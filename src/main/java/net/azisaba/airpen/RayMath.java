package net.azisaba.airpen;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** Ray vs. segment proximity for the line-of-sight eraser. */
final class RayMath {
    private RayMath() {
    }

    /**
     * Distance along the ray ({@code origin} + t·{@code dir}, {@code dir} normalised) to the point where it comes
     * closest to segment {@code a}–{@code b}, if it passes within {@code reach} of it; -1 otherwise.
     */
    static double hitDistance(@NotNull Vector origin, @NotNull Vector dir, @NotNull Vector a, @NotNull Vector b, double reach) {
        Vector seg = b.clone().subtract(a);
        Vector w = origin.clone().subtract(a);
        double segLen2 = seg.lengthSquared();
        double t; // along the ray
        double s; // along the segment, 0..1
        if (segLen2 < 1e-9) {
            t = Math.max(0, a.clone().subtract(origin).dot(dir));
            s = 0;
        } else {
            double b1 = dir.dot(seg);
            double d = dir.dot(w);
            double e = seg.dot(w);
            double denom = segLen2 - b1 * b1; // |dir| = 1
            s = denom < 1e-9 ? 0 : (e - b1 * d) / denom;
            s = Math.max(0, Math.min(1, s));
            Vector q = a.clone().add(seg.clone().multiply(s));
            t = Math.max(0, q.clone().subtract(origin).dot(dir));
        }
        Vector onRay = origin.clone().add(dir.clone().multiply(t));
        Vector onSeg = a.clone().add(seg.multiply(s));
        return onRay.distance(onSeg) <= reach ? t : -1;
    }
}
