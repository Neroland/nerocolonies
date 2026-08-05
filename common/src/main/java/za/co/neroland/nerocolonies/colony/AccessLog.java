package za.co.neroland.nerocolonies.colony;

import java.util.Locale;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The optional colony access log — <b>OFF by default</b> ({@code accessLogEnabled=false}).
 *
 * <h2>What it is for</h2>
 *
 * <p>A shared colony on a multiplayer server occasionally needs an answer to "who dissolved it?" or
 * "who took my access away?". That is the entire purpose. It is not analytics, it is not a
 * behaviour record, and it is not on unless an operator turns it on.
 *
 * <h2>What it stores, and nothing else (POPIA/GDPR)</h2>
 *
 * <ul>
 *   <li>the acting player's existing Minecraft game UUID;</li>
 *   <li>one {@link Action} enum value;</li>
 *   <li>a whole-second epoch timestamp.</li>
 * </ul>
 *
 * <p>Never a name, never an IP, never chat, and <b>never coordinates</b> — rows are filed under a
 * colony id, which is as precise as the location ever gets. Rows expire after
 * {@code accessLogRetentionDays} (default 7) via the retention sweep in {@link ColonyState}, a
 * player's rows are purged by the erasure hook, and {@code /nerocolonies data export} returns the
 * calling player's own rows only.
 */
public final class AccessLog {

    /** Hard cap on rows kept per colony, so a scripted client cannot grow the file without bound. */
    public static final int MAX_ROWS_PER_COLONY = 256;

    private AccessLog() {
    }

    /** The closed set of things worth recording. Anything not on this list is not logged at all. */
    public enum Action {
        FOUND,
        OPEN,
        RENAME,
        ACCESS_GRANT,
        ACCESS_REVOKE,
        OWNER_CHANGE,
        DISSOLVE;

        private static final Action[] VALUES = values();

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Unknown keys resolve to {@link #OPEN} rather than failing a whole file load. */
        public static Action fromKey(String key) {
            for (Action action : VALUES) {
                if (action.key().equalsIgnoreCase(key)) {
                    return action;
                }
            }
            return OPEN;
        }

        public static final Codec<Action> CODEC = Codec.STRING.xmap(Action::fromKey, Action::key);
    }

    /** One logged action. Immutable, and deliberately three fields wide. */
    public record Entry(UUID player, Action action, long epochSeconds) {

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Colony.UUID_CODEC.fieldOf("player").forGetter(Entry::player),
                Action.CODEC.optionalFieldOf("action", Action.OPEN).forGetter(Entry::action),
                Codec.LONG.optionalFieldOf("at", 0L).forGetter(Entry::epochSeconds)
        ).apply(instance, Entry::new));

        public static Entry now(UUID player, Action action) {
            return new Entry(player, action, System.currentTimeMillis() / 1000L);
        }

        /** Whether this row is older than {@code days} days as of {@code nowSeconds}. */
        public boolean expired(long nowSeconds, int days) {
            return days > 0 && this.epochSeconds > 0L
                    && this.epochSeconds < nowSeconds - days * 86_400L;
        }
    }
}
