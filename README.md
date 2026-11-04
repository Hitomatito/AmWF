<div align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="AmWF Logo" width="120">

  # AmWF - Android WiFi Monitor Mode Controller

  [![Release](https://img.shields.io/github/v/release/Hitomatito/AmWF?style=flat-square)](https://github.com/Hitomatito/AmWF/releases)
  [![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=fff)](https://developer.android.com)
  [![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=fff)](https://kotlinlang.org)
  [![UI](https://img.shields.io/badge/UI-Material%203-6750A4?style=flat-square)](https://m3.material.io)
  [![License](https://img.shields.io/github/license/Hitomatito/AmWF?style=flat-square)](LICENSE)

  **Control WiFi monitor mode on rooted Snapdragon devices — now with a built-in WPS Pixie Dust audit.**

  [Features](#features) • [What's new in v2.0](#whats-new-in-v20) • [Requirements](#system-requirements) • [Installation](#installation) • [Usage](#usage) • [How it works](#how-it-works) • [Project structure](#project-structure) • [Tech stack](#tech-stack) • [Limitations](#limitations)
</div>

> [!IMPORTANT]
> AmWF is a security research tool. Use it only on devices and networks you own or are explicitly authorized to audit, and comply with your local laws. See [DISCLAIMER_es.md](DISCLAIMER_es.md).

AmWF enables and disables WiFi monitor mode through Qualcomm's `con_mode` driver parameter on rooted Android devices. Monitor mode is required for wireless network analysis, packet capture, and WPA/WPA2 handshake capture.

## What's new in v2.0

- **WPS Pixie Dust audit built in** — scan for WPS-enabled networks, tap one, and AmWF runs a full Pixie Dust attack with bundled native binaries (`wpa_supplicant`, `wpa_cli`, `pixiewps`). It recovers the WPS PIN, and the WPA PSK when an SSID is set.
- **Manual compatibility check** — the disruptive check (radio switched to monitor mode, WiFi briefly drops) now runs only when you tap **Verificar compatibilidad**, with live progress and per-step status. It never runs automatically at startup.
- **Audit logging** — every audit persists a detailed log with the raw process output for post-mortem analysis. See [BINARIES_SETUP.md](BINARIES_SETUP.md) for how the native binaries are built and verified.

## Features

- **Monitor mode on demand** — enable/disable monitor mode via Qualcomm's `con_mode`, with a compatibility probe you trigger explicitly.
- **WPS Pixie Dust audit** — one-tap audit for WPS-enabled networks: network scan with band/WPS filters, phase tracker, session timer, live console, and PIN/PSK recovery.
- **Native-first binaries** — purpose-built `wpa_supplicant` with complete WPS hexdumps (the system supplicant truncates them), bundled as ARM assets, with automatic fallback to system `wpa_cli`/`pixiewps` when available.
- **Compatibility verification with progress** — chipset, driver, root, and capture/injection probing with a live progress indicator; results cached per process.
- **Redacted diagnostics export** — one-tap share of passive device/WiFi state; MAC addresses and SSIDs are redacted by default.
- **Spanish and English** — full ES/EN localization, switchable in-app.

## Supported devices

<details>
<summary><b>Chipsets</b> (click to expand)</summary>

AmWF identifies Snapdragon chipsets by codename and CPU part number:

| Generation | Chipset | Codename |
|------------|---------|----------|
| Flagship | Snapdragon 8 Gen 3 | `kalama` |
| Flagship | Snapdragon 8 Gen 2 | `cape` |
| Flagship | Snapdragon 8+ Gen 2 | - |
| Flagship | Snapdragon 8 Gen 1 | `taro` |
| Flagship | Snapdragon 8+ Gen 1 | - |
| Flagship | Snapdragon 888 / 888+ | `lahaina`, `haydn` |
| Flagship | Snapdragon 870 | `kona`, `alioth`, `cpu7pro` |
| Flagship | Snapdragon 865 | `kona`, `msmnile` |
| Flagship | Snapdragon 860 | `vayu` |
| Flagship | Snapdragon 855 / 855+ | `msmnile` |
| High-end | Snapdragon 8s Gen 3 | `crown` |
| High-end | Snapdragon 780G | `waipio` |
| High-end | Snapdragon 778G / 778G+ | `waipio` |
| High-end | Snapdragon 768G | `lito` |
| High-end | Snapdragon 750G | `atoll`, `sm7225` |
| Mid-range | Snapdragon 7 Gen 2 | `pineapple` |
| Mid-range | Snapdragon 7 Gen 1 | `pineapple`, `yupik` |
| Mid-range | Snapdragon 730G | `holi` |
| Mid-range | Snapdragon 720G | `corona` |
| Mid-range | Snapdragon 695 | `sm6350` |
| Mid-range | Snapdragon 6 Gen 1/2 | `qsm` |
| Entry | Snapdragon 680 | `bengal` |
| Entry | Snapdragon 665/670/675 | `trinket` |
| Entry | Snapdragon 662 | `bengal` |
| Laptop | Snapdragon 7c Gen 2 | `saipan` |

</details>

### Root methods

| Method | Supported |
|--------|-----------|
| Magisk | Yes |
| KernelSU | Yes |
| APatch | Yes |
| phh-su | Yes |
| SuperSU | Yes |
| Generic su | Yes |

### WiFi drivers

The app searches for `con_mode` with root (`su`), because modern SELinux policies block the app UID from reading `/sys/module`. Out-of-tree driver variants such as `qca_cld3_*` are also matched:

| Module Path | Typical Hardware |
|-------------|------------------|
| `/sys/module/wlan/parameters/con_mode` | Many Snapdragon (e.g. 855, 860, 732G) |
| `/sys/module/hdd/parameters/con_mode` | Snapdragon 888, 8 Gen series |
| `/sys/module/kiwi_v2/parameters/con_mode` | Snapdragon 8 Gen 2+ |
| `/sys/module/qca_cld3_kiwi_v2/parameters/con_mode` | Snapdragon 8 Elite |
| `/sys/module/kiwi/parameters/con_mode` | Snapdragon 6/7 series |
| `/sys/module/wcn/parameters/con_mode` | Qualcomm WCN6xxx |

### System requirements

| Requirement | Minimum |
|-------------|---------|
| Android version | 7.0 (API 24) |
| Root access | Required |
| Architecture | ARM64 (`aarch64`) — 32-bit devices cannot run the bundled supplicant |

Other Snapdragon chipsets may work if they expose `con_mode` through the Qualcomm WiFi driver (QCACLD).

## Installation

### From APK

1. Download the latest version from [Releases](https://github.com/Hitomatito/AmWF/releases).
2. Install the APK on your rooted Android device.
3. Grant root access when prompted.

### Build from source

Requirements: JDK 21 (Gradle daemon), Android SDK 36, and an ARM device for real monitor-mode/Pixie Dust behavior.

```bash
./gradlew :app:assembleDebug
```

Unit tests and lint:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

See [BINARIES_SETUP.md](BINARIES_SETUP.md) for rebuilding the native binaries (requires NDK r27+ and sibling source trees).

## Usage

### Main screen

- **Device Compatibility** — chipset, driver, root, `con_mode` path, and tested capabilities. All checks are passive until you tap the verify button.
- **WiFi Mode** — current mode (Normal / Monitor), interface name, MAC, and frequency.

### Verify compatibility (manual)

Tap **Verificar compatibilidad** in the Device Compatibility card. The probe temporarily switches the radio to monitor mode, tests capture/injection and the `con_mode` write, then restores normal mode. A progress bar and status message show each step (driver detection, root, capture test, restore...). WiFi may briefly disconnect — this is expected. The result is cached for the lifetime of the app process; it never runs automatically.

### Enable / disable monitor mode

- **ACTIVAR MONITOR** — requires a prior verification in the current session (a toast informs you otherwise).
- **VOLVER A NORMAL** — always available, no verification required.

### WPS Pixie Dust audit

1. Tap the **WPS Pixie Dust Audit** button to open the audit screen.
2. Scan networks; auditable networks advertise WPS and are not WPS-locked.
3. Tap an auditable network — the audit starts immediately (disabling WiFi first, by default).
4. AmWF runs the WPS exchange with the bundled `wpa_supplicant`, captures M1→M3, and cracks the Pixie Dust with `pixiewps`. On success you get the WPS PIN and, when an SSID is provided, the WPA PSK.

> [!WARNING]
> Audit logs under `/sdcard/AmWF/pixie-logs/` (fallback: app-private storage) contain the plaintext WPS PIN and WPA PSK in cleartext. They are written with owner-only permissions. Do not share them or commit them anywhere.

> [!NOTE]
> The audit only works against APs with WPS enabled and unlocked. Results depend on the AP's implementation. On 32-bit (`armeabi-v7a`) devices only `pixiewps_32` is bundled, so the full audit cannot run there.

### Diagnostics / bug reports

Tap **Compartir diagnóstico**: AmWF bundles passive device/WiFi state, the redacted in-app log buffer, and the last compatibility result into a shareable `.txt`. No capability test is run, so WiFi is untouched. MACs are reduced to their OUI prefix and SSIDs are replaced with `<redacted>`. Attach it when reporting an issue.

### Language

The language button (ES/EN) switches the app between Spanish and English; the interface recreates and keeps your choice.

## How it works

### Monitor mode activation flow

```
1. User taps "Verificar compatibilidad" (never automatic)
   └─> Detects root, chipset, WiFi driver, con_mode path
   └─> Tests write access to con_mode + capture/injection
   └─> Probes bundled Pixie Dust binaries
   └─> Restores normal WiFi at the end

2. User taps "ACTIVAR MONITOR"
   └─> svc wifi disable → con_mode = 4 → wlan0 down
   └─> iw dev wlan0 set monitor → wlan0 up → svc wifi enable

3. User taps "VOLVER A NORMAL"
   └─> svc wifi disable → con_mode = 0 → wlan0 down
   └─> iw dev wlan0 set type managed → wlan0 up → svc wifi enable
```

### con_mode values

| Value | Mode |
|-------|------|
| 0 | STA (managed / normal) |
| 4 | Monitor mode |

### WPS Pixie Dust flow

```
1. Scan → filter WPS-capable, WPS-unlocked networks
2. svc wifi disable (default) → attach bundled wpa_supplicant
3. WPS IE exchange M1 → M2 → M3 (hexdump-complete capture)
4. pixiewps brute-force of the M3 nonce material
5. On success: WPS PIN recovered; wpa_cli triggers provisioning → PSK from config
6. Audit log persisted to /sdcard/AmWF/pixie-logs/ (mode 600)
7. WiFi restored and framework state confirmed (svc wifi enable, verified + retried)
```

## Project structure

```
app/src/main/java/com/hitomatito/amwf/
├── MainActivity.kt               # Monitor mode / compatibility UI and entry point
├── MonitorModeManager.kt         # Monitor mode enable/disable logic
├── DeviceCompatibilityChecker.kt # Chipset, root, driver detection + binary probe
├── ShellExecutor.kt              # Root shell execution (deadlock-safe)
├── ProcessCompat.kt              # API 26+ process helpers for minSdk 24
├── LocaleHelper.kt               # Language switching (ES/EN)
├── diagnostics/                  # Passive state collection + redaction
│   ├── AppLogger.kt              # In-memory log ring buffer
│   ├── DiagnosticsCollector.kt   # Passive device/WiFi state gathering
│   ├── DiagnosticsExporter.kt    # Writes and shares the report
│   └── DiagnosticsFormat.kt      # MAC/OUI redaction
└── wifi/pixiedust/               # Native WPS Pixie Dust audit flow
    ├── PixieDustActivity.kt      # Audit screen (network list, session, console)
    ├── PixieDustViewModel.kt     # Audit state, scanning, cancellation
    ├── PixieDustRunner.kt        # Attack orchestration, retries, WiFi restore
    ├── SupplicantProcesses.kt    # Bundled wpa_supplicant/wpa_cli lifecycle
    ├── PixiewpsExecutor.kt       # pixiewps execution
    ├── PixiePskExtractor.kt      # WPA PSK extraction via WPS provisioning
    ├── M3Parser.kt               # WPS M3 hexdump parsing + M5/M7 dwell
    ├── NativeBinaries.kt         # Asset copy, missing-binary detection, messages
    ├── PixieAuditLog.kt          # Audit log persistence (credentials, mode 600)
    └── WifiScanPermissions.kt    # FINE location + NEARBY_WIFI_DEVICES (33+)
```

Unit tests live under `app/src/test/` (plain JUnit 4 over the parsing, filtering, permission, and transition logic); the ARM-only native binaries are not exercised by unit tests. See [AGENTS.md](AGENTS.md) for architecture details and the build/verify commands.

## Tech stack

| Technology | Purpose |
|------------|---------|
| Kotlin | Primary language |
| Material Design 3 | UI components |
| Coroutines | Async shell commands |
| View Binding | Type-safe view access |
| Shell (`su`) | Privileged `con_mode` / `iw` / `svc` operations |

## Limitations

- **Qualcomm only** — does not work on MediaTek, Exynos, or other chipsets.
- **Root required** — WiFi driver parameters cannot be modified without root.
- **Driver dependent** — newer Qualcomm drivers (cnss_pci/WCN) may not expose `con_mode`.
- **SELinux** — strict policies may block access to `con_mode`.
- **Injection not guaranteed** — most devices support passive capture only; injection depends on driver and chipset.
- **WPS required for Pixie Dust** — the audit only works against APs with WPS enabled and not locked; results depend on the AP's implementation.
- **32-bit devices** — `armeabi-v7a` ships `pixiewps_32` only; there is no bundled supplicant, so the full audit cannot run there.
- **Audit logs contain credentials** — `/sdcard/AmWF/pixie-logs/*.log` holds plaintext WPS PINs and WPA PSKs (owner-only permissions); treat and delete them accordingly.

---

<div align="center">
  <p>AmWF &copy; 2026 Hitomatito &mdash; released under the GPL-3.0 license (see <a href="LICENSE">LICENSE</a>)</p>
</div>
