# P1-04 Fact Extraction v1.0 设计

> 冻结对象：`fact-extraction-v1.0` Prompt 与 JSON Schema。本文只定义模型输入输出边界，不实现 Provider 调用、Java DTO、重试、降级或持久化。

## 1. 响应结构

模型必须只输出一个 JSON 对象，根对象固定为：

```json
{
  "facts": []
}
```

`facts` 是事实数组。一段证据可以产生多条独立事实；整份证据无法支持客观事实时返回空数组。根对象包装同时满足“只输出一个 JSON 对象”和“证据不足返回空事实列表”两个约束。

Schema 使用 JSON Schema 2020-12，文件为 `test/fixtures/p1/schemas/fact-extraction-v1.0.json`。未声明字段一律拒收。`quantitativeUnit` 不设单位白名单，只要求非空且最长 32 个字符；量纲语义留给规则阶段处理。

## 2. 字段边界

| StructuredBehavior 字段 | 来源 | v1.0 规则 |
| --- | --- | --- |
| `subject` | LLM | 必填，去空白后非空 |
| `action` | LLM | 必填，去空白后非空 |
| `object` | LLM | 可选；不及物语义可为 null |
| `status` | LLM | 必填；仅 `COMPLETED/IN_PROGRESS/PAUSED/TERMINATED/UNKNOWN` |
| `behaviorDate` | LLM | 可选；只接受 `yyyy-MM-dd` 或 `yyyy-MM-ddTHH:mm:ss` |
| `quantitativeData` | LLM | 可选数值；原文无数字时不得输出 |
| `quantitativeUnit` | LLM | `quantitativeData` 出现时必填、非空、最长 32 字符 |
| `description` | LLM | 必填；可压缩连接词，必须保留主体、动作、对象、数值和日期，不得增加判断 |
| `confidence` | LLM | 必填，闭区间 `[0,1]` |
| `evidenceIds` | LLM | 必填非空数组；每项必须来自本次输入证据集合 |
| `schemaVersion` | 系统 | 固定 `1.0` |
| `id` | 系统 | 按 P1-06 稳定 ID 规则装配，不交给模型生成 |
| `projectId/assessmentId/analysisRunId/sourceDocumentId` | 系统 | 从受校验的 `AnalysisScope` 与证据归属装配 |
| `extractionModel` | 系统 | 取 `AiChatProvider.modelId()` |
| `extractionPromptVersion` | 系统 | 固定 `fact-extract-v1.0` |
| `createdAt` | 系统 | 接收成功时生成 |
| `type/dimension/tags/descriptionVector` | 后续系统链 | 不属于事实抽取 v1.0 输出 |

系统字段承担隔离、幂等和审计职责。允许模型输出这些字段会把运行归属和版本正确性交给非确定性输出，因此 Schema 使用 `additionalProperties: false`，校验器以 `UNEXPECTED_FIELD` 拒收。

## 3. 事实边界

模型只陈述证据直接支持的企业事实，不判断违法、违规、合规、风险等级、责任或整改建议，也不引用法规。禁止输出 `COMPLIANT`、`NON_COMPLIANT`、`riskLevel`、`regulationIds`、`legalConclusion` 等判断字段或在文本字段中加入同类结论。

`UNKNOWN` 仅表示证据无法判断行为状态，仍需存在可抽取的主体、动作和描述。若连客观事实都无法形成，返回 `{"facts":[]}`，不生成 UNKNOWN 占位记录。

同一证据段包含多项事实时必须拆成多个数组元素。不同事实可以重复引用同一 `evidenceId`；单条事实也可以引用多段共同支撑的证据。

## 4. 示例

```json
{
  "facts": [
    {
      "subject": "本公司",
      "action": "完成一级供应商年度合规审查",
      "object": "12 家一级供应商",
      "status": "IN_PROGRESS",
      "behaviorDate": "2025-11-30",
      "quantitativeData": 3,
      "quantitativeUnit": "家",
      "description": "公司完成了 12 家一级供应商年度审查，其中 3 家未提供有效环境许可证明，整改尚未完成。",
      "confidence": 0.88,
      "evidenceIds": ["9f2a7c41d8b3e05614a7c9de2f83b110"]
    }
  ]
}
```

Java 装配日期精度值时，将 `yyyy-MM-dd` 补为当日 `T00:00:00`。Behavior ID 按 `sha256(analysisRunId + "|" + sourceDocumentId + "|" + textHash)` 前 32 位生成，示例不伪造旧式业务 ID。

## 5. P1-05 校验分层

1. L1 解析：直接解析；失败后可剥离 Markdown 围栏或扫描首个完整对象，但存在额外文本仍记录 `EXTRA_TEXT_AROUND_JSON` 并拒收。
2. L2 Schema：校验根对象、事实字段、类型、枚举、时间格式、置信度和数值单位配对。
3. L3 交叉：校验 `evidenceIds` 是输入集合子集；拒绝系统字段、风险判断和法规结论；完成日期精度归一化。

P1-05 只有在三层均通过后才能装配系统字段并计算稳定 ID。单条事实失败不得静默进入 Behavior 存储；批次返回结构须保留成功列表、失败项和模型调用元数据。

## 6. 输入装配上限

单次 Prompt 最多装配 40 条 EvidenceChunk，所有 `text` 合计最多 20,000 个 Unicode 字符。超限时由 P1-05 在调用 Provider 前按源文档、页码和段序号稳定分批，不允许在 Prompt 内静默截断证据。每批仍使用同一 scope，跨批同事实的去重由 P1-06 稳定 Behavior ID 负责。
