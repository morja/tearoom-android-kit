# tearoom-android-kit – rules for agents

Small shared Android libraries for Mathis' apps (tearoom.one family). Apps include this build
through Gradle (ADR-0001); there is no published artifact and no remote yet. Decisions live in
[docs/adr/](docs/adr/README.md); read them before changing behaviour.

## Modules

- `:unlock` (`one.tearoom:unlock`) – one permanent one-time purchase through Google Play Billing:
  `OneTimeUnlock`, `UnlockConfig`, `UnlockState`, pure rules in `UnlockPolicy`. Verification stays on
  the device (ADR-0002).

## Rules

1. No user-facing text in the library. It exposes codes (`UnlockMessage`, `PriceProblem`); each app
   maps them to its own string resources in all its languages.
2. No app-specific values (product ids, prefs names, keys); everything comes through `UnlockConfig`.
3. Plain `com.android.billingclient:billing` (callbacks), not `billing-ktx`.
4. AGP, Kotlin and Gradle follow the newest app that includes this build (now Dahlia: AGP 9.4.1,
   built-in Kotlin 2.4.20, Gradle 9.8.0). minSdk 26, compileSdk 36, Java 17 bytecode.
5. Approval needed for new dependencies, new modules and any deviation from an ADR.
6. Behaviour changes need a test at the SDK boundary (`OneTimeUnlockTest`: Robolectric, mockk,
   paused main looper) or in `UnlockPolicyTest`.
7. Every app that includes the library must still pass its own checks after a change here; the
   library has no version pinning between apps.
8. Never log purchase tokens, order ids or signatures.

## Checks

- `scripts/check` – unit tests, lint, release build of the library.

Agents work on branches, never push, merge into another repo or publish.
