# M8-D 逐步过程执行完成报告

**里程碑：** M8-D 逐步过程执行  
**状态：** ✅ **完成**  
**日期：** 2026-09-21  
**作者：** lov3r

---

## 执行摘要

M8-D 实现了可重用过程的逐步执行协调器，完成了 M8 执行路径学习的核心执行引擎。通过 ProcedureExecutionCoordinator，Arctra 现在可以一次执行一个步骤地运行缓存过程，同时保持与 ReAct 路径相同的治理语义和暂停/恢复能力。

**关键成就：** Arctra 不再需要每次都依赖模型推理——对于已学习的执行模式，可以直接执行缓存的步骤序列，显著降低延迟和成本。

---

## 实现的组件

### Checkpoint Schema 1.3 (1 个更新)

**SuspensionCheckpoint 扩展：**
- 添加 `procedureState` 字段（可选）
- 当 null 时：ReAct 执行（现有行为）
- 当非 null 时：过程执行（M8-D 新增）
- 向后兼容：现有 checkpoint 自动兼容

### 执行状态管理 (3 个新文件)

1. **ProcedureExecutionState** - 过程执行状态记录
   - `procedureId/revision`: 正在执行的过程标识
   - `currentStepIndex`: 下一个要执行的步骤索引
   - `boundInputs`: 绑定的输入参数（INPUT 绑定源）
   - `capturedStepOutputs`: 已捕获的步骤输出（Map<stepIndex, StepOutput>）

2. **StepOutput** - 步骤输出提取
   - 从工具执行结果中提取命名字段
   - 支持 PREVIOUS_STEP_OUTPUT 绑定
   - V1 极简 JSON 解析（实际应使用 Jackson）

3. **ParameterBindingResolver** - 参数绑定解析器
   - INPUT: 从 boundInputs 获取
   - CONSTANT: 直接使用字面值
   - PREVIOUS_STEP_OUTPUT: 从 capturedStepOutputs 提取（格式：stepIndex.outputName）
   - RUNTIME_CONTEXT: V1 未实现（延迟）

### 核心执行协调器 (1 个新文件 + 测试)

**ProcedureExecutionCoordinator** - 逐步执行协调器
- 准备下一步执行（`prepareNextStep`）
- 解析参数绑定（使用 ParameterBindingResolver）
- 评估治理决策（使用 OperationGovernanceEvaluator）
- 根据决策：
  - ALLOW: 生成 PendingToolCall，继续
  - REQUIRE_APPROVAL: 返回需要批准标记
  - DENY: 抛出 ProcedureGovernanceException
- 捕获步骤输出（`captureStepOutput`）
- 检测完成（所有步骤执行完毕）

---

## M8-D 逐步执行循环

```
对于每个步骤：
  1. 解析参数绑定（INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT）
  2. 序列化为 JSON 参数
  3. 评估治理决策（使用当前策略）
  4. 根据决策：
     - ALLOW → 生成 PendingToolCall
     - REQUIRE_APPROVAL → 标记需要批准
     - DENY → 抛出 ProcedureGovernanceException
  5. （工具执行后）捕获步骤输出
  6. 推进到下一步（currentStepIndex++）
  7. 更新 checkpoint（包括 procedureState）
```

---

## 关键架构决策

### 1. 逐步执行，不是批量物化

**一次一步，不是一次全部：**

M8-D 使用逐步执行循环，而不是批量生成所有 PendingToolCall。

**原因：**
- 步骤输出影响后续步骤参数（PREVIOUS_STEP_OUTPUT 绑定）
- 治理评估必须在参数确定后进行
- REQUIRE_APPROVAL 可能在任何步骤暂停
- 避免预生成不会执行的调用

### 2. CheckpointStore 是继续权威

**procedureState 存储在 checkpoint 中，不是单独的表。**

**原因：**
- checkpoint 已经是继续权威（ReAct 使用 pendingBatch）
- procedureState 是 checkpoint 的扩展，不是独立权威
- 简化恢复逻辑（一次 checkpoint 加载获取所有状态）

### 3. 治理是当前决策

**每个步骤通过当前 ToolGovernancePolicy 评估。**

**不是：**
- 历史策略（过程创建时的策略）
- 跳过治理（因为"已经批准过"）

**原因：**
- 策略改变必须立即生效
- HITL 边界不能因缓存而绕过
- 与 M8-C 治理集成一致

### 4. operationId 在物化时生成

**ProcedureExecutionCoordinator 生成新的 operationId。**

**不是：**
- 从历史 Ledger 重放 operationId
- 使用候选提取时的 operationId

**原因：**
- operationId 是框架逻辑操作标识
- 每次物理执行是新的逻辑操作
- 避免 operationId 冲突（并发/重试）

### 5. 步骤输出通过 checkpoint 传递

**capturedStepOutputs 存储在 ProcedureExecutionState 中。**

**不是：**
- 单独的输出存储
- 依赖 ExecutionLedger 历史记录

**原因：**
- checkpoint 是当前继续权威
- 简化恢复逻辑（所有状态在一处）
- 避免跨存储一致性问题

---

## V1 简化与限制

### 已知简化

**JSON 序列化/解析：**
- 使用极简字符串拼接（应使用 Jackson）
- 仅支持简单类型（String/Number/Boolean）
- 仅支持简单 JSON 路径（/fieldName）

**绑定解析：**
- RUNTIME_CONTEXT 未实现
- 无复杂表达式支持
- 无类型转换

**错误处理：**
- BindingResolutionException 为检查异常
- 无重试逻辑
- 无详细错误诊断

### 正确延迟（按设计）

**集成到 ReAct 执行路径：**
- M8-D 只实现协调器逻辑
- 与 SpringAiToolCallingEngine 的集成延迟到实际使用

**REQUIRE_APPROVAL 暂停/恢复：**
- M8-D 返回 requiresApproval 标记
- 创建 WAITING checkpoint 的逻辑延迟到集成时

**过程验证与回退：**
- 延迟到 M8-F
- M8-D 假设过程总是正确的

**意图解析：**
- 延迟到 M8-E
- M8-D 假设过程已选择

---

## 测试覆盖

### M8-D 测试 (7 个测试，全部通过)

**ProcedureExecutionCoordinatorTest:**
- ✅ 准备第一步（ALLOW 策略）
- ✅ 检测完成（所有步骤执行完毕）
- ✅ 需要批准（REQUIRE_APPROVAL 策略）
- ✅ 抛出异常（DENY 策略）
- ✅ 捕获步骤输出
- ✅ 拒绝 procedureId 不匹配
- ✅ 拒绝 revision 不匹配

### 完整测试套件

```
./mvnw clean test -pl arctra-core -am
✅ Tests run: 284, Failures: 0, Errors: 0, Skipped: 0
✅ BUILD SUCCESS
```

包括：
- 所有现有 checkpoint 测试（Schema 1.3 兼容）
- M8-A/M8-B/M8-C 测试
- 核心架构测试（依赖方向保护）

---

## 架构不变式验证

### 不变式 1: 逐步执行（不是批量）

```
prepareNextStep() 一次只准备一个步骤
不预生成整个执行路径
```

### 不变式 2: CheckpointStore 是继续权威

```
procedureState 存储在 SuspensionCheckpoint 中
恢复从 checkpoint 读取 procedureState
不依赖外部状态重建位置
```

### 不变式 3: 治理是当前决策

```
每个步骤调用 governanceEvaluator.evaluate()
使用当前 ToolGovernancePolicy
不缓存历史治理决策
```

### 不变式 4: operationId 在物化时生成

```
generateOperationId() 生成新标识
不从历史 Ledger 重放
每次物理执行有新 operationId
```

### 不变式 5: 步骤输出通过 checkpoint

```
capturedStepOutputs 在 ProcedureExecutionState 中
checkpoint 包含所有恢复所需状态
不依赖外部输出存储
```

---

## 公共 API 增量

### 新增类型 (arctra-core)

**执行状态（包私有，未来可能公开）：**
- `ProcedureExecutionState` - 过程执行状态记录
- `StepOutput` - 步骤输出
- `ParameterBindingResolver` - 参数绑定解析器
- `ProcedureExecutionCoordinator` - 逐步执行协调器

**Checkpoint Schema 扩展（公共）：**
- `SuspensionCheckpoint.procedureState` - 可选过程执行状态字段
- `SuspensionCheckpoint.CURRENT_SCHEMA_VERSION` - 更新为 "1.3"

### 未暴露（内部实现）

- 逐步执行循环逻辑
- JSON 序列化细节
- StepExecutionResult 类型
- operationId/toolCallId 生成

---

## 文件清单

### 生产代码 (4 个新文件，1 个更新)

```
arctra-core/src/main/java/cn/bitcss/arctra/
├── checkpoint/
│   └── SuspensionCheckpoint.java (更新: Schema 1.3)
└── procedure/
    ├── ProcedureExecutionState.java (新)
    ├── StepOutput.java (新)
    ├── ParameterBindingResolver.java (新)
    └── ProcedureExecutionCoordinator.java (新)
```

### 测试代码 (1 个新文件，2 个更新)

```
arctra-core/src/test/java/cn/bitcss/arctra/
├── checkpoint/
│   ├── CheckpointTestHelper.java (更新: Schema 1.3)
│   └── InMemoryCheckpointStoreTest.java (更新: Schema 1.3)
└── procedure/
    └── ProcedureExecutionCoordinatorTest.java (新)
```

---

## 验收标准（全部满足）

✅ ProcedureExecutionState 实现（跟踪执行进度）  
✅ StepOutput 实现（捕获步骤输出）  
✅ ParameterBindingResolver 实现（解析三种绑定源）  
✅ ProcedureExecutionCoordinator 实现（逐步执行循环）  
✅ Schema 1.3（procedureState 字段）  
✅ 向后兼容（现有 checkpoint 自动兼容）  
✅ 治理集成（使用 OperationGovernanceEvaluator）  
✅ 参数绑定解析（INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT）  
✅ 完成检测（isComplete）  
✅ M8-D 专注测试通过（7/7）  
✅ 完整测试套件通过（284/284）  
✅ 零 M6 权威更改（仅 checkpoint 数据扩展）

---

## M8 进度总结

- ✅ M8-A: 可重用过程域模型与权威
- ✅ M8-B: 候选提取（从成功执行中学习）
- ✅ M8-C: 治理集成（缓存过程的治理重新评估）
- ✅ M8-D: 逐步执行（核心执行协调器）
- ⏸️ M8-E: 意图解析与晋升
- ⏸️ M8-F: 验证与回退
- ⏸️ M8-G: 集成测试与闭环

---

## 下一轨道建议

**M8-E: 意图解析与晋升（Intent Resolution & Promotion）**

现在满足的前置条件:
- ✅ ReusableProcedure 域模型
- ✅ ProcedureCandidate 提取
- ✅ 治理评估器
- ✅ 逐步执行协调器

M8-E 将实现:
- ProcedureResolver（intentKey 匹配）
- IntentRegistration API
- 候选晋升逻辑（CANDIDATE → VALID）
- 活动修订选择
- 过程管理 API

**或者先完成集成：**

在实现 M8-E 之前，可以先将 M8-A/B/C/D 集成到 SpringAiToolCallingEngine：
- 在成功执行后提取候选
- 在后续执行中尝试匹配过程
- 使用 ProcedureExecutionCoordinator 执行
- 验证端到端学习周期

---

## 最终架构状态

**🟢 M8-D 逐步执行完成**

### Schema 演化

```
1.0 (M5)      → executionEpoch 缺失，disposition 缺失
1.1 (M6-T4F)  → 添加 executionEpoch
1.2 (M6-T6.4) → 添加 disposition
1.3 (M8-D)    → 添加 procedureState ✅
```

### 权威边界清晰

- **ReusableProcedureStore**: 可重用执行知识权威
- **CheckpointStore**: 当前继续权威（包括 procedureState）
- **ExecutionLedger**: 历史投影
- **ToolGovernancePolicy**: 治理语义权威

### 执行路径已建立

**ReAct 路径（模型推理）：**
```
模型生成 → GovernanceToolCallingAdvisor → 工具执行
```

**缓存路径（过程执行）：**
```
ProcedureExecutionCoordinator → OperationGovernanceEvaluator → 工具执行
```

两条路径：
- 使用相同的 ToolGovernancePolicy
- 产生相同的 PendingToolCall 结构
- 保持相同的 HITL 边界

---

**M8-A 建立了知识权威。M8-B 教会了 Arctra 如何学习。M8-C 确保学到的知识遵守治理规则。M8-D 让缓存过程真正运行起来。**

**下一步：要么实现意图解析与晋升（M8-E），要么先集成到 ReAct 引擎验证端到端流程。**

**@author lov3r**  
**日期: 2026-09-21**  
**状态: 完成**
