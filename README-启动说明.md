# Arctra --- Claude 中文启动包

当前阶段不考虑国际化。

目标是先完成一个：

``` text
能运行
能测试
能展示
能开源 Preview
```

的第一版。

## 第一次启动

在项目根目录启动 Claude Code，然后发送：

> 你现在是 Arctra 的主实现工程师。
>
> 阅读 `CLAUDE.md` 和
> `docs/DOCUMENT-MAP.md`，并按文档优先级建立项目上下文。
>
> 当前不要编码。
>
> 只分析 `TASKS.md` 中第一个 READY Task：`BOOT-001`。
>
> 检查 Repository 后输出： 1. 当前阶段理解； 2. Module Dependency
> Graph； 3. Java / Spring Boot / Spring AI 版本建议； 4. 文件修改计划；
> 5. Acceptance Criteria 对照； 6. 架构风险； 7. 是否需要 ADR。
>
> 输出后停止，等待我的批准。

确认计划后发送：

> 批准。只执行 BOOT-001。 完成代码、测试、verify、CURRENT-STATE 和 TASKS
> 更新后停止。 不要自动执行下一个 Task。

## 重要原则

不要让 Claude 一次实现整个 Roadmap。

正确节奏：

``` text
Architecture
  ↓
Task
  ↓
Claude Plan
  ↓
人工 Gate（架构敏感任务）
  ↓
Implementation
  ↓
Test / Verify
  ↓
Review
  ↓
CURRENT-STATE
  ↓
Next Task
```
