---
description: How to commit code changes following conventional commit discipline
---

# Commit Workflow

## Golden Rule
**Commit after every logical unit of work.** Never batch multiple unrelated changes into one commit.

## Commit Message Format

Use [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>: <concise description>
```

### Types

| Type | When to use | Example |
|------|-------------|---------|
| `feat` | New user-facing feature | `feat: wallet NWC setup flow` |
| `fix` | Bug fix | `fix: relay auth no longer loops on 403` |
| `refactor` | Code restructuring, no behavior change | `refactor: extract RelayOrb composable` |
| `chore` | Build, deps, tooling, docs | `chore: bump Ktor to 3.1.0` |
| `perf` | Performance improvement | `perf: O(n) list merge in ProfileFeed` |
| `style` | UI/visual-only changes | `style: darken relay orb inactive state` |

### Rules
1. Keep the description under 72 characters
2. Use imperative mood ("add", "fix", "move" — not "added", "fixing", "moved")
3. No period at the end
4. Lowercase after the colon

## During Development

```
1. Start working on a task
2. Get it to a state where it compiles (or is a coherent partial step)
3. Stage only relevant files: git add -p (or git add <specific files>)
4. Commit: git commit -m "fix: description"
5. Continue to the next task
6. Repeat
```

## During Refactoring Moves

When moving files between packages:
1. Move files to the new directory
2. Update package declarations
3. Fix broken imports
4. Verify build compiles
5. Commit: `refactor: move {what} to {where}`
6. **Never mix logic changes with file moves**

## Release Commits

When cutting a release, the version bump should be its own isolated commit:
```
chore: bump version to v0.5.22
```

This commit should touch exactly 3 files:
- `app/build.gradle.kts` (versionCode + versionName)
- `CHANGELOG.md` (new section)
- Any version string constants

## AI-Assisted Sessions

When Antigravity (or any AI assistant) completes a logical unit of work:
// turbo
1. Stage and commit the changes with an appropriate conventional commit message
2. Do NOT wait until the end of the session to commit
3. Each commit should be independently revertible
