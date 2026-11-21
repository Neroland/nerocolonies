package za.co.neroland.nerocolonies.menu;

/**
 * A non-negative value carried across two {@link net.minecraft.world.inventory.ContainerData} slots.
 * Vanilla syncs each slot as a signed short, so a value above 32,767 (an energy buffer of 50,000)
 * truncates on the wire; split into two 15-bit halves it arrives intact. Used for the exact amounts
 * behind the gauge tooltips; machine-scoped numbers only.
 */
public final class SplitValue {

    /** Bits in one half: 15, so each half survives the signed-short sync. */
    public static final int HALF_BITS = 15;
    private static final int HALF_MASK = (1 << HALF_BITS) - 1;
    /** Largest value a pair carries (2^30 - 1); larger values clamp, negative values read as 0. */
    public static final long MAX = (1L << (2 * HALF_BITS)) - 1L;

    private SplitValue() {
    }

    /** Low 15 bits of {@code value} clamped to 0..{@link #MAX}. */
    public static int low(long value) {
        return (int) (clamp(value) & HALF_MASK);
    }

    /** High 15 bits of {@code value} clamped to 0..{@link #MAX}. */
    public static int high(long value) {
        return (int) ((clamp(value) >> HALF_BITS) & HALF_MASK);
    }

    /** Rejoin a value split by {@link #low} and {@link #high}. */
    public static long join(int low, int high) {
        return ((long) (high & HALF_MASK) << HALF_BITS) | (low & HALF_MASK);
    }

    private static long clamp(long value) {
        return Math.max(0L, Math.min(MAX, value));
    }
}
