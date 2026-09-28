package moe.shizuku.manager.control;

public final class PortraitActivityGeometryTest {
    private static final String PKG = "game.qualiarts.hololive.dreams.jp";
    private static String record(int task, String config) {
        // Actual Android 13 formatting captured by the installed-app regression.
        return "    * Hist  #0: ActivityRecord{abc u0 " + PKG + "/.Main} t" + task + "}\n"
                + "      " + config + "\n";
    }
    private static String config(int w, int h) {
        return "CurrentConfiguration={land mAppBounds=Rect(0, 0 - " + w + ", " + h + ")}";
    }
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
    public static void main(String[] args) {
        // The exact task ID from the Android 13 failure report must parse too.
        PortraitActivityGeometry reported = PortraitActivityGeometry.parse(record(58934, config(712, 1662)), PKG, 58934);
        check(reported != null && reported.width == 712 && reported.height == 1662);
        check(PortraitActivityGeometry.parse("    * Hist #0: ActivityRecord{abc u0 " + PKG
                + "/.Main t58934}\n      " + config(712, 1662), PKG, 58934) != null);
        check(PortraitActivityGeometry.parse(record(589340, config(712, 1662)), PKG, 58934) == null);
        PortraitActivityGeometry g = PortraitActivityGeometry.parse(record(12, config(712, 1662)), PKG, 12);
        check(g != null && g.width == 712 && g.height == 1662 && "land".equals(g.orientation));
        check(PortraitActivityGeometry.parse(record(123, config(712, 1662)), PKG, 12) == null);
        check(PortraitActivityGeometry.parse(record(12, "state=RESUMED")
                + record(13, config(712, 1662)), PKG, 12) == null);
        check(PortraitActivityGeometry.parse(record(12, "mLastReportedConfiguration={mAppBounds=Rect(0, 0 - 712, 1662)}"), PKG, 12) == null);
        g = PortraitActivityGeometry.parse(record(12, "mLastReportedConfiguration={mAppBounds=Rect(0, 0 - 712, 1662)}")
                + "      " + config(1662, 712), PKG, 12);
        check(g != null && g.width > g.height);
        check(PortraitActivityGeometry.parse("mResumedActivity: ActivityRecord{abc u0 " + PKG + "/.Main t12}\n"
                + record(13, config(712, 1662)), PKG, 12) == null);
        check(PortraitActivityGeometry.parse(record(12, "state=RESUMED")
                + "  Display #0\n    " + config(712, 1662), PKG, 12) == null);
        check(PortraitActivityGeometry.parse(record(12, config(0, 1662)), PKG, 12) == null);
        System.out.println("Portrait activity geometry regression checks passed");
    }
}
