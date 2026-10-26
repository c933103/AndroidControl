package org.androidcontrol.app.control;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Checked AtomicFile adapter: no compat command may run after a failed commit. */
public final class TargetCompatJournal implements TargetCompatOverrides.Store {
    private final File base;
    private final AtomicFile atomic;

    public TargetCompatJournal(File base) {
        this.base = base;
        this.atomic = new AtomicFile(base);
    }

    @Override public String read() throws IOException {
        try {
            FileInputStream stream = atomic.openRead();
            // openRead can suppress a failed backup-to-base rename. A readable
            // base is not authoritative while the recovery backup still exists.
            if (sibling(".bak").exists()) {
                stream.close();
                throw new IOException("Could not recover compatibility journal; record retained");
            }
            return readStream(stream);
        } catch (FileNotFoundException error) {
            if (base.exists() || sibling(".bak").exists()) throw error;
            return null;
        }
    }

    @Override public void write(String value) throws IOException {
        FileOutputStream stream = atomic.startWrite();
        try {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            // AtomicFile.finishWrite logs sync failure rather than reporting it.
            stream.getFD().sync();
        } catch (IOException | RuntimeException error) {
            atomic.failWrite(stream);
            throw error;
        }
        atomic.finishWrite(stream);
        // finishWrite also suppresses close/rename failures. Do not openRead here:
        // it can delete a failed .new file or replace the base from .bak. Keep the
        // recovery evidence intact and verify the committed bytes directly.
        if (sibling(".new").exists() || sibling(".bak").exists()
                || !value.equals(readStream(new FileInputStream(base)))) {
            throw new IOException("Could not commit compatibility journal; no compatibility change applied");
        }
    }

    @Override public void delete() throws IOException {
        atomic.delete();
        if (base.exists() || sibling(".new").exists() || sibling(".bak").exists()) {
            throw new IOException("Could not remove compatibility journal; retry required");
        }
    }

    private File sibling(String suffix) { return new File(base.getPath() + suffix); }

    private static String readStream(FileInputStream stream) throws IOException {
        try (FileInputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
