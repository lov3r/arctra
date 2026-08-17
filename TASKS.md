# Arctra 任务队列

> WIP Limit: 同时只能有 1 个任务 IN_PROGRESS

---

## 已完成阶段

### Bootstrap Phase ✅ COMPLETE (2026-08-14)

- **BOOT-001:** Repository Bootstrap ✅
- **BOOT-002:** Agent Kernel Domain Skeleton ✅
- **BOOT-003:** 最小调用闭环 ✅
- **BOOT-004:** ExecutionEngine Contract ✅

---

## 当前 Milestone：M1 Incident Agent MVP

**目标：** 完成第一个真实 Vertical Slice，验证 Arctra 核心架构在真实场景中成立

**Scenario:** Production 500 Error Spike Analysis

**Definition of Done:**
- ✅ Spring AI-based Execution Engine 实现（命名待验证）
- ✅ Evidence 模型（Framework 通用语义）
- ✅ 至少 2 个 Mock Tool（examples 或 test fixtures）
- ✅ End-to-End Test 通过
- ✅ 诊断和操作建议输出（无生产 DDL）
- ✅ Dogfooding 验证 + 抽象清理 Review

---

### M1-T1: Arctra ↔ Spring AI Tool 边界设计

**状态：** COMPLETE ✅
**估算：** 0.5 天
**依赖：** 无
**完成日期：** 2026-08-14

**目标：** 明确 Arctra Tool Contract 与 Spring AI ToolCallback 的边界

**交付物：**
- ✅ 设计文档（docs/design/M1-T1-tool-boundary.md）
- ✅ 明确 M1 不创建 Arctra Tool Contract
- ✅ 使用 Spring AI 2.0 术语（ToolCallback / ToolCallbackProvider / ToolCallingAdvisor）
- ✅ 明确未来 Governance 不属于 Engine 实现

**关键结论：**
- M1 直接使用 Spring AI ToolCallback，不创建 Arctra Tool Contract
- Evidence 收集位置延后到 M1-T3 验证（Advisor / ToolCallback Wrapper / Engine）
- 未来 Governance 位于独立层（Engine 与 Tool 之间），不是每个 Engine 自己实现
- Mock Tools 当前不依赖 arctra-core（仅作为 M1 事实，非长期规则）

**Acceptance Criteria:**
- [x] 输出设计文档
- [x] 使用 Spring AI 2.0 当前术语
- [x] 明确 Arctra 增加的语义（Evidence 待验证位置）
- [x] 明确未来 Governance 架构位置（独立层，非 Engine）
- [x] 不提前创建 Permission/Risk/Audit 模型

---

### M1-T2: Evidence 领域模型

**状态：** COMPLETE ✅
**估算：** 0.5 天
**依赖：** 无
**完成日期：** 2026-08-17

**目标：** 创建 Evidence 模型（Framework 通用语义）

**交付物：**
- ✅ Evidence record（arctra-core/evidence）
- ✅ AgentResult 扩展（添加 evidences 字段）
- ✅ 单元测试（EvidenceTest + AgentResultTest）
- ✅ 向后兼容（原有 new AgentResult(content) 调用继续工作）

**关键决策：**
- Evidence 是 Framework 通用语义（适用所有 Agent 场景）
- Evidence 只包含可观察、可记录、可引用的执行事实（Tool Result、Retrieval Result、External System Result、Human Input、结构化 Model Output）
- Evidence 不包含 private model reasoning / chain-of-thought
- Evidence.source 当前使用 String（不冻结为正式 protocol）
- Evidence 不包含 timestamp（M1 不需要，等真实需求）
- Decision 暂不创建 Framework-level Contract（只有 Incident 一个消费者，通用 Contract 未被多场景验证）
- diagnosis/recommendations 属于 Incident 场景输出（不在 arctra-core）
- riskLevel/requiresApproval 延后到 Policy/HITL

**Acceptance Criteria:**
- [x] Evidence record 创建（source + content）
- [x] Evidence 不包含 timestamp
- [x] Evidence 不包含 private reasoning
- [x] Evidence invariants 测试通过
- [x] AgentResult 包含 evidences 字段
- [x] AgentResult 防御性拷贝
- [x] AgentResult 向后兼容
- [x] 单元测试：40 tests pass
- [x] ./mvnw clean verify 通过

---

### M1-T3: Spring AI 集成方案验证

**状态：** COMPLETE ✅
**估算：** 1 天
**依赖：** M1-T1
**完成日期：** 2026-08-17

**目标：** 验证 Spring AI ChatClient + ToolCallingAdvisor 是否满足需求

**交付物：**
- ✅ 添加 spring-ai-client-chat 依赖到 arctra-runtime-react
- ✅ 验证 Spring AI 2.0 实际 API (ChatClient, ToolCallback, ToolDefinition)
- ✅ 验证 Evidence capture 最佳位置 (ToolCallback Wrapper)
- ✅ 对比报告（复用 Spring AI Loop vs 自建）
- ✅ PoC 代码验证

**关键结论：**
- 推荐方案 A：完全复用 Spring AI Tool Calling Loop
- Evidence capture 位置：ToolCallback Wrapper (Engine 内部)
- ToolCallingAdvisor 是递归 Advisor，负责自动化 Loop
- Mock ChatModel 可行，可用于 M1-T7 E2E Test
- 不需要手动管理 Loop，不需要自建 Tool Request 解析

**Acceptance Criteria:**
- [x] 验证 Spring AI 2.0 API 存在性
- [x] 验证 ToolCallback / ToolCallbackProvider / ToolCallingAdvisor
- [x] 验证 Evidence capture 可行性（ToolCallback Wrapper 证明）
- [x] 输出推荐方案（方案 A）
- [x] PoC 代码验证（6 tests pass）

---

### M1-T4: Arctra Tool Contract 实现

**状态：** NOT_NEEDED ✅
**估算：** 0.5 天（未实施）
**依赖：** M1-T1
**决策日期：** 2026-08-17

**决策：** M1 阶段不创建 Arctra Tool Contract

**理由：**
- M1-T1 设计结论：M1 直接使用 Spring AI ToolCallback
- M1-T3 PoC 验证：Spring AI ToolCallback 满足 M1 需求
- 当前不存在 Arctra-specific Tool Contract 的真实消费者
- M1 只有 2 个 Mock Tools，不需要抽象
- 未来如果需要（Governance / Multi-Engine），可以在真实需求出现时创建

**交付物：**
- ✅ 设计决策（记录在 M1-T1 和 M1-T3 文档中）
- ✅ 不创建任何代码（正确决策）

**Acceptance Criteria:**
- [x] M1-T1 和 M1-T3 已明确不需要 Arctra Tool Contract
- [x] 决策轨迹已记录

---

### M1-T5: Mock Tools 实现

**状态：** COMPLETE ✅
**估算：** 0.5 天
**依赖：** M1-T3
**完成日期：** 2026-08-17

**目标：** 实现 2 个 Mock Tool（Incident Scenario 专属）

**范围：**
- QueryLogsTool：返回固定 Mock 日志
- GetDeploymentTool：返回固定 Mock 部署信息
- 放在 examples/incident-investigator
- 直接实现 Spring AI ToolCallback

**交付物:**
- ✅ QueryLogsTool
- ✅ GetDeploymentTool
- ✅ 单元测试（6 tests pass）

**关键决策：**
- 直接实现 Spring AI ToolCallback（不创建 Arctra Tool Contract）
- 放在 examples/incident-investigator（Scenario fixture，非 Framework capability）
- 空 input properties（M1 不需要参数化）
- Mock 数据内部常量（不需要 fixture framework）
- 测试 Contract + Deterministic + Scenario Facts

**Acceptance Criteria:**
- [x] 两个 Tool 在 examples/incident-investigator/tools
- [x] 直接实现 Spring AI ToolCallback
- [x] 返回固定 Mock 数据
- [x] 单元测试覆盖（Contract + Deterministic + Scenario Facts）
- [x] ./mvnw test -pl examples/incident-investigator 通过（6/6 tests）
- [x] ./mvnw clean verify 通过（全项目）
- [x] 不创建 Arctra Tool Contract
- [x] 不创建 Registry/Provider/Adapter
- [x] arctra-core 保持纯 Java

---

### M1-T6: Spring AI-based Execution Engine 实现

**状态：** BACKLOG
**估算：** 2 天
**依赖：** M1-T2, M1-T3, M1-T5

**目标：** 在 arctra-runtime-react 实现 Execution Engine

**范围：**
- 实现 AgentExecutionEngine 接口
- 基于 M1-T3 的推荐方案（复用 Spring AI or 自建）
- 收集 Evidence（Tool 调用记录）
- 返回 AgentResult（包含 Evidence）
- 使用 M1-T3 验证的命名（可能不是 NativeReActEngine）

**交付物：**
- Spring AI-based Engine（实现 AgentExecutionEngine）
- Evidence 收集机制
- 单元测试

**Acceptance Criteria:**
- [ ] 实现 AgentExecutionEngine 接口
- [ ] 基于 Spring AI ChatClient
- [ ] 收集 Evidence
- [ ] 返回 AgentResult
- [ ] 命名准确反映实际机制
- [ ] 单元测试覆盖（Mock Model/Tool）

---

### M1-T7: Incident Scenario E2E Test

**状态：** BACKLOG
**估算：** 1 天
**依赖：** M1-T6

**目标：** 验证完整 Incident Scenario

**范围：**
- 创建 IncidentAgentE2ETest
- 使用 Mock Model
- 使用 Mock Tools（QueryLogsTool, GetDeploymentTool）
- 验证输出：
  - Evidence 包含 Tool 调用记录
  - 诊断和操作建议
  - 无生产 DDL

**Scenario 输出示例：**
```
Root Cause Analysis:
  Database schema migration missing for user_status field

Evidence:
  1. [QueryLogsTool] 16:20 开始出现 SQLException: Unknown column 'user_status'
  2. [GetDeploymentTool] 16:18 部署 v1.2.3，代码新增 user_status 字段

Diagnosis:
  Schema drift between application code and database

Recommended Actions (requires approval):
  - Option 1: Execute schema migration (requires DBA review)
  - Option 2: Rollback to v1.2.2 (requires impact assessment)
```

**交付物：**
- IncidentAgentE2ETest
- 可重复运行的测试

**Acceptance Criteria:**
- [ ] 输入 Incident Question
- [ ] Agent 调用 queryLogs → getDeployment
- [ ] 输出包含 Evidence
- [ ] 输出包含诊断和操作建议
- [ ] 无生产 DDL 执行
- [ ] 操作建议标注需要审批
- [ ] 测试可重复运行

---

### M1-T8: Documentation, Dogfooding 和抽象清理

**状态：** BACKLOG
**估算：** 1 天
**依赖：** M1-T7

**目标：** 完成 M1 文档、Dogfooding 验证和抽象清理

**范围：**
- 更新 CURRENT-STATE.md（M1 完成报告）
- 更新 README（M1 能力描述）
- 创建 examples/incident-investigator 示例代码
- Dogfooding 验证：用 Arctra 自己解决一个真实问题
- 检查 V1-QUALITY-GATE.md（如果存在）
- Review：哪些抽象可以删除？哪些过度设计了？

**交付物：**
- 更新 CURRENT-STATE.md
- 更新 README
- examples/incident-investigator
- Dogfooding 报告
- 抽象清理清单

**Acceptance Criteria:**
- [ ] CURRENT-STATE.md M1 完成报告
- [ ] README 描述 M1 能力
- [ ] 示例代码可运行
- [ ] Dogfooding 验证通过（用 Arctra 解决一个问题）
- [ ] V1-QUALITY-GATE.md 检查通过（如果存在）
- [ ] 抽象清理 Review 完成（识别过度设计）

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
