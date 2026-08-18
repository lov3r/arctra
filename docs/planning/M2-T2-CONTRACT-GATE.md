# M2-T2 Contract Gate Analysis

**Date:** 2026-08-18  
**Status:** DRAFT - Awaiting Approval  
**Dependencies:** M2-T1 PoC Complete

---

## Purpose

Based on M2-T1 PoC findings, re-evaluate session identity propagation architecture before implementing M2-T2.

**Key Question:**
> "sessionId 应该如何从未来 Agent API 传播到 Spring AI conversation identity？"

---

## Input: M2-T1 PoC Key Findings

### Finding 1: ConversationId Propagation in Spring AI

**Verified Mechanism:**
```java
ChatClient client = ChatClient.builder(chatModel)
    .defaultAdvisors(new MessageChatMemoryAdvisor(...))
    .build();

client.prompt()
    .user("message")
    .advisors(spec -> spec.param("conversationId", "session-123"))
    .call();
```

**Key Insight:**
- ConversationId is **not** part of Prompt
- ConversationId is **not** part of ChatClientRequest directly  
- ConversationId is passed via **advisor parameter**
- MessageChatMemoryAdvisor reads it from advisor context

---

### Finding 2: MessageChatMemoryAdvisor is Self-Contained

**Behavior:**
- `before()`: Loads history from ChatMemory using conversationId
- `after()`: Saves new messages to ChatMemory using conversationId
- No external session management needed

**Implication:**
- Session management is **entirely Spring AI concern**
- Arctra only needs to ensure conversationId reaches the advisor

---

### Finding 3: Multiple Advisors Compose Well

**Verified:**
```java
ChatClient.builder(chatModel)
    .defaultAdvisors(
        new MessageChatMemoryAdvisor(...),  // Memory
        new ToolCallingAdvisor(...)          // Tool calling
    )
    .build();
```

**Implication:**
- Memory + Tool calling can coexist
- No special integration code needed

---

## Re-Evaluation: Candidate Architectures

### Option A: sessionId in AgentRequest

```java
record AgentRequest(
    String userMessage,
    String sessionId  // NEW
)
```

**Pros:**
- Simple to pass through call chain
- Co-located with user input

**Cons:**
- ❌ sessionId is NOT user input (semantic mismatch)
- ❌ AgentRequest注释明确："stateless, single-turn"
- ❌ Future: userId, traceId would pollute AgentRequest
- ❌ Makes single-turn case awkward (sessionId = null?)

**Verdict:** ❌ **REJECT** (Same as before, but now confirmed by PoC)

---

### Option B: Modify AgentExecutionEngine Contract

```java
interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId  // NEW
    );
}
```

**Pros:**
- sessionId independent of request
- Engine can use it to configure ChatClient

**Cons:**
- ❌ Breaks M1 API (all engines must update)
- ❌ Future: more context params → more signature changes
- ❌ Forces ALL engines to accept sessionId (even if they don't support it)

**M2-T1 PoC Impact:**
- PoC shows conversationId is Spring AI-specific
- Other engines might have different session mechanisms
- Forcing String sessionId on all engines is premature

**Verdict:** ❌ **REJECT** (Confirmed by PoC - too Spring AI-specific)

---

### Option C: ExecutionContext Object

```java
record ExecutionContext(
    String sessionId,
    // Future: String userId, String traceId
)

interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        ExecutionContext context  // NEW
    );
}
```

**Pros:**
- Extensible (add fields without breaking API)
- Separates execution context from user input

**Cons:**
- ⚠️ Still modifies Engine contract
- ⚠️ M2 only has ONE field (sessionId) - is ExecutionContext premature?
- ⚠️ Not all engines need context

**M2-T1 PoC Impact:**
- PoC shows sessionId is advisor-level concern
- ExecutionContext at Engine level might be wrong layer

**Re-evaluation:**
- ExecutionContext MIGHT be right
- But WHEN to introduce it?
- M2 with 1 field? Or wait for 2+ fields?

**Verdict:** ⚠️ **POSSIBLE** (But timing questionable)

---

### Option D: SpringAiToolCallingEngine.executeWithSession()

```java
public class SpringAiToolCallingEngine implements AgentExecutionEngine {
    
    // M1 method (unchanged)
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        // Single-turn, no memory
    }
    
    // M2 method (new, engine-specific)
    public AgentResult executeWithSession(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId
    ) {
        // Multi-turn with memory
    }
}
```

**Pros:**
- ✅ M1 API unchanged (backward compatible)
- ✅ sessionId is opt-in (single-turn doesn't need it)
- ✅ Engine-specific (other engines can have different methods)
- ✅ No premature abstraction (no ExecutionContext yet)

**Cons:**
- ⚠️ Creates API proliferation risk (execute, executeWithSession, executeWith...)
- ⚠️ Not discoverable via Engine interface
- ⚠️ Requires instanceof check or adapter pattern

**M2-T1 PoC Impact:**
- ✅ PoC confirms this CAN work
- ✅ conversationId is Spring AI-specific (engine-specific method makes sense)
- ⚠️ But: future Agent API will hide this

**Verdict:** ✅ **VIABLE** (But concerns about API proliferation)

---

### Option E: Runtime-Level SessionBinding (NEW)

**Concept:** Don't pass sessionId through Engine at all. Instead, Runtime configures Engine with bound session.

```java
// Pseudo-code
public class SessionBoundEngineAdapter {
    private final SpringAiToolCallingEngine engine;
    private final String sessionId;
    
    public SessionBoundEngineAdapter(
        SpringAiToolCallingEngine engine,
        String sessionId
    ) {
        this.engine = engine;
        this.sessionId = sessionId;
    }
    
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        // Engine internally uses this.sessionId
        return engine.executeInternal(definition, request, sessionId);
    }
}
```

**Pros:**
- ✅ Engine contract unchanged
- ✅ sessionId bound at Runtime layer
- ✅ Clean separation of concerns

**Cons:**
- ❌ Requires Engine to expose internal method
- ❌ More complex (adapter pattern)
- ❌ Doesn't match Spring AI's per-call conversationId model

**M2-T1 PoC Impact:**
- ❌ PoC shows conversationId is **per-call**, not per-engine
- ❌ Binding at engine level is wrong granularity

**Verdict:** ❌ **REJECT** (Doesn't match Spring AI model)

---

## Critical Realization from PoC

### Spring AI's Model: ConversationId is Per-Call

```java
// Same ChatClient, different conversationIds
client.prompt().user("msg1").advisors(spec -> spec.param("conversationId", "A")).call();
client.prompt().user("msg2").advisors(spec -> spec.param("conversationId", "B")).call();
```

**Implication:**
- ConversationId is **call-level parameter**, not configuration
- You don't "configure an engine with a session"
- You "execute a call with a conversationId"

**Impact on Architecture:**
- Option E (Runtime-level binding) is WRONG
- sessionId must be passed at **execution time**, not configuration time

---

## The Real Question

### Where Does Arctra Add Value?

**What Spring AI Provides:**
- ChatMemory storage
- MessageChatMemoryAdvisor (history injection)
- Advisor parameter passing

**What Arctra Should Provide:**
1. **Agent-level API** (hide ChatClient details)
2. **Session identity semantics** (what is a session in Arctra?)
3. **Integration with AgentDefinition** (agent metadata)
4. **Integration with Evidence** (governance trail)

**What Arctra Should NOT Provide:**
- ❌ Re-implementation of ChatMemory
- ❌ Re-implementation of history injection
- ❌ Wrapper around Spring AI Message

---

## Revised Candidate: Hybrid Approach

### Concept: Agent API manages session, Engine is session-agnostic

**Layer 1: Future Agent API (M3+)**
```java
agent.session("incident-123")
    .user("生产环境 500")
    .call();
    
// Internally:
// - Resolves AgentDefinition
// - Resolves Engine
// - Calls engine with sessionId somehow
```

**Layer 2: M2 Implementation (Today)**
```java
// No Agent API yet, so direct engine usage:
SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(
    chatModel,
    tools,
    chatMemory  // NEW: inject ChatMemory
);

// Option D: Engine-specific method
AgentResult result = engine.executeWithSession(
    definition,
    request,
    "session-123"
);
```

**Key Insight:**
- M2 doesn't need to solve "perfect Engine contract"
- M2 needs to prove "multi-turn CAN work"
- M3 Agent API will abstract the details

---

## Recommendation

### For M2: Choose Option D with Clear Path to M3

**M2 Implementation:**
```java
public class SpringAiToolCallingEngine implements AgentExecutionEngine {
    private final ChatModel chatModel;
    private final List<ToolCallback> tools;
    private final ChatMemory chatMemory;  // NEW
    
    // M1 compatibility (unchanged)
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return executeWithSession(definition, request, null);
    }
    
    // M2 capability (new)
    public AgentResult executeWithSession(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId  // nullable
    ) {
        // Build ChatClient with advisors
        List<Advisor> advisors = new ArrayList<>();
        
        if (sessionId != null) {
            advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }
        
        // Always add tool calling (if needed)
        // advisors.add(...);
        
        ChatClient client = ChatClient.builder(chatModel)
            .defaultAdvisors(advisors.toArray(new Advisor[0]))
            .build();
        
        var promptSpec = client.prompt()
            .system(buildSystemInstruction(definition))
            .user(request.userMessage())
            .tools(wrappedTools);
        
        if (sessionId != null) {
            promptSpec = promptSpec.advisors(spec ->
                spec.param("conversationId", sessionId)
            );
        }
        
        String content = promptSpec.call().content();
        return new AgentResult(content, evidences);
    }
}
```

**M3 Evolution Path:**
```java
// M3: Agent API layer
public class AgentClient {
    public SessionBuilder session(String sessionId) {
        return new SessionBuilder(sessionId, this);
    }
    
    class SessionBuilder {
        AgentResult call() {
            // Internally calls:
            // engine.executeWithSession(def, req, sessionId)
            // or
            // if engine supports session-aware contract (future):
            //     engine.execute(def, req, new ExecutionContext(sessionId))
        }
    }
}
```

**Why This Works:**
1. ✅ M2 doesn't force Engine contract change
2. ✅ M2 proves multi-turn works
3. ✅ M3 can introduce Agent API without breaking M2
4. ✅ M4 can refactor Engine contract if MULTIPLE engines need it

---

## Final Recommendations

### 1. M2-T2 Implementation

**Do:**
- ✅ Add `ChatMemory` field to `SpringAiToolCallingEngine`
- ✅ Add `executeWithSession()` method
- ✅ Keep `execute()` unchanged (delegates to `executeWithSession(null)`)
- ✅ Document: "Session support is SpringAiToolCallingEngine-specific"

**Don't:**
- ❌ Modify `AgentExecutionEngine` interface
- ❌ Create `ExecutionContext` (wait for 2+ fields)
- ❌ Create `SessionRuntime` (not needed yet)
- ❌ Create `Session` class (String is enough)

---

### 2. M3 Planning Inputs

**When to introduce ExecutionContext:**
- When at least 2 fields exist (e.g., sessionId + userId)
- When at least 2 engines need it

**When to introduce Agent API:**
- M3 or later
- After multi-turn is proven in M2

**When to modify Engine contract:**
- Only if MULTIPLE engines need session support
- Not for Spring AI-specific concerns

---

### 3. ADR Timing

**ADR-003: Defer to After M2-T2**

**Reason:**
- M2-T2 implementation will reveal additional constraints
- Better to document actual working solution than theoretical design

**ADR Content (Future):**
- Decision: Engine-specific session methods (not contract modification)
- Rationale: Session is opt-in, engine-specific
- Consequences: Future Agent API will abstract
- Evolution path: ExecutionContext when 2+ engines need it

---

## Conclusion

**Approved for M2-T2:**
- ✅ Option D: `executeWithSession()` method
- ✅ No Engine contract modification
- ✅ No premature abstractions
- ✅ Clear evolution path to M3

**Next Step:**
- Begin M2-T2 implementation
- Create integration test with real multi-turn scenario
- Verify tool calling + memory interaction
- Update documentation

---

**End of M2-T2 Contract Gate Analysis**
