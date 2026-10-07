# Algeriano for 3raya - Monetization Branch

This repository contains the monetization-enhanced version of the Algeriano anti-theft application.

## Overview

**Phone Fortress** is an advanced Android anti-theft application with the following features:
- Intruder detection with automatic photo capture
- Location tracking and monitoring
- Countdown-based security events
- Email notifications with evidence
- Multi-language support (16 languages)

## Monetization

One Google Play in-app product, `phone_fortress_premium_lifetime` (one-time,
non-consumable, price set by Play per country). No ads, no subscriptions.

| | Free | Premium |
| --- | --- | --- |
| Evidence captures per calendar month | 3 | unlimited |
| Visible event history | 5 | 50 |
| Photo retention on the device | 7 days | 30 days |
| Countdown automatic restart (continuous protection) | no | yes |

When the monthly allowance is spent, a failed unlock is still reported (log row,
notification and a text-only email) — only the camera and location stay off.

## Building

```bash
./gradlew assembleRelease
```

## Testing

All builds are verified through GitHub Actions CI/CD pipeline.

## License

Private repository - All rights reserved
