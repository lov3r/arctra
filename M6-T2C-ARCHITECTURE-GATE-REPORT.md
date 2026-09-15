# M6-T2C — Minimal Execution Event Dispatch Architecture Gate Report

**日期:** 2026-09-10  
**状态:** Architecture Gate Evaluation  
**决策:** 见 Section Q

---

## A. Source Truth

### 当前相关类和构造器

**SpringAiToolCallingEngine** (arctra-runtime-react)
- 1099 行
- 3 个构造器:
  - 4-param: `(ChatModel, List<ToolCallback>, ChatMemory, ToolGovernancePolicy)` — M4 ephemeral
  - 7-param: `(上述 + CheckpointStore, RuntimeBindingResolver, String)` — M5 deprecated
  - 8-param: `(上述 + ExecutionLedger)` — M5-T4/M6-T2A 当前版本

**ExecutionLedger** (arctra-core)
- Interface, 3 个方法
- `append(processId, eventType, checkpointVersion, payload) → ExecutionRecord`
- `queryByProcess(processId)`
- `queryRecentByProcess(processId, limit)`

**InMemoryExecutionLedger** (arctra-core)
- 94 行实现
- 使用 `AtomicLong` 分配序列号
- 使用 `ConcurrentHashMap` 存储

**ExecutionRecord** (arctra-core)
- Record: `(recordId, processId, sequence, eventType, occurredAt, checkpointVersion, payload)`
- Ledger-assigned sequence
- Immutable audit record

**EventType** (arctra-core)
- Enum, 10 个事件类型
- 7 个用于 lifecycle: `APPROVAL_REQUIRED`, `APPROVAL_GRANTED`, `APPROVAL_REJECTED`, `SUSPENDED`, `RESUMED`, `COMPLETED`, `CHECKPOINT_CONFLICT`
- 2 个预留用于 tool: `TOOL_EXECUTED`, `TOOL_FAILED`
- 1 个预留: `PROCESS_STARTED`

### 当前模块依赖

```
SpringAiToolCallingEngine (arctra-runtime-react)
    ↓ owns
ExecutionLedger (arctra-core)
    ↓ produces
ExecutionRecord (arctra-core)
    ↓ contains
EventType (arctra-core)
```

---

## B. Current Event Wiring Map

从 SpringAiToolCallingEngine.java 中识别出 **10 个 lifecycle 事件发射点**:

### 1. APPROVAL_REQUIRED (初次暂停)
- **方法:** `suspendForApprovalDurable()`
- **代码行:** 318-333
- **域提交点:** 治理决策 = REQUIRE_APPROVAL (governance decision made)
- **Ledger 投影点:** 域事实为真后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.APPROVAL_REQUIRED, null, payload);
      } catch (Exception e) {
          // Ledger append failure does not block suspension
      }
  }
  ```
- **payload 构造:** `{"toolNames": [...], "policyReason": "governance_requires_approval"}`
- **checkpointVersion:** `null` (checkpoint 尚未分配版本)

### 2. SUSPENDED (初次暂停)
- **方法:** `suspendForApprovalDurable()`
- **代码行:** 352-365
- **域提交点:** `checkpointStore.create(checkpoint)` 成功
- **Ledger 投影点:** checkpoint 持久化后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.SUSPENDED, 1L, payload);
      } catch (Exception e) {
          // Ledger append failure does not block returning suspended result
      }
  }
  ```
- **payload 构造:** `{"checkpointVersion": 1, "pendingToolCount": N, "evidenceCount": M}`
- **checkpointVersion:** `1L` (初始版本)

### 3. APPROVAL_GRANTED
- **方法:** `resumeProcess()`
- **代码行:** 708-724
- **域提交点:** CHECK A + RuntimeBinding + APPROVED signal 验证通过
- **Ledger 投影点:** 批准决策验证后
- **当前调用:**
  ```java
  if (executionLedger != null && signal instanceof ApprovalSignal approval) {
      try {
          EventType approvalEvent = approval.approved() 
              ? EventType.APPROVAL_GRANTED 
              : EventType.APPROVAL_REJECTED;
          executionLedger.append(processId, approvalEvent, 
                                 checkpoint.checkpointVersion(), payload);
      } catch (Exception e) {
          // Ledger append failure does not block resume
      }
  }
  ```
- **payload 构造:** `{"signalType": "APPROVED", "checkpointVersion": N}`
- **checkpointVersion:** `checkpoint.checkpointVersion()` (当前版本)

### 4. APPROVAL_REJECTED
- **方法:** `resumeProcess()`
- **代码行:** 708-724 (与 APPROVAL_GRANTED 共享代码块)
- **域提交点:** CHECK A + RuntimeBinding + REJECTED signal 验证通过
- **Ledger 投影点:** 拒绝决策验证后
- **当前调用:** 同上 (通过 `approval.approved()` 分支)
- **payload 构造:** `{"signalType": "REJECTED", "checkpointVersion": N}`
- **checkpointVersion:** `checkpoint.checkpointVersion()`

### 5. RESUMED
- **方法:** `resumeProcess()`
- **代码行:** 729-742
- **域提交点:** CHECK A + RuntimeBinding + 批准决策确认,continuation 环境准备完成
- **Ledger 投影点:** 恢复环境准备完成后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.RESUMED, 
                                 checkpoint.checkpointVersion(), payload);
      } catch (Exception e) {
          // Ledger append failure does not block resume
      }
  }
  ```
- **payload 构造:** `{"checkpointVersion": N, "runtimeBindingKey": "..."}`
- **checkpointVersion:** `checkpoint.checkpointVersion()`

### 6. COMPLETED
- **方法:** `durableContinueWithMessages()`
- **代码行:** 849-862
- **域提交点:** CHECK B `deleteIfVersion()` 成功 (checkpoint 已删除)
- **Ledger 投影点:** checkpoint 删除后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.COMPLETED, 
                                 currentCheckpoint.checkpointVersion(), payload);
      } catch (Exception e) {
          // Ledger append failure does not block returning completed result
      }
  }
  ```
- **payload 构造:** `{"checkpointVersion": N, "evidenceCount": M}`
- **checkpointVersion:** `currentCheckpoint.checkpointVersion()` (已删除的版本)

### 7. CHECKPOINT_CONFLICT (completion 路径)
- **方法:** `durableContinueWithMessages()`
- **代码行:** 824-837
- **域提交点:** CHECK B `deleteIfVersion()` 返回 false (版本冲突)
- **Ledger 投影点:** CAS 失败后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.CHECKPOINT_CONFLICT, 
                                 currentCheckpoint.checkpointVersion(), payload);
      } catch (Exception e) {
          // Ledger append failure does not affect conflict handling
      }
  }
  ```
- **payload 构造:** `{"operation": "DELETE", "conflictType": "VERSION_MISMATCH", "checkpointVersion": N}`
- **checkpointVersion:** `currentCheckpoint.checkpointVersion()`

### 8. APPROVAL_REQUIRED (re-suspension)
- **方法:** `handleDurableReSuspension()`
- **代码行:** 905-919
- **域提交点:** 治理决策 = REQUIRE_APPROVAL (新的待批准批次)
- **Ledger 投影点:** 治理决策后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.APPROVAL_REQUIRED, 
                                 oldCheckpoint.checkpointVersion(), payload);
      } catch (Exception e) {
          // Ledger append failure does not block re-suspension
      }
  }
  ```
- **payload 构造:** `{"toolNames": [...], "policyReason": "...", "resuspension": true}`
- **checkpointVersion:** `oldCheckpoint.checkpointVersion()` (当前版本)

### 9. CHECKPOINT_CONFLICT (re-suspension 路径)
- **方法:** `handleDurableReSuspension()`
- **代码行:** 943-956
- **域提交点:** CHECK B `replaceIfVersion()` 返回 false (版本冲突)
- **Ledger 投影点:** CAS 失败后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.CHECKPOINT_CONFLICT, 
                                 oldCheckpoint.checkpointVersion(), payload);
      } catch (Exception e) {
          // Ledger append failure does not affect conflict handling
      }
  }
  ```
- **payload 构造:** `{"operation": "REPLACE", "conflictType": "VERSION_MISMATCH", ...}`
- **checkpointVersion:** `oldCheckpoint.checkpointVersion()`

### 10. SUSPENDED (re-suspension)
- **方法:** `handleDurableReSuspension()`
- **代码行:** 968-981
- **域提交点:** CHECK B `replaceIfVersion()` 成功 (新 checkpoint 存在)
- **Ledger 投影点:** checkpoint 替换成功后
- **当前调用:**
  ```java
  if (executionLedger != null) {
      try {
          executionLedger.append(processId, EventType.SUSPENDED, 
                                 nextVersion, payload);
      } catch (Exception e) {
          // Ledger append failure does not block returning suspended result
      }
  }
  ```
- **payload 构造:** `{"checkpointVersion": N+1, "pendingToolCount": X, "evidenceCount": Y, "resuspension": true}`
- **checkpointVersion:** `nextVersion` (新版本)

---

## C. Engine Responsibility / Duplication Evidence

### 当前 SpringAiToolCallingEngine 职责

**核心职责:**
1. 执行 Agent (Spring AI ChatClient + Tool Calling Loop)
2. Evidence 收集 (per-execution isolation)
3. Governance 集成 (GovernanceToolCallingAdvisor)
4. Session 管理 (ChatMemory integration)
5. 暂停/恢复协议 (protocol reconstruction)
6. Checkpoint 管理 (durable suspension)
7. **✅ ExecutionLedger 投影 (10 个发射点)**

**跨领域关注点负担:**

```java
// Repeated pattern (×10):
if (executionLedger != null) {
    try {
        executionLedger.append(
            processId,
            EventType.XXX,
            checkpointVersion,
            """
            {"key": "value"}
            """
        );
    } catch (Exception e) {
        // Ledger append failure does not block ...
        // Domain fact remains true
        // Audit trail has a gap
    }
}
```

### 重复代码统计

**10 个发射点:**
- `if (executionLedger != null)` 检查: 10 次
- `try { ... } catch (Exception e) { ... }` 块: 10 次
- `executionLedger.append(...)` 调用: 10 次
- payload JSON 格式化: 10 次
- 相同的异常处理注释: 10 次

**代码行数估算:**
- 每个发射点平均 ~20 行 (null check + try/catch + append + payload formatting + comments)
- 总计: **~200 行** 用于 ledger 投影逻辑

**构造器依赖:**
- `ExecutionLedger executionLedger` 作为第 8 个参数
- 存储为 `private final ExecutionLedger executionLedger;`
- 被 10 个方法直接引用

### 具体重复示例

**重复 1: null check**
```java
// 出现 10 次
if (executionLedger != null) {
    // ...
}
```

**重复 2: try/catch**
```java
// 出现 10 次
try {
    executionLedger.append(...);
} catch (Exception e) {
    // Ledger append failure does not block ...
}
```

**重复 3: 参数构造**
```java
// 每个发射点都要构造:
processId,                      // 提取自不同来源
EventType.XXX,                  // 硬编码
checkpointVersion,              // 提取自不同来源
"""                             // JSON payload 格式化
{"key": "value"}
"""
```

**重复 4: 异常处理注释**
```java
// 几乎相同的注释出现 10 次:
// Ledger append failure does not block [operation]
// Domain fact remains true
// Audit trail has a gap
```

---

## D. Minimal Proposed Architecture

### 类图

```
SpringAiToolCallingEngine
    ↓ owns one
ExecutionEventDispatcher (package-private)
    ↓ owns list of
ExecutionEventListener (functional interface)
    ↑ implements
ExecutionLedgerListener (package-private)
    ↓ owns
ExecutionLedger
```

### 核心抽象

**1. ExecutionEvent (record)**
```java
/**
 * 已经为真的运行时/域事实的发射通知.
 * 
 * 不是 ExecutionRecord - Record 是 Ledger 分配序列号后的持久化产物.
 * Event 是发射时刻的域事实陈述.
 */
package cn.bitcss.arctra.execution;

public record ExecutionEvent(
    String processId,
    EventType eventType,
    Long checkpointVersion,  // nullable
    String payload           // nullable, JSON string
) {
    public ExecutionEvent {
        Objects.requireNonNull(processId, "processId cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        
        if (checkpointVersion != null && checkpointVersion <= 0) {
            throw new IllegalArgumentException(
                "checkpointVersion must be positive when non-null");
        }
    }
}
```

**2. ExecutionEventListener (functional interface)**
```java
/**
 * 执行事件监听器.
 * 
 * 接收已经为真的域事实的通知.
 * 监听器不得创建域真相 - 仅投影/观测已发生的事实.
 */
package cn.bitcss.arctra.execution;

@FunctionalInterface
public interface ExecutionEventListener {
    /**
     * 处理执行事件.
     * 
     * @param event 已经为真的域事实
     */
    void onEvent(ExecutionEvent event);
}
```

**3. ExecutionEventDispatcher (package-private)**
```java
/**
 * 同步的进程内执行事件分发器.
 * 
 * 按注册顺序调用监听器. 单个监听器失败不抑制其他监听器.
 * 无异步,无队列,无重试 - 仅职责提取.
 */
package cn.bitcss.arctra.execution;

final class ExecutionEventDispatcher {
    
    private final List<ExecutionEventListener> listeners;
    
    ExecutionEventDispatcher(List<ExecutionEventListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }
    
    /**
     * 发布事件到所有监听器.
     * 
     * 监听器按注册顺序同步调用.
     * 监听器异常被隔离 - 单个失败不抑制其他监听器.
     */
    void publish(ExecutionEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                // 监听器失败被隔离
                // TODO: 未来可添加日志记录 (不引入 SLF4J 依赖现在)
            }
        }
    }
}
```

**4. ExecutionLedgerListener (package-private)**
```java
/**
 * 将执行事件投影到 ExecutionLedger 的适配器.
 */
package cn.bitcss.arctra.execution;

final class ExecutionLedgerListener implements ExecutionEventListener {
    
    private final ExecutionLedger ledger;
    
    ExecutionLedgerListener(ExecutionLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
    }
    
    @Override
    public void onEvent(ExecutionEvent event) {
        ledger.append(
            event.processId(),
            event.eventType(),
            event.checkpointVersion(),
            event.payload()
        );
    }
}
```

### 最小化设计决策

**包含:**
- ✅ ExecutionEvent record (4 个字段)
- ✅ ExecutionEventListener 功能接口 (1 个方法)
- ✅ ExecutionEventDispatcher (package-private, 同步)
- ✅ ExecutionLedgerListener (package-private, 适配器)

**不包含:**
- ❌ EventBus / EventCenter / EventPublisher SPI
- ❌ Topics / channels / routing keys
- ❌ Filters / priorities / subscriptions
- ❌ 异步派发 / executor / queue
- ❌ Retry / dead-letter / transactional outbox
- ❌ Event sourcing / replay / recovery authority
- ❌ 分布式事件传输
- ❌ ListenerFailurePolicy / RetryPolicy
- ❌ eventId / 事件序列号分配 (sequence 仍属于 Ledger)

---

## E. ExecutionEvent Design

### 字段

```java
public record ExecutionEvent(
    String processId,           // REQUIRED - 进程标识
    EventType eventType,        // REQUIRED - 事件类型
    Long checkpointVersion,     // NULLABLE - checkpoint 版本
    String payload              // NULLABLE - JSON payload
)
```

### 字段验证

**Validation 所有权: ExecutionEvent 构造器**

```java
public ExecutionEvent {
    // processId: non-null, non-blank
    Objects.requireNonNull(processId, "processId cannot be null");
    if (processId.isBlank()) {
        throw new IllegalArgumentException("processId cannot be blank");
    }
    
    // eventType: non-null
    Objects.requireNonNull(eventType, "eventType cannot be null");
    
    // checkpointVersion: nullable, but if non-null must be positive
    if (checkpointVersion != null && checkpointVersion <= 0) {
        throw new IllegalArgumentException(
            "checkpointVersion must be positive when non-null");
    }
    
    // payload: nullable, no validation (format 由调用者保证)
}
```

### checkpointVersion Nullability 语义

**nullable 理由:**
- 某些事件发生在 checkpoint 分配前 (如初次 APPROVAL_REQUIRED)
- 某些事件发生在 checkpoint 删除后 (如 COMPLETED)
- Nullable design 显式表达"非所有事件都在 checkpointed state 中"

**Non-null 场景:**
- SUSPENDED (checkpoint 已创建)
- APPROVAL_GRANTED/REJECTED (CHECK A 验证了 checkpoint)
- RESUMED (checkpoint 存在)
- CHECKPOINT_CONFLICT (checkpoint 版本冲突)

**Null 场景:**
- 初次 APPROVAL_REQUIRED (checkpoint 尚未创建)
- COMPLETED (checkpoint 已删除)
- Future PROCESS_STARTED (可能在 checkpoint 之前)

---

## F. ExecutionEvent vs ExecutionRecord

### 概念区分

| 维度 | ExecutionEvent | ExecutionRecord |
|-----|----------------|-----------------|
| **性质** | 运行时域事实的发射通知 | 持久化的历史审计记录 |
| **生命周期** | 短暂 (method call) | 永久 (append-only) |
| **序列号** | 无 | Ledger-assigned sequence |
| **recordId** | 无 | Ledger-derived `processId:sequence` |
| **occurredAt** | 无 | Ledger-assigned timestamp |
| **Authority** | 域事实发生的通知 | 历史审计 authority |
| **创建者** | Engine (域逻辑) | Ledger (存储层) |
| **消费者** | Listeners (投影/观测) | 查询/审计/诊断 |

### Authority 职责明确

**ExecutionEvent:**
- 陈述"域事实已为真"
- 不分配序列号
- 不分配 recordId
- 不分配时间戳
- 不保证持久化

**ExecutionRecord:**
- Ledger 分配 sequence (历史顺序 authority)
- Ledger 分配 recordId (`processId:sequence`)
- Ledger 分配 occurredAt (存储时间戳)
- Ledger 保证 append-only 不可变性
- Ledger 是历史审计 authority

**数据流:**
```
Domain Fact becomes TRUE
    ↓
ExecutionEvent (Engine 发射)
    ↓
ExecutionEventListener.onEvent()
    ↓
ExecutionLedgerListener.onEvent()
    ↓
ExecutionLedger.append()
    ↓
ExecutionRecord (Ledger 分配 sequence/recordId/occurredAt)
```

### 为什么不让 Engine 直接创建 ExecutionRecord?

**原因:**
1. **Sequence allocation authority 属于 Ledger**
   - Ledger 负责分配唯一单调序列号
   - Engine 不应猜测或预分配序列号

2. **recordId 是派生标识**
   - Format: `processId:sequence`
   - Sequence 必须由 Ledger 分配后才能构造 recordId

3. **Timestamp authority 属于 Ledger**
   - Ledger 记录存储时间 (ledger-side)
   - Engine 记录域事实发生时间 (domain-side) - 但这不是 ExecutionRecord 的语义

4. **职责分离**
   - Engine: 域逻辑 + 域事实发射
   - Ledger: 存储 + 历史顺序 authority
   - ExecutionRecord 是 Ledger 的输出产物,不是 Engine 的输入

---

## G. Public API Decision

### ExecutionEvent

**提议: PUBLIC**

**理由:**
- ✅ 未来 logging/metrics/tracing listeners 需要访问
- ✅ 用户可能实现自定义 ExecutionEventListener (audit, business event projection)
- ✅ 4 个字段都是合理的公共 API (processId, eventType, checkpointVersion, payload)
- ✅ 简单的 record,无行为,低维护成本

**模块:** arctra-core (execution 包)

### ExecutionEventListener

**提议: PUBLIC**

**理由:**
- ✅ 这是合法的公共扩展点
- ✅ 真实用例存在:
  - Logging listener (log execution events)
  - Metrics listener (计数/计时)
  - Tracing listener (distributed trace spans)
  - Audit listener (合规审计,独立于 Ledger)
  - Business event listener (投影到业务事件总线)
- ✅ 功能接口,单方法,稳定契约
- ✅ 与 ExecutionEvent 对称 (event 是 public, listener 应该也是)

**反对理由:**
- ❌ "可能只是为了对称而公开" - 但真实用例存在,不仅仅是对称

**模块:** arctra-core (execution 包)

### ExecutionEventDispatcher

**提议: PACKAGE-PRIVATE**

**理由:**
- ✅ 用户不需要直接构造或持有 dispatcher
- ✅ Dispatcher 是内部组合机制
- ✅ Engine 内部使用,不暴露给外部
- ✅ 未来如果需要公开,可以再提升可见性 (internal-first)

**模块:** arctra-runtime-react (Engine 同包)

**如果未来需要公开:**
- 可能的场景: AgentRuntime 需要统一 dispatcher 实例跨多个 engine
- 那时再评估是否提升为 public (当前无需求)

### ExecutionLedgerListener

**提议: PACKAGE-PRIVATE**

**理由:**
- ✅ 这是 Engine 内部适配器
- ✅ 用户不需要直接构造 (Engine 内部组合)
- ✅ 实现细节,不是扩展点

**模块:** arctra-runtime-react (Engine 同包)

### EventType

**已经是 PUBLIC** (M6-T1)
- 无需更改

### ExecutionLedger

**已经是 PUBLIC** (M6-T1)
- 无需更改

### ExecutionRecord

**已经是 PUBLIC** (M6-T1)
- 无需更改

### Summary

| 类型 | 可见性 | 模块 | 理由 |
|-----|--------|-----|-----|
| ExecutionEvent | **PUBLIC** | arctra-core | 扩展点需要,真实用例 |
| ExecutionEventListener | **PUBLIC** | arctra-core | 合法扩展点,多用例 |
| ExecutionEventDispatcher | **PACKAGE-PRIVATE** | arctra-runtime-react | 内部组合机制 |
| ExecutionLedgerListener | **PACKAGE-PRIVATE** | arctra-runtime-react | 内部适配器 |

---

## H. Dispatcher Ownership

### 候选方案

**A. SpringAiToolCallingEngine 直接拥有 dispatcher**
```java
private final ExecutionEventDispatcher dispatcher;

public SpringAiToolCallingEngine(..., ExecutionEventDispatcher dispatcher) {
    this.dispatcher = dispatcher;
}

private void emitEvent(...) {
    dispatcher.publish(new ExecutionEvent(...));
}
```

**B. SpringAiToolCallingEngine 拥有单个 listener,外部组合**
```java
private final ExecutionEventListener listener;

public SpringAiToolCallingEngine(..., ExecutionEventListener listener) {
    this.listener = listener;
}

private void emitEvent(...) {
    listener.onEvent(new ExecutionEvent(...));
}

// 外部组合:
var listeners = List.of(
    new ExecutionLedgerListener(ledger),
    new LoggingListener(),
    new MetricsListener()
);
var compositeListener = new CompositeExecutionEventListener(listeners);
var engine = new SpringAiToolCallingEngine(..., compositeListener);
```

**C. SpringAiToolCallingEngine 拥有 listener 集合**
```java
private final List<ExecutionEventListener> listeners;

public SpringAiToolCallingEngine(..., List<ExecutionEventListener> listeners) {
    this.listeners = List.copyOf(listeners);
}

private void emitEvent(...) {
    var event = new ExecutionEvent(...);
    for (var listener : listeners) {
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            // isolate
        }
    }
}
```

**D. 另一个现有 runtime 抽象是正确的所有者**
- 例如: AgentRuntime 拥有 dispatcher
- Engine 通过 runtime 发射事件
- 评估: AgentRuntime 当前是 package-private,且不处理 lifecycle events

### 推荐方案: A (Engine 拥有 dispatcher)

**理由:**

1. **最小变更**
   - Dispatcher 是 package-private,在 Engine 同包
   - Engine 内部组合,外部不可见
   - 未来如果需要跨 engine 共享,再重构

2. **清晰的发射点**
   - Engine 知道域提交点
   - Engine 发射事件
   - Engine 不需要知道有多少 listeners

3. **避免构造器爆炸**
   - 不传递 `List<ExecutionEventListener>` (方案 C)
   - 不传递单个 `ExecutionEventListener` (方案 B,需要外部组合)
   - 传递 `ExecutionEventDispatcher` (封装了 listeners)

4. **隔离失败语义**
   - Dispatcher 内部处理 listener 异常隔离
   - Engine 不需要知道异常隔离逻辑

5. **未来扩展性**
   - 如果未来需要 AgentRuntime 级别的 dispatcher,可以重构
   - 当前 Engine-owned dispatcher 是最简单的起点

### 构造示例

```java
// Engine constructor
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionEventDispatcher eventDispatcher) { // ← NEW
    
    this.chatModel = chatModel;
    // ...
    this.eventDispatcher = eventDispatcher; // ← NEW
}

// Emit event (替换原来的 if (executionLedger != null) { ... })
private void emitEvent(
    String processId, 
    EventType eventType, 
    Long checkpointVersion, 
    String payload) {
    
    eventDispatcher.publish(
        new ExecutionEvent(processId, eventType, checkpointVersion, payload)
    );
}

// 原来的发射点变为:
emitEvent(processId, EventType.SUSPENDED, 1L, """
    {"checkpointVersion": 1, "pendingToolCount": %d}
    """.formatted(pendingBatch.size()));
```

### 外部组装 (调用者)

```java
// 创建 listeners
List<ExecutionEventListener> listeners = new ArrayList<>();

if (executionLedger != null) {
    listeners.add(new ExecutionLedgerListener(executionLedger));
}

// 未来可添加:
// listeners.add(new LoggingExecutionEventListener());
// listeners.add(new MetricsExecutionEventListener());

// 创建 dispatcher
var dispatcher = new ExecutionEventDispatcher(listeners);

// 创建 engine
var engine = new SpringAiToolCallingEngine(
    chatModel,
    tools,
    chatMemory,
    governancePolicy,
    checkpointStore,
    bindingResolver,
    runtimeBindingKey,
    dispatcher // ← 替换原来的 executionLedger 参数
);
```

---

## I. ExecutionLedger Adapter

### ExecutionLedgerListener 设计

```java
/**
 * ExecutionLedger 投影监听器.
 * 
 * 将执行事件投影到持久化 audit ledger.
 * Ledger append 失败不抛出异常 - 失败被静默处理.
 */
final class ExecutionLedgerListener implements ExecutionEventListener {
    
    private final ExecutionLedger ledger;
    
    ExecutionLedgerListener(ExecutionLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger cannot be null");
    }
    
    @Override
    public void onEvent(ExecutionEvent event) {
        // 直接调用 ledger.append()
        // 如果 append 抛出异常,由 Dispatcher 的 try/catch 捕获
        ledger.append(
            event.processId(),
            event.eventType(),
            event.checkpointVersion(),
            event.payload()
        );
    }
}
```

### Engine 从直接依赖 ExecutionLedger 变为依赖 Dispatcher

**BEFORE (M6-T2A):**
```java
private final ExecutionLedger executionLedger; // nullable

public SpringAiToolCallingEngine(..., ExecutionLedger executionLedger) {
    this.executionLedger = executionLedger;
}

// 10 个发射点:
if (executionLedger != null) {
    try {
        executionLedger.append(processId, eventType, version, payload);
    } catch (Exception e) {
        // silent
    }
}
```

**AFTER (M6-T2C):**
```java
private final ExecutionEventDispatcher eventDispatcher; // non-null

public SpringAiToolCallingEngine(..., ExecutionEventDispatcher eventDispatcher) {
    this.eventDispatcher = Objects.requireNonNull(eventDispatcher);
}

// 10 个发射点:
eventDispatcher.publish(
    new ExecutionEvent(processId, eventType, version, payload)
);
```

### Ledger 如何成为投影 listener

**组装时 (外部):**
```java
// 构建 listeners
List<ExecutionEventListener> listeners = new ArrayList<>();

if (executionLedger != null) {
    listeners.add(new ExecutionLedgerListener(executionLedger));
}

// 如果没有 ledger,listeners 为空 - dispatcher 仍然工作,只是不投影
var dispatcher = new ExecutionEventDispatcher(listeners);
```

### Nullable Ledger 语义保留

**M6-T2A 语义:**
- `ExecutionLedger executionLedger` 可以为 null
- 如果为 null,不记录 audit trail

**M6-T2C 语义:**
- `ExecutionEventDispatcher eventDispatcher` 不能为 null (但可以是空 listeners)
- 如果 ledger 为 null,不添加 ExecutionLedgerListener
- Empty dispatcher 仍然工作 (publish() 是 no-op)

---

## J. Listener Failure Isolation

### 失败语义

**设计决策: Dispatcher 级别隔离**

每个 listener 独立调用,异常被隔离:
```java
void publish(ExecutionEvent event) {
    for (var listener : listeners) {
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            // Listener 失败被隔离
            // 其他 listeners 仍然接收事件
            // 未来可添加日志 (不引入 SLF4J 现在)
        }
    }
}
```

### 为什么在 Dispatcher 而不是 Listener?

**原因:**
1. **统一失败处理点**
   - 一个地方处理所有 listener 失败
   - 避免每个 listener 都要 try/catch

2. **Listener 实现简单**
   - ExecutionLedgerListener 不需要 try/catch
   - 未来 LoggingListener 不需要 try/catch
   - 失败处理是 framework 责任,不是 listener 责任

3. **Engine 完全不知道失败**
   - Engine 调用 `dispatcher.publish()` 不会抛出异常
   - Engine 不需要处理 listener 失败

4. **避免多层 try/catch**
   - 不是: Engine try/catch → Dispatcher try/catch → Listener try/catch
   - 而是: Engine 调用 → Dispatcher try/catch → Listener 抛出

### 失败后的行为

**Listener A 失败,Listener B 是否仍然接收事件?**

**回答: 是**

```java
// 示例:
var listeners = List.of(
    new ExecutionLedgerListener(ledger),  // 如果这个失败
    new MetricsListener()                  // 这个仍然接收事件
);

dispatcher.publish(event);
// → ExecutionLedgerListener.onEvent() 抛出异常
// → Dispatcher 捕获,隔离
// → MetricsListener.onEvent() 仍然被调用
```

**理由:**
- 一个观测投影的失败不应该阻止其他观测投影
- Ledger append 失败不应该阻止 metrics 记录
- Metrics 失败不应该阻止 ledger append

### 失败是否影响域逻辑?

**回答: 不影响**

**M6-T2A 现有语义:**
> "Ledger append failure does not block execution. Domain fact remains true. Audit trail has a gap."

**M6-T2C 保留此语义:**
- Listener 失败是投影失败,不是域逻辑失败
- 域事实已经为真 (例如: checkpoint 已经持久化)
- Listener 失败只意味着观测有缺口
- Engine 继续执行,不抛出异常

### 未来是否需要 STRICT mode?

**当前: 不需要**

**理由:**
- M6-T2A 已经确立"投影失败不影响域逻辑"的语义
- 没有场景需要"ledger append 失败必须中止 execution"
- 如果未来需要,可以添加 `FailurePolicy` (但不在 M6-T2C 范围)

**不创建的抽象:**
- ❌ `ListenerFailurePolicy` (STRICT / RELAXED)
- ❌ `FailureStrategy` SPI
- ❌ Retry logic
- ❌ Dead-letter queue

---

## K. Constructor Migration

### 当前构造器 (M6-T2A)

```java
// 1. M4 ephemeral (4-param)
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy)

// 2. M5 deprecated (7-param)
@Deprecated
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey)

// 3. M5-T4/M6-T2A current (8-param)
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger) // ← nullable
```

### M6-T2C 提议构造器

```java
// 1. M4 ephemeral (4-param) - 保持兼容
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy) {
    
    this(chatModel, tools, chatMemory, governancePolicy, 
         null, null, null, 
         new ExecutionEventDispatcher(List.of())); // ← empty dispatcher
}

// 2. M5 deprecated (7-param) → 标记 @Deprecated,但保留
@Deprecated
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey) {
    
    this(chatModel, tools, chatMemory, governancePolicy,
         checkpointStore, bindingResolver, runtimeBindingKey,
         new ExecutionEventDispatcher(List.of())); // ← empty dispatcher
}

// 3. M6-T2A (8-param with ExecutionLedger) → 标记 @Deprecated
@Deprecated
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger) { // ← OLD API
    
    // 兼容性适配: ExecutionLedger → Dispatcher
    List<ExecutionEventListener> listeners = new ArrayList<>();
    if (executionLedger != null) {
        listeners.add(new ExecutionLedgerListener(executionLedger));
    }
    
    this(chatModel, tools, chatMemory, governancePolicy,
         checkpointStore, bindingResolver, runtimeBindingKey,
         new ExecutionEventDispatcher(listeners)); // ← NEW API
}

// 4. M6-T2C canonical (8-param with Dispatcher) - 新的规范构造器
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionEventDispatcher eventDispatcher) { // ← NEW
    
    // 实际实现
    this.chatModel = Objects.requireNonNull(chatModel);
    this.tools = Objects.requireNonNull(tools);
    this.chatMemory = Objects.requireNonNull(chatMemory);
    this.governancePolicy = Objects.requireNonNull(governancePolicy);
    
    // Validate durable config (all-or-nothing)
    // ...
    
    this.checkpointStore = checkpointStore;
    this.bindingResolver = bindingResolver;
    this.runtimeBindingKey = runtimeBindingKey;
    this.eventDispatcher = Objects.requireNonNull(eventDispatcher); // ← NEW
}
```

### 构造器演进策略

**M6-T2C:**
- ✅ 4-param: 保留,兼容
- ✅ 7-param: @Deprecated (M5),保留
- ✅ 8-param (ExecutionLedger): @Deprecated (M6-T2C),保留,适配到新 API
- ✅ 8-param (ExecutionEventDispatcher): 新规范构造器

**未来清理 (M7+):**
- 可以移除 7-param 构造器 (M5 deprecated)
- 可以移除 8-param (ExecutionLedger) 构造器 (M6-T2A deprecated)
- 保留 4-param + 8-param (Dispatcher) 作为稳定 API

### Breaking Changes 评估

**对 M6-T2A 用户代码的影响:**

**场景 1: 直接使用 4-param 构造器 (ephemeral mode)**
```java
// M6-T2A code:
var engine = new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, governancePolicy);

// M6-T2C: ✅ 零影响 (4-param 构造器保留)
```

**场景 2: 直接使用 8-param 构造器 (with ExecutionLedger)**
```java
// M6-T2A code:
var engine = new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, governancePolicy,
    checkpointStore, bindingResolver, runtimeBindingKey,
    executionLedger);

// M6-T2C: ⚠️ 构造器标记 @Deprecated (但仍然工作)
// 用户需要迁移到新 API:
var dispatcher = new ExecutionEventDispatcher(
    List.of(new ExecutionLedgerListener(executionLedger)));
var engine = new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, governancePolicy,
    checkpointStore, bindingResolver, runtimeBindingKey,
    dispatcher);
```

**场景 3: Engine 实现者 (如果有子类)**
```java
// M6-T2A: SpringAiToolCallingEngine 是 public class (非 final)
// M6-T2C: 仍然 public class (非 final)
// ✅ 无子类化场景 (Engine 不是扩展点)
```

**结论:**
- M4 ephemeral 用户: 零影响
- M6-T2A ledger 用户: @Deprecated 警告,需要迁移 (但旧 API 仍然工作)
- Breaking change 风险: **低** (兼容性构造器保留)

---

## L. Module Placement

### 提议模块结构

```
arctra-core (cn.bitcss.arctra.execution)
├── ExecutionEvent.java (record, PUBLIC)
├── ExecutionEventListener.java (interface, PUBLIC)
├── ExecutionLedger.java (interface, PUBLIC) - 已存在
├── ExecutionRecord.java (record, PUBLIC) - 已存在
├── EventType.java (enum, PUBLIC) - 已存在
└── InMemoryExecutionLedger.java (class, PUBLIC) - 已存在

arctra-runtime-react (cn.bitcss.arctra.runtime.react)
├── SpringAiToolCallingEngine.java - 修改
├── ExecutionEventDispatcher.java (class, PACKAGE-PRIVATE) - 新增
└── ExecutionLedgerListener.java (class, PACKAGE-PRIVATE) - 新增
```

### 模块边界

**arctra-core (framework-neutral):**
- ✅ ExecutionEvent (domain concept)
- ✅ ExecutionEventListener (extension contract)
- ✅ ExecutionLedger / ExecutionRecord / EventType (已存在)
- ❌ ExecutionEventDispatcher (runtime 实现细节)
- ❌ ExecutionLedgerListener (runtime 实现细节)

**arctra-runtime-react (Spring AI integration):**
- ✅ SpringAiToolCallingEngine (修改)
- ✅ ExecutionEventDispatcher (package-private)
- ✅ ExecutionLedgerListener (package-private)

### 依赖方向

```
SpringAiToolCallingEngine (react)
    ↓ uses
ExecutionEventDispatcher (react, package-private)
    ↓ uses
ExecutionEventListener (core, public)
    ↑ implements
ExecutionLedgerListener (react, package-private)
    ↓ uses
ExecutionLedger (core, public)
```

**Architecture rules 保护:**
```java
// ArchUnit rule (already exists):
classes().that().resideInPackage("cn.bitcss.arctra..")
    .and().areNotAnnotatedWith(AllowedDependency.class)
    .should().onlyDependOn(
        "cn.bitcss.arctra..",
        "java..",
        "org.springframework.." // only for runtime modules
    );
```

M6-T2C 不违反任何现有 architecture rules.

---

## M. Expected Engine Simplification

### BEFORE (M6-T2A)

**SpringAiToolCallingEngine 职责:**
1. 执行 Agent (Spring AI integration)
2. Evidence 收集
3. Governance integration
4. Session management
5. Checkpoint management
6. Protocol reconstruction
7. **✅ ExecutionLedger 投影逻辑 (10 个发射点)**

**ExecutionLedger 知识:**
- Engine 知道 `ExecutionLedger` interface
- Engine 知道 `append()` 方法签名
- Engine 知道 null check 语义
- Engine 知道异常隔离策略
- Engine 重复实现 10 次相同模式

**代码统计 (10 个发射点):**
```java
// 每个发射点 ~20 行:
if (executionLedger != null) {              // 1 line
    try {                                    // 1 line
        String payload = """                 // 3-5 lines
            {"key": "value"}
            """.formatted(...);
        executionLedger.append(              // 5 lines
            processId,
            EventType.XXX,
            checkpointVersion,
            payload
        );
    } catch (Exception e) {                  // 1 line
        // Comment: 3 lines                   // 3 lines
    }
}                                            // 1 line

// 总计: 10 sites × 20 lines = ~200 lines
```

**构造器:**
```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger) // ← 第 8 个参数
```

### AFTER (M6-T2C)

**SpringAiToolCallingEngine 职责:**
1. 执行 Agent (Spring AI integration)
2. Evidence 收集
3. Governance integration
4. Session management
5. Checkpoint management
6. Protocol reconstruction
7. **✅ 域事实发射 (10 个发射点) - 但不知道如何投影**

**ExecutionLedger 知识:**
- Engine **不知道** `ExecutionLedger` interface
- Engine **不知道** `append()` 方法
- Engine **不知道** null check 语义
- Engine **不知道** 异常隔离策略
- Engine 只知道 `ExecutionEventDispatcher.publish()`

**代码统计 (10 个发射点):**
```java
// 每个发射点 ~6 行 (可能提取为 helper):
emitEvent(                                   // 5-6 lines
    processId,
    EventType.XXX,
    checkpointVersion,
    """
    {"key": "value"}
    """.formatted(...)
);

// 或者直接:
eventDispatcher.publish(                     // 5-6 lines
    new ExecutionEvent(
        processId, 
        EventType.XXX, 
        checkpointVersion, 
        """
        {"key": "value"}
        """.formatted(...)
    )
);

// 总计: 10 sites × 6 lines = ~60 lines
// (如果提取 helper 方法,可能更少)
```

**可选 helper 方法 (进一步简化):**
```java
// Engine 内部 private helper:
private void emitEvent(
    String processId, 
    EventType eventType, 
    Long checkpointVersion, 
    String payload) {
    
    eventDispatcher.publish(
        new ExecutionEvent(processId, eventType, checkpointVersion, payload)
    );
}

// 发射点进一步简化为 1-2 lines:
emitEvent(processId, EventType.SUSPENDED, 1L, 
    """{"checkpointVersion": 1}""");
```

**构造器:**
```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionEventDispatcher eventDispatcher) // ← 第 8 个参数,类型变化
```

### 量化对比

| 维度 | BEFORE (M6-T2A) | AFTER (M6-T2C) | 改进 |
|-----|-----------------|----------------|-----|
| **发射点代码量** | ~200 lines | ~60 lines (或 ~10 lines with helper) | **-70% to -95%** |
| **null check** | 10 次 | 0 次 | **-100%** |
| **try/catch** | 10 次 | 0 次 (moved to Dispatcher) | **-100%** |
| **ExecutionLedger 知识** | Engine 直接依赖 | Engine 不知道 | **完全解耦** |
| **异常处理重复** | 10 次相同注释 | 1 次 (Dispatcher) | **-90%** |
| **构造器参数** | `ExecutionLedger` | `ExecutionEventDispatcher` | 类型变化,数量不变 |
| **未来 observability** | 每次都要修改 Engine | 添加新 Listener | **零 Engine 修改** |

### 职责提取证据

**BEFORE:**
```
SpringAiToolCallingEngine
    ├── 执行逻辑 (core responsibility)
    ├── 投影逻辑 (cross-cutting concern)
    │   ├── null check
    │   ├── try/catch
    │   ├── append call
    │   └── exception isolation
    └── (重复 10 次)
```

**AFTER:**
```
SpringAiToolCallingEngine
    └── 执行逻辑 + 域事实发射 (core responsibility)

ExecutionEventDispatcher (新抽象)
    └── 投影分发 + 异常隔离 (cross-cutting concern)

ExecutionLedgerListener (新适配器)
    └── Ledger 投影 (具体投影逻辑)
```

### 未来扩展验证

**添加 Logging Listener (BEFORE vs AFTER):**

**BEFORE (M6-T2A):**
```java
// 需要修改 SpringAiToolCallingEngine 的 10 个发射点:
if (executionLedger != null) {
    try {
        executionLedger.append(...);
    } catch (Exception e) { }
}

// 添加 logging:
if (executionLogger != null) {  // ← 新增
    try {                         // ← 新增
        executionLogger.log(...);  // ← 新增
    } catch (Exception e) { }     // ← 新增
}                                 // ← 新增

// 10 个发射点都要改! 每个发射点 +5 lines
// 总计: +50 lines
```

**AFTER (M6-T2C):**
```java
// Engine 零修改

// 外部组装时添加 listener:
var listeners = List.of(
    new ExecutionLedgerListener(ledger),
    new LoggingExecutionEventListener()  // ← 新增,零 Engine 修改
);

var dispatcher = new ExecutionEventDispatcher(listeners);
var engine = new SpringAiToolCallingEngine(..., dispatcher);
```

**结论: M6-T2C 提供可测量的职责简化和未来扩展性。**

---

## N. M6-T2B Compatibility

### M6-T2B 目标

**TOOL_EXECUTED / TOOL_FAILED 事件发射**

M6-T2B 尚未开始,但预期需要:
1. 工具执行成功 → 发射 `TOOL_EXECUTED`
2. 工具执行失败 → 发射 `TOOL_FAILED`
3. Payload 包含: toolName, toolCallId, duration, result/error

### M6-T2C 架构是否支持 M6-T2B?

**评估: ✅ 完全支持**

**M6-T2B 实现路径 (使用 M6-T2C 架构):**

```java
// 在 EvidenceCapturingToolCallback (或新的 wrapper):
public class EventEmittingToolCallback implements ToolCallback {
    
    private final ToolCallback delegate;
    private final ExecutionEventDispatcher dispatcher;
    private final String processId;
    
    @Override
    public String call(ToolContext context) {
        String toolName = delegate.getName();
        String toolCallId = context.getToolCallId();
        long startTime = System.currentTimeMillis();
        
        try {
            // 执行工具
            String result = delegate.call(context);
            
            // 工具执行成功 → 域事实为真
            long duration = System.currentTimeMillis() - startTime;
            dispatcher.publish(new ExecutionEvent(
                processId,
                EventType.TOOL_EXECUTED,
                null, // tool execution 不一定在 checkpointed state
                """
                {"toolName": "%s", "toolCallId": "%s", "duration": %d}
                """.formatted(toolName, toolCallId, duration)
            ));
            
            return result;
            
        } catch (Exception e) {
            // 工具执行失败 → 域事实为真
            long duration = System.currentTimeMillis() - startTime;
            dispatcher.publish(new ExecutionEvent(
                processId,
                EventType.TOOL_FAILED,
                null,
                """
                {"toolName": "%s", "toolCallId": "%s", "duration": %d, "error": "%s"}
                """.formatted(toolName, toolCallId, duration, e.getMessage())
            ));
            
            throw e;
        }
    }
}
```

**关键点:**
1. ✅ 使用相同的 `ExecutionEventDispatcher`
2. ✅ 使用相同的 `ExecutionEvent`
3. ✅ `EventType.TOOL_EXECUTED` / `TOOL_FAILED` 已经存在 (M6-T1)
4. ✅ ExecutionLedger 自动接收 tool events (通过 ExecutionLedgerListener)
5. ✅ 未来 Metrics/Logging listeners 自动接收 tool events
6. ✅ **零需要修改 Engine lifecycle event 发射逻辑**

### M6-T2B 不需要做什么

**不需要:**
- ❌ 修改 SpringAiToolCallingEngine 的 lifecycle 事件发射点
- ❌ 添加新的 ExecutionLedger 调用
- ❌ 修改 ExecutionEventDispatcher
- ❌ 修改 ExecutionEventListener 接口
- ❌ 修改 ExecutionEvent record

**只需要:**
- ✅ 创建 EventEmittingToolCallback wrapper (或修改现有 wrapper)
- ✅ 传递 dispatcher 到 wrapper
- ✅ 在工具成功/失败后发射事件

### M6-T2C 为 M6-T2B 提供的价值

1. **统一发射点:** Tool events 和 lifecycle events 使用相同机制
2. **统一投影:** ExecutionLedger 自动接收所有事件
3. **统一扩展:** 未来 observability listeners 同时接收 lifecycle + tool events
4. **零 Engine 修改:** M6-T2B 只需要修改 tool wrapper,不需要修改 Engine

**结论: M6-T2C 架构完全支持 M6-T2B,且提供统一的事件发射/投影机制。**

---

## O. Deferred Capabilities

### 明确 M6-T2C 不实现的能力

M6-T2C 是**最小化职责提取**,不是平台化事件系统。

**不实现 (future):**

#### 1. EventBus / EventCenter 平台
- ❌ 中央事件总线
- ❌ Topic / channel / routing key
- ❌ Event subscription management
- ❌ Dynamic listener registration/unregistration
- **理由:** 无需求,过度设计

#### 2. 异步派发
- ❌ 后台线程
- ❌ Executor / thread pool
- ❌ Queue / buffer
- ❌ Reactive event stream (Reactor, RxJava)
- **理由:** 同步语义足够,异步增加复杂性

#### 3. Retry / Reliability
- ❌ Listener retry policy
- ❌ Dead-letter queue
- ❌ Transactional outbox pattern
- ❌ At-least-once / exactly-once delivery
- **理由:** 投影失败不影响域逻辑,无需 retry

#### 4. Event Sourcing
- ❌ Event store as recovery authority
- ❌ State reconstruction from events
- ❌ Replay semantics
- ❌ Event versioning / upcasting
- **理由:** Checkpoint 仍是恢复 authority (M6 invariant)

#### 5. Listener Failure Policy
- ❌ `ListenerFailurePolicy` (STRICT / RELAXED / FAIL_FAST)
- ❌ `FailureStrategy` SPI
- ❌ Configurable failure handling
- **理由:** 当前隔离语义足够,无需配置

#### 6. Event Filtering / Routing
- ❌ Event filters (predicate-based)
- ❌ Routing rules (event type → specific listeners)
- ❌ Priority / ordering control
- **理由:** 所有 listeners 接收所有 events,无需 filtering

#### 7. Event ID / Ordering
- ❌ Event ID allocation (dispatcher-level)
- ❌ Event sequence (独立于 Ledger sequence)
- ❌ Causal ordering / vector clocks
- **理由:** Ledger sequence 仍是唯一 ordering authority

#### 8. 分布式事件
- ❌ Remote event transport (Kafka, RabbitMQ)
- ❌ Event serialization
- ❌ Cross-JVM event delivery
- ❌ Event persistence (独立于 Ledger)
- **理由:** M6-T2C 仅进程内职责提取

#### 9. Observability 实现
- ❌ LoggingExecutionEventListener 实现
- ❌ MetricsExecutionEventListener 实现
- ❌ TracingExecutionEventListener 实现
- ❌ SLF4J / Micrometer / OpenTelemetry 依赖
- **理由:** M6-T2C 只提供扩展点,不实现具体 listeners

#### 10. Listener Lifecycle
- ❌ Listener initialization / cleanup
- ❌ Listener health check
- ❌ Listener state management
- **理由:** Listeners 是无状态功能接口

#### 11. Event Batching
- ❌ Batch event delivery
- ❌ Flush policies
- ❌ Buffering
- **理由:** 同步立即派发足够

#### 12. Event Schema / Validation
- ❌ Event schema registry
- ❌ Payload validation (beyond null check)
- ❌ JSON schema enforcement
- **理由:** Payload 是 opaque string,由调用者保证格式

### YAGNI 原则应用

**当前有需求 (实现):**
- ✅ 移除 Engine 的重复 ledger 投影代码
- ✅ 允许未来添加 logging/metrics/tracing listeners
- ✅ 隔离 listener 失败

**当前无需求 (不实现):**
- ❌ 异步派发 (无并发需求)
- ❌ Retry (投影失败不影响域逻辑)
- ❌ Filtering (所有 listeners 接收所有 events)
- ❌ Event sourcing (Checkpoint 仍是 recovery authority)
- ❌ 分布式事件 (仅进程内职责提取)

**如果未来需要,可以添加的扩展点:**
- `ExecutionEventDispatcher` 可以演进为 interface (当前 final class)
- 可以添加 `AsyncExecutionEventDispatcher` 实现
- 可以添加 `FilteringExecutionEventListener` wrapper
- **但现在不需要,不创建**

---

## P. Risk Review

### 1. 重复 Authority 风险

**风险: ExecutionEvent 是否会成为第二个 recovery authority?**

**缓解:**
- ✅ ExecutionEvent Javadoc 明确: "已经为真的域事实的发射通知"
- ✅ ExecutionEvent 不包含 sequence (sequence 仍由 Ledger 分配)
- ✅ ExecutionEvent 不包含 recordId (recordId 由 Ledger 派生)
- ✅ ExecutionEvent 不包含 occurredAt (timestamp 由 Ledger 分配)
- ✅ M6 architecture 文档明确: "Checkpoint 是 recovery authority, Ledger 是 historical authority"
- ✅ ExecutionEvent 不改变这个不变式

**验证点:**
- Checkpoint 仍是 CHECK A / CHECK B 的唯一 authority
- Ledger sequence 仍是 historical ordering 的唯一 authority
- ExecutionEvent 只是发射通知,不是存储/恢复/排序 authority

### 2. 意外 Event Sourcing 风险

**风险: 是否会滑向 event-sourced runtime?**

**缓解:**
- ✅ ExecutionEvent 明确不是 recovery mechanism
- ✅ Checkpoint 仍是唯一恢复入口
- ✅ 无 replay / state reconstruction from events
- ✅ 无 "从事件重建 checkpoint" 逻辑
- ✅ Ledger 仍是 query/audit only,不是 recovery

**文档保护:**
```java
/**
 * ExecutionEvent 是域事实的发射通知,不是恢复状态的来源.
 * 
 * Checkpoint 仍是恢复 authority.
 * ExecutionLedger 仍是历史审计 authority.
 * 
 * 禁止从 ExecutionEvent history 推断当前可恢复状态.
 */
```

### 3. Public API 风险

**风险: ExecutionEvent / ExecutionEventListener 过早公开?**

**评估:**
- ✅ 真实用例存在: logging, metrics, tracing, audit
- ✅ 简单的契约: 1 record, 1 functional interface
- ✅ 低维护成本
- ⚠️ 如果未来需要改变 ExecutionEvent 字段?

**缓解:**
- ✅ ExecutionEvent 是 record - 天然不可变
- ✅ 如果需要添加字段,可以添加新构造器 (record canonical + custom)
- ✅ 如果需要演进,可以创建 ExecutionEventV2
- ✅ Listener 是功能接口 - 稳定契约

**决策:** 风险可接受,公开是合理的

### 4. Exception Semantic 风险

**风险: Listener 异常隔离是否正确?**

**场景分析:**

**场景 1: Ledger append 失败**
```java
// BEFORE (M6-T2A):
try {
    executionLedger.append(...);
} catch (Exception e) {
    // Silent - domain fact remains true
}

// AFTER (M6-T2C):
// Dispatcher calls listener:
try {
    listener.onEvent(event);  // → ledger.append() throws
} catch (Exception e) {
    // Silent - domain fact remains true
}

// ✅ 语义保持一致
```

**场景 2: 多个 listeners,一个失败**
```java
var listeners = List.of(
    new ExecutionLedgerListener(ledger),  // 假设这个失败
    new MetricsListener()                  // 这个应该仍然运行
);

// ✅ Dispatcher 隔离失败,MetricsListener 仍然接收事件
// ✅ 这是期望的行为 (one projection failure doesn't suppress others)
```

**场景 3: Dispatcher.publish() 从不抛出异常**
```java
// Engine 调用:
dispatcher.publish(event);  // 从不抛出 (所有 listener 异常被隔离)

// ✅ Engine 不需要 try/catch
// ✅ 域逻辑继续执行
```

**结论: 异常语义正确,无风险**

### 5. Ordering 风险

**风险: Listener 调用顺序是否重要?**

**当前设计:**
```java
for (var listener : listeners) {
    try {
        listener.onEvent(event);
    } catch (Exception e) { }
}

// Listeners 按注册顺序调用
```

**场景分析:**

**是否依赖顺序?**
- ExecutionLedgerListener: 不依赖其他 listeners
- Future LoggingListener: 不依赖其他 listeners
- Future MetricsListener: 不依赖其他 listeners
- Future TracingListener: 不依赖其他 listeners

**✅ 当前无顺序依赖**

**未来如果需要顺序:**
- 可以添加 `@Order` annotation
- 可以添加 priority 机制
- **但现在不需要,不创建**

**结论: 当前无风险,未来可扩展**

### 6. 性能风险

**风险: 同步调用所有 listeners 是否影响性能?**

**评估:**

**当前 listeners:**
- ExecutionLedgerListener: InMemoryExecutionLedger.append() - O(1) ConcurrentHashMap.put()
- 估计耗时: <1ms

**未来 listeners:**
- LoggingListener: SLF4J log call - 通常 <1ms (async appender)
- MetricsListener: Micrometer counter.increment() - <1ms
- TracingListener: Span creation - <1ms

**总耗时估算:**
- 4 listeners × 1ms = ~4ms overhead per event
- 10 lifecycle events per process = ~40ms overhead per process execution

**对比:**
- 典型 Agent execution: 1-10 seconds (model calls)
- 40ms overhead = **< 1% of total execution time**

**结论: 性能风险极低,同步调用可接受**

**如果未来需要优化:**
- 可以实现 `AsyncExecutionEventDispatcher`
- 但当前无需求

### 总结风险评估

| 风险 | 严重性 | 可能性 | 缓解措施 | 状态 |
|-----|--------|--------|---------|------|
| 重复 Authority | 高 | 低 | Javadoc + Architecture 文档 | ✅ 已缓解 |
| 意外 Event Sourcing | 高 | 低 | 明确文档禁止 | ✅ 已缓解 |
| Public API 过早 | 中 | 中 | Record 不可变 + 真实用例 | ✅ 可接受 |
| Exception Semantic | 中 | 低 | 保持 M6-T2A 语义 | ✅ 已验证 |
| Ordering 依赖 | 低 | 低 | 当前无依赖 | ✅ 无风险 |
| 性能影响 | 低 | 低 | <1% overhead | ✅ 可接受 |

---

## Q. Final Decision

### 决策: **GO — 实施 M6-T2C**

### 理由

#### 1. 具体的 Engine 简化证据

**量化改进:**
- 代码行数: ~200 lines → ~60 lines (**-70%**)
- null checks: 10 → 0 (**-100%**)
- try/catch blocks: 10 → 0 (**-100%**)
- ExecutionLedger 耦合: 直接依赖 → 完全解耦
- 未来 observability 扩展: 修改 Engine → 添加 Listener (零 Engine 修改)

**职责分离:**
- BEFORE: Engine = execution + ledger projection + exception handling
- AFTER: Engine = execution + domain fact emission | Dispatcher = projection + exception isolation

#### 2. 真实的未来扩展需求

**已识别的扩展点:**
- M6-T2B: TOOL_EXECUTED / TOOL_FAILED (即将实施)
- Future: LoggingExecutionEventListener
- Future: MetricsExecutionEventListener
- Future: TracingExecutionEventListener
- Future: AuditExecutionEventListener (独立于 Ledger)

**不是猜测,是已规划的工作**

#### 3. M6-T2A 发现的重复模式

**M6-T2A 实验完成:**
- 10 个 lifecycle event 发射点已识别
- 每个发射点的重复模式已验证
- 提取时机已成熟 (不是过早抽象)

#### 4. 最小化设计

**遵循 YAGNI:**
- ✅ 只有 4 个新类型 (Event, Listener, Dispatcher, LedgerListener)
- ✅ 同步,进程内,无异步/队列/retry
- ✅ 2 个 public API (Event, Listener) - 有真实用例
- ✅ 2 个 package-private (Dispatcher, LedgerListener) - 内部实现
- ✅ 无 EventBus / topics / routing / filtering / priorities

#### 5. 低风险

**风险评估:**
- 重复 authority: 已缓解 (Javadoc + 文档)
- Event sourcing: 已缓解 (明确禁止)
- Public API: 可接受 (真实用例 + 简单契约)
- Exception semantic: 已验证 (保持 M6-T2A 语义)
- Performance: 可接受 (<1% overhead)

#### 6. M5 Regression 保护

**兼容性:**
- ✅ M4 ephemeral 构造器保留
- ✅ M6-T2A ExecutionLedger 构造器保留 (@Deprecated,适配到新 API)
- ✅ 所有 M5 durable tests 继续通过 (无语义变更)

### 实施范围

**GO 的具体内容:**

#### Phase 1: Core Abstractions (arctra-core)
1. 创建 `ExecutionEvent` record (public)
2. 创建 `ExecutionEventListener` interface (public)
3. 单元测试

#### Phase 2: Dispatcher Implementation (arctra-runtime-react)
1. 创建 `ExecutionEventDispatcher` class (package-private)
2. 创建 `ExecutionLedgerListener` class (package-private)
3. 单元测试 (隔离失败,顺序调用)

#### Phase 3: Engine Integration
1. 修改 `SpringAiToolCallingEngine`:
   - 添加新 8-param 构造器 (with Dispatcher)
   - Deprecate 旧 8-param 构造器 (with ExecutionLedger)
   - 保留 4-param 构造器 (ephemeral)
   - 替换 10 个发射点 (ledger.append → dispatcher.publish)
   - 可选: 添加 `emitEvent()` helper 方法

#### Phase 4: Testing
1. 修改 M6-T2A lifecycle tests (适配新构造器)
2. 验证 M5 regression (249 tests 继续通过)
3. 添加新测试: multiple listeners, listener failure isolation

#### Phase 5: Documentation
1. Javadoc for ExecutionEvent / ExecutionEventListener
2. 更新 CURRENT-STATE.md
3. 更新 M6 architecture 文档
4. 迁移指南 (M6-T2A → M6-T2C)

### 实施计划

**预估时间: 1 天**

- Phase 1: 2 小时
- Phase 2: 2 小时
- Phase 3: 3 小时
- Phase 4: 1 小时
- Phase 5: 1 小时

**Definition of Done:**
- ✅ 4 个新类型实现
- ✅ SpringAiToolCallingEngine 10 个发射点替换
- ✅ M5 regression tests 通过 (249 tests)
- ✅ M6-T2A lifecycle tests 通过 (11 tests)
- ✅ 新 dispatcher tests 通过
- ✅ `./mvnw clean verify` 通过
- ✅ Javadoc 完整
- ✅ Architecture 文档更新
- ✅ 迁移指南完成

### 不包含在 GO 范围内

**明确不做 (defer to future):**
- ❌ LoggingExecutionEventListener 实现
- ❌ MetricsExecutionEventListener 实现
- ❌ TracingExecutionEventListener 实现
- ❌ M6-T2B (TOOL_EXECUTED / TOOL_FAILED wiring)
- ❌ 异步 dispatcher
- ❌ Retry / dead-letter / transactional outbox
- ❌ Event filtering / routing
- ❌ Listener lifecycle management

---

## Summary

**M6-T2C GO Decision 最终总结:**

1. **✅ Engine 简化证据充分** - ~200 lines → ~60 lines, 10× 重复模式消除
2. **✅ 未来扩展需求真实** - M6-T2B, logging, metrics, tracing 已规划
3. **✅ 最小化设计** - 4 个类型, 2 public, 无过度抽象
4. **✅ 低风险** - 风险已识别并缓解
5. **✅ M5 兼容** - 构造器兼容性保留
6. **✅ 实施范围清晰** - 1 天工作,明确 DoD

**开始实施 M6-T2C.**

---

**报告结束**
