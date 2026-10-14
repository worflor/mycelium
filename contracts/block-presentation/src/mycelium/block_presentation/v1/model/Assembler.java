package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The pure assembly of a mosaic: which parts, turned how, a tuple of input values draws. The compiler's manifest is computed with
 * it, the run-time plan assembles a block with it, and the harness holds every emitted artefact to it, so the three cannot disagree.
 * Reading a block's data and sizing inputs are the Sigil's ({@link Evaluator}), unchanged.
 */
public final class Assembler {
    private Assembler() {
    }

    /**
     * The parts drawn for these values: every part whose guard holds, in the order the mosaic lists them, with the model expanded.
     * The tuple must cover every input the mosaic reads.
     */
    public static List<Placed> assemble(Mosaic mosaic, Map<String, Object> tuple) {
        List<Placed> out = new ArrayList<>();
        for (int n = 0; n < mosaic.parts().size(); n++) {
            Part part = mosaic.parts().get(n);
            if (part.holds(tuple)) {
                out.add(new Placed(Template.expand(part.model(), tuple), part.turn(), n));
            }
        }
        return out;
    }

    /**
     * What a block with this data draws. An input whose component or field is absent takes its default; one outside its domain
     * takes its default too and the assembly says it was not in domain. Nothing is refused: a block always draws something.
     */
    public static Assembly assemble(Mosaic mosaic, ComponentView view) {
        Evaluator.Reading reading = Evaluator.read(mosaic.program(), view);
        return new Assembly(assemble(mosaic, reading.values()), reading.inDomain());
    }

    /** How many tuples part {@code n} spans: the product of the domains of the inputs it reads, saturating. */
    public static long span(Mosaic mosaic, int n) {
        return Evaluator.product(mosaic.inputsOf(n));
    }

    /**
     * Every model any state of the block can draw, sorted and without repeats: for each part, the models of the tuples of the
     * inputs that part reads for which its guard holds. Parts are enumerated apart, so the cost is the sum of their spans and
     * not the product of every domain; the caller bounds each span before asking.
     */
    public static List<String> manifest(Mosaic mosaic) {
        TreeSet<String> models = new TreeSet<>();
        for (int n = 0; n < mosaic.parts().size(); n++) {
            Part part = mosaic.parts().get(n);
            List<Input> read = mosaic.inputsOf(n);
            for (Map<String, Object> tuple : Evaluator.tuples(read)) {
                if (part.holds(tuple)) {
                    models.add(Template.expand(part.model(), tuple));
                }
            }
        }
        return new ArrayList<>(models);
    }
}
