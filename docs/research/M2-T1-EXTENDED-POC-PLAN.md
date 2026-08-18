# M2-T1 Extended PoC: Tool Calling + Memory Verification

**Purpose:** Executable verification of Tool Call/Response messages in ChatMemory

**Status:** MUST COMPLETE BEFORE M2-T2 IMPLEMENTATION

---

## Verification Strategy

由于独立 PoC 项目遇到编译器并发问题，改为在主项目 test 中验证。

**Location:** `arctra-runtime-react/src/test/java`

**Approach:**
1. 创建 integration test
2. 使用 FakeChatModel 模拟 tool calling loop
3. 使用真实的 Spring AI ChatMemory + MessageChatMemoryAdvisor
4. 验证实际 message sequence

---

## Test Implementation

创建测试文件：
```
arctra-runtime-react/src/test/java/.../
  ToolCallingMemoryIntegrationTest.java
```

**测试内容：**
1. Turn 1: User message triggers tool call
2. Verify ChatMemory contents after Turn 1
3. Turn 2: Follow-up user message
4. Verify history injection works
5. Verify session isolation

**关键验证点：**
- [ ] UserMessage 是否保存
- [ ] AssistantMessage with ToolCall 是否保存
- [ ] ToolResponseMessage 是否保存
- [ ] Final AssistantMessage 是否保存
- [ ] Advisor ordering behavior
- [ ] Multi-turn continuity

---

## Next Steps

1. 创建实际 test file
2. 运行并记录结果
3. 更新本文档
4. 如果发现问题，调整设计

**禁止猜测，必须 executable verification。**
