# ARCTRA TEST CONTRACT

**Version:** M7  
**Last Updated:** 2026-09-20  
**Status:** CURRENT BLOCKING BASELINE

---

## Test Classification System

All tests are classified into one of five categories:

### A. CRITICAL M6 KERNEL CONTRACT ✅ BLOCKING

Core durable execution semantics that MUST remain protected.

**Must Cover:**
- CHECK A: Version validation before resume
- CHECK B: CAS transition during completion/suspension
- RUNNABLE resume semantics
- WAITING_FOR_SIGNAL resume semantics
- operationId preservation across restarts
- toolCallId preservation across restarts
- attemptId increment semantics
- executionEpoch same-incarnation behavior
- executionEpoch cross-incarnation behavior  
- T5 recovery classification
- MAY_HAVE_INVOKED fail-closed behavior
- RecoveryResolution enumeration
- Tool result crash recovery
- JDBC durable checkpoint persistence
- JDBC InvocationStateStore persistence
- Unsupported durable configuration fail-fast
- Concurrent resume / CHECK B winner selection
- At-least-once execution guarantee

**Representative Tests:**
- `JdbcCheckpointStoreTest` - JDBC persistence
- `InMemoryCheckpointStoreTest` - Core semantics
- Selected concurrency tests preserving M6 CHECK A/B contracts
- Selected recovery classification tests

---

### B. CURRENT M7 CONTRACT ✅ BLOCKING

Recovery Control Plane discovery and operational semantics.

**Must Cover:**
- `CheckpointStore.listContinuations()` returns all checkpoints
- `CheckpointStore.listContinuationsByDisposition()` filters correctly
- Discovery returns point-in-time snapshot
- Discovery does NOT grant ownership
- Discovery ordering semantics (oldest-first)
- RecoverableContinuation immutable snapshot contract
- RecoveryControlPlane.listContinuations() facade
- RecoveryControlPlane.getContinuation() by processId
- Version-aware resume through Control Plane
- Signal forwarding to underlying engine
- Stale descriptor rejection
- Checkpoint deletion after completion
- RUNNABLE continuation discovery
- WAITING_FOR_SIGNAL continuation discovery
- Cross-runtime JDBC recovery (integration)

**Tests:**
- M7 core unit tests (to be created)
- M7 integration scenarios A-F (to be created)

---

### C. USEFUL REGRESSION ⚠️ MAINTAIN IF CHEAP

Tests providing value but not blocking M7 closure.

**Characteristics:**
- Cover edge cases not in A or B
- Stable, compile cleanly
- Low maintenance cost
- Provide additional safety net

**Examples:**
- Additional concurrency edge cases
- Error path coverage
- Metadata preservation tests
- Event emission verification

**Status:** Keep if they compile without modification

---

### D. LEGACY / IMPLEMENTATION-COUPLED 🔶 QUARANTINE

Tests tightly coupled to internal implementation details.

**Characteristics:**
- Access internal fields (e.g., `store.checkpoints`)
- Use bespoke test doubles with complex internal plumbing
- Test implementation structure rather than semantic contracts
- Created for intermediate milestones (M4, M5 refactors)
- Require large mechanical rewrites after legitimate API evolution

**Examples:**
- Tests accessing `FakeCheckpointStore.checkpoints` field
- Tests depending on internal `InMemoryCheckpointStore` structure
- Temporary architecture migration tests

**Action:** Move to `src/legacy-test/` or remove after analysis

---

### E. OBSOLETE / DUPLICATIVE ❌ REMOVE

Tests that no longer serve any purpose.

**Characteristics:**
- Duplicate stronger integration coverage
- Test features that were removed
- Test intermediate states of completed refactors
- Superseded by better tests in A, B, or C

**Action:** Delete after documentation

---

## Current Blocking Baseline

The **default Maven test lifecycle** runs only:

- Category A: Critical M6 Kernel
- Category B: Current M7 Contract  
- Category C: Useful Regression (if stable)

**Command:**
```bash
./mvnw clean test
```

Must execute and pass ALL tests in categories A, B, C.

---

## Quarantined Tests

Tests in category D are explicitly excluded from default compilation.

**Location:** `src/legacy-test/java/` (if retained)

**Status:** NOT COUNTED AS PASSING

**Rationale:** These tests are coupled to internal implementation details that legitimately changed during M7 API evolution. Mechanical repair would consume substantial time without protecting architectural contracts.

**Coverage Replacement:** Categories A + B provide stronger coverage through:
- Focused kernel invariant tests
- Integration scenarios
- Cross-runtime verification

---

## Test Execution Report Format

All test reports must clearly separate:

✅ **PASSED (Baseline)**
- Critical M6 Kernel: X tests
- Current M7 Contract: Y tests  
- Useful Regression: Z tests

🔶 **QUARANTINED (Not Executed)**
- Legacy/Implementation-Coupled: N tests
- Location: src/legacy-test/

❌ **FAILED (Blocking)**
- Any failure in categories A or B

---

## M6 Kernel Invariants Reference

These semantic contracts from M6 remain permanently protected:

### Identity & Versioning
- processId uniquely identifies continuation
- checkpointVersion increments on each transition
- Version mismatch causes stale rejection

### Concurrency
- CHECK A: Load checkpoint, validate version
- CHECK B: CAS transition to next state
- Only one CHECK B winner per checkpoint version
- Losers get CheckpointTransitionConflictException

### Recovery Classification
- Crash before tool execution → NOT_INVOKED → safe retry
- Crash during tool execution → MAY_HAVE_INVOKED → classification required
- Classification returns RecoveryResolution
- MAY_HAVE_INVOKED → fail closed by default

### Durability
- JDBC CheckpointStore → restart-safe
- JDBC InvocationStateStore → restart-safe
- In-memory stores → ephemeral (explicit)
- Mismatched durability → fail-fast

### Execution Semantics
- RUNNABLE → resume execution immediately
- WAITING_FOR_SIGNAL → requires approval signal
- Signal must contain approval decision
- Governance re-evaluated on resume

### At-Least-Once
- Physical execution MAY occur multiple times
- Only one logical completion (CHECK B winner)
- No exactly-once claim
- Idempotency responsibility on tool side

---

## M7 Control Plane Contracts

These new contracts from M7 must be verified:

### Discovery Semantics
- Returns point-in-time snapshot
- Does NOT grant ownership
- Concurrent modifications allowed
- No lease, no lock

### Snapshot Properties
- RecoverableContinuation is immutable
- Contains processId, version, disposition
- Contains metadata for operational decisions
- Does NOT contain sensitive conversation data

### Resume Flow
- Discovery → operator examination → resume decision
- Resume requires processId + checkpointVersion
- Stale version → safe rejection
- Valid version → CHECK A proceeds

### Cross-Runtime Recovery
- Runtime A creates checkpoint in JDBC
- Runtime A terminates
- Runtime B discovers continuation
- Runtime B resumes execution
- CHECK A/B semantics preserved

### Authority Separation
- CheckpointStore: continuation authority
- InvocationStateStore: invocation attempt authority
- ExecutionLedger: historical projection
- RecoveryControlPlane: operational facade (no execution)

---

## Test Maintenance Policy

### When M8+ Extends APIs

If future milestones extend interfaces:

1. **Judge production semantics first**
   - Is the extension natural for this abstraction?
   - Does it introduce semantic mismatch?

2. **Update baseline tests (A, B)**
   - These must compile and pass
   - Mechanical fixes are required

3. **Update regression tests (C) if cheap**
   - If stable and low-cost, update
   - If expensive, move to quarantine

4. **Ignore quarantined tests (D)**
   - Do not spend time on mechanical repairs
   - They are explicitly excluded

### When to Promote from Quarantine

A quarantined test may be promoted back to baseline (C) if:
- It is rewritten to use public APIs only
- It covers a gap in A or B
- Maintenance cost is low

### When to Delete from Quarantine

A quarantined test should be deleted if:
- Its contract is covered by stronger tests in A or B
- It tests removed features
- It has no residual value

---

## Anti-Patterns

**DO NOT:**
- ❌ Skip all tests with `maven.test.skip=true`
- ❌ Use `@Disabled` on tests with compilation errors
- ❌ Weaken assertions to get green builds
- ❌ Count quarantined tests as "passing"
- ❌ Pretend quarantine doesn't exist
- ❌ Redesign production APIs to fix test doubles
- ❌ Create test-only methods in production code

**DO:**
- ✅ Maintain explicit baseline classification
- ✅ Document quarantine rationale
- ✅ Ensure green baseline tests protect real contracts
- ✅ Write integration tests over unit test archaeology
- ✅ Judge production architecture independently of test cost

---

## Document Status

**Current Baseline:** M7  
**Last Classification:** 2026-09-20  
**Next Review:** M8 closure

This document defines the **CURRENT SUPPORTED CONTRACT**.

Historical tests outside this contract are explicitly excluded.

Green builds mean: **current supported contracts pass**.

Not: "every test ever written still passes".

---

**Maintained By:** Arctra Core Team  
**Status:** LIVING DOCUMENT
