package com.codaki.usbaudio;

/** Holds an unequal terminal packet until in-flight producer samples have reached the frozen FIFOs. */
final class PcmStopTail {
  private final short[] left, right;
  private final int leftFrames, rightFrames;
  final int flags;
  private boolean emitted;

  PcmStopTail(short[] left, int leftFrames, short[] right, int rightFrames, int flags) {
    if (left == null || right == null || leftFrames < 0 || rightFrames < 0
        || leftFrames > left.length || rightFrames > right.length
        || Math.max(leftFrames, rightFrames) > Math.min(left.length, right.length)
        || Math.max(leftFrames, rightFrames) > 4096) {
      throw new IllegalArgumentException("Invalid terminal packet bounds.");
    }
    this.left = left; this.right = right; this.leftFrames = leftFrames; this.rightFrames = rightFrames; this.flags = flags;
  }

  byte[] complete(PcmFifo leftFifo, PcmFifo rightFifo) {
    if (emitted) throw new IllegalStateException("Terminal packet was already emitted.");
    if (!leftFifo.snapshot().frozen || !rightFifo.snapshot().frozen) {
      throw new IllegalStateException("Both producers must be quiesced and frozen before terminal completion.");
    }
    int frames = Math.max(leftFrames, rightFrames);
    // Only fill the missing suffix. Remaining captured samples stay queued for
    // the next aligned tail packet; exhausted tracks keep terminal zero padding.
    leftFifo.drain(left, leftFrames, frames - leftFrames);
    rightFifo.drain(right, rightFrames, frames - rightFrames);
    emitted = true;
    return interleave(left, right, frames);
  }

  static byte[] interleave(short[] left, short[] right, int frames) {
    if (frames < 0 || frames > left.length || frames > right.length || frames > 4096) {
      throw new IllegalArgumentException("Invalid stereo packet size.");
    }
    byte[] pcm = new byte[frames * 4];
    for (int i = 0; i < frames; i++) {
      pcm[4*i] = (byte)left[i]; pcm[4*i+1] = (byte)(left[i] >> 8);
      pcm[4*i+2] = (byte)right[i]; pcm[4*i+3] = (byte)(right[i] >> 8);
    }
    return pcm;
  }
}
