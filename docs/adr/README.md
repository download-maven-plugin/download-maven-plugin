# Architecture Decision Records

This directory records significant decisions about the plugin: what was
decided, why, and what we gave up. See
[0001](0001-record-architecture-decisions.md) for when to write one and how.

To add a record, copy [`template.md`](template.md) to `NNNN-short-title.md`
with the next free number, and include it in the PR that makes the change.

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-java-8-runtime-java-11-build.md) | Run on Java 8, build with Java 11 | Accepted |
| [0003](0003-minimum-maven-3-6-3.md) | Require Maven 3.6.3 or newer | Accepted |
| [0004](0004-group-id-io-github.md) | Publish under the `io.github.download-maven-plugin` group ID | Accepted |
| [0005](0005-shared-download-cache-locking.md) | Share the download cache safely between processes with file locks | Accepted |
| [0006](0006-revalidate-cache-with-conditional-requests.md) | Revalidate cached files with HTTP conditional requests (`updatePolicy`) | Accepted |
