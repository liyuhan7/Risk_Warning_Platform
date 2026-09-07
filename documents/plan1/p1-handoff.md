# P1 开发对接说明

> 对齐日期：2026-09-05
> 代码基线：main，`96b1c4a385ce4dbc9eba2ef03637d80e6f0eb0cb`（本地 Nacos 迁移与配置收尾）
> 状态：P0 数据契约与真实链路可运行基线已收尾；P1 可开工，运行隔离等能力仍待实现。
> 依据：[P0-11 冻结决策](../plan0/baseline/p0-11-review-record.md)、[缺陷清单](../plan0/baseline/defect-backlog.md)、用户提供的核对结论。任务与验收主表以 [PLAN](../PLAN.md) 计划 1 为准。

P1-01 的运行身份与持久化基础及验证见 [执行记录](p1-01-execution.md)。P1-02 的 Evidence 权威存储与查询见 [执行记录](p1-02-execution.md)。P1-03 的 Behavior 作用域写入与查询隔离见 [执行记录](p1-03-execution.md)。P1-04 的 Fact Extraction Prompt、JSON Schema、样本与离线校验见 [执行记录](p1-04-execution.md)。P1-05 的抽取服务、校验、装配、分类/向量补全、ES 写入与主链切换见 [执行记录](p1-05-execution.md)。P1-06 的运行隔离、迁移脚本与真实 Provider/PG/ES/BERT 切换场景验证见 [执行记录](p1-06-execution.md)。P1-07 的新链回归、版本契约、旧 Batch 文档收尾与 checkpoint 说明见 [执行记录](p1-07-execution.md)。

## 1. 当前基线与证据边界

2026-09-04 迁移窗口记录已确认 ES 四索引 camelCase、PG 一文件一行、风险等级文本持久化、Milvus 字段迁移/索引与缺失 100 条补齐、createdAt 统一及历史回填完成。用户确认上传 → 行为 → 指标 → 风险 → 报告链、新 Risk.createdAt、风险等级、报告查询和 Nacos 注册正常。本次文档对齐静态核对了提交、ProjectFile、Assessment、Behavior 和 Mapping，未重跑在线查询或业务测试。

上述结果证明真实单次链路可以运行，不证明跨评估隔离、运行幂等、抽取准确性或规则正确性。P0-10 前端类型、真实 Provider 连续调用验证等遗留项仍按 P0-11 跟踪，不能把“基线收尾”写成所有历史任务均完成。

D-01、D-23、D-24、D-09、D-31、D-32 已闭环；F-07/F-09、Milvus 迁移补数不进入 P1 重复开发。D-26/D-28/D-29 的实施变化与待验收部分分别登记在缺陷清单。D-21 供应商吊销无完成证据，继续保留。

## 2. 数据身份与冻结边界

- `Project → Assessment → AnalysisRun`：一个评估可多次运行；独立运行记录至少包含 analysisRunId 主键、assessmentId、projectId、status、startedAt、finishedAt，状态 RUNNING/SUCCEEDED/FAILED。
- `AnalysisScope(projectId, assessmentId, analysisRunId)` 贯通 Kafka、DocumentProcessing、FactExtractionPipeline、Behavior、IndicatorResult、Risk。
- `sourceDocumentId = ProjectFile.id`，每条对应一个文件。现有 ProjectFile 一文件一行不代表评估与文件关联、文件上传哈希去重已经实现；P1-01 必须补齐并验证文档归属。
- EvidenceChunk 是文档资产：id、sourceDocumentId、pageNumber、segmentIndex、text、textHash、createdAt；稳定 ID 为 hash(sourceDocumentId, pageNumber, segmentIndex, textHash)，不包含 analysisRunId。处理日志可以带运行 ID，不能据此把 Evidence 变成运行资产。
- Behavior 是运行事实：projectId、assessmentId、analysisRunId、sourceDocumentId、evidenceIds，加 subject/action/object、status/occurredAt、quantitativeValue/unit、description/confidence、extractionModel/extractionPromptVersion、createdAt。
- Behavior 幂等键是 `(analysisRunId, sourceDocumentId, textHash)`；文件上传去重使用文件内容哈希。Evidence 可复用，不等于允许跨评估未经归属校验引用文件。
- 新结果按“写新 → 完整性校验 → SUCCEEDED 并切换当前有效结果 → 清理旧结果”发布。失败保留上次成功结果；成功切换前两次运行互不覆盖，不要求永久保存全部历史结果。

## 3. 执行顺序与 Entry Gate

保留任务编号，但以依赖决定执行顺序：

| 顺序 | 任务 | 重点 |
| --- | --- | --- |
| 1 | P1-01 | AnalysisRun/AnalysisScope、持久化与并发约束，Message → MessageTask → DocumentProcessingService → FactExtractionPipeline 全链 ID 透传，结果侧接线 |
| 2 | P1-02 | EvidenceChunk 稳定 ID、持久化、查询和文件页码定位 |
| 3 | P1-03 | Behavior 模型、Mapping、Repository、DTO、查询作用域与元数据 |
| 4 | P1-06 基础部分 | assessmentId + analysisRunId 查询、同 Run 幂等、新 Run 失败保护及有效结果切换 |
| 5 | P1-04 / P1-05 基础 | Prompt/Schema、FactExtractionService 与默认抽取主链 |
| 6 | P1-05 / P1-06 复验、P1-07 | 真实 Provider/PG/ES/BERT 抽取与切换场景；新链来源/版本契约和 F-13 回归收尾 |
| 7 | P1-08 / P1-09 / P1-10 | 前端 Evidence 回溯、Expected Facts 标注、真实 PDF 验收 |

原对接建议将 P1-06 放在 LLM 后，同时要求其能力属于 Entry Gate；这里明确拆成“前置基础实现 + 接入后复验”，消除顺序冲突。

进入 P1-04 之前必须全部通过：

- [ ] AnalysisRun 正式持久化，Kafka 至 IndicatorResult/Risk 的运行 ID 一致。
- [ ] sourceDocumentId 从 ProjectFile.id 进入文档链，Behavior 作用域字段真实落库。
- [ ] 同 Project 的 Assessment A/Run A1 与 Assessment B/Run B1 查询互不混入。
- [ ] Assessment A 再执行 Run A2，切换前 A1/A2 不覆盖，同 Run 重试无重复行为。
- [ ] A2 中途失败时 A1 完好可查；成功须通过完整性校验后切换。
- [ ] P0 已验证的上传至报告业务链无回归。

具体表名、接口签名、有效结果指针及并发原子性方案，在 P1-01 执行计划中明确；本次没有把这些设计写成已实现能力。

## 4. 事实抽取与兼容链

新链为 Document → TextSegment → EvidenceChunk → FactExtractionService → Schema Validator → Normalizer → Behavior。LLM 只抽取原文事实，不输出违法判断、风险等级或分数。行为状态无法判定时使用 `UNKNOWN`，可选对象无法确定时使用 `null`；整份证据无法支撑客观事实时返回空 `facts`，禁止默认 COMPLIANT、伪造数值或生成占位记录。

P1-04 已冻结 `fact-extraction-v1.0`：模型响应根对象为 `{"facts":[...]}`，无可抽取事实时返回空数组；系统作用域、ID、版本、时间戳与向量字段不由模型输出。单次输入上限为 40 条 EvidenceChunk、正文合计 20,000 个 Unicode 字符，超限由 P1-05 稳定分批。16 组离线样本已全部符合预期；该结果只证明设计契约和构造样本一致，不替代真实 Provider 与存储链复验。

P1-05 基础实现已复用 P0 Provider，完成稳定分批、L1/L2/L3 校验、一次模型输出重试、归一化、稳定 Behavior ID、分类/向量补全和显式 ES `_id` 写入；FILE_UPLOAD 默认入口已切换到新链，只有抽取和写入全部成功才发送指标消息。Provider 或校验失败会将 run 标记 FAILED，不以空事实掩盖失败。真实 Provider 与专用 PG/ES/BERT 尚未联调。

当前 ES Bulk 写入不是事务：Bulk 返回部分失败时，已成功的单项可能短暂留在失败 run 下。当前成功运行指针会阻止失败 run 被业务读取，后续成功切换会清理非当前 run；真实环境仍须验证这一失败隔离和清理行为。

旧 Spring Batch 链路已随提交 `1a5e537` 整体移除，事实抽取新链为 FILE_UPLOAD 唯一生产路径。新 Behavior 以 `schemaVersion="1.0"` 标识结构契约，以 `extractionPromptVersion="fact-extract-v1.0"` 标识抽取来源版本；旧 ES 文档缺少这两个字段。P1-07 已补齐新链回归测试并记录分类 checkpoint 的获取限制，详见 [执行记录](p1-07-execution.md)。

## 5. Exit Gate 与阶段外事项

P1 完成必须具备真实 PDF 上传、ProjectFile、Evidence、Structured Behavior、查询接口、前端原文定位的完整切片；每条新事实至少引用一个有效 Evidence，文件页码可追溯、字段受原文支持、错误显式记录。ES 必须实际查询新增字段覆盖和引用，不能只检查 HTTP 200。隔离、重试、失败保护和 P0 业务链均须回归通过。

D-07/D-08 与 D-33/D-34 留 P2；Milvus 历史补数完成不代表多值过滤、UTF-8 字节长度问题已解决。D-06/D-10/D-11/D-13/D-14/D-15/D-16/D-17/D-18 留 P3，D-13 在 P3-05 决策。P1 不扩展 RAG、Rule Engine 或完整风险页面。
