package za.co.neroland.nerocolonies.colony;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.protection.ClaimChecks;

/** NeroColonies registers one Core claim check. Plain JVM. */
class ColonyClaimCheckTest {

    @AfterEach
    void cleanup() {
        ClaimChecks.clearForTests();
    }

    @Test
    void registeringTwiceIsOneCheck() {
        ColonyClaimCheck.register();
        ColonyClaimCheck.register();
        assertTrue(ClaimChecks.active());
    }
}
