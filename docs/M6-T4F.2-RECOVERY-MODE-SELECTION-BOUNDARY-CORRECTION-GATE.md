# M6-T4F.2 — RECOVERY MODE SELECTION BOUNDARY CORRECTION GATE

**Status**: Architecture Correction Complete  
**Decision**: [To be determined after analysis]  
**Author**: Architecture Correction Analysis  
**Date**: 2024

---

## Executive Summary

**Mission**: Correct 3 final architectural issues before M6-T4F implementation.

**Issues**:
1. **Mode Selection Boundary** — Must occur inside CHECK A (TOCTOU prevention)
2. **Re-suspension Epoch Rollover** — Must update executionEpoch to current incarnation
3. **Source-Truth Verification** — ClassLoader scope and schema version conventions

**Outcome**: [To be determined]

---

## Table of Contents

1. [Issue 1: Mode Selection Must Be Inside CHECK A](#issue-1-mode-selection-must-be-inside-check-a)
2. [Issue 2: Re-suspension Epoch Rollover](#issue-2-re-suspension-epoch-rollover)
3. [Issue 3: Incarnation Scope and Schema Truth](#issue-3-incarnation-scope-and-schema-truth)
4. [Final Decisions Matrix](#final-decisions-matrix)
5. [Corrected Call Graph](#corrected-call-graph)
6. [Implementation Guidance](#implementation-guidance)
7. [Final Decision](#final-decision)

---

## Issue 1: Mode Selection Must Be Inside CHECK A

### 1.1 Problem Statement

**T4F.1 Proposed Architecture**:
```java
// Engine.resumeProcess()
SuspensionCheckpoint checkpoint = loadCheckpointForInspection(processId);
if (isRestartDetected(checkpoint)) {
  coordinator.resumeWithRecoveryClassification(...);
} else {
  coordinator.resume(...);
}
```

**Problem**: **TOCTOU (Time-Of-Check to Time-Of-Use)**

```
T1: Engine loads checkpoint (version 1, epoch-A)
T2: Concurrent resume completes → checkpoint v2 created
T3: Engine compares epoch-A ≠ epoch-B → decides recovery mode
T4: Coordinator CHECK A loads checkpoint → gets v2 (not v1)
T5: Mode selection based on v1, execution based on v2 → MISMATCH
```

**Risk**: Mode selection and actual execution see different checkpoint generations.

### 1.2 Source Evidence

**Current Coordinator API** (DurableResumeCoordinator.java:126-167):

```java
AgentResult resume(String processId, long checkpointVersion, ContinuationSignal signal) {
  // CHECK A: Load and validate checkpoint
  SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, checkpointVersion);
  
  // Resolve binding
  RuntimeBinding binding = resolveBinding(processId, checkpoint);
  
  // ... rest of resume logic
}
```

**Critical Observation**: 
- Coordinator already performs authoritative CHECK A load
- Coordinator validates `checkpointVersion` matches request
- Coordinator uses THIS checkpoint for entire resume operation

**NO pre-read authority** should duplicate this load.

### 1.3 Corrected Architecture

**Requirement**: Mode selection MUST use the SAME checkpoint snapshot as execution.

**Solution Options**:

**Option A: Coordinator-Internal Mode Selection** ✅
```java
// Coordinator becomes mode-selection authority
AgentResult resumeProcess(
    String processId, 
    long checkpointVersion, 
    ContinuationSignal signal,
    String currentExecutionEpoch) {  // NEW parameter
  
  // CHECK A: Load and validate (authoritative)
  SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, checkpointVersion);
  
  // Mode selection using THIS validated checkpoint
  if (!checkpoint.executionEpoch().equals(currentExecutionEpoch)) {
    // Different incarnation → recovery mode
    return resumeWithRecoveryClassificationInternal(checkpoint, signal, ...);
  } else {
    // Same incarnation → normal mode
    return resumeNormalInternal(checkpoint, signal, ...);
  }
}
```

**Option B: Coordinator Public API Split** ❌
```java
// Two public entry points
AgentResult resumeNormal(...)
AgentResult resumeWithRecovery(...)
```
**Rejected**: Requires Engine to make mode selection BEFORE CHECK A (TOCTOU).

**Option C: Unified Coordinator with Mode Parameter** ❌
```java
AgentResult resume(..., RecoveryMode mode)
```
**Rejected**: Still requires Engine to decide mode before CHECK A.

### 1.4 Selected Solution

**SELECTED**: **Option A — Coordinator-Internal Mode Selection**

**Key Changes**:

1. **Coordinator becomes mode-selection authority**
   - Receives `currentExecutionEpoch` as parameter
   - Performs CHECK A load (existing)
   - Compares epoch AFTER load
   - Routes to internal normal or recovery path

2. **Engine responsibilities**:
   - Pass `currentExecutionEpoch` to Coordinator
   - Do NOT pre-load checkpoint
   - Do NOT make mode decision

3. **Coordinator API** (package-private, internal change):
```java
// BEFORE (current)
AgentResult resume(String processId, long version, ContinuationSignal signal)

// AFTER (T4F)
AgentResult resume(
    String processId, 
    long version, 
    ContinuationSignal signal,
    String currentExecutionEpoch)  // NEW
```

**Guarantees**:
- ✅ Mode selection uses authoritative CHECK A checkpoint
- ✅ No TOCTOU race
- ✅ Single checkpoint load
- ✅ No Engine pre-read duplication

**Public API Impact**: ZERO (Coordinator is package-private)

---

## Issue 2: Re-suspension Epoch Rollover

### 2.1 Problem Statement

**Scenario**:
```
T1: epoch-A creates checkpoint v1 {epoch: epoch-A}
T2: JVM restart → epoch-B
T3: epoch-B recovers v1 → re-suspends
T4: checkpoint v2 created with epoch = ???
```

**Question**: Should v2 carry `epoch-A` (preserve) or `epoch-B` (rollover)?

### 2.2 Semantic Analysis

**executionEpoch Semantics**:
> The execution incarnation that **committed** this checkpoint generation.

**NOT**:
> The execution incarnation that originally created the process.

**Comparison with runtimeBindingKey**:
- `runtimeBindingKey`: Logical binding identity, preserved across generations
- `executionEpoch`: Physical incarnation identity, **current on each generation**

### 2.3 Source Evidence

**Re-suspension Path** (DurableResumeCoordinator.java:508-516):
```java
private AgentResult handleReSuspension(
    SuspensionCheckpoint oldCheckpoint,
    ResumedExecutionOutcome.GovernanceSuspended suspended,
    AgentExecutionContext context) {
  
  // Build next checkpoint (version N+1)
  SuspensionCheckpoint nextCheckpoint = new SuspensionCheckpoint(
      SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
      oldCheckpoint.processId(),
      oldCheckpoint.checkpointVersion() + 1,
      oldCheckpoint.runtimeBindingKey(),  // PRESERVED
      oldCheckpoint.sessionId(),
      suspended.pendingBatch(),
      suspended.evidences()
      // executionEpoch: ??? (T4F must add)
  );
}
```

**Initial Suspension Path** (SpringAiToolCallingEngine.java:424-432):
```java
SuspensionCheckpoint checkpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
    processId,
    1L,
    runtimeBindingKey,
    sessionId,
    pendingBatch,
    List.copyOf(evidences)
    // executionEpoch: ??? (T4F must add)
);
```

### 2.4 Rollover Analysis

**If v2 preserves epoch-A**:
```
epoch-B resumes v1 {epoch: epoch-A}
→ epoch-A ≠ epoch-B → recovery mode ✅ CORRECT
→ re-suspension creates v2 {epoch: epoch-A}
→ epoch-B resumes v2 {epoch: epoch-A}
→ epoch-A ≠ epoch-B → recovery mode ❌ FALSE POSITIVE
```

**Problem**: epoch-B will ALWAYS enter recovery mode for v2, even though epoch-B just created v2.

**If v2 rolls over to epoch-B**:
```
epoch-B resumes v1 {epoch: epoch-A}
→ epoch-A ≠ epoch-B → recovery mode ✅ CORRECT
→ re-suspension creates v2 {epoch: epoch-B}
→ epoch-B resumes v2 {epoch: epoch-B}
→ epoch-B = epoch-B → normal mode ✅ CORRECT
```

**If epoch-B crashes and epoch-C restarts**:
```
epoch-C resumes v2 {epoch: epoch-B}
→ epoch-B ≠ epoch-C → recovery mode ✅ CORRECT
```

### 2.5 Selected Solution

**SELECTED**: **Rollover to Current Epoch**

**Invariant**:
> Every newly committed checkpoint generation MUST carry the CURRENT execution incarnation.

**Implementation**:

**Initial Suspension**:
```java
SuspensionCheckpoint checkpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
    processId,
    1L,
    runtimeBindingKey,
    sessionId,
    pendingBatch,
    evidences,
    ExecutionIncarnation.current()  // CURRENT epoch
);
```

**Re-suspension**:
```java
SuspensionCheckpoint nextCheckpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
    oldCheckpoint.processId(),
    oldCheckpoint.checkpointVersion() + 1,
    oldCheckpoint.runtimeBindingKey(),  // Preserve
    oldCheckpoint.sessionId(),
    suspended.pendingBatch(),
    suspended.evidences(),
    ExecutionIncarnation.current()  // CURRENT epoch (rollover)
);
```

**Guaranteed Semantics**:
- ✅ Each checkpoint generation records its committer
- ✅ Same-incarnation resume → normal mode
- ✅ Cross-incarnation resume → recovery mode
- ✅ No false positives on re-suspension

---

## Issue 3: Incarnation Scope and Schema Truth

### 3.1 Incarnation Scope Reality

**T4F.1 Implementation**:
```java
final class ExecutionIncarnation {
  private static final String INSTANCE = UUID.randomUUID().toString();
  static String current() { return INSTANCE; }
}
```

**Actual Scope**: **ClassLoader-scoped static**

**NOT**: JVM-global across all ClassLoaders

### 3.2 ClassLoader Topology Analysis

**Supported Topology**:
- Single application ClassLoader
- Stable application lifecycle (no hot-reload)
- All Arctra classes loaded by same ClassLoader

**Unsupported Topology**:
- Multiple isolated application ClassLoaders (e.g., OSGi bundles)
- Hot-reload scenarios that recreate Arctra classes
- Multi-node deployments (already excluded)
- Application servers with complex ClassLoader hierarchies

**Practical Impact**:
- ✅ Standard Spring Boot application: SUPPORTED
- ✅ Standard standalone Java application: SUPPORTED
- ❌ OSGi/JBoss modules: UNSUPPORTED
- ❌ Hot-reload with class recreation: UNSUPPORTED

### 3.3 Schema Version Source Truth

**Current Source** (SuspensionCheckpoint.java):
```java
public record SuspensionCheckpoint(
    String schemaVersion,
    // ... other fields
) {
  public static final String CURRENT_SCHEMA_VERSION = "1.0";
}
```

**Observations**:
- Current version: `"1.0"` (String, semantic versioning)
- Introduced with operationId (M6-T3A)
- No prior schema evolution in git history

**T4F Schema Change**:
- Add `executionEpoch` component
- Bump to next version following project convention

**Project Convention**: Semantic versioning (`"1.0"`, `"1.1"`, `"2.0"`)

**Selected Next Version**: `"1.1"`

**Rationale**:
- Minor version bump (backward-compatible read, not write)
- Major bump (`"2.0"`) would imply breaking semantic change
- `"1.1"` signals additive evolution

### 3.4 Corrected Scope Declaration

**Accurate Statement**:

**Supported**:
- ✅ Single-process deployment
- ✅ Single application ClassLoader
- ✅ Stable application lifecycle (no hot-reload)
- ✅ Standard Spring Boot / standalone Java app

**Unsupported**:
- ❌ Multi-node deployments
- ❌ Multiple isolated ClassLoaders (OSGi, complex app servers)
- ❌ Hot-reload with Arctra class recreation
- ❌ Dynamic module systems

**Documentation Requirement**:
> executionEpoch is ClassLoader-scoped. T4F automatic recovery mode selection requires single-process deployment with stable application ClassLoader. Multi-node and complex ClassLoader topologies are unsupported.

---

## Final Decisions Matrix

| Question | Answer |
|----------|--------|
| **CHECK A mode-selection owner** | DurableResumeCoordinator (internal) |
| **Authoritative checkpoint used** | CHECK A load (existing `loadAndValidateCheckpoint()`) |
| **Engine pre-read required** | ❌ NO (TOCTOU violation) |
| **Re-suspension epoch behavior** | Rollover to CURRENT epoch |
| **Initial suspension epoch behavior** | Set to CURRENT epoch |
| **runtimeBindingKey behavior** | PRESERVED across generations (unchanged) |
| **Incarnation exact scope** | ClassLoader-scoped static |
| **Unsupported topology** | Multi-node, multiple ClassLoaders, hot-reload |
| **Current source schema version** | `"1.0"` (String, semantic versioning) |
| **Selected next schema version** | `"1.1"` (additive evolution) |
| **Public API delta** | +1 component in SuspensionCheckpoint |
| **Coordinator API delta** | +1 parameter (package-private, internal) |

---

## Corrected Call Graph

### Normal Resume (Same Incarnation)

```
User/Application
  → AgentRuntime.resumeProcess(processId, version, signal)
    → DefaultAgentRuntime.resumeProcess(...)
      → DurableExecutionEngine.resumeProcess(...)
        → SpringAiToolCallingEngine.resumeProcess(...)
          → DurableResumeCoordinator.resume(
              processId, 
              version, 
              signal,
              ExecutionIncarnation.current())  // NEW parameter
            
            → CHECK A: loadAndValidateCheckpoint()
              [authoritative load, version validation]
            
            → Compare: checkpoint.executionEpoch() vs currentEpoch
              → MATCH (same incarnation)
            
            → resumeNormalInternal(checkpoint, ...)
              → resolveBinding()
              → validateAndEmitApproval()
              → resumedExecutionHandler.executeResume()
              → handleResumedExecutionOutcome()
                → CHECK B: replaceIfVersion() or deleteIfVersion()
```

### Recovery Resume (Cross-Incarnation)

```
User/Application
  → AgentRuntime.resumeProcess(processId, version, signal)
    → [same path to Coordinator]
      → DurableResumeCoordinator.resume(processId, version, signal, currentEpoch)
        
        → CHECK A: loadAndValidateCheckpoint()
          [authoritative load]
        
        → Compare: checkpoint.executionEpoch() vs currentEpoch
          → MISMATCH (different incarnation)
        
        → resumeWithRecoveryClassificationInternal(checkpoint, ...)
          → classifyApprovedBatchOrFailClosed()
            [read invocation intent for each operation]
          → if any MAY_HAVE_INVOKED → throw RecoveryUncertaintyException
          → if all DEFINITELY_NOT_DISPATCHED → proceed
          → resolveBinding()
          → validateAndEmitApproval()
          → resumedExecutionHandler.executeResume()
          → handleResumedExecutionOutcome()
            → CHECK B
```

### Re-suspension Path

```
[During resume execution]
  → resumedExecutionHandler.executeResume(...)
    → [governance requires approval on NEW tool call]
    → return ResumedExecutionOutcome.GovernanceSuspended
  
  → handleResumedExecutionOutcome(...)
    → handleReSuspension(oldCheckpoint, suspended, context)
      → Build nextCheckpoint:
        - processId: PRESERVE
        - checkpointVersion: INCREMENT (N+1)
        - runtimeBindingKey: PRESERVE
        - sessionId: PRESERVE
        - pendingBatch: NEW (from suspended outcome)
        - evidences: NEW (from suspended outcome)
        - executionEpoch: CURRENT (rollover)  // NEW behavior
      
      → CHECK B: replaceIfVersion(...)
      → Return suspended AgentResult
```

### Initial Suspension Path

```
[During initial execution]
  → SpringAiExecutionLoop.executeWithGovernance(...)
    → [governance requires approval]
    → Build checkpoint v1:
      - schemaVersion: "1.1"
      - processId: NEW
      - checkpointVersion: 1
      - runtimeBindingKey: engine's key
      - sessionId: from context
      - pendingBatch: from tool calls
      - evidences: accumulated
      - executionEpoch: CURRENT  // NEW field
    
    → checkpointStore.create(checkpoint)
    → Return suspended AgentResult
```

---

## Implementation Guidance

### 6.1 Coordinator Internal Refactoring

**Current** (DurableResumeCoordinator.java):
```java
AgentResult resume(String processId, long version, ContinuationSignal signal) {
  SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, version);
  // ... normal resume logic
}

AgentResult resumeWithRecoveryClassification(String processId, long version, ContinuationSignal signal) {
  SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, version);
  // ... recovery classification + resume logic
}
```

**Refactored** (T4F):
```java
// Public-facing entry (called by Engine)
AgentResult resume(
    String processId, 
    long version, 
    ContinuationSignal signal,
    String currentExecutionEpoch) {  // NEW
  
  // CHECK A: Authoritative load
  SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, version);
  RuntimeBinding binding = resolveBinding(processId, checkpoint);
  
  // Mode selection using validated checkpoint
  if (requiresRecoveryMode(checkpoint, currentExecutionEpoch)) {
    return resumeWithRecoveryInternal(checkpoint, binding, signal);
  } else {
    return resumeNormalInternal(checkpoint, binding, signal);
  }
}

private boolean requiresRecoveryMode(SuspensionCheckpoint checkpoint, String currentEpoch) {
  String checkpointEpoch = checkpoint.executionEpoch();
  
  if (checkpointEpoch == null) {
    throw new IllegalStateException(
        "Cannot auto-detect restart for pre-T4F checkpoint (missing executionEpoch)");
  }
  
  return !checkpointEpoch.equals(currentEpoch);
}

private AgentResult resumeNormalInternal(
    SuspensionCheckpoint checkpoint, 
    RuntimeBinding binding,
    ContinuationSignal signal) {
  // Current resume() logic (lines 135-167)
}

private AgentResult resumeWithRecoveryInternal(
    SuspensionCheckpoint checkpoint,
    RuntimeBinding binding, 
    ContinuationSignal signal) {
  // Current resumeWithRecoveryClassification() logic (lines 243-279)
}
```

### 6.2 Engine Changes

**SpringAiToolCallingEngine.java**:
```java
@Override
public AgentResult resumeProcess(
    String processId, long checkpointVersion, ContinuationSignal signal) {
  
  if (durableResumeCoordinator == null) {
    throw new IllegalStateException("resumeProcess() requires durable configuration");
  }
  
  // Delegate to coordinator with current epoch
  return durableResumeCoordinator.resume(
      processId, 
      checkpointVersion, 
      signal,
      ExecutionIncarnation.current());  // NEW parameter
}
```

### 6.3 Checkpoint Construction

**Initial Suspension** (SpringAiExecutionLoop.java):
```java
SuspensionCheckpoint checkpoint = new SuspensionCheckpoint(
    "1.1",  // NEW schema version
    processId,
    1L,
    runtimeBindingKey,
    sessionId,
    pendingBatch,
    evidences,
    ExecutionIncarnation.current()  // NEW field
);
```

**Re-suspension** (DurableResumeCoordinator.handleReSuspension()):
```java
SuspensionCheckpoint nextCheckpoint = new SuspensionCheckpoint(
    "1.1",  // NEW schema version
    oldCheckpoint.processId(),
    oldCheckpoint.checkpointVersion() + 1,
    oldCheckpoint.runtimeBindingKey(),  // Preserve
    oldCheckpoint.sessionId(),
    suspended.pendingBatch(),
    suspended.evidences(),
    ExecutionIncarnation.current()  // NEW field (rollover)
);
```

### 6.4 Schema Version Handling

**CheckpointJsonCodec** (serialization):
```java
String serialize(SuspensionCheckpoint checkpoint) {
  ObjectNode json = objectMapper.createObjectNode();
  json.put("schemaVersion", checkpoint.schemaVersion());
  // ... existing fields
  json.put("executionEpoch", checkpoint.executionEpoch());  // NEW
  return objectMapper.writeValueAsString(json);
}
```

**CheckpointJsonCodec** (deserialization):
```java
SuspensionCheckpoint deserialize(String json) {
  JsonNode root = objectMapper.readTree(json);
  String schemaVersion = requireText(root, "schemaVersion");
  
  // ... existing fields
  
  // executionEpoch: present in v1.1+, absent in v1.0
  String executionEpoch = root.has("executionEpoch") 
      ? root.get("executionEpoch").asText() 
      : null;
  
  return new SuspensionCheckpoint(..., executionEpoch);
}
```

**Backward Compatibility**:
- v1.0 checkpoints (no executionEpoch): `executionEpoch = null`
- Mode selection: `null` epoch → throw IllegalStateException (explicit upgrade required)

---

## Final Decision

### Decision

✅ **GO — M6-T4F IMPLEMENTATION MAY BEGIN**

**All architectural corrections integrated**. Implementation ready with:
1. ✅ Mode selection inside CHECK A (TOCTOU prevented)
2. ✅ Epoch rollover on re-suspension (false positive prevented)
3. ✅ Accurate scope declaration (ClassLoader-scoped)
4. ✅ Schema version follows project convention (`"1.1"`)

### Corrected Implementation Checklist

**Core Components**:
1. ✅ `ExecutionIncarnation` singleton (ClassLoader-scoped)
2. ✅ `SuspensionCheckpoint` schema v1.1 with `executionEpoch`
3. ✅ `DurableResumeCoordinator.resume()` — add `currentExecutionEpoch` parameter
4. ✅ `DurableResumeCoordinator` — internal mode selection logic
5. ✅ `SpringAiToolCallingEngine.resumeProcess()` — pass current epoch
6. ✅ Initial suspension — inject current epoch
7. ✅ Re-suspension — rollover to current epoch
8. ✅ `CheckpointJsonCodec` v1.1 support

**Testing**:
1. ✅ Same-incarnation resume → normal mode
2. ✅ Cross-incarnation resume → recovery mode
3. ✅ Re-suspension epoch rollover → no false positive
4. ✅ v1.0 checkpoint handling (null epoch)
5. ✅ TOCTOU prevention (mode selection uses CHECK A checkpoint)

**Documentation**:
1. ✅ ClassLoader-scoped incarnation caveat
2. ✅ Unsupported topologies (multi-node, hot-reload)
3. ✅ Schema evolution (v1.0 → v1.1)
4. ✅ Epoch rollover semantics

### Public API Impact

**Accurate Accounting**:
- ✅ `SuspensionCheckpoint` — add `executionEpoch` component (String)
- ✅ Schema version: `"1.0"` → `"1.1"`
- ❌ NO constructor changes (SpringAiToolCallingEngine)
- ❌ NO new public types
- ✅ Coordinator API: +1 parameter (package-private, internal only)

### Critical Corrections Summary

| Issue | Problem | Correction |
|-------|---------|------------|
| **1: Mode Selection Boundary** | Engine pre-read → TOCTOU | Coordinator-internal selection after CHECK A |
| **2: Epoch Rollover** | Preserve old epoch → false positive | Rollover to current epoch on each generation |
| **3: Scope Declaration** | "JVM-global" | ClassLoader-scoped (accurate) |
| **3: Schema Version** | Assumed "v2" | Follow project convention: `"1.1"` |

---

## HARD STOP

M6-T4F.2 Architecture Correction Gate **COMPLETE**.

**DO NOT IMPLEMENT**:
- ExecutionIncarnation singleton
- SuspensionCheckpoint schema v1.1
- Coordinator internal refactoring
- Engine changes
- CheckpointJsonCodec v1.1
- Epoch rollover logic
- Mode selection logic
- Tests
- Documentation

**Awaiting**: Architecture correction review and M6-T4F implementation approval with all T4F/T4F.1/T4F.2 corrections fully integrated.

---

**END OF ARCHITECTURE CORRECTION GATE**
