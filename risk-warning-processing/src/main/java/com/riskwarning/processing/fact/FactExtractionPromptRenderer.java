package com.riskwarning.processing.fact;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** 将冻结 Prompt 与受校验的运行作用域、证据装配为单轮调用文本。 */
@Component
public class FactExtractionPromptRenderer {

    private final String template;

    public FactExtractionPromptRenderer() {
        this.template = readTemplate();
    }

    public String render(AnalysisScope scope, List<EvidenceChunk> evidenceChunks) {
        if (scope == null || evidenceChunks == null || evidenceChunks.isEmpty()) {
            throw new IllegalArgumentException("Prompt 装配需要运行作用域和非空证据批次");
        }
        StringBuilder evidences = new StringBuilder();
        for (int index = 0; index < evidenceChunks.size(); index++) {
            EvidenceChunk chunk = evidenceChunks.get(index);
            String referenceId = referenceId(index);
            evidences.append('[').append(referenceId).append("]\n")
                    .append("id: ").append(referenceId).append('\n')
                    .append("sourceFileName: ").append(chunk.getSourceFileName()).append('\n')
                    .append("pageNumber: ").append(chunk.getPageNumber()).append('\n')
                    .append("segmentIndex: ").append(chunk.getSegmentIndex()).append('\n')
                    .append("text: ").append(chunk.getText()).append("\n\n");
        }
        String scopeText = "projectId=" + scope.getProjectId()
                + ", assessmentId=" + scope.getAssessmentId()
                + ", analysisRunId=" + scope.getAnalysisRunId();
        return template
                .replace("{{SYSTEM_NOTE}}", "输入证据已由系统完成项目、评估和文件归属校验。")
                .replace("{{SCOPE}}", scopeText)
                .replace("{{EVIDENCES}}", evidences.toString().trim());
    }

    /** 短编号仅在当前排序后的证据批次内有效。 */
    public static String referenceId(int index) {
        return "E-" + (index + 1);
    }

    /** 将失败响应及字段校验反馈交给模型修正，证据原文仍是唯一事实依据。 */
    public String renderRetry(String originalPrompt, String previousResponse,
                              FactExtractionValidationResult validation, Set<String> allowedEvidenceIds) {
        StringBuilder prompt = new StringBuilder(originalPrompt);
        prompt.append("\n\n上一轮响应未通过契约校验。以下响应仅供修正，不能作为事实来源。\n")
                .append("<previous_response>\n")
                .append(previousResponse == null ? "" : previousResponse)
                .append("\n</previous_response>\n\n")
                .append("本批允许的 evidenceIds：").append(allowedEvidenceIds).append('\n')
                .append("只能逐字引用本批 id 字段，不得引用示例占位符或编造编号。\n字段校验反馈：\n");
        for (String detail : validation.errorDetails()) {
            prompt.append("- ").append(detail).append('\n');
        }
        return prompt.append("请依据本批原始证据修正上述错误，重新输出完整的 facts JSON 对象。")
                .append("保留所有有证据支持的事实和数值，不得只返回出错项，不得删除事实来绕过校验。")
                .append("日期不能完整表达时，省略 behaviorDate，将原始时间保留在 description；")
                .append("不得补造月初、月末或当前日期。仅输出 JSON，不输出解释或 Markdown。")
                .toString();
    }

    private String readTemplate() {
        ClassPathResource resource = new ClassPathResource(
                "fact-extraction/fact-extraction-v1.2.txt");
        try (InputStream input = resource.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Fact Extraction Prompt", exception);
        }
    }
}
