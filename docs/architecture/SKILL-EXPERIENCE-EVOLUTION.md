# Skill / Experience 演进指南

**版本：** 1.0  
**最后更新：** 2026-08-18  
**状态：** Future Direction Document

---

## 文档目的

本文档定义 Arctra 中 **Skill / Experience / Playbook** 的长期演进方向。

**重要声明：**
- 这是未来方向文档，不是当前实现计划
- M1 不实现这些概念
- 只有在真实需求出现后才进入具体设计
- 本文档建立的是"何时以及如何演进"的指导原则

---

## 1. 核心概念区分

### 1.1 Tool vs Skill

**Tool（工具）：原子执行能力**

```java
// Tool 是单一执行能力
queryDatabase(sql: String): ResultSet
searchWeb(query: String): SearchResults
sendEmail(to: String, subject: String, body: String): void
executeSql(statement: String): ExecutionResult
readFile(path: String): String
```

**特征：**
- 单一职责
- 独立执行
- 无业务逻辑
- 可复用

**Skill（技能）：组合能力解决特定问题**

```
示例：财务分析 Skill

Input: 股票代码
Steps:
  1. queryWindData(股票代码) → 财务数据
  2. calculateFinancialRatios(财务数据) → 财务指标
  3. queryIndustryData(行业) → 行业数据
  4. compareWithIndustry(财务指标, 行业数据) → 对比结果
  5. generateInvestmentReport(对比结果) → 投资建议
Output: 投资研究报告
```

**特征：**
- 组合多个 Tool
- 包含业务逻辑
- 解决特定类型问题
- 可参数化
- 可版本化

### 1.2 明确边界

**Tool 提供原子能力，Skill 提供解决方案。**

**不要把以下概念混淆：**
- Tool Definition ≠ Skill Definition
- Tool Permission ≠ Skill Permission
- Tool 是否允许某个 Agent 使用 ≠ Skill 如何组合 Tool

**Tool Governance（工具治理）：**
- 权限：这个 Agent 能用这个 Tool 吗？
- 风险：这次调用参数是否危险？
- 审批：是否需要人工批准？
- 审计：完整记录调用过程

**Skill Governance（技能治理）：**
- 版本：Skill 有哪些版本？
- 发布：哪些 Agent 可以使用这个 Skill？
- 验证：Skill 是否经过充分测试？
- 评估：Skill 的成功率如何？
- 回滚：如何回退到上一个版本？

---

## 2. 从执行到经验的生命周期

### 2.1 四个阶段

```
Execution Trace
    ↓
Experience Candidate
    ↓
Validated Experience
    ↓
Skill / Playbook
```

### 2.2 Execution Trace（执行轨迹）

**保存一次真实执行过程。**

**包含：**
- 输入（AgentRequest）
- Planning（如果有）
- Model 调用记录
- Tool 调用记录
- Context 状态
- 中间状态
- 输出（AgentResult）
- Evidence
- Token 消耗
- Cost
- Latency
- Error（如果有）
- Evaluation 结果（如果有）

**它是"发生了什么"。**

**实现位置：**
- 未来可能在 `arctra-runtime`
- 或独立的 `arctra-observability` 模块

**何时实现：**
- 需要完整执行可观测性时
- 需要 replay / debug 时
- 需要从历史执行中提取 Experience 时

### 2.3 Experience Candidate（经验候选）

**从成功或失败 Trace 中提炼。**

**包含：**
- 任务类型（"查询生产日志分析故障"）
- 成功/失败标志
- 使用的 Tool 序列
- 使用的策略
- 为什么成功？
- 哪些条件下容易失败？
- 如何恢复？

**它是"从执行中学到了什么"。**

**关键：不是每次成功都立即晋升为 Skill。**

**原因：**
- 可能是偶然成功
- 可能只在特定环境下有效
- 可能包含错误的因果推断

**需要：**
- 多次验证
- A/B 测试
- 不同场景下验证
- 人工审核（关键场景）

### 2.4 Validated Experience（已验证经验）

**经过多次验证后的 Experience Candidate。**

**验证标准：**
- 在至少 N 次（例如 5 次）类似场景中成功
- 成功率 > X%（例如 80%）
- 平均 Cost / Latency 在可接受范围
- 无重大副作用
- 人工审核通过（关键场景）

**它是"可信的经验"。**

### 2.5 Skill / Playbook（技能 / 剧本）

**经过充分验证后，晋升为稳定、可复用、可版本化的能力。**

**特征：**
- 参数化
- 版本化（v1.0, v1.1, v2.0）
- 发布控制
- 回滚支持
- 权限控制
- Agent 绑定
- Evaluation 标准
- Success Criteria

**形成闭环：**

```
Task
    ↓
Skill Matching（匹配合适的 Skill）
    ↓
Skill Execution（执行 Skill）
    ↓
Evaluation（评估结果）
    ↓
Experience Feedback（反馈到 Experience）
    ↓
Skill Evolution（Skill 演进）
```

---

## 3. 防止经验污染

### 3.1 错误模式

**错误：一次偶然成功 → 立即永久固化**

```
用户：查询昨天的订单
Agent：调用 queryDatabase("SELECT * FROM orders WHERE date = '2024-08-17'")
        → 成功
系统：立即保存为 Skill "查询昨天订单"

问题：
- SQL 硬编码日期，无法复用
- 没有参数化
- 只在特定日期有效
```

### 3.2 正确模式

**Candidate / Validation / Promotion 生命周期**

```
第 1 次成功：
  → 创建 Experience Candidate
  → 标记为"待验证"

第 2-5 次成功：
  → 更新 Candidate 统计
  → 验证在不同场景下是否有效

验证通过：
  → 晋升为 Validated Experience

多次使用后：
  → 人工审核
  → 提取可参数化模式
  → 晋升为 Skill v1.0

持续评估：
  → 监控 Skill 成功率
  → 收集失败 Feedback
  → 演进为 Skill v1.1, v2.0
```

### 3.3 关键机制

**必须支持：**
- Candidate 过期（例如 30 天未再次成功 → 删除）
- 成功率监控（低于阈值 → 降级或下线）
- 版本管理（新版本与旧版本 A/B 测试）
- 人工审核（关键场景 Skill 必须人工批准）
- 回滚机制（新版本失败 → 立即回退）

---

## 4. Skill 的长期形态

### 4.1 Skill Definition（未来）

```java
// 这是未来可能的形态，不是当前实现
record SkillDefinition(
    String id,                      // skill:financial-analysis:v1.0
    String name,                    // "财务分析"
    String description,
    String version,                 // "1.0"
    List<String> requiredTools,     // ["queryWindData", "calculateRatios"]
    List<Parameter> parameters,     // 输入参数定义
    OutputContract outputContract,  // 输出契约
    SuccessCriteria criteria,       // 成功标准
    String createdFrom,             // experience:xxx
    Instant createdAt,
    String owner,
    SkillStatus status              // DRAFT / TESTING / PUBLISHED / DEPRECATED
)
```

### 4.2 Skill Execution（未来）

```java
// 这是未来可能的形态
SkillExecutionResult result = skillRuntime.execute(
    SkillRequest.builder()
        .skillId("skill:financial-analysis:v1.0")
        .parameter("stockCode", "600000.SH")
        .parameter("analysisDepth", "FULL")
        .agent(agentDefinition)
        .context(executionContext)
        .build()
);
```

### 4.3 Skill Registry（未来）

```java
// 这是未来可能的形态
interface SkillRegistry {
    Optional<SkillDefinition> resolve(String skillId);
    List<SkillDefinition> findByTag(String tag);
    List<SkillDefinition> findForAgent(String agentId);
    void publish(SkillDefinition skill);
    void deprecate(String skillId, String reason);
}
```

---

## 5. 何时实现 Skill / Experience

### 5.1 不要现在实现的原因

**M1 阶段：**
- 只有一个 Scenario（Incident Investigator）
- 没有"重复成功模式"
- 没有"多次验证"需求
- 没有"跨 Agent 复用"需求

**当前应该专注：**
- 基础 Agent Runtime 能力
- Evidence 捕获
- Tool Governance（未来）
- 执行可观测性

### 5.2 触发条件

**Execution Trace：**
- ✅ **Introduce when:**
  - 需要完整的执行可观测性
  - 需要 debug / replay
  - 需要从历史执行中学习

- ✅ **Required evidence:**
  - 至少 2 个 Agent Scenario
  - 需要分析"为什么成功/失败"

**Experience Candidate：**
- ✅ **Introduce when:**
  - 同一类任务重复执行超过 5 次
  - 需要识别"成功模式"
  - 需要从失败中学习

- ✅ **Required evidence:**
  - Execution Trace 已经存在
  - 至少 2 个不同 Agent 有类似任务

**Skill / Playbook：**
- ✅ **Introduce when:**
  - 同一成功模式被验证超过 10 次
  - 需要跨 Agent 复用
  - 需要版本化 / 发布 / 回滚

- ✅ **Required evidence:**
  - Validated Experience 已经存在
  - 至少 3 个 Agent 需要类似能力

---

## 6. 与 AgentScope 的借鉴关系

### 6.1 AgentScope 的 Skill / Experience 机制

**AgentScope 提供了：**
- Agent 执行记录
- 成功经验沉淀
- Skill 注册与复用
- Experience 反馈

**值得学习：**
- Experience 的生命周期管理
- Skill 的参数化设计
- 成功模式的识别方法
- 反馈与演进机制

### 6.2 Arctra 的差异化

**不要简单复制 AgentScope 的 API。**

**Arctra 应该：**
- 深度集成 Spring 生态
- 支持企业级治理（版本、审批、审计）
- 与 Spring AI Tool Calling Loop 自然集成
- 支持 Tool Governance
- 支持 Skill RBAC
- 支持 Tenant Isolation

**示例差异化能力：**

```java
// Arctra 特有的企业级 Skill 管理
@Service
public class EnterpriseSkillService {
    
    // Skill 发布需要审批
    public void publishSkill(SkillDefinition skill, ApprovalWorkflow workflow) {
        // 集成企业审批流程
    }
    
    // Skill 使用需要权限
    @PreAuthorize("hasPermission(#skillId, 'SKILL', 'USE')")
    public SkillExecutionResult execute(String skillId, SkillRequest request) {
        // 集成 Spring Security
    }
    
    // Skill 执行需要审计
    @Audited
    public SkillExecutionResult executeWithAudit(
        String skillId, 
        SkillRequest request,
        TenantContext tenantContext
    ) {
        // 集成租户隔离和审计
    }
}
```

---

## 7. 实现路径（未来）

**建议的演进路径：**

### Phase 1: Execution Observability（M2 或 M3）
- 完整的 Execution Trace
- Evidence 捕获
- 执行可观测性

### Phase 2: Experience Learning（M3 或 M4）
- 从 Trace 中提取 Experience Candidate
- 简单的成功模式识别
- Experience 统计

### Phase 3: Skill Framework（M4 或 M5）
- Skill Definition
- Skill Registry
- Skill Execution Runtime
- 基础版本管理

### Phase 4: Enterprise Integration（M5+）
- Skill 发布审批
- Skill RBAC
- Skill 审计
- Tenant Isolation
- A/B Testing
- 成功率监控

---

## 8. 当前 M1 不做什么

**明确不实现：**
- ❌ Execution Trace
- ❌ Experience Candidate
- ❌ Validated Experience
- ❌ Skill Definition
- ❌ Skill Registry
- ❌ Skill Execution Runtime
- ❌ 任何"从历史中学习"的机制

**原因：**
- M1 只有一个 Scenario
- 没有"重复模式"
- 没有"跨 Agent 复用"需求
- 过早抽象会导致错误的设计

**当前专注：**
- ✅ Agent 基础能力
- ✅ Evidence 捕获
- ✅ Spring AI 集成
- ✅ 第一个真实 Vertical Slice

---

## 9. 总结

### 9.1 核心原则

**Tool 提供原子能力，Skill 提供解决方案。**

**不要把 Tool Governance 和 Skill Management 混淆。**

**Experience 需要多次验证，不是一次成功就固化。**

### 9.2 演进触发条件

**只有在以下情况下才开始 Skill / Experience 设计：**
1. 至少 2 个 Agent Scenario 已经运行
2. 同一类任务重复执行超过 5 次
3. 识别出可复用的成功模式
4. 需要跨 Agent 共享能力

### 9.3 与成熟框架的关系

**AgentScope 是重要的参考系，但不是实现目标。**

**学习设计思想，不复制 API。**

**Arctra 的差异化在于企业级集成和治理。**

---

**End of Document**
