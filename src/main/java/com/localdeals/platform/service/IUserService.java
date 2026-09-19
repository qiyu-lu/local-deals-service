package com.localdeals.platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.localdeals.platform.dto.LoginFormDTO;
import com.localdeals.platform.dto.Result;
import com.localdeals.platform.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    public Result sendCode(String phone, HttpSession session);
    public Result login(LoginFormDTO loginForm, HttpSession session);
    public Result me();

    Result logout(String token);

    Result sign();

    Result signCount();
}
