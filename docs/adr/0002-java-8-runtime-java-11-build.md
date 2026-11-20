# 0002. Run on Java 8, build with Java 11

- Status: Accepted
- Date: 2024-11-13 (compiler settings refined on 2026-11-19)
- Issues: #277, #286, #338

## Context

The plugin runs inside its users' builds, so its bytecode level is the
minimum Java version those builds must use. Many projects still build on
Java 8.

Test dependencies moved on: Mockito 5, WireMock 3 and Checkstyle 10 require
Java 11. In #277 the whole project was raised to Java 11 for that reason,
which made the released plugin unusable on Java 8 (reported in #286).

Setting `source`/`target` to 8 while compiling on a newer JDK produces
Java 8 bytecode, but still lets code call APIs that don't exist in Java 8
(for example `String.isBlank()`), which then fails at runtime.

## Decision

We will keep the plugin's runtime requirement at Java 8 and require Java 11
or newer only to build it:

- main code compiles with `maven.compiler.release=8`, so the compiler rejects
  both newer language features and newer JDK APIs;
- test code compiles with `maven.compiler.testRelease=11`;
- `maven-compiler-plugin` is pinned (3.6+ is needed for `release`/`testRelease`;
  otherwise the version depends on the Maven running the build);
- runtime dependencies must support Java 8 (e.g. resilience4j stays on 1.x,
  because 2.x requires Java 17);
- CI builds on Java 11, 17, 21 and 25.

## Alternatives considered

- **Require Java 11 at runtime** (#277) - cuts off users still on Java 8,
  for no benefit to them.
- **Stay on Java 8 test libraries** (Mockito 4, WireMock 2) - those are no
  longer maintained, and we would lose newer test tooling.
- **`source`/`target` 8 instead of `release` 8** - newer JDK API use
  goes unnoticed until it fails on a user's Java 8 build.

## Consequences

- Main code cannot use Java 9+ APIs or language features (`var`,
  `List.of`, `Files.readString`, ...). Tests can use Java 11.
- Every runtime dependency upgrade must check the dependency's minimum Java version.
- CI does not run the plugin on Java 8 itself; `release=8` guards the API
  level at compile time.
- Revisit when dropping Java 8 users becomes acceptable, for example in a
  new major version. That needs a new ADR superseding this one.
