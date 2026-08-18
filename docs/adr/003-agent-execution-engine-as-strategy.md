# ADR-003: AgentExecutionEngine as Pluggable Execution Strategy

**Status:** Proposed  
**Date:** 2026-08-18  
**Deciders:** lov3r  
**Context Source:** Post-M2 Execution Model Reassessment

---

## Context

M1/M2 实现了 `AgentExecutionEngine` interface 和 `SpringAiToolCallingEngine` 实现，但 Engine 的长期语义定位一直未明确：

- Engine 是否拥有完整的 Agent semantics？
- Engine 是否应该管理 Agent lifecycle？
- Engine 与未来的 Process / Workflow / Planning 是什么关系？

**触发因素：**

Post-M2 Execution Model Reassessment 发现：
1. 如果 Engine = complete Agent，则无法支持多种 execution strategies
2. 如果 Engine 管理 lifecycle，则无法在其上添加 Process orchestration
3. 当前代码未明确 Engine 边界，存在误解风险

**不明确的后果：**
- 未来可能把 multi-step state 塞进 ChatMemory
- 未来可能把 process lifecycle 塞进 Session
- 未来可能误以为 Tool Calling Engine = 唯一的 Agent 实现方式

---

## Decision

**We decide that:**

> `AgentExecutionEngine` 是 **pluggable execution strategy component**，不拥有完整的 Agent domain semantics。

**具体定义：**

1. **Engine Responsibility:**
   - Engine 负责"**如何执行**"（how to execute）
   - Engine 实现一种 execution strategy
   - Engine 不负责 Agent lifecycle 管理
   - Engine 不负责 Process orchestration

2. **Engine Positioning:**
   - Engine 是架构中的一个 **component**，不是 top-level boundary
   - Engine 是可插拔的（pluggable）
   - 不同 Engine 可以有不同的 execution unit 定义

3. **Long-term Architecture:**
   ```
   User Agent API
       ↓
   Agent Runtime (lifecycle management)
       ↓
   [Process Runtime] (for multi-step, optional)
       ↓
   AgentExecutionEngine (pluggable strategy)
       ├── ToolCallingEngine (current)
       ├── WorkflowEngine (future)
       └── PlanningEngine (future)
       ↓
   Model / Tool / Code / Sub-Agent
   ```

4. **execute() Semantics:**
   - `execute()` 的语义由具体 Engine 定义
   - `SpringAiToolCallingEngine.execute()` = "执行一次 Tool-Calling-based ReAct loop"
   - Future `WorkflowEngine.execute()` = "执行一个 workflow step 或 entire workflow"
   - Future `PlanningEngine.execute()` = "执行一次 goal-driven planning cycle"

---

## Rationale

### Why "Execution Strategy" not "Complete Agent"?

**如果 Engine = Complete Agent：**

❌ **Problem 1: 单一实现路径**
- 只能有一种 Agent 实现方式
- 无法支持 Workflow / Planning / Hybrid strategies

❌ **Problem 2: 无法分层**
- Process orchestration 无处安放
- Multi-step 必须在 Engine 内部实现
- 无法 checkpoint / resume / HITL

❌ **Problem 3: 职责混淆**
- Engine 既负责执行，又负责 lifecycle
- 违反 Single Responsibility Principle

**如果 Engine = Pluggable Strategy：**

✅ **Benefit 1: 多种实现共存**
- ToolCallingEngine, WorkflowEngine, PlanningEngine 并存
- 用户根据场景选择（或自动选择）

✅ **Benefit 2: 分层清晰**
- Engine 专注 execution
- Runtime 负责 lifecycle
- Process Runtime 负责 orchestration

✅ **Benefit 3: 演进空间**
- 添加新 Engine 不影响现有代码
- Process / Workflow 可以组合多个 Engine

---

## Consequences

### Positive

1. **清晰的职责边界**
   - Engine = how to execute
   - Definition = what to execute
   - Context = execution environment
   - Runtime = orchestration

2. **支持未来演进**
   - 可以添加 WorkflowEngine / PlanningEngine
   - 可以在 Engine 之上添加 Process Runtime
   - 不破坏现有代码

3. **避免错误抽象**
   - 不会把 multi-step state 塞进 ChatMemory
   - 不会把 process lifecycle 塞进 Session
   - 不会误解 Tool Loop = Multi-step Process

### Negative

1. **需要明确文档化**
   - 必须更新 EVOLUTION-GUIDE
   - 必须创建 EXECUTION-MODEL-SEMANTICS
   - 必须培训新开发者

2. **可能引入新层级**
   - 未来可能需要 Agent Runtime
   - 未来可能需要 Process Runtime
   - 架构复杂度增加（但清晰度也增加）

### Neutral

1. **当前代码无需修改**
   - 这是 semantic decision，不是 code change
   - 现有代码已经符合这个定位
   - 只需要文档化

---

## Compliance

### What This Decision Requires

1. **Documentation:**
   - ✅ Create EXECUTION-MODEL-SEMANTICS.md
   - ✅ Create ARCHITECTURE-GUARDRAILS.md
   - ✅ Update EVOLUTION-GUIDE.md
   - ✅ Update M2 guides (clarify multi-turn ≠ multi-step)

2. **Future Development:**
   - When creating new Engine, document its execute() semantics
   - When adding Process Runtime, position it above Engine
   - When designing Agent API, hide Engine selection from user

3. **Code Review:**
   - Check that Engine doesn't manage Agent lifecycle
   - Check that multi-step state doesn't go into ChatMemory
   - Check that process concerns don't leak into Session

### What This Decision Does NOT Require

1. **No Code Changes:**
   - Current SpringAiToolCallingEngine stays as-is
   - Current AgentExecutionEngine interface stays as-is
   - No refactoring needed

2. **No Immediate Implementation:**
   - Don't create Process Runtime now
   - Don't create WorkflowEngine now
   - Don't create Agent Runtime now
   - Wait for real consumer

---

## Alternatives Considered

### Alternative A: Engine = Complete Agent

**Definition:**  
`AgentExecutionEngine` 拥有完整的 Agent semantics，是 Agent 的完整实现。

**Rejected because:**
- 无法支持多种 execution strategies
- 未来 Process / Workflow 无处安放
- 违反 Open-Closed Principle

---

### Alternative B: Engine = Low-level Primitive

**Definition:**  
`AgentExecutionEngine` 只是一个 low-level execution primitive，不涉及任何 Agent semantics。

**Rejected because:**
- 当前 SpringAiToolCallingEngine 已经包含 Agent-level 概念（system prompt, tools）
- 过于 low-level，不符合 Arctra 定位（不是重新实现 Spring AI）
- 会导致过度抽象

---

### Alternative C: No Engine Abstraction

**Definition:**  
直接使用 Spring AI ChatClient，不创建 AgentExecutionEngine abstraction。

**Rejected because:**
- 无法支持非 Spring AI 的 execution strategies
- 无法统一 Evidence collection
- 无法统一 Agent semantics
- 违反 Arctra 定位（governance + observability harness）

---

## Related Decisions

- **ADR-001:** (if exists) Project structure decisions
- **ADR-002:** Project naming and coordinates
- **Future ADR:** Agent Runtime positioning (when implemented)
- **Future ADR:** Process Runtime vs Engine relationship (when multi-step implemented)

---

## References

- [Post-M2 Execution Model Reassessment](../architecture/POST-M2-EXECUTION-MODEL-REASSESSMENT.md)
- [Execution Model Semantics](../architecture/EXECUTION-MODEL-SEMANTICS.md)
- [Architecture Guardrails](../architecture/ARCHITECTURE-GUARDRAILS.md)
- [Architecture Evolution Guide](../architecture/EVOLUTION-GUIDE.md)

---

**Decision Status:** Proposed - Awaiting Approval
