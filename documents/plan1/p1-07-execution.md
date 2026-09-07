# P1-07 执行记录：新链回归空洞与来源/版本标识收尾

> 执行日期：2026-09-07  
> 依据：[执行计划](p1-07-plan.md)

## 1. 新链回归覆盖

旧 Spring Batch 已删除，F-13 不再以已不存在的 Batch 类作为回归对象。本次按事实抽取新链核查并补充离线测试：

| 关注点 | 覆盖测试 |
| --- | --- |
| L1/L2/L3 响应校验与 16 组样本 | `FactExtractionResponseValidatorTest` |
| 41 条、20,001 字符、空 Evidence、稳定排序、两次校验失败 | `FactExtractionServiceTest` |
| 日期归一化、作用域、稳定 Behavior ID | `StructuredBehaviorAssemblerTest` |
| 分类成功、向量失败降级、分类空响应阻断、分类数量不一致阻断 | `StructuredBehaviorWriterTest` |
| 全部文件抽取成功才写入、失败不写入 | `FactExtractionPipelineTest` |
| Evidence、源文件作用域与 FILE_UPLOAD 成功/失败门禁 | `EvidenceExtractionServiceTest`、`SourceDocumentScopeValidatorTest`、`MessageTaskScopeTest` |
| 显式 ES `_id`、作用域查询与随机索引清理 | `BehaviorScopeElasticsearchIT` |

本次在 `StructuredBehaviorWriterTest` 新增“分类空响应”和“分类结果数量不一致”两项测试。两种情况均断言不调用向量服务、也不写入 ES。

## 2. 来源与版本契约

静态核对结果如下：

- `Behavior` 有 `schemaVersion` 字段，`t_behavior` mapping 与 006 增量均声明为 `keyword`。
- `StructuredBehaviorAssembler` 固定写入 `schemaVersion="1.0"`，`StructuredBehaviorWriter` 不覆盖该字段，仓储按完整 Behavior 写入 ES。
- `StructuredBehaviorAssembler` 固定写入 `extractionPromptVersion="fact-extract-v1.0"`，并写入 Provider 的 `extractionModel`。

因此，结构契约版本是 `schemaVersion="1.0"`，抽取来源版本是 `extractionPromptVersion="fact-extract-v1.0"`。旧 ES Behavior 缺少这两个字段。P1-07 计划和 PLAN 中将 `fact-extract-v1.0` 直接写成 `schemaVersion` 的表述已据此更正；没有修改既有 ES 数据或 Java 版本常量。

## 3. 旧 Batch 残留核查

`risk-warning-processing` 的生产代码、测试、依赖、配置与脚本中未发现旧 Batch 类、`JobLauncher` 或 `spring-batch` 残留。当前对接说明已去除将旧链描述为未来兼容入口的文字。

计划、基线和中期汇报中的旧 Batch 内容保留为历史设计或历史实测记录，不作为当前生产链说明。未改动 Nacos 的外部配置。

## 4. 分类模型说明

分类 checkpoint 的获取、缺失时的实际行为和测试 Stub 约定见 [risk-warning-bert README](../../risk-warning-bert/README.md)。D-25 仍未关闭：本次只补充可复现说明，不实现私有模型分发。

## 5. 验证

```powershell
mvn -q -pl risk-warning-processing -am test '-Dtest=StructuredBehaviorWriterTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

结果：10 个离线测试类共 31 项通过，0 failure，0 error，0 skip。该组只使用 Mock，不依赖 checkpoint、LLM、PG、ES 或 Kafka。
