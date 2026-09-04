# RiskRule 分组阈值设计

> 设计日期：2026-08-27
> 仓库基线：`main`，提交 `a851e09`
> 数据依据：`documents/plan0/baseline/p0-04-rule-stats.md`，ES `t_indicator` 821 条有规则指标
> 定位：`P0-05` Schema 冻结与 `P3-06` Static Risk Threshold 的设计输入
> 实施约定：**本阶段只冻结设计，不修改 ES 数据。** 计划 3 落地时使用新字段或版本化写入，保留现有 `riskRule` 原值以维持 Baseline 可对照（PLAN.md:114）

## 1. 现状缺陷（已量化）

821 条 `riskRule` 去重后只有 1 种，全部为绝对分数阈值 `score < 0.5 → MEDIUM_RISK`。而指标级 `maxScore` 跨度为 0.3 至 2.0，同一绝对阈值施加于不同满分的指标，产生的不是「缺乏区分度」而是**判定错误**。

| 情形 | 指标数 | 后果 |
| --- | --- | --- |
| `maxScore < 0.5` | **89** | 即使得满分也 `< 0.5`，**恒定触发风险**，无论合规与否 |
| `maxScore == 0.5` | 270 | 仅得满分才不触发，得分低于满分即触发 |
| `maxScore >= 1.0` | 220 | 得分不足满分 50% 仍可能不触发 |

89 条恒触发指标的构成：`maxScore=0.4` 共 64 条，`maxScore=0.3` 共 25 条。集中于两个维度：企业国际合作风险 60 条、劳务合规风险 29 条。

这解释了 `p0-02-baseline.md` 记录的风险触发数异常（34/36、33/40、29/29）——触发比例过高且与案例合规性无关，部分来自阈值与满分量纲不匹配，而非评分链的语义判断。

### 1.1 代码中已存在冲突的第二套比例语义

`RiskLevelEnum.getByScoreRatio`（`RiskLevelEnum.java:56-64`）已按比例判定：

```java
if (scoreRatio >= 0.4) return LOW_RISK;
else if (scoreRatio >= 0.2) return MEDIUM_RISK;
else return HIGH_RISK;
```

即代码里已有比例口径（0.4 / 0.2 两档），与 `riskRule` 中的绝对值 0.5 并存且互不一致。当前主链未执行 `riskRule`，两者尚未冲突；一旦 `P3-06` 让 Rule Engine 执行 `riskRule`，两套口径必须先统一，否则同一指标会得到两个风险等级。

### 1.2 附带发现：零风险聚合除零

`RiskLevelEnum.getByRiskCount`（`:66-76`）在 `lowRiskCount / mediumRiskCount / highRiskCount` 全为 0 时，`:74` 的除法分母为 0，得到 `NaN`，随后 `getByScoreRatio(NaN)` 所有比较为 false 而返回 `HIGH_RISK`——即**零风险集合被判为高风险**。这正是 `P0-08` 点名待修的「零风险聚合」缺陷，此处记录以便该任务直接定位。本文不修改该方法。

## 2. 阈值语义定义（冻结）

### 2.1 从绝对分数改为得分比例

```text
scoreRatio = score / maxScore
```

- `score`：Rule Engine 依 `CalculationRule` 算出的指标得分
- `maxScore`：指标级 `maxScore` 字段，非 `RangeRule.maxScore`
- 取值域：`[0, 1]`。`score` 超出 `maxScore` 时截断为 1，`maxScore` 为 0 或空时不参与风险判定并标记 `NEEDS_REVIEW`（不得按 0 处理，否则重现恒触发缺陷）

比例语义使阈值与满分解耦，89 条恒触发指标随之消解。

### 2.2 判定方向

`scoreRatio` 越低风险越高。反向计分指标（`trueScore < falseScore`，共 **72** 条，横跨 9 种评分组合）已把方向编码进 `CalculationRule` 的评分本身，因此 `scoreRatio` 方向与常规指标一致，阈值表可直接复用，无需为其单独设阈值。

需要注意的是 Rule Engine 实现层不得假设 `trueScore > falseScore`，这是 `P3-04` 的实现约束。

### 2.3 边界规则

- 一律使用左闭右开区间，判定用严格小于 `<`
- 不落入任何风险区间时为无风险，不得因「未匹配」回退到某个默认等级
- `scoreRatio` 为 `NaN` 或无法计算时输出 `NEEDS_REVIEW`，不得参与聚合

## 3. 分组阈值表

分组依据只能用 `dimension`。`type` 字段 821 条全部为空串，按指标类型分组不可行。

| 维度 | 指标数 | 高风险 | 中风险 | 低风险 | 分档理由 |
| --- | --- | --- | --- | --- | --- |
| 产品合规风险 | 91 | < 0.40 | < 0.65 | < 0.85 | 涉产品安全与消费者权益，法定强制性强，容忍度最低 |
| 劳务合规风险 | 150 | < 0.40 | < 0.65 | < 0.85 | 涉劳动者法定权益，违规即有直接法律后果 |
| 企业国际合作风险 | 184 | < 0.35 | < 0.60 | < 0.80 | 制裁与出口管制，后果不可逆且可能触发域外处罚 |
| 供应链风险 | 114 | < 0.30 | < 0.55 | < 0.75 | 传导性风险，多为间接责任，容忍度居中 |
| 企业关联方风险 | 119 | < 0.30 | < 0.55 | < 0.75 | 治理结构问题，多数可通过整改消除 |
| 企业信用风险 | 163 | < 0.25 | < 0.50 | < 0.70 | 结果性指标，滞后反映既有问题，避免重复计入 |

前三个维度收紧的依据是对应法定强制义务，违规即产生直接法律后果；后三个偏治理与经营风险，留更多整改空间。

各维度实际 `maxScore` 区间，供实施时核对比例换算：

| 维度 | `maxScore` 最小 | 最大 |
| --- | --- | --- |
| 产品合规风险 | 1.0 | 2.0 |
| 企业信用风险 | 0.6 | 0.7 |
| 企业关联方风险 | 0.5 | 2.0 |
| 企业国际合作风险 | 0.3 | 0.5 |
| 供应链风险 | 0.5 | 1.0 |
| 劳务合规风险 | 0.3 | 1.2 |

企业国际合作风险的 `maxScore` 上限仅 0.5，是 89 条恒触发指标中占 60 条的原因。

## 4. RiskRule 结构变更建议

### 4.1 现有结构无法表达三档

`StaticThreshold`（`StaticThreshold.java:12-15`）的 `riskLevel` 是单值 `RiskLevelEnum`，一个 `StaticThreshold` 只能表达一个等级。`RiskRule.staticThreshold`（`RiskRule.java:13`）又是单对象而非列表，因此现结构最多表达「一个阈值、一个等级」，无法承载三档。

### 4.2 建议结构

将单对象改为有序阈值列表，并显式声明比例语义：

```json
{
  "description": "劳务合规风险维度风险规则",
  "thresholdMode": "SCORE_RATIO",
  "ruleVersion": "v2",
  "thresholds": [
    { "operator": "<", "thresholdValue": 0.40, "riskLevel": "HIGH_RISK" },
    { "operator": "<", "thresholdValue": 0.65, "riskLevel": "MEDIUM_RISK" },
    { "operator": "<", "thresholdValue": 0.85, "riskLevel": "LOW_RISK" }
  ],
  "adjustmentFactor": {
    "enabled": true,
    "requiredLowCount": 2,
    "escalateBy": 1
  }
}
```

要点：

- `thresholdMode` 显式区分 `SCORE_RATIO` 与旧的 `ABSOLUTE_SCORE`，使新旧规则可共存并对照，避免静默改变语义
- `thresholds` 按 `thresholdValue` 升序求值，命中即停；全部不命中为无风险
- `ruleVersion` 满足 PLAN.md:45 的规则版本可复算要求
- `AdjustmentFactor.riskLevelIncrease`（`AdjustmentFactor.java:16`）现为固定 `HIGH_RISK`，建议改为 `escalateBy` 相对升级档数，避免中低风险直接跳到高风险

### 4.3 兼容策略

新字段追加，不改动现有 `staticThreshold` 与 `adjustmentFactor` 的 JSON 语义。Rule Engine 按 `thresholdMode` 分派：缺失时按 `ABSOLUTE_SCORE` 走旧逻辑，保证旧数据可读。ES 写入使用新字段或版本化索引，不覆盖原值。

## 5. 对 P3-06 验收口径的影响

`P3-06` 原表述为「实现 Static Risk Threshold，替换 Report 统一 0.5 阈值的决策职责」。若沿用现有 821 条同质 RiskRule，替换后判定结果与 Report 的 `scoreRatio < 0.5` 等价，任务只完成了决策位置迁移，未产生行为差异。

采纳本设计后，`P3-06` 的验收应增加两条可验证项：

1. 同一 `scoreRatio` 在不同 `dimension` 下得到不同风险等级，证明分组生效
2. `maxScore < 0.5` 的 89 条指标在得满分时不再触发风险，证明比例语义修正了恒触发缺陷

第 2 条可直接用三案例复算验证：改造前后对比 `p0-02-baseline.md` 记录的风险触发数（34/36、33/40、29/29），触发数应显著下降且合规案例低于违规案例。

## 6. 未决项

| 事项 | 需决策方 | 最晚决策 |
| --- | --- | --- |
| 六维度阈值具体数值是否认可（本表为设计建议，非实测标定） | 全员（`P0-11`） | 计划 3 启动前 |
| `RiskLevelEnum.getByScoreRatio` 的 0.4/0.2 两档与本表如何统一 | A、B（`P0-05`） | 计划 3 启动前 |
| `RiskRule` 结构变更是否纳入五个核心 Schema 冻结范围 | A、B（`P0-05`） | 计划 1 启动前 |
| `escalateBy` 的升级上限与多维度叠加规则 | B | `P3-07` 实施时 |

阈值数值来自维度性质的定性判断，未经数据标定。计划 5 的对照实验应回头校准这组数值，届时以固定测试集的实际分数分布为依据。
