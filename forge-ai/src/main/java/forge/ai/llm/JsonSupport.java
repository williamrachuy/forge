package forge.ai.llm;

final class JsonSupport {
    private JsonSupport() {
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }

        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
            case '"':
                sb.append("\\\"");
                break;
            case '\\':
                sb.append("\\\\");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            default:
                if (ch < 0x20) {
                    sb.append(String.format("\\u%04x", (int) ch));
                } else {
                    sb.append(ch);
                }
                break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String unquoteAt(String json, int quoteIndex) {
        if (quoteIndex < 0 || quoteIndex >= json.length() || json.charAt(quoteIndex) != '"') {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = quoteIndex + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {
                return sb.toString();
            }
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }
            if (++i >= json.length()) {
                return null;
            }
            char escaped = json.charAt(i);
            switch (escaped) {
            case '"':
            case '\\':
            case '/':
                sb.append(escaped);
                break;
            case 'b':
                sb.append('\b');
                break;
            case 'f':
                sb.append('\f');
                break;
            case 'n':
                sb.append('\n');
                break;
            case 'r':
                sb.append('\r');
                break;
            case 't':
                sb.append('\t');
                break;
            case 'u':
                if (i + 4 >= json.length()) {
                    return null;
                }
                try {
                    sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                } catch (NumberFormatException ex) {
                    return null;
                }
                i += 4;
                break;
            default:
                return null;
            }
        }
        return null;
    }

    static String extractFirstMessageContent(String responseJson) {
        return extractFirstMessageStringField(responseJson, "content");
    }

    static String extractFirstMessageReasoningContent(String responseJson) {
        return extractFirstMessageStringField(responseJson, "reasoning_content");
    }

    static String extractFirstChoiceFinishReason(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return "";
        }
        return extractStringField(responseJson, "finish_reason", responseJson.indexOf("\"choices\""));
    }

    static String extractUsageSummary(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return "";
        }
        int usageIndex = responseJson.indexOf("\"usage\"");
        if (usageIndex < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendIntFieldSummary(sb, responseJson, "prompt_tokens");
        appendIntFieldSummary(sb, responseJson, "completion_tokens");
        appendIntFieldSummary(sb, responseJson, "reasoning_tokens");
        appendIntFieldSummary(sb, responseJson, "total_tokens");
        appendIntFieldSummary(sb, responseJson, "prompt_cache_hit_tokens");
        appendIntFieldSummary(sb, responseJson, "prompt_cache_miss_tokens");
        return sb.toString();
    }

    private static String extractFirstMessageStringField(String responseJson, String fieldName) {
        int messageIndex = responseJson.indexOf("\"message\"");
        if (messageIndex < 0) {
            return null;
        }
        return extractStringField(responseJson, fieldName, messageIndex);
    }

    private static String extractStringField(String responseJson, String fieldName, int startIndex) {
        if (responseJson == null || startIndex < 0) {
            return null;
        }

        int fieldIndex = responseJson.indexOf(quote(fieldName), startIndex);
        if (fieldIndex < 0) {
            return null;
        }

        int colonIndex = responseJson.indexOf(':', fieldIndex);
        if (colonIndex < 0) {
            return null;
        }

        int valueIndex = colonIndex + 1;
        while (valueIndex < responseJson.length() && Character.isWhitespace(responseJson.charAt(valueIndex))) {
            valueIndex++;
        }

        if (valueIndex >= responseJson.length() || responseJson.charAt(valueIndex) != '"') {
            return null;
        }
        return unquoteAt(responseJson, valueIndex);
    }

    private static void appendIntFieldSummary(StringBuilder sb, String json, String fieldName) {
        Integer value = extractIntField(json, fieldName);
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(fieldName).append('=').append(value);
    }

    private static Integer extractIntField(String json, String fieldName) {
        String needle = quote(fieldName);
        int fieldIndex = json.indexOf(needle);
        if (fieldIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', fieldIndex + needle.length());
        if (colonIndex < 0) {
            return null;
        }

        int valueIndex = colonIndex + 1;
        while (valueIndex < json.length() && Character.isWhitespace(json.charAt(valueIndex))) {
            valueIndex++;
        }

        int endIndex = valueIndex;
        while (endIndex < json.length() && Character.isDigit(json.charAt(endIndex))) {
            endIndex++;
        }
        if (endIndex == valueIndex) {
            return null;
        }

        try {
            return Integer.parseInt(json.substring(valueIndex, endIndex));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
