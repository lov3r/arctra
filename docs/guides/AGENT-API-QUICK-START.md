# Agent API Quick Start

**Version:** M3  
**Last Updated:** 2026-08-18

---

## Overview

Arctra Agent API 提供 stateless invocation handle，用于执行绑定到 AgentDefinition 的 agent。

**Key Characteristics:**
- **Stateless:** Agent 不拥有 conversation, session, 或 process state
- **Reusable:** Agent handle 可以安全地多次调用
- **Protocol:** Agent 定义调用契约，不是实现
- **Bound:** Agent 绑定到特定的 AgentDefinition

---

## Core Concepts

### 1. Agent

**Agent 是什么：**  
Stateless invocation handle for executing a bound AgentDefinition.

**Agent 不是：**
- ❌ Entity with lifecycle
- ❌ Configuration object
- ❌ Session/conversation owner
- ❌ Process abstraction

```java
public interface Agent {
    AgentResult execute(AgentRequest request);
    AgentResult execute(AgentRequest request, AgentExecutionContext context);
}
```

---

### 2. AgentDefinition

Agent 的定义/模板，包含 name 和 description。

```java
AgentDefinition definition = new AgentDefinition(
    "Incident Investigator",
    "You are an expert at analyzing production incidents."
);
```

---

### 3. AgentRequest

User input for agent execution.

```java
AgentRequest request = new AgentRequest(
    "Analyze production 500 errors"
);
```

---

### 4. AgentExecutionContext

Execution-level context (session, etc.).

```java
// Stateless
AgentExecutionContext.stateless()

// Stateful (with session)
AgentExecutionContext.withSession("session-123")
```

---

### 5. AgentRuntime

Creates Agent handles and manages execution.

```java
AgentRuntime runtime = new DefaultAgentRuntime(engine);
Agent agent = runtime.agent(definition);
```

---

## Quick Start

### Step 1: Composition Root (Setup)

```java
// 1. Create ChatModel
ChatModel chatModel = OpenAiChatModel.builder()
    .options(OpenAiChatOptions.builder()
        .apiKey("your-api-key")
        .model("gpt-4")
        .build())
    .build();

// 2. Create Tools
List<ToolCallback> tools = List.of(
    new QueryLogsTool(),
    new GetDeploymentTool()
);

// 3. Create ChatMemory
ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .maxMessages(20)
    .build();

// 4. Create Engine
AgentExecutionEngine engine = new SpringAiToolCallingEngine(
    chatModel,
    tools,
    chatMemory
);

// 5. Create Runtime
AgentRuntime runtime = new DefaultAgentRuntime(engine);

// 6. Create Agent handle (bound to definition)
AgentDefinition definition = new AgentDefinition(
    "Incident Investigator",
    "You are an expert at analyzing production incidents. " +
    "Use the available tools to investigate issues."
);

Agent incidentAgent = runtime.agent(definition);
```

---

### Step 2: Business Code (Invocation)

**Stateless Invocation:**

```java
AgentResult result = incidentAgent.execute(
    new AgentRequest("Analyze production 500 errors")
);

System.out.println(result.content());
System.out.println("Evidences: " + result.evidences().size());
```

**Stateful Invocation (Multi-Turn):**

```java
var context = AgentExecutionContext.withSession("incident-123");

// Turn 1
AgentResult result1 = incidentAgent.execute(
    new AgentRequest("生产环境从 16:20 开始出现大量 500 错误"),
    context
);

// Turn 2 (continues conversation)
AgentResult result2 = incidentAgent.execute(
    new AgentRequest("那这个问题最可能是什么原因？"),
    context
);
```

---

## State Ownership

**Important:** Agent handle 是 stateless 的。

| State Type | Owner | Lifecycle |
|------------|-------|-----------|
| **Conversation state** | AgentRuntime / ChatMemory | Session |
| **Execution state** | AgentResult | Per-execution |
| **Agent handle** | None | Stateless |

**Key Points:**
- Agent 不拥有 conversation history
- Conversation state 由 `AgentExecutionContext.sessionId` + `ChatMemory` 管理
- 同一个 Agent handle 可以处理多个 sessions

---

## Agent Handle Reuse

Agent handle 是 reusable 和 stateless 的：

```java
Agent agent = runtime.agent(definition);

// Reuse same handle
agent.execute(request1);
agent.execute(request2, contextA);
agent.execute(request3, contextB);
agent.execute(request4);
```

**Safe for:**
- Multiple invocations
- Different sessions
- Mixed stateless/stateful calls

---

## Session Isolation

Different sessions are completely isolated:

```java
var contextA = AgentExecutionContext.withSession("session-A");
var contextB = AgentExecutionContext.withSession("session-B");

// Session A
agent.execute(requestA1, contextA);

// Session B (independent)
agent.execute(requestB1, contextB);

// Session A again (continues A's conversation)
agent.execute(requestA2, contextA);
```

**Guarantee:**
- Session A and Session B do not share conversation history
- Agent handle does not "remember" which session was used last

---

## Configuration vs Invocation

**M3 separates two concerns:**

### Configuration (Composition Root)
- Create ChatModel, Tools, ChatMemory
- Create Engine, Runtime
- Create Agent handles
- Usually done once at application startup

### Invocation (Business Code)
- Call `agent.execute(...)`
- Pass request and optional context
- Receive result

**Key Principle:**  
Business code should NOT see ChatModel, Tools, ChatMemory, or Engine.

---

## Recommended vs Low-Level API

### Recommended (User-Facing)

```java
Agent agent = runtime.agent(definition);
agent.execute(request, context);
```

**Benefits:**
- Hides Engine construction
- Hides Spring AI details
- Clean invocation API

### Low-Level (Advanced/Extension)

```java
runtime.execute(definition, request, context);
```

**Use Cases:**
- One-off executions
- Dynamic agent definitions
- Testing/debugging

**Recommendation:**  
Prefer Agent API for business code.

---

## Current Limitations

### What M3 Provides

✅ Agent invocation handle (stateless, reusable)  
✅ Multi-turn conversation (via AgentExecutionContext + ChatMemory)  
✅ Session isolation  
✅ Evidence capture  
✅ Framework-neutral API (no Spring deps in arctra-core)

### What M3 Does NOT Provide

❌ Agent Registry (no `runtime.agent("name")`)  
❌ Model/Tool selection/registry  
❌ Multi-step Process/Workflow  
❌ Goal Planning  
❌ HITL / Checkpoint / Resume  
❌ Sub-Agent hierarchy  
❌ Persistent session (in-memory only)  
❌ Streaming API  

**Future:** These capabilities may be added in M4+.

---

## Future Process Compatibility

**Today (M3):**
```
Agent.execute()
    ↓
AgentRuntime
    ↓
AgentExecutionEngine
```

**Future (M4+ if needed):**
```
Agent.execute()
    ↓
AgentRuntime
    ↓
[Process Runtime] (for complex scenarios)
    ↓
AgentExecutionEngine(s)
```

**Key:** User API (`agent.execute()`) remains unchanged when Process is added.

---

## FAQ

### Q: Agent 拥有 session state 吗？

**A:** ❌ NO. Agent 是 stateless handle。

Conversation state 由 `ChatMemory` 拥有，通过 `AgentExecutionContext.sessionId` 识别。

---

### Q: 可以用 `runtime.agent("name")` 吗？

**A:** ❌ NO (M3). 当前没有 AgentRegistry。

必须使用 `runtime.agent(AgentDefinition)`。

**Future:** 当 AgentRegistry 实现后，可能支持。

---

### Q: Agent 和 Process 的关系？

**A:** Agent ≠ Process。

- **Agent** = stateless invocation handle
- **Process** (future) = lifecycle / state / checkpoint abstraction

Future: Agent API 可能会路由到 Process，但 Agent 本身不实现 Process。

---

### Q: 如何使用 Spring Boot？

**A:** M3 不提供 Spring Boot starter。

可以使用 `@Configuration` / `@Bean` 手动配置：

```java
@Configuration
public class AgentConfiguration {
    
    @Bean
    public Agent incidentAgent(
        ChatModel chatModel,
        List<ToolCallback> tools
    ) {
        var chatMemory = MessageWindowChatMemory.builder().build();
        var engine = new SpringAiToolCallingEngine(
            chatModel, tools, chatMemory
        );
        var runtime = new DefaultAgentRuntime(engine);
        
        return runtime.agent(
            new AgentDefinition("Incident", "...")
        );
    }
}
```

---

## Example: Incident Investigation

See: `examples/incident-investigator/src/test/java/.../IncidentAgentApiTest.java`

Complete vertical slice demonstrating:
- Agent handle creation
- Stateless invocation
- Multi-turn conversation
- Session isolation
- Agent handle reuse
- Evidence capture

---

## References

- [M3 Phase Planning](../planning/M3-PHASE-PLANNING.md)
- [M3-T1 Agent API Contract Gate](../planning/M3-T1-AGENT-API-CONTRACT-GATE.md)
- [ADR-004: Agent as Invocation Handle](../adr/004-agent-invocation-handle.md)
- [Architecture Guardrails](../architecture/ARCHITECTURE-GUARDRAILS.md)

---

**Agent API Quick Start - M3** ✅
