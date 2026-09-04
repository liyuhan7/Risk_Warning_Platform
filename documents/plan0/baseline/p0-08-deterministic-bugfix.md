# P0-08 零风险聚合缺陷的失败测试与修复

对应 PLAN.md `P0-08`、测试方式（PLAN.md:180）与验收标准 8（PLAN.md:172）。修复缺陷 D-09。

## 1. 范围界定

PLAN.md `P0-08` 的表述是「为零风险聚合**等**已确认的确定性 Bug 添加失败测试并修复」。按缺陷清单第五节的分层，第一层（无前置依赖）共四条：

| 缺陷 | 本次是否处理 | 理由 |
| --- | --- | --- |
| D-09 零风险聚合除零 | **是** | `P0-08` 点名项，确定性，无前置依赖 |
| D-21 硬编码凭据 | 否 | 已在 `P0-07` 处理 |
| D-24 测试硬编码路径 | 否 | 见下 |
| D-01 命名策略 | 否 | 已由 `P0-11` 决议：统一 camelCase 一次性切换 |

其余已确认缺陷均不满足「确定性且无前置依赖」：

- D-02、D-06、D-07、D-08 依赖 D-01 的命名决策或计划 2 / 计划 3 的实现，修复方式尚未确定。
- D-10、D-11、D-16 属阈值语义变更，须 `P0-11` 认可阈值表。
- D-30（重复分析销毁旧结果）虽是 P0 且确定性，但其修复要求「写新 → 校验成功 → 删旧」的替换顺序，牵涉 `BehaviorProcessingService` 的事务边界，且依赖 U-07 的 `analysisRunId` 在计划 1 落地。不属计划 0 的「不做实现改造」范围。
- D-22（无幂等键）已实测不影响评分（同指标下多行为取平均，总分逐位相同），不污染 Baseline，故不在 `P0-08` 范围——清单 `:359-360` 已记此判断。

D-24 未处理的原因：`BatchTest` 是 `@SpringBootTest(classes = ProcessingApplication.class)` 的集成测试，除硬编码 macOS 路径外还需要 PostgreSQL、Kafka、Elasticsearch 与 Spring Batch 元数据表齐备。仅替换路径不能使其通过，须先决定该测试是改造为可离线运行的单元测试、还是标注为需要基础设施的集成测试并从默认 `mvn test` 中排除。这是一项独立决策，登记为 F-06 留 `P0-11`。

本次因此只处理 D-09，符合 AGENTS.md「每次只实现一个明确的小任务」。

## 2. 缺陷成因

`RiskLevelEnum.getByRiskCount`（原 `:66-76`）：

```java
double averageWeight = 1 - totalWeight / (lowRiskCount + mediumRiskCount + highRiskCount);
return RiskLevelEnum.getByScoreRatio(averageWeight);
```

三个计数器全为 0 时分母为 0，`totalWeight` 也为 0，`0.0 / 0` 在 IEEE 754 下得 `NaN`。随后 `getByScoreRatio(NaN)`：`NaN >= 0.4` 与 `NaN >= 0.2` 均为 false（NaN 与任何值比较皆为 false），落入 `else` 返回 `HIGH_RISK`。

关键在于这三个计数器的语义。`AssessmentServiceImpl:70-75` 只在 `scoreRatio < THRESHOLD_RATIO`（即**已触发风险**）时才自增：

```java
if(scoreRatio < THRESHOLD_RATIO) {
    RiskLevelEnum riskLevelEnum = RiskLevelEnum.getByScoreRatio(scoreRatio);
    lowRiskCount += riskLevelEnum == RiskLevelEnum.LOW_RISK ? 1 : 0;
    ...
}
```

所以全 0 的含义是「没有任何指标触发风险」，是最好的情况。系统却输出最坏的等级。

根因是 `getByScoreRatio` 的 `else` 分支承担双重语义：既表示「比例低于 0.2」（最差），也兜住了 `NaN`（无数据）。两种语义相反的输入导向同一结果，最好与最坏在此不可区分。

## 3. 修复方式

用户决定：`getByRiskCount(0, 0, 0)` 返回 `LOW_RISK`，`getByScoreRatio(NaN)` 抛 `IllegalArgumentException`。

**零风险返回 `LOW_RISK`**：在加权计算前先判 `totalRiskCount == 0` 并直接返回。选 `LOW_RISK` 而非新增 `NO_RISK` 枚举值，是为保持 DTO / JSON / 数据库字段契约兼容（AGENTS.md 要求）；选 `LOW_RISK` 而非 `null`，是因为 `AssessmentServiceImpl:162`、`:167` 对 `getOverallRiskLevel()` 直接调 `.getDescription()` 与 `.name()` 且无 null 检查，返回 null 会让评估完成通知在 try-catch 中静默失败——等于用一个静默缺陷换掉另一个。

代价是「零风险」与「确有低风险」在返回值上不可区分。语义上零触发确实是可取值中风险最低的一档，可以接受；若后续需要区分，属新增枚举值的契约变更，留 `P0-11`。

**NaN 显式拒绝**：`getByScoreRatio` 入口加 `Double.isNaN` 判断并抛 `IllegalArgumentException`。这消除了 `else` 分支的双重语义，与 `p0-04-riskrule-design.md:62`「不得因未匹配回退到某个默认等级」、`:63`「`scoreRatio` 为 `NaN` 或无法计算时不得参与聚合」一致。

已核实该异常在现有链路上不可能抛出：`getByScoreRatio` 全仓仅一个生产调用点（`AssessmentServiceImpl:72`），位于 `if(scoreRatio < THRESHOLD_RATIO)` 分支内，而 `NaN < 0.5` 为 false，NaN 进不到该行。因此这是一道防止未来误用的守卫，不改变当前运行时行为。

顺带说明 `scoreRatio` 确实可能为 NaN：`AssessmentServiceImpl:67` 在 `maxPossibleScore == 0` 时用 `calculatedScore` 兜底，若两者同时为 0 则 `0.0 / 0.0` 得 NaN。该输入下 `:70` 判断为 false，指标不触发风险、不计入三个计数器——修复后这类评估的总体等级为 `LOW_RISK`，修复前为 `HIGH_RISK`。

未改动：两档阈值 0.4 / 0.2、`highRiskCount >= 5` 与 `mediumRiskCount >= 5` 的短路规则、加权系数 0.2 / 0.4 / 1.0。阈值口径统一属 D-11，需 `P0-11` 认可。

## 4. 测试

新增 `risk-warning-common/src/test/java/com/riskwarning/common/enums/risk/RiskLevelEnumTest.java`，5 个用例：

| 用例 | 断言 |
| --- | --- |
| `shouldReturnLowRiskWhenNoIndicatorTriggersRisk` | `getByRiskCount(0,0,0)` 为 `LOW_RISK` |
| `shouldRejectNaNScoreRatio` | `getByScoreRatio(NaN)` 抛 `IllegalArgumentException` |
| `shouldKeepExistingScoreRatioThresholds` | 1.0 / 0.4 / 0.39 / 0.2 / 0.19 / 0.0 六点的判定结果 |
| `shouldKeepCountShortCircuitRules` | `(0,0,5)` 为 `HIGH_RISK`、`(0,5,0)` 为 `MEDIUM_RISK` |
| `shouldKeepWeightedAggregationForNonZeroCounts` | `(1,0,0)`、`(4,0,0)` 为 `LOW_RISK`；`(0,0,1)`、`(0,0,4)` 为 `HIGH_RISK` |

后三条是回归锁定用例，确保修复只影响边界、不改动既有判定。

**先失败后通过**（PLAN.md:172 要求）：

修复前 `mvn -pl risk-warning-common test`：

```
Tests run: 5, Failures: 2
  RiskLevelEnumTest.shouldRejectNaNScoreRatio:30
    Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.
  RiskLevelEnumTest.shouldReturnLowRiskWhenNoIndicatorTriggersRisk:22
    expected: <LOW_RISK> but was: <HIGH_RISK>
```

两条失败精确对应两个缺陷点，另三条回归用例修复前即通过，说明既有行为在修复前已被锁定。

修复后：

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0   RiskLevelEnumTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0  （含 P0-07 的 10 条）
BUILD SUCCESS
```

`mvn clean package -DskipTests` 九模块全部通过。

## 5. 核查时发现的两项独立问题

均不在本任务范围，登记待处理。

**F-07 `overallRiskLevel` 按 ORDINAL 持久化。** `Assessment.java:36` 的 `overallRiskLevel` 字段没有 `@Enumerated` 注解（`:45` 的注解属于其下的 `status` 字段），Hibernate 默认按枚举序号持久化，与 `documents/schema.sql:134` 的 `overall_risk_level INT` 一致。这意味着调整 `RiskLevelEnum` 常量的声明顺序会静默改变已有数据的含义。若将来新增 `NO_RISK`，插入位置也会影响存量数据。Docker 未运行，未能核对实际库的列定义。建议改为 `@Enumerated(EnumType.STRING)` 并做数据迁移，属契约变更，留 `P0-11`。

**F-08 文档阈值与代码不一致。** `documents/中期汇报_系统实现资料稿.md:942-945` 描述的判定规则是 0.8 / 0.5 / 0.3 四档且含「无风险」，`:953` 提到 `RiskLevelEnum` 含 `CRITICAL`。代码实际是 0.4 / 0.2 两档三值，无「无风险」也无 `CRITICAL`。文档漂移，须在阈值口径统一（D-11）后一并更正。

## 6. 未完成项

- D-24：`BatchTest` 的定位决策（改造为单元测试或排除出默认 `mvn test`），登记 F-06。
- 全仓 `mvn test` 仍会因 D-24 在 Windows 上失败，本次验证范围限于 `risk-warning-common` 模块测试加全模块编译打包。
- 「零风险」与「确有低风险」的区分若确有需要，属新增枚举值的契约变更。
