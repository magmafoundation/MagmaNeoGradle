package net.neoforged.gradle.util;

/**
 * Utility class which holds constants for URLs.
 */
public final class UrlConstants {

    private UrlConstants() {
        throw new IllegalStateException("Can not instantiate an instance of: UrlConstants. This is a utility class");
    }

    public static final String MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest.json";
    public static final String NEO_FORGE_MAVEN = "https://repo.magmafoundation.org/releases/";
    public static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";
}
