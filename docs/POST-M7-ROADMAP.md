# POST-M7 ROADMAP — NEXT SUBSYSTEM ANALYSIS

**Date:** 2026-09-20  
**Context:** M7 Recovery Control Plane closed  
**Purpose:** Determine next major architectural subsystem  
**Status:** ANALYSIS ONLY — DO NOT IMPLEMENT

---

## EXECUTIVE SUMMARY

After M7 closure, Arctra has:
- ✅ Durable execution kernel (M5/M6)
- ✅ Recovery discovery and control plane (M7)
- ✅ ReAct reasoning runtime
- ✅ Governance and approval workflow
- ✅ Evidence capture and ledger

**Strategic Question:** What architectural capability should be prioritized next?

**Recommendation:** **EXECUTION PATH LEARNING (Cached Execution)**

**Why:** Maximum leverage - transforms Arctra from "always reason" to "reason once, execute efficiently thereafter" for repeated task patterns.

---

## 1. CURRENT PLATFORM STATE AFTER M7

### What Arctra Has Today

**Core Execution:**
- ReAct-based reasoning (SpringAi + tool calling)
- Durable suspension and resume
- Cross-runtime recovery
- At-least-once execution semantics

**Governance:**
- Tool approval workflow
- REQUIRE_APPROVAL / ALLOW / DENY decisions
- Signal-based continuation
- Human-in-the-loop integration

**Persistence:**
- JDBC checkpoint store (restart-safe)
- JDBC invocation state store (recovery classification)
- Execution ledger (historical projection)
- Evidence capture

**Operations:**
- Recovery control plane (M7)
- Continuation discovery
- Version-aware resume
- Operational dashboards (future)

### What Arctra Does Well

**Strengths:**
1. **Robust durability** - M6 CHECK A/B prevents corruption
2. **Cross-runtime recovery** - No single point of failure
3. **Governance integration** - Human oversight when needed
4. **Recovery classification** - Correct handling of crash windows

### What Arctra Does NOT Do Yet

**Gaps:**
1. **Repeated task efficiency** - Every execution requires full ReAct reasoning
2. **Long-running process visibility** - No harness-level progress tracking
3. **Enterprise integration** - No Spring Boot auto-configuration patterns
4. **Tool ecosystem** - Limited built-in tools beyond examples
5. **Operational observability** - Metrics, tracing, dashboards basic
6. **Multi-step workflows** - No explicit orchestration beyond sequential ReAct

### Architecture Question

**Is Arctra:**
- **A ReAct runtime** with durability?
- **OR**
- **A durable execution platform** where ReAct is ONE execution mechanism?

**Current State:** Arctra IS a ReAct runtime. All execution goes through chat model reasoning.

**Future State:** Should Arctra support multiple execution mechanisms?

---

## 2. CANDIDATE NEXT SUBSYSTEMS

### A. EXECUTION PATH LEARNING / CACHED EXECUTION

**Concept:** Learn from successful ReAct executions to create reusable execution paths.

**Core Idea:**
```
First execution (unknown task):
  User Request → ReAct Reasoning → Tool Chain → Success → Learn Path

Later execution (similar task):
  User Request → Match Path → Execute Tools → Success (no reasoning)
```

**Key Properties:**
- Discovery mode: Full ReAct reasoning
- Cached mode: Tool execution only (or minimal reasoning)
- Verification: Confirm result correctness
- Fallback: Drop to ReAct if path fails

**Value Proposition:**
- **10-100x faster** for repeated tasks
- **10-100x cheaper** (no model reasoning costs)
- **More predictable** execution (known path)
- **Preserve flexibility** (fallback to ReAct)

**Challenges:**
- Intent compatibility detection
- Parameter binding (generalization)
- Path invalidation (tool changes, environment)
- Security context verification
- Governance replay vs re-evaluation

**Authority Question:**
Does this introduce a new authority?
- **ExecutionPathStore**: Authority for "verified reusable execution knowledge"
- Separate from CheckpointStore (current state)
- Separate from InvocationStateStore (attempts)
- Separate from ExecutionLedger (history)

**Answer:** YES - new authority justified for learned execution capability.

---

### B. LONG-RUNNING PROCESS / HARNESS PROGRESS

**Concept:** Visibility and control for long-running agent processes.

**Core Idea:**
```
Current: Agent runs until suspension/completion (opaque)

Future:
  Agent → Phase 1 → Progress Update → Phase 2 → Progress → Done
  Operator sees: "Analyzing logs (2/5 services complete)"
```

**Key Properties:**
- Progress reporting from within execution
- Intermediate results/status
- Cancellation capability
- Time budgets and deadlines
- Multi-step task breakdown

**Value Proposition:**
- **Operational visibility** for long tasks
- **User experience** (progress bars, status)
- **Control** (cancel, pause, priority)
- **SLA enforcement** (timeouts, deadlines)

**Challenges:**
- Progress semantics (% complete? steps?)
- Cancellation during tool execution
- Checkpoint state after cancel
- Backward compatibility with existing agents

**Authority Question:**
Does this introduce a new authority?
- **ProcessProgressStore**: Authority for "current execution phase/progress"
- OR: Extension of CheckpointStore metadata?

**Answer:** UNCLEAR - could extend CheckpointStore or be new authority.

---

### C. SPRING BOOT / ENTERPRISE INTEGRATION

**Concept:** Production-ready Spring Boot patterns and enterprise features.

**Core Idea:**
```
Current: Manual wiring of components

Future:
  @EnableArctra
  @DurableAgent
  Auto-configured DataSource, metrics, health checks
```

**Key Properties:**
- Spring Boot auto-configuration
- Actuator integration (health, metrics)
- Transaction management
- Connection pooling
- Configuration properties
- Spring Security integration
- Multi-tenancy support

**Value Proposition:**
- **Enterprise adoption** (familiar patterns)
- **Production readiness** (health checks, metrics)
- **Developer ergonomics** (less boilerplate)
- **Spring ecosystem** integration

**Challenges:**
- Spring complexity
- Version compatibility
- Testing without Spring
- Core library independence

**Authority Question:**
Does this introduce a new authority?

**Answer:** NO - purely integration layer.

---

### D. MCP / TOOL ECOSYSTEM

**Concept:** Rich built-in tool library and Model Context Protocol integration.

**Core Idea:**
```
Current: Examples have ~3-5 custom tools

Future:
  Built-in: HTTP, SQL, File I/O, Search, Math, Code Execution
  MCP: VSCode tools, IDE tools, external services
```

**Key Properties:**
- Rich standard library of tools
- MCP client integration
- Tool discovery and registration
- Schema validation
- Parameter transformation
- Error handling standards

**Value Proposition:**
- **Developer productivity** (don't rebuild common tools)
- **Ecosystem integration** (MCP servers)
- **Standardization** (common tool patterns)
- **Examples** (real-world capabilities)

**Challenges:**
- Security (file access, code execution)
- Sandboxing and isolation
- Resource limits
- MCP protocol stability

**Authority Question:**
Does this introduce a new authority?

**Answer:** NO - tools are capabilities, not authorities.

---

### E. OBSERVABILITY / OPERATIONS

**Concept:** Production-grade monitoring, metrics, tracing, and dashboards.

**Core Idea:**
```
Current: Logs + basic execution ledger

Future:
  Metrics: execution duration, success rate, tool latency
  Tracing: distributed trace across resume/recovery
  Dashboards: operational visibility
  Alerts: SLO violations, error rates
```

**Key Properties:**
- Prometheus metrics
- OpenTelemetry tracing
- Structured logging
- Operational dashboards
- Alerting integration
- Performance profiling

**Value Proposition:**
- **Production operations** (monitoring, alerting)
- **Performance analysis** (bottleneck detection)
- **Debugging** (trace across restarts)
- **SLA compliance** (measure and enforce)

**Challenges:**
- Framework integration (Spring, Micronaut, etc.)
- Trace context across suspend/resume
- Metric cardinality (processId dimensions)
- Dashboard maintenance

**Authority Question:**
Does this introduce a new authority?

**Answer:** NO - observability is projection over existing authorities.

---

### F. DISTRIBUTED RECOVERY OWNERSHIP

**Concept:** Lease-based ownership for single-runtime execution guarantee.

**Core Idea:**
```
Current: At-least-once (concurrent resume possible)

Future:
  Lease acquisition → single runtime execution
  Lease expiration → automatic recovery
  Fencing tokens → prevent zombie execution
```

**Key Properties:**
- Lease-based continuation ownership
- Automatic lease renewal
- Lease expiration and recovery
- Fencing token validation
- Distributed lock coordination

**Value Proposition:**
- **Reduced duplicate execution** (closer to exactly-once)
- **Resource efficiency** (no wasted concurrent work)
- **Cleaner semantics** (single logical execution)

**Challenges:**
- Clock synchronization
- Lease timeout tuning
- Failure detection
- Network partitions
- Complexity vs. value

**Authority Question:**
Does this introduce a new authority?

**Answer:** MAYBE - lease management could be separate authority or extend CheckpointStore.

---

## 3. DEPENDENCY ANALYSIS

### Prerequisites and Ordering Constraints

```
Foundation (Complete):
  M5: Durable Execution
  M6: CHECK A/B Concurrency
  M7: Recovery Control Plane

Independent (No ordering):
  Spring Boot Integration
  MCP / Tool Ecosystem
  Observability

Dependent on Foundation:
  Execution Path Learning (needs M5/M6/M7)
  Long-Running Process (needs M5/M6)
  Distributed Ownership (needs M7)

Synergistic:
  Execution Path Learning + MCP Tools = powerful combination
  Long-Running Process + Observability = operational excellence
  Spring Boot + Observability = production readiness
```

### Critical Path

**To maximize value, prioritize:**
1. Core execution capabilities (Path Learning)
2. Enterprise integration (Spring Boot)
3. Operational capabilities (Observability, Long-Running)
4. Nice-to-have optimizations (Distributed Ownership)

---

## 4. EXECUTION PATH LEARNING — DEEP ANALYSIS

### Why This Should Be Next

**Maximum Leverage:**
- Transforms Arctra's value proposition
- "Always reason" → "Reason once, efficient thereafter"
- 10-100x performance improvement for repeated tasks
- Minimal architectural disruption

**Real-World Relevance:**
Many production agent use cases involve repeated task patterns:
- Incident investigation (same analysis steps)
- Data extraction (similar queries)
- Report generation (standard workflows)
- API integration (predictable operations)

**Architectural Foundation Ready:**
- M5/M6: Durable execution proven
- M7: Recovery mechanics established
- Clean separation: Path learning is orthogonal to durability

### Execution Path Learning Model

#### Discovery Phase (First Execution)

```
User: "Investigate latency spike in payment service"

ReAct:
  Reasoning → queryLogs(service=payment, metric=latency)
  Reasoning → getDeployment(service=payment)
  Reasoning → checkDependencies(service=payment)
  Reasoning → Final analysis

Arctra captures:
  Intent: "investigate latency in {service}"
  Path: [queryLogs, getDeployment, checkDependencies]
  Parameters: service → bound from intent
  Success: Verified by user/criteria
```

#### Cached Phase (Later Execution)

```
User: "Investigate latency spike in auth service"

Arctra:
  1. Match intent pattern → Found "investigate latency in {service}"
  2. Bind parameters → service = auth
  3. Execute path:
     queryLogs(service=auth, metric=latency)
     getDeployment(service=auth)
     checkDependencies(service=auth)
  4. Verify result
  5. Return (no chat model reasoning needed)

Fallback:
  If path fails → drop to full ReAct discovery
```

### Architecture Model

```
┌─────────────────────────────────────────┐
│           Agent / Harness               │
└─────────────────────────────────────────┘
                    ↓
        ┌───────────────────────┐
        │  Execution Selection  │
        │  (Intent → Strategy)  │
        └───────────────────────┘
                    ↓
    ┌───────────────┴───────────────┐
    │                               │
┌───────────────┐         ┌──────────────────┐
│ ReAct         │         │ Cached Path      │
│ Discovery     │         │ Execution        │
└───────────────┘         └──────────────────┘
    │                              │
    └──────────┬───────────────────┘
               ↓
   ┌───────────────────────┐
   │  Common Execution     │
   │  (Tool Calling)       │
   └───────────────────────┘
               ↓
   ┌───────────────────────┐
   │  M6 Durable Kernel    │
   └───────────────────────┘
```

### New Components

**ExecutionPathStore** (New Authority):
- Stores verified reusable execution paths
- Schema: intent pattern, tool sequence, parameter bindings
- Lifecycle: create, verify, invalidate, delete

**PathMatcher**:
- Intent → Path matching
- Semantic similarity + structural constraints
- Parameter extraction and binding

**CachedPathExecutor**:
- Execute path without reasoning
- Tool invocation only
- Verification and fallback

**PathLearner** (optional):
- Observe successful ReAct executions
- Extract reusable patterns
- Store with verification metadata

### Safety and Correctness

**Critical Invariants:**

1. **Intent Compatibility**
   - Path must match user's actual intent
   - Not just semantic similarity
   - Verify input contract

2. **Parameter Binding Safety**
   - Type checking
   - Value validation
   - Security context verification

3. **Tool Schema Compatibility**
   - Tool signature unchanged
   - Schema version checking
   - Invalidate path on breaking changes

4. **Governance Re-Evaluation**
   - Do NOT cache approval decisions
   - Re-evaluate current governance policy
   - Governance decides WHETHER (path decides HOW)

5. **Verification**
   - Confirm result correctness
   - Detect path degradation
   - Fallback on verification failure

6. **Fallback Guarantee**
   - If cached path fails → drop to ReAct
   - Never fail silently
   - User should not notice (except latency)

### Path Invalidation

**When to invalidate cached paths:**
- Tool schema changed (breaking)
- Tool removed or deprecated
- Governance policy changed materially
- Environment configuration changed
- Path success rate drops below threshold
- Explicit operator invalidation
- Time-based expiration (optional)

### Execution Selection Logic

```java
public interface ExecutionStrategy {
  AgentResult execute(AgentRequest request, AgentExecutionContext context);
}

// Pseudo-code
AgentResult executeAgent(AgentRequest request) {
  // 1. Try cached path execution
  Optional<ExecutionPath> path = pathStore.matchPath(request);
  if (path.isPresent() && path.get().isValid()) {
    try {
      return cachedPathExecutor.execute(path.get(), request);
    } catch (PathExecutionException e) {
      // Path failed - invalidate and fallback
      pathStore.invalidate(path.get().id());
    }
  }

  // 2. Fallback to ReAct discovery
  AgentResult result = reactEngine.execute(request);

  // 3. Optionally learn new path
  if (result.isSuccessful() && shouldLearnPath(result)) {
    pathLearner.learnPath(request, result);
  }

  return result;
}
```

### Governance Invariant

**CRITICAL: Governance must NOT be cached.**

```
Cached Path:
  Selects HOW execution may proceed (which tools, what order)

Governance:
  Still decides WHETHER each tool invocation may proceed
  Re-evaluates current policy
  Requires approval if policy says so
```

**Flow:**
```
1. Path matched: [queryLogs, getDeployment]
2. Execute queryLogs
   → Governance evaluates REQUIRE_APPROVAL
   → Suspend for approval (normal M6 flow)
3. Resume after approval
   → Continue path execution
4. Execute getDeployment
   → Governance evaluates ALLOW
   → Proceed immediately
```

**Path execution does NOT bypass governance.**

---

## 5. EXECUTION PATH LEARNING VS. OTHER CANDIDATES

### vs. Long-Running Process

**Path Learning:**
- Pro: Massive performance improvement
- Pro: Minimal disruption to existing architecture
- Con: Complex matching and verification logic

**Long-Running Process:**
- Pro: Better UX for long tasks
- Pro: Enables cancellation
- Con: Does not improve performance
- Con: Progress semantics unclear

**Verdict:** Path Learning provides more strategic value.

### vs. Spring Boot Integration

**Path Learning:**
- Pro: Core capability, not integration layer
- Pro: Unique to Arctra
- Con: More complex

**Spring Boot:**
- Pro: Enterprise adoption
- Pro: Production readiness patterns
- Con: Integration work, not differentiation
- Con: Can be done anytime

**Verdict:** Spring Boot is important but not urgent. Path Learning is strategic differentiator.

### vs. MCP / Tool Ecosystem

**Path Learning:**
- Pro: Maximizes value of existing tools
- Con: Requires some tools to be valuable

**MCP / Tools:**
- Pro: More capabilities
- Pro: Ecosystem integration
- Con: Does not improve efficiency of existing capabilities

**Verdict:** They are synergistic. Path Learning first, MCP enhances it.

### vs. Observability

**Path Learning:**
- Pro: Performance transformation
- Con: Harder to implement

**Observability:**
- Pro: Production necessity
- Pro: Relatively straightforward
- Con: Does not change fundamental capabilities

**Verdict:** Observability is important for production but can follow Path Learning.

### vs. Distributed Ownership

**Path Learning:**
- Pro: 10-100x performance improvement
- Con: Complex verification logic

**Distributed Ownership:**
- Pro: Cleaner semantics (closer to exactly-once)
- Con: Complex distributed systems engineering
- Con: May not be needed if tools are idempotent

**Verdict:** Path Learning provides more value. Distributed ownership is optimization, not necessity.

---

## 6. RECOMMENDED NEXT SUBSYSTEM

### Recommendation: **EXECUTION PATH LEARNING (M8)**

**Why:**
1. **Maximum strategic value** - Transforms Arctra's positioning
2. **10-100x performance** improvement for repeated tasks
3. **Architectural readiness** - M5/M6/M7 provide foundation
4. **Minimal disruption** - Clean separation of concerns
5. **Differentiation** - Unique capability in agent space

**Not Recommended Next:**
- Spring Boot - important but not strategic differentiator
- MCP Tools - enhances value but doesn't transform it
- Observability - necessary but can follow
- Distributed Ownership - optimization, not core value
- Long-Running Process - UX improvement, not performance

---

## 7. M8 OBJECTIVE (Proposed)

**M8: EXECUTION PATH LEARNING — CACHED EXECUTION**

**Goal:** Enable Arctra to learn from successful ReAct executions and reuse verified execution paths for similar future tasks, achieving 10-100x performance improvement for repeated task patterns.

**Scope:**

**In Scope:**
1. ExecutionPathStore (new authority)
2. Intent matching and parameter binding
3. Cached path execution
4. Verification and fallback
5. Path invalidation
6. Governance re-evaluation (not caching)
7. ReAct fallback guarantee

**Out of Scope:**
- Automatic path learning (manual/explicit initially)
- Distributed path sharing
- Path versioning and migration
- Advanced intent understanding (LLM-based)
- Path composition and transformation

**Success Criteria:**
1. Cached path executes without chat model reasoning
2. Governance still evaluates every tool call
3. Verification detects path degradation
4. Fallback to ReAct on path failure
5. 10x+ faster execution for matched paths
6. No silent failures (always fallback)

---

## 8. ARCHITECTURE QUESTIONS FOR M8 GATE

Before implementing M8, these questions must be answered:

### Q1: ExecutionStrategy Abstraction

**Question:** Should we introduce ExecutionStrategy now or wait for two concrete implementations?

**Options:**
A. Add ExecutionStrategy interface now (ReAct + Cached)
B. Wait until M9 for abstraction (after 2+ mechanisms)

**Recommendation:** Option A - two mechanisms justify abstraction.

### Q2: Authority Separation

**Question:** Is ExecutionPathStore a separate authority or extension of existing store?

**Analysis:**
- ExecutionPathStore authority: "verified reusable execution knowledge"
- Different from CheckpointStore: "current continuation state"
- Different from InvocationStateStore: "physical attempt tracking"
- Different from ExecutionLedger: "historical projection"

**Recommendation:** Separate authority. Different lifecycle, semantics, queries.

### Q3: Governance Replay vs. Re-Evaluation

**Question:** Should cached execution replay historical approval decisions or re-evaluate current policy?

**Options:**
A. Replay: Faster, consistent with original execution
B. Re-evaluate: Safer, current policy enforcement

**Recommendation:** Re-evaluate. Governance policy may have changed. Security > performance.

### Q4: Intent Matching Mechanism

**Question:** How to match user intent to cached paths?

**Options:**
A. Exact string match (brittle)
B. Semantic similarity (LLM-based, expensive)
C. Template-based with parameter extraction
D. Hybrid (template + semantic verification)

**Recommendation:** Start with C (template), evolve to D.

### Q5: Path Verification

**Question:** How to verify cached path produced correct result?

**Options:**
A. No verification (trust path)
B. Heuristic checks (tool success codes)
C. Result schema validation
D. LLM-based correctness check

**Recommendation:** Start with B+C, consider D for critical paths.

### Q6: Fallback Trigger

**Question:** When should cached execution fallback to ReAct?

**Triggers:**
- Tool execution failure
- Governance requires approval (proceed with path)
- Verification failure
- Parameter binding failure
- Path invalidated

**Recommendation:** Fallback on execution/verification failure. Continue path through governance suspension.

### Q7: Path Lifecycle

**Question:** Who creates, validates, and invalidates paths?

**Options:**
A. Automatic learning from all executions
B. Explicit operator creates paths
C. Automatic learning + operator approval
D. Developer-defined paths only

**Recommendation:** Start with B (explicit), evolve to C (semi-automatic).

### Q8: Durability Integration

**Question:** How does cached execution integrate with M6 durable kernel?

**Answer:**
- Cached path execution uses same tool calling mechanism
- Governance suspension works normally (WAITING_FOR_SIGNAL)
- Checkpoint stores "cached path execution" state
- Resume continues path execution
- M6 CHECK A/B still applies

**No special cases.** Cached execution is just tool orchestration.

---

## 9. DEFERRED WORK

### Explicitly NOT Included in M8

**Automatic Path Learning:**
- Observing executions and extracting patterns
- Defer to M9 or later
- M8: Manual path creation only

**Distributed Path Sharing:**
- Sharing learned paths across teams/orgs
- Path marketplace
- Defer indefinitely (may not be needed)

**Path Versioning:**
- Migrating paths when tools change
- Path compatibility tracking
- Defer to M9 if needed

**Advanced Intent Understanding:**
- LLM-based intent classification
- Deep semantic similarity
- Defer to M9 (start with templates)

**Path Composition:**
- Combining multiple paths
- Path parameters as inputs to other paths
- Defer to M10+ (if ever)

**Exactly-Once via Leases:**
- Still deferred
- Cached execution does not change at-least-once semantics
- Defer to separate milestone (if needed)

**Cancellation:**
- Still deferred
- Applies to both ReAct and Cached execution
- Defer to separate milestone

---

## 10. RELATIONSHIP TO EXISTING ARCHITECTURE

### M6 Durable Execution Kernel

**Unchanged:**
- CHECK A/B still applies
- Tool execution durability
- Recovery classification
- At-least-once semantics

**Enhanced:**
- Checkpoint stores path execution context
- Resume continues path (or fallback to ReAct)

### M7 Recovery Control Plane

**Unchanged:**
- Discovery still works
- Version-aware resume
- Cross-runtime recovery

**Enhanced:**
- Discovered continuations may be "cached path execution"
- Resume handler determines execution mode from checkpoint

### Current ReAct Runtime

**Changed:**
- ReAct becomes ONE execution mechanism
- Wrapped by execution selection logic

**Still Used:**
- Discovery phase (unknown tasks)
- Fallback from cached execution
- Full reasoning capability preserved

---

## 11. STRATEGIC MODEL EVOLUTION

### Current State

```
UNKNOWN TASK → ReAct
REPEATED TASK → ReAct (every time)
KNOWN WORKFLOW → ReAct (no alternative)
```

**Problem:** Every execution pays full reasoning cost.

### After M8

```
UNKNOWN TASK → ReAct Discovery
REPEATED TASK → Cached Execution (10-100x faster)
STABLE WORKFLOW → ReAct (or cached if learned)
```

**Benefit:** Repeated tasks become dramatically faster and cheaper.

### Future Vision (M9+)

```
UNKNOWN TASK → ReAct Discovery → Learn
REPEATED TASK → Cached Execution
STABLE WORKFLOW → Deterministic Execution (explicit)
```

**Model:**
- ReAct: Exploration, path discovery
- Cached: Learned execution capability
- Deterministic: Explicit stable process

**Arctra evolves from:**
- "ReAct runtime" → "Intelligent execution platform"

---

## 12. EXECUTION PATH LEARNING — RISKS

### Risk 1: Intent Matching Accuracy

**Risk:** Cached path does not match user's actual intent.

**Mitigation:**
- Conservative matching (prefer false negative)
- Verification of results
- Fallback to ReAct on doubt
- Explicit user confirmation (optional)

### Risk 2: Path Staleness

**Risk:** Cached path becomes invalid (tool changes, environment).

**Mitigation:**
- Tool schema versioning
- Automatic invalidation on breaking changes
- Success rate monitoring
- Explicit invalidation API

### Risk 3: Governance Bypass

**Risk:** Cached execution bypasses governance.

**Mitigation:**
- **Architecture invariant:** Governance ALWAYS evaluates
- Cached path selects HOW, governance decides WHETHER
- Test coverage for governance during cached execution

### Risk 4: Verification Overhead

**Risk:** Verification as expensive as ReAct.

**Mitigation:**
- Start with lightweight verification (tool success)
- Evolve to schema validation
- LLM verification only for critical paths
- Accept some false positives (fallback)

### Risk 5: Complexity

**Risk:** Path learning adds substantial complexity.

**Mitigation:**
- Start simple (manual path creation)
- Defer automatic learning to M9
- Clear separation of concerns
- Comprehensive testing

---

## 13. SUCCESS METRICS FOR M8

**Performance:**
- ✅ Cached execution 10x+ faster than ReAct for matched paths
- ✅ Cache hit rate >50% for repeated task scenarios

**Correctness:**
- ✅ Governance evaluates every tool call (cached or not)
- ✅ Verification detects path degradation
- ✅ Fallback to ReAct on failures (no silent errors)

**Safety:**
- ✅ Intent matching false positive rate <5%
- ✅ Path invalidation on tool schema changes
- ✅ Security context verification

**Durability:**
- ✅ Cached execution integrates with M6 CHECK A/B
- ✅ Suspension/resume works during cached execution
- ✅ Cross-runtime recovery preserves path context

**Usability:**
- ✅ Explicit path creation API
- ✅ Path invalidation API
- ✅ Observability (path hit/miss metrics)

---

## 14. ALTERNATIVE: LONG-RUNNING PROCESS

If Execution Path Learning is deemed too complex, the alternative is:

**M8: LONG-RUNNING PROCESS / HARNESS PROGRESS**

**Why Second Choice:**
- ✅ Improves UX for long tasks
- ✅ Enables cancellation
- ✅ Simpler than path learning
- ❌ Does not improve performance
- ❌ Less strategic differentiation

**Scope:**
- Progress reporting API
- Phase/step tracking
- Cancellation capability
- Time budgets
- Status queries

**Would Still Defer:**
- Execution Path Learning (M9)
- Spring Boot Integration
- MCP Tools
- Distributed Ownership

**Verdict:** Long-Running Process is solid alternative but lower strategic value.

---

## 15. FINAL RECOMMENDATION

### M8 Objective

**EXECUTION PATH LEARNING — CACHED EXECUTION**

### Rationale

1. **Highest strategic value** - transforms Arctra positioning
2. **10-100x performance** for repeated tasks
3. **Ready dependencies** - M5/M6/M7 complete
4. **Clean architecture** - orthogonal to durability
5. **Differentiation** - unique in agent space

### Architecture Gates Required

Before M8 implementation:
1. ✅ ExecutionStrategy abstraction design
2. ✅ ExecutionPathStore authority semantics
3. ✅ Governance re-evaluation decision
4. ✅ Intent matching mechanism
5. ✅ Path verification approach
6. ✅ Fallback trigger conditions
7. ✅ Path lifecycle (creation, validation, invalidation)
8. ✅ Durability integration model

### What NOT to Do in M8

- ❌ Automatic path learning (manual only)
- ❌ Distributed path sharing
- ❌ Path versioning/migration
- ❌ Advanced LLM-based intent matching
- ❌ Path composition
- ❌ Exactly-once via leases (still deferred)
- ❌ Cancellation (separate concern)

### Success Definition

**M8 succeeds when:**
- Cached path executes 10x+ faster than ReAct
- Governance still evaluates every tool call
- Verification detects degradation
- Fallback to ReAct on failures
- No silent errors
- Clean integration with M6/M7

---

## 16. ROADMAP SUMMARY

### Completed

- ✅ M5: Durable Execution Kernel
- ✅ M6: CHECK A/B Concurrency
- ✅ M7: Recovery Control Plane

### Recommended Next

- 🎯 **M8: Execution Path Learning** (Cached Execution)

### Future Consideration (Unordered)

- Spring Boot / Enterprise Integration
- MCP / Tool Ecosystem
- Observability / Operations
- Long-Running Process / Progress
- Distributed Recovery Ownership (if needed)
- Cancellation (if needed)

### Strategic Evolution

```
M7 (Current):
  ReAct runtime with durability and recovery

M8 (Proposed):
  Intelligent execution platform
  - ReAct for discovery
  - Cached for efficiency
  - Durable for reliability

M9+ (Future):
  Adaptive execution platform
  - Automatic learning
  - Multi-strategy execution
  - Enterprise-grade operations
```

---

## 17. CONCLUSION

**POST-M7 NEXT SUBSYSTEM: EXECUTION PATH LEARNING**

**Rationale:** Maximum strategic value, transformative performance, architectural readiness.

**M8 Objective:** Enable cached execution of learned paths, achieving 10-100x performance improvement for repeated task patterns while preserving governance, durability, and fallback guarantees.

**Next Step:** Architecture gate review for M8 design questions (DO NOT IMPLEMENT YET).

---

**Document Status:** FINAL  
**Purpose:** ANALYSIS ONLY  
**Implementation:** BLOCKED until architecture gate approval

**DO NOT BEGIN M8 IMPLEMENTATION.**

Next: Architecture review and M8 gate decision.
