# M3 Final Architecture

**Version:** M3 (Agent API & Runtime Boundary)  
**Date:** 2026-08-18  
**Status:** COMPLETE

---

## Executive Summary

M3 introduces **Agent invocation handle** as the recommended user-facing API, establishing a clear boundary between configuration/composition and invocation.

**Key Achievement:**  
Business code no longer directly manipulates ChatModel, Tools, ChatMemory, or Engine construction details.

---

## M3 Final Architecture

### Layered Architecture

```
┌─────────────────────────────────────────┐
│         Business Code (User)            │
│  agent.execute(request, context)        │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│      Agent (stateless handle)           │
│      (arctra-core, PUBLIC)              │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│         AgentRuntime                    │
│    (orchestration boundary)             │
│      (arctra-core, PUBLIC)              │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│      AgentExecutionEngine               │
│   (execution strategy contract)         │
│      (arctra-core, PUBLIC)              │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│    SpringAiToolCallingEngine            │
│  (Spring AI integration)                │
│   (arctra-runtime-react)                │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│          Spring AI                      │
│   (ChatClient, Tool Calling Loop)       │
└─────────────────────────────────────────┘
```

---

## Public API (M3 Final)

### Agent (NEW in M3)

**Interface:** `cn.bitcss.arctra.agent.Agent`  
**Visibility:** PUBLIC  
**Layer:** User-facing invocation API

```java
public interface Agent {
    // Stateless convenience
    default AgentResult execute(AgentRequest request);
    
    // Canonical method (stateful/stateless)
    AgentResult execute(AgentRequest request, AgentExecutionContext context);
}
```

**Semantics:**
- Stateless invocation handle
- Reusable across multiple invocations
- Bound to specific AgentDefinition
- Does NOT own session/conversation state

---

### AgentRuntime (EVOLVED in M3)

**Interface:** `cn.bitcss.arctra.runtime.AgentRuntime`  
**Visibility:** PUBLIC  
**Layer:** Orchestration boundary

```java
public interface AgentRuntime {
    // NEW in M3: Create agent handle
    Agent agent(AgentDefinition definition);
    
    // Low-level direct execution
    AgentResult execute(AgentDefinition, AgentRequest, AgentExecutionContext);
    default AgentResult execute(AgentDefinition, AgentRequest);
}
```

**Semantics:**
- Creates Agent handles
- Delegates execution to Engine
- Orchestration layer (not execution)

---

### AgentExecutionEngine (M1/M2, STABLE)

**Interface:** `cn.bitcss.arctra.runtime.AgentExecutionEngine`  
**Visibility:** PUBLIC  
**Layer:** Execution strategy contract

```java
public interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
    
    // M1 compatibility
    default AgentResult execute(AgentDefinition, AgentRequest);
}
```

**Semantics:**
- Execution strategy (pluggable)
- NOT complete Agent boundary
- Engine = "how to execute"

---

### AgentDefinition (M1, STABLE)

```java
public record AgentDefinition(String name, String description) {}
```

---

### AgentRequest (M1, STABLE)

```java
public record AgentRequest(String userMessage) {}
```

---

### AgentExecutionContext (M2, STABLE)

```java
public record AgentExecutionContext(String sessionId) {
    static AgentExecutionContext stateless();
    static AgentExecutionContext withSession(String sessionId);
}
```

---

### AgentResult (M1, STABLE)

```java
public record AgentResult(String content, List<Evidence> evidences) {}
```

---

### Evidence (M1, STABLE)

```java
public record Evidence(String source, String content) {}
```

---

## Recommended User Path

### Composition Root (Configuration)

```java
// 1. Create Engine dependencies
ChatModel chatModel = ...;
List<ToolCallback> tools = List.of(...);
ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

// 2. Create Engine
AgentExecutionEngine engine = new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory
);

// 3. Create Runtime
AgentRuntime runtime = new DefaultAgentRuntime(engine);

// 4. Create Agent handle
Agent agent = runtime.agent(
    new AgentDefinition("Incident Investigator", "...")
);
```

### Business Code (Invocation)

```java
// Stateless
AgentResult result = agent.execute(
    new AgentRequest("Analyze incident")
);

// Stateful (multi-turn)
var context = AgentExecutionContext.withSession("incident-123");
agent.execute(new AgentRequest("Turn 1"), context);
agent.execute(new AgentRequest("Turn 2"), context);
```

---

## Configuration vs Invocation Boundary

**M3 Key Principle:** Clear separation

### Configuration (Composition Root)
- ChatModel, Tools, ChatMemory construction
- Engine creation
- Runtime creation
- Agent handle creation
- Typically done once at startup

### Invocation (Business Code)
- `agent.execute(...)`
- AgentRequest, AgentExecutionContext construction
- AgentResult consumption
- Zero Spring AI dependencies

---

## Session Semantics (M2, preserved in M3)

**Session = Conversation Continuity Boundary**

**Owned by:**
- AgentExecutionContext (identity)
- ChatMemory (storage)

**NOT owned by:**
- Agent (stateless handle)
- AgentRuntime (orchestration only)

**Multi-Turn Flow:**
```
Same sessionId → ChatMemory retrieves history → Conversation continuity
Different sessionId → Independent conversations
Null sessionId → Stateless (no history)
```

---

## Engine Semantics (M1/M2, unchanged in M3)

**Engine = Execution Strategy**

**Current Strategy:**
- SpringAiToolCallingEngine (Tool-Calling-based ReAct)

**Future Strategies (not implemented):**
- WorkflowEngine
- PlanningEngine
- CustomEngine

**Key:** Engine is pluggable component, not complete Agent boundary.

---

## Tool Integration Status

**Current:**
- Spring AI ToolCallback coupling at engine/integration layer
- Acceptable for M3

**Future Trigger:**
- Multiple execution backends
- Tool governance shared across engines
- Non-Spring tool consumers

---

## Evidence Model (M1, stable)

**Evidence = Observable execution fact / provenance**

**Current Producer:**
- `tool:<toolName>` (from EvidenceCapturingToolCallback)

**Future Producers (not implemented):**
- `action:...`
- `agent:...`
- `human:...`

---

## Current Limitations

### M3 Provides

✅ Agent invocation handle (stateless, reusable)  
✅ Multi-turn conversation (via AgentExecutionContext + ChatMemory)  
✅ Session isolation  
✅ Evidence capture  
✅ Framework-neutral API (no Spring deps in arctra-core)  
✅ Tool calling (via Spring AI integration)

### M3 Does NOT Provide

❌ **Agent Registry** (no `runtime.agent("name")`)  
❌ **Model Registry / Selection**  
❌ **Tool Registry**  
❌ **Multi-Step Process / Workflow** (no Process abstraction)  
❌ **Goal Planning** (no GOAP/Embabel-style planner)  
❌ **HITL / Checkpoint / Resume**  
❌ **Sub-Agent hierarchy**  
❌ **Persistent session** (in-memory only)  
❌ **Session concurrency control**  
❌ **Context compaction** (turn-aware)  
❌ **Streaming API**  
❌ **Governance / Policy layer**

---

## Deferred Capabilities

**From M3 Planning, explicitly deferred:**

1. **Persistent Session** (originally M3-T4 optional)
   - Reason: Not blocking Agent API validation
   - Future: M4 candidate

2. **Agent Registry**
   - Reason: No resolution source in M3
   - Future: When `runtime.agent("name")` needed

3. **Multi-Step Process**
   - Reason: No real consumer (Tool Calling Loop sufficient)
   - Future: When explicit step output → input, checkpoint, HITL needed

---

## Known Architectural Pressure Points

### A. Framework-Level Multi-Step Process

**Current:** Tool Calling Loop handles current scenarios

**Trigger:**
- Explicit step output → next step input
- Checkpoint between steps
- Retry specific step (not entire execution)
- HITL between steps
- Deterministic orchestration (not model-driven)

**Not Triggered:** M3 scenarios work with Tool Calling Loop

---

### B. Tool Abstraction Boundary

**Current:** Spring AI ToolCallback coupling acceptable at integration layer

**Trigger:**
- Multiple execution backends (non-Spring AI)
- Tool governance shared across engines
- Tool definition independent of Spring AI
- Non-Spring tool consumers

**Not Triggered:** Only SpringAiToolCallingEngine exists

---

### C. Persistent Session

**Current:** In-memory ChatMemory

**Trigger:**
- Process restart recovery
- Distributed runtime
- Durable conversation continuity

**Not Triggered:** Demo/dev use cases work with in-memory

---

### D. Agent Registry

**Current:** `runtime.agent(AgentDefinition)` explicit

**Trigger:**
- `runtime.agent("name")` semantic needed
- Dynamic agent loading
- Agent versioning

**Not Triggered:** Explicit definition sufficient

---

## Future-Compatible Extension Seams

**M3 preserves future evolution:**

### Process Insertion

```
Today: Agent → Runtime → Engine
Future: Agent → Runtime → [Process] → Engine(s)
User API unchanged
```

**Key:** Agent is protocol, Runtime routes to Process transparently

---

### Multiple Engines

```
Today: Runtime → SpringAiToolCallingEngine
Future: Runtime → Engine selection → {ToolCalling, Workflow, Planning}
```

**Key:** Engine is pluggable strategy

---

### Registry

```
Today: runtime.agent(definition)
Future: runtime.agent("name") + registry resolution
```

**Key:** Runtime.agent() method already exists, overload for String

---

## Module Structure (M3 Final)

```
arctra-core (Pure Java, zero Spring deps)
├── cn.bitcss.arctra.agent
│   ├── Agent (NEW M3, PUBLIC)
│   ├── AgentDefinition (M1)
│   ├── AgentRequest (M1)
│   ├── AgentResult (M1)
│   └── AgentExecutionContext (M2)
├── cn.bitcss.arctra.runtime
│   ├── AgentRuntime (EVOLVED M3, PUBLIC)
│   ├── DefaultAgentRuntime (EVOLVED M3, PUBLIC)
│   ├── DefaultAgent (NEW M3, package-private)
│   └── AgentExecutionEngine (M1/M2)
└── cn.bitcss.arctra.evidence
    └── Evidence (M1)

arctra-runtime-react (Spring AI integration)
└── cn.bitcss.arctra.runtime.react
    ├── SpringAiToolCallingEngine (M1/M2)
    └── EvidenceCapturingToolCallback (M1)

examples/incident-investigator
└── IncidentAgentApiTest (M3)
```

---

## ADR Status

**ADR-004: Agent as Invocation Handle Protocol**
- Status: **ACCEPTED**
- Validated by: M3-T3 vertical slice
- Key decisions confirmed:
  - Agent = stateless invocation handle
  - Agent ≠ Process
  - runtime.agent(definition) not runtime.agent("name")
  - Framework-neutral

---

## M3 Final Snapshot

**Public API Types:** 3 core (Agent, AgentRuntime, AgentExecutionEngine) + 4 data (Definition, Request, Context, Result) + 1 observability (Evidence)

**Recommended Entry:** `Agent.execute()`  
**Low-Level Entry:** `AgentRuntime.execute()`, `AgentExecutionEngine.execute()`

**Test Coverage:** 128 tests (core: 57, runtime-react: 56, incident: 15)

**Build:** ✅ GREEN

**Spring AI Leakage:** ✅ NONE (arctra-core verified)

**Architecture Drift:** ✅ NONE (no premature abstractions)

---

**M3 Final Architecture - Complete** ✅
