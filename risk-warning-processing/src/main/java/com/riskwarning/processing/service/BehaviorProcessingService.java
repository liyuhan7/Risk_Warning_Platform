package com.riskwarning.processing.service;

import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.Indicator;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.riskwarning.common.po.regulation.Regulation;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.risk.RelatedBehavior;
import com.riskwarning.common.po.risk.RelatedIndicator;
import com.riskwarning.common.po.risk.RelatedRegulation;
import com.riskwarning.common.utils.KafkaUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.processing.entity.dto.DocumentProcessingResult;
import com.riskwarning.processing.repository.AssessmentRepository;
import com.riskwarning.processing.repository.IndicatorResultRepository;
import com.riskwarning.processing.util.behavior.FallbackCalculator;
import com.riskwarning.processing.util.behavior.QualitativeCalculator;
import com.riskwarning.processing.util.behavior.QuantitativeCalculator;
import com.riskwarning.processing.util.behavior.RegWeightCalculator;
import com.riskwarning.processing.util.behavior.SimilarityCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class BehaviorProcessingService {

    private static final double REG_APPLICABILITY_THRESHOLD = 0.15;

    private static final double REG_TO_INDICATOR_THRESHOLD = 0.2;

    private static final String BEHAVIOR_VECTOR_FIELD = "description_vector";

    private static final String INDICATOR_VECTOR_FIELD = "name_vector";

    private static final String REGULATION_VECTOR_FIELD = "full_text_vector";

    private static final Integer CANDIDATE_FETCH_SIZE = 200;

    // 行为取数空结果重试间隔（毫秒），避免行为刚写入未刷新导致的偶发空结果
    private static final long BEHAVIOR_FETCH_RETRY_INTERVAL_MS = 1000L;


    // 新增：注入 ElasticsearchClient 与索引名配置
    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private IndicatorResultRepository indicatorResultRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;


    @Autowired
    @Qualifier(value = "BehaviorProcessTaskThreadPool")
    private ThreadPoolTaskExecutor behaviorThreadPoolExecutor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private KafkaUtils kafkaUtils;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // 简化后：使用带相似度的候选项（Scored<T>），直接根据相似度与适用性判断是否影响指标
    public static class Scored<T> {
        private final T item;
        private final double sim;
        public Scored(T item, double sim) { this.item = item; this.sim = sim; }
        public T getItem() { return item; }
        public double getSim() { return sim; }
    }

    // 内部类：存储法规得分及其权重信息
    private static class RegulationScore {
        final String regulationId;
        final double score;
        final double hierarchyWeight;
        final double timelinessWeight;
        final double similarityWeight;

        RegulationScore(String regulationId, double score, double hierarchyWeight, double timelinessWeight, double similarityWeight) {
            this.regulationId = regulationId;
            this.score = score;
            this.hierarchyWeight = hierarchyWeight;
            this.timelinessWeight = timelinessWeight;
            this.similarityWeight = similarityWeight;
        }
    }

    /**
     * 内部类：用于存储单个 behavior 的计算结果
     * ✅ 只存储 DTO/基本类型，不存储 JPA Entity
     */
    private static class BehaviorCalculationResult {
        final String behaviorId;
        final Map<String, IndicatorMetadataDTO> indicatorMetadata;
        final DocumentProcessingResult.MappingResult result;

        BehaviorCalculationResult(String behaviorId,
                                  Map<String, IndicatorMetadataDTO> indicatorMetadata,
                                  DocumentProcessingResult.MappingResult result) {
            this.behaviorId = behaviorId;
            this.indicatorMetadata = indicatorMetadata;
            this.result = result;
        }
    }

    /**
     * DTO：指标元数据
     * ✅ 纯数据类，不包含 JPA Entity
     */
    private static class IndicatorMetadataDTO {
        final String indicatorEsId;
        final String name;
        final Integer indicatorLevel;
        final String dimension;
        final String type;
        final Double maxScore;

        IndicatorMetadataDTO(String indicatorEsId, String name, Integer indicatorLevel,
                             String dimension, String type, Double maxScore) {
            this.indicatorEsId = indicatorEsId;
            this.name = name;
            this.indicatorLevel = indicatorLevel;
            this.dimension = dimension;
            this.type = type;
            this.maxScore = maxScore;
        }
    }

    /**
     * 新方法：传入 projectId 和 assessmentId，从 ES 获取该项目的 behaviors，
     * 使用传入的 assessmentId 进行评估，不再创建新的 assessment
     *
     * 修改：使用批量处理方式，收集所有计算结果后批量更新，避免乐观锁冲突
     *
     * @param projectId 项目ID
     * @param assessmentId 评估ID（由调用方创建并传入）
     */
    public void processProjectBehaviors(Long userId, Long projectId, Long assessmentId) {
        if (userId == null || projectId == null || assessmentId == null) {
            throw new BusinessException("User ID, Project ID, and Assessment ID must be provided for behavior processing.");
        }
        updateAssessmentStatus(assessmentId, AssessmentStatusEnum.ASSESSING);
        log.info("[Process Project START] projectId={}, assessmentId={} (传入)", projectId, assessmentId);

        // 清理旧的指标计算结果，防止重复数据导致 NonUniqueResultException
        log.info("[Cleanup] 正在清理 assessmentId={} 的旧指标结果...", assessmentId);
        indicatorResultRepository.deleteByAssessmentId(assessmentId);
        log.info("[Cleanup] 清理完成");

        // 1. 从 ES 获取该项目的所有 behaviors
        List<Behavior> allBehaviors = fetchBehaviors(projectId); 
        if (allBehaviors == null || allBehaviors.isEmpty()) {
            throw new IllegalArgumentException("No behaviors found for projectId: " + projectId);
        }
        int totalBehaviorCount = allBehaviors.size();

        log.info("[Processing Behaviors] 开始进行指标计算分发，projectId={}, behaviorCount={}", projectId, totalBehaviorCount);
        
        log.info("[使用传入的 Assessment] assessmentId={}, projectId={}, behaviorCount={}",
                assessmentId, projectId, allBehaviors.size());

        // 2. 使用线程安全的 Map 收集计算结果
        Map<String, List<RelatedIndicator>> indicatorResultsMap = new ConcurrentHashMap<>();
        Map<String, IndicatorMetadataDTO> indicatorMetadataMap = new ConcurrentHashMap<>();
        
        // 3. 使用 CountDownLatch 等待所有行为处理完成
        CountDownLatch latch = new CountDownLatch(totalBehaviorCount);
        
        // 4. 统计处理数量
        AtomicInteger processedCount = new AtomicInteger(0);

        log.info("[Processing Start] 开始处理 {} 个行为，使用线程池大小：{}", 
                totalBehaviorCount, behaviorThreadPoolExecutor.getCorePoolSize());
        log.info("[ThreadPool Status] 核心线程数={}, 最大线程数={}, 队列容量={}, 当前活跃线程数={}, 队列大小={}", 
                behaviorThreadPoolExecutor.getCorePoolSize(),
                behaviorThreadPoolExecutor.getMaxPoolSize(),
                behaviorThreadPoolExecutor.getThreadPoolExecutor().getQueue().remainingCapacity(),
                behaviorThreadPoolExecutor.getActiveCount(),
                behaviorThreadPoolExecutor.getThreadPoolExecutor().getQueue().size());

        for (int i = 0; i < allBehaviors.size(); i++) {
            final Behavior behavior = allBehaviors.get(i);
            final int behaviorIndex = i;
            
            behaviorThreadPoolExecutor.execute(() -> {
                try {
                    log.info("[Behavior Processing] 开始处理第 {} 个行为，behaviorId={}", 
                            behaviorIndex + 1, behavior.getId());
                    
                    // 并发进行计算：获取候选指标和法规
                    log.debug("[Behavior Processing] 正在获取候选指标和法规，behaviorId={}", behavior.getId());
                    List<Scored<Indicator>> indicators = fetchTopIndicators(behavior, 6);
                    List<Scored<Regulation>> regulations = fetchTopRegulations(behavior, 10);
                    log.debug("[Behavior Processing] 获取到 {} 个指标和 {} 个法规，behaviorId={}", 
                            indicators.size(), regulations.size(), behavior.getId());

                    // 只计算，不保存
                    log.debug("[Behavior Processing] 正在计算映射结果，behaviorId={}", behavior.getId());
                    DocumentProcessingResult.MappingResult result = computeMappingFromCandidates(behavior, indicators, regulations);
                    log.debug("[Behavior Processing] 计算完成，result={}, behaviorId={}", 
                            result != null ? "有结果" : "无结果", behavior.getId());

                    if (result != null && result.getRelatedIndicators() != null) {
                        // 提取元数据
                        for (Scored<Indicator> s : indicators) {
                            if (s.getItem() != null && s.getItem().getId() != null) {
                                Indicator ind = s.getItem();
                                indicatorMetadataMap.putIfAbsent(ind.getId(), new IndicatorMetadataDTO(
                                    ind.getId(),
                                    ind.getName(),
                                    ind.getIndicatorLevel(),
                                    ind.getDimension(),
                                    ind.getType(),
                                    ind.getMaxScore()
                                ));
                            }
                        }
                        
                        // 收集计算结果到 Map 中
                        int relatedCount = result.getRelatedIndicators().size();
                        for (Map.Entry<String, RelatedIndicator> entry : result.getRelatedIndicators().entrySet()) {
                            String indicatorId = entry.getKey();
                            RelatedIndicator ri = entry.getValue();
                            
                            indicatorResultsMap.computeIfAbsent(indicatorId, k -> new CopyOnWriteArrayList<>())
                                              .add(ri);
                        }
                        log.info("[Behavior Processing] 收集到 {} 个相关指标，behaviorId={}", 
                                relatedCount, behavior.getId());
                    } else {
                        log.info("[Behavior Processing] 无相关指标，behaviorId={}", behavior.getId());
                    }
                    
                    // 检查是否需要批量处理
                    int currentCount = processedCount.incrementAndGet();
                    if (currentCount % 10 == 0) {
                        log.info("[Progress] 已处理 {}/{} 个行为，完成率：{:.2f}%，活跃线程数：{}", 
                                currentCount, totalBehaviorCount, 
                                (currentCount * 100.0) / totalBehaviorCount,
                                behaviorThreadPoolExecutor.getActiveCount());
                    }
                    
                    // 移除此处的破坏性多线程阶段保存逻辑
                    // 所有的结果将安全地累积到线程安全的 indicatorResultsMap 中，并在所有计算（比如620条）都完成后的主线程 [Final Batch Processing] 中一次性集中落库。
                    // 这将彻底杜绝 JPA NonUniqueResultException 并保证平均分计算完全准确。
                    
                    log.info("[Behavior Processing] 完成处理第 {} 个行为，behaviorId={}", 
                            behaviorIndex + 1, behavior.getId());
                    
                } catch (Exception e) {
                    log.error("[Behavior Processing Failed] behaviorId={}, error={}", behavior.getId(), e.getMessage(), e);
                } finally {
                    long remaining = latch.getCount();
                    latch.countDown();
                    log.debug("[CountDown] 剩余 {} 个行为待处理", latch.getCount());
                }
            });
        }

        // 5. 等待所有行为处理完成
        try {
            log.info("[Waiting] 等待所有行为处理完成，总行为数：{}", totalBehaviorCount);
            
            // 定期输出等待状态
            long startTime = System.currentTimeMillis();
            while (latch.getCount() > 0) {
                boolean completed = latch.await(30, TimeUnit.SECONDS); // 每30秒检查一次
                
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                long remaining = latch.getCount();
                int processed = totalBehaviorCount - (int)remaining;
                
                log.info("[Waiting Status] 已处理 {}/{} 个行为，剩余 {} 个，已等待 {} 秒，活跃线程数：{}", 
                        processed, totalBehaviorCount, remaining, elapsed, 
                        behaviorThreadPoolExecutor.getActiveCount());
                
                if (completed) {
                    break;
                }
                
                // 如果等待超过30分钟，强制退出
                if (elapsed > 1800) {
                    log.error("[Waiting Timeout] 等待超时（30分钟），强制退出");
                    break;
                }
            }
            
            if (latch.getCount() == 0) {
                log.info("[All Behaviors Processed] 共处理 {} 个行为，全部完成", totalBehaviorCount);
            } else {
                log.warn("[Processing Timeout] 处理超时，可能有行为未完成处理，剩余 {} 个", latch.getCount());
            }
            
            // 6. 处理剩余的结果（不足 batchSize 的部分）
            if (!indicatorResultsMap.isEmpty()) {
                int remainingCount = indicatorResultsMap.size();
                log.info("[Final Batch Processing] 处理剩余 {} 个指标结果", remainingCount);
                batchSaveIndicatorResults(indicatorResultsMap, indicatorMetadataMap, projectId, assessmentId);
                log.info("[Final Batch Processing] 剩余结果处理完成");
            } else {
                log.info("[Final Batch Processing] 无剩余结果需要处理");
            }
            
            // 7. 完成评估
            log.info("[Assessment Completing] 开始完成评估流程");
            completeAssessmentIfNeeded(userId, projectId, assessmentId);
            log.info("[Assessment Completed] 评估流程已完成");
            
        } catch (InterruptedException e) {
            log.error("[Process Interrupted] projectId={}, error={}", projectId, e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 批量保存指标结果
     */
    private void batchSaveIndicatorResults(Map<String, List<RelatedIndicator>> indicatorResultsMap,
                                           Map<String, IndicatorMetadataDTO> indicatorMetadataMap,
                                           Long projectId, Long assessmentId) {
        if (indicatorResultsMap.isEmpty()) {
            log.info("[Batch Save] 无指标结果需要保存");
            return;
        }
        
        try {
            log.info("[Batch Save] 开始批量保存，共 {} 个指标", indicatorResultsMap.size());
            List<IndicatorResult> resultsToSave = new ArrayList<>();
            
            int processedIndicators = 0;
            for (Map.Entry<String, List<RelatedIndicator>> entry : indicatorResultsMap.entrySet()) {
                String indicatorId = entry.getKey();
                List<RelatedIndicator> relatedIndicators = entry.getValue();
                
                if (relatedIndicators.isEmpty()) {
                    continue;
                }
                
                processedIndicators++;
                if (processedIndicators % 10 == 0) {
                    log.info("[Batch Save] 已处理 {} 个指标", processedIndicators);
                }
                
                // 计算平均得分
                double totalScore = 0;
                for (RelatedIndicator ri : relatedIndicators) {
                    totalScore += ri.getScore();
                }
                double avgScore = totalScore / relatedIndicators.size();
                
                // 获取指标元数据
                IndicatorMetadataDTO metadata = indicatorMetadataMap.get(indicatorId);
                double maxPossible = 100.0;
                if (metadata != null && metadata.maxScore != null && metadata.maxScore > 0) {
                    maxPossible = metadata.maxScore;
                }
                double absoluteScore = avgScore * maxPossible;
                
                // 检查是否已存在
                Optional<IndicatorResult> existingResult = indicatorResultRepository
                        .findByAssessmentIdAndIndicatorEsId(assessmentId, indicatorId);
                
                if (existingResult.isPresent()) {
                    // 更新现有记录
                    IndicatorResult existing = existingResult.get();
                    IndicatorResultDetail detail = existing.getCalculationDetails();
                    if (detail == null) {
                        detail = IndicatorResultDetail.builder()
                                .relatedIndicators(new ArrayList<>())
                                .build();
                    }
                    
                    // 合并相关指标
                    int existingCount = detail.getRelatedIndicators().size();
                    double existingScore = existing.getCalculatedScore();
                    double newAvgScore = (existingScore * existingCount + absoluteScore) / (existingCount + relatedIndicators.size());
                    
                    existing.setCalculatedScore(newAvgScore);
                    existing.setCalculatedAt(LocalDateTime.now());
                    detail.getRelatedIndicators().addAll(relatedIndicators);
                    existing.setCalculationDetails(detail);
                    
                    resultsToSave.add(existing);
                } else {
                    // 创建新记录
                    IndicatorResult result = IndicatorResult.builder()
                            .projectId(projectId)
                            .assessmentId(assessmentId)
                            .indicatorEsId(indicatorId)
                            .indicatorName(metadata != null ? metadata.name : indicatorId)
                            .indicatorLevel(metadata != null && metadata.indicatorLevel != null ? metadata.indicatorLevel : 0)
                            .dimension(metadata != null ? metadata.dimension : null)
                            .type(metadata != null ? metadata.type : null)
                            .calculatedScore(absoluteScore)
                            .maxPossibleScore(maxPossible)
                            .usedCalculationRuleType("auto")
                            .calculationDetails(IndicatorResultDetail.builder()
                                    .relatedIndicators(new ArrayList<>(relatedIndicators))
                                    .build())
                            .riskTriggered(false)
                            .riskStatus(IndicatorRiskStatus.fromCode("NOT_EVALUATED"))
                            .calculatedAt(LocalDateTime.now())
                            .createdAt(LocalDateTime.now())
                            .build();
                    
                    resultsToSave.add(result);
                }
            }
            
            // 批量保存
            if (!resultsToSave.isEmpty()) {
                log.info("[Batch Save] 准备保存 {} 个指标结果", resultsToSave.size());
                indicatorResultRepository.saveAll(resultsToSave);
                log.info("[Batch Save Success] 保存了 {} 个指标结果", resultsToSave.size());
            } else {
                log.info("[Batch Save] 无指标结果需要保存");
            }
            
        } catch (Exception e) {
            log.error("[Batch Save Failed] error={}", e.getMessage(), e);
        }
    }

    private void updateAssessmentStatus(Long assessmentId, AssessmentStatusEnum assessmentStatus) {
        Optional<Assessment> opt = assessmentRepository.findById(assessmentId);
        if (opt.isPresent()) {
            Assessment ar = opt.get();
            ar.setStatus(assessmentStatus);
            assessmentRepository.save(ar);
            log.info("[Assessment Status Updated] assessmentId={}, status={}", assessmentId, assessmentStatus);
        } else {
            log.warn("[Assessment Not Found] assessmentId={}", assessmentId);
            throw new RuntimeException("[Assessment Not Found] assessmentId=" + assessmentId);
        }
    }

    private void saveIndicatorResult(BehaviorCalculationResult calcResult, Long projectId, Long assessmentId) {
        String behaviorId = calcResult.behaviorId;
        Map<String, IndicatorMetadataDTO> indicatorMetadata = calcResult.indicatorMetadata;
        DocumentProcessingResult.MappingResult mr = calcResult.result;


        if (mr == null || mr.getRelatedIndicators() == null || mr.getRelatedIndicators().isEmpty()) {
            log.debug("[No Scores to Save] behaviorId={}", behaviorId);
            return;
        }

        Map<String, RelatedIndicator> relatedIndicators = mr.getRelatedIndicators();

        for (Map.Entry<String, RelatedIndicator> e : relatedIndicators.entrySet()) {
            String indicatorEsId = e.getKey();
            RelatedIndicator ri = e.getValue();
            double normalizedScore = ri.getScore();

            IndicatorMetadataDTO metadata = indicatorMetadata.get(indicatorEsId);
            double maxPossible = 100.0;
            if (metadata != null && metadata.maxScore != null && metadata.maxScore > 0) {
                maxPossible = metadata.maxScore;
            }
            double absoluteScore = normalizedScore * maxPossible;


            int retryTimes = 5;
            while(retryTimes > 0){
                try{
                    // t_indicator_result有assessment_id和indicator_es_id联合唯一索引，避免幻读插入覆盖，插入失败会重试后进入乐观锁更新逻辑
                    Optional<IndicatorResult> alIndicatorResult = indicatorResultRepository.findByAssessmentIdAndIndicatorEsId(assessmentId, indicatorEsId);

                    if (alIndicatorResult.isPresent()) {
                        IndicatorResult existing = alIndicatorResult.get();
                        IndicatorResultDetail indicatorResultDetail = existing.getCalculationDetails();

                        boolean alreadyContains = false;
                        for(RelatedIndicator relatedIndicator : indicatorResultDetail.getRelatedIndicators()) {
                            if(relatedIndicator.getIndicatorId().equals(behaviorId)) {
                                alreadyContains = true;
                                break;
                            }
                        }
                        if (!alreadyContains) {
                            LocalDateTime oldCalculatedAt = existing.getCalculatedAt();
                            double existingScore = existing.getCalculatedScore();
                            int existingCount = indicatorResultDetail.getRelatedIndicators().size();
                            double averagedScore = (existingScore * existingCount + absoluteScore) / (existingCount + 1);
                            existing.setCalculatedScore(averagedScore);
                            existing.setCalculatedAt(LocalDateTime.now());
                            indicatorResultDetail.getRelatedIndicators().add(ri);
                            existing.setCalculationDetails(indicatorResultDetail);
                            String calculationDetailsJson = objectMapper.writeValueAsString(indicatorResultDetail);
                            String riskStatusDbValue = existing.getRiskStatus() != null
                                    ? existing.getRiskStatus().getDbValue()
                                    : IndicatorRiskStatus.fromCode("NOT_EVALUATED").getDbValue();
                            int updateRes = indicatorResultRepository.updateWithOptimisticLock(
                                    existing,
                                    calculationDetailsJson,
                                    riskStatusDbValue,
                                    oldCalculatedAt
                            );
                            if( updateRes == 0) {
                                log.warn("[Indicator Result Update Failed] behaviorId={}, indicatorEsId={}, retrying...",
                                        behaviorId, indicatorEsId);
                                throw new RuntimeException("[Indicator Result Update Failed] behaviorId=" + behaviorId);
                            }
                        }
                        break;
                    } else {
                        IndicatorResult ir = IndicatorResult.builder()
                                .projectId(projectId)
                                .assessmentId(assessmentId)
                                .indicatorEsId(indicatorEsId)
                                .indicatorName(metadata != null ? metadata.name : indicatorEsId)
                                .indicatorLevel(metadata != null && metadata.indicatorLevel != null ? metadata.indicatorLevel : 0)
                                .dimension(metadata != null ? metadata.dimension : null)
                                .type(metadata != null ? metadata.type : null)
                                .calculatedScore(absoluteScore)
                                .maxPossibleScore(maxPossible)
                                .usedCalculationRuleType("auto")
                                .calculationDetails(IndicatorResultDetail.builder()
                                        .relatedIndicators(new ArrayList<>(Collections.singletonList(ri)))
                                        .build())
                                .riskTriggered(false)
                                .riskStatus(IndicatorRiskStatus.fromCode("NOT_EVALUATED"))
                                .calculatedAt(LocalDateTime.now())
                                .createdAt(LocalDateTime.now())
                                .build();
                        indicatorResultRepository.save(ir);
                        log.info("[Indicator Result Saved] behaviorId={}, indicatorEsId={}, score={}",
                                behaviorId, indicatorEsId, absoluteScore);
                        break;
                    }
                } catch (Exception ex) {
                    log.error("[Save Indicator Result Failed] behaviorId={}, indicatorEsId={}, error={}",
                            behaviorId, indicatorEsId, ex.getMessage());
                } finally {
                    retryTimes--;
                }
            }
        }
    }

    private void completeAssessmentIfNeeded(Long userId, Long projectId, Long assessmentId) {
        AssessmentCompletedEventMessage assessmentCompletedEventMessage = new AssessmentCompletedEventMessage(
                StringUtils.generateMessageId(),
                String.valueOf(System.currentTimeMillis()),
                StringUtils.generateTraceId(),
                userId,
                projectId,
                assessmentId
        );
        kafkaUtils.sendMessage(assessmentCompletedEventMessage);
        log.info("[Assessment Completed] projectId={}, assessmentId={}", projectId, assessmentId);
    }

    // 改名：保留原 compute-only 方法（不入库），接收带分数的候选列表
    public DocumentProcessingResult.MappingResult computeMappingFromCandidates(Behavior behavior,
                                                                               List<Scored<Indicator>> indicators,
                                                                               List<Scored<Regulation>> regulations) {

        List<String> warnings = new ArrayList<>();

        // 存储每个指标下所有法规的详细信息：(法规得分, 层级权重, 相似度权重)
        Map<String, List<RegulationScore>> indicatorRegScores = new HashMap<>();
        Map<String, RelatedIndicator> indicatorResults = new HashMap<>();

        // 初始化候选指标
        for (Scored<Indicator> s : indicators) {
            Indicator ind = s.getItem();
            if (ind != null && ind.getId() != null) {
                indicatorRegScores.put(ind.getId(), new ArrayList<>());
            }
        }

        // 对于每个法规候选，根据相似度和适用性计算对指标的影响
        for (Scored<Regulation> sreg : regulations) {
            Regulation reg = sreg.getItem();
            if (reg == null) continue;

            // 获取向量和标签数据用于本地相似度计算
            List<Float> behaviorVec = (behavior != null) ? behavior.getDescriptionVector() : null;

            List<Float> regVec = reg.getFullTextVector();
            List<String> behaviorTags = (behavior != null) ? behavior.getTags() : null;
            List<String> regTags = reg.getTags();

            boolean hasBehaviorVec = (behaviorVec != null && !behaviorVec.isEmpty());

            // 获取ES分数（如果有的话）
            double esScore = sreg.getSim();

            // 使用向量+标签计算行为与法规的相似度
            double computedSim = SimilarityCalculator.scoreBehaviorToTargetDefault(
                    behaviorVec, regVec, behaviorTags, regTags);

            // 最终相似度：优先使用本地计算值，如果本地计算失败则使用ES分数作为保底
            double behaviorRegSim = computedSim;
            if (computedSim <= 0.0 && esScore > 0.0) {
                behaviorRegSim = esScore;
            }

            // 检查是否通过相似度阈值
            if (behaviorRegSim < REG_APPLICABILITY_THRESHOLD) {
                continue;
            }

            // 对当前法规进行定性计算
            double qualitativeScore = computeRegulationScore(behavior, reg);

            // 计算法规层级权重和时效性权重
            double hierarchyWeight = RegWeightCalculator.getHierarchyWeight(reg);
            double timelinessWeight = RegWeightCalculator.getTimelinessWeight(reg,behavior);

            // 遍历所有指标，判断当前法规是否影响该指标
            for (Scored<Indicator> sind : indicators) {
                Indicator ind = sind.getItem();
                if (ind == null || ind.getId() == null) continue;

                // 获取指标向量
                List<Float> indVec = ind.getNameVector();

                // 计算法规与指标的相似度
                double regIndSim = SimilarityCalculator.scoreRegToIndicatorDefault(
                        regVec, indVec,
                        reg.getTags(), ind.getTags(),
                        reg.getIndustry(), ind.getIndustry()
                );

                // 计算影响力：法规与指标相似度 * 行为与法规相似度
                double influence = regIndSim * behaviorRegSim;

                // 检查是否通过影响力阈值
                if (influence < REG_TO_INDICATOR_THRESHOLD) {
                    continue;
                }

                // 将法规详细信息加入到指标的列表中
                indicatorRegScores.get(ind.getId()).add(
                    new RegulationScore(reg.getId(), qualitativeScore, hierarchyWeight, timelinessWeight, behaviorRegSim)
                );
                RelatedIndicator indicatorResult = indicatorResults.getOrDefault(ind.getId(),
                        RelatedIndicator.builder()
                                .indicatorId(ind.getId())
                                .indicatorName(ind.getName())
                                .score(0.0)
                                .maxScore(ind.getMaxScore())
                                .relatedBehaviors(new ArrayList<>())
                                .build());

                // 因为这里一直是同一个behavior，只需要添加regulation即可
                if(indicatorResult.getRelatedBehaviors().isEmpty()){
                    indicatorResult.getRelatedBehaviors().add(
                            RelatedBehavior.builder()
                                    .projectId(behavior.getProjectId())
                                    .description(behavior.getDescription())
                                    .relatedRegulations(new ArrayList<>(Collections.singletonList(
                                            RelatedRegulation.builder()
                                                    .regulationId(reg.getId())
                                                    .regulationName(reg.getName())
                                                    // todo: 填充相关法律更多字段
                                                    .violationType("")
                                                    .complianceRequirement("")
                                                    .build()
                                    )))
                                    .build()
                    );
                } else {
                    indicatorResult.getRelatedBehaviors().get(0).getRelatedRegulations().add(
                            RelatedRegulation.builder()
                                    .regulationId(reg.getId())
                                    .regulationName(reg.getName())
                                    // todo: 填充相关法律更多字段
                                    .violationType("")
                                    .complianceRequirement("")
                                    .build()
                    );
                }
                indicatorResults.put(ind.getId(), indicatorResult);
            }
        }

        // 计算每个指标的最终得分
        for (Scored<Indicator> sind : indicators) {
            Indicator ind = sind.getItem();
            if (ind == null || ind.getId() == null) continue;

            List<RegulationScore> regScoreList = indicatorRegScores.get(ind.getId());

            if (regScoreList == null || regScoreList.isEmpty()) {
                // 对未被法规影响的指标做兜底
                double fallback = getFallback(behavior, ind);
                double clampedFallback = FallbackCalculator.clamp01(fallback);
                indicatorResults.get(ind.getId()).setScore(clampedFallback);
                if (fallback == 0) {
                    warnings.add("Indicator " + ind.getId() + " has zero fallback score");
                }

            } else {
                // 计算加权平均
                double weightedSum = 0.0;
                double totalWeight = 0.0;

                for (RegulationScore rs : regScoreList) {
                    double weight = (rs.hierarchyWeight + rs.timelinessWeight + rs.similarityWeight) / 3.0;
                    weightedSum += rs.score * weight;
                    totalWeight += weight;
                }

                double weightedAvgScore = totalWeight > 0 ? weightedSum / totalWeight : 0.0;
                double clampedScore = FallbackCalculator.clamp01(weightedAvgScore);
                indicatorResults.get(ind.getId()).setScore(clampedScore);
            }
        }

        return DocumentProcessingResult.MappingResult.builder()
                .behaviorId(behavior.getId())
                .relatedIndicators(indicatorResults)
                .warnings(warnings)
                .build();
    }

    /**
     * 对单个法规进行定性/定量计算

     */
    private double computeRegulationScore(Behavior behavior, Regulation regulation) {
        // 判断是否有定量数据
        //TODO
        boolean hasQuantitativeData = Objects.equals(regulation.getType(), "定量") ||Objects.equals(behavior.getType(), "定量");

        if (hasQuantitativeData) {
            // 定量计算：根据法规定量指标和行为定量数据计算
            return QuantitativeCalculator.computeQuantitativeScore(
                    regulation.getQuantitativeIndicator(),
                    behavior.getQuantitativeData(),
                    regulation.getDirection(),
                    behavior.getStatus()
            );
        } else {
            // 定性计算：根据法规方向和行为状态矩阵计算

            return QualitativeCalculator.computeQualitativeScore(
                    regulation.getDirection(),
                    behavior.getStatus()
            );
        }
    }

    private static double getFallback(Behavior behavior, Indicator ind) {
        double fallback;
        if (ind.getMaxScore() != null && behavior.getQuantitativeData() != null) {
            double baseline = ind.getMaxScore();
            double deviation = Math.abs(baseline - behavior.getQuantitativeData());
            double ratio = deviation / (baseline == 0.0 ? 1.0 : baseline);
            fallback = Math.max(0.0, 1.0 - ratio);
        } else {
            fallback = FallbackCalculator.qualitativeFallback(behavior.getStatus());
        }
        return fallback;
    }

    /**
     * 从 ES 中获取指定 projectId 的所有 behaviors
     * 加固：查询前显式刷新索引，空结果时带间隔重试，避免行为刚写入尚未可见导致的偶发空结果
     */
    private List<Behavior> fetchBehaviors(Long projectId) {
        // 查询前先触发一次索引刷新，确保刚写入的行为立即可见（刷新失败不阻断查询）
        try {
            esClient.indices().refresh(r -> r.index(ElasticSearchConfig.BEHAVIOR_INDEX));
        } catch (Exception e) {
            log.warn("[Fetch Behaviors] 索引刷新失败(忽略): {}", e.getMessage());
        }

        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<Behavior> result = doFetchBehaviors(projectId);
                if (!result.isEmpty()) {
                    return result;
                }
                if (attempt < maxAttempts) {
                    log.info("[Fetch Behaviors] projectId={} 第 {} 次查询为空，{}ms 后重试",
                            projectId, attempt, BEHAVIOR_FETCH_RETRY_INTERVAL_MS);
                    Thread.sleep(BEHAVIOR_FETCH_RETRY_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Fetch Behaviors] 取数重试被中断: {}", e.getMessage());
                return Collections.emptyList();
            } catch (Exception e) {
                log.error("[Fetch Behaviors Failed] error={}", e.getMessage());
                if (attempt == maxAttempts) {
                    return Collections.emptyList();
                }
                try {
                    Thread.sleep(BEHAVIOR_FETCH_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Collections.emptyList();
                }
            }
        }
        return Collections.emptyList();
    }

    private List<Behavior> doFetchBehaviors(Long projectId) throws IOException {
        // 使用较大的 size 获取所有行为（大项目可改用 scroll API）
        int fetchSize = 10000;

        SearchResponse<Behavior> resp = esClient.search(s -> s
                        .index(ElasticSearchConfig.BEHAVIOR_INDEX)
                        .size(fetchSize)
                        .query(q -> q.bool(ma -> ma.must(m1 -> m1.term(t -> t.field("project_id").value(projectId))))),
                Behavior.class
        );

        List<Behavior> allBehaviors = new ArrayList<>();
        if (resp != null && resp.hits() != null && resp.hits().hits() != null) {
            for (Hit<Behavior> hit : resp.hits().hits()) {
                Behavior behavior = hit.source();
                if (behavior != null) {
                    allBehaviors.add(behavior);
                }
            }
        }

        // 处理所有行为，不再随机选择
        List<Behavior> selectedBehaviors = new ArrayList<>();
        if (!allBehaviors.isEmpty()) {
            for (Behavior behavior : allBehaviors) {
                behavior.setProjectId(projectId);
                selectedBehaviors.add(behavior);
            }
        }

        log.info("[Fetch Behaviors] projectId={}, totalCount={}, selectedCount={}",
                projectId, allBehaviors.size(), selectedBehaviors.size());

        return selectedBehaviors;
    }



    // 新增：从 ES 拉取候选指标：简单的文本多字段匹配，返回 ES hit score 作为相似度
    public List<Scored<Indicator>> fetchTopIndicators(Behavior behavior, int candidateSize) {
        try {
            String text = (behavior.getDescription() == null ? "" : behavior.getDescription())
                    + " " + (behavior.getTags() == null ? "" : String.join(" ", behavior.getTags()));

            log.info("[Fetching Indicator Candidates] behaviorId={}, candidateSize={}, queryText='{}'",
                    behavior.getId(), candidateSize, text);

            if (behavior.getDescriptionVector() == null || behavior.getDescriptionVector().isEmpty()) {
                log.warn("[Fetch Indicator Candidates Skipped] No vector found for behaviorId={}", behavior.getId());
                return Collections.emptyList();
            }

            SearchResponse<Indicator> resp = esClient.search(s -> s
                            .index(ElasticSearchConfig.INDICATOR_INDEX)
                            .size(candidateSize)
                            .knn(k -> k
                                    .field(INDICATOR_VECTOR_FIELD)
                                    .queryVector(
                                            behavior.getDescriptionVector()
                                    )
                                    .k(candidateSize)
                                    .numCandidates(CANDIDATE_FETCH_SIZE)
                            )
                            // 移除source过滤器，获取完整的指标数据（包括向量）
                    , Indicator.class);

            List<Scored<Indicator>> out = new ArrayList<>();
            if (resp != null && resp.hits() != null) {
                for (Hit<Indicator> h : resp.hits().hits()) {
                    Indicator ind = h.source();
                    double score = h.score() == null ? 0.0 : h.score();
                    out.add(new Scored<>(ind, score));
                }
            }
            return out;
        } catch (Exception ex) {
            log.error("[Fetch Indicator Candidates Failed] behaviorId={}, error", behavior.getId(), ex);
            return Collections.emptyList();
        }
    }

    // 新增：从 ES 拉取候选法规
    public List<Scored<Regulation>> fetchTopRegulations(Behavior behavior, int candidateSize) {
        try {

            if (behavior.getDescriptionVector() == null || behavior.getDescriptionVector().isEmpty()) {
                log.warn("[Fetch Regulation Candidates Skipped] No vector found for behaviorId={}", behavior.getId());
                return Collections.emptyList();
            }

            SearchResponse<Regulation> resp = esClient.search(s -> s
                            .index(ElasticSearchConfig.REGULATION_INDEX)
                            .size(candidateSize)
                            .knn(k -> k
                                    .field(REGULATION_VECTOR_FIELD)
                                    .queryVector(
                                            behavior.getDescriptionVector()
                                    )
                                    .k(candidateSize)
                                    .numCandidates(CANDIDATE_FETCH_SIZE)
                            )
                    , Regulation.class);

            List<Scored<Regulation>> out = new ArrayList<>();
            if (resp != null && resp.hits() != null) {
                for (Hit<Regulation> h : resp.hits().hits()) {
                    Regulation reg = h.source();
                    double score = h.score() == null ? 0.0 : h.score();
                    out.add(new Scored<>(reg, score));
                }
            }
            return out;
        } catch (Exception ex) {
            log.error("[Fetch Regulation Candidates Failed] behaviorId={}, error", behavior.getId(), ex);
            return Collections.emptyList();
        }
    }

    // 新增用于构建 calculation_details 的简单 JSON
    private ObjectNode buildCalculationDetails(double score, List<String> influencingRegs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("score", score);
        if (influencingRegs != null) {
            node.putPOJO("influencingRegulations", influencingRegs);
        } else {
            node.putPOJO("influencingRegulations", Collections.emptyList());
        }
        // 可在此添加更多中间过程信息
        return node;
    }
}
