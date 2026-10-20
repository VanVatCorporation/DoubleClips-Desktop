package com.vanvatcorporation.doubleclips.drive;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * One chunked, resumable upload: POST /uploads, PUT chunks (Upload-Offset), POST /complete. After a network error it
 * asks the server how far it got and continues from there; a real server answer (quota, forbidden, daily limit) stops it.
 */
public final class ResumableUpload {
    private ResumableUpload() {}

    private static final int MAX_ATTEMPTS = 5;

    /**
     * @param progress bytes of the file sent so far (0..size)
     * @param cancel   set to true to stop; the session is cancelled on the server and CancellationException is thrown
     */
    public static DriveNode run(DriveClient client, File file, String parentId, String name, String mimeType,
                                LongConsumer progress, AtomicBoolean cancel) throws IOException {
        final long size = file.length();
        DriveClient.UploadSession session = client.createUpload(parentId, name != null ? name : file.getName(), size, mimeType);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] buf = new byte[(int) Math.min(Math.max(session.chunkSize, 64 * 1024), Math.max(size, 1))];
            long offset = session.received;
            int attempts = 0;
            while (offset < size) {
                if (cancel != null && cancel.get()) throw new CancellationException("Upload stopped.");
                int length = (int) Math.min(buf.length, size - offset);
                raf.seek(offset);
                raf.readFully(buf, 0, length);
                try {
                    final long base = offset;
                    DriveClient.ChunkResult result = client.putChunk(session.uploadId, offset, buf, length, sent -> {
                        if (progress != null) progress.accept(Math.min(size, base + sent));
                    });
                    offset = result.offset;        // on a mismatch the server knows better
                    if (!result.mismatch) attempts = 0;
                    if (progress != null) progress.accept(Math.min(size, offset));
                } catch (DriveError e) {
                    throw e;                       // a real answer (quota, forbidden, daily limit ...)
                } catch (IOException e) {
                    if (cancel != null && cancel.get()) throw new CancellationException("Upload stopped.");
                    if (++attempts > MAX_ATTEMPTS) throw e;
                    try { Thread.sleep(attempts * 800L); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Interrupted"); }
                    offset = client.uploadReceived(session.uploadId);
                }
            }
            if (cancel != null && cancel.get()) throw new CancellationException("Upload stopped.");
            return client.completeUpload(session.uploadId);
        } catch (IOException | RuntimeException e) {
            client.cancelUpload(session.uploadId);
            throw e;
        }
    }
}
