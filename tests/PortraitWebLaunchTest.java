package org.androidcontrol.app.control;

public class PortraitWebLaunchTest {
    private static String record(String caller, int task, String action, String url) {
        return "    * Hist  #0: ActivityRecord{abc12 u0 browser/.Browser t" + task + "}\n"
            + "      launchedFromUid=123 launchedFromPackage=" + caller + " launchedFromFeature=null userId=0\n"
            + "      Intent { act=" + action + " dat=" + url + " cmp=browser/.Browser }\n";
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String url = "https://example.com/path?q=a%20b&next=2#fragment";
        String record = record("selected.app", 28, "android.intent.action.VIEW", url);
        PortraitWebLaunch link = PortraitWebLaunch.find(record, 28, "browser/.Browser", "selected.app");
        check(link != null && link.url.equals(url));
        check(PortraitWebLaunch.find(record + "      resultTo=ActivityRecord{123 u0 selected.app/.Game t28} resultWho=null resultCode=42\n",
            28, "browser/.Browser", "selected.app") == null);
        check(PortraitWebLaunch.find(record.replace("browser/.Browser t28", "browser/.Browser} t28"),
            28, "browser/.Browser", "selected.app").url.equals(url));
        check(PortraitWebLaunch.find(record, 2, "browser/.Browser", "selected.app") == null);
        check(PortraitWebLaunch.find(record, 28, "browser/.Browser", "selectedXapp") == null);
        check(PortraitWebLaunch.find(record, 28, "browser/.Other", "selected.app") == null);
        check(PortraitWebLaunch.find(record("other.app", 28, "android.intent.action.VIEW", url)
            + record, 28, "browser/.Browser", "selected.app") == null);
        check(PortraitWebLaunch.find(record("selected.app", 28, "billing.ACTION", url), 28,
            "browser/.Browser", "selected.app") == null);
        check(PortraitWebLaunch.find("    * Hist #0: ActivityRecord{abc12 u0 browser/.Browser t28}\n"
            + "  Task: unrelated\n" + "      launchedFromPackage=selected.app\n"
            + "      Intent { act=android.intent.action.VIEW dat=" + url + " }\n", 28,
            "browser/.Browser", "selected.app") == null);
        for (String bad : new String[]{"javascript:alert(1)", "intent://x", "file:///private", "content://private", "https://", "https://user:pass@example.com"}) {
            check(!PortraitWebLaunch.isWebUrl(bad));
        }
        check(PortraitWebLaunch.isWebUrl("http://127.0.0.1:1234/test"));
        System.out.println("Portrait web launch scope and full URL parsing passed");
    }
}
