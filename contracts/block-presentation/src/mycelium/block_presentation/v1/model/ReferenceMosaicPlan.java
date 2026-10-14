package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The plan the reference compiler builds, and the one an adapter reads back from the resource it was written to. */
public final class ReferenceMosaicPlan implements MosaicPlan {
    private final Mosaic mosaic;
    private final BlockRealization realization;
    private final List<String> manifest;

    /** @param mosaic already pruned to its read inputs; @param manifest sorted, every model the mosaic can draw */
    public ReferenceMosaicPlan(Mosaic mosaic, BlockRealization realization, List<String> manifest) {
        if (realization == BlockRealization.NONE) {
            throw new IllegalArgumentException("there is no plan for a version without a block presentation mechanism");
        }
        this.mosaic = mosaic;
        this.realization = realization;
        this.manifest = Collections.unmodifiableList(new ArrayList<>(manifest));
    }

    @Override
    public String block() {
        return mosaic.block();
    }

    @Override
    public BlockRealization realization() {
        return realization;
    }

    @Override
    public Mosaic mosaic() {
        return mosaic;
    }

    @Override
    public List<String> manifest() {
        return manifest;
    }

    @Override
    public Assembly assemble(ComponentView view) {
        return Assembler.assemble(mosaic, view);
    }

    @Override
    public List<Placed> assemble(Map<String, Object> tuple) {
        return Assembler.assemble(mosaic, tuple);
    }

    @Override
    public Map<String, Object> toTree() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", Long.valueOf(1));
        root.put("realization", realization.id());
        root.put("mosaic", mosaic.toTree());
        root.put("manifest", new ArrayList<Object>(manifest));
        return root;
    }

    /**
     * Reads a plan from the resource the compiler wrote. The manifest must be exactly what the mosaic can draw, so an adapter never
     * meets a model it did not load, and never loads one nothing draws.
     */
    public static Result<MosaicPlan> parse(String text) {
        Object tree;
        try {
            tree = Json.parse(text);
        } catch (Json.SyntaxException e) {
            return fail(Diagnostic.Kind.SYNTAX, e.getMessage());
        }
        if (!(tree instanceof Map<?, ?> root) || root.size() != 4 || !Long.valueOf(1).equals(root.get("schema"))
                || !(root.get("realization") instanceof String) || !(root.get("manifest") instanceof List<?> listed)) {
            return fail(Diagnostic.Kind.SCHEMA, "a plan is {\"schema\": 1, \"realization\", \"mosaic\", \"manifest\"}");
        }
        BlockRealization realization;
        try {
            realization = BlockRealization.parse((String) root.get("realization"));
        } catch (IllegalArgumentException e) {
            return fail(Diagnostic.Kind.SCHEMA, e.getMessage());
        }
        Result<Mosaic> read = Mosaic.fromTree(root.get("mosaic"));
        if (read instanceof Result.Failed<Mosaic> failed) {
            return new Result.Failed<>(failed.diagnostics());
        }
        Mosaic mosaic = ((Result.Ok<Mosaic>) read).value();
        for (int n = 0; n < mosaic.parts().size(); n++) {
            if (Assembler.span(mosaic, n) > Evaluator.DEFAULT_MAX_TUPLES) {
                return fail(Diagnostic.Kind.TOO_LARGE, "part " + n + " spans more than " + Evaluator.DEFAULT_MAX_TUPLES + " tuples");
            }
        }
        List<String> stored = new ArrayList<>();
        for (Object o : listed) {
            if (!(o instanceof String)) {
                return fail(Diagnostic.Kind.SCHEMA, "the manifest lists model references");
            }
            stored.add((String) o);
        }
        List<String> exact = Assembler.manifest(mosaic);
        if (!exact.equals(stored)) {
            return fail(Diagnostic.Kind.SCHEMA, "the manifest is not what the mosaic can draw (it should list " + exact.size() + " models, sorted)");
        }
        return new Result.Ok<>(new ReferenceMosaicPlan(mosaic, realization, stored));
    }

    private static Result<MosaicPlan> fail(Diagnostic.Kind kind, String message) {
        return new Result.Failed<>(Collections.singletonList(new Diagnostic(kind, "plan", message)));
    }
}
