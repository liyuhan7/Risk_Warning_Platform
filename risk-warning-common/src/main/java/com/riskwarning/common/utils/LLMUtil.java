package com.riskwarning.common.utils;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.riskwarning.common.provider.AiChatProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 指标与行为的属性推断工具。
 *
 * 只负责 Prompt 构建与响应解析，实际调用经 {@link AiChatProvider} 完成，
 * 因此本类不持有端点、凭据或供应商专用逻辑。
 * 仅在 llm.enabled=true 时注册，与 Provider 的生效条件保持一致。
 */
@Component
@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true")
public class LLMUtil {

    private final AiChatProvider chatProvider;

    public LLMUtil(AiChatProvider chatProvider) {
        this.chatProvider = chatProvider;
    }

    /**
     * 预定义的标签分类列表
     * 基于业务流程和管理职能进行分类，涵盖企业运营的各个方面
     */
    private static final List<String> PREDEFINED_TAGS = Arrays.asList(
            // 财务管理类
            "财务指标", "资金使用", "财务报告", "成本控制", "收入增长",
            "流动性", "盈利能力", "偿债能力", "营运能力", "发展能力",

            // 业务运营类
            "合同签署", "交易管理", "投资管理", "市场营销", "产品销售",
            "运营效率", "客户关系管理", "供应商管理", "效率指标",

            // 人力资源类
            "人力资源", "人事管理", "薪酬管理", "员工发展", "组织效能",

            // 风险管控类
            "风险管理", "风险管控", "内部举报处理", "安全指标", "质量管控",

            // 合规治理类
            "法规遵循", "许可证管理", "内控制度执行", "审计管理",
            "信息披露", "股东大会", "治理结构",

            // 外部关系类
            "政府关系维护", "监管配合", "客户满意度", "市场表现",

            // 社会责任类
            "可持续发展", "ESG指标", "社会责任履行", "公益活动", "环境保护",

            // 创新发展类
            "技术创新", "产品创新", "数字化转型", "研发投入",

            // 制度建设类
            "合规培训", "制度建设", "流程优化", "标准化管理"
    );

    /**
     * 预定义的行业分类列表
     * 基于国民经济行业分类标准和主要业务领域
     */
    private static final List<String> PREDEFINED_INDUSTRIES = Arrays.asList(
            // 金融业
            "银行业", "证券业", "保险业", "信托业", "基金业", "期货业", "金融租赁",

            // 制造业
            "汽车制造", "电子制造", "机械制造", "化工制造", "纺织制造", "食品制造",

            "医药制造", "钢铁制造", "有色金属", "建材制造",

            // 信息技术
            "软件开发", "互联网", "电信运营", "数据服务", "人工智能", "云计算",

            // 能源行业
            "石油天然气", "电力", "煤炭", "新能源", "核能", "水电",

            // 房地产建筑
            "房地产", "建筑工程", "装饰装修", "物业管理",

            // 零售贸易
            "零售业", "批发业", "电子商务", "连锁经营",

            // 交通运输
            "航空运输", "铁路运输", "公路运输", "水路运输", "物流仓储",

            // 医疗健康
            "医疗服务", "医疗器械", "生物技术", "健康管理",

            // 教育文化
            "教育服务", "文化传媒", "出版印刷", "广告营销",

            // 农林牧渔
            "农业", "林业", "牧业", "渔业", "农产品加工",

            // 公共服务
            "公共事业", "环保服务", "咨询服务", "法律服务", "会计服务",

            // 其他
            "综合类"
    );

    /**
     * 预定义的行为类型列表
     */
    private static final List<String> behaviorTypes = Arrays.asList(
            "定性", "定量"
    );

    /**
     * 预定义的行为状态列表
     */
    private static final List<String> behaviorStatuses = Arrays.asList(
            "已完成", "进行中", "暂停", "终止"
    );

    /**
     * 预定义的风险维度列表
     */
    private static final List<String> dimensions = Arrays.asList(
            "企业关联方风险", "产品合规风险", "劳务合规风险", "企业信用风险", "企业国际合作风险", "供应链风险"
    );

    /**
     * 根据指标的自然语言描��推断tags
     *
     * @param indicatorName 指标名称
     * @return 推断出的标签列表
     */
    public List<String> inferTags(String indicatorName) {
        String prompt = buildTagsInferencePrompt(indicatorName);
        String response = chatProvider.chat(prompt);
        return parseTagsFromResponse(response);
    }

    /**
     * 批量推断多个文本的标签
     *
     * @param textList 需要推断标签的文本列表
     * @return 每个文本对应的标签列表，返回结果为List<List<String>>
     */
    public List<List<String>> inferTagsBatch(List<String> textList) {
        if (textList == null || textList.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        String prompt = buildBatchTagsInferencePrompt(textList);
        String response = chatProvider.chat(prompt);
        return parseBatchTagsFromResponse(response, textList.size());
    }

    /**
     * 构建用于标签推断的Prompt
     *
     * @param indicatorName 指标名称
     * @return 完整的Prompt字符串
     */
    private static String buildTagsInferencePrompt(String indicatorName) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("你是一个专业的指标分类专家。请根据给定的指标信息，从预定义的标签列表中选择最适合的标签。在其他开始前请你注意我的要求：你的回复一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n\n");

        promptBuilder.append("指标信息：\n");
        promptBuilder.append("名称：").append(indicatorName).append("\n");

        promptBuilder.append("可选标签列表：\n");
        for (int i = 0; i < PREDEFINED_TAGS.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(PREDEFINED_TAGS.get(i)).append("\n");
        }

        promptBuilder.append("\n请根据指标的名称和描述，选择最适合的标签（可以选择多个）。\n");
        promptBuilder.append("要求：\n");
        promptBuilder.append("1. 只能从上述预定义标签列表中选择\n");
        promptBuilder.append("2. 选择2-5个最相关的标签\n");
        promptBuilder.append("3. 输出格式必须是纯JSON数组，例如：[\"财务指标\", \"风险管理\"]\n");
        promptBuilder.append("4. 一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n");

        return promptBuilder.toString();
    }

    /**
     * 构建用于批量标签推断的Prompt
     *
     * @param textList 文本列表
     * @return 完整的Prompt字符串
     */
    private static String buildBatchTagsInferencePrompt(List<String> textList) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("你是一个专业的指标分类专家。请根据给定的多个文本信息，为每个文本从预定义的标签列表中选择最适合的标签。在其他开始前请你注意我的要求：你的回复一定不要包含任何其他文字或者你的分析过程，只输出JSON数组的数组\n\n");

        promptBuilder.append("需要分类的文本列表：\n");
        for (int i = 0; i < textList.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(textList.get(i)).append("\n");
        }

        promptBuilder.append("\n可选标签列表：\n");
        for (int i = 0; i < PREDEFINED_TAGS.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(PREDEFINED_TAGS.get(i)).append("\n");
        }

        promptBuilder.append("\n请为每个文本选择最适合的标签（每个文本可以选择多个标签）。\n");
        promptBuilder.append("要求：\n");
        promptBuilder.append("1. 只能从上述预定义标签列表中选择\n");
        promptBuilder.append("2. 每个文本选择2-5个最相关的标签\n");
        promptBuilder.append("3. 输出格式必须是JSON数组的数组，按顺序对应输入文本，例如：[[\"财务指标\", \"风险管理\"], [\"合规培训\", \"制度建设\"], [\"技术创新\"]]\n");
        promptBuilder.append("4. 一定不要包含任何其他文字或者你的分析过程，只输出JSON数组的数组\n");
        promptBuilder.append("5. 结果数组的长度必须与输入文本数量一致（").append(textList.size()).append("个）\n");

        return promptBuilder.toString();
    }

    /**
     * 根据指标的自然语言描述推断industry
     *
     * @param indicatorName 指标名称
     * @return 推断出的行业列表
     */
    public List<String> inferIndustry(String indicatorName) {
        String prompt = buildIndustryInferencePrompt(indicatorName);
        String response = chatProvider.chat(prompt);
        return parseIndustriesFromResponse(response);
    }

    /**
     * 批量推断行为类型（定性/定量）
     *
     * @param behaviorList 行为描述列表
     * @return 每个行为对应的类型列表，返回结果为List<String>
     */
    public List<String> inferBehaviorTypesBatch(List<String> behaviorList) {
        if (behaviorList == null || behaviorList.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        String prompt = buildBehaviorTypesInferencePrompt(behaviorList);
        String response = chatProvider.chat(prompt);
        return parseBehaviorTypesFromResponse(response, behaviorList.size());
    }

    /**
     * 批量推断行为状态（已完成/进行中/暂停/终止）
     *
     * @param behaviorList 行为描述列表
     * @return 每个行为对应的状态列表，返回结果为List<String>
     */
    public List<String> inferBehaviorStatusesBatch(List<String> behaviorList) {
        if (behaviorList == null || behaviorList.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        String prompt = buildBehaviorStatusesInferencePrompt(behaviorList);
        String response = chatProvider.chat(prompt);
        return parseBehaviorStatusesFromResponse(response, behaviorList.size());
    }

    /**
     * 批量推断行为风险维度
     *
     * @param behaviorList 行为描述列表
     * @return 每个行为对应的风险维度列表，返回结果为List<String>
     */
    public List<String> inferBehaviorDimensionsBatch(List<String> behaviorList) {
        if (behaviorList == null || behaviorList.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        String prompt = buildBehaviorDimensionsInferencePrompt(behaviorList);
        String response = chatProvider.chat(prompt);
        return parseBehaviorDimensionsFromResponse(response, behaviorList.size());
    }

    /**
     * 构建用于行业推断的Prompt
     *
     * @param indicatorName 指标名称
     * @return 完整的Prompt字符串
     */
    private static String buildIndustryInferencePrompt(String indicatorName) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("你是一个专业的行业分析师。请根据给定的指标名称，从预定义的行业列表中选择最适合的行业分类。在其他开始前请你注意我的要求：你的回复一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n\n");

        promptBuilder.append("指标信息：\n");
        promptBuilder.append("名称：").append(indicatorName).append("\n\n");

        promptBuilder.append("可选行业列表：\n");
        for (int i = 0; i < PREDEFINED_INDUSTRIES.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(PREDEFINED_INDUSTRIES.get(i)).append("\n");
        }

        promptBuilder.append("\n请根据指标名称选择最适合的行业分类（可以选择多个）。\n");
        promptBuilder.append("要求：\n");
        promptBuilder.append("1. 只能从上述预定义行业列表中选择\n");
        promptBuilder.append("2. 选择1-3个最相关的行业分类，如果涉及较多，可以在最后加上'综合类'\n");
        promptBuilder.append("3. 如果无法明确判断，选择'综合类'\n");
        promptBuilder.append("4. 输出格式必须是纯JSON数组，例如：[\"银行业\", \"证券业\"]\n");
        promptBuilder.append("5. 一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n");

        return promptBuilder.toString();
    }

    /**
     * 构建用于行为类型推断的Prompt
     *
     * @param behaviorList 行为描述列表
     * @return 完整的Prompt字符串
     */
    private static String buildBehaviorTypesInferencePrompt(List<String> behaviorList) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("你是一个专业的行为分析师。请根据给定的行为描述，判断每个行为是定性的还是定量的。在其他开始前请你注意我的要求：你的回复一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n\n");

        promptBuilder.append("需要分析的行为列表：\n");
        for (int i = 0; i < behaviorList.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(behaviorList.get(i)).append("\n");
        }

        promptBuilder.append("\n可选行为类型：\n");
        for (int i = 0; i < behaviorTypes.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(behaviorTypes.get(i)).append("\n");
        }

        promptBuilder.append("\n请为每个行为判断其类型。\n");
        promptBuilder.append("判断标准：\n");
        promptBuilder.append("- 定性：基于描述、特征、质量等主观判断的行为\n");
        promptBuilder.append("- 定量：基于数据、指标、测量等客观数值的行为\n");
        promptBuilder.append("要求：\n");
        promptBuilder.append("1. 只能从'定性'或'定量'中选择一个\n");
        promptBuilder.append("2. 输出格式必须是纯JSON数组，例如：[\"定量\", \"定性\", \"定量\"]\n");
        promptBuilder.append("3. 一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n");
        promptBuilder.append("4. 结果数组的长度必须与输入行为数量一致（").append(behaviorList.size()).append("个）\n");

        return promptBuilder.toString();
    }

    /**
     * 构建用于行为状态推断的Prompt
     *
     * @param behaviorList 行为描述列表
     * @return 完整的Prompt字符串
     */
    private static String buildBehaviorStatusesInferencePrompt(List<String> behaviorList) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("你是一个专业的行为状态分析师。请根据给定的行为描述，判断每个行为的执行状态。在其他开始前请你注意我的要求：你的回复一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n\n");

        promptBuilder.append("需要分析的行为列表：\n");
        for (int i = 0; i < behaviorList.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(behaviorList.get(i)).append("\n");
        }

        promptBuilder.append("\n可选行为状态：\n");
        for (int i = 0; i < behaviorStatuses.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(behaviorStatuses.get(i)).append("\n");
        }

        promptBuilder.append("\n请为每个行为判断其执行状态。\n");
        promptBuilder.append("判断标准：\n");
        promptBuilder.append("- 已完成：行为已经完全执行完毕，有明确的结果\n");
        promptBuilder.append("- 进行中：行为正在执行过程中，还在持续\n");
        promptBuilder.append("- 暂停：行为暂时停止，但可能会继续\n");
        promptBuilder.append("- 终止：行为被彻底停止，不会再继续\n");
        promptBuilder.append("要求：\n");
        promptBuilder.append("1. 只能从上述预定义状态列表中选择一个\n");
        promptBuilder.append("2. 输出格式必须是纯JSON数组，例如：[\"已完成\", \"进行中\", \"已完成\"]\n");
        promptBuilder.append("3. 一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n");
        promptBuilder.append("4. 结果数组的长度必须与输入行为数量一致（").append(behaviorList.size()).append("个）\n");

        return promptBuilder.toString();
    }

    /**
     * 构建用于行为风险维度推断的Prompt
     *
     * @param behaviorList 行为描述列表
     * @return 完整的Prompt字符串
     */
    private static String buildBehaviorDimensionsInferencePrompt(List<String> behaviorList) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("你是一个专业的风险分析师。请根据给定的行为描述，判断每个行为属于哪个风险维度。在其他开始前请你注意我的要求：你的回复一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n\n");

        promptBuilder.append("需要分析的行为列表：\n");
        for (int i = 0; i < behaviorList.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(behaviorList.get(i)).append("\n");
        }

        promptBuilder.append("\n可选风险维度：\n");
        for (int i = 0; i < dimensions.size(); i++) {
            promptBuilder.append((i + 1)).append(". ").append(dimensions.get(i)).append("\n");
        }

        promptBuilder.append("\n请为每个行为选择最适合的风险维度。\n");
        promptBuilder.append("风险维度说明：\n");
        promptBuilder.append("- 企业关联方风险：与关联企业、关联交易相关的风险\n");
        promptBuilder.append("- 产品合规风险：产品设计、生产、销售等合规相关的风险\n");
        promptBuilder.append("- 劳务合规风险：劳动关系、员工管理等合规相关的风险\n");
        promptBuilder.append("- 企业信用风险：企业信誉、信用等级等相关的风险\n");
        promptBuilder.append("- 企业国际合作风险：跨境业务、国际合作等相关的风险\n");
        promptBuilder.append("- 供应链风险：供应商管理、采购等供应链相关的风险\n");
        promptBuilder.append("要求：\n");
        promptBuilder.append("1. 只能从上述预定义风险维度列表中选择一个\n");
        promptBuilder.append("2. 输出格式必须是纯JSON数组，例如：[\"企业关联方风险\", \"产品合规风险\"]\n");
        promptBuilder.append("3. 一定不要包含任何其他文字或者你的分析过程，只输出JSON数组\n");
        promptBuilder.append("4. 结果数组的长度必须与输入行为数量一致（").append(behaviorList.size()).append("个）\n");

        return promptBuilder.toString();
    }

    /**
     * 从大模型响应中解析标签列表
     *
     * @param response 大模型的响应
     * @return 解析出的标签列表
     */
    private static List<String> parseTagsFromResponse(String response) {
        try {
            // 清理响应内容，移除可能的前后缀
            String cleanedResponse = response.trim();
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            cleanedResponse = cleanedResponse.substring(cleanedResponse.lastIndexOf("["));

            // 解析JSON数组
            JSONArray tagsArray = JSONUtil.parseArray(cleanedResponse);
            List<String> tags = new java.util.ArrayList<>();

            for (int i = 0; i < tagsArray.size(); i++) {
                String tag = tagsArray.getStr(i);
                // 验证标签是否在预定义列表中
                if (PREDEFINED_TAGS.contains(tag)) {
                    tags.add(tag);
                }
            }

            return tags;
        } catch (Exception e) {
            System.err.println("Failed to parse tags from response: " + response);
            e.printStackTrace();
            throw  new RuntimeException(e);
        }
    }

    /**
     * 从大模型响应中解析批量标签列表
     *
     * @param response 大模型的响应
     * @param expectedSize 期望的结果数量
     * @return 解析出的批量标签列表
     */
    private static List<List<String>> parseBatchTagsFromResponse(String response, int expectedSize) {
        try {
            // 清理响应内容，移除可能的前后缀
            String cleanedResponse = response.trim();
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            // 解析JSON数组的数组
            JSONArray batchArray = JSONUtil.parseArray(cleanedResponse);
            List<List<String>> batchResults = new java.util.ArrayList<>();

            for (int i = 0; i < batchArray.size(); i++) {
                JSONArray tagsArray = batchArray.getJSONArray(i);
                List<String> tags = new java.util.ArrayList<>();

                if (tagsArray != null) {
                    for (int j = 0; j < tagsArray.size(); j++) {
                        String tag = tagsArray.getStr(j);
                        // 验证标签是否在预定义列表中
                        if (PREDEFINED_TAGS.contains(tag)) {
                            tags.add(tag);
                        }
                    }
                }

                batchResults.add(tags);
            }

            // 如果返回的结果数量不足，用空列表补齐
            while (batchResults.size() < expectedSize) {
                batchResults.add(new java.util.ArrayList<>());
            }

            return batchResults;
        } catch (Exception e) {
            System.err.println("Failed to parse batch tags from response: " + response);
            e.printStackTrace();

            // 返回空的结果列表，长度与输入一致
            List<List<String>> fallbackResults = new java.util.ArrayList<>();
            for (int i = 0; i < expectedSize; i++) {
                fallbackResults.add(new java.util.ArrayList<>());
            }
            return fallbackResults;
        }
    }

    /**
     * 从大模型响应中解析行业列表
     *
     * @param response 大模型的响应
     * @return 解析出的行业列表
     */
    private static List<String> parseIndustriesFromResponse(String response) {
        try {
            // 清理响应内容，移除可能的前后缀
            String cleanedResponse = response.trim();
            if (cleanedResponse.startsWith("```json")) {
                cleanedResponse = cleanedResponse.substring(7);
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
            }
            cleanedResponse = cleanedResponse.trim();

            // 提取JSON数组部分
            cleanedResponse = cleanedResponse.substring(cleanedResponse.lastIndexOf("["));

            // 解析JSON数组
            JSONArray industriesArray = JSONUtil.parseArray(cleanedResponse);
            List<String> industries = new java.util.ArrayList<>();

            for (int i = 0; i < industriesArray.size(); i++) {
                String industry = industriesArray.getStr(i);
                // 验证行业是否在预定义列表中
                if (PREDEFINED_INDUSTRIES.contains(industry)) {
                    industries.add(industry);
                }
            }

            // 如果解析出的行业列表为空，返回默认行业
            if (industries.isEmpty()) {
                industries.add("综合类");
            }

            return industries;

        } catch (Exception e) {
            System.err.println("Failed to parse industries from response: " + response);
            e.printStackTrace();
            // 返回默认行业
            throw  new RuntimeException(e);
        }
    }

    /**
     * 从LLM响应中解析行为类型列表
     *
     * @param response LLM响应
     * @param expectedSize 期望的结果数量
     * @return 行为类型列表
     */
    private static List<String> parseBehaviorTypesFromResponse(String response, int expectedSize) {
        List<String> behaviorTypesList = new java.util.ArrayList<>();

        try {
            // 清理响应字符串
            String cleanedResponse = response.trim();

            // 移除可能的代码块标记
            cleanedResponse = cleanedResponse.replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            // 提取JSON数组部分
            cleanedResponse = cleanedResponse.substring(cleanedResponse.lastIndexOf("["));

            // 解析JSON数组
            JSONArray typesArray = JSONUtil.parseArray(cleanedResponse);

            for (int i = 0; i < typesArray.size(); i++) {
                String behaviorType = typesArray.getStr(i);
                // 验证是否在预定义行为类型列表中
                if (behaviorTypes.contains(behaviorType)) {
                    behaviorTypesList.add(behaviorType);
                }
            }

        } catch (Exception e) {
            System.err.println("解析行为类型响应时出错: " + e.getMessage());
        }

        // 如果解析结果数量不匹配，补充默认值
        while (behaviorTypesList.size() < expectedSize) {
            behaviorTypesList.add("定性");
        }

        return behaviorTypesList.subList(0, expectedSize);
    }

    /**
     * 从LLM响应中解析行为状态列表
     *
     * @param response LLM响应
     * @param expectedSize 期望的结果数量
     * @return 行为状态列表
     */
    private static List<String> parseBehaviorStatusesFromResponse(String response, int expectedSize) {
        List<String> behaviorStatusesList = new java.util.ArrayList<>();

        try {
            // 清理响应字符串
            String cleanedResponse = response.trim();

            // 移除可能的代码块标记
            cleanedResponse = cleanedResponse.replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            // 提取JSON数组部分
            cleanedResponse = cleanedResponse.substring(cleanedResponse.lastIndexOf("["));

            // 解析JSON数组
            JSONArray statusesArray = JSONUtil.parseArray(cleanedResponse);

            for (int i = 0; i < statusesArray.size(); i++) {
                String behaviorStatus = statusesArray.getStr(i);
                // 验证是否在预定义行为状态列表中
                if (behaviorStatuses.contains(behaviorStatus)) {
                    behaviorStatusesList.add(behaviorStatus);
                }
            }

        } catch (Exception e) {
            System.err.println("解析行为状态响应时出错: " + e.getMessage());
        }

        // 如果解析结果数量不匹配，补充默认值
        while (behaviorStatusesList.size() < expectedSize) {
            behaviorStatusesList.add("进行中");
        }

        return behaviorStatusesList.subList(0, expectedSize);
    }

    /**
     * 从LLM响应中解析行为风险维度列表
     *
     * @param response LLM响应
     * @param expectedSize 期望的结果数量
     * @return 风险维度列表
     */
    private static List<String> parseBehaviorDimensionsFromResponse(String response, int expectedSize) {
        List<String> behaviorDimensionsList = new java.util.ArrayList<>();

        try {
            // 清理响应字符串
            String cleanedResponse = response.trim();

            // 移除可能的代码块标记
            cleanedResponse = cleanedResponse.replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            // 提取JSON数组部分
            cleanedResponse = cleanedResponse.substring(cleanedResponse.lastIndexOf("["));

            // 解析JSON数组
            JSONArray dimensionsArray = JSONUtil.parseArray(cleanedResponse);

            for (int i = 0; i < dimensionsArray.size(); i++) {
                String behaviorDimension = dimensionsArray.getStr(i);
                // 验证是否在预定义风险维度列表中
                if (dimensions.contains(behaviorDimension)) {
                    behaviorDimensionsList.add(behaviorDimension);
                }
            }

        } catch (Exception e) {
            System.err.println("解析行为风险维度响应时出错: " + e.getMessage());
        }

        // 如果解析结果数量不匹配，补充默认值
        while (behaviorDimensionsList.size() < expectedSize) {
            behaviorDimensionsList.add("企业信用风险");
        }

        return behaviorDimensionsList.subList(0, expectedSize);
    }


    /**
     * 探活调用，用于确认 Provider 配置与凭据可用。
     *
     * @return 是否调用成功
     */
    public boolean testConnection() {
        try {
            String testResponse = chatProvider.chat("请回复：连接成功");
            return testResponse != null && !testResponse.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
