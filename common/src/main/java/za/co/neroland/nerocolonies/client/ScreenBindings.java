package za.co.neroland.nerocolonies.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import za.co.neroland.nerocolonies.client.screen.ColonyBeaconScreen;
import za.co.neroland.nerocolonies.client.screen.ColonyStorageScreen;
import za.co.neroland.nerocolonies.client.screen.JobStationScreen;
import za.co.neroland.nerocolonies.client.screen.OutpostScreen;
import za.co.neroland.nerocolonies.client.screen.OxygenGeneratorScreen;
import za.co.neroland.nerocolonies.client.screen.ResearchScreen;
import za.co.neroland.nerocolonies.registry.NeroColoniesMenus;

/**
 * The canonical menu-to-screen binding table, iterated by all three loader client setups so a new
 * menu is wired once here instead of three times.
 *
 * <p><b>CLIENT-ONLY:</b> this class references screen classes, so it must only ever be loaded from a
 * client entry point (all three loader setups are client-only contexts).
 */
public final class ScreenBindings {

    private ScreenBindings() {
    }

    /** Loader-neutral mirror of the vanilla screen-constructor shape. */
    @FunctionalInterface
    public interface ScreenFactory<M extends AbstractContainerMenu, U extends AbstractContainerScreen<M>> {
        U create(M menu, Inventory inventory, Component title);
    }

    /** Adapts one loader's menu-screen registration call (vanilla {@code MenuScreens.register} or event). */
    @FunctionalInterface
    public interface Registrar {
        <M extends AbstractContainerMenu, U extends AbstractContainerScreen<M>> void register(
                MenuType<M> type, ScreenFactory<M, U> factory);
    }

    /** Register every NeroColonies screen against its menu type. */
    public static void registerAll(Registrar registrar) {
        registrar.register(NeroColoniesMenus.COLONY_BEACON.get(), ColonyBeaconScreen::new);
        registrar.register(NeroColoniesMenus.OXYGEN_GENERATOR.get(), OxygenGeneratorScreen::new);
        registrar.register(NeroColoniesMenus.JOB_STATION.get(), JobStationScreen::new);
        registrar.register(NeroColoniesMenus.COLONY_STORAGE.get(), ColonyStorageScreen::new);
        registrar.register(NeroColoniesMenus.RESEARCH_STATION.get(), ResearchScreen::new);
        registrar.register(NeroColoniesMenus.OUTPOST_BEACON.get(), OutpostScreen::new);
    }
}
