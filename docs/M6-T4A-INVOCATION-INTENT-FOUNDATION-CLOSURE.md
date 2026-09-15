# M6-T4A INVOCATION INTENT FOUNDATION CLOSURE

**Implementation Status**: ✅ **COMPLETE**  
**Date**: 2024  
**Author**: lov3r (via Claude Code)

---

## EXECUTIVE SUMMARY

**Mission**: Implement durable pre-call invocation intent recording as mandatory execution gate.

**Result**: M6-T4A successfully implements separate invocation-state authority (Candidate B from M6-T4.1) with hard gate semantics. Intent persistence blocks physical execution. All tests pass. Zero core changes. Zero public API changes.

**Test Baseline**:
- **Previous**: 369 tests (M6-T3B.1)
- **Current**: 389 tests (+20 new T4A tests)
- **Status**: ✅ All passing, 0 failures, 0 errors

---

## A. FROZEN BASELINE

### M6-T3B.1 Baseline
```
arctra-core:          205 tests
arctra-runtime-react: 129 tests
examples:              35 tests
────────────────────────────────
TOTAL:                369 tests
Failures:               0
Errors:                 0
Skipped:               23
BUILD SUCCESS
```

### M6-T4A New Tests
- **InMemoryInvocationStateStoreTest**: 14 tests
- **InvocationIntentGateTest**: 6 tests
- **Total new tests**: 20

### Current Total
```
arctra-core:          205 tests (unchanged)
arctra-runtime-react: 149 tests (+20)
examples:              35 tests (unchanged)
────────────────────────────────
TOTAL:                389 tests
Failures:               0
Errors:                 0
Skipped:               23
BUILD SUCCESS
```

---

## B. PRODUCTION DIFF

### New Files Created

**Package-private internal authority** (arctra-runtime-react):

1. **InvocationStateStore.java** (interface, 83 lines)
   - Recovery-critical invocation-intent authority contract
   - Single method: `recordInvocationIntent(processId, operationId)`
   - Provider-independent, no Spring AI dependencies

2. **InMemoryInvocationStateStore.java** (implementation, 75 lines)
   - Thread-safe JVM-local reference implementation
   - Uses `ConcurrentHashMap<String, Set<String>>` for storage
   - Idempotent state write semantics
   - NOT restart-durable (explicitly documented)

3. **InvocationIntentPersistenceException.java** (exception, 24 lines)
   - Framework infrastructure failure exception
   - NOT tool failure (distinct from TOOL_FAILED)

### Modified Production Files

**arctra-runtime-react/src/main/java/**:

1. **ProtocolReconstructor.java**
   - Added `InvocationStateStore` field
   - Modified constructor: `ProtocolReconstructor(tools, invocationStateStore)`
   - Modified `executeOperation()`: Added intent gate before `delegate.call()`
   - Lines changed: +15 (gate logic + constructor param)

2. **SpringAiResumedExecutionHandler.java**
   - Added `InvocationStateStore` field
   - Modified constructor: Added `invocationStateStore` parameter
   - Pass store to ProtocolReconstructor instances
   - Lines changed: +5

3. **SpringAiToolCallingEngine.java**
   - Added `InvocationStateStore` field
   - Create `InMemoryInvocationStateStore` instance at engine construction
   - Pass store to `SpringAiResumedExecutionHandler`
   - Lines changed: +4

### Modified Test Files

4. **ProtocolReconstructorTest.java**
   - Updated 6 test methods to pass `InMemoryInvocationStateStore` to constructor
   - Lines changed: +6

5. **TestTools.java** (new helper, 132 lines)
   - Test utility for creating counting/failing/recording tools

---

## C. INVOCATIO

NSTATESTORE CONTRACT

### Interface

**Package**: `cn.bitcss.arctra.runtime.react`  
**Visibility**: package-private (internal)  
**Dependencies**: None (provider-independent)

```java
interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId);
}
```

### Semantic Contract

**Method**: `recordInvocationIntent(processId, operationId)`

**Precondition**: Called immediately before physical `delegate.call()`

**Postcondition**: Intent durably recorded OR exception thrown

**Success**: Physical invocation MAY proceed  
**Failure**: Physical invocation MUST NOT proceed (hard gate)

**Idempotency**: Recording same (processId, operationId) multiple times succeeds  
**NOT claiming**: Multiple workers may record intent and execute (at-least-once preserved)

**Exceptions**:
- `InvocationIntentPersistenceException` if persistence fails
- `NullPointerException` if processId or operationId is null
- `IllegalArgumentException` if processId or operationId is blank

---

## D. INMEMORYINVOCATIONSTATESTORE SEMANTICS

### Implementation Characteristics

**Type**: In-memory reference implementation  
**Thread-Safety**: ✅ Yes (ConcurrentHashMap with concurrent Sets)  
**Restart-Durable**: ❌ NO (JVM-local only)  
**Cross-JVM**: ❌ NO (single-node only)  
**Claiming**: ❌ NO (idempotent state write)

### Storage Model

```
ConcurrentMap<String, Set<String>>
  processId → Set<operationId>
```

### Idempotent State Write

```java
// Worker A
recordInvocationIntent("proc-1", "op-A") → Set.add("op-A") → true

// Worker B (concurrent)
recordInvocationIntent("proc-1", "op-A") → Set.add("op-A") → false (already exists)
```

Both calls succeed. No claiming. Both workers may execute.

### Honest Limitations

**Documented in Javadoc**:
- NOT for production deployments requiring restart durability
- State lost on JVM restart
- Cannot coordinate across processes
- For testing and single-JVM validation only

---

## E. STORE OWNERSHIP / LIFECYCLE

### Ownership

**Owner**: `SpringAiToolCallingEngine`

**Lifecycle**: One instance per engine, created at engine construction, reused across all executions

### Construction Path

```
SpringAiToolCallingEngine (constructor)
  ↓ creates
InMemoryInvocationStateStore instance
  ↓ passes to
SpringAiResumedExecutionHandler (constructor)
  ↓ passes to
ProtocolReconstructor (local instantiation in handler methods)
  ↓ uses in
executeOperation() before delegate.call()
```

### Instance Reuse

**CORRECT**: Same store instance used across multiple resume operations for one engine  
**Intent state persists** across operations within same JVM session  
**NOT recreated** per operation (would lose intent tracking)

---

## F. EXACT PRE-CALL GATE

### Source Location

**File**: `ProtocolReconstructor.java`  
**Method**: `executeOperation()`  
**Line**: Immediately before `wrappedCallback.call()`

### Execution Flow

```java
// 1. Resolve ToolCallback by toolName
ToolCallback selectedTool = ...;

// 2. Create per-operation ToolObservationContext
ToolObservationContext operationContext = new ToolObservationContext(
    processId, checkpointVersion, operation.operationId(), eventListener);

// 3. Wrap with Evidence capture + event observation
EvidenceCapturingToolCallback wrappedCallback = 
    new EvidenceCapturingToolCallback(selectedTool, newEvidences, operationContext);

// 4. M6-T4A: INVOCATION INTENT GATE (hard gate)
if (operationContext != null) {
    invocationStateStore.recordInvocationIntent(
        operationContext.processId(), 
        operation.operationId());
    // If throws InvocationIntentPersistenceException, execution stops here
}

// 5. Build ToolContext
ToolContext toolContext = new ToolContext(...);

// 6. Physical invocation - ONLY AFTER SUCCESSFUL INTENT RECORDING
String result = wrappedCallback.call(operation.arguments(), toolContext);
  ↓
delegate.call(...)  // ← Physical delegate invocation
  ↓
TOOL_EXECUTED or TOOL_FAILED
```

### Gate Placement Verification

**Test**: `InvocationIntentGateTest.intentRecording_shouldOccurBeforeDelegateInvocation()`

Proves order:
1. `INTENT_RECORDED:op-123`
2. `DELEGATE_CALLED:test-tool`

---

## G. PERSISTENCE FAILURE SEMANTICS

### Failure Behavior

**If** `recordInvocationIntent()` **throws**:

1. ❌ Delegate NOT entered
2. ❌ No TOOL_EXECUTED event
3. ❌ No TOOL_FAILED event
4. ❌ No Evidence captured
5. ✅ Exception propagates: `InvocationIntentPersistenceException`
6. ✅ Checkpoint remains PENDING (CHECK B never reached)
7. ✅ Operation safe to retry after infrastructure repair

### Exception Classification

**InvocationIntentPersistenceException**:
- Framework infrastructure failure
- NOT tool failure (delegate never called)
- NOT governance failure
- Storage unavailable / disk full / network partition

**TOOL_FAILED** (distinct):
- Delegate exception (after successful intent recording)
- Tool logic failure
- External timeout/error

### Test Proof

**Test**: `InvocationIntentGateTest.intentPersistenceFailure_shouldBlockDelegateExecution()`

**Given**: Failing store throws exception  
**When**: Execute operation  
**Then**: 
- Exception propagates ✅
- Delegate call count = 0 ✅
- No evidence captured ✅

---

## H. SUCCESS PATH SEMANTICS

### Normal Execution Flow

```
recordInvocationIntent(proc-X, op-A) → SUCCESS
  ↓
delegate.call() → returns normally
  ↓
TOOL_EXECUTED event emitted
  ↓
Evidence captured
  ↓
ToolResponse constructed
  ↓
CHECK B (deleteIfVersion or replaceIfVersion)
```

### Test Coverage

**Test**: `InvocationIntentGateTest.intentPersistenceSuccess_shouldAllowDelegateExecution()`

**Assertions**:
- Intent recorded ✅
- Delegate invoked ✅
- Result constructed ✅

**Test**: `InvocationIntentGateTest.toolSuccessAfterIntentRecording_shouldEmitToolExecuted()`

**Assertions**:
- Intent recorded ✅
- TOOL_EXECUTED emitted ✅
- TOOL_FAILED NOT emitted ✅

---

## I. TOOL FAILURE PATH SEMANTICS

### Failure After Intent

```
recordInvocationIntent(proc-X, op-A) → SUCCESS
  ↓
delegate.call() → throws RuntimeException
  ↓
TOOL_FAILED event emitted
  ↓
Original exception propagates
  ↓
No Evidence captured (M4 semantics preserved)
```

### Critical Invariant

**Intent recorded** + **Delegate throws** = **TOOL_FAILED** (not gate failure)

### Test Coverage

**Test**: `InvocationIntentGateTest.toolFailureAfterIntentRecording_shouldEmitToolFailedNotGateFailure()`

**Assertions**:
- Intent WAS recorded (before delegate threw) ✅
- TOOL_FAILED event emitted ✅
- Exception propagates ✅

---

## J. CONCURRENT RESUME SEMANTICS

### At-Least-Once Preserved

**Timeline**:
```
Worker A: load checkpoint v1 → record intent(op-A) → execute → CHECK B (delete v1) → SUCCESS
Worker B: load checkpoint v1 → record intent(op-A) → execute → CHECK B (delete v1) → CONFLICT
```

**Both workers**:
- Record intent (idempotent Set.add) ✅
- Execute delegate ✅
- One CHECK B wins ✅

**No claiming introduced** ✅  
**No single-worker enforcement** ✅

### Test Coverage

**Test**: `InMemoryInvocationStateStoreTest.recordInvocationIntent_multipleWorkers_shouldNotClaim()`

**Assertions**:
- Duplicate recording succeeds ✅
- Both workers may proceed ✅

**Existing**: `ConcurrentDurableResumeTest` (M5 regression preserved)
- Both workers execute same operationId ✅
- One CHECK B wins ✅

---

## K. NO-CLAIMING PROOF

### Structural Search Results

**Search**: `claim`, `lease`, `fence`, `owner`, `workerId`, `lock` in new code

**Result**: 0 matches in production code

**Javadoc mentions**: Only to explicitly state "NOT claiming"

### Idempotent State Write ≠ Claiming

```java
// Both succeed (no boolean "newlyCreated" returned)
store.recordInvocationIntent("proc-1", "op-A"); // Worker A
store.recordInvocationIntent("proc-1", "op-A"); // Worker B

// Both may proceed to execute
// No "putIfAbsent" semantics preventing second worker
```

### Test Coverage

**Test**: `InMemoryInvocationStateStoreTest.recordInvocationIntent_duplicateRecording_shouldSucceed()`

**Assertion**: Duplicate recording does NOT throw ✅

---

## L. OPERATIONID CORRELATION PRESERVATION

### M6-T3B Semantics Unchanged

**Per-operation context** with `operationId` still created before physical invocation:

```java
ToolObservationContext operationContext = new ToolObservationContext(
    processId,
    checkpointVersion,
    operation.operationId(),  // M6-T3B: Exact 1:1 correlation
    eventListener
);
```

**Intent recording uses same operationId**:

```java
invocationStateStore.recordInvocationIntent(
    operationContext.processId(),
    operation.operationId()  // Same operationId from PendingToolCall
);
```

### Test Coverage

**Existing**: `ProtocolReconstructorTest` (M6-T3B regression preserved)
- Duplicate same-name tools with distinct operationIds ✅

---

## M. TOOLCONTEXT PRESERVATION

### M6-T3B.1 Semantics Unchanged

**ToolContext creation** occurs AFTER intent gate, unchanged:

```java
// Intent gate
invocationStateStore.recordInvocationIntent(...);

// ToolContext creation (unchanged from M6-T3B)
ToolContext toolContext = new ToolContext(
    Map.of("conversationHistory", conversationHistory)
);

// Delegate invocation with ToolContext
String result = wrappedCallback.call(operation.arguments(), toolContext);
```

**No regression** in ToolContext forwarding semantics.

---

## N. EVIDENCE SEMANTICS PRESERVATION

### M4 Semantics Unchanged

**Evidence capture** occurs inside `EvidenceCapturingToolCallback`, AFTER intent gate:

```java
// Intent gate (M6-T4A)
invocationStateStore.recordInvocationIntent(...);

// Delegate invocation
wrappedCallback.call(...)
  ↓
delegate.call() returns
  ↓
captureEvidence(toolName, result)  // M4 existing behavior
```

**Only successful executions** capture Evidence (M4 contract preserved).

### Test Coverage

**Existing**: `EvidenceCapturingToolCallbackTest` (M4 regression preserved)
- Evidence captured on success ✅
- No evidence on failure ✅

---

## O. CHECK A IMPACT

**Expected**: 0  
**Actual**: 0

**CHECK A location**: `DurableResumeCoordinator.loadAndValidateCheckpoint()`

**No changes** to CHECK A logic:
- Checkpoint loading unchanged
- Version validation unchanged
- StaleCheckpointException unchanged

**Intent recording** occurs AFTER CHECK A, during execution phase.

---

## P. CHECK B IMPACT

**Expected**: 0  
**Actual**: 0

**CHECK B location**: `DurableResumeCoordinator.handleResumedExecutionOutcome()`

**No changes** to CHECK B logic:
- `deleteIfVersion(processId, expectedVersion)` unchanged
- `replaceIfVersion(processId, expectedVersion, replacement)` unchanged
- Version tracking unchanged (still uses original loaded version)

**Intent recording** does NOT increment checkpoint version.

---

## Q. CHECKPOINT VERSION IMPACT

**Expected**: 0  
**Actual**: 0

**Checkpoint version** remains unchanged throughout execution:
- Loaded at CHECK A: `v1`
- Intent recorded: version still `v1` (no increment)
- CHECK B operates: against `v1`

**No version tracking complexity** introduced.

### Structural Search

**Search**: `checkpointVersion` in new code

**Result**: Only used to pass to `ToolObservationContext` (M6-T3B existing behavior)

---

## R. CORE DELTA

**Expected**: 0 production changes  
**Actual**: 0 production changes ✅

### Verification

```bash
git diff HEAD -- arctra-core/src/main/java/
```

**Result**: Empty (no changes)

**PendingToolCall**: Unchanged (still 4-parameter constructor with operationId from M6-T3A)  
**SuspensionCheckpoint**: Unchanged  
**CheckpointStore**: Unchanged  
**EventType**: Unchanged  
**ExecutionEvent**: Unchanged

**M6-T4A authority** entirely in `arctra-runtime-react` (internal).

---

## S. PUBLIC API DELTA

**Expected**: 0  
**Actual**: 0 ✅

### New Types Visibility

**All package-private** in `cn.bitcss.arctra.runtime.react`:
- `InvocationStateStore` (interface)
- `InMemoryInvocationStateStore` (implementation)
- `InvocationIntentPersistenceException` (exception)

**No public API exposure**.

### Existing Public API

**No constructor changes** to public types:
- `SpringAiToolCallingEngine` constructors unchanged (store created internally)
- `PendingToolCall` unchanged
- `SuspensionCheckpoint` unchanged

---

## T. TEST DELTA

### New Test Files

1. **InMemoryInvocationStateStoreTest.java** (14 tests)
   - Store semantics (record, idempotency, distinct ops/processes)
   - Thread safety (concurrent access)
   - Validation (null/blank rejection)

2. **InvocationIntentGateTest.java** (6 tests)
   - Hard gate (persistence failure blocks execution) ← MOST IMPORTANT
   - Success path (intent → delegate → success)
   - Failure path (intent → delegate throws → TOOL_FAILED)
   - Ordering (intent before delegate)
   - Multiple operations (partial failure handling)

3. **TestTools.java** (helper, not counted)

### Modified Test Files

4. **ProtocolReconstructorTest.java**
   - Updated 6 methods to pass `InMemoryInvocationStateStore`

### Test Count Reconciliation

```
M6-T3B.1 baseline: 369 tests
+ InMemoryInvocationStateStoreTest: 14 tests
+ InvocationIntentGateTest: 6 tests
────────────────────────────────────────────
Expected: 389 tests
Actual: 389 tests ✅
```

---

## U. STRUCTURAL SEARCH RESULTS

### No Core Changes

```bash
git diff HEAD -- arctra-core/src/main/java/
```
**Result**: Empty ✅

### No Checkpoint Schema Changes

**Search**: `CURRENT_SCHEMA_VERSION`, `PendingToolCall`, `SuspensionCheckpoint`

**Result**: Unchanged (still "1.1" from M6-T3A) ✅

### No Direct Ledger Use

**Search**: `ExecutionLedger`, `ExecutionRecord`, `ExecutionLedgerListener` in new invocation classes

**Result**: 0 dependencies ✅

### No Claiming Vocabulary

**Search**: `claim`, `lease`, `fence`, `owner`, `workerId`, `lock` in production code

**Result**: 0 matches (only in Javadoc stating "NOT claiming") ✅

### No attemptId

**Search**: `attemptId` in new production code

**Result**: 0 usage ✅ (deferred to retry correlation)

### No TOOL_STARTED

**Search**: `TOOL_STARTED` in new code

**Result**: 0 usage ✅ (rejected terminology)

---

## V. FULL REGRESSION

### Build Command

```bash
./mvnw clean verify
```

### Results

```
arctra-core:          205 tests
arctra-runtime-react: 149 tests (+20 new)
examples:              35 tests
────────────────────────────────────────────
TOTAL:                389 tests
Failures:               0
Errors:                 0
Skipped:               23
────────────────────────────────────────────
BUILD SUCCESS
```

### Existing Test Suites Preserved

✅ All M6-T3B tests pass  
✅ All M6-T3B.1 tests pass  
✅ All M5 durable resume tests pass  
✅ All M4 governance tests pass  
✅ All concurrent resume tests pass  
✅ All evidence capture tests pass  
✅ All lifecycle event tests pass

---

## W. REMAINING LIMITATION

### Honest Assessment

**M6-T4A establishes**:
- ✅ Recovery-critical invocation-intent authority contract
- ✅ Mandatory pre-call gating semantics
- ✅ Hard gate behavior (persistence failure blocks execution)
- ✅ Provider-independent authority model

**M6-T4A does NOT provide**:
- ❌ Restart-durable crash recovery
- ❌ Cross-JVM coordination
- ❌ Persistent storage implementation

### Why?

**InMemoryInvocationStateStore** is JVM-local reference implementation:
- State lost on JVM restart
- Cannot survive process crash
- Single-node only

### Production Path

**For restart durability**, implement persistent `InvocationStateStore`:
- JDBC-backed: store intent in database table
- Redis-backed: store intent in Redis SET
- Must survive JVM restart
- Must coordinate across nodes

**M6-T4A contract is correct**. Implementation is reference-only.

---

## X. DEFERRED WORK

### Explicitly NOT in M6-T4A

**Deferred to future milestones**:

1. **attemptId** (T4B/T4E)
   - Per-attempt correlation
   - Retry attempt identity
   - Attempt-bound idempotency

2. **Recovery query** (T4B)
   - `hasInvocationIntent(processId, operationId)`
   - Scan uncertain operations
   - Recovery decision logic

3. **Persistent InvocationStateStore** (T4B)
   - JDBC implementation
   - Redis implementation
   - Restart durability

4. **Retry logic** (T4E)
   - Automatic retry
   - Retry policy
   - Exponential backoff

5. **Idempotency** (T4C)
   - Idempotency key API
   - External deduplication
   - Safe replay

6. **Receipt** (T4C)
   - External receipt verification
   - Commit proof
   - Reconciliation API

7. **External reconciliation** (T4D)
   - Query external system
   - Determine commit state
   - Compensation

8. **Recovery policy** (T4D)
   - Uncertain operation handling
   - Retry vs query vs operator
   - Policy configuration

9. **Operator intervention** (T4F)
   - Operator UI
   - Manual resolution
   - Force completion/rollback

10. **ToolExecutionRuntime** (T4B/T5)
    - Unified tool execution subsystem
    - Timeout tracking
    - Circuit breaker

---

## Y. CLOSURE GATE

### 32 Mandatory Questions

**1. Does a separate InvocationStateStore exist?**  
✅ YES (interface + InMemoryInvocationStateStore)

**2. Is it package-private/internal?**  
✅ YES (cn.bitcss.arctra.runtime.react, not public)

**3. Is it provider-independent?**  
✅ YES (no Spring AI types, uses only processId/operationId)

**4. Does it depend on ExecutionLedger?**  
✅ NO

**5. Does it depend on CheckpointStore?**  
✅ NO

**6. Does it modify arctra-core?**  
✅ NO (0 production changes in core)

**7. Does it modify public API?**  
✅ NO (all new types package-private)

**8. Is INVOCATION_INTENT recorded before physical invocation?**  
✅ YES (proven by ordering test)

**9. Does intent persistence failure prevent delegate.call?**  
✅ YES (proven by hard gate test)

**10. Does persistence failure emit TOOL_FAILED?**  
✅ NO (framework gate failure, not tool failure)

**11. Does persistence failure emit TOOL_EXECUTED?**  
✅ NO (delegate never called)

**12. Does delegate success still emit TOOL_EXECUTED?**  
✅ YES (M6-T2B semantics preserved)

**13. Does delegate failure still emit TOOL_FAILED?**  
✅ YES (M6-T2B semantics preserved)

**14. Is Evidence behavior unchanged?**  
✅ YES (M4 semantics preserved)

**15. Is ToolContext forwarding unchanged?**  
✅ YES (M6-T3B.1 semantics preserved)

**16. Is toolCallId preservation unchanged?**  
✅ YES (protocol correlation preserved)

**17. Is operationId correlation unchanged?**  
✅ YES (M6-T3B semantics preserved)

**18. Is duplicate intent recording monotonic/idempotent?**  
✅ YES (Set.add semantics)

**19. Does duplicate intent recording suppress execution?**  
✅ NO (no claiming)

**20. Is concurrent at-least-once preserved?**  
✅ YES (both workers may execute)

**21. Is claiming introduced?**  
✅ NO (structural search: 0 matches)

**22. Is lease/fencing introduced?**  
✅ NO (structural search: 0 matches)

**23. Is attemptId introduced?**  
✅ NO (deferred to retry correlation)

**24. Is TOOL_STARTED introduced?**  
✅ NO (rejected terminology)

**25. Is CHECK A unchanged?**  
✅ YES (0 impact)

**26. Is CHECK B unchanged?**  
✅ YES (0 impact)

**27. Is checkpointVersion unchanged?**  
✅ YES (no increment, no tracking complexity)

**28. Is InMemoryInvocationStateStore honestly documented as JVM-local?**  
✅ YES (Javadoc explicitly states limitations)

**29. Is restart durability explicitly NOT claimed?**  
✅ YES ("NOT restart durable" in Javadoc and this report)

**30. Is full regression green?**  
✅ YES (389 tests, 0 failures, BUILD SUCCESS)

**31. Is test-count delta reconciled?**  
✅ YES (369 + 20 = 389)

**32. Is M6-T4A safe to close?**  
✅ YES

---

## Z. DECISION

**✅ FULL GO / CLOSE M6-T4A**

### All Closure Requirements Met

✅ Separate invocation-state authority implemented  
✅ Intent persistence before delegate  
✅ Persistence failure hard-gates physical invocation  
✅ Intent failure not classified as TOOL_FAILED  
✅ Concurrent at-least-once preserved  
✅ No claiming  
✅ No attemptId  
✅ No ToolExecutionRuntime  
✅ No core production delta  
✅ No public API delta  
✅ CHECK A unchanged  
✅ CHECK B unchanged  
✅ checkpointVersion unchanged  
✅ operationId/toolCallId/ToolContext semantics preserved  
✅ In-memory durability limitation truthfully documented  
✅ Mandatory tests executable and green  
✅ Full regression green

---

## HARD STOP

**M6-T4A CLOSED**

**DO NOT IMPLEMENT**:
- hasInvocationIntent recovery API
- Uncertain-operation scanning
- attemptId
- TOOL_STARTED
- Retry
- Automatic retry
- Idempotency key contract
- Receipt API
- External query
- Recovery policy
- Operator intervention
- Compensation
- Lease
- Fencing
- Worker ownership
- ToolExecutionRuntime
- JDBC InvocationStateStore
- Redis InvocationStateStore
- Persistent CheckpointStore
- Persistent ExecutionLedger
- New public recovery SPI

**Awaiting**: Architecture review before M6-T4B.

---

**END OF M6-T4A INVOCATION INTENT FOUNDATION CLOSURE**
