package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/** Explicit opt-in target plan, also usable by Java-free datapack projects. */
public abstract class DatapackTargetPlanTask extends DefaultTask {
    @Internal public abstract DirectoryProperty getRoot();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getEraTable();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getVersionTable();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getDeclaration();
    @OutputFile public abstract RegularFileProperty getOutputFile();
    @Option(option="declaration", description="JSON declaration: schema and exact targets")
    public void declaration(String path) { getDeclaration().set(getProject().file(path)); }
    @Option(option="output", description="Generated per-target datapack facts JSON")
    public void output(String path) { getOutputFile().set(getProject().file(path)); }
    @TaskAction public void run() throws Exception {
        DatapackTargetPlan.main(new String[]{getRoot().get().getAsFile().getAbsolutePath(),
                getDeclaration().get().getAsFile().getAbsolutePath(), getOutputFile().get().getAsFile().getAbsolutePath()});
    }
}
