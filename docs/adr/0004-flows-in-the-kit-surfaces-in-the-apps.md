# ADR-0004 · Flows in the kit, surfaces in the apps

Status: accepted, 6 November 2026 (user decision, Mathis: the purchase flow is one code path, tested
once, that behaves the same in every app; surfaces may differ per app, behaviour may not).

## Decision

- Behaviour that must be identical in every app and is expensive to test by hand lives in the kit, as
  one flow with a default surface. The first is the one-time purchase: `AppUnlock` in `:unlock` (the
  one holder every app declares, with `UnlockConfig` and `UnlockTexts`) and `UnlockPanel` /
  `UnlockSheet` in `:unlock-ui` (the purchase controls and the bottom sheet around them).
- Apps own their screens. An app shows the default sheet, puts the panel into a surface of its own, or
  keeps a surface the kit cannot draw; in every case the flow underneath is the kit's. The app decides
  where the purchase is reached from, what it says about its product (`intro`), and which actions sit
  next to the purchase (`actions`). An app never maps the kit's codes itself: `UnlockTexts` carries its
  string ids, `textRes(texts)` does the mapping.
- `:unlock-ui` has no theme of its own. Colours, type and shapes come from the host app's
  `MaterialTheme`; spacing comes through `UnlockStyle`, the only file in the module with sizes, so an
  app passes its tokens. The module has no text: every word comes through `UnlockTexts` or the slots.
- `:unlock-ui` compiles against the Compose BOM the newest app uses (now 2026.09.00, compileSdk 37),
  and every app on it uses the same BOM. Material 3's sheet is an experimental API whose binary shape
  changes between versions; an app on an older BOM would crash at runtime, not fail at compile time.
- A test build of one app is the reference for the flow (Dahlia, user decision F14): the billing test
  matrix of the skill `app-release` runs there once per kit change; the other apps run their checks and
  a look at their surface.

## Reasons

- Three holders, three `when` blocks over the same codes and three purchase surfaces drifted apart,
  and every billing change meant clicking through four apps. Behaviour that is identical by intent
  should be identical by construction.
- A surface in the kit as the default keeps a new app (Paperboat) at a few lines, while an app with a
  design of its own (Weatherfold, whose screens are drawn from a sky palette without `MaterialTheme`)
  keeps its page over the same flow.
- No `:ui` theme layer (user decision, REV-2 11): the apps look different on purpose; the sheet takes
  what the host theme gives.

## Consequences

- `:unlock` and `:unlock-ui` share one version (1.1.1 since the fixes of 6 November 2026, tag
  `unlock-1.1.1`); the apps name both. `UnlockTexts` has sixteen ids, so an app
  provides the five button texts too (buy with price, buy without price, loading price, try again,
  restore), in every language it ships.
- New dependencies in the kit: Compose (BOM, ui, foundation, material3) in `:unlock-ui`, and
  `androidx.annotation` in `:unlock` for `@StringRes`; the Compose compiler plugin joins the root build.
- The constraint on `androidx.fragment` 1.9.1 (Play Console flags Billing's 1.1.0) lives in `:unlock`
  and reaches every app; Dahlia no longer carries it.
- Weatherfold keeps `ProScreen` over `AppUnlock`; its page follows the same rules as the panel (the
  button keeps its place while the price loads, the reason stands under it, Try again next to it).
- The next flow of this kind (a widget debug mark, an app language picker) follows the same split:
  the rule and its default surface in the kit, the place and the words in the app.
