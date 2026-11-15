package com.steamlike.controller // 包声明：与其它 Activity 同包

import android.app.ActivityManager // 导入 ActivityManager（检查服务是否运行）
import android.app.AppOpsManager // 导入 AppOpsManager（检查「使用情况访问」授权）
import android.content.Context // 导入 Context
import android.content.Intent // 导入 Intent（Activity 跳转 / 通知服务）
import android.content.IntentFilter // 导入广播过滤器
import android.content.BroadcastReceiver // 导入广播接收器基类
import android.os.Build // 导入 Build（系统版本判断）
import android.os.Bundle // 导入 Bundle（生命周期参数）
import android.os.Process // 导入 Process（读取自身 UID）
import android.provider.Settings // 导入 Settings（悬浮窗权限检查 / 系统设置跳转）
import android.text.InputType // 导入输入类型常量
import android.widget.Button // 导入 Button
import android.widget.EditText // 导入 EditText
import android.widget.LinearLayout // 导入 LinearLayout
import android.widget.ScrollView // 导入 ScrollView
import android.widget.Spinner // 导入 Spinner
import android.widget.Switch // 导入 Switch
import android.widget.TextView // 导入 TextView
import android.widget.ArrayAdapter // 导入 ArrayAdapter（Spinner 适配器）
import android.widget.Toast // 导入 Toast
import androidx.core.content.ContextCompat // 导入 ContextCompat（启动前台服务）
import androidx.appcompat.app.AppCompatActivity // 导入 AppCompatActivity
import com.steamlike.controller.config.AppConfig // 导入运行时配置数据类
import com.steamlike.controller.config.AppConfigStore // 导入运行时配置读写类
import com.steamlike.controller.config.ControllerConfig // 导入控制器配置（JSON 解析）
import com.steamlike.controller.core.ControllerProfile // 导入控制器配置文件（操作集/图层）
import com.steamlike.controller.service.ControllerOverlayService // 导入悬浮窗服务
import com.steamlike.controller.ui.UiKit // 导入 UI 工具类
import java.io.File // 导入 File（配置状态判断）

/**
 * 高级设置二级页面
 *
 * 首页只保留高频操作（启动服务、连接状态、配置导入导出），
 * 把不常调整的选项集中到本页：
 * - 智能暂停 / 手柄捕获 / 白名单 / 拉起应用包名
 * - 右摇杆优化（死区、灵敏度、平滑、加速、光标速度）
 * - 长按映射（阈值、长按未映射时回退短按）
 * - WASD 自动锁定（自动锁定、自动居中、居中落点）
 * - 调试（手柄按键测试入口）
 *
 * 所有配置仍然写入同一个运行时配置文件（[AppConfigStore]），
 * 本页只覆盖自己负责的字段，避免把首页的监听地址/端口覆盖掉。
 */
class AdvancedSettingsActivity : AppCompatActivity() { // 声明高级设置页面 Activity

    private lateinit var smartPauseSwitch: Switch // 智能暂停开关
    private lateinit var smartResumeDelayEditText: EditText // 进入白名单应用后延迟恢复捕获（秒）
    private lateinit var whitelistEditText: EditText // 捕获白名单输入框
    private lateinit var usageStatsButton: Button // 「使用情况访问」授权入口
    private lateinit var usageStatsStatusText: TextView // 授权状态文本
    private lateinit var captureSwitch: Switch // 手柄捕获开关
    private lateinit var captureStatusText: TextView // 捕获状态文本
    private lateinit var launcherEditText: EditText // 悬浮窗「游戏」按钮拉起的应用包名
    private var suppressCaptureListener = false // 程序化同步开关时抑制监听

    override fun onCreate(savedInstanceState: Bundle?) { // 页面创建
        super.onCreate(savedInstanceState)
        val appCfg = AppConfigStore.load(this) // 读取当前运行时配置

        // 根布局：固定标题栏 + 可滚动内容
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            UiKit.applyDarkBackground(this, this@AdvancedSettingsActivity) // 与首页一致的深色背景
            fitsSystemWindows = true // 避让状态栏
        }
        // 标题栏（返回 + 标题），样式与其它二级页面一致
        val header = UiKit.titleBar(this, "高级设置", { finish() }).apply {
            setPadding(
                UiKit.dp(this@AdvancedSettingsActivity, 20),
                UiKit.dp(this@AdvancedSettingsActivity, 20),
                UiKit.dp(this@AdvancedSettingsActivity, 20),
                UiKit.dp(this@AdvancedSettingsActivity, 8)
            )
        }
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this) // 内容滚动容器
        val container = LinearLayout(this).apply { // 内容容器
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@AdvancedSettingsActivity, 20),
                UiKit.dp(this@AdvancedSettingsActivity, 8),
                UiKit.dp(this@AdvancedSettingsActivity, 20),
                UiKit.dp(this@AdvancedSettingsActivity, 32)
            )
        }
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)

        /* ===== 智能暂停卡片 ===== */
        val smartCard = UiKit.card(this)
        smartCard.addView(UiKit.sectionTitle(this, "智能暂停（修复右滑返回失效）"))
        smartCard.addView(UiKit.spacer(this, 4))
        smartCard.addView(UiKit.caption(
            this,
            ("焦点窗口会拦截 Android 13+ 的右滑返回手势。白名单应用（如 Winlator）在前台时保持手柄捕获，\n"
                + "切到其他应用自动暂停捕获，右滑返回恢复正常。需要授权\"使用情况访问\"，\n"
                + "以上设置随配置文件一并保存/导出。"),
            0xFF999999.toInt(), 11f
        ))
        smartCard.addView(UiKit.spacer(this, 6))

        smartPauseSwitch = UiKit.switchRow(this, "启用智能暂停", appCfg.smartPauseEnabled) {
            updateUsageStatsStatus() // 刷新授权状态显示
            toastLog(if (it) "智能暂停已开启（切出游戏自动暂停捕获）" else "智能暂停已关闭（手动模式）")
        }
        smartCard.addView(smartPauseSwitch)

        smartCard.addView(UiKit.label(this, "进入白名单应用后延迟恢复捕获（秒，0=立即）"))
        smartResumeDelayEditText = UiKit.input(
            this,
            "默认 12 秒",
            (appCfg.smartResumeDelayMs / 1000).toString()
        ).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        smartCard.addView(smartResumeDelayEditText)
        smartCard.addView(UiKit.spacer(this, 6))

        captureSwitch = UiKit.switchRow(this, "手柄捕获", appCfg.captureEnabled) { checked ->
            if (!suppressCaptureListener) setCaptureSwitch(checked)
        }
        smartCard.addView(captureSwitch)

        captureStatusText = UiKit.caption(this, "", 0xFF999999.toInt(), 11f)
        smartCard.addView(captureStatusText)
        smartCard.addView(UiKit.spacer(this, 6))

        smartCard.addView(UiKit.label(this, "捕获白名单（支持多个包名，逗号分隔）"))
        whitelistEditText = UiKit.input(
            this,
            "如: com.winlator, com.winlator.hub",
            appCfg.captureWhitelist.joinToString(",")
        )
        smartCard.addView(whitelistEditText)
        smartCard.addView(UiKit.spacer(this, 6))

        smartCard.addView(UiKit.label(this, "悬浮窗\"游戏\"按钮拉起的应用包名"))
        launcherEditText = UiKit.input(this, "如: com.winlator", appCfg.launcherPackage)
        smartCard.addView(launcherEditText)
        smartCard.addView(UiKit.spacer(this, 8))

        usageStatsButton = UiKit.button(this, "授权使用情况访问", {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) // 跳转系统「使用情况访问」设置
        })
        smartCard.addView(usageStatsButton)
        usageStatsStatusText = UiKit.caption(this, "", 0xFF999999.toInt(), 11f)
        smartCard.addView(usageStatsStatusText)
        smartCard.addView(UiKit.spacer(this, 6))
        smartCard.addView(UiKit.button(this, "保存智能暂停设置", {
            saveRuntimeConfig()
            notifyServiceWithConfig() // 让运行中的服务立即生效
            toastLog("智能暂停设置已保存")
        }, UiKit.Style.PRIMARY))
        container.addView(smartCard)

        /* ===== 右摇杆优化卡片 ===== */
        val lookCard = UiKit.card(this)
        lookCard.addView(UiKit.sectionTitle(this, "右摇杆优化设置"))
        lookCard.addView(UiKit.spacer(this, 4))
        lookCard.addView(UiKit.caption(
            this,
            ("右摇杆控制鼠标视角时，可通过以下参数调节手感。\n"
                + "• 觉得滑动过快 → 降低「灵敏度」或提高「加速曲线指数」\n"
                + "• 觉得不够流畅/抖动 → 提高「平滑系数」(0.5~0.8 推荐)\n"
                + "• 摇杆居中时仍在漂移 → 提高「死区」(0.1~0.25)\n"
                + "• 轻推难以精确瞄准 → 降低「加速曲线指数」靠近 1.0"),
            0xFF999999.toInt(), 11f
        ))
        lookCard.addView(UiKit.spacer(this, 4))

        val currentSettings = getCurrentProfile().globalSettings // 当前生效的全局设置

        val deadzoneEdit = makeSettingsEdit(
            title = "死区 (Deadzone)",
            hint = "0.0 ~ 1.0，默认 0.15",
            desc = ("小于此值的摇杆输入会被视为零输入，消除摇杆中心漂移。\n"
                + "推荐: 0.10~0.25。摇杆容易漂移可调高；摇杆精准可调低。"),
            value = currentSettings.deadzone
        )
        lookCard.addView(deadzoneEdit.first)

        val sensitivityEdit = makeSettingsEdit(
            title = "右摇杆视角灵敏度 (Look Sensitivity)",
            hint = "0.1 ~ 5.0，默认 0.5",
            desc = ("右摇杆控制视角/鼠标移动的整体速度倍率。\n"
                + "数值越大移动越快。觉得滑动过快请降低此值(如 0.3)；过慢可提高(如 0.8)。"),
            value = currentSettings.lookSensitivity
        )
        lookCard.addView(sensitivityEdit.first)

        val smoothingEdit = makeSettingsEdit(
            title = "视角平滑系数 (Look Smoothing)",
            hint = "0.0 ~ 0.95，默认 0.5",
            desc = ("指数移动平均(EMA)滤波系数，降低摇杆抖动让移动更顺滑。\n"
                + "0=关闭平滑(最跟手但有抖动)，越大越顺滑但延迟增加。\n"
                + "推荐: 0.3~0.7。觉卡顿降一点，觉延迟降一点。"),
            value = currentSettings.lookSmoothing
        )
        lookCard.addView(smoothingEdit.first)

        val accelerationEdit = makeSettingsEdit(
            title = "视角加速曲线指数 (Look Acceleration)",
            hint = "0.5 ~ 3.0，默认 1.5",
            desc = ("非线性响应曲线。1.0=线性(轻推重推一致)；\n"
                + ">1.0 轻推更慢、重推更快(利于精确瞄准又保证转向速度)；\n"
                + "<1.0 轻推更快、重推相对变慢。\n"
                + "觉得轻推过快难以瞄准 → 提高此值(如 1.8~2.2)。"),
            value = currentSettings.lookAcceleration
        )
        lookCard.addView(accelerationEdit.first)

        val cursorSpeedEdit = makeSettingsEdit(
            title = "光标移动速度倍率 (Cursor Speed)",
            hint = "默认 1.0",
            desc = ("左摇杆/其他鼠标移动场景的速度倍率(右摇杆视角由灵敏度控制)。\n"
                + ">1.0 更快，<1.0 更慢。一般保持 1.0 即可。"),
            value = currentSettings.cursorSpeed
        )
        lookCard.addView(cursorSpeedEdit.first)
        lookCard.addView(UiKit.spacer(this, 6))

        lookCard.addView(UiKit.button(this, "保存右摇杆设置", {
            val dz = deadzoneEdit.second.text.toString().trim().toFloatOrNull()
            val sens = sensitivityEdit.second.text.toString().trim().toFloatOrNull()
            val sm = smoothingEdit.second.text.toString().trim().toFloatOrNull()
            val ac = accelerationEdit.second.text.toString().trim().toFloatOrNull()
            val cs = cursorSpeedEdit.second.text.toString().trim().toFloatOrNull()
            if (dz == null || sens == null || sm == null || ac == null || cs == null) {
                toastLog("请填写全部 5 个数值", long = true)
            } else if (!Settings.canDrawOverlays(this)) {
                toastLog("请先授予悬浮窗权限并启动服务", long = true)
            } else {
                ensureServiceRunning()
                val intent = Intent(this, ControllerOverlayService::class.java).apply {
                    action = ControllerOverlayService.ACTION_UPDATE_SETTINGS
                    putExtra(ControllerOverlayService.EXTRA_DEADZONE, dz)
                    putExtra(ControllerOverlayService.EXTRA_LOOK_SENSITIVITY, sens)
                    putExtra(ControllerOverlayService.EXTRA_LOOK_SMOOTHING, sm)
                    putExtra(ControllerOverlayService.EXTRA_LOOK_ACCELERATION, ac)
                    putExtra(ControllerOverlayService.EXTRA_CURSOR_SPEED, cs)
                }
                ContextCompat.startForegroundService(this, intent)
                toastLog("正在保存右摇杆设置...")
            }
        }, UiKit.Style.PRIMARY))
        container.addView(lookCard)

        /* ===== 长按映射设置卡片 ===== */
        val longPressCard = UiKit.card(this)
        longPressCard.addView(UiKit.sectionTitle(this, "长按映射设置"))
        longPressCard.addView(UiKit.spacer(this, 4))
        longPressCard.addView(UiKit.caption(
            this,
            ("在「层与操作集设置」中为某按键配置「长按动作」后，本设置即生效。\n"
                + "• 按住时长达到阈值 → 触发长按动作；未达到 → 触发短按动作\n"
                + "• 阈值越小，越容易触发长按\n"
                + "• 开启下方开关后，未单独配置长按动作的按键，长按松开时会执行其短按动作"),
            0xFF999999.toInt(), 11f
        ))
        longPressCard.addView(UiKit.spacer(this, 8))

        longPressCard.addView(UiKit.label(this, "长按判定阈值"))
        longPressCard.addView(UiKit.caption(this, "按住时长达到该值即视为长按（默认 500 ms）", 0xFF999999.toInt(), 10f))
        longPressCard.addView(UiKit.spacer(this, 4))
        val longPressThresholdOptions = (100..800 step 50).toList() // 100~800 ms，50 ms 一档
        val longPressThresholdSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AdvancedSettingsActivity, android.R.layout.simple_spinner_item,
                longPressThresholdOptions.map { "${it} ms" }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(longPressThresholdOptions.indexOf(currentSettings.longPressThresholdMs).coerceAtLeast(0))
        }
        longPressCard.addView(longPressThresholdSpinner)
        longPressCard.addView(UiKit.spacer(this, 8))

        val longPressFallbackSwitch = UiKit.switchRow(
            this, "长按未映射时执行短按动作", currentSettings.longPressFallbackToShort
        ) {}
        longPressCard.addView(longPressFallbackSwitch)
        longPressCard.addView(UiKit.spacer(this, 6))

        longPressCard.addView(UiKit.button(this, "保存长按设置", {
            if (!Settings.canDrawOverlays(this)) {
                toastLog("请先授予悬浮窗权限并启动服务", long = true)
            } else {
                ensureServiceRunning()
                val intent = Intent(this, ControllerOverlayService::class.java).apply {
                    action = ControllerOverlayService.ACTION_UPDATE_SETTINGS
                    putExtra(ControllerOverlayService.EXTRA_LONG_PRESS_THRESHOLD_MS,
                        longPressThresholdOptions[longPressThresholdSpinner.selectedItemPosition])
                    putExtra(ControllerOverlayService.EXTRA_LONG_PRESS_FALLBACK, longPressFallbackSwitch.isChecked)
                }
                ContextCompat.startForegroundService(this, intent)
                toastLog("正在保存长按设置...")
            }
        }, UiKit.Style.PRIMARY))
        container.addView(longPressCard)

        /* ===== WASD 自动锁定卡片 ===== */
        val wasdLockCard = UiKit.card(this)
        wasdLockCard.addView(UiKit.sectionTitle(this, "WASD 自动锁定"))
        wasdLockCard.addView(UiKit.spacer(this, 4))
        wasdLockCard.addView(UiKit.caption(
            this,
            ("左摇杆移动（WASD）时是否自动锁定鼠标右键：\n"
                + "• 开启：移动时自动把鼠标居中并锁定右键（视角跟随移动方向）\n"
                + "• 关闭：移动不会自动锁定右键，需按键手动锁定\n"
                + "• 居中落点：默认停放到屏幕外下方，可改为屏幕正中央"),
            0xFF999999.toInt(), 11f
        ))
        wasdLockCard.addView(UiKit.spacer(this, 8))

        val wasdAutoLockSwitch = UiKit.switchRow(
            this, "WASD 移动时自动锁定鼠标右键", currentSettings.wasdAutoRightLock
        ) {}
        wasdLockCard.addView(wasdAutoLockSwitch)
        wasdLockCard.addView(UiKit.spacer(this, 4))

        val wasdAutoCenterSwitch = UiKit.switchRow(
            this, "自动锁定前把鼠标移到屏幕正中央", currentSettings.wasdAutoCenterMouse
        ) {}
        wasdLockCard.addView(wasdAutoCenterSwitch)
        wasdLockCard.addView(UiKit.spacer(this, 4))

        val cursorParkSwitch = UiKit.switchRow(
            this, "居中时停放到屏幕外下方（关闭则为屏幕正中央）", currentSettings.cursorParkOffscreen
        ) {}
        wasdLockCard.addView(cursorParkSwitch)
        wasdLockCard.addView(UiKit.spacer(this, 6))

        wasdLockCard.addView(UiKit.button(this, "保存自动锁定", {
            if (!Settings.canDrawOverlays(this)) {
                toastLog("请先授予悬浮窗权限并启动服务", long = true)
            } else {
                ensureServiceRunning()
                val intent = Intent(this, ControllerOverlayService::class.java).apply {
                    action = ControllerOverlayService.ACTION_UPDATE_SETTINGS
                    putExtra(ControllerOverlayService.EXTRA_WASD_AUTO_RIGHT_LOCK, wasdAutoLockSwitch.isChecked)
                    putExtra(ControllerOverlayService.EXTRA_WASD_AUTO_CENTER_MOUSE, wasdAutoCenterSwitch.isChecked)
                    putExtra(ControllerOverlayService.EXTRA_CURSOR_PARK_OFFSCREEN, cursorParkSwitch.isChecked)
                }
                ContextCompat.startForegroundService(this, intent)
                toastLog("正在保存自动锁定设置...")
            }
        }, UiKit.Style.PRIMARY))
        container.addView(wasdLockCard)

        /* ===== 调试卡片 ===== */
        val debugCard = UiKit.card(this)
        debugCard.addView(UiKit.sectionTitle(this, "调试"))
        debugCard.addView(UiKit.spacer(this, 6))
        debugCard.addView(UiKit.button(this, "测试手柄按键（模拟器调试用）", {
            ensureServiceRunning()
            if (LayerEditActivity.steamInputRef == null) {
                toastLog("服务正在初始化，请稍候...")
                var waited = 0
                val tick = 100
                val maxWait = 3000
                scroll.postDelayed(object : Runnable {
                    override fun run() {
                        waited += tick
                        if (LayerEditActivity.steamInputRef != null) {
                            startActivity(Intent(this@AdvancedSettingsActivity, GamepadTestActivity::class.java))
                        } else if (waited < maxWait) {
                            scroll.postDelayed(this, tick.toLong())
                        } else {
                            toastLog("服务初始化超时，请重试", long = true)
                        }
                    }
                }, tick.toLong())
            } else {
                startActivity(Intent(this@AdvancedSettingsActivity, GamepadTestActivity::class.java))
            }
        }))
        container.addView(debugCard)
    }

    /**
     * 保存运行时配置
     *
     * 只覆盖本页负责的字段（智能暂停/白名单/捕获/拉起包名），
     * 服务器地址、端口等首页字段保持文件中的原值。
     */
    private fun saveRuntimeConfig() {
        val cfg = AppConfigStore.load(this).copy(
            smartPauseEnabled = smartPauseSwitch.isChecked,
            smartResumeDelayMs = ((smartResumeDelayEditText.text.toString().trim().toIntOrNull() ?: 12)
                .coerceIn(0, 120)) * 1000,
            captureWhitelist = AppConfig.parseWhitelist(whitelistEditText.text.toString()),
            captureEnabled = captureSwitch.isChecked,
            launcherPackage = launcherEditText.text.toString().trim().ifBlank { AppConfig.DEFAULT_LAUNCHER }
        )
        AppConfigStore.save(this, cfg)
    }

    /** 把最新配置通过 Intent 发给服务（服务运行中时立即生效） */
    private fun notifyServiceWithConfig() {
        if (!isServiceRunning()) return // 服务未运行，等下次启动时读取配置即可
        val cfg = AppConfigStore.load(this)
        val intent = Intent(this, ControllerOverlayService::class.java).apply {
            putExtra(ControllerOverlayService.EXTRA_HOST, cfg.serverHost)
            putExtra(ControllerOverlayService.EXTRA_PORT, cfg.serverPort)
            putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, cfg.smartPauseEnabled)
            putExtra(ControllerOverlayService.EXTRA_WHITELIST, cfg.captureWhitelist.joinToString(","))
            putExtra(ControllerOverlayService.EXTRA_LAUNCHER_PACKAGE, cfg.launcherPackage)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /** 确保映射服务已启动（权限不足时提示并返回） */
    private fun ensureServiceRunning() {
        if (!Settings.canDrawOverlays(this)) {
            toastLog("请先授予悬浮窗权限", long = true)
            return
        }
        saveRuntimeConfig()
        notifyServiceWithConfig()
    }

    /** 捕获开关变化 → 持久化 + 通知服务（悬浮窗同步） */
    private fun setCaptureSwitch(enabled: Boolean) {
        saveRuntimeConfig()
        if (isServiceRunning()) {
            val intent = Intent(this, ControllerOverlayService::class.java).apply {
                action = ControllerOverlayService.ACTION_SET_CAPTURE
                putExtra(ControllerOverlayService.EXTRA_CAPTURE_ENABLED, enabled)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            toastLog("服务未运行，开关状态已保存（启动手柄映射后生效）")
        }
        logD("Capture switch set to $enabled")
    }

    /** 检查 ControllerOverlayService 是否在运行 */
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(100)
        return services.any {
            it.service.className == ControllerOverlayService::class.java.name &&
                it.service.packageName == packageName
        }
    }

    /** 从配置文件同步捕获开关状态 */
    private fun syncCaptureSwitchFromPrefs() {
        if (!::captureSwitch.isInitialized) return
        val enabled = AppConfigStore.load(this).captureEnabled
        suppressCaptureListener = true
        captureSwitch.isChecked = enabled
        suppressCaptureListener = false
        captureStatusText.text = if (enabled) "捕获状态: ✅ 运行中" else "捕获状态: ⏸ 已暂停（可点悬浮窗恢复）"
    }

    /** 检查「使用情况访问」权限是否已授权 */
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 刷新「使用情况访问」授权状态显示 */
    private fun updateUsageStatsStatus() {
        if (!::usageStatsStatusText.isInitialized) return
        val granted = hasUsageStatsPermission()
        usageStatsStatusText.text = if (granted) {
            "使用情况访问: ✅ 已授权（智能暂停可用）"
        } else {
            if (smartPauseSwitch.isChecked) {
                "使用情况访问: ❌ 未授权 — 点击上方按钮前往系统设置开启，否则智能暂停不生效（手动模式）"
            } else {
                "使用情况访问: ❌ 未授权（智能暂停已关闭，不影响手动模式）"
            }
        }
    }

    /** 获取当前生效的 ControllerProfile（服务运行中优先读运行时配置） */
    private fun getCurrentProfile(): ControllerProfile {
        LayerEditActivity.steamInputRef?.profile?.let { return it }
        val configFile = File(filesDir, "steamlike_config.json")
        if (configFile.exists()) {
            return try {
                ControllerConfig.fromJson(configFile.readText())
            } catch (e: Exception) {
                ControllerProfile.createDefault()
            }
        }
        return ControllerProfile.createDefault()
    }

    /** 创建设置项 UI 区块（标题 + 说明 + 数字输入框） */
    private fun makeSettingsEdit(
        title: String,
        hint: String,
        desc: String,
        value: Float
    ): Pair<LinearLayout, EditText> {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, UiKit.dp(this@AdvancedSettingsActivity, 10), 0, 0)
        }
        layout.addView(UiKit.label(this, title))
        layout.addView(UiKit.caption(this, desc, 0xFF999999.toInt(), 10f))
        layout.addView(UiKit.spacer(this, 4))
        val edit = UiKit.input(this, hint, value.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(edit)
        return Pair(layout, edit)
    }

    /** 捕获状态广播接收器（悬浮窗暂停/恢复时同步本页开关） */
    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ControllerOverlayService.ACTION_CAPTURE_STATUS) return
            val capturing = intent.getBooleanExtra(ControllerOverlayService.EXTRA_CAPTURING, true)
            if (::captureSwitch.isInitialized) {
                suppressCaptureListener = true
                captureSwitch.isChecked = capturing
                suppressCaptureListener = false
            }
            if (::captureStatusText.isInitialized) {
                captureStatusText.text = if (capturing) {
                    "捕获状态: ✅ 运行中"
                } else {
                    "捕获状态: ⏸ 已暂停（点悬浮窗'恢复捕获'或此开关恢复）"
                }
            }
            logD("Capture status: capturing=$capturing")
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ControllerOverlayService.ACTION_CAPTURE_STATUS)
        ContextCompat.registerReceiver(
            this, captureStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        syncCaptureSwitchFromPrefs()
        updateUsageStatsStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(captureStatusReceiver)
    }

    /** 日志辅助方法 */
    private fun logD(msg: String) = android.util.Log.d("SteamLikeAdvanced", msg)

    /** Toast 辅助方法（同时写日志） */
    private fun toastLog(msg: String, long: Boolean = false) {
        android.util.Log.i("SteamLikeAdvanced", "Toast: $msg")
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
