package com.riskwarning.org.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.dto.enterprise.EnterpriseUserResponse;
import com.riskwarning.common.po.enterprise.Enterprise;
import com.riskwarning.common.dto.AddMemberRequest;
import com.riskwarning.common.dto.enterprise.EnterpriseCreateRequest;
import com.riskwarning.common.result.Result;
import com.riskwarning.org.service.EnterpriseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import javax.validation.Valid;


/**
 * 企业控制器
 */
@RestController
@RequestMapping("/enterprise")
public class EnterpriseController {

    private final EnterpriseService enterpriseService;

    public EnterpriseController(EnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
    }

    /**
     * 创建企业
     *
     * @param req 企业创建请求
     * @return 创建的企业
     */
    @PostMapping
    @AuthRequired
    public Result<Enterprise> createEnterprise(@Valid @RequestBody EnterpriseCreateRequest req) {
        Enterprise e = new Enterprise();
        e.setName(req.getName());
        e.setCreditCode(req.getCreditCode());
        e.setType(req.getType());

        e.setIndustry(req.getIndustry());
        e.setBusinessScope(req.getBusinessScope());
        e.setRegisteredCapital(req.getRegisteredCapital());
        e.setEstablishmentDate(req.getEstablishmentDate());
        e.setLegalRepresentative(req.getLegalRepresentative());
        e.setRegisteredAddress(req.getRegisteredAddress());
        e.setBusinessStatus(req.getBusinessStatus());

        Enterprise saved = enterpriseService.createEnterprise(e);
        return Result.success(saved);
    }


    /**
     * 将用户添加到企业
     * @param enterpriseId
     * @param req
     * @return
     */
    @PostMapping("/{enterpriseId}/members")
    public Result<Void> addMember(@PathVariable Long enterpriseId, @RequestBody AddMemberRequest req) {
        enterpriseService.addUserToEnterprise(enterpriseId, req.getUserId(), req.getRole());
        return Result.success();
    }

    /**
     * 获取所有企业
     * @return 企业列表
     */
    @GetMapping
    public Result<List<Enterprise>> getAllEnterprises() {
        return Result.success(enterpriseService.getAllEnterprises());
    }

    /**
     * 根据企业ID获取用户列表
     * @param enterpriseId 企业ID
     * @return 用户列表
     */
    @GetMapping("/{enterpriseId}/members")
    public Result<List<EnterpriseUserResponse>> getEnterpriseMembers(@PathVariable Long enterpriseId) {
        List<EnterpriseUserResponse> users = enterpriseService.getUsersByEnterprise(enterpriseId);
        return Result.success(users);
    }


}
