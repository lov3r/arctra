# M4 Architecture Pressure Test

**Date:** 2026-08-18  
**Type:** Architecture Pressure Test + Phase Planning Gate  
**Status:** DRAFT - Awaiting Approval  
**Dependencies:** M3 COMPLETE

---

## Executive Summary

**Purpose:**  
通过真实高压力场景验证当前 execution model 的上限，找出第一个真正值得引入的新架构能力。

**Method:**  
Scenario-driven pressure test → Repeated pressure analysis → Creation trigger evaluation → M4 theme recommendation

**Key Principle:**  
不预设 M4 = Process/Workflow/Governance。从场景和架构压力反推。

---

## Part 1: Current Architecture Baseline

### 1.1 Current Execution Model

```
User
  ↓
Agent (stateless invocation handle)
  ↓
AgentRuntime (orchestration boundary)
  ↓
AgentExecutionEngine (execution strategy)
  ↓
SpringAiToolCallingEngine (Spring AI integration)
  ↓
Spring AI ChatClient
  ↓
Model ←→ Tool Calling Loop (Spring AI internal)
  ↓
AgentResult(content, evidences)
```

**Key Characteristics:**
- One `agent.execute()` call = one complete execution
- Tool Calling Loop inside engine (Spring AI managed)
- Evidence collected per-execution
- Session via ChatMemory (cross-execution conversation history)
- External code cannot see Tool Loop intermediate state

---

### 1.2 Current Capabilities

✅ **M1 (Tool Calling):**
- Spring AI-based tool calling
- Evidence capture
- AgentDefinition, AgentRequest, AgentResult

✅ **M2 (Multi-Turn):**
- Session continuity (AgentExecutionContext)
- ChatMemory integration
- Session isolation

✅ **M3 (Agent API):**
- Agent invocation handle
- Configuration vs Invocation separation
- Framework-neutral API

---

### 1.3 Current Limitations (Confirmed)

❌ **No framework-level multi-step process**  
❌ **No workflow/graph execution**  
❌ **No goal planning**  
❌ **No sub-agent orchestration**  
❌ **No HITL/checkpoint**  
❌ **No governance/policy layer**  
❌ **No agent registry**  
❌ **No model/tool registry**  
❌ **No persistent/distributed session**  
❌ **No context compaction (turn-safe)**

---

### 1.4 Key Semantic Boundaries

**Tool Calling Loop ≠ Multi-Step Process:**
- Tool Loop = Spring AI internal, model-driven
- Multi-Step = Framework-level, explicit steps

**Multi-Turn ≠ Multi-Step:**
- Multi-turn = conversation continuity (session)
- Multi-step = structured task execution (process)

**Session ≠ Process:**
- Session = conversation boundary (user-initiated)
- Process = task boundary (goal-initiated)

**AgentExecutionEngine:**
- Current: Execution strategy (NOT complete Agent boundary)
- One execute() call = one invocation (no pause/resume)

---

## Part 2: Pressure Scenarios

### Scenario A: Multi-Step Incident Remediation

**Business Goal:**  
分析生产事故并形成安全 remediation plan

**Explicit Steps:**

```
1. CollectLogs
   Input: incident info
   Output: LogEvidence

2. ClassifyFailure
   Input: LogEvidence
   Output: FailureHypothesis

3. InspectDeployment
   Input: FailureHypothesis
   Output: DeploymentEvidence

4. InspectSchema (conditional)
   Input: FailureHypothesis
   Output: SchemaEvidence (if DB-related)

5. CorrelateEvidence
   Input: LogEvidence, DeploymentEvidence, SchemaEvidence
   Output: RootCauseAnalysis

6. GenerateRemediationOptions
   Input: RootCauseAnalysis
   Output: RemediationOptions[]

7. RiskEvaluation
   Input: RemediationOptions
   Output: RankedOptions

8. FinalRecommendation
   Input: RankedOptions
   Output: SafeRemediation
```

**Key Characteristic:**  
Structured output of one step becomes structured input of next step.

**Cannot just:**  
"Stuff everything into one prompt and let model remember"

---

#### A.1 Current Architecture Simulation

**Option 1: Single execute() with Tool Calling Loop**

```java
agent.execute(new AgentRequest(
    "Analyze incident and generate safe remediation plan"
));
```

**How it works:**
- Model decides to call `queryLogs` tool
- Model analyzes logs, decides to call `getDeployment` tool
- Model analyzes deployment, generates plan
- One AgentResult returned

**Problems:**
- ❌ No explicit step boundary
- ❌ Cannot access intermediate step output (FailureHypothesis)
- ❌ Cannot retry specific step
- ❌ Cannot checkpoint between steps
- ❌ Step ordering is model-decided (not deterministic)
- ❌ Cannot express conditional step (if DB-related → inspect schema)
- ❌ Typed step output not captured

**Can Implement?** ⚠️ Partially  
**Complexity:** Medium (model prompt engineering)  
**Semantic Leakage:** High (business logic leaks into prompt)  
**Failure Boundary:** Too coarse (entire execution)  
**State Problem:** No intermediate state access

---

**Option 2: Custom Engine with Internal Steps**

```java
class RemediationEngine implements AgentExecutionEngine {
    @Override
    public AgentResult execute(...) {
        var logs = collectLogs();
        var hypothesis = classifyFailure(logs);
        var deployment = inspectDeployment(hypothesis);
        var rootCause = correlate(logs, deployment);
        var options = generateOptions(rootCause);
        var ranked = evaluate(options);
        return buildResult(ranked);
    }
}
```

**Problems:**
- ❌ Engine becomes procedural workflow
- ❌ No reusability (remediation-specific engine)
- ❌ No framework support (retry, checkpoint, etc.)
- ❌ Engine semantic violated (should be strategy, not workflow)

**Can Implement?** ✅ Yes  
**Complexity:** High (violates Engine semantic)  
**Semantic Leakage:** Critical (Engine = business workflow)  
**Architecture Pressure:** ⚠️ Engine boundary incorrect

---

**Option 3: Business Code Manual Orchestration**

```java
// Business code
var logs = logAgent.execute(...);
var hypothesis = classifyAgent.execute(..., logs.content());
var deployment = deployAgent.execute(..., hypothesis.content());
var rootCause = correlateAgent.execute(..., logs, deployment);
...
```

**Problems:**
- ❌ Business code becomes workflow orchestrator
- ❌ No framework support
- ❌ No retry/checkpoint
- ❌ Type-unsafe (passing content strings)
- ❌ Cannot persist intermediate state

**Can Implement?** ✅ Yes  
**Complexity:** Very High  
**Semantic Leakage:** Critical (business code = orchestration)  
**Architecture Pressure:** ⚠️ No orchestration layer

---

#### A.2 Architecture Pressure

**Repeated Pressure:**
1. **Explicit Step Boundary** needed
2. **Typed Step Output** needed
3. **Step Identity** (for retry) needed
4. **Intermediate State** access needed
5. **Deterministic Ordering** (not model-driven)

**Root Cause:**  
Current `agent.execute()` is too coarse-grained for multi-step tasks.

---

### Scenario B: Human Approval in High-Risk Operation

**Business Goal:**  
Agent 判断需要 rollback deployment，但需人工批准

**Required Flow:**

```
1. AnalyzeIncident
   Output: IncidentDiagnosis

2. DetermineAction
   Input: IncidentDiagnosis
   Output: RecommendedAction (e.g., ROLLBACK)

3. [PAUSE] EvaluateRisk
   Output: RiskAssessment → HIGH_RISK

4. [WAIT_FOR_APPROVAL]
   - State: WAITING
   - Duration: unknown (minutes to hours)
   
5. [HUMAN DECISION]
   → APPROVE or REJECT

6. [RESUME] ExecuteAction (if approved)
   Output: ActionResult
```

**Key Characteristic:**  
Execution must **pause** and **resume** after external event.

---

#### B.1 Current Architecture Simulation

**Option 1: Poll-based Workaround**

```java
// Initial execution
var result1 = agent.execute(new AgentRequest("Analyze and recommend"));

// Business code checks risk
if (isHighRisk(result1)) {
    // Wait for approval (how?)
    waitForApproval(); // ❌ Blocks thread
    
    // Resume (how?)
    var result2 = agent.execute(new AgentRequest("Execute approved action"));
}
```

**Problems:**
- ❌ No pause semantic in `execute()`
- ❌ Blocking wait (not durable)
- ❌ "Resume" is just another execute() (no state continuity)
- ❌ Application restart loses state

**Can Implement?** ⚠️ Awkwardly  
**Failure Boundary:** Cannot pause  
**State Problem:** No durable wait state

---

**Option 2: Database-based State Machine**

```java
// Initial
var result = agent.execute(...);
db.save(new Task(status=WAITING_APPROVAL, result=result));

// Hours later, different process
var task = db.load(taskId);
if (task.approved) {
    var finalResult = agent.execute(...); // ❌ Lost context
}
```

**Problems:**
- ❌ No framework support
- ❌ Business code manages state machine
- ❌ No type-safe state
- ❌ Context lost between executions

**Can Implement?** ✅ Yes  
**Complexity:** Very High  
**Semantic Leakage:** Critical  
**Architecture Pressure:** ⚠️ No lifecycle/state management

---

#### B.2 Architecture Pressure

**Repeated Pressure:**
1. **Pause/Resume Lifecycle** needed
2. **Durable State** needed
3. **Async Execution** needed
4. **External Event Handling** needed
5. **State Persistence** needed

**Root Cause:**  
Current `agent.execute()` is synchronous, stateless, cannot pause.

---

### Scenario C: Partial Failure + Retry

**Business Goal:**  
执行多步骤任务，某一步失败后只重试该步骤

**Execution:**

```
Step A: CollectLogs ✅ (success)
Step B: AnalyzeLogs ✅ (success)
Step C: FetchDeployment ❌ (timeout)
Step D: CorrelateEvidence ⏸️ (not executed)
```

**User Requirement:**  
Retry **only** Step C, continue to Step D.

**NOT acceptable:**  
Retry entire `agent.execute()` from Step A.

---

#### C.1 Current Architecture Simulation

**Option 1: Retry entire execute()**

```java
try {
    agent.execute(...);
} catch (Exception e) {
    // Retry entire execution
    agent.execute(...); // ❌ Repeats Step A, B
}
```

**Problems:**
- ❌ Failure boundary too coarse
- ❌ Wasted work (re-execute successful steps)
- ❌ Cannot target specific step

**Can Implement?** ✅ Yes  
**Complexity:** Low  
**Semantic Leakage:** None  
**Architecture Pressure:** ⚠️ Coarse failure boundary

---

**Option 2: Manual Step Isolation**

```java
var logs = stepA(); // ❌ No framework concept of "step"
var analysis = stepB(logs);
try {
    var deployment = stepC(analysis);
} catch (Exception e) {
    deployment = stepC(analysis); // Retry
}
var result = stepD(logs, analysis, deployment);
```

**Problems:**
- ❌ No framework step abstraction
- ❌ Business code = orchestrator
- ❌ No automatic retry/backoff
- ❌ No state persistence

**Architecture Pressure:** ⚠️ No execution unit abstraction

---

#### C.2 Architecture Pressure

**Repeated Pressure:**
1. **Step Identity** needed (to target retry)
2. **Step Status** needed (success/failure)
3. **Partial State** needed (Step A, B outputs preserved)
4. **Retry Boundary** = step (not entire execution)

**Root Cause:**  
Current execution is monolithic (no sub-units).

---

### Scenario D: Long-Lived Resume

**Business Goal:**  
Security investigation spanning multiple days

**Timeline:**

```
Day 1:
  Step 1: CollectInitialEvidence ✅
  Step 2: InspectDeployment ✅
  Step 3: TriggerExternalScan ✅
  → WAITING_FOR_SCAN_RESULT

[Application Restart]

Day 2:
  [Scan result arrives via webhook/API]
  → RESUME from Step 4
  Step 4: AnalyzeScanResult
  Step 5: ProduceFinalReport
```

**Key Characteristic:**  
Application restart should not lose task state.

---

#### D.1 Current Architecture Simulation

**Option 1: ChatMemory as State**

```java
// Day 1
agent.execute(
    new AgentRequest("Start investigation"),
    AgentExecutionContext.withSession("security-123")
);

// Day 2 (after restart)
agent.execute(
    new AgentRequest("Scan result: ..."),
    AgentExecutionContext.withSession("security-123")
);
```

**Problems:**
- ❌ ChatMemory only stores conversation (not task state)
- ❌ Cannot distinguish "where am I in the process?"
- ❌ Execution state ≠ Conversation state
- ❌ No semantic difference between "continue chat" vs "resume task"

**Can Implement?** ⚠️ Partially  
**Semantic Leakage:** High (mixing conversation and process state)  
**State Problem:** Wrong abstraction

---

**Option 2: Database-based Task State**

```java
// Day 1
var task = new Task(status=WAITING_SCAN);
db.save(task);

// Day 2
var task = db.load("security-123");
if (task.status == WAITING_SCAN && scanArrived()) {
    agent.execute(...); // ❌ How to resume?
}
```

**Problems:**
- ❌ No framework support
- ❌ Business code manages lifecycle
- ❌ No type-safe state
- ❌ Resume = new execute() (not true resume)

**Architecture Pressure:** ⚠️ No durable process state

---

#### D.2 Architecture Pressure

**Repeated Pressure:**
1. **Durable State** (survives restart)
2. **Process Lifecycle** (created, waiting, resumed, completed)
3. **State Persistence** (not just conversation)
4. **Resume Semantic** (not just new execute())

**Root Cause:**  
Current execution is transient (no lifecycle beyond one call).

---

## Part 3: Cross-Scenario Pressure Analysis

### 3.1 Repeated Pressures

| Pressure | Scenario A | Scenario B | Scenario C | Scenario D | Count |
|----------|-----------|-----------|-----------|-----------|-------|
| **Explicit Step Boundary** | ✅ | ✅ | ✅ | ✅ | 4/4 |
| **Process Lifecycle** | | ✅ | | ✅ | 2/4 |
| **Durable State** | | ✅ | ✅ | ✅ | 3/4 |
| **Pause/Resume** | | ✅ | | ✅ | 2/4 |
| **Step Identity** | ✅ | | ✅ | | 2/4 |
| **Typed Step Output** | ✅ | | | | 1/4 |
| **Retry Boundary** | | | ✅ | | 1/4 |

**Strongest Pressures:**
1. **Explicit Step Boundary** (4/4) - Universal need
2. **Durable State** (3/4) - High need
3. **Process Lifecycle** (2/4) - Medium need
4. **Pause/Resume** (2/4) - Medium need

---

### 3.2 Root Cause Analysis

**Current `agent.execute()` limitations:**
1. **Granularity:** Too coarse (one call = entire task)
2. **Lifecycle:** Synchronous only (no pause/resume)
3. **State:** Transient (no durability)
4. **Structure:** Opaque (no sub-units visible)

**What's missing:**
- Execution unit smaller than entire `execute()`
- Lifecycle beyond synchronous call/return
- State beyond AgentResult
- Observability into execution structure

---

## Part 4: Candidate Architecture Abstractions

### Candidate A: Agent Process

**Semantic:**  
"一次为了完成某个 goal/request 而存在的、具有独立 lifecycle 的 Agent task"

**Lifecycle:**
```
CREATED → RUNNING → [WAITING] → RUNNING → COMPLETED/FAILED
```

**Capabilities:**
- Process identity (ID)
- Lifecycle states
- Durable state
- Pause/resume
- Start/complete events

**Addresses Pressures:**
- ✅ Process Lifecycle (2/4 scenarios)
- ✅ Durable State (3/4 scenarios)
- ✅ Pause/Resume (2/4 scenarios)

**Does NOT address:**
- ❌ Explicit Step Boundary (still need Step abstraction)
- ❌ Typed Step Output

---

### Candidate B: Execution Step

**Semantic:**  
"Process 内的一个执行单元，有明确的 input/output 和 status"

**Capabilities:**
- Step identity
- Step status (pending/running/success/failure)
- Typed input/output
- Retry boundary

**Addresses Pressures:**
- ✅ Explicit Step Boundary (4/4 scenarios)
- ✅ Step Identity (2/4 scenarios)
- ✅ Typed Step Output (1/4 scenarios)
- ✅ Retry Boundary (1/4 scenarios)

**Requires:**
- Process abstraction (Step 属于 Process)

---

### Candidate C: Workflow

**Semantic:**  
"Developer-defined execution structure (ordering, branching)"

**Capabilities:**
- Deterministic ordering
- Conditional branching
- Explicit step definition

**Addresses Pressures:**
- ✅ Explicit Step Boundary
- ⚠️ Deterministic vs model-driven (design choice)

**Risk:**
- May be too heavyweight for current scenarios
- Graph/DSL complexity

**Verdict:** ⚠️ Premature (can defer to M5+)

---

### Candidate D: Dynamic Planning

**Semantic:**  
"Runtime goal-driven action selection (GOAP/Embabel-style)"

**Addresses Pressures:**
- ❌ None of current scenarios need dynamic planning

**Verdict:** ❌ Not triggered

---

### Candidate E: Checkpoint

**Semantic:**  
"Durable intermediate state snapshot"

**Capabilities:**
- Save state
- Resume from checkpoint

**Addresses Pressures:**
- ✅ Durable State (3/4 scenarios)
- ✅ Pause/Resume (2/4 scenarios)

**Relationship:**
- Checkpoint is mechanism, not semantic boundary
- Requires Process + Step first

**Verdict:** ⚠️ Supporting capability (not primary abstraction)

---

### Candidate F: HITL

**Semantic:**  
"Human-in-the-loop approval/intervention"

**Addresses Pressures:**
- ✅ Pause/Resume (Scenario B)

**Relationship:**
- HITL is use case, not core abstraction
- Requires Process pause/resume first

**Verdict:** ⚠️ Use case (not abstraction)

---

### Candidate G: Persistent Session

**Semantic:**  
"Durable conversation history"

**Addresses Pressures:**
- ❌ Wrong abstraction (conversation ≠ process state)

**Verdict:** ❌ Does not solve process pressures

---

### Candidate H: Governance

**Semantic:**  
"Permission/risk/policy layer"

**Addresses Pressures:**
- ❌ None of current scenarios

**Verdict:** ❌ Not triggered

---

## Part 5: Creation Trigger Analysis

### Process + Step: Creation Trigger Gate

**Current Consumer?**  
✅ YES - 4 pressure scenarios (A, B, C, D)

**Current Scenario?**  
✅ YES - Incident Remediation, HITL, Partial Retry, Long-Lived

**Without It, What Cannot Be Expressed Cleanly?**  
✅ Multi-step with explicit boundaries, pause/resume, partial retry

**Can Existing Spring AI Capability Solve It?**  
❌ NO - Spring AI only provides Tool Calling Loop (model-driven, opaque)

**Can It Be Local Implementation Detail?**  
❌ NO - Affects public API (need process handle, step visibility)

**Does It Need Public API?**  
✅ YES - User needs to start/resume process, access step state

**Does It Need Core Abstraction?**  
✅ YES - Process = core Arctra semantic (not implementation detail)

**Creation Trigger Satisfied?**  
✅ **YES**

---

### Workflow: Creation Trigger Gate

**Current Consumer?**  
⚠️ MAYBE - Scenario A wants deterministic ordering

**Can Tool Calling Loop + Process Handle It?**  
✅ Possibly - Process with implicit step ordering may suffice

**Why create Workflow now?**  
❌ No clear need for graph/DSL yet

**Creation Trigger Satisfied?**  
❌ **NO** (defer to M5+)

---

### Dynamic Planning: Creation Trigger Gate

**Current Consumer?**  
❌ NO

**Creation Trigger Satisfied?**  
❌ **NO**

---

### Persistent Session: Creation Trigger Gate

**Solves Process Pressures?**  
❌ NO (wrong abstraction)

**Creation Trigger Satisfied?**  
❌ **NO** (separate concern)

---

### Governance: Creation Trigger Gate

**Current Consumer?**  
❌ NO

**Creation Trigger Satisfied?**  
❌ **NO**

---

## Part 6: AgentExecutionEngine Review

**Current Contract:**
```java
AgentResult execute(
    AgentDefinition definition,
    AgentRequest request,
    AgentExecutionContext context
);
```

**Compatibility with Process:**

**Option 1: Engine = Process Executor**
```
AgentProcess execute(...) {
    // Engine creates and runs entire process
}
```
❌ Problem: Engine semantic violated

**Option 2: Engine = Step Executor**
```
StepResult executeStep(...) {
    // Engine executes one step
}
```
⚠️ Problem: Breaking change

**Option 3: Engine = Strategy (Current)**
```
Process calls Engine for certain steps
```
✅ Clean: Engine remains strategy

**Verdict:** ✅ **KEEP** current Engine contract

**Future:**
```
AgentProcess (M4)
  → Step execution
    → AgentExecutionEngine (current)
      → SpringAiToolCallingEngine
```

Engine stays as execution strategy, called by Process.

---

## Part 7: Agent API Compatibility

**Current:**
```java
agent.execute(request, context) → AgentResult
```

**With Process:**

**Option 1: execute() starts process, blocks until complete**
```java
AgentResult result = agent.execute(...); // blocks
```
✅ Backward compatible (simple cases)
❌ Cannot pause/resume

**Option 2: New async API**
```java
AgentProcess process = agent.start(...);
// Later
process.resume(...);
```
✅ New capability
✅ Backward compatible (keep execute())

**Verdict:** ✅ **COMPATIBLE**

**Recommendation:**
- Keep `agent.execute()` for simple synchronous cases
- Add `agent.start()` for async/long-lived cases (M4)

---

## Part 8: Arctra Ownership Boundary

**What Arctra Should Own:**
- ✅ **Agent invocation API** (M3)
- ✅ **AgentProcess lifecycle** (M4 candidate)
- ✅ **Step semantic** (M4 candidate)
- ✅ **Evidence** (M1)
- ✅ **Execution context** (M2)
- ⚠️ **Governance** (future)
- ⚠️ **Checkpoint** (future)

**What Spring AI Owns:**
- ✅ **Model integration**
- ✅ **Tool Calling Loop**
- ✅ **ChatMemory**
- ✅ **Advisors**

**Boundary:**
- Arctra = Agent/Process/Step/Evidence semantics
- Spring AI = Model/Tool execution primitives

**Verdict:** ✅ Clear separation maintained

---

## Part 9: External Framework Research

### Embabel (GOAP-style)

**Key Concepts:**
- AgentProcess (lifecycle)
- Action (reusable operations)
- Goal (completion condition)
- Planner (dynamic action selection)
- World State

**Relevant for M4:**
- ✅ AgentProcess lifecycle
- ❌ Dynamic planning (not needed yet)

---

### LangGraph

**Key Concepts:**
- Graph (nodes + edges)
- State
- Checkpoint
- Interrupt (pause)
- Resume

**Relevant for M4:**
- ✅ Checkpoint/Resume pattern
- ❌ Graph DSL (too heavyweight)

---

### AgentScope

**Key Concepts:**
- Agent lifecycle
- Pipeline (workflow)
- Msg (state)

**Relevant for M4:**
- ✅ Lifecycle management
- ⚠️ Pipeline (may defer)

---

### Spring AI

**Current (2.0):**
- Advisors
- ChatMemory
- Tool Calling

**Future (checking):**
- Workflow support?
- Session API?

**Verdict:** Spring AI focuses on execution primitives, not process semantics.

---

## Part 10: M4 Candidate Themes

### Theme A: Agent Process Foundation ⭐ RECOMMENDED

**Solves:**
- Scenario A (multi-step)
- Scenario B (pause/resume)
- Scenario C (partial retry)
- Scenario D (long-lived)

**Cross-Scenario Pressure:** 4/4

**Minimal Abstractions:**
1. **AgentProcess** (lifecycle + state)
2. **ExecutionStep** (explicit sub-unit)

**Public API Impact:**
- Add `agent.start()` → `AgentProcess`
- Add `AgentProcess.resume()`
- Keep `agent.execute()` for simple cases

**Framework Independence:** ✅ YES

**Implementation Risk:** MEDIUM

**Premature Risk:** ✅ LOW (4 scenarios, clear trigger)

**Vertical Slice:** Incident Remediation with HITL

---

### Theme B: Durable Session Foundation

**Solves:**
- Conversation persistence only
- ❌ Does NOT solve process pressures

**Verdict:** ❌ Wrong abstraction for current pressures

---

### Theme C: Governance Foundation

**Solves:**
- ❌ No current scenario

**Verdict:** ❌ No creation trigger

---

### Theme D: NO NEW ARCHITECTURE YET

**Alternative Verdict:**  
Wait for more real-world scenarios before adding abstractions.

**Verdict:** ❌ Rejected (4 scenarios show clear pressure)

---

## Part 11: Recommended M4 Theme

# **M4: Agent Process Foundation**

**Why Now:**
1. **4/4 scenarios** need explicit step boundary
2. **3/4 scenarios** need durable state
3. **2/4 scenarios** need pause/resume
4. **Creation trigger met** (real consumer, clear pressure)

**Why NOT Alternatives:**
- Workflow: Too heavyweight (can defer)
- Planning: Not needed (model-driven sufficient)
- Session: Wrong abstraction (conversation ≠ process)
- Governance: No scenario

**Minimal Scope:**
- AgentProcess (lifecycle)
- ExecutionStep (boundary)
- NO: Workflow DSL, Dynamic planning, Full governance

---

## Part 12: M4 Reference Scenario

**Incident Remediation with Human Approval**

**Flow:**
```
Process: IncidentRemediation

Step 1: CollectLogs
  → LogEvidence

Step 2: AnalyzeIncident
  Input: LogEvidence
  → IncidentDiagnosis

Step 3: DetermineRemediation
  Input: IncidentDiagnosis
  → RemediationPlan (e.g., ROLLBACK)

Step 4: EvaluateRisk
  Input: RemediationPlan
  → RiskLevel (HIGH)

[PAUSE] Step 5: WaitForApproval
  State: WAITING_FOR_APPROVAL
  
[HUMAN APPROVAL]

[RESUME] Step 6: ExecuteRemediation
  Input: RemediationPlan (approved)
  → ActionResult

Step 7: VerifyRemediation
  Input: ActionResult
  → Final Report
```

**Validates:**
- Multi-step execution
- Typed step output
- Pause/resume
- HITL integration
- Durable state

---

## Part 13: Minimal New Abstractions

**M4 will introduce ONLY:**

### 1. AgentProcess

```java
public interface AgentProcess {
    String id();
    ProcessStatus status();
    AgentResult result(); // when completed
    // Future: resume(), cancel()
}

enum ProcessStatus {
    CREATED, RUNNING, WAITING, COMPLETED, FAILED
}
```

### 2. ExecutionStep

**Concept only (not necessarily public type M4):**
- Step identity
- Step status
- Step input/output

**May be internal to Process implementation initially.**

---

## Part 14: Proposed M4 Tasks

### M4-T1: Process Architecture Contract Gate

**Goal:** Define Process semantic, API contract  
**Deliverables:** Contract gate document  
**Effort:** 3-5 days

### M4-T2: Minimal AgentProcess Implementation

**Goal:** AgentProcess + basic lifecycle  
**Deliverables:** AgentProcess interface, simple impl  
**Effort:** 5-7 days

### M4-T3: Incident Remediation Vertical Slice

**Goal:** Reference scenario with HITL  
**Deliverables:** Working example  
**Effort:** 5-7 days

### M4-T4: M4 Phase Closure

**Goal:** Documentation, reconciliation  
**Deliverables:** Closure report  
**Effort:** 2-3 days

**Total:** 15-22 days

---

## Part 15: Explicit Non-Goals

**M4 will NOT:**
- ❌ Workflow DSL
- ❌ Graph execution
- ❌ Dynamic planning
- ❌ Multi-agent coordination
- ❌ Full governance
- ❌ Production persistence (may use in-memory)
- ❌ Distributed execution
- ❌ Real rollback execution
- ❌ Agent/Model/Tool registry

**M4 Focus:** Minimal Process + Step foundation

---

## Part 16: Roadmap Impact

**UP:**
- Agent Process Foundation (M4)
- HITL (M5, after Process)
- Checkpoint (M5, after Process)

**UNCHANGED:**
- Persistent Session (separate track)
- Tool Abstraction (wait for multiple engines)

**DOWN:**
- Workflow DSL (defer to M6+)
- Dynamic Planning (defer to M6+)
- Governance (defer to M5+)

---

## Part 17: ADR Requirement

**M4 will require:**

**ADR-005: AgentProcess as Lifecycle Abstraction**
- Process = task lifecycle (not execution strategy)
- Process ≠ Workflow
- Process contains Steps
- Process pause/resume semantic

---

## Part 18: Approval Required

**Seeking Approval For:**

✅ **M4 Theme: Agent Process Foundation**

✅ **Reference Scenario:** Incident Remediation with HITL

✅ **Minimal Abstractions:**
- AgentProcess (lifecycle)
- ExecutionStep (concept)

✅ **Explicit Non-Goals:**
- No Workflow DSL
- No Dynamic Planning
- No Full Governance

✅ **Proposed Tasks:**
- M4-T1: Contract Gate
- M4-T2: Implementation
- M4-T3: Vertical Slice
- M4-T4: Closure

---

**M4 Architecture Pressure Test Complete.**  
**Awaiting Approval to proceed to M4-T1.**
