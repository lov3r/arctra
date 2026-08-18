package poc;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Critical PoC: Tool Calling + Memory Integration Verification
 *
 * Purpose: MUST verify actual behavior of tool calls with ChatMemory
 *
 * Key Questions (MUST ANSWER):
 * 1. Do tool call messages enter ChatMemory?
 * 2. Do tool response messages enter ChatMemory?
 * 3. What is the EXACT message sequence?
 * 4. How do MessageChatMemoryAdvisor and ToolCallingAdvisor interact?
 * 5. What is the advisor ordering?
 */
public class Step4_ToolCallingMemoryIntegration {

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("M2-T1 Extended PoC: Tool Calling + Memory Integration");
        System.out.println("=".repeat(80));

        testToolCallingWithMemory();
    }

    private static void testToolCallingWithMemory() {
        System.out.println("\n[CRITICAL TEST] Tool Calling + ChatMemory Integration\n");

        // Create ChatMemory
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(100)
            .build();

        // Create FakeChatModel with tool calling simulation
        ToolCallingFakeChatModel fakeModel = new ToolCallingFakeChatModel();

        // Create ChatClient with BOTH advisors
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
            .build();

        ChatClient chatClient = ChatClient.builder(fakeModel)
            .defaultAdvisors(memoryAdvisor)
            .build();

        String conversationId = "tool-test-conv";

        // Turn 1: User asks question that triggers tool call
        System.out.println("=== TURN 1 ===");
        System.out.println("User: What is the weather in Beijing?");

        try {
            // Define a fake weather tool
            FunctionCallback weatherTool = FunctionCallback.builder()
                .function("getWeather", (Function<WeatherRequest, String>) req -> {
                    System.out.println("  [TOOL EXECUTION] getWeather(" + req.city() + ")");
                    return "Temperature: 25°C, Sunny";
                })
                .description("Get weather for a city")
                .inputType(WeatherRequest.class)
                .build();

            String response1 = chatClient.prompt()
                .user("What is the weather in Beijing?")
                .advisors(spec -> spec.param("conversationId", conversationId))
                .functions(weatherTool)
                .call()
                .content();

            System.out.println("Assistant: " + response1);

        } catch (Exception e) {
            System.out.println("❌ Turn 1 failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        // Inspect memory contents
        System.out.println("\n--- Memory Contents After Turn 1 ---");
        inspectMemory(chatMemory, conversationId);

        // Turn 2: Follow-up question (tests history injection)
        System.out.println("\n=== TURN 2 ===");
        System.out.println("User: Is it warmer than yesterday?");

        try {
            String response2 = chatClient.prompt()
                .user("Is it warmer than yesterday?")
                .advisors(spec -> spec.param("conversationId", conversationId))
                .call()
                .content();

            System.out.println("Assistant: " + response2);

        } catch (Exception e) {
            System.out.println("❌ Turn 2 failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // Final memory inspection
        System.out.println("\n--- Memory Contents After Turn 2 ---");
        inspectMemory(chatMemory, conversationId);

        // Analysis
        System.out.println("\n=== ANALYSIS ===");
        analyzeMessageSequence(chatMemory, conversationId);
    }

    private static void inspectMemory(ChatMemory memory, String conversationId) {
        try {
            List<Message> messages = memory.get(conversationId);
            System.out.println("Total messages: " + messages.size());

            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                String typeInfo = msg.getClass().getSimpleName();
                String messageType = msg.getMessageType() != null ? msg.getMessageType().toString() : "NULL";

                // Try to get content
                String content = "N/A";
                try {
                    if (msg instanceof UserMessage um) {
                        content = um.getContent();
                    } else if (msg instanceof AssistantMessage am) {
                        content = am.getContent();
                    } else if (msg instanceof ToolResponseMessage trm) {
                        content = trm.getContent();
                    }
                } catch (Exception e) {
                    content = "[Unable to extract: " + e.getMessage() + "]";
                }

                System.out.printf("  [%d] %s (MessageType=%s)%n", i, typeInfo, messageType);
                if (content.length() > 100) {
                    System.out.println("      Content: " + content.substring(0, 100) + "...");
                } else {
                    System.out.println("      Content: " + content);
                }

                // Check for tool call metadata
                if (msg instanceof AssistantMessage am) {
                    var toolCalls = am.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        System.out.println("      ToolCalls: " + toolCalls.size() + " calls");
                        for (var tc : toolCalls) {
                            System.out.println("        - " + tc.name() + "(" + tc.arguments() + ")");
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("❌ Memory inspection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void analyzeMessageSequence(ChatMemory memory, String conversationId) {
        try {
            List<Message> messages = memory.get(conversationId);

            System.out.println("\nExpected sequence for Turn 1:");
            System.out.println("  [0] UserMessage(\"What is the weather in Beijing?\")");
            System.out.println("  [1] AssistantMessage with ToolCall(getWeather)");
            System.out.println("  [2] ToolResponseMessage(\"Temperature: 25°C...\")");
            System.out.println("  [3] AssistantMessage(\"The weather in Beijing is...\")");

            System.out.println("\nActual sequence:");
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                System.out.println("  [" + i + "] " + msg.getClass().getSimpleName());
            }

            // Verify critical conditions
            System.out.println("\n✓ Verification:");

            boolean hasUserMessage = messages.stream()
                .anyMatch(m -> m instanceof UserMessage);
            System.out.println("  - Has UserMessage: " + (hasUserMessage ? "✅" : "❌"));

            boolean hasAssistantMessage = messages.stream()
                .anyMatch(m -> m instanceof AssistantMessage);
            System.out.println("  - Has AssistantMessage: " + (hasAssistantMessage ? "✅" : "❌"));

            boolean hasToolResponse = messages.stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);
            System.out.println("  - Has ToolResponseMessage: " + (hasToolResponse ? "✅" : "❌"));

            long toolCallCount = messages.stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> (AssistantMessage) m)
                .filter(am -> am.getToolCalls() != null && !am.getToolCalls().isEmpty())
                .count();
            System.out.println("  - AssistantMessages with ToolCalls: " + toolCallCount);

            if (hasUserMessage && hasAssistantMessage && hasToolResponse) {
                System.out.println("\n✅ VERIFIED: Tool calling messages ARE saved to ChatMemory");
            } else {
                System.out.println("\n⚠️  PARTIAL: Some message types missing");
            }

        } catch (Exception e) {
            System.out.println("\n❌ Analysis failed: " + e.getMessage());
        }
    }

    // Weather request record
    record WeatherRequest(String city) {}

    /**
     * Fake ChatModel that simulates tool calling loop
     */
    static class ToolCallingFakeChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            System.out.println("\n  [FakeModel] Received prompt with " +
                prompt.getInstructions().size() + " messages");

            // Check if this is initial call or after tool execution
            boolean hasToolResponse = prompt.getInstructions().stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);

            if (hasToolResponse) {
                // Final response after tool execution
                System.out.println("  [FakeModel] Generating final response (after tool execution)");

                String response = "Based on the weather data, Beijing has a temperature of 25°C and it's sunny.";

                var generation = new Generation(new AssistantMessage(response));
                return new ChatResponse(List.of(generation));

            } else {
                // Initial response - simulate tool call request
                System.out.println("  [FakeModel] Generating tool call request");

                // Create AssistantMessage with tool call
                var toolCall = new AssistantMessage.ToolCall(
                    "call_123",
                    "function",
                    "getWeather",
                    "{\"city\":\"Beijing\"}"
                );

                var assistantMsg = new AssistantMessage("", List.of(toolCall));

                var generation = new Generation(assistantMsg);
                return new ChatResponse(List.of(generation));
            }
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }
}
