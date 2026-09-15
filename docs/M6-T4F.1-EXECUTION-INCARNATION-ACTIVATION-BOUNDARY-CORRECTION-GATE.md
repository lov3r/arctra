# M6-T4F.1 — EXECUTION INCARNATION & ACTIVATION BOUNDARY CORRECTION GATE

**Status**: Architecture Correction Complete  
**Decision**: [To be determined after analysis]  
**Author**: Architecture Correction Analysis  
**Date**: 2024

---

## Executive Summary

**Mission**: Correct 4 architectural blockers in M6-T4F before implementation can proceed.

**Blockers**:
1. **executionEpoch Scope** — Engine instance ≠ JVM/process restart
2. **Explicit Recovery Entry** — Package-private method not externally reachable
3. **JDBC Column Necessity** — JSON already persists executionEpoch
4. **Public API Accounting** — Undercounted changes

**Outcome**: [To be determined]

---

## Table of Contents

1. [Blocker A: executionEpoch Scope](#blocker-a-executionepoch-scope)
2. [Blocker B: Explicit Recovery Path Reachability](#blocker-b-explicit-recovery-path-reachability)
3. [Blocker C: JDBC execution_epoch Column](#blocker-c-jdbc-execution_epoch-column)
4. [Blocker D: Public API Accounting](#blocker-d-public-api-accounting)
5. [Milestone Semantics Clarification](#milestone-semantics-clarification)
6. [Multi-Node Boundary Clarification](#multi-node-boundary-clarification)
7. [Final Decisions Matrix](#final-decisions-matrix)
8. [Corrected Architecture](#corrected-architecture)
9. [Final Decision](#final-decision)

---

## Blocker A: executionEpoch Scope

### A.1 Problem Statement

**M6-T4F claimed**:
```
executionEpoch generated when SpringAiToolCallingEngine is constructed
scope = Engine instance
```

**Counterexample**:
```
Same JVM, no restart:
  Engine A → epoch-A → creates checkpoint
  Engine B → epoch-B → resumes checkpoint
  epoch-A ≠ epoch-B → FALSE POSITIVE "restart detected"
```

**Failure**: Engine instance reconstruction ≠ JVM/process restart

### A.2 Source Evidence

**Current Architecture** (from ThreeRuntimeRecoveryTest.java:107-117):
```java
// Runtime A
SpringAiToolCallingEngine engineA = new SpringAiToolCallingEngine(...);
DefaultAgentRuntime runtimeA = new DefaultAgentRuntime(engineA);

// ... checkpoint created by engineA

// Runtime B (same JVM, different Engine instance)
SpringAiToolCallingEngine engineB = new SpringAiToolCallingEngine(...);
DefaultAgentRuntime runtimeB = new DefaultAgentRuntime(engineB);

// resumeProcess() called on engineB
```

**Critical Observation**: 
- Multiple Engine instances in same JVM is NORMAL (tests prove this)
- Multiple Runtime instances in same JVM is NORMAL
- Engine reconstruction happens WITHOUT JVM restart

### A.3 Authority Options

**Option 1: Engine Instance Scope** ❌
- Generation: `new SpringAiToolCallingEngine()` → `UUID.randomUUID()`
- Problem: Engine reconstruction triggers false positive
- Verdict: **INCORRECT**

**Option 2: Runtime Instance Scope** ❌
- Generation: `new DefaultAgentRuntime()` → `UUID.randomUUID()`
- Problem: Runtime reconstruction triggers false positive
- Shared Runtime with multiple Engines = complex
- Verdict: **INCORRECT**

**Option 3: JVM/Process Incarnation** ✅
- Generation: **Once per JVM startup**, shared across all Engine/Runtime instances
- Implementation: Static initialization or singleton pattern
- Invariant: Engine/Runtime reconstruction in SAME JVM → SAME epoch
- Verdict: **CORRECT**

**Option 4: Application-Provided Incarnation** ✅
- Generation: Application provides incarnation ID at construction
- Application responsibility: Same incarnation for all engines in same JVM
- Flexibility: Allows custom incarnation semantics
- Verdict: **CORRECT but deferred** (requires public API design)

### A.4 Selected Solution

**SELECTED**: **Option 3 — JVM/Process Incarnation (Static)**

**Implementation**:
```java
// Package-private singleton (arctra-runtime-react)
final class ExecutionIncarnation {
  private static final String INSTANCE = UUID.randomUUID().toString();
  
  static String current() {
    return INSTANCE;
  }
}

// SpringAiToolCallingEngine usage
class SpringAiToolCallingEngine {
  private final String executionEpoch = ExecutionIncarnation.current();
}
```

**Guarantees**:
- ✅ Multiple Engine instances in same JVM → SAME epoch
- ✅ Multiple Runtime instances in same JVM → SAME epoch
- ✅ JVM restart → NEW epoch
- ✅ Engine reconstruction alone does NOT trigger false restart detection

**Scope**: Per-JVM (ClassLoader-specific)

**Lifecycle**: Initialized when `ExecutionIncarnation` class is first loaded

---

## Blocker B: Explicit Recovery Path Reachability

### B.1 Problem Statement

**M6-T4F claimed**:
```
MULTI_NODE_EXPLICIT
→ operator/application uses explicit recovery pathway
```

**Reality Check**: Is explicit recovery actually reachable from external code?

### B.2 Source Evidence

**Method Signature** (DurableResumeCoordinator.java:232):
```java
AgentResult resumeWithRecoveryClassification(
    String processId, long checkpointVersion, ContinuationSignal signal)
```

**Class Visibility** (DurableResumeCoordinator.java:61):
```java
final class DurableResumeCoordinator {  // NO public modifier = package-private
```

**Package**: `cn.bitcss.arctra.runtime.react` (runtime implementation, not public API)

**External Reachability**: ❌ **NONE**

**Production Call Sites**: ❌ **ZERO** (only test code)

**Public Entry Points**:
- ✅ `AgentRuntime.resumeProcess()` → delegates to `Engine.resumeProcess()` → calls `coordinator.resume()` (normal path)
- ❌ NO public method calls `coordinator.resumeWithRecoveryClassification()`

### B.3 Conclusion

**FACT**: Explicit recovery pathway is NOT externally reachable today.

**M6-T4F claim of "operator/application uses explicit recovery" is INVALID.**

### B.4 Options

**Option A: T4F Single-Node Auto Only**

- Scope: ONLY implement `SINGLE_NODE_AUTO` mode
- Multi-node: Explicitly DEFERRED (no automatic, no explicit)
- No `RecoveryActivationPolicy` enum needed
- Simplest T4F scope

**Option B: Add Public Explicit Recovery Entry**

Define minimal public API for explicit recovery:
```java
// AgentRuntime (arctra-core)
public interface AgentRuntime {
  AgentResult resumeProcess(...);  // Existing
  
  AgentResult resumeProcessWithRecovery(  // NEW
      String processId, 
      long checkpointVersion, 
      ContinuationSignal signal
  );
}
```

**Option C: Defer All Explicit Recovery**

- T4F implements ONLY automatic mode selection logic
- Explicit recovery remains test-only until future milestone defines public API
- Multi-node deployments: UNSUPPORTED until claim/lease milestone

### B.5 Selected Solution

**SELECTED**: **Option A — T4F Single-Node Auto Only**

**Rationale**:
1. No false claims about external reachability
2. Simplest T4F scope
3. Multi-node explicit recovery is SEPARATE concern (requires API design)
4. Can add public explicit entry in future milestone when needed

**T4F Scope**:
- ✅ Single-node automatic mode selection
- ❌ Multi-node explicit recovery (deferred)
- ❌ Public explicit recovery API (deferred)
- ❌ `RecoveryActivationPolicy` enum (not needed)

---

## Blocker C: JDBC execution_epoch Column

### C.1 Problem Statement

**M6-T4F proposed**:
```sql
ALTER TABLE arctra_checkpoints 
ADD COLUMN execution_epoch VARCHAR(255);
```

**Question**: Is this necessary when checkpoint is already JSON-serialized?

### C.2 Source Evidence

**Current JDBC Schema** (JdbcCheckpointStore.java):
```sql
CREATE TABLE IF NOT EXISTS arctra_checkpoints (
  process_id VARCHAR(255) PRIMARY KEY,
  checkpoint_version BIGINT NOT NULL,
  checkpoint_data TEXT NOT NULL  -- Entire SuspensionCheckpoint as JSON
)
```

**JSON Serialization** (CheckpointJsonCodec.java:106-150):
- Entire `SuspensionCheckpoint` serialized to `checkpoint_data`
- All fields including future `executionEpoch` will be in JSON

**Query Patterns**:
- `load(processId)` — Loads by process_id, deserializes JSON → has executionEpoch ✅
- `create(checkpoint)` — Serializes entire checkpoint → stores executionEpoch ✅
- `replaceIfVersion(...)` — CAS on version, updates JSON → updates executionEpoch ✅

**NO queries currently need**:
- Filter by execution_epoch
- Index on execution_epoch
- Join on execution_epoch
- Scan for "checkpoints from old epoch"

### C.3 Authority Analysis

**If executionEpoch in JSON only**:
- ✅ Single source of truth
- ✅ No synchronization risk (can't diverge)
- ✅ Schema evolution via JSON (no DDL migration)
- ❌ Cannot query "all checkpoints from epoch X" without full table scan + JSON parse

**If executionEpoch in dedicated column**:
- ✅ Queryable without JSON parse
- ❌ Dual representation (JSON + column must stay in sync)
- ❌ Write complexity (UPDATE both column and JSON)
- ❌ Authority ambiguity (which is truth if they diverge?)
- ❌ Schema migration required (ALTER TABLE)

### C.4 Startup Scanning Analysis

**M6-T4F does NOT include**:
- ❌ Startup checkpoint enumeration
- ❌ "Discover all orphaned checkpoints" feature
- ❌ Automatic batch recovery

**IF future milestone adds startup scanning**:
- Requirement: "Find all checkpoints with executionEpoch ≠ current"
- With JSON-only: Full table scan + deserialize each checkpoint
- With column: Indexed query `SELECT * WHERE execution_epoch != ?`

**Decision**: Cross this bridge when startup scanning is actually designed (not T4F scope)

### C.5 Selected Solution

**SELECTED**: **executionEpoch ONLY in JSON (no dedicated column)**

**Rationale**:
1. ✅ Single authority (no synchronization risk)
2. ✅ Current query patterns don't need column
3. ✅ Simpler implementation (no column management)
4. ✅ Schema evolution without DDL
5. ⚠️ Future startup scanning may require full scan (acceptable until proven bottleneck)

**T4F JDBC Impact**:
- ❌ NO `ALTER TABLE` required
- ✅ `SuspensionCheckpoint` adds `executionEpoch` field
- ✅ `CheckpointJsonCodec` serializes/deserializes it
- ✅ Stored in `checkpoint_data` TEXT column (existing)

---

## Blocker D: Public API Accounting

### D.1 Problem Statement

**M6-T4F claimed**:
```
Public API Changes: ZERO
```

**But also stated**:
```
SuspensionCheckpoint: add executionEpoch field (public record, schema v2)
RecoveryActivationPolicy: new public enum (arctra-core)
SpringAiToolCallingEngine: add RecoveryActivationPolicy parameter
```

**Contradiction**: Cannot be "ZERO public API changes" while adding public types and fields.

### D.2 Accurate Accounting

**SuspensionCheckpoint** (arctra-core):
```java
public record SuspensionCheckpoint(
    // ... 7 existing fields
    String executionEpoch  // NEW COMPONENT
) {
  public static final int CURRENT_SCHEMA_VERSION = 2;  // CHANGED
}
```

**Impact**: 
- ✅ Public API ADDITION (new component in public record)
- ✅ Schema version bump (public constant change)
- ⚠️ Breaking change (existing code constructing checkpoints must update)

**RecoveryActivationPolicy** (IF included):
```java
public enum RecoveryActivationPolicy {  // NEW PUBLIC TYPE
  SINGLE_NODE_AUTO,
  MULTI_NODE_EXPLICIT
}
```

**Impact**: 
- ✅ New public type in arctra-core
- (But Blocker B determined this is NOT needed for T4F)

**SpringAiToolCallingEngine Constructor** (arctra-runtime-react):
- Current: 8 parameters (or 7 for deprecated constructor)
- Proposed: +1 parameter for RecoveryActivationPolicy
- Impact: ⚠️ Constructor signature change (but Blocker B rejected this)

### D.3 Corrected Accounting

**With Blocker A/B/C corrections applied**:

**Public API Changes**:
1. ✅ `SuspensionCheckpoint` — add `executionEpoch` component (String)
2. ✅ `SuspensionCheckpoint.CURRENT_SCHEMA_VERSION` — bump to 2

**Constructor Changes**:
- ❌ NONE (no `RecoveryActivationPolicy` parameter)

**New Public Types**:
- ❌ NONE (no `RecoveryActivationPolicy` enum)

**Accurate Statement**:
- **Public API delta**: +1 component in public record
- **Breaking change**: YES (checkpoint construction signature changed)
- **Constructor delta**: ZERO (single-node mode hardcoded)

---

## Milestone Semantics Clarification

### Current M6-T4F Scope (from T4F report)

**Claimed**: "Automatic Recovery Activation"

**Implied**:
- JVM startup → discover checkpoints → automatically recover

**Reality from source code**:
- ❌ NO startup scanning
- ❌ NO checkpoint enumeration
- ❌ NO automatic batch recovery
- ✅ ONLY: `resumeProcess()` decides which path (normal vs recovery-classified)

### Corrected Milestone Semantics

**Accurate Name**: **M6-T4F — Automatic Recovery Mode Selection**

**What T4F Actually Implements**:
```
Application calls: runtime.resumeProcess(processId, version, signal)
                          ↓
Engine inspects: checkpoint.executionEpoch vs current epoch
                          ↓
                    Different epochs?
                    /              \
                  YES               NO
                   ↓                 ↓
        resumeWithRecoveryClassification   resume (normal)
```

**NOT implemented**:
- Startup-time checkpoint discovery
- Automatic resume initiation
- Background recovery worker

**Correct characterization**: 
- **Automatic MODE SELECTION** ✅
- **Automatic RECOVERY INITIATION** ❌

---

## Multi-Node Boundary Clarification

### What executionEpoch Proves

**✅ CAN prove**:
```
checkpoint.executionEpoch ≠ current.executionEpoch
→ checkpoint created by DIFFERENT execution incarnation
→ execution boundary crossed
```

**❌ CANNOT prove**:
```
- Original node is dead
- Original node will not resume this checkpoint
- Current node owns recovery rights
- No other node will attempt recovery
```

### Single-Node vs Multi-Node

**Single-Node Deployment**:
- One JVM, one epoch active at any time
- `checkpoint.epoch ≠ current.epoch` → proves JVM restarted
- Safe to automatically enter recovery mode ✅

**Multi-Node Deployment**:
- Multiple JVMs, multiple epochs simultaneously
- Node B sees `checkpoint.epoch(A) ≠ current.epoch(B)`
- Does NOT prove Node A crashed
- Does NOT prove Node B owns recovery
- Automatic recovery mode selection → UNSAFE ❌

### Configuration Reality

**M6-T4F proposed**:
```java
enum RecoveryActivationPolicy {
  SINGLE_NODE_AUTO,
  MULTI_NODE_EXPLICIT
}
```

**Problem**: Framework cannot automatically detect deployment topology.

**Reality**:
- Application/operator must explicitly configure deployment mode
- No magic detection of "am I single-node or multi-node?"
- Configuration error → unsafe behavior

**Corrected approach**:
- T4F: Hardcode single-node automatic mode selection
- Multi-node: UNSUPPORTED in T4F (requires future claim/lease milestone)
- No configuration enum (nothing to configure)

---

## Final Decisions Matrix

| Question | Answer |
|----------|--------|
| **Selected epoch authority** | JVM/Process Incarnation (static singleton) |
| **Epoch generation owner** | `ExecutionIncarnation` class (package-private, arctra-runtime-react) |
| **Epoch scope** | Per-JVM (ClassLoader-scoped static) |
| **Can multiple Engines in same JVM share epoch?** | ✅ YES (REQUIRED) |
| **Can Engine reconstruction trigger false restart?** | ❌ NO (multiple engines share same JVM epoch) |
| **SuspensionCheckpoint executionEpoch required?** | ✅ YES (new String component) |
| **Checkpoint schema bump required?** | ✅ YES (v1 → v2) |
| **Dedicated JDBC execution_epoch column required?** | ❌ NO (JSON-only sufficient) |
| **RecoveryActivationPolicy required now?** | ❌ NO (single-node hardcoded) |
| **Explicit recovery public entry exists today?** | ❌ NO (package-private only) |
| **If not, T4F treatment** | DEFER multi-node explicit recovery to future milestone |
| **Single-node automatic mode selection** | ✅ SUPPORTED (T4F scope) |
| **Multi-node automatic recovery** | ❌ UNSUPPORTED (deferred to claim/lease milestone) |
| **Public API delta** | +1 component in SuspensionCheckpoint (executionEpoch) |
| **Engine constructor delta** | ZERO (no new parameters) |
| **Correct milestone name** | M6-T4F — Automatic Recovery Mode Selection |
| **Exact next implementation slice** | See below |

---

## Corrected Architecture

### Architecture Summary

**Core Mechanism**: JVM-scoped execution incarnation for restart detection

**Scope**: Single-node automatic recovery mode selection

**Out of Scope**: Multi-node, startup scanning, explicit recovery API, configuration

### Components

**1. ExecutionIncarnation (NEW, package-private)**

```java
// arctra-runtime-react
package cn.bitcss.arctra.runtime.react;

/**
 * JVM/process execution incarnation identity for restart detection.
 * 
 * <p>Package-private singleton providing stable JVM-scoped incarnation ID.
 * All SpringAiToolCallingEngine instances in same JVM share same epoch.
 * 
 * <p>NOT public API - internal restart detection infrastructure.
 */
final class ExecutionIncarnation {
  
  private static final String INSTANCE = UUID.randomUUID().toString();
  
  /**
   * Get current JVM's execution incarnation ID.
   * 
   * <p>Stable for JVM lifetime, regenerated on restart.
   * 
   * @return execution incarnation UUID
   */
  static String current() {
    return INSTANCE;
  }
  
  private ExecutionIncarnation() {}  // No instantiation
}
```

**2. SuspensionCheckpoint Schema v2 (PUBLIC API CHANGE)**

```java
// arctra-core
public record SuspensionCheckpoint(
    String schemaVersion,
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences,
    String executionEpoch  // NEW: JVM incarnation for restart detection
) {
  public static final String CURRENT_SCHEMA_VERSION = "2";  // BUMPED
}
```

**3. SpringAiToolCallingEngine Changes**

```java
// arctra-runtime-react
public class SpringAiToolCallingEngine implements DurableExecutionEngine {
  
  private final String executionEpoch = ExecutionIncarnation.current();
  
  // Existing constructor (NO CHANGES)
  public SpringAiToolCallingEngine(
      ChatModel chatModel,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ToolGovernancePolicy governancePolicy,
      CheckpointStore checkpointStore,
      RuntimeBindingResolver bindingResolver,
      String runtimeBindingKey,
      ExecutionLedger executionLedger) {
    // ... existing construction logic
  }
  
  @Override
  public AgentResult resumeProcess(
      String processId, long checkpointVersion, ContinuationSignal signal) {
    
    if (durableResumeCoordinator == null) {
      throw new IllegalStateException("resumeProcess() requires durable configuration");
    }
    
    // NEW: Automatic restart detection
    SuspensionCheckpoint checkpoint = loadCheckpointForInspection(processId);
    
    if (isRestartDetected(checkpoint)) {
      // Cross-incarnation resume → recovery mode
      return durableResumeCoordinator.resumeWithRecoveryClassification(
          processId, checkpointVersion, signal);
    } else {
      // Same-incarnation resume → normal mode
      return durableResumeCoordinator.resume(processId, checkpointVersion, signal);
    }
  }
  
  private boolean isRestartDetected(SuspensionCheckpoint checkpoint) {
    String checkpointEpoch = checkpoint.executionEpoch();
    
    if (checkpointEpoch == null) {
      // Old v1 checkpoint without executionEpoch
      throw new IllegalStateException(
          "Cannot auto-detect restart for checkpoint without executionEpoch. "
          + "Checkpoint created by pre-T4F engine. "
          + "Complete or abandon checkpoint before upgrading to T4F.");
    }
    
    return !checkpointEpoch.equals(this.executionEpoch);
  }
  
  private SuspensionCheckpoint loadCheckpointForInspection(String processId) {
    return checkpointStore.load(processId)
        .orElseThrow(() -> new CheckpointNotFoundException(
            "Checkpoint not found for process " + processId));
  }
}
```

**4. Checkpoint Creation (inject epoch)**

```java
// SpringAiExecutionLoop.java (existing file)
SuspensionCheckpoint checkpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
    processId,
    checkpointVersion,
    engine.getRuntimeBindingKey(),
    sessionId,
    pendingBatch,
    accumulatedEvidences,
    engine.getExecutionEpoch()  // NEW: inject current epoch
);
```

**5. CheckpointJsonCodec v2 Support**

```java
// CheckpointJsonCodec.java
String serialize(SuspensionCheckpoint checkpoint) {
  // ... existing fields
  if (checkpoint.executionEpoch() != null) {
    json.put("executionEpoch", checkpoint.executionEpoch());
  }
}

SuspensionCheckpoint deserialize(String json) {
  // ... existing fields
  String executionEpoch = root.has("executionEpoch") 
      ? root.get("executionEpoch").asText() 
      : null;  // v1 checkpoint compatibility
  
  return new SuspensionCheckpoint(..., executionEpoch);
}
```

### Deployment Constraints

**SUPPORTED**:
- ✅ Single-node JVM
- ✅ Single-process deployment
- ✅ Automatic restart detection
- ✅ Automatic recovery mode selection

**UNSUPPORTED** (deferred to future milestone):
- ❌ Multi-node automatic recovery
- ❌ Explicit recovery API
- ❌ Startup checkpoint scanning
- ❌ Configuration options

**Deployment Requirement**:
- MUST be single-node/single-process deployment
- Multi-node deployments MUST wait for claim/lease milestone

---

## Final Decision

### Decision

⚠️ **CONDITIONAL GO — M6-T4F.1 CORRECTIONS REQUIRED FIRST**

**Condition**: M6-T4F implementation MUST incorporate all 4 blocker corrections.

### Corrected Scope

**Milestone Name**: **M6-T4F — Automatic Recovery Mode Selection (Single-Node)**

**Deliverables**:

1. ✅ `ExecutionIncarnation` singleton (package-private, arctra-runtime-react)
2. ✅ `SuspensionCheckpoint` schema v2 with `executionEpoch` (String component)
3. ✅ `CheckpointJsonCodec` v2 serialization/deserialization
4. ✅ `SpringAiToolCallingEngine.resumeProcess()` automatic mode selection logic
5. ✅ `SpringAiExecutionLoop` checkpoint creation with epoch injection
6. ✅ Restart detection tests (single-node)
7. ✅ Schema compatibility tests (v1 checkpoint handling)

**EXCLUDED from T4F**:
- ❌ Dedicated JDBC `execution_epoch` column
- ❌ `RecoveryActivationPolicy` enum
- ❌ Engine constructor changes
- ❌ Public explicit recovery API
- ❌ Multi-node support
- ❌ Startup checkpoint scanning
- ❌ Configuration options
- ❌ Claim/lease/fencing
- ❌ attemptId
- ❌ RecoveryPolicy
- ❌ Retry logic

### Public API Impact

**Accurate Accounting**:
- ✅ **+1 public component**: `SuspensionCheckpoint.executionEpoch` (String)
- ✅ **Schema version bump**: v1 → v2
- ⚠️ **Breaking change**: Checkpoint construction signature changed
- ✅ **NO constructor changes**: SpringAiToolCallingEngine unchanged
- ✅ **NO new public types**: No enums, no interfaces

### Critical Corrections Summary

| Blocker | Original T4F | Corrected T4F.1 |
|---------|-------------|-----------------|
| **A: Epoch Scope** | Engine instance | JVM-scoped singleton |
| **B: Explicit Entry** | "Available for multi-node" | NOT available (deferred) |
| **C: JDBC Column** | Add `execution_epoch` column | JSON-only (no column) |
| **D: API Accounting** | "ZERO public API changes" | +1 component in public record |

### Next Steps

1. ✅ Implement `ExecutionIncarnation` singleton
2. ✅ Update `SuspensionCheckpoint` to schema v2
3. ✅ Update `CheckpointJsonCodec` for v2
4. ✅ Implement automatic mode selection in `resumeProcess()`
5. ✅ Inject epoch in checkpoint creation
6. ✅ Write restart detection tests
7. ✅ Document single-node deployment requirement

---

## HARD STOP

M6-T4F.1 Architecture Correction Gate **COMPLETE**.

**DO NOT IMPLEMENT**:
- ExecutionIncarnation singleton
- SuspensionCheckpoint schema v2
- CheckpointJsonCodec v2
- Restart detection logic
- Epoch injection
- JDBC column changes
- RecoveryActivationPolicy enum
- Public explicit recovery API
- Multi-node support
- Startup scanning
- Claim/lease/fencing

**Awaiting**: Architecture correction review and M6-T4F implementation approval with corrections incorporated.

---

**END OF ARCHITECTURE CORRECTION GATE**
