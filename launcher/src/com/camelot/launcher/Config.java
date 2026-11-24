package com.camelot.launcher;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything a person may want to change lives in config/camelot.json, which the build copies into the
 * app's assets. This class reads it once at start-up. Anything missing falls back to a sensible default,
 * so a broken or half-written config never stops the home screen from opening.
 */
final class Config {
    static final String TAG = "Camelot";

    /** A tile on the Apps page. */
    static final class HomeApp {
        String name;
        String[] packages = new String[0];
        String match = "";          // fallback: an installed app whose label equals / contains this text
    }

    /** A row of shows under the tiles. */
    static final class ShowRow {
        String name;
        String[] packages = new String[0];
    }

    /** A tile on the Live TV page that switches the TV to an input (HDMI box, console, ...). */
    static final class LiveInput {
        String name;
        String subtitle;
        int hdmiPort;               // 1, 2, 3 ... (0 = not set)
        String inputId;             // exact TV input id; optional, see `python tools/camelot.py inputs`
    }

    String title = "CAMELOT";
    boolean showCrown = true;
    int accent = 0xFFD4AF37;
    int columns = 4;
    boolean hideMissingApps = true;
    boolean glassDocks = false;                         // clear glass panel behind each row of tiles
    final List<HomeApp> homeApps = new ArrayList<>();
    final List<ShowRow> showRows = new ArrayList<>();
    final List<LiveInput> liveInputs = new ArrayList<>();
    String liveTabName = "LIVE TV";                     // name of the second page, e.g. "GAMING" or "PS5"
    final Set<String> hiddenApps = new HashSet<>();     // package names or app labels, lower case
    int maxShowsPerRow = 25;
    long backgroundMillis = 9_000;
    float backgroundZoom = 1.18f;
    float backgroundDim = 1f;                           // 0 = no dark shade over pictures, 1 = default shade
    String netflixTop10Country = "";                    // "" = off, "global", or a tudum country slug, e.g. "india"

    static Config load(Context c) {
        Config cfg = new Config();
        try (InputStream in = c.getAssets().open("camelot.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            cfg.parse(new JSONObject(new String(bo.toByteArray(), "UTF-8")));
        } catch (Throwable t) {
            Log.w(TAG, "camelot.json missing or invalid, using defaults", t);
        }
        if (cfg.homeApps.isEmpty()) cfg.addDefaultApps();
        return cfg;
    }

    void parse(JSONObject o) {
        title = o.optString("title", title);
        showCrown = o.optBoolean("showCrown", showCrown);
        try { accent = Color.parseColor(o.optString("accentColor", "#D4AF37")) | 0xFF000000; } catch (Exception ignored) { }
        columns = Math.max(2, Math.min(6, o.optInt("columns", columns)));
        hideMissingApps = o.optBoolean("hideMissingApps", hideMissingApps);
        glassDocks = o.optBoolean("glassDocks", glassDocks);
        maxShowsPerRow = Math.max(1, o.optInt("maxShowsPerRow", maxShowsPerRow));
        backgroundMillis = Math.max(3_000, Math.round(o.optDouble("backgroundSeconds", 9) * 1000));
        backgroundZoom = (float) Math.max(1.0, Math.min(1.5, o.optDouble("backgroundZoom", backgroundZoom)));
        backgroundDim = (float) Math.max(0.0, Math.min(1.0, o.optDouble("backgroundDim", backgroundDim)));
        netflixTop10Country = o.optString("netflixTop10Country", "").trim().toLowerCase(Locale.ROOT);

        JSONArray a = o.optJSONArray("homeApps");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            HomeApp h = new HomeApp();
            h.name = j.optString("name", "");
            h.packages = strings(j, "packages", "package");
            h.match = j.optString("match", h.name).trim().toLowerCase(Locale.ROOT);
            if (!h.name.isEmpty()) homeApps.add(h);
        }

        a = o.optJSONArray("showRows");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            ShowRow r = new ShowRow();
            r.packages = strings(j, "packages", "package");
            r.name = j.optString("name", r.packages.length > 0 ? r.packages[0] : "");
            if (r.packages.length > 0) showRows.add(r);
        }

        liveTabName = o.optString("liveTabName", liveTabName).trim();
        if (liveTabName.isEmpty()) liveTabName = "LIVE TV";

        a = o.optJSONArray("liveInputs");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            LiveInput l = new LiveInput();
            l.hdmiPort = j.optInt("hdmiPort", 0);
            l.inputId = j.optString("inputId", "").trim();
            l.name = j.optString("name", l.hdmiPort > 0 ? "HDMI " + l.hdmiPort : "Live TV");
            l.subtitle = j.optString("subtitle", l.hdmiPort > 0 ? "HDMI " + l.hdmiPort : "");
            if (l.hdmiPort > 0 || !l.inputId.isEmpty()) liveInputs.add(l);
        }

        a = o.optJSONArray("hiddenApps");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) hiddenApps.add(s);
        }
    }

    static String[] strings(JSONObject j, String arrayKey, String singleKey) {
        List<String> out = new ArrayList<>();
        JSONArray a = j.optJSONArray(arrayKey);
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        } else {
            String s = j.optString(arrayKey, j.optString(singleKey, "")).trim();
            if (!s.isEmpty()) for (String p : s.split("[|,]")) if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out.toArray(new String[0]);
    }

    /** Used only when the config has no apps at all. */
    void addDefaultApps() {
        String[][] d = {
            {"YouTube", "com.google.android.youtube.tv|com.google.android.youtube.googletv", "youtube"},
            {"Netflix", "com.netflix.ninja", "netflix"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom", "prime video"},
            {"Disney+", "com.disney.disneyplus", "disney"},
        };
        for (String[] s : d) {
            HomeApp h = new HomeApp();
            h.name = s[0];
            h.packages = s[1].split("\\|");
            h.match = s[2];
            homeApps.add(h);
        }
    }

    boolean isHidden(String pkg, String label) {
        return hiddenApps.contains(pkg.toLowerCase(Locale.ROOT))
                || (label != null && hiddenApps.contains(label.trim().toLowerCase(Locale.ROOT)));
    }

    /** Name of the show row that a package belongs to, or null. */
    ShowRow rowFor(String pkg) {
        for (ShowRow r : showRows) for (String p : r.packages) if (p.equals(pkg)) return r;
        return null;
    }
}
