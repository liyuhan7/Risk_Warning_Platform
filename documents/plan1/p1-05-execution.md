# P1-05 基础执行记录

> 开工日期：2026-09-06；分支：main。范围为 FactExtractionService、生产校验、Behavior 装配与写入、FILE_UPLOAD 主链切换。真实 Provider 和专用 PG/ES/BERT 集成验收尚未执行。

## 已实现的代码

- 新增事实响应、失败项、调用元数据和抽取结果 DTO；生产资源固定为 `fact-extraction-v1.0` Schema 与 Prompt，并用测试校验其内容与 P1-04 冻结文件一致。
- `FactExtractionResponseValidator` 按 L1/L2/L3 校验 JSON 形状、必填字段、类型、枚举、范围、日期、数量单位联动、Evidence 引用、额外系统字段和禁止推断文本。P1-04 的 16 组有效与无效样本已全部移植为 Java 单元测试。
- `FactExtractionService` 校验 Evidence 完整性和作用域，按源文件、页码、段序号和 ID 稳定排序，并按最多 40 条、正文最多 20,000 个 Unicode 字符分批。Provider 传输重试沿用既有实现；模型响应校验失败时重试一次，再失败则携带批号、错误码、响应摘要和调用元数据抛出异常，禁止返回部分成功结果。
- `StructuredBehaviorAssembler` 负责 trim、日期归一化、作用域和版本字段装配，并使用 `sha256(runId|sourceDocumentId|sha256(normalized description))` 前 32 位生成稳定 ID。`Behavior` 与 ES Mapping 增加 `schemaVersion`，部署增量见 `006_behavior_schema_version.json`。
- `StructuredBehaviorWriter` 复用分类和向量服务补齐 `tags/type/dimension/descriptionVector`。分类失败阻断写入；向量失败记录告警并按既有降级语义写入无向量行为。ES 通过仓储适配层构造真实 `BulkRequest`，每条操作显式使用稳定 Behavior ID 作为 `_id`。
- `FactExtractionPipeline` 在同一 run 的全部源文件抽取成功后统一分类并写入。`MessageTask` 的 FILE_UPLOAD 默认路径已从旧 Batch 切换到新链；只有新链成功才发送指标消息，异常则标记 run FAILED。旧 Batch 类和兼容测试保留给 P1-07。
- FILE_UPLOAD 在标记 run FAILED 后结束本次工作任务，不再重新抛出异常。当前异步执行器下原异常不会回传到已返回的 Kafka listener，但会成为工作线程未捕获异常；修复后也能避免未来执行方式变化时对同一失败 run 重投并重复调用 LLM。回归测试直接捕获异步工作线程异常，确保失败终态不再向任务边界传播。

## 验证结果

- P1-05 定向测试通过：Validator、Assembler、FactExtractionService、Pipeline、Writer、ES BulkRequest 和 MessageTask 主链测试全部通过。
- `risk-warning-processing` 及依赖模块跳过测试打包通过。
- 排除依赖本地基础设施的 `ConnectivityTest` 与 `ESSearchTest` 后，processing 模块测试通过。
- processing 全量测试共运行 58 个：0 个断言失败、6 个环境错误、3 个跳过。6 个错误均来自 PostgreSQL `127.0.0.1:5432` 未运行，Spring Batch 无法识别数据库类型；对应 5 个 `ConnectivityTest` 和 1 个 `ESSearchTest`，不属于 P1-05 业务断言失败。

## 尚未完成的验收

- 未提供真实 LLM 凭据，尚未执行真实 Provider 连续调用和真实材料抽取准确性验证。
- 未启动专用 PostgreSQL、Elasticsearch 与 BERT 分类/向量服务，尚未验证“文档 → Evidence → 抽取 → 分类/向量 → Behavior 实际落库”的完整切片，也未执行 `006_behavior_schema_version.json`。
- P1-06 的同 Run 重投、A2 失败保留 A1、A2 成功后清理旧结果三个真实场景仍待复验。因此 PLAN 中 P1-05 保持未勾选。

## 已知限制

Elasticsearch Bulk 不是事务。若 Bulk 响应部分失败，已成功的单项可能留在失败 run 下；主链会把 run 标记 FAILED，当前成功运行过滤会阻止这些文档进入业务查询，后续成功切换会清理非当前 run。专用 ES 验收必须实际查询失败 run 与当前 run，验证隔离及清理，不能只检查 Bulk 返回或 HTTP 状态。

## 评审修订记录

- 2026-09-05 P1-05 评审确认保留 `Behavior.schemaVersion`（推翻 p1-03 计划 §3.3 决策 1）：作为行为级抽取 Schema 标识写入 t_behavior（006 增量、es_mappings 已同步）；p1-03-plan §3.3 与 p1-03-execution 已同步修订。若 P1-07 引入统一来源/版本标识可随之收编。
- 2026-09-05 P1-05 评审修复 D3 失败终态遗漏：`MessageTask` FILE_UPLOAD catch 不再重新抛出（失败即终态、消费端不抛出）；`MessageTaskScopeTest` 改为捕获异步工作线程异常并断言不逃逸（修复前为"Unexpected exception thrown: RuntimeException: 处理失败"）。
