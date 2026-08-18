package poc;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

/**
 * PoC 1: Basic ChatMemory API Discovery
 *
 * Purpose: Discover what ChatMemory API actually exists in Spring AI 2.0.0
 *
 * Strategy: Use reflection to discover classes without requiring OpenAI API key
 */
public class Step1_DiscoverChatMemoryApi {

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("M2-T1 PoC: Discovering Spring AI 2.0.0 ChatMemory API");
        System.out.println("=".repeat(80));

        discoverChatMemoryClasses();
        discoverChatClientBuilderMethods();
        discoverAdvisorClasses();
    }

    private static void discoverChatMemoryClasses() {
        System.out.println("\n[1] Searching for ChatMemory classes...\n");

        String[] candidateClasses = {
            "org.springframework.ai.chat.memory.ChatMemory",
            "org.springframework.ai.chat.memory.InMemoryChatMemory",
            "org.springframework.ai.chat.memory.MessageChatMemory",
            "org.springframework.ai.chat.memory.MessageWindowChatMemory",
            "org.springframework.ai.chat.memory.MessageChatMemoryRepository",
            "org.springframework.ai.model.ChatMemory",
            "org.springframework.ai.client.chat.memory.ChatMemory",
        };

        for (String className : candidateClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                System.out.println("✅ FOUND: " + className);

                // List public methods
                System.out.println("   Methods:");
                for (var method : clazz.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                        System.out.printf("   - %s(%s)%n",
                            method.getName(),
                            String.join(", ",
                                java.util.Arrays.stream(method.getParameterTypes())
                                    .map(Class::getSimpleName)
                                    .toArray(String[]::new)
                            )
                        );
                    }
                }
                System.out.println();

            } catch (ClassNotFoundException e) {
                System.out.println("❌ NOT FOUND: " + className);
            }
        }
    }

    private static void discoverChatClientBuilderMethods() {
        System.out.println("\n[2] Examining ChatClient.Builder methods...\n");

        try {
            Class<?> builderClass = ChatClient.Builder.class;

            System.out.println("Memory/Advisor-related methods:");
            for (var method : builderClass.getMethods()) {
                String name = method.getName();
                if (name.toLowerCase().contains("memory") ||
                    name.toLowerCase().contains("advisor") ||
                    name.toLowerCase().contains("history")) {

                    System.out.printf("✅ %s(%s) -> %s%n",
                        method.getName(),
                        String.join(", ",
                            java.util.Arrays.stream(method.getParameterTypes())
                                .map(Class::getSimpleName)
                                .toArray(String[]::new)
                        ),
                        method.getReturnType().getSimpleName()
                    );
                }
            }

        } catch (Exception e) {
            System.out.println("❌ Failed to examine Builder: " + e.getMessage());
        }
    }

    private static void discoverAdvisorClasses() {
        System.out.println("\n[3] Searching for Advisor classes...\n");

        String[] candidateClasses = {
            "org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor",
            "org.springframework.ai.chat.client.advisor.ChatMemoryAdvisor",
            "org.springframework.ai.chat.client.advisor.SimpleMessageChatMemoryAdvisor",
            "org.springframework.ai.chat.memory.MessageChatMemoryAdvisor",
            "org.springframework.ai.chat.advisor.MessageChatMemoryAdvisor",
            "org.springframework.ai.model.advisor.MessageChatMemoryAdvisor",
        };

        for (String className : candidateClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                System.out.println("✅ FOUND: " + className);

                // List constructors
                System.out.println("   Constructors:");
                for (var constructor : clazz.getConstructors()) {
                    System.out.printf("   - %s(%s)%n",
                        clazz.getSimpleName(),
                        String.join(", ",
                            java.util.Arrays.stream(constructor.getParameterTypes())
                                .map(Class::getSimpleName)
                                .toArray(String[]::new)
                        )
                    );
                }
                System.out.println();

            } catch (ClassNotFoundException e) {
                System.out.println("❌ NOT FOUND: " + className);
            }
        }
    }
}
