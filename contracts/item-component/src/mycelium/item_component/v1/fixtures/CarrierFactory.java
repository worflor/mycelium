package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/** How the harness makes the carrier under test: the reference makes the model's own, a broken one wraps it and gets one thing wrong. */
@FunctionalInterface
public interface CarrierFactory {
    FencedCarrier create(ComponentRegistry registry, LegacyKeyMigration migration);

    /** The model's own carrier. */
    static CarrierFactory reference() {
        return StandardFencedCarrier::of;
    }
}
