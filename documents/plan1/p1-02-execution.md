# P1-02 执行记录

> 开工日期：2026-09-05；分支：main。目标为 EvidenceChunk 的稳定身份、PostgreSQL 权威存储和受作用域保护的查询。

## 已实现

- `EvidenceChunk` 是文档级实体，不含 `analysisRunId`；包含冻结契约的 schemaVersion、源文件/项目/上传评估、文件名、页码、段号、字符位置、原文、文本哈希和创建时间。
- 原文规范化严格执行三步：统一换行、去首尾空白、连续空白折叠为半角空格。`textHash` 为小写 SHA-256，证据 ID 取 `sha256(sourceDocumentId|pageNumber|segmentIndex|textHash)` 前 32 位。
- [003_evidence_chunk.sql](migrations/003_evidence_chunk.sql) 建立 `t_evidence_chunk`，以 `(sourceDocumentId, projectId, assessmentId)` 组合外键绑定上传文件归属；通过表达式唯一索引覆盖页码为空时的同文件位置唯一性。
- `EvidenceExtractionService` 在旧 Batch 前读取 JSONL 分段、校验源文件归属后持久化 Evidence；重复解析同一源文件和位置会得到相同 ID。查询入口再次校验文件归属，并对读回记录复核哈希和 ID。

## A1 原始文件名采集（2026-09-05）

- 上传初始化请求新增必填 `fileName`，在 Redis 上传任务中以 `originalFileName` 保存；`UploadFileDto` 的既有 `serialVersionUID` 保持不变，因此旧 Redis 值反序列化后该字段为 `null`，仍可完成兼容链路。
- [004_project_file_original_name.sql](migrations/004_project_file_original_name.sql) 为 `t_project_file` 新增可空 `original_file_name`。`ExecuteQueueTask` 合并文件后将它写入一文件一行的 `ProjectFile`；系统生成的 `filePath` 规则没有变化。
- Evidence 写入优先使用 `ProjectFile.originalFileName`。历史记录为 `null` 时记录告警并降级使用 `filePath` 的 basename；该展示字段不参与 Evidence 的稳定 ID 或文本哈希。
- `fileType` 仍作为受控的文件后缀字段校验；原始名暂不做后缀强一致性阻断，避免前端格式差异影响上传。文件名只校验非空、最大 512 字符、无路径分隔符和控制字符。
- 前端独立仓库需在 `/file/initUpload` 请求体增加 `fileName`，这是后端 API 契约变更，尚未在本工作区实现。

## 边界

- PostgreSQL 是唯一权威源；本任务未写 ES 检索副本，ES 补写、失败记录和覆盖率验证留给后续检索任务。
- Evidence 只记录文件上传所属的 `assessmentId`，不记录哪次 `analysisRunId` 使用它；运行级引用由 P1-03 的 Behavior 实现。
- 业务数据库尚未应用 003、004 迁移，当前运行环境不能启用 Evidence 写入及原始文件名落库。

## 验证

- `EvidenceChunkTest` 2 项：规范化文本下稳定哈希/ID、位置校验。
- `EvidenceExtractionServiceTest` 4 项：JSONL 持久化、跨文件分段拒绝、受作用域查询与完整性复核、历史文件名回退。
- `FileServiceImplTest` 2 项：原始文件名写入 Redis 上传任务、路径形式的原始名拒绝。
- `EvidenceChunkRepositoryPersistenceTest`：专用 PostgreSQL 15 临时库上通过 Hibernate validate、实际写入和按源文件排序查询。
- 真实 PostgreSQL 验证曾发现 `text_hash` 的 `CHAR(64)` 与 JPA `VARCHAR(64)` 不一致；迁移已改为 `VARCHAR(64)` 并复验通过。

未在真实 PDF、业务 PostgreSQL 或 ES 上执行端到端验收；P1-03 之前不能声称 Evidence 已被 Behavior 引用。

## 评审结论（2026-09-05，有条件通过）

主体与 p0-05 冻结契约吻合、测试可复现（EvidenceChunkTest 2、EvidenceExtractionServiceTest 4、FileServiceImplTest 2、持久化测试在专用 PG 通过），具备进入 P1-03 的代码条件。放行条件与遗留如下。

### 已处理

- 归一化"连续空白（含换行）折叠为单个半角空格"语义已回写 [p0-05 §4.1](../plan0/baseline/p0-05-core-schemas.md)；`normalizeText` 行为不变，golden 已固化。

### 遗留 A1：原始文件名采集 —— 已完成（后端部分）

`EvidenceChunk.sourceFileName` 原取 `ProjectFile.filePath` basename（系统生成名）。已补齐采集：`/file/initUpload` 必填 `fileName` → `UploadFileDto.originalFileName` → `ProjectFile.original_file_name`（[004 迁移](migrations/004_project_file_original_name.sql)，可空，历史行 NULL）→ `EvidenceExtractionService` 优先读原始名，历史行缺失时回退存储路径 basename 并 WARN。未强制 fileName 与 fileType 后缀一致，仅校验非空、≤512、无路径分隔符与控制字符。前端仓库须在 `/initUpload` 增加 `fileName` 字段（独立仓库待同步）。测试：`FileServiceImplTest` 2 项、`EvidenceExtractionServiceTest` 追加"原始名优先/历史回退"用例共 4 项、`ProjectFileRepositoryPersistenceTest` 断言原始名持久化。P1-08 前端展示前确认前端已传 fileName。

### 计划 vs 实现差异（登记）

1. T1"JSONL 段记录补充 fileName"未执行：证据展示名改由 `ProjectFile.original_file_name` 承载（A1 已落地），JSONL 无需携带文件名。
2. 写入策略与计划不同：实现为直接 `saveAll`，同位置异 id 会触发唯一索引冲突并整链回滚（走 MessageTask 门禁标 FAILED），而非计划的"记解析错误、不改动现有行"；正常重复解析幂等成立（同 id 覆盖，persistence 测试验证）。
3. 计划 T3 的 `test/fixtures/p1/evidence_chunk_constraints.sql` 未交付，约束由 `EvidenceChunkRepositoryPersistenceTest` 覆盖；该测试注释须补充"先执行 003_evidence_chunk.sql"。
4. 服务方法签名与 PLAN §7 冻结接口 `EvidenceExtractionService.extract(scope, document)`（返回列表 + 解析错误）不一致，当前为 `extractAndPersist(scope, documents)`/`findBySourceDocument`，错误以异常表达；P1-04/05 接线前须对齐。
5. `MessageTask` 忽略 `extractAndPersist` 返回值：异常已由运行门禁覆盖；空文件 0 证据不中止 run（后续指标阶段因无行为 FAILED），行为可接受。
