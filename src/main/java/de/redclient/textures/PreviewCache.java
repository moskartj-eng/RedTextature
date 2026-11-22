package de.redclient.textures;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Laedt Texturen im Hintergrund und legt sie als echte GPU-Texturen ab (128x128, Seitenverhaeltnis bleibt).
 * Kleine Texturen werden mit ganzzahligem Faktor pixelgenau vergroessert, grosse per Box-Filter verkleinert.
 */
public final class PreviewCache {
    public static final int T = 128;
    private static final int MAX_SLOTS = 700;
    private static final int UPLOADS_PER_FRAME = 8;

    public static final class Slot {
        public volatile boolean ready;
        public volatile boolean failed;
        public Identifier id;
        volatile boolean removed;
        volatile boolean dropped;
        volatile int lastUsed;
        final PackIndex.Entry entry;

        Slot(PackIndex.Entry entry) {
            this.entry = entry;
        }
    }

    private record Decoded(Slot slot, int[] argb) {
    }

    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>(512, 0.75f, true);
    private static final ConcurrentLinkedQueue<Decoded> DONE = new ConcurrentLinkedQueue<>();
    private static ExecutorService pool;
    private static int frame, counter;

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "red-preview");
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }

    /** Fragt die Vorschau an (nur Render-Thread). Gibt sofort einen Slot zurueck; ready wird spaeter true. */
    public static Slot get(PackIndex.Entry e) {
        Slot s = SLOTS.get(e.uid());
        if (s == null) {
            s = new Slot(e);
            SLOTS.put(e.uid(), s);
            submit(s);
        } else if (s.dropped) {
            s.dropped = false;
            submit(s);
        }
        s.lastUsed = frame;
        return s;
    }

    private static void submit(Slot s) {
        s.lastUsed = frame;
        pool().execute(() -> {
            if (s.removed) return;
            if (frame - s.lastUsed > 40) { // Nutzer ist laengst weitergescrollt
                s.dropped = true;
                return;
            }
            try {
                DONE.add(new Decoded(s, decode(s.entry)));
            } catch (Throwable t) {
                s.failed = true;
            }
        });
    }

    /** Einmal pro Frame aufrufen: laedt fertige Bilder auf die GPU und raeumt alte Texturen weg. */
    public static void pump() {
        frame++;
        MinecraftClient mc = MinecraftClient.getInstance();
        int n = 0;
        Decoded d;
        while (n < UPLOADS_PER_FRAME && (d = DONE.poll()) != null) {
            if (d.slot().removed) continue;
            try {
                NativeImage img = new NativeImage(T, T, false);
                int[] a = d.argb();
                for (int y = 0; y < T; y++)
                    for (int x = 0; x < T; x++)
                        img.setColorArgb(x, y, a[y * T + x]);
                NativeImageBackedTexture tex = make(img);
                Identifier id = Identifier.of("redtextures", "p/" + (counter++));
                mc.getTextureManager().registerTexture(id, (AbstractTexture) tex);
                d.slot().id = id;
                d.slot().ready = true;
            } catch (Throwable t) {
                d.slot().failed = true;
            }
            n++;
        }
        if (SLOTS.size() > MAX_SLOTS) {
            Iterator<Map.Entry<String, Slot>> it = SLOTS.entrySet().iterator();
            int drop = SLOTS.size() - MAX_SLOTS + 60;
            while (it.hasNext() && drop-- > 0) {
                Slot s = it.next().getValue();
                if (frame - s.lastUsed < 3) break;
                destroy(mc, s);
                it.remove();
            }
        }
    }

    public static void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Slot s : SLOTS.values()) destroy(mc, s);
        SLOTS.clear();
        DONE.clear();
    }

    private static void destroy(MinecraftClient mc, Slot s) {
        s.removed = true;
        if (s.ready && s.id != null) {
            try {
                mc.getTextureManager().destroyTexture(s.id);
            } catch (Throwable ignored) {
            }
        }
        s.ready = false;
    }

    /** Konstruktor per Signatur suchen, damit es mit allen 1.21.x-Varianten klappt. */
    @SuppressWarnings("unchecked")
    private static NativeImageBackedTexture make(NativeImage img) throws Exception {
        for (Constructor<?> c : NativeImageBackedTexture.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && p[0] == NativeImage.class) return (NativeImageBackedTexture) c.newInstance(img);
            if (p.length == 2 && p[1] == NativeImage.class) {
                if (p[0] == String.class) return (NativeImageBackedTexture) c.newInstance("redtextures", img);
                if (p[0] == Supplier.class)
                    return (NativeImageBackedTexture) c.newInstance((Supplier<String>) () -> "redtextures", img);
            }
        }
        throw new IllegalStateException("kein passender NativeImageBackedTexture-Konstruktor");
    }

    // ------------------------------------------------------------------ Dekodieren (Hintergrund-Thread)

    private static int[] decode(PackIndex.Entry e) throws IOException {
        byte[] data = PackIndex.read(e, e.arch());
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) throw new IOException("kein Bild");
        int w = img.getWidth(), h = img.getHeight();
        int fh = (h > w && h % w == 0) ? w : h; // animierte Streifen: erstes Bild
        int[] src = img.getRGB(0, 0, w, fh, null, 0, w);
        int[] out = new int[T * T];

        if (w <= T && fh <= T) {
            int k = Math.max(1, Math.min(T / w, T / fh));
            int dw = w * k, dh = fh * k, ox = (T - dw) / 2, oy = (T - dh) / 2;
            for (int y = 0; y < dh; y++) {
                int sy = y / k;
                for (int x = 0; x < dw; x++) out[(oy + y) * T + ox + x] = src[sy * w + x / k];
            }
        } else {
            double s = Math.min((double) T / w, (double) T / fh);
            int dw = Math.max(1, (int) Math.round(w * s)), dh = Math.max(1, (int) Math.round(fh * s));
            int ox = (T - dw) / 2, oy = (T - dh) / 2;
            for (int dy = 0; dy < dh; dy++) {
                int sy0 = (int) Math.floor(dy / s);
                int sy1 = Math.min(fh, Math.max(sy0 + 1, (int) Math.ceil((dy + 1) / s)));
                for (int dx = 0; dx < dw; dx++) {
                    int sx0 = (int) Math.floor(dx / s);
                    int sx1 = Math.min(w, Math.max(sx0 + 1, (int) Math.ceil((dx + 1) / s)));
                    long a = 0, r = 0, g = 0, b = 0;
                    int cnt = 0;
                    for (int yy = sy0; yy < sy1; yy++) {
                        for (int xx = sx0; xx < sx1; xx++) {
                            int p = src[yy * w + xx];
                            int pa = p >>> 24;
                            a += pa;
                            r += (long) ((p >> 16) & 255) * pa;
                            g += (long) ((p >> 8) & 255) * pa;
                            b += (long) (p & 255) * pa;
                            cnt++;
                        }
                    }
                    if (a > 0 && cnt > 0) {
                        int oa = (int) (a / cnt);
                        out[(oy + dy) * T + ox + dx] = (oa << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
                    }
                }
            }
        }
        return out;
    }
}
