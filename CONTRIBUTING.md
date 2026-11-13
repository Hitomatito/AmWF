# Contributing to AmWF

Thank you for your interest in contributing to **AmWF**! We welcome code contributions, documentation improvements, bug reports, and feature proposals.

AmWF is an open-source wireless security research tool licensed under the [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE).

---

## 1. Ethical & Responsible Research

AmWF is intended strictly for authorized wireless security auditing, educational purposes, and hardware development on devices and networks you own or have explicit permission to test.
- Read our [Legal Notice & Disclaimer](DISCLAIMER_en.md).
- Read our [Security Policy](SECURITY.md) before reporting potential vulnerabilities.

---

## 2. Development Setup & Verification

Before submitting code, ensure your changes build cleanly and pass all unit tests and lint checks.

### 2.1 Build Requirements
- **JDK:** Java 21 (foojay daemon resolver).
- **Android SDK:** compileSdk 37.1 / targetSdk 37, minSdk 24.
- **Gradle:** Run tasks using `./gradlew` from the repository root.

See [docs/BUILDING.md](docs/BUILDING.md) for full setup instructions.

### 2.2 Local Verification Commands
All Gradle tasks are prefixed with `:app`:

```bash
# 1. Run JVM Unit Tests
./gradlew :app:testDebugUnitTest

# 2. Run Android Lint
./gradlew :app:lintDebug

# 3. Assemble Debug APK
./gradlew :app:assembleDebug

# Combined one-liner before submitting PR:
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

---

## 3. Core Architectural Invariants

When submitting pull requests, you must uphold the following core architectural rules:

### 3.1 Pure Reducers & Zero-Context Business Logic
- **No Android `Context` in Business Logic:** `PixieDustRunner`, `M3Parser`, `PixieProcessSupport`, and reducers operate strictly on pure inputs, streams, or gateway interfaces (`PixieDustGateways.kt`).
- UI state decisions belong in `PixieDustReducers.kt` and `PixieNetworkFilter.kt` as pure, deterministic functions accompanied by comprehensive JUnit 4 unit tests under `app/src/test/java/`.

### 3.2 Dynamic Runtime Localization (i18n)
- **Spanish is the default UI language** (`app/src/main/res/values/strings.xml`).
- **English is the secondary language** (`app/src/main/res/values-en/strings.xml`).
- **Every user-facing string must be added to BOTH files.**
- Visible strings must resolve through the wrapped locale context (`LocaleHelper.applyLocale()`); raw `applicationContext` keeps the system locale and will silently ignore user language toggles.

### 3.3 Security, Privacy & Logging Hygiene
- **Zero Internet Access:** Never declare `android.permission.INTERNET`.
- **Sensitive Credential Isolation:** Use `ShellExecutor.logD/W/E` for operational logs destined for the exportable `AppLogger`. However, **never route WPS PINs, WPA PSKs, or raw M3 key material to `AppLogger`**. Sensitive audit logs must use raw `Log.d` (`PixieDustRunner.log`).
- **Diagnostics Redaction:** Preserve automatic redaction of MAC addresses (to OUI) and network SSIDs in `DiagnosticsFormat.kt`.

### 3.4 Android & Process Compatibility (minSdk 24)
- AmWF supports Android 7.0 (API 24). API 26+ process methods (`Process.isAlive()`, `Process.destroyForcibly()`, `Process.waitFor(timeout, unit)`) must go through `ProcessCompat.kt`.
- Cleanup operations must only terminate (`pkill`) binaries starting with the app's bundled binary directory; never terminate the system supplicant.

### 3.5 Native Binary Integrity
- Never substitute Android's system `wpa_supplicant`. Its `CONFIG_ANDROID_LOG` truncates M3 Diffie-Hellman keys to 32 bytes, which renders Pixie Dust calculation impossible.
- `NativeBinaries` is the single source of truth for binary paths and status checks. Always invoke `NativeBinaries.ensure(context)` before checking for missing binaries.

---

## 4. Git & Commit Guidelines

### 4.1 Commit Message Convention
Commits follow the [Conventional Commits](https://www.conventionalcommits.org/) specification in English:

```text
<type>(<scope>): <summary in imperative mood>

[optional body explaining context and why]
```

**Allowed Types:**
- `feat`: New feature or user-facing enhancement.
- `fix`: Bug fix.
- `refactor`: Code change that neither fixes a bug nor adds a feature.
- `docs`: Documentation updates.
- `chore`: Maintenance tasks, dependency bumps, release commits.
- `build`: Changes to Gradle build scripts, AGP, or NDK recipes.

*Example:*
```text
feat(pixiedust): add progressive backoff for wps_reg retries
fix(compat): resolve con_mode path using root shell to bypass SELinux
docs(readme): add troubleshooting section for socket directory
```

### 4.2 SPDX License Headers
Every new main Kotlin source file (`app/src/main/java/**/*.kt`) must begin with the project's SPDX identifier:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
```

### 4.3 Git Hygiene
Never stage or commit:
- `local.properties` (contains local SDK paths)
- `keystore.properties` (contains release signing credentials)
- `*.logcat` or raw test outputs (may contain sensitive device details)
