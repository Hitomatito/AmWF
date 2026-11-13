# AmWF User Guide

Welcome to the **AmWF User Guide**. This document walks you through setting up, configuring, and using AmWF to perform Wi-Fi monitor mode captures and WPS Pixie Dust audits on rooted Qualcomm Snapdragon Android devices.

---

## 1. Prerequisites & Setup

### 1.1 Hardware Requirements
- A phone powered by a **Qualcomm Snapdragon** chipset (e.g., Snapdragon 600, 700, 800 series, or Snapdragon Gen 1/2/3).
- Built-in Wi-Fi adapter (no USB OTG Wi-Fi dongle required).

### 1.2 Software & Root Requirements
- **Android Version:** Android 7.0 (Nougat / API 24) through Android 15 (API 35+).
- **Root Solution:** Any standard modern root solution:
  - **Magisk** (v24+)
  - **KernelSU** (v0.6+)
  - **APatch** (v10+)
- Superuser access must be granted when prompted by AmWF.

### 1.3 Android System Permissions
To discover nearby Wi-Fi networks and interact with the hardware, the Android framework requires specific permissions:
- **Location Permission (Precise / Fine Location):** Android requires location access to return Wi-Fi scan results (BSSID, SSID, signal level).
- **Location Services (GPS / System Location Toggle):** Must be turned ON in Android quick settings; otherwise, the OS returns an empty scan list.
- **Nearby Wi-Fi Devices (`NEARBY_WIFI_DEVICES`):** On Android 13+ (API 33+), this runtime permission must be granted.

---

## 2. Getting Started: Compatibility Verification

AmWF includes a non-destructive hardware verification suite to test whether your device's kernel and Wi-Fi drivers support raw packet operations before changing any settings.

```
+-----------------------------------------------------------+
|                     AmWF Main Screen                      |
|                                                           |
|   [ Verificar compatibilidad / Verify compatibility ]    |
|                                                           |
|   Hardware Status:                                        |
|   * Chipset & Driver: Qualcomm qcacld-3.0                 |
|   * Root Access: Granted                                  |
|   * con_mode parameter: /sys/module/wlan/parameters/...   |
|   * Packet Injection: Supported                           |
|   * Packet Capture: Supported                             |
|   * Pixie Dust Binaries: Ready                            |
+-----------------------------------------------------------+
```

### Steps to Verify
1. Launch AmWF.
2. Tap **Verificar compatibilidad** (**Verify compatibility**).
3. Grant Superuser privileges when your root manager prompts you.
4. The test will run through several automated checks:
   - Detecting root access and SELinux configuration.
   - Checking the existence and write permissions of Qualcomm's `con_mode` node.
   - Verifying packet injection capabilities.
   - Verifying promiscuous raw capture capabilities.
   - Validating the presence and execution rights of bundled native binaries (`pixiewps`, `wpa_supplicant`, `wpa_cli`).

> [!NOTE]
> **Temporary Wi-Fi interruption:** During the compatibility test, the Wi-Fi radio is toggled off and on to evaluate hardware state transitions. Wi-Fi is restored to its previous state automatically once the test finishes.
> Results are cached for the entire session, so you only need to run this test once per app launch.

---

## 3. Wi-Fi Monitor Mode (RFMON)

Monitor mode enables the phone's Wi-Fi interface to listen to raw 802.11 radio frames in the air without associating with an access point.

### 3.1 Enabling Monitor Mode
1. Ensure you have run **Verify compatibility** at least once during your session.
2. Under the **Modo Monitor** card, toggle the switch to **Activado** (**Enabled**).
3. The interface (`wlan0`) switches to monitor mode via Qualcomm `con_mode=4`.
4. The status card displays the active interface, channel, and frequency.

### 3.2 Advanced Workflow: Capturing Traffic
Once monitor mode is active, you can run command-line network inspection tools directly on your phone using terminal emulators such as **Termux** (with root privileges):

```bash
# In Termux (as root):
su
tcpdump -i wlan0 -s 0 -w /sdcard/capture.pcap
```

### 3.3 Disabling Monitor Mode
1. Toggle the **Modo Monitor** switch to **Desactivado** (**Disabled**).
2. The driver resets `con_mode=0`, restoring normal station (client) operation.
3. System Wi-Fi is reconnected automatically.

---

## 4. WPS Pixie Dust Security Audit

The Pixie Dust module allows network owners and penetration testers to audit whether nearby Wi-Fi routers use vulnerable, non-random WPS seeds.

### 4.1 Scanning & Selecting a Network
1. Tap **Auditoría WPS Pixie Dust** on the main screen.
2. The network picker automatically scans the surrounding airspace and populates the list.
3. **Filter and Search:**
   - **Search Bar:** Type an SSID name or BSSID (MAC address).
   - **Auditables / Auditable:** Filters the list to display only routers broadcasting WPS enabled.
   - **5 GHz / 2.4 GHz:** Filters networks by frequency band.
4. Each network card displays:
   - **SSID** (Network Name)
   - **BSSID** (Router MAC address)
   - **Signal Level:** RSSI in dBm with a colored signal indicator.
   - **WPS Badge:** Indicates whether WPS is locked, unlocked, or version 1.0/2.0.

### 4.2 Starting the Audit
AmWF uses a streamlined **one-tap execution** model:
- Simply tap the target network card.
- The audit begins immediately; system Wi-Fi is turned off to dedicate the radio to the custom supplicant.

```
+-----------------------------------------------------------+
|                    Pixie Dust Audit                       |
|   Target: MyHomeWiFi [AA:BB:CC:DD:EE:FF]                  |
|   Status: Capturando M3 / Capturing M3...                 |
|   Progress: [==================>          ] 60%           |
|   Session Time: 00:14                                     |
|                                                           |
|   [v] Ver consola / Show console                          |
|   -----------------------------------------------------   |
|   [12:30:02] Asociando con AA:BB:CC:DD:EE:FF...           |
|   [12:30:03] EAPOL-Start enviado                          |
|   [12:30:04] Recibido M1. Enviando M2...                  |
|   [12:30:05] M3 capturado exitosamente.                   |
|   [12:30:06] Ejecutando pixiewps...                       |
|                                                           |
|   [ Cancelar auditoría / Cancel audit ]                   |
+-----------------------------------------------------------+
```

### 4.3 Audit Phases Explained
| Phase | Action Performed |
|---|---|
| **1. Asociación / Association** | Establishes 802.11 open authentication and association with the AP using AmWF's custom `wpa_supplicant`. |
| **2. Captura M1–M3** | Initiates EAP-WPS exchange, capturing the Enrollee Nonce, Diffie-Hellman public keys, and authenticator hashes. |
| **3. Cálculo Pixie Dust** | Feeds captured parameters into `pixiewps`. If the AP's PRNG is flawed, the 8-digit WPS PIN is computed offline in seconds. |
| **4. Extracción de Clave PSK** | Connects to the AP using the discovered PIN as a WPS registrar (`wps_reg`), obtaining the plaintext WPA/WPA2 passphrase. |

### 4.4 Interpreting Early Abort Reasons
If an audit cannot proceed, AmWF detects the underlying reason and stops early to preserve battery and avoid triggering router lockouts:
- **WPS bloqueado (WPS Lockout):** The AP has locked WPS enrollment due to previous failed attempts.
- **Rechazo de asociación (Association Reject):** The AP is overloaded, requires 802.11w PMF, or enforces MAC filtering.
- **Sin respuesta M3 (No M3 response):** The AP accepted association but failed to reply with WPS handshake messages within the timeout window.

### 4.5 Viewing & Copying Results
Upon successful audit:
- The recovered **WPS PIN** (8 digits) and **WPA Key** (passphrase) are presented clearly on screen.
- Tap the **Copiar clave** (**Copy key**) or **Copiar PIN** button to save credentials to the clipboard.
- The audit report is saved locally to `/sdcard/AmWF/pixie-logs/`.

> [!WARNING]
> **Plaintext Credential Notice:** Audit log files store the discovered WPS PIN and WPA passphrase in plaintext for verification. Store them securely and never post them on public issue trackers.

---

## 5. Diagnostics & Privacy

### 5.1 Exporting a Diagnostic Report
If you encounter compatibility or execution issues:
1. Tap the **Diagnósticos** (**Diagnostics**) button in the toolbar.
2. Review the generated system report.
3. Tap **Compartir informe** (**Share report**) to export the report via Android's system share sheet.

### 5.2 Privacy Guarantees
- **No Internet Access:** AmWF declares zero internet networking permissions.
- **Automatic Data Redaction:** Before any diagnostic report is exported, AmWF redacts:
  - MAC addresses (truncated to only their 3-byte vendor OUI, e.g., `AA:BB:CC:**:**:**`).
  - Network names (SSIDs discovered through wireless scans are replaced with anonymized hashes).
  - Sensitive cryptographic keys and passwords are NEVER routed to diagnostics logs.
