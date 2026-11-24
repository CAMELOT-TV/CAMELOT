#!/usr/bin/env python3
"""
Camelot helper: build Camelot and set it up on an Android TV / Google TV from your computer.
Works on Windows, macOS and Linux. Needs Python 3.8+, a JDK (11 or newer) and the Android SDK.

    python tools/camelot.py doctor              check that everything needed is installed
    python tools/camelot.py connect <TV-IP>     connect to the TV over your network (remembered)
    python tools/camelot.py pair <IP:PORT> <CODE>   pair first, for TVs that show a pairing code
    python tools/camelot.py info                TV model, Android version, current home screen
    python tools/camelot.py apps                apps on the TV (package names for the config)
    python tools/camelot.py inputs              the TV's HDMI inputs (for Live TV tiles)
    python tools/camelot.py build               build Camelot.apk from your config and backgrounds
    python tools/camelot.py install             install Camelot on the TV and open it
    python tools/camelot.py set-home            make Camelot the home screen (asks first)
    python tools/camelot.py setup               build + install + set-home in one go
    python tools/camelot.py restore-home        go back to the home screen you had before Camelot
    python tools/camelot.py uninstall           go back to your previous home screen, then remove Camelot
    python tools/camelot.py debloat <list>      turn off the apps in debloat/<list>.txt (asks first)
    python tools/camelot.py undo-debloat        turn back on everything debloat turned off
    python tools/camelot.py screenshot [file]   save a screenshot of the TV

Options: --tv <IP[:PORT]> picks a TV for one command; --yes answers "yes" to confirmations
(only use it when you have already agreed to the change).

Nothing here deletes anything from the TV: apps are disabled, never uninstalled, and every
change can be undone with restore-home / undo-debloat (or a factory reset).
"""

import argparse
import json
import os
import re
import secrets
import shutil
import struct
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAUNCHER = ROOT / "launcher"
CONFIG = ROOT / "config" / "camelot.json"
BACKGROUNDS = ROOT / "backgrounds"
DEBLOAT = ROOT / "debloat"
BUILD = ROOT / "build"
LOCAL = ROOT / ".camelot"            # your own machine's files: TV address, signing key. Never commit this.
APK = BUILD / "Camelot.apk"

PKG = "com.camelot.launcher"
ACTIVITY = PKG + "/.MainActivity"
MIN_SDK, TARGET_SDK = 23, 30
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}

# Stock home screens. Google TV keeps returning to its own home unless these are disabled.
STOCK_HOMES = {
    "com.google.android.apps.tv.launcherx": "Google TV home screen",
    "com.google.android.tungsten.setupwraith": "Google TV setup/recovery screen (takes over Home when the home screen is off)",
    "com.google.android.tvlauncher": "Android TV home screen",
    "com.google.android.leanbacklauncher": "Older Android TV home screen",
}

ASSUME_YES = False
TV_ARG = None


# ---------------------------------------------------------------- output helpers

def say(msg=""):
    print(msg, flush=True)


def fail(msg, code=1):
    print("\nERROR: " + msg, file=sys.stderr, flush=True)
    sys.exit(code)


def confirm(question):
    if ASSUME_YES:
        say(question + " [y/N] y  (--yes)")
        return True
    try:
        return input(question + " [y/N] ").strip().lower() in ("y", "yes")
    except EOFError:
        return False


def run(cmd, check=True, capture=True, env=None, timeout=600):
    """Runs a command (a list, never through a shell) and returns its output as text."""
    try:
        p = subprocess.run([str(c) for c in cmd], capture_output=capture, text=True, encoding="utf-8",
                           errors="replace", env=env, timeout=timeout)
    except FileNotFoundError:
        fail("Could not run " + str(cmd[0]))
    except subprocess.TimeoutExpired:
        fail("This took too long and was stopped: " + " ".join(str(c) for c in cmd[:3]) + " ...")
    out = ((p.stdout or "") + (p.stderr or "")) if capture else ""
    if check and p.returncode != 0:
        fail("Command failed: " + " ".join(str(c) for c in cmd[:4]) + " ...\n" + out.strip())
    return out


def load_local():
    try:
        return json.loads((LOCAL / "local.json").read_text("utf-8"))
    except Exception:
        return {}


def save_local(data):
    LOCAL.mkdir(exist_ok=True)
    (LOCAL / "local.json").write_text(json.dumps(data, indent=2), "utf-8")
    gi = LOCAL / ".gitignore"
    if not gi.exists():
        gi.write_text("*\n", "utf-8")     # belt and braces: this folder never goes to git


# ---------------------------------------------------------------- finding the tools

def exe(name):
    return name + (".exe" if os.name == "nt" else "")


def sdk_root():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        p = os.environ.get(env)
        if p and Path(p).is_dir():
            return Path(p)
    home = Path.home()
    if os.name == "nt":
        cands = [Path(os.environ.get("LOCALAPPDATA", home / "AppData" / "Local")) / "Android" / "Sdk"]
    elif sys.platform == "darwin":
        cands = [home / "Library" / "Android" / "sdk"]
    else:
        cands = [home / "Android" / "Sdk", Path("/usr/lib/android-sdk"), Path("/opt/android-sdk")]
    for c in cands:
        if c.is_dir():
            return c
    return None


def version_key(name):
    return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", name)]


def build_tools(sdk):
    """Newest build-tools folder that has aapt2, zipalign, d8 and apksigner."""
    bt = sdk / "build-tools" if sdk else None
    if not bt or not bt.is_dir():
        return None
    for d in sorted(bt.iterdir(), key=lambda p: version_key(p.name), reverse=True):
        if ((d / exe("aapt2")).exists() and (d / exe("zipalign")).exists()
                and (d / "lib" / "d8.jar").exists() and (d / "lib" / "apksigner.jar").exists()):
            return d
    return None


def android_jar(sdk):
    """Newest installed platform (API 30 or newer)."""
    pf = sdk / "platforms" if sdk else None
    if not pf or not pf.is_dir():
        return None
    best = None
    for d in pf.iterdir():
        m = re.match(r"android-(\d+)", d.name)
        if m and int(m.group(1)) >= 30 and (d / "android.jar").exists():
            if best is None or int(m.group(1)) > best[0]:
                best = (int(m.group(1)), d / "android.jar")
    return best[1] if best else None


def adb_path():
    sdk = sdk_root()
    if sdk and (sdk / "platform-tools" / exe("adb")).exists():
        return str(sdk / "platform-tools" / exe("adb"))
    return shutil.which("adb")


def java_tool(name):
    homes = [os.environ.get("JAVA_HOME")]
    if os.name == "nt":
        pf = os.environ.get("ProgramFiles", r"C:\Program Files")
        homes += [pf + r"\Android\Android Studio\jbr", pf + r"\Android\Android Studio\jre"]
    elif sys.platform == "darwin":
        homes += ["/Applications/Android Studio.app/Contents/jbr/Contents/Home",
                  "/Applications/Android Studio.app/Contents/jre/Contents/Home"]
    else:
        homes += [str(Path.home() / "android-studio" / "jbr"), "/opt/android-studio/jbr", "/snap/android-studio/current/jbr"]
    for h in homes:
        if h and (Path(h) / "bin" / exe(name)).exists():
            return str(Path(h) / "bin" / exe(name))
    found = shutil.which(name)
    if found or name == "java":
        return found
    # Some installers only put small java/javac launchers on PATH (e.g. Oracle's "javapath" on Windows).
    # Ask java where its real home is and look for the tool there (keytool lives only in the JDK's bin).
    java = shutil.which("java")
    if java:
        out = run([java, "-XshowSettings:properties", "-version"], check=False)
        m = re.search(r"java\.home = (.+)", out)
        if m:
            home = Path(m.group(1).strip())
            for cand in (home / "bin" / exe(name), home.parent / "bin" / exe(name)):
                if cand.exists():
                    return str(cand)
    return None


def java_version(java):
    out = run([java, "-version"], check=False)
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not m:
        return 0
    major = int(m.group(1))
    return int(m.group(2) or 0) if major == 1 else major


# ---------------------------------------------------------------- doctor

def cmd_doctor(_):
    ok = True
    say("Camelot doctor\n")
    say("  Python      %s" % sys.version.split()[0])
    java, javac = java_tool("java"), java_tool("javac")
    jv = java_version(java) if java else 0
    if java and javac and jv >= 11:
        say("  Java        OK (version %d)  %s" % (jv, javac))
    else:
        ok = False
        say("  Java        MISSING: install a JDK 11 or newer (e.g. Eclipse Temurin 17 from adoptium.net),")
        say("              or Android Studio, which includes one. If it's installed, set JAVA_HOME.")
    sdk = sdk_root()
    if sdk:
        say("  Android SDK %s" % sdk)
    else:
        ok = False
        say("  Android SDK MISSING: install Android Studio (or the command-line tools) and its SDK,")
        say("              or set ANDROID_HOME to your SDK folder.")
    bt = build_tools(sdk)
    if bt:
        say("  Build tools OK (%s)" % bt.name)
    elif sdk:
        ok = False
        say("  Build tools MISSING: in Android Studio > SDK Manager > SDK Tools, tick 'Android SDK Build-Tools'.")
    aj = android_jar(sdk)
    if aj:
        say("  Platform    OK (%s)" % aj.parent.name)
    elif sdk:
        ok = False
        say("  Platform    MISSING: in SDK Manager > SDK Platforms, install Android 11 (API 30) or newer.")
    adb = adb_path()
    if adb:
        say("  adb         OK  %s" % adb)
    else:
        ok = False
        say("  adb         MISSING: install 'Android SDK Platform-Tools' from the SDK Manager.")
    try:
        import PIL  # noqa: F401
        say("  Pillow      OK (backgrounds are resized to 1920x1080 automatically)")
    except ImportError:
        say("  Pillow      not installed (optional): pip install Pillow  to auto-resize backgrounds")
    try:
        cfg = load_config()
        say("  Config      OK: %d apps, %d show rows, %d live inputs"
            % (len(cfg.get("homeApps", [])), len(cfg.get("showRows", [])), len(cfg.get("liveInputs", []))))
    except SystemExit:
        ok = False
    bgs = background_files()
    say("  Backgrounds %d picture(s) in backgrounds/" % len(bgs))
    say("\n" + ("Everything needed is ready." if ok else "Fix the MISSING items above, then run doctor again."))
    return 0 if ok else 1


# ---------------------------------------------------------------- config + backgrounds

def load_config():
    if not CONFIG.exists():
        fail("config/camelot.json is missing.")
    try:
        cfg = json.loads(CONFIG.read_text("utf-8"))
    except json.JSONDecodeError as e:
        fail("config/camelot.json is not valid JSON (line %d, column %d): %s\n"
             "Tip: check for a missing comma or an extra comma before ] or }." % (e.lineno, e.colno, e.msg))
    if not isinstance(cfg, dict):
        fail("config/camelot.json must be a JSON object { ... }.")
    problems = []
    for i, a in enumerate(cfg.get("homeApps", [])):
        if not isinstance(a, dict) or not a.get("name"):
            problems.append("homeApps[%d] needs a \"name\"." % i)
    for i, r in enumerate(cfg.get("showRows", [])):
        if not isinstance(r, dict) or not (r.get("packages") or r.get("package")):
            problems.append("showRows[%d] needs \"packages\"." % i)
    for i, l in enumerate(cfg.get("liveInputs", [])):
        if not isinstance(l, dict) or not (l.get("hdmiPort") or l.get("inputId")):
            problems.append("liveInputs[%d] needs \"hdmiPort\" (or \"inputId\")." % i)
    color = cfg.get("accentColor", "#D4AF37")
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", str(color)):
        problems.append("accentColor must look like \"#D4AF37\".")
    if problems:
        fail("config/camelot.json:\n  " + "\n  ".join(problems))
    return cfg


def background_files():
    if not BACKGROUNDS.is_dir():
        return []
    return sorted(p for p in BACKGROUNDS.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_EXT)


def prepare_backgrounds(dest):
    """Copies backgrounds into the app. With Pillow, each is cropped to 16:9 and saved at 1920x1080."""
    dest.mkdir(parents=True, exist_ok=True)
    files = background_files()
    if not files:
        say("  No pictures in backgrounds/: the home screen will be plain black.")
        return 0
    try:
        from PIL import Image, ImageOps
    except ImportError:
        Image = None
    total = 0
    for i, f in enumerate(files, 1):
        if Image:
            out = dest / ("bg%02d.jpg" % i)
            with Image.open(f) as im:
                im = ImageOps.exif_transpose(im).convert("RGB")
                im = ImageOps.fit(im, (1920, 1080), Image.LANCZOS)
                im.save(out, "JPEG", quality=90, optimize=True)
        else:
            out = dest / ("bg%02d%s" % (i, f.suffix.lower()))
            shutil.copyfile(f, out)
        total += out.stat().st_size
    say("  %d background(s), %.1f MB%s" % (len(files), total / 1e6, "" if Image else
        "  (install Pillow to resize them: pip install Pillow)"))
    if total > 60e6:
        say("  Note: that's a lot of picture data; fewer or smaller pictures keep the app light.")
    return len(files)


# ---------------------------------------------------------------- class-file fix-up

def strip_method_parameters(class_file):
    """
    Newer JDKs (21+) write a 'MethodParameters' attribute with unnamed entries for some methods, and the d8
    in older build-tools crashes on it ("Cannot invoke String.length() ..."). Android never needs that
    attribute, so it is removed from each compiled class before d8 runs.
    """
    data = class_file.read_bytes()
    pos = 8
    count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    utf8 = {}
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack_from(">H", data, pos + 1)[0]
            utf8[i] = data[pos + 3:pos + 3 + ln]
            pos += 3 + ln
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        else:
            return False                       # unknown layout: leave the file alone
        i += 1
    out = bytearray(data[:pos])
    out += data[pos:pos + 6]                   # access flags, this, super
    pos += 6
    n = struct.unpack_from(">H", data, pos)[0]
    out += data[pos:pos + 2 + 2 * n]
    pos += 2 + 2 * n
    changed = False
    for _member in ("fields", "methods"):
        n = struct.unpack_from(">H", data, pos)[0]
        out += data[pos:pos + 2]
        pos += 2
        for _ in range(n):
            out += data[pos:pos + 6]
            pos += 6
            ac = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            kept = []
            for _ in range(ac):
                name_idx, ln = struct.unpack_from(">HI", data, pos)
                blob = data[pos:pos + 6 + ln]
                pos += 6 + ln
                if utf8.get(name_idx) == b"MethodParameters":
                    changed = True
                    continue
                kept.append(blob)
            out += struct.pack(">H", len(kept))
            for b in kept:
                out += b
    out += data[pos:]
    if changed:
        class_file.write_bytes(bytes(out))
    return changed


# ---------------------------------------------------------------- build

def keystore():
    """Your own signing key, made once on your machine and kept in .camelot/ (never in git)."""
    ks, info = LOCAL / "camelot-release.jks", LOCAL / "keystore.json"
    if ks.exists() and info.exists():
        return ks, json.loads(info.read_text("utf-8"))["password"]
    keytool = java_tool("keytool")
    if not keytool:
        fail("keytool not found (it comes with the JDK).")
    LOCAL.mkdir(exist_ok=True)
    password = secrets.token_urlsafe(18)
    say("  Creating your own signing key in .camelot/ (keep this folder; back it up if you like)")
    run([keytool, "-genkeypair", "-keystore", ks, "-storepass", password, "-keypass", password,
         "-alias", "camelot", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
         "-dname", "CN=Camelot"])
    info.write_text(json.dumps({"password": password}), "utf-8")
    save_local(load_local())               # also writes .camelot/.gitignore
    return ks, password


def cmd_build(_):
    sdk = sdk_root()
    bt, aj = build_tools(sdk), android_jar(sdk)
    java, javac = java_tool("java"), java_tool("javac")
    if not (sdk and bt and aj and java and javac):
        say("Something needed for the build is missing:")
        cmd_doctor(None)
        sys.exit(1)
    if java_version(java) < 11:
        fail("Java 11 or newer is needed (found %d). Install a newer JDK or set JAVA_HOME." % java_version(java))
    cfg = load_config()
    say("Building Camelot (build-tools %s, %s)" % (bt.name, aj.parent.name))

    if BUILD.exists():
        shutil.rmtree(BUILD)
    assets, gen, classes = BUILD / "assets", BUILD / "gen", BUILD / "classes"
    for d in (assets, gen, classes, BUILD / "dex"):
        d.mkdir(parents=True)
    (assets / "camelot.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False), "utf-8")
    say("  Config: %d apps, %d show rows, %d live inputs"
        % (len(cfg.get("homeApps", [])), len(cfg.get("showRows", [])), len(cfg.get("liveInputs", []))))
    prepare_backgrounds(assets / "backgrounds")

    aapt2 = bt / exe("aapt2")
    say("  Resources")
    run([aapt2, "compile", "--dir", LAUNCHER / "res", "-o", BUILD / "res.zip"])
    version_code = int((time.time() - 1577836800) // 60)      # minutes since 2020: always goes up
    unsigned = BUILD / "unsigned.apk"
    run([aapt2, "link", "-o", unsigned, "-I", aj, "--manifest", LAUNCHER / "AndroidManifest.xml",
         "--java", gen, "-A", assets, "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--version-code", version_code, "--version-name", time.strftime("%Y.%m.%d"),
         "--auto-add-overlay", BUILD / "res.zip"])

    say("  Java")
    sources = sorted((LAUNCHER / "src").rglob("*.java")) + sorted(gen.rglob("*.java"))
    run([javac, "-nowarn", "-Xlint:-options", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-classpath", aj, "-d", classes] + sources)
    for c in classes.rglob("*.class"):
        strip_method_parameters(c)
    jar = BUILD / "classes.jar"
    with zipfile.ZipFile(jar, "w") as z:
        for c in sorted(classes.rglob("*.class")):
            z.write(c, c.relative_to(classes).as_posix())
    run([java, "-cp", bt / "lib" / "d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", MIN_SDK,
         "--lib", aj, "--output", BUILD / "dex", jar])
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(BUILD / "dex" / "classes.dex", "classes.dex")

    say("  Signing")
    aligned = BUILD / "aligned.apk"
    run([bt / exe("zipalign"), "-f", "-p", "4", unsigned, aligned])
    ks, password = keystore()
    env = dict(os.environ, CAMELOT_KS_PASS=password)
    run([java, "-jar", bt / "lib" / "apksigner.jar", "sign", "--min-sdk-version", MIN_SDK, "--v4-signing-enabled", "false", "--ks", ks, "--ks-key-alias", "camelot",
         "--ks-pass", "env:CAMELOT_KS_PASS", "--key-pass", "env:CAMELOT_KS_PASS", "--out", APK, aligned], env=env)
    say("\nBuilt %s (%.1f MB)" % (APK.relative_to(ROOT), APK.stat().st_size / 1e6))
    return 0


# ---------------------------------------------------------------- talking to the TV

def adb_bin():
    a = adb_path()
    if not a:
        fail("adb not found. Install 'Android SDK Platform-Tools' (python tools/camelot.py doctor).")
    return a


def normalise_addr(addr):
    """An IP or host name gets adb's network port (5555) added; USB serials and emulator names stay as they are."""
    addr = addr.strip()
    if ":" in addr or "." not in addr:
        return addr
    return addr + ":5555"


def adb_devices():
    out = run([adb_bin(), "devices"], check=False)
    devs = {}
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            devs[parts[0]] = parts[1]
    return devs


def connect(addr, quiet=False):
    out = run([adb_bin(), "connect", addr], check=False, timeout=30).strip()
    if not quiet:
        say(out)
    return "connected" in out and "cannot" not in out and "failed" not in out


def tv_serial():
    """The TV to talk to: --tv, else the saved address, else the only device adb sees."""
    addr = normalise_addr(TV_ARG) if TV_ARG else load_local().get("tv")
    if addr:
        state = adb_devices().get(addr)
        if state != "device" and ":" in addr:          # network TVs can be reconnected; USB/emulator can't
            connect(addr, quiet=True)
            state = adb_devices().get(addr)
        if state == "unauthorized":
            fail("The TV is asking for permission. On the TV, tick 'Always allow from this computer' and press OK,\n"
                 "then run the command again.")
        if state != "device" and ":" not in addr:
            fail("Can't find %s. If it's an emulator, is it running? If it's a USB device, is the cable in?\n"
                 "Run 'adb devices' to see what's connected." % addr)
        if state != "device":
            fail("Can't reach the TV at %s.\n  - Is the TV on and on the same network?\n"
                 "  - Is network/USB debugging still on? (it can turn off after a TV update)\n"
                 "  - Did the TV's IP address change? Run: python tools/camelot.py connect <new IP>" % addr)
        return addr
    ready = [s for s, st in adb_devices().items() if st == "device"]
    if len(ready) == 1:
        return ready[0]
    if not ready:
        fail("No TV connected. Run: python tools/camelot.py connect <TV-IP>")
    fail("More than one device is connected; pick one with --tv <IP>:\n  " + "\n  ".join(ready))


def sh(serial, *args, check=False, timeout=60):
    return run([adb_bin(), "-s", serial, "shell"] + list(args), check=check, timeout=timeout)


def installed(serial, pkg):
    out = sh(serial, "pm", "list", "packages", pkg)          # lists every package containing the text
    return any(l.strip() == "package:" + pkg for l in out.splitlines())


def packages(serial, flag=""):
    out = sh(serial, "pm", "list", "packages", *([flag] if flag else []))
    return {l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")}


def activities(serial, category):
    out = sh(serial, "cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN", "-c", category)
    found = []
    for line in out.splitlines():
        m = re.match(r"^\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\s*$", line)
        if m and m.group(0).strip() not in found:
            found.append(m.group(0).strip())
    return found


def cmd_connect(a):
    addr = normalise_addr(a.address)
    say("Connecting to %s ..." % addr)
    if not connect(addr):
        time.sleep(2)
        state = adb_devices().get(addr)
        if state == "unauthorized":
            say("\nLook at the TV: allow USB/network debugging from this computer (tick 'Always allow'),\n"
                "then run this command again.")
        else:
            say("\nCouldn't connect. Check that the TV is on, on the same network, and that network debugging is on.\n"
                "Newer TVs use 'Wireless debugging' with a pairing code: see 'pair' in the README.")
        return 1
    time.sleep(1)
    state = adb_devices().get(addr)
    if state == "unauthorized":
        say("\nLook at the TV now: allow debugging from this computer (tick 'Always allow'), then run connect again.")
        return 1
    data = load_local()
    data["tv"] = addr
    save_local(data)
    say("Connected. The address is saved in .camelot/ so other commands use it automatically.")
    return 0


def cmd_pair(a):
    out = run([adb_bin(), "pair", a.address, a.code], check=False, timeout=60)
    say(out.strip())
    if "Successfully paired" in out:
        say("\nPaired. Now connect with the IP and the port shown under 'Wireless debugging' (not the pairing port):\n"
            "  python tools/camelot.py connect <IP:PORT>")
        return 0
    return 1


def cmd_info(_):
    s = tv_serial()
    prop = lambda k: sh(s, "getprop", k).strip()
    say("TV:       %s %s" % (prop("ro.product.manufacturer"), prop("ro.product.model")))
    say("Android:  %s (API %s)" % (prop("ro.build.version.release"), prop("ro.build.version.sdk")))
    google_tv = installed(s, "com.google.android.apps.tv.launcherx")
    say("Type:     %s" % ("Google TV" if google_tv else "Android TV (or other)"))
    home = sh(s, "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
              "-c", "android.intent.category.HOME").strip().splitlines()
    say("Home now: %s" % (home[-1].strip() if home else "unknown"))
    say("Camelot:  %s" % ("installed" if installed(s, PKG) else "not installed"))
    return 0


def cmd_apps(_):
    s = tv_serial()
    found = activities(s, "android.intent.category.LEANBACK_LAUNCHER") + activities(s, "android.intent.category.LAUNCHER")
    pkgs = sorted({f.split("/")[0] for f in found}) or sorted(packages(s, "-3"))
    cfg = load_config()
    used = set()
    for a in cfg.get("homeApps", []):
        p = a.get("packages", a.get("package", []))
        used.update(p if isinstance(p, list) else str(p).split("|"))
    say("Apps on the TV that can be opened (%d). * = already in config/camelot.json\n" % len(pkgs))
    for p in pkgs:
        say(("  * " if p in used else "    ") + p)
    say("\nNot sure which is which? Search the package name online, or ask your AI assistant.")
    return 0


def cmd_inputs(_):
    s = tv_serial()
    out = sh(s, "dumpsys", "tv_input", timeout=30)
    ids = []
    for m in re.finditer(r"TvInputInfo\{id=([^,}\s]+)", out):
        if m.group(1) not in ids:
            ids.append(m.group(1))
    hw = sorted(set(re.findall(r"TvInputHardwareInfo\{[^}]*\}", out)))
    if not ids:
        say("The TV didn't list any inputs. On many TVs a Live TV tile still works with just \"hdmiPort\".")
        return 0
    say("TV inputs (%d):\n" % len(ids))
    for i in ids:
        tag = "  <- HDMI / external input" if re.search(r"hdmi|HW\d|passthrough", i, re.I) else ""
        say("  " + i + tag)
    if hw:
        say("\nHardware ports reported by the TV:")
        for h in hw:
            say("  " + h)
    say("\nIn config/camelot.json, a Live TV tile usually only needs the port number, e.g.\n"
        '  "liveInputs": [ { "name": "Set-top box", "hdmiPort": 2 } ]\n'
        "If that opens the wrong input, add the exact id from the list above as \"inputId\".")
    return 0


def cmd_install(_):
    if not APK.exists():
        fail("No APK yet. Run: python tools/camelot.py build")
    s = tv_serial()
    say("Installing on %s ..." % s)
    out = run([adb_bin(), "-s", s, "install", "-r", APK], check=False, timeout=300)
    if "Success" not in out:
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
            fail("The Camelot on the TV was signed with a different key (another computer or a lost .camelot folder).\n"
                 "Run 'python tools/camelot.py uninstall' first, then install again.")
        fail("Install failed:\n" + out.strip())
    # lets Camelot read the 'continue watching' and channel rows that apps share with the TV
    sh(s, "pm", "grant", PKG, "android.permission.READ_TV_LISTINGS")
    sh(s, "am", "start", "-n", ACTIVITY)
    say("Installed and opened. To make it the home screen: python tools/camelot.py set-home")
    return 0


HOME_RECORD_TV = "/data/local/tmp/camelot-home.json"     # a copy kept on the TV, in case the computer changes
COMPONENT = re.compile(r"^\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\s*$")


def current_home(serial):
    """The home screen the TV opens right now (package/activity), or None if it would ask."""
    out = sh(serial, "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
             "-c", "android.intent.category.HOME")
    for line in reversed(out.splitlines()):
        m = COMPONENT.match(line)
        if m:
            comp = m.group(0).strip()
            return None if comp.startswith("android/") or "ResolverActivity" in comp else comp
    return None


def home_record_path(serial):
    return LOCAL / ("home-%s.json" % re.sub(r"[^A-Za-z0-9]", "_", serial))


def load_home_record(serial):
    """What set-home changed: the home screen in use before Camelot, and the apps it turned off."""
    try:
        rec = json.loads(home_record_path(serial).read_text("utf-8"))
        if isinstance(rec, dict) and rec:
            return rec
    except Exception:
        pass
    try:
        rec = json.loads(sh(serial, "cat", HOME_RECORD_TV).strip() or "{}")
        if isinstance(rec, dict):
            return rec
    except Exception:
        pass
    return {}


def save_home_record(serial, rec):
    LOCAL.mkdir(exist_ok=True)
    home_record_path(serial).write_text(json.dumps(rec, indent=1), "utf-8")
    save_local(load_local())
    sh(serial, "echo '%s' > %s" % (json.dumps(rec), HOME_RECORD_TV))


def clear_home_record(serial):
    p = home_record_path(serial)
    if p.exists():
        p.unlink()
    sh(serial, "echo '{}' > %s" % HOME_RECORD_TV)


def usable_homes(serial):
    return [h for h in activities(serial, "android.intent.category.HOME")
            if not h.startswith(PKG + "/") and "setupwraith" not in h]


def cmd_set_home(_):
    s = tv_serial()
    if not installed(s, PKG):
        fail("Camelot isn't installed on the TV yet. Run: python tools/camelot.py install")
    # remember the home screen in use now (stock or another launcher), so restore-home can go back to it
    rec = load_home_record(s)
    before = current_home(s)
    if before and not before.startswith(PKG + "/") and "setupwraith" not in before:
        rec["previousHome"] = before
        say("Current home screen: %s (restore-home will bring this one back)" % before)
    save_home_record(s, rec)

    sh(s, "cmd", "package", "set-home-activity", ACTIVITY)
    sh(s, "cmd", "role", "add-role-holder", "android.app.role.HOME", PKG)
    present = [p for p in STOCK_HOMES if p in packages(s, "-e")]
    if present:
        say("To keep the TV on Camelot, the stock home screen has to be turned off. On Google TV it ignores the\n"
            "default-home setting otherwise. This disables (does NOT uninstall) these apps:\n")
        for p in present:
            say("  %-45s %s" % (p, STOCK_HOMES[p]))
        say("\nUndo any time with: python tools/camelot.py restore-home   (a factory reset also undoes it)")
        if confirm("\nTurn them off now?"):
            done = []
            for p in present:
                out = sh(s, "pm", "disable-user", "--user", "0", p)
                if "disabled" in out:
                    done.append(p)
                say("  %s: %s" % (p, "turned off" if "disabled" in out else out.strip()))
            rec["disabledByCamelot"] = sorted(set(rec.get("disabledByCamelot", [])) | set(done))
            save_home_record(s, rec)
        else:
            say("Left them on. Camelot is set as default, but the stock home screen may come back on Google TV.")
    sh(s, "cmd", "package", "set-home-activity", ACTIVITY)
    sh(s, "input", "keyevent", "KEYCODE_HOME")
    say("\nDone. Press Home on the remote: you should see Camelot.")
    return 0


def cmd_restore_home(_):
    """Puts back the home screen that was in use before Camelot: the stock one or another launcher."""
    s = tv_serial()
    rec = load_home_record(s)
    # turn back on only what Camelot turned off; apps another launcher turned off are left as they were
    disabled = packages(s, "-d")
    for p in rec.get("disabledByCamelot", []):
        if p in disabled:
            out = sh(s, "pm", "enable", p)
            say("  %s: %s" % (p, "turned back on" if "enabled" in out else out.strip()))
    sh(s, "cmd", "role", "remove-role-holder", "android.app.role.HOME", PKG)

    homes = usable_homes(s)
    if not homes:
        # nothing else could be the home screen: turn the stock one back on so the TV is never left without one
        disabled = packages(s, "-d")
        for p in STOCK_HOMES:
            if p in disabled:
                out = sh(s, "pm", "enable", p)
                say("  %s: %s" % (p, "turned back on" if "enabled" in out else out.strip()))
        homes = usable_homes(s)

    prev = rec.get("previousHome")
    target = None
    if prev:
        target = next((h for h in homes if h == prev), None) \
            or next((h for h in homes if h.split("/")[0] == prev.split("/")[0]), None)
        if not target:
            say("The home screen used before Camelot (%s) isn't available any more." % prev)
    if not target:
        stock = [h for h in homes if h.split("/")[0] in STOCK_HOMES]
        target = (stock or homes or [None])[0]
    if target:
        sh(s, "cmd", "package", "set-home-activity", target)
        sh(s, "cmd", "role", "add-role-holder", "android.app.role.HOME", target.split("/")[0])
        say("Home screen set back to " + target + (" (the one you had before Camelot)" if target == prev else ""))
    clear_home_record(s)
    sh(s, "input", "keyevent", "KEYCODE_HOME")
    say("Done. If the TV asks which home app to use, pick the one you want and choose 'Always'.")
    return 0


def cmd_uninstall(a):
    s = tv_serial()
    cmd_restore_home(a)
    out = run([adb_bin(), "-s", s, "uninstall", PKG], check=False)
    say("Camelot removed." if "Success" in out else out.strip())
    return 0


def read_list(path):
    items = []
    for line in path.read_text("utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        pkg, _, note = line.partition("#")
        if pkg.strip():
            items.append((pkg.strip(), note.strip()))
    return items


def debloat_record(serial):
    return LOCAL / ("debloat-%s.txt" % re.sub(r"[^A-Za-z0-9]", "_", serial))


def cmd_debloat(a):
    path = DEBLOAT / (a.list if a.list.endswith(".txt") else a.list + ".txt")
    if not path.exists():
        names = sorted(p.stem for p in DEBLOAT.glob("*.txt"))
        fail("No list called %s. Lists available: %s" % (path.name, ", ".join(names)))
    s = tv_serial()
    enabled = packages(s, "-e")
    todo = [(p, n) for p, n in read_list(path) if p in enabled]
    if not todo:
        say("Nothing to do: none of the apps in %s are installed and turned on." % path.name)
        return 0
    say("These apps from %s are on the TV and would be turned off (disabled, not uninstalled):\n" % path.name)
    for p, n in todo:
        say("  %-48s %s" % (p, n))
    say("\nUndo any time with: python tools/camelot.py undo-debloat")
    if not confirm("\nTurn these off?"):
        say("Nothing changed.")
        return 0
    record = debloat_record(s)
    LOCAL.mkdir(exist_ok=True)
    done = []
    for p, _ in todo:
        out = sh(s, "pm", "disable-user", "--user", "0", p)
        if "disabled" in out:
            done.append(p)
            say("  turned off  " + p)
        else:
            say("  skipped     %s (%s)" % (p, out.strip() or "the TV refused"))
    with open(record, "a", encoding="utf-8") as f:
        for p in done:
            f.write(p + "\n")
    save_local(load_local())
    say("\n%d app(s) turned off. The list is saved in .camelot/ so undo-debloat can turn them back on." % len(done))
    return 0


def cmd_undo_debloat(a):
    s = tv_serial()
    record = debloat_record(s)
    if a.list:
        path = DEBLOAT / (a.list if a.list.endswith(".txt") else a.list + ".txt")
        pkgs = [p for p, _ in read_list(path)]
    elif record.exists():
        pkgs = [l.strip() for l in record.read_text("utf-8").splitlines() if l.strip()]
    else:
        fail("No record of turned-off apps for this TV. Name a list instead: undo-debloat <list>")
    present = packages(s)
    for p in dict.fromkeys(pkgs):
        if p in present:
            out = sh(s, "pm", "enable", p)
            say("  %s: %s" % (p, "turned on" if "enabled" in out else out.strip()))
        else:      # removed earlier by another tool: bring the preinstalled copy back
            out = sh(s, "cmd", "package", "install-existing", p)
            if "installed" in out.lower():
                say("  %s: restored" % p)
    if not a.list and record.exists():
        record.unlink()
    say("Done.")
    return 0


def cmd_screenshot(a):
    s = tv_serial()
    target = Path(a.file or "tv-screenshot.png")
    p = subprocess.run([adb_bin(), "-s", s, "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    if p.returncode != 0 or not p.stdout.startswith(b"\x89PNG"):
        fail("Couldn't take a screenshot (some TVs block it while video is playing).")
    target.write_bytes(p.stdout)
    say("Saved " + str(target))
    return 0


def cmd_setup(a):
    cmd_build(a)
    cmd_install(a)
    return cmd_set_home(a)


# ---------------------------------------------------------------- main

def main():
    global ASSUME_YES, TV_ARG
    if sys.version_info < (3, 8):
        fail("Python 3.8 or newer is needed.")
    ap = argparse.ArgumentParser(prog="camelot", description="Build and set up the Camelot home screen.",
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n")[1])
    ap.add_argument("--tv", help="TV for this command: IP, IP:PORT, or a device name from 'adb devices' (e.g. emulator-5554)")
    ap.add_argument("--yes", action="store_true", help="answer yes to confirmations")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("doctor")
    p = sub.add_parser("connect"); p.add_argument("address")
    p = sub.add_parser("pair"); p.add_argument("address"); p.add_argument("code")
    for name in ("info", "apps", "inputs", "build", "install", "set-home", "setup", "restore-home", "uninstall"):
        sub.add_parser(name)
    p = sub.add_parser("debloat"); p.add_argument("list")
    p = sub.add_parser("undo-debloat"); p.add_argument("list", nargs="?")
    p = sub.add_parser("screenshot"); p.add_argument("file", nargs="?")
    a = ap.parse_args()
    ASSUME_YES, TV_ARG = a.yes, a.tv
    if not a.cmd:
        ap.print_help()
        return 0
    handlers = {
        "doctor": cmd_doctor, "connect": cmd_connect, "pair": cmd_pair, "info": cmd_info, "apps": cmd_apps,
        "inputs": cmd_inputs, "build": cmd_build, "install": cmd_install, "set-home": cmd_set_home,
        "setup": cmd_setup, "restore-home": cmd_restore_home, "uninstall": cmd_uninstall,
        "debloat": cmd_debloat, "undo-debloat": cmd_undo_debloat, "screenshot": cmd_screenshot,
    }
    return handlers[a.cmd](a) or 0


if __name__ == "__main__":
    sys.exit(main())
