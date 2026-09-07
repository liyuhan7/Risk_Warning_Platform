# P1-04 Fact Extraction Prompt 与 JSON Schema 执行记录

> 执行日期：2026-09-06  
> 任务性质：A 类设计任务  
> 冻结版本：`fact-extraction-v1.0` / `fact-extract-v1.0`

## 1. 完成范围

本任务冻结事实抽取的模型输入输出契约，不调用真实 LLM，也不实现 Java DTO、`FactExtractionService`、Provider 适配、重试、降级或持久化。交付物如下：

| 交付物 | 结果 |
| --- | --- |
| `test/fixtures/p1/schemas/fact-extraction-v1.0.json` | JSON Schema 2020-12，根对象固定为 `facts` 数组 |
| `test/fixtures/p1/prompts/fact-extraction-v1.0.txt` | 单轮 Prompt 模板，限定只抽取证据支持的客观事实 |
| `test/fixtures/p1/fact-extraction-samples.json` | 16 组正常与反例样本 |
| `test/fixtures/p1/fact_extraction_validator.py` | 标准库实现的离线三层契约校验器 |
| `documents/plan1/p1-04-design.md` | 字段来源、事实边界、校验分层和输入上限 |

## 2. 冻结决策

1. 模型响应固定为 `{"facts":[...]}`；证据无法支持任何事实时返回 `{"facts":[]}`，不生成占位 Behavior。
2. 模型只输出 `subject/action/object/status/behaviorDate/quantitativeData/quantitativeUnit/description/confidence/evidenceIds`。作用域、标识、版本、时间戳和向量等字段全部由系统装配。
3. `status` 只允许 `COMPLETED/IN_PROGRESS/PAUSED/TERMINATED/UNKNOWN`；`UNKNOWN` 表示存在事实但状态证据不足。
4. 数值出现时单位必填；单位不设白名单，只校验非空且不超过 32 个字符。
5. 日期只允许 `yyyy-MM-dd` 或 `yyyy-MM-ddTHH:mm:ss`。P1-05 将日期精度值补为当日零点后装配到 Java 时间类型。
6. 一段证据包含多项事实时拆为多条；每条 `evidenceIds` 必须是本批输入 Evidence ID 的非空子集。
7. 单次 Prompt 最多包含 40 条 EvidenceChunk，正文合计最多 20,000 个 Unicode 字符。超限由 P1-05 稳定分批，禁止静默截断。
8. 模型不得输出法规、违法/合规、风险、责任、评分或整改结论。出现系统字段或禁止推断时拒收。

## 3. 校验分层

| 层级 | 职责 | 失败示例 |
| --- | --- | --- |
| L1 响应解析 | 解析唯一 JSON 对象；识别围栏和前后说明 | `INVALID_JSON`、`NOT_OBJECT`、`EXTRA_TEXT_AROUND_JSON` |
| L2 Schema 契约 | 校验根结构、字段、类型、必填、枚举、日期、置信度及数值/单位关系 | `MISSING_FIELD`、`BAD_ENUM`、`BAD_FORMAT`、`OUT_OF_RANGE`、`CROSS_RULE_VIOLATION` |
| L3 业务交叉 | 校验证据引用子集、禁止字段和禁止推断 | `UNKNOWN_REFERENCE`、`UNEXPECTED_FIELD`、`PROHIBITED_INFERENCE` |

离线脚本从冻结 Schema 读取必填字段、枚举、范围、日期表达式和单位长度，然后执行本契约所需的确定性检查。它是 P1-04 的样本验收工具，不是通用 JSON Schema 引擎；P1-05 需采用正式 Schema Validator 实现生产校验。

## 4. 样本验收结果

执行命令：

```powershell
py -3 test\fixtures\p1\fact_extraction_validator.py
```

结果：`allMatched=true`，16/16 组样本的通过状态和预期错误码一致。

| 类别 | 样本 | 结果 |
| --- | --- | --- |
| 正常数值、单位、日期 | S01 | 通过 |
| 一段多事实、多证据引用 | S02 | 通过 |
| 状态证据不足使用 UNKNOWN | S03 | 通过 |
| Markdown 包裹 | S04 | 按预期拒绝 |
| 缺必填字段 | S05 | 按预期拒绝 |
| 错枚举、空枚举 | S06～S07 | 按预期拒绝 |
| 置信度越界 | S08 | 按预期拒绝 |
| 数值缺单位 | S09 | 按预期拒绝 |
| 幻觉 Evidence ID | S10 | 按预期拒绝 |
| 风险或法规推断 | S11 | 按预期拒绝 |
| 无效 JSON、数组根节点 | S12～S13 | 按预期拒绝 |
| 无事实可抽取 | S14 | 通过空列表 |
| 输出系统字段 | S15 | 按预期拒绝 |
| 日期格式错误 | S16 | 按预期拒绝 |

Schema 与样本同时通过 `py -3 -m json.tool` 语法检查。当前环境未安装 `jsonschema` 或 Ajv，因此本轮没有宣称通过第三方 JSON Schema 元模式校验；生产校验器接入属于 P1-05。

## 5. P1-05 交接清单

1. 定义模型响应 DTO：根对象含 `facts`；事实字段与 Schema v1.0 一致，不接受额外字段。
2. `FactExtractionService.extract(scope, evidenceChunks)` 返回成功事实、失败项和调用元数据，不以空事实掩盖 Provider 或校验失败。
3. 输入装配先校验 Evidence 属于 `AnalysisScope`，按源文档、页码和段序号稳定排序、稳定分批。
4. 调用 Provider 时使用 `temperature=0`；`extractionModel` 取 `AiChatProvider.modelId()`，`extractionPromptVersion` 固定为 `fact-extract-v1.0`。
5. 按 L1/L2/L3 顺序校验。只有全部通过的事实才能归一化日期、装配作用域与系统字段，并计算 P1-06 冻结的 Behavior 稳定 ID。
6. Provider 的 429/5xx/IO 与 400/401/403、空响应、结构错误按既有异常分类处理；失败不得生成伪正常事实。
7. 使用固定 Stub 覆盖全部离线样本，再接入真实 Provider；真实连续调用、超长分批和写入链验证记录在 P1-05/P1-06 复验中。

## 6. 状态与限制

P1-04 的设计交付已完成，P1-05 可以依据冻结文件开始编码。P1 总 Entry Gate 中的专用 PostgreSQL/Elasticsearch 隔离、失败保护和 P0 全链回归仍未完成；本任务完成不代表真实抽取链或运行隔离已经通过集成验收。
