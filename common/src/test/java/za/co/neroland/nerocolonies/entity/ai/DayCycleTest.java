package za.co.neroland.nerocolonies.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.entity.ai.DayCycle.Phase;

/**
 * Covers the pure, clock-only forms of {@link DayCycle}: night detection with its hysteresis at dusk
 * and dawn and the wrap by day length, and the day's schedule (work, eat, work, social, sleep) with
 * the same hysteresis applied to falling asleep and waking up. The forms that take a level read the
 * dimension and config and are not covered here.
 */
class DayCycleTest {

    private static final Phase[] AWAKE = {Phase.WORK, Phase.EAT, Phase.SOCIAL};

    @Test
    @DisplayName("Noon is day and midnight is night")
    void noonAndMidnight() {
        assertFalse(DayCycle.isNight(6_000L, false));
        assertTrue(DayCycle.isNight(18_000L, false));
    }

    @Test
    @DisplayName("Hysteresis: a decision holds until the clock is clearly past the boundary")
    void hysteresisAtDusk() {
        long boundary = DayCycle.NIGHT_START;
        // Just past the boundary, a Neran that still thinks it is day keeps working...
        assertFalse(DayCycle.isNight(boundary + 1, false));
        // ...and one that already decided it is night stays home just before it.
        assertTrue(DayCycle.isNight(boundary - 1, true));
        // Well past the margin, both agree.
        assertTrue(DayCycle.isNight(boundary + DayCycle.HYSTERESIS, false));
    }

    @Test
    @DisplayName("Hysteresis at dawn")
    void hysteresisAtDawn() {
        long boundary = DayCycle.NIGHT_END;
        assertTrue(DayCycle.isNight(boundary + 1, true));
        // A Neran that has already decided it is day stays decided just before the boundary.
        assertFalse(DayCycle.isNight(boundary - 1, false));
        assertFalse(DayCycle.isNight(boundary + DayCycle.HYSTERESIS, true));
    }

    @Test
    @DisplayName("The clock wraps by day length, including negative values")
    void wraps() {
        assertFalse(DayCycle.isNight(6_000L + 5 * DayCycle.DAY_LENGTH, false));
        assertTrue(DayCycle.isNight(-6_000L, false)); // 18 000 of the previous day
    }

    // --- night: the margins exactly ---------------------------------------------------

    @Test
    @DisplayName("The night margin is exact: one tick either side of it flips the answer")
    void nightMarginsAreExact() {
        // Falling asleep: day holds until NIGHT_START + HYSTERESIS.
        assertFalse(DayCycle.isNight(DayCycle.NIGHT_START + DayCycle.HYSTERESIS - 1, false));
        assertTrue(DayCycle.isNight(DayCycle.NIGHT_START + DayCycle.HYSTERESIS, false));
        // Staying asleep: night already holds from NIGHT_START - HYSTERESIS.
        assertFalse(DayCycle.isNight(DayCycle.NIGHT_START - DayCycle.HYSTERESIS - 1, true));
        assertTrue(DayCycle.isNight(DayCycle.NIGHT_START - DayCycle.HYSTERESIS, true));
        // Waking: night holds until NIGHT_END + HYSTERESIS.
        assertTrue(DayCycle.isNight(DayCycle.NIGHT_END + DayCycle.HYSTERESIS - 1, true));
        assertFalse(DayCycle.isNight(DayCycle.NIGHT_END + DayCycle.HYSTERESIS, true));
        // Staying awake: day already holds from NIGHT_END - HYSTERESIS.
        assertTrue(DayCycle.isNight(DayCycle.NIGHT_END - DayCycle.HYSTERESIS - 1, false));
        assertFalse(DayCycle.isNight(DayCycle.NIGHT_END - DayCycle.HYSTERESIS, false));
    }

    @Test
    @DisplayName("Away from the two boundaries the previous decision makes no difference")
    void previousDecisionOnlyMattersNearTheBoundaries() {
        for (long t = 0; t < DayCycle.DAY_LENGTH; t++) {
            boolean nearDusk = Math.abs(t - DayCycle.NIGHT_START) <= DayCycle.HYSTERESIS;
            boolean nearDawn = Math.abs(t - DayCycle.NIGHT_END) <= DayCycle.HYSTERESIS;
            if (!nearDusk && !nearDawn) {
                assertEquals(DayCycle.isNight(t, false), DayCycle.isNight(t, true), "at " + t);
            }
        }
    }

    @Test
    @DisplayName("Staying asleep is at least as easy as falling asleep: night-if-was-night covers night-if-was-day")
    void hysteresisOnlyWidensTheCurrentDecision() {
        for (long t = 0; t < DayCycle.DAY_LENGTH; t++) {
            if (DayCycle.isNight(t, false)) {
                assertTrue(DayCycle.isNight(t, true), "at " + t);
            }
        }
    }

    // --- phases -----------------------------------------------------------------------

    @Test
    @DisplayName("The morning is work, from the first tick of the day")
    void morningIsWork() {
        assertEquals(Phase.WORK, DayCycle.phase(0L, Phase.WORK));
        assertEquals(Phase.WORK, DayCycle.phase(3_000L, Phase.WORK));
        assertEquals(Phase.WORK, DayCycle.phase(DayCycle.EAT_START - 1, Phase.WORK));
    }

    @Test
    @DisplayName("The meal runs from EAT_START up to, but not including, EAT_END")
    void mealBreak() {
        assertEquals(Phase.EAT, DayCycle.phase(DayCycle.EAT_START, Phase.WORK));
        assertEquals(Phase.EAT, DayCycle.phase(6_500L, Phase.EAT));
        assertEquals(Phase.EAT, DayCycle.phase(DayCycle.EAT_END - 1, Phase.EAT));
        assertEquals(Phase.WORK, DayCycle.phase(DayCycle.EAT_END, Phase.EAT));
    }

    @Test
    @DisplayName("The afternoon is work again, until the social hour")
    void afternoonIsWork() {
        assertEquals(Phase.WORK, DayCycle.phase(9_000L, Phase.WORK));
        assertEquals(Phase.WORK, DayCycle.phase(DayCycle.SOCIAL_START - 1, Phase.WORK));
    }

    @Test
    @DisplayName("The social hour runs from SOCIAL_START until night falls")
    void socialHour() {
        assertEquals(Phase.SOCIAL, DayCycle.phase(DayCycle.SOCIAL_START, Phase.WORK));
        assertEquals(Phase.SOCIAL, DayCycle.phase(12_000L, Phase.SOCIAL));
        assertEquals(Phase.SOCIAL, DayCycle.phase(DayCycle.NIGHT_START - 1, Phase.SOCIAL));
    }

    @Test
    @DisplayName("Midnight is sleep, whatever the Neran was doing")
    void midnightIsSleep() {
        for (Phase previous : Phase.values()) {
            assertEquals(Phase.SLEEP, DayCycle.phase(18_000L, previous), "from " + previous);
        }
    }

    @Test
    @DisplayName("Dusk hysteresis: a Neran still up stays social through the margin, then sleeps")
    void duskHysteresisForPhases() {
        long dusk = DayCycle.NIGHT_START;
        for (Phase previous : AWAKE) {
            assertEquals(Phase.SOCIAL, DayCycle.phase(dusk, previous), "from " + previous);
            assertEquals(Phase.SOCIAL, DayCycle.phase(dusk + DayCycle.HYSTERESIS - 1, previous), "from " + previous);
            assertEquals(Phase.SLEEP, DayCycle.phase(dusk + DayCycle.HYSTERESIS, previous), "from " + previous);
        }
    }

    @Test
    @DisplayName("Dusk hysteresis: a Neran already asleep stays asleep just before the boundary")
    void duskHysteresisWhenAlreadyAsleep() {
        long dusk = DayCycle.NIGHT_START;
        assertEquals(Phase.SLEEP, DayCycle.phase(dusk, Phase.SLEEP));
        assertEquals(Phase.SLEEP, DayCycle.phase(dusk - 1, Phase.SLEEP));
        assertEquals(Phase.SLEEP, DayCycle.phase(dusk - DayCycle.HYSTERESIS, Phase.SLEEP));
        // Outside the margin it is not night for anyone: the social hour.
        assertEquals(Phase.SOCIAL, DayCycle.phase(dusk - DayCycle.HYSTERESIS - 1, Phase.SLEEP));
    }

    @Test
    @DisplayName("No threshold tick near dusk flips a Neran back and forth")
    void duskDecisionIsStable() {
        for (long t = DayCycle.NIGHT_START - 2 * DayCycle.HYSTERESIS;
                t <= DayCycle.NIGHT_START + 2 * DayCycle.HYSTERESIS; t++) {
            for (Phase previous : Phase.values()) {
                Phase decided = DayCycle.phase(t, previous);
                // Asking again on the same tick with the new decision gives the same answer.
                assertEquals(decided, DayCycle.phase(t, decided), "at " + t + " from " + previous);
            }
        }
    }

    @Test
    @DisplayName("Dawn hysteresis: a sleeper sleeps through the margin, then goes to work")
    void dawnHysteresisForPhases() {
        long dawn = DayCycle.NIGHT_END;
        assertEquals(Phase.SLEEP, DayCycle.phase(dawn, Phase.SLEEP));
        assertEquals(Phase.SLEEP, DayCycle.phase(dawn + DayCycle.HYSTERESIS - 1, Phase.SLEEP));
        assertEquals(Phase.WORK, DayCycle.phase(dawn + DayCycle.HYSTERESIS, Phase.SLEEP));
        // A Neran already up stays up just before the boundary.
        for (Phase previous : AWAKE) {
            assertEquals(Phase.WORK, DayCycle.phase(dawn - 1, previous), "from " + previous);
            assertEquals(Phase.WORK, DayCycle.phase(dawn - DayCycle.HYSTERESIS, previous), "from " + previous);
            assertEquals(Phase.SLEEP, DayCycle.phase(dawn - DayCycle.HYSTERESIS - 1, previous), "from " + previous);
        }
    }

    @Test
    @DisplayName("Which waking phase came before makes no difference: only asleep-or-not is remembered")
    void wakingPhasesAreInterchangeable() {
        for (long t = 0; t < DayCycle.DAY_LENGTH; t += 5) {
            Phase fromWork = DayCycle.phase(t, Phase.WORK);
            assertEquals(fromWork, DayCycle.phase(t, Phase.EAT), "at " + t);
            assertEquals(fromWork, DayCycle.phase(t, Phase.SOCIAL), "at " + t);
        }
    }

    @Test
    @DisplayName("The phase agrees with isNight: sleep exactly when it is night for that Neran")
    void sleepMatchesNight() {
        for (long t = 0; t < DayCycle.DAY_LENGTH; t++) {
            for (Phase previous : Phase.values()) {
                boolean night = DayCycle.isNight(t, previous == Phase.SLEEP);
                assertEquals(night, DayCycle.phase(t, previous) == Phase.SLEEP, "at " + t + " from " + previous);
            }
        }
    }

    @Test
    @DisplayName("A day followed tick by tick runs work, eat, work, social, sleep, work")
    void fullDayInOrder() {
        List<Phase> changes = new ArrayList<>();
        Phase current = Phase.WORK;
        changes.add(current);
        for (long t = 0; t < DayCycle.DAY_LENGTH; t++) {
            Phase next = DayCycle.phase(t, current);
            if (next != current) {
                changes.add(next);
                current = next;
            }
        }
        assertEquals(List.of(Phase.WORK, Phase.EAT, Phase.WORK, Phase.SOCIAL, Phase.SLEEP, Phase.WORK), changes);
    }

    @Test
    @DisplayName("Phases wrap by day length, including negative clock values")
    void phasesWrap() {
        assertEquals(Phase.EAT, DayCycle.phase(DayCycle.EAT_START + 3 * DayCycle.DAY_LENGTH, Phase.WORK));
        assertEquals(Phase.SOCIAL, DayCycle.phase(DayCycle.SOCIAL_START - DayCycle.DAY_LENGTH, Phase.WORK));
        assertEquals(Phase.SLEEP, DayCycle.phase(-6_000L, Phase.WORK)); // 18 000 of the previous day
        for (long t = 0; t < DayCycle.DAY_LENGTH; t += 250) {
            for (Phase previous : Phase.values()) {
                assertEquals(DayCycle.phase(t, previous), DayCycle.phase(t + 7 * DayCycle.DAY_LENGTH, previous));
                assertEquals(DayCycle.phase(t, previous), DayCycle.phase(t - 2 * DayCycle.DAY_LENGTH, previous));
            }
        }
    }
}
