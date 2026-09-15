## P. Retry Safety

### Tool Classification (Conceptual)

**DO NOT implement enums yet** — This is semantic analysis only

**Category 1: Pure/Read-Only**
- No side effects
- Safe to retry indefinitely
- Examples: query, search, read

**Retry safety**: SAFE ✅

---

**Category 2: Idempotent Write**
- Side effect exists, but repeated execution safe
- Examples: PUT (replace), SET (overwrite), DELETE (idempotent)

**Retry safety**: SAFE ✅

---

**Category 3: Write with Idempotency Key**
- External system provides idempotency contract
- Tool provides stable key
- Examples: payment API with idempotency header

**Retry safety**: SAFE with external cooperation ✅

---

**Category 4: Non-Idempotent Side Effect**
- Repeated execution causes different outcome
- Examples: POST (create), transfer money, send email, increment counter

**Retry safety**: UNSAFE ❌

**Recovery options**:
- External query (if supported)
- Operator verification
- Business reconciliation
- Accept duplicate risk

---

**Category 5: Externally Queryable Operation**
- Non-idempotent, but outcome can be queried
- Examples: create order → query by order ID

**Retry safety**: SAFE with query ✅

---

### Can Arctra Infer Retry Safety?

**Answer**: NO ❌

**Rationale**:
- Tool implementation is opaque to framework
- HTTP POST may be idempotent (with external coordination) or not
- Same toolName may behave differently based on arguments
- External system contract unknown to Arctra

**Conclusion**: Tool must declare retry safety OR external contract must be provided

---

### Future Tool Contract (Conceptual)

**DO NOT implement yet**

**Possible API** (illustration only):
```java
@Tool(
  name = "createPayment",
  retrySafety = RetrySafety.IDEMPOTENT_WITH_KEY,
  idempotencyKeyExtractor = "args -> args.get('transactionId')"
)
```

**OR**:
```java
@Tool(name = "transferMoney", retrySafety = RetrySafety.UNSAFE)
@Tool(name = "queryBalance", retrySafety = RetrySafety.SAFE)
```

**Deferred to future milestones** — M6-T4 defines requirement, not API

---

## Q. Idempotency Semantics

### operationId vs Idempotency Key

**operationId** (Arctra framework identity):
- Meaning: "Which logical Arctra operation is this?"
- Owner: Arctra framework
- Stability: Stable across resume/retry within same process
- Scope: Arctra-internal correlation

**Idempotency key** (External system deduplication):
- Meaning: "Which external requests should the external system collapse?"
- Owner: External system contract
- Stability: Stable across ALL attempts (even different processes)
- Scope: External API contract

**DISTINCT semantics** — Do not conflate ⚠️

---

### Can operationId Be Used as Idempotency Key?

**Answer**: POSSIBLY, with caveats ⚠️

**When suitable**:
- External API accepts arbitrary opaque keys
- operationId format acceptable (e.g., "op-abc-123")
- operationId stable across all retry scenarios

**Concerns**:
1. **Retry across different processes**: operationId may differ
2. **Manual retry**: Operator may not have original operationId
3. **Business key vs technical key**: External system may require business-meaningful key

**Example where operationId works**:
```
POST /api/payment
Headers: Idempotency-Key: op-abc-123
Body: {"amount": 100, "from": "A", "to": "B"}
```

**Example where operationId does NOT work**:
```
External system expects: Idempotency-Key: txn-{date}-{from}-{to}-{amount}
Arctra provides: op-abc-123
Mismatch → External system cannot deduplicate
```

---

### Recommendation

**DO NOT automatically use operationId as idempotency key** ⚠️

**Better approach**:
- Tool declares idempotency key requirement
- Tool extracts key from arguments (business-meaningful)
- operationId available as fallback if tool permits

**Example**:
```java
// Tool implementation
@IdempotencyKey
String getIdempotencyKey(Map<String, Object> arguments) {
  // Business-meaningful key
  return String.format("txn-%s-%s-%s", 
    arguments.get("date"),
    arguments.get("from"),
    arguments.get("to"));
}
```

**Arctra may expose operationId to tool implementation, but tool decides whether to use it**

---

## R. External Receipt Semantics

### Definition

**Receipt**:
> Provider/tool-specific durable evidence that an external system accepted, committed, or can query a particular operation

**Examples**:
- Payment transaction ID: `"txn-12345"`
- Database transaction token: `"commit-abc-def"`
- Job submission ID: `"job-98765"`
- Message broker offset: `"partition-3-offset-47"`
- HTTP idempotency response: `{"idempotencyKey":"op-123","transactionId":"txn-456"}`

---

### Receipt Authority

**Who creates receipt?**: External system ✅

**Who persists it?**: Depends on recovery model ⚠️

**Options**:

**Option 1: Evidence**
- Receipt stored as Evidence
- Retrieved via Evidence query
- Model: Receipt is execution proof/content

**Option 2: Checkpoint recovery state**
- Receipt stored in checkpoint (if needed for resume)
- Retrieved via checkpoint load
- Model: Receipt is recovery-critical state

**Option 3: Separate receipt store**
- Dedicated OperationReceipt storage
- Retrieved via receipt query API
- Model: Receipt is tool-execution-specific state

**Option 4: Tool-opaque return value**
- Receipt embedded in tool result string
- Parsed by subsequent operations
- Model: Receipt is business data, not framework concern

---

### Can Receipt Resolve Uncertain Outcome?

**Answer**: YES, if external system provides strong receipt ✅

**Strong receipt scenario**:
```
POST /api/transfer → {"transactionId":"txn-456","status":"committed"}

After crash:
Query: GET /api/transaction/txn-456
Response: {"status":"completed","amount":100,"timestamp":"..."}

Conclusion: Transaction committed, operation succeeded
```

**Weak receipt scenario**:
```
POST /api/order → {"orderId":"pending-789","status":"processing"}

After crash:
Query: GET /api/order/pending-789
Response: {"status":"failed","error":"validation"}

Conclusion: Operation failed, safe to retry (if idempotent)
```

---

### Receipt vs Idempotency Key

**Idempotency key** (provided by Arctra):
- Purpose: Deduplicate requests
- Direction: Arctra → External system
- Timing: Sent with request

**Receipt** (provided by external system):
- Purpose: Prove commit or enable query
- Direction: External system → Arctra
- Timing: Returned in response

**Often used together**:
```
Request:
  POST /api/payment
  Headers: Idempotency-Key: op-abc-123
  Body: {...}

Response:
  {"transactionId":"txn-456","status":"completed"}

After crash:
Query with either:
  GET /api/payment?idempotencyKey=op-abc-123
  OR
  GET /api/transaction/txn-456
```

---

## S. Evidence Authority Impact

### Current Evidence Authority (Frozen)

**Evidence** (M4):
> Execution proof/content authority

**What it owns**:
- Tool execution results
- Model-visible proof/content
- Durable execution artifacts

**NOT used for**:
- Recovery decisions
- Process state
- Framework control flow

---

### Should Receipt Be Evidence?

**Analysis**:

**Arguments FOR**:
- Receipt is execution artifact ✅
- Durable storage already exists ✅
- Evidence is proof/content authority ✅

**Arguments AGAINST**:
- Receipt may be recovery-critical, not just proof ⚠️
- Evidence may be designed for model-visible content ⚠️
- Recovery queries may need different API than evidence query ⚠️

---

### Risk: Evidence Authority Expansion

**Current Evidence role**: Model-visible execution content

**With receipt**: Recovery-critical machine state

**Concern**: Evidence gains new authority without explicit design

**Example**:
```
Current Evidence usage:
  Tool "search" returns: "Found 5 results: ..."
  Evidence captured: "Found 5 results: ..."
  Purpose: Show to model in next turn

Receipt usage:
  Tool "payment" returns: {"transactionId":"txn-123"}
  Receipt captured: "txn-123"
  Purpose: Query external system after crash to determine commit
```

**Different semantics** ⚠️

---

### Recommendation

**DO NOT casually put receipts in Evidence** ⚠️

**Evaluation needed**:
1. Is receipt model-visible content? (YES → Evidence)
2. Is receipt recovery-critical machine state? (YES → Checkpoint or separate)
3. Is receipt both? (MAYBE → Dual storage or explicit Evidence extension)

**Deferred to implementation design** — M6-T4 identifies concern

---

## T. Recovery Decision Matrix

### Mandatory Decision Table

| Durable State | External Contract | Recovery Decision |
|---------------|-------------------|-------------------|
| **Never started** (no INVOCATION_INTENT) | None | **EXECUTE** — Safe, never ran |
| **Started, no outcome** (INVOCATION_INTENT, no TOOL_EXECUTED/FAILED) | None | **UNCERTAIN** — Operator intervention |
| **Started, TOOL_FAILED** | None | **UNCERTAIN** — Exception ≠ no side effect |
| **Started, TOOL_EXECUTED** | None | **UNCERTAIN** — Success ≠ commit proof |
| **Started, uncertain** | Idempotency key | **RETRY WITH KEY** — External deduplication |
| **Started, uncertain** | Queryable receipt | **QUERY THEN DECIDE** — External authority |
| **Started, uncertain** | Neither | **OPERATOR INTERVENTION** — Manual verification |

---

### Decision Semantics

**EXECUTE**: Safe to invoke delegate.call()

**RETRY WITH KEY**: Invoke with idempotency key, external system handles deduplication

**QUERY THEN DECIDE**: 
- Query external system with receipt/business key
- If committed: Mark complete (no re-execution)
- If rolled back: Safe to retry
- If uncertain: Escalate to operator

**OPERATOR INTERVENTION**: 
- Halt automatic recovery
- Require human verification
- External state check
- Manual decision to retry/skip/compensate

**RECONCILE**: 
- Background reconciliation process
- Compare Arctra state with external state
- Resolve discrepancies
- May require business logic

**WAIT**: 
- Async operation in progress
- Poll for completion
- Timeout triggers escalation

---

### Non-Idempotent Uncertain Outcome

**Scenario**:
```
Tool: "sendEmail" (non-idempotent)
State: INVOCATION_INTENT exists, no TOOL_EXECUTED
External contract: None (no idempotency, no query)

Recovery decision: ???
```

**Options**:

**Option 1: Operator intervention** ✅
- Halt automatic resume
- Require operator to verify (check sent emails manually)
- Operator decides: retry / skip / mark complete

**Option 2: Accept duplicate risk** ⚠️
- Retry automatically
- Email may be sent twice
- Business logic handles duplicates (e.g., email has "do not send twice" link)

**Option 3: Business reconciliation** ⚠️
- Retry creates potential duplicate
- Background process detects and resolves
- Requires business-specific logic

**Enterprise runtime recommendation**: Option 1 (operator intervention) for non-idempotent uncertain

---

## U. Operator Intervention

### Enterprise Reality

**Enterprise runtime must be honest when automation cannot determine truth** ✅

**Scenarios requiring intervention**:
1. Non-idempotent operation with uncertain outcome
2. External system unresponsive (cannot query)
3. Receipt lost (no transaction ID captured)
4. Conflicting external state (external data inconsistent)
5. Business rule violation (operation technically succeeded but violated business policy)

---

### Operator Decision Points

**Operator must answer**:
- Did the operation actually execute?
- Should it be retried?
- Should it be marked complete?
- Should it be compensated (reverse side effect)?
- Should the process fail?

---

### Required Operator Context

**Operator dashboard must provide**:
1. ProcessId and operation details
2. Checkpoint state (pendingBatch)
3. Ledger history (INVOCATION_INTENT, TOOL_EXECUTED/FAILED if any)
4. Tool arguments (for manual verification)
5. Evidence captured (if any)
6. External system contact info (where to verify)
7. Business context (what was being attempted)

**DO NOT design UI in M6-T4** — But define runtime semantic requirement ✅

---

### Operator Actions (Conceptual)

**Possible actions**:
- **Confirm Complete**: Mark operation as succeeded, remove from pending
- **Confirm Failed**: Mark operation as failed, remove from pending
- **Retry**: Execute again (with or without idempotency)
- **Skip**: Remove from pending without execution
- **Compensate**: Execute compensating action, then mark complete
- **Escalate**: Require higher-level decision

**Implementation deferred** — M6-T4 defines requirement

---
