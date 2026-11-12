# ADR-0006 · One rating prompt for every app

Status: proposed, 11 November 2026 (rule and API decided by Mathis on 11 November 2026; the new
dependency `com.google.android.play:review` waits for his acceptance of this ADR, AGENTS.md rule 5).

## Decision

- Asking for a Google Play rating is one flow in the kit, following ADR-0004: the rule, the counter
  and the Play call in a new module `:review` (`AppReview`, `ReviewConfig`, pure rules in
  `ReviewPolicy`, the Play call behind `ReviewLauncher`); the place of the settings row and its words
  are the app's. No `-ui` module: the row is one line in each app's own row style, the kit gives it
  `openListing`.
- Google Play In-App Review API (`com.google.android.play:review` 2.0.2, plain, not `review-ktx`, as
  with Billing), plus a permanent "Rate the app" row in every app that opens the Play listing
  (`market://`, else the web page).
- The rule, identical for all apps: at least 7 days since the first opening and the app opened on at
  least 10 different local calendar days. An opening is the main activity coming to the foreground
  (launcher, widget, notification). When the rule is met, the next opening asks once the main screen
  is calm for about 2 s, never while an error, a permission request, a purchase or onboarding is on
  screen; the app says what calm means, the kit waits and cancels.
- At most once per versionCode. The prompt counts as asked when Play's flow is shown. Play answers the
  request after a moment; if the screen is no longer calm, the activity paused or a newer try started by
  then, nothing is shown. Without Play or network the next opening tries again. Play throttles on top and never says whether the
  dialog showed or a rating was given.
- No own pre-question, no routing of unhappy users, no reward (Google's policy).
- Test builds force the next prompt through `FakeReviewManager` and reset the counter, from the
  app's debug settings or through `ReviewDebugReceiver` (declared only in a debug manifest).

## Reasons

- Four apps, one rule decided once: the same counter in four places would drift like the purchase
  flows did before ADR-0004.
- Play's own dialog keeps the user in the app and is the only form Google allows without a policy
  risk; the row covers those who want to rate on their own.
- Counting days, not starts, ignores rotations and returns from the settings without extra logic.

## Consequences

- New module `:review` 1.0.0 (`play:review` 2.0.2, `androidx.activity`, coroutines); the apps name it.
  It brings Play Core's common library into every app; Weatherfold's open question about
  `firebaselogging.googleapis.com` should be checked again with it in place.
- Existing users start counting with the update that brings the module, so their first prompt comes
  10 opening days later at the earliest.
- The real dialog shows only for an install from Google Play (internal test or internal app
  sharing); on the emulator a forced prompt goes to `FakeReviewManager` and is checked through the
  debug status line ("last prompt … (fake)").
- Weatherfold, Dahlia, Paperboat and Lazy Moves take it in their first round (11 November 2026);
  Breeze is a test project and stays off; Gazania, Gasamba and Feldbuch are not on the kit.
