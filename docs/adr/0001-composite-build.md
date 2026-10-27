# ADR-0001 · One local repository, included as a Gradle composite build

Status: accepted, 26 October 2026 (user decision, Mathis).

## Decision

- Shared Android code for the tearoom.one apps lives in this repository,
  `~/Work/Projects/apps/tearoom-android-kit`, local only for now (no remote, no published artifact).
- Apps include it with `includeBuild(...)` in their `settings.gradle.kts` and depend on Maven-style
  coordinates such as `one.tearoom:unlock:1.0.0`; Gradle substitutes the included project.
- The path comes from the Gradle property `tearoomKitPath` (for example in
  `~/.gradle/gradle.properties`), with `~/Work/Projects/apps/tearoom-android-kit` as the fallback. Relative
  paths do not fit, since the apps sit at different depths (`~/Work/Projects/...`,
  `~/Work/AndroidStudioProjects/...`). Without the library the build stops with a clear message.

## Reasons

- One place for the hardened billing code instead of three copies (Lazy Moves, Weatherfold, Dahlia).
- A composite build needs no publishing step and lets a change be tested in the apps at once.

## Consequences

- An app cannot be built from a fresh clone of its own repository alone; this matters for Dahlia,
  whose repository is public (GPL). If outside builds become important, publish the library (a public
  repository or Maven) or vendor it; that is a new decision.
- Every app builds against the current state of this repository. Before an app release, run the app's
  checks and note the library commit in the release notes of the app repository.
- AGP and Kotlin of this build follow the apps that include it; an app on an older AGP (Lazy Moves,
  AGP 8.13) upgrades first.
