package com.riskwarning.processing.repository;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

/**
 * Behavior 查询的唯一作用域条件。
 *
 * "项目 + 评估 + 运行三作用域精确匹配"是冻结语义（p1-03），写入聚合与只读查询两侧
 * 都必须经过这里构建，禁止任何调用方退化为项目级查询；从一处构建避免两处实现漂移。
 */
public final class BehaviorScopeQuery {

    private BehaviorScopeQuery() {
    }

    public static Query build(Long projectId, Long assessmentId, String analysisRunId) {
        if (projectId == null || projectId <= 0 || assessmentId == null || assessmentId <= 0
                || analysisRunId == null || analysisRunId.trim().isEmpty()) {
            throw new IllegalArgumentException("Behavior 查询缺少完整分析作用域");
        }
        return Query.of(q -> q.bool(b -> b
                .must(m -> m.term(t -> t.field("projectId").value(projectId)))
                .must(m -> m.term(t -> t.field("assessmentId").value(assessmentId)))
                .must(m -> m.term(t -> t.field("analysisRunId").value(analysisRunId)))));
    }
}