# TEST ARCHITECTURE BASELINE

**记录时间**: 2026-09-16  
**关联**: M6-T5 完成后  
**状态**: 所有测试通过 (207 tests, 0 failures, 13 skipped)

---

## 目的

本文档记录 Arctra V1 测试架构的 **blast radius baseline**，用于：

1. 监控未来 production API 演进对测试的影响范围
2. 触发 Test Architecture Stabilization 的决策依据
3. Architecture Fitness Rules 的参考基准

---

## Blast Radius 基线指标

### 构造函数调用点统计

| 组件 | 测试构造调用次数 | 构造函数参数数量 | 风险等级 | 监控阈值 |
|------|-----------------|-----------------|---------|---------|
| SpringAiToolCallingEngine | 84 | 4/7/8 (多重载) | CRITICAL | 100 |
| DefaultAgentRuntime | 34 | 1 | HIGH | 50 |
| DurableResumeCoordinator | 16 | (待确认) | MEDIUM | 30 |

### 方法调用点统计

| 方法 | 调用次数 | 参数数量 | 演进风险 | 状态 |
|------|---------|---------|---------|------|
| recordInvocationIntent | 58 | 3 | LOW | 已稳定 |
| ProtocolReconstructor.executeApprovedBatch | 9 | 6 | MEDIUM | 监控中 |

### 匿名实现统计

| Interface | 匿名实现数量 | 评估 |
|-----------|-------------|------|
| DurableExecutionEngine | 1 | 不需要 Shared Stub |
| ResumedExecutionHandler | 1 | 不需要 Shared Stub |

---

## SpringAiToolCallingEngine 详细分析

### 当前构造函数签名

```java
// 权威构造函数 (M5-T4)
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger)  // 8 parameters
```

### 演进历史

- **M5-T4**: 增加 durable suspension (checkpointStore, bindingResolver, runtimeBindingKey)
- **M5-T4**: 增加 executionLedger
- **M6-T2B.1**: 内部使用 ExecutionEventListener (通过 ExecutionLedger)
- **M6-T4E**: 内部创建 InvocationStateStore
- **M6-T4C**: 内部创建 InvocationRecoveryClassifier
- **M6-T2.5A-R3/R4**: 内部创建 SpringAiResumedExecutionHandler 和 DurableResumeCoordinator

### 风险评估

**CRITICAL** - 84 个测试调用点

**潜在演进方向**:
- Recovery policy 配置
- 更多 governance 选项
- 分布式协调依赖
- 性能监控组件

**触发 Test Harness 条件**:
- 构造函数增加第 9 个参数
- 或测试调用点超过 100

---

## DefaultAgentRuntime 详细分析

### 当前构造函数签名

```java
public DefaultAgentRuntime(AgentExecutionEngine engine)  // 1 parameter
```

### 风险评估

**HIGH** - 34 个测试调用点

虽然当前只有 1 个参数，但作为核心 runtime，未来可能增加：
- ProcessFactory
- 全局 ExecutionLedger
- RuntimeConfiguration
- 分布式相关依赖

**触发 Test Harness 条件**:
- 构造函数增加参数
- 或测试调用点超过 50

---

## ProtocolReconstructor.executeApprovedBatch 详细分析

### 当前方法签名

```java
List<Message> executeApprovedBatch(
    List<PendingToolCall> pendingBatch,
    List<Message> conversationHistory,
    List<Evidence> checkpointEvidences,
    List<Evidence> newEvidences,
    ToolObservationContext baseObservationContext,
    List<RecoveryClassificationResult> classifications)  // 6 parameters
```

### 演进历史

- **M6-T5**: 新增 `classifications` 参数

### 风险评估

**MEDIUM-LOW** - 9 个调用点，但 6 个参数已处于临界点

**参数语义分组**:
- Input: `pendingBatch`, `conversationHistory`, `checkpointEvidences`, `newEvidences`
- Metadata: `baseObservationContext`, `classifications`

**触发 Parameter Object 条件**:
- 增加第 7 个参数
- 或参数继续演进（如 recovery execution plan）

---

## recordInvocationIntent 详细分析

### 当前方法签名

```java
void recordInvocationIntent(
    String processId,
    String operationId,
    String attemptId)  // 3 parameters
```

### 演进历史

- **M6-T5**: 从 2 参数演进到 3 参数（新增 attemptId）
- 刚刚完成大规模迁移

### 风险评估

**LOW (已稳定)** - 58 个调用点，但不需要 Parameter Object

**理由**:
- 3 个参数语义清晰且独立
- 每个参数都是不可合并的身份标识
- 不太可能继续演进（已经是物理 attempt identity）

---

## 稳定化决策

### 方案选择: **方案 A - 最小化干预**

**理由**:
1. 当前所有测试编译通过、运行通过
2. M6-T5 刚刚完成一轮大规模迁移
3. 架构处于 V1 freeze 阶段
4. 符合架构宪法："不为'以后可能用到'提前创建抽象"

### 立即行动

- [x] 完成 Source Audit
- [x] 保存本 Baseline 文档
- [ ] (可选) 添加 Architecture Fitness Rule

### 延期到真实需求触发

以下稳定化措施**不在当前执行**，等待真实变化触发：

- SpringAiToolCallingEngine Test Harness
- DefaultAgentRuntime Test Harness
- Parameter Objects (如 ApprovedBatchExecutionRequest)
- Test Builders
- Shared Test Doubles

---

## 触发条件

**当以下任一情况发生时，立即执行 Test Architecture Stabilization**:

### 关键触发器

1. **SpringAiToolCallingEngine 构造函数变化**
   - 增加第 9 个参数
   - 或测试调用点 > 100

2. **DefaultAgentRuntime 构造函数变化**
   - 增加第 2 个参数
   - 或测试调用点 > 50

3. **ProtocolReconstructor.executeApprovedBatch 演进**
   - 增加第 7 个参数
   - 或调用点 > 15

4. **任何方法参数数量 >= 7**
   - 触发 Parameter Object 评估

### 次要触发器

5. 匿名实现数量 > 5（同一 interface）
6. 重复构造模式 > 20（同一组件）
7. Production API 变化导致 > 50 个测试编译失败

---

## 架构宪法对照

根据 **CLAUDE.md 第十三条: V1 最终工程原则**

> "架构进入 Freeze-by-default 状态：除非真实代码、测试或 Vertical Slice 暴露问题，否则不继续扩展架构。"
>
> "不为'以后可能用到'提前创建模块、抽象、依赖或公共 API。"

**当前决策符合宪法原则**:
- ✅ 当前没有"真实代码、测试暴露的问题"
- ✅ 所有测试通过
- ✅ 不主动创建测试基础设施
- ✅ 记录 baseline 和触发条件
- ✅ 等待真实变化再响应

---

## 监控检查清单

### 每次 Production API 变化后检查

```bash
# 1. 检查构造函数调用点
grep -r "new SpringAiToolCallingEngine(" src/test --include="*.java" | wc -l
grep -r "new DefaultAgentRuntime(" src/test --include="*.java" | wc -l

# 2. 检查方法参数数量（手动检查最新签名）
# 如果任何方法 >= 7 参数，触发评估

# 3. 检查测试编译状态
./mvnw test-compile

# 4. 检查测试运行状态
./mvnw verify
```

### 对照 Baseline

将新的指标与本文档中的基线对比：

- 构造调用增长 > 20% → 评估影响
- 方法参数增加 → 评估 Parameter Object
- 匿名实现增长 > 3 → 评估 Shared Stub

---

## 相关文档

- `CLAUDE.md` - 架构宪法
- `docs/ARCHITECTURE-V7.md` - 架构文档
- `docs/M6-T5-*.md` - M6-T5 相关文档
- `ARCTRA-TEST-ARCHITECTURE-STABILIZATION-TRACK.md` - 完整稳定化指南

---

## 审计历史

| 日期 | 事件 | Blast Radius 变化 |
|------|------|------------------|
| 2026-09-16 | 建立 Baseline (M6-T5 完成后) | - |

---

**维护策略**: 本文档应在每次重大 Production API 变化后更新。
