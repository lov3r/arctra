# ADR-004: Agent as Invocation Handle Protocol

**Status:** Accepted  
**Date:** 2026-08-18  
**Deciders:** lov3r  
**Context Source:** M3-T1 Agent API Contract Gate

---

## Context

M1/M2 建立了基础执行能力（Tool Calling + Multi-Turn），但用户必须直接操作 Engine 构建细节（ChatModel, Tools, ChatMemory）。

**触发因素：**
- 用户痛点：Engine construction 过于底层
- 需要稳定的 user-facing Agent API
- 必须隐藏 Spring AI implementation details

**不明确的后果：**
- 没有清晰的 invocation boundary
- 无法隐藏不同 execution strategies
- 未来 Process 插入困难

---

## Decision

**We decide that:**

> `Agent` 是 stateless invocation handle protocol，不是 entity 或 configuration。

**具体定义：**

1. **Agent as Protocol (Interface):**
   ```java
   public interface Agent {
       AgentResult execute(AgentRequest request);
       AgentResult execute(AgentRequest request, AgentExecutionContext context);
   }
   ```

2. **Agent Characteristics:**
   - Stateless (does not own mutable state)
   - Reusable (can be invoked multiple times)
   - Protocol (defines contract, not implementation)
   - Bound (tied to specific AgentDefinition)

3. **Agent NOT:**
   - ❌ Entity with lifecycle
   - ❌ Configuration object
   - ❌ Session/conversation owner
   - ❌ Process abstraction

4. **State Ownership:**
   - Conversation state → AgentRuntime / ChatMemory
   - Execution state → per-execution (AgentResult)
   - Agent handle → stateless

5. **Creation via AgentRuntime:**
   ```java
   AgentRuntime runtime = new DefaultAgentRuntime(engine);
   Agent agent = runtime.agent(new AgentDefinition("name", "desc"));
   ```

---

## Rationale

### Why "Invocation Handle Protocol"?

**Problem Solved:**
- Hides Engine/ChatModel/Tools construction
- Provides framework-neutral API
- Enables future Process insertion

**Characteristics Justification:**

**Stateless:**
- Agent 不拥有 conversation/session/process state
- State 归属于 ChatMemory / ExecutionContext / Process(future)
- Agent 可以安全 reuse 和 concurrent invoke

**Protocol (Interface):**
- Defines invocation contract
- Allows multiple implementations
- Future: different execution strategies

**Not Entity:**
- No lifecycle management
- No creation/destruction ceremony
- No mutable state

---

### Why NOT Agent as Entity?

**Rejected: Agent as Entity with Lifecycle**

```java
// ❌ NOT THIS
class Agent {
    void start();
    void execute(...);
    void stop();
    State getState();
}
```

**Reasons:**
- Introduces lifecycle complexity
- Requires state management
- Conflicts with stateless invocation semantic
- Makes concurrent use dangerous

---

### Why NOT Agent as Configuration?

**Rejected: Agent as Configuration Object**

```java
// ❌ NOT THIS
class AgentConfig {
    String model;
    List<Tool> tools;
    ...
}
```

**Reasons:**
- Mixes configuration vs invocation
- Configuration 是 composition root concern
- Invocation 是 business code concern
- Clear separation needed

---

### Why NOT No Agent Abstraction?

**Rejected: Keep Current (Direct Engine Use)**

```java
// ❌ NOT THIS (current M2)
var engine = new SpringAiToolCallingEngine(chatModel, tools, chatMemory);
engine.execute(definition, request, context);
```

**Reasons:**
- Doesn't solve user pain point
- Exposes Spring AI details
- Cannot hide execution strategies
- No future Process insertion point

---

### Why NOT runtime.agent("name")?

**Rejected: Agent Resolution by Name**

```java
// ❌ NOT THIS (M3)
runtime.agent("incident-investigator")  // where does name resolve?
```

**Reasons:**
- Semantic dishonesty (no AgentRegistry exists)
- Over-promises未来能力
- No resolution source in M3

**Accepted for M3:**
```java
// ✅ THIS (explicit definition)
runtime.agent(new AgentDefinition("name", "desc"))
```

**Future (M4+ if needed):**
```java
// Maybe later when registry exists
runtime.agent("name")
```

---

## Consequences

### Positive

1. **Clear Invocation Boundary**
   - Agent API = user-facing
   - Engine API = low-level / internal
   - Clear separation

2. **Hides Implementation Details**
   - No Spring AI types in business code
   - Engine construction in composition root
   - Framework-neutral

3. **Future Process Compatible**
   ```
   Today: Agent → Runtime → Engine → Result
   Future: Agent → Runtime → [Process] → Engine(s) → Result
   User API unchanged
   ```

4. **Stateless = Safe**
   - Reusable across executions
   - Concurrent-safe
   - No lifecycle management

### Negative

1. **New Public Type**
   - Adds `Agent` interface (1 new public type)
   - But minimal (only 2 methods)

2. **Composition Root Required**
   - Requires Spring configuration or factory
   - But this is correct separation

### Neutral

1. **Not a Complete Solution**
   - M3 only solves invocation API
   - Configuration API still low-level
   - But intentional (YAGNI for full configuration DSL)

---

## Compliance

### What This Decision Requires

1. **Implementation:**
   - ✅ `Agent` interface (PUBLIC, arctra-core)
   - ✅ `DefaultAgent` (package-private, arctra-core)
   - ✅ `AgentRuntime.agent(AgentDefinition)` method
   - ✅ Tests (binding, stateless, stateful, reusable, multiple handles)

2. **Documentation:**
   - Agent = stateless invocation handle
   - State ownership clarification
   - Composition root examples

3. **Future Development:**
   - Agent API must remain unchanged when Process added
   - runtime.agent("name") only after AgentRegistry exists
   - No Agent entity lifecycle

---

## Important Notes

### Agent vs Process (Future)

**Critical:**  
Agent ≠ Process

**Why:**
- Agent = stateless invocation handle
- Process = future abstraction with lifecycle / state / checkpoint

**Future Model:**
```
Agent.execute()
    ↓
AgentRuntime
    ↓
[Process Runtime] (future, for complex scenarios)
    ↓
AgentExecutionEngine
```

**Key:** Agent API hides Process, but Agent does NOT implement Process.

### Module Placement

**Decision:** DefaultAgent / DefaultAgentRuntime stay in arctra-core

**Why:**
- Only depend on pure Java core types
- No Spring / Spring AI dependencies
- Generic Arctra runtime implementation

---

## Related Decisions

- **ADR-003:** AgentExecutionEngine as Pluggable Strategy
- **Future ADR:** AgentProcess (if needed)
- **Future ADR:** AgentRegistry (if needed)

---

## References

- [M3 Phase Planning](../planning/M3-PHASE-PLANNING.md)
- [M3-T1 Agent API Contract Gate](../planning/M3-T1-AGENT-API-CONTRACT-GATE.md)
- [Architecture Guardrails](../architecture/ARCHITECTURE-GUARDRAILS.md)

---

**Decision Status:** Accepted  
**Effective:** M3-T2
