package net.azisaba.airpen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pen distance picker: a horizontal bar in front of the player (near end left, far end right). A red dot sits
 * exactly where the pen tip would be with the picked distance, on the player's line of sight. Below the bar a dotted
 * ruler runs from near to far (one dot per tenth, labelled every half block) with a red marker at the picked
 * distance: its perspective shows the depth that a lone dot cannot. Left-click while looking at the bar applies it;
 * looking away from the bar cancels. Distances are whole tenths of a block on a linear bar.
 */
public final class DistanceMenu implements PenMenu {
    private static final float S = 1.3f;
    private static final float HALF_LENGTH = 0.8f * S;
    /** Looking farther than this above/below the bar (or past its ends) cancels. */
    private static final double CANCEL_DISTANCE = 0.3 * S;
    private static final int LINE_ARGB = 0xB0FFFFFF;
    private static final float LINE_WIDTH = 0.012f * S;
    private static final int MARKER_ARGB = 0xFFFFC040;
    private static final float MARKER_WIDTH = 0.02f * S;
    private static final float MARKER_HALF_HEIGHT = 0.07f * S;
    private static final int TIP_ARGB = 0xFFFF2020;
    /** Real size of the red tip dot (blocks): it looks bigger the nearer it is. */
    private static final float TIP_SIZE = 0.06f;
    private static final Color BACKGROUND = Color.fromARGB(0x90, 0x10, 0x10, 0x10);
    /** The ruler runs this far below the eyes: low enough to clear the bar, high enough to stay on screen up close. */
    private static final double RULER_DROP = 0.25;
    private static final int RULER_ARGB = 0xB0FFFFFF;
    private static final float RULER_DOT = 0.02f;
    private static final float RULER_MAJOR_DOT = 0.04f;
    private static final float RULER_MARKER = 0.06f;
    private static final float RULER_LABEL_SCALE = 0.2f;

    private final AirPenPlugin plugin;
    private final Player player;
    private final int minLevel;
    private final int maxLevel;
    private final int initialLevel;
    private final Consumer<Double> onSet;
    private final Location center;
    private final Vector right;
    private final Vector up;
    private final Vector normal;
    private final Location plane;
    private final Location eye;
    private final List<TextDisplay> parts = new ArrayList<>();
    private final List<TextDisplay> marker = new ArrayList<>(2);
    /** Red dot where the pen would draw. */
    private final TextDisplay tip;
    /** Red marker on the ruler at the picked distance. */
    private final TextDisplay rulerMarker;
    private final TextDisplay value;
    private final long openedAt = System.currentTimeMillis();
    /** Level under the gaze, or -1 when looking away from the bar (cancel). */
    private int hovered;
    private int shownLevel = -2;

    /** {@code origin}: eye position and direction the menu is laid out from; a submenu gets its parent's so it opens in the same place. */
    public DistanceMenu(@NotNull AirPenPlugin plugin, @NotNull Player player, @NotNull Location origin, double current, double min, double max,
                        @NotNull Consumer<Double> onSet) {
        this.plugin = plugin;
        this.player = player;
        this.minLevel = toLevel(min);
        this.maxLevel = Math.max(minLevel + 1, toLevel(max));
        this.initialLevel = Math.max(minLevel, Math.min(maxLevel, toLevel(current)));
        this.onSet = onSet;
        AirPenConfig config = plugin.config();
        this.eye = origin.clone();
        this.normal = eye.getDirection().normalize();
        Vector r = normal.clone().crossProduct(new Vector(0, 1, 0));
        if (r.lengthSquared() < 1e-6) r = new Vector(1, 0, 0);
        this.right = r.normalize();
        this.up = right.clone().crossProduct(normal).normalize();
        this.center = eye.clone().add(normal.clone().multiply(config.menuDistance()));
        this.hovered = initialLevel;

        this.plane = center.clone().add(normal.clone().multiply(0.03));
        line(plane, right.clone().multiply(-HALF_LENGTH), right.clone().multiply(HALF_LENGTH), config);
        for (int level = minLevel; level <= maxLevel; level++) { // a tick every half block
            if (level % 5 != 0 && level != minLevel && level != maxLevel) continue;
            double height = level % 10 == 0 ? 0.05 : 0.03;
            Vector at = right.clone().multiply(barX(level));
            line(plane, at.clone().add(up.clone().multiply(-height * S)), at.clone().add(up.clone().multiply(height * S)), config);
        }
        // Explanations go above the bar: below it is the ruler
        parts.add(label(at(0, 0.78), Component.text("左右で調整して左クリックで決定 / バーから外して左クリックでキャンセル", NamedTextColor.GRAY), 0.25f * S));
        parts.add(label(at(0, 0.64), Component.text("赤い点の位置に描けます(下の目盛りは手前から奥への距離)", NamedTextColor.RED), 0.25f * S));
        parts.add(label(at(0, 0.45), Component.text("描く位置", NamedTextColor.GOLD), 0.4f * S));
        parts.add(label(at(-HALF_LENGTH / S, -0.13), Component.text(format(minLevel) + " (近い)", NamedTextColor.GRAY), 0.3f * S));
        parts.add(label(at(HALF_LENGTH / S, -0.13), Component.text(format(maxLevel) + " (遠い)", NamedTextColor.GRAY), 0.3f * S));
        value = label(at(0, 0.24), Component.empty(), 0.4f * S);
        parts.add(value);

        Vector markerDirection = up.clone().multiply(2 * MARKER_HALF_HEIGHT);
        Vector facingPlayer = normal.clone().multiply(-1);
        Location markerAt = markerLocation(initialLevel);
        marker.add(LineDisplays.asMenu(LineDisplays.ribbon(markerAt, markerDirection, MARKER_WIDTH, facingPlayer, MARKER_ARGB, false, config)));
        marker.add(LineDisplays.asMenu(LineDisplays.ribbon(markerAt, markerDirection, MARKER_WIDTH, facingPlayer, MARKER_ARGB, true, config)));
        for (TextDisplay m : marker) {
            m.setTeleportDuration(1);
            player.showEntity(plugin, m);
        }
        parts.addAll(marker);

        tip = LineDisplays.asMenu(LineDisplays.dot(tipLocation(initialLevel), TIP_SIZE, TIP_ARGB, config));
        tip.setTeleportDuration(1);
        player.showEntity(plugin, tip);
        parts.add(tip);

        for (int level = minLevel; level <= maxLevel; level++) {
            boolean major = level % 5 == 0 || level == minLevel || level == maxLevel;
            parts.add(dot(rulerLocation(level), major ? RULER_MAJOR_DOT : RULER_DOT, RULER_ARGB, config));
            if (major) {
                // Same real size at every distance, so farther labels look smaller like the dots do
                parts.add(label(rulerLocation(level).add(right.clone().multiply(0.07)),
                        Component.text(format(level), NamedTextColor.WHITE), RULER_LABEL_SCALE));
            }
        }
        rulerMarker = dot(rulerLocation(initialLevel), RULER_MARKER, TIP_ARGB, config);
        rulerMarker.setTeleportDuration(1);
        parts.add(rulerMarker);
        show(initialLevel);
    }

    /** Whole level: tenths of a block. */
    public static int toLevel(double distance) {
        return (int) Math.round(distance * 10);
    }

    private static double toDistance(int level) {
        return level / 10.0;
    }

    public static String format(double distance) {
        return String.format("%.1f", distance);
    }

    private static String format(int level) {
        return format(toDistance(level));
    }

    /** Offset along the bar (from its centre) of a level. */
    private double barX(int level) {
        return -HALF_LENGTH + 2 * HALF_LENGTH * (level - minLevel) / (double) (maxLevel - minLevel);
    }

    private Location at(double x, double y) {
        return center.clone().add(right.clone().multiply(x * S)).add(up.clone().multiply(y * S));
    }

    /** Bottom end of the marker over {@code level}; unrotated, since a display turns by its own yaw/pitch too. */
    private Location markerLocation(int level) {
        Location location = plane.clone().add(right.clone().multiply(barX(level))).add(up.clone().multiply(-MARKER_HALF_HEIGHT));
        location.setYaw(0);
        location.setPitch(0);
        return location;
    }

    /** Point on the ruler for {@code level}: that far ahead of the eyes (as the menu was opened), a little lower. */
    private Location rulerLocation(int level) {
        Location location = eye.clone().add(normal.clone().multiply(toDistance(level))).add(up.clone().multiply(-RULER_DROP));
        location.setYaw(0);
        location.setPitch(0);
        return location;
    }

    private TextDisplay dot(Location at, float size, int argb, AirPenConfig config) {
        TextDisplay display = LineDisplays.asMenu(LineDisplays.dot(at, size, argb, config));
        player.showEntity(plugin, display);
        return display;
    }

    /** Where the pen tip would be right now with {@code level}: on the current line of sight. */
    private Location tipLocation(int level) {
        Vector at = PenController.tipAt(player, toDistance(level));
        return at.toLocation(player.getWorld());
    }

    @Override
    public void tick() {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double facing = dir.dot(normal);
        int level = -1;
        if (facing > 0.2) {
            double t = center.toVector().subtract(eye.toVector()).dot(normal) / facing;
            Vector local = eye.toVector().add(dir.multiply(t)).subtract(center.toVector());
            double x = local.dot(right), y = local.dot(up);
            if (Math.abs(x) <= HALF_LENGTH + CANCEL_DISTANCE && Math.abs(y) <= CANCEL_DISTANCE) {
                double fraction = Math.max(0, Math.min(1, (x + HALF_LENGTH) / (2 * HALF_LENGTH)));
                level = (int) Math.round(minLevel + fraction * (maxLevel - minLevel));
            }
        }
        hovered = level;
        show(level);
        // The tip follows the gaze every tick, not only when the level changes
        if (tip.isValid()) tip.teleport(tipLocation(level < 0 ? initialLevel : level));
    }

    private void show(int level) {
        if (level == shownLevel) return;
        shownLevel = level;
        int shown = level < 0 ? initialLevel : level;
        Location markerAt = markerLocation(shown);
        for (TextDisplay m : marker) {
            // The marker points up, so its display sits one marker-width below its start (see LineDisplays)
            if (m.isValid()) m.teleport(markerAt.clone().subtract(0, MARKER_WIDTH, 0));
        }
        if (rulerMarker.isValid()) rulerMarker.teleport(rulerLocation(shown));
        if (value.isValid()) {
            value.text(level < 0
                    ? Component.text("キャンセル", NamedTextColor.GRAY)
                    : Component.text(format(level) + " ブロック", NamedTextColor.WHITE));
        }
    }

    @Override
    public @NotNull String release() {
        if (hovered < 0) return "キャンセル";
        onSet.accept(toDistance(hovered));
        return "距離" + format(hovered);
    }

    private void line(Location plane, Vector from, Vector to, AirPenConfig config) {
        Location at = plane.clone().add(from);
        Vector direction = to.clone().subtract(from);
        Vector facingPlayer = normal.clone().multiply(-1);
        List<TextDisplay> sides = new ArrayList<>(2);
        sides.add(LineDisplays.asMenu(LineDisplays.ribbon(at, direction, LINE_WIDTH, facingPlayer, LINE_ARGB, false, config)));
        if (config.doubleSided()) sides.add(LineDisplays.asMenu(LineDisplays.ribbon(at, direction, LINE_WIDTH, facingPlayer, LINE_ARGB, true, config)));
        sides.forEach(d -> player.showEntity(plugin, d));
        parts.addAll(sides);
    }

    private TextDisplay label(Location at, Component text, float scale) {
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, e -> {
            e.addScoreboardTag(LineDisplays.TAG_MENU);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.text(text);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(BACKGROUND);
            e.setSeeThrough(true);
            e.setShadowed(true);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
        player.showEntity(plugin, display);
        return display;
    }

    @Override
    public boolean isStale() {
        Location eye = player.getEyeLocation();
        return !eye.getWorld().equals(center.getWorld()) || eye.distanceSquared(center) > 9;
    }

    @Override
    public boolean isBroken() {
        for (TextDisplay display : parts) {
            if (!display.isValid()) return true;
        }
        return false;
    }

    @Override
    public long idleFor() {
        return System.currentTimeMillis() - openedAt;
    }

    @Override
    public void close() {
        parts.forEach(TextDisplay::remove);
        parts.clear();
    }
}
