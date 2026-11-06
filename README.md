# tearoom-android-kit

Shared Android code for the tearoom.one apps. Modules: `unlock`, a hardened one-time Pro/Plus unlock
through Google Play Billing (taken over from Lazy Moves, billing hardening of 16 October 2026), and
`testing`, the rules behind the apps' guardrail tests as a pure JVM library.

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
testImplementation("one.tearoom:testing:1.0.0")
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

## testing in short

`one.tearoom.testing.Guardrails` scans sources and resources and returns hits; the app's test asserts
(JUnit 4, JUnit 5 or kotlin.test). Allowed files are paths relative to the source root, a directory
entry such as `ui/theme` covers everything below it.

```kotlin
class GuardrailTest {
    private val sources = Guardrails.sources(Path.of("src/main/java"))
    private val theme = setOf(Path.of("ui/Theme.kt"), Path.of("ui/Components.kt"))

    @Test fun noColourLiterals() = assertNoHits(Guardrails.colourLiterals(sources))
    @Test fun sizesOnlyInTheme() = assertNoHits(Guardrails.rawSizes(sources, allowed = theme))
    @Test fun domainIsPlainKotlin() = assertNoHits(Guardrails.forbiddenImports(sources, mapOf("domain" to listOf("android.", "androidx."))))
    @Test fun oneNetworkClient() = assertNoHits(Guardrails.onlyIn(sources, Regex("openConnection"), setOf(Path.of("data/ApiClient.kt"))))

    @Test fun everyStringIsTranslated() {
        val report = Guardrails.translations(Path.of("src/main/res"), listOf("de", "es", "fr"))
        assertTrue(report.toString(), report.isClean)
    }

    private fun assertNoHits(hits: List<Hit>) = assertTrue(hits.describe(), hits.isEmpty())
}
```

- `sources(vararg roots)` fails when a root is missing or empty, so a wrong path cannot pass.
- `colourLiterals` knows `Color(0x…)`, the named `Color.*` constants, `"#RRGGBB"` and `argb(0x…)`;
  `rawSizes` finds `16.dp`, `14.sp`, `0.5.dp`. Both take another `pattern` when an app differs.
- `forbiddenImports` takes directories (relative to the root, `""` for all) with import prefixes;
  `android.` does not cover `androidx.`.
- `translations` parses every `values*/strings*.xml` as XML: translatable keys in every locale, no
  extra keys, `other` in every plural, equal array sizes, the same format arguments in strings and
  in the `other` item of plurals (`formatted="false"` is skipped). The `TranslationReport` lists
  each problem with locale, key and kind.
- `Baseline` records hits per file (`path: count`) for a rule an app does not meet yet;
  `Baseline.compare(hits, Baseline.read(file))` says whether it `holds` and whether it `isStale`
  (fewer hits than recorded, lower the file). `Baseline.render(hits)` writes a fresh one.

## Apps on this library

Since 27 October 2026 no app has its own Play Billing code; each keeps one `OneTimeUnlock` in a small
holder (`Pro`/`Plus` object) that maps `UnlockMessage` and `PriceProblem` to its strings.

| App | Product | Prefs (name/key) | Debug unlock |
|---|---|---|---|
| Dahlia | `pro` | `pro` / `owned` | resource `pro_for_testing` (debug and fast builds); the `free` build counts as not Pro |
| Lazy Moves | `plus_unlock` | `plus` / `unlocked` | debug build with the `pref_debug_simulate_plus` setting |
| Weatherfold | `pro` | `pro` / `unlocked` | debug build unless the `debug_free` preference is set |
| Paperboat | planned: clipboard sync as Pro (Paperboat ADR-0005), own `Entitlements` until it moves here | – | – |

Since 6 November 2026 all four apps and the Cockpit template run their guardrail tests on `testing`
(Lazy Moves with a counted baseline for raw sizes).

## Checks

```sh
scripts/check
```

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
