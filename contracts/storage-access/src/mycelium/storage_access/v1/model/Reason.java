package mycelium.storage_access.v1.model;

/** Why a move moved nothing. This contract's own vocabulary (decision 0005). */
public enum Reason {
    /** The amount asked was zero or less. */
    ZERO_REQUEST,
    /** Both addresses are one store (same level and position), whatever the faces. */
    SAME_STORE,
    /** No store of the goods' kind at the source address. */
    NO_SOURCE,
    /** No store of the goods' kind at the destination address. */
    NO_DESTINATION,
    /** The source's face gives none of the goods (fluids: not one whole thousandth). */
    EMPTY_SOURCE,
    /** The destination's face takes none of the goods. */
    FULL_DESTINATION,
    /** The source gave nothing of what it promised, or gave a remainder the seam refused. */
    EXTRACTION_FAILED,
    /** The destination took nothing of what was extracted, or took a remainder the seam refused. */
    INSERTION_FAILED,
    /** The destination took less than it promised, and then less than it had just taken. */
    TRANSFER_FAILED
}
