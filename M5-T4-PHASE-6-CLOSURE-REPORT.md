# M5-T4 Phase 6 Local Handle Exception Lifecycle Closure Report

**日期:** 2026-09-09

**目标:** 定义并实现正确的本地 Java AgentProcess 状态转换，当持久恢复成功、重新暂停或抛出异常时

**结果:** ✅ **PHASE 6: GO**

---

## A. 修改前的实际 DefaultAgentProcess 行为

### 当前异常处理（第 141-157 行）

```java
catch (Throwable t) {
  // JVM 致命错误应立即传播，不标记进程为 FAILED
  if (t instanceof VirtualMachineError || t instanceof ThreadDeath) {
    throw t;
  }

  // Continuation 失败：转换到 FAILED 并重新抛出原始异常
  // FAILED 是终态 - 进程无法再次恢复
  status.set(ProcessStatus.FAILED);

  // 重新抛出原始异常不变
  if (t instanceof RuntimeException) {
    throw (RuntimeException) t;
  } else {
    throw (Error) t;
  }
}
```

**当前行为:** 所有非 JVM 致命错误都导致 `FAILED` 状态。

**问题:** 不区分可重试的准备失败（如 ResumePreparationException）和真正的终态失败。

---

## B. 失败分类表

| 异常 | 工具/模型副作用? | CHECK B 提交? | 策略/版本仍有效? | 最终本地状态 |
|------|-----------------|--------------|------------------|-------------|
| ResumePreparationException | 否 | 否 | **是**（checkpoint 未变） | **WAITING**（可重试） |
| StaleCheckpointException | 否 | 否 | **否**（本地版本过时） | **FAILED**（本地句柄不可用） |
| CheckpointNotFoundException | 否 | 未知 | **否**（无 checkpoint 支持） | **FAILED**（本地句柄不可用） |
| CheckpointTransitionConflictException | 是 | 否（失去竞争） | **否**（本地策略过时） | **FAILED**（本地句柄过时） |
| 工具/模型/后端失败 | 可能是 | 否 | 不确定 | **FAILED**（保持 M4 语义） |
| ChatMemory 失败（CHECK B 后） | 是 | 是 | 不适用（无 checkpoint） | **COMPLETED**（但抛出异常）* |

**注释:**
- **策略有效性** 是关键判断标准
- **WAITING** 仅当 checkpoint 未变且相同策略/版本仍可用
- **FAILED** 表示"本地句柄不可用"，**不一定**表示全局逻辑进程失败
- **Checkpoint 是权威的**，不是本地对象状态

\* ChatMemory 失败分析见 K 节

---

## C. 最终异常策略

### Phase 6 实现（修改后）

```java
catch (Throwable t) {
  // JVM-fatal errors should propagate immediately without marking process as FAILED
  if (t instanceof VirtualMachineError || t instanceof ThreadDeath) {
    throw t;
  }

  // M5-T4 Phase 6: Exception lifecycle based on strategy validity
  //
  // ResumePreparationException: Retryable preparation failure
  // - CHECK A passed, binding resolution failed
  // - No tool/model execution, no CHECK B
  // - Checkpoint unchanged, same (processId, version) remains valid
  // - Local handle remains usable → WAITING
  //
  // All other exceptions: Terminal local failure → FAILED
  // - StaleCheckpointException: local handle holds stale version
  // - CheckpointNotFoundException: no backing checkpoint exists
  // - CheckpointTransitionConflictException: lost CHECK B race, handle stale
  // - Tool/model/backend failures: execution side effects may have occurred
  //
  // CRITICAL: FAILED here means "local handle unusable", NOT necessarily
  // global logical process failure. Checkpoint is authoritative.
  if (t instanceof cn.bitcss.arctra.runtime.ResumePreparationException) {
    // Retryable preparation failure - checkpoint unchanged
    status.set(ProcessStatus.WAITING);
  } else {
    // Terminal local failure - handle unusable
    status.set(ProcessStatus.FAILED);
  }

  // Rethrow original exception unchanged
  if (t instanceof RuntimeException) {
    throw (RuntimeException) t;
  } else {
    throw (Error) t;
  }
}
```

### 策略摘要

**可重试:**
- `ResumePreparationException` → `WAITING`
  - CHECK A 通过
  - 绑定解析失败
  - 无工具/模型执行
  - Checkpoint 未变
  - 相同 (processId, version) 仍然有效

**终态本地失败:**
- `StaleCheckpointException` → `FAILED` (本地版本过时)
- `CheckpointNotFoundException` → `FAILED` (无支持 checkpoint)
- `CheckpointTransitionConflictException` → `FAILED` (失去 CHECK B 竞争)
- 所有其他异常 → `FAILED` (保持 M4 语义)

---

## D. ResumePreparation 重试行为

### 测试: `resumePreparationException_waiting_retryable()`

**场景:**
1. 第一次调用 → `ResumePreparationException`
2. 第二次调用 → 成功完成

**验证:**
```java
// 第一次 resume → ResumePreparationException → WAITING
assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);

// 第二次 resume → 成功
AgentResult result = process.resume(...);
assertThat(result.isCompleted()).isTrue();
assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
assertThat(attemptCount.get()).isEqualTo(2);
```

✅ **通过** - 准备失败是可重试的

---

## E. Stale Checkpoint 行为

### 测试: `staleCheckpointException_failed_secondCallBlocked()`

**场景:**
- 本地句柄有 v1
- Checkpoint 是 v2（外部推进）

**验证:**
```java
// 第一次 resume → StaleCheckpointException → FAILED
assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
assertThat(engineCallCount.get()).isEqualTo(1);

// 第二次 resume → 在 CAS 处阻塞，引擎未调用
assertThatThrownBy(...)
    .isInstanceOf(IllegalStateException.class)
    .hasMessageContaining("Cannot resume process in state FAILED");

assertThat(engineCallCount.get()).isEqualTo(1);  // 仍然是 1
```

✅ **通过** - 过时句柄是终态，不会无限重试

---

## F. Missing Checkpoint 行为

### 测试: `checkpointNotFoundException_failed()`

**场景:**
- Checkpoint 缺失（在其他地方完成、删除或存储不一致）

**验证:**
```java
assertThatThrownBy(...).isInstanceOf(CheckpointNotFoundException.class);
assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
```

**语义:**
- 本地 `FAILED` ≠ 全局逻辑进程失败
- 仅表示此本地句柄不再可用
- Checkpoint 缺失可能对应于其他地方的逻辑完成

✅ **通过**

---

## G. CHECK B Conflict 行为

### 测试: `checkpointTransitionConflictException_failed()`

**场景:**
- 执行执行了工作
- 但失去了 CHECK B 竞争

**验证:**
```java
assertThatThrownBy(...).isInstanceOf(CheckpointTransitionConflictException.class);
assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
```

**语义:**
- 另一个 resume 可能已经：
  - 完成了 checkpoint
  - 推进到 vN+1
- 本地句柄在冲突后过时
- 不尝试自动重新加载/采用（Phase 6 范围外）

✅ **通过**

---

## H. 执行失败行为

### 测试: `toolModelFailure_failed()`

**场景:**
- 工具/模型/后端抛出 RuntimeException

**验证:**
```java
assertThatThrownBy(...)
    .isInstanceOf(RuntimeException.class)
    .hasMessage("Tool execution failed");

assertThat(process.status()).isEqualTo(ProcessStatus.FAILED);
```

✅ **通过** - 保持 M4 失败语义

---

## I. 成功完成/重新暂停回归

### 测试 #8: `successfulCompletion_completed()`

**验证:**
```java
AgentResult result = process.resume(...);
assertThat(result.isCompleted()).isTrue();
assertThat(process.status()).isEqualTo(ProcessStatus.COMPLETED);
```

✅ **通过** - M4 完成语义不变

### 测试 #9: `successfulReSuspension_waitingWithAdoptedStrategy()`

**场景:**
1. 第一次 resume → 重新暂停到 v2
2. 第二次 resume → 完成

**验证:**
```java
// 第一次 resume → 重新暂停
assertThat(result1.isSuspended()).isTrue();
assertThat(process.status()).isEqualTo(ProcessStatus.WAITING);
assertThat(result1.process()).isSameAs(process);  // 稳定身份

// 第二次 resume 使用采用的策略
AgentResult result2 = process.resume(...);
assertThat(result2.isCompleted()).isTrue();
assertThat(callCount.get()).isEqualTo(2);
```

✅ **通过** - 策略采用不变

### 测试 #10: `secondResumeAfterReSuspension_usesNewVersion()`

**验证:**
```java
// 第一次 resume with v1
process.resume(...);

// 第二次 resume with v2
process.resume(...);

assertThat(observedVersions).containsExactly(1L, 2L);
```

✅ **通过** - vN → vN+1 后第二次 resume 使用新策略/版本

---

## J. 同句柄并发回归

### 测试 #7: `sameHandleConcurrency_oneCasSucceeds()`

**场景:** 3 个并发 resume 尝试

**验证:**
```java
assertThat(successCount.get()).isEqualTo(1);      // 只有 1 个成功
assertThat(rejectedCount.get()).isEqualTo(2);     // 2 个在 CAS 处被拒绝
assertThat(executionCount.get()).isEqualTo(1);    // 只执行 1 次
```

✅ **通过** - Phase 2 并发语义不变

---

## K. ChatMemory Post-CHECK-B 失败分析

### 当前控制流

```
engine.resumeProcess()
→ CHECK B deleteIfVersion() 成功
→ persistCompletedAssistant() 抛出
→ 异常冒泡到 DefaultAgentProcess
```

### 关键问题

1. **DefaultAgentProcess 能知道 CHECK B 已提交吗?**
   - **否。** `AgentResult` 不携带 CHECK B 状态元数据。
   - 引擎返回 `AgentResult` 或抛出异常。
   - 如果 `persistCompletedAssistant()` 在引擎内部抛出，`DefaultAgentProcess` 只看到异常。

2. **能否区分这个与工具/模型失败?**
   - **否。** 没有专用异常类型。
   - 无法从通用 `RuntimeException` 区分 ChatMemory 失败。

3. **已有专用异常吗?**
   - **否。** 当前没有 `ChatMemoryPersistenceException`。

4. **解决它是否需要新后端元数据或新异常类型?**
   - **是。** 需要以下之一：
     - `AgentResult` 中的新字段：`boolean checkpointCommitted`
     - 新异常类型：`ChatMemoryPersistenceException`
     - 两阶段结果协议

### Phase 6 决定: **记录的限制**

**当前行为:**
- 如果 `persistCompletedAssistant()` 在 `CHECK B deleteIfVersion()` 成功后抛出
- 本地句柄变为 `FAILED`（因为 `DefaultAgentProcess` 看到异常）
- **但** checkpoint 已提交完成

**语义:**
- 本地 `FAILED` ≠ 持久进程失败
- **Checkpoint 仍然是权威的**
- Checkpoint 已删除（完成已提交）
- 只有 ChatMemory 写入失败

**可接受性:**
- M5 可接受，如果不可避免
- 不添加事务逻辑
- 不发明假解决方案
- 记录为已知限制

### 未实现的测试

**原因:** 需要架构更改（新元数据或异常类型），超出 Phase 6 范围。

**推荐:** 延期到未来阶段，如果这成为实际问题。

**当前状态:** **记录的限制** - 本地句柄可能在持久 checkpoint 提交完成后变为 FAILED。Checkpoint 仍然是权威的。

---

## L. 修改的文件

### 修改 (1)

1. **DefaultAgentProcess.java** (`arctra-core`)
   - 修改异常处理逻辑（第 141-177 行）
   - 添加基于策略有效性的分类
   - `ResumePreparationException` → `WAITING`
   - 所有其他异常 → `FAILED`
   - 添加详细注释说明语义

### 创建 (1)

2. **ProcessExceptionLifecycleTest.java** (`arctra-core`)
   - 11 个新测试
   - 覆盖所有异常生命周期不变量
   - M4 回归测试（并发、完成、重新暂停）

### 更新 (1)

3. **M5-T4-IMPLEMENTATION-GUIDE.md**
   - 添加 Phase 6 GO 状态
   - 添加 Phase 6 摘要

**总计:** 3 个文件

---

## M. 测试矩阵

| # | 不变量 | 测试方法 | 状态 |
|---|--------|----------|------|
| 1 | ResumePreparationException → WAITING，可重试 | resumePreparationException_waiting_retryable | ✅ |
| 2 | StaleCheckpointException → FAILED，第二次调用在 CAS 处阻塞 | staleCheckpointException_failed_secondCallBlocked | ✅ |
| 3 | CheckpointNotFoundException → FAILED | checkpointNotFoundException_failed | ✅ |
| 4 | CheckpointTransitionConflictException → FAILED | checkpointTransitionConflictException_failed | ✅ |
| 5 | 工具/模型失败 → FAILED | toolModelFailure_failed | ✅ |
| 6 | M4 ephemeral 失败 → FAILED (不变) | ephemeralFailure_failed_unchanged | ✅ |
| 7 | 同句柄并发 - 只有一个 CAS 成功 | sameHandleConcurrency_oneCasSucceeds | ✅ |
| 8 | 成功完成 → COMPLETED | successfulCompletion_completed | ✅ |
| 9 | 成功重新暂停 → WAITING，采用策略 | successfulReSuspension_waitingWithAdoptedStrategy | ✅ |
| 10 | vN→vN+1 后第二次 resume 使用新策略 | secondResumeAfterReSuspension_usesNewVersion | ✅ |
| 11 | 终态失败后第二次调用不调用后端 | secondCallAfterFailed_doesNotInvokeBackend | ✅ |
| 12 | ChatMemory post-CHECK-B 失败行为 | **记录的限制** | ⚠️ 文档化 |

**覆盖率:** 11/11 测试通过，1 个记录的限制

---

## N. 精确 Maven 结果

### 命令 1: `mvn test -Dtest=ProcessExceptionLifecycleTest -pl arctra-core`
- **退出代码:** 0
- **运行的测试:** 11
- **失败:** 0
- **错误:** 0
- **跳过:** 0
- **构建状态:** BUILD SUCCESS
- **时间:** 3.047 秒

### 命令 2: `mvn test -pl arctra-core`
- **退出代码:** 0
- **运行的测试:** 133 (+11 从 Phase 5.2 的 122)
- **失败:** 0
- **错误:** 0
- **跳过:** 0
- **构建状态:** BUILD SUCCESS

### 命令 3: `mvn test -pl arctra-runtime-react`
- **退出代码:** 0
- **运行的测试:** 68
- **失败:** 0
- **错误:** 0
- **跳过:** 6
- **构建状态:** BUILD SUCCESS

### 命令 4: `mvn test` (完整 reactor)
- **退出代码:** 0
- **Core:** 133 测试，0 失败
- **Runtime-react:** 68 测试，0 失败，6 跳过
- **Examples:** 22 测试，0 失败，9 跳过
- **总测试:** 223 (+11 从 Phase 5.2 的 212)
- **总失败:** 0
- **总错误:** 0
- **总跳过:** 15
- **构建状态:** BUILD SUCCESS
- **总时间:** 6.830 秒

### 命令 5: `mvn verify`
- **退出代码:** 0
- **构建状态:** BUILD SUCCESS
- **总时间:** 8.394 秒
- **所有模块:** 通过

---

## O. 实现指南更新

**文件:** `M5-T4-IMPLEMENTATION-GUIDE.md`

**更新:**

```markdown
**Implementation Progress:**
- Phase 6: ✅ GO (Local handle exception lifecycle)
- Phase 7-10: ⏳ PENDING

**Phase 6 Summary:**
- **Local Exception Policy:** ResumePreparationException → WAITING (retryable), all others → FAILED
- **Strategy Validity Based:** Status transitions based on whether strategy/version remains valid
- **11 New Tests:** Complete exception lifecycle coverage (133 total core tests)
- **M4 Semantics Preserved:** Ephemeral failure, concurrency, re-suspension unchanged
- **No Backend Changes:** All changes in DefaultAgentProcess only
```

✅ **已更新**

---

## P. 决定

### Phase 6 Gate 检查表

- [x] **本地状态语义源自实际策略有效性** ✅
- [x] **可重试的准备失败保持可重试** ✅ (ResumePreparationException → WAITING)
- [x] **过时版本句柄不误导性地视为当前** ✅ (StaleCheckpointException → FAILED)
- [x] **缺失 checkpoint 句柄语义已定义** ✅ (CheckpointNotFoundException → FAILED)
- [x] **CHECK B 冲突句柄语义已定义** ✅ (CheckpointTransitionConflictException → FAILED)
- [x] **执行失败本地保持终态** ✅ (RuntimeException → FAILED)
- [x] **M4 行为不变** ✅ (ephemeral 失败、完成、重新暂停)
- [x] **同句柄并发不变** ✅ (只有一个 CAS 成功)
- [x] **重新暂停策略采用不变** ✅ (稳定身份，采用新策略)
- [x] **vN→vN+1 后第二次 resume 使用新策略** ✅ (观察到的版本 [1L, 2L])
- [x] **未更改持久后端语义** ✅ (无后端生产更改)
- [x] **ChatMemory 崩溃窗口行为明确记录** ✅ (记录的限制)
- [x] **Core 测试通过** ✅ (133 测试，0 失败)
- [x] **Runtime-react 测试通过** ✅ (68 测试，0 失败)
- [x] **Reactor 测试通过** ✅ (223 测试，0 失败)
- [x] **Verify 退出代码 = 0** ✅
- [x] **实现指南已更新** ✅

**所有 gate 通过:** 16/16 ✅

---

## Q. 最终异常策略表

| 异常类型 | 本地状态 | 可重试? | 原因 |
|---------|---------|--------|------|
| `ResumePreparationException` | **WAITING** | **是** | Checkpoint 未变，策略有效 |
| `StaleCheckpointException` | **FAILED** | 否 | 本地版本过时 |
| `CheckpointNotFoundException` | **FAILED** | 否 | 无支持 checkpoint |
| `CheckpointTransitionConflictException` | **FAILED** | 否 | 失去 CHECK B 竞争，句柄过时 |
| 其他 `RuntimeException` / `Error` | **FAILED** | 否 | 执行失败，保持 M4 语义 |
| `VirtualMachineError` / `ThreadDeath` | (传播) | N/A | JVM 致命，不设置状态 |

**关键原则:**
1. **策略有效性** 驱动状态转换
2. **Checkpoint 是权威的**，不是本地对象
3. **本地 FAILED** ≠ 全局进程失败
4. **WAITING** 仅当相同策略/版本仍可用

---

## 结果

### ✅ **PHASE 6: GO**

**成就:**

1. ✅ **本地异常生命周期已定义** - 基于策略有效性
2. ✅ **可重试语义已实现** - ResumePreparationException → WAITING
3. ✅ **终态失败语义已明确** - 过时/缺失/冲突句柄 → FAILED
4. ✅ **M4 回归已验证** - 所有现有行为不变
5. ✅ **完整测试覆盖** - 11 个新测试，全部通过
6. ✅ **无后端更改** - 所有更改在 DefaultAgentProcess 中

**已知限制:**

⚠️ **ChatMemory Post-CHECK-B 失败:** 如果 ChatMemory 写入在 CHECK B 提交后失败，本地句柄可能变为 FAILED，即使 checkpoint 已提交完成。这是记录的限制，因为解决它需要新的后端元数据或异常类型（超出 Phase 6 范围）。Checkpoint 仍然是权威的。

**准备 Phase 7（不在此范围内）。**

---

**停止 — Phase 6 成功完成。M5-T4 Phase 1-6 完成且已验证。**
