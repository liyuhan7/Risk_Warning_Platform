package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ClassifierClient;
import com.riskwarning.processing.dto.ClassificationResult;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 为抽取事实补齐分类，并使用稳定 ID 写入 t_behavior。
 * 分类失败时阻止整批写入，由事实处理任务重试，避免留下不完整事实。
 */
@Service
@Slf4j
public class StructuredBehaviorWriter {

    private final BehaviorDocumentRepository behaviorDocumentRepository;
    private final ClassifierClient classifierClient;

    public StructuredBehaviorWriter(BehaviorDocumentRepository behaviorDocumentRepository,
                                    ClassifierClient classifierClient) {
        this.behaviorDocumentRepository = behaviorDocumentRepository;
        this.classifierClient = classifierClient;
    }

    public void enrichAndWrite(List<Behavior> behaviors) {
        if (behaviors == null) {
            throw new IllegalArgumentException("Behavior 列表不能为空");
        }
        if (behaviors.isEmpty()) {
            return;
        }
        classify(behaviors);
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

    private void requireWritableBehavior(Behavior behavior) {
        if (behavior == null || behavior.getId() == null || behavior.getId().trim().isEmpty()
                || behavior.getDescription() == null || behavior.getDescription().trim().isEmpty()) {
            throw new IllegalArgumentException("Behavior 缺少稳定 ID 或描述");
        }
    }
}
