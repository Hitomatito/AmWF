<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" alt="AmWF Logo" width="120">
</div>

# AmWF - Android WiFi Monitor Mode Controller

<div align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=flat-square&logo=kotlin" alt="Language">
  <img src="https://img.shields.io/badge/UI-Material%20Design%203-red?style=flat-square&logo=materialdesign" alt="UI">
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=flat-square" alt="License">
</div>

> Control the WiFi monitor mode on rooted Android devices with Qualcomm Snapdragon chipsets, and audit WPS-enabled networks with the bundled Pixie Dust flow.

## What is AmWF

AmWF is an Android application that enables and disables WiFi monitor mode using Qualcomm's `con_mode` driver parameter. Monitor mode is required for wireless network analysis, packet capture, and WPA/WPA2 handshake capture.

The app can probe your device's chipset, root method, WiFi driver, and injection/capture capabilities on demand before enabling monitor mode. The probe is never run automatically at startup; you trigger it with the «Verificar compatibilidad» button and WiFi may briefly disconnect during it.

AmWF also bundles a native **WPS Pixie Dust** audit flow for WPS-enabled networks. It uses a purpose-built `wpa_supplicant` (with complete WPS hexdumps), `wpa_cli` and `pixiewps` shipped as assets, drives the enrollee exchange on a spare/second interface or by temporarily disabling WiFi, and recovers the WPS PIN (and the WPA PSK when an SSID is provided). See [BINARIES_SETUP.md](BINARIES_SETUP.md) for how the bundled binaries are built and verified.

## Supported Devices

### Chipsets

AmWF identifies Snapdragon chipsets by codename and CPU part number. The following are explicitly recognized:

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

Other Snapdragon chipsets may work if they expose `con_mode` through the Qualcomm WiFi driver (QCACLD). The app checks for `con_mode` at runtime and reports compatibility when you request the check.

### Root Methods

| Method | Supported |
|--------|-----------|
| Magisk | Yes |
| KernelSU | Yes |
| APatch | Yes |
| phh-su | Yes |
| SuperSU | Yes |
| Generic su | Yes |

### WiFi Drivers

The app searches for `con_mode` across multiple Qualcomm driver modules. The search runs with root (`su`), because modern SELinux policies block the app UID from reading `/sys/module`; `su` runs in the `su` domain and can read sysfs. Out-of-tree driver variants such as `qca_cld3_*` are also matched:

| Module Path | Typical Hardware |
|-------------|------------------|
| `/sys/module/wlan/parameters/con_mode` | Many Snapdragon (e.g. 855, 860, 732G) |
| `/sys/module/hdd/parameters/con_mode` | Snapdragon 888, 8 Gen series |
| `/sys/module/kiwi_v2/parameters/con_mode` | Snapdragon 8 Gen 2+ |
| `/sys/module/qca_cld3_kiwi_v2/parameters/con_mode` | Snapdragon 8 Elite (e.g. Galaxy Z Fold7) |
| `/sys/module/kiwi/parameters/con_mode` | Snapdragon 6/7 series |
| `/sys/module/wcn/parameters/con_mode` | Qualcomm WCN6xxx |

### System Requirements

| Requirement | Minimum |
|-------------|---------|
| Android version | 7.0 (API 24) |
| Root access | Required |
| Architecture | ARM64 (aarch64) |

## How It Works

### Activation Flow

```
1. App launches
   └─> Detects root, chipset, WiFi driver, con_mode path
   └─> Tests write access to con_mode
   └─> Tests injection and capture capabilities

2. User taps "ACTIVAR MONITOR"
   └─> Disables WiFi service (svc wifi disable)
   └─> Writes con_mode = 4 (monitor mode)
   └─> Brings wlan0 DOWN
   └─> Sets interface to monitor mode (iw dev wlan0 set monitor)
   └─> Brings wlan0 UP
   └─> Re-enables WiFi service (svc wifi enable)

3. User taps "VOLVER A NORMAL"
   └─> Disables WiFi service
   └─> Writes con_mode = 0 (managed mode)
   └─> Brings wlan0 DOWN
   └─> Sets interface to managed mode (iw dev wlan0 set type managed)
   └─> Brings wlan0 UP
   └─> Re-enables WiFi service
```

### con_mode Values

| Value | Mode | Description |
|-------|------|-------------|
| 0 | STA | Managed / Normal mode |
| 4 | Monitor | Monitor mode (used by AmWF) |

### Capability Detection

The app tests your device's actual capabilities before enabling monitor mode:

| Capability | What It Means |
|------------|---------------|
| Injection | Device can transmit custom packets |
| Capture | Device can capture all WiFi traffic |
| Passive only | Device can only listen to traffic passively |

Results vary by device and driver. The app reports these capabilities in the compatibility section after you run the check.

## Installation

### From APK

1. Download the latest release from [Releases](https://github.com/Hitomatito/AmWF/releases)
2. Install the APK on your rooted Android device
3. Grant root access when prompted

## Usage

### Main Screen

The app has two sections:

**Device Compatibility** - Shows your device's chipset, WiFi driver, root status, con_mode path, and tested capabilities.

**WiFi Mode** - Shows the current mode (Normal / Monitor) with interface details (name, MAC address, frequency).

### Controls

- **ACTIVAR MONITOR** - Switches the WiFi interface to monitor mode
- **VOLVER A NORMAL** - Returns to managed mode
- **WPS Pixie Dust Audit** - For WPS-enabled networks, tap an auditable network to start the audit (WiFi-disable defaults on). Results are shown on screen and an audit log is kept under `/sdcard/AmWF/pixie-logs/` (fallback: app-private storage). **These logs contain plaintext WPS/WPA credentials and are written with owner-only permissions -- do not share them casually.**
- **Language button (ES/EN)** - Toggles between Spanish and English
- **Compartir diagnóstico / Share diagnostics** - Generates a report and opens the share sheet

### Diagnostics / Bug Reports

When something doesn't work, tap **Compartir diagnóstico**: AmWF gathers passive device and WiFi state (build properties, root/SELinux, chipset, `con_mode` path and value, `iw` info, driver modules, the last 80 wifi-related `dmesg` lines, available tools, the last compatibility result and an in-app log buffer) and lets you share it as a `.txt` file. It does **not** run the capability test, so WiFi is not interrupted.

MAC addresses are redacted by default (only the OUI prefix is kept) and WiFi SSIDs are replaced with `<redacted>`. Attach this file when opening an issue -- it is the fastest way to diagnose problems on hardware the maintainers don't own.

### Compatibility Check (manual)

The compatibility check is never run automatically when the app opens (it would disrupt your WiFi). Tap **Verificar compatibilidad / Verify compatibility** in the Device Compatibility card to run it. The check temporarily switches the radio to monitor mode to test capabilities, then restores normal mode; WiFi may briefly disconnect during it -- this is expected behavior. Within the same app process the result is cached and reused across recreations (e.g. language switch), so it does not run again until you tap the button.

## Project Structure

```
app/src/main/java/com/hitomatito/amwf/
├── MainActivity.kt              # Monitor mode / compatibility UI and entry point
├── MonitorModeManager.kt        # Monitor mode enable/disable logic
├── DeviceCompatibilityChecker.kt # Chipset, root, driver detection + Pixie Dust binary check
├── ShellExecutor.kt             # Root shell execution
├── ProcessCompat.kt             # API 26+ process helpers for minSdk 24
├── LocaleHelper.kt              # Language switching
├── diagnostics/                 # Passive state collection + MAC/SSID redaction
│   ├── AppLogger.kt             # In-memory log ring buffer
│   ├── DiagnosticsCollector.kt  # Passive device/WiFi state gathering
│   ├── DiagnosticsExporter.kt   # Writes and shares the report
│   └── DiagnosticsFormat.kt     # MAC redaction / formatting
└── wifi/pixiedust/              # Native WPS Pixie Dust audit flow
    ├── PixieDustActivity.kt     # Audit screen (network list, session, console)
    ├── PixieDustViewModel.kt    # Audit state, scanning, cancellation
    ├── PixieDustRunner.kt       # Attack orchestration, retries, WiFi restore
    ├── SupplicantProcesses.kt   # Bundled wpa_supplicant/wpa_cli lifecycle
    ├── PixiewpsExecutor.kt      # pixiewps execution
    ├── PixiePskExtractor.kt     # WPA PSK extraction via WPS provisioning
    ├── M3Parser.kt              # WPS M3 hexdump parsing (formats 1/2a/2b) + M5/M7
    ├── NativeBinaries.kt        # Asset copy, missing-binary detection, messages
    ├── PixieAuditLog.kt         # Audit log persistence (credentials, mode 600)
    └── WifiScanPermissions.kt   # WiFi scan permission routing (FINE + NEARBY @33+)
```

Unit tests live under `app/src/test/` (plain JUnit 4 over the parsing,
filtering, permission and transition logic); the ARM-only native binaries are
not exercised by unit tests. See [AGENTS.md](AGENTS.md) for the detailed
architecture and build/verify commands.

## Tech Stack

| Technology | Purpose |
|------------|---------|
| Kotlin | Primary language |
| Material Design 3 | UI components |
| Coroutines | Async shell commands |
| View Binding | Type-safe view access |

## Limitations

- **Qualcomm only** -- Does not work on MediaTek, Exynos, or other chipsets
- **Root required** -- Cannot modify WiFi driver parameters without root
- **Driver dependent** -- Newer Qualcomm drivers (cnss_pci/WCN) may not expose `con_mode`
- **SELinux** -- Strict SELinux policies may block access to `con_mode`
- **Injection not guaranteed** -- Most devices support passive capture only; packet injection depends on the specific driver and chipset
- **WPS required for Pixie Dust** -- The audit only works against APs with WPS enabled and not locked; results depend on the AP's chipset/implementation
- **32-bit devices** -- armeabi-v7a builds ship `pixiewps_32` only; the bundled `wpa_supplicant` is arm64-v8a only, so 32-bit devices cannot run the full audit
- **Audit logs contain credentials** -- `/sdcard/AmWF/pixie-logs/*.log` holds plaintext WPS PINs and WPA PSKs (written owner-only); treat and delete them accordingly

## License

AmWF is licensed under the **GNU General Public License v3.0 (or later)**.
See [LICENSE](LICENSE) for the full text.

Third-party components bundled or planned for the app are governed by their own
licenses -- see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

## Support

- Bug reports: [Issues](https://github.com/Hitomatito/AmWF/issues)
- Feature requests: [Discussions](https://github.com/Hitomatito/AmWF/discussions)

---

<div align="center">
  <p>AmWF &copy; 2026 Hitomatito &mdash; released under the <a href="LICENSE">GPL-3.0</a> license</p>
</div>
