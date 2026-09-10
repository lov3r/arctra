# Arctra M5-T1 Durable Process Contract Gate

**日期：** 2026-09-08  
**状态：** CONTRACT GATE  
**目标：** 定义使 AgentProcess 跨 JVM 重启可恢复的最小持久化语义

---

## 1. 当前 M4 悬挂机制

### 1.1 完整执行路径

基于源码检查（`SpringAiToolCallingEngine.java`, `GovernanceToolCallingAdvisor.java`, `DefaultAgentProcess.java`）：

```
Agent.execute(request)
  ↓
SpringAiToolCallingEngine.execute(definition, request, context)
  ↓
ChatClient.prompt()
  .advisors(
      MessageChatMemoryAdvisor,  // 读取 H, 持久化 U
      GovernanceToolCallingAdvisor  // 治理前置检查
  )
  ↓
Model 生成 AssistantMessage with ToolCalls
  ↓
GovernanceToolCallingAdvisor.aroundCall()
  ↓
evaluateBatch(toolCalls) → REQUIRE_APPROVAL
  ↓
throw ToolApprovalRequiredSignal(SuspensionState)
  ↓
SpringAiToolCallingEngine.execute() catches signal
  ↓
suspendForApproval(suspensionState, evidences, definition, context)
  ↓
创建 continuationFunction = new Function<ContinuationSignal, AgentResult>() {
    @Override
    public AgentResult apply(ContinuationSignal signal) {
        if (approved) {
            return resumeApproved(suspensionState, evidences, definition, context);
        } else {
            return resumeDenied(suspensionState, evidences, definition, context);
        }
    }
}
  ↓
ProcessFactory.createSuspended(continuationFunction)
  ↓
return new AgentResult(content, evidences, process)
```

### 1.2 恢复路径

```
process.resume(ApprovalSignal)
  ↓
DefaultAgentProcess.resume()
  ↓
continuationFunction.apply(signal)
  ↓
resumeApproved(suspensionState, evidences, definition, context)
  ↓
ToolCallingManager.execute(toolCalls) with wrapped callbacks
  ↓
Evidence 收集
  ↓
ToolExecutionResult.conversationHistory() 
  // Spring AI 返回完整协议消息：
  // [AssistantMessage(toolCalls), ToolResponseMessage(toolCallId)]
  ↓
continueWithMessages(messages, newEvidences, definition, context)
  ↓
ChatClient.prompt()
  .messages(messages)  // 协议延续，不读 ChatMemory
  .advisors(GovernanceToolCallingAdvisor)  // 可能再次 REQUIRE_APPROVAL
  ↓
Model 继续 或 完成
  ↓
if completed:
    手动持久化最终 Assistant 到 ChatMemory
    return AgentResult(content, evidences)
else if suspended again:
    return AgentResult with new Process
```

---

## 2. 为什么当前 Continuation 不可持久化

### 2.1 表面症状

```java
Function<ContinuationSignal, AgentResult> continuationFunction
```

Java `Function` 接口不是 `Serializable`。

### 2.2 深层问题：闭包捕获的状态

**当前闭包隐式捕获（通过外部作用域）：**

```java
new Function<ContinuationSignal, AgentResult>() {
    // 捕获以下变量：
    SuspensionState suspensionState           // ← 需要
    List<Evidence> evidences                  // ← 需要
    AgentDefinition definition                // ← 需要
    AgentExecutionContext context             // ← 需要
    
    // 通过 this 隐式捕获：
    SpringAiToolCallingEngine.this            // ← 问题！
        .chatModel                            // JVM 对象
        .tools                                // List<ToolCallback> — JVM 对象
        .chatMemory                           // ChatMemory 实现 — 可能不可序列化
        .governancePolicy                     // ToolGovernancePolicy 实现
}
```

**SuspensionState 内部包含：**
```java
record SuspensionState(
    ChatClientRequest originalRequest,        // Spring AI 类型
    AssistantMessage assistantMessageWithToolCalls,  // Spring AI 类型
    List<Evidence> evidences                  // Arctra 类型
)
```

### 2.3 根本问题

**闭包隐式引用整个 `SpringAiToolCallingEngine` 实例。**

该实例包含：
- `ChatModel chatModel` — Vert AI API 客户端，网络连接，非序列化
- `List<ToolCallback> tools` — 业务逻辑实现，Lambda，非序列化
- `ChatMemory chatMemory` — 可能是内存实现
- `ToolGovernancePolicy` — 业务策略实现

**这些是运行时依赖，不是执行状态。**

### 2.4 概念化问题

当前闭包混合了：
1. **语义执行状态**（需要持久化）
2. **运行时依赖**（需要重建）

持久化的正确目标是 #1，而当前实现无法分离二者。

---

## 3. 状态所有权映射表

| 状态类别 | 具体内容 | 当前所有者 | 持久化需求 | 重建方式 |
|---------|---------|-----------|-----------|---------|
| **A. Process Identity** | processId | `DefaultAgentProcess.id` | ✅ 必需 | 直接持久化 UUID |
| **A. Process Lifecycle** | ProcessStatus | `DefaultAgentProcess.status` | ✅ 必需 | 直接持久化枚举 |
| **B. Pending Tool Batch** | ToolCall list | `SuspensionState.assistantMessageWithToolCalls` | ✅ 必需 | 提取协议信息 |
| **B. Tool Call IDs** | toolCallId per call | `AssistantMessage.ToolCall.id()` | ✅ 必需 | 协议要求 |
| **B. Tool Names** | tool name | `AssistantMessage.ToolCall.name()` | ✅ 必需 | 工具解析需要 |
| **B. Tool Arguments** | JSON arguments | `AssistantMessage.ToolCall.arguments()` | ✅ 必需 | 执行需要 |
| **B. Governance Decision** | REQUIRE_APPROVAL | 隐式（已评估） | ✅ 必需 | 避免重复评估 |
| **C. Session Identity** | sessionId | `AgentExecutionContext.sessionId` | ✅ 必需 | 引用 Session |
| **C. Conversation History** | H + U + ... | `ChatMemory` | ❌ 不需要 | 通过 sessionId 查询 |
| **D. Accumulated Evidence** | 已执行工具的 Evidence | `List<Evidence> evidences` | ✅ 必需 | 避免丢失诊断 |
| **E. Agent Identity** | 哪个 Agent？ | 隐式（闭包） | ✅ 必需 | 重建 Engine/Tools |
| **E. Agent Definition** | system prompt, name | `AgentDefinition` | ⚠️ 引用 | agentName 或序列化 |
| **F. Runtime Dependencies** | ChatModel | `SpringAiToolCallingEngine` | ❌ 不持久化 | 应用启动重建 |
| **F. Tool Implementations** | ToolCallback list | `SpringAiToolCallingEngine.tools` | ❌ 不持久化 | 应用启动重建 |
| **F. Governance Policy** | 策略实现 | `SpringAiToolCallingEngine.governancePolicy` | ❌ 不持久化 | 应用启动重建 |
| **F. ChatMemory** | 内存/持久化实现 | `SpringAiToolCallingEngine.chatMemory` | ❌ 不持久化 | 应用启动重建 |

### 关键发现

**需要持久化的语义状态：**
1. Process 标识（processId）
2. Process 生命周期（WAITING）
3. 待执行的 ToolCall 批次（协议信息）
4. Session 引用（sessionId）
5. 已累积的 Evidence
6. Agent 标识（重建依赖的锚点）

**不需要持久化，需要重建的：**
1. ChatModel 实例
2. Tool 实现
3. Governance 策略实现
4. ChatMemory 实现

**不需要持久化，通过引用访问的：**
1. 对话历史（通过 sessionId → ChatMemory）

---

## 4. Process vs Continuation vs Session vs Protocol 状态

### 4.1 Process State（进程状态）

**定义：** AgentProcess 作为生命周期实体的状态

**包含：**
- `processId: String` — 稳定标识
- `status: ProcessStatus` — WAITING/RUNNING/COMPLETED/FAILED

**持久化判断：** ✅ 必需 — 这是 Process 的定义

**所有者：** `AgentProcess` 接口契约

---

### 4.2 Continuation State（延续状态）

**定义：** 恢复执行所需的协议和语义信息

**包含：**
- Pending ToolCall Batch
  - toolCallId (per call)
  - toolName
  - toolArguments (JSON)
- Governance Checkpoint
  - 批次已评估为 REQUIRE_APPROVAL
  - 避免恢复后重新评估导致循环
- Accumulated Evidence
  - 悬挂前已收集的 Evidence

**持久化判断：** ✅ 必需 — 这是"相同逻辑执行"的核心

**所有者：** 当前在 `Function` 闭包中，应该提取到显式表示

---

### 4.3 Session State（会话状态）

**定义：** 对话连续性状态

**包含：**
- `sessionId: String`
- Conversation History (H + U + A + ...)
- 持久化机制（由 ChatMemory 实现决定）

**持久化判断：** ⚠️ **引用，不复制**

**关键架构决策：**

```
❌ 错误：Process Checkpoint 包含完整 conversation messages

✅ 正确：Process Checkpoint 引用 sessionId
         恢复时：通过 sessionId → ChatMemory 获取历史
```

**理由：**
1. M4 不变式：Process ≠ Session
2. 避免重复持久化
3. ChatMemory 已有持久化机制（MessageChatMemoryAdvisor）
4. 对话历史可能很大

**依赖关系：**

Durable Process 要求 Durable Session。

如果 ChatMemory 是内存实现（如 `InMemoryChatMemory`），则恢复会失败。

**M5 语义：**

> Durable Process 恢复保证**仅在 Session 也持久化时**有效。

---

### 4.4 Protocol State（协议状态）

**定义：** Spring AI tool-calling 协议延续所需的状态

**包含：**
- AssistantMessage with ToolCalls
- ToolCall IDs（协议要求）
- ToolCall names/arguments
- 协议消息序列完整性

**持久化判断：** ✅ 必需 — 但不直接序列化 Spring AI 对象

**关键问题：**

当前 `SuspensionState` 包含：
```java
AssistantMessage assistantMessageWithToolCalls
```

这是 Spring AI 的内部类型。

**序列化策略评估：**

| 选项 | 优点 | 缺点 |
|------|------|------|
| 直接序列化 `AssistantMessage` | 简单 | Spring AI 类型非 Serializable<br>版本兼容性风险<br>泄漏框架到持久化层 |
| 提取协议字段到 Arctra 类型 | 框架中立<br>版本稳定 | 需要转换逻辑 |
| 序列化为 JSON | 可读<br>跨语言 | 需要 schema 版本管理 |

**推荐：** 提取协议字段到框架中立的 Arctra 类型

---

## 5. 最小持久化状态

### 5.1 核心语义

恢复一个 WAITING Process 需要回答：

1. **这是哪个 Process？** → `processId`
2. **它在等什么？** → `待执行的 ToolCall 批次`
3. **它属于哪个对话？** → `sessionId`
4. **它是哪个 Agent 的任务？** → `Agent 标识`
5. **之前执行了什么？** → `已累积的 Evidence`
6. **治理决策是什么？** → `批次已评估，已批准`

### 5.2 最小持久化模型

```java
// 概念模型（不是最终实现）
record MinimalCheckpoint(
    // Process Identity
    String processId,
    ProcessStatus status,  // WAITING
    
    // Agent Binding
    String agentName,  // 或 agentDefinitionId
    
    // Session Reference
    String sessionId,
    
    // Continuation: Pending Tool Batch
    List<PendingToolCall> pendingToolCalls,
    
    // Continuation: Accumulated State
    List<Evidence> accumulatedEvidences,
    
    // Metadata
    Instant createdAt,
    Instant suspendedAt
)

record PendingToolCall(
    String toolCallId,     // Spring AI 协议要求
    String toolName,       // 工具解析
    String arguments       // JSON string
)
```

### 5.3 不包含的内容

❌ **Conversation messages** — 通过 sessionId 引用  
❌ **ChatModel 实例** — 运行时重建  
❌ **Tool 实现** — 运行时重建  
❌ **Governance 策略** — 运行时重建  
❌ **Spring AI `ChatClientRequest`** — 过于内部  
❌ **Spring AI `AssistantMessage`** — 过于内部  
❌ **System prompt** — 通过 agentName 重建  
❌ **AgentDefinition 完整对象** — 通过 agentName 重建  

---

## 6. Agent/Runtime 重建问题

### 6.1 问题

当前闭包隐式捕获 `SpringAiToolCallingEngine` 实例。

恢复后，该实例已不存在。

**关键问题：**

> 应用重启后，如何将 `processId=P100` 绑定到正确的 Agent 运行时？

### 6.2 当前架构的隐式假设

```java
Agent agent = runtime.agent(new AgentDefinition("incident-agent", "..."));
AgentResult result = agent.execute(request);
AgentProcess process = result.process();
```

`agent` 是 JVM 对象引用，重启后消失。

### 6.3 需要的稳定锚点

**选项 A: Agent Name**

```java
record MinimalCheckpoint(
    String agentName,  // "incident-agent"
    ...
)
```

恢复时：
```java
Agent agent = runtime.agent(agentName);
// 需要 runtime.agent(String name) API
```

**选项 B: Agent Definition ID**

```java
record AgentDefinition(
    String id,  // 新增：稳定 ID
    String name,
    String systemPrompt
)
```

**选项 C: Agent Registry**

```java
interface AgentRegistry {
    Agent get(String agentName);
}
```

### 6.4 当前缺失

❌ **`AgentRuntime.agent(String name)`** 不存在  
❌ **AgentDefinition 无稳定 ID**  
❌ **AgentRegistry 不存在**  

### 6.5 M5-T1 结论

**最小解决方案：**

使用 `AgentDefinition.name` 作为稳定标识。

**要求：**
1. 应用必须能够根据 name 重建 Agent 运行时
2. 可能需要添加 `AgentRuntime.resolveAgent(String name)` API
3. 或应用层提供 Agent 注册/查找机制

**不在 M5-T1 范围：**
- 完整的 AgentRegistry 抽象
- AgentDefinition 版本管理
- 动态 Agent 注册

---

## 7. Tool 重建问题

### 7.1 问题

Checkpoint 包含：
```java
PendingToolCall(
    toolCallId = "call_abc",
    toolName = "restartDatabase",
    arguments = "{\"instanceId\": \"prod-db-1\"}"
)
```

恢复时需要：
```java
ToolCallback callback = resolveToolByName("restartDatabase");
```

### 7.2 当前架构

`SpringAiToolCallingEngine` 构造时接收 `List<ToolCallback> tools`。

**工具通过 name 匹配：**
```java
// Spring AI ToolCallingManager 内部逻辑
toolCalls.forEach(toolCall -> {
    ToolCallback callback = findToolByName(toolCall.name(), tools);
    callback.call(toolCall.arguments());
});
```

### 7.3 恢复要求

**应用必须在重启后提供相同的 tools 列表。**

**这已经成立：**

当前应用启动时构建 Agent：
```java
Agent agent = runtime.agent(
    new AgentDefinition("incident-agent", "..."),
    List.of(
        new QueryLogsTool(),
        new GetDeploymentTool()
    )
);
```

只要应用使用相同配置重启，tools 列表相同。

### 7.4 M5-T1 结论

**不需要 ToolRegistry。**

**假设：**
- 应用通过相同的 AgentDefinition + tools 重建 Agent
- Tool name 作为稳定标识
- Tool 实现是幂等的（重复执行安全，或应用层处理）

**风险：**
- 如果应用修改了 tool 列表，恢复会失败
- 不在 M5 范围：工具版本管理

---

## 8. Governance 重建语义

### 8.1 关键问题

```
P100 WAITING 因为批次 B 评估为 REQUIRE_APPROVAL
→ 重启
→ 用户发送 APPROVED
→ 治理策略应该重新评估 B 吗？
```

### 8.2 M4 语义分析

**M4 建立的不变式：**

> Governance 在执行**前**评估 ToolCall 批次。
> DENY 执行零个工具。
> REQUIRE_APPROVAL 在批准**前**执行零个工具。

**关键推导：**

悬挂时，批次 B **已经评估过**。

如果恢复后重新评估：
```
评估 B → REQUIRE_APPROVAL → WAITING
→ APPROVED
→ 评估 B → REQUIRE_APPROVAL → WAITING
→ 无限循环
```

### 8.3 M5 语义

**Checkpoint 必须记录：批次已评估，决策是 REQUIRE_APPROVAL。**

**恢复时：**
- 不重新调用 `governancePolicy.evaluate()`
- 直接执行批次 B（如果 APPROVED）
- 或直接拒绝批次 B（如果 DENIED）

### 8.4 实现含义

**Checkpoint 不需要包含完整的 GovernanceDecision 历史。**

**只需要：**
```java
record MinimalCheckpoint(
    List<PendingToolCall> pendingToolCalls,
    // 隐式：这些 calls 已评估为 REQUIRE_APPROVAL
    ...
)
```

**恢复逻辑：**
```java
// 跳过治理评估，直接执行
if (approved) {
    executeBatch(checkpoint.pendingToolCalls);
} else {
    denyBatch(checkpoint.pendingToolCalls);
}
```

---

## 9. Session 持久化依赖

### 9.1 当前 M4 Session 机制

```java
AgentExecutionContext context = new AgentExecutionContext(sessionId);

MessageChatMemoryAdvisor.builder()
    .chatMemory(chatMemory)
    .build()
    
ChatClient.prompt()
    .param(ChatMemory.CONVERSATION_ID, sessionId)
    .advisors(memoryAdvisor)
```

`ChatMemory` 实现决定持久化：
- `InMemoryChatMemory` — 内存，重启丢失
- 自定义实现（如 JDBC）— 持久化

### 9.2 M5 依赖

**Durable Process 恢复需要读取 Session 历史。**

**场景：**
```
P100 WAITING, sessionId=S1
ChatMemory(S1) = [H, U]
→ 重启
→ 恢复 P100
→ 执行 continueWithMessages(messages, ...)
```

`continueWithMessages` 使用 Spring AI 返回的协议消息，不直接读 ChatMemory。

**但是：**

最终完成时需要持久化最终 Assistant 到 ChatMemory。

如果 ChatMemory 丢失，持久化会失败或创建不一致状态。

### 9.3 M5-T1 结论

**明确依赖：**

> M5 Durable Process 恢复保证**仅在 Session 也持久化时**有效。

**配置要求：**
```java
// ❌ 不兼容 Durable Process
ChatMemory memory = new InMemoryChatMemory();

// ✅ 兼容 Durable Process
ChatMemory memory = new JdbcChatMemory(...);
```

**M5 不构建 Arctra Memory 抽象。**

**责任边界：**
- Arctra：持久化 Process checkpoint
- Spring AI / 应用：持久化 Session (ChatMemory)

---

## 10. Evidence 持久化决策

### 10.1 问题

Evidence 有两个用途：
1. **执行正确性** — 恢复需要吗？
2. **诊断/审计** — 查看历史执行

### 10.2 M4 Evidence 语义

```java
AgentResult {
    String content,
    List<Evidence> evidences,
    AgentProcess process
}
```

**M4 建立的不变式：**

> Evidence 表示实际工具执行。
> 实际执行 → Evidence

**当前行为：**
```
Agent.execute() → Tool A 执行 → Evidence A
→ REQUIRE_APPROVAL
→ P100 WAITING
→ resume(APPROVED)
→ Tool B 执行 → Evidence B
→ completed
→ AgentResult.evidences = [A, B]
```

### 10.3 关键问题

**悬挂前累积的 Evidence A 必须在最终结果中出现吗？**

**源码验证：**

```java
// SpringAiToolCallingEngine.suspendForApproval()
var continuationFunction = new Function<...>() {
    @Override
    public AgentResult apply(ContinuationSignal signal) {
        return resumeApproved(
            suspensionState,
            evidences,  // ← 闭包捕获
            definition,
            context
        );
    }
};

// SpringAiToolCallingEngine.resumeApproved()
List<Evidence> newEvidences = new ArrayList<>(evidences);  // ← 继承
// 执行工具，添加新 Evidence
return new AgentResult(content, newEvidences);
```

**结论：Evidence 是累积的。**

### 10.4 M5-T1 决策

**✅ Evidence 必须持久化。**

**理由：**
1. **正确性** — 最终 AgentResult 必须包含所有 Evidence
2. **M4 契约** — Evidence 累积是公共行为
3. **诊断价值** — 失败时查看已执行的工具

**Checkpoint 包含：**
```java
record MinimalCheckpoint(
    List<Evidence> accumulatedEvidences,
    ...
)
```

**恢复时：**
```java
List<Evidence> newEvidences = new ArrayList<>(checkpoint.accumulatedEvidences);
// 执行新工具，继续累积
```

---

## 11. 持久化状态的三种备选方案

### Model A: Minimal Suspension Checkpoint（推荐）

**核心思想：** 仅持久化 WAITING 状态所需的协议延续信息

**包含：**
```java
record SuspensionCheckpoint(
    // Process Identity
    String processId,
    ProcessStatus status,  // 固定为 WAITING
    
    // Agent Binding
    String agentName,
    
    // Session Reference
    String sessionId,
    
    // Pending Batch
    List<PendingToolCall> pendingBatch,
    
    // Accumulated State
    List<Evidence> evidences,
    
    // Metadata
    Instant suspendedAt
)
```

**优点：**
- 最小化持久化状态
- 语义清晰：这是悬挂点
- 框架中立（不包含 Spring AI 类型）
- Schema 稳定

**缺点：**
- 不支持 RUNNING checkpoint（接受的限制）
- 不支持 FAILED 持久化（可接受）

---

### Model B: Full Process Snapshot

**核心思想：** 持久化 Process 的完整执行状态

**包含：**
```java
record ProcessSnapshot(
    String processId,
    ProcessStatus status,  // WAITING/RUNNING/COMPLETED/FAILED
    String agentName,
    String sessionId,
    
    // Execution State
    ExecutionState state,
    
    // Metadata
    Instant createdAt,
    Instant updatedAt
)

record ExecutionState(
    List<Evidence> evidences,
    List<Message> messages,  // ← 包含对话历史
    Object continuationData,  // ← 泛化
    ...
)
```

**优点：**
- 可能支持更广泛的恢复场景
- 为未来 Replay 奠基

**缺点：**
- **过于宽泛** — 包含不需要的数据（messages）
- **违反 M4 不变式** — Process 包含 Session 状态
- **复杂** — 需要定义泛化的 ExecutionState
- **过早抽象** — M5 只需要 WAITING 恢复

---

### Model C: Event/Log Based Recovery

**核心思想：** 持久化执行转换事件，通过重放恢复

**包含：**
```java
record ProcessCreated(processId, agentName, sessionId, timestamp)
record ToolExecuted(processId, toolName, evidence, timestamp)
record ProcessSuspended(processId, pendingBatch, timestamp)
record ProcessResumed(processId, signal, timestamp)
```

**优点：**
- 完整审计日志
- 支持 Replay
- 支持诊断

**缺点：**
- **过度工程** — M5 不需要 Event Sourcing
- **复杂** — 需要事件存储和重放逻辑
- **性能** — 恢复需要重放事件
- **范围过大** — M5 目标是恢复，不是审计

---

## 12. 选择的 Checkpoint 模型

### ✅ **Model A: Minimal Suspension Checkpoint**

**理由：**

1. **正确性** — 包含恢复所需的所有语义状态
2. **最小化** — 不包含不需要的数据
3. **M4 兼容** — 保持 Process ≠ Session 边界
4. **框架中立** — 不序列化 Spring AI 类型
5. **实现简单** — 清晰的持久化边界
6. **风险低** — 最小抽象，最小过早设计

**不选择 Model B 的原因：**
- 包含 messages 违反 M4 不变式
- ExecutionState 过于泛化，缺乏具体压力

**不选择 Model C 的原因：**
- Event Sourcing 超出 M5 范围
- 无真实审计需求驱动

---

## 13. 持久化边界

### 13.1 哪些 Process 状态可持久化？

| 状态 | 可持久化？ | 可恢复？ | 理由 |
|------|----------|---------|------|
| **WAITING** | ✅ 是 | ✅ 是 | 安全悬挂点，无执行中状态 |
| **RUNNING** | ❌ 否 | ❌ 否 | JVM 栈/模型调用/工具执行无法恢复 |
| **COMPLETED** | ⚠️ 可选 | ⚠️ 可查询 | 可持久化用于审计，但无恢复意义 |
| **FAILED** | ⚠️ 可选 | ❌ 否 | 可持久化用于诊断，但 FAILED 是终止状态 |

### 13.2 M5 持久化边界定义

**M5 仅持久化 WAITING 状态。**

**语义模型：**

```
RUNNING
→ 到达持久化悬挂点（如 REQUIRE_APPROVAL）
→ 转换为 WAITING
→ 写入 Checkpoint
→ Process 可跨 JVM 重启恢复
```

**不持久化 RUNNING：**
- 执行中状态无法安全序列化
- 模型调用中无法暂停
- 工具执行中无法暂停

**COMPLETED/FAILED 持久化：**

M5-T1 **不要求** COMPLETED/FAILED 持久化。

**理由：**
- COMPLETED — 已有最终 AgentResult，无恢复需求
- FAILED — 终止状态，无恢复需求
- 审计/诊断是未来关注点

**可选扩展：**

M5-T2/T3 实现时可选择也持久化 COMPLETED/FAILED 用于：
- 审计日志
- 失败诊断
- 指标统计

但**不是 M5 核心语义要求**。

---

## 14. Exactly-Once vs At-Least-Once 保证

### 14.1 关键场景

```
P100 APPROVED
→ Tool A 开始执行
→ Tool A 成功（外部副作用已发生）
→ 准备写入 Checkpoint
→ JVM 崩溃
→ Checkpoint 未写入
```

**恢复后：**

从上一个 checkpoint 恢复，即 WAITING 状态，Tool A 尚未执行。

**用户 APPROVED，Tool A 会再次执行吗？**

### 14.2 三种可能的保证

#### A. At-Least-Once Execution

**语义：** 崩溃恢复可能导致工具重复执行

**M5 实现：**
- WAITING checkpoint 在批准后、工具执行前写入
- 崩溃后恢复到 WAITING
- 重新执行批次

**优点：**
- 实现简单
- 无需事务协调

**缺点：**
- 工具必须幂等
- 或应用层处理重复

#### B. At-Most-Once Execution

**语义：** 崩溃可能导致工具未执行

**M5 实现：**
- 工具执行后、结果返回前 checkpoint
- 崩溃后某些工具可能丢失

**优点：**
- 无重复执行

**缺点：**
- 可能丢失执行
- 难以推理

#### C. Exactly-Once Execution

**语义：** 每个工具恰好执行一次

**M5 实现：**
- 需要分布式事务
- 工具执行 + Checkpoint 原子化
- 需要幂等性或去重机制

**优点：**
- 最强保证

**缺点：**
- **极其复杂**
- 需要事务协调器
- 超出 M5 范围

### 14.3 M5-T1 决策

**✅ M5 提供 At-Least-Once 保证（WAITING 状态）**

**精确语义：**

> M5 保证 WAITING Process 可以恢复。
> 崩溃发生在 RUNNING 期间可能导致工具重复执行。

**Checkpoint 时机：**

```
P100 WAITING（批准前）
→ Checkpoint 写入
→ APPROVED
→ 转换为 RUNNING
→ 执行批次
→ （崩溃窗口：工具可能重复）
→ 如果成功，转换为 WAITING/COMPLETED
→ Checkpoint 更新/删除
```

**应用责任：**

工具实现应该：
- 幂等（推荐）
- 或应用层去重（基于 processId + toolCallId）

**不在 M5 范围：**
- Exactly-once 语义
- 分布式事务
- 工具执行去重框架

---

## 15. 恢复模型

### 15.1 三种恢复策略

#### A. EAGER RECOVERY（急切恢复）

```java
// 应用启动时
List<Checkpoint> waitingProcesses = checkpointStore.findByStatus(WAITING);
waitingProcesses.forEach(cp -> {
    AgentProcess process = reconstructProcess(cp);
    // 注册到内存 registry？
});
```

**优点：**
- 启动时恢复所有 Process
- 应用可以主动触发恢复

**缺点：**
- 启动时间长
- 可能恢复永远不会被 resume 的 Process
- 需要内存 registry

#### B. LAZY RECOVERY（延迟恢复）

```java
// 用户发送 APPROVED 时
runtime.resume(processId, signal);
  ↓
Checkpoint cp = checkpointStore.load(processId);
AgentProcess process = reconstructProcess(cp);
process.resume(signal);
```

**优点：**
- 按需恢复
- 无启动开销
- 无内存 registry

**缺点：**
- 需要新的 public API: `runtime.resume(processId, signal)`
- 改变当前对象引用模型

#### C. APPLICATION-MANAGED RECOVERY（应用管理）

```java
// 应用层负责
Application {
    Map<String, AgentProcess> activeProcesses;
    
    void onStartup() {
        List<Checkpoint> cps = checkpointStore.findAll();
        cps.forEach(cp -> {
            AgentProcess p = arctra.reconstruct(cp);
            activeProcesses.put(p.id(), p);
        });
    }
    
    void onApproval(String processId, ApprovalSignal signal) {
        AgentProcess p = activeProcesses.get(processId);
        p.resume(signal);
    }
}
```

**优点：**
- 灵活
- Arctra 不强制恢复策略

**缺点：**
- 应用负担重
- 需要应用层 registry

### 15.2 M5-T1 推荐

**✅ B. LAZY RECOVERY（延迟恢复）**

**理由：**
1. **最小化** — 仅在需要时恢复
2. **效率** — 无启动开销
3. **正确** — 避免恢复永远不会 resume 的 Process

**Public API 影响：**

需要添加：
```java
AgentRuntime {
    AgentProcess resume(String processId, ContinuationSignal signal);
}
```

**或：**
```java
ProcessRecovery {
    AgentProcess recover(String processId);
}
```

**这是最小的 public API delta。**

---

## 16. Public API 影响

### 16.1 当前 API

```java
Agent agent = runtime.agent(definition);
AgentResult result = agent.execute(request);

if (result.isSuspended()) {
    AgentProcess process = result.process();
    
    // 用户保存 process 引用？不现实
    AgentResult finalResult = process.resume(signal);
}
```

**问题：**

重启后，`process` 对象引用消失。

### 16.2 恢复 API 需求

**应用需要能够：**

1. 获取 processId（已有）
```java
String pid = process.id();
```

2. 持久化 processId（应用层）
```java
approvalQueue.add(pid);
```

3. 恢复 Process
```java
// ❌ 当前无此 API
AgentProcess process = runtime.resume(pid, signal);
```

### 16.3 最小 API 扩展

**选项 A: 添加到 AgentRuntime**

```java
public interface AgentRuntime {
    Agent agent(AgentDefinition definition);
    
    // M5 新增
    AgentProcess recoverProcess(String processId) throws ProcessNotFoundException;
}
```

**用法：**
```java
AgentProcess process = runtime.recoverProcess(processId);
AgentResult result = process.resume(signal);
```

**选项 B: 一步恢复+resume**

```java
public interface AgentRuntime {
    // M5 新增
    AgentResult resumeProcess(String processId, ContinuationSignal signal) 
        throws ProcessNotFoundException;
}
```

**用法：**
```java
AgentResult result = runtime.resumeProcess(processId, signal);
```

### 16.4 M5-T1 推荐

**✅ 选项 B: 一步 resumeProcess()**

**理由：**
- 更简洁的用户 API
- 避免暴露 Process 重建细节
- 恢复+resume 作为原子操作

**Public API Delta：**
```java
// AgentRuntime.java
AgentResult resumeProcess(String processId, ContinuationSignal signal) 
    throws ProcessNotFoundException;
```

**这是 M5 唯一的 public API 扩展。**

---

## 17. 配置漂移

### 17.1 问题

```
P100 悬挂于 AgentDefinition v1
→ 应用重新部署
→ AgentDefinition 变为 v2:
    - 不同的 system prompt
    - 不同的 tools
    - 不同的 governance policy
→ P100 恢复
→ 应该使用哪个定义？
```

### 17.2 语义选项

| 选项 | 语义 | 实现 | 风险 |
|------|------|------|------|
| A. 使用最新定义 | P100 使用 v2 | 简单 | 行为不一致 |
| B. 使用原始定义版本 | P100 使用 v1 | 需要版本存储 | 复杂 |
| C. 拒绝不兼容恢复 | 检测变化，拒绝 | 需要兼容性检查 | 用户体验差 |
| D. 应用定义策略 | 应用决定 | 灵活 | 无统一语义 |

### 17.3 M5-T1 立场

**M5 使用最新 AgentDefinition（选项 A）。**

**理由：**
1. **最简单** — 无需版本管理
2. **实用** — 大多数变更向后兼容
3. **应用责任** — 如果不兼容，应用不应重新部署

**已知风险：**

- System prompt 变化可能导致不同行为
- Tools 变化可能导致工具解析失败
- Governance policy 变化可能改变批准行为

**缓解：**

文档化为**应用责任**：

> 应用部署新版本时，应该：
> 1. 确保 AgentDefinition 变更向后兼容
> 2. 或明确放弃旧的 WAITING Processes

**不在 M5 范围：**
- AgentDefinition 版本管理
- 自动兼容性检查
- 配置迁移

---

## 18. Checkpoint 兼容性和版本管理

### 18.1 问题

持久化状态比代码长命。

```
Checkpoint schema v1
→ Arctra 升级到新版本
→ Checkpoint schema v2
→ 旧 checkpoints 如何处理？
```

### 18.2 最小兼容性元数据

```java
record SuspensionCheckpoint(
    // Schema Version
    String schemaVersion,  // "arctra-checkpoint-v1"
    
    // Framework Version (可选)
    String arctraVersion,  // "0.5.0"
    
    // Process State
    String processId,
    ...
)
```

### 18.3 M5-T1 建议

**✅ 包含 schemaVersion 字段**

**理由：**
- 最小化，仅一个字段
- 未来可扩展
- 明确版本边界

**初始值：**
```java
schemaVersion = "arctra-checkpoint-v1"
```

**未来策略：**

- 如果 schema 不兼容变更，递增版本
- 恢复时检查 schemaVersion
- 如果不支持，拒绝恢复或尝试迁移

**不在 M5-T1 范围：**
- 实际迁移逻辑
- 多版本并存支持

---

## 19. 安全性影响

### 19.1 敏感数据

**Checkpoint 可能包含：**

```java
PendingToolCall(
    toolName = "restartDatabase",
    arguments = "{\"password\": \"secret123\", \"instanceId\": \"prod-db-1\"}"
)
```

**风险：**
- 密码/token/凭证持久化
- PII（个人身份信息）
- 业务敏感数据

### 19.2 M5-T1 立场

**Arctra 不构建加密/脱敏机制。**

**责任边界：**

- **Arctra** — 持久化 Checkpoint
- **Infrastructure** — 加密存储（如数据库加密、文件系统加密）
- **Application** — 工具参数设计（避免明文密码）

**文档化：**

> ⚠️ **Security Notice**  
> Suspension Checkpoints 包含工具参数，可能包含敏感数据。  
> 应用应该：  
> 1. 使用加密存储（如 database-level encryption）  
> 2. 避免在工具参数中传递明文密码（使用 secret references）  
> 3. 应用访问控制到 checkpoint storage

**不在 M5 范围：**
- Field-level 加密
- 参数脱敏（redaction）
- Secret 引用解析

---

## 20. 恢复失败语义

### 20.1 恢复失败场景

```
runtime.resumeProcess(processId, signal)
```

**可能失败：**
1. Checkpoint 未找到
2. Checkpoint 反序列化失败
3. Agent 绑定失败（agentName 不存在）
4. Tool 解析失败（toolName 不存在）
5. Session 不存在（sessionId 无效）
6. Checkpoint schema 不兼容

### 20.2 失败分类

**A. 恢复失败（Recovery Failure）**

系统无法重建 Process 对象。

**B. 执行失败（Execution Failure）**

Process 重建成功，但 resume 执行失败（如工具抛出异常）。

### 20.3 语义区分

**M4 FAILED：**
> 已物化的 Process 的 continuation 失败。

**M5 Recovery Failure：**
> 无法重建 Process 对象。

**这是不同的概念。**

### 20.4 M5-T1 建议

**Recovery Failure 不是 FAILED 状态。**

**API 语义：**
```java
AgentResult resumeProcess(String processId, ContinuationSignal signal) 
    throws ProcessNotFoundException,
           ProcessRecoveryException;
```

**异常分类：**
- `ProcessNotFoundException` — Checkpoint 不存在
- `ProcessRecoveryException` — 恢复失败（包含原因）

**Process 在数据库中的状态保持 WAITING。**

**不引入新的 ProcessStatus：**
- ❌ 不添加 `RECOVERY_FAILED`
- ❌ 不添加 `CORRUPTED`

**理由：**

恢复失败通常是临时的（如应用配置错误），修复后可以重试。

**与 M4 FAILED 的区别：**

| 失败类型 | Process 状态 | 可重试？ | 语义 |
|---------|-------------|---------|------|
| M4 FAILED | FAILED | ❌ 否 | Continuation 失败，终止 |
| M5 Recovery Failure | WAITING | ✅ 是 | 恢复失败，可修复后重试 |

---

## 21. 纸面恢复追踪

### 完整场景：两次悬挂 + 恢复

```
[Initial Execution]
1. User: "重启生产数据库"
2. Model → ToolCall A: restartDatabase(prod-db-1)
3. Governance.evaluate(A) → REQUIRE_APPROVAL
4. P100 materialized, status=WAITING
5. Checkpoint written:
   {
     processId: "P100",
     status: WAITING,
     agentName: "incident-agent",
     sessionId: "S1",
     pendingBatch: [
       {id: "call_1", name: "restartDatabase", args: "{\"instanceId\": \"prod-db-1\"}"}
     ],
     evidences: [],
     suspendedAt: "2026-09-08T10:00:00Z"
   }
6. ChatMemory(S1) = [H, U] （MessageChatMemoryAdvisor.before() 已持久化 U）
7. return AgentResult(suspended, process=P100)

[JVM Terminates]
8. Application shutdown
9. P100 Java object destroyed
10. Checkpoint remains in storage
11. ChatMemory(S1) remains in storage

[JVM Starts]
12. Application starts
13. No automatic recovery (lazy model)

[First Approval]
14. User approves restartDatabase
15. Application calls: runtime.resumeProcess("P100", APPROVED)
16. Checkpoint loaded from storage
17. Agent resolved: agentName="incident-agent" → Agent instance
18. Tools resolved: "restartDatabase" → ToolCallback instance
19. Session valid: sessionId="S1" exists in ChatMemory
20. P100 reconstructed:
    - processId="P100" (stable)
    - status=WAITING
    - pending batch 绑定到 Tool 实现
21. P100.resume(APPROVED) called
22. status: WAITING → RUNNING
23. ToolCallingManager.execute([call_1])
24. Tool "restartDatabase" executes
25. Evidence E1 = Evidence("tool:restartDatabase", "success")
26. Model continuation with ToolResponseMessage
27. Model → ToolCall B: checkDatabaseStatus(prod-db-1)
28. Governance.evaluate(B) → REQUIRE_APPROVAL
29. P100 re-suspension:
    - status: RUNNING → WAITING
    - same processId="P100" (stable)
30. Checkpoint updated:
    {
      processId: "P100",
      status: WAITING,
      agentName: "incident-agent",
      sessionId: "S1",
      pendingBatch: [
        {id: "call_2", name: "checkDatabaseStatus", args: "{\"instanceId\": \"prod-db-1\"}"}
      ],
      evidences: [
        {source: "tool:restartDatabase", content: "success"}
      ],
      suspendedAt: "2026-09-08T10:05:00Z"
    }
31. return AgentResult(suspended, process=P100)

[JVM Terminates Again]
32. Application shutdown
33. Checkpoint updated, contains Evidence E1

[JVM Starts Again]
34. Application starts

[Second Approval]
35. User approves checkDatabaseStatus
36. runtime.resumeProcess("P100", APPROVED)
37. Checkpoint loaded (包含 Evidence E1)
38. P100 reconstructed with evidences=[E1]
39. P100.resume(APPROVED)
40. status: WAITING → RUNNING
41. Tool "checkDatabaseStatus" executes
42. Evidence E2 = Evidence("tool:checkDatabaseStatus", "healthy")
43. evidences = [E1, E2] (累积)
44. Model returns final Assistant message
45. status: RUNNING → COMPLETED
46. Persist final Assistant to ChatMemory(S1):
    chatMemory.add(S1, [AssistantMessage(final content)])
47. ChatMemory(S1) = [H, U, A]
48. Checkpoint deleted (可选) 或 status=COMPLETED
49. return AgentResult(content, evidences=[E1, E2], process=P100)
```

### 关键验证点

✅ **Stable processId:** P100 在整个过程中不变  
✅ **Evidence 累积:** E1 在第二次恢复后仍然存在  
✅ **Session 引用:** 通过 sessionId 访问 ChatMemory，不复制  
✅ **Protocol 正确性:** ToolCall IDs 保留，Spring AI 协议完整  
✅ **Governance 不重复评估:** 批次已评估，直接执行  
✅ **跨重启恢复:** 两次 JVM 终止，P100 都成功恢复  

---

## 22. 失败追踪

### 场景：恢复后执行失败

```
[Setup]
1. P100 WAITING, checkpoint exists
2. JVM restart

[Recovery + Failure]
3. runtime.resumeProcess("P100", APPROVED)
4. Checkpoint loaded
5. P100 reconstructed
6. P100.resume(APPROVED)
7. status: WAITING → RUNNING
8. Tool "restartDatabase" executes
9. Tool throws RuntimeException("Permission denied")
10. Exception escapes continuation
11. P100.status → FAILED (M4 语义)
12. Exception propagates to caller
13. Checkpoint updated (可选):
    {
      processId: "P100",
      status: FAILED,
      ...
    }
14. ChatMemory(S1) = [H, U] (仍然开放，M4 已知限制)
```

### 关键点

✅ **M4 FAILED 语义保留:** continuation 失败 → FAILED  
✅ **Exception 传播:** 原始异常不包装  
✅ **Checkpoint 可选更新:** FAILED 持久化不是 M5 核心要求  
✅ **Evidence 丢失:** 如果工具在失败前部分执行（M4 已知限制）  
⚠️ **Session 开放:** H + U 未关闭（M4 已知限制）  

---

## 23. 崩溃窗口追踪

### 危险窗口：RUNNING 期间崩溃

```
[Scenario]
1. P100 WAITING, checkpoint exists
2. runtime.resumeProcess("P100", APPROVED)
3. P100.resume(APPROVED)
4. status: WAITING → RUNNING
5. Checkpoint 未更新（RUNNING 不持久化）
6. Tool "restartDatabase" starts execution
7. External side effect: database restarting...
8. Tool execution completes successfully
9. Evidence E1 collected
10. Preparing to transition to WAITING/COMPLETED
11. ⚡ JVM CRASH ⚡
12. Checkpoint still says WAITING, pending batch unchanged

[Recovery]
13. JVM restarts
14. runtime.resumeProcess("P100", APPROVED)
15. Checkpoint loaded (状态：WAITING，same pending batch)
16. P100 reconstructed
17. P100.resume(APPROVED)
18. Tool "restartDatabase" executes AGAIN
19. ⚠️ DUPLICATE EXECUTION ⚠️
```

### M5 语义

**✅ At-Least-Once 保证**

崩溃窗口：APPROVED 到 下一个 WAITING checkpoint 之间

**可能后果：**
- 工具重复执行
- Evidence 丢失（E1 未持久化）

**缓解策略（应用层）：**

1. **幂等工具设计**
   ```java
   class RestartDatabaseTool {
       public String call(String args) {
           if (alreadyRestarted(instanceId)) {
               return "already restarted";
           }
           restart(instanceId);
           return "restarted";
       }
   }
   ```

2. **基于 processId + toolCallId 的去重**
   ```java
   if (executionLog.contains(processId, toolCallId)) {
       return cached result;
   }
   ```

**不在 M5 范围：**
- Exactly-once 保证
- 分布式事务
- 框架层去重

---

## 24. 最小存储操作

### 24.1 实际需要的操作

基于恢复模型（Lazy Recovery）分析：

**必需操作：**
```java
void save(String processId, SuspensionCheckpoint checkpoint);
Optional<SuspensionCheckpoint> load(String processId);
```

**可选操作：**
```java
void delete(String processId);  // 清理 COMPLETED checkpoint
```

**不需要的操作：**
```java
// ❌ 不需要（无 eager recovery）
List<SuspensionCheckpoint> findByStatus(ProcessStatus status);

// ❌ 不需要（无批量管理）
List<SuspensionCheckpoint> findAll();

// ❌ 不需要（无老化清理需求）
List<SuspensionCheckpoint> findOlderThan(Instant timestamp);
```

### 24.2 最小存储接口

```java
// 概念接口（不是最终实现）
interface CheckpointStore {
    void save(String processId, SuspensionCheckpoint checkpoint);
    
    Optional<SuspensionCheckpoint> load(String processId) 
        throws CheckpointDeserializationException;
    
    void delete(String processId);  // 可选：清理
}
```

### 24.3 实现策略

**第一实现：InMemoryCheckpointStore**（测试用）

```java
class InMemoryCheckpointStore {
    private final Map<String, SuspensionCheckpoint> store = new ConcurrentHashMap<>();
    
    void save(String pid, SuspensionCheckpoint cp) {
        store.put(pid, cp);
    }
    
    Optional<SuspensionCheckpoint> load(String pid) {
        return Optional.ofNullable(store.get(pid));
    }
}
```

**第二实现：FileCheckpointStore 或 JdbcCheckpointStore**

证明持久化可行性。

**不在 M5-T1 范围：**
- 多种存储后端
- 存储优化
- 批量操作

---

## 25. M5 建议范围

### IN SCOPE (M5)

#### M5-T1: Contract Gate（本文档）
- ✅ 最小持久化状态设计
- ✅ 恢复语义定义
- ✅ Public API delta
- ✅ 依赖分析
- ✅ 失败语义

#### M5-T2: Minimal Durable Process Implementation
- 定义 `SuspensionCheckpoint` 数据结构（framework-neutral）
- 定义 `CheckpointStore` 接口
- 实现 `InMemoryCheckpointStore`（测试用）
- 修改 `SpringAiToolCallingEngine` 在悬挂时写入 checkpoint
- 实现 `AgentRuntime.resumeProcess(processId, signal)`
- Agent 绑定机制（通过 agentName）
- 单元测试

#### M5-T3: Persistent Checkpoint Store
- 实现 `FileCheckpointStore` 或 `JdbcCheckpointStore`
- Schema 管理
- 序列化/反序列化（JSON）
- 集成测试

#### M5-T4: Recovery Verification & Documentation
- 跨 JVM 重启的端到端测试
- 恢复失败处理测试
- At-least-once 语义验证
- 最终文档
- 示例代码

---

### OUT OF SCOPE (M5)

❌ **Exactly-Once 保证** — 需要分布式事务，超出范围  
❌ **Event Bus / Observability** — 独立关注点  
❌ **RUNNING checkpoint** — 语义不支持  
❌ **COMPLETED/FAILED 持久化** — 非核心恢复需求  
❌ **Replay** — 未来能力  
❌ **TestKit（作为主要目标）** — 可能作为副产品  
❌ **Second Execution Engine** — 独立验证  
❌ **Workflow / Graph** — 独立关注点  
❌ **Multi-Agent** — 独立关注点  
❌ **Tool Registry** — 无真实需求  
❌ **AgentDefinition 版本管理** — 应用责任  
❌ **Checkpoint 迁移逻辑** — 未来关注点  
❌ **Field-level 加密** — Infrastructure 责任  
❌ **工具去重框架** — 应用层模式  
❌ **Eager Recovery** — 选择 Lazy 模型  
❌ **自定义 ChatMemory 抽象** — 依赖 Spring AI  

---

## 26. 明确的非目标

### 26.1 M5 不是

❌ **Event Sourcing 系统** — 不构建事件日志  
❌ **审计系统** — 不构建完整审计跟踪  
❌ **Workflow 引擎** — 不构建显式流程图  
❌ **分布式事务协调器** — 不提供 exactly-once  
❌ **Secret 管理** — 不处理敏感数据加密  
❌ **配置管理系统** — 不管理 AgentDefinition 版本  

### 26.2 M5 不保证

❌ **工具不重复执行** — At-least-once 语义  
❌ **RUNNING 状态恢复** — 仅 WAITING 可恢复  
❌ **Checkpoint 永远可读** — Schema 可能演进  
❌ **跨 Arctra 版本兼容** — 需要迁移  
❌ **配置漂移安全** — 应用责任  

### 26.3 M5 不要求

❌ **应用重写现有代码** — 向后兼容 M4  
❌ **更换 Spring AI** — 仍然是当前后端  
❌ **使用特定数据库** — CheckpointStore 可插拔  
❌ **实现 Exactly-Once** — 工具幂等是应用模式  

---

## 27. M5-T2 Implementation Recommendations

基于 M5-T1 契约，M5-T2 应该：

### 27.1 创建的核心类型

```java
// arctra-core (framework-neutral)
record SuspensionCheckpoint(
    String schemaVersion,
    String processId,
    ProcessStatus status,
    String agentName,
    String sessionId,
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences,
    Instant suspendedAt
)

record PendingToolCall(
    String toolCallId,
    String toolName,
    String arguments  // JSON string
)

interface CheckpointStore {
    void save(String processId, SuspensionCheckpoint checkpoint);
    Optional<SuspensionCheckpoint> load(String processId);
    void delete(String processId);
}

class InMemoryCheckpointStore implements CheckpointStore {
    // 测试实现
}
```

### 27.2 修改的现有类型

```java
// AgentRuntime.java
public interface AgentRuntime {
    Agent agent(AgentDefinition definition);
    
    // M5 新增
    AgentResult resumeProcess(String processId, ContinuationSignal signal)
        throws ProcessNotFoundException, ProcessRecoveryException;
}

// SpringAiToolCallingEngine.java
// 在 suspendForApproval() 时写入 checkpoint
// 在 continueWithMessages() 完成后删除 checkpoint
```

### 27.3 Agent 绑定机制

**选项 A: AgentRuntime 扩展**
```java
// DefaultAgentRuntime.java
private final Map<String, AgentDefinition> agentDefinitions = new ConcurrentHashMap<>();

public Agent agent(AgentDefinition definition) {
    agentDefinitions.put(definition.name(), definition);
    return new DefaultAgent(definition, engine);
}

AgentDefinition resolveDefinition(String agentName) {
    return agentDefinitions.get(agentName);
}
```

**选项 B: 应用层注册**
```java
// 应用启动时
AgentRegistry registry = ...;
registry.register("incident-agent", definition);

// 恢复时
AgentDefinition def = registry.get(checkpoint.agentName());
```

**推荐选项 A** — 最小化，利用现有 `AgentRuntime`

### 27.4 测试策略

**单元测试：**
- SuspensionCheckpoint 序列化/反序列化
- InMemoryCheckpointStore CRUD
- AgentRuntime.resumeProcess() 逻辑

**集成测试：**
- SpringAiToolCallingEngine checkpoint 写入
- 模拟 JVM 重启（清空内存，保留 checkpoint）
- 恢复 + resume 完整流程

**不需要：**
- 真实 JVM 重启测试（集成测试足够）

### 27.5 实现顺序

1. **定义数据结构** — SuspensionCheckpoint, PendingToolCall
2. **CheckpointStore 接口 + InMemory 实现**
3. **Agent 绑定机制** — agentName → AgentDefinition
4. **SpringAiToolCallingEngine 集成** — save/delete checkpoint
5. **AgentRuntime.resumeProcess()** — 恢复逻辑
6. **单元测试**
7. **集成测试**

---

## 28. GO / NO-GO 决策

### 28.1 Contract Gate 验收标准

| 标准 | 状态 | 验证 |
|------|------|------|
| 最小持久化状态已定义 | ✅ | SuspensionCheckpoint |
| Process vs Session 边界清晰 | ✅ | 引用 sessionId，不复制 |
| 恢复语义明确 | ✅ | Lazy recovery, at-least-once |
| Public API delta 最小化 | ✅ | 仅 resumeProcess() |
| Agent 重建问题已解决 | ✅ | 通过 agentName |
| Tool 重建问题已解决 | ✅ | 通过 name 匹配 |
| Governance 语义正确 | ✅ | 不重复评估 |
| Session 依赖已明确 | ✅ | 要求 durable ChatMemory |
| 崩溃窗口语义已定义 | ✅ | At-least-once |
| 安全性已文档化 | ✅ | Infrastructure 责任 |
| 纸面追踪已验证 | ✅ | 完整场景 |
| 范围已明确 | ✅ | IN/OUT scope 清晰 |
| 不是 Spring AI 序列化 | ✅ | Framework-neutral types |

### 28.2 架构质量检查

✅ **最小化** — 仅 WAITING checkpoint，不过度设计  
✅ **M4 兼容** — 不破坏 Process ≠ Session 不变式  
✅ **框架中立** — 不序列化 Spring AI 类型  
✅ **实现可行** — 无阻塞性技术障碍  
✅ **用户价值** — 解决生产批准跨重启问题  
✅ **风险可控** — 增量实现，清晰边界  

### 28.3 最终决策

## ✅ **GO — M5-T1 Contract Gate APPROVED**

**批准理由：**

1. **正确性** — 最小持久化状态足以恢复 WAITING Process
2. **架构一致性** — 保持 M4 不变式，Process ≠ Session
3. **框架中立** — 不泄漏 Spring AI 到持久化层
4. **实现清晰** — 边界明确，实现路径清楚
5. **风险可控** — 已知限制已文档化，无隐藏假设

**下一步：**

启动 **M5-T2: Minimal Durable Process Implementation**

**关键交付物：**
- `SuspensionCheckpoint` record
- `CheckpointStore` interface + `InMemoryCheckpointStore`
- `AgentRuntime.resumeProcess()`
- SpringAiToolCallingEngine checkpoint 集成
- 单元测试 + 集成测试

---

**STOP — M5-T1 Contract Gate 完成。等待 M5-T2 实施批准。**
