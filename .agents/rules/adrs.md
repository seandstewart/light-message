# Rules for Architecture Decision Records

Create and maintain ADRs per pattern.

## Format

```markdown
# ADR NNN: [Decision Title]

**Status:** [Proposed | Accepted | Deprecated]

**Context:** [Issue/problem driving decision?]

**Decision:** [Chosen approach?]

**Consequences:**

- Positive: [Benefits]
- Negative: [Trade-offs]
```

## Numbering & Naming

- Sequential numeric prefix with zeros: `001`, `002`, `016`
- Filename: `NNN-kebab-case-title.md`
- Title: `# ADR NNN: Title Case`

## Sections

| Section          | Required | Notes                              |
| ---------------- | -------- | ---------------------------------- |
| **Status**       | Yes      | Proposed, Accepted, or Deprecated  |
| **Context**      | Yes      | Problem/constraint. 1–3 sentences. |
| **Decision**     | Yes      | What chosen + why. 1–2 sentences.  |
| **Consequences** | Yes      | Bullets. ≥1 positive AND negative. |

## Scope

Document architectural and technical choices affecting design. Skip sprint tasks + implementation details.

**Appropriate:** Framework choice (Vanilla JS vs React), state management (FSM vs Redux), audio lib (Tone.js vs Web Audio), data model (JSON vs API)

**Not:** "Fix button bug", "Add help text", "Refactor CSS"

## Index Maintenance

`index.md` lists all ADRs:

```markdown
| #                    | Title | Status   |
| -------------------- | ----- | -------- |
| [001](./001-file.md) | Title | Accepted |
```

When adding:

1. Create `NNN-kebab-case-title.md`
2. Add row to index table
3. Use relative links

## References

Link to ADRs from docs:

```markdown
[ADR-001](../../docs/adrs/001-single-page-application.md)
```

When deprecated, add:

```markdown
**Status:** Deprecated

**Superseded by:** [ADR-007](./007-plain-css3.md)
```
