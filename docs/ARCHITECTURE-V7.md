# Arctra Architecture V7 --- 中文实现基线

> 定位：**Spring-native Agent Engineering Harness**
>
> 架构宪法：
>
> 1.  **一切可以扩展，但不是一切都是插件。**
> 2.  **内核掌握运行语义，Provider 提供能力。**
> 3.  **关键控制流显式执行，非关键副作用事件驱动。**

## 1. 项目定位

Arctra 不替代 Spring AI、AgentScope、Spring AI Alibaba
Graph、Embabel。

它解决的是不同 Agent Runtime 之上的统一工程问题：

``` text
运行
测试
治理
恢复
观测
评估
```

真正要形成的核心资产是：

``` text
统一 Runtime Contract
+ Evidence-driven Decision
+ Session / Recovery Semantics
+ TestKit / Evaluation / Observability
+ Spring-native DX
```

## 2. 简单架构

``` text
Application
    |
AgentClient
    |
AgentRuntime
    |
+-----------------------------+
| Runtime Semantics           |
| Session / State / Budget    |
| Failure / Cancellation      |
| Evidence / Decision         |
| Policy / HITL               |
| Checkpoint / Resume         |
+-----------------------------+
    |
AgentExecutionEngine
    |
+-------------+-------------------------+
| Native ReAct| AgentScope / Graph ...  |
+-------------+-------------------------+
```

普通用户首先只理解：

``` text
Agent = 谁来决策
RAG   = 去哪里找知识
Tool  = 能做什么
```

## 3. V7 全景

``` text
                               Arctra
                                        |
                                   AgentClient
                                        |
                                  AgentSession
                                        |
                                   AgentRuntime
                                        |
       +--------------------------------+--------------------------------+
       |                                |                                |
       v                                v                                v
 运行语义                         Capability Contract                 Session 语义
       |                                |                                |
 State Machine                    ModelProvider                     Session Log
 Budget                           ToolProvider                      Snapshot
 Cancellation                     Retriever                         Checkpoint
 Failure                          Reranker                          Resume
 Policy / HITL                    MemoryProvider                    Replay
 Evidence / Decision              SandboxProvider                   Future Fork
       |                          ExecutionEngine                    Projection
       |                                |
       |                      +---------+---------+
       |                      |                   |
       |                 Native ReAct        Future Engine
       |                                      AgentScope
       |                                      Graph
       |                                      Embabel
       |
       +---------------------------+-------------------------------+
                                   |
                              显式控制流
                                   |
                       Policy -> Interceptor -> Execute
                                   |
                              Event Publishing
                                   |
                    +--------------+--------------+
                    |              |              |
                 Metrics         Audit        Projection
                    |
              Observability / TestKit
```

## 4. Kernel 掌握什么

无论使用哪个 Engine，下列语义必须统一：

-   Agent Lifecycle
-   AgentSession / AgentExecution Identity
-   State Transition
-   Budget
-   Cancellation
-   Failure Classification
-   Policy / HITL
-   Evidence / Decision
-   Checkpoint / Resume
-   Session Log 基础语义
-   公共 Observability Contract

这些属于 Kernel，不做成 Plugin。

## 5. ExecutionEngine 边界

ExecutionEngine 负责"Agent 怎么跑"：

-   ReAct Loop
-   Planning Algorithm
-   Graph Traversal
-   Multi-Agent Coordination
-   Engine-specific Scheduling

Framework 负责"运行意味着什么"。

V1 只实现 Native ReAct。

AgentScope 等第三方 Engine 必须等 Runtime Contract 被 Native ReAct
和真实 Vertical Slice 验证后再接。

目标边界示意：

``` java
public interface AgentExecutionEngine {
    String name();
    Set<EngineCapability> capabilities();
    EngineResult execute(AgentExecutionContext context);
}
```

注意：这个接口在真正编码验证前不能因为文档示例就提前冻结 Public API。

## 6. Capability Contract

不自研复杂 SPI Container。

使用：

``` text
Java Contract
   |
Spring Bean
   |
AutoConfiguration
   |
Starter
```

主要能力边界：

``` text
ModelProvider
ToolProvider
Retriever
Reranker
MemoryProvider
SandboxProvider
AgentExecutionEngine
```

未来 Capability 可使用 namespace：

``` text
engine:resume
engine:multi-agent
model:tool-calling
model:vision
retrieval:vector
sandbox:network-isolation
```

避免一个越来越大的中央 Enum。

## 7. Provider Descriptor

只有需要运行时选择、诊断、Fallback 的 Provider 才考虑暴露：

``` text
name
version
capabilities
priority
health
metadata
```

用途：

-   Diagnostics
-   Capability Negotiation
-   Fail Fast
-   Fallback
-   Selection

不要把简单组件也强迫套入复杂 Descriptor。

## 8. 显式控制流

关键执行路径：

``` text
Runtime
  |
Policy
  |
Interceptor Chain
  |
Execution
```

禁止把权限、执行顺序、关键策略隐藏在 Event Listener。

Event Publishing 更适合：

``` text
Observability
Metrics
Audit
Projection
UI Notification
非关键扩展
```

原则：

> Event 用于表达"发生了什么"，而不是秘密决定"必须做什么"。

## 9. Session Model

``` text
AgentDefinition
      |
AgentSession
      |
AgentExecution
      |
ExecutionStep
```

### AgentSession

一次可持续的逻辑工作会话。

### AgentExecution

Session 内的一次具体运行。

### ExecutionStep

一次有意义的执行单元，例如：

-   Model Call
-   Retrieval
-   Tool Call
-   Policy Decision
-   Checkpoint

这个分层用于支持 Resume、Retry、用户继续输入以及未来 Fork。

## 10. Session Log

Session Log 是 Runtime 的权威执行记录，但它不等于：

``` text
Trace
Audit Log
Domain Event
Chat Memory
```

基础字段从第一版就考虑：

``` text
sessionId
executionId
sequence
eventType
schemaVersion
timestamp
payload
```

`schemaVersion` 必须从第一天存在，避免未来历史执行记录无法读取。

## 11. Snapshot

不能假设长期 Session 永远从第一条 Log 重放。

设计允许：

``` text
Session Log
+
Snapshot
```

恢复：

``` text
Load Latest Snapshot
        |
Replay Tail Records
        |
Restore Runtime State
```

V1 可以先 InMemory。

## 12. Replay

Replay 必须明确语义：

``` text
Trace Replay
= 只重建/展示历史执行，不重新产生外部副作用

Simulation Replay
= 可复用历史 Tool / 外部结果，重新运行部分推理

Live Replay
= 对当前真实世界重新调用 Model / Tool
```

V1 只做 Trace Replay 基础。

Live Replay 风险高，因为真实世界的副作用无法自动回滚。

## 13. Fork

未来能力：

``` text
Session
  |
Step N
  +--> Fork A
  +--> Fork B
```

适合：

-   Evaluation
-   Model 对比
-   Prompt 对比
-   Plan 对比
-   Debug
-   What-if

Fork 复制的是逻辑状态，不是外部世界。

已经执行过的生产重启，不会因为 Fork 而消失。

## 14. Cancellation

Cancellation 是一等 Runtime Semantics：

``` text
CANCEL_REQUESTED
      |
CANCELLING
      |
CANCELLED
```

需要向下传播到：

-   ExecutionEngine
-   Streaming
-   Tool
-   Retrieval
-   Sandbox

来源可能包括：

-   用户取消
-   Timeout
-   Budget 超限
-   Policy 强制停止
-   Application Shutdown

## 15. Failure Semantics

先分类，再决定恢复策略：

``` text
TRANSIENT
PERMANENT
USER_ERROR
POLICY_DENIED
DEPENDENCY_FAILURE
MODEL_FAILURE
TIMEOUT
BUDGET_EXCEEDED
INCOMPATIBLE_STATE
CANCELLED
```

对应：

``` text
Retry
Fallback
Replan
Suspend
Human
Stop
```

不要把所有失败都退化成 Generic Exception。

## 16. Evidence / Decision

Evidence 和 Decision 是框架的一等概念：

``` text
Source
  |
Evidence
  |
Hypothesis / Verification
  |
Decision
  |
Action / Recommendation
```

Decision 应能够引用 Evidence，而不是只有 String Answer。

Confidence 不应该强行压成一个数字，必要时区分：

``` text
modelConfidence
evidenceConfidence
systemConfidence
```

禁止持久化模型私有 Chain-of-Thought。

## 17. Action / Tool 分离

不要把框架退化成：

``` text
LLM -> @Tool
```

推荐：

``` text
Decision
   |
Action
   |
Policy
   |
Tool Resolution
   |
Concrete Tool
   |
Execution Result
```

例如：

``` text
RestartService
   |
   +-> KubernetesRestartTool
   +-> DockerRestartTool
   +-> SSHRestartTool
```

Tool Execution Semantics 预留：

``` text
READ_ONLY
IDEMPOTENT_WRITE
NON_IDEMPOTENT_WRITE
```

Tool Invocation Identity 要能支持 Retry、Resume、Deduplication、Audit。

## 18. Sandbox

Sandbox 与普通 Tool 分离，因为它拥有执行环境生命周期和隔离语义：

``` text
Create
Prepare
Execute
Stream
Timeout
Destroy
```

涉及：

``` text
Filesystem
Network
CPU
Memory
Secret
Workspace
Process Isolation
```

这对未来 Developer / DevOps Agent 很重要，但 V1
前期不因为未来需求提前实现。

## 19. Retrieval Runtime

``` text
Query
  |
Analyze / Rewrite
  |
+--------------------------+
| BM25 | Vector | Wiki ... |
+--------------------------+
            |
          Fusion
            |
          Rerank
            |
   Evidence Evaluation
            |
      Context Builder
```

Core Retrieval Result 不暴露具体基础设施 Document 类型。

有价值时保留 Score Provenance：

``` text
lexical
semantic
fusion
rerank
authority
freshness
final
```

V1 重点证明：

``` text
Vector
BM25
Hybrid
RRF
Rerank
Evaluation
```

## 20. Memory

Memory 不等于 Chat History：

``` text
Conversation Memory
Semantic Memory
Episodic Memory
Preference / Constraint Memory
```

未来冲突语义：

``` text
NEW
UPDATE
SUPERSEDE
CONFLICT
IGNORE
```

高级 Memory 不应该阻塞两个核心 Vertical Slice。

## 21. Policy / HITL / Resume

HITL 不是阻塞线程：

``` text
Policy Requires Approval
        |
Persist State
        |
Checkpoint
        |
WAITING_APPROVAL
        |
Return Control
```

恢复：

``` text
Approval
  |
Load Checkpoint
  |
Revalidate Preconditions
  |
Resume / Replan / Reject
```

等待期间外部世界可能变化，所以 Resume 前必须考虑 Preconditions
Revalidation。

## 22. TestKit

TestKit 是产品能力，不是最后补的测试工具。

目标体验：

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

覆盖：

-   Fake Model
-   Fake Tool
-   Fake Retriever
-   Timeout
-   Cancellation
-   Malformed Output
-   Dependency Failure
-   Duplicate Invocation
-   Budget Exceeded
-   Policy Denied

## 23. Evaluation

Knowledge Agent：

``` text
Recall@K
Precision@K
NDCG
Hit Rate
Answer Correctness
Evidence Coverage
```

Incident Agent：

``` text
RCA Accuracy
Tool Selection Accuracy
Evidence Correctness
Unsafe Action Rate
Policy Violation Rate
```

框架优势最终必须通过 Scenario / Evaluation 证明，而不是只靠架构图。

## 24. Observability

一次执行的 Timeline：

``` text
Agent Execution
  |
  +-- Engine
  +-- Model
  +-- Retrieval
  +-- Tool Search
  +-- Tool Call
  +-- Evidence
  +-- Policy
  +-- Decision
```

Spring 集成优先 Micrometer / Actuator。

## 25. DDD 边界

Core Domain：

``` text
Agent Runtime
```

Supporting Domain：

``` text
Retrieval
Tool Runtime
Memory
Policy / HITL
```

Engineering Infrastructure：

``` text
TestKit
Evaluation
Observability
Spring Integration
Starters
```

DDD 是建模工具，不是机械生成四层 Package 的模板。

## 26. Spring Boot 扩展方式

优先：

``` text
Spring Bean
AutoConfiguration
Conditional
ObjectProvider
Ordered
ConfigurationProperties
Starter
```

通常优先级：

``` text
User Bean
  >
Explicit Configuration
  >
Official AutoConfiguration
  >
Default / Noop
```

## 27. V1 Module

先保持克制：

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

只有出现真实代码和真实消费者时才继续拆 Module。

## 28. 两条 Vertical Slice

### Incident Investigator

``` text
Tool Calling
 -> Evidence
 -> Decision
 -> Policy
 -> HITL
 -> Checkpoint
 -> Resume
```

### Knowledge Assistant

``` text
Vector
 -> BM25
 -> Hybrid
 -> RRF
 -> Rerank
 -> Evidence
 -> Evaluation
```

这两个场景就是 V1 最重要的 Architecture Test。

## 29. Architecture Fitness Functions

CI 保护：

``` text
core !-> Spring Boot
core !-> AgentScope
core !-> Elasticsearch
core !-> Redis
domain !-> infrastructure
AgentRuntime !-> concrete engine
```

同时观察：

``` text
Public API Count
Module Count
Core Dependency Count
Architecture Violations
Contract Test Coverage
```

## 30. V1 Non-Goals

前期禁止提前做：

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
Enterprise RBAC
```

## 31. 真正要证明的差异点

V1/V2 应围绕四个亮点形成工程证据：

1.  **Runtime Contract / Multi-engine Readiness**
2.  **Evidence-driven Decision**
3.  **Session / Recovery Engineering**
4.  **TestKit + Evaluation + Observability**

RAG、Tool、Memory、HITL 是必备能力，但单独不能作为核心差异化。

## 32. 最终心智模型

``` text
                       Arctra
                              |
                          AgentClient
                              |
                         AgentSession
                              |
                         AgentRuntime
                              |
          +-------------------+-------------------+
          |                   |                   |
      Runtime 语义       Capability Contract    Session 语义
          |                   |                   |
 State / Budget          Model / Tool          Log / Snapshot
 Failure / Cancel        RAG / Memory          Checkpoint
 Evidence / Decision     Sandbox               Resume / Replay
 Policy / HITL           ExecutionEngine       Future Fork
          |                   |
          +---------+---------+
                    |
                 显式控制流
                    |
              Event Publishing
                    |
          Observability / Audit
                    |
              TestKit / Eval
```

V7 的目标不是拥有最多功能，而是：

> **外部 API 简单，内部运行语义稳定；能力可扩展，关键语义不漂移；Agent
> 能测试、能治理、能恢复、能观测。**


## 33. V1 架构冻结与验证原则

V7 从 V1 实现开始进入 **Freeze-by-default**。

后续架构变化优先由代码和真实场景驱动，而不是继续通过脑内推演增加设计。

架构有效性的主要证据：

```text
Incident Vertical Slice
Knowledge Vertical Slice
Scenario Tests
Architecture Tests
Evaluation
Dogfooding
```

Public API 遵循：

```text
internal first
-> real usage
-> stabilize
-> public
```

每次新增抽象必须回答：

```text
现在谁在用？
不加它，哪个当前需求做不了？
```

每次 Milestone Review 必须反向检查：

```text
哪些东西可以删除？
哪些 Public API 可以收缩？
哪些 Module 没有真实消费者？
哪些抽象只是为未来假设服务？
```

0.1.0 的质量优先级：

```text
Correctness
> Testability
> Recoverability
> Observability
> DX
> Extensibility
> Feature Count
```

因此 V1 宁可功能更少，也不要用大量未验证抽象换取“架构看起来完整”。
