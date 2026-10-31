# Changelog

Release notes are generated automatically from Conventional Commits.

Historical manually maintained release notes remain in `api/CHANGELOG.md` and `api/DEV-CHANGELOG.md`.

# [2.1.0-dev.1](https://github.com/Jman-Github/Universal-Patch-Manager/compare/v2.0.0...v2.1.0-dev.1) (2026-10-31)


### Bug Fixes

* **auto-patch:** format notifications and correct tap targets ([768798c](https://github.com/Jman-Github/Universal-Patch-Manager/commit/768798c22f00169b7cafe15e56deb08e5a5bc2ae))
* **ci:** prevent downloader tests from using Android logging ([a374f07](https://github.com/Jman-Github/Universal-Patch-Manager/commit/a374f0730227c8702f28e8c45a49ba7226bad52b))
* **manager:** correct bundle updates, icons, and runtime names ([2fe83db](https://github.com/Jman-Github/Universal-Patch-Manager/commit/2fe83dbd990ce1179028f402344e4162c579141e)), closes [#805](https://github.com/Jman-Github/Universal-Patch-Manager/issues/805) [#802](https://github.com/Jman-Github/Universal-Patch-Manager/issues/802) [#799](https://github.com/Jman-Github/Universal-Patch-Manager/issues/799)
* **reseam:** update SDK and patcher to v0.35.1 ([24602d1](https://github.com/Jman-Github/Universal-Patch-Manager/commit/24602d13f8353964e307fdbfa41f508490bd7d1b))
* **settings:** preserve preferences and improve UI consistency ([0932803](https://github.com/Jman-Github/Universal-Patch-Manager/commit/0932803783e3f6c8372001d7df4aebe4ed2ac9a8)), closes [#804](https://github.com/Jman-Github/Universal-Patch-Manager/issues/804) [#803](https://github.com/Jman-Github/Universal-Patch-Manager/issues/803) [#798](https://github.com/Jman-Github/Universal-Patch-Manager/issues/798) [#800](https://github.com/Jman-Github/Universal-Patch-Manager/issues/800) [#788](https://github.com/Jman-Github/Universal-Patch-Manager/issues/788) [#771](https://github.com/Jman-Github/Universal-Patch-Manager/issues/771) [#759](https://github.com/Jman-Github/Universal-Patch-Manager/issues/759) [#764](https://github.com/Jman-Github/Universal-Patch-Manager/issues/764) [#787](https://github.com/Jman-Github/Universal-Patch-Manager/issues/787) [#785](https://github.com/Jman-Github/Universal-Patch-Manager/issues/785) [#755](https://github.com/Jman-Github/Universal-Patch-Manager/issues/755) [#762](https://github.com/Jman-Github/Universal-Patch-Manager/issues/762) [#763](https://github.com/Jman-Github/Universal-Patch-Manager/issues/763) [#754](https://github.com/Jman-Github/Universal-Patch-Manager/issues/754) [#760](https://github.com/Jman-Github/Universal-Patch-Manager/issues/760)


### Features

* **about:** add GitHub Sponsors donation card ([3a91f61](https://github.com/Jman-Github/Universal-Patch-Manager/commit/3a91f61677bdd9f81cf2178d7dcd865af608b53c))
* **apps:** load and cache app names and icons ([92750e5](https://github.com/Jman-Github/Universal-Patch-Manager/commit/92750e50dc2abf36c4ea7a88b9795a550de276ee))
* **bundles:** add release controls and reliable downloads ([8d9a7fd](https://github.com/Jman-Github/Universal-Patch-Manager/commit/8d9a7fde98446b7deba250f1d938b48b89f927d7))
* **patcher:** restore configurable ART memory limits ([e10c0e9](https://github.com/Jman-Github/Universal-Patch-Manager/commit/e10c0e9f1862b739a40196942ba8f802ddeaafbf))
* **storage:** cache app lists and share retained APKs ([1fb12ad](https://github.com/Jman-Github/Universal-Patch-Manager/commit/1fb12ad83464f10fba067a05fd29bd05c70208b3)), closes [#767](https://github.com/Jman-Github/Universal-Patch-Manager/issues/767)

# [2.0.0](https://github.com/Jman-Github/Universal-Patch-Manager/compare/v1.10.0...v2.0.0) (2026-10-29)


* feat(manager)!: change runtime and patch bundle defaults ([d99e48a](https://github.com/Jman-Github/Universal-Patch-Manager/commit/d99e48ade6cd93dd059dde3575d3a7e8fc365974))
* feat(manager)!: rebrand URV to Universal Patch Manager ([995ae1c](https://github.com/Jman-Github/Universal-Patch-Manager/commit/995ae1c9f85c45dcf479c049381085e95e9b652f))


### Bug Fixes

* **reseam:** update SDK to 0.34.1 ([fa81d17](https://github.com/Jman-Github/Universal-Patch-Manager/commit/fa81d1794ecdb8589e8990f7ef25f59d6c3fd393))
* **runtime:** preserve update trust and patch progress order ([ceb7da4](https://github.com/Jman-Github/Universal-Patch-Manager/commit/ceb7da4594faea16ce3d0b990e9ec28150142ed0))


### BREAKING CHANGES

* ReVanced v22 now requires its runtime plugin.
Existing official ReVanced v21 plugins must update to version 1.8.1
(version code 8). Fresh installs include no patch bundles; users must
add them through discovery or import.
* The app package changes from
app.universal.revanced.manager to app.universal.patch.manager.
UP Manager installs separately from URV. Existing users must import
their backup and signing keystore to migrate. Runtime plugins and
integrations using the old manager namespace must use the new IDs.

## [1.10.1-dev.1](https://github.com/Jman-Github/Universal-ReVanced-Manager/compare/v1.10.0...v1.10.1-dev.1) (2026-10-28)


### Bug Fixes

* **runtime:** preserve update trust and patch progress order ([ceb7da4](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/ceb7da4594faea16ce3d0b990e9ec28150142ed0))

# [1.10.0](https://github.com/Jman-Github/Universal-ReVanced-Manager/compare/v1.9.0...v1.10.0) (2026-10-27)


### Bug Fixes

* **build:** restore classpaths and bump Morphe and Reseam SDKs ([da1b3dc](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/da1b3dcabe235d7267a5987d794fa475f1c39a5a))
* **bundles:** migrate legacy discovery sources ([2b1cc2f](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/2b1cc2fb551e6c581ae91dd65f80073aa60f57c7))
* **bundles:** prevent downgrades and clear stuck progress ([3a301d1](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/3a301d1500557ff380ca1658702541bf6e49d2b9)), closes [#780](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/780) [#783](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/783)
* **file-picker:** remove forced shimmer delay ([a06e22c](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/a06e22ce831325fb566ce74f5b34e71720f3ee1b))
* **manager:** fix picker, exports, language and dynamic colors ([c7f44e6](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/c7f44e6cb0ffb6890b19e0c241a90b899e8499c1)), closes [#751](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/751) [#752](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/752) [#766](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/766)
* **manager:** fix update alerts, reordering and runtime checks ([1494d5a](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/1494d5ac98aab89bf64634ea971570a09d1c9fbd)), closes [#794](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/794) [#795](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/795) [#797](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/797)
* **manager:** preserve bundle pins and improve UI consistency ([077cf03](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/077cf03d68988cae116fbad8c45d58d271f1186a)), closes [#786](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/786) [#793](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/793) [#792](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/792) [#791](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/791) [#789](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/789)
* **patcher:** stabilize runtime workers and merging ([ff82091](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/ff8209196f44e80d881c5f54373d2661385b56d9))
* **reseam:** fix bundle selection and plugin discovery ([582e60e](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/582e60eb0ad55b88bf05a6c57a28244560e4ac85))
* **reseam:** prevent native loader crashes and update SDK to 0.31.0 ([58b492d](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/58b492d631ebe8367125bd9e0bd9eb233cff0d07))
* **revanced-v21:** fix resource decoding and bump patcher to 21.1.0-dev.5 ([e3e326b](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/e3e326bab01cfd272f0a44bf0460762b896104e5))
* **root:** stream APKs into package install sessions ([c4ecbf3](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/c4ecbf34021dcb6acfb0d8b0df4c3de73c346c9e))


### Features

* **bundles:** add version pinning and refine settings UI ([24e760f](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/24e760f1d7d276ebbbee15c9921f1a2c6e06d908)), closes [#782](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/782) [#768](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/768) [#773](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/773) [#772](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/772)
* **discovery:** use the Patch Bundle Registry API ([ea9e377](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/ea9e3774d430d36d2437c60e60a02b44cfe833df))
* **file-picker:** add breadcrumbs and file details ([781e00b](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/781e00b626aa0f43d5e793954dc74c0c4cd8153b))
* **patcher:** add embedded JVM runtimes ([430625e](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/430625e3df1c4747f6f17c07e321dc9e96c2861e))
* **patcher:** add Reseam runtime ([#770](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/770)) ([8de2cdc](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/8de2cdc3848e6d5fb34456857fb723e203e62b09))
* **updater:** support UPM updates with migration notice ([6d9e108](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/6d9e108d8cd5b6ac7ca74366ee04f0aed68bbe4d))

# [1.10.0-dev.2](https://github.com/Jman-Github/Universal-ReVanced-Manager/compare/v1.10.0-dev.1...v1.10.0-dev.2) (2026-10-25)


### Bug Fixes

* **build:** restore classpaths and bump Morphe and Reseam SDKs ([da1b3dc](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/da1b3dcabe235d7267a5987d794fa475f1c39a5a))
* **bundles:** prevent downgrades and clear stuck progress ([3a301d1](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/3a301d1500557ff380ca1658702541bf6e49d2b9)), closes [#780](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/780) [#783](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/783)
* **file-picker:** remove forced shimmer delay ([a06e22c](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/a06e22ce831325fb566ce74f5b34e71720f3ee1b))
* **patcher:** stabilize runtime workers and merging ([ff82091](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/ff8209196f44e80d881c5f54373d2661385b56d9))
* **reseam:** fix bundle selection and plugin discovery ([582e60e](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/582e60eb0ad55b88bf05a6c57a28244560e4ac85))


### Features

* **bundles:** add version pinning and refine settings UI ([24e760f](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/24e760f1d7d276ebbbee15c9921f1a2c6e06d908)), closes [#782](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/782) [#768](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/768) [#773](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/773) [#772](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/772)
* **file-picker:** add breadcrumbs and file details ([781e00b](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/781e00b626aa0f43d5e793954dc74c0c4cd8153b))

# [1.10.0-dev.1](https://github.com/Jman-Github/Universal-ReVanced-Manager/compare/v1.9.0...v1.10.0-dev.1) (2026-10-24)


### Bug Fixes

* **manager:** fix picker, exports, language and dynamic colors ([c7f44e6](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/c7f44e6cb0ffb6890b19e0c241a90b899e8499c1)), closes [#751](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/751) [#752](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/752) [#766](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/766)


### Features

* **discovery:** use the Patch Bundle Registry API ([ea9e377](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/ea9e3774d430d36d2437c60e60a02b44cfe833df))
* **patcher:** add embedded JVM runtimes ([430625e](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/430625e3df1c4747f6f17c07e321dc9e96c2861e))
* **patcher:** add Reseam runtime ([#770](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/770)) ([8de2cdc](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/8de2cdc3848e6d5fb34456857fb723e203e62b09))

# Changelog

Release notes from the Semantic Release migration onward are generated automatically from Conventional Commits.

Historical manually maintained release notes remain in `api/CHANGELOG.md` and `api/DEV-CHANGELOG.md`.

# v1.9.0

## Development release history

### v1.9.0-dev.1

#### Features

- Added elapsed patching time to the Patcher Information widget for both regular and batch patching
- Added Repatch APK source retention so URV automatically reuses the original APK or split archive when repatching, with support for saved apps, batch patching, missing-source fallback, and Storage Management cleanup
- Added a delete action to individual saved unpatched app cards in Downloads, with a confirmation prompt before removing the stored APK
- Bumped Morphe Patcher to `1.9.0`
- Added configurable Play Store installation source modes for the system and rooted mount installers, adapted from https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
- Bumped Morphe Patcher to `1.12.0`
- Bumped Morphe Patcher to `1.14.0`
- Patch bundle changelogs can now display the full changelog history, depending on your changelog loading and cache limit settings. This replaces the previous `Previous changelogs` section
- Added patcher-style installation feedback to the Split APK Merger, including installation cancellation, a success dialog, and distinct patched/merged app installation messages https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/666 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/671 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/672


#### Bug fixes

- Fixed patch bundle discovery preferring stale primary results by selecting the newest available data, falling back to the dev service, and importing bundles through the v3 API
- Fixed saved patched apps installed through Shizuku-based custom installers showing Shell instead of the selected installer
- Fixed pre-release updates not being detected for some patch bundles imported through GitHub or GitLab URLs
- Fixed patch bundle tabs showing inaccurate counts by displaying selected and available patches as a selected/total value
- Fixed Rooted Mount being unavailable in post-patch installer choosers for compatible Standard patches
- Fixed the Patcher Information widget sometimes showing the wrong app version instead of the version of the APK actually being patched
- Fixed single and batch patching sometimes using or reporting stale patch selections, options, app metadata, and runtime information, especially after missing patches were removed or the patcher screen was restored
- Fixed system Play Store installs still appearing as installed by URV Manager in Android settings
- Fixed some Morphe patch bundles failing to load in process mode with an incorrect corrupted or incomplete error
- Fixed patcher runtimes not consistently honoring `Continue after patch errors`, including process runtimes continuing after fatal patch errors when it is disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/685
- Fixed the Split APK Merger progress notification being able to reappear after cancellation when concurrent merge state or progress updates raced with cancellation cleanup https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/669
- Fixed returning from a cancelled local APK patch leaving the app name and icon blank when the patcher's temporary input had already been deleted https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/665
- Fixed issues with remote patch bundles when using GitHub or GitLab URLs sometimes not detecting prereleases correctly
- Fixed incomplete patch bundle changelogs and missing history entries, including hidden release notes, overwritten descriptions, mixed release channels, and refresh errors not appearing when cached history is available
- Fixed ReVanced 21 and 22 patching failing before patches are applied when using process mode
- Provide a writable temporary workspace for Morphe patches in both in-process and process-mode patching
- Fixed patch bundle import and update feedback by preserving bundle name casing, identifying the selected release channel, and using correct singular/plural progress wording https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/601 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/632 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/633
- Fixed incorrect singular/plural wording when removing saved patches and displaying patch bundle update badges https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/605 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/680
- Fixed cancelling APK preparation incorrectly displaying a failure dialog https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/610
- Improved split selection dialogs by removing outdated guidance and aligning the Cancel action consistently https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/613 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/618
- Fixed manager, installed app, and applied patch bundle version displays to consistently use a single `v` prefix https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/704
- Fixed the Process Memory Limit dialog title alignment https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/676
- Fixed unsupported keystore converter errors wrapping unnaturally https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/673


#### CI

- Added a manual debug build workflow that uploads dev APK artifacts with a `-debug` version suffix without publishing a release
- Updated GitHub Actions dependencies to current Node 24-compatible versions, resolving Node.js 20 and deprecated action warnings
- Converted repository to semantic release based system instead of the manual mess I had before

### v1.8.1-dev.22 (2026-08-11)


#### Features

- Reorganized rooted-mount app actions into one connected segmented layout with a remembered collapsible tools section
- Rebuilt the rooted mount system with transactional operations, verified rollback, automatic recovery, boot and package-change reconciliation, legacy mount migration, and improved management tools https://github.com/MorpheApp/morphe-manager/pull/737
  - Added persistent transaction tracking to safely recover interrupted mount, update, downgrade, and unmount operations
  - Added verified stock APK backups and rollback before making package or module changes https://github.com/MorpheApp/morphe-manager/pull/673
  - Added automatic mount reconciliation after reboot, manager startup, app updates, and package changes
  - Added migration support for legacy URV and ReVanced root modules
  - Added repair, use-stock, remount, cleanup, and diagnostic export actions
  - Added stricter package, APK, signing, Android-user, and mount-ownership safety checks https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/646
  - Improved rooted-app progress, status messages, action layouts, and installer routing https://github.com/MorpheApp/morphe-manager/pull/779
- Improved system installer handling across patching, saved apps, downloads, manager updates, LSPosed modules, and split APK tools so APKs install directly from app storage, with OEM callback verification and an intent fallback https://github.com/MorpheApp/morphe-manager/pull/598
- Bumped ReVanced Patcher to `22.0.2-dev.1`
- Made the patcher `Done` button available after patching finishes with errors https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/649
- Removed `requested` from the patcher and Split APK Merger memory usage labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/604
- Updated the Android toolchain and core dependencies, including Kotlin, AGP, Ktor, Koin, Coil, AndroidX, and raised the app and bundled runtimes to target SDK 36
- Bumped Morphe Library to `1.4.0` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/652
- Bumped Morphe Patcher to `1.8.0-dev.3` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/652
- Updated selected filters on the patch selection and patch bundle discovery screens to use the blue selected-state styling https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/642
- Added batch patching for multiple apps with preflight checks, reorderable and cancellable queues, per-app patch configuration and progress, retained source APKs and results, install-all support, automatic repatching, launcher shortcuts, and opt-in external automation actions, adapted from https://github.com/MorpheApp/morphe-manager/pull/795
- Improved the Shizuku/Shevery installer flow with status and configuration controls, Play Store attribution, automatic installation of patched apps, clearer errors, and optional uninstall-and-retry handling for signature conflicts, adapted from https://github.com/MorpheApp/morphe-manager/pull/734
- Added APK download helper app support as an APK source, including helper discovery and trust management, GitHub source importing and updates, helper installation/uninstallation, and selecting helpers as an APK source when patching, adapted from https://github.com/MorpheApp/morphe-manager/pull/797
- Added install-target-aware Morphe patch availability with Standard and Root Mount modes, required and unavailable patch handling, matching installer routing, and optional GmsCore removal, adapted from https://github.com/MorpheApp/morphe-manager/pull/747
- Improved Root Mount compatibility with merged split APK inputs when a valid installed stock APK is available
- Bumped Morphe Patcher to `1.8.0`
- Improved saved unpatched app cards in Downloads
- Improved single and batch patcher logs with input APK version codes, included/excluded split lists, cleaner exports, and detailed patch failure errors
- Added an expandable Patcher information panel to single and batch patching with app version/version code, APK size, selected patches and bundles, manager and patcher versions, runtime and memory details, split/native library status, Android/device information, and available RAM/storage, inspired by Morphe's patch information panel
- Allowed manually changing split selections while filters are active in the Split APK Merger and patcher, with matching filters automatically selected or cleared as the selected splits change https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/615
- Updated dependent setting toggles to visually disable with their parent while preserving and restoring their previous state when the parent is re-enabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/630
- Made split sorting choices independent between the patcher and Split APK Merger https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/611
- Preselected the `Exclude unused languages` and `Exclude extra densities` filters on the patcher split selection screen when `Skip unneeded split APKs` is enabled, while preserving the existing native-library cleanup selection behavior https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/612
- Improved the Monochrome theme with solid black/white accents, normal untinted light/dark surfaces, optional pure-black dark mode, and higher-contrast informational surfaces and Tools icons https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/607
- Restored natural single-sentence wrapping for unsupported keystore errors in the Keystore Converter so the text only wraps when it reaches the available width https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/624
- Integrated each patch bundle's selected patch count directly into the bundle switcher and removed the separate "Selected in this bundle" indicator
- Replaced active Install actions with Cancel controls across single and batch patching, allowing in-progress internal, Shizuku, root/mount, external, Install All, and automatic post-patch installations to be cancelled without discarding completed patch results https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/661
- Added custom installer names to installed app details and label instead of showing the generic `Custom installer` label https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/655
- Added per-bundle pre-release update checks for patch bundles imported from GitHub and GitLab repository links https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/629


#### Bug fixes

- Fixed some APKs merged with the Split APK Merger tool failing when later used for patching
- Fixed the bundle update WebSocket service crashing after a background restart due to multiple DataStore instances accessing the same preferences file https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/647
- Fixed the `Recommended for this device` split preset leaving native library cleanup unselected in the Split APK Merger and patcher https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/622
- Fixed the `All` and `None` split presets remaining selected after applying cleanup filters in the Split APK Merger and patcher https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/616
- Fixed bundle information closing when opened before a notification-triggered bundle highlight finished
- Fixed RGB inputs in the custom YouTube color picker collapsing on shorter screens so entered values are retained and visible https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/628
- Fixed the `Disable patch selection tab swipe` setting not being restored by manager settings and full backups https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/651
- Fixed the patcher log export success message appearing late https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/620
- Fixed the merge notification remaining visible after cancellation https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/623
- Fixed ReVanced v22 patches failing with uninitialized hook and method errors
- Fixed patched APK export filenames omitting versions for additional applied patch bundles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/650
- Fixed split APKs with manually excluded language, ABI, or density modules failing during patching, including APKs produced by the Split APK merger tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/658

### v1.8.1-dev.21 (2026-07-25)


#### Features

- Added support for typed Morphe patch options and bumped the Morphe Patcher to `1.7.0` https://github.com/MorpheApp/morphe-manager/pull/706 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/635


#### CI

- Added JVM unit test checks before dev and release APK builds

### v1.8.1-dev.20 (2026-07-18)


#### Features

- Added support for importing remote patch bundles from GitHub and GitLab repository URLs by resolving root-level `patches-bundle.json` metadata https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/545
- Added support for Morphe `add-source` links, including opening them in URV with the import dialog prefilled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/545
- Improved the split APK downloader plugin flow by showing download progress in a loading screen before opening split selection
- Added optional live memory usage graphs to the patcher and split APK merger tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/567
- Force patching and split APK merging to run in separate processes on Android 11+ to prevent memory crashes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/572
- Removed obsolete experimental patcher and memory-limit settings
- Added an instability warning for Android 10 and older
- Updating patch profiles no longer requires you to select their patch bundles again https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/577
- Added live progress percentages to the patcher and split APK merger with smoother step and substep tracking https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/568
- Added color previews to patch option presets https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/564
- Updated local downloader and runtime plugins to display "Loaded" instead of "Trusted" https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/562
- Standardized revoke trust dialog titles across downloader and runtime plugins https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/561
- Adjusted the supported versions dialog so it is not become too wide when opened from patch search https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/559
- Standardized installer log filename and timestamp formatting with other exported logs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/558
- Improved dialog text consistency and added clearer save confirmations for patched and merged APKs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/555 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/553 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/552
- Plugin notifications no longer remain after opening the Download or Patcher Runtimes settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/556
- Moved “Merged APK filename format” under “Export filename format” in Advanced system settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/551
- Added a Done button to the patcher screen after patching completes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/580
- Added Shevery support for Shizuku-based installs, including detection, manager launching, installer icons, and updated installer labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/585
- Added suggested version dropdowns to app search results on the `Select an app` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/586
- Added split ordering options to the split APK merge selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/582
- Added the ability to install APKs downloaded through plugins from the Downloads screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/541
- Corrected singular and plural wording in patch bundle import progress https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/590
- Made patch bundle update progress counts use consistent `out of` wording https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/591
- Added a minimal patch selection view preset with controls for version tags and patch option previews https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/274
- Added consistent progress notification metadata and Android 16 progress styling for cutout ring support https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/394
- Made bundle import and update progress banners collapse independently and remember each state https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/588
- Added separate remembered folders for file selection and export workflows in both the custom and built-in Android file pickers https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/543
- Added an `Install base & mount` option that installs base and split APKs through root before mounting the patched APK https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/542
- Added per-profile installer selection and automatic installation to Patch Profiles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/583
- Added a Patcher Engine option to skip signing and keep patched APKs unsigned for saving or installation
- Automatically enabled manager pre-release updates when running a pre-release build https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/593
- Bumped Morphe Patcher to `1.6.0`
- Added APK version codes to app details, supported-version displays, searches, and Morphe patch compatibility checks https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/594
- Added an option to choose which split APKs are merged before patching https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/592
- Automatically remove unavailable patches and their saved options from saved selections, and show a toast indicating which patches were removed https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/595
- Added an optional root-only LSPosed tab with support for LSPosed, Vector, and compatible builds, including local and GitHub module management, update checks, manager and settings shortcuts, and customizable module actions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/596 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/598
- Added Signature metadata injector & cloner tool under APK signature tools
- Improved the Split installer tools log, making it consistent with the Signaute metadata injector & cloner tools log design
- Added a configurable memory limit of up to 2 GB for separate-process patching runtimes. Overal improved the memory system, being adapted from Morphe Managers implementation, URV now is able handle heavier patching and split APK merging on Android 11+ devices
- Added Monochrome and Material You theme presets, including optional Pure Black styling for Material You
- Improved theme and accent preset selection, layout, indicators, and previews
- Expanded Storage & Cache Management with separate cleanup options for signing files, patcher runtime plugins, and saved LSPosed module APKs, with safeguards against clearing files while they are in use
- Added support for opening `.rvp` and `.mpp` patch bundle files directly in URV, adapted from Morphe Manager's implementation https://github.com/MorpheApp/morphe-manager/pull/483
- Made the `Recommended for this device` split APK merge preset deselectable while preserving the selected language and density cleanup filters https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/550
- Sorted new downloader and runtime plugin dashboard notifications by plugin installation time https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/557
- Sorted patch bundle import and update progress banners by when each operation started https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/587
- Added direct installation from the split APK merger and replaced its save progress step with a write step https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/600


#### Bug fixes

- Fixed the saved app bundle update badge toggle appearing enabled while disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/547
- Fixed merger collapsible sections not respecting the auto-collapse and auto-expand settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/548
- Fixed merge split APK filter combinations not always being remembered across sessions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/563
- Fixed bundle update notifications sometimes appearing again after being tapped https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/571
- Fixed split APK merger progress briefly moving backward when merging starts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/570
- Fixed the merge notification remaining visible after cancellation on some devices https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/549
- Fixed the Bundle update label sometimes missing from saved app cards https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/566
- Fixed the Tools screen briefly appearing when holding Back after merging or saving a split APK https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/560
- Fixed rooted mounted apps sometimes starting without patches after reboot https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/578
- Fixed patcher and split APK merger progress bars appearing partially filled before progress begins https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/569
- Fixed issues with the split APK merger during corrupting and breaking some APKs
- Fixed bundle update labels not appearing when a saved app's patch bundle was imported again through settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/581
- Fixed bundle recommendations continuing to use the first selected bundle's patches after switching bundles while preserving custom patch selections https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/579
- Fixed patch bundle update and import progress bars showing a filled marker at the end before completion https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/589
- Fixed serval bugs around themeing & customization
- Fixed duplicate entries appearing in Previous changelogs
- Fixed Patch Profiles sometimes only showing storage as an APK source


#### Refactors

- Reorganized navigation, tab, and action-button settings under Appearance & UI, and improved search to expand and highlight nested settings correctly

### v1.8.1-dev.19 (2026-06-14)


#### Features

- Added a dashboard notification for newly installed runtime plugins and tightened notification card action padding https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/512
- Increased the patch bundle discovery empty state text size for consistency with other search results https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/513
- Added support for experimental app version targets in Morphe patch bundles
- Improved exported log filename consistency for merger, patcher, debug, and installer logs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/524
- Improved patch bundle update notifications with the manager icon, ordered bundle/version lists, and stacked grouped alerts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/299
- Added separate Split APK merger step expansion settings for auto-collapse, auto-expand, and active-category-only expansion https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/302
- Added a Split APK merger progress notification with the manager notification icon https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/304
- Added the manager icon to the patching progress notification https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/301
- Improved Patcher Runtimes plugin action button styling so `Update` matches `Delete`/`Settings` and `Revoke trust` uses the grey filled style https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/516
- Updated the local runtime plugin trust dialog secondary action from `Delete` to `Uninstall` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/521
- Updated downloader and runtime plugin trust dialog titles to `Trust` for consistency https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/522
- Updated runtime plugin delete and uninstall confirmation buttons to `Confirm` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/520
- Added specific copied URL toast messages for downloader plugins, runtime plugins, and patch bundles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/514
- Replaced local downloader and runtime plugin repository URL copy buttons with copy icons https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/523
- Moved the local downloader and runtime plugin `Latest` option below `Pre-release` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/517
- Improved downloader plugin action button styling so `Update` matches `Delete`/`Settings` and `Revoke trust` uses the grey filled style https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/515
- Removed redundant `downloader` text from downloader plugin names in trust dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/518
- Removed package/id details from runtime plugin trust dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/519
- Compact saved Apps card patch bundle summaries and added a setting to hide bundle update badges https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/528
- Bumped Morphe Patcher to `1.5.2`
- Improved patch selection and reset patch bundle dialogs with centered content, a restore icon, and consistent `Confirm` actions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/529 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/530
- Added `v` prefixes to downloader and runtime plugin version labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/526
- Updated the debug log export toast to `Debug log exported` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/527
- Added toast feedback when force downloading all patch bundles starts or downloads nothing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/531
- Improved downloader and runtime revoke trust dialogs with consistent confirmation prompts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/497
- Improved dialog titles, keystore converter messaging, and storage usage `Clear` button alignment for consistency https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/532 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/485 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/484
- Added an APK signer tool to the `Tools` tab for signing APKs with the manager's current signing certificate https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/504
- Added a Shizuku installer option that reports Google Play as the installation source https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/380
- Centered dialog titles that were still left-aligned in several dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/534
- Added patch profile action button ordering and visibility settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/482
- Centered additional dialog titles that were still left-aligned https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/536
- Improved patch bundle and patch profile import result messages with updated and skipped counts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/386
- Centered install result dialog titles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/537
- Added a filename format setting for merged APKs saved from the split APK merger tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/318
- Centered body text in the downloader help and language restart dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/539
- Allowed combining cleanup filters in the split APK merge tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/360
- Standardized supported version dialog layouts and centered suggested version chips https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/540
- Added a default-enabled New patches filter that shows newly added patches from each bundle at the top of the patch selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/236
- Centered the signature mismatch dialog body text across the patcher, app info, and quick action install flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/544
- Updated newly generated manager signing keystores to use `alias` as the alias and `password` as the password while preserving compatibility with existing and legacy manager keystores https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/538


#### Bug fixes

- Fixed the Patch Bundles tab empty state so searching with no added bundles still shows the no-bundles message https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/483
- Fixed ReVanced v22 AAPT2 selection so it uses the sanitized APK input before opening the patcher session
- Fixed color option detection so path-like fields are not mistaken for color values
- Fixed bundle update notifications disappearing after repeat checks while respecting intentionally dismissed alerts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/535
- Fixed Android back navigation on the patch selector so it matches the toolbar back behavior https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/408

### v1.8.1-dev.18 (2026-06-02)


#### Features

- Improved patcher runtime plugin trust dialogs to match downloader plugin trust dialogs and handle long signatures without overflowing
- Improved downloader and patcher runtime plugin source settings, including clearer runtime source details, repository URL copying, matching switch behavior, and the renamed ReVanced v21 runtime plugin https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/490 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/493
- Improved downloader and patcher runtime plugin trust dialogs with clearer wording, centered Plugin and Signature sections, consistent warning icons, Confirm actions, and corrected downloader plugin display names https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/491
- Polished runtime plugin labels, uninstall confirmation text, and About screen credit punctuation https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/486
- Improved runtime plugin dialog wording so delete and uninstall confirmations use consistent action labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/496
- Improved downloader and patcher runtime plugin labels for consistent naming across cards and dialogs, and updated the patch bundle discovery empty state to show `No bundles found` centered in the list https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/495 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/494 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/500
- Added CPU architecture labels to saved patched apps, showing values like ARM64, ARMv7, x86, Universal, or No native libs in the Apps tab and saved app details https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/251
- Improved bundle recommendation dialogs, runtime plugin cards, trust dialogs, source settings styling, and added an icon for the ReVanced v21 runtime plugin https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/498
- Centered the remaining patch selection and patcher result dialog text, including bundle action confirmations, patch defaults, incompatible patches, and install success dialogs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/487
- Made the patch selection filter sheet open fully expanded so all filter options are visible without scrolling first https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/507
- Added patch selection sorting options for Z-A, enabled first, and disabled first https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/511
- Added app icon background image output and optional Morphe notification icon generation to the custom YouTube asset creator tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/330


#### Bug fixes

- Fixed patcher runtime plugin repository imports so manager APK assets are skipped and valid runtime plugin assets can be found from repository URLs
- Fixed orphaned saved patched APKs taking up storage after they were no longer shown in the Apps tab
- Fixed bundle discovery searches showing the loading state indefinitely when no matching bundles are found https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/499
- Fixed patch option dialogs showing the color picker for file/path options https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/508
- Fixed bundle version selection dialogs showing cached patch bundle names instead of custom bundle display names https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/502
- Fixed ReVanced patching failures by retrying the Write patched APK step with the alternate AAPT2 binary when the selected AAPT2 fails https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/460

### v1.8.1-dev.17 (2026-05-17)


#### Features

- Added a patcher setting to continue patching when individual patch errors occur https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/288
- Removed the `LITE` and `FULL` URV build system and replaced it with a plugin-based system. ReVanced and Morphe are bundled with URV by default, while support for ReVanced v21 requires installing its plugin
- Bumped Morphe Patcher to `1.5.1`
- Removed the AmpleReVanced runtime because Ample now uses the Morphe patcher instead of its own runtime
- Make dialogs icons more consistent https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/467
- Made the confirmation buttons across dialogs more consistent https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/466
- Adjusted the keystore converter error message text size for better consistency with other tool error messages https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/462
- Release and Pre-release filters can no longer be selected at the same time; use Latest to show the newest bundle across both channels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/473
- Reduced reorder hold delay by making app, bundle, and profile drag handles start dragging immediately https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/477
- Moved Bundle type to the top of bundle information before editable fields https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/481
- Close dashboard search bars when switching between Apps, Patches, and Profiles tabs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/469
- Center storage management confirmation dialog titles and descriptions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/461
- Improve YouTube asset guide ring contrast on both light and dark images https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/463


#### Bug fixes

- Re-added the legacy/modern AAPT2 selector to resolve AAPT2 based resource compilation errors https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/460
- Fixed bundle update notifications being cleared after a follow-up background check when the same manual bundle update was still available but had already been reported https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/470
- Fixed the Apps tab "Update available" badge for patch bundles so stable releases correctly outrank matching prerelease/dev versions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/468
- Resolved more issues with saved patched apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279

### v1.8.1-dev.16 (2026-04-30)


#### Features

- Bumped Morphe Patcher to `1.4.2`
- Improved UI strings, empty/search states, bundle changelog formatting, and import feedback order https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/436 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/437 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/441 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/435 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/434 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/432
- Improved string consistency https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/433
- Bumped Morphe Patcher to `1.5.0`
- Added automatic color code correction, normalization, and a color picker for patch options
- Moved the cursor to the end of prefilled input fields when dialogs and search fields open automatically https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/444
- Added a Storage and cache management settings screen with a storage usage breakdown, refreshable size tracking, and clear actions for cache, downloads, patch bundles, saved patched apps, patch profile inputs, signing files, plugin files, temporary workspaces, and external app folders https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/454
- Added a scheduled auto-clear cache system https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/407
- Updated the save-file icon on the `Choose splits to merge` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/456
- Removed the duplicate top-bar cancel action from the split merge selection screen and renamed Split APK installer to Split installer https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/457 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/455
- Added an Updates setting to switch the manager update popup between full release notes and the minimal version-only view https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/420
- Kept the screen awake while the split merge process is running https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/387


#### Bug fixes

- Fixed split APK tool settings so installed-app filters persist/export correctly, installer logs use clearer copy/export labels, and the duplicate GitHub PAT export toggle is removed https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/439 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/440 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/442 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/443
- Fixed duplicate Apps tab entries being created after installing patched apps with custom installers https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed patch selection action buttons collapsing while scrolling, swiping bundles, or pressing actions when auto-collapse is disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/448

### v1.8.1-dev.15 (2026-04-24)


#### Features

- Added patch option import/export support for patch selection backups https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/417
- Blocked all in-app interaction while app/APK selection loading overlays are shown, including the Apps tab and app picker flow https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/429
- Updated dashboard delete confirmation dialogs to use the correct singular or plural wording for selected apps, patch bundles, and patch profiles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/430
- Improved text-entry UX by auto-focusing dialog and search inputs, opening the keyboard automatically, and keeping input dialogs visible above the IME across patch bundle, search, and settings flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/373 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/374
- Updated the split APK merge selection dialog to show the live selected module count in the Start merge action button https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/362
- Added a per-install installer selection mode that lets users choose the installer each time on the patcher and saved patched app flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/208
- Added a new Patcher logging setting with Default and Verbose modes https://github.com/ReVanced/revanced-manager/pull/3287
- Remember the selected split merge preset across app sessions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/303
- Added a Latest toggle to remote downloader plugin source settings so sources can update from the newest stable or pre-release GitHub release


#### Bug fixes

- Fixed split archive metadata resolution to use stricter device-matching split selection for app labels and icons
- Fixed split APK merging so remove-extras and native library cleanup preserve required ABI, language, and DPI splits when only one compatible config is available

### v1.8.1-dev.14 (2026-04-19)


#### Features

- Bumped Morphe Patcher to `1.4.1`
- Added a Morphe bytecode processing mode setting https://github.com/MorpheApp/morphe-manager/pull/403
- Made the About screen version row so long-press copy only targets the manager version value, not the "Version" label https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/424


#### Bug fixes

- Fixed the Export & Import settings flow by moving export actions before import, cleaning up wording and punctuation, correcting app count pluralization, and updating the manager version copy toast https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/418 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/425 https://github.com/ Jman-Github/Universal-ReVanced-Manager/issues/416 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/413 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/415
- Fixed patcher progress resume/replay syncing with notifications, improved Write APK DEX substep restoration, and improved app icon/label fallback handling https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/419
- Fixed the dashboard "Select from storage" flow briefly showing the wrong page while the selected APK is being loaded https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/421
- Fixed dashboard selection toolbars and selection state not clearing cleanly when switching between Apps, Bundles, and Profiles tabs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/423 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/422
- Fixed incorrect gesture-back preview behavior on screens that intercept back for in-app UI state changes instead of real navigation https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed framework cache recovery and bundled framework handling across all runtimes to prevent missing or corrupted framework cache patching failures
- Fixed duplicate Saved/Installed entries by collapsing matching saved variants after install when saved app overwrite is enabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279


#### CI

- Updated manager release APK filenames to use canonical ABI labels such as arm64-v8a, armeabi-v7a, and universal, and updated the in-app updater/workflows to match the new asset names while preserving compatibility with older releases https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/428

### v1.8.1-dev.13 (2026-04-14)


#### Features

- Removed the `MEDIUM` build profile as it included the same runtimes as `FULL`
- Improved the About screen layout and added version display copy behavior https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/399 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/400
- Updated English UI wording for merge tool titles, selected app counts, patch bundle empty-state text, and APK saved casing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/396 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/398 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/293 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/319
- Updated patch/merge log copy messages, aligned delete/import wording, and made the merge log button follow patcher-style availability https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/404 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/403 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/405 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/406
- Added loading-aware dashboard app input gating so app selection actions stay disabled until patch bundles are ready, with a clearer inactive button state https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/402
- Updated the `LITE` build type to only exclude the ReVanced v21 runtime, and only include the ReVanced v22 and Morphe patcher runtimes


#### Bug fixes

- Renamed the split merge native-libraries filter to `Exclude extra native libraries` and fixed patch bundle update banner grammar for singular vs plural counts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/363 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/359
- Improved installer settings consistency by adding blocked-installer notes, fixing PAT dialog link punctuation, fixed thw remaining issues with missing patch bundle update plural strings, and cleaning up installer wording/status labels https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/378 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/375 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/377 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/395
- Fixed rooted mount installs creating a second visible saved-app entry for the same patched app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed inconsistent dialog button alignment so export, patcher, color picker, and related settings dialogs now place actions on the right like the rest of the app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/376
- Fixed local APK source handling so returning from the patcher preserves the selected file correctly
- Fixed patching notification timing and cancellation behavior so it appears immediately and clears correctly on cancel/close https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/401 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/409 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/292
- Fixed downloader plugin edge cases by improving failed-source recovery, accepting valid plugin results without a reported version, showing download progress for size-less plugins, and expanding the downloader import URL field correctly on larger screens
- Fixed rooted mount installs creating an unused empty legacy directory under `/data/adb/revanced/<package>` for newly mounted apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/411

### v1.8.1-dev.12 (2026-04-10)


#### Features

- After importing settings, URV now requests any required runtime permissions for enabled features when they are not already granted
- Removed now unneeded extra `libaapt2.so`'s and aapt2 selector system
- Removed API status banner https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/372
- Added About credits and in-app licensing viewers
- Improved root service mount reliability and safety https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/370
- Added LITE, MEDIUM, and FULL build variants with matching profile-aware updater behavior
- Added patcher-style progress tracking and merge log copy/export support to the Merge split APKs tool, including excluded split details in exported logs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/388
- Updated the “No updates available” message https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/389
- Improved update dialog and changelog readability by reducing changelog heading/body sizes and tightening the update prompt text layout https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/381


#### Bug fixes

- Fixed the `Read APK file` step showing extra `Initializing patcher` subtext on some runtimes
- Fixed `Prepare split APK` substep ordering so skipped and non-skipped merge rows stay grouped consistently
- Reworked patcher progress handling across all runtimes so `Write patched APK` uses structured grouped progress instead of inconsistent fallback row creation
- Fixed the patch selector action popup collapsing after toggling patches even when Collapse actions after toggling patches is disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/338
- Fixed patch selector action popup behavior so patch toggles no longer dismiss it as an outside click when auto-collapse is turned off https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/338
- Fixed patch bundle update notifications showing the default bundle name instead of the user’s custom bundle name after a successful update https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/364
- Fixed bundle auto-update progress text so custom bundle names remain consistent throughout checking, downloading, and finalizing stages https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/364
- Fixed the `Recommended for this device` merge split APK preset including extra ABI splits by preferring the device’s primary ABI 
- Fixed merge split tools progress grouping so skipped split rows remain grouped separately from non-skipped rows
- Fixed the native library stripping toggle handler on the choose-splits dialog to use a stable explicit toggled state path
- Fixed merge split APK filter switching so selecting `Remove extra native libraries` resets previous preset exclusions first, instead of carrying over exclusions from filters like `Exclude unused languages` or `Recommended for this device` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/361
- Fixed Settings and Update action buttons wrapping awkwardly under Android screen zoom https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/379
- Fixed URV sometimes crashing when patching ran out of memory with the experimental patcher disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/349
- Fixed patcher progress sometimes lagging behind the foreground notification during the early Write patched APK phase https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/390
- Fixed a few UI edge cases, including hidden RGB values in the custom YouTube asset color picker, clearer patch-profile empty-state wording for downloaded apps, and duplicate saved-entry normalization when overwrite protection is enabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/291 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Hardened remote patch bundle updating, API fallback handling, and bundle refresh state management https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/372
- Fixed version assessment so universal-fallback APKs still respect the universal patches safeguard even when suggested-version enforcement is relaxed
- Fixed bundle and profile count text so selected, enabled/disabled, and import/export messages use the correct singular or plural wording https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/385 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/384

### v1.8.1-dev.11 (2026-04-01)


#### Features

- Bumped Morphe Patcher to `1.3.3`
- Bumped ReVanced Patcher to `22.0.1`


#### Bug fixes

- Fixed mislabeled image resources and invalid decoded manifest resource references breaking patching across the ReVanced, ReVanced v22, and Ample runtimes https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/346
- Fixed `.rvp` patch bundles being mislabeled as Ample without positive Ample markers https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/358
- Fixed older ReVanced `.rvp` bundles staying identified as ReVanced even when metadata loading fails https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/358
- Preserve replaced variants when reinstalling saved apps, and clarify that the “Always create a new saved app entry” option only affects patcher saves https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed fullscreen bundle/profile dialogs showing a mismatched status bar strip above the top bar https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/341
- Fixed the patcher source selector showing a false untrusted downloader plugin warning on clean installs with no downloader plugins added or installed https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/353
- Fixed the patch bundle URL editor sometimes requiring two OK taps after editing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/339
- Possibily resolved OOM and crashing errors that are occuring for certain users when patching Google Photos with the `De-ReVanced` patch bundle https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/343 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/349
- Fixed several split APK merger edge cases across runtimes, including duplicate extracted split filenames, incorrect density split skipping, and process-mode merge sorting not honoring its setting
- Improved patching stability by hardening worker shutdown and notification handling during restarts and app closure and added safer recovery for stale patch progress snapshots https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/365


#### Refactors

- Migrated URV’s internal source namespace from `app.revanced.manager` to `app.urv.manager` across the app, API, and runtime modules

### v1.8.1-dev.10 (2026-03-26)


#### Features

- Removed the automatically imported remote downloader plugins


#### Bug fixes

- Fixed downloader plugin signature verification failures potentially crashing URV during launch, reload, or update checks https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/332
- Fixed patcher runtimes holding loaded patch dex objects in memory longer than necessary by scoping patch loading closer to execution
- Possibly resolved issues around patching Google Photos with `De-ReVanced` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/349 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/343
- Fixed valid APK files being rejected in various app flows https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/333

### v1.8.1-dev.09 (2026-03-23)


#### Features

- Added icons to settings subsections and refined the settings card styling for a cleaner, flatter look
- Improved the main Settings screen layout, pinned the About card to the bottom when space allows, and added button-only `Reset`/`Edit` or `Reset`/`Settings` controls for configurable settings
- Improved the dashboard header and main tab labels on smaller screens by keeping the title on one scrollable line and widening the selected tab highlight
- Added remote downloader plugin importing/updating with improved plugin naming and trust handling, seeded default remote downloaders on fresh install, and support for newer official downloader plugins
- Added a full ReVanced announcements system with announcement list/detail screens, tag filtering, archived announcements, unread badges, and dashboard announcement banners https://github.com/ReVanced/revanced-manager/pull/2948
- Added announcement push notifications with deep links to the specific announcement, integrated into the existing websocket/background update system
- Added settings to enable or disable announcements and announcement notifications
- Bumped the Morphe patcher dependency


#### Bug fixes

- Fixed fullscreen back gestures showing the wrong screen preview on some devices and OEM ROMs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed patched app installs sometimes creating duplicate `Saved` and installed entries in the `Apps` tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed patcher warnings breaking progress on the patcher screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/267#issuecomment-4028751450
- Fixed multiple patching and split APK merge issues across runtimes, including bundle loading regressions, cancellation/cleanup problems, merge failures, and write-progress glitches https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/308
- Fixed new announcement banners not appearing on the dashboard until the screen or app was reloaded
- Fixed issues where the patching in progress notifaction would lag behind, or where the patcher UI would lag behind the notification https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/305 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/320
- Fixed the patch bundle URL editor sometimes needing an extra `OK` press after editing https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/324

### v1.8.1-dev.08 (2026-03-12)


#### Features

- Added a scrollable changelog preview directly to the manager update popup, so release notes can be read without opening the full updater screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/229
- Added a restart prompt after changing the in-app language https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/244
- Removed an extra UI separator from the `General` settings screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/287
- Improved patcher state handling across all patcher runtimes
- Added a `Prevent accidental touching` setting that protects against accidental page/tab swipes when enabled, and makes page/tab swipes easier with shorter drags when disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/286
- Added installed apps as a source for the Split APK merge tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/197
- Added a split selection step before merging so specific modules can be included or excluded https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/197
- mproved split merge loading, cancellation, and progress handling


#### Bug fixes

- Fixed (hopefully) the fullscreen back gesture showing the wrong screen preview so the back animation now matches the actual destination screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed an issue where the `Patching in progress` notification could remain visible after canceling patching https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/285`
- Fixed patch profiles and last used patch selections/options from overriding each other
 - Fixed saved patched app entries being overwritten, duplicated, or showing incorrect version/date metadata when repatching installed apps https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed stale merged APK output being offered after a later merge failure
- Fixed installed split archive creation to fail cleanly when APK parts are missing
- Fixed uninstall failures sometimes showing as Installation failed instead of Uninstall failed
- Fixed the uninstall service error messaging

### v1.8.1-dev.07 (2026-03-10)


#### Features

- Updated the ReVanced `libaapt2.so` binaries to the ones used by the official ReVanced Manager
- Added live substep counts to patcher progress group https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Improved app and patch search so closing search keeps filtered results visible, and both system back and top-bar back clear the search before leaving https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Improved patch option previews with a larger inline preview and a full preview dialog for long values like file paths https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Improved patch profile APK handling by preserving split archive extensions, loading split APK icons correctly and using detected APK versions for compatibilitu https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Patch profiles can now use the version detected from a selected APK
- Added an option to only keep the latest plugin-downloaded APK per app
- Added a delete confirmation for selected downloaded apps in `Downloads`
- Updated the manager update notification and update banner text to use clearer URV-specific wording, including `URV Manager update found` and `A new manager update is available` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/274
- Increased the size of the status bar icon https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/230
- Improved patch bundle update notifications to use clearer "update found" wording and correct singular/plural grammar for available bundle updates https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/235
- Added persistent tracking for the currently viewed manager update version so the icon state stays correct across app restarts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/232
- Reordered the `General` settings screen so Navigation & Tabs appears before Themes, while Themes and Background remain grouped together https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/234
- Renamed the screen shown after selecting an app to patch from App info to `Preparing to patch` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/264
- Updated the `Apps` and `Patch Profiles` tab search hints to use consistent wording of `Search by app name` and `Search by profile name` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/237
- Improved the Patch Profiles bundle selector with tabbed bundle switching and cleaner bundle source labeling https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/242
- Improved patch option viewing in Patch Profiles with a three-dots menu for patches with options


#### Bug fixes

- Fixed issues with the fullscreen back gesture causing UI glitches and showing the wrong screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/280
- Fixed an issue where the `AAPT2 selected` line in patcher logs would always be listed as `Unknown`
- Fixed RGB inputs in the custom YouTube color picker so typed values are visible and use numeric keyboard input https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/261
- Fixed previous patch bundle changelog history so it resets when a bundle source changes, fetches the correct number of older entries, and only shows historical changelog actions for bundle sources that actually supports them
- Fixed an issue where the `Patching in progress` notification would clear early https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/257
- Fixed anissue where the `Patchng in progress` notification would appear late https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/285
- Fixed an issue where on fresh installs the user would be required to regenerate the keystore manually
- Fixed background bundle and manager update checks being rescheduled on every app launch
- Fixed websocket-triggered update checks so newer refreshes are not dropped behind older queued work
- Fixed stale bundle update notifications not clearing when no updates remain
- Fixed manager update notifications being suppressed after notification permission is turned off and back on
- Clarified package selection text to use generic app package wording instead of APK-only wording for local files and patch profiles https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/264
- Clarified changelog link wording by changing the external action to `View changelogs on GitHub` on the manager update and changelog settings screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/233

### v1.8.1-dev.06 (2026-03-07)


#### Features

- Updated the Morphe runtimes aapt2 binaries to the ones used by the official ReVanced Manager https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/272
- Improved the existing `Previous changelog` system to backfill to a user set limit through settings
- Remove `AAPT2 version` and `AAPT sha256` from the patcher logs and added `AAPT2 selected`, `Environment`, `Device name` and `Selected patches` lines
- Bumped Morphe dependencies
- Improved the `Patching in progress` notification https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/278
- Renamed the manager keystore file to `urv_keystore.keystore` and added migration/restore support for legacy `manager.keystore` backups https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/268 


#### Bug fixes

- Fixed issues where there would be a pause between the patchers main categories/steps
- Fixed more issues with patching on certain runtimes
- Fixed issues with resuming manager updates with the in-app manager updater https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/277
- Fixed manager update asset selection so the updater correctly resolves both ABI-specific APKs
- Fixed issues with the `Always create a new saved app entry` setting https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/279
- Fixed the patcher screens pause between `Load patches` and `Read APK file` sub-steps
- Fixed issues with progress being reordered live during split APK merging for the AmpleReVanced runtime
- Fixed the `Export filename format` dialog so the single-line text field slides with the cursor https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/276

### v1.8.1-dev.05 (2026-03-05)


#### Features

- Overall improved the stability to all patcher runtimes
- Added device architecture, Android version, device model, and patcher version to the patcher logs
- Removed the duplicate export button from the App info screen top bar, keeping the existing export action in the main action row https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/260


#### Bug fixes

- Fixed an issue where the ReVanced v22 patcher runtime wouldn't follow the experimental patcher setting
- Fixed an issue where the patching process would die (needs testing) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/267
- Fixed issues where the patcher screen would not show the progress of patching accurately
- Fixed issues where the ReVanced v22 runtime would patch incorrectly resulting in broken patched apps
- Fixed an issue where if you patched an app with multiple patch bundles they sometimes would not be listed on the `App info` and `Applied patches` screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/262
- Fixed an issue where the hold tap guesture would not work for some apps in the `Apps` tab
- Fixed an issue where you couldn't select certain apps in the `Apps` tab on multiselection mode
- Fixed dependent patching flow settings so they are disabled when inactive and automatically reset when their parent toggle is turned off
- Fixed the `Export filename format` editor so tapping the text field no longer jumps the view to the end, and its helper content now scrolls separately without disrupting cursor placement https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/269

### v1.8.1-dev.04 (2026-03-02)


#### Features

- Corrected a few inconsistencies in Settings https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/240
- Aligned patcher step naming in the notification with the steps on the patcher screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/253
- Added a loading screen that appears when selecting an app from storage from the `Select an app` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/255
- Added support for the ReVanced Patcher v22 while keeping backwards compatibility with v21 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/252


#### Bug fixes

- Fixed an issue where exported keystores would have the `.json` file extension https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/238
- Fixed several UI issues and visual bugs on the `Create custom YouTube icons & headers` tool screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/243
- Fixed an issue where the `Tools` tab wasn't scrollable
- Fixed the wrong UI being shown when switching tabs quickly on the main screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/245
- Clarified the patch profile APK placeholder text from `No APK selected` to `No APK for this package` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/241
- Fixed issues with the patching notification not appearing immediately when patching starts https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/256
- Fixed an issue where the patching notification would disappear before patching was actually finished https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/257
- Fixed an issue where using the Android Documents Provider to export apps from `Downloads` would cause a crash https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/258
- Fixed an issue where the patcher process would die for some users https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/267

### v1.8.1-dev.03 (2026-02-23)


#### Features

- Improved bundle and manager update alerts and websocket status wording
- Added a `Split APK installer` tool to the `Tools` tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/189
- Redesigned and completely reorganized the settings screens https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/222
- Slight UI corrections and improvements in multiple parts of the app https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/223
- Added an option to choose an APK from URV downloaded apps for the Patch Profiles preset APK setting https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/224
- Made the package name of apps always shown on the `App info` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/225
- Improved the `Create custom YouTube icons & headers` tool https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/211
- Added a toggle in Settings > Patcher that disables/enables the expansion of the main categories on the patcher screen that are running https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/201


#### Bug fixes

- Fixed issues with memeory on Android 10 and lower devices (needs testing) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/150
- Fixed the `Patch confirmation` screen on older Android versions being covered by the three button navigation
- Fixed issues with the patcher screen being "frozen" and showing no progress or anyting at all for a few seconds on older Android versions
- Fixed UI lagging/buffering issues on the `Download APK file` step
- Fixed issues where downloading certain APK files using the downloader plugins would cause an error https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/221
- Fixed issues with the swiping to switch tabs guesture on the main screen and patch selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/204


#### CI

- All prereleases/releases are published with a universal (all ABI) APK, and the ABI specific APKs. The in-app updater now automatically picks the APK that matches your ABI

### v1.8.1-dev.02 (2026-02-21)


#### Features

- Pressing the system back buton on the `Apps` tab now exits the app
- Made search queries and filters on the custom file picker persist across sessions https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/209
- Improved export filenames for patch selections and keystores https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/205
- Added persistent bundle sorting to the `Patch Bundle Discovery` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/206
- Added a toggle in Settings > General that disables the swipe guesture on the patch selection screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/212
- Added live patching progress foreground notifications https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/207
- Set keystores generated with the `Keystore creator` tool to use the maximum possible expiration date
- Improved the push notification system by using websockets (this avoids having to use FCM) to keep the notifcation worker alive
- Added push notifications for manager updates https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/214
- Renamed `Search bundles` on the `Discover patch bundles` screen to `Search by bundle name` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/219


#### Bug fixes

- Fixed issues with loading patches from patch bundles on the discovery
- Fixed an issue where saved app entries would be duplicated when installing a entry marked as `Saved`
- Fixed issues with loading metadata for certain APKs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/216
- Fixed missing-split install failures when patching apps that are installed as split APKs https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/217

### v1.8.1-dev.01 (2026-02-19)


#### Features

- Adjusted the wording for the search bar in the custom file picker from `Search folder` to `Search current directory` https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/183
- Rename the `Show patch summary before patching` settings toggle to `Show patch confirmation screen` for consistency
- Made the filter selection states on the `Select an app` screen persist https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/193
- Added setting to disable the swipe gesture to switch between tabs on the main screen (located in Settings > General) https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/196
- Added the android document providers directory sort filters to the custom file picker https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/182
- Added patch option & value preview cards to the patch selection screens patch widgets https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/188
- Made it so hold tapping on the top left back button on the patcher screen brings you back to the `Apps` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/190
- Improved the metadata loader for split APKs on the `App info` screen https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/190
- Made the patch bundle bar on the patch selection screen show even when theres only one bundle available https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/192
- Added a versioned per-runtime framework cache keys and updated modern AAPT2 binaries to TechnoIndian builds that are used for SDK 35+ patching
- Added upstream changes https://github.com/ReVanced/revanced-manager/pull/2916
- Allow external apps to appear in the Android document provider as options to select files with https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/203


#### Bug fixes

- Fixed pressing the system back button on the `Patch Profiles` and `Tools` tabs sending the user back to the Android home screen instead of a different tab https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/191
- Fixed the `Patch confirmation` screen's scroll bar being very large https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/186
- Fixed the Android document provider file picker not resolving intents to local paths https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/202 
- Fixed an issue where the export filename variables wouldn't be placed at the cursor position https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/187
- Allow external apps to appear in the Android document provider as options to select files with https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/203
- Fixed the `Effective memory limit` listing in the patcher logs using the `Requested memory limit value` (needs testing)

## Changes after v1.9.0-dev.1

### Bug Fixes

* correct file picker sorting and log export feedback ([f01260d](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/f01260d3b5c4e5d0449954257ca0953878433f9e)), closes [#668](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/668) [#732](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/732)
* **deps:** bump Morphe Patcher to `1.14.1` ([942e286](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/942e28650deea5921527c50f4ba85a2d4faab1b1))
* **manager:** fix repatching, batch installs and scrolling UI ([6b0d3bc](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/6b0d3bc682224986e16461138eed63ec87813f58))
* **morphe:** correctly apply patch results and isolate workspace ([3aacd3a](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/3aacd3aba8a418c50692428dfb9fb9f94e051827))
* **morphe:** update ARSCLib for theme previews ([#743](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/743)) ([ca5b28f](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/ca5b28f31c6a1c1c6a2cdf3a7920feae2a067588)), closes [#1043](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/1043) [#740](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/740)
* **picker:** save APK folders before processing ([0cf5144](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/0cf5144c4c0b06f2b3d93bd6f3cf0061e5175bb6)), closes [#732](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/732)
* scope required patches and reopen completed steps ([fb0e9ce](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/fb0e9cedd6c21481299a27f5481ae661ff11afdc)), closes [#670](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/670) [#675](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/675)
* **settings:** prevent empty exports ([0ac73cd](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/0ac73cd134e4121712cf35ca46070c33f4f6097d)), closes [#742](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/742)
* **settings:** restore imports in one pass ([45981a8](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/45981a8e4d5717cd804fe148a54f66ebf3028313))
* **splits:** keep selection presets in sync with filters ([18ff36b](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/18ff36bba864384a6815b6f80e85330125298e66)), closes [#667](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/667) [#674](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/674)
* **storage:** automatically clean unused patch files ([b1d0b62](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/b1d0b62809bbc7ef048d3ddc988c2a6e4c8d78fd)), closes [#750](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/750)


### Features

* **bundles:** improve external bundle discovery and update handling ([7576a65](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/7576a65bb5b872cb55a2071741a8822ad6ac0d2d))
* **manager:** improve bundle updates and repatch workflows ([5ab0751](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/5ab0751fa2aa31af270dd545ed911b8948b7e6a0)), closes [#709](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/709) [#692](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/692) [#687](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/687) [#639](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/639) [#683](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/683) [#682](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/682)
* **manager:** improve file picking, patching and backups ([#749](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/749)) ([0eada70](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/0eada70c4f291ad33d62c82e954fc2615df3cc14))
* **manager:** improve patching, bundle, and settings workflows ([d86bc1f](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/d86bc1f1e3cbb06476f82debf4531ea3eaa30780)), closes [#621](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/621) [#617](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/617) [#603](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/603) [#735](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/735) [#734](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/734) [#703](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/703) [#702](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/702) [#729](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/729) [#726](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/726) [#725](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/725) [#718](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/718) [#711](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/711)
* **patcher:** add patcher and merger resource monitoring ([#748](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/748)) ([5ca1a04](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/5ca1a04cb6114a2e29e6407bc86645a9481cb89b))
* **patcher:** bump morphe patcher to `1.15.0` ([0cbb49a](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/0cbb49a7c8df8e524d0ba0715d3f2b788ed1007d))
* **root:** overhaul rooted mount system with split APK support and resilient recovery ([#722](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/722)) ([600aa60](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/600aa60ab6ca775d297a6e2fb25ad61257ea3df1))
* **settings:** add graph detail controls and fix theme restarts ([4ff03e9](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/4ff03e9516290af28a0aab6769437ec228234eed))
* **signature:** add APK signing and signature check controls ([fcddef8](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/fcddef81c5e10c150a24edd653e563332eaf9aad))
* **signature:** add patching workflows and APK source selection ([#741](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/741)) ([ba839ae](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/ba839ae19f1ea109f0092b6aa76847cb34eb0e0f))
* **storage:** clean unused Repatch sources automatically ([f03345b](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/f03345bf9a9873dfa851a9e24bbb9eca17988305)), closes [#745](https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/745)


### Performance Improvements

* **patcher:** reduce APK writing and signing overhead ([d0c98a4](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/d0c98a4e6bf7c49c1e52fffd2707782c4cfea8df))

# [1.9.0-dev.2](https://github.com/Jman-Github/Universal-ReVanced-Manager/compare/v1.9.0-dev.1...v1.9.0-dev.2) (2026-09-20)


### Bug Fixes

* **morphe:** correctly apply patch results and isolate workspace ([3aacd3a](https://github.com/Jman-Github/Universal-ReVanced-Manager/commit/3aacd3aba8a418c50692428dfb9fb9f94e051827))

# v1.9.0-dev.1

# Features

- Added elapsed patching time to the Patcher Information widget for both regular and batch patching
- Added Repatch APK source retention so URV automatically reuses the original APK or split archive when repatching, with support for saved apps, batch patching, missing-source fallback, and Storage Management cleanup
- Added a delete action to individual saved unpatched app cards in Downloads, with a confirmation prompt before removing the stored APK
- Bumped Morphe Patcher to `1.9.0`
- Added configurable Play Store installation source modes for the system and rooted mount installers, adapted from https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
- Bumped Morphe Patcher to `1.12.0`
- Bumped Morphe Patcher to `1.14.0`
- Patch bundle changelogs can now display the full changelog history, depending on your changelog loading and cache limit settings. This replaces the previous `Previous changelogs` section
- Added patcher-style installation feedback to the Split APK Merger, including installation cancellation, a success dialog, and distinct patched/merged app installation messages https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/666 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/671 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/672


# Bug fixes

- Fixed patch bundle discovery preferring stale primary results by selecting the newest available data, falling back to the dev service, and importing bundles through the v3 API
- Fixed saved patched apps installed through Shizuku-based custom installers showing Shell instead of the selected installer
- Fixed pre-release updates not being detected for some patch bundles imported through GitHub or GitLab URLs
- Fixed patch bundle tabs showing inaccurate counts by displaying selected and available patches as a selected/total value
- Fixed Rooted Mount being unavailable in post-patch installer choosers for compatible Standard patches
- Fixed the Patcher Information widget sometimes showing the wrong app version instead of the version of the APK actually being patched
- Fixed single and batch patching sometimes using or reporting stale patch selections, options, app metadata, and runtime information, especially after missing patches were removed or the patcher screen was restored
- Fixed system Play Store installs still appearing as installed by URV Manager in Android settings
- Fixed some Morphe patch bundles failing to load in process mode with an incorrect corrupted or incomplete error
- Fixed patcher runtimes not consistently honoring `Continue after patch errors`, including process runtimes continuing after fatal patch errors when it is disabled https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/685
- Fixed the Split APK Merger progress notification being able to reappear after cancellation when concurrent merge state or progress updates raced with cancellation cleanup https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/669
- Fixed returning from a cancelled local APK patch leaving the app name and icon blank when the patcher's temporary input had already been deleted https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/665
- Fixed issues with remote patch bundles when using GitHub or GitLab URLs sometimes not detecting prereleases correctly
- Fixed incomplete patch bundle changelogs and missing history entries, including hidden release notes, overwritten descriptions, mixed release channels, and refresh errors not appearing when cached history is available
- Fixed ReVanced 21 and 22 patching failing before patches are applied when using process mode
- Provide a writable temporary workspace for Morphe patches in both in-process and process-mode patching
- Fixed patch bundle import and update feedback by preserving bundle name casing, identifying the selected release channel, and using correct singular/plural progress wording https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/601 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/632 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/633
- Fixed incorrect singular/plural wording when removing saved patches and displaying patch bundle update badges https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/605 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/680
- Fixed cancelling APK preparation incorrectly displaying a failure dialog https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/610
- Improved split selection dialogs by removing outdated guidance and aligning the Cancel action consistently https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/613 https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/618
- Fixed manager, installed app, and applied patch bundle version displays to consistently use a single `v` prefix https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/704
- Fixed the Process Memory Limit dialog title alignment https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/676
- Fixed unsupported keystore converter errors wrapping unnaturally https://github.com/Jman-Github/Universal-ReVanced-Manager/issues/673


# CI

- Added a manual debug build workflow that uploads dev APK artifacts with a `-debug` version suffix without publishing a release
- Updated GitHub Actions dependencies to current Node 24-compatible versions, resolving Node.js 20 and deprecated action warnings
- Converted repository to semantic release based system instead of the manual mess I had before
