# M2 Session 能力：框架对比与提升方向

**日期：** 2026-08-18  
**目标：** 分析 Spring AI Alibaba、AgentScope、Spring AI Session API 的优秀设计，明确 Arctra M2 提升方向

---

## 1. 三大框架核心能力对比

### 1.1 Spring AI Session API (2026-04 发布)

**核心价值：** 替代 ChatMemory 的 Event-Sourced Session 管理

#### 核心抽象

```java
// Session metadata
Session(id, userId, ttl, metadata)

// Event with identity
SessionEvent(uuid, sessionId, timestamp, branch, Message)

// Compaction trigger
TurnCountTrigger / TokenCountTrigger / CompositeCompactionTrigger

// Compaction strategy
SlidingWindowCompactionStrategy
TurnWindowCompactionStrategy
TokenCountCompactionStrategy
RecursiveSummarizationCompactionStrategy

// Advisor integration
SessionMemoryAdvisor
```

#### 关键设计亮点

**1. Turn Safety（轮次安全）**
- Turn 定义：`UserMessage` + 所有后续事件（assistant reply, tool calls, tool results）
- 所有 compaction 策略强制 snap to turn boundaries
- 永远不会产生孤立的 tool result 或不完整的交互

**2. Event Sourcing**
- 完整 event log 永久保留（append-only）
- 每个 event 有 UUID + timestamp
- Compaction 只影响 active prompt，不删除历史
- 支持 keyword search 完整历史

**3. Multi-Agent Branch Isolation**
- `SessionEvent.branch` — dot-separated path（e.g., `"orch.researcher"`）
- 每个 agent 只看到：root events (branch=null) + 自己的 ancestor branches
- Sibling branches 互相隔离
- Synthetic summary 永远是 branch=null（全局可见）

**4. Context Compaction 分层**
- Trigger（何时压缩）与 Strategy（如何压缩）分离
- 必须同时配置 trigger + strategy（否则 IllegalArgumentException）
- LLM-based summarization 是可选策略之一（不强制）

**5. Transparent Integration**
- `SessionMemoryAdvisor` 无缝集成 ChatClient pipeline
- Session ID 运行时传入（advisor context key）
- Missing session 自动创建
- JDBC starter 自动配置 schema + beans

#### 解决的痛点

| ChatMemory 问题 | Session API 解决方案 |
|----------------|---------------------|
| 无 turn safety | Turn boundary enforcement |
| 无 event identity | UUID + timestamp on every event |
| 无 multi-agent 支持 | Branch-based isolation |
| 无历史 recall | Full event log + keyword search |
| 简单 eviction | Pluggable trigger + strategy |
| 无法审计 | Complete audit trail |

---

### 1.2 Spring AI Alibaba Agent Framework

**核心价值：** 面向 Spring 生态的 Agentic AI Framework

#### 三层架构

```
Spring AI Alibaba Admin (观测、评估、MCP 管理)
    ↓
Agent Framework (开发者 API，内置模式)
    ↓
Graph (有状态 runtime)
```

#### 关键设计亮点

**1. Built-in Agent Composition Patterns**
```java
SequentialAgent   // 线性 pipeline
ParallelAgent     // 并发执行
RoutingAgent      // 条件分发
LoopAgent         // 迭代执行
```

**2. Context Engineering Best Practices**
- Context compaction & editing（内置）
- Model & tool call limits
- Tool retry & planning
- Dynamic tool selection

**3. Human In The Loop**
- 作为 first-class 能力内置
- 通过 hooks 机制实现（`tutorials/hooks`）

**4. Graph-based State Management**
- Persistence（持久化）
- Workflow orchestration（流程编排）
- Streaming（流式输出）
- Conditional routing（条件路由）
- Nested graphs（嵌套图）
- State snapshots（可导出 PlantUML/Mermaid）

**5. Agent-to-Agent (A2A)**
- Nacos 集成
- 分布式 agent 协调
- 跨服务通信

#### 架构哲学

- **分离关注点：** orchestration (Agent Framework) vs. runtime (Graph) vs. platform (Admin)
- **可降级：** 开发者可降级到 Graph API 获得更多控制
- **Production-ready：** context engineering policies 预置，不留给开发者

---

### 1.3 AgentScope

**核心价值：** Production-ready multi-agent framework (Python)

#### 核心抽象

```python
Agent(name, system_prompt, model, toolkit)

Context   # 自动压缩、tool result offload、context injection
Memory    # 可插拔后端（ReMe, Mem0）
Event     # 统一事件总线（reasoning, tool calls, multimodal）

Agent Service  # Multi-tenancy、multi-session isolation
Agent Team     # Leader-worker orchestration
Middleware     # Composable hooks across loop
```

#### 关键设计亮点

**1. ReAct Loop with Structured Output**
- Realtime interruption & resume
- Batched (sequential / concurrent) tool acting
- Async execution（`async def main()`）

**2. Context Middleware**
- Automatic compaction
- Tool-result offload（工具结果可移出主 context）
- Context injection（system prompt, RAG, memory）
- Built-in middleware

**3. Event System**
- Unified event bus
- Streaming reasoning, tool calls, multimodal content
- Frontend integration

**4. Agent Service Layer**
- Leader–worker orchestration
- Built-in team tools
- Task planning
- Multi-tenancy & multi-session isolation
- Background task offloading（长任务后台运行，结果唤醒 agent）

**5. Permission & HITL**
- Fine-grained control
- Confirmation vs bypass mode
- Per-tool approval

**6. Sandbox / Workspace**
- Isolated execution
- Multiple backends（local, Docker, K8s, E2B, Daytona）

---

## 2. Arctra M1 现状 Gap 分析

### 2.1 M1 已有

✅ **单轮执行流程**
- AgentDefinition / AgentRequest / AgentResult
- AgentExecutionEngine（可替换）
- SpringAiToolCallingEngine（完全复用 Spring AI Tool Calling Loop）
- Evidence capture（per-execution）

✅ **架构边界清晰**
- arctra-core 纯 Java（无 Spring 依赖）
- Runtime ↔ Engine 分离
- 无过早抽象

### 2.2 M1 不具备（M2 需要）

❌ **Session 管理**
- 无 session identity
- 无 conversation history 保存
- 无 multi-turn context retention
- 无 session lifecycle

❌ **Context Management**
- 无 context compaction
- 无 token budget 管理
- 无 turn boundary enforcement
- 无历史 recall

❌ **Multi-Agent 能力**
- 无 agent isolation
- 无 branch-based session
- 无 agent-to-agent communication

❌ **HITL / Governance**
- 无 approval workflow
- 无 tool permission check
- 无 policy evaluation

---

## 3. M2 提升方向建议

### 优先级 P0：Session 核心能力（必须）

**直接借鉴 Spring AI Session API 设计：**

#### 3.1 Session Domain Model

```java
// arctra-core
package cn.bitcss.arctra.session;

public record Session(
    String id,
    String userId,         // optional, for multi-tenancy
    Instant createdAt,
    Instant lastAccessedAt,
    Map<String, Object> metadata
) {}

public record SessionEvent(
    UUID eventId,
    String sessionId,
    Instant timestamp,
    String branch,         // for multi-agent isolation (future)
    Message message        // Spring AI Message (reuse)
) {}
```

**关键决策：**
- ✅ 复用 Spring AI `Message`（不创建 ArctraMessage）
- ✅ Event sourcing pattern（append-only log）
- ✅ Turn safety 强制执行
- ❌ M2 暂不实现 branch isolation（defer to multi-agent milestone）

#### 3.2 Session Repository Contract

```java
// arctra-core
package cn.bitcss.arctra.session;

public interface SessionRepository {
    Session save(Session session);
    Optional<Session> findById(String id);
    
    void appendEvent(SessionEvent event);
    List<SessionEvent> getEvents(String sessionId);
    List<SessionEvent> getEvents(String sessionId, int maxEvents);
}
```

**实现：**
- `arctra-session-jdbc`（复用 Spring AI Session JDBC schema）
- `arctra-session-memory`（M2 测试用）

#### 3.3 Context Compaction（简化版）

**M2 只实现最简单策略：**

```java
// arctra-core
public interface CompactionTrigger {
    boolean shouldCompact(List<SessionEvent> events);
}

public interface CompactionStrategy {
    List<SessionEvent> compact(List<SessionEvent> events);
}

// arctra-session
public class TurnCountTrigger implements CompactionTrigger
public class SlidingWindowStrategy implements CompactionStrategy
```

**M2 不实现：**
- ❌ TokenCountTrigger（需要 tokenizer 集成）
- ❌ RecursiveSummarizationStrategy（需要 LLM summarization，复杂度高）
- ❌ CompositeCompactionTrigger

**理由：**
- Turn-based window 足够验证 session 机制
- Token-based 和 LLM-based 属于优化，不是核心能力
- 符合"最小可验证"原则

#### 3.4 SessionRuntime Contract

```java
// arctra-core
package cn.bitcss.arctra.runtime;

public interface SessionRuntime {
    /**
     * Execute agent with session context.
     * 
     * @param sessionId existing or new session
     * @param definition agent definition
     * @param request single-turn user request
     * @return result with updated session
     */
    SessionExecutionResult execute(
        String sessionId,
        AgentDefinition definition,
        AgentRequest request
    );
}

public record SessionExecutionResult(
    AgentResult agentResult,
    Session session,
    List<SessionEvent> newEvents
) {}
```

**关键设计：**
- SessionRuntime 委托给 AgentExecutionEngine（复用 M1 engine）
- Session 管理是 Runtime 关注点，Engine 无感知
- Engine 继续接收单轮 request（不改变 M1 contract）

---

### 优先级 P1：Spring AI Integration（推荐）

#### 3.5 复用 Spring AI ChatMemory Advisor

**选项 A：直接使用 Spring AI MessageChatMemoryAdvisor**

```java
// arctra-runtime-react
public class SpringAiSessionRuntime implements SessionRuntime {
    private final AgentExecutionEngine engine;
    private final SessionRepository sessionRepo;
    private final ChatModel chatModel;
    
    @Override
    public SessionExecutionResult execute(
        String sessionId, 
        AgentDefinition def, 
        AgentRequest req
    ) {
        // 1. Load session
        Session session = sessionRepo.findById(sessionId)
            .orElse(createNewSession(sessionId));
        
        // 2. Load history
        List<SessionEvent> events = sessionRepo.getEvents(sessionId);
        List<Message> history = toMessages(events);
        
        // 3. Build ChatClient with memory
        var chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(
                new MessageChatMemoryAdvisor(
                    new InMemoryChatMemory() // or SessionRepositoryAdapter
                )
            )
            .build();
        
        // 4. Execute via engine (engine uses ChatClient internally)
        AgentResult result = engine.execute(def, req);
        
        // 5. Append new events
        List<SessionEvent> newEvents = captureNewEvents(req, result);
        newEvents.forEach(sessionRepo::appendEvent);
        
        return new SessionExecutionResult(result, session, newEvents);
    }
}
```

**优点：**
- 完全复用 Spring AI 成熟能力
- 与 M1 一致的"优先复用"原则
- 减少维护成本

**缺点：**
- MessageChatMemoryAdvisor 不支持 turn safety
- 不支持 branch isolation
- 无 event sourcing（只是 in-memory list）

**选项 B：等待 Spring AI Session API 稳定后直接使用**

```java
// 未来（如果 Spring AI Session API 成熟）
public class SpringAiSessionRuntime implements SessionRuntime {
    private final AgentExecutionEngine engine;
    private final org.springframework.ai.session.SessionRepository sessionRepo;
    
    @Override
    public SessionExecutionResult execute(...) {
        // 直接使用 Spring AI SessionMemoryAdvisor
        var chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(
                new SessionMemoryAdvisor(
                    sessionRepo,
                    new TurnCountTrigger(10),
                    new SlidingWindowStrategy(20)
                )
            )
            .build();
        
        // ...
    }
}
```

**优点：**
- 获得 turn safety + event sourcing + branch isolation
- Production-ready（Spring AI 官方支持）
- 零维护成本

**缺点：**
- Spring AI Session API 2026-04 才发布，可能不够稳定
- M2 时间点可能还不成熟

**推荐策略（混合）：**

**M2 实现顺序：**
1. **先用 MessageChatMemoryAdvisor 快速验证 session 流程**
   - 证明 SessionRuntime ↔ Engine 边界正确
   - 验证 multi-turn conversation scenario
   - 快速交付 M2

2. **M3 迁移到 Spring AI Session API**
   - 等 Spring AI Session API 稳定
   - 获得 turn safety + event sourcing + branch isolation
   - 只需修改 SessionRuntime 实现，不影响 core contracts

---

### 优先级 P2：Context Engineering（可选）

#### 3.6 借鉴 AgentScope Middleware 模式

**未来可考虑（不是 M2）：**

```java
// arctra-core（未来）
public interface AgentMiddleware {
    void beforeExecution(ExecutionContext context);
    void afterExecution(ExecutionContext context, AgentResult result);
    void onToolCall(String toolName, String arguments);
    void onToolResult(String toolName, String result);
}

// Examples
ContextCompressionMiddleware
PermissionCheckMiddleware
AuditMiddleware
```

**M2 不实现理由：**
- M2 focus 是 session，不是 middleware 框架
- AgentScope middleware 是 Python dynamic 特性，Java 实现成本高
- 可以用 Spring AOP / Advisor 实现类似效果

---

### 优先级 P3：Multi-Agent（延后）

#### 3.7 借鉴 Spring AI Alibaba Agent Composition

**未来（M3+）：**

```java
// 借鉴但不照搬 API
SequentialAgent
ParallelAgent  
RoutingAgent
LoopAgent
```

**M2 不实现理由：**
- M2 验证 single-agent multi-turn
- Multi-agent 需要独立 milestone
- Spring AI Alibaba 的 Graph layer 很重，需要深入研究

---

## 4. EVOLUTION-GUIDE 原则检查

### 4.1 候选抽象逐个判断

#### Session

**现在谁在用？** M2 Conversation Agent  
**不加做不了什么？** 无法保持多轮上下文  
**是否有两个实现？** JDBC + Memory（测试）  
**新语义？** 是，Session 是独立生命周期概念  
**Spring AI 是否已提供？** **是**（Session API 2026-04）  
**Spring AI Alibaba 是否已提供？** 部分（Graph layer）  
**AgentScope 是否已提供？** 是（Agent Service）

**结论：** ✅ **需要创建，但优先复用 Spring AI 实现**

#### SessionRuntime

**现在谁在用？** M2 Conversation Agent  
**不加做不了什么？** 无法统一 session 管理  
**是否有两个实现？** SpringAiSessionRuntime（初期唯一）  
**新语义？** 是，Runtime 层新的 session 关注点  
**Spring AI 是否已提供？** 部分（SessionMemoryAdvisor）

**结论：** ✅ **需要创建 Arctra contract，实现委托 Spring AI**

#### SessionEvent

**现在谁在用？** SessionRuntime  
**不加做不了什么？** 无法持久化 conversation history  
**新语义？** 是，event sourcing 语义  
**Spring AI 是否已提供？** **是**（Session API）

**结论：** ✅ **需要，但数据结构复用 Spring AI**

#### CompactionTrigger / Strategy

**现在谁在用？** SessionRuntime  
**不加做不了什么？** 无法限制 context 大小  
**是否有两个实现？** TurnCount 一种足够 M2  
**新语义？** 是，compaction 策略  
**Spring AI 是否已提供？** **是**（Session API）

**结论：** ⚠️ **M2 先简化实现，M3 迁移到 Spring AI**

#### AgentMiddleware

**现在谁在用？** 无  
**不加做不了什么？** M2 可以不需要  
**是否有两个实现？** 无  
**AgentScope 是否已提供？** 是（Middleware）

**结论：** ❌ **M2 不需要，未来考虑**

---

### 4.2 能力复用决策流程

```
出现新需求（Session / Multi-turn）
    ↓
Spring AI 是否已有？
    ├─ 是 → Session API (2026-04)
    │       评估成熟度
    │       ├─ 成熟 → Direct Use
    │       └─ 不成熟 → MessageChatMemoryAdvisor (临时) + 后续迁移
    ↓
Spring AI Alibaba 是否已有？
    ├─ 是 → Graph layer（太重）
    │       评估复杂度
    │       └─ 太复杂 → 学习设计思想，轻量实现
    ↓
AgentScope 是否已有成熟实践？
    ├─ 是 → Agent Service / Context / Memory
    │       学习设计模式
    │       不复制 Python API
    ↓
当前项目是否真的存在差异化需求？
    ├─ 否 → 等待真实需求
    ├─ 是 → 评估是否属于平台核心能力
    ↓
决定：Direct Use / Composition / Lightweight Adapter / 自研
```

**M2 决策：**
- Session 基础能力：Direct Use Spring AI（MessageChatMemoryAdvisor → Session API）
- SessionRuntime contract：自研（Arctra 平台语义）
- Context compaction：Lightweight Adapter（先简化，后迁移）
- Multi-agent / Middleware：延后

---

## 5. M2 最终推荐方案

### 5.1 架构演进路径

**M2 Phase 1（快速验证）：**
```
SessionRuntime (Arctra)
    ↓ uses
MessageChatMemoryAdvisor (Spring AI)
    ↓ delegates to
AgentExecutionEngine (M1)
```

**M2 Phase 2 或 M3（迁移）：**
```
SessionRuntime (Arctra)
    ↓ uses
SessionMemoryAdvisor (Spring AI Session API)
    ↓ with
Turn Safety + Event Sourcing + Branch Isolation
    ↓ delegates to
AgentExecutionEngine (M1)
```

### 5.2 M2 最小类型清单

**arctra-core（纯 Java）：**
```java
package cn.bitcss.arctra.session;

public record Session(...)
public record SessionEvent(...)  // reuse Spring AI Message
public interface SessionRepository

package cn.bitcss.arctra.runtime;

public interface SessionRuntime
public record SessionExecutionResult(...)
```

**arctra-session-memory（测试用）：**
```java
public class InMemorySessionRepository implements SessionRepository
```

**arctra-session-jdbc（生产用，可选）：**
```java
public class JdbcSessionRepository implements SessionRepository
// 复用 Spring AI Session schema
```

**arctra-runtime-react（Spring AI 集成）：**
```java
public class SpringAiSessionRuntime implements SessionRuntime
// 内部使用 MessageChatMemoryAdvisor
```

### 5.3 M2 不创建

❌ `ArctraMessage`（复用 Spring AI Message）  
❌ `ConversationHistory`（用 List<SessionEvent>）  
❌ `AgentMiddleware`（延后）  
❌ `ContextCompressor`（用 Spring AI 或简化实现）  
❌ `MultiAgentOrchestrator`（M3+）  
❌ `ToolGovernance`（独立 milestone）

---

## 6. 对比总结表

| 能力 | Spring AI Session | Spring AI Alibaba | AgentScope | Arctra M2 计划 |
|------|------------------|-------------------|------------|---------------|
| **Session Management** | ✅ SessionMemoryAdvisor | ✅ Graph layer | ✅ Agent Service | ✅ 复用 Spring AI |
| **Turn Safety** | ✅ 强制执行 | ❓ 未知 | ✅ 结构化输出 | ✅ 复用 Spring AI |
| **Event Sourcing** | ✅ 完整实现 | ❓ 未知 | ✅ Event system | ✅ 简化版 or 复用 |
| **Context Compaction** | ✅ 4种策略 | ✅ Built-in | ✅ Middleware | ⚠️ M2 简化，M3 复用 |
| **Multi-Agent Isolation** | ✅ Branch-based | ✅ Graph | ✅ Team | ❌ M3+ |
| **HITL** | ❌ | ✅ Hooks | ✅ Permission | ❌ 独立 milestone |
| **Tool Governance** | ❌ | ❌ | ✅ Permission | ❌ 独立 milestone |
| **Agent Composition** | ❌ | ✅ 4种模式 | ✅ Team | ❌ M3+ |
| **Streaming** | ❌ | ✅ Graph | ✅ Event bus | ❌ 延后 |
| **Distributed** | ❌ | ✅ A2A | ✅ Service | ❌ 延后 |

**Arctra 差异化价值（未来）：**
- Spring 生态深度集成
- Evidence-based governance
- Skill / Experience / Playbook
- 企业级 RBAC / Multi-tenancy
- Checkpoint / Resume

---

## 7. M2-T1 行动建议

**M2-T1 设计文档应该包含：**

1. **Session Domain Model**
   - 明确 Session / SessionEvent 定义
   - 决定是否复用 Spring AI Message
   - 决定是否复用 Spring AI Session schema

2. **SessionRuntime Contract**
   - 明确 SessionRuntime ↔ AgentRuntime 边界
   - 明确 SessionRuntime ↔ Engine 交互
   - 决定 session 如何传递给 Engine

3. **Spring AI Integration 策略**
   - Phase 1: MessageChatMemoryAdvisor（快速验证）
   - Phase 2: SessionMemoryAdvisor（迁移计划）
   - 明确迁移触发条件

4. **Context Compaction 简化方案**
   - M2 只实现 TurnCountTrigger + SlidingWindow
   - 明确未来扩展点

5. **Test Strategy**
   - Conversation Agent scenario
   - Multi-turn E2E test
   - Session persistence test

**关键原则：**
- ✅ 优先复用 Spring AI 成熟能力
- ✅ 学习 AgentScope 设计思想，不复制 API
- ✅ 保持 Arctra 核心差异化（Evidence, Governance, Enterprise）
- ✅ 最小可验证（M2 focus 是 session，不是 middleware/multi-agent）
- ✅ 架构可演进（Phase 1 → Phase 2 平滑迁移）

---

**参考资料：**
- [Spring AI Session API Blog](https://spring.io/blog/2026/04/15/spring-ai-session-management)
- [Spring AI Alibaba GitHub](https://github.com/alibaba/spring-ai-alibaba)
- [AgentScope GitHub](https://github.com/agentscope-ai/agentscope)
- [EVOLUTION-GUIDE.md](../architecture/EVOLUTION-GUIDE.md)
