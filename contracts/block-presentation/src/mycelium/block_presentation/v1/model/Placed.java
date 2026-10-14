package mycelium.block_presentation.v1.model;

/** A part as drawn for one state: the model it names once its template is expanded, its turn, and which part of the mosaic it is. */
public record Placed(String model, int turn, int part) {
}
