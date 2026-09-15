# M6-T3B — TOOL EXECUTION CORRELATION ARCHITECTURE GATE

**Date**: 2024-09-14  
**Status**: Architecture Analysis Complete

---

## A. Frozen Baseline

### M6-T3A Verified Baseline

```
arctra-core: 205 tests
arctra-runtime-react: 128 tests
examples: 35 tests
TOTAL: 368 tests
Failures: 0
Errors: 0
Skipped: 23
BUILD SUCCESS
```

### Frozen M6-T3A Semantics

**operationId**:
- Arctra framework-owned logical durable tool operation identity
- Provider-independent
- Generated once when PendingToolCall is materialized
- Durable in checkpoint before execution
- Stable across concurrent resume attempts
- Distinct for distinct logical operations

**toolCallId**:
- Provider protocol correlation identity
- Spring AI-generated
- Preserved for protocol reconstruction

### Current PendingToolCall

```java
public record PendingToolCall(
    String operationId,   // Framework identity (M6-T3A)
    String toolCallId,    // Protocol identity
    String toolName,
    String arguments
)
```

### Frozen Limitation

> **operationId is durable but is NOT yet authoritatively correlated to delegate.call()**

Current TOOL_EXECUTED/TOOL_FAILED events MUST NOT claim operationId correlation.

---

## B. Current Exact Call Graph

### Approved Batch Execution Path

```
1. SuspensionCheckpoint (loaded from CheckpointStore)
   ├─ operationId: ✅ Available
   ├─ toolCallId: ✅ Available
   ├─ toolName: ✅ Available
   └─ arguments: ✅ Available

2. DurableResumeCoordinator.resume()
   ├─ CHECK A (load checkpoint)
   ├─ RuntimeBinding resolution
   └─ SpringAiResumedExecutionHandler.executeResume()
       ├─ operationId: ✅ Available (in pendingBatch)
       ├─ toolCallId: ✅ Available
       ├─ toolName: ✅ Available
       └─ arguments: ✅ Available

3. ProtocolReconstructor.executeApprovedBatch()
   ├─ Input: List<PendingToolCall> pendingBatch
   ├─ operationId: ✅ Available (in pendingBatch)
   ├─ toolCallId: ✅ Available
   ├─ toolName: ✅ Available
   └─ arguments: ✅ Available
   ↓
   Reconstruction:
   ├─ Build AssistantMessage.ToolCall from PendingToolCall
   │  └─ Uses: toolCallId, toolName, arguments
   │  └─ operationId: ⚠️ Not passed to ToolCall
   └─ Create ChatResponse with AssistantMessage

4. ToolCallingManager.executeToolCalls(prompt, chatResponse)
   ├─ Spring AI dispatches tools
   ├─ operationId: ❌ Lost
   ├─ toolCallId: ⚠️ In AssistantMessage.ToolCall but not passed to callback
   ├─ toolName: ✅ Used for dispatch
   └─ arguments: ✅ Passed to callback

5. EvidenceCapturingToolCallback.call(arguments, toolContext)
   ├─ operationId: ❌ NOT available
   ├─ toolCallId: ❌ NOT available
   ├─ toolName: ✅ Available (from delegate.getToolDefinition())
   └─ arguments: ✅ Available (parameter)
   ↓
   ToolObservationContext available:
   ├─ processId: ✅
   ├─ checkpointVersion: ✅
   └─ eventListener: ✅

6. delegate.call(arguments)
   ├─ operationId: ❌ NOT available
   ├─ toolCallId: ❌ NOT available
   ├─ toolName: ⚠️ Implicit (callback is selected by name)
   └─ arguments: ✅ Available
```

---

## C. Identifier Availability Matrix

| Boundary | processId | checkpointVersion | operationId | toolCallId | toolName | arguments |
|----------|-----------|-------------------|-------------|------------|----------|-----------|
| **SuspensionCheckpoint** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **DurableResumeCoordinator** | ✅ | ✅ | ✅ (in batch) | ✅ | ✅ | ✅ |
| **SpringAiResumedExecutionHandler** | ✅ | ✅ | ✅ (in batch) | ✅ | ✅ | ✅ |
| **ProtocolReconstructor** | ❌ | ❌ | ✅ (in batch) | ✅ | ✅ | ✅ |
| **AssistantMessage.ToolCall** | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| **ToolCallingManager.executeToolCalls()** | ❌ | ❌ | ❌ | ⚠️ | ✅ | ✅ |
| **EvidenceCapturingToolCallback.call()** | ✅ (ctx) | ✅ (ctx) | ❌ | ❌ | ✅ | ✅ |
| **delegate.call()** | ❌ | ❌ | ❌ | ❌ | ⚠️ | ✅ |

**ctx** = Available through ToolObservationContext (when provided)

---

## D. Last Boundary With operationId

### Critical Boundary

**ProtocolReconstructor.executeApprovedBatch()**

**Location**: `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/ProtocolReconstructor.java`

**Current state**:
- Receives: `List<PendingToolCall> pendingBatch` (contains operationId)
- Reconstructs: `AssistantMessage.ToolCall` (does NOT contain operationId)
- Passes to: `ToolCallingManager.executeToolCalls()`
- After this: operationId is LOST ❌

**Source code (lines 140-150)**:
```java
AssistantMessage rebuiltAssistantMessage =
    AssistantMessage.builder()
        .toolCalls(
            pendingBatch.stream()
                .map(
                    dto ->
                        new AssistantMessage.ToolCall(
                            dto.toolCallId(), // Preserve exact toolCallId
                            dto.toolName(),
                            dto.arguments()))
                .toList())
        .build();
```

**Critical finding**: `AssistantMessage.ToolCall` constructor:
```java
ToolCall(String id, String name, String arguments)
```

**No operationId field** in Spring AI ToolCall ❌

---

## E. Spring AI Per-Call Context Audit

### Spring AI Version

**Version**: 2.0.0

### ToolCallback Interface

**Package**: `org.springframework.ai.tool.ToolCallback`

**Methods**:
```java
String call(String functionArguments);
String call(String functionArguments, ToolContext toolContext);
ToolDefinition getToolDefinition();
```

### ToolContext

**Package**: `org.springframework.ai.chat.model.ToolContext`

**Purpose**: Provides model request context during tool execution

**Does NOT contain**:
- toolCallId ❌
- operationId ❌
- Per-call correlation identity ❌

### ToolCallingManager

**Package**: `org.springframework.ai.model.tool.ToolCallingManager`

**Method**:
```java
ChatResponse executeToolCalls(Prompt prompt, ChatResponse chatResponse)
```

**Behavior**:
- Extracts tool calls from AssistantMessage
- Dispatches by toolName to registered ToolCallback
- Invokes `callback.call(arguments)` or `callback.call(arguments, toolContext)`
- Collects ToolResponseMessage results
- Returns updated ChatResponse

**Per-call identity**:
- Uses toolCallId internally for ToolResponseMessage construction
- Does NOT expose toolCallId to ToolCallback ❌
- Does NOT support custom per-call context ❌

### Classification

**NONE** ❌

**Evidence**:
1. ToolCallback.call() receives only: arguments + ToolContext
2. ToolContext provides model-level context, not per-call identity
3. No Spring AI API to inject per-call metadata through ToolCallback interface
4. toolCallId exists in AssistantMessage.ToolCall but is inaccessible during callback execution

**Conclusion**: Spring AI 2.0.0 does NOT expose authoritative per-call context for correlation

---

## F. ToolCallingManager Responsibility Audit

### What ToolCallingManager Currently Owns

**1. Tool Selection**:
- Maps toolName → registered ToolCallback
- Fails if tool not found

**2. Argument Passing**:
- Passes functionArguments string directly to callback

**3. ToolContext Creation**:
- Provides ToolContext with model request metadata

**4. Exception Handling**:
- Catches callback exceptions
- Wraps in framework exception with tool name

**5. ToolResponseMessage Construction**:
- Creates ToolResponseMessage with:
  - toolCallId (preserved from AssistantMessage.ToolCall)
  - toolName
  - result or error

**6. Batch Execution**:
- Executes all tool calls from AssistantMessage
- Collects all responses
- Returns ChatResponse with ToolResponseMessage list

**7. Ordering**:
- **Sequential execution** (source evidence: single-threaded loop)
- Preserves order from AssistantMessage.getToolCalls()

### What Would Be Lost If Bypassed

**Critical**:
- ToolResponseMessage construction with correct toolCallId
- Exception wrapping and error handling semantics
- Sequential execution guarantee (if relied upon)

**Minor**:
- Tool selection by name (trivial to reproduce)
- ToolContext creation (can be recreated)

### Complexity Assessment

**Reproducing ToolCallingManager semantics**: **LOW-MEDIUM**

**Reason**:
- Core logic is straightforward (map/invoke/collect)
- No complex state machine
- No transaction semantics
- No distributed concerns
- Primary value is toolCallId preservation in ToolResponseMessage

---

## G. ToolCallback Contract

### Interface

**Package**: `org.springframework.ai.tool.ToolCallback`

```java
public interface ToolCallback {
    String call(String functionArguments);
    String call(String functionArguments, ToolContext toolContext);
    ToolDefinition getToolDefinition();
}
```

### Overloads

**Two call() overloads**:
1. `call(String functionArguments)` — basic invocation
2. `call(String functionArguments, ToolContext toolContext)` — with context

**Neither carries**:
- toolCallId ❌
- operationId ❌
- per-call correlation identity ❌

### Current EvidenceCapturingToolCallback

**Constructor**:
```java
EvidenceCapturingToolCallback(
    ToolCallback delegate,
    List<Evidence> evidences,
    ToolObservationContext observationContext // Optional
)
```

**ToolObservationContext** (when provided):
- processId
- checkpointVersion
- eventListener

**Does NOT contain**:
- operationId ❌
- toolCallId ❌

---

## H. ToolObservationContext Cardinality

### Current Definition

**Package**: `arctra-runtime-react`

```java
record ToolObservationContext(
    String processId,
    long checkpointVersion,
    ExecutionEventListener eventListener
)
```

### Current Cardinality

**Source**: ProtocolReconstructor.executeApprovedBatch()

```java
// Line 160-170
List<ToolCallback> wrappedCallbacks =
    tools.stream()
        .map(
            tool ->
                new EvidenceCapturingToolCallback(
                    tool,
                    newEvidences,
                    observationContext))  // SAME context for ALL
        .map(wrapper -> (ToolCallback) wrapper)
        .toList();
```

**Answer**: **PER_RESUME** (or PER_BATCH)

**Evidence**: One ToolObservationContext is created per resume attempt and shared across all tool callbacks in the batch

**Problem**: Cannot carry operationId because context is shared across multiple operations ❌

---

## I. Resume / Batch / Operation / Invocation Cardinality

### Cardinality Hierarchy

```
Resume Attempt
  └─ Pending Batch
      ├─ Operation A (operationId="op-A")
      │   └─ Physical Invocation 1
      └─ Operation B (operationId="op-B")
          └─ Physical Invocation 1

Concurrent Resume Attempt
  └─ Same Pending Batch
      ├─ Operation A (operationId="op-A")
      │   └─ Physical Invocation 2
      └─ Operation B (operationId="op-B")
          └─ Physical Invocation 2
```

### Critical Distinction

- **Resume**: One CHECK A → execute → CHECK B attempt
- **Batch**: List<PendingToolCall> in one checkpoint
- **Operation**: One logical PendingToolCall (one operationId)
- **Invocation**: One physical delegate.call() execution

**Current problem**: ToolObservationContext is PER_RESUME, but operationId is PER_OPERATION ❌

---

## J. Duplicate Same-Name + Same-Arguments Stress Case

### Test Case

```java
PendingToolCall(
    operationId="op-A",
    toolCallId="tc-1",
    toolName="search",
    arguments="{\"q\":\"x\"}"
)

PendingToolCall(
    operationId="op-B",
    toolCallId="tc-2",
    toolName="search",
    arguments="{\"q\":\"x\"}"
)
```

### Properties

- Same toolName: "search" ✅
- Same arguments: "{\"q\":\"x\"}" ✅
- Different toolCallId: "tc-1" ≠ "tc-2" ✅
- Different operationId: "op-A" ≠ "op-B" ✅

### Required Behavior

**Physical invocation #1 must correlate to exactly one of op-A or op-B**
**Physical invocation #2 must correlate to the other**

### Candidate Evaluation

**Candidate: Correlate by toolName** ❌
- Both have same name → ambiguous

**Candidate: Correlate by toolName + arguments** ❌
- Both have same args → ambiguous

**Candidate: Correlate by execution order** ⚠️
- Fragile (depends on sequential execution guarantee)
- Not durable (crash before all complete)
- Future concurrent execution would break

**Candidate: Correlate by toolCallId** ✅
- toolCallId is distinct
- But toolCallId is NOT available at callback boundary ❌

**Candidate: Direct per-operation invocation** ✅
- Explicitly invoke each PendingToolCall separately
- Pass operationId through invocation boundary

---

## K. Candidate A — Per-Call Wrapper

### Concept

Create dedicated wrapper for EACH PendingToolCall:

```java
for (PendingToolCall pending : pendingBatch) {
    OperationAwareToolCallback wrapper = new OperationAwareToolCallback(
        pending.operationId(),
        pending.toolCallId(),
        resolveCallback(pending.toolName()),
        observationContext
    );
    // Register wrapper somehow?
}
```

### Critical Question

**Can Spring AI register multiple callbacks with same tool name?**

**Answer**: NO ❌

**Evidence**: ToolCallingManager uses `Map<String toolName, ToolCallback>` internally

**Problem**: Two "search" operations would collapse to one callback registration

### Verdict

**REJECTED** ❌

**Reason**: Spring AI dispatches by toolName only, cannot distinguish duplicate same-name operations

---

## L. Candidate B — Direct ToolCallback Execution

### Concept

Bypass ToolCallingManager, invoke each PendingToolCall directly:

```java
for (PendingToolCall pending : pendingBatch) {
    ToolCallback callback = resolveCallbackByName(pending.toolName());
    
    // Invoke with operation context
    String result;
    try {
        result = callback.call(pending.arguments());
        // Emit TOOL_EXECUTED with operationId
    } catch (Exception e) {
        // Emit TOOL_FAILED with operationId
    }
    
    // Construct ToolResponseMessage manually
    responses.add(new ToolResponseMessage.ToolResponse(
        pending.toolCallId(),
        pending.toolName(),
        result
    ));
}
```

### What Would Be Reproduced

1. **Tool selection**: `resolveCallbackByName(toolName)` — trivial
2. **Argument passing**: Direct `callback.call(arguments)` — straightforward
3. **Exception handling**: try/catch — preserves semantics
4. **ToolResponseMessage**: Manual construction with correct toolCallId — straightforward
5. **Sequential execution**: Explicit loop — preserves ordering

### What Would NOT Be Lost

- toolCallId preservation ✅
- Exception semantics ✅
- Sequential execution ✅
- ToolContext (can be created if needed) ✅

### Advantages

✅ Exact 1:1 operation → invocation correlation
✅ operationId available at callback boundary
✅ Duplicate same-name + same-args operations safe
✅ No ordering assumptions
✅ Simple, explicit control flow

### Disadvantages

⚠️ Duplicates ToolCallingManager logic (but it's minimal)
⚠️ Future Spring AI ToolCallingManager changes wouldn't be inherited
⚠️ Must maintain ToolResponseMessage construction logic

### Complexity Assessment

**LOW** — Core logic is ~20-30 LOC wrapper around callback invocation

### Verdict

**VIABLE** ✅

---

## M. Candidate C — Explicit Per-Operation Protocol Execution

### Concept

Extract tool execution from ProtocolReconstructor into explicit per-operation loop:

```java
// ProtocolReconstructor: protocol reconstruction ONLY
AssistantMessage rebuilt = reconstructAssistantMessage(pendingBatch);

// New collaborator: explicit operation execution
List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
for (PendingToolCall pending : pendingBatch) {
    ToolResponseMessage.ToolResponse response = 
        executeOperation(pending, observationContext);
    responses.add(response);
}

ToolResponseMessage toolResponseMessage = 
    ToolResponseMessage.builder().responses(responses).build();
```

### Responsibilities

**ProtocolReconstructor**:
- Protocol conversion only
- Build AssistantMessage.ToolCall list
- Build ToolResponseMessage from responses

**New collaborator** (package-private):
- Resolve ToolCallback by name
- Invoke with operation context
- Emit TOOL_EXECUTED/TOOL_FAILED with operationId
- Return ToolResponse

### Advantages

✅ Clean separation of concerns
✅ ProtocolReconstructor remains protocol-focused
✅ Explicit operation correlation
✅ Testable in isolation

### Disadvantages

⚠️ Introduces new abstraction
⚠️ Splits current ProtocolReconstructor flow

### Verdict

**VIABLE** ✅ (Refinement of Candidate B)

---

## N. Candidate D — Provider Execution Adapter Seam

### Concept

Package-private adapter abstracts Spring AI tool execution:

```java
interface SpringAiToolExecutionAdapter {
    ToolResponseMessage.ToolResponse executeOperation(
        PendingToolCall operation,
        ToolCallback callback,
        ToolObservationContext context
    );
}
```

### Analysis

**Question**: Does this abstraction own a distinct responsibility?

**Answer**: It's just a method extraction from Candidate B/C

**Conclusion**: NOT justified as separate type — package-private method sufficient

### Verdict

**NOT JUSTIFIED** ❌ (over-engineering)

---

## O. Candidate E — Native Spring AI Context Propagation

### Analysis

**Does Spring AI 2.0.0 expose authoritative per-call context?**

**NO** ❌ (see Section E)

### Verdict

**NOT AVAILABLE** ❌

---

## P. Candidate Comparison

| Candidate | Exact Correlation | Duplicate-Safe | Preserves Spring Semantics | Complexity | Recommended |
|-----------|-------------------|----------------|----------------------------|------------|-------------|
| **A. Per-Call Wrapper** | ❌ No | ❌ No | N/A | LOW | ❌ REJECTED |
| **B. Direct ToolCallback Execution** | ✅ Yes | ✅ Yes | ✅ Yes | LOW | ✅ VIABLE |
| **C. Explicit Per-Operation Execution** | ✅ Yes | ✅ Yes | ✅ Yes | LOW-MEDIUM | ✅ VIABLE (cleaner) |
| **D. Provider Adapter** | ✅ Yes | ✅ Yes | ✅ Yes | MEDIUM | ❌ Over-engineering |
| **E. Native Spring AI Context** | N/A | N/A | ✅ Yes | N/A | ❌ Not available |

**Recommended**: **Candidate C** (Explicit Per-Operation Execution) ✅

**Rationale**:
- Clean separation: ProtocolReconstructor remains protocol-focused
- Explicit operation correlation: operationId available at invocation
- Duplicate-safe: Per-operation loop, no ambiguity
- Low complexity: ~30-40 LOC package-private method
- Future-proof: Can add attemptId/retry without redesigning

---

## Q. attemptId Decision

### Question

Can operationId be authoritatively correlated to delegate.call() WITHOUT attemptId?

### Answer

**YES** ✅

**Reason**:
- One delegate.call() invocation = one physical execution
- ExecutionRecord.sequence already distinguishes separate events
- Same operationId may appear in multiple TOOL_EXECUTED events (concurrent resume)
- That is truthful: same logical operation, multiple physical attempts

**Example**:
```
Record 1: TOOL_EXECUTED(operationId="op-A", sequence=5)
Record 2: TOOL_EXECUTED(operationId="op-A", sequence=12)  // Concurrent resume
```

### Future Pressure

attemptId becomes relevant when:
- Retry policy needs to count attempts per operation
- External receipts need to correlate to specific attempt
- Uncertain outcome requires attempt-level recovery

### Decision

**DEFER** ✅

**M6-T3B does NOT require attemptId**

---

## R. TOOL_STARTED Decision

### Question

Does authoritative operation correlation require pre-call TOOL_STARTED event?

### Answer

**NO** ❌

**Reason**:
- operationId already durable in checkpoint before execution
- Post-call TOOL_EXECUTED/TOOL_FAILED can carry operationId
- No current consumer requires pre-call event
- Crash-recovery completeness is separate concern

### When TOOL_STARTED Becomes Relevant

- Uncertain outcome recovery (crash during external call)
- Attempt lifecycle tracking
- Pre-call intent durability for external coordination

### Decision

**DEFER** ✅

**M6-T3B does NOT require TOOL_STARTED**

---

## S. Event Correlation Decision

### Question

Should M6-T3B add operationId to TOOL_EXECUTED/TOOL_FAILED payload?

### Analysis

**After M6-T3B implementation**:
- operationId will be authoritative at callback boundary ✅
- Adding it to events would be truthful ✅

**Current payload**:
```json
{"toolName":"search"}
```

**Proposed payload**:
```json
{
  "toolName": "search",
  "operationId": "op-abc-123"
}
```

### Concerns

**1. String payload brittleness**:
- Manual JSON construction
- No schema validation
- Easy to break

**2. No current consumer**:
- ExecutionLedger doesn't query by operationId yet
- No correlation use case today

**3. Future payload evolution**:
- May want structured event data (not String)
- May want attemptId later
- Premature to extend String schema now?

### Decision

**YES — Add operationId to payload** ✅

**Rationale**:
1. operationId will be authoritative (truthful)
2. Enables future ledger queries by operation
3. Minimal complexity (String concat)
4. Structured event data evolution is separate concern

**Implementation**: Extend current JSON payload format

---

## T. Crash Window / Uncertain Outcome Limitation

### Crash Scenario

```
1. Callback knows operationId
2. delegate.call() begins
3. External side effect commits (e.g., database write)
4. JVM crashes
5. No TOOL_EXECUTED emitted
```

### Durable State After Crash

**Checkpoint**: Operation still pending (operationId in pendingBatch)
**Ledger**: No success event
**External reality**: Side effect MAY have committed

### M6-T3B Does NOT Solve

- Uncertain outcome detection ❌
- External side-effect acknowledgment ❌
- Crash recovery completeness ❌
- Exactly-once guarantee ❌

### What M6-T3B DOES Provide

- Operation identity in events (when events are successfully emitted) ✅
- Post-call correlation (for successful emission) ✅

### Future Work

- TOOL_STARTED (pre-call intent)
- External receipts (side-effect confirmation)
- Uncertain outcome recovery
- Idempotency coordination

### Documentation Requirement

**MUST explicitly state**: M6-T3B enables operation correlation for successful event emission, NOT crash-safe external outcome tracking

---

## U. Evidence Impact

### Current Evidence

```java
public record Evidence(String source, String content)
```

**Does NOT contain operationId** ✅

### Should Evidence Gain operationId?

**NO** ❌

**Reason**:
- Evidence owns: execution proof/content
- Operation identity belongs in: execution events/metadata
- Evidence is consumed by: ChatMemory (model context)
- No Evidence consumer requires operation correlation

**Correct separation**:
- Evidence = proof/content (framework-neutral)
- ExecutionEvent = execution facts (with correlation)

### Decision

**NO Evidence changes** ✅

---

## V. Public API Impact

### Expected Public API Delta

**ZERO** ✅

**Rationale**:
- Tool execution correlation is internal runtime-react concern
- No new public types required
- PendingToolCall unchanged (already has operationId)
- ToolObservationContext is package-private
- ExecutionEvent payload is opaque String (backward-compatible extension)

### Changes Confined To

**arctra-runtime-react** (package-private):
- ProtocolReconstructor refactoring
- New package-private operation execution method
- EvidenceCapturingToolCallback enhancement (accept operationId)
- Event payload format extension

**No arctra-core changes** ✅

---

## W. Core Module Impact

### Expected Core Module Delta

**ZERO** ✅

**Reason**:
- operationId already exists in PendingToolCall
- ExecutionEvent payload is opaque String
- No core abstraction changes needed
- All correlation logic in runtime-react

### Protected Boundaries

- PendingToolCall (no further changes)
- SuspensionCheckpoint (no changes)
- ExecutionEvent (no structural changes)
- ExecutionLedger (no changes)

---

## X. R4 Boundary Protection

### DurableResumeCoordinator

**Expected semantic changes**: **ZERO** ✅

**Unchanged**:
- CHECK A (load checkpoint)
- RuntimeBinding resolution
- Signal validation
- CHECK B (completion/re-suspension)
- Checkpoint CAS operations
- Lifecycle event ordering

**Coordinator delegates to**:
- SpringAiResumedExecutionHandler (unchanged interface)

**Tool execution correlation happens BELOW handler boundary** ✅

### Verification

**No Spring AI imports in Coordinator** ✅
**No tool execution logic in Coordinator** ✅

---

## Y. Tool Execution Subsystem Pressure

### Question

Does M6-T3B justify a package-private tool execution subsystem?

### Analysis

**Current pressure**:
- Need per-operation invocation control
- Need operation correlation
- Need event emission with operationId

**NOT creating**:
- Public SPI
- Cross-provider abstraction
- Complex state machine
- Transaction coordinator

**Justified package-private extraction**:

```java
// Package-private method in ProtocolReconstructor
private ToolResponseMessage.ToolResponse executeOperation(
    PendingToolCall operation,
    ToolCallback callback,
    ToolObservationContext baseContext
) {
    // Per-operation execution with correlation
}
```

### Decision

**Package-private method extraction: YES** ✅

**New subsystem/abstraction: NO** ❌

**Rationale**: Single-responsibility method, not architectural component

---

## Z. Recommended M6-T3B Implementation Slice

### Minimal Safe Implementation

**Scope**: Add operationId correlation to tool execution, emit in events

### Changes Required

**1. ProtocolReconstructor** (refactor)
   - Extract per-operation execution loop
   - Add package-private `executeOperation()` method
   - Pass operationId to callback wrapper

**2. EvidenceCapturingToolCallback** (enhance)
   - Accept operationId parameter (optional for compatibility)
   - Include operationId in TOOL_EXECUTED/TOOL_FAILED payload

**3. ToolObservationContext** (extend)
   - Add optional operationId field
   - OR: Create per-operation context instances

**4. Event Payload** (extend)
   - Current: `{"toolName":"..."}`
   - New: `{"toolName":"...", "operationId":"..."}`

### Files Likely to Change

```
arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/
├─ ProtocolReconstructor.java (MODIFIED - extract per-operation execution)
├─ EvidenceCapturingToolCallback.java (MODIFIED - accept operationId)
└─ ToolObservationContext.java (MODIFIED - add operationId field OR create per-op instances)

arctra-runtime-react/src/test/java/cn/bitcss/arctra/runtime/react/
├─ ProtocolReconstructorTest.java (UPDATED - verify correlation)
├─ Various integration tests (UPDATED - event payload expectations)
```

### What M6-T3B Does NOT Include

❌ attemptId
❌ TOOL_STARTED event
❌ Idempotency
❌ Retry
❌ Uncertain outcome recovery
❌ External receipts
❌ ToolExecutionRuntime subsystem
❌ Public API changes
❌ Core module changes
❌ Coordinator changes

---

## AA. Entry Gate Answers

### 1. At delegate.call(), is toolCallId available today?

**NO** ❌

**Evidence**: ToolCallback.call() receives only arguments, not toolCallId

---

### 2. At delegate.call(), is operationId available today?

**NO** ❌

**Evidence**: ToolObservationContext shared across batch, doesn't carry operationId

---

### 3. Does Spring AI expose authoritative per-call context?

**NO** ❌ (see Section E)

**Evidence**: ToolCallback interface has no per-call correlation parameters

---

### 4. Can duplicate same-name + same-args operations be correlated safely today?

**NO** ❌

**Reason**: No identity available at callback boundary to distinguish them

---

### 5. Can ToolCallingManager be retained while propagating operationId authoritatively?

**NO** ❌

**Reason**: ToolCallingManager dispatches by toolName only, loses per-call identity

---

### 6. If not, can direct ToolCallback execution preserve current semantics?

**YES** ✅

**Evidence**: ToolCallingManager logic is minimal and reproducible (Section F)

---

### 7. Is a new package-private execution collaborator justified now?

**Extraction: YES** ✅ (package-private method)

**New subsystem: NO** ❌ (over-engineering)

---

### 8. Is attemptId required now?

**NO** ❌ (Section Q)

**Reason**: ExecutionRecord.sequence sufficient for now

---

### 9. Is TOOL_STARTED required now?

**NO** ❌ (Section R)

**Reason**: Post-call correlation sufficient without pre-call event

---

### 10. Can TOOL_EXECUTED/TOOL_FAILED truthfully carry operationId after T3B?

**YES** ✅

**Reason**: operationId will be authoritative at callback boundary

---

### 11. Does T3B change at-least-once semantics?

**NO** ❌

**Preserved**: Concurrent resume can still execute same operationId multiple times

---

### 12. Does T3B implement idempotency?

**NO** ❌

**Scope**: Correlation only, not duplicate prevention

---

### 13. Does T3B solve crash-uncertain external outcomes?

**NO** ❌ (Section T)

**Limitation**: Events only emitted if execution completes successfully

---

### 14. Does DurableResumeCoordinator need semantic changes?

**NO** ❌ (Section X)

**Protected**: Zero coordinator changes

---

### 15. Is public API change required?

**NO** ❌ (Section V)

**Scope**: Package-private runtime-react only

---

### 16. What is the smallest safe implementation?

**Answer**: Refactor ProtocolReconstructor to execute operations one-by-one with explicit operationId propagation, emit operationId in TOOL_EXECUTED/TOOL_FAILED payload

**Estimated**: ~50-80 LOC changes across 3 files

---

## AB. Decision

### ✅ **GO — M6-T3B implementation may begin**

**Architecture is sound**:
- [x] Exact 1:1 operation → invocation correlation achievable
- [x] Duplicate same-name + same-args operations safe
- [x] Spring AI semantics preservable via direct execution
- [x] No ordering assumptions required
- [x] No public API changes
- [x] No core module changes
- [x] DurableResumeCoordinator protected
- [x] at-least-once semantics preserved
- [x] Complexity LOW (method extraction + parameter passing)

**Recommended approach**:
- **Candidate C** (Explicit Per-Operation Execution)
- Refactor ProtocolReconstructor: extract per-operation loop
- Bypass ToolCallingManager: direct ToolCallback invocation
- Pass operationId through callback wrapper
- Emit operationId in event payload

**Scope discipline**:
- operationId correlation ONLY
- NO attemptId
- NO TOOL_STARTED
- NO idempotency
- NO retry
- NO uncertain-outcome recovery

**Limitations acknowledged**:
- Crash during external call still creates uncertainty
- Events only emitted if execution completes
- External side-effect tracking is future work

---

## HARD STOP

M6-T3B implementation may now begin with:
- Refactor ProtocolReconstructor for per-operation execution
- Add operationId to callback wrapper
- Extend event payload with operationId

**DO NOT implement**: attemptId, TOOL_STARTED, idempotency, retry, ToolExecutionRuntime, uncertain-outcome recovery

**Awaiting**: Implementation approval
