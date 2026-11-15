package za.co.neroland.nerocolonies.colony;

/**
 * How the colony tick and the visible work share a trade's goods without counting them twice.
 *
 * <p>A Neran that is loaded and working really gathers: the farmer harvests, the forester fells, the
 * miner digs, and every drop goes to colony storage. {@link Professions#produce} still rolls a
 * trade's {@code outputs} for everyone else, so a colony nobody is near keeps producing. The rule
 * that keeps the two apart: a Neran whose trade did <b>productive</b> work (work that put goods in
 * storage) within the last {@value #WINDOW_CYCLES} colony cycles gets no rolled outputs that cycle.
 *
 * <p>The time of the last productive work lives on the entity for the session only and is never
 * saved. After a restart every Neran starts as "not working", which can only err towards the colony
 * tick producing, never towards nothing being produced. Pure; unit-tested.
 */
public final class WorkAccounting {

    /** How many colony cycles one piece of productive work covers. */
    public static final int WINDOW_CYCLES = 2;

    /** The "never worked" marker. */
    public static final long NEVER = Long.MIN_VALUE;

    private WorkAccounting() {
    }

    /** The window in game ticks for a colony tick interval of {@code tickInterval} ticks. */
    public static long window(int tickInterval) {
        return (long) WINDOW_CYCLES * Math.max(1, tickInterval);
    }

    /**
     * Whether work done at {@code last} still counts at {@code now}. A time in the future (the clock
     * went backwards, e.g. a world swapped under a running server) does not count.
     */
    public static boolean recent(long last, long now, long window) {
        if (last == NEVER || now < last || window <= 0) {
            return false;
        }
        return now - last <= window;
    }
}
