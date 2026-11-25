## What and why

<!-- What does this change, and what problem does it solve? Link the issue: "Closes #123". -->

## Checklist

- [ ] `./mvnw verify` passes locally
- [ ] Fails closed: every new error path refuses the call, and a test proves it
- [ ] No argument values, prompts, tool results or secrets are logged or persisted
- [ ] Javadoc on new public types and methods explains why
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] `README.md` and `README.es.md` updated if the API or configuration changed
- [ ] ADR added if this is a significant decision (new module, new dependency, default behavior)
