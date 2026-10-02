package za.co.neroland.nerocolonies.entity;

/**
 * What a Neran is doing, shown as a small symbol above its head. Ordinal is synced: append only.
 */
public enum NeranStatus {
    NONE(""),
    WORKING("⚒"),
    HAULING("⇄"),
    NO_PATH("✕"),
    HUNGRY("♨"),
    NO_TOOL("❓"),
    SLEEPING("☾"),
    GUARDING("⚔"),
    SOCIAL("☺");

    private final String symbol;

    NeranStatus(String symbol) {
        this.symbol = symbol;
    }

    /** The symbol drawn before the Neran's name, or empty. */
    public String symbol() {
        return this.symbol;
    }

    public static NeranStatus byOrdinal(int ordinal) {
        NeranStatus[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NONE;
    }
}
