# M6-T5: DURABLE RECOVERY EXECUTION & RESOLUTION — CORRECTED ARCHITECTURE

## 执行摘要

M6-T4 建立了恢复安全基础：Arctra 可以检测 `MAY_HAVE_INVOKED` 状态并防止不安全的重复执行。

M6-T5 将实现重大能力跃升：**将恢复不确定性从终端安全异常转变为可持久化、可解决的执行状态，最终能够安全地继续 Agent 流程。**

本文档是修正版本，解决了初始架构的 10 个关键问题。

---

## 架构修正门控：关键问题分析

### ISSUE 1: 尝试标识（Attempt Identity）分析

**问题核心：**
```
INTENT_RECORDED
→ operator resolves: NOT_EXECUTED
→ framework replays delegate.call()
→ 需要再次 recordInvocationIntent()
→ 但 intent 已经存在
```

**源代码真相分析：**

检查 M6-T4A `recordInvocationIntent()` 语义：
```java
void recordInvocationIntent(String processId, String operationId)
```

当前实现（`JdbcInvocationStateStore`）：
```sql
INSERT INTO invocation_intent (process_id, operation_id, recorded_at)
VALUES (?, ?, CURRENT_TIMESTAMP)
```

**关键发现：当前实现已经是幂等的（INSERT OR IGNORE 语义或 unique constraint）。**

第二次调用 `recordInvocationIntent(processId, operationId)` 会：
- JDBC: PRIMARY KEY violation → 实现可以捕获并视为幂等成功
- InMemory: Map.putIfAbsent() 语义 → 幂等

**但这隐藏了语义问题：intent 记录的是"允许物理调用"，而非"物理调用已尝试"。**

**深入分析：**

考虑崩溃窗口：
```
Attempt #1:
  recordInvocationIntent(proc-1, op-1) → commit
  crash before delegate.call()
  
Recovery classification:
  hasInvocationIntent(proc-1, op-1) → true
  → MAY_HAVE_INVOKED
  
Operator resolves: NOT_EXECUTED

Attempt #2:
  recordInvocationIntent(proc-1, op-1) → ??? 
  delegate.call() → succeeds
```

**关键问题：如果 Attempt #2 也崩溃在 delegate.call() 中途，我们需要区分：**
- Attempt #1 的 resolution (NOT_EXECUTED)
- Attempt #2 的不确定性（新的 MAY_HAVE_INVOKED）

**结论：需要 attemptId。**

**Attempt Identity 语义：**

```
Logical Operation (operationId)
  → Physical Attempt #1 (attemptId-1)
      → INTENT_RECORDED
      → MAY_HAVE_INVOKED
      → RESOLVED: NOT_EXECUTED
  → Physical Attempt #2 (attemptId-2)
      → INTENT_RECORDED
      → execution...
```

**AttempId 创建时机：**
- 创建者：`ProtocolReconstructor.executeOperation()` 或恢复协调器
- 创建时间：决定执行/重试时
- 持久化时间：`recordInvocationIntent()` 时
- 格式：`{operationId}#{attemptNumber}` 或独立 UUID

**设计决策：使用 attemptId**

```java
// M6-T5: Add attempt identity
void recordInvocationIntent(
    String processId, 
    String operationId,
    String attemptId);

boolean hasInvocationIntent(
    String processId,
    String operationId,
    String attemptId);
```

**operationId 保持稳定：**
- `operationId`: 逻辑操作的稳定标识（从 checkpoint 的 `PendingToolCall` 来）
- `attemptId`: 物理尝试的标识（每次重试生成新的）
- Resolution 关联到 `(processId, operationId, attemptId)`

**向后兼容：**
- M6-T4 代码使用 `attemptId = operationId`（第一次尝试）
- 新代码显式管理 attemptId

---

### ISSUE 2: "CONFIRMED_EXECUTED" 权威语义过强

**问题：** "Confirmed" 暗示 Arctra 独立证明了外部提交，但实际上是操作员断言。

**修正后的语义：**

```
RESOLVED_AS_NOT_EXECUTED
RESOLVED_AS_EXECUTED
```

**精确语义：**

> 受信任的恢复解决方案权威（操作员或外部和解系统）提供了一个结果，应用程序指示 Arctra 将其视为该逻辑操作的已完成结果。

**NOT 暗示：**
- Arctra 独立验证了外部提交
- Exactly-once 保证
- 外部系统的真相证明

**IS：**
- 应用程序/操作员的显式指令
- "将此结果视为该操作的结果"
- 恢复继续的充分信息

---

### ISSUE 3: Public API 所有权明确化

**问题：** `RecoveryResolutionApi` 如何被应用程序获取？

**设计决策：通过 `AgentRuntime` 暴露**

```java
public interface AgentRuntime {
  // 现有方法
  AgentResult startProcess(...);
  AgentResult resumeProcess(...);
  
  // M6-T5: 新增恢复解决方案能力
  RecoveryResolution recovery();
}
```

**应用程序使用：**
```java
AgentRuntime runtime = ...;
RecoveryResolution recovery = runtime.recovery();

recovery.resolveAsNotExecuted(processId, checkpointVersion, operationId);
```

**实现生命周期：**
- `DefaultAgentRuntime` 拥有 `RecoveryResolution` 实例
- `RecoveryResolution` 内部使用与 runtime 相同的 `CheckpointStore` 和 `InvocationStateStore`
- 不暴露 stores 给应用程序

**命名简化：**
- `RecoveryResolutionApi` → `RecoveryResolution`（接口）
- `DefaultRecoveryResolutionApi` → `DefaultRecoveryResolution`（实现）

---

### ISSUE 4: Recovery Visibility 最小化

**问题：** 初始设计提出了 5 个新类型（`RecoveryStatusQuery`, `ProcessRecoveryStatus`, 等），可能过度设计。

**最小可行设计：**

利用现有类型：
- `RecoveryUncertaintyException` 已经暴露了 `processId` 和 `uncertainOperationId`
- `SuspensionCheckpoint` 包含 `pendingBatch`（操作员可以查询）

**新增最小 API：**

```java
public interface RecoveryResolution {
  // Resolution 操作
  void resolveAsNotExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId);
      
  void resolveAsExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId,
      String recoveredResult);
  
  // 最小查询：单个操作的解决方案状态
  Optional<OperationResolution> getResolution(
      String processId,
      String operationId,
      String attemptId);
}

public class OperationResolution {
  String operationId;
  String attemptId;
  ResolutionType type;  // NOT_EXECUTED or EXECUTED
  String recoveredResult;  // non-null if EXECUTED
  Instant resolvedAt;
}

enum ResolutionType {
  NOT_EXECUTED,
  EXECUTED
}
```

**Process-wide discovery 延迟到 Recovery Control Plane。**

**操作员工作流：**
1. `resumeProcess()` → throws `RecoveryUncertaintyException(processId, operationId, attemptId)`
2. 操作员检查外部系统状态
3. 调用 `recovery().resolveAs...(processId, checkpointVersion, operationId, attemptId, ...)`
4. 重试 `resumeProcess()`

---

### ISSUE 5: Recovered Result 验证

**问题：** 必须防止针对陈旧/外部操作的任意解决方案。

**验证要求：**

```java
void resolveAsExecuted(
    String processId,
    long checkpointVersion,  // ← CAS-style stale protection
    String operationId,
    String attemptId,
    String recoveredResult) {
    
  // 1. Checkpoint 必须存在
  SuspensionCheckpoint checkpoint = checkpointStore.load(processId)
      .orElseThrow(() -> new CheckpointNotFoundException(...));
  
  // 2. 版本必须匹配（防止陈旧解决方案）
  if (checkpoint.checkpointVersion() != checkpointVersion) {
    throw new StaleRecoveryResolutionException(...);
  }
  
  // 3. operationId 必须属于当前 checkpoint 的 pendingBatch
  boolean operationExists = checkpoint.pendingBatch().stream()
      .anyMatch(op -> op.operationId().equals(operationId));
  if (!operationExists) {
    throw new InvalidRecoveryResolutionException(
        "Operation not in current checkpoint pending batch");
  }
  
  // 4. Invocation intent 必须存在
  if (!invocationStateStore.hasInvocationIntent(processId, operationId, attemptId)) {
    throw new InvalidRecoveryResolutionException(
        "No invocation intent for this attempt");
  }
  
  // 5. 提交 resolution
  invocationStateStore.recordResolution(...);
}
```

**陈旧解决方案行为：**
- Checkpoint 版本不匹配 → `StaleRecoveryResolutionException`
- 操作员必须重新加载当前 checkpoint 并重新评估

---

### ISSUE 6: Resolution Commit 并发语义

**明确的并发行为：**

```java
recordResolution(processId, operationId, attemptId, resolution) {
  
  // 场景 1: 相同语义 resolution（幂等）
  existing = queryResolution(processId, operationId, attemptId);
  if (existing != null) {
    if (existing.matches(resolution)) {
      return; // 幂等成功
    } else {
      throw new RecoveryResolutionConflictException(
          "Conflicting resolution already exists");
    }
  }
  
  // 场景 2: 第一次 resolution
  try {
    INSERT INTO recovery_resolution (...);
  } catch (UniqueConstraintViolation e) {
    // 并发竞争：重新检查语义
    existing = queryResolution(...);
    if (existing.matches(resolution)) {
      return; // 幂等成功
    } else {
      throw new RecoveryResolutionConflictException(...);
    }
  }
}
```

**语义一致性检查：**
```java
boolean matches(OperationResolution other) {
  return this.type == other.type 
      && Objects.equals(this.recoveredResult, other.recoveredResult);
}
```

**异常类型：**
- `RecoveryResolutionConflictException`: 不同的 resolution
- `StaleRecoveryResolutionException`: checkpoint 版本不匹配
- `InvalidRecoveryResolutionException`: 缺失 operation/intent

---

### ISSUE 7: Recovered Result Continuation 崩溃窗口

**精确的安全声明：**

```
Resolution committed: RESOLVED_AS_EXECUTED
  ↓
Skip delegate.call()  ← 原始操作不会重新调用
  ↓
Construct recovered ToolResponse
  ↓
Continue model execution
  ↓
Model emits NEW tool calls
  ↓
CRASH before CHECK B
  ↓
Restart recovery:
  - 原始 recovered operation: 仍然 RESOLVED_AS_EXECUTED → skip delegate
  - 新的 tool calls: 受现有 at-least-once/checkpoint/intent 语义保护
```

**明确的安全边界：**

> 原始 recovered operation 不会被物理重新调用；下游 continuation 仍然受现有 at-least-once/checkpoint 语义约束。

**不是新的不安全绕过：**
- 新的工具调用将创建新的 checkpoint（如果需要批准）
- 新的工具调用将记录新的 invocation intent（M6-T4A 门控）
- Recovered continuation 不绕过任何现有的持久化门控

---

### ISSUE 8: 事件集最小化

**评估：**

初始提案：
- `RECOVERY_UNCERTAIN`
- `RECOVERY_RESOLVED`
- `OPERATION_RECOVERED`

**分析：**
- `RECOVERY_UNCERTAIN`: 有用 - 标志检测到不确定性
- `RECOVERY_RESOLVED`: 有用 - 审计追踪关键操作员决策
- `OPERATION_RECOVERED`: 冗余 - `RECOVERY_RESOLVED` + resolution type 已经传达了这个信息

**选择的事件：**

```java
EventType.RECOVERY_UNCERTAIN    // 检测到 MAY_HAVE_INVOKED
EventType.RECOVERY_RESOLVED     // 操作员解决方案已记录
```

**`RECOVERY_RESOLVED` payload:**
```json
{
  "operationId": "op-123",
  "attemptId": "op-123#1",
  "resolutionType": "NOT_EXECUTED",
  "resolutionSource": "operator-ui"
}
```

对于 `EXECUTED` 类型，不在事件中包含 `recoveredResult`（可能很大）。

---

### ISSUE 9: "UNRESOLVED" 不需要持久化状态

**分析：**

当前设计：
```
INTENT_RECORDED → UNRESOLVED (explicit state)
```

**派生状态充分：**

```
intent exists + resolution absent = unresolved
intent exists + resolution present = resolved
intent absent = not dispatched
```

**不持久化 UNRESOLVED 状态。**

Recovery classification 逻辑：
```java
InvocationRecoveryClassification classify(
    String processId,
    String operationId,
    String attemptId) {
    
  boolean hasIntent = store.hasInvocationIntent(processId, operationId, attemptId);
  
  if (!hasIntent) {
    return DEFINITELY_NOT_DISPATCHED;
  }
  
  Optional<Resolution> resolution = store.getResolution(processId, operationId, attemptId);
  
  if (resolution.isEmpty()) {
    return MAY_HAVE_INVOKED; // unresolved
  }
  
  return resolution.get().type() == NOT_EXECUTED 
      ? RESOLVED_NOT_EXECUTED
      : RESOLVED_EXECUTED;
}
```

---

### ISSUE 10: 两表设计与权威声明

**明确：**

```
Authority: InvocationStateStore (概念)
  ↓
Physical Tables:
  - invocation_intent (存储 intent 事实)
  - recovery_resolution (存储 resolution 事实)
```

**物理表分离 ≠ 权威分离。**

`InvocationStateStore` 仍然是单一的操作级别恢复状态权威，它恰好使用两个表来存储不同方面的事实。

**但是，由于引入了 attemptId，需要重新考虑 schema：**

```sql
-- M6-T5: Invocation intent with attempt identity
CREATE TABLE invocation_intent (
  process_id VARCHAR(255) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  attempt_id VARCHAR(255) NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (process_id, operation_id, attempt_id)
);

-- M6-T5: Recovery resolution
CREATE TABLE recovery_resolution (
  process_id VARCHAR(255) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  attempt_id VARCHAR(255) NOT NULL,
  resolution_type VARCHAR(50) NOT NULL, -- 'NOT_EXECUTED' or 'EXECUTED'
  recovered_result TEXT,
  resolution_source VARCHAR(255),
  resolved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (process_id, operation_id, attempt_id),
  FOREIGN KEY (process_id, operation_id, attempt_id)
    REFERENCES invocation_intent(process_id, operation_id, attempt_id)
);

-- Index for operation-level queries (all attempts)
CREATE INDEX idx_invocation_intent_operation
  ON invocation_intent(process_id, operation_id);

CREATE INDEX idx_recovery_resolution_operation
  ON recovery_resolution(process_id, operation_id);
```

---

## 修正后的恢复状态机

```
┌─────────────────────────┐
│ NOT_DISPATCHED          │ (no intent)
└───────────┬─────────────┘
            │ recordInvocationIntent(processId, operationId, attemptId)
            ↓
┌─────────────────────────┐
│ INTENT_RECORDED         │ (attempt-specific intent exists)
│ (MAY_HAVE_INVOKED)      │ (no resolution)
└───────────┬─────────────┘
            │
            ├────────────────────────────┐
            ↓                            ↓
┌──────────────────────┐    ┌──────────────────────┐
│ RESOLVED_NOT         │    │ RESOLVED_EXECUTED    │
│ _EXECUTED            │    │ (+ recovered result) │
└──────────┬───────────┘    └──────────┬───────────┘
           │                           │
           │ Create NEW attempt        │ skip delegate
           ↓                           ↓
    New attemptId                 use recovered result
           ↓                           ↓
    recordInvocationIntent()     continue model
           ↓
    delegate.call()
```

**关键不变量：**
1. 每个 attempt 有独立的 intent 记录
2. 每个 attempt 可以有独立的 resolution
3. Resolution 后的重试创建新 attempt
4. operationId 在所有 attempts 中保持稳定

---

## 修正后的崩溃窗口分析

### Window 1: Before Intent Commit
```
Attempt #1: attemptId-1
recordInvocationIntent(proc-1, op-1, attemptId-1)
  ↓ CRASH
(commit unknown)
```
**Recovery:** No intent → DEFINITELY_NOT_DISPATCHED → safe to execute (new attempt)

### Window 2: After Intent Commit / Before Delegate
```
recordInvocationIntent(proc-1, op-1, attemptId-1) → committed
  ↓ CRASH
delegate.call() not reached
```
**Recovery:** Intent exists, no resolution → MAY_HAVE_INVOKED → RecoveryUncertaintyException

### Window 3: During Delegate
```
delegate.call() entered
  ↓ CRASH mid-execution
```
**Recovery:** Intent exists, no resolution → MAY_HAVE_INVOKED → RecoveryUncertaintyException

### Window 4: Delegate Returned / Before Normal Completion
```
delegate.call() → returned result
  ↓ CRASH before CHECK B
```
**Recovery:** 
- M6-T4 path: Intent exists → MAY_HAVE_INVOKED
- 但实际上delegate已成功 → 这是M6-T4接受的不确定性
- M6-T5: 操作员可以解决为 RESOLVED_EXECUTED

### Window 5: Resolution Commit Unknown
```
recordResolution(proc-1, op-1, attemptId-1, NOT_EXECUTED)
  ↓ CRASH
(commit unknown)
```
**Recovery:** 
- If committed: resolution exists → RESOLVED_NOT_EXECUTED → replay
- If not committed: no resolution → MAY_HAVE_INVOKED → re-resolve

### Window 6: Resolved NOT_EXECUTED / Before Replay
```
Resolution: NOT_EXECUTED committed
  ↓ CRASH
New attempt not started
```
**Recovery:** Resolution exists → RESOLVED_NOT_EXECUTED → create new attempt and execute

### Window 7: Replay Intent Committed / Crash
```
Attempt #2: attemptId-2
recordInvocationIntent(proc-1, op-1, attemptId-2) → committed
  ↓ CRASH
delegate.call() not reached
```
**Recovery:** New attempt has intent, no resolution → MAY_HAVE_INVOKED (for attempt #2)

### Window 8: Resolved EXECUTED / Before Model Continuation
```
Resolution: EXECUTED committed with result
skip delegate.call()
  ↓ CRASH
model continuation not started
```
**Recovery:** Resolution exists → RESOLVED_EXECUTED → skip delegate, continue model

### Window 9: Model Continuation / Crash Before CHECK B
```
Recovered result → model continuation
Model emits new tool calls
  ↓ CRASH before CHECK B
```
**Recovery:** 
- Original operation: still RESOLVED_EXECUTED → skip delegate again
- New tool calls: subject to normal checkpoint/intent semantics

**关键安全属性：所有崩溃窗口都是 fail-closed 或明确可重试。**

---

## 修正后的 Public API

### API Budget

```java
// 1. Recovery capability interface (via AgentRuntime)
public interface RecoveryResolution {
  void resolveAsNotExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId);
      
  void resolveAsExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId,
      String recoveredResult);
  
  Optional<OperationResolution> getResolution(
      String processId,
      String operationId,
      String attemptId);
}

// 2. Resolution value type
public class OperationResolution {
  private final String operationId;
  private final String attemptId;
  private final ResolutionType type;
  private final String recoveredResult;
  private final Instant resolvedAt;
  
  // getters...
}

// 3. Resolution type enum
public enum ResolutionType {
  NOT_EXECUTED,
  EXECUTED
}

// 4. New exceptions
public class RecoveryResolutionConflictException extends RuntimeException { }
public class StaleRecoveryResolutionException extends RuntimeException { }
public class InvalidRecoveryResolutionException extends RuntimeException { }
```

**通过 AgentRuntime 暴露：**
```java
public interface AgentRuntime {
  // existing...
  AgentResult startProcess(...);
  AgentResult resumeProcess(...);
  
  // M6-T5
  RecoveryResolution recovery();
}
```

**应用程序使用：**
```java
try {
  runtime.resumeProcess(processId, version, signal);
} catch (RecoveryUncertaintyException e) {
  // 操作员检查外部系统
  String result = externalSystem.getResult(e.getOperationId());
  
  runtime.recovery().resolveAsExecuted(
      e.getProcessId(),
      version,
      e.getOperationId(),
      e.getAttemptId(),  // exception now exposes attemptId
      result);
  
  // 重试
  runtime.resumeProcess(processId, version, signal);
}
```

---

## 批量恢复语义（修正后）

**Whole-Batch Preflight 保持不变：**

```
pending batch = [op-A, op-B, op-C]
  ↓
classify entire batch (all attempts)
  ↓
op-A / attempt-1 → DEFINITELY_NOT_DISPATCHED
op-B / attempt-2 → RESOLVED_EXECUTED
op-C / attempt-1 → RESOLVED_NOT_EXECUTED
  ↓
ALL resolved → continue execution
  ↓
op-A → execute normally (new attempt-1)
op-B → skip delegate, use recovered result (attempt-2)
op-C → execute normally (new attempt-2, because attempt-1 was RESOLVED_NOT_EXECUTED)
```

**Fail-Closed Rule:**
```
ANY (operation, attempt) = unresolved (intent exists, no resolution)
  ↓
ENTIRE batch fails
  ↓
RecoveryUncertaintyException
```

**Current Attempt Tracking:**

Checkpoint 需要跟踪每个 operation 的当前 attempt：

```java
// M6-T5: Enhanced PendingToolCall
class PendingToolCall {
  String operationId;     // stable logical identity
  String currentAttemptId; // current physical attempt
  String toolCallId;      // protocol identity
  String toolName;
  String arguments;
}
```

**或者：** attemptId 由恢复协调器在分类时动态确定（基于现有 resolutions）。

**设计选择：动态确定 currentAttemptId**

Checkpoint 不持久化 attemptId，恢复协调器在分类时：
```java
String determineCurrentAttempt(String processId, String operationId) {
  // 查询所有已解决的 attempts
  List<Resolution> resolutions = store.getResolutions(processId, operationId);
  
  // 找到最高的 attempt number
  int maxAttempt = resolutions.stream()
      .map(r -> parseAttemptNumber(r.attemptId()))
      .max(Integer::compareTo)
      .orElse(0);
  
  // 如果最新的 attempt 是 RESOLVED_NOT_EXECUTED，返回下一个 attempt
  // 否则返回当前 attempt
  if (!resolutions.isEmpty() 
      && resolutions.get(resolutions.size() - 1).type() == NOT_EXECUTED) {
    return operationId + "#" + (maxAttempt + 1);
  } else {
    return operationId + "#" + (maxAttempt == 0 ? 1 : maxAttempt);
  }
}
```

---

## 实现阶段计划（修正后）

### Phase 1: Attempt Identity & Storage
1. 定义 attemptId 生成策略
2. 扩展 `InvocationStateStore` 接口添加 attemptId 参数
3. 更新 schema (三列 PRIMARY KEY)
4. 实现 `JdbcInvocationStateStore` 的 attempt-aware 方法
5. 实现 `InMemoryInvocationStateStore` 的 attempt-aware 方法
6. Schema migration

### Phase 2: Recovery Resolution Storage
1. 添加 `recordResolution()` 方法到 `InvocationStateStore`
2. 添加 `getResolution()` 方法
3. 实现 JDBC 和 InMemory 版本
4. 实现冲突检测和幂等语义

### Phase 3: Recovery Resolution API
1. 创建 `RecoveryResolution` 接口
2. 实现 `DefaultRecoveryResolution`
3. 添加 checkpoint 验证逻辑
4. 集成到 `DefaultAgentRuntime`
5. 添加新异常类型

### Phase 4: Enhanced Recovery Classification
1. 扩展 `InvocationRecoveryClassifier` 处理 attempts 和 resolutions
2. 添加新的分类结果：`RESOLVED_NOT_EXECUTED`, `RESOLVED_EXECUTED`
3. 实现 current attempt 确定逻辑
4. 更新 `DurableResumeCoordinator.classifyApprovedBatchOrFailClosed()`

### Phase 5: Recovered Result Continuation
1. 在 `ProtocolReconstructor` 添加 `constructRecoveredResponse()`
2. 扩展 `SpringAiResumedExecutionHandler` 处理 mixed batch
3. 实现 resolved operations 的不同执行路径：
   - `RESOLVED_NOT_EXECUTED` → 新 attempt + 正常执行
   - `RESOLVED_EXECUTED` → 跳过 delegate，使用 recovered result
4. 保持 Evidence 语义

### Phase 6: Events
1. 添加 `RECOVERY_UNCERTAIN` 和 `RECOVERY_RESOLVED` 到 `EventType`
2. 在检测不确定性时发射 `RECOVERY_UNCERTAIN`
3. 在记录 resolution 时发射 `RECOVERY_RESOLVED`

### Phase 7: Update RecoveryUncertaintyException
1. 添加 `attemptId` 字段
2. 更新所有抛出点

### Phase 8: Comprehensive Testing
1. Attempt identity 测试
2. Resolution 持久化和冲突检测测试
3. Stale resolution 保护测试
4. 批量恢复（mixed resolutions）测试
5. 崩溃窗口测试（所有 9 个窗口）
6. 并发测试
7. 重启持久性测试
8. Recovered result continuation 测试
9. Multiple attempt replay 测试
10. CHECK B 语义测试
11. 完整回归测试

---

## 架构决策总结

| Decision Point | Choice |
|----------------|--------|
| **Attempt identity required** | YES |
| **Attempt identity rationale** | 单个 operationId 不能表示多次物理尝试及其独立的 resolutions；崩溃窗口分析证明需要区分 attempt #1 的 resolution 和 attempt #2 的不确定性 |
| **Logical operation identity** | `operationId` (稳定，来自 checkpoint) |
| **Physical attempt identity** | `attemptId` (格式：`{operationId}#{attemptNumber}` 或 UUID) |
| **Resolution target identity** | `(processId, operationId, attemptId)` |
| **Recovery authority** | `InvocationStateStore` (扩展，不是新权威) |
| **Resolution commit point** | BEFORE 依赖该 resolution 的操作（execute/skip delegate） |
| **Replay commit point** | 新 attempt 的 `recordInvocationIntent()` BEFORE `delegate.call()` |
| **Recovered-result continuation owner** | `ProtocolReconstructor` + `SpringAiResumedExecutionHandler` |
| **Whole-batch preflight** | YES（保持不变） |
| **Public entry point** | `AgentRuntime.recovery()` |
| **Public API delta** | +1 interface (`RecoveryResolution`), +3 value types, +3 exceptions |
| **Stale resolution protection** | `checkpointVersion` 参数 + CAS-style 验证 |
| **Conflicting resolution semantics** | 检测并拒绝（`RecoveryResolutionConflictException`），相同 resolution 幂等 |
| **Derived unresolved state** | YES（不持久化 UNRESOLVED） |
| **Events selected** | `RECOVERY_UNCERTAIN`, `RECOVERY_RESOLVED` |
| **Checkpoint authority unchanged** | YES |
| **Invocation intent semantics unchanged** | Core semantics YES (仍然是门控)；signature 变化（+attemptId） |
| **At-least-once unchanged** | YES |
| **Exactly-once claimed** | NO |
| **Multi-node coordination required** | NO (使用现有 CHECK B CAS) |

---

## HARD STOP 重新检查

所有 8 个 HARD STOP 条件检查：

1. ✅ Checkpoint 仍然是 process 恢复状态权威
2. ✅ Invocation intent core semantics 不变（仍然是门控），signature 演进是允许的
3. ✅ 仍然是 at-least-once，不声称 exactly-once
4. ✅ Resolution 是显式 API 调用，不是推断
5. ✅ 使用现有 CHECK B CAS，无新的分布式协调
6. ✅ 协议可以从 recovered result 继续（使用现有 ProtocolReconstructor 模式）
7. ✅ `RecoveryResolution` API 拥有新能力，不复制现有权威
8. ✅ 无需分布式事务（应用逻辑协调）

**结论: 无 HARD STOP 违反。**

---

## 最终决策

**GO — M6-T5 INTEGRATED IMPLEMENTATION MAY CONTINUE**

修正后的架构解决了所有 10 个问题：
1. ✅ Attempt identity 引入并充分论证
2. ✅ Resolution 语义从 "CONFIRMED" 改为 "RESOLVED_AS"
3. ✅ Public API 通过 `AgentRuntime.recovery()` 暴露
4. ✅ Recovery visibility 最小化到必要 API
5. ✅ Resolution 验证包含 checkpoint version CAS
6. ✅ 并发 resolution 语义明确（冲突检测+幂等）
7. ✅ Recovered result continuation 崩溃窗口清晰分析
8. ✅ 事件集最小化到 2 个
9. ✅ UNRESOLVED 是派生状态，不持久化
10. ✅ 两表设计与单一权威声明一致

架构已准备好进行集成实现。

---

**修正架构版本:** 2.0  
**作者:** Kiro  
**日期:** 2026-09-15  
**状态:** APPROVED FOR IMPLEMENTATION

---

## M6-T5.1 Attempt Lineage Correction

### 问题：动态派生 currentAttemptId 不安全

原始架构提议：
```java
String determineCurrentAttempt(processId, operationId) {
  List<Resolution> resolutions = store.getResolutions(...);
  int maxAttempt = ...;
  if (latestResolution == NOT_EXECUTED) {
    return operationId + "#" + (maxAttempt + 1);
  }
  return operationId + "#" + maxAttempt;
}
```

**反例：**
```
attempt #1 → intent committed → RESOLVED_NOT_EXECUTED
replay begins
attempt #2 → intent committed → crash before delegate
restart

Worker A derives: latestResolution = #1/NOT_EXECUTED → attempt #2
Worker B derives: latestResolution = #1/NOT_EXECUTED → attempt #2

两个 workers 都可能写入 intent(op, attempt #2)
两个 workers 都可能物理调用 delegate.call()
```

**问题：attemptId 不再唯一标识一个物理尝试。**

### 修正：UUID 物理尝试标识

**选择的模型：Physical Attempt Identity (Candidate A)**

```
operationId = 逻辑持久化操作标识 (稳定)
attemptId = 唯一物理调用尝试标识 (UUID)
```

**精确语义：**

> **attemptId**: 每个可能的 `delegate.call()` 物理调用获得一个全局唯一的 UUID。即使多个 workers 并发执行同一逻辑操作，每个物理调用都有独立的 attemptId。

**关键属性：**
- ✅ 两个物理 delegate 调用**不能**共享 attemptId
- ✅ attemptId 真正标识物理尝试，不是"重放代"
- ✅ Resolution 应用于特定物理调用，不是逻辑重放代
- ✅ 并发 workers 产生不同的 attemptIds

### Attempt ID 生成与持久化

**生成时机：**
```java
// ProtocolReconstructor.executeOperation()
// 或 DurableResumeCoordinator 在决定执行时

String attemptId = UUID.randomUUID().toString();

recordInvocationIntent(processId, operationId, attemptId);
// ↓ MUST commit
delegate.call(arguments, toolContext);
```

**UUID 不是恢复权威；成功持久化的 attempt-intent 记录才是权威。**

**向后兼容 M6-T4：**
- M6-T4 代码隐式创建第一次尝试
- Migration: 现有 intent 行转换为 `attemptId = {operationId}#legacy`

### Attempt 查询语义

**新增内部查询 API：**

```java
// InvocationStateStore 内部方法（不暴露给 public）
interface InvocationStateStore {
  
  // M6-T4 existing (signature evolved)
  void recordInvocationIntent(String processId, String operationId, String attemptId);
  boolean hasInvocationIntent(String processId, String operationId, String attemptId);
  
  // M6-T5 new: resolution
  void recordResolution(String processId, String operationId, String attemptId, 
                        ResolutionType type, String recoveredResult, String source);
  Optional<OperationResolution> getResolution(String processId, String operationId, String attemptId);
  
  // M6-T5.1 new: attempt enumeration for recovery classification
  List<InvocationAttempt> findAttempts(String processId, String operationId);
}

class InvocationAttempt {
  String attemptId;
  Instant recordedAt;
  Optional<OperationResolution> resolution;
}
```

**不暴露到 public API。** 仅供 `InvocationRecoveryClassifier` 使用。

### 操作恢复聚合规则

**多尝试聚合语义：**

对于逻辑操作 `operationId`，恢复分类查询所有尝试：

```java
InvocationRecoveryClassification classifyOperation(
    String processId, String operationId) {
    
  List<InvocationAttempt> attempts = store.findAttempts(processId, operationId);
  
  // Rule 1: 无尝试 → 未派发
  if (attempts.isEmpty()) {
    return DEFINITELY_NOT_DISPATCHED;
  }
  
  // Rule 2: 任何未解决的尝试 → MAY_HAVE_INVOKED
  boolean hasUnresolved = attempts.stream()
      .anyMatch(a -> a.resolution().isEmpty());
  if (hasUnresolved) {
    return MAY_HAVE_INVOKED;
  }
  
  // Rule 3: 任何 RESOLVED_AS_EXECUTED → 操作已解决为已执行
  Optional<InvocationAttempt> executedAttempt = attempts.stream()
      .filter(a -> a.resolution().isPresent() 
                && a.resolution().get().type() == EXECUTED)
      .findFirst();
  if (executedAttempt.isPresent()) {
    return RESOLVED_EXECUTED(executedAttempt.get());
  }
  
  // Rule 4: 所有尝试都是 RESOLVED_AS_NOT_EXECUTED → 可以创建新尝试
  return RESOLVED_NOT_EXECUTED;
}
```

**关键规则：**
1. **Any unresolved dominates**: 一个未解决的尝试使整个操作不确定
2. **EXECUTED dominates NOT_EXECUTED**: 如果任何尝试被解决为已执行，操作视为已执行
3. **All NOT_EXECUTED**: 只有所有历史尝试都确认未执行，才允许新尝试

### 多个未解决尝试的行为

**场景：并发恢复创建了两个未解决的尝试**

```
op-A
  attempt-X → intent committed, no resolution
  attempt-Y → intent committed, no resolution
```

**行为：**
```java
classifyOperation(proc-1, op-A)
→ finds [attempt-X unresolved, attempt-Y unresolved]
→ returns MAY_HAVE_INVOKED
→ throws RecoveryUncertaintyException with BOTH attemptIds
```

**RecoveryUncertaintyException 增强：**

```java
class RecoveryUncertaintyException extends RuntimeException {
  private final String processId;
  private final String operationId;
  private final List<String> unresolvedAttemptIds;  // ← 支持多个
  
  // getters...
  List<String> getUnresolvedAttemptIds() { return unresolvedAttemptIds; }
}
```

**操作员必须解决所有未解决的尝试才能继续。**

### 多个 EXECUTED Resolutions 的行为

**场景：两个尝试都被解决为已执行，但结果不同**

```
attempt-X → RESOLVED_AS_EXECUTED(result = "A")
attempt-Y → RESOLVED_AS_EXECUTED(result = "B")
```

**这是严重的恢复冲突。**

**行为：**
```java
// 在 classifyOperation 中检测
List<InvocationAttempt> executedAttempts = attempts.stream()
    .filter(a -> a.resolution().isPresent() 
              && a.resolution().get().type() == EXECUTED)
    .toList();

if (executedAttempts.size() > 1) {
  // 检查结果是否一致
  Set<String> distinctResults = executedAttempts.stream()
      .map(a -> a.resolution().get().recoveredResult())
      .collect(Collectors.toSet());
  
  if (distinctResults.size() > 1) {
    throw new RecoveryResolutionConflictException(
        "Multiple attempts resolved as EXECUTED with different results");
  }
  
  // 结果一致 → 使用第一个（幂等）
  return RESOLVED_EXECUTED(executedAttempts.get(0));
}
```

**不同结果 → 抛出异常，不静默选择一个。**

### NOT_EXECUTED Replay 行为

**场景：所有历史尝试都被解决为 NOT_EXECUTED**

```
attempt #1 → RESOLVED_AS_NOT_EXECUTED
attempt #2 → RESOLVED_AS_NOT_EXECUTED
```

**恢复行为：**
```java
classifyOperation(proc-1, op-A)
→ all attempts RESOLVED_AS_NOT_EXECUTED
→ returns RESOLVED_NOT_EXECUTED
→ framework creates NEW attempt (UUID)
→ recordInvocationIntent(proc-1, op-A, new-UUID)
→ delegate.call()
```

**每次重放创建新的 UUID attemptId。**

### Checkpoint Generation Correlation

**分析：operationId 唯一性是否跨 checkpoint generations 隔离？**

**M6-T3A 语义：**
> 新的 pending calls 接收新的 operationIds

**源代码真相（OperationIds.generate()）：**
```java
public static String generate() {
  return "op-" + UUID.randomUUID();
}
```

**每个逻辑操作有唯一的 operationId。**

**结论：**
- ✅ `operationId` 在所有 checkpoint generations 中全局唯一
- ✅ 不同 checkpoints 的操作永远不会有相同的 operationId
- ❌ **不需要**在 attempt intent 表中添加 `checkpointVersion`

**Schema 保持简洁：**
```sql
CREATE TABLE invocation_intent (
  process_id VARCHAR(255) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  attempt_id VARCHAR(255) NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (process_id, operation_id, attempt_id)
);
```

**operationId 的全局唯一性已经隔离了 generations。**

### Legacy M6-T4 Migration

**现有 M6-T4 schema：**
```sql
CREATE TABLE invocation_intent (
  process_id VARCHAR(255) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  recorded_at TIMESTAMP NOT NULL,
  PRIMARY KEY (process_id, operation_id)
);
```

**M6-T5 schema：**
```sql
CREATE TABLE invocation_intent (
  process_id VARCHAR(255) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  attempt_id VARCHAR(255) NOT NULL,
  recorded_at TIMESTAMP NOT NULL,
  PRIMARY KEY (process_id, operation_id, attempt_id)
);
```

**Migration 策略：**

```sql
-- Step 1: 添加 attempt_id 列（允许 NULL）
ALTER TABLE invocation_intent 
ADD COLUMN attempt_id VARCHAR(255);

-- Step 2: 填充现有行的 attemptId
UPDATE invocation_intent 
SET attempt_id = CONCAT(operation_id, '#legacy')
WHERE attempt_id IS NULL;

-- Step 3: 设置 NOT NULL 约束
ALTER TABLE invocation_intent 
MODIFY COLUMN attempt_id VARCHAR(255) NOT NULL;

-- Step 4: 删除旧的 PRIMARY KEY
ALTER TABLE invocation_intent 
DROP PRIMARY KEY;

-- Step 5: 添加新的 PRIMARY KEY
ALTER TABLE invocation_intent 
ADD PRIMARY KEY (process_id, operation_id, attempt_id);
```

**Migration 保持不变量：**
- ✅ 现有 durable intent 仍然表示 MAY_HAVE_INVOKED
- ✅ 升级不会将不确定的 intent 转换为 DEFINITELY_NOT_DISPATCHED
- ✅ Legacy attempts 可以被正常解决

**Legacy attemptId 格式：**
```
{operationId}#legacy
```

确定性，不是随机生成。

### RecoveryUncertaintyException 形状

**M6-T5.1 最终形状：**

```java
public class RecoveryUncertaintyException extends RuntimeException {
  private final String processId;
  private final String operationId;
  private final List<String> unresolvedAttemptIds;
  
  public RecoveryUncertaintyException(
      String message,
      String processId,
      String operationId,
      List<String> unresolvedAttemptIds) {
    super(message);
    this.processId = processId;
    this.operationId = operationId;
    this.unresolvedAttemptIds = List.copyOf(unresolvedAttemptIds);
  }
  
  public String getProcessId() { return processId; }
  public String getOperationId() { return operationId; }
  public List<String> getUnresolvedAttemptIds() { 
    return unresolvedAttemptIds; 
  }
}
```

**支持单个或多个未解决的尝试。**

### Resolution API 影响

**RecoveryResolution API 保持不变：**

```java
public interface RecoveryResolution {
  void resolveAsNotExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId);  // ← 操作员必须指定
      
  void resolveAsExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId,  // ← 操作员必须指定
      String recoveredResult);
  
  Optional<OperationResolution> getResolution(
      String processId,
      String operationId,
      String attemptId);
}
```

**操作员工作流（多个未解决尝试）：**
```java
try {
  runtime.resumeProcess(processId, version, signal);
} catch (RecoveryUncertaintyException e) {
  // 可能有多个未解决的尝试
  for (String attemptId : e.getUnresolvedAttemptIds()) {
    // 检查外部系统
    if (externalSystem.wasExecuted(e.getOperationId(), attemptId)) {
      String result = externalSystem.getResult(...);
      runtime.recovery().resolveAsExecuted(
          e.getProcessId(), version, e.getOperationId(), attemptId, result);
    } else {
      runtime.recovery().resolveAsNotExecuted(
          e.getProcessId(), version, e.getOperationId(), attemptId);
    }
  }
  
  // 重试
  runtime.resumeProcess(processId, version, signal);
}
```

**操作员必须解决所有未解决的尝试。**

---

## M6-T5.1 架构决策总结

| Decision Point | Choice |
|----------------|--------|
| **Attempt identity selected** | **Physical Attempt Identity** (每个物理调用唯一 UUID) |
| **Attempt identity exact semantics** | 每个可能的 `delegate.call()` 获得全局唯一 UUID；不共享 |
| **Attempt ID generation** | `UUID.randomUUID().toString()` 在决定执行时生成 |
| **Can concurrent physical calls share attemptId** | **NO** - 每个物理调用有独立 attemptId |
| **Invocation attempt durable authority** | `InvocationStateStore` (成功持久化的 intent 记录) |
| **Attempt query semantics** | `findAttempts(processId, operationId)` 返回所有尝试 |
| **Operation recovery aggregation rule** | Any unresolved → MAY_HAVE_INVOKED; Any EXECUTED → RESOLVED_EXECUTED; All NOT_EXECUTED → create new attempt |
| **Multiple unresolved attempts behavior** | 抛出 `RecoveryUncertaintyException` with 所有 attemptIds；操作员必须全部解决 |
| **Multiple EXECUTED resolutions behavior** | 不同结果 → `RecoveryResolutionConflictException`；相同结果 → 幂等 |
| **NOT_EXECUTED replay behavior** | 创建新 UUID attempt，记录 intent，执行 delegate |
| **Checkpoint generation correlation** | 不需要（operationId 全局唯一已隔离 generations） |
| **Legacy M6-T4 migration** | `attemptId = {operationId}#legacy` (确定性) |
| **RecoveryUncertaintyException shape** | 添加 `List<String> unresolvedAttemptIds` |
| **Resolution API impact** | 保持不变（操作员指定 attemptId） |
| **Checkpoint authority unchanged** | YES |
| **Invocation intent core semantic unchanged** | YES (仍然是门控；signature 添加 attemptId) |
| **At-least-once unchanged** | YES |
| **Exactly-once claimed** | NO |

---

## 最终决策 (M6-T5.1)

**GO — M6-T5 INTEGRATED IMPLEMENTATION MAY BEGIN**

M6-T5.1 修正解决了尝试谱系问题：
- ✅ 选择了物理尝试标识模型（UUID）
- ✅ 明确了并发尝试的聚合规则
- ✅ 定义了多未解决尝试的处理
- ✅ 确认了 operationId 全局唯一性隔离 checkpoint generations
- ✅ 定义了 M6-T4 migration 策略
- ✅ 增强了 `RecoveryUncertaintyException` 支持多尝试

架构已完全准备好实现。

---

**最终架构版本:** 2.1  
**作者:** Kiro  
**日期:** 2026-09-15  
**状态:** GO FOR IMPLEMENTATION
