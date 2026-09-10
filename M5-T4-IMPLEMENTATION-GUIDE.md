# Arctra M5-T4 Full Implementation Guide

**Status:** Phase 10 COMPLETE — M5-T4 FINAL: GO

**Date:** 2024-2026

**Architecture Frozen:** M5-T4.5 Semantic Closure Approved

**Implementation Progress:**
- Phase 1: ✅ COMPLETE (CheckpointStore + RuntimeBinding)
- Phase 2: ✅ COMPLETE (Core Durable Capability)
- Phase 3: ✅ COMPLETE (AgentRuntime Durable Entry)
- Phase 4: ✅ COMPLETE (Initial Durable Suspension)
- Phase 5: ✅ COMPLETE (Unified Durable Resume Pipeline)
- Phase 5.1: ❌ NO-GO (Evidence duplication bug found, missing tests)
- Phase 5.2: ✅ GO (Bug fixed, 20/20 test coverage achieved)
- Phase 6: ✅ GO (Local handle exception lifecycle)
- Phase 7: ✅ GO (Cross-runtime recovery E2E)
- Phase 8: ✅ GO (Concurrency & conflict semantics)
- Phase 9: ✅ GO (Memory/Evidence/Governance regression)
- Phase 10: ✅ GO (Final closure & release gate)

**Final Test Totals:**
- Core: 136 tests, 0 failures
- Runtime-react: 79 tests, 0 failures, 6 skipped
- Examples: 22 tests, 0 failures, 9 skipped
- **Total: 237 tests, 0 failures**

**Final Build Results:**
- `mvn clean test`: ✅ BUILD SUCCESS (9.347s)
- `mvn verify`: ✅ BUILD SUCCESS (8.445s)

---

## M5-T4 FINAL RELEASE DECISION

### ✅ **M5-T4 FINAL: GO**

**M5-T4 durable suspension/recovery capability is functionally complete and validated for the defined M5 scope.**

**Deliverables:**
- Checkpoint-backed durable suspension
- Cross-runtime/JVM recovery
- Unified resume pipeline (CHECK A/B)
- Local handle lifecycle
- Memory/Evidence/Governance continuity
- Concurrency contracts (same-handle CAS + cross-runtime CHECK B)

**Known Limitations:**
- InMemoryCheckpointStore: JVM-local reference only
- No distributed checkpoint/memory atomicity
- At-least-once tool execution semantics
- Application-defined RuntimeBindingResolver required

**M5 does NOT claim:**
- Production-ready distributed durability
- Exactly-once tool execution
- Atomic checkpoint/ChatMemory transactions

---

## 0. FROZEN ARCHITECTURE CONSTRAINTS

### 0.1 Module Dependency (IMMUTABLE)
```
arctra-runtime-react → arctra-core (ONLY)
```

**FORBIDDEN:**
```
arctra-core → arctra-runtime-react (NEVER)
arctra-core → Spring AI (NEVER)
```

### 0.2 Primary Backend SPI
- `AgentExecutionEngine` remains ONE primary backend SPI
- ONE optional capability specialization: `DurableExecutionEngine extends AgentExecutionEngine`
- NO `executePreparedResume()` exposed
- NO second parallel execution SPI

### 0.3 One Durable Resume Pipeline
Both entry points converge on ONE implementation:

```
AgentProcess.resume() → engine.resumeProcess()
AgentRuntime.resumeProcess() → engine.resumeProcess()
```

Complete pipeline exists ONCE in `DurableExecutionEngine.resumeProcess()`:
1. Load checkpoint
2. CHECK A (version validation)
3. Resolve RuntimeBinding
4. Protocol reconstruction
5. Tool execution
6. Model continuation
7. Completion OR re-suspension
8. CHECK B (conditional CAS)
9. Return AgentResult

### 0.4 RuntimeBinding Semantics (CRITICAL CHANGE)

**FROM:**
```java
record RuntimeBinding(
    AgentDefinition definition,
    AgentExecutionEngine engine,  // ← REMOVE
    AgentExecutionContext context)
```

**TO:**
```java
record RuntimeBinding(
    AgentDefinition definition,
    AgentExecutionContext context)
```

**Execution authority:** Calling `DurableExecutionEngine` executes (NOT binding's engine).

**RuntimeBinding provides:** Definition + context ONLY.

### 0.5 runtimeBindingKey Semantics (CRITICAL)

**Invariant:** `runtimeBindingKey` is LOGICAL binding/configuration identity, NOT physical runtime instance ID.

**Initial suspension:**
```java
CP v1.runtimeBindingKey = engine.configuredKey
```

**Re-suspension (MUST preserve):**
```java
CP vN+1.runtimeBindingKey = CP vN.runtimeBindingKey  // DO NOT use this.runtimeBindingKey
```

**Cross-runtime behavior:**
- Runtime A (key="incident-agent") suspends → CP v1 key="incident-agent"
- Runtime B (key="analytics-agent") resumes → uses CP v1 key="incident-agent"
- Runtime B re-suspends → CP v2 key="incident-agent" (PRESERVED)

### 0.6 Process Identity

```
Logical process identity = processId (stable String)
Suspension episode identity = (processId, checkpointVersion)
Java AgentProcess object = local JVM handle only
```

**Re-suspension invariants:**
- processId unchanged
- checkpointVersion = previous + 1
- runtimeBindingKey unchanged
- sessionId unchanged

### 0.7 CHECK A (Pre-Execution Validation)

```java
checkpoint = store.load(processId);
if (checkpoint == null) throw CheckpointNotFoundException;
if (checkpoint.checkpointVersion() != expectedVersion) throw StaleCheckpointException;
// Only after CHECK A passes → continue execution
```

**Effect:** Zero tool side effects on CHECK A failure.

### 0.8 CHECK B (Post-Execution CAS)

**Completion:**
```java
boolean success = store.deleteIfVersion(processId, expectedVersion);
if (!success) throw CheckpointTransitionConflictException;
```

**Re-suspension:**
```java
boolean success = store.replaceIfVersion(processId, expectedVersion, nextCheckpoint);
if (!success) throw CheckpointTransitionConflictException;
```

**M5 guarantees:** Exactly one successful checkpoint transition.

**M5 does NOT guarantee:** Exactly-once tool execution (concurrent resumes may both execute tools).

### 0.9 Evidence Ownership

```java
historicalEvidence = checkpoint.accumulatedEvidences();  // Immutable
newEvidence = new ArrayList<>();  // Mutable capture sink
mergedEvidence = historicalEvidence + newEvidence;  // Merge once
```

**NO:**
- Double merge
- Historical evidence mutation
- Duplication

### 0.10 ChatMemory Consistency Limitation

**M5 does NOT provide atomicity across:**
- CheckpointStore transition (deleteIfVersion/replaceIfVersion)
- ChatMemory persistence (chatMemory.add())

**Known crash window:** Checkpoint deleted → crash before ChatMemory write → incomplete session.

**NOT implementing:** 2PC, saga, outbox, distributed transaction.

### 0.11 M4 Compatibility (CRITICAL)

**Ephemeral M4 behavior MUST remain unchanged.**

**No durable infrastructure may become mandatory for ordinary `AgentExecutionEngine`.**

---

## 1. IMPLEMENTATION PHASES

### Phase 1: CheckpointStore + RuntimeBinding

**Files to modify:**
1. `InMemoryCheckpointStore.java` - Fix CAS bugs
2. `RuntimeBindingResolver.java` - Extract RuntimeBinding to separate file
3. Create `RuntimeBinding.java` - New public record

**Changes:**

#### 1.1 InMemoryCheckpointStore.replaceIfVersion() Fix

**Bug:** Uses `result == replacement` (identity comparison) - can return true incorrectly.

**Fix:** Use explicit boolean flag to track actual mutation.

```java
@Override
public boolean replaceIfVersion(
    String processId, long expectedVersion, SuspensionCheckpoint replacement) {
  Objects.requireNonNull(processId, "processId cannot be null");
  Objects.requireNonNull(replacement, "replacement cannot be null");

  // Invariant: replacement processId must match key
  if (!replacement.processId().equals(processId)) {
    throw new IllegalArgumentException(
        "Replacement checkpoint processId ("
            + replacement.processId()
            + ") does not match key ("
            + processId
            + ")");
  }

  // Track whether conditional mutation succeeded
  boolean[] replaced = {false};

  store.compute(
      processId,
      (key, current) -> {
        if (current == null) {
          replaced[0] = false;
          return null;
        }
        if (current.checkpointVersion() == expectedVersion) {
          replaced[0] = true;
          return replacement;
        }
        replaced[0] = false;
        return current;
      });

  return replaced[0];
}
```

**deleteIfVersion() is already correct** (uses boolean array).

#### 1.2 RuntimeBinding Extraction

**Current:** Nested record inside `RuntimeBindingResolver.java`

**Target:** Separate public record `RuntimeBinding.java`

**Remove:** `engine` field

**New RuntimeBinding.java:**
```java
package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;

/**
 * Runtime binding for durable recovery.
 *
 * <p>Binds a suspended process to its agent definition and execution context for recovery.
 * Resolved by {@link RuntimeBindingResolver} using the checkpoint's runtimeBindingKey.
 *
 * <p><strong>Execution authority:</strong> The {@link DurableExecutionEngine} performing
 * recovery executes using the definition and context from this binding. The binding does
 * NOT specify which engine executes.
 *
 * @param definition agent definition (identity, configuration)
 * @param context execution context (session, metadata)
 * @author lov3r
 * @since M5-T4.5
 */
public record RuntimeBinding(
    AgentDefinition definition,
    AgentExecutionContext context) {

  public RuntimeBinding {
    if (definition == null) {
      throw new IllegalArgumentException("definition cannot be null");
    }
    if (context == null) {
      throw new IllegalArgumentException("context cannot be null");
    }
  }
}
```

**Update RuntimeBindingResolver.java:**
- Remove nested RuntimeBinding record
- Import RuntimeBinding
- Update Javadoc references

#### 1.3 Tests

**InMemoryCheckpointStoreTest additions:**
```java
@Test
void replaceIfVersion_versionMismatchSameObject_returnsFalse() {
  // Verify: version mismatch returns false even with same object
}

@Test
void replaceIfVersion_crossProcessId_throws() {
  // Verify: replacement with different processId throws
}

@Test
void replaceIfVersion_correctVersion_succeeds() {
  // Verify: correct version replacement works
}
```

**Phase 1 Gate:**
```bash
cd arctra-core
mvn clean test -Dtest=InMemoryCheckpointStoreTest
mvn clean compile
```

**SUCCESS CRITERIA:**
- ✅ arctra-core compiles
- ✅ InMemoryCheckpointStoreTest passes
- ✅ No binding.engine() usages exist (grep verification)

---

### Phase 2: Core Durable Capability

**Files to create:**
1. `DurableExecutionEngine.java` - New interface

**Files to modify:**
1. `DurableResumeStrategy.java` - Simplify to lightweight prepare
2. `ProcessFactory.java` - Add createDurableSuspended()
3. `DefaultAgentProcess.java` - Update exception handling (optional, may defer to Phase 6)

**Files to delete (if obsolete):**
1. `DurableExecutor.java` - Remove if exists
2. `DurableCapability.java` - Remove if exists

#### 2.1 DurableExecutionEngine Interface

**New file:** `arctra-core/.../runtime/DurableExecutionEngine.java`

```java
package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.ContinuationSignal;

/**
 * Durable execution engine capability.
 *
 * <p>Optional specialization of {@link AgentExecutionEngine} that supports checkpoint-backed
 * durable recovery. Engines implementing this interface can suspend execution durably and resume
 * across runtime/JVM boundaries.
 *
 * <p><strong>This is a capability specialization, not a competing SPI.</strong> Engines may
 * implement either {@link AgentExecutionEngine} (ephemeral only) or this interface (durable
 * capable).
 *
 * <p>This method owns the complete durable resume pipeline including CHECK A validation,
 * runtime binding resolution, protocol reconstruction, tool execution, model continuation,
 * and CHECK B checkpoint transitions.
 *
 * @author lov3r
 * @since M5-T4
 */
public interface DurableExecutionEngine extends AgentExecutionEngine {

  /**
   * Resume durable suspended process.
   *
   * <p>Cross-runtime recovery entry point. Loads checkpoint, performs validation and recovery
   * orchestration, and returns execution result.
   *
   * @param processId stable process identifier
   * @param checkpointVersion suspension episode version (fencing token)
   * @param signal continuation signal (approval/rejection decision)
   * @return execution result (may be suspended again)
   * @throws CheckpointNotFoundException if checkpoint not found
   * @throws StaleCheckpointException if checkpoint version mismatch (CHECK A failure)
   * @throws CheckpointTransitionConflictException if concurrent modification detected (CHECK B failure)
   * @throws ResumePreparationException if runtime binding resolution fails
   */
  AgentResult resumeProcess(
      String processId,
      long checkpointVersion,
      ContinuationSignal signal);
}
```

#### 2.2 Simplify DurableResumeStrategy

**Current responsibility:** Too much (loads checkpoint, CHECK A, binding resolution).

**New responsibility:** Lightweight delegation only.

**Store only:**
```java
private final String processId;
private final long checkpointVersion;
private final DurableExecutionEngine engine;
```

**prepare() must be lightweight:**
```java
@Override
public ResumeAttempt prepare(ContinuationSignal signal) {
  // Lightweight - no I/O, no side effects
  return new DurableAttempt(processId, checkpointVersion, signal, engine);
}

private static class DurableAttempt implements ResumeAttempt {
  @Override
  public AgentResult execute() {
    // Delegate to unified pipeline
    return engine.resumeProcess(processId, checkpointVersion, signal);
  }
}
```

#### 2.3 ProcessFactory.createDurableSuspended()

**Add public factory method:**

```java
/**
 * Create durable suspended process.
 *
 * <p>Factory method for durable checkpoint-backed suspension. The process delegates resume
 * operations to the durable engine capability.
 *
 * @param processId stable process identifier
 * @param checkpointVersion suspension episode version
 * @param engine durable execution engine
 * @return new AgentProcess in WAITING state
 * @since M5-T4
 */
public static AgentProcess createDurableSuspended(
    String processId,
    long checkpointVersion,
    DurableExecutionEngine engine) {
  
  Objects.requireNonNull(processId, "processId cannot be null");
  Objects.requireNonNull(engine, "engine cannot be null");
  
  DurableResumeStrategy strategy = new DurableResumeStrategy(
      processId, checkpointVersion, engine);
  
  return createSuspended(strategy);  // Calls package-private method
}
```

#### 2.4 Tests

**DurableResumeStrategyTest:**
```java
@Test
void prepare_noSideEffects() {
  // Verify prepare() performs no I/O or external calls
}

@Test
void execute_delegatesToEngine() {
  // Verify attempt.execute() calls engine.resumeProcess() exactly once
}
```

**ProcessFactoryTest:**
```java
@Test
void createDurableSuspended_returnsWaitingProcess() {
  // Verify process starts in WAITING state
}
```

**Phase 2 Gate:**
```bash
mvn clean test -pl arctra-core
```

---

### Phase 3: AgentRuntime Durable Entry

**Files to modify:**
1. `AgentRuntime.java` - Add resumeProcess() signature
2. `DefaultAgentRuntime.java` - Implement resumeProcess()

#### 3.1 AgentRuntime Interface

**Add method:**
```java
/**
 * Resume durable suspended process (cross-runtime recovery).
 *
 * @param processId stable process identifier
 * @param checkpointVersion suspension episode version
 * @param signal continuation signal
 * @return execution result
 * @throws UnsupportedOperationException if engine does not support durable recovery
 */
AgentResult resumeProcess(
    String processId,
    long checkpointVersion,
    ContinuationSignal signal);
```

#### 3.2 DefaultAgentRuntime Implementation

```java
@Override
public AgentResult resumeProcess(
    String processId,
    long checkpointVersion,
    ContinuationSignal signal) {
  
  Objects.requireNonNull(processId, "processId cannot be null");
  Objects.requireNonNull(signal, "signal cannot be null");
  
  if (!(engine instanceof DurableExecutionEngine durable)) {
    throw new UnsupportedOperationException(
        "Engine does not support durable recovery: " + engine.getClass().getName());
  }
  
  return durable.resumeProcess(processId, checkpointVersion, signal);
}
```

**Phase 3 Gate:**
```bash
mvn clean test -pl arctra-core
```

---

### Phase 4: Initial Durable Suspension

**Files to modify:**
1. `SpringAiToolCallingEngine.java` - Add durable configuration, implement initial suspension

**Configuration:**

Engine owns:
- `CheckpointStore checkpointStore`
- `RuntimeBindingResolver bindingResolver`
- `String runtimeBindingKey`

**Constructor (add overload if needed for backward compatibility):**

```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,  // null = ephemeral M4 mode
    RuntimeBindingResolver bindingResolver,  // null = ephemeral
    String runtimeBindingKey) {  // null = ephemeral
  
  // Store fields
  this.checkpointStore = checkpointStore;
  this.bindingResolver = bindingResolver;
  this.runtimeBindingKey = runtimeBindingKey;
}
```

**Initial suspension (on REQUIRE_APPROVAL):**

```java
// 1. Generate stable processId
String processId = UUID.randomUUID().toString();

// 2. Build checkpoint v1
SuspensionCheckpoint checkpoint = new SuspensionCheckpoint(
    SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
    processId,
    1L,  // Initial version
    runtimeBindingKey,
    context.sessionId(),
    pendingToolBatch,
    currentEvidences);

// 3. Persist checkpoint FIRST (durability-first)
checkpointStore.create(checkpoint);

// 4. Create durable process (only after checkpoint committed)
AgentProcess process = ProcessFactory.createDurableSuspended(
    processId, 1L, this);

// 5. Return WAITING result
return new AgentResult(partialContent, evidences, process);
```

**Phase 4 Gate:**
```bash
mvn clean test -pl arctra-runtime-react
```

---

### Phase 5: Unified Durable Resume Pipeline

**Files to modify:**
1. `SpringAiToolCallingEngine.java` - Implement `resumeProcess()` (declare `implements DurableExecutionEngine`)

**Implementation structure:**

```java
@Override
public AgentResult resumeProcess(
    String processId,
    long checkpointVersion,
    ContinuationSignal signal) {
  
  // === PHASE 1: CHECK A ===
  SuspensionCheckpoint checkpoint = checkpointStore.load(processId)
      .orElseThrow(() -> new CheckpointNotFoundException(processId));
  
  if (checkpoint.checkpointVersion() != checkpointVersion) {
    throw new StaleCheckpointException(
        processId, checkpointVersion, checkpoint.checkpointVersion());
  }
  
  // === PHASE 2: BINDING RESOLUTION ===
  RuntimeBinding binding;
  try {
    binding = bindingResolver.resolve(
        processId,
        checkpoint.runtimeBindingKey(),  // Use checkpoint's key
        checkpoint.sessionId());
  } catch (RuntimeBindingException e) {
    throw new ResumePreparationException("Binding resolution failed", e);
  }
  
  // === PHASE 3: EXECUTION ===
  List<Evidence> historicalEvidences = checkpoint.accumulatedEvidences();
  List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>());
  
  // Protocol reconstruction using ProtocolReconstructor
  // Tool execution with evidence capture
  // Model continuation
  
  // === PHASE 4a: COMPLETION ===
  if (finalAnswerProduced) {
    boolean deleted = checkpointStore.deleteIfVersion(processId, checkpointVersion);
    if (!deleted) {
      throw new CheckpointTransitionConflictException(
          processId, checkpointVersion, "Completion failed - checkpoint changed");
    }
    
    // Persist final Assistant to ChatMemory
    chatMemory.add(sessionId, new AssistantMessage(finalAnswer));
    
    List<Evidence> allEvidences = new ArrayList<>(historicalEvidences);
    allEvidences.addAll(newEvidences);
    
    return new AgentResult(finalAnswer, allEvidences);
  }
  
  // === PHASE 4b: RE-SUSPENSION ===
  else if (newApprovalRequired) {
    SuspensionCheckpoint nextCheckpoint = new SuspensionCheckpoint(
        SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
        checkpoint.processId(),  // SAME processId
        checkpoint.checkpointVersion() + 1,  // Increment
        checkpoint.runtimeBindingKey(),  // PRESERVE (DO NOT use this.runtimeBindingKey)
        checkpoint.sessionId(),  // SAME session
        newPendingBatch,
        mergedEvidences);
    
    boolean replaced = checkpointStore.replaceIfVersion(
        processId, checkpointVersion, nextCheckpoint);
    if (!replaced) {
      throw new CheckpointTransitionConflictException(
          processId, checkpointVersion, "Re-suspension failed - checkpoint changed");
    }
    
    AgentProcess newProcess = ProcessFactory.createDurableSuspended(
        processId, checkpointVersion + 1, this);
    
    return new AgentResult(partialContent, mergedEvidences, newProcess);
  }
}
```

**Phase 5 Gate:**
```bash
mvn clean test -pl arctra-runtime-react
mvn clean test  # Full test
```

---

### Phase 6: Local Handle Exception Semantics

**Files to modify:**
1. `DefaultAgentProcess.java` - Update exception handling

**Current:** ALL exceptions → FAILED

**Target:**

```java
catch (Throwable t) {
  if (t instanceof VirtualMachineError || t instanceof ThreadDeath) {
    throw t;
  }
  
  // Preparation failures: handle remains WAITING (retryable, no side effects)
  if (t instanceof CheckpointNotFoundException ||
      t instanceof StaleCheckpointException ||
      t instanceof ResumePreparationException) {
    status.set(ProcessStatus.WAITING);
    throw (RuntimeException) t;
  }
  
  // Post-execution conflict: handle becomes stale WAITING
  if (t instanceof CheckpointTransitionConflictException) {
    status.set(ProcessStatus.WAITING);
    throw (CheckpointTransitionConflictException) t;
  }
  
  // Execution failures: terminal FAILED
  status.set(ProcessStatus.FAILED);
  
  if (t instanceof RuntimeException) {
    throw (RuntimeException) t;
  } else {
    throw (Error) t;
  }
}
```

**Phase 6 Gate:**
```bash
mvn clean test
```

---

### Phase 7: Cross-Runtime E2E Test

**Test:** Three-runtime recovery

```java
@Test
void threeRuntimeRecovery() {
  // Runtime A: suspend → CP v1
  // Runtime B: resume v1 → Tool X → re-suspend → CP v2
  // Runtime C: resume v2 → Tool Y → complete
  
  // Verify:
  // - processId stable
  // - runtimeBindingKey preserved ("incident-agent" through all runtimes)
  // - Evidence [X, Y]
  // - Session H+U+A
}
```

---

### Phase 8: Concurrency Tests

**Test 1:** Cross-runtime concurrent resume

```java
@Test
void concurrentCrossRuntimeResume() {
  // Both runtime A and B call resumeProcess(P, v1)
  // Both may execute tools
  // Only one CHECK B succeeds
}
```

**Test 2:** Same local handle

```java
@Test
void sameLocalHandleConcurrentResume() {
  // Multiple threads call process.resume()
  // Only one CAS wins
  // Only winner executes
}
```

---

### Phase 9: Memory / Evidence / Governance Tests

**ChatMemory:** H + U → (suspended) → H + U + A

**Evidence:** No duplication across re-suspension

**Governance:** Original batch not re-governed, new ToolCalls governed

---

### Phase 10: Full Regression

```bash
mvn clean verify
```

**Must pass:**
- All arctra-core tests
- All arctra-runtime-react tests
- M4 ephemeral tests unchanged
- Full reactor build

---

## 2. CRITICAL IMPLEMENTATION RULES

### DO NOT:
- ❌ Add Spring dependencies to arctra-core
- ❌ Create second parallel backend SPI
- ❌ Expose `executePreparedResume()`
- ❌ Make ResumeStrategy/ResumeAttempt public
- ❌ Make DurableExecutor/DurableCapability public
- ❌ Add duplicate durable config in DefaultAgentRuntime
- ❌ Mutate runtimeBindingKey during re-suspension (use checkpoint's key)
- ❌ Claim exactly-once tool execution
- ❌ Implement transactions/outbox/saga
- ❌ Change M4 semantics without verified bug

### DO:
- ✅ Preserve module boundaries (runtime-react → core only)
- ✅ Keep ONE durable resume pipeline
- ✅ Use checkpoint's runtimeBindingKey during re-suspension
- ✅ Track CAS success with explicit boolean flag
- ✅ Validate replacement.processId() == key
- ✅ Document known crash windows
- ✅ Keep M4 ephemeral path unchanged
- ✅ Run tests after each phase

---

## 3. KNOWN LIMITATIONS (DOCUMENT)

1. **ChatMemory/Checkpoint atomicity:** No transaction across stores
2. **At-least-once tool execution:** Concurrent resumes may both execute
3. **InMemoryCheckpointStore only:** Production needs persistent store
4. **Application-defined RuntimeBinding:** No default resolver
5. **Local handle status snapshot:** After conflict, handle is stale
6. **No binding migration:** M5 does not support key/session migration

---

## 4. FINAL GO CRITERIA

- [ ] Full project compiles
- [ ] All targeted tests pass
- [ ] Full reactor verify passes
- [ ] Core has no runtime-react dependency
- [ ] One durable resume pipeline exists
- [ ] No duplicate durable config
- [ ] RuntimeBinding has no engine field
- [ ] Binding key preserved across re-suspension
- [ ] CHECK A prevents stale execution
- [ ] CHECK B detects concurrent transitions
- [ ] Evidence does not duplicate
- [ ] M4 regression passes
- [ ] Cross-runtime A → B → C passes

---

## 5. PHASE EXECUTION TRACKER

| Phase | Status | Files Modified | Tests | Gate |
|-------|--------|----------------|-------|------|
| 1 | NOT STARTED | InMemoryCheckpointStore, RuntimeBinding | CAS tests | ❌ |
| 2 | NOT STARTED | DurableExecutionEngine, DurableResumeStrategy, ProcessFactory | Core tests | ❌ |
| 3 | NOT STARTED | AgentRuntime, DefaultAgentRuntime | Runtime tests | ❌ |
| 4 | NOT STARTED | SpringAiToolCallingEngine (suspension) | Suspension tests | ❌ |
| 5 | NOT STARTED | SpringAiToolCallingEngine (resumeProcess) | Resume tests | ❌ |
| 6 | NOT STARTED | DefaultAgentProcess (exceptions) | Exception tests | ❌ |
| 7 | NOT STARTED | E2E test | Cross-runtime test | ❌ |
| 8 | NOT STARTED | Concurrency tests | Race tests | ❌ |
| 9 | NOT STARTED | Memory/Evidence/Governance tests | Integration tests | ❌ |
| 10 | NOT STARTED | Full regression | All tests | ❌ |

---

**END OF IMPLEMENTATION GUIDE**

**Next step:** Execute Phase 1 (CheckpointStore + RuntimeBinding fixes)
