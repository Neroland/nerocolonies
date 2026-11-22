package za.co.neroland.nerocolonies.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

/** NeroColonies' part of Core's data export: empty documents are reported as "nothing stored". Plain JVM. */
class ColonyDataExportTest {

    private static JsonObject doc(int ownedIds) {
        JsonObject root = new JsonObject();
        JsonArray owned = new JsonArray();
        for (int i = 0; i < ownedIds; i++) {
            owned.add("00000000-0000-0000-0000-00000000000" + i);
        }
        root.add("owned_colonies", owned);
        root.add("member_of_colonies", new JsonArray());
        root.add("access_log", new JsonArray());
        root.add("chief_of_colonies", new JsonArray());
        root.add("enemy_of_colonies", new JsonArray());
        return root;
    }

    @Test
    void allListsEmptyIsNothingStored() {
        assertTrue(ColonyDataExport.isEmpty(doc(0)));
    }

    @Test
    void anyRowIsTheirData() {
        assertFalse(ColonyDataExport.isEmpty(doc(1)));
    }
}
