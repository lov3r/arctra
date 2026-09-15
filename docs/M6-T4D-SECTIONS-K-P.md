# M6-T4D SECTIONS K-P

## K. Candidate Architecture Analysis

### K.1 Candidate A — Completely Independent Stores

**Architecture**:
```
CheckpointStore     → JDBC PostgreSQL (example)
InvocationStateStore → Redis (example)
```

No shared transaction. Independent commit boundaries.

**Crash Consistency Analysis**:

| Scenario | Checkpoint | Intent | Recovery Safe? |
|----------|-----------|--------|----------------|
| Intent write fails | Present | Absent | ✅ Execute (DEFINITELY_NOT_DISPATCHED) |
| Intent succeeds, checkpoint transitions later | Present v_N | Present | ⚠️ MAY_HAVE_INVOKED (fail closed) |
| Checkpoint deleted, intent orphaned | Absent | Present | ✅ No resumable checkpoint |
| Both committed, crash before CHECK B | Present v_N | Present | ⚠️ MAY_HAVE_INVOKED (fail closed) |

**Write Ordering**: Intent recorded BEFORE delegate.call() regardless of checkpoint state.

**Partial Availability**:
- If CheckpointStore unavailable → cannot load checkpoint → explicit recovery blocked (acceptable)
- If InvocationStateStore unavailable during recovery read → hasInvocationIntent() throws → fail closed (safe)
- If InvocationStateStore unavailable during normal execution → recordInvocationIntent() throws → delegate blocked (safe)

**Network Partitions**: Could create inconsistent visibility across nodes, but:
- Recovery always queries invocation-state authority with strong reads (requirement)
- Conservative classification preserves safety (false positives acceptable)

**Operational Complexity**: HIGH (two separate persistence technologies, independent monitoring, separate backup/recovery)

**Recovery Safety**: ✅ **SAFE** with conservative recovery classification and fail-closed reads

**Verdict**: **SAFE but operationally complex**

---

### K.2 Candidate B — Same Database, Independent Tables, No Cross-Store Transaction Contract

**Architecture**:
```
PostgreSQL:
  ├── checkpoint_table
  └── invocation_intent_table
  
Each store commits independently (no explicit coordinated transaction)
```

**Semantic Analysis**:

Physical colocation does NOT automatically provide cross-store atomicity unless transactions explicitly coordinated.

Independent `DataSource.getConnection()` calls → separate transactions.

**Crash Consistency**: Same as Candidate A (independent commits)

**Benefits over A**:
- Single database to operate
- Shared backup/recovery
- Potentially shared connection pool

**Critical Distinction**:
```
Same database ≠ Atomic cross-authority transaction
```

**Recovery Safety**: ✅ **SAFE** (same conservative classification requirements as A)

**Verdict**: **Operationally simpler than A, semantically equivalent**

---

### K.3 Candidate C — Shared Transactional Persistence Boundary

**Concept**:
```java
@Transactional
void recordIntentAndTransitionCheckpoint() {
  invocationStateStore.recordInvocationIntent(processId, operationId);
  checkpointStore.replaceIfVersion(processId, version, newCheckpoint);
  // Both commit atomically OR both rollback
}
```

**Question**: Which transitions actually need cross-store atomicity?

**Analysis of Each Transition**:

1. **Initial checkpoint create + (no intent yet)**
   - Intent recorded DURING execution, NOT at suspension
   - No atomicity requirement

2. **recordInvocationIntent + delegate.call()**
   - Cannot be generically atomic (external system independent)
   - Not applicable

3. **recordInvocationIntent + CHECK B delete**
   - Intent recorded during resumed execution
   - Checkpoint deleted AFTER completion
   - Temporal separation inherent
   - No atomicity requirement

4. **recordInvocationIntent + CHECK B replace**
   - Intent recorded for OLD pending operations
   - Checkpoint replaced with NEW pending operations
   - Different operation sets
   - No atomicity requirement

**Proof by Contradiction**:

**Hypothesis**: Independent commits create false-safe state.

**Test Case**: 
- Intent write succeeds
- Checkpoint transition fails
- Recovery classification: Checkpoint still contains op-A, intent(op-A) exists
- Classification: MAY_HAVE_INVOKED
- Action: Fail closed
- **Result**: Safe (false positive, not false negative)

**Reverse Test**:
- Checkpoint transition succeeds
- Intent write fails (impossible - intent recorded BEFORE transition attempt)

**Conclusion**: ❌ **Cross-store transaction NOT REQUIRED for correctness**

**Verdict**: **YAGNI** (would add distributed transaction complexity without correctness benefit)

---

### K.4 Candidate D — Shared Physical Substrate / Separate Semantic Authorities

**Architecture**:
```
PersistenceSubstrate (conceptual internal)
  ├── CheckpointStore authority (public API)
  └── InvocationStateStore authority (internal)
  
Physical implementation:
  - Single DataSource
  - Single TransactionManager
  - Shared schema/namespace
  - Separate tables with separate contracts
```

**Key Principle**: 
```
Shared physical substrate ≠ Merged semantic authority
```

**Implementation Options**:

**Option D1**: Independent transactions (default)
```java
class JdbcCheckpointStore implements CheckpointStore {
  @Transactional
  void create(SuspensionCheckpoint checkpoint) { ... }
}

class JdbcInvocationStateStore implements InvocationStateStore {
  @Transactional  
  void recordInvocationIntent(...) { ... }
}
```

**Option D2**: Explicit coordination when needed (future)
```java
class PersistenceCoordinator {
  @Transactional
  void coordinatedOperation() {
    // Only if future semantics require it
  }
}
```

**Benefits**:
- Single persistence configuration
- Clear authority separation preserved
- Option to add coordinated transactions IF needed later
- Simpler operational model than Candidate A
- Clearer than Candidate B (explicit substrate ownership)

**Enterprise Direction**: ✅ **Recommended**

**Verdict**: **Best long-term architecture**

---

### K.5 Candidate E — Conservative Independent Authorities + Reconciliation

**Concept**:
```
Stores commit independently
  + Strong authoritative reads
  + Conservative recovery classification
  + Fail-closed on ambiguity
  + Cleanup/reconciliation handles orphans
```

**Safety Analysis**:

**Q1: Can any partial state cause false DEFINITELY_NOT_DISPATCHED?**

| Checkpoint | Intent | Classification | False-Safe? |
|------------|--------|----------------|-------------|
| Present | Absent | DEFINITELY_NOT_DISPATCHED | ✅ Correct |
| Present | Present | MAY_HAVE_INVOKED | ✅ Safe (conservative) |
| Absent | Present | N/A (no checkpoint to recover) | ✅ Safe |
| Absent | Absent | N/A (no checkpoint to recover) | ✅ Safe |

**Answer**: ❌ **NO false-safe states reachable**

**Q2: Can intent loss ever occur after successful advertised commit?**

**Requirement**: If `recordInvocationIntent()` returns successfully, subsequent authoritative reads MUST observe intent (subject to backend consistency model).

**Analysis**: This is a **backend consistency requirement**, not framework architecture question.

**Backend Requirements**:
- Strong read-after-write consistency OR
- Session consistency with same connection OR
- Read from primary (not async replica)

**Framework cannot fix eventual-consistent backend returning stale false.**

**Q3: Can checkpoint existence + missing intent be trusted?**

**Scenario**: Checkpoint present, hasInvocationIntent() = false

**Meaning**: Gate never crossed (correct), OR backend read stale (dangerous)

**Safety Mechanism**: Backend MUST provide strong consistency for recovery reads (requirement, not optional)

**Q4: What happens if one backend is unavailable?**

- CheckpointStore unavailable → cannot load checkpoint → explicit recovery blocked (degraded availability, not safety violation)
- InvocationStateStore read unavailable → hasInvocationIntent() throws → fail closed (safe)
- InvocationStateStore write unavailable → recordInvocationIntent() throws → delegate blocked (safe)

**Q5: Does fail-closed read behavior cover enough cases?**

✅ **YES** — Unknown state (read failure) propagates as exception, NOT false classification.

**Verdict**: ✅ **SAFE** — This is the actual proposed architecture (combined with D)

---

### K.6 Candidate Comparison Matrix

| Dimension | A: Independent | B: Same DB Indep Tx | C: Cross-Store Tx | D: Shared Substrate | E: Conservative |
|-----------|----------------|---------------------|-------------------|---------------------|-----------------|
| **Authority clarity** | ✅ Clear | ✅ Clear | ⚠️ May blur | ✅ Clear | ✅ Clear |
| **Crash safety** | ✅ Safe (with E) | ✅ Safe (with E) | ✅ Safe | ✅ Safe (with E) | ✅ Safe |
| **False-safe risk** | ❌ None (with E) | ❌ None (with E) | ❌ None | ❌ None (with E) | ❌ None |
| **Restart recovery** | ✅ Enabled | ✅ Enabled | ✅ Enabled | ✅ Enabled | ✅ Enabled |
| **Multi-node path** | ✅ Clear | ✅ Clear | ⚠️ Distributed Tx | ✅ Clear | ✅ Clear |
| **Operational complexity** | ❌ HIGH (2 systems) | ✅ MEDIUM (1 system) | ❌ HIGH (XA) | ✅ LOW (1 substrate) | ✅ LOW |
| **Distributed transaction pressure** | ❌ None | ❌ None | ❌ HIGH | ❌ None | ❌ None |
| **Core impact** | Medium | Medium | High | Medium | Low (read semantics) |
| **Public API impact** | Medium | Medium | High | Medium | Low |
| **YAGNI compliance** | ✅ Good | ✅ Good | ❌ Over-engineered | ✅ Good | ✅ Excellent |
| **Enterprise direction** | ❌ Fragmented | ⚠️ Unclear ownership | ❌ Premature | ✅ **Recommended** | ✅ **Recommended** |

---

### K.7 Selected Architecture

**Primary**: **Candidate D + E Hybrid**

**Full Name**: Shared Physical Substrate with Separate Semantic Authorities, Conservative Independent Commits, Fail-Closed Recovery Reads

**Architecture**:
```
Persistent DurableRecoverySubstrate (internal concept, not public API)
  │
  ├── CheckpointStore authority (public core API)
  │   └── Persistent implementation (JDBC, future)
  │
  └── InvocationStateStore authority (package-private runtime-react)
      └── Persistent implementation (JDBC, future)

Physical:
  - Single DataSource
  - Single schema
  - checkpoint_table + invocation_intent_table
  - Independent transactions (default)
  - Strong read-after-write consistency required
  - Conservative recovery classification
  - Fail-closed on read uncertainty
```

**Why This Architecture**:

1. ✅ **Preserves authority separation** (CheckpointStore ≠ InvocationStateStore)
2. ✅ **No distributed transactions** (independent commits safe with conservative classification)
3. ✅ **Enterprise operational simplicity** (one database, one backup, one monitoring)
4. ✅ **Clear module ownership** (substrate can be internal implementation detail)
5. ✅ **Future flexibility** (can add coordinated transactions IF needed without public API change)
6. ✅ **Crash-safe** (proven by state matrix analysis)
7. ✅ **False-safe impossible** (proven by contradiction)

---

## L. Cross-Store Atomicity Decision

**Decision**: ❌ **NOT REQUIRED**

**Exact Transitions Analysis**:

| Transition | Requires Atomicity? | Justification |
|------------|---------------------|---------------|
| Initial checkpoint create | ❌ NO | Intent not yet recorded |
| recordInvocationIntent + delegate.call() | ❌ NO (impossible) | External system cannot be in framework transaction |
| recordInvocationIntent + CHECK B delete | ❌ NO | Temporal separation (intent during execution, delete after) |
| recordInvocationIntent + CHECK B replace | ❌ NO | Different operation sets, safe with conservative classification |

**Safety Proof**:

**Invariant**: No partial state can create false DEFINITELY_NOT_DISPATCHED.

**Proof by State Matrix**: (from Section G)
- All reachable states verified safe
- Uncertain states classified conservatively (MAY_HAVE_INVOKED)
- False positives acceptable (fail closed)
- False negatives impossible

**Why Independent Commits Are Safe**:

1. **Checkpoint already exists before intent** (created during initial suspension)
2. **Intent write is monotonic** (once present, remains present)
3. **If intent exists + checkpoint exists** → MAY_HAVE_INVOKED → fail closed (safe)
4. **If process completes + checkpoint deleted + intent orphaned** → no resumable checkpoint exists (safe)
5. **New suspension uses new operationIds** → old intents cannot contaminate new pending operations (safe)

**Conclusion**: Conservative recovery classification + strong reads = cross-store transactions unnecessary

---

## M. Intent Durability Contract

**Definition**: What does successful `recordInvocationIntent(...)` guarantee?

### M.1 Persistent Implementation Contract

```
After recordInvocationIntent(processId, operationId) returns successfully:

The invocation intent has crossed the implementation's advertised durable 
commit boundary and will be observable via hasInvocationIntent(processId, 
operationId) after process/JVM restart, subject to the backing store's 
documented consistency model.
```

**Does NOT guarantee**:
- Global consensus
- Multi-region durability  
- Linearizability (unless backend provides it)
- Exactly-once execution (framework remains at-least-once)

**DOES guarantee**:
- Durable within advertised backend boundary (e.g., JDBC transaction commit, Redis WAIT)
- Observable after JVM restart (with correct backend configuration)
- Read-after-write visibility (requirement for backend selection)

### M.2 In-Memory Implementation Contract

```
InMemoryInvocationStateStore:

Always succeeds (no actual persistence failure possible).
State lost on JVM restart (documented limitation).
Observable immediately (no consistency lag).
```

---

## N. Intent Read Consistency Contract

**Critical Requirement**: Recovery reads MUST NOT return stale false after acknowledged successful intent write.

### N.1 Required Consistency

**Minimum**: **Strong read-after-write consistency**

**Definition**: After `recordInvocationIntent()` returns successfully, subsequent `hasInvocationIntent()` calls from ANY process/node MUST observe the intent.

**Acceptable Backends**:
- JDBC with committed reads (default)
- Redis with primary reads (not async replicas without WAIT)
- Any system with strong consistency by default

**Unacceptable Backends**:
- Eventually consistent systems without read-after-write guarantee
- Async replica reads without synchronization
- Cached reads without invalidation

### N.2 Stale False Analysis

**Scenario**: 
```
Node A: recordInvocationIntent(proc-1, op-A) succeeds
Node A: crashes
Node B: loads checkpoint containing op-A
Node B: hasInvocationIntent(proc-1, op-A) → false (STALE READ)
Node B: classifies DEFINITELY_NOT_DISPATCHED
Node B: executes op-A again
```

**Result**: ❌ **UNSAFE** — False negative leads to duplicate execution interpretation

**Prevention**: Backend MUST provide read-after-write consistency for recovery reads.

### N.3 Session Consistency vs Strong Consistency

**Session Consistency**: Reads observe all writes from same session

**Sufficient?**: ⚠️ **NOT SUFFICIENT** for cross-node recovery

**Reason**: Recovery node is different session than write node.

**Requirement**: **Cross-session strong consistency** OR **read-from-primary** guarantee

---

## O. Checkpoint Durability Contract

### O.1 CheckpointStore CAS Semantics

**`create(checkpoint)`**:
- Stores new checkpoint at processId
- Idempotent within same version (re-create v1 succeeds if v1 exists)
- Throws CheckpointAlreadyExistsException if different version exists

**`replaceIfVersion(processId, expectedVersion, newCheckpoint)`**:
- Returns true: CAS succeeded, checkpoint now at newCheckpoint.checkpointVersion()
- Returns false: Version mismatch, checkpoint unchanged
- Must be globally meaningful across nodes

**`deleteIfVersion(processId, expectedVersion)`**:
- Returns true: CAS succeeded, checkpoint deleted
- Returns false: Version mismatch, checkpoint still exists
- Must be globally meaningful across nodes

### O.2 Persistent Implementation Requirements

**Linearizability**: NOT required for CheckpointStore

**Required Properties**:
1. **CAS correctness**: replaceIfVersion/deleteIfVersion version check atomic with mutation
2. **Read-after-write**: After create/replace succeeds, subsequent load() observes new checkpoint
3. **Multi-node visibility**: CAS from any node affects all nodes
4. **Failure atomicity**: Operation succeeds completely or fails completely (no partial updates)

**JDBC Example**:
```sql
UPDATE checkpoints 
SET checkpoint_data = ?, checkpoint_version = ? 
WHERE process_id = ? AND checkpoint_version = ?;

-- Returns affected row count
-- 0 → version mismatch (return false)
-- 1 → success (return true)
```

**Redis Example**:
```lua
-- Lua script for atomic CAS
if redis.call("GET", "checkpoint:" .. process_id .. ":version") == expected_version then
  redis.call("SET", "checkpoint:" .. process_id, new_data)
  redis.call("SET", "checkpoint:" .. process_id .. ":version", new_version)
  return 1
else
  return 0
end
```

---

## P. Checkpoint CAS Consistency Contract

### P.1 Multi-Node CAS Requirement

**Scenario**:
```
Node A: load checkpoint v1, execute, attemptdel CHECK B deleteIfVersion(proc-1, v1)
Node B: load checkpoint v1, execute, attempt CHECK B deleteIfVersion(proc-1, v1)
```

**Required Outcome**: Exactly ONE deleteIfVersion succeeds.

**Consistency Requirement**: CAS operations must be **atomic** and **authoritative** across all nodes.

**JDBC Implementation**: Natural (database transactions provide atomicity)

**Redis Implementation**: Lua scripts atomic, visible to all clients

**Eventual Consistency Systems**: ❌ **NOT SUITABLE** without strong consistency mode

### P.2 at-Least-Once Preservation

**Critical**: Persistence does NOT change at-least-once to exactly-once.

**Current Semantics** (frozen):
- Two nodes MAY concurrently load same checkpoint v1
- Two nodes MAY both execute same operation
- Two nodes MAY both attempt CHECK B
- Exactly ONE CHECK B succeeds (CAS)
- Other receives CheckpointTransitionConflictException

**Persistent CAS Semantics**:
- CAS prevents double checkpoint transition
- CAS does NOT prevent double execution before CHECK B

**Intent Uniqueness**:
```sql
CREATE TABLE invocation_intents (
  process_id VARCHAR(255),
  operation_id VARCHAR(255),
  recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (process_id, operation_id)
);
```

**INSERT behavior**:
```java
try {
  INSERT INTO invocation_intents (process_id, operation_id) VALUES (?, ?);
} catch (DuplicateKeyException e) {
  // Idempotent state write - another worker already recorded
  // This is NOT an error, NOT ownership conflict
  // Both workers may proceed to execute
}
```

**Semantic**: Duplicate key violation = idempotent state write success, NOT "another worker owns this operation"

---

