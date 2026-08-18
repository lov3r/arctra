# Execution Model Semantics

**Version:** 1.0  
**Date:** 2026-08-18  
**Status:** Active  
**Source:** Post-M2 Execution Model Reassessment

---

## Purpose

明确 Arctra 当前执行模型的语义，定义核心术语，防止概念混淆。

**为什么需要本文档：**
- M2 完成后，发现术语边界模糊
- Multi-turn / Multi-step / Tool Loop 容易混淆
- Session / Process / Execution 关系未明确
- Engine 的语义定位不清

本文档提供 **semantic source of truth**。

---

## Part 1: Current Execution Model

### 1.1 Actual Execution Flow

```
User
  ↓
[AgentRuntime] (interface exists, not yet implemented)
  ↓ (currently direct call)
AgentExecutionEngine.execute(definition, request, context)
  ↓
SpringAiToolCallingEngine
  ├── Wrap tools with EvidenceCapturingToolCallback
  ├── Build ChatClient
  │    └── (if sessionId) add MessageChatMemoryAdvisor
  ├── Build prompt (system + user + tools)
  ├── (if sessionId) pass conversationId to advisor
  └── call()
       ↓
Spring AI ChatClient
  └── ToolCallingAdvisor (Spring AI internal)
       ↓
       Model ←→ Tool ←→ Model ←→ Tool ←→ Model
       (Tool Calling Loop - Spring AI internal)
       ↓
       Final Response
  ↓
AgentResult(content, evidences)
```

### 1.2 Key Observations

1. **One execute() call** = 一次完整的 engine invocation
2. **Tool Calling Loop** 完全在 Engine 内部（Spring AI 管理）
3. **Evidence** 在 execute() 返回时收集（per-execution）
4. **Session** 通过 ChatMemory 跨 execution 共享 conversation history
5. **外部看不到** Tool Loop 的中间状态

---

## Part 2: Core Terminology

### 2.1 Multi-Turn Conversation

**Definition:**  
多轮对话，用户与 Agent 在同一 session 内多次交互，Agent 记住之前的对话。

**Example:**
```
Session "incident-123":
  Turn 1:
    User: "生产环境 500 错误，请分析"
    Agent: "根据日志，发现 user_status 字段缺失..."
  
  Turn 2:
    User: "那最可能的原因是什么？" (指代 Turn 1)
    Agent: "最可能是 schema migration 失败..."
```

**Mechanism:**
- `ChatMemory` 存储 conversation history
- `AgentExecutionContext.withSession(id)` 标识 session
- `MessageChatMemoryAdvisor` 注入 history 到 prompt

**Boundary:**
- Session boundary
- User-initiated

**State:**
- Conversation history (user + assistant messages)

**M2 Status:** ✅ Implemented

---

### 2.2 Tool Calling Loop

**Definition:**  
Model 在一次 execution 内多次调用 Tool，直到获得最终答案的循环。

**Example:**
```
One execute() call:
  Model: "I need to call queryLogs"
  → Tool: queryLogs() → "logs show user_status error"
  Model: "Now I need to call getDeployment"
  → Tool: getDeployment() → "v1.2.3 deployed at 16:18"
  Model: "Based on logs and deployment, the root cause is..."
  → Final Answer
```

**Mechanism:**
- Spring AI `ToolCallingAdvisor`
- Model-driven (Model decides which tools to call)
- Automatic loop (no external orchestration)

**Boundary:**
- One `engine.execute()` call
- Engine internal

**State:**
- Spring AI internal (not exposed to Arctra)

**Visibility:**
- External: One execute() → One result
- Internal: Multiple tool calls

**M1 Status:** ✅ Implemented (via Spring AI)

---

### 2.3 Multi-Step Process

**Definition:**  
为完成一个 Goal，Agent 需要执行多个结构化的 Step，每个 Step 的输出作为下一个 Step 的输入。

**Example:**
```
Goal: Diagnose Production Incident

Process:
  Step 1: CollectLogs
    Input: incident info
    Output: LogEvidence
  
  Step 2: AnalyzeLogs
    Input: LogEvidence
    Output: FailureHypothesis
  
  Step 3: InspectDeployment
    Input: FailureHypothesis
    Output: DeploymentEvidence
  
  Step 4: CorrelateEvidence
    Input: LogEvidence + DeploymentEvidence
    Output: RootCause
  
  Step 5: RecommendAction
    Input: RootCause
    Output: Recommendation
```

**Mechanism:**
- NOT IMPLEMENTED
- 可能需要：Workflow Engine 或 Planning Engine
- Step output → next step input (explicit data flow)

**Boundary:**
- Process (multiple executions)
- Goal-initiated

**State:**
- Process state (step outputs, checkpoints)

**Key Characteristics:**
- Step 之间有明确的 data dependency
- 可能需要 checkpoint (resume from step N)
- 可能需要 branching (if-then-else)
- 可能需要 HITL (human approval between steps)

**Status:** ❌ Not implemented (future)

---

### 2.4 Key Distinctions

#### Multi-Turn vs Multi-Step

| Dimension | Multi-Turn | Multi-Step |
|-----------|------------|------------|
| **User Involvement** | Multiple user inputs | Single user input |
| **Continuity** | Across user turns | Across agent steps |
| **State** | Conversation history | Process state |
| **Mechanism** | ChatMemory | Process Runtime |
| **Example** | ChatGPT conversation | Workflow execution |
| **M2 Status** | ✅ Implemented | ❌ Not implemented |

**Critical:** Multi-turn ≠ Multi-step (正交维度)

**Example showing both:**
```
Session (multi-turn):
  Turn 1: Process A (multi-step: step1 → step2 → step3)
  Turn 2: Process B (multi-step: step1 → step2)
  Turn 3: Continue Process A (multi-step: step4 → step5)
```

#### Tool Loop vs Multi-Step

| Dimension | Tool Loop | Multi-Step |
|-----------|-----------|------------|
| **Visibility** | Engine internal | Framework-level |
| **Control** | Model-driven | Workflow/Planning-driven |
| **State** | Not exposed | Checkpointed, observable |
| **Boundary** | One execute() | Multiple executions/steps |
| **Resumable** | No | Yes (from checkpoint) |
| **HITL** | No | Yes (between steps) |

**Critical:** Tool Loop 是 Engine 实现细节，不是 Process abstraction

---

## Part 3: Execution Layers

### 3.1 Three-Layer Model

```
┌─────────────────────────────────────┐
│ Session (conversation boundary)     │
│  ├── Turn 1 → Process P1            │
│  │              ├── Execution E1    │
│  │              └── Execution E2    │
│  └── Turn 2 → Process P2            │
│                 └── Execution E1    │
└─────────────────────────────────────┘
```

**Layer 1: Session**
- **Semantic:** Conversation continuity boundary
- **Initiator:** User
- **Lifetime:** Long-lived (many turns)
- **State:** Conversation history (ChatMemory)
- **Example:** "incident-123"

**Layer 2: Process**
- **Semantic:** Task execution boundary
- **Initiator:** Goal / Task
- **Lifetime:** Goal completion
- **State:** Task state (step outputs, checkpoints)
- **Example:** "Diagnose incident"
- **Status:** NOT IMPLEMENTED (future)

**Layer 3: Execution**
- **Semantic:** One engine invocation
- **Initiator:** Process or direct call
- **Lifetime:** One execute() call
- **State:** Per-execution evidences
- **Example:** One `engine.execute()` → AgentResult

### 3.2 Relationships

**Session → Process:**
- One session can contain **many processes**
- Processes are independent tasks within a conversation

**Process → Execution:**
- One process can span **one or more executions**
- Single-step process: 1 execution
- Multi-step process: N executions (future)

**Critical Anti-Pattern:**
- ❌ Session = Process (one session only executes one process)
- ❌ Process = Execution (multi-step can't exist)

---

## Part 4: AgentExecutionEngine Semantics

### 4.1 Contract

```java
public interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
}
```

### 4.2 Semantic Definition

**`AgentExecutionEngine` 是什么：**

> A pluggable **execution strategy** for processing an agent execution unit.

**`execute()` 的语义：**

> Execute one round of the strategy defined by this engine, given the definition, request, and context.

**关键点：**
- Engine 定义"如何执行"（how to execute）
- Engine **不拥有** complete Agent semantics
- 不同 Engine 可以有不同的 execution unit 定义

### 4.3 Current Implementation

**SpringAiToolCallingEngine:**

`execute()` 语义 = "执行一次 Tool-Calling-based ReAct loop"

内部行为：
1. 构建 prompt (system + user + tools)
2. (如果有 session) 注入 conversation history
3. 调用 Spring AI ChatClient
4. Spring AI 执行 Tool Calling Loop (Model ←→ Tool)
5. 收集 Evidence
6. 返回 AgentResult

**对外视角：**
```
execute() → AgentResult
```

**对内视角：**
```
execute() → [Model → Tool → Model → Tool] → AgentResult
```

### 4.4 Future Engines

**WorkflowEngine (future):**

`execute()` 语义 = "执行一个 workflow step"

可能行为：
- 执行 workflow 中的一个 node
- 或执行整个 workflow（取决于设计）

**PlanningEngine (future):**

`execute()` 语义 = "执行一次 goal-driven planning cycle"

可能行为：
- Plan → Execute Actions → Check Goal → Replan
- 直到 Goal achieved

### 4.5 Engine Positioning

**Engine 不是：**
- ❌ Complete Agent
- ❌ Agent Runtime
- ❌ Process orchestrator

**Engine 是：**
- ✅ Execution strategy component
- ✅ Pluggable implementation
- ✅ Focused on "how to execute"

**Future Architecture:**
```
User Agent API
    ↓
Agent Runtime (lifecycle management)
    ↓
[Process Runtime] (for multi-step, optional)
    ↓
AgentExecutionEngine (pluggable strategy)
    ├── ToolCallingEngine
    ├── WorkflowEngine
    └── PlanningEngine
    ↓
Model / Tool / Code / Sub-Agent
```

---

## Part 5: State Management

### 5.1 Three Types of State

#### Type A: Conversation State

**Semantic:** User-Agent 对话历史

**Storage:** `ChatMemory`

**Lifecycle:** Session lifetime

**Scope:** Cross-execution (within same session)

**Content:**
- User messages
- Assistant messages
- (Possibly) Tool call/response messages

**Example:**
```java
chatMemory.add("incident-123", userMessage);
chatMemory.add("incident-123", assistantMessage);
```

---

#### Type B: Process State (Future)

**Semantic:** Multi-step task 执行状态

**Storage:** Process Runtime (not implemented)

**Lifecycle:** Process completion

**Scope:** Process-specific

**Content:**
- Step outputs
- Intermediate results
- Checkpoints
- Branch decisions

**Example (future):**
```java
process.setState("logs", logEvidence);
process.setState("hypothesis", failureHypothesis);
process.checkpoint("after-analysis");
```

---

#### Type C: Evidence

**Semantic:** Execution 观测记录

**Storage:** `AgentResult.evidences()`

**Lifecycle:** One execution

**Scope:** Per-execution (isolated)

**Content:**
- Tool calls
- (Future) Action executions
- (Future) Sub-agent calls
- (Future) Human inputs

**Example:**
```java
return new AgentResult(content, evidences);
// evidences are collected during THIS execution only
```

### 5.2 Critical Separation

**DO:**
- ✅ Conversation state → ChatMemory
- ✅ Process state → Process Runtime (when implemented)
- ✅ Evidence → AgentResult (per-execution)

**DO NOT:**
- ❌ Put process state in ChatMemory
- ❌ Put conversation in Process state
- ❌ Accumulate Evidence across executions
- ❌ Mix different state types

---

## Part 6: Evidence Semantics

### 6.1 Current Definition

```java
public record Evidence(String source, String content) {}
```

**Semantic:**  
Framework-wide execution observation

### 6.2 Current Sources

M1/M2:
- `tool:queryLogs`
- `tool:getDeployment`

### 6.3 Future Sources

可能来源：
- `tool:*` - Tool execution
- `action:*` - Workflow action execution
- `retrieval:*` - Knowledge retrieval
- `agent:*` - Sub-agent execution
- `human:*` - Human input/approval
- `step:*` - Process step execution
- `external:*` - External system event

### 6.4 Key Principle

**Evidence 不是：**
- ❌ Tool-only concept
- ❌ Tool execution result
- ❌ Process state

**Evidence 是：**
- ✅ Framework-wide observation mechanism
- ✅ "What was observed during this execution"
- ✅ Provenance / auditability

**Abstraction:**  
当前 Evidence abstraction 是正确的，只是 producer 目前只有 Tool。未来可以有更多 producers。

---

## Part 7: AgentExecutionContext Semantics

### 7.1 Current Definition

```java
public record AgentExecutionContext(String sessionId) {
    public static AgentExecutionContext stateless();
    public static AgentExecutionContext withSession(String sessionId);
}
```

### 7.2 Semantic

**ExecutionContext 是什么：**

> Execution environment orthogonal to "what to execute" (Definition + Request) and "execution result" (Result).

**包含什么：**
- Execution-level identity (sessionId, processId, executionId)
- Cross-cutting concerns (tracing, tenancy, security)
- Execution environment (isolation, permissions)

**不包含什么：**
- ❌ User input (belongs in AgentRequest)
- ❌ Agent template (belongs in AgentDefinition)
- ❌ Execution result (belongs in AgentResult)
- ❌ Business domain state (belongs in Process state)

### 7.3 Future Extensions

可能添加（符合 inclusion criteria）：
- `processId` - identify multi-step process
- `executionId` - identify single execution
- `traceId` - distributed tracing
- `userId` / `tenantId` - multi-tenancy
- `checkpointId` - resume from checkpoint

不应添加（违反 inclusion criteria）：
- ❌ `incidentId` - domain-specific
- ❌ `toolContext` - engine-specific
- ❌ `modelParameters` - engine configuration

---

## Part 8: Quick Reference

### 8.1 Terminology Cheat Sheet

| Term | What It Is | Boundary | State | Status |
|------|------------|----------|-------|--------|
| **Multi-turn** | Conversation continuity | Session | Conversation history | ✅ M2 |
| **Multi-step** | Structured task execution | Process | Task state | ❌ Future |
| **Tool Loop** | Model-driven tool orchestration | One execute() | Spring AI internal | ✅ M1 (Spring AI) |
| **Session** | Conversation boundary | User-initiated | ChatMemory | ✅ M2 |
| **Process** | Task boundary | Goal-initiated | Process state | ❌ Future |
| **Execution** | One engine call | One execute() | Evidences | ✅ M1 |

### 8.2 Critical Distinctions

```
Multi-turn ≠ Multi-step
Tool Loop ≠ Multi-step Process
Session ≠ Process
Process ≠ Execution
Execution ≠ Tool Call
```

### 8.3 Ownership Table

| Concept | Owner | Purpose |
|---------|-------|---------|
| **Engine** | Execution strategy | How to execute |
| **Definition** | Agent template | What to execute |
| **Request** | User input | Content |
| **Context** | Execution environment | Where/when/who |
| **Result** | Execution output | Outcome |
| **Runtime** | Lifecycle management | Orchestration |

---

## Summary

**Current Execution Model:**
- ✅ Single-execution (one request → one result)
- ✅ Tool Calling (Spring AI internal loop)
- ✅ Multi-turn Conversation (ChatMemory)
- ❌ Multi-step Process (not implemented)

**Key Semantics:**
- Engine = execution strategy (not complete Agent)
- Tool Loop = engine internal (not Process)
- Multi-turn ≠ Multi-step (orthogonal)
- Session ≠ Process (different lifecycles)
- Evidence = framework-wide observation (not tool-only)

**Architecture Positioning:**
- Engine 是 component，不是 top-level boundary
- 未来会有 Runtime / Process Runtime 在 Engine 之上
- 当前架构不阻碍未来演进

---

**Execution Model Semantics v1.0** ✅

See also:
- [Architecture Guardrails](ARCHITECTURE-GUARDRAILS.md)
- [Post-M2 Execution Model Reassessment](POST-M2-EXECUTION-MODEL-REASSESSMENT.md)
