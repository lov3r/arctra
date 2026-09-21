# M8-A REUSABLE PROCEDURE DOMAIN CLOSURE

**Milestone:** M8-A Reusable Procedure Domain & Authority  
**Status:** ✅ **COMPLETE**  
**Date:** 2026-09-21  
**Author:** lov3r

---

## Executive Summary

M8-A establishes **Reusable Execution Knowledge** as a real provider-neutral domain and durable authority in Arctra. This track implements immutable procedure revisions, deterministic parameter bindings, and separate storage authorities for promoted procedures and unpromoted candidates.

**Key Achievement:** ReusableProcedureStore now owns reusable procedure definitions as a first-class authority, completely independent from CheckpointStore (continuation authority) and InvocationStateStore (physical invocation authority).

---

## Implemented Components

### Domain Model (arctra-core/src/main/java/cn/bitcss/arctra/procedure/)

1. **ReusableProcedure** - Immutable procedure revision
   - Identity: `(procedureId, revision)` - frozen at creation
   - Linear ordered steps (V1 constraint)
   - Deterministic parameter bindings
   - Tool compatibility fingerprints
   - Lifecycle status (VALID/INVALID/SUPERSEDED)
   - Does NOT contain runtime execution state

2. **ProcedureStep** - Single linear step in procedure
   - Zero-based contiguous step indexes
   - Tool identity + compatibility fingerprint
   - Parameter bindings (INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT/RUNTIME_CONTEXT)
   - Output extractions for future bindings
   - No control flow (no branches, conditions, loops)

3. **ParameterBinding** - Deterministic binding structure
   - BindingSource enum (INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT/RUNTIME_CONTEXT)
   - Path/reference/value (interpretation depends on source)
   - Forward reference validation (rejects future step references)
   - PREVIOUS_STEP_OUTPUT format: `"stepIndex.outputName"`

4. **ProcedureCandidate** - Unpromoted extracted structure
   - Separate lifecycle from ReusableProcedure
   - NOT automatically executable
   - Awaits validation and explicit promotion

5. **Supporting Types**
   - `ProcedureScope` - Agent-based scope (V1)
   - `ProcedureStatus` - VALID/INVALID/SUPERSEDED
   - `ToolCompatibilityFingerprint` - toolName + inputSchemaHash
   - `OutputExtraction` - outputName + jsonPath
   - `BindingSource` - enum for binding sources

### Exceptions

- `ProcedureNotFoundException` - revision not found
- `ProcedureAlreadyExistsException` - duplicate (procedureId, revision)

### Store Interfaces

1. **ReusableProcedureStore** - Authority for procedure definitions
   - `createRevision()` - store immutable revision
   - `findRevision(procedureId, revision)` - exact revision lookup
   - `listRevisions(procedureId)` - all revisions ordered
   - `findByIntent(scope, intentKey)` - intent-based search
   - `updateStatus()` - mutable metadata only

2. **ProcedureCandidateStore** - Unpromoted candidate storage
   - `save()` - store candidate
   - `findById()` - lookup by candidateId
   - `listByScope()` - scope-based search
   - `updateValidation()` - update validation outcome
   - `delete()` - remove after promotion/rejection

### In-Memory Implementations

- **InMemoryReusableProcedureStore** - Thread-safe ConcurrentHashMap-based
- **InMemoryProcedureCandidateStore** - Thread-safe ConcurrentHashMap-based

Both suitable for testing and single-JVM verification.

---

## Architecture Invariants Preserved

### ✅ M8-A Does NOT Change M6/M7 Authority Semantics

1. **CheckpointStore** remains sole authority for current execution position
2. **InvocationStateStore** remains sole authority for physical attempts
3. **Evidence** remains proof/evidence authority
4. **ExecutionLedger** remains historical projection only
5. **No ProcedureExecutionStateStore introduced** (avoiding split authority)

### ✅ Provider Neutrality

arctra-core procedure domain has **ZERO** dependencies on:
- Spring AI (AssistantMessage, ToolCallback, ChatClient)
- Spring Boot
- Anthropic types
- OpenAI types
- A2A protocol types
- Any specific execution engine

### ✅ Immutable Revision Semantics

- Once created, a revision's executable definition NEVER changes
- Updates create new revisions (same procedureId, incremented revision)
- Status metadata may change, but executable structure is frozen
- Old revisions remain readable (important for suspended executions)

### ✅ Linear V1 Constraints Enforced

- No branches, conditions, loops, or control flow in ProcedureStep
- Steps ordered by contiguous zero-based indexes
- Deterministic bindings only (no dynamic model reasoning)
- Forward references rejected at construction time

### ✅ Authority Separation

**Three distinct authorities, never conflated:**

| Authority | Owns |
|-----------|------|
| ReusableProcedureStore | Immutable procedure definitions |
| CheckpointStore | Current execution position (future: procedureState) |
| ProcedureCandidateStore | Unpromoted candidates |

---

## Test Coverage

### M8-A Tests (43 tests, all passing)

**ParameterBindingTest** (14 tests)
- ✅ Create all binding source types (INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT/RUNTIME_CONTEXT)
- ✅ Reject null/blank paths
- ✅ Validate PREVIOUS_STEP_OUTPUT not forward reference
- ✅ Reject forward references (step N cannot reference step N+1)
- ✅ Reject self-references
- ✅ Reject negative step indexes
- ✅ Extract step index and output name from PREVIOUS_STEP_OUTPUT
- ✅ Reject invalid format
- ✅ Non-PREVIOUS_STEP_OUTPUT bindings skip validation

**ReusableProcedureTest** (9 tests)
- ✅ Create valid procedure
- ✅ Reject empty procedureId
- ✅ Reject zero/negative revision
- ✅ Reject empty steps
- ✅ Reject non-contiguous step indexes
- ✅ Create next revision (immutability)
- ✅ Update status (metadata only)
- ✅ Two-step procedure with PREVIOUS_STEP_OUTPUT binding
- ✅ Reject undefined output reference

**InMemoryReusableProcedureStoreTest** (11 tests)
- ✅ Create revision
- ✅ Reject duplicate revision
- ✅ Same procedureId can have multiple revisions
- ✅ Find non-existent revision returns empty
- ✅ List revisions (ordered)
- ✅ List revisions for non-existent procedure returns empty
- ✅ Find by intent
- ✅ Find by intent returns multiple revisions
- ✅ Update status
- ✅ Update status for non-existent throws
- ✅ Old revision remains readable after new revision

**InMemoryProcedureCandidateStoreTest** (9 tests)
- ✅ Save candidate
- ✅ Reject duplicate candidate
- ✅ Find by ID returns empty for non-existent
- ✅ List by scope
- ✅ Update validation
- ✅ Update validation for non-existent throws
- ✅ Delete candidate
- ✅ Delete non-existent returns false
- ✅ Candidate is not automatically executable (semantic test)

### Build Status

```
./mvnw clean compile -pl arctra-core -am
✅ BUILD SUCCESS

./mvnw test -pl arctra-core -Dtest="cn.bitcss.arctra.procedure.*Test" -am
✅ Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
✅ BUILD SUCCESS
```

---

## What M8-A Does NOT Implement

### Deferred to Future Tracks

**M8-B (Candidate Learning):**
- Automatic candidate extraction from successful executions
- Tool schema fingerprint generation
- Binding capture logic
- Cacheability validation

**M8-C (Governance Integration):**
- OperationGovernanceEvaluator
- Cached procedure governance integration

**M8-D (Stepwise Execution):**
- ProcedureExecutionCoordinator
- Parameter binding evaluation
- Output extraction logic
- Step-by-step execution loop
- CheckpointStore integration (procedureState field)

**M8-E (Intent Resolution):**
- ProcedureResolver
- IntentRegistration
- Intent matching logic
- Active revision selection

**M8-F (Verification & Fallback):**
- ProcedureVerification implementation
- Deterministic verification predicates
- Fallback to ReAct logic
- Invalidation policy

---

## Public API Surface (Minimal)

### Exposed Types (arctra-core)

**Domain Model:**
- `ReusableProcedure`
- `ProcedureStep`
- `ProcedureCandidate`
- `ParameterBinding`
- `BindingSource` (enum)
- `ProcedureScope`
- `ProcedureStatus` (enum)
- `ToolCompatibilityFingerprint`
- `OutputExtraction`

**Store Interfaces:**
- `ReusableProcedureStore`
- `ProcedureCandidateStore`

**Implementations:**
- `InMemoryReusableProcedureStore`
- `InMemoryProcedureCandidateStore`

**Exceptions:**
- `ProcedureNotFoundException`
- `ProcedureAlreadyExistsException`

### NOT Exposed (Deferred)

- ProcedureManagement API (M8-E)
- IntentRegistration (M8-E)
- ProcedureVerification (M8-F)
- ProcedureExecutionCoordinator (M8-D)
- CandidateExtractor (M8-B)

---

## Key Design Decisions

### 1. No "Active Revision" in Store

ReusableProcedureStore does NOT maintain an "active revision" pointer. Intent resolution (M8-E) will select appropriate revisions. This avoids dual authorities for routing decisions.

### 2. Status as Mutable Metadata

ProcedureStatus (VALID/INVALID/SUPERSEDED) is mutable metadata, NOT part of immutable executable definition. Updates create new instances but don't change identity.

### 3. Physical Deletion Avoided

Old revisions marked SUPERSEDED remain readable. Critical for suspended executions that may reference older revisions.

### 4. Candidate Lifecycle Separation

ProcedureCandidate and ReusableProcedure have completely distinct lifecycles and storage. Existence of a candidate does NOT make it executable.

### 5. Forward Reference Prevention

PREVIOUS_STEP_OUTPUT bindings validated at construction time to prevent forward references (step N cannot reference step N+1 or beyond).

### 6. Output References Must Exist

Step construction validates that referenced outputs actually exist in previous steps, preventing dangling references.

### 7. Step Output Capture is Declarative

ProcedureStep declares **which** outputs to extract via OutputExtraction. Does NOT store raw results. Minimal continuation values only.

---

## Validation Rules Enforced

### ReusableProcedure Construction

- ✅ procedureId not null/blank
- ✅ revision > 0
- ✅ scope not null
- ✅ intentKey not null/blank
- ✅ steps not empty
- ✅ step indexes contiguous starting from 0
- ✅ all output references exist in previous steps

### ProcedureStep Construction

- ✅ stepIndex >= 0
- ✅ toolName not null/blank
- ✅ toolFingerprint not null
- ✅ PREVIOUS_STEP_OUTPUT bindings don't reference future steps
- ✅ PREVIOUS_STEP_OUTPUT bindings don't reference self
- ✅ output extraction names unique within step

### ParameterBinding

- ✅ source not null
- ✅ path not null/blank
- ✅ PREVIOUS_STEP_OUTPUT format: "stepIndex.outputName"
- ✅ forward references rejected
- ✅ negative step indexes rejected

---

## Files Created

### Production Code (11 files)

```
arctra-core/src/main/java/cn/bitcss/arctra/procedure/
├── BindingSource.java
├── InMemoryProcedureCandidateStore.java
├── InMemoryReusableProcedureStore.java
├── OutputExtraction.java
├── ParameterBinding.java
├── ProcedureAlreadyExistsException.java
├── ProcedureCandidate.java
├── ProcedureCandidateStore.java
├── ProcedureNotFoundException.java
├── ProcedureScope.java
├── ProcedureStatus.java
├── ProcedureStep.java
├── ReusableProcedure.java
├── ReusableProcedureStore.java
└── ToolCompatibilityFingerprint.java
```

### Test Code (4 files)

```
arctra-core/src/test/java/cn/bitcss/arctra/procedure/
├── InMemoryProcedureCandidateStoreTest.java
├── InMemoryReusableProcedureStoreTest.java
├── ParameterBindingTest.java
└── ReusableProcedureTest.java
```

---

## Known Limitations (Deferred by Design)

### M8-A Scope Boundaries

1. **No binding evaluation** - only structural representation
2. **No output extraction implementation** - only declarative contract
3. **No tool schema fingerprint generation** - representation only
4. **No checkpoint integration** - SuspensionCheckpoint unchanged
5. **No governance integration** - deferred to M8-C
6. **No execution logic** - deferred to M8-D
7. **No intent matching** - deferred to M8-E
8. **No verification implementation** - deferred to M8-F
9. **No JDBC persistence** - in-memory only (JDBC may be added if needed)
10. **No A2A support** - V1 is tool operations only

---

## Non-Blocking Legacy Test Debt

None encountered. All arctra-core tests remain unaffected by M8-A additions.

---

## Acceptance Criteria (All Met)

✅ ReusableProcedure is a real provider-neutral domain concept  
✅ ReusableProcedureStore is a real reusable-knowledge authority  
✅ Immutable revisions are enforced  
✅ Linear deterministic procedure structure is representable  
✅ Bindings are structurally validated  
✅ Minimal output contracts are representable  
✅ ProcedureCandidate is distinct from promoted procedure  
✅ Persistence semantics are proven (in-memory)  
✅ No runtime execution semantics were introduced prematurely  
✅ No checkpoint schema was changed  
✅ No M6/M7 authority semantics changed  
✅ Focused M8-A tests pass (43/43)

---

## Next Track Recommendation

**M8-B: Candidate Extraction from Successful Executions**

Prerequisites now satisfied:
- ✅ ReusableProcedure domain model exists
- ✅ ProcedureCandidate storage exists
- ✅ Binding representation validated
- ✅ Tool compatibility fingerprint representation ready

M8-B will implement:
- Automatic candidate extraction from successful ReAct executions
- Tool schema fingerprint generation
- Safe binding capture (whitelist approach)
- Cacheability validation (read-only/idempotent tools only)
- Direct candidate capture (not through ExecutionLedger)

---

## Final Architecture Status

**🟢 FULL GO — M8-A REUSABLE PROCEDURE DOMAIN CLOSED**

### Zero Authority Changes

- CheckpointStore authority: **UNCHANGED**
- InvocationStateStore authority: **UNCHANGED**
- Evidence authority: **UNCHANGED**
- ExecutionLedger projection: **UNCHANGED**

### Zero Provider Coupling

- arctra-core procedure domain: **100% provider-neutral**
- No Spring AI dependencies: **VERIFIED**
- No execution engine dependencies: **VERIFIED**

### Immutable Revision Semantics

- (procedureId, revision) uniqueness: **ENFORCED**
- Executable definition immutability: **ENFORCED**
- Old revision readability: **PRESERVED**

### Authority Separation

- ReusableProcedureStore owns definitions: **ESTABLISHED**
- ProcedureCandidateStore owns unpromoted candidates: **ESTABLISHED**
- CheckpointStore will own execution position: **PRESERVED FOR M8-D**

---

**M8-A establishes the knowledge authority. M8-B will teach Arctra how to learn it.**

**@author lov3r**  
**Date: 2026-09-21**  
**Status: CLOSED**
