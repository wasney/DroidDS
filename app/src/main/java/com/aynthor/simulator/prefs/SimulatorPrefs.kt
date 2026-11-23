package com.aynthor.simulator.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class SimulatorPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ayn_thor_prefs", Context.MODE_PRIVATE)

    companion object {
        const val MODE_PRIMARY = 0 // D-Pad or ABXY
        const val MODE_STICK = 1   // Left Stick or Right Stick
        const val MODE_BOTH = 2    // Both shown simultaneously

        const val FACE_LAYOUT_XBOX = 0     // Bottom=A, Right=B, Left=X, Top=Y
        const val FACE_LAYOUT_NINTENDO = 1 // Bottom=B, Right=A, Left=Y, Top=X

        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2

        const val SORT_AZ = 0
        const val SORT_ZA = 1
        const val SORT_RECENT = 2

        const val ORIENTATION_LANDSCAPE = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_AUTO = 2
        const val ORIENTATION_SYSTEM = 3

        const val LAYOUT_2_LAYER = 0
        const val LAYOUT_3_LAYER = 1

        const val SHOULDER_ADJACENT = 0
        const val SHOULDER_STACKED = 1

        const val STACKED_ORDER_2_OVER_1 = 0 // L2 over L1, R2 over R1
        const val STACKED_ORDER_1_OVER_2 = 1 // L1 over L2, R1 over R2

        private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
        private const val KEY_SPLIT_PERCENT = "split_percent"
        private const val KEY_TOP_ASPECT_RATIO = "top_aspect_ratio"
        private const val KEY_BOTTOM_ASPECT_RATIO = "bottom_aspect_ratio"
        private const val KEY_SHOW_RECENT_ROW = "show_recent_row"
        private const val KEY_ORIENTATION_MODE = "orientation_mode"
        private const val KEY_SHOW_CONTROL_CENTER = "show_control_center"
        private const val KEY_CC_BRIGHTNESS = "cc_show_brightness"
        private const val KEY_CC_VOLUME = "cc_show_volume"
        private const val KEY_CC_WIFI = "cc_show_wifi"
        private const val KEY_CC_BLUETOOTH = "cc_show_bluetooth"
        private const val KEY_CC_SLEEP_BOTTOM = "cc_show_sleep_bottom"
        private const val KEY_GAMEPAD_OPACITY = "gamepad_opacity"
        private const val KEY_GAMEPAD_SCALE = "gamepad_scale"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_SHOW_L3_R3 = "show_l3_r3"
        private const val KEY_USE_KERNEL_GAMEPAD = "use_kernel_gamepad"
        private const val KEY_DPAD_FOUR_WAY = "dpad_four_way"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag"
        private const val KEY_LANDSCAPE_LAYOUT_MODE = "landscape_layout_mode"
        private const val KEY_PORTRAIT_LAYOUT_MODE = "portrait_layout_mode"
        private const val KEY_LANDSCAPE_SHOULDER_LAYOUT = "landscape_shoulder_layout"
        private const val KEY_PORTRAIT_SHOULDER_LAYOUT = "portrait_shoulder_layout"
        private const val KEY_LANDSCAPE_STACKED_ORDER = "landscape_stacked_order"
        private const val KEY_PORTRAIT_STACKED_ORDER = "portrait_stacked_order"
        private const val KEY_CUSTOM_LAYOUT_LANDSCAPE = "custom_layout_landscape"
        private const val KEY_CUSTOM_LAYOUT_PORTRAIT = "custom_layout_portrait"
        private const val KEY_PROFILE_PACKAGES = "app_profile_packages"
        private const val PREFIX_APP_PROFILE = "app_profile_"

        const val PROFILE_TOUCH_DEFAULT = 0
        const val PROFILE_TOUCH_FORCE_SHOW = 1
        const val PROFILE_TOUCH_FORCE_HIDE = 2
        private const val KEY_SHOW_ALL_APPS = "show_all_apps"
        private const val KEY_TOUCH_CONTROLS = "touch_controls_enabled"
        private const val KEY_LEFT_CONTROL_MODE = "left_control_mode"
        private const val KEY_RIGHT_CONTROL_MODE = "right_control_mode"
        private const val KEY_TOP_TOUCH_FORWARDING = "top_touch_forwarding"
        private const val KEY_FACE_LAYOUT = "face_button_layout"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TOP_SORT_MODE = "top_sort_mode"
        private const val KEY_BOTTOM_SORT_MODE = "bottom_sort_mode"
        private const val KEY_SHOW_HOME_CLOCK = "show_home_clock"
        private const val KEY_SHOW_HOME_PERF = "show_home_perf"
        private const val KEY_SHOW_HOME_SHORTCUTS = "show_home_shortcuts"
        private const val KEY_FOLDERS = "custom_folders"
        private const val PREFIX_FOLDER_APPS = "folder_apps_"
        private const val PREFIX_LAST_LAUNCH = "last_launch_"
        private const val KEY_DUAL_SCREEN_ADDED = "dual_screen_added"
        private const val KEY_DUAL_SCREEN_REMOVED = "dual_screen_removed"

        val DEFAULT_DUAL_SCREEN_PACKAGES = setOf(
            "com.seedlessds.app",
            "com.dsemu.drastic",
            "io.github.azahar_emu.azahar",
            "org.citra.citra_emu",
            "org.lime3ds.android",
            "me.magnum.melonds",
            "info.cemu.cemu",
            "dev.nxnative.android.peanuts"
        )
    }

    fun getHiddenPackages(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setPackageHidden(packageName: String, hidden: Boolean) {
        val updated = getHiddenPackages().toMutableSet()
        if (hidden) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, updated).apply()
    }

    fun isShowAllApps(): Boolean = prefs.getBoolean(KEY_SHOW_ALL_APPS, false)

    fun setShowAllApps(showAll: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ALL_APPS, showAll).apply()
    }

    fun isTouchControlsEnabled(): Boolean = prefs.getBoolean(KEY_TOUCH_CONTROLS, true)

    fun setTouchControlsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOUCH_CONTROLS, enabled).apply()
    }

    fun getLeftControlMode(): Int = prefs.getInt(KEY_LEFT_CONTROL_MODE, MODE_PRIMARY)

    fun setLeftControlMode(mode: Int) {
        prefs.edit().putInt(KEY_LEFT_CONTROL_MODE, mode).apply()
    }

    fun getRightControlMode(): Int = prefs.getInt(KEY_RIGHT_CONTROL_MODE, MODE_PRIMARY)

    fun setRightControlMode(mode: Int) {
        prefs.edit().putInt(KEY_RIGHT_CONTROL_MODE, mode).apply()
    }

    fun isTopTouchForwardingEnabled(): Boolean = prefs.getBoolean(KEY_TOP_TOUCH_FORWARDING, true)

    fun setTopTouchForwardingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOP_TOUCH_FORWARDING, enabled).apply()
    }

    fun getFaceButtonLayout(): Int = prefs.getInt(KEY_FACE_LAYOUT, FACE_LAYOUT_XBOX)

    fun setFaceButtonLayout(layout: Int) {
        prefs.edit().putInt(KEY_FACE_LAYOUT, layout).apply()
    }

    fun getThemeMode(): Int = prefs.getInt(KEY_THEME_MODE, THEME_DARK)

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    fun getTopSortMode(): Int = prefs.getInt(KEY_TOP_SORT_MODE, SORT_AZ)

    fun setTopSortMode(mode: Int) {
        prefs.edit().putInt(KEY_TOP_SORT_MODE, mode).apply()
    }

    fun getBottomSortMode(): Int = prefs.getInt(KEY_BOTTOM_SORT_MODE, SORT_AZ)

    fun setBottomSortMode(mode: Int) {
        prefs.edit().putInt(KEY_BOTTOM_SORT_MODE, mode).apply()
    }

    fun isShowHomeClock(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_CLOCK, true)

    fun setShowHomeClock(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_CLOCK, show).apply()
    }

    fun isShowHomePerf(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_PERF, true)

    fun setShowHomePerf(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_PERF, show).apply()
    }

    fun isShowHomeShortcuts(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_SHORTCUTS, true)

    fun setShowHomeShortcuts(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_SHORTCUTS, show).apply()
    }

    fun getFolders(): List<String> {
        val set = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        return set.sortedBy { it.lowercase() }
    }

    fun addFolder(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val updated = (prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
        updated.add(clean)
        prefs.edit().putStringSet(KEY_FOLDERS, updated).apply()
    }

    fun removeFolder(name: String) {
        val updated = (prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
        updated.remove(name)
        prefs.edit()
            .putStringSet(KEY_FOLDERS, updated)
            .remove(PREFIX_FOLDER_APPS + name)
            .apply()
    }

    fun renameFolder(oldName: String, newName: String) {
        val cleanNew = newName.trim()
        if (cleanNew.isEmpty() || cleanNew == oldName) return
        val updated = (prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
        if (!updated.remove(oldName)) return
        updated.add(cleanNew)
        val mergedApps = getAppsInFolder(cleanNew) + getAppsInFolder(oldName)
        prefs.edit()
            .putStringSet(KEY_FOLDERS, updated)
            .remove(PREFIX_FOLDER_APPS + oldName)
            .putStringSet(PREFIX_FOLDER_APPS + cleanNew, mergedApps)
            .apply()
    }

    fun getAppsInFolder(folder: String): Set<String> {
        return prefs.getStringSet(PREFIX_FOLDER_APPS + folder, emptySet())?.toSet() ?: emptySet()
    }

    fun setAppInFolder(folder: String, packageName: String, inFolder: Boolean) {
        val updated = getAppsInFolder(folder).toMutableSet()
        if (inFolder) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(PREFIX_FOLDER_APPS + folder, updated).apply()
    }

    fun recordAppLaunch(packageName: String) {
        prefs.edit().putLong(PREFIX_LAST_LAUNCH + packageName, System.currentTimeMillis()).apply()
    }

    fun getLastLaunchTime(packageName: String): Long {
        return prefs.getLong(PREFIX_LAST_LAUNCH + packageName, 0L)
    }

    fun isDualScreenApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val added = prefs.getStringSet(KEY_DUAL_SCREEN_ADDED, emptySet()) ?: emptySet()
        if (added.contains(packageName)) return true
        val removed = prefs.getStringSet(KEY_DUAL_SCREEN_REMOVED, emptySet()) ?: emptySet()
        if (removed.contains(packageName)) return false
        return DEFAULT_DUAL_SCREEN_PACKAGES.contains(packageName)
    }

    fun setDualScreenApp(packageName: String, enabled: Boolean) {
        val added = (prefs.getStringSet(KEY_DUAL_SCREEN_ADDED, emptySet()) ?: emptySet()).toMutableSet()
        val removed = (prefs.getStringSet(KEY_DUAL_SCREEN_REMOVED, emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) {
            added.add(packageName)
            removed.remove(packageName)
        } else {
            added.remove(packageName)
            removed.add(packageName)
        }
        prefs.edit()
            .putStringSet(KEY_DUAL_SCREEN_ADDED, added)
            .putStringSet(KEY_DUAL_SCREEN_REMOVED, removed)
            .apply()
    }

    fun getSplitPercent(): Float = prefs.getFloat(KEY_SPLIT_PERCENT, 0.53f).coerceIn(0.40f, 0.68f)

    fun setSplitPercent(pct: Float) {
        prefs.edit().putFloat(KEY_SPLIT_PERCENT, pct.coerceIn(0.40f, 0.68f)).apply()
    }

    fun getTopAspectRatio(): String = prefs.getString(KEY_TOP_ASPECT_RATIO, "16:9") ?: "16:9"

    fun setTopAspectRatio(ratio: String) {
        prefs.edit().putString(KEY_TOP_ASPECT_RATIO, ratio).apply()
    }

    fun getBottomAspectRatio(): String = prefs.getString(KEY_BOTTOM_ASPECT_RATIO, "1240:1080") ?: "1240:1080"

    fun setBottomAspectRatio(ratio: String) {
        prefs.edit().putString(KEY_BOTTOM_ASPECT_RATIO, ratio).apply()
    }

    fun isShowRecentRow(): Boolean = prefs.getBoolean(KEY_SHOW_RECENT_ROW, true)

    fun setShowRecentRow(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_RECENT_ROW, show).apply()
    }

    fun getOrientationMode(): Int = prefs.getInt(KEY_ORIENTATION_MODE, ORIENTATION_LANDSCAPE)

    fun setOrientationMode(mode: Int) {
        prefs.edit().putInt(KEY_ORIENTATION_MODE, mode).apply()
    }

    // --- Quick Control Center toggles ---
    fun isShowControlCenter(): Boolean = prefs.getBoolean(KEY_SHOW_CONTROL_CENTER, true)
    fun setShowControlCenter(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_CONTROL_CENTER, show).apply()

    fun isShowCcBrightness(): Boolean = prefs.getBoolean(KEY_CC_BRIGHTNESS, true)
    fun setShowCcBrightness(show: Boolean) = prefs.edit().putBoolean(KEY_CC_BRIGHTNESS, show).apply()

    fun isShowCcVolume(): Boolean = prefs.getBoolean(KEY_CC_VOLUME, true)
    fun setShowCcVolume(show: Boolean) = prefs.edit().putBoolean(KEY_CC_VOLUME, show).apply()

    fun isShowCcWifi(): Boolean = prefs.getBoolean(KEY_CC_WIFI, true)
    fun setShowCcWifi(show: Boolean) = prefs.edit().putBoolean(KEY_CC_WIFI, show).apply()

    fun isShowCcBluetooth(): Boolean = prefs.getBoolean(KEY_CC_BLUETOOTH, true)
    fun setShowCcBluetooth(show: Boolean) = prefs.edit().putBoolean(KEY_CC_BLUETOOTH, show).apply()

    fun isShowCcSleepBottom(): Boolean = prefs.getBoolean(KEY_CC_SLEEP_BOTTOM, true)
    fun setShowCcSleepBottom(show: Boolean) = prefs.edit().putBoolean(KEY_CC_SLEEP_BOTTOM, show).apply()

    // --- Virtual Gamepad customization ---
    fun getGamepadOpacity(): Float = prefs.getFloat(KEY_GAMEPAD_OPACITY, 1.0f).coerceIn(0.25f, 1.0f)
    fun setGamepadOpacity(opacity: Float) = prefs.edit().putFloat(KEY_GAMEPAD_OPACITY, opacity.coerceIn(0.25f, 1.0f)).apply()

    fun getGamepadScale(): Float = prefs.getFloat(KEY_GAMEPAD_SCALE, 1.0f).coerceIn(0.75f, 1.25f)
    fun setGamepadScale(scale: Float) = prefs.edit().putFloat(KEY_GAMEPAD_SCALE, scale.coerceIn(0.75f, 1.25f)).apply()

    fun isHapticsEnabled(): Boolean = prefs.getBoolean(KEY_HAPTICS_ENABLED, false)
    fun setHapticsEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()

    fun isShowL3R3(): Boolean = prefs.getBoolean(KEY_SHOW_L3_R3, true)
    fun setShowL3R3(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_L3_R3, show).apply()

    fun isUseKernelGamepad(): Boolean = prefs.getBoolean(KEY_USE_KERNEL_GAMEPAD, true)
    fun setUseKernelGamepad(enabled: Boolean) = prefs.edit().putBoolean(KEY_USE_KERNEL_GAMEPAD, enabled).apply()

    fun isDpadFourWayOnly(): Boolean = prefs.getBoolean(KEY_DPAD_FOUR_WAY, false)
    fun setDpadFourWayOnly(enabled: Boolean) = prefs.edit().putBoolean(KEY_DPAD_FOUR_WAY, enabled).apply()

    // --- Per-App Launch & Layout Profiles ---
    data class AppProfile(
        val splitPercent: Float? = null,
        val orientationMode: Int? = null,
        val touchOverride: Int = PROFILE_TOUCH_DEFAULT,
        val pairedBottomPackage: String? = null
    )

    fun getAppProfile(packageName: String): AppProfile? {
        val raw = prefs.getString(PREFIX_APP_PROFILE + packageName, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            AppProfile(
                splitPercent = if (obj.has("split") && !obj.isNull("split")) obj.getDouble("split").toFloat() else null,
                orientationMode = if (obj.has("orient") && !obj.isNull("orient")) obj.getInt("orient") else null,
                touchOverride = obj.optInt("touch", PROFILE_TOUCH_DEFAULT),
                pairedBottomPackage = obj.optString("paired", "").takeIf { it.isNotBlank() }
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun setAppProfile(packageName: String, profile: AppProfile?) {
        val pkgs = (prefs.getStringSet(KEY_PROFILE_PACKAGES, emptySet()) ?: emptySet()).toMutableSet()
        val editor = prefs.edit()
        if (profile == null || (profile.splitPercent == null && profile.orientationMode == null &&
                profile.touchOverride == PROFILE_TOUCH_DEFAULT && profile.pairedBottomPackage.isNullOrBlank())
        ) {
            pkgs.remove(packageName)
            editor.remove(PREFIX_APP_PROFILE + packageName)
        } else {
            val obj = JSONObject().apply {
                if (profile.splitPercent != null) put("split", profile.splitPercent.toDouble())
                if (profile.orientationMode != null) put("orient", profile.orientationMode)
                put("touch", profile.touchOverride)
                if (!profile.pairedBottomPackage.isNullOrBlank()) put("paired", profile.pairedBottomPackage)
            }
            pkgs.add(packageName)
            editor.putString(PREFIX_APP_PROFILE + packageName, obj.toString())
        }
        editor.putStringSet(KEY_PROFILE_PACKAGES, pkgs).apply()
    }

    // --- Settings Backup (Export / Import JSON) ---
    fun exportToJson(): String {
        val root = JSONObject()
        root.put("format", "DroiDS-Backup-v1")
        root.put("faceLayout", getFaceButtonLayout())
        root.put("themeMode", getThemeMode())
        root.put("orientationMode", getOrientationMode())
        root.put("splitPercent", getSplitPercent().toDouble())
        root.put("topAspectRatio", getTopAspectRatio())
        root.put("bottomAspectRatio", getBottomAspectRatio())
        root.put("touchControls", isTouchControlsEnabled())
        root.put("leftControlMode", getLeftControlMode())
        root.put("rightControlMode", getRightControlMode())
        root.put("topTouchForwarding", isTopTouchForwardingEnabled())
        root.put("topSortMode", getTopSortMode())
        root.put("bottomSortMode", getBottomSortMode())
        root.put("showAllApps", isShowAllApps())
        root.put("showRecentRow", isShowRecentRow())
        root.put("showHomeClock", isShowHomeClock())
        root.put("showHomePerf", isShowHomePerf())
        root.put("showHomeShortcuts", isShowHomeShortcuts())
        root.put("showControlCenter", isShowControlCenter())
        root.put("ccBrightness", isShowCcBrightness())
        root.put("ccVolume", isShowCcVolume())
        root.put("ccWifi", isShowCcWifi())
        root.put("ccBluetooth", isShowCcBluetooth())
        root.put("ccSleepBottom", isShowCcSleepBottom())
        root.put("gamepadOpacity", getGamepadOpacity().toDouble())
        root.put("gamepadScale", getGamepadScale().toDouble())
        root.put("hapticsEnabled", isHapticsEnabled())
        root.put("showL3R3", isShowL3R3())
        root.put("useKernelGamepad", isUseKernelGamepad())
        root.put("dpadFourWay", isDpadFourWayOnly())
        root.put("landscapeLayoutMode", getLandscapeLayoutMode())
        root.put("portraitLayoutMode", getPortraitLayoutMode())
        root.put("landscapeShoulderLayout", getLandscapeShoulderLayout())
        root.put("portraitShoulderLayout", getPortraitShoulderLayout())
        root.put("landscapeStackedOrder", getLandscapeStackedOrder())
        root.put("portraitStackedOrder", getPortraitStackedOrder())
        root.put("autoCheckUpdates", isAutoCheckUpdatesEnabled())
        prefs.getString(KEY_CUSTOM_LAYOUT_LANDSCAPE, null)?.let { root.put("customLayoutLandscape", it) }
        prefs.getString(KEY_CUSTOM_LAYOUT_PORTRAIT, null)?.let { root.put("customLayoutPortrait", it) }

        root.put("hiddenPackages", JSONArray(getHiddenPackages().toList()))
        root.put("dualScreenAdded", JSONArray((prefs.getStringSet(KEY_DUAL_SCREEN_ADDED, emptySet()) ?: emptySet()).toList()))
        root.put("dualScreenRemoved", JSONArray((prefs.getStringSet(KEY_DUAL_SCREEN_REMOVED, emptySet()) ?: emptySet()).toList()))

        val foldersObj = JSONObject()
        for (folder in getFolders()) {
            foldersObj.put(folder, JSONArray(getAppsInFolder(folder).toList()))
        }
        root.put("folders", foldersObj)

        val profilesObj = JSONObject()
        for (pkg in (prefs.getStringSet(KEY_PROFILE_PACKAGES, emptySet()) ?: emptySet())) {
            prefs.getString(PREFIX_APP_PROFILE + pkg, null)?.let { raw ->
                try {
                    profilesObj.put(pkg, JSONObject(raw))
                } catch (_: Throwable) {
                }
            }
        }
        root.put("appProfiles", profilesObj)
        return root.toString(2)
    }

    fun importFromJson(jsonText: String): Boolean {
        return try {
            val root = JSONObject(jsonText)
            val ed = prefs.edit()
            if (root.has("faceLayout")) ed.putInt(KEY_FACE_LAYOUT, root.getInt("faceLayout"))
            if (root.has("themeMode")) ed.putInt(KEY_THEME_MODE, root.getInt("themeMode"))
            if (root.has("orientationMode")) ed.putInt(KEY_ORIENTATION_MODE, root.getInt("orientationMode"))
            if (root.has("splitPercent")) ed.putFloat(KEY_SPLIT_PERCENT, root.getDouble("splitPercent").toFloat().coerceIn(0.40f, 0.68f))
            if (root.has("topAspectRatio")) ed.putString(KEY_TOP_ASPECT_RATIO, root.getString("topAspectRatio"))
            if (root.has("bottomAspectRatio")) ed.putString(KEY_BOTTOM_ASPECT_RATIO, root.getString("bottomAspectRatio"))
            if (root.has("touchControls")) ed.putBoolean(KEY_TOUCH_CONTROLS, root.getBoolean("touchControls"))
            if (root.has("leftControlMode")) ed.putInt(KEY_LEFT_CONTROL_MODE, root.getInt("leftControlMode"))
            if (root.has("rightControlMode")) ed.putInt(KEY_RIGHT_CONTROL_MODE, root.getInt("rightControlMode"))
            if (root.has("topTouchForwarding")) ed.putBoolean(KEY_TOP_TOUCH_FORWARDING, root.getBoolean("topTouchForwarding"))
            if (root.has("topSortMode")) ed.putInt(KEY_TOP_SORT_MODE, root.getInt("topSortMode"))
            if (root.has("bottomSortMode")) ed.putInt(KEY_BOTTOM_SORT_MODE, root.getInt("bottomSortMode"))
            if (root.has("showAllApps")) ed.putBoolean(KEY_SHOW_ALL_APPS, root.getBoolean("showAllApps"))
            if (root.has("showRecentRow")) ed.putBoolean(KEY_SHOW_RECENT_ROW, root.getBoolean("showRecentRow"))
            if (root.has("showHomeClock")) ed.putBoolean(KEY_SHOW_HOME_CLOCK, root.getBoolean("showHomeClock"))
            if (root.has("showHomePerf")) ed.putBoolean(KEY_SHOW_HOME_PERF, root.getBoolean("showHomePerf"))
            if (root.has("showHomeShortcuts")) ed.putBoolean(KEY_SHOW_HOME_SHORTCUTS, root.getBoolean("showHomeShortcuts"))
            if (root.has("showControlCenter")) ed.putBoolean(KEY_SHOW_CONTROL_CENTER, root.getBoolean("showControlCenter"))
            if (root.has("ccBrightness")) ed.putBoolean(KEY_CC_BRIGHTNESS, root.getBoolean("ccBrightness"))
            if (root.has("ccVolume")) ed.putBoolean(KEY_CC_VOLUME, root.getBoolean("ccVolume"))
            if (root.has("ccWifi")) ed.putBoolean(KEY_CC_WIFI, root.getBoolean("ccWifi"))
            if (root.has("ccBluetooth")) ed.putBoolean(KEY_CC_BLUETOOTH, root.getBoolean("ccBluetooth"))
            if (root.has("ccSleepBottom")) ed.putBoolean(KEY_CC_SLEEP_BOTTOM, root.getBoolean("ccSleepBottom"))
            if (root.has("gamepadOpacity")) ed.putFloat(KEY_GAMEPAD_OPACITY, root.getDouble("gamepadOpacity").toFloat().coerceIn(0.25f, 1.0f))
            if (root.has("gamepadScale")) ed.putFloat(KEY_GAMEPAD_SCALE, root.getDouble("gamepadScale").toFloat().coerceIn(0.75f, 1.25f))
            if (root.has("hapticsEnabled")) ed.putBoolean(KEY_HAPTICS_ENABLED, root.getBoolean("hapticsEnabled"))
            if (root.has("showL3R3")) ed.putBoolean(KEY_SHOW_L3_R3, root.getBoolean("showL3R3"))
            if (root.has("useKernelGamepad")) ed.putBoolean(KEY_USE_KERNEL_GAMEPAD, root.getBoolean("useKernelGamepad"))
            if (root.has("dpadFourWay")) ed.putBoolean(KEY_DPAD_FOUR_WAY, root.getBoolean("dpadFourWay"))
            if (root.has("landscapeLayoutMode")) ed.putInt(KEY_LANDSCAPE_LAYOUT_MODE, root.getInt("landscapeLayoutMode"))
            if (root.has("portraitLayoutMode")) ed.putInt(KEY_PORTRAIT_LAYOUT_MODE, root.getInt("portraitLayoutMode"))
            if (root.has("landscapeShoulderLayout")) ed.putInt(KEY_LANDSCAPE_SHOULDER_LAYOUT, root.getInt("landscapeShoulderLayout"))
            if (root.has("portraitShoulderLayout")) ed.putInt(KEY_PORTRAIT_SHOULDER_LAYOUT, root.getInt("portraitShoulderLayout"))
            if (root.has("landscapeStackedOrder")) ed.putInt(KEY_LANDSCAPE_STACKED_ORDER, root.getInt("landscapeStackedOrder"))
            if (root.has("portraitStackedOrder")) ed.putInt(KEY_PORTRAIT_STACKED_ORDER, root.getInt("portraitStackedOrder"))
            if (root.has("autoCheckUpdates")) ed.putBoolean(KEY_AUTO_CHECK_UPDATES, root.getBoolean("autoCheckUpdates"))
            if (root.has("customLayoutLandscape")) ed.putString(KEY_CUSTOM_LAYOUT_LANDSCAPE, root.getString("customLayoutLandscape"))
            if (root.has("customLayoutPortrait")) ed.putString(KEY_CUSTOM_LAYOUT_PORTRAIT, root.getString("customLayoutPortrait"))

            fun readSet(arr: JSONArray?): Set<String> {
                if (arr == null) return emptySet()
                val out = mutableSetOf<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i))
                return out
            }

            if (root.has("hiddenPackages")) ed.putStringSet(KEY_HIDDEN_PACKAGES, readSet(root.optJSONArray("hiddenPackages")))
            if (root.has("dualScreenAdded")) ed.putStringSet(KEY_DUAL_SCREEN_ADDED, readSet(root.optJSONArray("dualScreenAdded")))
            if (root.has("dualScreenRemoved")) ed.putStringSet(KEY_DUAL_SCREEN_REMOVED, readSet(root.optJSONArray("dualScreenRemoved")))

            root.optJSONObject("folders")?.let { foldersObj ->
                val names = mutableSetOf<String>()
                val keys = foldersObj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    names.add(name)
                    ed.putStringSet(PREFIX_FOLDER_APPS + name, readSet(foldersObj.optJSONArray(name)))
                }
                ed.putStringSet(KEY_FOLDERS, names)
            }

            root.optJSONObject("appProfiles")?.let { profilesObj ->
                val pkgs = mutableSetOf<String>()
                val keys = profilesObj.keys()
                while (keys.hasNext()) {
                    val pkg = keys.next()
                    profilesObj.optJSONObject(pkg)?.let { prof ->
                        pkgs.add(pkg)
                        ed.putString(PREFIX_APP_PROFILE + pkg, prof.toString())
                    }
                }
                ed.putStringSet(KEY_PROFILE_PACKAGES, pkgs)
            }

            ed.apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun isAutoCheckUpdatesEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)

    fun setAutoCheckUpdatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, enabled).apply()
    }

    fun getSkippedUpdateTag(): String? = prefs.getString(KEY_SKIPPED_UPDATE_TAG, null)

    fun setSkippedUpdateTag(tag: String?) {
        val ed = prefs.edit()
        if (tag.isNullOrBlank()) ed.remove(KEY_SKIPPED_UPDATE_TAG) else ed.putString(KEY_SKIPPED_UPDATE_TAG, tag)
        ed.apply()
    }

    fun getLandscapeLayoutMode(): Int = prefs.getInt(KEY_LANDSCAPE_LAYOUT_MODE, LAYOUT_2_LAYER)

    fun setLandscapeLayoutMode(mode: Int) {
        prefs.edit().putInt(KEY_LANDSCAPE_LAYOUT_MODE, mode).apply()
    }

    fun getPortraitLayoutMode(): Int = prefs.getInt(KEY_PORTRAIT_LAYOUT_MODE, LAYOUT_2_LAYER)

    fun setPortraitLayoutMode(mode: Int) {
        prefs.edit().putInt(KEY_PORTRAIT_LAYOUT_MODE, mode).apply()
    }

    fun getLandscapeShoulderLayout(): Int = prefs.getInt(KEY_LANDSCAPE_SHOULDER_LAYOUT, SHOULDER_ADJACENT)

    fun setLandscapeShoulderLayout(mode: Int) {
        prefs.edit().putInt(KEY_LANDSCAPE_SHOULDER_LAYOUT, mode).apply()
    }

    fun getPortraitShoulderLayout(): Int = prefs.getInt(KEY_PORTRAIT_SHOULDER_LAYOUT, SHOULDER_ADJACENT)

    fun setPortraitShoulderLayout(mode: Int) {
        prefs.edit().putInt(KEY_PORTRAIT_SHOULDER_LAYOUT, mode).apply()
    }

    fun getLandscapeStackedOrder(): Int = prefs.getInt(KEY_LANDSCAPE_STACKED_ORDER, STACKED_ORDER_2_OVER_1)

    fun setLandscapeStackedOrder(order: Int) {
        prefs.edit().putInt(KEY_LANDSCAPE_STACKED_ORDER, order).apply()
    }

    fun getPortraitStackedOrder(): Int = prefs.getInt(KEY_PORTRAIT_STACKED_ORDER, STACKED_ORDER_2_OVER_1)

    fun setPortraitStackedOrder(order: Int) {
        prefs.edit().putInt(KEY_PORTRAIT_STACKED_ORDER, order).apply()
    }

    data class CustomItemLayout(
        val xFrac: Float,
        val yFrac: Float,
        val scale: Float = 1.0f,
        val opacity: Float = -1f,
        val baseWdp: Float = 0f,
        val baseHdp: Float = 0f
    )

    fun getCustomLayout(isPortrait: Boolean): Map<String, CustomItemLayout> {
        val key = if (isPortrait) KEY_CUSTOM_LAYOUT_PORTRAIT else KEY_CUSTOM_LAYOUT_LANDSCAPE
        val raw = prefs.getString(key, null) ?: return emptyMap()
        val out = LinkedHashMap<String, CustomItemLayout>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val item = obj.optJSONObject(id) ?: continue
                out[id] = CustomItemLayout(
                    xFrac = item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    yFrac = item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    scale = item.optDouble("s", 1.0).toFloat().coerceIn(0.60f, 1.50f),
                    opacity = item.optDouble("a", -1.0).toFloat(),
                    baseWdp = item.optDouble("bw", 0.0).toFloat().coerceIn(0f, 600f),
                    baseHdp = item.optDouble("bh", 0.0).toFloat().coerceIn(0f, 600f)
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun setCustomLayout(isPortrait: Boolean, layout: Map<String, CustomItemLayout>) {
        val key = if (isPortrait) KEY_CUSTOM_LAYOUT_PORTRAIT else KEY_CUSTOM_LAYOUT_LANDSCAPE
        if (layout.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val obj = JSONObject()
        for ((id, item) in layout) {
            obj.put(
                id,
                JSONObject().apply {
                    put("x", item.xFrac.toDouble())
                    put("y", item.yFrac.toDouble())
                    put("s", item.scale.toDouble())
                    put("a", item.opacity.toDouble())
                    if (item.baseWdp > 0f && item.baseHdp > 0f) {
                        put("bw", item.baseWdp.toDouble())
                        put("bh", item.baseHdp.toDouble())
                    }
                }
            )
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }
}
