# tearoom-android-kit – rules for agents

Small shared Android libraries for Mathis' apps (tearoom.one family). Apps include this build
through Gradle (ADR-0001); there is no published artifact, the source is public at
github.com/morja/tearoom-android-kit under Apache-2.0. Decisions live in
[docs/adr/](docs/adr/README.md); read them before changing behaviour.

## Modules

- `:unlock` (`one.tearoom:unlock`, 1.1.1) – one permanent one-time purchase through Google Play
  Billing: `AppUnlock` (the one holder every app declares) over `OneTimeUnlock`, `UnlockConfig`,
  `UnlockTexts` with `textRes`, `UnlockState`, pure rules in `UnlockPolicy`. Verification stays on the
  device (ADR-0002); the flow is the kit's, the surface the app's (ADR-0004).
- `:unlock-ui` (`one.tearoom:unlock-ui`, 1.1.1) – the default purchase surface: `UnlockPanel` (buy,
  try again, restore, Play's feedback) and `UnlockSheet` (a Material 3 bottom sheet around it with
  title, intro and body slots), drawn from the host app's `MaterialTheme`; spacing through
  `UnlockStyle`, the only file with sizes. No theme, no text of its own.
- `:permissions` (`one.tearoom:permissions`, 1.1.0) – one flow for asking, losing and recovering a
  runtime permission (ADR-0005) or a special app access (notification access, ADR-0007): `AppPermission` (one per kind and app, with `PermissionTexts` and
  `Explain`), `PermissionKind` with the Android version branches, `PermissionFlow` over the Activity
  Result APIs (`AppPermission.request(activity)`), pure rules in `PermissionPolicy`, the settings
  intents (the app's pages and the page of one notification channel) and the system's option label in
  `PermissionSettings`. No grant is cached; only the mark
  "answered without a dialog" lives in the kit's own preferences file.
- `:permissions-ui` (`one.tearoom:permissions-ui`, 1.0.0) – the default permission surface:
  `rememberPermissionFlow` (the flow for a Compose screen), `PermissionSheet` (the reason before the
  dialog, the settings path when blocked) and `PermissionHint` (the persistent line on a screen whose
  feature waits for its permission), drawn from the host app's `MaterialTheme`; spacing through
  `PermissionStyle`, the only file with sizes. No theme, no text of its own.
- `:review` (`one.tearoom:review`, 1.0.0) – the one rating prompt (ADR-0006): `AppReview` (one per
  app, `ReviewConfig` with the Play id and the calm delay) counts opening days, asks Google Play's
  In-App Review once the rule of `ReviewPolicy` is met and the app's main screen is calm, and opens
  the Play listing for the app's "Rate the app" row (`openListing`). The Play call sits behind
  `ReviewLauncher`; test builds force it through `FakeReviewManager` (`forceNext`, `reset`,
  `ReviewDebugReceiver`). No surface: the row is the app's.
- `:testing` (`one.tearoom:testing`) – the rules behind the apps' guardrail tests as a pure JVM
  library (ADR-0003): `Guardrails.sources`, `colourLiterals`, `rawSizes`, `forbiddenImports`, `onlyIn`,
  `translations`, plus `Baseline` for counted exceptions that may only shrink. No Android, no test
  framework; rules return `Hit`s or a `TranslationReport`, the app's test asserts.

## Rules

1. No user-facing text in the library. It exposes codes (`UnlockMessage`, `PriceProblem`,
   `PermissionStatus`) and takes the app's string ids in `UnlockTexts` and `PermissionTexts`, in all
   the app's languages; the mapping is `textRes(texts)`, never a `when` in an app.
2. No app-specific values (product ids, prefs names, keys); everything comes through `UnlockConfig`
   and `AppPermission`. An app never names a manifest permission or checks `Build.VERSION` for one;
   it declares a `PermissionKind`.
3. Plain `com.android.billingclient:billing` (callbacks), not `billing-ktx`.
4. AGP, Kotlin and Gradle follow the newest app that includes this build (now Dahlia: AGP 9.4.1,
   built-in Kotlin 2.4.20, Gradle 9.8.0; the Kotlin JVM plugin of `:testing` and the Compose plugin of
   the `-ui` modules match that Kotlin). minSdk 26, Java 17 bytecode. `:unlock`, `:permissions` and `:review`
   compile against 36 and may stay below an app's compileSdk; `:unlock-ui` and `:permissions-ui`
   compile against the Compose BOM the newest app uses (2026.09.00, compileSdk 37) and every app on
   them uses the same BOM (ADR-0004).
5. Approval needed for new dependencies, new modules and any deviation from an ADR.
6. Behaviour changes need a test at the SDK boundary (`OneTimeUnlockTest`: Robolectric, mockk,
   paused main looper), in `UnlockPolicyTest`, or for the holder in `AppUnlockTest`/`UnlockTextsTest`;
   in `:permissions` a case in `PermissionPolicyTest` or `PermissionFlowTest` (the fake device) and,
   at the Activity Result boundary, `ActivityPlatformTest` (Robolectric); a new version branch a line
   in `PermissionKindTest`. A rule change in `:testing` needs a case in its fixture tree
   (`testing/src/test/fixtures`) and `GuardrailsTest`/`TranslationsTest`. A change to a flow is tested
   once by hand on its reference app: the purchase on Dahlia with the billing test matrix of the
   skill `app-release`, the permissions on Weatherfold (location, background location) and Paperboat
   (notifications) and LSX Switch (notification access) with the lifecycle matrix of the skill
   `android-permissions` (ADR-0004, ADR-0005, ADR-0007).
   In `:review` a case in `ReviewPolicyTest` or, at the Play and lifecycle boundary, `AppReviewTest`
   (Robolectric, a fake `ReviewLauncher`).
7. Every app that includes the library must still pass its own checks after a change here; the
   library has no version pinning between apps.
8. Never log purchase tokens, order ids or signatures.

## Checks

- `scripts/check` – unit tests of all six modules, lint and release builds of the five Android ones.

Agents work on branches. Committing, merging and pushing follow the global rules. They never merge into another repo and never publish.
