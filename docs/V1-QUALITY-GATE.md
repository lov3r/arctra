# V1 最终工程质量检查表

每个 Milestone 都检查：

-   [ ] 当前设计被真实 Vertical Slice 使用，而不是只存在于接口中。
-   [ ] `./mvnw clean verify` 通过。
-   [ ] 核心依赖方向有 Architecture Test。
-   [ ] 关键成功/失败路径有 Scenario Test。
-   [ ] Timeout / Cancellation / Failure 等语义没有被隐藏。
-   [ ] Example 可运行并完成一次 Dogfooding。
-   [ ] Public API 没有不必要扩张。
-   [ ] 没有只为未来假设存在的 Module / Interface。
-   [ ] 错误信息能说明发生什么、为什么、如何修复。
-   [ ] 关键 Execution 能被观测和诊断。
-   [ ] 检查过"哪些东西可以删除"。
-   [ ] CURRENT-STATE / TASKS / ADR 与代码一致。

## 0.1.0 发布 Gate

发布前必须证明：

1.  Incident Investigator Vertical Slice 完整。
2.  Knowledge Assistant Vertical Slice 完整。
3.  TestKit 能测试 Agent，而无需真实外部系统。
4.  Core 不依赖具体基础设施和 ExecutionEngine。
5.  README / Example 能让陌生开发者快速运行。
6.  Framework 的核心亮点有 Scenario / Evaluation
    证据，而不只是设计文档。
