# Arctra M5 Milestone Summary

**状态:** ✅ **COMPLETE** (2026-09-09)

**里程碑目标:** 持久暂停/恢复能力 (Durable Suspension/Recovery Capability)

---

## 1. 总体目标

为 Arctra Agent 添加持久暂停/恢复能力，使进程可以跨 JVM 边界和运行时实例恢复执行。

**核心能力:**
- Checkpoint-backed durable suspension（基于 checkpoint 的持久暂停）
- Cross-runtime/JVM recovery（跨运行时/JVM 恢复）
- RuntimeBinding resolution contract（运行时绑定解析契约）
- CHECK A/B validation fencing（CHECK A/B 验证围栏）
- Memory/Evidence/Governance continuity（内存/证据/治理连续性）

---

## 2. M5 任务结构

### M5-T1: Durable Process Contract Gate ✅ COMPLETE
**完成日期:** 2026-09-08

**交付物:**
- 持久进程语义设计
- CheckpointStore 契约
- RuntimeBinding 模型
- CHECK A/B 验证策略
- 文档: `docs/planning/M5-T1-DURABLE-PROCESS-CONTRACT-GATE.md`

### M5-T2: Durable Resume Reconstruction PoC ✅ COMPLETE
**完成日期:** 2026-09-08

**交付物:**
- 协议重建验证
- Spring AI messages 重放可行性
- 文档: `docs/research/M5-T2-DURABLE-RESUME-RECONSTRUCTION-POC.md`

### M5-T3: Durable Recovery Architecture Gate ✅ COMPLETE
**完成日期:** 2026-09-08

**交付物:**
- DurableExecutionEngine 接口设计
- RuntimeBindingResolver 契约
- 跨运行时恢复架构
- 文档: `docs/architecture/M5-T3-DURABLE-RECOVERY-ARCHITECTURE-GATE.md`
- 修正: `docs/architecture/M5-T3A-DURABLE-RECOVERY-CONTRACT-AMENDMENT.md`

### M5-T4: Durable Suspension/Recovery Implementation ✅ **COMPLETE — FINAL GO**
**完成日期:** 2026-09-09

**状态:** ✅ **M5-T4 FINAL: GO**

**实施阶段:**
- Phase 1: ✅ CheckpointStore + RuntimeBinding
- Phase 2: ✅ Core Durable Capability
- Phase 3: ✅ AgentRuntime Durable Entry
- Phase 4: ✅ Initial Durable Suspension
- Phase 5.1: ❌ NO-GO (Evidence duplication bug)
- Phase 5.2: ✅ GO (Bug fixed, 20/20 test coverage)
- Phase 6: ✅ GO (Local handle exception lifecycle)
- Phase 7: ✅ GO (Cross-runtime recovery E2E)
- Phase 8: ✅ GO (Concurrency & conflict semantics)
- Phase 9: ✅ GO (Memory/Evidence/Governance regression)
- Phase 10: ✅ GO (Final closure & release gate)

**最终测试结果:**
```
mvn clean test: BUILD SUCCESS (9.347s)
mvn verify: BUILD SUCCESS (8.445s)

Core: 136 tests, 0 failures
Runtime-react: 79 tests, 0 failures, 6 skipped
Examples: 22 tests, 0 failures, 9 skipped
Total: 237 tests, 0 failures
```

**交付物:**
- `DurableExecutionEngine` 接口（extends AgentExecutionEngine）
- `RuntimeBindingResolver` 接口
- `CheckpointStore` 抽象 + `InMemoryCheckpointStore` 参考实现
- `RuntimeBinding` record
- `SuspensionCheckpoint` checkpoint 模型
- 6 个新异常类型
- `AgentRuntime.resumeProcess()` 方法
- `ProcessFactory.createDurableSuspended()` 工厂方法
- SpringAiToolCallingEngine 持久能力实现
- DurableResumeStrategy / EphemeralResumeStrategy
- 13 个新测试类（97 个新测试）
- 完整文档:
  - `M5-T4-IMPLEMENTATION-GUIDE.md`
  - `M5-T4-FINAL-CLOSURE-REPORT.md`
  - 10 个 Phase closure reports

---

## 3. M5 已完成能力

### 核心架构

```
Application
   │
   ▼
AgentRuntime
   │
   ├── execute() → AgentExecutionEngine
   │
   └── resumeProcess() → DurableExecutionEngine
          ▼
   SpringAiToolCallingEngine
          │
          ├─ CheckpointStore (CHECK A/B)
          ├─ RuntimeBindingResolver
          ├─ ChatMemory
          ├─ ToolGovernancePolicy
          ├─ Tools
          └─ ChatModel
```

### 交付的核心能力

1. **Checkpoint-backed Durable Suspension**
   - SuspensionCheckpoint 模型（包含 processId, checkpointVersion, runtimeBindingKey, sessionId, pendingBatch, accumulatedEvidences）
   - CheckpointStore 抽象（create, load, replaceIfVersion, deleteIfVersion）
   - InMemoryCheckpointStore 参考实现
   - 初始暂停流程（治理要求批准 → 创建 checkpoint v1）

2. **Cross-Runtime/JVM Recovery**
   - RuntimeBinding(AgentDefinition, AgentExecutionContext)
   - RuntimeBindingResolver 契约
   - 跨物理运行时实例恢复（A → B → C 验证）
   - runtimeBindingKey 逻辑绑定标识（跨运行时稳定）

3. **Unified Resume Pipeline (CHECK A/B)**
   - CHECK A: 加载 + 版本验证 + 绑定解析（在副作用前）
   - 协议重建（ProtocolReconstructor）
   - 工具执行（存储批次不重新治理）
   - 模型延续
   - CHECK B: 条件转换（deleteIfVersion 或 replaceIfVersion）
   - 完成 → 删除 checkpoint，持久化最终 Assistant
   - 重新暂停 → 替换为 vN+1 checkpoint

4. **Local Handle Lifecycle**
   - DurableResumeStrategy / EphemeralResumeStrategy
   - ResumePreparationException → WAITING（可重试）
   - StaleCheckpointException / CheckpointNotFoundException → FAILED
   - CheckpointTransitionConflictException → FAILED
   - 本地 FAILED ≠ 全局持久进程失败

5. **Memory/Evidence/Governance Continuity**
   - **ChatMemory:** H+U 在暂停，H+U+最终A 在完成（无重复 U，最终 A 恰好一次）
   - **Evidence:** 单调累积 [] → [A] → [A,B] → [A,B,C]（无重复，每个恰好一次）
   - **Governance:** 存储批次不重新治理，新 ToolCalls 正常治理（每个工具恰好治理一次）

6. **Concurrency Contracts**
   - **同本地句柄:** 本地 CAS（WAITING → RUNNING），一个后端执行
   - **跨运行时:** 两者都可能通过 CHECK A + 执行工具，只有一个赢得 CHECK B
   - **At-least-once 工具执行语义**（不保证恰好一次）

### 公共 API 扩展

**新接口 (3):**
1. `DurableExecutionEngine extends AgentExecutionEngine`
2. `RuntimeBindingResolver`
3. `CheckpointStore`

**新记录 (2):**
4. `RuntimeBinding(AgentDefinition, AgentExecutionContext)`
5. `SuspensionCheckpoint(...)`

**新参考实现 (1):**
6. `InMemoryCheckpointStore`

**新异常 (6):**
7. `CheckpointNotFoundException`
8. `StaleCheckpointException`
9. `CheckpointTransitionConflictException`
10. `CheckpointAlreadyExistsException`
11. `ResumePreparationException`
12. `RuntimeBindingException`

**新运行时方法 (1):**
13. `AgentRuntime.resumeProcess(String processId, long checkpointVersion, ContinuationSignal signal)`

**新工厂方法 (1):**
14. `ProcessFactory.createDurableSuspended(String id, String runtimeBindingKey, ResumeStrategy strategy)`

### 模块边界

```
arctra-runtime-react → arctra-core ✅ (ONLY)
arctra-core → arctra-runtime-react ❌ (FORBIDDEN, verified)
arctra-core → org.springframework.ai ❌ (FORBIDDEN, verified)
```

**验证结果:**
- `grep -R "org.springframework.ai" arctra-core/src/main/java` → 0 matches ✅
- `grep -R "cn.bitcss.arctra.runtime.react" arctra-core/src/main/java` → 0 matches ✅
- `grep -R "executePreparedResume" . --include="*.java"` → 0 matches ✅
- `grep -R "binding\.engine" . --include="*.java"` → 0 matches ✅

### M4 兼容性

✅ **M4 ephemeral 路径完全保留**
- 4-参数 SpringAiToolCallingEngine 构造函数仍然有效
- ProcessFactory.createSuspended() 仍然有效
- Ephemeral 失败 → FAILED
- 同句柄并发 → CAS 保护
- 成功完成 → COMPLETED
- 成功重新暂停 → WAITING

---

## 4. M5 已知限制

### 限制清单

以下限制是 M5 范围的明确边界，**不是** 未完成的工作：

1. **InMemoryCheckpointStore**
   - **限制:** JVM-local 参考实现，仅用于测试和单 JVM 验证
   - **影响:** 无跨 JVM 持久性，进程重启后数据丢失
   - **缓解:** 生产需要持久 CheckpointStore 实现

2. **CheckpointStore / ChatMemory 原子性**
   - **限制:** 无原子事务跨 CheckpointStore 转换 + ChatMemory 持久化
   - **崩溃窗口:** CHECK B 成功后 crash，ChatMemory 可能缺失最终 Assistant
   - **分类:** M5 可接受的限制
   - **缓解:** 生产需要事务性存储或外部一致性协调

3. **At-Least-Once 工具执行**
   - **限制:** M5 不保证恰好一次工具执行
   - **场景:** 跨运行时并发恢复，两者都通过 CHECK A，两者都执行工具
   - **影响:** 工具可能执行 2 次（或更多），模型调用可能重复
   - **缓解:** 工具应该是幂等的，或使用应用程序级去重

4. **应用程序定义的 RuntimeBindingResolver**
   - **限制:** M5 不提供内置通用解析器
   - **影响:** 应用程序必须实现 RuntimeBindingResolver
   - **缓解:** 解析逻辑是应用程序特定的

5. **本地句柄是快照**
   - **限制:** 本地 AgentProcess 句柄可能变得过时
   - **语义:** 本地 FAILED ≠ 全局持久进程失败
   - **权威:** Checkpoint 是权威的

6. **无 RuntimeBindingKey 迁移**
   - **限制:** M5 保留逻辑绑定 key 跨生命周期
   - **影响:** 无内置 key 重新映射/迁移，Key 在初始暂停时冻结

7. **Continuation 代码重复**
   - **限制:** 存在重复的 continuation 实现（continueWithMessages vs durableContinueWithMessages）
   - **分类:** 接受的 M6 技术债务
   - **影响:** 维护负担，但功能正确
   - **缓解:** 延期到 M6 整合

### M5 明确不声称

❌ **M5 不提供:**
1. 生产就绪的分布式持久性（需要持久 CheckpointStore）
2. 恰好一次工具执行保证（at-least-once 语义）
3. 原子 checkpoint/ChatMemory 事务
4. 自动重试 ResumePreparationException（可重试-由调用者，不自动重试）
5. RuntimeBindingResolver 恢复序列化 Java 对象（重建/重新解析活运行时状态）
6. 内置通用 RuntimeBindingResolver 实现
7. RuntimeBindingKey 迁移/版本控制
8. 分布式独占执行策略

✅ **M5 提供:**
1. 持久恢复的原语和契约
2. 单 JVM 持久恢复验证
3. InMemoryCheckpointStore 参考实现
4. 跨独立运行时实例恢复能力
5. 核心抽象和框架中立接口

---

## 5. M5 接受的技术债务

### TD-1: Continuation 代码重复

**位置:** SpringAiToolCallingEngine

**问题:**
```java
private AgentResult continueWithMessages(...)  // Ephemeral
private AgentResult durableContinueWithMessages(...)  // Durable
```

**根本原因:**
- Ephemeral/durable 路径有不同的后处理
- 共享 continuation 逻辑被复制

**分类:**
- **不是** 重复的持久管道
- **是** 共享 continuation 实现重复

**影响:**
- 维护成本增加
- 但不影响正确性
- 不影响公共 API

**延期到:** M6 整合

---

## 6. M6+ 输入积压

以下项目是 **明确延期** 到 M6 或更高里程碑的工作，不是 M5 未完成的任务：

### A. 恢复配置

**问题域:** 生产 RuntimeBinding 重建

**输入:**
1. 生产 RuntimeBindingResolver 实现策略
   - 注册表/配置/版本语义
   - Agent 定义查找机制
   - 执行上下文重建
2. RuntimeBindingKey 命名约定
3. RuntimeBindingKey 迁移/版本控制
4. 绑定解析失败策略

**当前 M5 状态:**
- RuntimeBindingResolver 接口已定义
- 应用程序必须实现解析器
- 无内置实现

**为什么不在 M5 解决:**
- 解析逻辑是应用程序特定的
- M5 专注于契约和核心原语
- 生产实现需要特定部署模式

**推荐下一里程碑:** M6 或 M7

---

### B. 执行保证

**问题域:** 工具执行语义和去重

**输入:**
1. At-least-once 优化策略
   - 工具幂等性指南
   - 应用程序级去重模式
2. 独占执行探索
   - 分布式锁集成
   - 租约语义
   - 冲突检测策略
3. 幂等辅助执行
   - 请求 ID 传播
   - 幂等性键约定
4. Exactly-once 可行性边界
   - 理论限制
   - 实现成本/收益分析
   - 何时需要

**当前 M5 状态:**
- At-least-once 工具执行（已记录）
- 跨运行时并发允许重复执行
- 缓解：工具幂等性责任在应用程序

**为什么不在 M5 解决:**
- 恰好一次执行需要分布式协调
- 幂等性策略是应用程序特定的
- M5 专注于核心持久恢复契约

**推荐下一里程碑:** M6 (策略/指南) + M7 (分布式锁集成)

---

### C. 重试 / 韧性

**问题域:** ResumePreparationException 重试策略

**输入:**
1. ResumePreparationException 重试策略
   - 自动重试 vs 调用者重试
   - 退避策略（exponential, jitter）
   - 重试限制配置
2. 瞬态/致命分类
   - CheckpointNotFoundException → 致命
   - StaleCheckpointException → 致命
   - ResumePreparationException → 瞬态
   - RuntimeBindingException → 取决于根本原因
3. 重试上下文传播
4. 可观察性集成

**当前 M5 状态:**
- ResumePreparationException → WAITING（可重试）
- 本地句柄变为 WAITING 而不是 FAILED
- 调用者负责重试决策
- 无自动重试

**为什么不在 M5 解决:**
- 重试策略是应用程序特定的
- 自动重试需要配置框架
- M5 专注于可重试语义边界

**推荐下一里程碑:** M6 (自动重试框架)

---

### D. 持久性

**问题域:** 生产 CheckpointStore 实现

**输入:**
1. JDBC CheckpointStore 实现
   - Schema 设计
   - 事务边界
   - 版本化 CAS（SELECT FOR UPDATE + version check）
2. Redis CheckpointStore 实现
   - Lua 脚本用于原子 CAS
   - TTL 配置
   - 序列化策略
3. 持久性特性
   - 性能（读/写延迟）
   - 一致性保证
   - 可用性权衡
4. Schema/版本兼容性
   - SuspensionCheckpoint 演化
   - 迁移策略
   - 向后/向前兼容性

**当前 M5 状态:**
- CheckpointStore 接口已定义
- InMemoryCheckpointStore 参考实现
- JVM-local，进程重启后数据丢失

**为什么不在 M5 解决:**
- M5 验证核心契约，不需要生产持久性
- 持久 store 实现需要特定基础设施选择
- Schema 设计需要生产用例输入

**推荐下一里程碑:** M6 (JDBC + Redis 实现)

---

### E. 一致性

**问题域:** CheckpointStore / ChatMemory 原子性

**输入:**
1. CheckpointStore / ChatMemory 崩溃窗口分析
   - 影响评估
   - 恢复场景
2. Outbox / 事务选项
   - 双写问题
   - Outbox 模式可行性
   - 分布式事务 vs 最终一致性
3. Arctra 是否应该拥有此关注点
   - 框架级一致性 vs 应用程序级
   - 复杂性权衡
   - 可插拔策略
4. ChatMemory 持久性契约
   - Spring AI ChatMemory 事务语义
   - 集成点

**当前 M5 状态:**
- 无原子事务跨 CheckpointStore + ChatMemory
- 崩溃窗口已记录
- 分类为 M5 可接受的限制

**为什么不在 M5 解决:**
- 一致性协调复杂且成本高
- 需要生产部署数据评估影响
- 解决方案取决于基础设施选择（JDBC, 事件源，等）

**推荐下一里程碑:** M7 (一致性策略)

---

### F. 运行时清理

**问题域:** Continuation 管道整合

**输入:**
1. Continuation 管道整合
   - 统一 continueWithMessages() / durableContinueWithMessages()
   - 提取共享逻辑
   - 维护 ephemeral/durable 后处理差异
2. 代码质量改进
   - 减少重复
   - 改进可测试性
   - 清晰注释

**当前 M5 状态:**
- 存在重复的 continuation 实现
- 功能正确
- 分类为接受的技术债务

**为什么不在 M5 解决:**
- Phase 10 仅验证/文档，不重构
- 不影响公共 API 或正确性
- 维护负担可接受

**推荐下一里程碑:** M6 (技术债务清理)

---

## 7. M5 最终决定

### ✅ **M5 COMPLETE — M5-T4 FINAL: GO**

**声明:**

**M5-T4 durable suspension/recovery capability is functionally complete and validated for the defined M5 scope.**

### 交付的功能

1. ✅ Checkpoint-backed durable suspension
2. ✅ Cross-runtime/JVM recovery
3. ✅ Unified resume pipeline (CHECK A/B)
4. ✅ Local handle lifecycle semantics
5. ✅ Memory/Evidence/Governance continuity
6. ✅ Concurrency contracts (CAS + CHECK B)
7. ✅ 237 tests, 0 failures

### M5 范围边界

**M5 提供:**
- 单 JVM 持久恢复验证
- InMemoryCheckpointStore 参考实现
- 跨独立运行时实例恢复能力
- 核心抽象和框架中立接口

**M5 不声称:**
- 生产分布式持久性（需要持久 CheckpointStore）
- 恰好一次工具执行（at-least-once 语义）
- 原子 checkpoint/ChatMemory 事务
- 内置 RuntimeBindingResolver 实现

### 发布就绪性

**M5 已准备好用于:**
- ✅ 单 JVM 测试和验证
- ✅ 架构评估
- ✅ 应用程序原型
- ✅ 持久持久性的基础

**M5 不立即生产就绪用于:**
- ❌ 分布式持久部署（需要持久存储）
- ❌ 严格的恰好一次执行要求

---

## 8. 参考文档

**M5-T4 核心文档:**
- 实施指南: `M5-T4-IMPLEMENTATION-GUIDE.md`
- 最终闭环报告: `M5-T4-FINAL-CLOSURE-REPORT.md`
- Phase closure reports: `M5-T4-PHASE-*.md`

**M5 规划文档:**
- `docs/planning/M5-T1-DURABLE-PROCESS-CONTRACT-GATE.md`
- `docs/architecture/M5-T3-DURABLE-RECOVERY-ARCHITECTURE-GATE.md`
- `docs/architecture/M5-T3A-DURABLE-RECOVERY-CONTRACT-AMENDMENT.md`
- `docs/research/M5-T2-DURABLE-RESUME-RECONSTRUCTION-POC.md`

---

**M5 实施已完成。核心持久能力已交付并验证。**

**M5-T4 COMPLETE — 2026-09-09**
