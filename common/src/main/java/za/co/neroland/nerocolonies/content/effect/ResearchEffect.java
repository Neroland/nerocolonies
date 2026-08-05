package za.co.neroland.nerocolonies.content.effect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One consequence of unlocking a research node.
 *
 * <p>Effects are a <b>dispatched</b> codec keyed on a {@code type} field, exactly like NeroQuests'
 * objectives and rewards:
 *
 * <pre>{@code
 * { "type": "nerocolonies:job_slots",  "amount": 2 }
 * { "type": "nerocolonies:job_unlock", "job": "nerocolonies:hydroponics" }
 * }</pre>
 *
 * <p>An unregistered {@code type} decodes to {@link Unknown} rather than failing, so a datapack
 * written for a newer NeroColonies never bricks an older jar: the node still loads, the unknown
 * effect is inert, and {@link ResearchEffectTypes} logs the id once.
 *
 * <p>The concrete effects are nested records rather than separate files because each is one line of
 * data and they are only ever read together — there is nothing here to specialise.
 */
public interface ResearchEffect {

    /** The registered type id, written as the effect's {@code type} field. */
    Identifier typeId();

    /** The dispatching codec every {@code effects} list uses. */
    Codec<ResearchEffect> CODEC =
            Identifier.CODEC.dispatch("type", ResearchEffect::typeId, ResearchEffectTypes::codecFor);

    // --- concrete effects ---------------------------------------------------

    /** Makes a housing tier buildable/countable in this colony. */
    record HousingTierUnlock(Identifier tier) implements ResearchEffect {

        public static final MapCodec<HousingTierUnlock> MAP_CODEC =
                RecordCodecBuilder.mapCodec(inst -> inst.group(
                        Identifier.CODEC.fieldOf("tier").forGetter(HousingTierUnlock::tier)
                ).apply(inst, HousingTierUnlock::new));

        @Override
        public Identifier typeId() {
            return ResearchEffectTypes.HOUSING_TIER;
        }
    }

    /** Makes a job definition assignable in this colony. */
    record JobUnlock(Identifier job) implements ResearchEffect {

        public static final MapCodec<JobUnlock> MAP_CODEC =
                RecordCodecBuilder.mapCodec(inst -> inst.group(
                        Identifier.CODEC.fieldOf("job").forGetter(JobUnlock::job)
                ).apply(inst, JobUnlock::new));

        @Override
        public Identifier typeId() {
            return ResearchEffectTypes.JOB_UNLOCK;
        }
    }

    /** Adds simultaneously worked job slots on top of {@code jobSlotsPerColony}. */
    record JobSlots(int amount) implements ResearchEffect {

        public static final MapCodec<JobSlots> MAP_CODEC =
                RecordCodecBuilder.mapCodec(inst -> inst.group(
                        Codec.INT.optionalFieldOf("amount", 1).forGetter(JobSlots::amount)
                ).apply(inst, JobSlots::new));

        @Override
        public Identifier typeId() {
            return ResearchEffectTypes.JOB_SLOTS;
        }
    }

    /**
     * Multiplies the colony's life-support oxygen burn. Values below {@code 1.0} are an improvement;
     * the multipliers of every unlocked node compound.
     */
    record OxygenEfficiency(double multiplier) implements ResearchEffect {

        public OxygenEfficiency {
            multiplier = Math.clamp(multiplier, 0.05D, 4.0D);
        }

        public static final MapCodec<OxygenEfficiency> MAP_CODEC =
                RecordCodecBuilder.mapCodec(inst -> inst.group(
                        Codec.DOUBLE.optionalFieldOf("multiplier", 0.9D)
                                .forGetter(OxygenEfficiency::multiplier)
                ).apply(inst, OxygenEfficiency::new));

        @Override
        public Identifier typeId() {
            return ResearchEffectTypes.OXYGEN_EFFICIENCY;
        }
    }

    /** Makes an export entry sellable from this colony. */
    record ExportUnlock(Identifier export) implements ResearchEffect {

        public static final MapCodec<ExportUnlock> MAP_CODEC =
                RecordCodecBuilder.mapCodec(inst -> inst.group(
                        Identifier.CODEC.fieldOf("export").forGetter(ExportUnlock::export)
                ).apply(inst, ExportUnlock::new));

        @Override
        public Identifier typeId() {
            return ResearchEffectTypes.EXPORT_UNLOCK;
        }
    }

    /** A flat addition to the colony's morale target. May be negative. */
    record MoraleBonus(double amount) implements ResearchEffect {

        public MoraleBonus {
            amount = Math.clamp(amount, -100.0D, 100.0D);
        }

        public static final MapCodec<MoraleBonus> MAP_CODEC =
                RecordCodecBuilder.mapCodec(inst -> inst.group(
                        Codec.DOUBLE.optionalFieldOf("amount", 1.0D).forGetter(MoraleBonus::amount)
                ).apply(inst, MoraleBonus::new));

        @Override
        public Identifier typeId() {
            return ResearchEffectTypes.MORALE_BONUS;
        }
    }

    /**
     * The fallback for a {@code type} this jar does not know. Carries the id it could not resolve
     * and does nothing at all — a newer datapack degrades rather than failing to load.
     */
    record Unknown(Identifier unresolvedType) implements ResearchEffect {

        @Override
        public Identifier typeId() {
            return this.unresolvedType;
        }
    }
}
