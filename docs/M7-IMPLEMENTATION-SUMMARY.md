# M7: Recovery Control Plane - Implementation Summary

## 实现日期
2026-09-20

## 实现者
lov3r

---

## 实现概览

M7 Recovery Control Plane 为 Arctra M6 Durable Execution Kernel 提供了操作发现和操作员驱动恢复能力。这是一个薄操作层，委托给现有的 M6 权威，而不是重复实现执行或状态管理。

---

## 核心组件

### 1. CheckpointStore 查询扩展

**文件:** `arctra-core/src/main/java/cn/bitcss/arctra/checkpoint/CheckpointStore.java`

**新增方法:**
```java
List<SuspensionCheckpoint> listContinuations();
List<SuspensionCheckpoint> listContinuationsByDisposition(ContinuationDisposition disposition);
```

**语义:**
- 返回当前可恢复 continuation 的快照
- 不是租约、不是所有权声明
- 结果可能立即过期（安全：M6 CAS 会拒绝过期操作）

**实现:**
- ✅ `InMemoryCheckpointStore` - 测试和单 JVM 场景
- ✅ `JdbcCheckpointStore` - 生产环境跨实例发现

---

### 2. JDBC Schema 升级

**文件:** `arctra-runtime-react/src/test/resources/jdbc-durable-recovery-schema.sql`

**变更:**
```sql
ALTER TABLE arctra_checkpoints ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_checkpoints_updated ON arctra_checkpoints(updated_at);
```

**目的:**
- 操作发现按更新时间排序（最旧优先）
- 识别长时间悬挂的进程
- 审计 checkpoint 生命周期

**迁移脚本:** `m7-discovery-migration.sql`

**向后兼容性:** ✅ 安全迁移，不破坏现有部署

---

### 3. RecoverableContinuation 描述符

**文件:** `arctra-core/src/main/java/cn/bitcss/arctra/controlplane/RecoverableContinuation.java`

**职责:**
- 可恢复 continuation 的操作视图
- 不可变快照（record）
- 包含恢复所需的元数据

**字段:**
```java
record RecoverableContinuation(
    String processId,
    long checkpointVersion,        // CAS token
    ContinuationDisposition disposition,
    String runtimeBindingKey,
    String sessionId
)
```

**关键语义:**
- **不是进程真相** - 是从 CheckpointStore 派生的读模型
- **不是所有权** - 多个操作员可发现同一个 continuation
- **快照** - 可能在发现后过期

---

### 4. RecoveryControlPlane 接口

**文件:** `arctra-core/src/main/java/cn/bitcss/arctra/controlplane/RecoveryControlPlane.java`

**操作:**

#### 发现操作
```java
List<RecoverableContinuation> listContinuations();
List<RecoverableContinuation> listContinuationsByDisposition(ContinuationDisposition disposition);
Optional<RecoverableContinuation> getContinuation(String processId);
```

#### 操作员恢复
```java
void resumeContinuation(String processId, long expectedVersion, ContinuationSignal signal);
```

**职责:**
- ✅ 提供跨实例发现能力
- ✅ 委托恢复到 M6 DurableExecutionEngine
- ❌ 不实现执行逻辑
- ❌ 不复制进程状态
- ❌ 不实现分布式锁/租约

---

### 5. DefaultRecoveryControlPlane 实现

**文件:** `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/DefaultRecoveryControlPlane.java`

**依赖:**
- `CheckpointStore` - 读取 continuation 权威
- `DurableExecutionEngine` - M6 恢复委托目标

**实现策略:**
- 发现 → 查询 CheckpointStore，转换为 RecoverableContinuation
- 恢复 → 直接委托 `durableEngine.resumeProcess(processId, expectedVersion, signal)`
- 无重复逻辑 → 薄操作门面

---

## 架构原则保护

### M7 不是什么

1. **不是进程状态权威**
   - CheckpointStore 保持权威
   - Control Plane 是读模型

2. **不是执行引擎**
   - 不实现工具调用
   - 不实现模型调用
   - 不实现协议重建
   - 委托给 M6 DurableExecutionEngine

3. **不是分布式协调器**
   - 不实现分布式锁
   - 不实现租约/心跳
   - 不实现 leader 选举
   - 并发通过 M6 CAS 解决

4. **不从 ExecutionLedger 推断当前状态**
   - ExecutionLedger 是历史权威
   - SuspensionCheckpoint 是当前状态权威
   - Control Plane 查询 CheckpointStore，不查询 ExecutionLedger

---

## 测试覆盖

### 单元测试

1. **RecoveryControlPlaneDiscoveryTest**
   - 列出所有 continuation
   - 按 disposition 过滤
   - 获取特定 continuation
   - 快照语义验证

2. **RecoveryControlPlaneOperatorRecoveryTest**
   - 版本感知恢复
   - 信号传播
   - 过期描述符拒绝
   - M6 委托正确性

### 集成测试

3. **CrossInstanceJdbcRecoveryTest** ⭐
   - JVM A 创建 checkpoint
   - JVM B 发现并恢复
   - 跨实例发现验证
   - RUNNABLE vs WAITING_FOR_SIGNAL 场景

### 并发测试

4. **RecoveryControlPlaneConcurrencyTest**
   - 并发恢复尝试
   - 发现期间 checkpoint 替换
   - 发现期间 checkpoint 删除
   - 过期描述符处理
   - 并发列表操作

### 架构测试

5. **RecoveryControlPlaneArchitectureTest**
   - ArchUnit 规则
   - 强制架构边界
   - 防止权威重复
   - 防止执行逻辑泄漏

---

## 跨实例恢复场景

### 场景 1: JVM 重启后恢复

```
时间线:
T1 - JVM A: 执行代理任务
T2 - JVM A: 治理要求批准，创建 WAITING_FOR_SIGNAL checkpoint
T3 - JVM A: 崩溃/关闭
T4 - JVM B: 启动，共享 JDBC CheckpointStore
T5 - JVM B: listContinuationsByDisposition(WAITING_FOR_SIGNAL)
T6 - JVM B: 操作员提供批准信号
T7 - JVM B: resumeContinuation(processId, version, approvalSignal)
T8 - JVM B: M6 CHECK A 验证版本，重建执行
T9 - JVM B: 执行完成，M6 CHECK B 删除 checkpoint
```

### 场景 2: 多 JVM 并发发现

```
拓扑:
- 共享 PostgreSQL CheckpointStore
- JVM A, B, C 运行相同的 DurableExecutionEngine 配置
- RuntimeBindingResolver 解析相同的 binding key

并发行为:
- JVM A, B, C 可以同时调用 listContinuations()
- 所有看到相同的 continuation
- 如果 JVM A 和 B 尝试恢复同一个进程：
  * 两者都通过 M6 CHECK A（在执行前）
  * 两者都执行工具（at-least-once）
  * 只有一个 CHECK B 成功（CAS）
  * 失败者收到 CheckpointTransitionConflictException
```

---

## 操作员工作流

### 工作流 1: 发现待批准进程

```java
RecoveryControlPlane controlPlane = ...;

// 发现所有等待批准的进程
List<RecoverableContinuation> waiting = 
    controlPlane.listContinuationsByDisposition(
        ContinuationDisposition.WAITING_FOR_SIGNAL
    );

// 检查每个进程
for (RecoverableContinuation cont : waiting) {
    System.out.printf("Process: %s%n", cont.processId());
    System.out.printf("Version: %d%n", cont.checkpointVersion());
    System.out.printf("Binding: %s%n", cont.runtimeBindingKey());
    System.out.printf("Session: %s%n", cont.sessionId());
}
```

### 工作流 2: 批准进程继续

```java
// 操作员选择进程并批准
String selectedProcessId = "incident-123";
long currentVersion = 5L;

ContinuationSignal approvalSignal = 
    new ApprovalSignal(true, "operator-approved");

try {
    controlPlane.resumeContinuation(
        selectedProcessId, 
        currentVersion, 
        approvalSignal
    );
    System.out.println("恢复成功");
} catch (StaleCheckpointException e) {
    System.out.println("版本过期 - 另一个操作员已处理");
} catch (CheckpointNotFoundException e) {
    System.out.println("进程已完成");
}
```

### 工作流 3: 自动恢复 RUNNABLE 进程

```java
// Worker 线程发现并恢复 RUNNABLE continuation
List<RecoverableContinuation> runnables = 
    controlPlane.listContinuationsByDisposition(
        ContinuationDisposition.RUNNABLE
    );

for (RecoverableContinuation cont : runnables) {
    try {
        // RUNNABLE 不需要外部信号
        controlPlane.resumeContinuation(
            cont.processId(), 
            cont.checkpointVersion(), 
            null
        );
    } catch (StaleCheckpointException e) {
        // 另一个 worker 已处理 - 继续
        continue;
    }
}
```

---

## 部署配置

### 单 JVM 配置（开发/测试）

```java
@Configuration
public class LocalRecoveryControlPlaneConfig {
    
    @Bean
    public CheckpointStore checkpointStore() {
        return new InMemoryCheckpointStore();
    }
    
    @Bean
    public RecoveryControlPlane recoveryControlPlane(
            CheckpointStore checkpointStore,
            DurableExecutionEngine durableEngine) {
        return new DefaultRecoveryControlPlane(
            checkpointStore, 
            durableEngine
        );
    }
}
```

### 多 JVM 配置（生产）

```java
@Configuration
public class DistributedRecoveryControlPlaneConfig {
    
    @Bean
    public CheckpointStore checkpointStore(DataSource dataSource) {
        return new JdbcCheckpointStore(dataSource);
    }
    
    @Bean
    public InvocationStateStore invocationStateStore(DataSource dataSource) {
        return new JdbcInvocationStateStore(dataSource);
    }
    
    @Bean
    public RecoveryControlPlane recoveryControlPlane(
            CheckpointStore checkpointStore,
            SpringAiToolCallingEngine durableEngine) {
        return new DefaultRecoveryControlPlane(
            checkpointStore, 
            durableEngine
        );
    }
}
```

**关键点:**
- 所有 JVM 共享同一个 DataSource（PostgreSQL/MySQL）
- RuntimeBindingResolver 必须在所有实例上解析相同的 binding
- 工具和模型可以是实例本地的

---

## 未来扩展方向

### V1 不包含，但可能在后续轨道实现

1. **取消操作**
   - 需要新的持久化生命周期状态
   - 需要防止未来恢复的机制
   - 需要审计记录

2. **分布式调度**
   - Worker 池管理
   - 负载均衡
   - 自动恢复策略

3. **Web Console**
   - 可视化 continuation 列表
   - 操作员批准 UI
   - 进程历史查看（集成 ExecutionLedger）

4. **高级查询**
   - 按 runtimeBindingKey 过滤
   - 按 sessionId 过滤
   - 按时间范围过滤
   - 分页

5. **Disposition 列提升**
   - SQL 级过滤优化
   - 避免 JSON 反序列化

6. **进程组/批量操作**
   - 批量批准
   - 批量取消
   - 组级可见性

---

## 验证清单

### ✅ 功能验证

- [x] InMemoryCheckpointStore 查询实现
- [x] JdbcCheckpointStore 查询实现
- [x] RecoverableContinuation 不可变快照
- [x] RecoveryControlPlane 接口定义
- [x] DefaultRecoveryControlPlane 实现
- [x] JDBC schema 升级脚本

### ✅ 测试验证

- [x] 发现操作单元测试
- [x] 操作员恢复单元测试
- [x] 跨实例 JDBC 集成测试
- [x] 并发场景测试
- [x] ArchUnit 架构测试

### ✅ 文档验证

- [x] Source Truth Findings 文档
- [x] API JavaDoc 完整
- [x] 架构决策记录
- [x] 实现总结文档

### ✅ 构建验证

- [x] Maven clean compile 成功
- [x] 无编译错误
- [x] 无编译警告（除遗留 ThreadDeath）

---

## 关键成功指标

1. **非侵入性** ✅
   - M7 不修改 M6 核心语义
   - CheckpointStore 扩展是附加的
   - 现有代码无需修改

2. **权威分离** ✅
   - CheckpointStore 保持 continuation 权威
   - DurableExecutionEngine 保持执行权威
   - Control Plane 是纯操作门面

3. **并发安全** ✅
   - M6 CAS 语义保护
   - 无新的并发原语
   - 过期描述符安全失败

4. **跨实例能力** ✅
   - JDBC 支持跨 JVM 发现
   - RuntimeBindingResolver 解耦
   - 测试验证场景

---

## 架构适应性验证

### 符合 Arctra 核心原则

1. **权威明确性** ✅
   - CheckpointStore 是 continuation 权威
   - ExecutionLedger 是历史权威
   - Control Plane 不引入新权威

2. **M6 封装性** ✅
   - 恢复完全委托给 DurableExecutionEngine
   - CHECK A/CHECK B 语义保留
   - 无执行逻辑重复

3. **可测试性** ✅
   - InMemory 实现支持快速单元测试
   - JDBC 实现支持集成测试
   - ArchUnit 保护架构边界

4. **可演进性** ✅
   - 接口为未来扩展预留空间
   - V1 范围克制
   - 迁移路径安全

---

## 结论

M7 Recovery Control Plane 成功地为 Arctra 提供了操作发现和恢复能力，同时保持了架构清洁度：

- **薄层** - 不重复 M6 逻辑
- **委托** - 依赖现有权威
- **跨实例** - JDBC 支持多 JVM 部署
- **安全** - M6 CAS 防止并发问题
- **可测试** - 完整测试覆盖
- **可演进** - 为未来扩展奠定基础

M7 是 Arctra 迈向生产可操作性的关键一步。
