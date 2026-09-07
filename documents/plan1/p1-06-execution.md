# P1-06 基础执行记录

> 开工日期：2026-09-05；分支：main。范围为运行隔离、同 Run 幂等、失败闭合和成功后的安全替换基础，不含超时扫描、Outbox、Evidence 清理或历史数据回填。

## 已实现的代码

- Behavior 使用 `sha256(analysisRunId|sourceDocumentId|sha256(normalizeText(text)))` 前 32 位作为 `id`，并由批量 ES 写入显式作为 `_id`；同 Run 的等价文本重试覆盖既有文档，不同 Run 使用不同 ID。
- `IndicatorResult` 增加可空 `analysisRunId`；处理侧按 `(assessmentId, analysisRunId, indicatorEsId)` 查找并以本次完整计算结果覆盖同键记录，不再在计算开始时删除整个 assessment。`005_indicator_result_run.sql` 增加列、外键和仅对非空 Run 生效的唯一索引，历史记录保持 NULL。
- Risk 增加 `analysisRunId`；报告汇总仅读取当前 Run 的指标结果，并以 `sha256(analysisRunId|assessmentId|indicatorEsId|name)` 前 32 位作为 Risk 的 ES `_id`。普通报告读取最近成功 Run，避免 A2 运行中混入 A1/A2。
- 报告仅在 RUNNING 成功转为 SUCCEEDED 后执行一次清理：删除同 assessment 非当前 Run 的 IndicatorResult，以及 ES 中非当前 Run 的 Risk 和 Behavior。Evidence 不参与清理。
- 指标计算异常会将对应 Run 标记 FAILED 且结束该消费任务；FILE_UPLOAD 文档处理、事实抽取或行为写入异常也会标记 FAILED，并在工作任务内消化异常。当前异步执行器下旧异常表现为工作线程未捕获异常，不会回传到已返回的 Kafka listener；修复同时防止未来执行方式变化时对已失败 Run 重投和重复调用 LLM。报告聚合异常会标记 FAILED，消息监听器记录异常而不无限重投。清理异常不会反向改写已成功的 Run 状态。

## 验证

- `LineProcessorScopeTest`：同 Run 的归一化等价文本 ID 相同，改变 Run 后 ID 不同。
- `LineRangeItemWriterTest`：批量与逐条降级写入共用稳定 Behavior ID，缺失 ID 时拒绝写入。
- `IndicatorResultRunScopeTest`：同 Run 结果按三元键定位并覆盖，不合并重试数据。
- `MessageTaskScopeTest`：指标后台任务失败时调用失败终态服务。
- `AssessmentServiceRunScopeTest`：Risk 携带 Run ID，稳定 ID 在同 Run 重试时保持不变。
- `AnalysisRunCompletionServiceTest`：成功后只清理一次；重复成功事件不清理；报告聚合失败时标 FAILED 且不清理。

## 真实环境切片验证（2026-09-06）

前置环境为本地 PostgreSQL、Elasticsearch 8.11、Kafka、BERT 与真实 DeepSeek Provider；使用 assessment 58、project 6、sourceDocumentId 44。验证前成功 Run A1 为 `9dde4ff0-8a38-4b0a-bc54-ada7dad7b198`，基线包含 Evidence 28 条、Behavior 82 条、IndicatorResult 180 条、Risk 53 条，报告总分 10.30423538322933、等级 HIGH_RISK。

- **S1 同 Run 重投通过**：重跑后 Behavior 82 条而非累加到 144 条，82/82 均有 768 维向量；IndicatorResult 为 180 条而非累加。真实模型输出非确定，验收口径是该 Run 仅保留一次完整抽取产物。
- **S2 新 Run 失败保留 A1 通过**：创建 A2 `bae27a4d-9af1-436b-9a4e-af7001b396b1`，用合法 sourceDocumentId 与不一致路径触发作用域校验失败。A2 转为 FAILED，Behavior/IndicatorResult/Risk 均为 0；A1 仍为 SUCCEEDED，Behavior 82、IndicatorResult 180、Risk 53，Evidence 28，报告仍为 10.30423538322933 / HIGH_RISK。
- **S3 新 Run 成功切换通过**：创建 A3 `182a7493-2608-4aff-bef5-dc6217f1628d`，复用 sourceDocumentId 44 的权威持久化路径执行完整链路。A3 转为 SUCCEEDED，写入 Behavior 57（57/57 有 768 维向量）、IndicatorResult 135、Risk 59；报告切换为总分 5.464015261045522、MEDIUM_RISK（风险 59、安全 76）。切换后 A1 的 Behavior、IndicatorResult、Risk 均为 0，A2 仍无结果，Evidence 28 条及 ID 边界保持不变。行为、指标、完成事件三个 Kafka 消费链均无积压。

## 当前限制与部署前置

- 真实切片环境已执行数据库迁移与 ES 增量 mapping；其他环境仍须按相同顺序部署，不重建、不删除、不回填历史 ES 文档。
- S3 首次轮询时 A3 已完成，未在观测侧捕获 A3 RUNNING 的中间瞬间；S2 已独立证明失败终态不会清理 A1，单元测试继续覆盖运行中查询隔离。
- Kafka 反序列化毒丸与 `__TypeId__` header 依赖已登记 D-35、D-36，不影响标准 Spring 类型 header 的业务生产链。
