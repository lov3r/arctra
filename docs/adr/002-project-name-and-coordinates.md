# ADR-002 — 项目名称与 Maven/Java 命名空间

状态：ACCEPTED

## 最终决策

```text
Project: Arctra
Domain: bitcss.cn
Organization Namespace: cn.bitcss
Maven GroupId: cn.bitcss.arctra
Java Base Package: cn.bitcss.arctra
Module Prefix: arctra-
```

## 原因

1. `bitcss.cn` 是长期控制的开源组织/技术品牌域名。
2. 使用反向域名 `cn.bitcss` 作为组织级公共命名空间。
3. Arctra 增加独立项目层，形成 `cn.bitcss.arctra`。
4. 品牌与组织身份分离：`Arctra` 是项目品牌，`bitcss.cn` / `cn.bitcss` 是组织与发布身份。
5. 不使用个人邮箱作为 Java/Maven namespace。
6. 不使用当前未控制的 `io.arctra` 等 namespace。

## V1 模块

```text
arctra-api
arctra-core
arctra-runtime-react
arctra-rag
arctra-tool
arctra-testkit
arctra-spring-boot-starter
```

## 冻结规则

V1 开始后，Project Name、Domain、GroupId、Base Package、Module Prefix 默认冻结。

除明确的域名所有权、法律或商标问题外，不再修改。
