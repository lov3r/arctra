# Arctra M5-T3A — Durable Recovery Contract Amendment
## Concurrency Semantics & Checkpoint Store Contract Correction

**Status:** CONTRACT AMENDMENT  
**Date:** 2026-09-08  
**Author:** lov3r

---

## Executive Summary

**DEFECT CONFIRMED:** M5-T3 incorrectly claimed that post-execution optimistic CAS provides "single-winner resume" preventing duplicate tool execution.

**ACTUAL GUARANTEE:** Post-execution CAS only prevents duplicate **checkpoint state transitions**, NOT duplicate **tool executions**.

**CORRECTION:** M5 provides "single-winner checkpoint commit" with "at-least-once execution", NOT "single-winner execution" or "execution exclusivity".

**IMPACT:** Terminology corrected, concurrency contract clarified, CheckpointStore SPI re-derived.

**RESULT:** ✅ GO - M5-T3 architecture remains sound with corrected semantics.

---

## 1. Original Defect Analysis

### 1.1 Incorrect Claim in M5-T3

**M5-T3 §17 stated:**

> **Option B: Optimistic Concurrency (Recommended)**
> 
> ```java
> // Execute continuation
> AgentResult result = ...;
> 
> // Claim checkpoint atomically
> if (result.isCompleted()) {
>   boolean deleted = store.deleteIfVersion(processId, currentVersion);
> }
> ```
> 
> **Only ONE resume succeeds in modifying checkpoint.**
> 
> ✅ Single-winner semantics

**M5-T3 §19 stated:**

> **CHOSEN SCOPE:** Single-winner resume with optimistic concurrency

### 1.2 Why This Is Wrong

**Actual execution order:**

```
Checkpoint: P100 version=7

Node A:
  load checkpoint v7
  → execute Tool A        ← SIDE EFFECT OCCURS
  → deleteIfVersion(7)   ← CAS happens HERE
  → succeeds

Node B:
  load checkpoint v7
  → execute Tool A        ← SIDE EFFECT OCCURS
  → deleteIfVersion(7)   ← CAS happens HERE
  → fails (checkpoint already deleted)
```

**Result:** Tool A executed **TWICE**.

**CAS only prevented:** Both nodes from committing checkpoint deletion.

**CAS did NOT prevent:** Duplicate tool execution.

### 1.3 Root Cause

**Post-execution CAS protects:**
- Checkpoint state transition atomicity
- Stale execution from overwriting newer checkpoints

**Post-execution CAS does NOT protect:**
- Tool execution exclusivity
- Side effect duplication

**Fundamental distinction:**

| Layer | Protected by Post-Execution CAS? |
|-------|----------------------------------|
| 1. Resume attempt | ❌ No |
| 2. Tool execution / Side effects | ❌ No |
| 3. Checkpoint state transition | ✅ YES |

M5-T3 conflated layers 2 and 3.

---

## 2. Corrected Concurrency Semantics

### 2.1 What M5 Actually Guarantees

**M5 guarantees:**

1. ✅ **Durable recovery** from SAFE WAITING checkpoints across runtime/JVM restart
2. ✅ **Stale suspension episode rejection** using `processId + checkpointVersion`
3. ✅ **Conditional checkpoint transitions** - only expected version succeeds
4. ✅ **Stale checkpoint protection** - old execution cannot overwrite/delete newer checkpoints

**M5 does NOT guarantee:**

1. ❌ **Exactly-once tool execution**
2. ❌ **Concurrent active-active resume exclusion**
3. ❌ **Distributed execution locking**
4. ❌ **Prevention of duplicate side effects** if same checkpoint concurrently resumed
5. ❌ **Lease/owner/heartbeat semantics**

### 2.2 Deployment Requirement

**IMPORTANT:**

> For a given checkpoint episode, applications SHOULD dispatch at most one active resume attempt at a time.

If multiple nodes/threads concurrently resume the same checkpoint:
- **Duplicate tool execution MAY occur**
- **Only one checkpoint transition will commit**
- **At-least-once semantics apply**

### 2.3 Corrected Terminology

**REMOVED TERMS:**
- ❌ "single-winner resume"
- ❌ "single-winner execution"
- ❌ "optimistic locking prevents duplicate execution"
- ❌ "execution-safe multi-node resume"

**CORRECT TERMS:**
- ✅ "single-winner checkpoint commit"
- ✅ "conditional checkpoint transition"
- ✅ "stale-checkpoint protection"
- ✅ "at-least-once execution"

---

## 3. CheckpointVersion Semantics - Clarified

### 3.1 Dual Role of checkpointVersion

**checkpointVersion serves TWO independent correctness purposes:**

#### Role 1: Suspension Episode Correlation

**Problem:**
```
P100 → CP1 version=1 (Tool A pending)
P100 → CP2 version=2 (Tool B pending)
Old approval for v1 arrives after v2 exists
```

**Solution:** Resume API includes `checkpointVersion`:
```java
resumeProcess(processId, checkpointVersion, signal)
```

**Pre-execution validation:**
```java
SuspensionCheckpoint current = store.load(processId);
if (current.checkpointVersion() != requestedVersion) {
  throw new StaleApprovalException("Approval is for old suspension episode");
}
// Validation BEFORE tool execution
```

**Prevents:** Stale approval from executing wrong tools.

#### Role 2: Post-Execution Conditional Transition

**Problem:**
```
Node A: loads v7, executes Tool A, attempts delete
Node B: loads v7, executes Tool A, attempts delete
Checkpoint already deleted by A → B must not fail silently
```

**Solution:** Conditional state transition:
```java
boolean deleted = store.deleteIfVersion(processId, expectedVersion);
if (!deleted) {
  throw new ConcurrentCheckpointModificationException();
}
```

**Prevents:** Stale execution from deleting newer checkpoints.

**Does NOT prevent:** Both A and B from executing Tool A.

### 3.2 Two Distinct Version Checks

**CHECK A - Pre-Execution (Stale Episode Rejection):**
```java
// BEFORE continuation/tool execution
load current checkpoint
if (current.version != requested.version):
  reject WITHOUT executing tools
```

**Purpose:** Prevent stale approval from approving wrong suspension episode.

**CHECK B - Post-Execution (Conditional Transition):**
```java
// AFTER continuation/tool execution
if (completed):
  deleteIfVersion(processId, expectedVersion)
if (suspended):
  updateIfVersion(processId, expectedVersion, newCheckpoint)
```

**Purpose:** Prevent stale execution from overwriting/deleting newer checkpoints.

**CRITICAL:** CHECK B does NOT prevent duplicate tool execution - tools already executed.

---

## 4. Re-Derived CheckpointStore Contract

### 4.1 Lifecycle-Driven Operations

**Consumer 1: Initial Durable Suspension**

**Need:** Create checkpoint ONLY IF processId does not already exist.

**Danger:** Unconditional `save()` might overwrite active checkpoint.

**Operation:**
```java
void create(SuspensionCheckpoint checkpoint);
// Throws if processId already exists
```

**Consumer 2: Resume Lookup**

**Need:** Load checkpoint by processId.

**Operation:**
```java
Optional<SuspensionCheckpoint> load(String processId);
```

**Consumer 3: Re-Suspension**

**Need:** Replace checkpoint ONLY IF current version matches.

**Danger:** Blind overwrite would allow stale execution to replace newer checkpoint.

**Operation:**
```java
boolean replaceIfVersion(
  String processId,
  long expectedVersion,
  SuspensionCheckpoint replacement
);
// Returns true if replaced (version matched)
// Returns false if version mismatch (concurrent modification)
```

**Consumer 4: Terminal Invalidation (Completion/FAILED)**

**Need:** Delete checkpoint ONLY IF current version matches.

**Danger:** Blind delete would allow stale execution to delete newer checkpoint.

**Operation:**
```java
boolean deleteIfVersion(String processId, long expectedVersion);
// Returns true if deleted (version matched)
// Returns false if version mismatch (already modified)
```

### 4.2 Final CheckpointStore SPI

```java
package cn.bitcss.arctra.checkpoint;

/**
 * Durable checkpoint storage for WAITING suspension recovery.
 *
 * <h2>Concurrency Semantics</h2>
 * <ul>
 *   <li>create: fails if processId already exists</li>
 *   <li>load: always reads current state</li>
 *   <li>replaceIfVersion: conditional update (CAS)</li>
 *   <li>deleteIfVersion: conditional delete (CAS)</li>
 * </ul>
 *
 * <h2>Correctness Guarantees</h2>
 * <p>Conditional operations prevent stale executions from corrupting
 * checkpoint state, but do NOT prevent duplicate tool execution.
 *
 * @author lov3r
 * @since M5
 */
public interface CheckpointStore {

  /**
   * Create initial suspension checkpoint.
   *
   * <p>MUST fail if processId already exists.
   *
   * @param checkpoint the checkpoint to create
   * @throws CheckpointAlreadyExistsException if processId exists
   */
  void create(SuspensionCheckpoint checkpoint);

  /**
   * Load checkpoint by processId.
   *
   * @param processId the process identifier
   * @return checkpoint if exists, empty if not found
   */
  Optional<SuspensionCheckpoint> load(String processId);

  /**
   * Replace checkpoint conditionally.
   *
   * <p>Succeeds ONLY IF current checkpointVersion matches expectedVersion.
   * Used for re-suspension.
   *
   * @param processId the process identifier
   * @param expectedVersion the expected current version
   * @param replacement the new checkpoint (with incremented version)
   * @return true if replaced, false if version mismatch
   */
  boolean replaceIfVersion(
      String processId,
      long expectedVersion,
      SuspensionCheckpoint replacement);

  /**
   * Delete checkpoint conditionally.
   *
   * <p>Succeeds ONLY IF current checkpointVersion matches expectedVersion.
   * Used for completion/failure terminal invalidation.
   *
   * @param processId the process identifier
   * @param expectedVersion the expected current version
   * @return true if deleted, false if version mismatch
   */
  boolean deleteIfVersion(String processId, long expectedVersion);
}
```

### 4.3 Removed Operations

**REMOVED: Unconditional `save()`**

**Rationale:** Blind overwrite is dangerous:
- Initial suspension: might overwrite active checkpoint
- Re-suspension: should be conditional (use `replaceIfVersion`)
- No safe consumer for unconditional save

**REMOVED: Unconditional `delete()`**

**Rationale:** Blind delete is dangerous:
- Terminal invalidation: should be conditional (use `deleteIfVersion`)
- Stale execution could delete newer checkpoint
- Administrative cleanup is separate concern (future management API)

**REMOVED: `updateIfVersion()` (renamed)**

**Rationale:** "update" is ambiguous (partial vs full replacement).  
**Replaced with:** `replaceIfVersion()` - clearer full-replacement semantic.

---

## 5. SuspensionCheckpoint Fields - Corrected

### 5.1 Field Re-Evaluation

| Field | Status | Consumer |
|-------|--------|----------|
| **schemaVersion** | REQUIRED | Checkpoint format evolution |
| **processId** | REQUIRED | Process identity |
| **checkpointVersion** | REQUIRED | Episode correlation + conditional CAS |
| **runtimeBindingKey** | REQUIRED | Runtime resolution |
| **sessionId** | REQUIRED | ChatMemory binding |
| **pendingToolCalls** | REQUIRED | Protocol reconstruction |
| **accumulatedEvidences** | REQUIRED | Evidence merge continuity |
| **suspendedAt** | **DEFERRED** | No M5 consumer yet |
| **suspensionReason** | OPTIONAL | Diagnostic only |

### 5.2 suspendedAt Decision - CHANGED

**M5-T3 claimed:** REQUIRED for "timeout/staleness detection"

**Amendment finding:** M5 does NOT implement:
- Checkpoint timeout
- Stale process expiration
- Checkpoint GC
- Automatic cancellation

**Corrected status:** DEFERRED

**Rationale:** "NO FIELD WITHOUT A CURRENT CONSUMER"

If future M5 phases add checkpoint timeout, `suspendedAt` becomes REQUIRED then.

**M5-T4 minimal checkpoint:**
```java
record SuspensionCheckpoint(
  String schemaVersion,                 // "1.0"
  String processId,                     // P100
  long checkpointVersion,               // 1, 2, 3...
  String runtimeBindingKey,             // "prod.incident-agent.v2"
  String sessionId,                     // S1
  List<PendingToolCall> pendingBatch,   // [{call_001, investigate, "{}"}]
  List<Evidence> accumulatedEvidences   // [{source, content}]
  // suspendedAt: DEFERRED - no current M5 consumer
)
```

---

## 6. Recovery Failure Semantics - Preserved

### 6.1 CheckpointStore Unavailable

**Scenario:**
```
resumeProcess(P100, v1, APPROVED)
→ store.load() temporarily fails (network/DB down)
```

**Semantic:**
- ❌ NOT: Process → FAILED
- ✅ Process remains logically WAITING
- ✅ Recovery attempt fails (retryable)
- ✅ No tools executed
- ✅ Checkpoint unchanged

**This is infrastructure failure, NOT process execution failure.**

### 6.2 Conditional Transition Failure

**Scenario:**
```
Tool A already executed
→ model completed
→ deleteIfVersion(P100, v1) returns false
```

**Meaning:** Checkpoint state changed concurrently (e.g., Node B already modified it).

**Semantic:**
- ✅ Side effects (Tool A) already occurred
- ✅ Current execution cannot commit its state transition
- ✅ Caller observes conflict/error
- ❌ NOT: Process → FAILED

**Application must:**
- Inspect current checkpoint state
- Determine if retry needed
- Accept that side effects occurred

**This is POST-EXECUTION checkpoint conflict, NOT pre-execution failure.**

---

## 7. M5 Concurrency Contract - FROZEN

### 7.1 Official M5 Concurrency Guarantee

**M5 guarantees:**

1. **Durable recovery** from SAFE WAITING checkpoints across runtime/JVM restart.

2. **Stale suspension episodes are rejected** using `processId + checkpointVersion` BEFORE tool execution.

3. **Checkpoint transitions are conditional** on expected `checkpointVersion`, preventing stale executions from overwriting or deleting newer checkpoints.

4. **At-least-once execution** - tool may execute multiple times if crashed/retried.

**M5 does NOT guarantee:**

1. **Exactly-once tool execution.**

2. **Concurrent active-active resume exclusion** - if same checkpoint resumed concurrently, tools may execute multiple times.

3. **Distributed execution locking** - no claim/lease/owner semantics.

4. **Prevention of duplicate side effects** if same checkpoint concurrently resumed.

5. **Zero tool execution waste** on checkpoint conflict.

### 7.2 Deployment Requirement

**IMPORTANT:**

> For a given checkpoint episode, applications SHOULD dispatch at most one active resume attempt at a time.
>
> M5 provides checkpoint state protection, NOT distributed execution coordination.

**If concurrent resume occurs:**
- Multiple tool executions MAY happen
- Only one checkpoint transition commits
- At-least-once semantics apply
- Tools should be idempotent

**M5 is a durable restart framework, NOT a distributed scheduler.**

---

## 8. M5-T3 Document Corrections

### 8.1 Modified Sections

**§17 Concurrency Solutions - CORRECTED:**

**OLD:**
> **Option B: Optimistic Concurrency (Recommended)**
> 
> **Only ONE resume succeeds in modifying checkpoint.**
> 
> ✅ Single-winner semantics

**NEW:**
> **Option B: Conditional Checkpoint Transition (Recommended)**
> 
> **Only ONE checkpoint state transition succeeds.**
> 
> ✅ Single-winner checkpoint commit  
> ❌ Does NOT prevent duplicate tool execution

**§19 M5 Concurrency Scope - CORRECTED:**

**OLD:**
> **CHOSEN SCOPE:** Single-winner resume with optimistic concurrency

**NEW:**
> **CHOSEN SCOPE:** Conditional checkpoint transitions with at-least-once execution

**§22 Storage Port - CORRECTED:**

**OLD:**
```java
interface CheckpointStore {
  void save(SuspensionCheckpoint checkpoint);
  Optional<SuspensionCheckpoint> load(String processId);
  void delete(String processId);
  boolean updateIfVersion(...);
  boolean deleteIfVersion(...);
}
```

**NEW:**
```java
interface CheckpointStore {
  void create(SuspensionCheckpoint checkpoint);  // NOT save
  Optional<SuspensionCheckpoint> load(String processId);
  boolean replaceIfVersion(...);  // NOT updateIfVersion
  boolean deleteIfVersion(...);
  // NO unconditional delete()
}
```

### 8.2 Added Clarifications

**§3 Checkpoint Semantic Model:**

> Checkpoint represents a SAFE WAITING resume point. Conditional transitions protect checkpoint state, NOT tool execution exclusivity.

**§16 Concurrency:**

> Post-execution CAS prevents stale checkpoint corruption, NOT duplicate tool execution. Applications should avoid concurrent resume of same checkpoint episode.

---

## 9. M5-T4 Revised Scope

### 9.1 Implementation Requirements

**M5-T4 MUST implement:**

1. ✅ `SuspensionCheckpoint` record (schemaVersion, processId, checkpointVersion, runtimeBindingKey, sessionId, pendingToolCalls, accumulatedEvidences)
2. ✅ `PendingToolCall` record (toolCallId, toolName, arguments)
3. ✅ `CheckpointStore` SPI (create, load, replaceIfVersion, deleteIfVersion)
4. ✅ `InMemoryCheckpointStore` with CAS semantics
5. ✅ `RuntimeBindingResolver` SPI
6. ✅ `AgentRuntime.resumeProcess(processId, checkpointVersion, signal)`
7. ✅ Pre-execution stale-episode validation (CHECK A)
8. ✅ Post-execution conditional transitions (CHECK B)
9. ✅ Initial suspension: `store.create()`
10. ✅ Re-suspension: `store.replaceIfVersion()`
11. ✅ Completion/FAILED: `store.deleteIfVersion()`
12. ✅ Evidence continuity
13. ✅ Session continuity
14. ✅ M4 ephemeral backward compatibility

**M5-T4 MUST NOT implement:**

- ❌ Distributed lock/claim/lease
- ❌ Pre-execution CAS (CLAIMED status)
- ❌ Exactly-once execution
- ❌ Owner/heartbeat
- ❌ Checkpoint timeout/GC
- ❌ suspendedAt handling (DEFERRED)

### 9.2 Critical M5-T4 Tests

**Test N: Concurrent Resume Behavior (NEW)**

```java
@Test
void concurrentResume_bothToolsExecute_onlyOneCheckpointTransitionCommits() {
  // Initial suspension
  checkpoint v1 created
  
  // Simulate concurrent resume
  Thread A: resumeProcess(P100, v1, APPROVED)
  Thread B: resumeProcess(P100, v1, APPROVED)
  
  // VERIFY:
  // 1. Tool executed by BOTH A and B (at-least-once)
  // 2. Only ONE deleteIfVersion succeeds
  // 3. One thread gets ConcurrentCheckpointModificationException
  
  // DOCUMENT: This is expected behavior - NOT a bug
}
```

**This test MUST demonstrate that M5 does NOT prevent duplicate tool execution.**

---

## 10. Final M5-T3 Status

### 10.1 Corrections Applied

✅ **"Single-winner resume" terminology removed**  
✅ **CAS semantics precisely defined** (checkpoint transition only)  
✅ **checkpointVersion dual role clarified** (episode correlation + conditional transition)  
✅ **Stale approval rejected BEFORE execution** (CHECK A)  
✅ **Active-active execution exclusion explicitly OUT OF SCOPE**  
✅ **CheckpointStore SPI re-derived** (create/load/replaceIfVersion/deleteIfVersion)  
✅ **Unconditional delete() removed** (no safe consumer)  
✅ **Unconditional save() removed** (dangerous blind overwrite)  
✅ **suspendedAt status corrected** (DEFERRED - no current consumer)  
✅ **Recovery Failure ≠ FAILED preserved**  
✅ **M4 behavior preserved**  
✅ **M5-T4 scope updated**  

### 10.2 Architecture Remains Sound

**Core thesis unchanged:**
- ✅ Durable recovery from WAITING checkpoints
- ✅ Protocol reconstruction from minimal DTO (M5-T2 proven)
- ✅ AgentRuntime-owned recovery
- ✅ Explicit RuntimeBindingResolver
- ✅ Conditional checkpoint transitions
- ✅ At-least-once execution

**Only correction:** Precise concurrency guarantee wording.

---

## 11. GO/NO-GO Decision

### 11.1 M5-T3A Amendment: ✅ GO

**All defects corrected:**
- ✅ Incorrect "single-winner resume" claim removed
- ✅ CAS semantics precisely defined
- ✅ Terminology corrected
- ✅ CheckpointStore contract re-derived
- ✅ No new architecture blockers discovered

**M5-T3 architecture remains fundamentally sound.**

### 11.2 M5-T4 Readiness: ✅ GO

**Prerequisites met:**
- ✅ Concurrency contract frozen
- ✅ CheckpointStore SPI finalized
- ✅ Checkpoint fields finalized
- ✅ Recovery semantics clarified
- ✅ Test requirements defined

**Implementation can proceed with corrected contract.**

---

## 12. Summary

### 12.1 What Changed

**Concurrency guarantee:**
- ❌ OLD: "Single-winner resume prevents duplicate execution"
- ✅ NEW: "Conditional checkpoint transitions with at-least-once execution"

**CheckpointStore SPI:**
- ❌ OLD: save/load/delete/updateIfVersion/deleteIfVersion
- ✅ NEW: create/load/replaceIfVersion/deleteIfVersion

**suspendedAt field:**
- ❌ OLD: REQUIRED
- ✅ NEW: DEFERRED (no M5 consumer yet)

### 12.2 What Remained

- ✅ AgentRuntime-owned recovery
- ✅ RuntimeBindingResolver
- ✅ processId + checkpointVersion in resume API
- ✅ Checkpoint semantic model
- ✅ Evidence direct persistence
- ✅ Durability-first suspension
- ✅ At-least-once execution
- ✅ M4 backward compatibility

### 12.3 Public API Delta

**M5-T3A:** ZERO code changes (architecture-only)

**M5-T4 will add:**
- 3 new types (SuspensionCheckpoint, CheckpointStore, RuntimeBindingResolver)
- 1 new method (AgentRuntime.resumeProcess)
- Total: 4 new public contracts

---

## Conclusion

M5-T3 Durable Recovery Architecture is **APPROVED** with corrected concurrency semantics.

**M5 provides:**
- ✅ Durable recovery across runtime restart
- ✅ Stale checkpoint protection
- ✅ At-least-once execution

**M5 does NOT provide:**
- ❌ Distributed execution coordination
- ❌ Exactly-once guarantees

**This is the correct scope for M5's "durable process restart" thesis.**

**M5-T4 implementation ready to proceed.**

---

**Author:** lov3r  
**Date:** 2026-09-08  
**Status:** ACCEPTED - M5-T3 CORRECTED AND FROZEN
