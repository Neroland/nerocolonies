package za.co.neroland.nerocolonies.compat.nerospace;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerospace.api.NerospaceTerraforming;
import za.co.neroland.nerospace.api.TerraformClaimPolicy;
import za.co.neroland.nerospace.api.TerraformRequest;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.ColonyClaims;

/**
 * NeroColonies' answer to Nerospace's terraform claim hook: a colony owns the overlay terraforming around its
 * beacon.
 *
 * <p>{@link #mayMutate} allows a reversible overlay {@code apply} or {@code rollback} only when the whole
 * request disc (centre and radius) lies inside <b>one</b> colony's claim and the actor may build there (owner,
 * Chief, Ally or operator, never an Enemy; see {@link ColonyClaims#mayTerraform}). Everything else is refused,
 * which is Nerospace's own fail-closed default. What this does not govern: the physical Terraformer block and
 * Landing Pods (Nerospace does not consult the policy for either).
 *
 * <p>Nerospace keeps <b>one</b> global policy slot with no getter, so whichever mod installs last wins. This is
 * installed once, at common init, when Nerospace is present, with one log line.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> the actor is used to make the decision and is never stored or logged; the
 * install log line carries no player data at all.
 *
 * <p>A separate class from {@link NerospaceBridge} so that a Nerospace without the hook (1.x) fails only
 * {@link #install()}, inside the bridge's {@code LinkageError} guard.
 */
final class NerospaceClaimPolicy implements TerraformClaimPolicy {

    private NerospaceClaimPolicy() {
    }

    /** Installs the policy. Throws {@link LinkageError} on a Nerospace without the hook; the bridge catches it. */
    static void install() {
        NerospaceTerraforming.setClaimPolicy(new NerospaceClaimPolicy());
        NeroColoniesCommon.LOGGER.info(
                "[NeroColonies] Installed the Nerospace terraform claim policy: overlay terraforming is allowed "
                        + "only wholly inside one colony claim, by someone who may build there. A policy another "
                        + "mod installs later replaces this one.");
    }

    @Override
    public boolean mayMutate(ServerPlayer actor, ServerLevel level, TerraformRequest request) {
        if (actor == null || level == null || request == null) {
            return false;
        }
        try {
            return ColonyClaims.mayTerraform(actor, level, request.center(), request.radius());
        } catch (RuntimeException e) {
            return false; // fail closed
        }
    }
}
