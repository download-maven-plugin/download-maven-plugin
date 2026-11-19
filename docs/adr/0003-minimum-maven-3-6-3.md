# 0003. Require Maven 3.6.3 or newer

- Status: Accepted
- Date: 2024-10-05
- Issues: #280

## Context

Plugin versions before 1.11.0 supported Maven 3.2.5 and newer. Maven versions
before 3.6.3 are end of life under the
[Maven compatibility plan](https://maven.apache.org/developers/compatibility-plan.html).
Supporting them kept the plugin on old Maven APIs and plugin-tools versions,
and every change had to be tested against them.

## Decision

Starting with plugin version 1.11.0, we will require Maven 3.6.3 or newer.
The `maven.version` property sets both the Maven API dependencies and
`<prerequisites>`, which becomes `requiredMavenVersion` in `plugin.xml`, so
Maven refuses to run the plugin on older versions.

CI and `.tool-versions` use exactly 3.6.3 to verify the minimum.

## Alternatives considered

- **Keep 3.2.5** - ties the plugin to EOL Maven versions and old APIs.
- **Require the latest Maven 3.9.x** - unnecessarily excludes users on
  still-supported Maven versions.

## Consequences

- Newer Maven and plugin-tools APIs are available, as long as they exist in 3.6.3.
- Users on older Maven must stay on plugin 1.10.x or older (documented in the README).
- The minimum Maven version is raised only together with the Maven
  compatibility plan, and needs a new ADR.
