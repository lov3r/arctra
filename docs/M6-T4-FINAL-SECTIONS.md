## V. Restart / Cross-JVM Scenario

### Scenario

**Node A**:
```
Load checkpoint v1 (op-A pending)
Begin executeOperation(op-A)
delegate.call() invoked
JVM crashes
```

**Node B** (starts later):
```
JVM starts
Load same durable checkpoint???
```

**Problem**: Current InMemoryCheckpointStore cannot provide checkpoint to Node B

---

### What Node B Must Know

**To decide recovery action, Node B needs**:

1. **Process state**: Is there a pending process?
2. **Operation identity**: Which operation was pending?
3. **Invocation state**: Did physical invocation begin?
4. **Outcome**: Did operation complete successfully?
5. **External state**: What actually committed?

**Current implementation provides**: NONE (in-memory only)

**Real persistent implementation would need to provide**: ALL

---

### Recovery Logic (Conceptual)

**With persistent checkpoint + ledger**:

```
Node B starts
Load checkpoint: op-A pending
Query ledger: 
  - Has INVOCATION_INTENT(op-A)? → YES
  - Has TOOL_EXECUTED(op-A)? → NO
  - Has TOOL_FAILED(op-A)? → NO

Conclusion: Uncertain outcome

Decision tree:
  IF tool has idempotency key:
    → Retry with key
  ELSE IF external query supported:
    → Query external state
  ELSE:
    → Operator intervention
```

---

### Separation: Semantic vs Implementation

**M6-T4 semantic architecture**: What Node B must know ✅

**Persistent implementation**: How Node B learns it ❌ (deferred)

**Critical distinction**: M6-T4 defines recovery contracts, not storage technology

---

## W. Process-Recovery Subsystem Ownership

### Current Subsystem Boundaries

**DurableResumeCoordinator**:
- CHECK A (checkpoint load/validation)
- Runtime binding resolution
- Approval/rejection lifecycle
- Resumed execution delegation
- CHECK B (checkpoint transition)

**Should NOT own**:
- Tool retry logic ❌
- Idempotency management ❌
- Receipt tracking ❌
- External query orchestration ❌
- Operator intervention workflow ❌

**Rationale**: DurableResumeCoordinator is process-lifecycle orchestrator, not tool-execution engine

---

### Candidate Owners for Uncertain-Outcome Recovery

**Option 1: Tool Execution Subsystem** (future ToolExecutionRuntime)
- Owns physical invocation
- Manages invocation state
- Handles retry/timeout
- Coordinates with external systems
- Reports to coordinator

**Option 2: Recovery Subsystem** (new)
- Dedicated uncertain-outcome handler
- Queries checkpoint + ledger
- Applies recovery policy
- Invokes tools via execution subsystem
- Reports outcomes to coordinator

**Option 3: Within DurableResumeCoordinator** (expansion)
- Coordinator gains recovery decision logic
- Risk: Overloads coordinator with tool concerns

---

### Recommendation

**Avoid Option 3** ❌

**Prefer Option 1** (Tool Execution Subsystem) ✅

**Rationale**:
- Tool concerns belong in tool subsystem
- Coordinator delegates execution, doesn't own it
- Clean boundary preservation

**Option 2 viable if tool execution subsystem becomes too complex**

---

## X. Tool Execution Subsystem Pressure

### Responsibilities Accumulating

**Physical execution concerns**:
1. operationId correlation (M6-T3A) ✅
2. Per-operation context (M6-T3B) ✅
3. Invocation intent (M6-T4 requirement) ⚠️
4. Timeout (future)
5. Retry (future)
6. Idempotency coordination (future)
7. Receipt tracking (future)
8. Uncertain outcome handling (future)
9. Compensation (future)
10. Tool events (current, distributed)

**Currently distributed across**:
- ProtocolReconstructor (per-operation execution)
- EvidenceCapturingToolCallback (invocation boundary)
- ExecutionLedgerListener (event emission)
- DurableResumeCoordinator (orchestration)

---

### Classification: JUSTIFIED FOR NEXT IMPLEMENTATION ✅

**Rationale**:
- Concerns are accumulating ✅
- Clear semantic boundary exists ✅
- Responsibility concentration happening ✅
- Provider independence needed ✅

**NOT required for M6-T4A**, but pressure is real

**Recommended for M6-T4B or M6-T5**

---

### Conceptual API (Illustration Only — DO NOT IMPLEMENT)

```java
interface ToolExecutionRuntime {
  // Execute one operation with recovery semantics
  ToolExecutionResult execute(
    PendingToolCall operation,
    ToolCallback delegate,
    ToolContext context,
    RecoveryPolicy policy
  );
  
  // Query execution state
  ExecutionState queryState(String operationId);
  
  // Handle uncertain outcome
  RecoveryDecision handleUncertain(
    String operationId,
    ExternalContract externalContract
  );
}
```

**Deferred to future implementation milestone**

---

## Y. Provider Boundary Protection

### Spring AI Concerns (Must Stay Isolated)

**Provider adapter owns**:
- ToolCallback interface
- ToolContext structure
- ToolResponseMessage construction
- AssistantMessage.ToolCall conversion
- Spring AI protocol compliance

**Framework owns**:
- operationId
- PendingToolCall
- SuspensionCheckpoint
- ExecutionEvent
- Recovery semantics
- Uncertain outcome model

**Frozen boundary**: Framework recovery semantics MUST NOT depend on Spring AI types

---

### Why This Matters

**Future providers** (beyond Spring AI):
- LangChain4j
- Semantic Kernel
- Direct OpenAI API
- Anthropic API
- Custom tool implementations

**Each has different**:
- Tool invocation protocol
- Context structure
- Response format
- Error handling

**Arctra uncertain-outcome model must be provider-independent** ✅

---

### Abstraction Verification

**Correct**:
```java
// Framework types only
interface UncertainOutcomeHandler {
  RecoveryDecision handle(
    PendingToolCall operation,  // ✅ Framework type
    ExecutionHistory history     // ✅ Framework type
  );
}
```

**Incorrect**:
```java
// Spring AI dependency leaked
interface UncertainOutcomeHandler {
  RecoveryDecision handle(
    AssistantMessage.ToolCall toolCall,  // ❌ Provider type
    ChatResponse response                // ❌ Provider type
  );
}
```

---

## Z. Candidate Architectures — Summary

### Candidate A: Best-Effort Pre-Call Event Only

**Model**:
```
Append INVOCATION_INTENT (best-effort)
If append fails: log warning, proceed
delegate.call()
Append TOOL_EXECUTED/FAILED (best-effort)
```

**Detects uncertainty**: ONLY if append succeeded before crash ⚠️

**Recovery authority**: Ledger presence → may have invoked

**Concurrency**: Safe (at-least-once preserved) ✅

**External cooperation**: Not required

**Complexity**: Low

**Fatal flaw**: Append failure + crash = undetectable uncertainty ❌

**Verdict**: INSUFFICIENT ❌

---

### Candidate B: Synchronous Durable Pre-Call Gate

**Model**:
```
Append INVOCATION_INTENT (synchronous, REQUIRED)
If append fails: throw exception, halt execution
delegate.call()
Append TOOL_EXECUTED/FAILED (best-effort)
```

**Detects uncertainty**: YES (absence = safe) ✅

**Recovery authority**: Ledger becomes gate + recovery input ⚠️

**Concurrency**: Safe (at-least-once preserved) ✅

**External cooperation**: Not required

**Complexity**: Medium

**Issue**: Ledger failure blocks execution ⚠️

**Verdict**: VIABLE but changes ledger authority ⚠️

---

### Candidate C: Checkpoint PENDING → IN_FLIGHT Transition

**Model**:
```
CAS checkpoint: op-A PENDING → IN_FLIGHT (v1 → v2)
If CAS fails: detect conflict, halt
delegate.call()
CHECK B: delete checkpoint (v2 → deleted)
```

**Detects uncertainty**: YES (IN_FLIGHT = may have invoked) ✅

**Recovery authority**: Checkpoint (unchanged) ✅

**Concurrency**: CHANGES semantics (introduces claiming) ⚠️

**External cooperation**: Not required

**Complexity**: Medium-High

**Issue**: Concurrent resume blocked ⚠️

**Verdict**: VIABLE but changes concurrency model ⚠️

---

### Candidate D: Separate Durable Attempt State

**Model**:
```
attemptStateStore.recordIntent(operationId, attemptId)
If write fails: throw exception, halt
delegate.call()
attemptStateStore.recordOutcome(operationId, attemptId, outcome)
```

**Detects uncertainty**: YES ✅

**Recovery authority**: Clear (dedicated store) ✅

**Concurrency**: Safe (at-least-once preserved) ✅

**External cooperation**: Not required

**Complexity**: High (new subsystem)

**Issue**: Additional infrastructure ⚠️

**Verdict**: VIABLE, cleanest separation ✅

---

### Candidate E: External Idempotency Only

**Model**:
```
No internal pre-call state
delegate.call() with idempotency key
Rely on external deduplication
```

**Detects uncertainty**: NO (assumes external handling) ❌

**Recovery authority**: External system ✅

**Concurrency**: Safe ✅

**External cooperation**: REQUIRED ✅

**Complexity**: Low (Arctra-side)

**Issue**: Only works for cooperating tools ⚠️

**Verdict**: INSUFFICIENT as general solution, but REQUIRED as cooperation mechanism ✅

---

### Candidate F: Hybrid

**Model**:
```
Internal: Checkpoint IN_FLIGHT or ledger intent (detect uncertainty)
External: Idempotency key + receipt (resolve uncertainty)
Recovery: Internal detection + external resolution
```

**Detects uncertainty**: YES ✅

**Recovery authority**: Hybrid (internal + external) ✅

**Concurrency**: Depends on internal mechanism ⚠️

**External cooperation**: OPTIONAL (graceful degradation) ✅

**Complexity**: High

**Issue**: Two mechanisms to coordinate ⚠️

**Verdict**: LIKELY ENTERPRISE DIRECTION ✅

---

## AA. Candidate Comparison Table

| Candidate | Detects Uncertainty | Recovery Authority Clear | Concurrency Safe | Requires External Cooperation | Complexity | Recommended |
|-----------|---------------------|--------------------------|------------------|-------------------------------|------------|-------------|
| **A: Best-effort event** | ⚠️ Only if append succeeded | ⚠️ Ledger | ✅ Yes | ❌ No | Low | ❌ NO |
| **B: Sync ledger gate** | ✅ Yes | ⚠️ Ledger becomes gate | ✅ Yes | ❌ No | Medium | ⚠️ MAYBE |
| **C: Checkpoint IN_FLIGHT** | ✅ Yes | ✅ Checkpoint | ⚠️ Changes (claiming) | ❌ No | Medium-High | ⚠️ MAYBE |
| **D: Separate attempt state** | ✅ Yes | ✅ Clear | ✅ Yes | ❌ No | High | ✅ YES (clean) |
| **E: External idempotency only** | ❌ No | ✅ External | ✅ Yes | ✅ YES | Low | ⚠️ PARTIAL |
| **F: Hybrid (C or D + E)** | ✅ Yes | ✅ Clear | ⚠️ Depends | ⚠️ Optional | High | ✅ YES (enterprise) |

---

## AB. Recommended M6-T4A Slice

### Problem Solved

**Detect uncertain outcome after crash** — Distinguish "never invoked" from "may have invoked"

---

### Fact Owned

**INVOCATION_INTENT** — Durable pre-call fact indicating physical invocation may proceed

---

### Authority

**Checkpoint state** (preferred) OR **Dedicated invocation state store** (alternative)

**NOT ledger** (avoid turning observer into gate)

---

### Likely Files/Types Affected

**If checkpoint-based**:
- `PendingToolCall` — Add execution state field
- `SuspensionCheckpoint` — Schema includes operation states
- `CheckpointStore` — May need state query API
- `DurableResumeCoordinator` — State transition before execution
- `ProtocolReconstructor` — Check state, record intent

**If separate store**:
- `ToolInvocationState` (new interface)
- `InMemoryToolInvocationState` (new impl)
- `ProtocolReconstructor` — Record intent before call
- `DurableResumeCoordinator` — Query state on resume

---

### Public API Impact

**Expected**: 0 (internal foundation only)

**Rationale**: First slice is internal state management, no user-facing API

---

### Core Impact

**Expected**: 0 (no arctra-core changes)

**Rationale**: New state is runtime concern, not process model

---

### Coordinator Impact

**Expected**: Minimal (state query before execution)

**Rationale**: Coordinator checks "may have invoked" state, delegates recovery decision

---

## AC. Recommended Sequence After T4A

### Milestone Sequence

**M6-T4A: Invocation Intent Foundation** ✅
- Durable pre-call fact
- Detect uncertain outcome
- Internal state only
- No retry, no recovery policy yet

**M6-T4B: Attempt Identity** ✅
- attemptId generation
- Concurrent resume correlation
- Retry preparation
- Still no automatic retry

**M6-T4C: External Cooperation Contracts** ✅
- Idempotency key API
- Receipt tracking
- Query API design
- Tool declares capabilities

**M6-T4D: Recovery Policy Foundation** ✅
- Recovery decision logic
- Policy evaluation (read-only, idempotent, etc.)
- Operator intervention hooks
- No automatic retry for unsafe operations

**M6-T4E: Controlled Retry** ✅
- Retry with idempotency
- Timeout/backoff
- External query before retry
- Graceful degradation

**M6-T4F: Operator Intervention UX** (optional)
- Dashboard integration
- Manual decision API
- Process continuation after intervention

---

## AD. Entry Gate Answers

**1. Can current Arctra distinguish never-invoked from may-have-invoked after crash?**  
❌ **NO** — No durable pre-call fact exists

**2. Is absence of TOOL_EXECUTED proof that no side effect occurred?**  
❌ **NO** — Crash during delegate.call() may have committed external effect

**3. Is TOOL_FAILED proof that no side effect occurred?**  
❌ **NO** — Exception may occur after external commit (timeout, network error)

**4. Does operationId identify a physical attempt?**  
❌ **NO** — operationId identifies logical operation, not physical attempt

**5. Is attemptId required to detect the first uncertain crash window?**  
❌ **NO** — Basic uncertainty detection needs pre-call intent only; attemptId needed for retry correlation

**6. Is a durable pre-call fact required?**  
✅ **YES** — Cannot distinguish uncertainty without it

**7. Can a best-effort ExecutionEvent safely provide that fact?**  
❌ **NO** — Append failure + crash = undetectable uncertainty

**8. Should ExecutionLedger become recovery authority?**  
⚠️ **AVOID IF POSSIBLE** — Violates observer contract

**9. Should checkpoint remain current recovery authority?**  
✅ **YES** — Preserve existing authority model

**10. Does checkpoint need new tool execution state?**  
✅ **YES** (if checkpoint-based solution) — PENDING vs IN_FLIGHT or equivalent

**11. Can arbitrary tool side effects be made exactly-once generically?**  
❌ **NO** — Distributed systems boundary, requires external cooperation

**12. Can operationId double as an external idempotency key automatically?**  
⚠️ **NO** — Semantics differ, tool must decide

**13. Is external cooperation required for effectively-once behavior?**  
✅ **YES** — Idempotency key, receipt, or query API needed

**14. Can uncertain non-idempotent operations require operator intervention?**  
✅ **YES** — Honest enterprise requirement

**15. Does current InMemoryCheckpointStore support real JVM restart recovery?**  
❌ **NO** — In-memory only, no cross-JVM support

**16. Does current ExecutionLedger survive JVM restart?**  
❌ **NO** — In-memory only

**17. Is ToolExecutionRuntime justified now?**  
✅ **JUSTIFIED FOR NEXT** (M6-T4B/T5, not T4A)

**18. Is DurableResumeCoordinator the correct owner of uncertain-outcome recovery?**  
❌ **NO** — Should delegate to tool execution subsystem

**19. What exact new durable fact is missing today?**  
**INVOCATION_INTENT** — Pre-call durable fact indicating physical invocation may proceed

**20. What is the smallest safe M6-T4A implementation slice?**  
**Durable pre-call invocation intent** — Detect uncertainty, no retry yet

**21. Does that slice require public API changes?**  
❌ **NO** — Internal foundation only

**22. Does it require changing CHECK A/B?**  
⚠️ **MINIMAL** — CHECK A queries state, CHECK B unchanged

**23. Does it change at-least-once semantics?**  
⚠️ **DEPENDS** — Checkpoint-based may introduce claiming (analyze carefully)

**24. Is M6-T4 ready to proceed?**  
✅ **YES** — Architecture analysis complete, M6-T4A slice defined

---

## AE. Decision

### ✅ **GO — M6-T4A May Begin**

**Architecture gate PASSED** ✅

**Next implementation**: M6-T4A — Invocation Intent Foundation

**Scope**:
- Durable pre-call fact (INVOCATION_INTENT or checkpoint state)
- Detect uncertain outcome after crash
- Internal mechanism only
- NO retry, NO recovery policy, NO public API

**Deferred to future**:
- attemptId (M6-T4B)
- Automatic retry (M6-T4E)
- External cooperation API (M6-T4C)
- Recovery policy (M6-T4D)
- Operator UX (M6-T4F)
- Persistent stores (separate milestone)
- ToolExecutionRuntime (M6-T4B or M6-T5)

---

## HARD STOP

M6-T4 Architecture Gate complete.

**DO NOT IMPLEMENT**:
- Production code changes
- New EventTypes
- attemptId
- TOOL_STARTED
- Retry logic
- Idempotency
- Receipt
- Recovery policy
- ToolExecutionRuntime
- Persistent stores
- Operator UI
- Public recovery SPI

**Awaiting**: Architecture review and M6-T4A implementation approval
