# Arctra 架构演进指南

**版本：** 1.0  
**最后更新：** 2026-08-18  
**状态：** Living Document

---

## 文档目的

本文档建立的是**何时以及为什么**创建新抽象的判断方法，而不是**现在应该创建哪些抽象**。

目标是避免由以下因素驱动的过早设计：
- Spring AI 当前的 API 形态
- 单个 Scenario 的特定需求
- 对未来需求的猜测
- 追求"架构纯洁性"的愿望

**核心原则：**  
只有当抽象能够隔离**真实的变化**或表达**新的领域语义**时，才创建它——而不是为了重命名外部框架。

---

## 1. 核心架构原则

### 1.1 Arctra 的长期定位

**Arctra 不是：**
- 通用 Agent Framework 的重新实现
- Java 版的 LangChain / AgentScope / LangGraph
- Spring AI 的镜像包装层
- 所有可能未来抽象的注册表

**Arctra 是：**

基于 Spring AI 基础能力，逐步建设面向实际业务和企业场景的 **Agent Runtime / Agent Platform**。

**架构层次：**

```
┌─────────────────────────────────────────────────────────────┐
│         Enterprise Agent Platform (Arctra)                  │
│   • Agent Lifecycle & Governance                            │
│   • Skill / Experience / Playbook                           │
│   • Policy / Approval / Audit                               │
│   • Observability / Evaluation                              │
│   • Enterprise Integration                                  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│      Spring AI Alibaba (Agent Engineering & Extension)      │
│   • Agent Framework / Graph / Workflow                      │
│   • Multi-Agent / MCP / RAG                                 │
│   • DashScope / Qwen Provider                               │
│   • Spring 生态集成与企业实践                                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│         AI Infrastructure (Spring AI)                       │
│   • ChatModel / EmbeddingModel / Message                    │
│   • ToolCallback / MCP / VectorStore                        │
│   • Tool Calling Loop / Advisor                             │
│   • Observation / Metrics                                   │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│       Model Providers / MCP / VectorStore                   │
│   • OpenAI / Anthropic / Qwen / etc.                        │
└─────────────────────────────────────────────────────────────┘

         AgentScope (架构设计参考，非依赖关系)
                            ↓
                   Agent Runtime 设计思想
```

**说明：**

这不代表代码上必须形成严格四层依赖。更准确的理解：
- **Spring AI** — 基础 AI 编程抽象和底层能力来源
- **Spring AI Alibaba** — Spring AI 生态中的扩展实现、企业实践和 Agent 能力来源
- **AgentScope** — Agent Runtime、生命周期、Skill、Experience、多 Agent 等方面的架构参考
- **Arctra** — 构建适合自身业务与企业治理需求的 Agent Runtime / Platform

### 1.2 核心原则

**不为了屏蔽底层框架而抽象，只抽象那些具有：**
1. **独立领域语义** — 表达 Agent Runtime 特有的概念
2. **独立生命周期** — 需要版本、发布、回滚、审批
3. **治理需求** — 需要权限、审计、租户隔离

**让 Spring AI 处理：**
- ChatModel 抽象
- Message 模型
- ToolCallback 接口
- Tool Calling Loop 自动化
- Model ↔ Tool 协议适配
- 参数 JSON Schema 解析
- MCP 集成
- VectorStore 抽象

**Arctra 增加：**
- **Agent 生命周期**语义（执行、治理、可观测性）
- **Evidence** 捕获和审计跟踪
- **Tool 调用治理**（权限、风险、审批、审计）
- **Skill / Experience / Playbook** 沉淀与复用
- **Policy** 和 HITL（Human-in-the-Loop）
- **Checkpoint/Resume** 支持长时间运行的 Agent
- **执行隔离**和多 Agent 编排
- **企业级可观测性**和治理
- **Spring 生态深度集成**（Spring Boot / Spring Security / Spring Cloud）

### 1.3 反模式示例

**错误：镜像抽象，没有新语义**
```java
// BAD: 只是改个名字
interface ArctraModel extends ChatModel { }
interface ArctraTool extends ToolCallback { }
interface ArctraMessage extends Message { }
record ArctraToolDefinition(String name, String desc, String schema) { }
```

这些只增加维护成本，不增加价值。

**错误：重复实现成熟框架已有能力**
```java
// BAD: 重新实现 Tool Calling Loop
class ArctraReActEngine {
    // 自己实现 Agent Loop
    // 自己实现 Tool 调用
    // 自己实现 Message 协议
}
```

Spring AI 已经提供了成熟的 Tool Calling Loop，不要重复造轮子。

---

## 2. 三层 API（方向，非实现）

### 2.1 Level 1: 用户面向 Agent API（未来）

**理想体验：**
```java
var result = arctra
    .agent("incident-investigator")
    .user("生产环境 16:20 开始出现大量 500，请分析")
    .call();
```

**这一层应该解决：**
- 向最终用户隐藏 Spring AI 实现细节
- 提供声明式的 Agent 调用
- 抽象掉 Engine 选择、Model 配置、Tool 连接

**这一层不应该解决：**
- 底层 Engine 调优
- Tool 实现细节
- Model 特定配置

**何时实现：**
- 至少存在 2 个不同的消费应用
- 当前手动连线在多个场景中变得重复
- AgentDefinition 解析/注册经过真实需求验证

**不要在以下情况之前实现：**
- M1 单场景验证
- 真实多场景使用

---

### 2.2 Level 2: Runtime / Extension Contracts（当前重点）

**当前状态（M1）：**
- `AgentExecutionEngine` — public 扩展契约
- `AgentRuntime` — package-private 内核契约
- `AgentDefinition`, `AgentRequest`, `AgentResult` — 领域模型
- `Evidence` — 框架级语义

**未来候选（未经验证不创建）：**
- AgentDefinitionResolver
- Tool/Capability Resolver  
- Engine Resolver
- Model Resolver
- Tool Contract

**对每个候选抽象必须回答的 7 个问题：**

1. **现在谁在使用它？**
2. **不创建它，当前哪个真实 Scenario 无法实现？**
3. **当前是否存在至少两个不同实现需要统一？**
4. **当前 Spring AI 类型泄漏是否已经造成真实问题？**
5. **这是领域抽象，还是仅仅对 Spring AI API 的重命名包装？**
6. **未来加入这个抽象是否会很困难？**
7. **如果未来容易加入，现在为什么必须提前加入？**

**只有有真实答案时才允许创建。**

---

### 2.3 Level 3: Integration / Engine Implementation（当前实现层）

**示例：**
- `arctra-runtime-react`
- 允许直接依赖 Spring AI

**当前实现：**
```
SpringAiToolCallingEngine
  ↓
ChatModel / ChatClient
  ↓
ToolCallback
  ↓
ToolCallingAdvisor
```

**关键原则：**

不需要为了所谓"架构纯洁性"把所有 Spring AI 类型包一层。

**如果：**
- ArctraModel 只是 ChatModel 的镜像
- ArctraTool 只是 ToolCallback 的镜像
- ArctraToolDefinition 只是 ToolDefinition 的镜像

**那么：**  
这些抽象没有产生新的领域语义，只增加维护成本。

**明确写入原则：**  
"Adapter / abstraction 必须隔离变化或者表达新的领域语义，否则不要创建。"

---

## 3. Tool 的未来演进模型（最重要）

### 3.1 不要简单理解为接口定义

**错误理解：**
```java
interface Tool {
    String name();
    String description();
    String schema();
    String execute(String args);
}
```

这很可能只是重新实现 Spring AI ToolCallback。

### 3.2 Tool 应该区分的 5 个层次

#### 层次 1：Tool Definition / Capability Definition

**回答："这个能力是什么？"**

例如 `queryLogs` 可能包含：
- name
- description  
- input contract
- output contract
- capability metadata

这一部分理论上可以被多个 Agent 共享。

---

#### 层次 2：Tool Implementation

**回答："这个能力实际上如何执行？"**

例如 `queryLogs` 可能有多种实现：
- Spring AI ToolCallback
- MCP Tool
- HTTP API
- Local Java Function

**何时分离 Definition 与 Implementation：**  
等真实需求出现后决定，**不要现在实现**。

---

#### 层次 3：Agent Tool Binding（非常关键）

**回答："某个 Agent 如何使用这个 Tool？"**

**示例场景：**

同一个 `queryLogs` Tool：

**Incident Investigator Agent：**
- production scope
- 最大查询 30 分钟
- 不允许敏感日志

**Security Investigator Agent：**
- production scope  
- 最大查询 24 小时
- 允许访问更多安全字段
- 更严格 Audit

**结论：**
- Tool capability 可以共享
- Permission / Scope / Governance **不应该**简单固化到 Tool 本身

---

#### 层次 4：Tool Invocation

**回答："这一次实际准备执行什么？"**

未来可能包含：
- agent
- tool
- arguments
- execution context
- environment  
- caller
- resource scope

**Governance 很可能针对 ToolInvocation 做决策，而不是只针对 ToolDefinition。**

---

#### 层次 5：Policy / Governance Decision

**未来应该考虑：**

```
Agent
  +
Tool
  +
Arguments
  +
Execution Context
    ↓
Policy Evaluation
    ↓
ALLOW / DENY / REQUIRE_APPROVAL
```

**明确写出：风险通常不是 Tool 的固有属性。**

**示例：**

`executeSql("SELECT ...")` 和 `executeSql("DROP TABLE ...")` 虽然是同一个 Tool，但风险完全不同。

`restartService(test)` 和 `restartService(production)` 风险也不同。

**因此不要过早设计：**
```java
Tool.riskLevel()
Tool.requiresApproval()
```

这种把动态治理语义固化在 Tool Definition 上的 API。

**更合理的长期方向：**

```
Shared Tool Capability
        ↓
Agent Tool Binding
        ↓
Tool Invocation
        ↓
Policy Evaluation
        ↓
ALLOW / DENY / REQUIRE_APPROVAL
```

**但注意：这只是未来 architecture direction。当前 M1 不实现这些类型。**

---

## 4. Model 的未来处理原则

### 4.1 当前状态是合理的

**ChatModel 是 Engine implementation dependency。**

当前类似：
```java
new SpringAiToolCallingEngine(chatModel, tools)
```

在 integration 层是**完全合理的**。

### 4.2 不要立即创建的抽象

**不要为了屏蔽 Spring AI 就立即创建：**
- ArctraModel
- ModelProvider
- ModelRegistry  
- ModelDescriptor
- ModelSelector

### 4.3 何时考虑 Model 抽象

**未来真正出现以下需求时，再考虑：**
- 一个 Agent 使用不同 provider
- 多模型 routing
- fallback
- cost-based selection
- capability-based selection  
- 用户配置 model reference
- 非 Spring AI Engine 需要共享 model semantics

### 4.4 未来可能的形态

**AgentDefinition 更可能引用：**
```java
modelRef = "reasoning-model"
```

**而不是直接持有：**
```java
ChatModel chatModel
```

**但当前没有真实需求时不要实现 ModelRef / ModelRegistry。**

---

## 5. AgentDefinition 的长期方向

### 5.1 核心原则

**AgentDefinition 应该描述 Agent，而不是携带 integration runtime objects。**

### 5.2 未来可能的形态

```java
AgentDefinition
  - name
  - description
  - toolRefs          // 未来
  - modelRef          // 未来
  - policyRef         // 未来
```

**但不要现在把这些字段全部加入。**

### 5.3 明确禁止的设计

**AgentDefinition 不应该为了 Spring AI Engine 而直接包含：**
```java
List<ToolCallback> tools
ChatModel chatModel
ToolCallingAdvisor advisor
```

因为这些是 **integration/runtime object**，不是 **Agent declaration**。

### 5.4 未来如果需要 declarative Agent

```java
toolRefs = ["queryLogs", "getDeployment"]
```

Runtime 再负责：
```
reference → resolve → runtime implementation
```

**但是 Resolver / Registry 也必须等真实需求出现以后再创建。**

---

## 6. 抽象触发条件（最重要部分）

为以下候选抽象分别定义明确的"什么时候才应该创建"。

### 候选 1：Arctra Tool Contract

**Current state:**  
M1 直接使用 Spring AI ToolCallback，examples 中的 Mock Tools（QueryLogsTool, GetDeploymentTool）直接实现 ToolCallback。

**Do NOT introduce when:**
- 唯一 runtime 是 Spring AI
- Tool 只是 ToolCallback 的一对一包装
- 没有 Governance consumer
- 没有第二种 Tool runtime
- 只是为了"看起来更纯"

**Introduce when:**
- Governance 需要 framework-neutral ToolInvocation
- 同一 Tool capability 需要跨多个 Engine（例如 Spring AI + MCP + Custom）
- MCP / Spring AI / remote tool 需要共享上层语义
- AgentDefinition 需要稳定引用 Tool capability（不依赖 Spring AI ToolCallback）
- Tool Definition 和 Tool Implementation 真正需要分离

**Required evidence:**
- 至少 2 个不同的 Tool runtime 实现
- 或：Governance layer 已经存在且需要统一的 Tool 抽象
- 或：AgentDefinition 中 toolRefs 的真实消费者

**Boundary:**  
如果创建，属于 `arctra-core`（framework contract）。

**Anti-pattern:**  
仅仅把 ToolCallback 的方法改个名字：
```java
// BAD
interface Tool {
    ToolDefinition definition();  // = getToolDefinition()
    String execute(String args);  // = call(String)
}
```

这没有增加新语义。

---

### 候选 2：ToolDefinition（Arctra 自己的）

**Current state:**  
使用 Spring AI ToolDefinition。

**Do NOT introduce when:**
- Spring AI ToolDefinition 满足需求
- 只有 Spring AI 一个 runtime
- 没有额外的 capability metadata 需要携带

**Introduce when:**
- 需要携带 Spring AI ToolDefinition 不支持的元数据（例如 capability tags, resource scope）
- 多个 runtime 需要统一的 Tool 元数据格式
- AgentDefinition 需要声明 Tool，而不依赖 Spring AI 类型

**Required evidence:**
- 至少一个真实的"Spring AI ToolDefinition 无法表达"的元数据需求
- 或：AgentDefinition 中 toolRefs 的真实使用

**Boundary:**  
如果创建，属于 `arctra-core`。

**Anti-pattern:**  
只是把 Spring AI ToolDefinition 的字段复制一遍。

---

### 候选 3：ToolRegistry / ToolResolver

**Current state:**  
不存在。Spring AI 有 ToolCallbackProvider/Resolver，但 Arctra 未使用。

**Do NOT introduce when:**
- Tools 是静态注入到 Engine constructor 的
- 没有"根据 name/ref 查找 Tool"的需求
- 只有一个 Scenario

**Introduce when:**
- AgentDefinition 通过 toolRefs（字符串）引用 Tool
- 需要从多个来源（Spring Bean、配置文件、MCP、HTTP）加载 Tool
- 多个 Agent 共享同一批 Tool，需要集中管理

**Required evidence:**
- AgentDefinition 包含 `List<String> toolRefs`
- 至少 2 个 Agent 需要动态解析 Tool

**Boundary:**  
如果创建，属于 `arctra-runtime`（runtime contract）。

**Anti-pattern:**  
只有一个 Agent、Tools 写死在代码里，却创建 Registry。

---

### 候选 4：Agent Tool Binding

**Current state:**  
不存在。

**Do NOT introduce when:**
- 每个 Tool 只被一个 Agent 使用
- 所有 Agent 对 Tool 的治理要求完全相同
- 没有 per-Agent permission/scope 需求

**Introduce when:**
- 同一个 Tool 被多个 Agent 使用，但每个 Agent 的 permission/scope/governance 要求不同
- 需要 per-Agent 的 Tool 配置（例如 timeout、retry、scope）
- Governance 需要知道"哪个 Agent 在用哪个 Tool"

**Required evidence:**
- 至少 2 个 Agent 使用同一个 Tool，但治理要求不同
- 或：Policy 需要基于 Agent+Tool 组合做决策

**Boundary:**  
如果创建，属于 `arctra-runtime`或独立的 `arctra-governance`。

**Anti-pattern:**  
把 permission/scope 固化到 Tool Definition 里。

---

### 候选 5：ToolInvocation

**Current state:**  
不存在。

**Do NOT introduce when:**
- 没有 Governance / Policy / Audit 需求
- Tool 执行不需要 context（agent, caller, environment）

**Introduce when:**
- Governance 需要基于"这一次调用"做决策（不只是基于 Tool Definition）
- Policy 需要检查 arguments / execution context / caller
- Audit 需要记录完整的调用上下文

**Required evidence:**
- Governance / Policy layer 已经存在
- 需要区分"Tool 是什么"和"这次调用准备做什么"

**Boundary:**  
如果创建，属于 `arctra-governance` 或 `arctra-runtime`。

**Anti-pattern:**  
在没有 Governance 需求时提前创建。

---

### 候选 6：Governance / Policy

**Current state:**  
不存在。

**Do NOT introduce when:**
- 所有 Tool 都是 Mock，无需治理
- 只有一个 Scenario
- 没有真实的 permission / risk / approval 需求

**Introduce when:**
- 需要在 Tool 执行前检查 permission
- 需要基于 Tool + arguments + context 评估风险
- 需要 HITL（人工审批）
- 需要 Audit 记录

**Required evidence:**
- 至少一个真实场景需要"拦截 Tool 调用"
- 至少一个真实的 Policy 规则（例如"生产环境操作需要审批"）

**Boundary:**  
如果创建，属于独立的 `arctra-governance` 或 `arctra-policy`。

**Anti-pattern:**  
在 M1 Mock Tools 阶段就创建 Governance。

---

### 候选 7：Model Abstraction

**Current state:**  
直接使用 Spring AI ChatModel。

**Do NOT introduce when:**
- 只有 Spring AI 一个 Model provider
- AgentDefinition 不需要引用 Model
- 没有 multi-model routing / fallback 需求

**Introduce when:**
- 一个 Agent 需要使用不同 provider 的 Model
- 需要 multi-model routing（例如 reasoning model + fast model）
- 需要 fallback / cost-based selection
- 非 Spring AI Engine 需要共享 model semantics
- AgentDefinition 需要通过 modelRef 引用 Model

**Required evidence:**
- 至少 2 个不同的 Model provider
- 或：AgentDefinition 包含 modelRef

**Boundary:**  
如果创建，属于 `arctra-core`（如果是 framework contract）或 `arctra-runtime`（如果是 runtime contract）。

**Anti-pattern:**  
只是把 ChatModel 改个名字。

---

### 候选 8：ModelResolver

**Current state:**  
不存在。

**Do NOT introduce when:**
- Model 是静态注入到 Engine constructor 的
- AgentDefinition 不包含 modelRef

**Introduce when:**
- AgentDefinition 通过 modelRef（字符串）引用 Model
- 需要从配置文件 / Spring Bean / 外部 registry 加载 Model

**Required evidence:**
- AgentDefinition 包含 `String modelRef`
- 至少 2 个 Agent 需要动态解析 Model

**Boundary:**  
如果创建，属于 `arctra-runtime`。

**Anti-pattern:**  
只有一个 Agent、Model 写死在代码里，却创建 Resolver。

---

### 候选 9：AgentClient / Arctra Facade

**Current state:**  
不存在。用户直接手动构造 Engine + Tools。

**Do NOT introduce when:**
- 只有一个 Scenario
- 当前手动连线不重复
- AgentDefinition 还没有 toolRefs / modelRef

**Introduce when:**
- 至少 2 个不同的消费应用
- 手动连线在多个场景中变得重复
- AgentDefinition resolution / Tool resolution / Model resolution 都已经存在并验证

**Required evidence:**
- 至少 2 个 example 应用
- 当前手动连线代码重复超过 3 次

**Boundary:**  
如果创建，属于 `arctra-api`（user-facing API）。

**Anti-pattern:**  
在 M1 单场景验证阶段就创建 facade。

---

### 候选 10：EngineResolver / EngineFactory

**Current state:**  
不存在。只有一个 Engine：SpringAiToolCallingEngine。

**Do NOT introduce when:**
- 只有一个 Engine 实现
- AgentDefinition 不需要声明"使用哪个 Engine"

**Introduce when:**
- 至少 2 个不同的 Engine 实现（例如 Spring AI + AgentScope）
- AgentDefinition 需要通过 engineRef 选择 Engine
- 需要基于 capability / cost / availability 动态选择 Engine

**Required evidence:**
- 至少 2 个 Engine 实现
- AgentDefinition 包含 `String engineRef`

**Boundary:**  
如果创建，属于 `arctra-runtime`。

**Anti-pattern:**  
只有一个 Engine 时就创建 Factory。

---

## 7. 每个 Milestone 的设计方法

以后每进入一个新的 Task/Milestone，**不要先问："应该创建哪些类？"**

**而应该按照以下顺序：**

### Step 1：Scenario

这个 Milestone 要让用户完成什么真实事情？

### Step 2：Execution Path

画出真实调用链：
```
User
  ↓
Agent API
  ↓
Runtime
  ↓
Engine
  ↓
Model
  ↓
Tool
  ↓
Result
```

### Step 3：Ownership

调用链中的每一个语义属于谁？
- Arctra domain
- Runtime
- Integration
- Scenario
- External framework

### Step 4：Leak Detection

是否有 infrastructure type 穿透到了不应该出现的上层？

如果有，先判断：
- 这是实际 coupling problem
- 还是只是"看起来不够纯"？

### Step 5：Abstraction Test

对于准备创建的每个 abstraction，回答本文档第 6 节的问题：
- 现在谁在用？
- 不加做不了什么？
- 是否有两个实现？
- 是否表达新的领域语义？
- 还是只是在 rename Spring AI？

### Step 6：Minimum Change

只实现当前 Scenario 必须的最小 API。

### Step 7：Future Hook

确认未来扩展没有被堵死。

**注意："未来可扩展"不等于"现在提前实现扩展点"。**

### Step 8：Acceptance Test

用 Scenario 验证，而不是只验证 class structure。

---

## 8. 成熟框架借鉴原则与防重复建设

### 8.1 AgentScope 等框架的研究价值

**AgentScope、LangChain、LangGraph 等成熟 Agent Framework 是重要的参考系，但不是实现目标。**

**学习它们的价值：**
- Agent 生命周期设计思想
- Agent Runtime 架构模式
- Tool 使用机制
- Memory / Session / Context 管理
- Hook / Middleware 设计
- Skill / Experience 沉淀模式
- 多 Agent 协作模式
- 执行过程管理
- 成功经验沉淀与复用
- 失败反馈与迭代
- 可观测性设计

**但明确：**
> 学习设计思想 ≠ 复制 API 和代码结构

### 8.2 三层判断法（防重复建设）

研究任何成熟框架的能力时，必须进行三层判断：

#### 第一层：Spring AI 是否已经解决?

**如果 Spring AI 已经提供成熟能力，优先直接使用：**
- ChatModel
- EmbeddingModel
- Message
- ToolCallback
- Advisor
- MCP
- VectorStore
- Tool Calling Loop
- Prompt Template
- Observation / Metrics

**除非存在明确的平台级需求，否则不要重新定义：**
```java
// BAD: 薄包装接口
interface MyChatModel extends ChatModel { }
interface MyTool extends ToolCallback { }
interface MyMessage extends Message { }
interface MyVectorStore extends VectorStore { }
interface MyEmbeddingModel extends EmbeddingModel { }
```

#### 第二层：Spring AI Alibaba 是否已经提供?

**Spring AI Alibaba 不仅是"阿里模型适配层"，更是 Spring AI 生态中的重要 Agent 工程实践和能力扩展来源。**

**重点关注其以下能力：**
- Agent Framework
- Graph / Workflow
- Multi-Agent
- MCP 集成
- RAG 增强能力
- Tool Calling 扩展
- Memory 管理
- Human-in-the-loop
- Planning
- State Management
- Agent Runtime
- Observability
- DashScope / Qwen Provider
- 企业场景集成
- 分布式 Agent 能力
- 持久化
- Workflow / Graph 编排

**四种处理策略：**

**1. Direct Use（直接使用）**
```java
// 如果能力满足需求，直接使用
// 不要增加额外抽象
```

**2. Composition（组合）**
```java
// 如果需要增加企业治理能力，优先采用组合
// 例如：Spring AI Alibaba Agent + ToolPermission + Audit + TenantContext
// 而不是：重新写一套 MyAgent
```

**3. Adapter（适配）**
```java
// 只有当存在明确集成边界时才允许轻量 Adapter
// 例如：SpringAiAlibabaGraphAdapter
// Adapter 必须是接口转换层，而不是重新定义整个领域模型
```

**4. Reimplementation（重新实现）**
```
只有在满足以下条件时才允许：
- 上游能力无法满足真实需求
- 关键能力不可扩展
- 存在明显性能或治理问题
- 企业级需求和通用框架设计发生本质冲突
- 该能力已成为本项目核心差异化资产

重新实现必须在 ADR / 架构文档中说明原因。
```

#### 第三层：是否属于通用 Framework 能力？

**如果以下能力已被成熟框架很好地解决，谨慎自研：**
- 基础 Agent 抽象
- 通用 Tool 抽象
- 基础 ReAct Loop
- 通用 Message Model
- 普通 Agent Loop
- 通用模型适配层
- 通用 Graph / Workflow
- 通用 Memory 管理

**必须回答：**
> "我们重新实现它能够获得什么长期不可替代的价值？"

**如果回答只是：**
- "以后可能更灵活"
- "可以屏蔽底层"
- "以后可能替换框架"

**则不足以构成新的抽象理由。**

#### 第四层：是否属于 Arctra 真正应该建设的平台能力？

**重点关注：**

**Agent Runtime & Lifecycle：**
- AgentDefinition（版本化、声明式）
- AgentVersion
- AgentSession
- ExecutionContext
- ExecutionTrace
- ExecutionReplay

**Model & Cost Governance：**
- ModelPolicy
- ModelRouting
- ModelFallback
- TokenBudget
- CostPolicy

**Tool Governance：**
- ToolPolicy
- ToolPermission
- ToolApproval
- ToolAudit

**Skill & Experience：**
- SkillRegistry
- SkillVersion
- ExperienceRegistry
- Playbook

**Enterprise Integration：**
- Tenant Isolation
- RBAC
- Audit
- Observability
- Evaluation

**与现有体系深度集成：**
- Spring / Spring Boot / Spring Security
- Spring AI / Spring AI Alibaba / MCP
- 数据库 / MQ / 配置中心
- 权限体系 / 监控体系

**这些能力才应该成为 Arctra 长期积累的核心资产。**

### 8.3 架构演进防重复建设检查清单

**新增 Agent Runtime 能力前，必须回答：**

**基础能力层：**
1. ✅ **Spring AI 是否已经提供？** 如果是，为什么不直接用？
2. ✅ **Spring AI Alibaba 是否已经提供成熟实现？** 如果是，为什么还需要？

**通用框架层：**
3. ✅ **AgentScope 或其他成熟框架是否已经提供？** 如果是，我们为什么还需要？
4. ✅ **这是基础框架能力，还是企业 Agent Platform 能力？** 基础能力应复用，平台能力才自建。

**领域价值层：**
5. ✅ **是否具有独立领域语义？** 不只是改名字。
6. ✅ **是否具有独立生命周期？** 需要版本、发布、回滚、审批。
7. ✅ **是否存在权限、审计、版本、租户、发布、回滚等治理需求？**

**必要性层：**
8. ✅ **如果删除这一层，业务是否真的会受到影响？**
9. ✅ **当前需求是否真实存在，还是纯粹为了"以后可能需要"？**
10. ✅ **能否先直接使用 Spring AI / Spring AI Alibaba，等第二个真实场景出现后再抽象？**
11. ✅ **未来加入这个抽象是否会很困难？** 如果容易加，为什么现在必须加？

**只有能够明确回答这些问题，才应该新增核心抽象。**

### 8.4 能力复用决策流程

```
出现新需求
    ↓
Spring AI 是否已有？
    ├─ 是 → 直接使用
    ↓
Spring AI Alibaba 是否已有？
    ├─ 是 → 评估四种策略
    │       1. Direct Use（直接使用）
    │       2. Composition（组合增强）
    │       3. Adapter（轻量适配）
    │       4. Reimplementation（重新实现，需充分理由）
    ↓
AgentScope / 其他成熟 Agent Framework 是否已有成熟实践？
    ├─ 是 → 学习设计思想，不复制 API
    ↓
当前项目是否真的存在差异化需求？
    ├─ 否 → 等待真实需求
    ├─ 是 → 评估是否属于平台核心能力
    ↓
决定：直接使用 / 组合使用 / 轻量适配 / 自研
```

### 8.5 架构决策优先级

**以后遇到新能力，按以下优先级处理：**

```
1. Spring AI 直接解决
        ↓
2. Spring AI Alibaba 直接解决
        ↓
3. 组合已有能力解决
        ↓
4. 轻量 Adapter
        ↓
5. 平台领域能力抽象
        ↓
6. 最后才考虑自研基础框架
```

**必须把"重新实现通用 Framework"放在最后。**

### 8.9 技术雷达（Tech Radar）

#### Adopt（当前采用）

**已采用并应优先使用：**
- Spring Boot
- Spring AI
- Maven
- JUnit 5 / AssertJ

#### Assess（持续评估）

**持续跟踪，评估引入时机：**
- **Spring AI Alibaba** — Agent / Graph / Workflow / MCP / RAG
- **AgentScope** — 架构设计参考
- MCP 深度集成
- Agent Graph / Workflow
- Experience Learning
- Skill Runtime

#### Trial（试验阶段）

**只允许在 PoC 或独立实验中尝试：**
- 自动经验晋升
- Agent 自修改
- 动态 Skill 生成
- 多 Agent 自动协作
- 长期 Autonomous Agent

#### Hold（暂不建议）

**暂不建议投入：**
- 自建 ChatModel 抽象
- 自建 Tool 基础抽象
- 自建 Message Protocol
- 自建通用 VectorStore
- 自建完整 Agent Framework
- 重新实现 Spring AI 基础能力
- 重新实现 Spring AI Alibaba 已有能力

**Tech Radar 应随项目演进调整。**

### 8.10 核心价值定位总结

**四层关系清晰定义：**

**Spring AI** — 解决"AI 怎么调用"
- 基础 AI 编程抽象
- Model / Message / Tool / VectorStore
- Tool Calling Loop
- Observation / Metrics

**Spring AI Alibaba** — 解决"Spring 生态下 Agent 怎么工程化"
- Agent Framework / Graph / Workflow
- Spring Bean / DI 集成
- 企业 Java 项目集成
- 分布式 Agent 能力

**AgentScope** — 帮助理解"Agent Runtime 应该怎么设计"
- 架构参考系
- 设计思想来源
- 生命周期管理模式

**Arctra** — 解决"企业中的 Agent 怎么被定义、运行、治理、评估和持续演进"
- Agent 生命周期（版本、发布、回滚、审批）
- Tool Governance（权限、风险、审批、审计）
- Skill / Experience（成功模式沉淀）
- Policy / HITL（动态治理）
- Execution Trace / Checkpoint / Resume
- 企业集成（Spring Security / Multi-tenancy / RBAC）

### 8.11 策略总结

**尽量复用下层成熟 execution primitives。**

**把设计精力投入到上层 Agent Runtime / Governance semantics。**

**AgentScope 应该成为：架构参考系，而不是实现目标。**

**Spring AI Alibaba 应该成为：能力来源，优先复用实现。**

**Arctra 的价值不应该来自：**
- "我们也有自己的 ChatModel"
- "我们也有自己的 ToolCallback"
- "我们也有自己的 ToolDefinition"
- "我们也有自己的 Message Model"

**这些 Spring AI 已经做得很好。**

**Arctra 更值得建立自己的领域能力：**
- **Agent lifecycle** — 版本、发布、回滚、审批
- **Execution semantics** — 执行上下文、执行跟踪、重放
- **Evidence** — 证据捕获、审计跟踪
- **Tool invocation governance** — 权限、风险、审批、审计
- **Policy** — 动态策略评估
- **Approval / HITL** — 人工审批流程
- **Audit** — 完整审计日志
- **Checkpoint / Resume** — 长时间运行支持
- **Execution isolation** — 执行隔离
- **Skill / Experience / Playbook** — 成功模式沉淀与复用
- **Multi-agent orchestration** — 如果未来真实需要
- **可观测性** — 企业级监控
- **企业级运行治理** — Spring 生态深度集成

### 8.12 Arctra 真正应该建立差异化的地方

**Arctra 的价值不应该来自：**
- "我们也有自己的 ChatModel"
- "我们也有自己的 ToolCallback"
- "我们也有自己的 ToolDefinition"
- "我们也有自己的 Message Model"
- "我们也有自己的 Graph / Workflow"

**这些 Spring AI / Spring AI Alibaba 已经做得很好。**

**Arctra 更值得建立自己的领域能力：**
- **Agent lifecycle** — 版本、发布、回滚、审批
- **Execution semantics** — 执行上下文、执行跟踪、重放
- **Evidence** — 证据捕获、审计跟踪
- **Tool invocation governance** — 权限、风险、审批、审计
- **Policy** — 动态策略评估
- **Approval / HITL** — 人工审批流程
- **Audit** — 完整审计日志
- **Checkpoint / Resume** — 长时间运行支持
- **Execution isolation** — 执行隔离
- **Skill / Experience / Playbook** — 成功模式沉淀与复用
- **Multi-agent orchestration** — 如果未来真实需要
- **可观测性** — 企业级监控
- **企业级运行治理** — Spring 生态深度集成

### 8.13 长期架构判断标准

**错误演进信号 1：重新包装 Spring AI**

如果未来项目逐渐出现大量：
```java
MyChatModel
MyTool
MyMessage
MyMemory
MyVectorStore
MyEmbeddingModel
```

并且内部主要只是：
```java
MyXXX → Spring AI XXX
```

**说明架构正在错误地向"重新包装 Spring AI"演进，需要立即重新评估。**

**错误演进信号 2：重新实现成熟框架**

如果项目逐渐开始自行实现：
- 通用 Agent Framework
- 通用 Tool Framework
- 通用 Message Protocol
- 通用 Model Adapter
- 通用 Graph / Workflow Engine

并且与 Spring AI Alibaba / AgentScope 等成熟框架高度重合，也需要重新评估项目定位。

**我们真正希望看到的演进：**

```
Spring AI (基础设施)
    ↓
Spring AI Alibaba (Agent 工程化)
    ↓
Agent Runtime (生命周期、执行、治理)
    ↓
Agent Definition / Session / Context (声明式 Agent)
    ↓
Skill / Experience / Playbook (成功模式沉淀)
    ↓
Policy / Governance (动态治理)
    ↓
Observability / Evaluation (可观测性)
    ↓
Enterprise Integration (Spring 生态集成)
```

**而不是：**

```
Spring AI
    ↓
自己重新包装一套 Spring AI
    ↓
自己重新实现一套 Spring AI Alibaba
    ↓
自己重新实现一套 AgentScope
```

### 8.6 AgentScope vs Spring AI Alibaba：不同的参考价值

**AgentScope 和 Spring AI Alibaba 都值得学习，但关注点不同。**

#### AgentScope 更适合重点研究：

- Agent 生命周期
- Agent 抽象
- Skill / Experience 沉淀模式
- 多 Agent 协作
- Runtime 架构
- Memory / State 管理
- Agent 自主演进
- 成功模式沉淀

**参考方向：** 设计思想、架构模式、生命周期管理

#### Spring AI Alibaba 更适合重点研究：

- Spring AI 生态下如何工程化 Agent
- Graph / Workflow 编排
- 状态流转
- Spring Bean / DI 集成
- 企业 Java 项目集成
- MCP 集成
- RAG 增强能力
- Model Provider（Qwen / DashScope）
- 分布式或生产级 Agent 能力

**参考方向：** 工程实践、Spring 生态集成、可复用能力

#### 核心原则

**不要简单判断"谁更好"。应该判断：哪个设计最适合成为本项目某一层的参考。**

**AgentScope** — 架构参考系，学习设计思想  
**Spring AI Alibaba** — 能力来源，优先复用实现

### 8.7 Graph / Workflow 不重复造轮子原则

**Spring AI Alibaba 很可能在 Graph / Workflow 方向提供越来越多能力。**

**未来如果项目出现以下需求：**
- Node / Edge / State
- Conditional Routing
- Parallel Node
- Retry / Error Handling
- Human Node / Approval Node
- Workflow Engine

**必须优先评估：**
1. Spring AI Alibaba Graph
2. Spring AI
3. 现有流程引擎

**不要第一反应就自行设计：**
```java
// BAD: 重复造轮子
MyGraph
MyNode
MyEdge
MyWorkflowEngine
```

**只有当前框架无法满足以下真实需求时，再考虑扩展：**
- 企业治理（权限、审批、审计）
- 复杂状态管理
- 可恢复执行 / Checkpoint
- 持久化
- 多租户隔离
- 长流程支持
- 版本控制

### 8.8 依赖框架但不被框架反向绑架

**避免重复造轮子 ≠ 把所有业务代码直接绑定到框架 API。**

#### 可以直接依赖框架的地方

**Infrastructure / Runtime 实现层：**
```java
// 正确：Runtime 层直接使用框架
SpringAiToolCallingEngine implements AgentExecutionEngine {
    private final ChatClient chatClient;  // Spring AI
    private final List<ToolCallback> tools;  // Spring AI
}
```

**示例：**
- Spring AI
- Spring AI Alibaba
- MCP
- VectorStore
- Model

#### 不应该直接暴露框架概念的地方

**真正属于本项目业务领域的对象：**
```java
// 正确：领域模型保持独立
record AgentDefinition(String name, String description)
record AgentVersion(String agentId, String version, ...)
record Skill(String id, String name, ...)
record Experience(String id, String content, ...)
record ToolPolicy(String toolId, List<Rule> rules, ...)
record ApprovalPolicy(String policyId, ...)
record Tenant(String id, String name, ...)
record ExecutionTrace(String executionId, ...)
```

**这些应保持自身领域语义。**

#### 架构边界

```
            Platform Domain
                  │
      Agent / Skill / Policy / Evidence
                  │
              Runtime
                  │
      ┌───────────┴────────────┐
      │                        │
  Spring AI           Spring AI Alibaba
```

**而不是：**

```
业务代码
   ↓
Spring AI Alibaba (所有层强绑定)
   ↓
Spring AI
```

**换句话说：**
> 不要包装 Spring AI 的基础接口，但要保护属于自己平台的领域模型。

---

## 9. 当前 M1 边界确认

### 9.1 M1 实际交付内容

**arctra-core（纯 Java）：**
- `AgentDefinition(name, description)`
- `AgentRequest(userMessage)`
- `AgentResult(content, evidences)`
- `Evidence(source, content)`
- `AgentExecutionEngine` 接口
- `AgentRuntime` 接口（package-private）
- `DefaultAgentRuntime` 实现（package-private）

**arctra-runtime-react（Spring AI 集成）：**
- `SpringAiToolCallingEngine implements AgentExecutionEngine`
- `EvidenceCapturingToolCallback`（package-private wrapper）
- 依赖：`spring-ai-client-chat` → `spring-ai-model` → `spring-ai-commons`

**examples/incident-investigator：**
- `QueryLogsTool implements ToolCallback`
- `GetDeploymentTool implements ToolCallback`
- E2E 测试

### 9.2 边界评估

#### ✅ 合理的设计

1. **arctra-core 保持纯 Java**
   - 无 Spring AI compile 依赖 ✅
   - Maven Enforcer 强制禁止 Spring/Spring AI/Elasticsearch/Redis compile 依赖 ✅

2. **Spring AI 依赖限制在 arctra-runtime-react**
   - `SpringAiToolCallingEngine` 直接使用 `ChatModel`, `ChatClient`, `ToolCallback` ✅
   - 这是 integration 层，合理 ✅

3. **Evidence 是 framework-neutral 的**
   - `Evidence(source, content)` 在 arctra-core ✅
   - 不依赖 Spring AI ✅
   - 可以被未来其他 Engine 复用 ✅

4. **AgentExecutionEngine 是 public extension contract**
   - 允许第三方实现自己的 Engine ✅

5. **AgentRuntime 是 package-private**
   - 当前只是内部契约，未来是否 public 由真实需求验证 ✅

6. **Examples 直接实现 Spring AI ToolCallback**
   - Mock Tools 是 scenario fixture，不是 framework capability ✅
   - 直接实现 ToolCallback 合理，避免过早抽象 ✅

#### ⚠️ 需要明确标记的实现细节

以下是 **M1 implementation detail**，不是长期 Framework Contract：

1. **SpringAiToolCallingEngine 的 constructor**
   ```java
   public SpringAiToolCallingEngine(ChatModel chatModel, List<ToolCallback> tools)
   ```
   - `ChatModel` 和 `ToolCallback` 暴露在 public API
   - 这是当前合理的，但未来可能需要：
     - AgentDefinition 引用 modelRef / toolRefs
     - Engine 从 Resolver 获取 Model / Tools
   - **标记：M1 pragmatic choice，未来可能演进**

2. **Evidence.source 格式**
   ```java
   "tool:queryLogs"
   ```
   - 这是约定，不是强制格式
   - **标记：M1 convention，未来可能需要结构化**

3. **AgentDefinition 只有 name + description**
   - 未来可能增加：toolRefs, modelRef, policyRef 等
   - **标记：M1 minimal，按需扩展**

#### ❌ 没有发现的边界泄漏

- arctra-core 没有 Spring AI 类型 ✅
- examples 依赖 Spring AI 是合理的（它们是 integration 消费者）✅
- arctra-runtime-react 依赖 Spring AI 是合理的（它是 integration 实现）✅

### 9.3 当前架构图

```
┌─────────────────────────────────────────┐
│         examples (application)          │
│  QueryLogsTool, GetDeploymentTool       │
│  (implements Spring AI ToolCallback)    │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│      arctra-runtime-react (integration) │
│  SpringAiToolCallingEngine              │
│  EvidenceCapturingToolCallback          │
│  (depends on Spring AI)                 │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│        arctra-core (pure Java)          │
│  AgentDefinition, AgentRequest          │
│  AgentResult, Evidence                  │
│  AgentExecutionEngine                   │
│  (NO Spring AI dependency)              │
└─────────────────────────────────────────┘
```

**结论：当前边界清晰，无需立即重构。**

---

## 10. Anti-Patterns（反模式）

### 反模式 1：镜像外部框架

**错误示例：**
```java
// BAD: 只是把 Spring AI API 改个名字
interface ArctraModel extends ChatModel { }
interface ArctraTool extends ToolCallback { }
```

**为什么错误：**  
没有增加新语义，只增加维护成本。

---

### 反模式 2：过早 Registry

**错误示例：**
```java
// BAD: 只有一个 Tool，却创建 Registry
interface ToolRegistry {
    Tool resolve(String name);
}
```

**为什么错误：**  
当前 Tools 是静态注入的，没有"动态查找"需求。

---

### 反模式 3：把动态语义固化到静态定义

**错误示例：**
```java
// BAD: 把风险等级固化到 Tool
interface Tool {
    RiskLevel getRiskLevel();
    boolean requiresApproval();
}
```

**为什么错误：**  
风险是 context-dependent 的：
- `executeSql("SELECT")` 低风险
- `executeSql("DROP TABLE")` 高风险

应该由 Policy 基于 ToolInvocation 动态评估。

---

### 反模式 4：在 Engine 里实现 Governance

**错误示例：**
```java
// BAD: 每个 Engine 自己实现 Permission 检查
class SpringAiToolCallingEngine {
    public AgentResult execute(...) {
        // 检查 permission
        // 检查 risk
        // 调用 Tool
    }
}
```

**为什么错误：**  
Governance 应该是独立层，统一拦截所有 Engine 的 Tool 调用。

**正确架构：**
```
Engine (任何实现)
  ↓
Governance Layer (统一)
  ↓
Tool Execution
```

---

### 反模式 5：为了未来需求提前抽象

**错误示例：**
```java
// BAD: M1 只有一个 Engine，却创建 EngineFactory
interface EngineFactory {
    AgentExecutionEngine create(String type);
}
```

**为什么错误：**  
未来可能永远不需要第二个 Engine。

**正确做法：**  
等第二个 Engine 真正出现时，再提取 Factory。

---

## 11. Future Decision Checklist

每次准备创建新抽象时，检查以下清单：

### Checklist

- [ ] **真实消费者存在？** 谁在用？哪个 Scenario 需要？
- [ ] **不加做不了？** 当前真正被阻塞的功能是什么？
- [ ] **至少两个实现？** 是否真的需要统一多个实现？
- [ ] **新语义？** 是否表达新的领域语义，还是只是 rename？
- [ ] **变化隔离？** 是否隔离真实的变化，还是只是"看起来纯"？
- [ ] **未来难加？** 如果未来容易加，为什么现在必须加？
- [ ] **Governance 需要？** 如果是 Tool 相关，Governance 是否已经存在？
- [ ] **验证过？** 是否已经通过 PoC / Scenario 验证？

**只有全部回答"是"，才创建抽象。**

---

## 12. Explicit Non-Decisions（明确未决定的事项）

当前**没有**决定：

### 关于 Tool

- ❌ Arctra 永远使用 Spring AI ToolCallback
- ❌ Arctra 一定需要自己的 Tool Contract
- ❌ Tool Definition 和 Implementation 一定分离
- ❌ 一定需要 ToolRegistry
- ❌ Tool 的元数据格式
- ❌ Agent Tool Binding 的具体设计
- ❌ ToolInvocation 的字段

### 关于 Model

- ❌ Arctra 永远使用 Spring AI ChatModel
- ❌ 一定需要 Model abstraction
- ❌ 一定需要 ModelRegistry
- ❌ Model selection 策略

### 关于 Agent

- ❌ AgentDefinition 最终一定有哪些字段
- ❌ AgentDefinition 是否包含 toolRefs / modelRef
- ❌ Agent 如何声明 capability 需求

### 关于 Governance

- ❌ Governance 最终 API 长什么样
- ❌ Policy 如何表达
- ❌ Permission / Risk / Audit 的具体模型
- ❌ HITL 如何集成

### 关于 Runtime

- ❌ Arctra 永远使用 Spring AI Tool Calling Loop
- ❌ 是否需要自己的 ReAct Loop
- ❌ 是否需要 EngineResolver / EngineFactory
- ❌ Session / Checkpoint / Resume 的设计

**这些都必须由未来真实 Scenario 驱动。**

---

## 13. 何时需要 ADR

以下情况需要创建 ADR：

1. **改变长期架构方向**
   - 例如：决定 Arctra Tool Contract 的边界
   - 例如：决定 Governance 是独立层还是 Engine 实现

2. **有多个合理方案**
   - 例如：Evidence capture 位置（Advisor / ToolCallback / Engine）
   - 例如：Tool Definition 是否与 Implementation 分离

3. **影响多个模块**
   - 例如：arctra-core 是否允许 Spring AI 类型
   - 例如：AgentDefinition 增加新字段

4. **不可逆的决策**
   - 例如：public API 设计
   - 例如：模块依赖方向

**M1 已经创建的 ADR：**
- ADR-001: Runtime/Engine Separation
- ADR-002: Project Name and Coordinates

**未来可能需要的 ADR：**
- Arctra Tool Contract Boundary（如果创建）
- Model Abstraction Strategy（如果需要）
- Governance Layer Design（如果实现）

---

## 14. 总结：设计的是触发条件，不是未来类

**本文档的核心价值：**

不是告诉你"未来应该有哪些类"。

而是告诉你"未来什么时候，我们才有足够证据去创建这个类"。

**这样做的好处：**

1. **避免过度设计** — 不为假想需求设计
2. **保持敏捷** — 随时可以根据真实需求演进
3. **减少维护成本** — 不维护无用的抽象
4. **保持简单** — 当前代码只解决当前问题

**记住：**

> "It is not enough for code to work."  
> — Robert C. Martin

**同样：**

> "It is not enough for architecture to be beautiful."  
> — Arctra Principle

**架构的价值在于：**
- 隔离真实的变化
- 表达清晰的领域语义
- 让当前代码简单
- 让未来扩展容易

**而不是：**
- 提前预测所有未来
- 创建完美的抽象层次
- 避免所有"不纯"的依赖

---

**End of Document**

---

## Appendix: 快速查询表

| 抽象候选 | 当前状态 | 最早可能创建时机 |
|---------|---------|----------------|
| Arctra Tool Contract | 不存在 | 第二个 Tool runtime 或 Governance 出现时 |
| ToolDefinition (Arctra) | 不存在 | 需要 Spring AI 无法表达的元数据时 |
| ToolRegistry | 不存在 | AgentDefinition 有 toolRefs 且至少 2 个 Agent 时 |
| Agent Tool Binding | 不存在 | 同一 Tool 被多个 Agent 使用且治理要求不同时 |
| ToolInvocation | 不存在 | Governance layer 存在且需要基于调用上下文决策时 |
| Governance / Policy | 不存在 | 至少一个真实场景需要 permission/risk/approval 时 |
| Model Abstraction | 不存在 | 至少 2 个 Model provider 或 AgentDefinition 有 modelRef 时 |
| ModelResolver | 不存在 | AgentDefinition 有 modelRef 且至少 2 个 Agent 时 |
| AgentClient / Facade | 不存在 | 至少 2 个消费应用且手动连线重复时 |
| EngineResolver | 不存在 | 至少 2 个 Engine 实现且 AgentDefinition 需要选择 Engine 时 |
