# P0-09 字段契约对账与向量冻结

任务：核对 Java 字段、文档 Mapping 和运行时 ES Mapping，冻结向量字段、维度和版本策略。

对账时间：ES 8.11.0，`http://localhost:9200`，四索引在线实测。证据留存于 `documents/plan0/baseline/p0-09-evidence/`。

## 1. 结论摘要

三方（Java POJO、`documents/es_mappings.json`、运行时 ES）**没有任何一对是一致的**。核心事实：

1. 命名风格分裂的根因不在代码，而在初始数据文件本身：`documents/data/indicator.json` 为 camelCase，`regulation.json` 与 `behavior.json` 为 snake_case。`test/init_es.py` 原样导入，不做字段改名。
2. `documents/es_mappings.json` 全为 snake_case，与 `t_indicator` 的实际存量文档**完全错位**：声明的 `indicator_level`、`max_score`、`calculation_rule`、`risk_rule`、`parent_indicator_id` 五个字段覆盖数均为 **0**。
3. 承载全部向量数据的 `name_vector` / `description_vector` / `full_text_vector` **在任何版本控制的可执行产物中都没有声明**，只存在于运行时和设计文档。它们由版本控制之外的手工操作创建，当前运行时状态**无法从仓库重建**。
4. `documents/es_mappings.json` 声明的 `vector` 字段在三索引中覆盖数均为 0，且缺少 `index: true` 与 `similarity`，按该文件重建索引会使 kNN 检索直接不可用。

## 2. 运行时实测

### 2.1 文档数与向量覆盖

| 索引 | 文档数 | 向量字段 | 覆盖数 | 孤儿 `vector` 覆盖数 |
| --- | --- | --- | --- | --- |
| `t_behavior` | 2146 | `description_vector` | 2146 | 0 |
| `t_indicator` | 1144 | `name_vector` | 1144 | 0 |
| `t_regulation` | 4865 | `full_text_vector` | 4865 | 0 |
| `t_risk` | 2596 | 无向量字段 | — | — |

三个业务索引的向量覆盖率均为 100%。

### 2.2 向量字段参数（运行时权威值）

三索引的 `*_vector` 与 `vector` 参数完全一致：

```json
{"type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine"}
```

`documents/es_mappings.json` 中的声明缺少后两个参数：

```json
{"type": "dense_vector", "dims": 768}
```

ES 8.x 中 `dense_vector` 默认 `index: false`，此时不支持 `knn` 检索。故按现有文档重建索引将丧失向量检索能力，这是**可执行文档与运行时的实质性偏离**，不是格式差异。

### 2.3 `t_risk` 文档数澄清

`_cat/indices` 显示 `t_risk` 有 444373 条，实为 Lucene 文档数，包含 `related_indicators` 嵌套子文档。经 `_count` 实测根文档为 **2596** 条，嵌套子文档约 441777 条。此前记录的「444373 条异常」不成立，撤销该疑点。

但 2596 条本身确认了累积问题：`assessment_id` 聚合显示前十桶为 8—17，每桶 180—373 条，基线评估 32/33/34 未进入前十。`LineRangeItemWriter.java:154` 的批量写入不设 `_id`，每次运行全部新建文档，即 D-22 所述缺少幂等键的直接后果。`behavior.json` 仅 600 条而 `t_behavior` 有 2146 条，同源于此。

## 3. 三方字段对账

### 3.1 `t_indicator`（分歧最严重）

运行时同时存在 camelCase 与 snake_case 两套字段名，后者全部为空壳：

| Java 字段 | 运行时实际字段（有值） | 覆盖数 | `es_mappings.json` 声明 | 该声明覆盖数 |
| --- | --- | --- | --- | --- |
| `indicatorLevel` | `indicatorLevel` (long) | 1144 | `indicator_level` (integer) | 0 |
| `maxScore` | `maxScore` (float) | 1144 | `max_score` (double) | 0 |
| `calculationRule` | `calculationRule` (object) | 821 | `calculation_rule` (object) | 0 |
| `riskRule` | `riskRule` (object) | 821 | `risk_rule` (object) | 0 |
| `parentIndicatorId` | `parentIndicatorId` (text) | 1144 | `parent_indicator_id` (keyword) | 0 |
| `nameVector` | `name_vector` | 1144 | 未声明 | — |
| `createAt` | 不存在 | 0 | `created_at` (date) | 0 |

两套字段的 ES 类型也不同（`long` 对 `integer`、`float` 对 `double`、`text` 对 `keyword`），说明 snake_case 一套来自 `es_mappings.json` 建索引，camelCase 一套由动态映射在导入时推断生成。

`questionnaire_form` 已声明但覆盖数 0；`indicator.json` 无 `createAt` / `created_at` 字段，故时间字段全空。

### 3.2 `t_behavior`

| Java 字段 | 注解 | 运行时字段 | 覆盖数 |
| --- | --- | --- | --- |
| `projectId` | `@JsonProperty("project_id")` | `project_id` | 2146 |
| `descriptionVector` | `@JsonProperty("description_vector")` | `description_vector` | 2146 |
| `behaviorDate` | 无 | `behavior_date` | 2146 |
| `createdAt` | 无 | `created_at` | 2146 |
| `quantitativeData` | 无 | `quantitative_data` | 1921 |
| `id` | 无 | `id` | 1546 |

无注解字段之所以仍为 snake_case，是 `ElasticSearchConfig.java:61` 的 `PropertyNamingStrategy.SNAKE_CASE` 所致，恰好与 `behavior.json` 的 snake_case 对齐。因此两个 `@JsonProperty` 注解是冗余的。此处「读写正常」属巧合，而非契约正确。

`id` 覆盖 1546 条而非全量：`behavior.json` 导入的 600 条无 `id` 字段（ID 仅存于 `_id` 元数据），Java 写入的 1546 条才带 `id` 字段值。

### 3.3 `t_regulation`

字段名与文档声明一致（同为 snake_case）。差异项：`id` 覆盖数 **0**（`regulation.json` 无 `id` 字段），`tags` 2399/4865，`quantitative_indicator` 1918/4865。

### 3.4 `t_risk`

`Risk.java` 全 camelCase 且无任何 `@JsonProperty`，经 SNAKE_CASE 映射器输出 snake_case。

| Java 字段 | 运行时字段 | 覆盖数 | 说明 |
| --- | --- | --- | --- |
| `createAt` | `create_at` (long) | 2596 | Java 字段名少一个 "d"，忠实映射为 `create_at` |
| — | `created_at` (date) | 0 | `es_mappings.json` 声明，从未写入 |
| `affectedObjects` | `affected_objects` | 0 | `String[]`，写入方始终为 null |
| `relatedIndicators` | `related_indicators` (nested) | 2596 | 全部根文档均有嵌套内容 |
| `processingStatus` | `processing_status` | 505 | 部分覆盖 |
| `id` | `id` | 0 | 仅存于 `_id` 元数据 |

`create_at` 与 `created_at` 并存是 `Risk.java:54` 字段名拼写缺陷经映射器放大的结果，不是数据录入错误。该字段类型为 `long`，与文档声明的 `date` 也不同。

## 4. 缺陷归档更新

- **D-01（命名不一致）**：根因确认为数据文件命名分裂，`ElasticSearchConfig.java:61` 的 SNAKE_CASE 是放大器而非唯一原因。此前 `p0-05-core-schemas.md:27` 记载「必须移除 SNAKE_CASE」，该结论需修正：单独移除会立刻破坏 `t_behavior`、`t_regulation`、`t_risk` 三个索引的读写（它们的存量数据是 snake_case），必须与数据迁移同批进行。
- **D-26（孤儿 `vector` 字段）**：确认三索引均有该字段且覆盖数均为 0，可判定为无用声明。但不能仅删声明了事，因为它同时暴露了「文档声明的字段不是实际使用的字段」这一更大问题。
- 新增 **F-09**：`Risk.java:54` 字段名 `createAt` 拼写缺陷，导致 ES 中存在 `create_at`（long，有数据）与 `created_at`（date，空）两个字段。修正需迁移 2596 条存量数据。
- 新增 **F-10**：`documents/es_mappings.json` 无法重建可用索引（缺 `*_vector` 声明、缺向量检索参数、`t_indicator` 字段名与数据文件不匹配）。当前运行时状态不可从仓库复现。
- 新增 **F-11**：`documents/中期汇报_系统实现资料稿.md:453` 记载行为向量字段为 `vector`，`:671` 记载指标向量为 `name_vector`，文档内部自相矛盾；`453` 与运行时不符。

## 5. 冻结决定

以下三项即刻冻结，计划 1—5 不得再引入同名不兼容定义。

### 5.1 向量字段名（冻结）

| 索引 | 向量字段名 | 源文本字段 |
| --- | --- | --- |
| `t_behavior` | `description_vector` | `description` |
| `t_indicator` | `name_vector` | `name` |
| `t_regulation` | `full_text_vector` | `full_text` |

依据：运行时 100% 覆盖，且 `EsVectorizationUtil.java:29/46/62` 硬编码一致。裸 `vector` 字段废弃，新建索引不再声明。

### 5.2 向量维度与检索参数（冻结）

```json
{"type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine"}
```

维度 768 在三处一致：`VectorConfig.java:12` 默认值、`risk-warning-knowledge/src/main/resources/application.yml:13`、ES 运行时 mapping。`EsVectorizationUtil.java:168-174` 按 `vectorConfig.getDimension()` 逐条校验，维度不符即计入失败，不写入。故 768 是有强制校验保障的既有契约。

`index: true` 与 `similarity: cosine` 必须显式声明，缺失将导致 kNN 不可用。

术语约定：配置项 `vector.dimension` 指向量维度，索引字段 `dimension` 指业务维度（keyword）。二者同名不同义，文档与代码中不得混用。

### 5.3 版本策略（冻结）

1. `documents/es_mappings.json` 为唯一权威 Mapping 源，必须能够重建出与运行时等价的索引。当前不满足，修正列为 F-10。
2. 任何 Mapping 变更须同时更新该文件与 `test/init_es.py`，并以实测字段覆盖数验证，不接受 HTTP 200 作为验证依据。
3. 索引结构变更采用新索引加别名切换，不在存量索引上原地改字段语义。
4. 向量字段名、维度、`similarity` 三项变更视为破坏性变更，须经接口评审。

## 6. F-05：`industry` → `complianceDomain` 改名策略（冻结）

F-05 由 `p0-05-core-schemas.md:561` 指派至本任务。U-09 决议将 `IndustryEnum` 更名为 `ComplianceDomainEnum`、字段 `industry` 更名为 `complianceDomain`（修复 D-29：名为行业实为合规领域）。

### 6.1 存量实测

| 索引 | `industry` 覆盖 | `complianceDomain` | `compliance_domain` |
| --- | --- | --- | --- |
| `t_indicator` | 1144 / 1144 | 0（字段不存在） | 0（字段不存在） |
| `t_regulation` | 4865 / 4865 | 0（字段不存在） | 0（字段不存在） |

`industry` 为多值数组（`keyword` 类型），共 53 个不同取值。`t_indicator` 出现频次前十：综合类 1144、银行业 149、证券业 137、咨询服务 120、法律服务 118、保险业 100、数据服务 99、互联网 69、软件开发 50、物流仓储 48。

「综合类」覆盖全部 1144 条，其余为具体行业名。这印证 D-29：取值中「银行业」「证券业」「保险业」是**行业**，而「综合类」是**适用范围**标记，两种语义混在同一字段。改名只解决命名与语义的错位，不解决取值本身的混杂，后者须在计划 2 定义取值域时处理。

### 6.2 冻结策略

**实施方式已由 `P0-11` 决议变更：不再采用「并存→回填→移除」，改为随 D-01 一次性切换同窗口完成。** 原并存策略（两字段并存、读取回落加告警、回填校验一致后移除）废止，理由：D-01 已定 camelCase，命名分歧风险消除，一次写入新字段无需维护回落逻辑，避免把 `D-03` 加字段与 `F-05` 回填拖过整个过渡期。

现行策略：

1. D-01 已决议统一 camelCase，新字段名为 `complianceDomain`（`t_indicator` 与 `t_regulation` 同名）。
2. 随一次性切换窗口同批完成 6009 条（1144 + 4865）回填，不设并存期。
3. Milvus 侧 `industry` → `compliance_domain` 须同窗口同步，见 6.3。

依据：`industry` 当前 100% 覆盖且被检索过滤实际使用（`VectorSearchService.java:124-136` 的 `buildFilterExpression`），原地改名会使过滤条件静默失效——与 D-01 中「Jackson 对找不到的字段静默赋 null」同类的失败模式。见 `p0-11-review-record.md` 3.2 与 5.2。

### 6.3 关联影响

`MilvusRepository.java:78` 的 collection schema 含 `dimension` 字段，`:230` / `:268` 的 `withOutFields` 列出 `id`、`es_id`、`name`、`dimension`、`industry`、`region`。

**Milvus 存量已由 `P0-11` 实测**：`indicator_vectors` 与 `regulation_vectors` 两个 collection 均存在；`regulation_vectors` 有数据且 `industry` 字段含真实值（如「征信业,金融业,综合类」）；`indicator_vectors` 未 load，存量条数待迁移前确认。`VectorSearchService.java:128` 以 `industry == "%s"` 精确匹配过滤，因此改名须同步 Milvus collection 结构，否则过滤静默失效——不再视为独立迁移，已并入 `p0-11-review-record.md` 5.2 的同窗口顺序第 5 步。

## 7. 本次未做的修改

本任务限于对账与冻结，未修改任何代码、Mapping 或数据。原因：

- 修正 `es_mappings.json` 会触发 `init_es.py` 的删除重建流程，涉及 8611 条业务文档与 100% 向量覆盖的销毁与重灌，须先备份并经用户确认。
- 统一命名（D-01）须与移除 SNAKE_CASE 同批进行，属跨模块破坏性变更。
- F-09 修正需迁移 2596 条 `t_risk` 存量数据。
- F-05 的 `complianceDomain` 回填须等 D-01 命名决策，否则会再造命名分歧。

上述各项已由 `P0-11` 接口评审决策（见 `p0-11-review-record.md`），但**均未实施**。实施须先完成四索引 ES snapshot 备份，再按该记录 5.2 的窗口顺序执行：PostgreSQL 先改（向后兼容）、ES 按改写后的 mapping 新建索引并迁移存量、Milvus 同步改名、核对通过后移除 `SNAKE_CASE`。

## 8. 验证方式

字段覆盖数经 `_count` 配合 `exists` 查询逐字段实测（nested 字段改用 nested 查询，`exists` 对 nested 路径无效）。运行时 mapping 原文见 `documents/plan0/baseline/p0-09-evidence/mapping-*.json`，`t_risk` 文档样本见同目录 `sample-t_risk.json`。

## 9. 遗留事项

| 编号 | 事项 | 阻塞点 |
| --- | --- | --- |
| D-01 | ~~统一字段命名~~ 已由 `P0-11` 决议：camelCase 一次性切换 | 待实施 |
| D-22 | 写入缺幂等键导致累积 | 依赖计划 1 的 `analysisRunId` |
| F-05 | ~~`complianceDomain` 新字段回填 6009 条~~ 已由 `P0-11` 决议随 D-01 同窗口完成 | 待实施 |
| F-09 | ~~`createAt` 拼写修正~~ 已由 `P0-11` 决议修正并迁移 2596 条 | 待实施 |
| F-10 | ~~`es_mappings.json` 不可重建运行时~~ 已由 `P0-11` 决议：导出运行时 mapping 改写为 camelCase，作为新建索引唯一依据 | 待实施 |
| F-11 | 汇报稿向量字段记载矛盾 | 随 F-08（风险等级口径统一后的文档更正）一并修订 |
| F-12 | ~~Milvus collection 是否有 `industry` 存量数据~~ **已实测确认有**：`regulation_vectors` 的 `industry` 含真实值，`indicator_vectors` 条数待 load 后核对 | 迁移时须同步改名 |
