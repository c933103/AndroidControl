package org.androidcontrol.app.control;

import android.util.AtomicFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class TargetCompatJournalTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (String mode : Arrays.asList("success", "leave-new", "leave-backup", "drop-new", "corrupt")) {
            Path directory = Files.createTempDirectory("compat-atomic-test-");
            try {
                AtomicFile.reset();
                File base = directory.resolve("journal").toFile();
                TargetCompatJournal journal = new TargetCompatJournal(base);
                journal.write("old record");
                AtomicFile.finishFailure = mode;
                int reads = AtomicFile.openReadCalls;
                try {
                    journal.write("new record");
                    check(mode.equals("success"), "silent " + mode + " failure was accepted");
                } catch (IOException error) {
                    check(!mode.equals("success"), "successful commit rejected");
                }
                check(AtomicFile.openReadCalls == reads, "verification consumed recovery evidence");
                if (mode.startsWith("leave")) check(new File(base + ".new").exists(), "failed-write evidence removed");
                if (mode.equals("leave-backup")) check(new File(base + ".bak").exists(), "backup evidence removed");
                if (mode.equals("success")) check(journal.read().equals("new record"), "successful bytes changed");
                passed++; System.out.println("PASS atomic adapter " + mode);
            } finally {
                try (var files = Files.walk(directory)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
        Path recoveryDirectory = Files.createTempDirectory("compat-atomic-recovery-");
        try {
            AtomicFile.reset();
            File base = recoveryDirectory.resolve("journal").toFile();
            Files.write(base.toPath(), "stale base".getBytes("UTF-8"));
            File backup = new File(base + ".bak");
            Files.write(backup.toPath(), "authoritative backup".getBytes("UTF-8"));
            AtomicFile.backupRecoveryFailure = true;
            TargetCompatJournal journal = new TargetCompatJournal(base);
            try { journal.read(); throw new AssertionError("failed backup recovery accepted stale base"); }
            catch (IOException expected) {
                check(backup.exists() && AtomicFile.openStreams == 0, "backup evidence or stream lifetime lost");
            }
            AtomicFile.backupRecoveryFailure = false;
            check(journal.read().equals("authoritative backup"), "backup recovery did not restore original");
            passed++; System.out.println("PASS atomic adapter failed backup recovery retains evidence and closes stream");
        } finally {
            AtomicFile.reset();
            try (var files = Files.walk(recoveryDirectory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Path directory = Files.createTempDirectory("compat-atomic-controller-");
        try {
            AtomicFile.reset();
            TargetCompatJournal journal = new TargetCompatJournal(directory.resolve("journal").toFile());
            List<String[]> calls = new ArrayList<>();
            TargetCompatOverrides controller = new TargetCompatOverrides(journal, commands -> { calls.add(commands); return ""; });
            String pkg = "fixture.compat.target";
            TargetCompatOverrides.Snapshot snapshot = TargetCompatOverrides.snapshot("ChangeId(1; rawOverrides={" + pkg + "=false})", pkg, Arrays.asList("1"));
            AtomicFile.finishFailure = "leave-new";
            try { controller.apply(pkg, "enable", "1", snapshot); throw new AssertionError("failed commit accepted"); }
            catch (IOException expected) { check(calls.isEmpty(), "compat command executed without a commit"); }
            AtomicFile.finishFailure = null;
            controller.apply(pkg, "enable", "1", snapshot);
            AtomicFile.deleteFailure = true;
            try { controller.restore(pkg, true); throw new AssertionError("failed deletion accepted"); }
            catch (IOException expected) { check(controller.hasPending(), "failed deletion lost journal"); }
            AtomicFile.deleteFailure = false;
            controller.restore(pkg, true); check(!controller.hasPending(), "retry did not clear journal");
            passed++; System.out.println("PASS atomic adapter controller commit/delete failure boundaries");
        } finally {
            AtomicFile.reset();
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("PASS " + passed + " production atomic adapter tests (controlled AtomicFile dependency)");
    }
}
