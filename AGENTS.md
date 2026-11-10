# AGENTS.md

AmWF is a single-module Android app (`:app`) for rooted Qualcomm Snapdragon devices. It controls WiFi monitor mode through Qualcomm's `con_mode` and includes a WPS Pixie Dust audit flow. Kotlin + XML/ViewBinding + AppCompat/Material 3; no Compose and no DI framework (dependencies are constructed manually).

## Build and verification

- Debug APK: `./gradlew :app:assembleDebug`
- JVM unit tests: `./gradlew :app:testDebugUnitTest`
- One test class: `./gradlew :app:testDebugUnitTest --tests "com.hitomatito.amwf.wifi.pixiedust.M3ParserTest"`
- Lint: `./gradlew :app:lintDebug`
- Full local check: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- Instrumented tests: `./gradlew :app:connectedDebugAndroidTest` (there are no `androidTest` sources at the moment; real monitor-mode and Pixie Dust behavior requires a rooted ARM device, and the native assets are ARM-only, so x86/x86_64 emulators cannot run them).
- There is no CI workflow; run the relevant checks locally.
- CLI builds need `JAVA_HOME` pointing at a JDK (there is no system `java` on PATH; Android Studio's JBR works, e.g. `/opt/android-studio/jbr`).
- Toolchain (see `gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`): Gradle 9.6.0, AGP 9.5.1, compileSdk 37.1 / targetSdk 37, minSdk 24, Java 11 bytecode. AGP 9 compiles Kotlin itself: do not add `org.jetbrains.kotlin.android`. The Gradle daemon uses Java 21 via `gradle/gradle-daemon-jvm.properties` and the foojay resolver.
- Root `keystore.properties` is optional and gitignored. Release builds use it when present and remain unsigned otherwise. Never stage `keystore.properties`, `local.properties` or `*.logcat`; they may hold signing secrets or device data.
- No formatter/static-analysis setup beyond `lintDebug` (no ktlint/detekt/editorconfig); match the existing style.

Unit tests are plain JUnit 4 (plus `kotlinx-coroutines-test` for suspend code); there is no Robolectric. `testOptions.unitTests.isReturnDefaultValues = true` makes Android stubs (e.g. `android.util.Log`) no-ops in JVM tests. Extract parsing, filtering, permission-matrix and transition logic into pure functions rather than trying to unit-test Android/root code.

## Architecture and invariants

- `MainActivity` is the monitor-mode/compatibility entry point; `MonitorModeManager` performs the radio and `con_mode` changes. `LocaleHelper` applies the persisted ES/EN locale in `attachBaseContext`.
- `DeviceCompatibilityChecker` performs real capture/injection and `con_mode` write tests. It disables WiFi before those tests, toggles the radio during capability checks and centralizes the final restore/restart. Preserve that ordering to avoid extra WiFi drops. The check is never run automatically: it is triggered only by the "Verificar compatibilidad" button (it disrupts WiFi); results are cached in `MainActivity` statics so `recreate()` does not rerun the disruptive test, and a recreated Activity waits for an in-flight check instead of starting a second one. Enabling monitor mode requires a prior verification in the process (otherwise a toast); disabling must always remain possible without one.
- `resolveConModePath()` (top-level in `DeviceCompatibilityChecker.kt`) must retain root-based discovery: SELinux can hide `/sys/module` from the app UID, so a `File`-only scan misses valid paths.
- Use `ShellExecutor` for one-shot `su`/`sh` commands and keep its reader threads/timeouts; naive pipe reads can deadlock. `SupplicantProcesses` and `PixiewpsExecutor` own long-lived processes that need streaming output.
- `PixieProcessSupport.pidWatchdogTail` must keep the watchdog as a single `sh -c` command: a multi-command background subshell makes Android's `sh` retain the stdout fd, so the reader does not see EOF until the timeout. `PixiewpsExecutor` also breaks on `PIXIEWPS_DONE_MARKER` as a fallback.
- With minSdk 24, API 26+ process calls (`destroyForcibly`, `Process.isAlive`, timed `waitFor`) must go through the `*Compat` helpers in `ProcessCompat.kt`.
- `diagnostics/` exports passive state plus the in-memory `AppLogger` through FileProvider. `DiagnosticsFormat` redacts MACs (to their OUI) and SSIDs, including SSIDs discovered from `iw dev`; preserve redaction for any new report field.
- In `wifi/pixiedust/`, `PixieDustRunner` orchestrates phases/retries and WiFi restoration. Its Android/process dependencies (supplicant, pixiewps, PSK extractor, native binaries, audit log, shell, WiFi state) are constructor-injected through the gateways in `PixieDustGateways.kt`; production wiring is `PixieDustRunner.create(context)`. Keep the runner free of `Context`: unit tests build it with fakes, so new dependencies must go through a gateway rather than inline `Context`/system-service access. `SupplicantProcesses`, `PixiewpsExecutor`, `PixiePskExtractor` and `PixieProcessSupport` own native-process work; `PixieAuditLog` persists audit logs. Keep this separation instead of rebuilding a runner god class. `cleanup()` must only `pkill` binaries whose cmdline starts with the app's bundled binary directory; never the system supplicant. It must confirm the framework WiFi state (via `WifiRestore.isEnabled`) after `svc wifi enable` and retry: the command can return 0 without turning the radio on (seen on cancel).
- `runAttack` must persist the audit log on every exit path (validation, missing binaries, capture failure, success and cancellation) and flush the supplicant queue before cleanup, or the final M3 lines are lost. Logs in `/sdcard/AmWF/pixie-logs/` (fallback `filesDir/pixie-logs`) contain plaintext WPS/WPA credentials.
- After a successful M3 capture, close the WPS session (`wps_cancel`) before killing the attack supplicant. Otherwise the AP keeps its session open and the PSK extraction, which now starts within seconds, collides with it (the AP retransmits M1 and the registrar fails with "Unexpected state (15)").
- Pixie Dust UX is intentional: tapping an auditable network selects it and immediately starts the audit with current options (WiFi-disable defaults on); there is no Run button or confirmation dialog. Search, filters, session timer, phase tracker and collapsible console are intentional scope, not unused UI.
- Keep `MainActivity`'s Pixie Dust button enabled even when its alpha is dimmed. If compatibility already confirmed missing binaries, the tap shows the summary instead of opening the audit; otherwise it opens the screen.
- Route WiFi scan permission and location-services checks through `WifiScanPermissions`: precise location is required on every Android version, plus `NEARBY_WIFI_DEVICES` on Android 13+.
- `PixieDustReducers.kt` holds the ViewModel's UI-state decisions as pure functions (`attackStartError`, `canStartScan`, `sortNetworksForList`, `attackOutcomeStage`, `*State` transitions). New UI-state decisions go there with unit tests, not inline in the ViewModel.
- Validate BSSID/interface input and shell-quote user-controlled values before privileged commands.

## Native Pixie Dust binaries

`app/src/main/assets/wifi/` contains prebuilt ARM binaries (ABI filters: arm64-v8a, armeabi-v7a); normal Gradle builds do not compile them.

- Rebuild with `./scripts/build-wifi-binaries.sh <arm64-v8a|armeabi-v7a>`. Requires NDK r27+ (`ANDROID_NDK`/`ANDROID_NDK_HOME`) and sibling trees `../pixiewps-master`, `../wpa_supplicant-2.11`, `../libnl-3.2.25`. The script applies the patches in `scripts/patches/` (libnl against wpa_supplicant 2.11, supplicant stdout flush).
- arm64-v8a builds `pixiewps`, `wpa_supplicant`, `wpa_cli` and libnl. armeabi-v7a builds only `pixiewps_32`; `wpa_supplicant_32` is intentionally absent and `wpa_cli_32` is a preserved prebuilt asset.
- `NativeBinaries` is the single source of truth for paths, missing-binary detection and messages: `missingBinariesMessage` for detailed failure reasons, `missingBinariesSummary` for compatibility/warning UI. Keep all callers using it.
- Call `NativeBinaries.ensure(context)` before `missingCriticalBinaries()`. Assets are copied lazily on first launch and after app updates, so checking first falsely reports missing binaries.
- Never substitute Android's system `wpa_supplicant`: `CONFIG_ANDROID_LOG` truncates WPS hexdumps to 32 bytes. The bundled supplicant is mandatory for complete M3 capture.
- `wpa_cli` and `pixiewps` fall back to system paths (`/system/bin`, `/vendor/bin`, `/vendor/bin/hw`, `/system/xbin`, `/odm/bin`) when the bundled asset is missing; the "Binarios Pixie Dust" row can show available because of that. `wpa_supplicant` never falls back.
- Supplicant control dir: `/data/vendor/wifi/amwf` on API 28+, `/data/misc/wifi/amwf` below; devices upgraded to P (not launched with it) keep the legacy path, so if the supplicant fails with "socket not created", try the other directory.
- The runtime supplicant config must be staged into `ctrlDir` via `su` stdin (`execRootWithInput`): the `su` SELinux domain cannot read `filesDir`. Keep `update_config=1` so `save_config` can persist the PSK after WPS provisioning.
- `BINARIES_SETUP.md` documents the native rebuild workflow, SELinux notes and the system-binary fallback (in English); `THIRD-PARTY-NOTICES.md` lists bundled binaries, their versions and licenses; `SECURITY.md` tells reporters how to disclose safely. Trust them over older notes.

## Conventions

- Spanish is the default UI language (`res/values/strings.xml`); English is `res/values-en/strings.xml`. Add every user-facing string to both.
- Comments and log messages are predominantly Spanish; match the surrounding file.
- New main-source Kotlin files need the `SPDX-License-Identifier: GPL-3.0-or-later` header.
- Use `ShellExecutor.logD/W/E` for diagnostics-relevant logging so it reaches `AppLogger`. Exception: `PixieDustRunner.log` intentionally uses raw `Log.d` because its lines contain M3 material and PIN/PSK; `AppLogger` is exported in diagnostics, so never route audit output through it.
- Commits follow Conventional Commits (`feat|fix|refactor|docs|chore|build(scope): summary`, English subject). Release commits bump `versionCode` and `versionName` in `app/build.gradle.kts` and use `chore(release): ...`.
