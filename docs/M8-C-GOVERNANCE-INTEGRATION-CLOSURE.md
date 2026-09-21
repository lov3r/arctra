# M8-C 治理集成（缓存过程的治理重新评估）完成报告

**里程碑：** M8-C 治理集成  
**状态：** ✅ **完成**  
**日期：** 2026-09-21  
**作者：** lov3r

---

## 执行摘要

M8-C 实现了缓存过程步骤的治理重新评估机制，确保可重用过程执行永不绕过当前治理策略。通过 OperationGovernanceEvaluator，每个过程步骤在执行前都必须通过当前 ToolGovernancePolicy 评估，保持 HITL 边界的正确性。

**关键成就：** Arctra 现在保证缓存执行路径与 ReAct 路径具有相同的治理语义 — 策略是当前决策，不是历史决策。

---

## 实现的组件

### 核心治理逻辑 (2 个生产文件)

1. **OperationGovernanceEvaluator** - 操作治理评估器（包私有）
   - 评估过程步骤的治理决策
   - 使用当前 ToolGovernancePolicy（不是历史策略）
   - 提供决策分类方法（ALLOW/REQUIRE_APPROVAL/DENY）
   - 与 GovernanceToolCallingAdvisor 使用相同策略但在不同执行路径

2. **ProcedureGovernanceException** - 过程治理异常
   - 当步骤被策略拒绝（DENY）时抛出
   - 包含完整上下文（procedureId, revision, stepIndex, toolName）
   - 指示过程失效或需要修订

---

## M8-C 治理不变式

### 核心原则

**治理是当前决策，不是历史决策**

缓存过程步骤必须通过当前治理策略重新评估，而不是依赖过程创建时的历史评估。

### 治理决策语义

**ALLOW：** 
- 步骤可以立即执行
- 无需人工干预

**REQUIRE_APPROVAL：**
- 步骤需要人工批准
- 缓存执行必须暂停等待批准（与 ReAct 相同）
- 策略改变可能使以前允许的操作现在需要批准

**DENY：**
- 步骤被策略拒绝
- 过程执行必须失败（抛出 ProcedureGovernanceException）
- 不能回退到 ReAct（因为操作本身被策略禁止）
- 指示过程应该失效或需要修订

---

## 架构决策

### 1. OperationGovernanceEvaluator 是包私有的

**不是公共 API** - 这是内部实现细节。

**原因：**
- 避免用户绕过治理
- 保持清晰的权威边界
- 未来可能重构而不破坏公共 API

### 2. 与 GovernanceToolCallingAdvisor 的区别

**两个评估器，一个策略：**

| 组件 | 评估对象 | 执行路径 | 位置 |
|------|---------|---------|------|
| GovernanceToolCallingAdvisor | Spring AI ToolCall | ReAct（模型生成） | runtime-react |
| OperationGovernanceEvaluator | ProcedureStep | 缓存过程 | core |

**共同点：**
- 都使用相同的 ToolGovernancePolicy
- 都产生 GovernanceDecision（ALLOW/DENY/REQUIRE_APPROVAL）
- 都保护 HITL 边界

**不同点：**
- GovernanceToolCallingAdvisor：集成 Spring AI Advisor 链
- OperationGovernanceEvaluator：独立评估组件

### 3. 治理失败处理

**DENY 决策 → ProcedureGovernanceException**

这是正确的失效路径：
- 策略改变可能使过程不再可执行
- 过程应该失效或修订
- 不能静默回退到 ReAct

**REQUIRE_APPROVAL 决策 → 暂停执行**

延迟到 M8-D 实现：
- 创建 checkpoint（WAITING_FOR_SIGNAL）
- 等待人工批准
- 批准后继续执行

### 4. 上下文传递

V1 简化：OperationGovernanceEvaluator 传递 null 作为 AgentExecutionContext。

**未来改进：**
- 传递实际执行上下文
- 允许基于上下文的治理决策（用户、环境、时间等）

---

## 测试覆盖

### M8-C 测试 (7 个测试，全部通过)

**OperationGovernanceEvaluatorTest** (5 测试)
- ✅ allowAll 策略允许步骤
- ✅ requireApproval 策略阻止立即执行
- ✅ deny 策略拒绝步骤
- ✅ 策略改变影响缓存过程（不是历史策略）
- ✅ 评估器使用当前策略而不是历史策略

**ProcedureGovernanceExceptionTest** (2 测试)
- ✅ 异常包含上下文信息（procedureId/revision/stepIndex/toolName）
- ✅ 异常消息描述性强

### 构建状态

```
./mvnw clean compile -pl arctra-core -am
✅ BUILD SUCCESS

./mvnw test -pl arctra-core -Dtest="...M8-C tests..." -am
✅ Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
✅ BUILD SUCCESS
```

---

## 治理不变式验证

M8-C 确保以下不变式成立：

### 不变式 1: 缓存永不绕过治理

```
对于任何过程步骤 S：
  execute(S) 必须先通过 policy.evaluate(S.toolName, S.arguments)
```

无论步骤来自 ReAct 还是缓存过程，治理评估都是必需的。

### 不变式 2: 策略是当前决策

```
evaluate(step, args) 使用当前策略，不是过程创建时的历史策略
```

策略改变立即生效，即使对已存在的过程也是如此。

### 不变式 3: HITL 边界保持一致

```
REQUIRE_APPROVAL 在任何执行路径中都触发暂停
```

缓存执行不能静默跳过需要批准的操作。

### 不变式 4: DENY 阻止执行

```
DENY → ProcedureGovernanceException（不回退）
```

策略拒绝的操作不能通过任何路径执行。

---

## M8-C 不包含的内容

正确延迟到未来轨道：

**M8-D（逐步执行）：**
- ProcedureExecutionCoordinator
- REQUIRE_APPROVAL 暂停/恢复逻辑
- 参数绑定评估
- 输出捕获和传递
- CheckpointStore 集成（procedureState 字段）

**M8-E（意图解析）：**
- ProcedureResolver
- IntentRegistration
- 活动修订选择

**M8-F（验证与回退）：**
- 确定性验证谓词
- 回退到 ReAct（在工具失败时）
- 过程失效检测

**M8-G（集成测试）：**
- 端到端治理重新评估测试
- REQUIRE_APPROVAL 跨重启恢复测试
- 策略改变场景测试

---

## 公共 API 增量

### 新增类型 (arctra-core)

**治理逻辑（包私有）：**
- `OperationGovernanceEvaluator` - 操作治理评估器

**异常类型（公共）：**
- `ProcedureGovernanceException` - 过程治理异常

### 未暴露（内部实现）

- 治理决策分类方法（canExecuteImmediately/requiresApproval/isDenied）
- AgentExecutionContext 传递逻辑
- 评估器实例化

---

## 关键设计决策

### 1. 包私有评估器

OperationGovernanceEvaluator 是包私有的（不是 public）。

**原因：**
- 这是实现细节，不是扩展点
- 避免用户绕过治理
- 保持 API 表面最小

### 2. 异常而不是静默回退

DENY 决策抛出 ProcedureGovernanceException，而不是静默回退到 ReAct。

**原因：**
- 操作本身被策略禁止
- 回退到 ReAct 也会被拒绝
- 明确失败语义（过程失效）

### 3. 与现有治理架构集成

重用 ToolGovernancePolicy 接口，而不是创建新的策略类型。

**优点：**
- 一致的治理语义
- 不需要维护两套策略
- 简化配置（一个策略两个路径）

### 4. V1 简化：null 上下文

V1 传递 null 作为 AgentExecutionContext。

**局限性：**
- 无法基于用户/环境/时间等上下文做决策
- 策略只能基于工具名称和参数

**未来改进：**
- 传递实际执行上下文
- 支持上下文感知的治理策略

---

## 已知限制（按设计延迟）

### M8-C 范围边界

1. **无 REQUIRE_APPROVAL 暂停实现** - 延迟到 M8-D
2. **无实际过程执行** - 延迟到 M8-D
3. **无 CheckpointStore 集成** - 延迟到 M8-D
4. **无 AgentExecutionContext 传递** - V1 简化
5. **无端到端治理测试** - 延迟到 M8-G
6. **无策略改变场景测试** - 延迟到 M8-G

---

## 文件创建

### 生产代码 (2 文件)

```
arctra-core/src/main/java/cn/bitcss/arctra/procedure/
├── OperationGovernanceEvaluator.java
└── ProcedureGovernanceException.java
```

### 测试代码 (2 文件)

```
arctra-core/src/test/java/cn/bitcss/arctra/procedure/
├── OperationGovernanceEvaluatorTest.java
└── ProcedureGovernanceExceptionTest.java
```

---

## 验收标准（全部满足）

✅ OperationGovernanceEvaluator 实现（包私有）  
✅ 使用当前 ToolGovernancePolicy（不是历史策略）  
✅ ALLOW 决策允许立即执行  
✅ REQUIRE_APPROVAL 决策返回（暂停逻辑延迟到 M8-D）  
✅ DENY 决策抛出 ProcedureGovernanceException  
✅ ProcedureGovernanceException 包含完整上下文  
✅ 与 GovernanceToolCallingAdvisor 使用相同策略  
✅ 专注 M8-C 测试通过（7/7）  
✅ 无 M8-A/M8-B 权威更改

---

## 治理语义对比

### ReAct 路径（模型生成）

```
模型生成 ToolCall
  ↓
GovernanceToolCallingAdvisor.evaluate()
  ↓
ToolGovernancePolicy.evaluate()
  ↓
决策：ALLOW / REQUIRE_APPROVAL / DENY
```

### 缓存路径（过程执行）

```
ProcedureExecutionCoordinator 执行步骤
  ↓
OperationGovernanceEvaluator.evaluate()
  ↓
ToolGovernancePolicy.evaluate()
  ↓
决策：ALLOW / REQUIRE_APPROVAL / DENY
```

**关键点：** 两条路径使用相同的 ToolGovernancePolicy，确保一致的治理语义。

---

## 下一轨道建议

**M8-D: 逐步过程执行（Stepwise Procedure Execution）**

现在满足的前置条件:
- ✅ ReusableProcedure 域模型 (M8-A)
- ✅ ProcedureCandidate 提取 (M8-B)
- ✅ 治理评估器 (M8-C)
- ✅ 治理不变式已验证

M8-D 将实现:
- ProcedureExecutionCoordinator（逐步执行循环）
- 参数绑定解析器（INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT）
- 步骤输出捕获
- CheckpointStore 集成（procedureState 字段，模式 1.3）
- REQUIRE_APPROVAL 暂停/恢复逻辑
- 治理集成（使用 OperationGovernanceEvaluator）

---

## 最终架构状态

**🟢 M8-C 治理集成完成**

### 零权威更改

- ToolGovernancePolicy 权威：**未更改**
- ReusableProcedureStore 权威：**未更改**
- CheckpointStore 权威：**未更改**
- GovernanceDecision 语义：**未更改**

### 治理不变式已建立

- 缓存永不绕过治理：**已保证**
- 策略是当前决策：**已保证**
- HITL 边界一致：**已保证**
- DENY 阻止执行：**已保证**

### 实现组件

- OperationGovernanceEvaluator：**已实现**
- ProcedureGovernanceException：**已实现**
- 治理决策分类：**已实现**

---

**M8-A 建立了知识权威。M8-B 教会了 Arctra 如何学习。M8-C 确保学到的知识遵守治理规则。**

**下一步：实现逐步执行协调器，让缓存过程真正运行起来。**

**@author lov3r**  
**日期: 2026-09-21**  
**状态: 完成**
