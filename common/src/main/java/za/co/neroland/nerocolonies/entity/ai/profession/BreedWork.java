package za.co.neroland.nerocolonies.entity.ai.profession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerocolonies.colony.Colony;
import za.co.neroland.nerocolonies.colony.ColonyDefence;
import za.co.neroland.nerocolonies.colony.ColonyStorage;
import za.co.neroland.nerocolonies.colony.ColonyStores;
import za.co.neroland.nerocolonies.content.ItemTarget;
import za.co.neroland.nerocolonies.entity.ColonistEntity;

/**
 * Breeding the colony's livestock: the Beastkeeper's main work, and something a Farmer does between
 * rounds of the field. Two grown animals of a kind inside the claim are fed one item each of their
 * breeding food from colony storage and fall in love, the way a player breeds them; vanilla does the
 * rest. Tamed animals and the colony's guardians are left alone, and a kind is not bred past
 * {@value #HERD_CAP} head within reach, so the pens do not fill the claim.
 */
final class BreedWork {

    /** How far around the workplace the Neran looks for livestock. */
    static final int RANGE = 16;

    /** A kind is not bred once this many (calves included) are within reach. */
    static final int HERD_CAP = 12;

    private BreedWork() {
    }

    /**
     * Feeds the nearest breedable pair.
     *
     * @return whether a pair was fed (or the Neran is walking to it)
     */
    static boolean work(ServerLevel level, ColonistEntity neran, Colony colony, BlockPos anchor) {
        Pair pair = findPair(level, colony, anchor);
        if (pair == null) {
            return false;
        }
        if (!WorkSupport.reach(neran, pair.first().blockPosition(), 9.0D)) {
            neran.workMemory().setTarget(pair.first().blockPosition(), level.getGameTime());
            return true;
        }
        if (!ColonyStorage.consume(level.getServer(), colony.colonyId(),
                List.of(new ItemTarget(Optional.of(pair.food()), Optional.empty(), 2)))) {
            return false;
        }
        WorkSupport.strike(neran, pair.first().blockPosition());
        pair.first().setInLove(null);
        pair.second().setInLove(null);
        return true;
    }

    private record Pair(Animal first, Animal second, Identifier food) {
    }

    @Nullable
    private static Pair findPair(ServerLevel level, Colony colony, BlockPos anchor) {
        AABB box = new AABB(anchor).inflate(RANGE);
        Map<EntityType<?>, List<Animal>> herds = new HashMap<>();
        for (Animal animal : level.getEntitiesOfClass(Animal.class, box, a -> a.isAlive()
                && !(a instanceof TamableAnimal) && !ColonyDefence.isGuardian(a)
                && colony.contains(a.blockPosition()))) {
            herds.computeIfAbsent(animal.getType(), key -> new ArrayList<>()).add(animal);
        }
        for (List<Animal> herd : herds.values()) {
            if (herd.size() >= HERD_CAP) {
                continue;
            }
            List<Animal> ready = new ArrayList<>();
            for (Animal animal : herd) {
                if (!animal.isBaby() && animal.getAge() == 0 && animal.canFallInLove()) {
                    ready.add(animal);
                }
            }
            if (ready.size() < 2) {
                continue;
            }
            Identifier food = storedFood(level, colony, ready.get(0));
            if (food != null) {
                return new Pair(ready.get(0), ready.get(1), food);
            }
        }
        return null;
    }

    /** An item in colony storage this animal breeds on, with at least two in stock. */
    @Nullable
    private static Identifier storedFood(ServerLevel level, Colony colony, Animal animal) {
        ColonyStores.Store store = ColonyStores.get(level.getServer()).peek(colony.colonyId());
        if (store == null) {
            return null;
        }
        Map<Identifier, Integer> counts = new HashMap<>();
        for (ItemStack stack : store.storage()) {
            if (!stack.isEmpty() && animal.isFood(stack)) {
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (counts.merge(id, stack.getCount(), Integer::sum) >= 2) {
                    return id;
                }
            }
        }
        return null;
    }
}
