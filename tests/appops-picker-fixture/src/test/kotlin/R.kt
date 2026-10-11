package org.androidcontrol.app
object R { object string {
    const val appops_all_groups = 2131820544
    const val appops_all_ops = 2131820545
    const val appops_allow = 2131820546
    const val appops_applied = 2131820547
    const val appops_apps_by_op = 2131820548
    const val appops_apps_loaded = 2131820549
    const val appops_auto_new = 2131820550
    const val appops_auto_result = 2131820551
    const val appops_backup = 2131820552
    const val appops_choose_app = 2131820553
    const val appops_default = 2131820554
    const val appops_deny = 2131820555
    const val appops_effective_mode = 2131820556
    const val appops_error = 2131820557
    const val appops_export = 2131820558
    const val appops_exported = 2131820559
    const val appops_filter = 2131820560
    const val appops_foreground = 2131820561
    const val appops_group = 2131820562
    const val appops_ignore = 2131820563
    const val appops_import = 2131820564
    const val appops_import_confirm = 2131820565
    const val appops_last_access = 2131820566
    const val appops_last_reject = 2131820567
    const val appops_no_apps = 2131820568
    const val appops_refresh = 2131820569
    const val appops_reset = 2131820570
    const val appops_reset_confirm = 2131820571
    const val appops_restrict = 2131820572
    const val appops_restrict_confirm = 2131820573
    const val appops_rule_user = 2131820574
    const val appops_rules = 2131820575
    const val appops_rules_help = 2131820576
    const val appops_rules_saved = 2131820577
    const val appops_save = 2131820578
    const val appops_search_apps = 2131820579
    const val appops_select_ops = 2131820580
    const val appops_selection = 2131820581
    const val appops_start_hint = 2131820582
    const val appops_title = 2131820583
    const val appops_uid_override = 2131820584
    const val appops_unknown = 2131820585
    const val appops_user = 2131820586
    const val appops_user_number = 2131820587
    const val appops_working = 2131820588
} }
object FixtureStrings { val values = mapOf(
    R.string.appops_all_groups to "All permission groups",
    R.string.appops_all_ops to "Show all operations",
    R.string.appops_allow to "Allow",
    R.string.appops_applied to "Applied %1\$d changes.",
    R.string.appops_apps_by_op to "Find apps by operation",
    R.string.appops_apps_loaded to "%1\$d apps for user %2\$d",
    R.string.appops_auto_new to "Automatically restrict new apps",
    R.string.appops_auto_result to "Last new app: %1\$s · applied %2\$d · failed %3\$d",
    R.string.appops_backup to "Backup",
    R.string.appops_choose_app to "Choose app",
    R.string.appops_default to "Default",
    R.string.appops_deny to "Deny",
    R.string.appops_effective_mode to "Effective AppOps mode: %1\$s",
    R.string.appops_error to "AppOps error: %1\$s",
    R.string.appops_export to "Export selected user’s package settings",
    R.string.appops_exported to "Exported package settings for %1\$d apps.",
    R.string.appops_filter to "Filter operations or permissions",
    R.string.appops_foreground to "Foreground only",
    R.string.appops_group to "Permission group",
    R.string.appops_ignore to "Ignore",
    R.string.appops_import to "Import AndroidControl or AppOpsX backup",
    R.string.appops_import_confirm to "Apply %1\$d changes from %2\$s to user %3\$d? Import changes the listed package modes only. Operations sharing a switch change together. AndroidControl reports each failed change.",
    R.string.appops_last_access to "Last allowed access: %1\$s",
    R.string.appops_last_reject to "Last rejected access: %1\$s",
    R.string.appops_no_apps to "No apps found. Check the privileged service and selected user.",
    R.string.appops_refresh to "Refresh",
    R.string.appops_reset to "Reset package defaults",
    R.string.appops_reset_confirm to "Restore recorded package operations for %1\$s to Android defaults in user %2\$d? UID-wide rules and runtime permission grants will stay unchanged.",
    R.string.appops_restrict to "Restrict selected",
    R.string.appops_restrict_confirm to "Set %1\$d selected operations to Ignore for %2\$s? Operations sharing a switch change together. This can interrupt app features.",
    R.string.appops_rule_user to "New-app rules · user %1\$d",
    R.string.appops_rules to "New-app rules",
    R.string.appops_rules_help to "Choose operations to set to Ignore for newly installed apps in the selected user. While enabled, the privileged AppOps service checks every five seconds. Existing apps, app updates and AndroidControl are excluded. Rules run only while that service is running. Saving replaces the previous user’s automatic rule.",
    R.string.appops_rules_saved to "Rules saved.",
    R.string.appops_save to "Save",
    R.string.appops_search_apps to "Search app name or package",
    R.string.appops_select_ops to "Tick the operations to restrict first.",
    R.string.appops_selection to "%1\$s · user %2\$d",
    R.string.appops_start_hint to "Start the AndroidControl privileged service, then choose an app.",
    R.string.appops_title to "App operations",
    R.string.appops_uid_override to "The package setting was saved, but a UID-level rule changes its effective mode. AndroidControl leaves UID rules unchanged because they can affect other apps sharing that UID. Allow also cannot grant a missing Android runtime permission.",
    R.string.appops_unknown to "Unknown mode",
    R.string.appops_user to "User",
    R.string.appops_user_number to "User %1\$d",
    R.string.appops_working to "Working…",
) }
