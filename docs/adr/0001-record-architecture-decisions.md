# 0001. Record architecture decisions

- Status: Accepted
- Date: 2026-11-19

## Context

Several decisions in this project are not obvious from the code alone:
why the plugin runs on Java 8 but needs Java 11 to build, why the download
cache index is locked the way it is, why the group ID changed. The reasons
are scattered across issues, PR discussions and commit messages, and
contributors keep running into the same questions (for example, raising the
Java level to pick up a newer dependency).

## Decision

We will record significant decisions as Architecture Decision Records (ADRs)
in `docs/adr/`, one Markdown file per decision, named
`NNNN-short-title.md` and numbered sequentially. New records start from
[`template.md`](template.md): a lightweight Michael Nygard format
(Context, Decision, Consequences) plus a short "Alternatives considered"
section.

A decision deserves an ADR when it:

- constrains users or contributors (supported Java/Maven versions, coordinates, defaults),
- changes on-disk formats or behaviour shared between plugin versions (the download cache),
- or was chosen over a reasonable alternative that someone is likely to propose again.

An accepted ADR is not rewritten. When a decision changes, a new ADR
supersedes it, and the old one only gets its status updated to
"Superseded by NNNN". Typo fixes and added links are fine.

An ADR goes into the same PR as the change it describes, so it is reviewed
together with the code.

## Alternatives considered

- **Keep rationale in commit messages and issues only** - that is what we did;
  it is hard to find and is not kept up to date as decisions change.
- **MADR template** - more structure (decision drivers, per-option pros and
  cons) than this small project needs.
- **adr-tools** - not needed for a handful of files; plain Markdown works
  without extra tooling.

## Consequences

- Rationale lives next to the code and is versioned with it.
- ADRs 0002-0006 were written retroactively from the git history and issues;
  their dates are those of the original change.
- Writing an ADR is a small extra step for significant PRs.
