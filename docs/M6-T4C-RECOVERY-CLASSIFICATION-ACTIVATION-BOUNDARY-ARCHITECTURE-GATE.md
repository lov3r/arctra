# M6-T4C RECOVERY CLASSIFICATION ACTIVATION BOUNDARY ARCHITECTURE GATE

**Gate Type**: Architecture / Source-Truth Decision Gate  
**Status**: ✅ **ANALYSIS COMPLETE**  
**Date**: 2024  
**Author**: lov3r (via Claude Code)

---

## EXECUTIVE SUMMARY

**Mission**: Determine at what exact runtime/recovery boundary it is correct to consult invocation intent without breaking concurrent at-least-once semantics.

**Core Problem**: M6-T4B gave us `hasInvocationIntent()`, but naively calling it on every resume would turn intent existence into an implicit claiming mechanism, violating frozen at-least-once semantics.

**Critical Discovery**: **Current Arctra CANNOT authoritatively detect JVM restart**. No existing metadata proves "this is recovery from crash vs normal concurrent resume."

**Selected Architecture**: **CANDIDATE F — EXPLICIT RECOVERY TRIGGER with deferred automatic activation**

**Decision**: **CONDITIONAL GO** — Establish manual recovery pathway foundation first. Automatic restart detection deferred until persistent stores exist.

---

## A. FROZEN BASELINE

### M6-T4B Verified Regression
```
arctra-core:          205 tests
arctra-runtime-react: 149 tests
examples:              35 tests
────────────────────────────────
TOTAL:                389 tests
Failures:               0
Errors:                 0
Skipped:               23
────────────────────────────────
BUILD SUCCESS
```

### Closed Milestones
- **M6-T3A**: Durable Logical Tool Operation Identity — ✅ CLOSED
- **M6-T3B**: Authoritative Execution-Boundary Correlation — ✅ CLOSED
- **M6-T3B.1**: ToolContext Semantic-Parity Verification — ✅ CLOSED
- **M6-T4**: Durable Tool Execution Crash Window Architecture Gate — ✅ CLOSED
- **M6-T4.1**: Invocation Intent Recovery Authority Selection — ✅ CLOSED
- **M6-T4A**: Invocation Intent Foundation Implementation — ✅ CLOSED
- **M6-T4A.1**: Mandatory Invocation Gate No-Bypass Closure — ✅ CLOSED
- **M6-T4B**: Invocation Recovery Read Authority — ✅ CLOSED

---

## B. FROZEN AUTHORITY MODEL

### Authorities (Unchanged)

**Checkpoint**: Resumable process / suspension recovery state authority  
**InvocationStateStore**: Recovery-critical invocation-intent authority  
**ExecutionLedger**: Durable execution history authority  
**ExecutionEvent**: Best-effort execution observation/distribution  
**Evidence**: Execution proof/content authority  
**External System**: External business commit/outcome authority

### Critical Boundaries

```
InvocationStateStore != CheckpointStore
InvocationStateStore != ExecutionLedger
InvocationStateStore != External System
```

**InvocationStateStore owns ONLY**:
- Did this logical operation ever cross the durable invocation gate?

**InvocationStateStore does NOT own**:
- Tool success/failure
- External commit/rollback
- Worker ownership
- Claiming
- Execution epoch

---

## C. CURRENT RESUME CALL GRAPH

### Source-Truth Entry Points

**Public API**:
```
AgentRuntime.resumeProcess(processId, checkpointVersion, signal)
  ↓
DefaultAgentRuntime.resumeProcess(...)
  ↓
DurableExecutionEngine.resumeProcess(...)
```

**AgentProcess API**:
```
AgentProcess.resume(signal)
  ↓
DefaultAgentProcess.resume(signal)
  ↓
DurableResumeStrategy.resume(...)
  ↓
DurableExecutionEngine.resumeProcess(processId, checkpointVersion, signal)
```

### Convergence Point

**ALL durable resume paths converge at**:
```
DurableExecutionEngine.resumeProcess(processId, checkpointVersion, signal)
```

**Implemented by**:
```
SpringAiToolCallingEngine.resumeProcess(...)
  ↓
DurableResumeCoordinator.resume(...)
```

### DurableResumeCoordinator Orchestration

```java
DurableResumeCoordinator.resume(processId, checkpointVersion, signal)
  ↓
1. CHECK A: load and validate checkpoint
   checkpointStore.load(processId)
   validate checkpointVersion matches
  ↓
2. Resolve RuntimeBinding
   bindingResolver.resolve(runtimeBindingKey)
  ↓
3. Extract checkpoint data
   pendingBatch, accumulatedEvidences, sessionId
  ↓
4. Delegate to ResumedExecutionHandler
   resumedExecutionHandler.executeResume(...)
  ↓
5. CHECK B: delete or replace checkpoint
   checkpointStore.deleteIfVersion(...) or replaceIfVersion(...)
```

---

## D. CURRENT RESUME ENTRY POINTS

### Resume Scenario Matrix

| Resume Scenario | Entry API | Same Process? | Same Engine Instance? | Can Runtime Distinguish? |
|----------------|-----------|---------------|----------------------|-------------------------|
| **Local process resume** | `process.resume(signal)` | ✅ YES | ✅ YES | ❌ NO (same as others) |
| **Cross-runtime resume** | `runtime.resumeProcess(...)` | ❌ NO | ⚠️ UNKNOWN | ❌ NO |
| **Same-JVM, new engine** | `runtime.resumeProcess(...)` | ❌ NO | ❌ NO | ❌ NO |
| **Post-restart resume** | `runtime.resumeProcess(...)` | ❌ NO | ❌ NO | ❌ NO |
| **Concurrent resume** | `runtime.resumeProcess(...)` | ❌ NO | ⚠️ MAYBE | ❌ NO |
| **Test resume** | `runtime.resumeProcess(...)` | ❌ NO | ❌ NO | ❌ NO |

### Critical Finding

**NO entry point currently distinguishes**:
- Normal concurrent resume
- Post-crash restart resume
- Same-JVM new-engine resume
- Test scenario resume

**All resume scenarios use identical signature**:
```java
resumeProcess(String processId, long checkpointVersion, ContinuationSignal signal)
```

---

## E. CAN RUNTIME DETECT RESTART TODAY?

**Answer**: **NO**

### Investigated Signals

#### 1. Engine Instance Identity

**Concept**: Different engine object = restart?

**Reality**: 
- Engine can be reconstructed within same JVM
- Tests create multiple engines
- No guarantee engine instance survives across resumes

**Verdict**: ❌ **NOT authoritative**

#### 2. Runtime Instance Identity

**Concept**: Different runtime object = restart?

**Reality**:
- Runtime can be reconstructed
- Multi-runtime scenarios exist
- No persistence of runtime identity

**Verdict**: ❌ **NOT authoritative**

#### 3. runtimeBindingKey

**Source**: `RuntimeBinding` Javadoc (line 16-18):

> The runtimeBindingKey in checkpoints identifies the **logical agent configuration/binding** required for recovery, not a physical runtime instance. It **remains stable across re-suspension episodes**.

**Semantics**: Logical binding identity, NOT execution epoch

**Verdict**: ❌ **MUST NOT repurpose as restart detection**

#### 4. sessionId

**Source**: `AgentExecutionContext` Javadoc (line 25-29):

> **Session Semantics**:
> - Same sessionId → conversation continuity
> - Different sessionId → conversation isolation
> - null sessionId → stateless execution

**Semantics**: Conversation/session identity for multi-turn continuity

**Persistence**: Stored in checkpoint, survives restart

**Does it change after crash?**: **NO** — same session continues after restart

**Verdict**: ❌ **NOT restart epoch marker**

#### 5. checkpointVersion

**Source**: M5 semantics

**Semantics**: Suspension/recovery generation (fencing token for CHECK A/B)

**Increments**: On re-suspension, NOT on resume

**Usage**: Concurrent modification detection (CHECK B)

**Verdict**: ❌ **NOT attempt identity or restart marker**

#### 6. Checkpoint Metadata

**Current SuspensionCheckpoint fields**:
- `schemaVersion`: Schema compatibility version
- `processId`: Stable process identity
- `checkpointVersion`: Suspension generation
- `runtimeBindingKey`: Logical binding identity
- `sessionId`: Conversation identity
- `pendingBatch`: Pending operations
- `accumulatedEvidences`: Evidence so far

**Fields that could indicate restart**: **NONE**

**Verdict**: ❌ **No restart marker in current schema**

### Conclusion

**Current Arctra has ZERO authoritative restart detection capability.**

Why this matters:
- Cannot distinguish "Worker A and Worker B concurrently resume" from "Worker A crashes, Worker B recovers"
- Intent existence alone cannot determine recovery vs concurrent execution
- Adding intent query to normal resume would break at-least-once

---

## F. WHY INTENT PRESENCE DOES NOT MEAN RECOVERY

### Scenario

```
Time T1: Worker A loads checkpoint v1
Time T2: Worker A records intent(op-A)
Time T3: Worker B loads checkpoint v1 (concurrent)
Time T4: Worker B queries hasInvocationIntent(op-A) → TRUE
```

**Question**: Does `true` mean this is recovery?

**Answer**: **NO**

### Why Not

**Worker B sees intent because**:
- Worker A recorded it (concurrent normal execution)
- NOT because Worker A crashed and B is recovering

**Both workers are legitimate** under frozen at-least-once semantics.

### The Distinction

**Intent existence** = invocation-gate-crossed fact  
**Recovery attempt** = execution-phase classification (NOT owned by InvocationStateStore)

**These are separate authorities.**

---

## G. CONCURRENT RESUME CONFLICT

### Current At-Least-Once Behavior (Frozen)

```
Worker A: CHECK A(v1) → record intent(op-A) → execute → CHECK B(v1) → SUCCESS
Worker B: CHECK A(v1) → record intent(op-A) → execute → CHECK B(v1) → CONFLICT
```

**Both workers execute. One CHECK B wins.**

### If Intent Query Added Naively

```
Worker A: CHECK A(v1) → hasIntent(op-A)=false → record → execute → CHECK B → SUCCESS
Worker B: CHECK A(v1) → hasIntent(op-A)=true → classify MAY_HAVE_INVOKED → SKIP?
```

**Result**: Worker B suppressed. Only A executes. **At-least-once violated**.

### Why This Is Wrong

**Intent query becomes**:
- Runtime claiming mechanism
- Execution suppression
- Single-executor enforcement

**But InvocationStateStore explicitly does NOT own claiming** (M6-T4B frozen).

### Requirement

**Normal concurrent resume MUST NOT query intent** unless architecture explicitly changes to single-owner semantics in future milestone.

---

## H. CANDIDATE A — QUERY EVERY RESUME

### Proposal

Add intent query to normal resume flow:

```java
DurableResumeCoordinator.resume(...) {
    CHECK A: load checkpoint
    ↓
    FOR EACH operation in pendingBatch:
        if (hasInvocationIntent(processId, operationId)) {
            classify MAY_HAVE_INVOKED
            // Now what? Skip? Throw? Query external?
        } else {
            classify DEFINITELY_NOT_DISPATCHED
            proceed to execute
        }
    ↓
    execute / throw / policy decision
    ↓
    CHECK B
}
```

### Consequences

#### Concurrent Resume Impact

**Scenario**: Two workers resume same checkpoint concurrently

**Worker A**:
```
hasIntent(op-A) = false → execute
```

**Worker B** (moments later):
```
hasIntent(op-A) = true (A just recorded it) → MAY_HAVE_INVOKED → ???
```

**If B skips execution**: At-least-once changed to at-most-once  
**If B also executes**: Why query intent at all?  
**If B throws**: Legitimate concurrent resume now fails

#### Analysis

✅ Provides recovery classification  
❌ **Breaks concurrent at-least-once semantics**  
❌ Turns intent into implicit claiming  
❌ No recovery policy yet (what does MAY_HAVE_INVOKED mean?)  
❌ Cannot distinguish recovery from concurrency

### Verdict

**REJECT** — Violates frozen at-least-once invariant.

---

## I. CANDIDATE B — EXPLICIT RECOVERY MODE

### Proposal

Add execution mode parameter:

```java
resumeProcess(processId, version, signal, RecoveryMode mode)

enum RecoveryMode {
    NORMAL_RESUME,    // Current behavior, no intent query
    RECOVERY_RESUME   // Query intent, classify operations
}
```

**Normal mode**: Existing at-least-once behavior  
**Recovery mode**: Query intent, classify, apply policy (future)

### Questions & Answers

**1. Who selects the mode?**  
→ Caller (public API) or runtime decision

**2. Public API change?**  
→ YES (adds parameter to public method)

**3. Can caller be trusted?**  
→ YES (same trust as current caller)

**4. Automatic inference possible?**  
→ NO (no restart marker available)

**5. Is manual mode acceptable?**  
→ POTENTIALLY (operator-driven recovery)

**6. Does it expose complexity?**  
→ YES (caller must understand semantics)

**7. Does it preserve at-least-once for NORMAL?**  
→ YES (unchanged path)

**8. Does it enable recovery for RECOVERY?**  
→ YES (intent query enabled)

**9. Concurrent resumes?**  
→ Both use NORMAL mode → at-least-once preserved

**10. Post-restart?**  
→ Operator/bootstrap selects RECOVERY mode

### Analysis

✅ Preserves normal at-least-once semantics  
✅ Enables recovery classification when explicitly requested  
✅ Clear semantic distinction  
❌ **Public API pressure** (adds parameter)  
❌ Requires caller knowledge (complexity leak)  
⚠️ Manual only (no automatic restart detection)

### Verdict

**VIABLE** but requires public API change and operator knowledge.

---

## J. CANDIDATE C — RECOVERY-SPECIFIC ENTRY

### Proposal

Separate entry point for recovery:

```java
// Existing
resumeProcess(processId, version, signal)  // Normal at-least-once

// New
recoverProcess(processId, version, signal) // Recovery with classification
```

### Questions & Answers

**Who calls recovery path?**  
→ Operator, bootstrap script, or recovery service

**How does restart bootstrap know?**  
→ Manual decision or external orchestration

**Duplicate orchestration?**  
→ Risk of CHECK A/B logic divergence

**Public API pressure?**  
→ YES (new public method)

### Analysis

✅ Preserves normal semantics  
✅ Clear separation  
❌ **Public API addition**  
❌ Risk of duplicated orchestration  
❌ Manual only

### Verdict

**VIABLE** but similar to Candidate B with more duplication risk.

---

## K. CANDIDATE D — EXECUTION EPOCH IDENTITY

### Proposal

Associate intent with execution epoch:

```
Checkpoint v1 created by runtime-epoch-A
Intent recorded by runtime-epoch-A
Crash
Resume by runtime-epoch-B (new epoch after restart)
B queries intent + epoch
B sees: intent from different epoch → classify as recovery
```

### Requirements

1. **Epoch identity generation**: Each runtime/JVM generates unique epoch
2. **Epoch persistence**: Checkpoint stores creating epoch
3. **Intent-epoch association**: InvocationStateStore tracks (processId, operationId, epoch)
4. **Epoch comparison**: Resume compares current epoch vs intent epoch

### Analysis

**Does this solve the problem?**  
→ YES (different epoch = different execution phase)

**Does it distinguish concurrent workers?**  
→ NO (same epoch, both legitimate)

**Is it claiming/lease/fencing?**  
→ ⚠️ **STARTS TO RESEMBLE IT**

**Persistent state required?**  
→ YES (epoch must survive restart)

**Is this premature?**  
→ ⚠️ **LIKELY** (attemptId not yet needed, attempt-level correlation premature)

### Resemblance to Attempt Identity

**Epoch** is essentially:
- Execution-phase correlation ID
- Physical-attempt generation marker
- Very similar to `attemptId` concept

**But attemptId frozen status**: **JUSTIFIED NEXT** (not required now)

### Verdict

**DEFER** — Begins to introduce attempt-level correlation without proven consumer. Resembles attemptId which is explicitly deferred.

---

## L. CANDIDATE E — CLAIM / LEASE / FENCING

### Proposal

One worker claims operation, only claimant executes:

```java
if (invocationStateStore.tryClaimOperation(processId, operationId, workerId)) {
    execute
} else {
    skip (another worker owns it)
}
```

### Semantic Change

**From**: At-least-once concurrent execution  
**To**: Single-owner execution (at-most-once if claim respected)

### Analysis

✅ Solves ambiguity (only one executor)  
✅ Clear ownership semantics  
❌ **Fundamental concurrency model change**  
❌ Requires lease/fencing/timeout for fault tolerance  
❌ Complexity: claim expiration, heartbeat, ownership transfer  
❌ InvocationStateStore must become ownership authority  
❌ **Violates frozen authority model** (store does not own claiming)

### Is This a Different Milestone?

**YES**

Claim/lease/fencing is a **foundational semantic change**, not activation boundary refinement.

Would require:
- Separate architecture gate
- Claim ownership authority design
- Lease expiration strategy
- Fencing token mechanism
- Heartbeat protocol
- Extensive testing

### Verdict

**REJECT for M6-T4C** — Different milestone. Would violate frozen at-least-once and authority model.

---

## M. CANDIDATE F — EXPLICIT RECOVERY TRIGGER

### Proposal

**Two-phase approach**:

**Phase 1** (M6-T4C): Establish recovery pathway foundation
- Add internal recovery activation capability
- Keep public API unchanged (defer to Phase 2)
- Manual/explicit recovery trigger only
- Document automatic detection as future work

**Phase 2** (future): Automatic restart detection
- Requires persistent CheckpointStore
- Requires persistent InvocationStateStore
- Runtime epoch or bootstrap detection
- Automatic mode selection

### Phase 1 Design (M6-T4C Scope)

**Internal capability**:
```java
// Package-private recovery pathway
AgentResult resumeWithRecoveryClassification(
    String processId, 
    long checkpointVersion, 
    ContinuationSignal signal
) {
    // Query hasInvocationIntent()
    // Classify operations
    // Apply policy (initial: fail-fast on MAY_HAVE_INVOKED)
}
```

**Public API**: Unchanged (use existing `resumeProcess`)

**Activation**: 
- Test-only initially
- Documented for manual operator use
- Foundation for future automatic activation

**Benefits**:
- ✅ Establishes recovery classification logic
- ✅ No public API pressure
- ✅ Preserves normal at-least-once
- ✅ Foundation for Phase 2
- ✅ Testable with InMemory stores
- ⚠️ Manual only (acceptable as foundation)

### Phase 2 Design (Future)

**Automatic detection prerequisites**:
1. Persistent CheckpointStore (survives restart)
2. Persistent InvocationStateStore (survives restart)
3. Execution epoch or bootstrap marker
4. Public API decision (mode parameter vs automatic)

**Once available**:
- Automatic restart detection
- Seamless recovery activation
- Enterprise-ready

### Why This Is Correct

**Separates concerns**:
- M6-T4C: Recovery classification logic (internals)
- Future: Automatic activation (requires persistence)

**Acknowledges reality**:
- Current: InMemory stores (no restart durability anyway)
- Future: Persistent stores enable true restart recovery

**YAGNI-compliant**:
- Don't build automatic activation before persistence exists
- Don't add public API before use case is clear

### Verdict

**ACCEPT** — Correct phasing, YAGNI-compliant, preserves invariants.

---

## N. CANDIDATE COMPARISON TABLE

| Dimension | A: Every Resume | B: Explicit Mode | C: Recovery Entry | D: Epoch Identity | E: Claim/Lease | F: Explicit Trigger |
|-----------|----------------|------------------|-------------------|-------------------|----------------|---------------------|
| **Preserves at-least-once** | ❌ NO | ✅ YES | ✅ YES | ✅ YES | ❌ NO | ✅ YES |
| **Distinguishes recovery** | ❌ NO | ✅ YES (manual) | ✅ YES (manual) | ✅ YES | N/A | ⚠️ MANUAL (Phase 1) |
| **Requires public API** | ❌ NO | ✅ YES | ✅ YES | ⚠️ MAYBE | ✅ YES | ❌ NO (Phase 1) |
| **Requires persistence** | ❌ NO | ❌ NO | ❌ NO | ✅ YES | ✅ YES | ❌ NO (Phase 1) |
| **Introduces ownership** | ⚠️ IMPLICIT | ❌ NO | ❌ NO | ⚠️ RESEMBLES | ✅ YES | ❌ NO |
| **Requires attemptId** | ❌ NO | ❌ NO | ❌ NO | ⚠️ RESEMBLES | ⚠️ MAYBE | ❌ NO |
| **Core impact** | 0 | 0 | 0 | HIGH | HIGH | 0 |
| **Runtime-react impact** | MEDIUM | MEDIUM | MEDIUM | HIGH | HIGH | LOW |
| **YAGNI** | ❌ NO | ⚠️ PARTIAL | ⚠️ PARTIAL | ❌ NO | ❌ NO | ✅ YES |
| **Enterprise direction** | ❌ WRONG | ✅ VIABLE | ✅ VIABLE | ⚠️ PREMATURE | ⚠️ DIFFERENT MS | ✅ CORRECT |

### Scoring

- **A**: 2 ✅, 5 ❌, 2 ⚠️ — **REJECT** (breaks at-least-once)
- **B**: 6 ✅, 1 ❌, 2 ⚠️ — **VIABLE** (but public API pressure)
- **C**: 6 ✅, 1 ❌, 2 ⚠️ — **VIABLE** (but duplication risk)
- **D**: 4 ✅, 1 ❌, 4 ⚠️ — **DEFER** (premature, resembles attemptId)
- **E**: 2 ✅, 4 ❌, 3 ⚠️ — **REJECT** (different milestone)
- **F**: 7 ✅, 0 ❌, 2 ⚠️ — **ACCEPT** (correct phasing)

---

## O. PERSISTENT STORE DEPENDENCY

### Current Reality

**InMemoryCheckpointStore**: JVM-local, lost on restart  
**InMemoryInvocationStateStore**: JVM-local, lost on restart

**Therefore**: True automatic restart recovery **NOT POSSIBLE TODAY**

### Phase 1 (M6-T4C) Without Persistence

**Can establish**:
- Recovery classification logic
- Internal activation pathway
- Test coverage with InMemory stores
- Foundation for Phase 2

**Cannot provide**:
- Automatic restart detection
- True crash recovery
- Production restart durability

### Phase 2 (Future) With Persistence

**Requires**:
1. Persistent CheckpointStore (JDBC/Redis)
2. Persistent InvocationStateStore (JDBC/Redis)
3. Execution epoch or bootstrap marker

**Enables**:
- Automatic restart detection
- True crash recovery
- Production restart durability

### Verdict

**M6-T4C does NOT require persistent stores** to establish internal recovery classification capability. Persistence is prerequisite for *automatic activation* (Phase 2).

---

## P. CHECKPOINT METADATA ANALYSIS

### Current SuspensionCheckpoint Fields

```java
record SuspensionCheckpoint(
    int schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences
)
```

### Analysis

**schemaVersion**: Schema compatibility, NOT restart marker  
**processId**: Stable process identity, NOT execution epoch  
**checkpointVersion**: Suspension generation, NOT attempt identity  
**runtimeBindingKey**: Logical binding, NOT runtime instance  
**sessionId**: Conversation identity, NOT restart marker  
**pendingBatch**: Operations to resume  
**accumulatedEvidences**: Execution history

### Fields That Could Indicate Restart

**NONE**

### Could We Add One?

**Possible**: `executionEpoch` or `creationTimestamp`

**But**:
- Would be execution-phase metadata, not suspension state
- Would need persistence to survive restart
- Would resemble attemptId (deferred)

**Verdict**: Do NOT add checkpoint fields in M6-T4C. Deferred to automatic activation design.

---

## Q. RUNTIMEBINDINGKEY ANALYSIS

### Source Truth

**RuntimeBinding Javadoc** (line 16-18):

> The runtimeBindingKey in checkpoints identifies the **logical agent configuration/binding** required for recovery, **not a physical runtime instance**. It **remains stable across re-suspension episodes**.

### Semantics

**What it IS**:
- Logical agent binding identifier
- Resolver lookup key
- Stable across re-suspensions

**What it is NOT**:
- Runtime instance identity
- JVM identity
- Execution epoch
- Worker identity
- Restart marker

### Can It Detect Restart?

**NO**

**Why**: Same runtimeBindingKey used before and after restart (intentionally stable).

### Verdict

**MUST NOT repurpose** runtimeBindingKey as restart detection. Would violate M5 binding semantics.

---

## R. SESSIONID ANALYSIS

### Source Truth

**AgentExecutionContext Javadoc** (line 25-29):

> **Session Semantics**:
> - Same sessionId → conversation continuity (Turn 2 sees Turn 1 context)
> - Different sessionId → conversation isolation
> - null sessionId → stateless execution

### Semantics

**Purpose**: Multi-turn conversation continuity

**Persistence**: Stored in checkpoint, restored on resume

**Behavior after restart**: **UNCHANGED** — same session continues

### Can It Detect Restart?

**NO**

**Why**: Conversation identity is orthogonal to execution epoch. Same conversation continues after crash.

### Verdict

**MUST NOT repurpose** sessionId as restart marker. Would break conversation continuity semantics.

---

## S. CHECKPOINTVERSION ANALYSIS

### Source Truth

**M5 Semantics**: Suspension/recovery generation

**Purpose**: Fencing token for CHECK A/B concurrent modification detection

### Behavior

**Increments**: On re-suspension (new episode)  
**Does NOT increment**: On resume

**Usage**:
- CHECK A: Validate version matches
- CHECK B: Use as fencing token for deleteIfVersion/replaceIfVersion

### Is It Attempt Identity?

**NO**

**checkpointVersion** = suspension generation  
**attemptId** (future) = physical invocation attempt

These are different concepts.

### Can It Detect Restart?

**NO**

**Why**: 
- Same version used by concurrent workers
- Does not change on resume
- Tracks suspension episodes, not execution phases

### Verdict

**MUST NOT repurpose** checkpointVersion as attempt identity or restart marker.

---

## T. ATTEMPTID RE-EVALUATION

### Current Status

**Frozen**: **JUSTIFIED NEXT** (M6-T4B closure)

### Question

Is attemptId required for M6-T4C recovery activation?

### Analysis

**Candidate D (Epoch Identity)** essentially introduces attempt-phase correlation.

**But**:
- No current consumer for attempt-level detail
- Recovery classification needs: "ever invoked?" (boolean), not "which attempt?"
- Epoch/attempt correlation is premature without retry logic

### Decision

**DEFER** (unchanged from M6-T4B)

**Rationale**:
- Recovery activation can be established without attempt ID
- attemptId justified when retry logic is designed
- Introducing now would violate YAGNI

**Status**: **JUSTIFIED NEXT** (for retry correlation, not activation)

---

## U. RECOVERY ACTIVATION AUTHORITY

### Question

Who owns the fact: "This execution is in recovery mode"?

### Analysis

**NOT InvocationStateStore**: Owns invocation-intent fact only

**NOT Checkpoint**: Owns suspension state, not execution mode

**NOT ExecutionLedger**: History authority, not execution control

**Candidates**:
1. Caller (runtime/operator)
2. DurableResumeCoordinator
3. Future RecoveryCoordinator/Planner

### Selected Authority (Phase 1)

**Caller / Runtime** (implicit via pathway selection)

**Why**:
- Manual recovery trigger (operator/test decides)
- No automatic detection yet
- Internal capability, not persisted fact

### Future Authority (Phase 2)

**Runtime bootstrap / RecoveryCoordinator** (automatic detection)

**Why**:
- Automatic restart detection
- Persistent markers available
- Enterprise-ready seamless recovery

---

## V. RECOVERY CLASSIFICATION CONSUMER

### Who Needs Classification Result?

**Phase 1**: Internal recovery pathway

**Phase 2**: DurableResumeCoordinator (orchestrator), RecoveryPlanner (policy)

### What Consumes It?

**DEFINITELY_NOT_DISPATCHED** → Proceed to execution  
**MAY_HAVE_INVOKED** → Recovery policy decision

### Initial Policy (Phase 1)

**Fail-fast on MAY_HAVE_INVOKED**:
```
throw RecoveryUncertaintyException(
    "Operation may have been invoked. Manual investigation required."
)
```

**Why fail-fast initially**:
- No retry policy yet
- No external query capability yet
- No operator intervention UI yet
- Safe default: alert operator to uncertain state

### Future Policy (Phase 2+)

- Automatic retry
- External system query
- Idempotency key verification
- Operator intervention workflow
- Configurable policy engine

---

## W. DURABLERESUMECOORDINATOR RESPONSIBILITY

### Current Responsibilities

✅ CHECK A orchestration  
✅ RuntimeBinding resolution  
✅ Resumed execution delegation  
✅ CHECK B orchestration  
✅ Exception mapping

### Should It Add?

**Recovery classification orchestration**: ⚠️ **MAYBE** (Phase 2)

**Recovery policy decisions**: ❌ **NO** (separate concern)

### Distinction

**Orchestration** (acceptable):
```java
if (recoveryMode) {
    classifier.classify(pendingBatch, invocationStateStore)
    recoveryPlanner.decide(classificationResult)
}
```

**Policy** (forbidden):
```java
if (MAY_HAVE_INVOKED) {
    if (retryCount < 3) retry();
    else if (hasIdempotencyKey) queryExternal();
    else manual();
}
```

### Verdict

Coordinator may **orchestrate** recovery classifier (delegate to it).  
Coordinator MUST NOT **decide** recovery policy (retry/query/manual).

---

## X. CONCURRENT AT-LEAST-ONCE IMPACT

**Selected Architecture**: **PRESERVED**

### Normal Resume Path (Unchanged)

```
Worker A: CHECK A → record intent → execute → CHECK B
Worker B: CHECK A → record intent → execute → CHECK B
One CHECK B wins, one conflicts
```

**Both may execute. At-least-once preserved.**

### Recovery Path (New, Explicit)

```
Operator triggers recovery mode:
CHECK A → hasInvocationIntent → classify → policy decision
```

**Only activated explicitly. Does NOT affect normal concurrent resumes.**

### Verification

**Concurrent workers use**: Normal resume path  
**Recovery use**: Explicit recovery path  
**At-least-once**: Unchanged for normal path

**Impact**: **PRESERVED**

---

## Y. CORE OWNERSHIP DECISION

### Is Recovery Activation Generic or Implementation-Specific?

**Generic durable-runtime concept**

**Why**:
- Any DurableExecutionEngine might need recovery classification
- InvocationStateStore is provider-independent
- Recovery logic is framework-level, not Spring AI specific

### Where Should It Live?

**Phase 1**: `cn.bitcss.arctra.runtime.react` (package-private)

**Why**:
- Only one engine implementation currently
- Internal capability foundation
- No cross-engine sharing yet

**Phase 2** (if multiple engines emerge): Promote to `cn.bitcss.arctra.runtime` (core)

### Verdict

**Keep internal** (runtime-react) for M6-T4C. Promote only when cross-engine pressure appears.

---

## Z. PUBLIC API IMPACT

**Selected Architecture**: **0**

**Phase 1 (M6-T4C)**:
- Internal recovery pathway only
- Public API unchanged
- Test-only / manual operator use
- Foundation for Phase 2

**Phase 2 (Future)**:
- MAY add public API (mode parameter or automatic)
- Decision deferred until automatic detection designed

### Why Zero Impact Is Correct

**Current reality**: InMemory stores, no restart durability anyway

**Public API pressure justified ONLY when**:
- Persistent stores exist
- Automatic detection designed
- Production restart recovery actually possible

**Adding API prematurely** would expose incomplete capability.

---

## AA. SELECTED ARCHITECTURE

**CANDIDATE F — EXPLICIT RECOVERY TRIGGER (Two-Phase)**

### Phase 1: Internal Recovery Foundation (M6-T4C Scope)

**Establish**:
1. Internal recovery classification logic
2. Package-private recovery pathway
3. Test coverage with InMemory stores
4. Foundation for Phase 2 automatic activation

**Characteristics**:
- ✅ Preserves normal at-least-once semantics
- ✅ Enables recovery classification when explicitly triggered
- ✅ No public API pressure
- ✅ YAGNI-compliant (builds foundation, not premature automation)
- ⚠️ Manual activation only (acceptable as foundation)

### Phase 2: Automatic Activation (Future)

**Prerequisites**:
- Persistent CheckpointStore
- Persistent InvocationStateStore
- Execution epoch or bootstrap detection mechanism

**Enables**:
- Automatic restart detection
- Seamless recovery activation
- Enterprise-ready crash recovery

### Why This Architecture Is Correct

**Separates concerns**:
- Classification logic (M6-T4C)
- Automatic activation (future, requires persistence)

**Acknowledges reality**:
- Current: No restart durability
- Future: Persistent stores enable true recovery

**Phased delivery**:
- Phase 1: Foundation (internal)
- Phase 2: Production-ready (automatic)

**YAGNI-compliant**:
- Don't build activation before persistence
- Don't expose API before use case clear

---

## AB. EXACT NEXT IMPLEMENTATION SLICE

### M6-T4C Problem

Runtime cannot distinguish normal concurrent resume from genuine recovery attempt.

### M6-T4C Activation Fact

"This execution pathway is recovery-classification mode" (execution intent, not persisted)

### M6-T4C Activation Authority

Caller / runtime (Phase 1: manual; Phase 2: automatic)

### M6-T4C Trigger

Phase 1: Explicit internal method invocation (test/manual)  
Phase 2: Automatic detection via persistent markers (future)

### M6-T4C Classification Consumer

Internal recovery pathway → classifier → policy (initial: fail-fast)

### M6-T4C Normal Resume Semantics

**Unchanged**: CHECK A → record intent → execute → CHECK B (at-least-once)

### M6-T4C Recovery Resume Semantics

**New pathway**: CHECK A → hasInvocationIntent → classify → policy → CHECK B or throw

### M6-T4C Concurrent Resume Semantics

**Preserved**: Both workers use normal path, at-least-once unchanged

### M6-T4C Persistence Dependency

**Phase 1**: No persistence required (InMemory stores sufficient for logic)  
**Phase 2**: Persistence required for automatic activation

### M6-T4C attemptId Status

**JUSTIFIED NEXT** (unchanged - not required for activation)

### M6-T4C Core Impact

**0** (all changes runtime-react internal)

### M6-T4C Public API Impact

**0** (internal capability only, Phase 1)

### M6-T4C Non-Goals

❌ Automatic restart detection (Phase 2)  
❌ Persistent store implementation  
❌ Recovery policy (retry/query/operator)  
❌ Public API changes  
❌ attemptId  
❌ Claim/lease/fencing  
❌ Checkpoint schema changes

---

## AC. EXPLICIT NON-GOALS

**M6-T4C does NOT implement**:

❌ Production automatic restart detection  
❌ Persistent JDBC InvocationStateStore  
❌ Persistent Redis InvocationStateStore  
❌ Persistent CheckpointStore  
❌ Execution epoch persistence  
❌ Automatic recovery mode selection  
❌ Public API for recovery mode  
❌ Recovery policy engine (RecoveryPlanner)  
❌ Retry logic  
❌ Automatic retry  
❌ Idempotency key verification  
❌ External system query  
❌ External receipt verification  
❌ Operator intervention UI  
❌ Compensation  
❌ Reconciliation  
❌ attemptId  
❌ Claim/lease/fencing  
❌ Worker ownership  
❌ ToolExecutionRuntime  
❌ Checkpoint schema changes  
❌ runtimeBindingKey repurposing  
❌ sessionId repurposing  
❌ checkpointVersion repurposing

**M6-T4C establishes internal recovery classification foundation only.**

---

## AD. ENTRY GATE QUESTIONS

### 40 Mandatory Answers

**1. Does InvocationStateStore know whether current resume is recovery?**  
✅ NO (correct - not its authority)

**2. Does intent presence itself prove recovery?**  
✅ NO (could be concurrent worker)

**3. Can current runtime detect JVM restart authoritatively?**  
✅ NO (critical finding)

**4. Can engine instance identity prove restart?**  
✅ NO (can reconstruct within JVM)

**5. Can runtime instance identity prove restart?**  
✅ NO (can reconstruct)

**6. Can runtimeBindingKey prove restart?**  
✅ NO (logical binding, stable across episodes)

**7. Can sessionId prove restart?**  
✅ NO (conversation identity, survives restart)

**8. Can checkpointVersion prove restart?**  
✅ NO (suspension generation, not attempt ID)

**9. Would querying intent on every resume change concurrency semantics?**  
✅ YES (would break at-least-once)

**10. Would querying intent on every resume introduce implicit claiming?**  
✅ YES (intent existence would suppress execution)

**11. Must normal concurrent resume remain at-least-once?**  
✅ YES (frozen invariant)

**12. Is recovery activation a distinct fact from invocation intent?**  
✅ YES (separate authorities)

**13. Who owns recovery activation?**  
✅ Caller/runtime (Phase 1), future bootstrap/coordinator (Phase 2)

**14. Must recovery activation be persisted?**  
✅ NO (execution intent, not durable fact)

**15. Does recovery classification require attemptId?**  
✅ NO (boolean existence sufficient)

**16. Does recovery activation require attemptId?**  
✅ NO

**17. Is attemptId required NOW?**  
✅ NO (JUSTIFIED NEXT unchanged)

**18. Is claim/lease/fencing required NOW?**  
✅ NO (different milestone)

**19. Does selected architecture preserve normal resume behavior?**  
✅ YES (unchanged path)

**20. Does selected architecture provide a path for restart recovery?**  
✅ YES (internal foundation for Phase 2)

**21. Does selected architecture work with future persistent store?**  
✅ YES (Phase 2 compatible)

**22. Does selected architecture require persistent store immediately?**  
✅ NO (InMemory sufficient for Phase 1)

**23. Does selected architecture require Checkpoint schema change?**  
✅ NO

**24. Does selected architecture change CHECK A?**  
✅ NO (checkpoint loading unchanged)

**25. Does selected architecture change CHECK B?**  
✅ NO (deleteIfVersion/replaceIfVersion unchanged)

**26. Does selected architecture change checkpointVersion?**  
✅ NO

**27. Does selected architecture require core changes?**  
✅ NO (runtime-react internal)

**28. Does selected architecture require public API changes?**  
✅ NO (Phase 1 internal only)

**29. Is a dedicated recovery classifier justified?**  
✅ YES (single responsibility, testable)

**30. Does DurableResumeCoordinator own recovery policy?**  
✅ NO (orchestrates, does not decide)

**31. Does DurableResumeCoordinator own recovery orchestration?**  
✅ YES (delegates to classifier/planner)

**32. Does read failure fail closed once recovery is activated?**  
✅ YES (unknown != absent, must throw)

**33. Is normal resume insulated from invocation-read failure?**  
✅ YES (does not query intent)

**34. Does Ledger remain outside recovery correctness?**  
✅ YES (history authority, not recovery truth)

**35. Does External System remain external commit authority?**  
✅ YES (unchanged)

**36. Is persistent CheckpointStore required for true restart recovery?**  
✅ YES (Phase 2 prerequisite)

**37. Is persistent InvocationStateStore required for true restart recovery?**  
✅ YES (Phase 2 prerequisite)

**38. Is true automatic restart recovery implemented today?**  
✅ NO (InMemory stores, manual only in Phase 1)

**39. Is T4C sufficiently specified for implementation?**  
✅ YES (Phase 1 scope clear)

**40. Is the next slice YAGNI-compliant?**  
✅ YES (builds foundation, not premature automation)

---

## AE. DECISION

**✅ CONDITIONAL GO — M6-T4C PHASE 1 IMPLEMENTATION MAY BEGIN**

### Conditions

1. **Scope**: Internal recovery classification foundation ONLY
2. **No public API changes**: Phase 1 is internal capability
3. **Manual activation**: Automatic detection deferred to Phase 2
4. **Normal resume unchanged**: At-least-once preserved
5. **Phase 2 prerequisite**: Acknowledge persistent stores required for automatic activation

### All Entry Requirements Met

✅ Preserves at-least-once semantics  
✅ Distinguishes recovery (manual Phase 1, automatic Phase 2)  
✅ No public API pressure (Phase 1)  
✅ No persistence required (Phase 1)  
✅ No claiming introduced  
✅ No attemptId required  
✅ Zero core impact  
✅ YAGNI-compliant phasing  
✅ Clear path to Phase 2

### Implementation Scope

**M6-T4C Phase 1 will establish**:
1. Internal `InvocationRecoveryClassifier` (or equivalent)
2. Classification logic: DEFINITELY_NOT_DISPATCHED / MAY_HAVE_INVOKED
3. Package-private recovery pathway in DurableResumeCoordinator
4. Initial policy: fail-fast on MAY_HAVE_INVOKED
5. Test coverage with InMemory stores
6. Documentation for Phase 2 automatic activation

**M6-T4C Phase 1 will NOT implement**:
- Automatic restart detection
- Public API changes
- Persistent stores
- Retry policy
- External query
- Operator UI

---

## HARD STOP

**M6-T4C ARCHITECTURE GATE COMPLETE**

**Awaiting**: Implementation execution approval for Phase 1.

**Phase 2** (automatic activation) awaiting persistent stores and automatic detection design.

---

**END OF M6-T4C RECOVERY CLASSIFICATION ACTIVATION BOUNDARY ARCHITECTURE GATE**
