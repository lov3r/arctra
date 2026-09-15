# M6-T4F Implementation Status

**Date**: 2024  
**Status**: Core Implementation Complete, Tests Pending

---

## Implementation Summary

M6-T4F (Automatic Recovery Mode Selection) has been implemented according to T4F/T4F.1/T4F.2 architecture specifications. The implementation adds execution incarnation tracking and automatic restart detection for single-node deployments.

---

## Completed Work

### Phase 1: Execution Incarnation Infrastructure ✅

**File**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/ExecutionIncarnation.java`

- Package-private ClassLoader-scoped singleton
- Generates UUID on class initialization
- Stable for entire ClassLoader lifetime
- Multiple Engine instances share same incarnation

### Phase 2: Checkpoint Schema Evolution ✅

**File**: `arctra-core/src/main/java/cn/bitcss/arctra/checkpoint/SuspensionCheckpoint.java`

**Changes**:
- Added `String executionEpoch` component (8th parameter)
- Schema version bumped: `"1.0"` → `"1.1"`
- Nullable for v1.0 compatibility

**API Impact**: +1 public component in public record (breaking change)

### Phase 3: Checkpoint Epoch Commit Semantics ✅

**Initial Suspension** (`SpringAiToolCallingEngine.java:425-433`):
```java
SuspensionCheckpoint checkpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION, // "1.1"
    processId,
    1L,
    runtimeBindingKey,
    sessionId,
    pendingBatch,
    evidences,
    ExecutionIncarnation.current()); // CURRENT epoch
```

**Re-suspension** (`DurableResumeCoordinator.java:507-517`):
```java
SuspensionCheckpoint nextCheckpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
    oldCheckpoint.processId(),
    oldCheckpoint.checkpointVersion() + 1,
    oldCheckpoint.runtimeBindingKey(), // Preserved
    oldCheckpoint.sessionId(),
    suspended.pendingBatch(),
    suspended.evidences(),
    ExecutionIncarnation.current()); // ROLLOVER to current
```

**Invariant**: Every newly committed checkpoint carries CURRENT execution incarnation

### Phase 4: Checkpoint JSON Codec v1.1 ✅

**File**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/CheckpointJsonCodec.java`

**Serialization**:
```java
if (checkpoint.executionEpoch() != null) {
  root.put("executionEpoch", checkpoint.executionEpoch());
} else {
  root.putNull("executionEpoch");
}
```

**Deserialization**:
```java
String executionEpoch = root.has("executionEpoch") && !root.path("executionEpoch").isNull()
    ? root.path("executionEpoch").asText()
    : null;
```

**v1.0 Compatibility**: Returns `null` for missing executionEpoch field

### Phase 5: CHECK A Mode Selection Authority ✅

**File**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/DurableResumeCoordinator.java`

**New Signature**:
```java
AgentResult resume(
    String processId,
    long checkpointVersion,
    ContinuationSignal signal,
    String currentExecutionEpoch) // NEW parameter
```

**Mode Selection Logic** (lines 103-113):
```java
// CHECK A: Load and validate checkpoint (authoritative)
SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, checkpointVersion);

// M6-T4F: Mode selection using THIS validated checkpoint (TOCTOU-safe)
if (requiresRecoveryMode(checkpoint, currentExecutionEpoch)) {
  // Cross-incarnation → recovery classification pathway
  return resumeWithRecoveryInternal(checkpoint, signal);
} else {
  // Same-incarnation → normal resume pathway
  return resumeNormalInternal(checkpoint, signal);
}
```

**Restart Detection** (lines 125-149):
```java
private boolean requiresRecoveryMode(SuspensionCheckpoint checkpoint, String currentEpoch) {
  String checkpointEpoch = checkpoint.executionEpoch();

  if (checkpointEpoch == null) {
    // v1.0 checkpoint - fail closed
    throw new ResumePreparationException("Cannot auto-detect restart for pre-T4F checkpoint...");
  }

  return !checkpointEpoch.equals(currentEpoch);
}
```

**Normal Path** (lines 151-181): Zero recovery classification reads  
**Recovery Path** (lines 183-233): Preflight classification via existing `classifyApprovedBatchOrFailClosed()`

### Phase 6: Engine Delegation ✅

**File**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/SpringAiToolCallingEngine.java`

**Updated** (lines 775-777):
```java
// M6-T4F: Delegate to coordinator with current execution incarnation
// Mode selection occurs INSIDE CHECK A to prevent TOCTOU
return durableResumeCoordinator.resume(
    processId, checkpointVersion, signal, ExecutionIncarnation.current());
```

### Phase 7: Test Fixture Updates ✅

**File**: `arctra-runtime-react/src/test/java/cn/bitcss/arctra/runtime/react/TestCheckpoints.java`

**Updated all factory methods** to inject `ExecutionIncarnation.current()`

**New helper**:
```java
public static SuspensionCheckpoint withEpoch(
    String processId, long version, String executionEpoch) {
  // For testing cross-incarnation scenarios
}
```

---

## Architectural Guarantees

### TOCTOU Prevention ✅

Mode selection uses authoritative CHECK A checkpoint. No Engine pre-read. No race window.

### Epoch Rollover Semantics ✅

- Initial suspension: epoch = current
- Re-suspension: epoch = current (NOT preserved from old checkpoint)
- runtimeBindingKey: preserved (different lifecycle)

### v1.0 Compatibility ✅

Old checkpoints without executionEpoch throw `ResumePreparationException` (fail-closed).

### Incarnation Scope

- **Supported**: Single-process, single ClassLoader, stable lifecycle
- **Unsupported**: Multi-node, multiple isolated ClassLoaders, hot-reload

---

## Remaining Work

### Test Compilation Issues

**Current blockers**:
1. Existing tests call `coordinator.resume(...)` with 3 parameters (need to add `currentExecutionEpoch`)
2. New test file (`AutomaticRecoveryModeSelectionTest.java`) needs refactoring to match existing test patterns

**Required fixes**:
- Update all test call sites to pass `ExecutionIncarnation.current()` as 4th parameter
- Simplify new test to use existing `TestCheckpoints`, `TestBindings`, `TestTools` utilities
- Add targeted tests for:
  - Same-incarnation resume (zero recovery reads)
  - Cross-incarnation resume (recovery classification activated)
  - Epoch rollover on re-suspension
  - v1.0 checkpoint handling
  - JDBC round-trip persistence

### Regression Test Suite

Need to run full reactor test suite after fixing compilation:
```bash
./mvnw test
```

Expected baseline: ~445 tests (core:205, runtime-react:205, examples:35)

### Old Method Cleanup

`DurableResumeCoordinator.resumeWithRecoveryClassification()` (line 391) is now superseded by internal `resumeWithRecoveryInternal()`. Can be removed after test migration.

---

## Public API Changes

**Added**:
- `SuspensionCheckpoint.executionEpoch()` — String component (nullable)
- `SuspensionCheckpoint.CURRENT_SCHEMA_VERSION` — bumped to `"1.1"`

**Modified**:
- `DurableResumeCoordinator.resume(...)` — +1 parameter (package-private, internal)

**Breaking Changes**:
- SuspensionCheckpoint constructor signature (8 parameters)
- v1.0 checkpoints cannot auto-resume (explicit upgrade required)

---

## Deployment Topology Support

### Single-Node (SUPPORTED) ✅

- ✅ Automatic restart detection
- ✅ Automatic recovery mode selection
- ✅ Zero configuration required
- ✅ Standard Spring Boot / standalone Java apps

### Multi-Node (UNSUPPORTED) ❌

- ❌ Automatic recovery (requires claim/lease — future milestone)
- ❌ No ownership coordination
- ❌ Manual explicit recovery only (via old `resumeWithRecoveryClassification()` until removed)

### Unsupported Topologies ❌

- OSGi / complex app server ClassLoaders
- Hot-reload with Arctra class recreation
- Dynamic module systems

---

## Next Steps

1. **Fix test compilation** — Update existing test call sites
2. **Run regression suite** — Verify 0 failures, 0 errors
3. **Add targeted T4F tests** — Prove mode selection, epoch rollover, v1.0 handling
4. **Remove old method** — Clean up `resumeWithRecoveryClassification()` after test migration
5. **Write closure report** — Document verification results

---

## Deferred Work (Future Milestones)

- Multi-node automatic recovery
- Claim/lease/fencing infrastructure
- Heartbeat/liveness detection
- Startup checkpoint scanning
- Public explicit recovery API
- `attemptId`, `RecoveryPolicy`, retry logic
- Idempotency coordination

---

## Architecture References

- **M6-T4F**: Original automatic recovery mode selection architecture
- **M6-T4F.1**: Execution incarnation & activation boundary corrections
- **M6-T4F.2**: Recovery mode selection boundary corrections (TOCTOU, epoch rollover, scope)

---

**Status**: Implementation complete, awaiting test fixes and verification.
