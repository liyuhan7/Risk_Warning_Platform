package com.riskwarning.common.dto.fact;

import com.riskwarning.common.po.behavior.Behavior;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 事实抽取结果；严格模式下存在失败项时由服务携带本对象抛出异常。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FactExtractionResult {

    private List<Behavior> behaviors;
    private List<FactExtractionFailure> failures;
    private FactExtractionCallMetadata metadata;
}
