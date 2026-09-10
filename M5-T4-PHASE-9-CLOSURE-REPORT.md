# M5-T4 Phase 9 Memory/Evidence/Governance System Regression Closure Report

**日期:** 2026-09-09

**目标:** 对 ChatMemory/Evidence/Governance 三个关键系统进行系统回归测试

**结果:** ✅ **PHASE 9: GO**

---

## A. 测试的生命周期

### 完整流程

```
Initial Execution
  → Model emits Tool A
  → Governance: REQUIRE_APPROVAL
  → Suspend with CP v1 (pending Tool A)

Resume v1 APPROVED
  → Execute Tool A
  → Capture Evidence A
  → Model emits Tool B
  → Governance: REQUIRE_APPROVAL
  → Re-suspend with CP v2 (pending Tool B)

Resume v2 APPROVED
  → Execute Tool B
  → Capture Evidence B
  → Model completes
  → Checkpoint deleted
```

### 三个系统并行验证

在每个生命周期点验证：
1. **ChatMemory:** 用户消息计数、最终 Assistant 存在性
2. **Evidence:** 累积列表、无重复
3. **Governance:** 每个工具的治理调用计数

---

## B. ChatMemory 语义

### 初始暂停后 (CP v1)

**状态:**
```
History User + History Assistant + User Request
```

**验证:**
```java
userCount >= 2  ✅ (History + Request)
lastAssistantText != "Final answer"  ✅ (无最终答案)
```

### 重新暂停后 (CP v2)

**状态:**
```
History User + History Assistant + User Request
(仍然没有最终 Assistant)
```

**验证:**
```java
userCount == userCountAfterSuspend  ✅ (不变)
lastAssistantText != "Final answer"  ✅ (仍无最终答案)
```

### 完成后

**状态:**
```
History User + History Assistant + User Request + Final Assistant
```

**验证:**
```java
userCount == userCountAfterSuspend  ✅ (不变)
lastAssistantText == "Final answer"  ✅ (最终答案存在)

finalAnswerCount == 1  ✅ (恰好一次)
```

### 关键不变量

✅ **用户消息不在暂停/恢复期间重复**
✅ **最终 Assistant 仅在完成时写入**
✅ **最终 Assistant 恰好一次**

---

## C. Evidence 语义

### CP v1 (初始暂停)

```java
accumulatedEvidences == []  ✅ (无证据)
```

### CP v2 (Tool A 执行后)

```java
evidences.size() == 1  ✅
evidences[0].source() == "tool:toolA"  ✅
```

### 完成 (Tool B 执行后)

```java
finalEvidence.size() == 2  ✅
finalEvidence[0].source() == "tool:toolA"  ✅
finalEvidence[1].source() == "tool:toolB"  ✅

// 无重复
toolACount == 1  ✅
toolBCount == 1  ✅
```

### 三集 Evidence (A → B → C)

**测试:** `threeEpisodes_evidenceABC()`

```java
CP v1: []
CP v2: [A]
CP v3: [A, B]
完成: [A, B, C]

aCount == 1  ✅
bCount == 1  ✅
cCount == 1  ✅
```

### 关键不变量

✅ **Evidence 单调累积**
✅ **无重复**
✅ **每个工具恰好一个 Evidence**

---

## D. 拒绝语义

### 测试: `rejection_noExecutionNoEvidence()`

**场景:**
```
Initial → Tool A pending → CP v1
Resume v1 REJECTED → Model continues → Complete
```

**验证:**
```java
toolAExecutionCount == 0  ✅ (未执行)
toolAEvidenceCount == 0  ✅ (无证据)

finalAssistant == "Completed after rejection"  ✅
finalAssistantCount == 1  ✅ (恰好一次)

checkpoint.isEmpty() == true  ✅ (已删除)
```

### 关键不变量

✅ **拒绝的工具不执行**
✅ **拒绝的工具不创建 Evidence**
✅ **拒绝后模型可以继续**
✅ **最终 Assistant 仍然正确持久化**

---

## E. Governance 语义

### 初始执行 (Tool A)

```java
governanceCounts.get("toolA") == 1  ✅ (治理)
governanceCounts.get("toolB") == 0  ✅ (未见)
```

### Resume v1 → Tool B

```java
// Tool A 不重新治理
governanceCounts.get("toolA") == 1  ✅ (仍然是 1)

// Tool B 治理 (新 ToolCall)
governanceCounts.get("toolB") == 1  ✅
```

### Resume v2 → Complete

```java
// Tool B 不重新治理
governanceCounts.get("toolB") == 1  ✅ (仍然是 1)
```

### 最终治理计数

```java
toolA total governance count == 1  ✅
toolB total governance count == 1  ✅

不是 2 次 (没有重新治理)
```

### 关键不变量

✅ **存储的待处理批次不重新治理**
✅ **新模型发出的 ToolCalls 正常治理**
✅ **每个工具恰好治理一次**

---

## F. 跨运行时内存连续性

### 来自 Phase 7: ThreeRuntimeRecoveryTest

**场景:**
```
Runtime A → suspend → CP v1
Runtime B → re-suspend → CP v2
Runtime C → complete
```

**验证 (已在 Phase 7 中):**
```java
// 相同 sessionId 贯穿
cp1.sessionId() == SESSION_ID  ✅
cp2.sessionId() == SESSION_ID  ✅

// 最终内存
userCount >= 2  ✅
assistantCount >= 2  ✅
lastMessage instanceof AssistantMessage  ✅
```

### 关键不变量

✅ **SessionId 跨运行时稳定**
✅ **共享 ChatMemory 保持连续性**
✅ **运行时替换不重复用户回合**

---

## G. 冲突内存行为

### 来自 Phase 8: ConcurrentDurableResumeTest

**测试:** `crossRuntimeCompletionRace_oneWinnerOneConflict()`

**验证 (已在 Phase 8 中):**
```java
completionCount == 1  ✅
conflictCount == 1  ✅

// 只有赢家写入最终 Assistant
assistantMessageCount == 1  ✅
```

### 关键不变量

✅ **只有 CHECK B 赢家写入最终 Assistant**
✅ **失败者不污染 ChatMemory**

---

## H. 生产文件更改

**总计:** **0** 个生产文件更改

**Phase 9 是纯验证阶段。**

✅ 所有 Memory/Evidence/Governance 语义已经正确实现

✅ 回归测试证实现有行为符合规范

---

## I. 测试创建/修改

### 创建 (1)

1. **DurableLifecycleRegressionTest.java** (`arctra-runtime-react`)
   - 3 个新的生命周期回归测试
   - 综合验证 Memory/Evidence/Governance

### 测试详细信息

| # | 测试方法 | 验证系统 |
|---|---------|---------|
| 1 | `fullLifecycle_memoryEvidenceGovernance` | 全部 3 个系统，完整 A→B 流程 |
| 2 | `rejection_noExecutionNoEvidence` | 拒绝语义 + Memory 完成 |
| 3 | `threeEpisodes_evidenceABC` | Evidence 单调性 A→B→C |

### 更新 (1)

2. **M5-T4-IMPLEMENTATION-GUIDE.md**
   - 添加 Phase 9 GO 状态
   - 添加最终 Memory/Evidence/Governance 语义

**总计:** 2 个文件

---

## J. 覆盖率映射 (14 个不变量)

### Memory 不变量 (4)

| # | 不变量 | 测试 | 状态 |
|---|--------|------|------|
| 1 | 初始暂停内存无最终 A | `fullLifecycle_memoryEvidenceGovernance` | ✅ |
| 2 | 重新暂停内存仍无最终 A | `fullLifecycle_memoryEvidenceGovernance` | ✅ |
| 3 | 完成写入最终 A 一次 | `fullLifecycle_memoryEvidenceGovernance` | ✅ |
| 4 | 跨恢复集无重复 U | `fullLifecycle_memoryEvidenceGovernance` | ✅ |

### Evidence 不变量 (4)

| # | 不变量 | 测试 | 状态 |
|---|--------|------|------|
| 5 | 批准的 Tool A Evidence 恰好一次 | `fullLifecycle_memoryEvidenceGovernance` | ✅ |
| 6 | 批准的 Tool B Evidence 恰好一次 | `fullLifecycle_memoryEvidenceGovernance` | ✅ |
| 7 | 拒绝的工具不创建 Evidence | `rejection_noExecutionNoEvidence` | ✅ |
| 8 | 历史 Evidence 不重复 | `threeEpisodes_evidenceABC` | ✅ |

### Governance 不变量 (3)

| # | 不变量 | 测试 | 状态 |
|---|--------|------|------|
| 9 | 存储的 Tool A 不重新治理 | `fullLifecycle_memoryEvidenceGovernance` | ✅ |
| 10 | 新 Tool B 治理 | `fullLifecycle_memoryEvidenceGovernance` | ✅ |
| 11 | 存储的 Tool B 不重新治理 | `fullLifecycle_memoryEvidenceGovernance` | ✅ |

### 跨运行时/冲突不变量 (3)

| # | 不变量 | 测试 | 状态 |
|---|--------|------|------|
| 12 | 跨运行时会话连续性 | Phase 7: `ThreeRuntimeRecoveryTest` | ✅ |
| 13 | 冲突失败者不写入最终 A | Phase 8: `ConcurrentDurableResumeTest` | ✅ |
| 14 | 完成后最终 checkpoint 已删除 | `fullLifecycle_memoryEvidenceGovernance` | ✅ |

**总覆盖率:** 14/14 (100%) ✅

---

## K. 精确 Maven 结果

### 命令 1: `mvn test -Dtest=DurableLifecycleRegressionTest -pl arctra-runtime-react`
- **退出代码:** 0
- **运行的测试:** 3
- **失败:** 0
- **错误:** 0
- **跳过:** 0
- **构建状态:** BUILD SUCCESS
- **时间:** 3.972 秒

### 命令 2: `mvn test -pl arctra-core`
- **退出代码:** 0
- **运行的测试:** 136
- **失败:** 0
- **错误:** 0
- **跳过:** 0
- **构建状态:** BUILD SUCCESS

### 命令 3: `mvn test -pl arctra-runtime-react`
- **退出代码:** 0
- **运行的测试:** 79 (+3 从 Phase 8 的 76)
- **失败:** 0
- **错误:** 0
- **跳过:** 6
- **构建状态:** BUILD SUCCESS

### 命令 4: `mvn test` (完整 reactor)
- **退出代码:** 0
- **Core:** 136 测试，0 失败
- **Runtime-react:** 79 测试，0 失败，6 跳过
- **Examples:** 22 测试，0 失败，9 跳过
- **总测试:** 237 (+3 从 Phase 8 的 234)
- **总失败:** 0
- **总错误:** 0
- **总跳过:** 15
- **构建状态:** BUILD SUCCESS
- **总时间:** 6.342 秒

### 命令 5: `mvn verify`
- **退出代码:** 0
- **构建状态:** BUILD SUCCESS
- **总时间:** 7.016 秒
- **所有模块:** 通过

---

## L. 实现指南更新

**文件:** `M5-T4-IMPLEMENTATION-GUIDE.md`

**更新:**

```markdown
**Implementation Progress:**
- Phase 9: ✅ GO (Memory/Evidence/Governance regression)
- Phase 10: ⏳ PENDING

**Phase 9 Summary:**
- **Complete Lifecycle Regression:** Initial → re-suspend → complete across 3 systems
- **3 New Lifecycle Tests:** Full Memory/Evidence/Governance validation
- **ChatMemory Semantics:** H+U during suspension, H+U+A only at completion, no duplicates
- **Evidence Semantics:** Monotonic [A]→[A,B]→[A,B,C], no duplication, exactly once each
- **Governance Semantics:** Stored batches not re-governed, new ToolCalls governed normally
- **Rejection Verified:** No execution, no evidence for rejected tools
- **No Production Changes:** All semantics already correct
```

✅ **已更新**

---

## M. 决定

### Phase 9 Gate 检查表

- [x] **无重复用户消息** ✅
- [x] **无过早的最终 Assistant** ✅
- [x] **最终 Assistant 恰好写入一次** ✅
- [x] **Evidence 单调** ✅
- [x] **Evidence 无重复** ✅
- [x] **拒绝的调用不创建执行 Evidence** ✅
- [x] **存储的批次不重新治理** ✅
- [x] **新模型 ToolCalls 正常治理** ✅
- [x] **跨运行时会话连续性保留** ✅
- [x] **冲突失败者不能写入最终 Assistant** ✅
- [x] **没有不必要的生产更改** ✅
- [x] **Runtime-react 测试通过** ✅
- [x] **Core 测试通过** ✅
- [x] **Reactor 测试通过** ✅
- [x] **Verify 退出代码 = 0** ✅
- [x] **实现指南已更新** ✅

**所有 gate 通过:** 16/16 ✅

---

## N. 系统语义摘要

### ChatMemory 最终语义

**生命周期:**
```
初始: H + U
暂停: H + U (无最终 A)
重新暂停: H + U (仍无最终 A)
完成: H + U + 最终 A (恰好一次)
```

**不变量:**
- 用户消息在暂停/恢复期间不重复
- 最终 Assistant 仅在完成时写入
- 最终 Assistant 恰好一次

### Evidence 最终语义

**累积:**
```
[] → [A] → [A, B] → [A, B, C]
```

**不变量:**
- 单调累积
- 无重复
- 每个执行的工具恰好一个 Evidence
- 拒绝的工具：零 Evidence

### Governance 最终语义

**边界:**
```
存储的待处理批次: 不重新治理
新模型发出的 ToolCalls: 正常治理
```

**计数:**
```
Tool A: 治理 1 次 (初始)
Tool B: 治理 1 次 (重新暂停时新)

不是 2 次每个
```

---

## 结果

### ✅ **PHASE 9: GO**

**成就:**

1. ✅ **完整生命周期回归已验证**
   - 初始 → 重新暂停 → 完成
   - 三个系统并行验证

2. ✅ **ChatMemory 语义已锁定**
   - 无重复用户消息
   - 最终 Assistant 仅在完成时
   - 恰好一次

3. ✅ **Evidence 语义已锁定**
   - 单调累积 [A] → [A,B] → [A,B,C]
   - 无重复
   - 拒绝：零 Evidence

4. ✅ **Governance 语义已锁定**
   - 存储的批次：不重新治理
   - 新 ToolCalls：正常治理
   - 每个工具恰好治理一次

5. ✅ **跨运行时/冲突行为已验证**
   - 会话连续性保留
   - 只有赢家写入最终 Assistant

6. ✅ **14/14 不变量映射到通过的测试**

7. ✅ **零生产更改 - 所有语义已正确**

**Phase 1-9 完整且已验证。M5-T4 核心持久能力完成。**

**准备 Phase 10（不在此范围内）。**

---

**停止 — Phase 9 成功完成。M5-T4 Phase 1-9 完成且已验证。**
