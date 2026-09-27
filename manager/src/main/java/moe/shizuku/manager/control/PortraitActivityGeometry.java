package moe.shizuku.manager.control;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the current configuration of an exact activity/task, never a neighbour. */
final class PortraitActivityGeometry {
    private static final Pattern HEADER = Pattern.compile("^\\s*\\* Hist #\\d+: ActivityRecord\\{.*");
    private static final Pattern BOUNDS = Pattern.compile(
            "mAppBounds=Rect\\((-?\\d+),\\s*(-?\\d+)\\s*-\\s*(-?\\d+),\\s*(-?\\d+)\\)");
    final int width;
    final int height;
    final String orientation;

    private PortraitActivityGeometry(int width, int height, String orientation) {
        this.width = width;
        this.height = height;
        this.orientation = orientation;
    }

    static PortraitActivityGeometry parse(String dump, String packageName, int taskId) {
        // Android uses ICU: unlike desktop OpenJDK, it rejects an unescaped '}'.
        Pattern identity = Pattern.compile(Pattern.quote(packageName) + "/\\S+\\s+t" + taskId + "(?:\\s|\\})");
        boolean inActivity = false;
        int headerIndent = -1;
        for (String line : dump.split("\\r?\\n")) {
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
            if (HEADER.matcher(line).matches()) {
                inActivity = identity.matcher(line).find();
                headerIndent = indent;
                continue;
            }
            if (!line.trim().isEmpty() && indent <= headerIndent) inActivity = false;
            if (!inActivity || !line.trim().startsWith("CurrentConfiguration=")) continue;
            Matcher bounds = BOUNDS.matcher(line);
            if (!bounds.find()) continue;
            int width = Integer.parseInt(bounds.group(3)) - Integer.parseInt(bounds.group(1));
            int height = Integer.parseInt(bounds.group(4)) - Integer.parseInt(bounds.group(2));
            if (width <= 0 || height <= 0) continue;
            Matcher orientation = Pattern.compile("\\b(port|land)\\b").matcher(line);
            return new PortraitActivityGeometry(width, height,
                    orientation.find() ? orientation.group(1) : null);
        }
        return null;
    }
}
