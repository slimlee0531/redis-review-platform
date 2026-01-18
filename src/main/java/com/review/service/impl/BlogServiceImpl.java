package com.review.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.review.dto.Result;
import com.review.entity.Blog;
import com.review.entity.User;
import com.review.mapper.BlogMapper;
import com.review.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.service.IUserService;
import com.review.utils.SystemConstants;
import com.review.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.Serializable;
import java.util.List;

import static com.review.utils.RedisConstants.BLOG_LIKED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            return;
        }
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
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
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isTrue(isMember)) {
            // 3. 如果点赞过，取消点赞
            boolean updated = update().setSql("liked = liked - 1").eq("id", id).update();
            // 把用户从 redis 的 set 集合中移除
            if (updated) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        } else {
            // 4. 如果没点赞过，可以点
            boolean updated = update().setSql("liked = liked + 1").eq("id", id).update();
            // 保存用户到 redis的 set集合
            if (updated) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }
        return Result.ok();
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
