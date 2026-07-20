# Agent Guidelines

Rules and context for code agents working on this project.

## Architecture Decisions

All structural choices are documented in [**docs/adrs/**](docs/adrs/). Before proposing changes to:

- Framework, build tool, or language choice
- State management pattern
- Data model

**Read the relevant ADR first.** It explains the decision, consequences, and trade-offs. Respect accepted decisions; propose new ADRs only for genuinely new choices.

## Initiative Context

**Active Initiative**: [v1](./agents/projects/v1/index.md)

## When to Update Documentation

After significant changes:

1. **Architecture change** → New ADR (with Status, Context, Decision, Consequences)
2. **Design/spec update** → Update `docs/projects/v[N]/design.md`
3. **Milestone progress** → Update `Current Milestone` in `docs/projects/v[N]/index.md`

Document **decisions**, not implementation details.

## Rules Files

- **[.agents/rules/caveman.md](./agents/rules/caveman.md)** — Default skill `/caveman`
- **[.agents/rules/adrs.md](./agents/rules/adrs.md)** — ADR format, numbering, index maintenance
- **[.agents/rules/projects.md](./agents/rules/projects.md)** — Project folder structure, proposal sections, cross-linking

Refer to these when creating or updating architecture docs.

## Tools

- Use `mise exec -- ...` to execute shell commands.
- Use `mise run ...` to run tasks
- Tasks are defined in [mise.toml](./mise.toml)
