package com.riskwarning.common.dto.project;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectCreateRequest {
    @NotNull
    @Positive
    private Long enterpriseId;
    
    
    @NotBlank
    private String name;

    /**
     * REGULAR: 常规评估
     * SPECIAL: 专项评估
     */
    private String type;
    private String description;
    private LocalDate startDate;
    private LocalDate plannedCompletionDate;

    /**
     * 行业编码（字符串枚举），对应 com.riskwarning.common.enums.project.IndustryEnum.code。
     * 允许值：
     * - "SUPPLY_CHAIN", "MARKETING", "HR", "CROSS_BORDER",
     *   "DATA_PRIVACY", "ANTI_TRUST", "IP", "FINANCE_TAX"
     * 服务端可使用 IndustryEnum.fromCode(industry) 验证并获取中文展示值（dbValue）。
     */
    private String industry;
    /**
     * 地区编码（字符串枚举），对应 com.riskwarning.common.enums.RegionEnum.code。
     * 允许值：
     * - "CN" (CHINA), "US" (USA), "EU" (EUROPE), "MULTI" (MULTI_REGION)
     * 服务端可使用 RegionEnum.fromCode(region) 验证。
     */
    private String region;
    /**
     * 面向用户编码（字符串枚举），对应 com.riskwarning.common.enums.project.ProjectOrientedUserEnum.code。
     * 允许值：
     * - "GOVERNMENT", "SOE", "SUPPLIER", "CUSTOMER",
     *   "EMPLOYEE", "INDIVIDUAL", "PUBLIC"
     * 服务端可使用 ProjectOrientedUserEnum.fromCode(orientedUser) 验证并获取中文描述。
     */
    private String orientedUser;

}
