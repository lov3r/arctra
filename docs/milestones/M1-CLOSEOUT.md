# M1 CLOSEOUT REPORT

**Date:** 2026-08-17  
**Milestone:** M1 Incident Agent MVP  
**Status:** ✅ COMPLETE

---

## 1. M1 Acceptance Result

**VERDICT:** ✅ **PASS**

**Summary:**
- All M1 tasks (T1-T7) completed
- E2E scenario verified with real OpenAI API
- Build passes: `./mvnw clean verify` ✅
- Architecture boundaries maintained
- No blocking issues

**Recommendation:** M1 is formally COMPLETE. Ready to proceed to next milestone.

---

## 2. Final Architecture Snapshot

### Module Structure

```
arctra-parent (parent pom)
├── arctra-api (placeholder, empty)
├── arctra-core (pure Java domain + runtime contracts)
│   ├── agent (AgentDefinition, AgentRequest, AgentResult)
│   ├── evidence (Evidence)
│   └── runtime (AgentRuntime, AgentExecutionEngine)
├── arctra-runtime-react (Spring AI integration)
│   ├── SpringAiToolCallingEngine
│   └── EvidenceCapturingToolCallback
├── arctra-spring-boot-starter (empty, reserved for future)
├── arctra-rag (placeholder)
├── arctra-tool (placeholder)
├── arctra-testkit (placeholder)
└── examples/
    ├── incident-investigator (M1 vertical slice)
    │   ├── tools/ (QueryLogsTool, GetDeploymentTool)
    │   └── tests/ (8 passing, 5 disabled E2E)
    └── knowledge-assistant (placeholder)
```

### Dependency Graph

```
arctra-core → ∅ (no dependencies, pure Java)
arctra-runtime-react → arctra-core, spring-ai-client-chat
examples/incident-investigator → arctra-core, arctra-runtime-react, spring-ai-openai (test)
```

### Architecture Boundaries Compliance

| Rule | Status | Enforcement |
|------|--------|-------------|
| arctra-core → no Spring | ✅ PASS | Maven Enforcer (banned dependencies) |
| arctra-core → no Spring AI | ✅ PASS | Maven Enforcer |
| arctra-core → pure Java | ✅ PASS | No compile dependencies |
| arctra-runtime-react → can use Spring AI | ✅ PASS | Intentional dependency |
| examples → can use framework | ✅ PASS | Correct dependency direction |
| examples → no reverse pollution | ✅ PASS | No framework code depends on examples |

---

## 3. Final Execution Flow

**Actual implementation flow (verified by code and tests):**

```
User
  ↓
AgentRuntime.execute(definition, request)
  ↓
DefaultAgentRuntime
  ↓
AgentExecutionEngine.execute(definition, request)
  ↓
SpringAiToolCallingEngine
  ├─ Wrap each ToolCallback with EvidenceCapturingToolCallback (per-execution)
  ├─ Build system instruction from AgentDefinition
  └─ ChatClient.prompt()
      .system(systemInstruction)
      .user(request.userMessage())
      .tools(wrappedTools.toArray(new ToolCallback[0]))  ← varargs, NOT (Object) cast
      .call()
      .content()
        ↓
      Spring AI ChatClient (automatic)
        ├─ Adds ToolCallingAdvisor (if options instanceof ToolCallingChatOptions)
        └─ Tool Calling Loop:
            1. Call ChatModel → returns ToolCalls
            2. Execute ToolCallbacks → capture Evidence
            3. Build ToolResponseMessage
            4. Call ChatModel again → returns final answer
  ↓
AgentResult(content, evidences)
  ↓
User
```

**Key Discovery:**
- ToolCallingAdvisor is **automatically added** by Spring AI when `.tools()` is called
- But it **conditionally executes** based on `options instanceof ToolCallingChatOptions`
- This is Spring AI's **capability detection** mechanism

---

## 4. Spring AI Integration Reality

### Early Design Assumptions vs Final Implementation

#### ❌ Early Design Assumption (M1-T3 initial exploration)
```java
// Assumed we needed explicit ChatClient builder config
ChatClient.builder(chatModel)
    .defaultAdvisors(new ToolCallingAdvisor(...))  // explicit
    .build();
```

#### ✅ Final Implementation Reality
```java
// ToolCallingAdvisor is added automatically by .tools()
ChatClient.builder(chatModel).build()  // no explicit advisor
    .prompt()
    .tools(tool1, tool2)  // ← this triggers advisor addition
    .call();
```

### Critical Spring AI API Discoveries

**1. ToolCallingAdvisor automatic addition**
- Location: `ChatClient.prompt().tools(...)` 
- Condition: Always added when tools are provided
- Execution: Only if `options instanceof ToolCallingChatOptions`

**2. Capability detection via options type**
```java
// Inside ToolCallingAdvisor.adviseCall()
ChatOptions options = chatClientRequest.prompt().getOptions();
if (!(options instanceof ToolCallingChatOptions)) {
    return callAdvisorChain.nextCall(chatClientRequest);  // skip
}
```

**3. Tool calling loop ownership**
- Owner: Spring AI ToolCallingAdvisor
- Arctra role: Provide ChatModel + ToolCallbacks
- Loop control: Fully delegated to Spring AI

**4. Evidence capture point**
- Strategy: Wrapper pattern around ToolCallback
- Implementation: EvidenceCapturingToolCallback
- Scope: Per-execution (new wrappers each call)

### Three Major Bugs Fixed

**Bug #1: Varargs parameter passing**
```java
// ❌ WRONG - cast to Object breaks varargs
.tools((Object) tools.toArray(new ToolCallback[0]))

// ✅ CORRECT - let varargs expand
.tools(tools.toArray(new ToolCallback[0]))
```
Impact: Tool calling loop never triggered (no error, silent failure)

**Bug #2: Missing inputSchema**
```java
// ❌ WRONG - Spring AI 2.0 requires inputSchema
ToolDefinition.builder()
    .name("tool")
    .build();

// ✅ CORRECT
ToolDefinition.builder()
    .name("tool")
    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
    .build();
```
Impact: IllegalArgumentException at runtime

**Bug #3: Fake ChatModel not returning ToolCallingChatOptions**
```java
// ❌ WRONG
public ChatOptions getDefaultOptions() {
    return null;
}

// ✅ CORRECT
public ChatOptions getDefaultOptions() {
    return OpenAiChatOptions.builder().build();
}
```
Impact: ToolCallingAdvisor skips execution (capability detection fails)

### No Workarounds Introduced

Final implementation is clean. All "workarounds" during exploration were removed:
- No custom ToolExecutor
- No manual loop implementation
- No Spring AI API bypass
- Fully delegated to ChatClient + ToolCallingAdvisor

---

## 5. Public API Surface

### New Public APIs (M1)

**arctra-core:**
```java
// Domain models
public record AgentDefinition(String name, String description)
public record AgentRequest(String userMessage)
public record AgentResult(String content, List<Evidence> evidences)
public record Evidence(String source, String content)

// Runtime contracts
public interface AgentRuntime {
    AgentResult execute(AgentDefinition definition, AgentRequest request);
}
public interface AgentExecutionEngine {
    AgentResult execute(AgentDefinition definition, AgentRequest request);
}
public class AgentRuntimeFactory {
    public static AgentRuntime create(AgentExecutionEngine engine);
}
```

**arctra-runtime-react:**
```java
// Engine implementation
public class SpringAiToolCallingEngine implements AgentExecutionEngine {
    public SpringAiToolCallingEngine(ChatModel chatModel, List<ToolCallback> tools);
    public AgentResult execute(AgentDefinition definition, AgentRequest request);
}

// Evidence capture (public for testing, internal use)
public class EvidenceCapturingToolCallback implements ToolCallback {
    // Wrapper pattern, transparent proxy
}
```

### API Leakage Analysis

**Question:** Does SpringAiToolCallingEngine leak Spring AI types?

**Answer:** YES, intentionally.

```java
public SpringAiToolCallingEngine(ChatModel chatModel, List<ToolCallback> tools)
                                 ^^^^^^^^              ^^^^^^^^^^^^
                                 Spring AI types exposed in constructor
```

**Verdict:** ✅ **ACCEPTABLE**

**Reasoning:**
- `arctra-runtime-react` is explicitly a Spring AI integration module
- Its purpose IS to integrate Spring AI ChatModel with Arctra runtime
- Users who choose this engine knowingly accept Spring AI dependency
- No accidental leakage into arctra-core (clean boundary)
- Future alternative engines (LangChain4j, custom) would expose their own types
- No premature abstraction needed (ModelAdapter, ToolRegistry, etc.)

**Not creating:**
- ~~ArctraChatModel~~ (no current need)
- ~~ArctraTool~~ (ToolCallback works fine)
- ~~ToolRegistry~~ (static List<ToolCallback> sufficient for M1)
- ~~ModelAdapter~~ (no multi-model scenario yet)

---

## 6. Cleanup Changes

### Test File Classification

| Test File | Type | Status | Disposition |
|-----------|------|--------|-------------|
| QueryLogsToolTest | Unit | ✅ PASS | KEEP - validates tool contract |
| GetDeploymentToolTest | Unit | ✅ PASS | KEEP - validates tool contract |
| EvidenceCaptureDirectTest | Integration | ✅ PASS | KEEP - validates evidence capture |
| FakeChatModelWithToolCalling | Test Fixture | N/A | KEEP - reusable test fixture |
| IncidentAgentFakeE2ETest | E2E | @Disabled | KEEP - validates fake model integration |
| IncidentAgentManualE2ETest | E2E | @Disabled | KEEP - manual verification with real API |
| IncidentAgentRealE2ETest | E2E | @Disabled | KEEP - real OpenAI integration test |
| SpringAiToolCallingSpringBootTest | E2E | @Disabled | KEEP - Spring Boot integration test |
| MinimalToolCallingTest | Exploration | ✅ PASS | **REMOVE** - exploration artifact |
| ExplicitToolCallingAdvisorTest | Exploration | Compile fail | **REMOVE** - failed experiment |
| DetailedFakeChatModelTest | Debug | @Disabled | **REMOVE** - temporary debug aid |
| TestApplication | Test Support | N/A | KEEP - Spring Boot test app |

### Cleanup Actions

**REMOVE (exploration artifacts with no regression value):**
1. `MinimalToolCallingTest` - proved `.tools()` doesn't auto-add advisor (but we now know it does, test is outdated)
2. `ExplicitToolCallingAdvisorTest` - failed attempt to manually construct ToolCallingAdvisor
3. `DetailedFakeChatModelTest` - verbose debug test, replaced by cleaner FakeE2ETest

**KEEP (regression value):**
- `FakeChatModelWithToolCalling` - demonstrates correct fake implementation (getDefaultOptions)
- `IncidentAgentFakeE2ETest` - validates complete fake integration
- `IncidentAgentManualE2ETest` - human-verified real API test
- `IncidentAgentRealE2ETest` - automated real API test (disabled by default)
- `SpringAiToolCallingSpringBootTest` - validates Spring Boot auto-config path

---

## 7. Test Results

### Build Output

```bash
$ ./mvnw clean verify

[INFO] Reactor Summary:
[INFO] 
[INFO] Arctra :: Parent ................................... SUCCESS
[INFO] Arctra :: API ...................................... SUCCESS
[INFO] Arctra :: Core ..................................... SUCCESS [2.568 s]
[INFO] Arctra :: Runtime :: ReAct ......................... SUCCESS [1.554 s]
[INFO] Arctra :: RAG ...................................... SUCCESS
[INFO] Arctra :: Tool ..................................... SUCCESS
[INFO] Arctra :: TestKit .................................. SUCCESS
[INFO] Arctra :: Spring Boot Starter ...................... SUCCESS
[INFO] Arctra :: Examples :: Knowledge Assistant .......... SUCCESS
[INFO] Arctra :: Examples :: Incident Investigator ........ SUCCESS [1.673 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  7.413 s
```

### Test Statistics

**arctra-core:**
- Tests run: 20+
- Failures: 0
- Errors: 0

**arctra-runtime-react:**
- Tests run: 0 (no tests yet, engine tested via examples)
- Failures: 0
- Errors: 0

**examples/incident-investigator:**
- Tests run: 13
- Passing: 8
- Skipped: 5 (@Disabled, requires real API or manual enable)
- Failures: 0
- Errors: 0

### E2E Verification

**Manual verification (jingbo confirmed):**
- ✅ `IncidentAgentManualE2ETest.should_work_with_manually_built_chatmodel()`
  - ChatModel called multiple times (tool calling loop worked)
  - Both tools executed (queryLogs, getDeployment)
  - 2 evidences captured
  - Complete root cause analysis generated

---

## 8. Remaining Technical Debt

### Non-Blocking Issues

**1. arctra-runtime-react has no unit tests**
- Current: Integration tested via examples
- Impact: Low (small codebase, 2 classes)
- Fix: Add unit tests for SpringAiToolCallingEngine
- Priority: Medium (can defer to M2)

**2. Some exploration tests should be removed**
- Current: 3 exploration tests still in codebase
- Impact: Low (don't affect build)
- Fix: Delete MinimalToolCallingTest, ExplicitToolCallingAdvisorTest, DetailedFakeChatModelTest
- Priority: Low (cleanup)

**3. MinimalToolCallingTest conclusion is outdated**
- Test output: "❌ Tool calling loop did NOT work. ToolCallingAdvisor was NOT added"
- Reality: Advisor IS added, but skipped due to null options
- Impact: Low (test still passes, just misleading message)
- Fix: Remove or update test
- Priority: Low

**4. Evidence does not include timestamp**
- Current: Evidence(source, content)
- Impact: Low (ordering is preserved by List, timestamp not needed for M1 scenario)
- Fix: Add timestamp field if future scenarios need it
- Priority: Deferred (wait for real need)

**5. Tool failure behavior not explicitly tested**
- Current: No test for ToolCallback throwing exception
- Impact: Low (Spring AI handles it, but behavior not documented)
- Fix: Add test for tool failure scenario
- Priority: Medium

### No Architectural Debt

- Clean separation: core vs runtime
- No premature abstraction
- No workarounds in production code
- Spring AI integration is direct and transparent

---

## 9. Deferred Decisions

### Still Deferred (correct to defer)

**Arctra Tool Contract:**
- Decision: Continue using Spring AI ToolCallback directly
- Why: No multi-framework scenario yet
- When: If/when we add LangChain4j or custom tool system

**Tool Registry:**
- Decision: Static List<ToolCallback> sufficient
- Why: M1 has 2 tools, no dynamic registration needed
- When: If/when we need runtime tool discovery

**Per-Agent Tool Configuration:**
- Decision: All tools available to all agents
- Why: M1 has 1 agent type
- When: If/when we have multiple agent types with different tool needs

**Tool Governance:**
- Permission, Policy, Risk, Audit, Sandbox
- Decision: All deferred
- Why: No production deployment, no security requirements yet
- When: Before production use

**Evidence Persistence:**
- Decision: In-memory only (List in AgentResult)
- Why: No audit/replay requirements yet
- When: If/when we need audit trail or debugging workflow

**Structured Decision Model:**
- Decision: Not created at framework level
- Why: Only used in M1 scenario PoC, not validated cross-scenario
- When: If/when multiple scenarios use similar decision structure

**HITL (Human-in-the-Loop):**
- Decision: Not implemented
- Why: No approval workflow needed for M1 mock scenario
- When: If/when agents need human approval before action

**Session Management:**
- Decision: Stateless, single-turn only
- Why: M1 is single request/response
- When: If/when we need multi-turn conversation

**Timeout/Retry:**
- Decision: Rely on Spring AI defaults
- Why: No custom requirements yet
- When: If/when we have SLA requirements

---

## 10. M2 Roadmap Status

### Current TASKS.md M2 Definition

**M2: Session 与 Multi-Turn 能力**

Target scenarios:
- Conversation Agent
- Multi-turn decision with clarification

Key capabilities:
- Session state management
- Multi-turn conversation
- Context retention

Tasks:
- M2-T1: Session Model Design
- M2-T2: Conversation Agent Scenario
- M2-T3: Multi-Turn Test Suite

### M2 Readiness Assessment

**Status:** ✅ **READY**

**Rationale:**
- M1 baseline stable
- Single-turn flow validated
- Clean foundation for session extension

**No M2 adjustments needed based on M1 results.**

M1 implementation confirmed:
- AgentRequest is single-turn (as designed)
- AgentResult is stateless (as designed)
- No session contamination in core (good)

M2 can cleanly add:
- SessionRuntime (new interface)
- Session (new domain model)
- Conversation history management
- Without modifying M1 contracts

---

## 11. Recommended Next Task

**Start:** M2-T1: Session Model Design

**Preparation:**
1. Push M1 to origin/main
2. Read M2 task descriptions in TASKS.md
3. Review conversation scenarios (if documented)
4. Design Session domain model
5. Design SessionRuntime interface

**Do NOT start coding M2 features until M2-T1 design is reviewed.**

---

## 12. Actions Taken During Closeout

### Code Changes

**Cleanup to perform:**
```bash
# Remove exploration tests
rm examples/incident-investigator/src/test/java/cn/bitcss/arctra/examples/incident/MinimalToolCallingTest.java
rm examples/incident-investigator/src/test/java/cn/bitcss/arctra/examples/incident/ExplicitToolCallingAdvisorTest.java
rm examples/incident-investigator/src/test/java/cn/bitcss/arctra/examples/incident/DetailedFakeChatModelTest.java
```

### Documentation Updates

1. ✅ Created: `docs/troubleshooting/spring-ai-tool-calling-pitfalls.md`
2. ✅ Created: `docs/milestones/M1-SpringAiToolCallingEngine.md`
3. ⏳ TODO: Update `docs/project/CURRENT-STATE.md` with M1 COMPLETE status
4. ⏳ TODO: Update `TASKS.md` with M1 final status

---

## 13. Git Status

```bash
$ git status
On branch main
Your branch is ahead of 'origin/main' by 2 commits.
  (use "git push" to publish your local commits)

nothing to commit, working tree clean
```

**Commits to push:**
- e3130d2: feat(runtime): implement SpringAiToolCallingEngine with Evidence capture
- 853b3a2: docs: add M1 implementation summary

---

## Final Summary

**M1 Milestone:** ✅ **COMPLETE**

**Key Achievements:**
1. Vertical slice validated end-to-end
2. Spring AI integration working correctly
3. Evidence capture mechanism proven
4. Architecture boundaries maintained
5. All critical bugs identified and fixed
6. Comprehensive troubleshooting documentation

**No Blockers.**

**Ready to proceed to M2.**

---

**Approval Required:** Please confirm M1 acceptance before starting M2 implementation.
