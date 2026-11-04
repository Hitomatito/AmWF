# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| Latest release (2.0.x) | Yes |
| Older releases | No |

## Reporting a vulnerability

AmWF is a security research tool, so we take vulnerability reports seriously.

**Do not open a public issue with exploit details.** Instead:

1. If GitHub private vulnerability reporting is enabled on this repository,
   use it (**Security → Report a vulnerability**).
2. Otherwise, open a [GitHub issue](https://github.com/Hitomatito/AmWF/issues) with
   the *minimum* information needed to identify the problem (component, version,
   device) and no reproduction details, and ask for a private channel.

Please include, when relevant:

- App version (`versionName` in the APK, e.g. 2.0.1) and Android version
- Device/chipset (e.g. Snapdragon 732G) and root method
- Affected component (e.g. monitor mode, Pixie Dust audit, diagnostics export)
- A redacted diagnostic report if it helps reproduce (see below)

## Handling sensitive data

- **Audit logs** (in `/sdcard/AmWF/pixie-logs/` or the app's private directory)
  contain the WPS PIN and the WPA key **in plain text**. Never attach them to an
  issue or send them anywhere. Delete them when you no longer need them.
- **Diagnostics reports** exported by the app redact MAC addresses (kept only to
  their OUI) and redact SSIDs, including SSIDs discovered from `iw dev`. Even so,
  inspect a report before sharing it.
- If a report accidentally contains credentials or personal data, say so in the
  report so maintainers can purge the data.

## Scope

In scope:

- Vulnerabilities in AmWF's own code, build process or bundled assets
  (`wpa_supplicant`, `wpa_cli`, `pixiewps`, libnl)
- Leaks of data the app stores or exports (audit logs, diagnostics)
- Privilege escalation within the app's normal operation

Out of scope:

- Weaknesses in the WPS/WPA protocols or in specific access points (that is what
  the Pixie Dust audit is *for*)
- Vulnerabilities in rooted devices, custom ROMs, kernels, Magisk/KernelSU or
  other root solutions
- Findings that require physical access to an unlocked device owned by the user
- Reports from automated scanners without a demonstrated impact

## What is working as intended

- Monitor mode requires root and Qualcomm `con_mode` support; it interrupts WiFi
  briefly. This is documented behavior, not a bug.
- The app has no Internet permission; it never transmits anything.
- Enabling monitor mode requires a prior successful compatibility check in the
  session (it can be disrupted by the check); disabling it always works.

## Safe harbor

Good-faith security research performed on your own devices and networks, following
this policy and the legal notice in [DISCLAIMER_en.md](DISCLAIMER_en.md), will not
be treated as malicious activity. Do not test against networks or devices you do
not own or are not authorized to use.

## Third-party components

Security issues in upstream components (pixiewps, wpa_supplicant, libnl) should
also be reported upstream; see their repositories in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). If an issue affects AmWF's exact
bundled build, let us know as well.
