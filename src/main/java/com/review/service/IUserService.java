package com.review.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.review.dto.LoginFormDTO;
import com.review.dto.Result;
import com.review.entity.User;

import javax.servlet.http.HttpSession;


public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

}
