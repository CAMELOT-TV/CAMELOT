# Troubleshooting

Run `python tools/camelot.py doctor` first. It checks Java, the Android SDK, adb and your config.

## Connecting

**"Can't reach the TV" / connection timed out**
- The TV must be on, not in deep standby, and on the same network as the computer.
- Make sure **USB debugging** (or **Network debugging**) is still on in Developer options. TV updates sometimes switch it off.
- The TV's IP address may have changed. Check it under Settings → Network & Internet, then run `connect` with the new one.
- Some routers' guest networks block devices from talking to each other. Use the main network.

**"unauthorized"**
A prompt is waiting on the TV. Tick **Always allow from this computer**, press **OK**, then run the command again.

### Pairing code TVs

Newer TVs (Android 11+ with **Wireless debugging**) need a one-time pairing:

1. On the TV, open **Developer options → Wireless debugging → Pair device with pairing code**.
2. Run `python tools/camelot.py pair <IP:PORT> <CODE>` with the IP, port and code shown on the TV.
3. Then run `python tools/camelot.py connect <IP:PORT>`, using the port shown on the main Wireless debugging screen. It is not the same as the pairing port.

## Building

**"Java 11 or newer is needed" / Java missing**
Install a JDK 11+ (for example [Temurin 17](https://adoptium.net)) or Android Studio. If it's installed but not found, set `JAVA_HOME` to its folder.

**Build tools or platform missing**
In Android Studio, open **SDK Manager**. Install **Android 11 (API 30)** or newer under SDK Platforms, and **Build-Tools** plus **Platform-Tools** under SDK Tools. If your SDK is somewhere unusual, set `ANDROID_HOME` to it.

**"config/camelot.json is not valid JSON"**
Go to the line number it gives you. The usual causes are a missing comma between items, an extra comma after the last item, or a missing quote.

## Installing

**INSTALL_FAILED_UPDATE_INCOMPATIBLE**
The Camelot already on the TV was signed by a different computer, or the `.camelot/` folder was lost. Run `python tools/camelot.py uninstall` once, then `install` again.

**Not enough space**
Use fewer or smaller background pictures, and install Pillow (`pip install Pillow`) so they're resized automatically.

## On the TV

**Pressing Home still opens the old home screen**
Run `python tools/camelot.py set-home` and agree to turn off the stock home screen. On Google TV that's the only way, because it ignores the default-home setting. A TV update can turn it back on, so just run `set-home` again.

**The screen went black for a moment while installing**
That's normal when Camelot is your only home screen: the TV briefly has no home while it's being replaced. Press **Home** if it doesn't come back on its own.

**An app tile is missing**
With `hideMissingApps` on, apps that aren't installed are left out. Check the package name with `python tools/camelot.py apps`.

**No show rows**
- Rows only appear for apps listed in `showRows` that share shows with the TV. Open the app and watch something for a minute, then go back Home.
- Some apps don't share anything. That's up to the app.
- The permission is given during `install`. If you installed Camelot another way, reinstall it with the helper.

**The Live TV tile opens the wrong input, or says it can't find it**
Run `python tools/camelot.py inputs`, then copy the right id into that tile's `inputId` in `config/camelot.json`.

**The Netflix Top 10 disappeared**
Netflix probably changed its Top 10 page. The row still shows continue-watching. Please open an issue.

## Getting back to normal

```
python tools/camelot.py restore-home     # the home screen you had before Camelot comes back
python tools/camelot.py uninstall        # remove Camelot (restores your previous home screen first)
python tools/camelot.py undo-debloat     # turn back on apps that debloat turned off
```

**If you can't use the helper at all** (for example, the computer is gone):

1. Open **TV Settings**. It's the first row in Camelot's **All Apps** panel.
2. Go to **Apps → See all apps → Show system apps**. Find **Google TV Home** (or **Android TV Home**) and press **Enable**. Do the same for **Setup**, if it's disabled.
3. Only then, uninstall **Camelot** from **Settings → Apps**, if you want to. Don't uninstall it before step 2, or the TV is left with no home screen.

As a last resort, a factory reset restores everything.
