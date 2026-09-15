# M6-T4D SECTIONS Q-V

## Q. Commit-Unknown Semantics

### Q.1 The Distributed Systems Reality

Persistent/distributed systems introduce a fundamental ambiguity:

```
Client → Server: WRITE request
Server: [processes, commits, prepares acknowledgement]
Network: [timeout / packet loss]
Client: Request timed out
```

**Client knows**: ❌ Nothing authoritative

**Possible states**:
1. Write never reached server (NOT committed)
2. Write reached server, failed validation (NOT committed)
3. Write committed, acknowledgement lost (COMMITTED but client doesn't know)

**This is NOT a framework bug. This is physics.**

### Q.2 Impact on Invocation Intent

**Scenario**:
```java
try {
  invocationStateStore.recordInvocationIntent(processId, operationId);
  // SUCCESS - proceed to delegate.call()
} catch (InvocationIntentPersistenceException e) {
  // FAILURE - do NOT call delegate
  // But: did the write actually commit?
}
```

**If exception means "definitely not committed"**: Safe (delegate blocked, no intent recorded)

**If exception means "commit unknown" (timeout)**: 
- Delegate NOT called (safe)
- Intent may actually be present (false positive on recovery)
- Recovery classification: MAY_HAVE_INVOKED (fail closed)
- **Result**: Safe conservative false positive

### Q.3 Commit-Unknown Safety for Intent

**Analysis**:

| Actual State | Framework Action | Recovery Sees | Classification | Safe? |
|--------------|-----------------|---------------|----------------|-------|
| Write committed | Delegate blocked | Intent present | MAY_HAVE_INVOKED | ✅ Conservative false positive |
| Write not committed | Delegate blocked | Intent absent | DEFINITELY_NOT_DISPATCHED | ✅ Correct |

**Conclusion**: ✅ **Intent commit-unknown is SAFE**

**Reason**: Worst case is false positive (fail closed), never false negative.

### Q.4 Commit-Unknown Safety for Checkpoint

**Scenario 1: create() timeout**

```java
try {
  checkpointStore.create(checkpoint); // Timeout
} catch (Exception e) {
  // Unknown: did checkpoint get created?
}
```

**Recovery Strategy**: Re-read checkpoint existence
- If exists: Proceed (idempotent create success)
- If not exists: Retry or fail

**Safety**: Can be reconciled with authoritative re-read.

**Scenario 2: deleteIfVersion() timeout**

```java
boolean deleted = checkpointStore.deleteIfVersion(processId, version);
// If this times out, did delete succeed?
```

**Current API Problem**: Boolean return cannot express "unknown"

**Recovery Strategy**: Re-read checkpoint
- If exists with same version: Delete did NOT succeed (retry)
- If not exists: Delete succeeded (completion achieved)
- If exists with different version: Concurrent modification (conflict)

**Safety**: ✅ Can be reconciled, but requires re-read logic

**Scenario 3: replaceIfVersion() timeout**

Similar to deleteIfVersion — re-read can reconcile.

### Q.5 Current API Gap

**CheckpointStore contract**:
```java
boolean replaceIfVersion(...);  // Returns true/false
boolean deleteIfVersion(...);   // Returns true/false
```

**Gap**: Cannot distinguish:
- `false` = "version mismatch (authoritative)"
- `unknown` = "commit outcome unknown (timeout)"

**Is this a blocker for T4D?**: ❌ **NO**

**Reasoning**:
1. In-memory implementation: timeout impossible
2. First persistent implementation: can handle via re-read in exception handler
3. Future refinement: Add OperationOutcome if needed

**Classification**: **JUSTIFIED LATER** (not T4D blocker)

---

## R. Cleanup / Retention Decision

### R.1 Cleanup API Status

**REQUIRED FOR NEXT SLICE**: ❌ **NO**

**Justification**:
- Orphan intents cannot affect recovery correctness (proven in Section H)
- New suspensions use new operationIds
- Storage hygiene ≠ recovery semantics

**Future Cleanup APIs** (when needed):

```java
interface InvocationStateStore {
  void recordInvocationIntent(String processId, String operationId);
  boolean hasInvocationIntent(String processId, String operationId);
  
  // Future additions:
  void deleteInvocationIntent(String processId, String operationId);
  void deleteByProcess(String processId);
  void deleteByProcessAndBefore(String processId, Instant cutoff);
}
```

**Classification**: **JUSTIFIED LATER** (lifecycle/retention milestone)

### R.2 Retention Semantics

**Question**: Do we need TTL, retention duration, or archival NOW?

**Answer**: ❌ **NO**

**Justification**:
- Intent accumulation is bounded by process execution rate
- Cleanup is operational hygiene, not recovery correctness
- Can be addressed in operationalization milestone

**Future Design**:
- TTL at database level (e.g., PostgreSQL `recorded_at + INTERVAL '30 days'`)
- Batch cleanup after process completion
- Archive to cold storage for audit

**Classification**: **DEFER** (not T4D scope)

---

## S. ExecutionLedger Interaction

### S.1 Can Ledger Be Used for Recovery?

**Question**: Could ExecutionLedger infer invocation state or clean up orphan intents?

**Answer**: ❌ **NO for recovery correctness**

**Frozen Truth** (from M6-T4):
```
ExecutionLedger = Best-effort execution history projection
NOT recovery authority
```

**Reasoning**:
1. Event projection may fail (isolated, does not affect execution truth)
2. Event absence ≠ operation never executed
3. TOOL_EXECUTED event ≠ external commit proof
4. Making ledger recovery-critical violates authority separation

### S.2 Ledger MAY Assist (Non-Authoritative)

**Acceptable uses**:
- **Diagnostics**: Operator investigation of uncertain operations
- **Cleanup hints**: "Process completed 7 days ago, safe to clean intents"
- **Audit trail**: Historical analysis

**NOT acceptable**:
- Recovery classification (use InvocationStateStore)
- Intent state inference (absence not authoritative)
- Automatic retry decisions

**Verdict**: ✅ **Keep ledger separate from recovery authority**

---

## T. ChatMemory Scope Decision

### T.1 ChatMemory Crash Window

**Current State**: ChatMemory persistence is Spring AI managed, separate from checkpoint/intent transactions.

**Crash Window**:
```
CHECK B (checkpoint delete/replace) succeeds
  ↓
chatMemory.add(sessionId, finalMessage)
  ↓ [CRASH HERE]
ChatMemory persistence may fail
```

**Impact**: Conversation continuity may be lost, but execution outcome already durable.

### T.2 Should ChatMemory Join T4D Substrate?

**Question**: Should ChatMemory be part of the transactional persistence substrate?

**Answer**: ❌ **NO — Keep separate**

**Justification**:

| Concern | Analysis |
|---------|----------|
| Recovery correctness | ChatMemory NOT needed for execution recovery |
| Conversation continuity | Important but separate concern from execution durability |
| Authority | ChatMemory owns conversation history, not execution state |
| Failure mode | Missing final message ≠ execution outcome unknown |
| Scope creep | Spring AI manages ChatMemory lifecycle |

**Current Semantics**:
- CHECK B commits → execution outcome durable
- chatMemory.add() → post-commit action (best-effort)
- Failure observable but does NOT invalidate execution completion

**Classification**: **SEPARATE MILESTONE** (conversation durability, not execution recovery)

### T.3 Future Conversation Durability

If conversation durability becomes critical:
1. Separate milestone: "M6-TX — Durable Conversation Continuity"
2. Analyze ChatMemory persistence semantics
3. Determine coordination with checkpoint (if any)
4. NOT part of T4D scope

**Verdict**: ✅ **Explicitly out of T4D scope**

---

## U. Configuration Ownership

### U.1 Current Construction Pressure

**SpringAiToolCallingEngine constructor** (8 parameters):
```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,           // Durable mode
    RuntimeBindingResolver bindingResolver,    // Durable mode
    String runtimeBindingKey,                  // Durable mode
    ExecutionLedger executionLedger            // Optional
)
```

**Internal construction**:
```java
// M6-T4A: Invocation state store constructed internally
InvocationStateStore invocationStateStore = new InMemoryInvocationStateStore();
```

**Persistent mode will require**:
- Persistent CheckpointStore implementation (needs DataSource)
- Persistent InvocationStateStore implementation (needs DataSource)
- Potentially shared DataSource / TransactionManager

### U.2 Pressure Analysis

**Adding more constructor parameters**:
```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger,
    DataSource dataSource,                     // NEW - for JDBC stores?
    TransactionManager transactionManager      // NEW - for coordinated tx?
)
```

**Problems**:
1. ❌ 10+ parameters (maintainability)
2. ❌ Public API exposure of infrastructure concerns
3. ❌ Test configuration complexity
4. ❌ No clear ownership of "durable substrate"

**Has real pressure arrived?**: ✅ **YES**

**M6-T2.5A deferred**: Constructor parameter grouping

**Decision point**: Construction/config grouping pressure NOW justified.

### U.3 Configuration Ownership Options

**Option 1**: More constructor parameters (current trajectory)
- ❌ Does not scale
- ❌ Mixes domain and infrastructure concerns

**Option 2**: Configuration object
```java
class DurableExecutionConfiguration {
  CheckpointStore checkpointStore;
  RuntimeBindingResolver bindingResolver;
  String runtimeBindingKey;
  ExecutionLedger executionLedger;
  InvocationStateStore invocationStateStore;  // If made configurable
}

new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, governancePolicy,
    durableConfig  // Single parameter
)
```

**Option 3**: Builder pattern
```java
SpringAiToolCallingEngine engine = SpringAiToolCallingEngine.builder()
    .chatModel(chatModel)
    .tools(tools)
    .chatMemory(chatMemory)
    .governancePolicy(governancePolicy)
    .durableExecution(config -> config
        .checkpointStore(checkpointStore)
        .bindingResolver(bindingResolver)
        .bindingKey(runtimeBindingKey)
        .executionLedger(executionLedger))
    .build();
```

**Option 4**: Persistence substrate object (internal)
```java
// Internal concept, not public API
class DurablePersistenceSubstrate {
  CheckpointStore checkpointStore;
  InvocationStateStore invocationStateStore;
  // Shared DataSource, TransactionManager if needed
}

// Engine constructor (unchanged public API):
new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, governancePolicy,
    checkpointStore, bindingResolver, runtimeBindingKey, executionLedger
)

// Internal: extract persistent stores from checkpointStore type
if (checkpointStore instanceof JdbcCheckpointStore jdbc) {
  DataSource ds = jdbc.getDataSource();
  invocationStateStore = new JdbcInvocationStateStore(ds);
}
```

**Recommended**: **Option 2 (DurableExecutionConfiguration) OR Option 4 (Internal substrate detection)**

**For T4D**: Recommend **Option 4** (defer public API change until persistent stores actually exist)

---

## V. Engine Construction Impact

### V.1 Persistent Store Injection

**Current**:
```java
// In-memory mode (default)
InvocationStateStore invocationStateStore = new InMemoryInvocationStateStore();
```

**Persistent mode (future)**:
```java
// How to inject persistent implementation?

Option A: Constructor parameter
  → Adds another parameter (pressure already high)

Option B: Derived from CheckpointStore
  → If CheckpointStore is JDBC, create JDBC InvocationStateStore
  → Couples implementations but avoids parameter explosion

Option C: Configuration object
  → Clean but requires public API change

Option D: Keep internal, derive from CheckpointStore type
  → Minimal public API impact
  → Relies on instanceof (acceptable for internal wiring)
```

**Recommended for NEXT slice**: **Option D**

**Reasoning**:
- Minimal public API change
- InvocationStateStore remains package-private
- First persistent implementation can share substrate with CheckpointStore
- Can refactor to explicit configuration later if pressure increases

### V.2 Constructor Impact Summary

**For M6-T4E (Persistent CheckpointStore)**:

**Public API Change**: ❌ **ZERO** (use existing CheckpointStore parameter)

**Implementation**:
```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,  // Accept JdbcCheckpointStore
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger
) {
    // Existing validation...
    
    // M6-T4E: Derive InvocationStateStore from CheckpointStore type
    InvocationStateStore invocationStateStore = 
        createInvocationStateStore(checkpointStore);
    
    // Rest unchanged...
}

private InvocationStateStore createInvocationStateStore(CheckpointStore checkpointStore) {
    if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
        return new JdbcInvocationStateStore(jdbcStore.getDataSource());
    }
    // Default: in-memory
    return new InMemoryInvocationStateStore();
}
```

**Verdict**: ✅ **Zero public API impact for T4E**

---

