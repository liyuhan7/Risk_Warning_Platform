package com.riskwarning.common.aspect;


import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.JwtUtils;
import com.riskwarning.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.servlet.http.HttpServletRequest;

@Aspect
@Component
@Slf4j
public class AuthAspect {

    @Autowired
    private JwtUtils jwtUtils;

    @PersistenceContext
    private EntityManager em;

    @Around("@annotation(com.riskwarning.common.annotation.AuthRequired)")
    public Object aroundAuthRequired(ProceedingJoinPoint joinPoint) throws Throwable {
        UserContext.clear();
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes == null) {
            throw new BusinessException(401, "请求缺少认证上下文");
        }

        HttpServletRequest request = attributes.getRequest();

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).trim().isEmpty())
            throw new BusinessException(401, "缺少或错误的认证令牌");
        Long userId;
        try { userId = jwtUtils.getUserIdFromToken(authorization); }
        catch (RuntimeException invalidToken) { throw new BusinessException(401, "认证令牌无效或已过期"); }
        if (userId == null || userId <= 0) throw new BusinessException(401, "认证令牌缺少有效用户身份");

        log.debug("AuthAspect: 验证令牌用户身份 userId={}", userId);

        // 查询用户信息，放入UserContext
        User user = em.find(User.class, userId);
        if (user == null) {
            log.warn("AuthAspect: user not found for id={}, 禁止访问", userId);
            throw new BusinessException(401, "认证用户不存在，请重新登录");
        }

        UserContext.setUser(user);
        try {
            return joinPoint.proceed();
        } finally {
            UserContext.clear();
        }
    }

}
