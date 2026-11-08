package za.co.neroland.nerocolonies.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerocolonies.colony.ColonyType;

/** The colony type rides at the end of the snapshot's living-colony half and survives the wire. */
class SnapshotLifeWireTest {

    private static ColonySnapshotPayload.Life roundTrip(ColonySnapshotPayload.Life life) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ColonySnapshotPayload.writeLife(buf, life);
            ColonySnapshotPayload.Life read = ColonySnapshotPayload.readLife(buf);
            assertEquals(0, buf.readableBytes(), "every byte written is read back");
            return read;
        } finally {
            buf.release();
        }
    }

    private static ColonySnapshotPayload.Life life(String type) {
        return new ColonySnapshotPayload.Life(2, 1, 10, 6, 1, 5, 3, 2, 1, 0, true, 4, 9, 0,
                List.of(new ColonySnapshotPayload.NeedLine("minecraft:iron_ingot", "item.minecraft.iron_ingot",
                        3, 8, 12, true)),
                List.of(), type);
    }

    @Test
    @DisplayName("Every colony type writes and reads back unchanged, with the rest of the record")
    void everyTypeRoundTrips() {
        for (ColonyType type : ColonyType.values()) {
            ColonySnapshotPayload.Life original = life(type.serializedName());
            ColonySnapshotPayload.Life read = roundTrip(original);
            assertEquals(original, read);
            assertEquals(type, read.type());
        }
    }

    @Test
    @DisplayName("An unknown type off the wire reads as GENERIC")
    void unknownIsGeneric() {
        assertEquals(ColonyType.GENERIC, roundTrip(life("plasma_city")).type());
        assertEquals(ColonyType.GENERIC, ColonySnapshotPayload.Life.NONE.type());
    }
}
