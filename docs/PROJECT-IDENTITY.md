# Arctra 项目标识

## 品牌与定位

```text
Arctra
A Spring-native Agent Engineering Harness.
```

中文定位：

> 面向 Spring 生态的 Agent Engineering Harness，用统一方式运行、测试、治理、恢复、评估和观测不同 Agent 执行引擎。

## 组织 / 域名

```text
Domain: bitcss.cn
Organization Namespace: cn.bitcss
```

## Maven Coordinates

```xml
<groupId>cn.bitcss.arctra</groupId>
```

示例：

```xml
<dependency>
    <groupId>cn.bitcss.arctra</groupId>
    <artifactId>arctra-spring-boot-starter</artifactId>
    <version>${arctra.version}</version>
</dependency>
```

## Java Base Package

```text
cn.bitcss.arctra
```

建议领域包：

```text
cn.bitcss.arctra.api
cn.bitcss.arctra.runtime
cn.bitcss.arctra.tool
cn.bitcss.arctra.retrieval
cn.bitcss.arctra.evidence
cn.bitcss.arctra.policy
```

不要为了 Maven Module 机械映射 Java Package。

## Module Naming

```text
arctra-api
arctra-core
arctra-runtime-react
arctra-rag
arctra-tool
arctra-testkit
arctra-spring-boot-starter
```

## Repository

```text
arctra
```

品牌、Repository、Artifact 使用 `arctra`；组织/发布身份使用 `bitcss.cn` / `cn.bitcss`。
