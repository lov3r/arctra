# Incident Investigator Example

This example demonstrates the complete Incident Agent vertical slice with tool calling.

## Tests

### Structure Test (Always Runs)

**IncidentAgentE2EStructureTest** validates that all components can be correctly assembled:
- SpringAiToolCallingEngine + Tools integration
- Engine executes with fake ChatModel
- All components construct correctly

```bash
./mvnw test -pl examples/incident-investigator
```

**Note:** The fake ChatModel returns a simple response without actual tool calling. This validates component integration but not complete LLM-driven behavior.

### Real E2E Test Status

A real E2E test with actual OpenAI API integration is planned but currently blocked by Spring AI 2.0 client configuration complexity.

**Challenges:**
- Spring AI 2.0's OpenAI client setup requires complex credential/authentication configuration
- `OpenAiSetup.setupSyncClient()` incorrectly detects Microsoft Foundry/Azure modes
- Direct OpenAI client builder APIs are not stable in current version

**Workaround for manual testing:**
- Use the Structure Test to validate component integration
- For real LLM behavior testing, create a Spring Boot application with:
  - `spring-ai-starter-openai` dependency
  - Configuration in `application.yaml`:
    ```yaml
    spring:
      ai:
        openai:
          base-url: https://router.ezsub.com/v1
          api-key: <your-key>
          model: gpt-5.4
    ```
  - Auto-configured `ChatModel` bean

**M2 Plan:**
- Wait for Spring AI 2.0 API stabilization
- Or create a dedicated integration test module with Spring Boot context
- Or use alternative ChatModel implementation for testing

## Project Structure

```
src/
├── main/java/cn/bitcss/arctra/examples/incident/
│   └── tools/
│       ├── QueryLogsTool.java        (Mock log query)
│       └── GetDeploymentTool.java    (Mock deployment info)
└── test/java/cn/bitcss/arctra/examples/incident/
    ├── tools/
    │   ├── QueryLogsToolTest.java            (Unit tests)
    │   └── GetDeploymentToolTest.java
    ├── FakeChatModelWithToolCalling.java    (Fake for structure test)
    └── IncidentAgentE2EStructureTest.java   (Always runs - 4 tests)
```

## M1 Vertical Slice

This example validates the M1 Incident Agent MVP component integration:

```
User Question
    ↓
AgentRequest
    ↓
SpringAiToolCallingEngine
    ↓
Spring AI Tool Calling Loop (delegated to ChatModel)
    ↓
Tools (QueryLogsTool, GetDeploymentTool)
    ↓
Evidence Capture (EvidenceCapturingToolCallback)
    ↓
AgentResult(content, evidences)
```

**Validated by Structure Test:**
- ✅ All components assemble correctly
- ✅ Engine executes without crashes
- ✅ Tools work and return expected data

**Not validated in M1:**
- ❌ Real LLM-driven tool calling behavior
- ❌ Evidence capture from actual tool invocations
- ❌ Complete analysis with real reasoning

**Reason:** Spring AI 2.0 client configuration API instability. Will be addressed in M2 with proper integration testing setup.
