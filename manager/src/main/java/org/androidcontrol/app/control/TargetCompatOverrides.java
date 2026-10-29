package org.androidcontrol.app.control;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Target-only compatibility changes with a write-ahead prior-state journal. */
public final class TargetCompatOverrides {
    /** Writes must atomically replace the record before returning. */
    public interface Store {
        String read() throws IOException;
        void write(String value) throws IOException;
        void delete() throws IOException;
    }

    public interface Commands {
        String run(String... args) throws Exception;
    }

    private enum State {
        DEFAULT("reset"), ENABLED("enable"), DISABLED("disable"), LEGACY_RESET("reset");
        final String restoreMode;
        State(String restoreMode) { this.restoreMode = restoreMode; }
    }

    public static final class Snapshot {
        private final String packageName;
        private final Map<String, State> states;
        private Snapshot(String packageName, Map<String, State> states) {
            this.packageName = packageName;
            this.states = states;
        }
    }

    private static final String HEADER = "target-compat-v1\t";
    private static final Pattern ID = Pattern.compile("[1-9][0-9]*");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern CHANGE = Pattern.compile("ChangeId\\(([1-9][0-9]*)(;.*)?\\)");
    private static final Pattern ENTRY = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)="
                    + "(true|false|\\[-?[0-9]+,-?[0-9]+,(?:true|false)\\])");
    private final Store store;
    private final Commands commands;

    public TargetCompatOverrides(Store store, Commands commands) {
        this.store = store;
        this.commands = commands;
    }

    public synchronized boolean hasPending() throws IOException {
        return store.read() != null;
    }

    /**
     * Read the AOSP API33/API35 platform_compat dump's rawOverrides, never its
     * evaluated packageOverrides. Unknown IDs and version-ranged target entries
     * are intentionally omitted: the caller must not mutate either. A malformed
     * or unreadable dump is not evidence of an absent override.
     */
    public static Snapshot snapshot(String dump, String packageName, Collection<String> ids) {
        requirePackage(packageName);
        for (String id : ids) requireId(id);
        if (dump == null || dump.trim().isEmpty()) throw invalidSnapshot();
        Map<String, State> states = new LinkedHashMap<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        String text = dump.trim();
        if (text.equals("No compat overrides.")) return new Snapshot(packageName, states);
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher change = CHANGE.matcher(line);
            if (!change.matches()) throw invalidSnapshot();
            String id = change.group(1);
            if (!ids.contains(id)) continue;
            if (seen.put(id, true) != null) throw invalidSnapshot();
            Map<String, String> raw = null;
            Map<String, String> evaluated = null;
            String fields = change.group(2);
            if (fields != null) {
                for (String field : fields.substring(1).split(";", -1)) {
                    field = field.trim();
                    if (field.startsWith("rawOverrides")) {
                        if (raw != null || !field.startsWith("rawOverrides=")) throw invalidSnapshot();
                        raw = parseOverrides(field.substring("rawOverrides=".length()));
                    } else if (field.startsWith("packageOverrides")) {
                        if (evaluated != null || !field.startsWith("packageOverrides=")) throw invalidSnapshot();
                        evaluated = parseOverrides(field.substring("packageOverrides=".length()));
                    } else if (!field.matches("name=[A-Za-z0-9_]+")
                            && !field.matches("enableSinceTargetSdk=-?[0-9]+")
                            && !field.equals("disabled") && !field.equals("loggingOnly")
                            && !field.equals("overridable")) {
                        throw invalidSnapshot();
                    }
                }
            }
            String value = raw == null ? null : raw.get(packageName);
            // A target evaluated override without its raw source is not the
            // pinned format. Do not interpret a different/OEM format as default.
            if (value == null && evaluated != null && evaluated.containsKey(packageName)) {
                throw invalidSnapshot();
            }
            if (value == null) states.put(id, State.DEFAULT);
            else if (value.equals("true")) states.put(id, State.ENABLED);
            else if (value.equals("false")) states.put(id, State.DISABLED);
            // A supported [min,max,bool] raw value is deliberately left untouched.
        }
        return new Snapshot(packageName, states);
    }

    private static Map<String, String> parseOverrides(String text) {
        if (!text.startsWith("{") || !text.endsWith("}")) throw invalidSnapshot();
        String body = text.substring(1, text.length() - 1).trim();
        Map<String, String> values = new LinkedHashMap<>();
        int position = 0;
        while (position < body.length()) {
            Matcher entry = ENTRY.matcher(body);
            entry.region(position, body.length());
            if (!entry.lookingAt()) throw invalidSnapshot();
            String value = entry.group(2);
            if (value.startsWith("[")) {
                String[] range = value.substring(1, value.length() - 1).split(",");
                try {
                    if (Long.parseLong(range[0]) > Long.parseLong(range[1])) throw invalidSnapshot();
                } catch (NumberFormatException error) { throw invalidSnapshot(); }
            }
            if (values.put(entry.group(1), value) != null) throw invalidSnapshot();
            position = entry.end();
            if (position == body.length()) break;
            if (body.charAt(position++) != ',') throw invalidSnapshot();
            while (position < body.length() && Character.isWhitespace(body.charAt(position))) position++;
            if (position == body.length()) throw invalidSnapshot();
        }
        return values;
    }

    /** Returns false when the change is unsupported or the command is ambiguous. */
    public synchronized boolean apply(String packageName, String mode, String id, Snapshot snapshot)
            throws Exception {
        requirePackage(packageName);
        requireId(id);
        if (!mode.equals("enable") && !mode.equals("disable")) throw new IllegalArgumentException("Invalid mode");
        if (!packageName.equals(snapshot.packageName)) throw new IllegalStateException("Compatibility snapshot owner changed");
        State prior = snapshot.states.get(id);
        if (prior == null) return false;
        Map<String, State> records = readJournal(packageName);
        if (!records.containsKey(id)) {
            records.put(id, prior);
            writeJournal(packageName, records); // Never mutate before this atomic write succeeds.
        }
        String output;
        try {
            output = commands.run("compat", mode, "--no-kill", id, packageName);
        } catch (Exception error) {
            // Keep the original record: the command may have taken effect.
            return false;
        }
        // AOSP may warn that an ID is "not known yet" and then still install
        // the override. Text alone is not proof of a no-op; keep the prior state.
        return !ambiguousApplyOutput(output);
    }

    public synchronized void restore(String packageName, boolean preserveProcess) throws Exception {
        Map<String, State> records = readJournal(packageName);
        Map<String, State> failed = new LinkedHashMap<>();
        for (Map.Entry<String, State> entry : records.entrySet()) {
            List<String> args = new ArrayList<>();
            args.add("compat");
            args.add(entry.getValue().restoreMode);
            if (preserveProcess) args.add("--no-kill");
            args.add(entry.getKey());
            args.add(packageName);
            try {
                commands.run(args.toArray(new String[0]));
            } catch (Exception error) {
                if (!ignorableRestore(error)) failed.put(entry.getKey(), entry.getValue());
            }
        }
        writeJournal(packageName, failed);
        if (!failed.isEmpty()) {
            throw new IllegalStateException("Could not restore " + failed.size()
                    + " compatibility override(s) for " + packageName + "; they were retained for retry.");
        }
    }

    private Map<String, State> readJournal(String packageName) throws IOException {
        requirePackage(packageName);
        String text = store.read();
        Map<String, State> records = new LinkedHashMap<>();
        if (text == null || text.trim().isEmpty()) return records;
        String[] lines = text.split("\\r?\\n");
        boolean versioned = lines[0].startsWith(HEADER);
        int start = 0;
        if (versioned) {
            if (!lines[0].equals(HEADER + packageName)) throw new IOException("Compatibility journal owner mismatch; record retained");
            start = 1;
        }
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\t", -1);
            if (!ID.matcher(fields[0]).matches()) throw new IOException("Invalid compatibility journal; record retained");
            State state = State.LEGACY_RESET;
            if (versioned) {
                if (fields.length != 2) throw new IOException("Invalid compatibility journal; record retained");
                try { state = State.valueOf(fields[1]); }
                catch (IllegalArgumentException error) { throw new IOException("Invalid compatibility journal; record retained", error); }
                if (records.containsKey(fields[0])) throw new IOException("Duplicate compatibility journal entry; record retained");
            } else if (fields.length != 1) throw new IOException("Invalid legacy compatibility journal; record retained");
            records.put(fields[0], state);
        }
        return records;
    }

    private void writeJournal(String packageName, Map<String, State> records) throws IOException {
        if (records.isEmpty()) { store.delete(); return; }
        StringBuilder text = new StringBuilder(HEADER).append(packageName).append('\n');
        for (Map.Entry<String, State> entry : records.entrySet()) {
            text.append(entry.getKey()).append('\t').append(entry.getValue().name()).append('\n');
        }
        store.write(text.toString());
    }

    private static boolean ambiguousApplyOutput(String output) {
        String text = output.toLowerCase(Locale.ROOT);
        return text.contains("not known yet") || text.contains("unknown change")
                || text.contains("unknown or invalid change") || text.contains("no such change")
                || text.contains("could have no effect");
    }

    private static boolean ignorableRestore(Exception error) {
        String text = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        return text.contains("unknown change") || text.contains("unknown id")
                || text.contains("no such change") || text.contains("not a known change")
                || text.contains("unknown package") || text.contains("package not found");
    }

    private static IllegalStateException invalidSnapshot() {
        return new IllegalStateException("Cannot safely read prior compatibility overrides; no compatibility changes applied");
    }

    private static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid compatibility change ID");
    }

    private static void requirePackage(String packageName) {
        if (packageName == null || !PACKAGE.matcher(packageName).matches()) throw new IllegalArgumentException("Invalid compatibility package");
    }
}
