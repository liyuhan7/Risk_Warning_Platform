## 前端专项开发计划：生产级页面与可解释风险工作台

> 前端仓库：`D:\大创\Risk_Warning_Platform_Fr`  
> 核对基线：`main`，提交 `f704085de7e2`  
> 技术栈：Vue 3、TypeScript、Vite 5、Element Plus、Pinia、Vue Router、Axios  
> 视觉基准：`documents/images/` 下 7 张页面效果图

### 1. 目标

在保留现有技术栈的前提下，把当前以功能演示为主的前端 Demo 升级为正式应用页面。产品不再围绕零散的表格、对话框和结果组件组织，而是形成下面这条连续、可恢复、可解释的用户链路：

```text
工作台
→ 企业管理
→ 项目工作区
→ 上传材料并创建 Assessment
→ 查看真实评估进度
→ 查看评估结果工作台
→ 查看 Risk 决策链与整改建议
```

完成后，系统应具备以下页面能力：

- 使用“左侧主导航 + 顶部全局栏 + 主内容区”的统一应用壳层。
- 首页成为用户每天进入系统后的任务工作台，而不是静态功能入口页。
- 企业和项目页面能够承担真实查询、筛选、分页、历史追踪和下一步操作。
- 发起评估采用清晰的分步流程，并由后端真实创建 Assessment。
- 评估过程使用独立进度页展示真实阶段、连接、失败和降级状态。
- 评估结果采用“总览 / 指标 / 风险”工作台，并通过 URL 恢复页面状态。
- Risk 详情以 `Evidence → Fact → Regulation → Analysis → Rule → Risk` 为核心决策链。
- 桌面与移动端使用适合各自屏幕的信息架构，不强行压缩同一套布局。
- 前端只解释和展示后端结果，不在浏览器内重新计算规则、分数和风险等级。

这里的“生产级”限定为页面信息架构、真实状态、组件边界、错误处理、响应式、可访问性和自动化测试达到正式应用要求；不等同于本轮同时建设完整的生产运维、监控和发布平台。

### 2. 当前现状

#### 已有能力

- 已有登录、注册、首页、企业、项目、问卷和评估结果路由。
- `AppHeader.vue` 提供基础横向导航和用户菜单，页面已统一使用 Element Plus。
- `Enterprise.vue` 和 `Project.vue` 已能读取列表并执行创建、成员等基础操作。
- `Project.vue` 已实现文件分片上传、失败重试、确认上传和等待评估。
- `AssessmentResult.vue` 已监听 WebSocket 完成事件，并加载总览、指标分布和风险清单。
- `RiskReport.vue` 已能显示风险、指标和法规的基础字段。
- Axios 拦截器已经统一处理 Token 和基础请求异常。

#### 与目标页面的主要差距

- `App.vue` 只有 `router-view`，统一 Sidebar、Topbar、面包屑和内容框架尚未形成。
- `Home.vue` 只有三个静态卡片，没有真实业务摘要、趋势、最近项目和待办风险。
- 企业页面没有完整搜索、筛选、分页、URL 恢复、持久错误和移动卡片模式。
- `Project.vue` 同时承担列表、详情、成员、上传和评估跳转，尚未拆成项目列表与项目工作区。
- 项目目前只查询单个评估结果，没有 Assessment 历史和材料是否参与本次评估的边界展示。
- 上传流程只有上传抽屉，没有“确认范围”和“创建 Assessment”的独立步骤语义。
- 评估中仍使用全屏等待遮罩，没有六阶段进度、连接回退、失败阶段和降级完成说明。
- 评估结果页的视图、筛选、页码和 Risk 选择没有写入 URL，刷新后不能恢复工作位置。
- 风险页面仍是长折叠列表，没有服务端分页、证据完整性筛选和稳定的列表+详情工作台。
- `RiskVO` 没有 Evidence、Structured Fact、AnalysisResult、RuleTrace、人工复核和降级字段。
- 空字段统一显示 `-`，不能区分空值、证据不足、接口未返回和降级缺失。
- “导出报告”仍是开发中提示；问卷仍依赖 Mock 和模拟延迟，不能作为正式主链入口。
- 现有报告组件缺少系统性的移动端结构和前端自动化测试。
- 前端工作区已有用户对 `src/api/report.ts` 的未提交接口路径修正，后续实现必须保留该改动。

### 3. 本次范围

- 建立统一 AppLayout、Sidebar、Topbar、全局搜索入口、通知入口、账户菜单和移动端 TabBar。
- 建立颜色、字号、间距、圆角、边框、阴影、状态和响应式设计 Token。
- 按效果图实现首页工作台、企业管理、项目工作区、材料上传、评估进度、评估结果和 Risk 详情七类页面。
- 将企业、项目、Assessment 和 Risk 的筛选、页码、选中项等可分享状态写入 URL。
- 建立统一 Loading、Empty、Error、Partial、Degraded、Insufficient Evidence 和 Needs Review 页面状态。
- 拆分过重页面，建立 Layout、Common、Project、Assessment、Report、Evidence 和 Rule 组件边界。
- 新增 Assessment/Report Store 或 Composable，统一请求、缓存、取消、重试、WebSocket 和轮询回退。
- 为首页、列表、Assessment 历史、进度、分页风险和 Risk 决策链补齐后端接口契约。
- 完成桌面、平板和移动端适配，以及键盘、焦点、表单语义和颜色非唯一表达。
- 引入最小单元、组件和核心 E2E 测试体系。

### 4. 非本次范围

- 不迁移到 React、Nuxt、微前端或服务端渲染。
- 不自研大型组件库，不引入低代码平台或复杂图表平台。
- 不在前端重新执行法规适用判断、规则计算或风险定级。
- 不实现 PDF/DOCX 在线编辑、OCR 人工校正、多人实时协作和复杂审批流。
- 不凭空增加后端没有真实数据支撑的摘要卡、趋势、通知和预计完成时间。
- 不把本地 Mock 问卷或模拟提交包装成正式能力。
- 不保留点击后只显示“开发中”的假入口；没有后端能力时隐藏或明确禁用。
- 不在本计划中同时建设前端监控平台、埋点体系、CDN、灰度发布和生产高可用。

### 5. 方案概述

#### 5.1 全局应用壳层

所有登录后页面统一采用固定左侧主导航、顶部全局栏和可滚动主内容区：

- Sidebar 放置 Logo、工作台、企业管理、项目管理、风险评估、风险报告、整改任务和系统设置。
- 当前一级入口使用背景、图标和文字共同高亮，不能只依赖颜色。
- Topbar 放置全局搜索、通知、用户头像和账户菜单，不再承担主要业务导航。
- 页面内部统一使用面包屑、PageHeader、主要操作区和内容分区。
- 桌面端允许折叠 Sidebar；移动端改为顶部菜单和底部 `工作台 / 项目 / 评估 / 我的` 四入口。

#### 5.2 首页 / 工作台

首页定位为“每日任务工作台”：

- 顶部显示“工作台”、欢迎信息、最近更新时间或主要快捷操作。
- 第一行显示企业总数、项目总数、待处理风险和高风险数量等 3—4 个真实摘要。
- 中部左侧显示近 6 个月风险趋势，右侧显示高中低风险分布。
- 底部左侧显示最近评估项目，右侧显示最新风险预警和待复核事项。
- 每个列表项直接进入对应项目、Assessment 或 Risk，不再只是视觉卡片。
- 趋势和摘要没有真实聚合接口时，不显示虚构数据，降级为最近项目和最近 Assessment。

![首页 / 工作台预期效果](images/首页.png)

#### 5.3 企业管理页

企业管理保留列表主结构，但升级为可查询的正式管理页面：

- PageHeader 左侧显示标题和说明，右侧显示唯一主操作“新建企业”。
- 搜索筛选栏支持企业名称/信用代码、行业、经营状态、风险状态和更多筛选。
- 筛选条件写入 URL，刷新、返回和分享链接后保持不变。
- 轻量统计区显示企业总数、正常经营、风险企业和待评估企业，均来自真实接口。
- 表格第一列组合企业名称与信用代码，其他核心列为行业、项目数、风险状态和最近评估时间。
- 行内只保留“查看详情”，其余低频操作收进更多菜单。
- 无数据时提供创建入口；失败时在列表区域显示错误和重新加载按钮。
- 移动端自动切换为企业卡片列表，不横向压缩完整表格。

![企业管理页预期效果](images/企业管理页.png)

#### 5.4 项目工作区

项目分为“项目列表”和“项目详情工作区”两个路由层级。进入具体项目后：

- 顶部显示项目名称、所属企业、类型、负责人、创建时间、编号和项目状态。
- 右侧主按钮为“发起风险评估”，其他操作进入更多菜单。
- 摘要区显示项目状态、最近评估时间、当前风险等级和高风险数量。
- 使用 `概览 / 评估历史 / 材料管理 / 项目成员 / 风险总览` 页签组织项目内部信息。
- 概览上部为基本信息和当前状态，下部左侧为 Assessment 历史，右侧为项目材料。
- Assessment 历史明确区分评估中、已完成、失败和降级完成，点击进入指定 Assessment。
- 材料列表显示文件类型、大小、上传时间、处理状态和是否参与本次 Assessment。
- 页面始终回答：项目当前状态是什么、过去有哪些评估、用户下一步可以做什么。

![项目工作区预期效果](images/项目工作区.png)

#### 5.5 发起评估 / 上传材料页

发起评估使用独立页面或大尺寸抽屉，并固定为三步流程：

```text
材料上传 → 确认范围 → 创建评估
```

- 材料上传展示所属项目、已有材料数量、文件约束、拖拽区和上传队列。
- 每个文件展示名称、类型、大小、分片进度、状态和失败原因，并允许单文件重试。
- 确认范围允许用户选择本次 Assessment 使用的材料，展示总数、总体大小和必要材料提示。
- 创建评估必须调用后端真实接口，成功后取得 Assessment ID，再进入进度页。
- 底部固定“取消 / 上一步 / 下一步或创建评估”，按钮状态由当前步骤和真实上传状态决定。
- 移动端改为单列，文件低频操作收进菜单。

![发起评估 / 材料上传页预期效果](images/材料上传页.png)

#### 5.6 评估进度页

该页面替代当前全屏等待遮罩：

- 顶部显示 Assessment 编号、所属项目、开始时间和总状态。
- 主流程固定为 `材料接收 → 解析处理 → 事实抽取 → 检索匹配 → 规则分析 → 结果生成`。
- 每个阶段支持等待中、处理中、已完成、失败和降级完成五种状态。
- 当前阶段显示正在处理的文件/对象；有真实进度时显示整体进度和材料、页数、事实、法规、风险等计数。
- 下方显示 WebSocket 状态、最近心跳、查询回退和阶段日志/温馨提示。
- 失败时显示失败阶段、原因、可否重试、“重新评估”和“返回项目”。
- 降级完成时明确提示结果限制，禁止伪装成普通完成。
- 后端没有真实百分比和预计时间时，只展示离散阶段与计数，不由前端模拟。

![评估进度页预期效果](images/评估进度页.png)

#### 5.7 评估结果 / 风险工作台

评估结果页面顶部显示项目、Assessment 编号、评估时间、整体风险等级和状态，主体使用 `总览 / 指标分布 / 风险详情` 页签：

- 总览用摘要和必要图表呈现总体评分、风险等级、风险数量、维度分布和评估摘要。
- 指标页展示指标分布、触发指标、安全指标和各维度表现，表格只承担明细查询。
- 风险页使用左右分栏：左侧为风险列表，右侧为当前 Risk 摘要和入口。
- 风险筛选支持搜索、等级、维度、合规状态、证据完整性和服务端分页。
- Risk 列表项显示名称、等级、评分、合规状态、证据完整性和更新时间。
- 页面状态写入 URL，至少包含 Assessment、页签、筛选、页码和 Risk ID。
- 窄屏先显示风险列表，选中后进入独立 Risk 详情页或底部抽屉。

```text
/assessment/123?view=risk&level=HIGH_RISK&riskId=456&page=1
```

![评估结果 / 风险工作台预期效果](images/评估结果详情.png)

#### 5.8 Risk 详情 / 决策链页面

Risk 详情是本轮最重要的可解释页面：

- 顶部显示 Risk 名称、等级、状态、分数、维度、证据完整性、人工复核和降级状态。
- 主体按 `Evidence → Fact → Regulation → Analysis → Rule → Risk` 纵向展示完整决策链。
- Evidence 展示文件名、页码、段落、原文/截图、来源系统和证据置信度。
- Fact 只展示后端抽取的结构化事实，前端不重新解释原文。
- Regulation 展示法规名称、条款编号、条款内容和来源。
- Analysis 展示事实与法规的冲突、支持信息、缺失信息、结论、置信度和模型/Prompt 版本。
- Rule 展示命中规则、条件、输入值、计算步骤、结果和规则版本，仅解释后端结果。
- Risk 展示最终结论、业务影响、整改建议和建议优先级。
- Evidence 不足时显示 `Evidence 不足 → Fact 不完整 → INSUFFICIENT_EVIDENCE`，不能用 `-` 代替。
- 需要人工复核时显示 `NEEDS_REVIEW` 和提交复核入口；本轮只定义入口与状态，完整复核工作流按后端范围决定。

![Risk 详情 / 决策链预期效果](images/风险详情.png)

#### 5.9 移动端整体规则

- Sidebar 改为顶部菜单或底部 `工作台 / 项目 / 评估 / 我的` TabBar。
- 所有双列布局改为单列堆叠，主要操作固定在易触达位置。
- 表格优先改成信息卡列表，筛选进入 Drawer。
- Risk 详情使用独立路由，按 Evidence、Fact、Regulation、Analysis、Rule、Risk 逐层展开。
- 不出现页面级横向滚动；状态同时使用文字、图标和颜色表达。
- 需要收起的长内容必须保留明确“展开/收起”控制，不能默认截断关键证据。

### 6. 涉及模块

#### 前端仓库

- `src/App.vue`：接入 AuthLayout 和 AppLayout。
- `src/router/index.ts`：增加嵌套路由、项目工作区、Assessment 进度、结果和 Risk 详情深链接。
- `src/style.css`：全局 Token、排版、焦点、响应式和 Element Plus 覆盖入口。
- `src/components/AppHeader.vue`：拆分并演进为 Topbar，与 Sidebar 和 MobileTabBar 协作。
- `src/components/layout/`：`AppLayout`、`AppSidebar`、`AppTopbar`、`PageHeader`、`MobileTabBar`。
- `src/components/common/`：`AsyncState`、`EmptyState`、`ErrorState`、`StatusBadge`、`SearchFilterBar`、`PagedList`。
- `src/views/Home.vue`：首页任务工作台。
- `src/views/Enterprise.vue`：企业搜索、筛选、分页和移动卡片模式。
- `src/views/Project.vue`：收敛为项目列表，并拆出项目详情工作区。
- `src/views/project/`：项目概览、评估历史、材料、成员和风险总览。
- `src/views/assessment/`：创建评估、评估进度和评估结果。
- `src/views/risk/`：Risk 详情独立页面。
- `src/components/report/`：总览、指标、风险列表和 Risk 摘要。
- `src/components/evidence/`：Evidence、Fact 和原文定位组件。
- `src/components/analysis/`：Regulation、Analysis、RuleTrace 和 RiskDecision 组件。
- `src/api/`：工作台、企业分页、项目工作区、Assessment、进度和 Risk 详情接口。
- `src/types/`：页面契约、分页、状态和解释链 DTO。
- `src/stores/assessment.ts`、`src/stores/report.ts` 或等价 Composable：跨页面状态与缓存。
- `src/utils/websocket.ts`：只管理连接和事件，不直接决定页面跳转。
- `package.json`：增加单元/组件测试和 E2E 脚本。

#### 后端配合模块

- `risk-warning-org`：工作台、企业统计、项目详情、材料范围和 Assessment 历史。
- `risk-warning-processing`：创建 Assessment、阶段进度、失败和降级状态。
- `risk-warning-report`：结果总览、指标、分页风险列表和 Risk 决策链详情。
- `risk-warning-common`：状态枚举、分页结构和公共 DTO 语义。
- `documents/API接口文档_示例.md`：同步正式接口、错误状态和示例响应。

### 7. 核心数据 / 接口变化

#### 核心前端类型

| 类型 | 页面用途 | 关键字段 |
| --- | --- | --- |
| `WorkbenchSummaryVO` | 首页 | 企业/项目/待处理/高风险真实计数、趋势、等级分布、最近项目、最近预警 |
| `EnterpriseListItemVO` | 企业管理 | 企业摘要、行业、经营状态、项目数、风险状态、最近评估时间 |
| `ProjectWorkspaceVO` | 项目工作区 | 项目、企业、负责人、状态、最近 Assessment、风险摘要、材料摘要 |
| `AssessmentHistoryItemVO` | 项目工作区 | Assessment ID、状态、开始/完成时间、负责人、风险等级、降级标志 |
| `ProjectMaterialVO` | 项目/上传 | 文件 ID、名称、类型、大小、上传/处理状态、是否进入本次评估 |
| `CreateAssessmentRequest` | 发起评估 | Project ID、选中材料 ID、幂等键和可选备注 |
| `AnalysisProgressVO` | 进度页 | Assessment/AnalysisRun、总状态、六阶段、对象计数、连接、失败和降级信息 |
| `RiskListItemVO` | 风险工作台 | Risk ID、名称、等级、分数、维度、合规状态、证据完整性、更新时间 |
| `EvidenceReferenceVO` | Risk 详情 | Evidence ID、文件、页码、段落/位置、原文、来源、置信度 |
| `StructuredFactVO` | Risk 详情 | 主体、行为、对象、状态、时间、数值、单位、置信度、Evidence 引用 |
| `AnalysisSummaryVO` | Risk 详情 | 适用性、法规要求、差距、状态、置信度、理由、模型/Prompt 版本、降级信息 |
| `RuleTraceVO` | Risk 详情 | Indicator、规则、输入、计算步骤、分数、阈值、决定和规则版本 |
| `RiskDetailVO` | Risk 详情 | Risk 摘要 + Evidence + Fact + Regulation + Analysis + Rule + 整改建议 |
| `PagedResult<T>` | 所有列表 | `items`、`page`、`pageSize`、`total`、`hasNext` |

#### 前端需要的接口能力

具体 URI 在接口评审时与现有 Gateway 前缀统一，下面只冻结页面能力：

| 页面 | 接口能力 |
| --- | --- |
| 首页 | 工作台摘要、风险趋势、等级分布、最近项目、最近预警/待复核 |
| 企业管理 | 企业服务端搜索、筛选、分页及真实统计 |
| 项目工作区 | 项目详情、Assessment 历史、材料列表、成员、风险摘要 |
| 发起评估 | 上传、材料范围确认、真实创建 Assessment |
| 评估进度 | 按 Assessment 查询阶段、计数、失败、降级和更新时间 |
| 评估结果 | 总览、指标分布、服务端分页/筛选 Risk |
| Risk 详情 | 一次返回或稳定组合 Evidence、Fact、Regulation、Analysis、Rule 和 Risk |

建议路由：

```text
/
/enterprises
/projects
/projects/:projectId
/projects/:projectId/assessments/new
/assessments/:assessmentId/progress
/assessments/:assessmentId?view=risk&level=HIGH_RISK&riskId=456&page=1
/assessments/:assessmentId/risks/:riskId
```

#### 统一状态语义

- 请求状态：`loading / empty / error / partial`。
- Assessment：`PENDING / RUNNING / COMPLETED / FAILED / DEGRADED`。
- 阶段：`WAITING / RUNNING / COMPLETED / FAILED / DEGRADED`。
- 合规结论：`COMPLIANT / NON_COMPLIANT / INSUFFICIENT_EVIDENCE / NEEDS_REVIEW`。
- Evidence：`COMPLETE / INCOMPLETE / MISSING / INVALID_REFERENCE`。

### 8. 开发任务拆分

#### F0：应用壳层和公共能力

- [ ] `FE-00`（C）冻结七类页面、移动端规则、路由图、状态矩阵和现有接口差距。
- [ ] `FE-01`（C）建立设计 Token 和 Element Plus 主题入口，统一字号、间距、状态色和焦点样式。
- [ ] `FE-02`（C）实现 AppLayout、Sidebar、Topbar、PageHeader、AuthLayout 和 MobileTabBar。
- [ ] `FE-03`（C）实现统一 Loading、Empty、Error、Partial、Degraded 和 StatusBadge 组件。
- [ ] `FE-04`（C）实现筛选 URL 序列化、分页 URL、Risk 深链接、404 和权限错误页。

#### F1：首页和企业管理

- [ ] `FE-05`（B、C）冻结工作台真实聚合接口；无接口的数据不进入效果实现。
- [ ] `FE-06`（C）按 `首页.png` 实现真实摘要、趋势、等级分布、最近项目和最近预警跳转。
- [ ] `FE-07`（B、C）冻结企业统计和服务端分页/筛选接口。
- [ ] `FE-08`（C）按 `企业管理页.png` 重构企业页面，完成 URL 筛选、统计、精简操作和持久错误状态。
- [ ] `FE-09`（C）实现企业列表移动卡片模式和无数据创建入口。

#### F2：项目工作区和发起评估

- [ ] `FE-10`（C）将现有 `Project.vue` 拆成项目列表、ProjectWorkspace 和独立业务组件。
- [ ] `FE-11`（B、C）冻结项目详情、Assessment 历史、材料和风险摘要接口。
- [ ] `FE-12`（C）按 `项目工作区.png` 实现五页签、状态摘要、最近 Assessment、材料和快捷操作。
- [ ] `FE-13`（B、C）冻结材料范围和创建 Assessment 契约，创建成功必须返回 Assessment ID。
- [ ] `FE-14`（C）按 `材料上传页.png` 实现上传、确认范围、创建评估三步流程和单文件重试。
- [ ] `FE-15`（C）实现上传离开保护、幂等提交、失败恢复和移动端文件操作菜单。

#### F3：评估进度

- [ ] `FE-16`（B、C）冻结 AnalysisProgressVO、六阶段枚举、失败、重试和降级语义。
- [ ] `FE-17`（C）按 `评估进度页.png` 实现阶段流程、真实计数、当前对象、连接状态和阶段消息。
- [ ] `FE-18`（C）实现 WebSocket 事件与 HTTP 查询回退，断线、恢复和重复事件不导致重复跳转。
- [ ] `FE-19`（C）实现失败、可重试、不可重试和降级完成页面状态。

#### F4：评估结果和 Risk 决策链

- [ ] `FE-20`（B、C）冻结结果总览、指标、分页 RiskListItemVO 和 RiskDetailVO。
- [ ] `FE-21`（C）按 `评估结果详情.png` 重构总览、指标和风险工作台，并把页签、筛选、页码和 Risk ID 写入 URL。
- [ ] `FE-22`（C）实现服务端分页风险列表、搜索、等级、维度、合规状态和证据完整性筛选。
- [ ] `FE-23`（C）按 `风险详情.png` 实现独立 Risk 页面和 Evidence→Fact→Regulation→Analysis→Rule→Risk 纵向链。
- [ ] `FE-24`（C）实现 Evidence 原文定位、结构化事实、法规引用、AI 分析、规则轨迹和整改建议组件。
- [ ] `FE-25`（B、C）实现 `INSUFFICIENT_EVIDENCE / NEEDS_REVIEW / DEGRADED` 的完整展示，不用空值代替状态。

#### F5：移动端和质量收口

- [ ] `FE-26`（C）完成 1440/1366 桌面、768 平板和 390/360 移动端布局，不出现页面级横向滚动。
- [ ] `FE-27`（C）完成键盘导航、焦点、表单标签、标题层级和颜色+文字+图标状态表达。
- [ ] `FE-28`（C）增加 Vitest + Vue Test Utils，覆盖状态、筛选、分页、URL 恢复和关键组件。
- [ ] `FE-29`（B、C）增加项目→上传→进度→结果→Risk 详情 Playwright E2E，覆盖断线回退。
- [ ] `FE-30`（全员）使用固定合规、不合规和证据不足案例完成视觉评审、真实联调和 9 月 24 日验收。

### 9. 依赖关系

- F0 是所有页面共同前置；应用壳层和状态组件冻结后才能批量改业务页面。
- 首页趋势、企业统计等效果图内容依赖后端真实聚合接口；接口未提供时必须按降级方案收缩页面。
- 项目工作区依赖 `risk-warning-org` 提供 Assessment 历史和材料范围，不能继续只返回项目的单个 Assessment。
- 发起评估依赖上传完成后真实创建 Assessment 并返回 ID，随后才能进入进度路由。
- 进度页依赖 `risk-warning-processing` 持久化阶段状态；WebSocket 只负责实时通知，HTTP 查询负责刷新恢复和断线回退。
- 评估结果依赖 `risk-warning-report` 提供总览、指标和服务端分页风险列表。
- Risk 详情依赖计划 1 的 Evidence/Fact、计划 3 的 Analysis/Rule、计划 4 的 RiskDetail 关系闭环。
- 当前 `src/api/report.ts` 用户修改必须保留，所有新接口基于现有修正继续开发。
- 前端专项与后端计划 1—4 并行，在计划 5 汇合进行完整 E2E 和 RC 验收。

### 10. 验收标准

1. 七类页面均使用统一 AppLayout，视觉结构与 `documents/images` 对应效果图一致，差异有明确业务或响应式理由。
2. 首页所有摘要和图表均来自真实接口；无数据时显示空态，不显示效果图示意数字。
3. 企业筛选、分页和搜索写入 URL，刷新后保持；空态和错误态均有页面内操作入口。
4. 项目详情能查看指定项目的 Assessment 历史和材料，点击历史记录进入准确 Assessment，不默认只取一个结果。
5. 发起评估完整执行上传、确认范围、创建 Assessment，并使用返回的 ID 进入进度页；重复点击不创建重复任务。
6. 进度页能显示六阶段和真实状态；失败展示阶段与原因，降级展示限制，断线自动使用 HTTP 查询回退。
7. 评估结果的总览、指标和风险页签可直接访问；Risk 列表使用服务端筛选和分页。
8. 刷新、后退或复制链接后能恢复 Assessment、页签、筛选、页码和选中 Risk。
9. Risk 详情完整展示 Evidence、Fact、Regulation、Analysis、Rule 和 Risk，所有引用 ID 可由后端查询验证。
10. Evidence 不足显示 `INSUFFICIENT_EVIDENCE`，人工复核显示 `NEEDS_REVIEW`，降级显示 `DEGRADED`；这些状态不能伪装成低风险或普通完成。
11. 前端不存在阈值、规则公式和风险等级二次计算，页面展示与后端 DTO 一致。
12. 1440×900、1366×768、768×1024、390×844 和 360×800 下无页面级横向滚动、文字遮挡和不可点击主操作。
13. 核心流程可使用键盘完成，状态不只依赖颜色，表单、图表和按钮具有可识别名称。
14. 不存在仍可点击但只提示“开发中”的主流程入口；Mock 问卷不进入正式导航。
15. `npm run build`、前端单元/组件测试和核心 Playwright E2E 通过，无 TypeScript 错误和未处理 Promise。

### 11. 测试方式

计划实施后至少提供：

```powershell
npm run build
npm run test:unit
npm run test:e2e
```

- 构建测试：校验 TypeScript、Vue SFC、路由懒加载和样式构建。
- URL 测试：企业筛选、Assessment 页签、Risk 筛选、页码和 Risk ID 往返序列化。
- 组件测试：AppLayout、AsyncState、EnterpriseList、ProjectWorkspace、AssessmentWizard、ProgressTimeline、RiskList、EvidenceViewer 和 RuleTrace。
- API Mock 测试：正常、空、401、403、404、500、超时、部分成功、失败、降级和错误引用。
- 上传测试：文件校验、单文件重试、确认范围、重复提交和创建 Assessment 返回 ID。
- 进度测试：六阶段状态、WebSocket 断线、HTTP 回退、恢复连接和重复事件。
- E2E：工作台→企业→项目→上传→确认→进度→结果→Risk 详情。
- 数据边界测试：同一 Project 的两个 Assessment 不串 Risk、Evidence 和页面状态。
- 视觉测试：逐一对照 7 张效果图检查 1440/1366 桌面，并在 768/390/360 下检查响应式重排。
- 可访问性测试：Tab 顺序、焦点可见、表单标签、标题层级、图表替代文本和状态表达。
- 联调测试：抽查页面展示的 Assessment、Evidence、Regulation、Rule 与后端查询一致。

### 12. 风险与降级

- 若首页聚合接口延期，首页只展示真实的最近项目、最近 Assessment 和待复核列表，暂缓趋势及摘要卡。
- 若企业统计接口延期，保留筛选和分页主列表，统计区隐藏，不在前端从当前页数据推算全局数量。
- 若项目工作区聚合接口延期，按页签分别加载，但保持统一 URL 和局部错误状态。
- 若后端暂时无法提供连续进度，进度页只显示离散阶段、对象和真实计数，不使用计时器伪造百分比或预计时间。
- 若 PDF/DOCX 预览延期，Evidence 先展示文件、页码、段落、原文和上下文；定位按钮在能力就绪前明确禁用。
- 若服务端风险分页延期，可对固定演示集临时客户端分页，但必须限制加载量并标记为过渡方案，不能通过生产验收。
- 若完整人工复核流程延期，只保留 `NEEDS_REVIEW` 状态和受控入口，不伪造已提交或已处理结果。
- 若导出、整改任务或系统设置尚无后端能力，对应入口隐藏或禁用并说明前置条件。
- 若时间不足，最低生产级交付为：统一 AppLayout、企业管理、项目工作区、三步发起评估、真实进度页、风险工作台、Risk 决策链、移动适配和一条自动化 E2E。

---