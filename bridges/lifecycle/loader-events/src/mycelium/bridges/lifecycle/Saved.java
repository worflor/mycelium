package mycelium.bridges.lifecycle;

import mycelium.item_component.v1.model.TagNode;
import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostKind;
import mycelium.state_lifecycle.v1.model.SlotId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the world keeps for the lifecycle bridge, as the pure value it is: the stored form of every durable slot of every host,
 * and the stored forms a load could not decode, kept apart so that nothing is lost. It knows nothing of the game; the game's saved
 * data ({@link LifecycleData}) holds one and turns it into a tag and back, which is the only thing that changes with the version.
 *
 * <p>The stored form of a slot is whatever its codec made; for a slot made from an {@code item.component@1} codec that is a
 * {@link TagNode}, the one form the bridge saves (a codec of another form is refused when the bridge is made, naming the slot).
 * Hosts are written without their world: the save is the file they are in.
 */
final class Saved {
    /** The save the hosts are named in. A host id carries one; the file the forms are in is already the save. */
    static final String WORLD = "save";

    private static final int FORMAT = 1;

    private final Map<HostId, Map<SlotId, TagNode>> forms = new TreeMap<>();
    private final Map<HostId, Map<SlotId, TagNode>> quarantined = new TreeMap<>();

    Optional<TagNode> form(HostId host, SlotId slot) {
        Map<SlotId, TagNode> m = forms.get(host);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(slot));
    }

    void put(HostId host, SlotId slot, TagNode form) {
        forms.computeIfAbsent(host, k -> new TreeMap<>()).put(slot, form);
    }

    void remove(HostId host, SlotId slot) {
        Map<SlotId, TagNode> m = forms.get(host);
        if (m != null) {
            m.remove(slot);
            if (m.isEmpty()) {
                forms.remove(host);
            }
        }
    }

    Set<HostId> hostsWith(SlotId slot) {
        Set<HostId> out = new TreeSet<>();
        for (Map.Entry<HostId, Map<SlotId, TagNode>> e : forms.entrySet()) {
            if (e.getValue().containsKey(slot)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Moves a form that does not decode out of the way, kept, so the host can come back without it and nothing is lost. */
    void quarantine(HostId host, SlotId slot) {
        Map<SlotId, TagNode> m = forms.get(host);
        if (m != null && m.containsKey(slot)) {
            quarantined.computeIfAbsent(host, k -> new TreeMap<>()).put(slot, m.get(slot));
            remove(host, slot);
        }
    }

    Optional<TagNode> quarantinedForm(HostId host, SlotId slot) {
        Map<SlotId, TagNode> m = quarantined.get(host);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(slot));
    }

    int quarantinedCount() {
        int n = 0;
        for (Map<SlotId, TagNode> m : quarantined.values()) {
            n += m.size();
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------- as a tag

    TagNode.TagCompound toTag() {
        Map<String, TagNode> root = new TreeMap<>();
        root.put("format", TagNode.TagInt.of(FORMAT));
        root.put("hosts", hosts(forms));
        if (!quarantined.isEmpty()) {
            root.put("quarantine", hosts(quarantined));
        }
        return new TagNode.TagCompound(root);
    }

    private static TagNode hosts(Map<HostId, Map<SlotId, TagNode>> in) {
        List<TagNode> list = new ArrayList<>();
        for (Map.Entry<HostId, Map<SlotId, TagNode>> e : in.entrySet()) {
            Map<String, TagNode> slots = new TreeMap<>();
            for (Map.Entry<SlotId, TagNode> s : e.getValue().entrySet()) {
                slots.put(s.getKey().value(), s.getValue());
            }
            Map<String, TagNode> host = new TreeMap<>();
            host.put("kind", TagNode.TagString.of(e.getKey().kind().name().toLowerCase(Locale.ROOT)));
            host.put("id", TagNode.TagString.of(e.getKey().id()));
            host.put("slots", new TagNode.TagCompound(slots));
            list.add(new TagNode.TagCompound(host));
        }
        return TagNode.TagList.of(list);
    }

    /** Reads what {@link #toTag} wrote. A host or slot it cannot read is skipped, never the whole save. */
    static Saved fromTag(TagNode.TagCompound tag) {
        Saved out = new Saved();
        read(tag.getList("hosts"), out.forms);
        read(tag.getList("quarantine"), out.quarantined);
        return out;
    }

    private static void read(Optional<TagNode.TagList> list, Map<HostId, Map<SlotId, TagNode>> into) {
        if (list.isEmpty()) {
            return;
        }
        for (TagNode n : list.get().elements()) {
            if (!(n instanceof TagNode.TagCompound host)) {
                continue;
            }
            Optional<String> kind = host.getString("kind");
            Optional<String> id = host.getString("id");
            Optional<TagNode.TagCompound> slots = host.getCompound("slots");
            if (kind.isEmpty() || id.isEmpty() || id.get().isBlank() || slots.isEmpty()) {
                continue;
            }
            HostKind k;
            try {
                k = HostKind.valueOf(kind.get().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Map<SlotId, TagNode> m = new TreeMap<>();
            for (Map.Entry<String, TagNode> s : slots.get().entries().entrySet()) {
                try {
                    m.put(new SlotId(s.getKey()), s.getValue());
                } catch (IllegalArgumentException e) {
                    // a key that is no slot id: not ours to keep
                }
            }
            if (!m.isEmpty()) {
                into.computeIfAbsent(new HostId(k, WORLD, id.get()), x -> new TreeMap<>()).putAll(m);
            }
        }
    }

    /** A copy that shares nothing with this one (the forms are immutable values). */
    Saved copy() {
        Saved out = new Saved();
        for (Map.Entry<HostId, Map<SlotId, TagNode>> e : forms.entrySet()) {
            out.forms.put(e.getKey(), new TreeMap<>(e.getValue()));
        }
        for (Map.Entry<HostId, Map<SlotId, TagNode>> e : quarantined.entrySet()) {
            out.quarantined.put(e.getKey(), new TreeMap<>(e.getValue()));
        }
        return out;
    }

    Map<HostId, Map<SlotId, TagNode>> formsView() {
        return Collections.unmodifiableMap(forms);
    }
}
