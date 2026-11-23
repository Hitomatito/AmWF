# AmWF Troubleshooting & Diagnostic Guide

This guide covers common errors, edge cases, and hardware behaviors encountered when running AmWF, along with step-by-step diagnostic and remediation steps.

---

## 1. Quick Diagnostic Checklist

Before troubleshooting individual error messages, verify the following baseline requirements:

- [ ] **Device has a Qualcomm Snapdragon processor** (MediaTek, Exynos, Tensor, and Kirin are not supported).
- [ ] **Root access is granted and functional** (tested in Magisk, KernelSU, or APatch).
- [ ] **Location toggle is turned ON** in Android quick settings (required by the OS for Wi-Fi scanning).
- [ ] **Runtime permissions are granted** (`Ubicación precisa` / `Fine Location`, and `Dispositivos cercanos` / `Nearby Devices` on Android 13+).
- [ ] **Device is running 64-bit ARM (`arm64-v8a`)** for full Pixie Dust audit capabilities.

---

## 2. Common Issues & Solutions

### 2.1 "Binarios Pixie Dust no disponibles" (Missing Pixie Dust Binaries)
**Symptom:** The compatibility check reports that required binaries are missing, or tapping a network displays a warning.

**Possible Causes & Fixes:**
1. **Lazy Asset Extraction:** AmWF unpacks its bundled ARM binaries on first launch and after app updates. If you ran the check immediately upon opening the app, the background extraction may still have been completing.
   - *Fix:* Close the app, reopen it, and tap **Verificar compatibilidad** again.
2. **32-Bit ARM Device (`armeabi-v7a`):** AmWF ships custom `wpa_supplicant` only for `arm64-v8a`. Older 32-bit devices cannot capture complete M3 frames with the bundled toolchain.
   - *Fix:* Inspect the architecture row in the compatibility screen. If it displays `armeabi-v7a`, M3 capture is unsupported on that hardware.
3. **Storage / App Data Corruption:**
   - *Fix:* Go to **Android Settings → Apps → AmWF → Storage → Clear Data**, then reopen the app and grant root access.

---

### 2.2 "Socket not created" / Supplicant Communication Failure
**Symptom:** The audit stops immediately at the Association stage with an error indicating that the control interface socket could not be opened.

**Possible Causes & Fixes:**
1. **Treble Directory Discrepancy on Upgraded ROMs:** Android 9+ uses `/data/vendor/wifi/amwf`, while Android 7–8 uses `/data/misc/wifi/amwf`. Devices running custom ROMs originally designed for older Android releases sometimes retain legacy directory trees.
   - *Fix:* Check if `/data/vendor/wifi` exists on your system. If missing, create it or grant full permissions using root terminal:
     ```bash
     su
     mkdir -p /data/vendor/wifi/amwf
     chmod 771 /data/vendor/wifi/amwf
     chown wifi:wifi /data/vendor/wifi/amwf
     ```
2. **Leftover Stale Sockets:** If a previous audit process crashed or was killed abruptly by the system, a stale UNIX socket file may remain.
   - *Fix:* AmWF cleans these automatically, but you can manually purge them:
     ```bash
     su
     rm -rf /data/vendor/wifi/amwf/* /data/misc/wifi/amwf/*
     ```

---

### 2.3 Monitor Mode Toggle Fails (con_mode Not Found or Write Error)
**Symptom:** Tapping the **Modo Monitor** toggle fails, displays an error toast, or remains off.

**Possible Causes & Fixes:**
1. **Compatibility Check Was Not Run:** AmWF intentionally requires a successful compatibility check during the current app process before allowing monitor mode to be enabled.
   - *Fix:* Tap **Verificar compatibilidad** first.
2. **Kernel Missing `con_mode` Node:** Some OEM kernels or heavily modified third-party kernels remove Qualcomm's debug parameters.
   - *Fix:* In a root shell, run:
     ```bash
     su
     find /sys/module -name con_mode 2>/dev/null
     ```
     If this command returns no output, your kernel does not expose the Qualcomm monitor mode hook.

---

### 2.4 "AP rechazó la asociación" (Association Rejections)
**Symptom:** During an audit, the console logs multiple association rejections before aborting.

**Possible Causes & Fixes:**
1. **Post-Audit Lockout:** if you audited this same AP in the last minutes, the rejection is usually the router's own temporary WPS lockout, not a configuration problem.
   - *Fix:* Wait a few minutes and retry.
2. **Protected Management Frames (802.11w / PMF):** Modern WPA3 or transition-mode routers require PMF, which blocks unauthorized unencrypted WPS association.
3. **MAC Filtering or Blacklisting:** The target AP restricts connections to whitelisted hardware addresses.
4. **Signal Degradation:** The signal may be too weak for stable 802.11 management frames.
   - *Fix:* Move closer to the access point — the **Buena señal / Strong signal** filter chip (−70 dBm or better) marks the range where real audits succeed.
   - *Design Behavior:* AmWF uses an `AssocRejectTracker` budget to abort early rather than flooding the air with futile association requests.

---

### 2.5 "WPS bloqueado" (WPS Lockout / Rate Limiting)
**Symptom:** The console reports that WPS state is locked or `pixiewps` calculation fails because the AP refuses WPS enrollment.

**Possible Causes & Fixes:**
- **Router Security Lockout:** Most residential routers temporarily lock their WPS feature after 3 to 10 failed PIN attempts. The lockout duration ranges from 5 minutes to 24 hours depending on router firmware.
- *Fix:* You must wait for the AP's lockout period to expire, or power-cycle the router if you own it.

---

### 2.6 "Sin respuesta del AP" (No Response to the WPS Registration)
**Symptom:** The audit sends the registration request, but the AP never answers (no association, no M1) and the attempt times out.

**Possible Causes & Fixes:**
The audit stops as `M3Capture.NoResponse`, and the localized reason tells the two cases apart:
- **The AP still shows up in the supplicant's scans but never answers:** a router-side WPS block or a session stuck half-open — not a signal problem.
  - *Fix:* Wait a few minutes and retry.
- **The AP never appears in scans:** the signal is weak, or interference corrupted the exchange.
  - *Fix:* Move closer to the access point. AmWF also spends one full-band retry round before declaring an AP out of range, which covers an AP that changed channel after the UI scan.

---

### 2.7 Wi-Fi Radio Fails to Reconnect After an Audit
**Symptom:** Normal Wi-Fi stays off after an audit is canceled or completed.

**Design Safeguards:**
- After issuing `svc wifi enable`, AmWF confirms the radio with two independent sources — the `dumpsys` framework state and `settings get global wifi_on` (via `WifiRestore.combinedEnabled`) — and retries up to three times, because the command can return code 0 without actually powering the radio on, and some OEM ROMs (MIUI) briefly report "enabled" before revoking the toggle.
- **Crash-safe markers:** both the compatibility check and the Pixie Dust audit persist a marker before touching the radio and clear it only once the framework confirms the radio is back on. If AmWF is killed mid-check or mid-audit, simply **reopen the app**: the pending restore is applied automatically on the next launch.
- *Quick Fix:* Toggle **Airplane Mode** on and off in Android quick settings, or run via root terminal:
  ```bash
  su
  svc wifi enable
  ```

---

### 2.8 Empty Network List in Pixie Dust Picker
**Symptom:** The network list shows 0 networks, or pulls down to refresh indefinitely.

**Possible Causes & Fixes:**
1. **Location Toggle is OFF:** Android framework privacy rules strictly prevent Wi-Fi scans if the system-wide Location toggle is disabled.
   - *Fix:* Pull down your Android notification shade and turn ON **Location** (GPS).
2. **Missing Permissions:**
   - *Fix:* Go to **App Info → Permissions** and ensure **Location (Precise)** and **Nearby Wi-Fi Devices** (Android 13+) are granted.
3. **Throttled Wi-Fi Scans:** Android limits scan frequency per foreground app.
   - *Fix:* Wait a few seconds before swiping down to refresh again.
