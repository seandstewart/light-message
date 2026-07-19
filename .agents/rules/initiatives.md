# Rules for Initiatives

Create + maintain initiative folders per pattern.

## Structure

Versioned folders: `initiatives/v1/`, `initiatives/v2/`, etc.

```
initiatives/v1/
  ├── index.md      (metadata + overview)
  ├── proposal.md   (scope, tech stack, milestones, timeline)
  └── design.md     (specs, domain models, contracts)
```

## Files

### `index.md` — Metadata & Overview

Header with status + milestone:

```markdown
# Initiative [vN]: "[Title]"

**Status:** [Draft | Proposed | Accepted | In Progress | Completed]
**Current Milestone**: [MN] ([Description])
**Active ADR:** [ADR ref or n/a]
```

Followed by index table:

```markdown
## Index

| Document                     | Purpose                         |
| ---------------------------- | ------------------------------- |
| [proposal.md](./proposal.md) | Scope, tech stack, milestones   |
| [design.md](./design.md)     | Specs, domain models, contracts |
```

### `proposal.md` — Scope & Strategy

Complete project definition:

1. **Project Plan** (title + diagram = intro)
2. **Architecture** — ASCII diagram (one view: vertical or flow)
3. **Milestones** — Table: Phase, Duration, Deliverable, Exit Criteria (start phase 0)
4. **Risk Register** — Table: Risk, Impact, Mitigation (reference ADRs inline)
5. **Dependencies** — Table: Artifact, Function, Notes (libs, bindings, services)
6. **Decision Gateways** — Numbered gates after critical phases
7. **References** — Links to ADRs, external docs

### `design.md` — Contracts & Domain Models

Technical specs + domain-driven design per Milestone 0 (TDD):

1. **Milestone 0: TDD** — Structure by deliverable
2. **Domain Overview & Bounded Contexts** — Subdomain map + glossary
3. **Protocol Specification** — IPC contracts, message catalogs, state machines, lifecycle
4. **Object Model** — Aggregates, entities, value objects, services, events
5. **Ubiquitous Language** — Terms + definitions
6. **State Machines** — Auth flow, delivery, message transitions
7. **API Contracts** — AsyncAPI (events), OpenAPI (REST), custom formats (Plist, Protobufs)
8. **Compliance & Constraint Mapping** — Regulatory + platform constraints
9. **Gate Verification** — Proof-of-concept checklist to unblock next phase

## Linking ADRs

`index.md` header includes **Active ADR**:

```markdown
**Active ADR:** [ADR-005](../adrs/005-rustpush-integration.md)
```

`proposal.md` Risk Register + Dependencies: reference ADRs inline:

```markdown
| Risk                     | Mitigation                                                           |
| ------------------------ | -------------------------------------------------------------------- |
| **Light SDK blocks NDK** | Petition Light citing [ADR-005](../adrs/005-rustpush-integration.md) |
```

Dependencies:

```markdown
| Artifact | Function       | Notes                                  |
| -------- | -------------- | -------------------------------------- |
| `OkHttp` | HTTP/WebSocket | Avoids whitelist mismatches. [ADR-004] |
```

## Milestone Conventions

Number from 0: `0`, `1`, `2` (not M1, M2). Phase 0 = TDD.

Table columns:

- **Phase**: Name (e.g., "SDK Audit & NDK Feasibility")
- **Duration**: Estimated (e.g., "1 week", "3–4 weeks")
- **Deliverable**: Artifact/capability completed
- **Exit Criteria**: Measurable proof (unit tests pass, integration test, hardware validation)

Total timeline = sum of phase durations.

## Status Progression

1. **Draft** — Initial design, contracts + gating logic
2. **Proposed** — Full proposal documented, ready for approval
3. **Accepted** — Approved, Phase 0 starting
4. **In Progress** — Active development, update `Current Milestone` as phases complete
5. **Completed** — All phases delivered/shipped/archived

Update `index.md` header + `Current Milestone` when status changes.

## Naming & Location

- Folder: `projects/v[N]/` (N = 1, 2, 3, ...)
- Version numbers track major iterations
- Docs evolve in place without renaming

## Cross-References

- Projects reference **ADRs** for architectural rationale
- Projects can reference other projects (e.g., "v2 extends v1") with relative links
- External docs linked by full URL if not co-located
