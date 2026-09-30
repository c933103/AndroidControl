package moe.shizuku.manager.control

import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings

object PortraitTarget {
    const val DEFAULT_PACKAGE = "game.qualiarts.hololive.dreams.jp"
    private const val PREFERENCE = "portrait_target_package"

    fun validate(value: String): String {
        val name = value.trim()
        require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(name)) {
            "Enter an Android package name, for example org.example.app"
        }
        require(name != BuildConfig.APPLICATION_ID) { "AndroidControl cannot target itself" }
        return name
    }

    fun get(): String = ShizukuSettings.getPreferences().getString(PREFERENCE, DEFAULT_PACKAGE)!!

    fun save(value: String) {
        val name = validate(value)
        check(ShizukuSettings.getPreferences().edit().putString(PREFERENCE, name).commit()) {
            "Could not save the target package"
        }
    }
}
