package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Every real Minecraft version ({@code eras/versions.json}, from Mojang's launcher manifest), newest first. A range
 * claim is enumerated against it: the versions inside a range are the ones in this list, and a range that names a
 * version missing from it names no version at all.
 */
public final class Versions {
    private Versions() {
    }

    public static List<String> load(File myceliumRoot) {
        File file = new File(myceliumRoot, "eras/versions.json");
        try {
            JsonObject json = new Gson().fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), JsonObject.class);
            List<String> out = new ArrayList<>();
            json.getAsJsonArray("versions").forEach(v -> out.add(v.getAsJsonObject().get("id").getAsString()));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Mycelium version list not found: " + file, e);
        }
    }
}
