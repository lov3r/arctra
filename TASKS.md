# Arctra 任务队列

> WIP Limit: 同时只能有 1 个任务 IN_PROGRESS

---

## 已完成阶段

### Bootstrap Phase ✅ COMPLETE (2026-08-14)

- **BOOT-001:** Repository Bootstrap ✅
- **BOOT-002:** Agent Kernel Domain Skeleton ✅
- **BOOT-003:** 最小调用闭环 ✅
- **BOOT-004:** ExecutionEngine Contract ✅

### M1: Incident Agent MVP ✅ COMPLETE (2026-08-17)

**目标：** 完成第一个真实 Vertical Slice，验证 Arctra 核心架构在真实场景中成立

**Scenario:** Production 500 Error Spike Analysis

**Definition of Done:**
- ✅ Spring AI-based Execution Engine 实现
- ✅ Evidence 模型
- ✅ 2 个 Mock Tool (QueryLogsTool, GetDeploymentTool)
- ✅ End-to-End Test 通过
- ✅ 诊断和操作建议输出
- ✅ 完整 M1 Closeout 审计

**Tasks:**
- ✅ M1-T1: Arctra ↔ Spring AI Tool 边界设计 (2026-08-14)
- ✅ M1-T2: Evidence 领域模型 (2026-08-17)
- ✅ M1-T3: Spring AI 集成方案验证 (2026-08-17)
- ✅ M1-T4: Spring AI Chat Memory 集成 (NOT_NEEDED)
- ✅ M1-T5: Mock Tools 实现 (2026-08-17)
- ✅ M1-T6: SpringAiToolCallingEngine 实现 (2026-08-17)
- ✅ M1-T7: Incident Scenario E2E Tests (2026-08-17)
- ✅ M1-CLOSEOUT: M1 收口与架构事实固化 (2026-08-17)

**Key Deliverables:**
- SpringAiToolCallingEngine (arctra-runtime-react)
- EvidenceCapturingToolCallback (arctra-runtime-react)
- Evidence domain model (arctra-core)
- QueryLogsTool, GetDeploymentTool (examples/incident-investigator)
- Complete E2E tests (8 passing, 4 disabled manual/real API tests)
- Spring AI Tool Calling 踩坑指南 (docs/troubleshooting)

**Critical Bugs Fixed:**
1. Varargs parameter passing in .tools() method
2. ToolDefinition inputSchema requirement (Spring AI 2.0)
3. ChatModel must return ToolCallingChatOptions for capability detection

**References:**
- M1 Implementation Summary: docs/milestones/M1-SpringAiToolCallingEngine.md
- M1 CLOSEOUT Report: docs/milestones/M1-CLOSEOUT.md
- Spring AI Pitfalls Guide: docs/troubleshooting/spring-ai-tool-calling-pitfalls.md

---

## 当前 Milestone：M2 Session 与 Multi-Turn 能力 🚧 IN PROGRESS

**状态：** M2-T2 COMPLETE, M2-T3 READY

**目标：** 让 Arctra Agent 支持有 session identity 的单 agent 多轮连续对话

**核心能力：**
- Same session → conversation continuity（对话连续性）
- Different session → conversation isolation（会话隔离）

**关键架构决策（已验证）：**
- ✅ Arctra owns session semantics（AgentExecutionContext）
- ✅ Spring AI provides conversation storage（ChatMemory）
- ✅ Engine contract evolved (3-param canonical method)
- ✅ arctra-core 继续保持 framework-neutral

---

### M2-T1: Spring AI ChatMemory PoC ✅ DONE

**完成日期：** 2026-08-18

**目标：** 验证 Spring AI 2.0.0 ChatMemory 实际 API 和行为

**交付物：**
- ✅ Spring AI ChatMemory API 验证（ChatMemory interface, MessageWindowChatMemory）
- ✅ MessageChatMemoryAdvisor 验证
- ✅ conversationId 传播机制验证（advisor param）
- ✅ Session isolation 机制确认
- ✅ 文档：`docs/research/M2-T1-POC-REPORT.md`

**关键发现：**
- ChatMemory.get(conversationId) / add(conversationId, messages)
- MessageChatMemoryAdvisor 通过 advisor.param("conversationId", id) 接收 session
- MessageWindowChatMemory.builder().maxMessages(N) 配置 sliding window
- Spring AI 负责 history injection 和 persistence

---

### M2-T2: AgentExecutionContext & Session Support ✅ DONE

**完成日期：** 2026-08-18

**目标：** 实现 AgentExecutionContext 和 Engine contract evolution

**交付物：**
- ✅ `AgentExecutionContext(String sessionId)` record
  - Factory methods: `stateless()`, `withSession(String)`
  - 测试：6 tests passed
- ✅ `AgentExecutionEngine` contract evolution
  - 新增 3-param canonical method: `execute(def, req, context)`
  - 保留 2-param compatibility method (default)
  - 测试：所有 test engines 更新
- ✅ `SpringAiToolCallingEngine` session support
  - Constructor 新增 `ChatMemory` 参数
  - sessionId → conversationId 映射
  - MessageChatMemoryAdvisor 集成
  - 测试：4 tests passed
- ✅ 核心模块测试通过：62 tests (1 skipped)
- ✅ 文档：
  - `docs/design/M2-T2-AGENT-EXECUTION-CONTEXT-DESIGN.md`
  - `docs/planning/M2-T2-CONTRACT-GATE-V2.md`
  - `docs/implementation/M2-T2-IMPLEMENTATION-REPORT.md`

**关键决策：**
- sessionId 是 Execution Context（不是 Request, Definition, 或 Engine capability）
- nullable String 设计（不用 Optional 或 SessionId value object）
- ChatMemory 通过 constructor injection（shared across executions）
- Evidence collection 保持 per-execution isolation

**未创建的抽象（遵循 YAGNI）：**
- ❌ Session class
- ❌ SessionRuntime
- ❌ SessionRepository
- ❌ ArctraMessage wrapper
- ❌ Memory abstraction

**已知限制：**
- ⚠️ 同一 session 并发请求不支持（M3: session locking）
- ⚠️ 无 context compaction（M3: 考虑 Spring AI Session API）
- ⚠️ Tool call messages persistence 未通过 executable PoC 验证

**Breaking Changes：**
- M1 用户代码：零影响（default method 保护）
- M1 Engine 实现者：需要实现 3-param method
- Example tests：需要添加 ChatMemory 参数（3个文件待修复）

---

### M2-T3: Multi-Turn E2E Scenario Test 📋 READY

**依赖：** M2-T2 ✅

**目标：** 验证完整 multi-turn conversation scenario

**Acceptance Criteria：**
1. Turn 1 execution 成功
2. Turn 2 理解 Turn 1 context（conversation continuity）
3. Different sessions 完全隔离
4. Evidence 正确捕获（per-execution）
5. Tool call/response 在 history 中（需验证）

**Test Scenario：**
```java
// Turn 1
var result1 = engine.execute(
    incidentAgent,
    new AgentRequest("生产环境 500 错误"),
    AgentExecutionContext.withSession("incident-123")
);

// Turn 2
var result2 = engine.execute(
    incidentAgent,
    new AgentRequest("最可能的原因是什么？"),
    AgentExecutionContext.withSession("incident-123")
);

// Assert: Turn 2 understands Turn 1 context
// Assert: Tool calls visible in history
```

**关键验证点：**
- Multi-turn continuity
- Session isolation
- Tool message persistence
- Evidence isolation

---

### M2-T4: Documentation & Limitations 📋 BACKLOG

**依赖：** M2-T3

**目标：** 完整文档化 M2 能力和限制

**交付物：**
- M2 用户指南（如何使用 multi-turn）
- Known limitations 文档
- CURRENT-STATE.md 更新
- Example README 更新
- ADR（如果需要）

**重点说明：**
- Multi-turn 使用方式
- Session 不支持并发
- 无 context compaction
- ChatMemory lifecycle

---

**状态：** 🟡 PENDING (awaiting M1 approval and M2 kickoff)

**目标：** 在 M1 单轮能力基础上，支持有状态的多轮对话

**核心场景：**
- Conversation Agent（对话式咨询）
- Multi-turn decision with clarification（需要澄清的多轮决策）

**Definition of Done:**
- Session state management 实现
- Multi-turn conversation 支持
- Context retention across turns
- Conversation Agent scenario 验证

**Tasks (待详细设计):**
- M2-T1: Session Model Design
- M2-T2: Conversation Agent Scenario
- M2-T3: Multi-Turn Test Suite

---


---

## Backlog Milestones

### Future Milestones (待规划)

以下能力没有明确归属到具体 Milestone，后续根据优先级重新规划：

**用户 API：**
- AgentClient API
- Spring Boot Starter
- Configuration Support

**Tool Governance：**
- Tool Permission
- Tool Policy
- Tool Risk Assessment
- Tool Audit
- Tool Sandbox/Isolation
- Tool Timeout/Retry

**真实集成：**
- 真实 Tool 集成
- 真实日志系统
- 真实部署系统

**Agent Capabilities：**
- Session 管理
- Checkpoint/Resume
- HITL（Human-in-the-Loop）
- Multi-Agent
- A2A (Agent-to-Agent)

**Knowledge Agent：**
- RAG（Retriever, Reranker）
- Vector Store 集成
- Hybrid Search

**Platform：**
- Web Console
- Distributed Runtime
- Event Sourcing
- Observability Platform

**Ecosystem：**
- AgentScope Integration
- Embabel Integration
- GraphRAG

**Enterprise：**
- Full RBAC
- Multi-Tenancy
- Compliance & Audit
