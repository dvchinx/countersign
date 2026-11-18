# 1. Multi-module Maven build and platform baseline

- Status: Accepted
- Date: 2026-11-18

## Context

Countersign ships as a library that Spring Boot teams add to their own applications. Three constraints shape
the build:

- The domain (`countersign-core`) must stay framework-free, so it can be tested in isolation and reused if
  other adapters appear later.
- Users bring their own parent POM. A library that forces `spring-boot-starter-parent` on them, or that drifts
  from the Spring Boot version they run, causes dependency conflicts that are hard to diagnose.
- Spring AI moves fast. Each Spring AI release is built against one specific Spring Boot version, and mixing
  versions is the most common source of runtime `NoSuchMethodError`s.

## Decision

- **One Maven reactor** with the modules listed in `CLAUDE.md`: `countersign-core`, `countersign-spring-ai`,
  `countersign-spring-boot-starter`, `countersign-audit-jdbc`, `countersign-test` and `examples/*`.
- **Import the platform BOMs; do not inherit from them.** The root POM imports `spring-boot-dependencies` and
  `spring-ai-bom` in `dependencyManagement`. Our published POMs never declare a Spring Boot parent.
- **Baseline: Spring Boot 4.1.2 and Spring AI 2.0.2**, the latest stable releases at the time of writing.
  Spring AI 2.0.2 is itself built against Spring Boot 4.1.2, and it includes the fix for CVE-2026-59318
  (the minimum is 2.0.1). The two versions are upgraded together.
- **Java 21** via `maven.compiler.release`, enforced by `maven-enforcer-plugin`, along with Maven 3.9 or later.
- **Strict compilation:** `-Xlint:all -Werror`. A warning in a security library is a bug we have not looked at yet.
- **Formatting:** Spotless with Palantir Java Format, checked in `verify`. Run `./mvnw spotless:apply` to fix.
- **Coverage:** JaCoCo in an opt-in `coverage` profile, so the default build stays fast.
- **Maven Wrapper** (`only-script` type, Maven 3.9.16, SHA-256 pinned), so `./mvnw verify` works on a fresh
  clone with only JDK 21 and Docker.
- **The framework-free core is enforced at the bytecode level** by `CoreArchitectureTest` (ArchUnit). Core
  classes may depend only on `java..`, `org.jspecify..` and the core itself. Adding the JSON library will
  require its own ADR and one new allowed package in that test.
- **Examples are part of the build**, so they compile and run their tests in CI, but they are never deployed
  (`maven.deploy.skip`).

## Consequences

- Users on a different Spring Boot 4.x minor version can still use Countersign. The versions they get are the
  ones their own build resolves, and we document the version we test against.
- Upgrading Spring Boot or Spring AI means changing two properties in the root POM, checking that the pair is
  compatible, and running the full build.
- `-Werror` makes new compiler warnings fail the build. Suppressions must be local and justified in a comment.
