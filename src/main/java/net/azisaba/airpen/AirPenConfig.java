package net.azisaba.airpen;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Immutable snapshot of config.yml. */
public record AirPenConfig(
        Material penMaterial,
        String penName,
        List<String> penLore,
        int penModelData,
        double distance,
        double minStep,
        double mergeAngleDeg,
        double maxSegmentLength,
        int maxSegmentsPerPlayer,
        double smoothing,
        double eraserRange,
        double eraserRadius,
        double autoClearDistance,
        /** Display view range: the client stops rendering ink farther than {@code view-distance} blocks. */
        float viewRange,
        boolean fullBright,
        Style style,
        double dotSpacing,
        LineGeometry.RibbonBase ribbonBase,
        boolean doubleSided,
        /** Legacy width presets, only used to convert old saved choices. */
        List<NamedValue<Float>> widths,
        float widthMin,
        float widthMax,
        float widthDefault,
        List<NamedValue<Integer>> colors,
        double menuDistance,
        double menuRadius,
        double menuCenterRadius
) {
    public record NamedValue<T>(String name, T value) {
    }

    /** Blocks a display with view range 1.0 is rendered at (before the client's entity distance setting). */
    private static final double VANILLA_VIEW_DISTANCE = 64.0;

    /** How lines are rendered. */
    public enum Style {
        /** Camera-facing squares stamped along the path: visible from every side, erasable piece by piece. */
        DOTS,
        /** Flat strips facing the drawer, two displays per straight piece. */
        RIBBON,
        /** Stretched block displays (closest block color). */
        BAR;

        static Style parse(String name) {
            for (Style style : values()) {
                if (style.name().equalsIgnoreCase(name)) return style;
            }
            return DOTS;
        }
    }

    public static @NotNull AirPenConfig load(@NotNull FileConfiguration c) {
        Material material = Material.matchMaterial(c.getString("pen.material", "BLAZE_ROD"));
        if (material == null || !material.isItem()) material = Material.BLAZE_ROD;

        List<NamedValue<Float>> widths = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("widths")) {
            widths.add(new NamedValue<>(String.valueOf(m.get("name")), ((Number) m.get("value")).floatValue()));
        }
        if (widths.isEmpty()) widths.add(new NamedValue<>("中", 0.06f));

        List<NamedValue<Integer>> colors = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("colors")) {
            Integer rgb = parseHex(String.valueOf(m.get("hex")));
            if (rgb != null) colors.add(new NamedValue<>(String.valueOf(m.get("name")), rgb));
        }
        if (colors.isEmpty()) colors.add(new NamedValue<>("白", 0xF0F0F0));

        ConfigurationSection d = c.getConfigurationSection("drawing");
        if (d == null) d = c.createSection("drawing");
        return new AirPenConfig(
                material,
                c.getString("pen.name", "<gold>エアペン"),
                c.getStringList("pen.lore"),
                c.getInt("pen.custom-model-data", 0),
                clamp(d.getDouble("distance", 2.0), 1.0, 4.3),
                Math.max(0.01, d.getDouble("min-step", 0.03)),
                d.getDouble("merge-angle", 4.0),
                Math.max(0.2, d.getDouble("max-segment-length", 4.0)),
                Math.max(10, d.getInt("max-segments-per-player", 2000)),
                clamp(d.getDouble("smoothing", 0.5), 0.05, 1.0),
                Math.max(1, c.getDouble("eraser.range", 4)),
                Math.max(0.02, c.getDouble("eraser.radius", 0.1)),
                c.getDouble("auto-clear-distance", 100),
                (float) (Math.max(1, d.getDouble("view-distance", 50)) / VANILLA_VIEW_DISTANCE),
                d.getBoolean("full-bright", true),
                Style.parse(d.getString("style", "ribbon")),
                clamp(d.getDouble("dot-spacing", 0.5), 0.1, 2.0),
                new LineGeometry.RibbonBase(
                        (float) c.getDouble("ribbon.base-x", -0.05),
                        (float) c.getDouble("ribbon.base-y", 0.0),
                        (float) Math.max(0.001, c.getDouble("ribbon.base-width", 0.125)),
                        (float) Math.max(0.001, c.getDouble("ribbon.base-height", 0.275))),
                c.getBoolean("ribbon.double-sided", true),
                List.copyOf(widths),
                (float) Math.max(0.005, c.getDouble("width.min", 0.01)),
                (float) Math.max(c.getDouble("width.min", 0.01) + 0.001, c.getDouble("width.max", 0.30)),
                (float) c.getDouble("width.default", 0.04),
                List.copyOf(colors),
                c.getDouble("menu.distance", 1.8),
                c.getDouble("menu.radius", 1.2),
                c.getDouble("menu.center-radius", 0.38));
    }

    /** Parses {@code #RRGGBB} / {@code RRGGBB}; null when malformed. */
    public static Integer parseHex(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (!s.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(s, 16);
    }

    public static String toHex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
