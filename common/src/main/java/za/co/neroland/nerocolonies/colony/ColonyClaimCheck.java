package za.co.neroland.nerocolonies.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.protection.ClaimCheck;
import za.co.neroland.nerolandcore.protection.ClaimChecks;

/**
 * NeroColonies' answer to Neroland Core's land-protection SPI, backed by the colony claims.
 *
 * <ul>
 *   <li>{@link #isProtected}: inside any colony's claim, world effects with no player (radiation, explosions,
 *       automated breaking) leave the land alone.</li>
 *   <li>{@link #mayInteract}: exactly {@link ColonyClaims#canBuild}: unclaimed ground is open; inside a claim
 *       only the Owner, Chiefs, Allies and operators may act, never an Enemy.</li>
 * </ul>
 *
 * <p>Cheap: the claim lookup is the colony store's O(1) chunk index; no chunk is loaded and nothing is logged.
 */
public final class ColonyClaimCheck implements ClaimCheck {

    public static final ColonyClaimCheck INSTANCE = new ColonyClaimCheck();

    private ColonyClaimCheck() {
    }

    /** Registers with Core at common init. Idempotent (Core ignores a second registration). */
    public static void register() {
        ClaimChecks.register(INSTANCE);
    }

    @Override
    public boolean isProtected(ServerLevel level, BlockPos pos) {
        return ColonyState.get(level.getServer()).colonyAt(level.dimension(), pos) != null;
    }

    @Override
    public boolean mayInteract(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return ColonyClaims.canBuild(player, level, pos);
    }
}
