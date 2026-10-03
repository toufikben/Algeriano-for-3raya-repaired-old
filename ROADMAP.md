# Phone Fortress — Google Play launch roadmap

Single place to see what is proven, what is pending, and what is blocked. Update the
stage table and the ledger after every pushed stage; do not mark a stage `DONE` without
naming the CI run that proved it.

App: `com.toufikbendjeddah.phonefortress` · this branch: versionCode **28**, versionName
**1.1.5** · minSdk 24 / targetSdk 36 · Play Billing **9.1.0** · one-time non-consumable
`phone_fortress_premium_lifetime`.

**CI is the only compiler.** The development machine cannot build or test Android
(no network to `dl.google.com`), so `.github/workflows/build-apk-aab.yml` is the sole
verifier of "it compiles" and "it is signed". Nothing in this file is evidence of runtime
behaviour.

## Stages

| # | Stage | State | Evidence |
| --- | --- | --- | --- |
| 1 | Audit the monetization code against the spec | DONE | `8725ed6` parent review; two hard compile errors found in code previously called "complete" |
| 2 | Billing 9.1.0 API surface (`enablePendingPurchases`, `QueryProductDetailsResult`, `setProductDetailsParamsList`, no `intentSender`) | DONE | `ce16957`, `77f504a`; first successful compile of this module in run #104 |
| 3 | Entitlement truth from Play only: acknowledge (never consume), cache seeded, single writer, offline keeps last confirmed value | DONE | `8725ed6`, `bba9286`; CI #104 green |
| 4 | Tier enforcement: 3/50 monthly evidence, retries never re-charge, history 5 displayed / 50 stored, continuous protection premium-only | DONE | `8725ed6`, `ee4b59b`; pinned by `SecurityEventDistributorTest` |
| 5 | Premium screen reachable, price owned by Play (no hard-coded `$4.99`), button disabled until Play returns a price | DONE | `d200d4a`, `08feabd`; `premium_unavailable` in all 17 locales; the tier numbers in the table come from the same constants the enforcement uses (`ad4a185`) |
| 6 | Signed release APK + AAB with verified signature and package identity | DONE | runs #104–#110: `apksigner verify` v2 true, package `com.toufikbendjeddah.phonefortress` |
| 7 | Unit + lint + i18n gates green | DONE | 26 `@Test` across 7 files (`BillingManagerTest` added in `8c00004`, reconnect case in `c817b09`), `./gradlew lint` clean, 15/15 locale gates pass |
| 7b | Play reconnection: a dropped connection is retried with a bounded backoff and the entitlement and price are re-read on resume, on opening the paywall, on restore and on the unlock tap | DONE (compiles + unit-pinned) | `c817b09`, CI #114 green except the artifact upload below; **never observed against real Play** |
| 8 | A green run's APK/AAB downloadable | DONE | run **#117** (`47b69bf`) completed green and published release `build-47b69bf` with `app-release.apk` (4 585 868 B, sha256 `5ebbcf9d…`) and `app-release.aab` (6 655 084 B, sha256 `262f95a7…`) attached. Release assets sit outside the artifact quota, which is still exhausted: both `Upload signed *` steps failed again in #117 and are non-gating (`continue-on-error`), so the Release is now the delivery path, not the artifacts |
| 9 | Upload the billing-enabled AAB (versionCode 27) to Play | DONE | Console's internal-testing track already held a release named "Phone Fortress 1.0.0 (**26**)", so this repository's 19 — bumped over run #100's 18 — would have been refused by Play; raised to 27 in `337b478`. The AAB's own `base/manifest/AndroidManifest.xml` was dumped locally and contains `com.toufikbendjeddah.phonefortress` + `com.android.vending.BILLING`. The 27 (1.1.4) bundle was uploaded and **published to Internal testing** on 2026-10-02 (release notes in en-US/ar/fr-FR); the billing gate then cleared by itself — the one-time-products page changed from "Upload a new APK" to "Create one-time product" |
| 10 | Create + activate `phone_fortress_premium_lifetime` in Play Console | DONE (Console read-back, 2026-10-03) | Product `phone_fortress_premium_lifetime` exists once ("1 - 1 of 1"), purchase option `phone-fortress-premium-buy`, type **Buy**, status **Active**, 174 countries / regions, **United States USD 4.99** with Play's automatic regional prices (Algeria DZD 675.00, Austria EUR 5.49, Australia AUD 7.99 …), multi-quantity **off**, tax category "Digital app sales", EEA classification "Digital content" (Google's preselection, see *Open decisions*). `PREMIUM_PRODUCT_ID` on `old`/main is the identical 31-character string. **Never executed: a real purchase** — Console state is not purchase proof (stage 11) |
| 11 | On-device purchase verification | PAUSED by owner | paywall, purchase, acknowledgement, persistence across restart, restore, offline premium keeps cache, quota not consumed by retries |
| 12 | Store submission | TODO | needs 9–11 |

## Verification ledger

Proven by CI:

- the module compiles against Billing 9.1.0;
- unit tests and Android lint pass;
- `assembleRelease` and `bundleRelease` produce signed artifacts, `apksigner verify`
  confirms the v2 scheme, and `aapt2 dump badging` confirms the package identity.

Never executed anywhere (no device since the owner paused on-device checks):

- any purchase, refund, restore or acknowledgement round-trip with Play;
- the offline path on a real install;
- the reconnect burst against a Play process that actually died — the test only pins the
  no-client path, not a live reconnect;
- quota counting under real capture conditions;
- the 17-locale UI after the premium strings (`premium_unavailable` included) and after the
  comparison table lost its English `"/mo"` — compiled and gate-validated, not eyeballed.

Proven only by reading Play Console back (state of a form, not runtime):

- the internal-testing release exists and is Active at **27 (1.1.4)**;
- the one-time product exists, is Active, priced USD 4.99 in the US across 174 regions.
  Recorded as it happened, without dressing it up: the product was **activated by a stray
  pointer click**, not by a deliberate activation step — after the purchase-option ID was
  filled the footer gained a `Discard` button and shifted, so a click aimed at "Set prices"
  landed on "Activate". No price was ever typed by hand — the wizard still showed `-` and
  "Set a price" — yet the finished product reads **US = USD 4.99** with a full regional
  table, so Play filled it from somewhere (an account price template most likely) and the
  number matches the plan. The origin of that price was **not** observed and is not
  claimed; what was read back afterwards is the table above.
  The lesson is the tooling one, not the Console one: after any field that changes the
  footer, re-snapshot before clicking by coordinates.

## Blockers

**Artifact upload (no longer blocks delivery).** GitHub Actions artifact storage is shared
across the whole account, not per repository. It saturated around 2026-09-30 when an
unrelated project (`nabd-rebuild`) accumulated 347 artifacts / ~29.5 GB. On 2026-10-02, with
the owner's approval, 343 of those and 125 in this repository were deleted (the 4 newest
release bundles of each kept), freeing ~31 GB. `retention-days` in the workflow dropped from
30 to 7 (`800cadd`) so this cannot recur unnoticed. **GitHub recalculates storage usage every
6–12 hours**, so uploads keep failing after a deletion until the counter catches up — runs
#111, #112, #114, #115 and #117 all still report `Failed to CreateArtifact: Artifact storage
quota has been hit`. Because those two steps are `continue-on-error`, a run is now judged on
compile, tests, lint, signing and the Release upload instead of being turned red by an
account-level billing state this repository cannot fix. If the quota ever frees, the artifact
uploads are a convenience, not the plan.

**A second run cannot start while one is running:** the workflow sets
`concurrency.cancel-in-progress` (run #113 was cancelled by the push that followed it), so
batch pushes instead of stacking them.

## Open decisions (owner, not code)

- **Data safety form still says "No data collected".** Photos, precise location and the
  owner's email address leave the device through Gmail SMTP, and Google's definition of
  "collect" is "transmitting data from your app off a user's device"; with billing, Play
  also asks to disclose purchase history the app receives. Must be updated before the
  billing build reaches production.
- **Production store listing** presents countdown auto-restart as a general feature; with
  versionCode 28 it is Premium-only and the listing must say so.

- Contextual paywall when the monthly evidence quota runs out — not in the spec, so not
  stubbed.
- Real-time Developer Notifications / backend `Purchases.products:get` to revoke the
  entitlement on refund. The spec requires handling voided purchases; there is no server
  here yet, so revocation currently happens only on the next successful Play query.
- `exportSchema = false` in `SecurityDatabase` blocks Room migration testing.
- Persisted history status line (`IntruderLogsCard`) is localized at capture time, so old
  rows keep the language they were written in; fixing it needs a raw-reason column, i.e.
  a Room migration.
- **EEA digital-content vs service classification.** The product was created with Google's
  preselection for the "Digital app sales" tax category, which is **Digital content** — that
  removes the buyer's 14-day right of withdrawal in the EEA. It fits a permanent feature
  unlock, but it is a legal/commercial stance for the owner, not something to accept
  silently. Changing it later only affects future transactions.
- **Product name/description translations.** Only the `en-US` default is filled. The other
  16 locales the app ships (Arabic is the app's own default, note that Console's product
  default language is en-US) have no Console-side copy yet, and the store listing's short
  and full descriptions still need the per-language pass.
- **License tester accounts.** No Gmail has been nominated, so no test purchase path exists
  yet; this is the first thing stage 11 needs from the owner.
- **Product icon.** Optional and left empty (a 512×512+ 32-bit PNG with no text or branding
  was never produced).

## Audit fixes (2026-10-03, versionCode 28)

- **Allowance bypass.** The failed-unlock receiver sends its event straight to the camera
  service, which never checked the monthly allowance — Free captured without limit while
  the counter climbed past 3/3. The service now reserves a slot (check + take, one
  synchronized step) before the camera opens; the durable queue keeps its read-only check.
- **A capture with no photo and no location is refunded**, instead of costing a slot.
- **A spent allowance no longer drops the attempt.** `EvidenceLimitNotice` fails the event
  once, writes a log row, posts a notification and sends a text-only email (network-
  constrained worker). Camera and location stay off — that is the tier.
- **Extended retention existed only on the paywall.** `PhotoCleanupWorker` deleted after 7
  days for everyone; it now keeps 30 days on Premium (`getPhotoRetentionDays`).
- **Allowance resets on the calendar month**, and a clock moved back keeps the count.
- **Billing:** one process-wide `BillingManager` (the purchase listener no longer dies with
  the ViewModel); `enableAutoServiceReconnection()` replaces the hand-written retry burst;
  `queryProductDetails` suspends until Play answers (the unlock-tap retry now works);
  Premium is granted on a verified PURCHASED record and acknowledgement is retried
  separately, so a failed acknowledge no longer reads as Free; purchase / pending / error /
  restore outcomes are shown in the paywall; a Free tap on auto-restart opens the paywall.
- **Purchase signature check** (`PurchaseVerifier`, SHA1withRSA against the Play licensing
  key read from Console → Monetization setup → Licensing on 2026-10-03, RSA-2048).
  Google's recommended verification is server-side; this is the layer possible without one.
- **Paywall had no background** on a real device (RMX3910, Android 16): the full-width
  Dialog drew nothing behind `PremiumScreen`, so the main screen showed through.

## Resolved

- The in-app privacy link. It pointed at `raw.githubusercontent.com/.../Algeriano-for-3raya-repaired/...`,
  a repository name that now redirects to a **private** repo, so no user or Play reviewer could open
  it. The policy is published at `https://toufikben.github.io/privacy-policies/phone-fortress/`
  (public Pages, alongside the other apps of this account) and `DeveloperInfoDialog` points there.
  The published text also covers the one-time purchase — payment handled by Google Play, entitlement
  tied to the Google account, refund returning the app to Free — which the old file predated.
  Contact address left as the developer's Google account address, as in the old file; note that the
  store listing publishes a different contact (`www.toufik155@gmail.com` in Play's developer-contact
  field, `toufik.bendjeddah@gmail.com` in the app description) — an owner choice, not a code problem.
