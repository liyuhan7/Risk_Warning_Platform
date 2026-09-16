package com.riskwarning.knowledge.service;

import com.riskwarning.common.po.indicator.Indicator;
import com.riskwarning.common.po.regulation.Regulation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalTextBuilder 黄金样本单测（计划 11 文本构造测试清单）：
 * 肯否定/计划态/RANGE、空值、多领域（complianceDomain）、地域编码、综合类、超长拒绝。
 */
class RetrievalTextBuilderTest {

    private final RetrievalTextBuilder builder = new RetrievalTextBuilder();

    private Indicator indicator(String name, String description, java.util.List<String> tags) {
        return Indicator.builder()
                .name(name)
                .description(description)
                .tags(tags)
                .build();
    }

    @Test
    void indicatorTemplateCombinesNameDescriptionAndTags() {
        Indicator indicator = indicator("供应商资质审查是否及时完成",
                "审查年度资质并续签框架合同", Arrays.asList("供应商管理", "资质审查"));
        assertEquals("供应商资质审查是否及时完成\n审查年度资质并续签框架合同\n供应商管理 资质审查",
                builder.indicatorRetrievalText(indicator));
    }

    @Test
    void indicatorEmptyDescriptionSegmentIsSkipped() {
        Indicator indicator = indicator("员工超时加班的发生率", "", Arrays.asList("用工管理"));
        assertEquals("员工超时加班的发生率\n用工管理", builder.indicatorRetrievalText(indicator));
    }

    @Test
    void indicatorEmptyTagsSegmentIsSkipped() {
        Indicator indicator = indicator("是否留存薪酬发放记录", "按月度留存", Collections.emptyList());
        assertEquals("是否留存薪酬发放记录\n按月度留存", builder.indicatorRetrievalText(indicator));
    }

    @Test
    void indicatorTemplateIgnoresRuleTypeAndDomainFields() {
        Indicator binary = indicator("虚假宣传行为预警效率", "存在预警记录即计分", null);
        binary.setCalculationRule(com.riskwarning.common.po.indicator.CalculationRule.builder()
                .ruleType(com.riskwarning.common.enums.RuleTypeEnum.BINARY).build());
        Indicator range = indicator("虚假宣传行为预警效率", "存在预警记录即计分", null);
        range.setCalculationRule(com.riskwarning.common.po.indicator.CalculationRule.builder()
                .ruleType(com.riskwarning.common.enums.RuleTypeEnum.RANGE).build());
        // 多领域 / 综合类 / 地域编码是过滤维度，不进入检索文本
        range.setComplianceDomain(Arrays.asList("综合类"));
        range.setRegion("CN");
        assertEquals(builder.indicatorRetrievalText(binary),
                builder.indicatorRetrievalText(range));
    }

    @Test
    void indicatorTemplateIsDeterministic() {
        Indicator indicator = indicator("关联交易价格公允性审查覆盖率", "覆盖全部关联交易",
                Arrays.asList("关联方", "定价公允"));
        assertEquals(builder.indicatorRetrievalText(indicator),
                builder.indicatorRetrievalText(indicator));
    }

    @Test
    void indicatorNullNameYieldsEmptyText() {
        assertEquals("", builder.indicatorRetrievalText(indicator(null, null, null)));
    }

    @Test
    void regulationTemplateCombinesNameAndFullText() {
        Regulation regulation = Regulation.builder()
                .name("《中华人民共和国劳动法》-工时制度规定")
                .fullText("延长工作时间每日不得超过三小时，每月不得超过三十六小时")
                .build();
        assertEquals("《中华人民共和国劳动法》-工时制度规定\n"
                        + "延长工作时间每日不得超过三小时，每月不得超过三十六小时",
                builder.regulationRetrievalText(regulation));
    }

    @Test
    void regulationMultiDomainAndRegionDoNotEnterText() {
        Regulation plain = Regulation.builder()
                .name("数据安全法-重要数据出境评估")
                .fullText("重要数据出境前完成安全评估")
                .build();
        Regulation multi = Regulation.builder()
                .name("数据安全法-重要数据出境评估")
                .fullText("重要数据出境前完成安全评估")
                .complianceDomain(Arrays.asList("企业信用风险", "企业国际合作风险"))
                .region("MULTI")
                .build();
        assertEquals(builder.regulationRetrievalText(plain),
                builder.regulationRetrievalText(multi));
    }

    @Test
    void regulationOverLengthIsRejectedRatherThanTruncated() {
        StringBuilder longText = new StringBuilder("超过上限的原文内容。");
        for (int i = 0; i < 2000; i++) {
            longText.append("超长法规原文内容。");
        }
        Regulation regulation = Regulation.builder()
                .name("超长法规").fullText(longText.toString()).build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> builder.regulationRetrievalText(regulation));
        assertTrue(ex.getMessage().contains("不静默截断原文"));
    }

    @Test
    void regulationTemplateIsDeterministic() {
        Regulation regulation = Regulation.builder()
                .name("个人信息保护法-跨境提供")
                .fullText("向境外提供个人信息应取得单独同意")
                .build();
        assertEquals(builder.regulationRetrievalText(regulation),
                builder.regulationRetrievalText(regulation));
    }

    @Test
    void templateVersionsAreFrozen() {
        assertEquals("ind-v1", RetrievalTextBuilder.INDICATOR_TEMPLATE_VERSION);
        assertEquals("reg-v1", RetrievalTextBuilder.REGULATION_TEMPLATE_VERSION);
    }
}