# Arctra：单人 + AI 可执行开发计划 V1

> 适用场景：1 名主开发者 + AI 协作，目标是在不失控的前提下，把 Developer
> Agent Kit 从架构文档推进到可运行、可测试、可开源的 V1。
>
> 核心原则：**先验证内核，再扩能力；先 Vertical
> Slice，再做平台化；每个阶段都必须有可运行成果。**

------------------------------------------------------------------------

# 1. 总体目标

V1 不追求"大而全"。只证明四件事：

1.  一个统一的 `AgentClient` 可以驱动 Agent。
2.  `AgentRuntime` 与 `ExecutionEngine` 解耦。
3.  Knowledge Agent 和 Incident Agent
    两个不同场景可以复用同一套核心能力。
4.  项目具备开源项目最基本的工程质量：测试、文档、可观测、Starter、CI。

V1 完成后应该具备：

``` text
AgentClient
AgentDefinition
AgentRuntime
AgentExecutionEngine
Native ReAct Engine

Retrieval Pipeline
Tool Runtime
Evidence / Decision
Policy
Checkpoint

Spring Boot Starter
TestKit
Evaluation
Observability

Knowledge Assistant Example
Incident Investigator Example
```

------------------------------------------------------------------------

# 2. V1 明确不做

以下全部放入 V2/V3：

``` text
AgentScope Integration
Embabel Integration
Multi-Agent
A2A
GraphRAG
Wiki Compiler
Web Console
Distributed Runtime
Full Event Sourcing
Plugin ClassLoader
复杂 Tenant 系统
企业 RBAC
```

原因：一个人 + AI 最大风险不是能力不足，而是并行需求过多导致主线失焦。

------------------------------------------------------------------------

# 3. 需要几个 AI

建议最多使用 **3 个 AI 角色**，其中 1 个主力、2 个辅助。

## AI-1：主开发 AI

建议使用 Claude Code / Codex 类 Coding Agent。

职责：

``` text
编码
重构
测试
Maven
Spring Boot Starter
CI
文档同步
```

主开发 AI 必须长期持有项目上下文，是唯一允许大规模修改 Repository 的
AI。

## AI-2：架构 Reviewer

职责：

``` text
Architecture Review
API Review
DDD Boundary Review
Failure Semantics
兼容性 Review
过度设计检查
```

Reviewer AI 主要负责挑战设计，不直接大规模修改项目代码。

## AI-3：测试 / 红队 AI

职责：

``` text
测试用例设计
边界场景
异常场景
Chaos Scenario
安全检查
README 新用户视角
DX Review
```

它模拟框架使用者、贡献者和生产故障。

推荐协作流：

``` text
你：确定任务 / 验收标准
 ↓
AI-1：实现
 ↓
AI-2：Architecture Review
 ↓
AI-3：测试 / 挑错
 ↓
你：最终判断并 Merge
```

你承担 Product Owner、Architect、Tech Lead 和 Final Reviewer。

------------------------------------------------------------------------

# 4. 时间预算

如果业余开发，每周约 15\~20 小时：

``` text
建议 V1：10~12 周
```

如果接近全职，每周 35\~45 小时：

``` text
建议 V1：6~8 周
```

本文按 **10 周可执行计划** 设计。

现实建议：宁愿按 12 周完成高质量 V1，也不要用 3
周堆出一个无法维护的框架。

------------------------------------------------------------------------

# 5. 每周固定节奏

``` text
周一：确定本周 Issue / RFC / Acceptance Criteria
周二~周四：实现 Vertical Slice
周五：测试 / Review / Refactor
周六：Docs / Benchmark / Demo
周日：只复盘和下周计划，不开启新 Feature
```

若只能晚上开发，保持节奏不变，只拉长时间。

------------------------------------------------------------------------

# 6. Git 与 Issue 规则

一个人不要使用复杂 GitFlow。

推荐：

``` text
main = 随时可运行
feature/* = 小步开发
fix/*
docs/*
refactor/*
```

每个 Task 控制在 0.5\~2 天。超过 3 天必须拆分。

每个 Issue 必须包含：

``` text
Goal
Non-Goal
Design
Acceptance Criteria
Tests
Docs Impact
Compatibility Impact
```

示例：

``` text
Goal:
实现 AgentExecutionEngine 抽象。

Non-Goal:
暂不支持 AgentScope。

Acceptance Criteria:
- NativeReActEngine implements contract
- AgentClient API 不感知具体 Engine
- Engine 可配置
- Contract tests pass
```

------------------------------------------------------------------------

# 7. Phase 0：范围冻结

时间：2\~3 天。

目标：停止继续扩架构，冻结 V1 Scope。

任务：

``` text
确定项目名称
确定 groupId / artifactId
确定 Java / Spring Boot / Spring AI 版本
确定 License
冻结 V1 Architecture
建立 ADR
建立 ROADMAP
```

阶段产出：

``` text
README skeleton
ARCHITECTURE.md
ROADMAP.md
ADR-001 ~ ADR-005
Maven parent
```

验收：空项目执行 `mvn clean verify` 成功。

------------------------------------------------------------------------

# 8. Phase 1：Repository Skeleton

时间：第 1 周。

第一版只创建：

``` text
arctra-api
arctra-core
arctra-runtime-react
arctra-rag
arctra-tool
arctra-testkit
arctra-spring-boot-starter

examples/
  knowledge-assistant
  incident-investigator
```

增加：

``` text
JUnit
AssertJ
ArchUnit
JaCoCo
Spotless / Checkstyle
GitHub Actions
```

产出：Repository 可编译、CI 可运行、Architecture Rules 生效。

阶段 Demo：`./mvnw clean verify` 全部绿色。

------------------------------------------------------------------------

# 9. Phase 2：最小 Agent Kernel

时间：第 2 周。

实现最少核心模型：

``` text
AgentDefinition
AgentRequest
AgentResult
AgentExecution
AgentState
AgentBudget
ExecutionId
```

第一版 High-Level API：

``` java
agentClient
    .agent("hello-agent")
    .user("hello")
    .call()
    .content();
```

此阶段不做 Tool 和 RAG。

测试：

``` text
FakeChatModel
AgentClientTest
AgentRuntimeTest
```

验收：**无真实 LLM 也能完成完整 Agent Unit Test。**

------------------------------------------------------------------------

# 10. Phase 3：ExecutionEngine 解耦

时间：第 3 周。

实现：

``` text
AgentExecutionEngine
NativeReActExecutionEngine
EngineCapability
EngineResolver
```

核心约束：

``` text
AgentRuntime 不依赖 Native ReAct 实现
AgentClient 不知道具体 Engine
```

测试：

``` text
FakeExecutionEngine
NativeReActEngineContractTest
EngineCapabilityMismatchTest
```

阶段产出：Runtime Contract 第一版稳定。

------------------------------------------------------------------------

# 11. Phase 4：Tool Vertical Slice

时间：第 4 周。

实现：

``` text
Action
ToolDescriptor
ToolProvider
ToolInvocation
ToolExecutionResult
ToolRiskLevel
ToolExecutionSemantics
ToolRegistry
ToolResolver
```

Interceptor V1 只实现：

``` text
Tracing
Timeout
Policy Hook
```

不要此时做完整 Circuit Breaker / Rate Limit。

Mock Tools：

``` text
queryLogs
getDeployment
getGitDiff
```

阶段产出：

``` text
Agent → Action → Tool → Result
```

------------------------------------------------------------------------

# 12. Phase 5：Evidence / Decision

时间：第 5 周。

实现：

``` text
Evidence
EvidenceRef
EvidenceSource
TrustLevel
Decision
DecisionConfidence
```

目标：Agent 结果不再只是 String。

Incident Result：

``` text
Conclusion
Evidence[]
Confidence
RecommendedAction
```

阶段 Demo：

``` text
Root Cause:
Missing database migration

Evidence:
LOG-1
DEPLOY-1
GIT-1
```

这是 V1 第一个真正有辨识度的能力。

------------------------------------------------------------------------

# 13. Milestone A：Incident Agent MVP

时间点：第 5 周结束。

标准场景：

``` text
16:18 发布
16:20 大量 500
日志：Unknown column user_status
Git：新增 user_status
数据库：缺少字段
```

Agent 自动完成：

``` text
查 Logs
→ 查 Deployment
→ 查 Git
→ 形成 Evidence
→ 输出 Decision
```

必须同时产出：

``` text
可运行 Demo
自动化测试
2~3 分钟录屏素材
技术文章素材
```

------------------------------------------------------------------------

# 14. Phase 6：RAG Vertical Slice

时间：第 6\~7 周。

实现：

``` text
Retriever
RetrievedItem
RetrievalScores
FusionStrategy
Reranker
RetrievalPipeline
```

按顺序迭代：

``` text
Vector Only
→ BM25
→ BM25 + Vector
→ RRF
→ Rerank
```

不要一开始做 Adaptive RAG。

每一步都保留 Evaluation 数据。

------------------------------------------------------------------------

# 15. Milestone B：Knowledge Assistant MVP

时间点：第 7 周结束。

问题示例：

``` text
为什么 order-service 不能直接访问 user_db？
```

数据源：

``` text
ADR
Architecture Doc
Incident
Runbook
```

结果必须包含：

``` text
Answer
Evidence
Source
```

并能够比较：

``` text
Vector Only
BM25 Only
Hybrid
Hybrid + Rerank
```

至少输出：

``` text
Recall@K
NDCG
Hit Rate
```

这是第二个有技术深度的阶段成果。

------------------------------------------------------------------------

# 16. Phase 7：Checkpoint / HITL

时间：第 8 周。

实现：

``` text
Checkpoint
CheckpointStore
WAITING_APPROVAL
ResumeCommand
Approval
PreconditionRevalidation
```

V1 只实现 InMemoryCheckpointStore。

流程 Demo：

``` text
Agent 计划执行高风险 Tool
→ WAITING_APPROVAL
→ 返回控制权
→ 用户批准
→ Resume
→ Revalidate
→ Execute
```

必须明确：HITL 不是线程阻塞或 `Thread.interrupt()`。

------------------------------------------------------------------------

# 17. Phase 8：Session Log / Observability

时间：第 9 周。

实现：

``` text
SessionId
ExecutionId
SessionLogEntry
Sequence
SchemaVersion
Snapshot
```

V1 只做：

``` text
Trace Replay
```

暂不实现 Live Replay、复杂 Fork 和完整 Event Sourcing。

Observability：

``` text
Micrometer
Trace
Agent execution duration
Tool call count / latency
Retrieval latency
```

阶段成果：可以查看一次 Agent Execution 的完整 Timeline。

------------------------------------------------------------------------

# 18. Phase 9：TestKit / DX / Open Source Preview

时间：第 10 周。

实现：

``` java
AgentScenario.builder()
    .agent("incident-agent")
    .givenToolResult("queryLogs", "Unknown column user_status")
    .whenUser("order-service 为什么 500？")
    .expectToolCall("queryLogs")
    .expectEvidenceCount(1)
    .expectDecisionContains("schema")
    .verify();
```

同时完成：

``` text
README
Quick Start
Examples
Error Messages
Actuator Diagnostics
Configuration Reference
```

DX 验收：

``` text
3 分钟理解
10 分钟启动
30 分钟完成 Custom Tool / Retriever
```

------------------------------------------------------------------------

# 19. Milestone C：0.1.0 Public Preview

发布要求：

``` text
2 个可运行 Example
README
Architecture Overview
Quick Start
Tool Guide
RAG Guide
Testing Guide
```

工程要求：

``` text
CI Green
Unit Tests
Scenario / E2E Tests
ArchUnit
Evaluation Dataset
Sample Data
Docker Compose
```

版本建议：`0.1.0`，不要直接宣称 1.0。

------------------------------------------------------------------------

# 20. 每阶段 Definition of Done

任何功能必须同时满足：

``` text
Code
+
Unit Test
+
Integration / Scenario Test
+
Docs
+
Observability
```

例如 Tool Runtime 只有在"实现、测试、Trace 可见、Guide 可读、Example
真调用"全部满足时才算 Done。

------------------------------------------------------------------------

# 21. AI-1 开发 Prompt 模板

``` text
Task:
实现 XXX。

Context:
参考 ARCHITECTURE.md 和 ADR-XXX。

Constraints:
- 不新增未批准模块
- Core 不依赖 Spring Boot
- 不改变 Public API，除非明确说明
- 不引入新的框架
- 保持 DDD 依赖方向
- 必须补测试

Acceptance Criteria:
1.
2.
3.

Before coding:
先输出设计和涉及文件。

After coding:
运行 mvn test / verify，
总结修改、测试结果和风险。
```

------------------------------------------------------------------------

# 22. AI-2 Architecture Review 模板

``` text
请只 Review，不修改代码。

重点检查：
1. 是否违反 Runtime / Engine 边界？
2. 是否产生过度抽象？
3. 是否引入不必要依赖？
4. Domain 是否依赖 Infrastructure？
5. Public API 是否过度扩大？
6. Failure semantics 是否明确？
7. 是否可测试？
8. 是否存在更简单方案？

输出：
BLOCKER
MAJOR
MINOR
OPTIONAL
```

------------------------------------------------------------------------

# 23. AI-3 Test / Red Team 模板

``` text
你是框架测试工程师，不要假设 Happy Path。

针对当前功能设计：
- normal
- timeout
- null
- retry
- duplicate
- malformed model output
- dependency unavailable
- cancellation
- budget exceeded
- policy denied

输出：
测试场景
期望行为
是否需要自动化
```

------------------------------------------------------------------------

# 24. WIP 与 Backlog 管理

只使用：

``` text
Backlog
Ready
In Progress
Review
Done
```

WIP Limit：

``` text
In Progress <= 2
```

看到新技术、新框架、新想法时统一写入 `BACKLOG.md`，不打断当前 Phase。

------------------------------------------------------------------------

# 25. 每个 Milestone 必须可展示

Milestone A：

``` text
Incident Agent
Tool + Evidence + Decision
```

产出 Demo 视频。

Milestone B：

``` text
Knowledge Agent
Hybrid RAG + Rerank + Evaluation
```

产出 Benchmark / 技术文章。

Milestone C：

``` text
HITL + Checkpoint + TestKit + Observability
```

产出 GitHub Public Preview。

------------------------------------------------------------------------

# 26. 风险与时间缓冲

理想：8\~10 周。

现实：10\~12 周。

如果每周只有 8\~10 小时：14\~18 周。

最容易超时的不是写接口，而是：

``` text
RAG Evaluation
Runtime 恢复语义
Starter / DX
文档
稳定测试
```

建议保留 20% 时间作为缓冲。

------------------------------------------------------------------------

# 27. 你的时间分配

推荐：

``` text
40% Coding
20% Architecture / Review
20% Testing / Evaluation
15% Docs / DX
5% Research
```

避免：

``` text
70% 看新技术
20% 改架构图
10% Coding
```

现在已经进入执行阶段。

------------------------------------------------------------------------

# 28. AI 使用频率

AI-1：持续使用，负责主线实现。

AI-2：每周 1\~2 次 Architecture Review。

AI-3：每个核心模块和 Milestone 完成后做 Failure / Test Review。

不需要三个 AI 全天并行。

------------------------------------------------------------------------

# 29. 推荐 Release 节奏

``` text
0.0.1 Skeleton
0.0.2 Agent Kernel
0.0.3 Execution Engine
0.0.4 Tool + Incident
0.0.5 Evidence / Decision
0.0.6 RAG
0.0.7 Evaluation
0.0.8 HITL / Checkpoint
0.0.9 TestKit / Observability
0.1.0 Public Preview
0.2.0 AgentScope Integration
```

不是每个版本都必须发布 Maven Central，早期可使用 Git Tag / GitHub
Release。

------------------------------------------------------------------------

# 30. 为什么 AgentScope 放到 0.2.0

先证明自己的 Runtime Contract，再证明第三方 Engine 可以接入。

0.2.0 最强 Demo：

``` text
同一个 AgentClient
同一个 AgentDefinition
同一个 Evidence
同一个 Policy
同一个 TestKit

Native ReAct
→ AgentScope

业务调用 API 不变
```

这比 V1 就依赖 AgentScope 更能证明框架价值。

------------------------------------------------------------------------

# 31. 每周复盘问题

``` text
1. 本周有没有可运行成果？
2. 有哪些测试证明它工作？
3. 是否增加了不必要抽象？
4. 是否破坏 Core Boundary？
5. README / Docs 是否同步？
6. 新用户是否更容易使用？
7. 哪个设计被代码证明是错的？
```

第 7 个问题尤其重要：代码应该反向验证架构。

------------------------------------------------------------------------

# 32. 第一天具体任务

第一天不要写 AgentScope、Multi-Agent、GraphRAG。

只完成：

``` text
1. 创建 Git Repository
2. 创建 Maven Parent
3. 创建 7 个 V1 模块
4. 加 JUnit / ArchUnit / Spotless
5. 建 README / ARCHITECTURE / ROADMAP / ADR
6. 建 GitHub Actions
7. 提交 Skeleton
```

第二天开始实现：

``` text
AgentClient + AgentRuntime
```

------------------------------------------------------------------------

# 33. 总执行路线

``` text
Scope Freeze
   ↓
Repository Skeleton
   ↓
Agent Kernel
   ↓
ExecutionEngine
   ↓
Tool Runtime
   ↓
Evidence / Decision
   ↓
Incident MVP
   ↓
RAG Pipeline
   ↓
Knowledge MVP
   ↓
HITL / Checkpoint
   ↓
Session Log / Observability
   ↓
TestKit / DX
   ↓
0.1.0 Preview
   ↓
AgentScope Integration
```

------------------------------------------------------------------------

# 34. 最终原则

> **不要试图一次完成框架，而是连续完成可以验证架构的 Vertical Slice。**

对于"一个人 + AI"的模式，最高效的组织不是让很多 AI
同时疯狂写代码，而是：

``` text
1 个主 AI 稳定实现
+
1 个架构 AI 挑错
+
1 个测试 AI 找失败场景
+
你负责范围、判断和最终决策
```

只要坚持每周输出一个可运行成果，10\~12 周足以形成一个值得公开的
`0.1.0`，而不是停留在架构文档阶段。
