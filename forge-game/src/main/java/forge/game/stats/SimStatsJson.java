package forge.game.stats;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

public final class SimStatsJson {
    private SimStatsJson() {
    }

    public static String toJson(final Object value) {
        final StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(final StringBuilder out, final Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String s) {
            appendString(out, s);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendMap(out, map);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            appendIterable(out, iterable);
            return;
        }
        if (value.getClass().isArray()) {
            appendArray(out, value);
            return;
        }
        appendString(out, value.toString());
    }

    private static void appendMap(final StringBuilder out, final Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, String.valueOf(entry.getKey()));
            out.append(':');
            append(out, entry.getValue());
        }
        out.append('}');
    }

    private static void appendIterable(final StringBuilder out, final Iterable<?> iterable) {
        out.append('[');
        final Iterator<?> iterator = iterable.iterator();
        boolean first = true;
        while (iterator.hasNext()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            append(out, iterator.next());
        }
        out.append(']');
    }

    private static void appendArray(final StringBuilder out, final Object array) {
        out.append('[');
        final int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(',');
            }
            append(out, Array.get(array, i));
        }
        out.append(']');
    }

    private static void appendString(final StringBuilder out, final String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
            case '"':
                out.append("\\\"");
                break;
            case '\\':
                out.append("\\\\");
                break;
            case '\b':
                out.append("\\b");
                break;
            case '\f':
                out.append("\\f");
                break;
            case '\n':
                out.append("\\n");
                break;
            case '\r':
                out.append("\\r");
                break;
            case '\t':
                out.append("\\t");
                break;
            default:
                if (c < 0x20) {
                    out.append(String.format("\\u%04x", (int)c));
                } else {
                    out.append(c);
                }
                break;
            }
        }
        out.append('"');
    }
}
