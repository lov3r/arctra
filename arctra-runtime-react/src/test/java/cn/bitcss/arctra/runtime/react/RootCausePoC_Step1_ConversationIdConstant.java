package cn.bitcss.arctra.runtime.react;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import java.lang.reflect.Field;

/**
 * Root Cause PoC - Step 1: Verify CONVERSATION_ID constant
 */
class RootCausePoC_Step1_ConversationIdConstant {

    @Test
    void checkConversationIdConstant() {
        System.out.println("=== STEP 1: Verify ChatMemory.CONVERSATION_ID ===\n");

        // Try to find CONVERSATION_ID constant
        try {
            Field[] fields = ChatMemory.class.getDeclaredFields();
            System.out.println("ChatMemory declared fields:");
            for (Field field : fields) {
                System.out.println("  - " + field.getName() + " : " + field.getType().getSimpleName());
                if (field.getName().contains("CONVERSATION") || field.getName().contains("conversation")) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    System.out.println("    VALUE = \"" + value + "\"");
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Cannot access ChatMemory fields: " + e.getMessage());
        }

        // Try common constant names
        String[] possibleKeys = {
            "conversationId",
            "conversation_id",
            "sessionId",
            "session_id",
            "CONVERSATION_ID"
        };

        System.out.println("\nWill test with key: \"conversationId\" (hardcoded string)");
        System.out.println("If ChatMemory has a constant, we should use it instead.\n");
    }
}
