package net.azisaba.airpen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Owns every stroke and its display entities. Entities are non-persistent and hidden by default:
 * the owner always sees them, other players only while the owner's drawings are public.
 */
public final class StrokeManager {
    private final AirPenPlugin plugin;
    private final Predicate<UUID> isPublic;
    private final Predicate<UUID> unlimitedInk;
    private final Map<UUID, Deque<Stroke>> strokes = new HashMap<>();
    private final Map<UUID, Integer> segmentCounts = new HashMap<>();
    /** Every ink display currently owned by a stroke; tagged displays outside this set are leftovers. */
    private final java.util.Set<UUID> tracked = new java.util.HashSet<>();

    public StrokeManager(@NotNull AirPenPlugin plugin, @NotNull Predicate<UUID> isPublic, @NotNull Predicate<UUID> unlimitedInk) {
        this.plugin = plugin;
        this.isPublic = isPublic;
        this.unlimitedInk = unlimitedInk;
    }

    public @NotNull Stroke begin(@NotNull Player owner, @NotNull Stroke stroke) {
        strokes.computeIfAbsent(owner.getUniqueId(), k -> new ArrayDeque<>()).addLast(stroke);
        return stroke;
    }

    /** The most recent stroke of {@code owner}, if any. */
    public @Nullable Stroke latest(@NotNull UUID owner) {
        Deque<Stroke> list = strokes.get(owner);
        return list == null || list.isEmpty() ? null : list.peekLast();
    }

    /** Ink used by {@code owner}: one unit per dot / straight piece. */
    public int used(@NotNull UUID owner) {
        return Math.max(0, segmentCounts.getOrDefault(owner, 0));
    }

    public int remaining(@NotNull UUID owner) {
        if (unlimitedInk.test(owner)) return Integer.MAX_VALUE;
        return Math.max(0, plugin.config().maxSegmentsPerPlayer() - used(owner));
    }

    /** Adds a straight piece; false (nothing added) when the owner is out of ink. */
    public boolean addSegment(@NotNull Stroke stroke, @NotNull Vector from, @NotNull Vector to) {
        return add(stroke, new Stroke.Segment(from, to, Stroke.Kind.LINE, stroke.currentFacing));
    }

    /** Adds a dot; false (nothing added) when the owner is out of ink. */
    public boolean addDot(@NotNull Stroke stroke, @NotNull Vector at) {
        return add(stroke, new Stroke.Segment(at, at, Stroke.Kind.DOT, stroke.currentFacing));
    }

    /**
     * Square patch, one line-width across, centred on a corner and turned along {@code bisector}: fills the notch
     * that two flat strips leave on the outside of a bend.
     */
    public boolean addCap(@NotNull Stroke stroke, @NotNull Vector corner, @NotNull Vector bisector) {
        return add(stroke, new Stroke.Segment(corner, corner.clone().add(bisector.clone().normalize().multiply(0.001)), Stroke.Kind.CAP, stroke.currentFacing));
    }

    private boolean add(Stroke stroke, Stroke.Segment segment) {
        if (remaining(stroke.owner) <= 0) return false;
        if (stroke.detached) {
            stroke.detached = false;
            strokes.computeIfAbsent(stroke.owner, k -> new ArrayDeque<>()).addLast(stroke);
        }
        stroke.segments.add(segment);
        spawn(stroke, segment);
        segmentCounts.merge(stroke.owner, 1, Integer::sum);
        return true;
    }

    public void extendSegment(@NotNull Stroke stroke, @NotNull Stroke.Segment segment, @NotNull Vector to) {
        segment.to = to.clone();
        if (segment.isSpawned()) {
            for (int i = 0; i < segment.entities.size(); i++) {
                LineDisplays.update(segment.entities.get(i), i, segment.from, segment.direction(), stroke.width,
                        segment.facing, plugin.config());
            }
        } else {
            spawn(stroke, segment);
        }
    }

    /** Removes the newest stroke of {@code owner}; false when there was none. */
    public boolean undo(@NotNull UUID owner) {
        Deque<Stroke> list = strokes.get(owner);
        while (list != null && !list.isEmpty()) {
            Stroke stroke = list.pollLast();
            stroke.detached = true;
            if (stroke.segments.isEmpty()) continue; // emptied by the eraser
            detach(stroke);
            return true;
        }
        return false;
    }

    public int clear(@NotNull UUID owner) {
        Deque<Stroke> list = strokes.remove(owner);
        if (list != null) list.forEach(this::detach);
        segmentCounts.remove(owner);
        return list == null ? 0 : list.size();
    }

    public int clearAll() {
        int n = 0;
        for (UUID owner : new java.util.ArrayList<>(strokes.keySet())) n += clear(owner);
        return n;
    }

    /**
     * Removes the pieces (dots, or straight segments for the other styles) of {@code owner}'s strokes that pass
     * within {@code radius} of {@code point}; the rest of each stroke stays. Returns the number of pieces removed.
     */
    public int eraseNear(@NotNull UUID owner, @NotNull UUID world, @NotNull Vector point, double radius) {
        Deque<Stroke> list = strokes.get(owner);
        if (list == null) return 0;
        int removed = 0;
        for (Iterator<Stroke> it = list.iterator(); it.hasNext(); ) {
            Stroke stroke = it.next();
            if (!stroke.world.equals(world)) continue;
            double r = radius + stroke.width / 2;
            for (Iterator<Stroke.Segment> pieces = stroke.segments.iterator(); pieces.hasNext(); ) {
                Stroke.Segment piece = pieces.next();
                if (piece.distanceTo(point) > r) continue;
                pieces.remove();
                despawn(piece);
                removed++;
            }
            if (stroke.segments.isEmpty()) {
                it.remove();
                stroke.detached = true;
            }
        }
        if (removed > 0) segmentCounts.merge(owner, -removed, Integer::sum);
        return removed;
    }

    /**
     * Eraser along the line of sight: finds the nearest piece of {@code owner}'s ink that the ray from {@code eye}
     * passes within reach of, then removes every piece within {@code radius} of that spot. Returns pieces removed.
     */
    public int eraseAlongRay(@NotNull UUID owner, @NotNull UUID world, @NotNull Vector eye, @NotNull Vector direction,
                             double range, double radius) {
        Deque<Stroke> list = strokes.get(owner);
        if (list == null) return 0;
        Vector dir = direction.clone().normalize();
        double bestT = Double.MAX_VALUE;
        for (Stroke stroke : list) {
            if (!stroke.world.equals(world)) continue;
            double reach = radius + stroke.width / 2;
            for (Stroke.Segment piece : stroke.segments) {
                double t = RayMath.hitDistance(eye, dir, piece.from, piece.to, reach);
                if (t >= 0 && t <= range && t < bestT) bestT = t;
            }
        }
        if (bestT == Double.MAX_VALUE) return 0;
        return eraseNear(owner, world, eye.clone().add(dir.multiply(bestT)), radius);
    }

    /** Removes strokes of {@code owner} in another world or whose start is farther than {@code distance} from {@code at}. */
    public int clearFar(@NotNull UUID owner, @NotNull Location at, double distance) {
        Deque<Stroke> list = strokes.get(owner);
        if (list == null) return 0;
        UUID world = at.getWorld().getUID();
        Vector position = at.toVector();
        double max = distance * distance;
        int removed = 0;
        for (Iterator<Stroke> it = list.iterator(); it.hasNext(); ) {
            Stroke stroke = it.next();
            boolean far = !stroke.world.equals(world)
                    || stroke.segments.isEmpty()
                    || stroke.segments.get(0).from.distanceSquared(position) > max;
            if (!far) continue;
            it.remove();
            detach(stroke);
            removed++;
        }
        return removed;
    }

    /** Applies the owner's public/private setting to every viewer currently online. */
    public void refreshVisibility(@NotNull UUID owner) {
        Deque<Stroke> list = strokes.get(owner);
        if (list == null) return;
        boolean visible = isPublic.test(owner);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(owner)) continue;
            for (Stroke stroke : list) {
                for (Stroke.Segment segment : stroke.segments) {
                    for (Display display : segment.entities) {
                        if (visible) viewer.showEntity(plugin, display);
                        else viewer.hideEntity(plugin, display);
                    }
                }
            }
        }
    }

    /** Hidden-by-default entities must be shown again to players who (re)join. */
    public void showTo(@NotNull Player viewer) {
        for (Map.Entry<UUID, Deque<Stroke>> e : strokes.entrySet()) {
            if (!e.getKey().equals(viewer.getUniqueId()) && !isPublic.test(e.getKey())) continue;
            for (Stroke stroke : e.getValue()) {
                for (Stroke.Segment segment : stroke.segments) {
                    segment.entities.forEach(display -> viewer.showEntity(plugin, display));
                }
            }
        }
    }

    /** Non-persistent displays vanish with their chunk; bring them back once it is loaded again. */
    public void respawnMissing() {
        for (Deque<Stroke> list : strokes.values()) {
            for (Stroke stroke : list) {
                World world = Bukkit.getWorld(stroke.world);
                if (world == null) continue;
                for (Stroke.Segment segment : stroke.segments) {
                    if (segment.isSpawned()) continue;
                    if (!world.isChunkLoaded(segment.from.getBlockX() >> 4, segment.from.getBlockZ() >> 4)) continue;
                    spawn(stroke, segment);
                }
            }
        }
    }

    /** Removes the stroke's ink and gives it back; the stroke itself is left empty and marked detached. */
    private void detach(Stroke stroke) {
        segmentCounts.merge(stroke.owner, -stroke.segments.size(), Integer::sum);
        despawn(stroke);
        stroke.segments.clear();
        stroke.detached = true;
    }

    private void despawn(Stroke stroke) {
        stroke.segments.forEach(this::despawn);
    }

    private void despawn(Stroke.Segment segment) {
        for (Display display : segment.entities) {
            tracked.remove(display.getUniqueId());
            display.remove();
        }
        segment.entities.clear();
    }

    /**
     * Removes ink displays no stroke owns (left behind e.g. when a reload could not clean up) and menu displays
     * older than a minute. Returns {ink, menu} counts.
     */
    public int[] sweepOrphans() {
        // Only ink the eraser can reach counts as owned: rebuilt from the stroke lists, not trusted from bookkeeping
        java.util.Set<UUID> reachable = new java.util.HashSet<>();
        for (Deque<Stroke> list : strokes.values()) {
            for (Stroke stroke : list) {
                for (Stroke.Segment piece : stroke.segments) {
                    for (Display display : piece.entities) reachable.add(display.getUniqueId());
                }
            }
        }
        tracked.retainAll(reachable);
        int ink = 0, menu = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Display display : world.getEntitiesByClass(Display.class)) {
                java.util.Set<String> tags = display.getScoreboardTags();
                if (tags.contains(LineDisplays.TAG_INK) && !reachable.contains(display.getUniqueId())) {
                    display.remove();
                    ink++;
                } else if (tags.contains(LineDisplays.TAG_MENU) && display.getTicksLived() > 20 * 60) {
                    display.remove();
                    menu++;
                }
            }
        }
        if (ink > 0) plugin.getLogger().info("Removed " + ink + " ink displays no stroke owned");
        return new int[]{ink, menu};
    }

    /**
     * Diagnoses the ink on the line of sight: the nearest piece any stroke owns, and the nearest tagged display
     * near the ray with whether a stroke owns it. Used to tell server-side leftovers from client-only ghosts.
     */
    public java.util.List<String> inspect(@NotNull Location eye, @NotNull Vector direction, double range) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        Vector origin = eye.toVector();
        Vector dir = direction.clone().normalize();
        UUID world = eye.getWorld().getUID();
        double bestT = Double.MAX_VALUE;
        Stroke bestStroke = null;
        Stroke.Segment bestPiece = null;
        for (Deque<Stroke> list : strokes.values()) {
            for (Stroke stroke : list) {
                if (!stroke.world.equals(world)) continue;
                for (Stroke.Segment piece : stroke.segments) {
                    double t = RayMath.hitDistance(origin, dir, piece.from, piece.to, 0.25 + stroke.width / 2);
                    if (t >= 0 && t <= range && t < bestT) {
                        bestT = t;
                        bestStroke = stroke;
                        bestPiece = piece;
                    }
                }
            }
        }
        if (bestPiece == null) {
            lines.add("管理中のインク: 視線上に無し");
        } else {
            String owner = Bukkit.getOfflinePlayer(bestStroke.owner).getName();
            long valid = bestPiece.entities.stream().filter(Display::isValid).count();
            lines.add(String.format("管理中のインク: %.1f ブロック先 / 持ち主 %s / %s %s / エンティティ %d 個 (有効 %d)",
                    bestT, owner, bestStroke.style, bestPiece.kind, bestPiece.entities.size(), valid));
        }
        Display nearest = null;
        double nearestT = Double.MAX_VALUE;
        for (Display display : eye.getWorld().getEntitiesByClass(Display.class)) {
            if (!display.getScoreboardTags().contains(LineDisplays.TAG_INK)) continue;
            Vector at = display.getLocation().toVector();
            double t = RayMath.hitDistance(origin, dir, at, at, 0.6);
            if (t >= 0 && t <= range && t < nearestT) {
                nearestT = t;
                nearest = display;
            }
        }
        if (nearest == null) {
            lines.add("サーバー上のインクのエンティティ: 視線の近くに無し");
        } else {
            lines.add(String.format("サーバー上のインクのエンティティ: %.1f ブロック先 / %s", nearestT,
                    tracked.contains(nearest.getUniqueId()) ? "管理中" : "迷子(どの線にも属していない)"));
        }
        if (bestPiece == null && nearest == null) {
            lines.add("→ サーバーには無いので、見えている線はクライアントにだけ残った表示です(再ログインで消えるはず)");
        } else if (nearest != null && !tracked.contains(nearest.getUniqueId())) {
            lines.add("→ サーバー側の迷子です。/airpen cleanup で消せます");
        }
        return lines;
    }

    /** Removes every AirPen display in loaded chunks (on start-up nothing can own them yet). */
    public int removeAllTagged() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Display display : world.getEntitiesByClass(Display.class)) {
                java.util.Set<String> tags = display.getScoreboardTags();
                if (tags.contains(LineDisplays.TAG_INK) || tags.contains(LineDisplays.TAG_MENU)) {
                    display.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private void spawn(Stroke stroke, Stroke.Segment segment) {
        despawn(segment);
        World world = Bukkit.getWorld(stroke.world);
        if (world == null) return;
        Location at = segment.from.toLocation(world);
        AirPenConfig config = plugin.config();
        Vector direction = segment.direction();
        int argb = 0xFF000000 | stroke.color;
        if (segment.kind == Stroke.Kind.DOT) {
            segment.entities.add(LineDisplays.dot(at, stroke.width, argb, config));
        } else if (stroke.style == AirPenConfig.Style.RIBBON) {
            segment.entities.add(LineDisplays.ribbon(at, direction, stroke.width, segment.facing, argb, false, config));
            if (config.doubleSided()) {
                segment.entities.add(LineDisplays.ribbon(at, direction, stroke.width, segment.facing, argb, true, config));
            }
        } else {
            segment.entities.add(LineDisplays.bar(at, direction, stroke.width, segment.facing, stroke.material, config));
        }
        segment.entities.forEach(display -> tracked.add(display.getUniqueId()));
        Player owner = Bukkit.getPlayer(stroke.owner);
        boolean visibleToOthers = isPublic.test(stroke.owner);
        for (Display display : segment.entities) {
            if (owner != null) owner.showEntity(plugin, display);
            if (!visibleToOthers) continue;
            for (Player viewer : world.getPlayers()) {
                if (!viewer.getUniqueId().equals(stroke.owner)) viewer.showEntity(plugin, display);
            }
        }
    }
}
