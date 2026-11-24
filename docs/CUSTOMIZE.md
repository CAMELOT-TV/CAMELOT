# Customizing Camelot

All settings live in **`config/camelot.json`**. After any change, rebuild and reinstall:

```
python tools/camelot.py build
python tools/camelot.py install
```

The file is JSON, so watch the commas: there's one between items and none after the last item in a list. If something's off, `build` tells you the line number.

---

## Look

| Setting | Default | What it does |
|---|---|---|
| `title` | `"CAMELOT"` | The name in the top-left corner. Any text works, for example `"THE GRAND HALL"`. |
| `showCrown` | `true` | The little crown before the title. `false` hides it. |
| `accentColor` | `"#D4AF37"` | The colour of the title, clock, focus frame, tabs and name line. Some ideas: silver `#C0C7D0`, rose gold `#E8B4A0`, emerald `#3FBF8A`, sky `#5AB0FF`, crimson `#E0475B`. |
| `columns` | `4` | Tiles per row, from 2 to 6. |
| `glassDocks` | `false` | `true` puts a clear glass panel behind each row of tiles. |

## Home screen apps

```json
"homeApps": [
  { "name": "YouTube", "packages": ["com.google.android.youtube.tv", "com.google.android.youtube.googletv"], "match": "youtube" },
  { "name": "Netflix", "packages": ["com.netflix.ninja"] }
]
```

- **`name`**: shown on the name line when the tile is selected.
- **`packages`**: the app's package name. Some apps have a different package on different TVs, so you can list several and the first one that's installed is used. Run `python tools/camelot.py apps` to see what's on your TV.
- **`match`** *(optional)*: if none of the packages is installed, Camelot looks for an app whose name contains this text.
- The order in the list is the order on screen, left to right and top to bottom.

| Setting | Default | What it does |
|---|---|---|
| `hideMissingApps` | `true` | Leaves out apps that aren't installed. `false` shows a "Not installed" tile for them instead. |

Each tile shows the app's own TV banner. Apps without a banner (phone apps) show their icon.

## Show rows

```json
"showRows": [
  { "name": "Prime Video", "packages": ["com.amazon.amazonvideo.livingroom"] },
  { "name": "Disney+",     "packages": ["com.disney.disneyplus"] }
]
```

Each row collects the shows that the app shares with the TV: continue watching, "up next" and its own channels. The rows appear in the order listed. An app that shares nothing gets no row, which is normal, because not every app supports this.

| Setting | Default | What it does |
|---|---|---|
| `maxShowsPerRow` | `25` | The longest a row can get. |

### Netflix Top 10

Netflix only shares "continue watching". To add the Top 10 list from Netflix's public Top 10 page to the Netflix row:

```json
"netflixTop10Country": "united-states"
```

Use `"global"` for the worldwide list, or your country as it appears in the Top 10 page address, for example `"india"`, `"united-kingdom"` or `"brazil"`. Leave it as `""` to turn it off. This reads a public web page, so it can stop working if Netflix changes that page. If it does, the row just shows continue-watching.

## Live TV page

```json
"liveInputs": [
  { "name": "Set-top box", "hdmiPort": 2 },
  { "name": "PlayStation", "hdmiPort": 1, "subtitle": "HDMI 1 · Game" }
]
```

Each entry adds a tile to the **LIVE TV** page that switches the TV to that input. With an empty list, there is no Live TV page and the APPS · LIVE TV tabs are hidden, so the home screen shows just your apps.

- **`hdmiPort`**: the port number printed on the back of the TV.
- **`subtitle`** *(optional)*: the small line under the name. It defaults to "HDMI n".
- **`inputId`** *(optional)*: if the tile opens the wrong input, run `python tools/camelot.py inputs` and copy the exact id of the right one here.

## All Apps panel

```json
"hiddenApps": [ "org.chromium.webview_shell", "TV Channel" ]
```

Apps listed here stay installed but don't appear in the **All Apps** panel. You can use a package name or the app's name as the TV shows it. Apps that are already on the home screen are never repeated in the panel.

## Backgrounds

Put pictures in the **`backgrounds/`** folder. Every picture there is included, in file-name order.

| Setting | Default | What it does |
|---|---|---|
| `backgroundSeconds` | `9` | How long each picture stays before the next one fades in. The minimum is 3. |
| `backgroundZoom` | `1.18` | How far each picture slowly zooms in. `1.0` means no zoom, and the maximum is `1.5`. |
| `backgroundDim` | `1.0` | The dark shade over the pictures that keeps text readable. `0` means no shade, `1` is the default. |

With no pictures, the background is plain black. With one picture, it slowly drifts in and out.

---

## Bigger changes

The app is plain Java in `launcher/src/com/camelot/launcher/`, with no Gradle or Android Studio project needed. `tools/camelot.py build` compiles it with the Android SDK directly.

| File | What's in it |
|---|---|
| `MainActivity.java` | The whole screen: layout, tiles, animations, remote-control keys and the All Apps panel |
| `Feed.java` | Reads the show rows from the TV, plus the optional Netflix Top 10 |
| `Config.java` | Reads `camelot.json`. Add new settings here. |

Some easy tweaks in `MainActivity.java`:

- tile size: `TILE_W`, `TILE_H` (in dp)
- show picture height: `THUMB_H`
- All Apps panel width: `PANEL_W`
- clock format: `clock.setFormat12Hour(...)` and `clock.setFormat24Hour(...)`

An AI assistant can make these changes for you. See [PROMPT.md](../PROMPT.md).
