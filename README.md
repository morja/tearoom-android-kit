# tearoom-android-kit

Shared Android code for the tearoom.one apps. Modules: `unlock`, a hardened one-time Pro/Plus unlock
through Google Play Billing (taken over from Lazy Moves, billing hardening of 16 October 2026) with
`AppUnlock` as the one holder every app declares; `unlock-ui`, the default purchase surface in Compose
and Material 3; and `testing`, the rules behind the apps' guardrail tests as a pure JVM library. The
rule behind the first two: flows in the kit, surfaces in the apps (ADR-0004).

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
implementation("one.tearoom:unlock:1.1.0")
implementation("one.tearoom:unlock-ui:1.1.0") // the default purchase sheet; apps with their own surface skip it
testImplementation("one.tearoom:testing:1.0.0")
```

`unlock-ui` compiles against the Compose BOM 2026.09.00 (compileSdk 37); an app on it uses the same BOM.

To use another checkout, set the property once in `~/.gradle/gradle.properties`:

```properties
tearoomKitPath=/path/to/tearoom-android-kit
```

or per call: `./gradlew -PtearoomKitPath=/path/to/kit assembleDebug`.

## unlock in short

```kotlin
// One top-level value per app; every screen, widget and worker asks it.
val Pro = AppUnlock(
    UnlockConfig(
        productId = "pro",
        prefsName = "pro",
        prefsKey = "unlocked",
        publicKeyBase64 = null, // Play Console licence key to check signatures locally; null = off
        debugUnlocked = { it.resources.getBoolean(R.bool.pro_for_testing) }, // the app's own mechanism
    ),
    UnlockTexts(
        unlocked = R.string.pro_thanks, pending = R.string.pro_pending, activeHere = R.string.pro_active_here,
        notFound = R.string.pro_not_found, playUnreachable = R.string.pro_play_unreachable,
        priceRefreshing = R.string.pro_price_refreshing, purchaseNotStarted = R.string.pro_purchase_not_started,
        purchaseIncomplete = R.string.pro_purchase_incomplete, billingUnavailable = R.string.pro_billing_unavailable,
        productUnavailable = R.string.pro_product_unavailable, playUnavailable = R.string.pro_play_unavailable,
        buy = R.string.pro_buy, buyWithoutPrice = R.string.pro_buy_no_price, loadingPrice = R.string.pro_loading_price,
        retry = R.string.pro_retry, restore = R.string.pro_restore,
    ),
)
```

- `AppUnlock` binds to the application context on the first call, so any context will do. Screens
  collect `Pro.state(context)` (`UnlockState`: `unlocked`, `purchased`, `price`, `loadingPrice`,
  `pending`, `busy`, `priceProblem`, `message`, `canBuy`); background work calls
  `Pro.isUnlocked(context)`, which only reads the cache.
- Call `Pro.refresh(context)` on every resume, `Pro.buy(activity)` from the buy button,
  `Pro.restore(context)` from a restore action, `Pro.clearMessage(context)` once a message was shown
  (the kit's sheet does that itself). `Pro.messages(context)` gives each piece of feedback once, for
  an app that shows it outside the purchase surface.
- Texts: `UnlockTexts` carries the app's string ids in every language the app ships; `textRes(texts)`
  on `UnlockMessage` and `PriceProblem` maps the codes. `notFound` must say that the purchase may
  belong to another Google account. `buy` takes the price as `%s`.

Behaviour: 15 s timeout per Play request, two retries (2 s, 4 s) for transient errors, late answers
ignored, the shown price kept while a refresh runs, the cached unlock kept when Play cannot be
reached, removed after a successful answer without the purchase (refund), pending payments never
unlock, acknowledgement retried, rental and preorder offers never sold as the permanent unlock.

## unlock-ui in short

```kotlin
if (showingPro) {
    UnlockSheet(
        Pro,
        title = stringResource(R.string.pro_title),
        onDismiss = { showingPro = false },
        style = UnlockStyle(horizontalPadding = Spacing.screen, gap = Spacing.gap, groupGap = Spacing.row),
        intro = { Text(stringResource(R.string.pro_intro)) },          // what the unlock includes
        actions = { /* buttons that belong to the purchase, e.g. "Continue without" */ },
    )
}
```

- `UnlockSheet` is a Material 3 bottom sheet drawn from the host app's `MaterialTheme`: title, the
  app's `intro`, then `UnlockPanel`. Owned: the thanks line. Otherwise the buy button with the price
  (kept in place while the price loads or is missing), the app's `actions`, Play's feedback (pending,
  why there is no price, the last purchase or restore), "Try again" while there is no price, and
  "Restore purchase". The message is cleared when the sheet goes.
- `UnlockPanel` alone goes into a surface of the app's own (Lazy Moves' Settings card). An app whose
  screens have no `MaterialTheme` keeps its page over `AppUnlock` (Weatherfold's `ProScreen`).
- `UnlockStyle` holds the spacing (`horizontalPadding`, `bottomPadding`, `gap` inside a group,
  `groupGap` between groups, `fullWidthButton`); pass the app's tokens. `body` replaces the panel for
  an app with another layout over the same flow.

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

Since 27 October 2026 no app has its own Play Billing code; since 6 November 2026 each declares one
`AppUnlock` with its `UnlockTexts` and no longer maps the kit's codes itself (ADR-0004).

| App | Product | Prefs (name/key) | Debug unlock | Surface | Kit tested with |
|---|---|---|---|---|---|
| Dahlia | `pro` | `pro` / `owned` | resource `pro_for_testing` (debug and fast builds); the `free` build counts as not Pro | `UnlockSheet` with Dahlia's intro and "Continue without" | unlock 1.1.0, unlock-ui 1.1.0 (reference app for the billing test matrix, QA-5) |
| Lazy Moves | `plus_unlock` | `plus` / `unlocked` | debug build with the `pref_debug_simulate_plus` setting | `UnlockPanel` in the Plus card of Settings | unlock 1.1.0, unlock-ui 1.1.0 (checks only) |
| Weatherfold | `pro` | `pro` / `unlocked` | debug build unless the `debug_free` preference is set | own `ProScreen` (sky palette, no `MaterialTheme`) over `AppUnlock` | unlock 1.1.0 (checks only) |
| Paperboat | `pro` | `pro` / `unlocked` | debug build (`FLAG_DEBUGGABLE`) | `UnlockSheet` from a Pro row in Settings; not released yet | unlock 1.1.0, unlock-ui 1.1.0 (checks only) |

Since 6 November 2026 all four apps and the Cockpit template run their guardrail tests on `testing`
(Lazy Moves with a counted baseline for raw sizes).

## Checks

```sh
scripts/check
```

## Licence

Apache License 2.0, see [LICENSE](LICENSE).
