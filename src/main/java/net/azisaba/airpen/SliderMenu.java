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
 * Width picker: a horizontal bar in front of the player. Looking along the bar picks the width, shown as a dot of
 * exactly that size in the current color; letting go applies it, letting go while looking away from the bar cancels.
 * Widths are whole "levels" (hundredths of a block) on a logarithmic bar, so thin widths get more room.
 */
public final class SliderMenu implements PenMenu {
    /** Overall size of the slider relative to the radial menu's proportions. */
    private static final float S = 1.3f;
    private static final float HALF_LENGTH = 0.8f * S;
    /** Looking farther than this above/below the bar (or past its ends) cancels. */
    private static final double CANCEL_DISTANCE = 0.3 * S;
    /** Below the bar down to this distance is the preview: looking there neither changes nor cancels the pick. */
    private static final double PREVIEW_AREA = 0.75 * S;
    private static final int LINE_ARGB = 0xB0FFFFFF;
    private static final float LINE_WIDTH = 0.012f * S;
    private static final int MARKER_ARGB = 0xFFFFC040;
    private static final float MARKER_WIDTH = 0.02f * S;
    private static final float MARKER_HALF_HEIGHT = 0.07f * S;
    private static final Color BACKGROUND = Color.fromARGB(0x90, 0x10, 0x10, 0x10);

    private final AirPenPlugin plugin;
    private final Player player;
    private final int minLevel;
    private final int maxLevel;
    private final int initialLevel;
    private final int color;
    private final Consumer<Float> onSet;
    private final Location center;
    private final Vector right;
    private final Vector up;
    private final Vector normal;
    private final List<TextDisplay> parts = new ArrayList<>();
    /** Position marker on the bar (front and back side). */
    private final List<TextDisplay> marker = new ArrayList<>(2);
    /** Real-size dot of the picked width, below the bar. */
    private final TextDisplay preview;
    private final TextDisplay value;
    private final Location plane;
    private final long openedAt = System.currentTimeMillis();
    /** Level under the gaze, or -1 when looking away from the bar (cancel). */
    private int hovered;
    private int shownLevel = -2;

    public SliderMenu(@NotNull AirPenPlugin plugin, @NotNull Player player, float current, float min, float max,
                      int color, @NotNull Consumer<Float> onSet) {
        this.plugin = plugin;
        this.player = player;
        this.minLevel = Math.max(1, toLevel(min));
        this.maxLevel = Math.max(minLevel + 1, toLevel(max));
        this.initialLevel = Math.max(minLevel, Math.min(maxLevel, toLevel(current)));
        this.color = color;
        this.onSet = onSet;
        AirPenConfig config = plugin.config();
        Location eye = player.getEyeLocation();
        this.normal = eye.getDirection().normalize();
        Vector r = normal.clone().crossProduct(new Vector(0, 1, 0));
        if (r.lengthSquared() < 1e-6) r = new Vector(1, 0, 0);
        this.right = r.normalize();
        this.up = right.clone().crossProduct(normal).normalize();
        this.center = eye.clone().add(normal.clone().multiply(config.menuDistance()));
        this.hovered = initialLevel;

        this.plane = center.clone().add(normal.clone().multiply(0.03));
        line(plane, right.clone().multiply(-HALF_LENGTH), right.clone().multiply(HALF_LENGTH), config);
        for (int i = 0; i <= 4; i++) { // ticks at the ends and quarters
            Vector at = right.clone().multiply(-HALF_LENGTH + 2 * HALF_LENGTH * i / 4.0);
            line(plane, at.clone().add(up.clone().multiply(-0.04 * S)), at.clone().add(up.clone().multiply(0.04 * S)), config);
        }
        parts.add(label(at(0, 0.45), Component.text("太さ", NamedTextColor.GOLD), 0.4f * S));
        parts.add(label(at(-HALF_LENGTH / S, -0.13), Component.text(String.valueOf(minLevel), NamedTextColor.GRAY), 0.3f * S));
        parts.add(label(at(HALF_LENGTH / S, -0.13), Component.text(String.valueOf(maxLevel), NamedTextColor.GRAY), 0.3f * S));
        parts.add(label(at(0, -0.66), Component.text("左右で調整して離すと決定 / バーから外して離すとキャンセル", NamedTextColor.GRAY), 0.25f * S));
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

        preview = LineDisplays.asMenu(LineDisplays.dot(at(0, -0.4), toWidth(initialLevel), 0xFF000000 | color, config));
        player.showEntity(plugin, preview);
        parts.add(preview);
        show(initialLevel);
    }

    /** Whole level shown to players: hundredths of a block. */
    public static int toLevel(float width) {
        return Math.round(width * 100);
    }

    private static float toWidth(int level) {
        return level / 100f;
    }

    /** Position along the bar (0..1, logarithmic) of a level, and back. */
    private double fraction(int level) {
        return Math.log((double) level / minLevel) / Math.log((double) maxLevel / minLevel);
    }

    private int levelAt(double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        return (int) Math.round(minLevel * Math.pow((double) maxLevel / minLevel, f));
    }

    /** Point in the menu plane, in units of the unscaled layout ({@code x} right, {@code y} up of the centre). */
    private Location at(double x, double y) {
        return center.clone().add(right.clone().multiply(x * S)).add(up.clone().multiply(y * S));
    }

    /** Bottom end of the marker over {@code level}; unrotated, since a display turns by its own yaw/pitch too. */
    private Location markerLocation(int level) {
        double x = -HALF_LENGTH + 2 * HALF_LENGTH * fraction(level);
        Location location = plane.clone().add(right.clone().multiply(x)).add(up.clone().multiply(-MARKER_HALF_HEIGHT));
        location.setYaw(0);
        location.setPitch(0);
        return location;
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
            boolean alongBar = Math.abs(x) <= HALF_LENGTH + CANCEL_DISTANCE;
            if (alongBar && Math.abs(y) <= CANCEL_DISTANCE) {
                level = levelAt((x + HALF_LENGTH) / (2 * HALF_LENGTH));
            } else if (alongBar && y < -CANCEL_DISTANCE && y >= -PREVIEW_AREA && hovered >= 0) {
                level = hovered; // glancing down at the preview keeps the current pick
            }
        }
        hovered = level;
        show(level);
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
        if (preview.isValid()) {
            preview.setTransformationMatrix(LineGeometry.dot(toWidth(shown), plugin.config().ribbonBase()));
        }
        if (value.isValid()) {
            value.text(level < 0
                    ? Component.text("キャンセル", NamedTextColor.GRAY)
                    : Component.text(String.valueOf(level), NamedTextColor.WHITE));
        }
    }

    @Override
    public @NotNull String release() {
        if (hovered < 0) return "キャンセル";
        onSet.accept(toWidth(hovered));
        return "太さ" + hovered;
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
