package com.didi4164.WhatsAppCallRecorder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Lossless, frame-aligned PCM WAV parts; reads finalized app WAVs without modifying them. */
public final class TelegramWavParts {
    public static final long MAX_PART_BYTES = 48_000_000L;
    public static final int HEADER_BYTES = 44;
    private TelegramWavParts() {}
    public static final class Part {
        public final long offset, dataBytes, bytes;
        Part(long offset, long dataBytes) { this.offset = offset; this.dataBytes = dataBytes; this.bytes = dataBytes + HEADER_BYTES; }
    }
    public static final class Plan {
        public final long dataBytes, dataPerPart;
        public final int channels, sampleRate, parts;
        Plan(long dataBytes, int channels, int sampleRate, long dataPerPart) {
            this.dataBytes = dataBytes; this.channels = channels; this.sampleRate = sampleRate; this.dataPerPart = dataPerPart;
            this.parts = (int)((dataBytes + dataPerPart - 1) / dataPerPart);
        }
        public Part part(int index) {
            if (index < 0 || index >= parts) throw new IllegalArgumentException("part index");
            long start = dataPerPart * index;
            return new Part(HEADER_BYTES + start, Math.min(dataPerPart, dataBytes - start));
        }
    }
    private static long unsigned(int value) { return value & 0xffffffffL; }
    private static boolean literal(byte[] header, int offset, String value) {
        return Arrays.equals(Arrays.copyOfRange(header, offset, offset + value.length()), value.getBytes(StandardCharsets.US_ASCII));
    }
    public static Plan plan(byte[] header, long fileBytes, long maxPartBytes) throws TelegramBackupFailure {
        if (header == null || header.length != HEADER_BYTES || fileBytes <= HEADER_BYTES || fileBytes > 0xffffffffL + 8
                || maxPartBytes < 48 || maxPartBytes > MAX_PART_BYTES) throw new TelegramBackupFailure("UNSUPPORTED_WAV");
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int channels = b.getShort(22) & 65535;
        int rate = b.getInt(24);
        long dataBytes = unsigned(b.getInt(40));
        int alignment = channels * 2;
        if (!literal(header, 0, "RIFF") || !literal(header, 8, "WAVEfmt ") || !literal(header, 36, "data")
                || unsigned(b.getInt(4)) != fileBytes - 8 || b.getInt(16) != 16 || b.getShort(20) != 1
                || channels < 1 || channels > 2 || rate != 16000 || b.getInt(28) != rate * alignment
                || b.getShort(32) != alignment || b.getShort(34) != 16 || dataBytes != fileBytes - HEADER_BYTES
                || dataBytes == 0 || dataBytes % alignment != 0) throw new TelegramBackupFailure("UNSUPPORTED_WAV");
        return new Plan(dataBytes, channels, rate, ((maxPartBytes - HEADER_BYTES) / alignment) * alignment);
    }
    public static Plan read(RandomAccessFile file) throws IOException, TelegramBackupFailure {
        byte[] header = new byte[HEADER_BYTES]; file.seek(0); file.readFully(header);
        return plan(header, file.length(), MAX_PART_BYTES);
    }
    public static byte[] header(long dataBytes, int channels, int rate) {
        if (channels < 1 || channels > 2 || rate != 16000 || dataBytes < 0 || dataBytes > 0xfffffff0L || dataBytes % (2 * channels) != 0)
            throw new IllegalArgumentException("PCM format");
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)(dataBytes + 36));
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)channels);
        b.putInt(rate).putInt(rate * channels * 2).putShort((short)(channels * 2)).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)dataBytes);
        return b.array();
    }
    public static void write(RandomAccessFile input, OutputStream output, Plan plan, int index, BooleanSupplier current)
            throws IOException, TelegramBackupFailure {
        Part part = plan.part(index);
        if (!current.getAsBoolean()) throw new TelegramBackupFailure("CANCELED");
        output.write(header(part.dataBytes, plan.channels, plan.sampleRate));
        input.seek(part.offset);
        byte[] buffer = new byte[65536];
        long left = part.dataBytes;
        while (left > 0) {
            if (!current.getAsBoolean()) throw new TelegramBackupFailure("CANCELED");
            int count = (int)Math.min(buffer.length, left);
            input.readFully(buffer, 0, count); output.write(buffer, 0, count); left -= count;
        }
    }
    public static String filename(String recordingId, int index, int total) {
        if (!recordingId.matches("[0-9]{1,19}-[a-f0-9]{8}") || index < 0 || index >= total || total < 1 || total > 100)
            throw new IllegalArgumentException("part identity");
        return String.format(java.util.Locale.ROOT, "WA-reco-%s-part-%03d-of-%03d.wav", recordingId, index + 1, total);
    }
    /** A newly frozen named recording uses its export name; legacy jobs keep the original part names. */
    public static String filename(String recordingId, int index, int total, String exportFileName) {
        if (exportFileName == null) return filename(recordingId, index, total);
        if (index < 0 || index >= total || total < 1 || total > 100
                || !RecordingNames.validExportFileName(recordingId, exportFileName)) throw new IllegalArgumentException("part identity");
        if (total == 1) return exportFileName;
        return exportFileName.substring(0, exportFileName.length() - 4)
                + String.format(java.util.Locale.ROOT, "-part-%03d-of-%03d.wav", index + 1, total);
    }
    /** Header-safe, bounded canonical name tied to the source's stable recording ID and exact part. */
    public static boolean validFilename(String recordingId, String value, int index, int total) {
        if (value == null || value.length() > RecordingNames.MAX_EXPORT_UTF8_BYTES || !RecordingNames.validRecordingId(recordingId)
                || index < 0 || index >= total || total < 1 || total > 100
                || value.getBytes(StandardCharsets.UTF_8).length > RecordingNames.MAX_EXPORT_UTF8_BYTES) return false;
        if (value.equals(filename(recordingId, index, total))) return true;
        if (total == 1) return RecordingNames.validExportFileName(recordingId, value);
        String suffix = String.format(java.util.Locale.ROOT, "-part-%03d-of-%03d.wav", index + 1, total);
        return value.endsWith(suffix) && RecordingNames.validExportFileName(recordingId,
                value.substring(0, value.length() - suffix.length()) + ".wav");
    }
}
