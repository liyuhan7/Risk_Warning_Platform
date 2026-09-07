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
            evidences.append("[E-").append(index + 1).append("]\n")
                    .append("id: ").append(chunk.getId()).append('\n')
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

    private String readTemplate() {
        ClassPathResource resource = new ClassPathResource(
                "fact-extraction/fact-extraction-v1.0.txt");
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
