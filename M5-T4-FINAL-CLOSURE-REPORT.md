# Arctra M5-T4 Final Closure Report

**日期:** 2026-09-09

**状态:** ✅ **M5-T4 FINAL: GO**

---

## A. Executive Summary

### 最终决定

✅ **M5-T4 durable suspension/recovery capability is functionally complete and validated for the defined M5 scope.**

### 交付成果

M5-T4 实现了完整的持久暂停/恢复能力：

1. **Checkpoint-backed durable suspension** — 基于 checkpoint 的持久暂停
2. **Cross-runtime/JVM recovery** — 跨运行时/JVM 恢复
3. **Unified resume pipeline (CHECK A/B)** — 统一恢复管道
4. **Local handle lifecycle** — 本地句柄生命周期
5. **Memory/Evidence/Governance continuity** — 三大系统连续性
6. **Concurrency contracts** — 并发契约

### 实施阶段

| Phase | 状态 | 描述 |
|-------|------|------|
| Phase 1 | ✅ GO | CheckpointStore + RuntimeBinding |
| Phase 2 | ✅ GO | Core Durable Capability |
| Phase 3 | ✅ GO | AgentRuntime Durable Entry |
| Phase 4 | ✅ GO | Initial Durable Suspension |
| Phase 5.1 | ❌ NO-GO | Evidence duplication bug found |
| Phase 5.2 | ✅ GO | Bug fixed, complete coverage |
| Phase 6 | ✅ GO | Local handle exception lifecycle |
| Phase 7 | ✅ GO | Cross-runtime recovery E2E |
| Phase 8 | ✅ GO | Concurrency & conflict semantics |
| Phase 9 | ✅ GO | Memory/Evidence/Governance regression |
| Phase 10 | ✅ GO | Final closure & release gate |

### 最终测试结果

```
mvn clean test: BUILD SUCCESS (9.347s)
mvn verify: BUILD SUCCESS (8.445s)

Core: 136 tests, 0 failures
Runtime-react: 79 tests, 0 failures, 6 skipped
Examples: 22 tests, 0 failures, 9 skipped
Total: 237 tests, 0 failures
```

### M5 范围声明

**M5 提供:**
- 单 JVM 持久恢复验证
- InMemoryCheckpointStore 参考实现
- 跨独立运行时实例恢复

**M5 不提供:**
- 生产分布式持久性
- 恰好一次工具执行保证
- 原子 checkpoint/ChatMemory 事务

---

## B. Final Architecture

### 架构图

```
Application
   │
   ▼
AgentRuntime
   │
   ├── execute()
   │      ▼
   │   AgentExecutionEngine (ephemeral/durable)
   │
   └── resumeProcess()  (durable only)
          ▼
      DurableExecutionEngine
          ▼
   SpringAiToolCallingEngine
          │
          ├─ CheckpointStore (CHECK A/B)
          ├─ RuntimeBindingResolver
          ├─ ChatMemory
          ├─ ToolGovernancePolicy
          ├─ Tools (List<ToolCallback>)
          └─ ChatModel
```

### 本地句柄路径

```
AgentProcess.resume()
   ▼
ResumeStrategy (Durable/Ephemeral)
   ▼
DurableExecutionEngine.resumeProcess()
```

### 跨运行时路径

```
AgentRuntime.resumeProcess(processId, version, signal)
   ▼
DurableExecutionEngine.resumeProcess()
   ▼
SpringAiToolCallingEngine (CHECK A → execute → CHECK B)
```

### 关键原则

1. **单一持久恢复入口:** `DurableExecutionEngine.resumeProcess()`
2. **核心框架中立:** arctra-core 不依赖 Spring AI
3. **模块边界清晰:** runtime-react → core (单向)
4. **M4 兼容性:** Ephemeral 路径完全保留

---

## C. Module Boundaries

### 最终依赖方向

```
arctra-runtime-react → arctra-core ✅
arctra-core → arctra-runtime-react ❌ (forbidden, verified)
arctra-core → org.springframework.ai ❌ (forbidden, verified)
```

### 验证结果

```bash
$ grep -R "org.springframework.ai" arctra-core/src/main/java
(no matches) ✅

$ grep -R "cn.bitcss.arctra.runtime.react" arctra-core/src/main/java
(no matches) ✅
```

### 模块职责

**arctra-core:**
- 框架中立的持久抽象
- CheckpointStore / RuntimeBinding
- AgentProcess / ResumeStrategy
- DurableExecutionEngine 接口

**arctra-runtime-react:**
- Spring AI 集成
- SpringAiToolCallingEngine (实现 DurableExecutionEngine)
- ProtocolReconstructor
- GovernanceToolCallingAdvisor / EvidenceCapturingToolCallback

---

## D. Public API Delta

### 新增公共类型

**Interfaces:**
1. `DurableExecutionEngine` — 持久引擎能力接口
2. `RuntimeBindingResolver` — 运行时绑定解析器
3. `CheckpointStore` — Checkpoint 存储抽象

**Records:**
4. `RuntimeBinding(AgentDefinition, AgentExecutionContext)` — 运行时绑定
5. `SuspensionCheckpoint` — 暂停 checkpoint

**Reference Implementation:**
6. `InMemoryCheckpointStore` — 内存 checkpoint 存储

### 新增公共方法

**AgentRuntime:**
```java
AgentResult resumeProcess(
    String processId, 
    long checkpointVersion, 
    ContinuationSignal signal
)
```

**DurableExecutionEngine:**
```java
AgentResult resumeProcess(
    String processId,
    long checkpointVersion,
    ContinuationSignal signal
)
```

**ProcessFactory:**
```java
static AgentProcess createDurableSuspended(
    String id,
    String runtimeBindingKey,
    ResumeStrategy strategy
)
```

### 新增异常

1. `CheckpointNotFoundException` — Checkpoint 不存在
2. `StaleCheckpointException` — Checkpoint 版本过时
3. `CheckpointTransitionConflictException` — CHECK B 冲突
4. `CheckpointAlreadyExistsException` — Checkpoint 已存在
5. `ResumePreparationException` — 恢复准备失败
6. `RuntimeBindingException` — 运行时绑定失败

### DurableExecutionEngine 最终签名

```java
public interface DurableExecutionEngine extends AgentExecutionEngine {
  AgentResult resumeProcess(
      String processId,
      long checkpointVersion,
      ContinuationSignal signal
  );
}
```

**验证:**
✅ 不包含 Spring 类型
✅ 不包含 CheckpointStore 参数
✅ 不包含 RuntimeBinding 参数
✅ 仅包含核心抽象

### RuntimeBinding 最终形态

```java
public record RuntimeBinding(
    AgentDefinition definition,
    AgentExecutionContext context
) { }
```

**验证:**
✅ 无引擎权威
✅ 仅包含定义 + 上下文

### DefaultAgentRuntime 最终实现

```java
public class DefaultAgentRuntime implements AgentRuntime {
  private final AgentExecutionEngine engine;  // 仅持有引擎
  
  @Override
  public AgentResult resumeProcess(...) {
    if (!(engine instanceof DurableExecutionEngine durable)) {
      throw new UnsupportedOperationException(...);
    }
    return durable.resumeProcess(...);
  }
}
```

**验证:**
✅ 无持久配置重复
✅ 仅持有引擎引用

---

## E. Durable Suspension

### 初始暂停流程

```
1. SpringAiToolCallingEngine.execute()
2. Model emits ToolCalls
3. Governance evaluates → REQUIRE_APPROVAL
4. Build SuspensionCheckpoint v1:
   - processId (UUID)
   - checkpointVersion = 1
   - runtimeBindingKey (from engine config)
   - sessionId
   - pendingBatch (approved ToolCalls)
   - accumulatedEvidences ([])
5. CheckpointStore.create(checkpoint)
6. Return suspended AgentResult with AgentProcess
```

### Checkpoint 内容

```java
SuspensionCheckpoint(
  schemaVersion,
  processId,          // 稳定标识
  checkpointVersion,  // 单调递增
  runtimeBindingKey,  // 逻辑绑定标识
  sessionId,          // ChatMemory 会话
  pendingBatch,       // 待执行的工具调用
  accumulatedEvidences // 历史证据
)
```

### ProcessFactory

```java
ProcessFactory.createDurableSuspended(
  processId,
  runtimeBindingKey,
  new DurableResumeStrategy(
    processId,
    checkpointVersion,
    engine
  )
)
```

---

## F. Durable Resume Pipeline

### 统一恢复管道

**唯一入口:** `SpringAiToolCallingEngine.resumeProcess(processId, version, signal)`

### CHECK A: 加载和验证

```
1. Load checkpoint from store
   → CheckpointNotFoundException if missing
   
2. Verify version match
   → StaleCheckpointException if mismatch
   
3. Resolve RuntimeBinding
   → ResumePreparationException if resolution fails
   
4. Reconstruct protocol (AssistantMessage with stored toolCalls)
   
5. Prepare execution context (tools, model, governance)
```

**关键:** CHECK A 在外部副作用前完成

### 执行阶段

```
6. Governance boundary:
   - Stored pending batch: NOT re-governed
   - Execute approved tools directly
   
7. Capture new Evidence
   
8. Call model with reconstructed protocol + tool results
   
9. Model response:
   - New approval-required ToolCalls → re-suspend
   - Completion → prepare CHECK B delete
```

### CHECK B: 条件转换

**完成路径:**
```
deleteIfVersion(processId, checkpointVersion)
→ true: persist final Assistant, return COMPLETED
→ false: CheckpointTransitionConflictException
```

**重新暂停路径:**
```
Build checkpoint vN+1 with:
- same processId
- checkpointVersion + 1
- same runtimeBindingKey  
- same sessionId
- new pendingBatch
- merged Evidence (historical + new)

replaceIfVersion(processId, vN, checkpoint vN+1)
→ true: create WAITING process, return suspended
→ false: CheckpointTransitionConflictException
```

### 恢复路径

**本地句柄:**
```
AgentProcess.resume(signal)
→ DurableResumeStrategy.prepare()
→ ResumeAttempt
→ engine.resumeProcess(processId, version, signal)
```

**跨运行时:**
```
AgentRuntime.resumeProcess(processId, version, signal)
→ (cast to DurableExecutionEngine)
→ engine.resumeProcess(processId, version, signal)
```

**两者收敛于同一后端方法。**

---

## G. CHECK A / CHECK B Semantics

### CHECK A 语义

**目的:** 在外部副作用前验证恢复可行性

**操作:**
1. Load checkpoint (may fail: not found)
2. Version check (may fail: stale)
3. Resolve binding (may fail: resolution error)

**失败行为:**
- 所有 CHECK A 失败在副作用前抛出异常
- 零工具执行
- 零模型调用
- 零 ChatMemory 写入

**验证测试:**
- `staleResumeAfterRace_rejectedBeforeSideEffects`
- `checkpointNotFoundException_failed`
- Phase 7 各种场景

### CHECK B 语义

**目的:** 原子条件 checkpoint 转换

**完成 CHECK B:**
```java
boolean deleted = store.deleteIfVersion(processId, checkpointVersion);
if (!deleted) {
  throw new CheckpointTransitionConflictException(...);
}
// 成功：持久化最终 Assistant
```

**重新暂停 CHECK B:**
```java
boolean replaced = store.replaceIfVersion(
  processId, 
  oldVersion, 
  newCheckpoint
);
if (!replaced) {
  throw new CheckpointTransitionConflictException(...);
}
// 成功：返回 WAITING process
```

**冲突处理:**
- 失败者抛出 CheckpointTransitionConflictException
- 失败者不修改 checkpoint
- 失败者不写入 ChatMemory

**验证测试:**
- `crossRuntimeCompletionRace_oneWinnerOneConflict`
- `crossRuntimeReSuspensionRace_oneWinnerOneConflict`

---

## H. RuntimeBinding / Binding Key Semantics

### RuntimeBindingKey 语义

**含义:** 逻辑 agent 配置/绑定标识，**不是** 物理运行时实例 ID

**生命周期:**
```
初始暂停: engine.runtimeBindingKey → checkpoint.runtimeBindingKey
恢复解析: resolver.resolve(checkpoint.runtimeBindingKey)
重新暂停: 保留 oldCheckpoint.runtimeBindingKey
```

**稳定性:**
```
CP v1: runtimeBindingKey = "incident-agent"
CP v2: runtimeBindingKey = "incident-agent"  (保留)
CP v3: runtimeBindingKey = "incident-agent"  (保留)
```

### 跨运行时绑定密钥验证

**Phase 7 测试:**
```
Runtime A 引擎 key: "incident-agent"
Runtime B 引擎 key: "runtime-B"      (故意不同)
Runtime C 引擎 key: "runtime-C"      (又不同)

Checkpoint v1: runtimeBindingKey = "incident-agent"
Checkpoint v2: runtimeBindingKey = "incident-agent"

Resolver B 接收: "incident-agent"  ✅ (不是 "runtime-B")
Resolver C 接收: "incident-agent"  ✅ (不是 "runtime-C")
```

**关键证明:** 绑定密钥是逻辑的，跨物理运行时实例保留

### RuntimeBinding 内容

```java
RuntimeBinding(
  AgentDefinition definition,  // agent 身份/配置
  AgentExecutionContext context  // 会话/元数据
)
```

**不包含:**
- ❌ 引擎权威
- ❌ CheckpointStore
- ❌ Tools/Model/Governance

**执行权威:** 属于调用的 DurableExecutionEngine，不是 binding

---

## I. Process Identity

### 三层身份模型

**1. 逻辑进程身份:**
```
processId: String (UUID, 稳定)
```

**2. 暂停情节身份:**
```
(processId, checkpointVersion): (String, long)
```

**3. 本地对象身份:**
```
AgentProcess Java 对象实例
```

### 身份不变量

**processId 稳定性:**
```
初始暂停: processId = "abc-123"
CP v1: processId = "abc-123"
CP v2: processId = "abc-123"  (不变)
完成: processId = "abc-123"  (不变)
```

**version 单调性:**
```
CP v1: checkpointVersion = 1
CP v2: checkpointVersion = 2  (+1)
CP v3: checkpointVersion = 3  (+1)
```

**本地句柄可能过时:**
```
Runtime A 句柄: (processId, v1)
Runtime B 推进: v1 → v2
Runtime A 句柄: 现在过时 (StaleCheckpointException)
```

### 跨运行时恢复

**不需要:**
- ❌ 原始 Java AgentProcess 句柄
- ❌ 原始运行时实例
- ❌ 原始引擎实例

**仅需要:**
- ✅ processId
- ✅ checkpointVersion
- ✅ 共享 CheckpointStore
- ✅ 新运行时实例 (独立资源)

---

## J. Local Handle Lifecycle

### 最终异常策略

```java
ResumePreparationException         → WAITING (可重试)
StaleCheckpointException          → FAILED (终态)
CheckpointNotFoundException       → FAILED (终态)
CheckpointTransitionConflictException → FAILED (终态)
Tool/Model/Backend 失败            → FAILED (终态)
```

### 成功转换

```java
成功完成                          → COMPLETED
成功重新暂停                       → WAITING (采用新策略)
```

### 本地 FAILED 语义

**关键区别:**
```
本地 FAILED = 此本地句柄不可用
         ≠ 全局持久进程失败
```

**Checkpoint 是权威的:**
- 本地句柄可能 FAILED
- 但全局 checkpoint 可能已在其他地方推进/完成

### Phase 6 验证

**测试覆盖:**
- 11 个异常生命周期测试
- 每个异常类型的状态转换
- M4 回归 (ephemeral 失败、并发、重新暂停)

---

## K. Memory Semantics

### ChatMemory 生命周期

```
初始执行:        H + U
初始暂停:        H + U  (无最终 A)
重新暂停:        H + U  (仍无最终 A)
完成:           H + U + 最终 A  (恰好一次)
```

### 关键不变量

1. **用户消息不重复**
   - 暂停/恢复期间 userCount 不变

2. **最终 Assistant 仅在完成时**
   - 暂停/重新暂停: 无最终 A
   - 完成: 最终 A 出现

3. **最终 Assistant 恰好一次**
   - `finalAnswerCount == 1`

4. **跨运行时连续性**
   - 相同 sessionId 贯穿
   - 共享 ChatMemory 保留历史

### 冲突内存行为

**Phase 8 验证:**
```
两个运行时并发完成:
→ 一个赢家写入最终 Assistant
→ 一个失败者抛出冲突 (不写入)

assistantMessageCount == 1  ✅
```

### 协议消息

**当前设计:**
- 协议重建在恢复期间可能添加中间消息
- 关键：无重复逻辑用户/最终 assistant 消息

### Phase 9 验证

**测试覆盖:**
- 完整生命周期 Memory 断言
- 每个阶段的 userCount/assistantCount
- 最终 Assistant 内容验证

---

## L. Evidence Semantics

### Evidence 所有权

```
historical = checkpoint.accumulatedEvidences
new = 此次恢复捕获的 Evidence
merged = historical + new (恰好一次)
```

### 单调累积

```
CP v1: []
CP v2: [A]
CP v3: [A, B]
完成: [A, B, C]
```

### 无重复

```java
aCount == 1  ✅
bCount == 1  ✅
cCount == 1  ✅
```

### Phase 5.2 Bug 修复

**问题:** ProtocolReconstructor 行 99 将历史 evidence 复制到新 sink

**修复:** 删除行 99

**验证:** 20/20 invariants, 19 new tests

### 拒绝语义

```
Tool A rejected
→ toolAExecutionCount == 0  ✅
→ toolAEvidenceCount == 0  ✅
```

**原则:** 拒绝的工具不创建执行 Evidence

### Phase 9 验证

**测试覆盖:**
- 完整生命周期 Evidence 累积
- 三集 A→B→C
- 拒绝场景

---

## M. Governance Semantics

### 治理边界

```
存储的待处理批次: 已治理 → 不重新治理
新模型发出的 ToolCalls: 正常治理
```

### 治理计数

```
Tool A:
  初始执行: 治理 1 次
  Resume v1: 不重新治理 (仍是 1)
  
Tool B:
  Resume v1: 治理 1 次 (新 ToolCall)
  Resume v2: 不重新治理 (仍是 1)
```

### 最终计数

```java
toolAGovernanceCount == 1  ✅ (不是 2)
toolBGovernanceCount == 1  ✅ (不是 2)
```

### 治理流程

```
1. 初始执行:
   Model emits Tool A
   → Governance evaluates
   → REQUIRE_APPROVAL
   → Suspend

2. Resume APPROVED:
   Stored Tool A
   → NOT re-governed  ✅
   → Execute directly
   
3. Model emits Tool B:
   New ToolCall
   → Governance evaluates  ✅
   → REQUIRE_APPROVAL
   → Re-suspend
```

### Phase 9 验证

**测试覆盖:**
- 完整生命周期治理计数跟踪
- 每个工具的治理调用精确断言
- 边界验证 (旧不重新治理，新治理)

---

## N. Cross-Runtime Recovery

### A → B → C E2E 流程

**Phase 7 验证:**

```
Runtime A (key="incident-agent"):
  execute → Tool X requires approval → CP v1

[Runtime A 消失]

Runtime B (key="runtime-B"):
  resumeProcess(P, 1L, APPROVED)
  → execute Tool X (Runtime B 工具实例)
  → Tool Y requires approval → CP v2

[Runtime B 消失]

Runtime C (key="runtime-C"):
  resumeProcess(P, 2L, APPROVED)
  → execute Tool Y (Runtime C 工具实例)
  → complete → checkpoint deleted
```

### 资源独立性

**每个运行时有自己的:**
- ✅ ChatModel 实例
- ✅ ToolCallback 实例
- ✅ ToolGovernancePolicy 实例
- ✅ RuntimeBindingResolver 实例
- ✅ SpringAiToolCallingEngine 实例

**共享:**
- ✅ CheckpointStore
- ✅ ChatMemory

### 资源权威证明

**工具执行:**
```
Tool X:
  Runtime A 实例: 0  (未执行，已消失)
  Runtime B 实例: 1  ✅ (执行)

Tool Y:
  Runtime B 实例: 0  (未执行，已消失)
  Runtime C 实例: 1  ✅ (执行)
```

**解析器调用:**
```
Resolver A: 0  (初始暂停不调用)
Resolver B: 1  ✅ (接收 "incident-agent")
Resolver C: 1  ✅ (接收 "incident-agent")
```

### 不变量保留

```
processId: 稳定贯穿 A→B→C
runtimeBindingKey: "incident-agent" 保留
sessionId: "session-123" 保留
checkpointVersion: 1 → 2 → deleted
Evidence: [] → [X] → [X,Y] (无重复)
```

### 无原始句柄要求

**关键证明:**
- Runtime B/C 恢复不使用原始 AgentProcess 句柄
- 仅使用 `processId + checkpointVersion`
- 共享基础设施 (store + memory)

---

## O. Concurrency Contract

### 两个不同的并发域

**Domain A: 同本地 AgentProcess**

```
同一 Java 对象实例
多个线程调用 process.resume()

保证:
→ 本地 CAS (WAITING → RUNNING)
→ 只有一个后端执行
→ 其他线程在 CAS 处被拒绝
```

**验证:**
```java
3 个线程并发 resume:
successCount == 1  ✅
rejectedCount == 2  ✅
backendResumeCount == 1  ✅
```

**Domain B: 跨运行时恢复**

```
Runtime A.resumeProcess(P, vN)
Runtime B.resumeProcess(P, vN)

两者都可能:
→ 通过 CHECK A
→ 执行工具
→ 调用模型

只有一个:
→ 赢得 CHECK B

失败者:
→ CheckpointTransitionConflictException
```

**验证:**
```java
两个运行时并发完成:
toolCount == 2  ✅ (两者都执行)
completionCount == 1  ✅
conflictCount == 1  ✅
```

### 恰好一次声明

**M5 保证:**
✅ 恰好一个成功的 checkpoint 转换 (CHECK B)

**M5 不保证:**
❌ 恰好一次工具执行
❌ 恰好一次模型调用

### 缓解措施

**应用程序责任:**
1. 工具应该是幂等的
2. 或使用应用程序级去重 (请求 ID, 幂等性键)

**M5 不实现去重** (超出范围)

### Phase 8 验证

**测试覆盖:**
- CheckpointStore 原子性 (3 测试)
- 同本地句柄 CAS (1 测试)
- 跨运行时完成竞争 (1 测试)
- 跨运行时重新暂停竞争 (1 测试)
- 版本隔离 (1 测试)

---

## P. M4 Compatibility

### Ephemeral 路径保留

**M4 使用模式仍然有效:**

```java
// 4-参数构造函数 (无持久配置)
SpringAiToolCallingEngine engine = 
  new SpringAiToolCallingEngine(
    model, tools, memory, policy
  );

// ProcessFactory.createSuspended (ephemeral)
AgentProcess process = ProcessFactory.createSuspended(
  id, ephemeralResumeFunction
);
```

### M4 语义不变

**验证的 M4 行为:**
- Ephemeral 失败 → FAILED
- 同句柄并发 → CAS 保护
- 成功完成 → COMPLETED
- 成功重新暂停 → WAITING，策略采用

### 持久性可选

**关键原则:**
- AgentExecutionEngine 不需要持久配置
- DurableExecutionEngine 是可选能力
- M4 应用程序可以继续使用 ephemeral 模式

---

## Q. Test Inventory

### M5-T4 新增测试类

**arctra-core (136 tests):**

1. **InMemoryCheckpointStoreTest** (10 tests)
   - CheckpointStore 参考实现验证

2. **CheckpointStoreConcurrencyTest** (3 tests, Phase 8)
   - `replaceIfVersion` 并发原子性
   - `deleteIfVersion` 并发原子性
   - 过时版本拒绝

3. **DurableCapabilityTest** (9 tests, Phase 2)
   - Core 持久能力基础验证

4. **AgentRuntimeDurableResumeTest** (5 tests, Phase 3)
   - AgentRuntime.resumeProcess() 入口

5. **ProcessExceptionLifecycleTest** (11 tests, Phase 6)
   - 本地句柄异常生命周期
   - ResumePreparationException → WAITING
   - 其他异常 → FAILED
   - M4 回归

**arctra-runtime-react (79 tests):**

6. **InitialDurableSuspensionTest** (8 tests, Phase 4)
   - 初始持久暂停流程
   - Checkpoint 创建验证

7. **DurableResumeExecutionTest** (11 tests, Phase 5)
   - 持久恢复执行路径
   - CHECK A/B 基础验证

8. **DurableResumeEvidenceTest** (12 tests, Phase 5.2)
   - Evidence 累积
   - 无重复验证
   - 20/20 invariants

9. **DurableResumeGovernanceTest** (8 tests, Phase 5)
   - 治理边界
   - 存储批次不重新治理

10. **DurableResumeMemoryTest** (9 tests, Phase 5)
    - ChatMemory 连续性
    - 无重复用户消息

11. **ThreeRuntimeRecoveryTest** (3 tests, Phase 7)
    - A → B → C 跨运行时 E2E
    - 资源权威
    - 身份稳定性

12. **ConcurrentDurableResumeTest** (5 tests, Phase 8)
    - 同句柄并发 CAS
    - 跨运行时完成竞争
    - 跨运行时重新暂停竞争
    - 版本隔离

13. **DurableLifecycleRegressionTest** (3 tests, Phase 9)
    - 完整生命周期回归
    - Memory/Evidence/Governance 综合
    - 拒绝语义
    - 三集 Evidence

### 测试到不变量映射

**核心不变量 (样本):**

| 不变量 | 主要测试 | Phase |
|--------|---------|-------|
| Checkpoint 原子性 | CheckpointStoreConcurrencyTest | 8 |
| Evidence 无重复 | DurableResumeEvidenceTest | 5.2 |
| 旧批次不重新治理 | DurableResumeGovernanceTest | 5 |
| 无重复用户消息 | DurableLifecycleRegressionTest | 9 |
| 跨运行时资源权威 | ThreeRuntimeRecoveryTest | 7 |
| 同句柄 CAS | ConcurrentDurableResumeTest | 8 |
| 本地异常生命周期 | ProcessExceptionLifecycleTest | 6 |

---

## R. Exact Build Results

### 命令 1: mvn clean test

```
Command: mvn clean test
Exit Code: 0
Build Status: BUILD SUCCESS
Total Time: 9.347 s

Module Results:
- arctra-core: 136 tests, 0 failures, 0 errors, 0 skipped
- arctra-runtime-react: 79 tests, 0 failures, 0 errors, 6 skipped
- arctra-rag: 0 tests
- arctra-tool: 0 tests
- arctra-testkit: 0 tests
- arctra-spring-boot-starter: 0 tests
- examples/knowledge-assistant: 0 tests
- examples/incident-investigator: 22 tests, 0 failures, 0 errors, 9 skipped

Total: 237 tests, 0 failures, 0 errors, 15 skipped
```

### 命令 2: mvn verify

```
Command: mvn verify
Exit Code: 0
Build Status: BUILD SUCCESS
Total Time: 8.445 s

All modules: SUCCESS
Verification: PASSED
```

### 源代码 Greps

**1. executePreparedResume:**
```bash
$ grep -R "executePreparedResume" . --include="*.java"
(no matches)
```
✅ 无第二持久管道

**2. org.springframework.ai in core:**
```bash
$ grep -R "org.springframework.ai" arctra-core/src/main/java
(no matches)
```
✅ Core 框架中立

**3. runtime-react in core:**
```bash
$ grep -R "cn.bitcss.arctra.runtime.react" arctra-core/src/main/java
(no matches)
```
✅ 模块边界清晰

**4. DurableExecutor/DurableCapability:**
```bash
$ grep -R "DurableExecutor\|DurableCapability" . --include="*.java"
arctra-core/src/test/java/.../DurableCapabilityTest.java (test only)
```
✅ 仅测试类名，无生产代码

**5. binding.engine:**
```bash
$ grep -R "binding\.engine" . --include="*.java"
(no matches)
```
✅ RuntimeBinding 无引擎权威

---

## S. Known Limitations

### 1. InMemoryCheckpointStore

**限制:** JVM-local 参考实现仅用于测试和单 JVM 验证

**影响:** 
- 无跨 JVM 持久性
- 进程重启后数据丢失

**缓解:** 
- 生产需要持久 CheckpointStore 实现 (JDBC, Redis, 等)

### 2. CheckpointStore / ChatMemory 原子性

**限制:** 无原子事务跨：
```
CheckpointStore 转换 (CHECK B)
+
ChatMemory 最终 Assistant 持久化
```

**崩溃窗口:**
```
CHECK B deleteIfVersion() 成功
→ [crash here]
→ ChatMemory.add(final Assistant) 未调用
```

**影响:**
- Checkpoint 已提交完成
- 但 ChatMemory 可能缺失最终 Assistant

**分类:** M5 可接受的限制

**缓解:** 生产需要事务性存储或外部一致性协调

### 3. At-Least-Once 工具执行

**限制:** M5 不保证恰好一次工具执行

**场景:**
```
Runtime A + B 并发 resumeProcess(P, v1):
→ 两者都通过 CHECK A
→ 两者都执行 Tool X
→ 只有一个赢得 CHECK B
```

**影响:**
- 工具可能执行 2 次（或更多）
- 模型调用可能重复

**缓解:**
- 工具应该是幂等的
- 或使用应用程序级去重

### 4. 应用程序定义的 RuntimeBindingResolver

**限制:** M5 不提供内置通用解析器

**影响:**
- 应用程序必须实现 RuntimeBindingResolver
- 解析逻辑是应用程序特定的

**示例:**
```java
RuntimeBindingResolver resolver = (processId, key, sessionId) -> {
  // 应用程序逻辑：从配置/注册表查找
  AgentDefinition def = agentRegistry.lookup(key);
  AgentExecutionContext ctx = AgentExecutionContext.withSession(sessionId);
  return new RuntimeBinding(def, ctx);
};
```

### 5. 本地句柄是快照

**限制:** 本地 AgentProcess 句柄可能变得过时

**场景:**
```
Runtime A 持有句柄 (P, v1)
Runtime B 在其他地方推进 v1 → v2
Runtime A 句柄尝试 resume()
→ StaleCheckpointException
→ 本地句柄 → FAILED
```

**语义:**
```
本地 FAILED ≠ 全局持久进程失败
```

**Checkpoint 是权威的**

### 6. 无 RuntimeBindingKey 迁移

**限制:** M5 保留逻辑绑定 key 跨生命周期

**影响:**
- 无内置 key 重新映射/迁移
- Key 在初始暂停时设置并冻结

### 7. Continuation 代码重复

**限制:** 存在重复的 continuation 实现

**位置:**
```
continueWithMessages()       (ephemeral)
vs
durableContinueWithMessages() (durable)
```

**分类:** 接受的 M6 技术债务

**影响:** 维护负担，但功能正确

**缓解:** 延期到 M6 整合

---

## T. Accepted Technical Debt

### TD-1: Continuation 代码重复

**位置:** SpringAiToolCallingEngine

**问题:**
```java
private AgentResult continueWithMessages(...)  // Ephemeral
private AgentResult durableContinueWithMessages(...)  // Durable
```

**根本原因:**
- Ephemeral/durable 路径有不同的后处理
- 共享 continuation 逻辑被复制

**分类:**
- **不是** 重复的持久管道
- **是** 共享 continuation 实现重复

**影响:**
- 维护成本增加
- 但不影响正确性
- 不影响公共 API

**延期到:** M6 整合

**不在 Phase 10 重构:**
- Phase 10 仅验证/文档
- 不添加新功能或重新设计

---

## U. Final Gate Checklist

### 源代码审计 ✅

- [x] 完整源代码真相已审计
- [x] core → runtime-react 依赖不存在
- [x] core → Spring AI 依赖不存在
- [x] 一个持久恢复编排存在
- [x] DurableExecutionEngine API 最小化
- [x] RuntimeBinding 无引擎权威
- [x] DefaultAgentRuntime 无重复持久配置

### 持久语义 ✅

- [x] CHECK A 在副作用前
- [x] CHECK B 条件完成/重新暂停
- [x] processId 稳定
- [x] checkpointVersion 单调
- [x] runtimeBindingKey 稳定
- [x] sessionId 稳定

### 系统语义 ✅

- [x] Evidence 无重复
- [x] 存储的待处理批次不重新治理
- [x] 新 ToolCalls 治理
- [x] ChatMemory 无重复 U
- [x] 最终 Assistant 恰好一次成功完成

### 并发语义 ✅

- [x] 同句柄 CAS 语义已证明
- [x] 跨运行时冲突语义已证明
- [x] 无恰好一次工具执行声明

### 跨运行时验证 ✅

- [x] 跨运行时 A→B→C 通过
- [x] M4 不变

### 文档 ✅

- [x] 已知限制已记录
- [x] 技术债务已记录
- [x] 实现指南已最终化
- [x] 最终闭环报告已创建

### 构建验证 ✅

- [x] mvn clean test 退出代码 = 0
- [x] mvn verify 退出代码 = 0
- [x] 精确最终测试总数已报告 (237 tests)
- [x] 零未解决的阻塞缺陷

---

## V. Final Decision

### ✅ **M5-T4 FINAL: GO**

**声明:**

**M5-T4 durable suspension/recovery capability is functionally complete and validated for the defined M5 scope.**

### 交付的功能

1. ✅ Checkpoint-backed durable suspension
2. ✅ Cross-runtime/JVM recovery
3. ✅ Unified resume pipeline (CHECK A/B)
4. ✅ Local handle lifecycle semantics
5. ✅ Memory/Evidence/Governance continuity
6. ✅ Concurrency contracts (CAS + CHECK B)
7. ✅ 237 tests, 0 failures

### M5 范围边界

**M5 提供:**
- 单 JVM 持久恢复验证
- InMemoryCheckpointStore 参考实现
- 跨独立运行时实例恢复能力
- 核心抽象和框架中立接口

**M5 不声称:**
- 生产分布式持久性 (需要持久 CheckpointStore)
- 恰好一次工具执行 (at-least-once 语义)
- 原子 checkpoint/ChatMemory 事务
- 内置 RuntimeBindingResolver 实现

### 后续工作 (M6+)

- Continuation 代码整合
- 生产 CheckpointStore 实现 (JDBC, Redis)
- 事务性 checkpoint/memory 协调
- 工具去重策略
- RuntimeBindingResolver 参考实现

### 发布就绪性

**M5-T4 已准备好用于:**
- ✅ 单 JVM 测试和验证
- ✅ 架构评估
- ✅ 应用程序原型
- ✅ 持久持久性的基础

**M5-T4 不立即生产就绪用于:**
- ❌ 分布式持久部署 (需要持久存储)
- ❌ 严格的恰好一次执行要求

---

**M5-T4 实施已完成。核心持久能力已交付并验证。**

**停止 — M5-T4 最终闭环完成。**
