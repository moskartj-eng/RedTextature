package de.redclient.textures;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Alle Einstellungen (HUD-Look + gemerkte Auswahl). Wird in config/redtextures.json gespeichert. */
public final class RedConfig {
    public static final int STYLE_SMOOTH = 0, STYLE_GRADIENT = 1, STYLE_GRID = 2, STYLE_DOTS = 3,
            STYLE_SCAN = 4, STYLE_CHECKER = 5, STYLE_TILE = 6;

    /** Akzentfarbe (RGB). */
    public static int accent = 0xE02020;
    /** Grundfarbe des Menues (RGB). */
    public static int bg = 0x0B0B0B;
    /** Hintergrund-Textur / Muster des Menues. */
    public static int style = STYLE_GRADIENT;
    /** Deckkraft des Menues 0.35 .. 1. */
    public static float opacity = 0.93f;
    /** Scroll-Glaette 0 (hart) .. 1 (sehr weich). */
    public static float smooth = 0.65f;
    /** Staerke des Motion-Blur-Effekts 0 .. 1. */
    public static float blur = 0.6f;
    public static boolean sounds = true;
    public static float volume = 0.8f;
    /** uid ("pack|pfad") der Textur, die als Menue-Hintergrund gekachelt wird. */
    public static String tileUid = "";

    /** Aktuelle Auswahl (uids) und zuletzt angewendete Auswahl. */
    public static final List<String> selected = new ArrayList<>();
    public static final List<String> applied = new ArrayList<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("redtextures.json");
    }

    /** Wie schnell der Scroll nachzieht (groesser = direkter). */
    public static float scrollK() {
        return 26f - 21f * smooth;
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (o.has("accent")) accent = o.get("accent").getAsInt() & 0xFFFFFF;
            if (o.has("bg")) bg = o.get("bg").getAsInt() & 0xFFFFFF;
            if (o.has("style")) style = Math.max(0, Math.min(6, o.get("style").getAsInt()));
            if (o.has("opacity")) opacity = clamp(o.get("opacity").getAsFloat(), 0.35f, 1f);
            if (o.has("smooth")) smooth = clamp(o.get("smooth").getAsFloat(), 0f, 1f);
            if (o.has("blur")) blur = clamp(o.get("blur").getAsFloat(), 0f, 1f);
            if (o.has("sounds")) sounds = o.get("sounds").getAsBoolean();
            if (o.has("volume")) volume = clamp(o.get("volume").getAsFloat(), 0f, 1f);
            if (o.has("tile")) tileUid = o.get("tile").getAsString();
            readList(o, "selected", selected);
            readList(o, "applied", applied);
        } catch (Exception ignored) {
        }
    }

    private static void readList(JsonObject o, String key, List<String> out) {
        out.clear();
        if (!o.has(key)) return;
        for (JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsString());
    }

    public static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("accent", accent);
            o.addProperty("bg", bg);
            o.addProperty("style", style);
            o.addProperty("opacity", opacity);
            o.addProperty("smooth", smooth);
            o.addProperty("blur", blur);
            o.addProperty("sounds", sounds);
            o.addProperty("volume", volume);
            o.addProperty("tile", tileUid);
            o.add("selected", arr(selected));
            o.add("applied", arr(applied));
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception ignored) {
        }
    }

    private static JsonArray arr(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }

    public static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
