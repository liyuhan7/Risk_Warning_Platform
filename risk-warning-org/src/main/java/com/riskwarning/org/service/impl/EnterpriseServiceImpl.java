package com.riskwarning.org.service.impl;

import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.dto.UserResponse;
import com.riskwarning.common.dto.enterprise.EnterpriseUserResponse;
import com.riskwarning.common.enums.EnterpriseRole;
import com.riskwarning.common.po.enterprise.Enterprise;
import com.riskwarning.common.po.enterprise.EnterpriseUser;
import com.riskwarning.common.po.enterprise.EnterpriseUserId;
import com.riskwarning.common.po.user.User;
import com.riskwarning.org.repository.EnterpriseRepository;
import com.riskwarning.org.repository.EnterpriseUserRepository;
import com.riskwarning.org.service.EnterpriseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EnterpriseServiceImpl implements EnterpriseService {

    private final EnterpriseRepository enterpriseRepository;
    private final EnterpriseUserRepository enterpriseUserRepository;
    private final EntityManager em;

    public EnterpriseServiceImpl(EnterpriseRepository enterpriseRepository,
                                 EnterpriseUserRepository enterpriseUserRepository,
                                 EntityManager em) {
        this.enterpriseRepository = enterpriseRepository;
        this.enterpriseUserRepository = enterpriseUserRepository;
        this.em = em;
    }

    @Override
    @Transactional
    public Enterprise createEnterprise(Enterprise enterprise) {
        String creditCode = enterprise.getCreditCode();

        if (creditCode != null && enterpriseRepository.findByCreditCode(creditCode).isPresent()) {
            throw new BusinessException(409, "企业信用代码已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        enterprise.setCreatedAt(now);
        enterprise.setUpdatedAt(now);
        Enterprise saved = enterpriseRepository.save(enterprise);

        User currentUser = UserContext.getUser();
        Long ownerUserId = currentUser.getId();

        if (ownerUserId != null) {
            User owner = em.find(User.class, ownerUserId);
            if (owner == null) {
                throw new BusinessException(404, "企业创建用户不存在");
            }

            EnterpriseUserId id = new EnterpriseUserId();
            id.setEnterpriseId(saved.getId());
            id.setUserId(ownerUserId);

            EnterpriseUser eu = new EnterpriseUser();
            eu.setId(id);
            eu.setEnterprise(saved);
            eu.setUser(owner);
            eu.setRole(EnterpriseRole.admin);

            enterpriseUserRepository.save(eu);
        }

        return saved;
    }

    @Override
    @Transactional
    public void addUserToEnterprise(Long enterpriseId, Long userId, String role) {
        requirePositiveId(enterpriseId, "企业 ID");
        requirePositiveId(userId, "用户 ID");
        // 确保企业存在
        Enterprise enterprise = enterpriseRepository.findById(enterpriseId)
                .orElseThrow(() -> new BusinessException(404, "企业不存在"));

        // 获取 user 引用（如果不存在会返回 null）
        User user = em.find(User.class, userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        EnterpriseRole roleEnum = EnterpriseRole.from(role);

        EnterpriseUserId id = new EnterpriseUserId();
        id.setEnterpriseId(enterpriseId);
        id.setUserId(userId);

        EnterpriseUser eu = new EnterpriseUser();
        eu.setId(id);
        eu.setEnterprise(enterprise);
        eu.setUser(user);
        eu.setRole(roleEnum);

        enterpriseUserRepository.save(eu);
    }


    @Override
    @Transactional
    public java.util.List<Enterprise> getAllEnterprises() {
        return enterpriseRepository.findAll();
    }


    @Override
    @Transactional
    public List<EnterpriseUserResponse> getUsersByEnterprise(Long enterpriseId) {
        requirePositiveId(enterpriseId, "企业 ID");
        enterpriseRepository.findById(enterpriseId)
                .orElseThrow(() -> new BusinessException(404, "企业不存在"));

        List<EnterpriseUser> all = enterpriseUserRepository.findAll();

        return all.stream()
                .filter(eu -> {
                    if (eu == null) return false;
                    // 尝试通过关联实体判断；若 enterprise 为 null 则退到 embedded id 判断
                    if (eu.getEnterprise() != null) {
                        return enterpriseId.equals(eu.getEnterprise().getId());
                    }
                    EnterpriseUserId id = eu.getId();
                    return id != null && enterpriseId.equals(id.getEnterpriseId());
                })
                .map(eu -> {
                    User u = eu.getUser();
                    UserResponse ur = new UserResponse(
                            u.getId(),
                            u.getEmail(),
                            u.getFullName(),
                            u.getAvatarUrl(),
                            u.getCreatedAt(),
                            u.getUpdatedAt()
                    );
                    String roleCode = eu.getRole() == null ? null : eu.getRole().toString();
                    return new EnterpriseUserResponse(ur, roleCode);
                })
                .collect(Collectors.toList());
    }

    private void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, name + " 必须为正数");
        }
    }

}
