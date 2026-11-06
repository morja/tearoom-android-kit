# tearoom-android-kit – rules for agents

Small shared Android libraries for Mathis' apps (tearoom.one family). Apps include this build
through Gradle (ADR-0001); there is no published artifact, the source is public at
github.com/morja/tearoom-android-kit under Apache-2.0. Decisions live in
[docs/adr/](docs/adr/README.md); read them before changing behaviour.

## Modules

- `:unlock` (`one.tearoom:unlock`) – one permanent one-time purchase through Google Play Billing:
  `OneTimeUnlock`, `UnlockConfig`, `UnlockState`, pure rules in `UnlockPolicy`. Verification stays on
  the device (ADR-0002).
- `:testing` (`one.tearoom:testing`) – the rules behind the apps' guardrail tests as a pure JVM
  library (ADR-0003): `Guardrails.sources`, `colourLiterals`, `rawSizes`, `forbiddenImports`, `onlyIn`,
  `translations`, plus `Baseline` for counted exceptions that may only shrink. No Android, no test
  framework; rules return `Hit`s or a `TranslationReport`, the app's test asserts.

## Rules

1. No user-facing text in the library. It exposes codes (`UnlockMessage`, `PriceProblem`); each app
   maps them to its own string resources in all its languages.
2. No app-specific values (product ids, prefs names, keys); everything comes through `UnlockConfig`.
3. Plain `com.android.billingclient:billing` (callbacks), not `billing-ktx`.
4. AGP, Kotlin and Gradle follow the newest app that includes this build (now Dahlia: AGP 9.4.1,
   built-in Kotlin 2.4.20, Gradle 9.8.0; the Kotlin JVM plugin of `:testing` matches that Kotlin).
   minSdk 26, compileSdk 36, Java 17 bytecode. The kit's compileSdk may stay below an app's; an app
   never has to lower its own to match.
5. Approval needed for new dependencies, new modules and any deviation from an ADR.
6. Behaviour changes need a test at the SDK boundary (`OneTimeUnlockTest`: Robolectric, mockk,
   paused main looper) or in `UnlockPolicyTest`; a rule change in `:testing` needs a case in its
   fixture tree (`testing/src/test/fixtures`) and `GuardrailsTest`/`TranslationsTest`.
7. Every app that includes the library must still pass its own checks after a change here; the
   library has no version pinning between apps.
8. Never log purchase tokens, order ids or signatures.

## Checks

- `scripts/check` – unit tests of both modules, lint and release build of `:unlock`.

Agents work on branches, never push, merge into another repo or publish.
