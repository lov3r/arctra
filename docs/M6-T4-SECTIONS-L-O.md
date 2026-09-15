## L. Persistence Reality

### Current Implementation Audit

**CheckpointStore implementations**:
- `InMemoryCheckpointStore` — Single JVM, ConcurrentHashMap-based
- **NO** file/database implementations

**ExecutionLedger implementations**:
- `InMemoryExecutionLedger` — Single JVM, ConcurrentHashMap + AtomicLong
- **NO** persistent implementations

### Critical Finding

**Current system CANNOT survive JVM restart** ❌

**Evidence**:
1. **InMemoryCheckpointStore.java:17**
   > "NOT for distributed deployment - state is JVM-local only."

2. **InMemoryExecutionLedger.java:16**
   > "NOT for distributed deployment - state is JVM-local only. For production shared storage, use JDBC or other persistent implementations."

**Reality check**:
- Checkpoint data: Lost on JVM exit
- Ledger history: Lost on JVM exit
- No cross-process recovery possible
- No node failover possible

### M6-T4 Scope Classification

**M6-T4 is SEMANTIC FOUNDATION**, not deployable crash recovery ✅

**What M6-T4 defines**:
- Uncertain outcome semantics
- Recovery state requirements
- Durable fact contracts
- Authority boundaries

**What M6-T4 does NOT provide**:
- Persistent CheckpointStore implementation ❌
- Persistent ExecutionLedger implementation ❌
- Cross-JVM recovery runtime ❌
- Distributed coordination ❌

### Persistence Requirements (Future)

**For real crash recovery, implementations needed**:

**Persistent CheckpointStore**:
- JDBC/PostgreSQL implementation
- Redis implementation
- Distributed KV store (etcd, Consul)

**Persistent ExecutionLedger**:
- JDBC/PostgreSQL implementation
- Append-only log (Kafka, EventStore)
- Time-series database

**Out of M6-T4 scope** — Deferred to implementation milestones

---

## M. Concurrency Interaction

### Current At-Least-Once Semantics (Frozen)

**M5 established**:
```
Worker A loads checkpoint v1
Worker B loads checkpoint v1
Both execute op-A
One CHECK B wins (CAS)
Loser gets CHECKPOINT_CONFLICT
```

**Result**: op-A may execute twice (at-least-once) ✅

**This is frozen and correct** — No single-execution guarantee

---

### Candidate A: Checkpoint PENDING → IN_FLIGHT Transition

**Proposed model**:
```
Worker A: Load checkpoint v1 (op-A PENDING)
Worker A: CAS transition op-A to IN_FLIGHT (checkpoint v2)
Worker A: Execute delegate.call()
```

**Question**: What happens to concurrent resume?

---

**Scenario 1: Pre-transition conflict**

```
Worker A: Load checkpoint v1 (op-A PENDING)
Worker B: Load checkpoint v1 (op-A PENDING)
Worker A: CAS v1 → v2 (op-A IN_FLIGHT) → SUCCESS
Worker B: CAS v1 → v2 (op-A IN_FLIGHT) → CONFLICT (v1 no longer exists)
```

**Result**: 
- Worker A proceeds ✅
- Worker B detects conflict before execution ✅
- op-A executes once ✅

**This ACCIDENTALLY introduces single-worker ownership** ⚠️

---

**Scenario 2: Post-transition crash**

```
Worker A: CAS v1 → v2 (op-A IN_FLIGHT) → SUCCESS
Worker A: Crashes before delegate.call()
Worker B: Loads checkpoint v2 (op-A IN_FLIGHT)
```

**Question**: What does Worker B do?

**Option 1**: Execute anyway (op-A IN_FLIGHT means "uncertain")
- Result: May execute twice (at-least-once preserved)
- Problem: IN_FLIGHT doesn't distinguish "may have started" from "definitely not started"

**Option 2**: Wait/skip (assume Worker A owns it)
- Result: Deadlock if Worker A never recovers
- Problem: Introduces worker ownership/lease semantics

**Option 3**: Timeout-based claim
- Result: Complex lease/fencing logic
- Problem: Distributed coordination

---

**Implication**: PENDING → IN_FLIGHT transition introduces implicit claiming ⚠️

**Consequences**:
- Concurrent resume behavior changes
- May need lease/timeout/fencing
- Single-worker ownership semantics
- Complexity increase

**Is this acceptable?** Requires careful evaluation ⚠️

---

### Candidate B: Ledger-Based Intent (No Checkpoint Change)

**Model**:
```
Worker A: Load checkpoint v1 (op-A PENDING)
Worker B: Load checkpoint v1 (op-A PENDING)
Worker A: Append INVOCATION_INTENT(op-A)
Worker A: Execute delegate.call()
Worker B: Append INVOCATION_INTENT(op-A)  
Worker B: Execute delegate.call()
```

**Result**: op-A may execute twice, both record intent ✅

**Concurrency**: UNCHANGED (at-least-once preserved) ✅

**Problem**: Two INVOCATION_INTENT records, but which execution succeeded?

**Recovery decision**:
```
Load checkpoint (op-A PENDING)
Query ledger: INVOCATION_INTENT(op-A) exists
Conclusion: op-A may have executed (uncertain)
Decision: ???
```

**Cannot distinguish**:
- First attempt succeeded, second never ran
- First crashed, second succeeded
- Both crashed

**Ledger-based approach preserves concurrency but provides limited recovery info** ⚠️

---

### attemptId Pressure from Concurrency

**With concurrent execution**:
```
Ledger:
  INVOCATION_INTENT(operationId=op-A)  ← Which attempt?
  INVOCATION_INTENT(operationId=op-A)  ← Which attempt?
  TOOL_EXECUTED(operationId=op-A)      ← Which attempt succeeded?
```

**Without attemptId**: Cannot correlate intent to outcome

**With attemptId**:
```
Ledger:
  INVOCATION_INTENT(operationId=op-A, attemptId=att-1)
  INVOCATION_INTENT(operationId=op-A, attemptId=att-2)
  TOOL_EXECUTED(operationId=op-A, attemptId=att-2)
```

**Recovery logic**:
```
att-1: Intent exists, no outcome → uncertain
att-2: Intent + outcome → executed
```

**Conclusion**: Concurrent resume creates pressure for attemptId ⚠️

---

### Separation: Uncertainty Detection vs Worker Ownership

**Question**: Can uncertain-outcome tracking be separable from concurrency ownership?

**Answer**: PARTIALLY ✅

**Uncertainty detection** (semantic goal):
- Know that physical invocation may have begun
- Detect crash windows
- Enable safe recovery decisions

**Worker ownership** (concurrency control):
- Prevent concurrent execution of same operation
- Lease/fencing/claiming
- Single-worker guarantee

**These are DIFFERENT concerns**

**Option 1**: Accept uncertainty detection WITHOUT single-worker ownership
- Preserve at-least-once
- Use attemptId to distinguish attempts
- Recovery handles multiple uncertain attempts

**Option 2**: Introduce worker ownership for operations
- Checkpoint state transition becomes claim
- Single-worker execution per operation
- More complex, but cleaner recovery

**Recommendation**: Option 1 for M6-T4A (preserve at-least-once, add attemptId)

---

## N. attemptId Pressure

### Classification: JUSTIFIED NEXT ✅

**Current pressure**:
1. **Concurrent resume correlation** — Distinguish which attempt succeeded
2. **Retry correlation** — Link retry to original attempt
3. **Ledger query** — Filter intent/outcome by attempt

**NOT required for FIRST uncertain-outcome detection** ⚠️

**Can defer if**:
- M6-T4A assumes single-worker execution (test/demo)
- OR accepts ambiguous correlation for concurrent attempts
- Retry is out of scope for T4A

---

### What attemptId Would Own

**Semantic definition**:
> Identity of one physical invocation attempt of a logical operation

**Relationship**:
```
operationId (logical)
  ├─ attemptId-1 (first physical invocation)
  ├─ attemptId-2 (retry after timeout)
  └─ attemptId-3 (retry after crash)
```

**Usage**:
```
INVOCATION_INTENT(operationId=op-A, attemptId=att-1)
TOOL_FAILED(operationId=op-A, attemptId=att-1)  // timeout
INVOCATION_INTENT(operationId=op-A, attemptId=att-2)  // retry
TOOL_EXECUTED(operationId=op-A, attemptId=att-2)
```

---

### Required vs Optional

**For M6-T4A basic uncertain-outcome detection**: NOT REQUIRED ⚠️

**Minimal requirement**:
```
INVOCATION_INTENT(operationId)  // Any physical attempt may have begun
```

**Recovery logic**:
```
If INVOCATION_INTENT exists AND no TOOL_EXECUTED:
  → Uncertain outcome
  → Require external verification or idempotency
```

**For retry/concurrent-resume correlation**: REQUIRED ✅

**Classification**: **JUSTIFIED NEXT** (implement in M6-T4B, not T4A)

---

## O. TOOL_STARTED / Invocation Intent Pressure

### Classification: REQUIRED NOW ✅

**Core requirement**: Detect crash window T9-T15

**Without pre-call durable fact**:
```
Crash at T9 (delegate.call begins)
Checkpoint: op-A PENDING
Ledger: No outcome
Recovery: Cannot distinguish "never ran" from "may have ran"
```

**With pre-call durable fact**:
```
Pre-call: Record INVOCATION_INTENT(op-A)
Crash at T9
Recovery: INVOCATION_INTENT exists → uncertain outcome
```

**Verdict**: Pre-call durable fact is REQUIRED for basic uncertain-outcome detection ✅

---

### EventType.TOOL_STARTED vs Semantic Need

**Semantic need**: Pre-call durable invocation intent ✅

**EventType.TOOL_STARTED**: ONE possible implementation ⚠️

**Alternatives**:
1. **Checkpoint state** (PENDING → IN_FLIGHT)
2. **Dedicated invocation state store**
3. **Ledger event** (INVOCATION_INTENT)

**Do NOT assume EventType.TOOL_STARTED is the only solution**

**Evaluation**:
- If using checkpoint state → No EventType needed
- If using ledger → EventType.INVOCATION_INTENT (not "STARTED" — see semantic subtlety below)
- If using dedicated store → Store-specific API

---

### Critical Semantic Subtlety

**"STARTED" implies actual entry** ❌

**Problem**: Durable write happens BEFORE delegate.call() entry

**Timeline**:
```
T8c: Write durable fact
T8d: Crash possible here
T8e: delegate.call() entry
```

**If crash at T8d**:
- Durable record says "STARTED"
- But delegate never actually entered

**Conclusion**: "STARTED" is semantically incorrect for pre-call fact ❌

---

### Proposed Semantic Names

**Better alternatives**:
- **INVOCATION_INTENT** — Framework intends to invoke ✅
- **DISPATCH_AUTHORIZED** — Authorized to dispatch ✅
- **EXECUTION_PREPARED** — Prepared for execution ✅

**NOT**:
- TOOL_STARTED — Implies actual entry ❌
- TOOL_INVOKED — Implies completion ❌
- TOOL_DISPATCHED — Implies sent ❌

**Recommendation**: INVOCATION_INTENT (clearest semantics)

---

### Classification Summary

**Pre-call durable fact**: REQUIRED NOW ✅  
**Specific EventType name**: Depends on storage mechanism ⚠️  
**EventType.TOOL_STARTED**: Semantically incorrect ❌  
**Recommended**: INVOCATION_INTENT or checkpoint state transition ✅

---
