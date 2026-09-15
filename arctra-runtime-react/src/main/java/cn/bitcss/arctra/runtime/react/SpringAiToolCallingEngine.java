package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import cn.bitcss.arctra.execution.EventType;
import cn.bitcss.arctra.execution.ExecutionEvent;
import cn.bitcss.arctra.execution.ExecutionEventListener;
import cn.bitcss.arctra.execution.ExecutionLedger;
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

  // M6-T2B.1 execution event sink (always non-null)
  private final ExecutionEventListener executionEventSink;

    // M6-T2.5A-R4 Durable resume coordinator (null in ephemeral mode)
  private final DurableResumeCoordinator durableResumeCoordinator;

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
    this(chatModel, tools, chatMemory, governancePolicy, null, null, null, null);
  }

  /**
   * Create a tool-calling engine with durable suspension capability (M5 backward compatibility).
   *
   * <p>This constructor exists for backward compatibility with M5 tests. New code should use the
   * 8-parameter constructor with explicit ExecutionLedger parameter.
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
   * @deprecated Use 8-parameter constructor with explicit ExecutionLedger parameter
   */
  @Deprecated
  public SpringAiToolCallingEngine(
      ChatModel chatModel,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ToolGovernancePolicy governancePolicy,
      CheckpointStore checkpointStore,
      RuntimeBindingResolver bindingResolver,
      String runtimeBindingKey) {
    this(chatModel, tools, chatMemory, governancePolicy, checkpointStore, bindingResolver,
         runtimeBindingKey, null);
  }

  /**
   * Create a tool-calling engine with durable suspension capability (M5).
   *
   * <p><strong>Durable configuration (all-or-nothing):</strong> Either all three durable
   * parameters are provided, or all are null for ephemeral-only mode.
   *
   * <p><strong>Execution ledger (optional):</strong> ExecutionLedger may be provided independently
   * for audit trail. If null, no execution events are recorded.
   *
   * @param chatModel the chat model
   * @param tools the tools available
   * @param chatMemory the chat memory
   * @param governancePolicy the governance policy
   * @param checkpointStore checkpoint store for durable suspension (null for ephemeral)
   * @param bindingResolver runtime binding resolver for recovery (null for ephemeral)
   * @param runtimeBindingKey logical binding key for this engine (null for ephemeral)
   * @param executionLedger execution ledger for audit trail (null to disable)
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
      String runtimeBindingKey,
      ExecutionLedger executionLedger) {

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

    // M6-T2B.1: adapt ExecutionLedger to ExecutionEventListener once at construction
    this.executionEventSink = adaptLedgerToListener(executionLedger);

    // M6-T4E: Create invocation-state store paired with checkpoint store
    InvocationStateStore invocationStateStore =
        createMatchingInvocationStateStore(checkpointStore);

    // M6-T4C Phase 1: Create recovery classifier (package-private internal)
    InvocationRecoveryClassifier recoveryClassifier =
        new InvocationRecoveryClassifier(invocationStateStore);

    // M6-T2.5A-R3: Create resumed execution handler
      // M6-T2.5A-R3 Spring AI resumed execution handler (M6-T4A: pass invocationStateStore)
      SpringAiResumedExecutionHandler resumedExecutionHandler =
          new SpringAiResumedExecutionHandler(chatModel, chatMemory, tools, governancePolicy, invocationStateStore);

    // M6-T2.5A-R4: Construct durable resume coordinator if durable mode is configured
    if (isDurableMode()) {
      // M6-T4C Phase 1: Pass recoveryClassifier to coordinator
      this.durableResumeCoordinator =
          new DurableResumeCoordinator(
              checkpointStore, bindingResolver, executionEventSink, resumedExecutionHandler, this, recoveryClassifier);
    } else {
      this.durableResumeCoordinator = null;
    }
  }

  /**
   * Internal no-op listener for when no ExecutionLedger is configured.
   *
   * <p>This avoids null checks in execution logic.
   *
   * @since M6-T2B.1
   */
  private static final ExecutionEventListener NOOP_EVENT_LISTENER = event -> {};

  /**
   * Adapt ExecutionLedger to ExecutionEventListener with projection failure isolation.
   *
   * <p>If no ledger is provided, returns a no-op listener.
   *
   * <p><strong>Projection isolation:</strong> Composite listener ensures ledger append failures
   * cannot alter execution truth.
   *
   * @param executionLedger the execution ledger (may be null)
   * @return non-null ExecutionEventListener
   * @since M6-T2B.1
   */
  private static ExecutionEventListener adaptLedgerToListener(ExecutionLedger executionLedger) {
    if (executionLedger == null) {
      return NOOP_EVENT_LISTENER;
    }

    ExecutionLedgerListener ledgerProjection = new ExecutionLedgerListener(executionLedger);
    return new CompositeExecutionEventListener(List.of(ledgerProjection));
  }

  private boolean isDurableMode() {
    return checkpointStore != null;
  }

  /**
   * Create matching InvocationStateStore paired with CheckpointStore.
   *
   * <p><strong>M6-T4E: Official JDBC Pairing</strong>
   *
   * <p>If CheckpointStore is JdbcCheckpointStore, creates matching JdbcInvocationStateStore
   * sharing the same DataSource. This ensures restart-durable recovery substrate coherence.
   *
   * <p><strong>Unknown/custom CheckpointStore:</strong> Falls back to InMemoryInvocationStateStore.
   * This configuration is execution-compatible but does NOT provide restart-durable recovery
   * guarantees.
   *
   * @param checkpointStore the configured checkpoint store (may be null for ephemeral)
   * @return matching invocation state store
   * @since M6-T4E
   */
  private InvocationStateStore createMatchingInvocationStateStore(
      CheckpointStore checkpointStore) {

    if (checkpointStore == null) {
      // Ephemeral mode - no durable configuration
      return new InMemoryInvocationStateStore();
    }

    if (checkpointStore instanceof JdbcCheckpointStore jdbcStore) {
      // Official JDBC persistent mode - pair with matching JDBC intent store
      return new JdbcInvocationStateStore(jdbcStore.getDataSource());
    }

    // Unknown/custom checkpoint store - safe fallback to in-memory
    // NOTE: This means custom persistent CheckpointStore implementations
    // will NOT get restart-durable recovery guarantees unless explicitly
    // paired through future configuration mechanism
    return new InMemoryInvocationStateStore();
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
   *
   * <p><strong>M6-T2A Event Wiring:</strong>
   * <ul>
   *   <li>APPROVAL_REQUIRED after governance decision
   *   <li>SUSPENDED after checkpoint.create() succeeds
   * </ul>
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
                        OperationIds.generate(), // operationId - framework identity (M6-T3A)
                        tc.id(),                 // toolCallId - protocol identity
                        tc.name(),               // toolName
                        tc.arguments()))         // arguments JSON
            .toList();

    // M6-T2A: APPROVAL_REQUIRED domain fact becomes TRUE
    // (governance decision = REQUIRE_APPROVAL)
    String toolNames = pendingBatch.stream().map(PendingToolCall::toolName).toList().toString();
    String approvalPayload = """
        {"toolNames": %s, "policyReason": "governance_requires_approval"}
        """.formatted(toolNames);
    emitEvent(processId, EventType.APPROVAL_REQUIRED, null, approvalPayload);

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

    // M6-T2A: SUSPENDED domain fact becomes TRUE
    // (checkpoint.create() succeeded)
    String suspendedPayload = """
        {"checkpointVersion": 1, "pendingToolCount": %d, "evidenceCount": %d}
        """.formatted(pendingBatch.size(), evidences.size());
    emitEvent(processId, EventType.SUSPENDED, 1L, suspendedPayload);

    // 6. ONLY after checkpoint persisted: create durable process
    AgentProcess process = ProcessFactory.createDurableSuspended(processId, 1L, this);

    // 7. Return suspended result
    String toolNamesList =
        suspensionState.assistantMessageWithToolCalls().getToolCalls().stream()
            .map(AssistantMessage.ToolCall::name)
            .toList()
            .toString();
    String partialContent =
        String.format("Execution suspended: tool batch %s requires approval", toolNamesList);
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
    if (durableResumeCoordinator == null) {
      throw new IllegalStateException(
          "resumeProcess() requires complete durable configuration. "
              + "Current: checkpointStore="
              + (checkpointStore != null ? "present" : "null")
              + ", bindingResolver="
              + (bindingResolver != null ? "present" : "null")
              + ", runtimeBindingKey="
              + (runtimeBindingKey != null ? "present" : "null"));
    }

    // Delegate to durable resume coordinator
    return durableResumeCoordinator.resume(processId, checkpointVersion, signal);
  }
  /**
   * Emit an execution event through the event sink.
   *
   * <p>Projection failures are isolated by CompositeExecutionEventListener and do not affect
   * execution truth.
   *
   * @param processId the process identifier
   * @param eventType the event type
   * @param checkpointVersion the checkpoint version (may be null)
   * @param payload the event payload (may be null)
   * @since M6-T2B.1
   */
  private void emitEvent(
      String processId, EventType eventType, Long checkpointVersion, String payload) {
    executionEventSink.onEvent(
        new ExecutionEvent(processId, eventType, checkpointVersion, payload));
  }
}

