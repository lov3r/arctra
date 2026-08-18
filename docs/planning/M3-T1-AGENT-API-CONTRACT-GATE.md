# M3-T1: Agent API & Runtime Boundary Contract Gate

**Date:** 2026-08-18  
**Type:** Architecture Contract Gate  
**Status:** DRAFT - Awaiting Approval  
**Dependencies:** M3 Phase Planning APPROVED

---

## Executive Summary

**Purpose:**  
定义 M3 Agent API 的语义、边界和实现策略，确保不引入过早抽象或语义虚假的 API。

**Key Questions:**
1. Agent 在 Arctra 中到底是什么？
2. 是否需要 `Agent` interface？
3. `runtime.agent("name")` 语义是否成立？
4. Configuration vs Invocation 如何分离？
5. Composition Root 在哪里？

**Recommended Decision:**  
**Option B: Minimal Invocation Wrapper** (详见 Section 8)

**Key Principle:**  
API 必须解决真实痛点（隐藏 Engine 构建），而不是追求表面优雅。

---

## Part 1: 重新审视 Agent 语义

### 1.1 What is "Agent" in Arctra?

**Question:** Agent 在 Arctra 中到底是什么？

**Current State:**
```java
record AgentDefinition(String name, String description)
```

**Analysis:**

**Agent 不是 (Currently):**
- ❌ Runtime object with lifecycle
- ❌ Stateful entity with mutable state
- ❌ First-class execution primitive
- ❌ Configuration with model/tool binding

**Agent 是 (Currently):**
- ✅ Definition/Template (name + description)
- ✅ Provided by user code
- ✅ Passed to Engine for execution

---

### 1.2 Should Agent Become a Public Interface?

**Proposed (in M3 Planning):**
```java
interface Agent {
    AgentResult execute(AgentRequest request);
    AgentResult execute(AgentRequest request, AgentExecutionContext context);
}
```

**Critical Analysis:**

**Concerns:**

1. **Semantic Ambiguity**
   - Is `Agent` a handle, entity, or configuration?
   - Does it have lifecycle?
   - Does it own state?

2. **Premature Abstraction**
   - 当前唯一实现: bind AgentDefinition + Engine
   - 是否只是 `engine.execute()` 的改名？

3. **Future Conflict Risk**
   - 未来 `AgentProcess` 概念是否冲突？
   - `Agent.execute()` vs `Process.execute()`?

4. **No Real Consumer for "Agent Object"**
   - 用户痛点: Engine construction 复杂
   - 不是: "没有 Agent object"

---

### 1.3 Verdict: Agent as Interface

**Recommendation:** ⚠️ **Conditional YES**

**Conditions:**
1. `Agent` 必须明确是 **invocation handle** (not entity)
2. 不拥有 lifecycle
3. 不拥有 mutable state
4. 未来 Process 可以实现 `Agent` interface (protocol)

**Semantic Definition:**
> `Agent` = invocation handle representing a configured agent execution capability

**Key Design:**
- Agent is a **protocol** (interface)
- Agent is **disposable** (no lifecycle)
- Agent is **immutable** (no state changes)
- Agent **delegates** to Engine/Runtime

---

## Part 2: runtime.agent("name") 语义审查

### 2.1 Proposed API

```java
runtime.agent("incident-investigator")
```

### 2.2 Semantic Questions

**Q1:** "incident-investigator" 从哪里 resolve？

**A1:** 当前**没有**:
- ❌ AgentRegistry
- ❌ AgentRepository
- ❌ AgentConfiguration file
- ❌ Spring configuration binding
- ❌ Any resolution source

**Q2:** 是否语义虚假？

**A2:** ✅ **YES** - 语义虚假

**Why:**  
`runtime.agent("name")` 暗示 agent resolution，但没有 resolution source。

---

### 2.3 Verdict: runtime.agent("name")

**Recommendation:** ❌ **REJECT for M3**

**Reason:**
- 语义虚假（暗示 registry 但不存在）
- 无真实 resolution source
- 过度承诺未来能力

**Alternative:**
```java
// M3: Explicit AgentDefinition
runtime.agent(AgentDefinition definition)

// Future (M4+ if needed): with registry
runtime.agent("name")  // after AgentRegistry exists
```

---

## Part 3: 现有 AgentRuntime 审查

### 3.1 Current AgentRuntime

```java
interface AgentRuntime {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    );
}

class DefaultAgentRuntime implements AgentRuntime {
    private final AgentExecutionEngine engine;
    
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return engine.execute(definition, request);
    }
}
```

### 3.2 Analysis

**Problems:**

1. **Incomplete Contract**
   - 缺少 3-param execution (context)
   - 与 M2 canonical Engine contract 不一致

2. **Trivial Delegation**
   - 只是 `engine.execute()` wrapper
   - 没有真正的 runtime logic

3. **Naming Too Large**
   - "Runtime" 暗示 lifecycle management
   - 实际只是 Engine facade

---

### 3.3 Verdict: Existing AgentRuntime

**Recommendation:** 🔄 **EVOLVE**

**Action:**  
Evolve AgentRuntime to align with M2 Engine contract + add Agent handle creation

**New Contract:**
```java
interface AgentRuntime {
    // M3: Create agent handle
    Agent agent(AgentDefinition definition);
    
    // Keep direct execution for low-level use
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
    
    // Backward compat
    default AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return execute(definition, request, AgentExecutionContext.stateless());
    }
}
```

---

## Part 4: Real User Pain Points

### 4.1 Current User Code (M2)

```java
// User must handle all construction
var chatModel = ...;  // Spring AI
var tools = List.of(new QueryLogsTool(), new GetDeploymentTool());
var chatMemory = MessageWindowChatMemory.builder().build();

// Construct Engine
var engine = new SpringAiToolCallingEngine(
    chatModel,
    tools,
    chatMemory
);

// Define Agent
var definition = new AgentDefinition(
    "Incident Investigator",
    "You are..."
);

// Execute
var result = engine.execute(definition, new AgentRequest("..."));
```

**Pain Points:**
1. 暴露 Spring AI types (ChatModel, ChatMemory)
2. Engine construction verbose
3. Tools construction manual
4. 无法隐藏 execution strategy

---

### 4.2 What M3 Should Hide

**Must Hide:**
- ✅ SpringAiToolCallingEngine construction
- ✅ ChatModel wiring
- ✅ Tools wiring
- ✅ ChatMemory configuration

**Must NOT Hide (Yet):**
- ❌ Agent configuration DSL (no consumer)
- ❌ Agent registry (no source)
- ❌ Model selection (no multiple models)
- ❌ Tool governance (no policy)

---

## Part 5: Configuration vs Invocation Separation

### 5.1 Two Distinct Problems

**Problem A: Configuration**  
"How to define/configure an Agent?"

**Problem B: Invocation**  
"How to invoke a configured Agent?"

---

### 5.2 M3 Scope Decision

**Recommendation:**  
M3 只解决 **Invocation API**，不解决完整 Configuration API。

**Why:**
- Configuration API 需要 registry/DSL/binding (no consumer)
- Invocation API 有真实痛点 (Engine construction)
- 分离关注点更诚实

**Implication:**
```java
// Configuration: still low-level (M3)
Agent agent = ... // composition root

// Invocation: high-level (M3)
agent.execute(request, context);
```

---

## Part 6: Composition Root

### 6.1 Question

**Where does Agent construction happen?**

### 6.2 Proposed Composition Root

**Application Setup (Composition Root):**

```java
@Configuration
class AgentConfiguration {
    
    @Bean
    public AgentRuntime agentRuntime(
        ChatModel chatModel,
        List<ToolCallback> tools,
        ChatMemory chatMemory
    ) {
        var engine = new SpringAiToolCallingEngine(
            chatModel,
            tools,
            chatMemory
        );
        
        return new DefaultAgentRuntime(engine);
    }
    
    @Bean
    public Agent incidentAgent(AgentRuntime runtime) {
        var definition = new AgentDefinition(
            "Incident Investigator",
            "You are an expert..."
        );
        
        return runtime.agent(definition);
    }
}
```

**Business Code (User):**

```java
@Service
class IncidentService {
    
    @Autowired
    private Agent incidentAgent;
    
    public void investigate(String message) {
        var result = incidentAgent.execute(
            new AgentRequest(message)
        );
        // ...
    }
}
```

**Key:**
- Configuration = Spring wiring (composition root)
- Invocation = business code (user)
- Separation of concerns

---

## Part 7: Candidate APIs

### Option A: Runtime-Centric

**Setup:**
```java
@Bean
AgentRuntime runtime(ChatModel model, List<ToolCallback> tools) {
    var engine = new SpringAiToolCallingEngine(model, tools, chatMemory);
    return new DefaultAgentRuntime(engine);
}
```

**Invocation:**
```java
runtime.execute(
    new AgentDefinition("name", "desc"),
    new AgentRequest("message"),
    AgentExecutionContext.withSession("session-123")
);
```

**Pros:**
- ✅ Simple
- ✅ No new public types

**Cons:**
- ❌ Still exposes AgentDefinition in business code
- ❌ Not hiding Engine complexity

---

### Option B: Minimal Invocation Wrapper ⭐ RECOMMENDED

**Setup (Composition Root):**
```java
@Bean
AgentRuntime runtime(ChatModel model, List<ToolCallback> tools) {
    var engine = new SpringAiToolCallingEngine(model, tools, chatMemory);
    return new DefaultAgentRuntime(engine);
}

@Bean
Agent incidentAgent(AgentRuntime runtime) {
    return runtime.agent(
        new AgentDefinition("Incident Investigator", "You are...")
    );
}
```

**Invocation (Business Code):**
```java
@Autowired
Agent incidentAgent;

// Stateless
agent.execute(new AgentRequest("message"));

// Stateful
agent.execute(
    new AgentRequest("message"),
    AgentExecutionContext.withSession("id")
);
```

**Pros:**
- ✅ Hides AgentDefinition from business code
- ✅ Hides Engine construction
- ✅ Simple invocation API
- ✅ Agent is Spring Bean (DI-friendly)
- ✅ No registry needed
- ✅ Framework-neutral (Agent interface in arctra-core)

**Cons:**
- ⚠️ Requires Spring configuration (but that's composition root)

---

### Option C: Bound Invocation Object

**Setup:**
```java
@Bean
AgentInvoker incidentInvoker(AgentRuntime runtime) {
    return runtime.bind(
        new AgentDefinition("Incident Investigator", "..."),
        sessionId -> AgentExecutionContext.withSession(sessionId)
    );
}
```

**Invocation:**
```java
invoker.invoke("message", "session-id");
```

**Pros:**
- ✅ Very simple invocation

**Cons:**
- ❌ Non-standard naming (invoke?)
- ❌ Couples sessionId into invocation signature
- ❌ Less flexible than AgentExecutionContext

---

### Option D: Keep Current (Minimal Evolution)

**Setup:**
```java
@Bean
AgentRuntime runtime(...) {
    // same as current
}
```

**Invocation:**
```java
runtime.execute(definition, request, context);
```

**Pros:**
- ✅ No breaking changes
- ✅ Minimal new API

**Cons:**
- ❌ Doesn't solve user pain point
- ❌ Still exposes AgentDefinition in business code

---

## Part 8: Recommended API (Option B)

### 8.1 New Public API

**arctra-core (framework-neutral):**

```java
/**
 * Agent invocation handle.
 * 
 * <p>Agent is an immutable, stateless invocation handle representing
 * a configured agent execution capability. It does not own lifecycle
 * or mutable state.
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * // Stateless
 * agent.execute(new AgentRequest("message"));
 * 
 * // Stateful
 * agent.execute(
 *     new AgentRequest("message"),
 *     AgentExecutionContext.withSession("session-id")
 * );
 * }</pre>
 * 
 * @see AgentRuntime
 */
public interface Agent {
    
    /**
     * Execute agent with stateless context.
     * 
     * @param request user request
     * @return execution result
     */
    AgentResult execute(AgentRequest request);
    
    /**
     * Execute agent with execution context.
     * 
     * @param request user request
     * @param context execution context (session, etc.)
     * @return execution result
     */
    AgentResult execute(AgentRequest request, AgentExecutionContext context);
}
```

```java
/**
 * Agent runtime for creating and managing agent execution.
 * 
 * <p>AgentRuntime is responsible for creating Agent handles and
 * providing direct execution capability.
 * 
 * <h2>Creating Agent Handles</h2>
 * <pre>{@code
 * Agent agent = runtime.agent(
 *     new AgentDefinition("name", "description")
 * );
 * }</pre>
 * 
 * <h2>Direct Execution</h2>
 * For advanced use cases, direct execution is available:
 * <pre>{@code
 * runtime.execute(definition, request, context);
 * }</pre>
 */
public interface AgentRuntime {
    
    /**
     * Create an agent invocation handle.
     * 
     * <p>The returned Agent is immutable and stateless. Multiple calls
     * with the same definition may return different instances, but they
     * behave identically.
     * 
     * @param definition agent definition (name, description)
     * @return agent invocation handle
     */
    Agent agent(AgentDefinition definition);
    
    /**
     * Execute agent directly (low-level API).
     * 
     * <p>For most use cases, prefer creating an Agent handle via
     * {@link #agent(AgentDefinition)} and invoking through the handle.
     * 
     * @param definition agent definition
     * @param request user request
     * @param context execution context
     * @return execution result
     */
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
    
    /**
     * Execute agent with stateless context (convenience).
     */
    default AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return execute(definition, request, AgentExecutionContext.stateless());
    }
}
```

**arctra-runtime-react:**

```java
/**
 * Default AgentRuntime implementation.
 * 
 * <p>Delegates execution to the configured AgentExecutionEngine.
 */
public class DefaultAgentRuntime implements AgentRuntime {
    
    private final AgentExecutionEngine engine;
    
    public DefaultAgentRuntime(AgentExecutionEngine engine) {
        this.engine = Objects.requireNonNull(engine);
    }
    
    @Override
    public Agent agent(AgentDefinition definition) {
        return new DefaultAgent(definition, engine);
    }
    
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    ) {
        return engine.execute(definition, request, context);
    }
    
    /**
     * Default Agent implementation (package-private).
     */
    private static class DefaultAgent implements Agent {
        
        private final AgentDefinition definition;
        private final AgentExecutionEngine engine;
        
        DefaultAgent(AgentDefinition definition, AgentExecutionEngine engine) {
            this.definition = definition;
            this.engine = engine;
        }
        
        @Override
        public AgentResult execute(AgentRequest request) {
            return engine.execute(
                definition,
                request,
                AgentExecutionContext.stateless()
            );
        }
        
        @Override
        public AgentResult execute(AgentRequest request, AgentExecutionContext context) {
            return engine.execute(definition, request, context);
        }
    }
}
```

---

### 8.2 Usage Example (Complete)

**Application Configuration (Composition Root):**

```java
@Configuration
public class AgentConfiguration {
    
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();
    }
    
    @Bean
    public List<ToolCallback> incidentTools() {
        return List.of(
            new QueryLogsTool(),
            new GetDeploymentTool()
        );
    }
    
    @Bean
    public AgentExecutionEngine executionEngine(
        ChatModel chatModel,
        @Qualifier("incidentTools") List<ToolCallback> tools,
        ChatMemory chatMemory
    ) {
        return new SpringAiToolCallingEngine(
            chatModel,
            tools,
            chatMemory
        );
    }
    
    @Bean
    public AgentRuntime agentRuntime(AgentExecutionEngine engine) {
        return new DefaultAgentRuntime(engine);
    }
    
    @Bean
    public Agent incidentAgent(AgentRuntime runtime) {
        return runtime.agent(
            new AgentDefinition(
                "Incident Investigator",
                "You are an expert at analyzing production incidents. " +
                "When investigating, always use the available tools to gather information."
            )
        );
    }
}
```

**Business Code (User):**

```java
@Service
public class IncidentService {
    
    private final Agent incidentAgent;
    
    @Autowired
    public IncidentService(Agent incidentAgent) {
        this.incidentAgent = incidentAgent;
    }
    
    public IncidentReport investigate(String incidentId, String description) {
        // Stateful execution (multi-turn capable)
        var result = incidentAgent.execute(
            new AgentRequest(description),
            AgentExecutionContext.withSession(incidentId)
        );
        
        return buildReport(result);
    }
    
    public QuickAnalysis quickAnalyze(String message) {
        // Stateless execution (simple)
        var result = incidentAgent.execute(
            new AgentRequest(message)
        );
        
        return buildAnalysis(result);
    }
}
```

**Test Code:**

```java
@Test
void testAgent() {
    // Arrange
    var engine = new SpringAiToolCallingEngine(...);
    var runtime = new DefaultAgentRuntime(engine);
    var agent = runtime.agent(
        new AgentDefinition("test", "desc")
    );
    
    // Act
    var result = agent.execute(new AgentRequest("test"));
    
    // Assert
    assertThat(result.content()).isNotBlank();
}
```

---

### 8.3 Key Characteristics

**Agent Interface:**
- ✅ Protocol, not entity
- ✅ Immutable
- ✅ Stateless
- ✅ Disposable (no lifecycle)
- ✅ Framework-neutral (arctra-core)

**AgentRuntime:**
- ✅ Minimal responsibilities (create handle, delegate execution)
- ✅ No registry (yet)
- ✅ No model/tool selection (yet)
- ✅ Simple orchestration

**Composition Root:**
- ✅ Spring configuration
- ✅ Explicit Engine construction
- ✅ Agent beans for DI

**Business Code:**
- ✅ No Spring AI types
- ✅ No Engine construction
- ✅ No AgentDefinition construction
- ✅ Simple invocation

---

## Part 9: Future Process Compatibility

### 9.1 Today (M3)

```
Agent.execute()
    ↓
Runtime
    ↓
Engine
    ↓
Result
```

### 9.2 Future (M4+ if needed)

```
Agent.execute()
    ↓
Runtime
    ↓
[Process] (if complex scenario)
    ↓
Engine(s)
    ↓
ProcessResult / AgentResult
```

### 9.3 Compatibility Analysis

**Question:** 用户 API 是否需要变化？

**Answer:** ✅ NO

```java
// Same API works
agent.execute(request, context);
```

**Why:**
- Agent is protocol (interface)
- Runtime decides: direct Engine vs Process
- User sees same API

**Future Advanced API (optional):**
```java
// For advanced users who need Process control
AgentProcess process = agent.startAsync(request, context);
process.awaitCheckpoint();
// ...
```

But basic `execute()` remains unchanged.

---

## Part 10: Public API Delta

### 10.1 New Public Types (arctra-core)

**1. `Agent` interface**
- Purpose: Invocation handle protocol
- Visibility: PUBLIC
- Reason: User-facing API

**2. `AgentRuntime` evolved**
- Purpose: Runtime orchestration + Agent handle creation
- Visibility: PUBLIC (already exists, evolved)
- Reason: User-facing API

**Total New Public Types: 1** (`Agent`)

**Evolved Types: 1** (`AgentRuntime`)

---

### 10.2 New Internal Types (arctra-runtime-react)

**1. `DefaultAgent` (package-private)**
- Purpose: Default Agent implementation
- Visibility: PACKAGE-PRIVATE
- Reason: Implementation detail

**Total Internal Types: 1**

---

### 10.3 Public API Budget: ✅ PASS

M3 新增 public types: **1** (Agent)

Within budget (target: ≤ 2)

---

## Part 11: Revised M3 Task List

### M3-T1: Agent API Contract Gate ✅ (This Document)

**Status:** DRAFT Complete

---

### M3-T2: Agent API Implementation

**Goal:** 实现 Agent interface + Runtime evolution

**Scope:**
- Implement `Agent` interface (arctra-core)
- Implement `DefaultAgent` (arctra-runtime-react, package-private)
- Evolve `AgentRuntime` interface
- Evolve `DefaultAgentRuntime` implementation
- Unit tests

**Deliverables:**
- `Agent.java` (PUBLIC interface)
- `DefaultAgent.java` (package-private)
- Updated `AgentRuntime.java`
- Updated `DefaultAgentRuntime.java`
- Tests

**Non-Goals:**
- ❌ No Agent Registry
- ❌ No Model/Tool Registry
- ❌ No Streaming
- ❌ No Process

**Estimated Effort:** 3-4 days

---

### M3-T3: Incident Example Migration + Vertical Slice

**Goal:** Migrate incident-investigator to M3 API

**Scope:**
- Create AgentConfiguration (composition root)
- Update IncidentAgentRealE2ETest to use Agent API
- Keep old Engine-based test for backward compat demo
- Update example README

**Deliverables:**
- `AgentConfiguration.java` (composition root)
- Updated tests
- Updated README

**Estimated Effort:** 2-3 days

---

### M3-T4: Documentation + M3 Phase Closure

**Goal:** 完善文档，关闭 M3

**Scope:**
- Agent API Quick Start Guide
- Update CURRENT-STATE.md
- Update TASKS.md
- M3 Implementation Report
- M3 Phase Closure

**Deliverables:**
- Agent API docs
- M3 closure report

**Estimated Effort:** 2-3 days

---

### ❌ Removed: Persistent Session

**Reason:**  
- 不属于 M3 core theme (Agent API & Runtime Boundary)
- 属于 independent architecture axis
- Defer to M4 backlog

---

## Part 12: ADR Decision

### ADR-004: Agent as Invocation Handle Protocol

**Status:** Proposed (subject to M3-T1 approval)

**Decision:**

> `Agent` 是 invocation handle protocol，不是 entity 或 configuration。
>
> - Agent = immutable, stateless handle
> - Agent = protocol (interface) for invocation
> - Agent 不拥有 lifecycle
> - Agent 不拥有 mutable state
> - Future: Process 可以实现 Agent protocol

**Rationale:**
- 解决真实痛点 (隐藏 Engine construction)
- 不引入 entity lifecycle complexity
- 为 future Process 预留空间
- 保持 framework-neutral

**Alternatives Rejected:**
- Agent as entity: 引入 lifecycle complexity
- Agent as configuration: 混淆 configuration vs invocation
- No Agent abstraction: 不解决用户痛点

---

## Part 13: Final Recommendations

### 13.1 Agent Semantic

**Answer:**  
Agent = **Invocation Handle Protocol**

**Characteristics:**
- Immutable
- Stateless
- Disposable
- Protocol (interface)

---

### 13.2 Runtime Semantic

**Answer:**  
Runtime = **Minimal Orchestration Layer**

**Current Responsibilities (M3):**
- Create Agent handles
- Delegate execution to Engine

**Future Responsibilities (M4+):**
- Agent registry resolution (if needed)
- Model/Tool selection (if needed)
- Process orchestration (if needed)

---

### 13.3 Configuration vs Invocation

**Separation:**
- Configuration: Composition root (Spring config)
- Invocation: Business code (user)

**M3 Scope:**  
Invocation API only

---

### 13.4 Composition Root

**Where:**  
Spring `@Configuration` class

**What:**
- Construct Engine
- Construct Runtime
- Create Agent beans

---

### 13.5 Candidate APIs

**Winner:** Option B (Minimal Invocation Wrapper)

**Why:**
- Solves real pain point
- Minimal public API
- Framework-neutral
- Future-compatible

---

### 13.6 Recommended API

```java
// Composition Root
@Bean
Agent incidentAgent(AgentRuntime runtime) {
    return runtime.agent(new AgentDefinition("name", "desc"));
}

// Business Code
agent.execute(request);
agent.execute(request, context);
```

---

### 13.7 Public API Delta

**New:** 1 public type (`Agent`)  
**Evolved:** 1 public type (`AgentRuntime`)  
**Budget:** ✅ PASS

---

### 13.8 runtime.agent("name") Verdict

**Verdict:** ❌ **REJECT for M3**

**Reason:** 语义虚假 (暗示 registry 但不存在)

**Alternative:** `runtime.agent(AgentDefinition)`

**Future:** `runtime.agent("name")` when registry exists

---

### 13.9 Existing AgentRuntime Verdict

**Verdict:** 🔄 **EVOLVE**

**Action:**
- Add `Agent agent(AgentDefinition)` method
- Add 3-param `execute()` (align with Engine)
- Keep backward compat

---

### 13.10 Future Process Compatibility

**Verdict:** ✅ **COMPATIBLE**

**Reason:**
- Agent is protocol
- Runtime can delegate to Process (future)
- User API unchanged

---

### 13.11 Revised M3 Tasks

**T1:** Contract Gate (✅ This Document)  
**T2:** Agent API Implementation (3-4 days)  
**T3:** Example Migration (2-3 days)  
**T4:** Documentation + Closure (2-3 days)

**Removed:** Persistent Session (defer to M4)

---

### 13.12 ADR Decision

**ADR-004:** Agent as Invocation Handle Protocol

**Status:** Proposed (awaiting approval)

---

## Part 14: Approval Gate

**Seeking Approval For:**

✅ **Agent Semantic:** Invocation Handle Protocol  
✅ **Agent Interface:** PUBLIC (arctra-core)  
✅ **Runtime Evolution:** Add `agent(AgentDefinition)`  
✅ **Recommended API:** Option B (Minimal Invocation Wrapper)  
✅ **Composition Root:** Spring configuration  
✅ **Reject:** `runtime.agent("name")` (no registry)  
✅ **Public API Budget:** 1 new type (Agent)  
✅ **M3 Tasks:** T2 Implementation, T3 Migration, T4 Docs  
✅ **Removed:** Persistent Session from M3

---

**M3-T1 Contract Gate Complete.**  
**Awaiting Your Approval to Proceed to M3-T2.**
