# AmWF Hardware & Software Compatibility

This document provides a comprehensive compatibility matrix and technical explanation regarding supported system-on-chips (SoCs), Android OS versions, ABIs, root solutions, and SELinux environments.

---

## 1. System-on-Chip (SoC) Matrix

AmWF interacts directly with low-level kernel drivers to toggle raw RFMON monitor mode and run native audit tooling. As such, hardware compatibility is strictly tied to the device's wireless chipset and Linux kernel driver.

| Chipset Family | Manufacturer | Monitor Mode | Pixie Dust Audit | Status | Notes |
|---|---|:---:|:---:|:---:|---|
| **Snapdragon 8 Gen 1 / 2 / 3 / 4** | Qualcomm | ✅ | ✅ | **Supported** | `qcacld-3.0` driver; full arm64-v8a support. |
| **Snapdragon 855 / 865 / 870 / 888** | Qualcomm | ✅ | ✅ | **Supported** | Standard `qcacld-3.0` kernel module. |
| **Snapdragon 700 Series (730G, 732G, 778G, 7+ Gen 2)** | Qualcomm | ✅ | ✅ | **Supported** | Tested reference hardware (e.g., Poco X3 NFC). |
| **Snapdragon 600 Series (665, 680, 695, 6 Gen 1)** | Qualcomm | ✅ | ✅ | **Supported** | `qcacld-3.0` / `pronto_wlan`. |
| **Snapdragon 400 Series** | Qualcomm | ⚠️ | ⚠️ | **Partial / Device-dependent** | Supported if the kernel exposes `con_mode`. |
| **MediaTek (Dimensity / Helio)** | MediaTek | ❌ | ❌ | **Unsupported** | MediaTek Wi-Fi drivers do not implement Qualcomm's `con_mode`. |
| **Samsung Exynos** | Samsung | ❌ | ❌ | **Unsupported** | Uses Shannon / Broadcom / Marvell Wi-Fi stacks without `con_mode`. |
| **Google Tensor (G1 / G2 / G3 / G4)** | Google | ❌ | ❌ | **Unsupported** | Employs Broadcom / Samsung wireless chipsets without `con_mode`. |
| **HiSilicon Kirin** | Huawei | ❌ | ❌ | **Unsupported** | Proprietary HiSilicon wireless stack without `con_mode`. |

### Why Are Non-Qualcomm Chipsets Unsupported?
Standard Linux uses the generic `nl80211` / `mac80211` subsystem to manage monitor mode virtual interfaces (`iw dev wlan0 interface add mon0 type monitor`).
However, on mobile Android devices, mobile Wi-Fi drivers are built as full-MAC "softMAC" offload firmware (`qcacld`, `bcmdhd`, `wmt_wifi`). 

Qualcomm provides a proprietary operational switch via sysfs called `con_mode`:
- Writing `4` into `con_mode` commands the physical Qualcomm baseband firmware to enter promiscuous packet capture mode without destroying the underlying `wlan0` netdev.
- MediaTek, Exynos, and Tensor devices do not ship with the Qualcomm firmware or driver, meaning this sysfs hook does not exist on those platforms.

---

## 2. Supported ABIs (CPU Architectures)

AmWF bundles native ARM ELF binaries compiled with the Android NDK r27:

| Architecture | ABI Filter | Binaries Bundled | Support Level |
|---|---|---|---|
| **ARM 64-bit** | `arm64-v8a` | `wpa_supplicant`, `wpa_cli`, `pixiewps`, `libnl-3.so.200`, `libnl-genl-3.so.200` | **Full Support** (Standard modern Android devices) |
| **ARM 32-bit** | `armeabi-v7a` | `pixiewps_32`, `wpa_cli_32` (prebuilt) | **Legacy / Partial** (`wpa_supplicant_32` is not shipped) |
| **x86 / x86_64** | `x86`, `x86_64` | *None* | **Unsupported** (Emulators and ChromeOS devices lack cellular/Qualcomm Wi-Fi hardware) |

> [!NOTE]
> On 32-bit ARM devices, `NativeBinaries.missingCriticalBinaries()` will flag that `wpa_supplicant_32` is absent, preventing invalid or truncated captures.

---

## 3. Android Operating System Versions

| Android Version | API Level | Status | Architectural Differences |
|---|---|:---:|---|
| **Android 7.0 – 7.1** (Nougat) | 24 – 25 | ✅ Supported | `minSdk 24`. Uses legacy socket directory `/data/misc/wifi/amwf`. Process control handled by `ProcessCompat`. |
| **Android 8.0 – 8.1** (Oreo) | 26 – 27 | ✅ Supported | Uses `/data/misc/wifi/amwf`. Native API 26 `Process` methods available. |
| **Android 9.0 – 12L** (Pie, 10, 11, 12) | 28 – 32 | ✅ Supported | Uses vendor socket directory `/data/vendor/wifi/amwf`. |
| **Android 13 – 15+** | 33 – 35+ | ✅ Supported | `targetSdk 37`. Requires runtime `NEARBY_WIFI_DEVICES` permission in addition to precise location. |

### Supplicant Control Directory Selection
Android Treble (introduced in Android 9 / API 28) segregated system and vendor directories. AmWF handles this automatically via `NativeBinaries.ctrlDir()`:
- **API ≥ 28:** `/data/vendor/wifi/amwf`
- **API < 28:** `/data/misc/wifi/amwf`

*Note on Upgraded Devices:* Devices originally launched with Android 8.0 and later upgraded to Android 9+ via custom ROMs occasionally retain the `/data/misc/wifi` directory structure. If the supplicant reports a `socket not created` error, check [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

## 4. Root Solutions & SELinux Compatibility

AmWF has been verified on the following root management solutions:
- **Magisk:** v20.4 through v28.x (Zygisk and standard modes supported).
- **KernelSU:** v0.6.0+ (GKI and non-GKI kernels).
- **APatch:** v10.5+.

### SELinux: Enforcing vs. Permissive
AmWF is engineered to run seamlessly under **SELinux Enforcing** mode without requiring you to compromise your device's security by switching to Permissive:
1. **Config Staging via Stdin:** In Enforcing mode, root commands (`su`) cannot read files directly from the app's sandboxed private storage (`/data/data/com.hitomatito.amwf/`). AmWF pipes configuration contents directly through standard input (`execRootWithInput`), writing the files directly into the allowed Wi-Fi vendor domain directory.
2. **Process Signals:** Process lifecycle signals (`SIGTERM`, `SIGKILL`) are executed directly within the privileged root context.
