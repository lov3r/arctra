# M2 Known Limitations

**Last Updated:** 2026-08-18  
**Version:** M2 (Session Support)

---

## Overview

M2 introduces multi-turn conversation support with the following known limitations. These are **by design** for M2 and will be addressed in future milestones.

---

## 1. Concurrency

### ❌ Same Session Concurrent Requests NOT Supported

**Limitation:**
Multiple concurrent requests with the same `sessionId` are NOT supported.

**Reason:**
- No session locking mechanism
- Spring AI `InMemoryChatMemory` concurrent safety unverified
- Potential race conditions in conversation history updates

**Impact:**
```java
// ❌ THIS MAY FAIL OR PRODUCE INCORRECT RESULTS
CompletableFuture.allOf(
    CompletableFuture.runAsync(() -> 
        engine.execute(def, req1, AgentExecutionContext.withSession("A"))),
    CompletableFuture.runAsync(() ->
        engine.execute(def, req2, AgentExecutionContext.withSession("A")))
).join();
```

**Workaround:**
Serialize requests per session:
```java
// ✅ CORRECT: Sequential execution for same session
engine.execute(def, req1, AgentExecutionContext.withSession("A"));
engine.execute(def, req2, AgentExecutionContext.withSession("A"));

// ✅ CORRECT: Parallel execution with different sessions
CompletableFuture.allOf(
    CompletableFuture.runAsync(() ->
        engine.execute(def, req1, AgentExecutionContext.withSession("A"))),
    CompletableFuture.runAsync(() ->
        engine.execute(def, req2, AgentExecutionContext.withSession("B")))
).join();
```

**Future:**
- M3: Redis-based distributed session locking
- M3: Session version control (optimistic locking)

---

## 2. Context Management

### ❌ No Turn-Safety in Compaction

**Limitation:**
`MessageWindowChatMemory` uses a simple sliding window that may break User/Assistant message pairs.

**Reason:**
- `MessageWindowChatMemory.maxMessages(N)` keeps last N messages
- No awareness of conversation turns
- May discard User message but keep Assistant response (or vice versa)

**Impact:**
```java
// maxMessages = 5
// Turn 1: [User1, Assistant1]
// Turn 2: [User2, ToolCall, ToolResponse, Assistant2]
// Turn 3: [User3, ...]
//
// After Turn 3, Turn 1 User1 may be discarded while Assistant1 remains
// → Broken conversation context
```

**Workaround:**
Use large enough `maxMessages` to avoid premature compaction:
```java
// Conservative: Keep last 50-100 messages
MessageWindowChatMemory.builder()
    .maxMessages(100)
    .build();
```

**Future:**
- M3: Turn-aware compaction (preserve User/Assistant pairs)
- M3: Smart summarization (compress old turns, keep recent ones)
- M3: Consider Spring AI Session API migration

---

## 3. Persistence

### ❌ In-Memory Only (Process Restart Loses History)

**Limitation:**
M2 only supports in-memory conversation storage. Process restart loses all history.

**Reason:**
- `MessageWindowChatMemory` is in-memory by default
- No persistent `ChatMemory` implementation integrated in M2

**Impact:**
```java
// Application restart
engine.execute(def, req, AgentExecutionContext.withSession("A"));
// → All previous conversation history for "A" is lost
```

**Workaround:**
None in M2. Accept that conversations are ephemeral.

**Future:**
- M3: JDBC-based `ChatMemory` (e.g., PostgreSQL)
- M3: Redis-based `ChatMemory` for distributed deployments
- M3: Configurable persistence strategy

---

## 4. Tool Messages

### ⚠️ Tool Message Persistence Unverified

**Limitation:**
Tool call and response messages may not be persisted in `ChatMemory`.

**Status:**
- Assumed: `MessageChatMemoryAdvisor.after()` saves all messages
- Not verified: No real API end-to-end test completed (upstream proxy unavailable)

**Potential Impact:**
If tool messages are NOT persisted:
- Turn 1: Agent calls `queryLogs`, returns analysis
- Turn 2: Agent sees Turn 1 user question + final response, but NOT tool call/response
- Turn 2: Agent might re-call `queryLogs` (redundant)

**Current Assumption:**
Spring AI's `MessageChatMemoryAdvisor` saves all intermediate messages (including tool calls). This is the expected behavior based on Spring AI design.

**Verification Plan:**
Run `IncidentAgentMultiTurnE2ETest` with real API when available to confirm tool message persistence.

**Workaround:**
None needed if assumption holds. If tool messages are not persisted:
- Accept redundant tool calls
- Or maintain separate tool result cache (out of scope for M2)

---

## 5. Long-Term Memory

### ❌ No Cross-Session Knowledge Extraction

**Limitation:**
M2 does not support extracting insights across sessions.

**Example:**
- Session A: "订单 123 延迟"
- Session B: "订单 456 延迟"
- Agent CANNOT learn: "多个订单都在延迟，可能是系统性问题"

**Reason:**
Sessions are isolated by design in M2. No knowledge aggregation layer exists.

**Future:**
- M3+: Long-term memory (extract patterns, facts, insights)
- M3+: Vector store for cross-session knowledge retrieval
- M3+: Session summary and consolidation

---

## 6. Critical Implementation Requirements

### ✅ MUST Use ChatMemory.CONVERSATION_ID Constant

**This is a BLOCKER if violated.**

**CORRECT ✅**
```java
promptSpec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId));
```

**WRONG ❌**
```java
promptSpec.advisors(a -> a.param("conversationId", sessionId));
```

**Why:**
Spring AI defines:
```java
public interface ChatMemory {
    String CONVERSATION_ID = "chat_memory_conversation_id";
}
```

Using string literal `"conversationId"` will fail with:
```
IllegalArgumentException: conversationId cannot be null
```

**Root Cause:**
- M2-T3 spent significant time debugging this issue
- See: `docs/implementation/M2-T3-ROOT-CAUSE-ANALYSIS-REPORT.md`

**Action:**
- If extending `SpringAiToolCallingEngine`, always use the constant
- Code review should catch string literal usage
- Future: Consider creating a test that verifies correct key usage

---

## Summary Table

| Limitation | Status | M2 Workaround | Future Plan |
|------------|--------|---------------|-------------|
| Same session concurrency | ❌ Not supported | Serialize per session | M3: Redis locking |
| Turn-safety compaction | ❌ Not supported | Large maxMessages | M3: Turn-aware compaction |
| Persistence | ❌ In-memory only | None | M3: JDBC/Redis |
| Tool message persistence | ⚠️ Unverified | None (assume works) | Verify with real API |
| Long-term memory | ❌ Not supported | None | M3+: Knowledge extraction |
| CONVERSATION_ID key | ✅ Must use constant | N/A | N/A |

---

## When These Limitations Matter

**You should be concerned if:**
- You need concurrent requests for the same session → Wait for M3
- You need conversation persistence across restarts → Wait for M3
- You have very long conversations (>100 turns) → Use large maxMessages
- You need cross-session insights → Wait for M3+

**You can proceed if:**
- Sequential requests per session are acceptable
- Ephemeral conversations are acceptable
- Conversations are reasonably short (<50 turns)
- Session isolation is desired behavior

---

## Reporting Issues

If you encounter issues beyond these known limitations:

1. Check `docs/implementation/M2-T3-ROOT-CAUSE-ANALYSIS-REPORT.md`
2. Verify `ChatMemory.CONVERSATION_ID` usage
3. Confirm sequential execution per session
4. Review `SpringAiToolCallingEngine` implementation

---

**Known Limitations documented** ✅

See also:
- Quick Start: [M2 Multi-Turn Quick Start](M2-MULTI-TURN-QUICK-START.md)
- Architecture: `docs/planning/M2-T2-CONTRACT-GATE-V2.md`
