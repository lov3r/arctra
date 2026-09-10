# M5-T4 Phase 8 Concurrency & Conflict Semantics Closure Report

**日期:** 2026-09-09

**目标:** 锁定同本地句柄和跨运行时持久恢复的并发行为

**结果:** ✅ **PHASE 8: GO**

---

## A. 并发模型

### 两个不同的并发域

**Domain A — 同本地 AgentProcess**

```
同一 Java AgentProcess 实例
多个并发 resume() 调用
```

**保证:**
- 本地 CAS (WAITING → RUNNING)
- 只有一个调用者进入后端执行
- 其他调用者在后端/工具执行前失败

**Domain B — 跨运行时恢复**

```
Runtime A.resumeProcess(P, vN)
Runtime B.resumeProcess(P, vN)
```

**两者都可能:**
- 通过 CHECK A
- 解析绑定
- 执行工具
- 调用模型

**只有一个可能:**
- 赢得 CHECK B

**失败者必须接收:**
- `CheckpointTransitionConflictException`

### M5 保证

✅ **恰好一个成功的 checkpoint 转换**

❌ **不保证恰好一次工具执行**

---

## B. 同句柄并发结果

### 测试: `sameHandle_concurrentResume_onlyOneBackendInvocation`

**场景:**
- 1 个持久 AgentProcess 句柄
- 3 个并发线程调用 `process.resume(signal)`
- 使用 CyclicBarrier 同步启动

**结果:**
```java
successCount == 1  ✅ (通过 CAS)
rejectedCount == 2  ✅ (在 CAS 处被拒绝)

backendResumeCount == 1  ✅ (只有一次后端调用)
toolExecutionCount == 1  ✅ (只有一次工具执行)
```

**异常:**
```java
IllegalStateException: "Cannot resume process in state RUNNING (must be WAITING)"
```

**关键证明:**
> **本地 CAS 保护后端调用 — 只有一个线程执行**

---

## C. 跨运行时完成竞争

### 测试: `crossRuntimeCompletionRace_oneWinnerOneConflict`

**场景:**
- Checkpoint v1 已存在，待处理 toolX
- Runtime A 和 B 并发调用 `resumeProcess(P100, 1L, approved)`
- 使用 CountDownLatch 确保两者都执行工具后再进入 CHECK B

**工具执行:**
```java
toolCountA + toolCountB == 2  ✅ (两个运行时都执行了)
```

**CHECK B 结果:**
```java
completionCount == 1  ✅ (一个赢家)
conflictCount == 1  ✅ (一个失败者)
```

**Checkpoint 最终状态:**
```java
store.load("P100").isEmpty() == true  ✅ (赢家删除了)
```

**ChatMemory 最终 Assistant:**
```java
assistantMessageCount == 1  ✅ (只有赢家写入)
```

**关键证明:**
> **两个运行时都可能执行工具（不是恰好一次），但只有一个 CHECK B 赢家**

---

## D. 跨运行时重新暂停竞争

### 测试: `crossRuntimeReSuspensionRace_oneWinnerOneConflict`

**场景:**
- Checkpoint v1 已存在
- Runtime A 和 B 并发恢复
- 两者都执行 toolX 并发出 toolY（需要批准）
- 使用同步确保两者都在 CHECK B 前执行

**工具执行:**
```java
toolCountA + toolCountB == 2  ✅ (两个都执行了)
```

**CHECK B 结果:**
```java
suspendedCount == 1  ✅ (一个赢家重新暂停)
conflictCount == 1  ✅ (一个失败者冲突)
```

**存储的 Checkpoint v2:**
```java
stored.checkpointVersion() == 2L  ✅
stored.processId() == "P100"  ✅ (不变)
存储中恰好一个 v2  ✅
```

**关键证明:**
> **重新暂停竞争：一个 replaceIfVersion 成功，一个失败**

---

## E. CheckpointStore 原子性

### 测试 1: `replaceIfVersion_concurrentExactlyOneSuccess`

**场景:**
- 初始 checkpoint v1
- 两个线程并发调用 `replaceIfVersion("P100", 1L, replacement)`
- 使用 CyclicBarrier 同步

**结果:**
```java
successCount == 1  ✅
failureCount == 1  ✅

存储的 checkpoint.version == 2L  ✅
存储的 checkpoint == winnerReplacement  ✅
```

### 测试 2: `deleteIfVersion_concurrentExactlyOneSuccess`

**场景:**
- 初始 checkpoint v1
- 两个线程并发调用 `deleteIfVersion("P100", 1L)`

**结果:**
```java
successCount == 1  ✅
failureCount == 1  ✅

store.load("P100").isEmpty() == true  ✅
```

### 测试 3: `replaceIfVersion_staleVersionFails`

**场景:**
- 线程 1 推进 v1 → v2
- 线程 2 尝试过时 v1 → v2

**结果:**
```java
t1Success == 1  ✅
t2Success == 0  ✅ (过时版本被拒绝)

存储的 == cp2 (不是过时的)  ✅
```

**关键证明:**
> **InMemoryCheckpointStore 使用 ConcurrentHashMap.compute() 提供正确的 CAS 语义**

---

## F. 竞争后的版本隔离

### 测试: `staleResumeAfterRace_rejectedBeforeSideEffects`

**场景:**
1. Runtime A 赢得竞争：v1 → v2
2. Runtime B 尝试过时 v1 恢复

**结果:**
```java
抛出 StaleCheckpointException  ✅

// CHECK A 阻止的零副作用
toolCountB == 0  ✅
modelCountB == 0  ✅
resolverCountB == 0  ✅

// Checkpoint v2 不变
stored.checkpointVersion() == 2L  ✅
```

**关键证明:**
> **竞争后的版本隔离工作 — CHECK A 在副作用前拒绝过时恢复**

---

## G. 冲突下的证据语义

### 设计

**每个并发恢复:**
- 构建自己的内存中 Evidence
- 只有 CHECK B 赢家的 checkpoint/result 变为持久/成功

**失败者:**
- 抛出冲突
- 其 Evidence 是瞬态的，随异常丢弃

**完成竞争:**
- 赢家结果包含其自己的 Evidence
- 失败者 Evidence 丢失

**重新暂停竞争:**
- 存储的 CP v2 仅包含赢家的合并 Evidence
- 失败者 Evidence 丢失

**关键原则:**
> **不尝试将失败者 Evidence 合并到赢家 checkpoint**

---

## H. 恰好一次声明

### M5 并发保证

✅ **保证:**
- 恰好一个成功的 checkpoint 转换 (CHECK B)
- 一个权威的 checkpoint 版本

❌ **不保证:**
- 恰好一次工具执行
- 恰好一次模型调用

### 为什么工具可能执行两次

**跨运行时竞争序列:**
```
Runtime A               Runtime B
---------               ---------
CHECK A (load v1) ✅    CHECK A (load v1) ✅
resolve binding ✅      resolve binding ✅
execute toolX ✅        execute toolX ✅  ← 两者都执行
call model ✅           call model ✅
CHECK B (delete v1) ✅  CHECK B (delete v1) ❌ ← 只有一个成功
→ COMPLETED             → ConflictException
```

**关键点:**
- 两者都通过 CHECK A（版本匹配时）
- 两者都执行工具（没有执行前锁）
- 只有一个赢得 CHECK B（CAS 保护）

### 记录的缓解措施

**应用程序责任:**
1. **工具应该是幂等的**
   - 多次调用产生相同结果
   - 无有害副作用

2. **或使用应用程序级去重**
   - 工具内部的请求 ID
   - 外部幂等性键

3. **M5 不实现去重**
   - 超出核心持久能力范围
   - 应用程序特定的语义

---

## I. 生产文件更改

**总计:** **0** 个生产文件更改

**Phase 8 是纯验证阶段。**

✅ 所有原子性已经正确实现：
- `DefaultAgentProcess`: 本地 CAS (第 104 行)
- `InMemoryCheckpointStore`: `compute()` 用于 CAS (第 59、88 行)

✅ E2E 测试证实并发语义正确

---

## J. 测试创建/修改

### 创建 (2)

1. **CheckpointStoreConcurrencyTest.java** (`arctra-core`)
   - 3 个新的原子性测试
   - 验证存储层的 CAS 保证

2. **ConcurrentDurableResumeTest.java** (`arctra-runtime-react`)
   - 5 个新的并发恢复测试
   - 完整的竞争场景覆盖

### 测试详细信息

**CheckpointStore 原子性 (3 测试):**
| # | 测试方法 | 验证 |
|---|---------|------|
| 1 | `replaceIfVersion_concurrentExactlyOneSuccess` | 并发替换 → 一个成功 |
| 2 | `deleteIfVersion_concurrentExactlyOneSuccess` | 并发删除 → 一个成功 |
| 3 | `replaceIfVersion_staleVersionFails` | 过时版本被拒绝 |

**跨运行时并发 (5 测试):**
| # | 测试方法 | 验证 |
|---|---------|------|
| 1 | `sameHandle_concurrentResume_onlyOneBackendInvocation` | 本地 CAS → 一个后端 |
| 2 | `crossRuntimeCompletionRace_oneWinnerOneConflict` | 完成竞争 + ChatMemory |
| 3 | `crossRuntimeReSuspensionRace_oneWinnerOneConflict` | 重新暂停竞争 |
| 4 | `staleResumeAfterRace_rejectedBeforeSideEffects` | 竞争后版本隔离 |
| 5 | `localHandle_afterCheckBConflict_becomesFailed` | 本地句柄 → FAILED |

### 更新 (1)

3. **M5-T4-IMPLEMENTATION-GUIDE.md**
   - 添加 Phase 8 GO 状态
   - 添加冻结并发契约

**总计:** 3 个文件

---

## K. 精确 Maven 结果

### 命令 1: `mvn test -Dtest=CheckpointStoreConcurrencyTest -pl arctra-core`
- **退出代码:** 0
- **运行的测试:** 3
- **失败:** 0
- **错误:** 0
- **跳过:** 0
- **构建状态:** BUILD SUCCESS
- **时间:** 3.195 秒

### 命令 2: `mvn test -Dtest=ConcurrentDurableResumeTest -pl arctra-runtime-react`
- **退出代码:** 0
- **运行的测试:** 5
- **失败:** 0
- **错误:** 0
- **跳过:** 0
- **构建状态:** BUILD SUCCESS
- **时间:** 3.504 秒

### 命令 3: `mvn test -pl arctra-core`
- **退出代码:** 0
- **运行的测试:** 136 (+3 从 Phase 7 的 133)
- **失败:** 0
- **错误:** 0
- **跳过:** 0
- **构建状态:** BUILD SUCCESS

### 命令 4: `mvn test -pl arctra-runtime-react`
- **退出代码:** 0
- **运行的测试:** 76 (+5 从 Phase 7 的 71)
- **失败:** 0
- **错误:** 0
- **跳过:** 6
- **构建状态:** BUILD SUCCESS

### 命令 5: `mvn test` (完整 reactor)
- **退出代码:** 0
- **Core:** 136 测试，0 失败
- **Runtime-react:** 76 测试，0 失败，6 跳过
- **Examples:** 22 测试，0 失败，9 跳过
- **总测试:** 234 (+8 从 Phase 7 的 226)
- **总失败:** 0
- **总错误:** 0
- **总跳过:** 15
- **构建状态:** BUILD SUCCESS
- **总时间:** 6.253 秒

### 命令 6: `mvn verify`
- **退出代码:** 0
- **构建状态:** BUILD SUCCESS
- **总时间:** 6.502 秒
- **所有模块:** 通过

---

## L. 实现指南更新

**文件:** `M5-T4-IMPLEMENTATION-GUIDE.md`

**更新:**

```markdown
**Implementation Progress:**
- Phase 8: ✅ GO (Concurrency & conflict semantics)
- Phase 9-10: ⏳ PENDING

**Phase 8 Summary:**
- **Frozen Concurrency Contract:** Same-handle CAS + cross-runtime CHECK B winner
- **8 New Concurrency Tests:** 3 CheckpointStore atomic + 5 cross-runtime race
- **Exactly-Once Statement:** M5 does NOT guarantee exactly-once tool execution
- **Tools May Execute Twice:** Cross-runtime race allows both to execute before CHECK B
- **Idempotency Required:** Documented as application responsibility
- **No Production Changes:** All atomicity already correct
```

✅ **已更新**

---

## M. 决定

### Phase 8 Gate 检查表

- [x] **同本地句柄允许一个后端执行** ✅
- [x] **跨运行时完成竞争允许一个 CHECK B 赢家** ✅
- [x] **失败的完成获得 CheckpointTransitionConflictException** ✅
- [x] **只有赢家写入最终 Assistant** ✅
- [x] **跨运行时重新暂停竞争允许一个 CHECK B 赢家** ✅
- [x] **存储包含一个权威 v2** ✅
- [x] **失败的重新暂停获得冲突** ✅
- [x] **竞争后过时的旧版本被拒绝** ✅
- [x] **replaceIfVersion 在并发下是原子的** ✅
- [x] **deleteIfVersion 在并发下是原子的** ✅
- [x] **没有恰好一次声明** ✅
- [x] **幂等性要求已记录** ✅
- [x] **没有不必要的生产代码更改** ✅
- [x] **Core 测试通过** ✅
- [x] **Runtime-react 测试通过** ✅
- [x] **Reactor 测试通过** ✅
- [x] **Verify 退出代码 = 0** ✅
- [x] **实现指南已更新** ✅

**所有 gate 通过:** 18/18 ✅

---

## N. 冻结并发契约摘要

### Domain A: 同本地 AgentProcess

```java
// 同一句柄，多个线程
process.resume(signal)  // Thread 1
process.resume(signal)  // Thread 2
process.resume(signal)  // Thread 3

保证:
→ 只有一个通过 CAS (WAITING → RUNNING)
→ 只有一个后端执行
→ 其他线程: IllegalStateException
```

### Domain B: 跨运行时恢复

```java
// 不同运行时，同 processId/version
runtimeA.resumeProcess(P, v1, signal)
runtimeB.resumeProcess(P, v1, signal)

两者都可能:
→ 通过 CHECK A
→ 执行工具（不是恰好一次！）
→ 调用模型

只有一个:
→ 赢得 CHECK B (replaceIfVersion 或 deleteIfVersion)

失败者:
→ CheckpointTransitionConflictException
→ Evidence 丢失
```

### 关键区别

| 方面 | 同本地句柄 | 跨运行时 |
|------|-----------|---------|
| **保护点** | 本地 CAS | CHECK B CAS |
| **工具执行** | 恰好一次 | 可能两次 |
| **失败者异常** | IllegalStateException | CheckpointTransitionConflictException |
| **幂等性** | 不需要 | **需要** |

---

## 结果

### ✅ **PHASE 8: GO**

**成就:**

1. ✅ **冻结并发契约已记录**
   - 同本地句柄：CAS → 一个后端
   - 跨运行时：CHECK B → 一个赢家

2. ✅ **CheckpointStore 原子性已验证**
   - `replaceIfVersion`: 并发 → 恰好一个成功
   - `deleteIfVersion`: 并发 → 恰好一个成功
   - 过时版本被正确拒绝

3. ✅ **跨运行时竞争语义已证明**
   - 完成竞争：两个执行，一个完成，一个冲突
   - 重新暂停竞争：两个执行，一个 v2，一个冲突
   - 只有赢家写入 ChatMemory

4. ✅ **恰好一次声明已明确**
   - M5 不保证恰好一次工具执行
   - 工具应该是幂等的（记录）

5. ✅ **零生产更改 - 架构已正确**
   - 本地 CAS 已实现
   - CheckpointStore CAS 已实现
   - 纯验证阶段

**Phase 1-8 完整且已验证。M5-T4 核心持久能力完成。**

**准备 Phase 9（不在此范围内）。**

---

**停止 — Phase 8 成功完成。M5-T4 Phase 1-8 完成且已验证。**
