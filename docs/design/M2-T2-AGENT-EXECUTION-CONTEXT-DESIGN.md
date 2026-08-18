# M2-T2 AgentExecutionContext Design Decision

**Date:** 2026-08-18  
**Purpose:** 确定 AgentExecutionContext 最终设计

---

## Option 比较

### Option A: Nullable String (推荐)

```java
public record AgentExecutionContext(String sessionId) {
    
    public static AgentExecutionContext stateless() {
        return new AgentExecutionContext(null);
    }
    
    public static AgentExecutionContext withSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        return new AgentExecutionContext(sessionId);
    }
}
```

**优点：**
- ✅ 简单直接
- ✅ 符合 Java API idiom（许多 Java API 使用 nullable fields）
- ✅ Factory methods 提供语义清晰的构造
- ✅ 无额外抽象（SessionId value object）
- ✅ 易于序列化（如果未来需要）
- ✅ `sessionId == null` 清晰表达 stateless

**缺点：**
- ⚠️ Nullable field（但通过 factory methods 控制）

---

### Option B: Optional<String>

```java
public record AgentExecutionContext(Optional<String> sessionId) {
    
    public static AgentExecutionContext stateless() {
        return new AgentExecutionContext(Optional.empty());
    }
    
    public static AgentExecutionContext withSession(String sessionId) {
        return new AgentExecutionContext(Optional.of(sessionId));
    }
}
```

**优点：**
- ✅ 避免 null

**缺点：**
- ❌ Optional as field anti-pattern（Java 官方不推荐 Optional 作为 field）
- ❌ 使用更复杂：`context.sessionId().orElse(null)`
- ❌ 序列化问题
- ❌ 不符合 Java API 惯例

**官方指导：**
> "Optional is primarily intended for use as a method return type"  
> — Java Optional Javadoc

---

### Option C: SessionId Value Object

```java
public record SessionId(String value) {
    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
    }
}

public record AgentExecutionContext(SessionId sessionId) {
    
    public static AgentExecutionContext stateless() {
        return new AgentExecutionContext(null);  // Still nullable
    }
    
    public static AgentExecutionContext withSession(String sessionId) {
        return new AgentExecutionContext(new SessionId(sessionId));
    }
}
```

**优点：**
- ✅ Type safety
- ✅ 可以添加 sessionId-specific 方法

**缺点：**
- ❌ 过早抽象（M2 没有 sessionId-specific 行为）
- ❌ 仍然 nullable（SessionId 可以为 null）
- ❌ 增加复杂度（多一层包装）
- ❌ 当前没有真实消费者需要 SessionId type

---

## 决策：Option A (Nullable String)

### 理由

**1. 符合 Java API 惯例**
- `HttpServletRequest.getSession()` 可以返回 null
- 许多 Java API 使用受控的 nullable fields

**2. Factory methods 提供清晰语义**
- `stateless()` vs `withSession(id)` 清晰表达意图
- 构造时验证（withSession 检查 blank）

**3. 无过早抽象**
- 不创建 SessionId value object（当前无消费者）
- 不使用 Optional（anti-pattern for fields）

**4. 实现简单**
- 使用方自然：`context.sessionId()`
- 检查简单：`if (sessionId != null)`

---

## Invariant

**Record invariant:**
- `sessionId` 可以是 `null`（表示 stateless）
- `sessionId` 不能是 blank string（通过 factory method 保证）

**Factory contract:**
```java
// Stateless execution
AgentExecutionContext.stateless()        // sessionId = null

// Session execution  
AgentExecutionContext.withSession("s1") // sessionId = "s1"

// ❌ Invalid (抛出异常)
AgentExecutionContext.withSession("")
AgentExecutionContext.withSession(null)
```

---

## Final Design

```java
package cn.bitcss.arctra.agent;

/**
 * Agent execution context.
 *
 * <p>Represents execution-level environment for an agent invocation,
 * independent of user input ({@link AgentRequest}) and agent template
 * ({@link AgentDefinition}).
 *
 * <p>Currently contains session identity for multi-turn conversation continuity.
 *
 * @param sessionId optional session identifier for conversation continuity.
 *                  {@code null} indicates stateless execution.
 * @author lov3r
 */
public record AgentExecutionContext(String sessionId) {

    /**
     * Create a stateless execution context (no session).
     *
     * @return stateless context with {@code sessionId = null}
     */
    public static AgentExecutionContext stateless() {
        return new AgentExecutionContext(null);
    }

    /**
     * Create an execution context with session.
     *
     * @param sessionId session identifier, must not be null or blank
     * @return context with the given sessionId
     * @throws IllegalArgumentException if sessionId is null or blank
     */
    public static AgentExecutionContext withSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be null or blank");
        }
        return new AgentExecutionContext(sessionId);
    }
}
```

---

## Package 决策

**推荐：** `cn.bitcss.arctra.agent.AgentExecutionContext`

**理由：**
- 与 `AgentDefinition`, `AgentRequest`, `AgentResult` 同 package
- 都是 agent execution 的核心概念
- 不需要独立的 `context` package（避免过度模块化）

---

**决策完成，准备实现。**
