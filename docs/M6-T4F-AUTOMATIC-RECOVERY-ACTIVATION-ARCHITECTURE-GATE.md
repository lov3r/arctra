# M6-T4F — AUTOMATIC RECOVERY ACTIVATION ARCHITECTURE GATE

**Status**: Architecture Analysis Complete  
**Decision**: [To be determined after analysis]  
**Author**: Architecture Analysis  
**Date**: 2024

---

## Executive Summary

**Central Question**: After JVM/process restart, how does Arctra authoritatively determine that a persisted checkpoint should enter the recovery-classification path rather than ordinary resume?

**Frozen Baseline**:
- M6-T4D: CLOSED — Persistence substrate architecture established
- M6-T4D.1: CLOSED — Construction boundary resolved
- M6-T4E: CLOSED — JDBC checkpoint + intent persistence implemented
- M6-T4C.1: CLOSED — Explicit recovery classification pathway verified

**Current State**: 
- ✅ Restart-durable recovery facts exist (checkpoint + intent in JDBC)
- ✅ Recovery classifier can distinguish DEFINITELY_NOT_DISPATCHED vs MAY_HAVE_INVOKED
- ✅ Explicit recovery pathway (`resumeWithRecoveryClassification`) proven safe
- ❌ NO automatic activation — recovery requires manual invocation
- ❌ NO restart detection authority

**Gap**: Runtime cannot determine "this is a post-restart recovery scenario" vs "this is normal concurrent resume"

---

## Table of Contents

1. [Current Source-Truth Call Graph](#1-current-source-truth-call-graph)
2. [Activation Authority Gap](#2-activation-authority-gap)
3. [Restart Detection vs Recovery Ownership](#3-restart-detection-vs-recovery-ownership)
4. [Candidate Architectures](#4-candidate-architectures)
5. [Multi-Node Counterexamples](#5-multi-node-counterexamples)
6. [Candidate Evaluation Matrix](#6-candidate-evaluation-matrix)
7. [Selected Architecture Decision](#7-selected-architecture-decision)
8. [Required Persisted Metadata](#8-required-persisted-metadata)
9. [Checkpoint Schema Impact](#9-checkpoint-schema-impact)
10. [Authority Ownership](#10-authority-ownership)
11. [Multi-Node Safety Analysis](#11-multi-node-safety-analysis)
12. [Next Implementation Slice](#12-next-implementation-slice)
13. [Deferred Work](#13-deferred-work)
14. [Final Decision](#14-final-decision)

---

## 1. Current Source-Truth Call Graph

### 1.1 Resume Entry Points

**Normal Resume Path** (no recovery classification):
```
AgentProcess.resume()
  → DurableResumeStrategy.execute()
    → DurableExecutionEngine.resumeProcess()
      → SpringAiToolCallingEngine.resumeProcess()
        → DurableResumeCoordinator.resume()
```

**Explicit Recovery Path** (M6-T4C.1):
```
Test/Operator Manual Call
  → DurableResumeCoordinator.resumeWithRecoveryClassification()
```

**Cross-Runtime Resume**:
```
AgentRuntime.resumeProcess()
  → DefaultAgentRuntime.resumeProcess()
    → DurableExecutionEngine.resumeProcess()
      → SpringAiToolCallingEngine.resumeProcess()
        → DurableResumeCoordinator.resume()
```

### 1.2 Current Recovery Decision Point

**Location**: Test code or operator manual invocation

**Authority**: NONE — Test explicitly chooses which path

**Current Test Pattern**:
```java
// ExplicitRecoveryPathTest.java:86-88
AgentResult result = coordinator.resumeWithRecoveryClassification(
    "proc-test", 1L, new ContinuationSignal.ApprovalSignal(true, "test")
);
```

### 1.3 Critical Observation

**NO PRODUCTION CODE INVOKES `resumeWithRecoveryClassification()`**

- `SpringAiToolCallingEngine.resumeProcess()` always delegates to `coordinator.resume()`
- No runtime startup logic scans for orphaned checkpoints
- No checkpoint metadata indicates "created before restart"
- No automatic detection mechanism exists

---

## 2. Activation Authority Gap

### 2.1 What Recovery Needs to Know

Recovery classification pathway requires answering:

**Q1**: Is this resume happening in the SAME execution incarnation that created the checkpoint?  
**Q2**: Or is this a DIFFERENT execution incarnation (post-restart)?

**Why it matters**:

| Scenario | Classification Needed? | Reason |
|----------|----------------------|--------|
| Same JVM, concurrent resume | ❌ NO | at-least-once semantics, no restart boundary crossed |
| Post-restart resume | ✅ YES | Cannot trust in-memory state, intent may exist from pre-crash execution |
| Multi-node, Node A alive | ❌ COMPLEX | Node B cannot conclude Node A crashed |

### 2.2 What Existing Identities DO NOT Prove

**processId**: Stable across restarts (by design) — NOT a restart marker  
**checkpointVersion**: Increments on re-suspension — NOT a restart marker  
**runtimeBindingKey**: Logical binding — NOT tied to JVM instance  
**sessionId**: Conversation identity — NOT tied to JVM instance  
**operationId**: Tool operation UUID — NOT tied to execution incarnation  
**CheckpointStore presence**: Persistence — NOT proof of restart  
**InvocationStateStore presence**: Persistence — NOT proof of restart

### 2.3 Missing Authority

**No durable fact currently records**:
- "This checkpoint was created by execution incarnation X"
- "Current execution incarnation is Y"
- "X ≠ Y → restart occurred"

**No runtime logic currently establishes**:
- Execution incarnation identity at startup
- Correlation between checkpoint creator and current executor
- Authorization to recover vs resume normally

---

## 3. Restart Detection vs Recovery Ownership

### 3.1 Two Distinct Problems

**Problem 1: Restart Detection**

> "The current execution incarnation is NOT the one that created this checkpoint"

This is a FACT about execution boundary crossing.

**Problem 2: Recovery Ownership**

> "This node/runtime is AUTHORIZED to recover this checkpoint"

This is a DECISION about multi-node coordination.

### 3.2 Single-Node vs Multi-Node

**Single-Node Deployment**:
- Restart detection SUFFICIENT for automatic recovery
- If `executionEpoch(checkpoint) ≠ executionEpoch(current)` → recovery mode
- No ownership ambiguity (only one node exists)

**Multi-Node Deployment**:
- Restart detection NOT SUFFICIENT
- Node B detecting "Node A's epoch ≠ my epoch" does NOT prove Node A crashed
- Node A may still be alive and processing the checkpoint
- Requires ownership coordination (claim/lease/fencing)

### 3.3 Critical Distinction

**Restart detection answers**: "Has execution boundary been crossed?"  
**Recovery ownership answers**: "Am I authorized to act on this?"

**These are separate authorities**.

---

## 4. Candidate Architectures

### Candidate A: Startup Always Means Recovery

**Mechanism**: Every persisted checkpoint loaded after runtime startup enters recovery mode.

**Implementation**:
```java
class SpringAiToolCallingEngine {
  private boolean startupComplete = false;
  
  public AgentResult resumeProcess(...) {
    if (!startupComplete) {
      return coordinator.resumeWithRecoveryClassification(...);
    } else {
      return coordinator.resume(...);
    }
  }
}
```

**Analysis**:

✅ **Pros**:
- Zero persistence overhead
- Simple logic
- Works for single-node restart

❌ **Cons**:
- **UNSAFE in multi-node**: Node B startup does NOT prove Node A crashed
- **UNSAFE for rolling deployment**: New nodes starting while old nodes run
- **UNSAFE for scale-out**: Adding capacity triggers recovery mode incorrectly
- First resume after startup triggers recovery even if checkpoint created 1ms ago

**Verdict**: ❌ **REJECT** — Multi-node unsafe

---

### Candidate B: executionEpoch

**Mechanism**: Persist runtime/execution epoch identity. Correlate checkpoint creator epoch with current epoch.

**Required Metadata**:
```java
record SuspensionCheckpoint(
  // ... existing fields
  String executionEpoch  // NEW: JVM/runtime incarnation identity
)
```

**Epoch Generation**:
- Created at runtime/engine startup
- Format: `UUID.randomUUID().toString()` or `"jvm-" + ManagementFactory.getRuntimeMXBean().getStartTime()`
- Persisted with every checkpoint

**Recovery Decision**:
```java
if (!checkpoint.executionEpoch().equals(currentExecutionEpoch)) {
  // Checkpoint created by DIFFERENT execution incarnation → recovery mode
  return coordinator.resumeWithRecoveryClassification(...);
} else {
  // Same incarnation → normal resume
  return coordinator.resume(...);
}
```

**Analysis**:

✅ **Pros**:
- Solves restart detection for single-node
- Clear execution boundary marker
- No coordination overhead for single-node
- Checkpoint metadata accurately tracks creator

❌ **Cons**:
- **DOES NOT SOLVE multi-node ownership**
- Node B seeing Node A's epoch ≠ its own epoch does NOT prove Node A crashed
- False positive in multi-node: Node B incorrectly enters recovery while Node A alive
- Schema change required

**Restart Detection**: ✅ YES  
**Recovery Ownership**: ❌ NO

**Verdict**: ⚠️ **PARTIAL** — Solves single-node, insufficient for multi-node

---

### Candidate C: Durable Runtime/Process Incarnation

**Mechanism**: Similar to executionEpoch, but with more explicit "incarnation" semantics.

**Comparison with Candidate B**:
- Terminology difference only
- Same technical substance: persist creator identity, compare at resume
- Same strengths: restart detection for single-node
- Same weakness: no multi-node ownership

**Verdict**: ⚠️ **EQUIVALENT TO B** — Same solution with different naming

---

### Candidate D: Explicit Recovery Activation Only

**Mechanism**: NO automatic recovery. Operator/application explicitly invokes recovery pathway.

**Current State**: This is what exists today (M6-T4C.1)

**Implementation**:
```java
// Operator must explicitly call:
coordinator.resumeWithRecoveryClassification(processId, version, signal);

// Normal resume remains default:
engine.resumeProcess(processId, version, signal);
```

**Analysis**:

✅ **Pros**:
- Already implemented and proven safe
- No false positives (operator controls activation)
- Works correctly in all deployment topologies
- No schema changes required
- No coordination overhead

❌ **Cons**:
- NOT automatic — requires human/external detection
- Manual intervention required after every restart
- Does not fulfill "automatic recovery activation" mission

**Verdict**: ✅ **SAFE FALLBACK** — Should remain available, but does not meet automatic recovery goal

---

### Candidate E: Claim / Lease / Fencing

**Mechanism**: Distributed ownership coordination for multi-node recovery.

**Required Components**:
1. **Claim/Lease Table**: Track which node owns which checkpoint
2. **Heartbeat**: Periodic renewal to prove liveness
3. **Fencing Tokens**: Prevent stale executors from committing
4. **Expiry Logic**: Detect crashed nodes via lease timeout

**Example Schema**:
```sql
CREATE TABLE recovery_ownership (
  process_id VARCHAR(255) PRIMARY KEY,
  owner_node_id VARCHAR(255) NOT NULL,
  lease_acquired_at TIMESTAMP NOT NULL,
  lease_expires_at TIMESTAMP NOT NULL,
  fencing_token BIGINT NOT NULL
);
```

**Recovery Flow**:
```
Node B startup:
  1. Scan for checkpoints
  2. Attempt to claim checkpoint (INSERT or UPDATE with fencing token)
  3. If claim succeeds → authorized to recover
  4. If claim fails → another node owns it
  5. Periodic heartbeat to maintain lease
  6. Recovery proceeds with fencing token in CHECK B
```

**Analysis**:

✅ **Pros**:
- Solves multi-node ownership problem
- Prevents duplicate recovery
- Enables true automatic multi-node recovery
- Industry-standard pattern (Kafka, ZooKeeper, etc.)

❌ **Cons**:
- **SIGNIFICANT complexity increase**
- New ownership authority (not just restart detection)
- Heartbeat infrastructure required
- Clock synchronization sensitivity
- Lease expiry tuning (too short = false positive, too long = slow recovery)
- Fencing token coordination with CHECK B CAS
- New failure modes (lease service unavailable)
- **NOT REQUIRED for single-node deployments**

**Restart Detection**: N/A (solves different problem)  
**Recovery Ownership**: ✅ YES

**Verdict**: ⚠️ **CORRECT BUT HEAVY** — Required for multi-node, but separate milestone

---

### Candidate F: Hybrid (executionEpoch + Explicit Fallback)

**Mechanism**: 
1. **Automatic activation** for single-node via executionEpoch
2. **Explicit activation** remains available for multi-node/complex scenarios

**Implementation**:
```java
public AgentResult resumeProcess(String processId, long version, ContinuationSignal signal) {
  SuspensionCheckpoint checkpoint = loadCheckpoint(processId);
  
  // Automatic restart detection (single-node safe)
  if (isRestartDetected(checkpoint)) {
    return coordinator.resumeWithRecoveryClassification(processId, version, signal);
  }
  
  // Normal resume (concurrent, same incarnation)
  return coordinator.resume(processId, version, signal);
}

private boolean isRestartDetected(SuspensionCheckpoint checkpoint) {
  return !checkpoint.executionEpoch().equals(currentExecutionEpoch);
}
```

**Multi-Node Handling**:
- Automatic activation DISABLED in multi-node config
- Operator uses explicit recovery pathway
- Or wait for future claim/lease milestone

**Analysis**:

✅ **Pros**:
- Automatic recovery for common single-node case
- Safe fallback for complex scenarios
- Incremental path: single-node now, multi-node later
- Clear deployment boundary (single vs multi-node)

⚠️ **Cons**:
- Multi-node deployments lose automatic recovery (until lease milestone)
- Configuration required to disable automatic in multi-node
- Two modes to document and test

**Verdict**: ✅ **PRAGMATIC** — Solves 80% case (single-node), defers 20% case (multi-node ownership)

---

## 5. Multi-Node Counterexamples

### Scenario 1: Node A and Node B Both Alive

**Setup**:
- Node A creates checkpoint with `executionEpoch = "epoch-A"`
- Node A records invocation intent for `op-X`
- Node A crashes AFTER intent write, BEFORE CHECK B
- Node B starts up with `executionEpoch = "epoch-B"`

**Node B sees**:
- Checkpoint exists with `executionEpoch = "epoch-A"`
- `"epoch-A" ≠ "epoch-B"` → restart detected
- Intent exists for `op-X`

**What Node B CANNOT conclude**:
- ❌ Node A crashed (A might still be alive)
- ❌ Node A will not resume this checkpoint (A might resume any second)
- ❌ Node B is authorized to recover (no ownership proof)

**If Node B proceeds with recovery**:
- **Race condition**: Node A and Node B both attempt CHECK B
- CAS ensures only one succeeds
- But BOTH may execute `op-X` physically (at-least-once)
- **Result**: Correct (at-least-once semantics preserved) but wasteful

**Risk Assessment**: 
- ⚠️ **Functional correctness**: SAFE (CAS prevents double commit)
- ❌ **Resource waste**: Duplicate execution
- ❌ **Side effect duplication**: at-least-once semantics tested

---

### Scenario 2: Node A Crashes, Node B Survives

**Setup**:
- Node A creates checkpoint, records intent, crashes
- Node B was already running (different epoch)
- Node B scans for orphaned checkpoints

**What Node B needs to know**:
1. Node A's checkpoint exists ✅ (can query CheckpointStore)
2. Node A crashed ❓ (no authority proves this)
3. Node B is authorized to recover ❓ (no ownership grant)

**Without lease/claim**:
- Node B sees foreign epoch → assumes crash → starts recovery
- If Node A actually restarted (new epoch) → duplicate recovery
- If Node A still processing (old epoch) → duplicate recovery

**Risk**: False positive recovery activation

---

### Scenario 3: Stale Node Returns

**Setup**:
- Node A creates checkpoint with `epoch-A`
- Node A network partitioned (appears crashed)
- Node B claims checkpoint, performs recovery, completes
- Node A network heals, still has `epoch-A`, attempts CHECK B

**Without fencing**:
- Node A's CHECK B fails (checkpoint already deleted by B) ✅
- Safe outcome, but Node A wasted work

**With fencing**:
- Node A's CHECK B includes stale fencing token → rejected immediately
- Earlier failure detection

---

### Scenario 4: Two Nodes Restart Simultaneously

**Setup**:
- Both Node A and Node B restart
- Both see checkpoint with old epoch
- Both detect "restart" condition

**Without ownership coordination**:
- Both attempt recovery
- Race to CHECK B
- CAS ensures one wins
- Result: SAFE but wasteful

---

## 6. Candidate Evaluation Matrix

| Criterion | A: Startup | B: Epoch | C: Incarnation | D: Explicit | E: Claim/Lease | F: Hybrid |
|-----------|-----------|----------|----------------|-------------|----------------|-----------|
| **Restart Detection** | ❌ False positive | ✅ YES | ✅ YES | N/A | N/A | ✅ YES |
| **Multi-Node Ownership** | ❌ NO | ❌ NO | ❌ NO | N/A | ✅ YES | ❌ NO (deferred) |
| **Single-Node Automatic** | ❌ Unsafe | ✅ YES | ✅ YES | ❌ NO | ✅ YES | ✅ YES |
| **Multi-Node Safe** | ❌ NO | ⚠️ Wasteful | ⚠️ Wasteful | ✅ YES | ✅ YES | ⚠️ Manual |
| **Schema Change** | ✅ None | ❌ Add epoch | ❌ Add epoch | ✅ None | ❌ New table | ❌ Add epoch |
| **Coordination Overhead** | ✅ None | ✅ None | ✅ None | ✅ None | ❌ Heartbeat | ✅ None |
| **Implementation Complexity** | ✅ Trivial | ✅ Low | ✅ Low | ✅ Done | ❌ High | ✅ Medium |
| **False Positive Risk** | ❌ High | ✅ None | ✅ None | ✅ None | ✅ None | ⚠️ Multi-node |
| **Correctness** | ❌ UNSAFE | ✅ SAFE | ✅ SAFE | ✅ SAFE | ✅ SAFE | ✅ SAFE |
| **Meets Mission** | ❌ NO | ⚠️ Partial | ⚠️ Partial | ❌ NO | ✅ YES | ⚠️ Partial |

---

## 7. Selected Architecture Decision

### 7.1 Decision

**SELECTED**: **Candidate F — Hybrid (executionEpoch + Explicit Fallback)**

**Rationale**:

1. **Pragmatic incremental path**: Solves common single-node case immediately
2. **Safe**: No false positives in correct configuration
3. **Preserves explicit path**: Multi-node can use manual activation
4. **Clear future**: Ownership coordination (Candidate E) becomes separate milestone
5. **Minimal complexity**: Does not prematurely introduce lease/claim infrastructure

### 7.2 Architecture Components

**Component 1: executionEpoch Metadata**
- Generated at SpringAiToolCallingEngine construction
- Format: UUID or JVM startup timestamp
- Persisted in SuspensionCheckpoint

**Component 2: Restart Detection Logic**
- Compare `checkpoint.executionEpoch()` vs `engine.currentExecutionEpoch`
- Mismatch → restart boundary crossed → automatic recovery mode

**Component 3: Configuration Guard**
- Single-node mode: automatic recovery enabled (default)
- Multi-node mode: automatic recovery disabled (explicit activation required)
- Configuration: system property or constructor parameter

**Component 4: Explicit Pathway Preservation**
- `resumeWithRecoveryClassification()` remains available
- Used in multi-node deployments
- Used when operator wants manual control

### 7.3 Deployment Topology Handling

**Single-Node Deployment** (default):
```
Automatic recovery: ENABLED
Restart detection: executionEpoch mismatch
Recovery activation: Automatic
Operator action: None required
```

**Multi-Node Deployment** (explicit config):
```
Automatic recovery: DISABLED
Restart detection: Not applicable
Recovery activation: Explicit operator call
Operator action: Required after node failure
```

**Future Multi-Node Automatic** (M7 or later):
```
Automatic recovery: ENABLED
Restart detection: executionEpoch mismatch
Ownership coordination: Claim/lease/fencing
Recovery activation: Automatic with ownership grant
Operator action: None required
```

---

## 8. Required Persisted Metadata

### 8.1 executionEpoch

**Type**: `String`

**Generation**: At SpringAiToolCallingEngine construction time

**Options**:

**Option A: UUID**
```java
private final String executionEpoch = UUID.randomUUID().toString();
```
- Pros: Globally unique, no clock dependency
- Cons: Opaque, no timestamp information

**Option B: JVM Start Time**
```java
private final String executionEpoch = 
    "jvm-" + ManagementFactory.getRuntimeMXBean().getStartTime();
```
- Pros: Debuggable, correlates with logs
- Cons: Clock skew risk in multi-node (not relevant for restart detection)

**Option C: Composite**
```java
private final String executionEpoch = 
    ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();
```
- Pros: Both readable and unique
- Cons: Longer string

**SELECTED**: **Option A (UUID)** for simplicity and zero clock dependency

### 8.2 Persistence Location

**Field**: `SuspensionCheckpoint.executionEpoch`

**Written**: Every checkpoint create/replace operation

**Read**: At resume to determine restart vs concurrent resume

---

## 9. Checkpoint Schema Impact

### 9.1 Schema Change

**Before** (current):
```java
public record SuspensionCheckpoint(
    int schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences
) {
  public static final int CURRENT_SCHEMA_VERSION = 1;
}
```

**After** (M6-T4F):
```java
public record SuspensionCheckpoint(
    int schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences,
    String executionEpoch  // NEW: JVM incarnation identity for restart detection
) {
  public static final int CURRENT_SCHEMA_VERSION = 2;  // INCREMENTED
}
```

### 9.2 Schema Version Bump

**Old Version**: `schemaVersion = 1` (no executionEpoch)  
**New Version**: `schemaVersion = 2` (with executionEpoch)

### 9.3 Migration Strategy

**Read Compatibility**:
```java
// CheckpointJsonCodec deserialization
if (schemaVersion == 1) {
  // Old checkpoint: no executionEpoch field
  // Treat as "unknown epoch" → cannot detect restart → use explicit recovery
  executionEpoch = null;
} else if (schemaVersion == 2) {
  // New checkpoint: executionEpoch present
  executionEpoch = json.get("executionEpoch").asText();
}
```

**Write Compatibility**:
- New engines always write `schemaVersion = 2` with `executionEpoch`
- Old engines cannot read v2 checkpoints (version validation fails)

**Deployment Strategy**:
- **NOT backward compatible**: Requires complete restart
- All existing checkpoints must complete or be abandoned before upgrade
- Or implement checkpoint migration tool (out of scope for T4F)

### 9.4 JDBC Schema Impact

**Table**: `arctra_checkpoints`

**Column Addition**:
```sql
ALTER TABLE arctra_checkpoints 
ADD COLUMN execution_epoch VARCHAR(255);
```

**NULL Handling**: Old checkpoints have `execution_epoch = NULL`

**Recovery Logic**:
```java
if (checkpoint.executionEpoch() == null) {
  // Old checkpoint → cannot auto-detect restart → require explicit recovery
  throw new IllegalStateException(
      "Checkpoint created by pre-T4F engine. Automatic recovery unavailable. "
      + "Use explicit recovery pathway or complete/abandon checkpoint.");
}
```

---

## 10. Authority Ownership

### 10.1 New Authority: Execution Incarnation

**Owns**: The fact "This execution incarnation is identified by this epoch"

**Scope**: Per SpringAiToolCallingEngine instance

**Lifecycle**: Created at construction, immutable, destroyed at JVM exit

**Persistence**: Written to every SuspensionCheckpoint

### 10.2 Authority Boundaries

| Authority | Owns | Stored In | M6-T4F Change |
|-----------|------|-----------|---------------|
| **CheckpointStore** | Resumable process state | JDBC arctra_checkpoints | ✅ Add execution_epoch column |
| **InvocationStateStore** | Physical invocation intent | JDBC arctra_invocation_intents | ✅ No change |
| **ExecutionLedger** | Event history (projection) | Implementation-specific | ✅ No change |
| **Execution Incarnation** | JVM identity for restart detection | SpringAiToolCallingEngine field | ✅ NEW |

### 10.3 Module Ownership

**executionEpoch Generation**: `SpringAiToolCallingEngine` (arctra-runtime-react)

**executionEpoch Storage**: `SuspensionCheckpoint` (arctra-core)

**Restart Detection Logic**: `SpringAiToolCallingEngine.resumeProcess()` (arctra-runtime-react)

**Rationale**: Restart detection is runtime concern, not core domain model

---

## 11. Multi-Node Safety Analysis

### 11.1 Single-Node Deployment

| Scenario | Restart Detected? | Automatic Recovery? | Safety |
|----------|------------------|---------------------|--------|
| JVM restart, checkpoint exists | ✅ YES (`epoch-A` ≠ `epoch-B`) | ✅ YES | ✅ SAFE |
| Concurrent resume, same JVM | ❌ NO (`epoch-A` = `epoch-A`) | ❌ NO | ✅ SAFE |
| First resume after suspension | ❌ NO (same epoch) | ❌ NO | ✅ SAFE |

**Verdict**: ✅ **SAFE** — Single-node automatic recovery works correctly

### 11.2 Multi-Node Deployment (Automatic Recovery DISABLED)

| Scenario | Configuration | Behavior | Safety |
|----------|--------------|----------|--------|
| Node A crash, Node B survives | Automatic OFF | Node B requires explicit activation | ✅ SAFE (manual) |
| Both nodes alive | Automatic OFF | Only explicit recovery allowed | ✅ SAFE |
| Rolling deployment | Automatic OFF | New nodes do not auto-recover | ✅ SAFE |

**Verdict**: ✅ **SAFE** — Manual activation prevents false positives

### 11.3 Multi-Node Deployment (Automatic Recovery ENABLED - UNSAFE)

| Scenario | Restart Detected? | Risk | Outcome |
|----------|------------------|------|---------|
| Node A checkpoint, Node B startup | ✅ YES (different epochs) | Node B recovers while A alive | ⚠️ Wasteful duplicate execution |
| Both nodes restart | ✅ YES (both see old epoch) | Race to recover | ⚠️ Wasteful duplicate execution |
| Rolling deployment | ✅ YES (new node sees old epoch) | New nodes incorrectly recover | ❌ **UNSAFE** |

**Verdict**: ❌ **UNSAFE** — Automatic recovery in multi-node requires ownership coordination

### 11.4 Configuration Requirement

**MUST prevent unsafe configuration**:

```java
public SpringAiToolCallingEngine(...) {
  if (isMultiNodeDeployment() && automaticRecoveryEnabled) {
    throw new IllegalArgumentException(
        "Automatic recovery cannot be enabled in multi-node deployment without " +
        "ownership coordination. Either disable automatic recovery, " +
        "or deploy single-node, or wait for claim/lease milestone.");
  }
}
```

**Detection Strategy**:
- Explicit constructor parameter: `RecoveryActivationPolicy.SINGLE_NODE` vs `MULTI_NODE_EXPLICIT`
- Environment variable: `ARCTRA_DEPLOYMENT_TOPOLOGY=single-node|multi-node`
- Fail-safe: Default to `MULTI_NODE_EXPLICIT` (conservative)

---

## 12. Next Implementation Slice

### 12.1 Scope: M6-T4F Implementation

**Milestone**: M6-T4F — Automatic Recovery Activation (Single-Node)

**Deliverables**:

1. **executionEpoch field** in SpringAiToolCallingEngine
2. **SuspensionCheckpoint schema v2** with executionEpoch
3. **CheckpointJsonCodec** serialization/deserialization for executionEpoch
4. **JDBC schema migration** (add execution_epoch column)
5. **Restart detection logic** in resumeProcess()
6. **RecoveryActivationPolicy** enum (SINGLE_NODE_AUTO, MULTI_NODE_EXPLICIT)
7. **Configuration validation** (prevent unsafe multi-node auto)
8. **Tests**: Single-node restart detection, multi-node config guard, schema compatibility

**NOT in scope**:
- ❌ Claim/lease/fencing infrastructure
- ❌ Heartbeat/liveness detection
- ❌ Multi-node automatic recovery
- ❌ Checkpoint ownership table
- ❌ Fencing tokens in CHECK B
- ❌ attemptId
- ❌ RecoveryPolicy
- ❌ Retry logic
- ❌ External outcome query
- ❌ Idempotency coordination

### 12.2 Implementation Steps

**Step 1: Core executionEpoch** (arctra-runtime-react)
```java
class SpringAiToolCallingEngine {
  private final String executionEpoch = UUID.randomUUID().toString();
  
  public String getExecutionEpoch() {
    return executionEpoch;
  }
}
```

**Step 2: Schema Update** (arctra-core)
```java
public record SuspensionCheckpoint(
    // ... existing 7 fields
    String executionEpoch  // NEW
) {
  public static final int CURRENT_SCHEMA_VERSION = 2;
}
```

**Step 3: Checkpoint Creation** (arctra-runtime-react)
```java
// SpringAiExecutionLoop checkpoint creation
SuspensionCheckpoint checkpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
    processId,
    checkpointVersion,
    runtimeBindingKey,
    sessionId,
    pendingBatch,
    accumulatedEvidences,
    engine.getExecutionEpoch()  // NEW: inject current epoch
);
```

**Step 4: Restart Detection** (arctra-runtime-react)
```java
public AgentResult resumeProcess(String processId, long version, ContinuationSignal signal) {
  SuspensionCheckpoint checkpoint = loadCheckpointForRestart(processId);
  
  if (isRestartDetected(checkpoint)) {
    return durableResumeCoordinator.resumeWithRecoveryClassification(
        processId, version, signal);
  } else {
    return durableResumeCoordinator.resume(processId, version, signal);
  }
}

private boolean isRestartDetected(SuspensionCheckpoint checkpoint) {
  if (checkpoint.executionEpoch() == null) {
    // Old v1 checkpoint → cannot auto-detect
    throw new IllegalStateException("Cannot auto-detect restart for pre-T4F checkpoint");
  }
  return !checkpoint.executionEpoch().equals(this.executionEpoch);
}
```

**Step 5: Configuration Policy** (arctra-core)
```java
public enum RecoveryActivationPolicy {
  SINGLE_NODE_AUTO,      // Automatic recovery via epoch detection
  MULTI_NODE_EXPLICIT    // Manual recovery activation only
}
```

**Step 6: Configuration Validation** (arctra-runtime-react)
```java
public SpringAiToolCallingEngine(..., RecoveryActivationPolicy recoveryPolicy) {
  this.recoveryPolicy = recoveryPolicy != null 
      ? recoveryPolicy 
      : RecoveryActivationPolicy.MULTI_NODE_EXPLICIT; // Safe default
}
```

**Step 7: JDBC Schema**
```sql
-- Migration script
ALTER TABLE arctra_checkpoints 
ADD COLUMN execution_epoch VARCHAR(255);

-- Old checkpoints have NULL, new checkpoints have UUID
```

**Step 8: JSON Codec** (arctra-runtime-react)
```java
// CheckpointJsonCodec serialization
if (checkpoint.schemaVersion() >= 2) {
  json.put("executionEpoch", checkpoint.executionEpoch());
}

// Deserialization
String executionEpoch = json.has("executionEpoch") 
    ? json.get("executionEpoch").asText() 
    : null;
```

---

## 13. Deferred Work

### 13.1 Multi-Node Automatic Recovery (Future Milestone)

**Milestone**: M7 or M6-T5 (TBD) — Recovery Ownership Coordination

**Requirements**:
- Claim/lease table
- Heartbeat mechanism
- Fencing token coordination
- Lease expiry detection
- Stale executor prevention

**Complexity**: HIGH — Separate architecture gate required

### 13.2 attemptId (Future Milestone)

**Purpose**: Correlate retry attempts for same logical operation

**Deferred Reason**: Requires retry policy architecture (not yet defined)

**Related**: RecoveryPolicy, idempotency coordination

### 13.3 RecoveryPolicy (Future Milestone)

**Purpose**: Pluggable policy for MAY_HAVE_INVOKED decisions

**Options**: 
- Fail closed (current Phase 1)
- Query external system for outcome
- Idempotent retry
- Skip and log

**Requires**: External outcome query infrastructure

### 13.4 Checkpoint Migration Tool

**Purpose**: Migrate v1 checkpoints to v2 without abandoning in-flight work

**Complexity**: Medium — Read v1, populate executionEpoch="migration", write v2

**Priority**: LOW — Can require clean slate for T4F deployment

---

## 14. Final Decision

### 14.1 Architecture Decision

✅ **GO — M6-T4F IMPLEMENTATION MAY BEGIN**

**Selected Architecture**: **Candidate F — Hybrid (executionEpoch + Explicit Fallback)**

**Scope**: Single-node automatic recovery activation

**Mechanism**: executionEpoch restart detection with configuration guard

### 14.2 Core Impact

**Public API Changes**:
- ❌ ZERO new public APIs
- ✅ SuspensionCheckpoint: add executionEpoch field (public record, schema v2)
- ✅ RecoveryActivationPolicy: new public enum (arctra-core)

**Constructor Changes**:
- ✅ SpringAiToolCallingEngine: add RecoveryActivationPolicy parameter (optional, defaults to MULTI_NODE_EXPLICIT)

**Checkpoint Schema**:
- ✅ Version bump: v1 → v2
- ✅ New field: executionEpoch (String, UUID)
- ⚠️ NOT backward compatible (requires clean slate or migration)

### 14.3 Deployment Constraints

**Single-Node Deployment**:
- ✅ Automatic recovery: SAFE and ENABLED
- ✅ Zero operator action required after restart
- ✅ Meets "automatic recovery activation" mission

**Multi-Node Deployment**:
- ⚠️ Automatic recovery: DISABLED (manual activation required)
- ✅ Explicit recovery pathway remains available
- ⚠️ Future milestone required for automatic multi-node

### 14.4 Next Implementation Milestone

**M6-T4F — Automatic Recovery Activation (Single-Node)**

**Deliverables**:
1. executionEpoch field + generation
2. SuspensionCheckpoint schema v2
3. CheckpointJsonCodec v2 support
4. JDBC schema migration
5. Restart detection logic
6. RecoveryActivationPolicy enum + validation
7. Single-node restart detection tests
8. Multi-node configuration guard tests

**Excluded**:
- Multi-node ownership coordination
- Claim/lease/fencing
- attemptId
- RecoveryPolicy
- Retry logic

### 14.5 Future Work

**M7 or M6-T5** (requires separate architecture gate):
- Multi-node automatic recovery
- Ownership coordination (claim/lease/fencing)
- Heartbeat infrastructure
- Fencing token integration with CHECK B

---

## 15. Critical Observations

### 15.1 Restart Detection ≠ Recovery Ownership

This architecture gate has proven these are **separate problems**:

1. **Restart Detection**: "Has the execution boundary been crossed?"
   - Solved by: executionEpoch comparison
   - Sufficient for: Single-node deployments

2. **Recovery Ownership**: "Is this node authorized to recover this checkpoint?"
   - Requires: Claim/lease/fencing
   - Required for: Multi-node automatic recovery

### 15.2 Incremental Path

**Phase 1** (M6-T4F): Single-node automatic recovery  
**Phase 2** (Future): Multi-node ownership coordination  
**Phase 3** (Future): Multi-node automatic recovery

This is a **pragmatic incremental architecture**, not a compromise.

### 15.3 at-least-once Semantics Preserved

Even in wasteful multi-node scenarios (if auto-recovery misconfigured):
- ✅ Functional correctness preserved (CAS prevents double commit)
- ✅ at-least-once semantics upheld
- ⚠️ Resource waste (duplicate execution)
- ❌ Configuration error (should be prevented by validation)

### 15.4 Explicit Pathway Remains Critical

`resumeWithRecoveryClassification()` is NOT deprecated:
- Required for multi-node deployments (M6-T4F)
- Useful for manual operator control
- Testing and validation
- Fallback when automatic detection fails

---

## AW. HARD STOP

M6-T4F Architecture Gate **COMPLETE**.

**DO NOT IMPLEMENT**:
- executionEpoch field
- SuspensionCheckpoint schema v2
- CheckpointJsonCodec v2 serialization
- JDBC schema migration
- Restart detection logic
- RecoveryActivationPolicy enum
- Configuration validation
- Claim/lease/fencing
- Heartbeat infrastructure
- attemptId
- RecoveryPolicy
- Retry logic
- External outcome query

**Awaiting**: Architecture review and M6-T4F implementation approval.

---

**END OF ARCHITECTURE GATE**

