package net.azisaba.airpen;

import com.comphenix.protocol.ProtocolLibrary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Stream;

public final class AirPenPlugin extends JavaPlugin implements TabExecutor {
    private AirPenConfig config;
    private PenItem pen;
    private PenController controller;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        config = AirPenConfig.load(getConfig());
        pen = new PenItem(this);
        controller = new PenController(this);
        getServer().getPluginManager().registerEvents(controller, this);
        ProtocolLibrary.getProtocolManager().addPacketListener(new InputListener(this, controller));
        controller.start();
        PluginCommand command = getCommand("airpen");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        ProtocolLibrary.getProtocolManager().removePacketListeners(this);
        if (controller != null) controller.shutdown();
    }

    private static final String CONFIG_VERSION = "config-version";
    private static final List<String> OLD_LORE = List.of(
            "<gray>左クリック長押し: 描く", "<gray>右クリック長押し: メニュー(視線で選んで離す)");
    private static final List<String> NEW_LORE = List.of(
            "<gray>右クリック長押し: 描く", "<gray>左クリック: メニュー(見て左クリックで決定)");

    /** Replaces values written by earlier builds whose defaults were wrong (only when still untouched). */
    private void migrateConfig() {
        boolean changed = false;
        // Old defaults are replaced once only: afterwards the same value may well be the admin's own choice
        int version = getConfig().isSet(CONFIG_VERSION) ? getConfig().getInt(CONFIG_VERSION) : 1;
        if (version < 2) {
            replaceDefault("ribbon.base-x", -0.075, -0.05);
            replaceDefault("menu.radius", 0.75, 1.2);
            replaceDefault("menu.center-radius", 0.28, 0.38);
            replaceDefault("menu.distance", 2.2, 1.8);
            replaceDefault("drawing.max-segments-per-player", 1500, 2000);
            getConfig().set(CONFIG_VERSION, 2);
            changed = true;
        }
        if (getConfig().isSet("drawing.view-range") && !getConfig().isSet("drawing.view-distance")) {
            // view-range (a multiplier) was replaced by view-distance in blocks
            getConfig().set("drawing.view-range", null);
            getConfig().set("drawing.view-distance", 50);
            changed = true;
        }
        // Left-click holding (barrier cursor) was replaced by right-click holding: these settings are gone
        for (String obsolete : List.of("drawing.release-ms", "drawing.continue-ms", "drawing.continue-distance",
                "drawing.cursor-radius")) {
            if (getConfig().isSet(obsolete)) {
                getConfig().set(obsolete, null);
                changed = true;
            }
        }
        if (getConfig().getStringList("pen.lore").equals(OLD_LORE)) {
            getConfig().set("pen.lore", NEW_LORE);
            changed = true;
        }
        if (changed) {
            saveConfig();
            getLogger().info("Updated outdated default values in config.yml");
        }
    }

    private void replaceDefault(String path, double old, double replacement) {
        if (!getConfig().isSet(path) || Math.abs(getConfig().getDouble(path) - old) > 1e-9) return;
        getConfig().set(path, replacement);
    }

    public @NotNull AirPenConfig config() {
        return config;
    }

    public @NotNull PenItem pen() {
        return pen;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "give" -> {
                if (!sender.hasPermission("airpen.admin")) return deny(sender);
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    sender.sendMessage(Component.text("プレイヤーが見つかりません", NamedTextColor.RED));
                    return true;
                }
                target.getInventory().addItem(pen.create(config));
                controller.markInkDirty(target);
                sender.sendMessage(Component.text(target.getName() + " にエアペンを渡しました", NamedTextColor.GREEN));
            }
            case "clear" -> {
                if (args.length >= 2) {
                    if (!sender.hasPermission("airpen.admin")) return deny(sender);
                    if (args[1].equalsIgnoreCase("all")) {
                        int n = controller.strokes().clearAll();
                        controller.markAllInkDirty();
                        sender.sendMessage(Component.text("全員の線を消しました (" + n + "本)", NamedTextColor.GREEN));
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Component.text("プレイヤーが見つかりません", NamedTextColor.RED));
                        return true;
                    }
                    int n = controller.strokes().clear(target.getUniqueId());
                    controller.markInkDirty(target);
                    sender.sendMessage(Component.text(target.getName() + " の線を消しました (" + n + "本)", NamedTextColor.GREEN));
                } else if (sender instanceof Player p) {
                    int n = controller.strokes().clear(p.getUniqueId());
                    controller.markInkDirty(p);
                    sender.sendMessage(Component.text("自分の線を消しました (" + n + "本)", NamedTextColor.GREEN));
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("airpen.admin")) return deny(sender);
                reloadConfig();
                migrateConfig();
                config = AirPenConfig.load(getConfig());
                controller.markAllInkDirty(); // ink limit or lore may have changed
                sender.sendMessage(Component.text("AirPen の設定を再読み込みしました", NamedTextColor.GREEN));
            }
            case "inspect" -> {
                if (!sender.hasPermission("airpen.admin")) return deny(sender);
                if (sender instanceof Player p) {
                    for (String line : controller.strokes().inspect(p.getEyeLocation(), p.getEyeLocation().getDirection(), 32)) {
                        sender.sendMessage(Component.text(line, NamedTextColor.YELLOW));
                    }
                }
            }
            case "cleanup" -> {
                if (!sender.hasPermission("airpen.admin")) return deny(sender);
                int[] removed = controller.strokes().sweepOrphans();
                sender.sendMessage(Component.text("迷子のエンティティを削除しました: インク " + removed[0] + " 個 / メニュー " + removed[1] + " 個", NamedTextColor.GREEN));
            }
            case "debug" -> {
                if (!sender.hasPermission("airpen.admin")) return deny(sender);
                if (sender instanceof Player p) {
                    boolean on = controller.toggleDebug(p);
                    sender.sendMessage(Component.text("AirPen デバッグ表示: " + (on ? "ON" : "OFF"), NamedTextColor.YELLOW));
                }
            }
            case "calibrate" -> {
                if (!sender.hasPermission("airpen.admin")) return deny(sender);
                if (sender instanceof Player p) calibrate(p);
            }
            default -> sender.sendMessage(Component.text("/airpen give [player] | clear [player|all] | reload | calibrate | debug | cleanup | inspect", NamedTextColor.GRAY));
        }
        return true;
    }

    /**
     * Pastes a red ribbon that should cover the targeted block face exactly (20 s). If it is offset or the wrong
     * size, the ribbon.base-* values (taken from the client's text renderer) need adjusting.
     */
    private void calibrate(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(8, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            player.sendMessage(Component.text("ブロックを見ながら実行してください", NamedTextColor.RED));
            return;
        }
        BlockFace face = hit.getHitBlockFace();
        Vector normal = face.getDirection();
        Location center = hit.getHitBlock().getLocation().add(0.5, 0.5, 0.5).add(normal.clone().multiply(0.51));
        Vector inPlane = normal.clone().crossProduct(new Vector(0, 1, 0));
        if (inPlane.lengthSquared() < 1e-6) inPlane = new Vector(1, 0, 0);
        // Zero-length strip of width 1 = a 1x1 square centred on the face
        Vector direction = inPlane.normalize().multiply(0.001);
        // Tagged as menu, not ink: the orphan sweep would take untracked ink away before the 20 s are up
        List<TextDisplay> parts = List.of(
                LineDisplays.asMenu(LineDisplays.ribbon(center, direction, 1f, normal, 0xC0FF2020, false, config)),
                LineDisplays.asMenu(LineDisplays.ribbon(center, direction, 1f, normal, 0xC02020FF, true, config)));
        parts.forEach(d -> player.showEntity(this, d));
        Bukkit.getScheduler().runTaskLater(this, () -> parts.forEach(TextDisplay::remove), 400);
        LineGeometry.RibbonBase base = config.ribbonBase();
        player.sendMessage(Component.text("面にぴったり重なれば OK(表=赤、裏=青)。ずれる場合は config.yml の ribbon.base-* を調整して /airpen reload", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("現在値: base-x=" + base.x() + " base-y=" + base.y()
                + " base-width=" + base.width() + " base-height=" + base.height(), NamedTextColor.GRAY));
    }

    private static boolean deny(CommandSender sender) {
        sender.sendMessage(Component.text("権限がありません", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        boolean admin = sender.hasPermission("airpen.admin");
        if (args.length == 1) {
            Stream<String> subs = admin ? Stream.of("give", "clear", "reload", "calibrate", "debug", "cleanup", "inspect")
                    : Stream.of("clear");
            return subs.filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && admin && !args[0].equalsIgnoreCase("reload")) {
            Stream<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName);
            if (args[0].equalsIgnoreCase("clear")) names = Stream.concat(Stream.of("all"), names);
            return names.filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
