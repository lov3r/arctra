package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.agent.AgentDefinition;
import cn.bitcss.arctra.agent.AgentExecutionContext;
import cn.bitcss.arctra.agent.AgentRequest;
import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.checkpoint.CheckpointStore;
import cn.bitcss.arctra.checkpoint.ContinuationDisposition;
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
import cn.bitcss.arctra.runtime.DurableExecutionEngine;
import cn.bitcss.arctra.runtime.RuntimeBindingResolver;
import cn.bitcss.arctra.runtime.react.durable.DurableResumeCoordinator;
import cn.bitcss.arctra.runtime.react.durable.ExecutionIncarnation;
import cn.bitcss.arctra.runtime.react.durable.InMemoryInvocationStateStore;
import cn.bitcss.arctra.runtime.react.durable.InvocationRecoveryClassifier;
import cn.bitcss.arctra.runtime.react.durable.InvocationStateStore;
import cn.bitcss.arctra.runtime.react.durable.JdbcInvocationStateStore;
import cn.bitcss.arctra.runtime.react.event.CompositeExecutionEventListener;
import cn.bitcss.arctra.runtime.react.event.ExecutionLedgerListener;
import cn.bitcss.arctra.runtime.react.execution.DurableContinuationExecutor;
import cn.bitcss.arctra.runtime.react.execution.DurableExecutionHandler;
import cn.bitcss.arctra.runtime.react.execution.EphemeralExecutionHandler;
import cn.bitcss.arctra.runtime.react.execution.ExecutionFlowCoordinator;
import cn.bitcss.arctra.runtime.react.execution.ExecutionMode;
import cn.bitcss.arctra.runtime.react.execution.ModelContinuationExecutor;
import cn.bitcss.arctra.runtime.react.governance.GovernanceToolCallingAdvisor;
import cn.bitcss.arctra.runtime.react.protocol.SpringAiResumedExecutionHandler;
import cn.bitcss.arctra.runtime.react.protocol.ToolApprovalRequiredSignal;
import cn.bitcss.arctra.runtime.react.tool.EvidenceCapturingToolCallback;
import cn.bitcss.arctra.procedure.ProcedureExecutionHandler;
import cn.bitcss.arctra.procedure.ProcedureExecutionState;
import cn.bitcss.arctra.procedure.ReusableProcedure;
import cn.bitcss.arctra.procedure.SimpleProcedureMatcher;
import cn.bitcss.arctra.procedure.StepExecutionResult;
import cn.bitcss.arctra.runtime.ProcessFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
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

  // M6-T4E invocation-state store (paired with checkpoint store)
  private final InvocationStateStore invocationStateStore;

    // M6-T2.5A-R4 Durable resume coordinator (null in ephemeral mode)
  private final DurableResumeCoordinator durableResumeCoordinator;

  // M6-T6.4 Execution flow coordinator
  private final ExecutionFlowCoordinator executionFlowCoordinator;

  // M6-T6.4 Model continuation executor (unified ChatClient execution)
  private final ModelContinuationExecutor modelContinuationExecutor;

  // M8-Integration components (optional - null when not configured)
  private final SimpleProcedureMatcher procedureMatcher;
  private final ProcedureExecutionHandler procedureExecutionHandler;

  // M8-Phase3.4: Execution event sink for emitting events
  private final ExecutionEventListener executionEventSink;

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
    this(chatModel, tools, chatMemory, governancePolicy, null, null, null, null, null, null);
  }

  /**
   * Create a tool-calling engine with durable suspension capability (M5 backward compatibility).
   *
   * <p>This constructor exists for backward compatibility with M5 tests. New code should use the
   * 10-parameter constructor with explicit ExecutionLedger and M8 components.
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
   * @deprecated Use 10-parameter constructor with explicit ExecutionLedger and M8 components
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
         runtimeBindingKey, null, null, null);
  }

  /**
   * Create a tool-calling engine with durable suspension capability (M5 + M8).
   *
   * <p><strong>Durable configuration (all-or-nothing):</strong> Either all three durable
   * parameters are provided, or all are null for ephemeral-only mode.
   *
   * <p><strong>Execution ledger (optional):</strong> ExecutionLedger may be provided independently
   * for audit trail. If null, no execution events are recorded.
   *
   * <p><strong>M8 execution path learning (optional):</strong> If procedureMatcher and
   * procedureExecutionHandler are provided, the engine will attempt to match and execute cached
   * procedures before falling back to ReAct.
   *
   * @param chatModel the chat model
   * @param tools the tools available
   * @param chatMemory the chat memory
   * @param governancePolicy the governance policy
   * @param checkpointStore checkpoint store for durable suspension (null for ephemeral)
   * @param bindingResolver runtime binding resolver for recovery (null for ephemeral)
   * @param runtimeBindingKey logical binding key for this engine (null for ephemeral)
   * @param executionLedger execution ledger for audit trail (null to disable)
   * @param procedureMatcher M8 procedure matcher (null to disable cached execution)
   * @param procedureExecutionHandler M8 procedure execution handler (null to disable cached execution)
   * @throws IllegalArgumentException if durable configuration is partial or M8 configuration is partial
   * @since M5-T4 (M8-Integration)
   */
  public SpringAiToolCallingEngine(
      ChatModel chatModel,
      List<ToolCallback> tools,
      ChatMemory chatMemory,
      ToolGovernancePolicy governancePolicy,
      CheckpointStore checkpointStore,
      RuntimeBindingResolver bindingResolver,
      String runtimeBindingKey,
      ExecutionLedger executionLedger,
      SimpleProcedureMatcher procedureMatcher,
      ProcedureExecutionHandler procedureExecutionHandler) {

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

    // M8-Integration: Validate M8 configuration (all-or-nothing)
    boolean hasMatcher = procedureMatcher != null;
    boolean hasHandler = procedureExecutionHandler != null;

    if (hasMatcher || hasHandler) {
      if (!hasMatcher || !hasHandler) {
        throw new IllegalArgumentException(
            "Partial M8 configuration rejected. M8 execution path learning requires both "
                + "procedureMatcher and procedureExecutionHandler. Got: procedureMatcher="
                + (hasMatcher ? "present" : "null")
                + ", procedureExecutionHandler="
                + (hasHandler ? "present" : "null"));
      }
    }

    this.procedureMatcher = procedureMatcher;
    this.procedureExecutionHandler = procedureExecutionHandler;

    // M6-T2B.1: adapt ExecutionLedger to ExecutionEventListener once at construction
      // M6-T2B.1 execution event sink (always non-null)
      this.executionEventSink = adaptLedgerToListener(executionLedger);

    // M6-T4E: Create invocation-state store paired with checkpoint store
    this.invocationStateStore = createMatchingInvocationStateStore(checkpointStore);

    // M6-T4C Phase 1: Create recovery classifier (package-private internal)
    InvocationRecoveryClassifier recoveryClassifier =
        new InvocationRecoveryClassifier(invocationStateStore);

    // M6-T2.5A-R3: Create resumed execution handler
      // M6-T2.5A-R3 Spring AI resumed execution handler (M6-T4A: pass invocationStateStore)
      SpringAiResumedExecutionHandler resumedExecutionHandler =
          new SpringAiResumedExecutionHandler(chatModel, chatMemory, tools, governancePolicy, invocationStateStore);

    // M6-T2.5A-R4: Construct durable resume coordinator if durable mode is configured
    if (checkpointStore != null) {
      // M6-T4C Phase 1: Pass recoveryClassifier to coordinator
      this.durableResumeCoordinator =
          new DurableResumeCoordinator(
              checkpointStore, bindingResolver, executionEventSink, resumedExecutionHandler, this, recoveryClassifier);
    } else {
      this.durableResumeCoordinator = null;
    }

    // M6-T6.4: Initialize execution flow decomposition components
    this.modelContinuationExecutor =
        new ModelContinuationExecutor(chatModel, tools, chatMemory, governancePolicy);

    EphemeralExecutionHandler ephemeralHandler =
        new EphemeralExecutionHandler(modelContinuationExecutor);

    DurableExecutionHandler durableHandler = null;
    if (checkpointStore != null) {
      DurableContinuationExecutor durableContinuationExecutor =
          new DurableContinuationExecutor(
              checkpointStore,
              invocationStateStore,
              tools,
              chatMemory,
              executionEventSink,
              modelContinuationExecutor,
              this);

      durableHandler =
          new DurableExecutionHandler(
              checkpointStore, runtimeBindingKey, executionEventSink, durableContinuationExecutor);
    }

    this.executionFlowCoordinator =
        new ExecutionFlowCoordinator(ephemeralHandler, durableHandler);
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

  /**
   * Get the execution mode for this engine.
   *
   * <p><strong>M6-T6.4 Phase 10:</strong> Returns {@link ExecutionMode} enum for type-safe
   * dispatch.
   *
   * @return DURABLE if CheckpointStore is configured, EPHEMERAL otherwise
   * @since M6-T6.4 Phase 10
   */
  private ExecutionMode getExecutionMode() {
    return checkpointStore != null ? ExecutionMode.DURABLE : ExecutionMode.EPHEMERAL;
  }

  /**
   * Create matching InvocationStateStore paired with CheckpointStore.
   *
   * <p><strong>M6 V1 Durable Capability Contract:</strong>
   *
   * <ul>
   *   <li><strong>Ephemeral mode (checkpointStore == null):</strong> Returns
   *       InMemoryInvocationStateStore for in-process execution.
   *   <li><strong>JDBC persistent mode:</strong> Pairs JdbcCheckpointStore with
   *       JdbcInvocationStateStore sharing the same DataSource. This ensures restart-durable
   *       recovery substrate coherence.
   *   <li><strong>InMemory test mode:</strong> Pairs InMemoryCheckpointStore with
   *       InMemoryInvocationStateStore. This is for testing only and does NOT provide
   *       restart-durable guarantees.
   *   <li><strong>Unsupported custom CheckpointStore:</strong> Throws IllegalArgumentException.
   *       Custom durable persistence providers are not supported in V1 because restart-safe DURABLE
   *       execution requires a matching persistent InvocationStateStore for T5 recovery
   *       classification.
   * </ul>
   *
   * @param checkpointStore the configured checkpoint store (may be null for ephemeral)
   * @return matching invocation state store
   * @throws IllegalArgumentException if checkpointStore is a non-JDBC/non-InMemory persistent implementation
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

    if (checkpointStore instanceof cn.bitcss.arctra.checkpoint.InMemoryCheckpointStore) {
      // Test-only mode - in-memory pairing for same-process testing
      // NOT restart-durable
      return new InMemoryInvocationStateStore();
    }

    // M6 V1 freeze: Unsupported custom persistent CheckpointStore
    // Fail-fast instead of silently downgrading to InMemory
    throw new IllegalArgumentException(
        "Unsupported CheckpointStore for restart-safe DURABLE execution. "
            + "Arctra V1 supports JdbcCheckpointStore because durable execution requires "
            + "a matching persistent InvocationStateStore for recovery classification. "
            + "Custom durable persistence providers are not supported by the current V1 runtime. "
            + "Provided: "
            + checkpointStore.getClass().getName());
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

    // M8-Integration: Attempt procedure matching if M8 components are configured
    if (procedureMatcher != null && procedureExecutionHandler != null) {
      String agentName = definition.name();
      String userPrompt = request.userMessage();

      var matchedProcedure = procedureMatcher.findMatch(agentName, userPrompt);

      if (matchedProcedure.isPresent()) {
        // Execute cached procedure path
        return executeCachedProcedure(matchedProcedure.get(), definition, request, context);
      }
    }

    // No match or M8 not configured - fall through to ReAct
    return executeReActPath(definition, request, context);
  }

  /**
   * Execute standard ReAct path (M8-Integration helper).
   *
   * <p>Extracted from original execute() to enable routing decision between cached and ReAct paths.
   *
   * @param definition agent definition
   * @param request agent request
   * @param context execution context
   * @return execution result
   * @since M8-Integration
   */
  private AgentResult executeReActPath(
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
      // Construct system instruction
      var systemInstruction = modelContinuationExecutor.buildSystemInstruction(definition);

      // Build initial messages (system + user)
      String sessionId = context.sessionId();
      List<Message> initialMessages = new ArrayList<>();
      initialMessages.add(new org.springframework.ai.chat.messages.SystemMessage(systemInstruction));
      initialMessages.add(new org.springframework.ai.chat.messages.UserMessage(request.userMessage()));

      // M6-T6.4: Delegate to ModelContinuationExecutor for unified execution
      return modelContinuationExecutor.executeWithMessages(
          initialMessages, evidences, definition, context, wrappedTools, governanceAdvisor, true);

    } catch (ToolApprovalRequiredSignal signal) {
      // M6-T6.4: Route to ExecutionFlowCoordinator based on disposition and durability
      return executionFlowCoordinator.route(
          signal.state(), evidences, definition, context, getExecutionMode());

    } finally {
      // Cleanup thread-local state
      governanceAdvisor.clearState();
    }
  }

  /**
   * Execute cached procedure path (M8-Integration).
   *
   * <p>Executes a matched procedure step-by-step using ProcedureExecutionHandler.
   *
   * <p><strong>V1 Execution Flow:</strong>
   *
   * <ol>
   *   <li>Initialize ProcedureExecutionState with empty inputs (V1 simplification)
   *   <li>Loop: executeNextStep() → execute tool → advanceAfterSuccess()
   *   <li>If REQUIRE_APPROVAL → suspend with checkpoint (TODO)
   *   <li>If DENY → mark procedure INVALID and fall back to ReAct
   *   <li>If completed → return success result
   * </ol>
   *
   * <p><strong>V1 Limitations:</strong>
   *
   * <ul>
   *   <li>No input binding from user prompt (all parameters use INPUT binding with empty map)
   *   <li>No checkpoint creation for REQUIRE_APPROVAL (deferred to Phase 3.4)
   *   <li>No evidence capture from cached execution
   *   <li>Procedure marked INVALID on any governance denial or execution error
   * </ul>
   *
   * @param procedure matched procedure to execute
   * @param definition agent definition
   * @param request agent request
   * @param context execution context
   * @return execution result
   * @since M8-Integration
   */
  private AgentResult executeCachedProcedure(
      ReusableProcedure procedure,
      AgentDefinition definition,
      AgentRequest request,
      AgentExecutionContext context) {

    // V1 simplification: empty input bindings
    // TODO M8-E: Extract actual inputs from user prompt
    var executionState = cn.bitcss.arctra.procedure.ProcedureExecutionState.initial(
        procedure.procedureId(), procedure.revision(), java.util.Map.of());

    try {
      // Execute steps sequentially
      while (!executionState.isComplete(procedure.steps().size())) {
        var stepResult = procedureExecutionHandler.executeNextStep(procedure, executionState);

        if (stepResult.isCompleted()) {
          break;
        }

        if (stepResult.requiresApproval()) {
          // Phase 3.4: Create checkpoint and suspend
          return handleProcedureApprovalRequired(
              procedure, executionState, stepResult, definition, context);
        }

        if (!stepResult.isAllowed()) {
          // Should not reach here - DENY throws exception in executeNextStep
          throw new IllegalStateException("Unexpected step result: not ALLOWED, not REQUIRE_APPROVAL, not COMPLETED");
        }

        // Execute the approved tool call
        var pendingCall = stepResult.pendingCall();
        String toolResult = executeToolDirectly(pendingCall, context);

        // Advance state with captured output
        var currentStep = procedure.steps().get(executionState.currentStepIndex());
        executionState = procedureExecutionHandler.advanceAfterSuccess(
            executionState, currentStep, toolResult);
      }

      // All steps completed successfully
      return new AgentResult(
          "Procedure executed successfully (cached path)",
          List.of()); // V1: no evidence capture, no process (completed)

    } catch (cn.bitcss.arctra.procedure.ProcedureGovernanceException e) {
      // Governance policy changed - procedure no longer valid
      // Mark as INVALID and fall back to ReAct
      markProcedureInvalid(procedure, "Governance denial: " + e.getMessage());
      return executeReActPath(definition, request, context);

    } catch (Exception e) {
      // Any other error - mark procedure invalid and fall back
      markProcedureInvalid(procedure, "Execution failed: " + e.getMessage());
      return executeReActPath(definition, request, context);
    }
  }

  /**
   * Execute tool directly without ReAct loop (M8-Integration helper).
   *
   * <p>V1 simplification: Executes tool synchronously without model interaction.
   *
   * @param pendingCall pending tool call
   * @param context execution context
   * @return tool result (JSON string)
   * @since M8-Integration
   */
  private String executeToolDirectly(
      cn.bitcss.arctra.checkpoint.PendingToolCall pendingCall,
      AgentExecutionContext context) {

    // Find matching tool
    var tool = tools.stream()
        .filter(t -> t.getToolDefinition().name().equals(pendingCall.toolName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Tool not found: " + pendingCall.toolName()));

    // Execute tool
    String result = tool.call(pendingCall.arguments());

    return result != null ? result : "{}";
  }

  /**
   * Handle REQUIRE_APPROVAL during procedure execution (M8-Phase3.4).
   *
   * <p>Creates a durable checkpoint with procedure execution state and suspends.
   *
   * <p><strong>Checkpoint semantics:</strong>
   *
   * <ul>
   *   <li>disposition: WAITING_FOR_SIGNAL (requires approval to continue)
   *   <li>procedureState: current execution state (for step-by-step resume)
   *   <li>pendingBatch: the tool call that requires approval
   * </ul>
   *
   * @param procedure procedure being executed
   * @param executionState current procedure execution state
   * @param stepResult step execution result (requiresApproval = true)
   * @param definition agent definition
   * @param context execution context
   * @return suspended agent result with durable process
   * @throws IllegalStateException if durable mode not configured
   * @since M8-Phase3.4
   */
  private AgentResult handleProcedureApprovalRequired(
      ReusableProcedure procedure,
      ProcedureExecutionState executionState,
      StepExecutionResult stepResult,
      AgentDefinition definition,
      AgentExecutionContext context) {

    // Precondition: durable mode must be configured for checkpoint creation
    if (checkpointStore == null || runtimeBindingKey == null) {
      // Cannot suspend without durable configuration - fall back to ReAct
      markProcedureInvalid(procedure, "REQUIRE_APPROVAL requires durable configuration");
      // Create a synthetic request for ReAct fallback
      AgentRequest fallbackRequest = new AgentRequest(
          "Execute: " + procedure.steps().get(executionState.currentStepIndex()).toolName());
      return executeReActPath(definition, fallbackRequest, context);
    }

    // 1. Generate stable processId
    String processId = java.util.UUID.randomUUID().toString();

    // 2. Build pendingBatch with single tool call that requires approval
    PendingToolCall pendingCall = stepResult.pendingCall();
    List<PendingToolCall> pendingBatch = List.of(pendingCall);

    // 3. Build checkpoint with procedureState
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            processId,
            1L, // Initial version
            runtimeBindingKey,
            context.sessionId(),
            ContinuationDisposition.WAITING_FOR_SIGNAL, // Requires approval
            pendingBatch,
            List.of(), // M8 V1: no evidence capture from cached execution
            cn.bitcss.arctra.runtime.react.durable.ExecutionIncarnation.current(),
            executionState); // Current procedure execution state

    // 4. Persist checkpoint FIRST (durability-first)
    try {
      checkpointStore.create(checkpoint);
    } catch (Exception e) {
      // Checkpoint creation failed - fall back to ReAct
      markProcedureInvalid(procedure, "Checkpoint creation failed: " + e.getMessage());
      // Create a synthetic request for ReAct fallback
      AgentRequest fallbackRequest = new AgentRequest(
          "Execute: " + procedure.steps().get(executionState.currentStepIndex()).toolName());
      return executeReActPath(definition, fallbackRequest, context);
    }

    // 5. Emit events
    executionEventSink.onEvent(
        new ExecutionEvent(
            processId,
            EventType.APPROVAL_REQUIRED,
            null,
            String.format(
                "{\"toolName\": \"%s\", \"source\": \"cached_procedure\"}",
                pendingCall.toolName())));

    executionEventSink.onEvent(
        new ExecutionEvent(
            processId,
            EventType.SUSPENDED,
            1L,
            String.format(
                "{\"checkpointVersion\": 1, \"procedureId\": \"%s\", \"stepIndex\": %d}",
                procedure.procedureId(), executionState.currentStepIndex())));

    // 6. Create durable process (ONLY after checkpoint persisted)
    AgentProcess process = ProcessFactory.createDurableSuspended(processId, 1L, this);

    // 7. Return suspended result
    String partialContent =
        String.format(
            "Procedure execution suspended: tool '%s' requires approval (step %d/%d)",
            pendingCall.toolName(),
            executionState.currentStepIndex() + 1,
            procedure.steps().size());
    return new AgentResult(partialContent, List.of(), process);
  }

  /**
   * Mark procedure as INVALID due to execution failure (M8-Integration helper).
   *
   * <p>V1 simplification: Only logs the invalidation. Store update deferred to Phase 4.
   *
   * @param procedure procedure to mark invalid
   * @param reason invalidation reason
   * @since M8-Integration
   */
  private void markProcedureInvalid(ReusableProcedure procedure, String reason) {
    // TODO Phase 4: Update store with INVALID status
    // For now, just log
    System.err.println("M8: Marking procedure INVALID: " + procedure.procedureId()
        + " rev " + procedure.revision() + " - " + reason);
  }

  // M6-T6.4: Old suspension methods removed - logic moved to execution components
  // M6-T6.4: buildSystemInstruction moved to ModelContinuationExecutor (single authority)

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

    // M6-T4F: Delegate to coordinator with current execution incarnation
    // Mode selection occurs INSIDE CHECK A to prevent TOCTOU
    return durableResumeCoordinator.resume(
        processId, checkpointVersion, signal, ExecutionIncarnation.current());
  }

  /**
   * Access recovery resolution capability (M6-T5).
   *
   * @return recovery resolution API
   * @throws IllegalStateException if durable mode not configured
   * @since M6-T5
   */
  @Override
  public cn.bitcss.arctra.runtime.RecoveryResolution recovery() {
    if (durableResumeCoordinator == null) {
      throw new IllegalStateException(
          "recovery() requires complete durable configuration. "
              + "Current: checkpointStore="
              + (checkpointStore != null ? "present" : "null")
              + ", bindingResolver="
              + (bindingResolver != null ? "present" : "null")
              + ", runtimeBindingKey="
              + (runtimeBindingKey != null ? "present" : "null"));
    }

    return durableResumeCoordinator.recovery();
  }
}

