# P1-03 执行记录

> 开工日期：2026-09-05；分支：main。目标是使 Behavior 以完整运行作用域写入并按该作用域读取。

## 已实现的代码

- `Behavior` 新增 `assessmentId`、`analysisRunId`、`sourceDocumentId`，以及事实三元组、单位、置信度、`evidenceIds` 与抽取版本字段。所有字段均为可空扩展，旧 ES 文档缺字段可反序列化为 `null`；`status`、`quantitativeData`、`behaviorDate` 保持原契约。
- `documents/es_mappings.json` 的 `t_behavior` 增加相同 camelCase 映射；`test/init_es.py` 直接读取该文件，因此索引重建场景自动采用增量字段。
- `LineProcessor` 已将当前 Batch 的 `projectId`、`assessmentId`、`analysisRunId`、`sourceDocumentId` 写入每个 Behavior。没有改动批量写入 `_id`、分类或向量化流程。
- `BehaviorProcessingService` 只按 `projectId + assessmentId + analysisRunId` 查询 Behavior，保留写后 refresh 与空结果重试。旧文档缺任一新字段时不命中，也不回退为项目级查询；总命中数超过 10,000 时记录 WARN。

## 验证

- `BehaviorSerdeTest`：新字段 JSON 往返与旧 JSON 缺字段兼容。
- `LineProcessorScopeTest`：结构化 JSON 与旧纯文本输入都保留三个运行作用域字段及源文件身份。
- `BehaviorScopeQueryTest`：查询包含三个精确 term，缺失运行身份直接拒绝。
- `BehaviorScopeElasticsearchIT`：在本地 Elasticsearch 8.11.0 上通过；使用随机 `p1_behavior_scope_*` 索引，已验证 Mapping、实际写入字段、两个 assessment/run 隔离及历史无作用域文档排除。测试索引已删除，未操作 `t_behavior`。

## 当前限制

- P1-03 代码与专用 ES 测试已完成；业务环境部署前应先向现有 `t_behavior` 发送仅新增字段的 PUT mapping。不重建、不删除、不回填存量行为。
- Behavior 的稳定 `_id`、同运行重试去重、有效结果切换及 IndicatorResult/Risk 的运行字段仍属于 P1-06；Evidence 引用完整性校验与抽取字段赋值仍属于 P1-05。

## 后续修订记录

- 2026-09-05（P1-05 评审）：本计划 §3.3 决策 1「`schemaVersion` 不入 t_behavior」被 P1-05 落地推翻——`Behavior` 与 `t_behavior` 新增 `schemaVersion`（增量见 `006_behavior_schema_version.json`），作为行为级抽取 Schema 标识；p1-03-plan §3.3 与 p1-05-execution 已同步。
