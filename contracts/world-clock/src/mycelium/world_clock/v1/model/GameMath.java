package mycelium.world_clock.v1.model;

/**
 * The game's own floor, as {@code Mth.floor} writes it in 26.3: {@code (int) Math.floor((double) v)}. The narrowing to
 * {@code int} saturates, exactly as the game's does, so a clock fed an absurd rate behaves as the game does.
 */
public final class GameMath {
    private GameMath() {
    }

    public static int floor(float v) {
        return (int) Math.floor((double) v);
    }

    public static int floor(double v) {
        return (int) Math.floor(v);
    }
}
