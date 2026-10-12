package mycelium.state_lifecycle.v1.harness;

import mycelium.state_lifecycle.v1.model.SlotCodec;
import mycelium.state_lifecycle.v1.model.SlotCodecException;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Two codecs for the harness's durable slots. Stored forms are strings, which are immutable and have value equality. */
final class Codecs {
    private Codecs() {
    }

    /** Any string, framed so that a stored form that is not one is refused. */
    static final SlotCodec<String> STRING = new SlotCodec<>() {
        @Override
        public Object encode(String value) {
            return "s:" + value;
        }

        @Override
        public String decode(Object stored) throws SlotCodecException {
            if (!(stored instanceof String text) || !text.startsWith("s:")) {
                throw new SlotCodecException("not a framed string: " + stored);
            }
            return text.substring(2);
        }

        @Override
        public boolean same(String a, String b) {
            return a.equals(b);
        }
    };

    /** A string-to-count map, written as sorted {@code key=count} pairs. Keys may not contain '=' or ';'. */
    static final SlotCodec<Map<String, Integer>> COUNTS = new SlotCodec<>() {
        @Override
        public Object encode(Map<String, Integer> value) {
            StringBuilder b = new StringBuilder("m:");
            for (Map.Entry<String, Integer> e : new TreeMap<>(value).entrySet()) {
                b.append(e.getKey()).append('=').append(e.getValue()).append(';');
            }
            return b.toString();
        }

        @Override
        public Map<String, Integer> decode(Object stored) throws SlotCodecException {
            if (!(stored instanceof String text) || !text.startsWith("m:")) {
                throw new SlotCodecException("not a framed count map: " + stored);
            }
            Map<String, Integer> out = new TreeMap<>();
            String body = text.substring(2);
            if (!body.isEmpty()) {
                for (String pair : body.split(";")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        throw new SlotCodecException("bad pair: " + pair);
                    }
                    try {
                        out.put(pair.substring(0, eq), Integer.parseInt(pair.substring(eq + 1)));
                    } catch (NumberFormatException e) {
                        throw new SlotCodecException("bad count: " + pair);
                    }
                }
            }
            return Collections.unmodifiableMap(out);
        }

        @Override
        public boolean same(Map<String, Integer> a, Map<String, Integer> b) {
            return a.equals(b);
        }
    };
}
