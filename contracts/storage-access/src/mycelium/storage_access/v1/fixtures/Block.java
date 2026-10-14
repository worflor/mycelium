package mycelium.storage_access.v1.fixtures;

/** A block of the memory world that keeps goods: a container or a tank. */
public interface Block {
    boolean present();

    /** The block is gone (broken, replaced): its handles must read and move nothing from now on. */
    void vanish();

    /** The next raw write (a slot or a tank level) is followed at once by the block vanishing, as a block that breaks when touched. */
    void vanishOnNextWrite();

    /** Takes back a {@link #vanishOnNextWrite()} that no write fired. */
    void disarm();

    /** Everything it holds, slot by slot, as a value that compares equal exactly when nothing changed. */
    Object snapshot();
}
