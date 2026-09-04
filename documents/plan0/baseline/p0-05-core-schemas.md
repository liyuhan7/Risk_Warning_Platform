# P0-05 五个核心 Schema 定义

## 一、文档定位

对应 `documents/PLAN.md` 任务 `P0-05`，交付 PLAN.md:83 指定的五个核心契约：`EvidenceChunk`、`StructuredBehavior`、`RetrievalResult`、`RiskAnalysisContext`、`AnalysisResult`。

PLAN.md:130-138 的契约表另列 `AnalysisTaskScope` 与 `AiModelProvider`。二者不计入「五个」，但 `AnalysisTaskScope` 被全部五个 Schema 引用，必须同批冻结，否则 PLAN.md:161「三条开发线不得各自创建同名但不兼容的对象」无法落地。`AiModelProvider` 属服务边界而非数据 Schema，其版本字段要求已并入本文档第九节。

本阶段只冻结契约，不实现 Java 类。实现分别落在 `P1-02`（EvidenceChunk）、`P1-03`（StructuredBehavior）、`P2-05`（RetrievalResult）、`P3-02`（AnalysisResult）。

本文档状态：**已冻结**（`P0-11` 接口评审追认，2026-08-27，见 `p0-11-review-record.md` 4.1）。11 项 U 系列未决项已全部决议，见第十二节。

## 二、公共约定

### 2.1 字段必填性标记

| 标记 | 含义 |
| --- | --- |
| `M` | 必填，缺失或为 null 即校验失败 |
| `C` | 条件必填，触发条件写在字段说明中 |
| `O` | 可选，允许 null |

### 2.2 JSON 命名风格

统一 **camelCase**。

P0-09 实测修正：此处原记「`t_indicator` / `t_behavior` / `t_regulation` 存量文档均为 camelCase」不成立，只有 `t_indicator` 是 camelCase（1144 条），`t_behavior` 与 `t_regulation` 的存量数据为 snake_case，且 `t_risk` 亦为 snake_case。因此 `ElasticSearchConfig.java:61` 的 `PropertyNamingStrategy.SNAKE_CASE` 不能单独移除——移除会立刻破坏另外三个索引的读写。统一 camelCase 仍是目标，但须与存量数据迁移同批实施。该改动属缺陷 D-01，决策移交 `P0-11`，详见 `p0-09-field-contract.md`。

例外：向量字段沿用 `<语义名>_vector` 下划线形式（`descriptionVector` → `description_vector`），因为存量数据与 Java `@JsonProperty` 已如此约定。`P0-09` 已冻结该命名与 768 维、`index: true`、`similarity: cosine` 检索参数，见 D-26。

### 2.3 时间与数值

- 时间统一使用 Java `LocalDateTime`，**全系统约定为东八区（UTC+8）**，与存量 PO（`Behavior.java:35`、`IndicatorResult.java:98`）保持一致。已决议不引入带时区类型。
- JSON 序列化格式 `2026-08-27T15:04:05`，不带时区后缀。ES 侧 `date` 类型需显式声明 `format` 与该格式一致。
- 该约定的边界：系统仅服务单一时区。`ComplianceDomainEnum`（见 7.1.1）含 `CROSS_BORDER`（跨境交易与支付），若后续出现由境外系统提供的时间戳，必须在入口处转换为东八区再落库，不得直接存原始值。此风险已知并接受。
- 时间字段命名统一 `*At`（`createdAt`、`analyzedAt`），表示行为发生时刻的业务字段可用 `*Date`（`behaviorDate`）。
- 置信度、得分比例统一 `double`，闭区间 `[0.0, 1.0]`。
- 金额、定量事实值统一 `double` + 独立 `unit` 字符串，禁止把单位拼进数值字符串。

### 2.4 空值语义

**枚举字段禁止空字符串。** 语义未知必须使用该枚举显式定义的 `UNKNOWN` 成员。

这条约束直接针对已确认缺陷：`t_behavior.status` 全量为空串，使 `QualitativeCalculator` 恒返回 0.5 固定分；`t_indicator.type` 全量为空串，使 P0-04 无法按指标类型分组。空串既非「无值」也非「未知」，是纯粹的信息丢失。

### 2.5 Schema 自身版本

每个 Schema 顶层携带 `schemaVersion`（`M`，字符串，语义化版本）。本次冻结全部为 `1.0`。

演进规则：

- 新增可选字段 → minor 递增（`1.0` → `1.1`），旧消费者必须仍可解析。
- 新增必填字段、删除字段、修改字段类型或枚举语义 → major 递增，需重新走接口评审。
- 消费者遇到 major 高于自身支持范围的消息，必须显式拒绝并落错误记录，不得静默按缺省值继续。

Kafka Topic 不变（PLAN.md:140）。消息新增字段时旧消费者必须可读。

## 三、AnalysisTaskScope（支撑契约）

一次分析任务的作用域标识，作为其余 Schema 的公共前缀字段组。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `analysisRunId` | `M` | String | 单次分析运行的唯一标识（UUID）。因允许同一 assessment 重复分析，它是唯一能标识「本次运行」的字段，见 3.4 |
| `userId` | `M` | Long | 触发方 |
| `projectId` | `M` | Long | 所属项目 |
| `assessmentId` | `M` | Long | 所属评估批次，当前唯一可用的「本次分析」边界 |
| `sourceDocumentIds` | `C` | List\<Long\> | 本次输入的源文件标识集合。文档类任务必填 |
| `traceId` | `M` | String | 链路追踪标识 |

### 3.1 与现有 Message 的关系

`Message.java:15-27` 已含 `traceId / userId / projectId / assessmentId`，`AnalysisTaskScope` 是其超集，缺 `analysisRunId` 与 `sourceDocumentIds`。落地时优先扩展 `Message` 而非另建平行类，避免同语义两套对象。

### 3.2 查询约束（已在 P0-03 冻结）

禁止仅按 `projectId` 读取本次分析输入。凡「本次运行」语义的查询，必须按 `analysisRunId` 过滤。

`P0-03` 原冻结「`analysisRunId` 落地前一律用 `assessmentId` 过滤」，该规则以「一个 assessment 只分析一次」为隐含前提。决议允许重复分析后此前提不成立，规则失效，修正见 3.4。

### 3.3 sourceDocumentId 语义

指**单个源文件**的稳定标识，由 org 侧在上传时分配。

已决议：**`sourceDocumentId` 即改造后的 `ProjectFile.id`，`t_project_file` 改为一文件一行。**

现状 `ProjectFile` 一次上传批次只落一行，`filePaths` 是 JSON 路径数组（`ProjectFile.java:30-31`），id 是批次 id，粒度不足（ISO-01 / D-23）。改造要点：

1. 拆除 `filePaths` 字段与 `StringListJsonConverter`，改为单值 `filePath`。
2. `ExecuteQueueTask.java:71-89` 现在循环内只 save 一次 ProjectFile，需改为每文件一行。
3. 需要一个批次概念时另加 `uploadBatchId`（同批上传共享），不要复用 `id`。
4. 存量数据需迁移：把每行的 `filePaths` 数组展开为多行。迁移前必须备份 `t_project_file`（AGENTS.md 安全约定）。

该改造属 `P1-01` / `P1-02` 前置，不在 Plan 0 实施。Plan 0 只冻结「`sourceDocumentId` = 单文件行的 id」这一语义。

### 3.4 重复分析与结果保留

已决议两条：**同一 `assessmentId` 允许重复分析**；**只保留最近一次运行的结果**。

#### 3.4.1 只留最近一次不等于可以先删再写

现有实现 `BehaviorProcessingService.java:183` 在分析开始时 `deleteByAssessmentId` 清空旧结果，然后逐步写入新结果。表面上这已经符合「只留最近一次」，实际存在缺陷：**清空发生在新结果产出之前**。若新运行在中途失败（LLM 超时、ES 写入异常、进程崩溃），旧结果已被删除而新结果不完整，该 assessment 落入无有效结果的状态，且不可恢复。

P0-02 已记录本链路存在多个失败点（召回为空、regulation 挂载异常、`riskTriggered` 恒 false）。在真实 LLM 接入后失败概率只会上升。

冻结要求：**新运行的结果必须先以新 `analysisRunId` 完整写入并确认成功，再删除上一次运行的结果。** 顺序是「写新 → 校验 → 删旧」，不是「删旧 → 写新」。

#### 3.4.2 analysisRunId 仍然必须实现

「只留最近一次」不能省掉 `analysisRunId`。3.4.1 的安全替换要求新旧两批结果在替换窗口内同时存在于库中，此时必须有字段把它们分开——`assessmentId` 两批相同，做不到。

`analysisRunId` 在本决议下的职责：

1. 替换窗口内区分「正在写入的新运行」与「待删除的旧运行」。
2. 中断恢复时识别并清理不完整的运行残留。
3. 并发保护：同一 assessment 若被重复触发，可据此判定已有运行在途并拒绝或排队。

它不再用于长期保留多次运行的历史。

#### 3.4.3 运行状态

安全替换需要知道一次运行是否完整，因此运行本身需要状态标记（`RUNNING` / `SUCCEEDED` / `FAILED`）。存放位置有两种做法：扩展 `t_assessment_result`，或新建运行记录表。此项属实现细节，留给 `P1-01` 决定，但**状态字段必须存在**，否则 3.4.1 的「校验成功后再删旧」无判据。

只保留最近一次意味着无法在库内比对新旧结果。PLAN.md:114 要求的新旧规则可比性，需通过导出 Baseline 快照到 `documents/plan0/baseline/` 的方式满足，而非依赖库中并存。P0-02 已用此方式记录了改造前基线。

`Message.java` 与 `AnalysisTaskScope` 落地时必须同批加入 `analysisRunId`，否则 Kafka 链路上该字段丢失（`MessageTask.java:73-77`、`:82-85` 已有丢弃 `assessmentId` 的先例）。

## 四、EvidenceChunk

文档切分后的可引用证据单元，是全链路唯一的原文来源。

已决议：**EvidenceChunk 是文档级资产，不是运行级产物。** 它归属于源文档，不归属于某次分析运行，因此**不含 `analysisRunId`**。理由与后果见 4.5。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | `M` | String | 固定 `1.0` |
| `id` | `M` | String | 稳定证据 ID，生成规则见 4.1 |
| `sourceDocumentId` | `M` | Long | 来源文件标识（改造后 `ProjectFile.id`），本对象的归属主体 |
| `projectId` | `M` | Long | 冗余自 `ProjectFile`，便于查询，见 4.5 |
| `assessmentId` | `M` | Long | 冗余自 `ProjectFile`，指上传该文件的评估批次，非「分析它的那次运行」 |
| `sourceFileName` | `M` | String | 原始文件名，用于前端展示 |
| `pageNumber` | `C` | Integer | 1 起。分页格式（PDF/DOCX）必填；纯文本可为空 |
| `segmentIndex` | `M` | Integer | 页内段序号，0 起。无分页时为全文段序号 |
| `charStart` | `O` | Integer | 段首在源文档正文中的字符偏移 |
| `charEnd` | `O` | Integer | 段尾偏移，满足 `charEnd > charStart` |
| `text` | `M` | String | 原文，不得改写、摘要或翻译 |
| `textHash` | `M` | String | 归一化后 SHA-256 十六进制小写 |
| `createdAt` | `M` | LocalDateTime | 生成时间，东八区 |

### 4.1 ID 与哈希策略

`textHash` = SHA-256(归一化文本)。归一化仅做三步：去首尾空白、连续空白折叠为单个半角空格、统一换行为 `\n`。不做大小写转换，不删标点。

`id` = `sha256(sourceDocumentId + "|" + pageNumber + "|" + segmentIndex + "|" + textHash)` 取前 32 位十六进制字符。`sourceDocumentId` 为 Long，参与拼接时取十进制字符串形式；`pageNumber` 为空时以空串参与。

选择确定性 ID 而非随机 UUID 的原因：**同一源文档被重复解析时必须产出相同 `id`**，使写入可幂等。当前上传链路无任何幂等控制，N 次重复处理产生 N 倍 `t_behavior` 数据（也是 `t_behavior` 实际 2146 条与 Baseline 记录 2121 条差异无法追溯的原因）。确定性 ID 让重复写入退化为覆盖而非累加。

#### 4.1.1 该策略的幂等边界

这套 ID 只对**同一 `sourceDocumentId` 的重复解析**幂等，不对**同一物理文件的重复上传**幂等。

因为 `t_project_file` 改为一文件一行后，同一份文件上传两次会产生两个 `ProjectFile` 行、两个 `sourceDocumentId`，进而算出两套证据 ID。内容完全相同的文件在库中会有两份证据。

覆盖的场景与不覆盖的场景：

| 场景 | 是否幂等 |
| --- | --- |
| 同一文档重复分析（U-10 的重跑） | 是，证据 ID 不变，直接复用 |
| 处理中断后重试同一文档 | 是 |
| 同一物理文件二次上传 | 否，产生独立的 `sourceDocumentId` 与独立证据 |

后者是否需要按内容去重（例如对文件整体做哈希，命中则复用已有 `sourceDocumentId`），属上传链路的幂等设计，不在本 Schema 范围，留给 `P1-01`。本节只明确：**Schema 层面不承诺跨上传去重**，不要误以为确定性 ID 解决了 D-22 的全部情形。

### 4.2 校验规则

1. `text` 去空白后长度 ≥ 1，否则丢弃该 Chunk 并计入解析错误，不得写入空证据。
2. `textHash` 必须与 `text` 重算一致，不一致视为数据损坏。
3. `id` 必须与 4.1 规则重算一致。
4. `charStart` / `charEnd` 同时存在或同时为空，不允许只有一侧。
5. 同一 `(sourceDocumentId, pageNumber, segmentIndex)` 唯一。唯一键不含 `assessmentId`——证据归属源文档，加入 `assessmentId` 会允许同一文档同一位置存在多条证据，与文档级资产定位矛盾。

### 4.3 生产者与消费者

- 生产者：`EvidenceExtractionService.extract(scope, document)`（`risk-warning-processing`）。
- 消费者：`FactExtractionService`（引用生成 Behavior）、`RiskAnalysisContext` 组装、前端证据链展示（`P0-10` / `P4-01`）。
- 存储：**PostgreSQL 为权威存储**，ES 仅为检索副本。见 4.4。

### 4.4 存储职责划分

已决议：`EvidenceChunk` 与 `AnalysisResult` 均以 **PostgreSQL 为唯一真相源**，ES 只保存检索所需字段的副本。

| 存储 | 保存内容 | 职责 |
| --- | --- | --- |
| PostgreSQL | 全部字段，含 `text` 原文 | 权威读写、事务、外键约束、证据链关联查询 |
| ES 副本 | `id`、作用域字段、`text`、向量字段 | 仅供向量与全文检索，返回 `id` 后回 PostgreSQL 取权威数据 |

约束：

1. 写入顺序固定为先 PostgreSQL 后 ES。ES 写入失败不回滚 PostgreSQL，但必须落失败记录并支持重试补写。
2. 任何业务判断、评分、报告生成只能读 PostgreSQL。ES 检索结果只用于产出候选 `id`。
3. 禁止只更新 ES 副本而不更新 PostgreSQL。
4. 需要 ES 副本覆盖率校验手段（AGENTS.md 要求 ES 改动须验证实际持久化覆盖率，不能只看 HTTP 200）。

选择 PostgreSQL 的理由：证据链要能回证，需要外键与事务保证 `AnalysisResult → Behavior → EvidenceChunk` 的引用完整性；ES 无事务，做不到「引用的证据必然存在」这条校验（4.2 规则与 8.4 规则 1 均依赖它）。

这与现有 `Behavior` / `Indicator` / `Regulation` 全在 ES 的做法不同，两类存储策略将长期共存。既有三者不在本次改造范围。

### 4.5 文档级资产的含义

已决议 EvidenceChunk 为文档级资产。展开为四条约束。

**一、生命周期跟随源文档，不跟随运行。** 同一文档重复分析时证据不重新生成、不删除、不复制，直接复用已有记录。这与 3.4 的「只留最近一次」不冲突——被替换的是 Behavior、IndicatorResult、Risk，证据不在替换范围内。

**二、不含 `analysisRunId`。** 一份文档的切分结果不随分析次数变化，若强行加该字段，同一 `id` 会对应多个运行取值，字段无法赋值（这正是 U-11 的两难点）。运行归属由 `StructuredBehavior.analysisRunId` 承担：证据说明"原文在哪里"，Behavior 说明"哪次运行读出了什么"。

**三、`projectId` / `assessmentId` 是冗余字段，不是权威来源。** 二者的权威值在 `ProjectFile`（即 `sourceDocumentId` 指向的行）。保留它们只为避免每次查询都 join，写入时从 `ProjectFile` 复制。禁止独立更新这两个字段。这里的 `assessmentId` 含义是"该文件在哪次评估中被上传"，**不是**"哪次运行分析了它"。

**四、清理证据的唯一合法条件是源文档被删除。** 不得因运行失败、结果替换或重跑而删除证据。

#### 4.5.1 由此产生的一致性要求

证据不含运行标识，意味着"本次运行用了哪些证据"这一信息**只存在于 `StructuredBehavior.evidenceIds` 与 `AnalysisResult.evidenceIds`**。这两处引用因此成为证据链的唯一连接点，其完整性校验（5.3 规则 1、8.4 规则 1）不再是可选的防御性检查，而是链路可回证的必要条件——引用一旦丢失或指向失效，无法从证据侧反查补回。

#### 4.5.2 与 P0-03 冻结规则的偏差

`p0-03-isolation.md` 冻结了「Risk / IndicatorResult / Evidence / Behavior 必须共享同一个 `assessmentId`」。本决议使 Evidence 脱离该规则：它的 `assessmentId` 表示上传批次，与其余三者表示的分析批次语义不同，重复分析时也不随之变化。

该冻结规则需修正为：**Risk / IndicatorResult / Behavior 三者共享同一 `analysisRunId` 与 `assessmentId`；Evidence 只保证 `sourceDocumentId` 可回指到同一 `assessmentId` 的上传记录。** 修正待 `P0-11` 确认后回写 `p0-03-isolation.md`。

## 五、StructuredBehavior

从证据中抽取的企业事实。**只承载事实，不承载风险判断或合规结论**——后者归 `AnalysisResult`。

现有 `Behavior`（`Behavior.java:16-43`）为其前身，本 Schema 是扩展而非替换。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | `M` | String | 固定 `1.0` |
| `id` | `M` | String | 行为唯一标识 |
| `analysisRunId` | `M` | String | 见第三节 |
| `projectId` | `M` | Long | |
| `assessmentId` | `M` | Long | 隔离本次分析输入，现有 `Behavior` 缺失此字段（D-03） |
| `sourceDocumentId` | `C` | Long | 文档抽取来源。问卷等非文档来源可为空 |
| `subject` | `M` | String | 行为主体 |
| `action` | `M` | String | 行为动作 |
| `object` | `C` | String | 行为对象。动作为不及物语义时可为空 |
| `status` | `M` | Enum | 见 5.1，禁止空串 |
| `behaviorDate` | `O` | LocalDateTime | 行为发生时间，对应 PLAN.md:287 的 `occurredAt` |
| `quantitativeData` | `O` | Double | 定量事实值 |
| `quantitativeUnit` | `C` | String | 单位。`quantitativeData` 非空时必填 |
| `description` | `M` | String | 事实描述，不得脱离原文引入推断 |
| `type` | `O` | String | 行为类型。当前存量全为空串，语义待定义 |
| `dimension` | `O` | String | 风险维度，取值见 5.2 |
| `tags` | `O` | List\<String\> | |
| `confidence` | `M` | Double | 抽取置信度 `[0.0, 1.0]` |
| `evidenceIds` | `M` | List\<String\> | 至少一个 `EvidenceChunk.id` |
| `extractionModel` | `M` | String | 抽取模型标识 |
| `extractionPromptVersion` | `M` | String | 抽取 Prompt 版本 |
| `description_vector` | `O` | List\<Float\> | 768 维，命名见 2.2 |
| `createdAt` | `M` | LocalDateTime | |

### 5.1 BehaviorStatusEnum

沿用 `QualitativeCalculator.normalizeStatus`（`:117-129`）已有词表，不另造：

| 成员 | 中文 |
| --- | --- |
| `COMPLETED` | 已完成 |
| `IN_PROGRESS` | 进行中 |
| `PAUSED` | 暂停 |
| `TERMINATED` | 终止 |
| `UNKNOWN` | 未知 |

抽取端必须输出上述枚举码之一。`UNKNOWN` 表示证据不足以判定状态，是合法结论，但**不得**再由下游当作可计分的中性值处理——现状是 `status` 全量空串归一为 `UNKNOWN` 后返回固定 0.5 分，使定性计算完全失效（D-07）。冻结要求：`UNKNOWN` 必须走「证据不足」分支，不参与打分。

### 5.2 维度取值

取值受 `RiskDimensionEnum` 约束。已决议：**以 ES 存量取值为权威，修改枚举以对齐数据。**

`RiskDimensionEnum.java:15` 的 `国际化经营风险` 改为 `企业国际合作风险`，同时删除 `:44-47` 的硬编码特例分支。不改动 ES 中的 184 条文档。

冻结后的六个权威维度值：

| 枚举成员 | 权威取值 |
| --- | --- |
| `ENTERPRISE_RELATED_RISK` | 企业关联方风险 |
| `PRODUCT_LEGITIMACY_RISK` | 产品合规风险 |
| `LABOR_LEGITIMACY_RISK` | 劳务合规风险 |
| `ENTERPRISE_CREDIT_RISK` | 企业信用风险 |
| `ENTERPRISE_INTERNATIONAL_COOPERATION_RISK` | 企业国际合作风险 |
| `SUPPLY_CHAIN_RISK` | 供应链风险 |

`fromValue` 对未知维度仍抛 `IllegalArgumentException`。改造后须验证 `t_indicator` 全部 dimension 取值均能通过且不触发特例分支。此为 D-28 的修复方案。

### 5.3 校验规则

1. `evidenceIds` 非空，且每个 ID 必须能查到实际存在的 `EvidenceChunk`；查不到即视为幻觉引用，拒收该 Behavior。
2. `subject` / `action` / `description` 去空白后非空。
3. `confidence` 在 `[0.0, 1.0]` 内。
4. `quantitativeData` 非空时 `quantitativeUnit` 必填，防止无单位裸数值参与规则计算。
5. `status` 必须是 5.1 中的枚举码，空串直接判失败。
6. 同一 `assessmentId` 下重复写入需幂等，禁止累加。

### 5.4 生产者与消费者

- 生产者：`FactExtractionService.extract(scope, evidenceChunks)`。
- 消费者：`RetrievalService`（构造检索文本）、`RiskAnalysisContext`、前端事实展示。
- 查询接口必须接收 `assessmentId` 或 `analysisRunId`（PLAN.md:299）。

### 5.5 与现有 Behavior 的字段映射

| 本 Schema | 现有 `Behavior` | 处理方式 |
| --- | --- | --- |
| `subject` / `action` / `object` | 无 | 新增 |
| `behaviorDate` | `behaviorDate` | 保留原名，不改为 `occurredAt` |
| `quantitativeData` | `quantitativeData` | 保留原名，不改为 `quantitativeValue` |
| `quantitativeUnit` | 无 | 新增 |
| `assessmentId` / `sourceDocumentId` | 无 | 新增，隔离前置 |
| `confidence` / `evidenceIds` | 无 | 新增 |
| `extractionModel` / `extractionPromptVersion` | 无 | 新增 |
| `status` | `status`（String） | 类型收紧为枚举 |

PLAN.md:288 使用 `quantitativeValue` / `occurredAt`，与存量字段名冲突。已决议：**保留存量名称 `quantitativeData` / `behaviorDate`**，PLAN 术语视为同义别名，后续文档与前端类型一律以存量名为准。依据 AGENTS.md「保持现有 DTO、JSON、数据库和 ES 字段契约兼容」。

## 六、RetrievalResult

一次检索返回的单条候选，用于把 Behavior 关联到 Indicator 或 Regulation。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | `M` | String | 固定 `1.0` |
| `candidateType` | `M` | Enum | `INDICATOR` / `REGULATION` |
| `candidateId` | `M` | String | 候选对象 ES ID |
| `score` | `M` | Double | 检索得分，语义由 `scoreType` 限定 |
| `scoreType` | `M` | Enum | `COSINE_SIMILARITY` / `BM25` / `HYBRID` |
| `rank` | `M` | Integer | 本次检索内排名，1 起 |
| `matchedFilters` | `M` | Map\<String, String\> | 实际命中的过滤条件，无过滤时为空对象而非 null |
| `embeddingModel` | `M` | String | 向量模型标识 |
| `embeddingVersion` | `M` | String | 向量版本，用于区分回填批次 |
| `behaviorId` | `M` | String | 关联的 `StructuredBehavior.id` |
| `analysisRunId` | `M` | String | 见第三节 |
| `assessmentId` | `M` | Long | |
| `retrievedAt` | `M` | LocalDateTime | 东八区，见 2.3 |

### 6.1 校验规则

1. `candidateId` 必须能在对应索引查到真实对象，禁止返回悬空 ID。
2. `rank` 在单次检索结果内连续且唯一。
3. `score` 与 `scoreType` 必须匹配：`COSINE_SIMILARITY` 落在 `[0.0, 1.0]`。
4. `behaviorId` 必须回指本次 `assessmentId` 的 Behavior（PLAN.md:463）。
5. `matchedFilters` 记录实际生效的过滤，不得记录「打算过滤但未生效」的条件。

### 6.2 为什么必须记录 scoreType 和 embeddingVersion

P0-02 实测：金标准指标排名落在 65—279 位，而 Top-6 内相似度在 0.83—0.91 区间高度扁平。仅凭 `score` 数值无法判断检索质量，也无法区分是模型区分度不足还是过滤条件缺失。`scoreType` 与 `embeddingVersion` 是后续 Recall 评测（`P2-06`）可复现的前提。

`scoreType` 为本文档在 PLAN.md:431 字段清单之外新增的字段。已决议**接受**，理由如上。

## 七、RiskAnalysisContext

送入 LLM 合规推理的完整输入包。它是**只读聚合体**，不新增事实，全部内容可回溯到前序 Schema。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | `M` | String | 固定 `1.0` |
| `scope` | `M` | AnalysisTaskScope | 任务作用域 |
| `enterpriseProfile` | `M` | Object | 企业背景，见 7.1 |
| `projectProfile` | `M` | Object | 项目背景，见 7.1 |
| `behavior` | `M` | StructuredBehavior | 本次分析的目标事实 |
| `evidenceChunks` | `M` | List\<EvidenceChunk\> | `behavior.evidenceIds` 指向的完整证据 |
| `candidateIndicators` | `M` | List\<Object\> | 候选指标，含 `id`、`name`、`dimension`、`maxScore`、`calculationRule` |
| `candidateRegulations` | `M` | List\<Object\> | 候选法规，含 `id`、`name`、`fullText`、`direction`、`applicableSubject` |
| `retrievalResults` | `M` | List\<RetrievalResult\> | 候选的来源与排名，用于审计 |
| `assembledAt` | `M` | LocalDateTime | |

### 7.1 背景字段最小集

`enterpriseProfile`：`enterpriseId`、`name`、`complianceDomain`、`region`。
`projectProfile`：`projectId`、`name`、`projectType`、`orientedUser`、`complianceDomain`。

取值分别受 `ComplianceDomainEnum`、`RegionEnum`、`ProjectType`、`ProjectOrientedUserEnum` 约束。

### 7.1.1 industry 更名为 complianceDomain

已决议：`IndustryEnum` 更名为 `ComplianceDomainEnum`，字段名 `industry` 更名为 `complianceDomain`。

理由：该枚举成员是 `供应链管理`、`市场营销与广告`、`人力资源与劳动关系`、`跨境交易与支付`、`数据隐私与网络安全`、`反垄断与不正当竞争`、`知识产权`、`财务与税务`（`IndustryEnum.java:7-14`），这是合规领域分类而非行业分类。名实不符的字段在检索过滤中会静默筛掉正确候选，表现为召回率偏低，与真正的召回缺陷难以区分（D-29）。

影响范围：`Project`、`Regulation`、`Enterprise` 的 `industry` 字段，以及 ES 中 `t_regulation.industry`。ES 字段重命名属 `P0-09` 冻结范围，需以新字段写入而非覆盖旧字段。

PLAN.md:430 要求 `RetrievalFilter` 支持「行业」过滤，按本决议应理解为**按合规领域过滤**。系统当前不存在真正的行业分类维度；若后续确需行业过滤，另建独立枚举，不得复用本字段。

### 7.2 校验规则

1. `evidenceChunks` 必须与 `behavior.evidenceIds` 完全对应，数量与集合均一致；缺任一条即拒绝组装，不允许「带着不全证据去推理」。
2. `candidateIndicators` 与 `candidateRegulations` 至少一个非空，否则本次分析应直接判定为「无适用法规」而非调用 LLM。
3. `retrievalResults` 中的 `candidateId` 必须覆盖两个候选列表中的全部 ID。
4. `behavior`、`retrievalResults` 的 `analysisRunId` 与 `assessmentId` 均须与 `scope` 一致。这是跨 Schema 一致性的核心闸门；只校验 `assessmentId` 会放过同一评估下不同运行的数据混用。
5. `evidenceChunks` **不参与** `analysisRunId` 校验（证据为文档级资产，见 4.5）。对证据只校验其 `sourceDocumentId` 落在 `scope.sourceDocumentIds` 内。

### 7.3 生产者与消费者

- 生产者：`RiskAnalysisOrchestrator`（`risk-warning-processing`）。
- 消费者：`ComplianceAnalysisService.analyze(context)`。
- 不落库，仅为进程内传递对象；但组装失败原因必须落审计日志。

## 八、AnalysisResult

LLM 合规推理的结构化输出，是规则引擎的唯一合法输入来源。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | `M` | String | 固定 `1.0` |
| `id` | `M` | String | 结果唯一标识 |
| `analysisRunId` | `M` | String | 见第三节 |
| `assessmentId` | `M` | Long | |
| `behaviorId` | `M` | String | 被分析的事实 |
| `evidenceIds` | `M` | List\<String\> | 支撑本结论的证据，须为 `behavior.evidenceIds` 子集 |
| `indicatorId` | `M` | String | 本结论对应的指标 |
| `regulationIds` | `C` | List\<String\> | 最终采用的法规。`applicable=APPLICABLE` 时必填 |
| `applicable` | `M` | Enum | 见 8.1 |
| `requirement` | `C` | String | 法规要求的结构化摘要。`applicable=APPLICABLE` 时必填 |
| `enterpriseFact` | `M` | String | 由 Evidence 支持的企业事实陈述 |
| `complianceStatus` | `M` | Enum | 见 8.2 |
| `gapType` | `C` | Enum | 见 8.3。`complianceStatus=NON_COMPLIANT` 时必填 |
| `gapValue` | `O` | Double | 定量差距值 |
| `gapUnit` | `C` | String | `gapValue` 非空时必填 |
| `confidence` | `M` | Double | `[0.0, 1.0]` |
| `reasoning` | `M` | String | 简明理由，须可追溯到 Evidence 与 Regulation |
| `modelVersion` | `M` | String | 调用模型标识 |
| `promptVersion` | `M` | String | Prompt 版本 |
| `analyzedAt` | `M` | LocalDateTime | |

### 8.1 ApplicabilityEnum

| 成员 | 说明 |
| --- | --- |
| `APPLICABLE` | 法规适用于该企业事实 |
| `NOT_APPLICABLE` | 明确不适用 |
| `UNKNOWN` | 信息不足以判定适用性 |

采用三态枚举而非可空布尔（PLAN.md:568「允许未知」）。布尔 + null 会让「不适用」与「未判定」在序列化后不可区分。

### 8.2 ComplianceStatusEnum

| 成员 | 说明 |
| --- | --- |
| `COMPLIANT` | 合规 |
| `NON_COMPLIANT` | 不合规 |
| `INSUFFICIENT_EVIDENCE` | 证据不足以判定 |
| `NEEDS_REVIEW` | 需人工复核 |

`INSUFFICIENT_EVIDENCE` 与 `NEEDS_REVIEW` 均**不得**被规则引擎当作 `COMPLIANT` 处理。这两个状态必须走独立分支，产出「未判定」而非默认得分。

### 8.3 GapTypeEnum

| 成员 | 说明 |
| --- | --- |
| `MISSING_ACTION` | 应做未做 |
| `PROHIBITED_ACTION` | 禁止而做 |
| `QUANTITATIVE_SHORTFALL` | 定量未达标 |
| `QUANTITATIVE_EXCESS` | 定量超限 |
| `DOCUMENTATION_GAP` | 材料或记录缺失 |

### 8.4 校验规则

1. `evidenceIds` 非空且为对应 Behavior 的 `evidenceIds` 子集。引用集外证据视为幻觉，拒收。
2. `regulationIds` 中每个 ID 必须存在于 `context.candidateRegulations`。禁止 LLM 凭记忆产出未经检索的法规 ID。
3. `applicable=NOT_APPLICABLE` 时 `complianceStatus` 只能是 `COMPLIANT` 或 `INSUFFICIENT_EVIDENCE`，不得为 `NON_COMPLIANT`。
4. `applicable=UNKNOWN` 时 `complianceStatus` 必须是 `INSUFFICIENT_EVIDENCE` 或 `NEEDS_REVIEW`。
5. `complianceStatus=NON_COMPLIANT` 时 `gapType` 必填。
6. `gapValue` 非空时 `gapUnit` 必填。
7. `confidence` 低于阈值时状态强制置为 `NEEDS_REVIEW`。**阈值本次不设定**，由 `P3-02` 依据真实 LLM 输出的置信度分布确定；实现时必须做成可配置项，不得写死。在阈值确定前该规则不生效。
8. 校验失败必须落原始响应与失败原因，按 `P0-06` 的重试策略处理，**不得静默吞掉**（PLAN.md:169）。

### 8.5 生产者与消费者

- 生产者：`ComplianceAnalysisService.analyze(context)`。
- 消费者：`RuleEngine.evaluate(indicator, analysisResults)`，经 `RuleInput` 转换后进入规则计算；`t_indicator_result.calculation_details` 保存可复算详情。
- Report 侧只读 `riskTriggered` 与既定风险信息，不再重算统一阈值（PLAN.md:587）。

### 8.6 与规则引擎的衔接边界

规则引擎**不得解析自由文本**（PLAN.md:509）。可进入 `RuleInput` 的只有：`complianceStatus`、`gapType`、`gapValue`、`gapUnit`、`confidence`、证据完整性标志。`reasoning`、`requirement`、`enterpriseFact` 仅供展示与审计。

`RuleInput` / `RuleEvaluationResult` 的完整定义属 `P3-02`，不在本次冻结范围。但已确认的前置约束：当前 821 条 `riskRule` 使用绝对阈值 0.5，其中 89 条指标 `maxScore < 0.5` 导致满分仍恒触发风险；改造方案见 `p0-04-riskrule-design.md`，实施在 `P3-06`。

## 九、AiModelProvider 的版本字段要求

`AiModelProvider` 是服务边界而非数据 Schema，本次只冻结它必须向上述 Schema 提供的版本信息：

| 提供给 | 字段 |
| --- | --- |
| StructuredBehavior | `extractionModel`、`extractionPromptVersion` |
| RetrievalResult | `embeddingModel`、`embeddingVersion` |
| AnalysisResult | `modelVersion`、`promptVersion` |

业务代码不得依赖静态密钥或供应商专用响应结构（PLAN.md:138）。当前 `LLMUtil.java:23` 存在明文硬编码凭据，属 `P0-07` 处理范围（D-21），该凭据须先在供应商控制台吊销。

## 十、生产者与消费者总览

| Schema | 生产者 | 主要消费者 | 落库位置 |
| --- | --- | --- | --- |
| `AnalysisTaskScope` | org 侧上传 / 任务触发 | 全链路 | 随 Kafka 消息传递 |
| `EvidenceChunk` | `EvidenceExtractionService` | Fact 抽取、Context、前端 | PostgreSQL 权威 + ES 检索副本 |
| `StructuredBehavior` | `FactExtractionService` | 检索、Context、前端 | ES `t_behavior` |
| `RetrievalResult` | `RetrievalService` | Context、Recall 评测 | 不落库，审计日志 |
| `RiskAnalysisContext` | `RiskAnalysisOrchestrator` | `ComplianceAnalysisService` | 不落库 |
| `AnalysisResult` | `ComplianceAnalysisService` | `RuleEngine`、前端 | PostgreSQL 权威 |

## 十一、对现有契约的影响

本节列出落地时**必然**触及的既有契约，供评审判断改动范围。

| 影响项 | 现状 | 变化 | 关联缺陷 |
| --- | --- | --- | --- |
| ES 反序列化命名 | `SNAKE_CASE` | 改 camelCase，须与 `t_behavior` / `t_regulation` / `t_risk` 存量数据迁移同批 | D-01 |
| 未知属性容错 | `FAIL_ON_UNKNOWN_PROPERTIES=false` | 建议开启或改为显式告警 | D-01 |
| `Behavior` | 无作用域字段 | 新增 `assessmentId`、`analysisRunId`、`sourceDocumentId` | D-03 |
| `Behavior.status` | String 空串 | 收紧为枚举 | D-07 |
| `Message` | 无 `analysisRunId` | 扩展而非另建，Kafka 全链路须透传 | ISO-02 |
| `RiskDimensionEnum` | 枚举与 ES 取值不一致 | 改枚举为 `企业国际合作风险`，删除 `:44-47` 特例 | D-28 |
| 向量字段命名 | 文档记 `vector`，代码用 `*_vector` | 冻结为 `*_vector` | D-26 |
| `IndustryEnum` | 名为行业实为合规领域 | 更名 `ComplianceDomainEnum`，字段改 `complianceDomain` | D-29 |
| `ProjectFile` | 一批次一行，`filePaths` 为数组 | 改一文件一行，另设 `uploadBatchId`；存量需迁移 | D-23 / ISO-01 |
| `ExecuteQueueTask.java:71-89` | 循环内仅 save 一次 ProjectFile | 改为每文件一行 | D-23 |
| `BehaviorProcessingService.java:183` | 先 `deleteByAssessmentId` 再写入 | 改为「写新 → 校验 → 删旧」，避免新运行失败后旧结果已毁 | D-30 / U-10 |
| 时间类型 | `LocalDateTime` 无时区 | 保持不变，全系统约定东八区 | U-05（已接受该限制） |

## 十二、未决项

### 12.1 已决议（2026-08-27）

| 编号 | 决议 | 落地位置 |
| --- | --- | --- |
| `U-01` | `sourceDocumentId` = 改造后 `ProjectFile.id`；`t_project_file` 改为一文件一行，拆除 `filePaths`，批次概念另设 `uploadBatchId` | 3.3；实施 `P1-01` / `P1-02` |
| `U-02` | PostgreSQL 为 `EvidenceChunk` / `AnalysisResult` 唯一真相源，ES 仅存检索副本 | 4.4 |
| `U-03` | 保留存量名 `quantitativeData` / `behaviorDate`，PLAN 术语视为别名 | 5.5 |
| `U-04` | 接受新增 `scoreType` 字段 | 6.2 |
| `U-05` | 全系统统一 `LocalDateTime` 并约定东八区，不引入带时区类型 | 2.3 |
| `U-06` | 以 ES 取值为权威，改 `RiskDimensionEnum` 为 `企业国际合作风险`，删除硬编码特例 | 5.2；修复 D-28 |
| `U-07` | 允许同一 assessment 重复分析，`analysisRunId` 转为必填并须在 Plan 1 实现 | 3.4 |
| `U-08` | 复核阈值本次不设定，由 `P3-02` 依真实分布确定，实现须可配置 | 8.4 规则 7 |
| `U-09` | `IndustryEnum` → `ComplianceDomainEnum`，字段 `industry` → `complianceDomain` | 7.1.1；修复 D-29 |

| `U-10` | 重复分析只保留最近一次运行结果；替换顺序必须为「写新 → 校验 → 删旧」 | 3.4；修复 D-30 |
| `U-11` | `EvidenceChunk` 为文档级资产，移除 `analysisRunId`，运行归属由 `StructuredBehavior` 承担 | 4.5；`id` 哈希不含 `analysisRunId`（4.1） |

本文档全部 11 项未决项已决议完毕。

### 12.2 决议引出的后续事项

以下不是未决项，而是决议成立后必须执行的动作，登记在此以免遗漏。

| 编号 | 事项 | 归属 |
| --- | --- | --- |
| `F-01` | ~~回写 `p0-03-isolation.md`~~ **已完成**：过滤依据改 `analysisRunId`，Evidence 从共享 `assessmentId` 规则中分离，新增 ISO-07 与验证场景二/三。该文修订已由 `P0-11` 追认为已冻结 | 已关闭 |
| `F-02` | ~~运行状态字段的存放位置~~ **已决策**（`P0-11`）：新建运行记录表，以 `analysisRunId` 为主键 | `P1-01` 实施 |
| `F-03` | ~~上传链路是否按内容哈希去重~~ **已决策**（`P0-11`）：按文件内容哈希去重 | `P1-01` 实施 |
| `F-04` | `t_project_file` 存量数据展开迁移（一批次多文件 → 多行）。**窗口已定**（`P0-11`）：与 D-01 命名迁移同批，作为迁移顺序第 1 步 | `P1-01` 实施 |
| `F-05` | `industry` → `complianceDomain` 改名。**实施方式已变更**（`P0-11`）：原「并存→回填→移除」策略废止，改为随 D-01 一次性切换同窗口完成 6009 条回填，无并存回落期。Milvus 侧 `industry` → `compliance_domain` 须同步，否则检索过滤静默失效 | `P0-11` 记录 3.2 |

## 十三、验收对照

对照 PLAN.md:168 验收标准 4「五个核心 Schema 均有字段、必填性、枚举、示例 JSON、生产者、消费者和版本规则」：

| 要求 | 位置 |
| --- | --- |
| 字段 | 第四至八节各字段表 |
| 必填性 | 各表「必填」列，标记定义见 2.1 |
| 枚举 | 5.1、6 章 `candidateType`/`scoreType`、8.1、8.2、8.3 |
| 示例 JSON | `p0-05-schema-examples.json` |
| 生产者 / 消费者 | 4.3、5.4、6 章、7.3、8.5，总览见第十节 |
| 版本规则 | 2.5（Schema 自身）、第九节（模型与 Prompt 版本） |
