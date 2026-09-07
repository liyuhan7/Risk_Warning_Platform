# P1-07 执行计划（重定义：新链回归空洞与来源/版本标识收尾）

> 拟定日期：2026-09-07；执行前由用户评审确认。
> 依据：[PLAN](../PLAN.md)（§8 P1-07、§10 验收 8）、[P1 对接说明](p1-handoff.md)（§4 兼容链段落及 2026-09-06 修订）、[defect-backlog](../plan0/baseline/defect-backlog.md)（F-13、D-24、D-25）、P1-05/06 执行记录。
> 前序：P1-01~06 已全部完成并通过真实环境切片验证（2026-09-06）。

## 1. 重定义理由

原 P1-07 为「保留旧 Batch 兼容入口，明确新旧来源/版本；补 F-13 的 LineProcessorTest、LineRangeItemWriterTest、BatchJobScopeTest」。

该任务的前提已失效：提交 `1a5e537` 已整体移除旧 Spring Batch 链路，事实抽取新链成为 FILE_UPLOAD 唯一生产路径。经静态核查：

- `risk-warning-processing` 生产与测试代码中不再存在 `LineProcessor`、`LineRangeItemWriter`、`BatchJob`、`JobLauncher`、`spring-batch` 依赖或 `batch` 包目录。
- 旧链「来源/版本区分」职责由 Behavior 的结构契约 `schemaVersion="1.0"` 与抽取来源 `extractionPromptVersion="fact-extract-v1.0"` 共同承担（P1-05 落地，p1-03 计划 §3.3 决策已同步推翻）。
- F-13 的回归空洞对象（被删除的 `BatchTest` 及旧 Batch 类）已不存在，需要以新链测试覆盖替代。

因此 P1-07 重定义为：**核查并补足事实抽取新链的测试覆盖，确认来源/版本标识契约，清理残留旧 Batch 引用，说明分类服务模型获取方式**。

## 2. 目标与验收口径

完成后应满足（对应 F-13 方向 / PLAN §10 验收 8 / D-25 说明要求）：

1. 新链（Evidence → FactExtraction → 装配 → 分类/向量 → ES 写入 → 消息门禁）测试覆盖核查完毕，缺口已补测试；不依赖私有模型、完整 ES/Kafka/PG 环境即可运行（外部依赖测试沿用现有显式跳过纪律）。
2. 来源/版本标识确认：Behavior 模型、ES Mapping、Assembler 装配、Writer 写入四者对 `schemaVersion="1.0"` 一致；`extractionPromptVersion="fact-extract-v1.0"` 标识抽取来源版本；PLAN 与执行记录已记录该契约。
3. 仓库内无残留旧 Batch 引用（Java 代码已核，另检查文档、配置、脚本），或残留处已登记处置方式。
4. 分类服务模型获取方式已写入文档：checkpoint 缺失时的行为、获取/训练途径、测试 Stub 约定（D-25 关联）。

## 3. 现状盘点（重定义基线）

| 对象 | 现状 | 位置 |
| --- | --- | --- |
| 旧 Batch 代码 | 已整体移除，无包/类/依赖残留 | `1a5e537`；processing `src` 静态核查 |
| 新链测试 | `FactExtractionResponseValidatorTest`（L1/L2/L3 与 16 组样本）、`FactExtractionServiceTest`（分批/重试）、`StructuredBehaviorAssemblerTest`、`StructuredBehaviorWriterTest`（分类/向量/ES `_id`）、`FactExtractionPipelineTest`、`EvidenceExtractionServiceTest`、`MessageTaskScopeTest`（主链与失败终态）、`SourceDocumentScopeValidatorTest`、`BehaviorScopeElasticsearchIT`、`AnalysisRunFailureServiceTest` | processing `src/test` |
| 来源/版本字段 | `schemaVersion="1.0"` 为结构契约；`extractionPromptVersion="fact-extract-v1.0"` 为抽取来源版本；Behavior 字段、`t_behavior` mapping、006 增量 JSON、Assembler 装配、Writer 写入均已具备 | common `po/behavior/Behavior`、`documents/es_mappings.json`、`documents/plan1/migrations/006_behavior_schema_version.json` |
| 分类服务 | Feign `ClassifierClient` → `bert.service.url: http://localhost:8090`（Java bert 服务）→ Python `bert_service.py:8002` `/classify/batch`；默认模型 `classify-service/checkpoints/best_model.pt`（gitignore，D-25） | processing `client/ClassifierClient.java`、`application.yml`、bert `bert_service.py:65` |
| 旧链注释/文档 | Java 注释无残留；`documents/plan1/p1-03-plan.md` 旧 §3.3 决策 1 已在 P1-05 评审时同步修订 | 全仓核查 |

## 4. 任务拆分（一轮一个小任务）

### T1：新链测试覆盖核查与补足（F-13 方向）

- 对照 P1-05 交接清单逐项核查已存在测试是否覆盖：DTO 反序列化、L1/L2/L3 校验分支、分批边界（40 条/20,000 字符）、重试一次、日期归一化、稳定 ID 计算、分类失败阻断、向量失败降级、ES Bulk 显式 `_id`、主链成功才发消息、失败终态不重投。
- 缺口补测试，重点候选：分批超限边界（41 条/20,001 字符）、Assembler 对空 `facts` 的处理、Writer 在分类空响应/数量不一致时的阻断、稳定 ID 与 Evidence 侧 textHash 的一致性。
- 测试纪律：不依赖私有 checkpoint 与完整外部环境；需外部服务的用例沿用现有「未设置环境变量显式跳过」约定。

### T2：来源/版本标识契约确认

- 静态核对四处一致：`Behavior.schemaVersion`、`es_mappings.json` 的 `t_behavior.schemaVersion`、`StructuredBehaviorAssembler` 装配值、`StructuredBehaviorWriter` 写入值；确认固定为 `"1.0"`。同时核对 `extractionPromptVersion` 固定为 `fact-extract-v1.0`。
- 将上述字段分工写入 PLAN §7 数据契约与 p1-07-execution（PLAN 验收 8 已同步为 `schemaVersion`/`extractionPromptVersion`）。
- 若发现任何一处不一致，作为缺陷登记并修复，不得静默对齐。

### T3：残留旧 Batch 引用清理

- Java 代码已核无残留；本轮核查文档（plan1 各执行/计划文档、PLAN、README）、配置（Nacos 相关 yaml 不在本仓库则登记）、脚本（`test/`、`build/`）中是否仍提「旧 Batch 兼容入口」「LineProcessorTest 待补」等过期表述。
- 过期表述更新为现状；无法确认归属的残留登记后由用户决定，不擅自删除他人文档内容。

### T4：分类服务模型获取方式说明（D-25 关联）

- 在环境搭建说明（README 或 build 下现有文档）写明：`classify-service/checkpoints/best_model.pt` 被 gitignore、clone 后缺失；缺 checkpoint 时 `bert_service.py` 分类接口的行为（启动失败或返回错误）；模型获取途径（训练脚本位置/来源说明）；测试一律使用 Stub 不依赖私有 checkpoint。
- 不实现模型分发（属 D-25 独立项），只补齐「如何获取」的说明并登记 D-25 验证口径。

## 5. 涉及文件

- 测试：`risk-warning-processing/src/test/java/com/riskwarning/processing/**`（T1 新增/扩展用例）
- 主代码：仅当 T2 发现来源/版本字段不一致时修改对应类；T1 缺口若暴露主链缺陷按缺陷流程登记后处理
- 文档：`documents/plan1/p1-07-execution.md`（本计划执行记录）、PLAN §7/§8（来源标识契约与任务状态）、README 或 build 环境说明（分类模型获取，T4）
- 缺陷清单：`documents/plan0/baseline/defect-backlog.md`（F-13 关闭或推进、D-25 验证口径、T2 新缺陷登记）

## 6. 完成标准与不做清单

完成标准：

1. T1 核查清单全部有测试对应或已登记缺口；新增用例全部通过（外部依赖用例跳过纪律不变）。
2. T2 四处 `schemaVersion` 静态核对一致，且 `extractionPromptVersion` 已文档化。
3. T3 文档/脚本无过期旧 Batch 表述；无法确认项已登记。
4. T4 模型获取说明已写入文档，D-25 记录更新。

不做：

- 不恢复或重建任何旧 Batch 类、依赖或兼容入口。
- 不做模型 checkpoint 分发/上传（D-25 只补说明）。
- 不扩大范围到 P1-08/09/10（前端 Mock、期望事实标注、真实 PDF 验收）。
- 不处理 D-35/D-36（Kafka 消费链加固，独立缺陷项）。

## 7. 风险与登记

1. T1 核查可能暴露新链测试盲区（如分批边界、空 facts 装配），修复涉及主链时按「缺陷先登记、再小步修复」流程，不夹带新功能。
2. T4 模型获取方式若实际由团队私有渠道提供，只记录渠道说明，不把私有路径写入公开文档。
