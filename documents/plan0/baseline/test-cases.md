# P0 固定测试案例（test-cases）

> 状态：材料与金标准已生成；`projectId` 与 `fileHash` 已人工审批冻结；`assessmentId` 待清库重跑后回填。
> 生成原则 v2：**金标准严格依据 `documents/data/indicator.json` 的真实可执行指标规则机械推导**，人工只负责撰写触发句子并复核其唯一性。

## 1. 生成原则（v2，替代早期基于外部 PDF 的方案）

1. 每个事实段落对应 `indicator.json` 中一条真实存在的三级 BINARY 指标，`expected.json` 记录完整 `indicatorId`；
2. 预期得分（trueScore/falseScore）与风险触发（staticThreshold 比较）由 `generate_fixtures.py` 从规则**机械计算**，禁止人工填写数值；
3. 句子的唯一职责是让 BINARY 条件真假**唯一可判**，并内嵌指标名称关键词以命中向量检索；
4. CASE-001 与 CASE-002 使用**同一批 8 个指标做镜像对照**：同一规则、相反事实、相反结论，任何评分链路改动都会在两侧同时显影；
5. CASE-003 仅提及指标主题、不含可判定证据，`expectedConditionAnswer/expectedScore/expectedRiskTriggered` 均为 null。

早期基于《虚拟企业材料与案例》PDF 构造的三份材料已废弃（判定依赖模型常识，无法机械验证），PDF 案例保留作为 P2 之后扩库参考素材。

## 2. 案例清单

| Case | 类型 | 虚构企业 | 事实段落数 | 覆盖维度 | 期望整体状态 |
|---|---|---|---:|---|---|
| CASE-001 | 明确不合规 | 宏图电子制造有限公司 | 8 | 产品合规×6、劳务×2 | NON_COMPLIANT |
| CASE-002 | 明确合规 | 正源智能装备有限公司 | 8 | 同上（镜像） | COMPLIANT |
| CASE-003 | 证据不足 | 云枢信息科技有限公司 | 6 | 同上子集 | INSUFFICIENT_EVIDENCE |

## 3. 指标映射表（脚本自动生成，禁止手改）

| 指标（前缀） | 名称 | 维度 | C-001 答/得分/风险 | C-002 答/得分/风险 |
|---|---|---|---|---|
| 25c2262a | 生产是否取得排污许可证 | 产品合规 | F / 0.0 / 触发 | T / 2.0 / 不触发 |
| 150e2357 | 欧盟RoHS 2.0十项物质检测是否合格 | 产品合规 | F / 0.0 / 触发 | T / 1.0 / 不触发 |
| edb74ec5 | 加班时长是否超过法定最高时长及频率 | 劳务合规 | T / 0.0 / 触发 | F / 0.8 / 不触发 |
| 8210bf7d | 是否按法定社保缴费基数缴纳社保 | 劳务合规 | F / 0.0 / 触发 | T / 1.0 / 不触发 |
| 76783faa | 是否符合欧盟CE认证要求 | 产品合规 | F / 0.0 / 触发 | T / 1.0 / 不触发 |
| 63c52b9d | 是否符合《数据安全法》数据出境要求 | 产品合规 | F / 0.0 / 触发 | T / 2.0 / 不触发 |
| f8ab34b2 | 生产过程是否设置不合格品处理程序 | 产品合规 | F / 0.0 / 触发 | T / 1.0 / 不触发 |
| e43ca387 | CCC认证标志使用是否规范 | 产品合规 | F / 0.0 / 触发 | T / 1.0 / 不触发 |

CASE-003 涉及其中 6 个指标的主题（全部答=null/得分未知）。

注意 edb74ec5 为反向计分指标（条件成立=超时加班发生 → trueScore=0.0），CASE-002 中条件不成立得 falseScore=0.8。

### 法规锚定

每个事实的 `expectedRegulations` 已锚定 `regulation.json` 中真实存在的法规件文（脚本按名称子串解析，缺失即报错）：
排污许可管理条例-按证排污 / 欧盟RoHS指令-铅含量限制·有害物质限量 / 劳动法-工时制度规定 / 社会保险法-社保缴纳 / 欧盟CE认证要求 / 数据出境安全评估办法·GDPR-SCC / 产品质量法-不合格产品 / 强制性产品认证管理规定(CCC)。

已知限制（回归比对时注意）：
1. **存在性已验证 ≠ 向量召回必中**。旧链行为↔法规用 KNN Top10 + 相似度阈值 0.15，且法规种子 tags 大多为空（Jaccard 分量≈0），能否命中须在 P0-02 实测 `03-regulation-retrieval.json`；
2. `regulation.json` 全部 4865 条**无 id 字段**，expected.json 只能以名称锚定，导入 ES 后需按名称映射回系统生成的 id 再比对。

## 4. 使用与回归门禁

- 上传链路跑完后核对 `t_behavior`：每案例 Behavior 数 = 背景段 + 事实段数（9/9/7）；
- 判分口径以 `expected.json` 的机器推导字段为准：逐 fact 比对系统 IndicatorResult 得分与风险触发；
- 回归红线：CASE-001 八项须全部触发风险、CASE-002 八项须全部不触发、CASE-003 不得输出确定的合规/违规结论；
- 复核人只需审查一件事：每个句子是否使对应条件的真假唯一确定（无歧义、无多解）。复核后在本文件登记姓名与日期即视为冻结。

## 5. 固定 ID 与隔离实验约定

### 已冻结标识（人工审批，禁止改动）

| Case | projectId | enterpriseId | source.docx SHA-256 | assessmentId |
|---|---:|---:|---|---|
| CASE-001 | 3 | 3 | `867a8033b0a6aebba673d9315021052c5b77575f1312fa386dfab050e0f71cde` | 32 |
| CASE-002 | 4 | 4 | `50584398ecc1408652fffba3a0d1aaf30cfa98648f4f144261b5f0e9a34038a0` | 33 |
| CASE-003 | 5 | 5 | `1cdf98fa5abbb81a5074d1f596a8b2c1c623f564ec0cadd248` | 34 |

首轮 Baseline 实测结果见 `p0-02-baseline.md`。

`fileHash` 取 `source.docx` 内容的 SHA-256。系统的 `fileHash` 由前端计算、仅用于 Redis 去重（`project:%d:file:hash`，TTL 24h）且不落库，因此以 fixture 文件内容哈希作为唯一权威值；已通过字节级比对确认 `storage/persist` 下的上传文件与 fixture 完全一致。

`generate_fixtures.py` 中 `FROZEN_PROJECT_IDS` / `FROZEN_ASSESSMENT_IDS` 维护上述映射，且当 `source.docx` 已存在时不再重写——python-docx 每次保存都会更新时间戳元数据导致哈希漂移。需要修改材料时必须显式删除 docx 并同步作废已冻结的 `fileHash`。

- 三案例复用固定 Project 3 / 4 / 5（企业 3 / 4 / 5），禁止复用种子 `project_id=1、2`；
- 数据隔离实验（P0-03）：另建 Project 下两个 Assessment 各传一句独有事实 docx，验证 `fetchBehaviors(projectId)` 是否串数据。

## 6. 已知旧链缺陷预警（Baseline 观察点）

| 观察点 | 预期表现 | 根因 |
|---|---|---|
| Behavior.quantitativeData | 恒为 0.0 | `LineProcessor` 固定赋值，全仓库无数值解析代码 |
| Behavior.status | 恒为空串→UNKNOWN | BERT 分类服务不返回 status |
| 定性矩阵 | 全落默认分 0.5 | status UNKNOWN |

### 已修复：短文档静默产出 0 条 Behavior（阻塞 Baseline 采集）

首次上传三个案例时全部得到 `No behaviors found`，ES 零写入。定位为两处缺陷叠加：

1. `ContentExtractor.isMeaningful` 用 `text.matches(".*[\u4e00-\u9fa5].*")` 检测中文——Java 的 `matches` 要求整串匹配且点号不匹配换行，含换行片段被误判为无中文而整体丢弃；
2. `FileScanner.scanWordByParagraph` 用单换行拼接段落，而分段按 `\n\n+`，导致全文 ≤500 字符时整篇成为一个含换行片段，必然触发上一条。

现象：全文 >500 字符的文档因触发句子拆分而侥幸正常（4 月的 1606 字符材料成功产出 29 行），全文较短的文档静默产出 0 行。

修复：中文检测改用 `Pattern.find`；段落拼接改为双换行。已补 `ContentExtractorTest`（3 例，含修复前必红的换行用例）。修复后三案例产出片段数 9 / 9 / 7，与预期段落数一致。

本测试集全部选用 BINARY 指标，正是为了绕开定量路径缺陷、让旧链结果可解释；不得为迁就缺陷修改材料。

### 已知缺陷：重复上传导致行为重复入库

上传链路对同一文件不做幂等清理，重传 N 次即在 `t_behavior` 产生 N 倍行为。首轮采集时 CASE-001 上传 4 次，得到 27 条行为（9 段 × 3 次有效解析），CASE-002 / 003 各 2 次但仅 9 / 7 条。

实测该污染**不改变分数**：同一指标下多行为取平均（`BehaviorProcessingService.java:412`），清理前后 36 个指标的 ID 与分数逐位相同（详见 `p0-02-baseline.md` 第 2.2 节）。但行为数偏离预期会使「Behavior 数 = 背景段 + 事实段」这一门禁失效，仍须在每次采集前清零。

处置：2026-08-27 清空三案例的 `t_behavior`（43 条）与 `t_assessment_result`（8 条，级联删除 105 条 `t_indicator_result`），保留 Project 与 Enterprise 实体使 projectId 冻结值不变；备份位于 `test/fixtures/p0/_backup_20260827_152505/`。后续每次采集前须确认目标 project 行为数为 0。

## 7. 完成判据核对

- [x] 三案例材料 + 机器推导金标准入库（本目录）
- [x] 人工复核句子唯一性并审批（用户审批，2026-08-27）
- [x] 实际走 FileController 分片上传，回填 projectId 与 fileHash（SHA-256）
- [x] 清库重跑后回填 assessmentId（32 / 33 / 34），行为数 9/9/7 与预期一致
- [ ] 团队任一成员可凭本目录复现同一输入并理解预期依据
