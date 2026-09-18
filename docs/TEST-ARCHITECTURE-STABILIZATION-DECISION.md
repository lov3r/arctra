# TEST ARCHITECTURE STABILIZATION - 架构决策

## 决策时间
2026-09-16

## 决策背景

在 M6-T5 完成包重构后，执行了完整的 **TEST ARCHITECTURE STABILIZATION SOURCE AUDIT**，发现：

### 关键数据

| 组件 | 测试调用点 | 构造参数 | 风险等级 |
|------|-----------|----------|----------|
| SpringAiToolCallingEngine | **84** | 8 | 🔴 CRITICAL |
| DefaultAgentRuntime | 34 | 1 | 🟡 HIGH |
| DurableResumeCoordinator | 16 | 4 | 🟢 MEDIUM |
| executeApprovedBatch | 9 | 6 (private) | 🟡 MEDIUM |
| recordInvocationIntent | 58 | 3 | 🟢 LOW |

### 测试状态
```
Tests run: 207
Failures: 0
Errors: 0  
Skipped: 13
```

✅ **所有测试通过，构建稳定**

### 匿名实现
- DurableExecutionEngine: 1
- ResumedExecutionHandler: 1
- 其他: 0

✅ **匿名实现极少，不构成 blast radius**

---

## 决策问题

是否立即执行完整 **TEST ARCHITECTURE STABILIZATION**？

### 方案 A: 最小化干预（仅监控 + Baseline）

**行动**:
1. 保存当前 blast radius baseline
2. 建立监控机制和触发条件
3. **不建立** Test Harness / Test Builder / Parameter Object
4. 等待真实 Production API 演进触发

**优点**:
- ✅ 符合 CLAUDE.md 架构宪法
- ✅ 符合 V1 Freeze 状态
- ✅ 最小化变更
- ✅ 避免过度工程

**缺点**:
- ⚠️ SpringAiToolCallingEngine 84 调用点风险仍存在
- ⚠️ 下次构造函数演进会触发 84 个测试修改

### 方案 B: 完整 Stabilization（立即执行）

**行动**:
1. SpringAiToolCallingEngineTestHarness
2. DefaultAgentRuntimeTestHarness
3. DurableResumeCoordinatorTestHarness
4. ApprovedBatchExecutionRequest + Builder
5. Recording Test Doubles
6. 迁移所有构造调用点

**优点**:
- ✅ 一次性解决所有 blast radius
- ✅ 未来 API 演进影响最小

**缺点**:
- ❌ 变更范围大（100+ 测试文件）
- ❌ 当前无真实 API 演进计划
- ❌ 违反 "不为未来提前创建" 原则
- ❌ V1 Freeze 期不应大规模重构

---

## 架构宪法对照

### CLAUDE.md 关键原则

> **第二条：最高架构原则**
> 
> 9. **不为"以后可能用到"提前创建模块、抽象、依赖或公共 API。**

> **第十三条：V1 最终工程原则**
>
> 从 V1 开始，架构进入 **Freeze-by-default** 状态：
> **除非真实代码、测试或 Vertical Slice 暴露问题，否则不继续扩展架构。**
>
> ### 1. 可证明优先于"看起来设计正确"
> 
> 架构文档中的设计首先是假设。

> ### 7. 架构修改触发条件
>
> V1 Freeze 后，Architecture Change 必须至少由以下一种证据触发：
> - Vertical Slice 无法自然实现；
> - 测试暴露语义缺陷；
> - DX 明显恶化；
> - **"以后可能需要"不是充分理由。**

### 当前状态对照

| 触发条件 | 当前状态 | 是否满足 |
|---------|---------|---------|
| Vertical Slice 无法实现 | 所有测试通过 | ❌ 否 |
| 测试暴露语义缺陷 | 0 failures | ❌ 否 |
| DX 明显恶化 | 构建稳定 | ❌ 否 |
| 当前真实需求无法实现 | 无当前需求 | ❌ 否 |
| Production API 演进计划 | M6+ 无明确计划 | ❌ 否 |

**结论**: ❌ **不满足任何架构修改触发条件**

---

## 最终决策

### ✅ 采用方案 A：最小化干预

**决策理由**:

1. **符合架构宪法**
   - ✅ 不为"以后可能用到"提前创建抽象
   - ✅ V1 Freeze 状态不应大规模重构
   - ✅ 无真实证据触发架构变更

2. **当前无真实痛点**
   - ✅ 所有测试通过
   - ✅ 构建稳定
   - ✅ 无 Production API 演进计划

3. **风险可控**
   - ✅ 已建立 Baseline 和监控机制
   - ✅ 已明确触发条件
   - ✅ Track 文档可随时执行

4. **避免过度工程**
   - ✅ 84 调用点是"潜在"风险，不是"真实"问题
   - ✅ 如果未来不演进，Test Harness 就是浪费
   - ✅ 预防性重构违反架构纪律

### 交付物

1. ✅ **TEST-ARCHITECTURE-BASELINE.md**
   - Blast radius 量化指标
   - 组件风险评估
   - 监控检查流程

2. ✅ **TEST-ARCHITECTURE-STABILIZATION-DECISION.md**（本文档）
   - 完整决策过程
   - 架构宪法对照
   - 方案评估

3. ✅ **触发条件和监控机制**
   - 清晰的量化阈值
   - 检查命令和流程

### 不交付

1. ❌ SpringAiToolCallingEngineTestHarness
2. ❌ DefaultAgentRuntimeTestHarness
3. ❌ Parameter Objects
4. ❌ Test Builders
5. ❌ Recording Test Doubles

**延期理由**: 等待真实 Production API 演进触发

---

## 触发条件（未来执行完整 Stabilization）

### 强制触发

以下情况**必须**执行完整 Stabilization：

1. **SpringAiToolCallingEngine 构造函数**
   - 增加第 9 个参数
   - OR 测试调用点 > 100

2. **DefaultAgentRuntime 构造函数**
   - 增加第 2 个参数
   - OR 测试调用点 > 50

3. **任何方法参数数量 >= 7**

4. **单次 API 变化导致 > 50 个测试 compile failure**

### 次要触发

以下情况**评估**是否执行：

5. 同一 interface 匿名实现 > 5
6. 同一组件重复构造 > 20
7. DX 明显恶化（开发者反馈）

---

## 监控机制

### 每次 Production API 变化后执行

```bash
# 1. 统计构造调用点
cd arctra-runtime-react
echo "SpringAiToolCallingEngine:"
grep -r "new SpringAiToolCallingEngine(" src/test --include="*.java" | wc -l

echo "DefaultAgentRuntime:"  
grep -r "new DefaultAgentRuntime(" src/test --include="*.java" | wc -l

echo "DurableResumeCoordinator:"
grep -r "new DurableResumeCoordinator(" src/test --include="*.java" | wc -l

# 2. 统计方法调用点
echo "recordInvocationIntent:"
grep -r "recordInvocationIntent(" src/test --include="*.java" | wc -l

echo "executeApprovedBatch:"
grep -r "executeApprovedBatch(" src/test --include="*.java" | wc -l

# 3. 运行测试
cd ..
./mvnw clean verify

# 4. 对照 baseline
cat docs/TEST-ARCHITECTURE-BASELINE.md
```

### 检查清单

每次 Production API 修改后：

- [ ] 构造调用点是否超过阈值？
- [ ] 方法参数是否 >= 7？
- [ ] 测试 compile failures 是否 > 50？
- [ ] 是否触发强制条件？
- [ ] 如果是，执行完整 ARCTRA-TEST-ARCHITECTURE-STABILIZATION-TRACK

---

## Track 文档状态

**ARCTRA-TEST-ARCHITECTURE-STABILIZATION-TRACK** 文档仍然是：

✅ **权威执行指南**

**触发时执行顺序**:
1. SOURCE AUDIT（已完成 baseline）
2. PART A - Shared Test Doubles
3. PART B - Parameter Objects  
4. PART C - Test Builders
5. PART D - Construction Harness
6. PART E - Recording Test Doubles
7. 完整迁移和验证

---

## 批准记录

**决策人**: 主开发者 + Claude Code (Opus 5)

**决策时间**: 2026-09-16

**决策**: ✅ 方案 A - 最小化干预

**下一步**: 继续 M6-T5 closure 或下一个 task

---

## 附录：方案对比

| 维度 | 方案 A（采用） | 方案 B |
|------|---------------|--------|
| 符合架构宪法 | ✅ 完全符合 | ❌ 违反 "不为未来" |
| V1 Freeze 状态 | ✅ 符合 | ❌ 大规模重构 |
| 当前真实需求 | ✅ 无需求 | ❌ 无需求但提前建 |
| 变更范围 | ✅ 最小（文档） | ❌ 大（100+ 测试） |
| 风险防护 | ✅ Baseline + 监控 | ✅ 完整 harness |
| 未来维护成本 | ✅ 低（如不演进） | ⚠️ 高（需维护 harness） |
| 工程质量 | ✅ 符合纪律 | ⚠️ 过度工程风险 |

**最终评分**: 方案 A ✅
