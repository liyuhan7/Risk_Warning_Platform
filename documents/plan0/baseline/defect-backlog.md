# 缺陷待做清单

> 建立日期：2026-08-27
> 仓库基线：`main`，提交 `a851e09`
> 覆盖范围：计划 0 至迁移窗口业务回归发现的缺陷；状态同步日期：2026-09-06
> 历史现象、行号和实测数保留作溯源；当前进度以各项状态及第七节为准。优先级 P0/P1/P2 与开发计划阶段编号是两套概念。
> 维护约定：后续阶段发现新缺陷时追加条目，编号只增不复用；状态变更须同步修改「状态」列与验证方式

## 状态图例

| 标记 | 含义 |
| --- | --- |
| 未开始 | 已确认存在，尚未动手 |
| 部分完成 | 已修一部分，仍有残留 |
| 已修复 | 已改并有测试或实测证据 |
| 已定方案 | 决策完成，实施或验证尚未闭环 |
| 待决策 | 需团队决策后才能动工 |

## 优先级说明

`P0` 为阻断项，不修则后续工作无法进行或产出不可信。`P1` 影响正确性但有绕行方案。`P2` 为质量与工程债。

---

## 一、阻断项

### D-01 ES 命名策略与索引字段不匹配

- 优先级：**P0**，全部规则相关工作的前置条件
- 位置：`risk-warning-common/.../config/ElasticSearchConfig.java:61`
- 现象：设置 `PropertyNamingStrategy.SNAKE_CASE`，按 `indicator_level` 反查，而 `t_indicator` 文档为 camelCase。
- 实证矛盾：同一索引同一语义字段，两种命名的命中数完全相反。

  | 查询字段 | 命中文档数 |
  | --- | --- |
  | `indicator_level`（配置所用） | **0** |
  | `indicatorLevel`（实际存储） | **1144** |
  | `riskRule` | 821 |
  | `calculationRule` | 821 |

  配置层与存储层的命名约定相互矛盾，且矛盾不报错——Jackson 对找不到的字段静默赋 null（`ElasticSearchConfig.java:62` 另设 `FAIL_ON_UNKNOWN_PROPERTIES=false`，进一步压制了本可暴露问题的异常）。
- 后果：`indicatorLevel`、`maxScore`、`calculationRule`、`riskRule` 全部反序列化为 null。821 条规则全部读不出来，且无任何错误日志。
- 关联：`p0-02-baseline.md` 根因 1；与 D-02 叠加导致三案例命中金标准指标 0/8、0/8、0/6
- 根因修正（P0-09 实测）：命名分裂的源头是初始数据文件本身，而非配置项。`documents/data/indicator.json` 为 camelCase，`regulation.json` 与 `behavior.json` 为 snake_case，`test/init_es.py` 原样导入不做改名。`ElasticSearchConfig.java:61` 是放大器：它使 `t_behavior` / `t_regulation` / `t_risk` 的读写恰好对齐 snake_case 存量数据，却与 `t_indicator` 的 camelCase 存量完全错位。因此 `p0-05-core-schemas.md:27` 所记「必须移除 SNAKE_CASE」的结论需修正——单独移除会立刻破坏另外三个索引，须与数据迁移同批实施。
- 状态：**已修复 / 已验证**。2026-09-04 迁移窗口四索引 camelCase 与配置同步完成；用户于 2026-09-05 确认真实业务链通过。
- 验证：迁移窗口字段覆盖核对通过；用户确认上传至报告链正常。本次文档对齐未重跑在线验证。

### D-02 `behavior.status` 恒为空串

- 优先级：**P0**，评分链唯一的合规性输入
- 位置：产出侧 `LineRangeItemWriter`（分类服务不返回 `status`）；消费侧 `QualitativeCalculator.java:117-129`
- 现象：`status` 恒为空串，`normalizeStatus("")` 在 `:129` 返回 `"UNKNOWN"`，三个分支函数一律走 `default: return 0.5`（`:52`、`:56`、`:75`、`:92`）。`quantitativeData` 同样恒为 0.0。
- 实证矛盾：生产侧与消费侧对 `status` 的假设相反。`LineRangeItemWriter` 的既有注释明确说明分类服务不返回 `status`，而 `QualitativeCalculator` 把 `status` 当作必有输入来分支。三案例实测 `t_behavior` 全部 9 条 `status=''`、`quantitative_data=0.0`，即消费侧的 6 个有效分支从未被执行过，`return 0.5` 是唯一实际路径。
- 后果：定性分恒为 0.5。这意味着「评分」实际不含任何合规性判断——分数差异只可能来自向量相似度噪声，与 `p0-02-baseline.md` 实测「违规 32.34 分反而低于合规 38.77 分」互为印证。
- 关联：`p0-02-baseline.md` 根因 2；D-14 的 `condition` 无输入亦源于此
- 状态：**基础实现完成 / 真实链待验证**。P1-05 已由事实抽取 Schema 生成 `status`、数量与单位字段，UNKNOWN 表达无法判断的状态；生产链已切换到新抽取服务。真实 Provider、专用 PG/ES/BERT 切片和评分消费效果尚未验证。
- 归属：P1-04 / P1-05 事实抽取；不继续修旧分类器。
- 验证：status 必须受原文支持；未知用 Schema 冻结的 UNKNOWN/null/insufficientEvidence 表达，不填空串或默认 COMPLIANT；缺失值不得伪装为正常评分输入。

### D-03 `Behavior` 缺作用域字段

- 优先级：**P0**，数据隔离的存储层前置
- 位置：`risk-warning-common/.../po/behavior/Behavior.java:16-43`；`documents/es_mappings.json:2-24`
- 现象：只有 `projectId`，无 `assessmentId`、`sourceDocumentId`、`analysisRunId`。
- 实证矛盾：同一条数据链上的上下游对象作用域粒度不对称——下游 `IndicatorResult.java:47` 与 `Risk.java:24` 都有 `assessmentId` 并据此隔离（`ReportServiceImpl.java:282` 按 `assessment_id` 过滤），唯独作为它们输入源的 `Behavior` 没有。结果是「结果按评估隔离，输入按项目混取」，下游的隔离建立在上游未隔离的数据之上，隔离性是假的。
- 后果：存储层无字段，查询层无论怎么改都无法按本次评估隔离。
- 关联：`p0-03-isolation.md` ISO-02；依赖 D-01 的命名决策，否则会再增一处命名不一致
- 状态：**已修复 / 专用 ES 已验证**。P1-03 已为 `Behavior` 增加 `assessmentId`、`analysisRunId`、`sourceDocumentId`，并在 Batch 写入侧透传；`documents/es_mappings.json` 同步为 camelCase 增量字段。Elasticsearch 8.11 专用测试索引已确认 Mapping 与实际写入字段。
- 验证：代码侧序列化兼容、Mapping JSON、LineProcessor 作用域测试和专用 ES 写入均通过；业务 `t_behavior` 部署前仍须执行仅新增字段的 PUT mapping。

---

## 二、正确性缺陷

### D-26 文档 Mapping 与运行时向量字段不一致，且存在孤儿字段

- 优先级：**P0**（属 `P0-09` 冻结范围）
- 位置：`documents/es_mappings.json:17`、`:130`、`:159`；Java 侧 `Indicator.java:35-36`、`Behavior.java:37-38`
- 实证矛盾：文档 mapping 三处向量字段均命名 `vector`，Java 侧却用 `name_vector` / `description_vector`。查运行时 mapping，**两套字段同时存在**且均为 768 维：

  | 索引 | 带前缀字段 | 有值文档 | `vector` 字段 | 有值文档 |
  | --- | --- | --- | --- | --- |
  | `t_behavior` | `description_vector` | 2146 / 2146 | 存在 | **0** |
  | `t_indicator` | `name_vector` | 1144 / 1144 | 存在 | **0** |
  | `t_regulation` | `full_text_vector` | 4865 / 4865 | 存在 | **0** |

- 后果：`vector` 是无人写入的孤儿字段，而文档 mapping 记录的恰好是这个字段。以文档为准做迁移会写错目标。PLAN.md:77 预警的「文档 Mapping 与 Java 向量字段命名不一致」由此确证。
- P0-09 补充实测：问题比命名不一致更严重。文档声明为 `{"type":"dense_vector","dims":768}`，缺少 `index: true` 与 `similarity`；ES 8.x 中 `dense_vector` 默认 `index: false`，按该文件重建索引会使 kNN 检索直接不可用。运行时权威值为 `{"type":"dense_vector","dims":768,"index":true,"similarity":"cosine"}`。此外承载全部数据的 `*_vector` 字段在任何版本控制的可执行产物中都未声明，仅见于运行时与 `系统设计.md`，即运行时状态无法从仓库重建（另立 F-10）。
- 关联：`P0-09` 已冻结字段名、维度与检索参数（见 `p0-09-field-contract.md` 第 5 节）；计划 2 新增检索向量字段时须避免再造第三套命名
- 状态：**部分完成**。当前 mapping 已声明 descriptionVector/nameVector/fullTextVector 及检索参数，迁移窗口核对通过；孤儿字段的废弃说明与初始化复建验收仍待专项核对。
- 验证：文档 mapping 与运行时一致，且每个向量字段都有明确写入方

### D-27 `t_behavior` 实际文档数与 Baseline 记录不符

- 优先级：P2
- 位置：ES `t_behavior`
- 实证矛盾：`p0-02-baseline.md:16` 记录三索引文档数为 2121 / 1144 / 4865，本次实测 `t_behavior` 为 **2146**（+25），`t_indicator`、`t_regulation` 未变。
- 后果：说明 Baseline 采集后又有行为写入（可能来自后续调试上传）。`t_behavior` 无作用域字段（D-03），无法判断这 25 条属于哪次评估，也无法安全清理。
- P0-09 补充实测：累积规模已可量化。源数据 `documents/data/behavior.json` 仅 600 条，ES 中 2146 条，差值 1546 恰等于 `id` 字段的覆盖数——导入的 600 条无 `id` 字段（ID 仅存于 `_id` 元数据），带 `id` 的 1546 条均由 Java 写入。`t_risk` 同理，`assessment_id` 聚合显示前十桶为 8—17、每桶 180—373 条。根因是 `LineRangeItemWriter.java:154` 的批量写入不设 `_id`，每次运行全部新建文档（D-22）。
- 关联：D-03、D-22；印证「无幂等 + 无作用域」使数据无法追溯
- 状态：未开始
- 验证：D-03 落地后可按 `assessmentId` 定位并清理非固定案例数据

### D-28 `RiskDimensionEnum` 取值与 ES 数据不一致，靠硬编码特例兜底

- 优先级：P1
- 位置：`RiskDimensionEnum.java:15`、`:44-47`
- 实证矛盾：枚举定义维度名为 `国际化经营风险`，而 `t_indicator` 实际存量取值是 `企业国际合作风险`（P0-04 实测该维度 184 条）。`fromValue` 在 `:44-47` 加了一段特例判断：先检查自身 description 是否等于 `国际化经营风险`，再把 `企业国际合作风险` 映射过去。**这段代码的存在本身就是矛盾的证据——它在运行时校验一个编译期常量，只为兜住枚举与数据不一致。**
- 后果：维度名有两个事实来源。任何按维度分组、过滤或聚合的新代码若直接用 `getDescription()` 与 ES 值比对，都会漏掉整个维度的 184 条指标；且 `fromValue` 对未知维度抛 `IllegalArgumentException`，一旦再出现第三种写法即运行时异常。
- 关联：P0-05 未决项 U-06；与 D-01 同属「同一概念两套命名」
- 状态：**部分完成**。当前枚举已采用企业国际合作风险并移除旧特例；全量维度取值回归待补证据。
- 验证：统一权威取值后，遍历 `t_indicator` 全部 dimension 值均能通过 `fromValue` 且不触发特例分支

### D-29 `IndustryEnum` 名为行业实为合规领域

- 优先级：P2
- 位置：`IndustryEnum.java:7-14`
- 实证矛盾：枚举成员是 `供应链管理`、`市场营销与广告`、`人力资源与劳动关系`、`跨境交易与支付`、`数据隐私与网络安全`、`反垄断与不正当竞争`、`知识产权`、`财务与税务`——这是**合规领域**分类，不是行业分类（制造业、金融业之类）。但字段名为 `industry`，PLAN.md:430 又要求检索过滤支持「行业」维度。
- 后果：Query Builder 按 `industry` 过滤时，语义究竟是「企业所处行业」还是「合规议题领域」不明确。若 Regulation 与 Project 两侧对该字段理解不同，过滤会静默筛掉正确候选——这类错误不会报错，只会表现为召回率低，与 D-14 的现象难以区分。
- 关联：P0-05 未决项 U-09；影响 `P2-04` Metadata Filter
- 状态：**部分完成**。Java/ES 与 Milvus 字段语义改名已实施；取值域混杂清理归 P2，完整跨端验收待补证据。
- 验证：明确字段语义后，检查 Project 与 Regulation 两侧取值是否落在同一枚举域

### D-04 `fetchBehaviors` 按项目全量取数

- 优先级：P0
- 位置：`BehaviorProcessingService.java:187`、`:800`、`:840-848`
- 现象：`term(project_id)` + `size=10000`，返回该 project 全部历史行为。
- 实证矛盾：`processProjectBehaviors` 在 `:174` 已接收 `assessmentId`，并在 `:183` 用它清理旧结果、在 `:424` 用它写入结果，却在 `:187` 取数时改用 `projectId`。**同一方法内同一个变量，写入时用它、读取时弃它。** 另 `size=10000` 是硬上限，超出即静默截断，无告警。
- 后果：同一项目的历史上传材料混入本次评估，违反 PLAN.md:42 的质量门槛。
- 关联：`p0-03-isolation.md` ISO-04；依赖 D-03
- 状态：**已修复 / 专用 ES 已验证**。P1-03 已将行为取数改为 `projectId + assessmentId + analysisRunId` 三个 term 的 bool 查询，旧文档缺字段不会命中，且命中超过 10,000 条会记录截断告警。Elasticsearch 8.11 专用测试索引已验证 Assessment/Run 隔离。
- 验证：查询构造单元测试已锁定三项 term 与禁止项目级回退；专用测试索引中同 projectId 的两个 assessmentId、同 assessmentId 的两个 run 与无作用域历史文档均不混入。

### D-05 Batch 链路丢弃 `assessmentId`

- 优先级：P0
- 位置：`MessageTask.java:73-77`、`:82-85`；`BatchJob.java:41`、`:47-48`；`LineProcessor.java:20-21`
- 现象：`assessmentId` 在消息中存在，但 `processDocument` 与 `runBatchJob` 均不接收，`jobParameters` 只有 `projectId` 与 `filePaths`，`LineProcessor` 无从注入。下游靠 `MessageTask.java:97-104` 从原消息重取转发绕过。
- 实证矛盾：`MessageTask.java:90-93` 对 `assessmentId` 做了 null 校验并以 `[CRITICAL]` 级别记录、抛异常中断——说明代码作者清楚该字段不可缺失。但这个校验位于**步骤 3**，而步骤 1、2（`:73-85`）早已在不传该字段的情况下把行为写完了。校验的严格程度与实际使用的缺失程度自相矛盾。
- 后果：行为生产环节不知道本次评估身份，是 D-04 只能按 projectId 查的根本原因。
- 关联：`p0-03-isolation.md` ISO-03。须同时透传 `analysisRunId`。Spring Batch 以 `jobParameters` 判定作业实例唯一性——加入 `analysisRunId` 后每次运行参数天然不同，不再撞作业实例，但也意味着 Batch 自身不再阻止重复提交，并发保护须由 ISO-07 的运行状态承担
- 状态：未开始
- 验证：`LineProcessor` 构造的 Behavior 携带正确 `assessmentId`

### D-06 `riskTriggered` 硬编码 false

- 优先级：P1
- 位置：`BehaviorProcessingService.java:436`、`:557`
- 现象：写入时固定 false，真实值由下游 `IndicatorResultRepository:59` 按分数阈值回写，不读 `riskRule`。
- 实证矛盾：`t_indicator_result` 表设有 `risk_triggered` 字段以承载指标级风险判定，821 条指标也都带 `riskRule`，但两者从未相遇——写入方给常量，回写方用另一套阈值，`riskRule` 全程未被读取。字段与规则都存在，中间的连接不存在。
- 后果：风险触发与指标自身的风险规则脱钩。
- 关联：`p0-02-baseline.md` 根因 4；修复方式取决于 D-15 的阈值语义决策
- 状态：未开始
- 验证：`riskTriggered` 由 Rule Engine 依 `riskRule` 决定，且计算详情可复算

### D-07 召回选不中目标指标

- 优先级：P1
- 位置：`BehaviorProcessingService.java:892`（`fetchTopIndicators`）
- 现象：纯 kNN 无过滤，k=6。金标准指标全库排名 65~279 位。相似度分布扁平（0.83~0.91，Top1 与第 6 名常差 0.01 以内）。
- 实证矛盾：k=6 的截断窗口与目标实际排名（65~279）相差一个数量级，召回在结构上不可能命中。且相似度区间跨度仅 0.08，Top1 与第 6 名差值常在 0.01 以内——这种分布下排序本身不携带区分信息，取 Top6 与随机取 6 条的差别很小。三案例命中金标准指标 0/8、0/8、0/6 与此完全一致。
- 后果：正确指标进不了 Top-K，评分对象本身就是错的。
- 关联：`p0-02-baseline.md` 根因 3；计划 2 的 Metadata Filter 与专用 Embedding 针对此项
- 状态：未开始
- 验证：30 条标注集的 Recall@5 / Recall@10 达到计划 0 冻结门槛

### D-08 法规重复挂载且引用为空

- 优先级：P1
- 位置：`BehaviorProcessingService.java:685`
- 现象：6 个指标共享同一批 10 条召回法规，`regulationId` 全为 null，`violationType`、`complianceRequirement` 硬编码空串。
- 实证矛盾：`t_regulation` 有 4865 条法规且 `full_text_vector` 覆盖率 4865/4865（本次实测），数据侧完备；但输出侧的 `regulationId` 全为 null。即法规被召回参与了评分计算，却没有留下任何可回指的标识——**用了但没记**。同时 `violationType` 与 `complianceRequirement` 是空串而非 null，掩盖了「未填充」与「确实为空」的区别。
- 后果：无法输出「违反某法规」的可信结论，违反 PLAN.md:43。
- 关联：`p0-02-baseline.md` 根因 5
- 状态：未开始
- 验证：每条法规引用可解析到真实 Regulation 且条款可回指

### D-09 零风险聚合除零判为高风险

- 优先级：P1
- 位置：`RiskLevelEnum.java:66-76`，除法在 `:74`
- 现象：`lowRiskCount`、`mediumRiskCount`、`highRiskCount` 全为 0 时分母为 0，得 `NaN`；`getByScoreRatio(NaN)` 所有比较为 false，落到 `else` 返回 `HIGH_RISK`。
- 实证矛盾：`getByScoreRatio`（`:56-64`）的 `else` 分支承担双重语义——既表示「比例低于 0.2」，也兜住了 `NaN`。两种输入语义相反（一个是最差，一个是无数据），却导向同一结果。**最好的情况（零风险）与最坏的情况（高风险）在此处不可区分。**
- 后果：零风险集合被判为高风险。
- 关联：`P0-08` 点名待修项；`p0-04-riskrule-design.md` 1.2 节
- 状态：**已修复**。P0-08 已修零风险计数与 NaN 边界；后续 P0-11 实施增加 NO_RISK，当前 getByRiskCount 在 totalRiskCount == 0 时返回 NO_RISK。P0-08 的 LOW_RISK 为当时过渡决策，不再是当前返回值。
- 验证：`RiskLevelEnumTest` 5 个用例，修复前 2 条失败（`expected: <LOW_RISK> but was: <HIGH_RISK>`、NaN 未抛异常）、3 条回归用例通过；修复后 5/5 通过，全模块 `mvn clean package` BUILD SUCCESS

### D-10 绝对阈值致 89 条指标恒触发风险

- 优先级：P1
- 位置：数据侧 821 条 `riskRule` 的 `staticThreshold.thresholdValue = 0.5`
- 现象：阈值是绝对分数，而指标级 `maxScore` 跨度 0.3~2.0。`maxScore < 0.5` 的 89 条（`0.4` 64 条 + `0.3` 25 条）即使得满分也 `< 0.5`，恒定触发风险；另有 270 条 `maxScore = 0.5` 仅满分不触发，220 条 `maxScore ≥ 1.0` 得分不足半数仍可能不触发。
- 实证矛盾：阈值是绝对分数，`maxScore` 是可变满分，两者量纲不可比却直接比较。极端体现为企业国际合作风险维度——其 `maxScore` 区间为 0.3~0.5，**整个维度 184 条指标的满分都不超过阈值 0.5**，其中 60 条无论如何取值都触发风险。而产品合规风险维度 `maxScore` 区间 1.0~2.0，得分只需超过 0.5（占满分 25%~50%）即安全。同一条阈值对两个维度的实际严格程度相差数倍。
- 后果：不是区分度不足，是判定错误。89 条集中于企业国际合作风险（60）与劳务合规风险（29）。
- 关联：`p0-04-riskrule-design.md` 第 1 节；处置方案为改用 `score / maxScore` 比例语义
- 状态：**已定方案**（`P0-11` 决议：统一为比例语义 `score / maxScore`，绝对阈值与可变 `maxScore` 量纲不可比的根因消除；六维度阈值表认可为初版，标注待实测校正。见 `p0-11-review-record.md` 2.1 与 2.3）
- 验证：89 条指标得满分时不再触发风险

### D-11 两套风险等级判定口径并存

- 优先级：P1
- 位置：`RiskLevelEnum.java:56-64`（比例语义 0.4 / 0.2 两档）与 821 条 `riskRule`（绝对值 0.5）
- 现象：代码里已有比例口径，与数据里的绝对值口径互不一致。
- 实证矛盾：同一个「风险等级」概念有三处互不一致的判定源——

  | 判定源 | 口径 | 阈值 |
  | --- | --- | --- |
  | `RiskLevelEnum.java:56-64` | 比例 | 0.4 / 0.2 三档 |
  | 821 条 `riskRule.staticThreshold` | 绝对分数 | 0.5 单档 |
  | `AssessmentServiceImpl`（Report 侧） | 比例 | 0.5 单档 |

  三者阈值数值与语义均不同。当前之所以没有暴露，仅因 `riskRule` 从未被执行、`getByScoreRatio` 与 Report 阈值作用于不同层级。
- 后果：一旦 `P3-06` 让 Rule Engine 执行 `riskRule`，同一指标会得出两个风险等级。
- 关联：`p0-04-riskrule-design.md` 1.1 节
- 状态：**已定方案**（`P0-11` 决议：三处判定源统一为比例语义 `score / maxScore`；821 条 `riskRule.thresholdValue` 须按六维度阈值表重新标定。见 `p0-11-review-record.md` 2.1）
- 验证：统一后同一 `scoreRatio` 只有一个判定结果

### D-12 `AssessmentRepository.findByProjectId` 返回单实体

- 优先级：P1
- 位置：`risk-warning-processing/.../repository/AssessmentRepository.java:14`；`risk-warning-org/.../repository/AssessmentRepository.java:10`；调用方 `ProjectServiceImpl.java:144`
- 现象：一个 project 有多个 assessment 时语义未定义，Spring Data 可能抛 `IncorrectResultSizeDataAccessException`。
- 实证矛盾：业务模型中 project 与 assessment 是一对多——`ExecuteQueueTask.java:93-103` 每次上传确认都新建 Assessment，从不复用；而查询签名 `Assessment findByProjectId(...)` 假设一对一。**写入按一对多，读取按一对一。**
- 后果：三案例每个 project 各只评估一次，故 Baseline 采集未暴露；多次评估后必现。
- 关联：`p0-03-isolation.md` ISO-05
- 状态：未开始
- 验证：同一 project 创建两个 assessment 后调用不报错且语义明确

---

## 三、可执行性障碍

### D-13 RANGE 规则依赖 JavaScript 表达式求值

- 优先级：P1
- 位置：数据侧 `RangeRule.calculationMethod`（字段定义见 `RangeRule.java:14`）
- 现象：存的是 JS 表达式字符串，110 条 RANGE 去重后 98 种。含三元表达式 93 条、括号嵌套 39 条、引用 `maxScore` 变量 9 条、JS 严格相等 `===` 5 条。
- 实证矛盾：三处自相矛盾。其一，`RangeRule` 已有 `minValue` / `maxValue` / `maxScore` 等结构化字段，却又用 JS 字符串重复表达同一套分段逻辑，两者可能不一致——实测存在 `minValue=0.0` 但表达式处理负值输入的规则（`x >= 0 ? ... : (x <= -50 ? 0 : ...)`），声明定义域与实际定义域冲突。其二，`RangeRule.java:15-17` 的注释指明 `maxScore` 属 `calculation_rule`、`outOfMinScore`/`outOfMaxScore` 属 `default_calculation_rule`，即单个类混装了两套规则来源的字段。其三，9 条表达式引用 `maxScore` 变量名，而 `RangeRule` 自身与指标级都有 `maxScore` 字段，求值时注入哪一个无从判断。
- 后果：Java 8 需 Nashorn 求值，等于执行来自 ES 的外部数据代码，构成注入面。
- 关联：`p0-04-rule-stats.md` 第 5 节
- 状态：**待决策**（求值 / 转结构化阈值 / 只支持子集，属 `P3-05`）
- 验证：选定方案后，110 条规则的求值结果与人工计算一致

### D-14 BinaryRule 的 `condition` 无判定输入

- 优先级：P1
- 位置：数据侧 711 条 `binaryRule.condition`
- 现象：`condition` 是中文自然语言判据（如「是否存在涉朝鲜业务往来」），需要有人判定真假才能按 `trueScore` / `falseScore` 计分。当前无任何环节产出该判定。
- 实证矛盾：`BinaryRule` 的字段组合暗示了一套完整的判定协议——`condition`（判什么）+ `trueScore` / `falseScore`（判定结果如何计分），三者齐备且 711 条无空值。但协议中「谁来判定」这一环在整个系统里没有实现方。规则数据已按可执行的形式准备好，执行者缺席。
- 后果：即使修好 D-01 拿到 711 条规则，也没有输入能回答这些 condition。
- 关联：这是计划 1 Evidence 抽取与计划 3 LLM 合规推理的存在理由，非缺陷而是未实现；列此以免被误判为「规则可直接执行」
- 状态：未开始（计划 1 / 计划 3 范围）
- 验证：每条 condition 有对应的 AnalysisResult 合规状态且可回指 Evidence

### D-15 RiskRule 全体同质

- 优先级：P1
- 位置：数据侧 821 条 `riskRule`
- 现象：去重后只有 1 种，`staticThreshold` 亦只有 1 种 `('<', 0.5, 'MEDIUM_RISK')`。不携带任何指标特异信息。
- 实证矛盾：821 条 RiskRule 的 `description` 字段全部为「风险生成规则」这一句占位文本，无一条说明该指标的风险含义；`adjustmentFactor` 也全部为 `requiredLowCount=2, riskLevelIncrease=HIGH_RISK`。与之对照，同一批指标的 `CalculationRule` 有 20 种评分组合、98 种表达式、711 条各异的 `condition`。**同一份数据中，计算规则逐条设计，风险规则全体复制**——两者投入程度不对等，说明 RiskRule 是补齐字段而非设计产物。
- 后果：`P3-06` 用 StaticThreshold 替换 Report 统一 0.5 阈值后行为不变，任务只完成决策位置迁移。
- 关联：`p0-04-riskrule-design.md` 已给出按 `dimension` 分组的六组三档阈值设计
- 状态：**已定方案**（`P0-11` 决议：`RiskRule` 改为有序 `thresholds` 列表支持三档，按 `dimension` 分组；六维度阈值表认可为初版。见 `p0-11-review-record.md` 2.2 与 2.3）
- 验证：同一 `scoreRatio` 在不同维度得到不同风险等级

### D-16 `RiskRule` 结构无法表达多档阈值

- 优先级：P1
- 位置：`StaticThreshold.java:12-15`（`riskLevel` 为单值）；`RiskRule.java:13`（`staticThreshold` 为单对象）
- 现象：现结构最多表达「一个阈值、一个等级」。
- 后果：D-15 的三档设计无法落地。
- 关联：`p0-04-riskrule-design.md` 4.2 节建议改为有序 `thresholds` 列表 + `thresholdMode` 显式声明语义
- 状态：**已定方案**（`P0-11` 决议：改为有序 `thresholds` 列表，`thresholdMode` 区分 `SCORE_RATIO` 与 `ABSOLUTE_SCORE`，新字段追加不改旧语义。见 `p0-11-review-record.md` 2.2）
- 验证：新结构能表达三档且旧数据仍可读

### D-17 BinaryRule 存在 72 条反向计分

- 优先级：P2（实现约束，非数据缺陷）
- 位置：数据侧 711 条 `binaryRule`
- 现象：72 条 `trueScore < falseScore`，横跨 9 种组合：`(0.0,0.5)` 27、`(0.0,1.0)` 11、`(0.0,0.6)` 9、`(0.0,0.4)` 9、`(0.0,2.0)` 6、`(0.0,0.8)` 3、`(0.0,0.3)` 3、`(0.0,0.7)` 2、`(0.0,0.9)` 2。
- 实证矛盾：`condition` 的措辞方向不统一——多数为正向（「是否具备良好资质」，成立得分），部分为负向（「是否发现合规漏洞」，成立扣分）。规则通过颠倒 `trueScore`/`falseScore` 表达负向语义，但数据中无任何字段标记方向，只能靠比较两个分数大小反推。本清单初版即因此误记为 27 条（只统计了最大的单一组合），实际 72 条——**这个矛盾已经在本项目内造成过一次统计错误**。
- 后果：Rule Engine 不得假设 `trueScore > falseScore`。
- 关联：`P3-04` 的测试须覆盖反向计分；`p0-04-rule-stats.md` 第 6 节（初版误记为 27 条，已更正）
- 状态：未开始
- 验证：`P3-04` 单元测试包含反向计分用例

### D-18 一二级指标无规则且聚合方式未定义

- 优先级：P2
- 位置：数据侧 level 1（53 条）与 level 2（270 条）
- 现象：323 条无 `calculationRule` 与 `riskRule`，规则 821 条全部挂在 level 3。
- 实证矛盾：`Indicator` 有 `parentIndicatorId` 字段支持层级聚合，但一二级指标既无规则也无已定义的聚合方式，层级结构存在而层级计算缺失。且 D-01 使 `indicatorLevel` 反序列化为 0，代码运行时根本区分不出层级——**层级信息在数据里有、在内存里没有**。
- 后果：一二级分数应由子指标聚合，但聚合方式未书面定义。
- 关联：`p0-04-rule-stats.md` 第 8 节
- 状态：未开始
- 验证：聚合规则书面冻结且可复算

### D-19 `type` 字段全空

- 优先级：P2
- 位置：数据侧 821 条有规则指标
- 现象：`type` 全部为空串，按指标类型分组不可行。
- 实证矛盾：`documents/系统设计.md:652-653` 为 `t_behavior.type` 定义了 `'定性'` / `'定量'` 取值域，`Indicator` 亦有同名字段并被 `IndicatorMetadataDTO` 读取（`BehaviorProcessingService.java:249`），但 821 条实际全为空串。字段有设计、有消费方，无数据。且空串而非 null，使「未填充」无法与「无类型」区分。
- 后果：分组只能依赖 `dimension`（六个维度）。定性/定量的区分本应决定走 BinaryRule 还是 RangeRule，现无法据此分派。
- 关联：`p0-04-riskrule-design.md` 第 3 节
- 状态：未开始
- 验证：确认是否需要回填，或明确该字段废弃

### D-20 定量值量纲混用且无单位声明

- 优先级：P2
- 位置：数据侧 110 条 `rangeRule` 的 `minValue` / `maxValue`
- 现象：以 `0.0—100.0`（49 条）与 `0.0—1.0`（20 条）为主，百分数与比例混用，单位语义未在数据中声明。
- 实证矛盾：两种量纲的规则共用同一个输入变量 `x`，而 `Behavior.quantitativeData` 是单一 `Double` 且无单位字段。同一个 `x=50` 对 `0—100` 规则表示中位，对 `0—1` 规则则远超上界。**规则期望两种量纲，输入只能提供一种无标注的数值。**
- 后果：Rule Engine 无法判断输入值该按哪种量纲比较。
- 关联：`P0-05` 的 Schema 需为定量值补单位字段
- 状态：未开始
- 验证：Schema 含单位字段且校验单位匹配

---

## 四、安全与工程债

### D-21 硬编码 LLM 凭据

- 优先级：**P0**（安全）
- 位置：`risk-warning-common/.../utils/LLMUtil.java:23`，使用点 `:458`
- 现象：百度千帆 API Key 以 `private static final String` 明文写死在源码中，已随仓库历史提交。
- 实证矛盾：仓库已具备外部化配置能力（Nacos 接入、`bert.python.path` 已改为 `${BERT_PYTHON_PATH:py -3.9}` 形式的占位符写法），同一仓库内既有正确做法的先例，LLM 凭据却仍以 `private static final String` 明文常量存在。并非缺少手段，而是未应用。
- 后果：凭据泄露。仅从源码删除不够，**必须先在百度云控制台吊销该 Key**，因为它已存在于 git 历史中，删除源码行不能使其失效。
- 关联：`P0-07`；PLAN.md:171 要求仓库、配置样例和日志均不得再出现有效凭据
- 状态：**代码侧已处理，供应商侧吊销待用户执行**。`P0-07` 已移除明文常量，凭据改由 `${LLM_API_KEY:}` 注入，全仓 `grep ALTAK|bce-v3` 0 命中；承载该凭据的旧工具类已在 2026-09-06 清理中删除。P0-06 实测该 Key 返回 403 `account_overdue` 而非 `invalid_token`，说明身份仍被服务端接受、欠费只是账户状态，充值后立即恢复可用，因此**控制台吊销仍是必须动作**。git 历史清除须用户决定是否重写历史。详见 `p0-07-provider-boundary.md`
- 验证：旧 Key 已吊销失效；新凭据经环境变量或 Nacos 注入；`grep` 全仓库无明文 Key

### D-31 LLM 调用无重试与退避

- 优先级：P1
- 位置：`risk-warning-common/.../utils/LLMUtil.java:462-487`
- 实证矛盾：`callLLMApi` 单次请求失败即抛 `IOException`，无重试、无退避、无区分可重试与不可重试错误。而 PLAN.md:169 明确要求验证过程「保留重试结果」，PLAN.md:185 又把 LLM Provider 不稳定列为预期风险——**计划把重试当作既有行为，实现里根本没有**。上层调用（`:143-144`、`:160-161` 等 6 处）也未各自补重试。
- 后果：任何瞬时网络抖动或供应商限流都会直接失败并中断整条分析链路。真实 LLM 接入后（计划 3）这是高频失败点，且与 D-30 的「先删后写」叠加时后果放大——旧结果已删、新运行因一次网络抖动失败。
- 影响范围修正：`P0-07` 核查确认 `LLMUtil` 改造前在全仓 `*.java` 中**无任何生产调用点**（唯一引用来自其自身 `main()`），因此本缺陷当时不污染运行时链路，属计划 3 接入真实大模型时才生效的缺陷。上述优先级依据（PLAN.md:169/185）不变。
- 关联：`P0-07` Provider 边界改造；D-30；`test/fixtures/p0/prompt_validation_run.py` 已实现最多 2 次重试加 2 秒间隔，可作参考口径
- 状态：**已修复**。`OpenAiCompatibleChatProvider` 按 `maxAttempts` 循环并指数退避，仅 408/429/500/502/503/504 与 `IOException` 触发重试；403 `account_overdue`、400、401 及响应结构缺失归入不可重试，一次终止。失败抛 `LlmProviderException` 不返回降级值
- 验证：`OpenAiCompatibleChatProviderTest` 10 个用例通过，其中 429 重试后成功、503 达上限且实际请求 3 次、403 与 400 实际请求 1 次

### D-32 无 temperature 参数，固定 Prompt 结果不可复现

- 优先级：P1
- 位置：`risk-warning-common/.../utils/LLMUtil.java:437-446`
- 实证矛盾：请求体只设 `model` 与 `messages`，未设 `temperature`，取供应商默认值（通常大于 0）。而 6 处 Prompt 全部要求「只输出 JSON 数组」「结果数组长度必须与输入一致」这类确定性输出，`buildBatchTagsInferencePrompt` 甚至依赖输出顺序与输入严格对应（`:221`）——**Prompt 假设输出确定，参数配置允许输出随机**。
- 后果：同一输入多次调用结果可能不同。批量推断场景下若返回数组长度漂移，会导致标签与文本错位；这类错位不会报错，只会静默产生错误的标签归属。也使「固定 Prompt 验证」失去前提。
- 影响范围修正：同 D-31，`LLMUtil` 改造前无生产调用点，本缺陷在计划 3 接入真实大模型时才生效。
- 关联：`P0-06`（脚本已显式设 `temperature=0.0`）、`P0-07`
- 状态：**已修复**。请求体显式写入 `temperature`，`LlmProviderProperties.temperature` 默认 `0.0` 且可配置
- 验证：单测断言 `temperature` 出现在实际请求体且值为 0.0；「同一输入连续调用 10 次输出逐字节一致」须等真实凭据到位后随 P0-06 live 模式验证

### D-33 Milvus 领域过滤对多值字段使用精确相等匹配

- 优先级：P2
- 位置：`VectorSearchService.java:129`（`buildFilterExpression`）
- 现象：`compliance_domain == "人工智能"` 精确匹配，但 Milvus 侧该字段是逗号拼接串（如「人工智能,电子制造,航空运输」），单值精确匹配恒为空。
- 实证：`search/regulations` 带 `industry=人工智能` 过滤返回 0 条；去掉过滤同 query 返回 3 条。Milvus 实测前 200 行中含「人工智能」子串 2 行、精确等于 0 行。旧 `industry` 字段时代即为同一拼接格式，本缺陷先于改名存在，非迁移引入。
- 根因：多值语义用单值串存储（逗号拼接），过滤用 `==` 单值匹配。Milvus 表达式仅支持前缀 `like "ab%"`，不支持两侧通配，无法用 like 兜底。
- 后果：按领域过滤的检索（`P2-04` Metadata Filter）静默返回空，不报错。当前无生产调用方（仅测试接口暴露），计划 2 检索链接入时才真正生效。
- 关联：P0-11 决议 1.5（改名同步）；PLAN.md 计划 2 「industry 取值域清理」（遗留事项）
- 状态：未开始。候选方案：① 写入侧改用 Milvus `Array<String>` 字段类型（`array_contains` 原生支持）；② 过滤侧改多个 `or` 拼接（须先拉全量不同值域）；③ ES 侧 keyword 数组过滤替代。须在计划 2 定义取值域时一并决策。
- 验证：带领域过滤的检索能召回该领域下的文档，且不误召回其他领域文档。

### D-34 Milvus VarChar 长度按字节校验，中文超长写入失败

- 优先级：P2
- 位置：`MilvusRepository.java` 写入链（`insertVectors`）
- 现象：schema `name` 字段 `max_length=256`，92 个中文字符的 name 为 258 个 UTF-8 字节，Milvus 按字节拒绝：`the length (258) of 0th string exceeds max length (256)`。
- 实证：Milvus 差集补数时 2 条法规（`8AUtUZ0B...`、`FAUtUZ0B...`）稳定失败，Python 按字符数测长合规、按 UTF-8 字节数测长超限。当次以 250 字节安全截断绕过。
- 后果：任何超长中文名（约 85+ 字）写入 Milvus 即失败，且整个批次 insert 一并失败——批量存储场景下单条脏数据会阻塞全批。当前仅测试接口暴露，知识库向量化链在计划 2 检索接入时生效。
- 关联：D-33（同属 Milvus 写入/过滤链健壮性）
- 状态：未开始。候选方案：① `VectorStorageService` 写入前按 UTF-8 字节统一截断（含 name/compliance_domain/dimension/region 全字段）；② schema `max_length` 提至 512 以上并同步 `MilvusRepository.createCollectionIfNotExists`。
- 验证：构造 300 字节中文名与 300 字节领域串的写入用例，不抛异常且截断后不劈开多字节字符。

### D-22 行为写入无幂等键

- 优先级：P2
- 位置：`LineProcessor`、`LineRangeItemWriter`
- 现象：同一文件重传 N 次产生 N 倍行为。首轮 CASE-001 上传 4 次得 27 条行为。
- 实证矛盾：清理前后总分逐位相同（`32.3364626851702`），27 条重复行为与 9 条产生完全一致的结果。这暴露了一个比重复本身更重要的设计矛盾——同指标下多行为取平均（`BehaviorProcessingService.java:412`），意味着**系统不做违规事实的累计聚合**：同一指标下发现 1 次违规与发现 10 次违规，得分相同。对合规风险评估而言，违规频次本应是加重因素。
- 后果：重复行为不影响评分（故不属 `P0-08` 范围），但放大存储与计算开销，且每次采集 Baseline 前须手工清零。上述「取平均」的聚合语义需在计划 3 单独决策。
- 关联：`p0-03-isolation.md` ISO-06；因不污染评分结果，不属 `P0-08` 范围
- 状态：**已实施 / 单元验证**。`LineProcessor` 按 `(analysisRunId, sourceDocumentId, textHash)` 生成 32 位稳定 `Behavior.id`，`LineRangeItemWriter` 将其作为 ES `_id`；不能用 `assessmentId`，否则同一 assessment 的两次运行会被误判为重复而互相覆盖。处理单测已覆盖等价文本和跨 Run 的 ID 语义。
- 待验证：专用 ES 环境中同 `_id` 连续写两次的实际文档数为 1；同一 assessment 的两次运行各自产出完整行为集且不互相去重。

### D-23 `sourceDocumentId` 存储粒度不足

- 优先级：P2
- 位置：`risk-warning-common/.../po/file/ProjectFile.java:19-35`；写入侧 `ExecuteQueueTask.java:71-89`
- 现象：一行存一个 `filePaths` JSON 数组（`:30-31` 经 `StringListJsonConverter`），一次上传多个文件只产生一行。其主键语义是上传批次 ID，非单文档 ID。
- 实证矛盾：`ExecuteQueueTask.java:75-88` 在循环中逐个处理文件，却在循环内反复对同一个 `projectFile` 对象 `setUserId` 并向其 `filePaths` 追加路径，循环结束后只 `save` 一次（`:89`）。逐文件处理与单行落库的粒度不匹配——多个文件的身份在落库时被合并丢失。
- 后果：无法为单个源文档分配持久标识，证据回溯定位缺少锚点。
- 关联：`p0-03-isolation.md` ISO-01；P0-05 未决项 U-01
- 状态：**已修复 / 已验证**。PG 已改为一文件一行，ProjectFile.id 为单文件锚点、filePath 为单路径；迁移核对与用户真实链验证完成。Behavior 回指由 D-03 / P1-03 实现，不再作为文件粒度缺陷重复开发。
- 优先级调整：P2 → **P1**（`EvidenceChunk` 与 `StructuredBehavior` 均以它为必填锚点，Plan 1 前必须完成）
- 验证：ProjectFile 模型与迁移记录已核对；用户确认真实上传链通过。

### D-30 重复分析会销毁上一次运行的结果

- 优先级：P0
- 位置：`BehaviorProcessingService.java:183`（`deleteByAssessmentId`）；`AssessmentRepository.findByProjectId` 返回单实体（org 侧 `:10`、processing 侧 `:14`）
- 实证矛盾：P0-05 已决议**允许同一 assessment 重复分析**，但现有实现在每次分析开始时按 `assessmentId` 清空旧结果后重写。两者直接冲突：第二次运行会把第一次的 IndicatorResult 与 Risk 全部删除。此外 `assessmentId` 在允许重复运行后不再能唯一标识一次运行，而全链路目前只有这一个作用域字段（`analysisRunId` 尚未实现）。
- 后果：历史运行结果不可保留、不可比对，与 PLAN.md:114 要求的新旧结果可比性冲突。修改规则或阈值后无法验证改动效果——这恰是 Plan 3 规则改造的验收前提。
- 关联：D-03、D-04、U-07、U-10；`analysisRunId` 实现（`P1-01`）为其前置
- 状态：**已实施 / 单元验证**。`IndicatorResult` 已按 `(assessmentId, analysisRunId, indicatorEsId)` 查询和覆盖写入，计算开始时不再删除整个 assessment；`Risk` 与报告查询均携带 `analysisRunId`。报告仅在 Run 成功持久化后删除同 assessment 的非当前 Run 指标结果、Risk 与 Behavior；汇总或指标阶段异常会将该 Run 标为 FAILED，保留上次成功结果。
- 待验证：在专用 PG/ES 环境执行 005 迁移后，验证 A2 运行期间 A1 可查、A2 失败仍保留 A1、A2 成功后只保留 A2，以及同 Run 重投计数不增长。

### D-24 测试含 macOS 硬编码路径

- 优先级：P2
- 位置：`risk-warning-processing/src/test/java/com/riskwarning/common/BatchTest.java:39-40`
- 现象：`/Users/huayecai/Desktop/bach_01/...` 绝对路径，另 `:42` 调用 `runBatchJob` 时也只传 `projectId` 与 `filePaths`，未传 `assessmentId`（与 D-05 同源）。
- 后果：其他机器上执行全量 `mvn test` 会失败。仓库主要开发环境为 Windows，该测试在本机必然失败。
- 状态：**已修复**。旧 BatchTest 已删除，当前仓库无该文件；F-13 回归空洞由 P1-07（重定义）补齐——旧 Batch 类已随主链路移除，P1-07 改为核查新事实抽取链的测试覆盖并清理残留引用。
- 验证：静态确认 BatchTest 已删除；不据此宣称全量 Maven 测试通过。

### D-25 分类模型检查点未随仓库分发

- 优先级：P2
- 位置：`classify-service/checkpoints/best_model.pt`（被 gitignore）
- 现象：他人 clone 后缺失，分类服务无法启动。
- 实证矛盾：向量化与分类是两条独立路径且易被误认为同一服务——knowledge 走 Docker `bert-service` 容器的 `:8000/encode`，分类走本机 bert 微服务拉起 Python 到 `:8002`，Python 依赖装在系统 Python 3.9。缺少检查点时，向量化仍正常而分类失败，故障表现为部分功能异常而非启动报错，难以定位。
- 后果：新成员无法复现完整链路。
- 状态：**说明已补，分发未实现**。P1-07 已在 `risk-warning-bert/README.md` 记录默认路径、`CLASSIFIER_MODEL_PATH` 覆盖方式、缺 checkpoint 时的实际降级行为和测试 Stub 约定；不分发或提交私有 checkpoint。
- 验证：提供与 `MultiTaskClassifier` 兼容的训练权重后，设置 `CLASSIFIER_MODEL_PATH`，启动服务并确认 `/classify/health` 的 `model_loaded=true`，再以已知样本核验分类结果；单元测试继续使用 Stub，不以私有 checkpoint 作为前置。

### D-35 消费端未配置 ErrorHandlingDeserializer，单条损坏消息卡死整个分区

- 优先级：P1
- 位置：Nacos `common-dev.yaml` `spring.kafka.consumer.value-deserializer`（直接使用 `JsonDeserializer`）；影响共用 `test-consumer` 组的全部服务（processing、report 等）
- 实测（2026-09-06 S1 重放验证）：`behavior_processing_tasks-2` offset 3 一条格式损坏的消息（重放工具写入时 header 文本混入 payload）触发 `RecordDeserializationException`，`DefaultErrorHandler.handleOtherException` 抛 `IllegalStateException: This error handler cannot process 'SerializationException's directly`。反序列化发生在 poll 层，错误处理器无法推进位点，消费者对同一条消息无限重试——处理日志刷屏、CPU 空转、HTTP 探活超时，最终须以成员身份加入消费组手工把位点前移才恢复。
- 后果：单条毒丸消息使整个消费组停滞，且恢复必须运维介入（reset offset）；消息损坏可能来自任何非标准生产方，与 D-36 同源。
- 关联：D-31 的重试覆盖 Provider 请求层，本条是消费入口层；P1-05 的 S1 同 run 重投场景依赖消息可安全重放
- 状态：未开始
- 验证：`value-deserializer` 改为 `ErrorHandlingDeserializer`（delegate 指向 `JsonDeserializer`）后，写入一条格式损坏消息，消费组 lag 正常前进、错误仅留日志、业务链无感知

### D-36 手工或跨语言生产的消息缺 `__TypeId__` header 时被静默跳过

- 优先级：P2
- 位置：Nacos `common-dev.yaml` `spring.json.value.default.type: com.riskwarning.common.message.Message`（基类兜底）；listener 方法参数为具体子类（如 `MessageTask.onMessage(BehaviorProcessingTaskMessage)`）
- 实测（2026-09-06 S1 重放验证）：用 `kafka-console-producer` 原样重投消息（无 `__TypeId__` header），消费位点前进、lag 归零，但 `onMessage` 从未执行且无任何业务日志——`JsonDeserializer` 缺 header 时 fallback 反序列化为基类 `Message`，与 listener 期望的子类不匹配，被错误处理器静默丢弃。同一 payload 改由 kafka-python 附带 `__TypeId__: com.riskwarning.common.message.BehaviorProcessingTaskMessage` header 后被正常消费。另一次重放失败源于 PS 5.1 写文件默认 UTF-8 BOM，BOM 前缀使 JSON 解析失败——非标准生产方的编码问题与 header 缺失叠加，均无日志可查。
- 后果：所有不经 Spring `JsonSerializer` 的消息（手工重放、kafka-ui、测试工具、跨语言生产者）静默丢失，无错误日志可检索——与 D-01 的 Jackson 静默赋 null 同属静默失败模式，且直接阻塞 S1 类消息重放验收。
- 关联：D-35；P1-05 的 S1 验收依赖重放通道
- 状态：未开始。候选方案：① 消费端改 `ByteArrayDeserializer` + 容器级 `JsonMessageConverter`，listener 参数类型自动推断，不再依赖 header；② 短期约定：任何手工重放必须附带 `__TypeId__` header
- 验证：无 header 的合法消息能被对应 listener 正常消费，或至少产生可检索的解析错误日志

### D-37 已终态 run 重放成功不刷新 finished_at，重放操作无留痕

- 优先级：P2
- 实测（2026-09-06 S1 重放验证）：同 run 重投全链成功后，`t_analysis_run.finished_at` 仍为首次终态值（2026-09-06 21:21:45.573），重放执行时间无处可查；本次仅能凭 ES 行为的 `createdAt` 全量刷新间接推断发生过重放。
- 后果：不影响数据正确性（先清后抽保证结果自洽），但重放类运维操作缺审计锚点，排查「数据何时被改写」时只能靠下游表的时间戳倒推。
- 关联：D-30 的同 run 重投语义；D-35/D-36 修复后重放将成为常规验收手段，此问题出现频次随之上升
- 状态：未开始。候选方案：① run 表增加 lastReplayedAt 类字段；② 允许 finished_at 在同 run 二次成功时刷新；③ 接受现状并写入运维说明
- 验证：重放后能从 PG 直接判断该 run 发生过重放及其时间

### D-38 mvn spring-boot:run 启动拉取不到 Nacos 配置，非 IDE 启动路径不可用

- 优先级：P2
- 位置：仓库根目录 `run-processing.ps1`（两步构建：先 `install` common，再单模块 `spring-boot:run`）；processing 的 `bootstrap.yml`
- 实测（2026-09-06）：脚本拉起的 JVM 报 `Failed to configure a DataSource ... profiles common are currently active`，`common-dev.yaml` 未取到；同一 profile 组合下 VSCode Spring Boot Dashboard 启动正常。脚本已内置 `.env` 加载与 JDK 8 切换，排除了凭据与版本因素。
- 后果：无 IDE 场景（CI、他人环境）无法用命令行启动服务；当前以 VSCode 启动为准，脚本保留待查。
- 关联：P1-05 切片验收采用 VSCode 启动路径，本条为该决策的遗留债
- 状态：未开始
- 验证：`mvn spring-boot:run` 启动的 processing 完成 Nacos 配置拉取并正常连接 PG，与 IDE 启动行为一致

---

## 五、按修复顺序的建议路径

当前顺序以 [PLAN](../../PLAN.md) 与 [P1 对接说明](../../plan1/p1-handoff.md) 为准：

1. 已闭环：D-01、D-23、D-24、D-09、D-31、D-32；F-07/F-09 和 Milvus 迁移补数不进入 P1 重复开发。
2. P1 Entry Gate：P1-01 运行模型与 ID 透传（D-05），P1-02 文档 Evidence，P1-03 Behavior 作用域与查询（D-03/D-04）；P1-06 的幂等和安全替换基础工作（D-22/D-30）前置至 Prompt 开始前。
3. P1-04/05 事实抽取解决 D-02，复用已有 Provider；随后复验 P1-06（2026-09-06 三场景通过），P1-07（重定义）补齐新链回归空洞，P1-08/09/10 完成追溯与真实材料验收。
4. P2 处理 D-07/D-08、领域取值与 D-33/D-34；P3 处理 D-06/D-10/D-11/D-13 至 D-18 的推理和规则问题，D-13 仍待 P3-05 决策。
5. D-21 保留供应商吊销待办；D-25 保留模型分发问题，P1 单元测试使用 Stub，不要求私有 checkpoint。

## 六、矛盾类型索引

按矛盾性质归类，便于识别同类问题在其他位置的复现。

| 矛盾类型 | 表现 | 条目 |
| --- | --- | --- |
| 同一概念两套命名 | 配置用 snake_case / 存储用 camelCase；文档 mapping 用 `vector` / Java 用 `*_vector`；枚举定义 `国际化经营风险` / 数据用 `企业国际合作风险` | D-01、D-26、D-28 |
| 同一概念多套判定口径 | 风险等级有三处不同阈值与语义 | D-11、D-10 |
| 上下游假设相反 | 生产侧不产出 `status` / 消费侧当必有输入；写入按一对多 / 读取按一对一 | D-02、D-12 |
| 同一方法内前后不一致 | `assessmentId` 写入时用、读取时弃；null 校验严格但校验点在使用点之后 | D-04、D-05 |
| 实现前提与业务决议相反 | 实现按「一次评估只分析一次」清空重写，业务允许重复分析 | D-30 |
| 字段存在但无连接 | `risk_triggered` 与 `riskRule` 从未相遇；`condition` 无判定方；`type` 有消费方无数据 | D-06、D-14、D-19 |
| 用了但没记 | 法规参与评分却不留 `regulationId` | D-08 |
| 结构与数据投入不对等 | CalculationRule 逐条设计 / RiskRule 全体复制 | D-15 |
| 结构表达能力不足 | 单值 `riskLevel` 无法表达三档；`ProjectFile` 单行无法标识多文档 | D-16、D-23 |
| 声明与实际不符 | `minValue=0.0` 但表达式处理负值；Baseline 记录 2121 实测 2146 | D-13、D-27 |
| 空串掩盖语义 | `type`、`violationType`、`complianceRequirement` 用空串而非 null | D-08、D-19 |
| 异常被静默压制 | `FAIL_ON_UNKNOWN_PROPERTIES=false` 掩盖字段缺失；`NaN` 落入 `else` 分支 | D-01、D-09 |
| 同仓库内做法不统一 | `bert.python.path` 已外部化 / LLM 凭据仍硬编码 | D-21 |
| 计划假设实现具备而实现缺失 | 计划要求保留「重试结果」，实现无任何重试 | D-31 |
| Prompt 假设与调用参数矛盾 | Prompt 要求确定性输出，未设 `temperature` 允许随机 | D-32 |
| 字段名与实际语义不符 | `industry` 承载的是合规领域分类而非行业分类 | D-29 |
| 靠硬编码特例兜住不一致 | 运行时校验编译期常量，只为映射两个维度名 | D-28 |
| 非标准消息被静默丢弃或卡死 | 缺 `__TypeId__` header 的消息位点前进但业务无感知；反序列化失败的消息使消费组无限重试 | D-35、D-36 |

## 七、统计

统计保留全部 34 条历史登记；“未闭环”包括未开始、已定方案和部分完成，不等于待决策。F 系列不重复计入 D 系列总数。

| 优先级 | 登记总数 | 已修复 | 未闭环 | 其中待决策 |
| --- | --- | --- | --- | --- |
| P0 | 8 | 1（D-01） | 7（含 D-21、D-26 部分完成） | 0 |
| P1 | 16 | 4（D-09、D-23、D-31、D-32） | 12（含 D-28 部分完成） | 1（D-13） |
| P2 | 14 | 1（D-24） | 13（含 D-29 部分完成） | 0 |
| 合计 | 38 | 6 | 32 | 1 |

2026-09-06 追加 D-35 至 D-38 共 4 条（P1×1、P2×3），来源为 P1-05 切片场景与 S1 同 run 重投验收：消费链两条（D-35 毒丸消息卡死分区、D-36 缺 `__TypeId__` header 静默跳过）、审计与启动路径各一条（D-37 重放无留痕、D-38 mvn 启动无 Nacos 配置）。

D-26/D-28/D-29 已有实施变化，但本次不扩大已验证结论，保留专项核对项。D-21 未取得吊销证据，不关闭。

以下为历史推进记录，迁移后的当前状态见上表及各条目：

P0-05 的九条决议消掉了 3 条待决策（D-23 存储粒度、D-28 维度取值、D-29 字段语义），同时「允许重复分析」这一决议引出 D-30，净减 2 条。

P0-07 关闭 D-31、D-32，并推进 D-21 至「代码侧已处理」。同时修正了一处影响判断：`LLMUtil` 改造前在全仓无生产调用点，因此 D-31 / D-32 当时并未污染运行时链路，属计划 3 才生效的缺陷。这不改变其优先级依据。

P0-08 关闭 D-09。该任务核查了第一层其余三条与全部已确认缺陷，逐条说明了不纳入本阶段的理由（见 `p0-08-deterministic-bugfix.md` 第 1 节）：D-24 需先决定 `BatchTest` 的定位（登记 F-06），D-30 依赖 U-07 的 `analysisRunId` 在计划 1 落地，其余均属待决策或依赖计划 2 / 3 的实现。核查中另发现两项独立问题：F-07（`overallRiskLevel` 按 ORDINAL 持久化，枚举顺序变更会静默改变存量数据含义）、F-08（`中期汇报_系统实现资料稿.md` 的阈值描述为 0.8/0.5/0.3 四档且含 `CRITICAL`，与代码的 0.4/0.2 两档三值不符）。

P0-09 不关闭任何缺陷（属对账与冻结任务，未改代码），但产出三项结果：一是冻结了向量字段名、768 维与 `index: true` / `similarity: cosine` 检索参数；二是修正 D-01 根因（命名分裂源于数据文件而非配置项）并补强 D-26（文档 mapping 缺检索参数，重建即丧失 kNN 能力）；三是撤销一处此前登记的疑点——`t_risk` 的 444373 系 Lucene 文档数，含 `related_indicators` 嵌套子文档，根文档实为 2596 条，非数据损坏。新增 F-09（`Risk.java:54` 字段名 `createAt` 少一个 "d"，致 ES 中 `create_at` 有数据而文档声明的 `created_at` 恒空）、F-10（`es_mappings.json` 无法重建出可用索引，运行时状态不可从仓库复现）、F-11（`中期汇报_系统实现资料稿.md:453` 与 `:671` 对向量字段名自相矛盾）。详见 `p0-09-field-contract.md`。

剩余 9 条待决策集中到 `P0-11` 一处评审：ES 字段契约与改名策略、孤儿字段处置、RiskRule 阈值认可、重复分析的结果保留策略、EvidenceChunk 的运行归属。`P0-09` 原计划承担的字段契约决策已确认不宜由单人执行——统一命名须与移除 SNAKE_CASE、迁移存量数据同批进行，故一并移交 `P0-11`。该评审未完成前，第二层及之后的改造都不宜开工。

`P0-11` 完成 15 项决策，关闭上述全部 9 条待决策及 D-24，详见 `p0-11-review-record.md`。待决策由 9 条降至 1 条——仅剩 D-13（RANGE 规则的 JavaScript 表达式处置），因 110 条规则去重后有 98 种不同表达式，须结合真实表达式分布在 `P3-05` 决定。评审另实测补齐 F-12：Milvus `regulation_vectors` 确有 `industry` 存量且被 `VectorSearchService.java:128` 精确匹配过滤使用，故 `complianceDomain` 改名须同步 Milvus，否则检索过滤静默失效——与 D-01 的 Jackson 静默赋 null 属同类失败模式。`indicator_vectors` 当前未 load，存量条数待迁移前确认。新增 F-13：删除 `BatchTest` 后 Batch 链路失去回归依据，须在 `P1-07` 补不依赖基础设施的单元测试。

**P0-11 评审当日全部破坏性变更均未执行（历史状态，已被下述迁移记录更新）。** `P0-11` 只作决策，实施须先完成四索引 ES snapshot 备份，再按记录第五节的窗口顺序执行。

> 迁移窗口执行记录（2026-09-04）：5.2 七步全部完成并核对通过；ES 快照 `pre_camelcase_migration` 保留待业务回归后清理。窗口后补做：`t_risk` 孤儿 96 条清理（assessment 29/30/31 已删，级联缺失遗留）、`createdAt` 按 PG 评估时间回填 2500 条、Milvus 差集补齐 100 条（发现 D-34）、种子文件 camelCase 转换（备份于 `documents/data/backup_snake_case/`）、F-09 Java 侧修正（`Risk.java`/`RiskVO.java`/`AssessmentServiceImpl`）。补数过程中发现 D-33（领域过滤精确匹配对多值串恒空）。

## 八、证据来源说明

本清单每条的「实证矛盾」均来自以下三类可复核来源，不含推测：

- **代码静态读取**：引用的文件与行号已逐一确认，可直接打开核对
- **ES 实测查询**：D-01 的命名命中数、D-26 的向量字段覆盖率、D-27 的文档数、D-10 各维度 `maxScore` 区间、D-15 / D-17 / D-19 / D-20 的规则分布，均由 `test/fixtures/p0/indicator_rule_stats.py` 或等价查询在 2026-08-27 实测取得
- **Baseline 记录**：D-02 / D-06 / D-07 / D-08 的运行时现象引自 `p0-02-baseline.md`，该文档记录了三案例（assessment 32/33/34）的实际执行结果

尚未取得实测证据的部分已在对应条目注明，例如 D-12 的异常仅为按 Spring Data 语义的推断，因当前每个 project 只有一次评估而未实际触发。

## 九、P1 开工状态同步（2026-09-05）

用户确认上传 → 行为 → 指标 → 风险 → 报告真实链路、新 Risk.createdAt、文本风险等级、Report 查询及 Nacos 注册通过。此为用户提供的验证结论，本次仅核对代码与文档，未重新运行服务或测试。F-07/F-09 与 Milvus 迁移补数已闭环；D-33/D-34 独立保留。单链可运行不证明跨 Assessment/Run 隔离正确。详见 [P1 开发对接说明](../../plan1/p1-handoff.md)。
