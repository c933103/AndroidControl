package org.androidcontrol.app.control;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TargetCompatOverridesTest {
    private static final String PKG = "fixture.compat.target";
    private static final String[] IDS = {"181136395", "184838306", "185004937", "237531167", "174042936"};
    private static int passed;

    private static final class MemoryStore implements TargetCompatOverrides.Store {
        String data;
        boolean failRead, failWrite, failDelete;
        @Override public String read() throws IOException {
            if (failRead) throw new IOException("fixture read failure");
            return data;
        }
        @Override public void write(String value) throws IOException {
            if (failWrite) throw new IOException("fixture atomic write failure");
            data = value;
        }
        @Override public void delete() throws IOException {
            if (failDelete) throw new IOException("fixture delete failure");
            data = null;
        }
    }

    private static final class FakeShell implements TargetCompatOverrides.Commands {
        final MemoryStore store;
        final Map<String, Boolean> explicit = new LinkedHashMap<>();
        final List<List<String>> calls = new ArrayList<>();
        String failId, failMessage, unknownId, warning;
        boolean failAfterMutation, crashAfterMutation;
        FakeShell(MemoryStore store) { this.store = store; }
        @Override public String run(String... args) throws Exception {
            check(args.length == 4 || args.length == 5, "unexpected fake command length");
            check(args[0].equals("compat"), "unexpected fake command");
            check(args[args.length - 1].equals(PKG), "wrong target package");
            if (args.length == 5) check(args[2].equals("--no-kill"), "unexpected command option");
            String id = args[args.length - 2];
            check(store.data != null && store.data.contains(id), "command preceded durable intent");
            calls.add(Arrays.asList(args.clone()));
            if (id.equals(unknownId)) return "Unknown change";
            boolean fail = id.equals(failId);
            if (fail && !failAfterMutation) { failId = null; throw new IOException(failMessage); }
            switch (args[1]) {
                case "enable": explicit.put(id, true); break;
                case "disable": explicit.put(id, false); break;
                case "reset": explicit.remove(id); break;
                default: throw new AssertionError("unexpected mode");
            }
            if (crashAfterMutation) { crashAfterMutation = false; throw new AssertionError("fixture daemon death"); }
            if (fail) { failId = null; throw new IOException(failMessage); }
            return warning == null ? "" : warning;
        }
    }

    private static final class Fixture {
        final MemoryStore store = new MemoryStore();
        final FakeShell shell = new FakeShell(store);
        TargetCompatOverrides service() { return new TargetCompatOverrides(store, shell); }
        TargetCompatOverrides.Snapshot snapshot() {
            return TargetCompatOverrides.snapshot(dump(shell.explicit), PKG, Arrays.asList(IDS));
        }
    }

    private static String dump(Map<String, Boolean> explicit) {
        StringBuilder result = new StringBuilder();
        for (String id : IDS) {
            result.append("ChangeId(").append(id).append("; name=FIXTURE_CHANGE");
            if (explicit.containsKey(id)) {
                result.append("; packageOverrides={").append(PKG).append('=').append(explicit.get(id)).append('}')
                        .append("; rawOverrides={").append(PKG).append('=').append(explicit.get(id)).append('}');
            }
            result.append(")\n");
        }
        return result.toString();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked operation, Class<? extends Throwable> type) throws Exception {
        try { operation.run(); }
        catch (Throwable error) { if (type.isInstance(error)) return; throw error; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void test(String name, Checked operation) throws Exception {
        operation.run();
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        for (String id : IDS) for (Boolean prior : new Boolean[]{null, false, true}) for (boolean preserve : new boolean[]{false, true}) {
            test("round trip " + id + " prior=" + prior + " preserve=" + preserve, () -> {
                Fixture f = new Fixture();
                if (prior != null) f.shell.explicit.put(id, prior);
                Map<String, Boolean> original = new LinkedHashMap<>(f.shell.explicit);
                TargetCompatOverrides.Snapshot snapshot = f.snapshot();
                for (int i = 0; i < IDS.length; i++) f.service().apply(PKG, i < 2 ? "disable" : "enable", IDS[i], snapshot);
                check(f.service().hasPending(), "journal missing");
                f.service().restore(PKG, preserve); // New instance models daemon restart.
                check(f.shell.explicit.equals(original), "explicit states were not exactly restored");
                check(!f.service().hasPending(), "completed journal retained");
                for (List<String> call : f.shell.calls.subList(IDS.length, f.shell.calls.size())) {
                    check(call.contains("--no-kill") == preserve, "restore process option changed");
                }
            });
        }
        test("repeat apply preserves the original snapshot", () -> {
            Fixture f = new Fixture(); f.shell.explicit.put(IDS[0], false);
            f.service().apply(PKG, "enable", IDS[0], f.snapshot());
            f.service().apply(PKG, "enable", IDS[0], f.snapshot());
            f.shell.unknownId = IDS[0];
            f.service().apply(PKG, "enable", IDS[0], f.snapshot());
            check(f.store.data.contains("DISABLED"), "repeat replaced or removed original prior state");
            f.shell.unknownId = null; f.service().restore(PKG, true);
            check(Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "repeat prior state lost");
        });
        for (boolean after : new boolean[]{false, true}) test("ambiguous apply afterMutation=" + after, () -> {
            Fixture f = new Fixture(); f.shell.explicit.put(IDS[0], false);
            f.shell.failId = IDS[0]; f.shell.failMessage = "transport failed"; f.shell.failAfterMutation = after;
            check(!f.service().apply(PKG, "enable", IDS[0], f.snapshot()), "ambiguous success reported");
            check(f.service().hasPending(), "ambiguous intent removed");
            f.service().restore(PKG, true);
            check(Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "ambiguous prior lost");
        });
        test("daemon death after mutation retains original", () -> {
            Fixture f = new Fixture(); f.shell.explicit.put(IDS[0], false); f.shell.crashAfterMutation = true;
            fails(() -> f.service().apply(PKG, "enable", IDS[0], f.snapshot()), AssertionError.class);
            f.service().restore(PKG, false);
            check(Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "crash lost prior");
        });
        for (Boolean prior : new Boolean[]{null, false, true}) test("failed restore retains state=" + prior, () -> {
            Fixture f = new Fixture(); if (prior != null) f.shell.explicit.put(IDS[0], prior);
            TargetCompatOverrides.Snapshot snapshot = f.snapshot();
            for (String id : IDS) f.service().apply(PKG, "enable", id, snapshot);
            f.shell.failId = IDS[0]; f.shell.failMessage = "temporary backend failure";
            fails(() -> f.service().restore(PKG, true), IllegalStateException.class);
            check(f.store.data.split("\n").length == 2 && f.store.data.contains(IDS[0]), "wrong retry records");
            f.service().restore(PKG, true);
            check(prior == null ? !f.shell.explicit.containsKey(IDS[0]) : prior.equals(f.shell.explicit.get(IDS[0])), "retry lost prior");
        });
        test("unknown command text retains prior state until restore", () -> {
            Fixture f = new Fixture(); f.shell.explicit.put(IDS[0], false); f.shell.unknownId = IDS[0];
            check(!f.service().apply(PKG, "enable", IDS[0], f.snapshot()), "unknown applied");
            check(f.store.data != null && Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "ambiguous command lost record");
            f.shell.unknownId = null; f.service().restore(PKG, true);
            check(Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "ambiguous restore lost prior");
        });
        for (String warning : new String[]{"Change 1 is not known yet", "Warning: this could have no effect"}) test("warning after mutation: " + warning, () -> {
            Fixture f = new Fixture(); f.shell.explicit.put(IDS[0], false); f.shell.warning = warning;
            check(!f.service().apply(PKG, "enable", IDS[0], f.snapshot()), "warning was not reported");
            check(Boolean.TRUE.equals(f.shell.explicit.get(IDS[0])) && f.store.data.contains("DISABLED"), "warning lost mutated state's recovery record");
            f.shell.warning = null; f.service().restore(PKG, true);
            check(Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "warning restore lost prior");
        });
        for (String message : new String[]{"unknown change", "unknown id", "no such change", "not a known change", "unknown package", "package not found"}) test("unavailable restore: " + message, () -> {
            Fixture f = new Fixture(); f.service().apply(PKG, "enable", IDS[0], f.snapshot());
            f.shell.failId = IDS[0]; f.shell.failMessage = message;
            f.service().restore(PKG, false); check(f.store.data == null, "unavailable reset retained");
        });
        test("legacy journal resets distinct IDs and retains explicit legacy retry mode", () -> {
            Fixture f = new Fixture(); f.store.data = IDS[0] + "\n" + IDS[0] + "\n" + IDS[1];
            f.shell.failId = IDS[0]; f.shell.failMessage = "temporary failure";
            fails(() -> f.service().restore(PKG, true), IllegalStateException.class);
            check(f.shell.calls.size() == 2 && f.store.data.contains("LEGACY_RESET"), "legacy mode was fabricated");
            f.service().restore(PKG, true); check(f.store.data == null, "legacy retry pending");
        });
        test("write failure before apply never mutates", () -> {
            Fixture f = new Fixture(); f.store.failWrite = true;
            fails(() -> f.service().apply(PKG, "enable", IDS[0], f.snapshot()), IOException.class);
            check(f.shell.calls.isEmpty() && f.store.data == null, "mutation preceded failed write");
        });
        test("read failure never mutates", () -> {
            Fixture f = new Fixture(); f.store.failRead = true;
            fails(() -> f.service().apply(PKG, "enable", IDS[0], f.snapshot()), IOException.class);
            check(f.shell.calls.isEmpty(), "mutation preceded failed read");
        });
        test("failed retry journal write preserves every original record", () -> {
            Fixture f = new Fixture(); TargetCompatOverrides.Snapshot snapshot = f.snapshot();
            for (String id : IDS) f.service().apply(PKG, "enable", id, snapshot);
            String original = f.store.data; f.store.failWrite = true;
            f.shell.failId = IDS[0]; f.shell.failMessage = "temporary failure";
            fails(() -> f.service().restore(PKG, true), IOException.class);
            check(original.equals(f.store.data), "failed atomic replacement lost original journal");
            f.store.failWrite = false; f.service().restore(PKG, true);
            check(f.shell.explicit.isEmpty() && f.store.data == null, "retry failed");
        });
        test("delete failure can be retried without losing prior", () -> {
            Fixture f = new Fixture(); f.shell.explicit.put(IDS[0], false);
            f.service().apply(PKG, "enable", IDS[0], f.snapshot()); f.store.failDelete = true;
            fails(() -> f.service().restore(PKG, false), IOException.class);
            check(f.store.data != null, "delete failure lost record");
            f.store.failDelete = false; f.service().restore(PKG, false);
            check(Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "delete retry lost original");
        });
        for (String journal : new String[]{"target-compat-v1\tother.pkg\n1\tENABLED\n", "target-compat-v2\t" + PKG + "\n", "1\tENABLED\n", "target-compat-v1\t" + PKG + "\n1\tBOGUS\n", "target-compat-v1\t" + PKG + "\n1\tENABLED\n1\tDISABLED\n"}) test("invalid journal retained " + journal.replace('\n', ' '), () -> {
            Fixture f = new Fixture(); f.store.data = journal;
            fails(() -> f.service().restore(PKG, false), IOException.class);
            check(f.shell.calls.isEmpty() && journal.equals(f.store.data), "invalid journal changed");
        });
        String prefix = "ChangeId(" + IDS[0];
        for (String invalid : new String[]{"", "Permission Denial", prefix + "; rawOverrides={" + PKG + "=false}", prefix + "; packageOverrides={" + PKG + "=true})", prefix + "; rawOverrides={" + PKG + "=wat})", prefix + "; rawOverrides={" + PKG + "=false, " + PKG + "=true})", prefix + "; rawOverrides={" + PKG + "=[5,4,true]})", prefix + "; rawOverrides={" + PKG + "=[0,999999999999999999999,true]})", prefix + "; rawOverrides={" + PKG + "=false,})", prefix + "; unfamiliarMetadata=true)", prefix + ")\n" + prefix + ")"}) test("reject malformed snapshot " + invalid, () -> {
            fails(() -> TargetCompatOverrides.snapshot(invalid, PKG, Arrays.asList(IDS)), IllegalStateException.class);
        });
        test("raw wins over evaluated and exact package match", () -> {
            Fixture f = new Fixture(); f.shell.explicit.put(IDS[0], false);
            String text = prefix + "; packageOverrides={" + PKG + "=true}; rawOverrides={" + PKG + ".other=true, " + PKG + "=false})";
            TargetCompatOverrides.Snapshot snapshot = TargetCompatOverrides.snapshot(text, PKG, Arrays.asList(IDS));
            f.service().apply(PKG, "enable", IDS[0], snapshot); f.service().restore(PKG, true);
            check(Boolean.FALSE.equals(f.shell.explicit.get(IDS[0])), "effective value mistaken for raw value");
        });
        test("ranged target is untouched, other packages do not match", () -> {
            Fixture f = new Fixture();
            String text = prefix + "; rawOverrides={" + PKG + "=[1,99,false], other.pkg=true})";
            check(!f.service().apply(PKG, "enable", IDS[0], TargetCompatOverrides.snapshot(text, PKG, Arrays.asList(IDS))), "range flattened");
            check(f.store.data == null && f.shell.calls.isEmpty(), "ranged value touched");
            text = prefix + "; rawOverrides={other.pkg=[1,99,false], " + PKG + ".other=true})";
            f.service().apply(PKG, "enable", IDS[0], TargetCompatOverrides.snapshot(text, PKG, Arrays.asList(IDS)));
            f.service().restore(PKG, true); check(f.shell.explicit.isEmpty(), "neighbor matched target");
        });
        test("unknown IDs and empty complete config are skipped", () -> {
            Fixture f = new Fixture();
            for (String text : new String[]{"ChangeId(99; disabled)", "No compat overrides."}) {
                check(!f.service().apply(PKG, "enable", IDS[0], TargetCompatOverrides.snapshot(text, PKG, Arrays.asList(IDS))), "unknown ID applied");
            }
            check(f.shell.calls.isEmpty() && f.store.data == null, "unknown ID state changed");
        });
        test("empty raw map is verified default", () -> {
            Fixture f = new Fixture();
            String text = prefix + "; name=TEST; enableSinceTargetSdk=33; disabled; loggingOnly; rawOverrides={}; overridable)";
            f.service().apply(PKG, "enable", IDS[0], TargetCompatOverrides.snapshot(text, PKG, Arrays.asList(IDS)));
            f.service().restore(PKG, false); check(f.shell.explicit.isEmpty(), "default was not restored");
        });
        test("snapshot owner mismatch never writes", () -> {
            Fixture f = new Fixture();
            TargetCompatOverrides.Snapshot snapshot = TargetCompatOverrides.snapshot(dump(f.shell.explicit), "other.pkg", Arrays.asList(IDS));
            fails(() -> f.service().apply(PKG, "enable", IDS[0], snapshot), IllegalStateException.class);
            check(f.shell.calls.isEmpty() && f.store.data == null, "owner mismatch changed state");
        });
        System.out.println("PASS " + passed + " compatibility override tests (production Java; fake store/shell only)");
    }
}
