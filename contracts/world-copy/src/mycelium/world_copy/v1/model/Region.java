package mycelium.world_copy.v1.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Axis-aligned block region: {@code min} inclusive, {@code max} exclusive on every axis.
 * A region with any zero-length axis is empty.
 */
public record Region(Pos min, Pos max) {
    public Region {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (max.x() < min.x() || max.y() < min.y() || max.z() < min.z()) {
            throw new IllegalArgumentException("max must be >= min on every axis");
        }
    }

    public static Region cube(Pos origin, int sizeX, int sizeY, int sizeZ) {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
            throw new IllegalArgumentException("sizes must be >= 0");
        }
        return new Region(origin, origin.offset(sizeX, sizeY, sizeZ));
    }

    public long size() {
        return (long) (max.x() - min.x()) * (max.y() - min.y()) * (max.z() - min.z());
    }

    public boolean contains(Pos p) {
        return p.x() >= min.x() && p.x() < max.x()
                && p.y() >= min.y() && p.y() < max.y()
                && p.z() >= min.z() && p.z() < max.z();
    }

    /** All positions in x-major, then y, then z order. Deterministic. */
    public List<Pos> positions() {
        List<Pos> out = new ArrayList<>((int) Math.min(size(), Integer.MAX_VALUE));
        for (int x = min.x(); x < max.x(); x++) {
            for (int y = min.y(); y < max.y(); y++) {
                for (int z = min.z(); z < max.z(); z++) {
                    out.add(new Pos(x, y, z));
                }
            }
        }
        return out;
    }
}
