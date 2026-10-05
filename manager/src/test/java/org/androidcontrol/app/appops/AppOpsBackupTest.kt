package org.androidcontrol.app.appops

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsBackupTest {
    private val catalog = JSONArray("""[
        {"code":0,"switch":0,"name":"android:coarse_location"},
        {"code":1,"switch":0,"name":"android:fine_location"},
        {"code":26,"switch":26,"name":"android:camera"},
        {"code":27,"switch":27,"name":"android:record_audio"}
    ]""")
    private fun native(ops: String) = """{"format":"androidcontrol-appops","version":2,
        "apps":[{"package":"example.app","ops":$ops}]}"""
    private fun reject(text: String) {
        assertTrue("Unsafe backup was accepted", runCatching { AppOpsBackup.parse(text, catalog) }.isFailure)
    }

    @Test fun nativeNamesPreserveModes() {
        val changes = AppOpsBackup.parse(native("""[{"name":"android:camera","mode":4}]"""), catalog)
        assertEquals(listOf(AppOpsBackup.Change("example.app", "android:camera", 4)), changes)
    }
    @Test fun legacyIdsImportAsIgnore() {
        val text = """{"v":1,"opbacks":[{"pkg":"example.app","ops":"26, 27"}]}"""
        assertEquals(listOf(AppOpsBackup.Change("example.app", "android:camera", 1),
            AppOpsBackup.Change("example.app", "android:record_audio", 1)), AppOpsBackup.parse(text, catalog))
    }
    @Test fun sharedSwitchesCollapseIdenticalModes() {
        val text = """{"v":1,"opbacks":[{"pkg":"example.app","ops":"0,1"}]}"""
        assertEquals(listOf(AppOpsBackup.Change("example.app", "android:coarse_location", 1)), AppOpsBackup.parse(text, catalog))
    }
    @Test fun conflictingSwitchesAreRejected() {
        reject(native("""[{"name":"android:coarse_location","mode":0},{"name":"android:fine_location","mode":1}]"""))
    }
    @Test fun duplicateLegacyEntriesAreRejected() {
        reject("""{"v":1,"opbacks":[{"pkg":"example.app","ops":"26,26"}]}""")
    }
    @Test fun malformedModeIsRejected() { reject(native("""[{"name":"android:camera","mode":99}]""")) }
    @Test fun unavailableOperationsAreRejected() { reject(native("""[{"name":"android:missing","mode":1}]""")) }
    @Test fun futureLegacyIdsAreRejected() { reject("""{"v":1,"opbacks":[{"pkg":"example.app","ops":"78"}]}""") }
    @Test fun invalidPackageIsRejected() { reject("""{"v":1,"opbacks":[{"pkg":"example.app;other","ops":"26"}]}""") }
    @Test fun oversizeInputIsRejected() { reject(" ".repeat(AppOpsBackup.MAX_BYTES + 1)) }
    @Test fun unknownVersionIsRejected() { reject(native("[]").replace("\"version\":2", "\"version\":3")) }
    @Test fun androidSystemPackageIsAccepted() {
        val text = """{"v":1,"opbacks":[{"pkg":"android","ops":"26"}]}"""
        assertEquals("android", AppOpsBackup.parse(text, catalog).single().pkg)
    }
}
