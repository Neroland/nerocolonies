package za.co.neroland.nerocolonies.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * NeroColonies' own creative tab, registered cross-loader through Neroland Core's
 * {@link RegistrationProvider} over the vanilla {@code CREATIVE_MODE_TAB} registry.
 *
 * <p>NeroColonies does <b>not</b> contribute to Core's shared {@code Neroland} tab: every item the
 * mod registers is listed here and nowhere else, in the order {@link NeroColoniesItems} registers
 * them. The icon is the Colony Beacon, the block a colony starts from.
 *
 * <p>The tab is a plain vanilla tab filled by {@code displayItems}, which behaves the same on
 * Fabric, Forge and NeoForge (the per-loader tab-injection events do not). Its contents are read
 * lazily when the tab is displayed, so registering the tab before the items have resolved is safe.
 */
public final class NeroColoniesCreativeTab {

    public static final RegistrationProvider<CreativeModeTab> TABS =
            RegistrationProvider.get(Registries.CREATIVE_MODE_TAB, NeroColoniesCommon.MOD_ID);

    // NOTE: vanilla CreativeModeTab.builder takes (Row, column); the no-arg overload and
    // withTabsBefore/After are NeoForge-only extensions, so they are avoided here (common = raw
    // vanilla). Every loader repositions modded tabs itself, so the slot is only a hint.
    public static final RegistryEntry<CreativeModeTab> NEROCOLONIES = TABS.register(
            NeroColoniesCommon.MOD_ID,
            key -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup." + NeroColoniesCommon.MOD_ID))
                    .icon(() -> new ItemStack(NeroColoniesItems.COLONY_BEACON.get()))
                    .displayItems((params, output) -> NeroColoniesItems.creativeContents()
                            .forEach(entry -> output.accept(entry.get())))
                    .build());

    private NeroColoniesCreativeTab() {
    }

    /** Force class-load so the static tab registration runs. */
    public static void init() {
    }
}
