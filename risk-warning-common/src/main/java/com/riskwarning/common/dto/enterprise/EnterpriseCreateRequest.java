package com.riskwarning.common.dto.enterprise;


import java.math.BigDecimal;
import java.time.LocalDate;
import javax.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnterpriseCreateRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String creditCode;
    private String type;
    private String industry;
    private String businessScope;
    private BigDecimal registeredCapital;
    private LocalDate establishmentDate;
    private String legalRepresentative;
    private String registeredAddress;
    private String businessStatus;
}
