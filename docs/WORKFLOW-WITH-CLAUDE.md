# 如何用这些文档驱动 Claude 全程开发

## 一、第一次启动 Claude

在 Repository 根目录打开 Claude Code，发送：

> 你现在是 Arctra 的主实现工程师。
>
> 首先阅读 `CLAUDE.md`，然后按照 `docs/DOCUMENT-MAP.md`
> 阅读所有当前权威文档，重点包括：
> `docs/ARCHITECTURE-V7.md`、`docs/DX-V3.md`、`docs/DEVELOPMENT-PLAN.md`、`docs/project/CURRENT-STATE.md`
> 和 `TASKS.md`。
>
> 架构图用于建立全局心智模型，但具体语义以文本架构和 ACCEPTED ADR 为准。
>
> 当前不要写代码。
>
> 检查 Repository 当前状态，只为 `BOOT-001` 制定执行计划。
>
> 输出： 1. 你对当前阶段的理解； 2. Module Dependency Graph； 3. Java /
> Spring Boot / Spring AI 版本建议和理由； 4. 要创建/修改的文件； 5.
> 每项工作与 BOOT-001 Acceptance Criteria 的对应关系； 6.
> 架构风险和过度设计风险； 7. 是否需要 ADR。
>
> 输出计划后停止，等待我的批准。不要提前执行 BOOT-002 或未来任务。

## 二、批准第一阶段

确认方案后发送：

> 方案批准。只实现 `BOOT-001`。
>
> 严格遵守 `CLAUDE.md`。 完成实现和测试后运行 `./mvnw clean verify`。
> 更新 `TASKS.md` 和 `docs/project/CURRENT-STATE.md`。 最后按照
> `CLAUDE.md` 输出任务完成报告，然后停止。不要自动开始下一个任务。

## 三、以后每个 Task

发送：

> 执行 `TASK-ID`。
>
> 先读取当前代码和项目状态，再做实现。 限制修改范围在本 Task。
> 完成必要测试并执行 verify。 更新 CURRENT-STATE 和 TASKS。
> 不自动开始下一个 Task。

## 四、遇到架构问题

如果涉及：

-   Public API
-   Runtime Contract
-   ExecutionEngine Contract
-   Module Boundary
-   Session
-   Evidence / Decision
-   Checkpoint / Resume
-   新的大型依赖

要求 Claude：

> 停止实现。 使用 `docs/adr/ADR-TEMPLATE.md` 创建 PROPOSED ADR。
> 给出真实可行的方案、优缺点和推荐方案。 在我批准前不要标记
> ACCEPTED，也不要实现。

## 五、独立 Reviewer Claude

每个 Phase 或 Milestone 后，新开一个 Claude Context：

> 你现在只作为独立 Framework Maintainer / Architecture Reviewer。
> 不修改代码。
>
> 阅读 CLAUDE.md、Architecture V7、当前状态、Accepted ADR 和本阶段
> diff。
>
> 尝试证明当前实现是错误的。
>
> 检查： - 过度设计 - 错误抽象 - Public API 泄漏 - Runtime / Engine
> 耦合 - Domain / Infrastructure 越界 - Event Bus 隐式控制流 - Failure /
> Cancellation 语义 - 不必要 Module / Dependency - 不可测试代码
>
> 按 BLOCKER / MAJOR / MINOR / OPTIONAL 输出问题。 不要直接修改。

## 六、Red-Team Claude

Milestone 后再开一个独立 Context：

> 你是该框架的 Red-Team Test Engineer。 不重新设计框架。
>
> 针对当前 Milestone 设计失败场景：
> timeout、cancellation、duplicate、retry、malformed model
> output、dependency unavailable、budget exceeded、policy
> denied、partial failure、invalid state transition。
>
> 给出缺失测试、期望行为和是否应该自动化。

## 七、每天结束

> 对照 TASKS.md 和 CURRENT-STATE.md 核对真实 Repository 状态。
> 不增加新功能。
>
> 输出： - 今天完成什么 - 什么尚未验证 - 是否有 failing test - 是否有
> pending ADR - 当前技术债 - 下一个 READY Task

## 八、人工必须亲自批准

长期保留人工 Gate：

-   Public API
-   AgentRuntime Contract
-   ExecutionEngine Contract
-   Module Boundary
-   Session Model
-   Checkpoint / Resume
-   Evidence / Decision
-   新的大型框架/依赖
-   ADR ACCEPTED

普通测试、文档、Example、Adapter、内部实现，在架构稳定后可以逐渐放权。

## 九、推荐 Claude 使用方式

不需要多个不同 AI 产品。

使用三个独立 Claude Context：

``` text
Builder Claude
  -> 唯一主要写代码

Reviewer Claude
  -> 独立审查架构和 diff

Red-Team Claude
  -> 攻击行为、边界和测试
```

避免多个 Agent 同时大规模修改同一 Repository。

## 十、核心原则

Repository 才是 Source of Truth，不依赖聊天上下文记忆。

每次恢复工作都从：

``` text
CLAUDE.md
+ DOCUMENT-MAP
+ CURRENT-STATE
+ TASKS
+ Accepted ADR
```

恢复。


## 十一、V1 Architecture Freeze Gate

V1 开发开始后，默认不继续扩展架构。

Claude 如果建议新增 Module / Public Interface / Runtime Abstraction，必须先回答：

1. 当前哪个真实 Task / Vertical Slice 正在使用它？
2. 不增加它会导致哪个当前 Acceptance Criteria 无法满足？
3. 能否先 internal 实现？
4. 能否用更小的已有抽象完成？
5. 删除该抽象是否反而让系统更简单？

如果 1、2 无法明确回答，默认拒绝新增。

每个 Milestone 完成后增加一次 Dogfooding + 删除性 Review：

> 真实运行当前 Example；找出难用、难测、难诊断的地方。同时检查哪些抽象、接口、模块可以删除或收缩。不要因为未来可能使用而保留设计。
