package com.riskwarning.org.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.dto.*;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.result.Result;
import com.riskwarning.org.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

/**
 * 用户认证控制器
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 用户注册
     *
     * @param registerRequest 注册请求
     * @return 注册结果
     */
    @PostMapping("/register")
    public Result<UserResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        return Result.success(userService.register(registerRequest));
    }

    /**
     * 用户登录
     *
     * @param loginRequest 登录请求
     * @return ���录结果（包含token和用户信息）
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        return Result.success(userService.login(loginRequest));
    }

    /**
     * 获取当前用户信息
     *
     * @param request HTTP请求
     * @return 用户信息
     */
    @GetMapping("/profile")
    @AuthRequired
    public Result<UserResponse> getCurrentUser(HttpServletRequest request) {
        return Result.success(userService.getUserById(currentUserId()));
    }

    /**
     * 根据用户ID获取用户信息
     *
     * @param userId 用户ID
     * @return 用户��息
     */
    @GetMapping("/{userId}")
    public Result<UserResponse> getUserById(@PathVariable Long userId) {
        return Result.success(userService.getUserById(userId));
    }

    /**
     * 更新用户信息
     *
     * @param updateRequest 更新请求
     * @param request HTTP请求
     * @return 更新结果
     */
    @PutMapping("/profile")
    @AuthRequired
    public Result<UserResponse> updateProfile(@RequestBody UserResponse updateRequest,
                                                  HttpServletRequest request) {
        return Result.success(userService.updateUser(currentUserId(), updateRequest));
    }

    /**
     * 根据邮箱查询用户信息
     *
     * @param email 用户邮箱
     * @return 用户信息
     */
    @GetMapping("/email")
    public Result<UserResponse> getUserByEmail(@RequestParam String email) {
        return Result.success(userService.getUserByEmail(email));
    }

    private Long currentUserId() {
        if (UserContext.getUser() == null || UserContext.getUser().getId() == null || UserContext.getUser().getId() <= 0)
            throw new BusinessException(401, "请登录后再访问用户资料");
        return UserContext.getUser().getId();
    }


}




