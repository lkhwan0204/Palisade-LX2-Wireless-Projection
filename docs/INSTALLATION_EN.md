# Palisade LX2 Wireless Apple CarPlay / Android Auto

[한국어](../README.md) | **English**

## Download — experimental pre-release

[Download experimental ZIP](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/wireless-carplay-aa-lx2dev-2.1.4-lx2dev.12-noaa-tbt.1.zip) · [SHA256SUMS](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/SHA256SUMS) · [Pre-release notes](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/tag/v2.1.4-lx2dev.12-noaa-tbt.1)

SHA-256: `ff99564ae90cb650beda6581b270893ff3d370d48cd2131619f24f9371b36749`

> ⚠️ **This experimental build has not been tested on a vehicle; installation is not recommended.** The exact tested HMG-API build and LX2 hook map, removal and recovery path have not been established. A download does not prove safe installation or working functionality.
>
> The released `2.1.4-lx2dev.12-noaa-tbt.1` is **different** from the reported vehicle-tested original `2.1.3-lx2dev.12`. Android Auto cluster/HUD turn guidance is excluded, and basic CarPlay and Android Auto connectivity are also unverified in this experimental build. Installing it over 2.1.3 raises the version and an ordinary downgrade to an older ZIP may be rejected.

> ⚠️ **Unofficial experimental project — read before installation**
>
> This is an independent project, not affiliated with, endorsed by, or certified by Hyundai Motor Company, Kia, or any other vehicle manufacturer. Installation and use are voluntary and at the user's own risk.
>
> Possible effects include infotainment malfunction, freezes or black screens, boot failure, data loss, battery discharge, vehicle function problems, software update conflicts, and effects on warranty or service. The outcome depends on the vehicle and circumstances.
>
> The software is provided **AS IS**, without any express or implied warranty, including merchantability, fitness for a particular purpose, or error-free operation. To the maximum extent permitted by law, developers and contributors disclaim liability for damages arising from installation or use. Nothing here excludes rights or liabilities that cannot lawfully be excluded.
>
> **Do not install, operate, or test while driving.** Park safely, provide suitable power and ventilation, back up needed data, and establish a recovery method before starting.

[한국어](../README.md) · [Compatibility](COMPATIBILITY.md) · [Test results](TEST_RESULTS.md) · [Troubleshooting](TROUBLESHOOTING.md)

## Current status — experimental release and original

The experimental ZIP and checksum above are available as a pre-release. The original `wireless-carplay-aa-lx2dev-2.1.3-lx2dev.12.zip`, for which the vehicle success was reported, remains withheld and is different from the released candidate. For the experimental source and notices see [distribution](DISTRIBUTION.md). **Installation is not recommended until vehicle behavior and a recovery path are verified.**

## Reported vehicle test

The owner reports a limited test on one vehicle. The original development README described the build as untested on a car when created; the results below were subsequently reported by the owner. This documentation task did not independently test the car or verify the later results from logs.

| Item | Reported test |
|---|---|
| Vehicle | 2021 Hyundai Palisade LX2, Korea, Gen5W, Android 4.4.2 |
| Firmware | `LX2.KOR.0000.V039.001.260902` |
| HMG-API | `1.0.0-beta.6` |
| NEMESIS | Earlier record says 1.15; **final version at test time is unknown** |
| Mod | `2.1.3-lx2dev.12` |
| Result | Wireless Apple CarPlay and Android Auto connected; maps, music and touch worked |

On switching from Android Auto to CarPlay, the screen was initially black; reconnecting Bluetooth and re-entering the projection screen restored it in the reported test. Cause and reproduction conditions are unknown. Long-duration stability, other cars, firmware and phones, and combinations with current official NEMESIS or HMG-API are untested. One successful test does not guarantee compatibility or safety.

**Version choice:** beta.6 is the reported test version, not a recommended or supplied installer. The current official HMG-API NEMESIS Package Store route requires NEMESIS **1.16 or later**. That current requirement does not establish the tested car's final NEMESIS version or validate a new version combination. Stop before a new installation until the exact combination, LX2 hook map and recovery path are verified.

[English detailed installation guide](INSTALLATION_EN.md) · [한국어 설치 안내](INSTALLATION_KO.md)

## Installation guide: NEMESIS → HMG-API → LX2 mod

**This explains documented procedures. The experimental ZIP has not been tested in a vehicle, and an exactly compatible HMG-API build, reproducible hook-map installation and recovery path are unverified; installation is not recommended.** Menus and routes can change by version.

### 1. Check the car and prepare

1. In the car's General > Software information, check model, region and exact firmware against the table above. Stop if different; compatibility is unknown.
2. Read the official [NEMESIS compatibility check](https://github.com/xwtk/hmg_nemesis#compatibility-check). It requires a compatible Android Gen5W `S5W_M` and actual OTA update support. Seeing an Update OTA menu alone does not prove OTA support; the official project notes that some cars meeting these conditions may still be incompatible.
3. Prepare a computer, empty USB drive, suitable power, backups and a recovery method. Formatting erases the USB. Stop if compatibility or recovery is unclear.

### USB layouts at a glance

The three stages use different USB layouts. If reusing a stick, clear the previous stage's files and follow the current official instructions. `USB/` means the drive root.

**NEMESIS initial install** — copy the tar intact:

```text
USB/
└── ota_package.tar
```

**HMG-API NEMESIS Package Store route** — extract the relevant ZIP to the root:

```text
USB/
├── NEMESIS.data
├── packages/
│   └── … official package files …
└── README …
```

**Mod installation** — copy the experimental ZIP intact (installation not recommended):

```text
USB/
└── HMGAPI/
    └── updates/
        └── wireless-carplay-aa-lx2dev-2.1.4-lx2dev.12-noaa-tbt.1.zip
```

The **direct HMG-API USB route** uses the separate `hmgapi-<version>-usb.zip`; extract it to the USB root according to that version's included README. Do not assume it uses the NEMESIS package layout.

### 2. Download and install official NEMESIS

1. Read the [official NEMESIS repository](https://github.com/xwtk/hmg_nemesis) and [detailed installation guide](https://github.com/xwtk/hmg_nemesis/blob/main/03-Install-and-Uninstall.md); download the official `ota_package.tar` linked by its README.
2. Format an empty USB as FAT32 or exFAT. Copy the tar file to the USB root **without extracting or repacking it**.
3. While parked, insert it in the head unit USB port. Follow the device-specific official instructions to enter Engineering Mode > USB Copy > Update DSP > Update OTA. Access varies by unit: do not guess passwords or codes. A general ‘No Supported Files’ USB message may be expected.
4. Wait for installation and head-unit restart according to the official instructions. Afterwards check that NEMESIS Manager opens and check its version. If progress is not confirmed, an error appears, or the unit repeatedly restarts, stop and consult official troubleshooting rather than retrying blindly.
5. Current official documentation describes opening Manager by pressing the configured physical button three times. A new install defaults to Navigation, but the setting and behavior may differ by version. Consult the [official Manager guide](https://github.com/xwtk/hmg_nemesis/blob/main/02-Debugging-Execution-Interface.md); do not guess if the installed version or configured button is unknown. Check the version shown by Manager or the instructions for that version.
6. The current official README says that 1.15 and earlier use the initial installation procedure to update to 1.16; 1.16 and later have a separate update method. See the [current update instructions](https://github.com/xwtk/hmg_nemesis#updating-nemesis). **Updating does not prove compatibility with this project.**

### 3. Install official HMG-API and check hooks

Check conflicting packages first. The current official guide lists HMG Leafia Plus (`wireless.carplay.aa`), `carplay.session` and `voicehandler.vr-gates` as conflicts with HMG-API; `wireless.projection` and `projection.tbt` conflict with the wireless projection mod. Preserve settings and a recovery plan and consult the [official conflict list](https://github.com/VysseCodes/HMG-API/wiki/Installing-HMG-API).

- **NEMESIS 1.16 or newer:** obtain the target version's `hmgapi-<version>-nemesis.zip` from [official HMG-API Releases](https://github.com/VysseCodes/HMG-API/releases). Follow the [official NEMESIS route](https://github.com/VysseCodes/HMG-API/wiki/Installing-HMG-API#nemesis): extract it to the root of an empty FAT32/exFAT USB so `NEMESIS.data`, `packages/` and README are at the top level. Insert after Android starts, install HMG-API in Manager's Package Store, restart when instructed and remove the USB.
- **When Package Store is unavailable, such as NEMESIS 1.15:** the current official [USB route](https://github.com/VysseCodes/HMG-API/wiki/Installing-HMG-API#usb-stick) uses a separate `hmgapi-<version>-usb.zip`, extracted to an empty FAT32 USB root, then Engineering Mode > USB Copy > DSP > Update OTA. Wait for the installation and restart messages, remove the USB, and check `HMGAPI/install.log`. The official guide says OTA error 684 afterwards can be expected. **This route and current HMG-API with lx2dev.12 have not been tested on this project car.** Do not continue an unverified installation.

Open HMG-API from the app menu. If its tile is absent, the current official guide offers NEMESIS Manager > My Packages > HMG-API > Launch. Check the top bar for **Hooks on** and the map-check status. Stop if hooks are off, the map is unchecked or unsupported, Boot Guard is active, or an error appears. Hooks on does not guarantee all features work. The exact test map and compatibility with the current official version are not established. Do not force installation or bypass map checks.

### 4. Install the LX2 mod — only after vehicle validation and a recovery path

1. Download the experimental ZIP above and verify it against [SHA256SUMS](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/SHA256SUMS). **It is downloadable, but installation is not recommended because vehicle behavior and recovery are unverified.**
2. Create `HMGAPI/updates/` at the USB root and copy the ZIP there without extracting or repacking it, so HMG-API can check the author signature and package integrity.
3. Open HMG-API > **Check USB updates**, select the mod, and inspect author, signature, version and vehicle checks. Stop on an untrusted author or compatibility rejection.
4. Return to the main screen, tap **Apply changes**, and review the plan and errors. Restart as instructed and remove the USB.
5. In **Mods**, confirm the mod appears and is enabled. The current official app guide describes green as running, yellow as conflict, red as not running or error, and grey as off. Labels may differ by version. Check any ‘Changes not applied’ or ‘Restart needed’ status. See the [official mod guide](https://github.com/VysseCodes/HMG-API/wiki/Installing-and-removing-mods).
6. While parked, enable Bluetooth and Wi-Fi on the iPhone, pair in the car's phone/projection settings and accept CarPlay prompts. For Android, enable Bluetooth and Wi-Fi and follow the car's Android Auto pairing prompts. A USB stick or wired phone can interfere with wireless connection. Check screen, audio and touch while parked.

### 5. First phone connection — after vehicle compatibility and recovery validation

**iPhone**

1. While parked, enable Wi-Fi and Bluetooth on the iPhone. Check any settings you need before deleting an existing pairing.
2. In the car's phone or device connection settings, pair the iPhone and check that the confirmation numbers match.
3. Read and accept the CarPlay prompt when shown. Wording and order vary with device and iOS version.
4. Open CarPlay in the car and check maps, music and touch. Remove the installation USB; wired connections can interfere with wireless projection.

**Android phone**

1. While parked, enable Wi-Fi and Bluetooth and check that Android Auto is available on the phone.
2. Pair in the car's phone or device connection settings and verify the confirmation numbers on both screens.
3. Read and accept the Android Auto and permissions prompts when shown. Names and screens vary by device and version.
4. Open Android Auto in the car and check maps, music and touch. These are general steps, not a verified exact screen sequence for every phone.

Seeing the mod installed is a separate check from confirming that wireless connection, video and audio work. If a check fails, consult troubleshooting before retrying.

### 6. Problems and recovery

If a black screen occurs, check the connection and screen while parked. Bluetooth reconnection and screen re-entry helped in the reported test; this does not guarantee recovery in other circumstances. In HMG-API select the mod, switch it off or select **Remove** and confirm, then **Apply changes** and restart as instructed. Removal and recovery were not independently validated on this vehicle. If the unit repeatedly restarts or the app cannot open, stop and consult the [official troubleshooting](https://github.com/VysseCodes/HMG-API/wiki/Troubleshooting) and [uninstall guide](https://github.com/VysseCodes/HMG-API/wiki/Uninstalling) for the installed version. Do not guess reset commands or force a downgrade.

Before sharing logs, redact VIN, serials, telephone numbers, MAC addresses, SSIDs, location, keys and passwords. See [troubleshooting](TROUBLESHOOTING.md).
