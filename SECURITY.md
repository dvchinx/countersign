# Security policy

Countersign decides whether an AI agent's tool calls run. A bug that lets a call through when it should be refused
is a security vulnerability, even if it looks like an ordinary bug.

## Supported versions

Countersign is pre-1.0. Fixes go into the latest minor release only.

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |

## Reporting a vulnerability

Please report privately through
[GitHub's private vulnerability reporting](https://github.com/dvchinx/countersign/security/advisories/new).
Do not open a public issue, pull request or discussion.

Include what you can: the affected version, the configuration or policies involved, steps or a test that reproduces
it, and what an attacker gains. Reports in English or Spanish are welcome.

Countersign is maintained by volunteers. We aim to acknowledge a report within 7 days and to agree on a disclosure
date with you. Fixes are released with a GitHub security advisory and, where it applies, a CVE. We credit reporters
who want to be credited.

## What counts

Examples of what we treat as vulnerabilities:

- A tool runs although its decision is `DENY`, a required approval was rejected or timed out, or an error occurred
  (a fail-open path).
- A tool call reaches the real tool without passing through the guard.
- A policy condition escapes the SpEL sandbox: type references, method calls on arbitrary types, bean access.
- A receipt can be edited, deleted, inserted or reordered without `ReceiptVerifier` detecting it, or forged without
  the signing key.
- Raw tool arguments, prompts, tool results or keys are logged or persisted when the user has not enabled it.
- Someone can approve a call they are not allowed to approve, through a gateway shipped with Countersign.

Usually not vulnerabilities, but tell us if you are unsure:

- Behavior that follows from the configuration, such as `default-decision: allow` or a policy that allows too much.
- An agent being persuaded by prompt injection to make a call that the policies allow. Countersign enforces
  policies; it does not judge intent.
- Leaked keys that were committed or exposed by the application itself.
- Vulnerabilities in Spring AI, Spring Boot or other dependencies; please report those upstream. Do tell us if
  Countersign needs a new minimum version because of one.
