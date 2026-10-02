package za.co.neroland.nerocolonies.data;

import java.util.ArrayList;
import java.util.List;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import za.co.neroland.nerocolonies.NeroColoniesCommon;

/**
 * List codecs that survive a bad entry.
 *
 * <p>{@code Codec.listOf()} fails the whole list when one element fails, and every NeroColonies
 * store is a handful of lists. One item from a mod that has since been removed, or one malformed
 * UUID, used to fail the entire file. {@link SavedDataRecovery} then installed fresh data, and the
 * next save overwrote every colony on the server. A lenient list decodes what it can, skips what it
 * cannot, and logs how many it skipped (a count and a label, never the content, which could be
 * player-shaped).
 *
 * <p>Encoding is unchanged, so a file written by this codec is byte-compatible with one written by
 * {@code listOf()}.
 */
public final class LenientCodecs {

    private LenientCodecs() {
    }

    /**
     * A list codec that skips elements that fail to decode.
     *
     * @param element the element codec
     * @param label   a stable, non-identifying name for log lines, e.g. {@code "colony"}
     */
    public static <T> Codec<List<T>> list(Codec<T> element, String label) {
        Codec<List<T>> strict = element.listOf();
        return new Codec<>() {
            @Override
            public <D> DataResult<Pair<List<T>, D>> decode(DynamicOps<D> ops, D input) {
                return ops.getList(input).map(stream -> {
                    List<T> out = new ArrayList<>();
                    int[] skipped = {0};
                    stream.accept(raw -> element.parse(ops, raw).result()
                            .ifPresentOrElse(out::add, () -> skipped[0]++));
                    if (skipped[0] > 0) {
                        NeroColoniesCommon.LOGGER.warn(
                                "[NeroColonies] Skipped {} unreadable {} entr{} while loading saved "
                                        + "data; everything else loaded normally.",
                                skipped[0], label, skipped[0] == 1 ? "y" : "ies");
                    }
                    return Pair.of(out, ops.empty());
                });
            }

            @Override
            public <D> DataResult<D> encode(List<T> input, DynamicOps<D> ops, D prefix) {
                return strict.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return "LenientList[" + element + "]";
            }
        };
    }

    /** Pure core of {@link #list}: how many of {@code results} would be kept. For tests. */
    static <T> int keptCount(List<DataResult<T>> results) {
        int kept = 0;
        for (DataResult<T> result : results) {
            if (result.result().isPresent()) {
                kept++;
            }
        }
        return kept;
    }
}
