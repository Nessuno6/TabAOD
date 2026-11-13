# TabAOD

An Always On Display for rooted Samsung Galaxy tablets, which don't get One UI's own AOD.
TabAOD replaces the system's doze screen with its own: a clock, your lock screen widgets,
notifications and a Now bar, drawn while the screen is otherwise off.

Developed and tested on a Galaxy Tab S12 Ultra (SM-X940) with One UI 9 and KernelSU.
Other Samsung tablets with an OLED screen may work; nothing here is guaranteed.

## Features

- **Clock styles**, including **Lock screen**, which uses the same face as One UI's lock
  screen clock (read from Samsung's clock pack on the device, not bundled).
- **Lock screen widgets**: copies the widgets on your lock screen, at the same grid positions,
  and keeps them in sync. You can also place your own widgets in the layout editor.
- **Notifications**: app icons, and pop-up cards for new notifications.
- **Now bar**: music that's playing, timers, stopwatches, calls, navigation and progress.
- **When to show**: Always, Auto (hides when face down or in the dark), As scheduled, or
  For new notifications only.
- **Fingerprint icon** over the under-display sensor.
- Dimmed lock screen wallpaper, burn-in protection, cover detection (turns off under a
  Book Cover), a Quick Settings tile, and "Turn AOD on/off" actions for Modes and Routines.
- **Automatic updates** for both the app and the root module (see below).

## Install

Needs root (KernelSU or Magisk).

1. Download `TabAOD_module_vX.Y.zip` from the [latest release](https://github.com/Nessuno6/TabAOD/releases/latest).
2. Install it in the KernelSU or Magisk app (Modules > Install from storage) and reboot.
   The module mounts a small overlay in memory that makes TabAOD the doze service (nothing on
   `/system` is written), installs the app, and grants it what it needs.
3. Open **Tab AOD** to set it up. The Setup card shows whether everything is ready.

## Updates

- **Root module**: `module.prop` points KernelSU / Magisk to [`update.json`](update.json), so
  the manager offers new versions in its module list. The module carries the app, so updating
  the module updates the app too.
- **App**: checks the same `update.json` when you open it and twice a day in the background.
  A new APK is downloaded from the GitHub release, checked against the SHA-256 in
  `update.json`, and installed with root. Turn off **Install updates automatically** (About)
  to only get a notification instead. Android only accepts an update signed with the same
  key as the installed app.

## Build

The build doesn't use Gradle: `aapt2`, `javac`, `d8`, `zipalign` and `apksigner` from the
Android SDK (platform 36, build-tools 36.0.0), run from Git Bash on Windows.

```bash
bash build.sh          # the app       -> build/TabAOD.apk
bash overlay/build.sh  # doze overlay  -> overlay/build/TabAodDozeOverlay.apk
bash module/build.sh   # module zip    -> dist/
bash release.sh        # all of the above, plus update.json
```

`ANDROID_HOME` and `JAVA_HOME` are used when set; otherwise Android Studio's default install
locations. APKs are signed with `KEYSTORE` (default: the Android debug keystore).

`release.sh --publish` also creates the GitHub release for the version in
`AndroidManifest.xml`; then commit and push `update.json`.

## License

[MIT](LICENSE) for TabAOD's own code. The parts listed in [NOTICE](NOTICE) keep their
own licenses: the clock adapted from AOSP (Apache 2.0) and the bundled fonts (SIL OFL 1.1).

## Credits

See [NOTICE](NOTICE). The "AOSP" clock style is adapted from the Android Open Source Project
(Apache 2.0). Fredoka, Big Shoulders Display and Bodoni Moda are bundled under the SIL Open
Font License 1.1. Samsung's and Android's own fonts are read from the device, not bundled.
