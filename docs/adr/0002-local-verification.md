# ADR-0002 · Purchase verification on the device, no server

Status: accepted, 26 October 2026 (user decision, Mathis).

## Decision

- There is no server of our own. Ownership is what Google Play's Billing Library reports on the
  device (`queryPurchasesAsync`, `onPurchasesUpdated`), cached in the app's shared preferences so it
  works offline.
- Optionally the app passes its Play licence key (`UnlockConfig.publicKeyBase64`, Play Console,
  Monetisation setup). Then a purchase only counts if Google's signature over `originalJson`
  (SHA1withRSA) matches; a malformed key fails closed. Without a key the check is off.
- Rules: a failed query keeps the cached unlock; a successful answer without the purchase removes it
  (refund, revocation); pending payments never unlock; purchases are acknowledged with bounded retries
  while the app runs.

## Reasons

- The apps sell one cheap permanent unlock (about 2 to 3 euros), have no accounts and promise no
  tracking. A server would add cost, a privacy policy entry and an operational duty for little gain.
- Google's guidance prefers server verification, but accepts local checks for apps without a backend.
  A patched app can skip any local check; the signature check only stops forged Play responses.

## Consequences

- Refunds are noticed the next time Play answers on the device, not instantly.
- Acknowledgement is not a durable background job: an unacknowledged purchase is retried on the next
  refresh, and Play refunds it after three days if the app never runs again.
- Before each release with billing changes, test with a licence-test account on a Play-installed
  build: purchase, pending payment (slow test card), cancel, refund/revocation, restore, offline.
