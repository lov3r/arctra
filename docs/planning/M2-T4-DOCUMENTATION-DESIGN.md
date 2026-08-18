# M2-T4: Documentation & Limitations - Task Design

**Date:** 2026-08-18  
**Status:** DESIGN  
**Dependencies:** M2-T1, M2-T2, M2-T3 COMPLETE

---

## 1. 任务目标

**目标：** 完善 M2 Multi-Turn Capability 的用户文档和限制说明

**为什么需要：**
- M2-T1/T2/T3 完成了实现和验证，但缺少用户使用指南
- Known limitations 分散在各个实现报告中，需要统一整理
- Example README 尚未更新，不反映 M2 能力
- 关键实现细节（ChatMemory.CONVERSATION_ID）需要在代码注释中明确

**不做什么：**
- ❌ 不创建完整的 User Manual（留给后续）
- ❌ 不写 API Reference（Javadoc 已足够）
- ❌ 不解决已知限制（留给 M3）
- ❌ 不创建 Tutorial（简单示例即可）

---

## 2. 当前状态分析

### 2.1 现有文档

**已有的实现文档：**
- ✅ M2-T1 PoC Report - Spring AI ChatMemory 验证
- ✅ M2-T2 Implementation Report - AgentExecutionContext 实现
- ✅ M2-T3 Root Cause Analysis Report - conversationId 问题修复
- ✅ M2-T2 Contract Gate V2 - 架构决策
- ✅ M2 Phase Planning - 原始规划（部分 SUPERSEDED）
- ✅ CURRENT-STATE.md - 项目当前状态

**缺失的文档：**
- ❌ 用户如何使用 multi-turn 的简单指南
- ❌ Known limitations 的统一说明
- ❌ Example README 未更新
- ❌ M2 能力的快速参考

### 2.2 已知限制（从现有文档提取）

**并发：**
- 同一 session 并发请求不支持
- Spring AI InMemoryChatMemory 并发安全性未验证
- 无 session lock 机制
- 计划：M3 实现 Redis-based locking

**Context Compaction：**
- MessageWindowChatMemory 使用简单 sliding window
- 无 turn-safety（可能切断 User/Assistant 配对）
- 超过 maxMessages 后简单丢弃旧消息
- 计划：M3 考虑 Spring AI Session API

**Tool Messages：**
- Tool call/response 是否进入 ChatMemory 未通过真实 API 完整验证
- 假设：MessageChatMemoryAdvisor.after() 保存所有 messages
- 需要真实 API 验证

**Persistence：**
- M2 仅支持 in-memory (MessageWindowChatMemory)
- 进程重启后 conversation history 丢失
- 计划：M3 可选 JDBC/Redis persistence

**Long-term Memory：**
- M2 不支持跨 session knowledge extraction
- 计划：M3+

**Critical Implementation Detail：**
- 必须使用 `ChatMemory.CONVERSATION_ID` 常量
- 使用字符串字面量 `"conversationId"` 会失败

### 2.3 Example 状态

**文件：** `examples/incident-investigator/README.md`

**当前内容：** 需要检查是否反映 M2 能力

---

## 3. M2-T4 交付物设计

### 3.1 核心文档

#### A. M2 Quick Start Guide

**位置：** `docs/guides/M2-MULTI-TURN-QUICK-START.md`

**目标：** 5分钟让用户了解如何使用 multi-turn

**内容大纲：**
1. **What is Multi-Turn?**
   - Same session → conversation continuity
   - Different session → isolation

2. **Minimal Example**
   ```java
   var engine = new SpringAiToolCallingEngine(
       chatModel,
       tools,
       MessageWindowChatMemory.builder().maxMessages(20).build()
   );
   
   // Turn 1
   var result1 = engine.execute(
       agentDef,
       new AgentRequest("分析 500 错误"),
       AgentExecutionContext.withSession("incident-123")
   );
   
   // Turn 2 - continues Turn 1
   var result2 = engine.execute(
       agentDef,
       new AgentRequest("那最可能的原因是什么？"),
       AgentExecutionContext.withSession("incident-123")
   );
   ```

3. **Key Concepts**
   - `AgentExecutionContext.withSession(id)` - 启用 session
   - `AgentExecutionContext.stateless()` - 不使用 session
   - `ChatMemory` - conversation storage
   - `sessionId` → `conversationId` mapping

4. **When to Use**
   - Multi-turn debugging/investigation
   - Conversational assistants
   - Stateful workflows

5. **When NOT to Use**
   - Simple one-shot queries
   - Parallel independent tasks
   - Stateless batch processing

6. **Next Steps**
   - See Example: `examples/incident-investigator`
   - Known Limitations: `docs/guides/M2-KNOWN-LIMITATIONS.md`

**字数：** ~500-800 words

---

#### B. M2 Known Limitations

**位置：** `docs/guides/M2-KNOWN-LIMITATIONS.md`

**目标：** 明确告知用户 M2 不支持什么

**内容大纲：**
1. **Concurrency**
   - ❌ Same session concurrent requests NOT supported
   - Why: No session locking
   - Workaround: Serialize requests per session
   - Future: M3 (Redis-based locking)

2. **Context Management**
   - ❌ No turn-safety in compaction
   - ❌ Simple sliding window (may break User/Assistant pairs)
   - Why: MessageWindowChatMemory limitation
   - Workaround: Use large enough maxMessages
   - Future: M3 (turn-aware compaction)

3. **Persistence**
   - ❌ In-memory only (process restart loses history)
   - Why: MessageWindowChatMemory default behavior
   - Workaround: None in M2
   - Future: M3 (JDBC/Redis ChatMemoryRepository)

4. **Tool Messages (Unverified)**
   - ⚠️ Tool call/response persistence not verified with real API
   - Assumption: MessageChatMemoryAdvisor saves all messages
   - Status: Pending real API verification
   - Impact: If tool messages not persisted, Turn 2 only sees final assistant response

5. **Long-term Memory**
   - ❌ No cross-session knowledge extraction
   - Future: M3+

6. **Critical Implementation Requirements**
   - ✅ MUST use `ChatMemory.CONVERSATION_ID` constant
   - ❌ DO NOT use string literal `"conversationId"`
   - Example:
     ```java
     // CORRECT ✅
     .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
     
     // WRONG ❌
     .advisors(a -> a.param("conversationId", sessionId))
     ```

**字数：** ~400-600 words

---

#### C. Example README Update

**位置：** `examples/incident-investigator/README.md`

**目标：** 展示 M2 multi-turn 能力

**需要添加：**
1. **Multi-Turn Scenario Section**
   - 描述如何运行 multi-turn test
   - 展示 session continuity 效果

2. **Test Overview**
   - IncidentAgentRealE2ETest - M1 single-turn
   - IncidentAgentMultiTurnE2ETest - M2 multi-turn (NEW)

3. **Usage Example**
   ```java
   // Turn 1: Initial question
   engine.execute(
       incidentAgent,
       new AgentRequest("生产环境 500 错误，请分析"),
       AgentExecutionContext.withSession("incident-123")
   );
   
   // Turn 2: Follow-up (no context repetition needed)
   engine.execute(
       incidentAgent,
       new AgentRequest("那最可能是什么原因？"),
       AgentExecutionContext.withSession("incident-123")
   );
   ```

**字数：** ~200-300 words addition

---

### 3.2 代码注释改进

#### D. SpringAiToolCallingEngine Javadoc

**位置：** `arctra-runtime-react/src/main/java/.../SpringAiToolCallingEngine.java`

**需要添加：**

1. **Class-level Javadoc 扩展**
   - 添加 M2 multi-turn 使用示例
   - 强调 ChatMemory.CONVERSATION_ID 要求
   - 链接到 M2 Quick Start Guide

2. **execute() method Javadoc**
   - 说明 context.sessionId() 的作用
   - 说明 null sessionId 的行为（stateless）

**示例：**
```java
/**
 * Agent execution engine based on Spring AI Tool Calling Loop.
 *
 * <p>Supports single-turn (M1) and multi-turn (M2) conversations via {@link AgentExecutionContext}.
 *
 * <h2>Single-Turn Usage (M1)</h2>
 * <pre>{@code
 * engine.execute(definition, request);  // Stateless
 * }</pre>
 *
 * <h2>Multi-Turn Usage (M2)</h2>
 * <pre>{@code
 * var context = AgentExecutionContext.withSession("session-123");
 * engine.execute(definition, request1, context);  // Turn 1
 * engine.execute(definition, request2, context);  // Turn 2 - sees Turn 1 context
 * }</pre>
 *
 * <p><strong>Critical:</strong> Do NOT subclass or modify conversationId passing logic.
 * Must use {@code ChatMemory.CONVERSATION_ID} constant, not string literal.
 *
 * @see AgentExecutionContext
 * @see <a href="../../../../../../../docs/guides/M2-MULTI-TURN-QUICK-START.md">M2 Quick Start Guide</a>
 */
```

---

#### E. AgentExecutionContext Javadoc

**位置：** `arctra-core/src/main/java/.../AgentExecutionContext.java`

**需要添加：**
- 使用示例
- Session semantics 说明
- null sessionId 含义

**示例：**
```java
/**
 * Agent execution context.
 *
 * <p>Represents execution-level environment for an agent invocation, independent of user input
 * ({@link AgentRequest}) and agent template ({@link AgentDefinition}).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Stateless execution (M1 behavior)
 * var context = AgentExecutionContext.stateless();
 * engine.execute(definition, request, context);
 *
 * // Session-based execution (M2 multi-turn)
 * var context = AgentExecutionContext.withSession("session-123");
 * engine.execute(definition, request, context);  // Turn 1
 * engine.execute(definition, followUp, context);  // Turn 2 - continues conversation
 * }</pre>
 *
 * <h2>Session Semantics</h2>
 * <ul>
 *   <li>Same sessionId → conversation continuity</li>
 *   <li>Different sessionId → conversation isolation</li>
 *   <li>null sessionId → stateless execution (no history)</li>
 * </ul>
 *
 * @param sessionId optional session identifier for conversation continuity. {@code null} indicates
 *     stateless execution.
 */
```

---

### 3.3 CURRENT-STATE.md 更新

**位置：** `docs/project/CURRENT-STATE.md`

**需要更新：**
1. M2-T3 状态从 "READY" → "DONE (awaiting API verification)"
2. M2-T4 状态从 "READY" → "IN PROGRESS"
3. 添加 "M2 User Documentation" section，链接到新文档

---

### 3.4 TASKS.md 更新

**位置：** `TASKS.md`

**M2-T4 条目内容：**
```markdown
### M2-T4: Documentation & Limitations ✅ DONE

**完成日期：** 2026-08-XX

**目标：** 完善 M2 用户文档和限制说明

**交付物：**
- ✅ M2 Quick Start Guide (`docs/guides/M2-MULTI-TURN-QUICK-START.md`)
- ✅ M2 Known Limitations (`docs/guides/M2-KNOWN-LIMITATIONS.md`)
- ✅ Example README 更新（multi-turn scenario）
- ✅ SpringAiToolCallingEngine Javadoc 改进
- ✅ AgentExecutionContext Javadoc 改进
- ✅ CURRENT-STATE.md 更新

**文档结构：**
- Quick Start: ~500-800 words, 5分钟上手
- Known Limitations: ~400-600 words, 明确边界
- Example README: +200-300 words, 实际示例

**关键内容：**
- ChatMemory.CONVERSATION_ID 使用要求
- 并发、持久化、compaction 限制
- Multi-turn vs Stateless 使用场景
```

---

## 4. 实施顺序

### Phase 1: 创建核心指南
1. 创建 `docs/guides/M2-MULTI-TURN-QUICK-START.md`
2. 创建 `docs/guides/M2-KNOWN-LIMITATIONS.md`

### Phase 2: 更新示例
3. 更新 `examples/incident-investigator/README.md`

### Phase 3: 改进代码注释
4. 更新 `SpringAiToolCallingEngine.java` Javadoc
5. 更新 `AgentExecutionContext.java` Javadoc

### Phase 4: 同步项目文档
6. 更新 `CURRENT-STATE.md`
7. 更新 `TASKS.md`
8. 更新 `DOCUMENT-MAP.md`（如果需要）

### Phase 5: 验证与提交
9. 确保所有链接有效
10. 确保示例代码可编译
11. Git commit & push

---

## 5. 验收标准

**必须满足：**
- [ ] M2 Quick Start Guide 可以让新用户在 5 分钟内理解如何使用 multi-turn
- [ ] Known Limitations 明确列出所有已知限制及其 workaround
- [ ] Example README 展示 multi-turn scenario
- [ ] SpringAiToolCallingEngine class Javadoc 包含 multi-turn 使用示例
- [ ] AgentExecutionContext Javadoc 说明 session semantics
- [ ] 所有新文档在 DOCUMENT-MAP.md 中被索引（如果适用）
- [ ] CURRENT-STATE.md 反映 M2-T4 完成状态
- [ ] TASKS.md 更新 M2-T4 为 DONE

**Should Have：**
- [ ] 文档中的代码示例可以直接编译（或明确标注为伪代码）
- [ ] 关键实现细节（ChatMemory.CONVERSATION_ID）在多处强调
- [ ] 限制说明包含 Future Plan 指向

**Won't Have：**
- ❌ 不创建完整的 API Reference（Javadoc 足够）
- ❌ 不创建 Step-by-step Tutorial（Quick Start 足够）
- ❌ 不解决任何技术限制（留给 M3）

---

## 6. 预估工作量

**文档编写：**
- Quick Start Guide: ~30-45 min
- Known Limitations: ~20-30 min
- Example README: ~15-20 min
- Javadoc 更新: ~20-30 min
- 项目文档同步: ~15-20 min

**总计：** ~1.5-2 hours

---

## 7. 风险与注意事项

**风险 1: 文档与实际代码不一致**
- 缓解：所有示例代码从实际测试中提取
- 缓解：文档编写时同步检查实际 API

**风险 2: Known Limitations 不完整**
- 缓解：系统性检查所有 M2 实现报告中的限制说明
- 缓解：与 CURRENT-STATE.md 的 Known Limitations 对齐

**风险 3: 用户误用 ChatMemory.CONVERSATION_ID**
- 缓解：在多处强调（Quick Start, Limitations, Javadoc）
- 缓解：提供正确和错误示例对比

---

## 8. 完成后状态

**M2 Phase 完成度：**
- ✅ M2-T1: Spring AI ChatMemory PoC
- ✅ M2-T2: AgentExecutionContext & Session Support
- ✅ M2-T3: Multi-Turn E2E Test (Root Cause Fixed)
- ✅ M2-T4: Documentation & Limitations

**M2 Progress:** 100% (4/4 tasks)

**下一阶段：**
- M3: Context Compaction & Long-term Memory
- M3: Session Locking & Concurrency
- M3: Persistent ChatMemory (JDBC/Redis)

---

**End of M2-T4 Design**
