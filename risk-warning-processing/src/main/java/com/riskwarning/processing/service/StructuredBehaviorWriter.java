package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ClassifierClient;
import com.riskwarning.processing.client.VectorizationClient;
import com.riskwarning.processing.dto.ClassificationResult;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 为新抽取事实补齐分类与向量，并使用稳定 ID 写入 t_behavior。
 * 分类或向量任一环节失败都必须失败整批写入：无向量的 Behavior
 * 会让 LEGACY 指标链无法计算候选，而 Indicator 任务重试无法补齐向量，
 * 只有让 Behavior Durable Work 重试才能恢复。
 */
@Service
@Slf4j
public class StructuredBehaviorWriter {

    private final BehaviorDocumentRepository behaviorDocumentRepository;
    private final ClassifierClient classifierClient;
    private final VectorizationClient vectorizationClient;

    public StructuredBehaviorWriter(BehaviorDocumentRepository behaviorDocumentRepository,
                                    ClassifierClient classifierClient,
                                    VectorizationClient vectorizationClient) {
        this.behaviorDocumentRepository = behaviorDocumentRepository;
        this.classifierClient = classifierClient;
        this.vectorizationClient = vectorizationClient;
    }

    public void enrichAndWrite(List<Behavior> behaviors) {
        if (behaviors == null) {
            throw new IllegalArgumentException("Behavior 列表不能为空");
        }
        if (behaviors.isEmpty()) {
            return;
        }
        classify(behaviors);
        vectorize(behaviors);
        requireVectors(behaviors);
        behaviorDocumentRepository.writeAll(behaviors);
    }

    private void classify(List<Behavior> behaviors) {
        List<Map<String, String>> items = new ArrayList<>();
        for (Behavior behavior : behaviors) {
            requireWritableBehavior(behavior);
            Map<String, String> item = new HashMap<>();
            item.put("text", behavior.getDescription());
            item.put("input_type", "behavior");
            items.add(item);
        }
        Map<String, Object> request = new HashMap<>();
        request.put("items", items);
        Map<String, Object> response = classifierClient.classifyBatch(request);
        if (response == null) {
            throw new IllegalStateException("分类服务返回空响应");
        }
        ClassificationResult result = ClassificationResult.fromMap(response);
        if (result.getResults() == null || result.getResults().size() != behaviors.size()) {
            throw new IllegalStateException("分类结果数量与 Behavior 数量不一致");
        }
        for (int index = 0; index < behaviors.size(); index++) {
            ClassificationResult.ItemResult item = result.getResults().get(index);
            if (item == null) {
                throw new IllegalStateException("分类结果包含空项");
            }
            if (item.getType() == null || item.getType().trim().isEmpty()
                    || item.getDimension() == null || item.getDimension().trim().isEmpty()) {
                throw new IllegalStateException("分类结果缺少 type 或 dimension");
            }
            behaviors.get(index).setTags(item.getTags());
            behaviors.get(index).setType(item.getType());
            behaviors.get(index).setDimension(item.getDimension());
        }
    }

    /** 向量化属于写入前置条件：任何失败都阻止 ES 写入并向上抛出。 */
    private void vectorize(List<Behavior> behaviors) {
        List<String> texts = new ArrayList<>();
        for (Behavior behavior : behaviors) {
            texts.add(behavior.getDescription());
        }
        Map<String, List<String>> request = new HashMap<>();
        request.put("texts", texts);
        Map<String, Object> response;
        try {
            response = vectorizationClient.batchVectorize(request);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("向量化服务调用失败，Behavior 不写入", exception);
        }
        if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
            throw new IllegalStateException("向量化服务未成功，Behavior 不写入");
        }
        @SuppressWarnings("unchecked")
        List<List<Number>> vectors = (List<List<Number>>) response.get("vectors");
        if (vectors == null || vectors.size() != behaviors.size()) {
            throw new IllegalStateException("向量数量与 Behavior 数量不一致，Behavior 不写入");
        }
        for (int index = 0; index < behaviors.size(); index++) {
            List<Number> vector = vectors.get(index);
            if (vector == null || vector.isEmpty()) {
                throw new IllegalStateException("第 " + index + " 条向量为空，Behavior 不写入");
            }
            List<Float> values = new ArrayList<>(vector.size());
            for (Number number : vector) {
                float value = number == null ? Float.NaN : number.floatValue();
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    throw new IllegalStateException("第 " + index + " 条向量包含无效数值，Behavior 不写入");
                }
                values.add(value);
            }
            behaviors.get(index).setDescriptionVector(values);
        }
    }

    /** 写入前再校验一次向量，防止历史对象绕过 vectorize 进入写库路径。 */
    private void requireVectors(List<Behavior> behaviors) {
        for (int index = 0; index < behaviors.size(); index++) {
            Behavior behavior = behaviors.get(index);
            List<Float> vector = behavior.getDescriptionVector();
            if (vector == null || vector.isEmpty()) {
                throw new IllegalStateException(
                        "Behavior 缺少描述向量，禁止写入: behaviorId=" + behavior.getId());
            }
        }
    }

    private void requireWritableBehavior(Behavior behavior) {
        if (behavior == null || behavior.getId() == null || behavior.getId().trim().isEmpty()
                || behavior.getDescription() == null || behavior.getDescription().trim().isEmpty()) {
            throw new IllegalArgumentException("Behavior 缺少稳定 ID 或描述");
        }
    }
}
