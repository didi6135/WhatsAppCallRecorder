package com.codaki.usbaudio;

import java.io.IOException;
import java.util.Arrays;

/**
 * Bounded PCM queue with a logical sample cursor. A deadline shortage advances
 * the cursor; subsequently arriving samples for that elapsed interval expire.
 * No Android classes, clock, waiting, or audio routing belong in this class.
 */
public final class PcmFifo {
  private final short[] samples;
  private int head;
  private int size;
  private int maxQueued;
  private long produced;
  private long consumed;
  private long delivered;
  private long missing;
  private long stale;
  private long overflow;
  private long afterCutoff;
  private boolean frozen;
  private long cutoffProduced = -1;
  private int cutoffQueued = -1;

  public PcmFifo(int capacityFrames) {
    if (capacityFrames <= 0) throw new IllegalArgumentException("PCM capacity must be positive.");
    samples = new short[capacityFrames];
  }

  /**
   * Returns the number of incoming samples excluded as expired or after cutoff.
   * An overflow is reported explicitly; queued samples are never overwritten.
   */
  public synchronized int offer(short[] values, int count) throws IOException {
    bounds(values, 0, count);
    if (frozen) {
      afterCutoff += count;
      return count;
    }

    int expired = (int) Math.min(count, Math.max(0L, consumed - produced));
    produced += count;
    stale += expired;
    int retained = count - expired;
    if (retained > samples.length - size) {
      overflow += retained;
      throw new IOException("Audio FIFO overflow; captured samples could not be queued.");
    }
    for (int i = expired; i < count; i++) {
      samples[(head + size) % samples.length] = values[i];
      size++;
    }
    maxQueued = Math.max(maxQueued, size);
    return expired;
  }

  public synchronized int size() {
    return size;
  }

  /**
   * Called after the caller's live wait/deadline. Returns actual missing samples.
   * Holes become silence, including when the caller reuses a nonzero buffer.
   */
  public synchronized int read(short[] target, int offset, int count) {
    bounds(target, offset, count);
    if (frozen) throw new IllegalStateException("Use drain after freezing the PCM cutoff.");
    Arrays.fill(target, offset, offset + count, (short) 0);
    int available = remove(target, offset, count);
    consumed += count;
    missing += count - available;
    return count - available;
  }

  /**
   * Freeze only after the producer has quiesced. The exclusive produced-frame
   * cutoff includes all producer reads already committed to this FIFO. An
   * already-read consumer packet belongs to that same cutoff and must still be
   * emitted by its caller; freezing cannot reclaim the caller's private packet.
   */
  public synchronized Snapshot freeze() {
    if (!frozen) {
      frozen = true;
      cutoffProduced = produced;
      cutoffQueued = size;
    }
    return snapshot();
  }

  /**
   * Drain an actual, possibly partial final block. No expected samples or gaps
   * are invented at STOP. The caller aligns the separate channel cutoffs.
   */
  public synchronized int drain(short[] target, int offset, int maxCount) {
    bounds(target, offset, maxCount);
    if (!frozen) throw new IllegalStateException("Freeze the PCM cutoff before draining.");
    int available = remove(target, offset, maxCount);
    consumed += available;
    return available;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(produced, consumed, delivered, missing, stale, overflow,
        size, maxQueued, frozen, cutoffProduced, cutoffQueued, afterCutoff);
  }

  private int remove(short[] target, int offset, int count) {
    int available = Math.min(size, count);
    for (int i = 0; i < available; i++) {
      target[offset + i] = samples[head];
      head = (head + 1) % samples.length;
      size--;
    }
    delivered += available;
    return available;
  }

  private static void bounds(short[] values, int offset, int count) {
    if (values == null) throw new NullPointerException("PCM buffer is required.");
    if (offset < 0 || count < 0 || offset > values.length - count) {
      throw new IndexOutOfBoundsException("PCM range is outside the buffer.");
    }
  }

  /** Immutable diagnostic counters; all frame counts refer to one mono track. */
  public static final class Snapshot {
    public final long producedFrames;
    public final long consumedFrames;
    public final long deliveredFrames;
    public final long missingFrames;
    public final long staleFrames;
    public final long overflowFrames;
    public final int queuedFrames;
    public final int maxQueuedFrames;
    public final boolean frozen;
    public final long cutoffProducedFrames;
    public final int cutoffQueuedFrames;
    public final long afterCutoffFrames;

    private Snapshot(long produced, long consumed, long delivered, long missing,
        long stale, long overflow, int queued, int maxQueued, boolean frozen,
        long cutoffProduced, int cutoffQueued, long afterCutoff) {
      producedFrames = produced;
      consumedFrames = consumed;
      deliveredFrames = delivered;
      missingFrames = missing;
      staleFrames = stale;
      overflowFrames = overflow;
      queuedFrames = queued;
      maxQueuedFrames = maxQueued;
      this.frozen = frozen;
      cutoffProducedFrames = cutoffProduced;
      cutoffQueuedFrames = cutoffQueued;
      afterCutoffFrames = afterCutoff;
    }
  }
}
