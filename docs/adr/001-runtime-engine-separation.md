# ADR-001 --- Agent Runtime 与 Execution Engine 分离

状态：ACCEPTED

## 背景

Arctra 需要拥有自己的轻量 Native ReAct，同时未来允许接入
AgentScope、Graph Engine、Embabel 等执行机制。

如果把某个第三方 Runtime
直接作为框架内核，它的运行语义会侵入整个项目，降低可替换性。

## 决策

`AgentRuntime` 掌握公共工程运行语义。

`AgentExecutionEngine` 是能力扩展边界，负责具体执行机制。

Core 不依赖任何具体 Execution Engine。

V1 只实现 Native ReAct。第三方 Engine Integration 必须等 Runtime
Contract 被真实代码证明后再做。

## 正向影响

-   稳定的业务调用 API。
-   Engine 可替换。
-   Policy / Evidence / Observability 等语义统一。
-   可以复用第三方 Agent Framework，而不让其拥有框架内核。

## Trade-off

-   Engine Contract 必须谨慎设计。
-   Engine 特有能力需要 Capability Negotiation。
-   必须避免为了统一而设计成"最低公共分母"。

## 验证

在接 AgentScope 前必须证明：

-   Native ReAct 完整运行。
-   AgentClient 不暴露 Engine-specific API。
-   Core 不依赖具体 Engine。
