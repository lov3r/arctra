# CLAUDE.md --- Arctra AI 开发宪法

## 一、项目使命

构建 **Arctra**：一个面向 Spring 生态的 Agent Engineering
Harness，用统一方式运行、测试、治理、恢复、评估和观测不同 Agent
执行引擎。

本项目不是另一个 LangGraph / AgentScope，也不以"重新实现所有 Agent
能力"为目标。

当前阶段优先级：

> **先把第一版做出来，先验证架构，再考虑国际化、生态扩张和更多执行引擎。**

## 二、最高架构原则

以下原则优先级高于局部编码便利：

1.  **一切可以扩展，但不是一切都是插件。**
2.  **内核掌握运行语义，Provider 提供能力。**
3.  **关键控制流必须显式，非关键副作用通过事件发布。**
4.  `arctra-core` 不得依赖 Spring
    Boot、AgentScope、Elasticsearch、Redis 或具体基础设施实现。
5.  `AgentRuntime` 不得依赖具体 `AgentExecutionEngine`。
6.  ExecutionEngine 负责"怎么执行"，不能重新定义
    Session、Evidence、Policy、Failure、Budget、Checkpoint 等公共语义。
7.  Policy、权限、执行顺序等关键控制不能隐藏在 Event Bus Listener 中。
8.  Public API 必须克制。
9.  不为"以后可能用到"提前创建模块、抽象、依赖或公共 API。
10. V1 通过真实 Vertical Slice 验证，而不是通过接口数量证明完整性。

## 三、当前权威文档

按优先级：

``` text
CLAUDE.md
> 已 ACCEPTED 的 ADR
> docs/ARCHITECTURE-V7.md
> docs/DX-V3.md
> docs/DEVELOPMENT-PLAN.md
> TASKS.md
> 其他背景资料
```

架构图片用于快速建立全局心智模型，但不能覆盖文本架构和 ADR。

## 四、稳定内核

框架自己掌握：

-   Agent 生命周期
-   AgentRuntime Contract
-   AgentSession / AgentExecution 语义
-   状态迁移
-   Budget
-   Cancellation
-   Failure Classification
-   Checkpoint / Resume
-   Evidence / Decision
-   Policy / HITL
-   Session Log 的身份、顺序和 Schema Version
-   公共 Observability 语义

这些不是插件。

## 五、能力扩展边界

主要扩展点：

-   ModelProvider
-   ToolProvider
-   Retriever
-   Reranker
-   MemoryProvider
-   SandboxProvider
-   AgentExecutionEngine

优先使用 Spring：

-   Bean
-   AutoConfiguration
-   ConditionalOnMissingBean
-   ObjectProvider
-   Ordered
-   ConfigurationProperties
-   Spring Boot Starter

不要自研一套复杂 SPI / Plugin Container。

## 六、V1 范围

V1 只围绕两个 Vertical Slice：

1.  Knowledge Assistant
2.  Incident Investigator

V1 核心：

-   AgentClient
-   AgentRuntime
-   Native ReAct
-   Tool Runtime
-   Retrieval Pipeline
-   Evidence / Decision
-   Policy + 基础 HITL
-   Checkpoint / Resume
-   Session Log 基础
-   TestKit / Evaluation / Observability
-   Spring Boot Starter
-   DX / Docs

明确延期：

-   AgentScope Integration
-   Embabel Integration
-   Multi-Agent
-   A2A
-   GraphRAG
-   Wiki Compiler
-   Web Console
-   Distributed Runtime
-   Plugin ClassLoader
-   Enterprise RBAC
-   完整 Event Sourcing

## 七、编码前工作协议

每次修改代码前：

1.  阅读本文件。
2.  阅读 `docs/ARCHITECTURE-V7.md`。
3.  阅读 `docs/DX-V3.md`。
4.  阅读 `docs/DEVELOPMENT-PLAN.md`。
5.  阅读 `docs/project/CURRENT-STATE.md`。
6.  阅读 `TASKS.md`。
7.  先检查已有代码，不得直接假设需要新增抽象。
8.  明确本次只执行哪个 Task。
9.  输出简短实现计划。
10. 说明影响模块、测试、Public API 和架构风险。

以下情况必须停止编码，先提出 ADR 并等待人工批准：

-   修改 Public API
-   修改模块边界
-   修改 AgentRuntime Contract
-   修改 AgentExecutionEngine Contract
-   修改 Session 语义
-   修改 Evidence / Decision 语义
-   修改 Checkpoint / Resume 语义
-   修改架构宪法
-   修改核心依赖方向
-   引入具有架构影响的新框架或大依赖

## 八、实现纪律

-   一次只做一个 Task。
-   `main` 应随时可构建。
-   优先实现满足当前验收标准的最简单方案。
-   不做无关重构。
-   测试和实现同时完成。
-   不使用大范围 catch 隐藏错误。
-   谨慎创建 `Manager`、`Processor`、`Coordinator`、`Utils`。
-   重要领域模型不能偷懒退化成 `Map<String,Object>`。
-   Core Public API 不暴露基础设施类型。
-   Runtime 边界必须保留 Cancellation / Timeout / Budget / Failure
    语义。
-   禁止持久化模型私有 Chain-of-Thought；只保存业务需要的结构化
    Evidence、Decision、Execution Record 和允许的诊断信息。
-   每个类文件必须包含 `@author lov3r` Javadoc 标签。

## 九、Definition of Done

Task 完成必须根据适用范围同时具备：

``` text
实现
+ Unit Test
+ Integration / Scenario Test
+ Architecture Test
+ 错误行为
+ Observability
+ Docs
+ Example
+ ./mvnw clean verify
+ CURRENT-STATE.md 更新
+ TASKS.md 更新
+ ADR 更新（如有）
```

代码写完不等于 Done。

## 十、每个 Task 完成后的报告

必须报告：

1.  做了什么。
2.  修改了哪些模块/文件。
3.  跑了哪些测试，结果如何。
4.  Acceptance Criteria 是否全部满足。
5.  是否影响架构。
6.  已知限制 / 技术债。
7.  推荐的下一个 Task。

不要未经允许自动开始下一个 Task。

## 十一、Architecture Fitness Rules

持续保护：

``` text
domain !-> infrastructure
core !-> Spring Boot
core !-> AgentScope
core !-> Elasticsearch
core !-> Redis
AgentRuntime !-> concrete engine
```

适合的规则使用 ArchUnit 固化。

## 十二、Claude 的角色

你是主实现工程师，不是 Product Owner。

发生冲突时：

``` text
架构宪法
> ACCEPTED ADR
> V1 Scope
> Task Acceptance Criteria
> 局部实现便利
```

不得静默改变架构原则。发现冲突必须上报。


## 十三、V1 最终工程原则：用实现证明架构

从 V1 开始，架构进入 **Freeze-by-default** 状态：除非真实代码、测试或 Vertical Slice 暴露问题，否则不继续扩展架构。

### 1. 可证明优先于“看起来设计正确”

架构文档中的设计首先是假设。

最终必须由以下内容证明：

```text
Incident Investigator
+
Knowledge Assistant
+
Scenario Test
+
Evaluation
+
Dogfooding
```

如果一个抽象无法自然服务真实 Vertical Slice，应允许修改甚至删除，而不是为了维护旧文档强行保留。

### 2. V1 以工程质量而不是功能数量衡量

0.1.0 不追求功能最多，重点验证：

- `./mvnw clean verify` 稳定通过；
- Core Dependency Direction 有 Architecture Test；
- 关键 Runtime Flow 有 Scenario Test；
- 两个 Example 可真实运行；
- 新用户能够快速启动；
- Framework Error 可理解、可修复；
- Agent Execution 可观测；
- 不存在大量只为未来预留的空接口和空模块。

### 3. Public API 宁少勿多

Public API 是长期兼容成本。

原则：

```text
先 internal
-> 真实场景证明
-> 再 public
```

优先稳定：

- AgentClient
- 必要 Definition
- Tool / Retrieval 等明确扩展 Contract

SessionLog、Transition、Journal、内部 Runtime Model 等没有真实外部扩展需求时，不要过早公开。

### 4. Failure 是正常运行路径

设计成功路径时必须同时考虑：

- Timeout
- Cancellation
- Duplicate Invocation
- Partial Failure
- Retry
- Dependency Failure
- Budget Exceeded
- Resume Preconditions Changed
- Reranker / Retriever Degradation

V1 不要求一次实现全部恢复机制，但 Runtime Contract 不得阻止未来正确实现这些语义。

### 5. 每个 Milestone 必须 Dogfooding

框架作者必须真实使用两个 Example，而不是只看测试。

至少验证：

```text
Knowledge Assistant -> 查询真实项目知识
Incident Investigator -> 跑完整故障分析 Case
```

如果框架作者自己使用 API 都觉得绕，应优先优化 DX，而不是要求用户理解更多内部概念。

### 6. 保留删除抽象的权利

每次 Phase Review 必须问：

> 如果删除这个抽象，系统是否更简单，而且当前需求仍然成立？

新增抽象、Module、Interface 前必须回答：

> 现在谁在使用它？

> 如果不增加它，哪个当前需求无法正确实现？

无法明确回答时，不增加。

### 7. 架构修改触发条件

V1 Freeze 后，Architecture Change 必须至少由以下一种证据触发：

- Vertical Slice 无法自然实现；
- 测试暴露语义缺陷；
- DX 明显恶化；
- Failure / Recovery 无法正确表达；
- Architecture Fitness Rule 无法满足；
- 当前真实需求无法在现有边界中合理实现。

“以后可能需要”不是充分理由。

### 8. 0.1.0 前禁止扩张项

在两个 Vertical Slice 和核心工程质量通过前，不启动：

```text
AgentScope Integration
Multi-Agent
A2A
GraphRAG
Wiki Compiler
Web Console
Distributed Runtime
Enterprise RBAC
```

### 9. 最终质量判断

Arctra 不以“拥有多少 Agent 能力”为成功标准。

成功标准是：

> 外部 API 足够简单，内部 Runtime Semantics 足够稳定；
> 能扩展但不过度抽象；
> Agent 能测试、能治理、能恢复、能观测；
> 每个重要设计都能被真实代码和测试证明。
