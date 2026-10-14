package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Domain;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.FieldKind;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reference compiler, which keeps every law. Its steps are the contract's: check the mosaic and the projection (M5), bound every
 * part's span before enumerating anything (M7), compute the manifest part by part (M4), and emit the plan deterministically (M6)
 * beside the assembly that agrees with the evaluator (M1, M2, M3).
 *
 * <p>The steps a broken compiler gets wrong are protected methods, so the rejection matrix breaks exactly one of them and leaves the
 * rest as they are.
 */
public class ReferenceMosaic implements MosaicCompiler {
    /** The most tuples one part may span unless a compiler is built with another cap: the Sigil's own. */
    public static final int DEFAULT_MAX_SPAN = Evaluator.DEFAULT_MAX_TUPLES;

    private final int maxSpan;

    public ReferenceMosaic() {
        this(DEFAULT_MAX_SPAN);
    }

    public ReferenceMosaic(int maxSpan) {
        if (maxSpan < 1) {
            throw new IllegalArgumentException("the cap is at least one tuple");
        }
        this.maxSpan = maxSpan;
    }

    @Override
    public int maxSpan() {
        return maxSpan;
    }

    @Override
    public MosaicCompilation compile(Mosaic mosaic, ComponentCatalog catalog, BlockTarget target) {
        String block = mosaic.block();
        if (target.realization() == BlockRealization.NONE) {
            return reject(new Diagnostic(Diagnostic.Kind.NO_REALIZATION, block, "Minecraft " + target.minecraft()
                    + " has no block presentation mechanism (the era table gives it \"none\"): there is nothing to compile for"));
        }
        List<Diagnostic> found = new ArrayList<>(mosaic.check());
        if (!found.isEmpty()) {
            return new MosaicCompilation.Rejected(found);
        }
        found.addAll(projectionDiagnostics(mosaic, catalog));
        if (!found.isEmpty()) {
            return new MosaicCompilation.Rejected(found);
        }
        found.addAll(boundDiagnostics(mosaic));
        if (!found.isEmpty()) {
            return new MosaicCompilation.Rejected(found);
        }
        Mosaic pruned = mosaic.pruned();
        MosaicPlan plan = newPlan(pruned, target.realization(), manifest(pruned));
        Map<String, String> artefacts = new TreeMap<>();
        artefacts.put(Layout.plan(block), render(plan.toTree()));
        return new MosaicCompilation.Compiled(plan, artefacts);
    }

    private static MosaicCompilation reject(Diagnostic d) {
        return new MosaicCompilation.Rejected(Collections.singletonList(d));
    }

    // --- the steps a broken compiler can get wrong -----------------------------------------------------------------

    /** Every reason the projection (M5) refuses the mosaic: an input over a component the client cannot see, and kin. */
    protected List<Diagnostic> projectionDiagnostics(Mosaic mosaic, ComponentCatalog catalog) {
        List<Diagnostic> out = new ArrayList<>();
        for (Input input : mosaic.inputs()) {
            String at = "inputs." + input.name();
            ComponentCatalog.Component component = catalog.component(input.component());
            if (component == null) {
                out.add(new Diagnostic(Diagnostic.Kind.CATALOG, at, "the component " + input.component() + " is not in the catalog"));
                continue;
            }
            out.addAll(visibility(input, component));
            if (input.domain() instanceof Domain.Present) {
                if (!component.unit()) {
                    out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, "a 'present' input reads a unit component, and "
                            + component.id() + " is a record"));
                }
            } else if (component.unit()) {
                out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, component.id() + " is a unit component: it has no field '"
                        + input.field() + "' (use a 'present' domain)"));
            } else {
                FieldKind kind = component.fields().get(input.field());
                if (kind == null) {
                    out.add(new Diagnostic(Diagnostic.Kind.CATALOG, at, component.id() + " has no field '" + input.field()
                            + "' (it has " + component.fields().keySet() + ")"));
                } else if (kind != input.domain().kind()) {
                    out.add(new Diagnostic(Diagnostic.Kind.TYPE, at, "the field '" + input.field() + "' of " + component.id()
                            + " is " + kind.id() + ", and the input's domain is " + input.domain().kind().id()));
                }
            }
        }
        return out;
    }

    /** The projection law itself (M5): a component the client is not sent cannot decide what the client draws. */
    protected List<Diagnostic> visibility(Input input, ComponentCatalog.Component component) {
        if (component.networkSynced()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new Diagnostic(Diagnostic.Kind.SERVER_PRIVATE, "inputs." + input.name(),
                "reads " + component.id() + ", which is not network-visible: the client never receives it, so presentation cannot"
                        + " depend on it. Make the component network-synced, or read something the client has"));
    }

    /**
     * The bound (M7): every part's span, the product of the domains of the inputs that part reads, is at most the cap. It is checked
     * from the domain sizes alone, before anything is enumerated, and the product saturates instead of overflowing. The product of
     * every input's domain is not bounded, and need not be: parts are enumerated apart.
     */
    protected List<Diagnostic> boundDiagnostics(Mosaic mosaic) {
        List<Diagnostic> out = new ArrayList<>();
        for (int n = 0; n < mosaic.parts().size(); n++) {
            long span = Assembler.span(mosaic, n);
            if (span > maxSpan) {
                out.add(new Diagnostic(Diagnostic.Kind.TOO_LARGE, "parts[" + n + "]", "the inputs this part reads span "
                        + (span == Long.MAX_VALUE ? "more than 2^63" : String.valueOf(span)) + " tuples; the cap is " + maxSpan
                        + ". Narrow a domain, or split the part so each reads fewer inputs"));
            }
        }
        return out;
    }

    /** Every model any state can draw (M4), sorted and without repeats. */
    protected List<String> manifest(Mosaic pruned) {
        return Assembler.manifest(pruned);
    }

    /** The plan the adapter evaluates. */
    protected MosaicPlan newPlan(Mosaic pruned, BlockRealization realization, List<String> manifest) {
        return new ReferenceMosaicPlan(pruned, realization, manifest);
    }

    /** A tree of values as bytes (M6): canonical JSON. */
    protected String render(Object tree) {
        return Json.write(tree);
    }
}
