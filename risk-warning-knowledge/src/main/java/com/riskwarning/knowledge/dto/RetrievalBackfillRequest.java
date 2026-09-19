package com.riskwarning.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Data;

import java.util.Locale;

/** 检索字段回填请求；索引必须由调用方显式提供。 */
@Data
public class RetrievalBackfillRequest {
    public enum Target {
        INDICATOR, REGULATION, BOTH;

        @JsonCreator
        public static Target fromJson(String value) {
            return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private String indicatorIndex;
    private String regulationIndex;
    private Target target = Target.BOTH;
    private boolean force;
}
