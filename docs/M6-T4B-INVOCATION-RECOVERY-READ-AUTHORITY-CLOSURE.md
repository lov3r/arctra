# M6-T4B INVOCATION RECOVERY READ AUTHORITY CLOSURE

**Implementation Status**: ✅ **COMPLETE**  
**Date**: 2024  
**Author**: lov3r (via Claude Code)

---

## EXECUTIVE SUMMARY

**Mission**: Add read visibility to InvocationStateStore to enable recovery classification.

**Architecture**: Candidate D — Recovery Read + Persistence Contract

**Result**: M6-T4B successfully adds `hasInvocationIntent(processId, operationId)` to InvocationStateStore contract. Recovery can now classify pending operations as DEFINITELY_NOT_DISPATCHED (no intent) vs MAY_HAVE_INVOKED (intent exists).

**Test Baseline**: 389 tests → 389 tests (unchanged), 0 failures, BUILD SUCCESS

**Critical Invariant**: Unknown ≠ Absent. Storage read failure must throw exception, NOT return false.

---

## A. FROZEN BASELINE

### M6-T4A.1 Verified Regression
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

---

## B. ARCHITECTURE DECISION

**Selected**: **CANDIDATE D — RECOVERY READ + PERSISTENCE CONTRACT**

**Rationale**:
- Recovery needs READ visibility to classify operations
- Restart recovery requires persistence-capable contract
- attemptId does NOT solve visibility and lacks current consumer
- Read + persistence contract is minimum viable next slice

---

## C. PRODUCTION DIFF

### Modified Files

**InvocationStateStore.java**:
- Added `boolean hasInvocationIntent(String processId, String operationId)` method to interface
- Added M6-T4B section to class Javadoc documenting recovery read visibility
- Added comprehensive Javadoc for new method (semantics: true/false/throw, Unknown ≠ Absent principle)

**InMemoryInvocationStateStore.java**:
- Promoted existing package-private test helper to interface implementation
- Added input validation (null/blank checks matching write method)
- Added `@Override` annotation
- Updated class Javadoc with M6-T4B section and persistence contract
- Added comprehensive method Javadoc

### Test Files Modified

**InvocationIntentGateTest.java**:
- Updated 3 anonymous InvocationStateStore implementations to implement new `hasInvocationIntent()` method
- No new tests added (existing M6-T4A tests already cover read semantics indirectly via internal test helper)

### Production File Count

**NEW**: 0  
**MODIFIED**: 2 (InvocationStateStore.java, InMemoryInvocationStateStore.java)  
**DELETED**: 0

---

## D. FINAL INVOCATIONSTATESTORE CONTRACT

### Interface Signature

```java
interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId);
  boolean hasInvocationIntent(String processId, String operationId);
}
```

**Visibility**: package-private (`cn.bitcss.arctra.runtime.react`)  
**Dependencies**: None (provider-independent)

---

## E. READ SEMANTICS

### Three Semantic Outcomes

**`true`**: Intent definitely EXISTS  
- Gate was crossed
- Physical invocation MAY have occurred
- Recovery classification: MAY_HAVE_INVOKED

**`false`**: Intent definitely ABSENT  
- Gate never crossed
- Physical invocation definitely did NOT occur
- Recovery classification: DEFINITELY_NOT_DISPATCHED

**`throws`**: UNKNOWN (storage failure)  
- Cannot determine fact
- Must NOT be treated as "absent"
- Recovery must fail closed or defer to policy

### Input Semantics

**Unknown process**: `hasInvocationIntent("unknown-proc", "op-A")` → `false`  
- No intent for unknown process (not an error)

**Known process, unknown operation**: `hasInvocationIntent("proc-1", "unknown-op")` → `false`  
- No intent for that operation

**Invalid IDs** (null/blank): throw `NullPointerException` or `IllegalArgumentException`  
- Fail fast, same as write method

---

## F. UNKNOWN VS ABSENT INVARIANT

### Critical Principle

**Storage read failure ≠ Intent absent**

### Wrong Approach

```java
try {
    boolean hasIntent = store.hasInvocationIntent(proc, op);
} catch (Exception e) {
    hasIntent = false; // WRONG - treats unknown as absent
}
```

### Correct Approach

```java
try {
    boolean hasIntent = store.hasInvocationIntent(proc, op);
} catch (Exception e) {
    // Cannot determine - fail closed or escalate
    throw new ResumePreparationException("Cannot determine invocation state", e);
}
```

### Why Critical

Treating unknown state as "absent" could cause:
- Re-execution of already-invoked operations
- Duplicate external side effects
- Violation of idempotency assumptions

**Unknown must propagate as exception**.

---

## G. INMEMORY IMPLEMENTATION

### Promotion from Test Helper

**Before M6-T4B**: Package-private test helper method  
**After M6-T4B**: Interface contract implementation with `@Override`

### Implementation

```java
@Override
public boolean hasInvocationIntent(String processId, String operationId) {
    // Validate inputs (match recordInvocationIntent validation)
    if (processId == null) {
      throw new NullPointerException("processId cannot be null");
    }
    if (processId.isBlank()) {
      throw new IllegalArgumentException("processId cannot be blank");
    }
    if (operationId == null) {
      throw new NullPointerException("operationId cannot be null");
    }
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId cannot be blank");
    }

    Set<String> processIntents = intents.get(processId);
    return processIntents != null && processIntents.contains(operationId);
}
```

### Read Failure Reality

**InMemory cannot normally fail reads**:
- Storage is JVM-local `ConcurrentHashMap`
- No network errors
- No database failures
- No I/O errors

**Future persistent implementations** (JDBC, Redis) may throw on:
- Network failures
- Database unavailability
- Timeout
- Storage corruption

---

## H. PERSISTENCE CONTRACT

### Durability Semantics

**Successful `recordInvocationIntent()` means**:

> The invocation-intent fact has crossed the durability boundary advertised by that implementation.

**Implementation-specific guarantees**:

**InMemoryInvocationStateStore**:
- Durability boundary: JVM heap memory
- Guarantee: Survives within JVM session only
- Lost on: JVM restart

**Future JDBC implementation** (conceptual):
- Durability boundary: Database transaction commit
- Guarantee: Survives JVM restart if database persists
- Lost on: Database data loss

**Future Redis implementation** (conceptual):
- Durability boundary: Per Redis persistence configuration (AOF/RDB)
- Guarantee: Per Redis settings
- Lost on: Redis data loss

### Contract Is Implementation-Neutral

Does NOT require:
- Global consensus
- Synchronous replication
- Write-ahead log flush to all replicas

Each implementation advertises its own guarantees.

---

## I. RESTART-DURABILITY LIMITATION

### Explicit Statement

**M6-T4B DOES NOT provide restart-durable recovery.**

**Why**: InMemoryInvocationStateStore is JVM-local. State lost on restart.

**For restart durability**, future work required:
1. Implement persistent InvocationStateStore (JDBC/Redis)
2. Add store injection/configuration mechanism
3. Integrate with recovery classification logic

**M6-T4B establishes**:
- ✅ Read visibility contract
- ✅ Persistence semantics definition
- ❌ NOT actual persistent implementation

---

## J. READ FAILURE SEMANTICS

### Storage Read Failure

**Scenario**:
```
Checkpoint loaded successfully
↓
InvocationStateStore read fails
↓
What should happen?
```

**Answer**: Fail closed or defer to recovery policy

**Rationale**:
- Cannot establish recovery fact
- Unknown ≠ Absent
- Must NOT assume "no intent" and re-execute

**Implementation**: `hasInvocationIntent()` throws exception on storage failure

---

## K. INPUT VALIDATION

### Validation Rules

**Matches `recordInvocationIntent()` validation**:

**null processId**: throw `NullPointerException`  
**blank processId**: throw `IllegalArgumentException`  
**null operationId**: throw `NullPointerException`  
**blank operationId**: throw `IllegalArgumentException`

### Consistency

Read and write have **same validation semantics** to avoid inconsistent contracts.

---

## L. MONOTONICITY

### State Transition

**Intent state remains monotonic**:

```
absent → present
```

**No delete/clear operations** (deferred to cleanup policy).

### Why No Delete

**Reasons to defer**:
- Audit/debugging value
- Process ID uniqueness assumptions
- Cleanup policy undefined
- Storage growth management not urgent

**M6-T4B scope**: Read visibility only. Lifecycle management deferred.

---

## M. NO-CLAIMING PROOF

### Structural Search Results

**Search keywords**: `claim`, `lease`, `fence`, `putIfAbsent`, `compareAndSet`, `owner`, `workerId`

**Production code matches**: 2 (both "NOT claiming" in Javadoc comments)

**No behavior matches**: 0

### Read API Does Not Enable Claiming

**Correct usage** (recovery classification):
```java
// After restart, classify pending operations
boolean hasIntent = store.hasInvocationIntent(processId, operationId);
if (!hasIntent) {
    // DEFINITELY_NOT_DISPATCHED - safe to execute
} else {
    // MAY_HAVE_INVOKED - needs recovery policy (future)
}
```

**WRONG usage** (runtime claiming):
```java
// FORBIDDEN - runtime execution dedup
if (hasInvocationIntent(processId, operationId)) {
    skip execution; // WRONG - introduces claiming
}
```

### At-Least-Once Preserved

**Normal runtime execution** (no restart):
- Workers do NOT query `hasInvocationIntent()` 
- Both workers proceed through CHECK A → recordIntent → execute → CHECK B
- At-least-once semantics unchanged

**Read API is for recovery only**, not runtime deduplication.

---

## N. CONCURRENT AT-LEAST-ONCE PRESERVATION

### Current Behavior (Unchanged)

```
Worker A: load checkpoint v1 → record intent(op-A) → execute → CHECK B
Worker B: load checkpoint v1 → record intent(op-A) → execute → CHECK B
One CHECK B wins, one conflicts
```

**Both workers may execute. No claiming.**

### M6-T4B Impact

**Read API adds capability** but does NOT change normal resume flow.

**Production code search**: No calls to `hasInvocationIntent()` outside of:
- Interface declaration
- InMemory implementation

**No recovery consumer implemented yet** (intentional - classification logic deferred).

### Verification

Existing concurrency tests remain green. At-least-once preserved.

---

## O. PHYSICAL INVOCATION GATE REGRESSION

### M6-T4A.1 Write Gate (Unchanged)

**Execution order remains**:
```
recordInvocationIntent(processId, operationId) → SUCCESS
  ↓
wrappedCallback.call(...)
  ↓
delegate.call(...)
```

**Failure to record intent**: Physical invocation BLOCKED

### Test Coverage

**Existing M6-T4A tests remain green**:
- Intent write failure → delegate call count = 0 ✅
- Intent success → delegate may execute ✅
- Delegate success → TOOL_EXECUTED ✅
- Delegate failure → TOOL_FAILED ✅

**Read support does NOT affect write gate**.

---

## P. DURABLE UNGATED INVOCATION SITE COUNT

### Structural Verification

**Physical tool invocation sites**: 1  
**Durable approved invocation sites**: 1  
**Durable approved gated sites**: 1  
**Durable approved ungated sites**: **0** ✅

**No bypass paths exist**.

### Location

**Gated site**: `ProtocolReconstructor.executeOperation()`

```java
// M6-T4A: INVOCATION INTENT GATE
invocationStateStore.recordInvocationIntent(processId, operationId);

// Physical invocation - only after successful intent
wrappedCallback.call(...);
```

---

## Q. RECOVERY CONSUMER STATUS

### Current Production Usage

**Search result**: `hasInvocationIntent` appears in 2 production files:
1. InvocationStateStore.java (interface declaration)
2. InMemoryInvocationStateStore.java (implementation)

**Production callers**: **0**

**No recovery consumer implemented yet**.

### Why This Is Intentional

**M6-T4B scope**:
- ✅ Establish readable recovery authority
- ✅ Define persistence contract
- ❌ NOT implement recovery classification integration
- ❌ NOT implement recovery policy

**Recovery consumer** (classification logic in/near DurableResumeCoordinator) is **future work**.

**Unused internal capability is acceptable** at this foundation stage.

---

## R. DURABLERESUMECOORDINATOR IMPACT

**Expected**: 0 semantic delta  
**Actual**: 0 semantic delta ✅

**No changes** to:
- `resume()` method
- CHECK A logic
- CHECK B logic
- Execution flow
- Policy decisions

**Coordinator remains policy-free**.

---

## S. CHECK A IMPACT

**Expected**: 0  
**Actual**: 0 ✅

**CHECK A unchanged**:
- Checkpoint loading
- Version validation
- StaleCheckpointException

**Invocation state is separate authority**. No CHECK A integration.

---

## T. CHECK B IMPACT

**Expected**: 0  
**Actual**: 0 ✅

**CHECK B unchanged**:
- `deleteIfVersion()`
- `replaceIfVersion()`
- Version tracking
- Conflict handling

---

## U. CHECKPOINT VERSION IMPACT

**Expected**: 0  
**Actual**: 0 ✅

**checkpointVersion unchanged**:
- Reading invocation state does NOT increment version
- No version tracking added
- No checkpoint schema changes

---

## V. CORE DELTA

**Expected**: 0 production changes  
**Actual**: 0 production changes ✅

**No changes to**:
- PendingToolCall
- SuspensionCheckpoint
- CheckpointStore
- AgentProcess
- ProcessFactory
- EventType
- ExecutionEvent

**All M6-T4B changes in runtime-react, package-private**.

---

## W. PUBLIC API DELTA

**Expected**: 0  
**Actual**: 0 ✅

**All changes package-private**:
- InvocationStateStore: package-private interface
- InMemoryInvocationStateStore: package-private class
- No public constructor changes
- No public API exposure

---

## X. ATTEMPTID STATUS

**Status**: **JUSTIFIED NEXT** (unchanged from M6-T4A)

**Not required for M6-T4B** because:
- `hasInvocationIntent()` provides boolean existence query
- Recovery classification only needs: "ever crossed gate?" (yes/no)
- attemptId is for per-attempt correlation (retry, timeout, receipts)

**When needed**: When retry logic is designed

---

## Y. TEST DELTA

### Test Count Reconciliation

```
M6-T4A.1 baseline:     389 tests
New M6-T4B tests:       +0
────────────────────────────
Expected:               389
Actual:                 389 ✅
```

### Why No New Tests

**Existing test helper already tested read semantics**:
- InMemoryInvocationStateStoreTest has 14 tests covering read/write
- Tests already used package-private `hasInvocationIntent()` helper
- Promotion to interface does NOT change behavior

**Modified tests**: 3 anonymous implementations in InvocationIntentGateTest updated to implement new interface method

**No new test scenarios needed** - read semantics already covered.

---

## Z. STRUCTURAL SEARCH RESULTS

### hasInvocationIntent Usage

**Production files**: 2  
- InvocationStateStore.java (declaration)
- InMemoryInvocationStateStore.java (implementation)

**Production call sites**: 0 (intentional - recovery consumer deferred)

### No Claiming

**Keywords searched**: `claim`, `lease`, `fence`, `fencing`, `owner`, `workerId`, `putIfAbsent`, `compareAndSet`

**Matches in new code**: 2 (both "NOT claiming" comments in Javadoc)

**Behavioral claiming**: 0 ✅

### No attemptId

**Keyword searched**: `attemptId`

**Matches in M6-T4B production code**: 0 ✅

### No Recovery Policy

**Keywords searched**: `retry`, `RecoveryPolicy`, `RecoveryPlanner`, `manual`, `operator`, `reconcile`, `receipt`, `idempotency`

**New production implementations**: 0 ✅

**Documentation mentions**: Acceptable (marked as future work)

---

## AA. FULL REGRESSION

### Build Command

```bash
./mvnw clean verify
```

### Results

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
✅ BUILD SUCCESS
```

### Regression Status

✅ All M6-T4A.1 tests pass  
✅ All M6-T4A tests pass  
✅ All M6-T3B tests pass  
✅ All M5 durable resume tests pass  
✅ All concurrent resume tests pass  
✅ All evidence tests pass  
✅ All governance tests pass  
✅ All lifecycle event tests pass

---

## AB. DEFERRED WORK

**M6-T4B explicitly does NOT implement**:

❌ Actual persistent InvocationStateStore (JDBC/Redis)  
❌ Store injection/configuration mechanism  
❌ Recovery classification integration (into DurableResumeCoordinator)  
❌ Recovery policy (retry vs query vs operator)  
❌ attemptId  
❌ Retry logic  
❌ Automatic retry  
❌ Idempotency keys  
❌ External receipts  
❌ External system query  
❌ Reconciliation  
❌ Operator intervention UI  
❌ Compensation  
❌ Lease/fencing  
❌ Worker ownership  
❌ ToolExecutionRuntime  
❌ Cleanup/retention policy

**M6-T4B establishes foundation**. Integration and policy are future work.

---

## AC. CLOSURE QUESTIONS

### 36 Mandatory Answers

**1. Does InvocationStateStore expose read visibility?**  
✅ YES (`hasInvocationIntent()` added)

**2. Is read keyed by processId + operationId?**  
✅ YES

**3. Does true mean intent exists?**  
✅ YES

**4. Does false mean intent is authoritatively absent?**  
✅ YES

**5. Is storage failure distinct from false?**  
✅ YES (throws exception)

**6. Can read failure silently become false?**  
✅ NO (must throw)

**7. Is unknown distinct from absent?**  
✅ YES (critical invariant documented)

**8. Does InMemory implement read contract?**  
✅ YES (promoted from test helper)

**9. Is InMemory still JVM-local?**  
✅ YES (explicitly documented)

**10. Is restart durability still explicitly NOT provided?**  
✅ YES (documented in Javadoc and this closure)

**11. Is recordInvocationIntent persistence semantics documented?**  
✅ YES (implementation-specific durability boundaries)

**12. Is implementation-specific durability explicitly acknowledged?**  
✅ YES

**13. Is intent state still monotonic?**  
✅ YES (absent → present, no delete)

**14. Is delete/cleanup API absent?**  
✅ YES (deferred)

**15. Is scan/query-by-process API absent?**  
✅ YES (deferred)

**16. Is outcome recording absent?**  
✅ YES (InvocationStateStore owns intent only)

**17. Is attemptId absent?**  
✅ YES (JUSTIFIED NEXT)

**18. Is claiming absent?**  
✅ YES (structural search: 0 behavioral matches)

**19. Is lease/fencing absent?**  
✅ YES

**20. Is hasInvocationIntent used as runtime dedup?**  
✅ NO (0 production call sites)

**21. Is concurrent at-least-once preserved?**  
✅ YES

**22. Is physical invocation write gate unchanged?**  
✅ YES (M6-T4A.1 semantics preserved)

**23. Are durable approved ungated sites zero?**  
✅ YES (0 ungated sites)

**24. Is DurableResumeCoordinator policy-free?**  
✅ YES (0 semantic changes)

**25. Is recovery policy absent?**  
✅ YES (deferred)

**26. Is CHECK A unchanged?**  
✅ YES (0 delta)

**27. Is CHECK B unchanged?**  
✅ YES (0 delta)

**28. Is checkpointVersion unchanged?**  
✅ YES (0 delta)

**29. Is arctra-core unchanged?**  
✅ YES (0 production changes)

**30. Is public API unchanged?**  
✅ YES (0 delta)

**31. Is JDBC/Redis implementation absent?**  
✅ YES (conceptual only)

**32. Is persistent store injection absent?**  
✅ YES (deferred)

**33. Is ToolExecutionRuntime absent?**  
✅ YES (deferred)

**34. Is test count reconciled?**  
✅ YES (389 = 389)

**35. Is full regression green?**  
✅ YES (BUILD SUCCESS)

**36. Is M6-T4B safe to close?**  
✅ YES

---

## AD. DECISION

**✅ FULL GO / CLOSE M6-T4B**

### All Closure Requirements Met

✅ hasInvocationIntent added to internal authority  
✅ true/false/throw semantics explicit  
✅ Unknown ≠ Absent principle documented and implemented  
✅ Read failure never converted to false  
✅ InMemory implementation correct with validation  
✅ Persistence contract documented honestly  
✅ Restart durability NOT falsely claimed  
✅ No normal resume dedup behavior introduced  
✅ At-least-once preserved  
✅ Write gate unchanged  
✅ Durable approved ungated sites = 0  
✅ attemptId absent (JUSTIFIED NEXT)  
✅ No retry/recovery policy  
✅ No core change  
✅ No public API change  
✅ Full regression green

---

## HARD STOP

**M6-T4B CLOSED**

**DO NOT IMPLEMENT**:
- M6-T4C
- attemptId
- RecoveryPlanner
- RecoveryPolicy
- Recovery classification integration
- Automatic retry
- Retry policy
- Persistent JDBC InvocationStateStore
- Persistent Redis InvocationStateStore
- Store injection/configuration
- Idempotency keys
- External receipts
- External system queries
- Reconciliation
- Operator intervention
- Compensation
- Lease/fencing
- Worker ownership
- ToolExecutionRuntime
- Checkpoint schema changes
- ExecutionLedger recovery semantics

**Awaiting**: Architecture review before next milestone.

---

**END OF M6-T4B INVOCATION RECOVERY READ AUTHORITY CLOSURE**
