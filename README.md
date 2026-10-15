# 风险合规预警系统

## 系统概述

本项目面向企业项目的风险合规评估，提供企业与项目管理、材料上传、事实分析、指标与法规检索、规则计算、报告和通知功能。后端采用 Java 8 Maven 多模块架构，前端采用 Vue 3。

系统统一使用以下分析链路：

**材料上传 → 证据落库 → 事实抽取与分类 → 指标/法规候选检索 → 模型条件判断 → 已审核规则计算 → 报告与通知**

候选指标和法规用于辅助分析，评分以已审核规则为依据。证据不足或规则未审核时，系统保留相应状态，不生成占位评分。

## 技术架构

### 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 8、Spring Boot 2.6.13、Spring Cloud 2021.0.5 |
| 服务治理 | Spring Cloud Alibaba、Nacos 2.4.2 |
| 数据存储 | PostgreSQL 13、Redis 6.2 |
| 检索 | Elasticsearch 8.11.0、IK 分词、1024 维 Embedding |
| 异步处理 | Kafka、持久化任务与 Outbox |
| 模型服务 | Python 分类服务、OpenAI 兼容模型接口 |
| 前端 | Vue 3、TypeScript、Element Plus、Vite |

### 微服务架构

| 模块 | 职责 | 默认端口 |
| --- | --- | --- |
| `risk-warning-common` | 公共契约、实体、消息与基础组件 | — |
| `risk-warning-gateway` | API 网关与请求路由 | 8088 |
| `risk-warning-org` | 用户、企业、项目与材料管理 | 8095 |
| `risk-warning-processing` | 事实抽取、分析编排与规则计算 | 8084 |
| `risk-warning-knowledge` | 指标/法规检索、Embedding 与候选回填 | 8089 |
| `risk-warning-bert` | 分类接口与 Python 子进程管理 | 8090 |
| `risk-warning-report` | 评估报告与结果查询 | 8093 |
| `risk-warning-notification` | 评估通知与消息处理 | 8091 |

Python 分类子进程默认监听 `8002`，由 BERT Java 服务启动。

## 项目结构

```text
Risk_Warning_Platform/
├── risk-warning-common/          # 公共模块
├── risk-warning-gateway/         # 网关服务
├── risk-warning-org/             # 企业与项目服务
├── risk-warning-processing/      # 合规分析服务
├── risk-warning-knowledge/       # 知识检索服务
├── risk-warning-bert/            # Java 分类接口与 Python 服务
├── risk-warning-report/          # 报告服务
├── risk-warning-notification/    # 通知服务
├── Risk_Warning_Platform_Fr/     # Vue 前端
├── build/                       # Docker、Nacos 与 ES 配置
├── documents/                   # Schema、候选数据、字典与文档
├── test/                        # 初始化工具与测试数据
├── .env.example                 # 本地模型配置模板
└── pom.xml                      # Maven 父工程
```

## 快速启动

### 环境要求

- JDK 8，或支持 Java 8 编译的 JDK。
- Maven、Python、Node.js 与 npm。
- Docker 与 Docker Compose。
- 可用的条件判断模型、Embedding 接口及本地分类模型权重。

以下命令使用 PowerShell，除前端命令外均从仓库根目录执行。本地开发时由 Docker 提供基础设施，Java 服务在本机运行。

### 1. 启动基础设施

首次使用时创建 Compose 所需的外部数据卷，再启动依赖服务：

```powershell
docker volume create postgres-data
docker volume create redis-data
docker compose -f build/docker-compose.yml up -d zookeeper kafka postgres redis nacos
docker compose -f build/docker-compose-elasticsearch.yml up -d elasticsearch
```

| 组件 | 默认访问地址 |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| Kafka | `localhost:9092` |
| Elasticsearch | `http://localhost:9200` |
| Nacos 控制台 | `http://localhost:8848/nacos` |

将 [common-dev.yaml](build/nacos/common-dev.yaml) 的内容发布到 Nacos：默认命名空间，Group 为 `DEFAULT_GROUP`，Data ID 为 `common-dev.yaml`。数据库、Redis、Kafka 和 ES 连接配置须与实际部署一致。

### 2. 初始化数据库与候选索引

[schema.sql](documents/schema.sql) 提供完整的当前表结构，只在空数据库执行。默认开发库为 `risk_warning_platform`，服务不会自动更新表结构。

```powershell
docker cp documents/schema.sql postgres-risk-warning:/tmp/schema.sql
docker exec postgres-risk-warning psql -U postgres -d risk_warning_platform -v ON_ERROR_STOP=1 --single-transaction -f /tmp/schema.sql
python test/init_es.py --create-only
```

ES 初始化工具创建缺失索引，并为指标和法规索引导入候选数据；已有索引会跳过，行为和风险索引不灌入演示数据。

| 文件 | 用途 |
| --- | --- |
| [schema.sql](documents/schema.sql) | 数据表、索引与约束 |
| [es_mappings.json](documents/es_mappings.json) | Elasticsearch 字段与检索配置 |
| [indicator.json](documents/data/indicator.json) | 开发用指标库，1494条 |
| [regulation.json](documents/data/regulation.json) | 开发用法规及管理要求库，2231条 |
| [dicts](documents/dicts) | 地区、行业与领域字典 |

### 3. 配置模型与分类环境

首次配置时，复制模板并填写本机模型参数；已有 `.env` 时直接编辑：

```powershell
Copy-Item .env.example .env
python -m pip install -r risk-warning-bert/bert-service/requirements.txt
```

| 配置项 | 说明 |
| --- | --- |
| `LLM_BASE_URL` | 完整的 OpenAI 兼容 `chat/completions` 端点 |
| `LLM_MODEL`、`LLM_API_KEY` | 条件判断模型名称与凭据 |
| `LLM_EXTRA_BODY` | 供应商可选请求参数，使用 JSON 字符串 |
| `EMBEDDING_ENDPOINT`、`EMBEDDING_MODEL` | Embedding 接口与模型 |
| `EMBEDDING_API_KEY` | Embedding 凭据 |

Processing 默认启用真实模型，Knowledge 默认启用 Embedding 和检索。Embedding 输出必须与索引的 **1024 维**向量一致。

分类模型权重放置在 `risk-warning-bert/classify-service/checkpoints/best_model.pt`。启动 BERT 服务的终端中，将 Python 路径设置为安装上述依赖的解释器，例如：

```powershell
$env:BERT_PYTHON_PATH = (Get-Command python).Source
```

密钥、模型权重和缓存不提交到仓库。

### 4. 启动后端服务

```powershell
mvn clean package -DskipTests
```

按下面顺序，在不同终端中分别执行一条命令，保持工作目录为仓库根目录。Windows 启动时显式使用 UTF-8，以正确读取中文配置。

```powershell
java '-Dfile.encoding=UTF-8' -jar risk-warning-knowledge/target/risk-warning-knowledge-1.0.0.jar
java '-Dfile.encoding=UTF-8' -jar risk-warning-bert/target/risk-warning-bert-1.0.0.jar
java '-Dfile.encoding=UTF-8' -jar risk-warning-org/target/risk-warning-org-1.0.0.jar
java '-Dfile.encoding=UTF-8' -jar risk-warning-processing/target/risk-warning-processing-1.0.0.jar
java '-Dfile.encoding=UTF-8' -jar risk-warning-report/target/risk-warning-report-1.0.0.jar
java '-Dfile.encoding=UTF-8' -jar risk-warning-notification/target/risk-warning-notification-1.0.0.jar
java '-Dfile.encoding=UTF-8' -jar risk-warning-gateway/target/risk-warning-gateway-1.0.0.jar
```

也可以通过 IDE 运行各模块的入口类，工作目录同样设为仓库根目录，以加载根目录 `.env`。BERT 服务会自动定位仓库内的 Python 脚本；自定义脚本位置通过 `bert.python.script` 配置。

### 5. 回填候选检索数据

Knowledge 启动后，为指标和法规生成检索文本及向量：

```powershell
$body = '{"target":"both","indicatorIndex":"t_indicator","regulationIndex":"t_regulation","force":false}'
Invoke-RestMethod -Method Post -Uri 'http://localhost:8089/retrieval/backfill' -ContentType 'application/json' -Body $body
```

回填完成后，检查 `failed=0`，核对 `updated/skipped` 与候选数量，并验证实际 ES 文档包含 `retrievalText`、`retrievalTextIk`、`retrievalVector` 和匹配的 Embedding 元数据。HTTP 200 本身不能证明候选已可检索。

### 6. 启动前端

```powershell
cd Risk_Warning_Platform_Fr
npm ci
npm run dev
```

访问终端输出的开发地址。前端代理默认指向 `http://localhost:8088`，需要调整时在前端 `.env.development.local` 中配置 `API_PROXY_TARGET`。

## 核心功能模块

| 功能 | 说明 |
| --- | --- |
| 用户、企业与项目管理 | 管理评估主体、项目信息与上传材料 |
| 事实分析 | 保存材料证据，抽取事实并进行业务分类 |
| 候选检索 | 使用向量检索与法规关键词检索匹配指标和法规 |
| 条件判断与规则计算 | 模型判断适用条件，Java 执行已审核的确定性规则 |
| 评估报告 | 展示运行状态、事实证据、待审事项与冻结的规则结果 |
| 异步任务与通知 | 通过持久化任务、Outbox 和消息队列处理分析与通知 |

正式规则注册表位于 [verified-rules-v1.json](risk-warning-processing/src/main/resources/rules/verified-rules-v1.json)。当前正式注册表为空；上传材料可以完成分析链路，但未审核指标会返回待审，不能据此认定无风险。报告使用该次运行冻结的规则决定。

## 开发指南

### 构建与测试

在仓库根目录执行后端与辅助工具测试：

```powershell
mvn test
mvn -pl risk-warning-knowledge -am test
python -m unittest discover -s test -p 'test_*.py'
python -m pytest risk-warning-bert/bert-service
```

Python 分类测试依赖见 `risk-warning-bert/bert-service/requirements-dev.txt`。PostgreSQL、ES 集成测试需按测试类说明设置环境变量与启用开关，并提供隔离数据库或索引。涉及检索的验证应检查实际持久化覆盖率和查询结果。

前端验证在前端目录执行：

```powershell
npm run test:unit -- --run
npm run build
```

### 配置与日志

- 服务配置位于各模块 `src/main/resources`；公共连接配置模板位于 `build/nacos`。
- 本机模型配置保存在根目录 `.env`，部署环境可通过环境变量提供。
- 公共日志配置将应用日志写入 `<服务名称>/out/app.log`，相对于服务工作目录解析。
- 开发与提交规范见 [AGENTS.md](AGENTS.md)。个人计划和验收记录放在忽略目录 `documents/local`。

## 常见问题

**为什么上传完成后仍显示待审？**

材料处理完成与获得可计分结论是不同状态。缺少审核规则、适用条件不确定或证据不足时，系统保留待审或证据不足结果，应结合分析详情确认原因。

**为什么检索不到指标和法规？**

先检查候选索引是否有数据、回填是否完成，再核对 Embedding 模型与版本、1024 维向量，以及 `retrievalTextIk` 的 IK 分词配置。`--create-only` 会跳过已有索引，不会修正旧 mapping；调整现有索引前须确认目标并完成可恢复备份。

**为什么分类服务启动失败？**

确认 `BERT_PYTHON_PATH` 指向正确的 Python 环境、依赖安装完整、分类权重存在，并检查 BERT 日志中的 Python 启动和模型加载结果。

**为什么模型配置没有生效？**

检查服务工作目录是否为仓库根目录、`.env` 参数是否填写，以及 Nacos 中实际发布的配置。条件判断接口需要完整的 `chat/completions` 地址。
