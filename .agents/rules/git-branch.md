# Git Branch Workflow

Standard branch strategy for all tasks.

## Branch Naming

Format: `<project>/<milestone>/<task>`

- `<project>`: v1, v2, etc.
- `<milestone>`: Phase number (0, 1, 2, ...) or name
- `<task>`: Kebab-case (e.g., `auth-validation`, `ui-refinement`)

**Examples:** `v1/0/schema-design`, `v1/1/auth-api`, `v1/2/ui-polish`

## Commits

[Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): subject

body (optional)
```

**Types:** `feat`, `fix`, `docs`, `test`, `refactor`, `chore`
**Scope:** Module or component (optional)
**Subject:** Imperative, lowercase, ≤50 chars

One logical change = one commit. Squash trivial changes before merge.

```
feat(auth): add token validation
fix(api): handle expired session gracefully
docs(readme): add setup instructions
```

## Stacking Dependencies

When task depends on unmerged work:

1. Branch off dependency (not main)
2. Keep dependency branch name in history for traceability
3. Rebase when dependency merges: `git rebase --onto main <dependency-branch>`
4. Document dependency in PR

```
main
  └─ v1/0/schema-design (merged)
    └─ v1/1/auth-api (unmerged)
      └─ v1/1/auth-ui (stacked)
```

When `v1/1/auth-api` merges, rebase `v1/1/auth-ui` onto main.

## Workflow

1. Create branch from main (or dependency if stacking)
2. Work with minimal, descriptive commits
3. Self-review with `/caveman-review`
4. Apply all recommendations from `/caveman-review`
5. Push to remote
6. Create PR with task + dependencies
7. Merge to main
8. Delete local + remote branch
9. Rebase stacked branches if dependents exist

## PR Template

```markdown
## Task

Brief summary

## Dependencies

- [ ] Link to merged PR/branch or "none"

## Changes

- Commit summary
- Related design docs/ADRs
```
