package de.redclient.textures;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Baut aus der Auswahl ein Resource Pack (als Ordner, damit es unter Windows nie gesperrt ist),
 * aktiviert es ganz oben und laedt alle Resource Packs neu.
 */
public final class RedPackBuilder {
    public static final String NAME = "RedClient_Selection";

    private static final String MCMETA = "{\n  \"pack\": {\n"
            + "    \"description\": \"Red Client - deine ausgewaehlten Texturen\",\n"
            + "    \"pack_format\": 34,\n"
            + "    \"supported_formats\": [34, 99],\n"
            + "    \"min_format\": 34,\n"
            + "    \"max_format\": 99\n"
            + "  }\n}\n";

    public static String id() {
        return "file/" + NAME;
    }

    private static Path dir(MinecraftClient mc) {
        return mc.getResourcePackDir().resolve(NAME);
    }

    /** Schreibt das Pack. Gibt die Anzahl der Texturen zurueck (0 = Pack wurde entfernt). */
    public static int write(MinecraftClient mc, Collection<PackIndex.Entry> sel) throws IOException {
        Path root = dir(mc);
        deleteRecursive(root);
        if (sel.isEmpty()) return 0;
        Files.createDirectories(root);
        Files.writeString(root.resolve("pack.mcmeta"), MCMETA, StandardCharsets.UTF_8);
        int n = 0;
        for (PackIndex.Entry e : sel) {
            byte[] png = PackIndex.read(e, e.arch());
            Path out = root.resolve(e.path());
            Files.createDirectories(out.getParent());
            Files.write(out, png);
            byte[] meta = PackIndex.readOptional(e, e.arch() + ".mcmeta"); // Animationen bleiben erhalten
            if (meta != null) Files.write(root.resolve(e.path() + ".mcmeta"), meta);
            n++;
        }
        return n;
    }

    /** Aktiviert (oder deaktiviert) das Pack und laedt alle Resource Packs neu. */
    public static void enableAndReload(MinecraftClient mc, boolean hasSelection) {
        ResourcePackManager m = mc.getResourcePackManager();
        m.scanPacks();
        List<String> enabled = new ArrayList<>(m.getEnabledIds());
        boolean was = enabled.contains(id());
        enabled.remove(id());
        boolean now = hasSelection && m.getProfile(id()) != null;
        if (now) enabled.add(id()); // letzter Eintrag = hoechste Prioritaet
        m.setEnabledProfiles(enabled);
        if (was == now) {
            mc.reloadResources(); // Liste unveraendert, aber Inhalt neu -> erzwingen
        } else {
            mc.options.refreshResourcePacks(m); // speichert options.txt und laedt neu
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> w = Files.walk(p)) {
            for (Path f : (Iterable<Path>) w.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(f);
        }
    }
}
