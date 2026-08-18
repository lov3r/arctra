package poc;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PoC Step 3: Multi-turn Conversation with FakeChatModel
 *
 * Purpose: Verify actual multi-turn behavior WITHOUT needing OpenAI API key
 *
 * Key Questions:
 * 1. How to pass conversationId at call time?
 * 2. Are UserMessage + AssistantMessage automatically saved?
 * 3. Are tool call messages saved?
 * 4. Does history injection work correctly?
 * 5. Session isolation?
 */
public class Step3_MultiTurnWithFakeModel {

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("M2-T1 PoC Step 3: Multi-Turn Conversation Verification");
        System.out.println("=".repeat(80));

        testBasicMultiTurn();
        testSessionIsolation();
        testMemoryContents();
    }

    private static void testBasicMultiTurn() {
        System.out.println("\n[TEST 1] Basic Multi-Turn Conversation\n");

        // Create ChatMemory
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(100)
            .build();

        // Create FakeChatModel that echoes history size
        FakeChatModel fakeModel = new FakeChatModel();

        // Create ChatClient with memory advisor
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
            .build();

        ChatClient chatClient = ChatClient.builder(fakeModel)
            .defaultAdvisors(memoryAdvisor)
            .build();

        // Turn 1
        System.out.println("--- Turn 1 ---");
        String conversationId = "test-conversation-1";

        // Question: How to pass conversationId?
        // Try different approaches:

        // Approach A: Via advisorContext?
        try {
            String response1 = chatClient.prompt()
                .user("Hello, my name is Alice")
                .advisors(spec -> spec
                    .param("conversationId", conversationId)  // Guess 1
                )
                .call()
                .content();

            System.out.println("Response: " + response1);
            System.out.println("✅ Approach A (advisorContext param) worked");

        } catch (Exception e) {
            System.out.println("❌ Approach A failed: " + e.getClass().getSimpleName());

            // Approach B: Try different key name
            try {
                String response1 = chatClient.prompt()
                    .user("Hello, my name is Alice")
                    .advisors(spec -> spec
                        .param(MessageChatMemoryAdvisor.CONVERSATION_ID_KEY, conversationId)  // Guess 2
                    )
                    .call()
                    .content();

                System.out.println("Response: " + response1);
                System.out.println("✅ Approach B (CONVERSATION_ID_KEY constant) worked");

            } catch (Exception e2) {
                System.out.println("❌ Approach B also failed: " + e2.getClass().getSimpleName());

                // Log all available constants in MessageChatMemoryAdvisor
                System.out.println("\nAvailable constants in MessageChatMemoryAdvisor:");
                for (var field : MessageChatMemoryAdvisor.class.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) &&
                        java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                        try {
                            System.out.println("  - " + field.getName() + " = " + field.get(null));
                        } catch (Exception ignore) {}
                    }
                }
            }
        }

        // Check memory contents
        System.out.println("\nMemory after Turn 1:");
        List<Message> messages = chatMemory.get(conversationId);
        System.out.println("  Message count: " + messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            System.out.println("  [" + i + "] " + msg.getMessageType() + ": " +
                msg.getContent().substring(0, Math.min(50, msg.getContent().length())));
        }
    }

    private static void testSessionIsolation() {
        System.out.println("\n[TEST 2] Session Isolation\n");
        // To be implemented after understanding conversationId passing
        System.out.println("⏸️  Deferred until conversationId passing is understood");
    }

    private static void testMemoryContents() {
        System.out.println("\n[TEST 3] Memory Contents Inspection\n");
        // To be implemented
        System.out.println("⏸️  Deferred until basic multi-turn works");
    }

    /**
     * Fake ChatModel for testing without API key
     */
    static class FakeChatModel implements ChatModel {
        private final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(
            org.springframework.ai.chat.prompt.Prompt prompt
        ) {
            int count = callCount.incrementAndGet();
            int historySize = prompt.getInstructions().size() - 1; // -1 for current message

            String response = String.format(
                "[FakeModel Call #%d] I received %d message(s). Current: '%s'",
                count,
                prompt.getInstructions().size(),
                prompt.getInstructions().get(prompt.getInstructions().size() - 1).getContent()
            );

            var generation = new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(response)
            );

            return new org.springframework.ai.chat.model.ChatResponse(
                List.of(generation),
                org.springframework.ai.chat.model.ChatResponseMetadata.empty()
            );
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }
}
