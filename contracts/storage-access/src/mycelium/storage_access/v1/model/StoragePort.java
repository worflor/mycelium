package mycelium.storage_access.v1.model;

import java.util.Optional;
import java.util.Set;

/**
 * The seam to the loader's block storage: Fabric's item and fluid lookups, NeoForge's and Forge's block capabilities,
 * vanilla's {@code Container} and {@code WorldlyContainer} under all of them. One port per level set (a server).
 */
public interface StoragePort {
    /** The kinds this platform reaches at all (a platform with no fluid storage API declares only {@code ITEM}). */
    Set<Kind> kinds();

    /**
     * The store of {@code kind} at the address's level and position, answering for its face, if one stands there now (S1).
     * A store that has vanished is never handed out; a store placed again at the address is found fresh.
     */
    Optional<Store> find(StoreAddress address, Kind kind);
}
