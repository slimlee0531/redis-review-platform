package com.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.dto.Result;
import com.review.entity.User;
import com.review.mapper.UserMapper;
import com.review.service.IUserService;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;


@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        return null;
    }

}
