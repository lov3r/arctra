# M3 Phase Planning

**Date:** 2026-08-18  
**Type:** Phase Planning + Architecture Gate  
**Status:** DRAFT - Awaiting Approval  
**Dependencies:** M1 COMPLETE, M2 COMPLETE

---

## 1. Executive Summary

**Purpose:**  
定义 Arctra M3 阶段的目标、范围和架构演进方向。

**Current State:**  
Arctra 已完成 M1 (Tool-Calling Agent) 和 M2 (Multi-Turn Conversation)，具备基础执行能力和会话连续性。

**Key Question:**  
M3 应该解决什么问题，才能让 Arctra 从当前的"execution engine wrapper"向真正的"Agent Runtime"演进？

**Recommended M3 Theme:**  
**Agent API & Runtime Boundary**

**Why Now:**  
- 当前直接调用 Engine 过于底层
- 需要稳定的用户面向 API 作为 discovery vehicle
- 必须在添加 Process/Workflow 之前建立正确的 API 边界
- Vertical slice principle: API first, infrastructure later

**Why Not Other Candidates:**  
- Multi-Step Process: 没有真实消费者 (YAGNI)
- Persistent Session: 重要但不影响架构方向
- Workflow/Planning: 过早，需要先有 API 和真实需求

---

## 2. Current Architecture Baseline

### 2.1 Current Public API

**Core Domain (arctra-core):**
```java
// Agent Definition
record AgentDefinition(String name, String description)

// Agent Request  
record AgentRequest(String userMessage)

// Execution Context
record AgentExecutionContext(String sessionId) {
    static AgentExecutionContext stateless();
    static AgentExecutionContext withSession(String sessionId);
}

// Execution Result
record AgentResult(String content, List<Evidence> evidences)

// Evidence
record Evidence(String source, String content)

// Engine Contract
interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
    
    default AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return execute(definition, request, AgentExecutionContext.stateless());
    }
}

// Runtime Interface (exists but not implemented)
interface AgentRuntime {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    );
}

// DefaultAgentRuntime (minimal implementation)
class DefaultAgentRuntime implements AgentRuntime {
    private final AgentExecutionEngine engine;
    // Simple delegation
}
```

**Runtime Implementation (arctra-runtime-react):**
```java
class SpringAiToolCallingEngine implements AgentExecutionEngine {
    SpringAiToolCallingEngine(
        ChatModel chatModel,
        List<ToolCallback> tools,
        ChatMemory chatMemory
    );
}
```

### 2.2 Current Execution Path

```
User Code
  ↓ (currently direct call)
AgentExecutionEngine.execute(definition, request, context)
  ↓
SpringAiToolCallingEngine
  ├── Build ChatClient (with MessageChatMemoryAdvisor if sessionId)
  ├── Wrap tools with EvidenceCapturingToolCallback
  ├── Build prompt
  ├── Pass ChatMemory.CONVERSATION_ID
  └── call()
       ↓
Spring AI [Model ←→ Tool Loop] (internal)
       ↓
AgentResult
```

**Key Observation:**  
用户直接调用 Engine，没有 Runtime 中间层。

### 2.3 Current State Ownership

| State Type | Owner | Lifecycle | Persistence |
|------------|-------|-----------|-------------|
| Conversation | ChatMemory | Session | In-memory (M2) |
| Evidence | AgentResult | Per-execution | Transient |
| Agent Definition | User code | Application | Static |
| Execution Context | User code | Per-call | Transient |

**Gap:** 没有 Process state ownership (因为没有 Process abstraction)

### 2.4 Current Spring AI Dependency Boundary

**Direct Dependencies:**
- `ChatModel` (constructor injection to Engine)
- `ToolCallback` (constructor injection to Engine)
- `ChatMemory` (constructor injection to Engine)
- `MessageChatMemoryAdvisor` (internal to Engine)
- `ChatClient` (internal to Engine)

**Boundary:**  
Spring AI dependencies 完全封装在 `SpringAiToolCallingEngine` 内部。

### 2.5 Current Agent / Runtime / Engine 职责

**AgentDefinition:**
- Minimalist (name + description only)
- No model binding
- No tool binding
- Pure template

**AgentExecutionEngine:**
- Execution strategy
- NOT complete Agent boundary
- Pluggable component

**AgentRuntime:**
- Interface exists
- `DefaultAgentRuntime` is trivial delegation
- NOT真正的 runtime

**Gap:** Runtime 职责未明确，当前只是 Engine wrapper

### 2.6 Current Evidence Flow

```
Tool execution
  ↓
EvidenceCapturingToolCallback
  ↓
List<Evidence> (per-execution)
  ↓
AgentResult.evidences
```

**Characteristics:**
- Per-execution isolated
- Tool-sourced only (currently)
- Framework-wide observation semantic (future-compatible)

### 2.7 Current Session Semantics

**Session:**
- Conversation continuity boundary
- User-initiated
- Long-lived (in-memory, lost on restart)
- Managed by ChatMemory

**NOT Session:**
- Process state
- Execution state
- Business domain state

### 2.8 Current明确不支持的能力

❌ Multi-Step Process  
❌ Workflow / Graph  
❌ Goal Planning  
❌ Persistent Session  
❌ Session Concurrency  
❌ Context Compaction (turn-aware)  
❌ Checkpoint / Resume  
❌ HITL  
❌ Sub-Agent  
❌ Long-Term Memory  
❌ Streaming  
❌ Agent Registry  
❌ Model Selection  
❌ Tool Registry  
❌ Governance  

---

## 3. Capability Maturity

### 3.1 Arctra Current Level

**Level Assessment:**

✅ **Level 1: Model Invocation** - COMPLETE (M1)
```
User → Model → Response
```

✅ **Level 2: Tool-Calling Agent** - COMPLETE (M1)
```
User → Model ←→ Tool → Response
```

✅ **Level 3: Multi-Turn Agent** - COMPLETE (M2)
```
Session → Turn → Agent Execution → Conversation Continuity
```

❌ **Level 4: Multi-Step Process** - NOT IMPLEMENTED
```
Goal → Step A → Step B → Step C → Result
```

❌ **Level 5: Adaptive Agent** - NOT IMPLEMENTED
```
Goal → Plan → Execute → Observe → Replan → Goal Completion
```

❌ **Level 6: Multi-Agent** - NOT IMPLEMENTED
```
Goal → Agent A → Agent B → Coordination → Result
```

**Current Maturity:** **Level 3 (Multi-Turn Agent)**

**Key Insight:**  
Tool Calling Loop (Model ←→ Tool 多次交互) 不等于 Multi-Step Process。
- Tool Loop = Spring AI internal, model-driven
- Multi-Step = Framework-level, workflow/planning-driven

---

## 4. M2 → M3 Architecture Gap

### 4.1 Identified Gaps

**Gap A: No High-Level Agent API**

当前用户必须：
```java
var chatModel = ...;
var tools = List.of(...);
var chatMemory = MessageWindowChatMemory.builder()...build();
var engine = new SpringAiToolCallingEngine(chatModel, tools, chatMemory);

engine.execute(
    new AgentDefinition("name", "desc"),
    new AgentRequest("message"),
    AgentExecutionContext.withSession("id")
);
```

**Problems:**
- 过于底层
- 暴露 Engine / ChatModel / Tools / ChatMemory 构建细节
- 无法隐藏不同 execution strategies
- 不符合"Agent Runtime"定位

---

**Gap B: AgentRuntime 职责不明确**

当前 `AgentRuntime` interface 存在但几乎无用：
- `DefaultAgentRuntime` 是 trivial delegation
- 没有真正的 lifecycle management
- 没有 agent resolution
- 没有 execution strategy selection

---

**Gap C: No Agent Registry / Resolution**

当前 AgentDefinition 由用户代码创建：
```java
var agent = new AgentDefinition("incident", "You are...");
```

未来可能需要：
```java
var agent = runtime.getAgent("incident-investigator");
```

But: 没有真实消费者证明需要 registry

---

**Gap D: Model Ownership 不清晰**

当前 ChatModel 绑定 Engine instance：
```java
new SpringAiToolCallingEngine(chatModel, ...)
```

未来可能：
- Agent A → Model A
- Agent B → Model B
- Step A → cheap model, Step B → reasoning model

当前设计是否阻碍？

---

**Gap E: Tool Ownership 不清晰**

当前 Tools 绑定 Engine instance：
```java
new SpringAiToolCallingEngine(..., tools, ...)
```

未来可能：
- Agent A → Tools [A, B]
- Agent B → Tools [C, D]
- Tool-specific governance policy

当前设计是否阻碍？

---

**Gap F: No Process / Multi-Step Abstraction**

当前一次 `execute()` = 一个完整 result

未来可能需要：
```
Process
  ├── Execution 1 → partial result
  ├── Execution 2 → partial result
  └── Execution 3 → final result
```

But: **没有真实消费者**

---

**Gap G: No Persistent Session**

当前 Session = in-memory, lost on restart

影响：Multi-turn 只是 demo capability

But: 是否比建立正确 API 边界更重要？

---

**Gap H: No Streaming**

当前 `execute()` 同步返回 `AgentResult`

未来可能需要：
```java
Flux<AgentResult> stream(...);
```

But: 是否已经影响 API 设计？

---

### 4.2 Gap Priority Analysis

| Gap | Impact | Urgency | Real Consumer | Blocks Future |
|-----|--------|---------|---------------|---------------|
| **A. High-Level API** | HIGH | HIGH | ✅ YES (users) | ✅ YES |
| B. Runtime 职责 | MEDIUM | MEDIUM | ⚠️ MAYBE | ✅ YES |
| C. Agent Registry | LOW | LOW | ❌ NO | ❌ NO |
| D. Model Ownership | MEDIUM | LOW | ❌ NO | ⚠️ MAYBE |
| E. Tool Ownership | MEDIUM | LOW | ❌ NO | ⚠️ MAYBE |
| F. Multi-Step | HIGH | LOW | ❌ NO | ❌ NO |
| G. Persistent Session | HIGH | MEDIUM | ⚠️ MAYBE | ❌ NO |
| H. Streaming | MEDIUM | LOW | ❌ NO | ⚠️ MAYBE |

**Critical Finding:**  
Gap A (High-Level API) 是唯一同时满足：
- Real consumer exists (users need simpler API)
- Blocks future evolution (需要正确的 API 边界)
- High urgency (影响用户体验)

---

## 5. Scenario Analysis

### 5.1 Scenario 1: Incident Investigation (Multi-Step)

**User Goal:**  
"生产环境出现大量 500 错误，请分析根本原因"

**Possible Execution:**

**Option A: Tool Calling Loop (Current)**
```
User: "分析 500 错误"
→ Model: "I need to call queryLogs"
→ Tool: queryLogs → logs
→ Model: "Now I need getDeployment"
→ Tool: getDeployment → deployment
→ Model: "Root cause is schema migration issue"
→ Response
```

**Characteristics:**
- Model-driven
- 一次 execute() 完成
- No explicit steps
- Works for simple scenarios

---

**Option B: Explicit Workflow (Future)**
```
Goal: Diagnose Incident

Workflow:
  Step 1: CollectLogs → logs
  Step 2: AnalyzeLogs(logs) → hypothesis
  Step 3: if (hypothesis.needsDeployment)
            InspectDeployment → deployment
          else
            InspectDatabase → db
  Step 4: Correlate → rootCause
```

**Characteristics:**
- Developer-defined structure
- Deterministic
- Observable steps
- Checkpoint/resume capable

---

**Option C: Dynamic Planning (Future)**
```
Goal: IncidentDiagnosed

Available Actions:
- collectLogs
- analyzeLogs
- inspectDeployment
- inspectDatabase
- correlate

Planner:
  State: {}
  → choose: collectLogs
  State: {logs}
  → evaluate, choose: analyzeLogs
  State: {logs, hypothesis}
  → evaluate, choose: inspectDeployment (not database)
  State: {logs, hypothesis, deployment}
  → evaluate, choose: correlate
  State: {rootCause}
  → Goal achieved
```

**Characteristics:**
- Runtime-driven
- Adaptive
- Non-deterministic
- Complex

---

**Question:** 当前 Tool Calling Loop 是否足够？

**Answer for M3:** 对于当前 scenarios (incident investigation), Tool Calling Loop **足够**。

**Real Trigger for Multi-Step:**  
当出现真实场景需要：
- Explicit checkpoint between steps
- Human approval between steps
- Retry specific step (not entire execution)
- Branch based on intermediate results (not model decision)

**Current Status:** 这些需求**不存在**。

---

### 5.2 Scenario 2: Change / Deployment Investigation

**User Goal:**  
"判断这次 deployment 是否应该 rollback"

**Possible Execution:**

**Option A: Tool Calling Loop**
```
User: "Should we rollback?"
→ Model calls: getDeployment, queryLogs, getMetrics
→ Model analyzes
→ Model: "Yes, rollback. Reason: ..."
```

**Option B: HITL Workflow**
```
Step 1: CollectEvidence
Step 2: RiskAnalysis
Step 3: [HUMAN APPROVAL REQUIRED]
Step 4: ExecuteRollback
```

**Question:** 当前是否需要 HITL？

**Answer for M3:** HITL 需要：
- Process abstraction
- Checkpoint/resume
- Async execution

当前**不需要**。Tool Calling Loop 返回建议，人工决定即可。

---

### 5.3 Scenario 3: Research Agent

**User Goal:**  
"调查 Rust async runtime 技术方案并形成报告"

**Possible Execution:**

**Option A: Tool Calling Loop**
```
Model: search "Rust async runtime"
→ Tool: search → results
Model: inspect promising sources
→ Tool: fetch content
Model: compare alternatives
Model: synthesize report
→ Response
```

**Option B: Dynamic Planning**
```
Goal: ResearchReportComplete

Actions: search, inspect, compare, identify_gaps, synthesize

Planner dynamically decides:
  search → inspect → identify_gaps → additional_search → compare → synthesize
```

**Question:** Planning 是否必要？

**Answer for M3:** Tool Calling Loop 已经实现了类似 planning。
- Model 动态决定下一步
- Model 基于中间结果调整
- 无需显式 Planner abstraction

**Real Trigger for Planning:**  
当 Tool Calling Loop 失控（太多 tool calls, 没有收敛）。

**Current Status:** 未观察到失控。

---

### 5.4 Scenario Analysis Conclusion

**Key Finding:**  
当前三个 scenarios 都可以通过 **Tool Calling Loop** 满足。

**Multi-Step / Workflow / Planning 的真实 trigger:**  
- HITL (human approval between steps)
- Long-running (需要 checkpoint/resume)
- Complex branching (模型无法理解的业务规则)
- Retry specific step (partial failure recovery)

**M3 Verdict:**  
Multi-Step Process **不是** M3 priority。

---

## 6. External Framework Research

### 6.1 Spring AI

**Relevant Capabilities (Spring AI 2.0):**
- ChatModel
- ChatClient
- ToolCallback
- ChatMemory / MessageChatMemoryAdvisor
- Advisor pattern

**Observation:**  
Spring AI focuses on **execution primitives**, not agent-level semantics.

**What Spring AI Does NOT Provide:**
- Agent definition / registry
- Agent lifecycle
- Process / workflow
- Multi-agent coordination
- Governance

**Implication:**  
Arctra should own **Agent Runtime** layer, not re-implement execution primitives.

---

### 6.2 AgentScope

**Key Concepts:**
- Agent (with Msg, Toolkit, Memory)
- Msg (communication unit)
- Pipeline (workflow)
- Session management

**Key Design:**
- Agent 是 first-class object
- Msg-based communication
- Pipeline 组合 agents

**Lesson for Arctra:**
- Agent 应该是 first-class citizen (不只是 Definition)
- Runtime 应该管理 agent lifecycle
- 但不要复制 Msg-based architecture (Spring AI 已有 Message)

---

### 6.3 Embabel

**Key Concepts:**
- Agent (has goals, actions, world state)
- Action (with preconditions/postconditions)
- Goal (completion condition)
- Planner (GOAP-style)
- World State

**Key Design:**
- Goal-driven dynamic planning
- Action selection based on state
- Explicit precondition/postcondition

**Lesson for Arctra:**
- Goal / Action / Planning 是高级能力
- 需要 explicit state management
- 不要过早引入（等真实需求）

**Key Difference:**
- Embabel solves **complex adaptive scenarios**
- Arctra (M1/M2) solves **tool-calling + conversation**
- These are complementary, not competing

---

### 6.4 Research Conclusion

**Key Insight:**  
外部框架的共同特征：
- Agent 是 first-class object (有 lifecycle, configuration, registry)
- Runtime 管理 agent execution
- Tool Calling 只是一种 execution strategy

**Implication for M3:**  
Arctra 需要建立 **Agent Runtime** 层，但不需要立即复制 Workflow/Planning。

---

## 7. Execution Model Analysis

### 7.1 Model A: Engine-Centric (Current)

```
User
  ↓
AgentExecutionEngine
  └── Engine completes entire goal
```

**Pros:**
- Simple
- Works for current scenarios

**Cons:**
- Engine = black box
- No observable intermediate state
- Cannot checkpoint/resume
- Cannot HITL

**Upper Limit:**  
当需要 explicit steps / checkpoints / HITL 时失效。

---

### 7.2 Model B: Process-Centric (Future)

```
User
  ↓
AgentProcess
  ├── Step A → Engine A
  ├── Step B → Engine B
  └── Step C → Engine C
```

**Pros:**
- Observable steps
- Checkpoint/resume
- HITL-capable
- Retry specific step

**Cons:**
- Requires Process abstraction
- More complex
- Overhead for simple scenarios

**Creation Trigger:**  
当真实场景需要 step output → next step input。

---

### 7.3 Model C: Graph/Workflow-Centric (Future)

```
User
  ↓
Graph
  ├── Node A → Engine
  ├── Edge (condition)
  ├── Node B → Engine
  └── Node C → Engine
```

**Pros:**
- Deterministic orchestration
- Branch/loop
- Checkpoint

**Cons:**
- Graph abstraction invasive
- Simple agent forced into graph
- Risk of copying LangGraph

**Recommendation:**  
Graph should be **workflow implementation detail**, not core primitive.

---

### 7.4 Recommended Model for M3

**Hybrid:**
```
User Agent API
  ↓
AgentRuntime
  ├── (if simple) → Engine → Result
  └── (if complex, future) → Process → Steps → Engine → Result
```

**Key:**
- API 隐藏 Engine / Process 差异
- Simple scenarios 不需要 Process
- Complex scenarios (future) 通过 Process

**M3 Focus:**  
建立 **AgentRuntime** 和 **Agent API**，为 future Process 预留空间。

---

## 8. AgentRuntime Long-Term Role

### 8.1 Runtime 应该负责什么？

**Proposed Responsibilities:**

1. **Agent Resolution**
   - Resolve agent by name/id (future: from registry)
   - Currently: user provides AgentDefinition

2. **Execution Strategy Selection**
   - Select Engine based on agent type (future)
   - Currently: only ToolCallingEngine

3. **Lifecycle Management**
   - Create/resume execution
   - Manage state (future: Process state)

4. **Context Management**
   - Provide AgentExecutionContext
   - Manage session (future: persistent)

5. **Observability**
   - Emit execution events (future)
   - Currently: Evidence only

6. **Coordination** (future)
   - Multi-agent coordination
   - Sub-agent management

**M3 Focus:**  
Runtime 至少应该提供 **简化的 API** 和 **Engine abstraction**。

---

### 8.2 Runtime 不应该负责什么？

**NOT Runtime:**
- ❌ Execution strategy implementation (Engine 的责任)
- ❌ Model invocation (Engine / Spring AI)
- ❌ Tool execution (Engine / Spring AI)
- ❌ Prompt construction (Engine)

**Key Principle:**  
Runtime = orchestration, Engine = execution

---

## 9. AgentExecutionEngine Compatibility Review

### 9.1 Current Contract

```java
AgentResult execute(
    AgentDefinition definition,
    AgentRequest request,
    AgentExecutionContext context
);
```

### 9.2 Compatibility Analysis

**Question 1:** 是否适合长期作为 Agent-level execution？

**Answer:** ⚠️ EVOLVE LATER

- 当前 contract 更像 **request-level execution**
- 未来可能需要 **goal-level execution** (Process)
- 但当前不需要修改

---

**Question 2:** 如果加入 Process，会不会冲突？

**Answer:** ✅ NO CONFLICT

Future:
```
Process
  ├── Execution 1: engine.execute(def, req1, ctx)
  ├── Execution 2: engine.execute(def, req2, ctx)
  └── Execution 3: engine.execute(def, req3, ctx)
```

Engine contract 不变，Process 在上层组合。

---

**Question 3:** AgentResult 是否应该始终代表整个 Goal？

**Answer:** ⚠️ DEPENDS

- 当前：AgentResult = one execution result
- 未来：ProcessResult (整个 goal) 可能不同于 AgentResult

But: 不需要 M3 修改。

---

### 9.3 Verdict

**Contract Status:** ✅ **KEEP**

- 当前 contract 不阻碍未来演进
- Process 可以在上层组合
- M3 不需要修改 Engine contract

---

## 10. High-Level Agent API Compatibility

### 10.1 Proposed API Designs

**Design A: Fluent API**
```java
arctra
    .agent("incident-investigator")
    .session("incident-123")
    .user("Analyze 500 errors")
    .call();
```

**Pros:**
- 简洁
- 链式调用
- 隐藏构建细节

**Cons:**
- 需要 agent registry
- "arctra" 是什么？static? bean?

---

**Design B: Builder Pattern**
```java
AgentRequest request = AgentRequest.builder()
    .message("Analyze 500 errors")
    .session("incident-123")
    .build();

runtime.agent("incident-investigator").execute(request);
```

**Pros:**
- 清晰的职责
- Runtime bean injection
- Request 是明确对象

**Cons:**
- 略冗长

---

**Design C: Simple Method**
```java
runtime.execute(
    "incident-investigator",
    "Analyze 500 errors",
    session("incident-123")
);
```

**Pros:**
- 极简
- 无 builder

**Cons:**
- 参数顺序混乱
- 难以扩展

---

### 10.2 Recommended Design

**Hybrid (Builder + Fluent):**
```java
// Get agent handle
var agent = runtime.agent("incident-investigator");

// Execute (fluent)
agent.execute(
    AgentRequest.of("Analyze 500 errors"),
    AgentExecutionContext.withSession("incident-123")
);

// Or builder
agent.execute(
    AgentRequest.builder()
        .message("...")
        .build()
);

// Future: streaming
Flux<AgentEvent> stream = agent.stream(...);
```

**Key:**
- `runtime.agent(name)` returns Agent handle
- Agent handle 隐藏 Engine / Model / Tools
- 当前 AgentRequest / AgentExecutionContext 保持

---

### 10.3 What Changes in M3

**New:**
```java
interface AgentRuntime {
    Agent agent(String name);
    Agent agent(AgentDefinition definition); // for custom
}

interface Agent {
    AgentResult execute(AgentRequest request);
    AgentResult execute(AgentRequest request, AgentExecutionContext context);
    
    // Future
    Flux<AgentEvent> stream(...);
    AgentProcess start(...); // async
}
```

**Keep:**
- AgentDefinition
- AgentRequest
- AgentExecutionContext
- AgentResult
- Evidence

---

## 11. Model Ownership

### 11.1 Current: Constructor Binding

```java
new SpringAiToolCallingEngine(chatModel, tools, chatMemory)
```

### 11.2 Future Scenarios

**Scenario A: Agent-Specific Model**
```
Agent A → Model A (GPT-4)
Agent B → Model B (GPT-3.5)
```

**Scenario B: Step-Specific Model**
```
Step A → cheap model (planning)
Step B → reasoning model (analysis)
```

**Scenario C: Dynamic Routing**
```
ExecutionContext → model selection based on load/cost
```

### 11.3 Analysis

**Question:** 当前 constructor binding 是否阻碍？

**Answer:** ⚠️ MAYBE

未来可能需要：
```java
// Agent → Model mapping
AgentDefinition(name, description, modelRef)

// Runtime resolves
Model model = modelRegistry.resolve(agent.modelRef());
Engine engine = new SpringAiToolCallingEngine(model, ...);
```

But: **没有真实消费者**

### 11.4 M3 Decision

**Action:** 暂不修改

**Reason:**
- 当前 binding 不阻碍 M3 API
- Model selection 可以在 Runtime 层处理
- 等真实需求再引入 ModelRegistry

**Future Trigger:**  
当至少 2 个 agents 需要不同 models。

---

## 12. Tool Ownership

### 12.1 Current: Constructor Binding

```java
new SpringAiToolCallingEngine(..., tools, ...)
```

### 12.2 Future Scenarios

**Scenario A: Agent-Specific Tools**
```
Agent A → [queryLogs, getDeployment]
Agent B → [search, browser]
```

**Scenario B: Tool Governance**
```
Tool: restartService
Agent A: allowed
Agent B: denied
```

### 12.3 Analysis

**Question:** 当前 constructor binding 是否阻碍？

**Answer:** ⚠️ MAYBE

未来可能需要：
```java
// Agent → Tool mapping
AgentDefinition(name, description, toolRefs)

// Runtime resolves + governance
List<Tool> tools = toolRegistry.resolve(agent.toolRefs(), governance);
Engine engine = new SpringAiToolCallingEngine(..., tools, ...);
```

But: **没有真实消费者**

### 12.4 M3 Decision

**Action:** 暂不修改

**Reason:**
- Tool binding 可以在 Runtime 层处理
- 等至少 2 个 agents 共享 tools
- 等 governance 真实需求

**Future Trigger:**  
当 Tool governance 成为真实需求。

---

## 13. Governance Interaction

### 13.1 Future Governance Interception Points

**Option A: Tool-Level**
```
Agent → Tool → [Risk Check] → Execute
```

**Option B: Action-Level** (future)
```
Process → Action → [Approval] → Execute
```

**Option C: Execution-Level**
```
Runtime → [Policy] → Engine → Execute
```

### 13.2 Analysis

**Question:** M3 是否应该考虑 Governance？

**Answer:** ❌ NO

**Reason:**
- 没有真实 governance 需求
- 先建立正确的 execution boundary
- Governance 可以后续通过 Advisor / Interceptor 加入

**Future Trigger:**  
当出现真实场景需要：
- Tool permission check
- Risk evaluation
- Human approval
- Audit logging

---

## 14. Evidence Compatibility

### 14.1 Current Evidence

```java
record Evidence(String source, String content)
```

### 14.2 Future Evidence Sources

```
tool:queryLogs
action:inspectDeployment (future)
agent:subAgent (future)
human:approval (future)
step:correlate (future)
```

### 14.3 M3 Decision

**Action:** ✅ KEEP

**Reason:**
- 当前 Evidence abstraction 正确
- Framework-wide observation semantic
- 不需要修改

**Don't Add (Yet):**
- processId
- stepId
- timestamp
- metadata

等真实需求。

---

## 15. Candidate M3 Directions

Based on analysis, propose 4 candidates:

---

### Candidate A: Agent API & Runtime Boundary

**解决什么问题:**
- 当前 API 过于底层
- 用户直接操作 Engine/Model/Tools
- 无法隐藏 execution strategies

**当前消费者:**
- ✅ 所有 Arctra 用户

**为什么现在做:**
- 建立正确的 API 是后续演进的基础
- Vertical slice: API first, infrastructure later
- 通过 API 设计 discover 真实需求

**为什么不是 M4:**
- 没有稳定 API，无法演进

**Public API 影响:**
- ✅ 新增 Agent API
- ✅ 保持 current API backward compatible

**Spring AI Dependency 影响:**
- ✅ NO CHANGE (封装更好)

**新 Abstraction:**
- `Agent` interface (handle, not entity)
- `AgentRuntime` 实现 (非 trivial)

**Abstraction Creation Trigger:**
- ✅ Real consumer: users need simpler API
- ✅ Improves encapsulation

**实现复杂度:** LOW-MEDIUM

**Architecture Leverage:** ✅ HIGH
- 为 future Process/Workflow 预留空间
- 不阻碍任何演进方向

---

### Candidate B: Persistent Session + Context Management

**解决什么问题:**
- Session 只支持 in-memory
- Application restart 丢失 session
- 无 turn-aware compaction

**当前消费者:**
- ⚠️ MAYBE (production users need durability)

**为什么现在做:**
- Multi-turn 没有 persistence 只是 demo

**为什么不是 M4:**
- 可以 M4，不影响架构方向

**Public API 影响:**
- ✅ MINIMAL (internal implementation)

**Spring AI Dependency 影响:**
- ⚠️ 需要 persistent ChatMemory implementation

**新 Abstraction:**
- Persistent ChatMemory implementation
- Turn-aware compaction

**Abstraction Creation Trigger:**
- ⚠️ No urgent trigger

**实现复杂度:** MEDIUM

**Architecture Leverage:** ⚠️ MEDIUM
- 重要但不影响架构方向

---

### Candidate C: Multi-Step Process Foundation

**解决什么问题:**
- 无法 explicit steps
- 无法 checkpoint/resume
- 无法 HITL

**当前消费者:**
- ❌ NO (scenarios 都可以用 Tool Loop)

**为什么现在做:**
- ???

**为什么不是 M4:**
- 应该 M4 或更晚

**Public API 影响:**
- ⚠️ SIGNIFICANT (new Process API)

**Spring AI Dependency 影响:**
- ✅ NO CHANGE

**新 Abstraction:**
- AgentProcess
- Step / Action
- Process state

**Abstraction Creation Trigger:**
- ❌ NO real consumer

**实现复杂度:** HIGH

**Architecture Leverage:** ❌ LOW (过早)

---

### Candidate D: Governance Foundation

**解决什么问题:**
- 无 tool permission
- 无 risk evaluation
- 无 audit

**当前消费者:**
- ❌ NO

**为什么现在做:**
- ???

**为什么不是 M4:**
- 应该 M4+

**Public API 影响:**
- ⚠️ MEDIUM (governance hooks)

**新 Abstraction:**
- ToolPolicy
- RiskEvaluator
- AuditLogger

**Abstraction Creation Trigger:**
- ❌ NO real consumer

**实现复杂度:** MEDIUM-HIGH

**Architecture Leverage:** ❌ LOW (过早)

---

## 16. Candidate Scoring

| Candidate | Arch Leverage | Real Consumer | Future Compat | Impl Risk | Framework Indep | Premature Risk | **Total** |
|-----------|---------------|---------------|---------------|-----------|-----------------|----------------|-----------|
| **A. Agent API** | 5 | 5 | 5 | 4 | 5 | 5 | **29/30** |
| B. Persistent Session | 3 | 3 | 5 | 4 | 4 | 4 | **23/30** |
| C. Multi-Step | 4 | 1 | 5 | 2 | 5 | 2 | **19/30** |
| D. Governance | 3 | 1 | 4 | 3 | 4 | 2 | **17/30** |

**Scoring Explanation:**

**Candidate A (Agent API):**
- Architecture Leverage: 5 - 建立正确边界，支持所有未来方向
- Real Consumer: 5 - 所有用户
- Future Compatibility: 5 - 不阻碍任何演进
- Implementation Risk: 4 - LOW risk
- Framework Independence: 5 - 更好的封装
- Premature Abstraction Risk: 5 - LOW risk (有消费者)

**Candidate B (Persistent Session):**
- Architecture Leverage: 3 - 重要但不影响方向
- Real Consumer: 3 - production 需要，但不紧急
- Implementation Risk: 4 - MEDIUM

**Candidate C (Multi-Step):**
- Real Consumer: 1 - 没有
- Implementation Risk: 2 - HIGH
- Premature Abstraction Risk: 2 - HIGH risk

**Candidate D (Governance):**
- Real Consumer: 1 - 没有
- Premature Abstraction Risk: 2 - HIGH risk

---

## 17. Recommended M3 Theme

**M3 PRIMARY THEME:**

# **Agent API & Runtime Boundary**

**Why This Theme:**

1. **Real Consumer Exists**
   - 所有 Arctra 用户需要更简单的 API
   - 当前直接操作 Engine 过于底层

2. **Foundation for Future**
   - Agent API 是后续演进的 discovery vehicle
   - 通过 API 使用发现真实需求（Process? Workflow? Planning?）
   - 不阻碍任何未来方向

3. **Vertical Slice Principle**
   - API first, infrastructure later
   - 避免 architecture-first 陷阱

4. **Right Time**
   - M1/M2 建立了 execution primitives
   - 现在需要 user-facing abstraction
   - 不是太早也不是太晚

5. **Minimal Abstraction**
   - 只创建必要的 Agent handle 和 Runtime
   - 不创建 Process/Workflow/Planning (等真实需求)

**Why NOT Other Candidates:**

**Not Multi-Step (C):**
- ❌ NO real consumer
- Tool Calling Loop 足够当前 scenarios
- 过早引入 Process abstraction
- YAGNI violation

**Not Persistent Session (B):**
- 重要但不影响架构方向
- 可以 M3.5 或 M4
- 先建立正确的 API 边界更重要

**Not Governance (D):**
- ❌ NO real consumer
- 过早
- 应该等 Process/Action 明确后再设计 interception points

---

## 18. Proposed Task Breakdown

### M3-T1: Agent API Design & Contract Gate

**Goal:**  
设计 Agent API，通过 Contract Gate 验证合理性

**Why Now:**  
必须先设计 API 才能实现

**Scope:**
- 设计 `Agent` interface
- 设计 `AgentRuntime` contract
- 分析与 current API 的兼容性
- 设计 future extensibility (streaming, async, process)

**Non-Goals:**
- ❌ 不实现
- ❌ 不涉及 Agent Registry
- ❌ 不涉及 Model/Tool resolution

**Dependencies:**  
M2 COMPLETE

**Deliverables:**
- `M3-T1-AGENT-API-CONTRACT-GATE.md`
- Proposed API design
- Compatibility analysis
- Migration path

**Acceptance Criteria:**
- API 设计覆盖 current scenarios
- API 不阻碍 future scenarios (Process, Streaming, HITL)
- Backward compatibility path 明确

**Architecture Questions:**
- `Agent` 是 handle 还是 entity？
- `AgentRuntime` 如何获取 (bean? static?)
- 如何隐藏 Engine/Model/Tools？

**PoC Required:** ❌ NO

**Estimated Effort:** 3-5 days

---

### M3-T2: AgentRuntime Implementation

**Goal:**  
实现 Agent API 的核心 Runtime

**Why Now:**  
T1 Contract Gate 通过后实现

**Scope:**
- 实现 `AgentRuntime`
- 实现 `Agent` handle
- Engine abstraction
- Simple agent resolution (by AgentDefinition, not registry)

**Non-Goals:**
- ❌ 不实现 Agent Registry
- ❌ 不实现 Model/Tool Registry
- ❌ 不实现 Persistent Session
- ❌ 不实现 Process

**Dependencies:**  
M3-T1 COMPLETE

**Deliverables:**
- `AgentRuntime` interface + implementation
- `Agent` interface
- Tests (unit + integration)

**Acceptance Criteria:**
- API 可用
- Current scenarios 通过 new API 工作
- Backward compatibility 保持

**Architecture Questions:**
- Runtime 如何持有 Engine instance？
- Agent handle lifecycle？

**PoC Required:** ❌ NO

**Estimated Effort:** 5-7 days

---

### M3-T3: Example Migration & Documentation

**Goal:**  
Migrate examples 到 new API，完善文档

**Why Now:**  
验证 API 可用性

**Scope:**
- Migrate incident-investigator 到 new API
- 保持 old API 也能用 (backward compatibility demo)
- 更新 Quick Start Guide
- 更新 Architecture docs

**Non-Goals:**
- ❌ 不删除 old API

**Dependencies:**  
M3-T2 COMPLETE

**Deliverables:**
- Updated examples
- Agent API Quick Start Guide
- M3 Implementation Report

**Acceptance Criteria:**
- Examples 使用 new API
- Old API still works
- Documentation complete

**PoC Required:** ❌ NO

**Estimated Effort:** 3-4 days

---

### M3-T4 (Optional): Persistent Session Foundation

**Goal:**  
如果时间允许，实现 persistent session

**Why Optional:**  
不影响 M3 core theme

**Scope:**
- Persistent ChatMemory implementation (JDBC or Redis)
- Configuration
- Migration guide

**Dependencies:**  
M3-T3 COMPLETE

**Deliverables:**
- PersistentChatMemory
- Configuration guide

**Estimated Effort:** 5-7 days

---

## 19. Architecture Gates

### Gate 1: M3-T1 Contract Gate (REQUIRED)

**Before:**  
任何 Agent API implementation

**Must Answer:**
1. Agent API 是否覆盖 current scenarios?
2. Agent API 是否兼容 future scenarios (Process, Streaming)?
3. Backward compatibility 如何保证?
4. `Agent` 是什么？handle? entity?
5. `AgentRuntime` 如何获取？
6. Engine/Model/Tools 如何隐藏？

**Approval Required:** YES

---

### Gate 2: No Process Abstraction in M3 (GUARD)

**Prevent:**  
在 M3 创建 AgentProcess / Step / Action

**Unless:**  
出现真实消费者证明需要

**Trigger:**  
当出现真实场景需要：
- Step output → next step input
- Checkpoint between steps
- Retry specific step
- HITL between steps

**Current Status:** Trigger NOT met

---

## 20. Explicit Non-Goals

**M3 明确不做：**

❌ **Multi-Step Process**
- No AgentProcess abstraction
- No Step abstraction
- No Process state
- No checkpoint/resume
- Reason: NO real consumer

❌ **Workflow / Graph**
- No Workflow abstraction
- No Graph abstraction
- No Node/Edge
- Reason: NO real consumer

❌ **Goal Planning**
- No Goal abstraction
- No Planner
- No Action abstraction
- No World State
- Reason: NO real consumer, too advanced

❌ **Agent Registry**
- No persistent agent storage
- No agent versioning
- Reason: NO real need (AgentDefinition 足够)

❌ **Model Registry / Selection**
- No ModelRegistry
- No dynamic model selection
- Reason: NO real consumer

❌ **Tool Registry / Governance**
- No ToolRegistry
- No Tool governance
- No permission/risk check
- Reason: NO real consumer

❌ **HITL**
- No human approval
- No pause/resume
- Reason: Requires Process abstraction

❌ **Sub-Agent**
- No agent hierarchy
- No agent coordination
- Reason: NO real consumer

❌ **Streaming**
- No streaming API
- Reason: Not blocking current API design

❌ **Complex Session Management**
- No session locking (可选 M3-T4)
- No distributed session (future)
- No context compaction (future)

---

## 21. Migration / Compatibility

### 21.1 Backward Compatibility

**Principle:**  
M3 new API 与 current API 共存

**Current API (保持):**
```java
var engine = new SpringAiToolCallingEngine(...);
engine.execute(definition, request, context);
```

**New API (M3):**
```java
var agent = runtime.agent(definition);
agent.execute(request, context);
```

**Both work.**

### 21.2 Migration Path

**Phase 1 (M3):**
- New API available
- Examples 使用 new API
- Old API still works

**Phase 2 (M4?):**
- Old API deprecated (if new API proven)

**Phase 3 (M5?):**
- Old API removed (maybe)

**Key:**  
不强制 migration，让 API 价值 speak for itself

---

## 22. Risks

### Risk 1: Agent API 设计错误

**Impact:** HIGH  
**Mitigation:**
- Contract Gate before implementation
- Prototype with examples
- Review against future scenarios

---

### Risk 2: AgentRuntime 职责不清

**Impact:** MEDIUM  
**Mitigation:**
- 明确 Runtime 只负责 orchestration
- 不把 execution 放进 Runtime

---

### Risk 3: 用户不愿意 migrate

**Impact:** LOW  
**Mitigation:**
- 保持 backward compatibility
- 不强制 migration
- 通过文档展示 new API 价值

---

### Risk 4: M3 后仍然不知道是否需要 Process

**Impact:** MEDIUM  
**Mitigation:**
- 这是正常的 discovery process
- Agent API 使用会 reveal 真实需求
- 不要过早创建 Process

---

## 23. Open Questions

### Q1: AgentRuntime 如何 obtain？

**Options:**
- Spring Bean injection
- Static factory
- Builder

**Decision:** Defer to M3-T1 Contract Gate

---

### Q2: Agent 是否应该有 lifecycle？

**Options:**
- Stateless handle (当前倾向)
- Stateful entity

**Decision:** Defer to M3-T1 Contract Gate

---

### Q3: 是否支持 custom AgentDefinition？

**Options:**
- Only predefined agents
- Allow user-provided AgentDefinition

**Decision:** 应该支持 custom (当前 M2 就支持)

---

### Q4: 是否在 M3 实现 Persistent Session？

**Options:**
- M3-T4 optional
- Defer to M4

**Decision:** Optional，时间允许就做

---

## 24. Approval Gate

**Seeking Approval For:**

✅ **M3 Theme: Agent API & Runtime Boundary**

✅ **M3 Tasks:**
- M3-T1: Agent API Contract Gate (REQUIRED)
- M3-T2: AgentRuntime Implementation (REQUIRED)
- M3-T3: Example Migration & Docs (REQUIRED)
- M3-T4: Persistent Session (OPTIONAL)

✅ **Explicit Non-Goals:**
- No Process / Workflow / Planning
- No Agent Registry
- No Model/Tool Registry
- No Governance
- No HITL
- No Sub-Agent
- No Streaming (for now)

✅ **Key Principles:**
- API first, infrastructure later
- Vertical slice discovery
- No premature abstraction
- Real consumer driven

---

**Approval Required Before:**
- Creating M3-T1/T2/T3 in TASKS.md
- Starting M3-T1 implementation
- Marking M3 as IN_PROGRESS

---

**M3 Phase Planning Complete.**  
**Awaiting Your Approval.**
