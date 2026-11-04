# 0012. The application updates itself from GitHub Releases

- Status: adopted
- Date: 2026-10-14 (1.1.11)
- Sources: PR #16, #49, #53, case h85, `update/UpdateLogic.kt`, `update/Updater.kt`, CLAUDE.md (Release...)

## Context

h85: request to update without manual download APK. Application is distributed by APK-file (site and GitHub),
The Headswap updates itself.

## Decision

Once per 6 hours (`CHECK_INTERVAL_MS`, `UpdateLogic.kt:14`) the application requests `releases/latest`: GitHub does not give there
drafts and pre-releases. When not-200 (limit 60 requests/h on IP) spare way - tag from redirect `/releases/latest`
(`tagFromLocation`, `assetUrl`). Before installation check: size, SHA-256 from the field `digest` release file, package name and
`versionCode`. Installation via `PackageInstaller` (`update/Updater.kt`), signature is checked by the system. Release description
is shown to the user before installation. The `vX.Y.Z` tag, the `headswap-X.Y.Z.apk` file (the name as on the site).
Test builds on GitHub are laid out only with `--prerelease`: `releases/latest` does not return them, so the application
They will not offer.

## Alternatives

- Google Play: will not miss an application that works through the developer mode Android Auto.
- The redirect tag as the main path: not used; it remains spare, there is no size and SHA-256 (`size = -1`, `sha256 = null`).

## Consequences

- Pre-release can’t be done “last”: test build never put out without `--prerelease`.
- The filename and tag are hard-set in `UpdateLogic.pickApk`/`assetUrl`; you can't change the name format without editing the application.
- Before creating a release, check the drafts (`gh release list`): if there is a draft, publish it (CLAUDE.md).
- In legacy-APK (0013), the update check is disabled.
