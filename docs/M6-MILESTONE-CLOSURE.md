# M6: DURABLE RECOVERY — MILESTONE CLOSURE

**Milestone:** M6 Durable Recovery  
**Status:** ✅ COMPLETE  
**Date:** 2026-09-16  
**Duration:** M6-T1 through M6-T6

---

## EXECUTIVE SUMMARY

**M6 Mission:**

> Build comprehensive durable recovery semantics for Agent execution, enabling safe continuation after crashes, restarts, and uncertain invocation states.

**Status:** **COMPLETE**

All M6 tasks finished:
- ✅ M6-T1: ExecutionLedger Foundation
- ✅ M6-T2: Event Dispatch & Observability
- ✅ M6-T3: Durable Tool Operation Identity
- ✅ M6-T4: Invocation Intent Foundation & Recovery Classification
- ✅ M6-T5: Physical Attempt Identity & Recovery Resolution
- ✅ **M6-T6: General Durable Execution Checkpoints (NO-GO Architecture Decision)**

**Key Achievement:**

Arctra now has **production-grade durable recovery** for Agent processes:
- Cross-JVM/runtime recovery
- Physical invocation uncertainty detection
- Operator-driven resolution workflow
- At-least-once execution guarantees
- Restart-safe semantics

---

## M6 COMPLETED CAPABILITIES

### 1. Execution History Authority (M6-T1)

**Delivered:**
- `ExecutionLedger` interface (append-only audit trail)
- `ExecutionRecord` with sequence, eventType, payload
- `InMemoryExecutionLedger` reference implementation
- Historical fact authority (NOT recovery state)

**Semantics:**
- Ledger records what happened (history)
- Checkpoint records where to continue (recovery state)
- Clear separation: audit vs. recovery

### 2. Event Dispatch & Observability (M6-T2)

**Delivered:**
- `ExecutionEventListener` projection interface
- Event types: SUSPENDED, RESUMED, COMPLETED, APPROVAL_REQUIRED, etc.
- `ExecutionLedgerListener` adapter (projects events to ledger)
- `CompositeExecutionEventListener` (projection isolation)

**Semantics:**
- Events published after domain facts become true
- Projection failures cannot alter execution truth
- Ledger is one projection, not the authority

### 3. Durable Tool Operation Identity (M6-T3)

**Delivered:**
- `operationId` in `PendingToolCall` (framework-owned, stable)
- Distinct from `toolCallId` (protocol-owned, ephemeral)
- Generated once at tool batch materialization
- Durable before physical execution possible

**Semantics:**
- `operationId`: Logical operation identity (recovery-critical)
- `toolCallId`: Protocol correlation (Spring AI conversation)
- Enables stable recovery reference across attempts

### 4. Invocation Intent & Recovery Classification (M6-T4)

**Delivered:**
- `InvocationStateStore` interface (intent + resolution authority)
- `recordInvocationIntent()` mandatory gate before physical invocation
- `hasInvocationIntent()` recovery read
- `InvocationRecoveryClassifier` (aggregates attempts into classification)
- `executionEpoch` for restart detection
- Automatic recovery mode selection (same vs. cross-incarnation)

**Semantics:**
- Intent recorded = "physical invocation MAY proceed"
- Intent presence after crash = "MAY_HAVE_INVOKED"
- Cross-incarnation resume triggers recovery classification
- Fail-closed: uncertainty blocks execution

### 5. Physical Attempt Identity & Recovery Resolution (M6-T5)

**Delivered:**
- `attemptId` (UUID, unique per physical invocation try)
- Per-attempt intent tracking
- Recovery classification types:
  - `DEFINITELY_NOT_DISPATCHED` (safe to execute)
  - `MAY_HAVE_INVOKED` (uncertain, needs resolution)
  - `RESOLVED_EXECUTED` (operator confirmed executed)
  - `RESOLVED_NOT_EXECUTED` (operator confirmed not executed)
- `RecoveryResolution` API (operator resolution interface)
- Mixed physical/recovered execution in single batch
- Whole-batch preflight classification gate

**Semantics:**
- `operationId`: stable logical identity
- `attemptId`: unique physical try identity
- Multiple attempts may exist for same operation
- Resolution tied to specific attempt
- Aggregation logic: any unresolved attempt → MAY_HAVE_INVOKED

### 6. Architecture Validation (M6-T6)

**Delivered:**
- Complete source audit of execution flow
- Crash window analysis (15+ phases analyzed)
- Authority distribution validation
- Checkpoint semantic analysis
- **Architecture Decision: NO general execution checkpoints needed**

**Key Findings:**
- Current authority model is correct and complete
- Every recovery fact has clear owner
- General checkpoints would create authority overlap
- Approval suspension is the natural checkpoint boundary
- Tool recovery via InvocationStateStore (separate authority)
- Conversation via ChatMemory (external authority)

---

## M6 ARCHITECTURE PRINCIPLES VALIDATED

### 1. Single Authority Principle

**Each durable fact has exactly one authoritative owner:**

| Fact | Authority | Storage |
|------|-----------|---------|
| Process waiting for approval | Checkpoint existence | CheckpointStore |
| Logical tool operation | PendingToolCall | Checkpoint |
| Physical attempt intent | InvocationIntent | InvocationStateStore |
| External outcome resolution | OperationResolution | InvocationStateStore |
| Conversation history | Messages | ChatMemory (Spring AI) |
| Execution history | ExecutionRecord | ExecutionLedger |

**No overlap. No gaps.**

### 2. Approval Is The Natural Checkpoint Boundary

**Why approval suspension is the correct checkpoint point:**
- Human decision point (naturally async)
- Operation batch already materialized (stable identity)
- Semantically meaningful suspension (not arbitrary)
- Clear continuation semantics (APPROVE/REJECT)

**Why ordinary execution doesn't need checkpoints:**
- Re-execution from start is safe (no decision to lose)
- Tool recovery handled by InvocationStateStore (separate concern)
- Conversation state owned by ChatMemory (external authority)
- Marginal cost: duplicate billing (acceptable operational overhead)

### 3. Recovery vs. History Separation

**Checkpoint (recovery state):**
- Where to continue execution
- Current pending operations
- Active process state

**Ledger (history):**
- What happened during execution
- Immutable audit trail
- Query/diagnosis support

**Not event sourcing:** Ledger does NOT reconstruct current state.

---

## M6 PUBLIC API ADDITIONS

### Core Interfaces

```java
// M6-T1: Historical fact authority
public interface ExecutionLedger {
  ExecutionRecord append(String processId, EventType eventType, 
                         Long checkpointVersion, String payload);
  List<ExecutionRecord> queryByProcess(String processId);
}

// M6-T2: Event projection
public interface ExecutionEventListener {
  void onEvent(ExecutionEvent event);
}

// M6-T3: Operation identity (in PendingToolCall)
public record PendingToolCall(
    String operationId,  // NEW: framework identity
    String toolCallId,   // Protocol identity
    String toolName,
    String arguments
)

// M6-T4: Invocation state authority
public interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId, String attemptId);
  boolean hasInvocationIntent(String processId, String operationId, String attemptId);
  List<InvocationAttempt> findAttempts(String processId, String operationId);
  void recordResolution(String processId, String operationId, String attemptId, 
                        ResolutionType type, String recoveredResult);
  Optional<OperationResolution> getResolution(String processId, String operationId, String attemptId);
}

// M6-T5: Recovery resolution capability
public interface RecoveryResolution {
  void resolveAsExecuted(String processId, long checkpointVersion,
                         String operationId, String attemptId, String recoveredResult);
  void resolveAsNotExecuted(String processId, long checkpointVersion,
                            String operationId, String attemptId);
}

// AgentRuntime extension (M6-T5)
AgentRuntime.recovery() → RecoveryResolution
```

### Exception Types (M6-T4, M6-T5)

```java
InvocationIntentPersistenceException   // Intent write failed
RecoveryUncertaintyException            // MAY_HAVE_INVOKED detected
RecoveryResolutionConflictException     // Conflicting resolution
InvalidRecoveryResolutionException      // Invalid resolution attempt
```

### Total Public API Delta

- **Interfaces:** 3 new (ExecutionLedger, ExecutionEventListener, RecoveryResolution)
- **Records:** 2 extended (PendingToolCall, SuspensionCheckpoint)
- **Exceptions:** 4 new
- **Runtime methods:** 1 new (recovery())
- **Total:** ~10 new public types/methods

---

## M6 KNOWN LIMITATIONS

### 1. Checkpoint/ChatMemory Consistency

**Issue:**
```
CheckpointStore.write()
  ↓ (crash)
ChatMemory.write()
```

**Status:** Known design limitation (M2/M5 existing)

**Impact:** Small crash windows where checkpoint and conversation may diverge

**Mitigation:**
- Use durable ChatMemory implementation if critical
- Rare window (acceptable operational risk)

**Future:** M7 "Consistency & Transactions" phase

### 2. Ordinary Execution (No HITL) Lost Work

**Issue:** Crash during non-approval execution → must re-execute from start

**Status:** Design decision (not a bug)

**Impact:** Lost compute time, duplicate billing

**Mitigation:**
- Use HITL for expensive operations
- Tool idempotency where possible
- InvocationStateStore prevents duplicate external side effects

**Rationale:** General checkpoints would add complexity without proportional value

### 3. Model Invocation Billing Duplication

**Issue:** Crash during model call → retry may bill twice

**Status:** Acceptable operational cost

**Impact:** Marginal billing overhead

**Mitigation:**
- Provider idempotency keys (if critical)
- Operational monitoring

**Rationale:** ModelInvocationStateStore complexity not justified

### 4. Process Discovery (Deferred to M7)

**Issue:** No `CheckpointStore.listCheckpoints()` enumeration

**Status:** M7 scope

**Impact:** Cannot list all waiting processes

**Mitigation:** Application tracks processIds if needed

**Future:** M7 recovery orchestration

### 5. Distributed Coordination (Deferred to M8)

**Issue:** No claim/lease mechanism

**Status:** M8 scope

**Impact:** Optimistic CAS only (at-least-once semantics)

**Mitigation:** Acceptable for M6 single-node / stateless scenarios

**Future:** M8 distributed recovery

---

## M6 TEST COVERAGE

**Total Tests:** 207 (0 failures)

**Coverage by Task:**
- M6-T1: ExecutionLedger tests
- M6-T2: Event projection tests
- M6-T3: Operation identity tests
- M6-T4: Recovery classification tests (restart detection, mode selection)
- M6-T5: Attempt identity, recovery resolution, mixed execution tests
- M6-T6: No implementation (architecture gate only)

**Test Categories:**
- Unit tests (authority contracts)
- Integration tests (cross-boundary scenarios)
- Scenario tests (end-to-end recovery workflows)
- Architecture tests (dependency boundaries)

---

## M6 IMPLEMENTATION STATISTICS

**Production Code:**
- Lines of Java: ~2,500 (M6 delta)
- New classes: ~25
- Modified classes: ~10

**Test Code:**
- Lines of test Java: ~4,000 (M6 delta)
- New test classes: ~30

**Documentation:**
- Architecture gates: 6 documents
- Implementation reports: 10+ documents
- Total documentation: ~50,000 words

**Duration:** ~3 weeks (calendar time)

---

## M6 DESIGN DECISIONS SUMMARY

### Key Decisions

1. **Recovery vs. History Separation**
   - Decision: Checkpoint (recovery) and Ledger (history) are separate authorities
   - Rationale: Arctra is NOT event-sourced; ledger for audit, not state reconstruction

2. **Physical Attempt Identity**
   - Decision: Add `attemptId` distinct from `operationId`
   - Rationale: Track multiple physical tries for same logical operation

3. **Fail-Closed Recovery**
   - Decision: Any uncertain attempt blocks entire batch
   - Rationale: Safety over availability; require explicit resolution

4. **Automatic Mode Selection**
   - Decision: Use `executionEpoch` to detect cross-incarnation
   - Rationale: Eliminate manual recovery activation; TOCTOU-safe inside CHECK A

5. **No General Checkpoints (M6-T6)**
   - Decision: NO new checkpoint abstraction beyond approval suspension
   - Rationale: Current authority model is complete; general checkpoints would create overlap

6. **Resolution API via AgentRuntime**
   - Decision: `runtime.recovery()` exposes resolution capability
   - Rationale: Single entry point; hides internal stores

7. **At-Least-Once Semantics**
   - Decision: Accept duplicate execution possibility
   - Rationale: Simpler than exactly-once; acceptable operational cost

---

## M6 vs. ORIGINAL M6 PLAN

**Original M6 Goals:**
- ✅ Durable recovery across runtime boundaries
- ✅ Tool invocation recovery semantics
- ✅ Recovery classification
- ✅ Operator resolution workflow
- ✅ Audit trail foundation

**Not Originally Planned (Discovered During M6):**
- ExecutionLedger as separate authority (T1)
- Event projection architecture (T2)
- Physical attempt identity (T5)
- Automatic restart detection (T4F)
- Architecture validation gate (T6)

**Deferred (Out of Scope):**
- Process discovery enumeration → M7
- Distributed coordination → M8
- ChatMemory consistency → M7 or separate workstream

---

## M6 ARCHITECTURE GATE (T6) HIGHLIGHTS

**M6-T6 Analysis:**
- Complete source audit (29,507 LOC)
- 15+ crash window phases analyzed
- Authority matrix validated (8 fact domains)
- Checkpoint semantic analysis
- Design options evaluated (4 options)

**M6-T6 Decision:**

> **NO-GO: No general execution checkpoints needed.**
>
> Current architecture is correct and sufficient. Every recovery fact has clear authoritative owner. Adding general checkpoints would create authority overlap without semantic value.

**Rationale:**
- Approval suspension is the natural checkpoint boundary
- Tool recovery via InvocationStateStore (separate authority)
- Conversation via ChatMemory (external authority)
- Ordinary execution re-execution is safe and acceptable

**Impact:** Zero M6-T6 implementation. Architecture validated as-is.

---

## NEXT PHASE: M7 RECOVERY ORCHESTRATION

**M7 Scope (Recommended):**
- Checkpoint enumeration API (`listCheckpoints()`)
- Orphaned process detection
- Automatic recovery triggers
- Recovery scheduling
- Process lifecycle management

**M7 Should NOT:**
- ❌ Add general execution checkpoints
- ❌ Redesign checkpoint model
- ❌ Solve ChatMemory consistency (separate workstream)

**M7 Entry Criteria:**
- ✅ M6 complete (all tasks done)
- ✅ Known limitations documented
- ✅ Test coverage stable (207 tests passing)
- ✅ Architecture validated (T6 gate)

---

## M6 SUCCESS CRITERIA VALIDATION

### Original Success Criteria

1. **Cross-runtime recovery** ✅
   - Checkpoint-backed durable suspension
   - CHECK A/B validation
   - RuntimeBinding resolution

2. **Tool invocation recovery** ✅
   - InvocationStateStore authority
   - Intent recording before execution
   - Recovery classification

3. **Uncertainty detection** ✅
   - MAY_HAVE_INVOKED classification
   - Whole-batch preflight gate
   - RecoveryUncertaintyException

4. **Operator resolution** ✅
   - RecoveryResolution API
   - Resolved execution semantics
   - Mixed physical/recovered execution

5. **At-least-once guarantees** ✅
   - Optimistic CAS (CHECK B)
   - Duplicate execution acceptable
   - External side effects reconcilable

**All criteria met.**

---

## M6 LESSONS LEARNED

### What Went Well

1. **Incremental architecture refinement**
   - T1-T5 built progressively on each other
   - Each task validated before next
   - No major rework needed

2. **Source-truth-first approach**
   - T6 gate relied on actual code audit
   - Avoided speculative over-design
   - NO-GO decision based on evidence

3. **Authority clarity**
   - Single-owner principle maintained throughout
   - No overlapping authorities created
   - Clear separation of concerns

4. **Test-driven validation**
   - 207 tests validate semantics
   - Each task had complete test coverage
   - Zero failures at M6 closure

### What Could Improve

1. **ChatMemory consistency** (deferred)
   - Recognized early but deferred
   - Should be prioritized in M7 or parallel track
   - Distributed transaction complexity high

2. **Documentation timing**
   - Some architecture documents written during implementation
   - Could benefit from more upfront design gates

3. **Public API evolution**
   - 10 new types/methods added
   - Could consolidate in future refactoring
   - Acceptable for M6 scope

---

## M6 CLOSURE CHECKLIST

- ✅ All M6 tasks complete (T1-T6)
- ✅ Test coverage: 207 tests, 0 failures
- ✅ Architecture validated (T6 NO-GO decision)
- ✅ Known limitations documented
- ✅ Public API changes documented
- ✅ CURRENT-STATE.md updated
- ✅ TASKS.md updated
- ✅ M6 Closure document created (this file)
- ✅ M7 recommendations provided
- ✅ ./mvnw clean verify passes

**M6 MILESTONE: CLOSED**

---

## FINAL RECOMMENDATION

**M6 is production-ready for:**
- Single-node Agent runtime
- Approval-driven workflows (HITL)
- Tool invocation recovery
- Operator-driven resolution
- Audit trail / observability

**M6 is NOT sufficient for:**
- Automatic recovery orchestration (M7)
- Distributed multi-node coordination (M8)
- Guaranteed checkpoint/ChatMemory consistency (future)

**Next Steps:**
1. Close M6 milestone
2. Plan M7 Recovery Orchestration
3. Consider ChatMemory consistency as parallel workstream
4. Continue V1 roadmap toward 0.1.0

---

**END OF M6 MILESTONE CLOSURE**

**Status:** ✅ COMPLETE  
**Date:** 2026-09-16  
**Total Duration:** M6-T1 through M6-T6  
**Test Coverage:** 207 tests, 0 failures  
**Architecture Decision:** Current model validated, no changes needed
