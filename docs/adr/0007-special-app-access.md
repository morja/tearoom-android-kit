# ADR-0007 · Special app access in the permission flow: notification access

Status: accepted, 13 November 2026 (user decision, Mathis, in the LSX Switch chat: "F1, ja, passt. A.",
meaning notification access becomes a new kind in the kit's permission flow with its own ADR, not an
exception in one app).

## Context

LSX Switch switches the speaker on when Spotify plays on another device. It reads Spotify's media
session, which needs notification access: the app's own `NotificationListenerService`, switched on by
the user in Android's settings. That is a special app access, not a runtime permission. There is no
system dialog, no rationale from Android and no "Don't ask again"; the user turns it on or off on a
page of the settings, at any time. ADR-0005 covers runtime permissions only.

## Decision

- A new kind `PermissionKind.NotificationListener(service)`, `service` being the class name of the
  app's listener (the package is the app's own, so debug builds with a suffix work unchanged). It
  is additive: no existing kind, rule or surface changes, and `:permissions` goes to 1.1.0.
- The kind marks itself `settingsOnly`. For such a kind the flow runs its own short path:
  the reason (`Explain.SHEET`, or the screen's own words with `Explain.SCREEN`) → the settings page
  instead of the dialog → the access read again on the way back. "Continue" in `PermissionSheet` and
  "Allow" in `PermissionHint` open the page, so both surfaces work without a change.
- The page: from Android 11 the notification-access page of the app's own component
  (`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` with the flattened component), before that, or
  where a system lacks that page, the list of all notification listeners.
- The state has two values: `GRANTED` while Android has the access on, `NOT_ASKED` otherwise. A
  return from the page without the access and a later revoke both read as `NOT_ASKED`, so the hint
  shows the reason and "Allow" again; the kind is never `DENIED` or `BLOCKED`, and the kit sets no
  mark for it. Nothing is cached: the flow reads the access on every resume, `isUsable(context)` on
  every call (the listener service and workers ask it).
- The access is read with `NotificationManager.isNotificationListenerAccessGranted` for the app's
  component (Android 8.1 and later), on Android 8.0 from the secure setting of enabled listeners.
- Inside the flow the access sits in the granted sets under one kit key
  (`one.tearoom.permissions.access.NOTIFICATION_LISTENER`), so stage, status and usable keep their
  rules. `PermissionPlatform.hasAccess(stage)` reads it, with a default of false, so a platform of an
  app or a test that knows no special access still compiles.
- Reference app for the manual test (rule 6): LSX Switch, the only app with this access. The skill
  `android-permissions` matrix runs there on the emulator: fresh install, turned on, left off,
  revoked while running, revoked while the app is gone, update, reinstall.

## Reasons

- One flow for everything the user grants (ADR-0005): the same reason sheet, the same hint, the same
  resume reading, tested once. An app-local flow would be a fourth way to find out the same thing.
- A separate flow class beside `PermissionFlow` was the other way. It would keep the runtime path
  untouched by construction, but duplicate the reason, the settings step, the rotation state and both
  bindings, and the surfaces would need a second type. The branch is two lines in the flow (the
  launch and the rationale) and the granted set; every existing test runs unchanged.
- Two states rather than a "denied" after a return without the access: Android gives no signal to
  tell "left off" from "never opened" or "revoked later", and a mark would be a cached grant in
  disguise. The reason and "Allow" fit all three.

## Consequences

- Other special accesses (exact alarms, usage access, overlay, battery optimisation) can follow as
  further `settingsOnly` kinds with their own page and reader in `SpecialAccess`; each needs an ADR
  line here or a new ADR. Exact alarms followed in ADR-0008.
- Android 13 and later restrict notification access for apps installed outside a store session
  (sideloaded from a browser or a file manager): the switch stays grey until the user allows
  restricted settings in App info. Builds installed with adb or from Play are not affected; an app
  that ships outside Play names the way in its own text.
- `PermissionSettings.option` names no option for the kind (it is never blocked).
