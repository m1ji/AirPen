package net.azisaba.airpen;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;

/** Spawns the hidden, non-persistent displays that make up a line. */
public final class LineDisplays {
    /** Scoreboard tag on every ink display, so leftovers no stroke owns any more can be found and removed. */
    public static final String TAG_INK = "airpen.ink";
    /** Scoreboard tag on every menu display (short-lived). */
    public static final String TAG_MENU = "airpen.menu";

    private LineDisplays() {
    }

    /** Re-tags a display built by this class as part of a menu instead of ink. */
    public static <T extends Display> T asMenu(T display) {
        display.removeScoreboardTag(TAG_INK);
        display.addScoreboardTag(TAG_MENU);
        return display;
    }

    /** Flat strip: the background of a text display holding a single space, in the exact ARGB color. */
    public static @NotNull TextDisplay ribbon(@NotNull Location from, @NotNull Vector direction, float width,
                                              @NotNull Vector facing, int argb, boolean back, @NotNull AirPenConfig config) {
        Location anchor = anchor(from, direction, width);
        return from.getWorld().spawn(anchor, TextDisplay.class, e -> {
            prepare(e, config);
            cullBox(e, direction, width);
            e.text(Component.text(" "));
            e.setBackgroundColor(Color.fromARGB(argb));
            e.setDefaultBackground(false);
            e.setShadowed(false);
            e.setSeeThrough(false);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTransformationMatrix(lifted(from, anchor, LineGeometry.ribbon(direction, width, facing, config.ribbonBase(), back)));
        });
    }

    /** Camera-facing square of the given color, centred on {@code at}. */
    public static @NotNull TextDisplay dot(@NotNull Location at, float width, int argb, @NotNull AirPenConfig config) {
        return at.getWorld().spawn(unrotated(at), TextDisplay.class, e -> {
            prepare(e, config);
            e.text(Component.text(" "));
            e.setBackgroundColor(Color.fromARGB(argb));
            e.setDefaultBackground(false);
            e.setShadowed(false);
            e.setSeeThrough(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setTransformationMatrix(LineGeometry.dot(width, config.ribbonBase()));
        });
    }

    /** Square bar: a stretched block display. */
    public static @NotNull BlockDisplay bar(@NotNull Location from, @NotNull Vector direction, float width,
                                            @NotNull Vector facing, @NotNull Material material, @NotNull AirPenConfig config) {
        Location anchor = anchor(from, direction, width);
        return from.getWorld().spawn(anchor, BlockDisplay.class, e -> {
            prepare(e, config);
            cullBox(e, direction, width);
            e.setBlock(material.createBlockData());
            e.setTransformationMatrix(lifted(from, anchor, LineGeometry.bar(direction, width, facing)));
        });
    }

    /** Re-applies the line transformation after the end point moved. {@code index} 1 is a ribbon's back side. */
    public static void update(@NotNull Display display, int index, @NotNull Vector from, @NotNull Vector direction,
                              float width, @NotNull Vector facing, @NotNull AirPenConfig config) {
        Location start = from.toLocation(display.getWorld());
        Location anchor = anchor(start, direction, width);
        if (Math.abs(display.getLocation().getY() - anchor.getY()) > 1e-4) display.teleport(anchor);
        cullBox(display, direction, width);
        Matrix4f shape = display instanceof TextDisplay
                ? LineGeometry.ribbon(direction, width, facing, config.ribbonBase(), index == 1)
                : LineGeometry.bar(direction, width, facing);
        display.setTransformationMatrix(lifted(start, anchor, shape));
    }

    /**
     * Clients skip rendering an entity whose culling box is off screen, and a display's box defaults to a single
     * point at its position. For a line that point is one end, so a long piece vanished as soon as its start left
     * the screen. The box is a {@code displayWidth}-wide square around the position, reaching
     * {@code displayHeight} upwards; the display therefore sits at the line's lowest point and the box covers the
     * whole piece.
     */
    private static void cullBox(Display display, Vector direction, float width) {
        double horizontal = Math.max(Math.abs(direction.getX()), Math.abs(direction.getZ()));
        display.setDisplayWidth((float) (2 * (horizontal + width)));
        display.setDisplayHeight((float) (Math.abs(direction.getY()) + 2 * width));
    }

    /** Position for a line starting at {@code from}: same x/z, at its lowest point (minus a margin). */
    private static Location anchor(Location from, Vector direction, float width) {
        Location anchor = unrotated(from);
        anchor.setY(from.getY() + Math.min(0, direction.getY()) - width);
        return anchor;
    }

    /** The line's shape is built around {@code from}; shift it up by however far below it the display sits. */
    private static Matrix4f lifted(Location from, Location anchor, Matrix4f shape) {
        return new Matrix4f().translate(0, (float) (from.getY() - anchor.getY()), 0).mul(shape);
    }

    /**
     * A display applies its own yaw/pitch before the transformation, and the transformation already holds the
     * full orientation, so the entity itself must not be turned (a location taken from a player's eyes would be).
     */
    private static Location unrotated(Location at) {
        Location location = at.clone();
        location.setYaw(0);
        location.setPitch(0);
        return location;
    }

    private static void prepare(Display e, AirPenConfig config) {
        e.addScoreboardTag(TAG_INK);
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        e.setViewRange(config.viewRange());
        e.setShadowRadius(0);
        if (config.fullBright()) e.setBrightness(new Display.Brightness(15, 15));
    }
}
