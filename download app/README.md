# Remote Control — downloads

**App version: v1.2 (versionName `1.2`, versionCode `3`)**

| File | Version | Built by |
|---|---|---|
| `remote-control-v1.0-debug.apk` | v1.2 (versionCode 3) | GitHub Actions — `Build debug APK` workflow |

The APK in this folder is produced automatically by
`.github/workflows/build-apk.yml` whenever the app sources change, then
committed back here. If the file is missing, open the repository's **Actions**
tab and run **Build debug APK** manually (*Run workflow*).

## Install

1. Download `remote-control-v1.0-debug.apk` from this folder.
2. Copy it to the Android phone, open it, allow **unknown sources**, install.
3. Launch **Remote Control** → grant location + notification permissions →
   enable **Remote Control taps** in Settings → Accessibility → tap
   **Grant screen capture** → toggle **Session running**.
4. Open the control page (see the root [README](../README.md)), enter the
   Session ID shown in the app, press Connect.

> Debug build, signed with the default debug keystore — for personal/testing
> use on devices you own or administer.
