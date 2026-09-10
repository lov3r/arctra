package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.governance.ToolGovernancePolicy;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import cn.bitcss.arctra.process.ContinuationSignal.ApprovalSignal;
import cn.bitcss.arctra.runtime.DurableExecutionEngine;
import cn.bitcss.arctra.runtime.ProcessFactory;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * Agent execution engine based on Spring AI with Governance support.
 *
 * <p>Uses Spring AI ChatClient + Advisor chain with custom {@link GovernanceToolCallingAdvisor} for
 * governance-aware tool execution.
 *
 * <p><strong>M2 Evolution:</strong> Supports multi-turn conversation continuity via {@link
 * AgentExecutionContext}. When {@code context.sessionId()} is present, uses {@link
 * MessageChatMemoryAdvisor} to inject conversation history and persist new messages.
 *
 * <p><strong>M4 Evolution:</strong> Supports governance-driven suspension. When a tool invocation
 * requires approval, execution suspends and returns {@link AgentResult} with {@link AgentProcess}.
 *
 * <h2>Advisor Chain</h2>
 *
 * <p>Execution order:
 *
 * <ol>
 *   <li>MessageChatMemoryAdvisor (before) - loads session history
 *   <li>GovernanceToolCallingAdvisor - tool calling loop + governance
 *   <li>MessageChatMemoryAdvisor (after) - persists new messages
 * </ol>
 *
 * @author lov3r
 */
public class SpringAiToolCallingEngine implements DurableExecutionEngine {

  private final ChatModel chatModel;
  private final List<ToolCallback> tools;
  private final ChatMemory chatMemory;
  private final ToolGovernancePolicy governancePolicy;

  // M5-T4 durable configuration (optional - all-or-nothing)
  private final CheckpointStore checkpointStore;
  private final RuntimeBindingResolver bindingResolver;
  private final String runtimeBindingKey;

  /**
   * Create a tool-calling engine with conversation memory and governance support (M4 ephemeral).
   *
   * @param chatModel the chat model for agent execution
   * @param tools the tools available to the agent
   * @param chatMemory the chat memory for conversation history (shared across executions)
   * @param governancePolicy the governance policy for tool invocations
   */
  public SpringAiToolCallingEngine(
      ChatModel chatModel,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ToolGovernancePolicy governancePolicy) {
    this(chatModel, tools, chatMemory, governancePolicy, null, null, null);
  }

  /**
   * Create a tool-calling engine with durable suspension capability (M5).
   *
   * <p><strong>Durable configuration (all-or-nothing):</strong> Either all three durable
   * parameters are provided, or all are null for ephemeral-only mode.
   *
   * @param chatModel the chat model
   * @param tools the tools available
   * @param chatMemory the chat memory
   * @param governancePolicy the governance policy
   * @param checkpointStore checkpoint store for durable suspension (null for ephemeral)
   * @param bindingResolver runtime binding resolver for recovery (null for ephemeral)
   * @param runtimeBindingKey logical binding key for this engine (null for ephemeral)
   * @throws IllegalArgumentException if durable configuration is partial
   * @since M5-T4
   */
  public SpringAiToolCallingEngine(
      ChatModel chatModel,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ToolGovernancePolicy governancePolicy,
      CheckpointStore checkpointStore,
      RuntimeBindingResolver bindingResolver,
      String runtimeBindingKey) {

    this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
    this.tools = Objects.requireNonNull(tools, "tools cannot be null");
    this.chatMemory = Objects.requireNonNull(chatMemory, "chatMemory cannot be null");
    this.governancePolicy =
        Objects.requireNonNull(governancePolicy, "governancePolicy cannot be null");

    // Validate durable configuration: all-or-nothing
    boolean hasStore = checkpointStore != null;
    boolean hasResolver = bindingResolver != null;
    boolean hasKey = runtimeBindingKey != null && !runtimeBindingKey.isBlank();

    if (hasStore || hasResolver || hasKey) {
      if (!hasStore || !hasResolver || !hasKey) {
        throw new IllegalArgumentException(
            "Partial durable configuration rejected. Got: checkpointStore="
                + (hasStore ? "present" : "null")
                + ", bindingResolver="
                + (hasResolver ? "present" : "null")
                + ", runtimeBindingKey="
                + (hasKey ? "present" : "null"));
      }
    }

    this.checkpointStore = checkpointStore;
    this.bindingResolver = bindingResolver;
    this.runtimeBindingKey = runtimeBindingKey;
  }

  private boolean isDurableMode() {
    return checkpointStore != null;
  }

  /**
   * M2 backward compatible constructor (no governance).
   *
   * @param chatModel the chat model
   * @param tools the tools
   * @param chatMemory the chat memory
   */
  public SpringAiToolCallingEngine(
      ChatModel chatModel, List<ToolCallback> tools, ChatMemory chatMemory) {
    this(chatModel, tools, chatMemory, ToolGovernancePolicy.allowAll());
  }

  @Override
  public AgentResult execute(
      AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {

    // Create ToolCallingManager for Spring AI tool execution mechanics
    var toolCallingManager = ToolCallingManager.builder().build();

    // Create thread-safe evidence list for this execution
    List<Evidence> evidences = Collections.synchronizedList(new ArrayList<>());

    // Wrap tools with evidence capturing
    List<ToolCallback> wrappedTools = tools.stream()
        .map(tool -> new EvidenceCapturingToolCallback(tool, evidences))
        .map(wrapper -> (ToolCallback) wrapper)
        .toList();

    // Create governance tool calling advisor with wrapped tools
    var governanceAdvisor =
        new GovernanceToolCallingAdvisor(wrappedTools, governancePolicy, context, toolCallingManager);

    try {
      // Build ChatClient without default tools to avoid duplication
      // Tools will be registered at prompt level
      var chatClient = ChatClient.builder(chatModel).build();

      // Construct system instruction
      var systemInstruction = buildSystemInstruction(definition);

      // Execute via ChatClient - explicitly specify advisors at prompt level
      String sessionId = context.sessionId();

      var promptSpec = chatClient.prompt()
          .system(systemInstruction)
          .user(request.userMessage())
          .tools(wrappedTools.toArray(new ToolCallback[0]))  // Use wrapped tools
          .advisors(spec -> {
            // Disable auto-registration of ToolCallingAdvisor
            spec.param("spring.ai.chat.client.tool.calling.advisor.auto-register", false);

            // Add MessageChatMemoryAdvisor if session exists
            if (sessionId != null) {
              var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
              spec.advisors(memoryAdvisor);
              spec.param(ChatMemory.CONVERSATION_ID, sessionId);
            }

            // Add GovernanceToolCallingAdvisor
            spec.advisors(governanceAdvisor);
          });

      // Execute
      var content = promptSpec.call().content();

      // Normal completion - Spring AI MessageChatMemoryAdvisor handles persistence
      return new AgentResult(content, evidences);

    } catch (ToolApprovalRequiredSignal signal) {
      // Suspension via internal control signal - not an error
      // User message already persisted by MessageChatMemoryAdvisor.before()
      // No synthetic AssistantMessage placeholder persisted (after() skipped)
      return suspendForApproval(signal.state(), evidences, definition, context);

    } finally {
      // Cleanup thread-local state
      governanceAdvisor.clearState();
    }
  }

  private AgentResult suspendForApproval(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Check mode: durable or ephemeral
    if (isDurableMode()) {
      return suspendForApprovalDurable(suspensionState, evidences, definition, context);
    } else {
      return suspendForApprovalEphemeral(suspensionState, evidences, definition, context);
    }
  }

  /**
   * Durable suspension (M5-T4 Phase 4).
   *
   * <p>Creates checkpoint-backed durable process. Durability-first: checkpoint persisted BEFORE
   * process exposed.
   */
  private AgentResult suspendForApprovalDurable(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // 1. Generate stable processId
    String processId = UUID.randomUUID().toString();

    // 2. Extract sessionId from context
    String sessionId = context.sessionId();

    // 3. Build pendingBatch from suspension state
    List<PendingToolCall> pendingBatch =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(
                tc ->
                    new PendingToolCall(
                        tc.id(), tc.name(), tc.arguments() != null ? tc.arguments() : "{}"))
            .toList();

    // 4. Build checkpoint v1
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            processId,
            1L, // Initial version
            runtimeBindingKey,
            sessionId,
            pendingBatch,
            List.copyOf(evidences)); // Defensive copy

    // 5. Persist checkpoint FIRST (durability-first)
    checkpointStore.create(checkpoint);

    // 6. ONLY after checkpoint persisted: create durable process
    AgentProcess process = ProcessFactory.createDurableSuspended(processId, 1L, this);

    // 7. Return suspended result
    String toolNames =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(AssistantMessage.ToolCall::name)
            .toList()
            .toString();
    String partialContent =
        String.format("Execution suspended: tool batch %s requires approval", toolNames);
    return new AgentResult(partialContent, evidences, process);
  }

  /**
   * Ephemeral suspension (M4 existing behavior).
   */
  private AgentResult suspendForApprovalEphemeral(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Create continuation function for resume
    var continuationFunction =
        new java.util.function.Function<ContinuationSignal, AgentResult>() {
          @Override
          public AgentResult apply(ContinuationSignal signal) {
            if (signal instanceof ApprovalSignal approval) {
              if (approval.approved()) {
                return resumeApproved(suspensionState, evidences, definition, context);
              } else {
                return resumeDenied(suspensionState, evidences, definition, context);
              }
            }
            throw new IllegalArgumentException("Unknown ContinuationSignal type: " + signal);
          }
        };

    // Materialize Process
    AgentProcess process = ProcessFactory.createSuspended(continuationFunction);

    // Return suspended result - display first tool name for user message
    String toolNames =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(AssistantMessage.ToolCall::name)
            .toList()
            .toString();
    String partialContent =
        String.format("Execution suspended: tool batch %s requires approval", toolNames);
    return new AgentResult(partialContent, evidences, process);
  }

  private AgentResult resumeApproved(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> previousEvidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // APPROVED: execute the entire original tool batch via ToolCallingManager
    // This preserves all ToolCall IDs, names, arguments exactly as Model generated them.

    // Wrap tools with evidence capturing for this resume execution
    List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>(previousEvidences));
    List<ToolCallback> wrappedTools = tools.stream()
        .map(tool -> new EvidenceCapturingToolCallback(tool, newEvidences))
        .map(wrapper -> (ToolCallback) wrapper)
        .toList();

    // Reconstruct ChatResponse with the original AssistantMessage containing all ToolCalls
    AssistantMessage assistantMessage = suspensionState.assistantMessageWithToolCalls();
    Generation generation = new Generation(assistantMessage);
    ChatResponse chatResponse = new ChatResponse(List.of(generation));

    // Build Prompt with wrapped tools - ToolCallingManager needs toolCallbacks in prompt options
    ToolCallingChatOptions optionsWithTools = ToolCallingChatOptions.builder()
        .toolCallbacks(wrappedTools)  // Use wrapped tools for evidence capture
        .build();

    Prompt promptWithTools = new Prompt(
        suspensionState.originalRequest().prompt().getInstructions(),
        optionsWithTools
    );

    // Create ToolCallingManager
    ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    // Execute ALL tools in the batch via ToolCallingManager
    ToolExecutionResult toolExecutionResult =
        toolCallingManager.executeToolCalls(promptWithTools, chatResponse);

    // Check returnDirect semantics
    if (toolExecutionResult.returnDirect()) {
      // Tool result should be returned directly to client
      // Build final response from tool execution result
      ChatResponse directResponse = ChatResponse.builder()
          .from(chatResponse)
          .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
          .build();

      String content = Objects.requireNonNull(directResponse.getResult()).getOutput().getText();

      // Completed via returnDirect - persist final answer
      persistCompletedAssistant(context, content);

      // Evidence captured by wrapped callbacks during execution
      return new AgentResult(content, newEvidences);
    }

    // Use Spring AI's conversation history for continuation
    List<Message> continuationMessages = toolExecutionResult.conversationHistory();

    // Continue execution via ChatClient with protocol-preserved messages
    // Pass newEvidences which now contains tool execution results
    return continueWithMessages(continuationMessages, newEvidences, definition, context);
  }

  private AgentResult resumeDenied(
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // REJECTED: construct protocol-valid denial responses for entire batch
    // 0 tools execute, 0 Evidence added

    AssistantMessage assistantMessage = suspensionState.assistantMessageWithToolCalls();

    // Build ToolResponseMessage with denial for each tool call
    List<ToolResponseMessage.ToolResponse> denialResponses = assistantMessage.getToolCalls().stream()
        .map(tc -> new ToolResponseMessage.ToolResponse(
            tc.id(),
            tc.name(),
            "Tool execution rejected by approval decision"
        ))
        .toList();

    // Build continuation messages preserving native protocol
    List<Message> continuationMessages =
        new ArrayList<>(suspensionState.originalRequest().prompt().getInstructions());
    continuationMessages.add(assistantMessage);
    continuationMessages.add(
        ToolResponseMessage.builder()
            .responses(denialResponses)
            .build());

    // Continue execution via ChatClient with protocol-preserved messages
    // No new evidences - rejected batch executes 0 tools
    return continueWithMessages(continuationMessages, evidences, definition, context);
  }

  /**
   * Continue execution with protocol-preserved messages.
   *
   * <p>This method re-enters the ChatClient/Advisor execution path with native Spring AI Messages,
   * preserving tool-calling protocol (AssistantMessage with ToolCalls, ToolResponseMessages with
   * toolCallIds).
   *
   * <p>This is the correct continuation mechanism after tool execution, as opposed to flattening
   * messages to text and restarting via AgentRequest.
   *
   * @param messages native Spring AI messages (including ToolResponseMessage)
   * @param evidences accumulated evidences
   * @param definition agent definition
   * @param context execution context
   * @return result (may be suspended again if another REQUIRE_APPROVAL is encountered)
   */
  private AgentResult continueWithMessages(
      List<Message> messages,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Create ToolCallingManager for Spring AI tool execution mechanics
    var toolCallingManager = ToolCallingManager.builder().build();

    // Wrap tools with evidence capturing - reuse the same evidence list
    List<ToolCallback> wrappedTools = tools.stream()
        .map(tool -> new EvidenceCapturingToolCallback(tool, evidences))
        .map(wrapper -> (ToolCallback) wrapper)
        .toList();

    // Create governance advisor with wrapped tools
    var governanceAdvisor =
        new GovernanceToolCallingAdvisor(wrappedTools, governancePolicy, context, toolCallingManager);

    // Pre-initialize evidences with previous ones before adviseCall()
    // (GovernanceToolCallingAdvisor.adviseCall() will preserve pre-set evidences)
    governanceAdvisor.initializeEvidences(evidences);

    try {
      // Build ChatClient without default tools to avoid duplication
      var chatClient = ChatClient.builder(chatModel).build();

      // Construct system instruction
      var systemInstruction = buildSystemInstruction(definition);

      // Continue execution via ChatClient with protocol-preserved messages
      String sessionId = context.sessionId();
      var promptSpec =
          chatClient
              .prompt()
              // NOTE: Do NOT add .system() here - messages already contains SystemMessage
              // from originalRequest. Adding it again would cause duplication.
              .messages(messages) // ← Use native Messages (includes SystemMessage)
              .tools(wrappedTools.toArray(new ToolCallback[0]))  // Use wrapped tools
              .advisors(
                  spec -> {
                    // Disable auto-registration of ToolCallingAdvisor
                    spec.param(
                        "spring.ai.chat.client.tool.calling.advisor.auto-register", false);

                    // Do NOT add MessageChatMemoryAdvisor here!
                    // The messages parameter already contains the full conversation history.
                    // Adding MemoryAdvisor would cause duplication.

                    // But DO preserve the CONVERSATION_ID parameter for consistency
                    if (sessionId != null) {
                      spec.param(ChatMemory.CONVERSATION_ID, sessionId);
                    }

                    // Add GovernanceToolCallingAdvisor
                    spec.advisors(governanceAdvisor);
                  });

      // Execute
      String content = promptSpec.call().content();

      // evidences list already contains all collected evidences (via wrapped tools)

      // Completed - persist final Assistant message to close the open conversational turn
      // MessageChatMemoryAdvisor is intentionally NOT installed during continuation
      // (messages parameter already contains full history, avoid duplication)
      // Therefore we manually persist only the final Assistant side here
      persistCompletedAssistant(context, content);

      return new AgentResult(content, evidences);

    } catch (ToolApprovalRequiredSignal signal) {
      // Re-suspension - throw control signal for outer catch
      return suspendForApproval(signal.state(), evidences, definition, context);

    } finally {
      governanceAdvisor.clearState();
    }
  }

  /**
   * Persist final Assistant message to ChatMemory when resumed execution completes.
   *
   * <p><b>Memory Semantic:</b>
   * <ul>
   *   <li>Initial execution: MessageChatMemoryAdvisor handles full H + U + A persistence</li>
   *   <li>Resumed execution: User message already persisted during suspension;
   *       this method closes the open turn by persisting only final Assistant message</li>
   * </ul>
   *
   * <p>This asymmetry is intentional: continuation bypasses MemoryAdvisor READ
   * (history already expanded in protocol state) but needs manual WRITE for final answer.
   *
   * @param context execution context (contains sessionId)
   * @param content final assistant message content
   */
  private void persistCompletedAssistant(AgentExecutionContext context, String content) {
    String sessionId = context.sessionId();
    if (sessionId != null) {
      chatMemory.add(sessionId, new AssistantMessage(content));
    }
  }

  private String buildSystemInstruction(AgentDefinition definition) {
    var name = definition.name();
    var description = definition.description();

    if (description == null || description.isBlank()) {
      return String.format("You are %s.", name);
    } else {
      return String.format("You are %s. %s", name, description);
    }
  }

  /**
   * Resume durable suspended process (M5-T4 Phase 5 - Unified Durable Resume Pipeline).
   *
   * <p>ONE authoritative durable recovery pipeline. All resume entry points converge here:
   *
   * <ul>
   *   <li>AgentProcess.resume() → DurableResumeStrategy → this method
   *   <li>AgentRuntime.resumeProcess() → this method
   * </ul>
   *
   * <h2>Pipeline:</h2>
   *
   * <ol>
   *   <li>CHECK A - Load and validate checkpoint version
   *   <li>Resolve RuntimeBinding (definition, context)
   *   <li>Protocol reconstruction (approved/rejected)
   *   <li>Model continuation
   *   <li>Governance on NEW tool calls
   *   <li>Completion → CHECK B (deleteIfVersion)
   *   <li>Re-suspension → CHECK B (replaceIfVersion)
   * </ol>
   *
   * @param processId stable process identifier
   * @param checkpointVersion suspension episode version (fencing token)
   * @param signal continuation signal (APPROVED/REJECTED)
   * @return execution result (may be suspended again)
   * @throws IllegalStateException if durable mode not configured
   * @throws cn.bitcss.arctra.checkpoint.CheckpointNotFoundException if checkpoint missing
   * @throws cn.bitcss.arctra.checkpoint.StaleCheckpointException if version mismatch
   * @throws cn.bitcss.arctra.runtime.ResumePreparationException if binding resolution fails
   * @throws cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException if CHECK B fails
   * @since M5-T4
   */
  @Override
  public AgentResult resumeProcess(
      String processId, long checkpointVersion, ContinuationSignal signal) {

    // Precondition: durable mode must be configured
    if (!isDurableMode()) {
      throw new IllegalStateException(
          "resumeProcess() requires complete durable configuration. "
              + "Current: checkpointStore="
              + (checkpointStore != null ? "present" : "null")
              + ", bindingResolver="
              + (bindingResolver != null ? "present" : "null")
              + ", runtimeBindingKey="
              + (runtimeBindingKey != null ? "present" : "null"));
    }

    // CHECK A: Load and validate checkpoint
    SuspensionCheckpoint checkpoint = loadAndValidateCheckpoint(processId, checkpointVersion);

    // Resolve RuntimeBinding
    cn.bitcss.arctra.runtime.RuntimeBinding binding =
        resolveBinding(processId, checkpoint);

    // Protocol reconstruction and execution based on signal
    List<Evidence> historicalEvidences = checkpoint.accumulatedEvidences();
    List<Evidence> newEvidences = Collections.synchronizedList(new ArrayList<>());

    List<Message> continuationMessages;
    if (signal instanceof ContinuationSignal.ApprovalSignal approval) {
      if (approval.approved()) {
        // APPROVED: execute stored pending batch
        continuationMessages =
            reconstructAndExecuteApproved(
                checkpoint, binding, historicalEvidences, newEvidences);
      } else {
        // REJECTED: construct denial responses
        continuationMessages =
            reconstructDenialResponses(checkpoint, binding);
      }
    } else {
      throw new IllegalArgumentException(
          "Unknown ContinuationSignal type: " + signal.getClass().getName());
    }

    // Merge evidences ONCE
    List<Evidence> mergedEvidences = new ArrayList<>(historicalEvidences);
    mergedEvidences.addAll(newEvidences);

    // Continue model with reconstructed protocol
    // Use special durable continuation that handles CHECK B
    return durableContinueWithMessages(
        checkpoint, continuationMessages, mergedEvidences, binding.definition(), binding.context());
  }

  /**
   * Durable continuation with CHECK B handling for completion and re-suspension.
   */
  private AgentResult durableContinueWithMessages(
      SuspensionCheckpoint currentCheckpoint,
      List<Message> messages,
      List<Evidence> evidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    var toolCallingManager = ToolCallingManager.builder().build();

    List<ToolCallback> wrappedTools =
        tools.stream()
            .map(tool -> new EvidenceCapturingToolCallback(tool, evidences))
            .map(wrapper -> (ToolCallback) wrapper)
            .toList();

    var governanceAdvisor =
        new GovernanceToolCallingAdvisor(
            wrappedTools, governancePolicy, context, toolCallingManager);

    try {
      String systemInstruction = buildSystemInstruction(definition);
      var chatClient = ChatClient.builder(chatModel).build();

      var promptSpec =
          chatClient
              .prompt()
              .system(systemInstruction)
              .messages(messages)
              .advisors(
                  spec -> {
                    spec.param(
                        "spring.ai.chat.client.tool.calling.advisor.auto-register", false);
                    spec.advisors(governanceAdvisor);
                  });

      var content = promptSpec.call().content();

      // Completion - CHECK B delete
      boolean deleted =
          checkpointStore.deleteIfVersion(
              currentCheckpoint.processId(), currentCheckpoint.checkpointVersion());

      if (!deleted) {
        throw new cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException(
            "Completion CHECK B failed for processId "
                + currentCheckpoint.processId()
                + ", version "
                + currentCheckpoint.checkpointVersion());
      }

      persistCompletedAssistant(context, content);
      return new AgentResult(content, evidences);

    } catch (ToolApprovalRequiredSignal signal) {
      // Re-suspension
      return handleDurableReSuspension(
          currentCheckpoint, signal.state(), evidences, definition, context);

    } finally {
      governanceAdvisor.clearState();
    }
  }

  /**
   * Handle durable re-suspension with CHECK B (replaceIfVersion).
   */
  private AgentResult handleDurableReSuspension(
      SuspensionCheckpoint oldCheckpoint,
      GovernanceToolCallingAdvisor.SuspensionState suspensionState,
      List<Evidence> mergedEvidences,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Extract new pending batch
    List<PendingToolCall> nextPendingBatch =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(
                tc ->
                    new PendingToolCall(
                        tc.id(), tc.name(), tc.arguments() != null ? tc.arguments() : "{}"))
            .toList();

    // Build checkpoint vN+1 with preserved identity
    long nextVersion = oldCheckpoint.checkpointVersion() + 1;

    SuspensionCheckpoint nextCheckpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            oldCheckpoint.processId(), // Preserve processId
            nextVersion,
            oldCheckpoint.runtimeBindingKey(), // Preserve from checkpoint
            oldCheckpoint.sessionId(),
            nextPendingBatch,
            mergedEvidences);

    // CHECK B: replaceIfVersion
    boolean replaced =
        checkpointStore.replaceIfVersion(
            oldCheckpoint.processId(), oldCheckpoint.checkpointVersion(), nextCheckpoint);

    if (!replaced) {
      throw new cn.bitcss.arctra.checkpoint.CheckpointTransitionConflictException(
          "Re-suspension CHECK B failed for processId "
              + oldCheckpoint.processId()
              + ", version "
              + oldCheckpoint.checkpointVersion());
    }

    // Create new durable process
    AgentProcess nextProcess =
        ProcessFactory.createDurableSuspended(oldCheckpoint.processId(), nextVersion, this);

    String toolNames =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(org.springframework.ai.chat.messages.AssistantMessage.ToolCall::name)
            .toList()
            .toString();
    String partialContent =
        String.format("Execution suspended: tool batch %s requires approval", toolNames);

    return new AgentResult(partialContent, mergedEvidences, nextProcess);
  }

  /**
   * CHECK A: Load and validate checkpoint version.
   *
   * @throws cn.bitcss.arctra.checkpoint.CheckpointNotFoundException if missing
   * @throws cn.bitcss.arctra.checkpoint.StaleCheckpointException if version mismatch
   */
  private SuspensionCheckpoint loadAndValidateCheckpoint(
      String processId, long expectedVersion) {

    SuspensionCheckpoint checkpoint =
        checkpointStore
            .load(processId)
            .orElseThrow(
                () ->
                    new cn.bitcss.arctra.checkpoint.CheckpointNotFoundException(
                        "Checkpoint not found for processId: " + processId));

    if (checkpoint.checkpointVersion() != expectedVersion) {
      throw new cn.bitcss.arctra.checkpoint.StaleCheckpointException(
          "Checkpoint version mismatch for processId "
              + processId
              + ". Expected: "
              + expectedVersion
              + ", Actual: "
              + checkpoint.checkpointVersion());
    }

    return checkpoint;
  }

  /**
   * Resolve RuntimeBinding using checkpoint identity.
   *
   * @throws cn.bitcss.arctra.runtime.ResumePreparationException if resolution fails
   */
  private cn.bitcss.arctra.runtime.RuntimeBinding resolveBinding(
      String processId, SuspensionCheckpoint checkpoint) {

    try {
      return bindingResolver.resolve(
          processId, checkpoint.runtimeBindingKey(), checkpoint.sessionId());
    } catch (Exception e) {
      throw new cn.bitcss.arctra.runtime.ResumePreparationException(
          "RuntimeBinding resolution failed for processId "
              + processId
              + ", runtimeBindingKey="
              + checkpoint.runtimeBindingKey(),
          e);
    }
  }

  /**
   * Reconstruct and execute approved pending batch.
   */
  private List<Message> reconstructAndExecuteApproved(
      SuspensionCheckpoint checkpoint,
      cn.bitcss.arctra.runtime.RuntimeBinding binding,
      List<Evidence> historicalEvidences,
      List<Evidence> newEvidences) {

    // Get conversation history from ChatMemory
    List<Message> conversationHistory = getConversationHistory(binding.context());

    // Use ProtocolReconstructor to execute approved batch
    ProtocolReconstructor reconstructor = new ProtocolReconstructor(tools);

    return reconstructor.executeApprovedBatch(
        checkpoint.pendingBatch(),
        conversationHistory,
        historicalEvidences,
        newEvidences);
  }

  /**
   * Reconstruct denial responses for rejected batch.
   */
  private List<Message> reconstructDenialResponses(
      SuspensionCheckpoint checkpoint, cn.bitcss.arctra.runtime.RuntimeBinding binding) {

    // Get conversation history
    List<Message> conversationHistory = getConversationHistory(binding.context());

    // Use ProtocolReconstructor to construct denials
    ProtocolReconstructor reconstructor = new ProtocolReconstructor(tools);

    return reconstructor.constructDenialResponses(
        checkpoint.pendingBatch(), conversationHistory);
  }

  /**
   * Get conversation history from ChatMemory.
   */
  private List<Message> getConversationHistory(AgentExecutionContext context) {
    String sessionId = context.sessionId();
    if (sessionId != null) {
      return chatMemory.get(sessionId);
    }
    return List.of();
  }
}
