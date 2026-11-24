# Debloat lists

Each `.txt` file here is a list of preinstalled apps that the helper can turn off:

```
python tools/camelot.py debloat google     # uses debloat/google.txt
python tools/camelot.py debloat tcl        # uses debloat/tcl.txt
python tools/camelot.py undo-debloat       # turns everything back on
```

How it works:

- It shows you which apps from the list are on your TV and asks before changing anything.
- Apps are **disabled**, never uninstalled. Nothing is deleted from the TV.
- `undo-debloat` turns every one of them back on, and a factory reset also restores them.

**Don't add** system apps you don't recognise. Some of them handle the remote, voice search, casting, HDMI-CEC, updates or the picture settings. If you're not sure what a package does, leave it on.

## Making a list for your TV brand

Copy `tcl.txt` to a new file, for example `sony.txt`, and write one package per line with a short note after `#`. You can see what's on your TV with `python tools/camelot.py apps`, or with `adb shell pm list packages`.

If the list works well on your TV, please share it with a pull request. Say which model and Android version you tested it on.
