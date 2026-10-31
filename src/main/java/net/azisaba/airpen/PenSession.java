package net.azisaba.airpen;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;


/** Per-player pen state. The volatile fields are written from the network thread (ProtocolLib). */
public final class PenSession {
    // settings (saved in the player's persistent data)
    int color = 0xF0F0F0;
    float width = 0.04f;
    /** Eye-to-tip distance; NaN = not chosen yet, follow the server default. */
    double distance = Double.NaN;
    boolean eraser;
    boolean visibleToOthers;

    // input signals (ms timestamps), written by the packet listener
    volatile boolean holdingPen;
    volatile long lastSwing;
    /** Last attack on an entity; its arm swing must not start a line. */
    volatile long lastAttack;
    /** Last right-click on a block/entity; repeated while held when the client does not start using the pen. */
    volatile long lastRightClick;
    /** Right-click released while the pen was in use (explicit packet). */
    volatile long lastUseRelease;
    /** Last right-click on a block/entity; the client swings the arm for interactive ones (chests, villagers...). */
    volatile long lastInteract;

    // drawing
    long handledSwing;
    /** Right-click was held when a menu opened: wait for it to be let go before drawing again. */
    boolean waitRightRelease;
    boolean drawing;
    @Nullable Stroke stroke;
    @Nullable Vector lastPoint;
    /** Pen tip with hand jitter smoothed out; this is what gets drawn. */
    @Nullable Vector smoothTip;
    /** Raw tip at the last tick right-click was held, i.e. where the pen was when released. */
    @Nullable Vector lastTip;
    long strokeEndedAt;
    /** "Out of ink" was already shown for the current press. */
    boolean inkWarned;
    /** Ink changed since the pens' durability bars were last updated. */
    boolean inkDirty;


    // menu
    @Nullable PenMenu menu;
    /** /airpen debug: show input state in the action bar and menu events in chat. */
    boolean debug;
    /** Written from the async chat thread too. */
    volatile long awaitingColorUntil;
}
