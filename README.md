# DroiDS

**DroiDS** is a no-root, Shizuku-powered dual-screen handheld gaming environment for foldable and large-screen Android devices (tested on Samsung Galaxy Z Fold 7 & Galaxy Z Fold 8). It creates two independent hardware-accelerated virtual displays—a **16:9 top screen (`1920×1080`)** and a **bottom-center secondary screen (`1240×1080`, `PRESENTATION`, dynamically resizable)**—paired with customizable multi-touch controls, full physical gamepad passthrough, and a configurable bottom home dashboard.

---

## Why DroiDS?

I've always been a huge fan of the Nintendo DS and 3DS, and seeing dual-screen handhelds like the **AYN Thor** spark a whole wave of native recompilations ("recomps") and emulator mods—even adding a second screen to games that never originally had one—immediately caught my attention. The catch is that most dual-screen apps and mods expect real multi-display hardware; only a handful go out of their way to let you view that second screen on a single display.

**DroiDS bridges that gap.** By spinning up two genuine Android displays on one large foldable screen, it lets you run *any* app, emulator, or mod built for dual-screen handhelds on a single-screen device—no special developer workarounds required.

And let's be honest: if you really wanted an AYN Thor or a similar dual-screen handheld, **but already spent all your money on an expensive foldable phone**, this lets you get the best of both worlds without buying yet another device. Beyond playing games, it's also meant to be a handy **testing tool for developers** building dual-screen Android apps, ports, and mods who don't actually own a dedicated dual-screen handheld yet.

---

## Features (v2.9.6-pre)

* **True Dual Virtual Displays (No Root):** Uses [Shizuku](https://github.com/RikkaApps/Shizuku-API) (`UID 2000` shell service) to create two independent trusted virtual screens so dual-screen emulators and recompilations (SeedlessDS, DraStic, Azahar, melonDS, Cemu, and more) run natively across both displays, or you can multitask with any two Android apps at once.
* **Interactive Touch & Toolbar Layout Editor:** Drag all 10 virtual gamepad controls (`D-Pad`, `L/R` analog sticks with `L3/R3`, `ABXY`, `L1/L2/R1/R2`, `SELECT/START`) and both utility toolbars anywhere on screen (including over the displays), adjust per-item **Size (`60%–150%`)** and **Opacity (`20%–100%`)**, toggle **Grid Snap**, and drag the live **Screen Split** divider—saved independently per **orientation (Landscape / Portrait)** and **control mode** (D-Pad only, Stick only, ABXY only, or Combo).
* **Low-Latency Kernel Gamepad + Physical Controller Support:**
  * Virtual controls register a native kernel Xbox 360 gamepad via `/dev/uinput` for fast, stall-free input with optional haptics, 4-Way D-Pad lock, and **Xbox / Nintendo** face button layouts.
  * Automatic physical controller detection (tested with GameSir) hides on-screen controls, drops sidebar margins for full-width bottom display space, and automatically switches the bottom display between **Thor (`1240:1080`)** for dual-screen emulators (zero stretching) and native full-width **Fill** for browsers, YouTube, and companion apps.
* **Modern Bottom-Anchored Quick Settings & Auto-Updater:** Two-pane translucent Settings sheet with a thumb-reachable left icon rail, swipe-down/tap-top dismissal, live preview, and an **About & Updates** tab featuring GitHub Releases auto/manual update checks with signature-verified in-app APK install, Debug/Crash Logs, links, and credits.
* **Built-In First-Launch Setup Guide:** Interactive Shizuku setup checklist with live status checkmarks, 1-tap shortcuts to **Download / Open Shizuku**, **Developer Options**, and **Battery Settings**, plus offline usage tips.
* **Configurable Bottom Home & Quick Control Center:** Handheld dashboard with toggleable **Clock**, **Live Performance Monitor** (CPU, GPU, RAM, Battery), **Quick Shortcuts**, **Brightness & Volume sliders**, **Wi-Fi / Bluetooth** shortcuts, and an **AMOLED Dim / Sleep Bottom Screen** overlay.
* **Power-User App Drawers, In-Grid Folders & Backups:** In-grid folders with 2×2 mini-icon previews (tap to open, long-press to rename/edit/delete), One UI-style **multi-select**, 1-tap **Auto-Organize into Folders**, live search across all folders, Recently Launched row, Dual-Screen app toggles, **Per-App Layout Profiles**, live **Screen Swap**, quick-return pill for paused dual-screen games, and full **JSON Settings Backup & Restore**.

---

## Permissions & Privacy

Because DroiDS runs a local Shizuku (`UID 2000`) service, it's important to be transparent about what every permission is used for:

* **Shizuku (`UID 2000`):** Used strictly on-device to create the two virtual displays, launch apps onto them, and register the local `/dev/uinput` virtual controller.
* **`INTERNET`:** Used *only* by the optional GitHub Releases update checker (`api.github.com` and `github.com` / `githubusercontent.com` over HTTPS). There is no analytics, telemetry, or third-party tracking. You can disable automatic startup update checks anytime in **Settings → About & Updates**.
* **`REQUEST_INSTALL_PACKAGES`:** Used *only* when you explicitly tap **Download & Install** on an update. Before launching Android's package installer, DroiDS verifies the download came from an HTTPS GitHub host, checks `Content-Length`, and confirms the APK's signing certificate matches the installed app.

---

## Requirements & Setup

1. Download and install the latest **`DroiDS-vX.Y.Z.apk`** from **GitHub Releases**.
2. Open **DroiDS** and follow the built-in **Setup Guide**—it will detect whether **[Shizuku](https://shizuku.rikka.app/)** is installed and give you 1-tap shortcuts to download/open it, jump to **Developer Options**, and grant permission.
3. *(Offline tip)* Wi-Fi is only needed once after a phone reboot to start Shizuku via Wireless Debugging—keep **USB Debugging** enabled and DroiDS will continue working completely offline.
4. Launch any game or emulator on the top screen, or tap **`+`** to launch a companion app on the bottom screen.

### Recommended Emulator Settings
* **SeedlessDS / DraStic / melonDS (Nintendo DS):** Enable external / secondary display output for the Bottom Screen.
* **Azahar (Nintendo 3DS):** `Settings → Layout → Landscape Screen Layout → Single Screen` and `Secondary Display Screen Layout → Bottom Screen`.

---

## Building from Source

Builds automatically via GitHub Actions (`.github/workflows/build-apk.yml`) when a `DroiDS-v*.zip` release archive is pushed to `main` (or triggered manually), using **JDK 17**, **Android SDK 35**, and **Gradle 8.9** and signing release APKs with repository secrets. See [`AI_CONTEXT.md`](AI_CONTEXT.md) for architecture notes and key design decisions.
