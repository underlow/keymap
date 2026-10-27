# KeyMap

An Android TV accessibility service that remaps remote buttons. It has no UI and no launcher icon.

A button listed in the config runs its action instead of its normal one. Every other button works
as usual. Actions:

- open an app;
- toggle mute;
- pause or resume video with one press (see [Plex](#plex-pause-with-one-press)).

## Install

The TV must have network debugging (adb) enabled.

Download `keymap-<version>.apk` from [Releases](../../releases) and install it:

```bash
adb connect <tv-ip>:5555
adb install -r keymap-<version>.apk
```

Or build it yourself (JDK 17 and the Android SDK) and install
`app/build/outputs/apk/debug/app-debug.apk`:

```bash
./gradlew assembleDebug
```

The APK is a debug build on purpose: writing the config needs `adb shell run-as`.

Enable the service. Append it to the services already enabled, do not replace them:

```bash
CUR=$(adb shell settings get secure enabled_accessibility_services)
adb shell settings put secure enabled_accessibility_services "${CUR}:com.mikhailov.keymap/.KeyMapService"
adb shell settings put secure accessibility_enabled 1
```

For `play-pause-if-idle`, also allow it to read media sessions:

```bash
adb shell cmd notification allow_listener com.mikhailov.keymap/com.mikhailov.keymap.MediaAccess
```

If the service does not start after an install, the firmware may block apps from starting in the
background. Allow it, then disable and enable the service again:

```bash
adb shell cmd appops set com.mikhailov.keymap APP_AUTO_START allow
```

## Config

The config is a JSON file in the app's data dir. Keys are `KEYCODE_*` names or numeric keycodes.
Values are one of:

- `pkg/activity`: open that activity;
- `pkg`: open the app;
- `mute`;
- `play-pause-if-idle:<pkg>`.

```json
{
  "KEYCODE_BUTTON_3": "org.smarttube.beta/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity",
  "KEYCODE_MENU": "mute",
  "KEYCODE_DPAD_CENTER": "play-pause-if-idle:com.plexapp.android"
}
```

To find a button's keycode, check the remote's `.kl` file listed in `adb shell dumpsys input`.

Write the config to the TV, then disable and enable the service so it reads the file:

```bash
adb shell 'run-as com.mikhailov.keymap sh -c "cat > files/keymap.json"' < keymap.json
```

Without a config file the service writes a default one on start (`DEFAULT_CONFIG` in
`KeyMapService.kt`).

## Plex: pause with one press

In the Plex player the center button needs two presses to pause or resume: the first only shows
the controls. Add this to the config and allow media session access (see Install):

```json
"KEYCODE_DPAD_CENTER": "play-pause-if-idle:com.plexapp.android"
```

One press then pauses or resumes. KeyMap acts only when Plex is on screen with a video playing or
paused and no button was pressed for 4.5 s, so the controls are hidden. With the controls on screen
and in menus the button works as usual.
