package net.azisaba.airpen;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Display transformations that turn a block display or a text display background into a line. */
public final class LineGeometry {
    private LineGeometry() {
    }

    /**
     * Size of a text display's background for the text " " in display-local blocks (before any transformation),
     * from vanilla's text renderer: 0.025 blocks per font pixel; x spans -w/2 .. w/2+1 px for the 4 px space,
     * y spans one 10 px line plus a 1 px margin upwards from the origin. Configurable because it comes from the
     * client's renderer, not from anything the server can measure.
     */
    public record RibbonBase(float x, float y, float width, float height) {
    }

    /**
     * Bar from the display's origin along {@code direction}, {@code width} thick, overshooting by half the width
     * at both ends so joints close. The cross-section is turned so one face points along {@code facing}, which
     * keeps the faces of neighbouring bars aligned.
     */
    public static @NotNull Matrix4f bar(@NotNull Vector direction, float width, @NotNull Vector facing) {
        Vector3f z = vec(direction);
        float length = Math.max(z.length(), 0.001f);
        z.normalize();
        Vector3f x = perpendicular(z, vec(facing));
        Vector3f y = new Vector3f(z).cross(x).normalize();
        return new Matrix4f()
                .set3x3(new Matrix3f(x, y, z))
                .translate(0, 0, -width / 2)
                .scale(width, width, length + width)
                .translate(-0.5f, -0.5f, 0);
    }

    /**
     * Flat strip from the display's origin along {@code direction}, {@code width} wide, lying in the plane that
     * faces {@code facing}. {@code back} turns the strip over (rotates it 180° about the line) so a second display
     * covers the other side.
     */
    public static @NotNull Matrix4f ribbon(@NotNull Vector direction, float width, @NotNull Vector facing,
                                           @NotNull RibbonBase base, boolean back) {
        Vector3f along = vec(direction);
        float length = Math.max(along.length(), 0.001f);
        along.normalize();
        Vector3f across = perpendicular(along, vec(facing));
        if (back) across.negate();
        Vector3f normal = new Vector3f(along).cross(across).normalize();
        Vector3f offset = new Vector3f(along).mul(-width / 2).add(new Vector3f(across).mul(-width / 2));
        return new Matrix4f()
                .translate(offset)
                .mul(new Matrix4f().set3x3(new Matrix3f(along, across, normal)))
                .scale((length + width) / base.width(), width / base.height(), 1)
                .translate(-base.x(), -base.y(), 0);
    }

    /** Square of side {@code width} centred on the display's origin (meant for a camera-facing billboard). */
    public static @NotNull Matrix4f dot(float width, @NotNull RibbonBase base) {
        return new Matrix4f()
                .scale(width / base.width(), width / base.height(), 1)
                .translate(-(base.x() + base.width() / 2), -(base.y() + base.height() / 2), 0);
    }

    /** Unit vector perpendicular to {@code axis} and {@code hint}; falls back to world axes when they are parallel. */
    private static Vector3f perpendicular(Vector3f axis, Vector3f hint) {
        Vector3f p = new Vector3f(axis).cross(hint);
        if (p.lengthSquared() < 1e-6f) p = new Vector3f(axis).cross(0, 1, 0);
        if (p.lengthSquared() < 1e-6f) p = new Vector3f(axis).cross(1, 0, 0);
        return p.normalize();
    }

    private static Vector3f vec(Vector v) {
        return new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ());
    }
}
