# Arctra Project Snapshot - 2026-08-18

**Generated:** M2-T2 Progress Reconciliation

---

## Current Phase

**M2 Session & Multi-Turn Capability** 🚧 IN PROGRESS

---

## Completed

### M2-T1: Spring AI ChatMemory PoC ✅ (2026-08-18)
- Verified Spring AI 2.0.0 ChatMemory API
- Validated MessageChatMemoryAdvisor
- Confirmed conversationId propagation mechanism

### M2-T2: AgentExecutionContext & Session Support ✅ (2026-08-18)
- Created `AgentExecutionContext(String sessionId)` record
- Evolved `AgentExecutionEngine` contract (3-param canonical method)
- Implemented `SpringAiToolCallingEngine` session support
- Integrated Spring AI ChatMemory
- Core modules: 62 tests passed

### M1: Incident Agent MVP ✅ (2026-08-17)
- Agent domain model
- Evidence capture system
- Spring AI Tool Calling integration
- Incident investigation example

---

## Ready

### M2-T3: Multi-Turn E2E Scenario Test 📋 READY
- Verify complete multi-turn conversation
- Validate session isolation
- Confirm tool message persistence

---

## Current Core Contract

### AgentExecutionEngine
```java
// Canonical method (M2)
AgentResult execute(
    AgentDefinition definition,
    AgentRequest request,
    AgentExecutionContext context
);

// Compatibility method (M1)
default AgentResult execute(
    AgentDefinition definition,
    AgentRequest request
) {
    return execute(definition, request, AgentExecutionContext.stateless());
}
```

### AgentExecutionContext
```java
public record AgentExecutionContext(String sessionId) {
    public static AgentExecutionContext stateless();
    public static AgentExecutionContext withSession(String sessionId);
}
```

### SpringAiToolCallingEngine
```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory  // M2 NEW
)
```

---

## Current Session Strategy

**Arctra Ownership:**
- Session semantic definition
- `AgentExecutionContext` as execution-level identity
- sessionId → conversationId mapping

**Spring AI Implementation:**
- `ChatMemory` for conversation history storage
- `MessageChatMemoryAdvisor` for history injection
- `MessageWindowChatMemory` for in-memory sliding window

**Not Created (YAGNI):**
- ❌ Session class
- ❌ SessionRuntime
- ❌ SessionRepository
- ❌ ArctraMessage wrapper
- ❌ Memory abstraction

---

## Deferred to M3+

- Context compaction / turn safety
- Session locking / concurrency control
- Long-term memory
- Persistent ChatMemory (JDBC/Redis)
- Multi-agent coordination
- Spring AI Session API migration

---

## Known Limitations

**M2 Current:**
- ⚠️ Same session concurrent requests NOT supported
- ⚠️ No context compaction (simple sliding window)
- ⚠️ Tool message persistence unverified (needs M2-T3)
- ⚠️ In-memory only (no persistence)

---

## Next Task

**M2-T3: Multi-Turn E2E Scenario Test**

**Test Scenario:**
```java
// Turn 1
engine.execute(
    agent,
    new AgentRequest("生产环境 500 错误"),
    AgentExecutionContext.withSession("incident-123")
);

// Turn 2
engine.execute(
    agent,
    new AgentRequest("最可能的原因？"),
    AgentExecutionContext.withSession("incident-123")
);

// Verify: Turn 2 understands Turn 1 context
```

---

## Code Statistics (M2-T2)

**Modified Files:**
- `AgentExecutionEngine.java` - evolved contract
- `SpringAiToolCallingEngine.java` - session support
- Test engines: Fake, Echo, UpperCase
- Tests updated

**New Files:**
- `AgentExecutionContext.java`
- `AgentExecutionContextTest.java`
- M2 planning/design/implementation docs

**Tests:**
- arctra-core: 46 tests passed
- arctra-runtime-react: 16 tests passed (1 skipped)
- **Total: 62 tests passed**

---

## Next Session Guidance

**When resuming work:**

1. Start with M2-T3 (Multi-Turn E2E Test)
2. Do NOT start M2-T4 until M2-T3 complete
3. Do NOT start M3 until M2 complete
4. Current codebase in working state (core modules pass)
5. Example tests need ChatMemory parameter fix (3 files)

**Key Documents:**
- `TASKS.md` - current task queue
- `docs/project/CURRENT-STATE.md` - project state
- `docs/implementation/M2-T2-IMPLEMENTATION-REPORT.md` - M2-T2 details
- `docs/planning/M2-T2-CONTRACT-GATE-V2.md` - architecture decisions

---

**End of Snapshot**
