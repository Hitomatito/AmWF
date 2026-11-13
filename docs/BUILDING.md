# Building & Compiling AmWF

This guide provides complete instructions for setting up the build environment, compiling the Android application from the command line or Android Studio, running unit tests, and rebuilding the bundled native ARM binaries.

---

## 1. Environment & Toolchain Prerequisites

### 1.1 Toolchain Specifications
- **Gradle:** 9.6.0 (via `gradlew` wrapper)
- **Android Gradle Plugin (AGP):** 9.5.1 (AGP 9 compiles Kotlin natively; do not apply `org.jetbrains.kotlin.android`)
- **JDK:** Java 21 (used by the Gradle daemon via `gradle/gradle-daemon-jvm.properties` and the Foojay toolchain resolver); compiles to Java 11 bytecode.
- **Android SDK:**
  - `compileSdk`: 37 (minor API level 1 / 37.1)
  - `targetSdk`: 37
  - `minSdk`: 24 (Android 7.0 Nougat)

### 1.2 Setting Up SDK and Java Paths
Ensure `JAVA_HOME` points to a compatible JDK 21 installation, and specify the Android SDK path via `ANDROID_HOME` or `local.properties`:

```bash
# Example setting JAVA_HOME (using Android Studio's bundled JBR or system JDK)
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME=/home/user/Android/Sdk

# Alternatively, define the SDK in local.properties (gitignored):
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

> [!WARNING]
> Never commit or stage `local.properties`, `keystore.properties`, or `*.logcat` files. They contain machine-specific paths, credentials, or hardware logs.

---

## 2. Building the Android Application

AmWF is a single-module project (`:app`). **All Gradle tasks must be prefixed with `:app`** (the root project does not apply the Android application plugin, so bare tasks like `./gradlew assembleDebug` do not exist).

### 2.1 Compiling a Debug APK
```bash
./gradlew :app:assembleDebug
```
The output APK is generated at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### 2.2 Running Unit Tests
Unit tests are written with plain JUnit 4 and `kotlinx-coroutines-test` (no Robolectric).
```bash
# Run all JVM unit tests
./gradlew :app:testDebugUnitTest

# Run a specific test class
./gradlew :app:testDebugUnitTest --tests "com.hitomatito.amwf.wifi.pixiedust.M3ParserTest"
```

### 2.3 Running Static Analysis & Lint
```bash
./gradlew :app:lintDebug
```

### 2.4 Complete Local Verification
To execute the full verification suite before committing:
```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

### 2.5 Note on Instrumented Tests
`app/src/androidTest` contains no test files. Testing low-level Wi-Fi monitor mode and WPS Pixie Dust requires physical Qualcomm hardware with root privileges; standard x86/x86_64 emulators cannot execute ARM binaries or interact with Qualcomm `con_mode` kernel drivers.

---

## 3. Release Builds & Signing

Release builds require a `keystore.properties` file in the root project directory. If absent, release builds remain unsigned.

### 3.1 `keystore.properties` Configuration
Create `keystore.properties` in the project root:
```properties
storeFile=/path/to/your-release-key.jks
storePassword=yourKeystorePassword
keyAlias=yourKeyAlias
keyPassword=yourKeyPassword
```

### 3.2 Assembling a Signed Release APK / AAB
```bash
# Signed APK
./gradlew :app:assembleRelease

# Signed Android App Bundle (AAB)
./gradlew :app:bundleRelease
```

> [!IMPORTANT]
> The setting `bundle { language { enableSplit = false } }` in `app/build.gradle.kts` is load-bearing. Because AmWF allows dynamic runtime language switching via `LocaleHelper`, an App Bundle that splits language resources would drop unselected locales. Do not enable language splitting.

---

## 4. Rebuilding Native Pixie Dust Binaries

AmWF ships precompiled ARM binaries in `app/src/main/assets/wifi/`. Standard Gradle builds do **not** recompile these binaries.

You only need to execute this workflow if you are upgrading or patching `pixiewps`, `wpa_supplicant`, or `libnl`.

### 4.1 Prerequisites for Native Builds
- **Android NDK:** r27 or newer (`export ANDROID_NDK=/path/to/android-ndk-r27`).
- **Source Repositories:** Sibling directories located in the parent directory of AmWF:
  ```text
  ../
  ├── AmWF/
  ├── pixiewps-master/       (git clone https://github.com/wiire-a/pixiewps)
  ├── wpa_supplicant-2.11/   (extracted from wpa_supplicant-2.11.tar.gz)
  └── libnl-3.2.25/          (extracted from libnl-3.2.25.tar.gz)
  ```

### 4.2 Executing the Build Script
Run the automated build script with the desired target ABI:

```bash
# Compile for 64-bit ARM (builds pixiewps, wpa_supplicant, wpa_cli, libnl)
./scripts/build-wifi-binaries.sh arm64-v8a

# Compile for 32-bit ARM (builds pixiewps_32 only)
./scripts/build-wifi-binaries.sh armeabi-v7a
```

### 4.3 Automated Patches Applied
The build script automatically applies AmWF's custom patches located in `scripts/patches/`:
- `libnl-3.2.25-wpa2.11.patch`: Backports `NLA_S8` and `nla_get_s8()` required by `wpa_supplicant 2.11`.
- `wpa_supplicant-2.11-stdout-flush.patch`: Adds `fflush(stdout)` after each debug emission to stream M3 hexdump frames in real time.

See [BINARIES_SETUP.md](../BINARIES_SETUP.md) for deeper details on the native binary compilation pipeline.
