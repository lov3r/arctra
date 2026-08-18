# M2 Multi-Turn Quick Start Guide

**Last Updated:** 2026-08-18  
**Version:** M2 (Session Support)

---

## What is Multi-Turn?

Multi-turn conversation support allows agents to maintain context across multiple interactions within the same session. This enables:

- **Conversation continuity** - Turn 2 can reference Turn 1 without repeating context
- **Session isolation** - Different sessions maintain independent conversation histories
- **Stateless compatibility** - Single-turn (M1) behavior is preserved

**Important:** Multi-turn ≠ Multi-step

- **Multi-turn** = conversation continuity (multiple user inputs, M2)
- **Multi-step** = structured task execution (single user input, multiple steps, future)

See: [Execution Model Semantics](../architecture/EXECUTION-MODEL-SEMANTICS.md)

---

## Minimal Example

```java
// Create engine with ChatMemory
var chatMemory = MessageWindowChatMemory.builder()
    .maxMessages(20)  // Keep last 20 messages
    .build();

var engine = new SpringAiToolCallingEngine(
    chatModel,
    List.of(tools),
    chatMemory
);

// Turn 1: Initial question
var result1 = engine.execute(
    agentDefinition,
    new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因"),
    AgentExecutionContext.withSession("incident-123")
);

// Turn 2: Follow-up (no context repetition needed)
var result2 = engine.execute(
    agentDefinition,
    new AgentRequest("那最可能的原因是什么？"),  // "那" refers to Turn 1
    AgentExecutionContext.withSession("incident-123")
);
```

**What happens:**
- Turn 1: Agent investigates, returns analysis
- Turn 2: Agent understands "那" refers to Turn 1's incident, continues analysis without re-investigation

---

## Key Concepts

### AgentExecutionContext

The execution context carries session identity:

```java
// Session-based execution (multi-turn)
var context = AgentExecutionContext.withSession("session-123");
engine.execute(definition, request, context);

// Stateless execution (single-turn, M1 behavior)
var context = AgentExecutionContext.stateless();
engine.execute(definition, request, context);

// Shorthand for stateless
engine.execute(definition, request);  // Uses stateless() by default
```

### ChatMemory

ChatMemory stores conversation history:

```java
var chatMemory = MessageWindowChatMemory.builder()
    .maxMessages(20)  // Sliding window size
    .build();
```

- **Shared across executions** with the same `sessionId`
- **In-memory storage** in M2 (process restart loses history)
- **Sliding window** - keeps last N messages, discards older ones

### Session Semantics

```java
// Same sessionId → conversation continuity
engine.execute(def, req1, AgentExecutionContext.withSession("A"));
engine.execute(def, req2, AgentExecutionContext.withSession("A"));
// ✅ req2 sees req1 conversation

// Different sessionId → isolation
engine.execute(def, req1, AgentExecutionContext.withSession("A"));
engine.execute(def, req2, AgentExecutionContext.withSession("B"));
// ✅ req2 does NOT see req1 conversation

// Null sessionId → stateless
engine.execute(def, req1, AgentExecutionContext.stateless());
engine.execute(def, req2, AgentExecutionContext.stateless());
// ✅ req2 does NOT see req1 (M1 behavior)
```

---

## When to Use Multi-Turn

**Use multi-turn when:**
- User needs to ask follow-up questions
- Investigation requires iterative refinement
- Building conversational assistants
- Stateful workflows (e.g., debugging, troubleshooting)

**Example scenarios:**
- "分析这个错误" → "那根本原因是什么？" → "如何修复？"
- "查询订单状态" → "为什么延迟了？" → "可以取消吗？"
- Multi-step incident investigation

---

## When NOT to Use Multi-Turn

**Use stateless when:**
- Simple one-shot queries
- Parallel independent tasks
- Batch processing with no inter-task context
- Testing (simpler setup, no shared state)

**Example scenarios:**
- Translating 100 independent documents
- Analyzing logs from different time periods
- One-time data extraction

---

## Critical Implementation Requirements

### ⚠️ MUST Use ChatMemory.CONVERSATION_ID Constant

**CORRECT ✅**
```java
// SpringAiToolCallingEngine internally uses:
promptSpec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId));
```

**WRONG ❌**
```java
// This will FAIL with "conversationId cannot be null"
promptSpec.advisors(a -> a.param("conversationId", sessionId));
```

**Why:** Spring AI defines `ChatMemory.CONVERSATION_ID = "chat_memory_conversation_id"`, not `"conversationId"`.

**Action:** If you extend or customize `SpringAiToolCallingEngine`, always use the constant.

---

## Evidence vs Conversation

**Evidence** is per-execution:
```java
var result1 = engine.execute(..., context);
var result2 = engine.execute(..., context);

result1.evidences();  // Tool calls from Turn 1
result2.evidences();  // Tool calls from Turn 2 (independent)
```

**Conversation** is cross-execution:
- Turn 2 sees Turn 1 user/assistant messages
- ChatMemory maintains conversation history

**This separation ensures:**
- Evidence provenance remains clear per execution
- Conversation context enables continuity

---

## Example

See the complete multi-turn scenario in:
- `examples/incident-investigator/`
- Test: `IncidentAgentMultiTurnE2ETest.java`

---

## Known Limitations

Multi-turn support in M2 has several limitations. See [M2 Known Limitations](M2-KNOWN-LIMITATIONS.md) for details:

- ❌ Same session concurrent requests NOT supported
- ❌ In-memory only (no persistence)
- ❌ Simple sliding window (no turn-safety)
- ⚠️ Tool message persistence unverified

---

## Next Steps

1. **Try the example:** Run `examples/incident-investigator`
2. **Review limitations:** [M2 Known Limitations](M2-KNOWN-LIMITATIONS.md)
3. **Check implementation:** `SpringAiToolCallingEngine` Javadoc
4. **Plan for M3:** Persistence, concurrency, compaction

---

**Quick Start Complete** ✅

For deeper understanding, see:
- Architecture decisions: `docs/planning/M2-T2-CONTRACT-GATE-V2.md`
- Implementation details: `docs/implementation/M2-T2-IMPLEMENTATION-REPORT.md`
