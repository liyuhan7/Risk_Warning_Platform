# P0-11 接口评审记录

> 评审日期：2026-08-27
> 依据：PLAN.md `P0-11`「召开接口评审，记录已冻结项、未决项、负责人和最晚决策日期」
> 输入：`P0-01` 至 `P0-09` 全部产出文档，`defect-backlog.md` 剩余 9 条待决策，`P0-05` 第 12.2 节 F 系列

本次评审对 15 项议题作出决策，关闭 `defect-backlog.md` 中全部 9 条待决策，并解决 `p0-03-isolation.md`、`p0-05-core-schemas.md` 的拟冻结状态。本文件为上述文档的最终冻结依据。

本记录只记载决策，不含实施。所有破坏性变更须按第五节的窗口顺序单独执行，执行前须完成 5.1 的备份。

## 一、已冻结项：ES 字段契约

### 1.1 命名策略（D-01）

**决策：全系统统一 camelCase，一次性切换。**

现状为四索引两套命名：`t_indicator`（1144 条）camelCase，`t_behavior` / `t_regulation` / `t_risk` snake_case。`ElasticSearchConfig.java:61` 的 `PropertyNamingStrategy.SNAKE_CASE` 恰好只对齐后三者，与 `t_indicator` 完全错位，且 Jackson 对找不到的字段静默赋 null，错位不报错。

一次性切换指配置项移除与四索引存量迁移在同一窗口内完成，不设并存过渡期。该方式周期短但无中途回退点，因此 5.1 的快照是本决策的生效前提——未完成快照不得开始迁移。

被否方案：分两步走（并存 → 回填 → 移除）虽每步可回退，但过渡期内读取需维护回落逻辑，且 `D-03` 加字段、`F-05` 回填都要等到过渡期结束，会把计划 1 的开工时间推后一个完整迁移周期。

### 1.2 孤儿向量字段（D-26）

**决策：保留 `vector` 字段，仅在文档标注废弃。**

三索引均同时存在 `vector`（0 条有值）与 `*_vector`（100% 有值），而 `es_mappings.json` 记录的恰是无人写入的 `vector`。

Elasticsearch 不支持从 mapping 中删除已声明字段，「删除」实际须 reindex 整个索引。该字段无数据、不占存储、不影响检索，标注废弃即可消除误导，reindex 8611 条文档的代价与收益不成比例。

计划 2 新增检索向量字段时，须避免再造第三套命名，字段名以 1.3 冻结的文件为唯一依据。

### 1.3 mapping 文件与初始化脚本（F-10）

**决策：从运行时导出真实 mapping、改写为 camelCase，作为新建索引的唯一依据；`init_es.py` 按此文件重建。**

`es_mappings.json` 现声明 `{"type":"dense_vector","dims":768}`，缺 `index: true` 与 `similarity`。ES 8.x 中 `dense_vector` 默认 `index: false`，按该文件重建索引会使 kNN 检索直接不可用。运行时权威值为 `{"type":"dense_vector","dims":768,"index":true,"similarity":"cosine"}`。

`init_es.py` 为本项目后续补充的脚本，可自由修改。因此该脚本与 mapping 文件在本次迁移中承担双重角色：既是 camelCase 契约的权威声明，也是迁移的执行工具。改写后须满足两项：一是按该文件重建出的索引与迁移后运行时状态完全一致，二是脚本的删除重建流程须加显式确认门，防止误运行清空存量。

冻结的向量字段名、维度与检索参数见 `p0-09-field-contract.md` 第 5 节，本次不改动。

### 1.4 `createAt` 拼写（F-09）

**决策：修正为 `createdAt`，迁移 2596 条 `t_risk` 存量。**

`Risk.java:54` 字段名少一个 `d`，导致 ES 中实际写入 `create_at`，而文档声明的 `created_at` 恒空。计划 4 的风险详情页需要该时间字段，2596 条是迁移成本最低的时点。

### 1.5 Milvus 存量（F-12）

**决策：迁移必须同步 Milvus collection，不得只改 ES。**

本次实测结论（`P0-09` 未实测，此前列为未知项）：

| 项 | 实测结果 |
| --- | --- |
| collection | `indicator_vectors`、`regulation_vectors` 均存在 |
| `regulation_vectors` 数据 | 有数据，`industry` 字段含真实值（如「征信业,金融业,综合类」） |
| `indicator_vectors` 状态 | collection 未 load，无法查询内容，schema 存在 |
| 字段命名 | Milvus 侧为 snake_case（`es_id`），与 ES 侧独立 |

`VectorSearchService.java:128` 以 `industry == "%s"` 精确匹配过滤。因此 `industry` → `complianceDomain` 改名若只改 ES 不改 Milvus，检索过滤会静默失效——与 D-01 中 Jackson 静默赋 null 属同类失败模式。

Milvus 字段命名不在 D-01 的 camelCase 范围内（D-01 只约束 ES 与 Java DTO 的映射）。Milvus 侧保持 snake_case，但 `industry` → `compliance_domain` 的语义改名须与 ES 同窗口完成。

`indicator_vectors` 未 load 意味着其存量条数未经确认，迁移前须先 load 并核对条数。

## 二、已冻结项：风险等级口径

### 2.1 判定口径统一（D-11、D-10）

**决策：统一为比例语义 `score / maxScore`。**

原有三处判定源互不一致：

| 判定源 | 口径 | 阈值 |
| --- | --- | --- |
| `RiskLevelEnum.java:56-64` | 比例 | 0.4 / 0.2 三档 |
| 821 条 `riskRule.staticThreshold` | 绝对分数 | 0.5 单档 |
| `AssessmentServiceImpl`（Report 侧） | 比例 | 0.5 单档 |

未暴露的原因是 `riskRule` 从未被执行、另两处作用于不同层级。`P3-06` 让规则引擎执行 `riskRule` 后，同一指标会得出两个等级。

统一为比例语义同时关闭 D-10：绝对阈值 0.5 与 `maxScore`（区间 0.3~2.0）量纲不可比，`maxScore < 0.5` 的 89 条指标得满分也低于阈值、恒定触发风险，其中企业国际合作风险维度 184 条指标满分全部不超过 0.5。改用比例后该缺陷自然消除。

代价：821 条 `riskRule` 的 `thresholdValue` 须按 2.3 的阈值表重新标定。

### 2.2 RiskRule 结构（D-15、D-16）

**决策：改为有序 `thresholds` 列表，支持高/中/低三档。**

`StaticThreshold.java:12-15` 的 `riskLevel` 是单值，`RiskRule.staticThreshold`（`RiskRule.java:13`）又是单对象，现结构最多表达「一个阈值一个等级」，无法承载三档。

结构见 `p0-04-riskrule-design.md` 4.2 节。要点：`thresholdMode` 显式区分 `SCORE_RATIO` 与 `ABSOLUTE_SCORE`，缺失时按 `ABSOLUTE_SCORE` 走旧逻辑；`thresholds` 按 `thresholdValue` 升序求值，命中即停，全不命中为无风险；`ruleVersion` 满足 PLAN.md:45 的规则版本可复算要求。新字段追加，不改旧字段语义，旧数据仍可读。

### 2.3 六维度阈值表

**决策：认可 `p0-04-riskrule-design.md:69-76` 的六维度阈值表为初版，标注为待实测校正。**

该表的分档理由是法理推断，不是实测标定。认可它是为了让规则引擎能够开工，后续用三案例复算结果反向校正。

**本项是本次评审中唯一未经数据验证的决策。** 阈值数值的合理性须在计划 3 用真实得分分布复核，届时如需调整属正常校正，不视为返工。`P3-06` 的验收应包含两条可验证项：同一 `scoreRatio` 在不同 `dimension` 下得到不同风险等级；`maxScore < 0.5` 的 89 条指标得满分时不再触发风险。

### 2.4 枚举持久化与 NO_RISK（F-07）

**决策：`overallRiskLevel` 改 `@Enumerated(EnumType.STRING)`，同时新增 `NO_RISK` 档位。**

两项绑定处理。`Assessment.java:36` 无 `@Enumerated` 注解，Hibernate 按枚举序号持久化（`schema.sql:134` 为 `overall_risk_level INT`），调整枚举声明顺序会静默改变存量数据含义。必须先改 STRING，插入 `NO_RISK` 才安全。

新增 `NO_RISK` 解决 `P0-08` 遗留的语义压缩：`getByRiskCount(0,0,0)` 现返回 `LOW_RISK`，「零指标触发风险」与「确有低风险指标」在返回值上不可区分，前端无法区分两种状态。

实施须包含 `overall_risk_level` 列由 INT 改为 VARCHAR 的数据迁移。`RiskLevelEnumTest` 中断言 `getByRiskCount(0,0,0)` 返回 `LOW_RISK` 的用例须同步改为 `NO_RISK`。

### 2.5 文档阈值漂移（F-08）

**决策：随口径统一一并更正。**

`中期汇报_系统实现资料稿.md:942-945` 描述为 0.8/0.5/0.3 四档含「无风险」，`:953` 提到 `RiskLevelEnum` 含 `CRITICAL`；代码实际为 0.4/0.2 两档三值。新增 `NO_RISK` 后档位数将变化，更正须在 2.1、2.4 落地后进行，以免二次修改。

## 三、已冻结项：数据粒度与字段改名

### 3.1 `t_project_file` 粒度（F-04）

**决策：现在改为一文件一行，与 1.1 的命名迁移同一窗口完成。**

`P0-05` 的 U-01 已决议 `sourceDocumentId` = 改造后的 `ProjectFile.id`（`Long`），拆除 `filePaths` 字段与 `StringListJsonConverter`，批次概念另设 `uploadBatchId` 而不复用 `id`。

存量迁移须把每行的 `filePaths` 数组展开为多行。`EvidenceChunk` 与 `StructuredBehavior` 均以 `sourceDocumentId` 为必填锚点，一行多文件时它无法唯一定位，因此该改造是计划 1 的前置。

### 3.2 `complianceDomain` 回填（F-05）

**决策：随 1.1 一次性切换同时完成 6009 条回填。**

命名已定 camelCase，不再有分歧风险，可一次写入新字段而无需并存回落期。

实测：`complianceDomain` 当前不存在，`industry` 在 `t_indicator`（1144）与 `t_regulation`（4865）均 100% 覆盖，共 6009 条。

该字段承载的语义混杂问题不由本次改名解决：53 个取值中「银行业」「证券业」是行业，而覆盖全部 1144 条的「综合类」是适用范围标记。取值域的清理属计划 2 范围。

按 1.5，Milvus 侧须同步改名，否则检索过滤静默失效。

## 四、已冻结项：其他

### 4.1 追认拟冻结文档

**决策：全部追认，标为已冻结。**

| 文档 | 原状态 | 追认范围 |
| --- | --- | --- |
| `p0-05-core-schemas.md` | 待评审 | 全文，含 11 项 U 系列决议 |
| `p0-03-isolation.md` | 大量条款拟冻结 | §1、§1.1、§1.2、ISO-01 至 ISO-04、ISO-06、ISO-07、§4 条款 1/3/4 及新增条款 7/8、§5 |
| Evidence 作用域修正 | 待确认 | `p0-05-core-schemas.md:228` |

Evidence 作用域修正的内容：**Risk / IndicatorResult / Behavior 三者共享同一 `analysisRunId` 与 `assessmentId`；Evidence 只保证 `sourceDocumentId` 可回指到同一 `assessmentId` 的上传记录。** 依据是 U-11 决议——`EvidenceChunk` 为文档级资产，运行归属由 `StructuredBehavior` 承担，其 `id` 哈希不含 `analysisRunId`。该修正已回写 `p0-03-isolation.md`（F-01），本次追认后生效。

### 4.2 运行状态字段位置（F-02）

**决策：新建运行记录表，以 `analysisRunId` 为主键。**

U-07 已决议允许同一 assessment 重复分析，assessment 与 run 是一对多，扩展 `t_assessment_result` 只能记录最新一次的状态。并发保护（ISO-07）也需要独立的运行记录承载——加入 `analysisRunId` 后每次运行的 `jobParameters` 天然不同，Spring Batch 自身不再阻止重复提交。

表须至少包含 `analysisRunId`（主键）、`assessmentId`、`projectId`、运行状态（`RUNNING` / `SUCCEEDED` / `FAILED`）、开始与结束时间。

### 4.3 上传幂等（F-03）

**决策：按文件内容哈希去重。**

覆盖「同一物理文件二次上传」场景，同一文件二次上传不重复建档。代价是上传时需读全文算哈希，大文件有耗时。

### 4.4 `BatchTest` 定位（F-06）

**决策：删除该测试。**

`BatchTest.java:39-40` 含 macOS 硬编码路径 `/Users/huayecai/Desktop/bach_01/...`，仓库主开发环境为 Windows，该测试在本机必然失败。它是 `@SpringBootTest(classes = ProcessingApplication.class)`，除路径外还需 PostgreSQL、Kafka、ES 与 Spring Batch 元数据表齐备，仅替换路径不能使其通过。`:42` 调用 `runBatchJob` 时也只传 `projectId` 与 `filePaths`，未传 `assessmentId`（与 D-05 同源），删除后 D-24 一并关闭。

**该决策留下一个测试空洞：** `BatchTest` 是目前唯一覆盖 Spring Batch 链路的测试，删除后 `P1-07`（保留旧 Batch 兼容入口）没有回归依据。已登记为 F-13。

### 4.5 gateway 启动风险

**决策：收窄 gateway 扫描范围，不再扫 `LLMUtil`。**

`GatewayApplication.java:10` 的 `scanBasePackages` 含 `com.riskwarning.common.utils`（内含 `LLMUtil`）但不含 `com.riskwarning.common.provider`。而 `p0-07-provider-boundary.md:81` 指导设置 `$env:LLM_ENABLED="true"`——该变量一旦在 shell 或容器层全局可见，gateway 会尝试注册 `LLMUtil` 却找不到 `AiChatProvider` Bean，启动即失败。

gateway 本不需要 `LLMUtil`，扫到它属历史遗留。收窄扫描范围是根治方案，改完后全局设置该变量也不影响 gateway。

## 五、实施窗口与顺序

### 5.1 迁移前置条件（强制）

**用 ES snapshot API 对 `t_indicator`、`t_behavior`、`t_regulation`、`t_risk` 做全量快照。** 未完成快照不得开始任何迁移动作。

须先确认 ES 已配置 snapshot repository；未配置则先配置。快照覆盖 8611 条文档与全部向量，失败时可整体回滚。

Milvus 侧无快照方案，`indicator_vectors` 须先 load 并记录条数，`regulation_vectors` 须记录条数，作为迁移后核对基准。

### 5.2 窗口内执行顺序

一次性切换把四项变更并入同一窗口，其中 `t_project_file` 在 PostgreSQL、其余在 ES 与 Milvus。**一次回滚需同时还原三个存储**，因此顺序须使失败时的还原成本最低：

```
1. PostgreSQL：t_project_file 展开为一文件一行（3.1）
   └─ 向后兼容：多出的行不影响旧读取路径，失败无需还原
2. PostgreSQL：overall_risk_level 由 INT 改 VARCHAR（2.4）
3. ES：按 1.3 改写后的 es_mappings.json 新建索引
4. ES：存量迁移 —— camelCase 改名（1.1）+ createdAt 修正（1.4）
        + complianceDomain 回填（3.2），同批完成
5. Milvus：industry → compliance_domain 同步改名（1.5）
6. 移除 ElasticSearchConfig.java:61 的 SNAKE_CASE（1.1）
7. 核对：四索引字段覆盖数不下降，Milvus 条数与 5.1 基准一致
```

第 1、2 步的 PostgreSQL 改动向后兼容，失败时无需还原；第 3 步及之后失败则从 5.1 的 ES 快照恢复。第 6 步必须在第 4 步核对通过后执行——提前移除会立刻打断 `t_behavior` / `t_regulation` / `t_risk` 的读写。

### 5.3 解锁关系

```
第一节（ES 字段契约）─┬─→ D-03 Behavior 加作用域字段 ─→ 计划 1 开工
                      └─→ 3.2 complianceDomain 回填
第二节（风险等级口径）───→ 计划 3 规则引擎（P3-04 至 P3-06）
第三节（数据粒度）───────→ P1-01 / P1-02
第四节（追认冻结）───────→ 计划 1 的契约依据
```

## 六、缺陷状态变更

本次评审关闭 `defect-backlog.md` 全部 9 条待决策：

| 缺陷 | 变更后状态 | 依据 |
| --- | --- | --- |
| D-01 | 已定方案 | 1.1 |
| D-03 | 已定方案（命名随 D-01） | 1.1 |
| D-26 | 已定方案 | 1.2 |
| D-10 | 已定方案 | 2.1 |
| D-11 | 已定方案 | 2.1 |
| D-15 | 已定方案 | 2.2 |
| D-16 | 已定方案 | 2.2 |
| D-23 | 已定方案 | 3.1 |
| D-30 | 已定方案（U-10 已决议，替换顺序「写新→校验→删旧」） | — |

D-24 随 4.4 关闭。D-13（RANGE 规则依赖 JavaScript 表达式）**不在本次范围**，仍为 `P3-05` 待决策——110 条 RANGE 规则去重后有 98 种不同表达式，处置方式（求值 / 转结构化阈值 / 只支持子集）须在规则引擎实现时结合真实表达式分布决定。

F 系列：F-01 已完成，F-02 至 F-10、F-12 本次决策完毕，F-11（汇报稿向量字段记载矛盾）为纯文档修订，随 2.5 一并更正。

## 七、遗留事项

| 编号 | 事项 | 归属 | 最晚期限 |
| --- | --- | --- | --- |
| F-13 | 删除 `BatchTest` 后补 Batch 链路回归测试。建议改为不依赖基础设施的 `LineProcessor` / `LineRangeItemWriter` 单元测试 | `P1-07` | 计划 1 结束前 |
| — | 六维度阈值表实测校正（2.3 唯一未经数据验证项） | `P3-06` | 计划 3 结束前 |
| — | `industry` 取值域清理（53 个取值混杂行业与适用范围） | 计划 2 | 定义取值域时 |
| D-13 | RANGE 规则 JavaScript 表达式处置 | `P3-05` | 计划 3 启动前 |
| — | `indicator_vectors` collection 未 load，存量条数未确认 | 迁移执行前 | 5.1 |
| — | `P0-10` 已跳过，Evidence / AnalysisResult 前端类型须补做 | 计划 4 前 | 前端联调前 |
| D-21 | 旧 LLM Key 的供应商侧吊销与 git 历史清除 | 用户执行 | 越早越好 |
| — | `P0-06` 连续 10 次真实 LLM 调用 | 用户提供新凭据后 | 计划 3 启动前 |

D-21 须特别说明：工作区已无明文凭据（全仓 grep 0 命中），但该 Key 仍存在于全部 21 个历史提交的 `LLMUtil.java:23`。**吊销完成前应视为已泄露。**

## 八、验收对照

对照 PLAN.md `P0-11`「记录已冻结项、未决项、负责人和最晚决策日期」：

| 要求 | 位置 |
| --- | --- |
| 已冻结项 | 第一至四节，共 15 项决策 |
| 未决项 | 第七节遗留事项，含归属与最晚期限 |
| 负责人 | 第七节「归属」列按任务编号标注，责任边界见 PLAN.md 0.2 |
| 最晚决策日期 | 第七节「最晚期限」列 |

本记录的回写已执行：`p0-03-isolation.md`（6.3 改为已冻结）、`p0-05-core-schemas.md`（文档状态改为已冻结，12.2 节 F-02 至 F-05 更新为已决策）、`defect-backlog.md`（9 条待决策及 D-24 按第六节更新，统计表待决策降为 1 条）、`p0-09-field-contract.md`（6.2 并存策略废止、6.3 Milvus 实测结论、第九节遗留事项）、`p0-07-provider-boundary.md`（第七节补 gateway 环境变量警示）、`p0-08-deterministic-bugfix.md`（D-01 行）、`PLAN.md`（`P0-11` 勾选）。
