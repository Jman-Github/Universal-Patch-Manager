<p align="center">
  <picture>
    <source
      width="660px"
      media="(prefers-color-scheme: dark)"
      srcset="assets/icons/upm-patchgrid-horizontal-dark.svg"
    >
    <img
      width="660px"
      src="assets/icons/upm-patchgrid-horizontal.svg"
      alt="Universal Patch Manager"
    />
  </picture>
</p>

# 💊 Universal Patch Manager

Universal Patch Manager (UPM) is an Android manager for patching apps with ReVanced, Morphe, and Reseam in one place. Built from ReVanced Manager, UPM expands the full patching workflow with third-party patch bundles, multiple patcher runtimes, saved apps and patch profiles, batch and automatic repatching, flexible APK sources, advanced installers, and standalone APK utilities.

UPM supports the ReVanced, Morphe, and Reseam patch ecosystems, rooted and non-rooted workflows, local and remote patch bundle sources, split APK handling, downloader integrations, signature and keystore tools, detailed patching diagnostics, backups, storage controls, and extensive UI customization.

<p align="center">
  <img src="https://img.shields.io/badge/License-GPL%20v3-yellow.svg" alt="GPLv3 License" />
  &nbsp;
  <a href="https://crowdin.com/project/universal-revanced-manager" style="text-decoration:none;"><img src="https://badges.crowdin.net/universal-revanced-manager/localized.svg" alt="Crowdin" /></a>
  &nbsp;
  <a href="https://crowdin.com/project/universal-revanced-manager" style="text-decoration:none;"><img src="https://img.shields.io/badge/Crowdin-Join-2E3340?logo=crowdin&logoColor=white" alt="Crowdin" /></a>
  &nbsp;
  <a href="https://t.me/urv_chat" style="text-decoration:none;"><img src="https://img.shields.io/badge/Telegram-Chat-2CA5E0?logo=telegram&logoColor=white" alt="Telegram" /></a>
  &nbsp;
  <a href="https://discord.gg/xZAqRHSp3V" style="text-decoration:none;"><img src="https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

## 💪 Features

UPM covers the full patching lifecycle, from choosing an APK source and patch bundle to patching, installing, saving, repatching, automating updates, and managing the files and tools around those workflows.

For runtime behavior, memory limits, and troubleshooting, see the [patching runtime guide](upm-patcher-core/README.md).

<details>
<summary><strong>Patch Bundles & Patcher Runtimes</strong></summary>
<ul>
  <li><strong>ReVanced, Morphe, and Reseam in one manager:</strong> Use ReVanced <code>.rvp</code>, Morphe <code>.mpp</code>, and Reseam <code>.reseam</code> patch bundles from the same app.</li>
  <li><strong>Third-party patch bundles:</strong> Add compatible local or remote patch bundles, including repository and release-based sources.</li>
  <li><strong>Bundle discovery:</strong> Browse the <a href="https://github.com/Jman-Github/Patch-Bundle-Registry">Patch Bundle Registry</a> catalog in UPM, search it, inspect supported apps and patches, and import bundles directly.</li>
  <li><strong>Remote updates:</strong> Check, download, and background-update remote bundles with progress, stable/pre-release channel handling, per-bundle update controls, and force-redownload support.</li>
  <li><strong>Full changelog history:</strong> View cached patch bundle changelog history with configurable loading and storage limits, plus release links when available.</li>
  <li><strong>Bundle organization:</strong> Rename imported bundles, reorder them, preserve metadata and timestamps, and export/import bundle configurations.</li>
  <li><strong>Built-in runtimes:</strong> Morphe and Reseam patching runtimes are bundled with UPM. Morphe supports JVM or ART execution; Reseam uses its native Android runtime.</li>
  <li><strong>Configurable ART memory:</strong> Reseam and ART fallbacks support a separate 512-2048 MB heap slider (default 1024 MB), while JVM remains preferred where supported. Android 11+ uses fresh ART workers with heap overrides; Android 8-10 retains Android-managed workers. Requests are capped at 1024 MB in 32-bit processes. If startup needs a smaller heap, UPM retries that run without changing your saved setting. Logs report the actual heap limit. Native memory is additional to the Java/Kotlin heap.</li>
  <li><strong>ReVanced v21 runtime plugin:</strong> Add the supported ReVanced v21 runtime plugin from a local APK or GitHub source and manage its trust, update channel, installed version, and stored plugin files.</li>
  <li><strong>ReVanced v22 runtime plugin:</strong> Add the ReVanced v22 plugin with the same trust and update controls as v21. Both JVM and ART execution are supported.</li>
</ul>
</details>

<details>
<summary><strong>Patch Selection, Profiles & Patching</strong></summary>
<ul>
  <li><strong>Flexible patch selection:</strong> Select/deselect all patches globally or per bundle, undo/redo changes, filter new patches, inspect option values, and switch between bundle recommendations and other supported versions.</li>
  <li><strong>Patch confirmation:</strong> Review selected bundles, patches, options, and relevant configuration before patching.</li>
  <li><strong>Patch profiles:</strong> Save patch selections, option values, target versions, a reusable APK/split source, installer choice, signature workflow, auto-install behavior, and one-tap patching preferences.</li>
  <li><strong>Single and batch patching:</strong> Patch one app or build a multi-app queue with per-app configuration, reordering, cancellation, detailed progress/results, and install-all actions.</li>
  <li><strong>Automatic repatching:</strong> Periodically repatch opted-in saved apps when their patch bundles change, with configurable intervals, charging requirements, notifications, and optional Shizuku/Shevery installation.</li>
  <li><strong>Repatch source retention:</strong> Keep the original APK or split archive used for a saved app so future manual, batch, and automatic repatches can reuse it without asking for the source again.</li>
  <li><strong>Split APK preparation:</strong> Patch <code>.apkm</code>, <code>.apks</code>, <code>.xapk</code>, and compatible split archives with selectable modules and cleanup filters for unused languages, densities, and native libraries.</li>
  <li><strong>Install-target-aware patches:</strong> Morphe patch availability can adapt to Standard or Root Mount patching, including required/unavailable patches and optional GmsCore handling.</li>
  <li><strong>Signature metadata workflow:</strong> Optionally clone signature metadata from the original signed input into the final patched APK and remember that choice for saved apps, profiles, batch jobs, and automatic repatching.</li>
  <li><strong>Signing control:</strong> Use the manager keystore, import compatible keystores, or skip patched APK signing where supported.</li>
  <li><strong>Detailed patcher diagnostics:</strong> View elapsed time, app/runtime/device information, selected patches, split/native-library state, and expandable patcher steps and sub-steps.</li>
  <li><strong>Live resource monitoring:</strong> Optional memory, CPU, and storage I/O graphs are available during patching, with compact layouts and peak/current readings.</li>
  <li><strong>Patcher logs:</strong> Export detailed logs with app, device, runtime, selected patch, split, and failure information.</li>
</ul>
</details>

<details>
<summary><strong>APK Sources, Downloaders & Saved Apps</strong></summary>
<ul>
  <li><strong>Multiple APK sources:</strong> Start from installed apps, local storage, manager-downloaded APKs, downloader plugins, supported helper apps, or split archives depending on the workflow.</li>
  <li><strong>Downloader plugins:</strong> Install, update, configure, trust/revoke, and remove compatible downloader plugins directly in UPM.</li>
  <li><strong>APK download helper apps:</strong> Use supported standalone helper apps as APK sources, with helper discovery, signer trust, GitHub source management, and install/update controls.</li>
  <li><strong>Download cache management:</strong> Keep multiple downloaded APKs, export or delete individual entries, and optionally retain only the newest download for each app.</li>
  <li><strong>Saved patched apps:</strong> Keep patched APKs in the Apps tab for later install/export/repatch workflows and inspect the patches, bundles, and related metadata used to create them.</li>
  <li><strong>Saved app organization:</strong> Search and reorder saved apps, manage update badges, and control whether patched apps are automatically saved.</li>
</ul>
</details>

<details>
<summary><strong>Installation & Root Workflows</strong></summary>
<ul>
  <li><strong>Central installer manager:</strong> Configure primary and fallback installers and choose installers per install or patching result, saved app, patch profile, or tool workflow.</li>
  <li><strong>Multiple install methods:</strong> Use the Android system installer, Shizuku/Shevery/Sui, supported custom installers, rooted installation, or Root Mount where compatible.</li>
  <li><strong>Google Play attribution:</strong> Supported system, Shizuku/Shevery, and rooted install modes can record Google Play Store as the installation source.</li>
  <li><strong>Root Mount:</strong> Mount compatible patched APKs over installed stock apps with stock-source checks, split-input compatibility, post-patch routing, and install-target-aware patch selection.</li>
  <li><strong>Root recovery:</strong> Track mounted apps across reboots, repair broken mounts, recover stock sources where possible, and export diagnostics when a mount needs attention.</li>
  <li><strong>Installation controls:</strong> Cancel in-progress installs, handle signature conflicts with clearer recovery options, and automatically install successful patch results when configured.</li>
</ul>
</details>

<details>
<summary><strong>Tools & System Utilities</strong></summary>
<ul>
  <li><strong>Split APK merger:</strong> Merge <code>.xapk</code>, <code>.apkm</code>, <code>.apks</code>, and compatible ZIP archives into a single APK with selectable split filters, signing, install/save actions, progress sub-steps, and live resource monitoring.</li>
  <li><strong>Split installer:</strong> Install compatible split packages directly without first merging them into a single APK.</li>
  <li><strong>APK signer:</strong> Sign APKs with the manager's signing configuration from a standalone tool.</li>
  <li><strong>Signature metadata injector &amp; cloner:</strong> Inspect sources and clone/inject legacy signature metadata into APK or split targets with selectable signing/output behavior.</li>
  <li><strong>Keystore creator:</strong> Generate new signing keystores in supported formats with configurable credentials and long-lived certificates.</li>
  <li><strong>Keystore converter:</strong> Convert supported keystore formats directly in the app.</li>
  <li><strong>Custom YouTube assets:</strong> Build custom adaptive icons and header assets with positioning, color, preset, and export controls.</li>
  <li><strong>LSPosed module management:</strong> Optional rooted tab for adding, updating, installing, and organizing user-added LSPosed-compatible modules from GitHub or local storage.</li>
</ul>
</details>

<details>
<summary><strong>Storage, Backups & File Management</strong></summary>
<ul>
  <li><strong>Storage dashboard:</strong> Inspect UPM storage usage across caches, patch bundles, runtime/downloader plugin files, saved APKs, repatch inputs, patch-profile inputs, signing files, LSPosed modules, backgrounds, and other app data.</li>
  <li><strong>Targeted cleanup:</strong> Clear individual storage areas, clear recreatable caches, or configure scheduled automatic cache cleanup.</li>
  <li><strong>Settings and data export/import:</strong> Back up and restore manager settings and supported UPM configuration data, including patch-related preferences and organization state.</li>
  <li><strong>Signing-file handling:</strong> Import/export compatible keystores while keeping signing material out of Android cloud backups.</li>
  <li><strong>Custom file picker:</strong> Favorite paths, remember folders per workflow, persist sorting/search preferences, show hidden files, and use Android document providers where supported.</li>
</ul>
</details>

<details>
<summary><strong>Appearance, Navigation & Search</strong></summary>
<ul>
  <li><strong>Theme customization:</strong> Choose theme/accent colors with presets, HEX input, and live previews, plus monochrome and pure-black options where supported.</li>
  <li><strong>Custom backgrounds:</strong> Import a background image, adjust its transparency, and keep a private app copy so it remains available if the original moves.</li>
  <li><strong>Navigation controls:</strong> Configure tab visibility/labels, swipe navigation behavior, action layouts, and other interaction preferences.</li>
  <li><strong>Search throughout UPM:</strong> Search settings with jump-to highlighting and search the Apps, Patch Bundles, Patch Profiles, discovery, patch selection, and file-picker workflows.</li>
  <li><strong>Patch-selection layouts:</strong> Use standard or more compact/minimal patch selection views and keep relevant filters/presets across sessions.</li>
</ul>
</details>

<details>
<summary><strong>Updates, Notifications & Advanced Controls</strong></summary>
<ul>
  <li><strong>Manager updates:</strong> Check for new UPM releases, preview changelogs in-app, and receive update notifications.</li>
  <li><strong>Patch bundle notifications:</strong> Get grouped update alerts and background download progress for bundle updates, including manual and automatic delivery modes.</li>
  <li><strong>Metered-network controls:</strong> Decide whether manager and patch bundle update checks/downloads may run on metered connections.</li>
  <li><strong>Developer options:</strong> Advanced controls remain directly accessible for patcher behavior, process/runtime settings, logging, memory handling, notification behavior, and diagnostics.</li>
  <li><strong>External automation:</strong> When external batch actions are enabled, automation apps can request batch patching, while launcher shortcuts can trigger update/repatch workflows.</li>
</ul>
</details>

<details>
<summary><strong>Localization</strong></summary>
<ul>
  <li><strong>Device language support:</strong> Follow the device language when a supported translation is available, with English fallback behavior.</li>
  <li><strong>Selectable app languages:</strong> English, German, Spanish, French, Chinese (Simplified), Indonesian, Hindi, Gujarati, Portuguese (Brazil), Vietnamese, Korean, Japanese, Russian, Turkish, and Ukrainian.</li>
  <li><strong>In-app switching:</strong> Change languages from Settings with a restart prompt when needed.</li>
</ul>
</details>

## 🔽 Download

You can download the most recent version of Universal Patch Manager from [GitHub releases](https://github.com/Jman-Github/universal-patch-manager/releases).

UP Manager uses the package <code>app.universal.patch.manager</code> and installs separately from the former URV app. Before switching, export your data from the old app using **Settings > Export & Import > Export everything**, then import that backup into UP Manager. Export and import your signing keystore separately on the same settings page so your patched apps keep the same signing key. Keep the old app installed until you have verified the imported data and key.

Existing URV JSON exports can be imported directly without renaming the files: **Import everything**, manager settings, patch bundles, patch profiles, and patch selections all retain their existing formats. UPM migrates the former manager API setting and manager-hosted plugin source URLs, and recognizes the old runtime package identities in imported settings. User-entered names, package names, options, and signing credentials are preserved.

Export/import the signing keystore separately; JSON backups do not include its key material. Files stored privately by URV and Android file-access grants do not move with a JSON backup. Reselect custom backgrounds, local bundle files, APK inputs, or option files if Android no longer grants access to them.

The embedded JVM uses OpenJDK 25 with compact object headers in 64-bit processes and OpenJDK 17 on 32-bit ARM. This configuration follows [Morphe Manager PR #1302](https://github.com/MorpheApp/morphe-manager/pull/1302). Reseam continues to run in its isolated ART worker.

Runtime plugins must be built for UPM: <code>app.universal.patch.manager.revanced.v21.runtime</code> and <code>app.universal.patch.manager.revanced.v22.runtime</code>. The bundled Reseam runtime uses <code>app.universal.patch.manager.reseam.runtime</code>, and Morphe uses <code>app.universal.patch.manager.morphe.runtime</code>.


## 📋 Patch Bundles

UPM starts with no patch bundles installed. Add bundles through discovery or import.

UPM can add compatible ReVanced (<code>.rvp</code>), Morphe (<code>.mpp</code>), and Reseam (<code>.reseam</code>) patch bundles from local files, supported remote URLs, repository/release sources, and the in-app discovery catalog. ReVanced v21 and v22 bundles require their respective runtime plugins. Morphe and Reseam are included by default.

The [Patch Bundle Registry](https://github.com/Jman-Github/Patch-Bundle-Registry) maintains a searchable catalog of community patch sources. Visit its [patch browsing site](https://patch-bundles.jmancentral.com/) to explore bundles, patch lists, supported apps, release versions, and changelogs, and copy remote bundle URLs for import into UPM.

The same registry powers UPM's in-app patch bundle discovery, where you can search the catalog, inspect compatible apps and patches, and import supported bundles directly.

## 🔌 Supported Downloader Plugins

UPM supports compatible downloader plugins and standalone APK download helper apps as reusable APK sources. They can be installed or imported from supported GitHub repository, release, or APK asset URLs, managed from Downloader settings, and used directly in patching and tool workflows.

The [ReVanced Manager Downloaders](https://github.com/brosssh/revanced-manager-downloaders/releases) repository provides compatible downloader plugins for sources such as APKMirror, APKPure, and APKCombo. UPM also supports standalone APK download helper apps, such as [Helper For Morphe](https://github.com/rushiranpise/helper-for-morphe/releases).

## ⭐ Star History

<a href="https://star-history.dera.page/#Jman-Github/Universal-Patch-Manager">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=Jman-Github/Universal-Patch-Manager&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=Jman-Github/Universal-Patch-Manager" />
   <img alt="Star History Chart" src="https://star-history.dera.page/svg?repos=Jman-Github/Universal-Patch-Manager" />
 </picture>
</a>

## ⚖️ License

Universal Patch Manager is licensed under the GPLv3 license. Please see the [license file](https://github.com/Jman-Github/universal-patch-manager/blob/main/LICENSE) for more information.
[tl;dr](https://www.tldrlegal.com/license/gnu-general-public-license-v3-gpl-3) you may copy, distribute and modify Universal Patch Manager as long as you track changes/dates in source files.
Any modifications to Universal Patch Manager must also be made available under the GPL, along with build & install instructions.
