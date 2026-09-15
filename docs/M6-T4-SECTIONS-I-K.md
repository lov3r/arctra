## I. Pre-Call Durable Fact Requirement

### The Core Problem

**Current state**:
- Checkpoint: `op-A` pending
- Crash at T9 (delegate.call begins)
- After restart: Cannot distinguish "never invoked" from "may have invoked"

**Question**: Does Arctra need a durable fact BEFORE physical invocation?

**Answer**: YES ✅

### Semantic Requirement

**Durable fact needed**:
> Arctra has crossed the point after which physical execution may have reached the external system

**Proposed semantic** (not EventType name yet):
- **INVOCATION_INTENT** — Framework intends to invoke, may proceed
- **DISPATCH_INTENT** — Operation dispatched, may reach external system
- **EXECUTION_AUTHORIZED** — Execution gate passed, may begin

**Critical distinction**: This fact records INTENT, not actual entry

**Why**: Crash may occur after durable write but before delegate.call() entry. The fact means "may have started", not "definitely started".

---

## J. Domain Fact vs Durable Recovery State

### Two Categories of Facts

**Category 1: Domain Execution Facts** (M6-T2B)

**Definition**: Truth about what happened during execution

**Examples**:
- delegate.call() returned → TOOL_EXECUTED
- delegate.call() threw → TOOL_FAILED
- Human approved → APPROVAL_GRANTED

**Authority**: The domain event itself (happens regardless of recording)

**Ledger role**: Best-effort historical projection

**Tolerance**: Projection failure does NOT invalidate domain truth

---

**Category 2: Recovery-Critical Intent Facts** (M6-T4 NEW)

**Definition**: Durable state required to make safe recovery decisions

**Examples**:
- Physical invocation may have begun
- Execution gate passed
- Operation dispatched

**Authority**: Durable record existence (does not exist until persisted)

**Storage role**: Gate-keeping, not just observation

**Tolerance**: Persistence failure BLOCKS execution or creates uncertainty

---

## K. Checkpoint vs Ledger Authority

### Current Frozen Architecture

**CheckpointStore authority**:
- Current resumable process state
- Recovery generation (checkpointVersion)
- CAS-protected transitions
- Terminal state = checkpoint deleted

**ExecutionLedger authority**:
- Durable execution history
- Event sequence ordering
- Query by process
- Append-only, no deletion

**Separation**: Clean, orthogonal responsibilities

---

### M6-T4 Pressure

**New requirement**: Distinguish "never invoked" from "may have invoked"

**Candidate A: Extend Checkpoint State**

**Conceptual model**:
```java
PendingToolCall {
  String operationId;
  String toolCallId;
  String toolName;
  String arguments;
  // NEW:
  ExecutionState state;  // PENDING, IN_FLIGHT, ???
}
```

**Pros**:
- Checkpoint remains single recovery authority ✅
- CAS already protects transitions ✅
- Natural recovery model ✅

**Cons**:
- Checkpoint version increments per operation ⚠️
- Affects concurrent resume (see section M) ⚠️
- CHECK B more complex ⚠️

---

**Candidate B: Use Ledger as Recovery Input**

**Model**:
```
Load checkpoint v1 (op-A pending)
  ↓
Query ledger: Has INVOCATION_INTENT(op-A)?
  ↓
If YES: op-A may have started (uncertain)
If NO: op-A never started (safe to execute)
```

**Pros**:
- No checkpoint schema change ✅
- Ledger query API already exists ✅

**Cons**:
- Ledger becomes recovery driver ⚠️
- Changes ledger from observer to authority ⚠️
- Best-effort append becomes recovery-critical ⚠️

---

**Candidate C: Separate Invocation State Store**

**Pros**:
- Clean responsibility separation ✅
- Does not overload checkpoint or ledger ✅
- Operation-specific semantics ✅

**Cons**:
- New subsystem (complexity) ⚠️
- New persistence contract ⚠️
- Integration points ⚠️

---

### Recommendation

**Avoid Candidate B** (ledger as recovery authority)

**Rationale**:
- Violates ledger's observer contract
- Mixes history with recovery state
- Best-effort append becomes critical

**Evaluate Candidate A first** (checkpoint state)

**Rationale**:
- Preserves checkpoint as single recovery authority
- Existing CAS semantics applicable
- Concurrency concerns addressable

**Consider Candidate C** if Candidate A proves too complex
