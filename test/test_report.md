 # Risk-Warning-Platform 全流程实战手册 (CLI 细化版)

> [!IMPORTANT]
> 本手册提供了从物理环境初始化、微服务配置锁定、到核心 AI 链路测试的完整命令行指令集。建议按顺序执行。

---

## 阶段 1：基础设施初始化 (The Seed Stage)

### 1.1 PostgreSQL 数据库初始化
*   **目标**：构建物理表结构体系。
*   **命令 (CMD/PowerShell)**：
    ```powershell
    # 请确保 psql 已在环境变量中，且 Postgres 服务已启动
    psql -U postgres -d postgres -f "d:\大创\Risk_Warning_Platform\risk-warning-common\src\main\resources\sql\schema.sql"
    ```
*   **成功标志**：日志中输出一连串 `CREATE TABLE` 与 `CREATE INDEX`。

### 1.2 Elasticsearch 原始元数据注入
*   **目标**：导入 6609 条未向量化的原始业务数据。
*   **命令 (项目根目录)**：
    ```powershell
    python d:\大创\Risk_Warning_Platform\init_es.py
    ```
*   **验证指令**：
    ```powershell
    curl.exe -s http://localhost:9200/_cat/indices?v
    ```
*   **预期基准**：`t_indicator` (1144), `t_regulation` (4865), `t_behavior` (600)。

---

## 阶段 2：环境强力隔离 (The Isolation Stage)

### 2.1 Nacos 配置隔离修正
*   **目标**：强制微服务从您的私有命名空间拉取配置。
*   **操作文件**：`risk-warning-common/src/main/resources/bootstrap-common.yml`
*   **关键代码**：确保 `config:` 下的 `namespace` 参数正确。



## 阶段 3：核心功能冒烟测试 (Functionality Check)

### 3.1 用户鉴权登录 (获取凭证)
*   **目标**：验证 Auth 服务连通性并获取测试所需 Token。
*   **命令**：
    ```powershell
    curl.exe -X POST "http://localhost:8088/api/auth/login" `
         -H "Content-Type: application/json" `
         -d "{\"username\":\"admin\",\"password\":\"123456\"}"
    ```
*   **成功标志**：返回包含 `"token": "eyJhbGci..."` 的 JSON 字符串。

### 3.2 跨模块路由转发校验
*   **目标**：通过网关分发请求到指定微服务。
*   **命令 (验证报表微服务)**：
    ```powershell
    curl.exe -X GET "http://localhost:8088/api/report/indicators"
    ```

---

## 4. AI 智能特征同步 (AI Synergy Stage)

### 4.1 触发全量 AI 向量化处理
*   **目标**：启动 BERT 模型为 6600+ 条数据注入“灵魂”。
*   **命令**：
    ```powershell
    curl.exe -X POST "http://localhost:8088/api/knowledge/vectorization/all" `
         -H "Authorization: Bearer <您的TOKEN>"
    ```
*   **监控点**：
    - (Knowledge 日志): `处理进度: XXX/4865`
    - (BERT 日志): `Python-Service INFO: ... "POST /vectorize" 200`

### 4.2 特征持久化验证
*   **目标**：确认高维向量数据已落库。
*   **检测指令**：
    ```powershell
    curl.exe -s -X GET "http://localhost:9200/t_indicator/_search?size=1" | findstr "name_vector"
    ```
*   **成功标志**：终端显示包含大量浮点数的向量数组，代表语义提取成功。

---

## 5. 常见运维异常 (Operations & Maintenance)

| 异常关键词 | 根本原因 | 紧急对策 |
| :--- | :--- | :--- |
| `ClientAbortException` | HTTP 超时断开 | 任务已在后台托管，只需静待 CPU 负载降低即可，无需干预。 |
| `index_not_found` | 配置漂移至远程 | 检查 Nacos 命名空间及 JVM 启动参数是否带符号 `-`。 |
| `Connection Refused` | 代理拦截 | 确认已彻底退出 Clash/VPN 等 TUN 驱动模式下的软件。 |

---
