package poc;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * PoC Step 2: Deep dive into ChatMemory and MessageChatMemoryAdvisor
 *
 * Purpose: Understand the actual API signatures and usage patterns
 */
public class Step2_DeepDiveChatMemoryApi {

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("M2-T1 PoC Step 2: Deep Dive into ChatMemory API");
        System.out.println("=".repeat(80));

        exploreChatMemoryInterface();
        exploreMessageWindowChatMemory();
        exploreMessageChatMemoryAdvisor();
    }

    private static void exploreChatMemoryInterface() {
        System.out.println("\n[1] ChatMemory Interface Analysis\n");

        Class<?> chatMemoryClass = ChatMemory.class;
        System.out.println("✅ Interface: " + chatMemoryClass.getName());
        System.out.println();

        System.out.println("Methods:");
        for (Method method : chatMemoryClass.getDeclaredMethods()) {
            System.out.printf("  %s %s(%s)%n",
                method.getReturnType().getSimpleName(),
                method.getName(),
                formatParameters(method.getParameterTypes())
            );
        }
        System.out.println();

        // Key questions:
        System.out.println("Key API Observations:");
        System.out.println("  - add(String, Message): 添加单条消息");
        System.out.println("  - add(String, List<Message>): 添加多条消息");
        System.out.println("  - get(String): 获取消息列表");
        System.out.println("  - clear(String): 清空某个 conversationId 的历史");
        System.out.println("  - Key type: String (conversationId)");
        System.out.println("  - Return type of get(): " +
            getMethodReturnType(chatMemoryClass, "get"));
    }

    private static void exploreMessageWindowChatMemory() {
        System.out.println("\n[2] MessageWindowChatMemory Implementation Analysis\n");

        Class<?> windowMemoryClass = MessageWindowChatMemory.class;
        System.out.println("✅ Class: " + windowMemoryClass.getName());
        System.out.println();

        // Constructors
        System.out.println("Constructors:");
        for (Constructor<?> constructor : windowMemoryClass.getConstructors()) {
            System.out.printf("  %s(%s)%n",
                windowMemoryClass.getSimpleName(),
                formatParameters(constructor.getParameterTypes())
            );
        }
        System.out.println();

        // Static methods (builder?)
        System.out.println("Static Methods:");
        for (Method method : windowMemoryClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) &&
                java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                System.out.printf("  static %s %s(%s)%n",
                    method.getReturnType().getSimpleName(),
                    method.getName(),
                    formatParameters(method.getParameterTypes())
                );
            }
        }
        System.out.println();

        // Try to understand builder pattern
        try {
            Object builder = windowMemoryClass.getMethod("builder").invoke(null);
            System.out.println("✅ Builder pattern available");
            System.out.println("   Builder class: " + builder.getClass().getName());

            // List builder methods
            System.out.println("   Builder methods:");
            for (Method method : builder.getClass().getMethods()) {
                if (method.getDeclaringClass() != Object.class &&
                    java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    System.out.printf("     - %s(%s) -> %s%n",
                        method.getName(),
                        formatParameters(method.getParameterTypes()),
                        method.getReturnType().getSimpleName()
                    );
                }
            }

        } catch (Exception e) {
            System.out.println("❌ Builder exploration failed: " + e.getMessage());
        }
    }

    private static void exploreMessageChatMemoryAdvisor() {
        System.out.println("\n[3] MessageChatMemoryAdvisor Analysis\n");

        Class<?> advisorClass = MessageChatMemoryAdvisor.class;
        System.out.println("✅ Class: " + advisorClass.getName());
        System.out.println();

        // Constructors
        System.out.println("Constructors:");
        for (Constructor<?> constructor : advisorClass.getConstructors()) {
            System.out.printf("  %s(%s)%n",
                advisorClass.getSimpleName(),
                formatParametersDetailed(constructor.getParameterTypes())
            );
        }
        System.out.println();

        // Public methods
        System.out.println("Public Methods:");
        for (Method method : advisorClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                System.out.printf("  %s %s(%s)%n",
                    method.getReturnType().getSimpleName(),
                    method.getName(),
                    formatParametersDetailed(method.getParameterTypes())
                );
            }
        }
        System.out.println();

        // Superclass and interfaces
        System.out.println("Type Hierarchy:");
        System.out.println("  Superclass: " + advisorClass.getSuperclass().getName());
        System.out.println("  Interfaces:");
        for (Class<?> iface : advisorClass.getInterfaces()) {
            System.out.println("    - " + iface.getName());
        }
    }

    private static String formatParameters(Class<?>[] params) {
        if (params.length == 0) return "";
        return String.join(", ",
            java.util.Arrays.stream(params)
                .map(Class::getSimpleName)
                .toArray(String[]::new)
        );
    }

    private static String formatParametersDetailed(Class<?>[] params) {
        if (params.length == 0) return "";
        return String.join(", ",
            java.util.Arrays.stream(params)
                .map(p -> p.getSimpleName() + " [" + p.getPackageName() + "]")
                .toArray(String[]::new)
        );
    }

    private static String getMethodReturnType(Class<?> clazz, String methodName) {
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return method.getReturnType().getName();
                }
            }
            return "unknown";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
