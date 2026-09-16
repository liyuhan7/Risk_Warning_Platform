package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BehaviorQueryTextBuilder 黄金样本单测（query-v1）：
 * 常规拼接、空段、空白归一化、dimension 不进入文本、确定性。
 */
class BehaviorQueryTextBuilderTest {

    private final BehaviorQueryTextBuilder builder = new BehaviorQueryTextBuilder();

    private Behavior behavior(String description, String action, String object, String dimension) {
        return Behavior.builder()
                .description(description)
                .action(action)
                .object(object)
                .dimension(dimension)
                .build();
    }

    @Test
    void queryTextJoinsDescriptionActionObject() {
        Behavior behavior = behavior("完成资质审查", "续签框架合同", "12家一级供应商", "企业信用风险");
        assertEquals("完成资质审查 续签框架合同 12家一级供应商",
                builder.buildQueryText(behavior));
    }

    @Test
    void queryTextSkipsNullSegments() {
        Behavior behavior = behavior("完成资质审查", null, null, null);
        assertEquals("完成资质审查", builder.buildQueryText(behavior));
    }

    @Test
    void queryTextCollapsesWhitespaceAndTrims() {
        Behavior behavior = behavior("  完成  资质审查 ", " 续签\n框架合同 ", " \t12家 ", null);
        assertEquals("完成 资质审查 续签 框架合同 12家", builder.buildQueryText(behavior));
    }

    @Test
    void queryTextIgnoresUnreliableDimensionField() {
        Behavior a = behavior("完成资质审查", "续签框架合同", "12家", "企业信用风险");
        Behavior b = behavior("完成资质审查", "续签框架合同", "12家", "产品合规风险");
        assertEquals(builder.buildQueryText(a), builder.buildQueryText(b));
    }

    @Test
    void queryTextIsDeterministic() {
        Behavior behavior = behavior("完成资质审查", "续签框架合同", "12家", "企业信用风险");
        assertEquals(builder.buildQueryText(behavior), builder.buildQueryText(behavior));
    }

    @Test
    void queryTextAllEmptySegmentsYieldsEmptyString() {
        assertEquals("", builder.buildQueryText(behavior(null, null, null, null)));
    }

    @Test
    void queryTextRejectsNullBehavior() {
        assertThrows(IllegalArgumentException.class, () -> builder.buildQueryText(null));
    }

    @Test
    void queryTemplateVersionIsFrozen() {
        assertEquals("query-v1", BehaviorQueryTextBuilder.QUERY_TEMPLATE_VERSION);
    }
}