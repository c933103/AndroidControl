package org.androidcontrol.app.control;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Moves old target-labelled journals without dropping pending restoration. */
public final class PortraitStateFiles {
    private PortraitStateFiles() {}

    public static List<File[]> migrations(File directory) throws IOException {
        List<File[]> moves = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot inspect portrait restoration records");
        for (String kind : new String[]{"compat", "tasks"}) {
            File destination = new File(directory, "androidcontrol-target-" + kind);
            File previous = null;
            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith("androidcontrol-") || !name.endsWith("-" + kind)
                        || file.equals(destination)) continue;
                if (previous != null || destination.exists()) {
                    throw new IOException("Conflicting portrait restoration records; originals retained");
                }
                previous = file;
            }
            if (previous != null) moves.add(new File[]{previous, destination});
        }
        return moves;
    }

    public static void migrate(List<File[]> moves) throws IOException {
        for (File[] move : moves) {
            // Recheck immediately before rename; never overwrite another journal.
            if (move[1].exists() || !move[0].renameTo(move[1])) {
                throw new IOException("Could not migrate portrait restoration record; retry required");
            }
        }
    }
}
