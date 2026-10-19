package dev.woflo.mycelium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every check the plugin makes of a mod, with its level (docs/decisions/0017, "Offer, don't police"). A check is
 * {@code off}, {@code warn} or {@code fail}; the defaults below fail only for silent breakage (a build that would ship
 * something that does not work and says nothing), and everything else warns. A mod sets a level in one place,
 * {@code mycelium { checks { ... } }}, and may allow one site with a reason; allowances are recorded in the evidence
 * manifest, so nothing is hidden by being allowed.
 *
 * <p>A check that is a <em>proof</em> (the pure layer compiles alone, a contract's harness, an era's stubs) is not in this
 * table: it either holds or the artifact is not what it says. The table is the checks that explain.
 */
public final class Checks {
    private Checks() {
    }

    public enum Level {
        OFF, WARN, FAIL;

        public static Level parse(String text) {
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("a check level is off, warn or fail, not '" + text + "'");
            }
        }

        public String lower() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One check: its id, default level, what it holds and what it prevents. */
    public record Check(String id, Level def, String what, String prevents) {
    }

    /** The table, in the order docs/plugin.md lists it. */
    public static final Map<String, Check> TABLE = new LinkedHashMap<>();

    private static void check(String id, Level def, String what, String prevents) {
        TABLE.put(id, new Check(id, def, what, prevents));
    }

    static {
        check("P1", Level.WARN, "the pure layer names no platform package (text)",
                "a pure layer that is not pure; the compile-alone proof (myceliumPureCompile) is the proof, this explains it");
        check("P1.split", Level.FAIL, "no package is split between the pure layer and the platform sources",
                "two layers' classes in one package: a pure class silently shadowed or package-private-linked to the platform");
        check("P3", Level.WARN, "the pure layer reads no clock, environment or unseeded chance (text)",
                "a pure layer that differs run to run; a policy, so it warns unless the mod declares a law it breaks");
        check("I1", Level.FAIL, "an optional integration's API is mentioned only in its home package",
                "NoClassDefFoundError when the other mod is not installed");
        check("I2", Level.WARN, "an integration's home is referenced only from its gates",
                "the home loaded without its guard; with no declared gates the referencing sites are reported as the derived gates");
        check("U1", Level.WARN, "an explicitly imported contract is referenced by the mod's own sources",
                "an evidence manifest that says the mod carries a contract it never uses");
        check("S1", Level.WARN, "the portability surface has not grown past its baseline",
                "the cost of the next port growing unseen (needs surfaceBaseline)");
        check("R0", Level.FAIL, "the claimed range is real: versions exist, the loader exists on each, proofs are usable",
                "a claim about versions that do not exist");
        check("R1", Level.WARN, "the range declares a keystone proof",
                "a range with no version it was run on (versions stay interpolated)");
        check("R2", Level.WARN, "each side of a wall inside the range has a proof",
                "a claim across a wall nobody crossed (versions stay interpolated)");
        check("R3", Level.FAIL, "the loader's metadata accepts every claimed version",
                "the loader refusing the artifact where it claims to run");
        check("L1", Level.FAIL, "every game reference resolves on each claimed version with name data",
                "NoSuchMethodError on a claimed version");
        check("L2", Level.WARN, "pinned name data was available to link against",
                "a version quietly not linked because its data could not be fetched");
        check("B1", Level.FAIL, "a bridge the mod overrides is a class the project compiles and it implements the bridge's port",
                "an override the contract's laws cannot be held to: a typo, or a class that is not the port");
        check("B2", Level.WARN, "a bridge has a realization on the target version",
                "a mod that targets a version a bridge cannot map on and does not know it: the bridge was not imported there");
        check("C1", Level.WARN, "a callback's static reference dependencies are bound before exposure (potential; opt-in experimental linter)",
                "callbacks observing state before initialization; external scheduling guarantees require separate evidence");
        check("PR1", Level.FAIL, "the committed presentation lock holds every selection the programs need",
                "an item drawn with no model on a version (a warn writes no presentation files)");
    }

    /** The configured levels and allowances, as the tasks see them. */
    public record Allowance(String check, String site, String reason) {
        boolean covers(String id, String where) {
            if (!check.equals(id)) {
                return false;
            }
            if (site.equals("*")) {
                return true;
            }
            String s = where.replace('\\', '/');
            String p = site.replace('\\', '/');
            return s.equals(p) || s.endsWith("/" + p);
        }
    }

    public record Config(Map<String, Level> levels, List<Allowance> allowances) {
        public static final Config DEFAULTS = new Config(Map.of(), List.of());

        public Level level(String id) {
            Level l = levels.get(id);
            if (l != null) {
                return l;
            }
            Check c = TABLE.get(id);
            if (c == null) {
                throw new IllegalArgumentException("Mycelium: unknown check '" + id + "' (known: " + TABLE.keySet() + ")");
            }
            return c.def();
        }

        /** The allowance for {@code id} at {@code site}, or null. */
        public Allowance allowed(String id, String site) {
            for (Allowance a : allowances) {
                if (a.covers(id, site)) {
                    return a;
                }
            }
            return null;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonObject lv = new JsonObject();
            for (Check c : TABLE.values()) {
                JsonObject one = new JsonObject();
                one.addProperty("level", level(c.id()).lower());
                one.addProperty("default", c.def().lower());
                lv.add(c.id(), one);
            }
            o.add("levels", lv);
            JsonArray al = new JsonArray();
            for (Allowance a : allowances) {
                JsonObject one = new JsonObject();
                one.addProperty("check", a.check());
                one.addProperty("site", a.site());
                one.addProperty("reason", a.reason());
                al.add(one);
            }
            o.add("allowances", al);
            return o;
        }
    }

    // --- the encoding tasks receive as an input ------------------------------------------------------------------------

    public static String encodeLevel(String id, Level level) {
        return "L|" + id + "|" + level.lower();
    }

    public static String encodeAllowance(String id, String site, String reason) {
        return "A|" + id + "|" + site + "|" + reason;
    }

    public static Config decode(List<String> lines) {
        Map<String, Level> levels = new LinkedHashMap<>();
        List<Allowance> allowances = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\\|", 4);
            if (p[0].equals("L") && p.length == 3) {
                levels.put(p[1], Level.parse(p[2]));
            } else if (p[0].equals("A") && p.length == 4) {
                allowances.add(new Allowance(p[1], p[2], p[3]));
            } else {
                throw new IllegalArgumentException("Mycelium: malformed check configuration '" + line + "'");
            }
        }
        return new Config(levels, allowances);
    }

    /** Validates a level setting where it is written, so a misspelt id is named at the line that made it. */
    public static void validateId(String id) {
        if (!TABLE.containsKey(id)) {
            throw new IllegalArgumentException("Mycelium: unknown check '" + id + "' (known: " + TABLE.keySet() + ")");
        }
    }

    // --- findings ------------------------------------------------------------------------------------------------------

    /** What one check found at one site, and what the configuration made of it. */
    public record Finding(String check, String site, String message, String effect, String reason) {
    }

    /** A task's findings, sorted into what fails, what warns and what an allowance covers. */
    public static final class Findings {
        private final Config config;
        private final List<Finding> all = new ArrayList<>();

        public Findings(Config config) {
            this.config = config;
        }

        /** Records a finding; returns whether it counts (not off, not allowed). */
        public boolean add(String id, String site, String message) {
            Level level = config.level(id);
            if (level == Level.OFF) {
                return false;
            }
            Allowance a = config.allowed(id, site);
            if (a != null) {
                all.add(new Finding(id, site, message, "allowed", a.reason()));
                return false;
            }
            all.add(new Finding(id, site, message, level.lower(), null));
            return true;
        }

        public Config config() {
            return config;
        }

        public List<Finding> all() {
            return all;
        }

        public List<Finding> failing() {
            return all.stream().filter(f -> f.effect().equals("fail")).toList();
        }

        public List<Finding> warning() {
            return all.stream().filter(f -> f.effect().equals("warn")).toList();
        }

        public JsonArray toJson() {
            JsonArray out = new JsonArray();
            for (Finding f : all) {
                JsonObject o = new JsonObject();
                o.addProperty("check", f.check());
                o.addProperty("site", f.site());
                o.addProperty("effect", f.effect());
                o.addProperty("message", f.message());
                if (f.reason() != null) {
                    o.addProperty("allowedBecause", f.reason());
                }
                out.add(o);
            }
            return out;
        }

        /** Logs the warnings and throws {@code headline} plus the failures, if there are any. */
        public void conclude(Logger log, String headline) {
            List<Finding> warn = warning();
            if (!warn.isEmpty()) {
                StringBuilder sb = new StringBuilder("Mycelium: " + headline + " (warning; set mycelium { checks { ... } } to change)");
                warn.forEach(f -> sb.append("\n  [").append(f.check()).append("] ").append(f.message()));
                log.warn(sb.toString());
            }
            List<Finding> fail = failing();
            if (!fail.isEmpty()) {
                StringBuilder sb = new StringBuilder("Mycelium: " + headline);
                fail.forEach(f -> sb.append("\n  [").append(f.check()).append("] ").append(f.message()));
                sb.append("\n  (a check can be lowered or allowed with a reason: mycelium { checks { ... } }, docs/plugin.md)");
                throw new GradleException(sb.toString());
            }
        }
    }
}
