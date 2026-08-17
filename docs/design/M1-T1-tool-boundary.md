# M1-T1 设计文档：Arctra ↔ Spring AI Tool 边界

**任务：** M1-T1: Arctra ↔ Spring AI Tool 边界设计  
**状态：** COMPLETE  
**日期：** 2026-08-14

---

## 1. Spring AI 2.0 Tool Calling 机制

### 核心组件

**使用当前 Spring AI 2.0 术语：**

1. **`ToolCallback`**
   - 定义：Tool 的执行契约
   - 职责：接收参数（JSON），执行逻辑，返回结果（String）
   - 特点：与 Model 解耦，可独立测试

2. **`ToolCallbackProvider`**
   - 定义：Tool 查找机制
   - 职责：根据 Tool Name 提供对应的 ToolCallback
   - 特点：可以从 Spring Bean、配置文件等来源提供 Tool

3. **`ToolCallbackResolver`**
   - 定义：Tool 解析和查找的协调者
   - 职责：协调多个 ToolCallbackProvider
   - 特点：支持多来源 Tool 注册

4. **`ToolCallingAdvisor`**
   - 定义：递归的 Tool Calling Loop 自动化
   - 职责：
     - Model 请求 Tool 时，自动解析参数
     - 调用对应的 ToolCallback
     - 将结果返回给 Model
     - 递归循环直到 Model 返回最终答案
   - 特点：开发者无需手写 ReAct Loop

5. **`ChatClient`**
   - 定义：Spring AI 的统一聊天接口
   - 职责：与 Model 交互，协调 Advisor
   - 特点：Fluent API，支持 Advisor 链

### Spring AI 已解决的问题

- ✅ Tool 定义和执行（ToolCallback）
- ✅ Tool 注册和查找（ToolCallbackProvider / Resolver）
- ✅ Tool 参数解析（JSON Schema）
- ✅ Tool Calling Loop 自动化（ToolCallingAdvisor）
- ✅ Model ↔ Tool 协议适配
- ✅ 与 Spring Bean 集成

---

## 2. Arctra 真正还需要补充什么语义

### 2.1 Evidence 收集（M1 需要）

**问题：** Spring AI 只返回 Tool 结果给 Model，不记录执行证据

**Arctra 需要：**
- Tool 调用记录（哪个 Tool、什么参数、什么结果）
- 时间戳
- 来源标识

**实现位置：** 待 M1-T3 验证
- 可能在 ToolCallingAdvisor 层（Advisor Wrapper）
- 可能在 ToolCallback 层（ToolCallback Wrapper）
- 可能在 Engine 层（Engine 内部观测）
- M1-T3 会对比不同 observation point，选择最合适的实现位置

**M1-T1 不提前决定 Evidence capture 位置。**

### 2.2 未来 Governance 扩展点（M1 不实现）

**问题：** Spring AI ToolCallback 没有以下语义：
- Permission 检查（这个 Agent 能用这个 Tool 吗？）
- Risk 评估（这个 Tool 操作是否危险？）
- Audit 记录（谁在什么时候调用了什么 Tool？）
- Policy 决策（这个调用需要人工审批吗？）
- Sandbox 隔离（Tool 在受控环境运行）
- Timeout/Retry（Tool 执行失败如何处理）

**Arctra 需要：**
- 为未来 Governance 预留扩展点
- 不阻碍在 Tool 执行前/后注入逻辑

**实现位置：** Arctra Tool Runtime 或独立 Governance 层
- **不在 Engine 实现：** Engine 只负责执行策略，不负责 Governance
- **不是每个 Engine 自己包装：** Governance 应统一，不是每个 Engine 各自实现
- **位于 Engine 与 Tool 之间：** Governance 拦截所有 Tool 调用，无论哪个 Engine
- **或独立的 Arctra Tool Runtime：** 统一管理所有 Tool 执行语义

**架构示意（未来，非 M1）：**

```
Engine (SpringAI / AgentScope / Custom)
  ↓
Arctra Tool Runtime / Governance Layer
  ↓ (Permission / Risk / Audit / Policy / Sandbox)
Actual Tool Execution (ToolCallback)
```

**关键：** Governance 不属于 Engine 实现细节

### 2.3 Tool 语义标记（M1 不实现）

**问题：** Spring AI ToolCallback 只关注"如何执行"，不关注"这个 Tool 是什么"

**Arctra 未来需要：**
- Tool 元数据（描述、分类、风险等级）
- Tool 能力声明（需要什么权限、访问什么资源）

**实现位置：** Arctra Tool Contract（未来）

---

## 3. 能力归属

### 职责划分

| 能力 | 归属 | M1 状态 | 理由 |
|------|------|---------|------|
| **Tool 定义** | Spring AI ToolCallback | ✅ 使用 | Spring AI 已提供 |
| **Tool 注册** | Spring AI ToolCallbackProvider | ✅ 使用 | Spring AI 已提供 |
| **Tool Calling Loop** | Spring AI ToolCallingAdvisor | ✅ 使用 | Spring AI 已提供 |
| **参数解析** | Spring AI | ✅ 使用 | Spring AI 已提供 |
| **Evidence 收集** | **待 M1-T3 验证** | 🔄 待决定 | 多种 observation point 可选 |
| **Permission** | Arctra Tool Runtime/Governance | ❌ 未来 | M1 无需权限控制 |
| **Risk 评估** | Arctra Tool Runtime/Governance | ❌ 未来 | M1 无需风险评估 |
| **Audit** | Arctra Tool Runtime/Governance | ❌ 未来 | M1 无需审计 |
| **Policy 决策** | Arctra Tool Runtime/Governance | ❌ 未来 | M1 无需 Policy |
| **Sandbox** | Arctra Tool Runtime | ❌ 未来 | M1 Mock Tools 无需隔离 |
| **Timeout/Retry** | Arctra Tool Runtime | ❌ 未来 | M1 Mock Tools 不会失败 |

---

## 4. M1 是否真的需要 Arctra 自己的 Tool Contract

### 判断：**M1 不需要**

**理由：**

1. **Spring AI ToolCallback 足够**
   - M1 的 Mock Tools 可以直接实现 ToolCallback
   - 无需 Arctra 自己的 Tool 接口

2. **Evidence 收集不依赖 Tool Contract**
   - 无论在 Advisor / ToolCallback Wrapper / Engine 哪层收集
   - 都不需要改变 Tool 契约

3. **未来 Governance 位于独立层**
   - 不在 Engine 实现 Governance
   - 不在 Tool 实现 Governance
   - Governance 拦截 Tool 调用，独立于 Tool Contract

4. **符合"现在谁在用？"原则**
   - M1 只需要 2 个 Mock Tools
   - 直接实现 Spring AI ToolCallback
   - 不需要 Arctra 自己的抽象

### 什么时候需要 Arctra Tool Contract？

**未来需要的时机：**
- 有真实的 Permission/Risk/Audit 需求
- 需要统一的 Tool 元数据管理
- 需要在多个 Engine 之间共享 Tool 语义
- 需要非 Spring AI 的 Tool 机制（如 AgentScope 集成）

**M1 不满足这些条件，不需要。**

---

## 5. 最小类型清单（M1）

### M1 不需要创建的类型

❌ `Tool` 接口（使用 Spring AI `ToolCallback`）
❌ `ToolRequest` / `ToolResult`（Spring AI 管理）
❌ `ToolRegistry`（使用 Spring AI `ToolCallbackProvider`）
❌ `ToolProvider`（使用 Spring AI）
❌ `ToolAdapter`（直接实现 `ToolCallback`）
❌ `ToolGovernance`（未来）
❌ `ToolRuntime`（未来）

### M1 需要的类型

**只有 Evidence 相关：**

```java
// arctra-core（M1-T2 创建）
package cn.bitcss.arctra.evidence;

public record Evidence(
  String source,      // Tool name or "model-reasoning"
  String content,     // Tool result or model output
  Instant timestamp
) {}
```

**仅此而已。**

---

## 6. Adapter 判断

### 是否需要 Adapter：**M1 不需要**

**理由：**

1. **Mock Tools 直接实现 Spring AI ToolCallback**
   ```java
   // examples/incident-investigator 或 test
   public class QueryLogsTool implements ToolCallback {
     @Override
     public String call(String jsonArguments) {
       // 返回 Mock 日志
       return """
         {
           "logs": [
             "16:20:15 ERROR SQLException: Unknown column 'user_status'",
             "16:20:18 ERROR SQLException: Unknown column 'user_status'"
           ]
         }
         """;
     }
     
     @Override
     public String getName() {
       return "queryLogs";
     }
     
     @Override
     public String getDescription() {
       return "Query application logs by time range and level";
     }
   }
   ```

2. **Engine 直接使用 Spring AI ChatClient**
   ```java
   // arctra-runtime-react
   class SpringAIBasedEngine implements AgentExecutionEngine {
     private final ChatClient chatClient;
     
     @Override
     public AgentResult execute(AgentDefinition def, AgentRequest req) {
       // Spring AI 自动处理 Tool Calling Loop
       var response = chatClient.prompt()
           .user(req.userMessage())
           .call()
           .content();
       
       // 收集 Evidence（实现位置待 M1-T3 验证）
       List<Evidence> evidences = collectEvidences();
       
       return new AgentResult(response, evidences);
     }
   }
   ```

3. **未来 Governance 不在 Engine 实现**
   ```java
   // 未来（不是 M1）
   // 独立的 Arctra Tool Runtime/Governance Layer
   class GovernanceToolCallbackWrapper implements ToolCallback {
     private final ToolCallback delegate;
     private final ToolGovernance governance; // 统一的 Governance
     
     @Override
     public String call(String arguments) {
       // Pre-execution: Permission / Risk / Policy
       governance.checkPermission(getName(), arguments);
       governance.assessRisk(getName(), arguments);
       
       // Execution
       String result = delegate.call(arguments);
       
       // Post-execution: Audit
       governance.audit(getName(), arguments, result);
       
       return result;
     }
   }
   
   // 所有 Engine 共享这个 Governance Layer
   // 不是每个 Engine 自己实现 Governance
   ```

**结论：M1 不需要 Adapter。**

---

## 7. 候选类型逐个判断

### Arctra ToolRegistry

**现在谁在用？** 无  
**不加做不了什么？** 什么都能做（Spring AI ToolCallbackProvider 足够）  
**结论：** ❌ 不需要

### Arctra ToolProvider

**现在谁在用？** 无  
**不加做不了什么？** 什么都能做（Spring AI 机制足够）  
**结论：** ❌ 不需要

### ToolAdapter

**现在谁在用？** 无  
**不加做不了什么？** 什么都能做（直接实现 ToolCallback）  
**结论：** ❌ 不需要

### ToolRequest / ToolResult

**现在谁在用？** 无  
**不加做不了什么？** 什么都能做（Spring AI 管理参数和结果）  
**结论：** ❌ 不需要

### Arctra Tool 接口

**现在谁在用？** 无  
**不加做不了什么？** 什么都能做（Spring AI ToolCallback）  
**结论：** ❌ 不需要

---

## 8. 推荐调用链

### M1 调用链（Evidence capture 位置待验证）

```
用户测试代码
  ↓
AgentRuntime.execute(definition, request)
  ↓
SpringAIBasedEngine.execute(...)
  ↓
ChatClient.prompt().user(message).call()
  ↓
  [Spring AI ToolCallingAdvisor - Recursive Loop]
  Model 请求 Tool 
    → ToolCallbackResolver 查找 ToolCallback
    → 执行 ToolCallback
    → 将结果返回 Model
    → 递归直到 Model 返回 Final Answer
  ↓
  [Evidence Capture - 具体位置待 M1-T3 验证]
  可能在：
  - ToolCallingAdvisor 层（Advisor Wrapper）
  - ToolCallback 层（ToolCallback Wrapper）
  - Engine 层（Engine 内部观测）
  ↓
Response.content()
  ↓
new AgentResult(content, evidences)
  ↓
返回给用户
```

**关键点：**
- Spring AI 管理整个 Tool Calling Loop
- Evidence capture 位置由 M1-T3 验证后决定
- Mock Tools 直接实现 Spring AI ToolCallback

---

## 9. 模块依赖图

```
┌─────────────────────┐
│   arctra-core       │ (Pure Java)
│                     │
│ - AgentRuntime      │
│ - AgentExecutionEngine
│ - Evidence          │ ← M1-T2 新增
└──────────┬──────────┘
           │
           ↓ implements
┌─────────────────────┐
│arctra-runtime-react │ (Spring AI)
│                     │
│ - SpringAIBasedEngine│ ← M1-T6
│   (uses ChatClient) │
└──────────┬──────────┘
           │ uses
           ↓
┌─────────────────────┐
│   Spring AI         │ (External)
│                     │
│ - ChatClient        │
│ - ToolCallback      │
│ - ToolCallbackProvider
│ - ToolCallingAdvisor│
└─────────────────────┘
           ↑ implements
           │
┌─────────────────────┐
│ Mock Tools          │ (examples or test)
│                     │
│ - QueryLogsTool     │ ← M1-T5
│ - GetDeploymentTool │
└─────────────────────┘
```

**依赖方向：**
- arctra-core → 不依赖任何人（纯 Java）
- arctra-runtime-react → arctra-core + Spring AI
- Mock Tools → Spring AI ToolCallback
- Mock Tools → M1 当前不依赖 arctra-core（但不作为长期规则）

**说明：**
- "Mock Tools 当前不依赖 arctra-core"只是 M1 实现事实
- 不作为长期 Architecture Rule
- 未来真实 Tool 可能需要 Arctra 类型

---

## 10. Module / Package 归属

### arctra-core（纯 Java）

```
cn.bitcss.arctra/
├── agent/
│   ├── AgentDefinition
│   ├── AgentRequest
│   └── AgentResult (扩展：添加 evidences 字段)
├── runtime/
│   ├── AgentRuntime
│   ├── AgentExecutionEngine
│   └── DefaultAgentRuntime
└── evidence/                    ← M1-T2 新增
    └── Evidence
```

### arctra-runtime-react（Spring AI 集成）

```
cn.bitcss.arctra.runtime.react/
└── SpringAIBasedEngine          ← M1-T6
```

### examples/incident-investigator（或 test）

```
cn.bitcss.arctra.examples.incident/
└── tools/
    ├── QueryLogsTool            ← M1-T5
    └── GetDeploymentTool        ← M1-T5
```

**说明：**
- Mock Tools 在 examples 或 test，不在产品代码
- 直接实现 Spring AI ToolCallback
- M1 暂无 Arctra 自己的 Tool 包

---

## 11. Spring AI 适配方式

### M1: 无需适配，直接使用

**M1 策略：**
1. Mock Tools 直接实现 `ToolCallback`
2. Engine 直接使用 `ChatClient` + `ToolCallingAdvisor`
3. Evidence 收集位置由 M1-T3 验证后决定

### 未来 Governance: 独立层，非 Engine 实现

**未来架构（不是 M1）：**

```
┌─────────────────────────────┐
│ Engine (SpringAI / AgentScope)│
└──────────┬──────────────────┘
           │ 调用
           ↓
┌─────────────────────────────┐
│ Arctra Tool Runtime/Governance│ ← 统一的 Governance 层
│                               │
│ - Permission Check            │
│ - Risk Assessment             │
│ - Policy Decision             │
│ - Audit Logging               │
│ - Sandbox Isolation           │
│ - Timeout/Retry               │
└──────────┬──────────────────┘
           │ 执行
           ↓
┌─────────────────────────────┐
│ ToolCallback Implementation   │
└─────────────────────────────┘
```

**关键设计：**
- Governance 不属于 Engine
- Governance 是独立层，统一管理所有 Tool 执行
- 所有 Engine 共享同一套 Governance
- 不是每个 Engine 自己包装 ToolCallback

**M1 不需要这个机制。**

---

## 12. 明确延后的能力

### M1 不实现（未来）

| 能力 | 延后理由 | 未来实现位置 |
|------|----------|--------------|
| **Permission** | M1 Mock Tools 无需权限 | Arctra Tool Runtime/Governance |
| **Risk 评估** | M1 无危险操作 | Arctra Tool Runtime/Governance |
| **Audit** | M1 无审计需求 | Arctra Tool Runtime/Governance |
| **Policy 决策** | M1 无 Policy 引擎 | Arctra Tool Runtime/Governance |
| **Sandbox** | M1 Mock Tools 无需隔离 | Arctra Tool Runtime |
| **Timeout** | M1 Mock Tools 不会超时 | Arctra Tool Runtime |
| **Retry** | M1 Mock Tools 不会失败 | Arctra Tool Runtime |
| **Tool 元数据** | M1 只有 2 个固定 Tool | Arctra Tool Contract（未来）|
| **Arctra Tool Contract** | Spring AI 足够 | 等 Governance / Multi-Engine 需求 |

---

## 13. ADR 判断

### 是否需要 ADR：**否**

**理由：**

1. **遵循已有 ADR-001**
   - Runtime 与 Engine 分离 ✅
   - M1 不改变这个决策

2. **没有新的架构决策**
   - 使用 Spring AI 是技术选型，不是架构约束
   - "M1 不创建 Arctra Tool Contract"是延后决策，不是禁止决策

3. **设计可逆**
   - M1 使用 Spring AI ToolCallback
   - 未来可以增加 Arctra Tool Contract + Governance Layer
   - 不影响用户 API（因为还没有 AgentClient）

### 如果需要 ADR 的情况

**以下情况需要 ADR：**
- 决定永远不支持非 Spring AI 的 Tool 机制
- 决定 Arctra Tool Governance 必须基于 Spring AI
- 决定 Tool 必须是 Spring Bean
- 决定 Governance 属于 Engine 实现

**M1 没有做这些决策。**

---

## 14. 推荐边界（总结）

### Arctra ↔ Spring AI 边界

| 层次 | Arctra 职责 | Spring AI 职责 |
|------|------------|----------------|
| **Tool 定义** | 无 | ToolCallback |
| **Tool 注册** | 无 | ToolCallbackProvider / Resolver |
| **Tool Calling Loop** | 无 | ToolCallingAdvisor (recursive) |
| **参数解析** | 无 | Spring AI |
| **Evidence 收集** | **待 M1-T3 验证位置** | 无 |
| **Governance** | **未来：独立 Tool Runtime/Governance 层** | 无 |

### 关键设计原则

1. **M1 不创建 Arctra Tool Contract**
   - 直接使用 Spring AI ToolCallback
   - 符合"现在谁在用？"原则
   - 等 Governance / Multi-Engine 需求出现后再决定

2. **Evidence 收集位置由 M1-T3 验证**
   - 不在 M1-T1 提前决定
   - 可能在 Advisor / ToolCallback Wrapper / Engine 层
   - 对比不同 observation point 后选择最佳方案

3. **未来 Governance 不属于 Engine**
   - Governance 是独立层，统一管理所有 Tool 执行
   - 位于 Engine 与 Tool 之间，或独立的 Arctra Tool Runtime
   - 不是每个 Engine 自己实现 Governance
   - 所有 Engine 共享同一套 Governance

4. **Mock Tools 在 examples/test**
   - 不污染 arctra-runtime-react 产品代码
   - 直接实现 Spring AI ToolCallback
   - M1 当前不依赖 arctra-core（但不作为长期规则）

---

## 15. M1-T1 最终结论

### 设计决策

**M1 暂不创建 Arctra Tool Contract**

**理由：**
- Spring AI ToolCallback 体系足够满足 M1 需求
- Mock Tools 直接实现 ToolCallback
- Evidence 收集不依赖 Tool Contract
- 未来 Governance 在独立层，不改变 Tool Contract

**是否需要 Arctra Tool Contract 由未来场景验证：**
- 真实 Governance 需求出现（Permission/Risk/Audit）
- Multi-Engine 需求出现（AgentScope 集成）
- Tool 元数据管理需求出现

### arctra-core 需要的类型

**只有：**
- `Evidence` record（M1-T2 创建）

### arctra-runtime-react 需要的类型

**只有：**
- `SpringAIBasedEngine`（M1-T6 创建，命名待验证）

### Mock Tools 归属

**放在：**
- `examples/incident-investigator` 或 test fixtures
- 直接实现 Spring AI ToolCallback

---

**M1-T1 设计文档已完成。** ✅
