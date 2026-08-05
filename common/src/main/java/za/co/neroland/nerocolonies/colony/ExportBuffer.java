package za.co.neroland.nerocolonies.colony;

import java.util.Map;
import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.economy.CoreCurrencies;
import za.co.neroland.nerolandcore.economy.CurrencyApi;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ExportEntry;

/**
 * The colony's export buffer: where jobs flagged {@code export} put their output, and the one place
 * in NeroColonies that touches money.
 *
 * <h2>A capability, not an API</h2>
 *
 * <p>The buffer is a bounded region of {@link ColonyStores} exposed through {@link ColonyContainer},
 * which every loader already wraps as the standard item capability on the colony beacon. NeroLogistics
 * pipes, AE2, Create and vanilla hoppers therefore drain it with <b>zero coupling</b> — there is no
 * export API to depend on, because there is nothing an external mod needs beyond "this block has an
 * inventory".
 *
 * <p>Insertion from outside is refused. What is in the buffer is exactly what the colony produced for
 * sale, which is what makes {@link #sell} a meaningful operation rather than a laundering machine.
 *
 * <h2>Overflow blocks, it never voids</h2>
 *
 * <p>A full buffer stops export production (see {@code JobBoard}); it does not spill into storage and
 * it certainly does not delete anything. A colony whose trade route has stalled should visibly stop
 * making trade goods — that is a problem the player can see and fix — rather than quietly converting
 * inputs into nothing.
 *
 * <h2>Valuation: exactly one code hook</h2>
 *
 * <p>Export tables are pure datapack. The only code-level hook in the whole export system is
 * {@link ExportEntry#baseValue()} feeding Core's {@link CurrencyApi} — a value lookup, not a pricing
 * engine. NeroColonies has no market model and will not grow one: when NeroEconomy exists it
 * registers itself as Core's currency provider and this path picks it up with no change here.
 *
 * <p>{@link CurrencyApi#hasRealProvider()} guards the sale. Core's in-memory fallback provider is a
 * development stand-in whose balances do not persist, so paying into it would look like a sale and
 * lose the player's goods. With no real provider the sale is refused, the goods stay in the buffer,
 * and the player is told why — the same log-and-skip rule NeroQuests uses for currency rewards.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>The deposit needs the colony owner's UUID, which is read from the colony record on the server
 * and handed straight to Core. It is never logged, never returned and never sent to a client: the
 * sale result carries counts and a credit total only.
 */
public final class ExportBuffer {

    private ExportBuffer() {
    }

    /** The configured buffer size, clamped to the backing region. */
    public static int usableSlots() {
        return Math.clamp(NeroColoniesConfig.EXPORT_BUFFER_SLOTS.get(), 0, ColonyStores.EXPORT_SLOTS);
    }

    /**
     * A vanilla {@link net.minecraft.world.Container} window onto this colony's export buffer.
     * Extract-only: {@code canPlaceItem} is always false, so nothing outside the colony can put
     * goods in.
     */
    public static ColonyContainer container(MinecraftServer server, UUID colonyId) {
        return new ColonyContainer(server, colonyId, ColonyContainer.Region.EXPORTS,
                ExportBuffer::usableSlots, false);
    }

    // --- production routing -------------------------------------------------

    /**
     * Inserts a job's export output.
     *
     * @return the number of items that did <b>not</b> fit; zero means everything went in
     */
    public static int insert(MinecraftServer server, UUID colonyId, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        ColonyStores stores = ColonyStores.get(server);
        NonNullList<ItemStack> items = stores.store(colonyId).exports();
        int limit = Math.clamp(usableSlots(), 0, items.size());
        int remaining = stack.getCount();
        boolean changed = false;

        for (int slot = 0; slot < limit && remaining > 0; slot++) {
            ItemStack existing = items.get(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int room = existing.getMaxStackSize() - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, remaining);
            existing.grow(moved);
            remaining -= moved;
            changed = true;
        }
        for (int slot = 0; slot < limit && remaining > 0; slot++) {
            if (!items.get(slot).isEmpty()) {
                continue;
            }
            ItemStack placed = stack.copyWithCount(Math.min(remaining, stack.getMaxStackSize()));
            items.set(slot, placed);
            remaining -= placed.getCount();
            changed = true;
        }
        if (changed) {
            stores.touch();
        }
        return remaining;
    }

    /** Whether a whole {@link za.co.neroland.nerocolonies.content.ItemAmount} would fit right now. */
    public static boolean fits(MinecraftServer server, UUID colonyId,
            za.co.neroland.nerocolonies.content.ItemAmount amount) {
        ItemStack stack = amount.toStack();
        if (stack.isEmpty()) {
            return false;
        }
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colonyId).exports();
        int limit = Math.clamp(usableSlots(), 0, items.size());
        int room = 0;
        for (int slot = 0; slot < limit && room < stack.getCount(); slot++) {
            ItemStack existing = items.get(slot);
            if (existing.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                room += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
        }
        return room >= stack.getCount();
    }

    /** How many of the usable slots currently hold something. */
    public static int filledSlots(MinecraftServer server, UUID colonyId) {
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colonyId).exports();
        int limit = Math.clamp(usableSlots(), 0, items.size());
        int filled = 0;
        for (int slot = 0; slot < limit; slot++) {
            if (!items.get(slot).isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /** Whether every usable slot is occupied and full — the signal that stops export production. */
    public static boolean isFull(MinecraftServer server, UUID colonyId) {
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colonyId).exports();
        int limit = Math.clamp(usableSlots(), 0, items.size());
        if (limit <= 0) {
            return true;
        }
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    // --- valuation and sale -------------------------------------------------

    /**
     * The outcome of a sale attempt. Counts and a credit total — nothing player-shaped.
     *
     * @param items   how many individual items were sold
     * @param credits how many credits were paid
     * @param status  why the sale ended the way it did
     */
    public record SaleResult(int items, long credits, Status status) {

        public enum Status {
            /** Goods were sold and credits were paid. */
            SOLD,
            /** The buffer held nothing the loaded export tables recognise. */
            NOTHING_TO_SELL,
            /** No real currency provider is installed (NeroEconomy is absent). */
            NO_MARKET,
            /** The colony has no owner to pay (post-erasure, or admin-created). */
            NO_OWNER
        }

        static SaleResult of(Status status) {
            return new SaleResult(0, 0L, status);
        }
    }

    /**
     * Values everything sellable in the buffer, removes it and pays the colony's owner.
     *
     * <p>Order matters: the market and the owner are checked <b>before</b> anything leaves the
     * buffer, so a refused sale is a no-op rather than a partial one.
     */
    public static SaleResult sell(MinecraftServer server, Colony colony) {
        if (!colony.hasOwner()) {
            return SaleResult.of(SaleResult.Status.NO_OWNER);
        }
        if (!CurrencyApi.hasRealProvider()) {
            // Core's in-memory fallback does not persist; paying into it would take the goods and
            // give nothing back. Log and skip, exactly as NeroQuests does for currency rewards.
            NeroColoniesCommon.LOGGER.debug(
                    "[NeroColonies] An export sale was refused: no currency provider is installed.");
            return SaleResult.of(SaleResult.Status.NO_MARKET);
        }

        ColonyStores stores = ColonyStores.get(server);
        NonNullList<ItemStack> items = stores.store(colony.colonyId()).exports();
        int limit = Math.clamp(usableSlots(), 0, items.size());
        Map<Identifier, ExportEntry> entries = ColonyDefinitions.exportsForServer(server);
        double multiplier = NeroColoniesConfig.EXPORT_VALUE_MULTIPLIER.get();

        double value = 0.0D;
        int sold = 0;
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ExportEntry entry = bestEntry(entries, colony, stack);
            if (entry == null) {
                continue; // not on this colony's manifest: leave it where it is
            }
            value += entry.baseValue() * stack.getCount() * multiplier;
            sold += stack.getCount();
            items.set(slot, ItemStack.EMPTY);
        }
        if (sold <= 0) {
            return SaleResult.of(SaleResult.Status.NOTHING_TO_SELL);
        }
        stores.touch();

        long credits = (long) Math.floor(Math.max(0.0D, value));
        if (credits > 0) {
            CurrencyApi.deposit(colony.ownerId(), CoreCurrencies.CREDITS, credits);
        }
        // Counts only — never who was paid (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A colony sold {} export item(s) for {} credit(s).",
                sold, credits);
        return new SaleResult(sold, credits, SaleResult.Status.SOLD);
    }

    /**
     * Values one stack against the loaded export tables, preferring the most valuable entry that
     * matches and that this colony has unlocked. Preferring the highest value is the honest reading
     * of overlapping tables: a pack that lists an item twice meant the better price to apply, not
     * whichever file happened to load first.
     */
    @Nullable
    private static ExportEntry bestEntry(Map<Identifier, ExportEntry> entries, Colony colony,
            ItemStack stack) {
        ExportEntry best = null;
        for (ExportEntry entry : entries.values()) {
            if (!entry.target().matches(stack)) {
                continue;
            }
            if (!ResearchEffects.exportUnlocked(colony, entry.id())) {
                continue;
            }
            if (best == null || entry.baseValue() > best.baseValue()) {
                best = entry;
            }
        }
        return best;
    }

    /**
     * The credit value the buffer would fetch right now, for the GUI. A read-only estimate: it makes
     * no market or ownership check, because a number on a screen is not a transaction.
     */
    public static long previewValue(MinecraftServer server, Colony colony) {
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colony.colonyId()).exports();
        int limit = Math.clamp(usableSlots(), 0, items.size());
        Map<Identifier, ExportEntry> entries = ColonyDefinitions.exportsForServer(server);
        double multiplier = NeroColoniesConfig.EXPORT_VALUE_MULTIPLIER.get();
        double value = 0.0D;
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ExportEntry entry = bestEntry(entries, colony, stack);
            if (entry != null) {
                value += entry.baseValue() * stack.getCount() * multiplier;
            }
        }
        return (long) Math.floor(Math.max(0.0D, value));
    }
}
