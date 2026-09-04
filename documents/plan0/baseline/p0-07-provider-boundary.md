# P0-07 Provider 配置边界与凭据轮换

对应 PLAN.md `P0-07`、契约 `AiModelProvider`（PLAN.md:138）与验收标准 7（PLAN.md:171）。

## 1. 前置核查结论

改造前核查了 `LLMUtil` 的实际使用情况，结论修正了缺陷清单中的一处影响判断。

`LLMUtil` 在全仓 `*.java` 中**没有任何生产调用点**。改造前对它的引用仅来自其自身的 `main()` 方法。`BehaviorProcessingService` 的导入清单不含它，全仓也不存在第二处大模型调用实现：`chat/completions`、`Bearer`、`OkHttpClient` 三个特征仅命中 `LLMUtil`、`VectorizationUtil` 与 `ApiVectorizationService`，后两者属 BERT 向量化路径。

由此：

- D-31（无重试）与 D-32（无 `temperature`）**当前不污染任何运行时链路**，它们在计划 3 接入真实大模型时才会生效。优先级依据不变（PLAN.md:169 要求保留重试结果，PLAN.md:185 将 Provider 不稳定列为预期风险），但不属于正在影响基线的缺陷。
- 现有链路中 Behavior 的 `type` / `status` / `dimension` 并非由大模型推断得出。这与 P0-02 根因二「`behavior.status` 恒空串导致定性得分恒 0.5」一致。
- D-21（硬编码凭据）的处置要求不变。无调用点不降低泄露风险：该 Key 已进入 git 历史，且经实测其身份仍被服务端接受。

## 2. 凭据处置

`LLMUtil.java:23` 原有明文 Key 已从源码移除。

**源码删除不等于吊销。** 该 Key 存在于 git 历史，任何能读取仓库的人都可取回。P0-06 的实测响应为：

```
HTTP 403
{"error":{"code":"account_overdue","message":"Access denied due to overdue account","type":"access_denied"}}
```

返回的是 `account_overdue` 而非 `invalid_token`，说明服务端已认出并接受该 Key 的身份，才会进入余额检查。欠费是账户状态，不是安全边界——账户充值后该 Key 立即恢复可用。

待用户执行的动作：

1. 在供应商控制台**吊销**该 Key。
2. 决定是否重写 git 历史清除该字符串。重写历史会改变已有提交哈希，影响所有克隆，须由用户本人决定，本次未执行。

## 3. 配置边界

按 OpenAI 兼容的 `/chat/completions` 协议定义契约。千帆 v2、DeepSeek、月之暗面、阿里百炼兼容模式、智谱共用同一请求与响应形状，因此更换供应商只调整配置，不改 Java 代码。

| 文件 | 职责 |
| --- | --- |
| `config/LlmProviderProperties.java` | `prefix = "llm"`，含端点、模型、凭据、温度、超时与重试策略 |
| `provider/AiChatProvider.java` | 稳定调用边界。业务代码只依赖本接口 |
| `provider/OpenAiCompatibleChatProvider.java` | 唯一实现。含重试、温度、错误分类与日志截断 |
| `provider/LlmProviderException.java` | 区分可重试与不可重试失败，保留 `httpStatus` |

`risk-warning-common` 被五个模块以 `scanBasePackages = {"...", "com.riskwarning.common"}` 整包扫描。若无条件注册 Provider Bean，`report`、`notification`、`org` 也会创建它。因此 `OpenAiCompatibleChatProvider` 与 `LLMUtil` 均标注 `@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true")`，且 `enabled` 默认 `false`。

`llm` 配置段只写入 `risk-warning-processing` 与 `risk-warning-knowledge` 两个模块的 `application.yml`，其余模块不引入无用配置。

凭据经 `${LLM_API_KEY:}` 注入，默认空值。`enabled=true` 而 `api-key`、`base-url`、`model` 任一缺失时，Provider 在构造阶段抛 `IllegalStateException` 使服务启动失败，避免带着空凭据发出必然失败的请求。

## 4. 缺陷修复

**D-32（无 `temperature`）**：请求体显式写入 `temperature`，默认 `0.0`。供应商默认值通常大于 0，会使同一 Prompt 多次调用结果不稳定，与固定 Prompt 验证的前提冲突。`buildBatchTagsInferencePrompt` 依赖输出顺序与输入严格对应，对确定性的要求更高。

**D-31（无重试）**：`chat` 按 `maxAttempts` 循环，退避为 `initialBackoffMillis × backoffMultiplier^n`。仅 `retryableStatusCodes`（默认 408/429/500/502/503/504）与 `IOException` 触发重试；其余失败一次终止。

重试与否的划分依据是失败性质而非严重程度：限流、网关错误、读超时属瞬时故障，重发有意义；参数非法、鉴权失败、账户欠费、响应结构缺失属确定性失败，重发只会得到同样结果并放大故障。**403 `account_overdue` 归入不可重试**——这正是当前阻塞 P0-06 的情况，对它重试三次毫无价值。

失败一律抛 `LlmProviderException`，不返回降级默认值，满足 PLAN.md:169「失败不会被静默吞掉」。

## 5. LLMUtil 改造范围

- 删除 `LLM_API_URL`、`MODEL`、`API_KEY` 三个常量与静态 `OkHttpClient`。
- 删除自建的 `callLLMApi`，改为委托注入的 `AiChatProvider`。
- 五个公开推断方法由 `static` 改为实例方法，移除 `throws IOException`（`LlmProviderException` 是 `RuntimeException`）。
- 删除 `main()`。它是依赖静态密钥的调试入口。
- **保留全部 Prompt 构建方法与 parse 方法，签名不变。** 它们是 P0-06 固定 Prompt 的来源，改动会使已完成的验证失效。

`parse*FromResponse` 中的降级行为（补齐默认值、`parseBatchTags` 异常时返回空列表）本次未改。它们是独立问题，属计划 3 的 AnalysisResult 校验范围，本任务不扩边界。

## 6. P0-06 脚本对齐

`test/fixtures/p0/prompt_validation_run.py` 的端点与模型改为读取 `LLM_BASE_URL` / `LLM_MODEL`，凭据环境变量由 `QIANFAN_API_KEY` 改为 `LLM_API_KEY`，与 Java 侧同一口径。三者任一缺失时 live 模式拒绝执行并列出缺失项。

## 7. 更换供应商步骤

无需修改任何 Java 代码：

```powershell
$env:LLM_ENABLED="true"
$env:LLM_BASE_URL="https://<供应商域名>/v1/chat/completions"
$env:LLM_MODEL="<模型标识>"
$env:LLM_API_KEY="<新Key>"
```

**注意：上述方式设置的变量在当前 PowerShell 会话内全局可见。** gateway 的 `scanBasePackages` 含 `com.riskwarning.common.utils`（内含 `LLMUtil`）但不含 `common.provider`，`LLM_ENABLED=true` 时 gateway 会因找不到 `AiChatProvider` Bean 启动失败。`P0-11` 已决议收窄 gateway 扫描范围以根治（见 `p0-11-review-record.md` 4.5）；在该改动落地前，只对需要 LLM 的服务进程设置这些变量，不要写入会话级或容器级全局环境。

若新供应商不兼容 OpenAI 协议，新增一个 `AiChatProvider` 实现并用条件注解区分，调用方不受影响。

## 8. 验证

| 项目 | 结果 |
| --- | --- |
| `mvn -pl risk-warning-common -am test` | BUILD SUCCESS，`Tests run: 10, Failures: 0, Errors: 0` |
| `mvn clean package -DskipTests` | BUILD SUCCESS，七模块全部通过 |
| `grep ALTAK\|bce-v3` 全仓 | 0 命中 |
| P0-06 offline 复跑 | 14/14 符合预期，与改造前一致 |
| P0-06 live 守卫 | 缺失环境变量时拒绝执行并退出码 1 |

`OpenAiCompatibleChatProviderTest` 用 JDK 内置 `HttpServer` 作桩，不引入新网络依赖。10 个用例覆盖：成功响应且 `temperature` 出现在请求体、429 重试后成功、503 达上限抛可重试异常且尝试次数为 3、403 `account_overdue` 不重试且仅请求 1 次、400 不重试、缺 `choices`、非法 JSON、缺凭据拒绝创建、缺端点拒绝创建、空 Prompt 拒绝。每个用例断言实际请求次数或请求体内容，而非仅断言返回值。

`risk-warning-common` 原先没有测试依赖，本次新增 `spring-boot-starter-test`（`test` 作用域，版本由父 pom 的 `spring-boot-dependencies` 管理）。

## 9. 未完成项

- 旧 Key 的供应商侧吊销（须用户在控制台执行）。
- git 历史清除决策（须用户决定是否重写历史）。
- 真实调用验证：等用户更换平台并提供新凭据后，执行 `--mode live --runs 10` 完成 P0-06。
- `parse*FromResponse` 的降级语义，留计划 3。
