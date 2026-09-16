package com.riskwarning.knowledge.service;

import com.riskwarning.common.po.indicator.Indicator;
import com.riskwarning.common.po.regulation.Regulation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库检索文本构建器（P2-02 冻结模板）。
 *
 * <p>模板版本（P2-02-01 模板对照实验冻结）：
 * <ul>
 *   <li>ind-v1：指标 = name + "\n" + description + "\n" + tags（空白段跳过，tags 空格连接）</li>
 *   <li>reg-v1：法规 = name + "\n" + fullText（完整原文，不截断）</li>
 * </ul>
 *
 * <p>complianceDomain / regionCodes 是过滤维度，不进入检索文本；
 * RANGE/BINARY 等计算规则不影响文本生成。输出为确定性函数：同输入必同输出。
 *
 * <p>超长法规明确拒绝并记录，不静默截断唯一原文（计划 12 风险表）。
 */
@Component
public class RetrievalTextBuilder {

    public static final String INDICATOR_TEMPLATE_VERSION = "ind-v1";
    public static final String REGULATION_TEMPLATE_VERSION = "reg-v1";

    /** 法规检索文本字符上限；实测语料最大 215 字符，此为安全余量下的硬上限。 */
    public static final int MAX_REGULATION_TEXT_CHARS = 12000;

    private static final String SEGMENT_SEPARATOR = "\n";

    /**
     * 指标检索文本：name + description + tags（ind-v1）。
     * 空 description / tags 段跳过；name 缺失时按空段处理。
     */
    public String indicatorRetrievalText(Indicator indicator) {
        if (indicator == null) {
            throw new IllegalArgumentException("指标不可为空");
        }
        List<String> parts = new ArrayList<>();
        addNonBlank(parts, indicator.getName());
        addNonBlank(parts, indicator.getDescription());
        if (indicator.getTags() != null && !indicator.getTags().isEmpty()) {
            addNonBlank(parts, String.join(" ", indicator.getTags()));
        }
        return String.join(SEGMENT_SEPARATOR, parts);
    }

    /**
     * 法规检索文本：name + fullText（reg-v1）。
     *
     * @throws IllegalArgumentException 检索文本超过 {@link #MAX_REGULATION_TEXT_CHARS}，
     *                                  明确拒绝而非截断（超长项由回填阶段记录并排除）
     */
    public String regulationRetrievalText(Regulation regulation) {
        if (regulation == null) {
            throw new IllegalArgumentException("法规不可为空");
        }
        List<String> parts = new ArrayList<>();
        addNonBlank(parts, regulation.getName());
        addNonBlank(parts, regulation.getFullText());
        String text = String.join(SEGMENT_SEPARATOR, parts);
        if (text.length() > MAX_REGULATION_TEXT_CHARS) {
            throw new IllegalArgumentException(
                    "法规检索文本超长（" + text.length() + " > " + MAX_REGULATION_TEXT_CHARS
                            + " 字符），按计划规则明确拒绝、不静默截断原文");
        }
        return text;
    }

    private void addNonBlank(List<String> parts, String value) {
        if (value != null) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
    }
}