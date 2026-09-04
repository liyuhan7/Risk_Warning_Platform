# P0-03 数据隔离核对与改造清单

> 核对日期：2026-08-27
> 仓库基线：`main`，提交 `a851e09`
> 对应计划：`documents/PLAN.md` 开发计划 0 任务 `P0-03`
> 验收依据：计划 0 验收标准第 6 条——三个作用域 ID 的传递和查询规则书面冻结，明确禁止只按 `project_id` 读取本次输入

本文只做核对与规则冻结，不含代码改动。改造条目的落地归属在每条中标注。

> **修订说明（依 P0-05 决议预修正）**
>
> `P0-05` 决议「允许同一 assessment 重复分析、只保留最近一次结果」与「EvidenceChunk 为文档级资产」，推翻了本文初版的两项前提。受影响内容已就地修正并标注，涉及 §1、§1.1、§1.2、ISO-01、ISO-07（新增）、§4 条款 1/3/4、§5、§6。
>
> 修正依据 `p0-05-core-schemas.md` 第 3.4 节与第 4.5 节，待 `P0-11` 一并确认。

## 1. 作用域字段定义（本阶段冻结）

| 字段 | 语义 | 生成方 | 本阶段状态 |
| --- | --- | --- | --- |
| `projectId` | 项目标识，代表长期存在的业务实体 | org 项目创建 | 已存在，**不再作为本次分析输入的查询依据** |
| `assessmentId` | 一次评估，界定「属于哪次评估」 | org 侧 `ExecuteQueueTask` 创建 Assessment 时产生 | 已存在于消息与下游结果，Behavior 层缺失。**不再是本次分析输入的权威边界**（见 1.1） |
| `sourceDocumentId` | 单个源文档的持久标识，用于证据回溯定位。类型 `Long`，取值即改造后的 `ProjectFile.id` | **org 侧上传落库时分配**（已决策） | 尚不存在，需新增 |
| `analysisRunId` | 同一 Assessment 内的一次执行，是本次分析输入的**权威边界** | Processing 编排入口生成 | 尚不存在，**须在计划 1 落地，不再推迟**（见 1.1） |

### 1.1 `assessmentId` 与 `analysisRunId` 的关系

`assessmentId` 标识「用户发起的一次评估」，`analysisRunId` 标识「系统对该评估的一次执行」，二者为一对多。

**本节初版的结论已作废。** 初版认为「重跑覆盖前次结果，尚不需要独立执行标识」，据此把 `analysisRunId` 推迟到计划 1 之后并规定过渡期一律用 `assessmentId`。`P0-05` 决议后该推论不成立：

1. 已决议**允许同一 assessment 重复分析**。`assessmentId` 只能界定「属于哪次评估」，界定不了「属于哪次运行」，因此不能再充当本次分析输入的边界。
2. 已决议**只保留最近一次运行结果**，但替换顺序必须改为「写新 → 校验成功 → 删旧」。初版依据的 `deleteByAssessmentId` 先清理再写入正是要被废除的做法——新运行中途失败时旧结果已毁而新结果不完整，该 assessment 落入无有效结果且不可恢复的状态（详见 ISO-07）。
3. 安全替换要求新旧两批结果在替换窗口内**同时存在于库中**，此时必须有字段将二者分开，而两批的 `assessmentId` 相同。

因此 `analysisRunId` 从「只冻结定义」改为**计划 1 必须落地**。它在「只留最近一次」前提下的职责是：

- 替换窗口内区分「正在写入的新运行」与「待删除的旧运行」；
- 中断恢复时识别并清理不完整的运行残留；
- 并发保护：同一 assessment 被重复触发时判定已有运行在途。

它不用于长期保留多次运行的历史。新旧结果可比性通过导出 Baseline 快照到 `documents/plan0/baseline/` 满足，不依赖库中并存。

落地要求：`Message` 基类新增该字段并保持旧消费者可读；Kafka 全链路必须透传，`MessageTask.java:73-77`、`:82-85` 已有丢弃 `assessmentId` 的先例，不得重演。安全替换还需要运行状态字段（`RUNNING`/`SUCCEEDED`/`FAILED`），否则「校验成功后再删旧」无判据，存放位置留给 `P1-01`。

### 1.2 `sourceDocumentId` 的生成方式

已决策由 org 侧在上传落库时分配持久 ID，不使用文件内容哈希。理由是前端证据定位需要一个与文件实体绑定、可被接口引用的稳定标识，内容哈希在同一文件重复上传时无法区分上传批次。

**但现有实体粒度不足。** `ProjectFile`（`ProjectFile.java:19-35`）是一行存一个 `filePaths` 数组（`:30-31` 经 `StringListJsonConverter` 存为 JSON 列），一次上传多个文件只产生一行。其自增主键 `id`（`:21-23`）语义上是**上传批次 ID**，不是单文档 ID。`ExecuteQueueTask.java:71-89` 在循环中向同一个 `projectFile` 累加路径后只 `save` 一次，印证了这一粒度。

因此 `sourceDocumentId` 需要新的一文件一行的存储粒度，见改造条目 ISO-01。

**粒度方案已由 `P0-05` 决议（原未决项 U-01）：** `t_project_file` 改为一文件一行，拆除 `filePaths` 字段与 `StringListJsonConverter`，`sourceDocumentId` 即改造后的 `ProjectFile.id`（`Long`）。需要批次概念时另设 `uploadBatchId`，不复用 `id`。存量数据须把每行的 `filePaths` 数组展开为多行，迁移前必须备份 `t_project_file`。

该决议同时明确了幂等边界：确定性证据 ID 只对**同一 `sourceDocumentId` 的重复解析**幂等，不对**同一物理文件的重复上传**幂等——同一份文件上传两次会产生两个 `ProjectFile` 行、两个 `sourceDocumentId`。是否按文件内容哈希去重属上传链路设计，留给 `P1-01`，与 ISO-06 的行为幂等键是两个不同层面的问题。

## 2. 逐环节传递现状

`assessmentId` 在上游产生、下游消费，但中间的行为生产环节完全不接收它，导致消费端只能退回按 `projectId` 查询。

下表为核对当日（2026-08-27）的实测现状，不因后续决议而改变。`analysisRunId` 列未列出——该字段在全部环节均不存在，须按 1.1 在计划 1 整链路引入。

| 环节 | 位置 | `projectId` | `assessmentId` | `sourceDocumentId` |
| --- | --- | --- | --- | --- |
| 创建 Assessment | `ExecuteQueueTask.java:93-103` | 有 | **产生** | 无 |
| 发送行为处理消息 | `ExecuteQueueTask.java:105-115` | 有 | 有 | 无（只有 `filePaths`） |
| 消费行为处理消息 | `MessageTask.java:60-66` | 有 | 有（消息中） | 无 |
| 文档处理 | `MessageTask.java:73-77` | 有 | **丢弃** | 无 |
| Batch 作业入口 | `MessageTask.java:82-85`、`BatchJob.java:41` | 有 | **丢弃** | 无 |
| Batch 作业参数 | `BatchJob.java:47-48` | 有 | **无** | 无（只有 `filePaths` 拼接串） |
| 构造 Behavior | `LineProcessor.java:20-34` | 有 | **无从注入** | 无 |
| Behavior 持久化 | `Behavior.java:16-43` | 有 | **字段不存在** | **字段不存在** |
| 发送指标计算消息 | `MessageTask.java:97-104` | 有 | 有（从原消息重取） | 无 |
| 读取 Behavior | `BehaviorProcessingService.java:187`、`:840-848` | **按此查询** | **无法使用** | 无 |
| 写 IndicatorResult | `IndicatorResult.java:41-47` | 有 | 有 | 无 |
| 写 Risk | `Risk.java:24` | — | 有 | 无 |
| 读 Risk | `ReportServiceImpl.java:282` | — | **按此过滤** | 无 |

结论：断裂集中在 **Behavior 这一层**。下游 `IndicatorResult` 与 `Risk` 均已按 `assessmentId` 隔离，`assessmentId` 之所以能到达下游，是因为 `MessageTask.java:97-104` 从原始 Kafka 消息重新取值转发，绕过了中间被丢弃的环节，并非中间链路真正携带了它。

## 3. 改造清单

按依赖顺序排列，不可乱序。ISO-02 是其余条目的前置。

### ISO-01 `sourceDocumentId` 的存储粒度与分配

- 问题：`ProjectFile` 一行存路径数组，无单文档标识。
- 涉及：`risk-warning-common/.../po/file/ProjectFile.java`、`risk-warning-org/.../task/ExecuteQueueTask.java:71-89`、`FileRepository`
- 要点：`t_project_file` 改一文件一行，拆除 `filePaths` 与 `StringListJsonConverter`，改为单值 `filePath`；`sourceDocumentId` 即改造后的 `ProjectFile.id`（`Long`）；批次概念另设 `uploadBatchId`。`ExecuteQueueTask.java:71-89` 现在循环内只 `save` 一次，须改为每文件一行。`BehaviorProcessingTaskMessage.filePaths`（`:14`）需同时携带 ID 与路径的对应关系，仅传路径无法反查。保持旧消费者可读。
- 存量迁移：把每行 `filePaths` 数组展开为多行，**迁移前必须备份 `t_project_file`**。
- 归属：计划 1（`P1-01`/`P1-02`）
- 决策状态：**已冻结**。生成方为 org 侧，粒度方案由 `P0-05` 决议（原 U-01）。优先级由 P2 上调为 P1——`EvidenceChunk` 与 `StructuredBehavior` 均以它为必填锚点。

### ISO-02 `Behavior` 增加作用域字段（前置阻断项）

- 问题：`Behavior.java:16-43` 只有 `projectId`；`documents/es_mappings.json:2-24` 的 `t_behavior` mapping 同样没有作用域字段。存储层无字段，查询层无论怎么改都无法隔离。
- 涉及：`Behavior.java`、`documents/es_mappings.json`、运行时 ES mapping
- 要点：新增 `assessmentId`、`analysisRunId`、`sourceDocumentId`（初版只列前者与后者，`analysisRunId` 依 1.1 修正一并补入；`sourceDocumentId` 类型为 `Long`）。字段命名须与 `P0-09` 的命名策略结论一致——`Behavior.java:20` 现用 `@JsonProperty("project_id")` 显式指定 snake_case，而 `t_indicator` 实际文档为 camelCase，两套命名并存问题见 `p0-02-baseline.md` 根因 1。**在 P0-09 冻结命名前不得先行添加字段**，否则会再增一处不一致。
- 归属：计划 1（`P1-03`），依赖 `P0-09`

### ISO-03 Batch 链路透传作用域

- 问题：`assessmentId` 在 `MessageTask.java:73-85` 两次调用中被丢弃，`BatchJob.java:47-48` 的 `jobParameters` 只有 `projectId` 与 `filePaths`，`LineProcessor.java:20-21` 因此无从注入。
- 涉及：`MessageTask.java`、`BatchJob.java`、`LineProcessor.java`、`LineRangePartitioner.java:25-26`
- 要点：`runBatchJob` 签名与 `jobParameters` 增加 `assessmentId`、`analysisRunId` 与文档标识；`LineProcessor` 注入后写入 Behavior。注意 Spring Batch 以 `jobParameters` 组合判定作业实例唯一性——加入 `analysisRunId` 后每次运行的 `jobParameters` 天然不同，重复分析不再撞作业实例；这解决了初版列为未决项的重跑语义问题，但也意味着 Spring Batch 自身不再阻止重复提交，并发保护须由 ISO-07 的运行状态承担。
- 归属：计划 1（`P1-01`），依赖 ISO-02

### ISO-04 `fetchBehaviors` 改为按 analysisRunId 查询

- 问题：`BehaviorProcessingService.java:187` 调用 `fetchBehaviors(projectId)`，`:840-848` 以 `term(project_id)` + `size=10000` 取该项目**全部历史行为**。这是 PLAN.md:73 记录的问题，核对确认仍存在。
- 涉及：`BehaviorProcessingService.java:800`、`:840`
- 要点：改为按 **`analysisRunId`** 过滤（初版写「按 `assessmentId` 过滤」，允许重复分析后不再充分——同一 assessment 的多次运行会互相混入）。`processProjectBehaviors` 已接收 `assessmentId`（`:174`），但需增加 `analysisRunId` 参数。行为数超过 10000 时需改用 scroll 或 search_after。
- 归属：计划 1（`P1-06`），依赖 ISO-02、ISO-03

### ISO-05 `findByProjectId` 返回单实体的语义缺陷

- 问题：`AssessmentRepository.findByProjectId` 在 processing 侧（`:14`）与 org 侧（`:10`）均返回单个 `Assessment`。一个 project 存在多个 assessment 时行为未定义，Spring Data 可能抛 `IncorrectResultSizeDataAccessException`。`ProjectServiceImpl.java:144` 正在使用。
- 涉及：两个 `AssessmentRepository`、`ProjectServiceImpl.java:144`
- 要点：按调用意图改为返回列表或「取最新一条」的显式查询。三案例每个 project 各有一次评估，故 Baseline 采集未暴露此问题，多次评估后必现。
- 归属：计划 1，可独立于 ISO-02 进行

### ISO-06 行为写入缺幂等键

- 问题：上传链路对同一文件不做幂等清理，重传 N 次产生 N 倍行为。`test-cases.md:99` 记录首轮 CASE-001 上传 4 次得到 27 条行为，`p0-02-baseline.md:14` 记录采集前须清零。
- 涉及：`LineRangeItemWriter`、`LineProcessor`
- 要点：以 `(analysisRunId, sourceDocumentId, textHash)` 为幂等键（初版用 `assessmentId`，允许重复分析后同一 assessment 的两次运行会被误判为重复而互相覆盖）。已实测该缺陷不影响评分（同指标下多行为取平均，`BehaviorProcessingService.java:412`，清理前后总分逐位相同），故不属于污染 Baseline 的 `P0-08` 范围，但会放大存储与计算开销。
- 归属：计划 1（`P1-06`），依赖 ISO-02

### ISO-07 结果替换顺序与运行状态（依 P0-05 决议新增）

- 问题：`BehaviorProcessingService.java:183` 在分析开始时 `deleteByAssessmentId` 清空旧结果后再逐步写入新结果。已决议「只保留最近一次运行结果」，表面符合，实际缺陷在于**清空发生在新结果产出之前**：新运行中途失败（LLM 超时、ES 写入异常、进程崩溃）时旧结果已删而新结果不完整，该 assessment 落入无有效结果且不可恢复的状态。`p0-02-baseline.md` 已记录本链路存在多个失败点，真实 LLM 接入后失败概率上升。
- 涉及：`BehaviorProcessingService.java:183`、`IndicatorResultRepository`、Risk 写入路径、运行状态存储
- 要点：替换顺序固定为 **写新（新 `analysisRunId`）→ 校验成功 → 删旧**，禁止先删后写。需引入运行状态字段（`RUNNING`/`SUCCEEDED`/`FAILED`），否则「校验成功」无判据；状态存放位置（扩展 `t_assessment_result` 或新建运行记录表）留给 `P1-01`。同一 assessment 若已有 `RUNNING` 运行在途，须拒绝或排队。
- 归属：计划 1（`P1-01`/`P1-06`），依赖 1.1 的 `analysisRunId` 落地
- 关联：缺陷清单 D-30
- 验证：同一 assessment 连续跑两次，第二次完整替换第一次；**人为使第二次中途失败，第一次结果仍完好可查**

## 4. 查询规则冻结条款

以下条款自本文件冻结，适用于计划 1 及之后所有阶段。

条款 1、3、4 已依 `P0-05` 决议修正，原文一并保留以便对照。

1. **禁止按 `projectId` 读取本次分析输入。** 凡涉及「本次运行的 Behavior」的查询，必须以 **`analysisRunId`** 为过滤条件。`projectId` 只可用于项目级历史统计与列表展示，不得用于喂入分析链路。
   *（原文为「必须以 `assessmentId` 为过滤条件」，且把 Evidence 与 Behavior 并列。允许重复分析后 `assessmentId` 不足以界定单次运行；Evidence 的归属另见条款 3。）*
2. 任何消费 `indicator_calculation_tasks` 的处理逻辑，其读取的 Behavior 集合必须完全落在消息携带的 `analysisRunId` 内。
3. Risk、IndicatorResult、Behavior 三者必须同属一个 `analysisRunId` 与 `assessmentId`，跨作用域引用视为数据缺陷。**Evidence 不受此条约束**：它是文档级资产，其 `assessmentId` 表示「该文件在哪次评估中被上传」而非「哪次运行分析了它」，重复分析时不随之变化。对 Evidence 只要求其 `sourceDocumentId` 可回指到同一 `assessmentId` 的上传记录。
   *（原文为「Risk、IndicatorResult 及其引用的 Evidence / Behavior 必须同属一个 `assessmentId`」。`P0-05` 第 4.5 节决议 EvidenceChunk 为文档级资产、不含 `analysisRunId`，故须从该规则中分离。）*
4. 需要「本次执行」语义处必须使用 `analysisRunId`，不得用 `assessmentId`、`traceId` 或消息 ID 代偿。
   *（原文为「`analysisRunId` 落地前一律使用 `assessmentId`」。该过渡条款以「一个 assessment 只分析一次」为隐含前提，前提已不成立，条款作废。）*
5. 新增作用域字段前，命名策略须先由 `P0-09` 冻结，避免再产生 snake_case / camelCase 并存。
6. Behavior 写入必须可幂等，重复处理同一文档不得产生等价重复记录。
7. 结果替换必须遵循「写新 → 校验成功 → 删旧」，禁止先删后写（见 ISO-07）。
8. Evidence 的清理条件仅为**源文档被删除**。不得因运行失败、结果替换或重跑而删除 Evidence。

### 4.1 证据链引用完整性的强制要求

Evidence 不携带 `analysisRunId` 带来一个后果：「本次运行用了哪些证据」这一信息**只存在于 `StructuredBehavior.evidenceIds` 与 `AnalysisResult.evidenceIds`**。这两处引用因此成为证据链的唯一连接点，引用一旦丢失或指向失效，无法从证据侧反查补回。

故其完整性校验（`p0-05-core-schemas.md` 5.3 规则 1、8.4 规则 1）不是可选的防御性检查，而是链路可回证的必要条件：`evidenceIds` 必须非空，且每个 ID 必须能查到实际存在的 EvidenceChunk，查不到即拒收该对象。

## 5. 隔离验证方法

对应计划 0 测试方式「用两个相同 `projectId`、不同 `assessmentId` 的样本验证数据隔离预期」。允许重复分析后需补充第二组场景——同一 `assessmentId` 的两次运行隔离。

前置：选定一个 project，清零其 `t_behavior`（现有链路无幂等，见 ISO-06）。

### 5.1 场景一：跨 Assessment 隔离

1. 对同一 project 上传材料 A，记录生成的 `assessmentId`（记为 A1）与行为数 Na。
2. 对同一 project 上传材料 B，记录 `assessmentId`（记为 A2）与本次新增行为数 Nb。
3. 按 A2 查询本次分析输入。

判定：

- 期望：返回行为数 = Nb，且全部来自材料 B。
- 当前实测预期：返回 Na + Nb 条，A1 的历史行为混入。这是 ISO-04 未改造前的必然结果，可作为改造前的失败基线。
- 改造后须补充：A1 与 A2 的 IndicatorResult 集合无交叉；A2 的 Risk 引用的 Behavior ID 全部属于 A2。

### 5.2 场景二：同 Assessment 跨运行隔离（依 P0-05 决议新增）

1. 对 A2 再次触发分析，记录新的 `analysisRunId`（记为 R2，首次为 R1）。
2. 在 R2 写入过程中按 `analysisRunId` 分别查询 R1 与 R2 的 Behavior、IndicatorResult。
3. 等待 R2 完成，再次查询。

判定：

- 替换窗口内：R1 与 R2 的结果集可各自独立查出且无交叉。
- R2 成功后：只存在 R2 的结果，R1 已被删除（只留最近一次）。
- Evidence 不受影响：两次运行引用同一批 EvidenceChunk，证据记录数不增加、`id` 不变（文档级资产，见条款 3、8）。
- 当前实测预期：无 `analysisRunId` 字段，该场景无法执行；且 `deleteByAssessmentId` 会在 R2 开始时即删除 R1 结果，步骤 2 查不到任何 R1 数据。

### 5.3 场景三：失败运行不破坏已有结果（对应 ISO-07）

1. 对已有成功结果的 A2 触发新运行 R3。
2. 人为使 R3 中途失败（例如断开 LLM 或 ES 连接）。
3. 查询 A2 的结果。

判定：

- 期望：R1/R2 的结果完好可查，R3 的不完整残留可被识别并清理。
- 当前实测预期：旧结果已在 R3 开始时被删除，A2 无任何有效结果——这是 ISO-07 改造前的失败基线。

场景一在 ISO-02 至 ISO-04 完成后应从失败转为通过；场景二、三依赖 `analysisRunId` 落地与 ISO-07。三者共同构成计划 1 `P1-06` 的验收证据。

## 6. 未决项

### 6.1 已由 P0-05 决议

| 原未决项 | 决议 | 出处 |
| --- | --- | --- |
| `sourceDocumentId` 的存储粒度方案 | `t_project_file` 改一文件一行，`sourceDocumentId` = `ProjectFile.id`（Long），批次另设 `uploadBatchId` | `p0-05-core-schemas.md` 3.3（原 U-01） |
| Batch `jobParameters` 增加参数后的重跑语义 | 加入 `analysisRunId` 后每次运行的 `jobParameters` 天然不同，不再撞作业实例；并发保护改由 ISO-07 的运行状态承担 | 本文 ISO-03 |

### 6.2 仍未决

| 事项 | 影响 | 需决策方 | 最晚决策 |
| --- | --- | --- | --- |
| Behavior 作用域字段命名（snake_case / camelCase） | ISO-02 能否开工 | A、B（`P0-09`） | 计划 1 启动前 |
| 运行状态字段的存放位置（扩展 `t_assessment_result` 或新建运行记录表） | ISO-07 实现方式 | B（`P1-01`） | ISO-07 实施时 |
| 上传链路是否按文件内容哈希去重（覆盖同一物理文件二次上传） | 是否需要在 ISO-01 之外补充上传级幂等 | B（`P1-01`） | 计划 1 启动前 |

### 6.3 本文修订状态

本次依 `P0-05` 决议所做的预修正（§1、§1.1、§1.2、ISO-01 至 ISO-04、ISO-06、ISO-07、§4 条款 1/3/4 及新增条款 7/8、§5）已由 `P0-11` 接口评审追认，状态为**已冻结**，可作为计划 1 的最终契约依据。见 `p0-11-review-record.md` 4.1。

6.2 的三项「仍未决」也已在同次评审决策：Behavior 作用域字段命名随 D-01 统一为 camelCase（记录 1.1）；运行状态字段新建运行记录表并以 `analysisRunId` 为主键（记录 4.2）；上传链路按文件内容哈希去重（记录 4.3）。
