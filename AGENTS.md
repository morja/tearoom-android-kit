# tearoom-android-kit – rules for agents

Small shared Android libraries for Mathis' apps (tearoom.one family). Apps include this build
through Gradle (ADR-0001); there is no published artifact, the source is public at
github.com/morja/tearoom-android-kit under Apache-2.0. Decisions live in
[docs/adr/](docs/adr/README.md); read them before changing behaviour.

## Modules

- `:unlock` (`one.tearoom:unlock`, 1.1.0) – one permanent one-time purchase through Google Play
  Billing: `AppUnlock` (the one holder every app declares) over `OneTimeUnlock`, `UnlockConfig`,
  `UnlockTexts` with `textRes`, `UnlockState`, pure rules in `UnlockPolicy`. Verification stays on the
  device (ADR-0002); the flow is the kit's, the surface the app's (ADR-0004).
- `:unlock-ui` (`one.tearoom:unlock-ui`, 1.1.0) – the default purchase surface: `UnlockPanel` (buy,
  try again, restore, Play's feedback) and `UnlockSheet` (a Material 3 bottom sheet around it with
  title, intro and body slots), drawn from the host app's `MaterialTheme`; spacing through
  `UnlockStyle`, the only file with sizes. No theme, no text of its own.
- `:testing` (`one.tearoom:testing`) – the rules behind the apps' guardrail tests as a pure JVM
  library (ADR-0003): `Guardrails.sources`, `colourLiterals`, `rawSizes`, `forbiddenImports`, `onlyIn`,
  `translations`, plus `Baseline` for counted exceptions that may only shrink. No Android, no test
  framework; rules return `Hit`s or a `TranslationReport`, the app's test asserts.

## Rules

1. No user-facing text in the library. It exposes codes (`UnlockMessage`, `PriceProblem`) and takes
   the app's string ids in `UnlockTexts`, in all the app's languages; the mapping is `textRes(texts)`,
   never a `when` in an app.
2. No app-specific values (product ids, prefs names, keys); everything comes through `UnlockConfig`.
3. Plain `com.android.billingclient:billing` (callbacks), not `billing-ktx`.
4. AGP, Kotlin and Gradle follow the newest app that includes this build (now Dahlia: AGP 9.4.1,
   built-in Kotlin 2.4.20, Gradle 9.8.0; the Kotlin JVM plugin of `:testing` and the Compose plugin of
   `:unlock-ui` match that Kotlin). minSdk 26, Java 17 bytecode. `:unlock` compiles against 36 and may
   stay below an app's compileSdk; `:unlock-ui` compiles against the Compose BOM the newest app uses
   (2026.09.00, compileSdk 37) and every app on it uses the same BOM (ADR-0004).
5. Approval needed for new dependencies, new modules and any deviation from an ADR.
6. Behaviour changes need a test at the SDK boundary (`OneTimeUnlockTest`: Robolectric, mockk,
   paused main looper), in `UnlockPolicyTest`, or for the holder in `AppUnlockTest`/`UnlockTextsTest`;
   a rule change in `:testing` needs a case in its fixture tree (`testing/src/test/fixtures`) and
   `GuardrailsTest`/`TranslationsTest`. A change to the flow is tested once by hand on Dahlia with
   the billing test matrix of the skill `app-release` (ADR-0004).
7. Every app that includes the library must still pass its own checks after a change here; the
   library has no version pinning between apps.
8. Never log purchase tokens, order ids or signatures.

## Checks

- `scripts/check` – unit tests of all three modules, lint and release builds of `:unlock` and `:unlock-ui`.

Agents work on branches, never push, merge into another repo or publish.
