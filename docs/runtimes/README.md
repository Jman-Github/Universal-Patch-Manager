# Runtime plugins

UPM downloads patcher runtimes separately from the manager APK. Fresh installs and installations upgrading from bundled runtimes must select at least one engine and finish runtime setup.

Runtime code, independent versions and release workflows live in [Universal-Patch-Manager-Runtimes](https://github.com/Jman-Github/Universal-Patch-Manager-Runtimes). See its [plugin contract](https://github.com/Jman-Github/Universal-Patch-Manager-Runtimes/blob/main/docs/PLUGIN-CONTRACT.md) and [GitHub setup guide](https://github.com/Jman-Github/Universal-Patch-Manager-Runtimes/blob/main/docs/GITHUB-SETUP.md).

The bootstrap catalog is app/src/main/assets/runtime-catalog.json. RuntimeCatalogRepository refreshes it from the runtime repository. Every entry can point to a different GitHub repository.

Patcher runtimes > Import opens the catalog selector, with no URL entry. Shared support downloads automatically once. Setup replaces old installed plugin apps after verifying the new APKs. Offline recovery uses signed local APKs, including the support plugin.

New runtime imports enable automatic updates and Latest by default. Latest follows the newest compatible release, including prereleases. Existing runtime settings and preferences restored from backups are preserved. Internal shared support always follows Latest automatically.

Official runtime signatures must match UPM's signing key. Release compatibility metadata, checksums, package IDs, runtime kinds and protocol versions are verified before activation. Content-addressed managed APK paths preserve the previous files across updates.

Manager backups include runtime sources, trust decisions and release preferences, but not APK files. Import restores those choices in the background without reopening runtime selection. Verified local APKs are reused, including files cached under another source identity. Latest and automatic-update settings resolve the selected release channel; sources with automatic updates disabled retain their saved release. Missing dependencies download automatically, and interrupted restores retry after restarts while respecting metered-download settings. An unpublished APK imported from a local file must be supplied again if no matching local copy remains.

Runtime setup completion and in-progress download state are local installation state, not exported manager settings. Downloads interrupted by process death can resume setup using already completed plugin downloads.

Publish compatible runtime releases before releasing this manager change. The manager build no longer compiles or packages engine APKs or JVM distributions.

Interrupted catalog transfers retain partial bytes for range-based retry. APK identity, checksum and signature are checked after transfer and before activation.

## Upstream runtime changes

The manager's JVM startup fixes and the runtime plugins' native-library support
and one-pass APK writer are documented with upstream commit and PR links in the
[runtime host documentation](https://github.com/Jman-Github/Universal-Patch-Manager-Runtimes/blob/dev/upm-patcher-core/README.md#upstream-changes-used-by-upm).
