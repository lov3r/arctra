# M6-T4B INVOCATION RECOVERY VISIBILITY & PERSISTENCE ARCHITECTURE GATE

**Gate Type**: Architecture / Source-Truth Decision Gate  
**Status**: ✅ **ANALYSIS COMPLETE**  
**Date**: 2024  
**Author**: lov3r (via Claude Code)

---

## EXECUTIVE SUMMARY

**Mission**: Determine the minimum next capability to turn INVOCATION_INTENT from write-only execution gate into usable recovery authority.

**Current Gap**: Arctra can WRITE invocation intent but cannot READ it for recovery. InMemoryInvocationStateStore loses all state on JVM restart.

**Selected Architecture**: **CANDIDATE D — RECOVERY READ + PERSISTENCE CONTRACT**

**Rationale**: 
- Recovery needs READ visibility to classify operations (DEFINITELY_NOT_DISPATCHED vs MAY_HAVE_INVOKED)
- Restart recovery requires persistence-capable contract (not immediate JDBC implementation)
- attemptId does NOT solve visibility problem and lacks current consumer
- Read + persistence contract is minimum viable next slice

**Decision**: **GO — M6-T4B IMPLEMENTATION MAY BEGIN**

---

## A. FROZEN BASELINE

### M6-T4A.1 Verified Regression
```
arctra-core:          205 tests
arctra-runtime-react: 149 tests
examples:              35 tests
────────────────────────────────
TOTAL:                389 tests
Failures:               0
Errors:                 0
Skipped:               23
────────────────────────────────
BUILD SUCCESS
```

### Closed Milestones
- **M6-T3A**: Durable Logical Tool Operation Identity — ✅ CLOSED
- **M6-T3B**: Authoritative Execution-Boundary Correlation — ✅ CLOSED
- **M6-T3B.1**: ToolContext Semantic-Parity Verification — ✅ CLOSED
- **M6-T4**: Durable Tool Execution Crash Window Architecture Gate — ✅ CLOSED
- **M6-T4.1**: Invocation Intent Recovery Authority Selection — ✅ CLOSED
- **M6-T4A**: Invocation Intent Foundation Implementation — ✅ CLOSED
- **M6-T4A.1**: Mandatory Invocation Gate No-Bypass Closure — ✅ CLOSED

---

## B. FROZEN M6-T4A AUTHORITY MODEL

### Authorities (Unchanged)

**Checkpoint**: Resumable process / suspension recovery state authority  
**InvocationStateStore**: Recovery-critical invocation-intent authority (NEW in M6-T4A)  
**ExecutionLedger**: Execution history authority  
**ExecutionEvent**: Best-effort execution observation/distribution  
**External System**: External business commit truth

### Critical Boundaries

```
InvocationStateStore ≠ CheckpointStore
InvocationStateStore ≠ ExecutionLedger
```

**DO NOT**:
- Move INVOCATION_INTENT into Checkpoint
- Use Ledger event presence as recovery authority
- Treat event absence as authoritative

---

## C. CURRENT SOURCE-TRUTH STORE CONTRACT

### Interface

**Location**: `cn.bitcss.arctra.runtime.react.InvocationStateStore`  
**Visibility**: package-private  
**Dependencies**: None (provider-independent)

```java
interface InvocationStateStore {
  /**
   * Record durable invocation intent.
   * @throws InvocationIntentPersistenceException if persistence fails
   */
  void recordInvocationIntent(String processId, String operationId);
}
```

### Current Capability

**WRITE-ONLY**: Can record intent exists  
**NO READ**: Cannot query if intent exists  
**NO SCAN**: Cannot list operations with intent

### Implementation

**InMemoryInvocationStateStore**:
- ✅ Thread-safe (ConcurrentHashMap)
- ✅ Idempotent writes
- ❌ NOT restart durable (JVM-local)
- ❌ NOT cross-node durable
- ⚠️ Has `hasInvocationIntent()` but NOT part of contract (package-private test helper only)

---

## D. CURRENT CONSTRUCTION / OWNERSHIP PATH

### Source Truth (SpringAiToolCallingEngine:191-198)

```java
// M6-T4A: Create invocation-state store (one instance per engine, reused across executions)
InvocationStateStore invocationStateStore = new InMemoryInvocationStateStore();

// Pass to resumed execution handler
SpringAiResumedExecutionHandler resumedExecutionHandler =
    new SpringAiResumedExecutionHandler(chatModel, chatMemory, tools, governancePolicy, invocationStateStore);
```

### Ownership Chain

```
SpringAiToolCallingEngine
  ↓ creates (line 193)
InMemoryInvocationStateStore (local variable)
  ↓ passes to
SpringAiResumedExecutionHandler (constructor, line 197-198)
  ↓ stores as field
  ↓ passes to
ProtocolReconstructor (local instantiation in methods 209, 223)
  ↓ uses in
executeOperation() before delegate.call()
```

### Ownership Answers

1. **Who owns store lifecycle?** → SpringAiToolCallingEngine (creates, scopes to engine lifetime)
2. **Exactly one store per engine instance?** → YES
3. **Store survives multiple resumes within one engine?** → YES (same instance reused)
4. **Any way to inject another implementation today?** → NO (hardcoded `new InMemoryInvocationStateStore()`)
5. **Would persistent implementation require constructor/config pressure?** → YES (currently no injection point)
6. **Would exposing dependency publicly be premature?** → LIKELY YES (prefer internal for now)

---

## E. MISSING RECOVERY CAPABILITY

### Current State

**After M6-T4A.1**:
```
✅ Can record: INVOCATION_INTENT(processId, operationId)
✅ Mandatory gate: No physical invocation without successful intent
✅ Fail-fast: Missing context → NullPointerException
❌ Cannot read: Unknown if intent exists for operation
❌ Cannot classify: DEFINITELY_NOT_DISPATCHED vs MAY_HAVE_INVOKED
❌ No restart recovery: InMemory store loses state on crash
```

### Recovery Scenario

```
1. Process X suspended with pending operation op-A
2. Checkpoint persists (someday)
3. JVM crashes
4. JVM restarts
5. Resume attempt loads checkpoint
6. Recovery needs to know: Did op-A cross invocation gate?
   → CURRENTLY CANNOT ANSWER
```

### The Gap

**Write authority exists. Read authority missing.**

---

## F. RECOVERY CONSUMER ANALYSIS

### Who Needs to Read Intent?

**Primary Consumer**: Recovery classification logic

**Location**: Likely inside or adjacent to `DurableResumeCoordinator`

**When**: Between CHECK A (checkpoint loaded) and execution decision

### Conceptual Flow

```
CHECK A: load checkpoint v1
  ↓
For each PendingToolCall in pendingBatch:
  query InvocationStateStore.hasInvocationIntent(processId, operationId)
  ↓
  if NO intent:
    classify as DEFINITELY_NOT_DISPATCHED
    → safe to execute
  ↓
  if intent EXISTS:
    classify as MAY_HAVE_INVOKED
    → needs recovery decision (future: retry? query external? operator?)
```

### Current Blocker

**DurableResumeCoordinator** has no way to query intent because interface is write-only.

### Future Consumers (Deferred)

- **RecoveryPlanner** (not yet exists)
- **ToolExecutionRuntime** (not yet exists)
- **Operator UI** (future, out of scope)

**For M6-T4B**: Focus on establishing read visibility contract. Policy decisions deferred.

---

## G. MINIMAL RECOVERY CLASSIFICATION

### Two-State Model

**State 1**: `DEFINITELY_NOT_DISPATCHED`
- Pending operation exists
- NO invocation intent recorded
- Physical invocation never crossed gate
- **Safe to execute** (first attempt)

**State 2**: `MAY_HAVE_INVOKED`
- Pending operation exists
- Invocation intent EXISTS
- Physical invocation may have occurred (gate crossed)
- **Uncertain** → needs recovery policy (defer to future)

### What This Does NOT Classify

❌ SUCCEEDED (external outcome unknown)  
❌ FAILED (external outcome unknown)  
❌ COMMITTED (external system authority)  
❌ ROLLED_BACK (external system authority)  
❌ COMPLETED (confusion with process completion)

**This classification concerns INVOCATION UNCERTAINTY only, not external outcome certainty.**

### Correctness

**This minimal model is CORRECT for M6-T4B** because:
- Distinguishes never-attempted from possibly-attempted
- Does not claim to know external outcomes
- Provides foundation for future recovery policy
- Preserves authority boundaries

---

## H. CRASH CASE MATRIX

### Case A: Crash Before Intent

**Timeline**:
```
Checkpoint pending
Intent NOT recorded
Crash (before recordInvocationIntent)
```

**Recovery sees**:
- Checkpoint with pendingBatch=[op-A]
- InvocationStateStore: NO intent for op-A

**Classification**: `DEFINITELY_NOT_DISPATCHED`

**Recovery action**: Safe to execute (first physical attempt)

---

### Case B: Crash After Intent, Before delegate.call

**Timeline**:
```
recordInvocationIntent(proc-X, op-A) → SUCCESS
Crash (before delegate.call() entry)
```

**Recovery sees**:
- Checkpoint with pendingBatch=[op-A]
- InvocationStateStore: intent EXISTS for op-A
- Ledger: NO TOOL_EXECUTED, NO TOOL_FAILED

**Classification**: `MAY_HAVE_INVOKED`

**Why correct**: Recovery cannot distinguish "intent but never called" from "intent and called". Both must be treated as uncertain.

---

### Case C: Crash During delegate.call

**Timeline**:
```
recordInvocationIntent() → SUCCESS
delegate.call() ENTERED
Crash (inside delegate)
```

**Recovery sees**:
- Checkpoint with pendingBatch=[op-A]
- InvocationStateStore: intent EXISTS
- Ledger: NO outcome (crash before event emission)

**Classification**: `MAY_HAVE_INVOKED`

---

### Case D: Delegate Returned, Crash Before CHECK B

**Timeline**:
```
recordInvocationIntent() → SUCCESS
delegate.call() → returns normally
TOOL_EXECUTED event emitted (best-effort, may succeed or fail)
Crash (before CHECK B deleteIfVersion)
```

**Recovery sees**:
- Checkpoint with pendingBatch=[op-A]
- InvocationStateStore: intent EXISTS
- Ledger: TOOL_EXECUTED MAY OR MAY NOT exist (best-effort projection)

**Question**: Can InvocationStateStore alone distinguish this from Case B/C?

**Answer**: **NO**

**Why**: InvocationStateStore only knows intent EXISTS. It does NOT track outcomes. TOOL_EXECUTED is in Ledger (separate authority, best-effort).

**Classification**: `MAY_HAVE_INVOKED` (same as B/C)

**Implication**: InvocationStateStore read API CANNOT provide outcome certainty. That requires:
- Ledger query (best-effort history)
- External system query (authoritative commit)
- Both deferred to future recovery policy

---

## I. LEDGER INTERACTION

### Critical Question

**Can recovery safely decide**:
```
intent EXISTS
+ TOOL_EXECUTED missing in Ledger
= delegate did NOT return
```

**Answer**: **NO**

### Why Ledger Absence Is Not Authoritative

**ExecutionLedger** records historical events via best-effort projection:
1. TOOL_EXECUTED event emitted (M6-T2B)
2. ExecutionEventListener receives event
3. ExecutionLedgerListener projects to Ledger
4. Ledger.append() called

**Any step can fail**:
- Event emission failure (unlikely but possible)
- Listener exception (isolated, does not block execution)
- Ledger append failure (storage issue)

**Therefore**:
- TOOL_EXECUTED present → delegate likely returned (but external commit still unknown)
- TOOL_EXECUTED absent → **UNKNOWN** (event may have failed to record, OR delegate never returned)

### Frozen Principle

**DO NOT make ExecutionLedger part of recovery correctness.**

Ledger is history/audit/observability authority, NOT recovery state authority.

---

## J. CANDIDATE A — READ VISIBILITY FIRST

### Proposal

Extend InvocationStateStore minimally:

```java
interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId);
  
  // NEW
  boolean hasInvocationIntent(String processId, String operationId);
}
```

### Recovery Classification

```
PendingToolCall exists
+ hasInvocationIntent() = false
→ DEFINITELY_NOT_DISPATCHED

PendingToolCall exists
+ hasInvocationIntent() = true
→ MAY_HAVE_INVOKED
```

### Advantages

✅ Solves current recovery visibility  
✅ Simple boolean API  
✅ InMemory implementation already has test-only version (promote to contract)  
✅ No attemptId complexity yet  
✅ Provider-independent  
✅ Preserves authority model

### Limitations

❌ InMemory still not restart-durable  
❌ Persistent implementation still needed for true restart recovery  
❌ No guidance on persistence semantics

### Assessment

**Candidate A is INCOMPLETE**. Adds read visibility but defers persistence contract definition, which means:
- Future persistent implementation may have incompatible semantics
- Read failure behavior undefined
- Durability requirements unclear

---

## K. CANDIDATE B — ATTEMPTID FIRST

### Proposal

Introduce attemptId representing one physical execution attempt of one logical operation:

```
operationId = logical operation identity
attemptId = one physical invocation attempt
```

**Conceptual**:
```
operationId: op-A
  ├─ attemptId: attempt-1 (first try)
  ├─ attemptId: attempt-2 (retry)
  └─ attemptId: attempt-3 (retry)
```

### Questions

**Does attemptId solve read visibility?**

**Answer**: **NO**

attemptId is per-attempt correlation. Recovery still needs:
```
Has operation op-A ever crossed invocation gate?
```

This question does NOT require knowing which specific attempt. It only requires:
```
Does ANY intent exist for op-A?
```

**Does attemptId solve restart durability?**

**Answer**: **NO**

attemptId is correlation identity. Persistence is separate concern.

### When IS attemptId Needed?

**Retry scenarios**:
- Correlate which attempt succeeded/failed
- Per-attempt timeout tracking
- Per-attempt idempotency keys
- Per-attempt external receipts

**Current consumer**: **NONE**

Arctra does NOT yet have:
- Retry logic
- Retry policy
- Attempt-level timeout
- Attempt-level idempotency

### Classification

**attemptId** = **JUSTIFIED NEXT** (after retry is introduced)

**Not REQUIRED NOW** because:
- Does not solve read visibility
- Does not solve persistence
- No current consumer exists

### Assessment

**Candidate B is WRONG**. Introduces complexity without solving current gap.

---

## K. CANDIDATE C — PERSISTENCE FIRST

### Proposal

Keep existing store contract (write-only) but introduce durable implementation:
- JDBC-backed InvocationStateStore
- Redis-backed InvocationStateStore

**NO read API added yet.**

### Questions

**Does persistence solve recovery visibility?**

**Answer**: **NO**

Persistent store can WRITE intent durably, but recovery still cannot READ it.

**Scenario**:
```
1. Record intent(op-A) → persists to JDBC
2. Crash
3. Restart
4. Load checkpoint
5. Want to know: Did op-A have intent?
   → STILL CANNOT ANSWER (no read API)
```

### Assessment

**Candidate C is INCOMPLETE**. Persistence without read API is useless for recovery.

---

## L. CANDIDATE D — READ + PERSISTENCE CONTRACT

### Proposal

Establish BOTH:
1. **Read visibility API**: `hasInvocationIntent(processId, operationId)`
2. **Persistence-capable contract**: Define durability semantics so persistent implementations can exist cleanly

**Does NOT mean immediate JDBC/Redis implementation.** Means designing contract correctly.

### Read API

```java
interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId);
  
  boolean hasInvocationIntent(String processId, String operationId) 
      throws InvocationStateReadException;
}
```

### Persistence Contract Semantics

**Successful `recordInvocationIntent()` means**:
> Intent fact has crossed backing store's durability boundary such that a subsequent process/JVM restart can recover it according to that implementation's advertised durability guarantees.

**InMemory implementation**:
- Durability boundary: JVM heap (not restart-durable)
- Advertised guarantee: "survives within JVM session only"

**Future JDBC implementation** (conceptual, not implemented):
- Durability boundary: Database transaction commit
- Advertised guarantee: "survives JVM restart if database persists"

**Future Redis implementation** (conceptual, not implemented):
- Durability boundary: Redis persistence configuration (AOF/RDB)
- Advertised guarantee: per Redis configuration

### Advantages

✅ Solves recovery read visibility  
✅ Defines persistence semantics upfront  
✅ InMemory can implement read API immediately  
✅ Future persistent implementations have clear contract  
✅ Does not force immediate JDBC/Redis work  
✅ Preserves at-least-once  
✅ No attemptId complexity yet

### Implementation Scope

**M6-T4B ONLY**:
1. Add `hasInvocationIntent()` to interface
2. Implement in InMemoryInvocationStateStore (already exists as test helper, promote)
3. Define read failure semantics
4. Document persistence contract requirements
5. NO actual JDBC/Redis implementation

**Deferred**:
- Actual JDBC implementation
- Actual Redis implementation
- Configuration/injection mechanism
- Public API exposure

### Assessment

**Candidate D is CORRECT**. Minimum viable next slice that:
- Enables recovery classification
- Prepares for future persistence
- Does not introduce premature complexity

---

## M. INVOCATIONSTATESTORE READ SEMANTICS

### Proposed Method

```java
/**
 * Check if invocation intent exists for operation.
 * 
 * @return true if intent recorded, false if definitely absent
 * @throws InvocationStateReadException if storage read fails
 */
boolean hasInvocationIntent(String processId, String operationId);
```

### Boolean Semantics

**`true`**: Intent definitely exists (gate was crossed)  
**`false`**: Intent definitely does NOT exist (gate never crossed)  
**`throws`**: Cannot determine (storage failure)

### Critical Question: Is Boolean Sufficient?

**Answer**: **YES** for current scope.

Recovery only needs two-state classification:
- Intent absent → NOT_DISPATCHED
- Intent present → MAY_HAVE_INVOKED

Future attempt-level details (attemptId) do not change this boolean fact.

### Edge Cases

**Missing process**: `hasInvocationIntent("unknown-proc", "op-A")` → return `false` (no intent for unknown process)

**Invalid IDs**: null/blank → throw `IllegalArgumentException` (fail fast, same as write)

**Storage failure**: Cannot read → throw `InvocationStateReadException` (do NOT return false)

---

## N. READ FAILURE SEMANTICS

### Critical Principle

**Storage read failure ≠ Intent absent**

### Scenario

```
Checkpoint loaded successfully
↓
InvocationStateStore read fails (network error, database down, etc.)
↓
What should happen?
```

**WRONG**:
```java
try {
    boolean hasIntent = store.hasInvocationIntent(proc, op);
} catch (InvocationStateReadException e) {
    // WRONG: assume false
    hasIntent = false;
}
```

**Why wrong**: Unknown ≠ Absent. Treating read failure as "no intent" could cause re-execution of already-invoked operations.

**CORRECT**:
```java
try {
    boolean hasIntent = store.hasInvocationIntent(proc, op);
} catch (InvocationStateReadException e) {
    // Read failed - cannot determine recovery fact
    // MUST NOT assume absent
    throw new ResumePreparationException(
        "Cannot determine invocation state for recovery", e);
}
```

### Fail-Closed Principle

**Cannot establish recovery fact → Do NOT physically invoke**

Similar to CHECK A: If checkpoint cannot be loaded, do not proceed. If intent state cannot be read, do not proceed (or defer to recovery policy).

### Exception Design

**New exception**: `InvocationStateReadException` (package-private, runtime)

**NOT**:
- Public API exception
- Exception hierarchy
- Generic `RuntimeException`

**Thrown by**: `hasInvocationIntent()` when storage read fails

**Caught by**: Recovery classification logic (likely DurableResumeCoordinator or adjacent)

---

## O. PERSISTENCE SEMANTICS

### Durability Contract

**Successful `recordInvocationIntent()`** means:

> Intent fact is durably recorded according to implementation's advertised guarantees.

**NOT**:
- Global consensus across all nodes
- Synchronous replication complete
- Write-ahead log flushed to all replicas

**Implementation-specific examples**:

**InMemory**:
- Durability: JVM heap
- Guarantee: Survives within JVM session
- Lost on: JVM restart

**JDBC** (future, conceptual):
- Durability: Database transaction commit
- Guarantee: Survives JVM restart if DB persists
- Lost on: Database data loss

**Redis** (future, conceptual):
- Durability: Per Redis configuration (AOF, RDB)
- Guarantee: Per Redis persistence settings
- Lost on: Redis data loss

### Persistence Failure

**If `recordInvocationIntent()` throws**:
- Physical invocation MUST NOT proceed (M6-T4A.1 frozen invariant)
- Exception: `InvocationIntentPersistenceException`

**Restart scenario**:
```
Record intent succeeded previously
→ JVM restarts
→ Read invocation state fails (database down)
→ ???
```

**Recovery SHOULD fail closed**: Cannot read state → Cannot classify → Do not execute (or defer to operator/policy).

---

## P. INMEMORY IMPLEMENTATION ROLE

### Current Status

**InMemoryInvocationStateStore** already has:
```java
// Package-private test helper (line 94-97)
boolean hasInvocationIntent(String processId, String operationId) {
    Set<String> processIntents = intents.get(processId);
    return processIntents != null && processIntents.contains(operationId);
}
```

### M6-T4B Action

**Promote to contract**:
1. Add `hasInvocationIntent()` to `InvocationStateStore` interface
2. InMemory implementation already has method → now implements contract
3. Update Javadoc to clarify JVM-local limitations

### Role

**InMemory remains**:
- ✅ Reference implementation
- ✅ Test implementation
- ✅ Single-JVM validation
- ❌ NOT for production restart recovery

### Documentation

**MUST state clearly**:
- JVM-local only
- State lost on restart
- For testing and single-JVM scenarios
- Production needs persistent implementation

---

## Q. CONSTRUCTION / CONFIGURATION PRESSURE

### Current Construction

```java
// SpringAiToolCallingEngine line 193
InvocationStateStore invocationStateStore = new InMemoryInvocationStateStore();
```

**Hardcoded. No injection.**

### Pressure for Persistent Implementation

**To support JDBC/Redis**, need:
```java
// Conceptual - NOT implemented in M6-T4B
InvocationStateStore invocationStateStore = 
    config.invocationStore() != null 
        ? config.invocationStore() 
        : new InMemoryInvocationStateStore();
```

### Options

**Option 1**: Add public constructor parameter
- ❌ Public API pressure
- ❌ Breaks existing constructors
- ❌ Exposes internal authority prematurely

**Option 2**: Internal configuration object
- ⚠️ Adds internal complexity
- ✅ No public API change

**Option 3**: Builder/factory pattern
- ⚠️ Major refactoring
- ✅ Future-proof

**Option 4**: Defer injection until needed
- ✅ YAGNI
- ✅ No premature API
- ⚠️ Future work when JDBC is ready

### M6-T4B Decision

**Option 4: Defer**

**Rationale**:
- M6-T4B adds read contract, NOT persistent implementation
- No actual JDBC/Redis code in M6-T4B
- Construction pressure is future concern when persistent store is implemented
- Prefer `public API delta = 0`

**When to address**: M6-T4C or later when persistent store implementation begins.

---

## R. ATTEMPTID RE-EVALUATION

### Current Identities (Frozen)

- **processId**: Process identity
- **checkpointVersion**: Recovery generation (checkpoint version)
- **operationId**: One logical durable tool operation (M6-T3A)
- **toolCallId**: Provider protocol correlation (Spring AI)
- **recordId/sequence**: Ledger history identity/order

### Potential Future

- **attemptId**: One physical invocation attempt of one logical operation

### Questions

**Is attemptId required to answer**: "Has this operation ever crossed INVOCATION_INTENT gate?"

**Answer**: **NO**

Recovery classification only needs:
```
hasInvocationIntent(processId, operationId) → boolean
```

Whether one attempt or multiple attempts crossed gate does NOT matter for classification.

**Is attemptId required for**:
- Multiple retries? → **YES**
- Per-attempt timeout? → **YES**
- Per-attempt outcome tracking? → **YES**
- Per-attempt external receipts? → **YES**
- Per-attempt idempotency keys? → **YES**

### Current Consumer

**Does Arctra have**:
- Retry logic? → **NO**
- Retry policy? → **NO**
- Attempt-level timeout? → **NO**
- Attempt-level receipts? → **NO**

**Current consumer for attemptId**: **NONE**

### Classification

**attemptId** = **JUSTIFIED NEXT** (unchanged from M6-T4A)

**Rationale**:
- NOT required for M6-T4B recovery read visibility
- NOT required for persistence contract
- Required for retry correlation (future)
- Introduce when retry logic is designed

---

## S. CONCURRENT RESUME IMPACT

### Current Behavior (Frozen)

```
Worker A: load checkpoint v1 → record intent(op-A) → execute → CHECK B
Worker B: load checkpoint v1 → record intent(op-A) → execute → CHECK B
One CHECK B wins, one conflicts
```

**At-least-once preserved. No claiming.**

### M6-T4B Read API Impact

**New capability**:
```java
boolean hasIntent = store.hasInvocationIntent(proc, op-A);
```

**Question**: Could concurrent workers use this to suppress execution?

**Scenario**:
```
Worker A: hasIntent(op-A) = false → execute
Worker B: hasIntent(op-A) = true (A just recorded) → DO NOT execute?
```

**WRONG USAGE**: Using `hasIntent()` as claiming mechanism.

### Correct Usage

**Read API is for RECOVERY classification** (after restart), NOT for runtime claiming.

**At runtime** (no restart):
- Both workers proceed through normal CHECK A → execute → CHECK B flow
- `hasIntent()` not queried during normal execution
- At-least-once preserved

**After restart** (recovery):
- Load checkpoint
- Query `hasIntent()` to classify
- Recovery policy decides (future work)

### Decision

**Concurrent at-least-once** = **PRESERVED**

Read API does NOT introduce claiming if used correctly for recovery only.

---

## T. RECOVERY BOUNDARY OWNERSHIP

### Where Should Classification Occur?

**Conceptual flow**:
```
DurableResumeCoordinator.resume()
  ↓
CHECK A: load checkpoint
  ↓
FOR EACH PendingToolCall:
    classify invocation state
    ↓
    if NOT_DISPATCHED:
        proceed to execution
    ↓
    if MAY_HAVE_INVOKED:
        ??? (recovery policy decision - future)
```

### Ownership Options

**Option A**: Inside `DurableResumeCoordinator`
- ⚠️ Risk: Coordinator becomes recovery policy owner
- ✅ Has access to checkpoint
- ✅ Controls resume flow

**Option B**: Before `RuntimeBinding` resolution
- ⚠️ Too early (need binding to execute)

**Option C**: After `RuntimeBinding` resolution
- ⚠️ Binding may be unnecessary if operation uncertain

**Option D**: Inside future `RecoveryPlanner`
- ✅ Clean separation: Coordinator delegates to planner
- ❌ Planner does not exist yet

**Option E**: Narrow internal classifier
- ✅ Single responsibility
- ✅ Coordinator uses but does not own policy
- ✅ Can be extracted/promoted later

### M6-T4B Decision

**Option E or defer to implementation**

**Principle**: **Coordinator MUST NOT become recovery policy engine**.

Coordinator orchestrates resume flow. Classification and policy should be separate concerns.

**For M6-T4B**: Define read API contract. Exact ownership can be determined during implementation based on cleanest boundaries.

---

## U. CORE IMPACT

### Expected

**arctra-core production delta** = 0

### Verification

M6-T4B changes:
1. `InvocationStateStore` interface (runtime-react, package-private)
2. `InMemoryInvocationStateStore` implementation (runtime-react, package-private)
3. Possible new exception (runtime-react, package-private)

**No changes to**:
- PendingToolCall
- SuspensionCheckpoint
- CheckpointStore
- AgentProcess
- ProcessFactory
- EventType
- ExecutionEvent

### Decision

**Core delta** = **0** ✅

---

## V. PUBLIC API IMPACT

### Expected

**Public API delta** = 0

### Changes

**All M6-T4B changes are package-private**:
- `InvocationStateStore`: package-private interface
- `InMemoryInvocationStateStore`: package-private class
- `InvocationStateReadException`: package-private exception (if added)

**No public constructor changes**:
- `SpringAiToolCallingEngine`: constructors unchanged
- Store creation remains internal

### Decision

**Public API delta** = **0** ✅

---

## W. MODULE OWNERSHIP DECISION

### Current

**InvocationStateStore** is package-private in `cn.bitcss.arctra.runtime.react`.

### Questions

1. **Is that still correct once recovery consumers appear?** → **YES** (recovery consumer likely in same package)
2. **Will DurableResumeCoordinator need access?** → **YES** (same package, can access)
3. **Is Coordinator in the same package?** → **YES** (`cn.bitcss.arctra.runtime.react`)
4. **Is provider-independent runtime-react ownership still coherent?** → **YES** (InvocationStateStore has no Spring AI dependencies)
5. **Has pressure emerged to promote to core?** → **NO** (single execution engine, no cross-engine sharing yet)

### Decision

**KEEP INTERNAL** (package-private in runtime-react)

**Rationale**:
- Recovery consumer (Coordinator) in same package
- Provider-independent (no Spring AI types)
- Single execution engine implementation
- No cross-engine pressure yet
- Premature to expose publicly

**When to promote**: If/when multiple execution engines need shared invocation-state authority.

---

## X. CANDIDATE COMPARISON TABLE

| Dimension | A: Read Visibility | B: attemptId First | C: Persistence First | D: Read + Persistence Contract |
|-----------|-------------------|-------------------|---------------------|-------------------------------|
| **Solves current recovery visibility** | ✅ YES | ❌ NO | ❌ NO | ✅ YES |
| **Survives restart conceptually** | ❌ NO (InMemory only) | ❌ NO | ✅ YES (but unreadable) | ✅ YES (read + persist) |
| **Useful without recovery policy** | ✅ YES (enables classification) | ❌ NO (no consumer) | ❌ NO (cannot read) | ✅ YES (enables classification) |
| **Requires public API** | ❌ NO | ❌ NO | ⚠️ MAYBE (injection) | ❌ NO |
| **Core impact** | 0 | 0 | 0 | 0 |
| **Runtime-react impact** | LOW (add method) | HIGH (new identity) | MEDIUM (new impl) | LOW (add method + doc) |
| **Construction pressure** | NONE | NONE | HIGH (injection) | DEFER (no impl yet) |
| **Preserves authority model** | ✅ YES | ✅ YES | ✅ YES | ✅ YES |
| **Preserves at-least-once** | ✅ YES | ✅ YES | ✅ YES | ✅ YES |
| **Requires attemptId** | ❌ NO | ✅ YES | ❌ NO | ❌ NO |
| **YAGNI** | ⚠️ PARTIAL (no persist doc) | ❌ NO (premature) | ⚠️ PARTIAL (no read) | ✅ YES (min viable) |
| **Enterprise direction** | ⚠️ INCOMPLETE | ❌ WRONG ORDER | ⚠️ INCOMPLETE | ✅ CORRECT |

### Score

- **Candidate A**: 5 ✅, 3 ❌, 4 ⚠️ (incomplete)
- **Candidate B**: 3 ✅, 6 ❌, 3 ⚠️ (wrong)
- **Candidate C**: 4 ✅, 5 ❌, 3 ⚠️ (incomplete)
- **Candidate D**: 11 ✅, 0 ❌, 1 ⚠️ (correct)

---

## Y. SELECTED NEXT SLICE

**CANDIDATE D — RECOVERY READ + PERSISTENCE CONTRACT**

### Why D Wins

1. **Solves recovery visibility**: `hasInvocationIntent()` enables classification
2. **Prepares for persistence**: Defines durability semantics upfront
3. **Does not force premature work**: No actual JDBC implementation required
4. **Minimum viable**: Smallest slice that unblocks recovery
5. **Preserves all invariants**: At-least-once, authority model, no claiming
6. **YAGNI-compliant**: Only adds what's needed now
7. **Future-proof**: Persistent implementations can follow contract

### Why Not A

Candidate A adds read but defers persistence contract, meaning:
- Future persistent implementation may have incompatible semantics
- Read failure behavior left undefined
- Incomplete foundation

### Why Not B

Candidate B introduces attemptId without:
- Solving read visibility
- Solving persistence
- Having any current consumer

Premature complexity.

### Why Not C

Candidate C adds persistence but no read API:
- Cannot query intent for recovery
- Useless for restart recovery without read

Incomplete.

---

## Z. SELECTED AUTHORITY INVARIANTS

### Unchanged Authorities

✅ Checkpoint = suspension recovery state  
✅ InvocationStateStore = invocation-intent authority (now readable)  
✅ ExecutionLedger = execution history  
✅ ExecutionEvent = best-effort observation  
✅ External System = external commit truth

### New Capability

**InvocationStateStore gains READ authority**:
- Can now answer: "Did invocation intent exist?"
- Enables recovery classification
- Does NOT become outcome authority
- Does NOT track TOOL_EXECUTED/FAILED

### Boundaries Preserved

```
InvocationStateStore ≠ CheckpointStore
InvocationStateStore ≠ ExecutionLedger
InvocationStateStore ≠ External System
```

**Ledger absence NOT authoritative** (frozen principle)

---

## AA. EXACT M6-T4B IMPLEMENTATION SCOPE

### Problem

Recovery cannot classify pending operations as DEFINITELY_NOT_DISPATCHED vs MAY_HAVE_INVOKED.

### Fact

`INVOCATION_INTENT(processId, operationId)` can be recorded but not read.

### Authority

**InvocationStateStore** owns invocation-intent read authority.

### Read Contract

```java
interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId);
  
  /**
   * Check if invocation intent exists.
   * @return true if intent recorded, false if definitely absent
   * @throws InvocationStateReadException if storage read fails
   */
  boolean hasInvocationIntent(String processId, String operationId);
}
```

### Persistence Requirement

**Successful `recordInvocationIntent()`** means:
> Intent fact durably recorded according to implementation's advertised guarantees (JVM-local for InMemory, database for JDBC, etc.).

**Read failure semantics**:
> Storage read failure MUST throw exception, NOT return false. Unknown ≠ Absent.

### Recovery Consumer

Recovery classification logic (likely in/near DurableResumeCoordinator).

### Failure Semantics

**Write failure**: Blocks physical invocation (M6-T4A.1 frozen)  
**Read failure**: Must NOT assume intent absent → Fail closed or defer to recovery policy

### attemptId Status

**JUSTIFIED NEXT** (unchanged) — Not required for M6-T4B visibility.

### Core Impact

**0** — All changes in runtime-react, package-private.

### Public API Impact

**0** — No public constructor or API changes.

---

## AB. EXPLICIT NON-GOALS

**M6-T4B does NOT implement**:

❌ attemptId identity  
❌ Retry logic  
❌ Automatic retry  
❌ RecoveryPolicy  
❌ External receipt verification  
❌ External system query  
❌ Idempotency keys  
❌ Compensation  
❌ Operator intervention UI  
❌ Lease/fencing  
❌ Claiming  
❌ ToolExecutionRuntime  
❌ Generic workflow engine  
❌ Event sourcing  
❌ JDBC InvocationStateStore implementation  
❌ Redis InvocationStateStore implementation  
❌ Persistent storage configuration  
❌ Public recovery SPI  
❌ Checkpoint schema changes  
❌ ExecutionLedger as recovery authority  
❌ CHECK A/B semantic changes

**M6-T4B establishes read visibility contract and persistence semantics. Actual persistent implementations deferred.**

---

## AC. ENTRY GATE QUESTIONS

### 36 Mandatory Answers

**1. What recovery fact is currently unreadable?**  
✅ Whether INVOCATION_INTENT exists for a pending operation

**2. Who owns that fact?**  
✅ InvocationStateStore

**3. Does recovery need to distinguish missing intent from store failure?**  
✅ YES (unknown ≠ absent)

**4. Does missing intent mean definitely not dispatched?**  
✅ YES

**5. Does existing intent mean definitely invoked?**  
✅ NO (may have crashed after intent, before delegate)

**6. Does existing intent mean may have invoked?**  
✅ YES

**7. Can Ledger absence prove no invocation?**  
✅ NO (event projection is best-effort)

**8. Can TOOL_FAILED prove no external commit?**  
✅ NO (delegate threw, but external state unknown)

**9. Can TOOL_EXECUTED prove external commit?**  
✅ NO (delegate returned, but external commit still uncertain)

**10. Is hasInvocationIntent-style visibility needed?**  
✅ YES

**11. Is boolean sufficient for current scope?**  
✅ YES (two-state classification)

**12. Should read failure return false?**  
✅ NO (must throw exception)

**13. Must read failure block recovery execution?**  
✅ YES (or defer to policy) — fail closed

**14. Is persistent storage required for true restart recovery?**  
✅ YES (InMemory loses state on restart)

**15. Does InMemory store remain valid for reference/testing?**  
✅ YES

**16. Is attemptId required to detect MAY_HAVE_INVOKED?**  
✅ NO (boolean intent existence sufficient)

**17. Is attemptId required for multiple physical attempt correlation?**  
✅ YES (but deferred to retry)

**18. Is attemptId required NOW?**  
✅ NO (JUSTIFIED NEXT)

**19. Does selected slice preserve at-least-once?**  
✅ YES

**20. Does selected slice introduce claiming?**  
✅ NO

**21. Does selected slice introduce lease/fencing?**  
✅ NO

**22. Does Checkpoint authority change?**  
✅ NO

**23. Does ExecutionLedger authority change?**  
✅ NO

**24. Does External System authority change?**  
✅ NO

**25. Does CHECK A need semantic changes?**  
✅ NO

**26. Does CHECK B need semantic changes?**  
✅ NO

**27. Does checkpointVersion change?**  
✅ NO

**28. Does arctra-core need change?**  
✅ NO

**29. Does public API need change?**  
✅ NO

**30. Where should recovery classification live?**  
✅ In/near DurableResumeCoordinator, but NOT as policy owner

**31. Does DurableResumeCoordinator become recovery policy owner?**  
✅ NO (classification yes, policy no)

**32. Is ToolExecutionRuntime introduced?**  
✅ NO (deferred)

**33. Is persistent JDBC/Redis implementation part of T4B?**  
✅ NO (contract only)

**34. Is retry part of T4B?**  
✅ NO

**35. Is external reconciliation part of T4B?**  
✅ NO

**36. Is T4B sufficiently specified to implement?**  
✅ YES

---

## AD. DECISION

**✅ GO — M6-T4B IMPLEMENTATION MAY BEGIN**

### All Entry Requirements Met

✅ Read visibility solves current gap  
✅ Persistence contract defined  
✅ Preserves authority model  
✅ Preserves at-least-once  
✅ No claiming  
✅ No premature attemptId  
✅ Zero core impact  
✅ Zero public API impact  
✅ YAGNI-compliant  
✅ Minimum viable next slice

---

## HARD STOP

**M6-T4B ARCHITECTURE GATE COMPLETE**

**Awaiting**: Implementation execution approval.

**DO NOT IMPLEMENT** without explicit user approval to proceed.

---

**END OF M6-T4B INVOCATION RECOVERY VISIBILITY & PERSISTENCE ARCHITECTURE GATE**
