package de.redclient.textures;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * /redmenu - Texture-Browser mit Mehrfachauswahl (Tab "Textures") und Menue-Design (Tab "HUD").
 * Alles wird selbst gezeichnet (Immediate-Mode): Smooth Scrolling, Hover-Animationen, Klick-Sounds.
 */
public class RedMenuScreen extends Screen {

    // ------------------------------------------------------------------ Konstanten

    private static final int HEADER = 34, FOOTER = 30, GAP = 6, LABEL = 12, ROW_CAT = 19, ROW_SEL = 24;
    private static final int[] ACCENTS = {
            0xE02020, 0xFF6A00, 0xF2C200, 0x2ECC40, 0x00C2A8, 0x00B7D4,
            0x3B6CFF, 0x7A5CFF, 0x9B4DFF, 0xFF4FA3, 0xFFFFFF, 0x9AA0A6};
    private static final int[] BGS = {0x0B0B0B, 0x101418, 0x14101A, 0x0F1A14, 0x1A1010, 0x1B1B1F, 0x000000, 0x20242B};
    private static final String[] STYLES = {
            "Glatt", "Verlauf", "Raster", "Punkte", "Scanlines", "Schachbrett", "Textur kacheln"};

    // ------------------------------------------------------------------ Hilfstypen

    private interface Drag {
        void to(double mx, double my);
    }

    private record Zone(int x1, int y1, int x2, int y2, Runnable left, Runnable right, Drag drag) {
        boolean hit(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    /** Ein Scrollbereich mit weichem Nachziehen. */
    private static final class Scroll {
        float target, cur, prev, max;

        void setMax(float m) {
            max = Math.max(0, m);
            target = RedConfig.clamp(target, 0, max);
            cur = RedConfig.clamp(cur, 0, max);
        }

        void wheel(double v, float step) {
            target = RedConfig.clamp(target - (float) v * step, 0, max);
        }

        void update(float dt) {
            prev = cur;
            cur += (target - cur) * (1f - (float) Math.exp(-RedConfig.scrollK() * dt));
            if (Math.abs(target - cur) < 0.02f) cur = target;
        }

        float speed(float dt) {
            return dt > 0 ? Math.abs(cur - prev) / dt : 0;
        }
    }

    // ------------------------------------------------------------------ Zustand

    private int tab = 0;
    private float tabX, tabW;
    private int cat = 0;
    private String query = "";

    private final List<PackIndex.Entry> filtered = new ArrayList<>();
    private final int[] counts = new int[PackIndex.CATEGORIES.length];
    private final LinkedHashMap<String, PackIndex.Entry> sel = new LinkedHashMap<>(); // Zielpfad -> Eintrag
    private final Set<String> appliedUids = new HashSet<>();
    private boolean needsReload;
    private PackIndex.Entry marked;

    private final Scroll gridScroll = new Scroll(), catScroll = new Scroll(), selScroll = new Scroll();

    private final Map<String, Float> anim = new HashMap<>();
    private final List<Zone> zones = new ArrayList<>(), frameZones = new ArrayList<>();
    private Drag dragging;

    private TextFieldWidget search, hexAccent, hexBg;
    private boolean syncing;

    private String status = "";
    private float statusTime;
    private String hoverInfo = "";

    // Layout
    private int px, py, pw, ph, bodyTop, bodyBot, sideL, sideR;
    private int catX, catY, catW, catH;
    private int gridX, gridY, gridW, gridH, cols, cell, areaW;
    private int selX, selY, selW, selH;
    private int hx0, hy0, colW;

    // Frame
    private DrawContext c;
    private int mx, my;
    private long lastNanos;
    private float dt, open, ga = 1f, time;
    private boolean hasClip;
    private int cx1, cy1, cx2, cy2;
    private int tAcc, tBg, tTxt, tMuted, tSide, tCard, tCardHi, tLine;

    public RedMenuScreen() {
        super(Text.literal("Red Menu"));
        PackIndex.reload();
        restoreSelection();
    }

    // ------------------------------------------------------------------ Init / Layout

    @Override
    protected void init() {
        int m = Math.max(6, Math.min(14, width / 50));
        px = m;
        py = m;
        pw = width - 2 * m;
        ph = height - 2 * m;
        bodyTop = py + HEADER;
        bodyBot = py + ph - FOOTER;
        sideL = Math.max(88, Math.min(122, pw / 5));
        sideR = Math.max(112, Math.min(176, pw * 27 / 100));

        catX = px + 8;
        catW = sideL - 16;
        catY = bodyTop + 22;
        catH = bodyBot - catY - 6;

        gridX = px + sideL + 8;
        gridW = pw - sideL - sideR - 16;
        gridY = bodyTop + 32;
        gridH = bodyBot - gridY - 4;
        areaW = gridW - 8;
        cols = Math.max(2, (areaW + GAP) / (64 + GAP));
        cell = Math.max(36, (areaW - (cols - 1) * GAP) / cols);

        selX = px + pw - sideR + 8;
        selW = sideR - 16;
        selY = bodyTop + 24;
        selH = bodyBot - selY - 28;

        hx0 = px + 16;
        hy0 = bodyTop + 12;
        colW = (pw - 32 - 28) / 3;

        // Suchfeld
        search = new TextFieldWidget(textRenderer, gridX + 24, bodyTop + 10, gridW - 30, 9, Text.literal("Suche"));
        search.setDrawsBackground(false);
        search.setMaxLength(48);
        search.setText(query);
        search.setChangedListener(s -> {
            query = s;
            gridScroll.target = 0;
            refreshFilter();
        });
        addDrawableChild(search);

        // Hex-Felder (HUD-Tab)
        int hexAy = hy0 + 12 + 2 * 18 + 8;
        int hexBy = hy0 + 86 + 12 + 2 * 18 + 8;
        hexAccent = hexField(hx0 + 34, hexAy + 3, RedConfig.accent, v -> RedConfig.accent = v);
        hexBg = hexField(hx0 + 34, hexBy + 3, RedConfig.bg, v -> RedConfig.bg = v);

        refreshFilter();
    }

    private TextFieldWidget hexField(int x, int y, int value, Consumer<Integer> set) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x + 5, y, 56, 9, Text.literal("Hex"));
        f.setDrawsBackground(false);
        f.setMaxLength(7);
        f.setText(String.format("#%06X", value & 0xFFFFFF));
        f.setChangedListener(s -> {
            if (syncing) return;
            Integer v = parseHex(s);
            if (v != null) {
                set.accept(v);
                RedConfig.save();
            }
        });
        addDrawableChild(f);
        return f;
    }

    private static Integer parseHex(String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        if (t.length() != 6) return null;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void setHex(TextFieldWidget f, int value) {
        syncing = true;
        f.setText(String.format("#%06X", value & 0xFFFFFF));
        syncing = false;
    }

    // ------------------------------------------------------------------ Daten

    private void restoreSelection() {
        sel.clear();
        for (String uid : RedConfig.selected) {
            PackIndex.Entry e = PackIndex.BY_UID.get(uid);
            if (e != null) sel.put(e.path(), e);
        }
        appliedUids.clear();
        appliedUids.addAll(RedConfig.applied);
        PackIndex.Entry t = PackIndex.BY_UID.get(RedConfig.tileUid);
        if (t != null) marked = t;
    }

    private void saveSelection() {
        RedConfig.selected.clear();
        for (PackIndex.Entry e : sel.values()) RedConfig.selected.add(e.uid());
        RedConfig.save();
    }

    private Set<String> selUids() {
        Set<String> s = new HashSet<>();
        for (PackIndex.Entry e : sel.values()) s.add(e.uid());
        return s;
    }

    private boolean pending() {
        return !selUids().equals(appliedUids);
    }

    private boolean isSelected(PackIndex.Entry e) {
        PackIndex.Entry s = sel.get(e.path());
        return s != null && s.uid().equals(e.uid());
    }

    private void toggle(PackIndex.Entry e) {
        marked = e;
        if (isSelected(e)) {
            sel.remove(e.path());
            RedSounds.deselect();
        } else {
            sel.put(e.path(), e); // gleicher Zielpfad aus anderem Pack wird ersetzt
            RedSounds.select();
        }
        saveSelection();
    }

    private void refreshFilter() {
        String q = query.toLowerCase(Locale.ROOT).trim();
        Arrays.fill(counts, 0);
        filtered.clear();
        for (PackIndex.Entry e : PackIndex.ENTRIES) {
            if (!q.isEmpty() && !(e.name().toLowerCase(Locale.ROOT).contains(q)
                    || e.pack().toLowerCase(Locale.ROOT).contains(q)
                    || e.folder().toLowerCase(Locale.ROOT).contains(q))) continue;
            counts[0]++;
            int ci = PackIndex.categoryIndex(e.category());
            counts[ci]++;
            if (cat == 0 || cat == ci) filtered.add(e);
        }
    }

    private void apply() {
        try {
            int n = RedPackBuilder.write(client, sel.values());
            appliedUids.clear();
            appliedUids.addAll(selUids());
            RedConfig.applied.clear();
            RedConfig.applied.addAll(appliedUids);
            RedConfig.save();
            needsReload = true;
            say(n == 0 ? "Auswahl leer - Pack entfernt. Klicke Done zum Neuladen."
                    : n + " Texturen angewendet. Klicke Done zum Neuladen.");
            RedSounds.apply();
        } catch (IOException ex) {
            say("Fehler beim Anwenden: " + ex.getMessage());
        }
    }

    private void done() {
        if (pending()) apply();
        boolean reload = needsReload;
        boolean has = !sel.isEmpty();
        RedSounds.done();
        close();
        if (reload) RedPackBuilder.enableAndReload(client, has);
    }

    private void say(String s) {
        status = s;
        statusTime = 6f;
    }

    // ------------------------------------------------------------------ Farben / Zeichenhelfer

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * t + ((b >> 16) & 255) * (1 - t));
        int g = (int) (((a >> 8) & 255) * t + ((b >> 8) & 255) * (1 - t));
        int bl = (int) ((a & 255) * t + (b & 255) * (1 - t));
        return (r << 16) | (g << 8) | bl;
    }

    private static float lum(int rgb) {
        return (0.2126f * ((rgb >> 16) & 255) + 0.7152f * ((rgb >> 8) & 255) + 0.0722f * (rgb & 255)) / 255f;
    }

    private int A(int rgb, float a) {
        int al = (int) (RedConfig.clamp(a * ga, 0f, 1f) * 255f);
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    private void rect(int x1, int y1, int x2, int y2, int rgb, float a) {
        if (x2 > x1 && y2 > y1) c.fill(x1, y1, x2, y2, A(rgb, a));
    }

    /** Rechteck mit 1px abgeschnittenen Ecken. */
    private void rrect(int x, int y, int w, int h, int rgb, float a) {
        rect(x + 1, y, x + w - 1, y + h, rgb, a);
        rect(x, y + 1, x + w, y + h - 1, rgb, a);
    }

    private void frame(int x, int y, int w, int h, int t, int rgb, float a) {
        rect(x, y, x + w, y + t, rgb, a);
        rect(x, y + h - t, x + w, y + h, rgb, a);
        rect(x, y + t, x + t, y + h - t, rgb, a);
        rect(x + w - t, y + t, x + w, y + h - t, rgb, a);
    }

    private void text(String s, int x, int y, int rgb, float a) {
        int col = A(rgb, a);
        if ((col >>> 24) < 8) return;
        c.drawText(textRenderer, s, x, y, col, false);
    }

    private int tw(String s) {
        return textRenderer.getWidth(s);
    }

    private String fit(String s, int w) {
        return textRenderer.trimToWidth(s, Math.max(4, w));
    }

    private float hoverAnim(String key, boolean on) {
        float v = anim.getOrDefault(key, 0f);
        v += ((on ? 1f : 0f) - v) * Math.min(1f, dt * 16f);
        if (anim.size() > 3000) anim.clear();
        anim.put(key, v);
        return v;
    }

    private boolean over(int x1, int y1, int x2, int y2) {
        if (dragging != null) return false;
        if (mx < x1 || mx >= x2 || my < y1 || my >= y2) return false;
        return !hasClip || (mx >= cx1 && mx < cx2 && my >= cy1 && my < cy2);
    }

    private void zone(int x1, int y1, int x2, int y2, Runnable left, Runnable right, Drag drag) {
        if (hasClip) {
            x1 = Math.max(x1, cx1);
            y1 = Math.max(y1, cy1);
            x2 = Math.min(x2, cx2);
            y2 = Math.min(y2, cy2);
        }
        if (x2 > x1 && y2 > y1) frameZones.add(new Zone(x1, y1, x2, y2, left, right, drag));
    }

    private void beginClip(int x1, int y1, int x2, int y2, float frac) {
        c.enableScissor(x1, y1, x2, y2);
        hasClip = true;
        cx1 = x1;
        cy1 = y1;
        cx2 = x2;
        cy2 = y2;
        c.getMatrices().pushMatrix();
        c.getMatrices().translate(0f, -frac);
    }

    private void endClip() {
        c.getMatrices().popMatrix();
        c.disableScissor();
        hasClip = false;
    }

    private void tex(PreviewCache.Slot s, int x, int y, int size) {
        if (s != null && s.ready && s.id != null) {
            c.drawTexture(RenderPipelines.GUI_TEXTURED, s.id, x, y, 0f, 0f, size, size,
                    PreviewCache.T, PreviewCache.T, PreviewCache.T, PreviewCache.T);
        } else {
            float p = 0.12f + 0.1f * (float) Math.sin(time * 5f + x * 0.07f + y * 0.05f);
            rect(x + size / 4, y + size / 4, x + size * 3 / 4, y + size * 3 / 4, tTxt, (s != null && s.failed) ? 0.04f : p);
        }
    }

    // ------------------------------------------------------------------ Widgets

    private void button(String id, int x, int y, int w, int h, String label, boolean enabled, boolean primary, Runnable click) {
        float hv = hoverAnim("b" + id, enabled && over(x, y, x + w, y + h));
        int base = primary ? mix(tAcc, tBg, 0.55f + 0.4f * hv) : mix(tTxt, tBg, 0.09f + 0.12f * hv);
        if (!enabled) base = mix(tTxt, tBg, 0.05f);
        rrect(x, y, w, h, base, enabled ? 0.95f : 0.6f);
        if (primary && enabled) rect(x + 1, y + h - 2, x + w - 1, y + h - 1, tAcc, 0.9f);
        int tc = !enabled ? tMuted : (primary ? 0xFFFFFF : tTxt);
        text(label, x + (w - tw(label)) / 2, y + (h - 8) / 2, tc, enabled ? 1f : 0.5f);
        if (enabled) zone(x, y, x + w, y + h, () -> {
            RedSounds.tap();
            click.run();
        }, null, null);
    }

    private void slider(String label, int x, int y, int w, float v, Consumer<Float> set, String valText) {
        text(label, x, y, tTxt, 0.9f);
        text(valText, x + w - tw(valText), y, tMuted, 0.9f);
        int ty = y + 13;
        rect(x, ty + 2, x + w, ty + 5, tLine, 0.7f);
        int fw = (int) (w * v);
        rect(x, ty + 2, x + fw, ty + 5, tAcc, 1f);
        boolean hv = over(x - 3, ty - 3, x + w + 3, ty + 10) || dragging != null;
        rect(x + fw - 2, ty, x + fw + 2, ty + 7, hv ? tAcc : tTxt, 1f);
        zone(x - 3, ty - 4, x + w + 3, ty + 11, null, null, (mxx, myy) -> {
            float nv = RedConfig.clamp((float) ((mxx - x) / w), 0f, 1f);
            set.accept(nv);
            RedSounds.tick(nv);
        });
    }

    private int swatches(int x, int y, int w, int[] cols, int perRow, int current, Consumer<Integer> pick) {
        int g = 4, sw = (w - (perRow - 1) * g) / perRow, sh = 14;
        for (int i = 0; i < cols.length; i++) {
            int rx = x + (i % perRow) * (sw + g), ry = y + (i / perRow) * (sh + g);
            boolean cur = (cols[i] & 0xFFFFFF) == (current & 0xFFFFFF);
            boolean hv = over(rx, ry, rx + sw, ry + sh);
            rect(rx - 1, ry - 1, rx + sw + 1, ry + sh + 1, cur ? tTxt : (hv ? tMuted : tLine), cur || hv ? 1f : 0.6f);
            rect(rx, ry, rx + sw, ry + sh, cols[i], 1f);
            final int col = cols[i];
            zone(rx, ry, rx + sw, ry + sh, () -> {
                RedSounds.tap();
                pick.accept(col);
                RedConfig.save();
            }, null, null);
        }
        return ((cols.length + perRow - 1) / perRow) * (sh + g);
    }

    private void fieldFrame(TextFieldWidget f, int pad) {
        int x = f.getX() - 5, y = f.getY() - 4, w = f.getWidth() + pad, h = 17;
        boolean fo = f.isFocused();
        rrect(x, y, w, h, tTxt, fo ? 0.14f : 0.07f);
        frame(x, y, w, h, 1, fo ? tAcc : tLine, fo ? 1f : 0.7f);
    }

    // ------------------------------------------------------------------ Render

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // bewusst leer: kein Vanilla-Blur, wir zeichnen den Hintergrund selbst
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;
        c = context;
        mx = mouseX;
        my = mouseY;
        frameZones.clear();
        hoverInfo = "";

        open = Math.min(1f, open + dt * 5f);
        ga = 1f - (1f - open) * (1f - open);
        if (statusTime > 0) statusTime -= dt;

        gridScroll.update(dt);
        catScroll.update(dt);
        selScroll.update(dt);
        PreviewCache.pump();

        tAcc = RedConfig.accent & 0xFFFFFF;
        tBg = RedConfig.bg & 0xFFFFFF;
        tTxt = lum(tBg) > 0.55f ? 0x111111 : 0xFFFFFF;
        tMuted = mix(tTxt, tBg, 0.5f);
        tSide = mix(tBg, 0x000000, 0.65f);
        tCard = mix(tTxt, tBg, 0.07f);
        tCardHi = mix(tTxt, tBg, 0.17f);
        tLine = mix(tTxt, tBg, 0.22f);

        search.visible = tab == 0;
        hexAccent.visible = tab == 1;
        hexBg.visible = tab == 1;

        drawPanel();
        drawHeader();
        if (tab == 0) drawTextures();
        else drawHud();
        drawFooter();

        zones.clear();
        zones.addAll(frameZones);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawPanel() {
        rect(0, 0, width, height, 0x000000, 0.62f);
        for (int i = 1; i <= 5; i++) rect(px - i, py - i, px + pw + i, py + ph + i, tAcc, 0.09f / i);
        rect(px, py, px + pw, py + ph, tBg, RedConfig.opacity);

        c.enableScissor(px, py, px + pw, py + ph);
        switch (RedConfig.style) {
            case RedConfig.STYLE_GRADIENT:
                c.fillGradient(px, py, px + pw, py + ph, A(tAcc, 0.22f), A(tAcc, 0f));
                break;
            case RedConfig.STYLE_GRID:
                for (int x = px; x < px + pw; x += 16) rect(x, py, x + 1, py + ph, tTxt, 0.05f);
                for (int y = py; y < py + ph; y += 16) rect(px, y, px + pw, y + 1, tTxt, 0.05f);
                break;
            case RedConfig.STYLE_DOTS:
                for (int y = py + 8; y < py + ph; y += 16)
                    for (int x = px + 8; x < px + pw; x += 16) rect(x, y, x + 2, y + 2, tTxt, 0.12f);
                break;
            case RedConfig.STYLE_SCAN:
                for (int y = py; y < py + ph; y += 3) rect(px, y, px + pw, y + 1, 0x000000, 0.18f);
                break;
            case RedConfig.STYLE_CHECKER:
                for (int y = py, r = 0; y < py + ph; y += 16, r++)
                    for (int x = px + ((r & 1) == 0 ? 0 : 16); x < px + pw; x += 32)
                        rect(x, y, x + 16, y + 16, tTxt, 0.035f);
                break;
            case RedConfig.STYLE_TILE: {
                PackIndex.Entry t = PackIndex.BY_UID.get(RedConfig.tileUid);
                if (t != null) {
                    PreviewCache.Slot s = PreviewCache.get(t);
                    if (s.ready && s.id != null) {
                        for (int y = py; y < py + ph; y += 32)
                            for (int x = px; x < px + pw; x += 32)
                                c.drawTexture(RenderPipelines.GUI_TEXTURED, s.id, x, y, 0f, 0f, 32, 32,
                                        PreviewCache.T, PreviewCache.T, PreviewCache.T, PreviewCache.T);
                        rect(px, py, px + pw, py + ph, tBg, 0.62f); // abdunkeln, damit alles lesbar bleibt
                    }
                }
                break;
            }
            default:
                break;
        }
        // Seitenleisten + Header
        rect(px, py, px + pw, py + HEADER, tSide, 0.55f);
        rect(px, bodyBot, px + pw, py + ph, tSide, 0.55f);
        if (tab == 0) {
            rect(px, bodyTop, px + sideL, bodyBot, tSide, 0.4f);
            rect(px + pw - sideR, bodyTop, px + pw, bodyBot, tSide, 0.4f);
        }
        c.disableScissor();

        frame(px, py, pw, ph, 1, tAcc, 0.95f);
        rect(px, bodyTop - 1, px + pw, bodyTop, tAcc, 0.55f);
        rect(px, bodyBot, px + pw, bodyBot + 1, tLine, 0.6f);
    }

    private void drawHeader() {
        c.drawText(textRenderer, "RED", px + 12, py + 9, A(tAcc, 1f), true);
        text("MENU", px + 12 + tw("RED") + 4, py + 9, tTxt, 1f);
        text("Texture-Browser · /redmenu", px + 12, py + 21, tMuted, 0.9f);

        String[] names = {"TEXTURES", "HUD"};
        int total = 0;
        int[] wid = new int[2];
        for (int i = 0; i < 2; i++) {
            wid[i] = tw(names[i]) + 26;
            total += wid[i];
        }
        int x = px + pw - 12 - total;
        int ty = py + 7;
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            boolean on = tab == i;
            float hv = hoverAnim("t" + i, over(x, ty, x + wid[i], ty + 22));
            if (on) {
                float tx = x, w = wid[i];
                if (tabW == 0) {
                    tabX = tx;
                    tabW = w;
                }
                tabX += (tx - tabX) * Math.min(1f, dt * 14f);
                tabW += (w - tabW) * Math.min(1f, dt * 14f);
            }
            rrect(x, ty, wid[i], 22, tTxt, 0.04f + 0.08f * hv);
            text(names[i], x + (wid[i] - tw(names[i])) / 2, ty + 7, on ? tTxt : tMuted, 1f);
            zone(x, ty, x + wid[i], ty + 22, () -> {
                if (tab != idx) {
                    tab = idx;
                    RedSounds.tap();
                    setFocused(null);
                }
            }, null, null);
            x += wid[i];
        }
        rect((int) tabX, py + HEADER - 3, (int) (tabX + tabW), py + HEADER - 1, tAcc, 1f);
    }

    // ------------------------------------------------------------------ Tab: Textures

    private void drawTextures() {
        drawCategories();
        drawSearchAndGrid();
        drawSelection();
    }

    private void drawCategories() {
        text("KATEGORIEN", catX, bodyTop + 9, tAcc, 1f);
        int n = PackIndex.CATEGORIES.length;
        catScroll.setMax(n * ROW_CAT - catH);
        int whole = (int) Math.floor(catScroll.cur);
        float frac = catScroll.cur - whole;
        beginClip(catX - 4, catY, catX + catW + 4, catY + catH, frac);
        for (int i = 0; i < n; i++) {
            int y = catY + i * ROW_CAT - whole;
            if (y + ROW_CAT < catY || y > catY + catH) continue;
            boolean on = cat == i;
            float hv = hoverAnim("k" + i, over(catX, y, catX + catW, y + ROW_CAT - 2));
            int bgc = on ? mix(tAcc, tBg, 0.3f) : mix(tTxt, tBg, 0.05f + 0.1f * hv);
            rrect(catX, y, catW, ROW_CAT - 2, bgc, on ? 0.95f : 0.8f);
            if (on) rect(catX, y + 1, catX + 2, y + ROW_CAT - 3, tAcc, 1f);
            text(PackIndex.CATEGORIES[i], catX + 7, y + 5, on ? tTxt : mix(tTxt, tBg, 0.8f), 1f);
            String cn = String.valueOf(counts[i]);
            text(cn, catX + catW - 4 - tw(cn), y + 5, tMuted, 0.9f);
            final int idx = i;
            zone(catX, y, catX + catW, y + ROW_CAT - 2, () -> {
                if (cat != idx) {
                    cat = idx;
                    gridScroll.target = 0;
                    refreshFilter();
                }
                RedSounds.tap();
            }, null, null);
        }
        endClip();
        fade(catX - 4, catY, catX + catW + 4, catY + catH, 6, catScroll, tSide, 0.9f);
    }

    private void fade(int x1, int y1, int x2, int y2, int h, Scroll s, int rgb, float a) {
        if (s.max <= 0) return;
        if (s.cur > 1) c.fillGradient(x1, y1, x2, y1 + h, A(rgb, a), A(rgb, 0f));
        if (s.cur < s.max - 1) c.fillGradient(x1, y2 - h, x2, y2, A(rgb, 0f), A(rgb, a));
    }

    private void drawSearchAndGrid() {
        // Suchfeld
        int fx = gridX, fy = bodyTop + 6, fw = gridW, fh = 18;
        boolean fo = search.isFocused();
        rrect(fx, fy, fw, fh, tTxt, fo ? 0.14f : 0.07f);
        frame(fx, fy, fw, fh, 1, fo ? tAcc : tLine, fo ? 1f : 0.7f);
        // kleine Lupe (ohne Sonderzeichen, damit sie in jeder Schrift klappt)
        frame(fx + 7, fy + 4, 7, 7, 1, tMuted, 1f);
        rect(fx + 13, fy + 10, fx + 15, fy + 12, tMuted, 1f);
        if (query.isEmpty() && !fo) text("Suchen (z. B. crystal, sword, glass) ...", fx + 24, fy + 5, tMuted, 0.8f);

        int n = filtered.size();
        if (n == 0) {
            String l1 = PackIndex.ENTRIES.isEmpty() ? "Noch keine Packs gefunden." : "Keine Texturen gefunden.";
            text(l1, gridX + 4, gridY + 8, tTxt, 0.9f);
            text(fit("Lege Resource Packs (.zip) in redclient/packs und klicke Neu laden.", gridW - 8),
                    gridX + 4, gridY + 22, tMuted, 0.9f);
            return;
        }

        int rowH = cell + LABEL + GAP;
        int rows = (n + cols - 1) / cols;
        int content = rows * rowH - GAP;
        gridScroll.setMax(content - gridH);
        int whole = (int) Math.floor(gridScroll.cur);
        float frac = gridScroll.cur - whole;

        beginClip(gridX, gridY, gridX + gridW, gridY + gridH, frac);
        int r0 = Math.max(0, whole / rowH);
        int r1 = Math.min(rows - 1, (whole + gridH) / rowH);
        for (int r = r0; r <= r1; r++) {
            for (int col = 0; col < cols; col++) {
                int idx = r * cols + col;
                if (idx >= n) break;
                drawCell(filtered.get(idx), gridX + col * (cell + GAP), gridY + r * rowH - whole);
            }
        }
        // Motion-Blur: bei schnellem Scrollen wird das Raster "weggewischt" + Speed-Linien
        float sp = gridScroll.speed(dt);
        float b = RedConfig.clamp(sp / 1800f, 0f, 1f) * RedConfig.blur;
        if (b > 0.03f) {
            rect(gridX, gridY, gridX + gridW, gridY + gridH, tBg, b * 0.5f);
            for (int i = 0; i < 9; i++) {
                int ly = gridY + (int) ((i * 47 + gridScroll.cur * 3.1f) % gridH + gridH) % gridH;
                int lx = gridX + (i * 71) % Math.max(1, areaW - 40);
                rect(lx, ly, lx + 24 + (i * 13) % 40, ly + 1, tAcc, b * 0.5f);
            }
        }
        endClip();
        fade(gridX, gridY, gridX + gridW, gridY + gridH, 8, gridScroll, tBg, 0.95f);

        // Scrollbar
        if (gridScroll.max > 0) {
            int bx = gridX + gridW - 4;
            rect(bx, gridY, bx + 3, gridY + gridH, tTxt, 0.07f);
            int th = Math.max(18, (int) ((float) gridH * gridH / content));
            int ty = gridY + (int) ((gridH - th) * (gridScroll.cur / gridScroll.max));
            boolean hv = over(bx - 3, gridY, bx + 6, gridY + gridH);
            rect(bx, ty, bx + 3, ty + th, tAcc, hv ? 1f : 0.75f);
            final int th2 = th;
            zone(bx - 4, gridY, bx + 7, gridY + gridH, null, null, (mxx, myy) -> {
                float t = RedConfig.clamp((float) ((myy - gridY - th2 / 2.0) / (gridH - th2)), 0f, 1f);
                gridScroll.target = t * gridScroll.max;
            });
        }
    }

    private void drawCell(PackIndex.Entry e, int x, int y) {
        int h = cell + LABEL;
        boolean sl = isSelected(e);
        boolean hov = over(x, y, x + cell, y + h);
        float hv = hoverAnim("c" + e.uid(), hov);
        if (hov) hoverInfo = e.name() + "  ·  " + e.pack() + "  ·  " + e.folder();

        rrect(x, y, cell, h, mix(tCardHi, tCard, hv), 0.92f);
        if (sl) {
            frame(x, y, cell, h, 2, tAcc, 1f);
            rect(x + 2, y + 2, x + cell - 2, y + h - 2, tAcc, 0.10f);
        } else if (hv > 0.02f) {
            frame(x, y, cell, h, 1, tAcc, hv * 0.9f);
        }
        int pad = 4 + (int) (hv * -1f);
        tex(PreviewCache.get(e), x + pad, y + pad, cell - pad * 2);
        text(fit(e.name(), cell - 6), x + 3, y + cell + 1, sl ? tTxt : mix(tTxt, tBg, 0.82f), 1f);

        if (sl) {
            int idx = 1;
            for (PackIndex.Entry o : sel.values()) {
                if (o == e) break;
                idx++;
            }
            String num = String.valueOf(idx);
            int bw = tw(num) + 6;
            rect(x + 3, y + 3, x + 3 + bw, y + 13, tAcc, 1f);
            text(num, x + 6, y + 4, 0xFFFFFF, 1f);
        }
        zone(x, y, x + cell, y + h, () -> toggle(e), () -> marked = e, null);
    }

    private void drawSelection() {
        text("AUSWAHL", selX, bodyTop + 9, tAcc, 1f);
        String cnt = String.valueOf(sel.size());
        int bw = tw(cnt) + 8;
        rrect(selX + selW - bw, bodyTop + 6, bw, 12, tAcc, 1f);
        text(cnt, selX + selW - bw + 4, bodyTop + 8, 0xFFFFFF, 1f);

        List<PackIndex.Entry> list = new ArrayList<>(sel.values());
        if (list.isEmpty()) {
            text("Noch nichts ausgewählt.", selX, selY + 4, tMuted, 0.9f);
            text(fit("Klicke Texturen links an,", selW), selX, selY + 18, tMuted, 0.7f);
            text(fit("dann auf Anwenden.", selW), selX, selY + 30, tMuted, 0.7f);
        }
        selScroll.setMax(list.size() * ROW_SEL - selH);
        int whole = (int) Math.floor(selScroll.cur);
        float frac = selScroll.cur - whole;
        beginClip(selX - 2, selY, selX + selW + 2, selY + selH, frac);
        for (int i = 0; i < list.size(); i++) {
            int y = selY + i * ROW_SEL - whole;
            if (y + ROW_SEL < selY || y > selY + selH) continue;
            PackIndex.Entry e = list.get(i);
            boolean hv = over(selX, y, selX + selW, y + ROW_SEL - 2);
            float h = hoverAnim("s" + e.uid(), hv);
            rrect(selX, y, selW, ROW_SEL - 2, mix(tCardHi, tCard, h), 0.9f);
            rect(selX, y + 1, selX + 2, y + ROW_SEL - 3, tAcc, 1f);
            tex(PreviewCache.get(e), selX + 4, y + 1, ROW_SEL - 4);
            int tx = selX + ROW_SEL + 4;
            text(fit(e.name(), selW - ROW_SEL - 18), tx, y + 4, tTxt, 1f);
            text(fit(e.pack(), selW - ROW_SEL - 18), tx, y + 13, tMuted, 0.8f);
            int xx = selX + selW - 12;
            boolean hx = over(xx, y + 3, xx + 10, y + 14);
            text("x", xx + 2, y + 5, hx ? tAcc : tMuted, 1f);
            zone(xx - 2, y, xx + 12, y + ROW_SEL - 2, () -> {
                sel.remove(e.path());
                RedSounds.deselect();
                saveSelection();
            }, null, null);
            zone(selX, y, selX + selW - 14, y + ROW_SEL - 2, () -> marked = e, () -> {
                sel.remove(e.path());
                RedSounds.deselect();
                saveSelection();
            }, null);
        }
        endClip();
        fade(selX - 2, selY, selX + selW + 2, selY + selH, 6, selScroll, tSide, 0.9f);

        button("clear", selX, selY + selH + 4, selW, 18, "Alle entfernen", !sel.isEmpty(), false, () -> {
            sel.clear();
            saveSelection();
        });
    }

    // ------------------------------------------------------------------ Tab: HUD

    private void drawHud() {
        int ca = hx0, cb = hx0 + colW + 14, cc = hx0 + 2 * (colW + 14);

        // Spalte A: Farben
        text("AKZENTFARBE", ca, hy0, tAcc, 1f);
        swatches(ca, hy0 + 12, colW, ACCENTS, 6, RedConfig.accent, v -> {
            RedConfig.accent = v;
            setHex(hexAccent, v);
        });
        int hexAy = hy0 + 12 + 2 * 18 + 8;
        text("Hex", ca, hexAy + 4, tMuted, 1f);
        fieldFrame(hexAccent, 14);

        int by = hy0 + 86;
        text("HINTERGRUNDFARBE", ca, by, tAcc, 1f);
        swatches(ca, by + 12, colW, BGS, 4, RedConfig.bg, v -> {
            RedConfig.bg = v;
            setHex(hexBg, v);
        });
        int hexBy = by + 12 + 2 * 18 + 8;
        text("Hex", ca, hexBy + 4, tMuted, 1f);
        fieldFrame(hexBg, 14);

        // Spalte B: Menue-Textur
        text("MENÜ-TEXTUR", cb, hy0, tAcc, 1f);
        int y = hy0 + 12;
        for (int i = 0; i < STYLES.length; i++) {
            final int idx = i;
            boolean on = RedConfig.style == i;
            button("st" + i, cb, y, colW, 17, (on ? "> " : "") + STYLES[i], true, on, () -> {
                RedConfig.style = idx;
                RedConfig.save();
            });
            y += 20;
        }
        if (RedConfig.style == RedConfig.STYLE_TILE) {
            PackIndex.Entry t = PackIndex.BY_UID.get(RedConfig.tileUid);
            String info = t == null ? "Keine Textur gewählt" : t.name();
            text(fit(info, colW), cb, y + 2, tMuted, 0.9f);
            final PackIndex.Entry mk = marked;
            button("tilepick", cb, y + 14, colW, 16, "Markierte Textur nutzen", mk != null, false, () -> {
                RedConfig.tileUid = mk.uid();
                RedConfig.save();
            });
        }

        // Spalte C: Feeling
        text("FEELING", cc, hy0, tAcc, 1f);
        int sy = hy0 + 12;
        slider("Deckkraft", cc, sy, colW, (RedConfig.opacity - 0.35f) / 0.65f, v -> {
            RedConfig.opacity = 0.35f + v * 0.65f;
        }, (int) (RedConfig.opacity * 100) + "%");
        sy += 26;
        slider("Scroll-Glätte", cc, sy, colW, RedConfig.smooth, v -> RedConfig.smooth = v,
                (int) (RedConfig.smooth * 100) + "%");
        sy += 26;
        slider("Motion Blur", cc, sy, colW, RedConfig.blur, v -> RedConfig.blur = v,
                (int) (RedConfig.blur * 100) + "%");
        sy += 26;
        slider("Sound-Lautstärke", cc, sy, colW, RedConfig.volume, v -> RedConfig.volume = v,
                (int) (RedConfig.volume * 100) + "%");
        sy += 28;
        button("snd", cc, sy, colW, 17, RedConfig.sounds ? "Tipp-Sounds: AN" : "Tipp-Sounds: AUS", true, RedConfig.sounds,
                () -> {
                    RedConfig.sounds = !RedConfig.sounds;
                    RedConfig.save();
                    if (RedConfig.sounds) RedSounds.select();
                });
    }

    // ------------------------------------------------------------------ Footer

    private void drawFooter() {
        int fy = bodyBot + 6, fh = 18;
        int x = px + 8;
        button("folder", x, fy, 100, fh, "Pack-Ordner öffnen", true, false,
                () -> Util.getOperatingSystem().open(PackIndex.packDir().toFile()));
        x += 106;
        button("reload", x, fy, 74, fh, "Neu laden", true, false, () -> {
            PreviewCache.clear();
            PackIndex.reload();
            restoreSelection();
            refreshFilter();
            say(PackIndex.ENTRIES.size() + " Texturen gefunden.");
        });
        x += 82;

        int doneW = 70, applyW = 96;
        int rx = px + pw - 8 - doneW;
        boolean pend = pending();
        button("done", rx, fy, doneW, fh, "Done", true, !pend && needsReload, this::done);
        button("apply", rx - 6 - applyW, fy, applyW, fh, pend ? "Anwenden" : "Angewendet", pend, pend, this::apply);

        int infoW = rx - 6 - applyW - 10 - x;
        String info;
        float a = 0.9f;
        if (!hoverInfo.isEmpty()) {
            info = hoverInfo;
        } else if (statusTime > 0) {
            info = status;
            a = Math.min(1f, statusTime);
        } else {
            info = filtered.size() + " Texturen  ·  " + sel.size() + " ausgewählt";
        }
        text(fit(info, infoW), x, fy + 5, hoverInfo.isEmpty() && statusTime > 0 ? tAcc : tMuted, a);
    }

    // ------------------------------------------------------------------ Eingabe

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = click.x(), y = click.y();
        int b = click.button();
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (!z.hit(x, y)) continue;
            if (b == 0) {
                if (z.drag() != null) {
                    dragging = z.drag();
                    dragging.to(x, y);
                    return true;
                }
                if (z.left() != null) {
                    setFocused(null);
                    z.left().run();
                    return true;
                }
            } else if (b == 1 && z.right() != null) {
                z.right().run();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging != null) {
            dragging.to(click.x(), click.y());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            dragging = null;
            RedConfig.save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (tab == 0) {
            if (mouseX >= catX && mouseX < catX + catW + 4 && mouseY >= catY && mouseY < catY + catH) {
                catScroll.wheel(vertical, ROW_CAT * 2.5f);
                return true;
            }
            if (mouseX >= selX && mouseX < selX + selW && mouseY >= selY && mouseY < selY + selH) {
                selScroll.wheel(vertical, ROW_SEL * 2f);
                return true;
            }
            if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
                gridScroll.wheel(vertical, (cell + LABEL + GAP) * 0.9f);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        PreviewCache.clear();
        PackIndex.closeAll();
        RedConfig.save();
    }
}
