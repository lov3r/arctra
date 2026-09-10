# Arctra

**Agent Engineering Harness for Spring Ecosystem**

---

## What is Arctra?

Arctra is a Spring-based framework for building, testing, governing, recovering, evaluating, and observing AI agents across different execution engines.

**Key principles:**
- Unified runtime semantics across execution engines
- Production-grade: testable, recoverable, observable
- Spring-native: leverage Spring Boot ecosystem
- Clean architecture: pure Java core, infrastructure at edges

**Not another LangGraph.** Arctra focuses on governance, testing, and observability — not reimplementing agent capabilities.

---

## Project Status

✅ **M5 Durable Suspension/Recovery - COMPLETE (2026-09-09)**

The project has completed foundational milestones M1-M5, establishing Agent execution, session management, process lifecycle semantics, and durable recovery capabilities.

### Completed Milestones

**M1: Incident Agent MVP (2026-08-17)**
- ✅ Agent domain model (AgentDefinition, AgentRequest, AgentResult)
- ✅ Evidence capture system
- ✅ Spring AI Tool Calling Engine integration
- ✅ Incident investigation example (with real tools)
- ✅ E2E tests (fake + real scenarios)

**M2: Session & Multi-Turn Capability (2026-08-18)**
- ✅ Spring AI 2.0.0 ChatMemory integration
- ✅ MessageChatMemoryAdvisor for conversation history
- ✅ AgentExecutionContext with session identity
- ✅ Multi-turn conversation flow

**M3: Agent API & Runtime Boundary (2026-08)**
- ✅ Agent public API stabilization
- ✅ AgentRuntime abstraction layer
- ✅ AgentExecutionEngine seam
- ✅ Clean architecture boundaries

**M4: Process Lifecycle & Governance (2026-09-08)**
- ✅ **M4-T1:** AgentProcess contract & Dynamic Materialization
- ✅ **M4-T2:** Process lifecycle foundation (WAITING/RUNNING/COMPLETED)
- ✅ **M4-T3:** Spring AI governance integration & memory closure
- ✅ **M4-T4:** Process failure semantics (FAILED lifecycle)

**M5: Durable Suspension/Recovery (2026-09-09)** ✅ **M5-T4 FINAL: GO**
- ✅ **M5-T1:** Durable Process Contract Gate
- ✅ **M5-T2:** Durable Resume Reconstruction PoC
- ✅ **M5-T3:** Durable Recovery Architecture Gate
- ✅ **M5-T4:** Durable Suspension/Recovery Implementation
  - Checkpoint-backed durable suspension
  - Cross-runtime/JVM recovery
  - Unified resume pipeline (CHECK A/B)
  - RuntimeBinding resolution contract
  - Local handle lifecycle semantics
  - Memory/Evidence/Governance continuity
  - Concurrency contracts (CAS + CHECK B)
  - 237 tests, 0 failures

**Current Capabilities:**
- Agent execution with stateless reusable handles
- AgentProcess lifecycle for tasks crossing synchronous boundaries
- Tool governance (ALLOW/DENY/REQUIRE_APPROVAL)
- Human-in-the-loop approval with suspend/resume
- Session-backed conversation continuity across suspend/resume
- Evidence collection and stable process identity
- Complete failure semantics (FAILED as terminal state)
- **Durable suspension/recovery across JVM boundaries** 🆕
- **Cross-runtime recovery (A → B → C)** 🆕
- **RuntimeBinding resolution for logical agent reconstruction** 🆕
- **CHECK A/B validation fencing** 🆕
- **Concurrency conflict detection** 🆕

**Architecture Documentation:**
- M5 Milestone Summary: `M5-MILESTONE-SUMMARY.md`
- M5-T4 Implementation Guide: `M5-T4-IMPLEMENTATION-GUIDE.md`
- M5-T4 Final Closure Report: `M5-T4-FINAL-CLOSURE-REPORT.md`
- M4 Final Architecture: `docs/architecture/M4-FINAL-ARCHITECTURE.md`
- M5 Planning & Architecture: `docs/planning/`, `docs/architecture/`

**M5 Known Limitations (design boundaries):**
- InMemoryCheckpointStore: JVM-local reference implementation
- No CheckpointStore/ChatMemory atomicity (crash window)
- At-least-once tool execution semantics (not exactly-once)
- Application-defined RuntimeBindingResolver required
- No automatic retry for ResumePreparationException
- Continuation code duplication (M6 technical debt)

**M5 Explicitly Does NOT Claim:**
- Production-ready distributed durability
- Exactly-once tool execution guarantees
- Atomic checkpoint/ChatMemory transactions

### Next

M6 direction to be determined. Candidate work items:
- Production CheckpointStore implementations (JDBC, Redis)
- RuntimeBinding reconstruction strategies
- CheckpointStore/ChatMemory consistency coordination
- Tool deduplication/idempotency strategies
- Automatic retry framework for ResumePreparationException
- Continuation pipeline consolidation

---

## V1 Scope

V1 will deliver **two vertical slices** to validate the architecture:

1. **Knowledge Assistant** — query project knowledge via RAG
2. **Incident Investigator** — analyze incidents with tool calling

V1 includes:
- AgentClient API
- Native ReAct runtime
- Tool & RAG pipelines
- Evidence/Decision tracking
- Checkpoint/Resume
- TestKit
- Basic observability

**Explicitly deferred:**
- AgentScope integration
- Multi-agent orchestration
- GraphRAG
- Web console
- Distributed runtime

---

## Module Structure

```
arctra-parent                     # Parent POM
├── arctra-api                    # Pure Java interfaces
├── arctra-core                   # Domain models (Pure Java, no Spring)
├── arctra-runtime-react          # Native ReAct implementation
├── arctra-rag                    # RAG pipeline
├── arctra-tool                   # Tool runtime
├── arctra-testkit                # Testing DSL
├── arctra-spring-boot-starter    # Spring Boot auto-config
└── examples/
    ├── knowledge-assistant       # V1 vertical slice
    └── incident-investigator     # V1 vertical slice
```

---

## Requirements

- **Java:** 21+
- **Maven:** 3.9.0+
- **Spring Boot:** 4.0.0
- **Spring AI:** 2.0.0

---

## Build

```bash
./mvnw clean verify
```

---

## Architecture Principles

1. **Core remains Pure Java** — no Spring Boot, no concrete infrastructure
2. **Execution engines are pluggable** — framework defines runtime semantics
3. **Everything is testable** — agents, tools, RAG, full scenarios
4. **Failures are first-class** — timeout, cancellation, retry, recovery
5. **Observability by design** — evidence, decisions, execution logs

See `docs/ARCHITECTURE-V7.md` for details.

---

## Documentation

### Core Documents
- **Architecture:** `docs/ARCHITECTURE-V7.md`
- **Developer Experience:** `docs/DX-V3.md`
- **Project Constitution:** `CLAUDE.md`
- **Current State:** `docs/project/CURRENT-STATE.md`
- **Tasks:** `TASKS.md`

### Architecture Guides
- **Architecture Evolution Guide:** `docs/architecture/EVOLUTION-GUIDE.md` — 何时以及为什么创建新抽象
- **Skill / Experience Evolution:** `docs/architecture/SKILL-EXPERIENCE-EVOLUTION.md` — 成功模式沉淀与复用
- **Tool / Skill Boundary:** `docs/architecture/TOOL-SKILL-BOUNDARY.md` — Tool 与 Skill 的明确边界

---

## License

Licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 lov3r and Arctra contributors.

---

## Contributing

Project is in early bootstrap phase. Contributions will be welcomed after V1 architecture is validated.

---

**Built with Spring. Designed for Production.**
