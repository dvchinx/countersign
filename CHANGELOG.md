# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `countersign-core` domain model: `ToolInvocation`, the sealed `Decision` (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`),
  `EvaluationResult` and an immutable JSON tree for tool arguments
  ([ADR 0002](docs/adr/0002-tool-arguments-as-core-json-tree.md)).
- `PolicyEngine`: policies evaluated in priority order, first match wins, configurable default decision, and
  fail-closed denial when a condition throws.
- `Policy` with tool globs (`db.*`) and pluggable `Condition`s.
- `SpelCondition` in `countersign-spring-ai`: SpEL conditions in a read-only `SimpleEvaluationContext` (no type
  references, constructors, bean references, method calls or assignment).
- Multi-module Maven build: `countersign-core`, `countersign-spring-ai`, `countersign-spring-boot-starter`,
  `countersign-audit-jdbc`, `countersign-test`, and the `banking-agent` and `mcp-server-guarded` examples.
- Platform baseline: Java 21, Spring Boot 4.1.2, Spring AI 2.0.2 ([ADR 0001](docs/adr/0001-multi-module-build-and-platform-baseline.md)).
- ArchUnit rule that keeps `countersign-core` free of Spring and other frameworks.
- Maven Wrapper, Spotless (Palantir Java Format), JaCoCo `coverage` profile and GitHub Actions CI.
- Apache 2.0 license, README in English and Spanish.
