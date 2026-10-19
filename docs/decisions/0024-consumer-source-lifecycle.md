# Consumer source lifecycle

Date: 2026-10-18

Status: Accepted

## Context

WorldPrompt developed contracts in the Mycelium checkout selected by its build. Its source patch made that work
reproducible, but preparing and refreshing it depended on consumer-owned Python scripts. An ignored compiler checkout
could also hold uncaptured work. Other consumers need the same lifecycle without maintaining another implementation.

## Options

Keep source preparation in each mod; add independent contract search paths; publish a package service; or maintain a
small source wrapper around Git with a committed source recipe. Independent registries and package resolution would
change contract ownership and introduce another dependency system. The source wrapper preserves the existing model.

## Decision

Build a Java 17 wrapper with the existing Gson dependency. Ship it with sources, licences, launchers and maintained
Gradle hooks. Its version is independent of the compiler revision. Git handles transport, normalization and merge.
The recipe records a full base commit, optional content-addressed binary patch, effective tree and completion policy.

Preparation uses a fresh directory and never resets an existing checkout. Capture uses a private index and validates
the patch round trip before an atomic lock replacement. Updates merge captured work into an isolated candidate;
conflicts remain available to ordinary Git tools. The active source and recipe are unchanged by update.

Verify the default compiler before loading its plugin. Explicit development overrides may differ and report measured
source identity. A consumer can require its captured source at completion and jar assembly. The same verifier runs at
both boundaries, without fetching during inspection. Jar provenance distinguishes expected source from actual source.

## Evidence

The wrapper's temporary-repository self-test exercises source preservation, binary patches, deletions, ignore rules,
Windows line endings, updates and conflict retention. It is part of the repository's `check`. WorldPrompt is the
independent consumer for the maintained Gradle hooks. Dated run results belong in `CHANGELOG.md`.

## Consequences

Contracts still live in the selected Mycelium checkout or fork and use its registry. No upstream approval is needed.
The wrapper installs no Java or Gradle toolchains and does not publish or manage credentials. Git-visible source
identity is not a complete build-input sandbox. Existing proof fingerprint semantics remain unchanged; modifying
the proof procedure requires its own review and validation.
