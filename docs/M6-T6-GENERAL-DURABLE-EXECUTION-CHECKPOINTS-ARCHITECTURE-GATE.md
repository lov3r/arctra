# M6-T6: GENERAL DURABLE EXECUTION CHECKPOINTS — ARCHITECTURE GATE

**Status:** ARCHITECTURE ANALYSIS  
**Date:** 2026-09-16  
**Phase:** Architecture Gate (NO IMPLEMENTATION)

---

## EXECUTIVE SUMMARY

**Core Question:**

> After a crash, given a known processId, what is the authoritative durable fact that tells Arctra where and how execution may safely continue?

**Current State (M6-T5 Complete):**

- ✅ Tool invocation recovery semantics established
- ✅ `InvocationStateStore` owns physical attempt recovery authority
- ✅ `SuspensionCheckpoint` owns approval-suspension recovery state
- ✅ Recovery classification can detect and resolve `MAY_HAVE_INVOKED`
- ✅ Cross-incarnation restart detection via `executionEpoch`

**Critical Gap:**

Arctra currently has strong **tool invocation recovery** but weak **execution recovery entry points**:

```
Agent execution crash (non-approval) → NO DURABLE RECOVERY STATE
```

Recovery is primarily tied to `SuspensionCheckpoint`, which is approval-specific.

**M6-T6 Mission:**

Evaluate whether Arctra needs **general execution checkpoints** beyond approval suspension, and if so, design the minimal semantic boundaries required.

---

## 1. SOURCE TRUTH: CURRENT EXECUTION FLOW

### 1.1 Initial Execution Entry Point

```java
// Entry: AgentRuntime.execute()
AgentRuntime.execute(definition, request, context)
  ↓
SpringAiToolCallingEngine.execute(definition, request, context)
  ↓
ChatClient.prompt()
    .system(systemInstruction)
    .user(request.userMessage())
    .tools(wrappedTools)
    .advisors(...)
  ↓
```

**Key observation:** No durable state created until approval suspension.

### 1.2 Model Call Flow

```java
ChatClient.prompt().call()
  ↓
ChatModel.call(Prompt)
  ↓
[External LLM API call]
  ↓
ChatResponse (with optional ToolCalls)
  ↓
GovernanceToolCallingAdvisor.adviseCall()
```

**Durable writes during model call:** NONE (currently)

### 1.3 Tool Materialization Flow

```java
ChatResponse contains AssistantMessage.ToolCall[]
  ↓
GovernanceToolCallingAdvisor evaluates batch
  ↓
If REQUIRE_APPROVAL:
  → suspendForApprovalDurable()
      ↓
      Generate processId
      ↓
      Build PendingToolCall[] with operationId
      ↓
      Create SuspensionCheckpoint v1
      ↓
      checkpointStore.create(checkpoint)  ← FIRST DURABLE WRITE
```

**Key observation:** Tool operations materialized in checkpoint BEFORE execution.

### 1.4 Approved Tool Execution Flow

```java
resumeProcess(processId, version, APPROVE)
  ↓
DurableResumeCoordinator.resume()
  ↓
CHECK A: load checkpoint
  ↓
Recovery mode selection (same vs cross-incarnation)
  ↓
If cross-incarnation:
  → Recovery classification for each operation
      ↓
      For each PendingToolCall:
        recoveryClassifier.classify(processId, operationId)
          ↓
          InvocationStateStore.findAttempts(processId, operationId)
          ↓
          Aggregate attempt states → classification
  ↓
SpringAiResumedExecutionHandler.executeResume()
  ↓
For each approved operation:
  attemptId = AttemptIds.generate()
  ↓
  invocationStateStore.recordInvocationIntent(processId, operationId, attemptId)
  ↓
  delegate.call()  ← PHYSICAL INVOCATION
  ↓
  Collect ToolResponse
  ↓
continueWithMessages() → ChatClient.call() again
  ↓
CHECK B: conditional checkpoint transition
```

**Key observation:** Tool execution has strong recovery through InvocationStateStore.

---

## 2. CURRENT DURABLE AUTHORITIES

### 2.1 Authority Matrix

| Fact | Current Authority | Durable? | Crash-Safe? |
|------|------------------|----------|-------------|
| **Process exists** | Checkpoint existence | Yes | Only if suspended |
| **Current recovery position** | SuspensionCheckpoint | Yes | Only at suspension |
| **Conversation history** | ChatMemory (Spring AI) | Depends | External |
| **Logical tool operation** | PendingToolCall in checkpoint | Yes | At suspension |
| **Physical tool attempt** | InvocationStateStore | Yes | Yes |
| **External tool outcome** | Resolution in InvocationStateStore | Yes | Yes |
| **Execution history** | ExecutionLedger | Yes (optional) | Audit only |
| **Approval decision** | ContinuationSignal (ephemeral) | No | No |
| **Model request sent** | NONE | No | No |
| **Model response received** | NONE | No | No |
| **Tool batch materialized** | Checkpoint (if suspended) | Conditional | No (unless HITL) |
| **Completion** | Checkpoint deletion | Yes (negative) | Yes |

**Critical Finding:** Many execution phases have NO durable authority.

### 2.2 ChatMemory Durability Analysis

**Source audit:**

```java
// SpringAiToolCallingEngine constructor
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,  // Injected, not owned
    ...
)
```

**ChatMemory is:**
- Spring AI interface (external contract)
- Implementation-dependent durability
- `InMemoryChatMemory`: JVM-local, NOT durable
- `CassandraChatMemory`: Durable (if configured)
- Arctra does NOT control durability guarantee

**Current assumption:**
- M2 multi-turn relies on ChatMemory for conversation continuity
- M5 durable resume assumes ChatMemory writes succeeded
- NO transactional coordination with CheckpointStore

**Crash window:**
```
CHECK B: checkpoint.delete() succeeds
  ↓
ChatMemory.add() fails
  ↓
Process shows COMPLETED
  ↓
Conversation history LOST
```

**Analysis:** This is a known M5 limitation, NOT new to T6.

---

## 3. CRASH WINDOW ANALYSIS

### 3.1 Complete Crash Window Map

| Phase | Durable State Before Crash | External Side Effect Possible? | Current Recovery | Ambiguity |
|-------|---------------------------|--------------------------------|------------------|-----------|
| **Before model request** | None | No | Re-execute from start | Lost work: none |
| **Model request sent** | None | Billing | Re-execute | Duplicate billing |
| **Model response received** | None | Billing | Re-execute | Lost response |
| **Model response accepted** | None | Billing | Re-execute | Lost response |
| **Tool batch materialized** | None (unless HITL) | No | Re-execute | Lost model response |
| **Approval checkpoint created** | Checkpoint v1 | No | Resume from checkpoint | None |
| **After intent / before delegate** | Intent recorded | No | Classification: MAY_HAVE_INVOKED | None (T5 resolves) |
| **During delegate execution** | Intent recorded | Yes (tool side effect) | Classification: MAY_HAVE_INVOKED | External state |
| **After delegate / before response** | Intent recorded | Yes | Classification: MAY_HAVE_INVOKED | External state |
| **Tool batch reconciled** | Intent + (maybe resolution) | Yes | Classification | External state |
| **Model continuation sent** | None | Billing | Re-execute continuation | Lost tool results |
| **Continuation response received** | None | Billing | Re-execute | Lost response |
| **Re-suspension checkpoint created** | Checkpoint v2 | No | Resume from v2 | None |
| **Before completion commit** | Checkpoint vN | Yes | Resume | Re-execute tools |
| **CHECK B delete checkpoint** | None (deleting) | Yes | Lost process state | No recovery |
| **After delete / before ChatMemory** | None | Yes | Lost process state | Conversation drift |

### 3.2 Critical Observations

**1. Most crash windows have NO recovery:**

Only recovery points:
- Approval suspension (HITL gate)
- Re-suspension (governance gate)

**2. Ordinary execution has no checkpoints:**

```
User request
  → model call #1
  → tool execution (NO HITL)
  → model call #2
  → completion
  
NO CHECKPOINT until suspension occurs
```

**3. Model call recovery is undefined:**

```
Model request sent → crash

Restart:
  What is the authoritative recovery position?
  → NONE (must re-execute entire process)
```

**4. Completion authority is "delete checkpoint":**

Completion is expressed as **absence of checkpoint**, not **presence of terminal state**.

---

## 4. CANDIDATE SEMANTIC BOUNDARIES

### 4.1 Boundary Evaluation

| Boundary | Persist? | Why? | Minimum State | Replay Safe? |
|----------|----------|------|---------------|--------------|
| **PROCESS_ACCEPTED** | NO | Ephemeral execution default | N/A | Yes (idempotent start) |
| **MODEL_REQUEST_PREPARED** | NO | No value without response | N/A | Yes (re-prepare) |
| **MODEL_RESPONSE_ACCEPTED** | MAYBE | Enables continuation without re-call | Conversation state | NO (billing) |
| **TOOL_BATCH_MATERIALIZED** | YES (conditional) | Already persisted in approval case | PendingToolCall[] | YES (governance) |
| **TOOL_BATCH_RECONCILED** | NO | Intent + resolution sufficient | N/A | NO (side effects) |
| **MODEL_CONTINUATION_ACCEPTED** | NO | Same as MODEL_RESPONSE | N/A | NO (billing) |
| **WAITING_FOR_APPROVAL** | YES | Current implementation | SuspensionCheckpoint | YES |
| **COMPLETED** | NO (negative) | Checkpoint deletion | N/A | YES (idempotent) |

### 4.2 Analysis: Do We Need New Boundaries?

**PROCESS_ACCEPTED:**
- Pro: Clear execution start marker
- Con: No recovery value (re-executing from start is safe)
- **Decision: DEFER**

**MODEL_RESPONSE_ACCEPTED:**
- Pro: Avoid duplicate LLM billing
- Con: Requires storing full conversation state beyond ChatMemory
- Con: Model responses are not deterministic (re-call may differ)
- Con: Creates new crash window (checkpoint vs ChatMemory consistency)
- **Decision: NO (billing is acceptable cost; non-determinism makes recovery semantics unclear)**

**TOOL_BATCH_MATERIALIZED (non-approval):**
- Pro: Could enable recovery before first tool execution
- Con: Only valuable if tools are expensive/slow
- Con: Governance already creates checkpoint at this point for HITL
- Con: Normal ALLOW case benefits from NOT creating checkpoint overhead
- **Decision: NO (optimization not worth complexity; ALLOW case should be fast path)**

**TOOL_BATCH_RECONCILED:**
- Pro: Post-execution, pre-continuation checkpoint
- Con: T5 already provides recovery through InvocationStateStore + resolution
- Con: Adding checkpoint duplicates authority
- **Decision: NO (InvocationStateStore is sufficient authority for tool recovery)**

**MODEL_CONTINUATION_ACCEPTED:**
- Same analysis as MODEL_RESPONSE_ACCEPTED
- **Decision: NO**

**COMPLETED (positive state):**
- Pro: Explicit terminal marker (vs absence)
- Con: Adds write overhead to every completion
- Con: Checkpoint absence already signals "not waiting"
- Con: ExecutionLedger already provides COMPLETED event
- **Decision: NO (negative authority via deletion is sufficient)**

---

## 5. SUSPENSIONCHECKPOINT SEMANTIC ANALYSIS

### 5.1 Current SuspensionCheckpoint Fields

```java
public record SuspensionCheckpoint(
    String schemaVersion,        // Schema evolution marker
    String processId,            // Stable process identity
    long checkpointVersion,      // Suspension episode (1, 2, 3...)
    String runtimeBindingKey,    // Runtime resolution key
    String sessionId,            // ChatMemory restoration key (nullable)
    List<PendingToolCall> pendingBatch,  // Pending operations
    List<Evidence> accumulatedEvidences, // Historical evidences
    String executionEpoch        // Restart detection (M6-T4F, nullable)
)
```

### 5.2 Field Authority Analysis

| Field | Semantic Owner | Why Needed? | Approval-Specific? | General Execution? |
|-------|---------------|-------------|-------------------|-------------------|
| `schemaVersion` | Framework | Schema evolution | No | Yes (any checkpoint) |
| `processId` | Framework | Process identity | No | Yes (any checkpoint) |
| `checkpointVersion` | Framework | CAS fencing token | No | Yes (any checkpoint) |
| `runtimeBindingKey` | Application | Cross-runtime binding | No | Yes (any checkpoint) |
| `sessionId` | Execution context | ChatMemory key | No | Yes (any checkpoint) |
| `pendingBatch` | **Approval semantics** | Operations awaiting decision | **YES** | Only if suspended before execution |
| `accumulatedEvidences` | Execution state | Historical proof | No | Yes (any checkpoint) |
| `executionEpoch` | Framework | Restart detection | No | Yes (any checkpoint) |

### 5.3 Key Finding

**`pendingBatch` is the ONLY approval-specific field.**

All other fields are general execution checkpoint concerns:
- Process/checkpoint identity
- Runtime binding for recovery
- Session continuity
- Evidence accumulation
- Restart detection

**Conclusion:** `SuspensionCheckpoint` is already 90% a general execution checkpoint. Only `pendingBatch` ties it to "waiting for approval" semantics.

### 5.4 Is SuspensionCheckpoint Actually General?

**Name suggests:** Approval suspension only  
**Content suggests:** General execution state with pending operations

**If we needed a general checkpoint, what would differ?**

```java
// Hypothetical GeneralCheckpoint
public record GeneralCheckpoint(
    String schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    ??? nextAction,              // What replaces pendingBatch?
    List<Evidence> accumulatedEvidences,
    String executionEpoch
)
```

**Problem:** What is `nextAction` for a general execution checkpoint?

Options:
1. **"Continue from model response"** → Need full conversation state (belongs in ChatMemory)
2. **"Continue from tool batch"** → That's `pendingBatch` (already have it)
3. **"Continue from completion"** → No continuation needed (terminal)
4. **"Continue from arbitrary step"** → Too vague (no clear semantics)

**Conclusion:** Every meaningful continuation point either:
- Has operations to execute → `pendingBatch`
- Needs conversation state → ChatMemory authority
- Is terminal → no checkpoint

---

## 6. IDENTITY HIERARCHY ANALYSIS

### 6.1 Current Identity Model

```
processId          — Stable process identity (across all suspensions)
  ↓
checkpointVersion  — Suspension episode (1 → 2 → 3...)
  ↓
operationId        — Logical durable tool operation
  ↓
attemptId          — Physical invocation attempt (UUID)
```

### 6.2 Missing Identities?

**turnId:**
- Semantics: "One model request/response cycle"
- Used by: Conversational systems, turn-based pricing
- M6-T6 need: **NO**
  - ChatMemory already owns conversation structure
  - Not needed for recovery correctness
  - Would duplicate authority

**stepId:**
- Semantics: "One execution step within a turn"
- Used by: Workflow systems, DAG execution
- M6-T6 need: **NO**
  - Arctra is not a workflow engine
  - Operations are batched, not DAG nodes
  - Would need to define what "step" means

**Conclusion:** Current identity hierarchy is sufficient.

`processId` + `checkpointVersion` provides all needed identity for general checkpoints.

### 6.3 Process Start Authority

**Question:** Is there durable "process exists" fact before first suspension?

**Current behavior:**
```
execute(definition, request, context)
  → NO processId generated
  → NO durable write
  → If crash: process never existed
```

**Is this a problem?**

**Analysis:**
- User does NOT have a processId yet (wasn't returned)
- User cannot call `resumeProcess()` without processId
- Re-executing `execute()` is safe (idempotent request)

**Conclusion:** Process materialization on first suspension is correct design.

Pre-suspension processId would require:
```
execute() 
  → generate processId
  → persist "RUNNING" checkpoint
  → actual execution
  → update checkpoint
```

This adds write overhead to EVERY execution, not just suspended ones.

**Decision: Process remains dynamically materialized (M4 design confirmed correct).**

---

## 7. COMPLETION AUTHORITY ANALYSIS

### 7.1 Current Completion Flow

```java
// After execution completes
CHECK B: checkpointStore.deleteIfVersion(processId, version)
  ↓
If deleted:
  → Checkpoint gone (negative authority)
  → ExecutionLedger: COMPLETED event (historical record)
  → ChatMemory: final messages persisted
  → Return AgentResult
```

### 7.2 Completion Semantics

**Checkpoint absence means:**
1. Process never suspended, OR
2. Process completed, OR
3. Process failed and cleaned up

**How to distinguish?**

- ExecutionLedger query: `queryByProcess(processId)` → last event
- If no ledger: Cannot distinguish (acceptable for M6)

### 7.3 Should Completion Be Positive State?

**Option A: Current (negative authority)**
```
WAITING state = checkpoint exists
COMPLETED state = checkpoint absent
```

**Option B: Positive authority**
```
WAITING state = checkpoint.status = WAITING
COMPLETED state = checkpoint.status = COMPLETED
```

**Trade-offs:**

| Aspect | Negative (Current) | Positive (Option B) |
|--------|-------------------|-------------------|
| Storage overhead | Delete (free space) | Persist forever (cleanup needed) |
| Query "is completed?" | `load() → empty` | `load() → status == COMPLETED` |
| Query "all waiting" | List all checkpoints | Filter by status |
| Accidental re-resume | Prevented (no checkpoint) | Prevented (status check) |
| Cleanup strategy | Automatic | Manual/scheduled |

**Decision: Keep negative authority.**

Reasons:
1. Storage efficiency (completed processes don't consume space)
2. Simpler query ("is waiting?" = "checkpoint exists?")
3. No cleanup job needed
4. M4/M5 design already proven stable

---

## 8. CONCURRENCY ANALYSIS

### 8.1 Current Concurrency Contract

**CHECK A (load + validate):**
```java
checkpoint = checkpointStore.load(processId)
if (checkpoint.version != requestedVersion) {
  throw StaleCheckpointException
}
```

**CHECK B (conditional transition):**
```java
boolean replaced = checkpointStore.replaceIfVersion(
    processId, expectedVersion, newCheckpoint)

if (!replaced) {
  throw CheckpointTransitionConflictException
}
```

### 8.2 Concurrent Resume Scenario

```
Worker A: resumeProcess(proc-1, v1, APPROVE)
  → CHECK A: load v1 ✓
  → Execute tools
  → CHECK B: replaceIfVersion(v1 → v2)

Worker B: resumeProcess(proc-1, v1, APPROVE) [concurrent]
  → CHECK A: load v1 ✓
  → Execute tools
  → CHECK B: replaceIfVersion(v1 → v2)
```

**Result:**
- One worker succeeds CHECK B (v1 → v2)
- Other worker fails CHECK B (v1 no longer current)
- Failed worker throws `CheckpointTransitionConflictException`
- **But tools may have executed (at-least-once semantics)**

### 8.3 Is This Acceptable?

**M5 design decision:** At-least-once execution is acceptable.

CHECK B prevents:
- Checkpoint corruption (stale state overwriting newer state)
- Lost work (newer checkpoint deletion by stale worker)

CHECK B does NOT prevent:
- Duplicate tool execution (inherent in distributed at-least-once)
- Wasted work (losing CHECK B means re-execution needed)

**For general checkpoints, same semantics apply:**

If we added ordinary execution checkpoints:
```
Crash during execution
  → Restart
  → Load checkpoint
  → Resume execution
  → Crash during CHECK B
  → Restart again
  → Load checkpoint (may be newer version now)
  → Must handle version mismatch
```

**Conclusion:** Optimistic CAS is sufficient. No need for claim/lease/fencing for M6.

---

## 9. MODEL INVOCATION RECOVERY ANALYSIS

### 9.1 Model Call Authority Question

**Crash scenario:**
```
ChatModel.call(prompt) sent
  ↓
[Network in flight]
  ↓
Crash
```

**Recovery questions:**
1. Was request sent to provider?
2. Was response generated?
3. Was response received?
4. Can we safely retry?

### 9.2 Current State: NO Model Invocation Authority

Arctra does NOT track:
- Model request sent
- Model response received
- Provider request ID

**Consequences:**
- Cannot distinguish "never sent" vs "sent but lost response"
- Must retry on crash
- Duplicate billing possible
- Non-deterministic responses (different result on retry)

### 9.3 Should We Add ModelInvocationStateStore?

**Similar to InvocationStateStore but for model calls:**

```java
void recordModelRequestIntent(String processId, String requestId);
boolean hasModelRequestIntent(String processId, String requestId);
```

**Analysis:**

**Differences from tool invocation:**

| Aspect | Tool Invocation | Model Invocation |
|--------|----------------|------------------|
| Side effects | External actions (DB, API, etc) | Billing only |
| Idempotency | Tool-dependent (often not idempotent) | Always non-idempotent (billing) |
| Determinism | Tool-dependent | Non-deterministic (model varies) |
| Recovery value | External reconciliation | Billing mitigation |
| Complexity | High (external systems) | Medium (provider APIs) |

**Arguments FOR ModelInvocationStateStore:**
- Avoid duplicate billing on crash
- Track request/response correlation
- Enable request deduplication

**Arguments AGAINST:**
- Billing is acceptable cost (vs complexity)
- Non-deterministic responses mean cached response may be wrong
- Provider rate limits already handle duplicate requests
- Adds write overhead to EVERY model call (not just suspended)

**Decision: NO ModelInvocationStateStore in M6.**

Reasons:
1. Billing cost is acceptable operational overhead
2. Non-determinism makes "recover cached response" semantically unclear
3. High write overhead for marginal benefit
4. Providers have idempotency keys if critical
5. Can be added later if billing becomes major issue

---

## 10. CHECKPOINT MODEL DESIGN OPTIONS

### OPTION A: Keep SuspensionCheckpoint, No Changes

**Semantics:**
- Checkpoint only created on approval suspension
- Ordinary execution has no checkpoint
- Crash during ordinary execution → re-execute from start

**Pros:**
- Zero M6-T6 implementation (already done)
- Simple mental model (checkpoint = waiting for approval)
- No write overhead for fast path
- Proven stable (M4/M5)

**Cons:**
- No recovery for ordinary execution crashes
- Lost work if crash after expensive tools (NO HITL case)

### OPTION B: Generalize SuspensionCheckpoint → ExecutionCheckpoint

**Semantics:**
- Checkpoint created at multiple execution boundaries
- Renamed to `ExecutionCheckpoint` (broader semantics)
- `pendingBatch` becomes optional (nullable for non-approval checkpoints)

**Proposed schema:**
```java
public record ExecutionCheckpoint(
    String schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<PendingToolCall> pendingBatch,  // Nullable for non-approval
    List<Evidence> accumulatedEvidences,
    String executionEpoch,
    CheckpointReason reason  // NEW: why checkpoint created
)

enum CheckpointReason {
  APPROVAL_SUSPENSION,
  PERIODIC_SNAPSHOT,      // Periodic execution checkpoint
  POST_TOOL_EXECUTION,    // After expensive tools
  MANUAL_CHECKPOINT       // Application-requested
}
```

**When to create checkpoints:**
- Approval suspension (existing)
- After each tool batch execution (new)
- Periodic (time-based, new)
- Application-explicit (new API)

**Pros:**
- Recovery for ordinary execution
- Reduced lost work on crash
- Flexible checkpoint strategy

**Cons:**
- High write overhead (checkpoint after every tool batch)
- Complex resume semantics (what does "pendingBatch = null" mean?)
- Schema evolution complexity
- Public API expansion

**Analysis:**
- "After tool batch" checkpoint duplicates InvocationStateStore authority
- "Periodic" requires background timer/trigger (complexity)
- "Manual" requires exposing checkpoint API to application

### OPTION C: Add Separate OrdinaryExecutionCheckpoint

**Semantics:**
- Keep `SuspensionCheckpoint` for approval (unchanged)
- Add `OrdinaryExecutionCheckpoint` for ordinary execution
- Different schemas for different purposes

**Proposed:**
```java
// Approval suspension (existing)
public record SuspensionCheckpoint(...)

// Ordinary execution (new)
public record OrdinaryExecutionCheckpoint(
    String schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<Evidence> accumulatedEvidences,
    String executionEpoch,
    ConversationSnapshot conversation  // NEW
)
```

**Pros:**
- Clear semantic distinction
- No impact on existing SuspensionCheckpoint
- Type-safe resume dispatch

**Cons:**
- Schema duplication (most fields identical)
- Two checkpoint authorities (confusing)
- Conversation snapshot duplicates ChatMemory authority
- Still requires write overhead

### OPTION D: Source Audit Conclusion — No New Checkpoint Abstraction

**Critical realization from source audit:**

Every meaningful recovery point either:
1. Has pending operations → `SuspensionCheckpoint.pendingBatch` (approval)
2. Has completed operations → `InvocationStateStore` authority
3. Has conversation state → `ChatMemory` authority
4. Is terminal → checkpoint deletion

**What would "ordinary execution checkpoint" contain?**

If no pending operations (crash after tools, before next model call):
- Need conversation state → ChatMemory already owns this
- Need tool results → Already in ChatMemory as ToolResponseMessages
- Need evidence → Could checkpoint, but marginal value

**Recovery semantics would be:**
```
Load ordinary checkpoint
  ↓
What to do?
  → Continue from conversation state
  → But conversation state is in ChatMemory
  → Checkpoint just duplicates ChatMemory pointer (sessionId)
```

**Conclusion:** Ordinary execution checkpoint provides no authority beyond what ChatMemory + InvocationStateStore already provide.

---

## 11. CHATMEMORY ANALYSIS: THE ELEPHANT IN THE ROOM

### 11.1 ChatMemory Durability Dependency

**M5 implicit assumption:**
```java
CHECK B: checkpoint.delete() succeeds
  ↓
ChatMemory.add(sessionId, finalMessages)  ← ASSUMED TO SUCCEED
  ↓
Return AgentResult
```

**Reality:**
- ChatMemory is Spring AI interface (external contract)
- Implementation durability varies:
  - `InMemoryChatMemory`: NOT durable
  - `CassandraChatMemory`: Durable (if configured)
- **No transactional coordination with CheckpointStore**

### 11.2 Known Crash Windows

**Window 1: Completion**
```
Tools executed successfully
  ↓
Continuation model call
  ↓
Response received: "Analysis complete: schema mismatch detected"
  ↓
CHECK B: checkpoint.delete() succeeds  ✓
  ↓
Crash before ChatMemory.add()
  ↓
Result: Process shows COMPLETED, conversation history LOST
```

**Window 2: Re-suspension**
```
Tools executed
  ↓
Continuation model call returns new tool batch
  ↓
CHECK B: checkpoint.replace(v1 → v2) succeeds  ✓
  ↓
Crash before ChatMemory.add()
  ↓
Result: Checkpoint v2 exists, but conversation missing last turn
```

### 11.3 Is This a T6 Problem?

**Analysis:**

This is NOT a new problem introduced by general checkpoints. This is **existing M2/M5 architecture**.

General checkpoints would make it WORSE:
- More checkpoint writes → more crash windows
- Each checkpoint transition creates ChatMemory consistency risk

**Options to address:**

**Option 1: Accept inconsistency (current)**
- Document as known limitation
- Application uses durable ChatMemory if critical
- Rare window, acceptable risk

**Option 2: Checkpoint-managed conversation**
- Store conversation in checkpoint itself
- Pros: Atomic consistency
- Cons: Duplicate ChatMemory authority, schema bloat

**Option 3: Coordinated write**
- Write ChatMemory first, then checkpoint
- Or: distributed transaction (XA)
- Cons: High complexity, performance penalty

**Option 4: Post-checkpoint correction**
- Write checkpoint, then ChatMemory
- On recovery: if checkpoint exists but ChatMemory missing last turn, reconstruct from checkpoint
- Cons: Complex reconstruction logic

**Decision for M6-T6:**

**ACCEPT INCONSISTENCY** (Option 1).

Reasons:
1. Existing M5 limitation (not new)
2. Rare crash window
3. General checkpoints would exacerbate, not solve
4. Solving requires distributed transaction or authority redesign
5. Outside M6-T6 scope

**Recommendation:** Document as known limitation, defer to M7 "Durability & Consistency" phase.

---

## 12. RECOVERY ENTRY POINT ANALYSIS

### 12.1 Current Resume API

```java
// AgentRuntime
AgentResult resumeProcess(
    String processId, 
    long checkpointVersion, 
    ContinuationSignal signal
)
```

**Semantics:**
- Resume from approval suspension
- Signal = APPROVE or REJECT
- Version = fencing token

### 12.2 Would General Checkpoints Need Different API?

**Hypothetical:**
```java
// Option 1: Overload existing
AgentResult resumeProcess(
    String processId,
    long checkpointVersion,
    ContinuationSignal signal  // What signal for ordinary resume?
)

// Option 2: Separate method
AgentResult recoverProcess(
    String processId,
    long checkpointVersion
)

// Option 3: Unified with mode
AgentResult resumeProcess(
    String processId,
    long checkpointVersion,
    ResumeMode mode  // APPROVAL_CONTINUATION, CRASH_RECOVERY
)
```

**Analysis:**

**Problem:** `ContinuationSignal` has approval semantics (APPROVE/REJECT).

Ordinary crash recovery has no "approval decision". What would the signal be?

```java
// Awkward
runtime.resumeProcess(processId, version, ContinuationSignal.APPROVE)
  // But there's no approval... this is just crash recovery
```

**Options:**

**Option A:** Make signal nullable
```java
AgentResult resumeProcess(
    String processId,
    long checkpointVersion,
    @Nullable ContinuationSignal signal  // null = ordinary resume
)
```

**Option B:** Add CONTINUE signal
```java
enum ContinuationSignal {
  APPROVE,
  REJECT,
  CONTINUE  // NEW: ordinary crash recovery
}
```

**Option C:** Separate method
```java
AgentResult resumeProcess(..., signal);  // Approval
AgentResult recoverProcess(...);         // Crash recovery
```

**Analysis:**

If we don't add general checkpoints (Option A from §10), this question is moot.

If we do add them, Option B (CONTINUE signal) is cleanest.

---

## 13. STORAGE REQUIREMENTS ANALYSIS

### 13.1 Current Storage Model

```
CheckpointStore:
  - processId → SuspensionCheckpoint (1:1)
  - Operations: create, load, replaceIfVersion, deleteIfVersion

InvocationStateStore:
  - (processId, operationId, attemptId) → intent record
  - (processId, operationId, attemptId) → resolution (optional)
  - Operations: recordIntent, hasIntent, findAttempts, recordResolution

ExecutionLedger (optional):
  - (processId, sequence) → ExecutionRecord
  - Operations: append, queryByProcess
```

### 13.2 Would General Checkpoints Need New Storage?

**No.**

Same `CheckpointStore` interface works:
- `create()` — initial checkpoint (approval or ordinary)
- `load()` — read current state
- `replaceIfVersion()` — transition to next checkpoint
- `deleteIfVersion()` — completion/failure

**Schema evolution:**

If `SuspensionCheckpoint` → `ExecutionCheckpoint`:
- Add `reason` field (enum)
- Make `pendingBatch` nullable
- Increment `schemaVersion`

Existing `CheckpointStore` implementations (JDBC, InMemory) would need schema migration, but interface unchanged.

---

## 14. LEGACY MIGRATION ANALYSIS

### 14.1 Current Checkpoint Schema

```java
// M5: schema version "1.0"
// M6-T4F: schema version "1.1" (added executionEpoch)
```

**Existing checkpoints in production:**
- May be v1.0 (no executionEpoch)
- May be v1.1 (has executionEpoch)

### 14.2 If We Add General Checkpoints

**New schema: "2.0"**
```java
public record ExecutionCheckpoint(
    // ... existing fields
    CheckpointReason reason  // NEW
)
```

**Migration:**
- Old `SuspensionCheckpoint` with schema "1.1"
- New `ExecutionCheckpoint` with schema "2.0"

**Compatibility:**

**Option 1: Fail closed**
```java
if (checkpoint.schemaVersion < "2.0") {
  throw ResumePreparationException("Old checkpoint requires completion or migration")
}
```

**Option 2: Interpret old as approval**
```java
CheckpointReason reason = inferReasonFromSchema(checkpoint);
if (checkpoint.schemaVersion == "1.1") {
  reason = CheckpointReason.APPROVAL_SUSPENSION;  // Safe assumption
}
```

**Decision:** If we don't add general checkpoints, no migration needed.

---

## 15. DESIGN OPTIONS COMPARISON

### 15.1 Summary Matrix

| Option | Write Overhead | Recovery Granularity | Authority Clarity | Complexity | Recommendation |
|--------|---------------|---------------------|-------------------|------------|----------------|
| **A: Status Quo** | Low (only HITL) | Coarse (approval points only) | High | Low | ✅ **RECOMMENDED** |
| **B: Generalize to ExecutionCheckpoint** | High (every tool batch) | Fine (multiple boundaries) | Medium | High | ❌ |
| **C: Separate OrdinaryCheckpoint** | Medium | Medium | Low (dual authority) | Very High | ❌ |
| **D: No checkpoint (relying on ChatMemory)** | None | ChatMemory-dependent | Low | Low | ⚠️ Risky |

### 15.2 Option A (Status Quo) Detailed Analysis

**What we have:**
- Checkpoint only on approval suspension
- Tool recovery via InvocationStateStore
- Conversation via ChatMemory
- History via ExecutionLedger

**What we DON'T have:**
- Recovery for ordinary execution (no HITL)
- Checkpoint-managed conversation state
- Periodic execution snapshots

**Is this sufficient?**

**YES, for the following reasons:**

1. **Approval is the natural checkpoint boundary**
   - Human decision point (naturally async)
   - Operation batch already materialized
   - Semantically meaningful suspension

2. **Tool recovery is separately handled**
   - InvocationStateStore provides physical attempt authority
   - Works across any execution boundary
   - No need to duplicate in checkpoint

3. **Conversation authority belongs to ChatMemory**
   - Spring AI contract
   - Arctra checkpointing conversation would duplicate authority
   - Application controls ChatMemory durability

4. **Ordinary execution can re-execute safely**
   - No external approval decision to lose
   - Re-executing tools uses InvocationStateStore recovery
   - Marginal cost: duplicate LLM billing (acceptable)

5. **Adding general checkpoints creates more problems than it solves**
   - ChatMemory consistency risk multiplies
   - Write overhead on fast path
   - Unclear semantics for "ordinary checkpoint" recovery
   - Authority overlap with existing stores

---

## 16. FINAL DECISION

### 16.1 Core Finding

**After complete source audit and crash window analysis:**

> Arctra's current checkpoint model (approval suspension only) is CORRECT and SUFFICIENT for M6.

**General execution checkpoints are NOT NEEDED because:**

1. Every meaningful recovery point already has an authority:
   - Pending operations → SuspensionCheckpoint.pendingBatch
   - Completed operations → InvocationStateStore
   - Conversation state → ChatMemory
   - Execution history → ExecutionLedger

2. "Ordinary checkpoint" has no clear semantic content:
   - Cannot contain conversation (belongs to ChatMemory)
   - Cannot contain tool state (belongs to InvocationStateStore)
   - Only metadata (session, evidences) → low recovery value

3. Adding general checkpoints creates new problems:
   - ChatMemory consistency windows multiply
   - Write overhead on every execution
   - Duplicate authority
   - Unclear resume semantics

### 16.2 Authority Principle Validation

**M6 Architecture principle:**

> "Each durable fact has exactly one authoritative owner."

**Current authority distribution:**

| Fact | Authority | Rationale |
|------|-----------|-----------|
| Process waiting for approval | Checkpoint existence | State transition boundary |
| Logical tool operation | PendingToolCall in checkpoint | Approval batch materialization |
| Physical tool attempt | InvocationStateStore.intent | Pre-execution gate |
| External tool outcome | InvocationStateStore.resolution | Reconciliation fact |
| Conversation state | ChatMemory | Spring AI contract |
| Execution history | ExecutionLedger | Audit trail |

**No overlap. No gaps that general checkpoints would fill.**

### 16.3 Selected Design: OPTION A (Status Quo)

**Decision:** NO new general checkpoint abstraction.

**Keep:**
- `SuspensionCheckpoint` (name unchanged)
- Checkpoint only on approval suspension
- Tool recovery via InvocationStateStore
- Conversation via ChatMemory

**Rationale:**
1. Current model is architecturally sound
2. Each authority has clear owner
3. No recovery gaps that need filling
4. General checkpoints would add complexity without semantic value
5. ChatMemory consistency is existing limitation (not T6 scope)

---

## 17. CHECKPOINT VS CHATMEMORY CONSISTENCY (KNOWN LIMITATION)

### 17.1 The Real Problem

**Root cause:**

Arctra checkpoints and Spring AI ChatMemory are **two separate authorities** with **no transactional coordination**.

**Crash windows exist at every state transition:**
```
checkpoint.write()
  ↓ (crash)
chatMemory.write()
```

### 17.2 Why This Is NOT T6 Responsibility

1. **Existing M2/M5 architecture**
   - Present since multi-turn support (M2)
   - Acknowledged in M5 limitations
   - Not introduced by T6

2. **External authority**
   - ChatMemory is Spring AI interface
   - Arctra does not control implementation
   - Application chooses durable or ephemeral ChatMemory

3. **General checkpoints would worsen, not solve**
   - More checkpoint writes → more consistency windows
   - Does not address fundamental dual-authority issue

### 17.3 Proper Solution (Out of Scope)

**Option 1: Single authority**
```
checkpoint.conversationHistory = messages[]
→ Remove ChatMemory dependency
→ Checkpoint owns conversation
```

**Option 2: Coordinated write**
```
ChatMemory.write() inside checkpoint transaction
→ Requires distributed transaction
→ Or: custom CheckpointStore + ChatMemory pair
```

**Option 3: Compensating action**
```
On recovery: if checkpoint exists but ChatMemory incomplete
→ Reconstruct conversation from checkpoint
→ Requires checkpoint to store conversation
```

**All options require major architecture redesign.**

**Recommendation:** Document as known M6 limitation, address in M7 "Consistency & Transactions" phase.

---

## 18. T6 VS T7 BOUNDARY

### 18.1 T6 Scope (This Gate)

✅ **IN SCOPE:**
- Evaluate need for general execution checkpoints
- Analyze current authority distribution
- Identify crash windows
- Design checkpoint model if needed

✅ **DECISION:** No general checkpoints needed.

### 18.2 T7 Scope (Future)

**T7: Recovery Discovery & Orchestration**

Expected scope:
- Checkpoint enumeration (`listWaitingProcesses()`)
- Startup scan for orphaned checkpoints
- Automatic recovery triggers
- Process ownership / claim / lease
- Distributed recovery coordination

**T6 does NOT implement:**
- ❌ Checkpoint scanning
- ❌ Auto-resume scheduler
- ❌ Process discovery
- ❌ Ownership claims

**T6 checkpoint model must not block T7:**

Current `CheckpointStore` interface:
```java
void create(SuspensionCheckpoint checkpoint);
Optional<SuspensionCheckpoint> load(String processId);
boolean replaceIfVersion(...);
boolean deleteIfVersion(...);
```

**Missing for T7:** No enumeration method.

**T7 will need:**
```java
// Future extension
List<String> listProcessIds();
List<SuspensionCheckpoint> listCheckpoints(Predicate<SuspensionCheckpoint> filter);
```

**Decision:** Defer to T7. Current design does not block future enumeration.

---

## 19. T6 VS T8 BOUNDARY

### 19.1 T8 Scope (Future)

**T8: Distributed Recovery & Multi-Node Ownership**

Expected scope:
- Claim / lease protocol
- Fencing tokens
- Distributed coordination (ZooKeeper, etc.)
- Multi-node recovery safety

**T6 does NOT design:**
- ❌ Claim mechanism
- ❌ Lease management
- ❌ Fencing beyond checkpointVersion

**Current checkpointVersion is sufficient for T8:**

`checkpointVersion` already acts as fencing token:
- Optimistic CAS prevents stale worker overwrite
- CHECK B fails if version mismatch
- Sufficient for single-node or stateless multi-node

**T8 may need:**
```java
// Hypothetical
String claimedBy;   // Worker ID
Instant claimExpiry;  // Lease timeout
```

**Decision:** Current single-incarnation model does not block future distributed extensions.

---

## 20. PUBLIC API IMPACT ANALYSIS

### 20.1 If We Kept Status Quo (Decision)

**Zero public API changes required.**

Existing API sufficient:
```java
AgentRuntime.execute(...)         // Initial execution
AgentRuntime.resumeProcess(...)   // Resume from suspension
AgentRuntime.recovery()           // M6-T5 resolution API
```

### 20.2 If We Added General Checkpoints (NOT selected)

**Required public API:**

```java
// New checkpoint reason exposure
public enum CheckpointReason {
  APPROVAL_SUSPENSION,
  PERIODIC_SNAPSHOT,
  POST_TOOL_EXECUTION
}

// Extended checkpoint
public record ExecutionCheckpoint(
    // ... existing fields
    CheckpointReason reason
)

// Possibly new resume signal
enum ContinuationSignal {
  APPROVE,
  REJECT,
  CONTINUE  // For ordinary recovery
}

// Or separate method
AgentRuntime.recoverProcess(String processId, long version);
```

**Public API budget:** ~3-5 new types/methods.

**Since we chose Status Quo:** Public API budget = **ZERO**.

---

## 21. IMPLEMENTATION ESTIMATE (IF GO)

### 21.1 Hypothetical: General Checkpoints Implementation

**IF we decided to implement general checkpoints (we didn't):**

**Phases:**

1. **Schema evolution** (1-2 days)
   - Add `CheckpointReason` enum
   - Make `pendingBatch` nullable
   - Update schema version to "2.0"
   - Migration strategy

2. **Checkpoint creation points** (2-3 days)
   - After tool execution (non-approval)
   - Periodic snapshots
   - Evidence accumulation threshold

3. **Resume semantics** (3-4 days)
   - Handle `pendingBatch = null` cases
   - Continuation from ordinary checkpoint
   - ChatMemory consistency handling

4. **Tests** (3-4 days)
   - Ordinary execution checkpoint tests
   - Cross-boundary recovery tests
   - Schema migration tests

5. **Documentation** (1-2 days)
   - User guide updates
   - Known limitations
   - Migration guide

**Total estimate:** ~10-15 days (2-3 weeks)

**Production LOC:** ~800-1000 lines  
**Test LOC:** ~1500-2000 lines

**But this is HYPOTHETICAL. Decision is NO-GO.**

---

## 22. RISKS & MITIGATION

### 22.1 Risk: Ordinary Execution Lost Work

**Risk:**
```
Expensive tool execution (NO HITL)
  → 10 minutes of processing
  → Crash before completion
  → Must re-execute entire 10 minutes
```

**Likelihood:** Low (crashes during execution are rare)

**Impact:** Medium (lost compute time, duplicate billing)

**Mitigation:**
1. Application uses HITL for expensive operations
2. Tool implementations are idempotent where possible
3. InvocationStateStore prevents duplicate external side effects
4. Acceptable operational cost

**Decision:** Accept risk. Do not add general checkpoints to mitigate.

### 22.2 Risk: ChatMemory Inconsistency

**Risk:**
```
CHECK B succeeds
  → Crash before ChatMemory.add()
  → Conversation history incomplete
```

**Likelihood:** Low (small crash window)

**Impact:** Medium (conversation context lost)

**Mitigation:**
1. Document as known limitation
2. Application uses durable ChatMemory if critical
3. Defer to M7 consistency phase

**Decision:** Accept risk. Not T6 scope.

### 22.3 Risk: Model Billing Duplication

**Risk:**
```
Model call sent
  → Crash before response persisted
  → Retry sends duplicate request
  → Double billing
```

**Likelihood:** Low (small crash window)

**Impact:** Low (billing cost acceptable operational overhead)

**Mitigation:**
1. Provider APIs often have idempotency keys (application can use)
2. Operational monitoring can detect duplicates
3. Cost is marginal compared to complexity of prevention

**Decision:** Accept risk. Do not add ModelInvocationStateStore.

---

## 23. FINAL ARCHITECTURE DECISION

### 23.1 Decision Summary

**M6-T6 ARCHITECTURE DECISION: NO-GO**

**No new general execution checkpoint abstraction.**

**Keep current checkpoint model:**
- `SuspensionCheckpoint` (approval suspension only)
- Checkpoint created on HITL governance decision
- Tool recovery via `InvocationStateStore`
- Conversation via `ChatMemory`
- History via `ExecutionLedger`

**Rationale:**

1. **Authority analysis:** Every recovery fact has clear owner
2. **Semantic analysis:** "Ordinary checkpoint" has no clear content beyond existing authorities
3. **Crash window analysis:** General checkpoints do not fill gaps
4. **Complexity analysis:** General checkpoints add problems without proportional value
5. **Source audit:** Current model is architecturally sound

### 23.2 What This Means

**M6-T6 deliverable:**

✅ **This architecture gate document** (analysis complete)

**M6-T6 does NOT deliver:**
- ❌ New checkpoint abstractions
- ❌ General execution checkpoints
- ❌ Periodic snapshots
- ❌ Model invocation tracking
- ❌ Public API changes
- ❌ Schema evolution
- ❌ Implementation phases

### 23.3 Answer to Core Question

**Question:**
> After a crash, given a known processId, what is the authoritative durable fact that tells Arctra where and how execution may safely continue?

**Answer:**

**IF process has suspended for approval:**
```
CheckpointStore.load(processId) → SuspensionCheckpoint
  → Contains: pendingBatch, sessionId, evidences, executionEpoch
  → Recovery: resumeProcess(processId, version, signal)
```

**IF process has NOT suspended:**
```
No durable process state exists
  → Process was ephemeral (never materialized)
  → Recovery: Re-execute from start (safe, idempotent)
```

**IF process completed:**
```
CheckpointStore.load(processId) → empty
ExecutionLedger.queryByProcess(processId) → COMPLETED event
  → No recovery needed
```

**This three-state model is sufficient and correct.**

---

## 24. M6 CLOSURE RECOMMENDATIONS

### 24.1 M6-T6 Status

✅ **COMPLETE — NO IMPLEMENTATION REQUIRED**

M6-T6 gate decision: **NO-GO** (architecturally sound, no changes needed)

### 24.2 M6 Track Status

**M6 Milestone: Durable Recovery**

Completed tasks:
- ✅ M6-T1: ExecutionLedger Foundation
- ✅ M6-T2: Event Dispatch
- ✅ M6-T3: Durable Tool Operation Identity
- ✅ M6-T4: Invocation Intent Foundation
- ✅ M6-T5: Durable Recovery Execution & Resolution
- ✅ **M6-T6: General Durable Execution Checkpoints** (NO-GO decision)

**M6 can now close.**

### 24.3 Known Limitations to Document

**M6 Known Limitations:**

1. **Checkpoint/ChatMemory consistency**
   - Two separate authorities, no transactional coordination
   - Small crash windows exist at state transitions
   - Mitigation: Use durable ChatMemory implementation
   - Future: M7 consistency phase

2. **Ordinary execution (no HITL) lost work**
   - Crash during execution → must re-execute from start
   - Marginal cost: duplicate billing, lost compute time
   - Mitigation: Use HITL for expensive operations
   - Acceptable operational overhead

3. **Model invocation billing duplication**
   - Crash during model call → retry may bill twice
   - Marginal cost: acceptable operational overhead
   - Mitigation: Provider idempotency keys if critical
   - Future: ModelInvocationStateStore if needed

4. **Process discovery**
   - No `listCheckpoints()` enumeration
   - No automatic recovery triggers
   - Future: M7 recovery orchestration

5. **Distributed coordination**
   - No claim/lease mechanism
   - Optimistic CAS only
   - Future: M8 distributed recovery

### 24.4 Next Phase Recommendations

**M7: Recovery Discovery & Orchestration**

Scope:
- Checkpoint enumeration API
- Orphaned process detection
- Automatic recovery triggers
- Recovery scheduling

**M7 should NOT:**
- ❌ Add general execution checkpoints
- ❌ Redesign checkpoint model
- ❌ Solve ChatMemory consistency (separate workstream)

**M8: Distributed Recovery**

Scope:
- Claim / lease protocol
- Multi-node ownership
- Fencing beyond version
- Distributed coordination primitives

---

## 25. CONCLUSION

### 25.1 Gate Verdict

**M6-T6: NO-GO — NO IMPLEMENTATION REQUIRED**

Reason: **Current architecture is correct and sufficient.**

### 25.2 Core Architectural Insight

> The absence of general execution checkpoints is not a gap — it is correct design.

**Arctra's checkpoint model reflects fundamental semantic boundaries:**
- Checkpoint = waiting for external decision (approval)
- Tool recovery = separate authority (InvocationStateStore)
- Conversation = external authority (ChatMemory)
- History = audit authority (ExecutionLedger)

**Each authority is single-purpose and non-overlapping.**

Adding general checkpoints would violate this principle by creating overlapping authorities.

### 25.3 Final Recommendation

**CLOSE M6-T6 WITHOUT IMPLEMENTATION.**

Document this architecture decision in:
- This gate document (completed)
- CURRENT-STATE.md (M6-T6 NO-GO noted)
- ARCHITECTURE-V7.md (if update needed)

**M6 milestone complete.**

---

## APPENDICES

### APPENDIX A: Sequence Diagrams

**A.1 Current Ordinary Execution (No HITL)**

```
User → Runtime: execute(def, req, ctx)
Runtime → Engine: execute(def, req, ctx)
Engine → ChatModel: call(prompt)
ChatModel → Engine: response with ToolCalls
Engine → Governance: evaluate(toolCalls)
Governance → Engine: ALLOW (all tools)
Engine → Tools: execute tools
Tools → Engine: results
Engine → ChatModel: continue(results)
ChatModel → Engine: final response
Engine → Runtime: AgentResult
Runtime → User: result

[NO CHECKPOINT CREATED - Ephemeral execution]
```

**A.2 Current Approval Execution (HITL)**

```
User → Runtime: execute(def, req, ctx)
Runtime → Engine: execute(def, req, ctx)
Engine → ChatModel: call(prompt)
ChatModel → Engine: response with ToolCalls
Engine → Governance: evaluate(toolCalls)
Governance → Engine: REQUIRE_APPROVAL
Engine → CheckpointStore: create(checkpoint v1)  [FIRST DURABLE WRITE]
Engine → Runtime: AgentResult(WAITING, process)
Runtime → User: result with process

[Checkpoint exists, process suspended]

User → Runtime: resumeProcess(procId, v1, APPROVE)
Runtime → DurableResumeCoordinator: resume(...)
Coordinator → CheckpointStore: load(procId)  [CHECK A]
Coordinator → InvocationStateStore: classify operations  [if cross-incarnation]
Coordinator → ResumedExecutionHandler: executeResume(...)
Handler → InvocationStateStore: recordIntent(attemptId)  [for each op]
Handler → Tools: execute tools
Handler → ChatModel: continue(results)
ChatModel → Handler: final response
Handler → Coordinator: COMPLETED outcome
Coordinator → CheckpointStore: deleteIfVersion(procId, v1)  [CHECK B]
Coordinator → Runtime: AgentResult
Runtime → User: result

[Checkpoint deleted, process completed]
```

### APPENDIX B: Authority Ownership Table

| Authority Domain | Durable Fact | Owner | Storage | Lifecycle |
|-----------------|--------------|-------|---------|-----------|
| **Process State** | Process waiting | Checkpoint existence | CheckpointStore | create → delete |
| **Operations** | Logical operation | PendingToolCall | CheckpointStore (in checkpoint) | materialization → completion |
| **Invocation** | Physical attempt intent | InvocationIntent | InvocationStateStore | recordIntent → (implicit) |
| **Recovery** | External outcome resolution | OperationResolution | InvocationStateStore | recordResolution → forever |
| **Conversation** | Message history | Messages | ChatMemory (Spring AI) | add → retention policy |
| **Evidence** | Execution proof | Evidence[] | Checkpoint (accumulated) | collect → checkpoint |
| **History** | Execution events | ExecutionRecord | ExecutionLedger | append → forever |
| **Binding** | Runtime resolution | RuntimeBinding (ephemeral) | Application resolver | resolve → release |

**No overlaps. No gaps.**

### APPENDIX C: Glossary

**processId:** Stable process identifier across entire lifecycle (suspension → resume → completion)

**checkpointVersion:** Suspension episode version (1 → 2 → 3...), CAS fencing token

**operationId:** Logical durable tool operation identity (framework-owned, stable across attempts)

**attemptId:** Physical invocation attempt identity (UUID, unique per execution try)

**executionEpoch:** Execution incarnation marker for restart detection (JVM instance ID + timestamp)

**SuspensionCheckpoint:** Durable state of WAITING process (approval suspension)

**InvocationStateStore:** Physical attempt recovery authority (intent + resolution)

**ChatMemory:** Conversation state authority (Spring AI contract, durability varies)

**ExecutionLedger:** Historical fact authority (append-only audit trail)

**CHECK A:** Pre-resume validation (load checkpoint, validate version, resolve binding)

**CHECK B:** Post-resume conditional transition (replaceIfVersion or deleteIfVersion)

**At-least-once:** Execution semantic where operations may execute multiple times but not zero times

---

**END OF M6-T6 ARCHITECTURE GATE**

**Decision: NO-GO — Current architecture is correct and sufficient.**

**M6 Milestone: READY FOR CLOSURE**