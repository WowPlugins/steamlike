package com.steamlike.controller.service  // 包声明：本文件位于 controller.service 包

import android.app.AppOpsManager  // 导入 AppOps 权限管理类
import android.app.Notification  // 导入通知类
import android.app.NotificationChannel  // 导入通知渠道类
import android.app.NotificationManager  // 导入通知管理器类
import android.app.Service  // 导入服务基类
import android.app.PendingIntent  // 导入待定 Intent 类（延迟执行）
import android.bluetooth.BluetoothAdapter  // 导入蓝牙适配器类
import android.bluetooth.BluetoothManager  // 导入蓝牙管理器类
import android.bluetooth.BluetoothProfile  // 导入蓝牙 profile 接口
import android.content.Context  // 导入上下文类
import android.content.Intent  // 导入 Intent 类（组件间通信）
import android.net.Uri  // 导入统一资源标识符类
import android.app.usage.UsageEvents  // 导入使用情况事件类
import android.app.usage.UsageStatsManager  // 导入使用情况统计管理器类
import android.graphics.PixelFormat  // 导入像素格式类
import android.graphics.drawable.GradientDrawable  // 导入渐变绘制类（圆角背景）
import android.graphics.drawable.StateListDrawable  // 导入状态列表绘制类（按下态背景）
import android.os.Build  // 导入系统版本类
import android.os.IBinder  // 导入 Binder 接口
import android.os.Process  // 导入进程类
import android.util.DisplayMetrics  // 导入显示指标类
import android.util.Log  // 导入日志工具类
import android.view.Gravity  // 导入重力定位类
import android.view.KeyEvent  // 导入按键事件类
import android.view.MotionEvent  // 导入触摸事件类
import android.view.View  // 导入视图基类
import android.view.WindowInsets  // 导入窗口 insets 类（系统栏区域）
import android.view.WindowManager  // 导入窗口管理器类
import android.view.animation.AccelerateInterpolator  // 导入加速插值器
import android.view.animation.DecelerateInterpolator  // 导入减速插值器
import android.view.inputmethod.InputMethodManager  // 导入输入法管理器类
import android.widget.Button  // 导入按钮控件
import android.widget.FrameLayout  // 导入帧布局容器
import android.widget.LinearLayout  // 导入线性布局容器
import android.widget.ScrollView  // 导入滚动视图容器
import android.widget.TextView  // 导入文本视图控件
import android.widget.Toast  // 导入 Toast 提示类
import androidx.core.app.NotificationCompat  // 导入通知兼容构建器
import androidx.core.app.ServiceCompat  // 导入服务兼容工具类
import android.content.pm.PackageManager  // 导入包管理器类
import android.content.pm.ServiceInfo  // 导入服务信息类（前台服务类型）
import com.steamlike.controller.LayerEditActivity  // 导入操作层编辑页
import com.steamlike.controller.config.AppConfig  // 导入应用默认配置
import com.steamlike.controller.config.AppConfigStore  // 导入运行时配置存储
import com.steamlike.controller.config.ConfigManager  // 导入配置管理器
import com.steamlike.controller.config.ControllerConfig  // 导入控制器配置（JSON 解析）
import com.steamlike.controller.core.ControllerButton  // 导入手柄按键枚举
import com.steamlike.controller.core.ControllerDevice  // 导入手柄设备类（读取手柄电量）
import com.steamlike.controller.core.GamepadBattery  // 导入手柄电量信息数据类
import com.steamlike.controller.core.KeyMapping  // 导入按键映射数据类
import com.steamlike.controller.core.MouseButton  // 导入鼠标按键枚举
import com.steamlike.controller.core.SteamInput  // 导入 SteamInput 手柄控制器
import com.steamlike.controller.injection.BridgeInputInjector  // 导入桥接事件注入器
import com.steamlike.controller.injection.GamepadInputView  // 导入焦点输入视图
import com.steamlike.controller.injection.InputBridgeServer  // 导入 TCP 桥接服务器
import com.steamlike.controller.mapping.KeyboardMouseMapper  // 导入键盘鼠标映射器
import com.steamlike.controller.mapping.WoWActionSets  // 导入 WoW 操作集预设

/**
 * 悬浮窗前台服务（无操作集版本）
 *
 * ## 职责
 * 1. 启动 TCP 桥接服务器，等待 Windows 客户端连接
 * 2. 创建 **全屏透明焦点窗口** (GamepadInputView) 捕获手柄事件
 * 3. 创建 **悬浮窗 UI** 显示状态、操作层堆栈、层切换按钮
 *
 * ## 双窗口架构
 * ```
 * WindowManager
 *  ├─ GamepadInputView (全屏透明, FLAG_NOT_TOUCHABLE, 可获焦点)
 *  │   → 捕获手柄 KeyEvent / MotionEvent
 *  │   → 触摸事件穿透到下层 Winlator
 *  │
 *  └─ 悬浮窗UI (小面板, FLAG_NOT_FOCUSABLE, 可触摸)
 *      → 显示连接状态、操作层堆栈
 *      → 提供层切换按钮（可拖动）
 * ```
 *
 * 注意: 焦点窗口捕获手柄事件后，触摸事件通过 FLAG_NOT_TOUCHABLE 穿透到 Winlator，
 * 用户仍可正常触摸操作 Winlator 界面。
 *
 * 注意: 有焦点的 overlay 窗口（即使 1x1）会让 Android 14+ 预测式返回手势失效。
 * 因此提供"暂停捕获"：暂停时**真正移除焦点窗口**以恢复侧滑返回手势，代价是
 * 手柄按键不再到达应用，恢复捕获需通过悬浮窗"恢复捕获"按钮或主界面开关
 * （无障碍按键过滤方案已弃用，见 GamepadAccessibilityService）。
 */
class ControllerOverlayService : Service() {  // class=类声明；继承 Service() 前台服务基类，实现手柄映射主控服务

    companion object {  // companion object=伴生对象，定义类级静态常量
        private const val TAG = "SteamLikeService"  // const val=编译期常量；日志标签
        const val CHANNEL_ID = "steamlike_controller"  // const val=编译期常量；通知渠道 ID
        const val NOTIFICATION_ID = 1001  // const val=编译期常量；前台通知 ID
        const val ACTION_STOP = "STOP"  // const val=编译期常量；停止服务的 Intent action
        /** 导出当前配置到指定 URI */
        const val ACTION_EXPORT_CONFIG = "EXPORT_CONFIG"  // const val=编译期常量；导出配置的 Intent action
        /** 从指定 URI 导入配置 */
        const val ACTION_IMPORT_CONFIG = "IMPORT_CONFIG"  // const val=编译期常量；导入配置的 Intent action
        /** 重置为默认配置（删除内部配置文件并重新初始化） */
        const val ACTION_RESET_CONFIG = "RESET_CONFIG"  // const val=编译期常量；重置配置的 Intent action
        /** 暂停悬浮窗（移除焦点窗口和悬浮窗UI，保留TCP服务器和映射器运行） */
        const val ACTION_PAUSE_OVERLAY = "PAUSE_OVERLAY"  // const val=编译期常量；暂停悬浮窗的 Intent action
        /** 恢复悬浮窗（重新创建焦点窗口和悬浮窗UI） */
        const val ACTION_RESUME_OVERLAY = "RESUME_OVERLAY"  // const val=编译期常量；恢复悬浮窗的 Intent action
        /** 更新右摇杆优化设置（GlobalSettings） */
        const val ACTION_UPDATE_SETTINGS = "UPDATE_SETTINGS"  // const val=编译期常量；更新右摇杆设置的 Intent action
        /** 设置捕获开关（MainActivity 调用，extra: EXTRA_CAPTURE_ENABLED） */
        const val ACTION_SET_CAPTURE = "SET_CAPTURE"  // const val=编译期常量；设置捕获开关的 Intent action
        /** 刷新悬浮窗层名（配置导入/重置后调用） */
        const val ACTION_REFRESH_LAYERS = "REFRESH_LAYERS"  // const val=编译期常量；刷新层名的 Intent action
        /** 智能暂停开关 (Boolean) */
        const val EXTRA_SMART_PAUSE = "smart_pause"  // const val=编译期常量；智能暂停开关的 extra 键
        /** 捕获白名单包名 (String, 逗号分隔) */
        const val EXTRA_WHITELIST = "capture_whitelist"  // const val=编译期常量；捕获白名单的 extra 键
        /** 悬浮窗"游戏"按钮拉起应用包名 (String) */
        const val EXTRA_LAUNCHER_PACKAGE = "launcher_package"  // const val=编译期常量；拉起应用包名的 extra 键
        /** 捕获开关 (Boolean) */
        const val EXTRA_CAPTURE_ENABLED = "capture_enabled"  // const val=编译期常量；捕获开关的 extra 键
        /** 切换悬浮窗视图（通知栏按钮触发） */
        const val ACTION_TOGGLE_OVERLAY = "TOGGLE_OVERLAY"  // const val=编译期常量；切换悬浮窗视图的 Intent action
        /** 操作层按钮：短按判定为点击（跳转设置页）的时间阈值（毫秒） */
        private const val LAYER_BUTTON_TAP_MS = 300L  // const val=编译期常量；长整型；短按点击判定阈值 300 毫秒
        /**
         * 蓝牙 HID Host profile 常量值（=4）
         *
         * [BluetoothProfile.HID_HOST] 在本项目 compileSdk 的 android.jar 中未暴露，
         * 该值是蓝牙规范中的稳定数值，直接使用字面量，避免编译期未解析。
         */
        private const val HID_HOST_PROFILE = 4  // const val=编译期常量；蓝牙 HID Host profile 数值
        /** Intent extra: 配置文件 URI */
        const val EXTRA_CONFIG_URI = "config_uri"  // const val=编译期常量；配置文件 URI 的 extra 键
        /** Intent extra: TCP监听地址，空表示监听所有接口 */
        const val EXTRA_HOST = "server_host"  // const val=编译期常量；TCP 监听地址的 extra 键
        /** Intent extra: TCP监听端口 */
        const val EXTRA_PORT = "server_port"  // const val=编译期常量；TCP 监听端口的 extra 键
        /** Intent extra: 摇杆死区 (Float, 0.0~1.0) */
        const val EXTRA_DEADZONE = "deadzone"  // const val=编译期常量；摇杆死区的 extra 键
        /** Intent extra: 右摇杆视角灵敏度 (Float, 0.1~5.0) */
        const val EXTRA_LOOK_SENSITIVITY = "look_sensitivity"  // const val=编译期常量；视角灵敏度的 extra 键
        /** Intent extra: 光标移动速度倍率 (Float) */
        const val EXTRA_CURSOR_SPEED = "cursor_speed"  // const val=编译期常量；光标速度倍率的 extra 键
        /** Intent extra: 视角平滑系数 (Float, 0.0~0.95) */
        const val EXTRA_LOOK_SMOOTHING = "look_smoothing"  // const val=编译期常量；视角平滑系数的 extra 键
        /** Intent extra: 视角加速曲线指数 (Float, 0.5~3.0) */
        const val EXTRA_LOOK_ACCELERATION = "look_acceleration"  // const val=编译期常量；视角加速指数的 extra 键

        /** 广播: 客户端连接状态变化 */
        const val ACTION_CLIENT_STATUS = "CLIENT_STATUS"  // const val=编译期常量；客户端连接状态广播 action
        /** Intent extra: 连接状态文本 */
        const val EXTRA_STATUS_TEXT = "status_text"  // const val=编译期常量；状态文本 extra 键
        /** Intent extra: 是否已连接 */
        const val EXTRA_CONNECTED = "connected"  // const val=编译期常量；连接标志 extra 键

        /** 广播: 捕获状态变化（悬浮窗/MainActivity 双向同步） */
        const val ACTION_CAPTURE_STATUS = "CAPTURE_STATUS"  // const val=编译期常量；捕获状态广播 action
        /** Intent extra: 是否正在捕获 */
        const val EXTRA_CAPTURING = "capturing"  // const val=编译期常量；捕获标志 extra 键

        /** 智能监控轮询间隔（毫秒） */
        private const val SMART_MONITOR_INTERVAL_MS = 1500L  // const val=编译期常量；长整型；智能监控轮询间隔 1.5 秒

        /** 前台应用查询时间窗（毫秒） */
        private const val SMART_FOREGROUND_WINDOW_MS = 60_000L  // const val=编译期常量；长整型；前台应用查询时间窗 60 秒

        /** 默认层名匹配（未重命名的层显示预设中文名） */
        private val DEFAULT_LAYER_NAME_REGEX = Regex("Layer\\d+")  // val=只读变量；匹配 "Layer数字" 形式默认层名的正则

        // ===== 悬浮窗配色（半透明，尽量透出背面画面）=====
        /** 展开面板背景（深色半透明，圆角） */
        private const val COLOR_PANEL = 0x991C1C1C.toInt()  // const val=编译期常量；展开面板背景色（深色半透明）
        /** 层按钮正常态背景 */
        private const val COLOR_LAYER_NORMAL = 0x66333333.toInt()  // const val=编译期常量；层按钮正常态背景色
        /** 层按钮激活态背景（绿色） */
        private const val COLOR_LAYER_ACTIVE = 0xAA4CAF50.toInt()  // const val=编译期常量；层按钮激活态背景色（绿色）
        /** 功能按钮正常态背景 */
        private const val COLOR_BTN_NORMAL = 0x66444444.toInt()  // const val=编译期常量；功能按钮正常态背景色
        /** 功能按钮按下态背景 */
        private const val COLOR_BTN_PRESSED = 0x666E6E6E.toInt()  // const val=编译期常量；功能按钮按下态背景色
        /** 游戏按钮背景（主操作，深绿半透明） */
        private const val COLOR_BTN_GAME = 0xAA2E7D32.toInt()  // const val=编译期常量；游戏按钮背景色（深绿半透明）
        /** 游戏按钮按下态 */
        private const val COLOR_BTN_GAME_PRESSED = 0xAA388E3C.toInt()  // const val=编译期常量；游戏按钮按下态背景色
        /** 关闭按钮背景（危险操作，深红半透明） */
        private const val COLOR_BTN_DANGER = 0x66B71C1C.toInt()  // const val=编译期常量；关闭按钮背景色（深红半透明）
        /** 关闭按钮按下态 */
        private const val COLOR_BTN_DANGER_PRESSED = 0x66D32F2F.toInt()  // const val=编译期常量；关闭按钮按下态背景色
        /** 收起胶囊边框 */
        private const val COLOR_COLLAPSED_STROKE = 0x33FFFFFF  // const val=编译期常量；收起胶囊边框色（半透明白）
        /** 悬浮窗常驻边框（普通态，半透明白色细边） */
        private const val COLOR_OVERLAY_BORDER_NORMAL = 0x33FFFFFF  // const val=编译期常量；悬浮窗常驻边框普通态颜色
        /** 悬浮窗常驻边框（右键长按锁存激活态，红色高亮） */
        private const val COLOR_OVERLAY_BORDER_ACTIVE = 0xFFFF4444.toInt()  // const val=编译期常量；悬浮窗边框激活态颜色（红色高亮）
        /** 按键映射列表项背景 */
        private const val COLOR_MAPPING_ITEM = 0x77222222.toInt()  // const val=编译期常量；按键映射列表项背景色
        /** 按键映射列表项高亮（手柄按键按下时的反馈色） */
        private const val COLOR_MAPPING_ACTIVE = 0xFF2196F3.toInt()  // const val=编译期常量；按键映射列表项高亮色（蓝色）
        /** 手柄电量文本颜色（浅绿色，区别于层名与操作集颜色） */
        private const val COLOR_BATTERY_TEXT = 0xFF8BC34A.toInt()  // const val=编译期常量；电量文本颜色
        /** 手柄电量刷新间隔（毫秒）：电量变化缓慢，60 秒刷新一次足够 */
        private const val BATTERY_REFRESH_INTERVAL_MS = 60_000L  // const val=编译期常量；60_000L=Long 字面量（下划线分隔便于阅读）
    }  // 结束 companion object 伴生对象

    private var windowManager: WindowManager? = null  // var=可变变量；?=可空类型；窗口管理器（管理悬浮窗与焦点窗口）
    private var overlayView: View? = null  // var=可变变量；?=可空类型；悬浮窗常驻容器视图
    /**
     * 悬浮窗常驻容器的圆角边框背景，右键长按锁存时切换边框颜色
     */
    private var overlayFrameBackground: GradientDrawable? = null  // var=可变变量；?=可空类型；悬浮窗常驻容器的圆角边框背景
    private var gamepadInputView: GamepadInputView? = null  // var=可变变量；?=可空类型；全屏透明焦点输入窗口
    private var statusText: TextView? = null  // var=可变变量；?=可空类型；状态文本视图
    private var layerText: TextView? = null  // var=可变变量；?=可空类型；操作层堆栈文本视图
    private var hintText: TextView? = null  // var=可变变量；?=可空类型；快捷键提示文本视图
    /**
     * "暂停/恢复捕获"按钮引用，用于在 isCapturing 状态变化时更新文本
     */
    private var captureButton: Button? = null  // var=可变变量；?=可空类型；"暂停/恢复捕获"按钮引用
    /**
     * 收起状态下显示当前激活层名与状态图标的 TextView 引用
     *
     * 用于操作层切换 / 捕获状态 / 手柄连接状态变化时动态更新悬浮窗文本。
     * 显示规则见 [buildCollapsedText]（三态图标：🎮未连接 / ▶映射中 / ⏸暂停）。
     */
    private var collapsedTextView: TextView? = null  // var=可变变量；?=可空类型；收起状态胶囊文本视图
    private val layerButtons = mutableMapOf<String, Button>()  // val=只读变量；层名→按钮的可变映射表
    /**
     * 按键映射列表页的按钮 → 视图引用，手柄按键按下时高亮对应项
     */
    private val mappingViewItems = mutableMapOf<ControllerButton, TextView>()  // val=只读变量；手柄按钮→映射项视图的映射表
    // 映射列表页子视图引用：切层时原地更新（不重建整个窗口），避免整屏闪屏
    private var mappingTitleView: TextView? = null  // var=可变变量；?=可空类型；映射页标题视图
    private var mappingItemsLayout: LinearLayout? = null  // var=可变变量；?=可空类型；映射项容器布局
    /**
     * 展开面板中显示当前操作集信息的 TextView
     *
     * 操作集切换时由 [onActionSetSwitched] 更新文本（展开面板重建时一并重建）。
     */
    private var actionSetText: TextView? = null  // var=可变变量；?=可空类型；操作集信息文本视图
    /**
     * 展开面板中显示手柄电量的 TextView
     *
     * 由 [refreshBatteryText] 刷新（每 [BATTERY_REFRESH_INTERVAL_MS] 毫秒一次），
     * 未连接手柄（或系统低于 Android 14）时该行隐藏，电量未知时显示"未知"。
     */
    private var batteryText: TextView? = null  // var=可变变量；?=可空类型；手柄电量文本视图
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())  // val=只读变量；主线程消息处理器，用于跨线程回主线程更新 UI

    /**
     * 手柄电量定时刷新任务
     *
     * 自排队循环：每 [BATTERY_REFRESH_INTERVAL_MS] 毫秒刷新一次电量文本。
     * 展开面板未创建时 [batteryText] 为 null，[refreshBatteryText] 内部自动跳过，
     * 因此任务常驻无副作用（Service 销毁时在 [onDestroy] 中移除）。
     */
    private val batteryRefreshTask = object : Runnable {  // val=只读变量；object : Runnable=匿名对象实现接口（语法：object 表达式）
        override fun run() {  // override=覆写接口方法；fun=函数声明；任务体
            refreshBatteryText()  // 后台读取并刷新手柄电量文本
            mainHandler.postDelayed(this, BATTERY_REFRESH_INTERVAL_MS)  // 延迟投递自身，形成循环（语法：postDelayed 延迟投递 Runnable）
        }  // 结束 run 函数
    }  // 结束 batteryRefreshTask 匿名对象

    // 悬浮窗收起/展开/映射列表状态
    private var isExpanded = false  // var=可变变量；悬浮窗是否处于展开状态
    private var isMappingView = false  // var=可变变量；是否处于按键映射列表视图
    private var overlayParams: WindowManager.LayoutParams? = null  // var=可变变量；?=可空类型；悬浮窗窗口布局参数

    /**
     * 最新状态文本缓存
     *
     * 用于解决"展开悬浮窗后仍显示'初始化中'"的问题：
     * startMapper() 在子线程异步完成并调用 updateStatus() 时，
     * 如果用户尚未展开悬浮窗，statusText 为 null，post 的内容被丢弃。
     * 展开时 showExpandedView() 会重新创建 statusText，
     * 通过此字段恢复最近一次的状态文本。
     */
    private var currentStatus: String = "初始化中..."  // var=可变变量；最新状态文本缓存，默认"初始化中..."

    private var steamInput: SteamInput? = null  // var=可变变量；?=可空类型；SteamInput 手柄控制器
    private var mapper: KeyboardMouseMapper? = null  // var=可变变量；?=可空类型；键盘鼠标映射器
    private var bridgeServer: InputBridgeServer? = null  // var=可变变量；?=可空类型；TCP 桥接服务器
    private var configManager: ConfigManager? = null  // var=可变变量；?=可空类型；配置管理器
    /** TCP监听地址，由Intent extra EXTRA_HOST设置，null表示监听所有接口 */
    private var serverHost: String? = null  // var=可变变量；?=可空类型；TCP 监听地址，null 表示监听所有接口
    /** TCP监听端口，由Intent extra EXTRA_PORT设置 */
    private var serverPort: Int = InputBridgeServer.DEFAULT_PORT  // var=可变变量；TCP 监听端口，默认端口

    // ====================================================================
    // 智能暂停（Smart Pause）
    // ====================================================================
    // 可焦点悬浮窗在 Android 13+ 会吃掉系统预测式返回手势（右滑返回失效），
    // 这是系统级行为，应用内无法让返回穿透焦点窗口。
    // 解决方案：监听前台应用（UsageStats），仅当"捕获白名单"内的应用
    // （如 Winlator）或本应用在前台时保持焦点窗口，其余时间自动移除，
    // 让下层应用恢复右滑返回。未授权"使用情况访问"时退化为手动模式。
    // ====================================================================

    /** 智能暂停开关（默认开启，由 MainActivity 设置） */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var smartPauseEnabled: Boolean = true  // var=可变变量；智能暂停开关，默认开启

    /**
     * 捕获总开关（用户手动暂停/恢复，与 MainActivity 开关双向同步）
     *
     * true=允许捕获（智能暂停模式下由前台应用决定窗口是否显示）
     * false=手动暂停（移除焦点窗口，智能监控不再自动恢复）
     */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var captureEnabled: Boolean = true  // var=可变变量；捕获总开关，默认开启

    /**
     * 用户手动暂停捕获标志（通过 ToggleCapture 动作或悬浮窗按钮触发）
     *
     * true 时智能监控不会自动恢复捕获（避免覆盖用户意图）。
     * 用户手动恢复或 `setCaptureEnabled(true)` 调用时重置。
     */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var manualPaused: Boolean = false  // var=可变变量；用户手动暂停捕获标志

    /** 捕获白名单包名集合（前台应用在此集合内时保持捕获） */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var captureWhitelist: Set<String> = AppConfig.DEFAULT_WHITELIST.toSet()  // var=可变变量；捕获白名单包名集合

    /** 悬浮窗"游戏"按钮拉起的目标应用包名（来自 AppConfig） */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var launcherPackage: String = AppConfig.DEFAULT_LAUNCHER  // var=可变变量；拉起应用包名

    /** 智能监控线程运行标志 */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var smartMonitorRunning = false  // var=可变变量；智能监控线程运行标志

    private var smartMonitorThread: Thread? = null  // var=可变变量；?=可空类型；智能监控线程

    /**
     * 悬浮窗是否被暂停（在 LayerEditActivity 等设置界面打开时移除窗口）
     *
     * ## 作用
     * 全屏透明焦点窗口 (GamepadInputView) 虽然设置 FLAG_NOT_TOUCHABLE，
     * 仍可能拦截系统手势（如边缘滑动返回）。当用户进入设置 Activity 时，
     * 临时移除所有 WindowManager 添加的 View，避免干扰系统手势。
     *
     * ## 调用流程
     * ```
     * LayerEditActivity.onCreate → ACTION_PAUSE_OVERLAY → pauseOverlay()
     *   ↓ 移除 gamepadInputView + overlayView
     * LayerEditActivity.onDestroy → ACTION_RESUME_OVERLAY → resumeOverlay()
     *   ↓ 重新创建 gamepadInputView + overlayView
     * ```
     */
    private var isOverlayPaused = false  // var=可变变量；悬浮窗是否被暂停（设置界面打开时移除窗口）

    /**
     * 系统键盘是否正在显示
     *
     * 键盘显示时**保持捕获**（不暂停、不移除 GamepadInputView）：软键盘绑定到
     * 1x1 焦点窗口，键入内容经 IME 转发到 Windows 注入 WoW，同时手柄事件仍可
     * 继续到达本应用。键盘隐藏时若捕获已暂停则恢复捕获。
     */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var isKeyboardShowing = false  // var=可变变量；系统键盘是否正在显示

    override fun onBind(intent: Intent?): IBinder? = null  // override=覆写父类方法；fun=函数声明；绑定服务时返回 null（本服务不支持绑定），?=可空参数

    override fun onCreate() {  // override=覆写父类方法；fun=函数声明；服务创建时回调
        super.onCreate()  // 调用父类实现
        // Android 14+ 需要指定前台服务类型
        ServiceCompat.startForeground(  // 以前台服务方式启动（Android 14+ 需指定服务类型）
            this,  // 服务上下文
            NOTIFICATION_ID,  // 前台通知 ID
            createNotification("启动中..."),  // 创建"启动中"提示通知
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE  // 前台服务类型：特殊用途
        )  // 结束 startForeground 调用
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager  // 获取窗口管理器并强制类型转换
        // 从配置文件加载全部运行时配置（服务器/智能暂停/白名单/捕获开关/拉起应用）
        reloadAppConfig()  // 从配置文件重新加载运行时配置
        createOverlay()  // 创建悬浮窗
    }  // 结束 onCreate 函数

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {  // override=覆写父类方法；fun=函数声明；服务启动命令回调，返回 Int 决定重启策略，?=可空参数
        // 读取TCP监听地址和端口（每次startService都可更新，但仅在首次startMapper时生效）
        intent?.getStringExtra(EXTRA_HOST)?.let { serverHost = it }  // ?.=安全调用；?.let{}=非空时执行 lambda；读取 TCP 监听地址 extra
        intent?.getIntExtra(EXTRA_PORT, serverPort)?.let { serverPort = it }  // ?.=安全调用；?.let{}=非空时执行 lambda；读取 TCP 监听端口 extra
        // 读取智能暂停配置（每次startService可更新）
        // ?.=安全调用；?.let{}=非空时执行；读取智能暂停开关 extra
        intent?.getBooleanExtra(EXTRA_SMART_PAUSE, smartPauseEnabled)?.let { smartPauseEnabled = it }
        // ?.=安全调用；?.let{}=非空时执行；解析白名单并转为集合
        intent?.getStringExtra(EXTRA_WHITELIST)?.let { captureWhitelist = AppConfig.parseWhitelist(it).toSet() }
        intent?.getStringExtra(EXTRA_LAUNCHER_PACKAGE)?.let { launcherPackage = it }  // ?.=安全调用；?.let{}=非空时执行；读取拉起应用包名 extra
        when (intent?.action) {  // when=分支判断；根据 Intent action 分发处理
            ACTION_STOP -> {  // 分支：停止服务
                stopSmartMonitor()  // 停止智能暂停监控
                stopSelf()  // 停止服务自身
                return START_NOT_STICKY  // 返回不粘性：进程被杀后不自动重启
            }  // 结束 ACTION_STOP 分支
            ACTION_EXPORT_CONFIG -> {  // 分支：导出配置
                @Suppress("DEPRECATION")  // 注解：抑制弃用警告（旧版 API）
                val uri = intent.getParcelableExtra<Uri>(EXTRA_CONFIG_URI)  // val=只读变量；泛型<Uri>；取出配置文件 URI
                handleExportConfig(uri)  // 处理导出配置
            }  // 结束 ACTION_EXPORT_CONFIG 分支
            ACTION_IMPORT_CONFIG -> {  // 分支：导入配置
                @Suppress("DEPRECATION")  // 注解：抑制弃用警告（旧版 API）
                val uri = intent.getParcelableExtra<Uri>(EXTRA_CONFIG_URI)  // val=只读变量；泛型<Uri>；取出配置文件 URI
                handleImportConfig(uri)  // 处理导入配置
            }  // 结束 ACTION_IMPORT_CONFIG 分支
            ACTION_RESET_CONFIG -> {  // 分支：重置配置
                handleResetConfig()  // 处理重置配置
            }  // 结束 ACTION_RESET_CONFIG 分支
            ACTION_PAUSE_OVERLAY -> {  // 分支：暂停悬浮窗
                pauseOverlay()  // 暂停悬浮窗（移除窗口）
            }  // 结束 ACTION_PAUSE_OVERLAY 分支
            ACTION_RESUME_OVERLAY -> {  // 分支：恢复悬浮窗
                resumeOverlay()  // 恢复悬浮窗（重建窗口）
            }  // 结束 ACTION_RESUME_OVERLAY 分支
            ACTION_UPDATE_SETTINGS -> {  // 分支：更新右摇杆设置
                handleUpdateSettings(intent)  // 处理设置更新
            }  // 结束 ACTION_UPDATE_SETTINGS 分支
            ACTION_SET_CAPTURE -> {  // 分支：设置捕获开关
                // MainActivity 捕获开关变化 → 应用到服务并广播同步
                intent.getBooleanExtra(EXTRA_CAPTURE_ENABLED, captureEnabled)?.let {  // ?.=安全调用；?.let{}=非空时执行 lambda；读取捕获开关 extra
                    setCaptureEnabled(it)  // 应用捕获开关到服务
                }  // 结束 let lambda
            }  // 结束 ACTION_SET_CAPTURE 分支
            ACTION_REFRESH_LAYERS -> {  // 分支：刷新悬浮窗层名
                refreshLayerNames()  // 刷新层按钮名称
            }  // 结束 ACTION_REFRESH_LAYERS 分支
            ACTION_TOGGLE_OVERLAY -> {  // 分支：切换悬浮窗视图
                toggleOverlayView()  // 切换悬浮窗视图
            }  // 结束 ACTION_TOGGLE_OVERLAY 分支
        }  // 结束 when 分支
        // 首次启动时初始化映射器
        if (steamInput == null) {  // if 判断：映射器未初始化
            startMapper()  // 启动映射器
        } else {  // else 分支：映射器已就绪
            // 映射器已就绪：智能暂停配置可能已更新，重启监控以生效
            restartSmartMonitor()  // 重启智能暂停监控
        }  // 结束 if-else 块
        return START_STICKY  // 返回粘性：进程被杀后系统自动重启服务
    }  // 结束 onStartCommand 函数

    private fun startMapper() {  // private=私有方法；fun=函数声明；启动手柄映射器
        // DisplayMetrics 必须在主线程获取
        val metrics = DisplayMetrics()  // val=只读变量；屏幕显示指标对象
        @Suppress("DEPRECATION")  // 注解：抑制弃用警告
        windowManager?.defaultDisplay?.getMetrics(metrics)  // ?.=安全调用；获取默认显示尺寸填充到 metrics

        // 在后台线程执行，避免 ServerSocket.bind() 触发 NetworkOnMainThreadException
        Thread {  // Thread=线程；lambda 语法 { }；在新线程中执行映射器初始化
            try {  // try=异常捕获；包裹可能抛异常的初始化逻辑
                // 启动TCP桥接服务器（网络操作，必须在子线程）
                bridgeServer = InputBridgeServer(serverHost, serverPort)  // 创建 TCP 桥接服务器
                bridgeServer?.onClientConnected = { addr ->  // ?.=安全调用；lambda {addr->}；设置客户端连接回调
                    Log.i(TAG, "Client connected: $addr")  // 字符串模板 "$var"=插值变量；打印客户端连接日志
                    updateStatus("✅ 客户端已连接: $addr")  // 字符串模板 "$var"=插值变量；更新状态文本
                    broadcastClientStatus("客户端: $addr", true)  // 字符串模板 "$var"=插值变量；广播客户端连接状态
                }  // 结束 onClientConnected lambda
                bridgeServer?.onClientDisconnected = { addr ->  // ?.=安全调用；lambda {addr->}；设置客户端断开回调
                    Log.i(TAG, "Client disconnected: $addr")  // 字符串模板 "$var"=插值变量；打印客户端断开日志
                    val msg = waitMessage()  // val=只读变量；生成等待连接状态文本
                    updateStatus(msg)  // 更新状态文本
                    broadcastClientStatus(msg, false)  // 广播断开状态
                }  // 结束 onClientDisconnected lambda
                bridgeServer?.onServerError = { msg ->  // ?.=安全调用；lambda {msg->}；设置服务器错误回调
                    Log.e(TAG, "Server error: $msg")  // 字符串模板 "$var"=插值变量；打印服务器错误日志
                    updateStatus("❌ 服务器错误: $msg")  // 字符串模板 "$var"=插值变量；更新错误状态
                    broadcastClientStatus("服务器错误: $msg", false)  // 字符串模板 "$var"=插值变量；广播错误状态
                }  // 结束 onServerError lambda

                if (bridgeServer?.start() != true) {  // ?.=安全调用；if 判断：服务器启动失败
                    Log.e(TAG, "TCP server start failed")  // 打印服务器启动失败日志
                    updateStatus("❌ TCP 服务器启动失败")  // 更新启动失败状态
                    broadcastClientStatus("TCP 服务器启动失败", false)  // 广播启动失败状态
                    return@Thread  // 带标签返回：从 lambda 中提前结束线程体
                }  // 结束 if 块
                Log.i(TAG, "TCP server started on ${serverHost ?: "0.0.0.0"}:${serverPort}")  // 字符串模板 "$var"=插值；?:=空值合并；打印监听地址与端口

                // 使用桥接注入器（通过TCP发送事件到Windows客户端）
                val injector = BridgeInputInjector(bridgeServer!!)  // val=只读变量；!!=非空断言；创建桥接事件注入器

                steamInput = SteamInput(this)  // 创建 SteamInput 手柄控制器
                // 将 SteamInput 实例暴露给 LayerEditActivity，供操作层设置界面读写配置
                LayerEditActivity.steamInputRef = steamInput  // 把 SteamInput 实例赋值给 LayerEditActivity 的静态引用
                // 手柄按钮按下/释放 → 悬浮窗按键映射页按钮高亮反馈
                steamInput?.onButtonStateChanged = { button, pressed ->  // ?.=安全调用；lambda {button, pressed->}；设置手柄按键状态变化回调
                    mainHandler.post { updateMappingViewHighlight(button, pressed) }  // 回主线程更新映射页按钮高亮
                }  // 结束 onButtonStateChanged lambda
                // 手柄连接/断开 → 更新收起悬浮窗的图标状态（🎮未连接 / ▶映射中 / ⏸暂停）
                steamInput?.onControllerConnected = { controller ->  // ?.=安全调用；lambda {controller->}；设置手柄连接回调
                    controllerConnected = true  // 标记手柄已连接
                    mainHandler.post {  // 回主线程更新收起悬浮窗文本
                        updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新收起胶囊文本
                    }  // 结束 post lambda
                }  // 结束 onControllerConnected lambda
                steamInput?.onControllerDisconnected = { controller ->  // ?.=安全调用；lambda {controller->}；设置手柄断开回调
                    controllerConnected = steamInput?.controllers?.isNotEmpty() ?: false  // ?.=安全调用；?:=空值合并；按剩余控制器判断连接状态
                    mainHandler.post {  // 回主线程更新收起悬浮窗文本
                        updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新收起胶囊文本
                    }  // 结束 post lambda
                }  // 结束 onControllerDisconnected lambda
                // 服务启动时手柄可能已经连接：onInputDeviceAdded 只在新设备插入时触发，
                // 已连接手柄不会触发回调，这里显式初始化连接状态，避免误显示"🎮 未连接"
                controllerConnected = steamInput?.controllers?.isNotEmpty() ?: false  // ?.=安全调用；?:=空值合并；显式初始化手柄连接状态
                // 刷新收起文本（若面板尚未创建则为空操作，面板创建时会读取最新状态）
                mainHandler.post {  // 回主线程执行
                    updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新收起胶囊文本
                }  // 结束 post lambda
                // 注册蓝牙 HID Host 代理，查询手柄真实连接状态（不依赖系统广播）
                registerHidProxy()  // 注册蓝牙 HID Host 代理
                // 启动手柄连接状态轮询（结合 HID Host 在线列表，兜底 InputManager 回调不可靠）
                startControllerMonitor()  // 启动手柄连接状态轮询
                configManager = ConfigManager(this, steamInput!!)  // !! = 非空断言；创建配置管理器

                mapper = KeyboardMouseMapper(  // 创建键盘鼠标映射器
                    steamInput = steamInput!!,  // 命名参数；!!=非空断言；传入 SteamInput
                    injector = injector,  // 命名参数；传入事件注入器
                    screenWidth = metrics.widthPixels,  // 命名参数；屏幕宽度
                    screenHeight = metrics.heightPixels  // 命名参数；屏幕高度
                )  // 结束 KeyboardMouseMapper 构造

                mapper?.onLayerChanged = { layers ->  // ?.=安全调用；lambda {layers->}；设置层切换回调
                    updateLayerText(layers)  // 更新操作层堆栈文本
                    updateLayerButtonColors(layers)  // 更新层按钮颜色
                    updateCollapsedViewText(layers)  // 更新收起胶囊文本
                    updateMappingView()  // 更新按键映射列表视图
                }  // 结束 onLayerChanged lambda

                // 操作集切换 → 悬浮窗整体刷新（操作集信息 + 层按钮 + 映射页）
                mapper?.onActionSetChanged = { actionSetName ->  // ?.=安全调用；lambda {actionSetName->}；设置操作集切换回调
                    onActionSetSwitched(actionSetName)  // 操作集切换后刷新悬浮窗
                }  // 结束 onActionSetChanged lambda

                // 按键映射动作回调（ToggleOverlay / ToggleKeyboard / ToggleCapture）
                mapper?.onToggleOverlay = { mainHandler.post { toggleOverlayView() } }  // ?.=安全调用；lambda；切换悬浮窗视图（回主线程）
                mapper?.onToggleKeyboard = { mainHandler.post { toggleSystemKeyboard() } }  // ?.=安全调用；lambda；切换系统键盘（回主线程）
                mapper?.onToggleCapture = { mainHandler.post { toggleCaptureState() } }  // ?.=安全调用；lambda；切换捕获状态（回主线程）

                // 鼠标长按切换（MouseToggle）：右键锁存时悬浮窗边框红色高亮，释放恢复
                mapper?.onMouseToggleChanged = { mouseButton, active ->  // ?.=安全调用；lambda {mouseButton, active->}；设置鼠标长按切换回调
                    if (mouseButton == MouseButton.RIGHT) {  // if 判断：仅右键锁存时处理
                        setRightToggleBorder(active)  // 切换悬浮窗边框颜色
                    }  // 结束 if 块
                }  // 结束 onMouseToggleChanged lambda

                if (mapper?.start() == true) {  // ?.=安全调用；if 判断：映射器启动成功
                    Log.i(TAG, "Mapper started successfully")  // 打印映射器启动成功日志
                    // 加载用户配置（覆盖默认WoW预设的绑定和属性）
                    // 动作定义和回调保持不变，仅修改绑定关系
                    loadUserConfig()  // 加载用户保存的配置

                    // 创建焦点输入窗口（addView 必须在主线程执行）
                    mainHandler.post { createGamepadInputWindow() }  // 回主线程创建焦点输入窗口

                    // 启动智能暂停监控（检测前台应用自动移除/恢复焦点窗口）
                    startSmartMonitor()  // 启动智能暂停监控

                    val waitMsg = waitMessage()  // val=只读变量；生成等待连接提示文本
                    updateStatus(waitMsg)  // 更新状态文本
                    broadcastClientStatus(waitMsg, false)  // 广播等待连接状态
                    updateLayerText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新操作层文本
                    updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新层按钮颜色
                } else {  // else 分支：映射器启动失败
                    Log.e(TAG, "Mapper start failed - check overlay permission")  // 打印启动失败日志
                    updateStatus("❌ 启动失败 - 请检查悬浮窗权限")  // 更新启动失败状态
                    broadcastClientStatus("启动失败 - 请检查悬浮窗权限", false)  // 广播启动失败状态
                }  // 结束 if-else 块
            } catch (e: Exception) {  // catch=捕获异常分支
                Log.e(TAG, "startMapper error", e)  // 打印映射器启动异常日志
                updateStatus("❌ 错误: ${e.message}")  // 字符串模板 "$var"=插值变量；更新错误状态
                broadcastClientStatus("错误: ${e.message}", false)  // 字符串模板 "$var"=插值变量；广播错误状态
            }  // 结束 try-catch 块
        }.start()  // 结束 Thread lambda 并启动线程
    }  // 结束 startMapper 函数

    /**
     * 等待 Windows 客户端连接的状态文本（悬浮窗/通知/主界面共用）
     */
    private fun waitMessage(): String =  // private=私有；fun=函数声明；返回等待客户端连接的状态文本，单表达式函数体
        "⏳ 等待 Windows 客户端连接（${serverHost ?: "0.0.0.0"}:${serverPort}）"  // 字符串模板 "$var"=插值；?:=空值合并；拼接监听地址与端口

    // ====================================================================
    // 配置管理
    // ====================================================================
    // 服务通过 Intent action 接收来自 MainActivity 的配置操作请求:
    // - ACTION_EXPORT_CONFIG: 导出当前配置到用户选择的 URI
    // - ACTION_IMPORT_CONFIG: 从用户选择的 URI 导入配置
    // - ACTION_RESET_CONFIG:  重置为默认配置（删除内部配置文件并重新初始化）
    //
    // 配置文件存储在 {filesDir}/steamlike_config.json，
    // 服务启动时自动加载（覆盖默认 WoW 预设的绑定和属性）。
    // ====================================================================

    /**
     * 加载内部配置文件并应用
     *
     * 在 [KeyboardMouseMapper.start] 之后调用，用用户保存的配置覆盖默认 WoW 预设。
     *
     * ## 调用时机
     * ```
     * startMapper():
     *   ① mapper.start() → WoWActionSets.setup() 加载代码中的默认配置
     *   ② loadUserConfig() → 读取内部配置文件，覆盖默认配置  ← 此方法
     *   ③ createGamepadInputWindow() → 创建焦点窗口
     * ```
     *
     * ## 行为说明
     * - 如果内部配置文件不存在，使用默认 WoW 预设（步骤①的配置）
     * - 如果配置文件存在，[ConfigManager.applyConfig] 会:
     *   - 清除默认绑定 → 应用配置文件中的绑定
     *   - 动作定义和回调不受影响（它们在代码中定义）
     * - 加载过程中的警告会输出到 Logcat（标签: ConfigManager）
     */
    private fun loadUserConfig() {  // private=私有；fun=函数声明；加载内部配置文件并应用
        val si = steamInput ?: return  // val=只读变量；?:=空值合并；SteamInput 为空则直接返回
        val cm = configManager ?: return  // val=只读变量；?:=空值合并；配置管理器为空则直接返回
        // 从内部存储加载配置（不存在则使用默认配置，自动应用到 SteamInput）
        cm.loadFromInternal()  // 从内部存储加载配置
    }  // 结束 loadUserConfig 函数

    /**
     * 处理导出配置请求
     *
     * 将当前 SteamInput 的运行时状态导出为 JSON，写入用户通过 SAF 选择的 URI。
     * 同时保存到内部存储（自动持久化）。
     *
     * ## 流程
     * ```
     * MainActivity 点击"导出配置"
     *      ↓ SAF CreateDocument 选择保存位置
     *      ↓ 发送 ACTION_EXPORT_CONFIG intent + URI
     * ControllerOverlayService.handleExportConfig(uri)
     *      ↓ configManager.exportConfig(steamInput) 提取当前配置
     *      ↓ configManager.saveToUri(config, uri) 写入用户选择的位置
     *      ↓ configManager.saveToFile(config) 同步到内部存储
     *      ↓ Toast 提示导出结果
     * ```
     *
     * @param uri 用户通过 SAF 选择的输出文件 URI（可能为 null）
     */
    private fun handleExportConfig(uri: Uri?) {  // private=私有；fun=函数声明；处理导出配置请求，?=可空参数
        val si = steamInput ?: run {  // val=只读变量；?:=空值合并；run{}=立即执行 lambda 块；SteamInput 为空时执行块
            toast("映射器未启动，请先启动手柄映射")  // 提示映射器未启动
            return  // 提前返回
        }  // 结束 run 块
        val cm = configManager ?: ConfigManager(this, si)  // val=只读变量；?:=空值合并；配置管理器为空则新建
        if (uri == null) {  // if 判断：URI 无效
            toast("导出失败: 无效的文件路径")  // 提示导出失败
            return  // 提前返回
        }  // 结束 if 块
        try {  // try=异常捕获
            val profile = si.profile  // val=只读变量；获取当前手柄配置档案
            // 导出到用户选择的位置（saveToUri 使用当前 steamInput.profile）
            cm.saveToUri(uri)  // 导出配置到用户选择的 URI
            // 同时保存到内部存储（确保自动持久化）
            cm.saveToInternal(profile)  // 同步保存到内部存储
            toast("配置已导出 (${profile.commonLayer.buttonMappings.size}个绑定, ${profile.layers.size}个层)")  // 字符串模板 "$var"=插值变量；提示导出统计
        } catch (e: Exception) {  // catch=捕获异常分支
            toast("导出失败: ${e.message}")  // 字符串模板 "$var"=插值变量；提示导出失败原因
        }  // 结束 try-catch 块
    }  // 结束 handleExportConfig 函数

    /**
     * 处理导入配置请求
     *
     * 从用户通过 SAF 选择的 URI 读取 JSON 配置，应用到 SteamInput，
     * 并保存到内部存储（下次启动自动加载）。
     *
     * ## 流程
     * ```
     * MainActivity 点击"导入配置"
     *      ↓ SAF OpenDocument 选择配置文件
     *      ↓ 发送 ACTION_IMPORT_CONFIG intent + URI
     * ControllerOverlayService.handleImportConfig(uri)
     *      ↓ configManager.loadFromUri(uri) 读取并解析 JSON
     *      ↓ configManager.applyConfig(steamInput, config) 验证并应用
     *      ↓ configManager.saveToFile(config) 保存到内部存储
     *      ↓ 更新悬浮窗 UI 显示
     *      ↓ Toast 提示导入结果（成功数/跳过数/警告）
     * ```
     *
     * @param uri 用户通过 SAF 选择的输入文件 URI（可能为 null）
     */
    private fun handleImportConfig(uri: Uri?) {  // private=私有；fun=函数声明；处理导入配置请求，?=可空参数
        val si = steamInput ?: run {  // val=只读变量；?:=空值合并；run{}=立即执行 lambda；SteamInput 为空时执行块
            toast("映射器未启动，请先启动手柄映射")  // 提示映射器未启动
            return  // 提前返回
        }  // 结束 run 块
        val cm = configManager ?: ConfigManager(this, si)  // val=只读变量；?:=空值合并；配置管理器为空则新建
        if (uri == null) {  // if 判断：URI 无效
            toast("导入失败: 无效的文件路径")  // 提示导入失败
            return  // 提前返回
        }  // 结束 if 块
        try {  // try=异常捕获
            // 从 URI 读取、解析、应用到 SteamInput、保存到内部存储
            val json = contentResolver.openInputStream(uri)?.use { stream ->  // val=只读变量；?.=安全调用；use{}=自动关闭资源 lambda；打开输入流读取
                stream.bufferedReader().readText()  // 读取全部文本内容
            } ?: run {  // ?:=空值合并；读流失败（null）时执行 run 块
                toast("导入失败: 无法读取配置文件")  // 提示读取失败
                return  // 提前返回
            }  // 结束 run 块
            // 应用按键映射
            val profile = ControllerConfig.fromJson(json)  // val=只读变量；从 JSON 解析出配置档案
            si.loadProfile(profile)  // 应用配置档案到 SteamInput
            // 应用运行时配置（settings: 白名单/智能暂停/捕获开关/拉起应用等）
            val importedCfg = ControllerConfig.appConfigFromJsonString(json)  // val=只读变量；解析 JSON 中的运行时配置
            AppConfigStore.save(this, importedCfg)  // 保存运行时配置
            reloadAppConfig()  // 重新加载运行时配置
            restartSmartMonitor()  // 重启智能暂停监控
            updateCaptureButtonState()  // 更新捕获按钮状态
            // 持久化（含导入的 settings）
            cm.saveToInternal(profile)  // 保存配置档案到内部存储
            toast("配置已导入")  // 提示导入成功

            // 更新悬浮窗的层显示（导入可能修改了层定义）
            updateLayerText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新操作层文本
            updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新层按钮颜色
            refreshLayerNames()  // 刷新层按钮名称
        } catch (e: Exception) {  // catch=捕获异常分支
            toast("导入失败: ${e.message}")  // 字符串模板 "$var"=插值变量；提示导入失败原因
        }  // 结束 try-catch 块
    }  // 结束 handleImportConfig 函数

    /**
     * 处理重置配置请求
     *
     * 删除内部配置文件，并重新初始化映射器以恢复代码中定义的默认 WoW 预设。
     *
     * ## 流程
     * ```
     * MainActivity 点击"重置为默认配置"
     *      ↓ 发送 ACTION_RESET_CONFIG intent
     * ControllerOverlayService.handleResetConfig()
     *      ↓ configManager.deleteConfigFile() 删除内部配置文件
     *      ↓ Toast 提示正在重置
     *      ↓ mapper.stop() 停止当前映射器
     *      ↓ startMapper() 重新初始化（加载默认配置，无配置文件则不覆盖）
     * ```
     */
    private fun handleResetConfig() {  // private=私有；fun=函数声明；处理重置配置请求
        val si = steamInput ?: run {  // val=只读变量；?:=空值合并；run{}=立即执行 lambda；SteamInput 为空时执行块
            toast("映射器未启动，请先启动手柄映射")  // 提示映射器未启动
            return  // 提前返回
        }  // 结束 run 块
        val cm = configManager ?: ConfigManager(this, si)  // val=只读变量；?:=空值合并；配置管理器为空则新建
        // 重置为默认配置（应用到 SteamInput 并保存到内部存储）
        cm.resetToDefault()  // 重置为默认配置
        toast("已重置为默认配置，正在重新初始化...")  // 提示正在重置

        // 停止并重新启动映射器
        mapper?.stop()  // ?.=安全调用；停止当前映射器
        mapper = null  // 清空映射器引用
        steamInput = null  // 清空 SteamInput 引用
        // 清除 LayerEditActivity 的 SteamInput 引用（startMapper 会重新设置）
        LayerEditActivity.steamInputRef = null  // 清除编辑页的静态 SteamInput 引用
        startMapper()  // 重新启动映射器
        // 刷新悬浮窗层名（重置后恢复默认名）
        refreshLayerNames()  // 刷新层按钮名称
    }  // 结束 handleResetConfig 函数

    /**
     * 处理右摇杆优化设置更新
     *
     * 从 Intent 读取 5 个 Float 参数，更新 SteamInput.profile.globalSettings，
     * 并保存到内部配置文件（持久化），下次启动自动加载。
     *
     * 参数缺省时使用当前 profile 中已有的值（保持不变）。
     */
    private fun handleUpdateSettings(intent: Intent) {  // private=私有；fun=函数声明；处理右摇杆设置更新
        val si = steamInput ?: run {  // val=只读变量；?:=空值合并；run{}=立即执行 lambda；SteamInput 为空时执行块
            toast("映射器未启动，请先启动手柄映射")  // 提示映射器未启动
            return  // 提前返回
        }  // 结束 run 块
        val old = si.profile.globalSettings  // val=只读变量；获取旧的全局设置
        val newSettings = com.steamlike.controller.core.GlobalSettings(  // val=只读变量；构造新的全局设置对象
            deadzone = intent.getFloatExtra(EXTRA_DEADZONE, old.deadzone),  // 命名参数；读取摇杆死区
            lookSensitivity = intent.getFloatExtra(EXTRA_LOOK_SENSITIVITY, old.lookSensitivity),  // 命名参数；读取视角灵敏度
            cursorSpeed = intent.getFloatExtra(EXTRA_CURSOR_SPEED, old.cursorSpeed),  // 命名参数；读取光标速度
            lookSmoothing = intent.getFloatExtra(EXTRA_LOOK_SMOOTHING, old.lookSmoothing),  // 命名参数；读取平滑系数
            lookAcceleration = intent.getFloatExtra(EXTRA_LOOK_ACCELERATION, old.lookAcceleration)  // 命名参数；读取加速指数
        )  // 结束 GlobalSettings 构造
        // 更新运行时 profile（loadProfile 会重置操作层状态，设置更新时合理）
        si.loadProfile(si.profile.copy(globalSettings = newSettings))  // copy()=复制对象并修改指定属性；应用新设置
        // 持久化到内部配置文件
        val cm = configManager ?: ConfigManager(this, si).also { configManager = it }  // val=只读变量；?:=空值合并；also{}=副作用 lambda；新建时同时缓存到字段
        cm.saveToInternal(si.profile)  // 保存配置档案到内部存储
        Log.i(TAG, "GlobalSettings updated: deadzone=${newSettings.deadzone}, " +  // 字符串模板 "$var"=插值变量；打印更新日志（多行拼接）
                "sensitivity=${newSettings.lookSensitivity}, " +  // 字符串模板 "$var"=插值变量；续行：灵敏度
                "smoothing=${newSettings.lookSmoothing}, " +  // 字符串模板 "$var"=插值变量；续行：平滑
                "acceleration=${newSettings.lookAcceleration}")  // 字符串模板 "$var"=插值变量；续行：加速
        toast("右摇杆设置已保存（灵敏度=${newSettings.lookSensitivity}, 平滑=${newSettings.lookSmoothing}）")  // 字符串模板 "$var"=插值变量；提示保存结果
    }  // 结束 handleUpdateSettings 函数

    /**
     * 在主线程显示 Toast 提示，同时输出到 Logcat
     *
     * 配置操作可能在非主线程执行，需要 post 到主线程才能更新 UI。
     * 所有提示消息同时输出到 Logcat（标签: SteamLikeService），方便调试。
     *
     * @param msg 提示消息
     */
    private fun toast(msg: String) {  // private=私有；fun=函数声明；在主线程显示 Toast 提示
        Log.i(TAG, "Toast: $msg")  // 字符串模板 "$var"=插值变量；同步打印日志
        mainHandler.post {  // 回主线程执行
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()  // 显示长时长 Toast
        }  // 结束 post lambda
    }  // 结束 toast 函数

    /**
     * 广播客户端连接状态给 MainActivity
     *
     * 当 Windows 客户端连接/断开时，发送广播让 Activity 更新 UI 状态显示。
     * Activity 通过注册 [ACTION_CLIENT_STATUS] 广播接收器来监听状态变化。
     *
     * @param statusText 状态描述文本
     * @param connected 是否已连接
     */
    private fun broadcastClientStatus(statusText: String, connected: Boolean) {  // private=私有；fun=函数声明；广播客户端连接状态
        val intent = Intent(ACTION_CLIENT_STATUS).apply {  // val=只读变量；apply{}=返回对象本身并执行块；创建状态广播 Intent
            setPackage(packageName)  // 限定接收者为本应用
            putExtra(EXTRA_STATUS_TEXT, statusText)  // 携带状态文本
            putExtra(EXTRA_CONNECTED, connected)  // 携带连接标志
        }  // 结束 apply 块
        sendBroadcast(intent)  // 发送广播
    }  // 结束 broadcastClientStatus 函数

    // ===== 悬浮窗暂停/恢复 =====

    /**
     * 暂停悬浮窗（移除焦点窗口和悬浮窗UI）
     *
     * 由 LayerEditActivity onCreate 调用，移除所有 WindowManager 添加的 View：
     * - GamepadInputView（全屏透明焦点窗口，会拦截边缘返回手势）
     * - overlayView（悬浮窗 UI）
     *
     * 保留 TCP 服务器和映射器运行，仅移除窗口视图。
     * 用户退出 LayerEditActivity 时调用 [resumeOverlay] 重建窗口。
     */
    private fun pauseOverlay() {  // private=私有；fun=函数声明；暂停悬浮窗（移除窗口）
        if (isOverlayPaused) return  // if 判断：已暂停则直接返回
        isOverlayPaused = true  // 标记悬浮窗已暂停
        mainHandler.post {  // 回主线程移除窗口
            gamepadInputView?.let { windowManager?.removeView(it) }  // ?.=安全调用；?.let{}=非空时执行；移除焦点窗口
            gamepadInputView = null  // 清空焦点窗口引用
            overlayView?.let { windowManager?.removeView(it) }  // ?.=安全调用；?.let{}=非空时执行；移除悬浮窗
            overlayView = null  // 清空悬浮窗引用
            Log.i(TAG, "Overlay paused (windows removed)")  // 打印暂停日志
        }  // 结束 post lambda
    }  // 结束 pauseOverlay 函数

    /**
     * 恢复悬浮窗（重新创建焦点窗口和悬浮窗UI）
     *
     * 由 LayerEditActivity onDestroy 调用，重新创建被 [pauseOverlay] 移除的窗口。
     * - 重建 GamepadInputView 捕获手柄事件
     * - 重建悬浮窗 UI 显示状态
     */
    private fun resumeOverlay() {  // private=私有；fun=函数声明；恢复悬浮窗（重建窗口）
        if (!isOverlayPaused) return  // if 判断：未暂停则直接返回
        isOverlayPaused = false  // 取消暂停标记
        mainHandler.post {  // 回主线程重建窗口
            // 重新创建焦点输入窗口（pauseOverlay 已将 gamepadInputView 置 null）
            // 仅在捕获中时重建；若捕获已暂停（isCapturing=false），保持暂停状态
            if (gamepadInputView == null && mapper != null && isCapturing) {  // if 判断：焦点窗口为空且映射器就绪且捕获中
                createGamepadInputWindow()  // 创建焦点输入窗口
            }  // 结束 if 块
            // 重新创建悬浮窗窗口（pauseOverlay 已将 overlayView 置 null）
            // createOverlay 内部会创建常驻容器并显示收起胶囊
            if (overlayView == null) {  // if 判断：悬浮窗不存在时重建
                createOverlay()  // 创建悬浮窗
            }  // 结束 if 块
            Log.i(TAG, "Overlay resumed (windows recreated)")  // 打印恢复日志
        }  // 结束 post lambda
    }  // 结束 resumeOverlay 函数

    // ===== 焦点输入窗口 =====

    /**
     * 当前是否正在捕获手柄事件（GamepadInputView 存在并持有焦点）
     *
     * true: GamepadInputView 存在，可接收手柄事件，但会阻止系统返回手势
     * false: GamepadInputView 已移除，系统返回手势恢复工作，但无法接收手柄事件
     *
     * 用户通过悬浮窗的"暂停捕获/恢复捕获"按钮切换。
     */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var isCapturing: Boolean = false  // var=可变变量；当前是否正在捕获手柄事件

    /**
     * 手柄连接状态
     *
     * - true: 至少有一个手柄已连接，正在(或可)映射
     * - false: 无手柄连接，收起悬浮窗显示"🎮 未连接"提示
     *
     * 由 [SteamInput.onControllerConnected] / [SteamInput.onControllerDisconnected]
     * 回调驱动，用于更新收起悬浮窗的图标状态。
     */
    @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
    private var controllerConnected: Boolean = false  // var=可变变量；手柄连接状态

    /** 手柄连接状态轮询周期（毫秒） */
    private val controllerMonitorIntervalMs = 2000L  // val=只读变量；轮询周期 2 秒
    /**
     * 手柄连接状态轮询任务
     *
     * InputManager 的 onInputDeviceAdded/Removed 回调在部分设备（如 MIUI）上不可靠，
     * 这里周期查询系统真实输入设备作为兜底，确保悬浮窗图标状态始终准确。
     */
    private var controllerMonitor: Runnable? = null  // var=可变变量；?=可空类型；手柄连接状态轮询任务
    /** 上一次打印的连接检测摘要（用于诊断日志去重，仅在变化时打印） */
    private var lastDetectSummary: String? = null  // var=可变变量；?=可空类型；上一次连接检测摘要
    /**
     * 蓝牙 HID Host profile 代理
     *
     * 通过 [BluetoothAdapter.getProfileProxy] 异步获取，用于查询手机作为 HID Host
     * 时当前连接的蓝牙手柄（如 Xbox Wireless Controller）。代理成功连接后
     * [hidProxyReady] 置 true，轮询即可用 [BluetoothProfile.getConnectedDevices]
     * 判断蓝牙手柄真实在线状态（该 API 不依赖系统广播，不受 MIUI 后台限制）。
     */
    private var hidProxy: BluetoothProfile? = null  // var=可变变量；?=可空类型；蓝牙 HID Host 代理
    /** HID Host 代理是否成功获取（false 时退化为仅设备条目判断） */
    private var hidProxyReady: Boolean = false  // var=可变变量；HID 代理是否就绪

    /**
     * 创建全屏透明焦点窗口捕获手柄事件
     *
     * - 1x1 像素: 不覆盖屏幕，避免遮挡其他应用 UI
     * - FLAG_NOT_TOUCHABLE: 触摸穿透到下层应用
     * - 可获焦点: 接收手柄KeyEvent和MotionEvent
     *
     * 注意: 有焦点的 TYPE_APPLICATION_OVERLAY 窗口会让 Android 14+ 预测式返回手势
     * 失效（系统认为有窗口可能要处理返回键）。因此需要提供"暂停捕获"按钮，
     * 用户需要右滑返回时手动暂停；暂停会真正移除本窗口（见 [pauseCapturing]）。
     */
    private fun createGamepadInputWindow() {  // private=私有；fun=函数声明；创建全屏透明焦点窗口
        if (isOverlayPaused) return  // if 判断：悬浮窗暂停中则直接返回
        if (gamepadInputView != null) return  // 已存在，避免重复创建  // if 判断：窗口已存在则直接返回，避免重复创建
        gamepadInputView = GamepadInputView(this).also { view ->  // also{}=副作用 lambda（返回原对象）；创建焦点输入视图
            // Ctrl+Alt+Shift+X 切换悬浮窗（在 GamepadInputView 中直接消费，不发往 Windows）
            view.onToggleOverlay = { mainHandler.post { toggleOverlayView() } }  // 设置切换悬浮窗回调（回主线程）
            // 转发手柄事件到 KeyboardMouseMapper → SteamInput
            view.onKeyEvent = { event ->  // lambda {event->}；设置按键事件回调
                mapper?.onKeyEvent(event) ?: false  // ?.=安全调用；?:=空值合并；转发按键事件，映射器为空返回 false
            }  // 结束 onKeyEvent lambda
            view.onGenericMotion = { event ->  // lambda {event->}；设置通用摇杆运动事件回调
                mapper?.onGenericMotionEvent(event) ?: false  // ?.=安全调用；?:=空值合并；转发摇杆事件
            }  // 结束 onGenericMotion lambda
            // IME 软键盘输入 → TCP → Windows SendInput 注入 WoW
            view.onImeChar = { ch -> forwardImeChar(ch) }  // lambda {ch->}；设置 IME 字符输入回调
            view.onImeKey = { event -> forwardImeKey(event) }  // lambda {event->}；设置 IME 按键事件回调
            // 键盘被隐藏（点击键盘自身隐藏按钮/返回键/输入法失活）→ 恢复捕获。
            // 系统不会给应用直接回调，由 GamepadInputView 通过 insets 变化 /
            // 连接关闭 / 返回键三种信号驱动
            view.onImeHidden = { mainHandler.post { handleImeHidden() } }  // lambda；设置键盘隐藏回调（回主线程）
            view.onImeClosed = { mainHandler.post { handleImeHidden() } }  // lambda；设置 IME 连接关闭回调（回主线程）
            view.onImeBackPressed = { mainHandler.post { handleImeHidden() } }  // lambda；设置返回键隐藏回调（回主线程）
        }  // 结束 also 块

        val params = GamepadInputView.createLayoutParams()  // val=只读变量；创建焦点窗口布局参数
        windowManager?.addView(gamepadInputView, params)  // ?.=安全调用；添加焦点窗口到窗口管理器
        isCapturing = true  // 标记正在捕获
        steamInput?.isCapturing = true  // ?.=安全调用；同步 SteamInput 的捕获状态

        // 请求焦点以接收手柄按键事件
        gamepadInputView?.post {  // ?.=安全调用；post{}=投递到主线程执行
            gamepadInputView?.requestFocus()  // ?.=安全调用；请求窗口焦点
        }  // 结束 post lambda
        // 延迟再次请求焦点，确保窗口动画完成后仍获得焦点
        gamepadInputView?.postDelayed({  // ?.=安全调用；postDelayed{}=延迟投递 lambda 到主线程
            gamepadInputView?.requestFocus()  // ?.=安全调用；再次请求窗口焦点
        }, 300)  // 延迟 300 毫秒执行
        Log.i(TAG, "GamepadInputView created (capturing enabled)")  // 打印焦点窗口创建日志
    }  // 结束 createGamepadInputWindow 函数

    /**
     * 暂停手柄捕获：移除 1x1 焦点输入窗口，恢复系统边缘滑动返回手势。
     *
     * 有焦点的 overlay 窗口（即使 1x1）会吃掉 Android 预测式返回手势，实测在这台
     * 设备上暂停后右滑返回无效；必须真正移除窗口才能恢复手势。
     *
     * 移除窗口后手柄按键无法再到达本应用（无障碍按键过滤在这台 MIUI 上未被授予，
     * capabilities=0），因此暂停后无法用手柄"切换捕获"键恢复，只能通过悬浮窗
     * "恢复捕获"按钮或主界面开关恢复（见 [resumeCapturing]）。
     */
    fun pauseCapturing() {  // fun=函数声明（公开）；暂停手柄捕获：移除焦点窗口
        val wasCapturing = isCapturing  // val=只读变量；记录暂停前的捕获状态
        // 始终移除焦点输入窗口
        gamepadInputView?.let { windowManager?.removeView(it) }  // ?.=安全调用；?.let{}=非空时执行；移除焦点窗口
        gamepadInputView = null  // 清空焦点窗口引用
        if (!wasCapturing) {  // if 判断：之前已暂停
            Log.i(TAG, "Focus window removed (already paused)")  // 打印已暂停日志
            return  // 直接返回
        }  // 结束 if 块
        steamInput?.isCapturing = false  // ?.=安全调用；同步 SteamInput 捕获状态为 false
        isCapturing = false  // 标记停止捕获
        // 无障碍转发路径已弃用（该设备未授予按键过滤能力）
        GamepadAccessibilityService.onPausedKeyEvent = null  // 清空无障碍按键转发回调
        updateCaptureButtonState()  // 更新捕获按钮状态
        Log.i(TAG, "Capturing paused (focus window removed)")  // 打印暂停捕获日志
    }  // 结束 pauseCapturing 函数

    fun resumeCapturing() {  // fun=函数声明（公开）；恢复手柄捕获
        if (isCapturing) return  // if 判断：已在捕获则直接返回
        steamInput?.isCapturing = true  // ?.=安全调用；同步 SteamInput 捕获状态为 true
        isCapturing = true  // 标记开始捕获
        manualPaused = false  // 清除手动暂停标志
        // 恢复捕获后焦点窗口重新接收按键，清除无障碍转发
        GamepadAccessibilityService.onPausedKeyEvent = null  // 清空无障碍按键转发回调
        // 暂停时焦点窗口已被 pauseCapturing 移除，恢复捕获时重新创建
        if (gamepadInputView == null && !isOverlayPaused && mapper != null) {  // if 判断：窗口为空、未暂停悬浮窗且映射器就绪
            createGamepadInputWindow()  // 重新创建焦点窗口
        }  // 结束 if 块
        // 主动重新请求焦点（键盘隐藏后窗口可能失去焦点，手柄事件需窗口有焦点才能路由）
        gamepadInputView?.let { view ->  // ?.=安全调用；?.let{}=非空时执行 lambda
            view.post { view.requestFocus() }  // 立即请求焦点
            view.postDelayed({ view.requestFocus() }, 200)  // 延迟 200 毫秒再请求焦点
        }  // 结束 let lambda
        updateCaptureButtonState()  // 更新捕获按钮状态
        // 字符串模板 "$var"=插值；if 表达式；打印恢复捕获日志
        Log.i(TAG, "Capturing resumed (focus window ${if (gamepadInputView == null) "missing, not recreated" else "kept"})")
    }  // 结束 resumeCapturing 函数

    /**
     * 切换手柄捕获状态（按键映射 ToggleCapture 动作触发）
     *
     * 捕获中 → 暂停捕获（设置 manualPaused 阻止智能监控自动恢复）
     * 已暂停 → 恢复捕获
     */
    private fun toggleCaptureState() {  // private=私有；fun=函数声明；切换手柄捕获状态
        if (isCapturing) {  // if 判断：正在捕获
            manualPaused = true  // 标记手动暂停，阻止智能监控自动恢复
            pauseCapturing()  // 暂停捕获
        } else {  // else 分支：已暂停
            manualPaused = false  // 清除手动暂停标志
            resumeCapturing()  // 恢复捕获
        }  // 结束 if-else 块
        // 同步 MainActivity 的捕获状态显示（不持久化 captureEnabled，保持游戏内快速切换）
        broadcastCaptureStatus(isCapturing)  // 广播捕获状态
    }  // 结束 toggleCaptureState 函数

    /**
     * 悬浮窗"暂停/恢复捕获"按钮点击
     *
     * 根据实际捕获状态（isCapturing）切换，并与 captureEnabled / MainActivity 开关双向同步：
     * - 暂停 → 移除焦点窗口（恢复右滑返回手势），持久化 captureEnabled=false
     * - 恢复 → 重建焦点窗口，持久化 captureEnabled=true
     *
     * 切换后自动收起悬浮窗（缩到最小胶囊），避免遮挡游戏画面。
     */
    private fun toggleCaptureFromButton() {  // private=私有；fun=函数声明；悬浮窗捕获按钮点击处理
        val enabled = !isCapturing  // val=只读变量；取反得到目标状态
        manualPaused = !enabled  // 手动暂停标志取反
        captureEnabled = enabled  // 更新捕获总开关
        if (enabled) {  // if 判断：目标为恢复捕获
            resumeCapturing()  // 恢复捕获
        } else {  // else 分支：目标为暂停捕获
            pauseCapturing()  // 暂停捕获
        }  // 结束 if-else 块
        val cfg = AppConfigStore.load(this).copy(captureEnabled = enabled)  // val=只读变量；copy()=复制对象改属性；读取配置并更新捕获开关
        AppConfigStore.save(this, cfg)  // 持久化配置
        broadcastCaptureStatus(enabled)  // 广播捕获状态
        updateCaptureButtonState()  // 更新捕获按钮状态
        // 切换后自动收起悬浮窗到最小，避免遮挡游戏画面
        showCollapsedView()  // 收起悬浮窗
        Log.i(TAG, "Capture button: $enabled")  // 字符串模板 "$var"=插值变量；打印切换日志
    }  // 结束 toggleCaptureFromButton 函数

    /**
     * 设置捕获总开关（悬浮窗按钮 / MainActivity 开关共用入口）
     *
     * 与 app 内部开关状态双向同步：
     * - 持久化到配置文件（AppConfigStore，MainActivity 下次启动读取）
     * - 广播 [ACTION_CAPTURE_STATUS]（MainActivity 实时刷新开关）
     *
     * @param enabled true=恢复捕获, false=暂停捕获
     */
    private fun setCaptureEnabled(enabled: Boolean) {  // private=私有；fun=函数声明；设置捕获总开关
        if (captureEnabled == enabled) return  // if 判断：状态未变化则直接返回
        captureEnabled = enabled  // 更新捕获总开关
        manualPaused = false  // 重置手动暂停标志  // 清除手动暂停标志
        // 持久化到配置文件（保留其他运行时配置不变）
        val cfg = AppConfigStore.load(this).copy(captureEnabled = enabled)  // val=只读变量；copy()=复制对象改属性；读取配置并更新捕获开关
        AppConfigStore.save(this, cfg)  // 持久化配置
        Log.i(TAG, "Capture switch: $enabled")  // 字符串模板 "$var"=插值变量；打印开关日志
        if (enabled) {  // if 判断：开启捕获
            // 用户手动恢复：立即恢复捕获，不受智能监控状态影响
            mainHandler.post { resumeCapturing() }  // 回主线程恢复捕获
        } else {  // else 分支：关闭捕获
            // 手动暂停
            mainHandler.post { pauseCapturing() }  // 回主线程暂停捕获
        }  // 结束 if-else 块
        broadcastCaptureStatus(enabled)  // 广播捕获状态
        updateCaptureButtonState()  // 更新捕获按钮状态
    }  // 结束 setCaptureEnabled 函数

    /**
     * 广播捕获状态给 MainActivity（用于同步 app 内开关）
     */
    private fun broadcastCaptureStatus(capturing: Boolean) {  // private=私有；fun=函数声明；广播捕获状态
        val intent = Intent(ACTION_CAPTURE_STATUS).apply {  // val=只读变量；apply{}=返回对象本身并执行块；创建状态广播 Intent
            setPackage(packageName)  // 限定接收者为本应用
            putExtra(EXTRA_CAPTURING, capturing)  // 携带捕获标志
        }  // 结束 apply 块
        sendBroadcast(intent)  // 发送广播
    }  // 结束 broadcastCaptureStatus 函数

    /**
     * 拉起配置的应用（悬浮窗"拉起应用"按钮）
     *
     * 通过 [launcherPackage]（AppConfig 配置，默认 com.winlator）拉起目标应用，
     * 例如从桌面快速回到 Winlator 游戏。
     * 拥有 SYSTEM_ALERT_WINDOW 权限的应用从后台启动 Activity 属于豁免场景，
     * 不受 Android 10+ 后台启动限制。
     *
     * 拉起应用后自动收起悬浮窗（缩到最小胶囊），避免遮挡游戏画面。
     */
    private fun launchGameApp() {  // private=私有；fun=函数声明；拉起配置的应用
        val pkg = launcherPackage  // val=只读变量；获取拉起应用包名
        if (pkg.isBlank()) {  // if 判断：包名为空
            toast("未配置拉起应用包名，请在 App 内设置")  // 提示未配置包名
            return  // 提前返回
        }  // 结束 if 块
        try {  // try=异常捕获
            val intent = packageManager.getLaunchIntentForPackage(pkg)  // val=只读变量；获取该包名的启动 Intent
            if (intent != null) {  // if 判断：找到启动 Intent
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // 添加新任务栈标志（服务启动 Activity 必需）
                startActivity(intent)  // 启动目标应用
                Log.i(TAG, "Launch app: $pkg")  // 字符串模板 "$var"=插值变量；打印拉起日志
                // 拉起应用后自动收起悬浮窗，避免遮挡游戏画面
                mainHandler.post {  // 回主线程执行
                    if (isExpanded || isMappingView) {  // if 判断：悬浮窗处于展开或映射态
                        showCollapsedView()  // 收起悬浮窗
                    }  // 结束 if 块
                }  // 结束 post lambda
            } else {  // else 分支：未找到该应用
                toast("未找到应用 $pkg，请在 App 内检查拉起应用包名")  // 字符串模板 "$var"=插值变量；提示未找到应用
            }  // 结束 if-else 块
        } catch (e: Exception) {  // catch=捕获异常分支
            Log.e(TAG, "Failed to launch app $pkg", e)  // 字符串模板 "$var"=插值变量；打印拉起失败日志
            toast("拉起 $pkg 失败: ${e.message}")  // 字符串模板 "$var"=插值变量；提示失败原因
        }  // 结束 try-catch 块
    }  // 结束 launchGameApp 函数

    /**
     * 刷新展开视图中"暂停/恢复捕获"按钮的文本与状态色
     *
     * - 捕获中：显示"暂停捕获"，红色系（点击将暂停）
     * - 已暂停：显示"恢复捕获"，绿色系（点击将恢复）
     */
    private fun updateCaptureButtonState() {  // private=私有；fun=函数声明；刷新捕获按钮文本与状态色
        captureButton?.post {  // ?.=安全调用；post{}=投递到主线程
            val capturing = isCapturing  // val=只读变量；读取当前捕获状态
            captureButton?.text = if (capturing) "暂停捕获" else "恢复捕获"  // ?.=安全调用；if 表达式；设置按钮文本
            // 状态色区分：捕获中=红色，已暂停=绿色
            val (normal, pressed) = if (capturing) {  // val=只读变量；解构声明；if 表达式选择颜色对
                COLOR_BTN_DANGER to COLOR_BTN_DANGER_PRESSED  // to=键值对；返回红色系（暂停按钮）
            } else {  // else 分支
                COLOR_BTN_GAME to COLOR_BTN_GAME_PRESSED  // to=键值对；返回绿色系（恢复按钮）
            }  // 结束 if-else 表达式
            captureButton?.background = createStateListBackground(normal, pressed, 8f)  // ?.=安全调用；设置按钮按下态背景
        }  // 结束 post lambda
        // 收起视图也同步更新（刷新状态图标：🎮未连接 / ▶映射中 / ⏸暂停）
        if (!isExpanded) {  // if 判断：悬浮窗未展开
            updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；更新收起胶囊文本
        }  // 结束 if 块
    }  // 结束 updateCaptureButtonState 函数

    // ====================================================================
    // 智能暂停监控（Smart Pause Monitor）
    // ====================================================================

    /**
     * 切换安卓系统键盘显示/隐藏
     *
     * 弹出键盘：**保持捕获、保留 1x1 焦点窗口**并让软键盘绑定到它（软键盘只能
     * 绑定本进程有焦点的窗口，Winlator 是独立进程无法直接绑定）。键入的文本经
     * IME → [forwardImeChar]/[forwardImeKey] → TCP → Windows SendInput 注入 WoW；
     * 手柄事件仍继续到达本应用（不暂停捕获）。
     *
     * 隐藏键盘：隐藏键盘并恢复捕获（若捕获已暂停）。
     *
     * 暂停捕获状态下也可调用（直接弹出/隐藏键盘）。
     */
    private fun toggleSystemKeyboard() {  // private=私有；fun=函数声明；切换安卓系统键盘显示/隐藏
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager  // val=只读变量；as?=安全类型转换（失败得 null）；获取输入法管理器
        if (imm == null) {  // if 判断：无法获取输入法管理器
            toast("无法获取输入法管理器")  // 提示获取失败
            return  // 提前返回
        }  // 结束 if 块

        if (isKeyboardShowing) {  // if 判断：键盘正在显示
            // 键盘正在显示 → 隐藏键盘
            imm.hideSoftInputFromWindow(gamepadInputView?.windowToken, 0)  // ?.=安全调用；隐藏软键盘
            isKeyboardShowing = false  // 清除键盘显示标记
            manualPaused = false  // 清除手动暂停标志
            if (!isCapturing) {  // if 判断：当前未捕获
                mainHandler.post { resumeCapturing() }  // 回主线程恢复捕获
            }  // 结束 if 块
            // 键盘隐藏后主动重新请求焦点：1x1 焦点窗口可能在 IME 交互后失去焦点，
            // 手柄事件需窗口有焦点才能继续路由到本应用
            mainHandler.postDelayed({  // postDelayed{}=延迟投递 lambda 到主线程
                gamepadInputView?.let { view ->  // ?.=安全调用；?.let{}=非空时执行 lambda
                    view.requestFocus()  // 请求窗口焦点
                    view.postDelayed({ view.requestFocus() }, 200)  // 延迟 200 毫秒再请求焦点
                }  // 结束 let lambda
            }, 100)  // 延迟 100 毫秒执行
            Log.i(TAG, "Keyboard hidden, capture resumed")  // 打印键盘隐藏日志
        } else {  // else 分支：键盘未显示
            // 键盘未显示 → 弹出键盘（保持捕获，不暂停）
            isKeyboardShowing = true  // 标记键盘显示中
            mainHandler.post {  // 回主线程弹出键盘
                gamepadInputView?.let { view ->  // ?.=安全调用；?.let{}=非空时执行 lambda
                    view.requestFocus()  // 先请求窗口焦点
                    imm.showSoftInput(view, 0)  // 弹出软键盘
                }  // 结束 let lambda
                Log.i(TAG, "Keyboard requested (capture kept active)")  // 打印弹出键盘日志
            }  // 结束 post lambda
        }  // 结束 if-else 块
    }  // 结束 toggleSystemKeyboard 函数

    /**
     * IME 键盘已被隐藏（点击键盘自身隐藏按钮/返回键/输入法失活）→ 恢复捕获。
     *
     * 与 [toggleSystemKeyboard] 的隐藏分支执行相同清理，但由 GamepadInputView 的
     * insets / 连接关闭 / 返回键信号驱动，而非"切换键盘"手柄键。
     *
     * 注意：这些信号在键盘未显示时也可能触发（如窗口 insets 变化），
     * 用 [isKeyboardShowing] 守卫，仅当确实认为键盘在显示时才处理。
     */
    private fun handleImeHidden() {  // private=私有；fun=函数声明；处理 IME 键盘隐藏信号
        if (!isKeyboardShowing) return  // if 判断：键盘未显示则忽略
        isKeyboardShowing = false  // 清除键盘显示标记
        manualPaused = false  // 清除手动暂停标志
        if (!isCapturing) {  // if 判断：当前未捕获
            mainHandler.post { resumeCapturing() }  // 回主线程恢复捕获
        }  // 结束 if 块
        // 键盘隐藏后主动重新请求焦点：1x1 焦点窗口可能在 IME 交互后失去焦点，
        // 手柄事件需窗口有焦点才能继续路由到本应用
        mainHandler.postDelayed({  // postDelayed{}=延迟投递 lambda 到主线程
            gamepadInputView?.let { view ->  // ?.=安全调用；?.let{}=非空时执行 lambda
                view.requestFocus()  // 请求窗口焦点
                view.postDelayed({ view.requestFocus() }, 200)  // 延迟 200 毫秒再请求焦点
            }  // 结束 let lambda
        }, 100)  // 延迟 100 毫秒执行
        Log.i(TAG, "Keyboard hidden detected (IME signal), capture resumed")  // 打印 IME 隐藏检测日志
    }  // 结束 handleImeHidden 函数

    /**
     * 转发 IME 单个字符到 Windows 注入
     *
     * - '\b': 退格 VK_BACK (0x08)
     * - '\u007F': 向前删除 VK_DELETE (0x2E)
     * - 普通字符: 按 [BridgeInputInjector.charToWindowsVK] 转为 VK + 必要时
     *   按下 Shift（VK_LSHIFT=0xA0）后注入按下/释放
     */
    private fun forwardImeChar(ch: Char) {  // private=私有；fun=函数声明；转发 IME 单个字符到 Windows 注入
        val server = bridgeServer ?: return  // val=只读变量；?:=空值合并；服务器为空则直接返回
        Log.d(TAG, "forwardImeChar ch='$ch' code=${ch.code}")  // 字符串模板 "$var"=插值变量；打印字符与码点
        when (ch) {  // when=分支判断；按字符分发
            '\b' -> {  // 分支：退格字符
                server.sendKeyEvent(0x08, true)   // VK_BACK  // 发送退格键按下事件（VK_BACK）
                server.sendKeyEvent(0x08, false)  // 发送退格键释放事件
            }  // 结束退格分支
            '\u007F' -> {  // 分支：向前删除字符（DEL）
                server.sendKeyEvent(0x2E, true)   // VK_DELETE  // 发送删除键按下事件（VK_DELETE）
                server.sendKeyEvent(0x2E, false)  // 发送删除键释放事件
            }  // 结束 DEL 分支
            else -> {  // 默认分支：普通字符
                val (vk, shift) = BridgeInputInjector.charToWindowsVK(ch) ?: return  // val=只读变量；解构声明；?:=空值合并；字符转 VK 码与是否需要 Shift
                if (shift) server.sendKeyEvent(0xA0, true)  // VK_LSHIFT  // if 判断；需要 Shift 时先按下左 Shift
                server.sendKeyEvent(vk, true)  // 发送字符键按下事件
                server.sendKeyEvent(vk, false)  // 发送字符键释放事件
                if (shift) server.sendKeyEvent(0xA0, false)  // if 判断；需要 Shift 时释放左 Shift
            }  // 结束默认分支
        }  // 结束 when 分支
    }  // 结束 forwardImeChar 函数

    /**
     * 转发 IME 特殊按键事件到 Windows 注入
     *
     * 仅处理按下事件（一次注入完整的按下+释放），把 Android KeyCode 映射为
     * Windows VK 后发送。退格/删除已在 [forwardImeChar] 处理，不会重复。
     */
    private fun forwardImeKey(event: KeyEvent) {  // private=私有；fun=函数声明；转发 IME 特殊按键事件
        if (event.action != KeyEvent.ACTION_DOWN) return  // if 判断：仅处理按下事件
        val server = bridgeServer ?: return  // val=只读变量；?:=空值合并；服务器为空则直接返回
        val vk = BridgeInputInjector.androidKeyCodeToWindowsVK(event.keyCode)  // val=只读变量；Android 按键码转 Windows VK
        if (vk != 0) {  // if 判断：VK 码有效
            Log.d(TAG, "forwardImeKey key=${event.keyCode} vk=0x${vk.toString(16)}")  // 字符串模板 "$var"=插值变量；打印按键码与十六进制 VK
            server.sendKeyEvent(vk, true)  // 发送按键按下事件
            server.sendKeyEvent(vk, false)  // 发送按键释放事件
        }  // 结束 if 块
    }  // 结束 forwardImeKey 函数

    /**
     * 启动智能暂停监控线程
     *
     * 每 [SMART_MONITOR_INTERVAL_MS] 轮询一次前台应用：
     * - 前台应用在捕获白名单内（或为本应用）→ 需要捕获 → 恢复焦点窗口
     * - 否则 → 暂停捕获（移除焦点窗口，下层应用恢复右滑返回）
     *
     * 未授权"使用情况访问"时无法查询前台应用，自动退化为手动模式
     * （监控线程停止，保留悬浮窗手动按钮）。
     */
    private fun startSmartMonitor() {  // private=私有；fun=函数声明；启动智能暂停监控线程
        if (smartMonitorRunning) return  // if 判断：监控已运行则直接返回
        if (!smartPauseEnabled) return  // if 判断：智能暂停关闭则直接返回
        if (!hasUsageStatsPermission()) {  // if 判断：未授权使用情况访问
            Log.w(TAG, "Smart pause disabled: no usage stats permission")  // 打印警告日志
            updateStatus("智能暂停不可用（未授权使用情况访问），已用手动模式")  // 更新状态提示退化为手动模式
            return  // 提前返回
        }  // 结束 if 块
        smartMonitorRunning = true  // 标记监控运行中
        smartMonitorThread = Thread({  // Thread=线程；lambda 语法 { }；创建智能监控线程
            while (smartMonitorRunning) {  // while=循环；监控运行期间持续轮询
                try {  // try=异常捕获
                    // 手动暂停或捕获开关关闭时监控不动作，
                    // 避免自动恢复覆盖用户的暂停意图
                    if (captureEnabled && !manualPaused) {  // if 判断：捕获开启且非手动暂停
                        val fg = getForegroundPackage()  // val=只读变量；查询当前前台应用包名
                        if (fg != null) {  // if 判断：前台应用可确定
                            // 仅白名单应用（如 Winlator）在前台时保持捕获。
                            // 本应用自身在前台时也要暂停捕获（needCapture=false），
                            // 否则应用内界面（MainActivity/操作层设置等）右滑返回
                            // 会被焦点窗口吃掉。
                            val needCapture = captureWhitelist.contains(fg)  // val=只读变量；判断前台应用是否在白名单
                            if (needCapture && !isCapturing) {  // if 判断：需要捕获且当前未捕获
                                Log.i(TAG, "Smart pause: foreground=$fg, resuming capture")  // 字符串模板 "$var"=插值变量；打印自动恢复日志
                                mainHandler.post { resumeCapturing() }  // 回主线程恢复捕获
                            } else if (!needCapture && isCapturing) {  // else if 判断：无需捕获且正在捕获
                                Log.i(TAG, "Smart pause: foreground=$fg, pausing capture")  // 字符串模板 "$var"=插值变量；打印自动暂停日志
                                mainHandler.post { pauseCapturing() }  // 回主线程暂停捕获
                            }  // 结束 else if 块
                        }  // 结束 if 块
                    }  // 结束 if 块
                } catch (e: Exception) {  // catch=捕获异常分支
                    Log.e(TAG, "Smart monitor error, falling back to manual", e)  // 打印监控异常日志
                    break  // 跳出循环，退化为手动模式
                }  // 结束 try-catch 块
                try {  // try=异常捕获
                    Thread.sleep(SMART_MONITOR_INTERVAL_MS)  // 线程休眠一个轮询周期
                } catch (e: InterruptedException) {  // catch=捕获中断异常
                    break  // 被中断则跳出循环
                }  // 结束 try-catch 块
            }  // 结束 while 循环
            smartMonitorRunning = false  // 循环结束标记停止运行
            Log.i(TAG, "Smart monitor stopped")  // 打印监控停止日志
        }, "SteamLike-SmartMonitor").apply { isDaemon = true }  // apply{}=返回对象并执行块；设置线程名为守护线程
        smartMonitorThread?.start()  // ?.=安全调用；启动监控线程
        Log.i(TAG, "Smart monitor started, whitelist=$captureWhitelist")  // 字符串模板 "$var"=插值变量；打印监控启动日志
    }  // 结束 startSmartMonitor 函数

    /**
     * 启动手柄连接状态轮询
     *
     * InputManager 的 onInputDeviceAdded/Removed 回调在部分设备（如 MIUI 蓝牙断开）
     * 上不可靠，会导致悬浮窗图标状态卡死（断开仍显示暂停/映射图标）。
     * 这里周期查询系统真实输入设备 + 蓝牙链路状态，检测到连接状态变化时刷新图标。
     */
    private fun startControllerMonitor() {  // private=私有；fun=函数声明；启动手柄连接状态轮询
        stopControllerMonitor()  // 先停止旧轮询，避免重复
        Log.i(TAG, "Controller monitor started")  // 打印轮询启动日志
        controllerMonitor = object : Runnable {  // object=匿名对象；实现 Runnable 接口
            override fun run() {  // override=覆写父类方法；fun=函数声明；Runnable 的执行体
                val connected = detectControllerConnected()  // val=只读变量；检测手柄是否在线
                // 仅状态变化时刷新 UI，避免高频重复设置文本
                if (connected != controllerConnected) {  // if 判断：连接状态发生变化
                    controllerConnected = connected  // 更新连接状态
                    updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；刷新收起胶囊文本
                    Log.i(TAG, "Controller monitor: connected=$connected")  // 字符串模板 "$var"=插值变量；打印连接状态日志
                }  // 结束 if 块
                mainHandler.postDelayed(this, controllerMonitorIntervalMs)  // 延迟一个周期后再次轮询（自重复）
            }  // 结束 run 函数
        }  // 结束匿名对象
        mainHandler.post(controllerMonitor!!)  // !! = 非空断言；立即投递首次轮询
    }  // 结束 startControllerMonitor 函数

    /**
     * 停止手柄连接状态轮询
     */
    private fun stopControllerMonitor() {  // private=私有；fun=函数声明；停止手柄连接状态轮询
        controllerMonitor?.let { mainHandler.removeCallbacks(it) }  // ?.=安全调用；?.let{}=非空时执行；移除已投递的轮询回调
        controllerMonitor = null  // 清空轮询任务引用
    }  // 结束 stopControllerMonitor 函数

    /**
     * 检测是否有手柄输入设备在线
     *
     * 判断规则（蓝牙 HID 轮询方案）：
     * 1. 系统已无手柄输入设备条目（有线拔插等会被系统直接移除）→ 已断开；
     * 2. 否则查询蓝牙 HID（HID_DEVICE profile）在线列表：
     *    - 无法查询（低版本/未授权 BLUETOOTH_CONNECT）→ 退化为仅靠设备条目，判在线；
     *    - 蓝牙手柄必须出现在 HID 在线列表才算在线（MIUI 上蓝牙手柄断电后输入设备
     *      条目残留，但 HID 连接列表会移除，因此 HID 列表是可靠的在线信号）；
     *    - 非蓝牙手柄（有线）不受 HID 列表影响，直接判在线。
     *
     * 这样连接但静止不动的手柄不会误判为断开（不依赖输入活跃度），
     * 关电源/关手机蓝牙后 HID 列表移除手柄 → 立即判离线。
     *
     * @return true=至少一个手柄在线, false=全部断开
     */
    private fun detectControllerConnected(): Boolean {  // private=私有；fun=函数声明；检测是否有手柄输入设备在线，返回 Boolean
        val gamepadDevices = android.view.InputDevice.getDeviceIds()  // val=只读变量；获取所有输入设备 ID 数组
            .toList()  // 转换为列表
            .mapNotNull { deviceId -> android.view.InputDevice.getDevice(deviceId) }  // mapNotNull{}=映射并过滤 null；把 ID 转为设备对象
            .filter { isGamepadDevice(it) }  // filter{}=按条件过滤；只保留真实游戏手柄
        if (gamepadDevices.isEmpty()) {  // if 判断：无手柄设备
            logDetectSummary("gamepads=[]")  // 打印检测摘要
            return false  // 返回未连接
        }  // 结束 if 块
        // HID Host 代理未就绪：退化为仅依赖设备条目（连接正常时图标不受影响）
        if (!hidProxyReady) return true  // if 判断：HID 代理未就绪则仅凭设备条目判在线
        val hidNames = queryHidHostConnectedNames()  // val=只读变量；查询 HID Host 在线设备名集合
        // val=只读变量；字符串模板 "$var"=插值；joinToString{}=拼接设备名；构建检测摘要
        val summary = "gamepads=[${gamepadDevices.joinToString { it.name ?: "?" }}] hid=$hidNames"
        logDetectSummary(summary)  // 打印检测摘要
        // 蓝牙手柄：名称匹配 HID Host 在线列表才在线（静止不动不误判、
        // 关电源/关蓝牙立即从列表移除）；有线手柄：设备条目存在即在线
        return gamepadDevices.any { device ->  // any{}=任一满足即 true；lambda {device->}；遍历手柄设备
            if (isBluetoothGamepad(device)) {  // if 判断：蓝牙手柄
                device.name != null && hidNames.contains(device.name)  // 蓝牙手柄需出现在 HID 在线列表中才判在线
            } else {  // else 分支：有线手柄
                true  // 有线手柄设备条目存在即判在线
            }  // 结束 if-else 块
        }  // 结束 any lambda
    }  // 结束 detectControllerConnected 函数

    /**
     * 打印连接检测摘要日志（仅在内容变化时打印，避免 2 秒轮询刷屏）
     */
    private fun logDetectSummary(summary: String) {  // private=私有；fun=函数声明；打印连接检测摘要
        if (summary != lastDetectSummary) {  // if 判断：摘要内容有变化
            lastDetectSummary = summary  // 缓存最新摘要
            Log.i(TAG, "Controller detect: $summary")  // 字符串模板 "$var"=插值变量；打印摘要
        }  // 结束 if 块
    }  // 结束 logDetectSummary 函数

    /**
     * 获取蓝牙适配器（统一做版本与权限检查）
     *
     * API 31+ 查询蓝牙状态需要 [android.Manifest.permission.BLUETOOTH_CONNECT]，
     * 未授权或系统版本过低时返回 null。
     *
     * @return 蓝牙适配器；null=无法访问蓝牙
     */
    private fun getBluetoothAdapter(): BluetoothAdapter? {  // private=私有；fun=函数声明；获取蓝牙适配器，返回可空
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null  // if 判断：系统版本低于 Android 12 则返回 null
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)  // if 判断：检查蓝牙连接权限
            != PackageManager.PERMISSION_GRANTED  // 权限未授予
        ) return null  // 未授权则返回 null
        @Suppress("MissingPermission")  // 已在上方检查 BLUETOOTH_CONNECT 权限  // 注解：抑制缺少权限警告
        return (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter  // as?=安全类型转换；?.=安全调用；获取蓝牙管理器并取其适配器
    }  // 结束 getBluetoothAdapter 函数

    /**
     * 注册蓝牙 HID Host profile 代理
     *
     * 通过 [BluetoothAdapter.getProfileProxy] 异步获取 HID_HOST 代理。该代理用于
     * 查询手机当前连接的蓝牙手柄，不依赖系统广播（MIUI 后台广播会被 SmartPower
     * 拦截），是可靠的连接状态信号。代理获取失败时 [hidProxyReady] 保持 false，
     * 连接检测退化为仅依赖设备条目。
     */
    private fun registerHidProxy() {  // private=私有；fun=函数声明；注册蓝牙 HID Host 代理
        if (hidProxyReady) return  // if 判断：代理已就绪则直接返回
        val adapter = getBluetoothAdapter() ?: return  // val=只读变量；?:=空值合并；无法获取适配器则返回
        @Suppress("MissingPermission")  // 已在上方检查 BLUETOOTH_CONNECT 权限  // 注解：抑制缺少权限警告
        runCatching {  // runCatching{}=捕获异常的 run 块，返回 Result
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {  // object=匿名对象；实现 ServiceListener 接口；异步获取 HID 代理
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {  // override=覆写父类方法；fun=函数声明；代理服务连接成功回调
                    if (profile == HID_HOST_PROFILE) {  // if 判断：连接的是 HID Host profile
                        hidProxy = proxy  // 缓存 HID 代理
                        hidProxyReady = true  // 标记代理就绪
                        Log.i(TAG, "HID Host proxy connected")  // 打印代理连接日志
                    }  // 结束 if 块
                }  // 结束 onServiceConnected 函数

                override fun onServiceDisconnected(profile: Int) {  // override=覆写父类方法；fun=函数声明；代理服务断开回调
                    if (profile == HID_HOST_PROFILE) {  // if 判断：断开的是 HID Host profile
                        hidProxy = null  // 清空代理引用
                        hidProxyReady = false  // 标记代理未就绪
                        Log.i(TAG, "HID Host proxy disconnected")  // 打印代理断开日志
                    }  // 结束 if 块
                }  // 结束 onServiceDisconnected 函数
            }, HID_HOST_PROFILE)  // 结束匿名对象，并指定 HID Host profile
        }.onFailure { Log.w(TAG, "getProfileProxy(HID_HOST) failed: ${it.message}") }  // onFailure{}=失败回调 lambda；字符串模板 "$var"=插值；打印获取失败日志
    }  // 结束 registerHidProxy 函数

    /**
     * 注销蓝牙 HID Host profile 代理
     */
    private fun unregisterHidProxy() {  // private=私有；fun=函数声明；注销蓝牙 HID Host 代理
        val adapter = getBluetoothAdapter()  // val=只读变量；获取蓝牙适配器
        val proxy = hidProxy  // val=只读变量；取出缓存的代理
        if (adapter != null && proxy != null) {  // if 判断：适配器与代理均可用
            @Suppress("MissingPermission")  // 已在上方检查 BLUETOOTH_CONNECT 权限  // 注解：抑制缺少权限警告
            runCatching { adapter.closeProfileProxy(HID_HOST_PROFILE, proxy) }  // runCatching{}=捕获异常的 run 块；关闭 profile 代理
        }  // 结束 if 块
        hidProxy = null  // 清空代理引用
        hidProxyReady = false  // 标记代理未就绪
    }  // 结束 unregisterHidProxy 函数

    /**
     * 查询 HID Host 当前连接的蓝牙设备名集合
     *
     * @return 已连接蓝牙设备名（优先 name，无 name 用 MAC 地址）集合
     */
    private fun queryHidHostConnectedNames(): Set<String> {  // private=私有；fun=函数声明；查询 HID Host 在线设备名集合
        val proxy = hidProxy ?: return emptySet()  // val=只读变量；?:=空值合并；代理为空返回空集合
        @Suppress("MissingPermission")  // 已在上方检查 BLUETOOTH_CONNECT 权限  // 注解：抑制缺少权限警告
        return runCatching {  // runCatching{}=捕获异常的 run 块
            proxy.connectedDevices.mapNotNull { it.name ?: it.address }.toSet()  // mapNotNull{}=映射并过滤 null；?:=空值合并；收集设备名（无名字用 MAC）
        }.getOrDefault(emptySet())  // 异常时返回空集合
    }  // 结束 queryHidHostConnectedNames 函数

    /**
     * 判断游戏手柄输入设备是否为蓝牙连接的手柄
     *
     * 蓝牙手柄在系统已配对蓝牙设备列表（bondedDevices）中有对应条目，通过设备名或
     * MAC 地址匹配判断。用于区分有线手柄与蓝牙手柄：有线手柄不在配对列表中。
     *
     * @param device 系统输入设备
     * @return true=蓝牙手柄, false=非蓝牙（有线/USB）或无法判断
     */
    private fun isBluetoothGamepad(device: android.view.InputDevice): Boolean {  // private=私有；fun=函数声明；判断是否为蓝牙手柄
        val adapter = getBluetoothAdapter() ?: return false  // val=只读变量；?:=空值合并；无法获取适配器返回 false
        val name = device.name ?: return false  // val=只读变量；?:=空值合并；设备名为空返回 false
        @Suppress("MissingPermission")  // 已在上方检查 BLUETOOTH_CONNECT 权限  // 注解：抑制缺少权限警告
        return adapter.bondedDevices.any { it.name == name || it.address == name }  // any{}=任一满足即 true；通过名字或 MAC 匹配已配对列表
    }  // 结束 isBluetoothGamepad 函数

    /**
     * 判断输入设备是否为真实游戏手柄
     *
     * 判定条件：
     * 1. 具备游戏手柄源（SOURCE_GAMEPAD 摇杆/ABXY 键 或 SOURCE_JOYSTICK 摇杆）；
     * 2. 且为外接设备（蓝牙/USB 手柄 [InputDevice.isExternal] 为 true）。
     *
     * 注意：不能仅靠源标志判断——系统内置按键设备（如电源键、gpio-keys、虚拟
     * 键盘）也可能声明 DPAD/GAMEPAD 源，但它们非外接，会被 [InputDevice.isExternal]
     * 排除，避免把"无手柄"误判为"有手柄在线"。
     *
     * @param device 系统输入设备
     * @return true=真实外接游戏手柄
     */
    private fun isGamepadDevice(device: android.view.InputDevice): Boolean {  // private=私有；fun=函数声明；判断输入设备是否为真实游戏手柄
        if (!device.isExternal) return false  // if 判断：非外接设备（内置按键设备）排除
        return device.sources and (  // 按位与：检查设备事件源标志
            android.view.InputDevice.SOURCE_GAMEPAD or  // 或：手柄按键源
                android.view.InputDevice.SOURCE_JOYSTICK  // 或：摇杆源
        ) != 0  // 具备任一游戏手柄源即判为手柄
    }  // 结束 isGamepadDevice 函数

    /**
     * 停止智能暂停监控线程
     */
    private fun stopSmartMonitor() {  // private=私有；fun=函数声明；停止智能暂停监控线程
        smartMonitorRunning = false  // 标记停止运行（循环条件失效）
        smartMonitorThread?.interrupt()  // ?.=安全调用；中断监控线程
        smartMonitorThread?.join(500)  // ?.=安全调用；等待线程结束（最多 500 毫秒）
        smartMonitorThread = null  // 清空线程引用
    }  // 结束 stopSmartMonitor 函数

    /**
     * 重启智能暂停监控（配置更新后调用，幂等）
     */
    private fun restartSmartMonitor() {  // private=私有；fun=函数声明；重启智能暂停监控（幂等）
        stopSmartMonitor()  // 先停止旧监控
        startSmartMonitor()  // 再启动新监控
        // 同步悬浮窗"暂停/恢复捕获"按钮状态
        updateCaptureButtonState()  // 更新捕获按钮状态
    }  // 结束 restartSmartMonitor 函数

    /**
     * 查询当前前台应用包名（UsageStats）
     *
     * 通过最近 60 秒的 UsageEvents 取最后一条 ACTIVITY_RESUMED/MOVE_TO_FOREGROUND 事件。
     *
     * @return 前台包名；无法确定时返回 null
     * @throws SecurityException 未授权"使用情况访问"时抛出
     */
    private fun getForegroundPackage(): String? {  // private=私有；fun=函数声明；查询当前前台应用包名，返回可空
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager  // val=只读变量；as=类型转换；获取使用情况统计管理器
        val end = System.currentTimeMillis()  // val=只读变量；当前时间戳
        val events = usm.queryEvents(end - SMART_FOREGROUND_WINDOW_MS, end)  // val=只读变量；查询时间窗内的事件
        val event = UsageEvents.Event()  // val=只读变量；事件容器对象
        var foreground: String? = null  // var=可变变量；?=可空类型；记录前台包名
        while (events.hasNextEvent()) {  // while=循环；遍历所有事件
            events.getNextEvent(event)  // 取出下一条事件
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||  // if 判断：事件为活动恢复
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND  // 或事件为移到前台
            ) {  // 满足前台事件条件
                foreground = event.packageName  // 记录该事件所属包名
            }  // 结束 if 块
        }  // 结束 while 循环
        return foreground  // 返回最后一条前台事件包名
    }  // 结束 getForegroundPackage 函数

    /**
     * 检查"使用情况访问"权限是否已授权
     */
    private fun hasUsageStatsPermission(): Boolean {  // private=私有；fun=函数声明；检查"使用情况访问"权限
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager  // val=只读变量；as=类型转换；获取 AppOps 管理器
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {  // val=只读变量；if 表达式；按系统版本分支
            appOps.unsafeCheckOpNoThrow(  // Android 10+ 用非抛异常方式检查（免权限调用）
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName  // 检查本应用的使用情况访问
            )  // 结束 unsafeCheckOpNoThrow 调用
        } else {  // else 分支：低版本系统
            @Suppress("DEPRECATION")  // 注解：抑制弃用警告
            appOps.checkOpNoThrow(  // 旧版检查接口
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName  // 检查本应用的使用情况访问
            )  // 结束 checkOpNoThrow 调用
        }  // 结束 if-else 表达式
        return mode == AppOpsManager.MODE_ALLOWED  // 比较是否允许
    }  // 结束 hasUsageStatsPermission 函数

    /**
     * 从配置文件重新加载全部运行时配置
     *
     * 在 onCreate 和配置导入后调用，读取 steamlike_config.json 的 settings：
     * 服务器地址/端口、智能暂停开关、捕获白名单、捕获开关、拉起应用包名。
     * 注意：serverHost/serverPort 仅在首次 startMapper 时生效，运行中修改需重启服务。
     */
    private fun reloadAppConfig() {  // private=私有；fun=函数声明；从配置文件重新加载运行时配置
        val cfg = AppConfigStore.load(this)  // val=只读变量；读取保存的运行时配置
        serverHost = cfg.serverHost  // 更新服务器地址
        serverPort = cfg.serverPort  // 更新服务器端口
        smartPauseEnabled = cfg.smartPauseEnabled  // 更新智能暂停开关
        captureWhitelist = cfg.captureWhitelist.toSet()  // 更新捕获白名单
        captureEnabled = cfg.captureEnabled  // 更新捕获总开关
        launcherPackage = cfg.launcherPackage  // 更新拉起应用包名
        Log.i(TAG, "AppConfig loaded: host=$serverHost port=$serverPort " +  // 字符串模板 "$var"=插值变量；打印配置加载日志（续行拼接）
            // 字符串模板 "$var"=插值变量；续行打印其余配置
            "smartPause=$smartPauseEnabled whitelist=$captureWhitelist capture=$captureEnabled launcher=$launcherPackage")
    }  // 结束 reloadAppConfig 函数

    // ===== 悬浮窗 UI =====

    /**
     * 创建悬浮窗（初始为收起状态）
     *
     * 悬浮窗有两种状态:
     * - 收起: 显示一个小图标按钮，可拖动移动位置，点击展开
     * - 展开: 显示完整的操作层面板（状态、层按钮、提示），可拖动
     *
     * 两种状态共享 [overlayParams] 的位置坐标，切换时保持位置不变。
     */
    private fun createOverlay() {  // private=私有；fun=函数声明；创建悬浮窗（初始为收起状态）
        overlayParams = WindowManager.LayoutParams(  // 创建悬浮窗布局参数
            WindowManager.LayoutParams.WRAP_CONTENT,  // 宽度自适应内容
            WindowManager.LayoutParams.WRAP_CONTENT,  // 高度自适应内容
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)  // if 表达式；按系统版本选择窗口类型
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY  // Android 8+ 使用应用悬浮窗类型
            else  // else 分支：低版本
                @Suppress("DEPRECATION")  // 注解：抑制弃用警告
                WindowManager.LayoutParams.TYPE_PHONE,  // 旧版手机悬浮窗类型
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or  // 或：不可获焦点标志
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,  // 或：全屏布局标志
            PixelFormat.TRANSLUCENT  // 半透明像素格式
        ).apply {  // apply{}=返回对象本身并执行块；配置布局参数
            gravity = Gravity.TOP or Gravity.START  // 重力：左上角定位
            x = 0  // 初始横坐标 0
            y = 100  // 初始纵坐标 100
        }  // 结束 apply 块

        // 常驻单窗口容器（FrameLayout）：收起/展开只切换内部子视图，
        // 不再 removeView/addView 重建窗口，避免系统窗口动画造成的闪烁
        val frame = FrameLayout(this)  // val=只读变量；创建常驻容器
        // 常驻圆角边框：普通态半透明白色细边，右键长按锁存时切换为红色高亮
        overlayFrameBackground = roundedDrawable(  // 创建圆角边框背景
            color = 0x00000000,               // 填充透明，仅显示边框  // 命名参数；填充透明
            cornerRadius = dp(16),  // 命名参数；圆角半径 16dp
            strokeColor = COLOR_OVERLAY_BORDER_NORMAL,  // 命名参数；边框颜色（普通态）
            strokeWidth = dp(2)  // 命名参数；边框宽度 2dp
        )  // 结束 roundedDrawable 调用
        frame.background = overlayFrameBackground  // 设置容器背景为圆角边框
        overlayView = frame  // 记录悬浮窗容器引用
        overlayParams?.let { windowManager?.addView(frame, it) }  // ?.=安全调用；?.let{}=非空时执行；添加容器到窗口管理器
        setupOverlayTouchListener(frame)  // 为容器设置拖动与点击监听
        // 初始显示收起胶囊
        showCollapsedView()  // 显示收起胶囊
        // 启用手柄电量定时刷新（先移除防重复排队）
        mainHandler.removeCallbacks(batteryRefreshTask)  // 移除可能存在的旧任务
        mainHandler.postDelayed(batteryRefreshTask, BATTERY_REFRESH_INTERVAL_MS)  // 延迟投递刷新任务，启动自排队循环
    }  // 结束 createOverlay 函数

    /**
     * 后台读取手柄电量并刷新悬浮窗显示
     *
     * [ControllerDevice.readBattery] 在部分设备上会阻塞数秒（实测 Xbox 手柄约 10 秒），
     * 因此必须在后台线程执行，读完再回主线程更新文本。
     * 展开面板未创建（收起状态）时 [batteryText] 为 null，直接返回不做任何事。
     */
    private fun refreshBatteryText() {  // private=私有；fun=函数声明；后台刷新电量文本
        if (batteryText == null) return  // 面板未创建则无需读取（语法：if 单行返回）
        Thread {  // 启动后台线程执行阻塞读取（语法：Thread { }.start() 匿名线程）
            val battery = ControllerDevice.readBattery()  // 读取手柄电量（阻塞操作）
            mainHandler.post { applyBatteryText(battery) }  // 回主线程更新 UI（语法：post 投递 Runnable）
        }.start()  // 启动线程
    }  // 结束 refreshBatteryText 函数

    /**
     * 在主线程把电量信息写入悬浮窗文本
     *
     * @param battery 电量信息；null 表示无手柄或系统不支持，此时隐藏该行
     */
    private fun applyBatteryText(battery: GamepadBattery?) {  // private=私有；fun=函数声明；应用电量文本
        val view = batteryText ?: return  // 取电量文本控件，为空（面板已收起/销毁）则直接返回（语法：?: 空值合并提前返回）
        if (battery == null) {  // 无电量数据时（语法：if 分支）
            view.visibility = View.GONE  // 隐藏该行
        } else {  // 读到电量信息（可能为"未知"）时
            view.text = battery.displayText  // 更新文本内容
            view.visibility = View.VISIBLE  // 显示该行
        }  // 结束分支
    }  // 结束 applyBatteryText 函数

    /**
     * 切换悬浮窗常驻边框颜色（鼠标右键长按锁存提示）
     *
     * @param active true=右键长按锁存激活（红色高亮边框），false=恢复普通态
     */
    private fun setRightToggleBorder(active: Boolean) {  // private=私有；fun=函数声明；切换悬浮窗边框颜色
        mainHandler.post {  // 回主线程执行
            val color = if (active) COLOR_OVERLAY_BORDER_ACTIVE else COLOR_OVERLAY_BORDER_NORMAL  // val=只读变量；if 表达式；按状态选颜色
            overlayFrameBackground?.setStroke(dp(2), color)  // ?.=安全调用；设置边框宽度与颜色
            Log.d(TAG, "Overlay border: ${if (active) "ACTIVE(right hold)" else "normal"}")  // 字符串模板 "$var"=插值；if 表达式；打印边框状态
        }  // 结束 post lambda
    }  // 结束 setRightToggleBorder 函数

    /**
     * 更新按键映射列表页的按钮高亮（手柄按键按下/释放反馈）
     *
     * 仅在映射列表视图显示时生效，按下高亮对应按钮项，释放恢复。
     *
     * @param button 手柄按钮
     * @param pressed true=按下高亮, false=释放恢复
     */
    private fun updateMappingViewHighlight(button: ControllerButton, pressed: Boolean) {  // private=私有；fun=函数声明；更新映射页按钮高亮
        if (!isMappingView) return  // if 判断：不在映射视图则直接返回
        val item = mappingViewItems[button] ?: return  // val=只读变量；?:=空值合并；无对应项则直接返回
        mainHandler.post {  // 回主线程执行
            val bg = if (pressed) COLOR_MAPPING_ACTIVE else COLOR_MAPPING_ITEM  // val=只读变量；if 表达式；按按下状态选颜色
            // 复用已有 drawable 仅改颜色：避免每次按下新建 Drawable 并替换 background，
            // 引发整窗硬件重绘造成"整个闪屏"
            (item.background as? GradientDrawable)?.setColor(bg) ?: run {  // as?=安全类型转换；?.=安全调用；?:=空值合并；run{}=执行块；复用背景改色，失败则新建
                item.background = roundedDrawable(bg, dp(6))  // 新建圆角背景
            }  // 结束 run 块
        }  // 结束 post lambda
    }  // 结束 updateMappingViewHighlight 函数

    /**
     * 显示收起状态的悬浮窗
     *
     * 收起状态显示一个小胶囊（当前激活层名），点击展开。
     */
    private fun showCollapsedView() {  // private=私有；fun=函数声明；显示收起状态的悬浮窗
        val frame = overlayView as? FrameLayout ?: return  // val=只读变量；as?=安全类型转换；?:=空值合并；容器无效则直接返回
        isMappingView = false  // 退出映射视图
        mappingViewItems.clear()  // 清空映射项高亮引用
        if (isExpanded && frame.childCount > 0) {  // if 判断：展开且有子视图（需播放离场动画）
            // 展开面板先缩小淡出（离场动画），动画结束后切换到收起胶囊
            val panel = frame.getChildAt(0)  // val=只读变量；取展开面板
            panel.animate()  // 对面板执行属性动画
                .scaleX(0.6f)  // 横向缩放到 0.6
                .scaleY(0.6f)  // 纵向缩放到 0.6
                .alpha(0f)  // 透明度渐变为 0
                .setDuration(200)  // 动画时长 200 毫秒
                .setInterpolator(AccelerateInterpolator())  // 加速插值器（离场加速）
                .withEndAction { showCollapsedNow(frame) }  // withEndAction{}=动画结束回调 lambda；结束后切换收起
                .start()  // 启动动画
            // 常驻边框同步淡出，避免收起过程中白色边框突兀闪现；
            // 动画结束后强制恢复不透明度：否则边框动画终值(alpha=0)会覆盖
            // showCollapsedNow 中的 frame.alpha=1f，导致整窗透明不可见
            // （表现为"点击后悬浮窗消失，重启映射也不出现"）
            frame.animate()  // 对常驻容器执行动画
                .alpha(0f)  // 透明度渐变为 0
                .setDuration(200)  // 动画时长 200 毫秒
                .setInterpolator(AccelerateInterpolator())  // 加速插值器
                .withEndAction { frame.alpha = 1f }  // withEndAction{}=动画结束回调 lambda；结束后恢复不透明度
                .start()  // 启动动画
        } else {  // else 分支：收起态或无需动画
            showCollapsedNow(frame)  // 直接切换到收起状态
        }  // 结束 if-else 块
    }  // 结束 showCollapsedView 函数

    /**
     * 实际切换到收起状态（单窗口内替换子视图）
     *
     * @param frame 悬浮窗常驻容器
     */
    private fun showCollapsedNow(frame: FrameLayout) {  // private=私有；fun=函数声明；实际切换到收起状态
        if (isOverlayPaused) return  // 悬浮窗暂停中不重建  // if 判断：悬浮窗暂停中则直接返回

        // 取消仍在运行的边框淡出动画，避免其终值(alpha=0)覆盖下面的恢复
        frame.animate().cancel()  // 取消容器正在运行的动画
        // 移除旧子视图（与新增同帧完成，无空白帧）
        frame.removeAllViews()  // 清空容器内所有子视图
        // 恢复常驻边框不透明度（收起动画期间被淡出为 0）
        frame.alpha = 1f  // 恢复容器不透明度

        // 创建收起视图（圆角胶囊：显示当前激活层名，点击展开）
        val collapsed = TextView(this).apply {  // val=只读变量；apply{}=返回对象本身并执行块；创建收起胶囊文本视图
            text = buildCollapsedText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；设置胶囊文本
            textSize = 13f  // 设置文本大小
            setTextColor(0xFFFFFFFF.toInt())  // 设置白色文本
            // 圆角胶囊背景 + 细边框（半透明，透出背面画面）
            background = roundedDrawable(  // 创建圆角胶囊背景
                color = 0x88222222.toInt(),  // 命名参数；深色半透明填充
                cornerRadius = dp(18),  // 命名参数；圆角半径 18dp
                strokeColor = COLOR_COLLAPSED_STROKE,  // 命名参数；胶囊边框颜色
                strokeWidth = dp(1)  // 命名参数；边框宽度 1dp
            )  // 结束 roundedDrawable 调用
            setPadding(dp(16), dp(8), dp(16), dp(8))  // 设置内边距
        }  // 结束 apply 块
        collapsedTextView = collapsed  // 记录胶囊视图引用
        frame.addView(collapsed, FrameLayout.LayoutParams(  // 添加胶囊到容器并指定布局参数
            FrameLayout.LayoutParams.WRAP_CONTENT,  // 宽度自适应
            FrameLayout.LayoutParams.WRAP_CONTENT  // 高度自适应
        ))  // 结束 addView 调用

        // 轻量进入动画：起始可见（alpha 0.4 + 缩放 0.9），避免首帧透明造成闪烁
        collapsed.alpha = 0.4f  // 初始透明度 0.4
        collapsed.scaleX = 0.9f  // 初始横向缩放 0.9
        collapsed.scaleY = 0.9f  // 初始纵向缩放 0.9
        collapsed.animate()  // 对胶囊执行进入动画
            .alpha(1f)  // 透明度渐变为 1
            .scaleX(1f)  // 横向缩放为 1
            .scaleY(1f)  // 纵向缩放为 1
            .setDuration(200)  // 动画时长 200 毫秒
            .setInterpolator(DecelerateInterpolator())  // 减速插值器（入场减速）
            .start()  // 启动动画
        isExpanded = false  // 标记收起状态
    }  // 结束 showCollapsedNow 函数

    /**
     * 悬浮窗进入动画（淡入 + 缩放 + 下移回弹）
     *
     * 用于展开/收起状态切换时的过渡。时长与位移调大，保证动效清晰可见。
     *
     * @param view 刚添加的新视图
     */
    private fun animateOverlayIn(view: View) {  // private=私有；fun=函数声明；悬浮窗进入动画
        // 常驻边框（frame 背景）与面板一起淡入：否则面板淡入时白色边框瞬间弹到全尺寸造成闪屏
        overlayView?.alpha = 0f  // ?.=安全调用；先让容器透明
        view.alpha = 0f  // 新视图初始透明
        view.scaleX = 0.8f  // 初始横向缩放 0.8
        view.scaleY = 0.8f  // 初始纵向缩放 0.8
        view.translationY = -dp(40).toFloat()  // 初始向上偏移 40dp
        view.animate()  // 对新视图执行进入动画
            .alpha(1f)  // 透明度渐变为 1
            .scaleX(1f)  // 横向缩放为 1
            .scaleY(1f)  // 纵向缩放为 1
            .translationY(0f)  // 纵向位移归零（回弹）
            .setDuration(420)  // 动画时长 420 毫秒
            .setInterpolator(DecelerateInterpolator())  // 减速插值器
            .start()  // 启动动画
        overlayView?.animate()  // ?.=安全调用；对容器执行淡入动画
            ?.alpha(1f)  // ?.=安全调用；透明度渐变为 1
            ?.setDuration(420)  // ?.=安全调用；动画时长 420 毫秒
            ?.setInterpolator(DecelerateInterpolator())  // ?.=安全调用；减速插值器
            ?.start()  // ?.=安全调用；启动动画
    }  // 结束 animateOverlayIn 函数

    /**
     * 根据当前状态构建收起悬浮窗的显示文本（含状态图标）
     *
     * 三种状态图标（优先级从高到低）:
     * 1. 手柄未连接: 显示"🎮 未连接"，提示需先连接手柄
     * 2. 捕获暂停: 层名后追加"⏸"（暂停图标），提醒手柄映射未工作
     * 3. 映射中(捕获中): 层名后追加"▶"（播放图标），表示手柄映射生效
     *
     * 层名规则:
     * - 空列表（无激活层）: 显示"公共层"
     * - 单个激活层: 显示该层名
     * - 多个激活层: 显示最上层名（最后激活的）
     *
     * @param activeLayers 激活层名称列表
     * @return 显示文本
     */
    private fun buildCollapsedText(activeLayers: List<String>): String {  // private=私有；fun=函数声明；构建收起悬浮窗的显示文本
        // 手柄未连接时优先提示，避免用户误以为映射未生效是配置问题
        if (!controllerConnected) {  // if=条件判断；手柄未连接时进入分支
            return "🎮 未连接"  // 返回"未连接"提示文本
        }  // 结束 if 分支
        // 优先显示中文/自定义显示名，未匹配到则用内部名
        val displayMap = getLayerDisplayNames().toMap()  // val=只读变量；取层显示名列表并转 Map（键=内部名，值=显示名）
        val layerName = if (activeLayers.isEmpty()) {  // val=只读变量；if 表达式：无激活层时取"公共层"
            "公共层"  // 无激活层时的显示名
        } else {  // 否则（有激活层）
            displayMap[activeLayers.last()] ?: activeLayers.last()  // 取最上层（最后激活）的显示名；?:=空值合并，无显示名时回退内部名
        }  // 结束 if 表达式
        // 捕获暂停 → ⏸；映射中 → ▶（图标与捕获状态一一对应）
        return if (isCapturing) "$layerName ▶" else "$layerName ⏸"  // return=返回；字符串模板"$var"拼接图标：捕获中显示▶，暂停显示⏸
    }  // 结束 buildCollapsedText 函数

    /**
     * 更新收起悬浮窗的层名显示
     *
     * 操作层切换时调用，让用户无需展开即可知道当前激活的层。
     * 仅在收起状态下生效（展开状态由 layerText 显示完整堆栈）。
     *
     * @param activeLayers 激活层名称列表
     */
    private fun updateCollapsedViewText(activeLayers: List<String>) {  // private=私有；fun=函数声明；更新收起悬浮窗的层名显示
        collapsedTextView?.post {  // ?.=安全调用（控件可能为空）；post=向主线程消息队列投递 lambda，在 UI 线程更新文本
            collapsedTextView?.text = buildCollapsedText(activeLayers)  // ?.=安全调用；设置收起胶囊文本为构建出的层名文本
        }  // 结束 post lambda
    }  // 结束 updateCollapsedViewText 函数

    /**
     * 显示展开状态的悬浮窗
     *
     * 移除收起视图，创建完整的操作层面板。
     * 面板包含"收起"按钮可切换回收起状态。
     */
    private fun showExpandedView() {  // private=私有；fun=函数声明；显示展开状态的悬浮窗面板
        val frame = overlayView as? FrameLayout ?: return  // val=只读变量；as?=安全转换（失败得 null）；?:=空值合并，容器为空则直接返回
        // 移除收起胶囊（与新增面板同帧完成，无空白帧）
        frame.removeAllViews()  // 清空容器内所有子视图
        isMappingView = false  // 标记当前不是映射列表视图
        mappingViewItems.clear()  // 清空映射项引用表

        val container = LinearLayout(this).apply {  // val=只读变量；apply=作用域函数（返回对象自身）；创建纵向布局容器
            orientation = LinearLayout.VERTICAL  // 设置容器方向为纵向
            // 圆角深色面板背景由外层滚动容器统一提供（见 wrapPanelInScroll），此处保持透明
            setPadding(dp(12), dp(10), dp(12), dp(10))  // 设置内边距（dp 转像素）
        }  // 结束 apply lambda

        // 面板标题
        container.addView(TextView(this).apply {  // 添加标题 TextView；apply=作用域函数
            text = "SteamLike 手柄"  // 设置标题文本
            textSize = 13f  // 设置字号 13sp
            setTextColor(0xFFCCCCCC.toInt())  // 设置浅灰色文字
            setPadding(0, 0, 0, dp(2))  // 设置底部内边距 2dp
        })  // 结束 apply lambda

        // 状态（恢复最近一次的状态文本，避免展开后仍显示"初始化中"）
        statusText = TextView(this).apply {  // apply=作用域函数；创建状态文本控件并赋值给成员字段
            text = currentStatus  // 显示缓存的最近状态文本
            setTextColor(0xFFFFFFFF.toInt())  // 设置白色文字
            textSize = 11f  // 设置字号 11sp
            setPadding(0, 0, 0, dp(2))  // 设置底部内边距 2dp
        }  // 结束 apply lambda
        container.addView(statusText)  // 把状态文本加入容器

        // 当前操作集（第一行信息，琥珀色区分于层名）
        actionSetText = TextView(this).apply {  // apply=作用域函数；创建操作集文本控件并赋值给成员字段
            text = "操作集: ${mapper?.getActiveActionSetName() ?: "默认"}"  // 字符串模板"${}"; ?.=安全调用；?:=空值合并（mapper 为空时显示"默认"）
            setTextColor(0xFFFFD54F.toInt())  // 设置琥珀色文字
            textSize = 12f  // 设置字号 12sp
            setPadding(0, dp(2), 0, dp(2))  // 设置上下内边距 2dp
        }  // 结束 apply lambda
        container.addView(actionSetText)  // 把操作集文本加入容器

        // 手柄电量（Android 14+ 才能读取；后台读取完成后再显示，未连接手柄时该行隐藏）
        batteryText = TextView(this).apply {  // apply=作用域函数；创建电量文本控件并赋值给成员字段
            text = ""  // 初始为空，读取完成前不显示
            setTextColor(COLOR_BATTERY_TEXT)  // 设置浅绿色文字
            textSize = 11f  // 设置字号 11sp
            setPadding(0, 0, 0, dp(2))  // 设置底部内边距 2dp
            visibility = View.GONE  // 读取完成前隐藏该行，避免出现空行
        }  // 结束 apply lambda
        container.addView(batteryText)  // 把电量文本加入容器
        refreshBatteryText()  // 展开面板后立即后台读取一次电量

        // 操作层堆栈
        layerText = TextView(this).apply {  // apply=作用域函数；创建操作层堆栈文本控件并赋值给成员字段
            text = ""  // 初始为空文本，稍后由 updateLayerText 填充
            setTextColor(0xFF9FA8FF.toInt())  // 设置浅蓝紫色文字
            textSize = 10f  // 设置字号 10sp
            setPadding(0, dp(2), 0, dp(4))  // 设置上下内边距
        }  // 结束 apply lambda
        container.addView(layerText)  // 把层堆栈文本加入容器

        // 操作层按钮 (2列 x 5行)，层名与 app 内部配置动态同步
        getLayerDisplayNames().chunked(2).forEach { rowLayers ->  // chunked(2)=每 2 个一组；forEach=lambda 遍历每行（每行两个层）
            val row = LinearLayout(this).apply {  // val=只读变量；apply=作用域函数；创建每行的横向布局
                orientation = LinearLayout.HORIZONTAL  // 设置行方向为横向
                setPadding(0, dp(2), 0, 0)  // 设置顶部内边距 2dp
            }  // 结束 apply lambda
            rowLayers.forEach { (name, display) ->  // forEach=lambda 遍历；解构声明 (name, display) 取出内部名与显示名
                row.addView(createLayerButton(name, display))  // 创建该操作层的按钮并加入行
            }  // 结束内层 forEach lambda
            container.addView(row)  // 把整行加入容器
        }  // 结束外层 forEach lambda

        // ===== 控制按钮（分组布局）=====
        // 主操作行：拉起应用（绿色主按钮） + 暂停/恢复捕获
        val primaryRow = LinearLayout(this).apply {  // val=只读变量；apply=作用域函数；创建主操作行横向布局
            orientation = LinearLayout.HORIZONTAL  // 设置行方向为横向
            setPadding(0, dp(6), 0, 0)  // 设置顶部内边距 6dp
        }  // 结束 apply lambda
        primaryRow.addView(  // 向主行添加按钮
            createOverlayButton(  // 调用工厂方法创建按钮
                label = "拉起应用",  // 按钮文本（命名参数）
                onClick = { launchGameApp() },  // 点击回调 lambda：拉起游戏应用
                weight = 1.2f,  // 横向均分权重 1.2（占更大比例）
                normalColor = COLOR_BTN_GAME,  // 正常态背景色（绿色）
                pressedColor = COLOR_BTN_GAME_PRESSED,  // 按下态背景色（深绿）
                textSize = 12f  // 字号 12sp
            )  // 结束 createOverlayButton 调用
        )  // 结束 addView
        // 暂停/恢复捕获按钮（始终显示，反映实际捕获状态，与 app 内开关双向同步）
        captureButton = createOverlayButton(  // 创建暂停/恢复捕获按钮并保存引用
            label = "暂停捕获",  // 按钮文本
            onClick = { toggleCaptureFromButton() },  // 点击回调 lambda：切换捕获状态
            weight = 1f,  // 均分权重 1
            textSize = 12f  // 字号 12sp
        )  // 结束 createOverlayButton 调用
        primaryRow.addView(captureButton!!)  // !! =非空断言；把捕获按钮加入主行（此时已赋值必非空）
        container.addView(primaryRow)  // 把主操作行加入容器

        // 次操作行：映射 + 收起 + 关闭（关闭红色）
        val secondaryRow = LinearLayout(this).apply {  // val=只读变量；apply=作用域函数；创建次操作行横向布局
            orientation = LinearLayout.HORIZONTAL  // 设置行方向为横向
            setPadding(0, dp(3), 0, 0)  // 设置顶部内边距 3dp
        }  // 结束 apply lambda
        secondaryRow.addView(  // 向次行添加按钮
            createOverlayButton("映射", { showMappingView() }, weight = 1f, textSize = 11f)  // 创建"映射"按钮，点击打开映射列表
        )  // 结束 addView
        secondaryRow.addView(  // 向次行添加按钮
            createOverlayButton("收起", { showCollapsedView() }, weight = 1f, textSize = 11f)  // 创建"收起"按钮，点击收起悬浮窗
        )  // 结束 addView
        secondaryRow.addView(  // 向次行添加按钮
            createOverlayButton(  // 调用工厂方法创建按钮
                label = "关闭",  // 按钮文本
                onClick = { stopSelf() },  // 点击回调 lambda：停止前台服务
                weight = 1f,  // 均分权重 1
                normalColor = COLOR_BTN_DANGER,  // 正常态背景色（红色）
                pressedColor = COLOR_BTN_DANGER_PRESSED,  // 按下态背景色（深红）
                textSize = 11f  // 字号 11sp
            )  // 结束 createOverlayButton 调用
        )  // 结束 addView
        container.addView(secondaryRow)  // 把次操作行加入容器

        // 快捷键提示
        hintText = TextView(this).apply {  // apply=作用域函数；创建快捷键提示文本控件并赋值给成员字段
            text = "LB+方向键/A/B/X/Y/L3/R3 切层\nLB+HOME 清全部\n组合键: A+RB=选怪 D-Pad+L3=栏5-8 D-Pad+R3=栏9/0/-/="  // 设置提示文本（\n 为换行）
            setTextColor(0xFF8A8A8A.toInt())  // 设置灰色文字
            textSize = 9f  // 设置字号 9sp
            setPadding(0, dp(5), 0, 0)  // 设置顶部内边距 5dp
        }  // 结束 apply lambda
        container.addView(hintText)  // 把提示文本加入容器

        // 添加到常驻容器（frame 已有拖动/点击监听）
        // 横屏等屏幕高度不足时面板会超高，用可滚动容器包裹，保证底部按钮（映射/收起/关闭）可达
        // 动画作用于整个面板（含圆角背景的滚动容器），避免"背景先弹出、内容后淡入"造成闪屏
        val panel = wrapPanelInScroll(container)  // val=只读变量；把面板内容包裹进可滚动容器
        frame.addView(panel)  // 把面板加入常驻容器
        // 收起→展开：淡入缩放动画（作用于面板整体）
        animateOverlayIn(panel)  // 对面板执行入场动画
        isExpanded = true  // 标记展开状态

        // 刷新当前状态（展开后显示最新状态）
        if (mapper != null) {  // if=条件判断；映射器非空时刷新
            updateLayerText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；刷新操作层堆栈文本
            updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；刷新层按钮激活色
        }  // 结束 if 分支
        // 同步"暂停/恢复捕获"按钮文本与状态色（与实际捕获状态一致）
        updateCaptureButtonState()  // 更新捕获按钮文本与颜色
    }  // 结束 showExpandedView 函数

    /**
     * 显示按键映射列表视图
     *
     * 展示当前激活层（或公共层）的所有按键映射，格式: X->Space
     * 点击任意区域返回展开视图。
     */
    private fun showMappingView(animate: Boolean = true) {  // private=私有；fun=函数声明；默认参数 animate=true；显示按键映射列表视图
        val frame = overlayView as? FrameLayout ?: return  // val=只读变量；as?=安全转换；?:=空值合并，容器为空则返回
        frame.removeAllViews()  // 清空容器内所有子视图
        isMappingView = true  // 标记进入映射列表视图
        mappingViewItems.clear()  // 清空映射项引用表
        mappingTitleView = null  // 重置标题视图引用
        mappingItemsLayout = null  // 重置映射项容器引用

        val activeLayers = mapper?.getActiveLayers() ?: emptyList()  // val=只读变量；?.=安全调用；?:=空值合并；获取当前激活层列表
        val layerName = if (activeLayers.isEmpty()) "公共层" else activeLayers.last()  // val=只读变量；if 表达式：无激活层显示"公共层"，否则取最上层

        // 内容面板
        val content = LinearLayout(this).apply {  // val=只读变量；apply=作用域函数；创建映射列表内容容器
            orientation = LinearLayout.VERTICAL  // 设置容器方向为纵向
            // 圆角深色面板背景由外层滚动容器统一提供（见 wrapPanelInScroll），此处保持透明
            setPadding(dp(12), dp(10), dp(12), dp(10))  // 设置内边距（dp 转像素）
        }  // 结束 apply lambda

        // 标题（第一行层信息）——切层时仅高亮此行为反馈，不再整页动效
        val title = TextView(this).apply {  // val=只读变量；apply=作用域函数；创建标题文本控件
            text = "映射 - $layerName"  // 字符串模板"$var"；标题显示当前层名
            textSize = 13f  // 设置字号 13sp
            setTextColor(0xFFCCCCCC.toInt())  // 设置浅灰色文字
            setPadding(0, 0, 0, dp(4))  // 设置底部内边距 4dp
        }  // 结束 apply lambda
        content.addView(title)  // 把标题加入内容容器
        mappingTitleView = title  // 保存标题引用供切层时高亮

        // 映射项容器（切层时只重建这一块，不重建整个窗口）
        val itemsLayout = LinearLayout(this).apply {  // val=只读变量；apply=作用域函数；创建映射项容器
            orientation = LinearLayout.VERTICAL  // 设置容器方向为纵向
        }  // 结束 apply lambda
        content.addView(itemsLayout)  // 把映射项容器加入内容容器
        mappingItemsLayout = itemsLayout  // 保存容器引用供切层时原地刷新
        rebuildMappingItems(itemsLayout)  // 首次构建映射项列表

        // 提示文本
        content.addView(TextView(this).apply {  // 添加提示文本控件；apply=作用域函数
            text = "长按任意处返回"  // 设置提示文本
            textSize = 9f  // 设置字号 9sp
            setTextColor(0xFF666666.toInt())  // 设置深灰色文字
            setPadding(0, dp(6), 0, 0)  // 设置顶部内边距 6dp
            gravity = android.view.Gravity.CENTER  // 文本居中显示
        })  // 结束 apply lambda

        // 单击任意处收起悬浮窗
        content.setOnClickListener {  // 设置点击监听（lambda）
            isMappingView = false  // 标记退出映射列表视图
            showCollapsedView()  // 收起悬浮窗（显示胶囊）
        }  // 结束点击监听 lambda

        // 两列布局：固定面板宽度（约屏幕 72%），避免把悬浮窗撑得过宽
        val panelWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()  // val=只读变量；按屏幕宽度 72% 计算面板宽度
        val panel = wrapPanelInScroll(content).apply {  // val=只读变量；apply=作用域函数；包裹可滚动容器后设置面板宽度
            (layoutParams as FrameLayout.LayoutParams).width = panelWidth  // as=类型转换；把容器布局参数宽设为面板宽度
        }  // 结束 apply lambda
        // 内容宽度填满面板：两列映射项均分宽度，消除"右侧大片空白"
        content.layoutParams = FrameLayout.LayoutParams(  // 重置内容布局参数
            FrameLayout.LayoutParams.MATCH_PARENT,  // 宽度填满父容器
            FrameLayout.LayoutParams.WRAP_CONTENT  // 高度自适应内容
        )  // 结束 LayoutParams 构造
        frame.addView(panel)  // 把面板加入常驻容器

        if (animate) {  // if=条件判断；需要动画时执行
            // 动画作用于面板整体（含背景），避免背景先弹出、内容后淡入造成闪屏
            animateOverlayIn(panel)  // 对面板执行入场动画
        }  // 结束 if 分支
    }  // 结束 showMappingView 函数

    /**
     * 重建映射项列表（标题下方的内容）
     *
     * 用于映射列表页初次构建与切层时原地刷新。只替换映射项容器内的子视图，
     * 不触碰窗口/面板本身，避免 WRAP_CONTENT 窗口缩放与白色边框弹出的整屏闪屏。
     *
     * @param container 映射项容器（LinearLayout.VERTICAL）
     */
    private fun rebuildMappingItems(container: LinearLayout) {  // private=私有；fun=函数声明；重建映射项列表
        container.removeAllViews()  // 清空容器内旧的映射项
        mappingViewItems.clear()  // 清空映射项引用表

        val profile = steamInput?.profile ?: return  // val=只读变量；?.=安全调用；?:=空值合并，配置为空则返回
        val activeLayers = mapper?.getActiveLayers() ?: emptyList()  // val=只读变量；?.=安全调用；?:=空值合并；获取激活层列表
        val targetLayer = if (activeLayers.isNotEmpty()) {  // val=只读变量；if 表达式：有激活层时取最上层
            profile.findLayer(activeLayers.last())  // 按最上层内部名查找目标层
        } else {  // 否则（无激活层）
            profile.commonLayer  // 使用公共层
        } ?: return  // ?:=空值合并；目标层为空则返回

        // 公共层兜底 + 当前层映射（键冲突时当前层覆盖公共层）
        val mappings = mutableMapOf<ControllerButton, KeyMapping>()  // val=只读变量；泛型<T>；创建按键到映射的可变 Map
        profile.commonLayer.buttonMappings.forEach { (btn, mapping) ->  // forEach=lambda 遍历；解构声明 (btn, mapping)；先加入公共层映射
            mappings[btn] = mapping  // 公共层映射写入合并表
        }  // 结束 forEach lambda
        targetLayer.buttonMappings.forEach { (btn, mapping) ->  // forEach=lambda 遍历；解构声明 (btn, mapping)；再加入当前层映射
            mappings[btn] = mapping  // 当前层映射覆盖公共层（键冲突时当前层优先）
        }  // 结束 forEach lambda

        if (mappings.isEmpty()) {  // if=条件判断；无任何映射时显示占位文本
            container.addView(TextView(this).apply {  // 添加"无映射"提示文本；apply=作用域函数
                text = "无映射"  // 设置占位文本
                textSize = 11f  // 设置字号 11sp
                setTextColor(0xFF888888.toInt())  // 设置灰色文字
            })  // 结束 apply lambda
            return  // 直接返回
        }  // 结束 if 分支

        val sortedMappings = ControllerButton.entries  // val=只读变量；取手柄按键枚举的全部枚举项
            .filter { it in mappings }  // lambda 过滤：只保留存在映射的按键
            .map { it to mappings[it]!! }  // lambda 映射；to=键值对；!!=非空断言（已确认存在）；转成 (按键, 映射) 对列表

        // 每行两个按键，两列显示
        sortedMappings.chunked(2).forEach { rowMappings ->  // chunked(2)=每 2 个一组；forEach=lambda 遍历每行
            val rowView = LinearLayout(this).apply {  // val=只读变量；apply=作用域函数；创建每行横向布局
                orientation = LinearLayout.HORIZONTAL  // 设置行方向为横向
                // 行宽填满面板：两列映射项均分宽度，消除面板右侧大片空白
                layoutParams = LinearLayout.LayoutParams(  // 设置行布局参数
                    LinearLayout.LayoutParams.MATCH_PARENT,  // 宽度填满父容器
                    LinearLayout.LayoutParams.WRAP_CONTENT  // 高度自适应内容
                )  // 结束 LayoutParams 构造
            }  // 结束 apply lambda
            rowMappings.forEachIndexed { index, (btn, mapping) ->  // forEachIndexed=lambda 遍历（带下标）；解构声明 (btn, mapping)
                val item = TextView(this).apply {  // val=只读变量；apply=作用域函数；创建单个映射项文本
                    // 使用与按键映射设置页一致的显示名（LB/RB/L2/R2 等），而非枚举原名
                    text = "${LayerEditActivity.buttonDisplayName(btn)} -> ${mapping.describe()}"  // 字符串模板"${}"; 显示"按键 -> 目标映射"
                    textSize = 11f  // 设置字号 11sp
                    setTextColor(0xFFFFFFFF.toInt())  // 设置白色文字
                    setPadding(dp(8), dp(4), dp(8), dp(4))  // 设置内边距
                    background = roundedDrawable(COLOR_MAPPING_ITEM, dp(6))  // 设置圆角背景（映射项底色）
                    isSingleLine = true  // 单行显示，超长省略
                    ellipsize = android.text.TextUtils.TruncateAt.END  // 超长时末尾省略号
                }  // 结束 apply lambda
                // 保存引用，手柄按键按下时高亮对应项
                mappingViewItems[btn] = item  // 以按键为键保存文本控件引用
                val params = LinearLayout.LayoutParams(  // val=只读变量；创建布局参数
                    0,   // 宽度 0 + weight=1 → 两列均分  // 构造参数：宽度 0，配合 weight=1f 均分两列
                    LinearLayout.LayoutParams.WRAP_CONTENT,  // 高度自适应内容
                    1f  // weight=1，两列均分宽度
                )  // 结束 LayoutParams 构造
                params.setMargins(0, dp(1), if (index == 0) dp(2) else 0, dp(1))  // if=条件判断；设置边距（首列右侧留 2dp 间距）
                rowView.addView(item, params)  // 把映射项按参数加入行
            }  // 结束 forEachIndexed lambda
            container.addView(rowView)  // 把整行加入映射项容器
        }  // 结束 forEach lambda
    }  // 结束 rebuildMappingItems 函数

    /**
     * 切层反馈：高亮第一行层信息背景 1 秒后恢复（替代原来的整页淡入/缩放动效）
     */
    private fun highlightLayerTitle(title: TextView) {  // private=私有；fun=函数声明；切层时高亮标题栏 1 秒
        val radius = dp(6)  // val=只读变量；圆角半径 6dp
        // 高亮态：半透明蓝底 + 轻微内边距，让色块贴合文字更清晰
        title.background = roundedDrawable(0x802196F3.toInt(), radius)  // 设置半透明蓝色圆角背景
        title.setPadding(dp(6), dp(2), dp(6), dp(2))  // 加大内边距让色块贴合文字
        title.postDelayed({  // postDelayed=延迟投递；lambda 在 1 秒后恢复原样
            // 恢复为透明背景与原内边距（0,0,0,4dp）
            title.background = roundedDrawable(0x00000000, radius)  // 恢复为透明圆角背景
            title.setPadding(0, 0, 0, dp(4))  // 恢复原内边距
        }, 1000L)  // 延迟 1000 毫秒执行
    }  // 结束 highlightLayerTitle 函数

    /**
     * 获取当前屏幕高度（像素）
     *
     * API 30+ 使用 [WindowManager.currentWindowMetrics]（返回完整屏幕含系统栏区域，
     * 与悬浮窗 FLAG_LAYOUT_IN_SCREEN 的布局范围一致）；低版本回退到 DisplayMetrics。
     *
     * @return 屏幕高度（像素）
     */
    private fun screenHeightPx(): Int {  // private=私有；fun=函数声明；返回屏幕高度（像素）
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // return=返回；if=条件判断；API 30+ 分支
            windowManager?.currentWindowMetrics?.bounds?.height()  // ?.=安全调用链；取当前窗口指标边界高度
                ?: resources.displayMetrics.heightPixels  // ?:=空值合并；取不到时回退 DisplayMetrics 高度
        } else {  // 否则（低版本）
            @Suppress("DEPRECATION")  // 注解：抑制弃用警告
            resources.displayMetrics.heightPixels  // 使用 DisplayMetrics 获取屏幕高度
        }  // 结束 if 表达式
    }  // 结束 screenHeightPx 函数

    /**
     * 获取底部系统栏高度（导航条/手势条，像素）
     *
     * 悬浮窗使用 FLAG_LAYOUT_IN_SCREEN 会布局到全屏（含系统栏之下），
     * 面板底部若延伸进系统栏区域会被导航条遮挡，表现为"下面两个角是直角
     * （被系统栏裁平）+ 内容滚动不到底部"。计算面板最大高度时需扣除该值。
     */
    private fun bottomSystemInsetPx(): Int {  // private=私有；fun=函数声明；返回底部系统栏高度（像素）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // if=条件判断；API 30+ 分支
            // 显示级 inset（不依赖视图）：currentWindowMetrics.windowInsets
            // 直接给当前显示的系统栏 insets，比 view.rootWindowInsets 可靠
            val insets = windowManager?.currentWindowMetrics?.windowInsets  // val=只读变量；?.=安全调用链；获取窗口系统栏 inset
            val bottom = insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0  // val=只读变量；?.=安全调用；?:=空值合并；取导航条底部高度
            if (bottom > 0) return bottom  // if=条件判断；高度有效则直接返回
        }  // 结束 if 分支
        // 兜底：部分设备悬浮窗(FLAG_LAYOUT_IN_SCREEN)拿不到窗口 inset
        // （rootWindowInsets/currentWindowMetrics.windowInsets 均为 0），
        // 但面板仍会布局到系统栏之下被遮挡。按常见导航条/手势条高度估算。
        return (resources.displayMetrics.density * 16f).toInt()  // return=返回；按密度估算常见导航条高度
    }  // 结束 bottomSystemInsetPx 函数

    /**
     * 将面板内容包裹进可滚动容器并限制最大高度
     *
     * 横屏（或小屏）时屏幕可用高度较小，展开面板（层按钮 + 控制按钮 + 快捷键提示）
     * 可能超出屏幕底部，导致底部的"映射/收起/关闭"按钮不可见。用 ScrollView 包裹，
     * 内容超高时限制高度可滚动，保证全部按钮可达。
     *
     * 圆角处理：面板圆角背景由 ScrollView 提供并配合 [android.view.View.setClipToOutline]，
     * 内容超高滚动时内容在圆角边界内被裁剪，四角始终保持圆角（不会因 ScrollView 矩形
     * 边界把面板裁成方形）。
     *
     * 高度处理：初始按上限布局防止撑出屏幕，布局后若内容未超高（如竖屏）则恢复贴合
     * 内容高度，避免面板下方出现空白。
     *
     * @param content 面板内容视图（背景应为透明，圆角由本方法统一提供）
     * @param maxHeight 期望的最大高度（像素）；null 时按当前屏幕高度自动计算
     * @return 包裹后的 ScrollView
     */
    // private=私有；fun=函数声明；默认参数 maxHeight=null；把面板包裹进可滚动容器
    private fun wrapPanelInScroll(content: View, maxHeight: Int? = null): ScrollView {
        val topOffset = overlayParams?.y ?: 0  // val=只读变量；?.=安全调用；?:=空值合并；取悬浮窗顶部偏移
        // 可用高度须扣除底部系统栏（导航条/手势条）高度，否则面板底部会被系统栏遮挡，
        // 表现为"下面两个角是直角 + 内容滚动不到底部"
        val bottomInset = bottomSystemInsetPx()  // val=只读变量；取底部系统栏高度
        val limit = maxHeight  // val=只读变量；先取调用方指定的最大高度
            ?: (screenHeightPx() - topOffset - bottomInset - dp(12)).coerceAtLeast(dp(100))  // ?:=空值合并；未指定时按屏幕高度计算并设下限 100dp
        Log.i(  // 打印调试日志
            TAG,  // 日志标签
            "wrapPanelInScroll: screen=${screenHeightPx()} y=$topOffset navInset=$bottomInset limit=$limit"  // 字符串模板"${}"; 记录高度计算参数
        )  // 结束 Log.i 调用
        return ScrollView(this).apply {  // return=返回；apply=作用域函数；创建滚动容器并配置
            // 隐藏滚动条：避免右侧竖条破坏悬浮窗圆角外观，滚动仍可用（直接滑动）
            isVerticalScrollBarEnabled = false  // 隐藏纵向滚动条
            isHorizontalScrollBarEnabled = false  // 隐藏横向滚动条
            // 圆角面板背景（固定于容器边界，不随内容滚动）+ 按轮廓裁剪内容保持圆角
            background = roundedDrawable(COLOR_PANEL, dp(14))  // 设置圆角深色面板背景
            clipToOutline = true  // 内容按背景轮廓裁剪（保持四角圆角）
            addView(content, FrameLayout.LayoutParams(  // 把内容加入滚动容器
                FrameLayout.LayoutParams.WRAP_CONTENT,  // 宽度自适应
                FrameLayout.LayoutParams.WRAP_CONTENT  // 高度自适应
            ))  // 结束 addView
            // 面板相对常驻边框内缩 2dp：面板填充不再覆盖边框，
            // 避免"面板圆角凸出边框圆角"（两圆角不再嵌套重叠）
            val baseParams = FrameLayout.LayoutParams(  // val=只读变量；创建滚动容器布局参数
                FrameLayout.LayoutParams.WRAP_CONTENT,  // 宽度自适应
                limit  // 高度受限于计算值
            )  // 结束 LayoutParams 构造
            baseParams.setMargins(dp(2), dp(2), dp(2), dp(2))  // 四周内缩 2dp 边距
            layoutParams = baseParams  // 应用布局参数
            // 布局后按实际内容高度调整：未超高回贴 WRAP_CONTENT（避免竖屏下方空白），
            // 超高保持限高可滚动。多帧校验：窗口首轮布局未完成时内容高度可能尚未测量
            // （height=0）或被低估，若误判为"未超高"会切成 WRAP_CONTENT，面板被内容
            // 撑出屏幕导致底部圆角不可见（直角）且滚动不到底。
            post(object : Runnable {  // post=投递到主线程队列；object : Runnable=匿名对象实现 Runnable 接口（线程回调）
                override fun run() {  // override=覆写父类方法；Runnable 的回调方法：布局完成后在主线程执行
                    val h = content.height  // val=只读变量；读取内容实际测量高度
                    if (h <= 0) {  // if=条件判断；内容尚未测量（高度为 0）时
                        post(this)  // 内容尚未测量，下一帧再判  // 递归投递自身，下一帧重试
                        return  // 直接返回，等待下一帧
                    }  // 结束 if 分支
                    Log.i(TAG, "wrapPanelInScroll: content.height=$h limit=$limit")  // 字符串模板"$var"；打印内容高度与限制高度
                    val width = (layoutParams as? FrameLayout.LayoutParams)  // val=只读变量；as?=安全转换（取容器布局参数，失败得 null）
                        ?.width ?: FrameLayout.LayoutParams.WRAP_CONTENT  // ?.=安全调用；?:=空值合并；取宽度，取不到则自适应
                    if (h <= limit) {  // if=条件判断；内容未超高时
                        val lp = FrameLayout.LayoutParams(  // val=只读变量；创建贴合内容的布局参数
                            width,  // 沿用原宽度
                            FrameLayout.LayoutParams.WRAP_CONTENT  // 高度改为自适应内容
                        )  // 结束 LayoutParams 构造
                        lp.setMargins(dp(2), dp(2), dp(2), dp(2))  // 保留四周 2dp 边距
                        layoutParams = lp  // 应用新布局参数（回贴内容高度）
                    } else {  // 否则（内容超高）
                        // 内容超高（可滚动）：补足底部内边距，让最后一行能滚出圆角区
                        val need = dp(16)  // val=只读变量；需补足的底部内边距 16dp
                        if (content.paddingBottom < need) {  // if=条件判断；现有底部内边距不足时
                            content.setPadding(  // 重新设置内容四边内边距
                                content.paddingLeft,  // 保持左边距
                                content.paddingTop,  // 保持上边距
                                content.paddingRight,  // 保持右边距
                                need  // 底部补足 16dp
                            )  // 结束 setPadding 调用
                        }  // 结束 if 分支
                    }  // 结束 if/else 分支
                }  // 结束 run 函数
            })  // 结束匿名 Runnable 对象与 post 调用
        }  // 结束 apply lambda（ScrollView 配置）
    }  // 结束 wrapPanelInScroll 函数

    /**
     * 为悬浮窗常驻容器设置拖动和点击监听
     *
     * - 拖动: 移动悬浮窗位置（收起和展开状态都支持）
     * - 点击: 收起状态下未移动的点击触发展开；展开状态下不拦截按钮点击
     *
     * @param view 悬浮窗常驻容器（FrameLayout）
     */
    private fun setupOverlayTouchListener(view: View) {  // private=私有；fun=函数声明；为悬浮窗容器设置拖动与点击监听
        var initialX = 0  // var=可变变量；记录按下时悬浮窗初始 X
        var initialY = 0  // var=可变变量；记录按下时悬浮窗初始 Y
        var initialTouchX = 0f  // var=可变变量；记录按下时手指原始 X
        var initialTouchY = 0f  // var=可变变量；记录按下时手指原始 Y
        var hasMoved = false  // var=可变变量；标记本次触摸是否发生过拖动

        view.setOnTouchListener { _, event ->  // 设置触摸监听（lambda）；忽略 view 参数，接收 MotionEvent
            // 展开/映射面板状态：不拦截触摸，交由内部 ScrollView 处理内容滚动与按钮点击
            // （横屏等屏幕高度不足时面板可滚动，底部的"映射/收起/关闭"按钮才能触达）
            if (isExpanded || isMappingView) return@setOnTouchListener false  // 展开/映射态不拦截触摸，返回 false 交内部处理
            when (event.action) {  // when=分支表达式；按触摸事件类型分发
                MotionEvent.ACTION_DOWN -> {  // 手指按下分支
                    initialX = overlayParams?.x ?: 0  // ?.=安全调用；?:=空值合并；记录初始 X（取不到为 0）
                    initialY = overlayParams?.y ?: 0  // ?.=安全调用；?:=空值合并；记录初始 Y（取不到为 0）
                    initialTouchX = event.rawX  // 记录手指原始 X
                    initialTouchY = event.rawY  // 记录手指原始 Y
                    hasMoved = false  // 重置拖动标记
                }  // 结束 ACTION_DOWN 分支
                MotionEvent.ACTION_MOVE -> {  // 手指移动分支
                    val dx = event.rawX - initialTouchX  // val=只读变量；计算横向位移
                    val dy = event.rawY - initialTouchY  // val=只读变量；计算纵向位移
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {  // if=条件判断；位移超过 5px 视为拖动
                        hasMoved = true  // 标记已拖动
                        overlayParams?.let { params ->  // ?.=安全调用；let=作用域函数（lambda 内 it/显式参数）；参数非空时执行拖动
                            params.x = initialX + dx.toInt()  // 更新悬浮窗 X 位置
                            params.y = initialY + dy.toInt()  // 更新悬浮窗 Y 位置
                            windowManager?.updateViewLayout(view, params)  // ?.=安全调用；用新参数刷新窗口布局
                        }  // 结束 let lambda
                    }  // 结束 if 分支
                }  // 结束 ACTION_MOVE 分支
                MotionEvent.ACTION_UP -> {  // 手指抬起分支
                    // 收起状态下，未移动的点击 = 展开
                    if (!hasMoved && !isExpanded) {  // if=条件判断；未拖动且处于收起态时视为点击
                        showExpandedView()  // 展开悬浮窗
                        return@setOnTouchListener true  // 消费事件，阻止其他处理
                    }  // 结束 if 分支
                }  // 结束 ACTION_UP 分支
            }  // 结束 when 分支
            // 移动了则消费事件（阻止按钮误触）；未移动则返回 false 让按钮处理
            hasMoved  // lambda 返回值：已移动则 true（消费），否则 false
        }  // 结束 setOnTouchListener lambda
    }  // 结束 setupOverlayTouchListener 函数

    private fun createLayerButton(name: String, display: String): Button {  // private=私有；fun=函数声明；创建操作层按钮
        val btn = Button(this).apply {  // val=只读变量；apply=作用域函数；创建按钮并配置
            text = display  // 设置按钮文本为层显示名
            textSize = 12f  // 设置字号 12sp
            setTextColor(0xFFFFFFFF.toInt())  // 设置白色文字
            // 圆角背景（保存引用供激活时变色）
            background = roundedDrawable(COLOR_LAYER_NORMAL, dp(8))  // 设置深灰圆角背景（未激活态）
            setPadding(0, 0, 0, 0)  // 清空内边距
            // 按住激活层，松开停用层（松开即回到公共层）
            // 短按（点击）→ 跳转到该操作层设置页面
            setOnTouchListener { _, event ->  // 设置触摸监听（lambda）；控制按住激活/松开停用
                when (event.action) {  // when=分支表达式；按触摸事件类型分发
                    MotionEvent.ACTION_DOWN -> {  // 按下分支
                        mapper?.activateLayer(name)  // ?.=安全调用；按下时激活该操作层
                    }  // 结束 ACTION_DOWN 分支
                    MotionEvent.ACTION_UP -> {  // 抬起分支
                        mapper?.deactivateLayer(name)  // ?.=安全调用；松开时停用该操作层
                        // 短按视为点击，跳转到对应的操作层设置页
                        if (event.eventTime - event.downTime < LAYER_BUTTON_TAP_MS) {  // if=条件判断；按住时间短于阈值视为短按
                            openLayerSettings(name)  // 打开该层设置页
                        }  // 结束 if 分支
                    }  // 结束 ACTION_UP 分支
                    MotionEvent.ACTION_CANCEL -> {  // 取消分支
                        mapper?.deactivateLayer(name)  // ?.=安全调用；手势取消时也停用该层
                    }  // 结束 ACTION_CANCEL 分支
                }  // 结束 when 分支
                true  // 消费事件，防止触发系统默认行为
            }  // 结束 setOnTouchListener lambda
            // 两列均分宽度，高度统一
            val params = LinearLayout.LayoutParams(0, dp(36), 1f)  // val=只读变量；宽度 0 + weight=1 均分，高度 36dp
            params.setMargins(dp(2), dp(2), dp(2), dp(2))  // 四周 2dp 边距
            layoutParams = params  // 应用布局参数
        }  // 结束 apply lambda
        layerButtons[name] = btn  // 以层内部名为键保存按钮引用
        return btn  // 返回创建的按钮
    }  // 结束 createLayerButton 函数

    /**
     * 打开对应操作层的设置页面（LayerEditActivity）
     *
     * 通过 EXTRA_LAYER_NAME 指定初始选中的操作层。
     * 进入编辑页时悬浮窗缩到最小（收起胶囊），避免展开面板遮挡编辑页；
     * LayerEditActivity 不再暂停悬浮窗，返回后悬浮窗保持收起状态。
     */
    private fun openLayerSettings(layerName: String) {  // private=私有；fun=函数声明；打开指定操作层设置页
        try {  // 异常捕获块：避免启动 Activity 失败导致崩溃
            // 先收起悬浮窗（展开面板 → 收起胶囊），避免覆盖编辑页面
            showCollapsedView()  // 收起悬浮窗
            startActivity(Intent(this, LayerEditActivity::class.java).apply {  // apply=作用域函数；创建跳转 Intent 并配置
                putExtra(LayerEditActivity.EXTRA_LAYER_NAME, layerName)  // 传入初始选中的操作层名
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // 新任务栈启动（服务中启动 Activity 必需）
            })  // 结束 apply lambda
            Log.i(TAG, "Open layer settings: $layerName")  // 字符串模板"$var"；打印打开日志
        } catch (e: Exception) {  // catch=捕获异常；处理启动失败
            Log.e(TAG, "Failed to open layer settings: $layerName", e)  // 打印错误日志
        }  // 结束 catch 块
    }  // 结束 openLayerSettings 函数

    /**
     * 创建圆角功能按钮（带按下态反馈）
     *
     * @param label 按钮文本
     * @param onClick 点击回调
     * @param weight 水平均分权重（>0 时占满并均分；0 为自适应宽度）
     * @param normalColor 正常态背景色
     * @param pressedColor 按下态背景色
     * @param textSize 文本大小（sp）
     */
    private fun createOverlayButton(  // private=私有；fun=函数声明；创建圆角功能按钮
        label: String,  // 参数：按钮文本
        onClick: () -> Unit,  // 参数：点击回调 lambda（无参、无返回值）
        weight: Float = 0f,  // 参数：默认参数 weight=0f；横向均分权重
        normalColor: Int = COLOR_BTN_NORMAL,  // 参数：默认参数；正常态背景色
        pressedColor: Int = COLOR_BTN_PRESSED,  // 参数：默认参数；按下态背景色
        textSize: Float = 11f  // 参数：默认参数 textSize=11f；字号
    ): Button {  // 返回类型：Button
        return Button(this).apply {  // return=返回；apply=作用域函数；创建按钮并配置
            text = label  // 设置按钮文本
            this.textSize = textSize  // 设置字号（this 指代按钮对象）
            setTextColor(0xFFFFFFFF.toInt())  // 设置白色文字
            isAllCaps = false  // 关闭全大写显示
            background = createStateListBackground(normalColor, pressedColor, 8f)  // 设置带按下态反馈的圆角背景
            setPadding(0, 0, 0, 0)  // 清空内边距
            setOnClickListener { onClick() }  // 设置点击监听（lambda）；点击时调用回调
            val params = LinearLayout.LayoutParams(  // val=只读变量；创建布局参数
                if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,  // if=条件判断；有权重则宽为 0 均分，否则自适应
                dp(34)  // 统一高度 34dp
            )  // 结束 LayoutParams 构造
            if (weight > 0f) params.weight = weight  // if=条件判断；有权重时设置均分权重
            params.setMargins(dp(2), dp(2), dp(2), dp(2))  // 四周 2dp 边距
            layoutParams = params  // 应用布局参数
        }  // 结束 apply lambda
    }  // 结束 createOverlayButton 函数

    // ===== 悬浮窗样式辅助 =====

    /** dp → px（接受 Int/Float） */
    private fun dp(value: Number): Int =  // private=私有；fun=函数声明；表达式体函数（= 直接返回结果）；dp 转像素
        (value.toFloat() * resources.displayMetrics.density).toInt()  // 数值乘密度转像素并取整

    /**
     * 创建圆角矩形背景
     *
     * @param color 填充色
     * @param cornerRadius 圆角半径（px）
     * @param strokeColor 边框色（null=无边框）
     * @param strokeWidth 边框宽度（px）
     */
    private fun roundedDrawable(  // private=私有；fun=函数声明；创建圆角矩形背景
        color: Int,  // 参数：填充色
        cornerRadius: Int,  // 参数：圆角半径（px）
        strokeColor: Int? = null,  // 参数：默认参数；可空边框色（null=无边框）
        strokeWidth: Int = 0  // 参数：默认参数；边框宽度（px）
    ): GradientDrawable {  // 返回类型：GradientDrawable（形状可绘制对象）
        return GradientDrawable().apply {  // return=返回；apply=作用域函数；创建渐变绘制对象并配置
            shape = GradientDrawable.RECTANGLE  // 形状为矩形
            setColor(color)  // 设置填充色
            this.cornerRadius = cornerRadius.toFloat()  // 设置圆角半径（this 指代绘制对象）
            if (strokeColor != null) {  // if=条件判断；边框色非空时绘制边框
                setStroke(strokeWidth, strokeColor)  // 设置边框宽度与颜色
            }  // 结束 if 分支
        }  // 结束 apply lambda
    }  // 结束 roundedDrawable 函数

    /**
     * 创建带按下态反馈的圆角按钮背景
     *
     * @param normalColor 正常态颜色
     * @param pressedColor 按下态颜色
     * @param cornerRadiusDp 圆角半径（dp）
     */
    private fun createStateListBackground(  // private=私有；fun=函数声明；创建带按下态反馈的圆角按钮背景
        normalColor: Int,  // 参数：正常态颜色
        pressedColor: Int,  // 参数：按下态颜色
        cornerRadiusDp: Float  // 参数：圆角半径（dp）
    ): StateListDrawable {  // 返回类型：StateListDrawable（状态列表绘制对象）
        return StateListDrawable().apply {  // return=返回；apply=作用域函数；创建状态列表绘制对象并配置
            addState(  // 添加状态分支
                intArrayOf(android.R.attr.state_pressed),  // 状态条件：按下态
                roundedDrawable(pressedColor, dp(cornerRadiusDp))  // 按下态使用按下色圆角背景
            )  // 结束 addState 调用
            addState(  // 添加状态分支
                intArrayOf(),  // 空状态条件：默认态（其余情况）
                roundedDrawable(normalColor, dp(cornerRadiusDp))  // 默认态使用正常色圆角背景
            )  // 结束 addState 调用
        }  // 结束 apply lambda
    }  // 结束 createStateListBackground 函数

    private fun updateStatus(text: String) {  // private=私有；fun=函数声明；更新悬浮窗状态文本
        // 缓存最新状态，showExpandedView() 重建 statusText 时使用
        currentStatus = text  // 把最新状态文本缓存到成员字段
        statusText?.post { statusText?.text = text }  // ?.=安全调用；post=投递到主线程；刷新状态文本显示
        updateNotification(text)  // 同步更新通知栏文案
    }  // 结束 updateStatus 函数

    private fun updateLayerText(layers: List<String>) {  // private=私有；fun=函数声明；更新操作层堆栈文本
        layerText?.post {  // ?.=安全调用；post=投递到主线程
            // ?.=安全调用；if=条件判断；字符串模板"${}"；无激活层显示提示，否则用" > "连接层名
            layerText?.text = if (layers.isEmpty()) "无激活层" else "层: ${layers.joinToString(" > ")}"
        }  // 结束 post lambda
    }  // 结束 updateLayerText 函数

    private fun updateLayerButtonColors(activeLayers: List<String>) {  // private=私有；fun=函数声明；按激活层刷新层按钮颜色
        // 按钮 key 与 activeLayers 都是内部层名，直接比对
        val activeSet = activeLayers.toSet()  // val=只读变量；把激活层列表转成集合以便快速判断
        layerButtons.forEach { (name, btn) ->  // forEach=lambda 遍历；解构声明 (name, btn) 取出层名与按钮
            btn.post {  // post=投递到主线程
                // 通过 GradientDrawable 设置圆角背景颜色（保留圆角）
                val bg = btn.background as? GradientDrawable  // val=只读变量；as?=安全转换；把背景转成渐变绘制对象
                if (name in activeSet) {  // if=条件判断；该层处于激活状态时
                    bg?.setColor(COLOR_LAYER_ACTIVE)   // 绿色=激活  // ?.=安全调用；激活层背景设为绿色
                    btn.setTextColor(0xFFFFFFFF.toInt())  // 文字设为白色
                } else {  // 否则（未激活）
                    bg?.setColor(COLOR_LAYER_NORMAL)   // 深灰=未激活  // ?.=安全调用；未激活层背景恢复深灰
                    btn.setTextColor(0xFFFFFFFF.toInt())  // 文字设为白色
                }  // 结束 if/else 分支
            }  // 结束 post lambda
        }  // 结束 forEach lambda
    }  // 结束 updateLayerButtonColors 函数

    /**
     * 更新按键映射列表视图（层切换时自动刷新）
     *
     * 仅在映射列表视图显示时生效。切层时只做两件事：
     * 1. 原地刷新映射项容器（不重建整个窗口，避免 WRAP_CONTENT 缩放闪屏）
     * 2. 高亮第一行层信息背景 1 秒作为切层反馈（替代原来的整页淡入/缩放动效）
     */
    private fun updateMappingView() {  // private=私有；fun=函数声明；层切换时刷新映射列表
        if (!isMappingView) return  // if=条件判断；非映射列表视图时直接返回
        val title = mappingTitleView ?: return  // val=只读变量；?:=空值合并；标题引用为空则返回
        val itemsLayout = mappingItemsLayout ?: return  // val=只读变量；?:=空值合并；映射项容器引用为空则返回

        // 更新第一行层信息文本，并高亮 1s 作为切层反馈
        val activeLayers = mapper?.getActiveLayers() ?: emptyList()  // val=只读变量；?.=安全调用；?:=空值合并；获取激活层列表
        val layerName = if (activeLayers.isEmpty()) "公共层" else activeLayers.last()  // val=只读变量；if 表达式：无激活层显示"公共层"，否则取最上层
        title.text = "映射 - $layerName"  // 字符串模板"$var"；更新标题显示当前层
        highlightLayerTitle(title)  // 高亮标题 1 秒作为切层反馈

        // 原地重建映射项，窗口尺寸不变
        rebuildMappingItems(itemsLayout)  // 重建映射项列表（不重建整个窗口）
    }  // 结束 updateMappingView 函数

    /**
     * 操作集切换后的悬浮窗刷新
     *
     * 切换操作集时其下操作层整体切换，层按钮/映射页都基于新的操作集：
     * - 映射页：重建映射列表（新操作集的层）
     * - 展开面板：重建层按钮与操作集信息
     * - 收起胶囊：刷新层名
     *
     * @param actionSetName 新操作集名称
     */
    private fun onActionSetSwitched(actionSetName: String) {  // private=私有；fun=函数声明；操作集切换后的悬浮窗刷新
        Log.i(TAG, "Action set switched: $actionSetName")  // 字符串模板"$var"；打印切换日志
        mainHandler.post {  // mainHandler=主线程 Handler；post=投递任务到主线程
            if (isOverlayPaused) return@post  // if=条件判断；悬浮窗暂停中则跳过（return@post=带标签返回）
            if (isMappingView) {  // if=条件判断；处于映射列表视图时
                showMappingView()  // 重建映射列表
            } else if (isExpanded) {  // else if=否则如果；处于展开面板时
                showExpandedView()  // 重建展开面板
            } else {  // 否则（收起状态）
                updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；刷新收起胶囊层名
            }  // 结束 if/else 分支
        }  // 结束 post lambda
    }  // 结束 onActionSetSwitched 函数

    /**
     * 切换悬浮窗视图：收起→展开，展开→映射列表，映射列表→展开
     */
    private fun toggleOverlayView() {  // private=私有；fun=函数声明；切换悬浮窗视图
        if (isOverlayPaused) return  // if=条件判断；悬浮窗暂停中则直接返回
        if (isMappingView) {  // if=条件判断；当前是映射列表时
            // 映射列表 → 收起
            showCollapsedView()  // 收起为胶囊
        } else {  // 否则（收起或展开）
            // 收起或展开 → 映射列表
            showMappingView()  // 切换为映射列表
        }  // 结束 if/else 分支
    }  // 结束 toggleOverlayView 函数

    /**
     * 获取悬浮窗层按钮的 (内部名, 显示名) 列表，与 app 内部配置动态同步
     *
     * - 未重命名的层（name 形如 "LayerN"）→ 显示预设中文名（战斗/骑乘/...）
     * - 用户在设置界面重命名后 → 显示自定义层名
     */
    private fun getLayerDisplayNames(): List<Pair<String, String>> {  // private=私有；fun=函数声明；泛型<List<Pair<..>>>；获取 (内部名, 显示名) 列表
        val profile = steamInput?.profile ?: return WoWActionSets.LAYER_NAMES  // val=只读变量；?.=安全调用；?:=空值合并；配置为空时返回预设层名表
        return profile.layers.map { layer ->  // return=返回；lambda 映射：遍历配置里的每个层
            val display = if (DEFAULT_LAYER_NAME_REGEX.matches(layer.name)) {  // val=只读变量；if=条件判断；内部名匹配默认层名格式时
                // ?.=安全调用；?:=空值合并；lambda 查找预设中文名，找不到用内部名
                WoWActionSets.LAYER_NAMES.firstOrNull { it.first == layer.name }?.second ?: layer.name
            } else {  // 否则（用户已重命名）
                layer.name  // 直接使用自定义层名
            }  // 结束 if 表达式
            layer.name to display  // 返回 (内部名, 显示名) 键值对
        }  // 结束 map lambda
    }  // 结束 getLayerDisplayNames 函数

    /**
     * 刷新悬浮窗层按钮名称（配置导入/重置后调用）
     *
     * 正常编辑流程（LayerEditActivity 编辑时悬浮窗已移除、退出后重建）会自动同步新层名；
     * 此方法用于服务运行中配置被导入/重置时的即时刷新。
     */
    private fun refreshLayerNames() {  // private=私有；fun=函数声明；配置变更后刷新悬浮窗层名
        mainHandler.post {  // mainHandler=主线程 Handler；post=投递任务到主线程
            if (isOverlayPaused) return@post  // 设置界面期间不重建  // if=条件判断；悬浮窗暂停中则跳过（return@post=带标签返回）
            if (isExpanded || isMappingView) {  // if=条件判断；处于展开或映射列表视图时
                isMappingView = false  // 先退出映射列表状态
                showExpandedView()  // 重建展开面板（同步新层名）
            } else {  // 否则（收起状态）
                updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())  // ?.=安全调用；?:=空值合并；刷新收起胶囊层名
            }  // 结束 if/else 分支
        }  // 结束 post lambda
    }  // 结束 refreshLayerNames 函数

    // ===== 通知 =====

    private fun createNotification(text: String): Notification {  // private=私有；fun=函数声明；创建前台服务通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {  // if=条件判断；Android 8.0+ 需创建通知渠道
            val channel = NotificationChannel(  // val=只读变量；创建通知渠道对象
                CHANNEL_ID, "手柄控制器", NotificationManager.IMPORTANCE_LOW  // 渠道 ID、名称、低重要性（不响铃）
            )  // 结束 NotificationChannel 构造
            val manager = getSystemService(NotificationManager::class.java)  // val=只读变量；获取通知管理器服务
            manager.createNotificationChannel(channel)  // 向系统注册通知渠道
        }  // 结束 if 分支

        val toggleIntent = Intent(this, ControllerOverlayService::class.java).apply {  // val=只读变量；apply=作用域函数；创建通知按钮的跳转 Intent
            action = ACTION_TOGGLE_OVERLAY  // 指定切换悬浮窗视图的动作
        }  // 结束 apply lambda
        val togglePending = PendingIntent.getService(  // val=只读变量；创建延迟 Intent（服务形式）
            this, 0, toggleIntent,  // 请求码 0，目标 Intent
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE  // or=位或运算；更新现有 PendingIntent 并设为不可变
        )  // 结束 PendingIntent 构造

        return NotificationCompat.Builder(this, CHANNEL_ID)  // return=返回；构建通知（链式调用）
            .setContentTitle("SteamLike手柄控制器")  // 设置通知标题
            .setContentText(text)  // 设置通知正文
            .setSmallIcon(android.R.drawable.ic_media_play)  // 设置小图标
            .setOngoing(true)  // 常驻通知（不可滑动清除）
            .addAction(0, "切换视图", togglePending)  // 添加"切换视图"操作按钮
            .build()  // 构建完成通知对象
    }  // 结束 createNotification 函数

    private fun updateNotification(text: String) {  // private=私有；fun=函数声明；更新通知内容
        val manager = getSystemService(NotificationManager::class.java)  // val=只读变量；获取通知管理器服务
        manager.notify(NOTIFICATION_ID, createNotification(text))  // 用新文本重新显示通知
    }  // 结束 updateNotification 函数

    override fun onDestroy() {  // override=覆写父类方法；Service 销毁回调：释放所有资源
        super.onDestroy()  // 调用父类销毁逻辑
        // 停止智能暂停监控
        stopSmartMonitor()  // 停止前台应用轮询线程
        // 停止手柄连接状态轮询
        stopControllerMonitor()  // 停止手柄状态轮询任务
        // 注销蓝牙 HID Host 代理
        unregisterHidProxy()  // 注销蓝牙代理监听
        // 清除无障碍按键转发回调
        GamepadAccessibilityService.onPausedKeyEvent = null  // 置空无障碍按键回调
        // 移除焦点输入窗口
        gamepadInputView?.let { windowManager?.removeView(it) }  // ?.=安全调用；let=作用域函数（it 指代窗口）；移除 1x1 焦点窗口
        gamepadInputView = null  // 清空窗口引用
        // 停止映射器
        mapper?.stop()  // ?.=安全调用；停止键盘鼠标映射器
        mapper = null  // 清空映射器引用
        steamInput = null  // 清空 SteamInput 引用
        // 清除 LayerEditActivity 的 SteamInput 引用
        LayerEditActivity.steamInputRef = null  // 清空静态引用
        configManager = null  // 清空配置管理器引用
        // 停止TCP服务器
        bridgeServer?.stop()  // ?.=安全调用；停止 TCP 桥接服务器
        bridgeServer = null  // 清空服务器引用
        // 移除悬浮窗UI
        overlayView?.let { windowManager?.removeView(it) }  // ?.=安全调用；let=作用域函数；移除悬浮窗容器
        overlayView = null  // 清空悬浮窗引用
        batteryText = null  // 清空电量文本引用（避免持有已移除的视图）
        mainHandler.removeCallbacks(batteryRefreshTask)  // 移除电量定时刷新任务，避免 Service 销毁后残留循环
    }  // 结束 onDestroy 函数
}  // 结束 ControllerOverlayService 类
