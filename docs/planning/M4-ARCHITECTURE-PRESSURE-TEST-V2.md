# M4 Architecture Pressure Test V2 — Governance Challenge

**Date:** 2026-08-18  
**Type:** Architecture Pressure Test Revision  
**Status:** DRAFT - Awaiting Approval  
**Dependencies:** M4 Pressure Test V1 Complete

---

## Executive Summary

**Purpose:**  
验证 M4 V1 是否因为"没有把 Governance 场景纳入压力测试"而过早得出 Governance 应 defer 的结论。

**Method:**  
新增 Governance 场景 → 重新分析 Tool invocation boundary → 评估 Governance vs Process 正交性 → 修正 M4 scope

**Key Finding:**  
✅ V1 Process foundation 结论保留  
✅ Governance interception 确实需要，但不依赖 Process  
✅ Tool abstraction creation trigger 仍未满足  
⚠️ agent.start() API 需要重新审查

---

## Part 1: Why V2 Exists

### 1.1 V1 Potential Bias

**V1 Scenario B (Human Approval):**
```
Analyze → DetermineAction → EvaluateRisk → 
[WAIT_FOR_APPROVAL] → Human Decision → ExecuteAction
```

**V1 Interpretation:**  
将 approval 解释为 **Process Step**。

**Bias:**  
可能错误地将 **Governance concern** (是否允许执行) 和 **Process concern** (如何 pause/resume) 混为一谈。

---

### 1.2 Unanswered Question

**Why must EvaluateRisk/Approval be a business Process Step?**

**Alternative possibility:**
```
Agent/Model decides to invoke dangerous Tool
    ↓
Tool Invocation Boundary (Governance interception)
    ↓
Governance Decision
    ├── ALLOW → Tool executes
    ├── DENY → Tool rejected (execution continues)
    └── REQUIRE_APPROVAL → Process suspension needed
```

**Key Distinction:**
- **Governance:** "Is this action allowed?"
- **Process:** "How does execution pause and resume?"

**V2 Goal:** Verify if these are orthogonal concerns.

---

## Part 2: V1 Conclusions Retained

**Following V1 conclusions are RETAINED pending V2 analysis:**

✅ **AgentProcess creation trigger met** (Scenarios A, C, D)  
✅ **ExecutionStep creation trigger met** (Scenarios A, C, D)  
✅ **Workflow DSL still premature**  
✅ **Dynamic Planning not needed**  
✅ **AgentExecutionEngine contract should remain unchanged**

**V2 will NOT attempt to disprove Process foundation.**

---

## Part 3: V1 Assumptions Challenged

### 3.1 Assumption 1: "Governance must wait for Process"

**V1 implied:** Governance 应在 Multi-Step/Process 完成后。

**Challenge:** ALLOW/DENY 可能不依赖 Process lifecycle。

---

### 3.2 Assumption 2: "Human Approval = Process Step"

**V1 treated:** Approval as inherent part of business process.

**Challenge:** Approval 可能是 Governance decision outcome，Process 只提供 suspension mechanism。

---

### 3.3 Assumption 3: "Tool abstraction not needed"

**V1 deferred:** Tool abstraction 因为只有 SpringAiToolCallingEngine。

**Challenge:** Governance metadata 可能需要 Arctra-owned Tool semantic。

---

## Part 4: Governance Scenario

### Scenario G: Tool Invocation Governance

**Business Context:**  
Incident Remediation Agent 有三个 Tool：

**1. queryLogs**
- Operation: Read-only
- Risk: LOW
- Expected: ALLOW (auto-execute)

**2. restartService**
- Operation: Mutating
- Risk: MEDIUM
- Expected: Policy-based (ALLOW or DENY)

**3. rollbackDeployment**
- Operation: Destructive, high impact
- Risk: HIGH
- Expected: REQUIRE_APPROVAL (human decision)

---

### 4.1 Key Characteristic

**Governance decision happens at Tool invocation boundary:**
- Agent/Model decides "I want to call rollbackDeployment"
- Before actual tool execution
- Governance intercepts and evaluates
- Decision: ALLOW / DENY / REQUIRE_APPROVAL

**NOT a business process step.**

---

### 4.2 Current Architecture Baseline

**Tool Invocation Flow (M3):**
```
Agent.execute()
    ↓
AgentRuntime
    ↓
AgentExecutionEngine
    ↓
SpringAiToolCallingEngine
    ├── Wraps tools with EvidenceCapturingToolCallback
    ├── Builds ChatClient with tools
    └── Spring AI Tool Calling Loop
        ↓
    Model decides: "call rollbackDeployment"
        ↓
    Spring AI invokes ToolCallback
        ↓
    [WHERE IS GOVERNANCE INTERCEPTION?]
        ↓
    Tool.call() executes
        ↓
    Evidence captured
```

**Question:** 当前架构的自然 governance interception point 在哪里？

---

## Part 5: ALLOW Simulation

### 5.1 Scenario: queryLogs (Low Risk)

**Flow:**
```
Model: "I need logs"
    ↓
Spring AI: invoke queryLogs ToolCallback
    ↓
[Governance Check]
    Decision: ALLOW (read-only, low risk)
    ↓
queryLogs.call() executes
    ↓
Tool Calling Loop continues
```

**Current Architecture Simulation:**

**Option 1: Governance-Aware ToolCallback Wrapper**
```java
class GovernanceToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final GovernancePolicy policy;
    
    @Override
    public String call(String input) {
        var decision = policy.evaluate(delegate.getName(), input);
        
        if (decision == ALLOW) {
            return delegate.call(input);
        }
        
        if (decision == DENY) {
            return "Tool invocation denied by policy";
        }
        
        if (decision == REQUIRE_APPROVAL) {
            // ❓ What happens here?
            throw new ApprovalRequiredException(...);
        }
    }
}
```

**Analysis:**
- ✅ ALLOW case works (simple delegation)
- ✅ No Process needed
- ✅ No AgentExecutionEngine change needed
- ✅ Spring AI ToolCallback remains execution primitive

---

### 5.2 Does ALLOW Need Process?

**Answer:** ❌ **NO**

**Reason:**  
ALLOW 只是 passthrough，Tool 正常执行，Tool Calling Loop 继续。

**Implication:**  
Governance ALLOW/DENY 不依赖 AgentProcess。

---

## Part 6: DENY Simulation

### 6.1 Scenario: restartService (Policy Denied)

**Flow:**
```
Model: "I will restart service"
    ↓
Spring AI: invoke restartService ToolCallback
    ↓
[Governance Check]
    Decision: DENY (current environment policy)
    ↓
Tool does NOT execute
    ↓
Return structured denial to Model
    ↓
Model receives: "Tool invocation denied: insufficient permission"
    ↓
Tool Calling Loop continues (Model may try alternative)
```

**Current Architecture Simulation:**

**GovernanceToolCallback (continued):**
```java
if (decision == DENY) {
    // Return structured error to Model
    return JSON.stringify(new ToolResult(
        status: "DENIED",
        reason: "Insufficient permission for restartService",
        policy: "production-safety-policy"
    ));
}
```

**Analysis:**
- ✅ DENY case works (return error, no execution)
- ✅ Spring AI Tool Calling Loop continues
- ✅ Model receives denial, can adjust strategy
- ✅ No Process suspension needed

---

### 6.2 Does DENY Need Process?

**Answer:** ❌ **NO**

**Reason:**  
DENY 是同步决策，返回错误，Tool Calling Loop 继续。No pause/resume.

**Implication:**  
Governance DENY 不依赖 AgentProcess。

---

## Part 7: REQUIRE_APPROVAL Simulation

### 7.1 Scenario: rollbackDeployment (High Risk)

**Flow:**
```
Model: "I will rollback deployment"
    ↓
Spring AI: invoke rollbackDeployment ToolCallback
    ↓
[Governance Check]
    Decision: REQUIRE_APPROVAL
    ↓
Tool must NOT execute now
    ↓
❓ What happens to current agent.execute()?
    ↓
❓ Can Spring AI Tool Calling Loop pause?
    ↓
❓ Can current execution return "SUSPENDED" state?
    ↓
[WAIT FOR APPROVAL]
    Duration: unknown (minutes to hours)
    ↓
[HUMAN DECISION]
    → APPROVE or REJECT
    ↓
[RESUME EXECUTION]
    If APPROVE: Tool executes, Loop continues
    If REJECT: Tool denied, Loop continues
```

---

### 7.2 Current Architecture Breaks Here

**Problem 1: Tool Calling Loop Cannot Pause**

Spring AI Tool Calling Loop 是同步的、内部的、不可中断的。

**Problem 2: ToolCallback.call() is Synchronous**
```java
String call(String input);  // Must return immediately
```

不能在 call() 内部 wait for approval。

**Problem 3: agent.execute() is Synchronous**
```java
AgentResult execute(...);  // Blocks until complete
```

不能返回 "SUSPENDED" 状态。

---

### 7.3 Where Does Architecture Pressure Come From?

**Governance Decision:** REQUIRE_APPROVAL (synchronous)

**Execution Reality:** Cannot pause Spring AI Tool Loop

**Missing Capability:** Execution suspension/resumption

**Root Cause:**  
Governance 可以同步决策 REQUIRE_APPROVAL，  
但 **execution suspension** 需要 Process lifecycle。

---

### 7.4 Does REQUIRE_APPROVAL Need Process?

**Answer:** ✅ **YES, but not for Governance decision**

**Clarification:**
- **Governance decision** (REQUIRE_APPROVAL) = synchronous, instant
- **Execution suspension** (wait for approval) = asynchronous, durable

**Correct Separation:**
```
Governance: Decides "approval required"
Process: Provides suspension/resumption mechanism
```

**Implication:**  
Governance 和 Process 是 **orthogonal concerns**。

---

## Part 8: Governance vs Process Boundary

### 8.1 Semantic Ownership

**Governance Owns:**
- "Is this action allowed?" (permission)
- "What risk level?" (evaluation)
- "Does it need approval?" (policy)
- Decision: ALLOW / DENY / REQUIRE_APPROVAL

**Process Owns:**
- "How does execution pause?" (lifecycle)
- "How does execution resume?" (continuation)
- "How is state preserved?" (durability)
- States: RUNNING / WAITING / COMPLETED

---

### 8.2 Relationship

**Governance does NOT depend on Process:**
- ALLOW / DENY work without Process
- REQUIRE_APPROVAL **decision** does not need Process

**Process provides mechanism for Governance outcome:**
- When Governance says REQUIRE_APPROVAL
- Process provides suspension capability
- Process becomes **consumer** of Governance decision

**Key:** Governance = first consumer of Process pause/resume.

---

### 8.3 Correct Model (Model C)

```
AgentProcess (lifecycle)
    ↓
ExecutionStep / Engine
    ↓
Tool Invocation Boundary
    ↓
Governance (interception)
    ├── ALLOW → Tool executes
    ├── DENY → Tool rejected, execution continues
    └── REQUIRE_APPROVAL
              ↓
          Process.suspend() (pause execution)
              ↓
          [Human approval event]
              ↓
          Process.resume() (continue execution)
              ↓
          Tool executes or denied based on approval
```

**Characteristics:**
- ✅ Governance orthogonal to Process
- ✅ Process provides suspension for Governance outcome
- ✅ Simple tool-calling agents can use Governance ALLOW/DENY
- ✅ Multi-step processes can use full Governance + suspension

---

## Part 9: Process Creation Trigger Re-evaluation

**Question:** 即使完全移除 Governance/HITL，Process 是否仍需要？

**Re-examine Scenarios:**

**Scenario A (Multi-Step):**
- ✅ Needs explicit step boundary
- ✅ Needs typed step output
- ✅ Needs deterministic ordering
- **Verdict:** Process needed (independent of Governance)

**Scenario C (Partial Retry):**
- ✅ Needs step identity
- ✅ Needs retry boundary
- **Verdict:** Process needed (independent of Governance)

**Scenario D (Long-Lived):**
- ✅ Needs durable state
- ✅ Needs process lifecycle
- **Verdict:** Process needed (independent of Governance)

---

### 9.1 Conclusion

✅ **AgentProcess creation trigger REMAINS SATISFIED**

**Reason:**  
Scenarios A, C, D independently证明 Process 必要性，与 Governance 无关。

**Implication:**  
即使不做 Governance，M4 Process Foundation 仍成立。

---

## Part 10: Governance Creation Trigger

### 10.1 Creation Trigger Gate

**1. Current Consumer?**  
✅ YES - Scenario G (Tool governance)

**2. Current Scenario?**  
✅ YES - High-risk tool invocation control

**3. Without It, What Cannot Be Expressed Cleanly?**  
✅ Tool permission/risk/approval without business code orchestration

**4. Can Existing Spring AI Capability Solve It?**  
⚠️ PARTIALLY - ToolCallback can be wrapped, but no governance semantic

**5. Can It Be Local Implementation Detail?**  
❌ NO - Affects tool invocation contract, policy configuration

**6. Does It Need Public API?**  
⚠️ MAYBE - GovernancePolicy needs to be configured somewhere

**7. Does It Need Core Abstraction?**  
⚠️ UNCLEAR - Minimal interception vs full governance framework?

---

### 10.2 Verdict

**Governance Interception:** ✅ **Creation trigger PARTIALLY met**

**Clarification:**
- **Tool invocation interception:** Real need
- **Governance decision semantic:** Real need  
- **Full Governance Framework:** ❌ Premature

**Recommendation:**  
**Minimal Governance Foundation** (interception + decision), not full framework.

---

## Part 11: Tool Abstraction Trigger Re-evaluation

### 11.1 Current State

**Spring AI ToolCallback:**
```java
interface ToolCallback {
    String getName();
    String getDescription();
    String call(String input);
}
```

**Represents:** "How to execute a tool"

**Does NOT represent:**
- Tool risk level
- Permission requirements
- Approval policy
- Arctra-specific metadata

---

### 11.2 Governance Need

**Governance needs to know:**
- Tool name (✅ ToolCallback provides)
- Tool risk level (❌ ToolCallback doesn't have)
- Permission required (❌ ToolCallback doesn't have)
- Approval policy (❌ ToolCallback doesn't have)

**Question:** 这些 metadata 应该在哪里？

---

### 11.3 Candidate Locations

**Option A: Extend ToolCallback**
```java
// ❌ Cannot extend Spring AI interface
```

**Option B: External Metadata Registry**
```java
Map<String, ToolMetadata> registry;
registry.get(toolCallback.getName());
```
⚠️ Requires tool name coordination

**Option C: Arctra ToolDefinition**
```java
record ToolDefinition(
    String name,
    RiskLevel risk,
    PermissionRequired permission,
    ToolCallback execution  // Spring AI callback
) {}
```
✅ Arctra semantic + Spring AI execution

---

### 11.4 Analysis

**EvidenceCapturingToolCallback 已证明:**
- Arctra 可以 wrap Spring AI ToolCallback
- Wrapper pattern 可行

**Governance 新增压力:**
- Metadata 不能存储在 ToolCallback
- Metadata 需要 Arctra-owned structure

**但：**
- ❌ 只有一个 governance scenario
- ❌ 没有多个 execution backend
- ❌ Tool metadata 可能 agent-specific (not tool-intrinsic)

---

### 11.5 Verdict

**Tool Abstraction Creation Trigger:** ❌ **NOT met (yet)**

**Reason:**
- Can use external metadata registry (Option B)
- Don't need Arctra ToolDefinition yet
- Wait for more pressure (multiple engines, governance maturity)

**Recommendation:**  
Keep Spring AI ToolCallback as execution primitive.  
Use external governance metadata for M4.

---

## Part 12: Tool Invocation Interception Analysis

### 12.1 Current Pattern (Evidence)

**EvidenceCapturingToolCallback:**
```java
class EvidenceCapturingToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final List<Evidence> evidences;
    
    @Override
    public String call(String input) {
        var result = delegate.call(input);
        evidences.add(new Evidence(
            "tool:" + delegate.getName(),
            result
        ));
        return result;
    }
}
```

**Pattern:** Decorator around Spring AI ToolCallback

---

### 12.2 Governance Extension

**GovernanceToolCallback (proposed):**
```java
class GovernanceToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final GovernancePolicy policy;
    
    @Override
    public String call(String input) {
        var decision = policy.evaluate(delegate.getName(), input);
        
        return switch (decision.type()) {
            case ALLOW -> delegate.call(input);
            case DENY -> formatDenial(decision.reason());
            case REQUIRE_APPROVAL -> {
                // ❓ How to suspend execution?
                throw new ApprovalRequiredException(decision);
            }
        };
    }
}
```

---

### 12.3 Composition

**Possible:**
```java
ToolCallback raw = new QueryLogsTool();
ToolCallback governed = new GovernanceToolCallback(raw, policy);
ToolCallback withEvidence = new EvidenceCapturingToolCallback(governed, evidences);
```

**Order matters:**
```
Call → Evidence → Governance → Raw Tool
```

**Analysis:**
- ✅ Decorator pattern scales
- ⚠️ Order coordination needed
- ⚠️ REQUIRE_APPROVAL 仍需 Process suspension

---

### 12.4 Interception Point

**Current natural interception point:**  
**SpringAiToolCallingEngine tool wrapping**

**Current code:**
```java
var wrappedTools = tools.stream()
    .map(tool -> new EvidenceCapturingToolCallback(tool, evidences))
    .toList();
```

**With Governance:**
```java
var wrappedTools = tools.stream()
    .map(tool -> new GovernanceToolCallback(tool, policy))
    .map(tool -> new EvidenceCapturingToolCallback(tool, evidences))
    .toList();
```

**Verdict:** ✅ Natural interception point exists.

---

## Part 13: agent.start() Contract Challenge

### 13.1 V1 Proposal

**V1 recommended:**
```java
// Simple case
agent.execute(request, context) → AgentResult

// Long-lived case
agent.start(request, context) → AgentProcess
```

---

### 13.2 Challenge

**Problem:** Suspension 不是 user 预先知道的。

**Scenario:**
```java
// User calls execute()
agent.execute(new AgentRequest("Fix incident"));

// During execution:
// - Model decides to rollback deployment
// - Governance says REQUIRE_APPROVAL
// - ❓ execute() 如何返回？
```

**Options:**

**A. execute() throws ApprovalRequiredException**
```java
try {
    agent.execute(...);
} catch (ApprovalRequiredException e) {
    var process = e.getProcess();
    // Later: process.resume(...)
}
```
❌ Exception for flow control

**B. execute() returns ExecutionOutcome**
```java
ExecutionOutcome outcome = agent.execute(...);
if (outcome.isCompleted()) {
    AgentResult result = outcome.getResult();
} else if (outcome.isSuspended()) {
    AgentProcess process = outcome.getProcess();
    // Later: process.resume(...)
}
```
✅ Explicit outcome types

**C. execute() always returns AgentResult, process suspension is transparent**
```java
agent.execute(...);  // Returns immediately (partial result?)
```
❌ Semantic confusion

---

### 13.3 Revised Understanding

**Key Insight:**  
Suspension 不是 invocation-time 决策，是 runtime outcome。

**Implication:**
- agent.execute() 可能 complete 或 suspend
- agent.start() 语义不清晰 (预先知道 long-lived?)

---

### 13.4 Recommended API

**Option 1: Unified execute() with outcome**
```java
ExecutionOutcome outcome = agent.execute(request, context);

// Simple case (most common)
if (outcome.completed()) {
    AgentResult result = outcome.result();
}

// Suspension case (rare)
if (outcome.suspended()) {
    AgentProcess process = outcome.process();
    // Later
    process.resume(approvalDecision);
}
```

**Option 2: Separate sync/async API**
```java
// For simple, known-synchronous tasks
AgentResult result = agent.execute(request, context);

// For potentially long-lived tasks
AgentProcess process = agent.startAsync(request, context);
// Later
process.awaitCompletion();
```

**Recommendation:** ⚠️ Needs M4-T1 Contract Gate decision

---

## Part 14: AgentProcess Creation Timing

### 14.1 When is Process Created?

**Option A: agent.start() creates Process**
```java
AgentProcess process = agent.start(request);
```
Explicit, user-initiated

**Option B: Every invocation has implicit Process**
```java
agent.execute(request);  // Internally creates Process
```
Transparent, always present

**Option C: Process created only when suspension occurs**
```java
agent.execute(request);
// If suspension → Process created
// If complete → No Process
```
On-demand, lazy

**Option D: Process explicit only for multi-step agents**
```java
// Simple agents
agent.execute(request);

// Multi-step agents
MultiStepAgent agent = ...;
agent.start(request) → AgentProcess
```
Type-driven

---

### 14.2 Analysis

**Agent = invocation handle (M3 semantic)**  
Not tied to specific execution duration

**Process = task lifecycle (M4 semantic)**  
Orthogonal to Agent

**Implication:**  
Process 不应该隐式绑定到每个 Agent invocation。

**Recommendation:**  
Process 是 explicit abstraction，只在需要时创建（Option C 或明确 API）。

---

## Part 15: Candidate Architecture Models

### Model A: Process Owns Governance ❌

```
Process
  → Step: EvaluateRisk
  → Step: WaitForApproval
  → Step: ExecuteTool
```

**Problem:** Governance becomes business step.

---

### Model B: Governance Owns Everything ❌

```
Tool Invocation
  → Governance Policy
    → Approval Workflow
      → Resume Execution
```

**Problem:** Governance 承担 Process 职责。

---

### Model C: Separation of Concerns ✅ RECOMMENDED

```
AgentProcess (lifecycle)
    ↓
ExecutionStep / Engine
    ↓
Tool Invocation
    ↓
Governance Interception
    ├── ALLOW → Tool
    ├── DENY → Rejection
    └── REQUIRE_APPROVAL
            ↓
        Process.suspend()
            ↓
        [Approval Event]
            ↓
        Process.resume()
```

**Advantages:**
- ✅ Governance orthogonal to Process
- ✅ Simple agents can use Governance without Process
- ✅ Process provides mechanism for Governance outcomes
- ✅ Clear semantic boundaries

---

## Part 16: Recommended Ownership Boundaries

**Arctra Owns:**
- ✅ Agent invocation API (M3)
- ✅ AgentProcess lifecycle (M4)
- ✅ ExecutionStep semantic (M4)
- ✅ Governance interception (M4 minimal)
- ✅ Evidence (M1)
- ✅ Execution context (M2)

**Spring AI Owns:**
- ✅ Model integration
- ✅ Tool Calling Loop
- ✅ ToolCallback execution
- ✅ ChatMemory

**Boundary:**
- Arctra = Process/Step/Governance semantics
- Spring AI = Model/Tool execution primitives

**Verdict:** ✅ Boundary clear and maintained

---

## Part 17: Revised M4 Scope Options

### Option 1: Process Foundation Only

**Scope:**
- AgentProcess
- ExecutionStep
- NO Governance

**Pros:** Focused, proven pressure  
**Cons:** Governance pressure ignored

---

### Option 2: Minimal Governance Only

**Scope:**
- Tool invocation interception
- Governance decision (ALLOW/DENY/REQUIRE_APPROVAL)
- NO Process

**Pros:** Addresses Governance pressure  
**Cons:** Cannot handle REQUIRE_APPROVAL (needs Process)

---

### Option 3: Process + Minimal Governance ⭐ RECOMMENDED

**Scope:**
- AgentProcess (lifecycle)
- ExecutionStep (concept)
- Minimal Governance (interception + decision)
- Vertical slice: Incident Remediation with tool governance + approval

**Pros:**
- ✅ Addresses all V1 + V2 pressures
- ✅ Validates orthogonality
- ✅ Complete scenario

**Cons:**
- ⚠️ Larger scope (need careful task breakdown)

---

### Option 4: Process Foundation + Governance Contract

**Scope:**
- AgentProcess implementation
- ExecutionStep implementation
- Governance architecture contract (ADR only, no implementation)

**Pros:**
- ✅ Focus on Process
- ✅ Defines Governance boundary

**Cons:**
- ⚠️ Governance implementation deferred

---

## Part 18: Recommended M4 Theme

# **M4: Agent Process Foundation + Minimal Governance Interception**

**Rationale:**
1. **Process pressure validated** (4 scenarios)
2. **Governance pressure confirmed** (tool invocation control)
3. **Orthogonality verified** (Governance ≠ Process)
4. **Vertical slice completeness** (can demonstrate full REQUIRE_APPROVAL flow)

---

## Part 19: Revised Task Breakdown

### M4-T1: Process & Governance Architecture Contract Gate ⭐

**Goal:** Define Process + Governance semantic, API contracts  
**Key Decisions:**
- AgentProcess lifecycle
- ExecutionStep boundary
- Governance interception point
- agent.execute() vs agent.start() vs ExecutionOutcome
- Process creation timing
- Governance vs Process relationship

**Deliverables:** Contract gate document  
**Effort:** 5-7 days (increased from V1 due to dual concerns)

---

### M4-T2: Minimal AgentProcess Implementation

**Goal:** AgentProcess interface + basic lifecycle  
**Scope:**
- AgentProcess interface
- ProcessStatus states
- Suspend/resume capability (skeleton)
- NO: Full workflow, checkpoint, distributed state

**Deliverables:** Working Process  
**Effort:** 5-7 days

---

### M4-T3: Minimal Governance Interception

**Goal:** Tool invocation governance  
**Scope:**
- GovernancePolicy interface (ALLOW/DENY/REQUIRE_APPROVAL)
- GovernanceToolCallback wrapper
- Policy configuration (simple, not DSL)
- NO: Full RBAC, audit, policy engine

**Deliverables:** Working governance interception  
**Effort:** 3-5 days

---

### M4-T4: Incident Remediation with Tool Governance + HITL

**Goal:** Complete vertical slice  
**Scope:**
- Multi-step incident process
- High-risk tool (rollback)
- Governance interception
- REQUIRE_APPROVAL → Process suspension
- Human approval → Resume
- Validation: Governance + Process orthogonality

**Deliverables:** Working example  
**Effort:** 5-7 days

---

### M4-T5: M4 Phase Closure

**Goal:** Documentation, reconciliation  
**Deliverables:** Closure report  
**Effort:** 2-3 days

---

**Total:** 20-29 days (vs V1: 15-22 days)

**Justification for increase:**  
Governance adds complexity, but validates architecture correctness.

---

## Part 20: Public API Impact

### 20.1 New Types (M4)

**Process:**
```java
interface AgentProcess {
    String id();
    ProcessStatus status();
    AgentResult result();  // when completed
}

enum ProcessStatus {
    CREATED, RUNNING, WAITING, COMPLETED, FAILED
}
```

**Governance:**
```java
interface GovernancePolicy {
    GovernanceDecision evaluate(String toolName, String input);
}

record GovernanceDecision(
    DecisionType type,  // ALLOW/DENY/REQUIRE_APPROVAL
    String reason
) {}
```

---

### 20.2 Agent API Evolution

**Current (M3):**
```java
AgentResult execute(AgentRequest, AgentExecutionContext);
```

**M4 Options (needs T1 decision):**

**A. ExecutionOutcome:**
```java
ExecutionOutcome execute(AgentRequest, AgentExecutionContext);
```

**B. Separate async API:**
```java
AgentResult execute(...);  // sync
AgentProcess startAsync(...);  // async
```

**Decision:** Deferred to M4-T1

---

## Part 21: Explicit Non-Goals

**M4 will NOT:**
- ❌ Workflow DSL / Graph
- ❌ Dynamic Planning / GOAP
- ❌ Full Governance Framework (RBAC, audit, policy DSL)
- ❌ Tool abstraction / ArctraTool
- ❌ Model/Agent Registry
- ❌ Distributed Process execution
- ❌ Production-grade checkpoint
- ❌ Multi-agent coordination

**M4 Focus:**
- ✅ Minimal Process (lifecycle + suspend/resume)
- ✅ Minimal Governance (interception + decision)
- ✅ Vertical slice validation

---

## Part 22: Architecture Risks

### Risk 1: Scope Creep

**Risk:** Governance + Process 一起做会导致 M4 过大

**Mitigation:**
- Strict non-goals
- Minimal implementation
- Vertical slice focused

---

### Risk 2: Premature Governance Abstraction

**Risk:** Governance semantic 尚未完全明确

**Mitigation:**
- M4-T1 Contract Gate 必须冻结 semantic
- 只做 interception，不做 full framework
- 保持与 Spring AI ToolCallback 兼容

---

### Risk 3: agent.execute() Breaking Change

**Risk:** ExecutionOutcome 可能导致 API 不兼容

**Mitigation:**
- M4-T1 必须设计兼容方案
- 可能保留 execute() 同步语义
- 新增 startAsync() 或类似 API

---

## Part 23: ADR Impact

### ADR-005: AgentProcess as Lifecycle Abstraction

**Must define:**
- Process = task lifecycle (not execution strategy)
- Process ≠ Workflow
- Process contains Steps (concept)
- Process pause/resume semantic

---

### ADR-006: Governance Interception Boundary (NEW)

**Must define:**
- Governance = tool invocation policy
- Governance ≠ Process
- Interception point = ToolCallback wrapper
- Decision types: ALLOW/DENY/REQUIRE_APPROVAL
- Governance uses Process suspension (not owns it)

---

## Part 24: Final Recommendation

### 24.1 V1 Conclusions Retained

✅ **AgentProcess creation trigger met**  
✅ **ExecutionStep creation trigger met**  
✅ **AgentExecutionEngine contract unchanged**  
✅ **Workflow/Planning deferred**

---

### 24.2 V1 Conclusions Modified

⚠️ **Governance should NOT defer to M5+**  
→ Minimal Governance Interception 应在 M4

⚠️ **HITL 不只是 Process concern**  
→ Governance 决策 + Process suspension

⚠️ **Tool abstraction 仍 defer**  
→ External metadata 足够 M4

---

### 24.3 Recommended M4 Scope

# **M4: Agent Process Foundation + Minimal Governance Interception**

**Primary:** AgentProcess + ExecutionStep  
**Secondary:** Governance interception (lightweight)

**Reference Scenario:**  
Incident Remediation with high-risk tool governance + human approval

---

## Part 25: Approval Gate

**Final Answers to 10 Questions:**

**Q1. M4 是否仍应该以 Agent Process Foundation 为主线？**  
✅ YES - Process pressure 独立成立

**Q2. Governance 是否真的必须等 Multi-Step/Process 完成以后？**  
❌ NO - Governance ALLOW/DENY 独立于 Process

**Q3. ALLOW/DENY 是否可以独立于 Process？**  
✅ YES - 已验证

**Q4. REQUIRE_APPROVAL 是否应该由 Governance 决策 + Process suspension 共同完成？**  
✅ YES - Orthogonal concerns

**Q5. Governance 和 Process 的 semantic ownership 分别是什么？**  
✅ Governance = "Can execute?"  
✅ Process = "How to pause/resume?"

**Q6. Tool abstraction creation trigger 是否因为 Governance 而发生变化？**  
❌ NO - 仍 defer，external metadata 足够

**Q7. Spring AI ToolCallback 是否仍然可以作为底层 execution primitive？**  
✅ YES - Wrapper pattern 证明可行

**Q8. agent.start() → AgentProcess 是否仍是正确 API？**  
⚠️ NEEDS M4-T1 DECISION - Suspension 不是预先知道的

**Q9. M4 最小合理 scope 到底是什么？**  
✅ Process + Minimal Governance (orthogonality validation)

**Q10. M4-T1 应该研究/冻结哪些 contract？**  
✅ Process lifecycle  
✅ Governance interception  
✅ agent.execute() outcome  
✅ Process creation timing  
✅ Suspension mechanism

---

**M4 Architecture Pressure Test V2 Complete.**  
**Awaiting Approval for Revised M4 Theme and Scope.**
