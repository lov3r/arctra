# Current State

**Last Updated:** 2026-08-17

---

## Current Phase: M1 Incident Agent MVP (IN PROGRESS)

**Current Task:** M1-T3 (Spring AI 集成方案验证) - READY

**Previous Phase:** BOOT-004 ✅ COMPLETE (2026-08-14)

---

## M1 Progress

### Completed Tasks

- ✅ **M1-T1:** Arctra ↔ Spring AI Tool 边界设计 (2026-08-14)
  - 设计文档：docs/design/M1-T1-tool-boundary.md
  - 结论：M1 直接使用 Spring AI ToolCallback，不创建 Arctra Tool Contract
  - Evidence 收集位置延后到 M1-T3 验证
  - 未来 Governance 位于独立层（非 Engine 实现）

- ✅ **M1-T2:** Evidence 领域模型 (2026-08-17)
  - Evidence record 创建（arctra-core/evidence）
  - AgentResult 扩展（添加 evidences 字段）
  - Evidence 是 Framework 通用语义（不包含 private reasoning）
  - Decision 暂不创建 Framework-level Contract（未被多场景验证）
  - 40 tests pass, ./mvnw clean verify SUCCESS

### Next Task

**M1-T3: Spring AI 集成方案验证**
- 验证 Spring AI ChatClient + ToolCallingAdvisor
- 对比不同 Evidence capture 位置（Advisor / ToolCallback / Engine）
- 验证 Engine 命名
- 推荐方案（复用 Spring AI Loop vs 自建）

---

## M1 Planning Summary

### Objective

Complete the first real Vertical Slice: from Incident Question to Evidence-based Diagnosis, validating that Arctra's core architecture works in real scenarios.

### Scenario

**Production 500 Error Spike Analysis**

Input: "生产环境从 16:20 开始出现大量 500 错误，请分析原因"

Expected Output:
```
Root Cause Analysis:
  Database schema migration missing for user_status field

Evidence:
  1. [QueryLogsTool] 16:20 开始出现 SQLException: Unknown column 'user_status'
  2. [GetDeploymentTool] 16:18 部署 v1.2.3，代码新增 user_status 字段

Diagnosis:
  Schema drift between application code and database

Recommended Actions (requires approval):
  - Option 1: Execute schema migration (requires DBA review)
  - Option 2: Rollback to v1.2.2 (requires impact assessment)
```

### M1 Tasks

1. **M1-T1:** Arctra ↔ Spring AI Tool 边界设计 (0.5d) - READY
2. **M1-T2:** Evidence 领域模型 (0.5d) - READY
3. **M1-T3:** Spring AI 集成方案验证 (1d) - BACKLOG
4. **M1-T4:** Arctra Tool Contract 实现 (0.5d) - BACKLOG
5. **M1-T5:** Mock Tools 实现 (0.5d) - BACKLOG
6. **M1-T6:** Spring AI-based Engine 实现 (2d) - BACKLOG
7. **M1-T7:** Incident Scenario E2E Test (1d) - BACKLOG
8. **M1-T8:** Documentation, Dogfooding 和抽象清理 (1d) - BACKLOG

**Total Estimate:** 6.5 days (1.5~2 weeks with buffer)

### Key Constraints

1. **arctra-core 保持纯 Java**
   - 无 Spring AI 依赖
   - 无 Spring Framework 依赖

2. **Spring AI 集成在 arctra-runtime-react**
   - Model 集成
   - Tool Calling Loop（优先复用 Spring AI 能力）

3. **不提前创建未来抽象**
   - 不提前创建 Permission/Risk/Audit
   - 不提前把 Policy/HITL 塞进核心模型
   - Mock Tools 放在 examples 或 test fixtures

4. **Evidence 是 Framework 通用语义**
   - 明确区分 Framework 模型和 Incident 场景输出
   - Decision/Diagnosis/Recommendation 归属待确定

5. **无生产 DDL 执行**
   - 只输出诊断和需审批的操作建议
   - 为未来 Policy/HITL 预留正确边界

### M1 Non-Goals

明确不在 M1 实现：
- AgentClient API
- Spring Boot Starter
- Tool Permission/Policy/Governance
- Tool Sandbox/Isolation
- Session 管理
- Checkpoint/Resume
- HITL 实现
- 真实 Tool 集成
- RAG
- Multi-Agent

---

## BOOT-004 Final Report

### What Was Completed

**BOOT-004: ExecutionEngine Contract**

Established the Runtime ↔ Engine boundary and proved engine replaceability:

1. **Engine Contract (1 public interface)**
   - `AgentExecutionEngine` — Public extension contract for pluggable engines
   - Minimal contract: `execute(AgentDefinition, AgentRequest) → AgentResult`
   - Execution-neutral Javadoc (no ReAct/Model/Tool specifics in contract)

2. **Runtime Implementation (1 class)**
   - `DefaultAgentRuntime` — Delegates to AgentExecutionEngine
   - Package-private (kernel internal)
   - Simple delegation pattern

3. **Engine Replaceability Proven**
   - 3 test engines implemented:
     - `FakeExecutionEngine` — Returns fake response
     - `EchoExecutionEngine` — Echoes user message
     - `UpperCaseExecutionEngine` — Converts to uppercase
   - Same runtime, different engines → different behaviors
   - No runtime modification required to change execution strategy

4. **Call Chain Verified**
   ```java
   AgentRuntime runtime = new DefaultAgentRuntime(engine);
   AgentResult result = runtime.execute(definition, request);
   // Runtime → Engine → Result ✅
   ```

5. **Boundary Clarity**
   - AgentRuntime = Kernel Internal Contract (package-private)
   - AgentExecutionEngine = Public Extension Contract (public)
   - Engine implementations are replaceable without runtime changes

### Key Decisions

1. **AgentRuntime remains package-private**
   - Reason: Engine in same package can access
   - Kernel internal contract, not user-facing API
   - Visibility determined by real usage scenarios

2. **Minimal Engine Contract**
   - Only `execute()` method
   - No `name()` — no real consumer exists yet
   - No `capabilities()` — only one engine type currently
   - No `EngineContext` / `EngineResult` — direct parameters sufficient
   - No `AgentExecutionException` hierarchy — runtime propagates exceptions as-is

3. **Execution-neutral contract description**
   - Javadoc doesn't mention ReAct / Model / Tool
   - ReAct is one future implementation, not the contract definition
   - Tool Runtime/Governance boundary reserved for architecture docs

4. **Replaceability proven with 3 engines**
   - Not just one Fake claiming "replaceable"
   - Three different behaviors with same runtime
   - Test explicitly verifies: change engine → change behavior, no runtime change

5. **No real implementation yet**
   - No Native ReAct
   - No Model integration
   - No Tool calling
   - BOOT-004 only defines and verifies the contract

### Verification Results

All Acceptance Criteria met:

- ✅ AgentExecutionEngine contract defined (public)
- ✅ AgentRuntime remains package-private
- ✅ DefaultAgentRuntime delegates to Engine
- ✅ Runtime → Engine → Result call chain works
- ✅ Engine replaceability proven (3 different engines)
- ✅ No name() / capabilities() / EngineContext / EngineResult
- ✅ No real ReAct implementation
- ✅ Tests: 27/27 passed (21 BOOT-002/003 + 6 BOOT-004)
- ✅ ./mvnw clean verify passes (3.8s)

### Code Statistics

- Production code: 2 files, ~50 lines
- Test code: 5 files, ~150 lines
- Total: 7 files, ~200 lines
- Build time: 3.8 seconds
- Test results: 27/27 passed

### Architecture Impact

**Established Runtime ↔ Engine Boundary:**

```
AgentRuntime (Kernel Internal)
  ↓ delegates to
AgentExecutionEngine (Public Extension Contract)
  ↓ implementations
FakeExecutionEngine / EchoExecutionEngine / UpperCaseExecutionEngine
```

**Boundary ensures:**
- Engine handles "how agent executes"
- Runtime handles "what execution means" (future: Session/Policy/Evidence)
- Engines are pluggable and replaceable

### What Was NOT Done (Non-Goals)

As planned, explicitly deferred:

❌ Native ReAct implementation → BOOT-005+
❌ Model integration (Spring AI) → BOOT-005+
❌ Tool calling → Future
❌ Engine name() / capabilities() → Wait for multiple real engines
❌ EngineContext / EngineResult → Current signature sufficient
❌ Exception hierarchy → Wait for real failure semantics
❌ Session/State management → Future
❌ Budget/Policy/Evidence → Future
❌ Spring integration → Future

---

## BOOT-003 Final Report

### What Was Completed

**BOOT-003: AgentClient 最小调用闭环**

Verified the minimal call path using Fake Model, proving the kernel contract works:

1. **Runtime Contract (1 interface)**
   - `AgentRuntime` — Kernel internal contract for executing agents
   - Intentionally package-private (not public yet)
   - Minimal contract: `execute(AgentDefinition, AgentRequest) → AgentResult`

2. **Fake Implementation (1 test class)**
   - `FakeAgentRuntime` — Returns fixed fake response
   - No real model, no engine abstraction
   - Proves the call path works

3. **Call Path Verified**
   ```java
   AgentRuntime runtime = new FakeAgentRuntime();
   AgentDefinition definition = new AgentDefinition("test", "desc");
   AgentRequest request = new AgentRequest("Hello");
   
   AgentResult result = runtime.execute(definition, request);
   // ✅ Works!
   ```

4. **Architecture Protection**
   - ArchUnit: Runtime !→ Client (forbid direction)
   - ArchUnit: Agent models !→ Runtime/Client (forbid direction)
   - No rules dictating implementation structure

5. **Test Coverage**
   - Unit tests: 3 tests covering runtime contract
   - Architecture tests: 6 rules (2 new forbid rules)
   - All tests pass: 21/21

### Key Decisions

1. **No AgentClient yet**
   - Reason: No Agent Registry/Resolution mechanism exists
   - `client.agent("name")` would fake "agent selection" semantics
   - AgentClient postponed until concrete user-facing semantics are validated
   - Current approach is honest: test explicitly creates AgentDefinition

2. **AgentRuntime is package-private**
   - Reason: Internal kernel contract, not stabilized yet
   - Follows: internal first → real usage → stabilize → public
   - Whether it becomes public extension SPI determined by future usage

3. **No ExecutionEngine abstraction**
   - Reason: BOOT-004's explicit goal
   - Fake Model doesn't need engine abstraction
   - Adding it now would be premature

4. **Minimal contract**
   - Only `execute(definition, request) → result`
   - No Session, no Execution tracking, no State Machine
   - No Budget, no Policy, no Evidence

5. **ArchUnit rules forbid directions, not dictate structure**
   - Removed: rules requiring specific dependencies
   - Added: rules forbidding wrong directions (runtime → client, agent → runtime)

### Verification Results

All Acceptance Criteria met:

- ✅ AgentRuntime contract defined
- ✅ FakeAgentRuntime works
- ✅ execute(def, req) → result call path verified
- ✅ No Agent Registry/Resolution (no fake semantics)
- ✅ AgentRuntime is package-private
- ✅ Dependency directions protected by ArchUnit
- ✅ ./mvnw clean verify passes (4.3s)
- ✅ No Session/Execution/Engine abstractions
- ✅ No Client/Factory/Registry
- ✅ Tests: 21/21 passed

### Code Statistics

- Production code: 1 file, ~25 lines
- Test code: 2 files, ~70 lines
- Total: 3 files, ~95 lines
- Build time: 4.3 seconds
- Test results: 21/21 passed (16 BOOT-002 + 3 BOOT-003 + 2 ArchUnit new)

### What Was NOT Done (Non-Goals)

As planned, explicitly deferred:

❌ AgentClient → Postponed until user-facing semantics validated
❌ Agent Registry/Resolution → No mechanism exists yet
❌ ExecutionEngine abstraction → BOOT-004's goal
❌ AgentSession management → Stateless call only
❌ AgentExecution tracking → No execution domain
❌ State Machine → Future
❌ Budget/Policy/Evidence → Future
❌ Spring integration → Future
❌ Real Model integration → Future
❌ Tool/RAG → Future

---

## BOOT-002 Final Report

### What Was Completed

**BOOT-002: Agent Kernel Domain Skeleton**

Created the minimal domain models needed for AgentClient.call() in BOOT-003:

1. **Domain Models (3 classes)**
   - `AgentDefinition` — Defines agent identity and purpose
   - `AgentRequest` — Stateless, single-turn request
   - `AgentResult` — Execution result (not "response" — emphasizes runtime semantics)

2. **Package Structure**
   - Package: `cn.bitcss.arctra.agent` (capability-oriented, not module-scoped)
   - All models are immutable Java records
   - All models are public (required by AgentClient API)

3. **Invariant Protection**
   - AgentDefinition: name cannot be blank
   - AgentRequest: userMessage cannot be blank
   - AgentResult: content cannot be null (but can be empty string)

4. **Test Coverage**
   - Unit tests: 12 tests covering all constructors and invariants
   - Architecture tests: 4 rules protecting core dependencies
   - Test coverage: 100% for domain models

5. **Architecture Protection**
   - ArchUnit rules: Core cannot depend on Spring, JPA, Elasticsearch, Redis
   - Maven Enforcer: Core banned dependencies enforcement
   - Test dependencies added to arctra-core POM

### Key Decisions

1. **Simplified scope** — Removed engine/engineConfig/sessionId/executionId
   - Engine selection deferred to ExecutionEngine Contract phase
   - Session management deferred (current is stateless)
   - Execution tracking deferred (no Execution domain yet)

2. **AgentResult vs AgentResponse** — Chose "Result" for runtime semantics
   - Emphasizes execution outcome, not transport layer
   - Aligns with "Engineering Runtime/Harness" positioning

3. **Package naming** — Used `cn.bitcss.arctra.agent`, not `cn.bitcss.arctra.core.agent`
   - Capability-oriented, not module-scoped
   - Maven module name doesn't leak into Java namespace

4. **No DDD ceremony** — Simple immutable records, not full DDD
   - Complexity doesn't justify Aggregate/Repository/Domain Service
   - Follows DDD principles (immutability, invariants) without full patterns

5. **Author tags** — Added `@author lov3r` to all classes
   - Updated CLAUDE.md to enforce this convention

### Verification Results

All Acceptance Criteria met:

- ✅ AgentDefinition / AgentRequest / AgentResult created
- ✅ All models are immutable records
- ✅ Invariants protected (illegal input throws IllegalArgumentException)
- ✅ Unit tests: 16 tests, 100% pass
- ✅ Architecture tests: 4 rules, 100% pass
- ✅ Package: cn.bitcss.arctra.agent (not core.agent)
- ✅ ./mvnw clean verify passes (3.6s)
- ✅ No premature abstractions (engine/session/execution/budget)
- ✅ All classes are public (AgentClient requires them)
- ✅ Code follows Spotless formatting

### Code Statistics

- Production code: 3 files, ~50 lines
- Test code: 4 files, ~130 lines
- Total: 7 files, ~180 lines
- Build time: 3.6 seconds
- Test results: 16/16 passed

---

## BOOT-001 Final Report

### What Was Completed

**BOOT-001: Maven Multi-Module Foundation**

Established the engineering baseline for Arctra:

1. **Maven Structure**
   - Created parent POM with version management (BOM)
   - Configured 7 core modules + 2 example modules
   - Set up Maven Wrapper (3.9.9)
   - Configured flatten-maven-plugin for CI-friendly versions

2. **Version Baseline**
   - Java 21 (LTS)
   - Spring Boot 4.0.0 (GA)
   - Spring AI 2.0.0 (GA)
   - JUnit 5.11.x
   - AssertJ 3.26.x
   - ArchUnit 1.3.0

3. **Architecture Protection**
   - Maven Enforcer rules: Java 21+, Maven 3.9.0+
   - Dependency convergence enforcement
   - arctra-core banned dependencies (no Spring, no concrete infrastructure)

4. **Code Quality Tools**
   - Spotless (Google Java Format)
   - JaCoCo (test coverage)
   - .editorconfig (editor consistency)

5. **CI/CD**
   - GitHub Actions workflow
   - Runs: `./mvnw clean verify`
   - Java 21 environment
   - Artifact uploads (test results, coverage)

6. **Module Structure**
   ```
   arctra-api                    # Pure Java interfaces
   arctra-core                   # Pure Java domain (enforced)
   arctra-runtime-react          # Depends on core only (no Spring AI yet)
   arctra-rag                    # Empty module
   arctra-tool                   # Empty module
   arctra-testkit                # core + JUnit + AssertJ
   arctra-spring-boot-starter    # core + Spring Boot + Spring AI
   examples/knowledge-assistant
   examples/incident-investigator
   ```

7. **Documentation**
   - Updated README.md (project status, scope, principles)
   - Updated CURRENT-STATE.md (this file)
   - Enhanced .gitignore

### Verification Results

All Acceptance Criteria met:

- ✅ `./mvnw clean verify` succeeds
- ✅ All 9 modules in Maven Reactor
- ✅ arctra-core has no Spring dependencies
- ✅ Maven Enforcer rules configured
- ✅ CI executes same verify step
- ✅ README explains project positioning
- ✅ CURRENT-STATE.md updated
- ✅ No domain/runtime implementation (structure only)

### Key Decisions

1. **Spring Boot 4.0.0 + Spring AI 2.0.0** — based on official compatibility matrix
2. **arctra-core remains Pure Java** — no Spring dependencies (enforced by Maven)
3. **Maven Enforcer over ArchUnit (for now)** — no classes exist yet; ArchUnit deferred to BOOT-002
4. **runtime-react defers Spring AI** — follows "first real consumer" principle
5. **No Checkstyle** — Spotless is sufficient for V1

---

## Next Phase: BOOT-002

**Goal:** First Domain Models + AgentRuntime Contract

### Planned Work

1. **Core Domain Classes**
   - `AgentDefinition`
   - `AgentRequest`
   - `AgentResponse`
   - `AgentSession`
   - `Evidence`
   - `Decision`

2. **Runtime Contracts**
   - `AgentRuntime` interface
   - `AgentExecutionEngine` SPI
   - `ExecutionContext`

3. **Testing**
   - First unit tests (domain model behavior)
   - ArchUnit tests (package dependencies)
   - TestKit foundation

4. **Observability Foundation**
   - Session lifecycle events
   - Execution state tracking

5. **Dependencies**
   - Introduce Spring AI to `arctra-runtime-react` (when needed)
   - Potentially introduce SLF4J to core (if logging is required)

### Entry Criteria

- BOOT-001 ✅ complete
- `./mvnw clean verify` ✅ passes
- Documentation ✅ up to date

### Exit Criteria

- Core domain models exist with tests
- AgentRuntime contract defined
- ArchUnit tests protect architecture
- All tests pass
- Documentation updated

---

## Module Dependency Matrix (Current)

| Module                     | Depends On                           | Notes                              |
|----------------------------|--------------------------------------|------------------------------------|
| arctra-api                 | (none)                               | Pure Java                          |
| arctra-core                | (none)                               | Pure Java, enforced by Maven       |
| arctra-runtime-react       | core                                 | No Spring AI yet                   |
| arctra-rag                 | core                                 | Empty                              |
| arctra-tool                | core                                 | Empty                              |
| arctra-testkit             | core, JUnit, AssertJ                 | Compile scope (user-facing)        |
| arctra-spring-boot-starter | core, Spring Boot, Spring AI         | Only module with Spring            |
| examples/*                 | arctra-spring-boot-starter           | Minimal POMs                       |

---

## Known Limitations / Technical Debt

1. **No functional code yet** — only structure and tooling
2. **ArchUnit tests deferred** — will be added in BOOT-002 when classes exist
3. **Examples have no configuration** — application.yml will be added when needed
4. **No README in example modules** — will be added with actual code

---

## Architecture Compliance Status

| Rule                                | Status | Enforcement                  |
|-------------------------------------|--------|------------------------------|
| arctra-core → no Spring             | ✅ PASS | Maven Enforcer               |
| arctra-core → no infrastructure     | ✅ PASS | Maven Enforcer               |
| Java 21+                            | ✅ PASS | Maven Enforcer               |
| Maven 3.9.0+                        | ✅ PASS | Maven Enforcer               |
| Dependency convergence              | ✅ PASS | Maven Enforcer               |
| Package dependencies (ArchUnit)     | ⏳ TODO | Deferred to BOOT-002         |

---

## Build & Test Status

**Local Build:** ✅ PASS

```bash
$ ./mvnw clean verify
[INFO] BUILD SUCCESS
```

**CI Build:** 🟡 Pending (first push to main)

---

## Questions / Blockers

None.

---

## References

- Architecture: `docs/ARCHITECTURE-V7.md`
- DX: `docs/DX-V3.md`
- Development Plan: `docs/DEVELOPMENT-PLAN.md`
- Constitution: `CLAUDE.md`
- Tasks: `TASKS.md`
