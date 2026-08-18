# Architecture Guardrails

**Version:** 1.0  
**Date:** 2026-08-18  
**Status:** Active  
**Source:** Post-M2 Execution Model Reassessment

---

## Purpose

本文档定义 Arctra 架构演进过程中**必须遵守的原则**，防止架构误入歧途。

这些原则基于 Post-M2 Execution Model Reassessment 的发现，旨在：
- 明确语义边界
- 防止概念混淆
- 保持架构演进空间
- 避免过早抽象

---

## Guardrail 1: Terminology Precision

### Rule

- ✅ **Multi-turn** = conversation continuity (ChatMemory, Session)
- ✅ **Multi-step** = structured task execution (Process, Workflow)
- ✅ **Tool Calling Loop** = engine implementation detail (Spring AI internal)
- ✅ **Session** = conversation boundary (user-initiated, long-lived)
- ✅ **Process** = task boundary (goal-initiated, complete when done)
- ✅ **Execution** = one engine.execute() call
- ❌ **DO NOT** mix these terms

### Why

M2 解决了 Multi-turn，但**不是** Multi-step。混用这些术语会导致：
- 误以为 multi-turn = multi-step
- 把 workflow state 塞进 ChatMemory
- 把 process lifecycle 塞进 Session

### Examples

**✅ CORRECT:**
```
"M2 adds multi-turn conversation support via ChatMemory."
"Future multi-step workflows will be orchestrated by Process Runtime."
"Tool Calling Loop is internal to SpringAiToolCallingEngine."
```

**❌ WRONG:**
```
"M2 adds multi-step support." (NO - M2 is multi-turn, not multi-step)
"Session manages process state." (NO - Session manages conversation, not process)
"Tool Loop is a process." (NO - Tool Loop is engine internal)
```

---

## Guardrail 2: Semantic Ownership

### Rule

- ✅ **AgentExecutionEngine** = execution strategy (how to execute)
- ✅ **AgentDefinition** = agent template (what to execute)
- ✅ **AgentRequest** = user input (content)
- ✅ **AgentExecutionContext** = execution environment (where/when/who)
- ✅ **AgentRuntime** = lifecycle management (orchestration)
- ❌ **Engine does NOT own** complete Agent semantics

### Why

如果 Engine = complete Agent，则：
- 无法支持多种 execution strategies
- 无法在 Engine 之上添加 Process orchestration
- 架构演进被锁死

### Architecture Model

```
User Agent API
    ↓
Agent Runtime (lifecycle, orchestration)
    ↓
[Process Runtime] (optional, for multi-step)
    ↓
AgentExecutionEngine (pluggable strategy)
    ├── ToolCallingEngine (current)
    ├── WorkflowEngine (future)
    └── PlanningEngine (future)
    ↓
Model / Tool / Code / Sub-Agent
```

**Key:** Engine 是 component，不是 top-level boundary。

### Examples

**✅ CORRECT:**
```
"SpringAiToolCallingEngine implements one execution strategy."
"Future WorkflowEngine will be another strategy."
"Agent Runtime orchestrates Engine invocations."
```

**❌ WRONG:**
```
"SpringAiToolCallingEngine is the Agent." (NO - it's a strategy)
"Engine manages Agent lifecycle." (NO - Runtime does)
"All execution must go through one Engine." (NO - pluggable)
```

---

## Guardrail 3: State Management

### Rule

- ✅ **Conversation state** → ChatMemory (M2)
- ✅ **Process state** → Process abstraction (when created)
- ✅ **Evidence** → per-execution collection (AgentResult)
- ❌ **DO NOT** put workflow state in ChatMemory
- ❌ **DO NOT** put process lifecycle in Session
- ❌ **DO NOT** accumulate Evidence across executions

### Why

不同类型的 state 有不同的 lifecycle 和 semantics：

| State Type | Lifecycle | Owner | Persistence |
|------------|-----------|-------|-------------|
| Conversation | Session | ChatMemory | Cross-execution |
| Process | Goal completion | Process Runtime | Checkpointed |
| Evidence | Execution | AgentResult | Per-execution |

混淆会导致：
- Session 变得臃肿
- 无法独立管理 process lifecycle
- Evidence 语义污染

### Examples

**✅ CORRECT:**
```java
// Conversation state
chatMemory.add(sessionId, userMessage);
chatMemory.add(sessionId, assistantMessage);

// Process state (future)
process.setState("logs", logEvidence);
process.setState("hypothesis", hypothesis);

// Evidence (per-execution)
return new AgentResult(content, evidences);
```

**❌ WRONG:**
```java
// Don't put process state in ChatMemory
chatMemory.add(sessionId, "step1_output", result); // NO

// Don't accumulate Evidence across turns
result.evidences().addAll(previousTurnEvidences); // NO

// Don't manage process in Session
session.setProcessState(...); // NO
```

---

## Guardrail 4: Abstraction Creation

### Rule

- ✅ **Create abstraction** ONLY when real consumer exists
- ✅ **Reference** EVOLUTION-GUIDE Creation Triggers
- ✅ **Wait for** at least one concrete scenario
- ❌ **DO NOT** create for "future might need"
- ❌ **DO NOT** create because "Embabel has it"

### Why

过早抽象 = 错误抽象。没有真实消费者时：
- 不知道真实需求形状
- 容易 over-engineer
- 维护成本高于收益

### Creation Triggers

| Abstraction | Create When |
|-------------|-------------|
| AgentProcess | Need step output → next step input, OR checkpoint/resume |
| Workflow | Need deterministic multi-step with branches |
| Goal/Planning | Need dynamic action selection based on state |
| Checkpoint | Need resume from intermediate state |
| SubAgent | Agent needs to call another agent |

**All currently:** ❌ NO real consumer

### Examples

**✅ CORRECT:**
```
"We need to inspect logs, THEN decide based on results whether to check 
deployment or database. This triggers AgentProcess + Workflow."
```

**❌ WRONG:**
```
"Embabel has Goal abstraction, so we should create it too." (NO)
"We might need multi-step in the future, let's add Process now." (NO)
"Let me create a Process framework just in case." (NO)
```

---

## Guardrail 5: Framework Positioning

### Rule

- ✅ **Spring AI** = implementation provider (models, tools, advisors)
- ✅ **Arctra** = governance + observability + testing harness
- ✅ **Tool** = execution primitive (not Agent abstraction)
- ❌ **DO NOT** reimplement Spring AI capabilities
- ❌ **DO NOT** wrap Spring AI just to rename
- ❌ **DO NOT** treat Tool as Agent-level abstraction

### Why

Arctra 不是：
- Spring AI 的镜像包装层
- 重新实现 LangChain
- Tool orchestration framework

Arctra 是：
- 基于 Spring AI，添加 governance/testing/observability
- 让 Agent 可测试、可恢复、可观测
- 统一不同 execution engines 的 semantics

### Examples

**✅ CORRECT:**
```java
// Use Spring AI ToolCallback directly
List<ToolCallback> tools = ...;
engine = new SpringAiToolCallingEngine(model, tools, memory);

// Add Arctra value: Evidence capture
tools.stream().map(t -> new EvidenceCapturingToolCallback(t, evidences))
```

**❌ WRONG:**
```java
// Don't wrap just to rename
interface ArctraTool extends ToolCallback {} // NO

// Don't reimplement tool calling
class ArctraToolCallingLoop { // NO - Spring AI has this
    // reimplementation
}

// Don't treat Tool as Agent
class ToolAgent implements Agent { // NO - Tool is primitive
    ToolCallback tool;
}
```

---

## Guardrail 6: API Design

### Rule

- ✅ **Agent API** must hide different execution strategies
- ✅ **Support** both sync (simple) and async (HITL) modes
- ✅ **API-first**, infrastructure-later (vertical slice)
- ❌ **DO NOT** expose Engine selection to user (unless explicit need)
- ❌ **DO NOT** leak execution strategy in API

### Why

用户不应该关心：
- 用的是 ToolCallingEngine 还是 WorkflowEngine
- 内部是 single-step 还是 multi-step
- 是否有 sub-agent

用户只关心：
- 我要完成什么任务
- 我要用哪个 agent
- 结果是什么

### Future API Vision

```java
// Simple sync
AgentResult result = arctra
    .agent("incident-investigator")
    .user("分析生产事故")
    .call();

// With session
AgentResult result = arctra
    .agent("incident-investigator")
    .session("incident-123")
    .user("那最可能的原因是什么？")
    .call();

// Async (for HITL)
AgentProcess process = arctra
    .agent("complex-workflow")
    .startAsync();
process.awaitCheckpoint();
process.provideInput(...);
AgentResult result = process.await();
```

**Key:** 同样的 API，可以隐藏 ToolCalling / Workflow / Planning。

### Examples

**✅ CORRECT:**
```java
// API hides strategy
arctra.agent("incident").call(); 
// → internally uses ToolCallingEngine

arctra.agent("approval-workflow").call();
// → internally uses WorkflowEngine
```

**❌ WRONG:**
```java
// Don't expose engine in API
arctra.agent("incident")
    .engine(ToolCallingEngine.class) // NO - leak implementation
    .call();

// Don't force user to choose
arctra.toolCallingAgent("incident").call(); // NO - leak strategy
arctra.workflowAgent("approval").call(); // NO
```

---

## Guardrail 7: Evidence Semantics

### Rule

- ✅ **Evidence** = framework-wide execution observation
- ✅ **Can come from:** tool, action, retrieval, agent, human, step
- ✅ **Semantics:** what was observed during execution
- ❌ **DO NOT** limit to tool execution only
- ❌ **DO NOT** make it tool-specific

### Why

Evidence 当前由 Tool 产生，但语义是"execution observation"，不是"tool result"。

未来可能来源：
- `tool:queryLogs`
- `action:inspectDeployment`
- `retrieval:runbook`
- `agent:securityAgent` (sub-agent)
- `human:approval`
- `step:correlateEvidence`

### Current vs Future

**Current (M1/M2):**
```java
// Only from tools
Evidence(source="tool:queryLogs", content="...")
Evidence(source="tool:getDeployment", content="...")
```

**Future:**
```java
// From multiple sources
Evidence(source="tool:queryLogs", content="...")
Evidence(source="action:inspectDeployment", content="...")
Evidence(source="retrieval:runbook", content="...")
Evidence(source="agent:securityAgent", content="...")
Evidence(source="human:approval", content="approved")
```

### Examples

**✅ CORRECT:**
```java
// Evidence abstraction stays general
record Evidence(String source, String content) {}

// Different producers
new Evidence("tool:queryLogs", result);
new Evidence("action:inspect", result); // future
new Evidence("agent:subAgent", result); // future
```

**❌ WRONG:**
```java
// Don't rename to tool-specific
record ToolEvidence(String toolName, String result) {} // NO

// Don't make tool-only interface
interface ToolEvidenceProducer {} // NO - too narrow
```

---

## Guardrail 8: Session vs Process

### Rule

- ✅ **One session** → many processes
- ✅ **Session** = user-initiated, long-lived, conversation boundary
- ✅ **Process** = goal-initiated, completes when done, task boundary
- ❌ **Session ≠ Process**
- ❌ **DO NOT** bind one session to one process

### Why

Session 和 Process 是不同的 lifecycle：

| | Session | Process |
|---|---------|---------|
| Initiator | User | Goal/Task |
| Lifetime | Long (many turns) | Short (goal completion) |
| State | Conversation history | Task state |
| Boundary | Conversation | Task execution |

**Relationship:**
```
Session S1:
  Turn 1 → Process P1 (task 1)
  Turn 2 → Process P2 (task 2)
  Turn 3 → Process P1 (continue task 1)
```

### Examples

**✅ CORRECT:**
```java
// One session, multiple processes
var session = AgentExecutionContext.withSession("debug-session");

// Process 1: Analyze incident
engine.execute(incidentAgent, request1, session);

// Process 2: Check deployment
engine.execute(deployAgent, request2, session);

// Process 1 again: Follow-up on incident
engine.execute(incidentAgent, request3, session);
```

**❌ WRONG:**
```java
// Don't tie session to process
class Session {
    Process currentProcess; // NO - one session can have many processes
}

// Don't make session = process
session.startProcess(); // NO - wrong semantic level
```

---

## Guardrail 9: Context Extension

### Rule

- ✅ **ExecutionContext** = execution-level cross-cutting concern
- ✅ **Good additions:** sessionId, processId, traceId, userId, tenantId
- ✅ **Must be:** orthogonal to business domain, consistent across engines
- ❌ **Bad additions:** incidentId, toolContext, modelParameters
- ❌ **DO NOT** make it a dumping ground

### Why

ExecutionContext 是"execution environment"，不是：
- Business domain state
- User input
- Agent template
- Execution result
- Engine-specific config

### Inclusion Criteria

**Add to ExecutionContext if:**
1. ✅ Execution-level concern (not domain-level)
2. ✅ Cross-cutting (applies to all engines)
3. ✅ Environment/identity (where/when/who, not what)

**Do NOT add if:**
1. ❌ Domain-specific (incidentId, orderId)
2. ❌ Engine-specific (toolContext, modelParams)
3. ❌ User input (belongs in AgentRequest)
4. ❌ Business logic (belongs in Process state)

### Examples

**✅ CORRECT:**
```java
record AgentExecutionContext(
    String sessionId,     // ✅ execution identity
    String processId,     // ✅ execution identity (future)
    String traceId,       // ✅ cross-cutting (observability)
    String userId,        // ✅ cross-cutting (multi-tenancy)
    String tenantId       // ✅ cross-cutting (isolation)
) {}
```

**❌ WRONG:**
```java
record AgentExecutionContext(
    String sessionId,
    String incidentId,    // ❌ domain-specific
    String toolContext,   // ❌ engine-specific
    Map<String,Object> modelParams, // ❌ engine config
    String businessState  // ❌ domain state
) {}
```

---

## Guardrail 10: Multi-Step Future

### Rule

- ✅ **Wait for real need** before choosing Workflow vs Planning
- ✅ **Both can coexist** (different scenarios, different strategies)
- ✅ **Implement via** Engine interface (pluggable)
- ❌ **DO NOT** force all scenarios into one multi-step model
- ❌ **DO NOT** assume Workflow必须先于Planning，或反之

### Why

Multi-step 有两种模式：

**Type A: Developer-Defined Workflow**
- Structure defined upfront
- Deterministic
- Good for: known processes, compliance, repeatability

**Type B: Goal-Driven Planning**
- Flow determined at runtime
- Dynamic, adaptive
- Good for: complex problems, exploration, adaptation

**Both are valid.** 不同场景需要不同模式。

### Strategy

```
M3: Wait for first real multi-step need
  → If need is deterministic process → Workflow
  → If need is adaptive planning → Planning
  → If both needed → support both (different engines)
```

### Examples

**✅ CORRECT:**
```java
// Workflow for deterministic process
WorkflowEngine workflowEngine = ...;
engine.execute(definition, request, context);

// Planning for adaptive scenarios
PlanningEngine planningEngine = ...;
engine.execute(definition, request, context);

// Same Engine interface, different strategies
```

**❌ WRONG:**
```java
// Don't force everything into Workflow
class AgentWorkflow { // NO - not all agents are workflows
    ...
}

// Don't assume Planning is always better
// "We should use Planning for everything because it's more advanced"
// NO - use the right tool for the job
```

---

## Summary Table

| # | Guardrail | Key Principle |
|---|-----------|---------------|
| 1 | **Terminology** | Multi-turn ≠ Multi-step, 不混用 |
| 2 | **Semantic Ownership** | Engine = strategy, not complete Agent |
| 3 | **State Management** | Conversation → ChatMemory, Process → Process state |
| 4 | **Abstraction Creation** | Only when real consumer exists |
| 5 | **Framework Positioning** | Spring AI = provider, Arctra = harness |
| 6 | **API Design** | Hide execution strategies from user |
| 7 | **Evidence Semantics** | Framework-wide observation, not tool-only |
| 8 | **Session vs Process** | One session → many processes |
| 9 | **Context Extension** | Cross-cutting only, not domain |
| 10 | **Multi-Step Future** | Workflow and Planning can coexist |

---

## Enforcement

**Code Review:**
- Reviewers should check against these guardrails
- Violation → discussion + alignment

**Architecture Decision:**
- Propose ADR when guardrail is unclear
- Update guardrails when new patterns emerge

**Evolution:**
- Guardrails evolve with project
- Update based on real learnings

---

**Architecture Guardrails v1.0** ✅

See also:
- [Post-M2 Execution Model Reassessment](POST-M2-EXECUTION-MODEL-REASSESSMENT.md)
- [Architecture Evolution Guide](EVOLUTION-GUIDE.md)
