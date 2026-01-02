package com.review.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.dto.LoginFormDTO;
import com.review.dto.Result;
import com.review.dto.UserDTO;
import com.review.entity.User;
import com.review.mapper.UserMapper;
import com.review.service.IUserService;
import com.review.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.query;
import static com.review.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号码
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4. 保存验证码到 Session
        session.setAttribute("code", code);
        // TODO 5. 发送验证码
        log.info("发送短信验证码成功，验证码:{}", code);

        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 2. 校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误");
        }
        // 3. 均校验成功，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 4. 判断用户是否存在
        if (user == null) {
            // 5. 若不存在，创建新用户保存到数据库
            user = createUserWithPhone(phone);
        }
        // 6. 保存用户信息到 Session
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        session.setAttribute("user", userDTO);
        return Result.ok(user);
    }

    private User createUserWithPhone(String phone) {
        log.info("创建新用户:{}", phone);
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
//        user.setCreateTime(LocalDateTime.now());
        // 2. 保存用户
        save(user);
        return user;
    }
}
