# Arctra --- DX 开发约束

## 北极星

**3 分钟理解、10 分钟运行、30 分钟扩展。**

## 第一层心智模型

``` text
Agent = 谁来决策
RAG   = 去哪里找知识
Tool  = 能做什么
```

普通用户不应该先学习：

``` text
ExecutionPlan
SessionLog
Projection
DDD
Shared Kernel
```

## 主要 API

``` java
String answer = agentClient
        .agent("knowledge-agent")
        .user(question)
        .call()
        .content();
```

结构化输出：

``` java
IncidentResult result = agentClient
        .agent("incident-agent")
        .user("order-service 发布后开始 500")
        .call()
        .entity(IncidentResult.class);
```

## API 学习层级

``` text
L1 AgentClient
L2 AgentDefinition / Configuration
L3 Tool / Retriever / Reranker / Policy
L4 ExecutionEngine
L5 AgentRuntime / Framework Extension
```

## Spring-native 扩展

``` java
@Bean
Retriever companyRetriever() { ... }

@Bean
AgentExecutionEngine companyEngine() { ... }
```

不要要求普通用户手工创建 Adapter / Factory / Runtime。

## 错误信息

每个面向用户的框架错误必须回答：

1.  发生了什么？
2.  为什么？
3.  怎么修？

## 文档优先按任务组织

优先：

-   创建 Agent
-   添加 Tool
-   开启 RAG
-   添加 Rerank
-   添加人工审批
-   测试 Agent
-   Debug Agent
-   使用 ExecutionEngine

## Example

不要只做一个巨大 enterprise-demo。

Knowledge：

``` text
basic -> vector -> hybrid -> rerank -> evidence -> evaluation
```

Incident：

``` text
tool -> evidence -> decision -> policy -> HITL -> resume
```

## V1 DX 验收

一个没参与框架开发的 Java/Spring 开发者应该：

-   约 3 分钟理解项目定位。
-   约 10 分钟跑通 Example。
-   约 30 分钟新增 Tool 或 Retriever。
-   遇到错误时不需要阅读框架源码才能知道如何修复。
