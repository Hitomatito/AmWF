# Disclaimer

**Last updated: November 2026**
**Spanish mirror: [DISCLAIMER_es.md](DISCLAIMER_es.md)**

## Important notice

AmWF is a wireless network development and auditing tool. Using this application implies full acceptance of the terms and conditions set out in this document.

## Nature of the application

AmWF is an application that allows:

- Enable and disable WiFi monitor mode
- Verify device compatibility
- Show technical system information
- Audit the WPS security of networks you own or are expressly authorized to audit (WPS Pixie Dust)
- Export a local diagnostics report on demand

## Legitimate use

This tool is designed exclusively for:

- **Security auditing** of networks you own or have explicit permission to test
- **Penetration testing** of networks under your control
- **Development and learning** about wireless network security
- **Network analysis** for educational or research purposes

## Prohibited use

The use of this application is **strictly prohibited** for:

- **Unauthorized access** to WiFi networks you do not own and are not authorized to audit
- **Interception of communications** without consent
- **Capture of handshakes** for cracking passwords of third parties
- **Any activity illegal** under local, state or federal laws
- **Denial-of-service attacks** (DoS/DDoS)
- **Invasion of privacy** of third parties

## Legal framework

The user is responsible for complying with all applicable laws, including but not limited to:

- **Local legislation** on cybercrime
- **Telecommunications regulations** in their country
- **Terms of service** of their internet provider
- **User agreements** of the WiFi networks

### Examples of applicable legislation

| Jurisdiction | Law | Description |
|--------------|-----|-------------|
| USA | CFAA (18 U.S.C. § 1030) | Unauthorized access to protected computers |
| Spain | Código Penal art. 197 bis (LO 10/1995) | Illegal access to information systems |
| Mexico | Código Penal Federal arts. 211 bis | Acceso ilícito a sistemas y equipos de informática |
| Colombia | Ley 1273 de 2009 | Delitos informáticos |
| Argentina | Ley 26.388 (2008) | Delitos informáticos (reforma al Código Penal) |
| European Union | Directive 2013/40/EU | Attacks against information systems |

**Note:** this list is illustrative and not exhaustive. Consult the legislation of your country.

## User responsibility

By using AmWF, the user:

1. **Declares to own** or have express authorization over the device and networks used
2. **Accepts full responsibility** for their actions and consequences
3. **Understands that any improper use** is the exclusive responsibility of the user
4. **Commits to use** the tool only for legitimate purposes

## Risks and limitations

### Technical risks

- The application modifies Linux kernel parameters
- Monitor mode may affect system stability
- Some SELinux configurations may block access
- Not every Snapdragon device is compatible
- **The con_mode method provides passive monitor mode only** on most devices
- **Packet injection and complete capture are not guaranteed** — this depends on the WiFi driver
- The capability check may not be accurate on all devices
- WPS audits only work against access points with WPS enabled and unlocked; results depend on the AP implementation

### Potential consequences

Misuse of this tool may result in:

- **Criminal liability** for unauthorized access
- **Civil liability** for damages to third parties
- **Loss of device warranty**
- **Network ban** for malicious activity
- **Legal prosecution** depending on the jurisdiction

## Recommended safe practices

If you use this tool:

1. **Use a VPN** to protect your traffic
2. **Keep the device updated** with the latest security patches
3. **Do not keep captured data** longer than necessary
4. **Securely delete** any sensitive captured information
5. **Document your audit activities** for your own protection

## Activity log

What the application stores on your device:

- **Audit logs** are written locally (in `/sdcard/AmWF/pixie-logs/`, or in the app's private directory as a fallback) and may contain the WPS PIN and the WPA key **in plain text**
- An **in-memory log buffer** is used by the diagnostics export; the exported report redacts MAC addresses (OUI only) and SSIDs
- **No data is transmitted** to external servers: the app has no Internet permission
- Only **local preferences** (language) and the audit logs described above are stored

Audit logs are written with owner-only permissions. Treat them as secrets: do not share or upload them, and delete them when they are no longer needed.

## Updates

The developer reserves the right to:

- Modify, update or discontinue the application at any time
- Change the terms of this disclaimer with prior notice
- Update the technical requirements without prior notice

## Contact

To report bugs, suggest improvements or report misuse:

- **GitHub Issues**: [Report an issue](https://github.com/Hitomatito/AmWF/issues)
- **Security reports**: see [SECURITY.md](SECURITY.md)

## Acknowledgement

By installing and using AmWF:

```
By installing this application, you confirm that:

□ You are of legal age in your jurisdiction
□ You have legal authority over the device
□ You will use the tool only for legitimate purposes
□ You understand and accept the associated risks
□ You are responsible for your own actions
□ You will not use this tool for illegal activities
□ You understand that misuse can have serious legal consequences
```

## Legal exemption

**AmWF and its developers:**

- **Are not responsible** for the misuse of the application
- **Do not endorse or promote** illegal activities
- **Provide no warranties** about the effectiveness or security of the software
- **Are not liable** for direct, indirect or consequential damages
- **Have no affiliation** with device manufacturers or service providers
- **Reserve the right** to cooperate with legal authorities in cases of abuse

---

**THIS DISCLAIMER IS AN INTEGRAL PART OF THE APPLICATION'S TERMS OF USE.**

**IF YOU DO NOT AGREE WITH THESE TERMS, DO NOT INSTALL OR USE THE APPLICATION.**

---

© 2026 AmWF. Free software under [GPL-3.0-or-later](LICENSE).
