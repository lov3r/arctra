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

🚧 **Bootstrap Phase - BOOT-001 Complete**

The project is currently establishing its Maven multi-module foundation. No functional code yet — only engineering infrastructure.

### Completed (BOOT-001)
- ✅ Maven multi-module structure (9 modules)
- ✅ Java 21 + Spring Boot 4.0.0 + Spring AI 2.0.0
- ✅ Maven Wrapper
- ✅ Architecture enforcement (Maven Enforcer)
- ✅ Code formatting (Spotless)
- ✅ Test coverage (JaCoCo)
- ✅ CI/CD (GitHub Actions)

### Next Steps (BOOT-002)
- Core domain models (AgentDefinition, AgentRequest, etc.)
- Basic AgentRuntime contract
- First unit tests

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

- **Architecture:** `docs/ARCHITECTURE-V7.md`
- **Developer Experience:** `docs/DX-V3.md`
- **Development Plan:** `docs/DEVELOPMENT-PLAN.md`
- **Project Constitution:** `CLAUDE.md`
- **Current State:** `docs/project/CURRENT-STATE.md`
- **Tasks:** `TASKS.md`

---

## License

TBD

---

## Contributing

Project is in early bootstrap phase. Contributions will be welcomed after V1 architecture is validated.

---

**Built with Spring. Designed for Production.**
