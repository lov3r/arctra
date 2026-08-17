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

## 当前 Milestone：M2 Session 与 Multi-Turn 能力

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
