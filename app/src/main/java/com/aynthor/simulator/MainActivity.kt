package com.aynthor.simulator

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.Gravity
import android.view.WindowManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.content.pm.ActivityInfo
import android.text.TextUtils
import android.widget.ImageView
import android.media.AudioManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.aynthor.simulator.databinding.ActivityMainBinding
import com.aynthor.simulator.launcher.AppInfo
import com.aynthor.simulator.launcher.DrawerItem
import com.aynthor.simulator.launcher.AppLauncherAdapter
import com.aynthor.simulator.launcher.AppRepository
import androidx.activity.OnBackPressedCallback
import com.aynthor.simulator.prefs.SimulatorPrefs
import com.aynthor.simulator.ui.SettingsSheetController
import com.aynthor.simulator.update.AppUpdater
import com.aynthor.simulator.ui.OnboardingOverlayController
import com.aynthor.simulator.ui.LayoutEditorController
import com.aynthor.simulator.service.VirtualDisplayUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 1001
        private const val TOP_WIDTH = 1920
        private const val TOP_HEIGHT = 1080
        private const val BOTTOM_WIDTH = 1240
        private const val BOTTOM_HEIGHT = 1080
        private const val VIRTUAL_DPI = 320
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SimulatorPrefs
    private lateinit var settingsSheet: SettingsSheetController
    private lateinit var appUpdater: AppUpdater
    private lateinit var onboardingOverlay: OnboardingOverlayController
    private lateinit var layoutEditor: LayoutEditorController
    private lateinit var inputManager: InputManager
    private lateinit var launcherAdapter: AppLauncherAdapter
    private lateinit var bottomLauncherAdapter: AppLauncherAdapter
    private var activeTopPackage: String? = null
    private var lastBottomPackage: String? = null
    private var hasBottomAppRunning = false
    private var lastDualScreenApp: AppInfo? = null
    private var pausedDualScreenApp: AppInfo? = null
    private var activeProfileSplit: Float? = null
    private var activeProfileOrientation: Int? = null

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(prefs.exportToJson().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Settings exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (prefs.importFromJson(text)) {
                    applyAllPreferences()
                    Toast.makeText(this, "Settings imported & applied", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid backup JSON file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val clientDeathToken = android.os.Binder()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val perfExecutor = Executors.newSingleThreadExecutor()
    private val inputExecutor = Executors.newSingleThreadExecutor()
    private val joystickRunning = AtomicBoolean(false)
    private val joystickDirty = AtomicBoolean(false)
    private var lastBottomTouchTime = 0L

    private var userService: IVirtualDisplayService? = null
    private var isServiceBound = false
    private var topSurfaceReady = false
    private var bottomSurfaceReady = false

    private var primaryDisplayId = -1    // 1920x1080
    private var secondaryDisplayId = -1
    private var currentBottomVirtualWidth = BOTTOM_WIDTH
    private var currentBottomVirtualHeight = BOTTOM_HEIGHT  // 1240x1080
    private var isSwapped = false

    private var allApps: List<AppInfo> = emptyList()
    private var physicalControllerConnected = false
    private var manualTouchOverride: Boolean? = null
    private var topSearchQuery = ""
    private var bottomSearchQuery = ""
    private var selectedTopFolder: String? = null
    private var selectedBottomFolder: String? = null
    private var lastLauncherStickNavMs = 0L
    private var isLightThemeActive = false
    private var isWindowNotFocusable = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val homeRefreshRunnable = object : Runnable {
        override fun run() {
            updateBottomHomeDashboard()
            uiHandler.postDelayed(this, 3000L)
        }
    }

    @Volatile private var leftStickX = 0f
    @Volatile private var leftStickY = 0f
    @Volatile private var rightStickX = 0f
    @Volatile private var rightStickY = 0f
    @Volatile private var hatX = 0f
    @Volatile private var hatY = 0f
    @Volatile private var lTrigger = 0f
    @Volatile private var rTrigger = 0f
    @Volatile private var stickDownTime = 0L
    private val keyDownTimes = mutableMapOf<Int, Long>()
    private val keyRepeatRunnables = mutableMapOf<Int, Runnable>()
    private val pendingKeyUpRunnables = mutableMapOf<Int, Runnable>()
    private val localLogs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun addLocalLog(msg: String) {
        localLogs.add(msg)
        while (localLogs.size > 40) {
            localLogs.removeAt(0)
        }
    }

    private val activeTopDisplayId: Int
        get() = if (isSwapped) secondaryDisplayId else primaryDisplayId

    private val activeBottomDisplayId: Int
        get() = if (isSwapped) primaryDisplayId else secondaryDisplayId

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, VirtualDisplayUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("virtual_display_service")
            .debuggable(false)
            .version(68)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IVirtualDisplayService.Stub.asInterface(binder)
            try { userService?.registerClientDeathToken(clientDeathToken) } catch (_: Throwable) {}
            isServiceBound = true
            tryCreateVirtualDisplays()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            isServiceBound = false
            primaryDisplayId = -1
            secondaryDisplayId = -1
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { checkShizukuAndBind() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            userService = null
            isServiceBound = false
            showShizukuOverlay(getString(R.string.shizuku_not_running), showAction = true, isPermission = false)
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_CODE) {
                runOnUiThread {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        checkShizukuAndBind()
                    } else {
                        showShizukuOverlay(
                            getString(R.string.shizuku_permission_needed),
                            showAction = true,
                            isPermission = true
                        )
                    }
                }
            }
        }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updatePhysicalControllerState()
        override fun onInputDeviceRemoved(deviceId: Int) = updatePhysicalControllerState()
        override fun onInputDeviceChanged(deviceId: Int) = updatePhysicalControllerState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                java.io.File(filesDir, "last_crash.txt").writeText("[$ts] Thread: ${thread.name}\n$sw")
            } catch (_: Throwable) {}
            prevHandler?.uncaughtException(thread, throwable)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SimulatorPrefs(this)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        setupFullscreen()
        binding.root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
            }
            if (::settingsSheet.isInitialized && v.height > 0) {
                settingsSheet.updateSheetHeight(v.height)
            }
            if (::onboardingOverlay.isInitialized) {
                onboardingOverlay.onLayoutSizeChanged()
            }
            if (::layoutEditor.isInitialized && !layoutEditor.isEditModeActive && v.width > 0 && v.height > 0) {
                layoutEditor.applySavedLayoutForCurrentOrientation()
            }
        }
        setupSurfaces()
        setupLauncher()
        setupUtilityBar()
        setupBottomHomeScreen()
        setupVirtualGamepad()
        setupLayoutEditor()
        setupSettingsSheet()
        setupOnboardingOverlay()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::layoutEditor.isInitialized && layoutEditor.isEditModeActive) {
                    layoutEditor.exitEditMode(save = true)
                } else if (::launcherAdapter.isInitialized && launcherAdapter.multiSelectMode) {
                    exitDrawerMultiSelect(isTop = true)
                } else if (::bottomLauncherAdapter.isInitialized && bottomLauncherAdapter.multiSelectMode) {
                    exitDrawerMultiSelect(isTop = false)
                } else if (binding.bottomLauncherOverlay.visibility == View.VISIBLE && selectedBottomFolder != null) {
                    openDrawerFolder(isTop = false, folderName = null)
                } else if (binding.launcherOverlay.visibility == View.VISIBLE && selectedTopFolder != null) {
                    openDrawerFolder(isTop = true, folderName = null)
                } else if (::onboardingOverlay.isInitialized && onboardingOverlay.isInWizardMode) {
                    onboardingOverlay.dismiss(markOnboardingDone = true)
                } else if (::settingsSheet.isInitialized && settingsSheet.isVisible) {
                    settingsSheet.dismiss()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        applyAppTheme()
        applyOrientationMode()
        applyDisplayLayout()
        updatePhysicalControllerState()
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            updateWindowFocusability()
        }
        updateWindowFocusability()
        uiHandler.post(homeRefreshRunnable)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        inputManager.registerInputDeviceListener(inputDeviceListener, null)

        appUpdater = AppUpdater(this, prefs)

        if (!prefs.isOnboardingCompleted()) {
            onboardingOverlay.refreshTheme(isLightThemeActive)
            onboardingOverlay.showWizard(startStep = 0)
        } else if (prefs.isAutoCheckUpdatesEnabled()) {
            uiHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    appUpdater.checkForUpdates(manual = false)
                }
            }, 2500L)
        }
        checkShizukuAndBind()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupFullscreen()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDisplayLayout()
        syncSurfaceMetadata()
    }

    private fun applyOrientationMode() {
        val effectiveOrient = activeProfileOrientation ?: prefs.getOrientationMode()
        requestedOrientation = when (effectiveOrient) {
            SimulatorPrefs.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            SimulatorPrefs.ORIENTATION_AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            SimulatorPrefs.ORIENTATION_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * When a physical controller (e.g. GameSir) is connected and touch controls are hidden:
     * - Automatically locks Bottom Aspect Ratio to "1240:1080" (Thor) for dual-screen emulators
     *   (SeedlessDS, DraStic, Azahar, etc.) so their Presentation never stretches.
     * - Automatically expands Bottom Aspect Ratio to "Fill" for standard top games + bottom apps
     *   (browsers, YouTube, guides, Bottom Home) for crisp full-width rendering.
     * When using on-screen touch controls, always respects the user's saved Bottom Aspect Ratio.
     */
    private fun effectiveBottomAspectRatio(): String {
        val touchVisible = manualTouchOverride ?: (!physicalControllerConnected && prefs.isTouchControlsEnabled())
        if (!touchVisible && physicalControllerConnected) {
            val isDualScreenActive = prefs.isDualScreenApp(activeTopPackage) && !hasBottomAppRunning
            return if (isDualScreenActive) "1240:1080" else "Fill"
        }
        return prefs.getBottomAspectRatio()
    }

    private fun applyDisplayLayout() {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val splitPct = activeProfileSplit ?: prefs.getSplitPercent()
        val topRatio = prefs.getTopAspectRatio()
        val bottomRatio = effectiveBottomAspectRatio()

        val layoutMode = if (isPortrait) prefs.getPortraitLayoutMode() else prefs.getLandscapeLayoutMode()
        val shoulderLayout = if (isPortrait) prefs.getPortraitShoulderLayout() else prefs.getLandscapeShoulderLayout()
        val stackedOrder = if (isPortrait) prefs.getPortraitStackedOrder() else prefs.getLandscapeStackedOrder()
        val isThreeLayer = (layoutMode == SimulatorPrefs.LAYOUT_3_LAYER)
        val isStackedShoulders = (shoulderLayout == SimulatorPrefs.SHOULDER_STACKED)

        val topLp = binding.topScreenContainer.layoutParams as ConstraintLayout.LayoutParams
        val botLp = binding.bottomScreenContainer.layoutParams as ConstraintLayout.LayoutParams
        val leftLp = binding.leftSidebar.layoutParams as ConstraintLayout.LayoutParams
        val rightLp = binding.rightSidebar.layoutParams as ConstraintLayout.LayoutParams

        val density = resources.displayMetrics.density
        fun dpPx(v: Int): Int = (v * density).toInt()

        if (isThreeLayer) {
            val touchVisible = manualTouchOverride ?: (!physicalControllerConnected && prefs.isTouchControlsEnabled())
            val controlsTop = if (touchVisible) 0.72f else 0.90f
            binding.portraitControlsGuideline.setGuidelinePercent(controlsTop)
            binding.splitGuideline.setGuidelinePercent((controlsTop * splitPct).coerceIn(0.25f, controlsTop - 0.22f))

            topLp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            topLp.bottomToTop = R.id.splitGuideline
            topLp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            topLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topLp.dimensionRatio = if (topRatio == "Fill") null else topRatio

            botLp.topToBottom = R.id.splitGuideline
            botLp.bottomToTop = R.id.portraitControlsGuideline
            botLp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            botLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            botLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            botLp.marginStart = 0
            botLp.marginEnd = 0
            botLp.dimensionRatio = if (bottomRatio == "Fill") null else bottomRatio

            leftLp.topToBottom = R.id.portraitControlsGuideline
            leftLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            leftLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            leftLp.endToStart = R.id.portraitCenterGuideline
            leftLp.endToEnd = ConstraintLayout.LayoutParams.UNSET

            rightLp.topToBottom = R.id.portraitControlsGuideline
            rightLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            rightLp.startToEnd = R.id.portraitCenterGuideline
            rightLp.startToStart = ConstraintLayout.LayoutParams.UNSET
            rightLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            // 2-Layer Layout (Top screen above split; Bottom screen flanked by left/right sidebars)
            binding.splitGuideline.setGuidelinePercent(splitPct)

            topLp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            topLp.bottomToTop = R.id.splitGuideline
            topLp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            topLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topLp.dimensionRatio = if (topRatio == "Fill") null else topRatio

            val touchVisible = manualTouchOverride ?: (!physicalControllerConnected && prefs.isTouchControlsEnabled())
            // When touch controls are hidden (e.g., physical controller like GameSir connected),
            // drop left/right sidebar margins to 0 so the bottom virtual display gets full width.
            val sidebarClearancePx = if (!touchVisible) {
                0
            } else {
                val sidebarMinDp = if (bottomRatio == "Fill") {
                    if (isStackedShoulders) 114 else 154
                } else {
                    if (isStackedShoulders) 96 else 132
                }
                dpPx(sidebarMinDp)
            }

            botLp.topToBottom = R.id.splitGuideline
            botLp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            botLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            botLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            botLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            botLp.marginStart = sidebarClearancePx
            botLp.marginEnd = sidebarClearancePx
            botLp.dimensionRatio = if (bottomRatio == "Fill") null else bottomRatio

            leftLp.topToBottom = R.id.splitGuideline
            leftLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            leftLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            leftLp.endToStart = R.id.bottomScreenContainer
            leftLp.endToEnd = ConstraintLayout.LayoutParams.UNSET

            rightLp.topToBottom = R.id.splitGuideline
            rightLp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            rightLp.startToEnd = R.id.bottomScreenContainer
            rightLp.startToStart = ConstraintLayout.LayoutParams.UNSET
            rightLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }

        binding.topScreenContainer.layoutParams = topLp
        binding.bottomScreenContainer.layoutParams = botLp
        binding.leftSidebar.layoutParams = leftLp
        binding.rightSidebar.layoutParams = rightLp

        applyShoulderButtonLayout(isStackedShoulders, stackedOrder)
        if (::layoutEditor.isInitialized) {
            binding.root.post { layoutEditor.applySavedLayoutForCurrentOrientation() }
        }
    }

    private fun applyShoulderButtonLayout(stacked: Boolean, stackedOrder: Int) {
        val density = resources.displayMetrics.density
        fun dpPx(v: Int): Int = (v * density).toInt()
        val gapPx = dpPx(4)

        fun configurePair(
            container: LinearLayout,
            firstView: View,
            secondView: View
        ) {
            // If either shoulder button has been custom-positioned in freeControlsLayer,
            // leave both untouched so we never reparent a child that already has another parent.
            if (firstView.parent !== container || secondView.parent !== container) return
            val containerLp = container.layoutParams as LinearLayout.LayoutParams
            if (stacked) {
                container.orientation = LinearLayout.VERTICAL
                containerLp.height = dpPx(68)
            } else {
                container.orientation = LinearLayout.HORIZONTAL
                containerLp.height = dpPx(36)
            }
            container.layoutParams = containerLp

            // Reorder children only when needed so all touch listeners stay intact
            if (container.childCount != 2 ||
                container.getChildAt(0) !== firstView ||
                container.getChildAt(1) !== secondView
            ) {
                container.removeAllViews()
                container.addView(firstView)
                container.addView(secondView)
            }

            if (stacked) {
                firstView.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { bottomMargin = gapPx }
                secondView.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            } else {
                firstView.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                ).apply { marginEnd = gapPx }
                secondView.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                )
            }
        }

        if (stacked) {
            if (stackedOrder == SimulatorPrefs.STACKED_ORDER_1_OVER_2) {
                configurePair(binding.leftShoulderContainer, binding.btnL1, binding.btnL2)
                configurePair(binding.rightShoulderContainer, binding.btnR1, binding.btnR2)
            } else {
                configurePair(binding.leftShoulderContainer, binding.btnL2, binding.btnL1)
                configurePair(binding.rightShoulderContainer, binding.btnR2, binding.btnR1)
            }
        } else {
            // Original adjacent order: [L2 | L1] on left, [R1 | R2] on right
            configurePair(binding.leftShoulderContainer, binding.btnL2, binding.btnL1)
            configurePair(binding.rightShoulderContainer, binding.btnR1, binding.btnR2)
        }
    }

    /**
     * Prevents Display 0 (MainActivity) from stealing key/gamepad focus from the game on
     * primaryDisplayId when the user taps virtual gamepad buttons — matching how native
     * dual-screen overlays keep input focus permanently locked to the game window.
     * Automatically clears FLAG_NOT_FOCUSABLE whenever an app drawer or Shizuku overlay is visible
     * so search boxes and controller drawer navigation still receive keyboard/key focus.
     */
    private fun updateWindowFocusability() {
        val needsFocus = binding.launcherOverlay.visibility == View.VISIBLE ||
            binding.bottomLauncherOverlay.visibility == View.VISIBLE ||
            binding.shizukuOverlay.visibility == View.VISIBLE ||
            (::settingsSheet.isInitialized && settingsSheet.isVisible) ||
            (::onboardingOverlay.isInitialized && onboardingOverlay.isVisible) ||
            (::layoutEditor.isInitialized && layoutEditor.isEditModeActive)
        val shouldBeNotFocusable = !needsFocus
        if (shouldBeNotFocusable == isWindowNotFocusable) return
        isWindowNotFocusable = shouldBeNotFocusable
        if (shouldBeNotFocusable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            if (primaryDisplayId > 0) {
                ioExecutor.execute { userService?.focusDisplay(primaryDisplayId) }
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupSurfaces() {
        binding.topSurfaceView.apply {
            virtualWidth = TOP_WIDTH
            virtualHeight = TOP_HEIGHT
            holder.setFixedSize(TOP_WIDTH, TOP_HEIGHT)
            touchForwardingEnabled = prefs.isTopTouchForwardingEnabled()
            onTouchInject = { displayId, action, downTime, eventTime, count, ids, xs, ys, pressures ->
                inputExecutor.execute {
                    userService?.injectTouchEvent(
                        displayId, action, downTime, eventTime, count, ids, xs, ys, pressures
                    )
                    if (displayId == secondaryDisplayId &&
                        (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP
                    ) {
                        lastBottomTouchTime = SystemClock.uptimeMillis()
                        userService?.focusDisplay(primaryDisplayId)
                    }
                }
            }
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    topSurfaceReady = true
                    tryCreateVirtualDisplays()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    topSurfaceReady = false
                }
            })
        }

        binding.bottomSurfaceView.apply {
            virtualWidth = BOTTOM_WIDTH
            virtualHeight = BOTTOM_HEIGHT
            holder.setFixedSize(BOTTOM_WIDTH, BOTTOM_HEIGHT)
            touchForwardingEnabled = true
            onTouchInject = { displayId, action, downTime, eventTime, count, ids, xs, ys, pressures ->
                inputExecutor.execute {
                    userService?.injectTouchEvent(
                        displayId, action, downTime, eventTime, count, ids, xs, ys, pressures
                    )
                    if (displayId == secondaryDisplayId &&
                        (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP
                    ) {
                        lastBottomTouchTime = SystemClock.uptimeMillis()
                        userService?.focusDisplay(primaryDisplayId)
                    }
                }
            }
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    bottomSurfaceReady = true
                    tryCreateVirtualDisplays()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    bottomSurfaceReady = false
                }
            })
        }
    }

    private fun setupLauncher() {
        launcherAdapter = AppLauncherAdapter(
            onAppClick = { app ->
                prefs.recordAppLaunch(app.packageName)
                launchAppOnDisplay(app, activeTopDisplayId, hideLauncherAfter = true)
            },
            onFolderClick = { folderName -> openDrawerFolder(isTop = true, folderName = folderName) },
            onAppLongClick = { app -> showAppContextMenu(app, isTopDrawer = true) },
            onFolderLongClick = { folderName -> showFolderContextMenu(folderName, isTop = true) },
            onSelectionChanged = { updateMultiSelectBarUi(isTop = true) }
        )
        binding.appRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = launcherAdapter
        }

        bottomLauncherAdapter = AppLauncherAdapter(
            onAppClick = { app ->
                prefs.recordAppLaunch(app.packageName)
                binding.bottomLauncherOverlay.visibility = View.GONE
                launchAppOnDisplay(app, activeBottomDisplayId, hideLauncherAfter = false)
            },
            onFolderClick = { folderName -> openDrawerFolder(isTop = false, folderName = folderName) },
            onAppLongClick = { app -> showAppContextMenu(app, isTopDrawer = false) },
            onFolderLongClick = { folderName -> showFolderContextMenu(folderName, isTop = false) },
            onSelectionChanged = { updateMultiSelectBarUi(isTop = false) }
        )
        binding.bottomAppRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = bottomLauncherAdapter
        }
        setupDrawerMultiSelectBars()
        binding.btnCancelBottomLauncher.setOnClickListener {
            exitDrawerMultiSelect(isTop = false)
            binding.bottomLauncherOverlay.visibility = View.GONE
        }
        binding.btnCloseBottomApp.setOnClickListener {
            closeBottomApp()
            binding.bottomLauncherOverlay.visibility = View.GONE
        }

        binding.etTopSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                topSearchQuery = s?.toString()?.trim().orEmpty()
                refreshLauncherList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etBottomSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                bottomSearchQuery = s?.toString()?.trim().orEmpty()
                refreshLauncherList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnTopSort.setOnClickListener {
            val next = (prefs.getTopSortMode() + 1) % 3
            prefs.setTopSortMode(next)
            updateDrawerFilterButtons()
            refreshLauncherList()
        }

        binding.btnBottomSort.setOnClickListener {
            val next = (prefs.getBottomSortMode() + 1) % 3
            prefs.setBottomSortMode(next)
            updateDrawerFilterButtons()
            refreshLauncherList()
        }

        binding.btnTopFolder.setOnClickListener {
            if (selectedTopFolder != null) {
                openDrawerFolder(isTop = true, folderName = null)
            } else {
                showFolderPickerDialog(isTop = true)
            }
        }
        binding.btnBottomFolder.setOnClickListener {
            if (selectedBottomFolder != null) {
                openDrawerFolder(isTop = false, folderName = null)
            } else {
                showFolderPickerDialog(isTop = false)
            }
        }

        updateShowAllButtonText()
        updateDrawerFilterButtons()
        binding.btnToggleShowAll.setOnClickListener {
            prefs.setShowAllApps(!prefs.isShowAllApps())
            updateShowAllButtonText()
            refreshLauncherList()
        }

        binding.btnFilterApps.setOnClickListener {
            showFilterAppsDialog()
        }

        ioExecutor.execute {
            val loaded = AppRepository.loadLaunchableApps(this)
            runOnUiThread {
                allApps = loaded
                refreshLauncherList()
                if (launcherAdapter.itemCount > 0) {
                    launcherAdapter.setSelectedPosition(0)
                }
                if (binding.topSplashOverlay.visibility == View.VISIBLE) {
                    binding.topSplashOverlay.animate()
                        .alpha(0f)
                        .setDuration(220L)
                        .withEndAction {
                            binding.topSplashOverlay.visibility = View.GONE
                            binding.topSplashOverlay.alpha = 1f
                        }
                        .start()
                }
            }
        }
    }

    private fun sortLabel(mode: Int): String = when (mode) {
        SimulatorPrefs.SORT_ZA -> "Z-A"
        SimulatorPrefs.SORT_RECENT -> "Recent"
        else -> "A-Z"
    }

    private fun openDrawerFolder(isTop: Boolean, folderName: String?) {
        exitDrawerMultiSelect(isTop)
        if (isTop) {
            selectedTopFolder = folderName
        } else {
            selectedBottomFolder = folderName
        }
        updateDrawerFilterButtons()
        refreshLauncherList()
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        val recycler = if (isTop) binding.appRecyclerView else binding.bottomAppRecyclerView
        if (adapter.itemCount > 0) {
            adapter.setSelectedPosition(0)
            recycler.scrollToPosition(0)
        }
    }

    private fun updateDrawerFilterButtons() {
        binding.btnTopSort.text = sortLabel(prefs.getTopSortMode())
        binding.btnBottomSort.text = sortLabel(prefs.getBottomSortMode())
        binding.btnTopFolder.text = selectedTopFolder?.let { "← $it" } ?: "Folders"
        binding.btnBottomFolder.text = selectedBottomFolder?.let { "← $it" } ?: "Folders"
    }

    private fun updateShowAllButtonText() {
        binding.btnToggleShowAll.text = if (prefs.isShowAllApps()) {
            getString(R.string.show_all_apps)
        } else {
            getString(R.string.show_filtered_apps)
        }
    }

    private fun sortApps(apps: List<AppInfo>, sortMode: Int): List<AppInfo> {
        return when (sortMode) {
            SimulatorPrefs.SORT_ZA -> apps.sortedByDescending { it.label.lowercase(Locale.getDefault()) }
            SimulatorPrefs.SORT_RECENT -> apps.sortedWith(
                compareByDescending<AppInfo> { prefs.getLastLaunchTime(it.packageName) }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )
            else -> apps.sortedBy { it.label.lowercase(Locale.getDefault()) }
        }
    }

    private fun buildDrawerItems(query: String, openFolder: String?, sortMode: Int): List<DrawerItem> {
        val showAll = prefs.isShowAllApps()
        val hidden = prefs.getHiddenPackages()
        val visibleApps = allApps.filter { app -> showAll || !hidden.contains(app.packageName) }

        if (query.isNotEmpty()) {
            val matching = visibleApps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            return sortApps(matching, sortMode).map { DrawerItem.App(it) }
        }

        if (openFolder != null) {
            val folderPkgs = prefs.getAppsInFolder(openFolder)
            val inFolder = visibleApps.filter { folderPkgs.contains(it.packageName) }
            return sortApps(inFolder, sortMode).map { DrawerItem.App(it) }
        }

        val visibleByPkg = visibleApps.associateBy { it.packageName }
        val folderNames = prefs.getFolders()
        val sortedFolderNames = if (sortMode == SimulatorPrefs.SORT_ZA) {
            folderNames.sortedByDescending { it.lowercase(Locale.getDefault()) }
        } else {
            folderNames.sortedBy { it.lowercase(Locale.getDefault()) }
        }

        val folderedPackages = HashSet<String>()
        val result = ArrayList<DrawerItem>(sortedFolderNames.size + visibleApps.size)
        for (folderName in sortedFolderNames) {
            val pkgs = prefs.getAppsInFolder(folderName)
            folderedPackages.addAll(pkgs)
            val folderApps = sortApps(pkgs.mapNotNull { visibleByPkg[it] }, sortMode)
            result.add(DrawerItem.Folder(folderName, folderApps))
        }

        val looseApps = visibleApps.filter { !folderedPackages.contains(it.packageName) }
        for (app in sortApps(looseApps, sortMode)) {
            result.add(DrawerItem.App(app))
        }
        return result
    }

    private fun refreshLauncherList() {
        val existingFolders = prefs.getFolders().toSet()
        if (selectedTopFolder != null && !existingFolders.contains(selectedTopFolder)) {
            selectedTopFolder = null
            updateDrawerFilterButtons()
        }
        if (selectedBottomFolder != null && !existingFolders.contains(selectedBottomFolder)) {
            selectedBottomFolder = null
            updateDrawerFilterButtons()
        }
        val topList = buildDrawerItems(topSearchQuery, selectedTopFolder, prefs.getTopSortMode())
        val bottomList = buildDrawerItems(bottomSearchQuery, selectedBottomFolder, prefs.getBottomSortMode())
        launcherAdapter.submitList(topList)
        bottomLauncherAdapter.submitList(bottomList)
        if (topList.isNotEmpty() && launcherAdapter.selectedPosition < 0) {
            launcherAdapter.setSelectedPosition(0)
        }
        populateRecentRow(
            section = binding.topRecentSection,
            row = binding.topRecentRow,
            hideForSearch = topSearchQuery.isNotBlank(),
            maxItems = 6,
            iconDp = 36,
            onClick = { app ->
                prefs.recordAppLaunch(app.packageName)
                launchAppOnDisplay(app, activeTopDisplayId, hideLauncherAfter = true)
            }
        )
        populateRecentRow(
            section = binding.bottomRecentSection,
            row = binding.bottomRecentRow,
            hideForSearch = bottomSearchQuery.isNotBlank(),
            maxItems = 4,
            iconDp = 30,
            onClick = { app ->
                prefs.recordAppLaunch(app.packageName)
                binding.bottomLauncherOverlay.visibility = View.GONE
                launchAppOnDisplay(app, activeBottomDisplayId, hideLauncherAfter = false)
            }
        )
    }

    private fun populateRecentRow(
        section: View,
        row: LinearLayout,
        hideForSearch: Boolean,
        maxItems: Int,
        iconDp: Int,
        onClick: (AppInfo) -> Unit
    ) {
        if (!prefs.isShowRecentRow() || hideForSearch) {
            section.visibility = View.GONE
            return
        }
        val recentApps = allApps
            .filter { prefs.getLastLaunchTime(it.packageName) > 0L }
            .sortedByDescending { prefs.getLastLaunchTime(it.packageName) }
            .take(maxItems)

        if (recentApps.isEmpty()) {
            section.visibility = View.GONE
            return
        }

        section.visibility = View.VISIBLE
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val iconPx = (iconDp * density).toInt()
        val itemW = ((iconDp + 26) * density).toInt()
        val pad = (4 * density).toInt()
        val labelColor = if (isLightThemeActive) Color.parseColor("#18181B") else Color.parseColor("#E5E7EB")

        for (app in recentApps) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.app_item_bg)
                layoutParams = LinearLayout.LayoutParams(itemW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener { onClick(app) }
                setOnLongClickListener {
                    showAppContextMenu(app)
                    true
                }
            }
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                setImageDrawable(app.icon)
            }
            val labelView = TextView(this).apply {
                text = app.label
                textSize = if (iconDp >= 34) 10f else 9f
                setTextColor(labelColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            item.addView(iconView)
            item.addView(labelView)
            row.addView(item)
        }
    }

    private fun showFolderPickerDialog(isTop: Boolean) {
        val folders = prefs.getFolders()
        val options = mutableListOf(
            "+ Create New Folder",
            "Auto-Organize into Folders…"
        )
        options.addAll(folders.map { "Open '$it'" })

        AlertDialog.Builder(this)
            .setTitle("Manage Folders")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showCreateFolderDialog()
                    1 -> showAutoOrganizeFoldersDialog()
                    else -> {
                        val chosen = folders[which - 2]
                        openDrawerFolder(isTop, chosen)
                    }
                }
            }
            .show()
    }

    private fun showFolderContextMenu(folderName: String, isTop: Boolean) {
        val actions = arrayOf(
            "Open Folder",
            "Rename Folder…",
            "Add / Remove Apps…",
            "Delete Folder"
        )
        AlertDialog.Builder(this)
            .setTitle(folderName)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openDrawerFolder(isTop, folderName)
                    1 -> showRenameFolderDialog(folderName)
                    2 -> showEditFolderAppsDialog(folderName)
                    3 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete '$folderName'?")
                            .setMessage("Apps inside will return to the main app grid. Nothing will be uninstalled.")
                            .setPositiveButton("Delete") { _, _ ->
                                prefs.removeFolder(folderName)
                                if (selectedTopFolder == folderName) selectedTopFolder = null
                                if (selectedBottomFolder == folderName) selectedBottomFolder = null
                                updateDrawerFilterButtons()
                                refreshLauncherList()
                                Toast.makeText(this, "Deleted folder '$folderName'", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(oldName: String) {
        val input = EditText(this).apply {
            setText(oldName)
            setSelection(oldName.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Folder")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != oldName) {
                    prefs.renameFolder(oldName, newName)
                    if (selectedTopFolder == oldName) selectedTopFolder = newName
                    if (selectedBottomFolder == oldName) selectedBottomFolder = newName
                    updateDrawerFilterButtons()
                    refreshLauncherList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditFolderAppsDialog(folderName: String) {
        val sortedApps = allApps.sortedBy { it.label.lowercase(Locale.getDefault()) }
        val currentPkgs = prefs.getAppsInFolder(folderName)
        val labels = sortedApps.map { it.label }.toTypedArray()
        val checked = BooleanArray(sortedApps.size) { i ->
            currentPkgs.contains(sortedApps[i].packageName)
        }
        AlertDialog.Builder(this)
            .setTitle("Apps in '$folderName'")
            .setMultiChoiceItems(labels, checked) { _, idx, isChecked ->
                prefs.setAppInFolder(folderName, sortedApps[idx].packageName, isChecked)
            }
            .setPositiveButton("Done") { _, _ -> refreshLauncherList() }
            .show()
    }

    private fun showAutoOrganizeFoldersDialog() {
        if (allApps.isEmpty()) {
            Toast.makeText(this, "Still loading apps…", Toast.LENGTH_SHORT).show()
            return
        }

        // Detect installed browsers via standard HTTPS VIEW intent
        val browserPkgs = try {
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
            packageManager.queryIntentActivities(browserIntent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }

        val emuKeywords = listOf(
            "retroarch", "drastic", "melonds", "seedlessds", "azahar", "citra", "lime3ds",
            "dolphin", "ppsspp", "duckstation", "aethersx2", "nethersx2", "cemu", "yuzu",
            "suyu", "sudachi", "citron", "ryujinx", "nx2android", "banananative", "winlator",
            "gamehub", "mobox", "vita3k", "flycast", "redream", "mupen", "lemuroid",
            "daijisho", "es-de", "pegasus", "recomp", "quiver", "moonlight", "artemis",
            "chiaki", "xbplay", "steamlink", "geforcenow", "pizzaboy", "myboy", "myoldboy"
        )

        val buckets = LinkedHashMap<String, MutableList<String>>()
        fun addToBucket(folder: String, pkg: String) {
            buckets.getOrPut(folder) { mutableListOf() }.add(pkg)
        }

        for (app in allApps) {
            val pkgLow = app.packageName.lowercase(Locale.US)
            val lblLow = app.label.lowercase(Locale.US)
            val isEmu = prefs.isDualScreenApp(app.packageName) ||
                emuKeywords.any { kw -> pkgLow.contains(kw) || lblLow.contains(kw) }

            when {
                isEmu -> addToBucket("Emulators & Ports", app.packageName)
                app.category == android.content.pm.ApplicationInfo.CATEGORY_GAME ->
                    addToBucket("Games", app.packageName)
                browserPkgs.contains(app.packageName) ||
                    pkgLow.contains("browser") || pkgLow.contains("firefox") || pkgLow.contains("iceraven") ->
                    addToBucket("Browsers", app.packageName)
                app.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO ||
                    app.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO ||
                    app.category == android.content.pm.ApplicationInfo.CATEGORY_IMAGE ->
                    addToBucket("Media", app.packageName)
                app.category == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL ||
                    app.category == android.content.pm.ApplicationInfo.CATEGORY_NEWS ->
                    addToBucket("Social", app.packageName)
                app.category == android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY ||
                    app.category == android.content.pm.ApplicationInfo.CATEGORY_MAPS ||
                    pkgLow.contains("shizuku") ->
                    addToBucket("Tools", app.packageName)
            }
        }

        if (buckets.isEmpty()) {
            Toast.makeText(this, "No apps matched automatic categories", Toast.LENGTH_SHORT).show()
            return
        }

        val summary = buildString {
            appendLine("Group installed apps into these folders:")
            appendLine()
            for ((name, pkgs) in buckets) {
                appendLine("• $name (${pkgs.size} apps)")
            }
            appendLine()
            append("Existing custom folders won't be removed.")
        }

        AlertDialog.Builder(this)
            .setTitle("Auto-Organize App Drawer")
            .setMessage(summary)
            .setPositiveButton("Organize") { _, _ ->
                var totalAssigned = 0
                for ((folderName, pkgs) in buckets) {
                    prefs.addFolder(folderName)
                    for (pkg in pkgs) {
                        prefs.setAppInFolder(folderName, pkg, true)
                        totalAssigned++
                    }
                }
                selectedTopFolder = null
                selectedBottomFolder = null
                updateDrawerFilterButtons()
                refreshLauncherList()
                Toast.makeText(
                    this,
                    "Organized $totalAssigned apps into ${buckets.size} folders",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = "Folder name (e.g. Emulators, Switch)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Create Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.addFolder(name)
                    refreshLauncherList()
                    Toast.makeText(this, "Folder '$name' created. Long-press it or any app to add apps.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupDrawerMultiSelectBars() {
        binding.btnTopSelectAll.setOnClickListener { launcherAdapter.toggleSelectAllVisible() }
        binding.btnTopMultiCreateFolder.setOnClickListener { showBatchCreateFolderDialog(isTop = true) }
        binding.btnTopMultiAddFolder.setOnClickListener { showBatchFolderDialog(isTop = true) }
        binding.btnTopMultiHide.setOnClickListener { applyBatchHide(isTop = true) }
        binding.btnTopMultiDual.setOnClickListener { applyBatchDualScreen(isTop = true) }
        binding.btnTopMultiDone.setOnClickListener { exitDrawerMultiSelect(isTop = true) }

        binding.btnBottomSelectAll.setOnClickListener { bottomLauncherAdapter.toggleSelectAllVisible() }
        binding.btnBottomMultiCreateFolder.setOnClickListener { showBatchCreateFolderDialog(isTop = false) }
        binding.btnBottomMultiAddFolder.setOnClickListener { showBatchFolderDialog(isTop = false) }
        binding.btnBottomMultiHide.setOnClickListener { applyBatchHide(isTop = false) }
        binding.btnBottomMultiDual.setOnClickListener { applyBatchDualScreen(isTop = false) }
        binding.btnBottomMultiDone.setOnClickListener { exitDrawerMultiSelect(isTop = false) }
    }

    private fun enterDrawerMultiSelect(isTop: Boolean, initialPackage: String) {
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        adapter.enterMultiSelect(initialPackage)
        updateMultiSelectBarUi(isTop)
    }

    private fun exitDrawerMultiSelect(isTop: Boolean) {
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        adapter.exitMultiSelect()
        updateMultiSelectBarUi(isTop)
    }

    private fun updateMultiSelectBarUi(isTop: Boolean) {
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        val bar = if (isTop) binding.topMultiSelectBar else binding.bottomMultiSelectBar
        if (!adapter.multiSelectMode) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        val count = adapter.getSelectedCount()
        val selectedPkgs = adapter.getSelectedPackages()
        val allHidden = selectedPkgs.isNotEmpty() && selectedPkgs.all { prefs.getHiddenPackages().contains(it) }
        val allDual = selectedPkgs.isNotEmpty() && selectedPkgs.all { prefs.isDualScreenApp(it) }
        val allSelected = adapter.isAllVisibleSelected()

        if (isTop) {
            binding.tvTopSelectCount.text = "$count selected"
            binding.btnTopSelectAll.text = if (allSelected) "None" else "All"
            binding.btnTopMultiHide.text = if (allHidden) "Unhide" else "Hide"
            binding.btnTopMultiDual.text = if (allDual) "Unmark 2-Scr" else "2-Scr"
            binding.btnTopMultiCreateFolder.isEnabled = count > 0
            binding.btnTopMultiAddFolder.isEnabled = count > 0
            binding.btnTopMultiHide.isEnabled = count > 0
            binding.btnTopMultiDual.isEnabled = count > 0
        } else {
            binding.tvBottomSelectCount.text = "$count"
            binding.btnBottomSelectAll.text = if (allSelected) "None" else "All"
            binding.btnBottomMultiHide.text = if (allHidden) "Unhide" else "Hide"
            binding.btnBottomMultiDual.text = if (allDual) "No 2-Scr" else "2-Scr"
            binding.btnBottomMultiCreateFolder.isEnabled = count > 0
            binding.btnBottomMultiAddFolder.isEnabled = count > 0
            binding.btnBottomMultiHide.isEnabled = count > 0
            binding.btnBottomMultiDual.isEnabled = count > 0
        }
    }

    private fun showBatchCreateFolderDialog(isTop: Boolean) {
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        val pkgs = adapter.getSelectedPackages()
        if (pkgs.isEmpty()) return
        val input = android.widget.EditText(this).apply {
            hint = "Folder name (e.g., 3DS, Switch, Recomps)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Create Folder (${pkgs.size} apps)")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    prefs.addFolder(name)
                    for (pkg in pkgs) {
                        prefs.setAppInFolder(name, pkg, true)
                    }
                    Toast.makeText(this, "Added ${pkgs.size} apps to '$name'", Toast.LENGTH_SHORT).show()
                    exitDrawerMultiSelect(isTop)
                    refreshLauncherList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBatchFolderDialog(isTop: Boolean) {
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        val pkgs = adapter.getSelectedPackages()
        if (pkgs.isEmpty()) return
        val folders = prefs.getFolders()
        val currentFolder = if (isTop) selectedTopFolder else selectedBottomFolder

        val options = mutableListOf<String>()
        if (currentFolder != null) {
            options.add("Remove ${pkgs.size} apps from '$currentFolder'")
        }
        options.addAll(folders.map { "Add to '$it'" })
        options.add("+ Create New Folder…")

        AlertDialog.Builder(this)
            .setTitle("Organize ${pkgs.size} Selected Apps")
            .setItems(options.toTypedArray()) { _, which ->
                var idx = which
                if (currentFolder != null) {
                    if (idx == 0) {
                        for (pkg in pkgs) {
                            prefs.setAppInFolder(currentFolder, pkg, false)
                        }
                        Toast.makeText(this, "Removed ${pkgs.size} apps from '$currentFolder'", Toast.LENGTH_SHORT).show()
                        exitDrawerMultiSelect(isTop)
                        refreshLauncherList()
                        return@setItems
                    }
                    idx -= 1
                }
                if (idx < folders.size) {
                    val target = folders[idx]
                    for (pkg in pkgs) {
                        prefs.setAppInFolder(target, pkg, true)
                    }
                    Toast.makeText(this, "Added ${pkgs.size} apps to '$target'", Toast.LENGTH_SHORT).show()
                    exitDrawerMultiSelect(isTop)
                    refreshLauncherList()
                } else {
                    showBatchCreateFolderDialog(isTop)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyBatchHide(isTop: Boolean) {
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        val pkgs = adapter.getSelectedPackages()
        if (pkgs.isEmpty()) return
        val allHidden = pkgs.all { prefs.getHiddenPackages().contains(it) }
        val hideNow = !allHidden
        for (pkg in pkgs) {
            prefs.setPackageHidden(pkg, hideNow)
        }
        val verb = if (hideNow) "Hidden" else "Unhidden"
        Toast.makeText(this, "$verb ${pkgs.size} apps", Toast.LENGTH_SHORT).show()
        exitDrawerMultiSelect(isTop)
        refreshLauncherList()
    }

    private fun applyBatchDualScreen(isTop: Boolean) {
        val adapter = if (isTop) launcherAdapter else bottomLauncherAdapter
        val pkgs = adapter.getSelectedPackages()
        if (pkgs.isEmpty()) return
        val allDual = pkgs.all { prefs.isDualScreenApp(it) }
        val markDual = !allDual
        for (pkg in pkgs) {
            prefs.setDualScreenApp(pkg, markDual)
        }
        val verb = if (markDual) "Marked" else "Unmarked"
        Toast.makeText(this, "$verb ${pkgs.size} apps as Dual-Screen", Toast.LENGTH_SHORT).show()
        exitDrawerMultiSelect(isTop)
    }

    private fun showAppContextMenu(app: AppInfo, isTopDrawer: Boolean = true) {
        val folders = prefs.getFolders()
        val isHidden = prefs.getHiddenPackages().contains(app.packageName)
        val isDualScreen = prefs.isDualScreenApp(app.packageName)
        val hasProfile = prefs.getAppProfile(app.packageName) != null
        val actions = mutableListOf(
            "Select Multiple Apps",
            if (isHidden) "Unhide App" else "Hide App",
            "Assign to Folders…",
            if (isDualScreen) "Unmark as Dual-Screen App" else "Mark as Dual-Screen App",
            if (hasProfile) "Edit Per-App Layout Profile…" else "Set Per-App Layout Profile…"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> enterDrawerMultiSelect(isTopDrawer, app.packageName)
                    1 -> {
                        prefs.setPackageHidden(app.packageName, !isHidden)
                        refreshLauncherList()
                    }
                    2 -> {
                        if (folders.isEmpty()) {
                            showCreateFolderDialog()
                        } else {
                            val checked = BooleanArray(folders.size) { i ->
                                prefs.getAppsInFolder(folders[i]).contains(app.packageName)
                            }
                            AlertDialog.Builder(this)
                                .setTitle("Folders for ${app.label}")
                                .setMultiChoiceItems(folders.toTypedArray(), checked) { _, idx, isChecked ->
                                    prefs.setAppInFolder(folders[idx], app.packageName, isChecked)
                                }
                                .setPositiveButton("Done") { _, _ -> refreshLauncherList() }
                                .show()
                        }
                    }
                    3 -> {
                        prefs.setDualScreenApp(app.packageName, !isDualScreen)
                        val msg = if (!isDualScreen) "${app.label} marked as Dual-Screen App" else "${app.label} unmarked as Dual-Screen App"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    4 -> showAppProfileDialog(app)
                }
            }
            .show()
    }

    private fun showFilterAppsDialog() {
        if (allApps.isEmpty()) return
        val hidden = prefs.getHiddenPackages()
        val labels = allApps.map { it.label }.toTypedArray()
        val checked = BooleanArray(allApps.size) { i -> hidden.contains(allApps[i].packageName) }

        AlertDialog.Builder(this)
            .setTitle("Hide Apps from Launcher")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                prefs.setPackageHidden(allApps[which].packageName, isChecked)
            }
            .setPositiveButton("Done") { _, _ ->
                refreshLauncherList()
            }
            .show()
    }

    private fun setupUtilityBar() {
        binding.btnHome.setOnClickListener {
            val showing = binding.launcherOverlay.visibility == View.VISIBLE
            binding.launcherOverlay.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) {
                if (launcherAdapter.itemCount > 0 && launcherAdapter.selectedPosition < 0) {
                    launcherAdapter.setSelectedPosition(0)
                }
                binding.appRecyclerView.requestFocus()
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnSwap.setOnClickListener {
            swapScreens()
        }

        binding.btnLaunchBottom.setOnClickListener {
            val showing = binding.bottomLauncherOverlay.visibility == View.VISIBLE
            binding.bottomLauncherOverlay.visibility = if (showing) View.GONE else View.VISIBLE
            updateReturnDualScreenVisibility()
        }

        binding.btnCloseBottom.setOnClickListener {
            handleBottomActionButton()
        }
        val dismissDualScreenReturn = {
            pausedDualScreenApp = null
            updateReturnDualScreenVisibility()
            if (!hasBottomAppRunning && secondaryDisplayId > 0) {
                ioExecutor.execute { userService?.closeTasksOnDisplay(secondaryDisplayId) }
            }
            updateBottomActionButton()
        }
        binding.btnReturnDualScreen.setOnClickListener {
            val target = pausedDualScreenApp
            if (target != null) {
                launchAppOnDisplay(target, primaryDisplayId, hideLauncherAfter = true)
            } else {
                dismissDualScreenReturn()
            }
        }
        binding.btnReturnDualScreen.setOnLongClickListener {
            dismissDualScreenReturn()
            true
        }
        binding.btnDismissReturnDualScreen.setOnClickListener {
            dismissDualScreenReturn()
        }
        updateBottomActionButton()

        binding.btnToggleTouch.setOnClickListener {
            val currentlyVisible = binding.leftGamepadContainer.visibility == View.VISIBLE
            val next = !currentlyVisible
            manualTouchOverride = next
            prefs.setTouchControlsEnabled(next)
            applyTouchControlsVisibility(next)
            applyDisplayLayout()
            syncSurfaceMetadata()
        }

        binding.btnToggleLeftStick.setOnClickListener {
            val next = (prefs.getLeftControlMode() + 1) % 3
            prefs.setLeftControlMode(next)
            applyStickModes()
            if (::layoutEditor.isInitialized && !layoutEditor.isEditModeActive) {
                binding.root.post { layoutEditor.applySavedLayoutForCurrentOrientation() }
            }
        }

        binding.btnToggleRightStick.setOnClickListener {
            val next = (prefs.getRightControlMode() + 1) % 3
            prefs.setRightControlMode(next)
            applyStickModes()
            if (::layoutEditor.isInitialized && !layoutEditor.isEditModeActive) {
                binding.root.post { layoutEditor.applySavedLayoutForCurrentOrientation() }
            }
        }

        binding.btnExit.setOnClickListener {
            shutdownAndExit()
        }
    }

    private fun setupVirtualGamepad() {
        binding.btnL1.apply {
            label = "L1"
            keyCode = KeyEvent.KEYCODE_BUTTON_L1
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnL2.apply {
            label = "L2"
            keyCode = KeyEvent.KEYCODE_BUTTON_L2
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnR1.apply {
            label = "R1"
            keyCode = KeyEvent.KEYCODE_BUTTON_R1
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnR2.apply {
            label = "R2"
            keyCode = KeyEvent.KEYCODE_BUTTON_R2
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnSelect.apply {
            label = "SELECT"
            keyCode = KeyEvent.KEYCODE_BUTTON_SELECT
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnStart.apply {
            label = "START"
            keyCode = KeyEvent.KEYCODE_BUTTON_START
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnL3.apply {
            label = "L3"
            keyCode = KeyEvent.KEYCODE_BUTTON_THUMBL
            onKeyEvent = ::handleVirtualKey
        }
        binding.btnR3.apply {
            label = "R3"
            keyCode = KeyEvent.KEYCODE_BUTTON_THUMBR
            onKeyEvent = ::handleVirtualKey
        }
        applyGamepadAppearance()

        binding.virtualDpad.onKeyEvent = ::handleVirtualKey
        binding.virtualFaceButtons.onKeyEvent = ::handleVirtualKey

        binding.virtualFaceButtons.isXboxLayout =
            (prefs.getFaceButtonLayout() == SimulatorPrefs.FACE_LAYOUT_XBOX)

        binding.virtualLeftStick.onStickMove = { x, y ->
            if (binding.launcherOverlay.visibility == View.VISIBLE) {
                handleTopLauncherStick(x, y)
            } else {
                leftStickX = x
                leftStickY = y
                sendVirtualJoystick(force = (x == 0f && y == 0f))
            }
        }

        binding.virtualRightStick.onStickMove = { x, y ->
            rightStickX = x
            rightStickY = y
            sendVirtualJoystick(force = (x == 0f && y == 0f))
        }

        applyStickModes()
    }

    private fun applyStickModes() {
        val leftMode = prefs.getLeftControlMode()
        binding.virtualDpad.visibility =
            if (leftMode == SimulatorPrefs.MODE_PRIMARY || leftMode == SimulatorPrefs.MODE_BOTH) View.VISIBLE else View.GONE
        val showLeftStick = leftMode == SimulatorPrefs.MODE_STICK || leftMode == SimulatorPrefs.MODE_BOTH
        binding.leftStickWrapper.visibility = if (showLeftStick) View.VISIBLE else View.GONE
        binding.btnL3.visibility = if (showLeftStick && prefs.isShowL3R3()) View.VISIBLE else View.GONE
        binding.btnToggleLeftStick.text = when (leftMode) {
            SimulatorPrefs.MODE_STICK -> "L-STK"
            SimulatorPrefs.MODE_BOTH -> "D+LS"
            else -> "D-PAD"
        }

        val rightMode = prefs.getRightControlMode()
        binding.virtualFaceButtons.visibility =
            if (rightMode == SimulatorPrefs.MODE_PRIMARY || rightMode == SimulatorPrefs.MODE_BOTH) View.VISIBLE else View.GONE
        val showRightStick = rightMode == SimulatorPrefs.MODE_STICK || rightMode == SimulatorPrefs.MODE_BOTH
        binding.rightStickWrapper.visibility = if (showRightStick) View.VISIBLE else View.GONE
        binding.btnR3.visibility = if (showRightStick && prefs.isShowL3R3()) View.VISIBLE else View.GONE
        binding.btnToggleRightStick.text = when (rightMode) {
            SimulatorPrefs.MODE_STICK -> "R-STK"
            SimulatorPrefs.MODE_BOTH -> "ABXY+RS"
            else -> "ABXY"
        }
    }

    private fun updatePhysicalControllerState() {
        val ids = InputDevice.getDeviceIds()
        var found = false
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (dev.isVirtual || dev.name == "AYN Thor X360 Pad") continue
            val sources = dev.sources
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (isGamepad) {
                found = true
                break
            }
        }
        val stateChanged = (physicalControllerConnected != found)
        physicalControllerConnected = found
        val showTouch = manualTouchOverride ?: (!found && prefs.isTouchControlsEnabled())
        applyTouchControlsVisibility(showTouch)
        if (stateChanged) {
            applyDisplayLayout()
            syncSurfaceMetadata()
        }
    }

    private fun applyTouchControlsVisibility(visible: Boolean) {
        if (::layoutEditor.isInitialized && layoutEditor.isEditModeActive) return
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        binding.leftGamepadContainer.visibility = vis
        binding.rightGamepadContainer.visibility = vis
        if (::layoutEditor.isInitialized) {
            for (v in layoutEditor.getGamepadViews()) {
                v.visibility = vis
            }
            if (visible) {
                applyStickModes()
            }
        }
    }

    private fun applyGamepadAppearance() {
        val alpha = prefs.getGamepadOpacity()
        val scale = prefs.getGamepadScale()
        binding.virtualDpad.fourWayOnly = prefs.isDpadFourWayOnly()
        binding.leftGamepadContainer.alpha = alpha
        binding.rightGamepadContainer.alpha = alpha
        for (v in listOf(binding.virtualDpad, binding.leftStickWrapper, binding.virtualFaceButtons, binding.rightStickWrapper)) {
            v.scaleX = scale
            v.scaleY = scale
        }
        applyStickModes()
        if (::layoutEditor.isInitialized && !layoutEditor.isEditModeActive) {
            layoutEditor.applySavedLayoutForCurrentOrientation()
        }
    }

    private fun handleVirtualKey(keyCode: Int, isDown: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (isDown) {
            // Cancel any pending delayed release for this same key so rapid re-taps register cleanly
            pendingKeyUpRunnables.remove(keyCode)?.let { uiHandler.removeCallbacks(it) }
            if (prefs.isHapticsEnabled()) {
                binding.rootLayout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            keyDownTimes[keyCode] = now
        } else {
            val startedAt = keyDownTimes[keyCode]
            if (startedAt != null) {
                val heldMs = now - startedAt
                if (heldMs in 0L until 35L) {
                    // Enforce a 35ms minimum hold floor (>2 frames at 60 FPS) so ultra-short
                    // touchscreen taps (12-24ms) never fall between emulator input polls.
                    pendingKeyUpRunnables.remove(keyCode)?.let { uiHandler.removeCallbacks(it) }
                    val delayedUp = Runnable {
                        pendingKeyUpRunnables.remove(keyCode)
                        handleVirtualKeyImmediate(keyCode, isDown = false, now = SystemClock.uptimeMillis())
                    }
                    pendingKeyUpRunnables[keyCode] = delayedUp
                    uiHandler.postDelayed(delayedUp, 35L - heldMs)
                    return
                }
            }
        }
        handleVirtualKeyImmediate(keyCode, isDown, now)
    }

    private fun handleVirtualKeyImmediate(keyCode: Int, isDown: Boolean, now: Long) {
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val downTime = if (isDown) {
            keyDownTimes[keyCode] ?: now
        } else {
            keyDownTimes.remove(keyCode) ?: now
        }

        // If launcher overlay is visible, route virtual gamepad to navigate the top launcher
        if (binding.launcherOverlay.visibility == View.VISIBLE) {
            handleTopLauncherKey(keyCode, isDown)
            return
        }

        // Always target the top virtual display (primaryDisplayId) for gamepad controls
        val targetId = primaryDisplayId
        if (targetId <= 0) {
            addLocalLog("vKey $keyCode dropped: primaryDisplayId=$targetId")
            return
        }

        // Also update HAT axes when D-pad is pressed so games reading either KeyEvent or HAT work
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> hatX = if (isDown) -1f else if (hatX < 0f) 0f else hatX
            KeyEvent.KEYCODE_DPAD_RIGHT -> hatX = if (isDown) 1f else if (hatX > 0f) 0f else hatX
            KeyEvent.KEYCODE_DPAD_UP -> hatY = if (isDown) -1f else if (hatY < 0f) 0f else hatY
            KeyEvent.KEYCODE_DPAD_DOWN -> hatY = if (isDown) 1f else if (hatY > 0f) 0f else hatY
            KeyEvent.KEYCODE_BUTTON_L2 -> lTrigger = if (isDown) 1f else 0f
            KeyEvent.KEYCODE_BUTTON_R2 -> rTrigger = if (isDown) 1f else 0f
        }

        val source = if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD
        } else {
            InputDevice.SOURCE_GAMEPAD
        }

        val useKernel = prefs.isUseKernelGamepad()
        if (!useKernel && (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_R2)
        ) {
            sendVirtualJoystick()
        }

        val metaFlags = if (useKernel) 0x10000000 else 0
        inputExecutor.execute {
            if (lastBottomTouchTime > 0L) {
                lastBottomTouchTime = 0L
                userService?.focusDisplay(targetId)
            }
            userService?.injectKeyEvent(
                targetId,
                action,
                keyCode,
                downTime,
                now,
                0,
                metaFlags,
                source
            )
        }

        val isFaceBtn = keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == KeyEvent.KEYCODE_BUTTON_X ||
            keyCode == KeyEvent.KEYCODE_BUTTON_Y
        if (isFaceBtn && !useKernel) {
            keyRepeatRunnables.remove(keyCode)?.let { uiHandler.removeCallbacks(it) }
            if (isDown) {
                var repCount = 0
                val repeatTask = object : Runnable {
                    override fun run() {
                        if (!keyDownTimes.containsKey(keyCode)) return
                        repCount++
                        val tNow = SystemClock.uptimeMillis()
                        val dTime = keyDownTimes[keyCode] ?: tNow
                        inputExecutor.execute {
                            userService?.injectKeyEvent(
                                targetId,
                                KeyEvent.ACTION_DOWN,
                                keyCode,
                                dTime,
                                tNow,
                                repCount,
                                0,
                                source
                            )
                        }
                        uiHandler.postDelayed(this, 100L)
                    }
                }
                keyRepeatRunnables[keyCode] = repeatTask
                uiHandler.postDelayed(repeatTask, 250L)
            }
        }
    }

    private fun sendVirtualJoystick(force: Boolean = false) {
        val targetId = primaryDisplayId
        if (targetId <= 0) {
            addLocalLog("vStick dropped: primaryDisplayId=$targetId")
            return
        }
        val isCentered = leftStickX == 0f && leftStickY == 0f && rightStickX == 0f && rightStickY == 0f && lTrigger == 0f && rTrigger == 0f
        if (isCentered) {
            stickDownTime = 0L
        } else if (stickDownTime == 0L) {
            stickDownTime = SystemClock.uptimeMillis()
        }

        joystickDirty.set(true)
        if (!force && !joystickRunning.compareAndSet(false, true)) return

        inputExecutor.execute {
            try {
                while (joystickDirty.getAndSet(false)) {
                    val now = SystemClock.uptimeMillis()
                    val dTime = if (stickDownTime != 0L) stickDownTime else now
                    if (lastBottomTouchTime > 0L) {
                        lastBottomTouchTime = 0L
                        userService?.focusDisplay(targetId)
                    }
                    userService?.injectJoystickEvent(
                        targetId,
                        dTime,
                        now,
                        leftStickX,
                        leftStickY,
                        rightStickX,
                        rightStickY,
                        lTrigger,
                        rTrigger,
                        hatX,
                        hatY
                    )
                }
            } finally {
                joystickRunning.set(false)
                if (joystickDirty.get()) {
                    sendVirtualJoystick(force = false)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepadKey = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        if (event.device?.name == "AYN Thor X360 Pad") return true
        if (isGamepadKey && binding.launcherOverlay.visibility == View.VISIBLE && !binding.etTopSearch.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                if (handleTopLauncherKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)) {
                    return true
                }
            }
        }
        if (isGamepadKey && binding.launcherOverlay.visibility != View.VISIBLE) {
            val targetId = primaryDisplayId
            if (targetId > 0) {
                inputExecutor.execute {
                    userService?.injectKeyEvent(
                        targetId,
                        event.action,
                        event.keyCode,
                        event.downTime,
                        event.eventTime,
                        event.repeatCount,
                        event.metaState,
                        event.source
                    )
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

        if (event.device?.name == "AYN Thor X360 Pad") return true
        if (isJoystick && binding.launcherOverlay.visibility == View.VISIBLE && !binding.etTopSearch.hasFocus()) {
            val ax = event.getAxisValue(MotionEvent.AXIS_X)
            val ay = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val effX = if (kotlin.math.abs(hx) > 0.5f) hx else ax
            val effY = if (kotlin.math.abs(hy) > 0.5f) hy else ay
            handleTopLauncherStick(effX, effY)
            return true
        }
        if (isJoystick && binding.launcherOverlay.visibility != View.VISIBLE) {
            val targetId = primaryDisplayId
            if (targetId > 0) {
                val ax = event.getAxisValue(MotionEvent.AXIS_X)
                val ay = event.getAxisValue(MotionEvent.AXIS_Y)
                val az = event.getAxisValue(MotionEvent.AXIS_Z)
                val arz = event.getAxisValue(MotionEvent.AXIS_RZ)
                val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

                inputExecutor.execute {
                    userService?.injectJoystickEvent(
                        targetId,
                        event.downTime,
                        event.eventTime,
                        ax, ay, az, arz, lt, rt, hx, hy
                    )
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun checkShizukuAndBind() {
        if (!Shizuku.pingBinder()) {
            showShizukuOverlay(
                getString(R.string.shizuku_not_running),
                showAction = true,
                isPermission = false
            )
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            showShizukuOverlay(
                getString(R.string.shizuku_permission_needed),
                showAction = true,
                isPermission = true
            )
            return
        }

        binding.shizukuOverlay.visibility = View.GONE
        if (::onboardingOverlay.isInitialized) {
            onboardingOverlay.dismissIfReconnectMode()
            onboardingOverlay.refreshLiveStatus()
        }
        if (!isServiceBound) {
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (e: Throwable) {
                showShizukuOverlay("Shizuku bind failed: ${e.message}", showAction = true, isPermission = false)
            }
        } else {
            tryCreateVirtualDisplays()
        }
    }

    private fun showShizukuOverlay(message: String, showAction: Boolean, isPermission: Boolean) {
        binding.shizukuOverlay.visibility = View.GONE
        if (::onboardingOverlay.isInitialized) {
            onboardingOverlay.refreshTheme(isLightThemeActive)
            if (onboardingOverlay.isInWizardMode) {
                onboardingOverlay.refreshLiveStatus()
            } else {
                onboardingOverlay.showShizukuReconnect(message)
            }
        }
    }

    private fun tryCreateVirtualDisplays() {
        val service = userService ?: return
        if (!topSurfaceReady || !bottomSurfaceReady) return

        val topSurface = binding.topSurfaceView.holder.surface
        val bottomSurface = binding.bottomSurfaceView.holder.surface

        // If displays already exist (e.g. returning after minimizing), re-attach each display to its permanent SurfaceView
        if (primaryDisplayId > 0 && secondaryDisplayId > 0) {
            ioExecutor.execute {
                service.swapSurfaces(topSurface, bottomSurface)
            }
            return
        }

        ioExecutor.execute {
            val ids = service.createDisplays(
                topSurface, TOP_WIDTH, TOP_HEIGHT, VIRTUAL_DPI,
                bottomSurface, BOTTOM_WIDTH, BOTTOM_HEIGHT, VIRTUAL_DPI
            )
            runOnUiThread {
                if (ids != null && ids.size >= 2 && ids[0] > 0 && ids[1] > 0) {
                    primaryDisplayId = ids[0]
                    secondaryDisplayId = ids[1]
                    syncSurfaceMetadata()
                } else {
                    localLogs.add("createDisplays returned invalid IDs: ${ids?.contentToString()}")
                    showDebugLogDialog()
                }
            }
        }
    }

    private fun swapScreens() {
        val service = userService ?: return
        if (primaryDisplayId <= 0 || secondaryDisplayId <= 0) return

        // If currently visually swapped (dual-screen Presentation mode), restore layout constraints instantly
        if (isSwapped) {
            isSwapped = false
            syncSurfaceMetadata()
            addLocalLog("Visual swap restored (isSwapped=false)")
            Toast.makeText(this, "Restored top & bottom screens", Toast.LENGTH_SHORT).show()
            return
        }

        ioExecutor.execute {
            // Two independent apps running: swap tasks between displays so each resizes natively
            val swappedTasks = service.swapTasksBetweenDisplays(primaryDisplayId, secondaryDisplayId)
            runOnUiThread {
                if (swappedTasks) {
                    val prevTop = activeTopPackage
                    activeTopPackage = lastBottomPackage
                    lastBottomPackage = prevTop
                    hasBottomAppRunning = true
                    binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
                    binding.bottomLauncherOverlay.visibility = View.GONE
                    binding.bottomHomeOverlay.visibility = View.GONE
                    updateBottomActionButton()
                    Toast.makeText(this, "Swapped top & bottom apps", Toast.LENGTH_SHORT).show()
                } else {
                    // Single-task or dual-screen Presentation app (e.g. SeedlessDS, Azahar, DraStic):
                    // reposition the two SurfaceViews via ConstraintLayout without touching VirtualDisplay surfaces!
                    isSwapped = true
                    syncSurfaceMetadata()
                    binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
                    binding.bottomLauncherOverlay.visibility = View.GONE
                    binding.bottomHomeOverlay.visibility = View.GONE
                    updateBottomActionButton()
                    addLocalLog("Visual swap enabled (isSwapped=true)")
                    Toast.makeText(this, "Swapped top & bottom screens", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncSurfaceMetadata() {
        // Each SurfaceView stays permanently bound to its own VirtualDisplay & native resolution:
        // topSurfaceView -> primaryDisplayId (1920x1080), bottomSurfaceView -> secondaryDisplayId (1240x1080).
        binding.topSurfaceView.apply {
            targetDisplayId = primaryDisplayId
            virtualWidth = TOP_WIDTH
            virtualHeight = TOP_HEIGHT
        }
        binding.bottomSurfaceView.apply {
            targetDisplayId = secondaryDisplayId
            virtualWidth = currentBottomVirtualWidth
            virtualHeight = currentBottomVirtualHeight
        }

        val topLp = binding.topSurfaceView.layoutParams as ConstraintLayout.LayoutParams
        val botLp = binding.bottomSurfaceView.layoutParams as ConstraintLayout.LayoutParams

        if (isSwapped) {
            // topSurfaceView (1920x1080) -> letterboxed 16:9 centered inside bottomScreenContainer
            topLp.topToTop = R.id.bottomScreenContainer
            topLp.bottomToBottom = R.id.bottomScreenContainer
            topLp.startToStart = R.id.bottomScreenContainer
            topLp.endToEnd = R.id.bottomScreenContainer
            topLp.dimensionRatio = "H,16:9"

            // bottomSurfaceView (1240x1080) -> pillarboxed 1240:1080 centered inside topScreenContainer
            botLp.topToTop = R.id.topScreenContainer
            botLp.bottomToBottom = R.id.topScreenContainer
            botLp.startToStart = R.id.topScreenContainer
            botLp.endToEnd = R.id.topScreenContainer
            botLp.dimensionRatio = "W,1240:1080"
        } else {
            // Normal: match user's configured top & bottom aspect ratios cleanly inside each container
            val cfgTopRatio = prefs.getTopAspectRatio()
            val cfgBotRatio = effectiveBottomAspectRatio()
            topLp.topToTop = R.id.topScreenContainer
            topLp.bottomToBottom = R.id.topScreenContainer
            topLp.startToStart = R.id.topScreenContainer
            topLp.endToEnd = R.id.topScreenContainer
            topLp.dimensionRatio = if (cfgTopRatio == "Fill") null else cfgTopRatio

            botLp.topToTop = R.id.bottomScreenContainer
            botLp.bottomToBottom = R.id.bottomScreenContainer
            botLp.startToStart = R.id.bottomScreenContainer
            botLp.endToEnd = R.id.bottomScreenContainer
            botLp.dimensionRatio = if (cfgBotRatio == "Fill") null else cfgBotRatio
        }

        binding.topSurfaceView.layoutParams = topLp
        binding.bottomSurfaceView.layoutParams = botLp
        binding.bottomScreenContainer.post { syncBottomVirtualResolution() }
    }

    /**
     * Keeps the secondary VirtualDisplay's native pixel resolution matched to the bottom container's
     * true aspect ratio (1240x1080 for Thor, 1440x1080 for 4:3, 1080x1080 for 1:1, 1920x1080 for 16:9,
     * or measured container aspect in Fill mode) so full-width layouts never stretch or distort.
     */
    private fun syncBottomVirtualResolution() {
        if (secondaryDisplayId <= 0 || isSwapped) return
        val cfgBotRatio = effectiveBottomAspectRatio()
        val targetH = BOTTOM_HEIGHT
        val targetW = when (cfgBotRatio) {
            "4:3" -> 1440
            "1:1" -> 1080
            "16:9" -> 1920
            "Fill" -> {
                val cw = binding.bottomScreenContainer.width
                val ch = binding.bottomScreenContainer.height
                if (cw > 0 && ch > 0) {
                    val aspect = (cw.toFloat() / ch.toFloat()).coerceIn(0.8f, 2.35f)
                    (((targetH * aspect).toInt() / 2) * 2).coerceIn(864, 2540)
                } else {
                    BOTTOM_WIDTH
                }
            }
            else -> BOTTOM_WIDTH // "1240:1080" (Thor Default)
        }
        if (targetW == currentBottomVirtualWidth && targetH == currentBottomVirtualHeight) return
        currentBottomVirtualWidth = targetW
        currentBottomVirtualHeight = targetH
        binding.bottomSurfaceView.apply {
            virtualWidth = targetW
            virtualHeight = targetH
            holder.setFixedSize(targetW, targetH)
        }
        ioExecutor.execute {
            userService?.resizeBottomDisplay(targetW, targetH, VIRTUAL_DPI)
        }
    }

    private fun updateReturnDualScreenVisibility() {
        val shouldShow = pausedDualScreenApp != null &&
            !hasBottomAppRunning &&
            binding.bottomHomeOverlay.visibility != View.VISIBLE &&
            binding.bottomLauncherOverlay.visibility != View.VISIBLE
        binding.returnDualScreenBar.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun updateBottomActionButton() {
        updateReturnDualScreenVisibility()
        val homeVisible = binding.bottomHomeOverlay.visibility == View.VISIBLE
        if (hasBottomAppRunning) {
            binding.btnCloseBottom.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            binding.btnCloseBottom.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9CA3AF"))
            binding.btnCloseBottom.contentDescription = "Close Bottom App"
        } else {
            binding.btnCloseBottom.setImageResource(R.drawable.ic_perf_gauge)
            val tintColor = if (homeVisible) Color.parseColor("#4C9AFF") else Color.parseColor("#9CA3AF")
            binding.btnCloseBottom.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
            binding.btnCloseBottom.contentDescription = if (homeVisible) {
                "Hide Performance Dashboard"
            } else {
                "Show Performance Dashboard"
            }
        }
    }

    private fun handleBottomActionButton() {
        if (hasBottomAppRunning) {
            closeBottomApp()
        } else {
            val showing = binding.bottomHomeOverlay.visibility == View.VISIBLE
            binding.bottomHomeOverlay.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) {
                updateBottomHomeDashboard()
            } else if (primaryDisplayId > 0) {
                ioExecutor.execute { userService?.focusDisplay(primaryDisplayId) }
            }
            updateBottomActionButton()
        }
    }

    private fun closeBottomApp() {
        val service = userService ?: return
        if (secondaryDisplayId <= 0) return
        val pkgToStop = lastBottomPackage
        ioExecutor.execute {
            var ok = service.closeTasksOnDisplay(secondaryDisplayId)
            if (!ok && !pkgToStop.isNullOrBlank()) {
                ok = service.forceStopPackage(pkgToStop)
            }
            if (primaryDisplayId > 0) {
                service.focusDisplay(primaryDisplayId)
            }
            runOnUiThread {
                hasBottomAppRunning = false
                lastBottomPackage = null
                // Keep bottom display surface visible so dual-screen apps (like SeedlessDS) immediately reappear;
                // tapping the button a second time (now a gauge icon) opens the Performance Dashboard.
                binding.bottomHomeOverlay.visibility = View.GONE
                updateBottomActionButton()
                val msg = if (ok && prefs.isDualScreenApp(activeTopPackage)) {
                    "Closed bottom app — returned to dual-screen"
                } else if (ok) {
                    "Closed bottom app"
                } else {
                    "Bottom display cleared"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLaunchOnBottomPicker() {
        val targetDisplayId = activeBottomDisplayId
        if (targetDisplayId <= 0) {
            Toast.makeText(this, "Bottom virtual display not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val showAll = prefs.isShowAllApps()
        val hidden = prefs.getHiddenPackages()
        val candidates = if (showAll) allApps else allApps.filterNot { hidden.contains(it.packageName) }
        if (candidates.isEmpty()) return

        val names = candidates.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Launch App on Bottom Screen")
            .setItems(names) { _, which ->
                launchAppOnDisplay(candidates[which], targetDisplayId, hideLauncherAfter = false)
            }
            .show()
    }

    private fun launchAppOnDisplay(app: AppInfo, displayId: Int, hideLauncherAfter: Boolean) {
        if (displayId <= 0) {
            Toast.makeText(this, "Virtual display is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val isLaunchingDualScreen = prefs.isDualScreenApp(app.packageName)
        var targetDisplayId = displayId
        var redirectedToTopFromDualScreen = false

        if (isLaunchingDualScreen) {
            // Dual-screen Presentation apps (SeedlessDS, DraStic, Azahar, etc.) must always run on primaryDisplayId
            // with secondaryDisplayId cleared so their Presentation attaches cleanly to both screens.
            targetDisplayId = primaryDisplayId
            if (isSwapped) {
                isSwapped = false
                syncSurfaceMetadata()
            }
            binding.bottomHomeOverlay.visibility = View.GONE
            binding.bottomLauncherOverlay.visibility = View.GONE
            val hadBottom = hasBottomAppRunning || !lastBottomPackage.isNullOrBlank()
            hasBottomAppRunning = false
            lastBottomPackage = null
            if (hadBottom && secondaryDisplayId > 0) {
                ioExecutor.execute { userService?.closeTasksOnDisplay(secondaryDisplayId) }
            }
        } else if (displayId == secondaryDisplayId &&
            prefs.isDualScreenApp(activeTopPackage) &&
            !hasBottomAppRunning
        ) {
            // Android's TYPE_PRESENTATION layer draws over normal Activities on secondaryDisplayId while a
            // dual-screen app is actively resumed on top. Route the new app to top to cleanly pause the
            // dual-screen app so the new app is immediately visible.
            targetDisplayId = primaryDisplayId
            redirectedToTopFromDualScreen = true
            binding.bottomHomeOverlay.visibility = View.GONE
        }

        val finalDisplayId = targetDisplayId
        val previousTopPackage = activeTopPackage
        if (isLaunchingDualScreen) {
            lastDualScreenApp = app
            pausedDualScreenApp = null
            updateReturnDualScreenVisibility()
        } else if (finalDisplayId == primaryDisplayId &&
            previousTopPackage != null &&
            previousTopPackage != app.packageName &&
            prefs.isDualScreenApp(previousTopPackage)
        ) {
            val paused = lastDualScreenApp?.takeIf { it.packageName == previousTopPackage }
                ?: allApps.firstOrNull { it.packageName == previousTopPackage }
            if (paused != null) {
                pausedDualScreenApp = paused
                binding.btnReturnDualScreen.text = "↩ Return to ${paused.label}"
                updateReturnDualScreenVisibility()
            }
        }

        if (finalDisplayId == primaryDisplayId) {
            activeTopPackage = app.packageName
            val profile = prefs.getAppProfile(app.packageName)
            activeProfileSplit = profile?.splitPercent
            activeProfileOrientation = profile?.orientationMode
            when (profile?.touchOverride ?: SimulatorPrefs.PROFILE_TOUCH_DEFAULT) {
                SimulatorPrefs.PROFILE_TOUCH_FORCE_SHOW -> {
                    manualTouchOverride = true
                    applyTouchControlsVisibility(true)
                }
                SimulatorPrefs.PROFILE_TOUCH_FORCE_HIDE -> {
                    manualTouchOverride = false
                    applyTouchControlsVisibility(false)
                }
                else -> {
                    manualTouchOverride = null
                    updatePhysicalControllerState()
                }
            }
            applyOrientationMode()
            applyDisplayLayout()
            syncSurfaceMetadata()

            val pairedPkg = profile?.pairedBottomPackage
            if (!pairedPkg.isNullOrBlank() && !isLaunchingDualScreen && secondaryDisplayId > 0) {
                val pairedApp = allApps.firstOrNull { it.packageName == pairedPkg }
                if (pairedApp != null) {
                    binding.root.postDelayed({
                        launchAppOnDisplay(pairedApp, secondaryDisplayId, hideLauncherAfter = false)
                    }, 350L)
                }
            }
        } else if (finalDisplayId == secondaryDisplayId) {
            lastBottomPackage = app.packageName
            hasBottomAppRunning = true
            binding.bottomHomeOverlay.visibility = View.GONE
            applyDisplayLayout()
            syncSurfaceMetadata()
        }
        updateBottomActionButton()
        refreshLauncherList()

        // Prefer Shizuku shell-privileged launch (am start --display) first:
        // Android 16+ Background Activity Launch (BAL) can silently drop startActivity() from
        // Display 0 when MainActivity uses FLAG_NOT_FOCUSABLE or when switching between apps
        // already focused on the virtual display. Fall back to startActivity() only if needed.
        val service = userService
        if (service != null) {
            if (hideLauncherAfter || isLaunchingDualScreen || redirectedToTopFromDualScreen) {
                binding.etTopSearch.clearFocus()
                binding.launcherOverlay.visibility = View.GONE
            }
            ioExecutor.execute {
                val ok = try {
                    service.launchAppOnDisplay(app.packageName, app.activityName, finalDisplayId)
                } catch (_: Throwable) {
                    false
                }
                if (ok) {
                    try { Thread.sleep(180L) } catch (_: InterruptedException) {}
                    try { service.focusDisplay(-finalDisplayId) } catch (_: Throwable) {}
                    runOnUiThread {
                        addLocalLog("Launched ${app.packageName} on display $finalDisplayId (via service)")
                        if (redirectedToTopFromDualScreen) {
                            Toast.makeText(this, "Opened ${app.label} on top (paused dual-screen app)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        try {
                            val intent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                component = ComponentName(app.packageName, app.activityName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            val options = ActivityOptions.makeBasic().setLaunchDisplayId(finalDisplayId)
                            startActivity(intent, options.toBundle())
                            addLocalLog("Launched ${app.packageName} on display $finalDisplayId (fallback)")
                            binding.root.postDelayed({ ioExecutor.execute { userService?.focusDisplay(-finalDisplayId) } }, 450L)
                        } catch (_: Throwable) {
                            Toast.makeText(this, "Failed to launch ${app.label}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(app.packageName, app.activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(finalDisplayId)
                startActivity(intent, options.toBundle())
                addLocalLog("Launched ${app.packageName} on display $finalDisplayId")
                if (hideLauncherAfter || isLaunchingDualScreen || redirectedToTopFromDualScreen) {
                    binding.etTopSearch.clearFocus(); binding.launcherOverlay.visibility = View.GONE
                }
            } catch (_: Throwable) {
                Toast.makeText(this, "Failed to launch ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shutdownAndExit() {
        teardownShizukuDisplays()
        finishAndRemoveTask()
    }

    private fun teardownShizukuDisplays() {
        try {
            userService?.releaseDisplays()
            userService?.destroy()
        } catch (_: Throwable) {
        }
        try {
            if (isServiceBound) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        } catch (_: Throwable) {
        }
        userService = null
        isServiceBound = false
        primaryDisplayId = -1
        secondaryDisplayId = -1
    }


    private fun showDebugLogDialog() {
        ioExecutor.execute {
            val serviceLog = try {
                userService?.debugLog ?: "(UserService not bound)"
            } catch (e: Throwable) {
                "(Failed to read UserService log: ${e.message})"
            }
            val displayTasks = try {
                userService?.displayTasksSummary ?: "(UserService not bound)"
            } catch (e: Throwable) {
                "(Failed to read display tasks: ${e.message})"
            }
            val fullLog = buildString {
                appendLine("=== Device & Shizuku ===")
                appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Shizuku alive: ${Shizuku.pingBinder()}, Bound: $isServiceBound")
                appendLine("Primary(1920x1080) ID: $primaryDisplayId, Secondary(1240x1080) ID: $secondaryDisplayId, Swapped: $isSwapped")
                appendLine()
                appendLine("=== Active Display Tasks ===")
                appendLine(displayTasks)
                val crashFile = java.io.File(filesDir, "last_crash.txt")
                if (crashFile.exists()) {
                    val crashText = try { crashFile.readText().take(3000) } catch (_: Throwable) { "" }
                    if (crashText.isNotBlank()) {
                        appendLine("\n=== Last Crash ===")
                        appendLine(crashText)
                    }
                }
                if (localLogs.isNotEmpty()) {
                    appendLine("\n=== App Log ===")
                    localLogs.forEach { appendLine(it) }
                }
                appendLine("\n=== UserService Log ===")
                appendLine(serviceLog.ifBlank { "(empty)" })
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Debug Log")
                    .setMessage(fullLog)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("AynThorDebugLog", fullLog))
                        Toast.makeText(this, "Debug log copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Clear Crash") { _, _ ->
                        try { java.io.File(filesDir, "last_crash.txt").delete() } catch (_: Throwable) {}
                        Toast.makeText(this, "Saved crash log cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }



    private fun setupBottomHomeScreen() {
        binding.btnHomeTopApps.setOnClickListener {
            binding.launcherOverlay.visibility = View.VISIBLE
            if (launcherAdapter.itemCount > 0 && launcherAdapter.selectedPosition < 0) {
                launcherAdapter.setSelectedPosition(0)
            }
        }
        binding.btnHomeBottomApps.setOnClickListener {
            binding.bottomLauncherOverlay.visibility = View.VISIBLE
        }
        binding.btnHomeSettings.setOnClickListener {
            showSettingsDialog()
        }
        binding.btnHomeSwap.setOnClickListener {
            swapScreens()
        }
        binding.btnHomeHideDashboard.setOnClickListener {
            binding.bottomHomeOverlay.visibility = View.GONE
            updateBottomActionButton()
        }

        val audioMgr = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        binding.seekVolume.max = maxVol
        binding.seekVolume.progress = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (primaryDisplayId > 0) {
                    ioExecutor.execute { userService?.focusDisplay(primaryDisplayId) }
                }
            }
        })

        val curBright = window.attributes.screenBrightness
        binding.seekBrightness.progress = if (curBright >= 0f) (curBright * 100f).toInt().coerceIn(5, 100) else 65
        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val lp = window.attributes
                    lp.screenBrightness = (progress.coerceIn(5, 100) / 100f)
                    window.attributes = lp
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (primaryDisplayId > 0) {
                    ioExecutor.execute { userService?.focusDisplay(primaryDisplayId) }
                }
            }
        })

        binding.btnCcWifi.setOnClickListener {
            try {
                val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                startActivity(intent)
            } catch (_: Throwable) {
                try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Throwable) {}
            }
        }
        binding.btnCcBluetooth.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Throwable) {}
        }
        binding.btnCcSleepBottom.setOnClickListener {
            binding.bottomSleepOverlay.visibility = View.VISIBLE
        }
        binding.bottomSleepOverlay.setOnClickListener {
            binding.bottomSleepOverlay.visibility = View.GONE
        }

        applyBottomHomeWidgetVisibility()
    }

    private fun applyBottomHomeWidgetVisibility() {
        binding.homeClockCard.visibility = if (prefs.isShowHomeClock()) View.VISIBLE else View.GONE
        binding.homePerfCard.visibility = if (prefs.isShowHomePerf()) View.VISIBLE else View.GONE
        binding.homeShortcutsCard.visibility = if (prefs.isShowHomeShortcuts()) View.VISIBLE else View.GONE

        val showCc = prefs.isShowControlCenter()
        val showBright = prefs.isShowCcBrightness()
        val showVol = prefs.isShowCcVolume()
        val showWifi = prefs.isShowCcWifi()
        val showBt = prefs.isShowCcBluetooth()
        val showSleep = prefs.isShowCcSleepBottom()
        val anyCcItem = showBright || showVol || showWifi || showBt || showSleep

        binding.homeControlsCard.visibility = if (showCc && anyCcItem) View.VISIBLE else View.GONE
        binding.ccBrightnessRow.visibility = if (showBright) View.VISIBLE else View.GONE
        binding.ccVolumeRow.visibility = if (showVol) View.VISIBLE else View.GONE
        binding.btnCcWifi.visibility = if (showWifi) View.VISIBLE else View.GONE
        binding.btnCcBluetooth.visibility = if (showBt) View.VISIBLE else View.GONE
        binding.btnCcSleepBottom.visibility = if (showSleep) View.VISIBLE else View.GONE
        binding.ccTogglesRow.visibility = if (showWifi || showBt || showSleep) View.VISIBLE else View.GONE
    }

    private fun updateBottomHomeDashboard() {
        if (binding.bottomHomeOverlay.visibility != View.VISIBLE) return
        val now = Date()
        if (prefs.isShowHomeClock()) {
            binding.tvHomeClock.text = clockFormat.format(now)
            binding.tvHomeDate.text = dateFormat.format(now)
        }
        if (prefs.isShowHomePerf()) {
            val battIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val tempTenths = battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val battTempC = tempTenths / 10.0
            val bolt = if (charging) " ⚡" else ""
            binding.tvPerfBattery.text = if (pct >= 0) {
                String.format(Locale.US, "BAT: %d%%%s (%.0f°C)", pct, bolt, battTempC)
            } else {
                "BAT: --%"
            }

            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            val usedGb = (mi.totalMem - mi.availMem) / (1024.0 * 1024.0 * 1024.0)
            binding.tvPerfRam.text = String.format(Locale.US, "RAM: %.1f / %.0f GB", usedGb, totalGb)

            val svc = userService
            val topDisp = primaryDisplayId
            if (svc != null) {
                perfExecutor.execute {
                    val raw = try {
                        svc.getPerformanceStats(topDisp)
                    } catch (_: Throwable) {
                        null
                    }
                    if (!raw.isNullOrBlank()) {
                        val parts = raw.split("|")
                        if (parts.size >= 4) {
                            runOnUiThread {
                                binding.tvPerfCpu.text = "CPU: ${parts[0]} (${parts[1]})"
                                binding.tvPerfGpu.text = "GPU: ${parts[2]} (${parts[3]})"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyAppTheme() {
        val mode = prefs.getThemeMode()
        val nightMode = when (mode) {
            SimulatorPrefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SimulatorPrefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        val isLight = when (mode) {
            SimulatorPrefs.THEME_LIGHT -> true
            SimulatorPrefs.THEME_DARK -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        }
        isLightThemeActive = isLight
        if (::settingsSheet.isInitialized) settingsSheet.refreshTheme(isLight)
        if (::onboardingOverlay.isInitialized) onboardingOverlay.refreshTheme(isLight)

        val rootBg = Color.parseColor(if (isLight) "#F4F4F8" else "#000000")
        val overlayBg = Color.parseColor(if (isLight) "#F2F4F4F8" else "#EE0B0B0F")
        val cardBg = Color.parseColor(if (isLight) "#E4E4EC" else "#181820")
        val inputBg = Color.parseColor(if (isLight) "#FFFFFF" else "#1E1E28")
        val primaryText = Color.parseColor(if (isLight) "#18181B" else "#FFFFFF")
        val secondaryText = Color.parseColor(if (isLight) "#52525B" else "#9CA3AF")
        val pillBg = Color.parseColor(if (isLight) "#E4E4EC" else "#1A1A20")

        binding.rootLayout.setBackgroundColor(rootBg)
        binding.launcherOverlay.setBackgroundColor(overlayBg)
        val msBg = if (isLight) Color.parseColor("#FFE5ECFB") else Color.parseColor("#FF1A2233")
        binding.topMultiSelectBar.setBackgroundColor(msBg)
        binding.bottomMultiSelectBar.setBackgroundColor(msBg)
        binding.tvTopSelectCount.setTextColor(primaryText)
        binding.tvBottomSelectCount.setTextColor(primaryText)
        binding.topSplashOverlay.setBackgroundColor(if (isLight) Color.parseColor("#FFF4F5F8") else Color.parseColor("#FF0B0B0F"))
        binding.tvSplashTitle.setTextColor(primaryText)
        binding.tvSplashSubtitle.setTextColor(secondaryText)
        binding.bottomLauncherOverlay.setBackgroundColor(overlayBg)
        binding.bottomHomeOverlay.setBackgroundColor(overlayBg)

        binding.homeClockCard.setBackgroundColor(cardBg)
        binding.homePerfCard.setBackgroundColor(cardBg)
        binding.homeShortcutsCard.setBackgroundColor(cardBg)
        binding.homeControlsCard.setBackgroundColor(cardBg)

        binding.launcherTitle.setTextColor(primaryText)
        binding.tvBottomLauncherTitle.setTextColor(primaryText)
        binding.tvHomeClock.setTextColor(primaryText)
        binding.tvHomeDate.setTextColor(secondaryText)
        binding.tvPerfRam.setTextColor(primaryText)
        binding.tvPerfCpu.setTextColor(secondaryText)
        binding.tvPerfGpu.setTextColor(secondaryText)

        binding.etTopSearch.setBackgroundColor(inputBg)
        binding.etTopSearch.setTextColor(primaryText)
        binding.etBottomSearch.setBackgroundColor(inputBg)
        binding.etBottomSearch.setTextColor(primaryText)

        binding.btnToggleLeftStick.setBackgroundColor(pillBg)
        binding.btnToggleLeftStick.setTextColor(secondaryText)
        binding.btnToggleRightStick.setBackgroundColor(pillBg)
        binding.btnToggleRightStick.setTextColor(secondaryText)

        launcherAdapter.isLightTheme = isLight
        bottomLauncherAdapter.isLightTheme = isLight

        binding.virtualDpad.applyTheme(isLight)
        binding.virtualLeftStick.applyTheme(isLight)
        binding.virtualFaceButtons.applyTheme(isLight)
        binding.virtualRightStick.applyTheme(isLight)
        binding.btnL1.applyTheme(isLight)
        binding.btnL2.applyTheme(isLight)
        binding.btnR1.applyTheme(isLight)
        binding.btnR2.applyTheme(isLight)
        binding.btnSelect.applyTheme(isLight)
        binding.btnStart.applyTheme(isLight)
        binding.btnL3.applyTheme(isLight)
        binding.btnR3.applyTheme(isLight)
    }

    private fun applyAllPreferences() {
        if (::layoutEditor.isInitialized) {
            layoutEditor.resetForPreferenceImport()
        }
        binding.virtualFaceButtons.isXboxLayout = (prefs.getFaceButtonLayout() == SimulatorPrefs.FACE_LAYOUT_XBOX)
        applyAppTheme()
        applyOrientationMode()
        applyDisplayLayout()
        syncSurfaceMetadata()
        // Update visibility/stick modes first, then apply saved custom layout after one layout pass
        // so legacy backups without saved baseWdp/baseHdp measure true post-import container sizes.
        applyStickModes()
        applyBottomHomeWidgetVisibility()
        updatePhysicalControllerState()
        updateDrawerFilterButtons()
        refreshLauncherList()
        updateBottomActionButton()
        binding.root.post {
            applyGamepadAppearance()
        }
    }

    private fun setupSettingsSheet() {
        settingsSheet = SettingsSheetController(
            context = this,
            prefs = prefs,
            callbacks = object : SettingsSheetController.Callbacks {
                override fun onDisplaySettingsChanged(orientationChanged: Boolean) {
                    activeProfileSplit = null
                    activeProfileOrientation = null
                    if (orientationChanged) {
                        applyOrientationMode()
                    }
                    applyDisplayLayout()
                    syncSurfaceMetadata()
                }

                override fun onBottomHomeSettingsChanged() {
                    applyBottomHomeWidgetVisibility()
                }

                override fun onGamepadSettingsChanged(layoutChanged: Boolean) {
                    if (layoutChanged) {
                        binding.virtualFaceButtons.isXboxLayout =
                            (prefs.getFaceButtonLayout() == SimulatorPrefs.FACE_LAYOUT_XBOX)
                    }
                    applyGamepadAppearance()
                }

                override fun onThemeChanged() {
                    applyAppTheme()
                }

                override fun onDrawerSettingsChanged() {
                    refreshLauncherList()
                }

                override fun onExportBackupRequested() {
                    exportBackupLauncher.launch("DroiDS-backup.json")
                }

                override fun onImportBackupRequested() {
                    importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }

                override fun onReplayOnboardingRequested() {
                    if (::onboardingOverlay.isInitialized) {
                        onboardingOverlay.refreshTheme(isLightThemeActive)
                        onboardingOverlay.showWizard(startStep = 0)
                    }
                }

                override fun onEditTouchLayoutRequested() {
                    if (::layoutEditor.isInitialized) {
                        layoutEditor.enterEditMode()
                    }
                }

                override fun onOpenDebugLogRequested() {
                    showDebugLogDialog()
                }

                override fun onCheckForUpdatesRequested() {
                    if (::appUpdater.isInitialized) {
                        appUpdater.checkForUpdates(manual = true)
                    }
                }

                override fun onOpenUrlRequested(url: String) {
                    if (::appUpdater.isInitialized) {
                        appUpdater.openUrl(url)
                    }
                }

                override fun onVisibilityChanged(visible: Boolean) {
                    updateWindowFocusability()
                }
            }
        )
        settingsSheet.attachTo(binding.rootLayout)
    }

    private fun setupLayoutEditor() {
        layoutEditor = LayoutEditorController(
            context = this,
            prefs = prefs,
            freeControlsLayer = binding.freeControlsLayer,
            callbacks = object : LayoutEditorController.Callbacks {
                override fun onSplitPercentChanged() {
                    activeProfileSplit = null
                    applyDisplayLayout()
                    syncSurfaceMetadata()
                }

                override fun onEditModeChanged(editing: Boolean) {
                    updateWindowFocusability()
                }

                override fun onRequestNormalVisibilityRestore() {
                    updatePhysicalControllerState()
                    applyGamepadAppearance()
                }
            }
        )
        layoutEditor.registerItems(
            linkedMapOf(
                LayoutEditorController.ItemId.L2 to binding.btnL2,
                LayoutEditorController.ItemId.L1 to binding.btnL1,
                LayoutEditorController.ItemId.DPAD to binding.virtualDpad,
                LayoutEditorController.ItemId.LSTICK to binding.leftStickWrapper,
                LayoutEditorController.ItemId.SELECT to binding.btnSelect,
                LayoutEditorController.ItemId.R1 to binding.btnR1,
                LayoutEditorController.ItemId.R2 to binding.btnR2,
                LayoutEditorController.ItemId.ABXY to binding.virtualFaceButtons,
                LayoutEditorController.ItemId.RSTICK to binding.rightStickWrapper,
                LayoutEditorController.ItemId.START to binding.btnStart,
                LayoutEditorController.ItemId.LEFT_BAR to binding.leftUtilityBar,
                LayoutEditorController.ItemId.RIGHT_BAR to binding.rightUtilityBar
            )
        )
        layoutEditor.attachTo(binding.rootLayout)
    }

    private fun setupOnboardingOverlay() {
        onboardingOverlay = OnboardingOverlayController(
            context = this,
            prefs = prefs,
            callbacks = object : OnboardingOverlayController.Callbacks {
                override fun onRequestShizukuPermission() {
                    if (Shizuku.pingBinder()) {
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
                    } else {
                        checkShizukuAndBind()
                    }
                }

                override fun onRetryShizukuBind() {
                    checkShizukuAndBind()
                }

                override fun onVisibilityChanged(visible: Boolean) {
                    updateWindowFocusability()
                }
            }
        )
        onboardingOverlay.attachTo(binding.rootLayout)
    }

    private fun showSettingsDialog() {
        if (!::settingsSheet.isInitialized) {
            setupSettingsSheet()
        }
        settingsSheet.refreshTheme(isLightThemeActive)
        settingsSheet.show()
    }

    private fun showAppProfileDialog(app: AppInfo) {
        val existing = prefs.getAppProfile(app.packageName) ?: SimulatorPrefs.AppProfile()
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scroll.addView(container)

        fun header(t: String) = container.addView(TextView(this).apply {
            text = t; textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 6)
        })

        header("Height Split Override")
        val splitLabels = arrayOf("Global Default", "45% Top / 55% Bottom", "50% Top / 50% Bottom", "53% Top / 47% Bottom", "58% Top / 42% Bottom", "62% Top / 38% Bottom")
        val splitValues: Array<Float?> = arrayOf(null, 0.45f, 0.50f, 0.53f, 0.58f, 0.62f)
        val splitSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, splitLabels)
            val idx = if (existing.splitPercent == null) 0 else {
                (1 until splitValues.size).minByOrNull { kotlin.math.abs((splitValues[it] ?: 0.53f) - existing.splitPercent) } ?: 0
            }
            setSelection(idx)
        }
        container.addView(splitSpinner)

        header("Orientation Override")
        val orientLabels = arrayOf("Global Default", "Landscape", "Portrait", "Auto-Rotate", "Follow Phone Settings")
        val orientValues: Array<Int?> = arrayOf(null, SimulatorPrefs.ORIENTATION_LANDSCAPE, SimulatorPrefs.ORIENTATION_PORTRAIT, SimulatorPrefs.ORIENTATION_AUTO, SimulatorPrefs.ORIENTATION_SYSTEM)
        val orientSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, orientLabels)
            val idx = orientValues.indexOf(existing.orientationMode).takeIf { it >= 0 } ?: 0
            setSelection(idx)
        }
        container.addView(orientSpinner)

        header("Virtual Touch Controls")
        val touchLabels = arrayOf("Global Default (Auto)", "Always Show Touch Controls", "Always Hide Touch Controls")
        val touchSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, touchLabels)
            setSelection(existing.touchOverride.coerceIn(0, 2))
        }
        container.addView(touchSpinner)

        header("Auto-Launch Companion App on Bottom")
        val sortedApps = allApps.sortedBy { it.label.lowercase() }
        val pairedLabels = mutableListOf("None")
        val pairedPkgs = mutableListOf<String?>(null)
        for (candidate in sortedApps) {
            if (candidate.packageName != app.packageName) {
                pairedLabels.add(candidate.label)
                pairedPkgs.add(candidate.packageName)
            }
        }
        val pairedSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, pairedLabels)
            val idx = pairedPkgs.indexOf(existing.pairedBottomPackage).takeIf { it >= 0 } ?: 0
            setSelection(idx)
        }
        container.addView(pairedSpinner)

        AlertDialog.Builder(this)
            .setTitle("Profile: ${app.label}")
            .setView(scroll)
            .setPositiveButton("Save Profile") { _, _ ->
                val updated = SimulatorPrefs.AppProfile(
                    splitPercent = splitValues[splitSpinner.selectedItemPosition],
                    orientationMode = orientValues[orientSpinner.selectedItemPosition],
                    touchOverride = touchSpinner.selectedItemPosition,
                    pairedBottomPackage = pairedPkgs[pairedSpinner.selectedItemPosition]
                )
                prefs.setAppProfile(app.packageName, updated)
                Toast.makeText(this, "Saved profile for ${app.label}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset") { _, _ ->
                prefs.setAppProfile(app.packageName, null)
                Toast.makeText(this, "Cleared profile for ${app.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleTopLauncherKey(keyCode: Int, isDown: Boolean): Boolean {
        if (!isDown) {
            return keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1
            )
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveTopLauncherSelection(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveTopLauncherSelection(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveTopLauncherSelection(-5); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveTopLauncherSelection(5); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (val item = launcherAdapter.getSelectedItem()) {
                    is DrawerItem.Folder -> openDrawerFolder(isTop = true, folderName = item.name)
                    is DrawerItem.App -> {
                        prefs.recordAppLaunch(item.app.packageName)
                        launchAppOnDisplay(item.app, activeTopDisplayId, hideLauncherAfter = true)
                    }
                    null -> Unit
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (selectedTopFolder != null) {
                    openDrawerFolder(isTop = true, folderName = null)
                } else {
                    binding.etTopSearch.clearFocus()
                    binding.launcherOverlay.visibility = View.GONE
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> { cycleTopFolder(-1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { cycleTopFolder(1); true }
            else -> false
        }
    }

    private fun handleTopLauncherStick(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLauncherStickNavMs < 180L) return
        val delta = when {
            x < -0.55f -> -1
            x > 0.55f -> 1
            y < -0.55f -> -5
            y > 0.55f -> 5
            else -> 0
        }
        if (delta != 0) {
            lastLauncherStickNavMs = now
            moveTopLauncherSelection(delta)
        }
    }

    private fun moveTopLauncherSelection(delta: Int) {
        val count = launcherAdapter.itemCount
        if (count <= 0) return
        val current = if (launcherAdapter.selectedPosition < 0) 0 else launcherAdapter.selectedPosition
        val next = (current + delta).coerceIn(0, count - 1)
        launcherAdapter.setSelectedPosition(next)
        binding.appRecyclerView.smoothScrollToPosition(next)
    }

    private fun cycleTopFolder(step: Int) {
        val allFolders = listOf<String?>(null) + prefs.getFolders()
        if (allFolders.size <= 1) return
        val idx = allFolders.indexOf(selectedTopFolder).coerceAtLeast(0)
        val nextIdx = (idx + step + allFolders.size) % allFolders.size
        selectedTopFolder = allFolders[nextIdx]
        updateDrawerFilterButtons()
        refreshLauncherList()
        if (launcherAdapter.itemCount > 0) launcherAdapter.setSelectedPosition(0)
    }

    private fun scheduleEscapedAppReclaim(retryIfUnfound: Boolean) {
        val pkg = activeTopPackage ?: return
        val topDisplay = primaryDisplayId
        val bottomDisplay = secondaryDisplayId
        val service = userService ?: return
        if (topDisplay <= 0 || pkg == packageName) return
        ioExecutor.execute {
            try {
                if (retryIfUnfound) {
                    Thread.sleep(140L)
                }
                var movedDisplay = service.reclaimEscapedPackage(pkg, topDisplay, bottomDisplay, packageName)
                if (movedDisplay <= 0 && retryIfUnfound) {
                    Thread.sleep(280L)
                    movedDisplay = service.reclaimEscapedPackage(pkg, topDisplay, bottomDisplay, packageName)
                }
                if (movedDisplay > 0) {
                    runOnUiThread {
                        if (movedDisplay == secondaryDisplayId) {
                            binding.bottomHomeOverlay.visibility = View.GONE
                            binding.bottomLauncherOverlay.visibility = View.GONE
                        }
                        addLocalLog("Auto-routed escaped $pkg task to display $movedDisplay")
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::onboardingOverlay.isInitialized) {
            onboardingOverlay.refreshLiveStatus()
        }
        if (!isServiceBound || !Shizuku.pingBinder()) {
            checkShizukuAndBind()
        } else {
            scheduleEscapedAppReclaim(retryIfUnfound = false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && !isChangingConfigurations) {
            scheduleEscapedAppReclaim(retryIfUnfound = true)
        }
        // Release any held virtual keys so nothing stays stuck when switching apps
        keyRepeatRunnables.values.forEach { uiHandler.removeCallbacks(it) }
        pendingKeyUpRunnables.values.forEach { uiHandler.removeCallbacks(it) }
        keyRepeatRunnables.clear()
        val heldKeys = keyDownTimes.keys.toList()
        for (kc in heldKeys) {
            handleVirtualKey(kc, false)
        }
        if (leftStickX != 0f || leftStickY != 0f || rightStickX != 0f || rightStickY != 0f || lTrigger != 0f || rTrigger != 0f) {
            leftStickX = 0f
            leftStickY = 0f
            rightStickX = 0f
            rightStickY = 0f
            lTrigger = 0f
            rTrigger = 0f
            hatX = 0f
            hatY = 0f
            sendVirtualJoystick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        teardownShizukuDisplays()
        uiHandler.removeCallbacks(homeRefreshRunnable)
        perfExecutor.shutdownNow()
        if (::appUpdater.isInitialized) {
            appUpdater.shutdown()
        }
        ioExecutor.shutdownNow()
        inputExecutor.shutdownNow()
    }
}
