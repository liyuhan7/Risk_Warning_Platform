package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.*;
import com.riskwarning.common.dto.scoring.ScoringJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** 将已核验规则发布为本地 TEST 版本；不切换运行服务，也不发布生产规则。 */
public final class ScoringRulePublicationTool {
    public static Map<String, Object> publish(Path reviewed, Path datasetDirectory, Path output) throws Exception {
        Map<String, Object> report = ScoringRegistryValidator.validate(reviewed, datasetDirectory, true);
        byte[] input = Files.readAllBytes(reviewed);
        JsonNode document = new ObjectMapper().readTree(input);
        if (!"TEST".equals(document.path("purpose").asText()) || ((Number)report.get("enabledIndicators")).intValue() == 0)
            throw new IllegalArgumentException("TEST 发布至少需要一个已经核验并启用的指标");
        if (document.path("registryVersion").asText().startsWith("draft-"))
            throw new IllegalArgumentException("发布规则必须使用正式 TEST 版本号");
        // 在任何文件写入前完成业务校验，避免把半成品称为可用版本。
        if (Files.exists(output)) throw new IllegalArgumentException("版本输出目录已存在，请使用新版本目录");
        report.put("purpose", "TEST"); report.put("registryHash", ScoringJson.sha256(input));
        report.put("inputFile", reviewed.toAbsolutePath().normalize().toString());
        report.put("runtimeEnabled", false);
        Files.createDirectories(output);
        Files.write(output.resolve("rules.json"), input, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("validation-report.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        return report;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("用法：已核验TEST注册表 数据目录 新版本输出目录");
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                publish(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]))));
    }
    private ScoringRulePublicationTool() { }
}
