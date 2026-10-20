package com.riskwarning.common.dto.scoring;

import lombok.Data;
import java.math.BigDecimal;
import java.util.*;

/** 本次运行冻结的共享事实及短编号，不跨行为复用观测值。 */
@Data
public class FactExtractionPlan {
    private String mode;
    private String catalogVersion;
    private String catalogHash;
    private String promptVersion;
    private String modelVersion;
    private int maxQuestions;
    private int maxInputCodePoints;
    private Map<String, BehaviorPlan> behaviors = new TreeMap<>();

    @Data
    public static class Definition {
        private String text;
        private String type;
        private String unit;
        private String scope;
    }

    @Data
    public static class Binding {
        private String indicatorId;
        private String requirementId;
        /** 为空时绑定要求的适用门。 */
        private String conditionId;
        private String factKey;
    }

    @Data
    public static class Question {
        private String id;
        private String factKey;
        private Definition definition;
        private List<String> legalContext = new ArrayList<>();
    }

    @Data
    public static class BehaviorPlan {
        private String behaviorId;
        private Map<String, String> evidenceReferences = new LinkedHashMap<>();
        private List<Question> questions = new ArrayList<>();
        private List<Binding> bindings = new ArrayList<>();
        private List<Batch> batches = new ArrayList<>();
        private List<String> oversizedQuestionIds = new ArrayList<>();
    }

    @Data
    public static class Batch {
        private int number;
        private List<String> questionIds = new ArrayList<>();
        private String prompt;
        private String contextHash;
    }

    /** 模型的事实答案；运行身份及指标身份只由服务端绑定。 */
    @Data
    public static class Answer {
        private String questionId;
        private String status;
        private Boolean booleanValue;
        private BigDecimal numberValue;
        private String unit;
        private String applicability;
        private List<String> evidenceIds = new ArrayList<>();
        private BigDecimal confidence;
        private String reason;
    }
}
