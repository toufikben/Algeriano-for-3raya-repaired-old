# Phone Fortress

An Android anti-theft app. When someone tries to unlock the phone and fails, it
photographs them with the front camera, records the location, and emails both to
the owner's address — from the lock screen, without the intruder seeing anything.

## What it does

- **Failed-unlock detection** through a device administrator. A configurable
  number of wrong passwords triggers a capture.
- **Front-camera photo and GPS fix** taken while the screen is still locked, by a
  foreground service that the app can start from the background.
- **Evidence emailed to the owner** — photo attached, location and timestamp in
  the body — plus a local log of every event with its own delivery state.
- **Countdown mode.** Arm protection for a chosen duration (one hour up to
  several days). Premium keeps it rearming itself indefinitely.
- **Nothing leaves the device until the owner's own mail server receives it.**
  There is no backend: the app talks SMTP directly with an app password.

## Free and Premium

One Google Play in-app product, `phone_fortress_premium_lifetime` — a one-time
purchase, non-consumable, priced by Play per country. No ads, no subscription.

| | Free | Premium |
| --- | --- | --- |
| Photo and location on every failed unlock | unlimited | unlimited |
| Visible event history | 50 | 50 |
| Photo retention on the device | 7 days | kept permanently |
| Countdown automatic restart | manual | automatic |

Capturing evidence is free and uncapped on both tiers. Charging per capture meant
that on the night the phone was actually stolen the allowance could already be
spent, which defeats the point of the app. Premium sells what costs the app
something to keep: the photos never expire, and the countdown re-arms itself so
protection does not lapse when the timer runs out.

## Requirements

- Android 7.0 (API 24) or newer.
- A mail account that allows SMTP with an app password. Gmail needs 2-Step
  Verification on and an app password generated for this app.
- Device administrator permission, camera, location and notification permissions.

## Building

```bash
./gradlew assembleRelease
```

A release build needs the signing keystore and its passwords in the environment
(`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`). Without them the
release variant builds unsigned and Play will refuse it.

`versionCode` is `31 + GITHUB_RUN_NUMBER` in CI, so every CI artifact is unique
and increasing without anybody editing the build file. A local build keeps the
base value.

## Testing

Unit tests run on the JVM under Robolectric:

```bash
./gradlew test
```

Everything is also built and verified by the GitHub Actions workflow in
`.github/workflows/build-apk-aab.yml`, which runs the tests, lint, both release
variants, and publishes the signed APK and AAB to a GitHub Release.

## Languages

17 locales: Arabic, Bengali, German, English, Spanish, French, Hindi, Indonesian,
Italian, Japanese, Korean, Malay, Portuguese, Russian, Turkish, Urdu and Chinese.
English is the base (`values/`), Arabic is `values-ar/`. The per-app language
picker is driven by `res/xml/locales_config.xml`, so the choice follows the app
rather than the whole system.

## Privacy

The app has no server and no analytics. Photos, locations and logs stay in the
app's private storage and are sent only to the email address the owner configures.
Stored credentials are encrypted with a key held in the Android keystore.

## License

Private repository — all rights reserved.
