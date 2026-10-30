package net.azisaba.airpen;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lines are block displays, so an arbitrary RGB color is drawn with the solid block whose average texture
 * color is closest (concrete, wool, terracotta and a few plain blocks; approximate vanilla values).
 */
public final class BlockPalette {
    private static final Map<Material, Integer> COLORS = new LinkedHashMap<>();

    static {
        put(Material.WHITE_CONCRETE, 0xCFD5D6);
        put(Material.LIGHT_GRAY_CONCRETE, 0x7D7D73);
        put(Material.GRAY_CONCRETE, 0x373A3E);
        put(Material.BLACK_CONCRETE, 0x080A0F);
        put(Material.BROWN_CONCRETE, 0x603C20);
        put(Material.RED_CONCRETE, 0x8E2121);
        put(Material.ORANGE_CONCRETE, 0xE06101);
        put(Material.YELLOW_CONCRETE, 0xF1AF15);
        put(Material.LIME_CONCRETE, 0x5EA919);
        put(Material.GREEN_CONCRETE, 0x495B24);
        put(Material.CYAN_CONCRETE, 0x157788);
        put(Material.LIGHT_BLUE_CONCRETE, 0x2489C7);
        put(Material.BLUE_CONCRETE, 0x2D2F8F);
        put(Material.PURPLE_CONCRETE, 0x64209C);
        put(Material.MAGENTA_CONCRETE, 0xA9309F);
        put(Material.PINK_CONCRETE, 0xD5658F);

        put(Material.WHITE_WOOL, 0xEAECED);
        put(Material.LIGHT_GRAY_WOOL, 0x8E8E87);
        put(Material.GRAY_WOOL, 0x3F4448);
        put(Material.BLACK_WOOL, 0x15151A);
        put(Material.BROWN_WOOL, 0x724829);
        put(Material.RED_WOOL, 0xA12723);
        put(Material.ORANGE_WOOL, 0xF17614);
        put(Material.YELLOW_WOOL, 0xF8C628);
        put(Material.LIME_WOOL, 0x70B91A);
        put(Material.GREEN_WOOL, 0x546D1B);
        put(Material.CYAN_WOOL, 0x158A91);
        put(Material.LIGHT_BLUE_WOOL, 0x3AAFD9);
        put(Material.BLUE_WOOL, 0x35399D);
        put(Material.PURPLE_WOOL, 0x7A2AAD);
        put(Material.MAGENTA_WOOL, 0xBE45B4);
        put(Material.PINK_WOOL, 0xEE8DAC);

        put(Material.WHITE_TERRACOTTA, 0xD2B2A1);
        put(Material.LIGHT_GRAY_TERRACOTTA, 0x876B62);
        put(Material.GRAY_TERRACOTTA, 0x3A2A24);
        put(Material.BLACK_TERRACOTTA, 0x251710);
        put(Material.BROWN_TERRACOTTA, 0x4D3324);
        put(Material.RED_TERRACOTTA, 0x8F3D2F);
        put(Material.ORANGE_TERRACOTTA, 0xA25426);
        put(Material.YELLOW_TERRACOTTA, 0xBA8523);
        put(Material.LIME_TERRACOTTA, 0x687635);
        put(Material.GREEN_TERRACOTTA, 0x4C532A);
        put(Material.CYAN_TERRACOTTA, 0x575B5B);
        put(Material.LIGHT_BLUE_TERRACOTTA, 0x716D8A);
        put(Material.BLUE_TERRACOTTA, 0x4A3B5B);
        put(Material.PURPLE_TERRACOTTA, 0x764656);
        put(Material.MAGENTA_TERRACOTTA, 0x96586D);
        put(Material.PINK_TERRACOTTA, 0xA24E4F);
        put(Material.TERRACOTTA, 0x985E43);

        put(Material.SNOW_BLOCK, 0xF9FEFE);
        put(Material.QUARTZ_BLOCK, 0xECE6DF);
        put(Material.GOLD_BLOCK, 0xF6D03D);
        put(Material.EMERALD_BLOCK, 0x2ACB57);
        put(Material.DIAMOND_BLOCK, 0x62EDE4);
        put(Material.LAPIS_BLOCK, 0x1F438C);
        put(Material.REDSTONE_BLOCK, 0xAF1805);
        put(Material.PRISMARINE_BRICKS, 0x63AB9E);
        put(Material.PURPUR_BLOCK, 0xA97DA9);
        put(Material.HONEYCOMB_BLOCK, 0xE5941E);
        put(Material.SEA_LANTERN, 0xACC7BE);
        put(Material.GLOWSTONE, 0xAB8354);
    }

    private BlockPalette() {
    }

    private static void put(Material m, int rgb) {
        COLORS.put(m, rgb);
    }

    /** The block whose color is perceptually closest to {@code rgb} (weighted RGB distance). */
    public static @NotNull Material closest(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        Material best = Material.WHITE_CONCRETE;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<Material, Integer> e : COLORS.entrySet()) {
            int c = e.getValue();
            int dr = r - (c >> 16 & 0xFF), dg = g - (c >> 8 & 0xFF), db = b - (c & 0xFF);
            double rm = (r + (c >> 16 & 0xFF)) / 2.0;
            double d = (2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db;
            if (d < bestDist) {
                bestDist = d;
                best = e.getKey();
            }
        }
        return best;
    }
}
