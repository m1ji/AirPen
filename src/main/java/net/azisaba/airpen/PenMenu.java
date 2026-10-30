package net.azisaba.airpen;

import org.jetbrains.annotations.NotNull;

/** A menu floating in front of one player, driven by gaze while right-click is held and decided on release. */
public interface PenMenu {
    /** Called every tick while the menu is open: follow the player's gaze. */
    void tick();

    /** Right-click was let go: apply what the gaze points at. Runs after {@link #close()}. Returns a debug note. */
    @NotNull String release();

    /** The player wandered off (other world, or too far from where the menu was opened). */
    boolean isStale();

    /** Any of the menu's displays was removed from outside (e.g. an entity clean-up). */
    boolean isBroken();

    long idleFor();

    void close();
}
