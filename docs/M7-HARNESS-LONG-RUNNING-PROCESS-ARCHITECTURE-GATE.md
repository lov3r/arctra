# M7 Harness & Long-Running Process Architecture Gate

**Date:** 2026-09-20  
**Status:** ARCHITECTURE GATE  
**Reference Project:** [LongHorizon-Harness](https://github.com/AMAP-ML/LongHorizon-Harness) v0.1.7  
**Reviewed Against:** Arctra M6 Durable Execution Kernel (Frozen)

---

## Executive Summary

**Decision:** **GO — RECOVERY CONTROL PLANE SHOULD PRECEDE HARNESS LAYER**

**Rationale:**

LongHorizon-Harness addresses **long-horizon computer-use task execution** through explicit Manager/Executor/Auditor role separation and verified-progress state management. Arctra can learn valuable architectural lessons, but **Arctra does NOT currently need a full LongHorizon-style harness layer** because:

1. **Different Problem Domains:**
   - LongHorizon: Long-horizon computer-use (GUI + CLI automation, dozens of hours, adversarial verification)
   - Arctra: Enterprise agent engineering runtime (knowledge/incident/business agents, multi-engine support, spring ecosystem)

2. **M6 Already Covers Core Execution Durability:**
   - Checkpoint-bounded crash recovery ✅
   - Physical attempt identity ✅
   - Invocation-level resume ✅
   - At-least-once tool execution (HITL paths) ✅

3. **Missing Infrastructure Layer, Not Missing Execution Layer:**
   - Process discovery/enumeration (deferred M6 → **M7 Control Plane**)
   - Operator-driven resumption (**M7 Control Plane**)
   - Run lifecycle supervision (**M7 Control Plane**)
   - Multi-process coordination (M8+)

**Key Insights Extracted from LongHorizon:**

| LongHorizon Concept | Arctra Equivalent | Action |
|---------------------|-------------------|--------|
| **Verified Progress** | Evidence (exists, but not progress authority) | Evaluate whether M7 needs AcceptedProgress semantic |
| **Fresh Context** | ChatMemory reconstruction after resume | ✅ Already correct: M6 ChatMemory is input, not authority |
| **Manager/Executor/Auditor** | Application-level pattern | Document as design pattern, not framework requirement |
| **Bounded Step** | AgentProcess boundary | ✅ No new concept needed (processId already stable) |
| **Run Supervision** | **Missing** | **M7 Recovery Control Plane (NEXT)** |
| **Environment Abstraction** | RuntimeBinding | ✅ Already exists, different scope |
| **Run/Round Ledger** | ExecutionLedger | ✅ Already exists, similar purpose |
| **Completion Guard** | Policy + Evidence | Evaluate verification boundary |
| **Human Gate** | HITL + Approval | ✅ Already exists, different mechanism |

**Recommended Next Subsystem:** **M7 Recovery Control Plane**

---

## 1. LongHorizon-Harness Source Architecture

### 1.1 Actual Architecture from Source

**Core Components:**

```
Supervisor (service.py)
    ├── Process Lifecycle Management
    ├── Worker Process Spawn/Control
    ├── Run Discovery/Enumeration
    └── Control Bus (owner/status records)
        ↓
Manager Loop (manager.py)
    ├── Round Orchestration (run/resume)
    ├── Task State Maintenance (current_task_state, current_task_contract)
    ├── Role Episode Coordination (Manager → Executor → Auditor)
    ├── Human Gate (approval/instruction injection)
    └── Round Ledger (rounds.jsonl)
        ↓
AgentAdapter (base.py)
    ├── Backend Abstraction (Claude Code, Codex, OpenCode, DeepSeek Harness)
    └── Episode Execution (prompt → EpisodeResult)
        ↓
Environment (base.py)
    ├── exec(command) → ExecResult
    ├── screenshot() → bytes
    └── upload/download
```

**Key Semantic Layers:**

1. **Supervisor Layer** — Process supervision, not execution semantics
2. **Manager Layer** — Long-running task loop coordination
3. **Adapter Layer** — Backend execution abstraction (preserves native loops)
4. **Environment Layer** — Execution environment abstraction

### 1.2 NOT Three Independent Agents

**Critical Finding:** Manager/Executor/Auditor are **role boundaries inside one loop**, NOT three separate autonomous agents.

- **One shared task state:** `current_task_state` and `current_task_contract` are maintained by Manager, read by all roles
- **No independent state:** Executor and Auditor do NOT maintain their own versions of task progress
- **Role episodes are bounded:** Each role receives a fresh prompt per round, but the authoritative task state lives in Manager's loop context

**Evidence from source:**

```python
# manager.py line 265-266
current_task_state = ""
current_task_contract = ""

# manager.py line 357-358 — Manager prompt construction
manager_prompt = build_role_manager_prompt(
    task=task,
    rounds=rounds,
    round_index=round_index,
    task_state=current_task_state,  # ← Authoritative state
    task_contract=current_task_contract,
    ...)

# manager.py line 491-492 — Manager updates state
current_task_state = extract_role_task_state(plan_text, fallback=current_task_state)
current_task_contract = extract_role_task_contract(plan_text, fallback=current_task_contract)
```

**Implication:** This is **not a Multi-Agent pattern**. It's a **role-based bounded execution pattern** where one loop maintains authority.

---

## 2. LongHorizon Execution Loop Reconstruction

### 2.1 The Actual Semantic Loop

```
Original Goal (task: str)
    ↓
[Resume: Recover Rounds from rounds.jsonl Ledger]
    ↓
Round Loop (while round_index < round_budget):
    ↓
Manager Episode (bounded)
    ├── Input: task + rounds history + current_task_state + current_task_contract
    ├── Output: plan_text + next_step (gui/cli/done/blocked/ask)
    ├── Extract: updated task_state + task_contract
    └── Record: ManagedRound (partial)
    ↓
[if next_step == "done" without audit evidence → reject, feedback, continue]
    ↓
Executor Episode (gui or cli, bounded)
    ├── Input: executor_prompt (with subtask from Manager)
    ├── Tool/Computer-Use execution
    ├── Output: executor_output (what executor claims happened)
    └── Runtime signals (timeout/error/cancelled)
    ↓
[Pre-Audit Workspace Snapshot]
    ↓
Auditor Episode (bounded, read-only enforced)
    ├── Input: auditor_prompt (original task + executor output + workspace inspection)
    ├── Independent environment inspection (files, logs, tests, UI)
    ├── Output: audit_report with control header
    │   - Status: complete / incomplete / blocked
    │   - Integrity: clean / suspect / violation
    │   - Contract audit: aligned / unknown / needs_revision / invalid
    └── Runtime signals + workspace mutation detection
    ↓
[Workspace Mutation Guard]
    ├── If auditor changed workspace → restore snapshot
    ├── If auditor deleted fabricated artifacts → record deletion ledger
    └── Integrity findings recorded
    ↓
[Acceptance Constraint Guard]
    ├── If blocking constraints listed → downgrade "complete" to "incomplete"
    └── Completion guard enforced by harness
    ↓
Parse Audit Report
    ├── Structured: AuditReport (status, integrity, contract_audit, findings)
    └── Record: ManagedRound (complete)
    ↓
[Decision Point]
    ├── complete + clean + aligned → completion_satisfied = True
    ├── incomplete / suspect / violation → continue next round
    ├── blocked / ask → human gate
    └── manager failure / auditor failure → classify + recover or abort
    ↓
[Human Gate (end of round)]
    ├── Check: completion / max rounds / blocked / ask / repeated failures
    ├── Write final response (if ending)
    ├── Call human_hook (dashboard approval/instruction injection)
    ├── Outcome: continue (reopen budget + inject instructions) OR stop
    └── Update: round_budget, carryover_instructions, abort_reason
    ↓
[Record Round]
    ├── Append to rounds.jsonl (ledger)
    ├── Write round artifacts (plan, executor output, audit report, task state)
    └── Emit progress events
    ↓
[Loop Decision]
    ├── completion_satisfied → write final report → DONE
    ├── abort_reason set → write failure report → DONE
    ├── round_index >= round_budget → max rounds gate → DONE or continue
    └── else → round_index++, continue
```

**Key Loop Invariants:**

1. **One authoritative task state:** `current_task_state` and `current_task_contract` maintained by Manager loop
2. **Round ledger is recovery authority:** `rounds.jsonl` replayed on resume
3. **Fresh context per episode:** Each role starts with fresh model context, but sees accumulated task state
4. **Verified progress only:** Executor claims are NOT trusted until Auditor confirms
5. **Harness enforces guards:** Completion guard, workspace mutation detection, acceptance constraints

### 2.2 What This Loop Proves

**Progress Authority:**

- Executor output is **claim**, not fact
- Auditor report is **verification**, not final authority
- **Manager's accepted task_state** is the only progress truth after verification passes

**Fresh Context Semantics:**

- Model conversation context resets per episode
- Task state carried externally (not in LLM context)
- **Model context is execution input, NOT process authority**

**Bounded Step Model:**

- One round = one Manager planning + one Executor action + one Auditor verification
- Each episode has timeout budget (separate from round budget)
- Episodes are independent (can use different models/backends per role)

---

## 3. LongHorizon Authority Model

### 3.1 State Authority Hierarchy

```
Run Authority (Supervisor)
    ├── runs/<run-id>/owner.json (process owner + PID)
    ├── runs/<run-id>/status.json (lifecycle status)
    └── Control commands (lifecycle-stop, lifecycle-resume)
        ↓
Task State Authority (Manager Loop)
    ├── current_task_state (what's been accomplished)
    ├── current_task_contract (acceptance criteria)
    ├── rounds: List[ManagedRound] (in-memory + ledger)
    └── task: str (original goal, immutable)
        ↓
Round Ledger (Durable History)
    ├── rounds.jsonl (append-only, latest entry per round_index wins)
    ├── Recovery: _recorded_rounds(role_dir) replays ledger
    └── Resume semantics: restore rounds → restore task_state/task_contract
        ↓
Episode Results (Transient Claims)
    ├── Manager: plan_text + next_step
    ├── Executor: executor_output (unverified claim)
    └── Auditor: audit_report (verification result)
        ↓
Verification Authority (Harness Guards)
    ├── Completion guard (reject "done" without clean audit)
    ├── Acceptance constraint guard (downgrade if blocking constraints exist)
    ├── Workspace mutation guard (restore snapshot if auditor wrote)
    └── Integrity findings (violation → block progress)
```

**Critical Distinction:**

| Layer | Authority | Durability | Recovery |
|-------|-----------|-----------|----------|
| **Supervisor** | Process lifecycle | owner.json + status.json | Enumerate runs, spawn workers |
| **Manager Loop** | Task progress state | rounds.jsonl | Replay ledger → restore task_state |
| **Episode Results** | Role output (transient) | Trajectory artifacts | Not authoritative for recovery |
| **Harness Guards** | Verification enforcement | Audit reports | Not recovered (re-executed) |

**Key Architectural Principle:**

> **Manager's task_state is process progress authority.**  
> **Rounds ledger is recovery authority.**  
> **Episode results are input to next round, not durable state.**

### 3.2 What LongHorizon Does NOT Store

**No process-level checkpoint in the AgentProcess sense:**

- LongHorizon does NOT have a single "SuspensionCheckpoint" equivalent
- State is reconstructed from `rounds.jsonl` + extracting task_state from last round
- Recovery = replay ledger, not load single checkpoint

**No tool-level attempt identity:**

- No `operationId` / `toolCallId` / `attemptId` tracking
- No CHECK A/B duplicate detection at tool level
- Idempotence is application responsibility (executor must handle retries)

**No invocation-level crash recovery:**

- If executor crashes mid-execution, entire round is retried
- No sub-round recovery (entire round is atomic unit)
- Workspace snapshot restored if auditor mutates, but no finer-grained recovery

**Implication for Arctra:**

LongHorizon's recovery granularity is **round-level**, not **tool-invocation-level**.  
Arctra M6 already provides **finer-grained recovery** (tool invocation + physical attempt).

---

## 4. Arctra Current Execution Hierarchy

### 4.1 Arctra M6 Semantic Hierarchy (As Verified)

```
AgentDefinition (who)
    ↓
AgentRequest (what + session context)
    ↓
AgentProcess (when materialized)
    ├── processId (stable across suspend/resume cycles)
    ├── ProcessStatus (RUNNING / WAITING / COMPLETED / FAILED)
    └── Dynamic materialization (only when crossing sync boundary)
        ↓
AgentExecutionContext
    ├── sessionId (nullable, for multi-turn)
    ├── durability mode (EPHEMERAL / DURABLE, M6-T6.3 pending)
    └── runtimeBindingKey (for RuntimeBindingResolver)
        ↓
Execution (per invocation)
    ├── Tool batch materialization (ALLOW vs REQUIRE_APPROVAL)
    ├── Governance decision point (Policy evaluation)
    └── Physical execution (with attempt tracking in HITL paths)
        ↓
Durable Checkpoint Authority (M6 Frozen)
    ├── CheckpointStore (single authority for continuation state)
    ├── SuspensionCheckpoint (processId, checkpointVersion, disposition, pendingBatch)
    ├── ContinuationDisposition (RUNNABLE / WAITING_FOR_SIGNAL)
    └── executionEpoch (crash/restart detection)
        ↓
Invocation State Authority (M6-T5)
    ├── InvocationStateStore (physical attempt identity)
    ├── CHECK A/B duplicate detection (operationId, toolCallId, attemptId)
    ├── Recovery classification (SUCCESS_IDEMPOTENT, UNCERTAIN, FAILURE_RETRYABLE)
    └── Operator-driven resolution (for UNCERTAIN cases)
        ↓
Execution Ledger (M6-T1, Historical Authority)
    ├── ExecutionLedger (append-only, audit/query/diagnosis)
    ├── ExecutionRecord (processId, sequence, eventType, payload)
    └── NOT recovery authority (Checkpoint remains authority)
        ↓
Evidence & ChatMemory (Execution Context)
    ├── Evidence (tool results, retrieval results, structured outputs)
    ├── ChatMemory (conversation history, reconstructed after resume)
    └── Both are execution INPUT, not durable authority
```

**Arctra M6 Authority Boundaries (Verified):**

| Authority Type | Location | Durability | Recovery Role |
|----------------|----------|-----------|---------------|
| **Continuation State** | CheckpointStore | Durable | Primary recovery authority |
| **Physical Attempt** | InvocationStateStore | Durable (HITL paths only) | Duplicate detection + classification |
| **Execution History** | ExecutionLedger | Durable | Audit/query, NOT recovery authority |
| **Evidence** | Per-execution collection | Transient (part of AgentResult) | Execution output, not authority |
| **ChatMemory** | Spring AI ChatMemory | Session-scoped | Input to execution, not authority |
| **Task Progress** | **MISSING** | **N/A** | **No equivalent to LongHorizon task_state** |

**Critical Finding:**

Arctra M6 has **checkpoint-bounded recovery** at the **tool invocation level**, but does NOT have **task-level progress authority** equivalent to LongHorizon's `current_task_state`.

---

## 5. Semantic Mapping Table

### 5.1 Core Concept Mapping

| LongHorizon Concept | Arctra M6 Equivalent | Coverage | Semantic Mismatch | Action |
|---------------------|----------------------|----------|-------------------|--------|
| **Run** | AgentProcess (when materialized) | Partial | LH Run = entire task; Arctra Process = suspension boundary | ACCEPT — Different scopes, both valid |
| **Round** | No direct equivalent | None | LH Round = Manager+Executor+Auditor cycle | NOT NEEDED — Application pattern |
| **Manager** | No equivalent | None | Planning/scheduling role | NOT NEEDED — Application responsibility |
| **Executor** | AgentExecutionEngine | Semantic overlap | LH Executor = one bounded action; Arctra Engine = full execution loop | ACCEPT — Different abstraction levels |
| **Auditor** | No equivalent | None | Independent verification role | EVALUATE — See §5.5 |
| **Verified State (task_state)** | **MISSING** | **None** | **LH has explicit progress authority; Arctra does not** | **CRITICAL — See §8** |
| **Fresh Context** | ChatMemory reconstruction | ✅ Correct | No mismatch | REUSE EXISTING |
| **Checkpoint** | SuspensionCheckpoint | ✅ Complete | LH = round ledger replay; Arctra = single checkpoint | ACCEPT — Different mechanisms, both valid |
| **AgentAdapter** | AgentExecutionEngine | Semantic overlap | LH preserves backend loops; Arctra abstracts execution | ACCEPT — Same goal, different boundary |
| **Environment** | RuntimeBinding | Semantic overlap | LH = exec/screenshot/upload; Arctra = execution context resolution | ACCEPT — Different scopes |
| **Supervisor** | **MISSING** | **None** | **Process lifecycle management** | **M7 CONTROL PLANE (NEXT)** |
| **Event stream** | ExecutionLedger | ✅ Exists | LH = role trajectories; Arctra = execution events | REUSE EXISTING |
| **Trajectory** | ExecutionRecord payload | Partial | LH = detailed role actions; Arctra = high-level events | ACCEPT — Different granularity |
| **Human gate** | HITL + Approval | ✅ Exists | LH = mid-round injection; Arctra = suspension boundary | ACCEPT — Different timing |
| **Completion guard** | Policy (partial) | Partial | LH = harness-enforced; Arctra = policy-driven | EVALUATE — See §5.5 |

### 5.2 Authority Mapping

| Authority Dimension | LongHorizon | Arctra M6 | Gap |
|---------------------|-------------|-----------|-----|
| **Process Lifecycle** | Supervisor (owner.json, status.json) | **MISSING** | **M7 Control Plane needed** |
| **Task Progress** | Manager (task_state, task_contract) | **MISSING** | **Evaluate if needed** |
| **Continuation State** | Rounds ledger (replay) | CheckpointStore (single checkpoint) | Different mechanisms, both valid |
| **Execution History** | rounds.jsonl + events.jsonl | ExecutionLedger | ✅ Equivalent |
| **Physical Attempt** | No equivalent | InvocationStateStore (M6-T5) | Arctra MORE granular |
| **Verification** | Auditor report (harness-enforced) | Policy + Evidence (application-driven) | Different responsibility assignment |

### 5.3 Recovery Mapping

| Recovery Dimension | LongHorizon | Arctra M6 | Winner |
|--------------------|-------------|-----------|--------|
| **Granularity** | Round-level (entire Manager+Executor+Auditor cycle) | Tool-invocation-level (per pendingToolCall) | **Arctra finer** |
| **Mechanism** | Replay rounds.jsonl → restore task_state | Load SuspensionCheckpoint → resume execution | Different, both valid |
| **Idempotence** | Application responsibility (executor handles retries) | Framework + Application (CHECK A/B + InvocationStateStore) | **Arctra stronger** |
| **Crash Detection** | Worker PID tracking (supervisor) | executionEpoch (M6-T4F) | Equivalent |
| **Duplicate Prevention** | None (round retries acceptable) | operationId + attemptId tracking | **Arctra stronger** |

### 5.4 Execution Model Mapping

| Execution Aspect | LongHorizon | Arctra M6 | Comment |
|------------------|-------------|-----------|---------|
| **Bounded Unit** | Round (Manager → Executor → Auditor) | Tool batch (pendingBatch) | LH coarser, Arctra finer |
| **Context Management** | Fresh per episode, task state external | Fresh per resume, ChatMemory reconstructed | ✅ Same principle |
| **Progress Commit** | After audit passes (task_state updated) | After checkpoint saved (continuation persisted) | Different semantic layers |
| **Failure Handling** | Retry entire round | Classify attempt + retry or resolve | Arctra more sophisticated |
| **Budget** | Round budget + episode timeouts | No round concept (unlimited continuations) | LH has explicit bound |

### 5.5 Verification Boundary Analysis

**LongHorizon Verification:**

```
Executor Output (claim)
    ↓
Auditor Episode (independent inspection)
    ├── Read-only workspace access
    ├── Independent environment inspection
    ├── Control header (Status / Integrity / Contract)
    ├── Blocking acceptance constraints
    └── Artifact deletion ledger (for fabricated outputs)
    ↓
Harness Guards
    ├── Completion guard (reject "done" without aligned audit)
    ├── Workspace mutation detection + restore
    ├── Acceptance constraint guard
    └── Integrity violation handling
    ↓
Accepted Progress (task_state updated)
```

**Arctra Verification:**

```
Tool Execution
    ↓
Evidence Collection (EvidenceCapturingToolCallback)
    ├── Tool results captured
    └── Evidence attached to AgentResult
    ↓
Policy Evaluation (pre-execution)
    ├── ToolGovernancePolicy.evaluate()
    ├── Decision: ALLOW / REQUIRE_APPROVAL / DENY
    └── Suspension if approval required
    ↓
Human Approval (if required)
    ├── Process suspended (WAITING_FOR_SIGNAL)
    ├── Operator reviews pending batch
    └── Resume with approval signal
    ↓
Execution Result (AgentResult with evidences)
```

**Key Differences:**

| Dimension | LongHorizon | Arctra M6 | Implication |
|-----------|-------------|-----------|-------------|
| **Verification Timing** | Post-execution (Auditor checks after) | Pre-execution (Policy before) + Post-execution (Evidence) | Arctra focuses on governance, not post-verification |
| **Independent Inspection** | Auditor inspects workspace independently | No independent verification | LH stronger verification |
| **Verification Authority** | Harness-enforced guards | Application-driven (Policy + Evidence) | Different responsibility |
| **Progress Acceptance** | Explicit (audit must pass) | Implicit (execution completes) | LH more rigorous |
| **Integrity Checking** | Workspace mutation detection | No workspace integrity checks | LH computer-use specific |

**Should Arctra Add Verification Layer?**

**NO for V1.** Reasons:

1. **Different domain:** Enterprise agents (knowledge/incident/business) don't need adversarial workspace verification
2. **Evidence already exists:** AgentResult.evidences provides post-execution evidence
3. **Policy covers governance:** Pre-execution governance is correct boundary for enterprise use cases
4. **Verification is application responsibility:** Not every agent needs independent auditor

**Possible Future:** Document verification pattern as application-level design pattern, not framework requirement.

---

## 6. Fresh Context vs Durable State Analysis

### 6.1 LongHorizon Fresh Context Model

**Key Principle:** Model conversation context resets per episode, but task state is carried externally.

**Evidence from source:**

```python
# manager.py line 357 — Manager prompt rebuilt fresh each round
manager_prompt = build_role_manager_prompt(
    task=task,  # Original goal (immutable)
    rounds=rounds,  # Round history (from ledger)
    round_index=round_index,
    task_state=current_task_state,  # External state
    task_contract=current_task_contract,  # External criteria
    ...)
```

**What this means:**

- Manager episode sees original task + round history, NOT previous conversation
- Executor episode gets fresh context with subtask prompt
- Auditor episode gets fresh context with verification prompt
- **Task state is maintained OUTSIDE model context**

**Recovery implications:**

- Resume = replay rounds ledger → restore task_state → rebuild fresh Manager prompt
- Model conversation history is NOT the authority
- **Model context is execution INPUT, not process authority**

### 6.2 Arctra Fresh Context Model

**Current M6 Behavior:**

```java
// After resume, ChatMemory is reconstructed
AgentResult result = process.resume(signal);

// Inside SpringAiToolCallingEngine:
// 1. Load checkpoint (processId, sessionId, pendingBatch)
// 2. Reconstruct ChatMemory from sessionId
// 3. MessageChatMemoryAdvisor.before() loads conversation history
// 4. Continue execution with restored context
```

**What Arctra does:**

- ChatMemory is session-scoped, persisted separately (Spring AI responsibility)
- Checkpoint contains pendingBatch (what to execute), NOT conversation history
- **ChatMemory is execution INPUT, not recovery authority**

**Verification:**

✅ **Arctra already follows the correct principle.**

| Aspect | LongHorizon | Arctra M6 | Status |
|--------|-------------|-----------|--------|
| **Model Context Authority** | NOT authoritative (task_state is) | NOT authoritative (Checkpoint is) | ✅ Both correct |
| **Context Reconstruction** | Rebuild prompt from external state | Reload ChatMemory from sessionId | ✅ Both correct |
| **Recovery Authority** | rounds.jsonl ledger | CheckpointStore | ✅ Both valid |
| **Fresh vs Durable Separation** | Explicit (task_state external) | Explicit (Checkpoint + ChatMemory separate) | ✅ Both correct |

**Conclusion:** Arctra M6 already implements the correct fresh-context semantics. No changes needed.

---

## 7. Bounded Step Analysis

### 7.1 LongHorizon Bounded Step

**One Round:**

```
Manager Planning
    ↓
Executor Action (one subtask)
    ↓
Auditor Verification
    ↓
Progress Accepted (or retry)
```

**Characteristics:**

- Round is **coarse-grained** (entire planning + action + verification cycle)
- One round may involve multiple tool calls by Executor
- Retry unit is entire round (if audit fails, re-plan + re-execute + re-audit)
- Round identity is sequential (round_index)
- Round budget is explicit (max_rounds config)

### 7.2 Arctra Bounded Step

**One Process Continuation:**

```
Load Checkpoint
    ↓
Reconstruct ChatMemory
    ↓
Execute Pending Batch (multiple tool calls)
    ↓
Save Checkpoint (if suspended again)
    ↓
Return AgentResult
```

**Characteristics:**

- Continuation is **fine-grained** (per tool batch)
- One continuation executes pendingBatch (List<PendingToolCall>)
- Retry unit is tool invocation (CHECK A/B duplicate detection)
- Process identity is stable (processId across all suspensions)
- No explicit continuation budget (unlimited suspensions allowed)

### 7.3 Comparison

| Dimension | LongHorizon Round | Arctra Process Continuation | Winner |
|-----------|-------------------|----------------------------|--------|
| **Granularity** | Coarse (Manager+Executor+Auditor) | Fine (tool batch) | Arctra finer |
| **Identity** | Sequential (round_index) | Stable (processId) | Different purposes |
| **Retry Unit** | Entire round | Individual tool | Arctra more precise |
| **Budget** | Explicit (max_rounds) | Implicit (unlimited) | LH has explicit bound |
| **Progress Commit** | After audit passes | After checkpoint saved | Different semantic layers |

**Conclusion:**

- LongHorizon's "round" and Arctra's "continuation" serve **different purposes**
- LongHorizon round = task decomposition + verification cycle
- Arctra continuation = execution suspension + resume boundary
- **Both are valid, not competing concepts**

**Should Arctra Add Round Concept?**

**NO.** Reasons:

1. **Different abstraction level:** Arctra operates at execution level, not task decomposition level
2. **processId already stable:** Provides stable identity across suspensions
3. **Round = application pattern:** Manager/Executor/Auditor coordination is application-level, not runtime responsibility
4. **Unlimited suspensions correct:** Enterprise agents may need many approval cycles (no arbitrary bound)

---

## 8. Process-Progress Authority Question

### 8.1 The Critical Question

**Does Arctra need a durable authority representing:**

```
ProcessGoal
AcceptedProgress  ← LongHorizon's task_state equivalent
RemainingWork
CurrentStep
CompletionState
```

### 8.2 Evidence from LongHorizon

**What task_state represents:**

```python
# manager.py line 265-266
current_task_state = ""
current_task_contract = ""

# Updated after each Manager episode
current_task_state = extract_role_task_state(plan_text, fallback=current_task_state)
current_task_contract = extract_role_task_contract(plan_text, fallback=current_task_contract)
```

**Purpose of task_state:**

1. **Accumulated Progress:** What has been accomplished so far (verified by auditor)
2. **Remaining Work:** What still needs to be done
3. **Acceptance Criteria:** What constitutes completion (task_contract)
4. **Context for Next Round:** Manager sees task_state to plan next step

**Why it exists:**

- Long-horizon tasks need **explicit progress tracking** across dozens of rounds
- Model context window is finite (cannot hold entire history)
- task_state is **compressed summary** of verified progress
- Enables recovery without replaying entire task history

### 8.3 Does Arctra Need This?

**Current Arctra State:**

| State Type | Authority | Purpose | Durability |
|-----------|-----------|---------|-----------|
| **Continuation State** | SuspensionCheckpoint | Resume execution | Durable |
| **Execution History** | ExecutionLedger | Audit/query | Durable |
| **Evidence** | AgentResult.evidences | Execution output | Transient |
| **ChatMemory** | Spring AI ChatMemory | Conversation context | Session-scoped |
| **Task Progress** | **NONE** | **N/A** | **N/A** |

**Key Questions:**

1. **Can ChatMemory serve as progress authority?**
   - ❌ NO — ChatMemory is model conversation, not structured progress
   - ❌ NO — ChatMemory is input to execution, not durable authority
   - ❌ NO — ChatMemory may be compacted/windowed (loses history)

2. **Can ExecutionLedger serve as progress authority?**
   - ❌ NO — ExecutionLedger is historical event log, not current state
   - ❌ NO — M6 explicitly states: "Checkpoint remains recovery authority; ledger provides audit/query/diagnosis"
   - ❌ NO — Replaying ledger doesn't give compressed progress summary

3. **Can Evidence serve as progress authority?**
   - ❌ NO — Evidence is per-execution transient output
   - ❌ NO — No accumulation mechanism across suspensions
   - ❌ NO — Evidence is content/proof, not progress state

**Conclusion:**

Arctra currently has **NO equivalent to LongHorizon's task_state**.

### 8.4 Should Arctra Add Process-Progress Authority?

**Arguments FOR:**

1. **Long-running enterprise tasks:** Incident investigation, knowledge synthesis may span many approval cycles
2. **Context window limits:** Cannot hold entire history in ChatMemory indefinitely
3. **Structured progress tracking:** Better than relying on LLM to maintain implicit state
4. **Recovery with progress awareness:** Resume should know what's been accomplished

**Arguments AGAINST:**

1. **V1 scenarios don't need it:** Knowledge Assistant (RAG) and Incident Investigator (tool-based) are relatively short
2. **ChatMemory suffices for V1:** Multi-turn conversation already tracked
3. **No V1 verification mechanism:** Adding progress authority without verification is premature
4. **Premature abstraction:** No real consumer exists yet

**Recommended Decision:**

**DEFER to M7+, but establish extensibility point.**

**Minimal Semantic Model (if added):**

```java
/**
 * Process progress state (optional, for long-running tasks).
 * 
 * Represents what has been accomplished and what remains,
 * independent of model conversation context.
 */
public record ProcessProgress(
    String processId,
    long progressVersion,  // Optimistic locking
    String goalSummary,    // What we're trying to accomplish
    String accomplishedSummary,  // What's been done (verified)
    String remainingWork   // What still needs doing
) {}
```

**DO NOT implement yet.** Wait for real consumer.

---

## 9. AgentProcess Audit

### 9.1 What AgentProcess Represents Today

**From source (AgentProcess.java):**

```java
/**
 * Agent process handle for suspended/long-running executions.
 * 
 * AgentProcess represents a task execution that has exceeded the synchronous
 * invocation boundary and requires lifecycle management.
 * 
 * Dynamic Materialization: AgentProcess materializes only when execution needs
 * to cross the synchronous invocation boundary (e.g., suspension for approval).
 */
public interface AgentProcess {
    String id();  // Stable process identity
    ProcessStatus status();  // RUNNING / WAITING / COMPLETED / FAILED
    AgentResult resume(ContinuationSignal signal);
    AgentResult result();
}
```

**Current Responsibility:**

- **Runtime handle** for suspended execution
- **Continuation handle** (resume() method)
- **Status facade** (status() query)
- **NOT durable process identity** (dynamically materialized)
- **NOT state machine** (status is derived from checkpoint)

### 9.2 Could AgentProcess Evolve for Long-Running Process Authority?

**Current Scope:**

```
AgentProcess = Suspension Boundary Handle
```

**Possible Evolution:**

```
AgentProcess = Long-Running Process Authority?
    ├── processId (already stable)
    ├── ProcessProgress (hypothetical)
    ├── ProcessStatus (already exists)
    └── Lifecycle methods (resume/result already exist)
```

**Analysis:**

| Question | Answer | Implication |
|----------|--------|-------------|
| **Does AgentProcess have correct semantic scope?** | YES — represents suspended execution lifecycle | Could evolve cleanly |
| **Would adding progress state overload it?** | MAYBE — currently focused on suspension boundary | Need to evaluate semantic cohesion |
| **Is it the right abstraction level?** | YES — processId already stable, public API | Good candidate for evolution |
| **Would it destroy current responsibility?** | NO — current methods remain valid | Backward compatible extension possible |

**Recommendation:**

**AgentProcess COULD evolve to include ProcessProgress, but SHOULD NOT do so in M7.**

Reasons:

1. **No real consumer yet:** V1 scenarios don't need progress tracking
2. **Verification mechanism missing:** Progress without verification is incomplete
3. **Keep suspension focus:** Current AgentProcess is correctly scoped for continuation management
4. **Defer to M8+:** When long-running enterprise tasks prove the need

**If added later:**

```java
public interface AgentProcess {
    String id();
    ProcessStatus status();
    
    // M5 continuation methods
    AgentResult resume(ContinuationSignal signal);
    AgentResult result();
    
    // M8+ hypothetical progress methods (DO NOT ADD YET)
    // Optional<ProcessProgress> progress();
    // void updateProgress(ProcessProgress progress);
}
```

---

## 10. Verification Boundary Analysis

### 10.1 LongHorizon Verification Model

**Purpose:** Independent verification before accepting executor claims as progress.

**Mechanism:**

```
Executor Claims → Auditor Independently Verifies → Harness Guards Enforce → Progress Accepted
```

**Key Features:**

1. **Independence:** Auditor inspects environment separately (read-only)
2. **Adversarial:** Assumes executor may be wrong/fabricated results
3. **Structured:** Control header (Status/Integrity/Contract) enforced
4. **Harness-enforced:** Completion guard, acceptance constraints, workspace mutation detection

**Why it exists:**

- Computer-use agents can fabricate artifacts (create files claiming they came from external systems)
- Long-horizon tasks need rigorous completion criteria
- Executor may claim success incorrectly

### 10.2 Arctra Current State

**What Arctra Has:**

1. **Pre-execution Governance:** ToolGovernancePolicy.evaluate()
2. **Evidence Collection:** EvidenceCapturingToolCallback
3. **Post-execution Evidence:** AgentResult.evidences

**What Arctra Does NOT Have:**

1. **Independent verification:** No separate verification agent/role
2. **Workspace integrity checks:** No mutation detection
3. **Acceptance criteria enforcement:** No harness-level completion guard
4. **Adversarial verification:** Assumes tool execution is honest

**Is This a Problem for V1?**

**NO.** Reasons:

1. **Different threat model:** Enterprise tools (query logs, retrieve documents) are trusted, not adversarial
2. **Evidence suffices:** Tool results captured as evidence, sufficient for V1 scenarios
3. **Verification is application responsibility:** Applications can implement verification if needed
4. **No computer-use risks:** Not creating/manipulating files on user's behalf

### 10.3 Should Arctra Add Verification Layer?

**For V1: NO.**

**For Future: MAYBE, as application-level pattern.**

**Possible Future Design (DO NOT IMPLEMENT YET):**

```java
/**
 * Result verifier (application-level pattern, not framework requirement).
 */
public interface ResultVerifier {
    VerificationResult verify(AgentResult result, VerificationContext context);
}

public record VerificationResult(
    VerificationStatus status,  // ACCEPTED / REJECTED / UNCERTAIN
    String reason,
    List<VerificationFinding> findings
) {}
```

**Where it belongs:**

- **NOT in AgentRuntime** (too opinionated)
- **NOT in AgentExecutionEngine** (engine executes, doesn't verify)
- **Application layer** (optional verification strategy)

**Document as pattern:** Show how applications can implement verification using Evidence + custom verification logic.

---

## 11. Manager/Executor/Auditor Comparison

### 11.1 Manager Concept

**LongHorizon Manager:**

- **Responsibility:** Plan next bounded step from task + rounds history + task_state
- **Input:** Original goal, round history, current progress
- **Output:** plan_text, next_step (gui/cli/done/blocked/ask), updated task_state
- **Nature:** Prompt-driven planning role (not a framework component)

**Arctra Equivalent:**

- **NONE directly**
- **Closest:** Application logic that decides what AgentRequest to submit
- **NOT runtime responsibility**

**Should Arctra Add Manager?**

**NO.** Manager is an **application-level pattern**, not runtime infrastructure.

Arctra applications can implement manager-like logic:

```java
// Application code (NOT framework)
public class IncidentInvestigationManager {
    public AgentRequest planNextStep(
        String originalGoal,
        List<AgentResult> history,
        String currentProgress) {
        
        // Application-specific planning logic
        return new AgentRequest(nextStepPrompt);
    }
}
```

**Rationale:**

1. **Planning is domain-specific:** Incident investigation ≠ knowledge synthesis ≠ data analysis
2. **Not every agent needs planning:** Simple RAG queries don't need multi-round planning
3. **Framework stays neutral:** Arctra provides execution runtime, not task decomposition DSL

### 11.2 Executor Concept

**LongHorizon Executor:**

- **Responsibility:** Execute one bounded subtask (GUI or CLI)
- **Input:** Subtask prompt from Manager
- **Output:** Executor output (what happened)
- **Nature:** Bounded action episode with fresh context

**Arctra Equivalent:**

- **AgentExecutionEngine** (semantic overlap)
- **SpringAiToolCallingEngine** (current implementation)

**Mapping:**

| Aspect | LongHorizon Executor | Arctra AgentExecutionEngine | Match? |
|--------|----------------------|---------------------------|--------|
| **Bounded execution** | One subtask per episode | Full request execution | Different scope |
| **Backend abstraction** | AgentAdapter preserves native loops | Engine abstracts execution | ✅ Same goal |
| **Tool execution** | Computer-use + CLI | Tool calling | Different domains |
| **Fresh context** | Per episode | Per invocation | ✅ Same principle |

**Conclusion:**

- **Semantic overlap:** Both abstract execution backend
- **Different scopes:** LH Executor = one step; Arctra Engine = full execution
- **Both valid:** Different abstraction levels for different purposes

**No changes needed.**

### 11.3 Auditor Concept

**LongHorizon Auditor:**

- **Responsibility:** Independently verify executor output
- **Input:** Original task, executor output, workspace state
- **Output:** Audit report (Status/Integrity/Contract)
- **Nature:** Read-only inspection, adversarial verification

**Arctra Equivalent:**

- **NONE**
- **Partial:** Evidence collection, but not independent verification

**Should Arctra Add Auditor?**

**NO for V1.** See §10.3 — Verification is application-level pattern, not framework requirement.

**Summary:**

| Role | LongHorizon | Arctra | Decision |
|------|-------------|--------|----------|
| **Manager** | Planning role | No equivalent | Document as application pattern |
| **Executor** | Bounded action | AgentExecutionEngine | ✅ Already exists (different scope) |
| **Auditor** | Independent verification | No equivalent | Document as application pattern (defer) |

---

## 12. Environment Comparison

### 12.1 LongHorizon Environment

**Contract (environment/base.py):**

```python
class Environment(Protocol):
    async def exec(command: str, timeout: int) -> ExecResult
    async def screenshot() -> bytes
    async def upload(local_path: str, remote_path: str) -> None
    async def download(remote_path: str, local_path: str) -> None
```

**Purpose:**

- Abstract execution environment (local vs future remote)
- Computer-use operations (exec, screenshot)
- File transfer (upload/download for remote workers)

**Current Implementation:**

- `LocalEnvironment` — subprocess execution on local machine

### 12.2 Arctra RuntimeBinding

**Contract:**

```java
/**
 * Runtime binding resolution for AgentProcess recovery.
 */
public interface RuntimeBindingResolver {
    RuntimeBinding resolve(String runtimeBindingKey);
}

public record RuntimeBinding(
    AgentDefinition definition,
    AgentExecutionEngine engine,
    ToolRegistry toolRegistry
    // ... other execution dependencies
) {}
```

**Purpose:**

- Resolve execution dependencies for process resume
- Reconstruct AgentDefinition + Engine + Tools after restart
- Enable cross-instance recovery

### 12.3 Comparison

| Aspect | LongHorizon Environment | Arctra RuntimeBinding | Overlap? |
|--------|------------------------|----------------------|----------|
| **Abstraction Goal** | Execution environment (local/remote) | Execution context reconstruction | Different purposes |
| **Operations** | exec/screenshot/upload/download | No operations (resolution only) | No overlap |
| **Computer-use** | Yes (core feature) | No (not applicable) | Different domains |
| **Recovery** | Not focused on recovery | Primary purpose | Different scopes |

**Conclusion:**

- **Different abstractions:** LH Environment = where execution happens; Arctra RuntimeBinding = how to reconstruct execution context
- **No conflict:** Both can coexist
- **No changes needed**

---

## 13. AgentAdapter Comparison

### 13.1 LongHorizon AgentAdapter

**Contract:**

```python
class AgentAdapter(Protocol):
    async def run_episode(
        prompt: str,
        env: Environment,
        budget: EpisodeBudget,
        live_trajectory_path: str | None
    ) -> EpisodeResult
```

**Purpose:**

- Abstract different agent backends (Claude Code, Codex, OpenCode, DeepSeek Harness)
- Preserve each backend's native execution loop
- Normalize episode results (status, output, duration, metadata)

**Implementations:**

- `ClaudeCodeAdapter` — wraps `claude` CLI
- `CodexAdapter` — wraps `codex` CLI
- `OpenCodeAdapter` — wraps `opencode` CLI
- `DeepSeekHarnessAdapter` — wraps `dsh` CLI

### 13.2 Arctra AgentExecutionEngine

**Contract:**

```java
public interface AgentExecutionEngine {
    AgentResult execute(
        AgentDefinition definition,
        AgentRequest request,
        AgentExecutionContext context
    );
}
```

**Purpose:**

- Abstract execution strategy (ReAct, AgentScope, Graph, Embabel future)
- Pluggable engine implementations
- Spring-native integration

**Implementations:**

- `SpringAiToolCallingEngine` — Spring AI Tool Calling Loop
- Future: `AgentScopeEngine`, `GraphEngine`, `EmbabelEngine`

### 13.3 Comparison

| Aspect | LongHorizon AgentAdapter | Arctra AgentExecutionEngine | Match? |
|--------|--------------------------|----------------------------|--------|
| **Abstraction Goal** | Backend CLI abstraction | Execution strategy abstraction | ✅ Same goal |
| **Preserves Native Loops** | YES (wraps CLI, no modification) | VARIES (Spring AI preserved, future engines TBD) | Similar approach |
| **Normalization** | EpisodeResult (status, output, duration) | AgentResult (content, evidences, process) | ✅ Same principle |
| **Backend Types** | CLI tools (Claude Code, Codex, etc.) | Java libraries (Spring AI, AgentScope, etc.) | Different ecosystems |

**Conclusion:**

- **Same architectural goal:** Abstract execution backend while preserving native behavior
- **Different ecosystems:** LH = CLI tools; Arctra = Java libraries
- **Both valid:** Different implementation strategies for same purpose

**Key Lesson for Arctra:**

When integrating AgentScope/Graph/Embabel, **preserve their native execution loops** where possible (like LH AgentAdapter), rather than forcing them into Spring AI patterns.

---

## 14. Supervisor / Control Plane Comparison

### 14.1 LongHorizon Supervisor

**Responsibilities (supervisor/service.py):**

1. **Process Lifecycle Management**
   - Spawn worker processes (`lh-harness run`)
   - Track worker PID
   - Detect worker crashes/exits
   - Clean up completed runs

2. **Run Discovery/Enumeration**
   - List all runs in `runs/` directory
   - Read owner.json + status.json per run
   - Provide Web API for dashboard

3. **Control Commands**
   - `lifecycle-stop` — graceful stop
   - `lifecycle-resume` — resume interrupted run
   - Revision-based optimistic locking (resume_epoch)

4. **State Files**
   - `owner.json` — {run_id, pid, started_at}
   - `status.json` — {status, updated_at, resume_epoch}
   - Control bus protocol (atomic file operations)

**Key Principle:**

> Supervisor is **infrastructure**, NOT execution semantics.  
> Worker remains normal `lh-harness run` process.  
> Supervisor doesn't re-implement Manager loop.

### 14.2 Arctra Current State

**What Arctra Has:**

- ✅ CheckpointStore (continuation state authority)
- ✅ ExecutionLedger (historical events)
- ✅ AgentProcess.resume() (programmatic resume)

**What Arctra DOES NOT Have:**

- ❌ Process enumeration/discovery
- ❌ Operator-driven resumption (outside application code)
- ❌ Run lifecycle supervision
- ❌ Process owner tracking
- ❌ Graceful stop/resume control
- ❌ Web API for operations

### 14.3 Is This Missing Infrastructure?

**YES.**

**Evidence:**

- M6 CURRENT-STATE.md line 64: "进程发现枚举（延期到 M7）"
- M6 explicitly deferred process discovery to M7
- No way for operator to list/resume suspended processes outside application code

**What's Missing:**

```
Recovery Control Plane (M7)
    ├── Process Discovery (list all suspended processes)
    ├── Process Enumeration (query by status/owner/time)
    ├── Operator-Driven Resumption (resume without application restart)
    ├── Lifecycle Commands (stop/resume/cancel)
    └── Operations API (for dashboard/CLI)
```

**This is THE NEXT SUBSYSTEM.**

---

## 15. Comparison Summary Tables

### 15.1 What Arctra Should Absorb (Top 5)

| # | LongHorizon Idea | Why It Matters | Which Subsystem | Belongs in M7? | Action |
|---|------------------|----------------|-----------------|----------------|--------|
| **1** | **Supervisor / Control Plane** | Process discovery, operator-driven resumption, lifecycle management | **M7 Recovery Control Plane** | **YES** | **IMPLEMENT NEXT** |
| **2** | **Fresh Context Principle** | Model context is input, not authority | Already correct in M6 | N/A | Document as verified principle |
| **3** | **Backend Adapter Pattern** | Preserve native execution loops when integrating engines | Future engine integrations | NO (M8+) | Document as integration guideline |
| **4** | **Verification as Separate Concern** | Don't conflate governance with post-verification | Application layer | NO (document as pattern) | Add to architecture docs |
| **5** | **Bounded Episode Budget** | Explicit timeout per role episode | Already exists (M5 episode budgets) | N/A | Already implemented |

### 15.2 What Arctra Should NOT Absorb (Top 5)

| # | LongHorizon Idea | Why NOT Copy | Reason |
|---|------------------|--------------|--------|
| **1** | **Manager/Executor/Auditor Roles** | Application-level pattern, not framework | Different domain (computer-use vs enterprise agents) |
| **2** | **Round-level Recovery** | Too coarse-grained | Arctra already has finer tool-invocation-level recovery (M6) |
| **3** | **Workspace Mutation Detection** | Computer-use specific | Enterprise agents don't manipulate workspace adversarially |
| **4** | **Harness-Enforced Completion Guard** | Too opinionated | Completion criteria are application-specific |
| **5** | **Task State as Durable Authority** | Premature without verification | No real V1 consumer; defer to M8+ when long-running tasks prove need |

---

## 16. M6 vs M7 Commit Boundaries

### 16.1 M6 Execution Commit (Frozen)

**Authority:** CheckpointStore

**Commit Point:** After tool batch suspended or completed

**What's Committed:**

```java
SuspensionCheckpoint(
    processId,
    checkpointVersion,
    disposition,  // RUNNABLE / WAITING_FOR_SIGNAL
    pendingBatch,  // What to execute next
    accumulatedEvidences,
    executionEpoch
)
```

**Semantics:**

- **Execution continuation committed** (can resume after crash)
- **Tool batch identity committed** (operationId tracks attempts)
- **Physical attempt tracked** (InvocationStateStore for HITL paths)

**Recovery:**

```
Load Checkpoint → Reconstruct ChatMemory → Resume Execution
```

### 16.2 M7 Process-Progress Commit (Hypothetical, NOT M7)

**IF added in future (M8+), would be:**

**Authority:** ProcessProgressStore (new)

**Commit Point:** After verification passes (if verification exists)

**What Would Be Committed:**

```java
ProcessProgress(
    processId,
    progressVersion,
    goalSummary,
    accomplishedSummary,  // Verified progress
    remainingWork
)
```

**Semantics:**

- **Task progress committed** (what's been accomplished)
- **Verified by application** (not framework-enforced)
- **Separate from execution checkpoint** (different semantic layer)

**Recovery:**

```
Load Checkpoint (M6) + Load Progress (M8) → Resume with Progress Awareness
```

### 16.3 Relationship

```
M8+ Process Progress Authority (hypothetical)
    ↓
M6 Execution Checkpoint Authority (frozen)
    ↓
Physical Tool Invocation (M6-T5)
    ↓
Execution Ledger (M6-T1, historical record)
```

**Key Principle:**

> M6 checkpoint commits **execution continuation**.  
> M8+ progress would commit **task accomplishment** (if added).  
> **These are separate semantic layers.**

---

## 17. Minimal Proposed M7 Domain Model

**M7 Focus:** Recovery Control Plane (NOT Process-Progress Authority)

### 17.1 M7 Control Plane Domain Model

```java
/**
 * Suspended process descriptor for discovery/enumeration.
 * 
 * NOT durable checkpoint (CheckpointStore remains authority).
 * Lightweight projection for operations.
 */
public record SuspendedProcessDescriptor(
    String processId,
    String ownerId,  // Which application/instance owns this
    ProcessStatus status,  // WAITING / RUNNING / COMPLETED / FAILED
    Instant suspendedAt,
    Instant lastResumedAt,
    String runtimeBindingKey,
    ProcessDisposition disposition  // RUNNABLE / WAITING_FOR_SIGNAL
) {}

/**
 * Control plane for suspended process lifecycle.
 */
public interface ProcessControlPlane {
    
    /**
     * Discover all suspended processes across instances.
     */
    List<SuspendedProcessDescriptor> listSuspended(ProcessQuery query);
    
    /**
     * Resume suspended process (operator-driven).
     */
    AgentResult resumeProcess(String processId, ContinuationSignal signal);
    
    /**
     * Cancel suspended process.
     */
    void cancelProcess(String processId, String reason);
    
    /**
     * Query process status.
     */
    Optional<SuspendedProcessDescriptor> getProcess(String processId);
}

/**
 * Query for process discovery.
 */
public record ProcessQuery(
    ProcessStatus status,  // Filter by status
    String ownerId,  // Filter by owner
    Instant suspendedAfter,  // Time range
    Instant suspendedBefore
) {}
```

### 17.2 What M7 Does NOT Add

**DO NOT ADD (defer to M8+):**

- ❌ ProcessProgress / AcceptedProgress
- ❌ Task decomposition / Round tracking
- ❌ Verification framework
- ❌ Manager/Executor/Auditor roles
- ❌ Workspace integrity checks
- ❌ Completion guards

**M7 Scope:** Infrastructure for discovering and resuming suspended processes, NOT task-level progress tracking.

---

## 18. Minimal Proposed M7 Public API

### 18.1 New Public Interfaces (M7)

```java
package cn.bitcss.arctra.control;

/**
 * Control plane for suspended process lifecycle management.
 * 
 * @author lov3r
 * @since M7
 */
public interface ProcessControlPlane {
    List<SuspendedProcessDescriptor> listSuspended(ProcessQuery query);
    AgentResult resumeProcess(String processId, ContinuationSignal signal);
    void cancelProcess(String processId, String reason);
    Optional<SuspendedProcessDescriptor> getProcess(String processId);
}

/**
 * Suspended process descriptor for operations.
 * 
 * @author lov3r
 * @since M7
 */
public record SuspendedProcessDescriptor(
    String processId,
    String ownerId,
    ProcessStatus status,
    Instant suspendedAt,
    Instant lastResumedAt,
    String runtimeBindingKey,
    ContinuationDisposition disposition
) {}

/**
 * Query for process discovery.
 * 
 * @author lov3r
 * @since M7
 */
public record ProcessQuery(
    ProcessStatus status,
    String ownerId,
    Instant suspendedAfter,
    Instant suspendedBefore
) {}
```

### 18.2 NO Changes to M6 Public API

**M6 Frozen Interfaces (NO CHANGES):**

- ✅ AgentProcess (no changes)
- ✅ SuspensionCheckpoint (no changes)
- ✅ CheckpointStore (no changes)
- ✅ ExecutionLedger (no changes)
- ✅ Evidence (no changes)

**M7 adds NEW subsystem, does NOT modify M6.**

---

## 19. Target Subsystem Diagram

```
┌─────────────────────────────────────────────────────────────┐
│ Application Layer                                           │
│  ├─ AgentClient API (V1)                                   │
│  ├─ Custom verification logic (application-level)          │
│  └─ Task planning/decomposition (application-level)        │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ M7 Recovery Control Plane (NEXT)                            │
│  ├─ ProcessControlPlane (discovery/enumeration)            │
│  ├─ SuspendedProcessDescriptor (operations projection)     │
│  ├─ Operator-driven resumption                             │
│  └─ Lifecycle commands (resume/cancel)                     │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Agent Runtime (V1)                                          │
│  ├─ AgentDefinition / AgentRequest / AgentResult           │
│  ├─ AgentProcess (suspension handle)                       │
│  ├─ AgentExecutionEngine (execution abstraction)           │
│  └─ AgentExecutionContext (session/durability)             │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ M6 Durable Execution Kernel (FROZEN)                        │
│  ├─ CheckpointStore (continuation authority)               │
│  ├─ SuspensionCheckpoint (durable state)                   │
│  ├─ InvocationStateStore (attempt tracking)                │
│  ├─ ExecutionLedger (historical record)                    │
│  ├─ CHECK A/B (duplicate detection)                        │
│  └─ Recovery Classification (T5)                           │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Execution Engine Layer                                      │
│  ├─ SpringAiToolCallingEngine (V1)                         │
│  ├─ Future: AgentScopeEngine (preserve native loop)        │
│  ├─ Future: GraphEngine                                    │
│  └─ Future: EmbabelEngine                                  │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ Provider Layer                                              │
│  ├─ ModelProvider (Spring AI, AgentScope, etc.)           │
│  ├─ ToolProvider (Spring AI ToolCallback, etc.)           │
│  ├─ Retriever / Reranker                                   │
│  ├─ MemoryProvider (ChatMemory)                            │
│  └─ SandboxProvider (future)                               │
└─────────────────────────────────────────────────────────────┘
```

**Side Concerns:**

```
Observability & TestKit
    ├─ ExecutionLedger events
    ├─ Metrics
    └─ Evaluation

Spring Boot Integration
    ├─ AutoConfiguration
    ├─ Starters
    └─ Health / Actuator
```

---

## 20. Dependency Ordering

### 20.1 Completed Milestones (Verified)

```
M1: Incident Agent MVP ✅
    ↓
M2: Session & Multi-Turn ✅
    ↓
M5: Durable Suspension/Recovery ✅
    ↓
M6: Durable Execution Kernel ✅
    ├─ T1: ExecutionLedger ✅
    ├─ T2: Event Dispatch ✅
    ├─ T3: Tool Identity ✅
    ├─ T4: Invocation Intent ✅
    ├─ T5: Recovery Classification ✅
    └─ T6: Architecture Gates ✅
```

### 20.2 Next Subsystems (Dependency Order)

```
M7: Recovery Control Plane (NEXT)
    ├─ Process Discovery/Enumeration
    ├─ Operator-Driven Resumption
    ├─ Lifecycle Commands
    └─ Operations API
    │
    └─ Depends on: M6 CheckpointStore ✅
    └─ Depends on: M6 AgentProcess ✅
    └─ Enables: External dashboards/CLI tools
    ↓
M7.5: Spring Boot Integration (parallel with M7)
    ├─ AutoConfiguration
    ├─ Starters
    ├─ Health Indicators
    └─ Actuator Endpoints
    ↓
M8: Provider Abstractions (after M7)
    ├─ MCP Integration
    ├─ Tool Ecosystem
    ├─ Multi-provider support
    └─ Plugin system
    ↓
M9+: Advanced Features (after M8)
    ├─ Long-running process progress (if needed)
    ├─ Multi-agent coordination
    ├─ Workflow primitives
    └─ GraphRAG
```

**Critical Path:**

```
M7 Control Plane → M7.5 Spring Boot → M8 Providers → M9+ Advanced
```

**Why This Order:**

1. **M7 first:** No way for operators to resume processes outside application code (gap identified)
2. **Spring Boot parallel:** Can develop alongside M7, no hard dependency
3. **M8 providers after M7:** Operations API enables better provider management
4. **M9+ deferred:** Long-running features need real consumer validation first

---

## 21. Hard-Stop Findings

### 21.1 No Architecture Blockers Found

**M6 Remains Valid:**

- ✅ Checkpoint-bounded recovery is correct design
- ✅ Fresh context semantics already correct
- ✅ Finer-grained recovery than LongHorizon (tool-level vs round-level)
- ✅ No conflicting authorities

**No Breaking Changes Required:**

- ✅ M6 public API remains frozen
- ✅ CheckpointStore remains authority
- ✅ AgentProcess scope remains valid
- ✅ ExecutionLedger semantics correct

### 21.2 Confirmed Gaps (NOT Blockers)

**Missing Infrastructure (M7):**

- Process discovery/enumeration
- Operator-driven resumption
- Lifecycle supervision
- Operations API

**These are NEW subsystems, not M6 fixes.**

### 21.3 Deferred Decisions (M8+)

**DO NOT ADD in M7:**

- Process-progress authority (task_state equivalent)
- Verification framework
- Manager/Executor/Auditor roles
- Workspace integrity checks
- Round-level abstractions

**Wait for real consumer before adding.**

---

## 22. Recommended Next Implementation Track

### 22.1 M7 Recovery Control Plane (NEXT)

**Goal:** Enable operators to discover, resume, and manage suspended processes.

**Estimated Duration:** 3-4 weeks

**Phases:**

1. **Phase 1: Process Discovery (1 week)**
   - ProcessControlPlane interface
   - SuspendedProcessDescriptor
   - ProcessQuery
   - Discovery from CheckpointStore

2. **Phase 2: Operator Resumption (1 week)**
   - resumeProcess() implementation
   - RuntimeBindingResolver integration
   - Cross-instance resume

3. **Phase 3: Lifecycle Commands (0.5 week)**
   - cancelProcess() implementation
   - Status updates

4. **Phase 4: Operations API (0.5 week)**
   - REST endpoints (for future dashboard)
   - CLI tool (for operators)

5. **Phase 5: Testing & Docs (1 week)**
   - Scenario tests
   - Architecture tests
   - User guide
   - Example

**Acceptance Criteria:**

- [ ] Operators can list all suspended processes
- [ ] Operators can resume any suspended process by processId
- [ ] Operators can cancel suspended processes
- [ ] Works across JVM restarts
- [ ] Works across application instances
- [ ] Architecture tests protect M6 boundaries
- [ ] User guide with examples
- [ ] `./mvnw clean verify` passes

**M7 Does NOT Include:**

- ❌ Process-progress tracking
- ❌ Verification framework
- ❌ Task decomposition
- ❌ Web dashboard (API only)

---

## 23. Final Decision

**GO — RECOVERY CONTROL PLANE SHOULD PRECEDE HARNESS LAYER**

### 23.1 Rationale

1. **Different Problem Domains:**
   - LongHorizon: Long-horizon computer-use with adversarial verification
   - Arctra: Enterprise agent engineering runtime with multi-engine support

2. **M6 Covers Core Execution:**
   - Checkpoint-bounded recovery ✅
   - Tool-invocation-level recovery (finer than LH) ✅
   - Fresh context semantics ✅
   - Execution history ✅

3. **Missing Infrastructure, Not Execution:**
   - Process discovery ❌ → **M7**
   - Operator resumption ❌ → **M7**
   - Lifecycle supervision ❌ → **M7**

4. **Harness Layer Premature:**
   - No V1 consumer for task-level progress tracking
   - Manager/Executor/Auditor = application pattern, not framework
   - Verification = optional application concern
   - Long-running features need real validation

### 23.2 What Arctra Learns from LongHorizon

**Key Architectural Lessons:**

1. **Model context is input, not authority** ✅ Already correct
2. **Backend adapter preserves native loops** → Apply to future engines
3. **Supervision separate from execution** → M7 Control Plane
4. **Verification as separate concern** → Document as pattern
5. **Explicit progress boundaries** → Defer until proven need

**What Arctra Should NOT Copy:**

1. Round-level recovery (too coarse)
2. Harness-enforced completion guards (too opinionated)
3. Manager/Executor/Auditor framework roles
4. Workspace integrity checks (computer-use specific)
5. Task state as framework authority (premature)

### 23.3 Immediate Next Steps

1. **Create M7 Recovery Control Plane Implementation Plan**
   - Detailed task breakdown
   - Architecture decision records
   - Public API design review

2. **Update TASKS.md**
   - Add M7-T1 through M7-T5 tasks
   - Mark M6 as CLOSED
   - Update CURRENT-STATE.md

3. **Architecture Review**
   - Present M7 gate decision to stakeholders
   - Confirm M7 public API before implementation
   - Update ARCHITECTURE-V7.md if needed

4. **Begin M7 Implementation**
   - Start with Phase 1 (Process Discovery)
   - Parallel track: Spring Boot integration planning

---

## 24. Conclusion

**LongHorizon-Harness is an excellent reference for long-horizon computer-use agent execution**, but Arctra operates in a different domain (enterprise agent engineering runtime) and at a different semantic level (multi-engine execution platform vs task coordination harness).

**Arctra M6 already provides stronger execution durability guarantees than LongHorizon** (tool-invocation-level recovery vs round-level recovery), and correctly implements fresh-context semantics.

**The missing piece is NOT a harness layer, but infrastructure:** Process discovery, operator-driven resumption, and lifecycle supervision. This is **M7 Recovery Control Plane**.

**Process-progress authority (task_state equivalent) and verification framework should be deferred to M8+** when long-running enterprise tasks prove the need. Do not add abstractions before real consumers exist.

**Manager/Executor/Auditor pattern is application-level, not framework responsibility.** Document as design pattern for applications that need multi-round verification workflows.

**Next action:** Implement M7 Recovery Control Plane to close the infrastructure gap identified in this gate analysis.

---

## ADDENDUM: REACT EXECUTION PATH LEARNING / EXECUTION CACHE

**Date:** 2026-09-20  
**Status:** ARCHITECTURE ANALYSIS (DO NOT IMPLEMENT)

---

### 1. Problem Statement

**Current Arctra Execution Model:**

Every agent execution triggers full ReAct reasoning loop:

```
User Request
    ↓
ChatModel reasoning (LLM inference)
    ↓
Tool call generation
    ↓
Governance evaluation
    ↓
Tool execution
    ↓
Result observation
    ↓
ChatModel reasoning (next step)
    ↓
...repeat until completion
```

**Problem:**

For **recurring task patterns**, Arctra repeatedly pays LLM inference cost for structurally identical execution paths.

**Example:**

```
Request 1: "查询客户 A 的逾期订单"
→ ReAct: getCustomer("A") → getOrders(customerId) → findOverdue(orders)
→ Cost: 3 LLM calls + tool execution

Request 2: "查询客户 B 的逾期订单"  (SAME STRUCTURE, different customer)
→ ReAct: getCustomer("B") → getOrders(customerId) → findOverdue(orders)
→ Cost: 3 LLM calls + tool execution  (REDUNDANT REASONING)
```

**Opportunity:**

If Request 2 could **recognize and reuse** the verified execution structure from Request 1, Arctra could:

- Reduce LLM token usage
- Reduce latency
- Increase determinism
- Preserve verification/governance semantics

**Key Insight:**

> **ReAct reasoning discovers reusable execution structure.**  
> **Once verified, structure can be cached and parameterized.**  
> **Future compatible requests can execute cached paths directly.**

---

### 2. Why Ordinary Prompt Caching Is Insufficient

**LLM Provider Prompt Caching (e.g., Anthropic/OpenAI):**

- Caches **input prefixes** (system prompt + conversation prefix)
- Reduces **input token cost** for repeated context
- Does NOT cache **execution decisions** or **tool sequences**
- Does NOT enable **zero-LLM-call execution**

**Example:**

```
Request 1: "查询客户 A 的逾期订单"
→ System prompt + conversation cached
→ Still requires LLM to reason: which tools? which order?

Request 2: "查询客户 B 的逾期订单"
→ System prompt + conversation cache HIT
→ STILL requires LLM to reason: which tools? which order?
→ Cost reduction: input tokens only
→ NOT eliminated: reasoning cost
```

**Semantic Caching (e.g., Vector Similarity):**

- Caches **response outputs** for semantically similar inputs
- Matches via embedding similarity
- Returns **cached response text**
- Does NOT support **parameterization** (cannot bind new customer ID)
- Does NOT support **dynamic binding** (customer A's response ≠ customer B's response)

**Example:**

```
Request 1: "查询客户 A 的逾期订单"
→ Response: "客户 A has 3 overdue orders..."
→ Cache: embedding(Request 1) → Response 1

Request 2: "查询客户 B 的逾期订单"
→ Embedding similarity: HIGH
→ Cache HIT: returns "客户 A has 3 overdue orders..."  ← WRONG ANSWER
```

**What Execution Path Caching Provides:**

- Caches **verified execution structure**, not responses
- Supports **parameterization** (bind new input values)
- Executes **cached path** with current tool implementations
- Preserves **governance** (re-evaluates current policy)
- Enables **zero-reasoning execution** for compatible requests

**Conceptual difference:**

| Caching Type | What's Cached | Reusability | Parameterization | Governance |
|--------------|---------------|-------------|------------------|------------|
| **Prompt Cache** | Input prefix | Reduces input tokens | N/A | N/A |
| **Semantic Cache** | Response text | Similar inputs → same response | ❌ NO | Bypassed |
| **Execution Path Cache** | Verified execution structure | Compatible requests → reuse structure | ✅ YES | Re-evaluated |

---

### 3. What Should Be Cached

**Reusable Execution Artifact:** `ExecutablePath`

**Conceptual Structure:**

```java
/**
 * Reusable execution path derived from verified ReAct execution.
 * 
 * Contains ONLY externally meaningful execution structure.
 * Does NOT contain chain-of-thought, reasoning traces, or model internals.
 */
public record ExecutablePath(
    // Identity
    String pathId,
    String pathVersion,
    
    // Applicability
    String intentSignature,  // What task pattern this applies to
    InputSchema inputSchema,  // Required input structure
    
    // Capabilities
    Set<String> requiredTools,  // Tool names + versions
    Set<String> requiredCapabilities,
    
    // Execution Structure
    List<ExecutionStep> steps,  // Ordered execution steps
    
    // Verification
    VerificationContract verification,
    
    // Governance
    GovernanceRequirements governance,
    
    // Provenance
    String derivedFrom,  // Original processId
    Instant learnedAt,
    String learnedBy,  // Agent definition version
    
    // Lifecycle
    PathStatus status,  // DISCOVERED / VERIFIED / ACTIVE / INVALIDATED
    List<InvalidationRule> invalidationRules
) {}

/**
 * One step in execution path.
 */
public record ExecutionStep(
    int stepIndex,
    StepType type,  // TOOL_CALL / DECISION / VERIFICATION / TRANSFORM
    
    // Tool execution (for TOOL_CALL steps)
    @Nullable String toolName,
    @Nullable ArgumentBinding arguments,  // Parameterized bindings
    
    // Dynamic reasoning (for DECISION steps)
    @Nullable DecisionContract decision,
    
    // Dependencies
    List<Integer> dependsOn,  // Which prior steps this depends on
    
    // Preconditions
    @Nullable Condition precondition,
    
    // Postconditions
    @Nullable Condition postcondition
) {}

/**
 * Argument binding (supports parameterization).
 */
public sealed interface ArgumentBinding permits
    LiteralBinding,      // Static value
    InputBinding,        // From request input
    StepOutputBinding,   // From previous step output
    EnvironmentBinding   // From execution environment
{}
```

**Key Principles:**

1. **Structure, not values:** Path contains execution structure, not runtime values
2. **Parameterized:** Arguments bound dynamically at execution time
3. **Verifiable:** Each step has preconditions/postconditions
4. **Governable:** Each step subject to current governance
5. **Durable:** Persisted independently of process state

---

### 4. What Must Never Be Cached

**PROHIBITED Content:**

```
❌ Chain-of-thought text
❌ Model reasoning traces
❌ Hidden reasoning steps
❌ Scratchpad content
❌ Natural-language reasoning
❌ Model internal representations
❌ User credentials / tokens / secrets
❌ Authorization decisions
❌ Approval grants
❌ Tenant-specific data
❌ PII (Personally Identifiable Information)
❌ Time-sensitive values
❌ Non-parameterized user inputs
```

**Why these are prohibited:**

| Prohibited Item | Risk | Mitigation |
|-----------------|------|------------|
| **Chain-of-thought** | Privacy leak, noise | Cache structure only |
| **Credentials** | Security breach | Never persist secrets |
| **Authorization** | Security bypass | Re-evaluate governance |
| **Approval grants** | Audit violation | Re-request approval |
| **PII** | Privacy violation | Parameterize inputs |
| **Time-sensitive values** | Stale data | Bind at runtime |

**Verification Principle:**

> **If removing this from the path would make it unsafe or incorrect, it should be RE-EVALUATED at runtime, not cached.**

---

### 5. ReAct-as-Discovery Model

**Conceptual Evolution:**

```
ReAct Loop (Current)
    ├── Discover execution structure (reasoning cost)
    └── Execute structure (tool cost)
        ↓
ReAct as Path Discovery (Future)
    ├── Discover + Execute (first time)
    ├── Verify execution (application-driven)
    ├── Derive reusable path (generalization)
    ├── Persist path (ExecutionPathStore)
    └── Reuse path (subsequent compatible requests)
```

**Lifecycle Diagram:**

```
UNKNOWN TASK → ReAct Discovery → Execution Trace → Verification
    → Path Derivation → Path Persistence → KNOWN TASK PATTERN
    → Path Matching → Cached Path Execution → Verify → Success/Fallback
```

**Key Architectural Principle:**

> **ReAct is NOT replaced. ReAct becomes the DISCOVERY MECHANISM for execution structure. Cached paths are OPTIMIZATIONS, not replacements.**

---

### 6. Cached Execution Model

**Strategy Selection:**

```
AgentRequest
    ↓
Path Resolution (NEW)
    ├─ Match intent + schema + capabilities
    └─ PathMatch | NoMatch
        ↓
    PathMatch? ─YES→ Cached Path Execution ─→ Verify ─→ Success?
        │                                              ↓
        NO                                            NO
        ↓                                              ↓
    ReAct Execution                         Invalidate + Fallback to ReAct
```

**Critical Guarantees:**

1. **Governance ALWAYS re-evaluated** (no bypass)
2. **M6 durability preserved** (operationId, checkpoint)
3. **Preconditions/postconditions checked**
4. **Fallback on any failure**
5. **Verification enforced**

---

### 7. Hybrid Execution Model

**Not all steps need to be deterministic.**

**Example: Incident Analysis Path (Hybrid)**

```
Step 1: queryLogs (deterministic tool call)
    ↓
Step 2: LLM semantic classification (reasoning slot)
    ↓
Step 3: queryMetrics (deterministic tool call)
    ↓
Step 4: generateReport (deterministic tool call)

Cost: 1 LLM call instead of 4-5 ReAct iterations
```

**Key Insight:**

> **Paths can contain "reasoning slots" for non-deterministic steps. This REDUCES reasoning without requiring ZERO reasoning.**

---

### 8. Path Matching Contract

**Safety Dimensions:**

```
Path Match Requires:
    ✓ Intent compatibility
    ✓ Input schema compatibility
    ✓ Required tools available
    ✓ Tool schema/version compatible
    ✓ Governance context compatible
    ✓ Path not invalidated
    ✓ Preconditions satisfied
```

**DO NOT rely solely on semantic embedding similarity.**

---

### 9. Governance Interaction

**CRITICAL INVARIANT:**

```
PATH CACHE HIT ≠ ACTION AUTHORIZED
```

**Every side-effecting operation MUST re-evaluate current governance:**

```java
// CORRECT: Re-evaluate governance
for (ExecutionStep step : path.steps()) {
    GovernanceDecision decision = governancePolicy.evaluate(
        step.toolName(), 
        resolveArguments(step.arguments()), 
        context  // CURRENT context
    );
    
    if (decision == DENY) throw new GovernanceDeniedException();
    if (decision == REQUIRE_APPROVAL) return suspendForApproval();
    
    // Only execute if ALLOW
    executeTool(step.toolName(), args, context);
}

// WRONG: Assume cached path implies authorization
executeTool(step.toolName(), args);  // ❌ BYPASSES GOVERNANCE
```

**Principle:**

> **A previously allowed action does NOT imply currently allowed action. Governance MUST always re-run.**

---

### 10. M6 Durability Interaction

**Cached path execution MUST preserve M6 semantics:**

| M6 Semantic | Preservation Mechanism |
|-------------|------------------------|
| **operationId** | Generate per path step: `pathId + ":" + stepIndex + ":" + attemptId` |
| **toolCallId** | Maintain Spring AI protocol where relevant |
| **attemptId** | Track via InvocationStateStore (HITL paths) |
| **executionEpoch** | Detect crash/restart boundaries |
| **CHECK A/B** | Duplicate detection still applies |
| **T5 Recovery** | Classification for uncertain attempts |
| **RUNNABLE / WAITING_FOR_SIGNAL** | Suspend on REQUIRE_APPROVAL |
| **DurabilityMode** | Respect EPHEMERAL vs DURABLE |

**Principle:**

> **Cached execution is an execution PLANNING optimization. It is NOT a replacement recovery mechanism.**

---

### 11. Verification Requirement

**Connection to LongHorizon verified-progress model:**

```
Path Lifecycle States:
    DISCOVERED (derived from trace)
    → EXECUTED (ran successfully once)
    → VERIFIED (independent verification passed)
    → REUSABLE (safe for future use)
    → INVALIDATED (no longer safe)
```

**Verification Contract:**

```java
public record VerificationContract(
    VerificationType type,  // NONE / SCHEMA / BUSINESS_RULE / INDEPENDENT
    @Nullable VerificationLogic logic,
    boolean requiredBeforeReuse
) {}
```

**Recommendation:**

- **V1:** Optional verification (application-driven)
- **Future:** Consider requiring verification before path becomes REUSABLE

---

### 12. Failure/Fallback Behavior

**Failure Scenarios:**

| Failure Reason | Detection | Response |
|----------------|-----------|----------|
| **Tool unavailable** | Pre-execution check | Fallback to ReAct immediately |
| **Schema mismatch** | Argument binding failure | Fallback to ReAct |
| **Precondition failed** | Condition evaluation | Fallback to ReAct |
| **Tool execution failed** | Tool result error | Fallback to ReAct |
| **Postcondition failed** | Condition evaluation | Fallback to ReAct |
| **Verification failed** | Verification check | Fallback to ReAct |
| **Governance DENY** | Policy evaluation | Throw exception (no fallback) |

**Fallback Protocol:**

```java
AgentResult executeCachedPath(ExecutablePath path, ...) {
    try {
        // Attempt cached path execution
        return executePathSteps(path, ...);
    } catch (PathExecutionException e) {
        // Mark path as suspect
        pathStore.markSuspect(path.pathId(), e.reason());
        
        // Fallback to ReAct
        return executeReAct(request, context);
    }
}
```

**After fallback:**

1. ReAct discovers corrected structure
2. Verify new execution
3. Update/supersede cached path
4. Mark old path as INVALIDATED

---

### 13. Invalidation Model

**Invalidation Triggers:**

| Dimension | Invalidation Rule |
|-----------|-------------------|
| **Tool definition version** | Tool schema fingerprint changed |
| **Tool implementation** | Tool behavior version changed |
| **Agent definition** | Prompt/instruction version changed |
| **Model family** | Model capability change |
| **Business policy** | Policy version changed |
| **Governance** | Authorization rules changed |
| **Verification** | Verification contract changed |

**DO NOT use simple TTL.**

**Invalidation Protocol:**

```java
public interface PathInvalidationStrategy {
    boolean shouldInvalidate(
        ExecutablePath path,
        ToolRegistry currentTools,
        AgentDefinition currentDefinition,
        GovernancePolicy currentPolicy
    );
}

// Example: Tool schema fingerprint strategy
public class ToolSchemaFingerprintStrategy implements PathInvalidationStrategy {
    public boolean shouldInvalidate(ExecutablePath path, ToolRegistry tools, ...) {
        for (String toolName : path.requiredTools()) {
            String currentFingerprint = tools.getSchemaFingerprint(toolName);
            String pathFingerprint = path.getToolFingerprint(toolName);
            
            if (!currentFingerprint.equals(pathFingerprint)) {
                return true;  // Tool schema changed → invalidate
            }
        }
        return false;
    }
}
```

---

### 14. Path Identity

**Identity Derivation:**

```java
/**
 * Path identity (NOT just prompt hash or embedding key).
 */
public record PathIdentity(
    String intentSignature,      // Structural task pattern
    String inputSchemaHash,      // Required input structure
    String toolCapabilityHash,   // Required tool versions
    String verificationContractHash
) {
    public String toPathId() {
        return "path:" + hash(intentSignature, inputSchemaHash, 
                              toolCapabilityHash, verificationContractHash);
    }
}
```

**Intent Signature:**

- NOT user prompt text
- NOT embedding vector
- **Structural pattern** of task (e.g., "query-entity-then-related-entities-then-report")

**Example:**

```
User Prompt 1: "查询客户 A 的逾期订单"
User Prompt 2: "查询客户 B 的逾期订单"

Intent Signature: "query:customer:orders:filter:overdue"
    ↓
Same PathIdentity (different input bindings)
```

---

### 15. Path Parameterization

**Separate STRUCTURE from RUNTIME VALUES:**

```java
// WRONG: Hard-coded values
ExecutionStep {
    tool: "getCustomer",
    arguments: {"customerId": "A"}  // ❌ Not reusable
}

// CORRECT: Parameterized bindings
ExecutionStep {
    tool: "getCustomer",
    arguments: {
        "customerId": InputBinding("customerId")  // ✅ Bind from request
    }
}
```

**Binding Types:**

| Binding Type | Source | Example |
|--------------|--------|---------|
| **LiteralBinding** | Static constant | `{"status": "active"}` |
| **InputBinding** | Request parameter | `{"customerId": $input.customerId}` |
| **StepOutputBinding** | Previous step output | `{"orders": $step1.result.orders}` |
| **EnvironmentBinding** | Execution context | `{"tenant": $context.tenantId}` |

**Prohibited Bindings:**

```
❌ Credentials / tokens
❌ Authorization decisions
❌ PII without explicit consent
❌ Time-sensitive values (use current time at execution)
```

---

### 16. Non-Deterministic Steps

**Hybrid Path with Reasoning Slots:**

```java
public sealed interface ExecutionStep permits
    ToolCallStep,      // Deterministic tool execution
    DecisionStep,      // LLM reasoning slot
    TransformStep,     // Deterministic transformation
    VerificationStep   // Verification check
{}

public record DecisionStep(
    String decisionType,  // SEMANTIC_CLASSIFICATION / EXTRACTION / JUDGMENT
    DecisionContract contract,
    InputBinding input,
    OutputBinding output
) implements ExecutionStep {}

public record DecisionContract(
    String prompt,           // Decision prompt template
    Set<String> allowedValues,  // Constrained output space
    @Nullable ValidationRule validation
) {}
```

**Example:**

```java
// Step 2: Semantic classification (requires LLM)
DecisionStep {
    decisionType: "SEMANTIC_CLASSIFICATION",
    contract: {
        prompt: "Classify error type from logs: ${input}",
        allowedValues: ["NETWORK", "DATABASE", "TIMEOUT", "OTHER"],
        validation: "Must return one allowed value"
    },
    input: StepOutputBinding("step1.logs"),
    output: "errorCategory"
}
```

**Execution:**

```java
// Execute decision step (ONE LLM call)
String errorCategory = chatModel.call(
    buildDecisionPrompt(step.contract(), bindings)
);

// Validate output
if (!step.contract().allowedValues().contains(errorCategory)) {
    throw new DecisionValidationException();
}

// Store in bindings
bindings.put(step.output(), errorCategory);
```

---

### 17. Execution Compilation Concept

**Metaphor Analysis:**

```
Source Code (user request)
    ↓ COMPILATION
Executable Binary (execution plan)
    ↓ RUNTIME
Execution Result
```

**Applied to Agents:**

```
ReAct Reasoning (dynamic discovery)
    ↓ "COMPILATION"
Verified Execution Path (reusable structure)
    ↓ RUNTIME
Cached Execution Result
```

**Is "compilation" a useful metaphor?**

**Arguments FOR:**

- Separates discovery (expensive) from execution (cheap)
- Familiar concept for developers
- Captures structural transformation

**Arguments AGAINST:**

- Not true compilation (no bytecode, no type checking)
- May imply false guarantees (correctness, completeness)
- Confuses with workflow compilation (different semantic level)

**Recommendation:**

Use **"Path Derivation"** or **"Structure Extraction"**, NOT "compilation."

Avoid implying compiler-level guarantees that don't exist.

---

### 18. Relationship to Workflow

**Should learned paths and authored workflows share execution representation?**

**Possible Unified Model:**

```java
public sealed interface ExecutionPlan permits
    ReactPlan,              // Dynamic reasoning (current)
    LearnedPath,            // Derived from verified execution
    AuthoredWorkflow        // Explicitly defined by developer
{}

public interface UnifiedExecutor {
    AgentResult execute(ExecutionPlan plan, AgentRequest request, AgentExecutionContext context);
}
```

**Arguments FOR Unification:**

1. **Single execution engine** (avoid duplicate logic)
2. **Smooth evolution** (ReAct → Learned → Workflow)
3. **Consistent durability** (M6 semantics for all)
4. **Shared governance** (same policy evaluation)

**Arguments AGAINST Unification:**

1. **Different semantics** (learned paths are optimizations; workflows are authoritative)
2. **Different lifecycle** (paths invalidated automatically; workflows versioned explicitly)
3. **Different verification** (paths may need independent verification; workflows assumed correct)
4. **Premature abstraction** (no real consumer yet)

**Recommendation:**

**DEFER unification to M9+.**

Start with separate `LearnedPathExecutor` and `WorkflowExecutor`.

If execution logic converges naturally, unify later.

Do NOT force unification prematurely.

---

### 19. Relationship to Process / Harness

**Who owns path selection?**

**Possible Layering:**

```
Application Layer
    ↓
M8+ Harness / Process Layer (hypothetical)
    ├─ Process-level decisions
    ├─ Task decomposition
    └─ Path selection strategy
        ↓
M7 Path Resolution Layer (NEW)
    ├─ ExecutionPathStore query
    ├─ Path matching
    └─ Path validation
        ↓
Agent Runtime (V1)
    ├─ AgentExecutionEngine
    └─ Execution strategy dispatch
        ↓
Execution Strategy
    ├─ ReactExecutor (SpringAiToolCallingEngine)
    ├─ CachedPathExecutor (NEW)
    └─ WorkflowExecutor (future)
        ↓
M6 Durable Execution Kernel (FROZEN)
    ├─ CheckpointStore
    ├─ InvocationStateStore
    └─ ExecutionLedger
```

**Responsibility Assignment:**

| Layer | Responsibility |
|-------|----------------|
| **Harness (M8+)** | "Should this task use cached path or discover fresh?" |
| **Path Resolution** | "Which cached path matches this request?" |
| **Execution Strategy** | "How to execute this specific plan type?" |
| **M6 Kernel** | "How to durably execute and recover?" |

**Current State (V1):**

- No Harness layer yet
- Path resolution would be in `AgentExecutionEngine` or new `PathResolutionLayer`
- Execution strategy dispatch in `ExecutionFlowCoordinator` (exists, could evolve)

---

### 20. Cache Authority Analysis

**Is ExecutionPathStore a new authority?**

**Compare with existing authorities:**

| Authority | What It Knows | Recovery Role | Lifecycle |
|-----------|---------------|---------------|-----------|
| **CheckpointStore** | Current continuation state | Primary recovery authority | Per-process |
| **InvocationStateStore** | Physical attempt truth | Duplicate detection | Per-invocation |
| **ExecutionLedger** | Historical events | Audit/query, NOT recovery | Append-only |
| **ChatMemory** | Conversation history | Execution input | Session-scoped |
| **Evidence** | Execution outputs | Proof/content | Transient |
| **ExecutionPathStore (NEW)** | **Known reusable execution structures** | **Optimization, NOT recovery** | **Versioned** |

**ExecutionPathStore Authority:**

```
"Known reusable execution structures for compatible task patterns"
```

**NOT:**

```
❌ Current process state
❌ Current recovery position
❌ Physical invocation truth
❌ Conversation truth
❌ Authorization decisions
```

**Conclusion:**

**YES, ExecutionPathStore is a NEW authority, but with LIMITED scope:**

- Authority over: reusable execution knowledge
- NOT authority over: process state, recovery, authorization

**This is legitimate.**

---

### 21. Multi-Tenancy / Security

**Path Reuse Boundaries:**

| Boundary | Reuse Scope | Rationale |
|----------|-------------|-----------|
| **Global** | Across all tenants/users | ❌ UNSAFE (leaks tenant logic) |
| **Per-tenant** | Within one tenant | ⚠️ POSSIBLE (with careful validation) |
| **Per-agent** | Within one agent definition | ✅ SAFE (agent-specific patterns) |
| **Per-user** | Within one user | ✅ SAFE (user-specific patterns) |
| **Per-environment** | Within one environment (dev/prod) | ✅ SAFE (env-specific tools) |

**Security Invariants:**

```
❌ NEVER cache: credentials, tokens, secrets
❌ NEVER cache: authorization decisions
❌ NEVER cache: approval grants
❌ NEVER cache: PII without consent
✓ Parameterize: tenant-specific data
✓ Re-evaluate: governance for current context
✓ Validate: path applies to current security context
```

**Recommended V1 Scope:**

```
Path reuse: per-agent + per-tenant
Cross-tenant reuse: PROHIBITED
```

---

### 22. Observability

**Execution Strategy Tracking:**

```java
public enum ExecutionStrategy {
    REACT_DISCOVERY,     // Full ReAct reasoning
    CACHED_PATH,         // Cached path execution
    HYBRID_PATH,         // Cached path with reasoning slots
    WORKFLOW             // Deterministic workflow (future)
}

public enum PathExecutionOutcome {
    CACHE_HIT,           // Path matched and executed successfully
    CACHE_MISS,          // No matching path found
    CACHE_INVALID,       // Path found but invalidated
    PATH_FAILED,         // Path execution failed
    FALLBACK_TO_REACT    // Fallback triggered
}
```

**ExecutionLedger Events:**

```java
// Path match attempt
ExecutionRecord(
    eventType: "PATH_MATCH_ATTEMPTED",
    payload: {
        "intentSignature": "query:customer:orders:overdue",
        "pathId": "path:abc123",
        "matchResult": "HIT" | "MISS" | "INVALID"
    }
)

// Path execution
ExecutionRecord(
    eventType: "CACHED_PATH_EXECUTED",
    payload: {
        "pathId": "path:abc123",
        "pathVersion": "v2",
        "stepsExecuted": 4,
        "llmCallsRequired": 1,  // For hybrid paths
        "outcome": "SUCCESS" | "FAILED" | "FALLBACK"
    }
)

// Path invalidation
ExecutionRecord(
    eventType: "PATH_INVALIDATED",
    payload: {
        "pathId": "path:abc123",
        "reason": "TOOL_SCHEMA_CHANGED",
        "invalidatedTool": "getCustomer"
    }
)
```

**Key Principle:**

> **Execution strategy and path usage MUST be observable, but NOT become recovery authority.**

---

### 23. Economic Value

**Expected Effects:**

| Dimension | Direction | Magnitude (estimated) |
|-----------|-----------|----------------------|
| **LLM token usage** | ↓ Decrease | 50-90% for cached paths |
| **Latency** | ↓ Decrease | 60-80% for cached paths |
| **Cost** | ↓ Decrease | Proportional to token reduction |
| **Determinism** | ↑ Increase | Higher for frequently used patterns |
| **Reliability** | ↑ Increase | Fewer reasoning failures |
| **Auditability** | ↑ Increase | Explicit execution structure |
| **Repeatability** | ↑ Increase | Same path → same behavior |
| **Throughput** | ↑ Increase | More requests per second |

**Measurement Required:**

- Benchmark: ReAct vs Cached Path execution
- Metrics: token count, latency, success rate
- Scenarios: high-frequency patterns (customer queries, incident analysis, report generation)

**Do NOT fabricate benchmarks.**

**Expected Direction:** Significant cost/latency reduction for recurring patterns.

---

### 24. Risks

**Identified Risks:**

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Unsafe path reuse** | Incorrect execution | Strict matching contract + preconditions |
| **Stale business logic** | Outdated behavior | Version-based invalidation |
| **Stale tool schema** | Execution failure | Tool fingerprint tracking |
| **Incorrect parameter binding** | Wrong data | Strong typing + validation |
| **Governance bypass** | Security breach | ALWAYS re-evaluate governance |
| **Cross-tenant leakage** | Data breach | Per-tenant path isolation |
| **Over-generalization** | Incorrect matches | Conservative matching |
| **Under-generalization** | Low cache hit rate | Path derivation tuning |
| **Cache poisoning** | Malicious paths | Verification before reuse |
| **Verification weakness** | Undetected failures | Independent verification (future) |
| **Silent fallback loops** | Resource exhaustion | Fallback counter + circuit breaker |
| **Path explosion** | Storage/query cost | Path pruning + LRU eviction |

**Risk Mitigation Strategy:**

1. **Conservative matching** (better miss than wrong match)
2. **Always re-evaluate governance** (no bypass)
3. **Precondition/postcondition guards** (fail fast)
4. **Automatic fallback** (never silent failure)
5. **Verification before reuse** (trust through proof)
6. **Per-tenant isolation** (no cross-tenant reuse in V1)
7. **Version-based invalidation** (explicit schema tracking)

---

### 25. Industry Concept Comparison

**Related Concepts:**

| Concept | Similarity | Difference |
|---------|------------|------------|
| **Semantic Caching** | Reuse for similar inputs | Arctra: structure, not response |
| **Prompt Caching** | Reduce input cost | Arctra: reduce reasoning cost |
| **Plan Caching** | Cache execution plan | Arctra: plus parameterization + verification |
| **Workflow Compilation** | Static structure | Arctra: learned, not authored |
| **Trace Replay** | Replay execution | Arctra: generalized + parameterized |
| **Program Synthesis** | Generate from traces | Arctra: extract structure, not synthesize |
| **Agent Memory** | Learn from experience | Arctra: specific to execution structure |
| **Skill Learning** | Acquire capabilities | Arctra: specific to task patterns |
| **Procedure Memory** | Recall procedures | Arctra: executable procedures |
| **Tool Trajectory Reuse** | Similar concept | Arctra: adds verification + governance |

**Key Distinction:**

> **Arctra's concept is most similar to "verified tool trajectory reuse with governance preservation."**

**Novel Aspects:**

1. **Governance re-evaluation** (no security bypass)
2. **M6 durability preservation** (no parallel recovery system)
3. **Hybrid paths** (deterministic + reasoning slots)
4. **Verification contract** (trust through proof)
5. **Version-based invalidation** (not TTL)

---

### 26. Minimum Viable V1

**If justified, smallest useful version:**

```
ExecutionPathCache V1:
    ✓ Single-agent scope (no cross-agent reuse)
    ✓ Single-tenant scope (no cross-tenant reuse)
    ✓ In-memory store (no distributed cache)
    ✓ Manual path derivation (no automatic learning)
    ✓ Exact/parameterized match only (no fuzzy matching)
    ✓ Synchronous execution (no background optimization)
    ✓ Simple invalidation (version-based only)
    ✓ Optional verification (application-driven)
    ✗ NO public plugin SPI
    ✗ NO cross-tenant sharing
    ✗ NO automatic path synthesis
    ✗ NO vector embedding matching
    ✗ NO distributed coordination
```

**V1 Scope:**

```java
// V1 API (minimal)
public interface ExecutionPathStore {
    Optional<ExecutablePath> findPath(PathMatchCriteria criteria);
    void storePath(ExecutablePath path);
    void invalidatePath(String pathId, String reason);
}

public interface PathMatchCriteria {
    String intentSignature();
    Map<String, Class<?>> inputSchema();
    Set<String> requiredTools();
}

// V1 Executor (simple)
public class CachedPathExecutor {
    public AgentResult execute(ExecutablePath path, AgentRequest request, AgentExecutionContext context) {
        // 1. Bind inputs
        // 2. Execute steps with governance re-evaluation
        // 3. Verify results
        // 4. Fallback on failure
    }
}
```

**V1 Integration Point:**

```java
// In AgentExecutionEngine.execute()
public AgentResult execute(AgentDefinition definition, AgentRequest request, AgentExecutionContext context) {
    
    // NEW: Try cached path first
    Optional<ExecutablePath> cachedPath = pathStore.findPath(buildCriteria(request));
    
    if (cachedPath.isPresent()) {
        try {
            return cachedPathExecutor.execute(cachedPath.get(), request, context);
        } catch (PathExecutionException e) {
            // Fallback to ReAct
            pathStore.invalidatePath(cachedPath.get().pathId(), e.reason());
        }
    }
    
    // Existing ReAct execution
    return executeReAct(definition, request, context);
}
```

---

### 27. Deferred Capabilities

**NOT in V1 (defer to M9+):**

```
❌ Automatic path learning (manual derivation only)
❌ Semantic embedding matching (exact match only)
❌ Cross-tenant path sharing
❌ Distributed path store
❌ Background path optimization
❌ Path composition (chaining paths)
❌ Path versioning / migration
❌ Path analytics / recommendations
❌ Independent auditor for verification
❌ Graph-based path synthesis
❌ Multi-agent path coordination
❌ Public plugin SPI for path derivation
❌ Vector database integration
```

**Rationale:**

- V1 proves core concept
- Avoid premature optimization
- Learn from real usage before expanding

---

### 28. Recommendation

**Should Arctra evolve toward execution path caching?**

**YES, but NOT immediately.**

**Recommended Sequence:**

```
M7: Recovery Control Plane (NEXT)
    ↓
M7.5: Spring Boot Integration
    ↓
M8: Provider Abstractions + MCP
    ↓
M9: Evaluate Execution Path Caching
    ├─ Real usage patterns observed
    ├─ High-frequency patterns identified
    ├─ Cost/benefit validated
    └─ V1 implementation justified
```

**Why Defer to M9:**

1. **No V1 consumer yet** (Knowledge Assistant and Incident Investigator are single-execution)
2. **Need usage data** (don't know which patterns repeat)
3. **M7/M8 higher priority** (infrastructure gaps more critical)
4. **Complexity risk** (adds significant system complexity)
5. **Verification unclear** (need real scenarios to design verification contracts)

**When to Implement:**

- **After M8** (provider abstractions stable)
- **When high-frequency patterns observed** (e.g., 1000s of similar customer queries)
- **When cost justification clear** (measured token/latency savings)
- **When verification contracts understood** (real application requirements)

---

### 29. Should ReAct Become One Execution Discovery Mechanism?

**YES.**

**Current Architecture (V1):**

```
arctra-runtime-react
    ├─ SpringAiToolCallingEngine (ReAct = THE runtime)
    └─ GovernanceToolCallingAdvisor (ReAct loop)
```

**Implied Coupling:**

```
arctra-runtime-react → ReAct is runtime itself
```

**Future Architecture (M9+):**

```
arctra-runtime (execution strategies)
    ├─ ReactExecutionStrategy (discovery)
    ├─ CachedPathExecutionStrategy (reuse)
    └─ WorkflowExecutionStrategy (deterministic)
        ↓
arctra-runtime-react (ReAct implementation)
    ├─ SpringAiToolCallingEngine
    └─ GovernanceToolCallingAdvisor
```

**Conceptual Distinction:**

```
Agent Runtime (what execution means)
    ↓
Execution Strategy (how to execute)
    ↓
Reasoning / Discovery Mechanism (how to discover structure)
    ↓
Durable Execution Kernel (how to execute durably)
```

**Minimum Architecture Change Required:**

```java
// NEW: Execution strategy abstraction (M9+)
public interface ExecutionStrategy {
    AgentResult execute(AgentDefinition definition, AgentRequest request, AgentExecutionContext context);
}

// ReAct becomes ONE strategy
public class ReactExecutionStrategy implements ExecutionStrategy {
    private final SpringAiToolCallingEngine reactEngine;
    
    public AgentResult execute(...) {
        return reactEngine.execute(...);
    }
}

// Cached path as another strategy
public class CachedPathExecutionStrategy implements ExecutionStrategy {
    private final ExecutionPathStore pathStore;
    private final CachedPathExecutor executor;
    private final ReactExecutionStrategy fallback;
    
    public AgentResult execute(...) {
        Optional<ExecutablePath> path = pathStore.findPath(...);
        if (path.isPresent()) {
            try {
                return executor.execute(path.get(), ...);
            } catch (PathExecutionException e) {
                return fallback.execute(...);  // Fallback to ReAct
            }
        }
        return fallback.execute(...);
    }
}

// Runtime delegates to strategy
public class DefaultAgentRuntime implements AgentRuntime {
    private final ExecutionStrategy strategy;  // Pluggable
    
    public AgentResult execute(...) {
        return strategy.execute(...);
    }
}
```

**Key Insight:**

> **ReAct should be ONE execution discovery mechanism, not THE runtime itself.**

**Benefits:**

1. **Cleaner separation:** runtime semantics vs execution strategy vs reasoning mechanism
2. **Easier testing:** mock strategies independently
3. **Future extensibility:** add new strategies without changing runtime
4. **Better naming:** `arctra-runtime` (core) vs `arctra-execution-react` (one strategy)

**DO NOT IMPLEMENT NOW.**

This is architectural direction for M9+.

---

### 30. Final Architectural Principle

**DO NOT CACHE REASONING. CACHE VERIFIED REUSABLE EXECUTION KNOWLEDGE.**

```
ReAct explores. (Discovery)
    ↓
Verification establishes trust. (Safety)
    ↓
Executable paths capture reusable structure. (Knowledge)
    ↓
The runtime executes. (Execution)
    ↓
M6 guarantees safe durable execution. (Durability)
    ↓
The Harness decides how long-running work progresses. (Coordination)
```

**If these boundaries hold:**

> **Repeated agent reasoning can gradually become reusable execution capability instead of recurring inference cost.**

---

**End of ReAct Execution Path Learning / Execution Cache Analysis**

**Status:** ARCHITECTURE ANALYSIS COMPLETE  
**Recommendation:** DEFER TO M9+ (after M7 Control Plane + M8 Providers)  
**Next Action:** Proceed with M7 Recovery Control Plane implementation

---

**End of Architecture Gate Document**

**Status:** APPROVED FOR M7 CONTROL PLANE IMPLEMENTATION  
**Date:** 2026-09-20  
**Reviewed By:** Architecture Gate Process  
**Next Milestone:** M7 Recovery Control Plane
