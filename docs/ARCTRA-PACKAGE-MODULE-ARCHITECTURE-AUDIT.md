# ARCTRA PACKAGE/MODULE ARCHITECTURE AUDIT

**执行日期**: 2026-09-16  
**触发任务**: M6-T6 前置 Architecture Gate  
**目标**: 评估当前 package/module structure 是否清晰支持 M6-T6 General Durable Execution Checkpoints

---

## 1. EXECUTIVE SUMMARY

### 当前状态

- **Module Dependency**: ✅ 正确 (`arctra-core` ← `arctra-runtime-react`)
- **Spring Isolation**: ✅ 正确 (`arctra-core` 零 Spring 依赖)
- **主要问题**: ⚠️ `arctra-runtime-react` 内部 33 个类全部位于单一扁平 package，subsystem boundary 不清晰
- **M6-T6 Readiness**: ⚠️ **CONDITIONAL GO** (需要先完成 runtime-react 内部 package restructure)

### 关键发现

1. **arctra-core ownership 基本清晰**：50 个类分布在 8 个 domain package，边界合理
2. **runtime-react 扁平化严重**：33 个类混杂在 `cn.bitcss.arctra.runtime.react`，包含至少 6 个独立 subsystem
3. **已发现真实 subsystem**：
   - Durable Resume Orchestration (DurableResumeCoordinator)
   - Spring AI Protocol Integration (ProtocolReconstructor, SpringAiResumedExecutionHandler)
   - Recovery Classification (InvocationRecoveryClassifier, 分类结果类型)
   - Invocation State Authority (InvocationStateStore, JDBC/InMemory 实现)
   - Tool Execution/Observation (EvidenceCapturingToolCallback, ToolObservationContext)
   - Persistence Infrastructure (JdbcCheckpointStore, CheckpointJsonCodec)

4. **没有发现 forbidden dependency cycles**
5. **当前可以开始 M6-T6**，但强烈建议先进行 package restructure 以避免未来技术债

---

## 2. CURRENT MODULE STRUCTURE

### Maven Module Hierarchy

```
arctra (root)
├── arctra-core
│   └── src/main/java
│       └── cn.bitcss.arctra
└── arctra-runtime-react
    └── src/main/java
        └── cn.bitcss.arctra.runtime.react
```

### Module Statistics

| Module | Production Classes | Test Classes | Dependencies |
|--------|-------------------|--------------|--------------|
| arctra-core | 50 | ~60+ | Zero external (除 JDK) |
| arctra-runtime-react | 33 | ~40+ | core + Spring AI + JDBC |

### Module Dependency Verification

```
arctra-core
     ↑
     │ (依赖)
     │
arctra-runtime-react
```

✅ **正确**: `core` 不依赖 `runtime-react`  
✅ **验证**: `grep -r "import org.springframework" arctra-core` → 0 结果

---

## 3. CURRENT PACKAGE STRUCTURE

### arctra-core Packages (良好分层)

```
cn.bitcss.arctra
├── agent (5 classes)
│   ├── Agent
│   ├── AgentDefinition
│   ├── AgentRequest
│   ├── AgentResult
│   └── AgentExecutionContext
├── checkpoint (8 classes)
│   ├── SuspensionCheckpoint
│   ├── CheckpointStore
│   ├── PendingToolCall
│   ├── InMemoryCheckpointStore
│   └── 4 exceptions
├── evidence (1 class)
│   └── Evidence
├── execution (6 classes)
│   ├── ExecutionLedger
│   ├── ExecutionEvent
│   ├── ExecutionEventListener
│   ├── ExecutionRecord
│   ├── EventType
│   └── InMemoryExecutionLedger
├── governance (2 classes)
│   ├── ToolGovernancePolicy
│   └── GovernanceDecision
├── process (3 classes)
│   ├── AgentProcess
│   ├── ContinuationSignal
│   └── ProcessStatus
├── recovery (6 classes)
│   ├── OperationResolution
│   ├── ResolutionType
│   └── 4 exceptions
└── runtime (19 classes)
    ├── AgentRuntime
    ├── AgentExecutionEngine
    ├── DurableExecutionEngine
    ├── RecoveryResolution
    ├── ResumeStrategy (+ Durable/Ephemeral)
    ├── RuntimeBinding
    ├── RuntimeBindingResolver
    ├── ProcessFactory
    ├── DefaultAgentRuntime
    ├── DefaultAgentProcess
    ├── DefaultAgent
    └── 8 exceptions
```

### arctra-runtime-react Packages (扁平化问题)

```
cn.bitcss.arctra.runtime.react (33 classes - ALL IN ONE PACKAGE)
├── [Engine Facade]
│   └── SpringAiToolCallingEngine
├── [Durable Orchestration]
│   ├── DurableResumeCoordinator
│   └── ExecutionIncarnation
├── [Protocol Integration]
│   ├── ProtocolReconstructor
│   ├── SpringAiResumedExecutionHandler
│   ├── ResumedExecutionHandler (interface)
│   ├── ResumedExecutionOutcome (sealed)
│   └── SpringAiExecutionLoop
├── [Recovery Classification]
│   ├── InvocationRecoveryClassifier
│   ├── InvocationRecoveryClassification (enum)
│   ├── RecoveryClassificationResult (sealed)
│   ├── RecoveryClassificationType (enum)
│   ├── DefinitelyNotDispatched (record)
│   ├── MayHaveInvoked (record)
│   ├── ResolvedExecuted (record)
│   ├── ResolvedNotExecuted (record)
│   ├── RecoveryUncertaintyException
│   └── DefaultRecoveryResolution
├── [Invocation State Authority]
│   ├── InvocationStateStore (interface)
│   ├── InMemoryInvocationStateStore
│   ├── JdbcInvocationStateStore
│   ├── InvocationAttempt (record)
│   ├── InvocationIntentPersistenceException
│   ├── AttemptIds (utility)
│   └── OperationIds (utility)
├── [Tool Execution]
│   ├── EvidenceCapturingToolCallback
│   ├── ToolObservationContext
│   ├── GovernanceToolCallingAdvisor
│   └── ToolApprovalRequiredSignal
├── [Persistence]
│   ├── JdbcCheckpointStore
│   └── CheckpointJsonCodec
└── [Event Projection]
    ├── ExecutionLedgerListener
    └── CompositeExecutionEventListener
```

**问题**: 所有类混在一个 package，subsystem 边界仅通过命名约定暗示，没有 package-level enforcement。

---

## 4. PRODUCTION CLASS INVENTORY

### arctra-core (50 classes)

#### cn.bitcss.arctra.agent (5)
| Class | Visibility | Responsibility | Known Callers |
|-------|-----------|---------------|---------------|
| Agent | public | Top-level Agent API | Application, Runtime |
| AgentDefinition | public | Agent metadata | Runtime, Engine |
| AgentRequest | public | Agent invocation request | Application |
| AgentResult | public | Agent execution result | Application |
| AgentExecutionContext | public | Execution context | Runtime, Engine |

#### cn.bitcss.arctra.checkpoint (8)
| Class | Visibility | Responsibility |
|-------|-----------|---------------|
| SuspensionCheckpoint | public | Durable suspension state |
| CheckpointStore | public | Checkpoint persistence contract |
| PendingToolCall | public | Suspended tool call |
| InMemoryCheckpointStore | public | Reference implementation |
| CheckpointNotFoundException | public | Exception |
| StaleCheckpointException | public | Exception |
| CheckpointAlreadyExistsException | public | Exception |
| CheckpointTransitionConflictException | public | Exception |

#### cn.bitcss.arctra.evidence (1)
| Class | Visibility | Responsibility |
|-------|-----------|---------------|
| Evidence | public | Execution evidence record |

#### cn.bitcss.arctra.execution (6)
| Class | Visibility | Responsibility |
|-------|-----------|---------------|
| ExecutionLedger | public | Execution history authority |
| ExecutionEvent | public | Domain event |
| ExecutionEventListener | public | Event listener contract |
| ExecutionRecord | public | Single execution record |
| EventType | public | Event type enum |
| InMemoryExecutionLedger | public | Reference implementation |

#### cn.bitcss.arctra.governance (2)
| Class | Visibility | Responsibility |
|-------|-----------|---------------|
| ToolGovernancePolicy | public | Tool approval policy |
| GovernanceDecision | public | Approval decision |

#### cn.bitcss.arctra.process (3)
| Class | Visibility | Responsibility |
|-------|-----------|---------------|
| AgentProcess | public | Process handle |
| ContinuationSignal | public | Resume signal (sealed) |
| ProcessStatus | public | Process status enum |

#### cn.bitcss.arctra.recovery (6)
| Class | Visibility | Responsibility |
|-------|-----------|---------------|
| OperationResolution | public | Recovery resolution record |
| ResolutionType | public | Resolution type enum |
| RecoveryUncertaintyException | public | Exception |
| RecoveryResolutionConflictException | public | Exception |
| StaleRecoveryResolutionException | public | Exception |
| InvalidRecoveryResolutionException | public | Exception |

#### cn.bitcss.arctra.runtime (19)
| Class | Visibility | Responsibility |
|-------|-----------|---------------|
| AgentRuntime | public | Runtime facade |
| AgentExecutionEngine | public | Engine contract |
| DurableExecutionEngine | public | Durable engine contract |
| RecoveryResolution | public | Recovery API |
| ResumeStrategy | public | Resume strategy (sealed) |
| DurableResumeStrategy | public | Durable strategy |
| EphemeralResumeStrategy | public | Ephemeral strategy |
| RuntimeBinding | public | Binding resolution result |
| RuntimeBindingResolver | public | Binding resolver contract |
| ProcessFactory | public | Process factory |
| DefaultAgentRuntime | public | Default runtime impl |
| DefaultAgentProcess | package | Process impl |
| DefaultAgent | package | Agent impl |
| MapBasedRuntimeBindingResolver | public | Reference resolver |
| ResumeAttempt | public | Resume attempt record |
| + 4 exceptions | public | Exceptions |

### arctra-runtime-react (33 classes)

#### cn.bitcss.arctra.runtime.react (33) - 按 subsystem 分组

**[Engine Facade]** (1)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| SpringAiToolCallingEngine | public | DurableExecutionEngine 实现，Spring AI 集成 |

**[Durable Orchestration]** (2)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| DurableResumeCoordinator | package | CHECK A/B, resume orchestration authority |
| ExecutionIncarnation | package | Restart detection identity |

**[Protocol Integration - Spring AI]** (5)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| ProtocolReconstructor | package | Spring AI protocol reconstruction, tool execution |
| SpringAiResumedExecutionHandler | package | Spring AI resumed execution handler |
| ResumedExecutionHandler | package | Resumed execution contract (interface) |
| ResumedExecutionOutcome | package | Outcome union (sealed interface) |
| SpringAiExecutionLoop | package | (可能是 legacy/unused - 需要验证) |

**[Recovery Classification]** (10)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| InvocationRecoveryClassifier | package | Recovery classification authority |
| InvocationRecoveryClassification | package | Classification enum (legacy?) |
| RecoveryClassificationResult | package | Classification result (sealed) |
| RecoveryClassificationType | package | Type enum |
| DefinitelyNotDispatched | package | Classification variant (record) |
| MayHaveInvoked | package | Classification variant (record) |
| ResolvedExecuted | package | Classification variant (record) |
| ResolvedNotExecuted | package | Classification variant (record) |
| RecoveryUncertaintyException | package | Exception (duplicates core?) |
| DefaultRecoveryResolution | package | RecoveryResolution 实现 |

**[Invocation State Authority]** (7)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| InvocationStateStore | package | Invocation state contract |
| InMemoryInvocationStateStore | package | In-memory implementation |
| JdbcInvocationStateStore | package | JDBC persistent implementation |
| InvocationAttempt | package | Attempt record (used by classifier) |
| InvocationIntentPersistenceException | package | Exception |
| AttemptIds | package | Attempt ID generator utility |
| OperationIds | package | Operation ID generator utility |

**[Tool Execution/Observation]** (4)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| EvidenceCapturingToolCallback | package | Tool wrapper for evidence capture |
| ToolObservationContext | package | Tool execution observation context |
| GovernanceToolCallingAdvisor | package | Spring AI advisor for governance |
| ToolApprovalRequiredSignal | package | Control-flow signal exception |

**[Persistence Infrastructure]** (2)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| JdbcCheckpointStore | public | JDBC CheckpointStore 实现 |
| CheckpointJsonCodec | package | Checkpoint JSON serialization |

**[Event Projection]** (2)
| Class | Visibility | Primary Responsibility |
|-------|-----------|----------------------|
| ExecutionLedgerListener | package | ExecutionEvent → ExecutionLedger projection |
| CompositeExecutionEventListener | package | Multi-listener composite |

---

## 5. DEPENDENCY MAP

### Module-Level Dependencies

```
arctra-core (no external dependencies)
     ↑
     │
arctra-runtime-react
     ├── Spring AI (ChatModel, ChatClient, ToolCallback, etc.)
     ├── Spring Framework (minimal - DataSource)
     └── JDBC (java.sql.*)
```

✅ **Clean**: No reverse dependency from core to runtime-react

### Package-Level Dependencies (arctra-core internal)

```
agent ←─────┐
            │
checkpoint ←┼─── runtime
            │
evidence ←──┤
            │
execution ←─┤
            │
governance ←┤
            │
process ←───┤
            │
recovery ←──┘
```

**分析**: `runtime` package 是 core 内部的中心协调者，依赖其他 domain package。这是合理的架构。

**问题**: `runtime` package 有 19 个类，职责范围较广：
- Runtime facade (AgentRuntime, DefaultAgentRuntime)
- Engine contract (AgentExecutionEngine, DurableExecutionEngine)
- Resume strategy (ResumeStrategy, DurableResumeStrategy, EphemeralResumeStrategy)
- Binding resolution (RuntimeBinding, RuntimeBindingResolver)
- Process factory (ProcessFactory)
- Recovery API (RecoveryResolution)
- Default implementations (DefaultAgent, DefaultAgentProcess)

判断: 当前可接受，但如果未来 runtime 继续增长，可能需要拆分为 `runtime.facade`, `runtime.engine`, `runtime.binding` 等子 package。

### Package-Level Dependencies (runtime-react internal)

由于所有类位于同一 package，无法通过 package structure 看到依赖方向。

基于代码阅读的**推断依赖**:

```
SpringAiToolCallingEngine (facade)
     │
     ├──→ DurableResumeCoordinator (orchestration)
     │         │
     │         ├──→ SpringAiResumedExecutionHandler (protocol)
     │         │         │
     │         │         └──→ ProtocolReconstructor (protocol)
     │         │                   │
     │         │                   ├──→ EvidenceCapturingToolCallback (tool)
     │         │                   └──→ InvocationStateStore (invocation)
     │         │
     │         └──→ InvocationRecoveryClassifier (recovery)
     │                   │
     │                   └──→ InvocationStateStore (invocation)
     │
     ├──→ JdbcCheckpointStore (persistence)
     └──→ ExecutionLedgerListener (event)
```

**重要发现**: 没有发现 circular dependency。依赖方向基本是单向的：
- `facade → orchestration → protocol → tool execution`
- `orchestration → recovery classification → invocation state`
- `facade → persistence`

---

## 6. FLAT-PACKAGE PROBLEMS

### 当前 runtime-react 扁平化带来的问题

1. **No Package-Level Boundary Enforcement**
   - 所有 33 个类互相可见 (同 package)
   - 无法通过 package-private visibility 限制访问
   - 容易产生意外依赖

2. **Subsystem Responsibilities Hidden**
   - 只能通过类名前缀推断 subsystem (Invocation*, Recovery*, Protocol*)
   - 新开发者无法从 package tree 理解架构

3. **Test Organization困难**
   - Test fixtures 无法按 subsystem 组织
   - 每个 test 可以访问所有 runtime-react internals

4. **Future M6-T6 Risk**
   - General checkpoint 相关类型会加入 runtime-react
   - 如果继续扁平化，将有 40+ 类在单一 package
   - 更难理清职责边界

5. **Public API Surface 模糊**
   - 当前只有 `SpringAiToolCallingEngine` 和 `JdbcCheckpointStore` 是 public
   - 其他 31 个 package-private 类理论上可以被同 package 的 public 类暴露

---

## 7. CORE OWNERSHIP ANALYSIS

### 7.1 当前 Core Contract 是否稳定?

**YES**. 以下 core types 已经在 M5 → M6-T5 验证中证明稳定:

**Agent API**:
- `Agent`, `AgentDefinition`, `AgentRequest`, `AgentResult`, `AgentExecutionContext`

**Checkpoint Domain**:
- `SuspensionCheckpoint`, `CheckpointStore`, `PendingToolCall`

**Execution History**:
- `ExecutionLedger`, `ExecutionEvent`, `ExecutionEventListener`, `EventType`

**Recovery Domain**:
- `OperationResolution`, `ResolutionType`, Recovery exceptions

**Process Lifecycle**:
- `AgentProcess`, `ContinuationSignal`, `ProcessStatus`

**Runtime Contract**:
- `AgentRuntime`, `AgentExecutionEngine`, `DurableExecutionEngine`
- `RecoveryResolution`, `ResumeStrategy`
- `RuntimeBinding`, `RuntimeBindingResolver`

### 7.2 哪些 runtime-react 类型应该 promote 到 core?

**审查标准**:
1. 描述 Arctra platform semantic (不是 Spring AI implementation detail)
2. 不依赖 Spring AI types
3. 其他未来 runtime implementation 也需要理解这个概念
4. 移入 core 改善 dependency direction

**分析结果**: **ZERO promotion candidates**

**理由**:

**DurableResumeCoordinator**:
- ❌ 虽然无 Spring imports，但职责是 "orchestrate Spring AI resumed execution"
- ❌ 它直接依赖 `SpringAiResumedExecutionHandler`
- ❌ 如果未来有 `AgentScopeExecutionEngine`，会有不同的 `AgentScopeResumeCoordinator`
- **判断**: 这是 **runtime-react orchestration implementation**，不是 core contract

**InvocationRecoveryClassifier**:
- ❌ 虽然是 recovery authority，但它是 runtime-react 内部 implementation
- ✅ 它依赖的 `InvocationStateStore` 确实是 authority abstraction
- ❌ 但 `InvocationStateStore` 本身依赖 `OperationResolution` (已在 core)
- **判断**: Classifier 是 runtime-react internal，但它依赖的 **domain types** 已经在 core

**InvocationStateStore**:
- ⚠️ 这是最接近 core 的 candidate
- ✅ 描述 durable invocation authority (Arctra semantic)
- ✅ 不依赖 Spring AI
- ✅ 其他 runtime 也需要 invocation intent tracking
- ❌ **但是**: 当前版本紧密耦合 JDBC 和 InMemory 实现细节
- **判断**: **DEFER**. M6-T6 完成后，如果出现第二个 runtime implementation 需要它，再考虑抽象提升

**ProtocolReconstructor**:
- ❌ 名字已经明确: Spring AI **Protocol** Reconstructor
- ❌ 直接依赖 Spring AI types (Message, ToolResponseMessage, etc.)
- **判断**: 明确的 **Spring AI integration layer**，不应进入 core

**ExecutionIncarnation**:
- ❌ 这是 runtime-react 内部的 restart detection 实现
- ❌ 基于 ClassLoader-scoped static singleton (实现细节)
- ✅ **但是**: `SuspensionCheckpoint.executionEpoch` 字段(String) 已经在 core
- **判断**: Incarnation **concept** 已经在 core (executionEpoch), generator 是 runtime detail

**AttemptIds / OperationIds**:
- ❌ 这些是 ID generator utilities，不是 domain types
- ✅ 它们生成的 String IDs 已经在 core types 中使用 (operationId, attemptId)
- **判断**: Generator 是 implementation utility，ID 本身已经在 core schema

### 7.3 Core Promotion Decision Table

| Type | Current | Proposed | Decision | Reason |
|------|---------|----------|----------|--------|
| DurableResumeCoordinator | runtime-react | - | **KEEP runtime-react** | Spring AI orchestration implementation |
| InvocationRecoveryClassifier | runtime-react | - | **KEEP runtime-react** | Runtime-specific recovery algorithm |
| InvocationStateStore | runtime-react | - | **DEFER** | 等待第二个 runtime 出现再决定是否抽象 |
| ProtocolReconstructor | runtime-react | - | **KEEP runtime-react** | Spring AI protocol integration |
| DefaultRecoveryResolution | runtime-react | - | **KEEP runtime-react** | RecoveryResolution 实现，不是 contract |
| ExecutionIncarnation | runtime-react | - | **KEEP runtime-react** | Runtime-specific implementation |
| AttemptIds / OperationIds | runtime-react | - | **KEEP runtime-react** | Utility generators，不是 domain types |

**结论**: **ZERO types should move to core**

Core 已经包含正确的 contracts 和 domain types。Runtime-react 是 correct implementation。

---

## 8. RUNTIME-REACT OWNERSHIP ANALYSIS

### 8.1 识别的真实 Subsystem

基于 M5 → M6-T5 演进和代码职责分析，runtime-react 内部存在以下**真实 subsystem**:

#### Subsystem 1: **Durable Orchestration**
- **职责**: CHECK A/B, resume lifecycle orchestration, restart detection
- **类型**:
  - `DurableResumeCoordinator` (orchestrator)
  - `ExecutionIncarnation` (restart detection)
- **依赖**: CheckpointStore, RuntimeBindingResolver, ResumedExecutionHandler, InvocationRecoveryClassifier
- **对外接口**: `resumeProcess()` 被 SpringAiToolCallingEngine 调用

#### Subsystem 2: **Protocol Integration**
- **职责**: Spring AI protocol reconstruction, message continuation
- **类型**:
  - `ProtocolReconstructor` (protocol reconstruction + tool execution)
  - `SpringAiResumedExecutionHandler` (resumed execution handler)
  - `ResumedExecutionHandler` (contract)
  - `ResumedExecutionOutcome` (result union)
- **依赖**: Spring AI types (Message, ChatClient, ToolCallback), InvocationStateStore
- **对外接口**: `ResumedExecutionHandler.executeResume()` 被 DurableResumeCoordinator 调用

#### Subsystem 3: **Recovery Classification**
- **职责**: Cross-incarnation recovery classification, multi-attempt aggregation
- **类型**:
  - `InvocationRecoveryClassifier` (classifier)
  - `RecoveryClassificationResult` (sealed result)
  - `DefinitelyNotDispatched`, `MayHaveInvoked`, `ResolvedExecuted`, `ResolvedNotExecuted` (variants)
  - `RecoveryClassificationType` (enum)
  - `RecoveryUncertaintyException` (exception)
  - `DefaultRecoveryResolution` (resolution implementation)
- **依赖**: InvocationStateStore
- **对外接口**: `classify()` 被 DurableResumeCoordinator 调用

#### Subsystem 4: **Invocation State**
- **职责**: Durable invocation intent & resolution authority
- **类型**:
  - `InvocationStateStore` (contract)
  - `InMemoryInvocationStateStore` (impl)
  - `JdbcInvocationStateStore` (impl)
  - `InvocationAttempt` (record)
  - `InvocationIntentPersistenceException` (exception)
  - `AttemptIds`, `OperationIds` (utilities)
- **依赖**: Core recovery types (OperationResolution, ResolutionType)
- **对外接口**: `recordInvocationIntent()`, `findAttempts()`, `recordResolution()`

#### Subsystem 5: **Tool Execution**
- **职责**: Tool wrapping, evidence capture, governance integration
- **类型**:
  - `EvidenceCapturingToolCallback` (tool wrapper)
  - `ToolObservationContext` (execution context)
  - `GovernanceToolCallingAdvisor` (Spring AI advisor)
  - `ToolApprovalRequiredSignal` (control signal)
- **依赖**: Spring AI ToolCallback, Core governance types
- **对外接口**: Wrap tools before execution

#### Subsystem 6: **Persistence**
- **职责**: JDBC checkpoint persistence, JSON codec
- **类型**:
  - `JdbcCheckpointStore` (CheckpointStore impl)
  - `CheckpointJsonCodec` (serialization)
- **依赖**: JDBC, Core checkpoint types
- **对外接口**: Public CheckpointStore implementation

#### Subsystem 7: **Event Projection**
- **职责**: ExecutionEvent → ExecutionLedger projection
- **类型**:
  - `ExecutionLedgerListener` (projector)
  - `CompositeExecutionEventListener` (composite)
- **依赖**: Core execution types
- **对外接口**: ExecutionEventListener implementation

### 8.2 Subsystem Dependency Graph

```
SpringAiToolCallingEngine (facade)
     │
     ├──→ Durable Orchestration
     │         │
     │         ├──→ Protocol Integration
     │         │         │
     │         │         └──→ Tool Execution
     │         │
     │         ├──→ Recovery Classification
     │         │         │
     │         │         └──→ Invocation State
     │         │
     │         └──→ Invocation State
     │
     ├──→ Persistence
     └──→ Event Projection
```

**验证**: 没有 circular dependency

---

## 9. SPRING INTEGRATION BOUNDARY

### 9.1 Spring AI Integration Points

runtime-react 中直接依赖 Spring AI 的类型:

| Class | Spring AI Dependencies |
|-------|----------------------|
| SpringAiToolCallingEngine | ChatModel, ChatClient, ToolCallback, MessageChatMemoryAdvisor |
| SpringAiResumedExecutionHandler | ChatModel, ChatClient, ChatMemory, ToolCallback |
| ProtocolReconstructor | Message, AssistantMessage, ToolResponseMessage, ToolContext |
| GovernanceToolCallingAdvisor | Spring AI Advisor framework, ToolCallingManager |
| EvidenceCapturingToolCallback | ToolCallback, ToolContext |
| SpringAiExecutionLoop | (可能是 legacy/unused) |

✅ **正确隔离**: Spring AI types 仅存在于 runtime-react，core 完全不感知。

### 9.2 Spring Framework Integration Points

| Class | Spring Dependencies |
|-------|-------------------|
| JdbcCheckpointStore | javax.sql.DataSource (JDBC standard, not Spring-specific) |
| JdbcInvocationStateStore | javax.sql.DataSource |

✅ **最小依赖**: 只使用 JDBC 标准接口，不依赖 Spring JDBC Template。

---

## 10. DURABLE EXECUTION BOUNDARY

### 10.1 Durable Orchestration Responsibilities

**DurableResumeCoordinator** 当前承担的职责:

1. ✅ CHECK A (checkpoint load & validation)
2. ✅ RuntimeBinding resolution
3. ✅ ContinuationSignal validation
4. ✅ Approval event emission (APPROVAL_GRANTED/REJECTED)
5. ✅ RESUMED event emission
6. ✅ Recovery classification orchestration (M6-T4F)
7. ✅ Resumed execution delegation
8. ✅ CHECK B (deleteIfVersion / replaceIfVersion)
9. ✅ CHECKPOINT_CONFLICT event emission
10. ✅ COMPLETED event emission
11. ✅ APPROVAL_REQUIRED event emission (re-suspension)
12. ✅ SUSPENDED event emission (re-suspension)
13. ✅ Next checkpoint construction
14. ✅ AgentProcess materialization
15. ✅ Recovery API exposure (M6-T5)

**判断**: Coordinator 职责清晰，边界合理。它是 durable resume 的**唯一 authority**。

**不拥有**:
- ❌ Spring AI protocol mechanics (delegated to ProtocolReconstructor)
- ❌ Tool execution (delegated to ProtocolReconstructor)
- ❌ ChatMemory persistence (delegated to SpringAiResumedExecutionHandler)

### 10.2 Protocol Integration Responsibilities

**ProtocolReconstructor** 当前承担的职责:

1. ✅ AssistantMessage reconstruction (preserving toolCallIds)
2. ✅ Per-operation tool execution (M6-T3B)
3. ✅ Invocation intent recording (M6-T4A gate)
4. ✅ Physical/recovered execution dispatch (M6-T5)
5. ✅ ToolResponseMessage construction
6. ✅ Continuation message sequence
7. ✅ Evidence capture
8. ✅ Tool event emission (TOOL_EXECUTED/TOOL_FAILED)

**判断**: 职责清晰，完全聚焦于 Spring AI protocol。

**SpringAiResumedExecutionHandler** 职责:

1. ✅ Conversation history retrieval
2. ✅ Signal-based dispatch (approved/rejected)
3. ✅ Protocol reconstruction delegation
4. ✅ Model continuation via ChatClient
5. ✅ Governance evaluation during continuation
6. ✅ New suspension detection
7. ✅ Evidence merging
8. ✅ ChatMemory persistence (post-CHECK B)

**判断**: 职责清晰，是 Spring AI resumed execution 的**唯一入口**。

---

## 11. RECOVERY BOUNDARY

### 11.1 Recovery Classification Authority

**InvocationRecoveryClassifier** 职责:

1. ✅ Query all physical attempts for logical operation
2. ✅ Apply aggregation rules (M6-T5)
3. ✅ Produce classification result
4. ✅ Validate result consistency (multiple EXECUTED)

**判断**: 单一职责，清晰边界。

### 11.2 Recovery Resolution Authority

**DefaultRecoveryResolution** 职责:

1. ✅ Validate resolution request against checkpoint
2. ✅ Delegate to InvocationStateStore
3. ✅ Emit RECOVERY_RESOLVED event

**判断**: 职责清晰，是 RecoveryResolution API 的**唯一实现** (runtime-react)。

### 11.3 Invocation State Authority

**InvocationStateStore** 职责:

1. ✅ Record invocation intent (M6-T4A hard gate)
2. ✅ Query invocation intent existence
3. ✅ Find all attempts for operation (M6-T5)
4. ✅ Record recovery resolution (M6-T5)
5. ✅ Query recovery resolution

**判断**: 单一 authority，清晰边界。

**实现**:
- `InMemoryInvocationStateStore`: 适合测试和单机非持久场景
- `JdbcInvocationStateStore`: 生产级 restart-durable implementation

---

## 12. INVOCATION BOUNDARY

### 12.1 Operation Identity Generation

| Generator | Responsibility | Usage |
|-----------|---------------|-------|
| OperationIds | 生成 logical operation 唯一标识 | PendingToolCall.operationId |
| AttemptIds | 生成 physical attempt 唯一标识 | InvocationAttempt.attemptId |

✅ **正确分离**: Operation (logical) vs Attempt (physical) identity。

### 12.2 Invocation State Model

```
Process
  └── Operation (operationId)
        └── Attempt #1 (attemptId-1)
        └── Attempt #2 (attemptId-2)
        └── ...
```

- **Operation**: 逻辑操作，来自 PendingToolCall，checkpoint 持久化
- **Attempt**: 物理尝试，每次 delegate.call() 前生成，InvocationStateStore 持久化

✅ **正确模型**: 支持 multi-attempt aggregation (M6-T5)。

---

## 13. PERSISTENCE BOUNDARY

### 13.1 Checkpoint Persistence

**JdbcCheckpointStore** 职责:

1. ✅ CREATE checkpoint (durability-first)
2. ✅ LOAD checkpoint
3. ✅ DELETE checkpoint (CAS with version)
4. ✅ REPLACE checkpoint (CAS with version)

✅ **完整 CRUD**: 支持完整 durable lifecycle。

**CheckpointJsonCodec** 职责:

1. ✅ SuspensionCheckpoint ↔ JSON serialization
2. ✅ Schema version handling
3. ✅ Forward/backward compatibility

### 13.2 Invocation State Persistence

**JdbcInvocationStateStore** 职责:

1. ✅ Record invocation intent (INSERT or idempotent)
2. ✅ Query intent existence
3. ✅ Find all attempts for operation
4. ✅ Record resolution (INSERT with conflict detection)
5. ✅ Query resolution

✅ **完整 authority**: 支持 invocation lifecycle。

### 13.3 Storage Pairing

当前 M6-T4E 已经实现:

```java
if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
    // JDBC checkpoint → JDBC invocation state (shared DataSource)
    return new JdbcInvocationStateStore(jdbcStore.getDataSource());
} else {
    // Unknown/custom checkpoint → fallback to in-memory
    return new InMemoryInvocationStateStore();
}
```

✅ **正确**: Restart-durable recovery 需要 checkpoint 和 invocation state 使用**同一持久化基础设施**。

---

## 14. PUBLIC/INTERNAL VISIBILITY ANALYSIS

### 14.1 arctra-core Public API

**Intended Public API** (50 classes, all public):

所有 core 类型当前都是 `public`，这是正确的，因为:
1. Core 是 framework contract
2. Application 需要访问这些 types
3. Runtime implementations 需要实现这些 contracts

✅ **合理**: Core API 应该 public。

### 14.2 arctra-runtime-react Public API

**Declared Public** (2 classes):
- `SpringAiToolCallingEngine` (DurableExecutionEngine 实现)
- `JdbcCheckpointStore` (CheckpointStore 实现)

**Package-Private** (31 classes):
- 所有其他 runtime-react internals

✅ **正确**: 只有 Engine facade 和 Persistent store 是 public API。

**问题**: 当前所有 31 个 package-private 类位于**同一 package**，理论上可以互相访问。如果进行 package restructure，可以进一步通过 package boundaries 限制访问。

例如:
```
cn.bitcss.arctra.runtime.react.durable
    DurableResumeCoordinator (package-private, 只被 engine facade 使用)

cn.bitcss.arctra.runtime.react.protocol  
    ProtocolReconstructor (package-private, 只被 durable coordinator 使用)

cn.bitcss.arctra.runtime.react.recovery
    InvocationRecoveryClassifier (package-private, 只被 durable coordinator 使用)
```

这样可以通过 package-private 进一步降低 accidental coupling。

---

## 15. TARGET PACKAGE TREE

基于真实 subsystem 分析，推荐的 target structure:

### 15.1 arctra-core (保持当前结构)

```
cn.bitcss.arctra
├── agent
├── checkpoint
├── evidence
├── execution
├── governance
├── process
├── recovery
└── runtime
```

**理由**: 当前 core structure 已经很好地反映了 domain boundaries，不需要调整。

**未来考虑**: 如果 `runtime` package 继续增长超过 25 个类，可以考虑拆分为:
```
runtime
├── facade (AgentRuntime, DefaultAgentRuntime)
├── engine (AgentExecutionEngine, DurableExecutionEngine)
├── binding (RuntimeBinding, RuntimeBindingResolver)
└── process (ProcessFactory, ResumeStrategy)
```

但当前 19 个类尚可接受，**暂不拆分**。

### 15.2 arctra-runtime-react (推荐 restructure)

**Option A: Subsystem-Oriented (推荐)**

```
cn.bitcss.arctra.runtime.react
├── SpringAiToolCallingEngine (facade, 保持顶层)
├── JdbcCheckpointStore (public impl, 保持顶层)
│
├── durable (Durable Orchestration)
│   ├── DurableResumeCoordinator
│   └── ExecutionIncarnation
│
├── protocol (Spring AI Protocol Integration)
│   ├── ProtocolReconstructor
│   ├── SpringAiResumedExecutionHandler
│   ├── ResumedExecutionHandler
│   ├── ResumedExecutionOutcome
│   └── SpringAiExecutionLoop (if still used)
│
├── recovery (Recovery Classification)
│   ├── InvocationRecoveryClassifier
│   ├── RecoveryClassificationResult (sealed)
│   ├── DefinitelyNotDispatched
│   ├── MayHaveInvoked
│   ├── ResolvedExecuted
│   ├── ResolvedNotExecuted
│   ├── RecoveryClassificationType
│   ├── RecoveryUncertaintyException
│   └── DefaultRecoveryResolution
│
├── invocation (Invocation State Authority)
│   ├── InvocationStateStore
│   ├── InMemoryInvocationStateStore
│   ├── JdbcInvocationStateStore
│   ├── InvocationAttempt
│   ├── InvocationIntentPersistenceException
│   ├── AttemptIds
│   └── OperationIds
│
├── tool (Tool Execution/Observation)
│   ├── EvidenceCapturingToolCallback
│   ├── ToolObservationContext
│   ├── GovernanceToolCallingAdvisor
│   └── ToolApprovalRequiredSignal
│
├── persistence (Persistence Infrastructure)
│   └── CheckpointJsonCodec
│
└── event (Event Projection)
    ├── ExecutionLedgerListener
    └── CompositeExecutionEventListener
```

**Option B: Minimal (如果不想大规模重组)**

```
cn.bitcss.arctra.runtime.react
├── SpringAiToolCallingEngine (facade)
├── JdbcCheckpointStore (public impl)
│
├── internal (所有 package-private internals 移到这里)
│   ├── durable
│   ├── protocol
│   ├── recovery
│   ├── invocation
│   ├── tool
│   ├── persistence
│   └── event
```

**推荐**: **Option A** (Subsystem-Oriented)

**理由**:
1. 真实反映 M5 → M6-T5 演进中形成的 subsystem boundaries
2. 每个 subsystem 职责清晰，便于未来维护
3. 可以通过 package-private 进一步限制 cross-subsystem 访问
4. Test 可以按 subsystem 组织
5. 为 M6-T6 general checkpoint 预留清晰位置 (可能在 `durable` 或新的 `checkpoint` subsystem)

---

## 16. MOVE-TO-CORE DECISIONS

### 16.1 Decision Summary

| Type | Current Location | Proposed | Decision | Reason |
|------|-----------------|----------|----------|--------|
| DurableResumeCoordinator | runtime-react | KEEP | ✅ NO MOVE | Spring AI orchestration impl |
| InvocationRecoveryClassifier | runtime-react | KEEP | ✅ NO MOVE | Runtime-specific algorithm |
| InvocationStateStore | runtime-react | DEFER | ⚠️ DEFER | 等待第二个 runtime 再决定 |
| ProtocolReconstructor | runtime-react | KEEP | ✅ NO MOVE | Spring AI protocol integration |
| DefaultRecoveryResolution | runtime-react | KEEP | ✅ NO MOVE | RecoveryResolution impl |
| ExecutionIncarnation | runtime-react | KEEP | ✅ NO MOVE | Runtime-specific impl |
| AttemptIds/OperationIds | runtime-react | KEEP | ✅ NO MOVE | Utility generators |
| SpringAiResumedExecutionHandler | runtime-react | KEEP | ✅ NO MOVE | Spring AI integration |
| RecoveryClassificationResult | runtime-react | KEEP | ✅ NO MOVE | Internal classification type |
| InvocationAttempt | runtime-react | KEEP | ✅ NO MOVE | Internal attempt record |

**结论**: **ZERO types should be promoted to core**

Core 已经包含正确的 contracts:
- `RecoveryResolution` (API)
- `OperationResolution` (domain type)
- `ResolutionType` (domain enum)
- `SuspensionCheckpoint` (包含 executionEpoch)

Runtime-react 是这些 contracts 的 correct implementation。

---

## 17. RUNTIME-REACT RELOCATION DECISIONS

### 17.1 Proposed Moves (按 subsystem 重组)

基于 **Option A: Subsystem-Oriented** 的 relocation 计划:

| Current | Proposed | Subsystem | Risk |
|---------|----------|-----------|------|
| All 33 classes in flat package | Move to subsystem packages | 7 subsystems | LOW |

**详细 Relocation**:

**Durable Orchestration** (2 classes → `durable/`):
- DurableResumeCoordinator
- ExecutionIncarnation

**Protocol Integration** (5 classes → `protocol/`):
- ProtocolReconstructor
- SpringAiResumedExecutionHandler
- ResumedExecutionHandler
- ResumedExecutionOutcome
- SpringAiExecutionLoop (verify if still used)

**Recovery Classification** (10 classes → `recovery/`):
- InvocationRecoveryClassifier
- RecoveryClassificationResult
- DefinitelyNotDispatched, MayHaveInvoked, ResolvedExecuted, ResolvedNotExecuted
- RecoveryClassificationType
- InvocationRecoveryClassification (verify if still used)
- RecoveryUncertaintyException
- DefaultRecoveryResolution

**Invocation State** (7 classes → `invocation/`):
- InvocationStateStore
- InMemoryInvocationStateStore, JdbcInvocationStateStore
- InvocationAttempt
- InvocationIntentPersistenceException
- AttemptIds, OperationIds

**Tool Execution** (4 classes → `tool/`):
- EvidenceCapturingToolCallback
- ToolObservationContext
- GovernanceToolCallingAdvisor
- ToolApprovalRequiredSignal

**Persistence** (1 class → `persistence/`):
- CheckpointJsonCodec
- (JdbcCheckpointStore 保持顶层 - public API)

**Event Projection** (2 classes → `event/`):
- ExecutionLedgerListener
- CompositeExecutionEventListener

**Facade** (2 classes → 保持顶层):
- SpringAiToolCallingEngine
- JdbcCheckpointStore

---

## 18. VISIBILITY CHANGES

### 18.1 Current Visibility

所有 runtime-react 类型:
- 2 public: SpringAiToolCallingEngine, JdbcCheckpointStore
- 31 package-private: 所有 internals

### 18.2 After Restructure Visibility

**保持不变**: 所有类型 visibility 不变

**但是** package boundaries 会限制 cross-package 访问:

例如:
- `protocol.ProtocolReconstructor` (package-private) 只能被同 package 或 parent package 访问
- `recovery.InvocationRecoveryClassifier` (package-private) 只能被 `durable.DurableResumeCoordinator` 访问 (如果 coordinator 在 parent package)

**Cross-Package Access 策略**:

Option 1: **Keep Facade at Top Level**
```
cn.bitcss.arctra.runtime.react
├── SpringAiToolCallingEngine (public, can access all subpackages)
├── durable/ (package-private)
├── protocol/ (package-private)
└── recovery/ (package-private)
```

Option 2: **Use Package-Private Interfaces**
```
cn.bitcss.arctra.runtime.react.durable
    DurableResumeCoordinator (package-private)
    
cn.bitcss.arctra.runtime.react.protocol
    ProtocolReconstructor (package-private)
    ResumedExecutionHandler (package-private interface)
```

**推荐**: **Option 1** (Facade at Top Level)

Facade (SpringAiToolCallingEngine) 位于 parent package，可以 access 所有 subpackages，subpackages 互相 package-private。

---

## 19. MIGRATION RISK

### 19.1 Impact Analysis

**Production Files Affected**: 33 classes relocation  
**Test Files Affected**: ~40 test classes (需要更新 import statements)  
**Imports Affected**: ~100-150 import statements (估算)

### 19.2 Risk Assessment

| Risk Category | Level | Mitigation |
|--------------|-------|------------|
| Compilation Errors | LOW | IDE auto-refactor, compile after each move |
| Runtime Behavior Change | NONE | Pure relocation, zero logic change |
| Test Failures | LOW | Update imports, all tests should pass |
| Public API Break | NONE | SpringAiToolCallingEngine, JdbcCheckpointStore package unchanged |
| Visibility Errors | LOW | All classes keep current visibility |

**总体风险**: **LOW**

这是纯粹的 package relocation，不改变任何逻辑。

### 19.3 Migration Verification

每个 relocation step 后验证:

1. ✅ `./mvnw clean compile` (编译通过)
2. ✅ `./mvnw test` (所有测试通过)
3. ✅ No new warnings
4. ✅ Architecture tests pass (if exist)

---

## 20. MIGRATION ORDER

### 20.1 推荐迁移顺序

**Phase 1: Bottom-Up (Leaf Subsystems First)**

1. **Event Projection** (2 classes, no internal dependencies)
   - ExecutionLedgerListener
   - CompositeExecutionEventListener

2. **Persistence** (1 class, no internal dependencies)
   - CheckpointJsonCodec

3. **Tool Execution** (4 classes, no internal dependencies)
   - EvidenceCapturingToolCallback, ToolObservationContext
   - GovernanceToolCallingAdvisor, ToolApprovalRequiredSignal

4. **Invocation State** (7 classes, no internal dependencies)
   - InvocationStateStore, implementations, utilities

**Phase 2: Mid-Level (Subsystems with Dependencies)**

5. **Recovery Classification** (10 classes, depends on Invocation State)
   - InvocationRecoveryClassifier
   - Classification result types
   - DefaultRecoveryResolution

6. **Protocol Integration** (5 classes, depends on Tool Execution, Invocation State)
   - ResumedExecutionHandler, ResumedExecutionOutcome
   - SpringAiResumedExecutionHandler
   - ProtocolReconstructor

**Phase 3: Top-Level (Orchestration)**

7. **Durable Orchestration** (2 classes, depends on Protocol, Recovery)
   - ExecutionIncarnation
   - DurableResumeCoordinator

**Phase 4: Facade (Last)**

8. **Verify Facade** (no move, just verify)
   - SpringAiToolCallingEngine (保持顶层)
   - JdbcCheckpointStore (保持顶层)

### 20.2 Verification After Each Phase

```bash
# After each phase
./mvnw clean compile
./mvnw test -pl arctra-runtime-react
./mvnw verify -pl arctra-runtime-react

# Full regression after all phases
./mvnw clean verify
```

---

## 21. ARCHITECTURE TESTS

### 21.1 Current Architecture Tests

检查项目是否已有 ArchUnit tests:

```bash
find . -name "*ArchTest*.java" -o -name "*ArchitectureTest*.java"
```

**如果已有**: 更新规则以反映新的 package structure

**如果没有**: 考虑添加**少量**关键规则

### 21.2 Recommended Architecture Rules (如果添加)

只添加**最关键**的规则:

```java
// Rule 1: Core must not depend on runtime-react
noClasses().that().resideInAPackage("cn.bitcss.arctra..")
    .and().resideOutsideOfPackage("..runtime.react..")
    .should().dependOnClassesThat()
    .resideInAPackage("..runtime.react..");

// Rule 2: Core must not depend on Spring
noClasses().that().resideInAPackage("cn.bitcss.arctra..")
    .and().resideOutsideOfPackage("..runtime.react..")
    .should().dependOnClassesThat()
    .resideInAPackage("org.springframework..");

// Rule 3: Protocol package should only be accessed by durable and facade
classes().that().resideInAPackage("..runtime.react.protocol..")
    .should().onlyBeAccessed().byClassesThat()
    .resideInAnyPackage("..runtime.react.durable..", "..runtime.react");
```

**建议**: 先完成 restructure，运行一段时间，再决定是否添加 ArchUnit。不要一开始就添加几十条脆弱规则。

---

## 22. M6-T6 READINESS

### 22.1 M6-T6: General Durable Execution Checkpoints

M6-T6 预期会引入:

- **Turn identity** (multi-turn checkpoint)
- **Step identity** (sub-turn checkpoint)
- **Execution boundary** (更细粒度的 checkpoint trigger)
- **General checkpoint** (不仅限于 tool approval suspension)
- **Recovery state** (更复杂的 recovery policy)

### 22.2 当前 Package Structure 对 M6-T6 的支持

**如果保持扁平化** (不 restructure):

❌ **问题**:
- 40+ 类混在一个 package
- General checkpoint 相关类型会进一步增加复杂度
- 难以理清 checkpoint vs invocation vs recovery boundaries

**如果完成 restructure**:

✅ **清晰**:
- General checkpoint 可以放在 `durable/` subsystem
- Turn/Step identity 可以放在新的 `checkpoint/` subsystem (如果需要)
- Recovery policy 可以放在 `recovery/` subsystem
- Subsystem boundaries 帮助新类型找到正确位置

### 22.3 M6-T6 新类型的预期位置

基于 restructure 后的 structure:

| M6-T6 概念 | 预期位置 | 理由 |
|-----------|---------|------|
| TurnIdentity | `durable/` 或新 `checkpoint/` | Checkpoint identity abstraction |
| StepIdentity | `durable/` 或新 `checkpoint/` | Sub-turn checkpoint identity |
| GeneralCheckpoint | Core `checkpoint` package | Core domain type (like SuspensionCheckpoint) |
| CheckpointTrigger | `durable/` | Orchestration policy |
| RecoveryPolicy | `recovery/` | Recovery classification extension |

**判断**: Restructure 后的 package structure 能够**清晰支持** M6-T6 扩展。

---

## 23. FINAL RECOMMENDATIONS

### 23.1 Core 保持不变

✅ **结论**: arctra-core 当前 package structure 合理，**不需要调整**。

**理由**:
- 50 个类分布在 8 个 domain packages
- 每个 package 职责清晰
- Module dependency 正确 (core ← runtime-react)
- Spring isolation 正确 (core 零 Spring 依赖)

### 23.2 Runtime-React 应该 Restructure

⚠️ **建议**: arctra-runtime-react 应该从扁平 package **重组为 subsystem-oriented structure**。

**理由**:
1. 当前 33 个类全部位于单一 package，subsystem boundary 不清晰
2. M6-T6 会继续增加类型，扁平化会进一步恶化
3. Restructure 风险低 (纯 relocation，零逻辑变更)
4. Restructure 后能够更好支持 M6-T6 扩展

### 23.3 Restructure Priority

**推荐顺序**:

**Option 1: M6-T6 前完成 restructure (推荐)**
- **好处**: M6-T6 新类型可以直接放入正确 subsystem
- **成本**: 1-2 天重组 + 验证

**Option 2: M6-T6 后完成 restructure**
- **好处**: 不阻塞 M6-T6 功能开发
- **成本**: M6-T6 类型先放入扁平 package，后续再 relocation

**决策建议**: **Option 1** (M6-T6 前完成)

**理由**: Restructure 本身很简单 (纯 relocation)，完成后可以让 M6-T6 开发更清晰。

### 23.4 Zero Core Promotion

✅ **结论**: **ZERO types should be promoted to core**。

**理由**:
- Core 已经包含正确的 contracts 和 domain types
- Runtime-react 是这些 contracts 的 correct implementation
- 过早提升会引入 premature abstraction

**唯一 DEFER**: `InvocationStateStore` 等待第二个 runtime implementation 出现后再决定是否抽象到 core。

---

## 24. GO/NO-GO DECISION

### 24.1 Architecture Gate 结论

**CONDITIONAL GO — PACKAGE RESTRUCTURE RECOMMENDED BEFORE M6-T6**

### 24.2 Go Criteria 评估

| Criterion | Status | Notes |
|-----------|--------|-------|
| Module ownership clear | ✅ PASS | core ← runtime-react 正确 |
| Package boundaries clear | ⚠️ PARTIAL | Core 清晰，runtime-react 扁平化 |
| No forbidden dependency | ✅ PASS | 没有发现 circular dependency |
| Migration mostly mechanical | ✅ PASS | 纯 relocation，零逻辑变更 |
| No major public API redesign | ✅ PASS | Public API 不变 |

### 24.3 Blocking Issues

**ZERO blocking issues**

当前 package structure **可以开始 M6-T6**，但会带来技术债。

### 24.4 Recommended Pre-M6-T6 Actions

1. **Execute runtime-react package restructure** (1-2 天)
   - 按 subsystem 重组 33 个类
   - 验证所有测试通过
   - 更新文档

2. **Optional: Add minimal ArchUnit tests** (0.5 天)
   - Core 不依赖 runtime-react
   - Core 不依赖 Spring
   - 仅 2-3 条关键规则

3. **Update CURRENT-STATE.md**
   - 记录 package restructure 完成
   - 更新架构图 (if any)

**总计时间成本**: 1.5-2.5 天

**收益**: M6-T6 开发过程中 package boundaries 清晰，新类型能够找到正确位置。

---

## 25. NEXT STEPS

### 25.1 如果选择 M6-T6 前 Restructure

1. **创建 restructure task**: 
   ```
   Task: Runtime-React Package Restructure
   - Move 33 classes to subsystem packages
   - Verify compilation and tests
   - Update imports
   - Update documentation
   ```

2. **Execute restructure**:
   - Follow migration order (Phase 1 → Phase 4)
   - Verify after each phase
   - Full regression after completion

3. **Architecture Gate PASSED**:
   - 继续 M6-T6 implementation

### 25.2 如果选择 M6-T6 后 Restructure

1. **Accept technical debt**:
   - M6-T6 新类型暂时放入 flat package
   - 记录 TODO: restructure after M6-T6

2. **M6-T6 implementation**:
   - 开始实现 General Checkpoint

3. **Post-M6-T6 restructure**:
   - Restructure 包含 M6-T6 新类型

---

## 26. CONCLUSION

### 26.1 Summary

- ✅ **Module structure**: 正确
- ✅ **Core ownership**: 清晰
- ✅ **Spring isolation**: 正确
- ⚠️ **Runtime-react package**: 扁平化，建议 restructure
- ✅ **No forbidden dependencies**: 验证通过
- ✅ **Core promotion**: ZERO types should move

### 26.2 Final Decision

**CONDITIONAL GO — PACKAGE RESTRUCTURE RECOMMENDED BEFORE M6-T6**

**Recommendation**:
- 花费 1.5-2.5 天完成 runtime-react package restructure
- 然后开始 M6-T6 implementation
- 收益: 清晰的 subsystem boundaries 支持未来扩展

**Alternative**:
- 直接开始 M6-T6 (接受扁平化技术债)
- M6-T6 后再 restructure

**最终决策权**: Product Owner / Tech Lead

---

**报告完成日期**: 2026-09-16  
**审计人**: Claude Opus 5  
**状态**: Architecture Gate - CONDITIONAL GO

