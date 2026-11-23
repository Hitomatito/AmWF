# Contributing to AmWF

Thank you for your interest in contributing to **AmWF**! We welcome code contributions, documentation improvements, bug reports, and feature proposals.

AmWF is an open-source wireless security research tool licensed under the [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE).

---

## 1. Ethical & Responsible Research

AmWF is intended strictly for authorized wireless security auditing, educational purposes, and hardware development on devices and networks you own or have explicit permission to test.
- Read our [Legal Notice & Disclaimer](DISCLAIMER_en.md).
- Read our [Security Policy](SECURITY.md) before reporting potential vulnerabilities.

---

## 2. Development Setup & Verification

Before submitting code, ensure your changes build cleanly and pass all unit tests and lint checks.

### 2.1 Build Requirements
- **JDK:** Java 21 (foojay daemon resolver).
- **Android SDK:** compileSdk 37.1 / targetSdk 37, minSdk 24.
- **Gradle:** Run tasks using `./gradlew` from the repository root.

See [docs/BUILDING.md](docs/BUILDING.md) for full setup instructions.

### 2.2 Local Verification Commands
All Gradle tasks are prefixed with `:app`:

```bash
# 1. Run JVM Unit Tests
./gradlew :app:testDebugUnitTest

# 2. Run Android Lint
./gradlew :app:lintDebug

# 3. Assemble Debug APK
./gradlew :app:assembleDebug

# Combined one-liner before submitting PR:
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

---

## 3. Core Architectural Invariants

When submitting pull requests, you must uphold the following core architectural rules:

### 3.1 Pure Reducers & Zero-Context Business Logic
- **No Android `Context` in Business Logic:** `PixieDustRunner`, `M3Parser`, `PixieProcessSupport`, and reducers operate strictly on pure inputs, streams, or gateway interfaces (`PixieDustGateways.kt`).
- UI state decisions belong in `PixieDustReducers.kt` and `PixieNetworkFilter.kt` as pure, deterministic functions accompanied by comprehensive JUnit 4 unit tests under `app/src/test/java/`.

### 3.2 Dynamic Runtime Localization (i18n)
- **Spanish is the default UI language** (`app/src/main/res/values/strings.xml`).
- **English is the secondary language** (`app/src/main/res/values-en/strings.xml`).
- **Every user-facing string must be added to BOTH files.**
- Visible strings must resolve through the wrapped locale context (`LocaleHelper.applyLocale()`); raw `applicationContext` keeps the system locale and will silently ignore user language toggles.

### 3.3 Security, Privacy & Logging Hygiene
- **Zero Internet Access:** Never declare `android.permission.INTERNET`.
- **Sensitive Credential Isolation:** Use `ShellExecutor.logD/W/E` for operational logs destined for the exportable `AppLogger`. However, **never route WPS PINs, WPA PSKs, or raw M3 key material to `AppLogger`**. Sensitive audit logs must use raw `Log.d` (`PixieDustRunner.log`).
- **Diagnostics Redaction:** Preserve automatic redaction of MAC addresses (to OUI) and network SSIDs in `DiagnosticsFormat.kt`.

### 3.4 Android & Process Compatibility (minSdk 24)
- AmWF supports Android 7.0 (API 24). API 26+ process methods (`Process.isAlive()`, `Process.destroyForcibly()`, `Process.waitFor(timeout, unit)`) must go through `ProcessCompat.kt`.
- Cleanup operations must only terminate (`pkill`) binaries starting with the app's bundled binary directory; never terminate the system supplicant. After `svc wifi enable`, cleanup must confirm the radio with dual sources (`WifiRestore.combinedEnabled`: `dumpsys` plus `settings get global wifi_on`, with a settle re-read — MIUI can report "enabled" for an instant and then revoke it) before clearing the restore marker.

### 3.5 Native Binary Integrity
- Never substitute Android's system `wpa_supplicant`. Its `CONFIG_ANDROID_LOG` truncates M3 Diffie-Hellman keys to 32 bytes, which renders Pixie Dust calculation impossible.
- `NativeBinaries` is the single source of truth for binary paths and status checks. Always invoke `NativeBinaries.ensure(context)` before checking for missing binaries.

### 3.6 Compatibility Check & Radio Restore
- `DeviceCompatibilityChecker` disables WiFi before the `con_mode` tests and restores the radio once in `finally`. Perform exactly one `con_mode` test write in `testCapabilities` (plus the restore of the original value); never add a separate write test — its extra driver transition caused the crash in issue #5.
- `PendingCompatRestore` (`CompatCheckRestore.kt`) stores the marker with synchronous commits, records `radio_touched` before `svc wifi disable`, and saves the original `con_mode`. Validate the stored path/value with `isValidConModeRestoreTarget` before interpolating them into root commands.
- On the next launch `MainActivity` applies a pending restore even with a cached compatibility result (only skipping while a check is in flight), confirms the WiFi state with `dumpsys` and retries, and keeps the marker on failure. `restoreIfPending` returns success only when the restore actually succeeded. Both the check's write and the pending restore go through `writeConMode`, which parses `echo $?` instead of trusting `su -c`'s exit code.
- An unverified check keeps MANAGED visually locked (`toggleEnabledForState`); MONITOR (disable) and UNKNOWN (retry) stay actionable.
- `ShellExecutor` reader threads must swallow `IOException` (killing a timed-out process closes the pipes from another thread). `con_mode` writes use `CON_MODE_WRITE_TIMEOUT_MS` (30 s) because the driver reload can block ~10 s; reader accumulators are synchronized because a descendant may keep the pipe open after `exec` returns.
- `pendingRestoreInProgress` blocks the verify button and the mode toggle while a pending restore is applied. On Activity recreate, a rethrown `CancellationException` preserves the cached result, while any other exception clears it and resets verification. `MonitorModeManager`'s normal enable/disable writes are raw `echo 4/0 > $conModePath` via `execRoot` and do **not** use `writeConMode`'s `echo $?` safeguard.

### 3.7 Pixie Dust Capture & Analysis
- `M3Parser` treats a `config_error=18` NACK after all six M3 fields as a complete capture, and validates exact hex lengths (32/384/64 chars) to reject Android's truncated 32-byte dumps.
- Watchdogs in `PixieProcessSupport` check `/proc/<pid>/stat` starttime (field 22) before killing, so a reused PID cannot kill an unrelated root process. Any new watchdog must preserve this.
- Keep `PixieDustRunner` free of `Context`: supplicant, pixiewps, PSK extraction, binaries, audit log, shell and WiFi state come from the gateways in `PixieDustGateways.kt`. `cleanup()` is `@Synchronized` and `cleanupDone`-guarded because `cancel()` and `finally` can both call it.
- `runAttack` must persist the audit log on every exit path (validation, missing binaries, capture failure, success, cancellation) and flush the supplicant queue before cleanup, or the final M3 lines are lost. The log's «Resumen» (per-phase durations, rounds, scan mode, pixiewps mode) is derived from timing milestones stamped through the `AuditLogGateway` and rendered by the pure `buildAuditSummaryLines`; new milestones must flow through that same path. The attack is marked in-flight right before its main `try` (`markAttackStarted`, cleared when the audit log is persisted on any exit) so `restoreIfPending` never enables WiFi over a live attack after a recreate, and orphan cleanups register in `PixieCleanupGate`, which `startAttack` waits for before launching a new audit. The attack's `svc wifi disable` is covered by the `PixieAttackRestore` marker (same pattern as `PendingCompatRestore`): marked **before** the disable, cleared by `cleanup()` only when the framework confirms the radio is back on; both `MainActivity.onCreate` and `PixieDustActivity.onCreate` apply a pending restore on the next launch.
- After a successful M3 capture, close the WPS session (`wps_cancel`) before killing the attack supplicant. `PixiePskExtractor` likewise cancels when provisioning ends without a PSK, and best-effort on the user-cancellation path.
- Hopeless APs abort early instead of burning rounds: association rejects go through the attack-wide `M3Parser.AssocRejectTracker` budget; silent M3 windows end as `M3Capture.NoResponse` with a localized reason split by `M3Parser.bssidVisibleInSupplicantOutput` (visible-but-WPS-stuck vs true out-of-range); `wps_reg` retries use progressive backoff.
- The runtime supplicant config stages a global `freq_list=<target freq>` to keep every `wps_reg` scan on the target's channel. An unknown or out-of-range frequency omits the line (full-band fallback); a restricted round that never sees the target BSSID spends a single full-band retry (`shouldFallbackToFullBandScan`) before declaring out-of-range. Keep the graceful path in `buildRuntimeConfigContent`.
- Pixie Dust UX is intentional: tapping an auditable network starts the audit immediately (no Run button, no confirmation dialog). The phase strip was removed entirely — `PixieDustStage` carries only `progress` and the stage title + percentage carry all the state; do not reintroduce it.
- `PixieDustViewModel.availableWifiInterfaces()` is non-root and best-effort by design. When it finds nothing, `startAttack` resolves the real interface with root (`ls /sys/class/net`, parsed by the pure `interfacesFromSysfsListing`) before launching — keep that resolution at attack start, not screen open.
- Mode selection is a pure function (`PixieModeSelector`): force `--mode` only from captured M5/M7, never from the BSSID OUI — an OUI identifies the router vendor, not the chipset. `PixieDiagnosisParser` converts pixiewps output into an action; a `MIGHT_BE_VULNERABLE` diagnosis drives a retry with fresh data, and `PixiewpsPlan.timeoutSeconds` travels with the command so the watchdog and the deadline cannot diverge. Do not add `--force`: it scans the whole epoch and takes around 30 minutes on real hardware; retry with fresh data instead. The mode-3 path passes `--r-nonce` from the captured `Registrar Nonce` hexdump (`M3Data.registrarNonce`, exact-length-guarded, never counted toward the six M3 fields); validation against a real RTL819x AP is still pending.

### 3.8 Monitor Channel Tuning
- QCACLD monitors come up **detuned**: `con_mode=4` + `type monitor` leave the PHY on no frequency, so the radio receives 0 frames even surrounded by networks (the historical false "Captura no verificada"). `enableMonitorMode` and `testRealCapture` must `iw dev <iface> set channel N`; the channel policy (`MonitorChannelPolicy`) is pure and unit-tested.
- Tune verification is the `iw set channel` exit code, not `iw dev info`: many drivers do not echo the channel line while in monitor, so tcpdump/rx counters are the final judge (`isTuneAccepted` accepts a silent report, rejects a mismatch).
- `testRealCapture` never runs `svc wifi enable`: `ip link up` powers the radio with the framework off, and enabling it mid-test lets the system supplicant claim and reversion the interface. The rotation starts at the persisted channel and absorbs transient `-1`/`EBUSY` from the driver reload after the `con_mode` write.
- The user's channel is persisted via `MonitorChannelStore` (default 6, synchronous commits) and exposed through the monitor chip selector (`CHANNEL_CHIP_IDS` map, visible only in MONITOR). DFS channels are deliberately excluded: the driver performs no radar detection.

### 3.9 Native Binary Invariants
- Rebuild with `./scripts/build-wifi-binaries.sh <abi>`. All `arm64-v8a` ELF `LOAD` segments must be **16 KB-aligned** (`-Wl,-z,max-page-size=16384`, enforced and verified by the script and re-verified by CI as a blocking step): Android 15+ on 16 KB-page devices won't load 4 KB-aligned binaries, and Play has rejected targetSdk 35+ updates without it since 2025-11-01. `armeabi-v7a` is exempt.
- arm64-v8a builds `pixiewps`, `wpa_supplicant`, `wpa_cli` and libnl. armeabi-v7a builds only `pixiewps_32`; `wpa_supplicant_32` is intentionally absent and `wpa_cli_32` is a preserved prebuilt asset.
- `wpa_cli` and `pixiewps` may fall back to system paths (`/system/bin`, `/vendor/bin`, `/vendor/bin/hw`, `/system/xbin`, `/odm/bin`) when the bundled asset is missing; `wpa_supplicant` must never fall back.
- The supplicant control dir comes from `NativeBinaries.ctrlDir()` (`/data/vendor/wifi/amwf` on API 28+, `/data/misc/wifi/amwf` below). The runtime config must be staged through `su` stdin (`execRootWithInput`) with `update_config=1`, so `save_config` can persist the PSK after WPS provisioning.

---

## 4. Git & Commit Guidelines

### 4.1 Commit Message Convention
Commits follow the [Conventional Commits](https://www.conventionalcommits.org/) specification in English:

```text
<type>(<scope>): <summary in imperative mood>

[optional body explaining context and why]
```

**Allowed Types:**
- `feat`: New feature or user-facing enhancement.
- `fix`: Bug fix.
- `refactor`: Code change that neither fixes a bug nor adds a feature.
- `docs`: Documentation updates.
- `chore`: Maintenance tasks, dependency bumps, release commits.
- `build`: Changes to Gradle build scripts, AGP, or NDK recipes.

*Example:*
```text
feat(pixiedust): add progressive backoff for wps_reg retries
fix(compat): resolve con_mode path using root shell to bypass SELinux
docs(readme): add troubleshooting section for socket directory
```

### 4.2 SPDX License Headers
Every new main Kotlin source file (`app/src/main/java/**/*.kt`) must begin with the project's SPDX identifier:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
```

### 4.3 Git Hygiene
Never stage or commit:
- `local.properties` (contains local SDK paths)
- `keystore.properties` (contains release signing credentials)
- `*.logcat` or raw test outputs (may contain sensitive device details)
