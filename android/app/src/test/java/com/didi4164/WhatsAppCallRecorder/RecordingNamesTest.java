package com.didi4164.WhatsAppCallRecorder;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingNamesTest {
    private static final String ID = "1791280000000-abcdef12";
    @Test public void hebrewEnglishAndEmojiRetainTheirVisibleName() {
        assertEquals("דוד Smith 👩‍💻", RecordingNames.sanitizeDisplayName("  דוד\u00a0 Smith 👩‍💻  "));
        assertEquals("Zoë", RecordingNames.sanitizeDisplayName("Zoe\u0308"));
        assertEquals("WA-reco-דוד Smith 👩‍💻-" + ID + ".wav", RecordingNames.exportFileName(ID, "דוד Smith 👩‍💻"));
    }
    @Test public void unsafePathHeaderAndBidirectionalCharactersCannotEscape() {
        String name = RecordingNames.sanitizeDisplayName("David/Smith\\Team\r\n\"<x>:|?*\u202e\u2066 Z");
        assertEquals("David Smith Team x Z", name);
        String file = RecordingNames.exportFileName(ID, name);
        assertTrue(RecordingNames.validExportFileName(ID, file));
        for (String bad : new String[]{"../" + file, file + "\n", file.replace(ID, "1-deadbeef"), "WA-reco-\u202eDavid-" + ID + ".wav"})
            assertFalse(RecordingNames.validExportFileName(ID, bad));
        assertFalse(RecordingNames.validExportFileName(ID, "x".repeat(100000)));
        assertFalse(RecordingNames.validRecordingId("1".repeat(100000) + "-abcdef12"));
    }
    @Test public void blanksControlsCombiningMarksAndMalformedUnicodeFallBack() {
        for (String raw : new String[]{null, "", " \t\n", "../\\\"", "\u202e\u2069", "\ud800", "\udfff", "\u0300\u0301", "---", "x".repeat(2049)}) {
            assertNull(RecordingNames.sanitizeDisplayName(raw));
            assertEquals(ID + ".wav", RecordingNames.exportFileName(ID, raw));
        }
    }
    @Test public void multiByteNamesAreBoundedWithoutSplittingSurrogatePairs() {
        for (String raw : new String[]{"A".repeat(1000), "ד".repeat(1000), "😀".repeat(1000), "東京".repeat(1000)}) {
            String name = RecordingNames.sanitizeDisplayName(raw);
            assertNotNull(name);
            assertTrue(name.codePointCount(0, name.length()) <= RecordingNames.MAX_DISPLAY_CODE_POINTS);
            assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= RecordingNames.MAX_DISPLAY_UTF8_BYTES);
            assertFalse(Character.isHighSurrogate(name.charAt(name.length() - 1)));
            String file = RecordingNames.exportFileName(ID, name);
            assertTrue(file.endsWith("-" + ID + ".wav"));
            assertTrue(file.getBytes(StandardCharsets.UTF_8).length <= RecordingNames.MAX_EXPORT_UTF8_BYTES);
        }
    }
    @Test public void sameNameKeepsDistinctStableRecordingIds() {
        assertNotEquals(RecordingNames.exportFileName(ID, "Alex"), RecordingNames.exportFileName("1791280000000-1234abcd", "Alex"));
    }
    @Test public void onlyWhatsappPackagesCanIdentifyAWhatsappCall() {
        assertTrue(RecordingNames.validCallPackage("com.whatsapp"));
        assertTrue(RecordingNames.validCallPackage("com.whatsapp.w4b"));
        assertFalse(RecordingNames.validCallPackage(null));
        assertFalse(RecordingNames.validCallPackage("com.whatsapp.fake"));
    }
    @Test public void driveLegacyDefaultAndExplicitNameRemainDifferent() {
        assertEquals("recording-" + ID + ".wav", RecordingNames.driveFileName(ID, null));
        assertEquals(ID + ".wav", RecordingNames.driveFileName(ID, ID + ".wav"));
        assertEquals(RecordingNames.exportFileName(ID, "Alex"), RecordingNames.driveFileName(ID, RecordingNames.exportFileName(ID, "Alex")));
    }
    @Test(expected = IllegalArgumentException.class) public void remoteNameCannotLoseStableId() {
        RecordingNames.driveFileName(ID, "Alex.wav");
    }
}
