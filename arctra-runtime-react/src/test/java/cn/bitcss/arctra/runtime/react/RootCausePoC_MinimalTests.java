package cn.bitcss.arctra.runtime.react;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * Root Cause PoC - Minimal Tests A/B/C
 *
 * Systematically verify Spring AI 2.0.0 behavior
 */
class RootCausePoC_MinimalTests {

    private static final String CONVERSATION_KEY = ChatMemory.CONVERSATION_ID;  // Use Spring AI constant

    // Simple fake model that echoes input count
    private static class SimpleFakeModel implements ChatModel {
        private int callCount = 0;

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount++;
            String response = "Response #" + callCount + " (saw " + prompt.getInstructions().size() + " messages)";
            return new ChatResponse(List.of(
                new Generation(new AssistantMessage(response))
            ));
        }
    }

    // Simple mock tool
    private static class MockTool implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("mockTool")
                .description("A mock tool")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        }

        @Override
        public String call(String functionArguments) {
            return "mock result";
        }
    }

    @Test
    void testA_MemoryWithoutTools() {
        System.out.println("\n=== TEST A: MessageChatMemoryAdvisor WITHOUT tools ===\n");

        var chatModel = new SimpleFakeModel();
        var chatMemory = MessageWindowChatMemory.builder().build();
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        var chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(memoryAdvisor)
            .build();

        try {
            // Turn 1
            String response1 = chatClient.prompt()
                .user("Turn 1 message")
                .advisors(a -> a.param(CONVERSATION_KEY, "session-A"))
                .call()
                .content();

            System.out.println("Turn 1: " + response1);

            // Turn 2
            String response2 = chatClient.prompt()
                .user("Turn 2 message")
                .advisors(a -> a.param(CONVERSATION_KEY, "session-A"))
                .call()
                .content();

            System.out.println("Turn 2: " + response2);
            System.out.println("\n✅ TEST A PASSED: Memory works without tools\n");

        } catch (Exception e) {
            System.out.println("\n❌ TEST A FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    void testB_ToolsWithoutMemory() {
        System.out.println("\n=== TEST B: Tools WITHOUT MessageChatMemoryAdvisor ===\n");

        var chatModel = new SimpleFakeModel();
        var chatClient = ChatClient.builder(chatModel).build();

        try {
            String response = chatClient.prompt()
                .user("Test message")
                .tools(new MockTool())
                .call()
                .content();

            System.out.println("Response: " + response);
            System.out.println("\n✅ TEST B PASSED: Tools work without memory\n");

        } catch (Exception e) {
            System.out.println("\n❌ TEST B FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    void testC_MemoryAndTools_DefaultAdvisors() {
        System.out.println("\n=== TEST C: MessageChatMemoryAdvisor + Tools (defaultAdvisors) ===\n");

        var chatModel = new SimpleFakeModel();
        var chatMemory = MessageWindowChatMemory.builder().build();
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        // Add memory advisor at ChatClient level (defaultAdvisors)
        var chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(memoryAdvisor)
            .build();

        try {
            // Turn 1
            String response1 = chatClient.prompt()
                .user("Turn 1 message")
                .tools(new MockTool())  // Add tools
                .advisors(a -> a.param(CONVERSATION_KEY, "session-C"))  // Set conversationId
                .call()
                .content();

            System.out.println("Turn 1: " + response1);

            // Turn 2
            String response2 = chatClient.prompt()
                .user("Turn 2 message")
                .tools(new MockTool())
                .advisors(a -> a.param(CONVERSATION_KEY, "session-C"))
                .call()
                .content();

            System.out.println("Turn 2: " + response2);
            System.out.println("\n✅ TEST C PASSED: Memory + Tools work together\n");

        } catch (Exception e) {
            System.out.println("\n❌ TEST C FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("\nFull stack trace:");
            e.printStackTrace();

            // Find first Spring AI frame
            System.out.println("\nFirst Spring AI frame:");
            for (StackTraceElement element : e.getStackTrace()) {
                if (element.getClassName().startsWith("org.springframework.ai")) {
                    System.out.println("  " + element);
                    break;
                }
            }
        }
    }
}
