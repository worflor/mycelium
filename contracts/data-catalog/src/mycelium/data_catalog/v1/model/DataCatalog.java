package mycelium.data_catalog.v1.model;

/** Entry point: a fresh reference catalog. */
public final class DataCatalog {
    private DataCatalog() {
    }

    public static <T> Catalog<T> create() {
        return new ReferenceCatalog<>();
    }
}
