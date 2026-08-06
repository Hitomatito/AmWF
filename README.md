# AmWF - Android WiFi Monitor Mode Controller

<div align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=flat-square&logo=kotlin" alt="Language">
  <img src="https://img.shields.io/badge/UI-Material%20Design%203-red?style=flat-square&logo=materialdesign" alt="UI">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License">
</div>

> Control the WiFi monitor mode on rooted Android devices with Qualcomm Snapdragon chipsets.

## What is AmWF

AmWF is an Android application that enables and disables WiFi monitor mode using Qualcomm's `con_mode` driver parameter. Monitor mode is required for wireless network analysis, packet capture, and WPA/WPA2 handshake capture.

The app automatically detects your device's chipset, root method, WiFi driver, and tests injection/capture capabilities before enabling monitor mode.

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

Other Snapdragon chipsets may work if they expose `con_mode` through the Qualcomm WiFi driver (QCACLD). The app checks for `con_mode` at runtime and reports compatibility automatically.

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

The app searches for `con_mode` across multiple Qualcomm driver modules:

| Module Path | Typical Hardware |
|-------------|------------------|
| `/sys/module/wlan/parameters/con_mode` | Older Snapdragon (855, 865, 860) |
| `/sys/module/hdd/parameters/con_mode` | Snapdragon 888, 8 Gen series |
| `/sys/module/kiwi_v2/parameters/con_mode` | Snapdragon 8 Gen 2+ |
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

Results vary by device and driver. The app reports these capabilities in the compatibility section at startup.

## Installation

### From APK

1. Download the latest release from [Releases](https://github.com/Hitomatito/AmWF/releases)
2. Install the APK on your rooted Android device
3. Grant root access when prompted

### Build from Source

```bash
git clone https://github.com/Hitomatito/AmWF.git
cd AmWF
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android Studio 2024+, JDK 17+, Gradle 8+.

## Usage

### Main Screen

The app has two sections:

**Device Compatibility** - Shows your device's chipset, WiFi driver, root status, con_mode path, and tested capabilities.

**WiFi Mode** - Shows the current mode (Normal / Monitor) with interface details (name, MAC address, frequency).

### Controls

- **ACTIVAR MONITOR** - Switches the WiFi interface to monitor mode
- **VOLVER A NORMAL** - Returns to managed mode
- **Language button (ES/EN)** - Toggles between Spanish and English

### First Launch

On first launch, the app automatically runs a compatibility check. This involves temporarily switching to monitor mode to test capabilities, then restoring normal mode. WiFi may briefly disconnect during this process -- this is expected behavior.

## Project Structure

```
app/src/main/java/com/hitomatito/amwf/
├── MainActivity.kt              # UI and activity lifecycle
├── MonitorModeManager.kt        # Monitor mode enable/disable logic
├── DeviceCompatibilityChecker.kt # Chipset, root, driver detection
├── ShellExecutor.kt             # Root shell execution
└── LocaleHelper.kt              # Language switching
```

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

## License

MIT License. See [LICENSE](LICENSE).

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
  <p>AmWF &copy; 2026</p>
</div>
