# P0-02 Baseline：旧链评估结果实测记录

> 采集日期：2026-08-27　采集人：B
> 结论：**系统当前没有实现「按指标规则判定合规性」这件事。** 旧链能端到端跑通并产出分数，但分数不具备区分合规与违规的能力。

## 1. 采集条件

| 项 | 值 |
|---|---|
| 输入 | `test/fixtures/p0/CASE-00{1,2,3}/source.docx`（已冻结，SHA-256 见 test-cases.md） |
| Project / Assessment | 3/32、4/33、5/34 |
| 基础设施 | 容器化 ES 8.11.0(:9200)、PostgreSQL 13(:5432)、Kafka、Redis、bert-service(:8000) |
| Java 服务 | 本机运行：org:8095、knowledge:8089、processing:8084、bert:8090 |
| 采集前置 | 三案例 `t_behavior` 清零（前次 27/9/7 含重复），本轮为 9/9/7 |

`t_behavior`、`t_indicator`、`t_regulation` 三索引文档数：2121 / 1144 / 4865。

## 2. 实测结果

| 项 | CASE-001 期望 | CASE-001 实测 | CASE-002 期望 | CASE-002 实测 | CASE-003 期望 | CASE-003 实测 |
|---|---|---|---|---|---|---|
| 整体结论 | NON_COMPLIANT | 32.336 / 等级 2 | COMPLIANT | 38.769 / 等级 1 | INSUFFICIENT_EVIDENCE | 35.846 / 等级 1 |
| Behavior 数 | 9 | 9 | 9 | 9 | 7 | 7 |
| IndicatorResult 数 | 8 | 36 | 8 | 40 | 6 | 29 |
| 命中金标准指标 | 8 | **0** | 8 | **0** | 6 | **0** |
| 三级指标数 | 8 | **0** | 8 | **0** | 6 | **0** |
| 风险触发数 | 8 | 34 | 0 | 33 | 不得判定 | 29 |

三案例跨 80 个实测指标，与 8 个金标准指标的交集为 **1**（`e43ca387` CCC 认证，且落在错误案例中）。

### 2.1 违规与合规方向相反

CASE-001（八项全违规）总分 32.336，CASE-002（八项全合规，与 CASE-001 同指标镜像对照）总分 38.769。合规案例得分更高本身符合直觉，但：

- 分差仅 6.43 分，而两案例事实完全相反、期望得分为 0.0 全项 vs 满分全项；
- 风险等级方向相反：违规案例等级 2，合规案例等级 1；
- CASE-003 材料故意不含可判定证据，却 29/29 全部触发风险。

### 2.2 结果与输入行为数量无关

清理前 assessment 29 基于 27 条重复行为（9 段 × 3 次重传），清理后 assessment 32 基于 9 条行为：

```
backup assessment 29 rows=36
new    assessment 32 rows=36
same indicator id set: True
score diffs=0
```

36 个指标 ID 与 36 个分数逐位相同，总分同为 `32.3364626851702`。原因是同一指标下多行为取平均（`BehaviorProcessingService.java:412`），复制输入不改变均值。

这条事实的意义：**系统未对违规事实做累计聚合**，只做相似度平均。同时说明全部缺陷是确定性的、与数据量无关，Baseline 可重复复现。

## 3. 根因

### 缺陷 1（阻断性）：ES 反序列化字段名不匹配，指标元数据全部丢失

`ElasticSearchConfig.java:61` 为 ES client 的 ObjectMapper 设置了 `PropertyNamingStrategy.SNAKE_CASE`，于是 `Indicator.indicatorLevel` 按 `indicator_level` 反查，而 `t_indicator` 实际文档使用 camelCase：

```
docs having snake_case indicator_level: 0
docs having camelCase indicatorLevel:   1144
  max_score          docs=0      maxScore          docs=1144
  risk_rule          docs=0      riskRule          docs=821
  calculation_rule   docs=0      calculationRule   docs=821
```

索引 mapping 里两套命名同时存在，但只有 camelCase 一套有数据。后果是 `Indicator` 反序列化后 `indicatorLevel`、`maxScore`、`calculationRule`、`riskRule` 全为 null，于是：

- `batchSaveIndicatorResults:427` 的三元回退使 `indicator_level` 落库恒为 0；
- `:390` 的 `maxScore > 0` 判断失败，`max_possible_score` 恒为默认 100；
- `calculationRule` / `riskRule` 为 null，规则即便有执行代码也无数据可用。

实测印证：105 条结果的 `indicator_level` 全为 0、`used_calculation_rule_type` 全为 `auto`、`type` 全为空串、`matched_behaviors_ids` 全为 NULL、`max_possible_score` 每案例仅 1 个取值。

需要澄清：kNN **确实召回了三级指标**。复现召回（k=6，num_candidates=200，无过滤）显示 9 个行为中 8 个的 Top6 全部是 `indicatorLevel=3`、`maxScore` 在 0.3~2.0 之间。落库为 0 与 100 完全由反序列化丢失造成，不是召回层选错了层级。

### 缺陷 2：召回选不中目标指标

`fetchTopIndicators:892` 是纯向量 kNN，无 industry、无 level、无维度过滤：

```java
.index(ElasticSearchConfig.INDICATOR_INDEX)
.size(candidateSize)                                  // 调用处 line 228 传入 6
.knn(k -> k.field("name_vector")
           .queryVector(behavior.getDescriptionVector())
           .k(candidateSize)
           .numCandidates(CANDIDATE_FETCH_SIZE))      // 200
```

用行为描述向量比对指标**名称**向量，1144 条指标全量竞争，每行为只取 6 条。实测金标准指标在全库排名：

| 行为（首 20 字） | 对应金标准指标排名 | Top1 实际召回 |
|---|---:|---|
| 生产基地尚未取得排污许可证 | 194 / 1144 | 针对监管部门整改要求，是否已按时通过验收？ |
| 铅含量超出欧盟 RoHS 限值 | 228 | 对照《企业可持续发展尽职调查指令》…尽职调查是否达标 |
| 人均加班时长达 58 小时 | 208 | 针对监管部门整改要求，是否已按时通过验收？ |
| 未按法定社保缴费基数申报缴费 | 161 | 企业在参与政府项目招投标时，是否存在串通投标… |
| 无法提供 CE 认证技术文件 | 66 | 产品设计是否符合东盟进口产品统一标准要求 |
| 数据出境未做安全评估 | 65 | 企业将数据存储在境外时，是否获得相关监管部门审批 |
| 未设置不合格品处理程序 | 279 | 检查企业开展的有奖销售活动… |
| 未加贴 CCC 认证标志 | 135 | 检查企业开展的有奖销售活动… |

最好情况排名 65，Top6 无法触及。相似度分布极为扁平（0.83~0.91），语义区分度不足：Top1 与第 6 名的差距普遍在 0.01 以内。

### 缺陷 3：评分唯一的合规性输入恒为空，定性分恒为 0.5

评分链路：

```
computeRegulationScore (:760)
  → QualitativeCalculator.computeQualitativeScore(reg.getDirection(), behavior.getStatus())
```

实测 `t_behavior` 全部 9 条：`status=''`、`quantitative_data=0.0`。`normalizeStatus("")` 落到 `QualitativeCalculator.java:129` 返回 `"UNKNOWN"`，三个分支函数一律走 `default: return 0.5`。

于是每条法规的定性分恒为 0.5，与行为是否违规完全无关。最终分数只剩权重差异：

```java
double weight = (rs.hierarchyWeight + rs.timelinessWeight + rs.similarityWeight) / 3.0;  // :738
```

**CASE-001 与 CASE-002 的 6.43 分差来自向量相似度噪声，不是合规性差异。**

兜底路径同样失效：`getFallback:783` 依赖 `behavior.getQuantitativeData()`，恒为 0.0，当 `maxScore=2.0` 时算出 `1 - |2-0|/2 = 0.0`。

### 缺陷 4：风险触发不由指标规则决定

全仓库 processing 模块搜索 `staticThreshold`、`adjustmentFactor`、`RiskRule` 零命中。`riskTriggered` 仅出现两处，均硬编码 `false`（`:436`、`:557`），`riskStatus` 恒为 `NOT_EVALUATED`。

数据库中 34/33/29 条 `risk_triggered=true`、`risk_status='已评估'`，只能由下游模块经 `IndicatorResultRepository:59` 的自定义 UPDATE 按分数阈值回写。金标准期望的 BINARY 规则（`operator:"<"`、`thresholdValue:0.5`、`riskLevel:"MEDIUM_RISK"`、`requiredLowCount:2`）在当前代码中**没有任何执行路径**。这解释了 CASE-003 全项触发风险。

### 缺陷 5：法规重复挂载，引用信息为空

`:685` 仅在 `relatedBehaviors.isEmpty()` 时新建条目，否则把法规全部塞进 `get(0)`。外层是「每法规 × 每指标」双循环，导致 6 个候选指标共享同一批 10 条召回法规。实测 `calculation_details` 中，指标「企业是否存在无正当理由拒绝与交易相对人进行交易的情况」的 `relatedBehaviors` 挂着「数据出境安全评估」这条行为，`relatedRegulations` 含「反垄断法-经营者集中申报」「GDPR-数据泄露通知时效」「研发投入占营收比例…」等与行为语义无关的条目，且各指标之间完全相同。

`regulationId` 全为 null（`regulation.json` 4865 条均无 id 字段），`violationType` 与 `complianceRequirement` 全为空串（`:695-696`、`:707-708` 硬编码，带 TODO）。

## 4. 结论

旧链实际执行的是：

```
行为向量 → kNN Top6 相似指标 → 法规方向 × 行为状态查表 → 加权平均 → × maxPossible
```

其中「行为状态」这一唯一的合规性输入恒为空，`indicatorLevel` / `maxScore` / `calculationRule` / `riskRule` 因命名策略不匹配全部为 null，风险判定不读指标规则。因此：

1. 分数无法区分合规与违规，方向可以任意；
2. 「证据不足」这一状态无法表达，系统必然给出确定结论；
3. 指标的 BINARY / RANGE 规则从未被执行；
4. 结果对输入行为数量不敏感，不做违规事实累计。

**旧链具备工程链路，不具备判定能力。** 这是计划 1—3 必须重建事实抽取、检索过滤与规则引擎的实测依据。

## 5. 修复依赖顺序

1. `ElasticSearchConfig` 命名策略与索引字段契约对齐（缺陷 1）。这是前置项：不修则 `maxScore` / `riskRule` 恒为 null，后续任何规则实现都拿不到数据。须同时决定两套命名字段的取舍，纳入 P0-09。
2. 填充 `behavior.status` 与 `quantitativeData`（缺陷 3）。这是评分的唯一输入源，不修则评分改动无法验证。
3. 改造召回：按维度或指标层级分层检索，提高候选覆盖（缺陷 2）。
4. 实现 `calculationRule` / `riskRule` 执行与 `riskTriggered` 判定（缺陷 4）。
5. 修正法规与行为的挂载关系，补齐引用字段（缺陷 5）。

## 6. 复现方式

```powershell
# 1. 确认输入未被改动
python -X utf8 test\fixtures\p0\generate_fixtures.py   # 幂等，校验 SHA-256

# 2. 采集前清零目标 project 的行为，避免重复上传污染
#    （删除属不可逆操作，须先备份，参见 test-cases.md 第 6 节）

# 3. 经前端上传三份 source.docx 至 Project 3/4/5 并触发评估

# 4. 导出结果
docker exec postgres-risk-warning psql -U postgres -d risk_warning_platform \
  -c "SELECT id, project_id, status, overall_score, overall_risk_level FROM t_assessment_result WHERE project_id IN (3,4,5);"
```

Baseline 数据备份：`test/fixtures/p0/_backup_20260827_152505/`（首轮 assessment 18/19/20/27-31 的 ES 行为 43 条、PG assessment 8 条、indicator_result 105 条）。
