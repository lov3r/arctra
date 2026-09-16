# M6-T5: DURABLE RECOVERY EXECUTION & RESOLUTION — ARCHITECTURE

## 执行摘要

M6-T4 建立了恢复安全基础：Arctra 可以检测 `MAY_HAVE_INVOKED` 状态并防止不安全的重复执行。

但当前恢复在以下点停止：

```
MAY_HAVE_INVOKED
→ RecoveryUncertaintyException
→ process 保持未解决状态
```

M6-T5 将实现重大能力跃升：

**将恢复不确定性从终端安全异常转变为可持久化、可解决的执行状态，最终能够安全地继续 Agent 流程。**

---

## 1. 源代码真相审计

### 1.1 当前恢复调用图

```
AgentRuntime.resumeProcess()
  ↓
SpringAiToolCallingEngine.resumeProcess()
  ↓
DurableResumeCoordinator.resume()
  ↓
CHECK A: loadAndValidateCheckpoint()
  ↓
requiresRecoveryMode(checkpoint, currentEpoch)
  ↓
┌─────────────────────────────┬──────────────────────────────┐
│ Same Incarnation            │ Cross Incarnation            │
│ resumeNormalInternal()      │ resumeWithRecoveryInternal() │
└─────────────────────────────┴──────────────────────────────┘
                                         ↓
                    classifyApprovedBatchOrFailClosed()
                                         ↓
                    InvocationRecoveryClassifier.classify()
                                         ↓
                    InvocationStateStore.hasInvocationIntent()
                                         ↓
                    ┌────────────────────┴──────────────────┐
                    │ false                    │ true        │
                    │ DEFINITELY_NOT_DISPATCHED│ MAY_HAVE_INVOKED
                    └────────────────────┬──────────────────┘
                                         ↓
                              RecoveryUncertaintyException
                                         ↓
                              process remains suspended
```

### 1.2 当前持久化状态

**CHECK A 后异常时的持久化状态：**
- ✅ `SuspensionCheckpoint` 保持不变（未到达 CHECK B）
- ✅ `INVOCATION_INTENT(processId, operationId)` 存在于 `InvocationStateStore`
- ✅ `executionEpoch` 已在 checkpoint 中
- ❌ 没有恢复解决方案状态的持久化

**关键发现：**
1. 不确定性检测发生在 `DurableResumeCoordinator.classifyApprovedBatchOrFailClosed()`
2. 异常后状态完全持久化（checkpoint + invocation intent）
3. Checkpoint 包含继续执行所需的所有数据：
   - `pendingBatch`: `List<PendingToolCall>`
   - `accumulatedEvidences`: `List<Evidence>`
   - `runtimeBindingKey`
   - `executionEpoch`
   - `sessionId`
4. `ContinuationSignal` 已经是恢复输入的抽象（`ApprovalSignal` / `RejectionSignal`）
5. 工具结果重建路径已存在：`ProtocolReconstructor.constructDenialResponses()`
6. Evidence 累积机制已经支持非执行路径

### 1.3 当前架构边界

**持久化权威：**
- `CheckpointStore`: Process 恢复状态权威
- `InvocationStateStore`: Invocation intent 权威（M6-T4A）
- `ExecutionLedger`: 历史投影（非权威）
- `ChatMemory`: 会话状态（非恢复权威）

**事件语义：**
- `TOOL_EXECUTED`: 框架观察到 `delegate.call()` 正常返回
- `TOOL_FAILED`: 框架观察到 `delegate.call()` 抛出异常
- `RESUMED`: Process 恢复开始
- `CHECKPOINT_CREATED`: Checkpoint 创建
- `CHECKPOINT_DELETED`: Checkpoint 删除（CHECK B）

**关键不变量（M6-T4A）：**
```java
recordInvocationIntent(processId, operationId)  // 硬门控
// ↓ MUST complete before
delegate.call(arguments, toolContext)           // 物理调用
```

---

## 2. M6-T5 架构设计

### 2.1 恢复权威演进

**当前（M6-T4）：**
```
InvocationStateStore
  ↓
hasInvocationIntent(processId, operationId): boolean
  ↓
false → DEFINITELY_NOT_DISPATCHED
true  → MAY_HAVE_INVOKED
```

**M6-T5 演进：**
```
InvocationStateStore → RecoveryStateStore
  ↓
查询恢复状态（processId, operationId）
  ↓
NOT_DISPATCHED → 未派发
INTENT_RECORDED → 已记录意图（MAY_HAVE_INVOKED）
CONFIRMED_NOT_EXECUTED → 已确认未执行
CONFIRMED_EXECUTED → 已确认已执行（带结果）
```

**设计决策：扩展现有 `InvocationStateStore` 而不是创建新权威**

理由：
1. 避免"同一事实的两个权威"反模式
2. `InvocationStateStore` 已经是操作级别的持久化状态权威
3. 恢复解决方案本质上是 invocation state 的演进
4. 共享相同的持久化基础设施（DataSource、schema）
5. 保持 `processId + operationId` 作为唯一标识

**命名决策：**
- 保持 `InvocationStateStore` 接口名称（向后兼容）
- 内部实现演进为完整的恢复状态机
- 文档中称为"恢复状态权威"

### 2.2 恢复状态机

```
┌─────────────────┐
│ NOT_DISPATCHED  │ (初始状态，无 intent 记录)
└────────┬────────┘
         │ recordInvocationIntent()
         ↓
┌─────────────────┐
│ INTENT_RECORDED │ (MAY_HAVE_INVOKED)
└────────┬────────┘
         │
         ├──────────────────────┬──────────────────────┐
         ↓                      ↓                      ↓
┌─────────────────────┐ ┌─────────────────────┐ ┌──────────────┐
│ CONFIRMED_NOT       │ │ CONFIRMED_EXECUTED  │ │ UNRESOLVED   │
│ _EXECUTED           │ │ (+ recovered result)│ │ (持久等待)    │
└─────────┬───────────┘ └─────────┬───────────┘ └──────────────┘
          │                       │
          │ safe to execute       │ safe to continue
          ↓                       ↓
    delegate.call()        skip delegate, use result
```

**状态转换规则：**

1. `NOT_DISPATCHED → INTENT_RECORDED`
   - 触发器：`recordInvocationIntent()` 成功
   - 持久化：MUST commit before physical invocation
   
2. `INTENT_RECORDED → CONFIRMED_NOT_EXECUTED`
   - 触发器：Operator/reconciliation API 调用
   - 语义：外部权威证明物理操作未生效
   - 持久化：MUST commit before subsequent execution
   
3. `INTENT_RECORDED → CONFIRMED_EXECUTED`
   - 触发器：Operator/reconciliation API 调用 + recovered result
   - 语义：外部和解提供权威成功结果
   - 持久化：MUST commit result before skipping delegate
   
4. `INTENT_RECORDED → UNRESOLVED`
   - 触发器：显式标记或超时策略（T5 不实现超时）
   - 语义：不确定性持久化，等待解决方案
   - 行为：Fail-closed，不执行物理调用

**不可逆转换：**
- `CONFIRMED_NOT_EXECUTED` 和 `CONFIRMED_EXECUTED` 是终态
- 冲突解决方案（两个不同解决方案）必须被拒绝

**存储失败语义：**
- 状态转换持久化失败 → 不执行依赖该状态的操作
- 状态读取失败 → fail-closed，抛出异常

### 2.3 恢复解决方案结果模型

```java
// M6-T5: Recovery resolution outcomes
enum RecoveryResolution {
  CONFIRMED_NOT_EXECUTED,  // 外部确认未执行
  CONFIRMED_EXECUTED       // 外部确认已执行（需要结果）
}

// Recovered result 包装
class RecoveredToolResult {
  String operationId;      // 操作标识
  String toolCallId;       // 协议标识（Spring AI）
  String toolName;         // 工具名称
  String result;           // 恢复的结果（JSON/text）
  String resolutionSource; // 解决方案来源（审计）
}
```

**Result Correlation:**
- `operationId`: 框架权威标识
- `toolCallId`: 协议标识，用于重建 `ToolResponseMessage`
- Checkpoint 中的 `PendingToolCall` 已经包含 `toolCallId`

### 2.4 批量恢复语义

**M6-T5 Phase 1: Whole-Batch Preflight**

```
pending batch = [op-A, op-B, op-C]
  ↓
classify entire batch
  ↓
op-A → DEFINITELY_NOT_DISPATCHED
op-B → CONFIRMED_EXECUTED (recovered result)
op-C → CONFIRMED_NOT_EXECUTED
  ↓
ALL resolved → continue execution
  ↓
op-A → execute normally (record intent first)
op-B → skip delegate, use recovered result
op-C → execute normally (record intent first)
```

**Fail-Closed Rule:**
```
ANY operation = INTENT_RECORDED (unresolved)
  ↓
ENTIRE batch fails
  ↓
RecoveryUncertaintyException
  ↓
no physical invocation
```

**Independent Operation Semantics:**
- Duplicate same-name operations remain independent via `operationId`
- 恢复解决方案基于 `(processId, operationId)` 而非 `toolName` 或 arguments

### 2.5 Operator API 设计

**设计原则：**
1. 不暴露 `DurableResumeCoordinator`
2. 使用持久化框架标识：`processId + operationId`
3. 窄语义 API，不是通用配置
4. 属于 `AgentRuntime` 层（面向应用的边界）

**API Location: `AgentRuntime` 或新的 `RecoveryResolutionApi`**

```java
// Option 1: Add to AgentRuntime interface
public interface AgentRuntime {
  // ... existing methods
  
  // M6-T5: Recovery resolution API
  void resolveOperationNotExecuted(String processId, String operationId);
  
  void resolveOperationExecuted(
      String processId,
      String operationId,
      String recoveredResult);
}

// Option 2: New dedicated API (preferred for separation of concerns)
public interface RecoveryResolutionApi {
  void confirmNotExecuted(String processId, String operationId);
  
  void confirmExecuted(
      String processId,
      String operationId, 
      String recoveredResult);
      
  RecoveryStatus queryRecoveryStatus(String processId);
}
```

**选择：Option 2 - 新的 `RecoveryResolutionApi`**

理由：
1. 恢复解决方案是独立的能力域
2. `AgentRuntime` 已经有很多职责
3. 未来可能需要更多恢复管理 API（查询、批量操作）
4. 清晰的 API 边界有助于权限控制

### 2.6 持久化 Schema 设计

**扩展 `InvocationStateStore` 存储：**

```sql
-- M6-T4A: Existing invocation intent table
CREATE TABLE invocation_intent (
  process_id VARCHAR(255) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (process_id, operation_id)
);

-- M6-T5: Add recovery resolution table
CREATE TABLE recovery_resolution (
  process_id VARCHAR(255) NOT NULL,
  operation_id VARCHAR(255) NOT NULL,
  resolution_type VARCHAR(50) NOT NULL, -- 'NOT_EXECUTED' or 'EXECUTED'
  recovered_result TEXT,                -- Non-null if resolution_type = 'EXECUTED'
  resolution_source VARCHAR(255),       -- 审计：谁/什么解决了这个不确定性
  resolved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (process_id, operation_id),
  FOREIGN KEY (process_id, operation_id) 
    REFERENCES invocation_intent(process_id, operation_id)
);

-- Index for process-level recovery status queries
CREATE INDEX idx_recovery_resolution_process 
  ON recovery_resolution(process_id, resolved_at);
```

**设计决策：**
1. 两表设计而非单表：分离 intent 记录（高频写入）和 resolution（低频、冲突敏感）
2. Foreign key 确保只能为已记录 intent 的操作添加 resolution
3. Primary key 确保每个 operation 只有一个 resolution（冲突检测）
4. `resolution_source` 用于审计追踪

**冲突解决方案检测：**
```sql
-- Insert-once semantics via primary key
INSERT INTO recovery_resolution 
  (process_id, operation_id, resolution_type, recovered_result, resolution_source)
VALUES (?, ?, ?, ?, ?)
-- 如果已存在 → UNIQUE constraint violation → 拒绝冲突 resolution
```

### 2.7 Recovered Result Continuation 路径

**Protocol Reconstruction Path:**

```
Recovered Result
  ↓
ProtocolReconstructor (new method)
  ↓
constructRecoveredResponse(
  PendingToolCall operation,
  String recoveredResult)
  ↓
ToolResponseMessage.ToolResponse(
  toolCallId,  // from PendingToolCall
  toolName,    // from PendingToolCall  
  recoveredResult)
  ↓
Continue Model Execution
  ↓
CHECK B
```

**Evidence Semantics:**
- 不生成 `TOOL_EXECUTED` 事件（未观察到 delegate 返回）
- 新事件：`OPERATION_RECOVERED` (optional)
- Evidence 可以包含恢复来源元数据

**Key Implementation Points:**
1. `ProtocolReconstructor` 已有 `constructDenialResponses()` - 模式类似
2. `SpringAiResumedExecutionHandler` 需要新的执行路径处理 recovered results
3. Recovered operations 不调用 `EvidenceCapturingToolCallback`
4. 不触发 `recordInvocationIntent()` (已经存在)

### 2.8 Recovery Visibility API

**Minimum Query Surface:**

```java
public interface RecoveryStatusQuery {
  // Process-level recovery status
  ProcessRecoveryStatus getProcessRecoveryStatus(String processId);
  
  // Operation-level detail (for debugging/operator)
  OperationRecoveryDetail getOperationDetail(
      String processId, String operationId);
}

public class ProcessRecoveryStatus {
  String processId;
  long checkpointVersion;
  List<UncertainOperation> uncertainOperations;
  boolean isBlocked;  // true if any unresolved
}

public class UncertainOperation {
  String operationId;
  String toolName;
  String classification;  // "MAY_HAVE_INVOKED", "UNRESOLVED", etc.
  Instant detectedAt;
  // NOT exposing arguments/results by default (security)
}

public class OperationRecoveryDetail {
  String operationId;
  String toolName;
  String recoveryState;
  String resolution;  // null if unresolved
  String resolutionSource;  // who resolved it
  Instant resolvedAt;
}
```

**Design Principles:**
1. 不暴露敏感的 arguments/results（除非操作员显式查询特定 operation）
2. Process-level view 用于判断是否blocked
3. Operation-level detail 用于操作员干预
4. 不使用 `ExecutionLedger` 作为查询权威（使用 `CheckpointStore` + `InvocationStateStore`）

---

## 3. 崩溃窗口分析

### 3.1 Resolution Commit Windows

**Window 1: Resolution Persistence**
```
BEGIN recordResolution()
  ↓
INSERT INTO recovery_resolution  ← CRASH HERE
  ↓
COMMIT
  ↓
return success
```

**Consequence:** Resolution lost, operation remains INTENT_RECORDED
**Safety:** Fail-closed (still uncertain)

**Window 2: Resolution-Dependent Execution**
```
recordResolution(CONFIRMED_NOT_EXECUTED)
  ↓
COMMIT  ← MUST完成
  ↓
delegate.call()  ← CRASH HERE
```

**Consequence:** Resolution persisted, execution not started
**Safety:** Safe - next recovery sees CONFIRMED_NOT_EXECUTED, will retry execution

**Window 3: Recovered Result Continuation**
```
recordResolution(CONFIRMED_EXECUTED, result)
  ↓
COMMIT  ← MUST完成
  ↓
skip delegate
  ↓
continue model  ← CRASH HERE
```

**Consequence:** Resolution persisted, continuation not completed
**Safety:** Safe - next recovery sees CONFIRMED_EXECUTED, will skip delegate and continue

### 3.2 Concurrent Resolution Conflicts

**Scenario:**
```
Worker A: confirmNotExecuted(proc-1, op-1)
Worker B: confirmExecuted(proc-1, op-1, result)
```

**Protection:** Primary key constraint
```sql
PRIMARY KEY (process_id, operation_id)
```

**Behavior:**
- First resolution commits successfully
- Second resolution fails with UNIQUE constraint violation
- Application receives clear error: "Conflicting resolution rejected"

**Idempotent Resolutions:**
- 相同的 resolution（相同类型和结果）可以被视为幂等
- 实现选项：检查现有 resolution 是否匹配

---

## 4. 并发分析

### 4.1 Cross-Instance Resolution Visibility

**Scenario:**
```
Instance A: Records resolution at T1
Instance B: Queries recovery state at T2 (T2 > T1)
```

**Requirement:** Instance B MUST see Instance A's resolution

**Implementation:**
- JDBC `READ COMMITTED` isolation level (minimum)
- No local caching of recovery resolutions
- Fresh database read on每次 recovery classification

### 4.2 Resume Concurrency (Existing T4 Semantics)

**M6-T4 CHECK B CAS semantics remain unchanged:**
```
Multiple workers attempt resume on same checkpoint
  ↓
CHECK A: All load same checkpoint
  ↓
CHECK B: CAS(expectedVersion)
  ↓
Only ONE worker succeeds, others get CheckpointTransitionConflictException
```

**M6-T5 Resolution + Resume Race:**
```
Worker A: Recording resolution
Worker B: Attempting resume
```

**Safety:**
- Worker A's resolution commit completes first → Worker B sees resolved state
- Worker B's resume attempt starts first → may throw RecoveryUncertaintyException → operator retries after resolution
- No data corruption due to PRIMARY KEY constraints

---

## 5. 事件语义

### 5.1 New Events (Optional)

```java
EventType.RECOVERY_UNCERTAIN  // MAY_HAVE_INVOKED detected
EventType.RECOVERY_RESOLVED   // Operator resolution recorded
EventType.OPERATION_RECOVERED // Execution continued with recovered result
```

**Design Decision: 添加这些事件**

理由：
1. Observability: 操作员需要知道何时检测到不确定性
2. Audit Trail: 解决方案是关键业务决策
3. Debugging: Recovered operation 路径需要可观察

**Event Semantics:**
- Events 是已经为真的领域事实的投影
- Event projection 失败不能撤销已提交的 resolution
- Events 不是恢复权威

### 5.2 TOOL_EXECUTED Semantics (Unchanged)

**M6-T5 保证：**
```java
TOOL_EXECUTED → framework observed delegate.call() return normally
```

**NOT emitted for:**
- Recovered operations (no delegate call occurred in this execution)
- Denial responses (M6-T2 existing semantic)

---

## 6. 实现阶段计划

### Phase 1: Recovery State Storage
1. 扩展 `InvocationStateStore` 接口添加 resolution 方法
2. 实现 `JdbcInvocationStateStore` resolution 持久化
3. 添加 `InMemoryInvocationStateStore` resolution 支持（测试用）
4. Schema migration

### Phase 2: Recovery Resolution API
1. 创建 `RecoveryResolutionApi` 接口
2. 实现 `DefaultRecoveryResolutionApi`
3. 集成到 `DefaultAgentRuntime`
4. 添加冲突检测和验证

### Phase 3: Recovery Classification Enhancement
1. 扩展 `InvocationRecoveryClassifier` 读取 resolutions
2. 更新分类逻辑（NOT_DISPATCHED, CONFIRMED_NOT_EXECUTED, CONFIRMED_EXECUTED, MAY_HAVE_INVOKED）
3. 更新 `DurableResumeCoordinator.classifyApprovedBatchOrFailClosed()`

### Phase 4: Recovered Result Continuation
1. 在 `ProtocolReconstructor` 添加 `constructRecoveredResponse()`
2. 扩展 `SpringAiResumedExecutionHandler` 处理 mixed batch (normal + recovered)
3. 实现 recovered operation 跳过 delegate 路径
4. 保持 Evidence 语义

### Phase 5: Recovery Visibility
1. 实现 `RecoveryStatusQuery` API
2. Process-level 和 operation-level 查询
3. 集成到 `DefaultAgentRuntime`

### Phase 6: Events
1. 添加新的 `EventType` 枚举值
2. 在关键点发射事件
3. 保持事件投影独立于恢复权威

### Phase 7: Comprehensive Testing
1. 基本恢复解决方案测试（NOT_EXECUTED, EXECUTED）
2. 批量恢复测试（mixed resolutions）
3. 崩溃窗口测试（resolution commit, continuation）
4. 并发测试（冲突 resolutions, cross-instance visibility）
5. 重启持久性测试
6. 协议继续测试（recovered result → model）
7. CHECK B 语义测试
8. 完整回归测试

---

## 7. HARD STOP 检查

**检查所有 HARD STOP 条件：**

1. ❌ Checkpoint 不再是当前 process 恢复状态权威
   - **未违反**: Checkpoint 仍然是恢复状态权威，`InvocationStateStore` 存储操作级别的 resolution

2. ❌ Invocation intent 语义必须改变
   - **未违反**: `recordInvocationIntent()` 语义不变，仍然是"允许物理调用"的门控

3. ❌ Recovery 需要 exactly-once 保证
   - **未违反**: 仍然是 at-least-once，resolution 不改变这一点

4. ❌ 推断通用外部提交真相
   - **未违反**: Resolution 是显式的操作员/和解 API 调用，不是推断

5. ❌ 多节点 ownership/claim/lease/fencing
   - **未违反**: 使用现有 CHECK B CAS 语义，无新的分布式协调

6. ❌ 协议无法从 recovered result 继续
   - **未违反**: `ProtocolReconstructor` 已有类似模式（denial responses），recovered result 使用相同机制

7. ❌ Public API 复制现有权威
   - **未违反**: 新的 `RecoveryResolutionApi` 拥有新能力（recovery resolution），不复制 `CheckpointStore` 或 `AgentRuntime`

8. ❌ 需要跨独立权威的分布式事务
   - **未违反**: `CheckpointStore` 和 `InvocationStateStore` 可以使用相同 DataSource，但通过应用逻辑协调，不需要 2PC

**结论: 无 HARD STOP 条件被违反，继续实现。**

---

## 8. 明确延迟的功能

M6-T5 **不**实现：

- ❌ Startup checkpoint scanning（自动发现未解决的 checkpoint）
- ❌ Automatic recovery trigger（自动触发恢复流程）
- ❌ Background recovery worker（后台恢复协调器）
- ❌ Automatic idempotency inference（自动幂等性检测）
- ❌ Generic retry engine（通用重试引擎）
- ❌ Full RecoveryPolicy framework（可配置恢复策略）
- ❌ Multi-node recovery ownership/claim/lease
- ❌ Cluster topology detection
- ❌ Recovery timeout policies（自动超时处理）
- ❌ MCP-specific recovery（工具特定的恢复逻辑）

这些属于后续的 **Recovery Control Plane** 和分布式执行 track。

---

## 9. 架构决策记录

### ADR-1: 扩展 InvocationStateStore 而非创建新权威
**Status:** Accepted  
**Rationale:** 避免"同一事实的两个权威"，recovery resolution 是 invocation state 的自然演进

### ADR-2: 新的 RecoveryResolutionApi 而非扩展 AgentRuntime
**Status:** Accepted  
**Rationale:** 清晰的职责分离，未来扩展空间，更好的 API 边界

### ADR-3: 两表设计 (invocation_intent + recovery_resolution)
**Status:** Accepted  
**Rationale:** 分离高频写入（intent）和冲突敏感写入（resolution），清晰的 foreign key 语义

### ADR-4: Whole-Batch Preflight for Recovery
**Status:** Accepted  
**Rationale:** Fail-closed safety，避免部分执行的复杂性，Phase 1 simple semantics

### ADR-5: 添加 Recovery Events (RECOVERY_UNCERTAIN, RECOVERY_RESOLVED, OPERATION_RECOVERED)
**Status:** Accepted  
**Rationale:** Observability 和 audit trail，不作为权威，保持事件投影独立性

### ADR-6: 不自动推断幂等性
**Status:** Accepted  
**Rationale:** 安全至上，显式 operator resolution 而非框架猜测

---

## 10. 下一步

**架构审查通过，继续实现。**

实现顺序：
1. Recovery State Storage (Schema + JdbcInvocationStateStore)
2. Recovery Resolution API
3. Enhanced Classification
4. Recovered Result Continuation
5. Recovery Visibility
6. Events
7. Comprehensive Testing

**预期完成标准:**
- 所有 25+ adversarial tests 通过
- Full reactor regression green
- Documentation complete
- FULL GO closure document

---

**架构文档版本:** 1.0  
**作者:** Kiro  
**日期:** 2026-09-15  
**状态:** APPROVED FOR IMPLEMENTATION
