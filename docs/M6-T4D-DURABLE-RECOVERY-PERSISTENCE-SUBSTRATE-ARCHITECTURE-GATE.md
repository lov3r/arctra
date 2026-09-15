# M6-T4D DURABLE RECOVERY PERSISTENCE SUBSTRATE ARCHITECTURE GATE

**Status**: ARCHITECTURE GATE  
**Date**: 2024-01-XX  
**Scope**: Persistence substrate and consistency contract analysis for JVM restart recovery  

---

## Executive Summary

**Central Question**: What persistence substrate and consistency contract must Arctra establish before durable recovery can safely survive JVM/process restart?

**Critical Insight**: The real problem is NOT "how do we persist CheckpointStore?" or "how do we persist InvocationStateStore?" independently. The real problem is:

> **How do two distinct recovery authorities remain semantically correct across crashes, persistence failures, restart, and eventually multiple nodes?**

**Key Finding**: Cross-authority atomic transactions are NOT required for correctness. Independent durable stores with conservative recovery classification and strong read-after-write consistency can safely preserve recovery semantics.

**Recommended Architecture**: **Candidate D + E Hybrid** — Shared physical persistence substrate with separate semantic authorities, conservative independent commits, and fail-closed recovery reads.

**Decision**: ✅ **GO — M6-T4E (Persistent CheckpointStore Implementation) May Begin**

---

## A. Frozen Baseline

### Test Status (Verified)

```
arctra-core:          205 tests PASSING
arctra-runtime-react: 159 tests PASSING  
examples:              35 tests PASSING
TOTAL:                399 tests PASSING
Failures:               0
Errors:                 0
Skipped:               23
BUILD:              SUCCESS
```

### Closed Milestones

```
M6-T3A       CLOSED — operationId foundation
M6-T3B       CLOSED — Direct per-operation execution
M6-T3B.1     CLOSED — ToolContext semantic parity
M6-T4        CLOSED — Uncertain outcome architecture
M6-T4.1      CLOSED — Invocation intent correction
M6-T4A       CLOSED — Invocation intent gate
M6-T4A.1     CLOSED — Intent gate validation
M6-T4B       CLOSED — Recovery read visibility
M6-T4C P1    CLOSED — Explicit recovery classification
M6-T4C.1     CLOSED — Recovery semantics validation
```

**Do not reopen their semantics.**

---

## B. Frozen Authority Model

The following authority separation is **FROZEN** and must be preserved across persistence:

| Authority | Responsibility | Current Contract |
|-----------|---------------|------------------|
| **Checkpoint** | Current resumable process/suspension state | `CheckpointStore` (core) |
| **InvocationStateStore** | Invocation-intent authority | `InvocationStateStore` (runtime-react internal) |
| **ExecutionLedger** | Durable execution history (best-effort) | `ExecutionLedger` (core) |
| **ExecutionEvent** | Already-true fact projection | Event emission |
| **Evidence** | Execution proof/content | Evidence objects |
| **External System** | External business commit/outcome | N/A (outside framework) |

**Critical Invariants:**

```
InvocationStateStore ≠ ExecutionLedger
InvocationStateStore ≠ Checkpoint  
Checkpoint ≠ ExecutionLedger
```

Persistence implementation may share physical infrastructure.  
That does NOT automatically mean semantic authorities should merge.

---

## C. Current Persistence Source Truth

### C.1 CheckpointStore Contract (Core Public API)

**Location**: `arctra-core/src/main/java/cn/bitcss/arctra/checkpoint/CheckpointStore.java`

**Interface**:
```java
public interface CheckpointStore {
  void create(SuspensionCheckpoint checkpoint);
  Optional<SuspensionCheckpoint> load(String processId);
  boolean replaceIfVersion(String processId, long expectedVersion, SuspensionCheckpoint newCheckpoint);
  boolean deleteIfVersion(String processId, long expectedVersion);
}
```

**Semantics:**
- `create`: Store new checkpoint v1 (idempotent within same version)
- `load`: Retrieve current checkpoint by processId
- `replaceIfVersion`: CAS replace v_old → v_new (returns false on version conflict)
- `deleteIfVersion`: CAS delete at v_expected (returns false on version conflict)

**Current Implementation**: `InMemoryCheckpointStore` (JVM-local, NOT restart-durable)

### C.2 InvocationStateStore Contract (Runtime-React Internal)

**Location**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/InvocationStateStore.java`

**Interface**:
```java
interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId);
  boolean hasInvocationIntent(String processId, String operationId);
}
```

**Semantics:**
- `recordInvocationIntent`: Durable pre-call gate (MUST succeed before delegate.call())
- `hasInvocationIntent`: Recovery read (true = intent exists, false = authoritatively absent, throws = unknown)

**Current Implementation**: `InMemoryInvocationStateStore` (JVM-local, NOT restart-durable)

**Visibility**: Package-private, NOT part of public API

### C.3 SuspensionCheckpoint Structure

**Location**: `arctra-core/src/main/java/cn/bitcss/arctra/checkpoint/SuspensionCheckpoint.java`

**Record Structure**:
```java
public record SuspensionCheckpoint(
    int schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences
) {}
```

**Persistence Challenge**: Contains complex nested structures:
- `PendingToolCall`: operationId, toolCallId, toolName, arguments
- `Evidence`: framework evidence objects
- Serialization readiness: **PARTIALLY READY** (requires JSON serialization strategy)

### C.4 PendingToolCall Structure

**Location**: `arctra-core/src/main/java/cn/bitcss/arctra/checkpoint/PendingToolCall.java`

**Record Structure**:
```java
public record PendingToolCall(
    String operationId,   // Framework identity (UUID, opaque)
    String toolCallId,    // Provider protocol identity
    String toolName,      // Tool name for dispatch
    String arguments      // JSON arguments
) {}
```

**Key Identity**: `operationId` = Arctra logical durable tool operation identity
- Generated once: `OperationIds.generate()` → `UUID.randomUUID().toString()`
- Stable across recovery attempts for same logical operation
- Distinct from provider `toolCallId`

---

## D. Persistence Call Graph

### D.1 Checkpoint Store Call Sites

**Production Sites (3 total)**:

1. **Initial Suspension** — `SpringAiToolCallingEngine:399`
   ```java
   checkpointStore.create(checkpoint);
   ```

2. **Completion (CHECK B)** — `DurableResumeCoordinator:452`
   ```java
   boolean deleted = checkpointStore.deleteIfVersion(processId, expectedVersion);
   ```

3. **Re-suspension (CHECK B)** — `DurableResumeCoordinator:520`
   ```java
   boolean replaced = checkpointStore.replaceIfVersion(processId, expectedVersion, nextCheckpoint);
   ```

**READ Sites**:
- `DurableResumeCoordinator:343` — CHECK A load and validate

### D.2 Invocation Intent Call Sites

**WRITE Sites (1 production)**:

1. **Pre-call Gate** — `ProtocolReconstructor:292`
   ```java
   invocationStateStore.recordInvocationIntent(
       operationContext.processId(), 
       operation.operationId()
   );
   // Only after success:
   delegate.call(...)
   ```

**READ Sites (1 production)**:

1. **Recovery Classification** — `InvocationRecoveryClassifier:96`
   ```java
   boolean hasIntent = invocationStateStore.hasInvocationIntent(processId, operationId);
   ```

### D.3 Execution Ledger Call Sites

**Projection Only** — ExecutionLedgerListener wraps ExecutionLedger  
**NOT used for recovery authority** — Best-effort history only

### D.4 ChatMemory Call Sites

**Spring AI Managed** — `SpringAiResumedExecutionHandler:291-296`  
```java
chatMemory.add(sessionId, finalMessage);
```

**Separate crash window** — NOT coordinated with checkpoint/intent transactions

---

## E. Recovery-Critical Persistence Boundaries

| Boundary | Authority | Implementation | Failure Semantics | Execution Continues? | Recovery-Critical? |
|----------|-----------|----------------|-------------------|----------------------|-------------------|
| **P1: Initial checkpoint create** | Checkpoint | CheckpointStore.create() | Throws exception | NO (fails execution) | ✅ YES |
| **P2: Invocation intent record** | InvocationState | InvocationStateStore.recordInvocationIntent() | Throws InvocationIntentPersistenceException | NO (blocks delegate.call()) | ✅ YES |
| **P3: Physical delegate invocation** | Tool | delegate.call() | Returns/throws | N/A (execution itself) | N/A |
| **P4: Re-suspension checkpoint replace** | Checkpoint | CheckpointStore.replaceIfVersion() | Returns false | NO (throws conflict exception) | ✅ YES |
| **P5: Completion checkpoint delete** | Checkpoint | CheckpointStore.deleteIfVersion() | Returns false | NO (throws conflict exception) | ✅ YES |
| **P6: Lifecycle event projection** | ExecutionLedger | executionEventSink.onEvent() | Best-effort (isolated) | YES (projection failure isolated) | ❌ NO |
| **P7: ChatMemory persistence** | ChatMemory | chatMemory.add() | Implementation-specific | YES (post-commit action) | ⚠️ SEPARATE (conversation continuity, not recovery correctness) |

**Key Insights**:
- P1, P2, P4, P5 are recovery-critical hard gates
- P6 is best-effort projection (NOT recovery authority)
- P7 is separate concern (conversation state, not execution recovery)

---

## F. Approved Invocation Crash Matrix

### Timeline (from M6-T4 established)

```
T0:  CHECK A (load + validate checkpoint)
T1:  RuntimeBinding resolution
T2:  APPROVAL_GRANTED event
T3:  RESUMED event  
T4:  resolve ToolCallback
T5:  create per-operation ToolObservationContext
T6:  wrap with EvidenceCapturingToolCallback
T7:  create ToolContext
T8:  recordInvocationIntent() begins
T9:  INVOCATION_INTENT fact committed to storage ← UNCERTAINTY BEGINS
T10: recordInvocationIntent() returns success
T11: delegate.call() begins ← Physical invocation MAY start
T12: external request may leave JVM
T13: external side effect MAY occur
T14: delegate returns/throws ← TOOL_EXECUTED/FAILED domain fact
T15: CHECK B (replaceIfVersion or deleteIfVersion)
T16: Checkpoint transition committed
```

### Crash Analysis

| Crash Point | Checkpoint State | Intent State | External Outcome | Recovery Classification | Safe Automatic Action? |
|-------------|------------------|--------------|------------------|------------------------|----------------------|
| **Before T8** | v_N present, op-A pending | Intent absent | No invocation | DEFINITELY_NOT_DISPATCHED | ✅ Execute |
| **During T8-T9** | v_N present, op-A pending | Intent write in-flight | No invocation | **Commit-unknown** | ⚠️ Depends on backend |
| **After T9** | v_N present, op-A pending | Intent present | Unknown | MAY_HAVE_INVOKED | ❌ Fail closed |
| **T10-T11** | v_N present, op-A pending | Intent present | Likely no side effect | MAY_HAVE_INVOKED | ❌ Fail closed |
| **T11-T13** | v_N present, op-A pending | Intent present | **UNKNOWN** | MAY_HAVE_INVOKED | ❌ Fail closed |
| **After T14** | v_N present, op-A pending | Intent present | May have committed | MAY_HAVE_INVOKED | ❌ Fail closed |
| **During T15** | CAS in-flight | Intent present | May have committed | MAY_HAVE_INVOKED | ❌ Fail closed |
| **After T16** | v_N+1 or deleted | Intent present (orphan) | Completed | N/A (checkpoint transitioned) | N/A |

**Critical Observation**: Intent presence + checkpoint presence = uncertain outcome requiring fail-closed recovery.

---

## G. Cross-Authority State Matrix

### G.1 State Combinations

| Checkpoint | Intent | Meaning | Reachable? | Safe? | Recovery Interpretation |
|------------|--------|---------|-----------|-------|------------------------|
| **absent** | absent | Process never created OR fully completed | ✅ YES | ✅ SAFE | No process to recover |
| **present** | absent | Operation pending, gate never crossed | ✅ YES | ✅ SAFE | DEFINITELY_NOT_DISPATCHED → Execute |
| **present** | present | Operation crossed gate, outcome unknown | ✅ YES | ⚠️ UNCERTAIN | MAY_HAVE_INVOKED → Fail closed (Phase 1) |
| **absent** | present | Orphan intent after completion/re-suspension | ✅ YES | ✅ SAFE | No resumable checkpoint → cleanup candidate |

### G.2 Temporal Context Matters

**Before Execution**:
- `present + absent` = Normal approved pending state
- `present + present` = Should NOT occur (intent recorded during execution)

**After Completion**:
- `absent + absent` = Normal clean state
- `absent + present` = Orphan intent (not dangerous, just residual metadata)

**After Re-suspension**:
- `present(v2) + present(op-A from v1)` = Old intent orphaned by transition

**Key Insight**: Orphan intents (checkpoint absent + intent present) are NOT dangerous because:
1. No resumable checkpoint exists
2. operationId is unique per logical operation
3. New suspension generates new operationIds
4. Old intents cannot trigger execution without matching pending checkpoint operation

---

## H. Orphan Invocation Intent Analysis

### H.1 When Orphans Occur

1. **Process Completion**: Checkpoint deleted (v_N), intent(op-A from v_N) remains
2. **Re-suspension**: Checkpoint transitioned (v_N → v_N+1), intent(op-A from v_N) remains
3. **Cleanup Lag**: Intent persisted, checkpoint later removed
4. **Manual Deletion**: Operator deletes checkpoint, intent remains

### H.2 Danger Analysis

**Question**: Can orphan intent create false-safe recovery classification?

**Answer**: ❌ **NO — Orphans are semantically safe**

**Proof**:
1. Recovery classification requires BOTH checkpoint AND intent
2. Query pattern: `hasInvocationIntent(processId, operationId)` where operationId comes from `checkpoint.pendingBatch()`
3. If checkpoint absent → no pending operations to classify
4. If checkpoint present but different operationId → old intent irrelevant
5. operationId is generated fresh per suspension episode: `OperationIds.generate()` → new UUID

**Conclusion**: Orphan intents are retained historical metadata, NOT active recovery hazards.

### H.3 Cleanup Requirements

**REQUIRED FOR NEXT SLICE**: ❌ **NO**

**Justification**:
- Orphans cannot affect new pending operations (different operationIds)
- Orphans cannot trigger execution without matching checkpoint
- Storage hygiene ≠ recovery correctness

**Classification**: **JUSTIFIED LATER** (lifecycle/retention concern, not T4D blocker)

**Future Cleanup API**: 
- `deleteInvocationIntent(processId, operationId)` — per-operation cleanup
- `deleteByProcess(processId)` — bulk process cleanup after completion

**Retention Policy**: Defer to operationalization milestone

---

## I. Re-suspension Intent Analysis

### Scenario

```
Checkpoint v1: op-A pending
↓
op-A executes successfully
↓
Model produces new approval-required: op-B
↓
CHECK B: replaceIfVersion(v1 → v2)
↓
Checkpoint v2: op-B pending
Intent store: intent(op-A) remains
```

### Question

Does old `intent(op-A)` interfere with recovery classification for v2?

### Analysis

**Recovery classification query**:
```java
for (PendingToolCall operation : checkpoint.pendingBatch()) {
  boolean hasIntent = invocationStateStore.hasInvocationIntent(processId, operation.operationId());
}
```

**v2 pendingBatch contains**: `op-B` (NEW operationId)  
**Intent store contains**: `intent(op-A)` (OLD operationId)  
**Query**: `hasInvocationIntent(processId, op-B)` → **false** (op-B intent not recorded yet)

**Conclusion**: ✅ **Old intent cannot interfere** because operationId uniqueness guarantees:
- Each suspension episode generates fresh operationIds
- Recovery only queries operationIds from current checkpoint.pendingBatch()
- operationId namespace collision impossible (UUID.randomUUID())

**Safety**: Old intent retention is safe.

---

## J. operationId Persistence Scope

### J.1 Current Generation

**Location**: `OperationIds.generate()` → `UUID.randomUUID().toString()`

**Properties**:
- Globally unique (statistical guarantee)
- Generated before suspension
- Persisted in `PendingToolCall`
- Opaque (implementation may change)

### J.2 Persistent Key Sufficiency

**Question**: Is `(processId, operationId)` sufficient as persistent InvocationStateStore key across checkpoint generations and restart?

**Analysis**:

| Scenario | (processId, operationId) Sufficient? | Reason |
|----------|-------------------------------------|---------|
| Within one JVM | ✅ YES | UUID collision probability negligible |
| Across JVM restart | ✅ YES | processId + operationId stable, loaded from checkpoint |
| Checkpoint v1 → v2 re-suspension | ✅ YES | New operations get new operationIds |
| Same tool name/arguments | ✅ YES | operationId distinguishes logical operations |
| Multi-node concurrent resume | ✅ YES | Both nodes see same (processId, operationId) from checkpoint |
| Provider toolCallId duplicates | ✅ YES | operationId is framework identity, independent of toolCallId |

**Conclusion**: ✅ **(processId, operationId) is sufficient**

**Do NOT add**:
- `checkpointVersion` to key (unless required by future semantics)
- `attemptId` to key (deferred to retry correlation milestone)

### J.3 Uniqueness Guarantee

**operationId uniqueness source**: UUID v4 random generation  
**Collision probability**: ~0 for practical purposes (2^122 space)  
**Verification**: No explicit collision detection needed (statistical guarantee sufficient)

---

