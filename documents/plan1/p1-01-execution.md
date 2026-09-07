# P1-01 执行记录

> 开工日期：2026-09-05；分支：main。遵守一轮一个小任务，提交与推送由用户执行。
> 依据：[P1 对接说明](p1-handoff.md)、[PLAN](../PLAN.md)、P0-11 独立运行记录决议。

## 任务拆分与依赖

1. **运行身份与持久化基础（本轮）**：AnalysisScope、AnalysisRunStatus、AnalysisRun、AnalysisRunRepository、增量 DDL、状态与数据库约束测试。
2. **创建运行与消息契约（已完成）**：在评估归属验证后创建运行，明确同评估在途时拒绝行为；Kafka Message 透传运行 ID，处理数据库提交与消息发送时序，保留旧消息兼容边界。
3. **文档身份链（已完成）**：逐文件传递 ProjectFile.id，持久化评估/文件关联，贯通 DocumentProcessingService、BatchJob、LineProcessor，并保留页码与分段顺序。
4. **结果侧接线（入口边界已完成）**：评估完成事件按完整运行作用域汇总报告；仅 RUNNING 可执行，成功后标记 SUCCEEDED，重复成功事件不重复汇总。IndicatorResult/Risk 的运行字段落库归 P1-06（P1-03 计划评审 2026-09-05 决策，与"当前有效结果"切换一并实施）。

P1-01 已实现运行身份、消息、文档和报告入口边界；其“至 Behavior、IndicatorResult、Risk 的运行 ID 真实落库”验收分别随 P1-03（Behavior）与 P1-06（IndicatorResult/Risk）完成，不能提前标为整体完成。P1-06 继续处理跨服务发布的失败恢复。

## 本轮实现与边界

- common/dto/analysis/AnalysisScope：不可变项目、评估、运行身份；项目与评估必须为正数，运行 ID 为 1 至 64 位且不含首尾空白。运行 ID 的生成在后续入口接线实现。
- common/po/analysis/AnalysisRun：独立 t_analysis_run，文本状态 RUNNING/SUCCEEDED/FAILED、开始结束时间、JPA 乐观锁 version；只允许 RUNNING 转终态。
- processing/repository/AnalysisRunRepository：按运行/评估/项目联合查询；在途查询不代替数据库唯一索引。
- [001_analysis_run.sql](migrations/001_analysis_run.sql)：单事务、一次性执行的增量脚本。组合外键校验评估与项目一致，部分唯一索引保证同评估至多一个 RUNNING，校验状态和结束时间。重复执行会失败，避免静默接受漂移 Schema。
- 时间沿用现有 Java LocalDateTime，DDL 显式 TIMESTAMP WITHOUT TIME ZONE；全链时区标准化不属于本轮。
- 外键默认拒绝删除仍被运行记录引用的评估，调用方后续必须显式处理运行记录生命周期；本轮尚无生产运行记录。

业务数据库尚未应用迁移，因此上传入口的新运行写入还不能在当前业务环境启用。实体 succeed 只变更状态，不能代替完整性校验与当前有效结果指针切换。终态不可逆由实体 API 与乐观锁保障，直接 SQL 写入不具备实体状态机语义。

## 运行创建与消息契约

- 上传确认事务保存 Assessment 后创建 UUID 运行身份并写入 RUNNING 记录；创建前校验评估属于消息中的项目，应用层先查在途状态，数据库部分唯一索引处理并发竞态。
- 首条 BehaviorProcessingTaskMessage 在事务提交后发送并等待 Broker 确认。事务回滚不发送；发送失败在新事务中将对应 Run 标为 FAILED，且不会删除既有成功结果。
- Message 基类增加 analysisRunId；Behavior、Indicator、Risk 和 AssessmentCompleted 消息保留旧构造器及无参反序列化入口，旧 JSON 可读取。P1 消费入口会在文档处理前拒绝缺运行 ID 的旧消息，不能静默按 projectId 执行。
- Behavior 消息创建 Indicator 消息时保留 projectId、assessmentId、analysisRunId 和 traceId；指标计算入口继续把运行 ID 传给 BehaviorProcessingService，AssessmentCompletedEventMessage 也携带同一运行 ID。Report 消费端先校验三层身份，再调用现有评估级汇总。
- 手工触发接口路径增加 analysisRunId。调用者必须传已存在且归属正确的运行；运行存在性将在结果侧接线任务统一验证，本轮不在 Controller 重复实现仓储检查。

当前发送方案关闭了“事务回滚但消息已发出”的窗口，但不是持久化 Outbox：数据库提交后、进程执行 afterCommit 回调前若崩溃，RUNNING 记录可能没有消息。MVP 后续需要运行超时扫描或 Outbox 才能自动恢复；本轮保留该限制，不将其包装为精确一次投递。

## 文档身份链

- `t_project_file` 增加允许为空的 `assessment_id`，通过 `(assessment_id, project_id)` 组合外键约束文件所属评估与项目一致；历史文件保持为空，不能被 P1 新链选中。上传确认事务先保存 Assessment，再保存各文件的归属。
- `BehaviorProcessingTaskMessage` 增加 `documents`，每项含 `sourceDocumentId` 与已落盘的 `filePath`；保留旧 `filePaths` 供反序列化兼容，但 P1 消费入口只接受完整文档身份。
- 消费前按文件 ID、项目、评估查询 `ProjectFile`，同时比对消息路径与数据库路径，拒绝跨评估、跨项目、重复 ID 或路径篡改。由此不能仅凭调用方传入的路径读取文件。
- 文档处理把每段写为 UTF-8 JSONL，记录 `sourceDocumentId`、页码、段序号和文本；每个运行使用确定性内部文件名。分区任务通过 JSON 参数传递文档列表，避免逗号出现在路径中时被拆分；`LineProcessor` 校验行内文件 ID 与分区 ID 一致。
- 运行结束仅清理本运行生成的内部 JSONL，不再删除整个项目内部目录。行为模型的持久化作用域字段留给 P1-03，本轮不把 `analysisRunId` 伪装成已落库隔离。

## 结果侧运行边界

- Report 消费 `AssessmentCompletedEventMessage` 时以项目、评估和运行 ID 联合读取 `AnalysisRun`；不存在、已失败或身份不匹配的消息均不会生成报告。
- 仅 RUNNING 运行调用既有评估汇总；汇总方法正常返回后才将该运行标为 SUCCEEDED。已成功运行收到重复事件直接返回，不会再次写入风险或通知。
- 报告汇总包含现有 ES 写入和通知等外部副作用，无法与运行状态更新构成分布式原子事务；P1-06 应为失败恢复补充幂等键和补偿策略。本轮不承诺跨服务精确一次。

## Batch 失败边界与线程池隔离

- 当前 `JobLauncher.run` 保持同步调用；分区 `taskExecutor` 只并发 Job 内部 worker，`run` 返回时全部分区已结束。因此指标消息发送和内部 JSONL 清理发生在 Batch 终态之后，不存在“Batch 尚未读取就删文件”的当前竞态。
- 调用方现在显式检查 `isJobExecutionSuccessful`。Batch 不是 `COMPLETED` 时不发送指标消息，处理服务在独立事务中将该 `AnalysisRun` 标记为 FAILED；报告侧因只接受 RUNNING 运行而不会错误标记成功。
- 外层文件处理任务与 Batch 分区 worker 分别使用 `FileProcessTaskThreadPool` 和 `BatchPartitionTaskThreadPool`，避免高并发下外层线程等待同一线程池中的分区任务。
- 同 Run 的 Batch 幂等、ES 稳定写入 ID、Kafka 消费确认与失败重试仍属于 P1-06，尚未实现。

## 验证

- P1-01 定向 Maven 测试：37 项通过，0 失败/错误/跳过；其中 35 项为不依赖外部服务的单元测试，2 项连接专用 PostgreSQL 15 临时库。
- `mvn -q -pl risk-warning-processing -am test -Dtest=AnalysisRunTest -Dsurefire.failIfNoSpecifiedTests=false`：5 项通过，0 失败/错误/跳过。
- [数据库约束回归](../../test/fixtures/p1/analysis_run_constraints.sql)：只允许在专用空 PostgreSQL 测试库运行，覆盖在途唯一性、评估项目归属、状态/时间校验、连续运行、跨评估独立性及文件评估归属。已在临时 PostgreSQL 15 实例（127.0.0.1:55440，独立临时数据目录）执行通过，最终 A1=SUCCEEDED、A2=FAILED、B1=RUNNING。此项验证数据库约束，不声称已完成消息消费并发处理。
- `AnalysisRunPersistenceTest` 与 `ProjectFileRepositoryPersistenceTest`：专用 PostgreSQL 库上各 1 项通过、0 跳过；Hibernate validate 通过，前者实际写入/作用域查询、乐观锁覆盖保护通过，后者确认文件查询同时限定文件、项目、评估三项身份。
- `git diff --check` 通过。
- 消息与运行创建针对性测试：MessageAnalysisRunIdTest 2 项、AnalysisRunServiceTest 3 项、AnalysisRunMessageDispatcherTest 5 项、MessageTaskScopeTest 3 项，加 AnalysisRunTest 5 项，合计 18 项通过；覆盖新旧 JSON、归属校验、在途拒绝、提交后发送、回滚不发送、作用域错配拒绝、Broker 失败标记和下游消息 ID 保持。
- 文档身份链针对性测试：MessageAnalysisRunIdTest 文档 JSON 往返 1 项、SourceDocumentScopeValidatorTest 2 项、DocumentProcessingServiceTest 1 项、BatchJobScopeTest 1 项、LineRangePartitionerTest 1 项、LineRangeItemReaderTest 1 项、LineProcessorScopeTest 3 项、MessageTaskScopeTest 追加 1 项，合计 11 项通过；覆盖 UTF-8、页码/段序号、含逗号路径、文档身份错配与缺失运行身份。
- `AnalysisRunCompletionServiceTest`：3 项通过；覆盖完整运行作用域校验、成功后状态更新、重复完成事件幂等返回，以及未知或失败运行拒绝汇总。
- Batch 失败边界：`BatchJobScopeTest` 1 项、`MessageTaskScopeTest` 5 项、`AnalysisRunFailureServiceTest` 2 项通过；覆盖结构化参数、失败 Batch 阻断下游和对应运行终止。
- 真实 Kafka/业务全链和文件关联尚未验证，后续接线前补齐；单元测试中的消息透传与失败重跑独立性不能视为 D-30 闭环。


数据库复验顺序：准备专用空库 → 用 `psql -X -v ON_ERROR_STOP=1 -f test/fixtures/p1/analysis_run_constraints.sql`（另显式传测试连接参数）执行 fixture → 设置 P1_TEST_DATABASE_URL/P1_TEST_DATABASE_USER（必要时 P1_TEST_DATABASE_PASSWORD）→ 运行下列 Maven 命令。fixture 为一次性空库脚本；每次完整复验使用新的专用空库。

```powershell
mvn -q -pl risk-warning-processing -am test '-Dtest=AnalysisRunTest,AnalysisRunPersistenceTest,ProjectFileRepositoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

未设置 P1_TEST_DATABASE_URL 时数据库测试会显式跳过，不能把跳过计作持久化通过。本轮测试设置了该变量，确认数据库测试实际执行。临时数据库测试完成后停止；业务数据库尚未迁移。
