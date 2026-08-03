# Hardware Info

A small, HWMonitor-inspired spec sheet app for Android. Every line of code
in this project was written from scratch for this repo — there are **zero
third-party dependencies**, not even AppCompat. It talks only to the plain
Android framework and the kernel's `/proc` and `/sys` files.

## What it shows

| Section | Details |
|---|---|
| Processor | SoC name, core count, cluster layout, ABIs, governor, **live clock-speed charts** (average + per-core sparklines) |
| Temperatures | **Live battery temperature chart** plus sparklines for readable SoC thermal sensors |
| Memory | Total / available / used RAM (live), swap & zram, kernel cache, low-RAM threshold |
| Graphics | GPU renderer, vendor and GL version (queried via an off-screen EGL context) |
| Storage | Internal + removable volumes with used/free space |
| Display | Resolution, density, refresh rate + supported rates, HDR formats, wide color |
| Battery | Live level, status, temperature, voltage, current draw, design charge, health |
| System | Android version, security patch, kernel, build ID, bootloader, live uptime |
| Sensors | Every hardware sensor with its vendor |

Live values refresh every 1.5 seconds while the app is on screen.

> **Note on RAM speed/type:** Android has no public API that exposes the
> LPDDR generation or clock speed of the memory chips — that information
> lives in vendor firmware only. The app says so honestly instead of
> guessing.

## Getting the APK

The GitHub Actions workflow in `.github/workflows/build-apk.yml` builds a
debug APK on every push. To download it:

1. Open the repo's **Actions** tab on GitHub.
2. Click the latest **Build Hardware Info APK** run.
3. Download the **HardwareInfo-debug-apk** artifact and unzip it.
4. Copy `app-debug.apk` to your phone and install it (you may need to
   allow "install unknown apps" for your file manager).

## Building locally

With the Android SDK installed:

```sh
cd HardwareInfo
gradle assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK platform 34. No permissions are needed at
runtime — everything the app reads is world-readable system information.
