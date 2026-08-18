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

🚧 **M2 Session & Multi-Turn Capability - IN PROGRESS**

The project has completed M1 (Incident Agent MVP) and is implementing M2 (Multi-Turn Conversation Support).

### Completed

**M1: Incident Agent MVP (2026-08-17)**
- ✅ Agent domain model (AgentDefinition, AgentRequest, AgentResult)
- ✅ Evidence capture system
- ✅ Spring AI Tool Calling Engine integration
- ✅ Incident investigation example (with real tools)
- ✅ E2E tests (fake + real scenarios)

**M2-T1: Spring AI ChatMemory PoC (2026-08-18)**
- ✅ Verified Spring AI 2.0.0 ChatMemory API
- ✅ Validated MessageChatMemoryAdvisor

**M2-T2: Session Support (2026-08-18)**
- ✅ AgentExecutionContext for session identity
- ✅ AgentExecutionEngine contract evolution (3-param method)
- ✅ SpringAiToolCallingEngine multi-turn support
- ✅ ChatMemory integration

### In Progress

**M2-T3: Multi-Turn E2E Test** - READY

### Next
- M2-T4: Documentation & Limitations
- M3: Context Compaction & Long-term Memory

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
