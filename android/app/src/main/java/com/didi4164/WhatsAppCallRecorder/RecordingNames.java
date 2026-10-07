package com.didi4164.WhatsAppCallRecorder;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Bounded names for metadata and exports. Recording IDs and private paths never depend on names. */
public final class RecordingNames {
    public static final int MAX_DISPLAY_CODE_POINTS = 80, MAX_DISPLAY_UTF8_BYTES = 160;
    public static final int MAX_EXPORT_UTF8_BYTES = 220;
    private RecordingNames() {}

    public static boolean validRecordingId(String id) {
        return id != null && id.length() <= 28 && id.matches("[0-9]{1,19}-[a-f0-9]{8}");
    }

    public static boolean validCallPackage(String value) {
        return "com.whatsapp".equals(value) || "com.whatsapp.w4b".equals(value);
    }

    private static boolean forbidden(int point) {
        int kind = Character.getType(point);
        return Character.isISOControl(point) || kind == Character.SURROGATE
                || (kind == Character.FORMAT && point != 0x200d)
                || "/\\\"<>:|?*".indexOf(point) >= 0;
    }

    /** NFC Unicode, without path/header/control/bidi characters. Keep valid emoji joiners. */
    public static String sanitizeDisplayName(String raw) {
        if (raw == null || raw.length() > 2048) return null;
        String input = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder();
        boolean space = false, meaningful = false;
        int points = 0, bytes = 0;
        for (int offset = 0; offset < input.length();) {
            int point = input.codePointAt(offset); offset += Character.charCount(point);
            if (forbidden(point) || Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                if (result.length() > 0) space = true;
                continue;
            }
            String fragment = new String(Character.toChars(point));
            int fragmentBytes = fragment.getBytes(StandardCharsets.UTF_8).length;
            int extraSpace = space ? 1 : 0;
            if (points + 1 + extraSpace > MAX_DISPLAY_CODE_POINTS || bytes + fragmentBytes + extraSpace > MAX_DISPLAY_UTF8_BYTES) break;
            if (space) { result.append(' '); points++; bytes++; space = false; }
            result.append(fragment); points++; bytes += fragmentBytes;
            meaningful |= Character.isLetterOrDigit(point) || Character.getType(point) == Character.OTHER_SYMBOL;
        }
        String name = result.toString().trim();
        // Dot-only, separators and combining marks cannot provide a useful call identity.
        return meaningful && !name.isEmpty() ? name : null;
    }

    /** The full stable ID is retained even after truncating a human name. */
    public static String exportFileName(String id, String displayName) {
        if (!validRecordingId(id)) throw new IllegalArgumentException("recording identity");
        String name = sanitizeDisplayName(displayName);
        return name == null ? id + ".wav" : "WA-reco-" + name + "-" + id + ".wav";
    }

    /** Only accept a canonical filename tied to this recording, never a supplied path. */
    public static boolean validExportFileName(String id, String value) {
        if (!validRecordingId(id) || value == null || value.length() > MAX_EXPORT_UTF8_BYTES
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_EXPORT_UTF8_BYTES) return false;
        if (value.equals(id + ".wav")) return true;
        String suffix = "-" + id + ".wav";
        if (!value.startsWith("WA-reco-") || !value.endsWith(suffix)) return false;
        String name = value.substring("WA-reco-".length(), value.length() - suffix.length());
        return value.equals(exportFileName(id, name));
    }

    /** Preserve the legacy remote naming when no additive filename was frozen in its job. */
    public static String driveFileName(String id, String frozenName) {
        if (!validRecordingId(id)) throw new IllegalArgumentException("recording identity");
        if (frozenName == null) return "recording-" + id + ".wav";
        if (!validExportFileName(id, frozenName)) throw new IllegalArgumentException("recording filename");
        return frozenName;
    }
}
