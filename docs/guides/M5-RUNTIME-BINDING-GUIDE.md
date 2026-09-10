# M5 Runtime Binding Guide

**适用版本:** Arctra 0.0.1-SNAPSHOT (M5)  
**最后更新:** 2026-09-10

---

## 目录

1. [What Problem RuntimeBindingResolver Solves](#what-problem-runtimebindingresolver-solves)
2. [The runtimeBindingKey Contract](#the-runtimebindingkey-contract)
3. [Why Different Runtime Instances Are Allowed](#why-different-runtime-instances-are-allowed)
4. [What Is Actually Reconstructed](#what-is-actually-reconstructed)
5. [MapBasedRuntimeBindingResolver — Reference Implementation](#mapbasedruntimebindingresolver--reference-implementation)
6. [Strict Resolution Policy](#strict-resolution-policy)
7. [Exception Layers and Retry Semantics](#exception-layers-and-retry-semantics)
8. [ChatMemory and Session Requirements](#chatmemory-and-session-requirements)
9. [Complete Example](#complete-example)
10. [Failure Scenarios](#failure-scenarios)
11. [Observability Compatibility](#observability-compatibility)
12. [Known Limitations](#known-limitations)

---

## What Problem RuntimeBindingResolver Solves

### The Cross-Runtime Recovery Scenario

M5 enables **durable suspension and cross-runtime recovery**. Consider this scenario:

**Runtime A (JVM 1, Machine X):**

```java
// Execute agent with durable capability
AgentResult result = engine.execute(definition, request, context);

// Governance requires approval → suspend durably
// Checkpoint persisted to store:
```

**Checkpoint content:**
```
processId            = "P-100"
checkpointVersion    = 3
runtimeBindingKey    = "tenant-42/deploy-agent/v3"
sessionId            = "S-100"
pendingBatch         = [toolCall1, toolCall2]
accumulatedEvidences = [...]
```

**Runtime A disappears** (pod killed, JVM crash, deployment rollout, etc.)

**Runtime B (JVM 2, Machine Y):**

```java
// Resume durable process (no original Java object graph)
AgentResult result = runtime.resumeProcess("P-100", 3, approvalSignal);
```

### The Core Problem

**Runtime B does NOT have:**
- Runtime A's Java objects
- Runtime A's `AgentDefinition` instance
- Runtime A's `AgentExecutionContext` instance
- Runtime A's memory state

**Runtime B MUST reconstruct:**
- Logically equivalent agent definition
- Logically equivalent execution context
- Using only the stable logical identity: **`runtimeBindingKey`**

### How RuntimeBindingResolver Solves This

```
Checkpoint (persistent)
   │
   ├─ processId
   ├─ runtimeBindingKey = "tenant-42/deploy-agent/v3"  ← stable logical identity
   └─ sessionId = "S-100"
   
   ↓
   
RuntimeBindingResolver.resolve(processId, runtimeBindingKey, sessionId)
   │
   ├─ Lookup agent definition by logical key
   ├─ Reconstruct execution context with sessionId
   └─ Return: RuntimeBinding(definition, context)
   
   ↓
   
DurableExecutionEngine uses its own resources:
   ├─ tools (List<ToolCallback>)
   ├─ model (ChatModel)
   ├─ policy (ToolGovernancePolicy)
   └─ chatMemory (ChatMemory)
   
   ↓
   
Execution continues from checkpoint state
```

**Key principle:**

> **Cross-runtime recovery reconstructs logical runtime binding, not physical Java object identity.**

---

## The runtimeBindingKey Contract

### What runtimeBindingKey IS

**Stable logical binding identity:**
- Application-defined opaque string
- Persists through entire process lifecycle
- Identifies the **logical agent configuration** required for recovery
- Remains constant across re-suspension episodes

### What runtimeBindingKey IS NOT

❌ **NOT physical runtime identifiers:**
- Machine ID
- JVM process ID
- Pod/container ID
- Kubernetes deployment name
- Spring bean name
- Java object identity (`System.identityHashCode`)
- Memory address
- Runtime instance UUID

### Recommended Format (Not Enforced)

**Hierarchical logical identity:**

```
{tenant}/{agent-type}/{version}
```

**Examples:**
```
tenant-42/deploy-agent/v3
prod/incident-investigator/v2
customer-acme/support-agent/2026-09
staging/order-processor/v1.2.3
```

**Framework position:**

> Arctra treats `runtimeBindingKey` as an **opaque string**. The framework does not parse tenant/version semantics. Application resolvers own interpretation.

### Stability Guarantee

**M5 guarantees:**

1. **Initial suspension:** Uses engine's `runtimeBindingKey` (application-provided at construction)
2. **Re-suspension:** Preserves checkpoint's `runtimeBindingKey` (not replaced with new engine's key)
3. **Resume:** Uses checkpoint's `runtimeBindingKey` (not current engine's key)

**Verified by test:** `DurableResumeExecutionTest:120-142`

```java
// Runtime A: engine constructed with key = "runtime-B"
// Checkpoint persists: runtimeBindingKey = "incident-agent"
// During resume: resolver receives "incident-agent" ✓

assertEquals("incident-agent", resolver.lastResolvedKey);
assertNotEquals("runtime-B", resolver.lastResolvedKey);
```

---

## Why Different Runtime Instances Are Allowed

### Common Misconception

❌ **Wrong assumption:**  
"Runtime B must use the exact same Agent instance as Runtime A"

### Actual M5 Semantics

✅ **Correct understanding:**  
"Runtime B must provide **logically equivalent** agent semantics"

### Physical vs Logical Identity

**Runtime A:**
```java
MapBasedRuntimeBindingResolver@A123
AgentDefinition@A456 ("Deploy Agent", "Manages deployments")
AgentExecutionContext@A789 (sessionId = "S-100")
```

**Runtime B:**
```java
MapBasedRuntimeBindingResolver@B987  ← Different Java object
AgentDefinition@B654 ("Deploy Agent", "Manages deployments")  ← Different object, SAME semantics
AgentExecutionContext@B321 (sessionId = "S-100")  ← Different object, SAME sessionId
```

### Requirements

**Only logical contract equivalence matters:**

1. **Same `runtimeBindingKey`** → same intended logical definition
2. **Same agent name & description** (current `AgentDefinition` model)
3. **Same `sessionId`** → access to same conversation history
4. **Compatible tool schema** (name + arguments)
5. **Compatible model behavior** (semantic contract)
6. **Compatible governance rules** (execution semantics)

**Physical Java object identity is IRRELEVANT.**

### Test Verification

`MapBasedRuntimeBindingResolverTest:62-77` proves:

```java
// Runtime A resolver
RuntimeBindingResolver resolverA = new MapBasedRuntimeBindingResolver(definitions);
RuntimeBinding bindingA = resolverA.resolve("process-1", key, "session-1");

// Runtime B resolver (different instance)
RuntimeBindingResolver resolverB = new MapBasedRuntimeBindingResolver(definitions);
RuntimeBinding bindingB = resolverB.resolve("process-1", key, "session-1");

// Physical objects differ
assertThat(resolverA).isNotSameAs(resolverB);
assertThat(bindingA).isNotSameAs(bindingB);

// Logical equivalence
assertThat(bindingA.definition()).isEqualTo(bindingB.definition());
assertThat(bindingA.context()).isEqualTo(bindingB.context());
```

---

## What Is Actually Reconstructed

### Current RuntimeBinding Contract

**Source:** `arctra-core/.../runtime/RuntimeBinding.java`

```java
public record RuntimeBinding(
    AgentDefinition definition,
    AgentExecutionContext context
) { }
```

**Only 2 fields.**

### Current AgentDefinition Contract

**Source:** `arctra-core/.../agent/AgentDefinition.java`

```java
public record AgentDefinition(
    String name,
    String description
) { }
```

### Current AgentExecutionContext Contract

**Source:** `arctra-core/.../agent/AgentExecutionContext.java`

```java
public record AgentExecutionContext(
    String sessionId  // nullable
) { }
```

### What RuntimeBindingResolver Reconstructs TODAY

✅ **Reconstructed by resolver:**
- `AgentDefinition` (name, description)
- `AgentExecutionContext` (sessionId)

❌ **NOT reconstructed by resolver:**
- `ChatModel` — provided by calling `DurableExecutionEngine`
- `List<ToolCallback>` — provided by engine
- `ToolGovernancePolicy` — provided by engine
- `ChatMemory` — provided by engine (but must be accessible via sessionId)
- API credentials — application runtime concern
- Spring beans — application runtime concern
- Connection pools — application runtime concern

### Execution Authority Boundary

**`RuntimeBinding`** = logical binding data required by current core contract

**`DurableExecutionEngine`** = execution authority and live runtime resources

**From `RuntimeBinding` Javadoc:**

> "The binding does NOT specify which engine executes - execution authority belongs to the calling durable engine."

**Key implication:**

> Runtime B's `DurableExecutionEngine` executes using its **own** tools, model, policy, and chatMemory. The resolver only provides the logical agent identity and session context.

---

## MapBasedRuntimeBindingResolver — Reference Implementation

### Positioning

`MapBasedRuntimeBindingResolver` is a **reference / teaching implementation**.

**From class Javadoc:**

> "This is a reference implementation for testing and learning, not a production framework component."

### Suitable For

✅ **Appropriate use cases:**
- Unit tests
- Integration tests
- Single-JVM examples
- Static configurations
- Teaching cross-runtime semantics
- Learning M5 durable recovery contract

### NOT Suitable For

❌ **Inappropriate use cases:**
- Distributed definition registry
- Dynamic config service
- Production version migration
- High-scale multi-tenant definition service
- Cross-datacenter agent deployment

### Constructor

```java
public MapBasedRuntimeBindingResolver(Map<String, AgentDefinition> definitions)
```

**Validation:**
- `definitions` cannot be `null`
- Map is used as-is (no defensive copy in current implementation)
- Recommend using immutable map: `Map.of(...)` or `Map.copyOf(...)`

### Resolution Logic

**Strict exact-match lookup:**

1. Validate `processId` (not null/blank)
2. Validate `runtimeBindingKey` (not null/blank)
3. Lookup `definitions.get(runtimeBindingKey)`
4. If missing → throw `RuntimeBindingException`
5. Reconstruct `AgentExecutionContext` from `sessionId`
6. Return `RuntimeBinding(definition, context)`

**No fuzzy matching, no fallback, no migration.**

### Production Alternative

Applications should implement `RuntimeBindingResolver` according to their own definition storage:

- **Database:** Query by logical key
- **Config service:** Fetch from Consul/etcd
- **Spring registry:** Lookup from `ApplicationContext`
- **Remote API:** Call definition management service

---

## Strict Resolution Policy

### The Policy

**Checkpoint persists:**
```
runtimeBindingKey = "deploy-agent/v3"
```

**Resolver contains:**
```
definitions = Map.of(
    "deploy-agent/v4", ...  ← Different version
)
```

**Result:** ❌ **FAIL**

### What Strict Resolution Prevents

❌ **Prohibited behaviors:**
- Silent upgrade (`v3` → `v4`)
- Fuzzy matching (`deploy-agent` matches `deploy-agent/v3`)
- Best-effort mapping (use `v4` because `v3` missing)
- Case-insensitive matching (`DEPLOY-AGENT/V3` → `deploy-agent/v3`)

### Rationale

> **Durable recovery must not silently change logical execution semantics.**

**Scenario:**
- `deploy-agent/v3` uses `restartServer(serverId: string)`
- `deploy-agent/v4` uses `restartServer(serverId: string, region: string)` — breaking change

**If silent upgrade allowed:**
- Checkpoint contains pending call with 1 argument
- v4 tool expects 2 arguments
- **Execution fails unpredictably**

### Future Migration (Not in M5)

**Explicit migration** is a separate concern:

```java
// Future possibility (NOT IMPLEMENTED)
interface BindingMigrationPolicy {
  boolean canMigrate(String fromKey, String toKey);
  RuntimeBinding migrate(String fromKey, String toKey, String sessionId);
}
```

**M5 position:** Explicit failure is better than silent semantic drift.

---

## Exception Layers and Retry Semantics

### Exception Flow

**1. Resolver-level failure:**

```java
// MapBasedRuntimeBindingResolver.resolve(...)
if (definition == null) {
    throw new RuntimeBindingException(
        runtimeBindingKey,
        "AgentDefinition not found for key '" + runtimeBindingKey + "'"
    );
}
```

**2. Durable resume boundary:**

```java
// SpringAiToolCallingEngine.resolveBinding(...)
try {
    return bindingResolver.resolve(processId, runtimeBindingKey, sessionId);
} catch (Exception e) {
    throw new ResumePreparationException(
        "RuntimeBinding resolution failed for processId " + processId,
        e
    );
}
```

### Exception Types

**`RuntimeBindingException`** (core)
- Underlying binding resolution failure
- Thrown by `RuntimeBindingResolver` implementations
- Contains `runtimeBindingKey` field

**`ResumePreparationException`** (core)
- Durable resume preparation boundary exception
- What applications observe when calling `resumeProcess()`
- Wraps resolver failures (and other preparation failures)

### What Applications See

**Application code:**

```java
try {
    AgentResult result = runtime.resumeProcess(processId, version, signal);
} catch (ResumePreparationException e) {
    // Preparation failed — checkpoint remains current
    // May retry later
} catch (CheckpointNotFoundException e) {
    // Checkpoint not found (CHECK A failure)
} catch (StaleCheckpointException e) {
    // Version mismatch (CHECK A failure)
}
```

**Applications do NOT directly catch `RuntimeBindingException`** at the `resumeProcess()` boundary.

### Retry Semantics

**When `ResumePreparationException` is thrown:**

✅ **Guaranteed safe conditions:**
- CHECK A already passed (checkpoint exists, version matches)
- No tool side effects started
- No model invocation occurred
- Checkpoint not advanced or deleted
- **Checkpoint remains at current version**

✅ **Retry eligibility:**
- Same `resumeProcess(processId, version, signal)` call may be retried
- If underlying cause was transient, retry may succeed

❌ **What M5 does NOT provide:**
- Automatic retry loop
- Retry backoff policy
- Transient vs permanent failure classification
- Retry count tracking
- Retry scheduler

### Transient vs Permanent — Known Limitation

**M5 limitation:**

> All resolver failures are currently wrapped as `ResumePreparationException`. Programmatic distinction between transient and permanent failures is not yet modeled.

**Example — Transient failure:**
```java
// Config service temporarily unavailable
throw new RuntimeBindingException(key, "Config service timeout", ioException);
// → ResumePreparationException
// Retry later may succeed
```

**Example — Permanent failure:**
```java
// Definition version permanently removed
throw new RuntimeBindingException("deploy-agent/v3", "Version v3 no longer exists");
// → ResumePreparationException
// Retry with same configuration will never succeed
```

**Current M5 behavior:** Both reach the same exception boundary.

**Mitigation:**
- Inspect `ResumePreparationException.getCause()` for underlying cause
- Check exception messages for hints
- Implement application-level retry logic with appropriate backoff

**Future consideration:** Exception subclasses or classification metadata (requires ADR).

---

## ChatMemory and Session Requirements

### The Checkpoint-Session Relationship

**Checkpoint stores `sessionId`, NOT conversation history:**

```java
public record SuspensionCheckpoint(
    String processId,
    long checkpointVersion,
    String runtimeBindingKey,
    String sessionId,           ← Session identity only
    List<PendingToolCall> pendingBatch,
    List<Evidence> accumulatedEvidences
) { }
```

### Cross-Runtime Continuation Prerequisite

**For Runtime B to continue conversation:**

1. Resolver reconstructs: `AgentExecutionContext(sessionId = "S-100")`
2. Engine uses `sessionId` to access `ChatMemory`
3. `ChatMemory.get(sessionId)` retrieves conversation history

**Critical requirement:**

> Runtime B must be able to access conversation state associated with `sessionId`.

### InMemoryChatMemory Limitation

**Spring AI's `InMemoryChatMemory`:**
- Stores conversation in JVM heap
- ✅ Suitable: single-JVM, multiple executions, same process
- ❌ Unsuitable: cross-JVM, cross-machine, JVM restart

**Example — Single-JVM works:**
```java
// Runtime A (JVM 1)
ChatMemory memory = MessageWindowChatMemory.builder().build();
engine.execute(...);  // sessionId = "S-100"
→ suspend → checkpoint

// Runtime B (SAME JVM 1, different engine instance)
// Uses SAME memory object
engine.resumeProcess(...);  // sessionId = "S-100"
→ memory.get("S-100") returns conversation ✓
```

**Example — Cross-JVM fails:**
```java
// Runtime A (JVM 1, Machine X)
ChatMemory memoryA = MessageWindowChatMemory.builder().build();
engine.execute(...);  // sessionId = "S-100"
→ suspend → checkpoint

// JVM 1 killed

// Runtime B (JVM 2, Machine Y)
ChatMemory memoryB = MessageWindowChatMemory.builder().build();  ← Different heap
engine.resumeProcess(...);  // sessionId = "S-100"
→ memoryB.get("S-100") returns empty list ✗
// Conversation history LOST
```

### Production Requirement

**True cross-runtime recovery requires:**

- Persistent/shared conversation storage
- Examples: Redis, database, distributed cache
- Both Runtime A and Runtime B access same backing store

**M5 does NOT provide:**
- Redis-backed ChatMemory implementation
- JDBC-backed ChatMemory implementation
- Distributed memory framework

**Application responsibility:**
- Integrate persistent ChatMemory at application/runtime layer
- Ensure `sessionId` maps to accessible conversation state

**Architecture note:**

> `RuntimeBindingResolver` does NOT own `ChatMemory`. It only reconstructs `sessionId` into `AgentExecutionContext`. The calling `DurableExecutionEngine` must provide `ChatMemory` that can access state via that `sessionId`.

---

## Complete Example

### Scenario Setup

**Agent definitions (static configuration):**

```java
Map<String, AgentDefinition> definitions = Map.of(
    "tenant-42/deploy-agent/v3",
    new AgentDefinition("Deploy Agent", "Manages production deployments"),
    
    "tenant-42/incident-agent/v1",
    new AgentDefinition("Incident Agent", "Investigates production incidents")
);
```

### Runtime A: Initial Suspension

```java
// Application configuration
RuntimeBindingResolver resolver = new MapBasedRuntimeBindingResolver(definitions);
CheckpointStore checkpointStore = new InMemoryCheckpointStore();
ChatMemory chatMemory = /* shared/persistent implementation */;

// Create durable engine with logical binding key
SpringAiToolCallingEngine engine = new SpringAiToolCallingEngine(
    chatModel,
    tools,
    chatMemory,
    governancePolicy,
    checkpointStore,
    resolver,
    "tenant-42/deploy-agent/v3"  ← Logical binding key
);

// Execute agent
AgentDefinition definition = new AgentDefinition("Deploy Agent", "Manages deployments");
AgentRequest request = new AgentRequest("Deploy v2.5 to production");
AgentExecutionContext context = AgentExecutionContext.withSession("S-100");

AgentResult result = engine.execute(definition, request, context);

// If governance requires approval → durable suspension
if (result.isSuspended()) {
    // Checkpoint persisted:
    // - processId = "P-12345"
    // - checkpointVersion = 1
    // - runtimeBindingKey = "tenant-42/deploy-agent/v3"  (from engine construction)
    // - sessionId = "S-100"
}
```

### Runtime B: Cross-Runtime Recovery

**Runtime B may be:**
- Different JVM
- Different machine
- Different pod/container
- Completely independent process

**Only requirement:** Same logical configuration

```java
// Runtime B configuration (MAY use different Java objects)
Map<String, AgentDefinition> definitionsB = Map.of(
    "tenant-42/deploy-agent/v3",
    new AgentDefinition("Deploy Agent", "Manages production deployments")
    // SAME logical definition, DIFFERENT Java object
);

RuntimeBindingResolver resolverB = new MapBasedRuntimeBindingResolver(definitionsB);
CheckpointStore checkpointStoreB = /* same backing store as Runtime A */;
ChatMemory chatMemoryB = /* same backing store as Runtime A */;

// Create engine (MAY use different binding key — won't be used for resume)
SpringAiToolCallingEngine engineB = new SpringAiToolCallingEngine(
    chatModelB,
    toolsB,
    chatMemoryB,
    governancePolicyB,
    checkpointStoreB,
    resolverB,
    "runtime-B-key"  ← This key is NOT used during resume
);

// Resume using checkpoint's processId and version
ContinuationSignal signal = new ContinuationSignal.ApprovalSignal(true, "Approved by admin");
AgentResult result = engineB.resumeProcess("P-12345", 1, signal);

// What happened internally:
// 1. CHECK A: Load checkpoint, validate version
// 2. resolverB.resolve("P-12345", "tenant-42/deploy-agent/v3", "S-100")
//    ↓ Uses checkpoint's "tenant-42/deploy-agent/v3", NOT "runtime-B-key"
// 3. Resolver returns RuntimeBinding(definition, context)
// 4. Engine reconstructs protocol, executes tools
// 5. Model continues
// 6. CHECK B: Checkpoint transition (complete or re-suspend)
```

**Key observations:**
- Runtime B resolver is different Java object
- Runtime B definition is different Java object
- **BUT** logical semantics are equivalent
- Checkpoint's `runtimeBindingKey` is authoritative

---

## Failure Scenarios

### Case A: Key Exists — Success

**Checkpoint:**
```
runtimeBindingKey = "deploy-agent/v3"
```

**Resolver contains:**
```java
Map.of("deploy-agent/v3", new AgentDefinition(...))
```

**Result:** ✅ Resolves successfully

```java
RuntimeBinding binding = resolver.resolve("P-100", "deploy-agent/v3", "S-100");
// Returns: RuntimeBinding(definition, context(sessionId="S-100"))
```

### Case B: Key Missing — Strict Resolution Failure

**Checkpoint:**
```
runtimeBindingKey = "deploy-agent/v3"
```

**Resolver contains:**
```java
Map.of("deploy-agent/v4", new AgentDefinition(...))  ← Different version
```

**Result:** ❌ Fails

**Exception flow:**
```java
// 1. Resolver-level
throw new RuntimeBindingException(
    "deploy-agent/v3",
    "AgentDefinition not found for key 'deploy-agent/v3' (processId: P-100)"
);

// 2. Wrapped at durable resume boundary
throw new ResumePreparationException(
    "RuntimeBinding resolution failed for processId P-100, runtimeBindingKey=deploy-agent/v3",
    runtimeBindingException
);
```

**Application observes:**
```java
try {
    runtime.resumeProcess("P-100", 3, signal);
} catch (ResumePreparationException e) {
    // Checkpoint remains at version 3
    // Can retry after fixing configuration
}
```

### Case C: Transient Failure — Retry Later

**First attempt:**

```java
// Config service temporarily unavailable
class CustomResolver implements RuntimeBindingResolver {
    public RuntimeBinding resolve(String processId, String key, String sessionId) {
        AgentDefinition def = configService.fetchDefinition(key);  // throws IOException
        // ...
    }
}

// First resume attempt
runtime.resumeProcess("P-100", 3, signal);
// → IOException → ResumePreparationException
// Checkpoint remains at version 3
```

**Later attempt:**

```java
// Config service restored
runtime.resumeProcess("P-100", 3, signal);  // SAME processId, version, signal
// → resolver.resolve(...) succeeds
// → execution continues
```

**Key points:**
- M5 does NOT automatically retry
- Application decides retry policy
- Checkpoint state unchanged between attempts
- Same call parameters may succeed later

---

## Observability Compatibility

### Current Domain Identifiers

M5 durable recovery uses these stable identifiers:

- **`processId`** — Stable process identity (entire lifecycle)
- **`checkpointVersion`** — Suspension episode version (1, 2, 3...)
- **`runtimeBindingKey`** — Logical binding identity
- **`sessionId`** — Conversation session identity

### Logging / Tracing Guidance

**Resolver implementations MAY log using these identifiers:**

```java
public RuntimeBinding resolve(String processId, String runtimeBindingKey, String sessionId) {
    logger.debug(
        "Resolving runtime binding: processId={}, runtimeBindingKey={}, sessionId={}",
        processId, runtimeBindingKey, sessionId
    );
    // ...
}
```

**Do NOT treat `runtimeBindingKey` as trace/runtime-instance identity:**

❌ **Wrong usage:**
```java
// runtimeBindingKey is NOT a traceId
Span span = tracer.startSpan(runtimeBindingKey);  ✗
```

✅ **Correct usage:**
```java
// runtimeBindingKey is logical binding identity
logger.info("Binding {} resolved for process {}", runtimeBindingKey, processId);  ✓
```

### Future Correlation Model

**M6 and beyond may introduce:**
- `turnId` — Conversation turn identity
- `stepId` — Tool invocation step identity
- `operationId` — Operation identity
- `recordId` — Execution record identity
- Distributed tracing integration

**M5 compatibility guarantee:**

> `processId`, `sessionId`, and `runtimeBindingKey` semantics remain stable. Future observability adapters will map M5 identifiers to unified correlation model.

**Do NOT introduce these in M5:**
- `turnId` / `stepId` / `operationId` fields in core contracts
- OpenTelemetry dependencies in `arctra-core`
- MDC framework dependencies

---

## Known Limitations

### 1. Minimal AgentDefinition Model

**Current:**
```java
public record AgentDefinition(String name, String description) { }
```

**Missing:**
- Version metadata
- Configuration properties
- Dependency declarations
- Schema version

**Workaround:**
- Encode version in `runtimeBindingKey`: `"agent/v3"`
- Use separate configuration service for complex metadata

**Future:** AgentDefinition may be extended (requires ADR)

### 2. Failure Classification Not Machine-Readable

**Current:**
- All resolver failures → `ResumePreparationException`
- No programmatic transient vs permanent distinction

**Impact:**
- Applications cannot automatically classify retry eligibility
- Must inspect exception messages or underlying causes

**Workaround:**
- Implement application-level retry heuristics
- Log and monitor failure patterns

**Future:** Exception subclasses or classification metadata (requires ADR)

### 3. ChatMemory Persistence Required

**Current:**
- M5 assumes `sessionId` maps to accessible conversation state
- No persistent ChatMemory implementation provided

**Impact:**
- Cross-JVM recovery requires application integration
- InMemoryChatMemory insufficient for true distributed recovery

**Workaround:**
- Integrate Redis/database-backed ChatMemory at application layer

**Future:** Persistent ChatMemory implementations or integration guide

### 4. Tool/Model Equivalence Not Automatically Verified

**Current:**
- Framework does not validate tool schema compatibility
- No automatic model semantic verification

**Impact:**
- Runtime B may have incompatible tools/model
- Execution may fail unpredictably after resume

**Workaround:**
- Enforce tool schema backward compatibility in CI/CD
- Document tool/model contracts explicitly
- Use integration tests across runtime versions

**Future:** Schema versioning and compatibility framework (requires ADR)

### 5. MapBasedRuntimeBindingResolver Production Limits

**Current:**
- Static in-memory map
- No dynamic configuration updates
- No distributed definition registry

**Impact:**
- Not suitable for high-scale multi-tenant scenarios
- Requires application restart for definition changes

**Workaround:**
- Implement custom resolver for production needs:
  - Database-backed resolver
  - Config service integration
  - Spring bean registry resolver

**Future:** Additional reference implementations (optional)

---

## Summary

### What You Learned

1. **`runtimeBindingKey`** is a stable logical binding identity, not physical runtime ID
2. **Cross-runtime recovery** reconstructs logical equivalence, not Java object identity
3. **`RuntimeBinding`** contains only `definition` + `context` today
4. **`MapBasedRuntimeBindingResolver`** is a reference implementation for learning
5. **Strict resolution** prevents silent semantic drift
6. **Exception layers**: `RuntimeBindingException` → `ResumePreparationException`
7. **Retry semantics**: preparation failures leave checkpoint unchanged, but M5 doesn't auto-retry
8. **ChatMemory** must be accessible across runtimes via `sessionId`

### Next Steps

**For learning:**
- Study `MapBasedRuntimeBindingResolver` implementation
- Run M5 tests: `MapBasedRuntimeBindingResolverTest`
- Experiment with cross-runtime scenarios

**For production:**
- Implement custom `RuntimeBindingResolver` for your definition storage
- Integrate persistent `ChatMemory` (Redis, database)
- Design stable `runtimeBindingKey` format for your domain
- Establish tool/model schema compatibility policies
- Implement application-level retry logic

**Remaining M5 hardening gaps:**
- Persistent CheckpointStore (JDBC, Redis)
- Failure classification framework
- Schema/version migration policy
- Comprehensive observability integration

---

**Document version:** 1.0  
**Last updated:** 2026-09-10  
**Feedback:** Report issues or suggestions to Arctra team
