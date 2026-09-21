# M7 Execution Path Learning — Architecture Gate Summary

**Date:** 2026-09-20  
**Author:** lov3r  
**Status:** ARCHITECTURE ANALYSIS COMPLETE — IMPLEMENTATION DEFERRED TO M8

---

## Executive Summary

This document summarizes the architecture gate analysis for **ReAct Execution Path Learning / Execution Cache**, a proposed feature that would allow Arctra to discover, verify, persist, and reuse successful execution paths instead of reasoning from scratch on every execution.

**Full Analysis:** See `M7-HARNESS-LONG-RUNNING-PROCESS-ARCHITECTURE-GATE.md`

---

## The Core Idea

### Current State

```
Every agent execution:
  User Request → Full ReAct Reasoning → Tool Execution → Result
  (High LLM cost, high latency, even for identical tasks)
```

### Proposed Evolution

```
First execution:
  User Request → ReAct Discovery → Tool Execution → Verify → Cache Path

Subsequent compatible executions:
  User Request → Match Cached Path → Execute Path → Verify → Result
  (Lower LLM cost, lower latency, fallback to ReAct if path fails)
```

---

## Key Architectural Findings

### 1. What Gets Cached

✅ **Cache:**
- Execution structure (tool sequence, dependencies)
- Parameter binding patterns (how to bind runtime inputs)
- Tool capability requirements (tool versions, schemas)
- Verification contract (how to verify success)
- Governance requirements (policies that must pass)
- Failure transitions (what to do on error)

❌ **NEVER Cache:**
- Model chain-of-thought text
- Governance/approval decisions
- Credentials, tokens, secrets
- Stale runtime values
- User-specific literal data

**Principle:** Cache execution knowledge, not execution artifacts.

---

### 2. Conceptual Model

```
ExecutablePath {
  pathId, pathVersion
  intentPattern, inputSchema
  requiredTools, toolVersionFingerprints
  steps: List<ExecutionStep>
  governanceRequirements
  verificationContract
  fallbackBehavior
  provenance
}

ExecutionStep {
  stepId, operation
  toolName?, argumentBindings
  reasoningSlot? (for hybrid paths)
  dependsOn, onSuccess, onFailure
}
```

---

### 3. Architecture Principles

#### ReAct as Discovery, Not as Runtime

**Current perception:**
```
arctra-runtime-react → ReAct IS the runtime
```

**Future model:**
```
ReAct = ONE execution discovery mechanism
Runtime = manages multiple execution strategies
```

#### Governance Must Always Re-Run

**Critical invariant:**
```
CACHED PATH ≠ CACHED AUTHORIZATION

Every governance decision must be made fresh.
Past approval does NOT imply current authorization.
```

#### M6 Semantics Preserved

```
Cached-path execution = execution planning optimization
NOT a replacement execution system

All M6 guarantees must hold:
- operationId uniqueness
- Idempotency
- Recovery classification
- Checkpoint/Resume
- Durability semantics
```

#### Verification Is Critical

```
Path Lifecycle:
  DISCOVERED → EXECUTED → VERIFIED → REUSABLE

Verification on every reuse:
  Execute → Verify → If fails: INVALIDATE → Fallback to ReAct
```

---

### 4. Proposed Layering

```
┌─────────────────────────────────────────┐
│ AgentRuntime / Harness                  │
│ (Session, Process, Policy, Budget)      │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│ EXECUTION PLANNING LAYER (NEW)          │
│ ├─> ExecutionPathResolver               │
│ ├─> ExecutionPathStore                  │
│ └─> PathLearningCoordinator             │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│ EXECUTION STRATEGY LAYER                │
│ ├─> ReActExecutionStrategy              │
│ ├─> CachedPathExecutionStrategy (NEW)   │
│ └─> DeterministicWorkflowStrategy (future)│
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│ COMMON EXECUTION SEMANTICS              │
│ (Tool Execution, Governance, Verification)│
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│ M6 DURABLE EXECUTION KERNEL (unchanged) │
└─────────────────────────────────────────┘
```

**Key insight:** Planning layer decides HOW to execute, M6 kernel ensures EACH execution is durable.

---

### 5. New Authority: ExecutionPathStore

ExecutionPathStore becomes a new source of truth:

**Authority:**
- "This path structure has been verified to work"
- "This path applies to intent pattern Y"
- "This path requires tools [A, B, C]"
- "This path has success rate R"

**NOT Authority For:**
- Current execution state (CheckpointStore)
- Physical invocation truth (InvocationStateStore)
- Current authorization (PolicyEngine)
- Conversation history (ChatMemory)

**Boundary:** Learned execution knowledge, not execution state.

---

### 6. Security and Governance

#### Multi-Tenancy

```
Path Scope:
  - GLOBAL: truly generic operations only
  - TENANT: most common (scoped to tenant)
  - USER: personal workflows
  - AGENT: agent-specific patterns

Never share:
  - Credentials
  - Authorization tokens
  - Tenant-specific data literals
  - Approval decisions
```

#### Governance Re-Execution

```
Original Execution (3 days ago):
  User had ADMIN role → Action approved

Current Execution (now):
  User role revoked to READ_ONLY → ???

❌ WRONG: "Path was approved before, execute"
✅ RIGHT: "Check current policy NOW"
```

---

### 7. Path Matching Contract

Before reusing a cached path:

```
Verify:
  ✅ Intent compatible
  ✅ Input schema compatible
  ✅ Tools available
  ✅ Tool versions compatible
  ✅ Preconditions met
  ✅ Governance policies exist (not approved, just exist)
  ✅ Verification available
  ✅ Environment compatible
  ✅ Path status == VALID

If ANY check fails:
  → Do not use cached path
  → Fallback to ReAct
```

---

### 8. Invalidation Model

**DO NOT use simple TTL.**

**USE semantic versioning:**

```
Invalidate when:
  1. Tool definition changed
  2. Tool schema changed (fingerprint mismatch)
  3. Agent definition changed
  4. Business policy changed
  5. Governance policy changed
  6. Verification policy changed
  7. Environment capability changed
  8. Repeated verification failures
  9. Explicit manual invalidation
```

---

### 9. Minimal V1 Scope

#### Includes

✅ Single-agent only  
✅ Single process only (in-memory or local storage)  
✅ Manual path learning trigger  
✅ Exact or simple parameterized match  
✅ Single-tenant only  
✅ Tool schema versioning (basic fingerprinting)  
✅ Basic verification (postcondition checking)  
✅ Governance re-execution  
✅ M6 kernel integration  
✅ Fallback to ReAct  
✅ Path status tracking (VALID/INVALID/SUPERSEDED)  

#### Excludes (Deferred)

❌ Automatic path learning  
❌ Distributed cache  
❌ Cross-tenant sharing  
❌ Semantic matching (embeddings)  
❌ LLM-based verification  
❌ Hybrid paths with reasoning slots  
❌ Path promotion to workflow  
❌ Public plugin SPI  
❌ Multi-agent coordination  
❌ Vector databases  
❌ Background optimization  

---

### 10. Industry Comparison

**Semantic Caching:** Caches LLM responses  
→ Arctra caches execution structure, not responses

**Prompt Caching:** Caches prompt prefixes  
→ Arctra operates at execution level, not token level

**Plan Caching:** Caches high-level plans  
→ Arctra caches verified executable plans with parameterization

**Workflow Compilation:** Converts dynamic to static  
→ Arctra keeps fallback to dynamic (workflows are final)

**Trace Replay:** Replays recorded traces  
→ Arctra generalizes and parameterizes, not replay

**Agent Memory:** Agents remember tasks  
→ Arctra stores executable structures, not natural language

**Differentiator:** Arctra combines learned execution knowledge with enterprise governance, verification, and durable execution semantics.

---

## Major Risks

1. **Over-generalization:** Path applied too broadly → Strict matching, verification
2. **Under-generalization:** Paths too specific, rarely reusable → Careful parameterization
3. **Stale business logic:** Cached path embeds outdated rules → Business rule versioning
4. **Tool schema drift:** Tool changes subtly → Schema fingerprinting, invalidation
5. **Governance bypass:** Cached path skips authorization → NEVER cache approvals
6. **Cross-tenant leakage:** Path exposes tenant data → Strict tenant scoping in V1
7. **Verification weakness:** Bad paths marked valid → Strong postconditions required
8. **Fallback loop:** Repeated fallback without learning → Track failure rate, invalidate
9. **Cache poisoning:** Malicious execution creates bad path → Verification before caching
10. **Silent fallback:** User expects cached speed, gets ReAct latency → Observability

---

## Economic Value Hypothesis

**Expected Impact:**

```
✅ Lower LLM token usage (fewer reasoning calls)
✅ Lower latency (skip reasoning for known patterns)
✅ Lower cost (reuse verified execution)
✅ Higher determinism (same path for similar tasks)
✅ Higher reliability (verified paths)
✅ Higher auditability (explicit execution structure)
✅ Higher repeatability (parameterized reuse)
✅ Higher throughput (less per-request reasoning)
```

**Requires Measurement:**

Cannot assume savings without workload data.

Must measure:
- How often do users run identical/similar tasks?
- What is actual token/latency reduction?
- What is complexity cost vs benefit?

---

## Final Recommendation

### DEFER TO M8, AFTER V1 VERTICAL SLICES

**Rationale:**

1. ✅ Architecturally sound
2. ✅ Addresses real cost/latency problem
3. ✅ Differentiating feature
4. ❌ Premature without workload data
5. ❌ M7 Harness is higher priority
6. ❌ V1 Vertical Slices must prove platform value first

**Order of Work:**

```
1. M7 Harness / Long-Running Process (core HITL, suspension, time)
2. V1 Vertical Slices (Knowledge Assistant, Incident Investigator end-to-end)
3. Measure real workload patterns
4. IF cost/latency savings justify complexity:
     → M8: Implement minimal path learning V1
5. IF NOT justified:
     → Defer indefinitely, focus elsewhere
```

---

## Minimum Architecture Change Required (Future)

**When path learning starts (M8 or later):**

**Phase 1: No Module Changes**
- Document that `arctra-runtime-react` is ONE strategy
- `AgentExecutionEngine` interface already strategy-agnostic

**Phase 2: Introduce Strategy Layer**
- Create ExecutionStrategy interface (or reuse AgentExecutionEngine)
- Add CachedPathExecutionStrategy
- Add CompositeExecutionEngine for strategy selection
- Keep M6 kernel unchanged

**Phase 3: Restructure Modules (Optional, Much Later)**
- Split execution strategies into separate modules
- Only if feature proves valuable
- Do NOT reorganize prematurely

---

## Key Architectural Question

**Should Arctra evolve toward:**

**REACT AS ONE EXECUTION DISCOVERY MECHANISM**

**rather than:**

**REACT AS THE RUNTIME ITSELF?**

**Answer: YES.**

**Reason:** Allows future execution strategies without breaking changes.

---

## The Core Principle

```
Do not cache reasoning.
Cache verified reusable execution knowledge.

ReAct explores.
Verification establishes trust.
Executable paths capture reusable structure.
The runtime executes.
M6 guarantees safe durable execution.
The Harness decides how long-running work progresses.
```

If these boundaries hold, repeated agent reasoning can gradually become reusable execution capability instead of recurring inference cost.

---

## Related Documents

- **Full Analysis:** `M7-HARNESS-LONG-RUNNING-PROCESS-ARCHITECTURE-GATE.md`
- **M6 Durable Execution:** `M6-POST-T6.4-RUNTIME-ARCHITECTURE-STABILIZATION-CLOSURE.md`
- **Architecture:** `ARCHITECTURE-V7.md`
- **Constitution:** `CLAUDE.md`

---

**Status: ANALYSIS COMPLETE — AWAITING V1 WORKLOAD DATA BEFORE IMPLEMENTATION DECISION**

---

**@author lov3r**
