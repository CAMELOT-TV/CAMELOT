# Instructions for AI assistants

You are helping someone set up or customize **Camelot**, a home screen (launcher) for Android TV and Google TV, on **their own TV** from their computer. Everything goes through one helper script, `tools/camelot.py`, run from the repository root. It works the same on Windows, macOS and Linux. On Windows, use `py` if `python` isn't found.

## Ground rules

1. **Ask before changing anything on the TV.** Reading is fine: `doctor`, `info`, `apps`, `inputs`, `screenshot`. Anything that changes the TV needs the person's clear "yes" in the chat first: `install`, `set-home`, `debloat`, `restore-home`, `uninstall`, `undo-debloat`. Before you ask, say what will change and how to undo it.
2. **Use `--yes` only after the person has agreed** to that exact change in this conversation. Never use it to skip a question they haven't answered.
3. **Never delete anything from the TV.** Don't run `pm uninstall`, `pm clear` or `rm`, don't factory reset, and don't change system settings outside what the helper does. Turning apps off goes through `debloat`, which disables apps (reversible) and records what it changed.
4. **Only turn off apps from the lists in `debloat/`,** or ones the person names after you explain what each does. If you don't know what a package does, say so and leave it on. Remote control, voice, casting, HDMI-CEC, updates, picture/sound settings and the keyboard must stay on.
5. **Don't interrupt someone using the TV.** Before taking a screenshot or sending a key press, ask whether they're watching something. Installing Camelot while it's the home screen briefly blanks the screen.
6. **Keep secrets and personal details out of git.** `.camelot/` holds the TV address and the signing key, and `build/` and `*.apk` are build output. All of these are gitignored, so never force-add them. Don't put IP addresses, names or anything personal into committed files, issues or pull requests.
7. **Use only pictures the person has the rights to** in `backgrounds/`. Their own photos and properly licensed images are fine.

## Setup flow

1. `python tools/camelot.py doctor`. If anything is MISSING, walk the person through installing it, using the hints it prints.
2. **TV side:** if they haven't already, explain how to turn on Developer options and USB/Network debugging, and how to find the TV's IP. These steps are in README section 1. Newer TVs with only "Wireless debugging" need `pair` first (see docs/TROUBLESHOOTING.md).
3. `python tools/camelot.py connect <IP>`. If it says unauthorized, ask them to accept the prompt on the TV, then retry.
4. `python tools/camelot.py info` to learn the model, Android version and whether it's Google TV.
5. `python tools/camelot.py apps`. Identify the apps by package name, then **ask which apps they want on the home screen and in what order**. Also ask which apps' shows they want as rows, and which apps to hide. Then edit `config/camelot.json`: `homeApps`, `showRows`, `hiddenApps`.
6. Ask whether anything is plugged into HDMI that they want a Live TV tile for, such as a set-top box or console, and on which port. Add it to `liveInputs` as `{ "name": "...", "hdmiPort": N }`. Use `python tools/camelot.py inputs` only if the port number alone doesn't work.
7. Ask about looks: accent colour, title, background pictures, speed. All the options are in docs/CUSTOMIZE.md.
8. `python tools/camelot.py build`, then, **after asking**, `install`.
9. **After asking**, `set-home`. It first remembers the current home screen, which may be the stock one or another launcher the person already uses, so `restore-home` can return to it. On Google TV this disables the stock home screen and the setup app, because that's the only way to stay on Camelot. Explain that this is reversible with `restore-home`, and that a TV update may undo it.
10. Optionally offer `debloat` with the right list(s). Show the list first and get a yes.
11. Ask the person to press Home and confirm it looks right. If they agree, `screenshot` lets you check it yourself.

## Customization requests

Most requests map to `config/camelot.json`. See docs/CUSTOMIZE.md for every setting. After editing, run `build` and then `install` (after asking). For changes that need code:

- The app is plain Java (source/target 8, lambdas are fine) in `launcher/src/com/camelot/launcher/`, built without Gradle by `tools/camelot.py build` (aapt2 → javac → d8 → zipalign → apksigner).
- `MainActivity.java` holds the layout, tiles, animations, D-pad handling (`dispatchKeyEvent`) and the All Apps panel. `Feed.java` reads the show rows. `Config.java` parses settings, so add new options there with a sensible default.
- Keep remote-control navigation predictable. Test every arrow direction from every edge: first and last tile, the tabs, the All Apps pill, Live TV tiles and show rows.
- `minSdk` is 23. Don't use APIs above that without a version check.
- Run `build` after every change. If it fails, read the error and fix it before going on.

## Undo

- `restore-home`: the home screen used before Camelot comes back, whether that was the stock one or another launcher. Only apps Camelot turned off are turned back on.
- `uninstall`: restores the home screen first, then removes Camelot.
- `undo-debloat`: turns back on everything that `debloat` turned off on this TV.
