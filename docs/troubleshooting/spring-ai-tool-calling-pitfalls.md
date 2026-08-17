# Spring AI Tool Calling 实践踩坑指南

> **作者**: lov3r & jingbo  
> **日期**: 2026-08-17  
> **版本**: Spring AI 2.0.0

## 概述

本文档记录了在实现 `SpringAiToolCallingEngine` 过程中遇到的所有坑，以及解决方案。这些坑花费了大量时间调试，希望能帮助后来者避免重复踩坑。

---

## 🔥 核心问题总结

### 问题 1: `.tools()` 方法的 varargs 参数传递错误

**症状**：
- Tool calling loop 没有触发
- ChatModel 只被调用一次
- 工具从未被执行
- 没有任何错误提示

**错误代码**：
```java
List<ToolCallback> tools = ...;
chatClient.prompt()
    .user("...")
    .tools((Object) tools.toArray(new ToolCallback[0]))  // ❌ 强制转换成 Object
    .call()
    .content();
```

**问题原因**：
- `.tools()` 方法签名是 `tools(Object... tools)` —— 可变参数（varargs）
- 强制转换成 `(Object)` 后，整个**数组变成了单个 Object 参数**
- ChatClient 接收到的是：1 个参数（一个数组对象），而不是 N 个 ToolCallback
- 导致 tools 没有被正确注册

**正确写法**：
```java
chatClient.prompt()
    .user("...")
    .tools(tools.toArray(new ToolCallback[0]))  // ✅ varargs 自动展开
    .call()
    .content();
```

**调试发现过程**：
1. 最初以为需要 Spring Boot 自动装配
2. 尝试手动创建 OpenAiChatModel 失败（构造函数不对）
3. 对比参考项目的代码，发现 `.tools()` 直接传入多个对象
4. 意识到 varargs 的问题

**教训**：
- Java varargs 不要用 `(Object)` 强制转换
- 参考官方示例时，注意方法签名

---

### 问题 2: ToolDefinition 缺少 inputSchema

**症状**：
```
java.lang.IllegalArgumentException: inputSchema cannot be null or empty
    at org.springframework.ai.tool.definition.ToolDefinition.<init>
```

**错误代码**：
```java
ToolDefinition.builder()
    .name("queryLogs")
    .description("Query application logs")
    .build();  // ❌ 缺少 inputSchema
```

**问题原因**：
- Spring AI 2.0 **强制要求** ToolDefinition 必须包含 `inputSchema`
- inputSchema 是 JSON Schema 格式，描述工具的输入参数
- 即使工具不需要参数，也必须提供一个空的 schema

**正确写法**：
```java
ToolDefinition.builder()
    .name("queryLogs")
    .description("Query application logs")
    .inputSchema("""
        {
          "type": "object",
          "properties": {
            "timeRange": {
              "type": "string",
              "description": "Time range to query"
            }
          },
          "required": []
        }
        """)
    .build();
```

**无参数工具的 schema**：
```java
.inputSchema("""
    {
      "type": "object",
      "properties": {},
      "required": []
    }
    """)
```

**教训**：
- Spring AI 1.x → 2.0 的 **Breaking Change**
- 必须为每个工具定义 JSON Schema

---

### 问题 3: ChatModel 没有返回 ToolCallingChatOptions

**症状**：
- ToolCallingAdvisor 被添加了，但直接跳过
- Tool calling loop 没有执行
- Fake ChatModel 测试失败

**问题代码**：
```java
public class FakeChatModel implements ChatModel {
    @Override
    public ChatResponse call(Prompt prompt) {
        // 返回包含 ToolCall 的响应
        return new ChatResponse(...);
    }
    
    @Override
    public ChatOptions getDefaultOptions() {
        return null;  // ❌ 返回 null
    }
}
```

**问题原因**：
ToolCallingAdvisor 内部有能力检测逻辑：

```java
ChatOptions options = chatClientRequest.prompt().getOptions();
if (!(options instanceof ToolCallingChatOptions toolCallingChatOptions)) {
    // 模型不支持 tool calling - 跳过 advisor
    return callAdvisorChain.nextCall(chatClientRequest);
}
```

- 如果 `getDefaultOptions()` 返回 `null` 或不是 `ToolCallingChatOptions` 类型
- ToolCallingAdvisor 会认为模型不支持 tool calling
- 直接跳过，不执行 tool calling loop

**正确写法**：
```java
@Override
public ChatOptions getDefaultOptions() {
    // 返回 ToolCallingChatOptions 的实现类
    return OpenAiChatOptions.builder().build();
}
```

**设计意图**：
这是 Spring AI 的**优雅降级**设计：
- 不是所有模型都支持 tool calling（如某些开源模型）
- 通过 options 类型进行能力检测（类型安全）
- 不支持的模型自动跳过，而不是报错

**调试发现过程**：
1. 真实 OpenAI 测试成功，但 Fake 失败
2. 怀疑是 Spring Boot 自动装配的问题
3. 创建最小化测试，发现 ToolCallingAdvisor 确实被添加
4. 断点调试 ToolCallingAdvisor，发现 `instanceof` 检查失败
5. 查看 OpenAiChatOptions 的类型，发现实现了 ToolCallingChatOptions
6. 修改 Fake 返回 OpenAiChatOptions，问题解决

**教训**：
- 实现自定义 ChatModel 时，必须正确实现 `getDefaultOptions()`
- 不要返回 `null`，返回对应的 options 实现类

---

### 问题 4: 对 OpenAiChatModel 构造方式的误解

**症状**：
- 尝试手动创建 OpenAiChatModel 时编译失败
- 找不到 `OpenAiApi` 类

**错误尝试**：
```java
// ❌ 错误 1: OpenAiApi 类不存在
var api = new OpenAiApi(baseUrl, apiKey);
var model = new OpenAiChatModel(api);

// ❌ 错误 2: 构造函数签名不对
var model = new OpenAiChatModel(baseUrl, apiKey, model);
```

**问题原因**：
- 凭记忆猜测 API，没有查看文档或参考代码
- Spring AI 2.0 的 API 与 1.x 不同

**正确写法**：
```java
// ✅ 使用 builder 模式
ChatModel chatModel = OpenAiChatModel.builder()
    .options(OpenAiChatOptions.builder()
        .baseUrl("https://api.openai.com/v1")
        .apiKey("sk-...")
        .model("gpt-4o-mini")
        .temperature(0.7)
        .build())
    .build();
```

**发现过程**：
1. 参考项目中有 `ChatModelConfig.java`
2. 看到 `OpenAiChatModel.builder()` 和 `OpenAiChatOptions.builder()` 的用法
3. 意识到应该用 builder 模式

**教训**：
- **先看文档/参考代码，再写代码**
- 不要凭记忆或猜测 API
- Builder 模式是 Spring AI 2.0 的标准

---

## 🎯 最佳实践总结

### 1. 正确实现 ToolCallback

```java
public class MyTool implements ToolCallback {
    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("myTool")
            .description("Clear description of what this tool does")
            .inputSchema("""
                {
                  "type": "object",
                  "properties": {
                    "param1": {
                      "type": "string",
                      "description": "Parameter description"
                    }
                  },
                  "required": ["param1"]
                }
                """)
            .build();
    }

    @Override
    public String call(String functionArguments) {
        // 解析参数
        // 执行逻辑
        // 返回结果（JSON 字符串）
        return "{\"result\": \"...\"}";
    }
}
```

**关键点**：
- `inputSchema` 必须是合法的 JSON Schema
- `description` 越详细，LLM 越能正确选择和使用工具
- `call()` 的参数是 JSON 字符串，需要手动解析

---

### 2. 正确传递多个 Tools

```java
// ✅ 方式 1: 直接传入多个 ToolCallback
chatClient.prompt()
    .user("...")
    .tools(tool1, tool2, tool3)
    .call()
    .content();

// ✅ 方式 2: 从 List 转换
List<ToolCallback> tools = List.of(tool1, tool2, tool3);
chatClient.prompt()
    .user("...")
    .tools(tools.toArray(new ToolCallback[0]))  // varargs 展开
    .call()
    .content();

// ❌ 错误: 强制转换成 Object
.tools((Object) tools.toArray(new ToolCallback[0]))
```

---

### 3. 手动创建 OpenAiChatModel（不依赖 Spring Boot）

```java
// 创建 ChatModel
ChatModel chatModel = OpenAiChatModel.builder()
    .options(OpenAiChatOptions.builder()
        .baseUrl("https://api.openai.com/v1")
        .apiKey("sk-...")
        .model("gpt-4o-mini")
        .temperature(0.7)
        .build())
    .build();

// 创建 ChatClient
ChatClient chatClient = ChatClient.builder(chatModel).build();

// 使用
String response = chatClient.prompt()
    .user("...")
    .tools(tool1, tool2)
    .call()
    .content();
```

**不需要**：
- ❌ Spring Boot 自动装配
- ❌ `spring-ai-starter-model-openai`（如果手动构建）
- ❌ `@Configuration` 类

**只需要**：
- ✅ `spring-ai-openai` 依赖
- ✅ 正确的 builder 调用

---

### 4. 实现 Fake ChatModel 用于测试

```java
public class FakeChatModel implements ChatModel {
    private int callCount = 0;

    @Override
    public ChatResponse call(Prompt prompt) {
        callCount++;
        
        // 检查是否是第二轮调用（工具已执行）
        boolean hasToolResponse = prompt.getInstructions().stream()
            .anyMatch(msg -> msg.getMessageType() == MessageType.TOOL);

        if (hasToolResponse) {
            // 返回最终答案
            return new ChatResponse(List.of(
                new Generation(new AssistantMessage("Final answer"))
            ));
        } else {
            // 返回 ToolCall，触发工具执行
            var message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                    new AssistantMessage.ToolCall(
                        "call_id_123",
                        "function",
                        "toolName",
                        "{}"
                    )
                ))
                .build();
            return new ChatResponse(List.of(new Generation(message)));
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // 关键：必须返回 ToolCallingChatOptions 实现类
        return OpenAiChatOptions.builder().build();
    }
}
```

**关键点**：
1. **必须返回 ToolCallingChatOptions**（问题 3）
2. **第一轮返回 ToolCall**，触发工具执行
3. **第二轮检测 TOOL 类型消息**，返回最终答案
4. 模拟真实 ChatModel 的行为

---

## 🔍 调试技巧

### 1. 验证 Tools 是否被注册

```java
// 在 ChatClient 调用前打印
System.out.println("Tools: " + tools.size());
tools.forEach(t -> System.out.println("  - " + t.getToolDefinition().name()));
```

### 2. 验证 Tool Calling Loop 是否触发

在自定义 ToolCallback 中添加日志：

```java
@Override
public String call(String args) {
    System.out.println("[Tool] " + getToolDefinition().name() + " called!");
    // ...
    return result;
}
```

如果工具没有被调用，说明 loop 没有触发。

### 3. 验证 ChatModel 被调用次数

在 Fake ChatModel 中计数：

```java
private int callCount = 0;

@Override
public ChatResponse call(Prompt prompt) {
    callCount++;
    System.out.println("[ChatModel] Call #" + callCount);
    // ...
}
```

- **只调用 1 次** → Tool calling loop 没有触发
- **调用 2+ 次** → Loop 正常工作

### 4. 检查 ChatOptions 类型

```java
ChatOptions options = chatModel.getDefaultOptions();
System.out.println("Options type: " + (options != null ? options.getClass() : "null"));
System.out.println("Is ToolCallingChatOptions: " + 
    (options instanceof ToolCallingChatOptions));
```

如果输出 `false`，说明 ToolCallingAdvisor 会跳过。

---

## 📚 相关资源

### Spring AI 文档
- [Tool Calling (Function Calling)](https://docs.spring.io/spring-ai/reference/api/tool-calling.html)
- [OpenAI Chat API](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)

### 参考项目
- [spring-ai-best-practice](https://github.com/javastacks/spring-ai-best-practice)
  - `cn/javastack/ai/config/ChatModelConfig.java` - 手动创建 ChatModel
  - `cn/javastack/ai/controller/ToolCallingController.java` - 使用 .tools()

### 相关 Issue
- Spring AI GitHub Issues 中搜索 "tool calling"
- 注意版本差异（1.x vs 2.0）

---

## 💡 设计洞察

### 为什么 Spring AI 要检查 ToolCallingChatOptions？

这是**能力检测（Capability Detection）**的设计模式：

1. **不是所有模型都支持 tool calling**
   - OpenAI GPT-4: ✅ 支持
   - Claude 3: ✅ 支持
   - 某些开源模型: ❌ 不支持

2. **类型安全的能力声明**
   - 通过 `implements ToolCallingChatOptions` 声明能力
   - 运行时 `instanceof` 检查

3. **优雅降级**
   - 不支持的模型自动跳过 advisor
   - 不会抛出异常，不会破坏流程

4. **避免运行时错误**
   - 如果强行向不支持的模型发送 tool calls，会失败
   - 提前检测，避免浪费 API 调用

**这是一个值得学习的设计模式**：用类型系统表达能力，而不是运行时检查字符串或配置。

---

## 🎓 总结

### 核心教训

1. **不要凭记忆写 API**
   - 先查文档、参考代码
   - Builder 模式是标准

2. **理解 varargs 的陷阱**
   - 不要用 `(Object)` 强制转换
   - 让编译器自动展开

3. **Breaking Changes 要关注**
   - Spring AI 2.0 强制要求 inputSchema
   - 升级时注意迁移指南

4. **深入理解框架的能力检测机制**
   - 为什么要检查 ToolCallingChatOptions
   - 如何实现 Fake 来测试

5. **调试时要系统化**
   - 从最小化测试开始
   - 逐层验证每个环节
   - 不要一开始就怀疑框架

### 时间成本

这些坑累计花费了 **约 4-5 小时** 调试时间：
- 问题 1（varargs）: ~2 小时
- 问题 2（inputSchema）: ~30 分钟
- 问题 3（ToolCallingChatOptions）: ~1.5 小时
- 问题 4（构造方式）: ~30 分钟

如果提前知道这些坑，**可以节省 90% 的时间**。

---

## 📝 版本历史

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-08-17 | 1.0 | 初始版本，记录 M1 实现过程中的所有坑 |

---

**希望这份文档能帮助后来者少走弯路！** 🚀
