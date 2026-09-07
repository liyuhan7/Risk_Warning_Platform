# P1-06 基础部分执行计划（运行隔离幂等与结果安全替换）

> 拟定日期：2026-09-05；执行前由用户评审确认。
> 依据：[PLAN](../PLAN.md)（§8 P1-06、§9 Entry Gate ④⑤、§10 验收 4/9）、[p0-05 冻结契约](../plan0/baseline/p0-05-core-schemas.md)（StructuredBehavior 幂等语义）、[P1 对接说明](p1-handoff.md)（§2 发布顺序、§3 Entry Gate）、[defect-backlog](../plan0/baseline/defect-backlog.md)（D-22、D-30）、P1-01/02/03 执行记录与本计划评审决策。
> 前序：P1-01/02/03 已评审通过。本计划完成后应闭合 Entry Gate 第 ④ 条；①—③ 已由 P1-01～03 达成，⑤ 待业务环境迁移后复验。

## 1. 目标与验收口径

PLAN §8 `P1-06`（B）：实现运行隔离、Behavior 幂等、并发保护及安全替换；含 IndicatorResult/Risk 的 `analysisRunId` 落库与"当前有效结果"切换（2026-09-05 评审决策）；基础实现与回归前置到 P1-04 之前，P1-05 后再用真实抽取结果复验。

完成后应满足（对应 Entry Gate ④ / PLAN §10 验收 4、9 / D-22 / D-30 验证口径）：

1. **同 Run 重试不重复**：同一 `analysisRunId` 的消息被重投/重跑 N 次，Behavior、IndicatorResult、Risk 均不累加（D-22 验证：同一文档重复处理不产生等价重复记录）。
2. **同一 Assessment 两次 Run 互不覆盖**：Run A2 运行期间 A1 的 IndicatorResult/Risk 完好可查（D-30 验证：人为使第二次中途失败，第一次结果仍完好）。
3. **失败保护**：A2 中途失败时 A1 结果可查；只有 A2 结果完整写完后才切换"当前有效结果"，随后清理旧结果。
4. **并发保护**：切换与清理只执行一次（重复完成事件幂等返回已有基础）；同一评估在途运行唯一（已有 DB 部分唯一索引）。
5. 新写入的 IndicatorResult / Risk 文档携带非空 `analysisRunId`；报告读取与写入按运行作用域，不再按 `assessmentId` 混取多轮结果。

## 2. 现状盘点（P1-06 改造基点）

| 对象 | 现状 | 位置 |
| --- | --- | --- |
| Behavior 写入 | `LineProcessor` 每行为生成随机 `UUID` `id`；`LineRangeItemWriter` bulk **不设 `_id`**（D-22：每次运行全新建文档，同 run 重试累加） | LineProcessor.java:66、LineRangeItemWriter.java:154 |
| IndicatorResult | PG `t_indicator_result`，**无 runId**；`processProjectBehaviors` 开头 `deleteByAssessmentId(assessmentId)` **先删后写**（D-30）；写入按 `(assessmentId, indicatorEsId)` 查改 + 自研乐观锁 | BehaviorProcessingService.java:190、457、511-570；IndicatorResultRepository.java:76 |
| Risk | ES `t_risk`，**无 runId、无显式 `_id`**（bulk 全新建，重跑累积）；由 report `AssessmentServiceImpl.aggregateInformation` 生成（阈值 0.5）并 bulk 写入 | AssessmentServiceImpl.java:79-128 |
| report 汇总 | `aggregateInformation(userId, projectId, assessmentId)` **按 assessmentId** 读取 IndicatorResult → 写 Risk → 更新 Assessment 状态 → 发通知；P1-01 包装 `AnalysisRunCompletionService.aggregateAndComplete(userId, scope)` 负责"仅 RUNNING → 汇总 → SUCCEEDED、重复事件返回" | AnalysisRunCompletionService.java、AssessmentServiceImpl.java:55-145 |
| 状态流转 | 消息发送失败（org）与 Batch 失败（processing）已能标 FAILED；**指标/风险阶段失败无人标 FAILED**（run 悬挂 RUNNING）；report 汇总异常 → `aggregateAndComplete` 抛错但 run 未标 FAILED | AnalysisRunFailureService.java（processing） |
| 读取 | report 的 IndicatorResult/Risk 读取全部按 assessmentId（多轮结果切换前会混读） | AssessmentServiceImpl:61、ReportServiceImpl:61/272 |

## 3. 冻结语义（p1-handoff §2 / D-22 / D-30 已定，代码不得偏离）

- 发布顺序固定：**写新 → 完整性校验 → 标记 SUCCEEDED 并切换当前有效结果 → 清理旧结果**。失败保留上次成功结果。不要求永久保留全部历史结果。
- Behavior 幂等键 = `(analysisRunId, sourceDocumentId, textHash)`；不得用 `assessmentId`（同评估两次运行会互相误判覆盖）。
- 文件上传去重用文件内容哈希（F-03），与运行幂等**不可混用**；F-03 现状见第 9 节登记。
- 清理只针对"运行结果"（IndicatorResult/Risk/Behavior，D1=A 已确认含 Behavior），Evidence 是文档资产**永不随运行清理**（p0-05 4.5）。

## 4. 评审决策（2026-09-05 已确认，实施按此执行）

- **D1 清理范围 = 方案 A（结果 + Behavior 全清）**：切换成功后删除同 assessment 非当前 run 的 IndicatorResult（PG）、Risk（ES delete_by_query）、Behavior（ES delete_by_query by runId）——与 p0-05"只保留最近一次运行结果"一致；P1-05 新旧链对照改由 P1-07"同 run 内来源/版本并存"承载，不依赖跨 run 保留旧行为。
- **D2 Behavior 稳定 ID = 方案 1**：文档 `id` 与 ES `_id` 统一为 `sha256(analysisRunId + "|" + sourceDocumentId + "|" + textHash)` 前 32（textHash 用与 Evidence 相同的归一化文本 SHA-256）；存量 2146 条随机 UUID 不回填；算法回写 p0-05 StructuredBehavior §5。纯文本旧链行同样可算 textHash，统一稳定 id，无需随机回退。
- **D3 失败即终态 = 方案 1**：各阶段失败立即标 FAILED，消费端 catch 后不再抛出（消息视为已消费，权威状态在 PG）；重新发起走新评估/新 run；有限重投 + DLQ 登记为后续增强。
- **D4 悬挂 RUNNING = 登记不做**：本轮不实现超时扫描/Outbox；待 P1-05 接入真实抽取、任务时长可估后再评审带心跳的自动终态；"唯一 RUNNING 索引只限 RUNNING、FAILED 后可再建 run"保证失败后不被永久卡死，仅有悬挂 RUNNING 需要 D4 兜底（登记 backlog）。
- **D5 同 Run 重跑先清后抽（2026-09-06 真实模型实测后决策）**：`RealModelExtractionEvalTest` 实测 DeepSeek-v4-flash（temperature=0、关闭思考模式后）同输入两次调用的描述/事实拆分粒度仍不稳定（两轮归一化描述一致率 0.0~0.5），导致 D2 稳定 id（textHash 派生）在"同 run 消息重投/失败后重跑"场景可能产生不同 `_id` 而累加重复。因此同 `analysisRunId` 重跑进入抽取前，先清理该 run 已落库的 Behavior/结果（ES delete_by_query by runId，与 T4 清理同键复用），再执行抽取写入；D2 稳定 id 保留（同文覆盖快路径），但不作为同 run 去重的唯一依赖。验证：套件 2 真实链 S1（同 run 重投同一文档）落库后该 run 文档数 = 1 次抽取产物、无重复等价记录。

## 5. 任务拆分（一轮一个小任务）

### T1：Behavior 稳定 ID 与写入幂等（D-22 基础）

- 冻结 `Behavior.id` 规则（D2 已确认：统一稳定 id，先回写 p0-05）；`LineProcessor` 构造时按段文本计算 `textHash` 与稳定 `id`（替换随机 UUID）；纯文本旧链行同样可算 textHash，统一稳定，无随机回退分支。
- `LineRangeItemWriter`：index 操作显式 `.id(behavior.getId())`，使同 run 重试退化为覆盖。
- 测试：稳定 id 单测（同 run 同段重复处理 id 一致）；writer 幂等集成（同 `_id` 写两次 count=1）。

### T2：IndicatorResult 运行作用域与先写后切（D-30 基础，processing）

- `IndicatorResult` 增加 `analysisRunId` 列；[005 迁移](migrations/005_indicator_result_run.sql)（可空、历史 NULL、`(assessment_id, analysis_run_id, indicator_es_id)` 唯一索引兜底）。
- **移除 `processProjectBehaviors` 开头的 `deleteByAssessmentId`**；计算与写入全程携带 runId；查改键从 `(assessmentId, indicatorEsId)` 改为 `(assessmentId, analysisRunId, indicatorEsId)`；同 run 重试走 upsert（唯一键 + 乐观锁逻辑适配）。
- Report/其他按 `findByAssessmentId` 的读取方同步收窄为按 `(assessmentId, runId)`（report 消费端已有 runId，见 T3）。
- 测试：processing 单测（查询键）+ PG 持久化（专用库：同 run 重写 upsert 不重复、两 run 并存、迁移约束）。

### T3：Risk 运行作用域与 report 汇总按 run（report）

- `AnalysisRunCompletionService.aggregateAndComplete` 把 `scope.getAnalysisRunId()` 传入 `AssessmentService.aggregateInformation`（签名加 `analysisRunId`）。
- `AssessmentServiceImpl.aggregateInformation(userId, projectId, assessmentId, analysisRunId)`：按 `(assessmentId, analysisRunId)` 读 IndicatorResult；生成的 `Risk` 携带 `analysisRunId`；bulk 写 ES 前显式 `.id(...)`（稳定规则：`sha256(analysisRunId|assessmentId|indicatorEsId|name)` 前 32 或等价，T3 冻结）。
- `documents/es_mappings.json` 的 `t_risk` 增加 `analysisRunId` keyword（增量 PUT mapping，不重建）。
- report 读取（`ReportServiceImpl.fetchRisksFromES`、`assembleIndicatorResult`）从"按 assessmentId 读全部"改为"按当前成功 run 读"——由于 T4 清理后仅剩当前 run 结果，读取键先切到 `(assessmentId, analysisRunId)` 并显式传入；"解析当前成功 run"的服务方法（`AnalysisRunRepository.findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc`）随读取入口落地。
- 测试：report 单测（runId 传递、Risk 文档含 runId、幂等返回已存在用例回归）。

### T4：切换与清理（report 完成侧）

- `AnalysisRunCompletionService.aggregateAndComplete`：现有逻辑（存在性/FAILED 拒绝/SUCCEEDED 幂等返回/RUNNING → 汇总 → `succeed`）保持不变，在 `succeed` 成功后执行**清理本 assessment 非当前 run 的结果**（D1=A）：
  - IndicatorResult：`deleteByAssessmentIdAndAnalysisRunIdNot(assessmentId, currentRunId)`（同事务或独立事务，须幂等可重试）；
  - Risk（ES）：`delete_by_query` `assessmentId + NOT analysisRunId`；
  - Behavior（ES）：同键 delete_by_query。
- 并发保护：清理只发生在"RUNNING→SUCCEEDED"转变成功的这一次（`succeed` 失败即不清理；重复事件在转变前 return）；同评估唯一 RUNNING 由 DB 保证。
- 测试：AnalysisRunCompletionServiceTest 扩展（切换后清理调用一次、重复事件不重复清理、失败不清理）；ES/PG 集成验证"两次运行 → 切换后仅剩第二次结果"。

### T5：失败路径状态闭合（processing + report）

- `MessageTask`（indicator 消费）：catch 中调用 `analysisRunFailureService.markFailed(scope)` 将 run 置 FAILED 后**不再抛出**（D3 已确认：消息视为已消费，权威状态在 PG，避免与终态冲突的无限重投）。
- `AnalysisRunCompletionService.aggregateAndComplete` 汇总异常路径：捕获后标 FAILED（独立事务），标 FAILED 本身失败时保留原始异常并告警（与 `markDispatchFailed` 容错模式一致）。
- 保留超时扫描（D4）为 backlog 登记，不在本任务实现。
- 测试：indicator 消费失败 → run FAILED 且消息不重投（单测/mock）；report 汇总失败 → run FAILED。

### T6：集成验证、回归与文档收尾

- 场景回归（ES + PG 专用环境，测试索引/专用库）：
  - 同 assessment 连续两次 run（A1 SUCCEEDED → A2 RUNNING → A2 成功切换），验证 A2 运行期间 A1 可查、切换后仅剩 A2 结果；
  - A2 中途失败 → A1 完好；A2 重试不重复；
  - 行为/指标/风险同 run 重试 count 不变。
- P1-01/02/03 定向测试全量回归（跳过项纪律不变）。
- 文档：defect-backlog D-22/D-30 更新（实施+验证）；p0-05 回写 Behavior.id 规则（D2）；PLAN §8 P1-06 进度与 P1-01 完成口径（P1-06 落库完成后 P1-01 可按新口径勾选）；`p1-06-execution.md`。

## 6. 涉及文件

修改（主代码）：
- common：`po/behavior/Behavior` 无字段变化（仅 id 语义）；`po/indicator/IndicatorResult`（+analysisRunId）；`po/risk/Risk`（+analysisRunId）
- processing：`batch/LineProcessor`（稳定 id）、`batch/LineRangeItemWriter`（显式 _id）、`service/BehaviorProcessingService`（去 delete、runId 查改键）、`repository/IndicatorResultRepository`（新查询/删除）、`task/MessageTask`（indicator 失败路径）
- report：`service/AnalysisRunCompletionService`（切换后清理）、`service/impl/AssessmentServiceImpl`（runId 签名、Risk 带 runId 与 _id）、`repository/IndicatorResultRepository`、`repository/AnalysisRunRepository`（当前成功 run 查询）、`ReportService*`（读取键）
- 迁移：`documents/plan1/migrations/005_indicator_result_run.sql`
- 文档：`es_mappings.json`（t_risk）、p0-05、defect-backlog、PLAN、p1-06-execution.md

## 7. 005 迁移草案（T2 冻结）

```sql
BEGIN;
ALTER TABLE public.t_indicator_result
    ADD COLUMN analysis_run_id VARCHAR(64);
ALTER TABLE public.t_indicator_result
    ADD CONSTRAINT fk_indicator_result_run FOREIGN KEY (analysis_run_id)
        REFERENCES public.t_analysis_run (analysis_run_id) ON DELETE RESTRICT;
-- 历史行 analysis_run_id 为 NULL，不参与唯一约束；NULL 不判重
CREATE UNIQUE INDEX uq_indicator_result_assessment_run_indicator
    ON public.t_indicator_result (assessment_id, analysis_run_id, indicator_es_id)
    WHERE analysis_run_id IS NOT NULL;
COMMIT;
```

评审点：外键引用 `t_analysis_run` 是否必要（倾向保留，运行记录生命周期随 P1-01 决议"显式处理"）；`indicator_es_id` 是否允许 NULL。

## 8. 完成标准与不做清单

完成标准：
1. 同 Run 重试：Behavior/IndicatorResult/Risk 均不累加（ES/PG 实测）。
2. 新 Run 失败：旧结果完好可查；成功切换后旧结果被清理（D1=A：含 Behavior）。
3. IndicatorResult/Risk 落库 `analysisRunId` 非空；报告读写不再按 assessmentId 混取。
4. 失败即终态：指标/风险阶段失败 → run FAILED 且不无限重投。
5. P1-01～03 定向测试无回归；D-22/D-30 更新为已修复并附验证。

不做：
- 不做运行超时扫描/Outbox（D4 登记 backlog）。
- 不做文件内容哈希去重（F-03 现状核实后登记，见第 9 节）。
- 不迁移/回填存量 2146 行为与历史 IndicatorResult/Risk 的 id/runId（存量查询语义不受影响，因新键查询不命中旧行）。
- 不实现 LLM 抽取（P1-04/05）、来源/版本标识（P1-07）。D1=A 下切换后仅剩当前 run 行为，前端展示无需 run 过滤（P1-08 不受此约束）。
- 不删 Evidence（文档资产）。

## 9. 风险与登记

1. **F-03（上传内容哈希去重）现状核实**：`FileServiceImpl.initUpload` 仅在 Redis 有效期内对同项目文件 hash 拒重；跨期二次上传仍产生新 `ProjectFile`（Evidence 4.1.1 已知边界）。本计划不做，登记待后续上传链改造（若 P1-10 验收出现重复上传场景再评审）。
2. report 汇总（Risk 写 ES + assessment 更新 + 通知）与 run 状态更新无法构成分布式原子事务；本计划通过"重复事件幂等返回 + 切换后清理幂等"做补偿，跨服务精确一次不承诺（沿用 p1-01 记录口径）。
3. `Behavior.id` 改为确定性后，存量随机 id 行为与新增稳定 id 行为并存；P1-07 来源标识落地前靠 id 前缀/字段空值区分（登记）。
4. 清理删除量大时（单次运行数百 Risk/上千 Behavior）delete_by_query 耗时——接受（不要求永久保留）；如需分批/异步删除后续评审。
5. `AnalysisRunCompletionService` 汇总抛出时如何保证 run 状态一致性：T5 定"失败标 FAILED"路径后，若标 FAILED 本身也失败，保留原始异常并告警（与既有 `markDispatchFailed` 的容错模式一致）。
