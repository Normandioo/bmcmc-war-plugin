package com.cumania.war.site;

import java.util.List;

/** A city's land: its capture limit or province outline from the site's SVGs, in blocks. Several rings are combined the even-odd way (holes work). */
public final class Polygon {
    private final List<int[]> rings;
    public final int minX, minZ, maxX, maxZ;

    public Polygon(List<int[]> rings) {
        this.rings = rings;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int[] ring : rings) for (int i = 0; i + 1 < ring.length; i += 2) {
            x0 = Math.min(x0, ring[i]); x1 = Math.max(x1, ring[i]);
            z0 = Math.min(z0, ring[i + 1]); z1 = Math.max(z1, ring[i + 1]);
        }
        minX = x0; minZ = z0; maxX = x1; maxZ = z1;
    }

    public boolean contains(double x, double z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
        boolean inside = false;
        for (int[] ring : rings) {
            int n = ring.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = ring[2 * i], zi = ring[2 * i + 1], xj = ring[2 * j], zj = ring[2 * j + 1];
                if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside;
            }
        }
        return inside;
    }

    /** A round area for the rare city that has no outline of its own. */
    public static Polygon circle(int cx, int cz, int radius) {
        int[] ring = new int[2 * 32];
        for (int i = 0; i < 32; i++) {
            double a = 2 * Math.PI * i / 32;
            ring[2 * i] = (int) Math.round(cx + radius * Math.cos(a));
            ring[2 * i + 1] = (int) Math.round(cz + radius * Math.sin(a));
        }
        return new Polygon(List.of(ring));
    }
}
