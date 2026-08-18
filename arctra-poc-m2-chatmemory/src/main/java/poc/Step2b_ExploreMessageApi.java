package poc;

import org.springframework.ai.chat.messages.Message;

/**
 * Explore Message interface actual methods
 */
public class Step2b_ExploreMessageApi {

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("M2-T1 PoC: Exploring Message API");
        System.out.println("=".repeat(80));

        exploreMessageInterface();
    }

    private static void exploreMessageInterface() {
        System.out.println("\n[1] Message Interface Methods\n");

        Class<?> messageClass = Message.class;

        System.out.println("All methods in Message interface:");
        for (var method : messageClass.getDeclaredMethods()) {
            System.out.printf("  %s %s(%s)%n",
                method.getReturnType().getSimpleName(),
                method.getName(),
                String.join(", ",
                    java.util.Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .toArray(String[]::new)
                )
            );
        }
    }
}
