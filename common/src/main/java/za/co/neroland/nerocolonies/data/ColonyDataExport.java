package za.co.neroland.nerocolonies.data;

import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.data.PlayerDataExport;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.colony.ColonyRoles;
import za.co.neroland.nerocolonies.colony.ColonyState;

/**
 * NeroColonies' part of Neroland Core's {@code /neroland data export}: the same document as
 * {@code /nerocolonies data export} (owned and member colonies, Chief and Enemy listings, the player's own
 * access-log rows; never another player's id), built in memory and never written to disk or logged.
 */
public final class ColonyDataExport {

    private ColonyDataExport() {
    }

    /** Registers with Core at common init. */
    public static void register() {
        PlayerDataExport.register(NeroColoniesCommon.MOD_ID, ColonyDataExport::export);
    }

    /** Core's exporter over the live stores. Server thread; reads only. */
    public static @Nullable JsonObject export(MinecraftServer server, UUID player) {
        JsonObject mine = ColonyState.get(server).export(player);
        ColonyRoles.get(server).exportInto(mine, player);
        return isEmpty(mine) ? null : mine;
    }

    /** Whether every list in the document is empty (NeroColonies holds nothing for the player). Plain JVM. */
    public static boolean isEmpty(JsonObject doc) {
        for (Map.Entry<String, JsonElement> field : doc.entrySet()) {
            JsonElement value = field.getValue();
            if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
