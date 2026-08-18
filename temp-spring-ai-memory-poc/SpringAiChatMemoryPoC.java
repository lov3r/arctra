package temp;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * PoC to verify Spring AI 2.0.0 ChatMemory API surface.
 *
 * This is NOT production code - just API exploration.
 *
 * Purpose: Understand how ChatMemory works with Spring AI 2.0.0:
 * - How to create ChatMemory
 * - How to associate it with a conversationId
 * - How to integrate with ChatClient
 * - How messages are stored and retrieved
 * - Whether tool call messages are included
 * - How to use with Advisors
 */
public class SpringAiChatMemoryPoC {

    public void exploreChatMemoryApi(ChatModel chatModel) {

        // Question 1: How to create ChatMemory?
        ChatMemory memory = new InMemoryChatMemory();

        // Question 2: How to manually add messages?
        // memory.add(conversationId, messages);

        // Question 3: How to retrieve messages?
        // List<Message> history = memory.get(conversationId, maxMessages);

        // Question 4: How to integrate with ChatClient?
        // ChatClient chatClient = ChatClient.builder(chatModel)
        //     .defaultAdvisors(???)
        //     .build();

        // Question 5: Is there a MessageChatMemoryAdvisor?
        // Question 6: How to pass conversationId at call time?
        // Question 7: Does memory automatically save assistant replies?
        // Question 8: Do tool call messages go into memory?
    }

    // This code will NOT compile - it's for API exploration
    // We need to check actual Spring AI 2.0.0 API docs or source
}
