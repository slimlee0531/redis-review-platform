package com.review.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.review.dto.Result;
import com.review.dto.UserDTO;
import com.review.entity.Follow;
import com.review.mapper.FollowMapper;
import com.review.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.service.IUserService;
import com.review.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /**
     * 关注/取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        if (isFollow) {
            // 2. 关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean saved = save(follow);
            if (saved) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3. 取关 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean removed = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (removed) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 判断是否关注
     * @param followUserId
     * @return
     */
    public Result isFollow(Long followUserId) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    /**
     * 共同关注
     * @param id
     * @return
     */
    public Result followCommons(Long id) {
        // 1. 获取当前用户 id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2. 求交集
        String key2 = "follows:" + id;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3. 解析 id 集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4. 查询用户
        List<UserDTO> userDTOList = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

}
