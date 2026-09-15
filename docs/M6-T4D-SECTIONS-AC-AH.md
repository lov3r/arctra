# M6-T4D SECTIONS AC-AH — FINAL SECTIONS

## AC. Multi-Node Semantics

### AC.1 Future Multi-Node Scenario

```
Node A: CHECK A load checkpoint v1
Node B: CHECK A load checkpoint v1
Node A: recordInvocationIntent(proc-1, op-A)
Node B: recordInvocationIntent(proc-1, op-A)
Node A: delegate.call(op-A)
Node B: delegate.call(op-A)
Node A: attempt CHECK B deleteIfVersion(proc-1, v1)
Node B: attempt CHECK B deleteIfVersion(proc-1, v1)
```

### AC.2 Expected Behavior

**Intent Recording**:
- Both nodes' `recordInvocationIntent()` succeed (idempotent state write)
- Both see intent exists after recording
- No ownership conflict

**Execution**:
- Both nodes may execute delegate.call() (at-least-once semantics preserved)
- Both produce TOOL_EXECUTED or TOOL_FAILED

**CHECK B**:
- Exactly ONE `deleteIfVersion()` succeeds (CAS)
- Other receives version mismatch → CheckpointTransitionConflictException

### AC.3 Persistent Store Requirements

**Read-after-write visibility**: 
- Node A writes intent → Node B must observe it (not stale false)

**CAS global authority**:
- Node A's CAS must be visible to Node B immediately

**JDBC**: ✅ Naturally provides (database transaction isolation)

**Redis**: ✅ Provides with primary reads (no async replica lag)

---

## AD. At-Least-Once Preservation

### AD.1 Frozen Semantics

**Current** (frozen in M6-T4):
```
at-least-once execution semantics
```

**Meaning**:
- Same logical operation MAY execute multiple times before CHECK B
- Framework does NOT guarantee exactly-once
- External idempotency required for exactly-once external effect

### AD.2 Persistence Does NOT Change This

**Persistent stores** do NOT automatically introduce:
- Single-worker execution
- Ownership claiming
- Execution deduplication

**Intent uniqueness** does NOT imply:
- "Another worker owns this" (on duplicate key)
- "I must not execute" (on existing intent)

**Intent semantics remain**:
```
Monotonic state write: "invocation gate crossed"
NOT: "I am the owner"
```

### AD.3 Duplicate Key Handling

**Database**:
```sql
INSERT INTO invocation_intents (process_id, operation_id) 
VALUES (?, ?)
ON CONFLICT (process_id, operation_id) DO NOTHING;
-- Success even if already exists (idempotent)
```

**Application**:
```java
void recordInvocationIntent(String processId, String operationId) {
  try {
    jdbc.update("INSERT INTO invocation_intents ...");
  } catch (DuplicateKeyException e) {
    // NOT an error - idempotent state write succeeded
    // Another worker already recorded - BOTH may execute
  }
}
```

**Critical**: Duplicate key ≠ ownership conflict

---

## AE. Unique Constraint Semantics

### AE.1 Database Constraint

```sql
PRIMARY KEY (process_id, operation_id)
```

**Purpose**: Ensure intent uniqueness per operation

**NOT Purpose**: Claim ownership, prevent concurrent execution

### AE.2 INSERT Semantics

**Option 1**: INSERT with exception handling
```java
try {
  INSERT INTO invocation_intents VALUES (?, ?);
  return true; // First to record
} catch (DuplicateKeyException e) {
  return true; // Already recorded (idempotent success)
}
```

**Option 2**: INSERT ... ON CONFLICT
```sql
INSERT INTO invocation_intents (process_id, operation_id)
VALUES (?, ?)
ON CONFLICT (process_id, operation_id) DO NOTHING;
-- Always succeeds (idempotent)
```

**Recommended**: **Option 2** (cleaner, no exception handling)

### AE.3 Idempotency Contract

**Framework contract**:
```
recordInvocationIntent() success means:
  "Intent now exists (either I recorded it, or it already existed)"

NOT:
  "I am the exclusive owner"
  "No other worker may execute"
```

---

## AF. Persistent Intent Write Failure

### AF.1 Deterministic Failure

**Scenario**: Validation failure, constraint violation, etc.

```java
recordInvocationIntent(processId, operationId);
// Throws InvocationIntentPersistenceException
```

**Behavior**:
- Exception propagates
- delegate.call() NOT invoked
- Checkpoint remains unchanged

**Recovery**: Safe (no intent recorded, operation still pending)

### AF.2 Timeout / Commit-Unknown

**Scenario**: Network timeout, database connection lost

```java
recordInvocationIntent(processId, operationId);
// Throws exception (timeout)
// Actual commit status: UNKNOWN
```

**Possible states**:
1. Write never reached database → No intent (safe)
2. Write committed, acknowledgement lost → Intent exists (safe false positive)

**Framework action**: Delegate NOT invoked (conservative)

**Recovery classification**:
- If intent actually present → MAY_HAVE_INVOKED (conservative)
- If intent actually absent → DEFINITELY_NOT_DISPATCHED (correct)

**Conclusion**: ✅ Timeout safe (fail closed)

---

## AG. Persistent Intent Read Failure

### AG.1 Read Failure Semantics

**Frozen** (from M6-T4C):
```
hasInvocationIntent() read failure
  → Exception propagates
  → Fail closed (do NOT classify as DEFINITELY_NOT_DISPATCHED)
```

**Implementation**:
```java
boolean hasInvocationIntent(String processId, String operationId) {
  try {
    return jdbc.queryForObject("SELECT COUNT(*) > 0 FROM invocation_intents WHERE ...");
  } catch (DataAccessException e) {
    // Do NOT return false (would mean "absent")
    // Propagate exception (unknown ≠ absent)
    throw new RuntimeException("Invocation state read failed - recovery cannot proceed safely", e);
  }
}
```

**Critical**: Unknown state must NOT become false classification.

---

## AH. Persistent Checkpoint Write Failure

### AH.1 Create Failure

**Deterministic failure**:
- Duplicate processId (idempotency check fails)
- Validation failure
- Throws exception

**Timeout**:
- Commit unknown
- Re-read checkpoint existence to reconcile

### AH.2 Replace CAS Failure

**Version mismatch** (normal):
```java
boolean replaced = replaceIfVersion(processId, expectedVersion, newCheckpoint);
// Returns false (version mismatch)
```

**Timeout** (commit-unknown):
- Re-read checkpoint to determine actual state
- If still at old version → retry
- If at new version → success (commit occurred)
- If at different version → concurrent modification

### AH.3 Delete CAS Failure

**Version mismatch** (normal):
```java
boolean deleted = deleteIfVersion(processId, expectedVersion);
// Returns false (version mismatch)
```

**Timeout** (commit-unknown):
- Re-read checkpoint existence
- If exists with same version → retry
- If not exists → success (delete occurred)
- If exists with different version → concurrent modification

---

## AI. Commit-Unknown Reconciliation

### AI.1 Reconciliation Strategy

**General Pattern**:
```
1. Operation times out (commit unknown)
2. Re-read authoritative state
3. Reconcile actual outcome:
   - Success → proceed as if returned success
   - Failure → retry or fail
   - Different state → handle concurrent modification
```

### AI.2 Intent Write Timeout

**Reconciliation**:
```java
try {
  recordInvocationIntent(processId, operationId);
} catch (TimeoutException e) {
  // Commit unknown - reconcile
  boolean actuallyPresent = hasInvocationIntent(processId, operationId);
  if (actuallyPresent) {
    // Commit succeeded despite timeout - proceed
  } else {
    // Commit failed - retry or fail
    throw new InvocationIntentPersistenceException("Intent recording failed", e);
  }
}
```

**Note**: Current InMemoryInvocationStateStore cannot timeout (always succeeds).

### AI.3 Checkpoint CAS Timeout

**Reconciliation**:
```java
try {
  boolean deleted = deleteIfVersion(processId, version);
  // If times out, deleted is not meaningful
} catch (TimeoutException e) {
  // Re-read actual state
  Optional<SuspensionCheckpoint> current = load(processId);
  if (current.isEmpty()) {
    // Delete succeeded
    return true;
  } else if (current.get().checkpointVersion() == version) {
    // Delete failed, checkpoint still at old version
    return false;
  } else {
    // Concurrent modification
    throw new CheckpointTransitionConflictException("Concurrent modification detected");
  }
}
```

---

## AJ. Transaction Necessity Test Results

### AJ.1 Proposed Cross-Store Transactions

**Analyzed in Section L**: Each potential transition tested

**Results**: ❌ **ZERO transitions require cross-store atomicity**

### AJ.2 Test Application

For each proposed transaction, ask:

> **"What false-safe or authority-corruption state does this transaction prevent?"**

**Answers**:

1. **Initial checkpoint create + intent**: Intent not recorded at suspension time (temporal separation)
2. **Intent record + delegate.call()**: Cannot be atomic (external system)
3. **Intent record + CHECK B delete**: Different operation sets, safe with conservative classification
4. **Intent record + CHECK B replace**: Different operation sets, safe with conservative classification

**Conclusion**: All motivations reduce to either:
- Operational convenience (not correctness)
- Misunderstanding of authority separation
- Premature optimization

**Verdict**: ✅ **Transaction necessity test passed (zero transactions needed)**

---

## AK. Persistence Substrate Authority

### AK.1 Allowed Ownership

If shared infrastructure selected, it may own:

**Infrastructure Lifecycle**:
- DataSource / connection pool management
- TransactionManager configuration
- Resource cleanup
- Health checks
- Metrics/observability

**Store Construction**:
- CheckpointStore instantiation
- InvocationStateStore instantiation
- Shared schema management
- Migration coordination

**Durability Configuration**:
- fsync policies
- Replication settings
- Consistency levels

### AK.2 Forbidden Ownership

Infrastructure must NOT own:

**Recovery Semantics**:
- ❌ Recovery classification logic
- ❌ Retry decisions
- ❌ External reconciliation
- ❌ Claiming / ownership
- ❌ Business outcome interpretation

**Domain Concerns**:
- ❌ Tool execution logic
- ❌ Checkpoint version progression
- ❌ Evidence interpretation
- ❌ Policy decisions

**Principle**: Infrastructure provides storage, NOT recovery policy.

---

## AL. Recovery Classification Consumer Status

### AL.1 Current Consumer

**M6-T4C Phase 1**: `DurableResumeCoordinator.resumeWithRecoveryClassification()`

**Activation**: Manual/explicit only (test or operator)

**NOT activated**:
- Normal concurrent resume (`DurableResumeCoordinator.resume()`)
- Initial execution
- Ephemeral resume

### AL.2 Persistence Does NOT Auto-Activate Recovery Mode

**Critical**:
```
Persistent store exists
  ≠ 
Automatically use explicit recovery classification
```

**Reason**: Automatic activation requires additional architecture:
- JVM restart detection (executionEpoch or equivalent)
- Lifecycle management
- Activation policy

**Classification**: **SEPARATE MILESTONE** (M6-T4F — Automatic Restart Activation)

---

## AM. Phase Ordering Recommendation

### AM.1 Completed Phases

```
✅ M6-T3A  — operationId foundation
✅ M6-T3B  — Direct per-operation execution  
✅ M6-T4   — Uncertain outcome architecture
✅ M6-T4A  — Invocation intent gate
✅ M6-T4B  — Recovery read visibility
✅ M6-T4C  — Explicit recovery classification
```

### AM.2 Recommended Next Phases

**M6-T4D** → **M6-T4E** → **M6-T4F** → **M6-T4G**

**M6-T4E**: Persistent CheckpointStore Implementation
- JDBC reference implementation
- JdbcCheckpointStore + JdbcInvocationStateStore
- Restart persistence capability
- Schema design
- Tests (restart, multi-instance, CAS concurrency)

**M6-T4F**: Automatic Restart Activation Architecture
- executionEpoch design
- Restart detection
- Explicit recovery activation
- Normal vs recovery resume distinction

**M6-T4G**: Retry / Recovery Policy Framework
- attemptId introduction
- Retry correlation
- Recovery policy SPI
- Operator intervention hooks

**M6-T5**: External System Cooperation
- Idempotency key support
- External receipt acknowledgement
- External outcome query

**Defer to separate track**:
- Claiming / lease / fencing (if ever needed)
- ToolExecutionRuntime subsystem
- Distributed transaction support (YAGNI)

---

## AN. executionEpoch Status

### AN.1 Required for T4D Persistence?

**Answer**: ❌ **NO**

**Reasoning**:
- (processId, operationId) sufficient for invocation-intent persistence
- Checkpoint already contains processId
- No epoch needed to store facts

### AN.2 Required for T4F Automatic Activation?

**Answer**: ✅ **LIKELY YES**

**Use Case**: Distinguish JVM restart from normal concurrent resume

**Future Design**:
```java
class ExecutionEpoch {
  String epochId;          // Per-JVM-boot identity
  Instant bootTimestamp;
}

// At JVM startup:
ExecutionEpoch currentEpoch = ExecutionEpoch.generate();

// In recovery:
if (checkpoint.executionEpoch() != currentEpoch) {
  // Different boot → use explicit recovery classification
  return resumeWithRecoveryClassification(...);
} else {
  // Same boot → normal concurrent resume
  return resume(...);
}
```

**Classification**: **JUSTIFIED NEXT FOR AUTOMATIC ACTIVATION** (M6-T4F)

---

## AO. attemptId Status

### AO.1 Required for T4D/T4E Persistence?

**Answer**: ❌ **NO**

**Reasoning**:
- Intent recording asks: "Has gate been crossed?" (binary)
- attemptId asks: "Which retry attempt?" (correlation)
- Different semantic questions

### AO.2 Required for Retry Correlation?

**Answer**: ✅ **YES WHEN RETRY EXISTS**

**Use Case**:
```
Attempt 1: intent recorded, uncertain outcome
Attempt 2: retry initiated
Attempt 3: retry initiated

Need to correlate:
- Which attempt produced which outcome?
- Which attempt reached external system?
```

**Future Schema**:
```sql
CREATE TABLE invocation_attempts (
  process_id VARCHAR(255),
  operation_id VARCHAR(255),
  attempt_id VARCHAR(255),
  recorded_at TIMESTAMP,
  PRIMARY KEY (process_id, operation_id, attempt_id)
);
```

**Classification**: **JUSTIFIED NEXT FOR RETRY MILESTONE** (M6-T4G)

---

## AP. Claim / Lease / Fencing Status

### AP.1 Required for Persistence?

**Answer**: ❌ **NO**

**Reasoning**:
- Persistence enables multi-node durability
- Multi-node ≠ requires single-worker execution
- at-least-once semantics frozen (no change needed)

### AP.2 Ever Required?

**Answer**: ⚠️ **MAYBE NEVER**

**Scenarios where claiming MIGHT be useful**:
1. Expensive tool execution (avoid duplicate expensive work)
2. External system cannot handle duplicates
3. Resource constraints (thread pool exhaustion)

**But**:
- #1: Optimization, not correctness
- #2: External idempotency key better solution
- #3: Throttling/backpressure, not claiming

**Alternative Architecture**: Remain at-least-once, require external cooperation for exactly-once effects

**Classification**: **REJECT FOR FORESEEABLE FUTURE** (not needed, adds complexity)

---

