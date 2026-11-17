package br.com.batepapo.client;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;

import java.util.ArrayDeque;

/** At most three decoded pictures in flight, including any frame held by the JavaFX viewer. */
final class ScreenPixels {
    private final ArrayDeque<byte[]> returned = new ArrayDeque<>(3);
    private int inUse;

    VoiceEngine.Frame convert(VideoFrame video) throws Exception {
        int width = video.buffer.getWidth(), height = video.buffer.getHeight();
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || count > 3840L * 2160) return null;
        byte[] bytes = acquire((int) count * 4);
        if (bytes == null) return null;
        try {
            // libyuv's ARGB FourCC stores B, G, R, A bytes on the supported little-endian Windows platform.
            VideoBufferConverter.convertFromI420(video.buffer, bytes, FourCC.ARGB);
            return new VoiceEngine.Frame(width, height, bytes, () -> recycle(bytes));
        } catch (Exception | LinkageError failure) {
            recycle(bytes);
            throw failure;
        }
    }

    private synchronized byte[] acquire(int length) {
        if (inUse == 3) return null;
        inUse++;
        byte[] bytes = returned.pollFirst();
        return bytes != null && bytes.length == length ? bytes : new byte[length];
    }

    private synchronized void recycle(byte[] bytes) {
        inUse--;
        returned.addFirst(bytes);
    }
}
