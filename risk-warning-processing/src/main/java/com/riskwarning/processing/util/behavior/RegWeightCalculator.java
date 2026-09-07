package com.riskwarning.processing.util.behavior;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.regulation.Regulation;

import java.time.Duration;
import java.time.LocalDateTime;

public final class RegWeightCalculator {

    private RegWeightCalculator() {}

    /**
     * 根据适用主体（applicableSubject）计算权重
     * 权重分级策略：
     * - Tier 1 (1.0): 国家级监管主体
     * - Tier 2 (0.9): 金融机构、上市公司等高监管主体
     * - Tier 3 (0.8): 企业集团、商业银行等重点企业主体
     * - Tier 4 (0.7): 一般企业、用人单位等普通法人主体
     * - Tier 5 (0.6): 个人、劳动者等自然人主体
     * - Tier 6 (0.5): 其他/未分类主体
     */
    public static double getHierarchyWeight(Regulation reg) {
        if (reg == null || reg.getApplicableSubject() == null || reg.getApplicableSubject().trim().isEmpty()) {
            return 0.5;
        }
        String subject = reg.getApplicableSubject().trim();

        // Tier 1: 国家级监管主体 (1.0)
        if (subject.contains("国家") || subject.contains("国务院")) {
            return 1.0;
        }

        // Tier 2: 高监管主体 (0.9)
        if (subject.equals("上市公司") || subject.equals("发行人") ||
            subject.equals("金融机构") || subject.equals("商业银行") ||
            subject.equals("证券公司") || subject.equals("保险公司") ||
            subject.equals("银行业金融机构") || subject.equals("银行保险机构") ||
            subject.equals("关键信息基础设施运营者") || subject.equals("内幕信息知情人") ||
            subject.equals("内幕交易行为人") || subject.equals("操纵市场行为人")) {
            return 0.9;
        }

        // Tier 3: 重点企业主体 (0.8)
        if (subject.equals("企业集团") || subject.equals("中央企业") ||
            subject.equals("国有企业") || subject.equals("供应链主体") ||
            subject.equals("项目公司") || subject.equals("排放企业") ||
            subject.equals("网络运营者") || subject.equals("信托公司") ||
            subject.equals("征信机构") || subject.equals("支付机构") ||
            subject.equals("私募基金管理人") || subject.equals("数据处理者") ||
            subject.equals("个人信息处理者")) {
            return 0.8;
        }

        // Tier 4: 一般企业/法人主体 (0.7)
        if (subject.equals("企业") || subject.equals("用人单位") ||
            subject.equals("生产经营单位") || subject.equals("经营者") ||
            subject.equals("网络交易经营者") || subject.equals("网络交易平台经营者") ||
            subject.equals("生产企业") || subject.equals("出口商") ||
            subject.equals("劳务派遣单位") || subject.equals("用工单位") ||
            subject.equals("出口经营者") || subject.equals("进口经营者") ||
            subject.equals("进出口经营者") || subject.equals("进出口企业") ||
            subject.equals("出口企业") || subject.equals("建设单位") ||
            subject.equals("施工单位") || subject.equals("承包单位") ||
            subject.equals("特种设备使用单位") || subject.equals("特种设备生产单位") ||
            subject.equals("电子商务经营者") || subject.equals("互联网信息服务提供者") ||
            subject.equals("服务提供者") || subject.equals("经营机构") ||
            subject.equals("快递企业") || subject.equals("承运人") ||
            subject.equals("托运人") || subject.equals("排污单位") ||
            subject.equals("危险废物产生单位") || subject.equals("回收拆解企业") ||
            subject.equals("药品生产企业") || subject.equals("化妆品企业") ||
            subject.equals("食品生产者") || subject.equals("生产者") ||
            subject.equals("销售者") || subject.equals("制造商") ||
            subject.equals("生产商") || subject.equals("供应商") ||
            subject.equals("网络关键设备制造商") || subject.equals("App运营者") ||
            subject.equals("特许人") || subject.equals("收购人") ||
            subject.equals("广告主") || subject.equals("境内机构") ||
            subject.equals("单位") || subject.equals("组织") ||
            subject.equals("事故发生单位") || subject.equals("生产经营单位主要负责人")) {
            return 0.7;
        }

        // Tier 5: 自然人主体 (0.6)
        if (subject.equals("劳动者") || subject.equals("从业人员") ||
            subject.equals("职工") || subject.equals("工伤职工") ||
            subject.equals("投资者") || subject.equals("纳税人") ||
            subject.equals("纳税义务人") || subject.equals("持票人") ||
            subject.equals("外国投资者")) {
            return 0.6;
        }

        // Tier 6: 复合/其他主体 (0.5)
        return 0.5;
    }

    public static double getTimelinessWeight(Regulation reg, Behavior behavior) {
        // 法规发布时间缺失时无法判断时效；行为日期是可选字段（事实抽取在原文无明确日期时省略），
        // 任一日期缺失都返回中性权重，避免 Duration.between 收到 null 参数抛 NPE。
        if (reg == null || reg.getCreatedAt() == null
                || behavior == null || behavior.getBehaviorDate() == null) {
            return 0.5;
        }
        LocalDateTime created = reg.getCreatedAt();
        long years = Duration.between(created, behavior.getBehaviorDate()).toDays() / 365;
        return years <= 2 ? 0.7 : 0.3;
    }
}
