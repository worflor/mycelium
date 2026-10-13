package mycelium.world_clock.v1.model;

/**
 * What a client is told about a clock (26.x's {@code ClockNetworkState}): ticks, fraction, and the rate it should run
 * at. There is no pause flag: a paused clock, or one whose {@code advance_time} rule is off, is sent with rate zero.
 */
public record NetworkState(long totalTicks, float partialTick, float rate) {
}
