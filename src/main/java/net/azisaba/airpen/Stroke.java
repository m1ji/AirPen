package net.azisaba.airpen;

import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One continuous line drawn by a player: a chain of straight segments, each rendered by one or two displays. */
public final class Stroke {
    public final UUID owner;
    public final UUID world;
    /** Exact color, used by the ribbon style. */
    public final int color;
    /** Closest block to {@link #color}, used by the bar style. */
    public final Material material;
    public final float width;
    /** Rendering style fixed when the stroke began, so a config reload does not change existing lines. */
    public final AirPenConfig.Style style;
    /** Drawer's view direction when the stroke began. */
    public final Vector facing;
    /** Drawer's view direction right now; each new piece turns its flat face towards it. */
    public Vector currentFacing;
    public final List<Segment> segments = new ArrayList<>();
    /**
     * Taken off its owner's list (undo, clear, auto-clear, erased). The drawer may still be adding to it; the next
     * piece puts it back, otherwise that ink would belong to no stroke and be swept away.
     */
    boolean detached;

    public Stroke(@NotNull UUID owner, @NotNull UUID world, int color, float width, @NotNull AirPenConfig.Style style,
                  @NotNull Vector facing) {
        this.owner = owner;
        this.world = world;
        this.color = color;
        this.material = BlockPalette.closest(color);
        this.width = width;
        this.style = style;
        this.facing = facing.clone().normalize();
        this.currentFacing = this.facing.clone();
    }

    /** Last straight segment (joint caps and dots are skipped). */
    public @Nullable Segment last() {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).kind == Kind.LINE) return segments.get(i);
        }
        return null;
    }

    public enum Kind {
        /** Straight piece between two points. */
        LINE,
        /** Square patch covering the outer corner where two pieces meet at an angle. */
        CAP,
        /** Single camera-facing dot (dot style). */
        DOT
    }

    public static final class Segment {
        public final Vector from;
        public Vector to;
        public final Kind kind;
        /** View direction the piece was drawn with (ribbon / bar face it). */
        public final Vector facing;
        /** Front and (for double-sided ribbons) back display; empty while not spawned. */
        public final List<Display> entities = new ArrayList<>(2);

        Segment(Vector from, Vector to, Kind kind, Vector facing) {
            this.from = from.clone();
            this.to = to.clone();
            this.kind = kind;
            this.facing = facing.clone();
        }

        public Vector direction() {
            return to.clone().subtract(from);
        }

        /**
         * The displays still exist. {@code isDead} rather than {@code isValid}: the latter can read false for a moment
         * while the entity is fine, and respawning then made lines blink out.
         */
        public boolean isSpawned() {
            return !entities.isEmpty() && entities.stream().noneMatch(Display::isDead);
        }

        /** Distance from {@code p} to this segment. */
        public double distanceTo(Vector p) {
            Vector d = direction();
            double len2 = d.lengthSquared();
            if (len2 < 1e-9) return p.distance(from);
            double t = Math.max(0, Math.min(1, p.clone().subtract(from).dot(d) / len2));
            return p.distance(from.clone().add(d.multiply(t)));
        }
    }
}
