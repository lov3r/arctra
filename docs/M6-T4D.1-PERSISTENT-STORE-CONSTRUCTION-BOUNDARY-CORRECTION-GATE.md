# M6-T4D.1 — PERSISTENT STORE CONSTRUCTION BOUNDARY CORRECTION GATE

**Gate Type**: Architecture / Source-Truth Correction Gate  
**Status**: COMPLETE  
**Date**: 2026-09-15  
**Author**: lov3r

---

## 执行摘要

**核心矛盾**: M6-T4D 提案同时声称 JdbcCheckpointStore 为 package-private，但又显示外部应用通过 `new JdbcCheckpointStore(dataSource)` 构造——这在逻辑上不可能。

**解决方案**: 选择 **Candidate A — Public JdbcCheckpointStore**

**关键决策**:
- JdbcCheckpointStore = **PUBLIC**
- JdbcInvocationStateStore = **PACKAGE-PRIVATE**
- 通过 instanceof 类型检测内部配对
- 持久化 checkpoint + 内存 intent = **禁止配置**（false-safe 风险）
- 内存 checkpoint + 持久化 intent = **允许但无用**
- 未知自定义 CheckpointStore = 默认内存 intent（安全降级）

---

## A. 已接受的 T4D 持久化语义

M6-T4D 已建立并冻结：

### 架构选择

**Candidate D + E Hybrid**:
- 共享物理持久化基础设施
- 独立语义权威
- 独立提交（无跨存储原子事务）
- 强读后写一致性
- fail-closed 恢复读

### 冻结决策

```text
跨存储原子事务 = NOT REQUIRED
CheckpointStore ≠ InvocationStateStore
at-least-once 语义保留
operationId 唯一性防止意图污染
孤儿意图安全（checkpoint 删除后 intent 残留）
```

### 一致性契约

- **CheckpointStore**: 强 CAS 语义（replaceIfVersion / deleteIfVersion）
- **InvocationStateStore**: 幂等写入，强读一致性
- **恢复分类**: 保守解读（unknown = MAY_HAVE_INVOKED）
- **Commit-unknown**: 通过权威重读 + 保守解释处理

---

## B. 构造矛盾

### 原始 T4D 提案

**声称 1**: JdbcCheckpointStore 为 package-private in arctra-runtime-react

**声称 2**: 应用构造示例

```java
CheckpointStore store = new JdbcCheckpointStore(dataSource);
```

### 逻辑冲突

如果 JdbcCheckpointStore 是 package-private，外部应用**无法实例化**。

如果外部应用可以实例化，那么该类**不能是 package-private**。

### 问题扩展

T4D 同时提出：

```java
if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
    invocationStateStore = 
        new JdbcInvocationStateStore(jdbcStore.getDataSource());
}
```

这意味着：
- CheckpointStore 实现类型**隐式选择** InvocationStateStore
- DataSource **从 checkpoint 存储提取**
- 持久化模式**通过类型检测激活**

**问题**: 这是否给 CheckpointStore 赋予了它不拥有的责任？

---

## C. 当前引擎构造源代码真相

### SpringAiToolCallingEngine 构造器签名

**当前 8 参数构造器** (line 151-159):

```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,              // 可选 durable 三件套
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger               // 可选
)
```

### CheckpointStore 可见性

**接口**: `public interface CheckpointStore` (arctra-core)

**实现**: `public class InMemoryCheckpointStore implements CheckpointStore` (arctra-core)

### 当前构造模式

**测试用例**:

```java
// 1. 短格式（ephemeral）
new SpringAiToolCallingEngine(chatModel, tools, chatMemory, policy)

// 2. 全格式（durable）
new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, policy,
    new FakeCheckpointStore(),
    new FakeRuntimeBindingResolver(),
    "test-key"
)
```

**应用级用法**: 示例仅使用 4 参数 ephemeral 构造器

### InvocationStateStore 构造 (line 193)

```java
InvocationStateStore invocationStateStore = new InMemoryInvocationStateStore();
```

**硬编码**: 当前引擎内部**总是使用内存 intent 存储**。

### 构造器压力

**已有**: 8 参数构造器存在，但尚未触发配置对象需求。

---

## D. 持久化存储配对不变量

### 核心不变量

**MUST**:

```text
用于同一引擎的 CheckpointStore 和 InvocationStateStore 
必须属于兼容的持久化基础设施/配置。
```

### 禁止的错误配对

```text
持久化 CheckpointStore + 内存 InvocationStateStore  → FORBIDDEN (false-safe)
内存 CheckpointStore + 持久化 InvocationStateStore  → USELESS (safe)
```

### 正确配对

```text
内存 checkpoint + 内存 intent           → 单 JVM 测试
JDBC checkpoint + JDBC intent (同 DB)  → 重启恢复
Redis checkpoint + Redis intent        → 分布式恢复
```

---

## E. 错误配置矩阵

### 场景 1: 持久化 Checkpoint + 内存 Intent

**配置**:
```java
CheckpointStore = JdbcCheckpointStore(dataSource)
InvocationStateStore = InMemoryInvocationStateStore()
```

**执行序列**:
```text
1. recordInvocationIntent(proc-1, op-A) → 内存写入
2. delegate.call(op-A) → 执行
3. JVM crash
4. JVM restart
5. checkpoint.load(proc-1) → 成功（checkpoint 持久化）
6. hasInvocationIntent(proc-1, op-A) → false（intent 丢失）
7. recovery 分类 → DEFINITELY_NOT_DISPATCHED
```

**危险**: ❌ **FALSE-SAFE STATE**

Intent 已记录且 delegate 可能已调用，但重启后 intent 丢失导致错误分类为 DEFINITELY_NOT_DISPATCHED。

**结论**: **MUST REJECT** 此配置

---

### 场景 2: 内存 Checkpoint + 持久化 Intent

**配置**:
```java
CheckpointStore = InMemoryCheckpointStore()
InvocationStateStore = JdbcInvocationStateStore(dataSource)
```

**执行序列**:
```text
1. recordInvocationIntent(proc-1, op-A) → JDBC 写入
2. delegate.call(op-A) → 执行
3. JVM crash
4. JVM restart
5. checkpoint.load(proc-1) → empty（checkpoint 丢失）
6. hasInvocationIntent(proc-1, op-A) → true（intent 持久化）
```

**结果**: 没有可恢复的 checkpoint，孤儿 intent 残留。

**危险**: ✅ **SAFE** （无 checkpoint 则无恢复尝试）

**有用性**: ❌ **USELESS** （无法恢复执行）

**结论**: 技术上安全但操作无意义，**可接受但应警告**

---

### 场景 3: 不一致的持久化后端

**配置**:
```java
CheckpointStore = JdbcCheckpointStore(postgresDB)
InvocationStateStore = RedisInvocationStateStore(redisCluster)
```

**问题**:
- 跨后端一致性依赖于独立系统
- 网络分区可能导致不对称可见性
- 运维复杂度高

**T4D 已证明**: 独立提交 + 保守分类 = 安全

**结论**: ✅ **SAFE** 但 ❌ **NOT RECOMMENDED**（运维复杂）

---

### 场景 4: 相同持久化后端

**配置**:
```java
DataSource ds = ...;
CheckpointStore = JdbcCheckpointStore(ds)
InvocationStateStore = JdbcInvocationStateStore(ds)
```

**优势**:
- 单一持久化配置
- 共享事务基础设施（如需要）
- 统一备份/恢复
- 运维简单

**结论**: ✅ **RECOMMENDED**

---

## F. Candidate A — Public JdbcCheckpointStore

### 架构

```java
// arctra-runtime-react
public final class JdbcCheckpointStore implements CheckpointStore {
    private final DataSource dataSource;
    
    public JdbcCheckpointStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }
    
    // package-private accessor for internal pairing
    DataSource getDataSource() {
        return dataSource;
    }
}
```

### 应用构造

```java
DataSource dataSource = ...; // 应用配置
CheckpointStore store = new JdbcCheckpointStore(dataSource);

new SpringAiToolCallingEngine(
    chatModel, tools, chatMemory, policy,
    store,  // ← 直接传入
    bindingResolver, runtimeBindingKey, ledger
)
```

### 引擎内部配对

```java
// SpringAiToolCallingEngine 构造器内部
InvocationStateStore invocationStateStore;

if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
    // 检测到 JDBC → 使用匹配的 JDBC intent store
    invocationStateStore = 
        new JdbcInvocationStateStore(jdbcStore.getDataSource());
} else {
    // 默认/未知 → 内存模式（安全降级）
    invocationStateStore = new InMemoryInvocationStateStore();
}
```

### 优势

✅ **最小 public API 扩展**: 仅暴露具体实现类，不新增抽象  
✅ **零构造器签名变化**: 使用现有 CheckpointStore 参数  
✅ **InvocationStateStore 保持 package-private**  
✅ **DataSource getter 保持 package-private**（同包访问）  
✅ **配对保证**: instanceof 检测确保匹配  
✅ **安全降级**: 未知实现 → 内存 intent（保守）

### 劣势

⚠️ **冻结具体实现**: JdbcCheckpointStore 成为 public API  
⚠️ **instanceof 耦合**: 类型检测非多态  
⚠️ **未来迁移成本**: 若需抽象工厂需保留兼容

### Public API 影响

**新增 public 类**:
- `JdbcCheckpointStore` (arctra-runtime-react)

**未新增**:
- ❌ 构造器参数
- ❌ 配置对象
- ❌ 工厂接口
- ❌ InvocationStateStore 公开

---

## G. Candidate B — Public Factory

### 架构

```java
// arctra-runtime-react
public final class DurableStores {
    
    public static CheckpointStore jdbc(DataSource dataSource) {
        // 返回内部持有配对能力的 checkpoint store
        return new JdbcCheckpointStoreWithPairing(dataSource);
    }
    
    private DurableStores() {}
}

// Package-private internal
final class JdbcCheckpointStoreWithPairing implements CheckpointStore {
    private final DataSource dataSource;
    
    JdbcInvocationStateStore createPairedInvocationStore() {
        return new JdbcInvocationStateStore(dataSource);
    }
}
```

### 应用构造

```java
CheckpointStore store = DurableStores.jdbc(dataSource);
```

### 引擎内部配对

```java
if (checkpointStore instanceof JdbcCheckpointStoreWithPairing paired) {
    invocationStateStore = paired.createPairedInvocationStore();
} else {
    invocationStateStore = new InMemoryInvocationStateStore();
}
```

### 优势

✅ **隐藏具体实现**: JdbcCheckpointStore 保持 package-private  
✅ **明确配对语义**: 工厂方法表达"配对基础设施"

### 劣势

❌ **隐式能力接口**: `createPairedInvocationStore()` 是伪装的能力  
❌ **抽象过早**: 仅一个实现时引入工厂  
❌ **用户困惑**: 为何不直接 `new JdbcCheckpointStore`？

### 评估

⚠️ **过度工程**: 工厂模式在单实现时属于 YAGNI

---

## H. Candidate C — Durable Configuration

### 架构

```java
// arctra-runtime-react
public final class DurableExecutionConfiguration {
    private final CheckpointStore checkpointStore;
    private final RuntimeBindingResolver bindingResolver;
    private final String runtimeBindingKey;
    private final ExecutionLedger executionLedger;
    
    // package-private for engine access
    final InvocationStateStore invocationStateStore;
    
    public static DurableExecutionConfiguration jdbc(
        DataSource dataSource,
        RuntimeBindingResolver resolver,
        String bindingKey,
        ExecutionLedger ledger
    ) {
        return new DurableExecutionConfiguration(
            new JdbcCheckpointStore(dataSource),
            resolver,
            bindingKey,
            ledger,
            new JdbcInvocationStateStore(dataSource)
        );
    }
}
```

### 新构造器

```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    DurableExecutionConfiguration durableConfig  // 新参数
)
```

### 优势

✅ **参数分组**: 8 参数 → 5 参数  
✅ **明确配对**: 配置对象内部保证一致性  
✅ **未来扩展**: 可添加更多持久化配置

### 劣势

❌ **Public API 膨胀**: 新 public 配置类  
❌ **构造器签名变化**: 破坏现有用法  
❌ **过度设计**: 压力尚不足以证明需要

### 评估

⚠️ **JUSTIFIED LATER**: 配置压力确实存在，但 T4E 可延后

---

## I. Candidate D — Builder

### 架构

```java
SpringAiToolCallingEngine engine = SpringAiToolCallingEngine.builder()
    .chatModel(chatModel)
    .tools(tools)
    .chatMemory(chatMemory)
    .governancePolicy(policy)
    .durableExecution(durable -> durable
        .checkpointStore(jdbcCheckpointStore)
        .bindingResolver(resolver)
        .bindingKey("key")
        .ledger(ledger))
    .build();
```

### 评估

❌ **超出 T4E 范围**: Builder 解决的问题远大于持久化构造  
❌ **Public API 大变化**: 全新构造模式  
❌ **YAGNI**: 当前构造器压力尚可接受

**分类**: **NOT JUSTIFIED NOW**

---

## J. Candidate E — Persistence Handle

### 架构概念

```java
public interface DurablePersistence {
    CheckpointStore checkpointStore();
    // InvocationStateStore 保持隐藏
}
```

### 问题

❌ **新 public SPI**: 持久化抽象过早  
❌ **语义不清**: handle 是否拥有生命周期？  
❌ **复杂度不当**: 为配对引入新抽象层

**分类**: **REJECTED**

---

## K. Candidate F — Internal-Only T4E

### 概念

T4E 仅实现 **package-private** JDBC 存储用于内部测试，**不提供**应用可配置持久化。

### 重命名里程碑

```text
M6-T4E — JDBC Persistent Store Reference Implementation (Internal)
```

### 优势

✅ **Public API delta = 0** (真正的零)  
✅ **契约成熟**: 实现验证接口稳定性  
✅ **无过早承诺**: 不冻结 public 实现

### 劣势

❌ **不可用于应用**: T4E 不交付生产持久化能力  
❌ **里程碑误导**: 名称暗示可用性

### 评估

⚠️ **诚实但受限**: 如选择此路径，必须明确文档化为"内部参考实现"

---

## L. Candidate G — New Persistence Module

### 概念

```text
arctra-persistence-jdbc/
  ├── JdbcCheckpointStore (public)
  └── JdbcInvocationStateStore (???)
```

### 问题

❌ **跨模块访问**: JdbcInvocationStateStore 无法实现 runtime-react 的 package-private 接口  
❌ **强制提升**: 需要将 InvocationStateStore 提升为 public 或创建副本  
❌ **无第二消费者**: YAGNI 原则不满足

**分类**: **NOT JUSTIFIED NOW**

---

## M. 候选方案对比

| 维度 | A: Public JDBC | B: Factory | C: Config | D: Builder | E: Handle | F: Internal | G: Module |
|------|---------------|-----------|-----------|-----------|-----------|-------------|-----------|
| **应用可用** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| **Public API delta** | +1 class | +1 class | +1 class | +3+ classes | +1 interface | 0 | +1 class |
| **InvocationStateStore 内部** | ✅ | ✅ | ⚠️ | ⚠️ | ❌ | ✅ | ❌ |
| **构造器压力** | 不解决 | 不解决 | 解决 | 解决 | 不解决 | 不解决 | 不解决 |
| **权威清晰** | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| **未来迁移** | 中等 | 容易 | 容易 | 容易 | 难 | 容易 | 中等 |
| **YAGNI 合规** | ✅ | ⚠️ | ⚠️ | ❌ | ❌ | ✅ | ❌ |
| **企业方向** | ✅ | ⚠️ | ✅ | ✅ | ❌ | ❌ | ⚠️ |

---

## N. 选定构造架构

**选择**: **Candidate A — Public JdbcCheckpointStore**

### 理由

1. ✅ **最小扩展**: 仅暴露一个具体实现类
2. ✅ **零构造器变化**: 使用现有参数
3. ✅ **InvocationStateStore 保持内部**: package-private 未被破坏
4. ✅ **配对保证**: instanceof 检测确保一致性
5. ✅ **安全降级**: 未知实现 → 内存模式
6. ✅ **YAGNI 合规**: 不引入过早抽象
7. ✅ **诚实 API**: 承认第一方实现需要 public 可见性

### 临时机制确认

**instanceof 检测**: 接受为**临时内部接线**

**替换触发器**:
- 第二个持久化后端实现
- 第二个执行引擎需要配对
- 独立持久化模块创建

---

## O. JdbcCheckpointStore 可见性

**决策**: **PUBLIC**

**位置**: `arctra-runtime-react`

**声明**:
```java
public final class JdbcCheckpointStore implements CheckpointStore
```

**原因**: 外部应用必须能够实例化以配置持久化模式

---

## P. JdbcInvocationStateStore 可见性

**决策**: **PACKAGE-PRIVATE**

**位置**: `arctra-runtime-react`

**声明**:
```java
final class JdbcInvocationStateStore implements InvocationStateStore
```

**原因**:
- 无第二执行引擎消费者
- 契约仍在成熟中
- 过早 public 冻结兼容性负担

---

## Q. 应用构造入口

### 用户代码

```java
// 1. 配置 DataSource（应用责任）
DataSource dataSource = createDataSource();

// 2. 构造持久化 checkpoint store
CheckpointStore checkpointStore = new JdbcCheckpointStore(dataSource);

// 3. 传入引擎（现有构造器，无变化）
SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(
    chatModel,
    tools,
    chatMemory,
    governancePolicy,
    checkpointStore,      // ← 持久化存储
    bindingResolver,
    runtimeBindingKey,
    executionLedger
);
```

### 与内存模式对比

```java
// 内存模式（测试/单 JVM）
CheckpointStore checkpointStore = new InMemoryCheckpointStore();

// 持久化模式（生产/重启恢复）
CheckpointStore checkpointStore = new JdbcCheckpointStore(dataSource);

// 构造器调用完全相同
new SpringAiToolCallingEngine(..., checkpointStore, ...);
```

---

## R. 引擎内部配对机制

### SpringAiToolCallingEngine 构造器内部

```java
public SpringAiToolCallingEngine(
    ChatModel chatModel,
    List<ToolCallback> tools,
    ChatMemory chatMemory,
    ToolGovernancePolicy governancePolicy,
    CheckpointStore checkpointStore,
    RuntimeBindingResolver bindingResolver,
    String runtimeBindingKey,
    ExecutionLedger executionLedger) {
    
    // ... 现有验证 ...
    
    this.checkpointStore = checkpointStore;
    this.bindingResolver = bindingResolver;
    this.runtimeBindingKey = runtimeBindingKey;
    
    // M6-T4E: 基于 checkpoint store 类型选择匹配的 invocation store
    InvocationStateStore invocationStateStore = 
        createMatchingInvocationStateStore(checkpointStore);
    
    // ... 其余构造逻辑 ...
}

private InvocationStateStore createMatchingInvocationStateStore(
    CheckpointStore checkpointStore) {
    
    if (checkpointStore == null) {
        // Ephemeral mode
        return new InMemoryInvocationStateStore();
    }
    
    if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
        // JDBC persistent mode - pair with matching JDBC intent store
        return new JdbcInvocationStateStore(jdbcStore.getDataSource());
    }
    
    // Unknown/custom checkpoint store - safe fallback to in-memory
    // NOTE: This means custom persistent CheckpointStore implementations
    // will NOT get restart-durable recovery guarantees unless explicitly
    // paired through future configuration mechanism
    return new InMemoryInvocationStateStore();
}
```

### DataSource 访问

```java
// JdbcCheckpointStore
public final class JdbcCheckpointStore implements CheckpointStore {
    private final DataSource dataSource;
    
    // Package-private accessor (same package as SpringAiToolCallingEngine)
    DataSource getDataSource() {
        return dataSource;
    }
}
```

**可见性**: `getDataSource()` 为 **package-private**，仅引擎内部访问

---

## S. DataSource 所有权

**明确**: **Application owns DataSource**

### 应用责任

- 连接池配置
- JDBC URL / 凭证
- 事务管理器（如需）
- 连接生命周期
- 健康检查

### 框架职责

- 接收已配置的 DataSource
- 使用 DataSource 创建持久化存储
- 不拥有连接管理

### 示例

```java
// 应用配置 DataSource
DataSource dataSource = DataSourceBuilder.create()
    .url("jdbc:postgresql://localhost:5432/arctra")
    .username("arctra")
    .password("...")
    .build();

// 框架使用 DataSource
CheckpointStore store = new JdbcCheckpointStore(dataSource);
```

---

## T. 未知自定义 CheckpointStore 语义

### 场景

用户实现自定义持久化 CheckpointStore：

```java
public class CustomPersistentCheckpointStore implements CheckpointStore {
    // 自定义持久化逻辑
}
```

### 引擎行为

```java
CheckpointStore custom = new CustomPersistentCheckpointStore();
SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(
    ..., custom, ...
);

// 内部：
invocationStateStore = new InMemoryInvocationStateStore();  // 降级
```

### 语义

**配对**: Custom checkpoint + **内存 intent**

**重启后**:
- Checkpoint 存在（持久化）
- Intent 丢失（内存）
- **FALSE-SAFE RISK**

### 处理策略

**选项 1**: 接受配置但**不保证重启恢复**

**选项 2**: 在构造时拒绝未知持久化 checkpoint

**选项 3**: 要求显式配对（未来）

### 选定策略

**选项 1**: **接受但不保证**

**理由**:
- 自定义 CheckpointStore 可能是测试实现（非持久化）
- 拒绝会破坏可扩展性
- 未来可通过配置对象显式配对

**文档要求**:

```text
WARNING: Custom CheckpointStore implementations will be paired
with InMemoryInvocationStateStore by default. This configuration
does NOT provide restart-durable recovery guarantees.

For restart durability, use provided implementations:
- JdbcCheckpointStore (recommended)
- Future: RedisCheckpointStore

Or contact framework team for custom pairing support.
```

---

## U. 重启持久化保证边界

### 保证级别

| CheckpointStore | InvocationStateStore | 重启恢复保证 |
|----------------|---------------------|------------|
| InMemory | InMemory | ❌ 无（JVM 本地） |
| JDBC | JDBC（匹配） | ✅ **完全保证** |
| JDBC | InMemory | ❌ **拒绝配置**（false-safe） |
| InMemory | JDBC | ⚠️ 安全但无用 |
| Custom | InMemory | ⚠️ **无保证**（降级） |
| JDBC | Custom | ❓ 未来 |

### 配置验证

**T4E 实现**: 引擎**不主动验证**配对一致性（信任类型检测）

**未来增强**: 可添加显式验证：

```java
if (checkpointStore.isPersistent() && 
    !invocationStateStore.isPersistent()) {
    throw new IllegalArgumentException(
        "Persistent checkpoint requires persistent invocation store");
}
```

**T4E 决策**: **不实现** capability 接口（YAGNI）

---

## V. Public API 影响

### 新增 Public API

**类**:
- `JdbcCheckpointStore` (arctra-runtime-react)

**方法**:
- `JdbcCheckpointStore(DataSource)`

### 未变化

- ❌ CheckpointStore 接口（已存在）
- ❌ 构造器签名（使用现有参数）
- ❌ InvocationStateStore（保持 package-private）

### API 稳定性

**Public 承诺**:
- JdbcCheckpointStore 构造器签名
- CheckpointStore 实现契约

**可变部分**（未 public）:
- DataSource accessor
- InvocationStateStore 配对机制

---

## W. Core 影响

**arctra-core 模块变化**: **ZERO**

**理由**:
- CheckpointStore 接口已存在
- JdbcCheckpointStore 位于 runtime-react
- 无新 core 契约

---

## X. 构造器影响

**SpringAiToolCallingEngine 构造器签名**: **UNCHANGED**

**内部实现变化**:

```diff
  public SpringAiToolCallingEngine(..., CheckpointStore checkpointStore, ...) {
      // ...
      
-     InvocationStateStore invocationStateStore = new InMemoryInvocationStateStore();
+     InvocationStateStore invocationStateStore = 
+         createMatchingInvocationStateStore(checkpointStore);
      
      // ...
  }
```

**测试兼容性**: ✅ 所有现有测试无需修改

---

## Y. 模块影响

**新模块**: ❌ **NO**

**位置**: `arctra-runtime-react`

**类**:
```text
arctra-runtime-react/
  └── cn.bitcss.arctra.runtime.react/
      ├── JdbcCheckpointStore.java (NEW, public)
      ├── JdbcInvocationStateStore.java (NEW, package-private)
      ├── InvocationStateStore.java (existing, package-private)
      ├── InMemoryInvocationStateStore.java (existing)
      └── SpringAiToolCallingEngine.java (modified)
```

**未创建**:
- ❌ arctra-persistence-jdbc
- ❌ 独立配置模块

---

## Z. M6-T4E 最终名称

**原名称**: M6-T4E — Persistent CheckpointStore Implementation

**问题**: 名称仅提及 CheckpointStore，但实际包含两个存储

**修正名称**: 

```text
M6-T4E — JDBC Durable Recovery Store Pair Implementation
```

**或**:

```text
M6-T4E — JDBC Persistent Checkpoint + Intent Stores
```

**推荐**: **M6-T4E — JDBC Durable Recovery Store Pair Implementation**

---

## AA. M6-T4E 精确范围

### 包含

1. **JdbcCheckpointStore** (public)
   - 实现 CheckpointStore
   - create / load / replaceIfVersion / deleteIfVersion
   - DataSource 构造
   - 事务边界
   
2. **JdbcInvocationStateStore** (package-private)
   - 实现 InvocationStateStore
   - recordInvocationIntent / hasInvocationIntent
   - 相同 DataSource
   
3. **Schema 设计**
   ```sql
   CREATE TABLE checkpoints (...);
   CREATE TABLE invocation_intents (...);
   ```
   
4. **Checkpoint 序列化**
   - SuspensionCheckpoint → JSON
   - PendingToolCall 序列化
   - Evidence 序列化策略
   
5. **引擎配对机制**
   - instanceof 检测
   - createMatchingInvocationStateStore()
   - DataSource 提取
   
6. **测试**
   - 重启持久化测试
   - 多实例可见性
   - CAS 并发
   - Intent 幂等性
   - Read-after-write 验证
   
7. **文档**
   - 配置指南
   - Schema 迁移
   - 自定义 CheckpointStore 警告

### 明确不包含

❌ executionEpoch  
❌ 自动重启激活  
❌ attemptId  
❌ 重试逻辑  
❌ RecoveryPolicy  
❌ 配置对象  
❌ Builder 模式  
❌ 独立持久化模块  
❌ Redis 实现  
❌ Claim/lease/fencing

---

## AB. 入口门问题（38 个）

### 构造与可见性 (1-10)

1. **外部应用今天能实例化建议的 JdbcCheckpointStore 吗？**  
   ✅ **YES** — 选定方案为 public

2. **如果 package-private，如何外部激活持久化模式？**  
   N/A — 选择 public 解决了此问题

3. **Public API delta 零 和 外部持久化可用性 能同时为真吗？**  
   ❌ **NO** — 这是原始矛盾的核心

4. **当前 T4D 提案在此点上是否自相矛盾？**  
   ✅ **YES** — package-private 与外部构造不兼容

5. **持久化 CheckpointStore 和 InvocationStateStore 必须作为连贯配对配置吗？**  
   ✅ **YES** — 配对正确性是强制性的

6. **配对正确性与事务原子性不同吗？**  
   ✅ **YES** — 配置配对 ≠ 运行时跨存储事务

7. **持久化 checkpoint + 内存 intent 能创建 false-safe 恢复吗？**  
   ✅ **YES** — 重启后 intent 丢失导致错误分类

8. **因此该配置必须被拒绝/阻止吗？**  
   ✅ **YES** — 通过不创建该配对实现

9. **内存 checkpoint + 持久化 intent 是否恢复正确？**  
   ✅ **YES** — 安全（无 checkpoint 则无恢复）

10. **是否操作有用？**  
    ❌ **NO** — 无法恢复执行

### 语义与权威 (11-20)

11. **CheckpointStore 在语义上拥有 invocation-store 选择权吗？**  
    ❌ **NO** — 它是 checkpoint 权威，非配置权威

12. **实现类型检测能容忍为临时接线吗？**  
    ✅ **YES** — 明确文档化为临时机制

13. **类型检测创建 public API 吗？**  
    ❌ **NO** — instanceof 是内部实现细节

14. **DataSource accessor 能保持 package-private 吗？**  
    ✅ **YES** — 同包访问足够

15. **JdbcInvocationStateStore 应保持 package-private 吗？**  
    ✅ **YES** — 无第二消费者

16. **JdbcCheckpointStore 应该 public 吗？**  
    ✅ **YES** — 应用需要实例化

17. **Public JdbcCheckpointStore 构成过早 SPI 提升吗？**  
    ❌ **NO** — 具体实现 ≠ SPI 抽象

18. **Public factory 比 public 配置对象更小吗？**  
    ⚠️ **SIMILAR** — 都是 +1 public 类

19. **配置对象压力已到来吗？**  
    ⚠️ **EMERGING** — 8 参数但尚可管理

20. **必须现在解决吗？**  
    ❌ **NO** — T4E 可延后

### 架构与范围 (21-30)

21. **Builder 引入现在是否正当？**  
    ❌ **NO** — 超出 T4E 范围

22. **新持久化模块现在是否正当？**  
    ❌ **NO** — 无第二实现

23. **InvocationStateStore 必须现在提升吗？**  
    ❌ **NO** — 保持内部

24. **Core 能保持不变吗？**  
    ✅ **YES** — 零 core 变化

25. **引擎构造器签名能保持不变吗？**  
    ✅ **YES** — 使用现有参数

26. **自定义 CheckpointStore 实现如何处理？**  
    **默认内存 intent（安全降级）**

27. **自定义持久化 CheckpointStore 能安全使用隐式 InMemoryInvocationStateStore 吗？**  
    ❌ **NO** — 但作为降级接受（不保证重启恢复）

28. **重启持久化保证必须限于已知配对基础设施吗？**  
    ✅ **YES** — 仅 JDBC+JDBC 保证

29. **M6-T4E 名称当前是否误导？**  
    ✅ **YES** — 应反映双存储实现

30. **T4E 应明确实现两个存储吗？**  
    ✅ **YES** — checkpoint + intent pair

### 范围确认 (31-38)

31. **自动激活仍超出范围吗？**  
    ✅ **YES** — M6-T4F

32. **executionEpoch 仍超出范围吗？**  
    ✅ **YES** — M6-T4F

33. **attemptId 仍超出范围吗？**  
    ✅ **YES** — M6-T4G

34. **RecoveryPolicy 仍超出范围吗？**  
    ✅ **YES** — M6-T4G

35. **Claim/lease/fencing 仍超出范围吗？**  
    ✅ **YES** — 可预见未来拒绝

36. **T4D 持久化一致性架构仍不变接受吗？**  
    ✅ **YES** — 候选 D+E 冻结

37. **构造边界现在是否充分明确？**  
    ✅ **YES** — 矛盾已解决

38. **此修正后 M6-T4E 实现能否安全开始？**  
    ✅ **YES** — 架构清晰

---

## AC. 最终决策

### 构造架构选择

**Candidate A — Public JdbcCheckpointStore**

### M6-T4E 最终名称

**M6-T4E — JDBC Durable Recovery Store Pair Implementation**

### 可见性决策

- **JdbcCheckpointStore**: PUBLIC
- **JdbcInvocationStateStore**: PACKAGE-PRIVATE

### 应用构造入口

```java
CheckpointStore store = new JdbcCheckpointStore(dataSource);
// 传入现有构造器
```

### 引擎配对机制

```java
instanceof JdbcCheckpointStore → JdbcInvocationStateStore(shared DataSource)
其他 → InMemoryInvocationStateStore() (安全降级)
```

### DataSource 所有者

**Application**

### 配对机制

**类型检测（临时）** — instanceof + DataSource 提取

### 未知/自定义 CheckpointStore 行为

**默认内存 intent** — 安全降级，不保证重启恢复

### 配置规则

- **持久化 checkpoint + 内存 intent**: ❌ **不创建**（引擎不制造此配对）
- **内存 checkpoint + 持久化 intent**: ⚠️ **允许但无用**
- **自定义 checkpoint + 内存 intent**: ✅ **允许但不保证重启恢复**

### Public API 影响

**+1 public class**: JdbcCheckpointStore

### Core 影响

**ZERO**

### 构造器签名影响

**ZERO** — 内部实现变化

### 新模块

**NO**

### InvocationStateStore 提升

**NO** — 保持 package-private

### instanceof 接线状态

**临时可接受机制**

### 临时接线替换触发器

- 第二持久化后端
- 第二执行引擎
- 独立持久化模块

### M6-T4E 实现范围

见 Section AA — 双存储 + schema + 序列化 + 测试

---

## GATE STATUS: ✅ GO — M6-T4E IMPLEMENTATION MAY BEGIN

**架构**: 验证完成  
**矛盾**: 已解决  
**构造边界**: 明确  
**配对不变量**: 定义  
**Public API**: 最小扩展  
**YAGNI**: 合规  

**M6-T4E — JDBC Durable Recovery Store Pair Implementation** 获准开始。

---

## HARD STOP

M6-T4D.1 Architecture Correction Gate **COMPLETE**.

**DO NOT IMPLEMENT**:
- JdbcCheckpointStore
- JdbcInvocationStateStore  
- Schema creation
- Serialization logic
- DataSource wiring
- Migration scripts
- executionEpoch
- attemptId
- RecoveryPolicy
- Retry logic
- Automatic activation
- Claim/lease/fencing

**Awaiting**: Architecture review and M6-T4E implementation approval.

---

**END M6-T4D.1 PERSISTENT STORE CONSTRUCTION BOUNDARY CORRECTION GATE**
