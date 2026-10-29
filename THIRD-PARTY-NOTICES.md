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

## WiFi security toolchain (planned — Pixie Dust module)

The following components are planned to be bundled with the upcoming WPS /
Pixie Dust module. Licensing was verified against upstream sources before
adoption so the project license (GPL-3.0) remains compatible with every
component:

| Component | License | Copyright | Upstream |
|-----------|---------|-----------|----------|
| pixiewps | GPL-3.0-or-later | (c) 2015-2017 wiire <wi7ire@gmail.com> | <https://github.com/wiire-a/pixiewps> |
| wpa_supplicant / wpa_cli | BSD-3-Clause | (c) 2002-2019 Jouni Malinen <j@w1.fi> and contributors | <https://w1.fi/wpa_supplicant/> |
| libnl (libnl-3, libnl-genl-3, libnl-route-3) | LGPL-2.1-or-later | (c) Thomas Graf <tgraf@suug.ch> | <https://github.com/thom311/libnl> |

Notes:

- **pixiewps** performs the offline WPS PIN brute-force at the core of the
  Pixie Dust attack. Adopting GPL-3.0 as AmWF's project license was decided
  precisely so this component could be bundled without license conflicts.
- **wpa_supplicant** is used as a station-side WPS client to trigger the M3
  exchange. Its BSD-3-Clause terms permit redistribution in binary form with
  attribution; the copyright notice is preserved here.
- **libnl** is linked by the above binaries. LGPL-2.1 permits static/dynamic
  linking under its terms; if it is statically linked, corresponding object
  files will be offered as required by the license.
- When these components ship, their exact source revisions and build
  instructions will be published in this file so every recipient can obtain
  the corresponding source as required by the GPL-3.0 and LGPL-2.1.
