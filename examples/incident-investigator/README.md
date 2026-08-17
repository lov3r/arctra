# Incident Investigator Example

This example demonstrates the complete Incident Agent vertical slice with tool calling.

## Tests

### 1. Structure Test (Always Runs)

**IncidentAgentE2EStructureTest** validates that all components can be correctly assembled:
- SpringAiToolCallingEngine + Tools integration
- Engine executes with fake ChatModel
- All components construct correctly

```bash
./mvnw test -pl examples/incident-investigator
```

**Note:** The fake ChatModel returns a simple response without actual tool calling. For real LLM-driven tool calling, see the Real E2E test below.

### 2. Real E2E Test (Manual - Requires API Key)

**IncidentAgentRealE2ETest** uses a real ChatModel to validate complete tool calling with LLM reasoning.

**Requirements:**
- Network access
- OpenAI-compatible API (via proxy)

**To run:**

1. **Remove @Disabled annotation** from `IncidentAgentRealE2ETest.java`

2. **Run the test:**
   ```bash
   ./mvnw test -pl examples/incident-investigator -Dtest=IncidentAgentRealE2ETest
   ```

**Configuration:**
- Base URL: `https://router.ezsub.com/v1`
- API Key: (already configured in test)
- Model: `gpt-5.4`

### Expected Real E2E Behavior

The Agent should:
1. Call `queryLogs` → discover SQLException with 'user_status' column
2. Call `getDeployment` → discover v1.2.3 deployment at 16:18
3. Analyze correlation → diagnose schema drift as root cause

The test verifies:
- Response contains key analysis (user_status, deployment info, schema-related issue)
- Evidences captured (2+ tool invocations)
- Evidence content matches mock data

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
    ├── IncidentAgentE2EStructureTest.java   (Always runs)
    └── IncidentAgentRealE2ETest.java        (Manual - requires API)
```

## M1 Vertical Slice

This example validates the complete M1 Incident Agent MVP:

```
User Question
    ↓
AgentRequest
    ↓
SpringAiToolCallingEngine
    ↓
Spring AI Tool Calling Loop
    ↓
Tools (QueryLogsTool, GetDeploymentTool)
    ↓
Evidence Capture (EvidenceCapturingToolCallback)
    ↓
AgentResult(content, evidences)
```
