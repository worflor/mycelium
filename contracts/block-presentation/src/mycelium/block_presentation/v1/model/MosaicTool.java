package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The whole compile, over text, for a build tool: mosaics and the component catalog go in as JSON text, and one JSON report comes out
 * (diagnostics, the files to write, the blocks compiled). It takes and returns only text and maps of text, so a build can call it
 * across a class loader with nothing in common but the JDK. It reads no file and no clock.
 */
public final class MosaicTool {
    private MosaicTool() {
    }

    /**
     * Compiles every mosaic for a target.
     *
     * @param minecraft   the version, only to name it in a refusal
     * @param realization the realization the era table gives that version ({@code dynamic-model}, {@code none})
     * @param mosaics     each mosaic's file name to its text
     * @return the report: {@code ok}, {@code diagnostics}, {@code files} (path to text), {@code blocks}
     */
    public static String compile(String minecraft, String realization, Map<String, String> mosaics, String components) {
        List<Object> diagnostics = new ArrayList<>();
        List<Object> blocks = new ArrayList<>();
        Map<String, String> files = new TreeMap<>();
        BlockRealization r = null;
        try {
            r = BlockRealization.parse(realization);
        } catch (IllegalArgumentException e) {
            add(diagnostics, "target", new Diagnostic(Diagnostic.Kind.SCHEMA, "realization", e.getMessage()));
        }
        Result<ComponentCatalog> catalog = ComponentCatalog.parse(components);
        if (catalog instanceof Result.Failed<ComponentCatalog> failed) {
            for (Diagnostic d : failed.diagnostics()) {
                add(diagnostics, "components", d);
            }
        }
        if (r != null && catalog instanceof Result.Ok<ComponentCatalog> ok) {
            BlockTarget target = new BlockTarget(minecraft, r);
            MosaicCompiler compiler = new ReferenceMosaic();
            Map<String, String> blockFile = new TreeMap<>();
            for (Map.Entry<String, String> e : new TreeMap<>(mosaics).entrySet()) {
                Result<Mosaic> parsed = Mosaic.parse(e.getValue());
                if (parsed instanceof Result.Failed<Mosaic> failed) {
                    for (Diagnostic d : failed.diagnostics()) {
                        add(diagnostics, e.getKey(), d);
                    }
                    continue;
                }
                Mosaic mosaic = ((Result.Ok<Mosaic>) parsed).value();
                String other = blockFile.put(mosaic.block(), e.getKey());
                if (other != null) {
                    add(diagnostics, e.getKey(), new Diagnostic(Diagnostic.Kind.SCHEMA, "block",
                            mosaic.block() + " is also the block of " + other + ": one mosaic per block"));
                    continue;
                }
                MosaicCompilation c = compiler.compile(mosaic, ok.value(), target);
                if (c instanceof MosaicCompilation.Rejected rejected) {
                    for (Diagnostic d : rejected.diagnostics()) {
                        add(diagnostics, e.getKey(), d);
                    }
                } else {
                    MosaicCompilation.Compiled done = (MosaicCompilation.Compiled) c;
                    files.putAll(done.artefacts());
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("file", e.getKey());
                    one.put("block", done.plan().block());
                    one.put("inputs", Long.valueOf(done.plan().mosaic().inputs().size()));
                    one.put("parts", Long.valueOf(done.plan().mosaic().parts().size()));
                    one.put("models", Long.valueOf(done.plan().manifest().size()));
                    blocks.add(one);
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", Long.valueOf(1));
        root.put("ok", Boolean.valueOf(diagnostics.isEmpty()));
        root.put("minecraft", minecraft);
        root.put("realization", realization);
        root.put("diagnostics", diagnostics);
        root.put("blocks", blocks);
        root.put("files", diagnostics.isEmpty() ? new LinkedHashMap<String, Object>(files) : new LinkedHashMap<String, Object>());
        return Json.write(root);
    }

    private static void add(List<Object> out, String file, Diagnostic d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", d.kind().name());
        m.put("where", file + ": " + d.where());
        m.put("message", d.message());
        m.put("lockLines", new ArrayList<Object>());
        out.add(m);
    }
}
