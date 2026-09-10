# Arctra M6 输入积压

**创建日期:** 2026-09-09

**状态:** 待规划

**目的:** M5 延期工作项和 M6 候选方向

---

## 重要说明

本文档包含 M5 **明确延期** 的工作项，**不是** M5 未完成的任务。

这些是 M5 范围边界外的工作，需要在 M6 或更高里程碑中解决。

**禁止:**
- 将这些项目视为 M5 遗留缺陷
- 声称 M5 未完成
- 将 M5 限制重新分类为待办事项

**正确理解:**
- M5 交付了定义范围内的核心持久能力
- 以下项目是有意识的设计边界
- 每个项目都有明确的理由说明为什么不在 M5 解决

---

## A. 恢复配置

### A1. 生产 RuntimeBindingResolver 实现

**问题域:** 生产环境下如何从 runtimeBindingKey 重建 RuntimeBinding

**当前 M5 状态:**
- `RuntimeBindingResolver` 接口已定义
- 应用程序必须实现解析器
- 无内置实现

**为什么不在 M5 解决:**
- 解析逻辑是应用程序特定的
- M5 专注于契约和核心原语
- 生产实现需要特定部署模式

**M6 输入:**
1. **注册表/配置语义**
   - Agent 定义查找机制（内存注册表 vs 配置文件 vs 数据库）
   - 逻辑 agent 标识符约定
   - 版本化 agent 定义

2. **重建策略**
   - AgentDefinition 重建（tools, model, policy 实例）
   - AgentExecutionContext 重建（session, metadata）
   - 资源生命周期（共享 vs per-process）

3. **参考实现**
   - `MapBasedRuntimeBindingResolver`（内存注册表）
   - `ConfigBasedRuntimeBindingResolver`（YAML/Properties）
   - Spring Boot 自动配置集成

4. **失败策略**
   - 绑定解析失败处理
   - 未知 runtimeBindingKey 行为
   - 降级策略

**依赖:**
- 无（可独立实施）

**优先级:** 高（生产部署前必需）

**推荐里程碑:** M6

---

### A2. RuntimeBindingKey 命名约定

**问题域:** runtimeBindingKey 命名最佳实践

**当前 M5 状态:**
- runtimeBindingKey 是 String
- 无约定或验证
- 在初始暂停时冻结

**为什么不在 M5 解决:**
- 命名约定取决于应用程序组织
- M5 验证核心契约，不强制约定

**M6 输入:**
1. 推荐命名模式（例如 `<domain>:<agent-type>:<version>`）
2. 命名空间策略（多租户场景）
3. 版本化约定
4. 验证规则（可选）

**依赖:**
- A1 (RuntimeBindingResolver 实现)

**优先级:** 中

**推荐里程碑:** M6

---

### A3. RuntimeBindingKey 迁移/版本控制

**问题域:** 如何处理 runtimeBindingKey 演化

**当前 M5 状态:**
- runtimeBindingKey 在初始暂停时冻结
- 无内置 key 重新映射/迁移
- M5 不提供 key 迁移

**为什么不在 M5 解决:**
- 迁移策略复杂，需要生产用例
- M5 专注于核心持久恢复

**M6 输入:**
1. Key 演化场景
   - Agent 重命名
   - 组织重构
   - 版本升级
2. 迁移策略
   - Checkpoint 就地更新
   - Resolver 层映射
   - 向后兼容性窗口
3. 版本控制语义
   - 何时需要新 key vs key 版本
   - 版本格式

**依赖:**
- A1 (RuntimeBindingResolver 实现)
- D1 (持久 CheckpointStore 实现)

**优先级:** 低（仅在生产演化后需要）

**推荐里程碑:** M7

---

## B. 执行保证

### B1. At-Least-Once 优化策略

**问题域:** 当前 at-least-once 语义的优化和指南

**当前 M5 状态:**
- At-least-once 工具执行（已记录）
- 跨运行时并发允许重复执行
- 缓解：工具幂等性责任在应用程序

**为什么不在 M5 解决:**
- 优化策略需要生产数据
- 幂等性模式是应用程序特定的

**M6 输入:**
1. **工具幂等性指南**
   - 幂等工具设计模式
   - 幂等性键约定
   - 请求 ID 传播
   - 示例：幂等 HTTP 客户端、幂等数据库操作

2. **应用程序级去重模式**
   - 分布式去重存储（Redis, DynamoDB）
   - TTL 策略
   - 去重键设计

3. **检测和可观察性**
   - 重复执行检测
   - 度量和警报
   - 审计日志

**依赖:**
- 无（可独立实施）

**优先级:** 高（生产部署前需要指南）

**推荐里程碑:** M6

---

### B2. 独占执行探索

**问题域:** 避免跨运行时重复执行的分布式协调

**当前 M5 状态:**
- 无独占执行
- 跨运行时并发允许重复执行

**为什么不在 M5 解决:**
- 分布式锁需要基础设施选择
- 复杂性和性能权衡需要评估
- M5 专注于核心持久契约

**M6 输入:**
1. **分布式锁集成**
   - Redis 锁（Redisson）
   - ZooKeeper 锁
   - 数据库锁（SELECT FOR UPDATE）
   - 云原生锁（AWS DynamoDB, Azure CosmosDB）

2. **租约语义**
   - 租约超时配置
   - 租约续约策略
   - 孤儿租约清理

3. **冲突检测策略**
   - 乐观锁 vs 悲观锁
   - CHECK A 前获取锁 vs CHECK A 后
   - 锁粒度（per-process vs per-checkpoint-version）

4. **性能权衡分析**
   - 延迟影响
   - 可用性影响
   - 复杂性成本

**依赖:**
- D1 (持久 CheckpointStore 实现)
- 分布式锁基础设施

**优先级:** 中（仅当 at-least-once 不可接受时）

**推荐里程碑:** M7

---

### B3. Exactly-Once 可行性边界

**问题域:** 恰好一次执行的理论和实践限制

**当前 M5 状态:**
- M5 不保证恰好一次
- 已记录为明确限制

**为什么不在 M5 解决:**
- 恰好一次执行在分布式系统中理论复杂
- 需要深入分析成本/收益

**M6 输入:**
1. **理论限制分析**
   - CAP 定理影响
   - 两阶段提交可行性
   - Saga 模式适用性

2. **实现成本/收益分析**
   - 延迟成本
   - 复杂性成本
   - 可用性影响
   - 何时真正需要恰好一次

3. **替代方案**
   - 幂等性 + at-least-once（推荐默认）
   - 应用程序级去重
   - 事件源 + 幂等消费者

4. **决策树**
   - 何时使用 at-least-once + 幂等性
   - 何时需要独占执行
   - 何时需要分布式事务

**依赖:**
- B1 (At-least-once 优化)
- B2 (独占执行探索)

**优先级:** 低（研究性）

**推荐里程碑:** M7 或更高

---

## C. 重试 / 韧性

### C1. ResumePreparationException 自动重试

**问题域:** 自动重试可重试异常

**当前 M5 状态:**
- `ResumePreparationException` → WAITING（可重试）
- 本地句柄变为 WAITING 而不是 FAILED
- 调用者负责重试决策
- 无自动重试

**为什么不在 M5 解决:**
- 重试策略是应用程序特定的
- 自动重试需要配置框架
- M5 专注于可重试语义边界

**M6 输入:**
1. **自动重试策略**
   - 重试 vs 调用者重试
   - 配置 API（maxRetries, backoff, jitter）
   - 默认策略

2. **退避策略**
   - Exponential backoff
   - Jitter（避免雷鸣群）
   - 可配置退避函数

3. **重试限制**
   - 最大重试次数
   - 最大重试时间窗口
   - 放弃后行为（FAILED vs WAITING）

4. **可观察性**
   - 重试计数器
   - 退避延迟度量
   - 失败后度量

**依赖:**
- 无（可独立实施）

**优先级:** 中

**推荐里程碑:** M6

---

### C2. 瞬态/致命异常分类

**问题域:** 明确哪些异常可重试，哪些致命

**当前 M5 状态:**
- `ResumePreparationException` → WAITING（可重试）
- `StaleCheckpointException` → FAILED（致命）
- `CheckpointNotFoundException` → FAILED（致命）
- `CheckpointTransitionConflictException` → FAILED（致命）
- `RuntimeBindingException` → 取决于根本原因（未细化）

**为什么不在 M5 解决:**
- 细粒度分类需要生产经验
- RuntimeBindingException 分类取决于解析器实现

**M6 输入:**
1. **异常分类表**
   - 每个异常的可重试性
   - 根本原因到分类的映射

2. **RuntimeBindingException 细化**
   - 瞬态绑定失败（网络错误）
   - 致命绑定失败（未知 key）

3. **工具/模型异常分类**
   - 瞬态工具失败（超时，限流）
   - 致命工具失败（认证错误）
   - 模型 API 错误分类

4. **可扩展分类 API**
   - 应用程序定义异常分类器
   - 默认分类器

**依赖:**
- A1 (RuntimeBindingResolver 实现)
- C1 (自动重试框架)

**优先级:** 中

**推荐里程碑:** M6

---

## D. 持久性

### D1. 生产 CheckpointStore 实现

**问题域:** 持久、分布式 CheckpointStore 实现

**当前 M5 状态:**
- `CheckpointStore` 接口已定义
- `InMemoryCheckpointStore` 参考实现
- JVM-local，进程重启后数据丢失

**为什么不在 M5 解决:**
- M5 验证核心契约，不需要生产持久性
- 持久 store 实现需要特定基础设施选择
- Schema 设计需要生产用例输入

**M6 输入:**
1. **JDBC CheckpointStore 实现**
   - Schema 设计（表结构，索引）
   - 事务边界（ACID 保证）
   - 版本化 CAS（SELECT FOR UPDATE + version check）
   - 数据库兼容性（PostgreSQL, MySQL, H2）
   - 序列化策略（JSON vs 二进制）

2. **Redis CheckpointStore 实现**
   - Lua 脚本用于原子 CAS
   - Key 设计（namespace, TTL）
   - 序列化策略
   - 持久性配置（RDB, AOF）
   - Cluster 兼容性

3. **性能基准测试**
   - 读/写延迟
   - 并发冲突率
   - 吞吐量

4. **高可用性**
   - 复制策略
   - 故障转移行为

**依赖:**
- 无（可独立实施）

**优先级:** 高（生产部署前必需）

**推荐里程碑:** M6

---

### D2. CheckpointStore Schema/版本兼容性

**问题域:** SuspensionCheckpoint 模型演化

**当前 M5 状态:**
- `SuspensionCheckpoint` 模型定义
- schemaVersion 字段存在但未使用
- 无演化策略

**为什么不在 M5 解决:**
- 演化需求在生产使用后出现
- Schema 演化策略复杂

**M6 输入:**
1. **Schema 版本控制**
   - schemaVersion 语义
   - 版本升级路径

2. **向后/向前兼容性**
   - 新字段添加策略
   - 字段弃用策略
   - 迁移工具

3. **SuspensionCheckpoint 演化场景**
   - 添加新元数据字段
   - pendingBatch 结构变化
   - accumulatedEvidences 结构变化

4. **迁移工具**
   - Checkpoint 批量迁移
   - 在线迁移 vs 离线迁移
   - 回滚能力

**依赖:**
- D1 (持久 CheckpointStore 实现)

**优先级:** 低（仅在生产演化后需要）

**推荐里程碑:** M7

---

## E. 一致性

### E1. CheckpointStore / ChatMemory 崩溃窗口分析

**问题域:** CHECK B 成功后 crash，ChatMemory 可能缺失最终 Assistant

**当前 M5 状态:**
- 无原子事务跨 CheckpointStore + ChatMemory
- 崩溃窗口已记录
- 分类为 M5 可接受的限制

**为什么不在 M5 解决:**
- 一致性协调复杂且成本高
- 需要生产部署数据评估影响
- 解决方案取决于基础设施选择

**M6 输入:**
1. **影响评估**
   - 崩溃窗口概率
   - 影响用户体验
   - 恢复场景（人工 vs 自动）

2. **检测和恢复**
   - 检测不一致状态
   - 修复工具
   - 警报策略

3. **是否值得解决**
   - 成本/收益分析
   - 替代缓解措施

**依赖:**
- D1 (持久 CheckpointStore 实现)
- 生产部署数据

**优先级:** 中

**推荐里程碑:** M7

---

### E2. Outbox / 事务选项

**问题域:** 原子 CheckpointStore / ChatMemory 事务

**当前 M5 状态:**
- 无事务协调
- 崩溃窗口存在

**为什么不在 M5 解决:**
- 事务协调复杂
- 需要特定基础设施选择
- 影响评估未完成

**M6 输入:**
1. **Outbox 模式可行性**
   - Checkpoint 完成写入 outbox
   - 后台进程将 outbox 写入 ChatMemory
   - 幂等性保证

2. **分布式事务选项**
   - 2PC（两阶段提交）可行性
   - Saga 模式适用性
   - XA 事务（JDBC + JMS）

3. **最终一致性策略**
   - 接受崩溃窗口
   - 后台修复进程
   - 用户可见行为

4. **ChatMemory 持久性契约**
   - Spring AI ChatMemory 事务语义
   - 集成点
   - 约束

**依赖:**
- E1 (崩溃窗口影响评估)
- D1 (持久 CheckpointStore 实现)

**优先级:** 低（仅当 E1 评估为高影响时）

**推荐里程碑:** M7 或更高

---

### E3. Arctra 是否应该拥有此关注点

**问题域:** 一致性协调是框架责任还是应用程序责任

**当前 M5 状态:**
- M5 不提供事务协调
- 应用程序负责

**为什么不在 M5 解决:**
- 设计哲学问题，需要深思熟虑

**M6 输入:**
1. **框架级一致性 vs 应用程序级**
   - Arctra 提供事务协调？
   - 或仅提供钩子/扩展点？

2. **复杂性权衡**
   - 框架复杂性成本
   - 应用程序灵活性收益
   - 默认行为 vs 可插拔策略

3. **其他框架先例**
   - Spring Batch（作业存储库事务）
   - Axon Framework（事件存储 + saga）
   - Temporal（工作流持久性）

4. **决策**
   - Arctra 立场
   - 文档化理由

**依赖:**
- E1, E2 (一致性选项探索)

**优先级:** 低（哲学性）

**推荐里程碑:** M7

---

## F. 运行时清理

### F1. Continuation 管道整合

**问题域:** 消除 continueWithMessages / durableContinueWithMessages 重复

**当前 M5 状态:**
- 存在重复的 continuation 实现
- 功能正确
- 分类为接受的技术债务

**为什么不在 M5 解决:**
- Phase 10 仅验证/文档，不重构
- 不影响公共 API 或正确性
- 维护负担可接受

**M6 输入:**
1. **重复分析**
   - 共享逻辑提取
   - Ephemeral/durable 后处理差异

2. **重构方案**
   - 统一 continuation 方法
   - 策略模式用于后处理
   - 模板方法模式

3. **测试保留**
   - 确保重构不破坏行为
   - 保留所有现有测试

**依赖:**
- 无（可独立实施）

**优先级:** 低（技术债务清理）

**推荐里程碑:** M6

---

## G. 其他候选方向

以下是 M5 范围外的其他候选方向，不是延期工作：

### G1. 可观察性增强

- Process 执行追踪
- Checkpoint 转换事件
- RuntimeBinding 解析度量
- CHECK A/B 度量
- 重试度量

### G2. Spring Boot 集成

- Spring Boot Starter 增强
- CheckpointStore 自动配置
- RuntimeBindingResolver 自动配置
- Health indicators
- Actuator endpoints

### G3. 测试工具

- Checkpoint 测试 DSL
- RuntimeBinding 测试 fixtures
- 跨运行时恢复测试工具
- 并发测试工具

### G4. 文档和示例

- Durable suspension/recovery 用户指南
- RuntimeBinding 设计模式
- CheckpointStore 实现指南
- 端到端示例应用程序

---

## 推荐 M6 范围

基于优先级和依赖关系，推荐 M6 聚焦：

**高优先级（生产部署前必需）:**
1. **D1:** JDBC + Redis CheckpointStore 实现
2. **A1:** 生产 RuntimeBindingResolver 实现（MapBased, ConfigBased）
3. **B1:** At-least-once 优化策略和工具幂等性指南

**中优先级（生产韧性）:**
4. **C1:** ResumePreparationException 自动重试框架
5. **C2:** 瞬态/致命异常分类
6. **E1:** CheckpointStore/ChatMemory 崩溃窗口影响评估

**低优先级（清理）:**
7. **F1:** Continuation 管道整合

**延期到 M7+:**
- A2, A3 (RuntimeBindingKey 约定/迁移)
- B2, B3 (独占执行，恰好一次可行性)
- D2 (Schema 版本兼容性)
- E2, E3 (事务选项，设计哲学)
- G1-G4 (可观察性，集成，工具，文档)

---

## 非目标

以下明确不是 M6 目标：

❌ **修复 M5"缺陷"** — M5 已完整交付
❌ **重新实现 M5 能力** — M5 架构稳定
❌ **突破 M5 限制声明** — M5 限制是有意识的设计边界
❌ **恰好一次保证** — 复杂度高，收益低，延期到 M7 研究

---

## 下一步

1. **M6 规划会议**
   - 审查本积压
   - 确定 M6 范围
   - 分配任务

2. **架构闸门**
   - M6-T0: 持久性和韧性架构闸门
   - 评估 CheckpointStore 实现选项
   - 评估重试框架设计

3. **不开始实施**
   - 本文档仅输入积压
   - M6 实施需要显式启动决策

---

**M6 输入积压创建完成 — 2026-09-09**
