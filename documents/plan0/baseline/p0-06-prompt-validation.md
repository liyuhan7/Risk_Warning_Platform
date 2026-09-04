# P0-06 固定 Prompt 结构化 JSON 验证

> 执行日期：2026-08-27
> 对应计划：`documents/PLAN.md` 任务 `P0-06`
> 验收依据：计划 0 验收标准第 5 条——固定 Prompt 至少连续验证 10 次，每次保留原始响应、校验结果和重试结果，失败不被静默吞掉
> 测试方式依据：PLAN.md:178——覆盖正常、缺字段、错枚举、额外文本、超时和无效 JSON

## 一、当前状态

| 部分 | 状态 |
| --- | --- |
| Prompt 模板与固定输入 | 已完成 |
| JSON 校验器（Schema + 交叉规则） | 已完成 |
| 构造样本验证（14 条，覆盖 6 类场景） | **已完成并通过，14/14 符合预期** |
| 连续 10 次真实调用 | **未执行**，等待凭据轮换，见第六节 |

真实调用未执行的原因：验证需调用百度千帆，而现有凭据硬编码在 `LLMUtil.java:23` 且已进入 git 历史（D-21）。已决定先按 `P0-07` 吊销并换新 Key、改由环境变量注入，再执行 live 模式。**这一决定改变了 P0-06 与 P0-07 的实施顺序。**

## 二、验证对象

选定 `AnalysisResult` 合规推理 Prompt。理由：它是 P0-05 冻结的五个 Schema 中唯一由 LLM 直接产出的对象，校验规则最复杂（8.4 节 8 条含交叉校验），且是 `P3-02` 的直接前置。

`StructuredBehavior` 的事实抽取 Prompt 不在本次范围，其设计归属 `P1-04`。

## 三、交付物

| 文件 | 作用 |
| --- | --- |
| `test/fixtures/p0/prompts/compliance-reasoning-v1.0.txt` | Prompt 模板，占位符形式，独立成文件便于版本化与单独评审 |
| `test/fixtures/p0/prompts/compliance-reasoning-input.json` | 固定输入，与 `p0-05-schema-examples.json` 同一案例 |
| `test/fixtures/p0/analysis_result_validator.py` | 校验器，实现 p0-05 第 8 节字段约束与 8.4 交叉规则 |
| `test/fixtures/p0/prompt_validation_run.py` | 执行器，offline / live 双模式 |
| `documents/plan0/baseline/p0-06-samples/offline-samples.json` | 14 条构造样本的原始响应与校验结果 |

复现命令：

```
py -3.9 test\fixtures\p0\prompt_validation_run.py --mode offline

$env:LLM_BASE_URL="https://<供应商域名>/v1/chat/completions"
$env:LLM_MODEL="<模型标识>"
$env:LLM_API_KEY="<新Key>"
py -3.9 test\fixtures\p0\prompt_validation_run.py --mode live --runs 10
```

## 四、校验器设计

### 4.1 三层结构

分层逐级短路，使失败原因可定位到具体层次而非只报「校验失败」。

| 层 | 职责 | 失败码示例 |
| --- | --- | --- |
| L1 解析层 | 能否从响应文本取出 JSON 对象 | `INVALID_JSON`、`NOT_OBJECT`、`EXTRA_TEXT_AROUND_JSON` |
| L2 字段层 | 必填性、类型、枚举、取值范围 | `MISSING_FIELD`、`BAD_ENUM`、`EMPTY_STRING_IN_ENUM`、`OUT_OF_RANGE` |
| L3 交叉层 | 8.4 的字段间约束 | `CROSS_RULE_VIOLATION`、`UNKNOWN_REFERENCE` |

### 4.2 校验范围只覆盖 LLM 产出字段

`AnalysisResult` 的 20 个字段中，12 个由 LLM 产出并参与校验；`schemaVersion`、`id`、`analysisRunId`、`assessmentId`、`behaviorId`、`modelVersion`、`promptVersion`、`analyzedAt` 由系统装配，不要求也不允许 LLM 输出（若出现则记为 `UNEXPECTED_FIELD` 警告）。

这条边界很重要：让 LLM 生成 `analysisRunId` 之类的系统标识，等于把作用域字段的正确性交给模型，而 P0-03/P0-05 刚把它定为强制隔离依据。

### 4.3 额外文本判为错误而非警告

Prompt 硬性要求只输出 JSON。响应带 Markdown 围栏或前后说明时，校验器仍会容错提取出对象（`parsed` 可用），但**同时记 `EXTRA_TEXT_AROUND_JSON` 错误**。

理由：能解析不等于遵守了指令。若判为警告，统计上会显示「100% 通过」，掩盖模型未遵守输出格式的事实；而下游若有任何直接 `json.loads` 的调用点，这类响应会当场失败。调用方可根据 `parsed` 决定是否接受降级结果，但统计口径必须记录违规。

### 4.4 提取器的容错顺序

1. 直接 `json.loads`，完全合规的情形零开销通过。
2. 剥离 Markdown 代码块围栏后重试。
3. 括号配对扫描，跳过字符串字面量与转义字符。

第 3 步的字符串跳过是必要的：`reasoning` 字段中出现 `}` 字符时，朴素的「找第一个 `}`」会截断出无效 JSON。已实测 `{"a": "} not the end", "b": 2}` 可正确解析。

## 五、构造样本结果

14 条全部符合预期。真实调用只能产出「正常」与偶发偏差，后四类场景无法稳定复现，故须构造注入。

| 样本 | 类别 | 期望 | 实际 | 触发的失败码 |
| --- | --- | --- | --- | --- |
| S01-normal | 正常 | 通过 | 通过 | — |
| S02-missing-field | 缺字段 | 拒收 | 拒收 | `MISSING_FIELD` |
| S03-bad-enum | 错枚举 | 拒收 | 拒收 | `BAD_ENUM` |
| S04-extra-text | 额外文本 | 拒收 | 拒收 | `EXTRA_TEXT_AROUND_JSON` |
| S05-invalid-json | 无效 JSON | 拒收 | 拒收 | `INVALID_JSON` |
| S06-timeout | 超时 | 拒收 | 拒收 | `TIMEOUT` |
| S07-empty-string-enum | 空串枚举 | 拒收 | 拒收 | `EMPTY_STRING_IN_ENUM` |
| S08-hallucinated-regulation | 幻觉引用 | 拒收 | 拒收 | `UNKNOWN_REFERENCE` |
| S09-hallucinated-evidence | 幻觉引用 | 拒收 | 拒收 | `UNKNOWN_REFERENCE` |
| S10-cross-rule-not-applicable | 交叉规则 | 拒收 | 拒收 | `CROSS_RULE_VIOLATION` |
| S11-missing-gap-type | 交叉规则 | 拒收 | 拒收 | `CROSS_RULE_VIOLATION` |
| S12-unitless-gap | 交叉规则 | 拒收 | 拒收 | `CROSS_RULE_VIOLATION` |
| S13-confidence-out-of-range | 取值越界 | 拒收 | 拒收 | `OUT_OF_RANGE` |
| S14-unknown-applicable-mismatch | 交叉规则 | 拒收 | 拒收 | `CROSS_RULE_VIOLATION` |

PLAN.md:178 要求的六类已全覆盖（S01—S06），其余 8 条覆盖 P0-05 的交叉校验规则与空串约束。

### 5.1 固定输入内置了一个干扰项

`compliance-reasoning-input.json` 的候选法规有两条：供应链环境合规管理办法（相关）与环境保护税法（无关）。若模型把后者列入 `regulationIds`，说明它未做适用性判断而是把所有候选照抄。

该判定属人工核对项，不进自动断言——两条法规都在候选集合内，`UNKNOWN_REFERENCE` 规则抓不到它。`_expectation` 字段记录了期望值供 live 模式后人工比对。

## 六、真实调用的前置条件

### 6.1 原有凭据实测结果：账户欠费

用原 Key 实际发起调用，服务端返回：

```
HTTP 403
{"error":{"code":"account_overdue","message":"Access denied due to overdue account","type":"access_denied"}}
```

返回码是 `account_overdue` 而非 `invalid_token` 或 `401`。服务端要先认出并接受该 Key 的身份，才会进入余额检查，**因此这个凭据仍然有效，只是账户欠费**。

两点结论：

- 欠费不构成安全保护。账户充值后该 Key 立即恢复可用，而它已在 git 历史中，任何能读到仓库的人都能取回。**控制台吊销仍是必须动作**，不可因「反正调不通」而跳过。
- 该 403 不可重试。对账户状态问题重试只会放大故障，`P0-07` 的 Provider 已将其归入不可重试类并有单测覆盖。

### 6.2 live 模式前置条件

1. 账户充值，恢复调用能力。
2. **在供应商控制台吊销** `LLMUtil.java:23` 原有的 Key。
3. 新凭据经环境变量注入，端点与模型同样由环境变量提供：

```powershell
$env:LLM_BASE_URL="https://<供应商域名>/v1/chat/completions"
$env:LLM_MODEL="<模型标识>"
$env:LLM_API_KEY="<新Key>"
```

4. 运行 `--mode live --runs 10`。

第 1 步与第 2 步的顺序可以互换，但两者都不可省略。若更换供应商，则第 1 步不再需要，只需按上述环境变量指向新平台——脚本与 Java 侧都不需要改代码。

`prompt_validation_run.py` 不含任何默认密钥，也不打印密钥内容；三个环境变量任一缺失时 live 模式拒绝执行并列出缺失项。它不依赖 `LLMUtil`，自带独立调用实现，与 Java 侧共用同一套 `llm.*` 配置口径。

### 6.3 沙箱环境限制

沙箱内 PowerShell 的 TLS 握手受限（SChannel 报 `No credentials are available in the security package`），而 DNS 解析与 TCP 443 连接均正常。Python 走独立 OpenSSL 栈可正常发出请求，因此本脚本以 Python 实现，不要改回 PowerShell。

## 七、核对时发现的两处缺陷

两条均已在 `P0-07` 修复，此处保留发现过程。

### 7.1 `callLLMApi` 无任何重试

原 `LLMUtil.java:462-487` 单次请求失败即抛 `IOException`，无重试、无退避。已登记为缺陷 D-31。

PLAN.md:169 要求保留「重试结果」，说明重试是预期行为。本脚本实现了最多 2 次重试加 2 秒间隔，`P0-07` 的 `OpenAiCompatibleChatProvider` 采用了同一口径并补上了可重试与不可重试的区分。

### 7.2 无 `temperature` 参数，固定 Prompt 不可复现

原 `LLMUtil.java:437-446` 的请求体只有 `model` 与 `messages`，未设 `temperature`，取供应商默认值（通常大于 0）。这意味着**同一 Prompt 多次调用结果不稳定**，与「固定 Prompt 验证」的前提直接冲突。已登记为缺陷 D-32。

本脚本显式设 `temperature=0.0`；`P0-07` 后 Java 侧同样显式写入该参数，默认 `0.0`。

## 八、未完成事项

| 事项 | 阻塞原因 | 解除条件 |
| --- | --- | --- |
| 连续 10 次真实调用 | 无可用凭据。原 Key 账户欠费且须吊销 | 用户更换平台或充值后提供新凭据，按 6.2 设置三个环境变量 |
| 干扰法规的人工核对 | 依赖真实调用结果 | 同上 |
| `promptVersion` 与模型版本记入结果 | live 模式已实现记录，但无实际数据 | 同上 |

`P0-07` 已完成配置边界，脚本侧不再有代码层面的阻塞项，只等凭据。

本任务在真实调用完成前处于**部分完成**状态。校验器与构造样本本身不依赖凭据，可独立作为 `P3-02` 的回归测试基线。
