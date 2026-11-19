# 0004. Publish under the `io.github.download-maven-plugin` group ID

- Status: Accepted
- Date: 2024-11-27
- Issues: #248, #249

## Context

The plugin was published as `com.googlecode.maven-download-plugin`, from when
the project was hosted on Google Code. Google Code has been shut down and
the project lives on GitHub, so the group ID made the project look abandoned
(#249). The GitHub organization and repository were renamed to
`download-maven-plugin` (#248).

Maven Central only allows publishing under a namespace whose ownership is
verified. For GitHub-hosted projects that is `io.github.<organization>`.

## Decision

Starting with 2.0.0 we will publish under the verified namespace
`io.github.download-maven-plugin`, with artifact ID `download-maven-plugin`.
Java packages moved to `io.github.download.maven.plugin` at the same time.
The README says so prominently at the top.

## Alternatives considered

- **Keep `com.googlecode.maven-download-plugin`** - points to a defunct host.
- **`com.github.*`** - Sonatype no longer issues new `com.github` namespaces;
  `io.github` is the current convention.

## Consequences

- Users must change the group ID to upgrade to 2.0.0 or later; versions up
  to 1.13.0 remain available under the old coordinates.
- The major version bump marks the coordinate change.
- Releases are published to the verified namespace by the GitHub release workflow.
