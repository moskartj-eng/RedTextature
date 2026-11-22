package de.redclient.textures;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Liest alle Packs aus .minecraft/redclient/packs und indexiert deren Texturen. */
public final class PackIndex {
    /**
     * @param path   Zielpfad im Spiel, z. B. assets/minecraft/textures/item/diamond_sword.png
     * @param arch   Pfad innerhalb des Packs (kann einen Wurzelordner enthalten)
     * @param folder Unterordner unterhalb von textures/, z. B. item oder entity/equipment
     */
    public record Entry(String pack, String path, String arch, String name, String folder,
                        String category, Path source, boolean zip) {
        public String uid() {
            return pack + "|" + path;
        }
    }

    public static final String[] CATEGORIES = {
            "Alle", "Crystals", "Totems", "Swords", "Axes", "Pickaxes", "Shovels", "Hoes", "Ranged",
            "Armor", "Shields", "Food", "Potions", "Gems", "Wood", "Stone", "Glass", "Wool", "Blocks",
            "Mobs", "Particles", "Environment", "GUI", "Items", "Misc"
    };

    public static final List<Entry> ENTRIES = new ArrayList<>();
    public static final Map<String, Entry> BY_UID = new HashMap<>();

    private static final String PREFIX = "assets/minecraft/textures/";
    private static final Set<String> ALLOWED = Set.of("item", "block", "entity", "particle", "environment",
            "gui", "painting", "mob_effect");
    private static final Map<Path, ZipFile> ZIPS = new ConcurrentHashMap<>();

    public static Path packDir() {
        return FabricLoader.getInstance().getGameDir().resolve("redclient").resolve("packs");
    }

    public static int categoryIndex(String cat) {
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(cat)) return i;
        return CATEGORIES.length - 1;
    }

    public static void reload() {
        closeAll();
        ENTRIES.clear();
        BY_UID.clear();
        try {
            Files.createDirectories(packDir());
        } catch (IOException e) {
            return;
        }
        try (Stream<Path> s = Files.list(packDir())) {
            s.sorted().forEach(p -> {
                try {
                    if (Files.isDirectory(p)) scanDir(p);
                    else if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) scanZip(p);
                } catch (Exception ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        ENTRIES.sort(Comparator.comparing((Entry e) -> e.name().toLowerCase(Locale.ROOT)).thenComparing(Entry::pack));
        for (Entry e : ENTRIES) BY_UID.put(e.uid(), e);
    }

    public static synchronized void closeAll() {
        for (ZipFile z : ZIPS.values()) {
            try {
                z.close();
            } catch (IOException ignored) {
            }
        }
        ZIPS.clear();
    }

    private static void scanZip(Path p) throws IOException {
        String pack = p.getFileName().toString();
        try (ZipFile z = new ZipFile(p.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) add(pack, e.getName().replace('\\', '/'), p, true);
            }
        }
    }

    private static void scanDir(Path dir) throws IOException {
        String pack = dir.getFileName().toString();
        try (Stream<Path> w = Files.walk(dir, 12)) {
            w.filter(Files::isRegularFile).forEach(f ->
                    add(pack, dir.relativize(f).toString().replace('\\', '/'), dir, false));
        }
    }

    private static void add(String pack, String arch, Path source, boolean zip) {
        if (!arch.toLowerCase(Locale.ROOT).endsWith(".png")) return;
        int i = arch.indexOf(PREFIX);
        if (i < 0 || (i > 0 && arch.charAt(i - 1) != '/')) return;
        String path = arch.substring(i);
        String rel = path.substring(PREFIX.length());
        int first = rel.indexOf('/');
        if (first < 0) return;
        String sub = rel.substring(0, first);
        if (!ALLOWED.contains(sub)) return;
        int last = rel.lastIndexOf('/');
        String name = rel.substring(last + 1, rel.length() - 4);
        String folder = rel.substring(0, last);
        ENTRIES.add(new Entry(pack, path, arch, name, folder,
                classify(sub, folder.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)), source, zip));
    }

    // ------------------------------------------------------------------ Kategorien

    private static final Set<String> FOOD = Set.of("apple", "golden_apple", "enchanted_golden_apple", "bread",
            "cookie", "cake", "pumpkin_pie", "carrot", "golden_carrot", "potato", "baked_potato",
            "poisonous_potato", "beetroot", "beetroot_soup", "mushroom_stew", "rabbit_stew", "suspicious_stew",
            "melon_slice", "sweet_berries", "glow_berries", "chorus_fruit", "dried_kelp", "honey_bottle",
            "cooked_beef", "beef", "cooked_porkchop", "porkchop", "cooked_chicken", "chicken", "cooked_mutton",
            "mutton", "cooked_rabbit", "rabbit", "cooked_cod", "cod", "cooked_salmon", "salmon", "tropical_fish",
            "pufferfish", "rotten_flesh", "spider_eye");

    private static final Set<String> GEMS = Set.of("diamond", "emerald", "coal", "charcoal", "lapis_lazuli",
            "redstone", "quartz", "amethyst_shard", "netherite_scrap", "diamond_block", "emerald_block",
            "gold_block", "iron_block", "copper_block", "netherite_block", "lapis_block", "redstone_block",
            "coal_block", "quartz_block", "amethyst_block");

    private static boolean has(String n, String... parts) {
        for (String p : parts) if (n.contains(p)) return true;
        return false;
    }

    private static String classify(String sub, String folder, String n) {
        switch (sub) {
            case "particle":
                return "Particles";
            case "environment":
                return "Environment";
            case "gui":
                return "GUI";
            case "painting":
            case "mob_effect":
                return "Misc";
            default:
                break;
        }
        if (n.contains("crystal") && !n.contains("amethyst")) return "Crystals";
        if (n.contains("totem")) return "Totems";
        boolean armorStand = n.contains("armor_stand");
        if (!armorStand && (n.endsWith("_helmet") || n.endsWith("_chestplate") || n.endsWith("_leggings")
                || n.endsWith("_boots") || n.contains("elytra") || n.contains("armor") || folder.contains("equipment")))
            return "Armor";
        if (n.endsWith("_sword")) return "Swords";
        if (n.endsWith("_pickaxe")) return "Pickaxes";
        if (n.endsWith("_axe")) return "Axes";
        if (n.endsWith("_shovel")) return "Shovels";
        if (n.endsWith("_hoe")) return "Hoes";
        if (n.equals("bow") || n.startsWith("bow_") || n.equals("crossbow") || n.startsWith("crossbow")
                || n.contains("arrow") || n.contains("trident") || n.equals("mace") || n.equals("wind_charge"))
            return "Ranged";
        if (n.contains("shield")) return "Shields";
        if (FOOD.contains(n)) return "Food";
        if (n.contains("potion") || n.contains("bottle")) return "Potions";
        if (GEMS.contains(n) || n.contains("_ore") || n.endsWith("_ingot") || n.endsWith("_nugget") || n.startsWith("raw_"))
            return "Gems";
        if (sub.equals("block")) {
            if (has(n, "log", "planks", "stripped_", "hyphae", "stem") || n.endsWith("_wood")) return "Wood";
            if (n.contains("glass")) return "Glass";
            if (has(n, "wool", "carpet", "concrete", "terracotta")) return "Wool";
            if (has(n, "stone", "cobble", "deepslate", "brick", "andesite", "granite", "diorite", "basalt",
                    "blackstone", "tuff", "obsidian")) return "Stone";
            return "Blocks";
        }
        if (sub.equals("entity")) return "Mobs";
        return "Items";
    }

    // ------------------------------------------------------------------ Dateizugriff

    private static ZipFile zip(Path p) throws IOException {
        ZipFile z = ZIPS.get(p);
        if (z != null) return z;
        synchronized (PackIndex.class) {
            z = ZIPS.get(p);
            if (z == null) {
                z = new ZipFile(p.toFile());
                ZIPS.put(p, z);
            }
            return z;
        }
    }

    /** Liest eine Datei des Packs (Textur in Originalgroesse). Wirft IOException, falls sie fehlt. */
    public static byte[] read(Entry e, String archPath) throws IOException {
        byte[] b = readOptional(e, archPath);
        if (b == null) throw new IOException("Eintrag fehlt: " + archPath);
        return b;
    }

    /** Wie {@link #read}, liefert aber null wenn die Datei nicht existiert (z. B. .mcmeta). */
    public static byte[] readOptional(Entry e, String archPath) throws IOException {
        if (!e.zip()) {
            Path f = e.source().resolve(archPath);
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        }
        ZipFile z = zip(e.source());
        ZipEntry ze = z.getEntry(archPath);
        if (ze == null) return null;
        try (InputStream in = z.getInputStream(ze)) {
            return in.readAllBytes();
        }
    }
}
