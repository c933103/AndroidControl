package org.androidcontrol.app.control;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public final class PortraitStateFilesTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("portrait-state-").toFile();
        try {
            File previous = new File(directory, "androidcontrol-previous-target-compat");
            File tasks = new File(directory, "androidcontrol-previous-target-tasks");
            Files.write(previous.toPath(), "pending-compat\n".getBytes());
            Files.write(tasks.toPath(), "pending-task\n".getBytes());
            List<File[]> moves = PortraitStateFiles.migrations(directory);
            check(moves.size() == 2);
            PortraitStateFiles.migrate(moves);
            check(!previous.exists() && !tasks.exists());
            check(new String(Files.readAllBytes(new File(directory, "androidcontrol-target-compat").toPath())).equals("pending-compat\n"));
            check(new String(Files.readAllBytes(new File(directory, "androidcontrol-target-tasks").toPath())).equals("pending-task\n"));
            check(PortraitStateFiles.migrations(directory).isEmpty());
            Files.write(previous.toPath(), "different-pending-state".getBytes());
            try { PortraitStateFiles.migrations(directory); throw new AssertionError("Conflict accepted"); }
            catch (IOException expected) { check(previous.exists()); }
            check(new String(Files.readAllBytes(new File(directory, "androidcontrol-target-compat").toPath())).equals("pending-compat\n"));
        } finally {
            for (File file : directory.listFiles()) Files.delete(file.toPath());
            Files.delete(directory.toPath());
        }
        System.out.println("Portrait journal migration checks passed");
    }
}
