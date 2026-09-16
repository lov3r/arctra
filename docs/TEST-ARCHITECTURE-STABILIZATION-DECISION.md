# TEST ARCHITECTURE STABILIZATION - 决策记录

**日期**: 2026-09-16  
**触发**: M6-T5 完成后的测试架构评估  
**决策者**: Architecture Review  
**状态**: ✅ APPROVED - 最小化干预方案

---

## 背景

M6-T5 开发过程中暴露出测试架构的潜在 blast radius 问题：

1. **SpringAiToolCallingEngine**: 84 个测试构造调用点，8 参数构造函数
2. **DefaultAgentRuntime**: 34 个测试构造调用点
3. **ProtocolReconstructor.executeApprovedBatch**: 6 个参数，临界状态

原始 Track 文档提出完整的测试架构稳定化方案，包括：
- Shared Test Doubles
- Parameter Objects
- Test Builders
- Subsystem Test Harness

---

## 问题陈述

需要回答的核心问题：

> **现在是否应该执行完整的 Test Architecture Stabilization？**

---

## 实际调查结果

### 当前状态

```
测试编译: ✅ PASS
测试运行: ✅ PASS (207 tests, 0 failures, 13 skipped)
Production API 演进计划: ❌ 无
最近 API 变化: M6-T5 刚完成（recordInvocationIntent 3参数迁移）
匿名实现数量: 1-2 个（非常少）
```

### Blast Radius 量化

| 组件 | 调用点 | 参数数 | 风险 | 近期变化 |
|------|-------|--------|------|---------|
| SpringAiToolCallingEngine | 84 | 8 | CRITICAL | 稳定 |
| DefaultAgentRuntime | 34 | 1 | HIGH | 稳定 |
| ProtocolReconstructor | 9 | 6 | MEDIUM | M6-T5新增1参数 |
| recordInvocationIntent | 58 | 3 | LOW | M6-T5刚迁移完 |

### 关键发现

1. ✅ **没有活跃的 API 演进**
2. ✅ **所有测试都通过**
3. ✅ **M6-T5 刚完成大规模修复**
4. ⚠️ **存在潜在 blast radius，但未实际触发**

---

## 架构宪法对照

### CLAUDE.md 第十三条: V1 最终工程原则

> **"用实现证明架构"**
>
> "架构进入 Freeze-by-default 状态：除非真实代码、测试或 Vertical Slice 暴露问题，否则不继续扩展架构。"
>
> **"不为'以后可能用到'提前创建模块、抽象、依赖或公共 API。"**
>
> "新增抽象、Module、Interface 前必须回答：
> - 现在谁在使用它？
> - 如果不增加它，哪个当前需求无法正确实现？"

### 当前情况对照

| 宪法原则 | 当前状态 | 符合? |
|---------|---------|------|
| 真实问题触发 | 没有实际 compile failure 问题 | ❌ 不符合触发条件 |
| 不为未来预留 | Test Harness 是为"可能的未来变化" | ❌ 违反原则 |
| 当前需求驱动 | 当前测试已全部通过 | ✅ 无需改动 |
| V1 Freeze | 架构处于 freeze 状态 | ✅ 应保持稳定 |
| 可证明性 | 没有真实证据证明需要 Harness | ❌ 不符合 |

**结论**: 主动建立 Test Harness **违反架构宪法**。

---

## Track 文档本身的判断标准

原始 Track 文档中的核心目标：

> "目标不是让当前 M6-T5 测试'勉强编译'。
>
> 目标是建立稳定的 test boundary，使未来 production evolution 的修改范围显著收敛。"

关键判断：

> "而不是：任何 production API 演进 → 几十/上百个测试同时 compile failure"

### 当前实际情况

- ✅ **没有 production API 演进计划**
- ✅ **没有"几十/上百个测试同时 compile failure"的现实问题**
- ✅ **M6-T5 已经完成大规模修复**

**因此**: 暂不符合 Track 执行的真实条件。

---

## 决策选项

### 方案 A: 最小化干预 ✅ SELECTED

**行动**:
1. ✅ 完成 Source Audit
2. ✅ 记录 blast radius baseline
3. ✅ 建立触发条件和监控机制
4. ✅ 继续 M6-T5 closure
5. ⏸️ 延期所有测试基础设施建设

**理由**:
- 符合架构宪法："不为未来预留"
- 当前没有真实痛点
- V1 处于 freeze 状态
- M6-T5 刚完成修复，应保持稳定
- 监控机制足以捕获未来变化

**优势**:
- ✅ 不引入新的测试复杂度
- ✅ 保持当前工作状态
- ✅ 遵守架构纪律
- ✅ 降低过度工程化风险

**风险**:
- ⚠️ 未来 API 变化时需要大规模修改
- ✅ 但通过监控可以及时响应

---

### 方案 B: 主动稳定化 ❌ REJECTED

**行动**:
1. 建立 SpringAiToolCallingEngineBuilder (test-only)
2. 建立 DefaultAgentRuntimeBuilder (test-only)
3. 建立 Parameter Objects
4. 建立 Test Harness
5. 大规模迁移测试

**理由**:
- 提前防御未来 API 变化

**缺点**:
- ❌ 违反架构宪法："不为未来预留"
- ❌ 引入新的测试基础设施复杂度
- ❌ 当前没有真实需求
- ❌ 过度工程化
- ❌ 需要额外维护成本

**拒绝原因**: **违反 CLAUDE.md 架构原则**

---

## 最终决策

### ✅ 采用方案 A: 最小化干预

### 立即完成 (本次)

- [x] Source Audit
- [x] 保存 TEST-ARCHITECTURE-BASELINE.md
- [x] 保存本决策文档
- [x] 继续 M6-T5 closure

### 明确延期 (等待真实触发)

- [ ] SpringAiToolCallingEngine Test Harness
- [ ] DefaultAgentRuntime Test Harness
- [ ] Parameter Objects (ApprovedBatchExecutionRequest)
- [ ] Test Builders
- [ ] Shared Test Doubles

---

## 触发条件

**当以下任一情况发生时，重新评估并执行 Test Architecture Stabilization**:

### 强制触发条件

1. **SpringAiToolCallingEngine 构造函数增加第 9 个参数**
   - 或测试调用点超过 100

2. **DefaultAgentRuntime 构造函数增加参数**
   - 或测试调用点超过 50

3. **ProtocolReconstructor.executeApprovedBatch 增加第 7 个参数**
   - 或调用点超过 15

4. **任何方法参数数量 >= 7**
   - 立即触发 Parameter Object 评估

### 次要触发条件

5. 单次 Production API 变化导致 > 50 个测试 compile failure
6. 同一 interface 匿名实现 > 5 个
7. 同一组件重复构造模式 > 20 个

---

## 监控机制

### 手动检查（每次 Production API 变化后）

```bash
# 1. 构造函数调用点统计
grep -r "new SpringAiToolCallingEngine(" src/test --include="*.java" | wc -l
grep -r "new DefaultAgentRuntime(" src/test --include="*.java" | wc -l

# 2. 测试编译和运行
./mvnw clean verify

# 3. 对照 baseline
# 查看 docs/TEST-ARCHITECTURE-BASELINE.md
```

### (可选) Architecture Fitness Rule

可以在 `ArchitectureTest.java` 中添加自动化检查：

```java
@Test
void test_blast_radius_should_not_exceed_baseline() {
    long engineConstructions = countConstructorCalls("SpringAiToolCallingEngine");
    assertThat(engineConstructions).isLessThanOrEqualTo(100); // baseline: 84
    
    long runtimeConstructions = countConstructorCalls("DefaultAgentRuntime");
    assertThat(runtimeConstructions).isLessThanOrEqualTo(50); // baseline: 34
}
```

**决定**: 暂不实现，保持最小化干预。如果未来触发条件满足时再添加。

---

## 与 Track 文档的关系

本决策**不是拒绝 Track 文档的价值**。

Track 文档提供了：
- ✅ 完整的测试架构稳定化方法论
- ✅ 系统化的 blast radius 分析框架
- ✅ 清晰的实施步骤和模式

**本决策只是判断**:
- 当前不满足执行条件
- 应该延期到真实需求触发
- 监控机制足以保护架构

Track 文档仍然是**未来执行时的权威指南**。

---

## 决策的架构影响

### 短期 (V1)

- ✅ 保持测试架构简单
- ✅ 降低维护成本
- ✅ 符合 V1 freeze 原则

### 中期 (触发时)

- ⚠️ 如果 API 变化，需要批量修改测试
- ✅ 但有 baseline 和 Track 指南支持
- ✅ 监控机制可及时发现

### 长期

- ✅ 架构演进基于真实需求
- ✅ 避免过度工程化
- ✅ 测试基础设施只在必要时引入

---

## 关键指标记录

### M6-T5 完成后 (2026-09-16)

```
总测试数: 207
失败数: 0
跳过数: 13
SpringAiToolCallingEngine 构造: 84
DefaultAgentRuntime 构造: 34
DurableResumeCoordinator 构造: 16
recordInvocationIntent 调用: 58
executeApprovedBatch 调用: 9
匿名 DurableExecutionEngine: 1
匿名 ResumedExecutionHandler: 1
```

---

## 批准和责任

| 角色 | 决策 | 理由 |
|------|------|------|
| Architecture Review | ✅ APPROVE 方案 A | 符合架构宪法，当前无真实需求 |
| Test Strategy | ✅ APPROVE 监控机制 | Baseline 和触发条件清晰 |
| V1 Delivery | ✅ APPROVE 最小化干预 | 保持 V1 scope 和 stability |

---

## 后续行动

### 立即 (本次完成)

- [x] 保存本决策文档
- [x] 保存 TEST-ARCHITECTURE-BASELINE.md
- [ ] 更新 CURRENT-STATE.md
- [ ] 继续 M6-T5 closure

### 持续监控

- [ ] 每次 Production API 变化后检查 blast radius
- [ ] 对照 baseline 评估是否触发
- [ ] 必要时执行完整 Test Architecture Stabilization

---

## 相关文档

- `docs/TEST-ARCHITECTURE-BASELINE.md` - Blast radius 基线
- `ARCTRA-TEST-ARCHITECTURE-STABILIZATION-TRACK.md` - 完整稳定化指南
- `CLAUDE.md` - 架构宪法
- `docs/ARCHITECTURE-V7.md` - 架构文档

---

**签署**: Architecture Team  
**日期**: 2026-09-16  
**版本**: 1.0
