package org.androidcontrol.app.appops

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppOpsNameCompatibilityTest {
    private val catalog = JSONArray("""[
        {"code":17,"switch":17,"name":"android:receive_emergency_broadcast"},
        {"code":26,"switch":26,"name":"android:camera"},
        {"code":42,"switch":42,"name":"android:monitor_location_high_power"},
        {"code":51,"switch":51,"name":"android:read_phone_state"},
        {"code":61,"switch":61,"name":"android:turn_screen_on"}
    ]""")
    private fun backup(ops: String) = """{"format":"androidcontrol-appops","version":2,
        "apps":[{"package":"example.app","ops":$ops}]}"""

    @Test fun android7BackupImportsOnModernCatalog() {
        val changes = AppOpsBackup.parse(backup("""[
            {"name":"CAMERA","mode":1},
            {"name":"RECEIVE_EMERGECY_SMS","mode":0},
            {"name":"MONITOR_HIGH_POWER_LOCATION","mode":1},
            {"name":"OP_READ_PHONE_STATE","mode":2},
            {"name":"TURN_ON_SCREEN","mode":3}
        ]"""), catalog)
        assertEquals(listOf(
            AppOpsBackup.Change("example.app", "android:camera", 1),
            AppOpsBackup.Change("example.app", "android:receive_emergency_broadcast", 0),
            AppOpsBackup.Change("example.app", "android:monitor_location_high_power", 1),
            AppOpsBackup.Change("example.app", "android:read_phone_state", 2),
            AppOpsBackup.Change("example.app", "android:turn_screen_on", 3)), changes)
    }

    @Test fun aliasesCannotHideDuplicateWrites() {
        assertTrue(runCatching { AppOpsBackup.parse(backup("""[
            {"name":"CAMERA","mode":1},{"name":"android:camera","mode":0}
        ]"""), catalog) }.isFailure)
    }

    @Test fun savedSelectionsSurviveAnOsUpgrade() {
        val selected = JSONArray("""["CAMERA","MONITOR_HIGH_POWER_LOCATION","OP_READ_PHONE_STATE"]""")
        val available = (0 until catalog.length()).map { catalog.getJSONObject(it).getString("name") }.toSet()
        assertEquals("""["android:camera","android:monitor_location_high_power","android:read_phone_state"]""",
            AppOpsNames.normalizeSelection(selected, available).toString())
        assertTrue(runCatching { AppOpsNames.normalizeSelection(JSONArray("""["UNAVAILABLE_OP"]"""), available) }.isFailure)
    }

    @Test fun identifierCaseIsIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("android:write_clipboard", AppOpsNames.normalize("WRITE_CLIPBOARD"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun publicAndVendorIdentifiersKeepTheirExactNames() {
        assertEquals("android:camera", AppOpsNames.normalize("android:camera"))
        assertEquals("vendor:CustomOperation", AppOpsNames.normalize("vendor:CustomOperation"))
    }
}
