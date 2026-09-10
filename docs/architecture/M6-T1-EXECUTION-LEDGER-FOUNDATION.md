# M6-T1 — ExecutionRecord + ExecutionLedger Foundation

**Status:** ✅ COMPLETE  
**Date:** 2026-09-10  
**Track:** B — M6 General Durable Execution Foundation  
**Phase:** Foundation (NO Production Wiring)

---

## 1. MISSION

Establish the **durable execution history authority** for Arctra.

ExecutionLedger provides append-only audit trail of process execution events. This is **foundation only** - no production wiring to runtime/engine/governance in M6-T1.

---

## 2. WHY EXECUTIONLEDGER EXISTS

### Four Different Authorities

Arctra durable execution uses **four separate authorities**:

| Authority | Scope | Persistence |
|-----------|-------|-------------|
| **Checkpoint** | Current recovery state | CheckpointStore |
| **ExecutionLedger** | Historical execution facts | ExecutionLedger |
| **Evidence** | Execution proof/content | (future EvidenceStore) |
| **ChatMemory** | Conversation history | ChatMemory |

**Critical:** These are NOT redundant. Each has different responsibility.

---

### Checkpoint vs Ledger

**Checkpoint = Recovery State Authority**
- Answers: "What can be resumed RIGHT NOW?"
- Mutable: checkpoint v1 → v2 → v3 → deleted
- Recovery correctness depends on Checkpoint

**ExecutionLedger = Historical Fact Authority**
- Answers: "What HAPPENED during execution?"
- Immutable: records are append-only
- Audit/query/diagnosis authority

**M6-T1 does NOT change recovery algorithm.**  
Recovery still uses Checkpoint. Ledger provides audit trail.

---

### Arctra is NOT Event-Sourced in M6-T1

**Forbidden pattern:**
```java
// ❌ DO NOT DO THIS
List<ExecutionRecord> history = ledger.queryByProcess(processId);
State state = reconstructStateFromHistory(history);
resume(state);
```

**Correct pattern:**
```java
// ✅ Checkpoint remains recovery authority
Checkpoint checkpoint = store.load(processId);
RuntimeBinding binding = resolver.resolve(...);
resume(binding, checkpoint);
```

ExecutionLedger is for **audit/query/diagnosis**, not state reconstruction.

---

## 3. PUBLIC API

### 3.1 EventType (11 values)

```java
public enum EventType {
  PROCESS_STARTED,
  
  APPROVAL_REQUIRED,
  APPROVAL_GRANTED,
  APPROVAL_REJECTED,
  
  SUSPENDED,
  RESUMED,
  
  TOOL_EXECUTED,
  TOOL_FAILED,
  
  CHECKPOINT_CONFLICT,
  
  COMPLETED,
  FAILED
}
```

**Taxonomy Status:** Provisional / Additive  
**NOT frozen** - will evolve with future durable execution capabilities.

---

### 3.2 ExecutionRecord (7 fields)

```java
public record ExecutionRecord(
    String recordId,           // Derived: processId:sequence
    String processId,          // Process identity
    long sequence,             // Ledger ordering authority
    EventType eventType,       // Event classification
    Instant occurredAt,        // Timestamp (UTC)
    Long checkpointVersion,    // Nullable correlation
    String payload             // Nullable JSON string
)
```

**Canonical Identity:** `(processId, sequence)` composite key  
**recordId:** Derived string representation for external references

---

### 3.3 ExecutionLedger (3 methods)

```java
public interface ExecutionLedger {
  ExecutionRecord append(
      String processId,
      EventType eventType,
      Long checkpointVersion,
      String payload
  );
  
  List<ExecutionRecord> queryByProcess(String processId);
  
  List<ExecutionRecord> queryRecentByProcess(String processId, int limit);
}
```

**Design Decision:** `queryByEventType()` removed - no current framework need for cross-process analytics.

---

## 4. SEQUENCE SEMANTICS

### Guaranteed Properties (MUST)

Sequence numbers **MUST**:
- Be positive (>= 1)
- Be unique within processId
- Be strictly monotonically increasing according to ledger allocation order
- Be ledger-assigned (not caller-assigned)

---

### Explicitly NOT Guaranteed (MUST NOT assume)

Sequence numbers **MUST NOT** be assumed to:
- Be gapless/contiguous (gaps may occur)
- Encode wall-clock invocation order
- Encode thread scheduling order
- Have sequence N+1 after sequence N

**Valid sequences:** `1, 2, 4, 5, 7, 8` (gaps at 3, 6)

---

### Why Allow Gaps?

1. **JDBC sequence allocation** — Transaction rollback loses allocated sequence
2. **Failed append** — Allocation ≠ successful persistence
3. **Distributed allocation** — Pre-allocated ranges may have unused values
4. **Concurrency** — Allocation order ≠ persistence order

---

### Timestamp vs Sequence

| Field | Authority | Purpose |
|-------|-----------|---------|
| `occurredAt` | Observed event time | May be out-of-order (clock skew) |
| `sequence` | Ledger ordering | Total order within process |

**If conflict:** Sequence wins for historical ordering.

---

## 5. APPEND-ONLY SEMANTICS

**ExecutionLedger is strictly append-only.**

**Forbidden:**
```java
// ❌ No update/delete/replace methods
ledger.updateRecord(recordId, newPayload);
ledger.deleteRecord(recordId);
ledger.modifyHistoricalRecord(...);
```

**Allowed:**
```java
// ✅ Only append
ledger.append(processId, eventType, checkpointVersion, payload);
```

---

### Handling High-Frequency Events

**Problem:** RESUME_PREPARATION_FAILED occurs 100 times - how to record?

**Solution:** Append each occurrence individually
```java
// Each failure is independent record
ledger.append(processId, RESUME_PREPARATION_FAILED, ...);  // 1st
ledger.append(processId, RESUME_PREPARATION_FAILED, ...);  // 2nd
// ...
ledger.append(processId, RESUME_PREPARATION_FAILED, ...);  // 100th

// Query: COUNT WHERE eventType = RESUME_PREPARATION_FAILED
```

**Aggregation:** Use metrics/materialized views, not ledger mutation.

---

## 6. SYNCHRONOUS VISIBILITY

**M6-T1 Contract:** A successful `append()` means the record is **immediately queryable** from the same ledger instance.

```java
ExecutionRecord appended = ledger.append("P1", TOOL_EXECUTED, null, null);

// ✅ Must be queryable immediately
List<ExecutionRecord> records = ledger.queryByProcess("P1");
assertThat(records).contains(appended);
```

**Does NOT guarantee:**
- fsync / replicated durable commit (storage implementation concern)
- Cross-instance visibility (distributed implementation concern)

---

## 7. CORRELATION DESIGN

### checkpointVersion (only correlation field in M6-T1)

**Direct field** (no ExecutionCorrelation record):
```java
public record ExecutionRecord(
    ...
    Long checkpointVersion,  // Nullable
    ...
)
```

**Rationale:** Only 1 correlation field TODAY - avoid speculative abstraction.

---

### Deferred Correlation Fields

**NOT in M6-T1:**
- `evidenceId` — EvidenceStore doesn't exist yet
- `runtimeId` — No framework contract for runtime identity
- `traceId` / `spanId` — OpenTelemetry adapter doesn't exist

**Future:** Add additively when concrete framework responsibility exists.

---

## 8. OBSERVABILITY BOUNDARY

**ExecutionRecord ≠ Log ≠ Trace**

### Durable Plane vs Observability Plane

| Plane | Purpose | Components |
|-------|---------|------------|
| **Durable** | Correctness / Audit / Recovery | Checkpoint, ExecutionRecord, Evidence, ChatMemory |
| **Observability** | Debugging / Performance | Logs, Metrics, Traces |

---

### One Event, Multiple Projections

**Example: APPROVAL_GRANTED**

```java
// 1. ExecutionRecord (durable audit)
ledger.append(processId, APPROVAL_GRANTED, checkpointVersion, payload);

// 2. Application Log (diagnostic)
logger.info("Approval granted processId={} by user={}", processId, userId);

// 3. Trace Span (timing)
span.addEvent("approval_granted");

// 4. Metric (counter)
approvalGrantedCounter.increment();
```

**Key:** Deleting logs doesn't affect recovery. Trace unavailability doesn't block checkpoint correctness.

---

## 9. INMEMORY REFERENCE IMPLEMENTATION

`InMemoryExecutionLedger`:
- Thread-safe (AtomicLong + ConcurrentHashMap)
- Per-process atomic sequence allocation
- Append-only
- JVM-local only (NOT for distributed deployment)

**Implementation Note:** Happens to produce gapless sequences (1, 2, 3, ...) due to `AtomicLong.incrementAndGet()`. This is implementation detail, NOT contract guarantee.

---

## 10. M5 COMPATIBILITY

**Zero M5 modifications:**

| M5 Component | M6-T1 Impact |
|--------------|--------------|
| AgentRuntime | ❌ None |
| DurableExecutionEngine | ❌ None |
| RuntimeBindingResolver | ❌ None |
| CheckpointStore | ❌ None |
| SuspensionCheckpoint | ❌ None |
| Evidence | ❌ None |
| AgentResult | ❌ None |
| SpringAiToolCallingEngine | ❌ None |

**M6-T1 is pure additive foundation.**

---

## 11. KNOWN LIMITATIONS

### 11.1 No Production Wiring

M6-T1 **does NOT** wire ExecutionLedger into:
- SpringAiToolCallingEngine (no TOOL_EXECUTED recording)
- DefaultAgentProcess (no PROCESS_STARTED recording)
- AgentRuntime (no SUSPENDED/RESUMED recording)
- CheckpointStore (no CHECKPOINT_CONFLICT recording)

**Future:** M6-T2+ will implement selective wiring.

---

### 11.2 No EvidenceStore

Evidence still lives in `SuspensionCheckpoint.accumulatedEvidences` (M5 write amplification problem).

**Future:** M6-T2 EvidenceStore migration after Track A/B coordination.

---

### 11.3 No Observability Integration

M6-T1 **does NOT** provide:
- OpenTelemetry adapter
- Logging integration
- Metrics integration
- `traceId`/`spanId` propagation

**Future:** M6+ observability adapters.

---

### 11.4 No Recovery Verification

ExecutionLedger is audit-only in M6-T1.

**Future:** Recovery verification using ledger history (e.g., "Did checkpoint v3 successfully resume?").

---

### 11.5 InMemory Only

`InMemoryExecutionLedger` is JVM-local reference implementation.

**Future:** JDBC/Redis persistent implementations.

---

## 12. FUTURE DIRECTIONS (明确延期)

**NOT in M6-T1:**
- TurnCheckpoint / ToolStepCheckpoint
- operationId execution semantics
- Uncertain outcome handling
- RecoveryPlanner
- Automatic RetryPolicy
- Lease/Fencing
- Exactly-once guarantees
- OpenTelemetry adapter
- EvidenceStore
- Production ledger wiring
- JDBC/Redis implementations

---

## 13. SUMMARY

**What M6-T1 provides:**
- ✅ EventType enum (11 provisional types)
- ✅ ExecutionRecord (7-field immutable record)
- ✅ ExecutionLedger interface (3 methods)
- ✅ InMemoryExecutionLedger (reference implementation)
- ✅ Sequence allocation (atomic, unique, monotonic, gap-tolerant)
- ✅ Append-only semantics
- ✅ Synchronous visibility guarantee
- ✅ Zero M5 modification
- ✅ 37 new tests (concurrency-validated)

**What M6-T1 does NOT provide:**
- ❌ Production wiring
- ❌ EvidenceStore
- ❌ Observability integration
- ❌ Recovery verification
- ❌ Persistent ledger implementations

**Authority Model:**
- Checkpoint = current recovery state
- ExecutionLedger = historical fact
- Evidence = execution proof
- ChatMemory = conversation history

**Arctra is NOT event-sourced in M6-T1.**

---

**M6-T1 COMPLETE — 2026-09-10**
