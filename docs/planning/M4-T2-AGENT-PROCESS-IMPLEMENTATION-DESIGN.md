# M4-T2: AgentProcess Lifecycle Foundation

**Date:** 2026-08-19  
**Type:** Lifecycle Verification Design / Contract Revalidation  
**Status:** APPROVED - Ready for Implementation  
**Dependencies:** M4-T1 Contract Gate APPROVED

**Task Semantic:** Verify AgentProcess lifecycle contract through test-only infrastructure. Zero production integration. Production suspension integration deferred to M4-T3/M4-T4.

---

## Executive Summary

**Purpose:**  
验证 M4-T1 契约在实际 implementation 层面的可行性，设计 Dynamic Materialization 机制，确保 M4-T2 implementation 不会偏离架构语义。

**Key Finding:**

✅ **大部分 Public API 已经实现：**
- AgentProcess interface ✅
- ProcessStatus enum ✅
- ContinuationSignal sealed interface ✅
- AgentResult evolution ✅
- DefaultAgentProcess (package-private) ✅

⚠️ **Production Integration Explicitly Deferred:**
- SpringAiToolCallingEngine 将在 M4-T3/M4-T4 集成 suspension/materialization
- Governance interception 属于 M4-T3
- Production suspend/resume flow 属于 M4-T3/M4-T4
- M4-T2 仅通过 test fixture 验证 lifecycle contract

**Contract Status:** ✅ **CONFIRMED with REFINEMENTS**

**M4-T2 Goal:** 通过 test-only infrastructure 验证 AgentProcess lifecycle contract 的可实施性，证明 task lifecycle 设计语义正确。

**Key Principle:** M4-T2 does NOT integrate Process into production execution paths. It only verifies the frozen lifecycle contract is implementable. Production integration is M4-T3/M4-T4 responsibility.

**Design Refinement (2026-08-19):** 移除 production/test dependency，使用 FakeSuspendingEngine 纯测试验证，保护 Engine/Process 边界，零生产代码逻辑变更。详见 Part 17。

---

## Part 1: Current State Analysis

### 1.1 Existing Code Inventory

**arctra-core/process/ (PUBLIC):**
```
AgentProcess.java          ✅ Exists (50 lines)
ProcessStatus.java         ✅ Exists (enum: RUNNING/WAITING/COMPLETED/FAILED)
ContinuationSignal.java    ✅ Exists (sealed, ApprovalSignal)
```

**arctra-core/runtime/ (PACKAGE-PRIVATE):**
```
DefaultAgentProcess.java   ✅ Exists (96 lines)
  - Constructor: Function<ContinuationSignal, AgentResult>
  - Uses AtomicReference<ProcessStatus> for thread safety
  - Implements resume() with CAS state transition
  - Stores finalResult volatile
```

**arctra-core/agent/ (PUBLIC):**
```
AgentResult.java           ✅ Modified (includes AgentProcess process field)
  - Backward compatible 2-param constructor
  - isCompleted() / isSuspended() helpers
```

**arctra-runtime-react/ (ENGINE):**
```
SpringAiToolCallingEngine.java  ❌ NO Process logic
  - Still only returns completed AgentResult
  - No suspension handling
  - No Governance interception
```

**Verdict:**  
M4-T1 Public API 已实现 80%，但 **Runtime integration 为 0%**。

---

### 1.2 Contract Revalidation

**从已有代码验证 M4-T1 设计：**

#### AgentProcess API

```java
public interface AgentProcess {
    String id();
    ProcessStatus status();
    AgentResult resume(ContinuationSignal signal);
    AgentResult result();
}
```

**Revalidation:**

✅ **id()** - 合理，UUID-based  
✅ **status()** - 合理，AtomicReference 保证可见性  
✅ **resume()** - 合理，CAS 防止并发  
⚠️ **result()** - 当前实现正确（COMPLETED 才返回），但需测试验证

**无需修改。**

---

#### ProcessStatus States

```java
RUNNING, WAITING, COMPLETED, FAILED
```

**Revalidation:**

当前 DefaultAgentProcess 的状态转换：

```
Constructor → WAITING (initial state)
resume() → WAITING → RUNNING (CAS)
  ↓
  success + completed → RUNNING → COMPLETED
  success + suspended → RUNNING → WAITING
  exception → RUNNING → FAILED
```

**问题识别：**

❌ **RUNNING 永远不会被外部观察到**

原因：
- Process 创建时就是 WAITING
- resume() CAS 立即转换 WAITING → RUNNING
- 在 continuationFunction 执行期间（RUNNING），外部无法 safely 调用 status()
- 执行完成后立即转换为 COMPLETED/WAITING/FAILED

**结论：**  
RUNNING 是内部 transient state，对外部 consumer 无价值。

**Refinement 1:**

保留 RUNNING（for semantic completeness），但明确：

> RUNNING is a transient internal state. External consumers will primarily observe WAITING, COMPLETED, or FAILED.

**不修改 enum，但更新文档。**

---

#### ContinuationSignal

```java
public sealed interface ContinuationSignal permits ApprovalSignal {
    record ApprovalSignal(boolean approved, String reason) 
        implements ContinuationSignal { }
}
```

**Revalidation:**

✅ **Sealed interface** - 正确，防止外部扩展  
✅ **ApprovalSignal only** - 正确，M4-T2 唯一真实 use case  
✅ **Validation in constructor** - reason 不能 blank

**无需修改。**

---

#### DefaultAgentProcess Implementation

```java
class DefaultAgentProcess implements AgentProcess {
    private final Function<ContinuationSignal, AgentResult> continuationFunction;
    // ...
}
```

**Revalidation - Critical Analysis:**

**这是整个 M4-T2 最关键的设计。**

**Continuation State 保存在哪里？**

答案：**保存在 Java closure 中。**

```java
Function<ContinuationSignal, AgentResult> continuationFunction
```

这个 Function 是一个 **closure**，它可以 capture：
- original AgentDefinition
- original AgentRequest  
- original AgentExecutionContext
- pending tool name
- pending tool arguments
- engine reference
- previous evidences
- 任何需要恢复执行的上下文

**优点：**
- ✅ 简单，无需显式 state class
- ✅ Type-safe
- ✅ M4 in-memory implementation 完全合理

**缺点：**
- ❌ 不可序列化（但 M4 本来就是 in-memory only）
- ❌ 不可跨 JVM restart（但 M4 scope 明确不支持）
- ❌ Closure 持有的引用可能很大（需要注意 memory leak）

**Verdict:**  
✅ **对 M4 in-memory implementation 完全正确。**

**但需要明确标注：**

> M4 uses Java closure for continuation state. This is an implementation shortcut valid for in-memory lifecycle only. Future persistent process will require explicit serializable state.

**Refinement 2:**  
在 DefaultAgentProcess Javadoc 中明确说明 closure-based state 的限制。

---

### 1.3 Critical Missing Piece: Process Materialization

**当前 SpringAiToolCallingEngine.execute():**

```java
public AgentResult execute(...) {
    // Wrap tools
    // Build ChatClient
    // Execute
    var content = promptSpec.call().content();
    
    return new AgentResult(content, evidences);  // ← Always completed
}
```

**问题：**

❌ **永远返回 completed result**  
❌ **没有任何 suspension 检测**  
❌ **没有 Process materialization**  
❌ **没有 Governance interception**

**M4-T2 必须回答：**

1. **谁决定需要 suspension？**
2. **谁创建 DefaultAgentProcess？**
3. **continuationFunction 如何实现？**
4. **如何从 Spring AI Tool Calling Loop 中 escape？**

---

## Part 2: Dynamic Materialization Design

### 2.1 Process Creation Trigger (Reconfirmed)

**Trigger:**

> Process materializes when task execution needs to outlive the current invocation boundary.

**M4-T2 Concrete Trigger:**

> Governance decision = REQUIRE_APPROVAL

**Not triggered by:**
- ❌ Multiple tool calls
- ❌ Multiple model interactions  
- ❌ Complex reasoning
- ❌ Long execution time

**Only triggered by:**
- ✅ Governance says "cannot complete in this invocation"

---

### 2.2 Who Materializes Process? (SUPERSEDED by Part 17)

**⚠️ NOTE: This section represents early analysis. Final decision in Part 17.**

**M4-T2 Revised Decision (Part 17):**

❌ **Production code does NOT materialize Process in M4-T2.**

✅ **Test-only FakeSuspendingEngine directly materializes Process for verification.**

**Rationale:**

- M4-T2 proves lifecycle contract works, not production integration
- Production Engine boundary must be protected
- Real suspension integration is M4-T3/M4-T4 responsibility

**Who materializes in future (M4-T3+):**

```
Governance intercepts Tool
  ↓
REQUIRE_APPROVAL
  ↓
Suspension signal
  ↓
[M4-T3/T4 design: Engine? Runtime middleware? TBD]
  ↓
Materialize AgentProcess
```

**M4-T2 does NOT answer this question for production.**

---

### 2.3 Controlled Re-entry Mechanism

**M4-T1 Contract Gate 已明确：**

> M4 不实现 true stack continuation。采用 controlled re-entry / pseudo-continuation。

**Design:**

```
First invocation:
  agent.execute(...)
    ↓
  Engine.execute(...)
    ↓
  [Simulated] Tool wants approval
    ↓
  Engine detects suspension need
    ↓
  Capture state:
    - pendingToolName
    - pendingToolArguments
    - AgentDefinition
    - AgentExecutionContext (sessionId)
    - previousEvidences
    ↓
  Create continuationFunction:
    signal -> {
      if (signal.approved()) {
        execute pendingTool
        collect evidence
        return completed result
      } else {
        return denial result
      }
    }
    ↓
  Create AgentProcess(continuationFunction)
    ↓
  Return AgentResult(partial_content, evidences, process)

Second invocation:
  process.resume(ApprovalSignal)
    ↓
  continuationFunction.apply(signal)
    ↓
  [controlled re-entry]
    ↓
  execute pendingTool
    ↓
  return completed result
```

**Key Point:**

❌ **不恢复 Spring AI Tool Calling Loop stack**  
❌ **不恢复 Model internal state**  
✅ **直接执行 approved tool**  
✅ **返回 tool result 作为最终 result**

**Limitation:**

- Model 不会看到 tool execution result
- Model 不会继续 reasoning
- 这是 M4 pragmatic choice

**Acceptable because:**

M4 只需证明：
- Task can suspend
- Task can resume  
- Process has independent lifecycle
- State can be preserved across invocations

**不需要证明：**
- Perfect Model continuity
- Full reasoning restoration

---

### 2.4 Suspension Simulation (SUPERSEDED by Part 17)

**⚠️ NOTE: This approach REJECTED due to production/test dependency issue.**

**Original approach had production SpringAiToolCallingEngine catching test exception - unacceptable.**

**See Part 17.3 for approved test strategy: FakeSuspendingEngine (test-only).**

---

## Part 3: Ownership and Boundaries

### 3.1 Who Owns What?

**AgentProcess (interface):**
- ✅ Owned by: arctra-core (public API)
- ✅ Consumer: User code, tests
- ✅ Responsibility: Process lifecycle contract

**DefaultAgentProcess (implementation):**
- ✅ Owned by: arctra-core (package-private)
- ✅ Created by: AgentExecutionEngine (M4-T2)
- ✅ Responsibility: In-memory lifecycle management

**Process Materialization:**
- ✅ Triggered by: Suspension need detection
- ✅ Executed by: AgentExecutionEngine (M4-T2 pragmatic)
- ✅ Future: Governance → Engine

**Continuation State:**
- ✅ Stored in: Java closure (Function)
- ✅ Lifetime: Process lifetime
- ✅ Limitation: Not serializable (M4 accepted)

---

### 3.2 Agent / Runtime / Engine / Process Boundary

**Current (M3):**

```
Agent.execute()
  ↓
AgentRuntime.execute()
  ↓
AgentExecutionEngine.execute()
  ↓
SpringAiToolCallingEngine
  ↓
Spring AI
  ↓
AgentResult (always completed)
```

**M4-T2:**

```
Agent.execute()
  ↓
AgentRuntime.execute()
  ↓
AgentExecutionEngine.execute()
  ↓
SpringAiToolCallingEngine
  ├─ normal case → completed AgentResult
  └─ suspension case
       ↓
     materialize AgentProcess
       ↓
     suspended AgentResult(process)

[Later]
AgentProcess.resume()
  ↓
continuationFunction.apply()
  ↓
completed AgentResult
```

**Key Boundary:**

✅ **Agent** - unchanged, stateless handle  
✅ **AgentRuntime** - unchanged, simple delegate  
✅ **AgentExecutionEngine** - +process materialization logic  
✅ **AgentProcess** - new, independent lifecycle

**Verification:**

- ❌ Agent 没有变成 Process
- ❌ Runtime 没有变成 Process Manager
- ❌ Engine 没有变成 Process Runtime
- ✅ Process 是独立 abstraction

---

### 3.3 Session Boundary Maintained

**Session vs Process:**

```
Session = conversation continuity (sessionId)
Process = task lifecycle (processId)
```

**M4-T2 Verification:**

```java
// Same session, multiple processes
AgentExecutionContext ctx = AgentExecutionContext.withSession("session-001");

agent.execute(request1, ctx);  // → Process P001
agent.execute(request2, ctx);  // → Process P002
```

**Orthogonality maintained:**

✅ sessionId ≠ processId  
✅ Session state (ChatMemory) ≠ Process state (continuation closure)  
✅ Session managed by user  
✅ Process managed by framework

---

## Part 4: ExecutionStep Decision

### 4.1 Does M4-T2 Need ExecutionStep Java Type?

**Question:**

M4-T1 mentioned ExecutionStep as "internal concept". Does M4-T2 implementation actually need a Java class/interface for it?

**Analysis:**

**M4-T2 Process has:**
- One suspension point (tool approval)
- One resume execution (approve → execute → complete)

**No need for:**
- ❌ Multiple named steps
- ❌ Step identity for retry
- ❌ Step status tracking
- ❌ Step-level observability

**Verdict:**

❌ **M4-T2 does NOT need ExecutionStep Java type.**

**Reason:**

- Current process is effectively single-step (suspend → resume → complete)
- No consumer needs step granularity
- Adding ExecutionStep now would be premature

**Future:**

When multi-step processes appear (M5+) AND external consumers need step visibility, then consider creating ExecutionStep.

**M4-T2 Conclusion:**

> ExecutionStep remains a semantic concept. No Java type in M4-T2.

---

## Part 5: Concurrency and Failure Semantics

### 5.1 Concurrent Resume Protection

**DefaultAgentProcess already implements:**

```java
if (!status.compareAndSet(ProcessStatus.WAITING, ProcessStatus.RUNNING)) {
    throw new IllegalStateException("Cannot resume process in state " + status.get());
}
```

**Verdict:**

✅ **Concurrent resume is prevented via CAS.**

**Test requirement:**

M4-T2 must have concurrent resume test verifying:
- First resume succeeds
- Second resume throws IllegalStateException

---

### 5.2 Failure Semantics

**Current DefaultAgentProcess:**

```java
try {
    AgentResult result = continuationFunction.apply(signal);
    // handle result
} catch (Exception e) {
    status.set(ProcessStatus.FAILED);
    throw new RuntimeException("Process execution failed during resume", e);
}
```

**Analysis:**

**If continuationFunction throws:**
- Process → FAILED
- Exception propagates

**Question:** 这是正确的吗？

**Alternative:**

Process stays WAITING, allow retry?

**Decision:**

✅ **Current behavior correct for M4-T2.**

**Reason:**

- M4 不实现 retry
- Continuation failure 应该是 terminal
- User 可以看到 FAILED status
- Exception 包含原因

**But needs clarification:**

是 **execution failure** 还是 **invalid signal**？

**Refinement 3:**

明确区分两种 failure：

```java
// Invalid signal (process state issue)
if (!status.compareAndSet(...)) {
    throw new IllegalStateException(...);  // Process state error
}

// Execution failure
try {
    result = continuationFunction.apply(signal);
} catch (Exception e) {
    status.set(ProcessStatus.FAILED);
    throw new ProcessExecutionException("Continuation failed", e);  // Execution error
}
```

Create `ProcessExecutionException` (package-private) to distinguish.

---

### 5.3 Resume After COMPLETED

**Current behavior:**

```java
public AgentResult result() {
    if (status.get() != ProcessStatus.COMPLETED) {
        throw new IllegalStateException(...);
    }
    return finalResult;
}
```

**But what if user calls resume() on COMPLETED process?**

Current CAS check will catch it:

```java
if (!status.compareAndSet(ProcessStatus.WAITING, ProcessStatus.RUNNING)) {
    throw new IllegalStateException("...state COMPLETED (must be WAITING)");
}
```

✅ **Already correct.**

**Test requirement:**

Verify resume() on COMPLETED process throws IllegalStateException.

---

## Part 6: Persistence Migration Boundary

### 6.1 M4-T2 In-Memory Limitations

**Explicit limitations:**

1. ❌ **Closure not serializable**
   - DefaultAgentProcess holds Function<>
   - Cannot serialize to DB/Redis
   
2. ❌ **No restart recovery**
   - JVM restart → all processes lost
   - processId lost
   
3. ❌ **No distributed execution**
   - Process tied to single JVM instance
   
4. ❌ **No process store**
   - No ProcessRepository
   - No查询 API

**Acceptable for M4:**

M4 goal is proof of concept for:
- Dynamic materialization ✅
- Lifecycle independence ✅
- Suspend/resume ✅

Not for:
- Production durability ❌
- Distributed execution ❌

---

### 6.2 Future Persistence Migration Path

**M5+ will need:**

```java
// Serializable process state
record ProcessState(
    String processId,
    ProcessStatus status,
    String agentDefinitionId,
    String pendingAction,
    Map<String, Object> capturedContext,
    Instant suspendedAt
) {}

// Process store
interface ProcessStore {
    void save(ProcessState state);
    Optional<ProcessState> load(String processId);
}

// Reconstructing process from store
AgentProcess loadProcess(String processId) {
    ProcessState state = store.load(processId);
    // Reconstruct continuation logic
    Function<ContinuationSignal, AgentResult> continuation = ...;
    return new RestoredAgentProcess(state, continuation);
}
```

**M4-T2 API 不阻塞这个演进吗？**

✅ **不阻塞。**

**Reason:**

- AgentProcess interface 不暴露 Function
- processId 是 String (可持久化)
- ProcessStatus 是 enum (可持久化)
- Continuation logic 可以从 state 重建

**Migration path clear:**

```
M4: AgentProcess backed by closure
M5: AgentProcess backed by ProcessState + reconstructed continuation
```

External API (AgentProcess.resume()) unchanged.

---

## Part 7: Public API Delta Summary (REVISED)

### 7.1 Already Implemented (No Change Needed)

✅ **AgentProcess interface** (arctra-core)  
✅ **ProcessStatus enum** (arctra-core)  
✅ **ContinuationSignal** (arctra-core)  
✅ **AgentResult evolution** (arctra-core)  
✅ **DefaultAgentProcess** (arctra-core, package-private)

---

### 7.2 Refinements Needed (REVISED)

**Refinement 1: ProcessStatus Javadoc (ENHANCED)**

Add to RUNNING enum constant:

> **M4 Note:** In single-JVM synchronous execution, RUNNING is a transient internal state. External consumers will primarily observe WAITING, COMPLETED, or FAILED. Future distributed/async execution may make RUNNING reliably observable.

**Refinement 2: DefaultAgentProcess Javadoc**

Add to class Javadoc:

> **M4 Implementation Note:** Uses Java closure (Function) for continuation state. This is valid for in-memory lifecycle only. Future persistent process will require explicit serializable state.

**~~Refinement 3: Add ProcessExecutionException~~** ❌ **REMOVED**

Not needed - no current consumer. Use existing IllegalStateException.

---

### 7.3 New Implementation Needed (REVISED)

**Production code:**

❌ **NO changes to SpringAiToolCallingEngine** (boundary protection)

**Test fixtures:**

1. ✅ **FakeSuspendingEngine** (test scope) - Directly materializes Process for lifecycle verification
2. ✅ **AgentProcessLifecycleTest** (test scope) - 13 lifecycle verification tests

**~~Removed from scope:~~**
- ❌ SuspensionRequestedException (caused production/test dependency)
- ❌ SuspendingTestTool (not needed with fake engine)
- ❌ SpringAiToolCallingEngine modifications (deferred to M4-T3)

---

## Part 8: Test Plan (SUPERSEDED by Part 17.14)

**⚠️ NOTE: Original test plan (10 tests) superseded by revised plan (13 tests) in Part 17.14.**

**See Part 17.14 for complete FakeSuspendingEngine-based test infrastructure.**

### 8.1 Core Lifecycle Tests (Original - for reference)

```java
AgentResult result = agent.execute(normalRequest, context);

assertThat(result.isCompleted()).isTrue();
assertThat(result.isSuspended()).isFalse();
assertThat(result.process()).isNull();
```

**Test 2: Suspended execution materializes Process**

```java
AgentResult result = agent.execute(suspendingRequest, context);

assertThat(result.isSuspended()).isTrue();
assertThat(result.process()).isNotNull();
assertThat(result.process().status()).isEqualTo(ProcessStatus.WAITING);
assertThat(result.process().id()).isNotBlank();
```

**Test 3: Resume completes Process**

```java
AgentResult suspended = agent.execute(suspendingRequest, context);
AgentProcess process = suspended.process();

ContinuationSignal signal = new ApprovalSignal(true, "approved");
AgentResult completed = process.resume(signal);

assertThat(completed.isCompleted()).isTrue();
assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
assertThat(process.result()).isEqualTo(completed);
```

**Test 4: Process identity stable**

```java
AgentProcess process = result.process();
String idBefore = process.id();

process.resume(signal);
String idAfter = process.id();

assertThat(idAfter).isEqualTo(idBefore);
```

---

### 8.2 Boundary Tests

**Test 5: sessionId ≠ processId**

```java
AgentExecutionContext ctx = AgentExecutionContext.withSession("session-123");
AgentResult result = agent.execute(suspendingRequest, ctx);
AgentProcess process = result.process();

assertThat(ctx.sessionId()).isEqualTo("session-123");
assertThat(process.id()).isNotEqualTo("session-123");
```

**Test 6: Evidence does not duplicate across re-entry**

```java
AgentResult suspended = agent.execute(...);
int evidenceCountSuspended = suspended.evidences().size();

AgentResult completed = suspended.process().resume(approvalSignal);
int evidenceCountCompleted = completed.evidences().size();

// New evidences from resume, not duplicates
assertThat(evidenceCountCompleted).isGreaterThan(evidenceCountSuspended);
// Verify content not duplicated
```

---

### 8.3 Concurrency and Failure Tests

**Test 7: Concurrent resume prevented**

```java
AgentProcess process = result.process();

CompletableFuture<AgentResult> future1 = CompletableFuture.supplyAsync(
    () -> process.resume(signal1)
);
CompletableFuture<AgentResult> future2 = CompletableFuture.supplyAsync(
    () -> process.resume(signal2)
);

// One succeeds, one throws IllegalStateException
```

**Test 8: Resume after COMPLETED throws**

```java
process.resume(signal);  // Completes
assertThatThrownBy(() -> process.resume(signal))
    .isInstanceOf(IllegalStateException.class);
```

**Test 9: result() before COMPLETED throws**

```java
AgentProcess process = suspended.process();

assertThatThrownBy(() -> process.result())
    .isInstanceOf(IllegalStateException.class)
    .hasMessageContaining("COMPLETED");
```

**Test 10: Continuation execution failure**

```java
// Create process with failing continuation
Function<ContinuationSignal, AgentResult> failingContinuation = 
    signal -> { throw new RuntimeException("execution failed"); };
AgentProcess process = new DefaultAgentProcess(failingContinuation);

assertThatThrownBy(() -> process.resume(signal))
    .isInstanceOf(ProcessExecutionException.class);
assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
```

---

## Part 9: Files to Change (REVISED)

### 9.1 arctra-core (Production)

**Modify (Javadoc only):**

1. `ProcessStatus.java` - Enhanced Javadoc (RUNNING future-proofing note)
2. `DefaultAgentProcess.java` - Enhanced Javadoc (closure limitation note)

**No change:**

- AgentProcess.java ✅
- ContinuationSignal.java ✅
- AgentResult.java ✅

**~~Do NOT add:~~**
- ❌ ProcessExecutionException (not needed)

---

### 9.2 arctra-runtime-react (Production)

**❌ NO CHANGES** - SpringAiToolCallingEngine unchanged

---

### 9.3 arctra-runtime-react/test (Test Scope)

**Add:**

1. `FakeSuspendingEngine.java` - Test-only engine for Process lifecycle verification
2. `AgentProcessLifecycleTest.java` - 13 lifecycle verification tests

**~~Do NOT add:~~**
- ❌ SuspensionRequestedException (production/test dependency issue)
- ❌ SuspendingTestTool (not needed with fake engine)
- ❌ SpringAiToolCallingEngine test modifications (production unchanged)

---

## Part 10: Implementation Order (REVISED)

**Phase 1: Documentation Refinements (Javadoc only)**

1. Update ProcessStatus.java Javadoc (RUNNING enhanced note)
2. Update DefaultAgentProcess.java Javadoc (closure limitation note)
3. Verify existing M1-M3 tests still pass

**Phase 2: Test Infrastructure**

4. Create FakeSuspendingEngine (test scope)
   - Implement COMPLETE / SUSPEND_ONCE / SUSPEND_TWICE modes
   - Verify compilation

**Phase 3: Lifecycle Verification Tests**

5. Create AgentProcessLifecycleTest (test scope)
6. Implement 13 lifecycle verification tests:
   - Synchronous execution (no process)
   - Suspension materialization
   - Resume to completion
   - Stable processId
   - Session ≠ Process identity
   - Evidence preservation
   - Concurrent resume protection
   - Resume after COMPLETED throws
   - result() before COMPLETED throws
   - Resume failure → FAILED
   - Denial handling
   - Re-suspension capability
   - Backward compatibility
7. All tests pass
8. `./mvnw clean verify` succeeds

**Phase 4: Documentation**

9. Update CURRENT-STATE.md (M4-T2 status)
10. Update TASKS.md (M4-T2 COMPLETE)

**Total: 2 production files (Javadoc only) + 2 test files (new)**

---

## Part 11: Acceptance Criteria (REVISED)

**M4-T2 Complete when:**

- [ ] ProcessStatus.RUNNING Javadoc enhanced (future-proofing note)
- [ ] DefaultAgentProcess Javadoc enhanced (closure limitation note)
- [ ] FakeSuspendingEngine implemented (test scope, 3 modes)
- [ ] AgentProcessLifecycleTest with 13 verification tests
- [ ] All 13 lifecycle tests pass
- [ ] Existing M1-M3 tests pass (backward compatibility)
- [ ] `./mvnw clean verify` succeeds
- [ ] Public API unchanged (Javadoc refinements only)
- [ ] **Zero production code logic changes** (Javadoc only)
- [ ] SpringAiToolCallingEngine **unchanged**
- [ ] No production/test dependency
- [ ] Dynamic materialization demonstrated (via test engine)
- [ ] Session ≠ Process boundary verified
- [ ] Concurrent resume protection verified
- [ ] Re-suspension capability verified
- [ ] Documentation updated (CURRENT-STATE, TASKS)

---

## Part 12: Explicit Non-Goals (ENHANCED)

**M4-T2 will NOT:**

- ❌ **Modify SpringAiToolCallingEngine production logic**
- ❌ **Implement real Spring AI suspension detection**
- ❌ **Implement Tool Governance interception**
- ❌ **Implement real pending Tool execution**
- ❌ **Restore Model reasoning state**
- ❌ **Resume Spring AI internal loop**
- ❌ Implement approval workflow UI/API
- ❌ Implement persistent Process (M5+)
- ❌ Implement ProcessStore (M5+)
- ❌ Implement ExecutionStep Java type
- ❌ Implement multi-step orchestration
- ❌ Implement distributed Process execution
- ❌ Implement background worker
- ❌ Implement restart recovery
- ❌ Implement Workflow DSL
- ❌ Implement ExecutionRecord
- ❌ Implement ProcessExecutionException
- ❌ Modify Agent API
- ❌ Modify AgentRuntime API
- ❌ Modify AgentExecutionEngine contract

**These bold items are explicitly deferred to M4-T3/M4-T4 for proper integration design.**

---

## Part 13: Risks and Mitigations (REVISED)

### Risk 1: Closure Memory Leak

**Risk:** continuationFunction captures large objects

**Mitigation:**
- M4-T2: Test-only, acceptable
- Production (future): Will use explicit serializable state
- Documented limitation in DefaultAgentProcess Javadoc

---

### Risk 2: Test Module Placement

**Risk:** Test code in wrong module breaks encapsulation

**Mitigation:**
- Place lifecycle tests in arctra-core/test (not runtime-react/test)
- DefaultAgentProcess is core package-private
- No cross-module test dependency
- See Part 19 for final placement

---

### Risk 3: Semantic Confusion

**Risk:** M4-T2 "completion" misunderstood as production integration

**Mitigation:**
- Clear documentation: "Lifecycle Verification" not "Implementation"
- Explicit Non-Goals list production integration
- M4-T3 responsibility clearly stated
- Task name clarified: "Lifecycle Foundation" not "Implementation"

---

## Part 14: Contract Conflicts

### 14.1 Conflicts with M4-T1?

**Checked:**

✅ AgentProcess API - Match  
✅ ProcessStatus states - Match  
✅ ContinuationSignal - Match  
✅ Dynamic Materialization semantic - Match (proven by test)  
✅ Controlled Re-entry concept - Match (clarified as "Lifecycle Continuation Fixture")  
✅ In-memory only - Match

**No conflicts. M4-T1 contract remains valid.**

---

### 14.2 Conflicts with ADR-004?

**ADR-004: Agent as Invocation Handle**

Checked:
- Agent remains stateless ✅
- Agent does not become Process ✅
- Agent API unchanged ✅
- M4-T2 does not modify Agent ✅

**No conflicts.**

---

### 14.3 Conflicts with Evolution Guide?

**Checked against:**
- docs/architecture/EVOLUTION-GUIDE.md

Key principles reconciled:

**Original statement (corrected):**
- ❌ "AgentProcess has real consumer (M4-T2 tests)"

**Corrected statement:**
- ✅ "AgentProcess creation pressure established by M4 architecture scenarios (HITL suspension, long-lived task, retry, recovery). M4-T2 tests verify frozen lifecycle contract is implementable, not that real consumers exist yet."

**Other principles:**
- Not creating premature abstractions ✅ (Process driven by M4 pressure test scenarios)
- Closure-based state is pragmatic choice ✅ (M4 in-memory only)
- Test-only verification approach ✅ (no production pollution)

**No conflicts after clarification.**

---

## Part 15: Deferred Decisions

**M4-T2 does NOT decide:**

- ❌ Persistent ProcessState schema
- ❌ ProcessStore interface
- ❌ Multi-step orchestration pattern
- ❌ ExecutionStep public API
- ❌ Governance API (M4-T3)
- ❌ Process query/search API
- ❌ Distributed Process execution
- ❌ Process versioning
- ❌ Process migration strategy

**These await future milestones with real requirements.**

---

## Part 16: Final Recommendation (SUPERSEDED by Part 17)

**⚠️ NOTE: Original recommendation superseded by Design Refinement (Part 17).**

**See Part 19 (Final Reconciliation) for approved recommendation.**

---

## Appendix A: Code Shape Preview (SUPERSEDED)

**⚠️ NOTE: Appendix A and B show original design. See Part 17.14 for approved test infrastructure (FakeSuspendingEngine).**

### A.1 Refined DefaultAgentProcess (Reference only)

```java
/**
 * Default implementation of AgentProcess.
 *
 * <p>Package-private implementation for in-memory process lifecycle management.
 *
 * <p><strong>M4 Implementation Note:</strong> Uses Java closure (Function) for 
 * continuation state. This is valid for in-memory lifecycle only. Future persistent 
 * process will require explicit serializable state.
 *
 * <h2>Thread Safety</h2>
 * <p>Uses atomic state transitions to prevent concurrent resume execution.
 *
 * @author lov3r
 * @since M4
 */
class DefaultAgentProcess implements AgentProcess {
    // ... existing implementation
    
    @Override
    public AgentResult resume(ContinuationSignal signal) {
        // ... existing CAS check
        
        try {
            AgentResult result = continuationFunction.apply(signal);
            // ... existing state transitions
        } catch (Exception e) {
            status.set(ProcessStatus.FAILED);
            throw new ProcessExecutionException(
                "Process execution failed during resume", e
            );
        }
    }
}
```

---

### A.2 SpringAiToolCallingEngine with Suspension (REJECTED)

**❌ This approach REJECTED in Part 17 Design Refinement.**

**Reason:** Production/test dependency violation.

**M4-T2 does NOT modify SpringAiToolCallingEngine.**

**See Part 17.14 for approved approach: FakeSuspendingEngine (test-only).**

---

## Appendix B: Traceability Matrix (REVISED)

| M4-T1 Contract Item | M4-T2 Implementation | Status |
|---------------------|---------------------|--------|
| AgentProcess API | Already implemented | ✅ |
| ProcessStatus states | Already implemented + Javadoc enhancement | 🔨 |
| ContinuationSignal | Already implemented | ✅ |
| Dynamic Materialization | Semantic proven via FakeSuspendingEngine (test) | 🔨 |
| Controlled Re-entry | Lifecycle Continuation Fixture (test) | 🔨 |
| In-memory only | DefaultAgentProcess closure + Javadoc note | 🔨 |
| Session ≠ Process | Test verification | 🔨 |
| Concurrent resume protection | DefaultAgentProcess CAS (already implemented) | ✅ |
| Failure semantics | IllegalStateException (no new exception) | ✅ |
| Production integration | ❌ Explicitly deferred to M4-T3/M4-T4 | N/A |

Legend: ✅ Already Done | 🔨 M4-T2 To Implement | N/A Not M4-T2 Scope

---

---

## Part 17: Design Refinement (2026-08-19)

### 17.1 Critical Issue: Production → Test Dependency

**Problem Identified:**

Original design proposed:
- SuspensionRequestedException = test scope only
- SpringAiToolCallingEngine production code catches SuspensionRequestedException

**This is unacceptable:** Production source cannot depend on test fixtures.

---

### 17.2 Resolution: Test-Only Process Lifecycle Verification

**Revised M4-T2 Strategy:**

❌ **Do NOT modify SpringAiToolCallingEngine production code for suspension.**

✅ **Use test-only infrastructure to verify Process lifecycle.**

**Rationale:**

M4-T2's real goal is to prove:
> Task lifecycle CAN outlive invocation boundary

M4-T2's goal is NOT:
> Spring AI Tool Calling Loop suspension is integrated

**The latter belongs to M4-T3/M4-T4.**

---

### 17.3 Revised Test Strategy

**Approach: Test-Only Fake Engine**

Create test-scoped components that directly exercise AgentProcess without polluting production Engine:

**Option A: FakeSuspendingEngine (Recommended)**

```java
// Test scope only
class FakeSuspendingEngine implements AgentExecutionEngine {
    
    private final boolean shouldSuspend;
    
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context) {
        
        if (shouldSuspend) {
            // Directly materialize process (test simulation)
            return createSuspendedResult(definition, request, context);
        } else {
            // Normal completion
            return new AgentResult("Completed synchronously");
        }
    }
    
    private AgentResult createSuspendedResult(...) {
        // Capture test state
        String capturedMessage = "Test suspension at: " + Instant.now();
        
        // Create continuation
        Function<ContinuationSignal, AgentResult> continuation = signal -> {
            if (signal instanceof ApprovalSignal approval && approval.approved()) {
                return new AgentResult("Resumed and completed: " + capturedMessage);
            } else {
                return new AgentResult("Denied");
            }
        };
        
        // Materialize process
        AgentProcess process = new DefaultAgentProcess(continuation);
        
        return new AgentResult("Suspended", List.of(), process);
    }
}
```

**Usage in tests:**

```java
@Test
void dynamicMaterialization() {
    // Test-only engine
    AgentExecutionEngine suspendingEngine = new FakeSuspendingEngine(true);
    AgentRuntime runtime = new DefaultAgentRuntime(suspendingEngine);
    Agent agent = runtime.agent(definition);
    
    // Verify suspension
    AgentResult result = agent.execute(request, context);
    assertThat(result.isSuspended()).isTrue();
    
    // Verify resume
    AgentProcess process = result.process();
    AgentResult completed = process.resume(new ApprovalSignal(true, "test"));
    assertThat(completed.isCompleted()).isTrue();
}
```

**Benefits:**

✅ Zero production code pollution  
✅ Directly tests AgentProcess contract  
✅ Proves Dynamic Materialization semantic  
✅ No test/production dependency  
✅ Clean separation of concerns

---

### 17.4 Engine / Process Boundary Protection

**Re-evaluation:**

**Question:** Should SpringAiToolCallingEngine own Process materialization?

**Answer:** ❌ **NO - Not in M4-T2.**

**Reasoning:**

SpringAiToolCallingEngine = execution strategy (Spring AI integration)

Process materialization = lifecycle concern (orthogonal to execution strategy)

**Current architecture:**

```
AgentRuntime
  ↓
AgentExecutionEngine (strategy)
  ↓ delegates
Spring AI ChatClient
```

**M4-T2 should NOT introduce:**

```
SpringAiToolCallingEngine
  ↓ owns
Process materialization
  ↓ owns
DefaultAgentProcess instantiation
```

**Why?**

1. Process lifecycle is framework concern, not Spring AI concern
2. Future engines (AgentScope, Graph, etc.) would duplicate this logic
3. Mixing execution strategy with lifecycle management violates SRP
4. M4-T2 only needs to prove Process contract, not integrate it everywhere

**Verdict:**

M4-T2 uses **test-only fake engine** to prove Process lifecycle.

M4-T3+ will design proper **suspension interception** when Governance exists.

---

### 17.5 Controlled Re-entry Scope Clarification

**Critical Clarification:**

Current design's "Controlled Re-entry" is **NOT** real Agent continuation.

**What M4-T2 continuation does:**

```
resume(APPROVED)
  ↓
Execute closure callback
  ↓
Return pre-defined result
```

**What M4-T2 continuation does NOT do:**

- ❌ Restore Model reasoning state
- ❌ Resume Spring AI Tool Calling Loop
- ❌ Let Model see tool execution result
- ❌ Continue Agent planning
- ❌ Restore Spring AI internal stack

**Correct naming:**

M4-T2 implements: **Lifecycle Continuation Fixture**

NOT: Agent Reasoning Continuation

**What M4-T2 proves:**

✅ State survives invocation boundary  
✅ Signal can resume process  
✅ Process identity remains stable  
✅ Lifecycle WAITING → resume → COMPLETED  
✅ Continuation callback mechanism works

**What M4-T2 does NOT prove:**

❌ Agent reasoning continuation solved  
❌ Spring AI loop restoration solved  
❌ Real tool governance integration

**True Controlled Re-entry belongs to M4-T3/M4-T4.**

---

### 17.6 ProcessExecutionException Re-evaluation

**Question:** Does M4-T2 really need ProcessExecutionException?

**Current usage:**

```java
catch (Exception e) {
    status.set(ProcessStatus.FAILED);
    throw new ProcessExecutionException("...", e);
}
```

**Who consumes this distinction?**

- ❌ No code catches ProcessExecutionException specifically
- ❌ No code handles it differently from RuntimeException
- ❌ No observability layer logs it separately

**Principle:**

> "Who consumes this distinction NOW?"

**Verdict:** ❌ **NOT NEEDED in M4-T2.**

**Reasoning:**

- No current consumer needs the type distinction
- IllegalStateException already distinguishes state errors
- Generic RuntimeException sufficient for execution failure
- Can add specific exception later when consumer appears

**Revised approach:**

```java
catch (Exception e) {
    status.set(ProcessStatus.FAILED);
    throw new IllegalStateException(
        "Process execution failed during resume: " + id(), e
    );
}
```

**Refinement 3 REMOVED.**

---

### 17.7 RUNNING ProcessStatus Re-evaluation

**Analysis:**

Current implementation shows:

```
Constructor → WAITING (materialization)
resume() → WAITING → RUNNING (CAS, internal)
  ↓
  [execution happens - transient]
  ↓
RUNNING → COMPLETED/WAITING/FAILED
```

**External observability:**

- Process created → WAITING (observable)
- resume() called → briefly RUNNING (not reliably observable)
- resume() returns → COMPLETED/FAILED (observable)

**Question:** Should public ProcessStatus include RUNNING?

**Arguments for KEEP:**

1. ✅ Semantic completeness (state machine clarity)
2. ✅ Future: distributed execution may make RUNNING observable
3. ✅ Debugging: thread dumps may show RUNNING
4. ✅ Future: process.status() during parallel operations
5. ✅ Documents internal state machine

**Arguments for REMOVE:**

1. ⚠️ External code cannot reliably observe RUNNING
2. ⚠️ Misleads users that RUNNING is stable observable state
3. ⚠️ Single-JVM execution makes it transient

**Verdict:** ✅ **KEEP RUNNING**

**Reasoning:**

1. **Future-proofing:** Distributed/async execution WILL need observable RUNNING
2. **Semantic honesty:** Process IS running during resume execution
3. **Low cost:** Enum value costs nothing
4. **Documented:** Javadoc will clarify "transient in M4"

**Refinement:**

Update ProcessStatus Javadoc:

```java
/**
 * Active execution.
 *
 * <p>Process is currently executing.
 *
 * <p><strong>M4 Note:</strong> In single-JVM synchronous execution, RUNNING 
 * is a transient internal state. External consumers will primarily observe 
 * WAITING, COMPLETED, or FAILED. Future distributed/async execution may 
 * make RUNNING reliably observable.
 */
RUNNING,
```

**Refinement 1 ENHANCED (keep RUNNING, enhance documentation).**

---

### 17.8 Revised M4-T2 Scope

**M4-T2 WILL verify:**

1. ✅ AgentProcess public contract
2. ✅ DefaultAgentProcess lifecycle implementation
3. ✅ Dynamic materialization semantic (via test engine)
4. ✅ WAITING → resume(APPROVED) → COMPLETED
5. ✅ WAITING → resume(DENIED) → COMPLETED (with denial content)
6. ✅ WAITING → resume failure → FAILED
7. ✅ Stable processId across lifecycle
8. ✅ Concurrent resume protection (CAS)
9. ✅ AgentResult.isSuspended() / isCompleted() helpers
10. ✅ Session identity ≠ Process identity
11. ✅ Synchronous execution → no Process materialization
12. ✅ Re-suspension capability (process → completed result with new process)
13. ✅ M1-M3 backward compatibility (existing tests pass)

**M4-T2 will NOT verify:**

- ❌ Real Spring AI Tool suspension integration
- ❌ Tool Governance interception
- ❌ Approval workflow UI/API
- ❌ Real pending Tool execution with Model continuation
- ❌ Model reasoning restoration
- ❌ Spring AI internal loop restoration
- ❌ Production Engine suspension handling

**These belong to M4-T3/M4-T4.**

---

### 17.9 Revised Files to Change

**arctra-core (Production):**

**Modify:**

1. ✅ `ProcessStatus.java` - Enhanced Javadoc (RUNNING note)
2. ✅ `DefaultAgentProcess.java` - Enhanced Javadoc (closure limitation note)

**No change:**
- AgentProcess.java ✅
- ContinuationSignal.java ✅
- AgentResult.java ✅

**Do NOT add:**
- ❌ ProcessExecutionException (not needed)

---

**arctra-runtime-react (Production):**

**No changes to SpringAiToolCallingEngine.java** ✅

---

**arctra-runtime-react/test (Test scope):**

**Add:**

1. ✅ `FakeSuspendingEngine.java` - Test-only engine that materializes Process
2. ✅ `AgentProcessLifecycleTest.java` - Lifecycle verification tests

**Do NOT add:**
- ❌ SuspensionRequestedException (production can't depend on it)
- ❌ SuspendingTestTool (not needed with fake engine)
- ❌ SpringAiToolCallingEngine modifications

---

### 17.10 Revised Implementation Order

**Phase 1: Documentation Refinements**

1. Update ProcessStatus.java Javadoc (RUNNING note)
2. Update DefaultAgentProcess.java Javadoc (closure note)
3. Verify existing tests pass

**Phase 2: Test Infrastructure**

4. Create FakeSuspendingEngine (test scope)
5. Verify it compiles and can create processes

**Phase 3: Lifecycle Tests**

6. Create AgentProcessLifecycleTest
7. Implement 13 verification tests
8. All tests pass

**Phase 4: Documentation**

9. Update CURRENT-STATE.md
10. Update TASKS.md

**Total changes: 2 production files (Javadoc only) + 2 test files (new)**

---

### 17.11 Revised Acceptance Criteria

**M4-T2 Complete when:**

- [ ] ProcessStatus.RUNNING Javadoc enhanced
- [ ] DefaultAgentProcess Javadoc enhanced
- [ ] FakeSuspendingEngine implemented (test)
- [ ] AgentProcessLifecycleTest with 13 tests
- [ ] All 13 lifecycle tests pass
- [ ] Existing M1-M3 tests pass (backward compatibility)
- [ ] `./mvnw clean verify` succeeds
- [ ] Public API unchanged (Javadoc refinements only)
- [ ] Zero production code changes beyond Javadoc
- [ ] SpringAiToolCallingEngine unchanged
- [ ] No test/production dependency
- [ ] Documentation updated (CURRENT-STATE, TASKS)

---

### 17.12 Revised Non-Goals (Enhanced)

**M4-T2 explicitly does NOT:**

- ❌ Modify SpringAiToolCallingEngine production logic
- ❌ Implement real Spring AI suspension detection
- ❌ Implement Tool Governance interception
- ❌ Implement real pending Tool execution
- ❌ Restore Model reasoning state
- ❌ Resume Spring AI internal loop
- ❌ Implement approval workflow
- ❌ Implement persistent Process (M5+)
- ❌ Implement ProcessStore (M5+)
- ❌ Implement ExecutionStep Java type
- ❌ Implement multi-step orchestration
- ❌ Implement distributed Process execution
- ❌ Implement background worker
- ❌ Implement restart recovery
- ❌ Implement Workflow DSL
- ❌ Implement ExecutionRecord
- ❌ Modify Agent API
- ❌ Modify AgentRuntime API
- ❌ Modify AgentExecutionEngine contract
- ❌ Add ProcessExecutionException

---

### 17.13 M4-T3/M4-T4 Responsibility Handoff

**What M4-T2 proves:** Process lifecycle contract works

**What M4-T3+ must build:**

1. **Governance Interception:**
   - Tool invocation → Governance decision
   - REQUIRE_APPROVAL → suspension trigger
   - Governance API (ToolGovernancePolicy, etc.)

2. **Suspension Bridge:**
   - Governance → Engine communication
   - How Engine receives suspension signal
   - Where to inject suspension handling

3. **Real Controlled Re-entry:**
   - Capture Spring AI execution context
   - Restore Tool Calling Loop state
   - Let Model see tool execution result
   - Continue Agent reasoning

4. **Integration Pattern:**
   - Where does Process materialization happen?
   - AgentRuntime middleware layer?
   - Engine-agnostic suspension handler?
   - Governance-aware execution wrapper?

**M4-T2 explicitly defers these decisions.**

---

### 17.14 Test Infrastructure Design

**FakeSuspendingEngine Implementation:**

```java
/**
 * Test-only fake engine for Process lifecycle verification.
 *
 * <p>This engine simulates suspension without requiring real Spring AI 
 * integration or Governance. It directly exercises AgentProcess contract.
 *
 * <p><strong>Scope:</strong> M4-T2 test only. Not for production use.
 *
 * @author lov3r
 * @since M4-T2
 */
class FakeSuspendingEngine implements AgentExecutionEngine {

    private final SuspensionMode mode;
    
    enum SuspensionMode {
        /** Complete synchronously, no process materialization */
        COMPLETE,
        
        /** Suspend once, resume to completion */
        SUSPEND_ONCE,
        
        /** Suspend, resume to another suspension (re-suspension) */
        SUSPEND_TWICE
    }
    
    FakeSuspendingEngine(SuspensionMode mode) {
        this.mode = mode;
    }
    
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context) {
        
        return switch (mode) {
            case COMPLETE -> new AgentResult("Completed without suspension");
            
            case SUSPEND_ONCE -> createSingleSuspension(definition, request, context);
            
            case SUSPEND_TWICE -> createReSuspension(definition, request, context);
        };
    }
    
    private AgentResult createSingleSuspension(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context) {
        
        // Capture test state
        String captured = "Captured: " + request.userMessage();
        
        // Create continuation
        Function<ContinuationSignal, AgentResult> continuation = signal -> {
            if (signal instanceof ApprovalSignal approval) {
                if (approval.approved()) {
                    return new AgentResult("Resumed and completed: " + captured);
                } else {
                    return new AgentResult("Execution denied: " + approval.reason());
                }
            }
            throw new IllegalArgumentException("Unexpected signal: " + signal);
        };
        
        // Materialize process
        AgentProcess process = new DefaultAgentProcess(continuation);
        
        return new AgentResult(
            "Suspended pending approval",
            List.of(new Evidence("test:suspension", "simulated")),
            process
        );
    }
    
    private AgentResult createReSuspension(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context) {
        
        String phase1 = "Phase 1: " + request.userMessage();
        
        Function<ContinuationSignal, AgentResult> continuation1 = signal1 -> {
            if (signal1 instanceof ApprovalSignal approval1 && approval1.approved()) {
                
                // Phase 2 continuation
                String phase2 = "Phase 2 after: " + phase1;
                Function<ContinuationSignal, AgentResult> continuation2 = signal2 -> {
                    if (signal2 instanceof ApprovalSignal approval2 && approval2.approved()) {
                        return new AgentResult("Completed after re-suspension: " + phase2);
                    } else {
                        return new AgentResult("Phase 2 denied");
                    }
                };
                
                // Create second process
                AgentProcess process2 = new DefaultAgentProcess(continuation2);
                
                // Return suspended again
                return new AgentResult("Phase 1 approved, awaiting phase 2", List.of(), process2);
                
            } else {
                return new AgentResult("Phase 1 denied");
            }
        };
        
        AgentProcess process1 = new DefaultAgentProcess(continuation1);
        
        return new AgentResult("Suspended phase 1", List.of(), process1);
    }
}
```

**Test Example:**

```java
@Test
void testDynamicMaterialization_SynchronousNoProcess() {
    // COMPLETE mode
    AgentExecutionEngine engine = new FakeSuspendingEngine(COMPLETE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test", "test"));
    
    AgentResult result = agent.execute(
        new AgentRequest("test request"),
        AgentExecutionContext.stateless()
    );
    
    assertThat(result.isCompleted()).isTrue();
    assertThat(result.isSuspended()).isFalse();
    assertThat(result.process()).isNull();
}

@Test
void testDynamicMaterialization_SuspensionMaterializesProcess() {
    // SUSPEND_ONCE mode
    AgentExecutionEngine engine = new FakeSuspendingEngine(SUSPEND_ONCE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test", "test"));
    
    AgentResult result = agent.execute(
        new AgentRequest("test request"),
        AgentExecutionContext.stateless()
    );
    
    assertThat(result.isSuspended()).isTrue();
    assertThat(result.process()).isNotNull();
    assertThat(result.process().id()).isNotBlank();
    assertThat(result.process().status()).isEqualTo(ProcessStatus.WAITING);
}

@Test
void testLifecycle_ResumeCompletesProcess() {
    AgentExecutionEngine engine = new FakeSuspendingEngine(SUSPEND_ONCE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test", "test"));
    
    // Suspend
    AgentResult suspended = agent.execute(
        new AgentRequest("test request"),
        AgentExecutionContext.stateless()
    );
    AgentProcess process = suspended.process();
    String originalId = process.id();
    
    // Resume
    ContinuationSignal signal = new ApprovalSignal(true, "approved");
    AgentResult completed = process.resume(signal);
    
    // Verify
    assertThat(completed.isCompleted()).isTrue();
    assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
    assertThat(process.id()).isEqualTo(originalId);  // Stable identity
    assertThat(process.result()).isEqualTo(completed);
}

@Test
void testLifecycle_ReSuspension() {
    AgentExecutionEngine engine = new FakeSuspendingEngine(SUSPEND_TWICE);
    AgentRuntime runtime = new DefaultAgentRuntime(engine);
    Agent agent = runtime.agent(new AgentDefinition("test", "test"));
    
    // First suspension
    AgentResult result1 = agent.execute(
        new AgentRequest("test"),
        AgentExecutionContext.stateless()
    );
    AgentProcess process1 = result1.process();
    
    // First resume → second suspension
    AgentResult result2 = process1.resume(new ApprovalSignal(true, "phase 1 ok"));
    assertThat(result2.isSuspended()).isTrue();
    AgentProcess process2 = result2.process();
    assertThat(process2.id()).isNotEqualTo(process1.id());  // Different process
    
    // Second resume → completion
    AgentResult result3 = process2.resume(new ApprovalSignal(true, "phase 2 ok"));
    assertThat(result3.isCompleted()).isTrue();
}
```

---

## Part 18: Final Refinement Summary

### 18.1 Key Changes from Original Design

**1. Production/Test Separation:**
- ❌ Original: SpringAiToolCallingEngine catches test exception
- ✅ Revised: FakeSuspendingEngine (test only) materializes Process

**2. Engine Boundary Protection:**
- ❌ Original: Production Engine owns Process materialization
- ✅ Revised: Test Engine proves lifecycle, production unchanged

**3. Controlled Re-entry Scope:**
- ❌ Original: Implied Agent continuation solved
- ✅ Revised: Explicit "Lifecycle Continuation Fixture" only

**4. Exception Design:**
- ❌ Original: Add ProcessExecutionException
- ✅ Revised: Use existing IllegalStateException

**5. ProcessStatus.RUNNING:**
- ⚠️ Original: Keep with basic note
- ✅ Revised: Keep with enhanced future-proofing note

---

### 18.2 Production Code Impact

**Before refinement:** 
- SpringAiToolCallingEngine: +suspension handling
- ProcessExecutionException: +new type

**After refinement:**
- Production code: **Javadoc changes only**
- Test code: +FakeSuspendingEngine, +tests

---

### 18.3 Architecture Integrity

✅ **Engine = execution strategy** (preserved)  
✅ **Process = lifecycle** (preserved)  
✅ **No production/test dependency** (fixed)  
✅ **M4-T3 integration space** (protected)

---

### 18.4 What M4-T2 Now Proves

**Proved by test infrastructure:**
1. ✅ AgentProcess contract is sound
2. ✅ DefaultAgentProcess implementation works
3. ✅ Dynamic materialization semantic is correct
4. ✅ Process lifecycle WAITING → COMPLETED/FAILED works
5. ✅ processId remains stable
6. ✅ Concurrent resume protection works
7. ✅ Re-suspension is possible
8. ✅ Session ≠ Process boundary maintained

**Not claimed:**
- ❌ Spring AI integration (M4-T3)
- ❌ Real Governance (M4-T3)
- ❌ Agent reasoning continuation (M4-T3/T4)

---

---

## Part 19: Final Reconciliation Summary

### 19.1 Contract Status

✅ **CONFIRMED - No Changes to M4-T1 Contract**

M4-T1 Contract remains valid. M4-T2 verifies its implementability through test infrastructure.

**Refinements (non-breaking, Javadoc only):**
1. ProcessStatus.RUNNING enhanced documentation (future-proofing)
2. DefaultAgentProcess closure limitation documentation

---

### 19.2 Task Semantic

**Corrected Task Name:** AgentProcess Lifecycle Foundation (not "Implementation")

**What M4-T2 proves:**
- ✅ AgentProcess lifecycle contract is implementable
- ✅ Dynamic Materialization semantic is sound
- ✅ suspend/resume lifecycle works
- ✅ Concurrency/state invariants hold
- ✅ Closure-based continuation viable for M4 in-memory

**What M4-T2 does NOT prove:**
- ❌ Production suspension integration complete
- ❌ Spring AI Tool Calling Loop suspension
- ❌ Governance-triggered materialization
- ❌ Real Agent reasoning continuation

**M4-T2 is lifecycle verification, not production integration.**

---

### 19.3 Real Consumer Clarification

**Corrected Statement:**

> AgentProcess creation pressure was established by M4 architecture pressure test scenarios (HITL suspension, long-lived task, retry, recovery needs identified in M4 Pressure Test V1/V2).
> 
> M4-T2 tests verify that the frozen lifecycle contract is implementable and sound. Tests are NOT real consumers—they are verification instruments.
> 
> Real consumers will emerge in M4-T3+ when Governance integration and production suspension flows are built.

**Evolution Guide Consistency:** ✅ Corrected

---

### 19.4 Test Module Placement

**Decision:** Place lifecycle tests in `arctra-core/src/test/java`

**Rationale:**

1. **DefaultAgentProcess is package-private in arctra-core**
   - Test must access `cn.bitcss.arctra.runtime` package
   - Cannot cross module boundaries with package-private

2. **AgentProcess lifecycle is framework-neutral**
   - Does not require Spring AI
   - Does not require arctra-runtime-react
   - Core concept, core test location

3. **FakeSuspendingEngine is framework test fixture**
   - Implements AgentExecutionEngine (core interface)
   - No Spring AI dependency
   - Belongs in core test

**Final test placement:**

```
arctra-core/src/test/java/cn/bitcss/arctra/runtime/
  ├── FakeSuspendingEngine.java
  └── AgentProcessLifecycleTest.java
```

**arctra-runtime-react:** NO test changes for M4-T2

---

### 19.5 Final Production Changes

**arctra-core (production):**

1. ✅ `ProcessStatus.java` - Javadoc enhancement (RUNNING note)
2. ✅ `DefaultAgentProcess.java` - Javadoc enhancement (closure note)

**Total: 2 files, Javadoc only, zero logic changes**

**arctra-runtime-react (production):**

❌ **NO CHANGES**

---

### 19.6 Final Test Changes

**arctra-core/test:**

1. ✅ `FakeSuspendingEngine.java` (NEW)
   - Test-only implementation of AgentExecutionEngine
   - Three modes: COMPLETE, SUSPEND_ONCE, SUSPEND_TWICE
   - Directly materializes DefaultAgentProcess for verification

2. ✅ `AgentProcessLifecycleTest.java` (NEW)
   - 13 lifecycle verification tests
   - Tests AgentProcess contract
   - Tests Dynamic Materialization semantic
   - Tests concurrency/failure semantics
   - Verifies Session ≠ Process boundary

**Total: 2 files, test scope only**

**arctra-runtime-react/test:**

❌ **NO CHANGES**

---

### 19.7 Public API Delta

**Public API (arctra-core):**

✅ **NO changes** - Already implemented in prior work:
- AgentProcess interface
- ProcessStatus enum
- ContinuationSignal sealed interface
- AgentResult evolution

**Documentation refinements:**
- ProcessStatus.RUNNING Javadoc (internal enhancement)
- DefaultAgentProcess Javadoc (implementation note)

**No new public types. No breaking changes. No additive API.**

---

### 19.8 Production Integration Status

**M4-T2:**

❌ **NO production integration**

Zero production logic changes. Lifecycle contract verified through test infrastructure only.

**Future (M4-T3/M4-T4):**

Will implement:
1. ✅ Governance interception (Tool → REQUIRE_APPROVAL)
2. ✅ Suspension bridge (Governance → Engine/Runtime communication)
3. ✅ Production Process materialization (triggered by real suspension)
4. ✅ Real Controlled Re-entry (Model continuation)
5. ✅ Spring AI Tool Calling Loop restoration
6. ✅ Integration pattern (Runtime middleware? Engine wrapper? TBD)

**M4-T2 explicitly defers all production integration decisions.**

---

### 19.9 M4-T3 Responsibility

**M4-T3 must answer:**

1. **Who triggers suspension in production?**
   - Governance layer intercepts Tool invocation
   - Decides ALLOW / DENY / REQUIRE_APPROVAL

2. **How does suspension signal reach materialization point?**
   - Exception? Return value? Callback?
   - Where is the interception boundary?

3. **Who materializes Process in production?**
   - Engine? Runtime middleware? Governance itself?
   - What captures continuation state?

4. **What is the Controlled Re-entry mechanism?**
   - How to restore Spring AI execution context?
   - How to let Model see tool result?
   - How to continue reasoning?

5. **What is the production continuation state schema?**
   - Beyond closure (for future persistence)
   - Serializable representation
   - Migration from M4 closure-based

**M4-T2 does NOT answer these questions.**

---

### 19.10 Ready for Implementation?

✅ **YES - APPROVED**

**Pre-conditions met:**

1. ✅ Contract reconciled with M4-T1
2. ✅ Task semantic clarified (verification, not integration)
3. ✅ Real consumer statement corrected
4. ✅ Test module placement decided (core/test)
5. ✅ Production changes minimal (Javadoc only)
6. ✅ Test strategy sound (FakeSuspendingEngine)
7. ✅ No production/test dependency
8. ✅ M4-T3 responsibility clearly deferred
9. ✅ All superseded sections marked
10. ✅ Single source of truth (Part 17)

**Blockers:** NONE

**Next Action:** Begin M4-T2 Implementation Phase 1 (Javadoc enhancements)

---

**M4-T2 FINAL DESIGN RECONCILIATION COMPLETE**

**Implementation approved. Task semantic clarified. Boundaries protected.**

---

**End of Document**
