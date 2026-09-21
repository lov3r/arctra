# M8 执行路径学习 — 架构门控

**Milestone:** M8 Execution Path Learning / Cached Execution  
**状态:** 🚧 **ARCHITECTURE GATE — 等待批准**  
**日期:** 2026-09-20  
**作者:** lov3r

---

## 执行概要

**M7 Recovery Control Plane 已关闭。**

**M8 需要架构门控批准才能实施。**

当前 M8 路线图选择了 **Execution Path Learning / Cached Execution**，该方向已被接受。

**然而，路线图存在一个重要的概念不一致性：**

路线图称此子系统为 **"Execution Path Learning"（执行路径学习）**

但提议的 M8 V1 实现仅包含 **manual / explicit path creation（手动/显式路径创建）**

而 **automatic learning from successful ReAct executions（从成功的 ReAct 执行中自动学习）** 被延期。

**这有将 M8 变成轻量级工作流/模板引擎的风险，而不是预期的学习系统。**

本架构门控必须在实施前解决语义模型。

---

## 1. 原始产品理念

预期能力是：

### 首次执行

```
Request
  ↓
ReAct reasoning
  ↓
Tool execution
  ↓
Observation
  ↓
Further reasoning
  ↓
Tool execution
  ↓
…
  ↓
Successful / verified result
  ↓
Extract reusable execution knowledge
  ↓
Persist verified execution path
```

### 后续兼容执行

```
Request
  ↓
Recognize reusable path
  ↓
Bind current parameters
  ↓
Execute known path
  ↓
Current governance
  ↓
Verify result
  ↓
Return
```

**无需重复完整 ReAct 规划。**

核心理念：

> **REACT 发现执行知识。**
>
> **ARCTRA 学习它。**
>
> **后续执行可以复用它。**

---

## 2. 这不是响应缓存

**不要设计:**

```
prompt → answer cache
```

输出不能简单是：

```
cached AgentResult
```

不同的请求具有不同的：
- 参数
- 环境状态
- 工具输出
- 授权
- 治理
- 外部副作用

我们想复用的是：

**执行结构（EXECUTION STRUCTURE）**

而不是：

**之前的执行结果（previous execution result）**

---

## 3. 这不是思维链缓存

**不要持久化或重放隐藏的模型推理。**

**不要将可复用路径定义为:**

```
Thought
  → Action
  → Observation
  → Thought
  → Action
```

可复用的 artifact 必须只包含执行相关的、非 CoT 的结构。

潜在内容：
- 工具操作
- 依赖关系
- 参数绑定
- 条件
- 预期输出合约
- 验证规则
- 回退边界
- 溯源

**确定最小表示。**

---

## M8 ARCHITECTURE CORRECTION (2026-09-20)

**CRITICAL CORRECTIONS TO ORIGINAL GATE ANALYSIS**

Three hard architecture contradictions were identified and resolved:

1. **Cached execution MUST NOT bypass governance** — corrected
2. **PREVIOUS_STEP_OUTPUT requires stepwise execution** — corrected  
3. **Current procedure position has ONE authority** — corrected

These corrections supersede sections of the original analysis below.

---

## 第一部分：源代码真相检查

### 0. 当前执行边界重建（基于源代码）

通过检查实际源代码，当前 Arctra 执行流程如下：

```
Agent.execute(request, context)
  ↓
SpringAiToolCallingEngine.execute()
  ↓
ModelContinuationExecutor.buildSystemInstruction()
  ↓
[初始消息: SystemMessage + UserMessage]
  ↓
ModelContinuationExecutor.executeWithMessages()
  ↓
ChatClient.prompt().messages().tools().advisors()
  ↓
【Advisor Chain】
  - MessageChatMemoryAdvisor (加载历史)
  - GovernanceToolCallingAdvisor (工具调用循环 + 治理)
  ↓
ChatModel 生成 (Spring AI)
  ↓
AssistantMessage 包含 ToolCalls
  ↓
GovernanceToolCallingAdvisor: 治理前置检查
  - 批量评估所有工具调用
  - 决策优先级: DENY > REQUIRE_APPROVAL > ALLOW
  ↓
【如果 REQUIRE_APPROVAL】
  ↓
ToolApprovalRequiredSignal (抛出)
  ↓
ExecutionFlowCoordinator.route()
  ↓
【根据 ExecutionMode 路由】
  - EPHEMERAL → EphemeralExecutionHandler
  - DURABLE → DurableExecutionHandler
  ↓
【DURABLE 路径】
  ↓
创建 SuspensionCheckpoint
  - processId, checkpointVersion
  - pendingBatch (List<PendingToolCall>)
    - operationId (框架所有)
    - toolCallId (Spring AI 协议)
    - toolName
    - arguments
  - accumulatedEvidences
  - disposition (WAITING_FOR_SIGNAL / RUNNABLE)
  ↓
CheckpointStore.create()
  ↓
返回 AgentProcess (WAITING)
```

#### 恢复路径（关键）：

```
AgentRuntime.resumeProcess(processId, version, signal)
  ↓
DurableResumeCoordinator.resume()
  ↓
CHECK A: CheckpointStore.load(processId)
  - 验证 checkpointVersion
  - 如果不匹配 → StaleCheckpointException
  ↓
RuntimeBindingResolver.resolve(checkpoint.runtimeBindingKey)
  → RuntimeBinding(AgentDefinition, AgentExecutionContext)
  ↓
executionEpoch 比较 (M6-T4F)
  - 相同 → 正常恢复
  - 不同 → 跨重启恢复 (需要 T5 分类)
  ↓
【T5 Recovery Classification】（跨重启时）
  ↓
InvocationRecoveryClassifier.classifyBatch()
  ↓
查询 InvocationStateStore
  - DEFINITELY_NOT_DISPATCHED → 新尝试
  - MAY_HAVE_INVOKED → RecoveryUncertaintyException
  - RESOLVED_NOT_EXECUTED → 新尝试
  - RESOLVED_EXECUTED → 使用恢复的结果
  ↓
ProtocolReconstructor.executeApprovedBatch()
  ↓
【对于每个 PendingToolCall】
  ↓
如果 RESOLVED_EXECUTED:
  - 使用恢复的结果 (跳过物理调用)
否则:
  ↓
  recordInvocationIntent(processId, operationId, attemptId)
    → InvocationStateStore (M6-T4A 硬门控)
  ↓
  ToolCallback.call(arguments, toolContext)
    → 物理工具执行
  ↓
  emitToolExecuted(operationId) 或 emitToolFailed(operationId)
  ↓
构造 ToolResponseMessage
  ↓
ModelContinuationExecutor.continueWithMessages()
  ↓
【继续 ChatClient 执行】
  ↓
可能产生更多 ToolCalls (重新进入治理循环)
或最终答案
  ↓
CHECK B: 
  - 如果完成 → CheckpointStore.deleteIfVersion()
  - 如果重新暂停 → CheckpointStore.replaceIfVersion()
  ↓
返回 AgentResult(content, evidences)
```

### 关键边界识别

**A. ReAct 特定规划发生在:**

`ModelContinuationExecutor.executeWithMessages()` → ChatModel 生成

**这是模型推理发生的地方**。没有显式的"ReAct 规划器"类。

**B. 工具调用变成提供者无关操作的位置:**

`PendingToolCall` 记录创建时：
- `operationId`: Arctra 框架所有的逻辑操作标识
- `toolCallId`: Spring AI 协议相关
- `toolName`, `arguments`: 提供者无关

**C. 治理发生在:**

`GovernanceToolCallingAdvisor.adviseCall()` 
- 在工具执行之前
- 批量评估所有工具

**D. M6 持久性开始于:**

`CheckpointStore.create(SuspensionCheckpoint)`
- 包含 `PendingToolCall` 列表（持久工具调用表示）

**E. 物理 ToolCallback.call 发生在:**

`ProtocolReconstructor.executeOperation()`
- 在 `recordInvocationIntent()` 之后（硬门控）
- 使用 `EvidenceCapturingToolCallback` 包装

**F. 工具结果返回到模型继续:**

`ToolResponseMessage` → `ModelContinuationExecutor.continueWithMessages()`

---

## 第二部分：M8 核心架构决策

### 1. 可复用执行知识的定义

**定义:**

> **可复用执行过程（Reusable Execution Procedure）** 是一个经过验证的、参数化的、不可变的可执行结构，描述了针对特定意图模式的工具操作序列及其依赖关系，可在兼容的未来请求中安全复用，以避免重复的模型规划成本。

**拥有（OWNS）:**

- 工具操作序列（tool operation sequence）
- 参数绑定模式（parameter binding patterns）
- 前序输出依赖（previous-output dependencies）
- 工具兼容性指纹（tool compatibility fingerprints）
- 意图匹配键（intent matching key）
- 验证契约（verification contract）
- 溯源信息（provenance: derivedFromExecutionId, createdAt）
- 过程状态（VALID / INVALID / SUPERSEDED）

**不拥有（DOES NOT OWN）:**

- 当前执行位置 (currentStep) → 属于 Checkpoint
- 物理尝试 (attemptId) → 属于 InvocationStateStore
- 批准决策 → 属于当前 Governance 评估
- 授权结果 → 属于当前 Governance 评估
- 对话状态 → 属于 ChatMemory
- 历史执行真相 → 属于 ExecutionLedger
- 工具结果内容 → 属于 Evidence
- 凭据/秘密 → 永不捕获

---

### 2. 新权威决策

**答案: YES**

**M8 需要一个新的持久权威用于经过验证的可复用执行知识。**

**权威声明:**

> **ReusableProcedureStore 是当前活动的可复用过程定义的权威。**

它拥有"这个过程结构已被验证有效"的事实，但不拥有"此进程当前在此过程中的位置"（CheckpointStore 拥有）或"此操作是否被物理调用"（InvocationStateStore 拥有）。

**术语选择:**

经过评估，选择以下术语：

| 概念 | 术语 | 理由 |
|------|------|------|
| 原始成功执行跟踪 | ExecutionTrace | 清晰表示历史记录 |
| 提取但未信任的可复用结构 | ProcedureCandidate | 候选状态 |
| 可信可复用可执行结构 | ReusableProcedure | 明确表示可复用性 |
| 该结构的一次运行时执行 | ProcedureExecution | 与 Procedure 对应 |

避免使用"Cache"作为核心域名，因为这是持久的学习知识，不是临时缓存。

避免使用"Path"，因为它可能暗示线性结构，而我们可能需要依赖关系。

选择"Procedure"是因为它传达了可执行性和可重复性。

---

### 3. 可缓存性模型

**M8 V1 可缓存性规则（保守）:**

| 条件 | 可缓存? | 理由 |
|------|---------|------|
| 线性工具操作结构 | ✅ YES | V1 仅支持线性 |
| 包含动态模型推理决策 | ❌ NO | 需要模型无法跳过 |
| 所有参数可从输入/前输出绑定 | ✅ YES | 确定性绑定 |
| 工具模式稳定且可指纹化 | ✅ YES | 兼容性检查 |
| 仅包含只读/幂等工具 | ✅ YES | 回退安全 |
| 包含非幂等副作用工具 | ❌ NO (V1) | 回退不安全 |
| 有确定性验证器 | ✅ YES | 可验证正确性 |
| 验证需要 LLM 评判 | ❌ NO (V1) | 过于复杂 |
| 包含秘密/凭据 | ❌ NO | 安全风险 |
| 执行成功 | ⚠️ 必要但不充分 | 还需满足其他条件 |

**可缓存性决策算法:**

```
function isCacheable(executionTrace): boolean {
  if (executionTrace.failed) return false;
  if (containsDynamicReasoning(executionTrace)) return false;
  if (containsNonIdempotentTools(executionTrace)) return false;
  if (containsSecretsOrCredentials(executionTrace)) return false;
  if (!hasLinearStructure(executionTrace)) return false;
  if (!hasToolSchemaFingerprints(executionTrace)) return false;
  if (!hasDeterministicVerification(executionTrace)) return false;
  return true;
}
```

---

### 4. M8 V1 过程形状决策

**决策: 仅线性有序操作（LINEAR ORDERED OPERATIONS ONLY）**

**理由:**

1. 当前源代码中 `PendingToolCall` 列表已经是有序的
2. 避免引入 BPMN/工作流引擎复杂性
3. DAG 依赖关系可以在 V2 中添加，如果被证明必要
4. 线性结构易于验证和调试

**V1 过程表示:**

```java
public record ReusableProcedure(
    String procedureId,
    int revision,
    String intentKey,
    ProcedureScope scope,
    List<ProcedureStep> steps,  // ORDERED, LINEAR
    VerificationContract verification,
    Instant createdAt,
    String derivedFromExecutionId
) {}

public record ProcedureStep(
    int stepIndex,
    String toolName,
    String toolSchemaFingerprint,
    Map<String, ParameterBinding> parameterBindings,
    OutputExtraction outputExtraction  // for next step
) {}
```

**如果 ReAct 跟踪需要任意动态推理在操作之间:**

→ 标记为 NON_CACHEABLE (V1)

---

### 5. 静态 vs 动态决策

**V1 决策:**

| 决策类型 | V1 支持? | 实现 |
|----------|----------|------|
| 静态输入参数绑定 | ✅ YES | `INPUT.serviceName` |
| 常量值 | ✅ YES | `CONSTANT["prod"]` |
| 前一步输出绑定 | ✅ YES | `STEP[0].output.serviceId` |
| 运行时上下文引用 | ✅ YES | `CONTEXT.environment` |
| 动态模型推理决策 | ❌ NO | 标记为不可缓存 |
| 条件分支 | ❌ NO (V1) | 未来考虑确定性谓词 |

**静态决策示例:**

```java
Step 1: getService
  serviceName = INPUT.serviceName

Step 2: queryLogs
  serviceId = STEP[1].output.id
  environment = INPUT.environment
```

**动态决策（V1 不可缓存）示例:**

```
if (deployment.version != expectedVersion):
  inspectDeployment()  ← 需要模型推理
```

---

### 6. 参数绑定模型

**V1 绑定源:**

```java
public enum BindingSource {
  INPUT,              // 来自请求输入
  CONSTANT,           // 硬编码常量
  PREVIOUS_STEP_OUTPUT,  // 前一步的输出
  RUNTIME_CONTEXT     // 如 runtimeBindingKey 提供的上下文
}

public record ParameterBinding(
    BindingSource source,
    String path  // JSON Pointer 或简单字段路径
) {}
```

**示例:**

```java
parameterBindings = {
  "serviceName": ParameterBinding(INPUT, "serviceName"),
  "serviceId": ParameterBinding(PREVIOUS_STEP_OUTPUT, "1.output.id"),
  "environment": ParameterBinding(RUNTIME_CONTEXT, "environment")
}
```

**输出提取（简单）:**

```java
public record OutputExtraction(
    String outputField  // 要捕获的字段，如 "id"
) {}
```

**不使用:**
- JavaScript / SpEL / Groovy / Python
- 任意表达式语言

**使用:**
- JSON Pointer 或简单点表示法
- 确定性字段提取

---

### 7. 常量捕获安全

**永不捕获的值类型:**

- 凭据（credentials）
- 令牌（tokens）
- 密码（passwords）
- 授权决策
- 临时标头
- 会话秘密
- 运行时句柄

**实现:**

候选提取阶段拒绝包含这些值的执行。

不需要显式 `NON_CAPTUREABLE` 语义，因为可缓存性验证会拒绝它们。

**检测机制:**

- 参数名称模式匹配（`*password*`, `*token*`, `*secret*`, `*credential*`）
- 值内容分析（看起来像 JWT、API 密钥等）

---

### 8. 工具兼容性

**V1 工具兼容性标识:**

```java
public record ToolCompatibilityFingerprint(
    String toolName,
    String inputSchemaHash  // JSON Schema 的 SHA-256
) {}
```

**兼容性检查:**

```
当前工具集 vs 过程所需工具:
  1. toolName 必须存在
  2. inputSchemaHash 必须匹配
```

**如果不匹配:**

→ 拒绝过程复用（fail closed）
→ 回退到 ReAct

**模式指纹输入:**

- 工具名称
- 输入参数 JSON Schema
- （V1 不包括输出模式，如果当前源未公开）

---

### 9. 自动候选提取

**答案: YES - 这是 M8 的核心**

**提取源（基于当前架构）:**

**最佳来源: ExecutionLedger**

ExecutionLedger 已经记录：
- `TOOL_BATCH_MATERIALIZED` → 工具批次
- `TOOL_EXECUTED(operationId)` → 个别操作
- `TOOL_FAILED(operationId)` → 失败
- `SUSPENDED` / `RESUMED` / `COMPLETED` → 生命周期

**提取映射:**

```
ExecutionLedger.queryByProcess(processId)
  ↓
过滤 COMPLETED 执行
  ↓
提取序列:
  - TOOL_BATCH_MATERIALIZED events
  - TOOL_EXECUTED events (按顺序)
  - 对应的 Evidence
  ↓
重建 ExecutionTrace:
  - 工具序列
  - 参数（从 payload）
  - 成功/失败
  - Evidence 引用
  ↓
应用可缓存性验证
  ↓
如果可缓存:
  ↓
  生成 ProcedureCandidate
```

**缺失信息分析:**

当前 ExecutionLedger **不**记录：
- 工具参数内容（仅 operationId）
- 工具结果内容（仅 Evidence 引用）

**需要的最小额外投影:**

**新建议: TOOL_INVOKED event type**

```java
EventType.TOOL_INVOKED

payload: {
  "operationId": "...",
  "toolName": "...",
  "arguments": "{...}",  // 完整参数
  "toolCallId": "..."
}
```

这允许候选提取重建完整的工具调用结构，而不修改 M6 权威语义。

**不修改 M6 - 仅添加新事件类型。**

---

### 10. 晋升模型

**V1 晋升语义:**

```
ReAct 成功执行
  ↓
自动候选提取 (从 ExecutionLedger)
  ↓
可缓存性验证
  ↓
结构规范化
  ↓
工具模式指纹化
  ↓
确定性验证器分配
  ↓
【需要显式晋升】
  ↓
操作员批准 OR 自动验证通过
  ↓
ReusableProcedureStore.create()
```

**决策: 显式晋升（Explicit Promotion）**

**不**自动激活候选，即使验证器存在。

**理由:**
- 安全第一
- V1 保守
- 允许人工审查

**晋升 API (最小):**

```java
public interface ProcedureLearningService {
  // 内部自动调用
  ProcedureCandidate extractCandidate(String executionId);
  
  // 操作员调用
  ReusableProcedure promoteCandidate(String candidateId);
  
  // 操作员调用
  void invalidateProcedure(String procedureId);
}
```

---

### 11. 验证模型

**V1 验证:**

```java
public interface ProcedureVerification {
  VerificationResult verify(
      ProcedureExecution execution,
      AgentResult result,
      List<Evidence> evidences
  );
}

public record VerificationResult(
    boolean passed,
    String reason
) {}
```

**V1 支持的验证器类型:**

1. **确定性谓词验证器**
   ```java
   result.evidences.size() >= 1
   result.content.contains("completed")
   ```

2. **Evidence 模式验证器**
   ```java
   evidences.any(e -> e.source() == "queryLogs")
   ```

3. **外部状态确认** (应用程序提供)
   ```java
   externalSystem.checkTaskCompleted(taskId)
   ```

**V1 不支持:**
- LLM 评判者
- 多 agent 验证
- 复杂业务验证框架

**验证失败处理:**

验证失败 **不**立即使过程无效。

记录失败，增加降级计数器。

如果重复失败（例如 3 次），则标记为 INVALID。

---

### 12. 过程匹配 V1

**决策: 结构化确定性匹配（NO 语义/向量匹配）**

**匹配机制:**

```java
public record IntentPattern(
    String intentKey,  // 如 "investigate-service-latency"
    Map<String, Class<?>> inputSchema
) {}
```

**匹配算法:**

```
1. intentKey 精确匹配
2. 输入模式兼容性检查
3. 工具可用性检查
4. 工具模式兼容性检查
5. 环境/范围兼容性
```

**示例:**

```java
IntentPattern pattern = new IntentPattern(
    "investigate-service-latency",
    Map.of(
        "serviceName", String.class,
        "environment", String.class
    )
);
```

**应用程序注册意图:**

```java
@Bean
public IntentRegistration serviceLatencyIntent() {
  return IntentRegistration.builder()
      .intentKey("investigate-service-latency")
      .procedureId("proc-123")
      .build();
}
```

**理由不使用语义匹配:**

- 避免向量数据库依赖
- 避免嵌入模型依赖
- 确定性可预测
- 更容易调试
- V1 验证复用语义优先于便利性

---

### 13. 自动学习 vs 自动匹配

**关键区分（保留）:**

| 阶段 | V1 模式 |
|------|---------|
| 候选提取 | ✅ 自动（从成功执行） |
| 候选验证 | ✅ 自动（可缓存性规则） |
| 候选晋升 | ⚠️ 显式（操作员批准） |
| 意图注册 | ⚠️ 显式（应用程序配置） |
| 过程匹配 | ✅ 自动（确定性） |
| 过程执行 | ✅ 自动（匹配后） |

这保留了"学习"理念（自动候选提取），同时保持安全性（显式激活）。

---

### 14. 过程范围

**V1 范围选择: AgentDefinition**

**理由:**

- `AgentDefinition` 已经是现有概念
- 自然隔离边界
- 避免发明租户语义
- 每个 agent 有自己的可复用过程

**范围标识:**

```java
public record ProcedureScope(
    String agentName  // AgentDefinition.name()
) {}
```

**查找:**

```
ReusableProcedureStore.findByIntent(intentKey, agentName)
```

**不共享:**

- 跨 agent 的过程
- 全局过程（除非显式 "global" agentName）
- 跨租户（V1 不支持多租户）

---

### 15. 过程标识和不可变修订

**决策: 不可变过程修订（IMMUTABLE PROCEDURE REVISION）**

**理由:**

暂停的持久执行必须恢复到它开始时的相同可执行定义。

**标识模型:**

```java
public record ProcedureIdentity(
    String procedureId,    // 稳定逻辑标识
    int revision           // 从 1 开始递增
) {}
```

**生命周期:**

```
首次学习:
  procedureId = generateId()
  revision = 1
  ↓
  ReusableProcedureStore.create()

需要更新时:
  创建新修订:
    same procedureId
    revision = 2
  ↓
  ReusableProcedureStore.create()
  
激活管理:
  IntentRegistration 指向 (procedureId, revision)
```

**暂停执行引用:**

Checkpoint 需要扩展以包含过程执行状态（如果使用缓存过程）：

```java
// 不修改 SuspensionCheckpoint 字段
// 使用 payload 扩展或新的并行状态

public record ProcedureExecutionState(
    String procedureId,
    int procedureRevision,
    int currentStepIndex,
    Map<String, Object> boundInputs,
    Map<Integer, Object> stepOutputs  // 步骤 → 输出
) {}
```

这可以作为附加状态存储，不修改 M6 的 `SuspensionCheckpoint` 核心字段。

---

### 16. 过程存储 vs Checkpoint 权威分离

**冻结区分:**

| 权威 | 拥有 |
|------|------|
| ReusableProcedureStore | 过程定义存在 |
| CheckpointStore | 当前执行在过程中的位置 |

**绝对不能放入 ReusableProcedureStore:**

- `currentStepIndex`
- 绑定的运行时值
- 批准暂停
- `checkpointVersion`
- `attemptId`

**这些属于 Checkpoint 或 InvocationStateStore。**

---

### 17. M6 更改分析

**答案: NO - M8 不需要更改冻结的 M6 语义**

**M8 需要的是附加的，而不是权威更改:**

1. **新事件类型 (非破坏性添加):**
   ```java
   EventType.TOOL_INVOKED  // 新增
   EventType.PROCEDURE_CANDIDATE_EXTRACTED  // 新增
   EventType.PROCEDURE_MATCHED  // 新增
   EventType.PROCEDURE_EXECUTED  // 新增
   ```

2. **过程执行状态 (作为附加有效负载):**
   
   不修改 `SuspensionCheckpoint` 字段。
   
   可以通过以下方式存储：
   - 新的并行存储（ProcedureExecutionStateStore）
   - 或将 JSON 放入 checkpoint 的扩展字段（如果存在）

3. **CHECK A/B 语义: 完全保留**
   
   过程执行仍然通过相同的 M6 管道。

**M6 不变式保留:**

- `operationId` 唯一性 ✅
- `InvocationStateStore` 意图 ✅
- CHECK A/B ✅
- 持久性语义 ✅
- 恢复分类 ✅

---

### 18. WAITING_FOR_SIGNAL 与过程

**流程验证:**

```
可复用过程执行
  ↓
Step N
  ↓
当前治理 = REQUIRE_APPROVAL
  ↓
M6 持久 WAITING_FOR_SIGNAL
  ↓
Checkpoint 包括:
  - pendingBatch (Step N operations)
  - ProcedureExecutionState:
      procedureId, revision, currentStepIndex=N
  ↓
重启（如有必要）
  ↓
M7 发现
  ↓
AgentRuntime.resumeProcess(...)
  ↓
CHECK A 加载 checkpoint
  ↓
恢复 ProcedureExecutionState
  ↓
继续 Step N 在批准后
  ↓
【不使过程无效】
  ↓
【不回退到 ReAct】
  ↓
继续相同的 procedureId + revision
```

**正确行为: 治理暂停不是过程失败。**

---

### 19. 物理工具执行 - 一个管道

**关键原则: 必须只有一个物理工具执行语义。**

**当前共同边界（基于源代码）:**

```
ProtocolReconstructor.executeOperation()
  ↓
recordInvocationIntent(processId, operationId, attemptId)
  ↓
ToolCallback.call(arguments, toolContext)
```

**M8 目标架构:**

```
【规划层】
  ├─> ReAct 规划
  │   → 产生 PendingToolCall 列表
  │
  └─> 可复用过程解析
      → 产生 PendingToolCall 列表
  
  ↓
  
【共同持久操作执行】
  ProtocolReconstructor.executeApprovedBatch()
    ↓
    对于每个 PendingToolCall:
      ↓
      recordInvocationIntent()  (M6-T4A)
      ↓
      ToolCallback.call()
      ↓
      emitToolExecuted()
```

**两个路径收敛到相同的 `PendingToolCall` 表示。**

**不创建 CachedPathExecutor → ToolCallback.call() 作为独立管道。**

---

### 20. 识别共同边界

**答案:**

**当前:**

`ProtocolReconstructor` 已经是共同执行边界。

它接受 `List<PendingToolCall>` 并执行它们。

**M8 扩展:**

```
【新增】ProcedurePlanner
  ↓
  根据 ReusableProcedure 生成 List<PendingToolCall>
  ↓
  （与 ReAct 产生的格式相同）
  ↓
【现有】ProtocolReconstructor.executeApprovedBatch()
  ↓
  （无需更改）
```

**关键类/方法:**

- **输入边界:** `List<PendingToolCall>`
- **执行:** `ProtocolReconstructor.executeApprovedBatch()`
- **输出:** `List<Message>` (ToolResponseMessages)

**最小重构:**

可能提取接口：

```java
public interface OperationProducer {
  List<PendingToolCall> planOperations(
      AgentDefinition definition,
      AgentRequest request,
      AgentExecutionContext context
  );
}

// 实现
class ReActOperationProducer implements OperationProducer {
  // 当前 ReAct 规划逻辑
}

class CachedProcedureOperationProducer implements OperationProducer {
  // 从 ReusableProcedure 生成 PendingToolCall
}
```

但**不立即创建**。当 M8 实施开始时再评估。

---

### 21. ExecutionStrategy 决策

**答案: NO - 不需要 ExecutionStrategy（至少现在不需要）**

**理由:**

共同边界是 `List<PendingToolCall>`，而不是 `AgentResult execute(...)`。

**更简单的组合:**

```
SpringAiToolCallingEngine:
  ↓
  检查 ProcedureResolver
  ↓
  如果找到匹配:
    → 使用 CachedProcedurePlanner
  否则:
    → 使用 ModelContinuationExecutor (正常 ReAct)
  ↓
  两者都产生 PendingToolCall 列表
  ↓
  传递给共同执行
```

**不需要新的公共 ExecutionStrategy 接口。**

**内部可能有:**

```java
// Package-private
interface OperationPlanner {
  List<PendingToolCall> plan(...);
}
```

但这不是公共 API。

---

### 22. 副作用分类

**当前工具元数据检查:**

当前源代码中，`ToolCallback` **不**公开副作用特征。

Spring AI 的 `ToolDefinition` 也没有标准的副作用字段。

**V1 决策:**

**M8 V1 限制可复用过程为只读/幂等工具。**

**实施:**

应用程序必须显式注册工具的副作用特征：

```java
@Bean
public ToolEffectMetadata queryLogsMetadata() {
  return ToolEffectMetadata.builder()
      .toolName("queryLogs")
      .effectType(EffectType.READ_ONLY)
      .build();
}
```

**可缓存性验证:**

```
if (procedure.steps.any(step -> 
    toolEffects.get(step.toolName) == NON_IDEMPOTENT_WRITE))
{
  reject("V1 does not support non-idempotent tools in cached procedures");
}
```

**不发明广泛的公共效果分类法，除非需要。**

---

### 23. 回退安全性

**分析:**

```
FAILURE BEFORE ANY PHYSICAL TOOL CALL:
  → ReAct 回退安全
  
FAILURE AFTER PARTIAL READ-ONLY/IDEMPOTENT EXECUTION:
  → ReAct 回退可能安全（如果观察可重用）
  
FAILURE AFTER NON-IDEMPOTENT SIDE EFFECTS:
  → ReAct 回退不安全（可能重复副作用）
```

**V1 决策:**

由于 V1 限制为只读/幂等工具：

**回退策略:**

```java
try {
  executeCachedProcedure(...)
} catch (ProcedureExecutionException e) {
  if (e.failureType == BINDING_FAILURE || 
      e.failureType == TOOL_MISSING ||
      e.failureType == SCHEMA_INCOMPATIBILITY) {
    // 在任何执行之前失败
    fallbackToReAct();
  } else if (e.failureType == VERIFICATION_FAILURE ||
             e.failureType == TOOL_TRANSIENT_FAILURE) {
    // 部分执行但所有工具幂等
    fallbackToReAct();  // V1 安全
  } else {
    // 未知失败
    fail();  // 不盲目回退
  }
}
```

**关键: M6 只保证至少一次，所以即使幂等工具，回退也可能导致重复。**

**V1 接受这一点作为权衡。**

---

### 24. 执行失败 ≠ 过程无效

**失败矩阵:**

| 失败类型 | 当前执行结果 | 回退? | 使过程无效? | 暂停? |
|----------|-------------|-------|-------------|-------|
| 绑定失败 | FAILED | Yes | No | No |
| 工具缺失 | FAILED | Yes | Yes | No |
| 模式不匹配 | FAILED | Yes | Yes | No |
| 治理 DENY | FAILED | No | No | No |
| 治理 REQUIRE_APPROVAL | WAITING | No | No | Yes |
| 工具瞬态失败 | FAILED | Yes | No | No |
| 工具永久失败 | FAILED | Yes | Maybe† | No |
| 验证失败 | FAILED | Yes | No† | No |
| 环境不匹配 | FAILED | Yes | No | No |
| 过期过程修订 | FAILED | Yes | No | No |
| M6 恢复不确定性 | Exception | No | No | No |

† 增加失败计数器。重复失败（例如 3 次）→ 使无效。

**关键区分:**

- **工具瞬态失败:** 网络超时、速率限制 → 不使无效
- **模式不兼容:** 工具定义改变 → 立即使无效

---

### 25. 结果生成

**M8 优化目标主要是:**

**避免重复规划**

**不是:**

**零 LLM 调用**

**潜在流程:**

```
请求
  ↓
确定性过程匹配
  ↓
确定性工具执行
  ↓
结构化工具结果
  ↓
【可选模型摘要】
  ↓
AgentResult
```

**V1 决策:**

```java
public record ReusableProcedure(
    ...
    ResultPresentation resultPresentation
) {}

public enum ResultPresentation {
  DETERMINISTIC,      // 直接结构化输出
  MODEL_SUMMARIZED    // 需要最终模型生成
}
```

**如果 MODEL_SUMMARIZED:**

执行后调用：

```java
ModelContinuationExecutor.summarizeToolResults(
    toolResults, 
    definition
)
```

这仍然比完整的 ReAct 规划便宜得多。

---

### 26. 可观测性事实

**未来事件（设计为可发射，不立即实施）:**

```java
EventType.PROCEDURE_CANDIDATE_EXTRACTED
EventType.PROCEDURE_PROMOTED
EventType.PROCEDURE_MATCHED
EventType.PROCEDURE_EXECUTED
EventType.PROCEDURE_VERIFICATION_PASSED
EventType.PROCEDURE_VERIFICATION_FAILED
EventType.PROCEDURE_FALLBACK_TO_REACT
EventType.PROCEDURE_INVALIDATED
```

**这些进入 ExecutionLedger。**

**ExecutionLedger 不成为过程权威。**

它保持历史投影。

---

### 27. 模块放置

**决策:**

```
arctra-core:
  - ReusableProcedure (domain model)
  - ReusableProcedureStore (interface)
  - ProcedureCandidate (domain model)
  - ProcedureVerification (interface)
  - 提供者无关语义

arctra-runtime-react:
  - ReActCandidateExtractor (从 ExecutionLedger)
  - CachedProcedurePlanner (生成 PendingToolCall)
  - 集成到 SpringAiToolCallingEngine

可能的新模块（仅在依赖需要时）:
  arctra-procedure:
    - ReusableProcedureStore 实现
    - 候选提取、验证、晋升逻辑
```

**理由:**

可复用过程语义不是 ReAct 特定的。

但候选提取**是** ReAct 特定的（从 ReAct 执行跟踪）。

因此核心模型在 `arctra-core`，ReAct 特定提取在 `arctra-runtime-react`。

---

### 28. 公共 API 预算

**M8 V1 最小公共 API:**

```java
// 操作员/管理
public interface ProcedureManagement {
  List<ProcedureCandidate> listCandidates();
  ReusableProcedure promoteCandidate(String candidateId);
  void invalidateProcedure(String procedureId);
}

// 应用程序意图注册
public interface IntentRegistration {
  void registerIntent(String intentKey, String procedureId, int revision);
}
```

**所有其他内容都保持 package-private 或内部，直到被证明需要。**

**不暴露:**
- 内部执行图机制
- ProcedurePlanner
- CandidateExtractor
- OperationProducer

---

### 29. 最终 M8 V1 建议

**选项 B (经评估的首选):**

```
自动候选提取
  - 从 ExecutionLedger 自动提取
  - 可缓存性验证
  
显式晋升
  - 操作员批准
  - 或自动验证器 + 手动激活
  
确定性匹配
  - 结构化 intentKey
  - 无语义/向量匹配
  
受限线性过程
  - 有序工具操作
  - 无条件/循环
  - 只读/幂等工具
```

**这包含真正的学习，同时保持 V1 安全。**

---

## 第三部分：架构图和映射

### 30. 目标架构图

```
                   Agent Request
                        │
                        ▼
              ┌─────────────────────┐
              │ ProcedureResolver   │
              │ (检查意图匹配)      │
              └─────────┬───────────┘
                        │
            ┌───────────┴───────────┐
            │ HIT                   │ MISS
            ▼                       ▼
    ┌──────────────────┐    ┌──────────────────┐
    │ CachedProcedure  │    │ ReAct Planning   │
    │ Planner          │    │ (ModelContinuation│
    │                  │    │ Executor)        │
    └─────────┬────────┘    └────────┬─────────┘
              │                      │
              │                      ├──→ 候选提取
              │                      │
              ▼                      ▼
         List<PendingToolCall>  List<PendingToolCall>
              │                      │
              └──────────┬───────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │ Common Durable       │
              │ Operation Execution  │
              │ (ProtocolReconstructor)│
              └──────────┬────────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │ M6 Durable Kernel    │
              │ - InvocationStateStore│
              │ - CheckpointStore    │
              │ - Governance         │
              └──────────┬────────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │ Verification         │
              │ (执行后)             │
              └──────────┬────────────┘
                         │
                 ┌───────┴───────┐
                 │               │
           PASS │               │ FAIL
                 ▼               ▼
          AgentResult      Fallback/Invalidate
```

**关键边界:**

- **规划层:** ReAct vs 缓存过程
- **共同操作层:** PendingToolCall 列表
- **M6 持久层:** 不变
- **M7 恢复层:** 不变

---

### 31. 权威表

| 事实 | 权威 |
|------|------|
| 当前继续状态 | CheckpointStore |
| 物理调用尝试 | InvocationStateStore |
| 历史执行 | ExecutionLedger |
| 证据/证明 | Evidence (通过 AgentResult) |
| 对话 | ChatMemory |
| **可复用可执行知识** | **ReusableProcedureStore** (新) |
| **当前过程执行位置** | **Checkpoint 扩展或并行存储** (新) |
| 批准/授权 | 当前 Governance 评估 |
| 工具模式指纹 | ToolRegistry (扩展) |

**无重叠。**

---

### 32. 源映射表

| M8 概念 | 现有 Arctra 概念 | 源位置 | 重用/扩展/新 | 权威影响 |
|---------|------------------|--------|--------------|---------|
| ExecutionTrace | ExecutionLedger query result | ExecutionLedger | 重用 | 无 - 只读 |
| PendingToolCall | PendingToolCall | checkpoint.PendingToolCall | 重用 | 无 - 重用格式 |
| operationId | operationId | checkpoint.PendingToolCall | 重用 | 无 |
| ProcedureCandidate | - | 新 | 新 | 新（候选存储） |
| ReusableProcedure | - | 新 | 新 | 新（过程存储） |
| ProcedureExecution | - | 新 | 新 | 新（临时状态） |
| 工具执行 | ProtocolReconstructor | runtime.react.protocol | 重用 | 无 - 保持不变 |
| 治理 | GovernanceToolCallingAdvisor | runtime.react.governance | 重用 | 无 - 保持不变 |
| Checkpoint | SuspensionCheckpoint | checkpoint | 扩展（payload） | M6 核心字段不变 |
| Evidence | Evidence | evidence | 重用 | 无 |
| 验证 | - | 新 | 新 | 新（验证接口） |

---

### 33. M8 实施轨道

**如果架构被批准，建议实施轨道:**

```
M8-A: 过程语义模型 + 存储权威
  - ReusableProcedure domain model
  - ReusableProcedureStore interface + impl
  - ProcedureCandidate model
  - 不可变修订语义
  - 2-3 days

M8-B: ReAct 跟踪提取 + 候选生成
  - 添加 TOOL_INVOKED event type
  - ReActCandidateExtractor
  - 可缓存性验证
  - 工具模式指纹
  - 2-3 days

M8-C: 晋升 + 确定性解析
  - ProcedureManagement API
  - IntentRegistration
  - ProcedureResolver (intentKey 匹配)
  - 2-3 days

M8-D: 持久过程执行
  - CachedProcedurePlanner (生成 PendingToolCall)
  - 集成到 SpringAiToolCallingEngine
  - Checkpoint 扩展过程执行状态
  - 跨重启恢复测试
  - 3-4 days

M8-E: 验证 + 回退
  - ProcedureVerification interface
  - 确定性谓词验证器
  - 验证失败处理
  - 回退到 ReAct 逻辑
  - 失败后过程失效
  - 2-3 days

M8-F: 集成测试 + 闭环
  - 端到端场景测试
  - 自动候选提取测试
  - 过程匹配测试
  - 参数绑定测试
  - 工具模式不兼容测试
  - 治理重新评估测试
  - 批准暂停/恢复测试
  - 跨运行时过程恢复测试
  - 回退安全测试
  - M6 至少一次保留验证
  - 3-4 days

总计: ~17-23 days
```

**不立即实施这些。**

**首先需要架构批准。**

---

### 34. 关键测试策略

**M8 阻塞测试（必须通过）:**

1. **自动候选提取**
   - 成功 ReAct 执行 → 自动生成候选

2. **不可缓存跟踪拒绝**
   - 包含动态推理 → 拒绝
   - 包含秘密 → 拒绝
   - 包含非幂等工具 → 拒绝

3. **晋升和匹配**
   - 晋升候选 → 活动过程
   - intentKey 匹配 → 使用过程

4. **参数绑定**
   - INPUT 绑定工作
   - PREVIOUS_STEP_OUTPUT 绑定工作
   - CONSTANT 绑定工作

5. **工具模式不兼容**
   - 工具模式改变 → 拒绝过程
   - 回退到 ReAct

6. **治理重新评估**
   - 过去的批准不意味着当前授权
   - 每次都重新运行治理

7. **批准暂停/恢复**
   - 过程执行 → REQUIRE_APPROVAL → 暂停
   - 恢复 → 继续相同过程
   - 不使无效

8. **跨运行时过程恢复**
   - Runtime A: 执行过程到一半
   - Runtime B: 恢复相同 procedureId + revision
   - 继续从 currentStepIndex

9. **验证失败**
   - 验证失败 → 记录
   - 重复失败 → 使无效

10. **安全回退**
    - 幂等工具 → 回退允许
    - 部分执行 → 观察传递（如果可能）

11. **M6 至少一次保留**
    - 过程执行通过相同的 M6 管道
    - operationId 唯一性保留
    - InvocationStateStore 意图保留
    - CHECK A/B 保留

**不复活历史测试考古学。**

**专注于 M8 契约测试。**

---

## 第四部分：最终决策摘要

### 35. 必需的 25 个明确决策

1. **新可复用知识权威?** ✅ YES - ReusableProcedureStore
2. **最终术语?** ✅ ReusableProcedure / ProcedureCandidate / ProcedureExecution
3. **可缓存性规则?** ✅ 线性、幂等、无动态推理、有验证器
4. **线性/DAG/图?** ✅ 线性（V1）
5. **参数绑定模型?** ✅ INPUT / CONSTANT / PREVIOUS_STEP_OUTPUT / RUNTIME_CONTEXT
6. **前输出绑定?** ✅ JSON Pointer 或简单点表示法
7. **工具兼容性?** ✅ toolName + inputSchemaHash
8. **自动候选提取?** ✅ YES - 从 ExecutionLedger
9. **晋升规则?** ✅ 显式（操作员批准）
10. **验证规则?** ✅ 确定性谓词 + Evidence 模式 + 外部确认
11. **匹配规则?** ✅ 结构化 intentKey（无语义/向量）
12. **过程范围?** ✅ AgentDefinition.name()
13. **不可变修订?** ✅ YES - (procedureId, revision)
14. **currentStep 权威?** ✅ Checkpoint 扩展或并行存储
15. **Checkpoint 集成?** ✅ 附加有效负载，不修改核心字段
16. **治理行为?** ✅ 总是重新评估（永不缓存授权）
17. **WAITING_FOR_SIGNAL 行为?** ✅ 暂停正常，恢复继续相同过程
18. **副作用限制?** ✅ V1 仅只读/幂等
19. **回退规则?** ✅ 在幂等工具后允许
20. **失败失效规则?** ✅ 模式不兼容立即失效；瞬态失败不失效；重复验证失败失效
21. **共同持久执行边界?** ✅ ProtocolReconstructor.executeApprovedBatch()
22. **需要 ExecutionStrategy?** ❌ NO
23. **模块放置?** ✅ core(模型) + runtime-react(提取) + 可能新模块(存储)
24. **公共 API?** ✅ 最小：ProcedureManagement + IntentRegistration
25. **M8 更改 M6 权威语义?** ❌ NO - 仅附加事件类型和可选状态

---

### 36. 性能声明清理

**删除未经证实的声明:**

- ❌ "10-100x 更快"
- ❌ "10x 接受标准"
- ❌ "<5% 误报"

**替换为:**

- ✅ "预期减少重复规划延迟"
- ✅ "预期降低模型规划成本"
- ✅ "更确定性的重复执行"
- ✅ "需要在实施后进行基准测试"

---

## 最终架构门控决策

经过全面的源代码真相检查、架构分析和具体决策，我对 M8 执行路径学习提出以下门控决定：

---

**🟢 GO — M8 EXECUTION PATH LEARNING V1 ARCHITECTURE APPROVED**

**有以下条件和边界：**

### 批准范围

**M8 V1 将实施:**

1. ✅ **自动候选提取** - 从成功的 ReAct 执行（真正的学习）
2. ✅ **显式晋升** - 操作员批准（安全优先）
3. ✅ **确定性匹配** - 结构化 intentKey（无语义/向量）
4. ✅ **受限线性过程** - 有序工具操作（无条件/循环）
5. ✅ **仅幂等工具** - 只读或幂等工具（回退安全）
6. ✅ **不可变修订** - (procedureId, revision) 语义（暂停安全）
7. ✅ **治理重新评估** - 永不缓存授权决策
8. ✅ **M6 集成** - 通过现有持久管道（无权威更改）
9. ✅ **确定性验证** - 谓词 + 模式验证器
10. ✅ **安全回退** - 回退到 ReAct 与失败分类

### 架构不变式

**M8 必须保留:**

- ✅ M6 持久性语义（operationId, InvocationStateStore, CHECK A/B）
- ✅ M7 恢复语义（发现、恢复控制平面）
- ✅ 单一物理工具执行管道
- ✅ 治理是当前决策（不是历史）
- ✅ CheckpointStore 拥有当前继续
- ✅ ReusableProcedureStore 拥有过程定义（新权威）
- ✅ ExecutionLedger 保持历史投影（不是活动权威）

### 实施要求

1. **不修改 M6 核心字段** - 仅附加事件类型和可选状态
2. **通过 ProtocolReconstructor** - 所有工具执行
3. **Checkpoint 扩展** - 过程执行状态作为附加有效负载
4. **完整测试覆盖** - 上述 11 个阻塞测试
5. **最小公共 API** - 仅 ProcedureManagement + IntentRegistration

### 明确延期到 V2+

- ❌ 条件/分支（未来考虑确定性谓词）
- ❌ DAG 依赖（未来如果需要）
- ❌ 非幂等工具（需要高级回退语义）
- ❌ 语义/向量匹配（需要嵌入基础设施）
- ❌ 自动晋升（需要更多验证数据）
- ❌ LLM 验证器（过于复杂 V1）
- ❌ 跨 agent 共享（需要更多隔离分析）
- ❌ 多租户（当前不存在）

### 风险和缓解

| 风险 | 缓解 |
|------|------|
| 过度泛化 | 严格 intentKey 匹配 + 工具指纹 |
| 回退循环 | 失败计数 + 自动失效 |
| 治理绕过 | 总是重新运行治理（冻结不变式） |
| 副作用重复 | V1 仅幂等 + at-least-once 文档 |
| 过程陈旧 | 工具模式指纹 + 失败失效 |

### 成功标准

**M8 V1 被认为成功，如果:**

1. 至少一个真实场景展示自动候选提取
2. 显式晋升后过程匹配和执行工作
3. 治理在缓存执行期间重新评估
4. 批准暂停/恢复保留过程连续性
5. 跨运行时恢复工作（相同过程修订）
6. 回退到 ReAct 在失败时是安全的
7. 所有 M6 不变式保留（ArchUnit 测试）
8. 零 M6 权威更改
9. 可观测性事件捕获学习周期
10. 文档清楚地解释了什么被缓存（结构）和什么不被缓存（授权、CoT）

---

### 下一步

1. ⏸️ **不立即开始实施**
2. 📋 **人工审查此门控决定**
3. ✅ **如果批准** → 创建详细的 M8-A 任务
4. ❌ **如果拒绝** → 识别阻塞问题并重新评估
5. 📊 **如果批准** → 在实施后建立基准

---

**此架构门控现已完成。**

**等待人工最终批准再继续 M8 实施。**

---

**@author lov3r**  
**Date: 2026-09-20**  
**Status: ARCHITECTURE GATE COMPLETE — AWAITING APPROVAL**

---

# M8 架构纠正 (ARCHITECTURE CORRECTION)

**Date: 2026-09-20 (Second Pass)**

本节解决原始架构门控中发现的三个硬性架构矛盾。

---

## 硬性问题 #1 — 缓存执行不得绕过治理

### 问题陈述

原始架构提议：

```
ReusableProcedure
→ CachedProcedurePlanner  
→ List<PendingToolCall>
→ ProtocolReconstructor.executeApprovedBatch()
```

方法名 `executeApprovedBatch()` 强烈暗示批次已通过治理。

这是错误的。**缓存过程操作必须通过当前治理评估。**

### 源代码真相 - 实际治理管道

通过检查源代码，实际流程为：

```java
// GovernanceToolCallingAdvisor.adviseCall()

1. ChatModel 生成 AssistantMessage.ToolCall 列表

2. 治理预检（PHASE 1）:
   for each ToolCall:
     decision = governancePolicy.evaluate(toolName, arguments, context)
   
3. 决策优先级（PHASE 2）:
   if (hasDeny) → 拒绝整个批次
   if (hasRequireApproval) → 暂停整个批次
   if (hasAllow + DURABLE) → 持久化物化信号
   if (hasAllow + EPHEMERAL) → Spring AI 执行

4. DURABLE + ALLOW 路径:
   throw ToolApprovalRequiredSignal(disposition=RUNNABLE)
   ↓
   SpringAiToolCallingEngine 捕获
   ↓
   ExecutionFlowCoordinator.route() → DurableExecutionHandler
   ↓
   构建 SuspensionCheckpoint (disposition=RUNNABLE, pendingBatch)
   ↓
   CheckpointStore.create()
   ↓
   DurableContinuationExecutor.executeFromCheckpoint()
   ↓
   ProtocolReconstructor.executeApprovedBatch()
```

**关键发现：**

1. **治理在 AssistantMessage.ToolCall 级别发生**（Spring AI 协议对象）
2. **治理发生在 Advisor 内部**，在工具执行之前
3. **PendingToolCall 在治理之后物化**（在 DurableExecutionHandler 中）
4. **ProtocolReconstructor 确实是"已批准"边界** — 它接收已通过治理的 PendingToolCall

### 纠正的 M8 架构

**缓存过程步骤必须产生类似 ToolCall 的请求，然后通过相同的治理管道。**

但是，存在一个问题：

- **ReAct** 路径有真实的 `ChatModel` 生成 `AssistantMessage.ToolCall`
- **缓存过程** 路径没有 ChatModel 调用

**解决方案：提取提供者无关的治理评估器**

创建一个内部治理评估组件，GovernanceToolCallingAdvisor 和缓存过程执行器都可以使用：

```java
// 新建 - 内部包私有
class OperationGovernanceEvaluator {
  
  record OperationRequest(
    String toolName,
    String arguments
  ) {}
  
  record BatchGovernanceResult(
    List<GovernanceDecision> decisions,
    GovernanceDecision effectiveDecision  // DENY / REQUIRE_APPROVAL / ALLOW
  ) {}
  
  BatchGovernanceResult evaluateBatch(
    List<OperationRequest> operations,
    ToolGovernancePolicy policy,
    AgentExecutionContext context
  ) {
    List<GovernanceDecision> decisions = operations.stream()
      .map(op -> policy.evaluate(op.toolName(), op.arguments(), context))
      .toList();
    
    // 应用决策优先级
    if (decisions.contains(DENY)) return DENY;
    if (decisions.contains(REQUIRE_APPROVAL)) return REQUIRE_APPROVAL;
    return ALLOW;
  }
}
```

**纠正的缓存过程执行流程：**

```
ProcedureExecutionCoordinator.executeStep(currentStepIndex)
  ↓
绑定当前步骤参数
  ↓
物化 OperationRequest
  ↓
OperationGovernanceEvaluator.evaluateBatch([operation])
  ↓
  ├─ DENY → 构造拒绝响应，继续模型
  ├─ REQUIRE_APPROVAL → 创建 WAITING_FOR_SIGNAL checkpoint
  └─ ALLOW → 继续执行
  ↓
如果 ALLOW:
  创建 PendingToolCall(operationId, toolCallId, toolName, arguments)
  ↓
  如果 DURABLE:
    创建/更新 checkpoint (disposition=RUNNABLE)
  ↓
  ProtocolReconstructor.executeOperation()
    (现有 M6 管道)
```

**GovernanceToolCallingAdvisor 保持不变** — 它仍然处理 Spring AI 协议的 AssistantMessage.ToolCall。

**两条路径在已治理操作处汇合：**

```
ReAct (via GovernanceToolCallingAdvisor)
  → AssistantMessage.ToolCall
  → 治理评估
  → PendingToolCall 物化
  ↓
缓存过程 (via OperationGovernanceEvaluator)  
  → OperationRequest
  → 治理评估
  → PendingToolCall 物化
  ↓
【共同边界】
  List<PendingToolCall> (已通过治理)
  ↓
  ProtocolReconstructor.executeApprovedBatch()
```

### 治理不变式（已纠正）

✅ **缓存过程步骤通过当前治理评估**  
✅ **不缓存历史授权决策**  
✅ **REQUIRE_APPROVAL 仍然暂停（WAITING_FOR_SIGNAL checkpoint）**  
✅ **DENY 仍然拒绝**  
✅ **只有一个治理语义权威（ToolGovernancePolicy）**

---

## 硬性问题 #2 — PREVIOUS_STEP_OUTPUT 使得整体路径物化不可能

### 问题陈述

原始架构支持：

```
Step 1: getService(name = INPUT.service)
Step 2: queryLogs(serviceId = STEP[1].output.id)
```

Step 2 的参数在 Step 1 执行之前无法完全物化。

但原始架构也说：

```
ReusableProcedure
→ 生成 List<PendingToolCall>  
→ executeApprovedBatch(List<PendingToolCall>)
```

**这两个决策冲突。**

### 纠正：V1 必须逐步执行过程

**M8 V1 必须使用逐步执行循环：**

```
ProcedureExecutionState {
  procedureId: string
  procedureRevision: int
  currentStepIndex: int
  boundInputs: Map<String, Object>
  capturedStepOutputs: Map<Int, StepOutput>
}

执行循环：
  while (currentStepIndex < procedure.steps.size()) {
    
    currentStep = procedure.steps[currentStepIndex]
    
    // 使用运行时状态绑定参数
    resolvedArguments = bindParameters(
      currentStep.parameterBindings,
      boundInputs,
      capturedStepOutputs,
      runtimeContext
    )
    
    // 物化一个操作
    operation = OperationRequest(
      currentStep.toolName,
      resolvedArguments
    )
    
    // 治理（如上节所述）
    governanceResult = evaluator.evaluateBatch([operation], policy, context)
    
    if (governanceResult == DENY) {
      // 构造拒绝，继续模型
      break;
    }
    
    if (governanceResult == REQUIRE_APPROVAL) {
      // 创建 checkpoint with currentStepIndex, capturedStepOutputs
      checkpoint = new SuspensionCheckpoint(
        ...
        disposition = WAITING_FOR_SIGNAL,
        pendingBatch = [PendingToolCall from operation],
        procedureExecutionState = {
          procedureId, procedureRevision, currentStepIndex,
          boundInputs, capturedStepOutputs
        }
      )
      checkpointStore.create(checkpoint)
      return AgentProcess(WAITING)
    }
    
    // ALLOW - 执行
    pendingCall = new PendingToolCall(
      generateOperationId(),
      generateToolCallId(),
      currentStep.toolName,
      resolvedArguments
    )
    
    result = executeOperation(pendingCall)
    
    // 捕获输出供未来步骤使用
    if (currentStep.outputExtraction != null) {
      capturedStepOutputs[currentStepIndex] = extractOutput(result)
    }
    
    // 推进
    currentStepIndex++
    
    // 如果 DURABLE，更新 checkpoint
    if (context.durability() == DURABLE) {
      checkpoint = new SuspensionCheckpoint(
        ...
        checkpointVersion++,
        disposition = RUNNABLE,
        pendingBatch = [], // 当前步骤已完成
        procedureExecutionState = {
          procedureId, procedureRevision, currentStepIndex,
          boundInputs, capturedStepOutputs
        }
      )
      checkpointStore.replaceIfVersion(checkpoint)
    }
  }
  
  // 所有步骤完成 - 可选模型摘要
  return generateResult(capturedStepOutputs)
```

### 物化时间（已纠正）

**过程定义存储：**
- 工具标识
- 参数绑定规则
- 工具指纹
- 步骤顺序

**不存储最终运行时参数。**

**只有当 step N 变为可执行时，Arctra 才解析绑定并物化：**

```
PendingToolCall(
  operationId,      // 在物化时新生成
  toolCallId,       // 在物化时新生成  
  toolName,
  arguments         // 在物化时解析
)
```

### 操作标识必须在物化时创建

**不要持久化历史 operationId/toolCallId** 从学习的 ReAct 执行到 ReusableProcedure 中以供重放。

ReusableProcedure 描述操作**结构**。

每个新的 ProcedureExecution 创建**新的**运行时操作标识。

不可变过程标识：

```
procedureId + revision
```

不是：

```
operationId
```

---

## 硬性问题 #3 — 当前过程位置有一个权威

### 问题陈述

原始架构说：

> 当前过程执行位置  
> → Checkpoint 扩展或并行存储

**此歧义不可接受。**

### 纠正：CheckpointStore 是唯一的继续权威

**冻结的不变式：**

**CheckpointStore 是唯一权威：**

- `procedureId`
- `procedureRevision`  
- `currentStepIndex`
- 恢复所需的绑定运行时输入
- 未来绑定所需的步骤输出
- 恢复相同过程所需的执行模式

**不要引入 ProcedureExecutionStateStore 作为第二个持久当前状态权威。**

### 无并行执行状态存储

移除提议：

```
ProcedureExecutionStateStore
```

**除非它仅仅是非权威缓存/投影。**

**不要创建必须与 CheckpointStore 保持一致才能正确恢复的持久存储。**

否则会出现崩溃窗口：

```
Checkpoint N 已提交
但 ProcedureExecutionState 未提交
```

或：

```
ProcedureExecutionState N+1 已提交  
但 Checkpoint 仍为 N
```

**那会创建分裂的恢复真相。**

M6 故意避免这种情况。

### 过程执行状态必须随继续一起传递

**解决方案：扩展 SuspensionCheckpoint 以包含可选的过程执行状态。**

通过检查源代码，`SuspensionCheckpoint` 是一个 Java record，具有明确定义的模式版本：

```java
public record SuspensionCheckpoint(
    String schemaVersion,  // 当前 "1.2"
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,
    ContinuationDisposition disposition,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences,
    String executionEpoch
) {}
```

**M8 添加（架构决策）：**

增加可选字段（模式 1.3）：

```java
public record SuspensionCheckpoint(
    ...
    String executionEpoch,
    ProcedureExecutionState procedureState  // 新增，nullable
) {}

public record ProcedureExecutionState(
    String procedureId,
    int procedureRevision,
    int currentStepIndex,
    Map<String, Object> boundInputs,
    Map<Integer, StepOutput> capturedStepOutputs
) {}

public record StepOutput(
    String rawResult,      // 工具原始返回
    Map<String, Object> extracted  // 提取的字段
) {}
```

**模式演变：**

```
1.0: M5 原始
1.1: M6-T4F 添加 executionEpoch
1.2: M6-T6.4 添加 disposition  
1.3: M8 添加 procedureState (可为空)
```

**向后兼容：**
- `procedureState = null` → 传统 ReAct checkpoint
- `procedureState != null` → 缓存过程 checkpoint

**checkpointVersion 的 CAS 保护整个状态转换。**

### 步骤输出持久性

**问题：** 如果 Step 2 使用 Step 1 输出，然后在 Step 1 之后崩溃，Step 1 的确切输出从哪里来？

**M8 决策：**

**步骤输出通过 checkpoint 的 `ProcedureExecutionState.capturedStepOutputs` 传递。**

**不从以下重建：**
- Evidence（Evidence 是证明，不是工具结果权威）
- InvocationStateStore（M6 T5 用于恢复分类，不是结果存储）

**原理：**

1. 步骤输出必须**精确**（不是近似）
2. 步骤输出**小**（只有 outputExtraction 指定的提取字段）
3. Checkpoint 已经承载继续状态
4. checkpointVersion CAS 保护完整状态

**示例：**

```java
Step 1 执行：
  result = toolCallback.call(...)  // "{"id": "svc-123", "name": "api", ...}"
  
  if (step.outputExtraction.outputField == "id") {
    capturedStepOutputs[1] = new StepOutput(
      rawResult = result,
      extracted = {"id": "svc-123"}
    )
  }
  
  checkpoint = new SuspensionCheckpoint(
    ...
    checkpointVersion = 2,
    procedureState = new ProcedureExecutionState(
      currentStepIndex = 2,  // 下一步
      capturedStepOutputs = {1: StepOutput(...)}
    )
  )
  
  checkpointStore.replaceIfVersion(checkpoint)
```

**崩溃后恢复：**

```java
checkpoint = checkpointStore.load(processId)
procedureState = checkpoint.procedureState()

// Step 1 输出可用
step1Output = procedureState.capturedStepOutputs().get(1)
serviceId = step1Output.extracted().get("id")  // "svc-123"

// Step 2 现在可以绑定
step2Args = bindParameters(
  procedure.steps[2].parameterBindings,
  procedureState.boundInputs(),
  procedureState.capturedStepOutputs(),
  context
)
```

### 逐步暂停模型（证明崩溃窗口）

**CASE A: 步骤 N 尚未物化**

```
崩溃
→ 恢复加载 checkpoint (currentStepIndex = N)
→ 相同步骤 N 用新操作标识物化
```

✅ 正确

**CASE B: 步骤 N 已物化并提交 checkpoint**

```
checkpoint 已提交
→ 崩溃在物理调用之前
→ M6 恢复分类 (DEFINITELY_NOT_DISPATCHED)
→ 新尝试
```

✅ M6 处理

**CASE C: 步骤 N MAY_HAVE_INVOKED**

```
M6 T5 不确定性适用
→ RecoveryUncertaintyException
→ 需要操作员解析
```

✅ M6 处理

**CASE D: 步骤 N RESOLVED_EXECUTED**

```
崩溃在推进到 N+1 之前
→ 恢复
→ 确切结果从 InvocationStateStore 恢复（M6 T5）
→ 捕获到 capturedStepOutputs
→ 安全推进而不盲目重放
```

✅ M6 T5 + checkpoint 更新

**CASE E: 步骤 N 完成，继续推进到 N+1**

```
checkpoint 更新为 currentStepIndex = N+1
→ 崩溃
→ 恢复从 N+1 开始，永不将 N 重新物化为新逻辑步骤
```

✅ 正确

### 治理 + 逐步执行

**证明：**

```
Step N 已物化
↓
治理 REQUIRE_APPROVAL
↓
WAITING_FOR_SIGNAL checkpoint

Checkpoint 包含:
  - pendingBatch: [Step N 的 PendingToolCall]
  - procedureState:
      procedureId, revision, currentStepIndex=N
      capturedStepOutputs (步骤 0..N-1)
↓
重启（如有必要）
↓
M7 发现
↓
AgentRuntime.resumeProcess(...)
↓
CHECK A 加载 checkpoint
↓
恢复 ProcedureExecutionState
↓
继续 Step N 在批准后
  (使用相同的 operationId - M6 现有语义)
↓
【不使过程无效】
↓
【不回退到 ReAct】
↓
继续相同 procedureId + revision
```

✅ 治理暂停不是过程失败

### 未物化步骤 vs 已物化待处理操作

**概念区分（不一定是公共枚举）：**

**未物化步骤：**
- 过程定义 + 执行位置决定未来操作
- 尚无 operationId

**已物化待处理操作：**
- M6 PendingToolCall 标识成为该逻辑工具操作的权威
- 具有 operationId, toolCallId

**这是关键的所有权边界。**

---

## 纠正的架构摘要

### 共同边界（最终答案）

**共同语义边界：已治理的持久操作**

```
【规划层】
  ReAct 规划 (ChatModel)
    → AssistantMessage.ToolCall
    → GovernanceToolCallingAdvisor 评估
    → 如果 ALLOW + DURABLE: PendingToolCall 物化
  
  过程步骤执行 (ProcedureExecutionCoordinator)  
    → OperationRequest
    → OperationGovernanceEvaluator 评估  
    → 如果 ALLOW + DURABLE: PendingToolCall 物化

【共同边界】
  List<PendingToolCall> (已通过治理，持久化意图)
  ↓
【M6 持久执行内核】
  ProtocolReconstructor.executeApprovedBatch()
  ↓
  recordInvocationIntent() (M6-T4A)
  ↓
  ToolCallback.call()
  ↓
  emitToolExecuted()
  ↓
  Evidence 捕获
```

**ProtocolReconstructor 保持为内部实现细节。**

它确实是共同执行边界，但不提升为公共 API。

### 过程执行是 M6 之上的协调循环

```java
// 概念 - 不一定是这个确切的类名
class ProcedureExecutionCoordinator {
  
  AgentResult execute(
    ReusableProcedure procedure,
    Map<String, Object> inputs,
    AgentExecutionContext context
  ) {
    
    ProcedureExecutionState state = new ProcedureExecutionState(
      procedure.procedureId(),
      procedure.revision(),
      0,  // currentStepIndex
      inputs,
      Map.of()  // capturedStepOutputs
    );
    
    while (state.currentStepIndex() < procedure.steps().size()) {
      
      ProcedureStep currentStep = procedure.steps().get(state.currentStepIndex());
      
      // 绑定参数
      Map<String, Object> resolvedArgs = bindParameters(
        currentStep.parameterBindings(),
        state.boundInputs(),
        state.capturedStepOutputs(),
        context
      );
      
      // 物化操作
      OperationRequest operation = new OperationRequest(
        currentStep.toolName(),
        toJson(resolvedArgs)
      );
      
      // 治理
      BatchGovernanceResult governance = evaluator.evaluateBatch(
        List.of(operation), 
        governancePolicy, 
        context
      );
      
      if (governance.effectiveDecision() == DENY) {
        // 构造拒绝，退出循环
        break;
      }
      
      if (governance.effectiveDecision() == REQUIRE_APPROVAL) {
        // 创建 WAITING_FOR_SIGNAL checkpoint
        createCheckpoint(state, operation, WAITING_FOR_SIGNAL);
        return suspendedResult();
      }
      
      // ALLOW - 执行通过 M6
      PendingToolCall pendingCall = materializePendingCall(currentStep, resolvedArgs);
      
      if (context.durability() == DURABLE) {
        createOrUpdateCheckpoint(state, List.of(pendingCall), RUNNABLE);
      }
      
      String result = executeViaM6Pipeline(pendingCall);
      
      // 捕获输出
      if (currentStep.outputExtraction() != null) {
        StepOutput output = extractOutput(result, currentStep.outputExtraction());
        state = state.withCapturedOutput(state.currentStepIndex(), output);
      }
      
      // 推进
      state = state.withCurrentStepIndex(state.currentStepIndex() + 1);
      
      if (context.durability() == DURABLE) {
        updateCheckpoint(state);
      }
    }
    
    // 所有步骤完成
    return generateResult(state.capturedStepOutputs(), procedure.resultPresentation());
  }
}
```

**此协调器：**
- 拥有协调语义
- **不是**第二个持久内核
- 委托给 M6 用于持久操作执行

### 权威表（已纠正 - 无歧义）

| 事实 | 权威 |
|------|------|
| 可复用过程定义 | ReusableProcedureStore |
| **当前继续包括过程执行位置** | **CheckpointStore** |
| 已物化逻辑待处理操作 | SuspensionCheckpoint (M6 现有) |
| 物理尝试 | InvocationStateStore |
| 历史执行 | ExecutionLedger |
| 证据 | Evidence (通过 AgentResult) |
| 对话 | ChatMemory |
| 治理决策 | 当前 Governance 评估 |

**无 OR。无并行存储。**

### M6 更改决策（最终）

**答案：NO 权威更改**

**可接受的附加更改：**

```
SuspensionCheckpoint 添加可选字段:
  - procedureState: ProcedureExecutionState (nullable)

模式版本: 1.2 → 1.3
```

**可接受因为：**
- 相同的 CheckpointStore 权威
- 相同的 checkpointVersion CAS
- 相同的 CHECK A/B
- 向后兼容（procedureState 可为空）
- 相同的 InvocationStateStore 语义

**不可接受：**
- 新的 CHECK 语义
- 新的调用权威
- 新的物理执行路径
- 新的恢复权威
- 并行过程状态存储

### 纠正的目标架构图

```
                   Agent Request
                        │
                        ▼
              ┌─────────────────────┐
              │ ProcedureResolver   │
              │ (检查意图匹配)       │
              └─────────┬───────────┘
                        │
            ┌───────────┴───────────┐
            │ HIT                   │ MISS
            ▼                       ▼
    ┌──────────────────┐    ┌──────────────────┐
    │ Procedure        │    │ ReAct Planning   │
    │ Execution        │    │ (ModelContinuation│
    │ Coordinator      │    │ Executor)        │
    │                  │    │                  │
    │ [逐步循环]       │    │                  │
    │  - 绑定参数      │    │                  │
    │  - 物化操作      │    │                  │
    └─────────┬────────┘    └────────┬─────────┘
              │                      │
              │                      ├──→ 候选提取
              │                      │
              ▼                      ▼
         OperationRequest    AssistantMessage.ToolCall
              │                      │
              ▼                      ▼
    OperationGovernance    GovernanceToolCalling
    Evaluator              Advisor
              │                      │
              └──────────┬───────────┘
                         │
                【共同治理门控】
                         │
                DENY / REQUIRE_APPROVAL / ALLOW
                         │
                         ▼
            持久操作物化 (PendingToolCall)
                         │
                         ▼
              ┌──────────────────────┐
              │ M6 Durable Kernel    │
              │ - CheckpointStore    │
              │ - InvocationStateStore│
              │ - ProtocolReconstructor│
              └──────────┬────────────┘
                         │
                         ▼
                   工具观察
                         │
                 ┌───────┴───────┐
                 │               │
           过程推进        ReAct 模型
           (next step)     继续
```

---

## ExecutionLedger 候选提取（安全纠正）

### 问题

原始建议添加：

```
TOOL_INVOKED event with full arguments
```

**问题：** 参数可能包含：
- 秘密
- PII
- 大有效负载
- 临时凭据

### 纠正

**选项 A（推荐）：专用学习投影**

在过程执行期间，直接捕获候选：

```java
// 在 ProcedureExecutionCoordinator 中
if (executionSucceeded && isCacheable(executionTrace)) {
  ProcedureCandidate candidate = extractCandidate(executionTrace);
  candidateStore.save(candidate);
}
```

不要求 ExecutionLedger 成为原始参数存档。

**选项 B：带清理的 TOOL_INVOKED**

```java
TOOL_INVOKED event payload: {
  "operationId": "...",
  "toolName": "...",
  "argumentsFingerprint": "sha256(...)",  // 不是完整参数
  "bindingHints": {
    "serviceName": "INPUT.serviceName",
    "environment": "CONSTANT[prod]"
  }
}
```

记录**绑定结构**，不是**实际值**。

### 秘密检测（纠正）

**按名称/模式检测秘密不足。**

**V1 安全方法：**

**仅捕获显式合格的绑定：**

```java
enum BindingCapturability {
  SAFE_TO_CAPTURE,   // INPUT, PREVIOUS_STEP_OUTPUT references
  CONSTANT_ALLOWED,  // 显式白名单常量
  NEVER_CAPTURE      // 默认
}
```

**白名单比黑名单更安全。**

### Ledger 不得成为学习数据库

ExecutionLedger 记录历史学习事件。

但不要将其扩展为：
- 完整过程训练数据集
- 原始参数存储
- 活动过程权威

**ProcedureCandidate 本身是正确的持久提取工件。**

---

## 纠正的最终决策摘要

### 25 个必需决策（已更新）

1. **新可复用知识权威?** ✅ YES - ReusableProcedureStore
2. **最终术语?** ✅ ReusableProcedure / ProcedureCandidate / ProcedureExecution  
3. **可缓存性规则?** ✅ 线性、幂等、无动态推理、有验证器
4. **线性/DAG/图?** ✅ 线性（V1）
5. **参数绑定模型?** ✅ INPUT / CONSTANT / PREVIOUS_STEP_OUTPUT / RUNTIME_CONTEXT
6. **前输出绑定?** ✅ 简单字段提取（不是任意表达式）
7. **工具兼容性?** ✅ toolName + inputSchemaHash
8. **自动候选提取?** ✅ YES - 从成功执行直接提取
9. **晋升规则?** ✅ 显式（操作员批准）
10. **验证规则?** ✅ 确定性谓词 + Evidence 模式
11. **匹配规则?** ✅ 结构化 intentKey（无语义/向量）
12. **过程范围?** ✅ AgentDefinition.name()
13. **不可变修订?** ✅ YES - (procedureId, revision)
14. **currentStep 权威?** ✅ **CheckpointStore（通过 procedureState 字段）**
15. **Checkpoint 集成?** ✅ **添加可选 procedureState 字段（模式 1.3）**
16. **治理行为?** ✅ **总是重新评估（通过 OperationGovernanceEvaluator）**
17. **WAITING_FOR_SIGNAL 行为?** ✅ 暂停正常，恢复继续相同过程
18. **副作用限制?** ✅ V1 仅只读/幂等
19. **回退规则?** ✅ 在幂等工具后允许
20. **失败失效规则?** ✅ 工具缺失/模式不兼容→失效；瞬态失败→不失效
21. **共同持久执行边界?** ✅ **已治理的 PendingToolCall → ProtocolReconstructor**
22. **需要 ExecutionStrategy?** ❌ NO
23. **模块放置?** ✅ core(模型) + runtime-react(提取+执行)
24. **公共 API?** ✅ 最小：ProcedureManagement + IntentRegistration
25. **M8 更改 M6 权威语义?** ❌ **NO - 仅 Checkpoint 模式 1.2→1.3 附加字段**

### 纠正的实施轨道

```
M8-A: 过程语义模型 + 存储权威
  - ReusableProcedure / ProcedureCandidate 域模型
  - ReusableProcedureStore interface + impl
  - ProcedureExecutionState 记录
  - SuspensionCheckpoint 模式 1.3 扩展
  - 2-3 days

M8-B: 候选提取（从成功执行）
  - 直接候选捕获（不通过 Ledger）
  - 可缓存性验证
  - 工具模式指纹
  - 安全绑定捕获（白名单）
  - 2-3 days

M8-C: 治理集成
  - OperationGovernanceEvaluator（内部包私有）
  - GovernanceToolCallingAdvisor 保持不变
  - 治理不变式测试
  - 2 days

M8-D: 逐步过程执行
  - ProcedureExecutionCoordinator
  - 参数绑定解析器
  - 步骤输出捕获
  - checkpoint 推进
  - 治理集成（使用 OperationGovernanceEvaluator）
  - 3-4 days

M8-E: 晋升 + 确定性解析
  - ProcedureManagement API
  - IntentRegistration
  - ProcedureResolver (intentKey 匹配)
  - 2-3 days

M8-F: 验证 + 回退
  - ProcedureVerification interface
  - 确定性谓词验证器
  - 验证失败处理
  - 回退到 ReAct 逻辑
  - 2-3 days

M8-G: 集成测试 + 闭环
  - 逐步执行测试
  - PREVIOUS_STEP_OUTPUT 绑定测试
  - 治理重新评估测试（缓存路径）
  - REQUIRE_APPROVAL 暂停/恢复测试
  - 跨重启过程恢复测试
  - 步骤输出持久性测试
  - M6 CHECK A/B 保留验证
  - 3-4 days

总计: ~19-26 days
```

---

## 最终架构门控决策（已纠正）

经过三个硬性架构矛盾的解决，我对 M8 执行路径学习提出以下最终门控决定：

---

**🟢 GO — M8 EXECUTION PATH LEARNING V1 ARCHITECTURE APPROVED (CORRECTED)**

---

### 已纠正的核心架构原则

1. ✅ **缓存过程步骤通过当前治理** — 通过 OperationGovernanceEvaluator
2. ✅ **V1 使用逐步执行循环** — 不是整体路径物化
3. ✅ **CheckpointStore 是唯一继续权威** — 通过 procedureState 字段
4. ✅ **operationId 在物化时创建** — 不从历史重放
5. ✅ **步骤输出通过 checkpoint 传递** — 不是并行存储
6. ✅ **治理是一个语义权威** — ToolGovernancePolicy
7. ✅ **ProtocolReconstructor 保持内部** — 共同执行边界但非公共 API
8. ✅ **候选提取不要求 Ledger 原始参数** — 直接捕获或清理的绑定提示

### 架构不变式（已纠正）

- ✅ M6 持久性语义（operationId, InvocationStateStore, CHECK A/B）
- ✅ M7 恢复语义
- ✅ 单一物理工具执行管道
- ✅ 治理是当前决策（不是历史）
- ✅ CheckpointStore 拥有当前继续**包括过程位置**
- ✅ ReusableProcedureStore 拥有过程定义
- ✅ ExecutionLedger 保持历史投影

### Checkpoint 模式演变

```
1.2 (M6-T6.4 当前) → 1.3 (M8)

添加:
  procedureState: ProcedureExecutionState (可为空)

ProcedureExecutionState {
  procedureId: string
  procedureRevision: int
  currentStepIndex: int
  boundInputs: Map<String, Object>
  capturedStepOutputs: Map<Int, StepOutput>
}
```

**这是数据模型扩展，不是权威语义更改。**

### 实施要求（已纠正）

1. **SuspensionCheckpoint 添加 procedureState 字段** — 模式 1.3
2. **ProcedureExecutionCoordinator 逐步循环** — 不是批量物化
3. **OperationGovernanceEvaluator 用于缓存路径** — 内部包私有
4. **GovernanceToolCallingAdvisor 保持不变** — 仍处理 Spring AI ToolCall
5. **候选提取直接或清理** — 不将 Ledger 变为参数存档
6. **完整测试覆盖** — 包括逐步、治理、恢复测试

### 最终成功标准（已纠正）

M8 V1 被认为成功，如果：

1. ✅ 自动候选提取工作（从成功执行）
2. ✅ 逐步过程执行工作（包括 PREVIOUS_STEP_OUTPUT 绑定）
3. ✅ 缓存过程步骤通过治理（DENY / REQUIRE_APPROVAL / ALLOW）
4. ✅ 治理 REQUIRE_APPROVAL 暂停过程（WAITING_FOR_SIGNAL checkpoint）
5. ✅ 恢复继续相同 procedureId + revision
6. ✅ 步骤输出通过 checkpoint 持久化和恢复
7. ✅ 跨重启恢复工作（使用 procedureState）
8. ✅ M6 不变式保留（ArchUnit 测试）
9. ✅ 零 M6 权威更改（仅 Checkpoint 数据扩展）
10. ✅ 可观测性事件捕获学习周期

---

### 下一步

1. ⏸️ **不立即开始实施**
2. 📋 **人工审查此纠正的门控决定**
3. ✅ **如果批准** → 创建详细的 M8-A 任务
4. ❌ **如果拒绝** → 识别剩余的阻塞问题
5. 📊 **如果批准** → 在实施后建立基准

---

**此架构门控现已纠正并完成。**

**三个硬性架构矛盾已解决。**

**等待人工最终批准再继续 M8 实施。**

---

**@author lov3r**  
**Date: 2026-09-20 (Corrected)**  
**Status: ARCHITECTURE GATE CORRECTED — AWAITING FINAL APPROVAL**

