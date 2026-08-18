# M2-T2 Contract Gate V2

**Date:** 2026-08-18  
**Status:** DRAFT - Awaiting Approval  
**Dependencies:** M2-T1 PoC Complete

---

## Executive Summary

本文档基于 M2-T1 PoC 的实际发现，重新分析 Session Identity Propagation 架构设计。

**核心发现：**
- ✅ Spring AI conversationId 是 per-call parameter（不是 configuration）
- ✅ MessageChatMemoryAdvisor 通过 advisor param 接收 conversationId
- ✅ Session 管理完全由 Spring AI 负责

**核心问题：**
> sessionId 的 **semantic ownership** 属于谁？

**候选答案：**
- A. SpringAiToolCallingEngine capability parameter
- B. Agent execution context
- C. Runtime-level concern

---

## Part 1: 重新理解 PoC 真正证明了什么

### 1.1 PoC 验证的事实

**Fact 1: Spring AI 的 conversationId 是 per-call**
```java
client.prompt()
    .advisors(spec -> spec.param("conversationId", id))
    .call();
```

**Fact 2: conversationId 不是 ChatClient configuration**
```java
// ❌ 不是这样：
ChatClient client = new ChatClient(model, conversationId);

// ✅ 是这样：
client.prompt()
    .advisors(spec -> spec.param("conversationId", id))
    .call();
```

**Fact 3: MessageChatMemoryAdvisor 自包含**
- `before()`: 根据 conversationId 加载 history
- `after()`: 根据 conversationId 保存 messages
- 无需外部 session 管理

---

### 1.2 PoC 不能推导的结论

**❌ 错误推导 1:**
> "因为 Spring AI 是 per-call，所以 Arctra 不能有 Runtime-level session binding"

**反驳：**
- Spring AI per-call ≠ Arctra API per-call
- Arctra 可以在 Runtime 层管理 session identity
- SpringAiToolCallingEngine 只是 adapter，负责映射

**❌ 错误推导 2:**
> "因为 conversationId 通过 advisor param 传递，所以必须有 executeWithSession()"

**反驳：**
- Advisor param 是 Spring AI transport mechanism
- 不代表 Arctra public API 必须暴露 sessionId parameter
- 可以通过其他方式传递（例如 ExecutionContext）

---

### 1.3 PoC 揭示的真实问题

**真实问题：**
> Arctra 的 sessionId 如何到达 Spring AI 的 advisor param？

**传播链：**
```
Arctra session identity (语义层)
    ↓ (transport)
Spring AI conversationId (实现层)
```

**关键设计点：**
1. Arctra sessionId 在哪一层被引入？
2. 谁负责将 Arctra sessionId 映射到 Spring AI conversationId？
3. ExecutionEngine contract 应该感知 session 吗？

---

## Part 2: Session 的 Semantic Ownership 分析

### 2.1 什么是 Session？

**Arctra 语义：**
> Session = execution continuity identity

**不是：**
- ❌ Spring AI conversationId（那是实现细节）
- ❌ ChatMemory key（那是存储机制）
- ❌ User identity（那是另一个维度）

**是：**
- ✅ Agent execution 的连续性标识
- ✅ 跨多次 execution 的状态边界
- ✅ Platform-level semantic（不是 framework-specific）

---

### 2.2 sessionId 属于谁？

#### 选项 A: sessionId 属于 AgentRequest

```java
record AgentRequest(
    String userMessage,
    String sessionId
)
```

**分析：**
- sessionId 是 user input 吗？❌ 不是
- sessionId 是 request content 吗？❌ 不是
- AgentRequest 是"stateless, single-turn"吗？✅ 是（注释明确）

**结论：** ❌ **语义错误**

---

#### 选项 B: sessionId 属于 AgentDefinition

```java
record AgentDefinition(
    String name,
    String description,
    String sessionId  // ❌
)
```

**分析：**
- AgentDefinition 是 agent 模板吗？✅ 是
- sessionId 是模板的一部分吗？❌ 不是（session 是 runtime instance）
- 一个 AgentDefinition 可以服务多个 session 吗？✅ 应该可以

**结论：** ❌ **语义错误**

---

#### 选项 C: sessionId 属于 AgentExecutionContext

```java
record AgentExecutionContext(
    String sessionId
)

interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
}
```

**分析：**
- sessionId 是 execution-level concern 吗？✅ 是
- sessionId 影响 execution 行为吗？✅ 是（影响 history injection）
- sessionId 是 Agent 语义吗？✅ 是（不是 Spring AI-specific）
- sessionId 属于哪一次 execution？✅ 每次 execution 可以不同

**语义对齐：**
```
Agent execution context
  ├─ sessionId: 这次 execution 的连续性标识
  ├─ (future) userId: 这次 execution 的用户标识
  └─ (future) traceId: 这次 execution 的追踪标识
```

**结论：** ✅ **语义正确**

---

#### 选项 D: sessionId 属于 SpringAiToolCallingEngine

```java
public class SpringAiToolCallingEngine {
    public AgentResult executeWithSession(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId
    ) { ... }
}
```

**分析：**
- sessionId 是 Spring AI-specific 吗？❌ 不是（是 Arctra 语义）
- sessionId 只有 SpringAiToolCallingEngine 需要吗？❌ 不一定
- 其他 Engine（未来 AgentScopeEngine）会需要 session 吗？✅ 很可能
- executeWithSession 是长期 public API 吗？⚠️ 存疑

**分析 API proliferation 风险：**
```java
// 当前
execute(...)
executeWithSession(...)

// 未来？
executeWithUser(...)
executeWithTenant(...)
executeWithTrace(...)
executeWithApproval(...)

// 或组合？
executeWithSessionAndUser(...)
executeWithSessionAndTrace(...)
```

**结论：** ⚠️ **短期可行，长期有风险**

---

### 2.3 结论：Session 是 Execution Context

**核心判断：**
> sessionId 不属于 user input（AgentRequest）  
> sessionId 不属于 agent template（AgentDefinition）  
> sessionId 不属于 engine capability（SpringAiToolCallingEngine）  
> **sessionId 属于 agent execution context**

**类比：**
- HTTP request 有 request body（AgentRequest）
- HTTP request 有 headers（AgentExecutionContext）
- sessionId 像 Cookie/Session-ID header，不是 body

---

## Part 3: Option C vs Option D 深度对比

### 3.1 API Surface 对比

#### Option C1: ExecutionContext (覆盖式)

```java
public record AgentExecutionContext(String sessionId) {}

public interface AgentExecutionEngine {
    // 新 canonical method (3-param)
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
    
    // 旧 compatibility method (2-param) - 委托
    default AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return execute(definition, request, AgentExecutionContext.stateless());
    }
}
```

**实现：**
```java
public class SpringAiToolCallingEngine implements AgentExecutionEngine {
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    ) {
        String sessionId = context.sessionId();
        
        // Map to Spring AI conversationId
        var promptSpec = client.prompt()
            .system(...)
            .user(request.userMessage());
        
        if (sessionId != null) {
            promptSpec = promptSpec.advisors(spec ->
                spec.param("conversationId", sessionId)
            );
        }
        
        return promptSpec.call();
    }
}
```

---

#### Option C2: ExecutionContext (重载式)

```java
public record AgentExecutionContext(String sessionId) {
    public static AgentExecutionContext stateless() {
        return new AgentExecutionContext(null);
    }
}

public interface AgentExecutionEngine {
    // 保持 2-param 作为主方法
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    );
    
    // 新增 3-param 重载
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
}
```

**问题：** 哪个是 canonical？需要明确。

---

#### Option D: Engine-Specific Method

```java
public interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    );
    // 不变
}

public class SpringAiToolCallingEngine implements AgentExecutionEngine {
    @Override
    public AgentResult execute(...) {
        return executeWithSession(..., null);
    }
    
    // Engine-specific
    public AgentResult executeWithSession(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId
    ) {
        // 实现
    }
}
```

---

### 3.2 对比维度分析

| 维度 | Option C (ExecutionContext) | Option D (executeWithSession) |
|------|----------------------------|------------------------------|
| **语义正确性** | ✅ sessionId 是 execution context | ⚠️ sessionId 看起来像 engine capability |
| **可扩展性** | ✅ 未来加字段不破坏 API | ❌ 未来加能力需要新 method |
| **Contract 统一性** | ✅ 所有 engine 统一 contract | ❌ Session 是 engine-specific |
| **M1 兼容性** | ✅ default method 保持兼容 | ✅ execute() 不变 |
| **发现性** | ✅ 通过 interface 可发现 | ❌ 需要 instanceof 或文档 |
| **实现复杂度** | ⚠️ 所有 engine 都要处理 context | ✅ 只改 SpringAiToolCallingEngine |
| **"只有一个字段"顾虑** | ⚠️ M2 只有 sessionId | ✅ 不创建 context 对象 |
| **长期 API 质量** | ✅ Clean, extensible | ⚠️ API proliferation 风险 |
| **适配 Future Agent API** | ✅ 自然映射 | ⚠️ 需要 adapter 层 |

---

### 3.3 "只有一个字段"的重新评估

**原顾虑：**
> "ExecutionContext M2 只有一个字段（sessionId），所以是过早抽象"

**重新分析：**

**判断标准不应该是字段数量，而是：**
1. sessionId 是否有独立的语义？✅ 是（execution context）
2. sessionId 是否属于 AgentRequest？❌ 不是
3. sessionId 是否属于 AgentDefinition？❌ 不是
4. sessionId 未来是否应该被所有 engine 看见？✅ 是
5. sessionId 是否是 framework-specific？❌ 不是（Arctra 语义）

**类比分析：**

**Bad Example（过早抽象）：**
```java
// M2 只有一个配置项，但创建了 Config 对象
record EngineConfig(int maxRetries) {}
// 过早，因为没有第二个配置项的迹象
```

**Good Example（正确抽象）：**
```java
// HTTP Request 只有一个 header，但仍然有 Headers 对象
record HttpHeaders(String sessionCookie) {}
// 正确，因为 header 有独立语义，且未来会增加
```

**AgentExecutionContext 更像 HttpHeaders：**
- sessionId 有独立的 execution-level 语义
- 未来明确会有 userId, traceId（不是猜测）
- 即使 M2 只有一个字段，抽象仍然语义正确

**关键洞察：**
> 创建抽象的触发条件不是"字段数量 ≥ 2"  
> 而是"是否有独立的领域语义 + 是否有明确的演进方向"

---

### 3.4 Capability vs Context 的区别

**Capability（能力）：**
- Engine 可以做什么
- 例如：是否支持 streaming, tool calling, multi-modal
- 不同 engine 可以有不同 capability

**Context（上下文）：**
- 这次 execution 的环境信息
- 例如：sessionId, userId, traceId
- 所有 engine 都应该接收（即使不使用）

**关键区别：**

**Session 不是 capability，是 context：**
```java
// ❌ 错误理解（session 是 capability）
if (engine.supportsSession()) {
    engine.executeWithSession(...);
}

// ✅ 正确理解（session 是 context）
AgentExecutionContext context = new AgentExecutionContext(sessionId);
AgentResult result = engine.execute(definition, request, context);

// Engine 内部决定如何处理：
// - SpringAiToolCallingEngine: 使用 MessageChatMemoryAdvisor
// - AgentScopeEngine: 使用 AgentScope SessionManager
// - StatelessEngine: 忽略 sessionId（或 throw UnsupportedOperationException）
```

**类比：**
- userId 不是 capability，是 context（即使某些 engine 忽略）
- traceId 不是 capability，是 context（即使某些 engine 不追踪）
- sessionId 同理

---

## Part 4: Runtime / Engine / Spring AI 职责边界

### 4.1 清晰的职责分层

#### Layer 1: Arctra Semantics（语义层）

**职责：**
- 定义 Session 是什么（execution continuity identity）
- 定义 AgentExecutionContext
- 定义 AgentExecutionEngine contract

**不负责：**
- 不负责 conversation history storage
- 不负责 Spring AI adapter
- 不负责具体 persistence

**关键类型：**
```java
// arctra-core
record AgentExecutionContext(String sessionId) {}

interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
}
```

---

#### Layer 2: Engine Adaptation（适配层）

**职责：**
- 将 Arctra semantics 映射到 framework semantics
- SpringAiToolCallingEngine: sessionId → conversationId
- AgentScopeEngine (future): sessionId → AgentScope session

**不负责：**
- 不定义 session 语义（由 Arctra 定义）
- 不暴露 framework-specific API 到 public

**关键实现：**
```java
// arctra-runtime-react
public class SpringAiToolCallingEngine implements AgentExecutionEngine {
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    ) {
        // Adapt: Arctra sessionId → Spring AI conversationId
        String sessionId = context.sessionId();
        
        if (sessionId != null) {
            promptSpec.advisors(spec ->
                spec.param("conversationId", sessionId)  // Adapter
            );
        }
        
        // ...
    }
}
```

---

#### Layer 3: Framework Implementation（实现层）

**职责：**
- Spring AI: 提供 ChatMemory storage
- Spring AI: 提供 MessageChatMemoryAdvisor
- Spring AI: 提供 conversationId parameter passing

**不负责：**
- 不定义 Arctra session 语义
- 不感知 AgentExecutionContext

**关键 API：**
```java
// Spring AI (framework layer)
client.prompt()
    .advisors(spec -> spec.param("conversationId", id))
    .call();
```

---

### 4.2 职责边界图

```
┌─────────────────────────────────────────────────────────┐
│ Arctra Semantics (arctra-core)                         │
│                                                         │
│ AgentExecutionContext(sessionId)                        │
│ AgentExecutionEngine.execute(..., context)              │
│                                                         │
│ Defines: What is session                                │
│ Defines: How engines receive session                    │
└─────────────────────────────────────────────────────────┘
                            ↓ implements
┌─────────────────────────────────────────────────────────┐
│ Engine Adaptation (arctra-runtime-react)                │
│                                                         │
│ SpringAiToolCallingEngine                               │
│   - Receives: AgentExecutionContext                     │
│   - Maps: sessionId → conversationId                    │
│   - Calls: Spring AI with advisor param                │
│                                                         │
│ Adapts: Arctra semantics → Spring AI semantics         │
└─────────────────────────────────────────────────────────┘
                            ↓ uses
┌─────────────────────────────────────────────────────────┐
│ Framework Implementation (Spring AI)                    │
│                                                         │
│ MessageChatMemoryAdvisor                                │
│   - Receives: conversationId via advisor param          │
│   - Loads: history from ChatMemory                      │
│   - Saves: new messages                                 │
│                                                         │
│ Provides: Storage + history injection mechanism         │
└─────────────────────────────────────────────────────────┘
```

**关键洞察：**
> Spring AI per-call 模型只影响 Layer 3（实现层）  
> 不影响 Layer 1（Arctra 语义层）的设计  
> Layer 2（适配层）负责映射

---

## Part 5: Future Agent API 映射分析

### 5.1 未来 Agent API (M3+)

**目标 API：**
```java
agent
    .session("incident-123")
    .user("生产环境 500 错误")
    .call();
```

---

### 5.2 Option C 的映射

```java
// Agent API layer (M3)
public class AgentClient {
    public SessionBuilder session(String sessionId) {
        return new SessionBuilder(sessionId, this);
    }
    
    class SessionBuilder {
        private final String sessionId;
        
        public UserBuilder user(String message) {
            return new UserBuilder(message, sessionId, AgentClient.this);
        }
        
        class UserBuilder {
            AgentResult call() {
                // 构造 context
                AgentExecutionContext context =
                    new AgentExecutionContext(sessionId);
                
                // 调用 engine
                return engine.execute(
                    agentDefinition,
                    new AgentRequest(userMessage),
                    context  // ← 自然映射
                );
            }
        }
    }
}
```

**优点：**
- ✅ 自然、直接的映射
- ✅ 无需 instanceof 检查
- ✅ 所有 engine 统一调用方式

---

### 5.3 Option D 的映射

```java
// Agent API layer (M3)
public class AgentClient {
    class UserBuilder {
        AgentResult call() {
            // 需要判断 engine 类型
            if (engine instanceof SpringAiToolCallingEngine springEngine) {
                return springEngine.executeWithSession(
                    agentDefinition,
                    new AgentRequest(userMessage),
                    sessionId  // ← 需要 adapter
                );
            } else {
                // 其他 engine？
                // 没有统一的 session 支持方式
                return engine.execute(agentDefinition, new AgentRequest(userMessage));
            }
        }
    }
}
```

**缺点：**
- ⚠️ 需要 instanceof 判断
- ⚠️ 没有统一的 session 传递方式
- ⚠️ 新 engine 需要新的 adapter 逻辑

---

## Part 6: Migration Strategy 分析

### 6.1 Option C 的迁移路径

**M2 引入：**
```java
public interface AgentExecutionEngine {
    // 新 canonical method
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
    
    // 兼容 M1
    default AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return execute(definition, request, AgentExecutionContext.stateless());
    }
}
```

**Breaking Change：**
- ⚠️ 所有 Engine 实现需要更新
- ✅ 但通过 default method，M1 代码继续工作

**Migration:**
```java
// M1 Engine 实现（需要更新）
public class MyEngine implements AgentExecutionEngine {
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        // Old implementation
    }
    
    // M2: 需要添加 3-param 方法
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    ) {
        // 可以忽略 context，调用 2-param 方法
        return execute(definition, request);
    }
}
```

**Impact:**
- M1 **用户代码**不受影响（default method）
- M1 **Engine 实现者**需要添加 3-param 方法（可以忽略 context）

---

### 6.2 Option D 的迁移路径

**M2 引入：**
```java
public interface AgentExecutionEngine {
    AgentResult execute(...);  // 不变
}

public class SpringAiToolCallingEngine {
    // M1 (保持)
    @Override
    public AgentResult execute(...) { ... }
    
    // M2 (新增)
    public AgentResult executeWithSession(...) { ... }
}
```

**Breaking Change：**
- ✅ 无 breaking change
- ✅ M1 代码完全不受影响

**Migration:**
- 无需迁移

**Impact:**
- ✅ 零影响

---

### 6.3 迁移成本对比

| 维度 | Option C | Option D |
|------|---------|----------|
| **M1 用户代码** | ✅ 不受影响 | ✅ 不受影响 |
| **M1 Engine 实现** | ⚠️ 需要添加方法 | ✅ 不受影响 |
| **Breaking Change** | ⚠️ Interface 变化 | ✅ 无 |
| **Migration 工作量** | ⚠️ 中等 | ✅ 零 |
| **长期 API 质量** | ✅ 更好 | ⚠️ API proliferation |

---

## Part 7: 最终推荐与理由

### 7.1 推荐：Option C (AgentExecutionContext)

**决策：** 引入 `AgentExecutionContext`，修改 `AgentExecutionEngine` contract

**核心理由：**

**1. 语义正确性**
> sessionId 是 execution context，不是 engine capability parameter

**2. 长期 API 质量**
> 避免 executeWithSession / executeWithUser / ... API proliferation

**3. Future-proof**
> 未来 userId, traceId 自然扩展，无需修改 contract

**4. 适配 Future Agent API**
> agent.session(id).user(...).call() 自然映射到 execute(..., context)

**5. Capability vs Context 清晰**
> Session 是所有 engine 都应该接收的 context，不是 opt-in capability

---

### 7.2 具体设计

#### 7.2.1 arctra-core

```java
package cn.bitcss.arctra.runtime;

/**
 * Agent execution context.
 *
 * <p>Represents the execution-level environment for an agent invocation,
 * independent of the user input (AgentRequest) and agent template (AgentDefinition).
 *
 * <p>Currently contains session identity for multi-turn conversation continuity.
 * Future extensions may include userId, tenantId, traceId, etc.
 *
 * @param sessionId optional session identifier for conversation continuity.
 *                  null indicates stateless execution.
 * @author lov3r
 */
public record AgentExecutionContext(String sessionId) {

    /**
     * Create a stateless execution context (no session).
     */
    public static AgentExecutionContext stateless() {
        return new AgentExecutionContext(null);
    }

    /**
     * Create an execution context with session.
     */
    public static AgentExecutionContext withSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        return new AgentExecutionContext(sessionId);
    }
}
```

```java
package cn.bitcss.arctra.runtime;

/**
 * Agent execution engine contract.
 *
 * <p>Updated in M2 to support execution context.
 *
 * @author lov3r
 */
public interface AgentExecutionEngine {

    /**
     * Execute an agent with execution context.
     *
     * <p>This is the canonical execution method. Engines should implement this method
     * and handle the execution context appropriately.
     *
     * @param definition the agent definition
     * @param request the user request
     * @param context execution context (session, user, trace, etc.)
     * @return the execution result
     */
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );

    /**
     * Execute an agent without execution context (stateless).
     *
     * <p>Convenience method for stateless execution. Delegates to
     * {@link #execute(AgentDefinition, AgentRequest, AgentExecutionContext)}
     * with a stateless context.
     *
     * @param definition the agent definition
     * @param request the user request
     * @return the execution result
     */
    default AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return execute(definition, request, AgentExecutionContext.stateless());
    }
}
```

---

#### 7.2.2 arctra-runtime-react

```java
package cn.bitcss.arctra.runtime.react;

public class SpringAiToolCallingEngine implements AgentExecutionEngine {

    private final ChatModel chatModel;
    private final List<ToolCallback> tools;
    private final ChatMemory chatMemory;  // NEW in M2

    public SpringAiToolCallingEngine(
        ChatModel chatModel,
        List<ToolCallback> tools,
        ChatMemory chatMemory
    ) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.chatMemory = chatMemory;
    }

    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    ) {
        // Wrap tools with evidence capture
        List<Evidence> evidences = new ArrayList<>();
        var wrappedTools = tools.stream()
            .map(tool -> new EvidenceCapturingToolCallback(tool, evidences))
            .toList();

        // Build advisors
        List<Advisor> advisors = new ArrayList<>();
        
        String sessionId = context.sessionId();
        if (sessionId != null) {
            // Add memory advisor when session is present
            advisors.add(
                MessageChatMemoryAdvisor.builder(chatMemory).build()
            );
        }

        // Build ChatClient
        ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(advisors.toArray(new Advisor[0]))
            .build();

        // Build prompt
        var promptSpec = chatClient.prompt()
            .system(buildSystemInstruction(definition))
            .user(request.userMessage())
            .tools(wrappedTools.toArray(new ToolCallback[0]));

        // Pass session to advisor if present
        if (sessionId != null) {
            promptSpec = promptSpec.advisors(spec ->
                spec.param("conversationId", sessionId)  // Adapt to Spring AI
            );
        }

        // Execute
        String content = promptSpec.call().content();

        return new AgentResult(content, evidences);
    }

    private String buildSystemInstruction(AgentDefinition definition) {
        // Same as M1
        var name = definition.name();
        var description = definition.description();

        if (description == null || description.isBlank()) {
            return String.format("You are %s.", name);
        } else {
            return String.format("You are %s. %s", name, description);
        }
    }
}
```

---

### 7.3 回答 5 个关键问题

#### Q1: sessionId 是 SpringAiToolCallingEngine capability parameter，还是 Agent execution context？

**A:** **Agent execution context**

**理由：**
- sessionId 表达的是"这次 execution 的连续性标识"
- 不是"SpringAiToolCallingEngine 的能力"
- 是 platform-level semantic，不是 framework-specific

---

#### Q2: executeWithSession() 是长期合理 API，还是 M2 plumbing API？

**A:** **M2 plumbing API**（如果选择 Option D 的话）

**理由：**
- executeWithSession / executeWithUser / ... 会导致 API proliferation
- 长期应该是统一的 execute(..., context)
- 即使 M2 采用 Option D，也应该是临时方案

---

#### Q3: AgentExecutionContext 是否已经达到创建门槛？

**A:** ✅ **是**

**理由：**
- sessionId 有独立的 execution-level 语义
- sessionId 不属于 AgentRequest / AgentDefinition
- 未来明确会有 userId, traceId（不是猜测）
- 判断标准不是字段数量，而是语义独立性

---

#### Q4: Canonical AgentExecutionEngine contract 应该是什么？

**A:**
```java
AgentResult execute(
    AgentDefinition definition,
    AgentRequest request,
    AgentExecutionContext context
);
```

**理由：**
- 3-parameter 是 canonical
- 2-parameter 是 convenience（通过 default method）
- 所有新 engine 应实现 3-parameter

---

#### Q5: 未来 agent.session(...).user(...).call() 如何映射到底层 execution？

**A:**
```java
// Agent API (M3)
agent.session("incident-123").user("...").call()

// Maps to:
AgentExecutionContext context = AgentExecutionContext.withSession("incident-123");
engine.execute(definition, request, context);

// SpringAiToolCallingEngine adapts:
context.sessionId() → Spring AI conversationId → MessageChatMemoryAdvisor
```

**理由：**
- 自然、直接的映射
- 无需 instanceof 判断
- 所有 engine 统一

---

## Part 8: 对 Option D 的最终评估

### 8.1 Option D 不是完全错误

**Option D 在以下情况下是合理的：**
1. 项目非常早期，Engine contract 高度不稳定
2. 只有一个 Engine，且短期不会有第二个
3. 明确这是临时 plumbing API，M3 会重构

### 8.2 但 Arctra 不符合这些条件

**Arctra 当前状态：**
1. ✅ M1 已经验证 Engine contract 基本稳定
2. ✅ CLAUDE.md 明确：Engine 是 public extension contract
3. ✅ 项目定位：支持多种 execution engine（AgentScope, Spring AI Alibaba）
4. ✅ M2 正是发现新 semantic（execution context）的时机

**结论：**
> Option D 适合原型阶段，不适合 Arctra 当前阶段

---

## Part 9: Migration Impact 最终评估

### 9.1 Option C 的 Breaking Change 是可接受的

**原因 1: 项目早期**
- 当前没有外部 Engine 实现者
- M1 只有 SpringAiToolCallingEngine
- Breaking change 只影响项目内部

**原因 2: Default method 保护用户**
- M1 用户代码完全不受影响
- Engine 实现者需要更新，但工作量小

**原因 3: 现在修比以后修便宜**
- M3 有 Agent API 后再改，成本更高
- M5 有多个外部 Engine 后再改，几乎不可能

**原因 4: CLAUDE.md 允许演进**
> "如果新的 semantic 证明原 contract 不完整：允许演进 contract。"

---

### 9.2 "零 Breaking Change" 不是最高优先级

**优先级顺序（CLAUDE.md）：**
```
架构宪法
> ACCEPTED ADR
> V1 Scope
> Task Acceptance Criteria
> 局部实现便利  // ← "零 breaking change" 在这里
```

**如果为了零 breaking change，创建长期更差的 API：**
> "这是错误的兼容性优化"（你的原话）

---

## Part 10: 最终决策

### 10.1 推荐方案

✅ **Option C: AgentExecutionContext**

**具体：**
1. 创建 `AgentExecutionContext(String sessionId)`
2. 修改 `AgentExecutionEngine` contract（添加 3-param method）
3. 保留 2-param method 作为 default（M1 兼容）
4. 所有 engine 实现 3-param method

---

### 10.2 不推荐 Option D 的原因

**短期优势：**
- ✅ 零 breaking change
- ✅ 实现简单

**长期劣势：**
- ❌ API proliferation（executeWith...）
- ❌ 语义混淆（session 看起来像 capability）
- ❌ Future Agent API 映射复杂
- ❌ 不支持未来多 engine
- ❌ 临时 plumbing API 变成长期 public API

---

### 10.3 关键论据总结

**1. 语义论据：**
> sessionId 是 execution context，不是 capability parameter

**2. 架构论据：**
> Arctra owns session semantics, Spring AI provides implementation

**3. 演进论据：**
> 未来 userId/traceId 必然出现，ExecutionContext 是正确抽象

**4. API 质量论据：**
> execute(..., context) 优于 executeWithSession / executeWithUser / ...

**5. 时机论据：**
> M2 是引入 ExecutionContext 的正确时机（早期，成本低）

---

## Part 11: ADR 建议

**ADR-003: Agent Execution Context**

**Status:** Proposed

**Context:**  
M2 需要支持 multi-turn conversation，sessionId 需要从用户 API 传递到 Engine。

**Decision:**  
引入 `AgentExecutionContext` 作为 execution-level 上下文，修改 `AgentExecutionEngine` contract 添加 3-parameter 方法。

**Rationale:**
1. sessionId 是 execution context，不属于 AgentRequest / AgentDefinition
2. 避免 API proliferation（executeWithSession / executeWithUser / ...）
3. 支持未来扩展（userId, traceId）
4. 清晰的职责分层（Arctra semantics vs Spring AI implementation）

**Consequences:**
- ✅ M1 用户代码不受影响（default method）
- ⚠️ Engine 实现者需要添加 3-param method
- ✅ 长期 API 质量更好
- ✅ 支持未来 Agent API

**Alternatives:**
- Option D (executeWithSession): 短期简单，长期 API proliferation
- Option A/B (sessionId in Request/Definition): 语义错误

---

## Part 12: 下一步

### 12.1 如果批准 Option C

**M2-T2 Implementation:**
1. 创建 `AgentExecutionContext`
2. 修改 `AgentExecutionEngine` interface
3. 更新 `SpringAiToolCallingEngine`
4. 更新 `DefaultAgentRuntime`（如果需要）
5. 创建 integration tests
6. 更新文档

---

### 12.2 如果不批准

**需要明确：**
1. 拒绝 Option C 的具体理由？
2. 是否有 Option E（其他方案）？
3. 是否接受 Option D 的长期风险？

---

**End of M2-T2 Contract Gate V2**
