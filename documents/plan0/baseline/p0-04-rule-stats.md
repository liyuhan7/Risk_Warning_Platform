# P0-04 Indicator 规则统计

> 统计日期：2026-08-27
> 仓库基线：`main`，提交 `a851e09`
> 数据源：ES `t_indicator`（`localhost:9200`），1144 条；与 `documents/data/indicator.json` 条数一致
> 复现命令：`py -3.9 test/fixtures/p0/indicator_rule_stats.py`
> 抽样命令：`py -3.9 test/fixtures/p0/indicator_rule_stats.py --dump-samples`

本文所有数字由脚本输出，不含估计值。字段一律按 camelCase 读取，与 `p0-02-baseline.md` 根因 1 的实测结论一致。

## 1. 总量与层级分布

| 层级 | 数量 | 占比 |
| --- | --- | --- |
| level 1 | 53 | 4.63% |
| level 2 | 270 | 23.60% |
| level 3 | 821 | 71.77% |
| 合计 | 1144 | 100% |

## 2. CalculationRule

| 项 | 数量 | 占比 |
| --- | --- | --- |
| 有 `calculationRule` | 821 | 71.77% |
| 无 `calculationRule` | 323 | 28.23% |
| 其中 `ruleType=BINARY` | 711 | 62.15% |
| 其中 `ruleType=RANGE` | 110 | 9.62% |

规则**全部挂在 level 3**，且 level 3 指标数（821）与有规则的指标数（821）完全相等。level 1（53）与 level 2（270）合计 323，恰为无规则数。所以规则覆盖与层级是严格对应关系：三级指标全部有规则，一二级指标全部没有。

结构完整性检查无缺口：`ruleType` 无空值、无未知值，`binaryRule.condition` 空值 0 条，`ruleType` 与子对象类型 821 条全部匹配。

## 3. RiskRule

| 项 | 数量 | 占比 |
| --- | --- | --- |
| 有 `riskRule` | 821 | 71.77% |
| 无 `riskRule` | 323 | 28.23% |
| `staticThreshold` 字段完整 | 821 | 71.77% |

与 CalculationRule 完全同集合：821 条两者兼有，0 条只有其中一个。

## 4. 按结构判定的可执行性

| 分类 | CalculationRule | RiskRule |
| --- | --- | --- |
| EXECUTABLE | 821 | 821 |
| NEEDS_FIX | 0 | 0 |
| MISSING | 323 | 323 |

判定口径：`ruleType` 与子对象匹配，且 `BinaryRule` 的 `condition`/`trueScore`/`falseScore`、`RangeRule` 的 `minValue`/`maxValue`/`calculationMethod` 加至少一套评分字段、`StaticThreshold` 的 `operator`/`thresholdValue`/`riskLevel` 全部非空。

**但这个结论只说明字段齐备，不等于计划 3 可以自动执行。** 下面两节是真正的障碍。

## 5. RANGE 规则依赖 JavaScript 表达式求值

`RangeRule.calculationMethod`（`RangeRule.java:14`）存的是 JavaScript 表达式字符串，不是可直接映射到 Java 8 的结构化阈值。110 条 RANGE 规则去重后有 **98 种不同表达式**，几乎一条一式。

| 语法特性 | 涉及规则数 |
| --- | --- |
| 引用 `x` 变量 | 110 |
| 三元表达式 `?:` | 93 |
| 含括号嵌套 | 39 |
| 引用 `maxScore` 变量 | 9 |
| JS 严格相等 `===` | 5 |

实际样本：

```text
x === 0 ? 0.5 : (1 - x) * 0.5
x >= 90 ? 1.0 : x >= 70 ? 0.8 : x >= 50 ? 0.6 : x >= 30 ? 0.4 : 0.2
x >= 95 ? maxScore : x >= 85 ? maxScore * 0.9 : x >= 75 ? maxScore * 0.8 : x >= 60 ? maxScore * 0.7 : 0
x >= 0 ? (x >= 50 ? 0.5 : (x >= 0 ? x / 100 * 0.5 : 0.25)) : (x <= -50 ? 0 : (x < 0 ? 0.5 + x / 100 * 0.5 : 0))
```

三点后果：

1. 表达式含 `maxScore` 变量的 9 条，求值时需注入指标级 `maxScore`，不是纯 `x` 单变量函数。
2. 最后一条样本处理负值输入，说明部分规则的取值域超出 `minValue=0.0` 的声明范围，`minValue`/`maxValue` 与表达式实际定义域不一致。
3. Java 8 的 Nashorn 可执行这些表达式，但从 ES 读取字符串直接求值等于执行外部数据中的代码，属于注入面。计划 3 若要自动执行 RANGE，需先决定是求值还是改为结构化阈值表。

`minValue`/`maxValue` 组合以 `0.0—100.0`（49 条）和 `0.0—1.0`（20 条）为主，两种量纲混用，单位语义未在数据中声明。

## 6. RiskRule 内容完全同质

821 条 RiskRule 去重后**只有 1 种**，全部为：

```json
{
  "description": "风险生成规则",
  "staticThreshold": { "operator": "<", "thresholdValue": 0.5, "riskLevel": "MEDIUM_RISK" },
  "adjustmentFactor": { "enabled": true, "requiredLowCount": 2, "riskLevelIncrease": "HIGH_RISK" }
}
```

`staticThreshold` 去重同样只有 1 种：`('<', 0.5, 'MEDIUM_RISK')`。

这说明 RiskRule 不携带任何指标特异信息，是模板批量填充的产物。字段结构可执行，但执行它等价于对所有指标施加同一条「得分 < 0.5 判中风险」的规则——与 `AssessmentServiceImpl` 现有的统一 `scoreRatio < 0.5` 阈值（PLAN.md:501）在效果上没有区别。

对计划 3 `P3-06`（用 StaticThreshold 替换 Report 统一 0.5 阈值）的直接影响：**替换后行为不变**。这条任务的价值在于把决策位置从 Report 移到 Rule Engine，而非改变判定结果。需要在计划 3 前明确 RiskRule 是否需要重新按指标设计，否则这次迁移只是搬家。

相比之下 BinaryRule 有实际差异：评分组合去重 20 种，`trueScore=0.5/0.6/1.0` 分布较散。其中 **72 条为反向计分**（`trueScore < falseScore`，条件成立反而扣分），横跨 9 种组合：`(0.0, 0.5)` 27 条、`(0.0, 1.0)` 11 条、`(0.0, 0.6)` 9 条、`(0.0, 0.4)` 9 条、`(0.0, 2.0)` 6 条、`(0.0, 0.8)` 3 条、`(0.0, 0.3)` 3 条、`(0.0, 0.7)` 2 条、`(0.0, 0.9)` 2 条。规则引擎不能假设 `trueScore > falseScore`。

按维度分布：企业国际合作风险 20 条、劳务合规风险 18 条、企业关联方风险 17 条、供应链风险 9 条、企业信用风险 7 条、产品合规风险 1 条。

## 6.1 由本节引出的设计文档

RiskRule 同质化的处置方案（按维度分组的比例阈值）见 `documents/plan0/baseline/p0-04-riskrule-design.md`。该文档另量化了一项本节未覆盖的缺陷：`maxScore < 0.5` 的 89 条指标在现行绝对阈值下**得满分仍恒定触发风险**。

## 7. 与旧链实测的交叉印证

`p0-02-baseline.md` 记录三案例命中金标准指标 0/8、0/8、0/6，三级指标数为 0。本次统计给出结构性解释：

- 规则 821 条全部在 level 3，而 level 3 指标因 `p0-02-baseline.md` 根因 1 的反序列化丢失，`indicatorLevel` 落库为 0、`calculationRule`/`riskRule` 为 null。
- 也就是说，**唯一携带规则的那一层指标，恰好是反序列化失效影响的那一层**。修复命名策略（根因 1）后，规则引擎才可能拿到这 821 条。

## 8. 对后续任务的输入

| 结论 | 影响任务 |
| --- | --- |
| 规则与 level 3 严格对应，一二级无规则 | 计划 3 的规则引擎只对三级指标生效；一二级指标的分数须由子指标聚合而非规则计算 |
| RANGE 依赖 98 种 JS 表达式，9 条引用 `maxScore` | `P3-05` 需先决策求值方案（Nashorn 求值 / 转结构化阈值 / 只支持子集） |
| RiskRule 只有 1 种，等价于统一 0.5 阈值 | `P3-06` 迁移后行为不变；处置方案见 `p0-04-riskrule-design.md` |
| 绝对阈值下 89 条指标得满分仍恒触发风险 | `P3-06` 须改用 `score / maxScore` 比例语义 |
| BinaryRule 有 72 条反向计分，横跨 9 种组合 | `P3-04` 不得假设 `trueScore > falseScore` |
| `minValue`/`maxValue` 量纲混用且与表达式定义域不一致 | `P0-05` 的 Schema 需为定量值补单位字段 |
| 规则字段只在 camelCase 下可读 | `P0-09` 的命名取舍：改 ES 数据还是改 Java 命名策略 |

## 9. 未决项

| 事项 | 需决策方 | 最晚决策 |
| --- | --- | --- |
| RANGE 表达式的执行方案（求值 / 转结构化 / 支持子集） | A、B | 计划 3 启动前 |
| RiskRule 是否需按指标重新设计 | 全员（`P0-11`） | 计划 3 启动前 |
| 定量值单位与量纲的声明方式 | A、B（`P0-05`） | 计划 1 启动前 |
