package net.azisaba.airpen;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Turns client packets into pen input while the pen is held: right-click holding/releasing, left-click swings,
 * and swallows block digging / block and entity interaction so the pen never breaks or opens anything.
 * <ul>
 *   <li>Right-click: "use item" starts using the pen (held); its release packet ends the hold at once. Clicks on
 *       blocks/entities that do not start using the pen repeat every 4 ticks while held instead.</li>
 *   <li>Left-click: every press swings the arm (holding it on a block keeps swinging and digging, which is
 *       swallowed so nothing is mined).</li>
 * </ul>
 * Runs on the network thread: it only writes timestamps and schedules block resyncs.
 */
public final class InputListener extends PacketAdapter {
    private final PenController controller;

    public InputListener(@NotNull AirPenPlugin plugin, @NotNull PenController controller) {
        super(plugin, ListenerPriority.HIGH,
                PacketType.Play.Client.BLOCK_DIG, PacketType.Play.Client.ARM_ANIMATION, PacketType.Play.Client.USE_ITEM_ON,
                PacketType.Play.Client.USE_ENTITY, PacketType.Play.Client.USE_ITEM, PacketType.Play.Client.HELD_ITEM_SLOT);
        this.controller = controller;
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        PenSession session = player == null ? null : controller.session(player.getUniqueId());
        if (session == null) return;
        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_SLOT) {
            // The main thread only notices the new slot on its next tick; packets right after it (digging with the
            // pen just taken out) must already be treated as pen input
            try {
                session.holdingPen = controller.isPen(player.getInventory().getItem(event.getPacket().getIntegers().read(0)));
            } catch (RuntimeException ignored) {
                // inventory changing under us: the next tick sets it right
            }
            return;
        }
        if (!session.holdingPen) return;
        PacketContainer packet = event.getPacket();
        long now = System.currentTimeMillis();
        PacketType type = event.getPacketType();

        if (type == PacketType.Play.Client.ARM_ANIMATION) {
            session.lastSwing = now;
            return;
        }
        if (type == PacketType.Play.Client.USE_ITEM) {
            // Right-click into the air: also repeated every 4 ticks while held unless the pen is being "used".
            // Left untouched, it is what starts using the pen.
            session.lastRightClick = now;
            return;
        }
        if (type == PacketType.Play.Client.BLOCK_DIG) {
            EnumWrappers.PlayerDigType dig = packet.getPlayerDigTypes().read(0);
            switch (dig) {
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                    // the pen never breaks blocks
                }
                case RELEASE_USE_ITEM -> {
                    // Right-click let go while the pen was being "used": an exact release, no waiting needed
                    session.lastUseRelease = now;
                    return;
                }
                default -> {
                    return; // drop item, swap hands: leave untouched
                }
            }
            swallow(event, packet.getBlockPositionModifier().read(0));
            return;
        }
        if (type == PacketType.Play.Client.USE_ITEM_ON) {
            // Right-clicking a real block the client treats as interactive never starts "using" the pen, but the
            // client repeats the click every 4 ticks while the button is held: that stands in for the hold
            session.lastRightClick = now;
            session.lastInteract = now;
            swallow(event, packet.getMovingBlockPositions().read(0).getBlockPosition());
            return;
        }
        if (type == PacketType.Play.Client.USE_ENTITY) {
            EnumWrappers.EntityUseAction action = packet.getEnumEntityUseActions().read(0).getAction();
            if (action == EnumWrappers.EntityUseAction.ATTACK) {
                session.lastAttack = now;
                return;
            }
            // Same for right-clicking an entity; the pen does not interact with entities
            session.lastRightClick = now;
            session.lastInteract = now;
            event.setCancelled(true);
        }
    }

    /** Cancels the packet, acknowledges its prediction sequence and restores what the client should see there. */
    private void swallow(PacketEvent event, BlockPosition position) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        int sequence = event.getPacket().getIntegers().read(0);
        PacketContainer ack = new PacketContainer(PacketType.Play.Server.BLOCK_CHANGED_ACK);
        ack.getIntegers().write(0, sequence);
        ProtocolLibrary.getProtocolManager().sendServerPacket(player, ack);
        Bukkit.getScheduler().runTask(plugin, () -> controller.resyncBlock(player, position.getX(), position.getY(), position.getZ()));
    }
}
