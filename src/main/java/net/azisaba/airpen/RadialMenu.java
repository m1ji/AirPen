package net.azisaba.airpen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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

/**
 * FiveM-style radial menu floating in front of one player: entries on a ring, "cancel" fixed in the middle.
 * The hovered entry is the one the player's gaze points at; only that player can see the menu.
 */
public final class RadialMenu implements PenMenu {
    public static final int CENTER = -1;
    public static final int NONE = -2;

    private static final Color BACKGROUND = Color.fromARGB(0x90, 0x10, 0x10, 0x10);
    private static final Color HOVER = Color.fromARGB(0xD0, 0xE0, 0x90, 0x10);

    public record Entry(Component label, Runnable action) {
    }

    private final AirPenPlugin plugin;
    private final Player player;
    private final List<Entry> entries;
    private final Location center;
    private final Vector right;
    private final Vector up;
    private final Vector normal;
    private final List<TextDisplay> displays = new ArrayList<>();
    /** Divider lines and the ring around "cancel". */
    private final List<TextDisplay> decorations = new ArrayList<>();
    private TextDisplay centerDisplay;
    private int hovered = NONE;
    private final long openedAt = System.currentTimeMillis();

    public RadialMenu(@NotNull AirPenPlugin plugin, @NotNull Player player, @NotNull Component title, @NotNull List<Entry> entries) {
        this.plugin = plugin;
        this.player = player;
        this.entries = List.copyOf(entries);
        AirPenConfig config = plugin.config();
        Location eye = player.getEyeLocation();
        this.normal = eye.getDirection().normalize();
        Vector worldUp = new Vector(0, 1, 0);
        Vector r = normal.clone().crossProduct(worldUp);
        if (r.lengthSquared() < 1e-6) r = new Vector(1, 0, 0); // looking straight up/down
        this.right = r.normalize();
        this.up = right.clone().crossProduct(normal).normalize();
        this.center = eye.clone().add(normal.clone().multiply(config.menuDistance()));

        double radius = config.menuRadius();
        for (int i = 0; i < this.entries.size(); i++) {
            double angle = angleOf(i);
            Location at = center.clone()
                    .add(right.clone().multiply(Math.cos(angle) * radius))
                    .add(up.clone().multiply(Math.sin(angle) * radius));
            displays.add(spawn(at, this.entries.get(i).label(), 0.45f));
        }
        centerDisplay = spawn(center.clone().add(up.clone().multiply(0.13)), title, 0.34f);
        drawDecorations(config);
        displays.add(spawn(center, Component.text("✕ キャンセル", NamedTextColor.WHITE), 0.42f));
    }

    /** Entries start at the top and go clockwise. */
    private double angleOf(int index) {
        return Math.PI / 2 - 2 * Math.PI * index / entries.size();
    }

    private TextDisplay spawn(Location at, Component text, float scale) {
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

    private static final int LINE_ARGB = 0xB0FFFFFF;
    private static final float LINE_WIDTH = 0.012f;
    private static final int INNER_RING_SEGMENTS = 16;
    private static final int OUTER_RING_SEGMENTS = 28;

    /** Ring around "cancel", outer frame, and dividers halfway between neighbouring entries, just behind the labels. */
    private void drawDecorations(AirPenConfig config) {
        Location plane = center.clone().add(normal.clone().multiply(0.03));
        double inner = config.menuCenterRadius();
        double outer = config.menuRadius() * 1.5;
        if (entries.size() > 1) {
            double half = Math.PI / entries.size();
            for (int i = 0; i < entries.size(); i++) {
                double angle = angleOf(i) - half;
                line(plane, radial(angle, inner), radial(angle, outer), config);
            }
        }
        ring(plane, inner, INNER_RING_SEGMENTS, config);
        ring(plane, outer, OUTER_RING_SEGMENTS, config);
    }

    private void ring(Location plane, double radius, int segments, AirPenConfig config) {
        for (int i = 0; i < segments; i++) {
            double a0 = 2 * Math.PI * i / segments, a1 = 2 * Math.PI * (i + 1) / segments;
            line(plane, radial(a0, radius), radial(a1, radius), config);
        }
    }

    private Vector radial(double angle, double radius) {
        return right.clone().multiply(Math.cos(angle) * radius).add(up.clone().multiply(Math.sin(angle) * radius));
    }

    private void line(Location plane, Vector from, Vector to, AirPenConfig config) {
        Location at = plane.clone().add(from);
        Vector direction = to.clone().subtract(from);
        Vector facing = normal.clone().multiply(-1);
        List<TextDisplay> sides = new ArrayList<>(2);
        sides.add(LineDisplays.asMenu(LineDisplays.ribbon(at, direction, LINE_WIDTH, facing, LINE_ARGB, false, config)));
        if (config.doubleSided()) sides.add(LineDisplays.asMenu(LineDisplays.ribbon(at, direction, LINE_WIDTH, facing, LINE_ARGB, true, config)));
        sides.forEach(d -> player.showEntity(plugin, d));
        decorations.addAll(sides);
    }

    /** Recomputes which entry the player is looking at and highlights it; returns the index. */
    public int updateHover() {
        int index = pick(player.getEyeLocation());
        if (index != hovered) {
            setHighlight(hovered, false);
            setHighlight(index, true);
            hovered = index;
        }
        return hovered;
    }

    public int hovered() {
        return hovered;
    }

    @Override
    public void tick() {
        updateHover();
    }

    @Override
    public @NotNull String release() {
        if (hovered >= 0) {
            entries.get(hovered).action().run();
            return "項目" + hovered;
        }
        return hovered == CENTER ? "キャンセル" : "どこも見ていない";
    }

    public @NotNull Entry entry(int index) {
        return entries.get(index);
    }

    private int pick(Location eye) {
        Vector dir = eye.getDirection().normalize();
        double facing = dir.dot(normal);
        if (facing <= 0.2) return NONE; // looking away from the menu
        Vector toCenter = center.toVector().subtract(eye.toVector());
        double t = toCenter.dot(normal) / facing;
        if (t <= 0) return NONE;
        Vector local = eye.toVector().add(dir.multiply(t)).subtract(center.toVector());
        double x = local.dot(right), y = local.dot(up);
        if (Math.hypot(x, y) < plugin.config().menuCenterRadius()) return CENTER;
        double angle = Math.atan2(y, x);
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            double diff = Math.abs(Math.atan2(Math.sin(angle - angleOf(i)), Math.cos(angle - angleOf(i))));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private void setHighlight(int index, boolean on) {
        TextDisplay display = index == CENTER ? displays.get(displays.size() - 1)
                : index >= 0 && index < entries.size() ? displays.get(index) : null;
        if (display != null && display.isValid()) display.setBackgroundColor(on ? HOVER : BACKGROUND);
    }

    /** True when the player wandered off (other world or too far from where the menu was opened). */
    @Override
    public boolean isStale() {
        Location eye = player.getEyeLocation();
        return !eye.getWorld().equals(center.getWorld()) || eye.distanceSquared(center) > 9;
    }

    /** True when any of the menu's displays was removed from outside (e.g. an entity clean-up). */
    @Override
    public boolean isBroken() {
        for (TextDisplay display : displays) {
            if (!display.isValid()) return true;
        }
        return centerDisplay != null && !centerDisplay.isValid();
    }

    @Override
    public long idleFor() {
        return System.currentTimeMillis() - openedAt;
    }

    @Override
    public void close() {
        displays.forEach(TextDisplay::remove);
        displays.clear();
        decorations.forEach(TextDisplay::remove);
        decorations.clear();
        if (centerDisplay != null) centerDisplay.remove();
        centerDisplay = null;
    }

    public static Component swatch(int rgb, String name) {
        return Component.text("■ ", TextColor.color(rgb)).append(Component.text(name, NamedTextColor.WHITE));
    }
}
