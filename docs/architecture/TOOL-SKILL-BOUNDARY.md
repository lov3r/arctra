# Tool 与 Skill 边界定义

**版本：** 1.0  
**最后更新：** 2026-08-18  
**状态：** Architecture Principle Document

---

## 文档目的

明确 **Tool** 和 **Skill** 的边界，防止将不同层次的概念混淆。

---

## 1. 核心区分

### 1.1 Tool（工具）

**定义：原子执行能力**

```java
// Tool 提供单一功能
queryDatabase(sql: String): ResultSet
searchWeb(query: String): SearchResults
sendEmail(to: String, subject: String, body: String): void
executeSql(statement: String): ExecutionResult
readFile(path: String): String
restartService(serviceName: String): void
```

**特征：**
- 单一职责
- 独立执行
- 无业务逻辑
- 可跨场景复用
- 无状态（或状态由 Tool 自己管理）

**当前 M1 实现：**
```java
// examples/incident-investigator
QueryLogsTool implements ToolCallback
GetDeploymentTool implements ToolCallback
```

### 1.2 Skill（技能）

**定义：组合多个 Tool 解决特定类型问题的能力**

```
示例：财务分析 Skill

Input: 
  - stockCode: String
  - analysisDepth: BASIC | FULL

Steps:
  1. queryWindData(stockCode) 
     → 获取财务数据
  2. calculateFinancialRatios(financialData) 
     → 计算财务指标
  3. queryIndustryData(industry) 
     → 获取行业数据
  4. compareWithIndustry(ratios, industryData) 
     → 行业对比
  5. generateInvestmentReport(comparison) 
     → 生成投资建议

Output: 投资研究报告
```

**特征：**
- 组合多个 Tool
- 包含业务逻辑和领域知识
- 解决特定类型问题
- 可参数化
- 可版本化
- 可跨 Agent 复用

**当前 M1 状态：不存在**
- M1 只有 Agent + Tools，没有 Skill 层

---

## 2. 边界对比

| 维度 | Tool | Skill |
|------|------|-------|
| **职责** | 原子执行能力 | 组合能力解决问题 |
| **复杂度** | 简单、单一功能 | 复杂、多步骤流程 |
| **业务逻辑** | 无 | 有 |
| **领域知识** | 无 | 有 |
| **依赖关系** | 独立 | 依赖多个 Tool |
| **参数化** | 简单参数 | 复杂参数 + 配置 |
| **版本管理** | 通常不需要 | 必须版本化 |
| **治理需求** | Permission / Audit | Permission / Audit / Approval / Evaluation |
| **生命周期** | 通常稳定 | 持续演进 |
| **当前实现** | M1 已有 | M1 不存在 |

---

## 3. Tool Governance vs Skill Governance

### 3.1 Tool Governance（工具治理）

**关注："这个 Agent 能用这个 Tool 吗？这次调用是否危险？"**

**包含：**
- **Permission（权限）** — 这个 Agent 能用这个 Tool 吗？
- **Risk（风险）** — 这次调用的参数是否危险？
- **Approval（审批）** — 这次调用是否需要人工批准？
- **Audit（审计）** — 完整记录调用过程

**示例：**

```java
// Tool: executeSql

Governance Policy:
  - Agent "data-analyst" 可以使用
  - Agent "chatbot" 不可以使用
  
  - 如果 SQL 包含 "DROP", "DELETE", "TRUNCATE" → 需要审批
  - 如果目标是生产数据库 → 需要审批
  - 如果目标是测试数据库 → 自动放行
  
  - 所有调用必须记录到审计日志
```

**关键：Tool Governance 是动态的，基于调用上下文。**

**不要把风险固化到 Tool Definition：**

```java
// BAD: 把风险固化到 Tool
interface Tool {
    RiskLevel getRiskLevel();  // ❌ 错误
    boolean requiresApproval(); // ❌ 错误
}
```

**为什么错误：**
- `executeSql("SELECT ...")` 低风险
- `executeSql("DROP TABLE ...")` 高风险
- 同一个 Tool，风险取决于参数和上下文

**正确做法：Policy 基于 ToolInvocation 动态评估。**

### 3.2 Skill Governance（技能治理）

**关注："这个 Skill 是否经过验证？哪些 Agent 可以使用？"**

**包含：**
- **Version（版本）** — Skill 有哪些版本？
- **Publish（发布）** — 哪些 Agent 可以使用这个 Skill？
- **Validation（验证）** — Skill 是否经过充分测试？
- **Evaluation（评估）** — Skill 的成功率如何？
- **Rollback（回滚）** — 如何回退到上一个版本？
- **A/B Testing** — 新版本和旧版本对比
- **Approval（审批）** — Skill 发布是否需要人工审批？

**示例：**

```java
// Skill: financial-analysis v1.0

Governance:
  - Status: PUBLISHED
  - Allowed Agents: ["investment-advisor", "risk-manager"]
  - Success Rate: 87% (based on 150 executions)
  - Average Cost: $0.15
  - Average Latency: 8.5s
  
  - Validation:
    - Tested on 50 different stocks
    - Reviewed by domain expert
    - Passed evaluation criteria
  
  - Version History:
    - v1.1 (TESTING) - added industry comparison
    - v1.0 (PUBLISHED) - current stable version
    - v0.9 (DEPRECATED) - initial version
```

---

## 4. 不要混淆的概念

### 4.1 Tool Definition ≠ Skill Definition

**Tool Definition：**
```java
// 描述一个原子能力
ToolDefinition.builder()
    .name("queryLogs")
    .description("Query application logs")
    .inputSchema("""
        {
          "type": "object",
          "properties": {
            "timeRange": {"type": "string"}
          }
        }
        """)
    .build();
```

**Skill Definition（未来）：**
```java
// 描述一个复合能力
SkillDefinition.builder()
    .id("skill:incident-analysis:v1.0")
    .name("Incident Analysis")
    .description("Analyze production incidents using logs and deployment info")
    .requiredTools(List.of("queryLogs", "getDeployment"))
    .parameters(List.of(
        Parameter.of("incidentDescription", "string", true),
        Parameter.of("timeRange", "string", false)
    ))
    .successCriteria(SuccessCriteria.builder()
        .mustContain(List.of("root cause", "recommendation"))
        .build())
    .build();
```

### 4.2 Tool Permission ≠ Skill Permission

**Tool Permission：**
```
Agent "chatbot" 不能使用 Tool "executeSql"
```

**Skill Permission（未来）：**
```
Agent "junior-analyst" 不能使用 Skill "financial-analysis"
Agent "senior-analyst" 可以使用 Skill "financial-analysis"
```

**区别：**
- Tool Permission 关注"能否执行这个原子操作"
- Skill Permission 关注"能否使用这个复合能力"

**可能的场景：**
- Agent 可以使用 Skill "incident-analysis"
- 但 Skill 内部使用的某个 Tool 仍需要二次权限检查

### 4.3 Agent Tool Binding ≠ Skill Assignment

**Agent Tool Binding（未来）：**
```
定义：某个 Agent 对某个 Tool 的特定使用约束

示例：
  Agent "production-operator" 使用 Tool "restartService"：
    - 只能重启自己负责的服务
    - 生产环境需要审批
    - 测试环境自动放行
    - 最多每小时 3 次
```

**Skill Assignment（未来）：**
```
定义：某个 Agent 可以使用哪些 Skill

示例：
  Agent "incident-investigator"：
    - 可以使用 Skill "incident-analysis" v1.0
    - 可以使用 Skill "log-query" v2.1
    - 不能使用 Skill "database-migration"
```

---

## 5. 同一个 Tool，不同 Agent 的不同治理要求

**示例场景：**

### Tool: queryLogs

**Agent: "chatbot"**
- ❌ 不允许使用

**Agent: "incident-investigator"**
- ✅ 允许使用
- 生产环境日志：只能查询最近 30 分钟
- 测试环境日志：可以查询任意时间范围
- 不允许查询包含 "password", "token", "secret" 的字段

**Agent: "security-investigator"**
- ✅ 允许使用
- 生产环境日志：可以查询最近 24 小时
- 可以查询包含敏感信息的字段
- 所有查询必须记录到安全审计日志

**结论：**
> Tool capability 可以共享，但 Tool 是否允许某个 Agent 使用、什么条件下使用、是否需要审批、如何审计，应属于 Agent / Runtime 的治理策略。

> 不要把 Tool Definition 和 Agent Governance 混成一个对象。

---

## 6. 风险通常不是 Tool 的固有属性

### 6.1 错误设计

```java
// BAD: 把风险等级固化到 Tool
interface Tool {
    String name();
    String description();
    RiskLevel getRiskLevel();       // ❌ 错误
    boolean requiresApproval();     // ❌ 错误
}

enum RiskLevel {
    LOW, MEDIUM, HIGH
}
```

### 6.2 为什么错误

**同一个 Tool，不同调用的风险完全不同：**

```java
// Tool: executeSql

executeSql("SELECT * FROM users LIMIT 10")
  → 风险：LOW（只读查询，小数据量）

executeSql("SELECT * FROM users")
  → 风险：MEDIUM（只读查询，但可能返回大量数据）

executeSql("UPDATE users SET role = 'admin' WHERE id = 123")
  → 风险：HIGH（数据修改）

executeSql("DROP TABLE users")
  → 风险：CRITICAL（数据删除）
```

**同样：**

```java
// Tool: restartService

restartService("test-service")
  → 风险：LOW（测试环境）

restartService("payment-service")
  → 风险：HIGH（生产环境，关键服务）
```

### 6.3 正确做法

**Policy 基于 ToolInvocation 动态评估：**

```java
// 未来可能的形态

record ToolInvocation(
    AgentDefinition agent,
    Tool tool,
    String arguments,        // JSON
    ExecutionContext context // environment, caller, etc.
)

interface PolicyEngine {
    PolicyDecision evaluate(ToolInvocation invocation);
}

enum PolicyDecision {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL
}
```

**Policy 可以检查：**
- Agent 是谁？
- Tool 是什么？
- 参数是什么？（例如 SQL 是否包含 DROP / DELETE）
- 执行环境是什么？（生产 / 测试）
- 调用者是谁？
- 时间是什么？（工作时间 / 非工作时间）
- 最近调用频率如何？

---

## 7. 未来可能的架构（方向，非实现）

### 7.1 Tool Layer

```
Tool Definition
    ↓
Tool Implementation (Spring AI ToolCallback / MCP / HTTP API / etc.)
    ↓
Tool Execution
```

### 7.2 Skill Layer

```
Skill Definition
    ↓
Skill = Tool Sequence + Logic + Domain Knowledge
    ↓
Skill Execution
```

### 7.3 Governance Layer

```
ToolInvocation
    ↓
Policy Evaluation (Permission / Risk / Approval)
    ↓
Tool Execution
    ↓
Audit
```

### 7.4 完整架构

```
Agent
    ↓
Skill Matching (if Skill exists)
    ↓
Tool Selection
    ↓
Agent Tool Binding (permission / scope for this Agent)
    ↓
ToolInvocation
    ↓
Policy Evaluation
    ↓
ALLOW / DENY / REQUIRE_APPROVAL
    ↓ (if ALLOW)
Tool Execution
    ↓
Audit
    ↓
Evidence
    ↓
Experience Feedback (if enabled)
```

---

## 8. 当前 M1 状态

### 8.1 已有

- ✅ Tool（Spring AI ToolCallback）
- ✅ QueryLogsTool / GetDeploymentTool（Mock Tools）
- ✅ Tool Calling Loop（Spring AI ToolCallingAdvisor）
- ✅ Evidence 捕获（EvidenceCapturingToolCallback）

### 8.2 不存在（未来）

- ❌ Skill Definition
- ❌ Skill Execution
- ❌ Tool Governance（Permission / Risk / Approval）
- ❌ Agent Tool Binding
- ❌ Policy Evaluation
- ❌ ToolInvocation
- ❌ Audit（完整审计）

### 8.3 为什么不存在

**M1 只有：**
- 1 个 Scenario（Incident Investigator）
- 2 个 Mock Tools（无风险）
- 1 个 Agent

**没有真实需求：**
- 没有"多个 Agent 使用同一个 Tool"
- 没有"Tool 需要权限控制"
- 没有"Tool 需要审批"
- 没有"Skill 需要跨 Agent 复用"

---

## 9. 何时引入 Skill

### 9.1 触发条件

**不要在以下情况引入：**
- 只有 1 个 Agent Scenario
- 没有"重复成功模式"
- 没有"跨 Agent 复用"需求

**应该在以下情况引入：**
- 至少 2 个 Agent Scenario 已经运行
- 同一类任务重复执行超过 5 次
- 识别出可复用的成功模式
- 需要跨 Agent 共享能力

### 9.2 实现顺序

**建议路径：**

1. **Phase 1: 更多 Agent Scenarios（M2 或 M3）**
   - 实现第二个、第三个 Agent
   - 观察是否出现重复模式

2. **Phase 2: Execution Trace（M3）**
   - 记录完整执行过程
   - 识别成功模式

3. **Phase 3: Experience Candidate（M3 或 M4）**
   - 从 Trace 中提取可能的 Skill
   - 验证成功率

4. **Phase 4: Skill Framework（M4 或 M5）**
   - Skill Definition
   - Skill Registry
   - Skill Execution

5. **Phase 5: Tool Governance（M5+）**
   - Tool Permission
   - Tool Risk Evaluation
   - Tool Approval / Audit

---

## 10. 总结

### 10.1 核心原则

**Tool 提供原子能力，Skill 提供解决方案。**

**不要把 Tool Definition 和 Agent Governance 混淆。**

**风险是 context-dependent 的，不是 Tool 的固有属性。**

### 10.2 当前 M1

- ✅ Tool 已经存在（Spring AI ToolCallback）
- ❌ Skill 不存在（未来）
- ❌ Tool Governance 不存在（未来）

### 10.3 未来演进

**只有在真实需求出现后，才创建 Skill / Governance 抽象。**

**AgentScope 是参考系，不是实现目标。**

---

**End of Document**
