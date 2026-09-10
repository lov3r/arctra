# Arctra M5-T3 — Durable Recovery Architecture Gate

**Status:** ARCHITECTURE CONTRACT GATE  
**Date:** 2026-09-08  
**Author:** lov3r

---

## 1. Executive Summary

**CENTRAL QUESTION:** Who owns durable process recovery, what durable identity is sufficient, how is checkpoint lifecycle controlled, and how do we prevent stale/duplicate resume?

**KEY DECISIONS:**
- Recovery Owner: AgentRuntime ✅
- Runtime Binding Strategy: Explicit RuntimeBindingResolver ✅
- Checkpoint Semantic Model: Minimal framework-neutral DTO ✅
- Concurrency Scope: Conditional checkpoint transitions (at-least-once) ✅
- Approval Correlation: processId + checkpointVersion ✅

**GO/NO-GO:** ✅ **GO** (with M5-T3A corrections applied)

**IMPORTANT:** See M5-T3A-DURABLE-RECOVERY-CONTRACT-AMENDMENT.md for concurrency semantics corrections.

---

## 2. Frozen Inputs from M5-T1/T2

### 2.1 M5-T1 Frozen Semantics

✅ **Durable Process = durable recovery of SAFE WAITING suspension points**  
✅ **RUNNING is NOT checkpoint-resumable**  
✅ **Stable logical identity = processId**  
✅ **Process ≠ Session** (Process checkpoint references sessionId, does not contain full conversation)  
✅ **Durable Session/ChatMemory is prerequisite** for conversation continuity  
✅ **Do NOT persist Spring AI runtime objects** (AssistantMessage, ChatClientRequest, ToolCallback, closures)  
✅ **Governance-approved pending batch NOT re-evaluated on resume**  
✅ **At-least-once semantics** (crash may cause tool execution repeat)  
✅ **NOT exactly-once** (M5 does not guarantee)  

### 2.2 M5-T2 Frozen Proof

✅ **Protocol reconstruction from minimal DTO is PROVEN FEASIBLE**

Minimal durable state:
```java
record DurablePendingToolCall(
  String toolCallId,
  String toolName, 
  String arguments
)
```

✅ **AssistantMessage reconstructible from DTO**  
✅ **ToolCallingManager executes with NEW tool instances** (dispatch by name)  
✅ **ToolCallId preserved** across runtime boundary  
✅ **Evidence continuity** via merge (checkpoint evidences + new evidences)  
✅ **ChatMemory continuity** with standard persistence  
✅ **Re-suspension possible** (same processId, new checkpoint)  

### 2.3 M5-T2 Public API Delta

✅ **ZERO public API changes in PoC** (740 LoC test-only)

---

## 3. Corrections to M5-T2 Recommendations

### Correction A: Recovery Failure ≠ FAILED

**M5-T2 incorrectly suggested:**
```
CheckpointStore unavailable → Process FAILED
```

**CORRECTED:**

Storage/recovery infrastructure failure BEFORE continuation execution is NOT M4 Process FAILED.

Conceptually:
```
P100 durable state = WAITING
→ recovery attempt fails (store unavailable)
→ P100 remains logically WAITING
→ recovery attempt fails (retryable)
```

Do NOT introduce new ProcessStatus yet.

### Correction B: ChatMemory Not "Free Durability"

**M5-T2 proved:**  
IF session memory survives runtime boundary, Process recovery does not need to copy conversation messages.

**M5-T2 did NOT prove:**  
Every ChatMemory implementation is durable.

**CORRECTED:**  
Treat durable ChatMemory as **configuration/deployment prerequisite**, not automatic.

### Correction C: Evidence Durability Wording

**M5-T2 proved:**  
Evidence merge continuity.

**M5-T2 did NOT prove:**  
"Evidence does not need serialization."

**CORRECTED:**  
If accumulated Evidence is required after JVM loss, some durable representation IS required. M5-T3 must decide Evidence durable strategy.

### Correction D: AgentRuntime.resumeProcess() Not Frozen

**M5-T2 proposed:**
```java
AgentRuntime.resumeProcess(processId, signal)
```

This was a PoC **recommendation**, NOT a proven architecture contract.

**M5-T3 must decide recovery ownership.**

---

## 4. Current Responsibility Map

### Component Analysis

| Component | Current Responsibility | State Ownership | Knows processId? | Knows AgentDefinition? | Knows Tools? | Knows Checkpoint? | Durable Recovery Impact |
|-----------|----------------------|-----------------|------------------|----------------------|--------------|-------------------|------------------------|
| **Agent** | Stateless invocation handle | None (stateless) | ❌ No | ✅ Yes (bound) | ❌ No | ❌ No | None - remains stateless |
| **AgentRuntime** | Agent factory, execution delegation | None | ❌ No | ✅ Yes (parameter) | ❌ No | ❌ No | **CANDIDATE** - would add process lookup/resume |
| **AgentExecutionEngine** | Execution strategy | None | ❌ No | ✅ Yes (parameter) | ✅ Yes (owns) | ❌ No | **CANDIDATE** - would add protocol reconstruction |
| **AgentProcess** | Lifecycle handle | processId, status, continuation | ✅ Yes (owns) | ❌ No | ❌ No | ❌ No | **CANDIDATE** - would become recoverable |
| **DefaultAgentProcess** | In-memory lifecycle | processId, status, closure, result | ✅ Yes (owns) | ❌ No | ❌ No | ❌ No | Major change - closure → checkpoint |
| **SpringAiToolCallingEngine** | Spring AI execution | ChatModel, tools, memory, policy | ❌ No | ✅ Yes (exec param) | ✅ Yes (owns) | ❌ No | Would add protocol reconstruction method |
| **GovernanceToolCallingAdvisor** | Governance evaluation | Thread-local evidences | ❌ No | ❌ No | ❌ No | ❌ No | Minimal - already creates SuspensionState |
| **ChatMemory** | Conversation persistence | Session messages | ❌ No | ❌ No | ❌ No | ❌ No | None - prerequisite, not participant |

### Current Execution Flow

```
User Request
  ↓
Agent.execute(request, context)
  ↓
AgentRuntime.execute(definition, request, context)
  ↓
AgentExecutionEngine.execute(definition, request, context)
  ↓
[Spring AI ChatClient + Advisors]
  ↓
GovernanceToolCallingAdvisor → REQUIRE_APPROVAL
  ↓
throw ToolApprovalRequiredSignal
  ↓
SpringAiToolCallingEngine catches signal
  ↓
ProcessFactory.createSuspended(closure)
  ↓
return AgentResult(content, evidences, process)
```

**Key Observations:**
- AgentRuntime does NOT currently know about processId
- AgentDefinition is passed as parameter (not stored anywhere)
- Tools are owned by Engine
- No central process registry exists
- No runtime binding registry exists

---

## 5. Recovery Ownership Analysis

### CANDIDATE A: AgentRuntime Owns Recovery

**Potential API:**
```java
public interface AgentRuntime {
  Agent agent(AgentDefinition definition);
  AgentResult execute(AgentDefinition definition, AgentRequest request, AgentExecutionContext context);
  
  // NEW M5 API
  AgentResult resumeProcess(String processId, ContinuationSignal signal);
}
```

**Advantages:**
- ✅ Central entry point (consistent with execute)
- ✅ Application-facing API (user calls runtime for both exec and resume)
- ✅ Can coordinate checkpoint store + engine binding

**Disadvantages:**
- ❌ AgentRuntime becomes multi-purpose: factory + executor + process coordinator
- ❌ Requires runtime to maintain process registry (new responsibility)
- ❌ Requires runtime to maintain engine/definition binding registry
- ❌ Violates single responsibility principle
- ❌ Makes AgentRuntime stateful (currently stateless except engine reference)

**Responsibility Overload Risk:** HIGH

AgentRuntime would own:
1. Agent factory ✅ (current)
2. Execution delegation ✅ (current)
3. **Process lookup** ❌ (new)
4. **Checkpoint coordination** ❌ (new)
5. **Runtime binding resolution** ❌ (new)
6. **Resume dispatch** ❌ (new)

This is 4 new responsibilities on top of 2 existing.

### CANDIDATE B: AgentProcess Owns Recovery

**Potential API:**
```java
// After JVM restart, no Process object exists
// Would require ProcessResolver or ProcessRegistry

ProcessHandle handle = processRegistry.resolve(processId);
AgentResult result = handle.resume(signal);
```

**Advantages:**
- ✅ Process is already the lifecycle abstraction
- ✅ Semantically natural (process resumes itself)

**Disadvantages:**
- ❌ After JVM restart, no Process object exists
- ❌ Requires introducing ProcessRegistry/ProcessResolver (new concept)
- ❌ Current AgentProcess is a suspended execution handle, not a process manager
- ❌ Would distort AgentProcess semantics (lifecycle entity → registry key)
- ❌ Who owns the registry? (pushes problem elsewhere)
- ❌ Process doesn't know AgentDefinition or runtime binding

**Semantic Mismatch Risk:** HIGH

Current AgentProcess semantics:
```
AgentProcess = handle for ONE suspended execution lifecycle
```

Recovery semantics would require:
```
AgentProcess = identifier + registry lookup + binding resolution + resume coordinator
```

These are conflicting concerns.

### CANDIDATE C: AgentExecutionEngine Owns Recovery

**Potential API:**
```java
public interface AgentExecutionEngine {
  AgentResult execute(AgentDefinition definition, AgentRequest request, AgentExecutionContext context);
  
  // NEW M5 API
  AgentResult resumeProcess(String processId, ContinuationSignal signal);
}
```

**Advantages:**
- ✅ Engine already owns tools, execution strategy
- ✅ Engine is where protocol reconstruction happens
- ✅ Minimal API surface change

**Disadvantages:**
- ❌ Engine doesn't know which AgentDefinition to use
- ❌ Engine doesn't have checkpoint store access
- ❌ Pushes persistence concerns into execution strategy
- ❌ Makes engine stateful (currently stateless except tool/memory references)
- ❌ Different engine instances can't share checkpoint store
- ❌ Violates execution strategy abstraction

**Cohesion Risk:** MEDIUM-HIGH

Engine is execution strategy, not process coordinator.

### CANDIDATE D: Dedicated Recovery Seam

**Conceptual (NOT implementation yet):**
```java
// New concept
interface ProcessRecoveryCoordinator {
  AgentResult resumeProcess(String processId, ContinuationSignal signal);
}

// Implementation delegates to:
// - CheckpointStore (load checkpoint)
// - RuntimeBindingResolver (find engine/definition)
// - Engine (protocol reconstruction + execution)
```

**Advantages:**
- ✅ Clear separation of concerns
- ✅ Recovery coordination is explicit responsibility
- ✅ Does not overload existing abstractions
- ✅ Can encapsulate checkpoint + binding + engine coordination
- ✅ Testable in isolation

**Disadvantages:**
- ❌ New abstraction (added complexity)
- ❌ Who owns/creates the coordinator?
- ❌ Requires RuntimeBindingResolver (another new concept)
- ❌ More indirection

**Complexity vs Clarity Trade-off:** Needs evaluation

### CANDIDATE E: Application-Managed Recovery

**Conceptual:**
```java
// Application does:
CheckpointStore store = ...;
SuspensionCheckpoint checkpoint = store.load(processId);

AgentDefinition definition = resolveDefinition(checkpoint.agentName);
AgentExecutionEngine engine = getEngine(definition);

// Reconstruct protocol
AssistantMessage rebuilt = rebuildFromCheckpoint(checkpoint);

// Execute
AgentResult result = engine.continueWithReconstructedProtocol(rebuilt, checkpoint, signal);
```

**Advantages:**
- ✅ Maximum flexibility
- ✅ No framework registry needed
- ✅ Application controls all binding

**Disadvantages:**
- ❌ Makes Arctra durability too low-level
- ❌ Exposes internal reconstruction details
- ❌ Every application reimplements recovery coordination
- ❌ No guidance on correct recovery lifecycle
- ❌ High error-prone surface

**Usability Risk:** CRITICAL

This is NOT a framework - this is a toolkit.

---

## 6. Runtime Binding Problem

### The Core Issue

After restart, checkpoint says:
```
processId = P100
agentBindingKey = ???
```

How does Arctra reconstruct:
- AgentDefinition
- Execution Engine
- Tools
- GovernancePolicy  
- ChatMemory binding

### AgentDefinition.name Sufficiency Analysis

**Current AgentDefinition contract:**
```java
public record AgentDefinition(String name, String description) {
  public AgentDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name cannot be blank");
    }
  }
}
```

**NO UNIQUENESS CONSTRAINT IN CONTRACT.**

**Can two different runtime configurations have the same AgentDefinition.name?**

**Answer: YES** - nothing prevents:

```java
// Production environment
AgentDefinition("incident-agent", "Investigates incidents")
+ GPT-4 model
+ prod tools (real database restart)
+ strict governance
+ Redis ChatMemory

// Test environment  
AgentDefinition("incident-agent", "Investigates incidents")
+ test model (mocked)
+ fake tools (no-op)
+ permissive governance
+ in-memory ChatMemory
```

**SAME AgentDefinition.name, DIFFERENT runtime binding.**

**Conclusion:**  
❌ **AgentDefinition.name is NOT sufficient as durable runtime identity.**

### Binding vs Definition Distinction

**AgentDefinition identity:**
- Name + description (template)
- Stable across environments

**Runtime Binding identity:**
- AgentDefinition + Model + Tools + Governance + Memory + Environment
- May differ per tenant, per environment, per deployment

**Checkpoint needs:**
```
Resume the same LOGICAL EXECUTION ENVIRONMENT
NOT merely same prompt template
```

**Example:**
```
Checkpoint says: "incident-agent"

Which binding?
- prod incident-agent with real tools?
- test incident-agent with mocks?
- tenant-A incident-agent?
- tenant-B incident-agent?
```

**AMBIGUOUS.**

### Candidate Durable Binding Keys

**Option 1: agentName (definition name)**
- ❌ Not unique across environments
- ❌ Not unique across tenants
- ❌ Requires external disambiguation

**Option 2: definitionId (explicit unique ID)**
- Still doesn't capture tools/policy/model binding
- Definition is template, not runtime config

**Option 3: runtimeBindingKey (application-defined)**
```java
record SuspensionCheckpoint(
  String processId,
  String runtimeBindingKey,  // e.g., "prod.incident-agent.v2"
  ...
)
```
- ✅ Application controls uniqueness
- ✅ Can encode environment + version
- ❌ Requires application binding registry

**Option 4: Composite binding (agentName + environment + tenant)**
```java
record RuntimeBinding(
  String agentName,
  String environment,
  String tenantId
)
```
- ✅ Structured
- ❌ Still requires resolution logic
- ❌ What if model version matters?

**Option 5: No durable binding - callback-based recovery**
```java
interface RuntimeBindingResolver {
  AgentExecutionEngine resolveEngine(String processId, SuspensionCheckpoint checkpoint);
  AgentDefinition resolveDefinition(String processId, SuspensionCheckpoint checkpoint);
}
```
- ✅ Maximum flexibility
- ✅ Application provides resolution logic
- ❌ Every recovery requires application callback

---

## 7. Checkpoint Semantic Model

### Definition

**Checkpoint is NOT:**
- ❌ AgentProcess object
- ❌ Conversation history
- ❌ Workflow snapshot  
- ❌ JVM continuation
- ❌ Event stream

**DEFINITION:**

> A durable record of a SAFE WAITING resume point sufficient to reconstruct the next continuation of one logical AgentProcess.

**Properties:**
- Immutable snapshot
- Framework-neutral types
- Sufficient for protocol reconstruction
- Does NOT contain runtime objects
- Represents WAITING state only

---

## 8. Checkpoint Field Ownership

| Field | Status | Rationale |
|-------|--------|-----------|
| **schemaVersion** | REQUIRED | Checkpoint format evolution - forward/backward compatibility |
| **processId** | REQUIRED | Stable process identity - core recovery key |
| **processStatus** | NOT REQUIRED | If only WAITING persists, status is redundant - checkpoint existence = WAITING |
| **runtimeBindingKey** | REQUIRED | Resolve engine/definition/tools - see §6 analysis |
| **sessionId** | REQUIRED | ChatMemory restoration - Process ≠ Session but references it |
| **pendingToolCalls** | REQUIRED | Protocol reconstruction - M5-T2 proved minimal DTO sufficient |
| **accumulatedEvidence** | REQUIRED | Evidence merge continuity - checkpoint evidences + new evidences |
| **suspensionReason** | OPTIONAL | Diagnostic/audit - not required for recovery correctness |
| **createdAt** | OPTIONAL | Audit - not required for recovery |
| **suspendedAt** | DEFERRED | No M5 consumer yet - timeout/GC out of scope |
| **checkpointVersion** | REQUIRED | Duplicate resume prevention - see §16 analysis |

**NO FIELD WITHOUT A CONSUMER** - all REQUIRED fields have proven consumers from M5-T1/T2.

**Minimal Checkpoint Schema:**

```java
record SuspensionCheckpoint(
  String schemaVersion,                 // "1.0" - format evolution
  String processId,                     // Stable identity
  String runtimeBindingKey,             // Runtime resolution
  String sessionId,                     // ChatMemory binding
  List<PendingToolCall> pendingBatch,   // Protocol reconstruction
  List<Evidence> accumulatedEvidences,  // Merge context
  long checkpointVersion                // Episode correlation + conditional CAS
  // suspendedAt: DEFERRED - no current M5 consumer
)

record PendingToolCall(
  String toolCallId,
  String toolName,
  String arguments
)
```

**ProcessStatus NOT included** - checkpoint existence semantically means WAITING. Terminal states (COMPLETED/FAILED) delete checkpoint.

---

## 9. Evidence Durable Representation

**Current Evidence API:**
```java
public record Evidence(String source, String content) {
  // Framework-neutral
  // Immutable
  // No runtime objects
}
```

**Analysis:**

✅ **Framework-neutral** - no Spring AI types  
✅ **Immutable** - record  
✅ **Safe as persistence contract** - only strings  
❌ **Does NOT contain runtime objects**  
✅ **Stable scalar/string data only**  
✅ **Public contract implies serialization stability**  

**DECISION: Persist Evidence directly.**

Evidence is ALREADY a suitable durable type. No checkpoint-specific representation needed.

**Checkpoint stores:**
```java
List<Evidence> accumulatedEvidences
```

**After recovery:**
```java
List<Evidence> merged = concat(checkpoint.accumulatedEvidences, newToolEvidences);
```

**Risk:** If Evidence evolves to contain complex types, checkpoint schema breaks. Mitigation: schemaVersion + careful Evidence evolution governance.

---

## 10. Checkpoint Lifecycle - Initial Suspension

**Execution Flow:**

```
Agent executing
→ Model generates ToolCall
→ Governance: REQUIRE_APPROVAL
→ [CHECKPOINT CREATION POINT]
→ Build checkpoint DTO
→ CheckpointStore.save(checkpoint)
→ [SUSPENSION SUCCESS POINT]
→ Return AgentResult(WAITING, process)
```

**CRITICAL QUESTION:** Must checkpoint write succeed BEFORE returning WAITING?

**Option A: Durability-First (Strong Consistency)**
```
IF checkpoint.save() FAILS:
  → throw CheckpointWriteException
  → Process does NOT transition to WAITING
  → User does NOT receive suspended result
  → No in-memory Process created
```

**Option B: Best-Effort Durability**
```
IF checkpoint.save() FAILS:
  → log error
  → Return WAITING Process anyway
  → Process exists in-memory but NOT durable
  → Resume works if same JVM, fails after restart
```

**Option C: Dual-Mode**
```
IF durability configured + save fails:
  → throw exception (Option A)
  
IF durability NOT configured:
  → ephemeral process (M4 behavior)
```

**RECOMMENDATION: Option A (Durability-First)**

**Rationale:**
- Durability is correctness requirement, not performance optimization
- Returning WAITING without successful checkpoint = lying to user
- User expects suspended process to survive restart
- Better to fail fast than silently lose durability

**Exception handling:**
```java
try {
  checkpointStore.save(checkpoint);
} catch (CheckpointStoreException e) {
  // Process did NOT suspend durably
  throw new ProcessSuspensionException(
    "Failed to persist suspension checkpoint - process not suspended", e);
}
```

Process does NOT enter WAITING if checkpoint fails.

---

## 11. Checkpoint Lifecycle - Resume

**Execution Flow:**

```
resumeProcess(processId, signal)
→ CheckpointStore.load(processId)
→ RuntimeBindingResolver.resolve(checkpoint.runtimeBindingKey)
→ Reconstruct protocol from checkpoint
→ [CHECKPOINT STILL EXISTS]
→ Execute tool via ToolCallingManager
→ Collect new evidences
→ Continue to model
→ IF completed:
    → CheckpointStore.delete(processId)
    → Return AgentResult(COMPLETED)
   ELSE IF suspended again:
    → CheckpointStore.save(newCheckpoint) // Overwrites old
    → Return AgentResult(WAITING, process)
```

**CRITICAL: Keep old checkpoint until safe point**

**Options:**

**A. Delete before execution**
```
load checkpoint
→ delete checkpoint
→ execute tools
→ [CRASH HERE = lost]
```
❌ Tool execution lost

**B. Mark consumed before execution**
```
load checkpoint
→ mark consumed (checkpoint.consumed = true)
→ execute tools
→ delete after completion
```
✅ Idempotent (consumed checkpoint not resumable)
❌ Requires checkpoint mutation

**C. Keep until next safe checkpoint**
```
load checkpoint version N
→ execute tools
→ IF completed:
    delete checkpoint N
  ELSE IF suspended:
    save checkpoint N+1 (atomic replace)
```
✅ At-least-once safe
✅ Crash during execution → old checkpoint still exists → retryable
❌ Duplicate resume possible (see §16)

**RECOMMENDATION: Option C + Optimistic Concurrency**

Checkpoints remain until:
- COMPLETED → delete
- FAILED → delete  
- Re-suspended → replaced by new version

**At-least-once semantics honored.**

---

## 12. Checkpoint Lifecycle - Re-Suspension

**Trace:**

```
Checkpoint CP1(P100, version=1, toolCallId=call_A)
→ Resume approved
→ Execute Tool A
→ Model continues
→ Model generates Tool B (REQUIRE_APPROVAL)
→ [SECOND SUSPENSION]
→ Checkpoint CP2(P100, version=2, toolCallId=call_B)
```

**CRITICAL: CP2 must invalidate CP1**

**Options:**

**A. Overwrite by processId**
```
CheckpointStore.save(processId=P100, checkpoint=CP2)
→ CP1 automatically replaced
```
✅ Simple
❌ Race condition (see §16)

**B. Versioned update**
```
CheckpointStore.update(
  processId=P100,
  expectedVersion=1,
  newCheckpoint=CP2 with version=2
)
→ Atomic: only succeeds if current version=1
```
✅ Safe against concurrent resume
✅ Detects stale checkpoint
❌ Requires CAS in store

**C. Delete-then-insert**
```
CheckpointStore.delete(P100, expectedVersion=1)
CheckpointStore.save(CP2)
```
❌ Not atomic
❌ Crash between delete/save = lost

**RECOMMENDATION: Option B (Versioned Update)**

**Storage port requires:**
```java
boolean updateCheckpoint(String processId, long expectedVersion, SuspensionCheckpoint newCheckpoint);
// Returns true if successful (version matched)
// Returns false if version mismatch (concurrent modification)
```

---

## 13. Checkpoint Lifecycle - Completion

**Trace:**

```
Checkpoint exists: P100 version=3 WAITING
→ Resume → tool execution → model completion
→ [TERMINAL STATE]
→ Must invalidate checkpoint
```

**Options:**

**A. Delete checkpoint**
```
CheckpointStore.delete(P100)
```
✅ Clean
✅ No stale data
❌ No audit trail

**B. Tombstone as COMPLETED**
```
CheckpointStore.save(P100, status=COMPLETED, terminal=true)
```
❌ Contradicts "checkpoint = WAITING only" semantic
❌ Audit is separate concern

**C. Delete + separate audit**
```
CheckpointStore.delete(P100)
AuditLog.record(P100, COMPLETED)
```
✅ Separation of concerns
✅ Checkpoint store is recovery only
✅ Audit is separate system

**RECOMMENDATION: Option A (Delete)**

**Rationale:**
- Checkpoint store is for ACTIVE recovery, not historical audit
- COMPLETED process has no recovery need
- Audit is separate concern (M5 out of scope)
- Simpler storage semantics

**Implementation:**
```java
if (result.isCompleted()) {
  checkpointStore.delete(processId);
  return result;
}
```

Stale checkpoint MUST NOT remain resumable after completion.

---

## 14. Checkpoint Lifecycle - Failure

**Trace:**

```
Checkpoint exists: P100 WAITING
→ Resume → reconstruction succeeds
→ Tool execution throws
→ [TERMINAL FAILURE]
→ Process → FAILED
```

**Old WAITING checkpoint must not remain resumable.**

**DECISION: Same as Completion - Delete**

```java
if (executionFailed) {
  checkpointStore.delete(processId);
  throw originalException;
}
```

FAILED is terminal. Checkpoint deleted.

---

## 15. Checkpoint Lifecycle - Rejection/Denial

**Trace:**

```
Checkpoint: P100 Tool A pending
→ Resume with DENIED signal
→ Construct rejection ToolResponseMessage
→ Model continues with rejection
→ May complete OR suspend again
```

**Checkpoint handling:**

```java
if (signal.approved() == false) {
  // Execute denial protocol
  AgentResult result = resumeDenied(...);
  
  if (result.isCompleted()) {
    checkpointStore.delete(P100);
  } else if (result.isSuspended()) {
    checkpointStore.update(P100, expectedVersion, newCheckpoint);
  }
}
```

**Same lifecycle as approval** - checkpoint replaced on re-suspension, deleted on completion.

---

## 16. Concurrency - The Double-Resume Problem

**CRITICAL CORRECTNESS ISSUE**

**M4 Protection:**
```java
// DefaultAgentProcess - JVM-local CAS
if (!status.compareAndSet(ProcessStatus.WAITING, ProcessStatus.RUNNING)) {
  throw new IllegalStateException("Already resumed");
}
```

✅ Works within single JVM

**M5 Problem:**

```
Checkpoint: P100 version=7 WAITING

Node A: resumeProcess(P100, APPROVED)
→ load checkpoint version 7
→ execute Tool A

Node B: resumeProcess(P100, APPROVED)  
→ load checkpoint version 7
→ execute Tool A

BOTH execute same tool!
```

JVM-local CAS does NOT protect across nodes/processes.

**This is a REAL durable-process correctness problem.**

---

## 17. Concurrency Solutions

**Option A: No Concurrency Guarantee**

M5 scope: single-node durable restart only.

Document:
> M5 does NOT support concurrent multi-node resume. Duplicate resume may execute tools multiple times. Application must ensure single resume per checkpoint.

✅ Simplest
❌ Dangerous in production
❌ Requires external coordination

**Option B: Conditional Checkpoint Transition (Recommended)**

```java
SuspensionCheckpoint checkpoint = store.load(processId);
long currentVersion = checkpoint.checkpointVersion();

// Pre-execution validation (CHECK A)
if (currentVersion != requestedVersion) {
  throw new StaleApprovalException("Checkpoint version changed");
}

// Execute continuation (side effects occur here)
AgentResult result = ...;

// Post-execution conditional transition (CHECK B)
if (result.isCompleted()) {
  boolean deleted = store.deleteIfVersion(processId, currentVersion);
  if (!deleted) {
    throw new ConcurrentCheckpointModificationException();
  }
} else if (result.isSuspended()) {
  boolean replaced = store.replaceIfVersion(processId, currentVersion, newCheckpoint);
  if (!replaced) {
    throw new ConcurrentCheckpointModificationException();
  }
}
```

**Checkpoint version increments:**
```
Initial suspension: version=1
First re-suspension: version=2
Second re-suspension: version=3
```

**Only ONE checkpoint state transition succeeds.**

✅ Single-winner checkpoint commit
✅ No distributed locks
✅ Protects checkpoint state integrity
❌ Does NOT prevent duplicate tool execution (see M5-T3A)

**Option C: Pessimistic Lock**

```java
boolean claimed = store.claimCheckpoint(processId, ownerId, leaseDuration);
if (!claimed) {
  throw new ConcurrentResumeException();
}

// Execute
// Release or auto-expire
```

❌ Requires distributed locking
❌ Lease expiry complexity
❌ Owner death recovery complexity

**RECOMMENDATION: Option B (Conditional Checkpoint Transition)**

**CRITICAL CLARIFICATION (M5-T3A):**

Post-execution CAS protects **checkpoint state transitions**, NOT **tool execution**.

If same checkpoint concurrently resumed:
- Tool may execute multiple times ✗
- Only one checkpoint transition commits ✓

M5 provides at-least-once execution, NOT exactly-once.

---

## 18. Concurrency - Crash After Claim

**Problem:**

```
Node A loads checkpoint version 7
→ Executes tool (side effect!)
→ [CRASH before updating checkpoint]
```

Checkpoint version=7 still exists. Retryable?

**YES - at-least-once semantics**

Tool execution may repeat. This is M5's explicit semantic choice (§2.1).

**Mitigation: Tools must be idempotent or accept duplicate execution.**

Framework does NOT guarantee exactly-once.

---

## 19. M5 Concurrency Scope - DECISION

**CHOSEN SCOPE:** Conditional checkpoint transitions with at-least-once execution

**M5 supports:**
✅ Shared checkpoint store across nodes
✅ Single-winner checkpoint commit (CAS-based)
✅ At-least-once execution
✅ Stale checkpoint protection

**M5 does NOT support:**
❌ Exactly-once execution
❌ Concurrent resume execution exclusion
❌ Distributed transaction coordination
❌ Pre-execution claim/lease/lock
❌ Zero tool execution waste on collision

**Contract:**
> M5 guarantees conditional checkpoint state transitions, NOT execution exclusivity.
> 
> Multiple concurrent resume attempts for the same checkpoint episode:
> - MAY execute tools multiple times (at-least-once)
> - ONLY ONE checkpoint transition commits (single-winner)
> - Stale executions cannot corrupt checkpoint state
> 
> Applications SHOULD dispatch at most one resume per checkpoint episode.
> Tools SHOULD be idempotent or accept duplicate execution.

**This keeps M5 as a durable restart framework, NOT a distributed scheduler.**

---

## 20. Approval Correlation - CRITICAL PROBLEM

**The Issue:**

```
P100 suspends with Tool A → generates approval request REQ1
P100 resumes, suspends again with Tool B → generates approval request REQ2

Delayed approval for REQ1 arrives AFTER REQ2 exists.
```

If approval only carries `processId`:
```
resumeProcess(P100, APPROVED)
```

**Which suspension episode does it approve?**

**Current M4 API:**
```java
process.resume(ApprovalSignal)
```

Works because Java object = specific suspension episode.

**M5 challenge:**

After JVM restart, `processId` alone is ambiguous if multiple suspension episodes exist over time.

**M5 Resolution requires correlation:**

**Option 1: Approval includes checkpointVersion**
```java
resumeProcess(processId, checkpointVersion, signal)
```

✅ Explicit correlation
❌ Exposes internal versioning

**Option 2: Approval includes suspensionId**
```java
record SuspensionCheckpoint(
  String processId,
  String suspensionId,  // UUID per suspension episode
  ...
)

resumeProcess(suspensionId, signal)
```

✅ Explicit episode identity
✅ Opaque to application
❌ processId + suspensionId redundant?

**Option 3: Opaque resumeToken**
```java
// At suspension, framework generates token
String resumeToken = framework.generateResumeToken(checkpoint);

// Approval carries token
resumeProcess(resumeToken, signal)

// Token encodes: processId + checkpointVersion + signature
```

✅ Opaque
✅ Can include security/validation
❌ Token management complexity

**Option 4: Stale approval is application concern**
```
resumeProcess(processId, signal)
→ Always resumes CURRENT checkpoint
→ If checkpoint changed, old approval applies to wrong episode
→ Application must track approval-to-episode correlation externally
```

❌ Framework does not solve the problem

**RECOMMENDATION: Option 1 (checkpointVersion in resume API)**

**Rationale:**
- Simplest
- Explicit
- No token management
- checkpointVersion is already required for optimistic concurrency

**Updated API:**
```java
AgentResult resumeProcess(String processId, long checkpointVersion, ContinuationSignal signal);
```

**Approval request must include:**
```java
record ApprovalRequest(
  String processId,
  long checkpointVersion,
  List<PendingToolCall> pendingBatch,
  Instant requestedAt
)
```

**Resume validates:**
```java
SuspensionCheckpoint current = store.load(processId);
if (current.checkpointVersion() != expectedVersion) {
  throw new StaleApprovalException(
    "Checkpoint version mismatch - approval is for stale suspension episode");
}
```

---

## 21. Public API Recommendation

**RECOMMENDED M5 PUBLIC API:**

```java
public interface AgentRuntime {
  // M4 APIs (unchanged)
  Agent agent(AgentDefinition definition);
  AgentResult execute(AgentDefinition definition, AgentRequest request, AgentExecutionContext context);
  
  // NEW M5 API
  AgentResult resumeProcess(String processId, long checkpointVersion, ContinuationSignal signal);
}
```

**Rationale:**
- AgentRuntime is already primary entry point
- Consistent with execute() (same return type)
- processId + checkpointVersion solves correlation problem
- ContinuationSignal unchanged from M4

**Recovery ownership:** AgentRuntime

**Why not dedicated ProcessRecoveryCoordinator?**
- Would require: "Who creates it? Where does it live?"
- AgentRuntime is already the coordinator
- One entry point simpler than two

**Limitation:** Runtime becomes stateful (needs CheckpointStore + binding registry)

This is ACCEPTABLE because:
- Runtime already has Engine reference (stateful)
- Recovery is core runtime concern, not optional addon
- Keeps M3 boundary (application → Runtime → Engine)

---

## 22. Storage Port Derivation

**Required Operations (derived from lifecycle):**

```java
public interface CheckpointStore {
  
  // Initial suspension - must fail if processId exists
  void create(SuspensionCheckpoint checkpoint);
  
  // Resume lookup
  Optional<SuspensionCheckpoint> load(String processId);
  
  // Re-suspension - conditional replacement
  boolean replaceIfVersion(String processId, long expectedVersion, SuspensionCheckpoint replacement);
  
  // Completion/failure - conditional invalidation
  boolean deleteIfVersion(String processId, long expectedVersion);
}
```

**Minimal operations - EVERY method maps to lifecycle transition.**

**NOT CRUD** - derived from actual consumers.

**REMOVED operations (M5-T3A):**
- ❌ `save()` - blind overwrite dangerous
- ❌ `delete()` - blind delete dangerous  
- ❌ `updateIfVersion()` - renamed to `replaceIfVersion` for clarity

---

## 23. Module Boundary

**Type Ownership:**

| Type | Module |
|------|--------|
| `SuspensionCheckpoint` | arctra-core |
| `PendingToolCall` | arctra-core |
| `CheckpointStore` | arctra-core (interface) |
| `InMemoryCheckpointStore` | arctra-core or test utilities |
| `JdbcCheckpointStore` | arctra-jdbc or separate module |
| `RuntimeBindingResolver` | arctra-core (interface) |
| Protocol reconstruction logic | arctra-runtime-react (Spring AI specific) |

**Rationale:**
- Checkpoint semantic model is framework-neutral → core
- Storage implementation is infrastructure → separate
- Protocol reconstruction is execution-engine-specific → engine module

---

## 24. Backward Compatibility

**M4 in-memory flow MUST continue to work:**

```java
AgentResult result = agent.execute(request, context);
if (result.isSuspended()) {
  AgentProcess process = result.process();
  AgentResult final = process.resume(ApprovalSignal);
}
```

**M5 adds parallel path:**

```java
// After JVM restart
AgentResult recovered = runtime.resumeProcess(processId, version, signal);
```

**Durability mode selection:**

```java
// Without CheckpointStore configured
AgentRuntime runtime = new DefaultAgentRuntime(engine);
→ M4 behavior (ephemeral process)

// With CheckpointStore configured  
AgentRuntime runtime = new DefaultAgentRuntime(engine, checkpointStore, bindingResolver);
→ M5 behavior (durable process)
```

**No forced migration** - applications can adopt M5 durability when ready.

---

## 25. RECOMMENDED ARCHITECTURE - MODEL D

**Model D: AgentRuntime-Owned Recovery with Minimal Extensions**

### Components

```
Application
  ↓
AgentRuntime (NEW: resumeProcess API)
  ↓
CheckpointStore (NEW: storage port)
  ↓
RuntimeBindingResolver (NEW: binding callback)
  ↓
AgentExecutionEngine (NEW: reconstructWithCheckpoint package-private method)
```

### Why This Model?

✅ AgentRuntime is already primary entry point  
✅ Keeps M3 boundary clean  
✅ Single public API addition  
✅ Binding resolution is explicit (not hidden registry)  
✅ Engine owns protocol reconstruction (correct responsibility)  
✅ Checkpoint store is pluggable  
✅ Backward compatible  

### Control Flow

**Initial Suspension:**
```
Engine.execute()
→ Governance REQUIRE_APPROVAL
→ Build checkpoint
→ checkpointStore.save(checkpoint)
→ Return AgentResult(WAITING, process)
```

**Recovery:**
```
runtime.resumeProcess(processId, version, signal)
→ checkpoint = checkpointStore.load(processId)
→ binding = bindingResolver.resolve(checkpoint)
→ engine = binding.engine()
→ engine.reconstructAndResume(checkpoint, binding.definition(), binding.context(), signal)
→ Return AgentResult
```

**Re-Suspension:**
```
Engine.reconstructAndResume()
→ Execute continuation
→ Model requests another approval
→ Build new checkpoint (version++)
→ checkpointStore.updateIfVersion(processId, oldVersion, newCheckpoint)
→ Return AgentResult(WAITING, process)
```

### Public API Impact

**1 new Runtime method:**
```java
AgentResult resumeProcess(String processId, long checkpointVersion, ContinuationSignal signal);
```

**2 new SPIs:**
```java
interface CheckpointStore { ... }
interface RuntimeBindingResolver { ... }
```

**Total: 3 new public types**

---

## 26. Decision Matrix

| Criterion | Model A (Runtime-Owned) | Model B (Process-Owned) | Model C (Engine-Owned) | Model D (Hybrid) | Application-Managed |
|-----------|------------------------|------------------------|----------------------|------------------|-------------------|
| **Responsibility Cohesion** | Medium | Low | Low | High | N/A |
| **Public API Clarity** | High | Low | Medium | High | Low |
| **M3 Boundary Compatible** | Yes | No | Yes | Yes | No |
| **Runtime Binding Clarity** | Explicit | Unclear | Implicit | Explicit | Manual |
| **Checkpoint Ownership** | Runtime | Registry | Engine | Runtime | Application |
| **Concurrency Handling** | Runtime | Store | Engine | Runtime+Store | Application |
| **Re-suspension Safety** | Yes | Unclear | Yes | Yes | Manual |
| **Backend Neutrality** | Yes (SPI) | Yes | No | Yes (SPI) | Yes |
| **Implementation Complexity** | Medium | High | Low | Medium | Low (but transfers to app) |
| **Premature Abstraction Risk** | Low | High | Low | Low | N/A |
| **Recommended** | ❌ | ❌ | ❌ | ✅ | ❌ |

**Model D wins** - balanced cohesion + explicit binding + minimal API expansion.

---

## 27. Open Contract Questions

**NONE** - All critical questions resolved:

✅ Recovery owner: AgentRuntime  
✅ Runtime binding: Explicit resolver callback  
✅ Checkpoint model: Framework-neutral DTO with minimal fields  
✅ Evidence strategy: Direct persistence  
✅ Checkpoint lifecycle: Defined for all transitions  
✅ Concurrency scope: Single-winner optimistic concurrency  
✅ Approval correlation: checkpointVersion in resume API  
✅ Storage port: Minimal operations derived from lifecycle  

---

## 28. GO / NO-GO DECISION

**GO ✅**

**Confidence: HIGH**

**Rationale:**

1. ✅ M5-T2 proved protocol reconstruction feasibility
2. ✅ All ownership questions resolved (AgentRuntime)
3. ✅ Binding problem solved (explicit RuntimeBindingResolver)
4. ✅ Checkpoint semantic model is minimal and proven
5. ✅ Lifecycle for all transitions defined
6. ✅ Concurrency handled (optimistic, single-winner)
7. ✅ Approval correlation solved (checkpointVersion)
8. ✅ Public API impact minimal (1 method + 2 SPIs)
9. ✅ Backward compatible (M4 ephemeral mode preserved)
10. ✅ M4 frozen invariants honored

**Remaining Risks:**

⚠️ **Tool idempotency** - at-least-once may cause duplicate execution (application concern)  
⚠️ **Storage backend maturity** - production store needs testing (implementation risk, not design risk)  
⚠️ **Binding registry complexity** - application must implement RuntimeBindingResolver (guidance needed)

**These are manageable implementation/deployment risks, NOT architecture blockers.**

---

## 29. Recommended M5-T4 Scope

**M5-T4: Minimal Checkpoint Infrastructure**

**Goals:**
1. Implement CheckpointStore SPI
2. Implement InMemoryCheckpointStore (optimistic concurrency)
3. Implement RuntimeBindingResolver SPI
4. Add AgentRuntime.resumeProcess() API
5. Add package-private Engine.reconstructAndResume() method
6. Add checkpoint save on suspension
7. Add checkpoint load/delete on resume
8. Add checkpoint version management
9. Add StaleApprovalException
10. Add scenario test: suspend → JVM restart simulation → resume → complete

**Out of scope:**
- JDBC/File checkpoint store (M5-T5)
- Distributed deployment testing
- Checkpoint garbage collection
- Audit logging
- Process monitoring dashboard

---

## 30. Final Architecture Summary

**M5 Durable Recovery Architecture:**

```
┌─────────────────────────────────────────────────────────────┐
│ Application Layer                                           │
│  - Implements RuntimeBindingResolver                        │
│  - Handles approval requests (processId + checkpointVersion)│
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│ AgentRuntime (M5 Extensions)                                │
│  + resumeProcess(processId, version, signal)                │
│  + checkpointStore: CheckpointStore                         │
│  + bindingResolver: RuntimeBindingResolver                  │
└─────────────────────────┬───────────────────────────────────┘
                          │
            ┌─────────────┼──────────────┐
            │             │              │
┌───────────▼──┐  ┌───────▼────────┐  ┌─▼──────────────────┐
│CheckpointStore│  │BindingResolver │  │AgentExecutionEngine│
│  - save      │  │ - resolve      │  │+ reconstruct...()  │
│  - load      │  │   binding      │  │  (package-private) │
│  - delete    │  └────────────────┘  └────────────────────┘
│  - updateIf  │
│  - deleteIf  │
└──────────────┘
```

**Key Principles:**
- Runtime owns recovery coordination
- Binding is explicit, not registry-based
- Checkpoint is framework-neutral
- Engine owns protocol reconstruction
- Storage is pluggable via SPI
- Concurrency via optimistic locking
- At-least-once execution semantics

---

## CONCLUSION

M5-T3 Architecture Gate: ✅ **APPROVED - GO TO IMPLEMENTATION**

All critical architectural questions resolved. Implementation can proceed with high confidence.

---

**Author:** lov3r  
**Date:** 2026-09-08  
**Status:** ACCEPTED
