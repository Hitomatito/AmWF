# Third-Party Notices

This file lists third-party software bundled with or planned for AmWF, along with
their licenses and copyright holders. This project's own code is licensed under
the [GNU General Public License v3.0 or later](LICENSE).

For redistribution obligations of each component, refer to the license full text
listed under each entry.

## Runtime dependencies (already bundled)

| Component | License | Copyright |
|-----------|---------|-----------|
| AndroidX (Core, AppCompat, View Binding) | Apache-2.0 | The Android Open Source Project |
| Material Components for Android | Apache-2.0 | The Android Open Source Project |
| Kotlin standard library & coroutines | Apache-2.0 | JetBrains s.r.o. |

These libraries are distributed as required by the Apache License 2.0. The full
license text for Apache-2.0 is available at <https://www.apache.org/licenses/LICENSE-2.0>.

## WiFi security toolchain (bundled)

The Pixie Dust module bundles the following prebuilt components. Licensing was
verified before adoption so the project license (GPL-3.0) remains compatible
with every component:

| Component | License | Copyright | Upstream |
|-----------|---------|-----------|----------|
| pixiewps | GPL-3.0-or-later | (c) 2015-2017 wiire <wi7ire@gmail.com> | <https://github.com/wiire-a/pixiewps> |
| wpa_supplicant (arm64-v8a) | BSD-3-Clause OR GPL-2.0-only | (c) 2002-2024 Jouni Malinen <j@w1.fi> and contributors | <https://w1.fi/wpa_supplicant/> |
| wpa_cli (arm64-v8a + armeabi-v7a) | BSD-3-Clause OR GPL-2.0-only | (c) 2002-2024 Jouni Malinen <j@w1.fi> and contributors | <https://w1.fi/wpa_supplicant/> |
| libnl-3.so.200, libnl-genl-3.so.200 (libnl 3.2.25) | LGPL-2.1-only | (c) 2003-2014 Thomas Graf <tgraf@suug.ch> et al. | <https://github.com/thom311/libnl> |

Provenance and build details:

- **pixiewps** was compiled by this project from upstream source using the
  Android NDK r27 (clang 18) for arm64-v8a and armeabi-v7a. Build recipe:
  `scripts/build-wifi-binaries.sh`. Source revision: upstream master as of
  2026-10.
- **wpa_supplicant** (arm64-v8a) and **wpa_cli** were compiled by this project
  from the wpa_supplicant 2.11 release with the Android NDK r27 (clang 18),
  API 24. The supplicant is built with `CONFIG_DEBUG_FILE=y`, without
  `CONFIG_ANDROID_LOG`, so its WPS debug hexdumps are written in full to
  stdout instead of being truncated to 32 bytes in logcat. A one-line
  `fflush(stdout)` after each debug line keeps the pipe unbuffered so the app
  can parse the M3 fields while the supplicant is still running; the patch is
  in `scripts/patches/wpa_supplicant-2.11-stdout-flush.patch`. Build recipe:
  `scripts/build-wifi-binaries.sh`; the exact configuration is documented in
  `BINARIES_SETUP.md`.
- **libnl 3.2.25** was compiled by this project for Android arm64-v8a and is
  bundled because the supplicant uses the libnl-3 genl API for nl80211. A
  small compatibility patch adds the `NLA_S8` type and `nla_get_s8()` helper
  required by wpa_supplicant 2.11; the patch is in
  `scripts/patches/libnl-3.2.25-wpa2.11.patch`. libnl is dynamically linked
  via `LD_LIBRARY_PATH`; its complete corresponding source, including the
  patch, is available from the upstream release and this repository.

Notes:

- **pixiewps** performs the offline WPS PIN brute-force at the core of the
  Pixie Dust attack. Adopting GPL-3.0 as AmWF's project license was decided
  precisely so this component could be bundled without license conflicts.
- **wpa_supplicant** is used as a station-side WPS client to trigger the M3
  exchange, and **wpa_cli** drives its control interface. The BSD-3-Clause
  branch of their dual license permits redistribution in binary form with
  attribution; the copyright notice is preserved here. Recipients may obtain
  the corresponding source for the wpa_supplicant 2.11 release at
  <https://w1.fi/releases/wpa_supplicant-2.11.tar.gz>, and for libnl at
  <https://github.com/thom311/libnl/releases/tag/libnl3_2_25>.
- The Android platform libraries used by the app itself (bionic, liblog, etc.)
  are provided by the device and are not bundled in `assets/wifi/`.
