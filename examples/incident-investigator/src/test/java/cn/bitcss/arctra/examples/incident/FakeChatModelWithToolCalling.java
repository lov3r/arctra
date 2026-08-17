package cn.bitcss.arctra.examples.incident;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * Fake ChatModel that simulates tool calling behavior
 *
 * <p>Behavior:
 * <ul>
 *   <li>First call: returns AssistantMessage with ToolCalls (triggers tool execution)
 *   <li>Second call: returns final text response (after tools executed)
 * </ul>
 *
 * <p>KEY: Must return ToolCallingChatOptions to enable ToolCallingAdvisor!
 *
 * @author lov3r
 */
public class FakeChatModelWithToolCalling implements ChatModel {

  private int callCount = 0;

  @Override
  public ChatResponse call(Prompt prompt) {
    callCount++;

    System.out.println("\n[FakeChatModel] Call #" + callCount);
    System.out.println("[FakeChatModel] Messages in prompt: " + prompt.getInstructions().size());

    // 检查是否有 ToolResponseMessage（说明工具已经被调用了）
    boolean hasToolResponse = prompt.getInstructions().stream()
        .anyMatch(msg -> msg.getMessageType() == MessageType.TOOL);

    if (hasToolResponse) {
      // 第二轮：工具已经执行，返回最终答案
      System.out.println("[FakeChatModel] Tools executed, returning final response");
      return new ChatResponse(List.of(
          new Generation(new AssistantMessage(
              "根据日志和部署信息分析，16:20 开始的 500 错误是由于 v1.2.3 部署引入了 user_status 字段，但数据库表未同步更新导致的 SQLException。"
          ))
      ));
    } else {
      // 第一轮：返回 ToolCall，触发工具调用
      System.out.println("[FakeChatModel] First call, requesting tool execution");

      // 使用 builder 创建 AssistantMessage，包含 ToolCall
      var assistantMessage = AssistantMessage.builder()
          .content("")
          .toolCalls(List.of(
              new AssistantMessage.ToolCall(
                  "call_queryLogs_001",
                  "function",
                  "queryLogs",
                  "{}"
              ),
              new AssistantMessage.ToolCall(
                  "call_getDeployment_002",
                  "function",
                  "getDeployment",
                  "{}"
              )
          ))
          .build();

      return new ChatResponse(List.of(new Generation(assistantMessage)));
    }
  }

  @Override
  public @NonNull ChatOptions getOptions() {
    // KEY: Return ToolCallingChatOptions to enable ToolCallingAdvisor!
    return ToolCallingChatOptions.builder().build();
  }
}
