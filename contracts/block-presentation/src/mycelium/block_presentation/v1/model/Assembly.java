package mycelium.block_presentation.v1.model;

import java.util.Collections;
import java.util.List;

/**
 * What a block draws for one state: the parts whose guards hold, in the order the mosaic declares them (later paints over earlier),
 * and whether every input the mosaic reads was inside its domain. An input outside its domain reads as its default and
 * {@code inDomain} says so; the other parts are unaffected.
 */
public record Assembly(List<Placed> placed, boolean inDomain) {
    public Assembly {
        placed = Collections.unmodifiableList(List.copyOf(placed));
    }
}
