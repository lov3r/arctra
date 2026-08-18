# M2 Phase Planning: Multi-Turn Conversation Support

**日期：** 2026-08-18  
**状态：** IMPLEMENTATION IN PROGRESS  
**作者：** Claude (M2 Phase Planning)

**更新：** 2026-08-18 - M2-T2 COMPLETE

---

## ⚠️ SUPERSEDED DESIGN DECISIONS

本文档包含 M2 原始规划。部分设计在 M2-T1 PoC 和 M2-T2 Contract Gate 后已被修正。

**最终实现参考：**
- `docs/planning/M2-T2-CONTRACT-GATE-V2.md` - 最终架构决策
- `docs/implementation/M2-T2-IMPLEMENTATION-REPORT.md` - 实际实现

**关键变更：**
1. ✅ Engine contract 被修改（添加 3-param method）- 原规划建议保持不变已被推翻
2. ✅ AgentExecutionContext 被创建 - 最终判断为合理抽象
3. ❌ executeWithSession() 方案被否决 - 采用 canonical 3-param execute() 
4. ✅ ChatMemory 通过 constructor injection - 确认为 shared dependency

---

## 执行摘要

**M2 目标：** 让 Arctra Agent 支持有 session identity 的单 agent 多轮连续对话

**核心能力：**
1. Same session → conversation continuity（对话连续性）
2. Different session → conversation isolation（会话隔离）

**关键架构决策（原规划）：**
- Arctra owns session semantics（会话语义）✅ CONFIRMED
- Spring AI provides conversation storage（存储实现）✅ CONFIRMED
- Engine contract 保持不变（不修改 M1）❌ SUPERSEDED - Engine contract 被修改
- arctra-core 继续保持 framework-neutral ✅ CONFIRMED

**最终实现（M2-T2）：**
- ✅ AgentExecutionContext 作为 execution-level semantic
- ✅ AgentExecutionEngine 添加 3-param canonical method
- ✅ 2-param method 通过 default method 保持向后兼容
- ✅ ChatMemory 通过 constructor injection
- ✅ sessionId → conversationId 映射由 Engine 负责

**推荐方案（原规划）：** Conversation ID in Runtime Context + Spring AI ChatMemory

**最终实现（M2-T2）：** AgentExecutionContext + Engine Contract Evolution

参考最终实现文档：
- `docs/planning/M2-T2-CONTRACT-GATE-V2.md`
- `docs/implementation/M2-T2-IMPLEMENTATION-REPORT.md`

---

## 1. M2 核心目标

### 1.1 用户故事

**Scenario: Multi-turn Incident Investigation**

```java
// Turn 1
agent
    .session("incident-123")
    .user("生产环境 16:20 开始出现大量 500，请分析")
    .call();
// Agent 调用 queryLogs + getDeployment，返回初步分析

// Turn 2 (same session)
agent
    .session("incident-123")
    .user("那最可能的原因是什么？")
    .call();
// Agent 理解"那"指向 Turn 1 的上下文

// Turn 3 (different session)
agent
    .session("incident-456")
    .user("刚才的问题是什么？")
    .call();
// Agent 不能读取 incident-123 的历史
```

### 1.2 验收标准

**Must Have:**
1. ✅ 同一 session 多次调用，后续调用能访问前面的对话历史
2. ✅ 不同 session 完全隔离，互不影响
3. ✅ Tool call/result 正确进入 conversation history
4. ✅ Evidence 机制继续工作
5. ✅ arctra-core 保持 pure Java（无 Spring AI 依赖）
6. ✅ M1 单轮能力继续工作（不破坏）

**Should Have:**
7. ✅ Conversation history 自动保存/加载（用户无需手动管理）
8. ✅ 基础的 conversation history window（避免无限增长）

**Could Have:**
9. ⚠️ Conversation history 持久化（JDBC/Redis）— 可选，优先 in-memory
10. ⚠️ Session metadata（创建时间、最后访问时间）— 简化

**Won't Have (M2):**
11. ❌ Context compaction framework（M3）
12. ❌ Long-term memory（M3+）
13. ❌ Multi-agent session（M3+）
14. ❌ Session locking（M3）
15. ❌ Event sourcing（M3+）
16. ❌ Branch isolation（M3+）
17. ❌ Checkpoint/Resume（M3+）

---

## 2. M1 Baseline 架构回顾

### 2.1 当前调用链

```
User
  ↓
AgentRuntime.execute(AgentDefinition, AgentRequest)
  ↓
DefaultAgentRuntime
  ↓
AgentExecutionEngine.execute(AgentDefinition, AgentRequest)
  ↓
SpringAiToolCallingEngine
  ↓ builds
ChatClient.prompt()
    .system(systemInstruction)
    .user(request.userMessage())
    .tools(wrappedTools)
    .call()
  ↓
Spring AI Tool Calling Loop
  ↓
EvidenceCapturingToolCallback
  ↓
AgentResult(content, evidences)
```

### 2.2 M1 关键约束

**arctra-core（纯 Java）：**
```java
cn.bitcss.arctra.agent/
  - AgentDefinition(name, description)
  - AgentRequest(userMessage)  // 明确注释：stateless, single-turn
  - AgentResult(content, evidences)

cn.bitcss.arctra.evidence/
  - Evidence(source, content)

cn.bitcss.arctra.runtime/
  - AgentRuntime (package-private interface)
  - DefaultAgentRuntime (package-private)
  - AgentExecutionEngine (public extension contract)
```

**arctra-runtime-react（Spring AI 集成）：**
```java
cn.bitcss.arctra.runtime.react/
  - SpringAiToolCallingEngine implements AgentExecutionEngine
  - EvidenceCapturingToolCallback (package-private)
```

**关键发现：**
- AgentRequest 注释明确："stateless, single-turn"
- AgentRuntime 是 package-private（不是 public contract）
- AgentExecutionEngine 是 public contract（不应轻易修改）

---

## 3. 架构演进手册关键原则（适用于 M2）

### 3.1 核心判断方法

**对每个候选抽象必须回答：**
1. 现在谁在用？
2. 不加做不了什么？
3. 是否有两个实现？
4. 是否表达新的 Arctra 领域语义？
5. 还是只是 Spring AI 的重命名？

### 3.2 四层判断法

```
第一层：Spring AI 是否已解决？
    ↓
第二层：Spring AI Alibaba 是否已提供？
    ↓
第三层：是否属于通用 Framework 能力？
    ↓
第四层：是否属于 Arctra 真正应该建设的平台能力？
```

### 3.3 Arctra 应该拥有的语义

**让 Spring AI 处理：**
- ChatModel 抽象
- Message 模型
- ToolCallback 接口
- Tool Calling Loop 自动化
- Conversation history storage

**Arctra 增加：**
- **Session identity semantics**（会话标识语义）
- Agent 生命周期语义
- Evidence 捕获和审计跟踪
- Tool 调用治理（未来）
- Skill / Experience / Playbook（未来）

---

## 4. 竞品研究关键发现

### 4.1 AgentScope 核心洞察

**状态外置是正确设计：**
```
Agent (stateless template)
    ↓
AgentStateStore (external state)
    ↓ keyed by (userId, sessionId)
Per-session state
```

**Session 定义：**
> "a collection of StateModule" — 状态管理边界，不是业务概念

**Memory 分层：**
- Short-term: conversation history（bounded, per-session）
- Long-term: 跨 session 知识（accumulated, extracted facts）

**关键原则：**
- Session ≠ Conversation ≠ Memory
- Agent instance 不持有 session state
- 自动 save/load（用户无感知）

### 4.2 Spring AI Session API（2026-04）

**提供能力：**
- Turn safety（轮次安全）
- Event sourcing（事件溯源）
- Branch isolation（分支隔离）
- SessionMemoryAdvisor

**M2 判断：**
- ⚠️ 发布时间太新（2026-04），稳定性未知
- ⚠️ 功能过重（event sourcing, branch isolation M2 不需要）
- ✅ 设计思想值得学习（turn safety, advisor integration）
- **决策：M2 暂不使用，继续观察成熟度，M3 可能迁移**

### 4.3 Spring AI ChatMemory（当前可用）

**从依赖看：**
```
org.springframework.ai:spring-ai-autoconfigure-model-chat-memory:jar:2.0.0:test
```

**推测 API（需验证）：**
```java
ChatMemory memory = new InMemoryChatMemory();
// memory.add(conversationId, messages)
// memory.get(conversationId, lastN)

MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
ChatClient client = ChatClient.builder(chatModel)
    .defaultAdvisors(advisor)
    .build();

// conversationId 如何传递？
```

**需要 PoC 验证（M2-T1）：**
1. ChatMemory 实际 API surface
2. MessageChatMemoryAdvisor 是否存在
3. conversationId 如何传递（context? parameter?）
4. Tool call messages 是否自动进入 memory
5. 与 ToolCallingAdvisor 如何组合

---

## 5. Session 语义定义

### 5.1 Session 是什么

**Arctra 定义：**
> Session 是 **conversation continuity 的标识边界**，而不是独立的领域对象。

**Session 不是：**
- ❌ 独立的 domain entity（不需要 lifecycle, version, approval）
- ❌ Conversation（conversation 是 session 的内容）
- ❌ Memory（memory 是 conversation 的存储机制）
- ❌ Agent instance（agent 是无状态模板）

**Session 是：**
- ✅ Execution context identity（执行上下文标识）
- ✅ Conversation isolation boundary（对话隔离边界）
- ✅ State continuity marker（状态连续性标记）

**关键洞察：**
> Session 是一个 **标识符** (String sessionId)，不是一个复杂对象。  
> 它解决的是"如何区分不同对话"，而不是"如何管理对话"。

### 5.2 Conversation 是什么

**Arctra 定义：**
> Conversation 是 session 中的 **message history**，不是顶层抽象。

**体现为：**
```
Session "incident-123"
    ↓ contains
List<Message> (Spring AI Message)
    - UserMessage
    - AssistantMessage
    - ToolCallMessage
    - ToolResponseMessage
```

**不创建：**
- ❌ Conversation class
- ❌ ConversationHistory class
- ❌ ArctraMessage wrapper

### 5.3 Memory 是什么

**Arctra M2 定义：**
> Memory 是 conversation history 的 **storage mechanism**（存储机制）

**M2 只做 Short-term Memory：**
- Spring AI ChatMemory 负责存储
- 不做 long-term memory（M3+）
- 不做 knowledge extraction

### 5.4 Session vs Conversation vs Memory 关系

```
┌─────────────────────────────────────┐
│ Session ID: "incident-123"          │ ← 标识符
│                                     │
│   Conversation (content):           │ ← 内容
│   ├─ UserMessage                    │
│   ├─ AssistantMessage               │
│   ├─ ToolCallMessage                │
│   └─ ToolResponseMessage            │
│                                     │
│   Memory (storage):                 │ ← 机制
│   └─ Spring AI ChatMemory           │
└─────────────────────────────────────┘
```

---

## 6. Session Identity 传播设计

### 6.1 核心问题

**Session ID 如何从用户 API 传递到 Spring AI ChatMemory？**

```
User API (future)
  ↓ sessionId
Runtime
  ↓ sessionId?
Engine
  ↓ sessionId?
ChatClient + Advisor
  ↓ conversationId
ChatMemory
```

### 6.2 候选方案分析

#### 方案 A：sessionId in AgentRequest

```java
// 修改 AgentRequest
record AgentRequest(
    String userMessage,
    String sessionId  // 新增
)
```

**优点：**
- 简单直接
- sessionId 与 request 一起传递

**缺点：**
- ❌ sessionId 不是 "request" 的一部分（语义不正确）
- ❌ AgentRequest 注释明确："stateless, single-turn"
- ❌ session 是 execution context，不是 user input
- ❌ 未来可能有其他 execution context（userId, traceId）
- ❌ 会导致 AgentRequest 成为"垃圾桶对象"

**评估：** ❌ **不推荐**（语义错误）

---

#### 方案 B：修改 AgentExecutionEngine contract

```java
// 修改 Engine contract
interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId  // 新增
    );
}
```

**优点：**
- sessionId 独立于 request
- 语义更清晰

**缺点：**
- ❌ AgentExecutionEngine 是 public contract（破坏 M1 API）
- ❌ 未来会有更多 context（userId, traceId, tenant）
- ❌ 每增加一个 context 就修改一次 contract
- ❌ sessionId 不是所有 Engine 都需要（有些 Engine 可能无状态）

**评估：** ❌ **不推荐**（Contract 不稳定）

---

#### 方案 C：ExecutionContext 对象

```java
// 新增 ExecutionContext
record ExecutionContext(
    String sessionId,
    String userId,    // 未来
    String traceId    // 未来
)

interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        ExecutionContext context  // 新增
    );
}
```

**优点：**
- 可扩展（未来加字段不破坏 contract）
- 语义清晰（execution-level context）

**缺点：**
- ⚠️ 仍然修改 public Engine contract
- ⚠️ ExecutionContext M2 只有一个字段（过早抽象？）
- ⚠️ 不是所有 Engine 都需要 context

**评估：** ⚠️ **可行但有顾虑**

---

#### 方案 D：Runtime-level session binding（推荐）

**核心思想：**
> SessionId 不穿过 Engine contract，由 Runtime 层管理

**架构：**
```
User API (future)
  ↓ sessionId
SessionAwareRuntime (new, package-private)
  ↓ loads conversation history
  ↓ combines with request
AgentExecutionEngine (unchanged)
  ↓
SpringAiToolCallingEngine (modified internally)
  ↓
ChatClient + ChatMemoryAdvisor
```

**具体设计：**

**Step 1: 不修改 Engine contract**
```java
// arctra-core
interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    );
    // 不变！
}
```

**Step 2: SpringAiToolCallingEngine 内部管理 sessionId**
```java
// arctra-runtime-react
public class SpringAiToolCallingEngine implements AgentExecutionEngine {
    private final ChatModel chatModel;
    private final List<ToolCallback> tools;
    private final ChatMemory chatMemory;  // 新增

    // 方式 1: Engine-level session binding
    public void setSessionId(String sessionId) {
        // Store in ThreadLocal or instance field
    }

    // 方式 2: Per-execution session parameter（不修改 contract）
    public AgentResult executeWithSession(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId
    ) {
        // Build ChatClient with session-aware advisor
    }
}
```

**Step 3: Runtime 层协调**
```java
// arctra-runtime-react (or new module)
class SessionAwareRuntimeAdapter {
    private final AgentExecutionEngine engine;

    public AgentResult executeWithSession(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId
    ) {
        if (engine instanceof SpringAiToolCallingEngine springEngine) {
            return springEngine.executeWithSession(def, req, sessionId);
        } else {
            // Fallback: engine doesn't support session
            return engine.execute(def, req);
        }
    }
}
```

**优点：**
- ✅ AgentExecutionEngine contract 不变（向后兼容）
- ✅ arctra-core 保持 pure Java
- ✅ Session 是 opt-in capability（Engine 可以不支持）
- ✅ 不创建过早抽象（ExecutionContext）
- ✅ Spring AI integration 细节封装在 runtime-react

**缺点：**
- ⚠️ 不是最"优雅"的设计（有 instanceof 判断）
- ⚠️ 需要文档说明 session 是 opt-in

**评估：** ✅ **推荐**（最小破坏，最大灵活性）

---

### 6.3 推荐方案细化

**M2 采用方案 D 的变体：**

**核心原则：**
1. AgentExecutionEngine contract 不变
2. Session 支持通过 SpringAiToolCallingEngine 特定方法暴露
3. 不在 arctra-core 创建 ExecutionContext（M2 只有一个字段）
4. 未来如果多个 Engine 需要 session，再提取 common contract

**实现：**

```java
// arctra-runtime-react
public class SpringAiToolCallingEngine implements AgentExecutionEngine {

    // M1 method (保持不变)
    @Override
    public AgentResult execute(
        AgentDefinition definition,
        AgentRequest request
    ) {
        return executeWithSession(definition, request, null);
    }

    // M2 new method (Engine-specific)
    public AgentResult executeWithSession(
        AgentDefinition definition,
        AgentRequest request,
        String sessionId  // nullable
    ) {
        // 1. Build ChatClient with appropriate advisors
        //    - If sessionId != null: add MessageChatMemoryAdvisor
        //    - Always: add ToolCallingAdvisor
        
        // 2. Execute
        // 3. Return result
    }
}
```

**用户代码（M2）：**
```java
var engine = new SpringAiToolCallingEngine(chatModel, tools, chatMemory);

// Single-turn (M1 compatibility)
AgentResult result1 = engine.execute(definition, request);

// Multi-turn (M2)
AgentResult result2 = engine.executeWithSession(definition, request, "session-123");
```

**未来 Agent API（M3+）：**
```java
// 这只是设计探针，不是 M2 实现
agent
    .session("incident-123")
    .user("...")
    .call();

// 内部调用：
// engine.executeWithSession(def, req, "incident-123")
```

---

## 7. Spring AI ChatMemory 集成策略

### 7.1 需要验证的 API（M2-T1）

创建 PoC 验证：
1. `ChatMemory` / `InMemoryChatMemory` 是否存在
2. `MessageChatMemoryAdvisor` 是否存在
3. conversationId 如何传递
4. Tool call messages 是否自动进入 memory
5. 与 ToolCallingAdvisor 组合是否正常

### 7.2 推测的集成方式

```java
// arctra-runtime-react
public AgentResult executeWithSession(
    AgentDefinition definition,
    AgentRequest request,
    String sessionId
) {
    // 1. Create/get ChatMemory
    ChatMemory chatMemory = getChatMemory(); // shared or per-engine
    
    // 2. Build ChatClient with advisors
    List<Advisor> advisors = new ArrayList<>();
    
    if (sessionId != null) {
        advisors.add(new MessageChatMemoryAdvisor(chatMemory));
    }
    
    // Tool calling advisor (always)
    // advisors.add(new ToolCallingAdvisor(...)); // 可能自动添加
    
    ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(advisors.toArray(new Advisor[0]))
        .build();
    
    // 3. Execute with sessionId in context
    var promptSpec = chatClient.prompt()
        .system(buildSystemInstruction(definition))
        .user(request.userMessage())
        .tools(wrappedTools);
    
    if (sessionId != null) {
        promptSpec = promptSpec.advisorContext(
            "conversationId", sessionId  // 猜测
        );
    }
    
    String content = promptSpec.call().content();
    
    return new AgentResult(content, evidences);
}
```

### 7.3 关键未知项

**Must verify in M2-T1:**
1. `MessageChatMemoryAdvisor` class name 和 package
2. `conversationId` 传递机制（context key? parameter?）
3. `ToolCallingAdvisor` 是否与 memory advisor 冲突
4. Tool call/result messages 是否自动保存
5. History window 配置方式

---

## 8. Module & Package 边界

### 8.1 M2 不创建新 module

**决策：** M2 不创建 `arctra-session` module

**理由：**
- M2 session 支持是 runtime capability，不是独立 domain
- 所有代码在现有 module 实现
- 避免过早模块化

### 8.2 Package 设计

**arctra-core（无变化）：**
```
cn.bitcss.arctra.agent/
  - AgentDefinition (unchanged)
  - AgentRequest (unchanged)
  - AgentResult (unchanged)

cn.bitcss.arctra.evidence/
  - Evidence (unchanged)

cn.bitcss.arctra.runtime/
  - AgentRuntime (unchanged)
  - DefaultAgentRuntime (unchanged)
  - AgentExecutionEngine (unchanged)
```

**arctra-runtime-react（扩展）：**
```
cn.bitcss.arctra.runtime.react/
  - SpringAiToolCallingEngine (modified)
    + executeWithSession() method
  - EvidenceCapturingToolCallback (unchanged)
```

**关键：** arctra-core 完全不变，session 支持封装在 runtime-react

---

## 9. 最小领域类型

### 9.1 M2 创建的类型

**答案：** 无新 public 类型

**理由：**
- sessionId 是 String（不需要 SessionId wrapper）
- conversation history 是 List<Message>（Spring AI）
- ChatMemory 是 Spring AI 类型

### 9.2 M2 不创建的类型

明确 **不创建：**

❌ `Session` class  
❌ `SessionRuntime` interface  
❌ `SessionRepository` interface  
❌ `SessionState` class  
❌ `Conversation` class  
❌ `ConversationHistory` class  
❌ `Memory` interface  
❌ `ArctraMessage` wrapper  
❌ `ExecutionContext` record（M2 只有一个字段，过早）  
❌ `SessionId` value object（String 足够）

**理由：** 遵循 EVOLUTION-GUIDE "现在谁在用？" 原则

---

## 10. 执行流程

### 10.1 Single-turn (M1 compatibility)

```
User
  ↓
engine.execute(definition, request)
  ↓
SpringAiToolCallingEngine
  ↓
ChatClient (no memory advisor)
  ↓
Tool Calling Loop
  ↓
AgentResult
```

**不变！**

### 10.2 Multi-turn (M2 new)

```
User
  ↓
engine.executeWithSession(definition, request, "session-123")
  ↓
SpringAiToolCallingEngine
  ├─ Create ChatClient with MessageChatMemoryAdvisor
  ├─ Pass sessionId via advisor context
  └─ Execute
      ↓
  MessageChatMemoryAdvisor
      ├─ Load history from ChatMemory["session-123"]
      ├─ Prepend to prompt
      └─ After execution: save new messages
          ↓
  Tool Calling Loop (with history context)
      ├─ Model sees: history + current user message
      ├─ Tool calls
      └─ Generates response
          ↓
  ChatMemory automatically updated:
      ├─ UserMessage
      ├─ AssistantMessage
      ├─ ToolCallMessage
      └─ ToolResponseMessage
          ↓
AgentResult
```

### 10.3 Multi-turn Sequence

```
Turn 1:
  User: "生产环境 500 错误，请分析"
    ↓
  ChatMemory["incident-123"]: []
    ↓
  Model receives: UserMessage
    ↓
  Tool calls: queryLogs, getDeployment
    ↓
  Response: "初步分析：user_status 字段缺失"
    ↓
  ChatMemory["incident-123"]: [
      UserMessage("生产环境..."),
      AssistantMessage("让我查看..."),
      ToolCallMessage("queryLogs"),
      ToolResponseMessage("..."),
      ToolCallMessage("getDeployment"),
      ToolResponseMessage("..."),
      AssistantMessage("初步分析...")
  ]

Turn 2:
  User: "最可能的原因是什么？"
    ↓
  ChatMemory["incident-123"]: [7 messages from Turn 1]
    ↓
  Model receives: history + UserMessage("最可能...")
    ↓
  Model understands context
    ↓
  Response: "最可能是部署时未执行数据库迁移"
    ↓
  ChatMemory updated with Turn 2 messages
```

---

## 11. Session 隔离策略

### 11.1 隔离机制

**Spring AI ChatMemory 负责：**
```java
ChatMemory memory = new InMemoryChatMemory();

// Different sessions
memory.add("session-123", messages1);
memory.add("session-456", messages2);

// Retrieval
List<Message> history1 = memory.get("session-123", 100);
List<Message> history2 = memory.get("session-456", 100);
// history1 != history2
```

**Arctra 负责：**
- 确保 sessionId 正确传递
- 文档说明 session isolation 语义

### 11.2 并发处理

**M2 限制：**
- ⚠️ 不支持同一 session 并发请求
- ⚠️ 必须在文档中说明

**理由：**
- Spring AI InMemoryChatMemory 并发安全性未知
- Session lock 需要分布式协调（Redis）
- M2 focus 是功能验证，不是 production 并发

**M3 考虑：**
- Session-level lock（Redis）
- 或：last-write-wins
- 或：optimistic locking

---

## 12. Failure 语义

### 12.1 Session 不存在

**行为：**
- Spring AI ChatMemory 自动创建
- 用户无需预先创建 session

### 12.2 ChatMemory 故障

**行为：**
- M2: 抛出异常（fail-fast）
- M3: 可能降级为 stateless execution

### 12.3 History 过大

**M2 行为：**
- 依赖 Spring AI ChatMemory 默认行为
- 可能：sliding window（最近 N 条）
- 可能：抛出异常

**M3 计划：**
- Compaction framework
- Token-based window
- Summarization

---

## 13. Testing 策略

### 13.1 测试层次

**Unit Tests:**
- SpringAiToolCallingEngine.executeWithSession()
- Session ID 传递逻辑
- ChatMemory integration

**Integration Tests:**
- Multi-turn conversation with FakeChatModel
- Session isolation verification
- Tool call/result in history

**E2E Tests:**
- Real multi-turn incident investigation
- Evidence + conversation history
- Cross-session isolation

### 13.2 关键测试场景

```java
@Test
void should_maintain_conversation_continuity() {
    // Turn 1
    var result1 = engine.executeWithSession(def, req1, "session-1");
    
    // Turn 2
    var result2 = engine.executeWithSession(def, req2, "session-1");
    
    // Assert: Turn 2 response shows understanding of Turn 1 context
}

@Test
void should_isolate_different_sessions() {
    // Session A
    engine.executeWithSession(def, reqA, "session-A");
    
    // Session B
    var resultB = engine.executeWithSession(def, reqB, "session-B");
    
    // Assert: resultB does not reference session-A context
}

@Test
void should_include_tool_calls_in_history() {
    // Turn 1 with tool calls
    engine.executeWithSession(def, req1, "session-1");
    
    // Verify: ChatMemory contains ToolCallMessage + ToolResponseMessage
}
```

---

## 14. Migration & Evolution Path

### 14.1 从 M1 迁移到 M2

**Single-turn 代码（M1）：**
```java
var engine = new SpringAiToolCallingEngine(chatModel, tools);
AgentResult result = engine.execute(definition, request);
```

**继续工作！** 不破坏。

**Multi-turn 代码（M2）：**
```java
var engine = new SpringAiToolCallingEngine(chatModel, tools, chatMemory);
AgentResult result = engine.executeWithSession(definition, request, sessionId);
```

**向后兼容。**

### 14.2 未来演进路径

**M3: 迁移到 Spring AI Session API（如果成熟）**
```java
// 替换 MessageChatMemoryAdvisor
SessionMemoryAdvisor advisor = new SessionMemoryAdvisor(
    sessionRepository,
    new TurnCountTrigger(10),
    new SlidingWindowStrategy(20)
);
```

**改动：** 只在 SpringAiToolCallingEngine 内部  
**外部 API：** 不变

**M3+: 提取 ExecutionContext（如果需要）**
```java
// 当多个字段出现时
interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        ExecutionContext context  // sessionId, userId, traceId, etc.
    );
}
```

---

## 15. M2 Task Breakdown

### M2-T1: Spring AI ChatMemory PoC (2d)

**Goal:**  
验证 Spring AI 2.0.0 ChatMemory 实际 API 和行为

**Why now:**  
M1 已经踩过 Spring AI API 猜测的坑，M2 必须先验证再设计

**Activities:**
1. 创建独立 PoC 项目
2. 验证 ChatMemory / InMemoryChatMemory API
3. 验证 MessageChatMemoryAdvisor 是否存在
4. 验证 conversationId 传递机制
5. 验证与 ToolCallingAdvisor 组合
6. 验证 tool call messages 是否进入 history
7. 验证 history window 配置
8. 文档化实际 API 和行为

**Acceptance Criteria:**
- ✅ PoC 代码编译通过
- ✅ Multi-turn conversation 工作
- ✅ Tool calls 正确进入 history
- ✅ Session isolation 验证
- ✅ API 文档化（class names, methods, parameters）

**Non-Goals:**
- ❌ 不集成到 Arctra
- ❌ 不设计 Arctra abstraction
- ❌ 不修改任何 arctra-core 代码

**Output:**
- `docs/research/M2-T1-SPRING-AI-CHATMEMORY-POC.md`
- PoC 代码（可能在单独分支或 temp 目录）

---

### M2-T2: SpringAiToolCallingEngine Session Support (3d)

**Goal:**  
在 SpringAiToolCallingEngine 中实现 executeWithSession() 方法

**Dependencies:** M2-T1

**Activities:**
1. 修改 SpringAiToolCallingEngine constructor（增加 ChatMemory）
2. 实现 executeWithSession() method
3. 集成 MessageChatMemoryAdvisor
4. 传递 sessionId via advisor context
5. 保持 execute() 方法向后兼容
6. Unit tests
7. Integration tests with FakeChatModel

**Acceptance Criteria:**
- ✅ executeWithSession() 编译通过
- ✅ execute() 保持不变（M1 compatibility）
- ✅ ChatMemory integration 工作
- ✅ SessionId 正确传递
- ✅ Tool calls 进入 history
- ✅ 16+ tests pass

**Non-Goals:**
- ❌ 不修改 AgentExecutionEngine contract
- ❌ 不创建 Session class
- ❌ 不创建 SessionRuntime
- ❌ 不实现 persistent storage（使用 InMemoryChatMemory）

**Modified Files:**
- `arctra-runtime-react/src/main/java/.../SpringAiToolCallingEngine.java`
- `arctra-runtime-react/src/test/java/.../SpringAiToolCallingEngineSessionTest.java`

---

### M2-T3: Multi-Turn E2E Scenario Test (2d)

**Goal:**  
验证完整 multi-turn incident investigation scenario

**Dependencies:** M2-T2

**Activities:**
1. 扩展 examples/incident-investigator
2. 创建 multi-turn test scenario
3. 验证 conversation continuity
4. 验证 session isolation
5. 验证 Evidence 机制继续工作
6. 文档化使用方式

**Acceptance Criteria:**
- ✅ Multi-turn test 通过
- ✅ Turn 2 理解 Turn 1 上下文
- ✅ Different sessions 隔离
- ✅ Evidence 正确捕获
- ✅ Tool calls 在 history 中

**Test Scenario:**
```java
// Turn 1
var result1 = engine.executeWithSession(
    incident Agent,
    new AgentRequest("生产环境 16:20 开始出现大量 500，请分析"),
    "incident-123"
);
assertThat(result1.content()).contains("SQLException", "user_status");
assertThat(result1.evidences()).hasSize(2); // queryLogs + getDeployment

// Turn 2
var result2 = engine.executeWithSession(
    incidentAgent,
    new AgentRequest("那最可能的原因是什么？"),
    "incident-123"
);
assertThat(result2.content()).contains("数据库迁移");

// Turn 3 (different session)
var result3 = engine.executeWithSession(
    incidentAgent,
    new AgentRequest("刚才的问题是什么？"),
    "incident-456"
);
assertThat(result3.content()).doesNotContain("500", "SQLException");
```

**Output:**
- `examples/incident-investigator/.../MultiTurnScenarioTest.java`
- Updated README with multi-turn usage example

---

### M2-T4: Documentation & Limitations (1d)

**Goal:**  
完整文档化 M2 能力和限制

**Dependencies:** M2-T3

**Activities:**
1. 更新 CURRENT-STATE.md
2. 更新 TASKS.md
3. 创建 M2 用户指南
4. 明确文档限制（并发、compaction）
5. 更新 examples README

**Acceptance Criteria:**
- ✅ 文档明确 M2 能力
- ✅ 文档明确 M2 限制
- ✅ 用户能根据文档使用 multi-turn
- ✅ 明确说明 session 不支持并发

**Output:**
- `docs/user-guide/M2-MULTI-TURN-CONVERSATION.md`
- Updated `CURRENT-STATE.md`
- Updated `TASKS.md`
- Updated `examples/incident-investigator/README.md`

---

## 16. M2 Acceptance Criteria（阶段级别）

**功能验收：**
1. ✅ 同一 session 多次调用，后续理解前面上下文
2. ✅ 不同 session 完全隔离
3. ✅ Tool call/result 进入 conversation history
4. ✅ Evidence 机制继续工作
5. ✅ M1 单轮 API 继续工作（不破坏）

**架构验收：**
6. ✅ arctra-core 保持 pure Java（无 Spring AI 依赖）
7. ✅ AgentExecutionEngine contract 未修改
8. ✅ 无过早抽象（Session class, SessionRuntime, ExecutionContext）
9. ✅ ./mvnw clean verify 通过

**文档验收：**
10. ✅ M2 能力和限制明确文档化
11. ✅ 用户指南完整
12. ✅ 限制（并发、compaction）明确说明

---

## 17. M2 Non-Goals（明确不做）

### 17.1 功能层面

❌ **Context compaction framework**  
- 理由：M2 focus 是验证 session 机制，compaction 是优化
- 计划：M3

❌ **Long-term memory**  
- 理由：独立复杂能力，需要知识提取
- 计划：M3+

❌ **Multi-agent session**  
- 理由：需要 orchestration（Graph）
- 计划：M3+

❌ **Session lock / concurrency control**  
- 理由：需要分布式协调（Redis）
- 计划：M3

❌ **Event sourcing**  
- 理由：M2 不需要完整审计
- 计划：如果需要，M3+ 迁移到 Spring AI Session API

❌ **Branch isolation**  
- 理由：Multi-agent 能力
- 计划：M3+

❌ **Checkpoint / Resume**  
- 理由：Long-running workflow 能力
- 计划：M3+

### 17.2 架构层面

❌ **创建 Session class**  
- 理由：sessionId (String) 足够

❌ **创建 SessionRuntime**  
- 理由：Engine 特定方法足够

❌ **创建 SessionRepository**  
- 理由：Spring AI ChatMemory 负责存储

❌ **创建 ExecutionContext**  
- 理由：M2 只有一个字段（过早抽象）

❌ **创建 ArctraMessage**  
- 理由：Spring AI Message 足够

❌ **修改 AgentExecutionEngine contract**  
- 理由：向后兼容优先

❌ **创建 arctra-session module**  
- 理由：过早模块化

### 17.3 实现层面

❌ **JDBC / Redis persistence**  
- 理由：M2 验证功能，InMemoryChatMemory 足够
- 计划：M3 可选

❌ **Spring AI Session API integration**  
- 理由：太新（2026-04），稳定性未知
- 计划：M3 评估迁移

❌ **Custom ChatMemory implementation**  
- 理由：复用 Spring AI

---

## 18. ADR 决策

### 18.1 需要 ADR 的决策

**ADR-003: M2 Session Identity Propagation**

**Context:**  
M2 需要支持 multi-turn conversation，sessionId 需要从用户 API 传递到 Spring AI ChatMemory

**Decision:**  
采用 "Runtime-level session binding" 方案：
- AgentExecutionEngine contract 不变
- SpringAiToolCallingEngine 增加 executeWithSession() method（Engine-specific）
- sessionId 通过 Spring AI advisor context 传递
- arctra-core 保持 framework-neutral

**Consequences:**
- 向后兼容（M1 API 不破坏）
- Session 是 opt-in capability
- 不需要 ExecutionContext 过早抽象
- 未来可演进（多个 Engine 需要时再提取 common contract）

**Alternatives Considered:**
- sessionId in AgentRequest（语义错误）
- 修改 Engine contract（破坏 API）
- ExecutionContext（过早抽象）

---

### 18.2 不需要 ADR 的决策

**使用 Spring AI ChatMemory:**  
- 技术选型，不是架构决策
- 符合 EVOLUTION-GUIDE "复用成熟能力"

**不创建 Session class:**  
- 遵循 "现在谁在用？" 原则
- sessionId (String) 足够

**M2 不做 compaction:**  
- Scope 决策，不是架构决策

---

## 19. Risks & Open Questions

### 19.1 High Risk

**Risk 1: Spring AI ChatMemory API 与预期不符**

**Mitigation:**  
M2-T1 优先验证 API（2天 PoC）

**Contingency:**  
如果 ChatMemory 不可用：
- 自建 InMemoryConversationStore（简化版）
- M3 迁移到 Spring AI Session API

---

**Risk 2: MessageChatMemoryAdvisor 与 ToolCallingAdvisor 冲突**

**Mitigation:**  
M2-T1 验证 advisor 组合

**Contingency:**  
如果冲突：
- 不使用 MessageChatMemoryAdvisor
- 手动管理 conversation history injection

---

### 19.2 Medium Risk

**Risk 3: Tool call messages 不自动进入 history**

**Mitigation:**  
M2-T1 验证

**Contingency:**  
手动 append tool messages 到 ChatMemory

---

**Risk 4: 并发访问同一 session 导致 history 错乱**

**Mitigation:**  
M2 文档明确说明不支持并发

**Contingency:**  
M3 实现 session lock

---

### 19.3 Open Questions

**Q1: Spring AI ChatMemory 的 history window 默认大小？**

**Answer in M2-T1**

---

**Q2: ChatMemory 是否支持持久化（JDBC/Redis）？**

**Answer:**  
M2 使用 InMemoryChatMemory  
M3 评估 persistent store

---

**Q3: 未来 Agent API 如何暴露 session？**

**Answer:**  
M2 不实现 Agent API  
M3 设计时考虑

---

## 20. 推荐实施顺序

### 20.1 实施顺序

```
M2-T1 (PoC)
    ↓ API 验证
M2-T2 (Engine)
    ↓ 实现
M2-T3 (E2E)
    ↓ 验证
M2-T4 (Docs)
```

### 20.2 每个 Task 审批点

- M2-T1 完成 → 审批 PoC 结果
- M2-T2 完成 → 审批实现
- M2-T3 完成 → 审批 E2E
- M2-T4 完成 → M2 Closeout

---

## 21. 最终架构总结

### 21.1 M2 After 架构图

```
User
  ↓
SpringAiToolCallingEngine.executeWithSession(def, req, sessionId)
  ↓
ChatClient
  ├─ MessageChatMemoryAdvisor(conversationId=sessionId)
  │   ├─ Load history from ChatMemory
  │   └─ Prepend to prompt
  └─ ToolCallingAdvisor (automatic)
      ↓
Spring AI Tool Calling Loop
      ↓
EvidenceCapturingToolCallback
      ↓
AgentResult(content, evidences)
      ↓
ChatMemory automatically updated with new messages
```

### 21.2 M2 核心设计原则

**1. Arctra owns session semantics, Spring AI provides storage**

**2. Session is an identifier, not a complex object**

**3. No premature abstraction (Session class, SessionRuntime, ExecutionContext)**

**4. Backward compatible (M1 API unchanged)**

**5. Framework-neutral core (arctra-core remains pure Java)**

---

## 22. 八个关键问题的最终答案

### Q1: M2 中 "Session" 的最小定义到底是什么？

**A:** Session 是 **conversation continuity 的标识边界** (String sessionId)

---

### Q2: M2 是否真的需要一个 Session class？

**A:** ❌ **不需要**  
- sessionId (String) 足够
- 不需要 lifecycle, version, approval
- 遵循 "现在谁在用？" 原则

---

### Q3: M2 是否真的需要 SessionRuntime？

**A:** ❌ **不需要**  
- SpringAiToolCallingEngine.executeWithSession() 足够
- 不创建独立 Runtime abstraction

---

### Q4: M2 是否真的需要 SessionRepository？

**A:** ❌ **不需要**  
- Spring AI ChatMemory 负责存储
- 不创建 Arctra abstraction

---

### Q5: AgentExecutionEngine contract 是否需要修改？

**A:** ❌ **不修改**  
- 向后兼容优先
- Session 通过 Engine-specific method 暴露
- 未来多个 Engine 需要时再提取 common contract

---

### Q6: sessionId 应该存在于哪一层？

**A:** **Runtime-react 层（Engine-specific）**  
- 不在 arctra-core
- 不在 AgentRequest
- 不在 Engine contract
- SpringAiToolCallingEngine.executeWithSession() 的参数

---

### Q7: Spring AI ChatMemory 在 Arctra 架构中属于什么角色？

**A:** **Implementation capability（实现能力）**  
- 负责 conversation history storage
- Arctra 不包装它（不创建 ArctraMemory）
- 直接使用，视为 infrastructure

---

### Q8: 如果未来从 Spring AI ChatMemory / Session API 切换实现，当前 M2 设计哪些部分保持不变？

**A:** **不变的部分：**
- ✅ AgentExecutionEngine contract（不依赖 ChatMemory）
- ✅ arctra-core domain models（纯 Java）
- ✅ Evidence 机制
- ✅ M1 单轮 API

**需要改变的部分：**
- ⚠️ SpringAiToolCallingEngine 内部实现（替换 advisor）
- ⚠️ 用户代码（如果使用 executeWithSession）

---

## 23. 结论与批准请求

### 23.1 M2 Phase Planning 完成

**本次规划输出：**
1. ✅ M2 核心目标明确
2. ✅ Session 语义定义清晰
3. ✅ Session identity 传播方案确定
4. ✅ Spring AI ChatMemory 集成策略明确
5. ✅ 架构边界清晰（不创建过早抽象）
6. ✅ Task breakdown 完整
7. ✅ Acceptance criteria 明确
8. ✅ Non-goals 明确
9. ✅ ADR 决策明确
10. ✅ Risks 识别

### 23.2 关键设计决策

**1. Session 是标识符，不是对象**
- sessionId: String
- 不创建 Session class

**2. Engine contract 不变**
- 向后兼容
- Session 通过 Engine-specific method

**3. arctra-core 保持纯 Java**
- 无 Spring AI 依赖
- 无新 public 类型

**4. 复用 Spring AI ChatMemory**
- 不创建 Arctra abstraction
- 直接使用 infrastructure

**5. M2-T1 优先验证 API**
- 避免 M1 的 API 猜测问题
- 2 天 PoC

### 23.3 请求批准

**请审批：**
1. 整体 M2 架构方案
2. Session identity propagation 方案（方案 D）
3. 不创建 Session/SessionRuntime/SessionRepository
4. M2 Task breakdown
5. ADR-003 决策

**请明确：**
1. M2-T1 PoC 是否应该先执行？
2. 是否有遗漏的架构风险？
3. 是否有其他需要考虑的方案？

**如果批准，下一步：**
开始 M2-T1 (Spring AI ChatMemory PoC)

---

**End of M2 Phase Planning**
