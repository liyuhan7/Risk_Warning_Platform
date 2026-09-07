# P1-02 执行计划（EvidenceChunk 权威存储）

> 拟定日期：2026-09-05；执行前由用户评审确认。
> 依据：[PLAN](../PLAN.md)（§7/§8/§10）、[P0-05 冻结契约](../plan0/baseline/p0-05-core-schemas.md)（第四节、U-02/U-11）、[P1 对接说明](p1-handoff.md)、[P1-01 执行记录](p1-01-execution.md)。
> 已确认决策：P1-02 只建 PostgreSQL 权威存储，不建 ES 检索副本、不做 Evidence 向量化；先给 JSONL 段记录补充 `fileName` 再落库。

## 1. 目标与验收口径

按 PLAN §8 `P1-02`（B）：实现 `EvidenceChunk` 对象、ID/哈希策略、持久化和查询。

本计划完成后应满足（对应 PLAN §10 验收 1 的 Evidence 部分）：

1. 固定 PDF 上传后，能生成非空 `EvidenceChunk` 列表并真实写入 PostgreSQL；每个 Chunk 有文件标识（`sourceDocumentId`）、正确页码、段序号和原文。
2. 同一 `sourceDocumentId` 重复解析产出相同证据 ID，写入退化为覆盖（幂等），不重复累加。
3. 查询接口能按 `sourceDocumentId` 稳定取回全部证据（含页码、段序号、原文），供 P1-05 事实抽取与 P1-08 前端定位消费。
4. 迁移与实体映射通过专用 PostgreSQL 库真实写入验证，不依赖 Hibernate 自动建表。

## 2. 冻结契约摘要（以 p0-05 第四节为唯一权威，代码不得偏离）

### 2.1 字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `schemaVersion` | M | 固定 `"1.0"` |
| `id` | M | 稳定证据 ID，规则见 2.2，**不含 analysisRunId**（U-11） |
| `sourceDocumentId` | M | `ProjectFile.id`，归属主体 |
| `projectId` / `assessmentId` | M | 冗余自 `ProjectFile`（写入时复制，禁止独立更新）；`assessmentId` 语义 = 上传批次，非分析运行 |
| `sourceFileName` | M | 原始文件名，前端展示用 |
| `pageNumber` | C | 1 起；分页格式必填，纯文本可为空 |
| `segmentIndex` | M | 页内段序号 0 起；无分页时为全文段序号 |
| `charStart` / `charEnd` | O | 成对存在或同时为空 |
| `text` | M | 原文，不得改写/摘要/翻译 |
| `textHash` | M | 归一化后 SHA-256，hex 小写 |
| `createdAt` | M | 生成时间 |

### 2.2 ID 与哈希规则（p0-05 §4.1，照抄实现）

- 归一化三步：去首尾空白 → 连续空白折叠为单个半角空格 → 统一换行为 `\n`。不做大小写转换、不删标点。
- `textHash` = SHA-256(归一化文本)，hex 小写 64 位。
- `id` = sha256(`sourceDocumentId + "|" + pageNumber + "|" + segmentIndex + "|" + textHash`) 取**前 32 位 hex**；`sourceDocumentId` 用十进制字符串；`pageNumber` 为空时以空串参与。

### 2.3 校验（p0-05 §4.2）

1. `text` 去空白后长度 ≥ 1，否则丢弃该段并计入解析错误，不写空证据。
2. `textHash` 与 `text` 重算一致，不一致视为数据损坏。
3. `id` 与规则重算一致。
4. `charStart`/`charEnd` 成对存在或同时为空。
5. `(sourceDocumentId, pageNumber, segmentIndex)` 唯一（唯一键不含 assessmentId）。

### 2.4 存储与生命周期（p0-05 §4.3/4.4/4.5）

- PostgreSQL 为唯一真相源（`U-02`）；本计划不建 ES 检索副本、不向量化（副本与向量化挂账 P2/P3，见第 3 节）。
- 写入顺序若未来出现 ES 副本，须先 PG 后 ES；本计划无副本，不适用。
- 清理证据的唯一合法条件 = 源文档删除；运行失败、结果替换、重跑均不删不改证据（4.5 约束四）。
- 证据 `projectId/assessmentId` 从 `ProjectFile` 复制；写入前须确认该文件属于本次作用域（复用 `ProjectFileRepository.findByIdAndProjectIdAndAssessmentId`）。

### 2.5 幂等边界（p0-05 §4.1.1）

确定性 ID 只对**同一 `sourceDocumentId` 的重复解析**幂等；同一物理文件二次上传（产生新 `sourceDocumentId`）不去重（属上传链 F-03/P1-06，不在本计划）。

## 3. 与 RAG / ES 检索副本的边界

- 本计划只交付 PG 权威存储。ES 检索副本（`id`、作用域字段、`text`、向量字段，U-02 §4.4）与 Evidence 向量化**不在本计划**——当前没有任何检索消费方，先建会违反"单一真相源 + 副本须有覆盖率校验"约束。
- "Evidence 的 ES 副本与检索场景何时建"登记为 P2/P3 前置未决（早期愿景中的"更细粒度 Evidence 检索"未进入 PLAN 主表）。
- 本计划为未来检索预留的只有：原文完整、稳定 ID、页码/段序、归一化哈希——保证任何后续"以证据为上下文或检索源"的场景可稳定回指。

## 4. 输入现状与缺口

- P1-01 已提供：段级 UTF-8 JSONL（每行含 `sourceDocumentId/pageNumber/segmentIndex/text`）、确定性内部文件名、`SourceDocumentScopeValidator`（文件三键归属校验）、专用 PG 测试库流程。
- 缺口 A：JSONL 段记录**缺 `sourceFileName`**（`ProjectFile` 无 fileName 列）→ 需在 `DocumentProcessingService.writeSegments` 写入 `fileName`（`FileGetter.FileMetadata.getFileName()` 已可得），`DocumentSegmentRecord` 增加字段。
- 缺口 B：`charStart/charEnd` 是否可可靠获得取决于 `ContentExtractor.TextSegment` 是否含偏移（实现时核对；无法可靠获得则保持为空，不阻塞）。
- 缺口 C：从 JSONL 生成证据的落库执行者与调用点尚不存在（本计划 T5 交付）。

## 5. 任务拆分（一轮一个小任务，编号保留不表示并行）

### T1：JSONL 段记录补充 fileName（回改 P1-01 产物）

- 修改 `DocumentSegmentRecord`：新增可空 `fileName`。
- 修改 `DocumentProcessingService.writeSegments`：记录 `metadata.getFileName()`。
- 核对 `LineProcessor.parseRecord` 兼容（新字段对 JSON 解析无破坏；旧纯文本回退分支不变）。
- 更新测试：`DocumentProcessingServiceTest` 断言每行含 fileName；`LineProcessorScopeTest` 保持通过。
- 完成标准：JSONL 每行可反序列化为含 fileName 的段记录；现有 29+ 项 P1 定向测试无回归。

### T2：EvidenceChunk 实体与 ID/哈希工厂（common，不依赖 DB）

- 新增 `common/po/evidence/EvidenceChunk`（JPA 实体，`@Table("t_evidence_chunk")`；参照 `AnalysisRun` 的 PO 风格，schemaVersion 固定 "1.0"；字段映射见 2.1）。
- 新增证据哈希/校验工具（建议 `common` 内独立类）：归一化（2.2 三步）、`textHash`、`id`（前 32 hex）、字段校验（2.3）。
- 单元测试：
  - 归一化样例：`\r\n` 换行、连续空格、首尾空白、中文标点保留、大小写不折叠、`pageNumber` 为空。
  - golden 值：固定输入产出固定 `textHash` 与 `id`（测试里固化样例值，防实现漂移）。
  - 校验：空文本拒绝、char 不成对拒绝、`pageNumber < 1` 拒绝、id/textHash 与重算不一致拒绝。
- 完成标准：哈希与 ID 实现与 p0-05 §4.1 逐字一致，测试固化 golden。

### T3：迁移与约束回归（DDL 冻结）

- 新增 `documents/plan1/migrations/003_evidence_chunk.sql`（单事务、一次性执行；风格同 001/002）。DDL 草案见第 8 节，执行 T3 时冻结字段与约束。
- 新增 `test/fixtures/p1/evidence_chunk_constraints.sql`：专用空库验证——唯一键、外键归属、char 成对 CHECK、textHash 格式、同 id 覆盖（`INSERT ... ON CONFLICT (id) DO UPDATE` 语义复现应用层写入策略）。
- 完成标准：fixture 在专用空 PostgreSQL 库执行通过；业务库**不**执行（与 001/002 同一复验纪律）。

### T4：Repository 与持久化测试（processing）

- 新增 `processing/repository/EvidenceChunkRepository`：`findBySourceDocumentId`（按 pageNumber、segmentIndex 排序，支持分页）、`findById`（继承）、`existsById`；按需 `findByIdIn`（供 P1-03 evidenceIds 完整性校验，可在 P1-03 加）。
- 新增持久化测试（专用 PG，沿用 `P1_TEST_DATABASE_URL` 显式跳过纪律）：真实写入/按文件查询/同 id 覆盖幂等/同位置异 id 冲突行为/约束拒绝。
- 完成标准：迁移 + 实体 + Repository 在专用库上真实写入与查询通过；Hibernate validate 通过。

### T5：EvidenceExtractionService 落库接线（processing，垂直打通）

- 新增 `processing/service/EvidenceExtractionService`：
  - 输入沿用冻结接口形态 `extract(AnalysisScope scope, List<SourceDocumentRef> documents)` 对应的内部文件集合（读取 `ProcessedDocument.internalFilePath` 的 JSONL）；
  - 逐行解析段记录 → 2.3 校验 → 计算 id/textHash → 按第 6 节写入策略落 PG → 收集"丢弃的空段、损坏行、同位置冲突"为解析错误；
  - 每文件先经归属校验（复用 `SourceDocumentScopeValidator` / `ProjectFileRepository`），`projectId/assessmentId/sourceFileName` 从 `ProjectFile` + JSONL 获取。
- `MessageTask` 接线：`processDocuments` 之后、`runBatchJob` 之前调用（证据先于行为落库；证据异常走既有 `markRunFailed` 门禁；extract 返回的部分解析错误记录日志与结果，不中止 run）。
- 测试：
  - 单元（Mockito）：读临时 JSONL → 生成的 chunk 字段/id/hash 正确；坏行与空文本行进错误列表；文件归属错配拒绝。
  - 回归：`MessageTaskScopeTest` 增补接线相关断言（如有必要）；Batch 终态门禁测试不受影响。
- 完成标准：`processDocuments → Evidence 落库 → Batch` 顺序固定；定向测试全绿。

### T6（不在本计划，登记交接）

- ES 检索副本与向量化 → P2/P3 前置未决。
- `sourceFileName` 为空的历史文件回填、跨上传内容去重 → F-03 / P1-06。
- Report/前端按 `evidenceIds` 回查证据的读取接口 → 消费方（P1-08 前端 Mock、P1-03 校验）出现后再按模块复制 Repository。

## 6. 写入与幂等策略（T5 实现依据，评审点）

主键 = 确定性 `id`。写入逐段执行：

1. 按 `(sourceDocumentId, pageNumber, segmentIndex)` 查询现有行：
   - 不存在 → 插入；
   - 存在且 `id` 相同 → 覆盖更新（等价幂等，重复解析场景）；
   - 存在且 `id` 不同（同位置文本漂移，正常重跑不应发生）→ **记解析错误，不改动现有行**。
2. 校验失败（2.3）的段一律不写，进错误列表。
3. 注意：`pageNumber` 可空，PostgreSQL 唯一索引对 NULL 不判重，应用层查询承担主要去重；DB 唯一键作为非 NULL page 时的兜底。

## 7. 涉及文件清单

新增（主代码）：
- `risk-warning-common/src/main/java/com/riskwarning/common/po/evidence/EvidenceChunk.java`
- `risk-warning-common/src/main/java/com/riskwarning/common/…/EvidenceHash.java`（命名在 T2 冻结）
- `risk-warning-processing/.../repository/EvidenceChunkRepository.java`
- `risk-warning-processing/.../service/EvidenceExtractionService.java`

修改：
- `risk-warning-processing/.../entity/dto/DocumentSegmentRecord.java`（加 fileName）
- `risk-warning-processing/.../service/DocumentProcessingService.java`（writeSegments 写 fileName）
- `risk-warning-processing/.../task/MessageTask.java`（接线 evidence 落库步骤）

新增（文档/测试/迁移）：
- `documents/plan1/migrations/003_evidence_chunk.sql`
- `test/fixtures/p1/evidence_chunk_constraints.sql`
- 测试：common（哈希/校验）、processing（持久化、extract 服务、DocumentProcessingServiceTest 更新）

## 8. DDL 草案（T3 冻结时以本稿为基础评审）

```sql
BEGIN;
CREATE TABLE public.t_evidence_chunk (
    id VARCHAR(32) PRIMARY KEY,
    schema_version VARCHAR(16) NOT NULL,
    source_document_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    source_file_name TEXT NOT NULL,
    page_number INTEGER,
    segment_index INTEGER NOT NULL,
    char_start INTEGER,
    char_end INTEGER,
    text TEXT NOT NULL,
    text_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_evidence_document_location
        UNIQUE (source_document_id, page_number, segment_index),
    CONSTRAINT fk_evidence_source_document
        FOREIGN KEY (source_document_id) REFERENCES public.t_project_file (id),
    CONSTRAINT fk_evidence_assessment_project
        FOREIGN KEY (assessment_id, project_id)
        REFERENCES public.t_assessment_result (id, project_id),
    CONSTRAINT ck_evidence_page_number CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT ck_evidence_segment_index CHECK (segment_index >= 0),
    CONSTRAINT ck_evidence_char_range CHECK (
        (char_start IS NULL AND char_end IS NULL)
        OR (char_start IS NOT NULL AND char_end IS NOT NULL AND char_end > char_start)),
    CONSTRAINT ck_evidence_text_hash CHECK (text_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_evidence_source_document ON public.t_evidence_chunk (source_document_id);
COMMENT ON TABLE public.t_evidence_chunk IS '文档级证据资产，PG 为唯一真相源，不含运行身份';
COMMIT;
```

评审点：`schema_version` 是否设默认 '1.0'；`fk_evidence_assessment_project` 是否与 ProjectFile 的既有组合外键构成冗余（倾向保留，防归属漂移，与 001 风格一致）；`text_hash` 正则校验是否接受。

## 9. 测试计划汇总

- 单元（无外部依赖）：归一化/hash/id golden、字段校验、extract 服务（Mockito）、JSONL fileName 回归、Batch 门禁回归。
- 专用 PostgreSQL：`EvidenceChunkPersistenceTest`（写入/幂等/冲突/约束/查询）、fixture `evidence_chunk_constraints.sql`。
- 命令：
  ```powershell
  mvn -q -pl risk-warning-processing -am test '-Dtest=EvidenceHashTest,EvidenceChunkValidationTest,EvidenceExtractionServiceTest,EvidenceChunkPersistenceTest,DocumentProcessingServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
  ```
  未设 `P1_TEST_DATABASE_URL` 时数据库测试显式跳过，跳过不计作通过。

## 10. 完成标准与不做清单

完成标准：
1. 契约字段/ID/哈希与 p0-05 §4.1 逐字一致，golden 测试固化。
2. JSONL → PG 证据真实写入打通（专用库验证），重复解析幂等。
3. 按 `sourceDocumentId` 查询可用，P1-05/P1-08 的读取依赖具备。
4. P1-01 定向测试无回归；`git diff --check` 通过。

不做：
- 不建 ES 检索副本、不向量化、不做 Evidence 检索接口（P2/P3）。
- 不实现 LLM Fact Extraction（P1-04/05）。
- 不做上传内容去重与历史文件回填（F-03/P1-06）。
- 不把证据写入挂到运行成败上（4.5 约束四）；运行失败门禁只终止 run 记录，不删证据。

## 11. 风险与未决（进执行记录跟踪）

1. 同位置文本漂移（解析器升级）的处理策略——本计划定为"记错误不改动"，若 P1-10 出现真实漂移再评审。
2. `charStart/charEnd` 可用性依赖 `ContentExtractor.TextSegment`，T2/T5 核对后决定留空或填充。
3. 失败 run 的消息重投语义（run 已 FAILED 后重投仍会重跑全链）→ 交接 P1-06。
4. 业务库 001/002/003 迁移与真实 Kafka 全链验证 → P1-10 阶段验收前置。
