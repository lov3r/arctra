# M2 竞品架构深度研究

**研究日期：** 2026-08-18  
**研究范围：** AgentScope, Spring AI Alibaba Graph  
**研究目标：** 理解 Session/State/Memory/Execution 真实职责边界，为 Arctra M2 找到正确的抽象

---

## 第一部分：24 个关键问题回答

### 1. 什么是 Session？

**AgentScope 定义：**
> "a collection of StateModule in an application, e.g. multiple agents"

**核心语义：**
- Session 是**容器级抽象**，管理一个或多个 agent 的状态
- 不是单个 agent 的属性，而是协调多个 stateful entity 的边界
- 作用：将多个 agent/toolkit/memory 作为**一个单元**保存/恢复

**Spring AI Alibaba Graph 定义：**
- Graph 本身不直接暴露 "Session" 概念
- 通过 **OverAllState** + 持久化机制实现类似语义
- Session 更多是 Graph execution instance 的持久化表达

**关键洞察：**
> Session ≠ Conversation  
> Session 是**状态管理边界**，不是业务概念

---

### 2. 什么是 Conversation？

**AgentScope 实践：**
- Conversation 是 Memory 中存储的 **messages 序列**
- 体现为 `memory.content: List[Message]`
- 不是独立抽象，而是 Memory 的一种内容类型

**Spring AI Alibaba：**
- 未单独定义 Conversation 概念
- Graph 通过 OverAllState 携带上下文
- Conversation history 是 state 的一部分

**关键洞察：**
> Conversation 不是 first-class 抽象  
> 它是 Memory/State 中的**历史消息集合**

---

### 3. 什么是 Memory？

**AgentScope 双层设计：**

**Short-term Memory（对话历史）：**
- "Stores conversation history for the current session"
- 需要 Session 才能跨调用持久化
- 内容：当前 session 的完整消息序列
- 问题：无界增长，需要 compaction

**Long-term Memory（跨 session 知识）：**
- "Stores user preferences and knowledge across sessions"
- 由外部组件自动持久化（Mem0, ReMe）
- 内容：从对话中提取的**事实**（facts）
- 问题：如何从对话中提炼事实

**设计理由（WHY）：**
> "Enable the agent to 'remember facts across sessions' while preventing conversation context from growing unboundedly."

**架构模式：**
```
Daily logs (高频低质量)
    ↓ 后台提炼
MEMORY.md (低频高质量)
    ↓ 每次推理注入
System Prompt
```

**Spring AI Alibaba：**
- 未明确区分 short-term vs long-term
- 通过 Graph state 携带上下文
- Memory 管理可能委托给 Spring AI ChatMemory

**关键洞察：**
> Memory 不是单一概念  
> 必须区分：对话历史（bounded） vs 跨 session 知识（accumulated）

---

### 4. 什么是 Agent State？

**AgentScope 明确定义：**
> "the agent status in the running application, including its current system prompt, memory, context, equipped tools, and other information that **change over time**"

**包含内容：**
- System prompt（可能运行时修改）
- Memory content（会话历史）
- Context（当前推理上下文）
- Equipped tools（当前可用工具）
- 任何继承 `StateModule` 的属性

**不包含内容：**
- AgentDefinition（静态配置）
- Model provider（基础设施）
- 不变的业务逻辑

**Spring AI Alibaba Graph State：**
- OverAllState：workflow 级别的共享状态
- 不是 agent instance 的状态
- 是 Graph execution 的状态

**关键洞察：**
> Agent State = 运行时可变信息  
> ≠ AgentDefinition（静态配置）  
> ≠ Execution State（单次执行）

---

### 5. 什么是 Execution State？

**AgentScope：**
- 未明确单独定义 Execution State
- 每次 `agent.call()` 是一次 execution
- Execution 结果（reply）进入 Agent State（memory）
- Execution 本身是瞬态的，不独立持久化

**Spring AI Alibaba Graph：**
- Graph execution 有明确的 **execution state**
- 包括：当前节点、已执行路径、节点输出
- 支持：快照、暂停、恢复、人工干预
- Execution state 可以持久化并恢复

**关键洞察：**
> AgentScope: Execution 是瞬态，结果进入 Agent State  
> Graph: Execution 本身是 stateful，可暂停/恢复

**两种模型：**
```
# AgentScope 模型
Agent State (persistent)
    ↓
Execution (transient)
    ↓
Result → update Agent State

# Graph 模型
Execution State (persistent)
    ↓
Node execution
    ↓
Update Execution State
```

---

### 6. Session 与 Agent instance 是什么关系？

**AgentScope 清晰分离：**

**Agent（蓝图/模板）：**
- 定义 agent 的能力、配置、行为
- 可以被多个 session 复用
- 是**无状态的类定义**

**Session（运行时实例）：**
- 绑定到具体的 `(userId, sessionId)`
- 持有该交互的 agent state
- 一个 agent 可以服务多个 session

**Agent Service 架构：**
```
Agent (reusable template)
    ↓ 1:N
Session (runtime state)
    ↓
Workspace (isolated execution)
    ↓
Messages (persisted transcript)
```

**Spring AI Alibaba：**
- 未明确暴露此分离
- Graph 是 workflow 模板
- Graph execution instance 类似 session

**关键洞察：**
> Agent = stateless template  
> Session = stateful instance  
> 一个 agent 服务多个 session（multi-tenancy 核心）

---

### 7. 同一个 Agent 是否可以服务多个 Session？

**AgentScope 明确支持：**
> "Agent Service turns AgentScope agents into a multi-tenant, multi-session HTTP service"

**实现机制：**
```
Agent Class (单例 or 无状态)
    ↓
AgentService 路由层
    ↓
Session 1 (userId=A, sessionId=X)
Session 2 (userId=A, sessionId=Y)
Session 3 (userId=B, sessionId=Z)
```

**隔离保证：**
- 每个 session 有独立的 state store
- Request 通过 `get_current_user_id()` 解析 tenant
- Storage layer 强制 `user_id` 过滤

**Spring AI Alibaba：**
- Graph 本身是无状态模板
- 可以启动多个 execution instance
- 每个 execution 有独立 state

**关键洞察：**
> 必须支持  
> 这是 production multi-tenancy 的基础

---

### 8. Session ID / conversation ID 在调用链哪个位置进入？

**AgentScope 模型：**

**方式 1（显式传递）：**
```python
await session.save_session_state(
    session_id="user_1",
    agent=agent
)

await session.load_session_state(
    session_id="user_1",
    agent=agent
)
```

**方式 2（HTTP Service）：**
```
HTTP Request
    ↓ Header / Auth
userId + sessionId
    ↓ Dependency Injection
get_current_user_id() / get_current_session_id()
    ↓ Storage Layer
user_id filter
```

**Spring AI Alibaba Graph：**
- 未明确文档说明
- 可能通过 Graph builder 传入
- 或通过 execution context 携带

**关键洞察：**
> Session ID 在调用链**入口**进入  
> 不是在 Agent 内部生成  
> 由 Runtime/Service 层管理

---

### 9. 多轮消息在哪里保存？

**AgentScope 三层存储：**

**Layer 1: Agent Memory（运行时）**
- `agent.memory.content: List[Message]`
- 当前 session 的消息序列

**Layer 2: Session State（持久化）**
- JSONSession: `./user_1.json`
- 包含完整 agent state（含 memory）

**Layer 3: Conversation Log（审计）**
- `sessions/<sessionId>.log.jsonl`
- 完整对话 + 推理 trace
- 永不 compact

**Spring AI Alibaba：**
- OverAllState 中携带消息历史
- 通过 StateSerializer 持久化
- 具体存储机制未明确文档

**关键洞察：**
> 多层存储，各有用途：  
> - Memory: 推理用（会 compact）  
> - State: 恢复用（完整快照）  
> - Log: 审计用（永久保留）

---

### 10. 下一轮调用时，历史消息如何恢复？

**AgentScope 自动恢复：**

**HarnessAgent 默认行为：**
> "State persistence is on by default"  
> "AgentState is auto-saved at the end of every call() and auto-loaded on the next"

**恢复流程：**
```
call(userId="A", sessionId="X")
    ↓
AgentStateStore.load((A, X))
    ↓ if exists
Restore agent.memory from snapshot
    ↓
Execute reasoning loop
    ↓
AgentStateStore.save((A, X))
```

**用户无需显式 save/load：**
- Framework 自动处理
- 用户只需传递 `(userId, sessionId)`

**Spring AI Alibaba Graph：**
- 需要显式配置 persistence
- 通过 Checkpointer 机制
- 可能需要手动触发 save/restore

**关键洞察：**
> 优秀设计：自动 save/load  
> 用户无感知，只需提供 session identity

---

### 11. Tool call / Tool result 是否进入 conversation history？

**AgentScope：**
- **是**，完整进入 Memory
- Tool call 作为 `FunctionCallMessage`
- Tool result 作为 `ToolResponseMessage`
- 成为 conversation context 的一部分

**Spring AI：**
- **是**，Spring AI Message 包括：
  - `UserMessage`
  - `AssistantMessage`
  - `ToolCallMessage`（Spring AI 2.0）
  - `ToolResponseMessage`（Spring AI 2.0）

**关键洞察：**
> Tool call/result 是 conversation 的一部分  
> Model 需要看到完整交互历史  
> 这是 ReAct loop 正确工作的前提

---

### 12. System prompt 是否属于 Session state？

**AgentScope：**
- **是**，明确包含在 Agent State
- 可以运行时修改（workspace-driven persona）
- 每次推理重建 system prompt（从 `AGENTS.md` + `MEMORY.md`）

**设计理由：**
- Persona 可能随 session 演化
- Long-term memory 注入 system prompt
- 支持 per-session 定制化

**Spring AI Alibaba：**
- 未明确说明
- 可能在 AgentDefinition（静态）
- 或在 OverAllState（动态）

**关键洞察：**
> System prompt **可以是** session state  
> 如果支持运行时 persona 调整  
> 但大多数场景是静态的

---

### 13. Agent configuration 是否属于 Session state？

**AgentScope 区分：**

**静态配置（不属于 Session state）：**
- Agent 类定义
- Model provider
- 基础工具集

**动态配置（属于 Session state）：**
- 当前 equipped tools（可能运行时添加/移除）
- Permission settings
- Sandbox configuration
- Plan Mode state

**关键原则：**
> 运行时可变 → State  
> 静态不变 → Configuration

**Spring AI Alibaba：**
- 未明确区分
- OverAllState 可能包含部分配置

**关键洞察：**
> 必须区分 Configuration vs State  
> Configuration: 模板级别  
> State: 实例级别

---

### 14. Session persistence 如何实现？

**AgentScope 可插拔存储：**

**接口：**
```python
class StorageBase:
    async def save(key, data)
    async def load(key)
```

**实现：**
- `JSONSession`: 文件存储（`./user_1.json`）
- `RedisStorage`: Redis KV
- `SQLAlchemyStorage`: 关系数据库

**Spring AI Alibaba Graph：**
- 通过 `StateSerializer`
- 支持 JSON、自定义序列化
- Checkpointer 机制

**关键洞察：**
> 存储后端可插拔  
> 不绑定特定数据库  
> Framework 只定义 contract

---

### 15. 是否区分 short-term / long-term memory / conversation history / execution state？

**AgentScope 明确区分：**

| 概念 | 生命周期 | 容量 | 用途 | Compaction |
|------|---------|------|------|-----------|
| **Short-term Memory** | Session | Bounded | 推理上下文 | Yes |
| **Long-term Memory** | 跨 Session | Unbounded | 知识积累 | No（提炼） |
| **Conversation History** | Session | Unbounded | 完整记录 | No（审计） |
| **Execution State** | Single call | Transient | 运行时临时 | N/A |

**WHY 区分：**
- Short-term: 给模型看，必须 bounded
- Long-term: 跨 session 累积，语义提炼
- History: 审计合规，永久保留
- Execution: 临时变量，不持久化

**Spring AI Alibaba：**
- 未明确区分
- 可能只有 OverAllState（混合）

**关键洞察：**
> 必须区分  
> 混在一起会导致：  
> - Context 无限增长  
> - 无法跨 session 学习  
> - 审计日志被 compact

---

### 16. Context window 超限后如何处理？

**AgentScope 三种策略：**

**1. Compaction（压缩）：**
```python
harnessAgent
    .compaction(
        trigger=TurnCountTrigger(10),
        strategy=SlidingWindowStrategy(20)
    )
```

**2. Tool Result Eviction（工具结果外置）：**
```python
.toolResultEviction(
    sizeThreshold=80_000,  # 80K chars
    strategy=OffloadStrategy.TO_DISK
)
```
- 大结果存到磁盘
- 用 placeholder 替代

**3. Long-term Memory Distillation（提炼）：**
- 后台任务提炼事实到 `MEMORY.md`
- 旧对话可以丢弃
- 事实注入 system prompt

**Spring AI Session API（未调研但已知）：**
- `RecursiveSummarizationCompactionStrategy`
- LLM 递归总结历史

**关键洞察：**
> 三层防御：  
> 1. 压缩对话（保留最近）  
> 2. 外置大数据（占位符）  
> 3. 提炼知识（system prompt）

---

### 17. 支持的压缩策略

**AgentScope：**
- Sliding Window（最近 N 条）
- Turn Window（最近 N 轮）
- Token-based（token 预算）
- 外置策略（offload）

**Spring AI Session API：**
- SlidingWindowCompactionStrategy
- TurnWindowCompactionStrategy
- TokenCountCompactionStrategy
- RecursiveSummarizationCompactionStrategy（LLM 总结）

**关键洞察：**
> 压缩不是单一策略  
> 需要组合：window + offload + summarization

---

### 18. Session 是否可以跨进程恢复？

**AgentScope：**
- **是**，明确支持
> "Enable the agent to restore state across requests, process restarts, and multi-user scenarios"

**实现机制：**
- State 存储在外部（Redis/DB）
- 不依赖进程内存
- 任何进程可以加载 `(userId, sessionId)`

**Agent Service 设计：**
> "All shared state lives in Redis (storage + message bus), so multiple worker processes — or multiple nodes — can serve one logical service"

**Spring AI Alibaba：**
- Graph 支持 checkpoint
- 理论上可以跨进程恢复
- 具体实现未详细文档

**关键洞察：**
> 跨进程恢复是 production 必需  
> 要求：外部存储 + stateless worker

---

### 19. Session storage 是否可替换？

**AgentScope：**
- **完全可替换**
- 通过 `StorageBase` 接口
- 内置：JSON / Redis / SQLAlchemy
- 用户可自定义

**Spring AI Alibaba：**
- 通过 `StateSerializer` 抽象
- 支持不同序列化格式
- 存储后端未明确暴露

**关键洞察：**
> Storage 必须可替换  
> 不能绑定特定数据库

---

### 20. 多租户如何隔离 Session？

**AgentScope 三层隔离：**

**Layer 1: Routing（路由层）：**
```python
@router.post("/sessions/{session_id}/runs")
async def create_run(
    session_id: str,
    user_id: str = Depends(get_current_user_id)
):
    # user_id from auth
```

**Layer 2: Storage（存储层）：**
- 所有查询强制 `user_id` 过滤
- Key: `(userId, sessionId)`
- 一个用户看不到其他用户的 session

**Layer 3: Workspace（执行层）：**
- Per-session 或 per-user workspace
- 文件系统隔离

**Spring AI Alibaba：**
- 未明确文档
- 可能需要应用层自行实现

**关键洞察：**
> Multi-tenancy 必须在 Framework 层解决  
> 不能依赖应用层手动过滤

---

### 21. 并发请求访问同一个 Session 时如何处理？

**AgentScope 明确机制：**

**Session Lock（Redis）：**
> "Session locks — Enforce single-run-per-session"

**并发策略：**
- 同一 session 同时只能有一个 execution
- 后续请求等待或拒绝
- 通过 Redis lock 实现

**WHY 需要 lock：**
- 避免 state 冲突
- 保证消息顺序
- 防止 race condition

**Spring AI Alibaba：**
- 未明确说明
- 可能依赖应用层处理

**关键洞察：**
> Session 级别 lock 是必需的  
> 否则 multi-turn 会乱序

---

### 22. Streaming 与 Session state 如何协作？

**AgentScope：**

**Streaming 模式：**
- Tool calls streaming
- Reasoning trace streaming
- Multimodal content streaming

**与 Session 关系：**
- Streaming 只影响输出格式
- State management 独立进行
- 最终结果仍然进入 memory

**Spring AI Alibaba Graph：**
- 支持节点流式输出
- State 仍然在节点完成后更新

**关键洞察：**
> Streaming 是输出格式  
> State 是完整结果  
> 两者正交

---

### 23. Tool execution state 是否属于 Session？

**AgentScope：**

**Tool execution 本身：**
- 瞬态的，不持久化

**Tool result：**
- 进入 conversation memory
- 作为 Session state 的一部分

**Long-running tools：**
- 后台执行，state 在 task manager
- 结果通过 inbox 送回 session

**Spring AI Alibaba：**
- Tool call 是 workflow node
- Node execution 是 Graph state 的一部分

**关键洞察：**
> Tool execution 本身：瞬态  
> Tool result：进入 Session  
> Long-running tool：独立 state

---

### 24. Agent 多轮状态由谁持有？

**AgentScope 明确设计：**

**外置状态管理：**
- Agent instance 可以是无状态的
- State 由 **AgentStateStore** 持有
- Agent 从 store load，执行，save back

**WHY 外置：**
- Agent 可以是单例 or 短生命周期
- 支持跨进程、跨重启
- 支持 multi-tenancy

**架构模式：**
```
AgentStateStore (external)
    ↓ load
Agent.call() (stateless)
    ↓ save
AgentStateStore
```

**Spring AI Alibaba Graph：**
- State 外置在 OverAllState
- Graph 本身是无状态的
- Execution instance 持有 state

**关键洞察：**
> **状态外置是核心设计**  
> Agent 不应该持有 session state  
> 由 Runtime/Store 管理

---

## 第二部分：概念对比表

| 概念 | Arctra M1 | Spring AI Alibaba | AgentScope | 语义一致？ | 对 Arctra 有用？ |
|------|-----------|-------------------|------------|----------|---------------|
| **Agent** | AgentDefinition（静态） | Graph（workflow 模板） | Agent（无状态类） | ✅ 都是模板 | **保持** |
| **AgentDefinition** | name + description | ❌ 无对应 | Agent 参数 | ⚠️ 太简单 | **扩展但不混入 state** |
| **Request** | AgentRequest（单轮） | Input data | User message | ✅ 单次输入 | **保持** |
| **Execution** | 瞬态（M1 无显式抽象） | Graph execution | call() 调用 | ⚠️ 不同模型 | **M2 需要明确** |
| **Session** | ❌ 无 | ❌ 无明确概念 | StateModule 集合 | ⚠️ AgentScope 独有 | **M2 核心** |
| **Conversation** | ❌ 无 | ❌ 无 | Memory.content | ⚠️ 不是独立抽象 | **不作为顶层抽象** |
| **Message** | ❌ 无 | ❌ 无 | Spring AI Message | ✅ 标准 | **复用 Spring AI** |
| **Memory** | ❌ 无 | ❌ 无明确概念 | Short + Long | ❌ 两种不同语义 | **M2 先做 short-term** |
| **Short-term Memory** | ❌ 无 | ❌ 无 | Conversation history | ✅ 对话历史 | **M2 需要** |
| **Long-term Memory** | ❌ 无 | ❌ 无 | 跨 session 知识 | ⚠️ 独立能力 | **M3+** |
| **State** | ❌ 无 | OverAllState（workflow） | Agent State（运行时） | ❌ 不同层次 | **M2 需要区分** |
| **Execution State** | ❌ 无 | Graph execution state | ❌ 瞬态 | ❌ 不同模型 | **暂不需要** |
| **Context** | ❌ 无 | ❌ 无明确 | Runtime context | ⚠️ 多义 | **避免使用 Context** |
| **Checkpoint** | ❌ 无 | Graph checkpoint | State snapshot | ✅ 快照 | **M2 先简化** |
| **Evidence** | Evidence（框架语义） | ❌ 无 | ❌ 无 | ⚠️ Arctra 独有 | **保持** |
| **Tool Invocation** | Tool call（瞬态） | Node execution | Tool call | ✅ 瞬态 | **M2 需进入 history** |

---

## 第三部分：核心架构发现

### 发现 1：状态外置是正确设计

**AgentScope 模式：**
```
Agent (stateless template)
    ↓
AgentStateStore (external state)
    ↓ keyed by (userId, sessionId)
Per-session state
```

**WHY 正确：**
- Agent 可以是单例
- 支持 multi-tenancy
- 支持跨进程恢复
- 支持水平扩展

**对 Arctra 启示：**
> AgentDefinition 不应该持有 session state  
> Session state 由 SessionRuntime 外置管理

---

### 发现 2：Session 是状态管理边界，不是业务概念

**AgentScope 清晰定义：**
> Session = "a collection of StateModule"

**不是：**
- ❌ Session ≠ Conversation
- ❌ Session ≠ User
- ❌ Session ≠ Agent instance

**是：**
- ✅ Session = 状态持久化单元
- ✅ Session = 多租户隔离边界
- ✅ Session = 状态恢复标识

**对 Arctra 启示：**
> Session 应该是 Runtime 层概念  
> 不是 AgentDefinition 的属性

---

### 发现 3：Memory 必须分层

**AgentScope 双层设计：**

**为什么必须分层：**
| 问题 | Short-term | Long-term |
|------|-----------|-----------|
| Context 爆炸 | Bounded window | 提炼知识 |
| 跨 session | ❌ 每次清空 | ✅ 持续积累 |
| Compaction | Sliding window | 语义提炼 |
| 注入位置 | Conversation | System prompt |

**对 Arctra 启示：**
> M2 先做 short-term（conversation history）  
> Long-term 是独立 milestone（M3+）  
> 不要混在一起

---

### 发现 4：Execution ≠ Session

**两种模型对比：**

**AgentScope 模型：**
```
Session (persistent)
    ↓ contains multiple
Execution (transient)
    ↓ produces
Result → updates Session state
```

**Graph 模型：**
```
Execution State (persistent)
    ↓ contains
Node executions
    ↓
Can pause/resume/checkpoint
```

**对 Arctra 启示：**
> M2 应该采用 AgentScope 模型  
> Execution 是瞬态，Session 是持久化单元  
> Graph 的 pauseable execution 是 M3+ 能力

---

### 发现 5：自动 save/load 优于手动

**AgentScope HarnessAgent：**
> "State persistence is on by default"  
> "auto-saved at the end of every call() and auto-loaded on the next"

**用户体验：**
```python
# 用户无需显式 save/load
agent.call(userId="A", sessionId="X", message="hello")
agent.call(userId="A", sessionId="X", message="continue")
# Framework 自动恢复上下文
```

**对 Arctra 启示：**
> SessionRuntime 应该自动管理 save/load  
> 用户只需传递 sessionId  
> 不要暴露 save/restore API

---

### 发现 6：Session Lock 是必需的

**AgentScope 设计：**
> "Session locks — Enforce single-run-per-session"

**WHY 必需：**
- 防止并发修改 state
- 保证消息顺序
- 避免 race condition

**对 Arctra 启示：**
> M2 必须考虑并发控制  
> 至少在文档中说明限制

---

### 发现 7：Tool result 必须进入 conversation history

**AgentScope / Spring AI 共识：**
- Tool call 作为 Message
- Tool result 作为 Message
- 成为 conversation context 的一部分

**对 Arctra 启示：**
> M1 Evidence 机制需要扩展  
> Evidence 应该进入 conversation history  
> 而不只是返回给用户

---

### 发现 8：System prompt 可以是动态的

**AgentScope 设计：**
- System prompt 每次推理重建
- 从 `AGENTS.md` + `MEMORY.md` 注入
- 支持运行时修改

**对 Arctra 启示：**
> AgentDefinition.description 太静态  
> 未来可以支持 session-level persona  
> 但 M2 暂不需要

---

## 第四部分：Spring AI Alibaba vs AgentScope 差异

### 核心差异

| 维度 | Spring AI Alibaba Graph | AgentScope |
|------|------------------------|------------|
| **核心抽象** | Graph（workflow orchestration） | Agent（ReAct loop） |
| **状态模型** | OverAllState（workflow 级别） | Agent State（instance 级别） |
| **执行模型** | Stateful execution（可暂停） | Stateless execution（瞬态） |
| **主要场景** | 复杂 workflow / 多 agent 编排 | 单 agent multi-turn conversation |
| **架构哲学** | LangGraph-inspired（workflow-first） | Agent-first（conversation-first） |
| **Production 支持** | 通过 Graph 持久化 | 通过 Agent Service 层 |
| **多租户** | 未明确 | 内置 multi-tenancy |
| **Memory 管理** | 未明确 | 双层 memory（short + long） |

### 解决的不同问题

**Spring AI Alibaba Graph 解决：**
- 复杂 workflow 编排（conditional routing, parallel nodes）
- 长时间运行任务（checkpoint, resume）
- Human-in-the-loop（approval nodes）
- Multi-agent coordination

**AgentScope 解决：**
- Multi-turn conversation（session management）
- Context management（compaction, offload）
- Multi-tenancy（isolation, scaling）
- Long-term memory（knowledge accumulation）

**两者不冲突：**
- Graph 是 orchestration layer
- AgentScope 是 agent runtime layer
- 可以组合：Graph node 调用 AgentScope agent

---

## 第五部分：对 Arctra 的启示

### 启示 1：M2 应该专注 Session，不是 Graph

**Arctra 定位：**
> "Agent Engineering Harness for Spring ecosystem"

**M2 核心问题：**
- ✅ Multi-turn conversation（session management）
- ✅ Context retention（conversation history）
- ❌ 不是 workflow orchestration
- ❌ 不是 multi-agent coordination

**理由：**
- Graph 是独立能力（M3+）
- Session 是 conversation 基础
- 先解决单 agent multi-turn
- 再考虑 multi-agent

---

### 启示 2：采用 AgentScope 的 Session 模型，不是 Graph 模型

**推荐模型：**
```
Session (持久化边界)
    ↓ contains multiple
Execution (瞬态)
    ↓ produces
AgentResult → updates Session state
```

**不采用 Graph 的 Execution State 模型：**
- 过于复杂（checkpoint, resume, pause）
- M2 不需要 long-running workflow
- Conversation agent 不需要 pauseable execution

---

### 启示 3：状态外置 + 自动 save/load

**推荐架构：**
```
SessionRuntime (stateless)
    ↓ manages
SessionRepository (external state)
    ↓ keyed by sessionId
Per-session state:
  - Conversation history
  - Agent state (if any)
```

**用户体验：**
```java
sessionRuntime.execute(
    sessionId,
    agentDefinition,
    new AgentRequest("continue")
);
// Framework 自动 load history → execute → save
```

---

### 启示 4：M2 只做 Short-term Memory（Conversation History）

**M2 范围：**
- ✅ 保存 conversation messages
- ✅ 加载 conversation history
- ✅ 基础 compaction（sliding window）
- ❌ 不做 long-term memory
- ❌ 不做 knowledge extraction
- ❌ 不做 semantic retrieval

**理由：**
- Long-term memory 是独立复杂能力
- M2 focus 是 multi-turn conversation
- 先验证 session 机制

---

### 启示 5：复用 Spring AI Message，不创建 ArctraMessage

**决策：**
- ✅ 直接使用 `org.springframework.ai.chat.messages.Message`
- ✅ 包括 UserMessage, AssistantMessage, ToolCallMessage, ToolResponseMessage
- ❌ 不创建 `ArctraMessage` wrapper

**理由：**
- Spring AI Message 已经足够完善
- 符合 EVOLUTION-GUIDE "复用成熟能力"
- 减少维护成本

---

### 启示 6：Evidence 与 Conversation History 的关系

**当前 M1：**
- Evidence 只返回给用户
- 不进入 conversation context

**M2 应该：**
- Tool call → ToolCallMessage
- Tool result → ToolResponseMessage + Evidence
- Evidence 作为 metadata 或 parallel track

**架构：**
```
Conversation History (for model)
  - UserMessage
  - AssistantMessage
  - ToolCallMessage
  - ToolResponseMessage

Evidence Track (for governance/audit)
  - Evidence(source, content)
  - Parallel to messages
  - 不影响 conversation flow
```

---

### 启示 7：SessionRuntime 不应该暴露 AgentState

**错误设计：**
```java
// BAD: 暴露 state 管理
interface SessionRuntime {
    AgentState getState(String sessionId);
    void setState(String sessionId, AgentState state);
}
```

**正确设计：**
```java
// GOOD: 自动管理 state
interface SessionRuntime {
    SessionExecutionResult execute(
        String sessionId,
        AgentDefinition definition,
        AgentRequest request
    );
}
```

**理由：**
- State 是实现细节
- 用户只关心 execute
- Framework 自动 save/load

---

### 启示 8：Session ID 由调用者提供

**推荐 API：**
```java
// 用户控制 session identity
sessionRuntime.execute(
    "session-123",  // 用户提供
    agentDef,
    request
);
```

**不是：**
```java
// BAD: Framework 生成 session ID
var session = sessionRuntime.createSession();
session.execute(request);
```

**理由：**
- 用户可能有自己的 session ID 规则
- 支持外部系统集成
- 符合 AgentScope 设计

---

### 启示 9：M2 不做 Multi-Agent

**明确延后：**
- ❌ ParallelAgent
- ❌ RoutingAgent
- ❌ Agent Team
- ❌ Agent-to-Agent communication

**M2 专注：**
- ✅ Single agent
- ✅ Multi-turn conversation
- ✅ Session management

**理由：**
- Multi-agent 是独立复杂能力
- 需要 Graph / workflow 支持
- M2 先验证 session 基础

---

### 启示 10：Storage 必须可插拔

**推荐架构：**
```java
interface SessionRepository {
    Session save(Session session);
    Optional<Session> findById(String sessionId);
    
    void appendMessage(String sessionId, Message message);
    List<Message> getMessages(String sessionId);
}

// 实现
InMemorySessionRepository (M2 测试)
JdbcSessionRepository (生产)
RedisSessionRepository (未来)
```

**理由：**
- 不同场景需要不同存储
- 测试需要 in-memory
- 生产需要持久化

---

## 第六部分：Arctra 可以做得更好的地方

### 机会 1：更简洁的 API

**AgentScope 当前：**
```python
await session.save_session_state(session_id="user_1", agent=agent)
await session.load_session_state(session_id="user_1", agent=agent)
```

**Arctra 可以：**
```java
// 完全自动
sessionRuntime.execute(sessionId, agentDef, request);
// 无需显式 save/load
```

**优势：**
- 更简单的用户体验
- 更少出错机会
- 框架承担更多责任

---

### 机会 2：Evidence 与 Session 的自然集成

**AgentScope：**
- 没有 Evidence 概念

**Arctra 优势：**
- M1 已有 Evidence 机制
- M2 可以自然扩展到 session
- Evidence 成为 session 的一部分

**设计：**
```java
record SessionExecutionResult(
    AgentResult agentResult,
    Session session,
    List<Evidence> evidences  // session 级别的 evidence
)
```

---

### 机会 3：单轮 → 多轮 平滑升级

**目标：**
```java
// M1 单轮（继续支持）
AgentResult result = runtime.execute(def, request);

// M2 多轮（自然扩展）
SessionExecutionResult result = sessionRuntime.execute(
    sessionId,  // 新增参数
    def,
    request
);
```

**优势：**
- 不破坏 M1 API
- 用户渐进式迁移
- 单轮场景无负担

---

### 机会 4：避免过多 Manager / Registry

**AgentScope 有：**
- SessionManager
- Agent Service
- MessageBus
- BackgroundTaskManager
- SchedulerManager
- WakeupDispatcher

**Arctra 可以：**
- SessionRuntime（统一入口）
- SessionRepository（存储）
- 不创建过多 Manager

**优势：**
- 更简洁的架构
- 更少的概念
- 更容易理解

---

### 机会 5：TypeSafe Session

**AgentScope：**
```python
session_id: str  # 字符串，容易出错
```

**Arctra 可以：**
```java
record SessionId(String value) {
    // Type-safe wrapper
}

sessionRuntime.execute(
    SessionId.of("session-123"),
    ...
);
```

**优势：**
- 类型安全
- 编译期检查
- IDE 支持

---

## 第七部分：M2 架构决策

### 决策 1：Session 归属 arctra-core

**位置：**
```
cn.bitcss.arctra.session/
  - Session
  - SessionRepository
  - SessionEvent (or ConversationMessage)
```

**理由：**
- Session 是 framework 语义
- 不依赖 Spring AI
- 可以被不同 runtime 复用

---

### 决策 2：SessionRuntime 归属 arctra-core

**位置：**
```
cn.bitcss.arctra.runtime/
  - SessionRuntime (new)
  - AgentRuntime (existing)
  - AgentExecutionEngine (existing)
```

**关系：**
```
SessionRuntime
    ↓ uses
AgentExecutionEngine (不变)
```

**理由：**
- SessionRuntime 是 runtime 层新抽象
- 委托给 AgentExecutionEngine
- Engine 无需感知 session

---

### 决策 3：复用 Spring AI Message

**决策：**
- ✅ 使用 `org.springframework.ai.chat.messages.Message`
- ✅ UserMessage, AssistantMessage, ToolCallMessage, ToolResponseMessage
- ❌ 不创建 ArctraMessage

**Session 中存储：**
```java
record Session(
    String id,
    Instant createdAt,
    List<Message> messages  // Spring AI Message
)
```

---

### 决策 4：M2 实现 InMemorySessionRepository + 可选 JdbcSessionRepository

**M2 必须：**
- InMemorySessionRepository（测试用）

**M2 可选：**
- JdbcSessionRepository（如果时间允许）

**M3+：**
- RedisSessionRepository
- 其他存储

---

### 决策 5：M2 不实现 Compaction

**决策：**
- ❌ M2 不实现 context compaction
- ⚠️ 在文档中说明限制
- ✅ M3 实现 compaction

**理由：**
- Compaction 是优化，不是核心
- M2 focus 是验证 session 机制
- 可以手动限制消息数量

---

### 决策 6：M2 不实现 Long-term Memory

**决策：**
- ❌ M2 不实现 long-term memory
- ✅ M2 只做 conversation history（short-term）

**理由：**
- Long-term memory 是独立能力
- 需要知识提取、语义检索
- M2 不是 memory milestone

---

### 决策 7：M2 不实现 Multi-Agent

**决策：**
- ❌ M2 不实现 multi-agent
- ✅ M2 只验证 single-agent multi-turn

**理由：**
- Multi-agent 需要 orchestration（Graph）
- M2 focus 是 session

---

### 决策 8：M2 不实现 Session Lock

**决策：**
- ⚠️ M2 不实现并发控制
- ⚠️ 在文档中说明：同一 session 不支持并发

**理由：**
- Lock 需要分布式协调（Redis）
- M2 focus 是 session 基础
- M3 实现 production-grade lock

---

## 第八部分：M2 最终推荐方案

### 核心抽象（最小集）

```java
// arctra-core
package cn.bitcss.arctra.session;

public record Session(
    String id,
    Instant createdAt,
    Instant lastAccessedAt
) {}

public interface SessionRepository {
    Session save(Session session);
    Optional<Session> findById(String id);
    
    void appendMessage(String sessionId, Message message);
    List<Message> getMessages(String sessionId);
    List<Message> getMessages(String sessionId, int maxMessages);
}

// arctra-core
package cn.bitcss.arctra.runtime;

public interface SessionRuntime {
    SessionExecutionResult execute(
        String sessionId,
        AgentDefinition definition,
        AgentRequest request
    );
}

public record SessionExecutionResult(
    AgentResult agentResult,
    Session session,
    List<Message> newMessages
) {}
```

### 实现（最小集）

```java
// arctra-session-memory
public class InMemorySessionRepository implements SessionRepository {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> messages = new ConcurrentHashMap<>();
    
    // 实现
}

// arctra-runtime-react
public class SpringAiSessionRuntime implements SessionRuntime {
    private final AgentExecutionEngine engine;  // 复用 M1
    private final SessionRepository sessionRepo;
    
    @Override
    public SessionExecutionResult execute(
        String sessionId,
        AgentDefinition definition,
        AgentRequest request
    ) {
        // 1. Load session & history
        Session session = sessionRepo.findById(sessionId)
            .orElse(createNewSession(sessionId));
        List<Message> history = sessionRepo.getMessages(sessionId);
        
        // 2. Build new user message
        UserMessage userMsg = new UserMessage(request.userMessage());
        
        // 3. Combine history + new message
        List<Message> allMessages = new ArrayList<>(history);
        allMessages.add(userMsg);
        
        // 4. Execute engine (传递完整历史)
        // TODO: 需要修改 Engine contract 支持 messages
        AgentResult result = engine.execute(definition, request);
        
        // 5. Extract new messages from result
        List<Message> newMessages = extractMessages(result);
        
        // 6. Append to session
        newMessages.forEach(msg -> sessionRepo.appendMessage(sessionId, msg));
        
        // 7. Update session
        Session updatedSession = updateSession(session);
        sessionRepo.save(updatedSession);
        
        return new SessionExecutionResult(result, updatedSession, newMessages);
    }
}
```

### M2 不创建

❌ `AgentState`  
❌ `ConversationHistory`  
❌ `Memory`  
❌ `LongTermMemory`  
❌ `CompactionStrategy`  
❌ `SessionManager`  
❌ `SessionLock`  
❌ `MultiAgentOrchestrator`

---

## 第九部分：关键问题回答

### Q: Session 到底是什么？

**A:** Session 是**状态持久化边界**，不是业务概念。

它解决：
- 多轮对话的状态连续性
- Multi-tenancy 隔离
- 跨进程/重启恢复

它不是：
- Conversation（业务概念）
- User（身份概念）
- Agent instance（执行实体）

---

### Q: Execution 与 Session 的关系？

**A:** Session 包含多次 Execution，Execution 是瞬态的。

```
Session (persistent)
    ├── Execution #1 (transient)
    ├── Execution #2 (transient)
    └── Execution #3 (transient)
```

---

### Q: 多轮状态由谁持有？

**A:** 由 SessionRuntime 外置管理，Agent 是无状态的。

```
SessionRuntime (stateless)
    ↓
SessionRepository (external state)
    ↓
Per-session conversation history
```

---

### Q: AgentDefinition 应该包含什么？

**A:** 只包含静态配置，不包含 session state。

```java
// M1（保持）
record AgentDefinition(String name, String description)

// M2 可能扩展（但不包含 session）
record AgentDefinition(
    String name,
    String description,
    List<String> toolRefs  // 未来
    // ❌ 不包含 sessionId
    // ❌ 不包含 conversation history
)
```

---

### Q: Conversation History 在哪里？

**A:** 在 SessionRepository 中，作为 `List<Message>`。

不是独立抽象，是 session 的内容。

---

### Q: M2 需要 Memory 抽象吗？

**A:** 不需要。M2 只需要 conversation history（`List<Message>`）。

Long-term memory 是 M3+ 能力。

---

## 第十部分：参考资料

### AgentScope

- [State/Session Management](https://doc.agentscope.io/tutorial/task_state.html)
- [Harness Architecture](https://java.agentscope.io/v2/en/docs/harness/architecture.html)
- [Agent Service Architecture](https://docs.agentscope.io/latest/en/deploy/agent-service)
- [Memory Management](https://java.agentscope.io/v2/en/docs/harness/memory.html)

### Spring AI Alibaba

- [What is Spring AI Alibaba Graph](https://java2ai.com/en/docs/1.0.0.2/tutorials/graph/whats-spring-ai-alibaba-graph/)
- [Quick Start](https://java2ai.com/en/docs/1.0.0.2/tutorials/graph/quick-guide/)

### Spring AI

- [Session API Blog](https://spring.io/blog/2026/04/15/spring-ai-session-management)

---

## 第十一部分：下一步行动

### M2-T1 设计文档应该包含

1. **Session Domain Model**
   - Session 定义（基于 AgentScope 模型）
   - SessionRepository contract
   - 明确不包含 AgentState

2. **SessionRuntime Contract**
   - SessionRuntime ↔ AgentExecutionEngine 边界
   - 明确 Engine 不感知 session
   - 自动 save/load 机制

3. **Message Storage Strategy**
   - 使用 Spring AI Message
   - 不创建 ArctraMessage
   - InMemorySessionRepository 实现

4. **Engine Contract 扩展评估**
   - 当前 Engine 接收单轮 request
   - SessionRuntime 如何传递 history？
   - 是否需要修改 Engine contract？

5. **Scenario Verification**
   - Multi-turn incident analysis
   - Session isolation 验证
   - Cross-call context retention

6. **Non-Goals 明确**
   - ❌ Compaction
   - ❌ Long-term memory
   - ❌ Multi-agent
   - ❌ Session lock

---

**研究完成。准备进入 M2-T1 设计阶段。**
