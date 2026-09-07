# P1-05 执行计划（FactExtractionService 与抽取链落地）

> 拟定日期：2026-09-05；执行前由用户评审确认。
> 依据：[PLAN](../PLAN.md)（§8 P1-05、§10 验收 3/6）、[p0-05 冻结契约](../plan0/baseline/p0-05-core-schemas.md)（§5 StructuredBehavior/5.3 校验）、[P1-04 冻结文件](p1-04-execution.md)（Schema/Prompt/样本/交接 7 条）、[p0-07 Provider 边界](../plan0/baseline/p0-07-provider-boundary.md)、P1-03/06 执行记录（Behavior 演进与稳定 id）。
> 前序：P1-01～04 与 P1-06 基础已评审通过。本计划是 A+B 混合任务：实现抽取服务并接入主链。

## 1. 目标与验收口径

PLAN §8 `P1-05`（A、B）：实现 `FactExtractionService`、Provider 适配、校验、重试、错误分类和显式降级；按 P1-04 冻结契约把证据转为 Structured Behavior 并落库。

完成后应满足（对应 PLAN §10 验收 3、6 + P1-04 交接 7 条）：

1. `FactExtractionService.extract(scope, evidenceChunks)` 可用：分批调用 Provider → L1/L2/L3 校验 → 归一化 → 装配系统字段与 P1-06 稳定 id → 返回"成功事实 + 失败项 + 调用元数据"；**不以空事实掩盖 Provider 或校验失败**（P1-04 交接 2）。
2. Provider 复用 `AiChatProvider`（temperature=0、`extractionModel=modelId()`、`extractionPromptVersion="fact-extract-v1.0"`）；429/5xx/IO 重试退避、403/400/401/结构错误不可重试一次终止（既有 Provider 已实现，P1-05 只接线不重写）。
3. 校验按 L1/L2/L3 顺序（解析 → Schema → 交叉：evidenceIds⊆输入、unit 有值必填、禁止系统字段/推断），失败条不进库且计入失败项。
4. 新链行为经 **分类补全（tags/type/dimension）+ 向量化（descriptionVector）** 后落 ES t_behavior（显式 `_id` = 稳定 id）——维持下游指标/法规计算输入完整（D-F2 已确认：复用旧分类服务与向量化）。
5. 固定 Stub Provider 覆盖 P1-04 全部 16 组样本后再接真实 Provider（交接 7）。

## 2. 冻结输入与现状

- **模型输入输出契约**：`schemas/fact-extraction-v1.0.json`、`prompts/fact-extraction-v1.0.txt`、16 组样本（已冻结）。
- **Provider**：`AiChatProvider.chat(prompt)` 单轮 user 消息（无 system role、无 API 层 JSON 约束 → system 说明并入 user 文本）；`modelId()` 提供版本；错误分类在 `OpenAiCompatibleChatProvider` 完成。
- **Behavior 存储**：P1-03 演进后的 `Behavior` PO（含作用域/证据/抽取字段）+ `_id=sha256(runId|sid|textHash)` 稳定规则（P1-06）。
- **Evidence 读取**：`EvidenceExtractionService.findBySourceDocument(scope, sourceDocumentId)`（P1-02，归属校验 + 完整性复核）。
- **分类与向量**：旧链 `LineRangeItemWriter` 内的分类（`ClassifierClient` 4 头：tags/type/dimension）与向量化（`VectorizationClient`）逻辑可复用；下游 `BehaviorProcessingService` 依赖这些字段（`:603-647` 向量/标签匹配、`:751` 定性/定量判定）。
- **主链现状**：`MessageTask` FILE_UPLOAD → processDocuments（JSONL + Evidence 落 PG）→ **Batch（旧链逐行产行为）** → indicator 消息。

## 3. 新链子流程（P1-05 目标态，旧 Batch 不再从 FILE_UPLOAD 默认调用）

```text
processDocuments（JSONL + Evidence PG）
  ↓ 读取本文件证据（findBySourceDocument，稳定排序）
FactExtractionService.extract(scope, evidenceChunks)
  稳定分批（≤40 条 / ≤20000 字符，P1-04 上限）
  → AiChatProvider.chat（temperature=0）
  → L1 解析 → L2 Schema → L3 交叉（失败条重试一次后记 failed item）
  → Normalizer（日期归一化/trim/单位校验）→ 系统字段装配（含稳定 id）
  → （D-F2 分类补全 + 向量化，若确认）
  → 批量写 ES t_behavior（显式 _id）
  → 返回成功 facts / failed items / 元数据
  ↓
发送 indicator 消息（沿用现有门禁：失败即终态 FAILED）
```

- 旧 Batch（`LineRangePartitioner/LineProcessor/LineRangeItemWriter`）**保留代码**，本任务把 FILE_UPLOAD 入口切换到新链；兼容触发与来源标识登记 P1-07。

## 4. 任务拆分（一轮一个小任务）

### T1：响应 DTO 与映射（common）
- 新建 LLM 响应 DTO（根 `facts`，字段与 Schema v1.0 一致，`additionalProperties=false` 语义），放 `common/dto/analysis` 或 `common/dto/fact`（T1 定）。
- 提供 `StructuredBehavior` 装配器：facts → 演进后 `Behavior` 的系统字段填充（scope/schemaVersion/extractionModel/extractionPromptVersion/稳定 id/createdAt）。
- 单元测试：facts→Behavior 装配（id 与 P1-06 规则一致、空 evidenceIds 拒绝）。

### T2：生产 Schema Validator（L1/L2/L3）
- 按 D-F4 实现 **Java 自研确定性校验**：读冻结 Schema 文件，把必填/枚举/范围/日期两档/单位长度/值-单位联动编译为 Java 校验规则（零新依赖），与 P1-04 Python 校验器同构并互相印证。
- L1 解析（p0-06 §4.4 三级提取）、L2 字段（必填/枚举/范围/日期两档/单位长度/值-单位联动）、L3 交叉（evidenceIds⊆输入、禁止系统字段与推断文本）→ 错误分类到失败项。
- 测试：P1-04 16 组样本全部移植为 Java 单测输入（同一契约双实现互相印证）。

### T3：FactExtractionService.extract
- `extract(scope, evidenceChunks)`：归属与排序 → 稳定分批（40/20000）→ 每批调 Provider → 校验 → 归一化 → 返回 `FactExtractionResult(成功 facts、failedItems、元数据)`。
- 错误语义（D-F3 已确认：严格）：Provider 层失败（重试耗尽/不可重试）→ 抛 `LlmProviderException` 由上层按 D3 标 FAILED；模型单条输出校验失败 → 该批重试一次，仍失败 → 整 run FAILED（不产生部分结果），失败明细进日志与元数据。
- Stub Provider（实现 `AiChatProvider`，按样本返回）驱动测试；测试覆盖分批、超长截断拒绝、失败项汇总、元数据字段（模型/版本/耗时/批数）。

### T4：主链接线（processing MessageTask，D-F1 已确认切换）
- 新链执行器（处理链编排）：processDocuments → 逐文件读取证据 → `extract` → 分类补全（复用 ClassifierClient，D-F2）→ 向量化（复用 VectorizationClient，失败不阻断写）→ 写 ES t_behavior（新写入器，显式 `_id`）→ 全部成功后发送 indicator 消息。
- 失败语义：任何一步失败或校验整体失败 → `markRunFailed`（D3 失败即终态、不重投、不产生部分结果）。
- 移除 FILE_UPLOAD 对旧 Batch 的调用（保留类文件）；回归：P1-01～03 定向测试适配（BatchJobScopeTest 等保留，主链路径单测新写）。
- 说明：Batch 终态门禁（P1-01 修复）针对旧链；新链无 Batch 步骤，门禁由新链"整体成功才发 indicator + 失败标 FAILED"承接。

### T5：集成验证与文档
- ES/PG 专用环境：真实（或 Stub→真实 Provider 两步）跑"文档 → Evidence → 抽取 → Behavior 落库 → 隔离/幂等"切片；验证 Behavior 含作用域/evidenceIds/向量且 `_id` 幂等。
- 文档：`p1-05-execution.md`、defect-backlog 相关登记、PLAN P1-05 进度；P1-06 复验三场景的前置说明更新。
- 真实 Provider 连续调用依赖凭据（P0-06/P1-04 同款前置），登记。

## 5. 评审决策（2026-09-05 已确认，实施按此执行）

- **D-F1 主链切换 = 是**：FILE_UPLOAD 默认入口切换为抽取链；旧 Batch 代码保留、仅 P1-07 兼容入口可触发。
- **D-F2 标签/维度/向量 = 复用分类 + 向量化**：抽取后复用旧分类服务（`ClassifierClient`）补 `tags/type/dimension`，并向量化 `descriptionVector`；P2 检索升级前保留下游输入完整。
- **D-F3 失败降级 = 严格**：任一 Provider 失败（重试耗尽/不可重试）或任一校验条重试一次后仍失败 → 整 run FAILED（按 D3 失败即终态，不产生部分结果）；失败明细进日志与 `FactExtractionResult` 元数据。
- **D-F4 生产 Schema Validator = Java 自研确定性校验**：移植 P1-04 校验器（读冻结 Schema 生成规则），零新依赖；与 Python 端同构并互相印证；P1-04 声明"生产可用正式引擎"为可选，本决策关闭。

## 6. 涉及文件（预计）

新增（主代码）：
- `common/dto/...`：LLM 响应 DTO（facts）+ `FactExtractionResult`（失败项/元数据）
- `processing/service/FactExtractionService`（或按 p0-05 命名空间）+ Schema 校验器 + Normalizer
- `processing/service/`：StructuredBehavior→Behavior 装配、新链行为写入器（显式 `_id`）
- `processing/provider/`：Stub AiChatProvider（测试）

修改：
- `processing/task/MessageTask`（FILE_UPLOAD 切新链）
- 复用：`EvidenceExtractionService.findBySourceDocument`、`ClassifierClient`、`VectorizationClient`、既有门禁

测试/文档：
- 16 组样本 Java 化、分批/失败/元数据测试、切片集成测试
- `documents/plan1/p1-05-execution.md`、PLAN、defect-backlog

## 7. 完成标准与不做清单

完成标准：
1. extract 三件套（服务/校验/归一化）对 16 组样本全部通过（Java 端复现 P1-04 判定）。
2. 主链切片（文档→证据→抽取→分类/向量→Behavior 落 ES）在专用环境跑通；Behavior 三作用域 + evidenceIds + 向量非空、`_id` 幂等。
3. 失败语义按 D-F3 生效（Provider 失败→FAILED；不产伪事实）。
4. P1-01～04 定向回归通过（MessageTask 主链测试适配）。

不做：
- 不做检索升级/Reranker/新 Embedding（P2）。
- 不做来源/版本标识落地（P1-07）。
- 不实现有效结果切换的复验（P1-06 复验阶段）。
- 不做真实 Provider 连续调用（凭据前置，登记）。
- 不删除旧 Batch 类（P1-07 兼容入口保留）。

## 8. 风险与登记

1. 分类/向量服务（BERT）不可用时新链行为无向量 → 指标 kNN 空召回；沿用"向量化失败不阻断写入"容错并告警（与旧 writer 一致）。
2. Provider 输入超长分批由 P1-05 负责稳定分批；批次间无状态（单轮），失败重试按批。
3. 失败语义已按 D-F3 定严格档：Provider/校验失败即整 run FAILED；`FactExtractionResult` 元数据与日志保留每批失败明细（批号、错误码、截断原文）以便诊断。
4. system/角色提示因 Provider 仅支持单 user 消息，全部并入 user 文本（已按 P1-04 Prompt 设计）——不得另起多轮。
