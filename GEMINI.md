# Antigravity Workspace Guidelines for Music Hi-Fi (ESP32 DAC & Android)

## 🚨 CRITICAL RULE 1: Package Identity (`applicationId`)
- The production package name for this app is **`com.stash.app.debug`**.
- **NEVER** change `applicationId` to `com.stash.app` or anything else.
- All users in production have `com.stash.app.debug` installed. Changing it breaks the in-app updater (`NOT_AN_UPDATE`) and creates duplicate app installs.
- `app/build.gradle.kts` contains an enforced compile-time check guarding this.

---

## 🚀 CRITICAL RULE 2: Automated Release Protocol
Whenever releasing a new version:
1. **Bump Version in `app/build.gradle.kts`**:
   - `versionCode` (increment by 1, e.g. `174` -> `175`)
   - `versionName` (e.g. `"0.9.138"` -> `"0.9.139"`)
2. **Update `.github/workflows/build-apk.yml`**:
   - Update `tag_name` to `v<version>` (e.g. `v0.9.139`)
   - Update `name` to `"Music Hi-Fi v<version>"`
   - Update release notes in the workflow body.
3. **Commit & Push to `master`**:
   - `git add app/build.gradle.kts .github/workflows/build-apk.yml`
   - `git commit -m "chore(release): bump version to v<version>"`
   - `git push origin master` (with `BypassSandbox: true`)
4. **Update the Release Google Sheet IMMEDIATELY**:
   The in-app updater's Apps Script mirror reads from this Google Sheet:
   - **Spreadsheet ID**: `1y1K0DY9MNJcPkN5sl1HPe44VifRrjL4Ovfk6-PPpSA4`
   - **Sheet Name**: `Release`
   - Run these 3 simple commands immediately (escape commas with `\,` in notes):
     ```powershell
     gsheets mutate write 1y1K0DY9MNJcPkN5sl1HPe44VifRrjL4Ovfk6-PPpSA4 "Release!A2" "<version>"
     gsheets mutate write 1y1K0DY9MNJcPkN5sl1HPe44VifRrjL4Ovfk6-PPpSA4 "Release!B2" "https://github.com/Gokul3611/HIFI_music_app/releases/download/v<version>/Music.apk"
     gsheets mutate write 1y1K0DY9MNJcPkN5sl1HPe44VifRrjL4Ovfk6-PPpSA4 "Release!C2" "<escaped_notes>"
     ```
   - Verify by querying the Apps Script endpoint:
     `curl.exe -k -sL "https://script.google.com/macros/s/AKfycbxGnTkSM45kveGO79MRvLSJk1ZAGNpM11X4UVZIr9H89NSev2gxmXR1Db7NnZpru1chFQ/exec?action=update"`
