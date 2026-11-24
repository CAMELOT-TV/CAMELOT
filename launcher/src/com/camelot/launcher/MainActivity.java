package com.camelot.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    /** Settings from config/camelot.json (see Config.java). */
    Config cfg;
    static final int TILE_W = 170, TILE_H = 96, PANEL_W = 380, THUMB_H = 96;
    int ACCENT = 0xFFD4AF37;
    int COLS = 4;
    long BG_INTERVAL = 9_000;

    /** The accent colour with another alpha (0x00 = clear .. 0xFF = solid). */
    int accent(int alpha) { return (alpha << 24) | (ACCENT & 0x00FFFFFF); }

    /** Mixes the accent colour towards white (amount > 0) or black (amount < 0). */
    int accentShade(float amount) {
        int r = (ACCENT >> 16) & 255, g = (ACCENT >> 8) & 255, b = ACCENT & 255;
        int t = amount > 0 ? 255 : 0;
        float k = Math.abs(amount);
        r = Math.round(r + (t - r) * k); g = Math.round(g + (t - g) * k); b = Math.round(b + (t - b) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static class AppEntry {
        String pkg, label;
        Intent launch;
        ResolveInfo ri;
    }

    /** Background pictures packed into the app by the build (assets/backgrounds). */
    final List<String> bgFiles = new ArrayList<>();
    /** Number of tiles on the Apps page; Live TV tiles follow them in {@link #tiles}. */
    int appCount = 0;
    ImageView bgA, bgB;
    FrameLayout bgLayer;
    TextView menuPill;
    TextView nameLine;
    final java.util.Map<View, String> tileNames = new java.util.HashMap<>();
    View focusRing;
    boolean noGlide = false;
    int feedRowsPerScreen = 3;
    LinearLayout contentView;
    android.animation.ValueAnimator ringAnim;
    FrameLayout pager;
    LinearLayout page1, page2;
    TextView pageLabel1, pageLabel2;
    int page = 0;
    int bgIndex = 0;
    final Handler bgHandler = new Handler(Looper.getMainLooper());
    final Runnable bgTick = this::nextBackground;

    /** Crossfades to the next background picture and starts its slow zoom. */
    void nextBackground() {
        if (bgA == null) return;
        if (bgFiles.size() < 2) {                        // one picture: drift slowly in and out
            ImageView iv = bgA.getAlpha() > 0.5f ? bgA : bgB;
            boolean in = iv.getScaleX() < (1f + cfg.backgroundZoom) / 2f;
            float to = in ? cfg.backgroundZoom : 1f;
            iv.animate().scaleX(to).scaleY(to).translationX(in ? px(16) : -px(12)).setDuration(BG_INTERVAL + 1500)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
            bgHandler.postDelayed(bgTick, BG_INTERVAL + 1500);
            return;
        }
        bgIndex = (bgIndex + 1) % bgFiles.size();
        final ImageView front = bgA.getAlpha() > 0.5f ? bgA : bgB;
        final ImageView back = front == bgA ? bgB : bgA;
        back.setImageBitmap(loadBackground(bgIndex));
        bgLayer.removeView(back);
        bgLayer.addView(back, 1, new FrameLayout.LayoutParams(-1, -1));   // above the old picture, below the shade
        back.setAlpha(0f);
        kenBurns(back);
        front.animate().cancel();
        front.setAlpha(1f);
        back.animate().alpha(1f).setDuration(1200).withEndAction(() -> {
            front.setAlpha(0f);
            front.setImageDrawable(null);
        }).start();
        bgHandler.postDelayed(bgTick, BG_INTERVAL);
    }

    ScrollView scroller;
    LinearLayout feedBox;
    volatile Map<String, List<Feed.Item>> feed = new java.util.HashMap<>();
    Feed feedLoader;

    FrameLayout root;
    LinearLayout grid, panel, panelList;
    ScrollView panelScroll;
    final List<View> tiles = new ArrayList<>();
    final Set<String> mainPkgs = new HashSet<>();
    int lastFocus = 0;
    boolean panelOpen = false;
    float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        cfg = Config.load(this);
        ACCENT = cfg.accent;
        COLS = cfg.columns;
        BG_INTERVAL = cfg.backgroundMillis;
        findBackgrounds();
        buildUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (panelOpen) closePanel(false);
        // Home button: jump back to the top, first app
        lastFocus = 0;
        if (scroller != null) scroller.scrollTo(0, 0);
        if (appCount > 0) {
            if (page != 0) { page1.setVisibility(View.VISIBLE); page2.setVisibility(View.GONE); page = 0; updatePageTabs(); }
            tiles.get(0).requestFocus();
            showName(tileNames.get(tiles.get(0)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTiles();
        if (feedLoader == null) feedLoader = new Feed(this, cfg);
        feedLoader.load(result -> {
            feed = result;
            buildFeedRows();
        });
        bgHandler.removeCallbacks(bgTick);
        if (bgA != null) {
            kenBurns(bgA.getAlpha() > 0.5f ? bgA : bgB);
            bgHandler.postDelayed(bgTick, BG_INTERVAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        bgHandler.removeCallbacks(bgTick);
    }

    /** Lists the pictures the build packed into assets/backgrounds. */
    void findBackgrounds() {
        bgFiles.clear();
        try {
            String[] names = getAssets().list("backgrounds");
            if (names != null) for (String n : names) {
                String l = n.toLowerCase(Locale.ROOT);
                if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp"))
                    bgFiles.add("backgrounds/" + n);
            }
        } catch (Exception ignored) { }
        Collections.sort(bgFiles);
    }

    /** Decodes one background at about screen size, so large photos don't use too much memory. */
    android.graphics.Bitmap loadBackground(int i) {
        if (bgFiles.isEmpty()) return null;
        String f = bgFiles.get(i % bgFiles.size());
        int screenW = Math.max(1280, getResources().getDisplayMetrics().widthPixels);
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getAssets().open(f)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            try (java.io.InputStream in = getAssets().open(f)) { return android.graphics.BitmapFactory.decodeStream(in, null, o2); }
        } catch (Throwable t) {
            return null;
        }
    }

    int kbTurn = 0;

    void kenBurns(ImageView iv) {
        iv.animate().cancel();
        int dir = (kbTurn++ % 2 == 0) ? 1 : -1;
        // slow, steady zoom; it keeps moving through the fade so it never stops abruptly
        iv.setScaleX(1.0f);
        iv.setScaleY(1.0f);
        iv.setTranslationX(-dir * px(12));
        iv.setTranslationY(px(4));
        iv.animate().scaleX(cfg.backgroundZoom).scaleY(cfg.backgroundZoom).translationX(dir * px(16)).translationY(-px(6))
                .setDuration(BG_INTERVAL + 1500)
                .setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    int px(float v) { return Math.round(v * dp); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- UI ----------

    void buildUi() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackgroundColor(0xFF000000);
        if (!bgFiles.isEmpty()) {
            bgLayer = new FrameLayout(this);
            root.addView(bgLayer, new FrameLayout.LayoutParams(-1, -1));
            bgA = new ImageView(this);
            bgB = new ImageView(this);
            for (ImageView iv : new ImageView[]{bgA, bgB}) {
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                bgLayer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }
            bgIndex = (int) (System.currentTimeMillis() / 1000 % bgFiles.size());
            bgA.setImageBitmap(loadBackground(bgIndex));
            bgB.setAlpha(0f);
            kenBurns(bgA);
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xBF000000, 0x8C000000, 0xB3000000, 0xFF000000}));
            shade.setAlpha(cfg.backgroundDim);
            bgLayer.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(px(16), px(22), px(16), px(18));
        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setSmoothScrollingEnabled(true);
        scroller.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        contentView = content;
        // accent-coloured focus frame that glides from tile to tile
        focusRing = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(px(14) * 1.1f);
        rg.setColor(0x14FFFFFF);
        rg.setStroke(Math.round(px(3) * 1.1f), ACCENT);
        float rr = px(14) * 1.1f;
        GradientDrawable ringSheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        ringSheen.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
        android.graphics.drawable.LayerDrawable ringGlass =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{rg, ringSheen});
        int rs = Math.round(px(3) * 1.1f);
        ringGlass.setLayerInset(1, rs, rs, rs, px(40));
        focusRing.setBackground(ringGlass);
        focusRing.setElevation(px(30));
        focusRing.setAlpha(0f);
        root.addView(focusRing, new FrameLayout.LayoutParams(px(TILE_W), px(TILE_H)));
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (bgLayer == null) return;
            float h = Math.max(1, root.getHeight());
            bgLayer.setTranslationY(-y * 0.35f);
            float a = 1f - Math.min(1f, y / (h * 0.9f));
            bgLayer.setAlpha(0.15f + 0.85f * a);
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(cfg.title);
        title.setTextColor(ACCENT);
        title.setTextSize(28);
        title.setLetterSpacing(0.3f);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        android.graphics.Path cp = new android.graphics.Path();
        cp.moveTo(0f, 1.2f); cp.lineTo(0f, 0.3f); cp.lineTo(0.5f, 0.75f); cp.lineTo(1f, 0f);
        cp.lineTo(1.5f, 0.75f); cp.lineTo(2f, 0.3f); cp.lineTo(2f, 1.2f); cp.close();
        cp.addRect(0f, 1.3f, 2f, 1.5f, android.graphics.Path.Direction.CW);
        android.graphics.drawable.ShapeDrawable crown = new android.graphics.drawable.ShapeDrawable(
                new android.graphics.drawable.shapes.PathShape(cp, 2f, 1.5f));
        crown.getPaint().setColor(ACCENT);
        crown.getPaint().setAntiAlias(true);
        View crownView = new View(this);
        crownView.setBackground(crown);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(27), px(20));
        clp.rightMargin = px(5);
        top.addView(crownView, clp);
        if (!cfg.showCrown) crownView.setVisibility(View.GONE);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        // size the crown to the height of the capital letters and sit it on the same line as the title
        title.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            android.graphics.Rect cap = new android.graphics.Rect();
            title.getPaint().getTextBounds("H", 0, 1, cap);
            int h = cap.height();
            int w = Math.round(h * 2f / 1.5f);
            LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) crownView.getLayoutParams();
            if (p2.height != h || p2.width != w) {
                p2.height = h; p2.width = w;
                crownView.setLayoutParams(p2);
            }
            crownView.post(() -> crownView.setTranslationY(
                    (title.getTop() + title.getBaseline()) - crownView.getBottom()));
        });
        TextView tab = new TextView(this);
        menuPill = tab;
        tab.setText("ALL APPS");
        tab.setTextColor(ACCENT);
        tab.setTextSize(14);
        tab.setLetterSpacing(0.25f);
        tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tab.setPadding(px(14), px(6), px(14), px(6));
        GradientDrawable tbg = new GradientDrawable();
        tbg.setCornerRadius(px(16));
        tbg.setColor(0);
        tab.setBackground(tbg);
        tab.setOnClickListener(v -> openPanel());
        tab.setFocusable(true);
        tab.setOnFocusChangeListener((v, has) -> { pillHighlight(has || panelOpen); if (has) showName("All Apps"); });
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(-2, -2);
        tabLp.rightMargin = px(24);
        top.addView(tab, tabLp);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a  ·  EEE, d MMM");
        clock.setFormat24Hour("HH:mm  ·  EEE, d MMM");
        clock.setTextColor(ACCENT);
        clock.setTextSize(18);
        top.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));

        content.addView(new View(this), new LinearLayout.LayoutParams(-1, px(6)));

        // page tabs: APPS · LIVE TV
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        pageLabel1 = pageTab("APPS");
        pageLabel2 = pageTab("LIVE TV");
        tabs.addView(pageLabel1);
        TextView sep = new TextView(this);
        sep.setText("   \u00B7   ");
        sep.setTextColor(0x66FFFFFF);
        tabs.addView(sep);
        tabs.addView(pageLabel2);
        if (cfg.liveInputs.isEmpty()) {             // nothing to show on a Live TV page
            sep.setVisibility(View.GONE);
            pageLabel2.setVisibility(View.GONE);
        }
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(-1, -2);
        tlp2.topMargin = px(44);
        content.addView(tabs, tlp2);

        nameLine = new TextView(this);
        nameLine.setTextSize(22);
        nameLine.setLetterSpacing(0.22f);
        nameLine.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameLine.setTextColor(ACCENT);
        nameLine.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        nameLine.setPadding(px(94), 0, 0, 0);   // moved to the first tile's left edge once tiles are laid out
        nameLine.setSingleLine(true);
        nameLine.setShadowLayer(px(6), 0, px(1), 0x66000000);
        nameLine.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (b - t == ob - ot && r - l == orr - ol) return;
            float h = b - t;
            // glassy accent colour: bright highlight on top, the colour itself, a deeper shade at the bottom
            nameLine.getPaint().setShader(new android.graphics.LinearGradient(0, h * 0.15f, 0, h * 0.85f,
                    new int[]{accentShade(0.75f), accentShade(0.12f), accentShade(-0.08f)}, new float[]{0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            nameLine.invalidate();
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = px(26);
        content.addView(nameLine, nlp);

        pager = new FrameLayout(this);
        pager.setClipChildren(false);
        pager.setClipToPadding(false);
        page1 = new LinearLayout(this);
        page2 = new LinearLayout(this);
        for (LinearLayout pg : new LinearLayout[]{page1, page2}) {
            pg.setOrientation(LinearLayout.VERTICAL);
            pg.setGravity(Gravity.CENTER_HORIZONTAL);
            pg.setClipChildren(false);
            pg.setClipToPadding(false);
            pager.addView(pg, new FrameLayout.LayoutParams(-1, -2));
        }
        page2.setVisibility(View.GONE);
        grid = page1;
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = px(30);
        content.addView(pager, plp);

        feedBox = new LinearLayout(this);
        feedBox.setOrientation(LinearLayout.VERTICAL);
        feedBox.setClipChildren(false);
        feedBox.setClipToPadding(false);
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(-1, -2);
        fblp.topMargin = px(72);   // show rows start just below the screen edge
        content.addView(feedBox, fblp);





        // Hidden right-side panel
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF5000000);
        panel.setPadding(px(24), px(32), px(24), px(24));
        panel.setElevation(px(24));
        TextView ph = new TextView(this);
        ph.setText("All Apps");
        ph.setTextColor(ACCENT);
        ph.setTextSize(22);
        ph.setPadding(px(8), 0, 0, px(16));
        panel.addView(ph);
        panelScroll = new ScrollView(this);
        panelScroll.setVerticalScrollBarEnabled(false);
        panelList = new LinearLayout(this);
        panelList.setOrientation(LinearLayout.VERTICAL);
        panelScroll.addView(panelList, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(panelScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(px(PANEL_W), -1, Gravity.RIGHT));

        setContentView(root);
    }

    /** Shows the focused app's name on the line under APPS · LIVE TV with a soft fade. */
    String wantedName = null;

    void showName(final String name) {
        if (nameLine == null || name == null) return;
        final String up = name.toUpperCase(Locale.ROOT);
        if (up.equals(wantedName)) return;
        wantedName = up;
        nameLine.animate().cancel();
        nameLine.animate().alpha(0f).translationY(px(4)).setDuration(110).withEndAction(() -> {
            nameLine.setText(wantedName);            // always the latest name asked for
            nameLine.setTranslationY(-px(4));
            nameLine.animate().alpha(1f).translationY(0).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }).start();
    }

    /** Clear glass: almost no tint, bright rim, sharp top reflection line, soft sheen and a faint bottom glow. */

    void glassDock(LinearLayout v) {
        if (!cfg.glassDocks) {                   // same spacing and sizes, just no glass
            v.setBackground(null);
            v.setPadding(px(12), px(2), px(12), px(10));
            return;
        }
        float r = px(26);
        int edge = Math.max(1, px(1));
        GradientDrawable body = new GradientDrawable();
        body.setCornerRadius(r);
        body.setColor(0x02FFFFFF);
        body.setStroke(edge, ACCENT);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1FFFFFFF, 0x05FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        GradientDrawable topLine = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00F6E3A1, 0xCCF6E3A1, 0x00F6E3A1});
        GradientDrawable diag = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x12FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x08FFFFFF});
        diag.setCornerRadius(r);
        GradientDrawable lowGlow = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x0FFFFFFF, 0x00FFFFFF});
        lowGlow.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        android.graphics.drawable.LayerDrawable glass = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{body, diag, sheen, lowGlow, topLine});
        glass.setLayerInset(1, edge, edge, edge, edge);
        glass.setLayerInset(2, edge, edge, edge, px(90));
        glass.setLayerInset(3, edge, px(120), edge, edge);
        glass.setLayerInset(4, px(22), edge, px(22), 0);
        glass.setLayerHeight(4, Math.max(1, px(1)));
        v.setBackground(glass);
        v.setPadding(px(12), px(2), px(12), px(10));
    }

    TextView pageTab(String t) {
        final TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(12);
        v.setLetterSpacing(0.25f);
        v.setPadding(px(14), px(6), px(14), px(6));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        bg.setColor(0);
        v.setBackground(bg);
        v.setFocusable(true);
        v.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? accent(0x33) : 0);
            bg.setStroke(has ? px(2) : 0, ACCENT);
            if (has) {
                if (scroller != null) scroller.smoothScrollTo(0, 0);
                showName(x == pageLabel2 ? "Live TV" : "Apps");
                int want = (x == pageLabel2) ? 1 : 0;
                if (want != page) goToPage(want, -1);
            }
        });
        v.setOnClickListener(x -> enterPage());
        return v;
    }

    /** From the APPS / LIVE TV tabs into the tiles of the current page. */
    void enterPage() {
        if (tiles.isEmpty()) return;
        int idx;
        if (page == 1 && tiles.size() > appCount) idx = appCount;
        else if (appCount == 0) return;
        else idx = Math.min(lastFocus < appCount ? lastFocus % COLS : 0, appCount - 1);
        tiles.get(idx).requestFocus();
    }

    void updatePageTabs() {
        pageLabel1.setTextColor(page == 0 ? ACCENT : accent(0xB3));
        pageLabel2.setTextColor(page == 1 ? ACCENT : accent(0xB3));
    }

    /** Slide between the Apps page and the Live TV page. */
    void goToPage(int target, int focusIndex) {
        if (target == page) { if (focusIndex >= 0) tiles.get(focusIndex).requestFocus(); return; }
        final LinearLayout out = page == 0 ? page1 : page2;
        final LinearLayout in = target == 0 ? page1 : page2;
        int dir = target > page ? 1 : -1;
        float w = pager.getWidth() > 0 ? pager.getWidth() : px(900);
        page = target;
        updatePageTabs();
        pager.setMinimumHeight(page1.getHeight());
        in.setVisibility(View.VISIBLE);
        in.setTranslationX(dir * w * 0.6f);
        in.setAlpha(0f);
        in.animate().translationX(0).alpha(1f).setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        out.animate().translationX(-dir * w * 0.6f).alpha(0f).setDuration(260)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> { out.setVisibility(View.GONE); out.setTranslationX(0); out.setAlpha(1f); }).start();
        if (focusIndex >= 0) {
            noGlide = true;
            if (ringAnim != null) ringAnim.cancel();
            focusRing.setAlpha(0f);
            tiles.get(focusIndex).requestFocus();
            pager.postDelayed(() -> noGlide = false, 400);
        }
    }

    void refreshTiles() {
        List<AppEntry> all = launchableApps();
        mainPkgs.clear();
        tiles.clear();
        page1.removeAllViews();
        page2.removeAllViews();

        // page 1: the apps from the config, in the config's order
        List<View> cells = new ArrayList<>();
        for (Config.HomeApp a : cfg.homeApps) {
            AppEntry found = find(all, a);
            if (found == null && cfg.hideMissingApps) continue;
            if (found != null) {
                if (mainPkgs.contains(found.pkg)) continue;     // same app listed twice
                mainPkgs.add(found.pkg);
            }
            cells.add(makeAppTile(a.name, found));
        }
        appCount = tiles.size();
        addTileRows(page1, cells);

        // page 2: Live TV, one tile per configured input, laid out on the same grid as the apps
        List<View> live = new ArrayList<>();
        for (Config.LiveInput in : cfg.liveInputs) live.add(makeLiveTile(in));
        addTileRows(page2, live);

        if (tiles.isEmpty()) {                          // nothing installed from the config yet
            page = 0;
            page1.setVisibility(View.VISIBLE);
            page2.setVisibility(View.GONE);
            updatePageTabs();
            if (!panelOpen && menuPill != null) menuPill.post(menuPill::requestFocus);
            return;
        }
        alignNameLine();
        int want = Math.min(lastFocus, tiles.size() - 1);
        int wantPage = want >= appCount ? 1 : 0;
        page1.setVisibility(wantPage == 0 ? View.VISIBLE : View.GONE);
        page2.setVisibility(wantPage == 1 ? View.VISIBLE : View.GONE);
        page = wantPage;
        updatePageTabs();
        if (!panelOpen) {
            final View target = tiles.get(want);
            target.post(target::requestFocus);
        }
    }

    /** Starts the name line exactly above the left edge of the first tile, whatever the number of columns. */
    void alignNameLine() {
        if (nameLine == null || tiles.isEmpty()) return;
        final View first = tiles.get(0);
        first.post(() -> {
            if (first.getWidth() == 0 || contentView == null) return;
            android.graphics.Rect r = new android.graphics.Rect(0, 0, first.getWidth(), first.getHeight());
            try { contentView.offsetDescendantRectToMyCoords(first, r); } catch (Exception e) { return; }
            int left = r.left - contentView.getPaddingLeft();
            if (left > 0 && left != nameLine.getPaddingLeft()) nameLine.setPadding(left, 0, 0, 0);
        });
    }

    /** Lays tiles out in rows of COLS; a short last row is filled with invisible slots so it stays left-aligned. */
    void addTileRows(LinearLayout pg, List<View> cells) {
        if (cells.isEmpty()) return;
        int slots = ((cells.size() + COLS - 1) / COLS) * COLS;
        LinearLayout row = null;
        for (int i = 0; i < slots; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                glassDock(row);
                row.setPadding(px(18), px(4), px(18), px(8));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.gravity = Gravity.CENTER_HORIZONTAL;
                rlp.topMargin = px(8);
                rlp.bottomMargin = px(8);
                pg.addView(row, rlp);
            }
            View cell;
            LinearLayout.LayoutParams tlp;
            if (i < cells.size()) {
                cell = cells.get(i);
                tlp = new LinearLayout.LayoutParams(px(TILE_W), -2);
            } else {                                    // empty slot keeps the grid shape
                cell = new View(this);
                cell.setVisibility(View.INVISIBLE);
                tlp = new LinearLayout.LayoutParams(px(TILE_W), px(TILE_H));
            }
            tlp.setMargins(px(10), px(14), px(10), px(10));
            row.addView(cell, tlp);
        }
    }

    LinearLayout tileWrap(FrameLayout card, String name) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setClipChildren(false);
        wrap.addView(card, new LinearLayout.LayoutParams(px(TILE_W), px(TILE_H)));
        tileNames.put(card, name);
        tiles.add(card);
        return wrap;
    }

    /** Glide the focus frame from the previous tile to this one. */
    void moveRingTo(final FrameLayout card, long delay) {
        if (focusRing == null) return;
        card.postDelayed(() -> {
            if (card.getWidth() == 0 || !card.hasFocus()) return;
            android.graphics.Rect r = new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight());
            contentView.offsetDescendantRectToMyCoords(card, r);
            final float tw = card.getWidth() * 1.1f, th = card.getHeight() * 1.1f;
            final float tx = r.centerX() - tw / 2f, ty = r.centerY() - th / 2f;
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusRing.getLayoutParams();
            boolean hidden = noGlide || focusRing.getAlpha() < 0.05f;
            if (noGlide) focusRing.setAlpha(0f);
            if (hidden) {                      // first focus: no glide needed
                setBorder(card, true);
                focusRing.setTranslationX(tx); focusRing.setTranslationY(ty);
                lp.width = Math.round(tw); lp.height = Math.round(th);
                focusRing.setLayoutParams(lp);
                return;
            }
            final float sx = hidden ? tx : focusRing.getTranslationX(), sy = hidden ? ty : focusRing.getTranslationY();
            final float sw = hidden ? tw : lp.width, sh = hidden ? th : lp.height;
            if (ringAnim != null) ringAnim.cancel();
            ringAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            ringAnim.setDuration(420);
            ringAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            ringAnim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                focusRing.setTranslationX(sx + (tx - sx) * f);
                focusRing.setTranslationY(sy + (ty - sy) * f);
                lp.width = Math.round(sw + (tw - sw) * f);
                lp.height = Math.round(sh + (th - sh) * f);
                focusRing.setLayoutParams(lp);
            });
            ringAnim.addListener(new RingLanded(this, card));
            focusRing.setAlpha(1f);
            ringAnim.start();
        }, delay);
    }

    /** When the gliding frame reaches a tile, the tile's own border takes over. */
    static final class RingLanded extends android.animation.AnimatorListenerAdapter {
        final MainActivity act;
        final FrameLayout card;
        boolean cancelled;
        RingLanded(MainActivity a, FrameLayout c) { act = a; card = c; }
        @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
        @Override public void onAnimationEnd(android.animation.Animator a) {
            if (cancelled || !card.hasFocus()) return;
            act.setBorder(card, true);
            act.focusRing.setAlpha(0f);
        }
    }

    /** Glass reflection on the upper part of a focused tile. */
    void glass(FrameLayout card, boolean on) {
        FrameLayout fc = face(card);
        if (fc == null) return;
        View g = fc.findViewWithTag("glass");
        if (g == null) {
            g = new View(this);
            g.setTag("glass");
            g.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x38FFFFFF, 0x12FFFFFF, 0x00FFFFFF}));
            g.setAlpha(0f);
            fc.addView(g, new FrameLayout.LayoutParams(-1, px(TILE_H * 0.58f), Gravity.TOP));
        }
        g.bringToFront();
        g.animate().cancel();
        g.animate().alpha(on ? 1f : 0f).setDuration(on ? 520 : 300).start();
    }

    /** A soft light sweep across the tile when it gets focus. */
    void shine(FrameLayout card) {
        FrameLayout fc = face(card);
        if (fc == null) return;
        View sh = fc.findViewWithTag("shine");
        if (sh == null) {
            sh = new View(this);
            sh.setTag("shine");
            sh.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x00FFFFFF, 0x1AFFFFFF, 0x3DFFFFFF, 0x1AFFFFFF, 0x00FFFFFF}));
            sh.setRotation(20f);
            fc.addView(sh, new FrameLayout.LayoutParams(px(130), px(TILE_H * 2), Gravity.CENTER_VERTICAL));
        }
        sh.bringToFront();
        final View s2 = sh;
        int w = Math.max(card.getWidth(), px(TILE_W));
        s2.animate().cancel();
        s2.setTranslationX(-px(160));
        s2.setAlpha(1f);
        s2.animate().translationX(w + px(40)).setStartDelay(180).setDuration(1150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
                .withEndAction(() -> s2.setAlpha(0f)).start();
    }

    final java.util.Map<View, FrameLayout> faces = new java.util.HashMap<>();

    FrameLayout face(View card) { return faces.get(card); }

    /** Accent border: the tile's face shrinks by the border width and the accent rim shows around it. */
    void setBorder(View card, boolean on) {
        FrameLayout f = faces.get(card);
        if (f == null) return;
        GradientDrawable rim = (GradientDrawable) card.getBackground();
        rim.setColor(on ? ACCENT : 0);
        int b = on ? px(3) : 0;
        card.setPadding(b, b, b, b);
        Drawable fb = f.getBackground();
        if (fb instanceof GradientDrawable) ((GradientDrawable) fb).setCornerRadius(px(14) - b);
        f.invalidateOutline();
    }

    FrameLayout baseCard(int color) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable rimBg = new GradientDrawable();
        rimBg.setCornerRadius(px(14));
        rimBg.setColor(0);
        card.setBackground(rimBg);
        FrameLayout faceView = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(14));
        bg.setColor(color);
        faceView.setBackground(bg);
        faceView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        faceView.setClipToOutline(true);
        card.addView(faceView, new FrameLayout.LayoutParams(-1, -1));
        faces.put(card, faceView);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((v, has) -> {
            if (has) {
                lastFocus = Math.max(0, tiles.indexOf(v));
                showName(tileNames.get(v));
                if (scroller != null) scroller.smoothScrollTo(0, 0);
                v.animate().scaleX(1.1f).scaleY(1.1f).translationZ(px(12)).setDuration(380)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f)).start();
                glass((FrameLayout) v, true);
                moveRingTo((FrameLayout) v, 0);
                shine((FrameLayout) v);
            } else {
                setBorder(v, false);
                glass((FrameLayout) v, false);
                v.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(320)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                if (focusRing != null && !noGlide) {
                    // put the gliding frame where this tile's border was, so it can travel to the next tile
                    android.graphics.Rect r = new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight());
                    contentView.offsetDescendantRectToMyCoords(v, r);
                    float w = v.getWidth() * 1.1f, h = v.getHeight() * 1.1f;
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusRing.getLayoutParams();
                    if (ringAnim == null || !ringAnim.isRunning()) {
                        focusRing.setTranslationX(r.centerX() - w / 2f);
                        focusRing.setTranslationY(r.centerY() - h / 2f);
                        lp.width = Math.round(w); lp.height = Math.round(h);
                        focusRing.setLayoutParams(lp);
                    }
                    focusRing.setAlpha(1f);
                    v.post(() -> { if (!tiles.contains(getCurrentFocus())) focusRing.setAlpha(0f); });
                }
            }
        });
        return card;
    }

    View makeAppTile(final String name, final AppEntry e) {
        PackageManager pm = getPackageManager();
        FrameLayout card = baseCard(0xFF1C2033);
        card.setTag(e == null ? null : e.pkg);
        FrameLayout fc = face(card);
        Drawable banner = null;
        if (e != null) {
            try { banner = e.ri.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
            if (banner == null) {
                try { banner = pm.getApplicationBanner(e.pkg); } catch (Exception ignored) { }
            }
        }
        if (banner != null) {                           // the app's own TV banner, as the app made it
            ImageView img = new ImageView(this);
            img.setImageDrawable(banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setScaleX(1.05f);
            img.setScaleY(1.05f);
            fc.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else if (e != null) {                         // phone-style app: icon in the middle
            Drawable icon;
            try { icon = pm.getApplicationIcon(e.pkg); } catch (Exception x) { icon = e.ri.loadIcon(pm); }
            ImageView img = new ImageView(this);
            img.setImageDrawable(icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fc.addView(img, new FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER));
        } else {
            TextView t = new TextView(this);
            t.setText("Not installed");
            t.setTextColor(0x66FFFFFF);
            t.setTextSize(13);
            fc.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        card.setOnClickListener(v -> {
            if (e == null) { toast(name + " is not installed"); return; }
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).withEndAction(() -> {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
                launch(e.launch);
            }).start();
        });
        return tileWrap(card, name);
    }

    /** A Live TV tile: black glass with a soft accent glow, a pulsing "live" dot, the name and its input. */
    View makeLiveTile(final Config.LiveInput in) {
        FrameLayout card = baseCard(0xFF0B0B0D);
        float r = px(14);
        // black glass face with a soft glow in the accent colour
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1C1A16, 0xFF0B0B0D, 0xFF050506});
        base.setCornerRadius(r);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(px(TILE_W * 0.7f));
        glow.setGradientCenter(0.5f, 0.15f);
        glow.setColors(new int[]{accent(0x40), accent(0x0D), accent(0x00)});
        glow.setCornerRadius(r);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(r);
        rim.setColor(0);
        rim.setStroke(Math.max(1, px(1)), accent(0x59));
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1AFFFFFF, 0x00FFFFFF});
        sheen.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        android.graphics.drawable.LayerDrawable faceBg = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{base, glow, sheen, rim});
        faceBg.setLayerInset(2, 0, 0, 0, px(TILE_H * 0.55f));
        face(card).setBackground(faceBg);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER);
        // small pulsing red "live" dot
        final View dot = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(0xFFE53935);
        dot.setBackground(dd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(9), px(9));
        dlp.rightMargin = px(10);
        inner.addView(dot, dlp);
        android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f);
        pulse.setDuration(1100);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulse.start();

        final TextView t1 = new TextView(this);
        t1.setText(in.name.toUpperCase(Locale.ROOT));
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t1.setTextSize(17);
        t1.setLetterSpacing(0.14f);
        t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t1.setTextColor(0xFFFFFFFF);
        t1.setShadowLayer(px(8), 0, 0, accent(0x66));
        inner.addView(t1, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER);
        stack.addView(inner, new LinearLayout.LayoutParams(-2, -2));
        TextView t2 = new TextView(this);
        t2.setText(in.subtitle == null ? "" : in.subtitle.toUpperCase(Locale.ROOT));
        if (in.subtitle == null || in.subtitle.isEmpty()) t2.setVisibility(View.GONE);
        t2.setTextColor(0xB3FFFFFF);
        t2.setTextSize(12);
        t2.setLetterSpacing(0.2f);
        t2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(-2, -2);
        t2lp.topMargin = px(4);
        stack.addView(t2, t2lp);

        face(card).addView(stack, new FrameLayout.LayoutParams(-1, -1));
        card.setOnClickListener(v -> openInput(in));
        return tileWrap(card, in.name);
    }

    // ---------- shows row ----------

    void buildFeedRows() {
        feedBox.removeAllViews();
        PackageManager pm = getPackageManager();
        for (Config.ShowRow f : cfg.showRows) {
            List<Feed.Item> items = feed.get(f.name);
            if (items == null || items.isEmpty()) continue;
            final LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setClipChildren(false);
            block.setClipToPadding(false);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(px(18), 0, 0, 0);
            for (String p : f.packages) {
                try {
                    ImageView ic = new ImageView(this);
                    ic.setImageDrawable(pm.getApplicationIcon(p));
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(22), px(22));
                    ilp.rightMargin = px(10);
                    head.addView(ic, ilp);
                    break;
                } catch (Exception ignored) { }
            }
            TextView t = new TextView(this);
            t.setText(f.name);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(17);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setShadowLayer(px(4), 0, px(1), 0xCC000000);
            head.addView(t);
            block.addView(head, new LinearLayout.LayoutParams(-1, -2));

            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setPadding(px(18), px(10), px(18), px(8));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            for (Feed.Item it : items) {
                View th = makeThumb(it, false);
                View card = ((ViewGroup) th).getChildAt(0);
                final View.OnFocusChangeListener inner = card.getOnFocusChangeListener();
                card.setOnFocusChangeListener((v, has) -> {
                    inner.onFocusChange(v, has);
                    if (has) {
                        // page through the rows: each screen shows a fixed set of whole rows
                        int idx = feedBox.indexOfChild(block);
                        int first = (idx / Math.max(1, feedRowsPerScreen)) * Math.max(1, feedRowsPerScreen);
                        View top = feedBox.getChildAt(first);
                        int y = top.getTop() + feedBox.getTop() + ((View) feedBox.getParent()).getTop() - px(14);
                        scroller.smoothScrollTo(0, Math.max(0, y));
                    }
                });
                row.addView(th);
            }
            hs.addView(row, new FrameLayout.LayoutParams(-2, -2));
            block.addView(hs, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.bottomMargin = px(14);
            feedBox.addView(block, blp);
            block.setAlpha(0f);
            block.setTranslationY(px(24));
            block.animate().alpha(1f).translationY(0).setStartDelay(80L * feedBox.getChildCount())
                    .setDuration(380).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        // bottom spacing so the last row can scroll fully into view
        final View tailSpace = new View(this);
        feedBox.addView(tailSpace, new LinearLayout.LayoutParams(-1, px(200)));
        // space the rows so a whole number of them fills the screen exactly: no half-visible row at the bottom
        feedBox.post(() -> {
            int screen = scroller.getHeight();
            int rows = feedBox.getChildCount() - 1;
            if (screen <= 0 || rows <= 0) return;
            int h = feedBox.getChildAt(0).getHeight();
            int usable = screen - px(14);
            int n = Math.max(1, usable / (h + px(14)));
            int gap = (usable - n * h + n - 1) / n + px(2);   // the next row always starts just below the screen edge
            feedRowsPerScreen = n;
            for (int i = 0; i < rows; i++) {
                View blk = feedBox.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) blk.getLayoutParams();
                lp.bottomMargin = gap;
                blk.setLayoutParams(lp);
            }
            ViewGroup.LayoutParams tl = tailSpace.getLayoutParams();
            tl.height = screen;
            tailSpace.setLayoutParams(tl);
        });
        // rebuilding the rows must never steal focus from the apps
        View cf = getCurrentFocus();
        if (!panelOpen && !tiles.isEmpty() && (cf == null || (!tiles.contains(cf) && cf != pageLabel1 && cf != pageLabel2 && cf != menuPill))) {
            scroller.scrollTo(0, 0);
            int want = Math.min(lastFocus, tiles.size() - 1);
            if ((want >= appCount ? 1 : 0) == page) tiles.get(want).requestFocus();
            else tiles.get(page == 1 && tiles.size() > appCount ? appCount : 0).requestFocus();
        }
    }

    String labelFor(String pkg) {
        Config.ShowRow r = cfg.rowFor(pkg);
        return r == null ? "" : r.name;
    }

    View makeThumb(final Feed.Item it, boolean showApp) {
        int h = px(THUMB_H);
        int w = Math.round(h * it.aspect);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setClipChildren(false);
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(10));
        bg.setColor(0xFF1C2033);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(10));
        ring.setColor(0);
        card.setForeground(ring);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (it.progress > 0) {
            View bar = new View(this);
            bar.setBackgroundColor(ACCENT);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Math.round(w * it.progress), px(4), Gravity.BOTTOM);
            card.addView(bar, blp);
        }
        feedLoader.image(it.image, img, w);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(has ? px(3) : 0, ACCENT);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? px(10) : 0).setDuration(130).start();
        });
        card.setOnClickListener(v -> openItem(it));
        wrap.addView(card, new LinearLayout.LayoutParams(w, h));
        TextView t = new TextView(this);
        t.setText(showApp ? it.title + "  \u00B7  " + labelFor(it.pkg) : it.title);
        t.setTextColor(0xDDFFFFFF);
        t.setTextSize(11);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(px(3), 0, px(1), 0xCC000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(w, -2);
        tlp.topMargin = px(5);
        wrap.addView(t, tlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(16);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    void openItem(Feed.Item it) {
        if (it.intentUri != null) {
            try {
                Intent i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        Intent l = getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
        if (l == null) l = getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (l != null) launch(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------- apps ----------

    List<AppEntry> launchableApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> map = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.ri = ri;
                e.launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                map.put(pkg, e);
            }
        }
        return new ArrayList<>(map.values());
    }

    /** Finds a configured app: first by its package names, then by its label. */
    AppEntry find(List<AppEntry> all, Config.HomeApp a) {
        for (String p : a.packages)
            for (AppEntry e : all) if (e.pkg.equals(p)) return e;
        String kw = a.match;
        if (kw == null || kw.isEmpty()) return null;
        for (AppEntry e : all) if (e.label.toLowerCase(Locale.ROOT).equals(kw)) return e;
        for (AppEntry e : all) if (e.label.toLowerCase(Locale.ROOT).contains(kw)) return e;
        return null;
    }

    void launch(Intent i) {
        try {
            startActivity(i);
        } catch (Exception ex) {
            toast("Can't open this app");
        }
    }

    // ---------- Live TV inputs ----------

    /** Switches the TV to a configured input: the exact input id if given, otherwise the HDMI port number. */
    void openInput(Config.LiveInput in) {
        String label = in.hdmiPort > 0 ? "HDMI " + in.hdmiPort : in.name;
        if (in.inputId != null && !in.inputId.isEmpty() && tryInput(in.inputId)) return;
        String id = in.hdmiPort > 0 ? findHdmi(in.hdmiPort) : null;
        if (id == null) { toast("Couldn't find " + label + " on this TV"); return; }
        if (!tryInput(id)) toast("Couldn't switch to " + label);
    }

    boolean tryInput(String id) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Finds the TV's input id for HDMI port n. Most TVs name their inputs "HDMI 1", "HDMI 2" ... (in the label
     * or the id); if not, the n-th HDMI port in the TV's own list is used.
     */
    String findHdmi(int n) {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        List<TvInputInfo> ports = new ArrayList<>();
        for (TvInputInfo t : tim.getTvInputList())
            if (t.getType() == TvInputInfo.TYPE_HDMI && t.getParentId() == null) ports.add(t);
        String want = "HDMI" + n;
        for (TvInputInfo t : ports) {
            String s = (t.getId() + " " + t.loadLabel(this)).toUpperCase(Locale.ROOT).replace(" ", "");
            // "HDMI1" must not match "HDMI10"
            int at = s.indexOf(want);
            while (at >= 0) {
                int end = at + want.length();
                if (end >= s.length() || !Character.isDigit(s.charAt(end))) return t.getId();
                at = s.indexOf(want, end);
            }
        }
        if (ports.size() >= n) return ports.get(n - 1).getId();
        return null;
    }

    // ---------- right-side panel ----------

    void pillHighlight(boolean on) {
        if (menuPill == null) return;
        GradientDrawable g = (GradientDrawable) menuPill.getBackground();
        g.setColor(on ? accent(0x33) : 0);
        g.setStroke(on ? px(2) : 0, ACCENT);
        menuPill.setTextColor(ACCENT);
    }

    void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        pillHighlight(true);
        populatePanel();
        panel.setVisibility(View.VISIBLE);
        panel.setTranslationX(px(PANEL_W));
        panel.setAlpha(0f);
        panel.animate().translationX(0).alpha(1f).setDuration(450)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        panelScroll.scrollTo(0, 0);
        if (panelList.getChildCount() > 0) panelList.getChildAt(0).requestFocus();
    }

    void closePanel(boolean animate) {
        if (!panelOpen) return;
        panelOpen = false;
        pillHighlight(false);
        if (animate) {
            panel.animate().translationX(px(PANEL_W)).alpha(0f).setDuration(320)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        } else {
            panel.setVisibility(View.GONE);
        }
        if (!tiles.isEmpty()) tiles.get(Math.min(lastFocus, tiles.size() - 1)).requestFocus();
        else if (menuPill != null) menuPill.requestFocus();
    }

    void populatePanel() {
        panelList.removeAllViews();
        PackageManager pm = getPackageManager();
        panelList.addView(panelRow("TV Settings", null,
                v -> launch(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        List<AppEntry> all = launchableApps();
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
        for (final AppEntry e : all) {
            if (mainPkgs.contains(e.pkg)) continue;
            if (cfg.isHidden(e.pkg, e.label)) continue;
            panelList.addView(panelRow(e.label, e.ri.loadIcon(pm), v -> launch(e.launch)));
        }
    }

    View panelRow(String label, Drawable icon, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(12), px(10), px(12), px(10));
        r.setFocusable(true);
        r.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(14));
        bg.setColor(0);
        r.setBackground(bg);
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            r.addView(iv, new LinearLayout.LayoutParams(px(40), px(40)));
        } else {
            TextView g = new TextView(this);
            g.setText("⚙");
            g.setTextColor(ACCENT);
            g.setTextSize(24);
            g.setGravity(Gravity.CENTER);
            r.addView(g, new LinearLayout.LayoutParams(px(40), px(40)));
        }
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16);
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = px(16);
        r.addView(tv, tlp);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? accent(0x14) : 0);
            bg.setStroke(has ? px(3) : 0, ACCENT);
        });
        r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(4);
        r.setLayoutParams(lp);
        return r;
    }

    // ---------- keys ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (panelOpen && panelList.getChildCount() > 0) {
                View pc = getCurrentFocus();
                if (k == KeyEvent.KEYCODE_DPAD_DOWN && pc == panelList.getChildAt(panelList.getChildCount() - 1)) return true;
                if (k == KeyEvent.KEYCODE_DPAD_UP && pc == panelList.getChildAt(0)) return true;
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            }
            if (panelOpen && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_LEFT)) {
                closePanel(true);
                return true;
            }
            if (!panelOpen && k == KeyEvent.KEYCODE_MENU) {
                openPanel();
                return true;
            }
            int idx = tiles.indexOf(getCurrentFocus());
            boolean hasLive = tiles.size() > appCount;
            boolean onApp = idx >= 0 && idx < appCount;
            boolean onLive = idx >= appCount;
            int liveIdx = idx - appCount;                // position among the Live TV tiles
            View cur = getCurrentFocus();
            boolean onTab = cur == pageLabel1 || cur == pageLabel2;
            if (!panelOpen && k == KeyEvent.KEYCODE_DPAD_RIGHT && idx >= 0) {
                // ▶ from the right edge of the apps goes to Live TV; from the right edge of Live TV opens All Apps
                if (onApp && (idx % COLS == COLS - 1 || idx == appCount - 1)) {
                    if (hasLive) goToPage(1, appCount); else openPanel();
                    return true;
                }
                if (onLive && (liveIdx % COLS == COLS - 1 || idx == tiles.size() - 1)) { openPanel(); return true; }
            }
            // Live TV page: ▼ only moves to another Live TV tile; never down into the show rows
            if (!panelOpen && k == KeyEvent.KEYCODE_DPAD_DOWN && page == 1 && onLive) {
                View next = cur.focusSearch(View.FOCUS_DOWN);
                int ni = tiles.indexOf(next);
                if (ni < appCount) return true;
            }
            // ▼ on the last row of shows does nothing (no scrolling into empty space)
            if (!panelOpen && k == KeyEvent.KEYCODE_DPAD_DOWN && cur != null && feedBox != null && feedBox.getChildCount() > 1) {
                View lastRow = feedBox.getChildAt(feedBox.getChildCount() - 2);
                for (View v = cur; v != null && v != feedBox; v = (v.getParent() instanceof View) ? (View) v.getParent() : null) {
                    if (v == lastRow) return true;
                }
            }
            // ▲ from the first row of tiles goes up to the page tabs
            if (!panelOpen && k == KeyEvent.KEYCODE_DPAD_UP && ((onApp && idx < COLS) || (onLive && liveIdx < COLS))) {
                (page == 1 ? pageLabel2 : pageLabel1).requestFocus();
                return true;
            }
            if (!panelOpen && cur == menuPill) {
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { (page == 1 ? pageLabel2 : pageLabel1).requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            }
            if (!panelOpen && onTab) {
                if (k == KeyEvent.KEYCODE_DPAD_UP) { menuPill.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { enterPage(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT && cur == pageLabel1 && hasLive) { pageLabel2.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && cur == pageLabel2) { pageLabel1.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            }
            // ◀ on the left edge of the apps does nothing; on the left edge of Live TV it goes back to the apps
            if (!panelOpen && k == KeyEvent.KEYCODE_DPAD_LEFT && onApp && idx % COLS == 0) return true;
            if (!panelOpen && k == KeyEvent.KEYCODE_DPAD_LEFT && onLive && liveIdx % COLS == 0) {
                if (appCount > 0) goToPage(0, Math.min(COLS - 1, appCount - 1));
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_BACK) return true; // a launcher has nowhere to go back to
        return super.dispatchKeyEvent(e);
    }
}
