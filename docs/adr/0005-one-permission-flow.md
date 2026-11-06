# ADR-0005 · One permission flow for every app

Status: accepted, 6 November 2026 (user decision, Mathis: every permission kind of the skill
`android-permissions` at once, F16; Google's Activity Result APIs rather than Accompanist).

## Decision

- Asking for, losing and recovering a runtime permission is one flow in the kit, following ADR-0004:
  the behaviour in `:permissions` (`AppPermission`, `PermissionKind`, `PermissionTexts`,
  `PermissionFlow`, the pure rules in `PermissionPolicy`), a default surface in `:permissions-ui`
  (`rememberPermissionFlow`, `PermissionSheet`, `PermissionHint`) drawn from the host app's
  `MaterialTheme`. Apps own the words, the place and, where they want, the surface.
- One kind per feature, never a manifest permission by name: location (approximate, precise, precise
  required), background location as the second step after the foreground location, notifications,
  photos and media, nearby devices, camera, microphone, and physical activity (Lazy Moves' departures,
  not in the skill but the same flow). The kind holds the Android version branches
  (notifications from 13, background location from 10, the media eras up to the selected photos of
  14, Bluetooth permissions from 12 with the fine location before), so an app never checks
  `Build.VERSION` for a permission.
- The flow reads the system on every use and caches no grant; "only this time" is a grant until the
  app is left and nothing more. The one thing kept, in the kit's own preferences file, is a mark that
  Android answered a request without a dialog, so the next start shows the settings path instead of
  a dead button. A grant or a new rationale clears it.
- Blocked is read from Android, not guessed from the user: after the second refusal Android stops
  explaining the permission (`shouldShowRequestPermissionRationale` true before the request, false
  after); where it never explained (a later process, "Don't allow" in the settings) a silent answer
  shows in its speed, under a second (Weatherfold's measurement, 5 November 2026).
- The reason comes first: the kit asks for its reason sheet before the first dialog and after a
  refusal (`Explain.SHEET`), or the screen that starts the feature carries the reason and the dialog
  opens at once (`Explain.SCREEN`, Weatherfold's welcome and disclosure pages). In both modes a
  blocked permission shows the settings path.
- The background location is a two-step flow from one tap: the foreground location first, then,
  once it is granted, the background location, with the reason (Play's prominent disclosure) in
  between where the app says so. Requesting both in one call is never done.
- The settings deep link lands on the app's notification page for notifications and on App info for
  everything else; the app's `blocked` text names the rest of the way, with `%1$s` for the option to
  pick: Android's own name of "Allow all the time" in the language of the settings for the
  background location, the permission group's label otherwise.
- Tested in the kit: the version branches and the rules on the JVM, the flow against a fake device
  (every row of the skill's matrix a flow can see), the Activity Result binding, the marks and the
  settings intents with Robolectric. On a device the skill's lifecycle matrix runs once per kit
  change on the reference apps (Weatherfold for the location and the background location, Paperboat
  for notifications); the other apps run their checks and a look at their surface.

## Reasons

- Weatherfold measured silent answers with a clock, Lazy Moves stored a "blocked" flag, Paperboat
  asked on every resume with the old `requestPermissions`; three ways to find out the same thing, one
  of them against the skill. A flow that is identical by intent should be identical by construction.
- The matrix takes an hour per app and kind by hand. One flow, tested once on a reference app, is
  the only way it keeps running before every release.
- Activity Result APIs over Accompanist (user decision): Google's own, no extra dependency, and the
  same launcher in an activity and in Compose.

## Consequences

- New modules `:permissions` 1.0.0 (`androidx.activity`, coroutines, annotation) and
  `:permissions-ui` 1.0.0 (Compose BOM 2026.09.00, activity-compose, lifecycle-runtime-compose); the
  apps name both. Weatherfold takes only `rememberPermissionFlow` from the second and draws its own
  pages; Health Connect's permissions (Lazy Moves) stay outside, they have their own contract.
- `PermissionTexts` has six ids per kind (reason, denied, blocked, allow, open settings, not now) and
  an optional seventh for a partial grant, in every language the app ships. The kit's manifest adds
  the `<queries>` entry for the permission controller, which Weatherfold carried alone.
- Weatherfold keeps its own pages over the flow (`Explain.SCREEN`); its timing heuristic and its
  settings helper move into the kit. Paperboat asks once, with the reason, when the first device is
  paired, and shows the kit's hint on its screen while the notification stays off. Lazy Moves follows
  with a hand-over note (its tree was in use on 6 November 2026).
- Dahlia has no runtime permission and stays off the module.
