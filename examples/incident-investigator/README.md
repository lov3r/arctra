# Incident Investigator Example

演示 Arctra Agent 如何分析生产环境故障。

## Scenario

生产环境从 16:20 开始出现大量 500 错误。Agent 需要：
1. 查询日志（queryLogs）
2. 查询部署记录（getDeployment）
3. 关联时间线，定位根本原因

## Agent 定义

- **Name:** Incident Investigator
- **Description:** 你是一个生产故障分析专家
- **Tools:** QueryLogsTool, GetDeploymentTool

---

## Tests

### M1: Single-Turn E2E

**Test:** `IncidentAgentRealE2ETest` (Spring Boot + Real API)

单轮对话：
```java
engine.execute(
    incidentAgent,
    new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因")
);
```

Agent 完成：
- ✅ 调用 queryLogs 获取日志
- ✅ 调用 getDeployment 获取部署信息
- ✅ 返回根本原因分析（v1.2.3 schema mismatch）
- ✅ Evidence 包含 tool 调用记录

**状态:** `@Disabled` - 需要手动启用

---

### M2: Multi-Turn E2E (NEW)

**Test:** `IncidentAgentMultiTurnE2ETest` (Manual Build + Real API)

多轮对话示例：
```java
var context = AgentExecutionContext.withSession("incident-123");

// Turn 1: 初始问题
var result1 = engine.execute(
    incidentAgent,
    new AgentRequest("生产环境从 16:20 开始出现大量 500 错误，请分析原因"),
    context
);
// Agent 调用 tools，返回初步分析

// Turn 2: 追问（无需重复上下文）
var result2 = engine.execute(
    incidentAgent,
    new AgentRequest("那最可能的原因是什么？"),  // "那" 指代 Turn 1
    context
);
// Agent 理解上下文，继续分析
```

**验证场景：**
1. **Same Session Continuity** - Turn 2 理解 Turn 1 context
2. **Different Session Isolation** - 不同 session 独立
3. **Session Re-entry** - A → B → A 恢复 context
4. **Evidence Isolation** - Evidence 是 per-execution
5. **Stateless Regression** - M1 行为保留

**M2 特性：**
- ✅ Session continuity - 多轮对话记住上下文
- ✅ Session isolation - 不同 session 互不干扰
- ✅ Evidence per-execution - Evidence 不累积
- ✅ Stateless backward compatible - M1 行为保留

**状态:** `@Disabled` - 需要手动启用（需要真实 API）

---

## Multi-Turn 使用指南

### 启用 Multi-Turn

```java
// 创建 ChatMemory
var chatMemory = MessageWindowChatMemory.builder()
    .maxMessages(20)
    .build();

// 创建 Engine（注入 ChatMemory）
var engine = new SpringAiToolCallingEngine(
    chatModel,
    List.of(new QueryLogsTool(), new GetDeploymentTool()),
    chatMemory
);

// 使用 session 执行
var context = AgentExecutionContext.withSession("incident-123");
engine.execute(agentDef, request, context);
```

### Stateless 执行（M1 行为）

```java
// 不提供 context，或使用 stateless()
engine.execute(agentDef, request);  // Stateless
engine.execute(agentDef, request, AgentExecutionContext.stateless());  // 显式
```

### 更多信息

- Quick Start: [M2 Multi-Turn Quick Start Guide](../../docs/guides/M2-MULTI-TURN-QUICK-START.md)
- Known Limitations: [M2 Known Limitations](../../docs/guides/M2-KNOWN-LIMITATIONS.md)

---

## Project Structure

```
src/
├── main/java/cn/bitcss/arctra/examples/incident/
│   └── tools/
│       ├── QueryLogsTool.java        (Mock log query)
│       └── GetDeploymentTool.java    (Mock deployment info)
└── test/java/cn/bitcss/arctra/examples/incident/
    ├── tools/
    │   ├── QueryLogsToolTest.java
    │   └── GetDeploymentToolTest.java
    ├── IncidentAgentRealE2ETest.java          (M1 single-turn)
    └── IncidentAgentMultiTurnE2ETest.java     (M2 multi-turn, NEW)
```

---

## M1 Vertical Slice

This example validates the M1 Incident Agent MVP:

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

---

## M2 Multi-Turn Extension

M2 adds conversation continuity:

```
Turn 1:
  AgentRequest + AgentExecutionContext(sessionId)
    ↓
  SpringAiToolCallingEngine
    ↓
  ChatMemory.get(sessionId) → [] (empty)
    ↓
  Spring AI Tool Calling Loop
    ↓
  ChatMemory.add(sessionId, [user1, assistant1])

Turn 2:
  AgentRequest + AgentExecutionContext(sessionId)  // Same sessionId
    ↓
  ChatMemory.get(sessionId) → [user1, assistant1]  // History injected
    ↓
  Spring AI Tool Calling Loop (with history)
    ↓
  ChatMemory.add(sessionId, [user2, assistant2])
```

---

## Running Tests

```bash
# All tests (structure + E2E, E2E tests disabled by default)
./mvnw test -pl examples/incident-investigator

# Enable E2E tests manually (requires API key)
# Edit test class, remove @Disabled annotation, then:
./mvnw test -pl examples/incident-investigator -Dtest=IncidentAgentMultiTurnE2ETest
```

---

**Example Complete** ✅

See also:
- [M2 Multi-Turn Quick Start](../../docs/guides/M2-MULTI-TURN-QUICK-START.md)
- [M2 Known Limitations](../../docs/guides/M2-KNOWN-LIMITATIONS.md)
