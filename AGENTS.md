# AGENTS.md

AmWF is a single-module Android app (`:app`) for rooted Qualcomm Snapdragon devices. It controls WiFi monitor mode through Qualcomm's `con_mode` and includes a WPS Pixie Dust audit flow. Kotlin + XML layouts/ViewBinding + AppCompat/Material 3; no Compose.

## Build and test

- Build debug APK: `./gradlew :app:assembleDebug`
- Run all JVM unit tests: `./gradlew :app:testDebugUnitTest`
- Run one test class: `./gradlew :app:testDebugUnitTest --tests "com.hitomatito.amwf.wifi.pixiedust.M3ParserTest"`
- Lint: `./gradlew :app:lintDebug`
- Instrumented tests are only meaningful on a rooted ARM device: `./gradlew :app:connectedDebugAndroidTest`
- Toolchain: Gradle 9.3.1, AGP 9.1, compileSdk 36.1, minSdk 24, Java 11 target. AGP 9 compiles Kotlin itself: do not add the standalone `org.jetbrains.kotlin.android` plugin.
- `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon to Java 21; the foojay resolver downloads it on first use. App bytecode still targets Java 11.
- `keystore.properties` at the repo root is optional and gitignored. If present, release builds use it for signing; if absent, release remains unsigned.
- There is no CI workflow in the repo; run these commands locally.

Unit tests are plain JUnit over pure functions (M3 hexdump parsing, `con_mode` path selection, chipset parsing, the scan-permission matrix, binary naming, diagnostics redaction); there is no Robolectric, so extract new logic into pure functions instead of trying to test Android/root code. Real monitor-mode and Pixie Dust behavior needs a rooted Snapdragon device and cannot be validated in an emulator.

## Architecture

- `MainActivity` — monitor-mode UI and compatibility screen; `MonitorModeManager` — enable/disable/read monitor mode; `LocaleHelper` — persisted ES/EN locale applied in `attachBaseContext`.
- `DeviceCompatibilityChecker` — root, chipset, driver and `con_mode` detection plus real capture/injection tests. It stops WiFi before the `con_mode` write tests and centralizes the final restore/restart at the end; capability tests also toggle the radio. Preserve that flow to avoid extra WiFi drops; compatibility results are cached in `MainActivity` statics so configuration changes and language toggles (`recreate()`) don't rerun the disruptive test.
- `ShellExecutor` — shared one-shot `su`/`sh` execution plus structured logging. Keep its reader threads and timeout logic; long root commands can deadlock with a naive `readText()`. `SupplicantProcesses` and `PixiewpsExecutor` start long-lived supplicant/pixiewps processes directly when they need streaming output.
- `diagnostics/` — passive report export (`CompatibilityResult` + in-memory `AppLogger` lines) shared via FileProvider. MACs and SSIDs are redacted by `DiagnosticsFormat`; preserve that for new report fields.
- `wifi/pixiedust/` — WPS audit screen/ViewModel, `PixieSessionRenderer` (session card, result and console rendering), `PixieDustRunner` (orquestación de fases, reintentos de captura M3 y limpieza/restauración del WiFi), `SupplicantProcesses` (arranque, `wpa_cli`, parada y volcado de la cola del supplicant), `PixiewpsExecutor` (ejecución de `pixiewps` y extracción del PIN), `PixiePskExtractor` (provisión WPS con el PIN para obtener el PSK), `PixieAuditLog` (persistencia del log de auditoría), `PixieProcessSupport` (comandos `su` y drenaje de streams), `PixieNetworkFilter` (filtro y búsqueda puros de la hoja de selección), `PixieDustStage` (etapas y reglas de transición), `M3Parser`, and `NativeBinaries` (binary resolution/asset copying). `PixieDustUiState` agrupa el estado por área (`scan`/`session`) en lugar de campos sueltos. Audit logs go to `/sdcard/AmWF/pixie-logs/` (fallback `filesDir/pixie-logs`) and contain plaintext WPS/WPA credentials. `runAttack` must persist that log on every exit path (early validation, capture failure, success and cancellation) and flush the supplicant queue before cleanup, otherwise the last M3 lines are lost.
- `MainActivity`'s Pixie Dust button stays enabled even when the compatibility check reports missing binaries: the tap shows which binary is missing (and `PixieDustActivity` repeats it from `nativeError`). The dimmed alpha is a "not ready" hint, not a disabled state, so don't pair it with `isEnabled = false`.
- In the Pixie Dust network picker, tapping an auditable network both selects it and starts the audit immediately with the current options (the WiFi-disable switch defaults to on). There is intentionally no separate Run button or confirmation dialog; keep the picker subtitle and KDoc aligned with that instead of adding one without an explicit product decision.
- The audit screen's network search, chip filters, session timer, phase tracker and collapsible console are intentional scope, not leftovers: they exist to pick a single authorized AP and to follow a long-running session. Don't remove them as "unused UI" without an explicit request.
- `WifiScanPermissions` centralizes scan permissions: precise location on every Android version (`startScan()`/`getScanResults()` require it) plus `NEARBY_WIFI_DEVICES` on Android 13+, and checks location services. Keep permission checks routed through it instead of ad-hoc SDK checks.

`resolveConModePath()` in `DeviceCompatibilityChecker.kt` must keep using root: SELinux blocks the app UID from reading `/sys/module` on modern Android. Route new one-shot privileged shell calls through `ShellExecutor`; validate BSSID/interface input and shell-quote user-controlled values like the existing runner.

`minSdk` is 24: API 26+ process APIs (`destroyForcibly`, `Process.isAlive`, timed `waitFor`) go through the `*Compat` helpers in `ProcessCompat.kt`; don't call the raw APIs.

## Native Pixie Dust binaries

`app/src/main/assets/wifi/` contains prebuilt ARM binaries; normal Gradle builds do not compile them.

- Rebuild: `./scripts/build-wifi-binaries.sh <arm64-v8a|armeabi-v7a>`.
- Requires NDK r27+ (`ANDROID_NDK`/`ANDROID_NDK_HOME`) and sibling source trees `../pixiewps-master`, `../wpa_supplicant-2.11`, `../libnl-3.2.25`.
- arm64-v8a: `pixiewps`, `wpa_supplicant`, `wpa_cli`, libnl libs. armeabi-v7a: only `pixiewps_32` is built; `wpa_supplicant_32` is intentionally absent and `wpa_cli_32` is a preserved precompiled asset.
- `NativeBinaries` is the single source of truth for binary paths, missing-binary detection and missing-binary messages (`missingBinariesMessage` para el motivo de fallo, `missingBinariesSummary` para la fila de compatibilidad y el aviso al abrir la auditoría; ambos deciden aquí el caso armeabi-v7a). Keep the compatibility check, `MainActivity` and `PixieDustRunner` using it.
- Never substitute the system `wpa_supplicant`: Android's build uses `CONFIG_ANDROID_LOG`, truncating WPS hexdumps to 32 bytes, so the bundled supplicant is mandatory for Pixie Dust.
- `BINARIES_SETUP.md` is the authoritative workflow for these assets. `README.md` predates the Pixie Dust module and is stale on project structure.

## Conventions

- Default UI language is Spanish (`res/values/strings.xml`); English is `res/values-en/strings.xml`. Add every new user-facing string to both.
- Comments and log messages are predominantly Spanish; match the surrounding file.
- New main-source Kotlin files should carry the existing `SPDX-License-Identifier: GPL-3.0-or-later` header (some tests omit it).
- Use `ShellExecutor.logD/W/E` (not raw `Log`) for diagnostics-relevant logging; the in-app report reads `AppLogger`.
- Project license is GPL-3.0-or-later; bundled native binaries have their own notices in `THIRD-PARTY-NOTICES.md`.
