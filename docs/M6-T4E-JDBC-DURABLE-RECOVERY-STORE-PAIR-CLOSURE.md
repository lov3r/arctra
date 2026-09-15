# M6-T4E — JDBC DURABLE RECOVERY STORE PAIR IMPLEMENTATION CLOSURE

**Milestone**: M6-T4E  
**Status**: ✅ COMPLETE  
**Date**: 2026-09-15  
**Author**: lov3r

---

## Executive Summary

**Delivered**: JDBC-backed persistent checkpoint and invocation-state stores with restart-durable recovery substrate.

**Architecture**: Candidate D + E Hybrid + Candidate A construction boundary (as specified in M6-T4D/T4D.1).

**Core Achievement**: Framework now provides genuine JVM-restart-durable recovery fact persistence for approved tools, while preserving all existing ephemeral and in-memory execution modes.

**Public API Impact**: +1 public class (`JdbcCheckpointStore`)

**Core Impact**: ZERO

---

## A. Frozen Baseline

**M6-T4E Start**:
```
arctra-core:          205 tests
arctra-runtime-react: 159 tests
examples:              35 tests
TOTAL:                399 tests
Failures:               0
Errors:                 0
Skipped:               23
BUILD SUCCESS
```

---

## B. Architecture Implemented

### Selected Architecture

**Candidate D + E Hybrid** (M6-T4D):
- Shared JDBC physical substrate
- Separate semantic authorities (CheckpointStore ≠ InvocationStateStore)
- Independent transactions (NO cross-store atomic transaction)
- Strong read-after-write consistency
- Fail-closed recovery reads

**Candidate A Construction Boundary** (M6-T4D.1):
- Public JdbcCheckpointStore (application-instantiable)
- Package-private JdbcInvocationStateStore (internal)
- Engine instanceof detection for pairing
- Application owns DataSource

### Frozen Decisions Preserved

✅ Cross-store atomic transaction: **NOT REQUIRED**  
✅ CheckpointStore ≠ InvocationStateStore (distinct authorities)  
✅ at-least-once semantics: **UNCHANGED**  
✅ operationId uniqueness prevents intent contamination  
✅ Orphan intents safe (checkpoint deleted, intent remains)  
✅ Conservative recovery classification (fail-closed on unknown)

---

## C. Production Implementation

### New Production Files

**JdbcCheckpointStore.java** (177 lines, **PUBLIC**)
- Implements CheckpointStore
- Constructor: `JdbcCheckpointStore(DataSource)`
- Methods: create, load, replaceIfVersion, deleteIfVersion
- Package-private: `getDataSource()` for internal pairing
- Location: `arctra-runtime-react/src/main/java/.../runtime/react/`

**JdbcInvocationStateStore.java** (95 lines, **package-private**)
- Implements InvocationStateStore
- Constructor: `JdbcInvocationStateStore(DataSource)`
- Methods: recordInvocationIntent, hasInvocationIntent
- Idempotent intent write (duplicate key = success)
- Strong authoritative reads (SQL failure propagates)
- Location: `arctra-runtime-react/src/main/java/.../runtime/react/`

**CheckpointJsonCodec.java** (173 lines, **package-private**)
- Serializes SuspensionCheckpoint ↔ JSON
- Preserves all recovery-critical fields:
  - operationId (exact, no regeneration)
  - toolCallId (exact, provider protocol identity)
  - nullable sessionId
  - Evidence content
  - PendingToolCall order
- Thread-safe (stateless ObjectMapper)
- Location: `arctra-runtime-react/src/main/java/.../runtime/react/`

### Modified Production Files

**SpringAiToolCallingEngine.java**
- Added: `createMatchingInvocationStateStore(CheckpointStore)` (line ~247)
- Replaced hardcoded `new InMemoryInvocationStateStore()` with pairing logic
- Behavior:
  - `JdbcCheckpointStore` → `JdbcInvocationStateStore(same DataSource)`
  - `null` (ephemeral) → `InMemoryInvocationStateStore`
  - Unknown custom → `InMemoryInvocationStateStore` (safe fallback)
- **No constructor signature change**

**pom.xml** (arctra-runtime-react)
- Added: `spring-jdbc` (compile scope)
- Added: `jackson-databind` (compile scope)
- Added: `h2` (test scope)

### Schema

**jdbc-durable-recovery-schema.sql** (test resources)
```sql
CREATE TABLE arctra_checkpoints (
    process_id          VARCHAR(255) PRIMARY KEY,
    checkpoint_version  BIGINT NOT NULL,
    schema_version      VARCHAR(32) NOT NULL,
    runtime_binding_key VARCHAR(255) NOT NULL,
    session_id          VARCHAR(255),
    checkpoint_data     TEXT NOT NULL
);

CREATE TABLE arctra_invocation_intents (
    process_id   VARCHAR(255) NOT NULL,
    operation_id VARCHAR(255) NOT NULL,
    recorded_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (process_id, operation_id)
);
```

**Target**: PostgreSQL (production), H2 (tests)

---

## D. Dependency Delta

### Added Dependencies

| Dependency | Scope | Justification |
|------------|-------|---------------|
| `spring-jdbc` | compile | JdbcTemplate for SQL execution, transaction management |
| `jackson-databind` | compile | JSON serialization of SuspensionCheckpoint |
| `h2` | test | Embedded database for integration tests |

### Rationale

- **spring-jdbc**: Minimal JDBC abstraction, no ORM overhead
- **jackson-databind**: Robust JSON serialization, already widely used
- **h2**: Standard embedded DB for testing, PostgreSQL-compatible

**NOT Added**:
- ❌ JPA/Hibernate
- ❌ MyBatis
- ❌ R2DBC
- ❌ jOOQ
- ❌ Flyway/Liquibase (schema migration deferred to deployment)

---

## E. JDBC Schema

### Checkpoints Table

**Purpose**: Store serialized SuspensionCheckpoint

**Primary Key**: `process_id` (one checkpoint per process)

**Version Field**: `checkpoint_version` (CAS predicate)

**Payload**: `checkpoint_data` (JSON TEXT)

### Invocation Intents Table

**Purpose**: Record invocation intent before physical delegate.call()

**Primary Key**: `(process_id, operation_id)` (composite)

**Idempotency**: Duplicate INSERT = success (no error)

**recorded_at**: Operational metadata (NOT used in recovery semantics)

---

## F. JdbcCheckpointStore Semantics

### create(checkpoint)

- **Transaction**: Committed before return
- **Duplicate processId**: Throws `CheckpointAlreadyExistsException`
- **Durability**: Strong (cross-instance visible immediately)

### load(processId)

- **Consistency**: Strong authoritative read
- **Not found**: Returns `Optional.empty()`
- **SQL failure**: Propagates as exception (NOT empty)

### replaceIfVersion(processId, expectedVersion, replacement)

- **Atomic CAS**: Single UPDATE with version predicate
- **Success**: Returns `true`, rows updated = 1
- **Stale version**: Returns `false`, rows updated = 0
- **SQL exception**: Propagates (NOT interpreted as version conflict)

### deleteIfVersion(processId, expectedVersion)

- **Atomic CAS**: Single DELETE with version predicate
- **Success**: Returns `true`, rows deleted = 1
- **Stale version**: Returns `false`, rows deleted = 0
- **SQL exception**: Propagates

### Thread Safety

✅ Thread-safe (assuming DataSource is thread-safe)  
✅ No per-call shared mutable state  
✅ JdbcTemplate is thread-safe

---

## G. Checkpoint Serialization

### Implementation

**CheckpointJsonCodec** using Jackson ObjectMapper

### Preserved Fields

✅ `schemaVersion` (exact)  
✅ `processId` (exact)  
✅ `checkpointVersion` (exact long)  
✅ `runtimeBindingKey` (exact)  
✅ `sessionId` (nullable preserved)  
✅ `pendingBatch` (order preserved)  
✅ `accumulatedEvidences` (order preserved)

### PendingToolCall Preservation

✅ `operationId` (exact string, no regeneration)  
✅ `toolCallId` (exact string, provider protocol identity)  
✅ `toolName` (exact)  
✅ `arguments` (exact JSON string)

### Evidence Preservation

✅ `source` (exact)  
✅ `content` (exact, including multi-line)

### Error Handling

**Missing required field**: Throws `IllegalArgumentException`  
**Invalid JSON**: Throws `IllegalStateException`  
**Serialization failure**: Throws `IllegalStateException`

### Round-Trip Guarantee

```java
deserialize(serialize(checkpoint)) == checkpoint
```

Proven by 10 focused unit tests.

---

## H. Evidence Serialization

### Current Evidence Type

**Record**: `Evidence(String source, String content)`

**Characteristics**:
- Simple record (not polymorphic)
- Two non-null String fields
- No provider objects
- No arbitrary payloads

### Serialization Strategy

**Direct JSON**:
```json
{
  "source": "tool-name",
  "content": "result content"
}
```

**Complexity**: Low (no special handling required)

**Round-trip**: Proven lossless

---

## I. create() Semantics

### Behavior Match

**Matches `InMemoryCheckpointStore.create()`**:
- Duplicate processId → Exception
- Non-null validation
- Atomic insert

### Durability Contract

**Transaction commit** completes before return.

**Cross-instance visibility**: Immediate after successful return.

**No async replication**: Strong consistency.

---

## J. load() Semantics

### Strong Authoritative Read

**Data source**: Primary database (via supplied DataSource)

**No caching**: Direct DB query every time

**Not found**: `Optional.empty()`

**SQL failure**: Exception propagates (NOT interpreted as empty)

### Critical Distinction

```
empty = authoritative absence
exception = unknown / infrastructure failure
```

This distinction is **recovery-critical**.

---

## K. replaceIfVersion CAS Semantics

### Atomic Operation

**Single SQL UPDATE**:
```sql
UPDATE arctra_checkpoints
SET ...
WHERE process_id = ? AND checkpoint_version = ?
```

**Atomicity**: Database transaction isolation guarantees

**NO SELECT-then-UPDATE** race window

### Return Value Semantics

```
rows updated = 1 → true  (CAS success)
rows updated = 0 → false (version conflict)
SQLException     → propagate (infrastructure failure)
```

### CHECK B Correctness

This CAS implementation satisfies **CHECK B** (re-suspension) correctness requirements from M6-T4C:

- Concurrent resume attempts: at most one succeeds
- Version conflict detection: atomic
- No lost updates

### Multi-Instance Proof

✅ Tested with concurrent threads + independent store instances  
✅ Exactly one winner proven  
✅ Final checkpoint matches winner's version

---

## L. deleteIfVersion CAS Semantics

### Atomic Operation

**Single SQL DELETE**:
```sql
DELETE FROM arctra_checkpoints
WHERE process_id = ? AND checkpoint_version = ?
```

### Return Value Semantics

```
rows deleted = 1 → true  (CAS success)
rows deleted = 0 → false (version conflict)
SQLException     → propagate (infrastructure failure)
```

### Terminal State Semantics

Successful deletion = process reached terminal state (SUCCESS or explicit user abandonment).

Checkpoint deleted, intent may remain (orphan intent is safe).

---

## M. JdbcInvocationStateStore Semantics

### recordInvocationIntent(processId, operationId)

**Idempotent monotonic state write**:
- First write: INSERT succeeds
- Duplicate write: INSERT fails with duplicate key → **catch and treat as success**
- Final state: intent exists = success

**NOT claiming**: Multiple workers may record same intent

**NOT ownership**: No worker identity recorded

**NOT deduplication**: at-least-once execution preserved

### hasInvocationIntent(processId, operationId)

**Strong authoritative read**:
```sql
SELECT COUNT(*) FROM arctra_invocation_intents
WHERE process_id = ? AND operation_id = ?
```

**Return semantics**:
```
count > 0   → true  (intent exists)
count = 0   → false (intent absent)
SQLException → propagate (unknown ≠ absent)
```

**CRITICAL**: SQL failure MUST propagate, NOT return false.

False = "definitely not invoked" (safe to execute).  
Exception = "unknown" (classify as MAY_HAVE_INVOKED).

---

## N. Intent Idempotency

### Implementation

**Try INSERT, catch duplicate key**:
```java
try {
  jdbcTemplate.update("INSERT INTO ... VALUES (?, ?)", processId, operationId);
} catch (DuplicateKeyException e) {
  // Idempotent success - intent already exists
}
```

### Semantics

**Both workers succeed semantically** (intent exists).

**No claim conflict**: This is state write, not resource acquisition.

**at-least-once preserved**: Both may proceed to execute delegate.

### Concurrency Proof

✅ Tested with concurrent threads  
✅ Both writes return successfully  
✅ One row exists (database primary key enforcement)  
✅ No exception propagated to callers

---

## O. Intent Read Consistency

### Strong Read Requirement

**M6-T4D frozen requirement**: Recovery classification MUST observe committed intent writes.

**No stale false**: Cannot return `false` when intent was committed.

**Implementation**: Direct authoritative database query (no cache, no replica).

### SQL Failure Handling

**MUST NOT**:
```java
catch (SQLException e) {
  return false; // FORBIDDEN
}
```

**MUST**:
```java
// Let SQLException propagate
// Caller (InvocationRecoveryClassifier) interprets as unknown
```

### Recovery Classification Impact

```
hasIntent = true  → MAY_HAVE_INVOKED
hasIntent = false → DEFINITELY_NOT_DISPATCHED
hasIntent throws  → MAY_HAVE_INVOKED (conservative)
```

Fail-closed on uncertainty.

---

## P. Engine Pairing

### Implementation

**SpringAiToolCallingEngine.createMatchingInvocationStateStore()**:
```java
private InvocationStateStore createMatchingInvocationStateStore(
    CheckpointStore checkpointStore) {
  
  if (checkpointStore == null) {
    return new InMemoryInvocationStateStore();
  }
  
  if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
    return new JdbcInvocationStateStore(jdbcStore.getDataSource());
  }
  
  return new InMemoryInvocationStateStore();
}
```

### Pairing Rules

| CheckpointStore | InvocationStateStore | Restart-Durable Recovery |
|----------------|---------------------|--------------------------|
| `null` (ephemeral) | InMemory | ❌ No (JVM-local) |
| `InMemoryCheckpointStore` | InMemory | ❌ No (JVM-local) |
| `JdbcCheckpointStore` | JDBC (paired DataSource) | ✅ **YES** |
| Unknown custom | InMemory (fallback) | ❌ No (execution-compatible only) |

### Official JDBC Pairing Guarantee

**Restart-durable recovery substrate** ONLY for:
```
JdbcCheckpointStore
+ internally-paired JdbcInvocationStateStore
+ same DataSource
```

### Forbidden Configuration

**NEVER created by engine**:
```
JdbcCheckpointStore + InMemoryInvocationStateStore
```

This would be **false-safe** after restart.

---

## Q. DataSource Ownership

### Ownership Model

**Application owns**:
- DataSource configuration
- Connection pool
- JDBC URL / credentials
- Transaction manager (if needed)
- Connection lifecycle
- Health checks
- Schema creation/migration

**Framework responsibility**:
- Accept configured DataSource
- Use DataSource to create stores
- Execute SQL within transactions
- Release connections properly

### Application Construction

```java
// Application configures DataSource
DataSource dataSource = DataSourceBuilder.create()
    .url("jdbc:postgresql://localhost:5432/arctra")
    .username("arctra")
    .password(System.getenv("DB_PASSWORD"))
    .build();

// Pass to checkpoint store
CheckpointStore store = new JdbcCheckpointStore(dataSource);

// Engine uses existing constructor
SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, policy,
    store,  // ← JDBC checkpoint store
    bindingResolver, runtimeBindingKey, ledger
);
```

**No framework magic**: Application controls all JDBC configuration.

---

## R. Custom CheckpointStore Semantics

### Unknown/Custom Implementation Handling

**Example**:
```java
public class CustomPersistentCheckpointStore implements CheckpointStore {
  // Custom persistent backend
}
```

**Engine behavior**:
```java
CheckpointStore custom = new CustomPersistentCheckpointStore();
SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(..., custom, ...);

// Internally:
invocationStateStore = new InMemoryInvocationStateStore(); // fallback
```

### Resulting Configuration

```
Custom persistent checkpoint + InMemory intent
```

### Semantics

**Execution-compatible**: ✅ Yes (normal operation works)

**Restart-durable recovery**: ❌ **NO**

**Why unsafe after restart**:
- Checkpoint survives (persistent custom store)
- Intent lost (in-memory)
- Recovery reads: `hasInvocationIntent = false`
- Classification: `DEFINITELY_NOT_DISPATCHED`
- **FALSE-SAFE** if delegate was actually invoked

### Documentation Requirement

**User-facing documentation MUST state**:

> Custom CheckpointStore implementations are execution-compatible but do **NOT** provide restart-durable recovery guarantees unless explicitly paired with a matching persistent InvocationStateStore through a future configuration mechanism.
>
> For restart-durable recovery, use the official `JdbcCheckpointStore`.

**NOT documented as**: "safe persistent fallback"

---

## S. Restart-Durable Guarantee Boundary

### Official Guarantee

**Framework guarantees restart-durable recovery substrate** ONLY for:

✅ `JdbcCheckpointStore`  
✅ Internally-paired `JdbcInvocationStateStore`  
✅ Same DataSource  
✅ PostgreSQL/H2-compatible JDBC backend

### What This Means

**Checkpoint survives restart**: ✅  
**Intent survives restart**: ✅  
**Recovery classification correct after restart**: ✅  
**CAS operations atomic across nodes**: ✅

### What This Does NOT Mean

❌ **Automatic restart activation** (M6-T4F)  
❌ **executionEpoch detection** (M6-T4F)  
❌ **Recovery policy** (M6-T4G)  
❌ **Retry logic** (M6-T4G)  
❌ **Idempotency coordination** (future)  
❌ **External outcome query** (future)

**Distinction**:
```
Restart-durable recovery FACTS = M6-T4E ✅
Restart recovery ACTIVATION    = M6-T4F (deferred)
```

---

## T. Cross-Instance Persistence Tests

### Test Coverage

**JdbcCheckpointStoreTest**:
- ✅ Store A creates, Store B loads (restart simulation)
- ✅ Store A creates, Store B immediately reads (cross-instance visibility)
- ✅ Store A and B concurrent CAS (exactly one winner)

**JdbcInvocationStateStoreTest**:
- ✅ Store A records, Store B checks (restart simulation)
- ✅ Store A records, Store B immediately reads (cross-instance visibility)
- ✅ Store A and B concurrent duplicate writes (both succeed)

**JdbcDurableRecoveryPairTest**:
- ✅ Checkpoint + intent both survive restart
- ✅ Same DataSource pairing verified
- ✅ Cross-store read-after-write

### Proof Method

**Restart simulation**: Discard store instance, create new instance, verify persistence

**Cross-instance visibility**: Independent store instances + shared database

**Strong consistency**: Immediate reads after committed writes

---

## U. CAS Concurrency Tests

### replaceIfVersion Concurrent Test

**Setup**:
- Checkpoint v1 exists
- Two independent store instances (A and B)
- Both attempt: `replaceIfVersion(processId, 1, newVersion)`

**Execution**:
- Concurrent threads via `CountDownLatch`
- Real database contention

**Assertion**:
- ✅ Exactly one returns `true` (success)
- ✅ Exactly one returns `false` (version conflict)
- ✅ Final checkpoint version matches winner

**Result**: **PASSED** ✅

### deleteIfVersion Test

**Setup**: Checkpoint exists at version N

**Execution**:
- Attempt delete with wrong version → `false`
- Attempt delete with correct version → `true`
- Verify checkpoint gone

**Result**: **PASSED** ✅

---

## V. Intent Concurrency Tests

### Duplicate Intent Concurrent Test

**Setup**: Two independent store instances

**Execution**:
- Both record same `(processId, operationId)` concurrently
- Real database duplicate key contention

**Assertion**:
- ✅ Both calls return successfully (no exception)
- ✅ Success count = 2
- ✅ Exception count = 0
- ✅ One row exists in database

**Semantics**: Idempotent monotonic state write (NOT claiming)

**Result**: **PASSED** ✅

---

## W. Physical Invocation Gate Test

### Requirement (M6-T4A)

**Hard gate**: `recordInvocationIntent()` MUST commit successfully BEFORE `delegate.call()`.

### Test Evidence

**InvocationIntentGateTest** (existing, M6-T4A):
- Intent write failure blocks delegate execution
- Delegate invocation count = 0 when intent write fails
- Framework infrastructure failure ≠ tool execution failure

### JDBC Store Behavior

**JdbcInvocationStateStore.recordInvocationIntent()**:
- Uses JdbcTemplate (auto-commits transaction)
- Exception propagates immediately if INSERT fails
- Caller (ProtocolReconstructor) does not proceed to delegate.call()

### Ordering Guarantee

```
1. recordInvocationIntent(processId, operationId)
   → INSERT committed
2. Only if step 1 succeeds:
   → delegate.call(operationId, tool, args)
```

**Gate preserved**: ✅ JDBC stores maintain M6-T4A hard gate

---

## X. Recovery Classification Regression

### Existing Tests (M6-T4C)

**ExplicitRecoveryPathTest** (5 tests):
- ✅ All tests PASS with JDBC stores
- ✅ Recovery classification semantics unchanged
- ✅ `hasInvocationIntent` reads work correctly

**InvocationRecoveryClassifierTest** (5 tests):
- ✅ All tests PASS with JDBC stores
- ✅ Intent present → MAY_HAVE_INVOKED
- ✅ Intent absent → DEFINITELY_NOT_DISPATCHED
- ✅ Read failure → exception propagates

### Semantics Preservation

**M6-T4C recovery classification logic**: UNCHANGED

**Classification rules**: UNCHANGED

**at-least-once semantics**: UNCHANGED

**Only storage backend changed**: In-memory → JDBC (when configured)

---

## Y. Normal Resume Isolation

### Requirement (M6-T4C.1)

**Normal resume path** MUST NOT perform invocation-state reads.

**Only explicit recovery path** performs `hasInvocationIntent()` queries.

### Test Evidence

**T4C.1 Proof Test** (existing):
- Normal resume: `hasInvocationIntent` call count = 0
- Explicit recovery: `hasInvocationIntent` call count > 0

### JDBC Store Impact

**None**: Call-site behavior unchanged.

**ProtocolReconstructor**: Still only calls `recordInvocationIntent` on normal path.

**DurableResumeCoordinator**: Still only calls recovery classifier on explicit recovery path.

### Result

✅ Normal resume isolation PRESERVED

---

## Z. Commit-Unknown Handling

### Architecture Decision (M6-T4D)

**Commit-unknown = timeout/network failure during commit**:
- Transaction may have committed
- Transaction may have failed
- Outcome unknown

### M6-T4D Solution

**Conservative interpretation** + **authoritative re-read**:
1. SQL exception during write → propagate (do not interpret as success or failure)
2. Caller may re-read authoritative state
3. Classify conservatively (fail-closed on uncertainty)

### M6-T4E Implementation

**Checkpoint CAS operations**:
- `SQLException` → propagate (NOT interpreted as version conflict)
- Caller (DurableResumeCoordinator) can re-read checkpoint state
- Version mismatch detected via authoritative load

**Intent write**:
- `DuplicateKeyException` → catch and treat as success (idempotent)
- Other `SQLException` → propagate (infrastructure failure)
- Caller can re-query `hasInvocationIntent` for authoritative state

### Reconciliation Scope

**T4E does NOT add**:
- ❌ Automatic retry loops
- ❌ OperationOutcome enum
- ❌ UNKNOWN state
- ❌ Public reconciliation API

**T4E provides**:
- ✅ Authoritative re-read capability
- ✅ Exception propagation (commit-unknown surfaces)
- ✅ Conservative classification on uncertainty

**Reconciliation timing**: Caller responsibility (coordinator already has retry logic for CHECK B)

---

## AA. Core Impact

### arctra-core Changes

**ZERO**

**Unchanged**:
- CheckpointStore interface
- SuspensionCheckpoint record
- PendingToolCall record
- Evidence record
- No new public types
- No new exceptions
- No semantic changes

### Justification

JDBC implementation lives entirely in `arctra-runtime-react`.

Core contracts remain abstract and storage-agnostic.

---

## AB. Public API Delta

### New Public API

**JdbcCheckpointStore** (arctra-runtime-react):
- `public final class JdbcCheckpointStore implements CheckpointStore`
- `public JdbcCheckpointStore(DataSource dataSource)`
- Implements all CheckpointStore methods (already public contract)

### Unchanged Public API

❌ CheckpointStore interface (already existed)  
❌ SuspensionCheckpoint (already existed)  
❌ PendingToolCall (already existed)  
❌ SpringAiToolCallingEngine constructors (signatures unchanged)  
❌ InvocationStateStore (remains package-private)

### Total Public API Delta

**+1 public class**: `JdbcCheckpointStore`

**+0 public methods** (beyond CheckpointStore contract)

**+0 public exceptions**

**+0 core changes**

---

## AC. Constructor Delta

### SpringAiToolCallingEngine Constructors

**Before M6-T4E**:
```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger)
```

**After M6-T4E**:
```java
// IDENTICAL SIGNATURE
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,      // ← same parameter
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger)
```

### Application Code Impact

**Before**:
```java
new SpringAiToolCallingEngine(..., new InMemoryCheckpointStore(), ...)
```

**After (ephemeral)**:
```java
new SpringAiToolCallingEngine(..., new InMemoryCheckpointStore(), ...)
// Still works, unchanged
```

**After (persistent)**:
```java
new SpringAiToolCallingEngine(..., new JdbcCheckpointStore(dataSource), ...)
// New capability, same constructor
```

### Constructor Signature Delta

**ZERO**

---

## AD. InvocationStateStore Visibility

### Current Visibility

**InvocationStateStore** (interface): **package-private**

**InMemoryInvocationStateStore**: **package-private**

**JdbcInvocationStateStore**: **package-private**

### M6-T4E Change

**NONE**

### Justification

**No second consumer**: Only SpringAiToolCallingEngine uses InvocationStateStore.

**Contract still maturing**: Not ready for public SPI commitment.

**Internal pairing sufficient**: Engine instanceof detection works within same package.

**Future promotion trigger**: Second execution engine or independent persistence module.

---

## AE. Module Impact

### New Modules Created

**NONE**

### Module Ownership

**JdbcCheckpointStore**: `arctra-runtime-react`  
**JdbcInvocationStateStore**: `arctra-runtime-react`  
**CheckpointJsonCodec**: `arctra-runtime-react`

### Rationale

**arctra-core unchanged**: Persistence implementation not core concern.

**No arctra-persistence-jdbc**: YAGNI (no second implementation yet).

**Package-private InvocationStateStore**: Cannot be in separate module without promoting to public.

### Future Module Split Trigger

- Second persistent backend (Redis)
- Second execution engine consuming InvocationStateStore
- Third-party persistence SPI

---

## AF. PostgreSQL/H2 Compatibility

### Target Semantics

**Production**: PostgreSQL-compatible JDBC behavior

**Tests**: H2 embedded database

### SQL Portability

**Portable SQL used**:
- Standard INSERT/UPDATE/DELETE
- Standard WHERE predicates
- VARCHAR, BIGINT, TEXT, TIMESTAMP (standard types)

**Duplicate key handling**:
```java
try {
  jdbcTemplate.update("INSERT ...");
} catch (DuplicateKeyException e) {
  // Portable - Spring translates DB-specific exceptions
}
```

### Known Differences

**PostgreSQL `ON CONFLICT`**: NOT used (rely on Spring exception translation)

**PostgreSQL `JSONB`**: NOT used (use TEXT for portability)

**H2 limitations**: None encountered (standard SQL sufficient)

### Compatibility Status

✅ **H2 tests green** (all 46 new tests pass)  
⚠️ **PostgreSQL compatibility**: Source-reviewed, not integration-tested

**Recommendation**: Add Testcontainers PostgreSQL tests in future milestone.

---

## AG. Test Delta

### M6-T4C.1 Baseline

```
399 tests (baseline before T4E)
```

### M6-T4E New Tests

**CheckpointJsonCodecTest**: 10 tests
- Round-trip preservation
- Nullable sessionId
- operationId/toolCallId integrity
- Evidence preservation
- Multiple pending operations
- Missing field validation

**JdbcCheckpointStoreTest**: 13 tests
- Create/load
- Restart simulation
- Nullable sessionId
- CAS replace success/stale
- CAS delete success/stale
- Concurrent CAS (exactly one winner)
- operationId preservation
- Cross-instance visibility

**JdbcInvocationStateStoreTest**: 17 tests
- Record/check intent
- Intent absent
- Process/operation isolation
- Duplicate intent idempotency
- Concurrent duplicate writes
- Restart simulation
- Cross-instance visibility
- Validation (null/blank inputs)
- Multiple intents same process

**JdbcDurableRecoveryPairTest**: 6 tests
- Official JDBC pair restart
- Same DataSource pairing
- Cross-store read-after-write
- Checkpoint deletion leaves intent (orphan safe)
- Multiple operations partial intent
- InMemory checkpoint + JDBC intent (safe but useless)

### Test Delta Summary

```
M6-T4C.1 baseline:  399 tests
M6-T4E new tests:   +46 tests
Expected total:     445 tests
```

---

## AH. Focused Regression

### Core Test Suite (Fast)

**Run**:
```bash
./mvnw test -Dtest=DurableResumeExecutionTest,InvocationRecoveryClassifierTest,ExplicitRecoveryPathTest,CheckpointJsonCodecTest,JdbcCheckpointStoreTest,JdbcInvocationStateStoreTest,JdbcDurableRecoveryPairTest
```

**Result**:
```
Tests run: 67, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Time: ~3 seconds
```

**Coverage**:
- ✅ Checkpoint serialization
- ✅ JDBC checkpoint persistence
- ✅ JDBC intent persistence
- ✅ Store pair coherence
- ✅ Recovery classification
- ✅ Explicit recovery path

---

## AI. Full Regression

### Command

```bash
./mvnw clean test
```

### Result

```
[INFO] Reactor Summary:
[INFO] Arctra :: Parent ................................... SUCCESS
[INFO] Arctra :: API ...................................... SUCCESS
[INFO] Arctra :: Core ..................................... SUCCESS [205 tests ✅]
[INFO] Arctra :: Runtime :: ReAct ......................... SUCCESS [205 tests ✅]
[INFO] Arctra :: RAG ...................................... SUCCESS
[INFO] Arctra :: Tool ..................................... SUCCESS
[INFO] Arctra :: TestKit .................................. SUCCESS
[INFO] Arctra :: Spring Boot Starter ...................... SUCCESS
[INFO] Arctra :: Examples :: Knowledge Assistant .......... SUCCESS
[INFO] Arctra :: Examples :: Incident Investigator ........ SUCCESS [13 tests ✅]
[INFO] BUILD SUCCESS
```

### Interpretation

✅ **All existing tests PASS**  
✅ **No regressions**  
✅ **New tests integrated successfully**  
✅ **arctra-core unchanged** (205 tests baseline preserved)  
✅ **Examples unaffected**

### Test Count Reconciliation

**Note**: runtime-react reported count includes new M6-T4E tests (46) + existing tests.

**Baseline preserved**: 399 total tests before T4E  
**New tests added**: 46 (M6-T4E)  
**Expected**: ~445 total tests  
**Actual**: Full regression green ✅

---

## AJ. Structural Search

### Public Class Count

```bash
grep -r "^public .*class.*CheckpointStore" arctra-runtime-react/src/main/java
```

**Result**:
- `JdbcCheckpointStore`: ✅ 1 public class
- `InvocationStateStore`: ❌ 0 public (remains package-private)

### InvocationStateStore Visibility

```bash
grep "public interface InvocationStateStore" arctra-runtime-react/src/main/java
```

**Result**: No matches ✅ (still package-private)

### SpringAiToolCallingEngine Constructor Signatures

**Verified**: Constructor signatures unchanged (see Section AC)

### arctra-core JDBC Imports

```bash
grep -r "import.*jdbc" arctra-core/src/main/java
```

**Result**: 0 matches ✅

### Physical Invocation Ungated Sites

**Search**: All `delegate.call()` invocations must be preceded by `recordInvocationIntent()`

**Verified**: ProtocolReconstructor line 292 (existing gate preserved)

### hasInvocationIntent Normal Resume Reads

**Verified**: T4C.1 proof test confirms 0 reads on normal resume path

### Claim/Lease/Fencing Schema Fields

```bash
grep -E "claim|lease|fencing|owner|worker" jdbc-durable-recovery-schema.sql
```

**Result**: 0 matches ✅

### executionEpoch

```bash
grep -r "executionEpoch" arctra-runtime-react/src/main/java
```

**Result**: 0 new usages ✅ (deferred to M6-T4F)

### attemptId

```bash
grep -r "attemptId" arctra-runtime-react/src/main/java
```

**Result**: 0 new usages ✅ (deferred to M6-T4G)

---

## AK. Pairing Search

### Official JDBC Pairing Path

**SpringAiToolCallingEngine.createMatchingInvocationStateStore()**:
```java
if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
    return new JdbcInvocationStateStore(jdbcStore.getDataSource());
}
```

**Verified**: ✅ Only path creating JdbcInvocationStateStore

### Forbidden Configuration Search

**Search**: `JdbcCheckpointStore` + `InMemoryInvocationStateStore`

```bash
grep -A 5 "JdbcCheckpointStore" SpringAiToolCallingEngine.java | grep "InMemory"
```

**Result**: No code path creates this forbidden pairing ✅

**Engine logic**: instanceof check ensures JDBC → JDBC pairing

---

## AL. Deferred Work

### M6-T4F — Automatic Restart Activation

**Deferred**:
- Startup checkpoint scanning
- executionEpoch (JVM boot marker)
- Automatic recovery initiation
- RecoveryPlanner
- Stale checkpoint detection

### M6-T4G — Recovery Policy & Retry

**Deferred**:
- attemptId (physical attempt identity)
- RecoveryPolicy
- Retry logic
- Idempotency coordination
- External outcome query
- Receipt reconciliation

### Future Enhancements

**Deferred**:
- Redis implementation
- Separate persistence module
- Public InvocationStateStore SPI
- Builder/configuration object
- Intent TTL / cleanup API
- Checkpoint enumeration API
- Claim/lease/fencing (rejected)
- Worker ownership (rejected)

---

## AM. Closure Questions (60)

### Visibility & Ownership (1-10)

1. **Is JdbcCheckpointStore public?** ✅ YES
2. **Is JdbcInvocationStateStore package-private?** ✅ YES
3. **Is InvocationStateStore still package-private?** ✅ YES
4. **Does application own DataSource?** ✅ YES
5. **Does JdbcCheckpointStore accept DataSource publicly?** ✅ YES
6. **Is DataSource accessor non-public?** ✅ YES (package-private `getDataSource()`)
7. **Does Engine automatically pair JdbcCheckpointStore with JdbcInvocationStateStore?** ✅ YES
8. **Do both use the same DataSource?** ✅ YES
9. **Can official JDBC mode ever pair with InMemoryInvocationStateStore?** ❌ NO
10. **Are custom checkpoint stores still supported?** ✅ YES

### Guarantees & Contracts (11-20)

11. **Are custom checkpoint stores explicitly excluded from restart-durable recovery guarantee?** ✅ YES
12. **Is CheckpointStore interface unchanged?** ✅ YES
13. **Are engine constructor signatures unchanged?** ✅ YES
14. **Is core free of JDBC dependencies?** ✅ YES
15. **Is no new persistence module created?** ✅ YES
16. **Does checkpoint survive reconstruction of store instance?** ✅ YES
17. **Does intent survive reconstruction of store instance?** ✅ YES
18. **Is cross-instance visibility proven?** ✅ YES
19. **Is checkpoint serialization directly tested?** ✅ YES
20. **Are operationId and toolCallId preserved exactly?** ✅ YES

### Serialization & Data Integrity (21-30)

21. **Is nullable sessionId preserved?** ✅ YES
22. **Is Evidence round-trip proven?** ✅ YES
23. **Is replaceIfVersion a single atomic CAS operation?** ✅ YES
24. **Is deleteIfVersion a single atomic CAS operation?** ✅ YES
25. **Is concurrent CAS proven to have exactly one winner?** ✅ YES
26. **Is intent write idempotent?** ✅ YES
27. **Do concurrent duplicate intent writes both semantically succeed?** ✅ YES
28. **Does duplicate intent remain non-claiming?** ✅ YES
29. **Can hasInvocationIntent return false on SQL failure?** ❌ NO (propagates exception)
30. **Does SQL failure propagate as unknown?** ✅ YES

### Execution Semantics (31-40)

31. **Must intent commit occur before delegate invocation?** ✅ YES
32. **Is intent-write failure proven to block delegate?** ✅ YES
33. **Does intent persistence failure remain infrastructure failure rather than TOOL_FAILED?** ✅ YES
34. **Is normal resume still free from invocation-state reads?** ✅ YES
35. **Is explicit recovery semantics unchanged?** ✅ YES
36. **Is at-least-once execution unchanged?** ✅ YES
37. **Is cross-store transaction still absent?** ✅ YES
38. **Is transaction held across delegate.call?** ❌ NO
39. **Is claim absent?** ✅ YES
40. **Is lease absent?** ✅ YES

### Deferred Features (41-50)

41. **Is fencing absent?** ✅ YES
42. **Is worker ownership absent?** ✅ YES
43. **Is executionEpoch absent?** ✅ YES
44. **Is attemptId absent?** ✅ YES
45. **Is RecoveryPolicy absent?** ✅ YES
46. **Is retry absent?** ✅ YES
47. **Is automatic restart activation absent?** ✅ YES
48. **Is checkpoint enumeration absent?** ✅ YES
49. **Is invocation intent TTL absent?** ✅ YES
50. **Is intent cleanup API absent?** ✅ YES

### Regression & Completion (51-60)

51. **Is ExecutionLedger persistence unchanged?** ✅ YES
52. **Is ChatMemory persistence unchanged?** ✅ YES
53. **Is public API delta limited to JdbcCheckpointStore?** ✅ YES
54. **Is core API delta zero?** ✅ YES
55. **Is test count reconciled?** ✅ YES (46 new tests added)
56. **Is focused regression green?** ✅ YES (67 tests, 0 failures)
57. **Is full regression green?** ✅ YES (BUILD SUCCESS)
58. **Is restart-durable fact persistence now genuinely provided for official JDBC pair?** ✅ YES
59. **Is automatic restart recovery still explicitly NOT provided?** ✅ YES
60. **Is M6-T4E safe to close?** ✅ **YES**

---

## AN. Decision

### ✅ **FULL GO — CLOSE M6-T4E**

### Criteria Met

✅ JdbcCheckpointStore public and usable by application  
✅ JdbcInvocationStateStore internal  
✅ Official JDBC pair shares DataSource  
✅ Checkpoint persistent across instances  
✅ Intent persistent across instances  
✅ Strong authoritative intent reads  
✅ No stale-false conversion on failure  
✅ Atomic checkpoint CAS  
✅ CAS concurrency one-winner proof  
✅ Idempotent duplicate intent write  
✅ Write intent before delegate  
✅ Intent write failure blocks delegate  
✅ Checkpoint serialization complete  
✅ operationId preserved  
✅ Evidence recovery data preserved  
✅ Normal resume semantics unchanged  
✅ Explicit recovery semantics unchanged  
✅ at-least-once unchanged  
✅ No cross-store transaction  
✅ No public constructor change  
✅ Zero core JDBC impact  
✅ Full regression green

### Deliverables Complete

✅ JDBC persistent checkpoint store (public)  
✅ JDBC persistent invocation-state store (internal)  
✅ Engine automatic pairing  
✅ Checkpoint JSON serialization  
✅ Restart-durable recovery substrate  
✅ Comprehensive test suite (46 new tests)  
✅ SQL schema  
✅ Documentation

### Gates Passed

✅ M6-T4D Architecture Gate (Candidate D + E)  
✅ M6-T4D.1 Construction Boundary Gate (Candidate A)  
✅ All 60 closure questions answered YES (except forbidden behaviors)

---

## AO. HARD STOP

**M6-T4E — JDBC Durable Recovery Store Pair Implementation** is **COMPLETE**.

### DO NOT IMPLEMENT

❌ M6-T4F — Automatic restart activation  
❌ Startup scanning  
❌ executionEpoch  
❌ attemptId  
❌ RecoveryPlanner  
❌ RecoveryPolicy  
❌ Retry logic  
❌ Idempotency coordination  
❌ External outcome query  
❌ External receipt reconciliation  
❌ Operator workflow  
❌ Claim/lease/fencing  
❌ Worker ownership  
❌ Redis implementation  
❌ New persistence module  
❌ Public InvocationStateStore  
❌ Builder pattern  
❌ DurableExecutionConfiguration  
❌ ToolExecutionRuntime  
❌ Intent TTL  
❌ Intent cleanup API  
❌ Checkpoint enumeration API

### Awaiting

**Architecture review and M6-T4F implementation approval.**

---

**END M6-T4E JDBC DURABLE RECOVERY STORE PAIR IMPLEMENTATION CLOSURE**
