package com.riskwarning.org.service.impl;

import com.riskwarning.common.dto.*;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.JwtUtils;
import com.riskwarning.common.utils.PasswordUtils;
import com.riskwarning.org.repository.UserRepository;
import com.riskwarning.org.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordUtils passwordUtils;
    private final JwtUtils jwtUtils;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest registerRequest) {
        if (registerRequest == null) throw new IllegalArgumentException("注册信息不能为空");
        // 检查邮箱是否已存在
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new BusinessException(409, "邮箱已被注册");
        }

        // 创建用户
        User user = new User();
        user.setEmail(registerRequest.getEmail());
        user.setPassword(passwordUtils.encodePassword(registerRequest.getPassword()));
        user.setFullName(registerRequest.getFullName());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        // 保存用户
        User savedUser = userRepository.save(user);

        // 转换为响应DTO
        return convertToUserResponse(savedUser);
    }

    @Override
    public LoginResponse login(LoginRequest loginRequest) {
        if (loginRequest == null) throw new IllegalArgumentException("登录信息不能为空");
        // 根据邮箱查找用户
        User user = userRepository.findByEmail(loginRequest.getEmail())
                .orElseThrow(() -> new BusinessException(401, "用户不存在"));

        // 验证密码
        if (!passwordUtils.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "密码错误");
        }

        // 生成JWT token
        String token = jwtUtils.generateToken(user.getId(), user.getEmail(), user.getFullName());

        // 返回登录响应
        UserResponse userResponse = convertToUserResponse(user);
        return new LoginResponse(token, userResponse);
    }

    @Override
    public UserResponse getUserById(Long userId) {
        requireUserId(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        return convertToUserResponse(user);
    }

    @Override
    public UserResponse getUserByEmail(String email) {
        if (email == null || email.trim().isEmpty()) throw new IllegalArgumentException("邮箱不能为空");
        User user=userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
            return convertToUserResponse(user);

    }

    @Override
    @Transactional
    public UserResponse updateUser(Long userId, UserResponse updateRequest) {
        requireUserId(userId);
        if (updateRequest == null) throw new IllegalArgumentException("更新信息不能为空");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));

        // 更新用户信息
        if (updateRequest.getFullName() != null) {
            user.setFullName(updateRequest.getFullName());
        }
        if (updateRequest.getAvatarUrl() != null) {
            user.setAvatarUrl(updateRequest.getAvatarUrl());
        }
        user.setUpdatedAt(LocalDateTime.now());

        // 保存更新
        User savedUser = userRepository.save(user);
        return convertToUserResponse(savedUser);
    }

    @Override
    public boolean validatePassword(User user, String rawPassword) {
        return passwordUtils.matches(rawPassword, user.getPassword());
    }

    /**
     * 转换User实体到UserResponse DTO
     */
    private UserResponse convertToUserResponse(User user) {
        UserResponse response = new UserResponse();
        BeanUtils.copyProperties(user, response);
        return response;
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("用户 ID 必须为正数");
    }




}
