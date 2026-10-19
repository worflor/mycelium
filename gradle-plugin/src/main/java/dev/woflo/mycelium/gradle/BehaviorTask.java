package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Experimental, opt-in behavioral diagnostics over the selected target's compiled consumer code. */
public abstract class BehaviorTask extends DefaultTask {
    public BehaviorTask() {
        getOutputs().upToDateWhen(task -> ((BehaviorTask) task).getPlatformRuntimeImages().stream().allMatch(java.io.File::isFile));
    }

    @Input
    public String getAnalysisRuntime() {
        return System.getProperty("java.runtime.version") + " / " + System.getProperty("java.vendor");
    }

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public java.util.List<java.io.File> getPlatformRuntimeImages() {
        return PlatformHierarchy.inputs().stream().map(java.nio.file.Path::toFile).toList();
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    @Input
    public abstract ListProperty<String> getChecks();

    @Input
    public abstract Property<Integer> getTargetJava();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws Exception {
        var inputs = getClasses().getFiles().stream().map(java.io.File::toPath).sorted().toList();
        var result = CallbackInitialization.inspect(inputs, getTargetJava().get(),
                progress -> getLogger().lifecycle(
                        "Mycelium bytecode analysis [{}]: {} summaries, {} pending, {} contexts, {} instructions, {} initialization updates; busiest={} ({} updates)",
                        progress.phase(), progress.summaryEvaluations(), progress.pendingSummaries() + progress.initializationPending(),
                        progress.callbackContexts(), progress.dataflowSteps(), progress.initializationPropagations(),
                        progress.hottestSummary().replaceFirst("\\[\\d+=.*", " [callback context]"), progress.summaryUpdates()));
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("experimental", true);
        report.addProperty("targetJava", getTargetJava().get());
        report.addProperty("inputSha256", Fingerprint.ofDirectories(inputs));
        boolean checked = result.analyzedBodies() > 0 && !result.roots().isEmpty();
        report.addProperty("checked", checked);
        report.addProperty("complete", checked && result.analysisErrors().isEmpty());
        report.addProperty("completenessScope", "supported bytecode model only");
        report.addProperty("conclusion", "undetermined");
        report.addProperty("conclusionReason", "C1 reports potential callback risks; external effects and scheduling remain unknown.");
        report.addProperty("classFiles", result.classes());
        report.addProperty("methods", result.methods());
        report.addProperty("analyzedBodies", result.analyzedBodies());
        report.addProperty("solver", "dependency-driven interprocedural worklist");
        report.addProperty("summaryEvaluations", result.summaryEvaluations());
        report.addProperty("dependencyEdges", result.dependencyEdges());
        report.addProperty("callbackContexts", result.callbackContexts());
        report.addProperty("dataflowSteps", result.dataflowSteps());
        report.addProperty("initializationPropagations", result.initializationPropagations());
        JsonArray dependencies = new JsonArray();
        for (var edge : result.dependencies()) {
            JsonObject item = new JsonObject();
            item.addProperty("factType", edge.factType());
            item.addProperty("fact", edge.fact());
            item.addProperty("dependent", edge.dependent().toString());
            if (edge.dependentContext() != null) item.addProperty("dependentContext", edge.dependentContext());
            dependencies.add(item);
        }
        report.add("summaryDependencies", dependencies);
        report.addProperty("scope", "static reference dependencies exposed through callbacks in compiled consumer code");
        report.addProperty("nativeEventOrderProven", false);
        report.addProperty("assignmentIsNotNullnessProof", true);
        report.addProperty("externalScheduling", "unknown");
        JsonObject exceptions = new JsonObject();
        exceptions.addProperty("separateOutcomeEffects", true);
        exceptions.addProperty("handlerOrder", "class-file exception table");
        exceptions.addProperty("rethrowPartitions", "caught exception origins and types");
        exceptions.addProperty("argumentDependentBindings", true);
        exceptions.addProperty("referenceOriginSnapshots", true);
        exceptions.addProperty("platformAncestryJava", result.platformHierarchy().targetJava());
        exceptions.addProperty("platformAncestryModel", result.platformHierarchy().model());
        exceptions.addProperty("platformAncestryStatus", result.platformHierarchy().status());
        exceptions.addProperty("platformAncestryReason", result.platformHierarchy().reason());
        exceptions.addProperty("analysisRuntime", getAnalysisRuntime());
        var image = getPlatformRuntimeImages().get(0);
        if (image.isFile()) exceptions.addProperty("platformRuntimeSha256", Fingerprint.ofFile(image.toPath()));
        var releaseSymbols = getPlatformRuntimeImages().get(1);
        if (releaseSymbols.isFile()) exceptions.addProperty("platformReleaseSymbolsSha256", Fingerprint.ofFile(releaseSymbols.toPath()));
        exceptions.addProperty("implicitFaultConditionsProven", false);
        exceptions.addProperty("externalFieldEffectsKnown", false);
        report.add("exceptionFlow", exceptions);
        JsonArray limits = new JsonArray();
        for (String limit : java.util.List.of("reflective entrypoints", "captured instance aliases",
                "complete runtime dispatch targets", "native method bodies", "unknown callback targets and effects",
                "implicit initializer cross-owner effects and reentrant initialization",
                "complete implicit fault conditions and asynchronous exceptions",
                "platform headers absent from the requested javac release API model", "native event ordering")) limits.add(limit);
        report.add("unmodeled", limits);
        report.addProperty("rootSelection", "public methods, plus class initializers that expose callbacks");
        JsonArray roots = new JsonArray();
        result.roots().forEach(root -> roots.add(root.toString()));
        report.add("roots", roots);
        if (!checked) report.addProperty("why", result.analyzedBodies() == 0
                ? "no compiled method bodies to analyze" : "no entry roots could be inferred");
        JsonArray candidates = new JsonArray();
        Checks.Findings found = new Checks.Findings(Checks.decode(getChecks().get()));
        for (var candidate : result.findings()) {
            JsonObject item = new JsonObject();
            item.addProperty("root", candidate.root().toString());
            item.addProperty("registration", candidate.registration());
            item.addProperty("callback", candidate.callback().toString());
            item.addProperty("field", candidate.dependency().toString());
            item.addProperty("read", candidate.read());
            item.addProperty("confidence", "potential");
            candidates.add(item);
            found.add("C1", candidate.registration(), candidate.registration() + ": callback " + candidate.callback()
                    + " can dereference " + candidate.dependency() + " at " + candidate.read()
                    + " without an established and preserved binding on this path. Check nullable writes and the external API's ordering guarantee.");
        }
        report.add("candidates", candidates);
        JsonArray errors = new JsonArray();
        result.analysisErrors().forEach(errors::add);
        report.add("analysisErrors", errors);
        for (String error : result.analysisErrors()) found.add("C1", "*", "callback analysis incomplete: " + error);
        report.add("findings", found.toJson());
        var directory = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(directory);
        try (var output = Files.newBufferedWriter(directory.resolve("behavior.json"), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report, output);
            output.write("\n");
        }
        getLogger().lifecycle("Mycelium experimental linter: callback initialization: {} method bodies, {} potential dependencies, {} analysis error(s); native event order not proven",
                result.analyzedBodies(), result.findings().size(), result.analysisErrors().size());
        found.conclude(getLogger(), "callback initialization");
    }
}
