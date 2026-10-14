package mycelium.storage_access.v1.model;

import java.util.Optional;

/**
 * An item container shaped as vanilla's {@code Container} and {@code WorldlyContainer} are: numbered slots, each holding one
 * goods up to a limit, and per face the slots it may reach and whether each may take or give particular goods. An unsided
 * container answers every face with every slot and allows everything. {@link Sided#face} turns one into a {@link Store}.
 */
public interface SlottedContainer {
    boolean present();

    int size();

    Optional<Holding> at(int slot);

    /** The most of {@code goods} the slot holds: the container's stack limit and the goods' own, the smaller. */
    long limit(int slot, Goods goods);

    /** The slots a face reaches ({@code getSlotsForFace}). */
    int[] slotsFor(Face face);

    /** Whether {@code goods} may be placed into the slot through the face ({@code canPlaceItemThroughFace}, with {@code canPlaceItem}). */
    boolean canPlace(int slot, Goods goods, Face face);

    /** Whether {@code goods} may be taken from the slot through the face ({@code canTakeItemThroughFace}). */
    boolean canTake(int slot, Goods goods, Face face);

    /** The raw write the adapter and its undo use: the slot now holds exactly this, or nothing. */
    void put(int slot, Optional<Holding> holding);
}
