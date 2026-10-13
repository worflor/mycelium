package mycelium.bridges.world_clock;

import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.NetworkState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What crosses to a client, as bytes: the game time it was true at and each clock's {@link NetworkState}, the same three
 * numbers 26.x's own time packet carries. It is plain {@code java.io}, so it is the same on every version and every loader;
 * the mod's {@link Transport} decides how the bytes travel.
 */
public final class ClockWire {
    private static final int VERSION = 1;
    private static final int MOST_CLOCKS = 4096;

    private ClockWire() {
    }

    public static byte[] encode(ClockSync sync) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeLong(sync.gameTime());
            out.writeInt(sync.clocks().size());
            for (Map.Entry<ClockId, NetworkState> e : sync.clocks().entrySet()) {
                out.writeUTF(e.getKey().id());
                out.writeLong(e.getValue().totalTicks());
                out.writeFloat(e.getValue().partialTick());
                out.writeFloat(e.getValue().rate());
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    /** The sync in {@code payload}; a payload that is not one (or is truncated) is an {@link IllegalArgumentException}. */
    public static ClockSync decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("a clock payload of version " + version + ", not " + VERSION);
            }
            long gameTime = in.readLong();
            int n = in.readInt();
            if (n < 0 || n > MOST_CLOCKS) {
                throw new IllegalArgumentException("a clock payload of " + n + " clocks");
            }
            Map<ClockId, NetworkState> clocks = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                ClockId id = new ClockId(in.readUTF());
                clocks.put(id, new NetworkState(in.readLong(), in.readFloat(), in.readFloat()));
            }
            return new ClockSync(gameTime, clocks);
        } catch (IOException truncated) {
            throw new IllegalArgumentException("a clock payload that ends early", truncated);
        }
    }
}
