# M8 集成验证计划

**目标：** 将 M8-A/B/C/D 集成到 SpringAiToolCallingEngine，验证端到端学习周期

---

## 集成架构

### 当前执行流程（M6）

```
AgentRequest
  ↓
ExecutionFlowCoordinator.dispatch()
  ↓
├─ EPHEMERAL → EphemeralExecutionHandler
└─ DURABLE → DurableExecutionHandler
      ↓
   ModelContinuationExecutor
      ↓
   GovernanceToolCallingAdvisor
      ↓
   工具执行
```

### M8 扩展执行流程

```
AgentRequest
  ↓
ExecutionFlowCoordinator.dispatch()
  ↓
ProcedureMatchingDecision (新)
  ↓
├─ NO_MATCH → ReAct 路径（现有）
│     ↓
│  ModelContinuationExecutor
│     ↓
│  GovernanceToolCallingAdvisor
│     ↓
│  工具执行
│     ↓
│  ProcedureCandidateExtractor (新 - M8-B)
│
└─ MATCHED → 缓存路径（新 - M8-D）
      ↓
   ProcedureExecutionCoordinator
      ↓
   OperationGovernanceEvaluator
      ↓
   工具执行
```

---

## 集成点

### 1. 过程匹配决策（M8-E 简化版）

**位置：** ExecutionFlowCoordinator

**职责：**
- 检查是否有可用过程匹配当前请求
- V1 简化：基于 agentName 简单匹配，不做复杂意图解析

**实现：**
```java
class SimpleProcedureMatcher {
  Optional<ReusableProcedure> findMatch(String agentName, String userPrompt);
}
```

### 2. 候选提取（M8-B）

**位置：** 成功执行后（ExecutionFlowCoordinator 或 DurableExecutionHandler）

**职责：**
- 从成功的 ReAct 执行中提取候选过程
- 持久化到 ReusableProcedureStore

**实现：**
```java
class ProcedureCandidateExtractor {
  Optional<ProcedureCandidate> extract(ExecutionLedger ledger, AgentRequest request);
}
```

### 3. 缓存路径执行（M8-D）

**位置：** 新的 ProcedureExecutionHandler

**职责：**
- 使用 ProcedureExecutionCoordinator 逐步执行
- 处理 REQUIRE_APPROVAL 暂停
- 捕获步骤输出
- 失败时回退到 ReAct

**实现：**
```java
class ProcedureExecutionHandler {
  AgentResult execute(
    ReusableProcedure procedure,
    AgentRequest request,
    ProcedureExecutionState state
  );
}
```

---

## 实施步骤

### 阶段 1: 基础设施（存储与匹配）

**任务：**
- [ ] InMemoryReusableProcedureStore 实现
- [ ] SimpleProcedureMatcher 实现（基于 agentName）
- [ ] 集成到 SpringAiToolCallingEngine 构造函数

**验收：**
- 可以存储和查询过程
- 可以基于 agentName 匹配

### 阶段 2: 候选提取（学习）

**任务：**
- [ ] ProcedureCandidateExtractor 实现
- [ ] 集成到成功执行后的钩子
- [ ] 自动持久化候选到存储

**验收：**
- 成功执行后自动提取候选
- 候选包含正确的步骤和绑定
- 候选持久化到存储

### 阶段 3: 缓存路径执行（复用）

**任务：**
- [ ] ProcedureExecutionHandler 实现
- [ ] 集成到 ExecutionFlowCoordinator
- [ ] 处理 REQUIRE_APPROVAL 暂停
- [ ] 处理执行失败回退

**验收：**
- 匹配到过程时使用缓存路径
- 逐步执行正确推进
- REQUIRE_APPROVAL 正确暂停
- 失败时回退到 ReAct

### 阶段 4: 端到端测试

**任务：**
- [ ] 第一次执行：ReAct + 候选提取
- [ ] 第二次执行：过程匹配 + 缓存执行
- [ ] REQUIRE_APPROVAL 暂停/恢复测试
- [ ] 治理策略改变测试

**验收：**
- 完整学习周期工作
- 第二次执行不调用模型
- 治理重新评估正确
- 跨重启恢复工作

---

## V1 简化

### 不实现（延迟到 M8-E）

- 复杂意图解析（intentKey 匹配）
- 候选晋升 API（手动晋升）
- 多修订管理（只保留最新）

### 不实现（延迟到 M8-F）

- 确定性验证谓词
- 智能回退决策
- 过程失效检测

### V1 假设

- 过程自动 VALID（不需要人工晋升）
- 基于 agentName 简单匹配
- 失败立即回退到 ReAct
- 单一活动修订

---

## 成功标准

集成验证成功，如果：

1. ✅ 第一次执行：ReAct 路径，自动提取候选
2. ✅ 第二次执行：过程匹配，缓存路径（不调用模型）
3. ✅ 逐步执行工作（包括 PREVIOUS_STEP_OUTPUT 绑定）
4. ✅ 治理重新评估工作（DENY/REQUIRE_APPROVAL/ALLOW）
5. ✅ REQUIRE_APPROVAL 暂停/恢复工作
6. ✅ 跨重启恢复工作（procedureState 持久化）
7. ✅ 失败回退到 ReAct
8. ✅ 可观测性事件捕获学习周期

---

**下一步：开始阶段 1 - 基础设施（存储与匹配）**
