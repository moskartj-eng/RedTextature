package de.redclient.textures;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** Kleine Klick-/Tipp-Sounds fuer das Menue (nutzt nur Vanilla-Sounds, kein Resource Pack noetig). */
public final class RedSounds {
    private static long lastTick;

    private static void play(RegistryEntry<SoundEvent> s, float pitch, float vol) {
        if (!RedConfig.sounds) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getSoundManager().play(PositionedSoundInstance.master(s, pitch, vol * RedConfig.volume));
    }

    /** Tab / Kategorie / Button. */
    public static void tap() {
        play(SoundEvents.UI_BUTTON_CLICK, 1.35f, 0.9f);
        play(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 1.9f, 0.5f);
    }

    public static void select() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 1.7f, 0.8f);
        play(SoundEvents.UI_BUTTON_CLICK, 1.6f, 0.5f);
    }

    public static void deselect() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 1.1f, 0.9f);
    }

    public static void apply() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.7f);
        play(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.5f, 0.7f);
        play(SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 2.0f, 0.7f);
    }

    public static void done() {
        play(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.2f, 0.9f);
        play(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.8f, 0.5f);
    }

    /** Fuer Slider: hoeherer Wert = hoeherer Ton, mit Drosselung. */
    public static void tick(float value) {
        long now = System.currentTimeMillis();
        if (now - lastTick < 55) return;
        lastTick = now;
        play(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 0.8f + value * 1.1f, 0.6f);
    }
}
