# CardRecord (刷记)

**English** | [简体中文](README.zh-CN.md)

CardRecord is a local-first Android app for counting card swipes, so you can see how close each card is to an annual-fee waiver.

## Features

- Manage multiple debit and credit cards, grouped into folders
- Track expiry, the next annual-fee date, and credit card statement and payment due days
- Count purchases in the year before each annual-fee date and show waiver progress
- Get a progress reminder about 30 and 10 days before the annual-fee date
- Pick a solid color, a card network preset, or your own photo as the card face, in landscape or portrait
- Import and export backups, custom card faces included

## Download

Requires Android 8.0 or later. Get the latest test build, rebuilt from every change on `main`, at [apk-latest](https://github.com/ChuwuYo/CardRecord/releases/tag/apk-latest); see the [changelog](docs/CHANGELOG.md) (Chinese) for what's new.

## Privacy

No account and no server: your data never leaves your phone unless you export a backup yourself. Backups hold unencrypted card details and card face images, so keep them safe; you can delete a backup once it has been imported.

## Building

Requires the Android SDK and Java 17:

```bash
./gradlew :app:assembleDebug
```

Signing and release steps are in [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md); architecture and data rules are in [`docs/Design.md`](docs/Design.md) (both in Chinese).

## Card network artwork and trademarks

The Visa, Mastercard, JCB, American Express, Diners Club, and Discover paths are based on the matching [Simple Icons](https://simpleicons.org/) entries, converted to Android VectorDrawables. The UnionPay / QuickPass artwork is maintained in this project.

Brand names and logos belong to their respective owners and are used here only to identify card networks. Asset licenses and brand guidelines follow the latest terms of the source projects and rights holders.
