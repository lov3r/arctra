# 文档地图

## 权威文档

  -----------------------------------------------------------------------
  文档                                作用
  ----------------------------------- -----------------------------------
  `CLAUDE.md`                         AI 开发宪法、工作纪律、最高约束

  `docs/adr/*.md` 中状态为 `ACCEPTED` 已确认的长期架构决策
  的 ADR                              

  `docs/ARCHITECTURE-V7.md`           当前完整架构基线

  `docs/DX-V3.md`                     当前完整开发者体验设计

  `TASKS.md`                          当前真正允许执行的任务队列

  `docs/project/CURRENT-STATE.md`     当前代码库状态与进度 Source of
                                      Truth
  -----------------------------------------------------------------------

## 执行类文档

  文档                             作用
  -------------------------------- --------------------------
  `docs/DEVELOPMENT-PLAN.md`       阶段、里程碑、时间规划
  `docs/WORKFLOW-WITH-CLAUDE.md`   人如何驱动 Claude 工作
  `docs/DX.md`                     开发时快速查阅的 DX 约束
  `docs/ARCHITECTURE.md`           架构入口和优先级说明

## 架构图

`docs/architecture/arctra-v7-panorama.png`

架构图用于快速建立全局心智模型。

**不能仅根据图片推导具体运行语义。** 图片与文本发生差异时，以
`CLAUDE.md`、已 ACCEPTED ADR、`ARCHITECTURE-V7.md` 为准。

## 当前语言策略

V1 阶段：

-   架构文档以中文为主。
-   开发流程文档以中文为主。
-   README 第一版可以先中文。
-   Java 类型、接口、方法、模块名保持标准英文命名。
-   代码注释只在确有必要时添加，不要求为了"中文化"大量写中文注释。
-   国际化和英文文档在 V1 可运行、可测试、可发布之后再处理。


## V1 质量 Gate

`docs/V1-QUALITY-GATE.md`：每个 Milestone 和 0.1.0 发布前必须检查的工程质量清单。


## M2 Session & Multi-Turn 设计文档

### 规划与研究
- `docs/planning/M2-PHASE-PLANNING.md` - M2 原始规划（部分已被 SUPERSEDED）
- `docs/planning/M2-T2-CONTRACT-GATE-V2.md` - M2-T2 最终架构决策
- `docs/research/M2-COMPETITIVE-ARCHITECTURE-RESEARCH.md` - AgentScope/Spring AI Session API 研究
- `docs/research/M2-T1-POC-REPORT.md` - Spring AI ChatMemory PoC 验证报告
- `docs/design/M2-SESSION-FRAMEWORK-ANALYSIS.md` - Session 语义分析
- `docs/design/M2-T2-AGENT-EXECUTION-CONTEXT-DESIGN.md` - AgentExecutionContext 设计决策

### 实现报告
- `docs/implementation/M2-T2-IMPLEMENTATION-REPORT.md` - M2-T2 实现总结

---

`docs/PROJECT-IDENTITY.md`：Arctra 品牌名、Maven Coordinates、Java Base Package 和模块命名的唯一参考。

`docs/adr/002-project-name-and-coordinates.md`：项目命名与命名空间的已接受决策。
