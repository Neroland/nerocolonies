package za.co.neroland.nerocolonies.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.menu.ColonyBeaconMenu;
import za.co.neroland.nerocolonies.menu.ColonyStorageMenu;
import za.co.neroland.nerocolonies.menu.JobStationMenu;
import za.co.neroland.nerocolonies.menu.OutpostMenu;
import za.co.neroland.nerocolonies.menu.OxygenGeneratorMenu;
import za.co.neroland.nerocolonies.menu.ResearchMenu;

/** Menu types, through Neroland Core's {@link RegistrationProvider} over the vanilla MENU registry. */
public final class NeroColoniesMenus {

    public static final RegistrationProvider<MenuType<?>> MENUS =
            RegistrationProvider.get(Registries.MENU, NeroColoniesCommon.MOD_ID);

    public static final RegistryEntry<MenuType<ColonyBeaconMenu>> COLONY_BEACON =
            MENUS.register("colony_beacon",
                    key -> new MenuType<>(ColonyBeaconMenu::new, FeatureFlags.VANILLA_SET));

    public static final RegistryEntry<MenuType<OxygenGeneratorMenu>> OXYGEN_GENERATOR =
            MENUS.register("oxygen_generator",
                    key -> new MenuType<>(OxygenGeneratorMenu::new, FeatureFlags.VANILLA_SET));

    public static final RegistryEntry<MenuType<JobStationMenu>> JOB_STATION =
            MENUS.register("job_station",
                    key -> new MenuType<>(JobStationMenu::new, FeatureFlags.VANILLA_SET));

    public static final RegistryEntry<MenuType<ColonyStorageMenu>> COLONY_STORAGE =
            MENUS.register("colony_storage",
                    key -> new MenuType<>(ColonyStorageMenu::new, FeatureFlags.VANILLA_SET));

    public static final RegistryEntry<MenuType<ResearchMenu>> RESEARCH_STATION =
            MENUS.register("research_station",
                    key -> new MenuType<>(ResearchMenu::new, FeatureFlags.VANILLA_SET));

    public static final RegistryEntry<MenuType<OutpostMenu>> OUTPOST_BEACON =
            MENUS.register("outpost_beacon",
                    key -> new MenuType<>(OutpostMenu::new, FeatureFlags.VANILLA_SET));

    private NeroColoniesMenus() {
    }

    /** Empty by design — exists so Fabric class-loads this holder from common init. */
    public static void init() {
    }
}
