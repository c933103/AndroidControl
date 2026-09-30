package moe.shizuku.manager.control;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Must run through app_process on Android, not the desktop JVM. */
public final class AndroidPortraitRegexTest {
    public static void main(String[] args) {
        if (!"Android Runtime".equals(System.getProperty("java.runtime.name"))) {
            throw new AssertionError("This regression requires the actual Android runtime");
        }
        String oldPattern = Pattern.quote("org.androidcontrol.regression.target")
                + "/\\S+\\s+t58934(?:\\s|})";
        boolean reproduced = false;
        try {
            Pattern.compile(oldPattern);
        } catch (PatternSyntaxException expected) {
            reproduced = true;
        }
        if (!reproduced) throw new AssertionError("Did not reproduce the reported r1203 failure");
        System.out.println("Reproduced r1203 regex failure on Android");
        PortraitActivityGeometryTest.main(args);
        System.out.println("Fixed production parser passed on Android");
    }
}
