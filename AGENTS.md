# AGENTS.md

AmWF is a single-module Android app (`:app`) for rooted Qualcomm Snapdragon devices. It controls WiFi monitor mode through Qualcomm's `con_mode` and includes a WPS Pixie Dust audit flow. Kotlin + XML/ViewBinding + AppCompat/Material 3; no Compose and no DI framework (dependencies are constructed manually).

## Build and verification

- Debug APK: `./gradlew :app:assembleDebug`
- JVM unit tests: `./gradlew :app:testDebugUnitTest`
- One test class: `./gradlew :app:testDebugUnitTest --tests "com.hitomatito.amwf.wifi.pixiedust.M3ParserTest"`
- Lint: `./gradlew :app:lintDebug`
- Full local check: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- Instrumented tests: `./gradlew :app:connectedDebugAndroidTest`. Real monitor-mode and Pixie Dust behavior requires a rooted ARM device; x86/x86_64 emulators can install the app but cannot execute the ARM-only native assets.
- There is no CI workflow; run the relevant checks locally.
- Toolchain: Gradle 9.3.1, AGP 9.1, compileSdk 36.1, minSdk 24, Java 11 bytecode. AGP 9 compiles Kotlin itself: do not add `org.jetbrains.kotlin.android`. The Gradle daemon uses Java 21 via `gradle/gradle-daemon-jvm.properties` and the foojay resolver.
- Root `keystore.properties` is optional and gitignored. Release builds use it when present and remain unsigned otherwise.

Unit tests are plain JUnit 4 over pure functions; there is no Robolectric. Extract parsing, filtering, permission-matrix and transition logic into pure functions rather than trying to unit-test Android/root code.

## Architecture and invariants

- `MainActivity` is the monitor-mode/compatibility entry point; `MonitorModeManager` performs the radio and `con_mode` changes. `LocaleHelper` applies the persisted ES/EN locale in `attachBaseContext`.
- `DeviceCompatibilityChecker` performs real capture/injection and `con_mode` write tests. It disables WiFi before those tests, toggles the radio during capability checks and centralizes the final restore/restart. Preserve that ordering to avoid extra WiFi drops. Results are cached in `MainActivity` statics so `recreate()` does not rerun the disruptive test.
- `resolveConModePath()` must retain root-based discovery: SELinux can hide `/sys/module` from the app UID, so a `File`-only scan misses valid paths.
- Use `ShellExecutor` for one-shot `su`/`sh` commands and keep its reader threads/timeouts; naive pipe reads can deadlock. `SupplicantProcesses` and `PixiewpsExecutor` own long-lived processes that need streaming output.
- With minSdk 24, API 26+ process calls (`destroyForcibly`, `Process.isAlive`, timed `waitFor`) must go through the `*Compat` helpers in `ProcessCompat.kt`.
- `diagnostics/` exports passive state plus the in-memory `AppLogger` through FileProvider. `DiagnosticsFormat` redacts MACs and SSIDs; preserve redaction for new report fields.
- In `wifi/pixiedust/`, `PixieDustRunner` orchestrates phases/retries and WiFi restoration. `SupplicantProcesses`, `PixiewpsExecutor`, `PixiePskExtractor` and `PixieProcessSupport` own native-process work; `PixieAuditLog` persists audit logs. Keep this separation instead of rebuilding a runner god class.
- `runAttack` must persist the audit log on every exit path (validation, missing binaries, capture failure, success and cancellation) and flush the supplicant queue before cleanup, or the final M3 lines are lost. Logs in `/sdcard/AmWF/pixie-logs/` (fallback `filesDir/pixie-logs`) contain plaintext WPS/WPA credentials.
- Pixie Dust UX is intentional: tapping an auditable network selects it and immediately starts the audit with current options (WiFi-disable defaults on); there is no Run button or confirmation dialog. Search, filters, session timer, phase tracker and collapsible console are intentional scope, not unused UI.
- Keep `MainActivity`'s Pixie Dust button enabled even when its alpha is dimmed. If compatibility already confirmed missing binaries, the tap shows the summary instead of opening the audit; otherwise it opens the screen.
- Route WiFi scan permission and location-services checks through `WifiScanPermissions`: precise location is required on every Android version, plus `NEARBY_WIFI_DEVICES` on Android 13+.
- Validate BSSID/interface input and shell-quote user-controlled values before privileged commands.

## Native Pixie Dust binaries

`app/src/main/assets/wifi/` contains prebuilt ARM binaries; normal Gradle builds do not compile them.

- Rebuild with `./scripts/build-wifi-binaries.sh <arm64-v8a|armeabi-v7a>`. Requires NDK r27+ (`ANDROID_NDK`/`ANDROID_NDK_HOME`) and sibling trees `../pixiewps-master`, `../wpa_supplicant-2.11`, `../libnl-3.2.25`.
- arm64-v8a builds `pixiewps`, `wpa_supplicant`, `wpa_cli` and libnl. armeabi-v7a builds only `pixiewps_32`; `wpa_supplicant_32` is intentionally absent and `wpa_cli_32` is a preserved prebuilt asset.
- `NativeBinaries` is the single source of truth for paths, missing-binary detection and messages: `missingBinariesMessage` for detailed failure reasons, `missingBinariesSummary` for compatibility/warning UI. Keep all callers using it.
- Call `NativeBinaries.ensure(context)` before `missingCriticalBinaries()`. Assets are copied lazily on first launch and after app updates, so checking first falsely reports missing binaries.
- Never substitute Android's system `wpa_supplicant`: `CONFIG_ANDROID_LOG` truncates WPS hexdumps to 32 bytes. The bundled supplicant is mandatory for complete M3 capture.
- Use `BINARIES_SETUP.md` for the native rebuild workflow and rationale; its runtime method names predate the runner split, so verify runtime details against current Kotlin. `README.md`'s project structure also predates Pixie Dust.

## Conventions

- Spanish is the default UI language (`res/values/strings.xml`); English is `res/values-en/strings.xml`. Add every user-facing string to both.
- Comments and log messages are predominantly Spanish; match the surrounding file.
- New main-source Kotlin files need the `SPDX-License-Identifier: GPL-3.0-or-later` header.
- Use `ShellExecutor.logD/W/E` for diagnostics-relevant logging so it reaches `AppLogger`; do not add raw `Log` calls for those events.
