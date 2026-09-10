# Arctra 架构对齐审查报告

**日期:** 2026-09-09  
**审查范围:** M1-M5 已实现 + M6 规划  
**审查目的:** 确认当前实现和未来规划与初始架构目标的关系，明确架构演进方向

---

## 执行摘要

### 整体评估: ✅ **架构演进合理，需要明确长期定位**

**核心发现:**

1. ✅ **M1-M5 基础扎实** — Agent 生命周期、Runtime Contract、持久化恢复能力完整
2. ✅ **M6 规划架构合理** — ExecutionRecord/Evidence 分离、状态所有权清晰
3. ⚠️ **需要明确长期愿景** — Arctra 的最终形态是什么？
4. ✅ **核心能力优先** — 在扩展应用场景前，先把底层能力做扎实是正确的

---

## 1. 初始定位与演进方向

### 1.1 初始定位 (CLAUDE.md)

> 构建 **Arctra**：一个面向 Spring 生态的 **Agent Engineering Harness**，用统一方式**运行、测试、治理、恢复、评估和观测**不同 Agent 执行引擎。

**关键词:**
- Agent Engineering Harness
- 统一方式
- 运行、测试、治理、**恢复**、评估、观测

---

### 1.2 "恢复" 的深度问题

**浅层恢复 (M4 水平):**
```text
审批暂停 → 人工批准 → 继续执行
```

**深层恢复 (M5 实现):**
```text
进程崩溃 → 跨 JVM 恢复 → CHECK A/B 保证正确性
```

**更深层恢复 (M6 规划):**
```text
任意点失败 → 事件溯源 → 精确重放或续传
```

**问题: 哪个深度是"足够好"？**

答案取决于 Arctra 的长期愿景：

**A. Agent Harness (原始定位)**
```text
目标: 让不同 Agent 框架 (Spring AI, AgentScope, ...) 能在统一平台上运行

核心关注:
  - Agent 生命周期统一
  - 测试工具统一
  - 可观测性统一
  - 治理策略统一

恢复深度: M4-M5 水平足够
```

**B. Production Agent Runtime (演进方向)**
```text
目标: 提供生产级 Agent 运行时，支持长时间任务、跨服务协作

核心关注:
  - 可靠性 (崩溃恢复)
  - 可审计性 (完整历史)
  - 可维护性 (故障诊断)
  - 可扩展性 (分布式)

恢复深度: M6+ 水平必需
```

---

## 2. M5 实现评估

### 2.1 M5 交付能力

✅ **核心能力完整:**
- Checkpoint-backed suspension
- Cross-runtime recovery (A → B → C verified)
- RuntimeBinding resolution
- CHECK A/B validation
- Memory/Evidence/Governance continuity
- Concurrency conflict detection

✅ **架构边界清晰:**
- Core 模块无基础设施依赖
- DurableExecutionEngine 扩展点合理
- Public API 克制 (14 个新增，都有明确职责)

✅ **测试覆盖充分:**
- 237 tests, 0 failures
- 包含并发、异常、跨运行时场景

---

### 2.2 M5 的"过度"是优势还是负担？

**被认为"过度"的部分:**
- 跨 JVM 恢复 (V1 Example 可能不需要)
- RuntimeBindingResolver (复杂度高)
- CHECK A/B 并发语义 (V1 单实例可能不需要)
- 详尽文档 (8000+ 行实施指南)

**重新评估:**

**如果目标是 B (Production Runtime):**
- 这些都是**必需的基础**
- 现在做比后期重构更好
- 文档详尽是**优势**，不是负担

**类比:**
```text
Temporal.io / Cadence 的核心能力:
  - Durable execution
  - Workflow/Activity abstraction
  - Event sourcing
  - Saga pattern
  - ...

这些能力不是"为了 V1 Example"，而是"为了生产可靠性"
```

**如果 Arctra 的目标是成为 Production Agent Runtime:**
- M5 不是"过度"，而是**必要的第一步**
- M6 ExecutionRecord/Ledger 不是"偏离"，而是**架构完整性要求**

---

## 3. M6 规划评估

### 3.1 M6 核心架构决策

✅ **Event/Record/Evidence/Checkpoint 四元模型清晰:**
```text
Event      = 语义事实 (概念)
Record     = 账本条目 (持久化)
Evidence   = 执行证据 (详细数据)
Checkpoint = 恢复快照 (当前状态)
```

✅ **状态所有权明确:**
```text
ChatMemory      = 对话权威
ExecutionRecord = 执行历史权威
Evidence        = 执行证据权威
Checkpoint      = 恢复状态权威
```

✅ **Hybrid 持久化策略合理:**
```text
Snapshot (快速恢复) + Journal (完整审计) + Evidence (详细证据)
```

---

### 3.2 M6 是否"偏离"？

**原定位:** Agent Engineering Harness  
**M6 方向:** Durable Execution Engine

**关键问题: 这两者矛盾吗？**

**答案: 不矛盾，而是互补**

```text
Agent Engineering Harness 需要什么？

1. 统一运行语义 ✅ (M1-M4 已有)
2. 治理能力 ✅ (M4 已有)
3. 可测试性 ⚠️ (TestKit 尚未完成)
4. 可观测性 ⚠️ (Observability 尚未完成)
5. 可恢复性 ✅ (M5 已有)
6. 可审计性 ⚠️ (M6 ExecutionRecord 提供)

M6 不是"偏离 Harness"，而是"完善 Harness 的审计能力"
```

**类比 Kubernetes:**
```text
Kubernetes 初始定位: Container Orchestrator
演进方向: Cloud-native Runtime Platform

但 Kubernetes 没有"偏离"容器编排
而是把容器编排做到极致，成为平台
```

---

## 4. 长期愿景澄清

### 4.1 两种可能的愿景

**愿景 A: 轻量级 Harness**
```text
定位: Spring AI 的测试/治理/观测扩展

核心能力:
  - AgentClient 统一 API
  - 测试工具 (TestKit)
  - 治理策略 (Policy)
  - 可观测性 (Metrics/Tracing)
  - 基础恢复 (审批暂停)

不做:
  - 跨 JVM 恢复
  - 完整事件溯源
  - 分布式协调

适用场景:
  - 开发/测试阶段
  - 单机应用
  - 简单 Agent 任务
```

**愿景 B: 生产级 Agent Runtime**
```text
定位: Java 生态的 Temporal/Cadence (for Agents)

核心能力:
  - 持久化执行 (M5)
  - 事件溯源 (M6)
  - 分布式恢复
  - 长时间任务
  - Saga 模式
  - 多 Agent 协作

适用场景:
  - 生产环境
  - 关键业务流程
  - 长时间运行任务
  - 跨服务 Agent 编排
```

---

### 4.2 当前轨迹更接近 B

**证据:**
- M5 实现了跨 JVM 恢复 (超出轻量级 Harness 需求)
- M6 规划了 ExecutionRecord/Ledger (事件溯源基础)
- 文档详尽程度接近生产级系统
- CHECK A/B 并发语义 (分布式系统考量)

**如果目标是 B:**
- ✅ 当前方向正确
- ✅ M5/M6 是必要的基础
- ✅ 核心能力优先是正确的

**需要补充的是:**
- 明确声明长期愿景 (README / 架构文档)
- V1 Example 从"演示"升级为"真实场景"
- 早期引入生产级用户反馈

---

## 5. V1 范围重新审视

### 5.1 V1 的真正目标

**原计划:**
```text
Knowledge Assistant + Incident Investigator Example
→ 证明 Arctra 可以服务不同场景
```

**问题: Example 的深度**

**A. Demo-level Example**
```text
Knowledge Assistant:
  - 简单查询代码
  - 本地运行
  - 单次对话

Incident Investigator:
  - 模拟故障场景
  - 本地工具
  - 审批演示
```

**B. Production-like Example**
```text
Knowledge Assistant:
  - 真实项目知识库
  - 长时间索引任务
  - 多轮对话需要恢复

Incident Investigator:
  - 连接真实监控系统
  - 审批需要跨服务
  - 回滚操作需要 Saga
```

**如果目标是 B (Production Runtime):**
- V1 Example 应该是 Production-like
- M5 跨 JVM 恢复是必需的
- M6 ExecutionRecord 是必需的

---

### 5.2 V1 策略建议

**建议: 分层验证**

**V1.0 (当前 M5 基础):**
```text
目标: 验证核心 Runtime 能力

交付:
  - 完整 Runtime Contract (M1-M5)
  - 参考实现 (InMemory*)
  - 单元/集成测试
  - 架构文档

不交付:
  - Example (延期到 V1.1)
  - 生产 CheckpointStore (延期到 V1.2)
```

**V1.1 (Production-like Examples):**
```text
目标: 用真实场景验证 Runtime

交付:
  - Knowledge Assistant (真实索引任务)
  - Incident Investigator (真实故障场景)
  - Dogfooding 报告

基于:
  - M5 跨 JVM 恢复
  - M6 ExecutionRecord (如果需要审计)
```

**V1.2 (Production-ready):**
```text
目标: 生产可用

交付:
  - JDBC/Redis CheckpointStore
  - 生产 RuntimeBindingResolver
  - Observability 集成
  - Spring Boot Starter
  - 完整文档
```

---

## 6. M6 执行建议

### 6.1 不暂停 M6，但调整策略

**原 M6-T1 规划:**
```text
ExecutionRecord + Evidence Separation + EvidenceStore
→ 一次性完成所有基础设施
```

**调整后策略:**
```text
M6-T1: ExecutionRecord 最小实现
  - ExecutionRecord model
  - InMemoryExecutionLedger
  - 仅记录关键事件 (SUSPENDED, RESUMED, COMPLETED)
  - 不实现 EvidenceStore (Evidence 继续嵌入 Checkpoint)

M6-T2: Production-like Example 验证
  - 用真实场景测试 ExecutionRecord 价值
  - Dogfooding: 我们自己需要审计吗？

M6-T3: 根据反馈决定是否继续
  - 如果 ExecutionRecord 有价值 → 完成 Track A
  - 如果不够 → 评估 Track B 必要性
```

---

### 6.2 核心原则: 渐进式验证

**不是:**
```text
设计完美架构 → 一次性实现 → 希望用户需要
```

**而是:**
```text
最小可用实现 → 真实场景验证 → 根据反馈迭代
```

**M6 具体步骤:**

1. **ExecutionRecord 最小实现 (2 周)**
   - 仅核心事件类型
   - InMemory 存储
   - 基础查询 API

2. **集成到 SpringAiToolCallingEngine (1 周)**
   - 记录 SUSPENDED/RESUMED/COMPLETED
   - 不改变 Evidence 嵌入逻辑

3. **Dogfooding (1 周)**
   - 用 Arctra 自己测试
   - 审计是否有价值？
   - 查询 API 是否好用？

4. **决策点**
   - 有价值 → 继续 Track A (生产存储、Evidence 分离)
   - 价值有限 → 暂停，转向 Observability/TestKit

---

## 7. 架构演进路线图

### 7.1 已完成 (M1-M5)

```text
✅ Agent Lifecycle (M1-M2)
✅ Evidence & Decision (M3)
✅ Policy & Governance (M4)
✅ Checkpoint & Resume (M5)
✅ Cross-runtime Recovery (M5)
```

---

### 7.2 核心能力完善 (M6-M8)

**M6: Execution History (审计能力)**
```text
ExecutionRecord (最小实现)
→ 验证审计需求
→ 根据反馈决定深度
```

**M7: Observability (可观测性)**
```text
Metrics (进程状态、执行时间、失败率)
Tracing (工具调用链、决策链)
Logging (结构化日志)
```

**M8: TestKit (可测试性)**
```text
Agent 测试工具
Mock Tools
Policy 测试
恢复测试
```

---

### 7.3 场景验证 (M9-M10)

**M9: Production-like Examples**
```text
Knowledge Assistant (真实索引)
Incident Investigator (真实故障)
Dogfooding 报告
```

**M10: Production Infrastructure**
```text
JDBC CheckpointStore
Redis CheckpointStore
Production RuntimeBindingResolver
Spring Boot Starter
```

---

### 7.4 扩展能力 (V2+)

**V2: Multi-Engine**
```text
AgentScope Integration
Spring AI Alibaba Graph Integration
```

**V3: Multi-Agent**
```text
A2A Protocol
Agent Orchestration
```

---

## 8. 关键原则重申

### 8.1 核心能力优先

**✅ 当前策略正确:**
```text
M1-M5: 把 Runtime 基础做扎实
M6-M8: 把核心能力做完整
M9-M10: 场景验证 + 生产化
V2+: 扩展能力
```

**而不是:**
```text
M1-M4: 基础能力
M5: 快速 Example
M6: 接入 AgentScope
M7: Multi-Agent
→ 基础不稳，后期重构代价大
```

---

### 8.2 架构完整性优先

**M6 ExecutionRecord 是架构完整性要求**

**当前状态:**
```text
Evidence = 工具执行记录
Checkpoint = 当前状态快照

缺失: 生命周期事件历史
  - 谁批准了？
  - 何时暂停？
  - 是否发生冲突？
  - 何时完成？
```

**如果目标是 Production Runtime:**
- 这些历史不能丢失
- ExecutionRecord 不是"过度设计"
- 而是"基础设施必需"

---

### 8.3 渐进式验证

**不是预测未来需求，而是用真实场景验证当前设计**

**M6 调整:**
```text
不是: 一次性实现 ExecutionRecord + EvidenceStore + JDBC + Redis
而是: 最小 ExecutionRecord → Dogfooding → 决策下一步
```

---

## 9. 与初始架构的关系

### 9.1 未偏离核心使命

**核心使命:**
> 用统一方式运行、测试、治理、**恢复**、评估和观测不同 Agent 执行引擎

**当前进展:**
```text
运行 ✅ (AgentRuntime)
测试 ⚠️ (TestKit 未完成)
治理 ✅ (Policy)
恢复 ✅ (Checkpoint) → M5 深化
评估 ⚠️ (Evaluation 未完成)
观测 ⚠️ (Observability 未完成)
```

**"恢复"深化不是偏离，而是把核心能力做扎实**

---

### 9.2 架构宪法仍然有效

**宪法第 2 条:**
> 内核掌握运行语义，Provider 提供能力

**M6 符合:**
```text
ExecutionRecord = 运行语义 (生命周期事件)
CheckpointStore = Provider (InMemory / JDBC / Redis)
```

**宪法第 4 条:**
> arctra-core 不得依赖 Spring Boot / Redis / ...

**M6 符合:**
```text
ExecutionRecord / ExecutionLedger 在 core
InMemoryExecutionLedger 在 core
JdbcExecutionLedger 在 infra 模块 (未来)
```

---

## 10. 最终建议

### 10.1 继续 M6，但策略调整

✅ **继续 ExecutionRecord 实现**
✅ **但采用最小可用 + 渐进验证策略**
✅ **Dogfooding 决定深度**

---

### 10.2 明确长期愿景

✅ **在 README 明确声明:**
```text
Arctra 的长期目标:
  成为 Java 生态的 Production Agent Runtime
  
V1: 验证核心 Runtime 能力
V2: 多引擎支持
V3: 多 Agent 编排
```

---

### 10.3 平衡核心能力与应用场景

**当前策略:**
```text
M1-M5: 核心能力 ✅
M6-M8: 核心能力完善 ✅
M9-M10: 场景验证 + 生产化 ⚠️ 需要提前

调整:
M6: ExecutionRecord (最小)
M7: Production-like Example (提前验证)
M8: 根据 M7 反馈决定 M6 深度
```

---

## 11. 结论

### ✅ **架构演进合理，需要明确长期定位**

**M1-M5 基础扎实:**
- Agent 生命周期完整
- Runtime Contract 清晰
- 持久化恢复能力生产级

**M6 规划架构合理:**
- ExecutionRecord 是架构完整性要求
- 状态所有权清晰
- Hybrid 持久化策略合理

**需要调整的是策略，不是方向:**
- 最小可用实现
- 尽早 Dogfooding
- 渐进式验证

**核心问题不是"是否偏离"，而是"是否声明愿景":**
- 如果 Arctra 要成为 Production Agent Runtime
- 那么 M5/M6 是正确的方向
- 需要在文档中明确声明

---

**下一步: 创建 Vision Statement**

```text
docs/VISION.md

明确:
  - Arctra 的长期目标
  - 与 Temporal/Spring AI/AgentScope 的差异化
  - V1/V2/V3 的演进路线
  - 为什么核心能力优先
```

---

**@author lov3r**
