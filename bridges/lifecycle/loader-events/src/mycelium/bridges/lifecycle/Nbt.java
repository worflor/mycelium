package mycelium.bridges.lifecycle;

import mycelium.item_component.v1.model.LogicalNbtBridge;
import mycelium.item_component.v1.model.TagNode;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mojang's {@link Tag} to Rosetta's {@link TagNode} and back (the same conversion as {@code item-carrier}'s, kept here so each bridge stands alone): the one place the game's tag API shows through. How a tag is
 * read (a number's width, a string's text, a compound's keys) changed inside this variant's range, so the accessors at the
 * foot are what an overlay replaces; the conversion above them is the same on every version.
 *
 * <p>This variant's accessors are the ones from 1.20.5 to 1.21.4 ({@code getAsLong}, {@code getAsString},
 * {@code getAllKeys}).
 */
public final class Nbt {
    private Nbt() {
    }

    public static TagNode.TagCompound compound(CompoundTag tag) {
        return (TagNode.TagCompound) node(tag);
    }

    public static CompoundTag compoundTag(TagNode.TagCompound node) {
        return (CompoundTag) tag(node);
    }

    public static TagNode node(Tag t) {
        switch (t.getId()) {
            case 1:
                return new TagNode.TagByte((byte) integral(t));
            case 2:
                return new TagNode.TagShort((short) integral(t));
            case 3:
                return new TagNode.TagInt((int) integral(t));
            case 4:
                return new TagNode.TagLong(integral(t));
            case 5:
                return new TagNode.TagFloat((float) floating(t));
            case 6:
                return new TagNode.TagDouble(floating(t));
            case 7:
                return new TagNode.TagByteArray(((ByteArrayTag) t).getAsByteArray());
            case 8:
                return new TagNode.TagString(text(t));
            case 9: {
                List<TagNode> elements = new ArrayList<>();
                for (Tag e : (ListTag) t) {
                    elements.add(node(e));
                }
                return LogicalNbtBridge.toPhysicalList(elements);
            }
            case 10: {
                CompoundTag c = (CompoundTag) t;
                Map<String, TagNode> entries = new TreeMap<>();
                for (String key : keys(c)) {
                    entries.put(key, node(c.get(key)));
                }
                return new TagNode.TagCompound(entries);
            }
            case 11:
                return new TagNode.TagIntArray(((IntArrayTag) t).getAsIntArray());
            case 12:
                return new TagNode.TagLongArray(((LongArrayTag) t).getAsLongArray());
            default:
                throw new IllegalArgumentException("unreadable tag type " + t.getId());
        }
    }

    public static Tag tag(TagNode n) {
        if (n instanceof TagNode.TagByte b) {
            return ByteTag.valueOf(b.value());
        } else if (n instanceof TagNode.TagShort s) {
            return ShortTag.valueOf(s.value());
        } else if (n instanceof TagNode.TagInt i) {
            return IntTag.valueOf(i.value());
        } else if (n instanceof TagNode.TagLong l) {
            return LongTag.valueOf(l.value());
        } else if (n instanceof TagNode.TagFloat f) {
            return FloatTag.valueOf(f.value());
        } else if (n instanceof TagNode.TagDouble d) {
            return DoubleTag.valueOf(d.value());
        } else if (n instanceof TagNode.TagString s) {
            return StringTag.valueOf(s.value());
        } else if (n instanceof TagNode.TagByteArray a) {
            return new ByteArrayTag(a.value());
        } else if (n instanceof TagNode.TagIntArray a) {
            return new IntArrayTag(a.value());
        } else if (n instanceof TagNode.TagLongArray a) {
            return new LongArrayTag(a.value());
        } else if (n instanceof TagNode.TagList list) {
            ListTag out = new ListTag();
            for (TagNode e : LogicalNbtBridge.toLogicalList(list)) {
                out.add(tag(e));
            }
            return out;
        }
        CompoundTag out = new CompoundTag();
        for (Map.Entry<String, TagNode> e : ((TagNode.TagCompound) n).entries().entrySet()) {
            out.put(e.getKey(), tag(e.getValue()));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------- the accessors

    private static Set<String> keys(CompoundTag c) {
        return c.getAllKeys();
    }

    private static long integral(Tag t) {
        return ((NumericTag) t).getAsLong();
    }

    private static double floating(Tag t) {
        return ((NumericTag) t).getAsDouble();
    }

    private static String text(Tag t) {
        return t.getAsString();
    }
}
