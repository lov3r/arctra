# M6-T4D SECTIONS W-AB

## W. InvocationStateStore Module Ownership

### W.1 Current Status

**Location**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/InvocationStateStore.java`

**Visibility**: Package-private interface

**Current Scope**: Runtime-react internal authority

### W.2 Cross-Module Pressure Analysis

**Question**: Has real cross-module pressure justified promotion?

**Potential Future Scenarios**:

1. **Second execution engine** (e.g., AgentScope integration)
   - Would need invocation-state authority
   - Pressure: REAL

2. **Separate JDBC persistence module** (e.g., `arctra-persistence-jdbc`)
   - Cannot implement package-private runtime-react interface from different module
   - Pressure: REAL

3. **Alternative backend** (e.g., Redis implementation)
   - Same problem as JDBC module
   - Pressure: REAL

### W.3 Current T4D Decision

**For M6-T4E (next slice — Persistent CheckpointStore)**:

**Keep Internal**: ✅ **YES**

**Reasoning**:
1. First persistent implementation can remain runtime-react internal
2. Contract still maturing (commit-unknown semantics, cleanup APIs)
3. Can promote when SECOND persistent implementation or SECOND engine actually materializes
4. Premature public SPI = frozen compatibility burden

**Implementation Strategy for T4E**:
```
arctra-runtime-react/
  └── src/main/java/cn/bitcss/arctra/runtime/react/
      ├── InvocationStateStore.java (package-private)
      ├── InMemoryInvocationStateStore.java
      └── JdbcInvocationStateStore.java (NEW, package-private)
```

### W.4 Future Promotion Triggers

**Promote when**:
- Second execution engine needs invocation-state authority
- Separate persistence module cannot access runtime-react package-private
- Third-party extension needs to implement persistent backend

**Do NOT promote merely because**:
- Persistence is "important"
- Interface looks "mature"
- Documentation is "complete"

**Classification for T4D**: **KEEP INTERNAL**

**Classification for T4E**: **KEEP INTERNAL**

**Future milestone**: **PROMOTE WHEN SECOND CONSUMER EXISTS**

---

## X. CheckpointStore Module Ownership

### X.1 Current Status

**Location**: `arctra-core/src/main/java/cn/bitcss/arctra/checkpoint/CheckpointStore.java`

**Visibility**: Public core API

**Implementations**:
- `InMemoryCheckpointStore` (core, reference)

### X.2 Persistent Implementation Ownership

**Question**: Where should `JdbcCheckpointStore` live?

**Options**:

**Option A**: `arctra-core`
```
arctra-core/
  └── cn.bitcss.arctra.checkpoint/
      ├── CheckpointStore.java
      ├── InMemoryCheckpointStore.java
      └── JdbcCheckpointStore.java (NEW)
```

**Problems**:
- ❌ JDBC dependencies in core (violates architecture)
- ❌ Core depends on javax.sql / Spring JDBC
- ❌ Bloats core for users not needing JDBC

**Option B**: `arctra-runtime-react`
```
arctra-runtime-react/
  └── cn.bitcss.arctra.runtime.react/
      └── JdbcCheckpointStore.java (NEW)
```

**Problems**:
- ⚠️ Persistence implementation in runtime-react feels misplaced
- ⚠️ Not easily reusable by future second engine

**Option C**: New module `arctra-persistence-jdbc`
```
arctra-persistence-jdbc/
  └── cn.bitcss.arctra.persistence.jdbc/
      ├── JdbcCheckpointStore.java
      └── JdbcInvocationStateStore.java
```

**Benefits**:
- ✅ Clean module boundary
- ✅ JDBC dependencies isolated
- ✅ Users opt-in via dependency
- ✅ Reusable across engines

**Problems**:
- ⚠️ Cannot implement package-private InvocationStateStore (cross-module)
- ⚠️ Forces InvocationStateStore promotion OR separate internal copy

### X.3 T4D Decision

**For M6-T4E**: **Option B** (JdbcCheckpointStore in runtime-react)

**Reasoning**:
1. Defer module creation until contract stabilizes
2. Keep first implementation close to consumer
3. Avoid forcing InvocationStateStore promotion prematurely
4. Can refactor to separate module later without public API break

**For future (when pressure arrives)**: **Option C** (separate persistence module)

**Trigger**: Second engine OR clear third-party integration need

**Classification**: **JUSTIFIED LATER** (not T4E blocker)

---

## Y. New Persistence Module Decision

### Y.1 Question

Should we create `arctra-persistence-jdbc` (or similar) NOW in T4D/T4E?

### Y.2 Analysis

**Arguments FOR**:
- Clean separation of concerns
- JDBC dependencies don't pollute core/runtime
- Third-party can implement persistence without core/runtime dependencies

**Arguments AGAINST**:
- Contract still maturing (commit-unknown, cleanup APIs)
- No second consumer yet (YAGNI)
- InvocationStateStore package-private creates cross-module problem
- Premature module = compatibility burden

### Y.3 Decision

**For T4E**: ❌ **DO NOT CREATE**

**Reasoning**:
1. First implementation validates contract in real usage
2. Single consumer (SpringAiToolCallingEngine)
3. Can refactor to module later without breaking public API
4. Avoid premature SPI stabilization

**Classification**: **JUSTIFIED LATER**

**Future trigger**: When second implementation consumer exists

---

## Z. First Backend Decision

### Z.1 Candidates

1. **JDBC** (PostgreSQL, MySQL, H2)
2. **Redis**
3. **Other** (MongoDB, filesystem, etc.)

### Z.2 JDBC Semantic Fit

**Requirements vs JDBC**:

| Requirement | JDBC Capability | Assessment |
|-------------|----------------|------------|
| Strong read-after-write | ✅ Transaction commit guarantees | ✅ Perfect fit |
| CAS semantics | ✅ UPDATE WHERE version = ? | ✅ Natural |
| Multi-node visibility | ✅ Database provides | ✅ Natural |
| Atomic operations | ✅ ACID transactions | ✅ Perfect fit |
| Idempotent writes | ✅ INSERT ... ON CONFLICT | ✅ Supported |
| Durability | ✅ Configurable (fsync) | ✅ Mature |
| Operational maturity | ✅ Enterprise standard | ✅ Excellent |
| Testability | ✅ H2 in-memory, Testcontainers | ✅ Excellent |

**Schema Example**:
```sql
CREATE TABLE checkpoints (
  process_id VARCHAR(255) PRIMARY KEY,
  checkpoint_version BIGINT NOT NULL,
  schema_version INT NOT NULL,
  runtime_binding_key VARCHAR(255) NOT NULL,
  session_id VARCHAR(255),
  checkpoint_data TEXT NOT NULL,  -- JSON
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE invocation_intents (
  process_id VARCHAR(255),
  operation_id VARCHAR(255),
  recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (process_id, operation_id)
);

CREATE INDEX idx_intents_process ON invocation_intents(process_id);
```

**Verdict**: ✅ **JDBC is semantically ideal first choice**

### Z.3 Redis Semantic Fit

**Requirements vs Redis**:

| Requirement | Redis Capability | Assessment |
|-------------|----------------|------------|
| Strong read-after-write | ✅ Single-threaded, immediate visibility | ✅ Perfect fit |
| CAS semantics | ✅ WATCH/MULTI or Lua scripts | ✅ Supported |
| Multi-node visibility | ⚠️ Requires primary reads (not async replicas) | ⚠️ Configuration critical |
| Atomic operations | ✅ Lua scripts atomic | ✅ Good |
| Idempotent writes | ✅ SET nx, Lua scripts | ✅ Supported |
| Durability | ⚠️ Configurable (AOF, RDB) | ⚠️ Requires tuning |
| TTL/Cleanup | ✅ Native EXPIRE | ✅ Better than JDBC |
| Operational maturity | ✅ Widely used | ✅ Good |
| Testability | ✅ Embedded Redis, Testcontainers | ✅ Good |

**Concerns**:
- Async replica reads could return stale false (MUST use primary or WAIT)
- Durability guarantees weaker than JDBC by default (requires AOF always)
- CAS via Lua scripts (less familiar to Java developers)

**Verdict**: ✅ **Redis is viable but requires careful configuration**

### Z.4 Decision

**First Reference Implementation**: **JDBC FIRST**

**Reasoning**:
1. ✅ Strongest consistency guarantees by default
2. ✅ Most familiar to enterprise Java teams
3. ✅ Natural CAS semantics (UPDATE WHERE version = ?)
4. ✅ Better testability (H2 in-memory)
5. ✅ Simpler mental model (ACID transactions)
6. ✅ Checkpoint serialization to JSON natural fit

**Redis**: **JUSTIFIED LATER** (second reference implementation)

**When**: After JDBC validates contract, if Redis-specific use cases emerge

---

## AA. JDBC Transaction Analysis

### AA.1 Shared DataSource Question

**Can CheckpointStore and InvocationStateStore share a DataSource without requiring every operation to share one transaction?**

**Answer**: ✅ **YES**

**Mechanism**:
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
void recordInvocationIntent(String processId, String operationId) {
  // Independent transaction
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
boolean replaceIfVersion(...) {
  // Independent transaction
}
```

Each method gets its own transaction. No cross-store coordination by default.

### AA.2 Transitions Requiring Shared Transaction

**From Section L**: ❌ **NONE**

All transitions safe with independent commits + conservative classification.

### AA.3 Future Coordinated Transaction

**IF** future semantics require it:
```java
@Transactional
void coordinatedOperation() {
  invocationStateStore.recordInvocationIntent(...);
  checkpointStore.replaceIfVersion(...);
  // Both in same transaction
}
```

**But**: Not needed for M6-T4E or foreseeable future.

---

## AB. Redis Atomicity Analysis

### AB.1 Consistency Model

Redis single-threaded → all operations atomic from client perspective.

### AB.2 CAS Implementation

**Checkpoint CAS**:
```lua
-- Lua script (atomic)
local current_version = redis.call("HGET", "checkpoint:" .. process_id, "version")
if current_version == expected_version then
  redis.call("HSET", "checkpoint:" .. process_id, "version", new_version)
  redis.call("HSET", "checkpoint:" .. process_id, "data", new_data)
  return 1
else
  return 0
end
```

**Intent Record**:
```lua
-- SADD is idempotent
redis.call("SADD", "intents:" .. process_id, operation_id)
```

### AB.3 Multi-Node Consistency

**Critical Requirement**: Recovery reads MUST target primary (not async replicas).

**Redis Configuration**:
```
# Write to primary
SET intents:proc-1:op-A "true"

# Read MUST be from primary OR use WAIT
GET intents:proc-1:op-A

# OR use WAIT to ensure replication
SET intents:proc-1:op-A "true"
WAIT 1 1000  # Wait for 1 replica, 1 second timeout
```

**Verdict**: ✅ **Redis CAN satisfy requirements with proper configuration**

---

