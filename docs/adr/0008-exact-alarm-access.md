# ADR-0008 · Special app access in the permission flow: exact alarms

Status: accepted, 13 November 2026 (user decision, Mathis: LSX Switch gets its own wake-up alarm, and
the exact-alarm access goes through the kit's permission flow the way notification access did in
ADR-0007, not as an exception in the app).

## Context

LSX Switch wakes the user with its own alarm, scheduled with `AlarmManager.setAlarmClock`. Since
Android 12 that needs the special app access "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`). Android
13 and earlier granted it to most apps at install; Android 14 and later no longer grant it to new
installs, so the user switches it on in Android's settings. Like notification access it has no
dialog, no rationale and no "Don't ask again", and the user may turn it off at any time. ADR-0007
set the pattern for such accesses and named exact alarms as a likely next one.

## Decision

- A new kind `PermissionKind.ExactAlarm`, `settingsOnly` like `NotificationListener`. It is additive:
  no existing kind, rule or surface changes, and `:permissions` goes to 1.2.0.
- From Android 12 the kind holds one kit key (`one.tearoom.permissions.access.EXACT_ALARM`) in the
  granted sets; below 12 it holds none, so the policy reads `NOT_NEEDED` and the flow runs the feature
  at once without opening anything, as with notifications before Android 13.
- The access is read with `AlarmManager.canScheduleExactAlarms()` (Android 12 and later), on every
  resume and every `isUsable(context)`; nothing is cached.
- The page: `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with `package:<app>`, the app's own
  "Alarms & reminders" switch. Where a system has no such page, App info opens instead.
- Two states, as in ADR-0007: `GRANTED` while the access is on, `NOT_ASKED` otherwise; never `DENIED`
  or `BLOCKED`, no mark. `PermissionSettings.option` names no option for it.
- The app declares `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />`.
  Revoking the access cancels all of the app's exact alarms; the app schedules them again on
  `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` and when it next runs. That
  receiver and the alarms themselves are the app's, not the kit's.
- `USE_EXACT_ALARM` (granted at install, restricted by Google Play to alarm-clock and calendar apps)
  is not covered: it needs no flow, and an app that qualifies declares it on its own.
- Reference app for the manual test (rule 6): LSX Switch. The skill `android-permissions` matrix runs
  there on the emulator: fresh install on Android 14 or later, turned on, left off, revoked while
  running (the alarm is re-scheduled once it is back), update, reinstall.

## Reasons

- One flow for everything the user grants (ADR-0005, ADR-0007): the same reason sheet, hint and
  resume reading. ADR-0007 built the `settingsOnly` path for exactly this; a second app-local path
  would duplicate it.
- `USE_EXACT_ALARM` was the other way: no switch for the user, but Play allows it only for apps whose
  core function is an alarm clock or calendar, and LSX Switch's core function is the speaker. The
  settings access is open to every app.

## Consequences

- Apps on Android 12 and 13 usually find the access already on and never see the page; on Android 14
  and later a fresh install starts with it off.
- An app that schedules exact alarms must also handle the access going away while alarms are set;
  the kit only reports the state.
