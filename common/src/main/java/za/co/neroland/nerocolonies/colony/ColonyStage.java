package za.co.neroland.nerocolonies.colony;

import java.util.Locale;

/**
 * The five stages a colony grows through. Ordinal order is growth order, and the ordinal is what is
 * persisted and synced, so stages are append-only.
 *
 * <ul>
 *   <li>{@link #FOUNDING} — two Founding Nerans and nothing else. The four Starter Works (house, farm,
 *       lumber yard, mine head) build <b>only</b> from materials the player supplies.</li>
 *   <li>{@link #SETTLED} — the Starter Works stand. Gatherers fill the needs list, land is cleared,
 *       professions are handed out, and everything builds slowly from scrap or quickly when
 *       supplied.</li>
 *   <li>{@link #GROWING} — breeding, broader auto-planning, a wider claim.</li>
 *   <li>{@link #THRIVING} — faster growth; extravagant buildings unlock.</li>
 *   <li>{@link #METROPOLIS} — the showpieces.</li>
 * </ul>
 */
public enum ColonyStage {
    FOUNDING, SETTLED, GROWING, THRIVING, METROPOLIS;

    /** Lower-case name, as used in data files and lang keys. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a data-file stage name; unknown or missing means {@link #SETTLED}. */
    public static ColonyStage parse(String raw) {
        if (raw != null) {
            for (ColonyStage stage : values()) {
                if (stage.key().equalsIgnoreCase(raw.trim())) {
                    return stage;
                }
            }
        }
        return SETTLED;
    }

    /** The stage for a persisted or synced ordinal, clamped. */
    public static ColonyStage byOrdinal(int ordinal) {
        ColonyStage[] all = values();
        return all[Math.clamp(ordinal, 0, all.length - 1)];
    }

    /** Whether this stage has reached {@code other}. */
    public boolean atLeast(ColonyStage other) {
        return ordinal() >= other.ordinal();
    }

    /** The next stage, or this one at the top. */
    public ColonyStage next() {
        return this == METROPOLIS ? this : values()[ordinal() + 1];
    }
}
