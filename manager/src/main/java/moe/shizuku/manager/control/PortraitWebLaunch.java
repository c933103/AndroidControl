package moe.shizuku.manager.control;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the top activity's own launch record, never another task's URL. */
public final class PortraitWebLaunch {
    public final String activityId;
    public final String url;

    private PortraitWebLaunch(String activityId, String url) {
        this.activityId = activityId;
        this.url = url;
    }

    public static boolean isWebUrl(String value) {
        if (value == null || value.length() > 16384) return false;
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()
                    && uri.getRawUserInfo() == null;
        } catch (Exception invalid) { return false; }
    }

    public static PortraitWebLaunch find(String dump, int taskId, String component, String caller) {
        // No dynamic regex fragments: package/component text is compared literally.
        Pattern header = Pattern.compile("^\\s*\\*\\s+Hist\\s+#\\d+:\\s+ActivityRecord\\{([0-9a-f]+) u\\d+ (\\S+) t(\\d+)(?:[^}]*?)\\}.*$");
        String id = null;
        int headerIndent = -1;
        boolean owner = false;
        String url = null;
        for (String line : dump.split("\\n")) {
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
            Matcher h = header.matcher(line);
            if (h.matches()) {
                if (id != null) break;
                if (h.group(2).equals(component) && h.group(3).equals(Integer.toString(taskId))) {
                    id = h.group(1);
                    headerIndent = indent;
                }
                continue;
            }
            if (id == null) continue;
            if (!line.trim().isEmpty() && indent <= headerIndent) break;
            for (String token : line.trim().split("\\s+")) {
                if (token.equals("launchedFromPackage=" + caller)) owner = true;
            }
            if (line.trim().startsWith("Intent {") && line.contains("act=android.intent.action.VIEW ")) {
                for (String token : line.trim().split("\\s+")) {
                    if (token.startsWith("dat=")) url = token.substring(4);
                }
            }
        }
        return owner && isWebUrl(url) ? new PortraitWebLaunch(id, url) : null;
    }
}
