# Submitting AmWF to F-Droid

F-Droid does **not** build from a submission in this repository. The build recipe
lives in [`fdroiddata`](https://gitlab.com/fdroid/fdroiddata), in a YAML file
named after the application ID: `metadata/com.hitomatito.amwf.yml`. This page is
the upstream-side checklist and a ready-to-copy recipe.

Reference docs: [Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/),
[Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/).

## Upstream checklist (already satisfied here)

- [x] Public source repo, GPL-3.0-or-later `LICENSE`, SPDX headers on Kotlin sources.
- [x] FOSS dependencies only (AndroidX, Material, Kotlin/coroutines, ZXing core — all Apache-2.0);
  no GMS, Firebase, analytics, ads or `INTERNET` permission.
- [x] Release tag `v2.1` on the version-bump commit; `versionCode 7` / `versionName 2.1`
  in `app/build.gradle.kts`.
- [x] Fastlane metadata in both locales: `short_description.txt`, `full_description.txt`,
  `images/icon.png` (512×512), two screenshots, and `changelogs/7.txt`.
  Short descriptions have no trailing dot and changelogs stay under 500 characters.
- [x] Release builds work **without** `keystore.properties`: the signing config is conditional,
  so F-Droid can build an unsigned APK and sign it with its own key. Never commit that file.

## Points to disclose in the merge request

- **Bundled native binaries:** `app/src/main/assets/wifi/` tracks 8 prebuilt ARM binaries
  (pixiewps, wpa_supplicant, wpa_cli, libnl). They are FLOSS with documented provenance in
  `THIRD-PARTY-NOTICES.md` and a reproducible build recipe in `scripts/build-wifi-binaries.sh`
  (NDK r27 + the patches in `scripts/patches/`). The recipe below uses `scanignore` **only**
  for that directory because these binaries are shipped deliberately, not produced by the
  Gradle build. If maintainers require them to be rebuilt during the F-Droid build, that
  needs a separate `srclibs`/`submodules` recipe.
- **Toolchain:** Gradle 9.9.2 wrapper, AGP 9.5.2, `compileSdk 37.1`, Java 21 daemon resolved
  through the foojay plugin (see `gradle/gradle-daemon-jvm.properties`). The build server may
  need a `sudo:` step to install JDK 21 / SDK 37.1 if its image does not ship them yet.
- **Gradle task:** the root project applies no Android plugin, so tasks are `:app`-prefixed.
  The recipe uses an explicit `build:` command instead of `gradle: release`.
- **Signing:** no `AllowedAPKSigningKeys` initially. F-Droid signs with its own certificate,
  so installs from F-Droid and from the GitHub release cannot update each other without
  uninstalling. Reproducible builds can be attempted later; adding the key now would be
  premature while the toolchain above may still change the output.
- **Root:** `RequiresRoot: true`; root is used for `con_mode`, `svc wifi` and the bundled
  native processes. No anti-feature applies: no ads, tracking, non-free network service or
  non-free asset.

## Metadata for `fdroiddata`

Fork <https://gitlab.com/fdroid/fdroiddata>, create a branch (e.g. `com.hitomatito.amwf`),
and add `metadata/com.hitomatito.amwf.yml`:

```yaml
Categories:
  - Security
License: GPL-3.0-or-later
AuthorName: Hitomatito
SourceCode: https://github.com/Hitomatito/AmWF
IssueTracker: https://github.com/Hitomatito/AmWF/issues
RepoType: git
Repo: https://github.com/Hitomatito/AmWF
RequiresRoot: true
AutoUpdateMode: Version
UpdateCheckMode: Tags
UpdateCheckData: 'app/build.gradle.kts|versionCode\s*=\s*(\d+)|.|versionName\s*=\s*"([^"]+)"'
CurrentVersion: 2.1
CurrentVersionCode: 7
MaintainerNotes: |
  Version info lives in app/build.gradle.kts, not in the root build file.
  The Gradle project is the :app module, hence the explicit assemble task.
  app/src/main/assets/wifi contains intentionally shipped FLOSS ARM binaries
  (pixiewps, wpa_supplicant, wpa_cli, libnl); licenses, provenance and the
  rebuild script are documented in THIRD-PARTY-NOTICES.md and BINARIES_SETUP.md.
  .
Builds:
  - versionName: 2.1
    versionCode: 7
    commit: ee944f07180494034acc45e95014ae3368474b85
    scanignore:
      - app/src/main/assets/wifi
    build:
      - ./gradlew :app:assembleRelease
    output: app/build/outputs/apk/release/app-release.apk
```

`Summary` and `Description` are intentionally omitted: F-Droid pulls the localized
Fastlane texts from this repository instead. `WebSite` is omitted too (there is no site
distinct from the source repository).

## Test and submit

With the [fdroidserver build container](https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/)
mounted on your `fdroiddata` clone:

```bash
fdroid readmeta
fdroid rewritemeta com.hitomatito.amwf
fdroid checkupdates --allow-dirty com.hitomatito.amwf
fdroid lint com.hitomatito.amwf
fdroid build com.hitomatito.amwf
```

If the build fails because of the JDK/SDK versions, add a `sudo:` step to the build block
and retest. After a clean build:

```bash
git add metadata/com.hitomatito.amwf.yml
git commit -m "New App: com.hitomatito.amwf"
git push origin com.hitomatito.amwf
```

Open a merge request against `fdroiddata` with the **New App** label, link this repository
and the GitHub release, and reply to reviewer questions there. After the MR is merged,
F-Droid's build cycle publishes the app; the guide quotes roughly 24–48 hours from merge
to appearance in the main repository.

For future releases: bump the version here, tag `vX.Y` on the bump commit, refresh the
Fastlane changelog/screenshots, and let `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`
pick up the new tag.
