# M7 Recovery Control Plane - Source Truth Findings

## 检查日期
2026-09-20

## 检查范围

- CheckpointStore interface and implementations
- SuspensionCheckpoint structure
- ContinuationDisposition
- Database schema
- ExecutionLedger semantics
- RuntimeBindingResolver
- DurableExecutionEngine contract
- Existing recovery tests

---

## 关键发现

### 1. CheckpointStore 当前能力

**接口：** `cn.bitcss.arctra.checkpoint.CheckpointStore`

**当前操作：**
- `create(SuspensionCheckpoint)` - 创建初始 checkpoint
- `load(String processId)` - 按 processId 加载
- `replaceIfVersion(processId, expectedVersion, replacement)` - CAS 替换
- `deleteIfVersion(processId, expectedVersion)` - CAS 删除

**枚举/查询能力：** ❌ **不存在**

**结论：** CheckpointStore 需要扩展查询能力以支持进程发现。

---

### 2. SuspensionCheckpoint 结构

**Schema Version：** 1.2 (M6-T6.4)

**字段：**
```java
record SuspensionCheckpoint(
    String schemaVersion,           // 当前 "1.2"
    String processId,               // 稳定进程标识
    long checkpointVersion,         // 乐观锁版本
    String runtimeBindingKey,       // 运行时绑定解析键
    String sessionId,               // 会话 ID（可为 null）
    ContinuationDisposition disposition,  // M6-T6.4 新增
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences,
    String executionEpoch           // M6-T4F 新增（可为 null）
)
```

**Disposition 语义：**
- `RUNNABLE` - 可自动继续（ALLOW + DURABLE）
- `WAITING_FOR_SIGNAL` - 等待外部信号（REQUIRE_APPROVAL）

**结论：** Checkpoint 已经是自描述的，包含操作发现所需的所有元数据。

---

### 3. JDBC Schema

**表：** `arctra_checkpoints`

```sql
CREATE TABLE arctra_checkpoints (
    process_id          VARCHAR(255) PRIMARY KEY,
    checkpoint_version  BIGINT NOT NULL,
    schema_version      VARCHAR(32) NOT NULL,
    runtime_binding_key VARCHAR(255) NOT NULL,
    session_id          VARCHAR(255),
    checkpoint_data     TEXT NOT NULL
);
```

**当前索引：** 仅 PRIMARY KEY (process_id)

**时间戳：** ❌ **不存在** - 没有 created_at/updated_at

**结论：** 
- 基本查询字段已存在
- 需要决定是否添加时间戳元数据
- 需要考虑 disposition 字段是否应从 JSON 提升到列

---

### 4. ExecutionLedger 语义

**接口：** `cn.bitcss.arctra.execution.ExecutionLedger`

**权威声明：**
> "ExecutionLedger is the **historical fact authority**. It records what happened during execution. It is **NOT the current recovery state authority** - that remains with SuspensionCheckpoint."

> "**M6-T1 recovery algorithms MUST NOT infer current resumable state from ledger history.**"

**结论：** ✅ **M7 必须使用 CheckpointStore 作为当前进程发现的权威，不得查询 ExecutionLedger 作为当前状态。**

---

### 5. 进程完成/失败表示

**当前语义：**
- 终端状态（COMPLETED / FAILED）通过 **删除 checkpoint** 表示
- Checkpoint 存在 = 可恢复的 continuation
- Checkpoint 不存在 = 进程已终止或从未存在

**结论：** 
- M7 V1 应专注于**当前可恢复 continuation 的发现**
- 不应尝试从不存在的 checkpoint 重建 COMPLETED/FAILED 状态
- 历史进程查询应保留为 ExecutionLedger 职责

---

### 6. RuntimeBindingResolver

**接口：** `cn.bitcss.arctra.runtime.RuntimeBindingResolver`

**职责：**
```java
RuntimeBinding resolve(String processId, String runtimeBindingKey, String sessionId)
```

**语义：**
- 将 checkpoint 中的 `runtimeBindingKey` 解析为具体运行时依赖
- 返回 AgentDefinition + AgentExecutionContext
- 执行权威属于调用 DurableExecutionEngine，不属于 binding

**结论：** ✅ **M7 发现必须公开 runtimeBindingKey 以便操作员判断恢复是否可行。**

---

### 7. DurableExecutionEngine.resumeProcess()

**契约：**
```java
AgentResult resumeProcess(
    String processId,
    long checkpointVersion,  // ← 必须参与
    ContinuationSignal signal
)
```

**CHECK A (pre-execution):**
- 验证 checkpoint 存在
- 验证 version 匹配（fencing token）
- 失败则零副作用

**CHECK B (post-execution):**
- CAS checkpoint 转换
- 完成删除 / 重新暂停替换
- 失败抛出 CheckpointTransitionConflictException

**结论：** ✅ **M7 必须保留 checkpointVersion 参与恢复调用，操作员不能绕过版本保护。**

---

### 8. 跨实例恢复验证

**现有测试：** `ThreeRuntimeRecoveryTest`

**验证场景：**
```
Runtime A → suspend → checkpoint v1
Runtime B → resume v1 → re-suspend → checkpoint v2
Runtime C → resume v2 → complete → checkpoint deleted
```

**共享基础设施：**
- CheckpointStore
- ChatMemory
- RuntimeBindingResolver

**独立资源：**
- Tools
- ChatModel
- DurableExecutionEngine

**结论：** ✅ **M7 必须实现类似的跨 JVM 实例测试，证明重启后的发现和恢复。**

---

## M7 架构决策

### 决策 1：CheckpointStore 扩展策略

**问题：** CheckpointStore 是 public API，如何添加查询能力？

**选项：**
1. 直接在 CheckpointStore 添加方法
2. 创建分离的 CheckpointQueryStore 接口
3. 创建 package-private 查询能力

**推荐：** **选项 1 - 直接扩展 CheckpointStore**

**理由：**
- CheckpointStore 已经是 checkpoint 权威
- 查询是同一权威的读操作
- 避免引入第二个进程存储抽象
- V1 保持最小 API 表面积

**API 设计：**
```java
public interface CheckpointStore {
    // 现有方法...
    
    /**
     * List current recoverable continuations.
     * 
     * @return list of active checkpoints, empty if none
     */
    List<SuspensionCheckpoint> listContinuations();
    
    /**
     * List continuations by disposition.
     * 
     * @param disposition filter by continuation disposition
     * @return filtered list of checkpoints
     */
    List<SuspensionCheckpoint> listContinuationsByDisposition(
        ContinuationDisposition disposition
    );
}
```

---

### 决策 2：时间戳元数据

**问题：** 是否添加 created_at/updated_at 到 checkpoint schema？

**当前状况：** Schema 没有时间戳

**操作需求：**
- 按时间排序 continuation（最旧优先恢复）
- 识别长时间悬挂的进程
- 审计 checkpoint 生命周期

**推荐：** **添加 updated_at 时间戳**

**理由：**
- 时间戳是元数据，不是生命周期权威
- 对操作发现有实际价值
- 不改变 M6 语义
- JDBC 可高效索引和排序

**Schema 变更：**
```sql
ALTER TABLE arctra_checkpoints 
ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_checkpoints_updated 
ON arctra_checkpoints(updated_at);
```

---

### 决策 3：Disposition 列提升

**问题：** disposition 当前在 JSON 中，是否提升到独立列？

**权衡：**

**提升优点：**
- SQL 可直接过滤 RUNNABLE vs WAITING_FOR_SIGNAL
- 索引高效
- 避免 JSON 解析

**提升缺点：**
- Schema 变更
- 与其他 checkpoint 字段不对称
- 增加迁移复杂度

**推荐：** **V1 不提升，保持在 JSON 中**

**理由：**
- Disposition 过滤可在应用层进行
- V1 预期 continuation 数量不大
- 避免过早优化
- 保持 schema 简单
- 未来可按需优化

---

### 决策 4：M7 模块边界

**问题：** M7 代码应放在哪个模块？

**选项：**
1. `arctra-core` - 核心模块
2. `arctra-runtime-react` - 与 JDBC 实现共存
3. 新建 `arctra-control-plane` 模块

**推荐：** **选项 1 - arctra-core**

**包结构：**
```
cn.bitcss.arctra.controlplane/
├── RecoverableContinuation      (read model)
├── ContinuationQuery            (query criteria)
├── ProcessControlPlane          (facade)
└── package-info.java
```

**理由：**
- 控制平面 API 是 provider-neutral 的
- 依赖 core 的 CheckpointStore/DurableExecutionEngine
- 不依赖 Spring AI 或 runtime-react 实现细节
- V1 不需要独立模块的复杂度

---

### 决策 5：取消操作 (Cancellation)

**问题：** M7 是否实现 `cancelProcess(processId, reason)`？

**M6 当前状态：** 没有持久化取消语义

**取消语义需要：**
1. 定义"取消"的含义（删除 checkpoint？终端状态？）
2. 防止未来恢复的机制
3. 历史审计记录
4. 可能需要新的生命周期权威

**推荐：** **DEFER - M7 V1 不实现取消**

**理由：**
- 取消需要新的持久化生命周期语义
- 不是发现/恢复的必需功能
- 可以通过删除 checkpoint 手动实现（非事务性）
- 避免引入不成熟的 public API
- 可在后续 M 轨道中正确设计

---

## M7 实现范围

### ✅ 包含

1. **CheckpointStore 查询扩展**
   - `listContinuations()`
   - `listContinuationsByDisposition(disposition)`

2. **JDBC 实现**
   - 高效 SQL 查询
   - 时间戳元数据
   - 合理索引

3. **控制平面读模型**
   - `RecoverableContinuation` - 发现结果 DTO
   - 包含 processId, checkpointVersion, disposition, runtimeBindingKey, updatedAt

4. **操作员恢复触发**
   - `ProcessControlPlane.resumeContinuation(processId, checkpointVersion, signal)`
   - 委托到 DurableExecutionEngine.resumeProcess()

5. **InMemoryCheckpointStore 查询支持**
   - 测试和开发场景

6. **跨 JVM 实例集成测试**
   - JVM A: create checkpoint
   - JVM B: discover + resume

7. **并发/过期测试**
   - 操作员发现版本 N
   - 另一个 worker 推进到版本 N+1
   - 操作员恢复版本 N → CHECK A 拒绝

### ❌ 明确不包含

1. **取消操作** - 需要新的生命周期语义
2. **分布式所有权** - 发现 ≠ 声明所有权
3. **心跳/租约/栅栏** - 分布式协调不在 V1
4. **从 ExecutionLedger 重建状态** - 违反权威原则
5. **ExecutionPathCache** - 已冻结为未来方向
6. **Spring Boot 自动配置** - 适配器层延后
7. **REST API / Web Console** - 延后
8. **复杂分页/过滤** - V1 保持简单

---

## 下一步：实现

**Phase 2:** 实现 CheckpointStore 查询扩展
**Phase 3:** 实现 JDBC 查询 + schema 升级
**Phase 4:** 实现控制平面 API
**Phase 5:** 跨实例集成测试

继续实施...
