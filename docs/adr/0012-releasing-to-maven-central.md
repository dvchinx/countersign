# 12. Releasing to Maven Central

- Status: Accepted
- Date: 2026-11-24

## Context

The MVP is done when `0.1.0` is on Maven Central. Central requires, for every published artifact, a POM with name,
description, URL, license, developers and SCM, a sources jar, a javadoc jar and a GPG signature on each file. Since
2025 uploads go through the Central Portal (`central.sonatype.com`); the legacy OSSRH staging repositories are gone.
The namespace `io.github.dvchinx` is verified on the Portal.

The examples are part of the build (they must compile and pass in CI) but are not a product and must never be
published. Release versions on Central are immutable: a wrong upload cannot be replaced, only superseded.

## Decision

- A `release` profile in the root POM adds `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin` and
  Sonatype's `central-publishing-maven-plugin`, which replaces `maven-deploy-plugin`. Outside the profile, the build
  is unchanged and needs no keys, so `./mvnw verify` still works on a fresh clone.
- Javadoc warnings fail the release build (`javadoc.failOnWarnings`, default `true`). The starter disables only the
  `missing` doclint group: `@ConfigurationProperties` accessors are documented on their fields, which is where the
  configuration processor reads them.
- The examples skip sources and javadoc and are listed in `excludeArtifacts`, so they never reach the bundle.
- The plugin uses `autoPublish=true` and `waitUntil=published`: the build fails if the Portal rejects the bundle,
  and succeeds only once the release is on Central.
- Releases run from GitHub Actions (`.github/workflows/release.yml`) when a `v*` tag is pushed. The workflow refuses a
  tag that does not match the POM version and refuses SNAPSHOT versions, then runs the full test suite as part of
  `deploy`. Credentials are a Portal user token (never the account password) and a GPG key dedicated to signing
  releases, stored as the repository secrets `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY` and
  `GPG_PASSPHRASE`.

Release steps:

1. Move the CHANGELOG's `[Unreleased]` entries under the new version, set the version
   (`./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false`) and update the README badges.
2. Commit, tag `vX.Y.Z`, push the tag; the workflow publishes.
3. Set the next development version (`X.Y.(Z+1)-SNAPSHOT`) and commit.

## Consequences

- Publishing needs no maintainer machine; anyone with push rights to tags can release, so tag pushes should stay
  restricted to maintainers.
- A release can only be as good as `./mvnw verify` at that commit; there is no manual staging review on the Portal.
  `./mvnw -Prelease -Dgpg.skip -DskipUpload=true deploy` (with any `central` server in `settings.xml`) builds the
  exact bundle locally, under `target/central-publishing`, without uploading it.
- New public types in published modules need Javadoc before they can be released, which the CLAUDE.md already asks.
- Snapshots are not published. If users need them later, the same plugin can deploy to the Portal's snapshot
  repository.
