# Arctra --- 当前任务队列

> 这是 Claude 真正执行工作的队列。不要自动执行 Backlog。

流程：

``` text
BACKLOG -> READY -> IN_PROGRESS -> REVIEW -> DONE
```

WIP Limit：**同时只能有 1 个实现任务。**

## BOOT-001 --- Repository Bootstrap

状态：READY

### 目标

创建最小但健康的 V1 Maven Repository Skeleton。

### 非目标

-   不实现 Agent Runtime。
-   不实现 Tool/RAG。
-   不接 AgentScope。
-   不创建未来假设驱动的框架抽象。

### 交付物

``` text
pom.xml
mvnw / mvnw.cmd
arctra-api
arctra-core
arctra-runtime-react
arctra-rag
arctra-tool
arctra-testkit
arctra-spring-boot-starter

examples/
  knowledge-assistant
  incident-investigator
```

工程基线：

-   项目正式名称使用 `Arctra`。
- Maven GroupId 使用 `cn.bitcss.arctra`。
- Java Base Package 使用 `cn.bitcss.arctra`。
- Java 版本确定并记录。
-   Spring Boot / Spring AI 版本确定并记录。
-   JUnit 5。
-   AssertJ。
-   ArchUnit。
-   格式化 / Checkstyle 方案。
-   JaCoCo 基础。
-   GitHub Actions。
-   README Skeleton。
-   第一批架构依赖测试。

### 验收标准

1.  根目录 `./mvnw clean verify` 成功。
2.  所有 V1 Module 都进入 Maven Reactor。
3.  Core 不依赖 Spring Boot。
4.  至少有第一批 Architecture Test 保护核心依赖方向。
5.  CI 执行与本地相同的 verify。
6.  README 一屏内说明项目定位。
7.  更新 `CURRENT-STATE.md`。
8.  不引入真正的 Domain / Runtime 实现。

### 编码前必须输出

Claude 先输出：

-   Module Dependency Graph
-   Java / Spring Boot / Spring AI 版本和选择理由
-   创建/修改文件列表
-   Bootstrap 风险
-   是否需要 ADR

第一轮等待人工批准后再编码。

------------------------------------------------------------------------

## BOOT-002 --- Agent Kernel Domain Skeleton

状态：BACKLOG

只有 BOOT-001 DONE 后才能开始。

目标概念：

-   AgentDefinition
-   AgentRequest
-   AgentResult
-   AgentExecution
-   AgentState
-   AgentBudget
-   ExecutionId

------------------------------------------------------------------------

## BOOT-003 --- AgentClient 最小调用闭环

状态：BACKLOG

目标：使用 Fake Model 跑通最小调用路径，不引入 Tool/RAG。

------------------------------------------------------------------------

## BOOT-004 --- ExecutionEngine Contract

状态：BACKLOG

只有 AgentClient / Runtime 基本语义通过代码验证后才能开始。

------------------------------------------------------------------------

## 后续 Milestone

``` text
M1 Incident Agent MVP
M2 Knowledge Assistant MVP
M3 HITL / Checkpoint / TestKit
M4 0.1.0 Public Preview
M5 0.2.0 AgentScope Integration
```
