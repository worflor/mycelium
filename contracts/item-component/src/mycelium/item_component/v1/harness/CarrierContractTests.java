package mycelium.item_component.v1.harness;

import mycelium.harness.Check;
import mycelium.item_component.v1.fixtures.CarrierFactory;
import mycelium.item_component.v1.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The carrier laws of item.component@1 (SPEC.md): presence on a stack (T13), typed write outcomes (T14), one decode path
 * (T15) and declared legacy-key migration (T16 to T20), over {@link FencedCarrier}.
 */
final class CarrierContractTests {
    private static final String FENCE = RosettaTranslator.FENCED_NAMESPACE;

    private record Ownership(String owner, int issue) {
    }

    private static final RecordCodec.Field<Ownership, String> OWNER =
            RecordCodec.field("owner", ComponentCodec.STRING, Ownership::owner);
    private static final RecordCodec.Field<Ownership, Integer> ISSUE =
            RecordCodec.field("issue", ComponentCodec.INT, Ownership::issue);

    private static final ComponentDefinition<Unit> FLAG = ComponentDefinition.presence(ComponentId.of("test:flag"));
    private static final ComponentDefinition<Integer> COUNT = new ComponentDefinition<>(ComponentId.of("test:count"), ComponentCodec.INT, 0);
    private static final ComponentDefinition<Integer> RIG = ComponentDefinition.withoutDefault(ComponentId.of("test:rig"), ComponentCodec.INT);
    private static final ComponentDefinition<String> NAME = ComponentDefinition.withoutDefault(ComponentId.of("test:name"), ComponentCodec.STRING);
    private static final ComponentDefinition<Ownership> OWNERSHIP = RecordCodec.of(
            r -> new Ownership(r.get(OWNER), r.get(ISSUE)), OWNER, ISSUE).definition(ComponentId.of("test:tool_state"));

    private static final ComponentRegistry REGISTRY = ComponentRegistry.builder()
            .register(FLAG).register(COUNT).register(RIG).register(NAME).register(OWNERSHIP).build();

    private static final LegacyKeyMigration.Key OLD_DEEP = LegacyKeyMigration.Key.of("old", "deep");

    private static final LegacyKeyMigration MIGRATION = LegacyKeyMigration.builder()
            .key("old_rig", RIG, ComponentCodec.INT)
            .keys(List.of(LegacyKeyMigration.Key.of("old_owner"), LegacyKeyMigration.Key.of("old_issue")), OWNERSHIP,
                    v -> new Ownership(v.string("old_owner", ""), v.integer("old_issue", 0)))
            .mark("old_flag", FLAG)
            .key(OLD_DEEP, NAME, ComponentCodec.STRING)
            .drop("old_dead")
            .build();

    private final CarrierFactory factory;

    CarrierContractTests(CarrierFactory factory) {
        this.factory = factory;
    }

    void run(Check c) {
        t13Presence(c);
        t14WriteOutcomes(c);
        t15OneDecodePath(c);
        t16MigrationValues(c);
        t17MigrationStrips(c);
        t18MigrationIdempotent(c);
        t19MigrationQuarantine(c);
        t20MigrationUntouched(c);
    }

    private FencedCarrier carrier() {
        return factory.create(REGISTRY, MIGRATION);
    }

    // ---------------------------------------------------------------------------------------------------- roots

    private static TagNode.TagCompound compound(Object... keysAndValues) {
        TagNode.TagCompound out = TagNode.TagCompound.empty();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out = out.with((String) keysAndValues[i], (TagNode) keysAndValues[i + 1]);
        }
        return out;
    }

    private static TagNode.TagInt i(int v) {
        return TagNode.TagInt.of(v);
    }

    private static TagNode.TagString s(String v) {
        return TagNode.TagString.of(v);
    }

    /** A stack as the mod stored it before Rosetta: old keys, a retired key, and keys that are not the mod's. */
    private static TagNode.TagCompound oldRoot() {
        return compound(
                "Damage", i(3),
                "other_mod", compound("x", i(1)),
                "old_rig", i(7),
                "old_owner", s("abc"),
                "old_issue", TagNode.TagByte.of((byte) 3),
                "old_flag", TagNode.TagByte.of((byte) 1),
                "old", compound("deep", s("nm"), "other", i(9)),
                "old_dead", i(1),
                "old_rig_extra", s("not the table's"));
    }

    private static final TagNode GARBLED = TagNode.TagString.of("garbled");

    private static TagNode.TagCompound envelopeRoot() {
        return compound("Damage", i(3), FENCE, GARBLED);
    }

    private static final TagNode OOPS = TagNode.TagString.of("oops");

    private static TagNode.TagCompound corruptRoot() {
        return compound("Damage", i(3), FENCE, compound("test:count", OOPS, "test:rig", i(2)));
    }

    private static TagNode.TagCompound plainRoot() {
        return compound("Damage", i(3), "other_mod", compound("x", i(1)));
    }

    private static boolean contains(TagNode hay, TagNode needle) {
        if (hay.equals(needle)) {
            return true;
        }
        if (hay instanceof TagNode.TagCompound comp) {
            for (TagNode child : comp.entries().values()) {
                if (contains(child, needle)) return true;
            }
        } else if (hay instanceof TagNode.TagList list) {
            for (TagNode child : list.elements()) {
                if (contains(child, needle)) return true;
            }
        }
        return false;
    }

    private static boolean refused(WriteOutcome out, WriteOutcome.Reason reason, TagNode.TagCompound unchanged) {
        return out instanceof WriteOutcome.Refused r && r.reason() == reason && r.root().equals(unchanged);
    }

    private static Optional<WriteOutcome.Written> written(WriteOutcome out) {
        return out instanceof WriteOutcome.Written w ? Optional.of(w) : Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------------- T13

    /** T13 on a stack: a mark is carried and cleared like any component, and a cleared stack is a plain stack again. */
    private void t13Presence(Check c) {
        FencedCarrier carrier = carrier();
        TagNode.TagCompound plain = plainRoot();

        Optional<WriteOutcome.Written> marked = written(carrier.set(plain, FLAG, Unit.INSTANCE));
        c.that(marked.isPresent() && carrier.read(marked.get().root(), FLAG).isPresent(), "T13:presence-round-trip");
        c.that(marked.isPresent() && carrier.read(marked.get().root()).has(FLAG), "T13:presence-round-trip");
        c.that(marked.isPresent() && !carrier.read(marked.get().root(), RIG).isPresent(), "T13:absence-is-absence");

        // a value with no default survives as a stack value even when it is zero
        Optional<WriteOutcome.Written> zero = written(carrier.set(plain, RIG, 0));
        c.that(zero.isPresent() && carrier.read(zero.get().root(), RIG).value().equals(Optional.of(0)), "T13:no-default-written");

        // cleared, the stack is again exactly the plain one
        if (marked.isPresent()) {
            Optional<WriteOutcome.Written> cleared = written(carrier.clear(marked.get().root(), FLAG));
            c.that(cleared.isPresent() && cleared.get().root().equals(plain), "T13:absence-is-absence");
        } else {
            c.fail("T13:absence-is-absence");
        }
        // a default is still elided: setting it to its default writes nothing
        Optional<WriteOutcome.Written> dflt = written(carrier.set(plain, COUNT, 0));
        c.that(dflt.isPresent() && dflt.get().root().equals(plain), "T13:default-still-elided");
    }

    // ---------------------------------------------------------------------------------------------------- T14

    private void t14WriteOutcomes(Check c) {
        FencedCarrier carrier = carrier();

        // A write reports what it did: written, and whether the root changed
        TagNode.TagCompound plain = plainRoot();
        Optional<WriteOutcome.Written> first = written(carrier.set(plain, COUNT, 5));
        c.that(first.isPresent() && first.get().changed() && first.get().root().get("Damage") != null
                && first.get().root().has(FENCE), "T14:written-reports");
        Optional<WriteOutcome.Written> again = first.flatMap(w -> written(carrier.set(w.root(), COUNT, 5)));
        c.that(again.isPresent() && !again.get().changed() && again.get().root().equals(first.get().root()),
                "T14:written-reports");
        Optional<WriteOutcome.Written> nothing = written(carrier.clear(plain, COUNT));
        c.that(nothing.isPresent() && !nothing.get().changed() && nothing.get().root().equals(plain), "T14:written-reports");

        // An unreadable envelope: refused with its reason and the root untouched, never a silent no-op
        TagNode.TagCompound env = envelopeRoot();
        c.that(refused(carrier.set(env, COUNT, 5), WriteOutcome.Reason.ENVELOPE_QUARANTINED, env), "T14:refused-with-reason");
        c.that(refused(carrier.clear(env, COUNT), WriteOutcome.Reason.ENVELOPE_QUARANTINED, env), "T14:refused-with-reason");
        c.that(refused(carrier.write(env, CarrierSnapshot.empty().with(COUNT, 5)), WriteOutcome.Reason.ENVELOPE_QUARANTINED, env),
                "T14:refused-with-reason");
        c.that(refused(carrier.write(env, carrier.read(env).with(COUNT, 5)), WriteOutcome.Reason.ENVELOPE_QUARANTINED, env),
                "T14:refused-with-reason");
        // writing back what was read destroys nothing, so it is written (and changes nothing)
        Optional<WriteOutcome.Written> back = written(carrier.write(env, carrier.read(env)));
        c.that(back.isPresent() && !back.get().changed() && back.get().root().equals(env), "T14:written-reports");

        // KEEP applies the write and keeps the envelope; REPLACE applies it and may discard it
        Optional<WriteOutcome.Written> kept = written(carrier.set(env, COUNT, 5, OnQuarantine.KEEP));
        c.that(kept.isPresent() && contains(kept.get().root(), GARBLED), "T14:no-data-loss");
        c.that(kept.isPresent() && carrier.read(kept.get().root(), COUNT).value().equals(Optional.of(5))
                && kept.get().root().get("Damage") != null, "T14:no-data-loss");
        Optional<WriteOutcome.Written> replaced = written(carrier.set(env, COUNT, 5, OnQuarantine.REPLACE));
        c.that(replaced.isPresent() && carrier.read(replaced.get().root(), COUNT).value().equals(Optional.of(5))
                && !contains(replaced.get().root(), GARBLED), "T14:replace-discards");
        // the envelope is not lost by refusing either
        c.that(refused(carrier.set(env, COUNT, 5), WriteOutcome.Reason.ENVELOPE_QUARANTINED, env) && contains(env, GARBLED),
                "T14:no-data-loss");

        // A component entry that does not decode
        TagNode.TagCompound corrupt = corruptRoot();
        c.that(refused(carrier.set(corrupt, COUNT, 5), WriteOutcome.Reason.COMPONENT_QUARANTINED, corrupt), "T14:refused-with-reason");
        c.that(refused(carrier.clear(corrupt, COUNT), WriteOutcome.Reason.COMPONENT_QUARANTINED, corrupt), "T14:refused-with-reason");
        c.that(refused(carrier.write(corrupt, CarrierSnapshot.empty().with(COUNT, 5)), WriteOutcome.Reason.COMPONENT_QUARANTINED, corrupt),
                "T14:refused-with-reason");
        // a write elsewhere in the same fence is fine and keeps it
        Optional<WriteOutcome.Written> elsewhere = written(carrier.set(corrupt, RIG, 3));
        c.that(elsewhere.isPresent() && contains(elsewhere.get().root(), OOPS)
                && carrier.read(elsewhere.get().root(), RIG).value().equals(Optional.of(3)), "T14:no-data-loss");

        Optional<WriteOutcome.Written> keptSet = written(carrier.set(corrupt, COUNT, 5, OnQuarantine.KEEP));
        c.that(keptSet.isPresent() && contains(keptSet.get().root(), OOPS)
                && carrier.read(keptSet.get().root(), COUNT).value().equals(Optional.of(5)), "T14:no-data-loss");
        // evidence kept once stays kept through later ordinary writes
        Optional<WriteOutcome.Written> later = keptSet.flatMap(w -> written(carrier.set(w.root(), COUNT, 6)));
        c.that(later.isPresent() && contains(later.get().root(), OOPS)
                && carrier.read(later.get().root(), COUNT).value().equals(Optional.of(6)), "T14:no-data-loss");
        Optional<WriteOutcome.Written> keptClear = written(carrier.clear(corrupt, COUNT, OnQuarantine.KEEP));
        c.that(keptClear.isPresent() && contains(keptClear.get().root(), OOPS)
                && !carrier.read(keptClear.get().root(), COUNT).isPresent()
                && !carrier.read(keptClear.get().root(), COUNT).isQuarantined(), "T14:no-data-loss");
        Optional<WriteOutcome.Written> replacedSet = written(carrier.set(corrupt, COUNT, 5, OnQuarantine.REPLACE));
        c.that(replacedSet.isPresent() && !contains(replacedSet.get().root(), OOPS)
                && carrier.read(replacedSet.get().root(), COUNT).value().equals(Optional.of(5)), "T14:replace-discards");
        Optional<WriteOutcome.Written> replacedClear = written(carrier.clear(corrupt, COUNT, OnQuarantine.REPLACE));
        c.that(replacedClear.isPresent() && !contains(replacedClear.get().root(), OOPS)
                && carrier.read(replacedClear.get().root(), RIG).value().equals(Optional.of(2)), "T14:replace-discards");
    }

    // ---------------------------------------------------------------------------------------------------- T15

    private void t15OneDecodePath(Check c) {
        FencedCarrier carrier = carrier();
        TagNode.TagCompound future = compound(FENCE, compound("test:tool_state",
                compound("owner", s("a"), "issue", i(1), "zz_future", s("later"))));
        TagNode.TagCompound clean = compound(FENCE, compound("test:count", i(5), "test:rig", i(2), "test:flag", TagNode.TagCompound.empty()));
        TagNode.TagCompound removed = compound(FENCE, compound("!removed", TagNode.TagList.of(s("test:count"))));
        TagNode.TagCompound badOld = compound("old_rig", s("x"));
        TagNode.TagCompound stale = compound(FENCE, compound("test:rig", i(9)), "old_rig", i(7));
        List<TagNode.TagCompound> roots = List.of(clean, future, corruptRoot(), envelopeRoot(), oldRoot(), removed, badOld, stale,
                plainRoot());

        for (TagNode.TagCompound root : roots) {
            CarrierSnapshot snapshot = carrier.read(root);
            for (ComponentDefinition<?> def : REGISTRY.definitions()) {
                ComponentRead<?> one = carrier.read(root, def);
                boolean present = snapshot.has(def.id());
                c.that(one.isPresent() == present, "T15:same-decode-path");
                c.that(one.isRemoved() == snapshot.isRemoved(def.id()), "T15:same-decode-path");
                if (present && one.isPresent()) {
                    c.equal(snapshot.get(def.id()), one.value().orElse(null), "T15:same-decode-path");
                }
                if (!present && snapshot.unmappedTags().containsKey(def.id().toString())) {
                    c.that(one.isQuarantined(), "T15:same-decode-path");
                }
                if (one.isQuarantined()) {
                    c.that(!present && !snapshot.isRemoved(def.id()), "T15:same-decode-path");
                }
            }
        }

        // A component that is stored but unreadable is quarantined, with its raw evidence: never absent, never thrown
        ComponentRead<Integer> bad = carrier.read(corruptRoot(), COUNT);
        c.that(bad.isQuarantined() && bad instanceof ComponentRead.Quarantined<Integer> q && q.record().rawNode().equals(OOPS),
                "T15:quarantine-visible");
        ComponentRead<Integer> good = carrier.read(corruptRoot(), RIG);
        c.that(good.isPresent() && good.value().equals(Optional.of(2)), "T15:quarantine-visible");
        ComponentRead<Integer> inEnvelope = carrier.read(envelopeRoot(), COUNT);
        c.that(inEnvelope.isQuarantined() && inEnvelope instanceof ComponentRead.Quarantined<Integer> e
                && e.record().category() == RecoveryRecord.FailureCategory.UNREADABLE_ENVELOPE
                && e.record().rawNode().equals(GARBLED), "T15:quarantine-visible");
        c.that(!carrier.read(plainRoot(), COUNT).isQuarantined() && !carrier.read(plainRoot(), COUNT).isPresent(),
                "T15:quarantine-visible");
        ComponentRead<Integer> unreadableOld = carrier.read(badOld, RIG);
        c.that(unreadableOld.isQuarantined(), "T15:quarantine-visible");
    }

    // ---------------------------------------------------------------------------------------------------- T16

    private void t16MigrationValues(Check c) {
        FencedCarrier carrier = carrier();
        CarrierSnapshot snapshot = carrier.read(oldRoot());
        c.equal(7, snapshot.get(RIG), "T16:migration-values");
        c.equal(new Ownership("abc", 3), snapshot.get(OWNERSHIP), "T16:migration-values");
        c.that(snapshot.has(FLAG), "T16:migration-values");
        c.equal("nm", snapshot.get(NAME), "T16:migration-values");
        c.that(snapshot.unmappedTags().isEmpty() && snapshot.recoveryRecords().isEmpty(), "T16:migration-values");
        c.equal(Optional.of(7), carrier.read(oldRoot(), RIG).value(), "T16:migration-values");
        c.equal(Optional.of(new Ownership("abc", 3)), carrier.read(oldRoot(), OWNERSHIP).value(), "T16:migration-values");

        // a rule over several keys fills the missing ones as its reader says
        c.equal(new Ownership("", 4), carrier.read(compound("old_issue", i(4)), OWNERSHIP).value().orElse(null), "T16:migration-values");
        c.equal(new Ownership("x", 0), carrier.read(compound("old_owner", s("x")), OWNERSHIP).value().orElse(null), "T16:migration-values");
        // nothing old, nothing migrated
        c.that(carrier.read(plainRoot()).isEmpty(), "T16:migration-values");
    }

    // ---------------------------------------------------------------------------------------------------- T17

    private void t17MigrationStrips(Check c) {
        FencedCarrier carrier = carrier();
        TagNode.TagCompound old = oldRoot();
        Optional<WriteOutcome.Written> out = written(carrier.set(old, COUNT, 5));
        if (out.isEmpty()) {
            c.fail("T17:migration-strips");
            return;
        }
        TagNode.TagCompound root = out.get().root();
        for (String key : List.of("old_rig", "old_owner", "old_issue", "old_flag", "old_dead")) {
            c.that(!root.has(key), "T17:migration-strips");
        }
        c.that(!(root.get("old") instanceof TagNode.TagCompound o && o.has("deep")), "T17:migration-strips");
        CarrierSnapshot after = carrier.read(root);
        c.equal(7, after.get(RIG), "T17:migration-strips");
        c.equal(new Ownership("abc", 3), after.get(OWNERSHIP), "T17:migration-strips");
        c.equal("nm", after.get(NAME), "T17:migration-strips");
        c.equal(5, after.get(COUNT), "T17:migration-strips");

        // a plain write of the migrated snapshot strips them too
        Optional<WriteOutcome.Written> plainWrite = written(carrier.write(old, carrier.read(old)));
        c.that(plainWrite.isPresent() && !plainWrite.get().root().has("old_rig") && plainWrite.get().changed(), "T17:migration-strips");

        // clearing a migrated component clears it for good: the old key does not bring it back
        Optional<WriteOutcome.Written> cleared = written(carrier.clear(old, RIG));
        c.that(cleared.isPresent() && !cleared.get().root().has("old_rig")
                && !carrier.read(cleared.get().root(), RIG).isPresent(), "T17:migration-strips");
    }

    // ---------------------------------------------------------------------------------------------------- T18

    private void t18MigrationIdempotent(Check c) {
        FencedCarrier carrier = carrier();
        TagNode.TagCompound old = oldRoot();
        CarrierSnapshot first = carrier.read(old);
        Optional<WriteOutcome.Written> w1 = written(carrier.write(old, first));
        if (w1.isEmpty()) {
            c.fail("T18:migration-idempotent");
            return;
        }
        CarrierSnapshot second = carrier.read(w1.get().root());
        c.equal(first, second, "T18:migration-idempotent");
        Optional<WriteOutcome.Written> w2 = written(carrier.write(w1.get().root(), second));
        c.that(w2.isPresent() && !w2.get().changed() && w2.get().root().equals(w1.get().root()), "T18:migration-idempotent");

        // the fenced state is authoritative: a stale old key does not pull a stack back
        TagNode.TagCompound stale = compound(FENCE, compound("test:rig", i(9)), "old_rig", i(7));
        c.equal(9, carrier.read(stale).get(RIG), "T18:migration-idempotent");
        c.equal(Optional.of(9), carrier.read(stale, RIG).value(), "T18:migration-idempotent");
        Optional<WriteOutcome.Written> staleOut = written(carrier.write(stale, carrier.read(stale)));
        c.that(staleOut.isPresent() && !staleOut.get().root().has("old_rig")
                && carrier.read(staleOut.get().root()).get(RIG).equals(9), "T18:migration-idempotent");

        // an explicit removal is authoritative too
        TagNode.TagCompound removed = compound(FENCE, compound("!removed", TagNode.TagList.of(s("test:rig"))), "old_rig", i(7));
        c.that(carrier.read(removed, RIG).isRemoved(), "T18:migration-idempotent");
    }

    // ---------------------------------------------------------------------------------------------------- T19

    private void t19MigrationQuarantine(Check c) {
        FencedCarrier carrier = carrier();
        TagNode.TagCompound bad = compound("Damage", i(3), "old_rig", s("x"), "other_mod", compound("x", i(1)));
        CarrierSnapshot snapshot = carrier.read(bad);
        c.that(!snapshot.has(RIG) && carrier.read(bad, RIG).isQuarantined()
                && !snapshot.recoveryRecords().isEmpty(), "T19:migration-quarantine");

        Optional<WriteOutcome.Written> out = written(carrier.set(bad, COUNT, 1));
        c.that(out.isPresent() && !out.get().root().has("old_rig") && contains(out.get().root().get(FENCE), s("x")),
                "T19:migration-quarantine");
        if (out.isPresent()) {
            TagNode.TagCompound root = out.get().root();
            c.that(carrier.read(root, RIG).isQuarantined() && !carrier.read(root, RIG).isPresent(), "T19:migration-quarantine");
            c.equal(snapshot.unmappedTags(), carrier.read(root).unmappedTags(), "T19:migration-quarantine");
            Optional<WriteOutcome.Written> twice = written(carrier.write(root, carrier.read(root)));
            c.that(twice.isPresent() && !twice.get().changed(), "T19:migration-quarantine");
        }

        // a rule over several keys: one unreadable and the whole rule is quarantined, each key kept
        TagNode.TagCompound half = compound("old_owner", s("a"), "old_issue", s("bad"));
        CarrierSnapshot halfRead = carrier.read(half);
        c.that(!halfRead.has(OWNERSHIP) && carrier.read(half, OWNERSHIP).isQuarantined(), "T19:migration-quarantine");
        Optional<WriteOutcome.Written> halfOut = written(carrier.set(half, COUNT, 1));
        c.that(halfOut.isPresent() && contains(halfOut.get().root(), s("a")) && contains(halfOut.get().root(), s("bad")),
                "T19:migration-quarantine");

        // old keys the write does not account for (a snapshot not read from this root) are kept, not stripped
        TagNode.TagCompound old = oldRoot();
        Optional<WriteOutcome.Written> foreign = written(carrier.write(old, CarrierSnapshot.empty().with(COUNT, 1)));
        c.that(foreign.isPresent() && foreign.get().root().get("old_rig") != null
                && foreign.get().root().get("old_owner") != null, "T19:migration-quarantine");

        // the fenced entry is stored but unreadable: the old key may hold the only good copy, so it stays
        TagNode.TagCompound both = compound(FENCE, compound("test:rig", OOPS), "old_rig", i(7));
        Optional<WriteOutcome.Written> kept = written(carrier.set(both, COUNT, 1));
        c.that(kept.isPresent() && kept.get().root().get("old_rig") != null && contains(kept.get().root(), OOPS),
                "T19:migration-quarantine");
    }

    // ---------------------------------------------------------------------------------------------------- T20

    private void t20MigrationUntouched(Check c) {
        FencedCarrier carrier = carrier();
        TagNode.TagCompound old = oldRoot();
        Optional<WriteOutcome.Written> out = written(carrier.set(old, COUNT, 5));
        if (out.isEmpty()) {
            c.fail("T20:migration-untouched");
            return;
        }
        TagNode.TagCompound root = out.get().root();
        for (String key : List.of("Damage", "other_mod", "old_rig_extra")) {
            c.equal(old.get(key), root.get(key), "T20:migration-untouched");
        }
        // inside the old compound only the named key went
        c.that(root.get("old") instanceof TagNode.TagCompound o && o.size() == 1 && i(9).equals(o.get("other")), "T20:migration-untouched");

        // a root with nothing the table names changes only by the fence
        TagNode.TagCompound plain = plainRoot();
        Optional<WriteOutcome.Written> plainOut = written(carrier.set(plain, COUNT, 5));
        c.that(plainOut.isPresent() && plainOut.get().root().without(FENCE).equals(plain), "T20:migration-untouched");
        c.that(plainOut.isPresent() && plainOut.get().root().get(FENCE) instanceof TagNode.TagCompound f && f.size() == 1,
                "T20:migration-untouched");

        // an old compound that held only the named key goes with it; one that held more stays
        Map<String, TagNode> deepOnly = Map.of("old", compound("deep", s("nm")));
        Optional<WriteOutcome.Written> deepOut = written(carrier.write(new TagNode.TagCompound(deepOnly), carrier.read(new TagNode.TagCompound(deepOnly))));
        c.that(deepOut.isPresent() && !deepOut.get().root().has("old") && deepOut.get().root().has(FENCE), "T20:migration-untouched");
    }
}
