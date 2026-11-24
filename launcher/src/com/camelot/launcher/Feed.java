package com.camelot.launcher;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads the shows each app publishes to the TV (continue watching + its own channels). */
class Feed {
    static class Item {
        String pkg, title, image, intentUri;
        float aspect = 16f / 9f;
        float progress = 0;
    }

    interface Done { void done(Map<String, List<Item>> result); }

    final Activity act;
    final Config cfg;
    final ExecutorService io = Executors.newFixedThreadPool(3);
    final LruCache<String, Bitmap> cache = new BitmapCache(40 * 1024 * 1024);

    /** Memory cache for show pictures, measured in bytes. */
    static final class BitmapCache extends LruCache<String, Bitmap> {
        BitmapCache(int bytes) { super(bytes); }
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    }

    static final String NETFLIX = "com.netflix.ninja";
    static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    Feed(Activity a, Config c) { act = a; cfg = c; }

    /**
     * Netflix only shares "continue watching" with the TV, so this adds Netflix's public Top 10 list
     * (TV shows + films) for the country in the config, cached for 12 hours.
     * It reads Netflix's public Top 10 web page, so it may stop working if Netflix changes that page;
     * when it does, the Netflix row simply shows continue-watching only.
     */
    List<Item> netflixTop10() {
        String country = cfg.netflixTop10Country;
        List<Item> list = new ArrayList<>();
        if (country == null || country.isEmpty() || country.equals("off")) return list;
        String base = country.equals("global") ? "https://www.netflix.com/tudum/top10"
                : "https://www.netflix.com/tudum/top10/" + country.replaceAll("[^a-z0-9-]", "");
        android.content.SharedPreferences sp = act.getSharedPreferences("camelot", Activity.MODE_PRIVATE);
        String cached = sp.getString("nf_top10", "");
        long when = sp.getLong("nf_top10_at", 0);
        if (!base.equals(sp.getString("nf_top10_src", ""))) cached = "";      // country changed
        if (cached.isEmpty() || System.currentTimeMillis() - when > 12 * 3600_000L) {
            StringBuilder sb = new StringBuilder();
            for (String u : new String[]{base + "/tv", base}) {
                try {
                    HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
                    con.setRequestProperty("User-Agent", UA);
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(15000);
                    String html;
                    try (InputStream in = con.getInputStream()) { html = new String(readAll(in), "UTF-8"); }
                    parseTop10(html, sb);
                } catch (Throwable ignored) { }
            }
            if (sb.length() > 0) {
                cached = sb.toString();
                sp.edit().putString("nf_top10", cached).putString("nf_top10_src", base)
                        .putLong("nf_top10_at", System.currentTimeMillis()).apply();
            }
        }
        for (String line : cached.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 3) continue;
            Item it = new Item();
            it.pkg = NETFLIX;
            it.title = f[1];
            it.image = f[2];
            it.intentUri = "intent://www.netflix.com/title/" + f[0] + "#Intent;scheme=https;package=" + NETFLIX + ";action=android.intent.action.VIEW;end";
            list.add(it);
        }
        return list;
    }

    static final java.util.regex.Pattern P_RANK = java.util.regex.Pattern.compile("\"top10\":\\{.*?\"videoId\":(\\d+)", java.util.regex.Pattern.DOTALL);
    static final java.util.regex.Pattern P_PARENT = java.util.regex.Pattern.compile("\"parentShow\":\\{[^}]*?\"videoId\":(\\d+)");
    static final java.util.regex.Pattern P_TITLE = java.util.regex.Pattern.compile("\"title\":\"((?:[^\"\\\\]|\\\\.)+)\"");
    static final java.util.regex.Pattern P_ART = java.util.regex.Pattern.compile("\"sdpArt\".*?\"url\":\"([^\"]+)\"", java.util.regex.Pattern.DOTALL);

    static void parseTop10(String html, StringBuilder out) {
        String[] blocks = html.split("\"PulseTop10ItemEntity:");
        Set<String> ids = new HashSet<>();
        for (int i = 1; i < blocks.length; i++) {
            String b = blocks[i].length() > 9000 ? blocks[i].substring(0, 9000) : blocks[i];
            java.util.regex.Matcher r = P_RANK.matcher(b), t = P_TITLE.matcher(b), a = P_ART.matcher(b), pa = P_PARENT.matcher(b);
            if (!r.find() || !t.find() || !a.find()) continue;
            String id = pa.find() ? pa.group(1) : r.group(1);
            if (!ids.add(id)) continue;
            String title = unescape(t.group(1));
            int colon = title.indexOf(": Season");
            if (colon < 0) colon = title.indexOf(": Limited Series");
            if (colon < 0) colon = title.indexOf(": Part ");
            if (colon > 0) title = title.substring(0, colon);
            out.append(id).append('\t').append(title.replace('\t', ' ')).append('\t').append(unescape(a.group(1))).append('\n');
        }
    }

    static String unescape(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'x' && i + 3 < s.length()) { o.append((char) Integer.parseInt(s.substring(i + 2, i + 4), 16)); i += 3; continue; }
                if (n == 'u' && i + 5 < s.length()) { o.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16)); i += 5; continue; }
                o.append(n); i++; continue;
            }
            o.append(c);
        }
        return o.toString();
    }

    void load(final Done cb) {
        io.execute(() -> {
            // results are keyed by show-row name; one row can collect several package names
            Map<String, List<Item>> out = new HashMap<>();
            Map<String, String> rowOf = new HashMap<>();
            for (Config.ShowRow r : cfg.showRows) {
                out.put(r.name, new ArrayList<>());
                for (String p : r.packages) rowOf.put(p, r.name);
            }
            Map<String, Set<String>> seen = new HashMap<>();
            read("content://android.media.tv/watch_next_program", "last_engagement_time_utc_millis DESC", rowOf, out, seen, true);
            read("content://android.media.tv/preview_program", null, rowOf, out, seen, false);
            String nfRow = rowOf.get(NETFLIX);
            if (nfRow != null) {
                Set<String> nfSeen = seen.get(nfRow);
                if (nfSeen == null) { nfSeen = new HashSet<>(); seen.put(nfRow, nfSeen); }
                for (Item it : netflixTop10()) if (nfSeen.add(it.title.toLowerCase())) out.get(nfRow).add(it);
            }
            int max = cfg.maxShowsPerRow;
            for (String k : new ArrayList<>(out.keySet())) {
                List<Item> l = out.get(k);
                if (l.isEmpty()) out.remove(k);
                else if (l.size() > max) out.put(k, new ArrayList<>(l.subList(0, max)));
            }
            act.runOnUiThread(() -> cb.done(out));
        });
    }

    void read(String uri, String order, Map<String, String> rowOf, Map<String, List<Item>> out,
              Map<String, Set<String>> seen, boolean watchNext) {
        try (Cursor c = act.getContentResolver().query(Uri.parse(uri), null, null, null, order)) {
            if (c == null) return;
            int iPkg = c.getColumnIndex("package_name"), iTitle = c.getColumnIndex("title"),
                iPoster = c.getColumnIndex("poster_art_uri"), iThumb = c.getColumnIndex("thumbnail_uri"),
                iIntent = c.getColumnIndex("intent_uri"), iAsp = c.getColumnIndex("poster_art_aspect_ratio"),
                iBrowsable = c.getColumnIndex("browsable"), iPos = c.getColumnIndex("last_playback_position_millis"),
                iDur = c.getColumnIndex("duration_millis"), iEp = c.getColumnIndex("episode_title");
            while (c.moveToNext()) {
                String pkg = c.getString(iPkg);
                String row = pkg == null ? null : rowOf.get(pkg);
                if (row == null) continue;
                if (iBrowsable >= 0 && !c.isNull(iBrowsable) && c.getInt(iBrowsable) == 0) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.title = iTitle >= 0 ? c.getString(iTitle) : null;
                if (it.title == null || it.title.isEmpty()) it.title = iEp >= 0 ? c.getString(iEp) : "";
                if (it.title == null || it.title.trim().isEmpty()) continue; // skip profile/promo tiles with no show name
                String poster = iPoster >= 0 ? c.getString(iPoster) : null;
                String thumb = iThumb >= 0 ? c.getString(iThumb) : null;
                it.image = poster != null ? poster : thumb;
                if (it.image == null) continue;
                it.intentUri = iIntent >= 0 ? c.getString(iIntent) : null;
                int a = (iAsp >= 0 && !c.isNull(iAsp)) ? c.getInt(iAsp) : 0;
                switch (a) {
                    case 1: it.aspect = 3f / 2f; break;
                    case 2: it.aspect = 4f / 3f; break;
                    case 3: it.aspect = 1f; break;
                    case 4: it.aspect = 2f / 3f; break;
                    case 5: it.aspect = 1f / 1.441f; break;
                    default: it.aspect = 16f / 9f;
                }
                if (watchNext && iPos >= 0 && iDur >= 0 && !c.isNull(iPos) && !c.isNull(iDur)) {
                    long pos = c.getLong(iPos), dur = c.getLong(iDur);
                    if (dur > 0 && pos > 0) it.progress = Math.min(1f, (float) pos / dur);
                }
                Set<String> s = seen.get(row);
                if (s == null) { s = new HashSet<>(); seen.put(row, s); }
                if (!s.add(it.title.toLowerCase())) continue;
                out.get(row).add(it);
            }
        } catch (Throwable ignored) { }
    }

    void image(final String src, final ImageView into, final int targetW) {
        into.setTag(src);
        Bitmap hit = cache.get(src);
        if (hit != null) { into.setImageBitmap(hit); return; }
        into.setImageDrawable(null);
        io.execute(() -> {
            Bitmap b = fetch(src, targetW);
            if (b == null) return;
            cache.put(src, b);
            act.runOnUiThread(() -> { if (src.equals(into.getTag())) into.setImageBitmap(b); });
        });
    }

    Bitmap fetch(String src, int targetW) {
        try {
            byte[] data;
            if (src.startsWith("http")) {
                HttpURLConnection con = (HttpURLConnection) new URL(src).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(10000);
                con.setInstanceFollowRedirects(true);
                try (InputStream in = con.getInputStream()) { data = readAll(in); }
            } else {
                try (InputStream in = act.getContentResolver().openInputStream(Uri.parse(src))) { data = readAll(in); }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
