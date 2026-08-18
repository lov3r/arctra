# Arctra 架构文档索引

**最后更新：** 2026-08-18

---

## 架构指南文档

本目录包含 Arctra 的架构演进指导文档，旨在建立长期架构决策的判断标准。

### 核心原则

**Arctra 不是：**
- 通用 Agent Framework 的重新实现
- Java 版的 LangChain / AgentScope / LangGraph
- Spring AI 的镜像包装层

**Arctra 是：**
- 基于 Spring AI 基础能力的 **Agent Runtime / Agent Platform**
- 专注于 Agent 生命周期、治理、可观测性和企业集成

---

## 文档列表

### 1. [Architecture Evolution Guide](./EVOLUTION-GUIDE.md)

**核心问题：** 何时以及为什么创建新抽象？

**主要内容：**
- Arctra 的长期定位与核心原则
- 四层架构关系（Spring AI / Spring AI Alibaba / AgentScope / Arctra）
- 三层 API 架构方向（User API / Runtime Contracts / Integration）
- 10 个候选抽象的触发条件（Tool Contract / Registry / Governance / Model / etc.）
- 成熟框架借鉴原则
  - **Spring AI** — 基础 AI 编程抽象，优先直接使用
  - **Spring AI Alibaba** — Agent 工程化能力来源，优先复用实现
  - **AgentScope** — 架构设计参考，学习设计思想
- 四层判断法（Spring AI → Spring AI Alibaba → AgentScope → 平台能力）
- Spring AI Alibaba 四种处理策略（Direct Use / Composition / Adapter / Reimplementation）
- Graph / Workflow 不重复造轮子原则
- 依赖框架但不被框架反向绑架
- 技术雷达（Adopt / Assess / Trial / Hold）
- 防重复建设检查清单
- M1 边界确认
- 反模式识别
- 每个 Milestone 的设计方法

**适用场景：**
- 准备创建新的核心抽象时
- 设计新的 Milestone 时
- 评估是否需要引入新的框架能力时
- 判断某个能力是否属于 Arctra 职责时
- 评估 Spring AI Alibaba 能力时

**关键原则：**
> 不为了屏蔽底层框架而抽象，只抽象那些具有独立领域语义、独立生命周期，或者确实需要平台治理的概念。

> Spring AI 是基础设施，不重新包装。Spring AI Alibaba 是能力来源和工程实践参考，优先复用。AgentScope 是 Agent Runtime 架构参考，重点学习其设计思想。本项目只重点建设拥有独立领域价值和企业治理价值的能力。

---

### 2. [Skill / Experience Evolution](./SKILL-EXPERIENCE-EVOLUTION.md)

**核心问题：** 如何从执行历史中学习并沉淀可复用能力？

**主要内容：**
- Tool vs Skill 的明确区分
- 从执行到经验的四阶段生命周期
  - Execution Trace（执行轨迹）
  - Experience Candidate（经验候选）
  - Validated Experience（已验证经验）
  - Skill / Playbook（技能 / 剧本）
- 防止经验污染的机制
- Skill 的长期形态（Definition / Execution / Registry）
- 何时引入 Skill / Experience
- 与 AgentScope 的借鉴关系
- 实现路径（Phase 1-4）

**适用场景：**
- 观察到重复成功模式时
- 需要跨 Agent 复用能力时
- 设计 Execution Observability 时
- 评估是否需要 Skill 框架时

**关键原则：**
> Experience 需要多次验证，不是一次成功就固化。

**当前状态：**
- M1 不实现任何 Skill / Experience 能力
- 等待至少 2 个 Agent Scenario 运行后再考虑

---

### 3. [Tool / Skill Boundary](./TOOL-SKILL-BOUNDARY.md)

**核心问题：** Tool 和 Skill 的边界在哪里？

**主要内容：**
- Tool（原子能力）vs Skill（组合能力）
- Tool Governance vs Skill Governance
- 不要混淆的概念
  - Tool Definition ≠ Skill Definition
  - Tool Permission ≠ Skill Permission
  - Agent Tool Binding ≠ Skill Assignment
- 同一个 Tool，不同 Agent 的不同治理要求
- 风险不是 Tool 的固有属性
- 未来可能的架构（方向，非实现）
- 何时引入 Skill

**适用场景：**
- 设计 Tool 相关能力时
- 评估是否需要 Tool Governance 时
- 判断某个概念属于 Tool 还是 Skill 时
- 设计 Policy / Permission 时

**关键原则：**
> Tool 提供原子能力，Skill 提供解决方案。  
> 不要把 Tool Definition 和 Agent Governance 混成一个对象。  
> 风险是 context-dependent 的，不是 Tool 的固有属性。

**当前状态：**
- M1 有 Tool（Spring AI ToolCallback）
- M1 没有 Skill
- M1 没有 Tool Governance

---

## 使用指南

### 准备创建新抽象时

1. **首先阅读** [EVOLUTION-GUIDE.md](./EVOLUTION-GUIDE.md) 第 6 节"抽象触发条件"
2. **回答检查清单** 中的 10 个问题
3. **确认** Spring AI 是否已经解决
4. **确认** AgentScope 等成熟框架是否已经解决
5. **确认** 是否有真实消费者和至少 2 个实现

### 设计新的 Milestone 时

1. **阅读** [EVOLUTION-GUIDE.md](./EVOLUTION-GUIDE.md) 第 7 节"每个 Milestone 的设计方法"
2. **遵循** 8 步设计流程：Scenario → Execution Path → Ownership → Leak Detection → Abstraction Test → Minimum Change → Future Hook → Acceptance Test
3. **避免** 第 10 节列出的 5 个反模式

### 研究成熟框架时

1. **阅读** [EVOLUTION-GUIDE.md](./EVOLUTION-GUIDE.md) 第 8 节"成熟框架借鉴原则"
2. **应用** 三层判断法：Spring AI → Framework → Platform
3. **确认** 是否属于 Arctra 真正应该建设的平台能力

### 设计 Skill / Experience 时

1. **阅读** [SKILL-EXPERIENCE-EVOLUTION.md](./SKILL-EXPERIENCE-EVOLUTION.md)
2. **确认** 是否满足触发条件（至少 2 个 Agent Scenario，同类任务重复 5 次以上）
3. **遵循** 四阶段生命周期：Trace → Candidate → Validated → Skill
4. **实施** 防经验污染机制

### 设计 Tool 相关能力时

1. **阅读** [TOOL-SKILL-BOUNDARY.md](./TOOL-SKILL-BOUNDARY.md)
2. **明确** 当前设计的是 Tool 还是 Skill
3. **区分** Tool Governance 和 Skill Governance
4. **避免** 把风险固化到 Tool Definition

---

## 关键决策原则

### 1. 需求驱动 vs 架构驱动

**正确：**
```
真实需求 → 识别重复模式 → 提炼领域概念 → 最后抽象
```

**错误：**
```
看到 AgentScope 有某种设计 → 立即模仿 → 提前创建抽象
```

### 何时复用 vs 何时自建

**复用 Spring AI：**
- ChatModel / EmbeddingModel / Message / ToolCallback
- Tool Calling Loop
- VectorStore / MCP
- Advisor / Prompt Template
- Observation / Metrics

**复用 Spring AI Alibaba：**
- Agent Framework / Graph / Workflow
- Multi-Agent 协作
- MCP 集成
- RAG 增强能力
- DashScope / Qwen Provider
- 分布式 Agent 能力

**自建 Arctra：**
- Agent 生命周期（版本、发布、回滚、审批）
- Tool Governance（Permission / Risk / Approval / Audit）
- Skill / Experience（成功模式沉淀）
- Policy / HITL（动态治理）
- Execution Trace / Checkpoint / Resume
- 企业集成（Spring Security / Multi-tenancy / RBAC）

### 3. 何时创建抽象 vs 何时等待

**立即创建（当前 M1）：**
- ✅ 当前真实需要
- ✅ 已有至少 1 个真实消费者
- ✅ 不创建就做不了

**等待验证（M2+）：**
- ❌ 未来可能需要
- ❌ 为了架构纯洁性
- ❌ 只是重命名外部框架

---

## 架构演进检查清单

在任何架构决策前，回答：

**基础能力层：**
- [ ] Spring AI 是否已经提供？
- [ ] Spring AI Alibaba 是否已经提供？

**通用框架层：**
- [ ] AgentScope 或其他成熟框架是否已经提供？
- [ ] 这是基础框架能力，还是企业 Agent Platform 能力？

**领域价值层：**
- [ ] 是否具有独立领域语义？
- [ ] 是否具有独立生命周期？
- [ ] 是否存在治理需求（权限、审计、版本、租户）？

**必要性层：**
- [ ] 如果删除这一层，业务是否真的会受到影响？
- [ ] 当前需求是否真实存在？
- [ ] 能否先直接使用 Spring AI / Spring AI Alibaba，等第二个场景出现后再抽象？
- [ ] 未来加入这个抽象是否会很困难？

---

## 当前 M1 架构确认

### 已有（合理）

- ✅ arctra-core 保持纯 Java
- ✅ Spring AI 依赖限制在 arctra-runtime-react
- ✅ Evidence 是 framework-neutral
- ✅ AgentExecutionEngine 是 public extension contract
- ✅ Examples 直接实现 Spring AI ToolCallback

### 不存在（未来）

- ❌ Arctra Tool Contract
- ❌ Tool Governance（Permission / Risk / Approval）
- ❌ Skill / Experience / Playbook
- ❌ Model Abstraction
- ❌ AgentClient / Facade
- ❌ Execution Trace
- ❌ Policy / HITL

**原因：M1 只有 1 个 Scenario，没有真实需求。**

---

## 版本历史

- **v1.0 (2026-08-18)** — 初始版本，包含 EVOLUTION-GUIDE / SKILL-EXPERIENCE-EVOLUTION / TOOL-SKILL-BOUNDARY

---

**Built with Spring. Designed for Production.**
