package net.azisaba.airpen;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the pen every tick. Right-click held = draw (the pen is a "usable" item, so holding and letting go are
 * reported exactly); left-click = open the menu / pick what the gaze points at.
 */
public final class PenController implements Listener {
    /** Bends sharper than this get a joint cap; gentler ones overlap closely enough on their own. */
    private static final double JOINT_CAP_ANGLE = 15.0;
    /** Swings this soon after an attack belong to the attack. */
    private static final long ATTACK_SWING_MS = 150;
    /** Swings this soon after a right-click on a block/entity belong to that click (chests, doors, villagers...). */
    private static final long INTERACT_SWING_MS = 100;
    /** Right-clicks repeat every 4 ticks (200 ms) while held; a little slack for network jitter. */
    private static final long RIGHT_CLICK_HOLD_MS = 250;
    /** Arm swings closer together than this are one held left-click (digging swings every tick), not new clicks. */
    private static final long CLICK_GAP_MS = 150;
    /** A menu left open without a click closes after this long. */
    private static final long MENU_IDLE_MS = 15_000;
    /** Tip speeds (blocks per tick) between which smoothing fades out. */
    private static final double SLOW_SPEED = 0.03;
    private static final double FAST_SPEED = 0.25;

    private final AirPenPlugin plugin;
    private final Map<UUID, PenSession> sessions = new ConcurrentHashMap<>();
    private final StrokeManager strokes;
    private final NamespacedKey keyColor;
    private final NamespacedKey keyWidth;
    private final NamespacedKey keyWidthValue;
    private final NamespacedKey keyVisible;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public PenController(@NotNull AirPenPlugin plugin) {
        this.plugin = plugin;
        this.strokes = new StrokeManager(plugin, owner -> {
            PenSession s = sessions.get(owner);
            return s != null && s.visibleToOthers;
        }, PenController::hasUnlimitedInk);
        this.keyColor = new NamespacedKey(plugin, "color");
        this.keyWidth = new NamespacedKey(plugin, "width");
        this.keyWidthValue = new NamespacedKey(plugin, "width-value");
        this.keyVisible = new NamespacedKey(plugin, "visible");
    }

    public void start() {
        int leftovers = strokes.removeAllTagged();
        if (leftovers > 0) plugin.getLogger().info("Removed " + leftovers + " leftover displays from an earlier run");
        Bukkit.getOnlinePlayers().forEach(this::load);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, strokes::respawnMissing, 40, 40));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::clearFarStrokes, 20, 20));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, strokes::sweepOrphans, 600, 600));
    }

    public void shutdown() {
        tasks.forEach(BukkitTask::cancel);
        for (Player player : Bukkit.getOnlinePlayers()) {
            PenSession session = sessions.get(player.getUniqueId());
            if (session == null) continue;
            closeMenu(session);
        }
        strokes.clearAll();
        strokes.removeAllTagged();
        sessions.clear();
    }

    public @Nullable PenSession session(@NotNull UUID player) {
        return sessions.get(player);
    }

    public @NotNull StrokeManager strokes() {
        return strokes;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PenSession session = sessions.get(player.getUniqueId());
            if (session == null) continue;
            tickPlayer(player, session, now);
            // Rewriting the held item makes the hand bob, so the ink gauge only updates between strokes
            // ...and never while the menu is up or right-click is held: replacing the item cancels the hold
            if (session.inkDirty && !session.drawing && session.menu == null && !player.isHandRaised()) {
                refreshInk(player, session);
            }
        }
    }

    private void tickPlayer(Player player, PenSession session, long now) {
        // Anyone holding a pen may use it: no permission, so a permissions plugin cannot lock players out by accident
        boolean holding = plugin.pen().isPen(player.getInventory().getItemInMainHand());
        session.holdingPen = holding;
        if (!holding) {
            stopDrawing(session, now);
            closeMenu(session);
            return;
        }
        // Right-click held = the pen is in use, or right-clicks are still repeating (the client resends them every
        // 4 ticks while held when it does not use the pen, e.g. on a chest). An explicit release ends it at once.
        boolean released = session.lastUseRelease >= session.lastRightClick && now - session.lastUseRelease < 1000;
        boolean rightHeld = player.isHandRaised() && plugin.pen().isPen(player.getActiveItem())
                || !released && now - session.lastRightClick <= RIGHT_CLICK_HOLD_MS;
        // Left-click = an arm swing after a pause; attacks also swing and are ignored
        boolean leftClick = session.lastSwing != session.handledSwing
                && session.lastSwing - session.handledSwing > CLICK_GAP_MS
                && session.lastSwing - session.lastAttack > ATTACK_SWING_MS
                && session.lastSwing - session.lastInteract > INTERACT_SWING_MS;
        session.handledSwing = session.lastSwing;
        if (session.debug) {
            player.sendActionBar(Component.text(String.format("使用中=%s 右クリ=%dms前 右長押し=%s メニュー=%s 描画=%s",
                    player.isHandRaised() && plugin.pen().isPen(player.getActiveItem()),
                    Math.min(9999, now - session.lastRightClick), rightHeld,
                    session.menu == null ? "なし" : "あり", session.drawing), NamedTextColor.GRAY));
        }
        tickMenu(player, session, leftClick);
        if (session.menu != null) {
            stopDrawing(session, now);
            // Right-click still held from before the menu opened must be let go before drawing resumes
            session.waitRightRelease = rightHeld;
            return;
        }
        tickDrawing(player, session, tip(player), rightHeld, now);
    }

    private void tickDrawing(Player player, PenSession session, Vector tip, boolean rightHeld, long now) {
        if (session.waitRightRelease) {
            if (rightHeld) return;
            session.waitRightRelease = false;
        }
        if (!session.drawing) {
            if (!rightHeld) return;
            startDrawing(player, session, tip, now);
        } else if (!rightHeld) {
            // The smoothed tip trails the pen; finish the line where the pen was at the last held tick
            if (!session.eraser && session.lastTip != null) addPoint(session, session.lastTip);
            stopDrawing(session, now);
            return;
        }
        session.lastTip = tip.clone();
        if (session.stroke != null) session.stroke.currentFacing = player.getEyeLocation().getDirection();
        sample(player, session, tip);
    }

    private void startDrawing(Player player, PenSession session, Vector tip, long now) {
        session.drawing = true;
        session.inkWarned = false;
        if (session.eraser) return;
        AirPenConfig config = plugin.config();
        if (strokes.remaining(player.getUniqueId()) <= 0) {
            session.stroke = null;
            warnOutOfInk(player, session);
            return;
        }
        session.stroke = strokes.begin(player, new Stroke(player.getUniqueId(), player.getWorld().getUID(),
                session.color, width(session), config.style(), player.getEyeLocation().getDirection()));
        session.lastPoint = tip.clone();
        session.smoothTip = tip.clone();
        session.lastTip = tip.clone();
        if (session.stroke.style == AirPenConfig.Style.DOTS) strokes.addDot(session.stroke, tip);
    }

    private void stopDrawing(PenSession session, long now) {
        if (!session.drawing) return;
        session.drawing = false;
        session.strokeEndedAt = now;
    }

    private void sample(Player player, PenSession session, Vector tip) {
        if (session.eraser) {
            Location eye = player.getEyeLocation();
            AirPenConfig config = plugin.config();
            int removed = strokes.eraseAlongRay(player.getUniqueId(), player.getWorld().getUID(),
                    eye.toVector(), eye.getDirection(), config.eraserRange(), config.eraserRadius());
            if (removed > 0) session.inkDirty = true;
            return;
        }
        // Follow the real tip part of the way each tick: filters hand jitter when moving slowly, while a fast
        // stroke follows the pen almost directly so the line does not lag behind it
        Vector smooth = session.smoothTip == null ? tip.clone() : session.smoothTip;
        Vector gap = tip.clone().subtract(smooth);
        double speed = Math.max(0, Math.min(1, (gap.length() - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED)));
        double base = plugin.config().smoothing();
        smooth.add(gap.multiply(base + (1 - base) * speed));
        session.smoothTip = smooth;
        if (!addPoint(session, smooth.clone())) warnOutOfInk(player, session);
    }

    private void warnOutOfInk(Player player, PenSession session) {
        if (session.inkWarned) return;
        session.inkWarned = true;
        player.sendActionBar(Component.text("インク切れ: 消しゴムか「元に戻す」で消すと、また描けます", NamedTextColor.RED));
    }

    /** Extends the current stroke towards {@code tip}; false when ink ran out. */
    private boolean addPoint(PenSession session, Vector tip) {
        Stroke stroke = session.stroke;
        if (stroke == null || session.lastPoint == null) return true;
        session.inkDirty = true;
        if (stroke.style == AirPenConfig.Style.DOTS) return stampDots(session, stroke, tip);
        AirPenConfig config = plugin.config();
        Vector step = tip.clone().subtract(session.lastPoint);
        if (step.length() < config.minStep()) return true;
        Stroke.Segment last = stroke.last();
        if (last != null && last.to.equals(session.lastPoint)) {
            Vector dir = last.direction();
            double angle = Math.toDegrees(dir.angle(step));
            if (angle < config.mergeAngleDeg() && dir.length() + step.length() <= config.maxSegmentLength()) {
                strokes.extendSegment(stroke, last, tip);
                session.lastPoint = tip.clone();
                return true;
            }
        }
        if (last != null && last.to.equals(session.lastPoint)) {
            Vector before = last.direction().normalize();
            Vector after = step.clone().normalize();
            if (Math.toDegrees(before.angle(after)) >= JOINT_CAP_ANGLE) {
                Vector bisector = before.clone().add(after);
                strokes.addCap(stroke, session.lastPoint, bisector.lengthSquared() < 1e-6 ? before : bisector);
            }
        }
        if (!strokes.addSegment(stroke, session.lastPoint, tip)) return false;
        session.lastPoint = tip.clone();
        return true;
    }

    /** Places dots at even spacing from the previous dot towards {@code tip}; the remainder carries over. */
    private boolean stampDots(PenSession session, Stroke stroke, Vector tip) {
        double spacing = Math.max(0.005, stroke.width * plugin.config().dotSpacing());
        Vector from = session.lastPoint;
        Vector delta = tip.clone().subtract(from);
        int count = (int) Math.floor(delta.length() / spacing);
        if (count == 0) return true;
        Vector step = delta.normalize().multiply(spacing);
        Vector point = from.clone();
        for (int i = 0; i < count; i++) {
            point.add(step);
            if (!strokes.addDot(stroke, point)) return false;
            session.lastPoint = point.clone();
        }
        return true;
    }

    // ------------------------------------------------------------------ ink & lifetime

    /** Admins draw without an ink limit. */
    private static boolean hasUnlimitedInk(UUID owner) {
        Player player = Bukkit.getPlayer(owner);
        return player != null && player.hasPermission("airpen.unlimited");
    }

    /** Shows the remaining ink as the durability of every pen the player carries. */
    public void refreshInk(@NotNull Player player, @NotNull PenSession session) {
        session.inkDirty = false;
        boolean unlimited = hasUnlimitedInk(player.getUniqueId());
        int max = plugin.config().maxSegmentsPerPlayer();
        int used = unlimited ? -1 : Math.min(max, strokes.used(player.getUniqueId()));
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!plugin.pen().isPen(item)) continue;
            if (plugin.pen().applyInk(item, used, max, plugin.config())) inventory.setItem(slot, item);
        }
    }

    /** Drawings disappear once their owner wanders off (other world, or farther than the configured distance). */
    private void clearFarStrokes() {
        double distance = plugin.config().autoClearDistance();
        if (distance <= 0) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PenSession session = sessions.get(player.getUniqueId());
            if (strokes.clearFar(player.getUniqueId(), player.getLocation(), distance) > 0 && session != null) {
                session.inkDirty = true;
            }
        }
    }

    /** Toggles the per-player input diagnostics; returns the new state. */
    public boolean toggleDebug(@NotNull Player player) {
        PenSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        session.debug = !session.debug;
        return session.debug;
    }

    private static void debug(Player player, PenSession session, String message) {
        if (session.debug) player.sendMessage(Component.text("[AirPen] " + message, NamedTextColor.DARK_GRAY));
    }

    /** Pens handed out later should show the current ink right away. */
    public void markInkDirty(@NotNull Player player) {
        PenSession session = sessions.get(player.getUniqueId());
        if (session != null) session.inkDirty = true;
    }

    /** After a reload or clearing everyone's ink: every pen's gauge and lore need redrawing. */
    public void markAllInkDirty() {
        sessions.values().forEach(session -> session.inkDirty = true);
    }

    public boolean isPen(@Nullable ItemStack item) {
        return plugin.pen().isPen(item);
    }

    /** Pen tip: a fixed distance in front of the eyes, or just in front of the first block in the way. */
    private Vector tip(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double distance = plugin.config().distance();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, dir, distance, FluidCollisionMode.NEVER, true);
        if (hit != null) return hit.getHitPosition().subtract(dir.multiply(0.03));
        return eye.toVector().add(dir.multiply(distance));
    }

    /** After a swallowed dig/use packet: show the client the real block again (undoes any prediction). */
    public void resyncBlock(@NotNull Player player, int x, int y, int z) {
        if (!player.isOnline()) return;
        player.sendBlockChange(new Location(player.getWorld(), x, y, z), player.getWorld().getBlockAt(x, y, z).getBlockData());
    }

    // ------------------------------------------------------------------ menu

    /** Left-click opens the menu; with a menu open, left-click picks what the gaze points at (or closes it). */
    private void tickMenu(Player player, PenSession session, boolean leftClick) {
        if (session.menu != null) {
            String reason = session.menu.isStale() ? "離れた"
                    : session.menu.idleFor() > MENU_IDLE_MS ? "放置"
                    : null;
            if (reason != null) {
                debug(player, session, "メニューを閉じた: " + reason);
                closeMenu(session);
            }
        }
        if (leftClick) {
            if (session.menu == null || session.menu.isBroken()) {
                closeMenu(session);
                session.menu = mainMenu(player, session);
                debug(player, session, "メニューを開いた");
            } else {
                PenMenu menu = session.menu;
                menu.tick(); // pick from where the player looks right now
                closeMenu(session);
                debug(player, session, "クリック: " + menu.release());
            }
        }
        if (session.menu != null) session.menu.tick();
    }

    private void closeMenu(PenSession session) {
        if (session.menu == null) return;
        session.menu.close();
        session.menu = null;
    }

    private RadialMenu mainMenu(Player player, PenSession session) {
        List<RadialMenu.Entry> entries = new ArrayList<>();
        entries.add(new RadialMenu.Entry(RadialMenu.swatch(session.color, "色"),
                () -> session.menu = colorMenu(player, session)));
        entries.add(new RadialMenu.Entry(Component.text("太さ: " + widthLabel(session), NamedTextColor.WHITE),
                () -> session.menu = widthMenu(player, session)));
        entries.add(new RadialMenu.Entry(onOff("消しゴム", session.eraser), () -> {
            session.eraser = !session.eraser;
            player.sendActionBar(onOff("消しゴム", session.eraser));
        }));
        entries.add(new RadialMenu.Entry(Component.text("↶ 元に戻す", NamedTextColor.WHITE), () -> {
            boolean undone = strokes.undo(player.getUniqueId());
            session.inkDirty = true;
            player.sendActionBar(Component.text(undone ? "最後の線を消しました" : "消せる線がありません", NamedTextColor.GRAY));
        }));
        entries.add(new RadialMenu.Entry(Component.text("🗑 全部消す", NamedTextColor.RED), () -> {
            int n = strokes.clear(player.getUniqueId());
            session.stroke = null;
            session.inkDirty = true;
            player.sendActionBar(Component.text(n + "本の線を消しました", NamedTextColor.GRAY));
        }));
        entries.add(new RadialMenu.Entry(onOff("他の人に見せる", session.visibleToOthers), () -> {
            session.visibleToOthers = !session.visibleToOthers;
            save(player, session);
            strokes.refreshVisibility(player.getUniqueId());
            player.sendActionBar(onOff("他の人に見せる", session.visibleToOthers));
        }));
        return new RadialMenu(plugin, player, Component.text("AirPen", NamedTextColor.GOLD), entries);
    }

    private RadialMenu colorMenu(Player player, PenSession session) {
        List<RadialMenu.Entry> entries = new ArrayList<>();
        for (AirPenConfig.NamedValue<Integer> color : plugin.config().colors()) {
            entries.add(new RadialMenu.Entry(RadialMenu.swatch(color.value(), color.name()), () -> setColor(player, session, color.value())));
        }
        entries.add(new RadialMenu.Entry(Component.text("# カラーコード", NamedTextColor.AQUA), () -> {
            session.awaitingColorUntil = System.currentTimeMillis() + 30_000;
            player.sendMessage(Component.text("チャットにカラーコード(例: #FF8800)を入力してください。「cancel」で中止します。", NamedTextColor.AQUA));
        }));
        return new RadialMenu(plugin, player, Component.text("色", NamedTextColor.GOLD), entries);
    }

    private PenMenu widthMenu(Player player, PenSession session) {
        AirPenConfig config = plugin.config();
        return new SliderMenu(plugin, player, width(session), config.widthMin(), config.widthMax(), session.color, value -> {
            session.width = value;
            save(player, session);
            player.sendActionBar(Component.text("太さ: " + widthLabel(session), NamedTextColor.GRAY));
        });
    }

    private void setColor(Player player, PenSession session, int rgb) {
        session.color = rgb;
        session.eraser = false;
        save(player, session);
        player.sendActionBar(RadialMenu.swatch(rgb, AirPenConfig.toHex(rgb)));
    }

    private static Component onOff(String label, boolean on) {
        return Component.text(label + ": ", NamedTextColor.WHITE)
                .append(Component.text(on ? "ON" : "OFF", on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
    }

    private float width(PenSession session) {
        AirPenConfig config = plugin.config();
        return Math.max(config.widthMin(), Math.min(config.widthMax(), session.width));
    }

    /** Players see the width as a whole number: hundredths of a block. */
    private String widthLabel(PenSession session) {
        return String.valueOf(SliderMenu.toLevel(width(session)));
    }

    // ------------------------------------------------------------------ settings & events

    private void load(Player player) {
        PenSession session = new PenSession();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        session.color = pdc.getOrDefault(keyColor, PersistentDataType.INTEGER, plugin.config().colors().get(0).value());
        session.width = loadWidth(pdc);
        session.visibleToOthers = pdc.getOrDefault(keyVisible, PersistentDataType.BYTE, (byte) 0) == 1;
        sessions.put(player.getUniqueId(), session);
    }

    /** Continuous width; earlier builds stored an index into the preset list, which is converted once. */
    private float loadWidth(PersistentDataContainer pdc) {
        Float value = pdc.get(keyWidthValue, PersistentDataType.FLOAT);
        if (value != null) return value;
        Integer index = pdc.get(keyWidth, PersistentDataType.INTEGER);
        List<AirPenConfig.NamedValue<Float>> presets = plugin.config().widths();
        if (index != null && index >= 0 && index < presets.size()) return presets.get(index).value();
        return plugin.config().widthDefault();
    }

    private void save(Player player, PenSession session) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(keyColor, PersistentDataType.INTEGER, session.color);
        pdc.set(keyWidthValue, PersistentDataType.FLOAT, session.width);
        pdc.set(keyVisible, PersistentDataType.BYTE, (byte) (session.visibleToOthers ? 1 : 0));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
        markInkDirty(event.getPlayer());
        strokes.showTo(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PenSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) closeMenu(session);
        strokes.clear(event.getPlayer().getUniqueId()); // drawings leave with their owner
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PenSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        closeMenu(session);
        stopDrawing(session, System.currentTimeMillis());
        strokes.clear(event.getPlayer().getUniqueId()); // drawings stay behind only while their owner is around
        session.stroke = null;
        session.inkDirty = true;
        strokes.showTo(event.getPlayer());
    }

    /** The durability bar is the ink gauge; the pen itself never wears out. */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (plugin.pen().isPen(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (plugin.pen().isPen(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        PenSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.awaitingColorUntil < System.currentTimeMillis()) return;
        event.setCancelled(true);
        session.awaitingColorUntil = 0;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel") || text.equals("キャンセル")) {
                player.sendMessage(Component.text("カラーコードの入力を中止しました。", NamedTextColor.GRAY));
                return;
            }
            Integer rgb = AirPenConfig.parseHex(text);
            if (rgb == null) {
                player.sendMessage(Component.text("カラーコードは #RRGGBB の形で入力してください: " + text, NamedTextColor.RED));
                return;
            }
            setColor(player, session, rgb);
            boolean blocks = plugin.config().style() == AirPenConfig.Style.BAR;
            player.sendMessage(RadialMenu.swatch(rgb, AirPenConfig.toHex(rgb) + " に設定しました"
                    + (blocks ? "(近いブロックの色で描かれます)" : "")));
        });
    }
}
