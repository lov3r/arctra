# M6-T3A — DURABLE LOGICAL OPERATION IDENTITY REPORT

**Date**: 2024-09-14  
**Status**: ✅ **FULL GO / CLOSE M6-T3A**

---

## A. Frozen Baseline

```
arctra-core: 205 tests
arctra-runtime-react: 128 tests  
examples: 35 tests
TOTAL: 368 tests
Failures: 0
Errors: 0
Skipped: 23
BUILD SUCCESS
```

**Source**: M6-T2.5A-R4.2 verified baseline

---

## B. Source Truth Before Change

### PendingToolCall (Original)

**File**: `arctra-core/src/main/java/cn/bitcss/arctra/checkpoint/PendingToolCall.java`

**Original schema**:
```java
public record PendingToolCall(
    String toolCallId,
    String toolName,
    String arguments
)
```

**Production call sites**: 2
- SpringAiToolCallingEngine.suspendForApprovalDurable() (line 362)
- SpringAiResumedExecutionHandler.convertToPendingBatch() (line 178)

**Test call sites**: 32 across multiple test files

---

### SuspensionCheckpoint

**Schema version**: `"1.0"`

**No validation of PendingToolCall internal structure** — checkpoint only validates pendingBatch is non-empty

---

### CheckpointStore Implementations

1. **InMemoryCheckpointStore** (only implementation)
   - JVM-local ConcurrentHashMap
   - NOT persistent across restart
   - No serialization

2. **No persistent storage exists today** ❌

---

## C. Public API Compatibility Audit

### PendingToolCall Visibility

**Package**: `cn.bitcss.arctra.checkpoint`  
**Module**: `arctra-core`  
**Visibility**: `public record`

**Classification**: **B. Technically public internal/model API**

**Evidence**:
1. No module-info.java — all public types are technically exported
2. No external documentation recommending direct PendingToolCall construction
3. Examples do NOT construct PendingToolCall directly
4. Testkit does NOT export PendingToolCall factories
5. Primary usage: internal framework checkpoint materialization

**Conclusion**: Public for checkpoint persistence, NOT for external construction API

---

### Breaking Change Acceptance

**API Break**: ✅ YES — canonical constructor changes from 3 to 4 fields

**Justification**:
1. Early milestone (M6) — public API not yet frozen
2. Internal model type, not deliberate external API
3. No external construction pattern documented
4. Clear migration path (add operationId parameter)

**Mitigation**: Document in migration guide, no compatibility constructor needed

---

## D. Checkpoint Persistence Reality

### Current State

| Question | Answer |
|----------|--------|
| Which CheckpointStore implementations exist? | InMemoryCheckpointStore only |
| Is it persistent across restart? | NO — JVM-local only |
| Is JSON/Jackson/serialization implemented? | NO |
| Can old checkpoints be loaded after restart? | NO — no persistence |
| Does CURRENT_SCHEMA_VERSION participate in validation? | NO — passive marker only |
| Is there a migration mechanism today? | NO |

**Conclusion**: **No durable checkpoint persistence exists**

**Schema migration requirement**: **Deferred** until persistent CheckpointStore implementation arrives

---

## E. operationId Semantic Contract

### What operationId Owns

> **One logical durable tool operation**

### Properties

- Framework-owned (not provider-specific)
- Opaque identity (implementation may change UUID → ULID)
- Generated once when PendingToolCall is materialized
- Stable across concurrent resume attempts
- Distinct for each logical operation, even same-name tools

### What operationId Does NOT Own

- Physical execution attempt identity (future: attemptId)
- Provider protocol correlation (toolCallId owns this)
- Execution outcome (events own this)
- Retry semantics (not yet implemented)

---

## F. PendingToolCall Change

### New Schema

```java
public record PendingToolCall(
    String operationId,   // NEW - Arctra framework operation identity
    String toolCallId,    // Spring AI protocol identity
    String toolName,
    String arguments
)
```

### Field Ordering Rationale

1. **operationId FIRST** — framework primary identity
2. **toolCallId SECOND** — provider protocol identity
3. toolName, arguments — execution parameters

### Validation

```java
if (operationId == null || operationId.isBlank()) {
    throw new IllegalArgumentException("operationId cannot be null or blank");
}
```

**No UUID format validation** — semantic contract: opaque identity

---

## G. ID Generation Ownership

### OperationIds Utility

**File**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/OperationIds.java`

**Visibility**: `package-private`

**Implementation**:
```java
static String generate() {
    return UUID.randomUUID().toString();
}
```

**Rationale for NOT creating OperationIdGenerator abstraction**:
- Single-responsibility utility
- Only two production call sites
- No test injection requirement (tests use deterministic "test-op-X" IDs)
- YAGNI — no evidence of multiple generation strategies

---

## H. Initial Suspension Flow

### Current Flow (with operationId)

```
SpringAiToolCallingEngine.suspendForApprovalDurable()
  → Model produces ToolCalls
  → Governance evaluates → REQUIRE_APPROVAL
  → Build pending batch:
      toolCalls.stream().map(tc -> 
          new PendingToolCall(
              OperationIds.generate(),  // NEW
              tc.id(),
              tc.name(),
              tc.arguments()
          )
      )
  → SuspensionCheckpoint.create(pendingBatch)
  → checkpointStore.create(checkpoint)
```

**Timing**: operationId generated BEFORE checkpoint.create() ✅

**Durability**: operationId durable before any physical execution ✅

---

## I. Re-suspension Flow

### SpringAiResumedExecutionHandler Flow

```
executeResume()
  → Resumed tools execute
  → Model produces NEW ToolCalls
  → Governance → REQUIRE_APPROVAL
  → convertToPendingBatch():
      assistantMessage.getToolCalls().stream().map(tc ->
          new PendingToolCall(
              OperationIds.generate(),  // NEW operation
              tc.id(),
              tc.name(),
              tc.arguments()
          )
      )
  → Return GovernanceSuspended(newPendingBatch, ...)
  → DurableResumeCoordinator: replaceIfVersion(checkpointV2)
```

**NEW operations get NEW operationIds** ✅

---

## J. Concurrent Resume Identity

### Test Coverage

**File**: `ConcurrentDurableResumeTest`

**Verification**: Both concurrent workers load same checkpoint → see same operationId

**Example**:
```
Checkpoint v1:
  PendingToolCall(operationId="test-op-1", ...)

Worker A resumes → loads operationId="test-op-1"
Worker B resumes → loads operationId="test-op-1"

✅ Same logical operation across attempts
```

**at-least-once preserved**: Both may execute external tool ✅

---

## K. toolCallId Preservation

### Protocol Reconstruction

**ProtocolReconstructor** uses toolCallId (NOT operationId):

```java
// Line 147
new AssistantMessage.ToolCall(
    dto.toolCallId(),  // Preserves protocol identity
    dto.toolName(),
    dto.arguments()
)
```

**Spring AI ToolResponseMessage** correlates by toolCallId:
```java
new ToolResponseMessage.ToolResponse(
    toolCallId,  // Protocol correlation
    toolName,
    toolResult
)
```

**operationId does NOT replace protocol identity** ✅

---

## L. Execution-Boundary Limitation

### Critical Gap

**operationId is durable but NOT yet authoritatively correlated to delegate.call()**

**Evidence**:

1. **EvidenceCapturingToolCallback** receives:
   - `ToolCallback delegate`
   - `ToolObservationContext` (processId, checkpointVersion, eventListener)
   - Does NOT receive operationId ❌

2. **delegate.call() boundary** has access to:
   - toolName (from delegate.getToolDefinition())
   - arguments (parameter)
   - Does NOT have toolCallId ❌
   - Does NOT have operationId ❌

3. **TOOL_EXECUTED/TOOL_FAILED payload** contains:
   ```json
   {"toolName":"..."}
   ```
   - Does NOT contain operationId ❌

**What M6-T3A does NOT solve**: Event-level operation correlation

**Future milestone required**: M6-T3B or later must establish delegate.call() → operationId correlation

---

## M. Schema Version Decision

### Change Made

**Before**: `CURRENT_SCHEMA_VERSION = "1.0"`  
**After**: `CURRENT_SCHEMA_VERSION = "1.1"`

### Justification

**YES — schema version incremented** ✅

**Reason**: PendingToolCall gained operationId field — structural change to checkpoint contract

### Documentation

```java
/**
 * <p><strong>M6-T3A:</strong> Schema version changed from "1.0" to "1.1" due to 
 * PendingToolCall gaining operationId field. This is an additive change - old 
 * checkpoints without operationId cannot be automatically migrated because persistent 
 * checkpoint storage is not yet implemented.
 *
 * <p><strong>Migration Strategy:</strong> When persistent CheckpointStore implementations 
 * arrive, migration from "1.0" to "1.1" must generate operationIds for old PendingToolCall 
 * instances. Current InMemoryCheckpointStore does not persist across JVM restarts, so 
 * migration is deferred.
 */
public static final String CURRENT_SCHEMA_VERSION = "1.1";
```

---

## N. Old Checkpoint Compatibility

### Current Reality

**No persistent checkpoints exist** — InMemoryCheckpointStore is JVM-local only

### Migration Strategy

**Deferred** until persistent CheckpointStore implementation

**When persistent storage arrives**:
1. Detect schema "1.0" on load
2. Generate operationIds for PendingToolCall instances
3. Upgrade to schema "1.1"
4. Persist upgraded checkpoint

**M6-T3A does NOT implement migration** ✅ (not yet needed)

---

## O. Duplicate Same-Name Tool Semantics

### Preservation

**Two same-name tools remain distinct**:

```java
Model produces:
  ToolCall(id="tc-1", name="fetchData", args="{url:'/api/a'}")
  ToolCall(id="tc-2", name="fetchData", args="{url:'/api/b'}")

Framework materializes:
  PendingToolCall(operationId="op-A", toolCallId="tc-1", name="fetchData", ...)
  PendingToolCall(operationId="op-B", toolCallId="tc-2", name="fetchData", ...)
```

**Properties**:
- operationId ≠ operationId ✅
- toolCallId ≠ toolCallId ✅
- toolName == toolName ✅

**Protocol reconstruction preserves distinct toolCallIds** ✅

---

## P. R4 Boundary Protection

### DurableResumeCoordinator

**Semantic changes**: **ZERO** ✅

**Unchanged**:
- CHECK A (load checkpoint)
- RuntimeBinding resolution
- Signal validation
- CHECK B (completion/re-suspension)
- Checkpoint CAS operations
- Lifecycle event ordering

**Coordinator naturally carries richer PendingToolCall data** — no logic change ✅

---

## Q. Tests Added / Updated

### Core Tests Updated

**PendingToolCall validation**:
- operationId non-null ✅
- operationId non-blank ✅
- toolCallId retained ✅
- toolName retained ✅
- arguments retained ✅

### Test Fixtures Updated

**TestCheckpoints**:
- Added `testOperationId(int index)` helper
- Updated all factory methods to include operationId
- Deterministic test IDs ("test-op-0", "test-op-1", ...)

### Test Files Modified

**arctra-core**: 4 test files
- CheckpointStoreConcurrencyTest
- InMemoryCheckpointStoreTest
- CrossRuntimeFailureRecoveryTest
- RuntimeBindingExceptionClassificationTest

**arctra-runtime-react**: 7 test files
- DurableResumeCoordinatorTest
- DurableResumeMemoryTest
- DurableResumeGovernanceTest
- DurableResumeExecutionTest
- DurableResumeEvidenceTest
- ConcurrentDurableResumeTest
- ProtocolReconstructorTest

**Total test updates**: ~35 PendingToolCall construction sites

---

## R. Structural Search Results

### Production PendingToolCall Construction

```bash
$ grep -rn "new PendingToolCall(" arctra-*/src/main --include="*.java"
```

**Results**: 2 sites (both updated) ✅

1. SpringAiToolCallingEngine.java:362 — initial suspension
2. SpringAiResumedExecutionHandler.java:178 — re-suspension

**All use OperationIds.generate()** ✅

### Test PendingToolCall Construction

**All test sites use deterministic test IDs** ✅

**No stale 3-field construction remaining** ✅

---

## S. Public API Delta

### PendingToolCall

**Canonical constructor**:
- **Before**: `PendingToolCall(String toolCallId, String toolName, String arguments)`
- **After**: `PendingToolCall(String operationId, String toolCallId, String toolName, String arguments)`

**Backward-compatible constructor**: NO

**Breaking source compatibility**: YES ✅

**Breaking binary compatibility**: YES ✅

**Justification**: Early milestone, internal model API, clear migration path

---

### SuspensionCheckpoint

**Schema version**:
- **Before**: `"1.0"`
- **After**: `"1.1"`

**Structural change**: PendingToolCall field added (propagates through checkpoint)

---

## T. Full Regression

```
./mvnw clean verify

arctra-core: 205 tests, 0 failures ✅
arctra-runtime-react: 128 tests, 0 failures ✅  
examples: 35 tests, 0 failures ✅

TOTAL: 368 tests
Failures: 0 ✅
Errors: 0 ✅
Skipped: 23
BUILD SUCCESS ✅
```

**Baseline preserved** ✅

**No test regression** ✅

---

## U. Remaining Identity Gap

### What Is Still Missing?

**TOOL_EXECUTED / TOOL_FAILED events cannot carry authoritative operationId correlation**

### Why?

1. **toolCallId is lost** before delegate.call()
2. **operationId is not yet available** at execution boundary
3. **No 1:1 mapping** exists between PendingToolCall materialization → physical callback invocation

### What Blocks Correlation?

**Spring AI ToolCallingManager dispatch**:
- Dispatches by toolName to registered ToolCallback
- Multiple PendingToolCall instances with same toolName → ambiguous mapping
- No Spring AI API to inject per-call context through ToolCallback interface

### Solution Path (Future)

**Option A**: Enhance ToolObservationContext with operationId  
- Requires establishing PendingToolCall → ToolCallback invocation mapping  
- Complex due to provider dispatch indirection  

**Option B**: Pre-call/post-call event wrapping  
- Emit TOOL_STARTED before delegate.call() with operationId  
- Correlate TOOL_EXECUTED/TOOL_FAILED by execution order  
- Fragile under concurrent/async execution  

**Option C**: Separate tool execution runtime layer  
- Own complete execution lifecycle  
- Bypass Spring AI ToolCallingManager  
- Significant refactoring  

**M6-T3B decision required** ❌

---

## V. M6-T3B Readiness

### 1. Does every new checkpoint-backed PendingToolCall have an operationId?

**YES** ✅ — Generated at materialization, before checkpoint persistence

---

### 2. Is operationId framework-owned?

**YES** ✅ — OperationIds.generate() (UUID)

---

### 3. Is operationId provider-independent?

**YES** ✅ — No Spring AI coupling

---

### 4. Is it durable before physical execution is possible?

**YES** ✅ — Persisted in checkpoint.create() / replaceIfVersion() before resume

---

### 5. Is it stable across concurrent resume attempts?

**YES** ✅ — Both workers load same checkpoint → same operationId

---

### 6. Are new re-suspended operations assigned new operationIds?

**YES** ✅ — SpringAiResumedExecutionHandler generates new IDs for new operations

---

### 7. Is toolCallId still preserved separately?

**YES** ✅ — toolCallId distinct field, protocol reconstruction uses it

---

### 8. Does operationId reach delegate.call() authoritatively today?

**NO** ❌ — This is the remaining gap

---

### 9. Does T3A introduce attemptId?

**NO** ✅ — Deferred to future milestone

---

### 10. Does T3A implement idempotency?

**NO** ✅ — Identity foundation only

---

### 11. Does T3A change at-least-once semantics?

**NO** ✅ — at-least-once execution preserved

---

### 12. Did DurableResumeCoordinator semantics change?

**NO** ✅ — Zero coordinator changes

---

### 13. Is public API compatibility impact fully documented?

**YES** ✅ — Breaking change acknowledged, justified, documented

---

### 14. Is checkpoint schema compatibility truthfully documented?

**YES** ✅ — No persistent storage exists, migration deferred

---

### 15. Is M6-T3B now justified?

**YES** ✅ — operationId foundation complete, execution-boundary correlation is next natural milestone

---

## W. Decision

### ✅ **FULL GO / CLOSE M6-T3A**

**All completion criteria met**:
- [x] Every checkpoint-backed PendingToolCall has operationId
- [x] operationId is framework-owned
- [x] operationId is provider-independent
- [x] Durable before physical execution
- [x] Stable across concurrent resume
- [x] New re-suspended operations get new IDs
- [x] toolCallId preserved separately
- [x] No attemptId introduced
- [x] No idempotency implemented
- [x] at-least-once semantics preserved
- [x] DurableResumeCoordinator unchanged
- [x] Public API impact documented
- [x] Checkpoint schema compatibility truthful
- [x] Full regression green (368 tests, 0 failures)

**Limitation acknowledged**: operationId does NOT yet reach delegate.call() execution boundary

**M6-T3B readiness**: CONFIRMED

---

## HARD STOP

M6-T3A is complete.

**DO NOT implement**:
- attemptId
- TOOL_STARTED event
- operationId in TOOL_EXECUTED/TOOL_FAILED payload
- Execution-boundary correlation
- Idempotency
- Retry
- ToolExecutionRuntime

**Awaiting**: M6-T3B architecture review
