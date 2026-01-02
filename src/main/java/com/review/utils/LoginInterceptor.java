package com.review.utils;

import cn.hutool.core.bean.BeanUtil;
import com.review.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    // 这里的 LoginInterceptor 是手动做的，不能用注解
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取 session | 获取请求头中的 token
//        HttpSession session = request.getSession();
        String token = request.getHeader("authorization");
        if (token.isEmpty()) {
            // 不存在，拦截，401
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        // 2. 基于 token 获取 Redis 中的用户
//        Object user = session.getAttribute("user");
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // 3. 判断用户是否存在
        if (userMap.isEmpty()) {
            // 4. 不存在，拦截，401
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        // 5. 将查询到的 Hash 数据转为 UserDTO 对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 5. 存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(userDTO);
        // 6. 刷新 token 有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 7. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }

}
