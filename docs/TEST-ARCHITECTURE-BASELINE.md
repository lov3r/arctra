# TEST ARCHITECTURE BASELINE

**建立时间**: 2026-09-16  
**模块**: arctra-runtime-react  
**状态**: V1 Freeze  
**测试状态**: ✅ 207 pass, 0 failures, 13 skipped

---

## 目的

本文档记录 Arctra **TEST ARCHITECTURE BASELINE**，用于监控 Production API 演进对测试的 blast radius 影响。

当 Production API 变化导致指标超过阈值时，触发完整 **TEST ARCHITECTURE STABILIZATION**。

---

## Blast Radius 指标

### 1. 构造函数调用点

| 组件 | 测试调用点 | 参数数 | 风险等级 | 阈值 |
|------|-----------|--------|----------|------|
| **SpringAiToolCallingEngine** | **84** | 8 | 🔴 CRITICAL | > 100 或增加第 9 参数 |
| **DefaultAgentRuntime** | **34** | 1 | 🟡 HIGH | > 50 或增加第 2 参数 |
| **DurableResumeCoordinator** | **16** | 4 | 🟢 MEDIUM | > 30 |

**当前最高风险**: SpringAiToolCallingEngine（84 调用点，8 参数）

#### SpringAiToolCallingEngine 构造函数

```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,                    // 1
    List<ToolCallback> tools,               // 2
    ChatMemory chatMemory,                  // 3
    ToolGovernancePolicy governancePolicy,  // 4
    CheckpointStore checkpointStore,        // 5
    RuntimeBindingResolver bindingResolver, // 6
    String runtimeBindingKey,               // 7
    ExecutionLedger executionLedger)        // 8 (M6 新增)
```

**典型测试模式**:
```java
// 大量测试传递 null 给不关心的依赖
new SpringAiToolCallingEngine(
    chatModel,
    tools,
    chatMemory,
    policy,
    null,  // checkpointStore
    null,  // bindingResolver
    null,  // runtimeBindingKey
    null   // executionLedger
)
```

**风险**: 下次增加参数（如 RecoveryCoordinator）→ **84 个测试同时 compile failure**

#### DefaultAgentRuntime 构造函数

```java
public DefaultAgentRuntime(
    AgentExecutionEngine engine)  // 1
```

**风险**: 虽然仅 1 参数，但 34 调用点，如果增加 policy/ledger/observer → 34 个测试修改

#### DurableResumeCoordinator 构造函数

```java
public DurableResumeCoordinator(
    CheckpointStore checkpointStore,
    RuntimeBindingResolver runtimeBindingResolver,
    ResumedExecutionHandler resumedExecutionHandler,
    InvocationStateStore invocationStateStore)
```

**当前风险**: 低（16 调用点，4 参数相对稳定）

---

### 2. 方法参数列表

| 方法 | 调用点 | 参数数 | 可见性 | 风险等级 | 阈值 |
|------|--------|--------|--------|----------|------|
| **recordInvocationIntent** | **58** | 3 | public | 🟢 LOW | >= 5 参数 |
| **executeApprovedBatch** | **9** | 6 | private | 🟡 MEDIUM | >= 7 参数 |

#### recordInvocationIntent

```java
recordInvocationIntent(
    String processId,
    String operationId,
    String attemptId)
```

- **调用点**: 58 (production ~15, test ~43)
- **最近演进**: M6-T5 新增 attemptId
- **语义稳定性**: ✅ 高（三者共同表达 physical invocation identity）
- **未来演进可能**: ❌ 低
- **推荐**: 保持监控，无需 Parameter Object

#### executeApprovedBatchInternal (private)

```java
executeApprovedBatchInternal(
    List<PendingToolCall> pendingBatch,
    List<Message> conversationHistory,
    List<Evidence> checkpointEvidences,
    List<Evidence> newEvidences,
    ToolObservationContext baseObservationContext,
    List<RecoveryClassificationResult> classifications)
```

- **调用点**: 9 (全部 test)
- **可见性**: private
- **最近演进**: M6-T5 新增 classifications
- **未来演进可能**: ⚠️ 中等（durable execution 可能携带更多元数据）
- **推荐**: ⚠️ 监控临界点（6 参数），如增加到 7 参数考虑 Parameter Object

---

### 3. 匿名实现

| Interface | 匿名实现数 | 位置 | 风险等级 | 阈值 |
|-----------|-----------|------|----------|------|
| **DurableExecutionEngine** | 1 | ExplicitRecoveryPathTest | 🟢 LOW | > 5 |
| **ResumedExecutionHandler** | 1 | ExplicitRecoveryPathTest | 🟢 LOW | > 5 |
| AgentExecutionEngine | 0 | - | 🟢 NONE | > 5 |
| InvocationStateStore | 0 | - | 🟢 NONE | > 5 |
| CheckpointStore | 0 | - | 🟢 NONE | > 5 |

**当前状态**: ✅ **极低，不构成 blast radius**

---

## 触发条件

### 🔴 强制触发（必须执行完整 Stabilization）

以下任一条件满足，**必须**执行完整 **TEST ARCHITECTURE STABILIZATION TRACK**：

1. **SpringAiToolCallingEngine**
   - 构造函数增加第 9 个参数
   - OR 测试调用点 > 100

2. **DefaultAgentRuntime**
   - 构造函数增加第 2 个参数
   - OR 测试调用点 > 50

3. **任何方法参数数量 >= 7**

4. **单次 API 变化导致 > 50 个测试 compile failure**

### 🟡 次要触发（评估是否执行）

5. **同一 interface 匿名实现 > 5**
6. **同一组件重复构造 > 20**（DurableResumeCoordinator 接近）
7. **DX 明显恶化**（开发者反馈测试修改困难）

---

## 监控流程

### 每次 Production API 修改后检查

```bash
#!/bin/bash
# Blast Radius Check Script

cd arctra-runtime-react

echo "=== TEST ARCHITECTURE BLAST RADIUS CHECK ==="
echo ""

echo "1. 构造函数调用点:"
echo "  SpringAiToolCallingEngine: $(grep -r 'new SpringAiToolCallingEngine(' src/test --include='*.java' | wc -l) (baseline: 84, threshold: 100)"
echo "  DefaultAgentRuntime: $(grep -r 'new DefaultAgentRuntime(' src/test --include='*.java' | wc -l) (baseline: 34, threshold: 50)"
echo "  DurableResumeCoordinator: $(grep -r 'new DurableResumeCoordinator(' src/test --include='*.java' | wc -l) (baseline: 16, threshold: 30)"
echo ""

echo "2. 方法调用点:"
echo "  recordInvocationIntent: $(grep -r 'recordInvocationIntent(' src/test --include='*.java' | wc -l) (baseline: 58)"
echo "  executeApprovedBatch: $(grep -r 'executeApprovedBatch' src/test --include='*.java' | wc -l) (baseline: 9)"
echo ""

echo "3. 匿名实现:"
echo "  DurableExecutionEngine: $(grep -r 'new DurableExecutionEngine()' src/test --include='*.java' | wc -l) (baseline: 1, threshold: 5)"
echo "  ResumedExecutionHandler: $(grep -r 'new ResumedExecutionHandler()' src/test --include='*.java' | wc -l) (baseline: 1, threshold: 5)"
echo ""

cd ..
echo "4. 运行测试:"
./mvnw clean verify -q

echo ""
echo "5. 对照 baseline: docs/TEST-ARCHITECTURE-BASELINE.md"
echo "6. 触发条件: docs/TEST-ARCHITECTURE-BASELINE.md#触发条件"
```

### 检查清单

- [ ] 构造调用点是否超过阈值？
- [ ] 方法参数是否 >= 7？
- [ ] 匿名实现是否 > 5？
- [ ] 测试 compile failures 是否 > 50？
- [ ] 是否触发强制条件？
- [ ] 如果是，执行 **ARCTRA-TEST-ARCHITECTURE-STABILIZATION-TRACK**

---

## 组件详细分析

### SpringAiToolCallingEngine (🔴 CRITICAL)

**当前状态**:
- 调用点: **84** 🔴
- 参数: 8
- Null-heavy 模式: ✅ 大量存在

**历史演进**:
- M1: 初始实现（4 参数）
- M2: 增加 ChatMemory
- M5: 增加 CheckpointStore, RuntimeBindingResolver, runtimeBindingKey
- M6: 增加 ExecutionLedger

**未来可能增加**:
- RecoveryCoordinator（M6+）
- PolicyEngine
- ObservabilityContext
- Budget/Timeout Context

**当前无 Harness 的影响**:
- ✅ 每次参数增加 → **84 个测试同时修改**
- ✅ 大量 null 传递 → 测试意图不清晰
- ✅ 构造复杂度高 → 测试设置困难

**推荐解决方案**（触发后）:
```java
// 替代当前的 84 个直接构造
SpringAiToolCallingEngineTestHarness harness =
    SpringAiToolCallingEngineTestHarness.builder()
        .chatModel(chatModel)
        .tools(tools)
        .build();  // 其他使用 safe defaults

SpringAiToolCallingEngine engine = harness.engine();
```

---

### DefaultAgentRuntime (🟡 HIGH)

**当前状态**:
- 调用点: **34** 🟡
- 参数: 1

**简单但调用点多**:
- 虽然只有 1 参数，但 34 调用点
- 如果增加 policy/ledger/observer → 34 个测试修改

**未来可能增加**:
- ExecutionPolicy
- ExecutionLedger
- RuntimeObserver
- Budget/Timeout

**当前可接受理由**:
- ✅ 仅 1 参数，修改成本相对低
- ✅ 无明确演进计划
- ⚠️ 但 34 调用点接近监控阈值

---

### DurableResumeCoordinator (🟢 MEDIUM)

**当前状态**:
- 调用点: **16** 🟢
- 参数: 4

**相对稳定**:
- ✅ 调用点少（16）
- ✅ 4 参数相对合理
- ✅ M6 后趋于稳定

**未来可能增加**:
- Recovery Control Plane 依赖
- Policy/Governance

**当前评估**: 风险低，继续监控即可

---

## 历史记录

| 日期 | SpringAiToolCallingEngine | DefaultAgentRuntime | DurableResumeCoordinator | 备注 |
|------|--------------------------|--------------------|-----------------------|------|
| 2026-09-16 | 84 调用点 (8 参数) | 34 调用点 (1 参数) | 16 调用点 (4 参数) | Baseline 建立 |

**未来**: 每次 Production API 变化后更新此表

---

## 决策参考

详见：[TEST-ARCHITECTURE-STABILIZATION-DECISION.md](./TEST-ARCHITECTURE-STABILIZATION-DECISION.md)

**当前决策**: 方案 A - 最小化干预（监控 + Baseline）

**执行指南**: [ARCTRA-TEST-ARCHITECTURE-STABILIZATION-TRACK](../ARCTRA-TEST-ARCHITECTURE-STABILIZATION-TRACK.md)

---

## 附录：快速命令

```bash
# 快速检查 blast radius
cd arctra-runtime-react && \
  echo "SpringAiToolCallingEngine: $(grep -r 'new SpringAiToolCallingEngine(' src/test --include='*.java' | wc -l)" && \
  echo "DefaultAgentRuntime: $(grep -r 'new DefaultAgentRuntime(' src/test --include='*.java' | wc -l)"

# 查看典型构造模式
grep -A 10 "new SpringAiToolCallingEngine(" src/test/java -r | head -30

# 运行测试
cd .. && ./mvnw clean verify
```
