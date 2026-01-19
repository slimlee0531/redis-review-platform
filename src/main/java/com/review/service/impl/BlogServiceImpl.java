package com.review.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.review.dto.Result;
import com.review.dto.ScrollResult;
import com.review.dto.UserDTO;
import com.review.entity.Blog;
import com.review.entity.Follow;
import com.review.entity.User;
import com.review.mapper.BlogMapper;
import com.review.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.service.IFollowService;
import com.review.service.IUserService;
import com.review.utils.SystemConstants;
import com.review.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import static com.review.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.review.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IFollowService followService;
    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户 (也就是该blog的作者)
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存探店博文
        boolean saved = blogService.save(blog);
        if (!saved) {
            return Result.fail("新增笔记失败！");
        }
        // 3. 查询作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> followList = followService.query().eq("follow_user_id", user.getId()).list();
        // 4. 推送笔记 id 给所有粉丝
        for (Follow follow : followList) {
            // 4.1. 获取粉丝 id
            Long userId = follow.getUserId();
            // 4.2. 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 热门博客
     * @param current
     * @return
     */
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 赋值用户信息   赋值帖子是否被当前用户点赞过
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    public Result queryBlogById(Long id) {
        // 1. 查询 blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！！");
        }
        // 2. 查询 blog 有关的用户
        queryBlogUser(blog);
        // 3. 判断当前 blog 是否被当前用户点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return;
        }
        Long userId = userDTO.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 笔记点赞
     * @param id
     * @return
     */
    public Result likeBlog(Long id) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            // 3. 如果点赞过，取消点赞
            boolean updated = update().setSql("liked = liked - 1").eq("id", id).update();
            // 把用户从 redis 的 zset 集合中移除
            if (updated) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            // 4. 如果没点赞过，可以点
            boolean updated = update().setSql("liked = liked + 1").eq("id", id).update();
            // 保存用户到 redis的 zset集合  zadd key value score
            if (updated) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1. 查询 top5 的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2. 解析出期中的用户 id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3. 根据用户 id 查询用户 WHERE id IN (5, 1) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOList = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    /**
     * 查询关注列表idol的博客
     * @param max 当前时间戳
     * @param offset 偏移量
     * @return 博客列表
     */
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询收件箱 ZREVRANGEBYSCORE key Max Min (WITHSCORES) LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate
                .opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3. 非空判断
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 4. 解析数据 blogId minTime offset
        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0;
        int sameTimeCount = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            // 获取笔记 id
            ids.add(Long.valueOf(Objects.requireNonNull(tuple.getValue())));
            // 获取时间戳
            long time = Objects.requireNonNull(tuple.getScore()).longValue();
            if (time == minTime) {
                sameTimeCount ++;
            } else {
                minTime = time;
                sameTimeCount = 1;
            }
        }
        int nextOffset = minTime == max ? sameTimeCount + offset : sameTimeCount;
        // 5. 根据 id 查询 blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogList = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();

        blogList.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setOffset(nextOffset);
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    /**
     * 封装博客作者信息
     * @param blog 博客
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
