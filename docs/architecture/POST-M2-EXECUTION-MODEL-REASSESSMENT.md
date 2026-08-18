# Post-M2 Execution Model Reassessment

**Date:** 2026-08-18  
**Type:** Architecture Evolution Gate  
**Status:** COMPLETE

---

## Executive Summary

**Purpose:** 评估 M1/M2 当前执行模型是否能自然演进到 Multi-step / Workflow / Goal Planning，防止架构锁死在"一次 Prompt + Tool Loop = 一个 AgentResult"的初级模型。

**Verdict:** 🟡 **YELLOW - Semantically Compatible, Documentation Critical**

**Key Finding:**  
当前代码架构**技术上兼容**未来 Multi-step，但**语义边界模糊**，存在误解风险。必须立即明确：

- ✅ `AgentExecutionEngine` 是 **execution strategy**，不拥有完整 Agent semantics
- ✅ `Tool Calling Loop` 是 **engine implementation detail**，不是 Process abstraction
- ✅ `Multi-turn` ≠ `Multi-step`（这是两个正交维度）
- ⚠️ 当前 `execute()` 语义**未明确定义**是"完成 Goal"还是"执行一次策略"

**Action Required:**  
无需修改代码，但必须立即修正架构文档和术语定义，否则未来容易走错方向。

---

## Part 1: CURRENT EXECUTION MODEL

### 1.1 实际执行流程（基于代码）

```
User
  ↓
AgentRuntime (interface, 未实现)
  ↓ (当前直接调用)
AgentExecutionEngine.execute(definition, request, context)
  ↓
SpringAiToolCallingEngine
  ├── Wrap tools with EvidenceCapturingToolCallback
  ├── Build ChatClient
  │    └── (if sessionId) → add MessageChatMemoryAdvisor
  ├── Build prompt (system + user + tools)
  ├── (if sessionId) → pass conversationId to advisor
  └── call()
       ↓
Spring AI ChatClient
  └── ToolCallingAdvisor (Spring AI internal)
       ↓
Model → Tool → Model → Tool → Model
       ↓
Final Response
  ↓
AgentResult(content, evidences)
```

### 1.2 当前 AgentExecutionEngine.execute() 语义

**Contract:**
```java
AgentResult execute(
    AgentDefinition definition,
    AgentRequest request,
    AgentExecutionContext context
);
```

**问题：当前语义是什么？**

代码事实：
- 接收一个 user request
- 返回一个 result（content + evidences）
- 内部可能调用多次 Tool（Tool Calling Loop）
- 但这是**一次 execute() call**

**当前语义解读（未文档化）：**

可能的解读 A:
> execute() = 完成整个 Agent Goal

可能的解读 B:
> execute() = 执行一次 Agent execution strategy

可能的解读 C:
> execute() = 处理一个 user turn

**实际行为倾向于 C**，但文档没有明确。

### 1.3 Tool Calling Loop 的位置

**Tool Calling Loop:**
```
Model → "need tool A" → call tool A → Model → "need tool B" → call tool B → Model → final answer
```

**问题：这属于什么层级？**

当前代码事实：
- Tool Loop 完全封装在 `SpringAiToolCallingEngine.execute()` 内部
- 由 Spring AI `ToolCallingAdvisor` 自动处理
- 外部看不到中间状态
- 一次 `execute()` 返回一个最终 result

**结论：**  
Tool Calling Loop 是 **SpringAiToolCallingEngine 的 implementation detail**，不是 Arctra 的 Process abstraction。

外部视角：
```
execute() → AgentResult
```

内部视角（Spring AI）：
```
execute() → [Model → Tool → Model → Tool → Model] → AgentResult
```

---

## Part 2: TERMINOLOGY DISAMBIGUATION

### 2.1 必须区分的四个概念

#### A. Multi-Turn Conversation (M2 已解决)

```
Session A:
  Turn 1: User → Agent
  Turn 2: User → Agent (sees Turn 1)
  Turn 3: User → Agent (sees Turn 1-2)
```

- **Dimension:** Conversation continuity
- **Mechanism:** ChatMemory
- **Boundary:** Session
- **State:** Conversation history

---

#### B. Tool Calling Loop (M1 已有)

```
One execute() call:
  Model → "call queryLogs" → Tool → Model → "call getDeployment" → Tool → Model → Answer
```

- **Dimension:** Model-driven tool orchestration
- **Mechanism:** Spring AI ToolCallingAdvisor
- **Boundary:** One execute() call
- **State:** Spring AI internal (not exposed to Arctra)

---

#### C. Multi-Step Agent Process (未实现)

```
Goal: Diagnose Incident

Process:
  Step 1: CollectLogs → LogEvidence
  Step 2: AnalyzeLogs(LogEvidence) → FailureHypothesis
  Step 3: InspectDeployment(FailureHypothesis) → DeploymentEvidence
  Step 4: CorrelateEvidence(LogEvidence, DeploymentEvidence) → RootCause
  Step 5: RecommendAction(RootCause) → Recommendation
```

- **Dimension:** Structured workflow
- **Mechanism:** NOT IMPLEMENTED
- **Boundary:** Process (multiple executions/steps)
- **State:** Process state (step outputs, checkpoints)
- **Key:** Step output → next step input

---

#### D. Dynamic Goal Planning (未实现)

```
Goal: Diagnose Incident

Available Actions: [collectLogs, analyzeLogs, inspectDeployment, correlate, ...]

Planner:
  State: {}
  → choose: collectLogs
  State: {logs: ...}
  → re-evaluate, choose: inspectDeployment
  State: {logs: ..., deployment: ...}
  → re-evaluate, choose: correlate
  State: {rootCause: ...}
  → Goal achieved
```

- **Dimension:** Goal-driven dynamic planning
- **Mechanism:** NOT IMPLEMENTED (GOAP / Embabel-style)
- **Boundary:** Goal lifecycle
- **State:** World state / domain state
- **Key:** Planner dynamically chooses actions based on state

---

### 2.2 Critical Distinctions

**Multi-turn ≠ Multi-step**

| | Multi-turn | Multi-step |
|---|---|---|
| User involvement | Multiple user inputs | Single user input |
| State | Conversation history | Process state |
| Continuity | Across user turns | Across agent steps |
| Example | ChatGPT conversation | Workflow execution |

**Tool Loop ≠ Multi-step Process**

| | Tool Loop | Process |
|---|---|---|
| Visibility | Internal to engine | Explicit to framework |
| State | Not exposed | Checkpointed, resumable |
| Control | Model-driven | Workflow-driven or Goal-driven |
| Boundary | One execute() | Multiple executions/steps |

**Session ≠ Process**

| | Session | Process |
|---|---|---|
| Semantic | Conversation boundary | Task execution boundary |
| Lifecycle | User-initiated, long-lived | Goal-initiated, complete when done |
| Relationship | One session → many processes | One process → execute in one session |

**Example:**
```
Session S1:
  Turn 1 → Process P1 (diagnose incident-1) → Result
  Turn 2 → Process P2 (diagnose incident-2) → Result
  Turn 3 → Process P3 (recommend action for incident-1) → Result
```

---

## Part 3: EMBABEL ARCHITECTURE FINDINGS

### 3.1 Embabel Core Concepts

**Based on Embabel documentation and examples:**

1. **Agent** - Has goals, actions, world state
2. **Goal** - What the agent wants to achieve
3. **Action** - What the agent can do (with preconditions/postconditions)
4. **World State** - Current state of the domain
5. **Plan** - Sequence of actions to achieve goal
6. **Planning** - GOAP-style (Goal-Oriented Action Planning)

### 3.2 Embabel Execution Model

```
User: "Achieve Goal X"
  ↓
Agent receives Goal
  ↓
Planner:
  - Current state: S0
  - Goal: G
  - Available actions: [A1, A2, A3, ...]
  ↓
Planning (GOAP):
  - Find action sequence: [A1, A3, A5]
  ↓
Execution:
  Execute A1 → state changes to S1
  Execute A3 → state changes to S2
  Execute A5 → state changes to S3
  ↓
Check if Goal G achieved in S3
  - Yes → Done
  - No → Replan
```

### 3.3 Key Difference from "Prompt + Tools"

**"Prompt + Tools" (Current Arctra):**
```
User: "Diagnose incident"
→ Model decides: call queryLogs → call getDeployment → answer
→ One execute() → AgentResult
```

**Embabel (Goal Planning):**
```
User: "Diagnose incident"
→ Goal: IncidentDiagnosed
→ Planner: 
   State: {}
   Action: CollectLogs (precondition: none, postcondition: logs available)
   State: {logs}
   Action: AnalyzeLogs (precondition: logs available, postcondition: hypothesis formed)
   State: {logs, hypothesis}
   ...
   Goal achieved: IncidentDiagnosed
```

**Key Differences:**

1. **State Management:**
   - Prompt + Tools: No explicit state (Model internal)
   - Embabel: Explicit world state

2. **Action Selection:**
   - Prompt + Tools: Model decides in natural language
   - Embabel: Planner uses preconditions/postconditions

3. **Goal Completion:**
   - Prompt + Tools: Model says "done"
   - Embabel: Explicit goal condition checked against state

4. **Replanning:**
   - Prompt + Tools: Model self-corrects in same call
   - Embabel: Explicit replan after each action

5. **Determinism:**
   - Prompt + Tools: Model-driven (non-deterministic)
   - Embabel: Mix of deterministic planning + LLM actions

### 3.4 What to Learn from Embabel

**✅ Borrow:**
- Explicit state management (when needed)
- Action precondition/postcondition (when needed)
- Goal-driven planning (when needed)
- Separation of planning vs execution

**❌ Don't Copy:**
- Don't create `Goal` / `Action` / `WorldState` now
- Don't force GOAP on all scenarios
- Don't assume all agents need planning
- Embabel is Python-first, different ecosystem

**Key Insight:**
Embabel solves **Complex Multi-Step with Dynamic Planning**. Arctra M1/M2 solves **Tool-Calling + Conversation**. These are complementary, not competing.

Future Arctra should support:
- Simple Tool Calling (M1)
- Multi-turn Conversation (M2)
- Structured Workflow (M3?)
- Goal Planning (M4?)

---

## Part 4: AGENTEXECUTIONENGINE REASSESSMENT

### 4.1 Current Contract

```java
public interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
}
```

### 4.2 Three Interpretations

#### Interpretation A: execute() = Complete Agent Goal

```
execute() semantics: "Achieve the goal implied by the user request"
```

**Implications:**
- One execute() should完成整个任务
- Multi-step必须在execute()内部完成
- Process = one execute() call

**Problems:**
- 无法 checkpoint between steps
- 无法 partial retry
- 无法 HITL (Human-In-The-Loop)
- 无法 observable intermediate steps

**Verdict:** ❌ **Too limiting for future**

---

#### Interpretation B: execute() = Execute One Strategy

```
execute() semantics: "Execute one round of agent strategy"
```

**Implications:**
- execute()是execution primitive
- Multi-step需要外部循环
- Process可以调用多次execute()

**Problems:**
- 当前SpringAiToolCallingEngine已经内部loop（Tool Calling）
- 语义模糊：什么是"one strategy"？

**Verdict:** 🟡 **Closer, but needs clarification**

---

#### Interpretation C: Engine = Execution Mechanism

```
AgentExecutionEngine = pluggable execution mechanism
execute() = process one execution unit using this engine
```

**Implications:**
- Engine不拥有完整Agent semantics
- Engine是未来Process Runtime的一个component
- 不同Engine可以有不同execution unit定义

**Future Model:**
```
AgentProcess
  ├── Step 1 → execute via ToolCallingEngine
  ├── Step 2 → execute via WorkflowEngine
  └── Step 3 → execute via PlanningEngine
```

**Verdict:** ✅ **Most future-compatible**

### 4.3 Recommendation

**Adopt Interpretation C:**

> `AgentExecutionEngine` 是 **pluggable execution strategy**，不是完整的 Agent semantic boundary。
>
> `execute()` 的语义由具体 Engine 定义。对于 `SpringAiToolCallingEngine`，它是"执行一次Tool-Calling-based ReAct loop"。
>
> 未来的 `WorkflowEngine` / `PlanningEngine` 可以有不同的execution unit定义。

**This allows future evolution:**
```
Today:
  User → SpringAiToolCallingEngine.execute() → Result

Future (Multi-step):
  User → AgentProcess
           ├── Step 1: ToolCallingEngine.execute() → partial result
           ├── Step 2: ToolCallingEngine.execute() → partial result
           └── Step 3: ToolCallingEngine.execute() → final result

Future (Planning):
  User → GoalPlanningEngine.execute() → Result
         (internally: plan → action → replan → action → done)
```

---

## Part 5: AGENTDEFINITION REASSESSMENT

### 5.1 Current State

```java
public record AgentDefinition(String name, String description) {}
```

**Extremely minimal.**

### 5.2 Risk Analysis

**Question:** 如果未来变成：

```java
AgentDefinition(
    name,
    description,
    model,
    tools
)
```

是否把Arctra限制成"LLM Tool Agent Configuration Framework"？

**Answer:** ⚠️ **Yes, 存在风险**

如果AgentDefinition = "model + tools configuration"，则暗示：
- Agent = configured LLM + tools
- 无法表达 workflow-based agent
- 无法表达 goal-planning agent
- 无法表达 code-only agent

### 5.3 Semantic Direction

**AgentDefinition 应该表达什么？**

**Option A: Agent Template**
```java
AgentDefinition(
    identity,    // who the agent is
    capabilities, // what it can do (abstract)
    constraints  // what it cannot do
)
```

**Option B: Agent Configuration**
```java
AgentDefinition(
    name,
    modelRef,
    toolRefs,
    systemPrompt
)
```

**Option C: Agent Specification**
```java
AgentDefinition(
    name,
    goals,
    actions,
    execution strategy
)
```

**Recommendation:**

当前的 `AgentDefinition(name, description)` **accidentally correct** - 太简单以至于不会限制未来。

但未来扩展时：
- ❌ 不要把它变成纯 LLM configuration
- ✅ 保持它是 agent identity + high-level semantics
- ✅ 具体的execution details由Engine处理

**Future Direction:**
```java
AgentDefinition(
    name,
    description,
    // Future:
    // - identity / persona (not implementation)
    // - goals (what it should achieve, not how)
    // - capabilities (abstract, not concrete tools)
    // - constraints (policies, not implementation)
)
```

**Concrete execution concerns belong elsewhere:**
- Model → Engine configuration or runtime selection
- Tools → Engine configuration or registry
- Prompt → Engine implementation detail

---

## Part 6: AGENTEXECUTIONCONTEXT REASSESSMENT

### 6.1 Current State

```java
public record AgentExecutionContext(String sessionId) {
    public static AgentExecutionContext stateless();
    public static AgentExecutionContext withSession(String sessionId);
}
```

### 6.2 Future Context Needs

未来可能需要：
- `processId` - identify a multi-step process
- `executionId` - identify a single execution within process
- `traceId` - distributed tracing
- `userId` / `tenantId` - multi-tenancy
- `checkpointId` - resume from checkpoint
- `parentExecutionId` - sub-agent tracking

**Risk:** AgentExecutionContext becomes a dumping ground.

### 6.3 Design Principles

**What belongs in ExecutionContext:**
- ✅ Execution-level identity (sessionId, processId, executionId)
- ✅ Cross-cutting concerns (tracing, tenancy)
- ✅ Execution environment (isolation, permissions)

**What does NOT belong:**
- ❌ User input (belongs in AgentRequest)
- ❌ Agent template (belongs in AgentDefinition)
- ❌ Execution result (belongs in AgentResult)
- ❌ Business domain state (belongs in Process state)
- ❌ Tool-specific context (belongs in Tool runtime)

**Guideline:**

> ExecutionContext = "execution environment" orthogonal to "what to execute" (Definition + Request) and "execution result" (Result).

**Recommendation:**

当前 `AgentExecutionContext` 是正确的 extension point，但需要明确 **inclusion criteria**：

- Context是execution-level cross-cutting concern
- Context不依赖业务领域
- Context在不同execution strategies下语义一致

**Example good additions:**
- `traceId` - yes (cross-cutting)
- `processId` - yes (execution identity)
- `userId` - yes (cross-cutting)

**Example bad additions:**
- `incidentId` - no (domain-specific, belongs in Request or Process state)
- `toolContext` - no (engine-specific)
- `modelParameters` - no (engine configuration)

---

## Part 7: EVIDENCE REASSESSMENT

### 7.1 Current State

```java
public record Evidence(String source, String content) {}
```

**Purpose:** Framework-wide execution observation

**Current source:** `tool:queryLogs`, `tool:getDeployment`

### 7.2 Future Evidence Sources

未来可能来源：
- `tool:queryLogs`
- `action:inspectDeployment`
- `retrieval:runbook`
- `agent:securityAgent` (sub-agent)
- `human:approval`
- `step:correlateEvidence`
- `external:pagerduty`

### 7.3 Semantic Question

**Question:** Evidence 是否被当前 ToolCallback implementation 错误地"语义收窄"？

**Analysis:**

当前实现：
- Evidence 由 `EvidenceCapturingToolCallback` 生成
- 只捕获 Tool execution

但Evidence的语义定义：
> Framework-wide execution observation

**Conclusion:** ✅ **Current abstraction正确，implementation coverage不完整（预期）**

Evidence abstraction没有被"收窄"，只是当前唯一的evidence producer是Tool execution。

未来可以有：
- ActionEvidenceCollector
- RetrievalEvidenceCollector
- SubAgentEvidenceCollector
- 等等

**Recommendation:**

- ✅ Evidence abstraction保持不变
- ✅ 未来添加新evidence sources时，继续使用Evidence record
- ⚠️ 考虑未来是否需要Evidence subtyping或metadata（但不是现在）

---

## Part 8: SESSION VS PROCESS

### 8.1 Relationship Model

```
Session S1 (conversation boundary):
  Turn 1:
    User: "Analyze incident"
    → Process P1 (incident analysis task)
       ├── Execution E1
       ├── Execution E2
       └── Execution E3
    → Result

  Turn 2:
    User: "What about deployment?"
    → Process P2 (deployment analysis task)
       ├── Execution E1
       └── Execution E2
    → Result
```

**Key Points:**

1. **Session** - conversation continuity boundary
   - User-initiated
   - Long-lived
   - Maintains conversation history

2. **Process** - task execution boundary
   - Goal-initiated
   - Lifecycle: start → execute → complete
   - Maintains task state (if multi-step)

3. **Execution** - single engine invocation
   - One `engine.execute()` call
   - May trigger internal loop (tool calling)
   - Returns one AgentResult

### 8.2 Critical Anti-Pattern

**❌ DO NOT:** Session = Process

This would mean:
- One session can only execute one process
- Multi-step process = multi-turn conversation
- Cannot have multiple independent tasks in same conversation

**✅ CORRECT:** One session → many processes

### 8.3 Recommendation

**Document explicitly:**

> - **Session** = conversation continuity (ChatMemory boundary)
> - **Process** = task execution lifecycle (Goal → Complete)
> - **Execution** = one engine.execute() call
>
> Relationship: One session can contain multiple processes. One process can span multiple executions (if multi-step).

---

## Part 9: WORKFLOW VS GOAL PLANNING

### 9.1 Two Types of Multi-Step

#### Type A: Developer-Defined Workflow

```java
Workflow incidentDiagnosis = Workflow.builder()
    .step("collectLogs", collectLogsAction)
    .step("analyzeLogs", analyzeLogsAction)
    .step("inspectDeployment", inspectDeploymentAction)
    .step("correlate", correlateAction)
    .build();
```

**Characteristics:**
- Structure defined by developer
- Deterministic flow
- Nodes, edges, branches
- Examples: LangGraph, Spring Batch

---

#### Type B: Goal-Driven Dynamic Planning

```java
Goal goal = Goal.builder()
    .condition(state -> state.has("rootCause"))
    .build();

Agent agent = Agent.builder()
    .goal(goal)
    .actions(collectLogs, analyzeLogs, inspectDeployment, correlate)
    .build();

// Runtime decides: collectLogs → inspectDeployment → correlate (skips analyzeLogs)
```

**Characteristics:**
- Flow determined at runtime
- Planner uses preconditions/postconditions
- Non-deterministic (depends on state)
- Examples: Embabel, GOAP

### 9.2 Which Should Arctra Support?

**Options:**

A. Only Workflow (deterministic)  
B. Only Planning (dynamic)  
C. Both  
D. Neither now, but architecture doesn't block either

**Recommendation: D → eventually C**

**Rationale:**

1. **Current M1/M2:** Neither is needed yet (single-execution scenarios)

2. **First Multi-Step Need:** Likely Workflow (more common, easier to reason about)

3. **Advanced Scenarios:** Goal Planning (complex, adaptive scenarios)

4. **Architecture Strategy:** Don't bake either into core now, but ensure both can be added later

**How to stay compatible:**

- ✅ Keep `AgentExecutionEngine` as pluggable strategy
- ✅ Don't assume "one execute() = complete task"
- ✅ Keep Process abstraction separate from Session
- ✅ Keep Evidence as general observation mechanism

**Future coexistence:**
```
WorkflowEngine implements AgentExecutionEngine
PlanningEngine implements AgentExecutionEngine
ToolCallingEngine implements AgentExecutionEngine (current)
```

All can be orchestrated by a higher-level Process Runtime.

---

## Part 10: FUTURE AGENT API COMPATIBILITY

### 10.1 Desired Future API

```java
arctra
    .agent("incident-investigator")
    .session("incident-123")
    .user("分析生产事故")
    .call();
```

**Question:** 这个API能否隐藏不同execution strategies？

### 10.2 What Could Be Hidden

**A. Simple Tool Calling (M1)**
```java
.call() → SpringAiToolCallingEngine.execute() → AgentResult
```

**B. Multi-turn Conversation (M2)**
```java
.session("...").call() → SpringAiToolCallingEngine.execute(withSession) → AgentResult
```

**C. Fixed Workflow**
```java
.call() → WorkflowEngine.execute() → [Step1, Step2, Step3] → AgentResult
```

**D. Dynamic Planning**
```java
.call() → PlanningEngine.execute() → [Plan, Execute, Replan, ...] → AgentResult
```

**E. Sub-Agent Process**
```java
.call() → ProcessEngine.execute() 
         → [SubAgent1.execute(), SubAgent2.execute()] 
         → AgentResult
```

**F. HITL / Checkpoint / Resume**
```java
.call() → Process starts
       → Checkpoint
       → await human approval
       → Resume
       → AgentResult
```

### 10.3 Analysis

**A-D:** ✅ **Can be hidden** - all return AgentResult synchronously

**E:** ✅ **Can be hidden** - internal sub-agents不暴露给API

**F:** ❌ **Cannot be synchronously hidden** - HITL requires async

### 10.4 Recommendation

**Fluent API should support both sync and async:**

```java
// Sync (most cases)
AgentResult result = arctra.agent("...").call();

// Async (HITL, long-running)
AgentProcess process = arctra.agent("...").startAsync();
process.awaitCheckpoint();
process.provideHumanInput(...);
AgentResult result = process.await();
```

**Key Design:**  
- Sync `.call()` hides A-E
- Async `.startAsync()` exposes Process for F
- Same agent can support both (engine decides)

---

## Part 11: ABSTRACTION CREATION TRIGGERS

Based on Architecture Evolution Guide principles, define triggers for each future concept:

| Concept | Current Consumer | Missing Capability | Creation Trigger | Create Now? |
|---------|------------------|-------------------|------------------|-------------|
| **AgentProcess** | None | Multi-step state management | At least one scenario needs: step output → next step input, OR checkpoint/resume | ❌ NO |
| **AgentStep** | None | Explicit step boundary | AgentProcess exists AND needs observable steps | ❌ NO |
| **Action** | None | Precondition/postcondition | Goal Planning implementation | ❌ NO |
| **Goal** | None | Explicit goal condition | Goal Planning implementation | ❌ NO |
| **Plan** | None | Dynamic action sequencing | Goal Planning implementation | ❌ NO |
| **Workflow** | None | Developer-defined flow | At least one scenario needs: deterministic multi-step with branches | ❌ NO |
| **WorkflowEngine** | None | Workflow execution | Workflow abstraction exists | ❌ NO |
| **WorldState** | None | Domain state tracking | Goal Planning implementation | ❌ NO |
| **Checkpoint** | None | Resume from intermediate state | At least one scenario needs: long-running process with resume | ❌ NO |
| **SubAgent** | None | Agent hierarchy | At least one scenario needs: agent calls another agent | ❌ NO |
| **ExecutionEvent** | None | Observable execution lifecycle | At least two consumers need: execution observation | ❌ NO |
| **ProcessRuntime** | None | Process lifecycle management | At least one multi-step scenario exists | ❌ NO |

**Current Conclusion:** All are ❌ NO - 没有真实消费者。

**First Trigger Likely:**  
When a real scenario emerges that says: "I need to inspect logs, THEN based on results, decide whether to check deployment or check database."

This would trigger:
1. AgentProcess (to maintain state between steps)
2. Workflow or Planning (depending on whether flow is fixed or dynamic)
3. ProcessRuntime (to orchestrate)

---

## Part 12: M3 ROADMAP IMPACT

### 12.1 Original M3 Plan (Proposed)

未明确，可能包括：
- Context compaction
- Session improvements
- Persistent ChatMemory
- ...

### 12.2 Reassessment Result

**Question:** 基于本次研究，M3应该优先做什么？

**Options:**

A. **Agent API** - User-facing fluent API  
B. **Process Abstraction** - Multi-step foundation  
C. **Workflow** - Developer-defined multi-step  
D. **Context Compaction** - Fix M2 limitation  
E. **Session Improvements** - Concurrency, persistence  
F. **HITL** - Human-in-the-loop  
G. **Multi-Agent** - Sub-agent orchestration  
H. **Goal Planning** - Embabel-style GOAP

**Recommendation: 先做 A (Agent API)**

**Rationale:**

1. **Vertical Slice Principle:** Need a real consumer (user API) before building Process infrastructure

2. **Current Gap:** User直接调用 `engine.execute()` 不符合"Agent Engineering Harness"定位

3. **Discovery Vehicle:** Agent API 实现会reveal真实需求，决定是否需要 Process/Workflow

4. **Non-blocking:** Agent API 可以先wrap current engine，later evolve to support Process

**M3 Recommendation:**

```
M3: Agent API & Runtime
- AgentClient / Facade
- Simple AgentRuntime implementation
- Agent lifecycle (create, execute, cleanup)
- Observability hooks
- 不实现 Process/Workflow（等真实需求）
```

**Why not Process/Workflow first?**
- 没有真实消费者
- 不知道真实需求形状
- 可能over-engineer

**Discovery Strategy:**
```
M3: Agent API → discover需求
M4: Multi-step (if needed, based on M3 feedback)
```

---

## Part 13: COMPATIBILITY VERDICT

### 13.1 Final Verdict

🟡 **YELLOW - Semantically Compatible, Documentation Critical**

### 13.2 What This Means

**GREEN Components:**
- ✅ Code structure technically compatible
- ✅ No structural lock-in at code level
- ✅ Can add Process/Workflow without breaking current code

**YELLOW Issues:**
- ⚠️ Semantic boundaries未明确文档化
- ⚠️ Terminology混用（multi-turn / multi-step / process）
- ⚠️ `execute()` 语义ambiguous
- ⚠️ Engine ownership未明确

**If left unfixed:**
- 未来开发者可能误解 Engine = complete Agent
- 可能把 multi-step state塞进ChatMemory
- 可能把 process lifecycle塞进Session
- 可能创建错误抽象

### 13.3 Required Actions

**✅ CODE CHANGES:** None required

**⚠️ DOCUMENTATION CHANGES:** Critical

Must document:
1. AgentExecutionEngine = execution strategy (not complete Agent)
2. Tool Calling Loop = engine implementation detail
3. Multi-turn ≠ Multi-step (orthogonal dimensions)
4. Session ≠ Process (different lifecycles)
5. Evidence = framework-wide observation (not just tools)
6. execute() semantics per engine (not universal)

**📋 ARCHITECTURE GUARDRAILS:** Add new document

---

## Part 14: ENGINE VERDICT

**Long-term Positioning:**

> `AgentExecutionEngine` 是 **pluggable execution strategy**，不拥有完整 Agent domain semantics。
>
> 它是未来 Process Runtime / Agent Runtime 的一个 component，负责"如何执行"，不负责"执行什么"（AgentDefinition）、"执行环境"（AgentExecutionContext）、或"执行编排"（Process）。

**Future Architecture:**

```
User Agent API
    ↓
Agent Runtime
    ↓
Process Runtime (optional, for multi-step)
    ↓
Agent Execution Engine (pluggable strategy)
    ├── ToolCallingEngine (current)
    ├── WorkflowEngine (future)
    └── PlanningEngine (future)
    ↓
Model / Tool / Code / Sub-Agent
```

**Key Principle:**

> Engine 执行策略，Runtime 管理生命周期，API 提供接口，Domain Model 表达语义。

---

## Part 15: DO WE CHANGE CODE NOW?

**Answer: ❌ NO**

**Rationale:**

1. **No Real Consumer:** 没有真实场景需要 Multi-step
2. **YAGNI Principle:** 不为未来可能而创建抽象
3. **Code Compatible:** 当前代码结构不阻碍未来演进
4. **Discovery First:** 需要先有 Agent API 和真实场景

**What Changes:**

- ❌ NO production Java code changes
- ✅ YES documentation changes (architecture semantics)
- ✅ YES add architecture guardrails document
- ✅ YES update EVOLUTION-GUIDE if needed

---

## Part 16: DOCUMENTATION CHANGES

### 16.1 Required New Document

**Create:** `docs/architecture/EXECUTION-MODEL-SEMANTICS.md`

**Content:**
- Current execution model (Section 1 of this report)
- Terminology disambiguation (Section 2)
- Session vs Process vs Execution
- Engine positioning
- Multi-turn vs Multi-step
- Architecture guardrails

### 16.2 Required Updates

**Update:** `docs/architecture/EVOLUTION-GUIDE.md`

Add section:
- "Multi-Step Abstraction Creation Triggers"
- "Process vs Execution Distinction"

**Update:** `docs/project/CURRENT-STATE.md`

Add clarification:
- M2 = Multi-turn conversation (not multi-step)
- Current execution model

**Update:** `docs/guides/M2-MULTI-TURN-QUICK-START.md`

Add note:
- Multi-turn ≠ Multi-step

### 16.3 New Guardrails Document

**Create:** `docs/architecture/ARCHITECTURE-GUARDRAILS.md`

Content: Section 18 of this report

---

## Part 17: ADR DECISION

**Question:** 是否需要ADR？

**Answer: ✅ YES**

**Reason:**

这次形成了真正的architecture decision：

> "AgentExecutionEngine 是 execution strategy，不是 complete Agent semantic boundary"

这是长期constraint，影响未来所有演进。

**ADR Topic:**  
"ADR-003: Agent Execution Engine as Pluggable Strategy"

**Status:** Proposed (需要你审批)

**Context:**  
Post-M2 reassessment发现 Engine 语义未明确

**Decision:**  
AgentExecutionEngine 定位为 execution strategy component，不拥有完整 Agent semantics

**Consequences:**
- Future multi-step由 Process Runtime 编排
- Engine专注于"如何执行"
- 允许多种execution strategies共存

---

## Part 18: ARCHITECTURE GUARDRAILS

### 必须遵守的演进原则

#### 1. Terminology

- ✅ Multi-turn = conversation continuity (M2)
- ✅ Multi-step = structured task execution (future)
- ✅ Tool Calling Loop = engine implementation detail
- ✅ Session = conversation boundary
- ✅ Process = task execution boundary
- ✅ Execution = one engine.execute() call
- ❌ 不混用这些术语

#### 2. Semantic Ownership

- ✅ Engine = execution strategy (how to execute)
- ✅ Definition = agent template (what to execute)
- ✅ Request = user input (content)
- ✅ Context = execution environment (where/when)
- ✅ Runtime = lifecycle management (orchestration)
- ❌ Engine 不拥有完整 Agent semantics

#### 3. State Management

- ✅ Conversation state → ChatMemory
- ✅ Process state → Process abstraction (when created)
- ✅ Evidence → per-execution collection
- ❌ 不把 workflow state 塞进 ChatMemory
- ❌ 不把 process lifecycle 塞进 Session

#### 4. Abstraction Creation

- ✅ 只有真实消费者出现才创建
- ✅ 不为"未来可能"创建抽象
- ✅ 参考 EVOLUTION-GUIDE 的 Creation Triggers
- ❌ 不因为 Embabel 很先进就照搬 GOAP

#### 5. Framework Positioning

- ✅ Spring AI = implementation provider
- ✅ Arctra = governance + observability + testing harness
- ✅ Tool = execution primitive (not Agent abstraction)
- ❌ 不重新实现 Spring AI 已有能力

#### 6. API Design

- ✅ Agent API 必须能隐藏不同 execution strategies
- ✅ 支持 sync (simple) 和 async (HITL) 模式
- ✅ API先行，infrastructure后行（vertical slice）
- ❌ 不暴露 Engine 选择给用户（除非explicit需求）

#### 7. Evidence

- ✅ Evidence = framework-wide observation
- ✅ 不限于 Tool execution
- ✅ 未来可以有 action / retrieval / agent / human evidence
- ❌ 不把 Evidence 变成 tool-specific 概念

#### 8. Session vs Process

- ✅ One session → many processes
- ✅ Session = user-initiated, long-lived
- ✅ Process = goal-initiated, complete when done
- ❌ Session ≠ Process

#### 9. Context Extension

- ✅ Context = execution-level cross-cutting concern
- ✅ Good: sessionId, processId, traceId, userId
- ❌ Bad: incidentId (domain), toolContext (engine-specific)
- ❌ 不让 ExecutionContext 变成 dumping ground

#### 10. Multi-Step Future

- ✅ 先有真实需求，再决定 Workflow vs Planning
- ✅ Workflow 和 Planning 可以共存
- ✅ 都通过 Engine interface
- ❌ 不强制所有场景使用同一种 multi-step 模式

---

## Part 19: NEXT RECOMMENDED ACTION

**Only One Recommendation:**

### 📝 Create Architecture Semantics Documentation

**Task:** 创建架构语义文档，明确当前执行模型和术语定义

**Priority:** HIGH (blocking future evolution clarity)

**Deliverables:**
1. `docs/architecture/EXECUTION-MODEL-SEMANTICS.md` (new)
2. `docs/architecture/ARCHITECTURE-GUARDRAILS.md` (new)
3. `docs/adr/003-agent-execution-engine-as-strategy.md` (new)
4. Update `docs/architecture/EVOLUTION-GUIDE.md`
5. Update relevant M2 docs to clarify multi-turn ≠ multi-step

**Why This First:**
- 零代码变更
- 明确未来演进语义
- 防止错误理解
- 为 M3 planning 提供清晰基础

**Why Not M3 Implementation:**
- 没有真实消费者
- 需要先明确语义
- Agent API 需求未确定

---

## FINAL SUMMARY

### 1. CURRENT MODEL
```
User → AgentExecutionEngine.execute() 
     → SpringAiToolCallingEngine 
     → [Tool Calling Loop] 
     → AgentResult
```

### 2. KEY DISCOVERY
**Multi-turn ≠ Multi-step** - 这是两个正交维度，当前M2只解决了multi-turn

### 3. EMBABEL LESSON
- Borrow: Explicit state + goal-driven planning concepts
- Don't copy: GOAP implementation or Python API
- Key: Embabel解决complex planning，Arctra解决governance/testing

### 4. COMPATIBILITY VERDICT
🟡 **YELLOW** - 代码兼容，语义需要明确

### 5. ENGINE VERDICT
AgentExecutionEngine = **execution strategy component**，不是complete Agent

### 6. PROCESS MODEL
- Session = conversation boundary
- Process = task boundary (future)
- Execution = one engine call
- Tool Loop = engine internal

### 7. FUTURE MODEL
Arctra应支持：Tool Calling (M1) + Multi-turn (M2) + Workflow (M3?) + Planning (M4?)

### 8. DO WE CHANGE CODE NOW?
❌ **NO** - 无需代码变更

### 9. DOCUMENTATION CHANGES
✅ **YES** - 创建语义文档、guardrails、ADR

### 10. M3 IMPACT
推荐先做 Agent API，不直接做 Process/Workflow

### 11. ADR
✅ **YES** - ADR-003: Engine as Strategy

### 12. NEXT ACTION
**Create Architecture Semantics Documentation** (zero code changes)

---

**Reassessment Complete.** 等待审批。
