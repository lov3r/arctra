# M6 持久化执行与状态所有权架构指南

**版本:** 1.0  
**日期:** 2026-09-09  
**状态:** Architecture Gate — GO ✅

---

## 执行摘要

本文档定义 Arctra M6 双轨持久化战略的核心架构原则：

1. **Track A** — M5 持久化能力加固（生产就绪）
2. **Track B** — 通用持久化执行扩展（普通对话/工具恢复）

核心架构决策：

- **Event / Record / Evidence / Checkpoint 四元模型** — 清晰的状态所有权分离
- **Hybrid Model** — Checkpoint (快照) + ExecutionRecord (账本) + Evidence (证据)
- **Evidence 保持狭义** — 仅表示工具执行证据，不扩展为通用事件历史
- **ExecutionRecord 作为生命周期账本** — 持久化审批、暂停、恢复、冲突等关键事件
- **ChatMemory = 对话权威** — 不重复存储执行历史

---

## 1. 核心概念模型

### 1.1 四元分离

```text
Event (事件)
  ↓ durably represented by
Record (记录)
  ↓ may reference
Evidence (证据)

Checkpoint (检查点) — 独立维度，表示当前恢复状态
```

#### Event — 语义事实

```text
Event = Something happened

例子:
  PROCESS_STARTED
  TOOL_EXECUTED
  APPROVAL_GRANTED
  SUSPENDED
  RESUMED
  CHECKPOINT_CONFLICT
  COMPLETED
```

Event 是语义层面的事实，不一定是独立的 Java 类型。

---

#### Record — 持久账本条目

```text
Record = Durable structured representation of an Event
```

**推荐实现:**

```java
public record ExecutionRecord(
    String recordId,           // R-{processId}-{sequence}
    String processId,
    long sequence,             // 进程内单调递增序列号
    EventType eventType,       // 事件类型枚举
    @Nullable Long checkpointVersion,
    Instant timestamp,
    @Nullable String runtimeId,
    @Nullable String correlationId,
    Map<String, Object> payload  // 动态负载
)
```

**选择理由:**

- ✅ 单一持久化模型（最小 Public API）
- ✅ Schema 演化友好（新 EventType 不破坏旧记录）
- ✅ 序列化简单（扁平 JSON）
- ✅ 可查询性强（payload 字段可索引）

---

#### Evidence — 执行证明

```text
Evidence = Supporting proof/data that substantiates an execution fact
```

**M5/M6 定义 (保持不变):**

```java
public record Evidence(
    String toolName,
    String toolArguments,   // JSON
    String toolResult,      // JSON (可能很大)
    Instant executedAt,
    String executionStatus  // SUCCESS | FAILURE
)
```

**重要决策: Evidence 保持狭义**

```text
Evidence 仅表示工具执行证据

NOT:
  ❌ 审批决策 (属于 ExecutionRecord.payload)
  ❌ 暂停事件 (属于 ExecutionRecord)
  ❌ 恢复事件 (属于 ExecutionRecord)
```

---

#### Checkpoint — 当前恢复快照

```text
Checkpoint = Current authoritative recovery snapshot
```

**M6 建议 Schema:**

```java
public record SuspensionCheckpoint(
    String processId,
    long checkpointVersion,           // CAS 版本控制
    String runtimeBindingKey,         // 逻辑绑定标识
    String sessionId,                 // ChatMemory 标识
    ToolCallBatch pendingBatch,       // 待执行工具
    
    // 🆕 M6 新增
    long lastRecordSequence,          // 最后的 ExecutionRecord 序列号
    List<String> evidenceIds,         // Evidence 引用（不再嵌入）
    @Nullable String lastEvidenceId   // 一致性验证
)
```

**关键变化:**

- Evidence 不再嵌入 → 引用 evidenceIds
- 引用最后的 ExecutionRecord.sequence
- 消除写放大

---

### 1.2 四元关系

```text
┌──────────────┐
│    Event     │ ← 语义事实（概念层）
└──────┬───────┘
       │
       │ durably represented by
       ▼
┌──────────────┐
│    Record    │ ← 账本条目（持久化层）
└──────┬───────┘
       │
       │ may reference
       ▼
┌──────────────┐
│   Evidence   │ ← 执行证据（详细数据）
└──────────────┘

┌──────────────┐
│  Checkpoint  │ ← 当前状态快照（独立维度）
└──────┬───────┘
       │
       │ references lastRecordSequence
       └────────────────────────┐
                                 ▼
                          ExecutionRecord[]
```

**回答的问题:**

- **Checkpoint:** "我们现在在哪里？"
- **ExecutionRecord:** "我们怎么到这里的？"
- **Evidence:** "什么证明了这个执行事实？"

---

## 2. 状态所有权矩阵

| 事实/数据                     | ChatMemory | ExecutionRecord | Evidence | Checkpoint | 外部系统 |
|-------------------------------|------------|-----------------|----------|------------|----------|
| **对话维度**                  |            |                 |          |            |          |
| User message                  | ✅ 权威    | ❌              | ❌       | ❌         | ❌       |
| Final Assistant message       | ✅ 权威    | ❌              | ❌       | ❌         | ❌       |
| ToolResponseMessage           | ✅ 权威    | reference       | ❌       | ❌         | ❌       |
| **执行维度**                  |            |                 |          |            |          |
| Tool arguments                | 副本       | reference       | ✅ 权威  | reference  | ❌       |
| Tool raw result               | 摘要       | reference       | ✅ 权威  | ❌         | ✅ 权威  |
| Tool execution status         | ❌         | ✅ 权威         | ✅ 权威  | ❌         | ❌       |
| operationId                   | ❌         | ✅ 权威         | ❌       | ❌         | ✅ 权威  |
| **审批维度**                  |            |                 |          |            |          |
| Approval request              | ❌         | ✅ 权威         | ❌       | ❌         | ❌       |
| Approval decision             | ❌         | ✅ 权威         | payload  | ❌         | ❌       |
| Approver identity             | ❌         | ✅ 权威         | ❌       | ❌         | ❌       |
| **生命周期维度**              |            |                 |          |            |          |
| Suspension reason             | ❌         | ✅ 权威         | payload  | reference  | ❌       |
| Pending ToolCall              | reference  | reference       | ❌       | ✅ 权威    | ❌       |
| Resume signal                 | ❌         | ✅ 权威         | ❌       | ❌         | ❌       |
| Checkpoint conflict           | ❌         | ✅ 权威         | ❌       | ❌         | ❌       |
| Completion fact               | ❌         | ✅ 权威         | ❌       | ❌         | ❌       |
| **恢复维度**                  |            |                 |          |            |          |
| Process status                | ❌         | derived         | ❌       | ✅ 权威    | ❌       |
| Checkpoint version            | ❌         | reference       | ❌       | ✅ 权威    | ❌       |
| runtimeBindingKey             | ❌         | reference       | ❌       | ✅ 权威    | ❌       |

**权威分类:**

- **✅ 权威** — 唯一真实来源
- **reference** — 引用另一个权威存储
- **副本** — 非权威副本
- **摘要** — 权威数据的投影
- **derived** — 可从其他权威数据推导

---

## 3. 持久化架构: Hybrid Model

### 3.1 架构选择

**Snapshot vs Journal vs Hybrid:**

| Model            | 恢复速度 | 历史可审计性 | 存储大小 | 调试能力 |
|------------------|----------|--------------|----------|----------|
| Snapshot Only    | ✅ 快    | ❌ 无        | ✅ 小    | ❌ 无    |
| Journal Only     | ❌ 慢    | ✅ 完整      | ⚠️ 增长  | ✅ 完整  |
| **Hybrid (推荐)**| ✅ 快    | ✅ 完整      | ⚠️ 中等  | ✅ 完整  |

**推荐: Hybrid Model**

```text
Checkpoint (快照) + ExecutionRecord (账本) + Evidence (证据)
```

---

### 3.2 Hybrid 架构图

```text
┌─────────────────────────────────────────┐
│         Application Layer               │
└───────────┬─────────────────────────────┘
            │
            ▼
┌───────────────────────────────────────┐
│       AgentRuntime / Engine           │
└───┬─────────────┬─────────────┬───────┘
    │             │             │
    ▼             ▼             ▼
┌─────────┐  ┌──────────┐  ┌──────────┐
│Checkpoint│  │Execution │  │Evidence  │
│  Store   │  │  Ledger  │  │  Store   │
└─────────┘  └──────────┘  └──────────┘
     │             │             │
     └─────────────┴─────────────┘
            │
            ▼
    ┌───────────────┐
    │   ChatMemory  │ (对话权威)
    └───────────────┘
```

---

### 3.3 存储接口

#### CheckpointStore (M5 已有)

```java
public interface CheckpointStore {
    void create(SuspensionCheckpoint checkpoint);
    SuspensionCheckpoint load(String processId, long expectedVersion);
    boolean replaceIfVersion(String processId, long expectedVersion, SuspensionCheckpoint newCheckpoint);
    boolean deleteIfVersion(String processId, long expectedVersion);
}
```

---

#### ExecutionLedger (🆕 M6 新增)

```java
public interface ExecutionLedger {
    /**
     * 追加记录到账本
     * @return 分配的 recordId
     */
    String append(ExecutionRecord record);
    
    /**
     * 查询进程的所有记录（按 sequence 排序）
     */
    List<ExecutionRecord> queryByProcess(String processId);
    
    /**
     * 查询进程的最后 N 条记录
     */
    List<ExecutionRecord> queryRecentByProcess(String processId, int limit);
    
    /**
     * 查询特定事件类型
     */
    List<ExecutionRecord> queryByEventType(String processId, EventType eventType);
}
```

---

#### EvidenceStore (🆕 M6 新增)

```java
public interface EvidenceStore {
    /**
     * 存储 Evidence
     * @return evidenceId
     */
    String store(String processId, Evidence evidence);
    
    /**
     * 加载单个 Evidence
     */
    Evidence load(String evidenceId);
    
    /**
     * 加载进程的所有 Evidence
     */
    List<Evidence> loadForProcess(String processId);
}
```

---

## 4. 强制持久化事件分类

| 事件                          | 分类                  | 理由                                                                 |
|-------------------------------|-----------------------|----------------------------------------------------------------------|
| **PROCESS_STARTED**           | ✅ MUST DURABLY RECORD | 审计起点，恢复推理基础                                               |
| **USER_TURN_ACCEPTED**        | ⚠️ SHOULD RECORD      | Turn-level 恢复需要，M5 不需要                                       |
| **MODEL_STARTED**             | 📝 TRACE ONLY         | 操作诊断，不是业务事实                                               |
| **MODEL_COMPLETED**           | ⚠️ SHOULD RECORD      | Turn-level 恢复需要，M5 不需要                                       |
| **MODEL_FAILED**              | ✅ MUST DURABLY RECORD | 失败诊断，审计需要                                                   |
| **TOOL_PLANNED**              | ⚠️ SHOULD RECORD      | Tool-level 恢复需要，M5 不强制                                       |
| **TOOL_EXECUTION_STARTED**    | 📝 TRACE ONLY         | 不可靠（崩溃后不知道是否真的开始）                                   |
| **TOOL_EXECUTED**             | ✅ MUST DURABLY RECORD | 副作用证明，幂等性去重基础                                           |
| **TOOL_FAILED**               | ✅ MUST DURABLY RECORD | 失败诊断，审计需要                                                   |
| **APPROVAL_REQUIRED**         | ✅ MUST DURABLY RECORD | 审计需要（谁请求了什么审批）                                         |
| **SUSPENDED**                 | ✅ MUST DURABLY RECORD | 生命周期事实，审计需要                                               |
| **APPROVAL_GRANTED**          | ✅ MUST DURABLY RECORD | 审计需要（谁批准了，何时批准）                                       |
| **APPROVAL_REJECTED**         | ✅ MUST DURABLY RECORD | 审计需要（谁拒绝了，为什么）                                         |
| **RESUME_ATTEMPTED**          | ⚠️ SHOULD RECORD      | 失败诊断有用，但高频失败会污染历史                                   |
| **RESUME_PREPARATION_FAILED** | ⚠️ SHOULD RECORD      | 可重试失败，记录有助于诊断，但需要去重策略                           |
| **RESUMED**                   | ✅ MUST DURABLY RECORD | 生命周期事实，审计需要                                               |
| **CHECKPOINT_CREATED**        | 📝 TRACE ONLY         | Checkpoint 本身已持久化，不需要单独记录                              |
| **CHECKPOINT_ADVANCED**       | ⚠️ SHOULD RECORD      | 有助于理解 checkpoint 演化                                           |
| **CHECKPOINT_DELETED**        | 📝 TRACE ONLY         | 完成/失败事件已表达此信息                                            |
| **CHECKPOINT_CONFLICT**       | ✅ MUST DURABLY RECORD | 并发冲突证明，审计需要                                               |
| **COMPLETED**                 | ✅ MUST DURABLY RECORD | 生命周期终点，审计需要                                               |
| **FAILED**                    | ✅ MUST DURABLY RECORD | 生命周期终点，审计需要                                               |

---

## 5. Evidence 可扩展性优化

### 5.1 M5 写放大问题

```text
v1: accumulatedEvidences = []
v2: accumulatedEvidences = [A]          (1 Evidence, ~1 KB)
v3: accumulatedEvidences = [A, B]       (复制 A)
v4: accumulatedEvidences = [A, B, C]    (复制 A+B)
...
v100: accumulatedEvidences = [A...Z...] (复制 99 次)

写放大因子: O(n²)
```

---

### 5.2 M6 解决方案: 独立 EvidenceStore

```java
// Checkpoint 仅存储 IDs
SuspensionCheckpoint {
    evidenceIds = ["E-P100-1", "E-P100-2", "E-P100-3"]
}

// ExecutionRecord 引用 evidenceId
ExecutionRecord {
    eventType = TOOL_EXECUTED,
    payload = {
        "toolCallId": "TC88",
        "evidenceId": "E-P100-3"
    }
}
```

**优势:**

- ✅ 写放大从 O(n²) 降为 O(n)
- ✅ Checkpoint 大小固定
- ✅ Checkpoint 删除后 Evidence 保留
- ✅ 独立查询工具执行历史
- ✅ Evidence Schema 独立演化

---

## 6. RuntimeBindingResolver 生产模型

### 6.1 核心原则

```text
RuntimeBindingResolver 重建逻辑等价的运行时状态
NOT 反序列化 Java 对象图
```

---

### 6.2 跨机器重建

**Checkpoint 包含:**

```java
SuspensionCheckpoint {
    runtimeBindingKey = "tenant-42/deploy-agent/v3",  // 逻辑标识
    sessionId = "S100"                                 // ChatMemory 标识
}
```

**不包含:**

```text
❌ AgentDefinition 对象
❌ ChatMemory 对象
❌ Tool 对象
❌ Model 对象
```

---

**Runtime B 重建:**

```java
RuntimeBinding binding = resolver.resolve(
    processId,
    "tenant-42/deploy-agent/v3",
    "S100"
);

// 重建内容:
binding.definition()  // 从 DefinitionRegistry 加载
binding.context()     // 重建运行时上下文
  ├─ chatMemory       // 共享持久 ChatMemory (sessionId)
  ├─ tools            // Runtime B 的 ToolRegistry
  ├─ model            // Runtime B 的 Model 配置
  └─ governance       // Runtime B 的 Policy Registry
```

---

### 6.3 必须外部持久化的内容

| 内容            | 持久化方式                                  | 理由                          |
|-----------------|---------------------------------------------|-------------------------------|
| AgentDefinition | 配置文件 / 数据库                           | 定义必须可恢复                |
| ChatMemory      | 共享存储 (JDBC / Redis)                     | 对话连续性                    |
| Tools           | ToolRegistry (NOT 序列化 Tool 对象)         | 语义等价的工具实现            |
| Model           | ModelProvider (NOT 序列化 ChatModel 对象)   | 语义等价的模型配置            |
| Governance      | PolicyRegistry (NOT 序列化 Policy 对象)     | 语义等价的策略逻辑            |

---

### 6.4 runtimeBindingKey 契约

```text
runtimeBindingKey = 稳定的逻辑绑定标识

NOT:
  ❌ machine ID
  ❌ JVM ID
  ❌ Spring bean name
  ❌ 物理 Agent instance ID
```

**例子:**

```text
runtimeBindingKey = "tenant-42/deploy-agent/v3"

Runtime A (北京): resolve("tenant-42/deploy-agent/v3") → 逻辑等价绑定
Runtime B (上海): resolve("tenant-42/deploy-agent/v3") → 逻辑等价绑定
Runtime C (深圳): resolve("tenant-42/deploy-agent/v3") → 逻辑等价绑定
```

---

## 7. CHECK A / CHECK B 通用化

### 7.1 语义定义

**CHECK A = 执行前验证**

```text
问题: 这个执行尝试是否仍然被授权/当前？
目的: 在副作用前拒绝过期的执行
```

**CHECK B = 提交前验证**

```text
问题: 我仍然被允许提交我刚执行的工作结果吗？
目的: 在提交时检测并发冲突
```

---

### 7.2 M5 审批恢复 (已实现)

```text
CHECK A:
  load checkpoint
  if checkpoint.version != expectedVersion:
    throw StaleCheckpointException

execute:
  executeStoredBatch

CHECK B:
  checkpointStore.replaceIfVersion(v, v+1)
  OR checkpointStore.deleteIfVersion(v)
```

---

### 7.3 通用化到其他步骤

#### Turn-Level (M6 候选)

```text
CHECK A:
  load TurnCheckpoint
  verify version

execute:
  call model

CHECK B:
  TurnCheckpoint.replaceIfVersion(v, v+1)
```

#### Tool-Step (M6/M7 候选)

```text
CHECK A:
  load ToolStepCheckpoint
  verify version

execute:
  call tool

CHECK B:
  ToolStepCheckpoint.replaceIfVersion(v, v+1)
```

---

### 7.4 EXCLUSIVE Mode 仍需要 CHECK A/B

**分布式锁 ≠ 恰好一次保证**

```text
Lease: 防止并发重复 (并发窗口)
CHECK A/B: 防止过期重复 (时间窗口)

EXCLUSIVE mode = Lease + CHECK A/B
```

---

## 8. 执行保证模式

| Mode                  | 并发重复预防 | 崩溃窗口重复 | 恰好一次保证 | 延迟 | 适用性                   |
|-----------------------|--------------|--------------|--------------|------|--------------------------|
| **OPTIMISTIC (M5)**   | ❌           | ❌           | ❌           | 低   | 幂等工具，只读查询       |
| **EXCLUSIVE**         | ✅           | ⚠️           | ❌           | 中等 | 非幂等副作用，需排他     |
| **IDEMPOTENT_ASSISTED**| ✅           | ✅           | ⚠️           | 低   | 外部系统支持幂等 key     |
| **TRANSACTIONAL**     | ✅           | ✅           | ✅           | 高   | 极少（共享事务边界）     |

**重要澄清:**

```text
分布式锁不保证恰好一次执行

租约超时后的重试仍可能导致重复副作用
工具成功但 Evidence 未提交 → UNCERTAIN_OUTCOME
```

---

## 9. 双轨战略路线图

### 9.1 Track A — M5 持久化能力加固

**目标:** 生产就绪

| ID | 任务                              | 优先级 | M5 影响 | 依赖   |
|----|-----------------------------------|--------|---------|--------|
| A1 | 生产 CheckpointStore 实现 (JDBC/Redis) | 高     | 无      | 无     |
| A2 | 生产 RuntimeBindingResolver       | 高     | 无      | 无     |
| A3 | ExecutionRecord + Evidence 分离   | 高     | 中等    | 无     |
| A4 | ResumePreparationException 重试策略 | 中     | 低      | 无     |
| A5 | 可观察性增强                      | 中     | 低      | A3     |
| A6 | 独占执行/租约模式                 | 低     | 高      | A3     |
| A7 | Checkpoint/Memory 一致性缓解      | 中     | 中等    | A1, A3 |
| A8 | Evidence 外部化存储               | 中     | 高      | A3     |

---

### 9.2 Track B — 通用持久化执行

**目标:** 扩展持久化边界

| ID  | 任务                          | 实现风险 | M5 兼容性 | 依赖     |
|-----|-------------------------------|----------|-----------|----------|
| B1  | Process/Turn/Step 身份模型    | 低       | 高        | 无       |
| B2  | 通用执行状态抽象              | 中等     | 中等      | B1       |
| B3  | ExecutionRecord 账本          | 中等     | 高        | A3       |
| B4  | 模型步骤重放                  | 高       | 中等      | B2, B3   |
| B5  | 持久工具规划                  | 中等     | 高        | B3       |
| B6  | 工具执行生命周期              | 高       | 中等      | B3, B5   |
| B7  | Uncertain Outcome 处理        | 高       | 高        | B6       |
| B8  | operationId/幂等性            | 中等     | 高        | B6       |

---

### 9.3 推荐路线图: Strategy 3 — Interleaved

```text
M6-T1: ExecutionRecord + Evidence Separation (A3 + B3)
  ├─ 引入 ExecutionRecord + ExecutionLedger
  ├─ 引入 EvidenceStore
  └─ Checkpoint 引用而不是嵌入

M6-T2: Production CheckpointStore (A1)
  ├─ JDBC CheckpointStore
  └─ Redis CheckpointStore

M6-T3: Production RuntimeBindingResolver (A2)
  ├─ 参考实现
  └─ 生产部署指南

M6-T4: Retry Policy & Observability (A4 + A5)
  ├─ RetryPolicy 框架
  └─ ExecutionRecord 可观察性

M7+: Tool-Level Durability, Uncertain Outcome, EXCLUSIVE mode
```

---

## 10. M5 兼容性保证

### 10.1 不变的 M5 语义

```text
✅ AgentRuntime.resumeProcess(processId, checkpointVersion, signal)
✅ DurableExecutionEngine.resumeFromCheckpoint(checkpoint, signal, token)
✅ CHECK A / CHECK B 语义
✅ RuntimeBinding 契约
✅ SuspensionCheckpoint 核心字段
✅ Evidence 定义
✅ M4 ephemeral 路径
```

---

### 10.2 M6 扩展点

```text
🆕 ExecutionRecord (新抽象，不影响 M5)
🆕 ExecutionLedger (新接口，不影响 M5)
🆕 EvidenceStore (新接口，不影响 M5)
🆕 Checkpoint.lastRecordSequence (新字段，向后兼容)
🆕 Checkpoint.evidenceIds (替代 accumulatedEvidences，迁移路径)
```

---

## 11. 架构决策记录 (ADR)

### ADR-M6-001: Event/Record 实现选型

**决策:** ExecutionRecord 作为单一持久化模型（选项 B）

**理由:**

- 最小 Public API 表面
- Schema 演化友好
- 序列化简单
- 可查询性强

**拒绝的替代方案:**

- 选项 A (分离 Event 和 Record 类型) — Public API 膨胀
- 选项 C (泛型信封) — 序列化复杂

---

### ADR-M6-002: Evidence 语义边界

**决策:** Evidence 保持狭义定义，仅表示工具执行证据

**理由:**

- 语义清晰
- M5 兼容性高
- 避免与 ExecutionRecord 混淆

**拒绝的替代方案:**

- 扩展 Evidence 为通用事件负载 — 语义混乱

---

### ADR-M6-003: Snapshot vs Journal vs Hybrid

**决策:** Hybrid Model (Checkpoint + ExecutionRecord + Evidence)

**理由:**

- 恢复性能（Checkpoint 快速恢复）
- 审计合规（ExecutionRecord 完整历史）
- 调试能力（Record + Evidence）
- M5 兼容性

**拒绝的替代方案:**

- Snapshot Only — 无审计历史
- Journal Only — 恢复性能差

---

### ADR-M6-004: Evidence 存储策略

**决策:** 独立 EvidenceStore（选项 B）

**理由:**

- 消除 O(n²) 写放大
- Checkpoint 大小可控
- Checkpoint 删除后 Evidence 保留
- Schema 独立演化

**拒绝的替代方案:**

- 选项 A (全量嵌入) — 写放大严重
- 选项 D (Hybrid) — 复杂度高

---

## 12. 架构不变式

### 12.1 核心依赖方向

```text
arctra-core → Spring AI: 0 dependencies ✅
arctra-core → runtime-react: 0 dependencies ✅
arctra-core → Spring Boot: 0 dependencies ✅
AgentRuntime → concrete engine: 0 dependencies ✅
```

---

### 12.2 状态所有权唯一性

```text
每个事实/数据有且仅有一个权威所有者

ChatMemory = 对话权威
ExecutionRecord = 执行历史权威
Evidence = 执行证据权威
Checkpoint = 当前恢复状态权威
```

---

### 12.3 Public API 克制

```text
优先级:
  先 internal
  → 真实场景证明
  → 再 public

M6 新增 Public API 必须通过 ADR 审批
```

---

## 13. 参考完整例子

### 审批恢复完整生命周期

```text
#1 PROCESS_STARTED
  timestamp = T0

#2 TOOL_PLANNED
  payload = {toolCallId: TC88, toolName: "restartServer", arguments: {server: "prod-01"}}

#3 APPROVAL_REQUIRED
  checkpointVersion = 1
  payload = {toolCallId: TC88, policyId: "production-ops-policy", reason: "..."}

#4 SUSPENDED
  checkpointVersion = 1
  payload = {reason: "REQUIRE_APPROVAL", pendingToolCallId: TC88}

[用户批准]

#5 APPROVAL_GRANTED
  payload = {toolCallId: TC88, approver: "alice@example.com", reason: "..."}

#6 RESUME_ATTEMPTED
  checkpointVersion = 1
  payload = {signal: APPROVED, runtimeId: "runtime-B"}

#7 RESUMED
  checkpointVersion = 1
  payload = {signal: APPROVED, runtimeId: "runtime-B"}

#8 TOOL_EXECUTED
  payload = {toolCallId: TC88, evidenceId: "E-P100-8", operationId: "OP-P100-TC88"}

#9 CHECKPOINT_ADVANCED
  checkpointVersion = 2 (v1 → v2)

#10 COMPLETED
  payload = {finalMessage: "...", checkpointDeleted: true}
```

---

## 14. 下一步行动

**推荐下一任务:** M6-T1 — ExecutionRecord + Evidence Separation Architecture

**范围:**

1. 定义 ExecutionRecord 类型
2. 定义 ExecutionLedger 接口
3. 定义 EvidenceStore 接口
4. InMemoryExecutionLedger 参考实现
5. InMemoryEvidenceStore 参考实现
6. Checkpoint Schema 扩展（lastRecordSequence, evidenceIds）
7. Architecture Test 验证新边界
8. M5 兼容性测试

**禁止:**

```text
❌ 修改 M5 核心语义
❌ 破坏 M4 ephemeral 路径
❌ 扩大 Public API 表面（除了新抽象）
❌ 实现 Track B 任务（Turn/Tool durability 延期到 M6-T5+）
```

---

## 附录: 术语表

| 术语                    | 定义                                                                 |
|-------------------------|----------------------------------------------------------------------|
| Event                   | 语义事实（概念层）— 某事发生了                                       |
| Record                  | 持久账本条目 — Event 的持久化表示                                    |
| Evidence                | 执行证明 — 证明或描述执行事实的数据                                  |
| Checkpoint              | 当前恢复快照 — 权威的当前状态                                        |
| ExecutionLedger         | 执行账本 — 持久化 ExecutionRecord 的存储抽象                         |
| EvidenceStore           | 证据存储 — 持久化 Evidence 的存储抽象                                |
| runtimeBindingKey       | 逻辑绑定标识 — 稳定的跨运行时绑定键                                  |
| RuntimeBindingResolver  | 运行时绑定解析器 — 重建逻辑等价运行时状态的契约                     |
| CHECK A                 | 执行前验证 — 在副作用前验证执行尝试是否仍然有效                     |
| CHECK B                 | 提交前验证 — 在提交时通过 CAS 检测并发冲突                          |
| At-Least-Once           | 至少一次执行保证 — 可能重复执行                                     |
| UNCERTAIN_OUTCOME       | 不确定结果 — 工具可能已执行但 Evidence 未提交                       |
| operationId             | 操作标识 — 外部系统幂等去重的 key                                    |

---

**文档维护者:** Arctra Architecture Team  
**最后更新:** 2026-09-09  
**下次审查:** M6-T1 完成后

**@author lov3r**
