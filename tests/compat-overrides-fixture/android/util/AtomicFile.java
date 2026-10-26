package android.util;

import java.io.*;
import java.nio.file.*;

/** Controlled dependency, not an Android runtime implementation. */
public final class AtomicFile {
    public static String finishFailure;
    public static boolean deleteFailure, backupRecoveryFailure;
    public static int openStreams;
    public static int openReadCalls;
    private final File base, fresh, backup;
    public AtomicFile(File base) {
        this.base = base; this.fresh = new File(base + ".new"); this.backup = new File(base + ".bak");
    }
    public FileOutputStream startWrite() throws IOException { return new FileOutputStream(fresh); }
    public void finishWrite(FileOutputStream stream) {
        try {
            stream.close();
            if ("leave-new".equals(finishFailure)) return;
            if ("leave-backup".equals(finishFailure)) {
                Files.write(backup.toPath(), "previous".getBytes("UTF-8")); return;
            }
            if ("drop-new".equals(finishFailure)) { fresh.delete(); return; }
            if ("corrupt".equals(finishFailure)) Files.write(fresh.toPath(), "corrupted".getBytes("UTF-8"));
            Files.move(fresh.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { /* AOSP finishWrite does not report its failures. */ }
    }
    public void failWrite(FileOutputStream stream) {
        try { stream.close(); } catch (IOException ignored) {}
        fresh.delete();
    }
    public FileInputStream openRead() throws FileNotFoundException {
        openReadCalls++;
        if (backup.exists() && !backupRecoveryFailure) { base.delete(); backup.renameTo(base); }
        if (base.exists()) fresh.delete();
        return new FileInputStream(base) {
            private boolean closed;
            { openStreams++; }
            @Override public void close() throws IOException {
                if (!closed) { closed = true; openStreams--; }
                super.close();
            }
        };
    }
    public void delete() {
        if (deleteFailure) return;
        base.delete(); fresh.delete(); backup.delete();
    }
    public static void reset() { finishFailure = null; deleteFailure = false; backupRecoveryFailure = false; openReadCalls = 0; openStreams = 0; }
}
