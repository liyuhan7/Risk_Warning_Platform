package com.riskwarning.processing.service;

import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ClassifierClient;
import com.riskwarning.processing.config.AnalysisProcessingProperties;
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
    private final AnalysisProcessingProperties properties;

    public StructuredBehaviorWriter(BehaviorDocumentRepository behaviorDocumentRepository,
                                    ClassifierClient classifierClient,
                                    AnalysisProcessingProperties properties) {
        this.behaviorDocumentRepository = behaviorDocumentRepository;
        this.classifierClient = classifierClient;
        this.properties = properties;
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
        int batchSize = properties.getBatchSize();
        if (batchSize <= 0) {
            throw new IllegalStateException("事实分类批量大小必须大于零");
        }
        for (Behavior behavior : behaviors) {
            requireWritableBehavior(behavior);
        }
        // 分类服务逐条推理，限制单次请求规模；所有批次成功后才统一写入。
        int totalBatches = (behaviors.size() - 1) / batchSize + 1;
        for (int start = 0; start < behaviors.size(); start += batchSize) {
            int end = Math.min(start + batchSize, behaviors.size());
            log.info("事实分类批次：batch={}/{}, size={}, total={}",
                    start / batchSize + 1, totalBatches, end - start, behaviors.size());
            classifyBatch(behaviors.subList(start, end));
        }
    }

    private void classifyBatch(List<Behavior> behaviors) {
        List<Map<String, String>> items = new ArrayList<>();
        for (Behavior behavior : behaviors) {
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
