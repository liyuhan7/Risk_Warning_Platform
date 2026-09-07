# P1-04 执行计划（Fact Extraction Prompt 与 JSON Schema）

> 拟定日期：2026-09-05；执行前由用户评审确认。
> 依据：[PLAN](../PLAN.md)（§8 P1-04、§10 验收 3/6）、[p0-05 冻结契约](../plan0/baseline/p0-05-core-schemas.md)（第五节 StructuredBehavior、5.1/5.2/5.3/5.4）、[p0-06 Prompt 验证记录](../plan0/baseline/p0-06-prompt-validation.md)（方法学与校验分层）、[p1-handoff](p1-handoff.md)（§4 事实抽取要求）、[P1-06 执行记录](p1-06-execution.md)（行为稳定 id 规则已冻结，p0-05 §5 已回写）。
> 前序：P1-01～03 已评审通过；P1-06 基础部分已评审通过（PG/ES 集成复验待部署前置）。P1-04 是 A 类设计任务，只冻结 Prompt/JSON Schema/样本集，不写抽取服务代码（P1-05）。

## 1. 目标与验收口径

按 PLAN §8 `P1-04`（A）：设计 Fact Extraction Prompt 和 JSON Schema，**只抽取事实，不输出风险判断**。

完成后应满足：

1. 交付可版本化的 Prompt 模板（v1.0）与 JSON Schema（v1.0），与 p0-05 StructuredBehavior 字段逐项对齐；二者独立成文件，可单独评审。
2. 明确"LLM 产出字段"与"系统装配字段"边界：LLM 不得输出 `schemaVersion/id/projectId/assessmentId/analysisRunId/sourceDocumentId/createdAt/extractionModel/extractionPromptVersion/descriptionVector` 等系统字段（延续 p0-06 §4.2 边界，避免把作用域正确性交给模型）。
3. 构造样本集覆盖至少六类场景（沿用 PLAN §11：正常、Markdown 包裹、缺字段、错类型/错枚举、错误单位、空/不足证据），离线校验全部符合预期；幻觉引用（输出不在输入证据集内的 evidenceId）必须被判定为拒绝。
4. 明确与 P1-05 的交接：Schema/Prompt/样本冻结 → P1-05 据此实现 `FactExtractionService.extract(scope, evidenceChunks)`、Schema Validator、Normalizer、重试与降级。

## 2. 冻结输入（不可偏离）

### 2.1 抽取产物 StructuredBehavior（p0-05 §5 字段表，LLM 产出子集）

| 字段 | 必填 | 类型 | 备注 |
| --- | --- | --- | --- |
| `subject` | M | String | 主体，去空白非空（5.3 规则 2） |
| `action` | M | String | 动作，去空白非空 |
| `object` | C | String | 对象；不及物语义可为空 |
| `status` | M | Enum | 仅 `COMPLETED/IN_PROGRESS/PAUSED/TERMINATED/UNKNOWN`（5.1）；空串直接判失败（5.3 规则 5）；`UNKNOWN`=证据不足，合法但不得再当下游中性值计分（D-07 语义） |
| `behaviorDate` | O | String(LocalDateTime) | 原文有明确时间才输出 |
| `quantitativeData` / `quantitativeUnit` | O / C | Double / String | 有值则单位必填（5.3 规则 4）；禁止从无数字的原文臆造数值 |
| `description` | M | String | 不得脱离原文引入推断（5.3 规则 2） |
| `confidence` | M | Double `[0,1]` | 越界即拒（5.3 规则 3） |
| `evidenceIds` | M | String[] | 至少一个；**必须是本次输入证据的子集**（5.3 规则 1：幻觉引用拒收）；禁止默认 COMPLIANT 或伪造 |

### 2.2 输入形状（P1-05 交接口径，P1-04 按此设计 Prompt 占位符）

`FactExtractionService.extract(scope, evidenceChunks)`：输入为**本次 scope 的证据列表**，每条证据至少带 `id`、`pageNumber`（可空）、`segmentIndex`、`text`、`sourceFileName`（可空展示）。Prompt 把证据编号化（如 `[E-1] ...`）并提供其 id，LLM 输出的 `evidenceIds` 必须取自输入集合——这是 5.3 规则 1 能落地的前提。

### 2.3 硬性输出约束（p1-handoff §4）

- LLM **只抽取原文事实**：不输出"是否违法/违规"、风险等级、分数、法规是否适用、整改建议。
- 原文无法支撑的字段按 Schema 显式表达：`status=UNKNOWN`、数值缺省不填单位、无法判定时用证据不足语义，**禁止默认 COMPLIANT 或伪造数值**。
- 供应商调用沿用 P0-07 Provider（`AiChatProvider.chat(prompt)` + `modelId()`）、`temperature=0`、可重试错误（429/5xx/IO）退避、不可重试错误（403/400/401/结构缺失）一次终止并抛 `LlmProviderException`，不返回降级假值——这些由 P1-05 复用，P1-04 只要求 Prompt/Schema 与之兼容（单轮完整 prompt、无多轮状态）。
- 若整份输入证据无法支撑任何事实，抽取结果为**空列表**（D-E1 已确认），不进库、不产生占位记录。

## 3. 输出格式纪律（继承 p0-06 §4，P1-04 冻结）

1. Prompt 硬性要求"只输出一个 JSON 对象，无其他文字"。
2. Markdown 围栏/前后说明 = **违规**（p0-06 §4.3：可解析但记 `EXTRA_TEXT_AROUND_JSON` 错误，统计口径不放过），容错提取顺序照搬 p0-06 §4.4（直接 loads → 剥围栏 → 括号配对扫描）。
3. 系统装配字段若出现在 LLM 输出 = `UNEXPECTED_FIELD` 警告/拒收（与 p0-06 §4.2 一致）。
4. Prompt 不含任何密钥/端点配置；系统字段（scope、模型、Prompt 版本）由 Java 侧装配。

## 4. 任务拆分（每轮一个小交付）

### T1：JSON Schema v1.0（先定数据契约，Prompt 依据它写）

- 交付 `test/fixtures/p1/schemas/fact-extraction-v1.0.json`：JSON Schema（draft-07 或 2020-12，评审时定）仅描述第 2.1 节 LLM 产出字段：
  - `required`: `subject/action/status/description/confidence/evidenceIds`；
  - `status` 枚举 5 值、禁止空串；`confidence` `[0,1]`；`quantitativeUnit` 仅做"有值必填 + 非空 + ≤N 字符"校验（D-E2 不做白名单）；
  - `behaviorDate` pattern 两档：`yyyy-MM-dd` 或 `yyyy-MM-ddTHH:mm:ss`（D-E3 允许日期精度）；
  - `evidenceIds` `minItems:1`、`items: {type: string}`，集合子集校验属交叉层（见 T3 校验器，不放 JSON Schema 的 `enum`——输入集运行时才知道）。
- 交付 `documents/plan1/p1-04-design.md`：字段边界说明（LLM 产出 vs 系统装配，含"为什么 LLM 不产 id/scope/版本"的理由，引 p0-06 §4.2）、与 p0-05 §5 的逐字段映射、样例 JSON（沿用 p0-05-schema-examples 案例但去掉已废弃的旧 id 格式，注明 id 规则以 P1-06 冻结为准）。
- 评审决策：D-E1～E4 已确认（见第 5 节）。

### T2：Prompt 模板 v1.0

- 交付 `test/fixtures/p1/prompts/fact-extraction-v1.0.txt`：占位符形式（如 `{{SYSTEM_NOTE}}`/`{{SCOPE}}`/`{{EVIDENCES}}`），内容至少包含：
  - 角色与任务：从给定材料证据中抽取企业客观事实，不判断违法/风险/责任；
  - 证据输入格式（编号 + id + 页码/段号 + 原文）与输出要求（仅 JSON、evidenceIds 必须引用输入编号对应的 id）；
  - 字段级指令（每个 LLM 产出字段一段：如何从原文判定 status/时间/数值/单位、原文无时间或数字时怎么办）；
  - 反例明示（禁止默认 COMPLIANT、禁止伪造数值/单位、禁止输出风险等级与法规结论）；
  - "系统装配字段不要输出"的明示。
- 每条字段指令必须能回溯到 p0-05 §5 的校验规则（评审以"规则可追溯"为标准）。

### T3：构造样本集与离线校验脚本

- 交付 `test/fixtures/p1/fact-extraction-samples.json`：正常样本（至少 3 类行业事实，含数字+单位、含时间、无数字）+ 反例样本（沿用 p0-06 六类基线 + P1 特有）：
  1. 正常（多证据引用、UNKNOWN 状态合法样本）；
  2. Markdown 包裹；
  3. 缺必填字段；
  4. 错状态枚举 / 空串状态；
  5. `confidence` 越界；
  6. `quantitativeData` 有值而 `quantitativeUnit` 缺失——必须拒绝（D-E2 无白名单，只测"有值必填"）；
  7. 幻觉 evidenceId（不在输入集）——必须拒绝；
  8. 输出风险判断/法规结论（"违反 XX 法"）——必须拒绝；
  9. 无效 JSON / 数组而非对象；
  10. 全空/不足证据 → 期望结果为空事实列表（D-E1 已确认）。
- 交付 `test/fixtures/p1/fact_extraction_validator.py`：三层校验（L1 解析 / L2 JSON Schema 字段 / L3 交叉：evidenceIds⊆输入集、quantitativeUnit 有值必填、system 字段出现即警告）——风格照搬 p0-06 的 `analysis_result_validator.py`；离线跑样本输出与预期表。
- 完成标准：样本 100% 符合预期（同 p0-06 表格方式记录）。

### T4：设计评审、冻结与交接文档

- 组织设计评审：Prompt 逐段规则 ↔ p0-05 校验规则可追溯；字段边界无争议；样本表评审。
- 交付 `documents/plan1/p1-04-execution.md`：冻结记录（含与 P1-05 的接口交接清单：Java DTO 字段清单、`extract` 返回"列表 + 失败项 + 调用元数据"形状、validator 职责边界、`extractionModel/modelId()` 与 `extractionPromptVersion="fact-extract-v1.0"` 注入点、P1-06 稳定 id 装配点）。
- live 真实调用（连续 N 次）不在 P1-04（依赖凭据，P0-06 遗留同款前置：用户提供 `LLM_BASE_URL/LLM_MODEL/LLM_API_KEY`）；登记为 P1-05 接入后复验。

## 5. 评审决策（2026-09-05 已确认，实施按此执行）

- **D-E1 证据不足 = 返回空事实列表**：`insufficientEvidence` 字段不进 Schema；空结果不进库、不产生占位记录（与 5.3/StructuredBehavior 校验语义及 P1-06 幂等最一致）。
- **D-E2 单位不做白名单**：`quantitativeUnit` 只做"有值必填 + 非空 + ≤N 字符"校验；量纲语义（D-20）登记给 P3 规则侧，不在 P1-04 臆造单位字典。
- **D-E3 `behaviorDate` 允许日期精度**：`yyyy-MM-dd` 与 `yyyy-MM-ddTHH:mm:ss` 两档均合法（Schema pattern 两档）；Java 装配为 `LocalDateTime` 时日期档补 `T00:00:00`。
- **D-E4 一段证据多事实 = 多条独立记录**：每条 fact 独立 subject/action/object/时间/引用，Prompt 明示，禁止合并为一条笼统描述。

## 6. 交付物清单

| 文件 | 作用 |
| --- | --- |
| `test/fixtures/p1/schemas/fact-extraction-v1.0.json` | JSON Schema（LLM 产出字段） |
| `test/fixtures/p1/prompts/fact-extraction-v1.0.txt` | Prompt 模板 v1.0 |
| `test/fixtures/p1/fact-extraction-samples.json` | 构造样本（10+ 条，含预期） |
| `test/fixtures/p1/fact_extraction_validator.py` | 离线三层校验器 |
| `documents/plan1/p1-04-design.md` | 字段边界/样例/与 P0-05 映射 |
| `documents/plan1/p1-04-execution.md` | 冻结记录 + P1-05 交接清单 |

## 7. 完成标准与不做清单

完成标准：
1. Schema/Prompt/样本三件套冻结，样本离线校验 100% 符合预期（记录表）。
2. 字段边界与 p0-05 §5 逐字段可追溯；系统字段不在 LLM 输出范围。
3. 风险判断/法规结论类输出在 Prompt 明令禁止、样本中有反例且被拒。
4. P1-05 交接清单明确（DTO 字段、extract 返回形状、validator/normalizer 职责、版本注入、稳定 id 装配）。

不做：
- 不写 `FactExtractionService`/Java DTO/Schema Validator/Normalizer/重试降级（P1-05）。
- 不做真实 LLM 连续调用（凭据前置未满足，登记；P1-05 接入后复验）。
- 不做 dimension/type/tags 的 LLM 抽取（其语义待定义且属标签化而非事实抽取；登记给检索/分类链评审，P1-04 Schema 不含，除非评审推翻）。
- 不做 Prompt 版本管理框架/评测集（P1-09 标注 Expected Facts）。
- 不改任何 Java 主代码（纯文档/样本/脚本交付）。

## 8. 风险与登记

1. 无可用 LLM 凭据 → live 验证顺延；P1-04 结论不依赖 live（样本为构造注入），与 p0-06 同口径声明"真实调用前置未满足"。
2. 供应商对超长多段证据的输入长度限制：Prompt 需约束单次输入证据条数与总字符（评审时定上限并写进 Prompt/输入装配说明）。
3. evidenceIds 与多段证据引用：一段文本可被多条事实引用（多对多），Prompt 需说明重复引用合法；P1-05 校验"至少一个且都在输入集"即可。
4. `description` 不得改写原文的度：定义"可压缩连接词但保留数值/日期/主体名词"的编辑许可（避免逐字超长）；样例中给改写 vs 不改写对照。
