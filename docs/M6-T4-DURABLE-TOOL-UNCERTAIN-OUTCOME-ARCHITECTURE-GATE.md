# M6-T4 DURABLE TOOL EXECUTION CRASH WINDOW & UNCERTAIN OUTCOME ARCHITECTURE GATE

**Date**: 2024-09-14  
**Type**: Architecture Gate (Analysis Only — No Implementation)  
**Status**: Draft

---

## A. Frozen Baseline

### M6-T3A: Durable Logical Tool Operation Identity

**Status**: CLOSED ✅

**Achievement**: 
- `operationId` = Arctra-owned durable logical tool operation identity
- Framework-generated, stable across concurrent resume attempts
- Distinct for each logical operation, even same-name tools
- Durable before physical execution is possible

### M6-T3B: Authoritative Execution-Boundary Correlation

**Status**: CLOSED ✅

**Achievement**:
- Per-operation `ToolObservationContext` with `operationId`
- Authoritative correlation at `delegate.call()` boundary
- Event payload: `{"toolName":"X","operationId":"Y"}`
- Duplicate same-name + same-arguments operations safe

### Current Verified Regression

```
arctra-core:          205 tests, 0 failures
arctra-runtime-react: 128 tests, 0 failures
examples:              35 tests, 0 failures
TOTAL:                368 tests
Failures:               0
Errors:                 0
Skipped:               23
BUILD SUCCESS
```

### Current Identity Semantics

| Identity | Meaning | Owner |
|----------|---------|-------|
| `processId` | Durable process identity | Framework |
| `checkpointVersion` | Recovery generation identity | Checkpoint |
| `operationId` | Arctra logical tool operation identity | Framework (M6-T3A) |
| `toolCallId` | Provider protocol correlation identity | Provider (Spring AI) |
| `ExecutionRecord.recordId` | Durable historical record identity | Ledger |
| `ExecutionRecord.sequence` | Per-process historical ordering | Ledger |

**Frozen**: These identities remain distinct and cannot be collapsed.

---

## B. Current Execution Timeline

### Source Authority

**Files**:
- `DurableResumeCoordinator.java` (orchestration)
- `SpringAiResumedExecutionHandler.java` (execution delegation)
- `ProtocolReconstructor.java` (per-operation execution)
- `EvidenceCapturingToolCallback.java` (delegate invocation boundary)

### Timeline for ONE Approved Operation

```
T0: Checkpoint loaded
    Source: DurableResumeCoordinator.loadAndValidateCheckpoint()
    State: checkpoint contains PendingToolCall(operationId=op-A, state=PENDING)

T1: Runtime binding resolved
    Source: bindingResolver.resolve(checkpoint.runtimeBindingKey())
    State: RuntimeBinding available

T2: APPROVAL_GRANTED emitted
    Source: eventListener.onEvent(APPROVAL_GRANTED)
    State: Domain fact TRUE (approval decision exists)
    Ledger: May be recorded (best-effort projection)

T3: RESUMED emitted
    Source: eventListener.onEvent(RESUMED)
    State: Continuation beginning
    Ledger: May be recorded (best-effort projection)

T4: Operation selected for execution
    Source: for (PendingToolCall operation : pendingBatch)
    State: operation = PendingToolCall(operationId=op-A)

T5: Per-operation context created
    Source: new ToolObservationContext(..., operation.operationId(), ...)
    State: operationId known to framework

T6: ToolContext created
    Source: new ToolContext(Map.of("conversationHistory", ...))
    State: Execution environment prepared

T7: Wrapper invoked
    Source: wrappedCallback.call(operation.arguments(), toolContext)
    State: Entering EvidenceCapturingToolCallback

T8: Immediately before delegate.call()
    Source: EvidenceCapturingToolCallback.call() line before try block
    State: About to enter try { delegate.call(...) }

T9: delegate.call() begins
    Source: delegate.call(functionArguments, toolContext)
    State: Physical invocation started
    *** CRITICAL POINT: External side effect may now occur ***

T10: delegate.call() completes (return or throw)
    Source: delegate returns OR throws
    State: Framework observes outcome
    Domain fact: TOOL_EXECUTED=TRUE or TOOL_FAILED=TRUE

T11: TOOL_EXECUTED or TOOL_FAILED emitted
    Source: emitToolExecutedEvent() or emitToolFailedEvent()
    State: Event emission attempted
    Ledger: May be recorded (best-effort projection)

T12: Evidence captured (success only)
    Source: captureEvidence(toolName, result)
    State: Evidence list updated

T13: ToolResponse constructed
    Source: new ToolResponse(operation.toolCallId(), ...)
    State: Protocol response ready

T14: Model continuation
    Source: continueWithModel(continuationMessages, ...)
    State: Provider invoked with ToolResponseMessages

T15: CHECK B executed
    Source: checkpointStore.deleteIfVersion(processId, checkpointVersion)
    State: Checkpoint transition attempted (CAS)
```

---

## C. Current Durable Authorities

### Checkpoint Authority

**What it owns**:
- Current resumable process state
- Pending operations (`List<PendingToolCall>`)
- Recovery generation (`checkpointVersion`)
- Accumulated evidence

**What it does NOT own**:
- Execution history
- Tool outcome facts
- Physical invocation state

**Persistence**: `CheckpointStore` (currently `InMemoryCheckpointStore`)

**Lifetime**: Created at suspension, deleted/replaced at terminal/re-suspension

**CAS operations**:
- `replaceIfVersion()` — re-suspension (new governance wait)
- `deleteIfVersion()` — completion/failure

---

### Ledger Authority

**What it owns**:
- Durable execution history
- Event records (`ExecutionRecord`)
- Historical ordering (`sequence`)

**What it does NOT own**:
- Recovery state
- Current process state
- Physical invocation state

**Persistence**: `ExecutionLedger` (currently `InMemoryExecutionLedger`)

**Append semantics**: Best-effort projection, failure does NOT invalidate domain fact

**Query**: `queryByProcess()`, `queryRecentByProcess()`

---

### Domain Fact Authority

**What it owns**:
- Execution outcome truth (delegate returned/threw)
- Approval decision truth (approved/rejected)
- Resume continuation truth (resumed)

**What it does NOT own**:
- Ledger persistence success

**Frozen distinction** (M6-T2B):
```
Domain fact truth ≠ Ledger projection success
```

**Example**:
- delegate.call() returns → TOOL_EXECUTED domain fact = TRUE
- Event emission fails → Ledger lacks record
- Domain truth remains: tool executed successfully

---

### External Side-Effect Authority

**What it owns**:
- External system state changes
- External transaction commit/rollback
- External business outcome

**What Arctra does NOT own**:
- External commit truth
- External rollback truth
- External idempotency detection

**Frozen limitation**: Arctra cannot atomically coordinate with arbitrary external systems

---

## D. Crash Window Matrix

### Analysis of Crash Points

| Point | Checkpoint Truth | Ledger Truth | External Truth | Safe Automatic Replay? |
|-------|------------------|--------------|----------------|------------------------|
| **T0** (checkpoint loaded) | op-A pending | No outcome | No effect | YES — never started |
| **T1** (binding resolved) | op-A pending | No outcome | No effect | YES — never started |
| **T2** (APPROVAL_GRANTED) | op-A pending | May have APPROVAL_GRANTED | No effect | YES — never started |
| **T3** (RESUMED) | op-A pending | May have RESUMED | No effect | YES — never started |
| **T4** (operation selected) | op-A pending | May have RESUMED | No effect | YES — never started |
| **T5** (context created) | op-A pending | May have RESUMED | No effect | YES — never started |
| **T6** (ToolContext created) | op-A pending | May have RESUMED | No effect | YES — never started |
| **T7** (wrapper invoked) | op-A pending | May have RESUMED | No effect | YES — never started |
| **T8** (before delegate.call) | op-A pending | May have RESUMED | No effect | YES — never started |
| **T9** (delegate.call begins) | op-A pending | May have RESUMED | **UNKNOWN** ⚠️ | **UNCERTAIN** ⚠️ |
| **T10** (delegate returns/throws) | op-A pending | May have RESUMED | **UNKNOWN** ⚠️ | **UNCERTAIN** ⚠️ |
| **T11** (event emitted) | op-A pending | May have outcome | **UNKNOWN** ⚠️ | **UNCERTAIN** ⚠️ |
| **T12** (evidence captured) | op-A pending | May have TOOL_EXECUTED | **UNKNOWN** ⚠️ | **UNCERTAIN** ⚠️ |
| **T13** (response constructed) | op-A pending | May have TOOL_EXECUTED | **UNKNOWN** ⚠️ | **UNCERTAIN** ⚠️ |
| **T14** (model continuation) | op-A pending | May have TOOL_EXECUTED | **UNKNOWN** ⚠️ | **UNCERTAIN** ⚠️ |
| **T15** (CHECK B) | Transition attempted | May have TOOL_EXECUTED | **UNKNOWN** ⚠️ | **UNCERTAIN** ⚠️ |

### Key Findings

**Safe automatic replay window**: T0 → T8 (before delegate.call)  
**Uncertain outcome window**: T9 → T15 (delegate.call begins until CHECK B)  
**Critical observation**: External truth becomes UNKNOWN at T9 and remains unknown even after TOOL_EXECUTED

---


## E. Definition of Uncertain Outcome

### Proposed Definition

**Uncertain Outcome**:
> An operation has an uncertain outcome when Arctra has durable evidence that a physical invocation may have begun, but lacks authoritative evidence sufficient to determine whether the external side effect committed.

### Challenge Analysis

**Question 1: Does delegate.call() entry establish uncertainty?**

**Answer**: YES ✅

**Rationale**: Once `delegate.call()` begins (T9), the external system may receive the request and commit a side effect. Even if the delegate throws or times out, the external effect may have succeeded.

**Evidence**: Network/timeout exceptions do not prove rollback.

---

**Question 2: Does TOOL_FAILED eliminate uncertainty?**

**Answer**: NO ❌

**Rationale**: TOOL_FAILED only proves the delegate threw an exception. It does NOT prove external rollback.

**Example scenario**:
```
HTTP POST sent to external API
External server commits transaction
Network drops before response
Client times out (60 seconds)
delegate.call() throws TimeoutException
Framework emits: TOOL_FAILED

External truth: Side effect COMMITTED
Arctra truth: TOOL_FAILED (delegate threw)
```

**Conclusion**: TOOL_FAILED does NOT mean "safe to retry without idempotency"

---

**Question 3: Does timeout eliminate uncertainty?**

**Answer**: NO ❌

**Rationale**: Timeout proves nothing about external state. The external system may have:
- Never received the request (network failure before send)
- Received and committed (slow processing)
- Received and rolled back (processing failure)

**Timeout = Maximum uncertainty**

---

**Question 4: Does network exception eliminate uncertainty?**

**Answer**: NO ❌

**Rationale**: Network exceptions can occur at multiple points:
- Before request sent → No effect (safe)
- After request sent, before response → UNKNOWN ⚠️
- After server committed, response lost → Effect committed ⚠️

**Arctra cannot distinguish these cases without external cooperation**

---

**Question 5: Does process crash eliminate certainty?**

**Answer**: YES ❌ (crash CREATES uncertainty)

**Rationale**: Crash between T9-T15 means:
- delegate.call() may have started
- External side effect may have occurred
- No TOOL_EXECUTED/TOOL_FAILED recorded
- Checkpoint remains pending

**This is the central M6-T4 problem**

---

**Question 6: Does successful return prove external commit?**

**Answer**: NO ❌

**Rationale**: delegate.call() returning successfully only proves:
- Delegate code completed without throwing
- Delegate returned a value

**It does NOT prove**:
- External transaction committed
- Async operation completed
- External validation succeeded

**Example**:
```java
// Tool returns "accepted" but processing is async
String result = externalApi.submitJob(data);
// result = "job-123-accepted"
return result;
// External processing may fail later
```

**TOOL_EXECUTED ≠ External business completion**

---

**Question 7: Does external receipt prove external commit?**

**Answer**: DEPENDS ⚠️

**Rationale**: Receipt semantics vary:
- **Strong receipt**: Transaction ID from committed database transaction ✅
- **Weak receipt**: "Request received" acknowledgment ❌
- **Idempotency key**: Proves duplicate detection, not commit ⚠️

**Conclusion**: Receipt authority depends on external system contract

---

**Question 8: Who is authoritative for external side-effect truth?**

**Answer**: THE EXTERNAL SYSTEM ✅

**Rationale**: Only the external system knows:
- Whether transaction committed
- Whether operation completed
- Whether side effect persists

**Arctra options**:
1. **Idempotency key** — External system deduplicates
2. **Receipt/transaction ID** — External system provides commit proof
3. **Query API** — Arctra queries external state after crash
4. **None** — Operator intervention for non-idempotent operations

**Frozen limitation**: Arctra cannot be authoritative for external truth

---

### Refined Definition

**Uncertain Outcome** (refined):
> An operation has an uncertain outcome when:
> 1. Arctra's durable state indicates the operation is pending OR
> 2. Physical invocation may have reached the external system (post-T9) AND
> 3. No authoritative external evidence (receipt, idempotency confirmation, query result) determines commit truth

**Key insight**: Uncertainty begins at delegate.call() entry and persists until:
- External receipt obtained, OR
- External query succeeds, OR
- Idempotency contract guarantees safety, OR
- Operator confirms state

---

## F. TOOL_EXECUTED Semantics

### Frozen M6-T2B Definition

**TOOL_EXECUTED**:
> Framework observed the ToolCallback invocation return normally

**What it means**:
- `delegate.call()` returned (did not throw)
- Framework received return value
- Evidence captured (if configured)

**What it does NOT mean**:
- External transaction committed ❌
- External side effect persists ❌
- External validation succeeded ❌
- Async processing completed ❌
- Safe to mark operation "done" without verification ❌

### TOOL_EXECUTED Is Also Uncertain

**Example 1: Async processing**
```
delegate.call() → externalApi.submitJob(data)
returns: "job-123-queued"
Framework: TOOL_EXECUTED ✅

External reality: Job processing not started yet
External outcome: May succeed or fail later
```

**Example 2: Fire-and-forget**
```
delegate.call() → messageBroker.publish(event)
returns: "published"
Framework: TOOL_EXECUTED ✅

External reality: Message in queue, not yet consumed
External outcome: Consumer may fail
```

**Example 3: Eventually consistent write**
```
delegate.call() → cacheWrite(key, value)
returns: "written"
Framework: TOOL_EXECUTED ✅

External reality: Cache propagation in progress
External outcome: Read-your-write not guaranteed
```

### TOOL_EXECUTED Scope

**Execution outcome** (what TOOL_EXECUTED captures):
- Framework invocation completed
- Delegate code path succeeded
- No exception thrown

**Business outcome** (what TOOL_EXECUTED does NOT capture):
- External transaction success
- Async operation completion
- External validation
- Durable persistence

**M6-T4 scope decision**: Focus on **invocation uncertainty**, not full business-outcome semantics

**Rationale**: Business outcome depends on tool-specific semantics. Framework can only know invocation outcome.

---

## G. TOOL_FAILED Semantics

### Frozen M6-T2B Definition

**TOOL_FAILED**:
> Framework observed the ToolCallback invocation throw

**What it means**:
- `delegate.call()` threw exception
- Framework caught exception
- No evidence captured

**What it does NOT mean**:
- External side effect definitely absent ❌
- Safe to retry without idempotency ❌
- External transaction rolled back ❌
- External system never received request ❌

### TOOL_FAILED Is Potentially Uncertain

**Critical insight**: Exception does NOT prove no side effect

**Example 1: Timeout after commit**
```
POST /api/transfer {"from":"A","to":"B","amount":100}
  ↓
Server receives request
Server commits database transaction
Response delayed (slow network)
Client timeout (60s)
delegate.call() throws TimeoutException

Framework: TOOL_FAILED ❌
External reality: Transaction COMMITTED ✅
Account B: +$100 ✅
```

**Example 2: Connection reset after write**
```
delegate.call() → socket.write(data)
Server receives and processes
Connection reset before ACK
delegate.call() throws IOException

Framework: TOOL_FAILED ❌
External reality: Data RECEIVED and PROCESSED ✅
```

**Example 3: Transient error after partial success**
```
Batch operation: insertRows(1000 rows)
500 rows inserted successfully
Database connection lost
delegate.call() throws SQLException

Framework: TOOL_FAILED ❌
External reality: 500 rows COMMITTED ✅
```

### TOOL_FAILED ≠ Safe to Retry

**Automatic retry after TOOL_FAILED is UNSAFE without**:
1. Idempotency key contract, OR
2. External rollback verification, OR
3. Read-your-write query, OR
4. Tool declaration "safe to retry"

**Without these, retry may**:
- Duplicate side effect (e.g., double payment)
- Violate business invariants
- Create inconsistent state

### Exception Classification

**Exceptions that MAY indicate no side effect**:
- Validation failures (before external call)
- Authentication failures (before external call)
- Rate limit (before processing)

**Exceptions that are UNCERTAIN**:
- Timeout ⚠️
- Network/IO errors ⚠️
- Connection reset ⚠️
- Server 500/502/503 ⚠️

**Arctra cannot safely distinguish these automatically**

---

## H. External Side-Effect Authority

### Separation of Concerns

**Arctra authorities**:
1. **Process lifecycle** — resume/suspend/completion
2. **Execution observation** — what the framework observed
3. **Operation identity** — operationId correlation

**External system authorities**:
1. **Transaction commit truth** — did the transaction commit?
2. **Business operation outcome** — did the business logic succeed?
3. **Side effect persistence** — does the effect still exist?

**Frozen boundary**: Arctra cannot be authoritative for external truth

### External Cooperation Mechanisms

**Option 1: Idempotency Key**

External system contract:
> Requests with same idempotency key are deduplicated. Only first request is processed. Subsequent requests return same result.

**Arctra's role**: Provide stable key (e.g., operationId)

**External system's role**: 
- Detect duplicate
- Return idempotent response
- Guarantee at-most-once processing

**Example**:
```
POST /api/payment
Headers: Idempotency-Key: op-abc-123

First request: Processes payment, returns {"status":"completed"}
Retry request: Detects duplicate, returns same {"status":"completed"}
```

---

**Option 2: External Receipt**

External system contract:
> Upon commit, return durable transaction/operation ID that can be queried later

**Arctra's role**: Persist receipt, query on restart

**External system's role**:
- Provide transaction ID after commit
- Support query API: GET /transaction/{id}
- Return authoritative state

**Example**:
```
POST /api/order → {"orderId":"ord-456","status":"confirmed"}

After restart:
GET /api/order/ord-456 → {"status":"shipped"}
```

---

**Option 3: Query API**

External system contract:
> Support query by business key to determine operation outcome

**Arctra's role**: Query before retry

**External system's role**:
- Support lookup by business identifier
- Return authoritative state

**Example**:
```
Operation: Transfer $100 from A to B

After crash:
Query: GET /accounts/A/transactions?date=today
Response: [..., {"to":"B","amount":100,"status":"completed"}]
```

---

**Option 4: None (Non-Idempotent)**

**No external cooperation**

**Characteristics**:
- No idempotency key support
- No receipt/transaction ID
- No query API

**Arctra options**:
- **Manual intervention** — Operator verifies external state
- **Business reconciliation** — Separate process reconciles
- **Accept duplicate risk** — Retry and handle duplicates in business logic

**Example**: Legacy SOAP service with no idempotency support

---

### Authority Matrix

| Authority Question | Who Knows | How Arctra Learns |
|--------------------|-----------|-------------------|
| Did delegate.call() return? | Arctra | Direct observation (TOOL_EXECUTED) |
| Did delegate.call() throw? | Arctra | Direct observation (TOOL_FAILED) |
| Did external API receive request? | External system | Receipt / query / idempotency response |
| Did external transaction commit? | External system | Receipt / query / idempotency response |
| Is external state consistent? | External system | Query / reconciliation |
| Is retry safe? | Depends on tool type | Idempotency contract / tool declaration |

**Frozen principle**: Arctra observes invocation. External system owns commit truth.

