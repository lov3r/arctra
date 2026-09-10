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

### M2-T3: Multi-Turn E2E Scenario Test ✅ DONE (Root Cause Fixed, E2E Ready)

**完成日期：** 2026-08-18

**目标：** 验证完整 multi-turn conversation scenario

**实际完成：**
- ✅ Root Cause 分析并修复（使用正确的 `ChatMemory.CONVERSATION_ID` key）
- ✅ SpringAiToolCallingEngine 修复
- ✅ 最小验证测试通过（Memory + Tools 组合工作正常）
- ✅ M2-T3 E2E Test 创建（5个场景，使用真实 ChatModel）
- ⚠️ 真实 API 验证暂未执行（上游代理不可用）

**Root Cause：**
- 使用错误的字符串字面量 `"conversationId"`
- 正确应使用 `ChatMemory.CONVERSATION_ID` 常量（值为 `"chat_memory_conversation_id"`）

**关键发现：**
- MessageChatMemoryAdvisor + tools 完全兼容
- defaultAdvisors + prompt-level param 正常工作
- Conversation history 正确注入（验证通过）

**E2E 测试场景：**
1. Same Session Continuity - Turn 2 理解 Turn 1
2. Different Session Isolation - Sessions 不互相干扰
3. Session Re-entry - A → B → A 恢复 context
4. Evidence Isolation - per-execution，不累积
5. Stateless Regression - M1 behavior 保留

**测试状态：**
- @Disabled - 需要手动启用
- 编译通过：5 tests skipped
- 等待真实 API 可用后验证

**文档：**
- `docs/implementation/M2-T3-ROOT-CAUSE-ANALYSIS-REPORT.md`

**已知限制：**
- Tool message persistence 未通过真实 API 完整验证
- 同一 session 并发不支持（M3）
- 无 context compaction（M3）

**下一步：**
- 当真实 API 可用时，运行 M2-T3 验证完整行为
- 继续 M2-T4 Documentation

---

### M2-T4: Documentation & Limitations ✅ DONE

**完成日期：** 2026-08-18

**目标：** 完善 M2 用户文档和限制说明

**交付物：**
- ✅ M2 Quick Start Guide (`docs/guides/M2-MULTI-TURN-QUICK-START.md`)
- ✅ M2 Known Limitations (`docs/guides/M2-KNOWN-LIMITATIONS.md`)
- ✅ Example README 更新（multi-turn scenario）
- ✅ AgentExecutionContext Javadoc 改进
- ✅ CURRENT-STATE.md 更新

**文档统计：**
- Quick Start: 654 words, 5分钟上手
- Known Limitations: 946 words, 明确边界
- Example README: 518 words (updated with M2 scenarios)

**关键内容：**
- ✅ ChatMemory.CONVERSATION_ID 使用要求（多处强调）
- ✅ 并发、持久化、compaction 限制
- ✅ Multi-turn vs Stateless 使用场景
- ✅ 代码示例和最佳实践

---

## M2 Phase Status

**M2: Session & Multi-Turn Capability ✅ COMPLETE (2026-08-18)**

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

---

## M3: Agent API & Runtime Boundary ✅ COMPLETE (2026-08)

**目标：** 稳定公共 API，建立清晰的运行时边界

**Definition of Done:**
- ✅ Agent 公共 API 冻结（Agent, AgentDefinition, AgentRequest, AgentResult）
- ✅ AgentRuntime 抽象层
- ✅ AgentExecutionEngine 作为执行策略 seam
- ✅ 架构边界清晰（core, runtime, engine）

**关键决策：**
- Agent 是无状态的可重用句柄
- AgentRuntime 负责创建和管理 Agent 实例
- AgentExecutionEngine 是可替换的执行策略
- 核心保持框架中立

---

## M4: Process Lifecycle & Governance ✅ COMPLETE (2026-09-08)

**目标：** 建立跨同步边界的任务生命周期语义和人工批准能力

**Definition of Done:**
- ✅ AgentProcess 生命周期完整（WAITING/RUNNING/COMPLETED/FAILED）
- ✅ Dynamic Materialization（仅在需要时创建 Process）
- ✅ 治理策略（ALLOW/DENY/REQUIRE_APPROVAL）
- ✅ 人工批准的悬挂/恢复
- ✅ 会话内存跨悬挂/恢复正确性
- ✅ 失败语义完整
- ✅ 完整测试覆盖（152 tests, 0 failures）

**Tasks:**
- ✅ **M4-T1:** Process & Governance Contract Gate
  - AgentProcess 语义设计
  - Dynamic Materialization 契约
  - Process vs Session 区分
  - 治理决策模型
  - 稳定 processId 不变式

- ✅ **M4-T2:** Agent Process Lifecycle Foundation
  - DefaultAgentProcess 实现
  - ProcessStatus 状态机
  - Continuation 机制（Java 闭包）
  - Suspension/Resume 基础
  - ProcessFactory 和测试

- ✅ **M4-T3:** Spring AI Governance + Memory Closure
  - GovernanceToolCallingAdvisor 实现
  - 批量治理前置检查（DENY > REQUIRE_APPROVAL > ALLOW）
  - ToolApprovalRequiredSignal 内部控制流
  - 协议延续（messages 重放）
  - 会话内存正确性（H + U + A，无占位符污染）
  - Evidence 收集集成

- ✅ **M4-T4:** Process Failure Semantics
  - FAILED 生命周期路径
  - Resume 失败边界（continuation 异常 → FAILED）
  - 终止状态行为（FAILED 不能 resume/result）
  - 初始失败保持普通异常（不物化 Process）
  - 零公共 API 扩展（原始异常不包装）

**关键成就：**
- 任务生命周期可跨多个同步调用边界
- 人工在环的执行流程（REQUIRE_APPROVAL）
- 批量工具治理（批次级别批准）
- 稳定的进程标识（processId 跨整个生命周期）
- 会话内存跨悬挂/恢复保持连续性
- 完整失败语义（FAILED 作为终止状态）

**M4 冻结的不变式（15 条）：**
1. Agent 是可重用且无状态的
2. Process 表示一个逻辑任务执行
3. 稳定的 Process 标识是 processId
4. Process 和 Session 是独立标识
5. Process 是动态物化的
6. WAITING 是 Process 状态，不是 Assistant 内容
7. 悬挂的 Process 可以保持一个对话轮次开放
8. Governance 在执行前评估整个 ToolCall 批次
9. DENY 在批次中执行零个工具
10. REQUIRE_APPROVAL 在批准前执行零个工具
11. 实际工具执行委托给 Spring AI
12. FAILED 适用于已物化的、其延续失败的 Process
13. 初始同步失败不物化 Process
14. COMPLETED 和 FAILED 是终止状态
15. 工具协议不是持久化的对话历史

**架构文档：**
- 最终架构报告：`docs/architecture/M4-FINAL-ARCHITECTURE.md`
- 规划文档：`docs/planning/M4-*.md`
- 项目报告：`docs/project/M4-*.md`

**已知 M4 限制（设计决策）：**
- 内存 Continuation（无持久化/检查点）
- 批准粒度 = Batch（不支持单工具批准）
- 无 Retry 框架（FAILED 是终止状态）
- 无事件总线（依赖直接查询）
- 部分 Evidence 在失败时不可访问
- 失败后会话轮次保持开放（H + U）

---

## M5: Durable Suspension/Recovery ✅ COMPLETE (2026-09-09)

**目标:** 持久暂停/恢复能力，使进程可以跨 JVM 边界和运行时实例恢复执行

**Definition of Done:**
- ✅ Checkpoint-backed durable suspension
- ✅ Cross-runtime/JVM recovery
- ✅ Unified resume pipeline (CHECK A/B)
- ✅ Local handle lifecycle semantics
- ✅ Memory/Evidence/Governance continuity
- ✅ Concurrency contracts
- ✅ 237 tests, 0 failures
- ✅ M4 compatibility preserved

**Tasks:**
- ✅ **M5-T1:** Durable Process Contract Gate
  - 持久进程语义设计
  - CheckpointStore 契约
  - RuntimeBinding 模型
  - CHECK A/B 验证策略

- ✅ **M5-T2:** Durable Resume Reconstruction PoC
  - 协议重建验证
  - Spring AI messages 重放可行性

- ✅ **M5-T3:** Durable Recovery Architecture Gate
  - DurableExecutionEngine 接口设计
  - RuntimeBindingResolver 契约
  - 跨运行时恢复架构

- ✅ **M5-T4:** Durable Suspension/Recovery Implementation ✅ **FINAL GO**
  - Phase 1-10 完整实施
  - CheckpointStore + RuntimeBinding
  - DurableExecutionEngine 实现
  - SpringAiToolCallingEngine 持久能力
  - 完整测试覆盖（97 个新测试）
  - 跨运行时恢复验证（A → B → C）
  - Memory/Evidence/Governance 连续性
  - 并发冲突语义

**关键成就:**
- 持久暂停/恢复跨 JVM 边界
- RuntimeBinding 解析契约
- CHECK A（验证前置）/ CHECK B（条件转换）
- 本地句柄生命周期（WAITING/FAILED）
- 跨运行时资源权威验证
- At-least-once 工具执行语义（已记录）
- M4 ephemeral 路径完全保留

**公共 API 扩展:**
- `DurableExecutionEngine extends AgentExecutionEngine`
- `RuntimeBindingResolver`
- `CheckpointStore`（+ `InMemoryCheckpointStore` 参考实现）
- `RuntimeBinding(AgentDefinition, AgentExecutionContext)`
- `SuspensionCheckpoint`
- 6 个新异常类型
- `AgentRuntime.resumeProcess()`
- `ProcessFactory.createDurableSuspended()`

**架构文档:**
- M5 里程碑总结: `M5-MILESTONE-SUMMARY.md`
- M5-T4 实施指南: `M5-T4-IMPLEMENTATION-GUIDE.md`
- M5-T4 最终闭环报告: `M5-T4-FINAL-CLOSURE-REPORT.md`
- M5 规划文档: `docs/planning/M5-*.md`
- M5 架构文档: `docs/architecture/M5-*.md`

**已知 M5 限制（设计边界）:**
- InMemoryCheckpointStore: JVM-local 参考实现
- 无 CheckpointStore/ChatMemory 原子性（崩溃窗口）
- At-least-once 工具执行（不保证恰好一次）
- 应用程序定义的 RuntimeBindingResolver（无内置实现）
- 本地句柄是快照（可能过时）
- 无 RuntimeBindingKey 迁移
- Continuation 代码重复（M6 技术债务）

**M5 明确不声称:**
- ❌ 生产就绪的分布式持久性
- ❌ 恰好一次工具执行保证
- ❌ 原子 checkpoint/ChatMemory 事务
- ❌ 自动重试 ResumePreparationException
- ❌ RuntimeBindingResolver 序列化 Java 对象恢复

**延期到 M6+ 的工作:**
- 生产 RuntimeBinding 重建策略
- 生产 CheckpointStore 实现（JDBC, Redis）
- CheckpointStore/ChatMemory 一致性协调
- 工具去重/幂等性策略
- ResumePreparationException 自动重试框架
- RuntimeBindingKey 迁移/版本控制
- Continuation 管道整合

---

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
