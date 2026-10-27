# tearoom-android-kit

Shared Android code for the tearoom.one apps. First module: `unlock`, a hardened one-time Pro/Plus
unlock through Google Play Billing (taken over from Lazy Moves, billing hardening of 16 October 2026).

## Using it in an app

Clone this repository next to the app repository (same parent folder):

```sh
git clone https://github.com/morja/tearoom-android-kit.git
```

The app includes it as a Gradle composite build (ADR-0001). In the app's `settings.gradle.kts`:

```kotlin
// tearoom-android-kit (https://github.com/morja/tearoom-android-kit): the Gradle property
// tearoomKitPath, else a checkout next to this repository, else ~/Work/Projects/apps/tearoom-android-kit.
val tearoomKit = providers.gradleProperty("tearoomKitPath").orNull
    ?: listOf(
        rootDir.resolve("../tearoom-android-kit"),
        rootDir.resolve("../../tearoom-android-kit"),
        file("${System.getProperty("user.home")}/Work/Projects/apps/tearoom-android-kit"),
    ).firstOrNull { it.resolve("settings.gradle.kts").isFile }?.canonicalPath
    ?: error("tearoom-android-kit not found: clone https://github.com/morja/tearoom-android-kit next to this repository or set tearoomKitPath")
includeBuild(tearoomKit)
```

and in the app module:

```kotlin
implementation("one.tearoom:unlock:1.0.0")
```

To use another checkout, set the property once in `~/.gradle/gradle.properties`:

```properties
tearoomKitPath=/path/to/tearoom-android-kit
```

or per call: `./gradlew -PtearoomKitPath=/path/to/kit assembleDebug`.

## unlock in short

```kotlin
val unlock = OneTimeUnlock(application, UnlockConfig(
    productId = "pro",
    prefsName = "pro",
    prefsKey = "unlocked",
    publicKeyBase64 = null, // Play Console licence key to check signatures locally; null = off
    debugUnlocked = { it.resources.getBoolean(R.bool.pro_for_testing) },
))
```

- One instance per app. Screens collect `unlock.state` (`UnlockState`: `unlocked`, `purchased`,
  `price`, `loadingPrice`, `pending`, `busy`, `priceProblem`, `message`, `canBuy`); background work
  calls `unlock.isUnlocked(context)`, which only reads the cache.
- Call `refresh()` on every resume, `purchase(activity)` from the buy button, `restore()` from a
  restore action, `clearMessage()` once a message was shown.
- Map `UnlockMessage` and `PriceProblem` to the app's strings. `NOT_FOUND` must say that the
  purchase may belong to another Google account.

Behaviour: 15 s timeout per Play request, two retries (2 s, 4 s) for transient errors, late answers
ignored, the shown price kept while a refresh runs, the cached unlock kept when Play cannot be
reached, removed after a successful answer without the purchase (refund), pending payments never
unlock, acknowledgement retried, rental and preorder offers never sold as the permanent unlock.

## Apps on this library

Since 27 October 2026 no app has its own Play Billing code; each keeps one `OneTimeUnlock` in a small
holder (`Pro`/`Plus` object) that maps `UnlockMessage` and `PriceProblem` to its strings.

| App | Product | Prefs (name/key) | Debug unlock |
|---|---|---|---|
| Dahlia | `pro` | `pro` / `owned` | resource `pro_for_testing` (debug and fast builds) |
| Lazy Moves | `plus_unlock` | `plus` / `unlocked` | debug build with the `pref_debug_simulate_plus` setting |
| Weatherfold | `pro` | `pro` / `unlocked` | debug build unless the `debug_free` preference is set |

## Checks

```sh
scripts/check
```

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
