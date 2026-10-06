package texsuite;

import java.nio.charset.StandardCharsets;

final class SourceExcerpt {
    private SourceExcerpt() { }

    static String marked(byte[] bytes, int start, int end) {
        int left = start;
        int right = end;
        while (left > 0 && start - left < 80 && bytes[left - 1] != '\n'
                && bytes[left - 1] != '\r') {
            left--;
        }
        while (right < bytes.length && right - end < 80 && bytes[right] != '\n'
                && bytes[right] != '\r') {
            right++;
        }
        while (left < start && (bytes[left] & 0xc0) == 0x80) left++;
        while (right < bytes.length && right > end && (bytes[right] & 0xc0) == 0x80) right--;
        String prefix = left > 0 && bytes[left - 1] != '\n' && bytes[left - 1] != '\r'
                ? "…" : "";
        String suffix = right < bytes.length && bytes[right] != '\n' && bytes[right] != '\r'
                ? "…" : "";
        return prefix + DocumentInput.safeDisplay(new String(bytes, left, start - left,
                StandardCharsets.UTF_8)) + "⟦"
                + DocumentInput.safeDisplay(new String(bytes, start, end - start,
                        StandardCharsets.UTF_8)) + "⟧"
                + DocumentInput.safeDisplay(new String(bytes, end, right - end,
                        StandardCharsets.UTF_8)) + suffix;
    }
}
