package za.co.neroland.nerocolonies.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.economy.CoreCurrencies;
import za.co.neroland.nerolandcore.economy.CurrencyApi;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.compat.CompatRegistry;
import za.co.neroland.nerocolonies.compat.EconomyCompat;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.content.ColonyDefinitions;
import za.co.neroland.nerocolonies.content.ExportEntry;
import za.co.neroland.nerocolonies.progression.ColonyGates;

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
 * <h2>Valuation and payment</h2>
 *
 * <p>Export tables are pure datapack, and NeroColonies has no market model of its own. With
 * NeroEconomy's sibling API installed (see {@link za.co.neroland.nerocolonies.compat.EconomyCompat}),
 * an item its Merchant Houses buy is priced at their live bid and the sale is paid as budgeted
 * payouts that can be refused; the export table ({@link ExportEntry#baseValue()} times the config
 * multiplier) prices everything else. Without it, the table prices everything and Core's
 * {@link CurrencyApi} pays. Either way goods leave the buffer only for credits that actually moved.
 *
 * <p>Without the budgeted API, {@link CurrencyApi#hasRealProvider()} guards the sale. Core's in-memory fallback provider is a
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
     * The outcome of a sale attempt. Counts, a credit total and at most one lang key — nothing
     * player-shaped.
     *
     * @param items      how many individual items were sold
     * @param credits    how many credits were paid
     * @param status     why the sale ended the way it did
     * @param refusalKey when the market refused a payout, the lang key explaining why (a sale can be
     *                   {@link Status#SOLD} in part and still carry one); otherwise {@code null}
     */
    public record SaleResult(int items, long credits, Status status, @Nullable String refusalKey) {

        public enum Status {
            /** Goods were sold and credits were paid (possibly only part of the buffer). */
            SOLD,
            /** The buffer held nothing the loaded export tables recognise. */
            NOTHING_TO_SELL,
            /** No real currency provider is installed (NeroEconomy is absent). */
            NO_MARKET,
            /** The colony has no owner to pay (post-erasure, or admin-created). */
            NO_OWNER,
            /** The market refused the first payout; nothing was sold. See {@link #refusalKey}. */
            REFUSED
        }

        static SaleResult of(Status status) {
            return new SaleResult(0, 0L, status, null);
        }
    }

    /** One sellable slot, priced per unit once at the start of a sale. */
    private record Lot(int slot, double unitPrice) {
    }

    /** Units taken from one slot into the payout being assembled. */
    private record Take(int slot, int units) {
    }

    /**
     * Values everything sellable in the buffer, pays the colony's owner and removes what was paid
     * for.
     *
     * <p><b>With NeroEconomy's budgeted API.</b> The sale is split into payouts of at most {@code exportPayoutChunk} credits, each made through
     * {@link EconomyCompat#pay} under the reason {@code export_sale} with the request key
     * {@code colony/<colony id>/sale/<counter>} (the counter is persisted on the colony's
     * {@link ColonyLife} record and moves on only after a payout is applied). Goods leave the buffer
     * <b>only</b> for a payout that stands as applied (applied, or a duplicate of one that was); the
     * sale stops at the first refusal, and everything not yet paid for stays in the buffer.
     *
     * <p><b>Without it.</b> One deposit through Core's {@link CurrencyApi}, and the goods leave only when it reports
     * success. With no real provider the sale is refused before anything is touched.
     */
    public static SaleResult sell(MinecraftServer server, Colony colony) {
        if (!colony.hasOwner()) {
            return SaleResult.of(SaleResult.Status.NO_OWNER);
        }
        EconomyCompat economy = CompatRegistry.economy();
        boolean budgeted = economy.active();
        if (!budgeted && !CurrencyApi.hasRealProvider()) {
            // Core's in-memory fallback does not persist; paying into it would take the goods and
            // give nothing back. Log and skip, exactly as NeroQuests does for currency rewards.
            NeroColoniesCommon.LOGGER.debug(
                    "[NeroColonies] An export sale was refused: no currency provider is installed.");
            return SaleResult.of(SaleResult.Status.NO_MARKET);
        }

        ColonyStores stores = ColonyStores.get(server);
        NonNullList<ItemStack> items = stores.store(colony.colonyId()).exports();
        List<Lot> lots = priceLots(server, colony, items, economy);
        if (lots.isEmpty()) {
            return SaleResult.of(SaleResult.Status.NOTHING_TO_SELL);
        }

        ColonyLife life = ColonyLife.get(server);
        double cap = budgeted ? Math.max(1, NeroColoniesConfig.EXPORT_PAYOUT_CHUNK.get()) : Double.MAX_VALUE;
        int sold = 0;
        long paid = 0L;
        String refusal = null;

        while (true) {
            List<Take> takes = new ArrayList<>();
            double value = assemble(lots, items, cap, takes);
            if (takes.isEmpty()) {
                break;
            }
            long credits = (long) Math.floor(Math.max(0.0D, value));
            if (credits > 0L) {
                if (budgeted) {
                    ColonyLife.Life record = life.life(colony.colonyId());
                    EconomyCompat.Payout payout = economy.pay(server, colony.ownerId(), credits,
                            EconomyCompat.REASON_EXPORT_SALE, saleKey(colony.colonyId(), record.exportSales()));
                    if (!payout.ok()) {
                        if (payout.invalid() && cap > 1.0D && credits > 1L) {
                            // Above NeroEconomy's single-payout cap: halve and try again. A refused
                            // key is not remembered, so the same key is safe to reuse.
                            cap = Math.max(1.0D, Math.floor(credits / 2.0D));
                            continue;
                        }
                        refusal = payout.translationKey();
                        break;
                    }
                    record.countExportSale();
                    life.touch();
                } else if (!CurrencyApi.deposit(colony.ownerId(), CoreCurrencies.CREDITS, credits)) {
                    refusal = "message.nerocolonies.export.deposit_failed";
                    break;
                } else {
                    // Counted on both paths so the sale tally below is honest; the budgeted path's
                    // request keys only need to be unique, so a skipped number costs nothing.
                    life.life(colony.colonyId()).countExportSale();
                    life.touch();
                }
            }
            for (Take take : takes) {
                items.get(take.slot()).shrink(take.units());
                if (items.get(take.slot()).isEmpty()) {
                    items.set(take.slot(), ItemStack.EMPTY);
                }
                sold += take.units();
            }
            paid += credits;
            stores.touch();
            if (!budgeted) {
                break; // one deposit covers the whole buffer
            }
        }

        if (sold <= 0) {
            return refusal != null
                    ? new SaleResult(0, 0L, SaleResult.Status.REFUSED, refusal)
                    : SaleResult.of(SaleResult.Status.NOTHING_TO_SELL);
        }
        // Counts only — never who was paid (POPIA/GDPR).
        NeroColoniesCommon.LOGGER.debug("[NeroColonies] A colony sold {} export item(s) for {} credit(s).",
                sold, paid);
        // The world hears the sale tally on the colony's channel; the owner gets the record gate.
        ColonyCrossings.count(server, colony, ColonyCrossings.CHANNEL_EXPORTS_SHIPPED,
                life.life(colony.colonyId()).exportSales());
        ColonyGates.recordForOwner(server, colony, ColonyGates.FIRST_EXPORT);
        return new SaleResult(sold, paid, SaleResult.Status.SOLD, refusal);
    }

    /**
     * Fills one payout from the lots, in slot order, without going over {@code cap} credits. A lot
     * whose single unit is worth more than {@code cap} is skipped (it can never be paid in one
     * payout) and stays in the buffer.
     *
     * @return the payout's value; {@code takes} receives what it covers
     */
    private static double assemble(List<Lot> lots, NonNullList<ItemStack> items, double cap, List<Take> takes) {
        double value = 0.0D;
        for (Lot lot : lots) {
            int available = items.get(lot.slot()).getCount();
            if (available <= 0) {
                continue;
            }
            int units;
            if (lot.unitPrice() <= 0.0D) {
                units = available;
            } else {
                if (lot.unitPrice() > cap) {
                    continue;
                }
                double room = cap - value;
                units = (int) Math.min(available, Math.floor((room + 1.0E-9D) / lot.unitPrice()));
            }
            if (units <= 0) {
                break; // this payout is full
            }
            takes.add(new Take(lot.slot(), units));
            value += units * Math.max(0.0D, lot.unitPrice());
        }
        return value;
    }

    /**
     * The request key of a budgeted export payout: {@code colony/<colony id>/sale/<counter>}. The
     * colony id is written as two base-36 halves so the key never reads as a UUID (NeroEconomy refuses
     * those) and never holds a player id or name. At most 59 characters.
     */
    static String saleKey(UUID colonyId, long counter) {
        return "colony/" + compactId(colonyId) + "/sale/" + Math.max(0L, counter);
    }

    /** A colony id as {@code <msb>.<lsb>} in unsigned base 36: unique, short, and not UUID-shaped. */
    public static String compactId(UUID colonyId) {
        return Long.toUnsignedString(colonyId.getMostSignificantBits(), 36) + "."
                + Long.toUnsignedString(colonyId.getLeastSignificantBits(), 36);
    }

    /** Prices every sellable slot once: market bid first, export table × multiplier as the fallback. */
    private static List<Lot> priceLots(MinecraftServer server, Colony colony, NonNullList<ItemStack> items,
            EconomyCompat economy) {
        int limit = Math.clamp(usableSlots(), 0, items.size());
        Map<Identifier, ExportEntry> entries = ColonyDefinitions.exportsForServer(server);
        double multiplier = NeroColoniesConfig.EXPORT_VALUE_MULTIPLIER.get();
        List<Lot> lots = new ArrayList<>();
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ExportEntry entry = bestEntry(entries, colony, stack);
            if (entry == null) {
                continue; // not on this colony's manifest: leave it where it is
            }
            lots.add(new Lot(slot, unitPrice(server, economy, entry, stack, multiplier)));
        }
        return lots;
    }

    /**
     * One unit's price. NeroEconomy's Merchant House bid when it buys this item and the stack is
     * unmodified (a bid is for a plain unit); otherwise the colony's own export table times the
     * config multiplier.
     */
    private static double unitPrice(MinecraftServer server, EconomyCompat economy, ExportEntry entry,
            ItemStack stack, double multiplier) {
        if (economy.active() && stack.getComponentsPatch().isEmpty() && !stack.isDamaged()) {
            OptionalLong bid = economy.bid(server, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (bid.isPresent() && bid.getAsLong() > 0L) {
                return bid.getAsLong();
            }
        }
        return entry.baseValue() * multiplier;
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
     * The credit value the buffer would fetch right now, for the GUI. A read-only estimate at the same
     * prices a sale would use: it makes no market, budget or ownership check, because a number on a
     * screen is not a transaction.
     */
    public static long previewValue(MinecraftServer server, Colony colony) {
        NonNullList<ItemStack> items = ColonyStores.get(server).store(colony.colonyId()).exports();
        double value = 0.0D;
        for (Lot lot : priceLots(server, colony, items, CompatRegistry.economy())) {
            value += Math.max(0.0D, lot.unitPrice()) * items.get(lot.slot()).getCount();
        }
        return (long) Math.floor(Math.max(0.0D, value));
    }
}
