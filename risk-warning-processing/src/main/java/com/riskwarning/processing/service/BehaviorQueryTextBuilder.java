package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import org.springframework.stereotype.Component;

/**
 * 行为检索查询文本构建器（P2-02 冻结模板 query-v1）。
 *
 * <p>query-v1 = description + action + object，三段去空白后以单个空格连接。
 * dimension 不进入查询文本：行为维度来自后置分类器（P2-06 结论其不可靠），
 * 检索相关性以文本语义为准，与标注集查询文本口径一致。
 *
 * <p>输出为确定性函数：同输入必同输出；连续空白折叠为单空格。
 */
@Component
public class BehaviorQueryTextBuilder {

    public static final String QUERY_TEMPLATE_VERSION = "query-v1";

    /**
     * 构建行为检索查询文本。
     *
     * @param behavior 行为；description/action/object 均可为 null（按空段处理）
     * @return 非空查询文本；三个字段全空时返回空字符串
     */
    public String buildQueryText(Behavior behavior) {
        if (behavior == null) {
            throw new IllegalArgumentException("行为不可为空");
        }
        StringBuilder raw = new StringBuilder();
        appendPart(raw, behavior.getDescription());
        appendPart(raw, behavior.getAction());
        appendPart(raw, behavior.getObject());
        return raw.toString().trim();
    }

    private void appendPart(StringBuilder raw, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (!normalized.isEmpty()) {
            if (raw.length() > 0) {
                raw.append(' ');
            }
            raw.append(normalized);
        }
    }
}