package com.riskwarning.common.dto.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 检索阶段的元数据过滤条件（P2 检索契约 filter-v1）。
 *
 * <p>过滤语义来自冻结决策（D-08 / P2-07 消融记录，检索表达式在
 * p2-00-rag-execution-plan.md 5.x 冻结）：
 * <ul>
 *   <li>行业：候选 complianceDomain 与企业行业标签闭包交集非空即保留；
 *       候选为综合类（仅含「综合类」且无其他领域值）时豁免；
 *       企业或候选任一侧领域缺失时不过滤（缺失不过滤规则）。</li>
 *   <li>地区：候选 regionCodes 含项目地域代码，或含 GLOBAL/MULTI/UNKNOWN 之一即保留；
 *       projectRegionCodes 或候选 regionCodes 缺失时不过滤。</li>
 * </ul>
 *
 * <p>enabled 默认 false：P2-07 消融结论为硬过滤不启用；初评阶段通过消融开关
 * 对照过滤前后 Gold 留存率后，再由评测结论决定是否启用。
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalFilter {

    public static final String FILTER_VERSION = "filter-v1";

    /** 企业行业标签闭包；空集合表示企业背景缺失，按缺失不过滤处理。 */
    private Set<String> enterpriseIndustryTags;

    /** 项目地域代码集合；空集合表示项目地域缺失，按缺失不过滤处理。 */
    private Set<String> projectRegionCodes;

    /** false 表示完全不过滤（P2-07 消融结论的默认状态）。 */
    private boolean enabled;

    /** 完全不过滤的检索条件。 */
    public static RetrievalFilter none() {
        return RetrievalFilter.builder()
                .enterpriseIndustryTags(Collections.emptySet())
                .projectRegionCodes(Collections.emptySet())
                .enabled(false)
                .build();
    }

    /** 按冻结语义构造检索条件；null 入参按空集合处理（缺失不过滤）。 */
    public static RetrievalFilter of(Set<String> enterpriseIndustryTags,
                                     Set<String> projectRegionCodes) {
        return RetrievalFilter.builder()
                .enterpriseIndustryTags(copyOrEmpty(enterpriseIndustryTags))
                .projectRegionCodes(copyOrEmpty(projectRegionCodes))
                .enabled(true)
                .build();
    }

    private static Set<String> copyOrEmpty(Set<String> source) {
        return source == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}