# P1-03 执行计划（Behavior 作用域与查询隔离）

> 拟定日期：2026-09-05；执行前由用户评审确认。
> 依据：[PLAN](../PLAN.md)（§7/§8/§9/§10）、[p0-05 冻结契约](../plan0/baseline/p0-05-core-schemas.md)（第五节 StructuredBehavior 与 5.5 字段映射）、[p0-11 评审记录](../plan0/baseline/p0-11-review-record.md)（D-01 camelCase、D-03 方案）、[defect-backlog](../plan0/baseline/defect-backlog.md)（D-03/D-04）、[P1 对接说明](p1-handoff.md)、[P1-01/P1-02 执行记录](p1-01-execution.md)。
> 前序：P1-01（运行/文件身份 + Batch 终态门禁）、P1-02（EvidenceChunk PG 权威存储）已通过评审；本计划完成后满足 Entry Gate 第 2 条"Behavior 三个作用域字段非空且真实落库"。

## 1. 目标与验收口径

按 PLAN §8 `P1-03`（B）：更新 Behavior、ES Mapping、Repository、DTO 与查询，保存完整作用域、证据引用和抽取元数据，**禁止只按项目取本次行为**。对应缺陷 D-03（存储层无作用域字段）、D-04（按项目全量取数）。

完成后应满足：

1. 新写入的 Behavior 文档含非空 `projectId/assessmentId/analysisRunId/sourceDocumentId`（D-03 验证口径：mapping 与 Java 字段一致，实际写入文档含非空 `assessmentId`）。
2. `fetchBehaviors` 不再按 `projectId` 全量取数；同 project 两个 assessment、同 assessment 两个 run 的查询互不混入（D-04 验证口径）。
3. ES 新字段通过实际查询确认已持久化并可按作用域过滤（AGENTS：不止检查 HTTP 200）。
4. 旧行为记录（无作用域字段，如存量 2146 条）天然不进任何作用域查询，不迁移、不删除、不回填。
5. P0 上传 → 行为 → 指标 → 风险 → 报告链在 P1-03 落库字段后无回归（LineProcessor 生成的行为开始带作用域）。

## 2. 现状盘点（P1-03 改动基点）

| 对象 | 现状 | 位置 |
| --- | --- | --- |
| `Behavior` PO | 仅 `id/projectId/description/type/dimension/tags/status(String)/quantitativeData/behaviorDate/descriptionVector/createdAt`；无任何作用域/证据/抽取元数据 | `common/po/behavior/Behavior.java:15-40` |
| t_behavior Mapping | properties 见 [es_mappings.json](es_mappings.json)，camelCase；无作用域/证据字段 | documents/es_mappings.json t_behavior 节 |
| ES 写入 | `LineRangeItemWriter` 批量 `bulk.document(behavior)` 写 t_behavior，未设 `_id`（D-22，属 P1-06） | processing/batch/LineRangeItemWriter.java:154 |
| 行为构造 | `LineProcessor.process` 已持有 `projectId/assessmentId/analysisRunId/sourceDocumentId`（Batch 作用域校验后），但构造 Behavior 时只填 `projectId/description` 等 | processing/batch/LineProcessor.java:61-73 |
| 取数 | `BehaviorProcessingService.fetchBehaviors(projectId)` → `doFetchBehaviors` term(projectId) + size=10000（D-04）；唯一调用点在 `processProjectBehaviors(:190)`，该方法已带 `assessmentId+analysisRunId` 参数 | BehaviorProcessingService.java:174-194、805-879 |
| 读取方 | 仅 `fetchBehaviors`（指标计算输入）；Report 读 Risk 不读 Behavior；knowledge 向量化只遍历 description | — |

## 3. 冻结契约摘要（p0-05 第五节 + p1-handoff §2，代码不得偏离）

### 3.1 演进后 Behavior = 现有 Behavior 扩展（p0-05 §5.5），字段命名以存量为准

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `assessmentId` / `analysisRunId` / `sourceDocumentId` | M（sourceDocumentId 问卷等来源可为 C） | Long / String / Long | 作用域；`analysisRunId` 为运行级，`sourceDocumentId` 指向 ProjectFile.id |
| `subject` / `action` / `object` | M / M / C | String | 企业事实三元结构；P1-03 先落字段，值由 P1-04/05 抽取填充 |
| `quantitativeUnit` | C | String | 与既有 `quantitativeData` 配对；`quantitativeData` 非空时必填（5.3 规则 4） |
| `confidence` | M | Double `[0,1]` | P1-03 先落字段（默认语义随 P1-05），值由抽取填充 |
| `evidenceIds` | M | List\<String\> | 至少一个 EvidenceChunk.id；P1-03 先落字段，引用完整性校验随 P1-05 启用（5.3 规则 1） |
| `extractionModel` / `extractionPromptVersion` | M | String | 抽取审计版本；旧链不产生则留空，来源区分见 P1-07 |
| `status` | M | **维持 String 类型不变** | p0-05 5.1 枚举化属 StructuredBehavior 抽取产物；存量文档 `status=""` 与旧分类链兼容优先，类型收紧随 P1-05 新链产物落地 |
| `schemaVersion` | — | — | StructuredBehavior Schema 字段；是否写入 t_behavior 待 3.3 决策 |

保留存量字段名：`quantitativeData`/`behaviorDate`（p0-05 §5.5 决议，PLAN 的 `quantitativeValue`/`occurredAt` 为同义别名，不使用）。

### 3.2 隔离语义（p1-handoff §2 / p0-11 决议）

- Behavior 是**运行级事实**：作用域 = `projectId + assessmentId + analysisRunId + sourceDocumentId`；幂等键 `(analysisRunId, sourceDocumentId, textHash)`（写入幂等实施在 P1-06，本计划不实现）。
- 证据引用只存在于 `Behavior.evidenceIds`；本计划只落字段，校验由 P1-05 在写入新链产物时执行。
- 旧记录（无 `assessmentId`）不得进入作用域查询——term 过滤对缺失字段不命中，天然排除，无需数据迁移。

### 3.3 评审决策（2026-09-05 已确认，同步回写 PLAN 与 P1-01 执行记录）

1. **`schemaVersion` 写入 t_behavior（2026-09-05 修订，P1-05 评审确认保留）**：原决策"不入 t_behavior"被 P1-05 落地推翻——`Behavior`/`t_behavior` 新增 `schemaVersion`（006_behavior_schema_version.json），作为行为级抽取 Schema 标识（兼具审计价值与 P1-07 来源区分的雏形）；修订记录见 p1-03-execution 与 p1-05-execution。若未来 P1-07 引入统一来源/版本标识，可随之收编。
2. **来源/版本标识字段**：本计划不引入，登记待 P1-07 冻结（与旧链兼容区分一并处理）。
3. **IndicatorResult/Risk 的 `analysisRunId` 落库归 P1-06**：与 Risk/IndicatorResult"当前有效结果"切换一并实施；P1-03 只做 Behavior 侧作用域与查询隔离。PLAN §8 与 P1-01 执行记录口径已同步。

## 4. 任务拆分（一轮一个小任务）

### T1：Behavior 模型扩展（common，不碰 ES）

- `Behavior.java` 增加：`assessmentId(Long)`、`analysisRunId(String)`、`sourceDocumentId(Long)`、`subject/action/object(String)`、`quantitativeUnit(String)`、`confidence(Double)`、`evidenceIds(List<String>)`、`extractionModel/extractionPromptVersion(String)`。命名 camelCase，与 D-01 决策一致。
- 保持 `status(String)`、`quantitativeData`、`behaviorDate` 等存量字段不动（兼容旧文档反序列化：ES 旧文档缺新字段 → Jackson 默认 null，反序列化不失败；需验证缺字段/新字段为 null 均可解析）。
- 单元测试：字段往返（fastjson/Jackson 序列化 → 反序列化，旧 JSON 无新字段可解析、新 JSON 全字段保留）；`@Builder` 覆盖。

### T2：ES Mapping 增量（文档 + 运行时，先于写入）

- 更新 `documents/es_mappings.json` 的 `t_behavior.properties`，新增：
  - `assessmentId: long`、`analysisRunId: keyword`、`sourceDocumentId: long`
  - `subject/action/object: keyword`（事实枚举/精确检索场景；如需全文检索在 P2 Query Builder 定）
  - `quantitativeUnit: keyword`
  - `confidence: float`
  - `evidenceIds: keyword`
  - `extractionModel/extractionPromptVersion: keyword`
- 运行环境：对已存在索引用 **PUT mapping 增量**（不加字段重建，避免触碰存量 2146 条；重建流程仅在迁移窗口使用），并同步 `init_es.py` 的权威声明。
- 完成标准：新增字段 mapping 生效（GET _mapping 实测）；存量文档不受影响。
- 注：本任务验收依赖本地 ES（docker compose）可用；无 ES 环境时以文档 + 测试索引为限，不作为通过依据（沿用"集成测试须说明前置"纪律）。

### T3：写入侧落库（LineProcessor + LineRangeItemWriter）

- `LineProcessor.process`：构造 Behavior 时填充 `assessmentId/analysisRunId/sourceDocumentId`（Batch 作用域已在方法内校验非空，直接透传）；`subject/action/object/confidence/evidenceIds` 等新字段本阶段不填（旧链无抽取），保持 null。
- `LineRangeItemWriter`：仅验证 `document(behavior)` 自动携带新字段且无序列化异常；**不改 `_id` 策略（D-22/P1-06）**、不改分类/向量化逻辑。
- 完成标准：真实写入（或测试索引写入）的文档含非空 `assessmentId/analysisRunId/sourceDocumentId`；`LineProcessorScopeTest` 扩展断言三个作用域字段随 Behavior 落库。

### T4：取数隔离（D-04，处理链核心改动）

- `BehaviorProcessingService`：
  - `fetchBehaviors(Long projectId, Long assessmentId, String analysisRunId)` / `doFetchBehaviors` 查询改 bool must：`term(projectId) + term(assessmentId) + term(analysisRunId)`；调用点 `processProjectBehaviors(:190)` 透传已有参数。
  - 保留显式 refresh + 空结果重试（写后读可见性加固，勿删）。
  - `size=10000` 硬上限超出时补 WARN（D-04 提及的静默截断，最小修复）。
- 旧行为（无 assessmentId/analysisRunId）因 term 不命中被排除——同时补一条注释说明这是期望语义，禁止按 projectId 兜底。
- 完成标准：同 project 两个 assessment、同 assessment 两个 run 数据互不混入（真实 ES/测试索引验证）。

### T5：作用域查询与集成验证（面向 P1-05/08 的读取入口）

- 将"按作用域取 Behavior"收敛为可复用入口（方法/服务，先满足内部调用；对外查询接口与 DTO 随 P1-08 前端契约，不提前建 Controller）。
- ES 集成测试：写入带不同 `assessmentId/analysisRunId` 的行为集 → 断言按 scope 查询只返回本运行数据、新字段可按 term 过滤、旧文档（无作用域）永不命中、缺字段反序列化不抛错。
- 回归：P1-01/P1-02 定向测试、`BatchJobScopeTest`、`MessageTaskScopeTest`、`LineProcessorScopeTest` 全绿；`git diff --check`。

### T6：文档与登记（收尾）

- 更新 `documents/es_mappings.json`、`defect-backlog.md`（D-03/D-04 状态与验证）、`p1-03-execution.md`；PLAN §8 P1-03 勾选按用户提交节奏处理。
- 顺手补 P1-02 登记 3：`EvidenceChunkRepositoryPersistenceTest` 类注释补"先执行 003"。

## 5. 涉及文件清单

新增（主代码，预计最少）：
- （无独立新类；若 T5 收敛查询入口则 `processing/service/BehaviorQueryService.java` 或等价，T5 定）

修改：
- `risk-warning-common/.../po/behavior/Behavior.java`（T1）
- `risk-warning-processing/.../batch/LineProcessor.java`（T3）
- `risk-warning-processing/.../service/BehaviorProcessingService.java`（T4：fetchBehaviors 签名与查询、调用点、size 告警）
- `risk-warning-processing/.../batch/LineRangeItemWriter.java`（T3：仅确认，尽量零改动）

文档/测试/迁移：
- `documents/es_mappings.json`（t_behavior 增量字段）
- `documents/plan1/p1-03-execution.md`（执行记录）
- `risk-warning-processing/src/test/.../service/`（BehaviorScopeIsolationTest 等，T5）
- `risk-warning-common/src/test/.../po/behavior/`（BehaviorSerdeTest 等，T1）
- `documents/plan0/baseline/defect-backlog.md`（D-03/D-04 更新）

## 6. ES 变更与数据安全约束

- t_behavior 采用 **PUT mapping 增量**，不清索引、不重建、不动存量 2146 条（AGENTS：删除/重建须确认与备份；本计划不做）。
- 新字段全部可空/缺省：旧文档缺字段反序列化为 null（先写一个针对旧文档形状的回归测试锁定）。
- 作用域隔离只向前生效：旧无作用域记录永不进入新评估（期望语义，非数据丢失）。
- `es_mappings.json` 保持权威一致性：任何运行时 mapping 变更必须同步该文件。

## 7. 测试计划

- 单元：Behavior 序列化兼容（旧 JSON 缺新字段、新 JSON 全字段）；LineProcessor 构造断言三个作用域字段非空。
- ES 集成（前置：本地 ES；或专用测试索引）：mapping 新字段实测（GET _mapping 含新字段）；写入文档含非空作用域；两 assessment / 两 run 隔离查询；旧文档不命中。
- 回归：P1-01/P1-02 全部定向测试；Batch 终态门禁相关测试。

## 8. 完成标准与不做清单

完成标准：
1. 新写入 Behavior 三个作用域字段非空且真实落库（对应 Entry Gate 第 2 条前半）。
2. fetchBehaviors 按 `assessmentId + analysisRunId` 过滤并校验所属项目（对应 Entry Gate 第 3 条 Behavior 侧）。
3. evidenceIds/confidence/抽取元数据字段随模型与 Mapping 就位（值由 P1-04/05 填充）。
4. 旧链与存量数据零迁移、零回填、零删除；P0 基线链无回归。

不做：
- 不实现写入幂等/`_id` 策略与同 Run 重试去重（P1-06）。
- 不做"当前有效结果"指针与失败保留切换（P1-06）。
- 不实现 status 枚举收紧与 5.1/5.3 抽取校验（P1-05 新链产物）。
- 不引入来源/版本标识（P1-07）；不给 IndicatorResult/Risk 落 analysisRunId（3.3 决策 3，归 P1-06）。
- 不建对外查询 Controller/DTO（P1-08 前端契约）。
- 不迁移/删除存量 t_behavior。

## 9. 风险与未决

1. ~~3.3 决策 3（IndicatorResult/Risk 的 analysisRunId 归属）~~ **已决策：归 P1-06**，PLAN §8 与 P1-01 执行记录口径已同步，不再列为未决。
2. 新字段类型（subject/action/object 用 keyword 还是 text）影响 P2 Query Builder 检索；本计划以 keyword 起步，P2 评审时如需全文可加 text 子字段（增量，不破坏）。
3. LineRangeItemWriter 批量写入未设 `_id`（D-22）：P1-03 落库的行为在"同 run 重试"下仍会重复，P1-06 幂等落地前不得宣称"同 Run 重试无重复行为"。
4. 旧链继续写入无抽取字段的行为：来源区分（P1-07）落地前，t_behavior 会同时存在"带作用域无抽取字段"（旧链产物）与后续"带作用域带抽取字段"（新链产物）文档，靠字段空值区分，不做删除。
5. fetchBehaviors 隔离后指标阶段如遇"本 run 无行为"会走既有空结果重试 → FAILED 门禁，语义不变。
