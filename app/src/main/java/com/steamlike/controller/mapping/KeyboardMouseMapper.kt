package com.steamlike.controller.mapping // 语法：package 包声明，声明本文件所属的包（映射模块）

import android.os.Handler // 语法：import 导入声明，导入 Handler（长按阈值定时器用）
import android.os.Looper // 语法：import 导入声明，导入 Looper（主线程消息循环）
import android.os.SystemClock // 语法：import 导入声明，导入系统单调时钟 SystemClock（摇杆循环计时用）
import android.util.Log // 语法：import 导入声明，导入 Log 日志工具
import android.view.KeyEvent // 语法：import 导入声明，导入 KeyEvent（使用 Android 按键码常量，如 KEYCODE_D）
import android.view.MotionEvent // 语法：import 导入声明，导入 MotionEvent（摇杆/扳机运动事件）
import com.steamlike.controller.core.ControllerButton // 语法：import 导入声明，导入手柄按钮枚举
import com.steamlike.controller.core.ControllerStick // 语法：import 导入声明，导入摇杆类型枚举
import com.steamlike.controller.core.KeyMapping // 语法：import 导入声明，导入按键映射数据类
import com.steamlike.controller.core.MappedAction // 语法：import 导入声明，导入映射动作密封类
import com.steamlike.controller.core.MouseButton // 语法：import 导入声明，导入鼠标按钮枚举
import com.steamlike.controller.core.OperationLayer // 语法：import 导入声明，导入操作层类型（记录触发过鼠标动作的层）
import com.steamlike.controller.core.SteamInput // 语法：import 导入声明，导入 SteamInput 核心控制器
import com.steamlike.controller.injection.InputInjector // 语法：import 导入声明，导入输入注入器接口

/**
 * 键盘/鼠标映射器
 *
 * 监听 [SteamInput] 的映射回调，将手柄按键映射转换为键盘/鼠标事件，
 * 通过 [InputInjector] 注入到目标应用。
 *
 * ## 子命令（Sub-Command）处理
 *
 * 当按键映射包含子命令时，按以下顺序注入:
 * 1. 按下主键
 * 2. 按下所有子命令键
 * 3. 松开所有子命令键
 * 4. 松开主键
 *
 * 示例: 手柄X → KeyMapping(KeyboardKey(Alt), subCommands=[KEYCODE_3])
 * ```
 * 按下X: sendKeyDown(Alt) → sendKeyDown(3)
 * 松开X: sendKeyUp(3) → sendKeyUp(Alt)
 * 最终输出: Alt+3 组合键
 * ```
 *
 * ## 摇杆处理
 *
 * - 左摇杆: 默认不映射（可在设置中配置为 MouseMove）
 * - 右摇杆: 默认映射为视角控制（LookAround），发送鼠标相对移动
 * - 灵敏度由 [SteamInput.profile] 的 [com.steamlike.controller.core.GlobalSettings] 控制
 *
 * ## 线程安全
 * 所有回调在主线程执行（View 事件分发线程），无需额外同步。
 *
 * @param steamInput Steam 输入控制器
 * @param injector 输入注入器
 */
class KeyboardMouseMapper( // 语法：class 类声明，定义键盘/鼠标映射器
    private val steamInput: SteamInput, // 语法：val 只读构造参数，持有 SteamInput 控制器引用（用于注册映射回调）
    private val injector: InputInjector, // 语法：val 只读构造参数，持有输入注入器引用（用于发送键鼠事件）
    screenWidth: Int = 0, // 屏幕宽度参数，默认 0（语法：默认参数）
    screenHeight: Int = 0 // 屏幕高度参数，默认 0（语法：默认参数）
) { // 类体开始

    private val TAG = "SteamLikeMapper" // 语法：val 只读变量，日志标签常量（类型推断为 String）

    /**
     * 当前按下的主键集合（用于松开时释放）
     *
     * key = 手柄按钮, value = 主键的 Android KeyCode
     */
    private val pressedMainKeys = mutableMapOf<ControllerButton, Int>() // 语法：val+mutableMapOf 可变映射，记录按下主键：手柄按钮→主键KeyCode

    /**
     * 当前按下的子命令键集合（用于松开时释放）
     *
     * key = 手柄按钮, value = 子命令的 Android KeyCode 列表
     */
    private val pressedSubKeys = mutableMapOf<ControllerButton, List<Int>>() // 语法：val+mutableMapOf 可变映射，记录按下子命令键：手柄按钮→KeyCode列表

    /**
     * 当前按下鼠标按钮的手柄按钮集合
     */
    private val pressedMouseButtons = mutableMapOf<ControllerButton, MouseButton>() // 语法：val+mutableMapOf 可变映射，记录按下鼠标按钮的手柄按钮→鼠标按钮

    // ====================================================================
    // 「只切换层」状态（切层键按住时的修饰键暂时抬起）
    // ====================================================================
    // 切层+按键映射（如 LB = 切层 + Ctrl）按住期间，Ctrl 作为修饰键一直处于按下态。
    // 层内某按键开启「只切换层」时，需要在该动作执行期间暂时抬起这些切层键，
    // 只保留切层效果（如 A 映射 2 → 游戏收到 2 而不是 Ctrl+2），松开后自动恢复。
    // ====================================================================

    /** 当前按住「切层+按键」的手柄按钮 → 其注入的主键码（供「只切换层」抬起） */
    private val heldLayerKeys = mutableMapOf<ControllerButton, Int>() // 语法：val+mutableMapOf 可变映射

    /** 因「只切换层」而暂时抬起的切层键码（松开后重新按下） */
    private val liftedLayerKeys = mutableSetOf<Int>() // 语法：val+mutableSetOf 可变集合

    /** 当前处于「只切换层」动作按住中的手柄按钮（集合清空时恢复切层键） */
    private val onlyLayerSwitchButtons = mutableSetOf<ControllerButton>() // 语法：val+mutableSetOf 可变集合

    /**
     * 左摇杆当前按下的方向键集合（WASD映射）
     *
     * 摇杆8方向映射:
     * - 上(W), 下(S), 左(A), 右(D)
     * - 左上(A+W), 右上(D+W), 左下(A+S), 右下(D+S)
     *
     * 每次摇杆移动时计算新方向集合，与旧集合差异发送按键事件。
     */
    private val leftStickPressedKeys = mutableSetOf<Int>() // 语法：val+mutableSetOf 可变集合（无重复元素），保存左摇杆当前按下的方向键

    /**
     * 当前处于"锁存"状态的鼠标按钮集合（全局统一状态）
     *
     * 只按鼠标按钮记录，不区分是哪个手柄按钮触发的，因此
     * 「鼠标长按」映射与左摇杆移动自动锁定右键共用同一份标志：
     *
     * - 第一次按下（鼠标长按映射）→ 发送 MouseDown，加入集合
     * - 再次按下 → 发送 MouseUp，移出集合
     * - 左摇杆 WASD 触发且右键不在集合中 → 发送 MouseDown 并加入集合
     *   （见 [ensureRightButtonLockedOnMove]）
     *
     * 锁存状态由用户主动解锁，不因松开手柄键而释放（见 [handleMouseToggle]）。
     * 只有在操作层激活期间确实点击或长按过鼠标左右键的层被停用时才会统一释放
     * （见 [releaseMouseStateOnLayerDeactivated]）。
     */
    private val toggledMouseButtons = mutableSetOf<MouseButton>() // 语法：val+mutableSetOf 可变集合，记录鼠标按钮锁存状态（全局统一标志）

    /**
     * Windows 客户端（Rust 原生侧）上报的锁存状态
     *
     * APK 手柄映射与 Rust 快捷键各自维护一份锁存，互不知情。Rust 侧一旦用快捷键
     * 锁定/解锁右键，会通过 0x28 报文上报，这里记录下来与 [toggledMouseButtons]
     * 合并成统一的「锁定视图」（见 [isMouseLocked]）：
     * - 悬浮窗边框高亮、右键锁定切换动作的判断都用合并后的状态
     * - 「释放全部」时，仅远端锁存的情况改为通知客户端解锁，避免多发一条 MouseUp
     */
    private val remoteLockedButtons = mutableSetOf<MouseButton>() // 远端（Rust）上报的锁存按钮

    /** 已向外部通知过「锁存中」的按钮（合并视图变化时才回调，避免重复/错误通知） */
    private val notifiedLockedButtons = mutableSetOf<MouseButton>() // 语法：val+mutableSetOf 可变集合

    /** 客户端（重）连接后等待同步：收到一次 0x28 锁存上报后再对齐物理鼠标状态 */
    private var pendingLockResync = false // 语法：var 可变布尔标志

    /** 客户端「解锁+居中+按键」临时禁用 WASD 自动锁定：APK 侧同样暂停自动锁定 */
    private var remoteWasdLockSuspended = false // 语法：var 可变布尔标志

    /** 本地锁存的设定时间（毫秒），用于判断远端「未锁存」上报是否已滞后 */
    private val toggledMouseAt = mutableMapOf<MouseButton, Long>() // 语法：val+mutableMapOf 可变映射

    /**
     * 在激活期间触发过鼠标左右键动作的操作层集合
     *
     * 用于限制「层停用释放鼠标锁存」的触发条件：
     * 只有用户在该层内按下过鼠标左键/右键（短按点击或长按锁存）时才记录该层与对应手柄按钮；
     * 仅按下切层键、未操作鼠标左右键的层不会被记录，松开时自然不释放。
     */
    private val layerMouseActionButtons = mutableMapOf<OperationLayer, MutableSet<ControllerButton>>() // 层 → 该层内操作过鼠标左右键的手柄按钮

    /**
     * 当前按住「精细移动」映射的手柄按钮集合
     *
     * 集合非空即处于精细移动模式：右摇杆位移按 [FINE_MOVE_FACTOR] 缩小。
     * 按键松开时移出集合，全部松开后恢复正常灵敏度。
     */
    private val fineMoveButtons = mutableSetOf<ControllerButton>() // 语法：val+mutableSetOf 可变集合，记录按住精细移动的按钮

    /** 精细移动模式是否生效（供右摇杆发送线程无锁读取） */
    @Volatile // 语法：@Volatile 注解，保证跨线程可见
    private var fineMoveActive = false // 语法：var 可变变量，精细移动模式标志

    /**
     * 当前按住「快速移动」映射的手柄按钮集合
     *
     * 集合非空即处于快速移动模式：右摇杆位移按 [FAST_MOVE_FACTOR] 放大。
     * 与精细移动同时按住时以精细移动为准。
     */
    private val fastMoveButtons = mutableSetOf<ControllerButton>() // 语法：val+mutableSetOf 可变集合，记录按住快速移动的按钮

    /** 快速移动模式是否生效（供右摇杆发送线程无锁读取） */
    @Volatile // 语法：@Volatile 注解，保证跨线程可见
    private var fastMoveActive = false // 语法：var 可变变量，快速移动模式标志

    // ====================================================================
    // 长按判定状态机（Long-Press）
    // ====================================================================
    // 仅对「已配置长按动作」或「全局回退开关开启」的可延迟短按动作生效：
    // 按下时不立即注入，改为记录按下时间并启动阈值定时器；松开时按按住时长
    // 决定执行短按动作还是长按动作（见 [beginDeferredPress]/[finishDeferredPress]）。
    // 层切换、滚轮、鼠标长按锁存、悬浮窗/键盘/捕获切换等动作不参与判定。
    // ====================================================================

    /** 主线程 Handler，用于长按阈值定时与解锁后的延时居中（与输入事件同线程，避免并发） */
    private val mainHandler = Handler(Looper.getMainLooper()) // 语法：Handler(Looper.getMainLooper())=绑定主线程的消息处理器

    /**
     * 「解锁 → 居中」之间插入的等待时长（毫秒）
     *
     * 锁定时游戏处于 mouselook：WoW 会自己接管光标（隐藏指针、把光标钉在视口中心），
     * 收到解锁后需等它的帧循环处理完才真正放开光标。若解锁与居中间隔太近，
     * 居中会被游戏随后的一次光标接管覆盖（表现为「锁定状态下在游戏里不居中」）。
     * 因此解锁报文立即发出，居中及其之后的动作整体延后本时长，相对顺序不变。
     */
    private val unlockCenterDelayMs = 150L // 语法：private val 私有只读属性，延时时长（毫秒）

    /** 一次延迟按下（长按判定中）的上下文 */
    private data class DeferredPress( // 语法：private data class 私有数据类，封装延迟按下上下文
        val mapping: KeyMapping, // 按下时生效的映射（松开时按它判定，避免层切换后映射变化）
        val pressTimeMs: Long, // 按下时刻（SystemClock.elapsedRealtime）
        val layerName: String? // 映射所属操作层名（null=公共层），用于「只切换层」只抵消本层切层键
    ) // 结束 DeferredPress 数据类

    /** 处于长按判定中的按键 → 延迟按下上下文 */
    private val deferredPresses = mutableMapOf<ControllerButton, DeferredPress>() // 语法：val+mutableMapOf 可变映射

    /** 各按键的长按阈值定时器（松开时取消） */
    private val longPressTimers = mutableMapOf<ControllerButton, Runnable>() // 语法：val+mutableMapOf 可变映射（按钮→定时任务）

    /** 已在阈值到达时注入「保持型」长按动作按下的按键（松开时释放） */
    private val longHoldActive = mutableSetOf<ControllerButton>() // 语法：val+mutableSetOf 可变集合

    /** 已在阈值到达时触发「瞬时」长按动作的按键（松开时无需再处理） */
    private val longInstantFired = mutableSetOf<ControllerButton>() // 语法：val+mutableSetOf 可变集合

    /**
     * 右摇杆最新位置（主线程事件回调写入，发送循环线程读取）
     *
     * 事件驱动方案的缺陷：MotionEvent 到达时间受主线程负载、蓝牙抖动、
     * 系统批处理影响而不均匀——即使位移量按 dt 缩放正确，鼠标包的
     * **时间分布**仍会抖动，游戏内表现为一顿一顿。
     *
     * 业界方案（Steam Input/DS4Windows）是**固定频率刷新循环**：
     * 由稳定时钟每 tick 读取最新位置并发送，包节奏完全均匀。
     * 这里事件回调只负责更新位置状态，实际发送由 [startLookLoop] 消费。
     */
    @Volatile // 语法：@Volatile 注解，保证字段跨线程可见（主线程写、发送循环线程读）
    private var latestLookX = 0f // 语法：var 可变变量，右摇杆最新 X 位置（0f=Float 字面量）

    @Volatile // 语法：@Volatile 注解，保证字段跨线程可见
    private var latestLookY = 0f // 语法：var 可变变量，右摇杆最新 Y 位置

    /**
     * 右摇杆平滑滤波后的 X/Y 值（仅在发送循环线程内读写）
     *
     * 指数移动平均（EMA），使用**时间常数**：α = 1 - exp(-dt/τ)
     * τ 由 [com.steamlike.controller.core.GlobalSettings.lookSmoothing] 映射（0~45ms），
     * 与事件频率无关，tick 频率变化时平滑效果一致。
     */
    private var smoothedLookX = 0f // 语法：var 可变变量，右摇杆 EMA 平滑后的 X
    private var smoothedLookY = 0f // 语法：var 可变变量，右摇杆 EMA 平滑后的 Y

    /**
     * 右摇杆固定频率发送循环线程（自校正 tick）
     *
     * 每 [LOOK_TICK_MS] 毫秒读取最新摇杆位置，按实际间隔 dt 计算位移并发送，
     * 位移总量 = 速度 × 实际经过时间（tick 被系统延迟时总量不变，不丢量）。
     * 独立于主线程，主线程 jank 不影响发送节奏。
     */
    private var lookThread: Thread? = null // 语法：var+Thread? 可空类型，发送循环线程引用（null=未启动）

    @Volatile // 语法：@Volatile 注解，保证运行标志跨线程可见
    private var lookThreadRunning = false // 语法：var 可变变量，发送循环是否运行标志

    /**
     * 操作层变化回调
     *
     * 当激活的操作层发生变化时调用，传递当前所有激活层的名称列表。
     * 由 ControllerOverlayService 设置，用于更新悬浮窗 UI。
     */
    var onLayerChanged: ((List<String>) -> Unit)? = null // 语法：var+函数类型+? 可空回调，操作层变化时触发（lambda 类型）

    /**
     * 操作集切换回调
     *
     * 当通过 [switchActionSet] 切换操作集时调用，传递新操作集名称。
     * 由 ControllerOverlayService 设置，用于更新悬浮窗显示当前操作集。
     */
    var onActionSetChanged: ((String) -> Unit)? = null // 语法：var+函数类型+? 可空回调，操作集切换时触发

    /**
     * 切换悬浮窗回调
     *
     * 当映射到 ToggleOverlay 动作的手柄按钮按下时调用。
     * 由 ControllerOverlayService 设置，触发悬浮窗收起/映射列表切换。
     */
    var onToggleOverlay: (() -> Unit)? = null // 语法：var+函数类型+? 可空回调，切换悬浮窗时触发

    /**
     * 切换系统键盘回调
     *
     * 当映射到 ToggleKeyboard 动作的手柄按钮按下时调用。
     * 由 ControllerOverlayService 设置，触发软键盘显示/隐藏。
     */
    var onToggleKeyboard: (() -> Unit)? = null // 语法：var+函数类型+? 可空回调，切换系统键盘时触发

    /**
     * 回车+切换系统键盘回调
     *
     * 当映射到 ToggleKeyboardEnter 动作的手柄按钮按下时调用。
     * 由 ControllerOverlayService 设置：先发送回车，再切换软键盘显示/隐藏。
     */
    var onToggleKeyboardEnter: (() -> Unit)? = null // 语法：var+函数类型+? 可空回调，回车后切换系统键盘时触发

    /**
     * 切换捕获状态回调
     *
     * 当映射到 ToggleCapture 动作的手柄按钮按下时调用。
     * 由 ControllerOverlayService 设置，触发暂停/恢复捕获。
     */
    var onToggleCapture: (() -> Unit)? = null // 语法：var+函数类型+? 可空回调，切换捕获状态时触发

    /**
     * 鼠标长按切换（MouseToggle）状态变化回调
     *
     * 当 MouseToggle 锁存状态切换时调用，传递鼠标按钮与当前是否处于"按下"状态。
     * 由 ControllerOverlayService 设置，用于悬浮窗边框高亮提示
     * （如右键长按锁存时改变边框颜色，释放后恢复）。
     */
    var onMouseToggleChanged: ((MouseButton, Boolean) -> Unit)? = null // 语法：var+函数类型+? 可空回调，MouseToggle 锁存状态变化时触发

    /**
     * 请求 Windows 客户端解除鼠标锁存（发 0x26 报文）
     *
     * 用于「右键锁定切换 / 释放全部」遇到仅由 Rust 快捷键锁定的情况：
     * 锁存由客户端本地维护，APK 无法直接松开，只能通知客户端解锁并延时居中。
     * 由 ControllerOverlayService 设置为 [InputBridgeServer.sendUnlockMouse]。
     */
    var onRequestRemoteUnlock: (() -> Unit)? = null // 语法：var+函数类型+? 可空回调

    /**
     * 是否正在捕获（历史字段，当前无实际作用）
     *
     * 早期设计中暂停捕获时由服务设置此字段，映射器据此仅处理切换动作。
     * 现在暂停捕获会直接移除 GamepadInputView 焦点窗口，事件根本到不了映射器；
     * 捕获状态由 [com.steamlike.controller.service.ControllerOverlayService.isCapturing]
     * 与 [com.steamlike.controller.core.SteamInput.isCapturing] 统一管理。
     * 本字段已不再被读写，保留仅为兼容旧逻辑。
     */
    var isCapturing: Boolean = true // 语法：var 可变变量，是否正在捕获（历史兼容字段）

    /**
     * 启动映射器，注册回调
     *
     * @return true=启动成功
     */
    fun start(): Boolean { // 语法：fun 函数声明，返回 Boolean，启动映射器并注册回调
        steamInput.onButtonMapped = { button, isPressed, mapping, layerName -> // 注册按钮映射回调（语法：lambda 表达式赋给属性）
            handleMapping(button, isPressed, mapping, layerName) // 分发按钮映射到内部处理方法
        } // 结束回调注册 // 结束按钮映射 lambda
        steamInput.onStickMapped = { stick, x, y -> // 注册摇杆映射回调（语法：lambda 表达式）
            handleStick(stick, x, y) // 分发摇杆事件到内部处理方法
        } // 结束摇杆映射 lambda
        steamInput.onLayerChanged = { layerName -> // 注册操作层变化回调（语法：lambda 表达式）
            Log.i(TAG, "Active layer: $layerName") // 打印激活层日志（语法：字符串模板 "$layerName"）
            // 通知外部监听器，传递当前所有激活层名称列表
            onLayerChanged?.invoke(getActiveLayers()) // 安全调用触发回调，传入激活层名称列表（语法：?. 安全调用 + invoke()）
        } // 结束操作层变化 lambda
        steamInput.onLayerDeactivated = { layer -> // 注册操作层停用回调（语法：lambda 表达式带参数）
            // 仅当该层激活期间点击或长按过鼠标左右键时才释放左右键状态，
            // 避免「只按过切层键、没操作鼠标」时误释放用户原有的长按状态
            releaseMouseStateOnLayerDeactivated(layer) // 按条件释放鼠标点击/长按状态
        } // 结束操作层停用 lambda
        steamInput.onButtonReleased = { button -> // 注册无映射按钮的松开回调（语法：lambda 表达式）
            // 该按钮按下时的映射已不可达（层已停用/无映射）：先结束可能是精细移动的按住态，
            // 再按按下时记录的状态释放已注入的键鼠事件
            clearFineMove(button) // 退出精细移动（若在按住中）
            clearFastMove(button) // 退出快速移动（若在按住中）
            if (!finishDeferredPress(button)) releaseButtonInjection(button) // 释放已注入状态（延迟按下则交给长按判定收尾）
        } // 结束无映射松开 lambda
        steamInput.onActionSetChanged = { actionSetName -> // 注册操作集切换回调（语法：lambda 表达式）
            Log.i(TAG, "Action set: $actionSetName") // 打印操作集切换日志（语法：字符串模板 "$actionSetName"）
            onActionSetChanged?.invoke(actionSetName) // 安全调用触发回调，传递新操作集名称（语法：?. 安全调用）
        } // 结束操作集切换 lambda
        // 启动右摇杆固定频率发送循环
        startLookLoop() // 调用内部方法启动右摇杆发送循环
        Log.i(TAG, "KeyboardMouseMapper started") // 打印映射器启动日志
        return true // 返回 true 表示启动成功
    } // 结束 start 函数

    /**
     * 停止映射器，释放所有按键
     */
    fun stop() { // 语法：fun 函数声明，停止映射器并释放所有按键状态
        stopLookLoop() // 停止右摇杆发送循环
        // 清理长按判定状态：取消所有阈值定时器，避免停止后仍触发注入
        longPressTimers.values.forEach { mainHandler.removeCallbacks(it) } // 遍历取消所有定时任务（语法：values=映射值集合 + forEach lambda）
        longPressTimers.clear() // 清空定时器记录
        deferredPresses.clear() // 清空延迟按下记录
        longHoldActive.clear() // 清空保持型长按记录
        longInstantFired.clear() // 清空瞬时长按记录
        injector.releaseAll() // 通知注入器释放所有已注入的键鼠状态
        pressedMainKeys.clear() // 清空主键按下记录
        pressedSubKeys.clear() // 清空子命令键按下记录
        pressedMouseButtons.clear() // 清空鼠标按钮按下记录
        heldLayerKeys.clear() // 清空切层键归属记录
        liftedLayerKeys.clear() // 清空被「只切换层」抬起的键
        onlyLayerSwitchButtons.clear() // 清空「只切换层」按住记录
        leftStickPressedKeys.clear() // 清空左摇杆方向键集合
        toggledMouseButtons.clear() // 清空鼠标按钮锁存记录（含左摇杆移动自动锁定的右键）
        remoteLockedButtons.clear() // 清空客户端上报的锁存记录
        notifiedLockedButtons.clear() // 清空已通知过的锁存状态
        pendingLockResync = false // 取消待同步标记
        remoteWasdLockSuspended = false // 取消客户端的临时禁用标记
        toggledMouseAt.clear() // 清空本地锁定时间记录
        layerMouseActionButtons.clear() // 清空「层 → 操作过鼠标左右键的按钮」记录
        fineMoveButtons.clear() // 清空精细移动按住记录
        fineMoveActive = false // 关闭精细移动标志
        fastMoveButtons.clear() // 清空快速移动按住记录
        fastMoveActive = false // 关闭快速移动标志
        latestLookX = 0f // 重置右摇杆最新 X
        latestLookY = 0f // 重置右摇杆最新 Y
        smoothedLookX = 0f // 重置平滑 X
        smoothedLookY = 0f // 重置平滑 Y
        Log.i(TAG, "KeyboardMouseMapper stopped") // 打印映射器停止日志
    } // 结束 stop 函数

    // ====================================================================
    // SteamInput 委托方法
    // ====================================================================
    // 以下方法将调用转发到 SteamInput，供 ControllerOverlayService 使用。
    // ====================================================================

    /**
     * 获取当前激活的操作层名称列表
     *
     * @return 激活层名称列表（空列表=仅公共层）
     */
    fun getActiveLayers(): List<String> = steamInput.getActiveLayers().map { it.name } // 语法：= 表达式体函数 + map lambda 映射，返回激活层名称列表

    /**
     * 处理系统 KeyEvent（按钮按下/释放）
     *
     * 转发到 [SteamInput.dispatchKeyEvent] 进行手柄按键映射。
     *
     * @param event 系统按键事件
     * @return true=已处理
     */
    fun onKeyEvent(event: KeyEvent): Boolean = steamInput.dispatchKeyEvent(event) // 语法：= 表达式体函数，转发按键事件给 SteamInput

    /**
     * 处理系统 MotionEvent（摇杆移动/扳机按压）
     *
     * 转发到 [SteamInput.dispatchGenericMotionEvent] 进行摇杆映射。
     *
     * @param event 系统运动事件
     * @return true=已处理
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean = steamInput.dispatchGenericMotionEvent(event) // 语法：= 表达式体函数，转发运动事件给 SteamInput

    /**
     * 清除所有激活的操作层（回到公共层）
     */
    fun clearAllLayers() = steamInput.deactivateAllLayers() // 语法：= 表达式体函数，清除所有激活操作层

    /**
     * 按名称激活操作层
     *
     * @param name 操作层名称
     */
    fun activateLayer(name: String) = steamInput.activateLayer(name) // 语法：= 表达式体函数，按名称激活操作层

    /**
     * 按名称停用操作层
     *
     * @param name 操作层名称
     */
    fun deactivateLayer(name: String) = steamInput.deactivateLayer(name) // 语法：= 表达式体函数，按名称停用操作层

    /**
     * 切换操作集（整体切换其下所有操作层）
     *
     * @param name 目标操作集名称
     */
    fun switchActionSet(name: String) = steamInput.switchActionSet(name) // 语法：= 表达式体函数，切换操作集

    /**
     * 当前操作集名称
     */
    fun getActiveActionSetName(): String = steamInput.getActiveActionSetName() // 语法：= 表达式体函数，返回当前操作集名称

    /**
     * 处理按键映射
     *
     * 根据 [mapping.action] 类型分发到对应的处理方法。
     * 子命令在主键按下/松开时一起注入。
     *
     * **松开事件按"已注入状态"释放**，不按当前层映射分发：
     * 按住 B(鼠标右键) 时切换操作层，激活层可能覆盖 B 的映射（如 B→技能键）。
     * 若按当前映射分发释放会走进 KeyboardKey 分支，而 B 按下时记录在
     * [pressedMouseButtons] 而非 [pressedMainKeys]，导致右键 MouseUp 永远发不出去、
     * 游戏内右键卡死。因此松开统一走 [releaseButtonInjection]。
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mapping 按键映射
     * @param layerName 映射所属操作层名（null=公共层）
     */
    private fun handleMapping(button: ControllerButton, isPressed: Boolean, mapping: KeyMapping, layerName: String?) { // 语法：private fun 私有函数，按动作类型分发按键映射
        if (!isPressed) { // 语法：if 分支，判断是否为松开事件
            clearFineMove(button) // 若该按钮正按住「精细移动」则退出，恢复普通灵敏度
            clearFastMove(button) // 若该按钮正按住「快速移动」则退出，恢复普通灵敏度
            if (finishDeferredPress(button)) return // 若该按钮处于长按判定中，按按住时长决定短按/长按（语法：if 提前返回）
            releaseButtonInjection(button) // 按已注入状态释放该按钮的全部键鼠状态
            return // 直接返回，松开不按当前层映射分发
        } // 结束 if 松开分支

        // 长按判定：仅对「已配置长按动作」或「全局回退开启」的可延迟短按动作生效
        if (isLongPressEligible(mapping)) { // 语法：if 判断是否启用长按判定
            beginDeferredPress(button, mapping, layerName) // 按下时不注入，记录时间并启动阈值定时器
            return // 返回，等待松开时判定
        } // 结束长按判定分支

        // 「只切换层」：先暂时抬起切层键同时按下的按键，使本次动作只保留切层效果
        if (mapping.layerSwitchOnly && isOnlyLayerApplicable(mapping.action)) { // 语法：&& 逻辑与
            suppressLayerKeys(button, layerName) // 抬起本层切层键（松开该按钮时由 releaseButtonInjection 恢复）
        } // 结束「只切换层」判断

        when (val action = mapping.action) { // 语法：when 分支表达式，同时用 val 声明局部变量 action
            // 切换动作：始终处理
            is MappedAction.ToggleOverlay -> { onToggleOverlay?.invoke(); return } // 悬浮窗切换动作：触发回调后返回（语法：is 类型匹配 + 分号并列多语句）
            is MappedAction.ToggleKeyboard -> { onToggleKeyboard?.invoke(); return } // 系统键盘切换动作：触发回调后返回（语法：is 类型匹配）
            is MappedAction.ToggleKeyboardEnter -> { onToggleKeyboardEnter?.invoke(); return } // 回车+键盘切换动作：触发回调后返回
            is MappedAction.ToggleCapture -> { onToggleCapture?.invoke(); return } // 捕获切换动作：触发回调后返回（语法：is 类型匹配）
            else -> {} // 其他动作不在切换分支处理（语法：else 兜底分支）
        } // 结束切换动作 when

        // 非切换动作：始终处理（暂停时也转发到 bridge，让游戏接收手柄输入）
        when (val action = mapping.action) { // 语法：when 分支表达式，分发非切换动作
            is MappedAction.None -> {} // 空动作（点击未设置，仅配置长按）：不注入任何事件
            is MappedAction.KeyboardKey -> handleKeyboardKey(button, true, action.keyCode, mapping.subCommands) // 键盘按键动作：处理主键+子命令（语法：is 类型匹配）
            is MappedAction.SwitchLayerAndKey -> if (!mapping.layerSwitchOnly) { // 切层+按键动作：开启「只切换层」时只切层、不注入按键
                pressLayerKey(button, action.keyCode, mapping.subCommands) // 记录归属并注入同时按下的按键（语法：if 单表达式分支）
            } // 结束切层+按键分支
            is MappedAction.NumpadKey -> handleKeyboardKey(button, true, action.keyCode, mapping.subCommands) // 数字小键盘动作：按键盘键注入（VK 映射到小键盘键）
            is MappedAction.MouseClick -> handleMouseClick(button, true, action.button) // 鼠标点击动作：按下鼠标按钮
            is MappedAction.MouseToggle -> handleMouseToggle(button, action.button) // 鼠标长按切换动作（仅在按下时到达此处）
            is MappedAction.MouseSwapToggle -> handleMouseSwapToggle() // 鼠标长按交换动作：在左右键长按间交换
            is MappedAction.MouseReleaseAll -> handleMouseReleaseAll() // 释放鼠标所有长按动作：解除全部长按锁存
            is MappedAction.MouseRightLockToggle -> handleRightLockToggle() // 右键锁定切换动作：按当前状态锁定/解锁视角
            is MappedAction.MouseClickSequence -> handleMouseClickSequence() // 交互键动作：临时释放锁存→左键点击→右键点击→恢复锁存
            is MappedAction.MouseFineMove -> enterFineMove(button) // 精细移动动作：按住期间缩小右摇杆鼠标位移
            is MappedAction.MouseFastMove -> enterFastMove(button) // 快速移动动作：按住期间放大右摇杆鼠标位移
            is MappedAction.Turn180 -> turnByPixels(action.pixels, action.stepMs) // 定角转身动作：按下即注入一次水平位移
            is MappedAction.SwitchLayer -> {} // SteamInput 已处理 // 切换层动作：由 SteamInput 内部处理
            is MappedAction.MouseMove, is MappedAction.LookAround -> {} // handleStick 处理 // 鼠标移动/视角动作：由 handleStick 处理
            is MappedAction.MouseScrollUp -> handleMouseScroll(SCROLL_DELTA) // 鼠标上滚动作：传入正增量
            is MappedAction.MouseScrollDown -> handleMouseScroll(-SCROLL_DELTA) // 鼠标下滚动作：传入负增量
            is MappedAction.ToggleOverlay, // 悬浮窗切换动作（此处重复列出，上面已处理）
            is MappedAction.ToggleKeyboard, // 系统键盘切换动作（此处重复列出，上面已处理）
            is MappedAction.ToggleKeyboardEnter, // 回车+键盘切换动作（此处重复列出，上面已处理）
            is MappedAction.ToggleCapture -> {} // 已处理 // 捕获切换动作：上面已处理
        } // 结束非切换动作 when
    } // 结束 handleMapping 函数

    /**
     * 判断某映射是否启用「长按判定」
     *
     * - 已配置长按动作 → 一律启用（无论短按动作是保持型还是触发型，如鼠标锁存/交换）
     * - 未配置长按动作但全局回退开关开启，且短按为保持型动作（键盘键/数字小键盘/鼠标点击）
     *   → 也启用（长按松开时回退执行短按动作）
     * - 其余情况（未配置且回退关闭）→ 不启用，保持原有「按下即触发」行为
     *
     * @param mapping 按键映射
     */
    private fun isLongPressEligible(mapping: KeyMapping): Boolean { // 语法：private fun 私有函数，判断是否启用长按判定
        if (mapping.longPressAction != null) return true // 配置了长按动作 → 一律启用判定
        val shortAction = mapping.action // 取短按动作
        // 未配置长按动作时，仅「保持型短按动作」在回退开关开启下参与判定
        val holdable = shortAction is MappedAction.KeyboardKey || // 键盘键（语法：|| 逻辑或）
                shortAction is MappedAction.NumpadKey || // 数字小键盘
                shortAction is MappedAction.MouseClick // 鼠标点击
        return holdable && steamInput.profile.globalSettings.longPressFallbackToShort // 回退开关决定
    } // 结束 isLongPressEligible 函数

    /**
     * 判断长按动作是否为「保持型」（按下保持、松开释放）
     *
     * - 保持型：键盘键 / 数字小键盘 / 鼠标点击 → 阈值到达时注入按下，手柄松开时释放
     * - 瞬时型：滚轮/切换/锁存等 → 阈值到达时触发一次，手柄松开时不处理
     */
    private fun isHoldableAction(action: MappedAction): Boolean = // 语法：表达式体函数 + when 判断
        action is MappedAction.KeyboardKey || action is MappedAction.NumpadKey || action is MappedAction.MouseClick

    /**
     * 按下注入一个「可保持」动作（记录状态，供 [releaseButtonInjection] 释放）
     *
     * @param subCommands 子命令键码列表（短按动作沿用 [KeyMapping.subCommands]；长按动作沿用 [KeyMapping.longPressSubCommands]）
     */
    private fun injectActionDown(button: ControllerButton, action: MappedAction, subCommands: List<Int> = emptyList()) { // 语法：private fun 私有函数，注入动作按下
        when (action) { // 按动作类型分发（语法：when 分支）
            is MappedAction.KeyboardKey -> handleKeyboardKey(button, true, action.keyCode, subCommands) // 键盘键：注入按下+子命令
            is MappedAction.NumpadKey -> handleKeyboardKey(button, true, action.keyCode, subCommands) // 数字小键盘：注入按下+子命令
            is MappedAction.MouseClick -> handleMouseClick(button, true, action.button) // 鼠标点击：注入按下
            else -> {} // 其它动作不由本函数处理
        } // 结束 when
    } // 结束 injectActionDown 函数

    /**
     * 触发一个「瞬时」动作（一次生效，无按下/松开之分）
     */
    private fun injectInstantAction(button: ControllerButton, action: MappedAction) { // 语法：private fun 私有函数，触发瞬时动作（带按钮，用于记录层内鼠标动作）
        when (action) { // 按动作类型分发（语法：when 分支）
            is MappedAction.MouseToggle -> handleMouseToggle(button, action.button) // 鼠标长按锁存：切换锁存状态
            is MappedAction.MouseSwapToggle -> handleMouseSwapToggle() // 鼠标长按交换：交换左右键锁存
            is MappedAction.MouseReleaseAll -> handleMouseReleaseAll() // 释放鼠标所有长按：解除全部锁存
            is MappedAction.MouseRightLockToggle -> handleRightLockToggle() // 右键锁定切换：按当前状态锁定/解锁视角
            is MappedAction.MouseClickSequence -> handleMouseClickSequence() // 交互键：临时释放锁存后左右键依次点击
            is MappedAction.Turn180 -> turnByPixels(action.pixels, action.stepMs) // 定角转身：一次注入整段水平位移（长按/短按都走这里）
            is MappedAction.MouseScrollUp -> handleMouseScroll(SCROLL_DELTA) // 滚轮上滚：发送一次上滚
            is MappedAction.MouseScrollDown -> handleMouseScroll(-SCROLL_DELTA) // 滚轮下滚：发送一次下滚
            is MappedAction.ToggleOverlay -> onToggleOverlay?.invoke() // 切换悬浮窗（语法：?. 安全调用）
            is MappedAction.ToggleKeyboard -> onToggleKeyboard?.invoke() // 切换系统键盘
            is MappedAction.ToggleKeyboardEnter -> onToggleKeyboardEnter?.invoke() // 回车+切换系统键盘
            is MappedAction.ToggleCapture -> onToggleCapture?.invoke() // 切换捕获
            else -> {} // 其余动作（键盘/小键盘/鼠标点击为保持型，层切换/摇杆不处理）
        } // 结束 when
    } // 结束 injectInstantAction 函数

    /**
     * 执行一次「点按」（短按动作的统一执行入口）
     *
     * - 保持型动作：按下后立即释放，形成一次点按
     * - 触发型动作（鼠标锁存/交换、滚轮、悬浮窗/键盘/捕获切换）：直接触发一次
     *
     * @param subCommands 保持型动作的子命令键码列表（触发型动作忽略）
     */
    private fun tapAction(button: ControllerButton, action: MappedAction, subCommands: List<Int> = emptyList()) { // 语法：private fun 私有函数，执行一次点按
        if (isHoldableAction(action)) { // 保持型动作
            injectActionDown(button, action, subCommands) // 注入按下
            releaseButtonInjection(button) // 立即释放 → 形成点按
        } else { // 触发型动作
            injectInstantAction(button, action) // 直接触发一次
        } // 结束类型判断
    } // 结束 tapAction 函数

    /**
     * 开始一次「延迟按下」（长按判定）
     *
     * 按下时不注入任何事件，仅记录按下时间；若配置了长按动作，则在阈值到达时
     * 由 [onLongPressThreshold] 注入长按按下。
     */
    private fun beginDeferredPress(button: ControllerButton, mapping: KeyMapping, layerName: String?) { // 语法：private fun 私有函数，开始长按判定
        if (deferredPresses.containsKey(button)) return // 已在判定中则忽略，防重复（语法：containsKey 判断）
        val pressTime = SystemClock.elapsedRealtime() // 记录按下时刻（单调时钟，不受系统时间调整影响）
        deferredPresses[button] = DeferredPress(mapping, pressTime, layerName) // 存入延迟按下上下文（含所属层）
        if (mapping.longPressAction != null) { // 仅配置了长按动作时才需要阈值定时器
            val threshold = steamInput.profile.globalSettings.longPressThresholdMs.toLong() // 读取长按阈值（毫秒）
            val runnable = Runnable { onLongPressThreshold(button) } // 构造阈值到达回调（语法：Runnable{}=可运行 lambda）
            longPressTimers[button] = runnable // 记录定时任务，便于松开时取消
            mainHandler.postDelayed(runnable, threshold) // 延迟阈值毫秒后触发（语法：postDelayed 延迟投递）
        } // 结束长按动作判断
        Log.d(TAG, "LongPress begin: $button (threshold=${steamInput.profile.globalSettings.longPressThresholdMs}ms)") // 打印开始判定日志
    } // 结束 beginDeferredPress 函数

    /**
     * 长按阈值到达回调：注入长按动作
     *
     * - 保持型动作 → 注入按下并记入 [longHoldActive]，等待手柄松开时释放
     * - 瞬时型动作 → 触发一次并记入 [longInstantFired]
     */
    private fun onLongPressThreshold(button: ControllerButton) { // 语法：private fun 私有函数，阈值到达注入长按动作
        longPressTimers.remove(button) // 定时器已触发，移除记录
        val pending = deferredPresses[button] ?: return // 取出延迟按下上下文，已松开则返回（语法：?: 空值合并）
        val longAction = pending.mapping.longPressAction ?: return // 无长按动作（回退模式）则不注入
        if (isHoldableAction(longAction)) { // 保持型动作
            if (pending.mapping.longPressLayerSwitchOnly && isOnlyLayerApplicable(longAction)) { // 长按动作开启「只切换层」
                suppressLayerKeys(button, pending.layerName) // 先抬起本层切层键，使长按输出不叠加其按键（松开时在释放流程中恢复）
            } // 结束「只切换层」判断
            injectActionDown(button, longAction, pending.mapping.longPressSubCommands) // 注入按下（带长按子命令）并记录状态
            longHoldActive.add(button) // 记入保持型长按集合
        } else if (longAction is MappedAction.MouseFineMove) { // 长按为精细移动：阈值到达即进入精细模式
            enterFineMove(button) // 进入精细移动（松开时由 handleMapping 的 clearFineMove 退出）
            longHoldActive.add(button) // 记入保持型集合，松开时走保持型释放分支
        } else if (longAction is MappedAction.MouseFastMove) { // 长按为快速移动：阈值到达即进入快速模式
            enterFastMove(button) // 进入快速移动（松开时由 handleMapping 的 clearFastMove 退出）
            longHoldActive.add(button) // 记入保持型集合，松开时走保持型释放分支
        } else { // 瞬时型动作
            injectInstantAction(button, longAction) // 触发一次（瞬时动作用不到子命令）
            longInstantFired.add(button) // 记入瞬时长按集合
        } // 结束类型判断
        Log.d(TAG, "LongPress fired: $button → $longAction") // 打印长按触发日志（语法：字符串模板 "$var"）
    } // 结束 onLongPressThreshold 函数

    /**
     * 结束一次「延迟按下」：按按住时长决定执行短按还是长按
     *
     * @return true=该按钮此前处于长按判定中（已处理）；false=不在判定中
     */
    private fun finishDeferredPress(button: ControllerButton): Boolean { // 语法：private fun 私有函数，结束长按判定
        val pending = deferredPresses.remove(button) ?: return false // 取出并移除延迟按下上下文，无则返回 false
        longPressTimers.remove(button)?.let { mainHandler.removeCallbacks(it) } // 取消未触发的阈值定时器（语法：?.let 安全调用）
        val held = SystemClock.elapsedRealtime() - pending.pressTimeMs // 计算按住时长（毫秒）
        val threshold = steamInput.profile.globalSettings.longPressThresholdMs.toLong() // 读取长按阈值
        val longAction = pending.mapping.longPressAction // 取长按动作
        if (held < threshold) { // 短按：按住时长未达阈值
            if (pending.mapping.layerSwitchOnly && isHoldableAction(pending.mapping.action)) { // 短按动作开启「只切换层」
                suppressLayerKeys(button, pending.layerName) // 先抬起本层切层键（点按内部的释放流程会恢复）
            } // 结束「只切换层」判断
            tapAction(button, pending.mapping.action, pending.mapping.subCommands) // 执行短按动作（保持型点按/触发型触发）
        } else if (longAction != null) { // 长按：已配置长按动作
            if (longHoldActive.remove(button)) { // 保持型长按已按下 → 释放
                releaseButtonInjection(button) // 释放长按注入的按键
            } else if (!longInstantFired.remove(button)) { // 瞬时型尚未触发（阈值回调与松开竞态）→ 补发一次
                if (pending.mapping.longPressLayerSwitchOnly && isHoldableAction(longAction)) { // 长按动作开启「只切换层」
                    suppressLayerKeys(button, pending.layerName) // 先抬起本层切层键（点按内部的释放流程会恢复）
                } // 结束「只切换层」判断
                tapAction(button, longAction, pending.mapping.longPressSubCommands) // 补发执行长按动作（带长按子命令）
            } // 结束长按类型判断
        } else if (steamInput.profile.globalSettings.longPressFallbackToShort) { // 长按未配置动作：回退开关开启
            if (pending.mapping.layerSwitchOnly && isHoldableAction(pending.mapping.action)) { // 回退执行的短按动作开启「只切换层」
                suppressLayerKeys(button, pending.layerName) // 先抬起本层切层键（点按内部的释放流程会恢复）
            } // 结束「只切换层」判断
            tapAction(button, pending.mapping.action, pending.mapping.subCommands) // 松开时执行短按动作（保持型点按/触发型触发）
        } // 否则（未配置长按动作且回退关闭）：什么都不做
        Log.d(TAG, "LongPress end: $button held=${held}ms threshold=${threshold}ms") // 打印判定结束日志
        return true // 已处理
    } // 结束 finishDeferredPress 函数

    /**
     * 释放指定手柄按钮已注入的全部键鼠状态
     *
     * 根据按下时记录的状态精确释放（与当前层映射无关）：
     * - 子命令键（逆序松开）
     * - 主键
     * - 鼠标按钮（MouseClick 按下态）
     *
     * 不处理 [MappedAction.MouseToggle]：toggle 是用户主动锁存机制
     * （按一下按住、再按一下释放），松开手柄键不应改变其状态。
     *
     * @param button 手柄按钮
     */
    private fun releaseButtonInjection(button: ControllerButton) { // 语法：private fun 私有函数，释放指定按钮的全部键鼠状态
        // 0. 切层+按键：解除切层键归属；若该键已被「只切换层」抬起，则不再参与恢复
        heldLayerKeys.remove(button)?.let { key -> liftedLayerKeys.remove(key) } // 语法：?.let 安全调用
        var released = false // 语法：var 可变变量，标记是否发生了释放动作
        // 1. 松开子命令键（逆序）
        pressedSubKeys.remove(button)?.reversed()?.forEach { subKeyCode -> // 取出并移除子命令列表，逆序遍历（语法：?. 安全调用链 + reversed() + forEach lambda）
            injector.sendKeyUp(subKeyCode) // 发送子命令键松开事件
            released = true // 标记已发生释放
        } // 结束子命令松开 lambda
        // 2. 松开主键
        pressedMainKeys.remove(button)?.let { mainKeyCode -> // 取出并移除主键码，非空时执行 lambda（语法：?. 安全调用 + let 作用域函数）
            injector.sendKeyUp(mainKeyCode) // 发送主键松开事件
            released = true // 标记已发生释放
        } // 结束主键释放 lambda
        // 3. 松开鼠标按钮（MouseClick 按下态）
        pressedMouseButtons.remove(button)?.let { mouseButton -> // 取出并移除鼠标按钮，非空时执行 lambda（语法：?. 安全调用 + let）
            injector.sendMouseUp(mouseButton) // 发送鼠标按钮松开事件
            released = true // 标记已发生释放
        } // 结束鼠标按钮释放 lambda
        // 4. 「只切换层」动作松开：本按钮的按键先松开，再恢复此前抬起的切层键
        restoreLayerKeys(button) // 无抑制记录时为空操作
        if (released) { // 语法：if 分支，若本按钮确有释放动作
            Log.d(TAG, "Released button $button injections") // 打印释放日志（语法：字符串模板 "$button"）
        } // 结束 if (released) 分支
    } // 结束 releaseButtonInjection 函数

    /**
     * 判断动作是否支持「只切换层」
     *
     * 只有会向游戏注入按键/鼠标的动作才有「叠加切层键按下的按键」问题：
     * 键盘按键、数字小键盘、鼠标点击、切层+按键（自身也按住一个键）。
     *
     * @param action 映射动作
     */
    private fun isOnlyLayerApplicable(action: MappedAction): Boolean = // 语法：表达式体函数 + || 逻辑或
        action is MappedAction.KeyboardKey || action is MappedAction.NumpadKey || // 键盘/数字小键盘（语法：is 类型判断）
            action is MappedAction.MouseClick || action is MappedAction.SwitchLayerAndKey // 鼠标点击/切层+按键

    /**
     * 注入「切层+按键」同时按下的按键，并记录归属
     *
     * 归属记录用于「只切换层」在这些按键按住期间暂时抬起它们（[suppressLayerKeys]）。
     *
     * @param button 手柄按钮
     * @param keyCode 同时按下的键盘键码（可能为 Shift 符号键）
     * @param subCommands 该映射的子命令列表
     */
    private fun pressLayerKey(button: ControllerButton, keyCode: Int, subCommands: List<Int>) { // 语法：private fun 私有函数
        val actual = if (KeyMapping.isShiftSymbolKey(keyCode)) KeyMapping.shiftSymbolBaseKey(keyCode) else keyCode // 还原符号键的实际键码
        heldLayerKeys[button] = actual // 记录该按钮当前按住的切层键
        handleKeyboardKey(button, true, keyCode, subCommands) // 注入按键按下（含子命令）
        // 已有「只切换层」动作按住：新按下的切层键保持抬起，避免叠加影响该动作输出
        if (onlyLayerSwitchButtons.isNotEmpty() && liftedLayerKeys.add(actual)) { // 集合非空且该键此前未抬起
            injector.sendKeyUp(actual) // 立即抬起该切层键
        } // 结束抑制中判断
    } // 结束 pressLayerKey 函数

    /**
     * 抬起「本层」切层键正按住的键盘键（「只切换层」动作按下时调用）
     *
     * 只抵消当前动作所属层的切层键：例如先按住 R2（切层+Shift）再切到 L2 层，
     * L2 层内开启「只切换层」的动作不会抬起 R2 的 Shift，Shift 仍保持按下。
     *
     * 多个「只切换层」动作同时按住时按集合去重，全部松开后才恢复。
     *
     * @param button 触发「只切换层」的手柄按钮
     * @param layerName 动作所属操作层名（null=公共层，不抵消任何切层键）
     */
    private fun suppressLayerKeys(button: ControllerButton, layerName: String?) { // 语法：private fun 私有函数
        onlyLayerSwitchButtons.add(button) // 记录抑制中的按钮（松开时由 restoreLayerKeys 恢复）
        // 找出本层的切层键：公共层中「切换到该层」的按钮当前正注入的键盘键
        val layerKeys = if (layerName == null) emptyList() else { // 公共层动作不抵消任何切层键
            steamInput.profile.commonLayer.buttonMappings // 公共层按钮映射表
                .filterValues { m -> layerTargetOf(m.action) == layerName } // 只保留切换到本层的切层按钮
                .keys.mapNotNull { heldLayerKeys[it] } // 取这些按钮当前注入的键盘键
        } // 结束层键收集
        layerKeys.forEach { key -> // 遍历本层切层键
            if (liftedLayerKeys.add(key)) injector.sendKeyUp(key) // 逐个抬起（集合去重，避免同一键重复抬）
        } // 结束遍历
        if (layerKeys.isNotEmpty()) Log.d(TAG, "OnlyLayerSwitch: lift keys $layerKeys for layer $layerName") // 打印抬起日志
    } // 结束 suppressLayerKeys 函数

    /** 取「切换层」类动作的目标层名（其它动作返回 null） */
    private fun layerTargetOf(action: MappedAction): String? = when (action) { // 语法：when 表达式直接返回结果
        is MappedAction.SwitchLayer -> action.layerName // 切层动作：返回目标层
        is MappedAction.SwitchLayerAndKey -> action.layerName // 切层+按键动作：返回目标层
        else -> null // 其它动作：不是切层键
    } // 结束 layerTargetOf 函数

    /**
     * 恢复被「只切换层」抬起的切层键（动作松开时调用）
     *
     * 仅当所有「只切换层」动作都松开后才恢复；已被按住的键才重新按下，
     * 避免切层键在手柄键松开后又被按下导致卡键。
     *
     * @param button 触发「只切换层」的手柄按钮
     */
    private fun restoreLayerKeys(button: ControllerButton) { // 语法：private fun 私有函数
        if (!onlyLayerSwitchButtons.remove(button)) return // 该按钮不在抑制中，无需恢复
        if (onlyLayerSwitchButtons.isNotEmpty()) return // 仍有抑制动作按住，保持抬起
        val keys = liftedLayerKeys.toList() // 复制待恢复键列表
        liftedLayerKeys.clear() // 清空抬起记录
        keys.forEach { key -> // 遍历待恢复键
            if (pressedMainKeys.containsValue(key)) injector.sendKeyDown(key) // 仍被按住才重新按下
        } // 结束遍历
        if (keys.isNotEmpty()) Log.d(TAG, "OnlyLayerSwitch: restore layer keys $keys") // 打印恢复日志
    } // 结束 restoreLayerKeys 函数

    /**
     * 处理键盘按键映射（含子命令）
     *
     * 按下时:
     * 1. 按下主键
     * 2. 依次按下所有子命令键
     *
     * 松开时:
     * 1. 依次松开所有子命令键
     * 2. 松开主键
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mainKeyCode 主键 Android KeyCode
     * @param subCommands 子命令 KeyCode 列表
     */
    private fun handleKeyboardKey( // 语法：private fun 私有函数声明，处理键盘按键映射（含子命令）
        button: ControllerButton, // 手柄按钮参数
        isPressed: Boolean, // 是否按下参数
        mainKeyCode: Int, // 主键 Android KeyCode 参数
        subCommands: List<Int> // 子命令 KeyCode 列表参数（语法：List<Int> 整型列表）
    ) { // 函数体开始
        // 识别主键是否为 Shift 符号键（编码 = SHIFT_SYMBOL_OFFSET + 数字键码，如 "!" = Shift+1）
        val isShiftSymbol = KeyMapping.isShiftSymbolKey(mainKeyCode) // 是否 Shift 符号键
        val actualKey = if (isShiftSymbol) KeyMapping.shiftSymbolBaseKey(mainKeyCode) else mainKeyCode // 还原为实际数字键码（语法：if 表达式）
        if (isPressed) { // 语法：if 分支，按下分支
            // 防重复按下
            if (pressedMainKeys.containsKey(button)) return // 若主键已在按下状态则返回，防重复注入（语法：containsKey 映射包含键判断）

            // 1. 按下主键（符号键需先按住左 Shift 修饰）
            val subs = mutableListOf<Int>() // 创建可变列表收集需随主键一起松开的修饰/子命令键（语法：val+mutableListOf 可变列表）
            if (isShiftSymbol) { // 符号键：先按下左 Shift
                injector.sendKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT) // 发送左 Shift 按下事件
                subs.add(KeyEvent.KEYCODE_SHIFT_LEFT) // 记录 Shift 供松开
            } // 结束符号键 Shift 分支
            injector.sendKeyDown(actualKey) // 发送主键（数字键）按下事件
            pressedMainKeys[button] = actualKey // 记录该按钮主键码（语法：Map 下标写入键值）

            // 2. 按下所有子命令键（子命令也可能为符号键，同样需 Shift 修饰）
            subCommands.forEach { subKeyCode -> // 遍历子命令列表（语法：forEach lambda）
                val subShift = KeyMapping.isShiftSymbolKey(subKeyCode) // 子命令是否为符号键
                val subActual = if (subShift) KeyMapping.shiftSymbolBaseKey(subKeyCode) else subKeyCode // 还原子命令实际键码
                if (subShift) { // 符号子命令：按下左 Shift 并记录
                    injector.sendKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT) // 发送左 Shift 按下事件
                    subs.add(KeyEvent.KEYCODE_SHIFT_LEFT) // 记录 Shift 供松开
                } // 结束符号子命令 Shift 分支
                injector.sendKeyDown(subActual) // 发送子命令键按下事件
                subs.add(subActual) // 把子命令键加入记录列表
            } // 结束子命令按下 lambda
            if (subs.isNotEmpty()) { // 语法：if 分支，若存在修饰/子命令键
                pressedSubKeys[button] = subs // 记录该按钮的子命令键列表（语法：Map 下标写入键值）
            } // 结束 if (subs 非空) 分支

            Log.d(TAG, "KeyDown: ${KeyMapping.keyCodeToName(mainKeyCode)}" + // 打印主键按下日志（语法：字符串模板 "${表达式}"）
                    // 若有子命令则追加组合键名称，否则为空字符串
                    if (subCommands.isNotEmpty()) "+${subCommands.map { KeyMapping.keyCodeToName(it) }}" else "") // 有子命令时拼接组合名，否则空串（语法：map lambda + if 表达式）
        } else { // 语法：if-else 分支，松开分支
            // 防重复松开
            val main = pressedMainKeys.remove(button) ?: return // 取出并移除主键码，未按下则返回（语法：?: 空值合并）

            // 1. 松开所有子命令键（逆序，含符号子命令附带的 Shift）
            pressedSubKeys.remove(button)?.reversed()?.forEach { subKeyCode -> // 取出子命令列表并逆序遍历松开（语法：?. 安全调用 + reversed() + forEach）
                injector.sendKeyUp(subKeyCode) // 发送子命令键松开事件
            } // 结束子命令松开 lambda

            // 2. 松开主键
            injector.sendKeyUp(main) // 发送主键松开事件

            Log.d(TAG, "KeyUp: ${KeyMapping.keyCodeToName(main)}" + // 打印主键松开日志（语法：字符串模板 "${表达式}"）
                    if (subCommands.isNotEmpty()) "+${subCommands.map { KeyMapping.keyCodeToName(it) }}" else "") // 有子命令时拼接组合名，否则空串（语法：map lambda + if 表达式）
        } // 结束 if-else 按下/松开分支
    } // 结束 handleKeyboardKey 函数

    /**
     * 处理鼠标点击映射
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mouseButton 鼠标按钮
     */
    private fun handleMouseClick(button: ControllerButton, isPressed: Boolean, mouseButton: MouseButton) { // 语法：private fun 私有函数，处理鼠标点击映射
        if (isPressed) { // 语法：if 分支，按下分支
            if (pressedMouseButtons.containsKey(button)) return // 若该按钮已按下则返回，防重复（语法：containsKey 映射包含键判断）
            markMouseActionLayer(button, mouseButton) // 记录当前激活层内按下过鼠标左右键（用于层停用时决定是否释放）
            injector.sendMouseDown(mouseButton) // 发送鼠标按钮按下事件
            pressedMouseButtons[button] = mouseButton // 记录手柄按钮→鼠标按钮（语法：Map 下标写入键值）
            Log.d(TAG, "MouseDown: $mouseButton") // 打印鼠标按下日志（语法：字符串模板 "$mouseButton"）
        } else { // 语法：if-else 分支，松开分支
            val mb = pressedMouseButtons.remove(button) ?: return // 取出并移除鼠标按钮，未按下则返回（语法：?: 空值合并）
            injector.sendMouseUp(mb) // 发送鼠标按钮松开事件
            Log.d(TAG, "MouseUp: $mb") // 打印鼠标松开日志（语法：字符串模板 "$mb"）
        } // 结束 if-else 分支
    } // 结束 handleMouseClick 函数

    /**
     * 处理鼠标长按切换映射
     *
     * 第一次按下 → 发送 MouseDown（不松开）
     * 第二次按下 → 先发送鼠标居中，再发送 MouseUp（不再按下）
     * 松开手柄键不触发任何操作（保持 toggle 状态）
     *
     * 锁存状态按鼠标按钮全局记录（[toggledMouseButtons]），与左摇杆移动
     * 自动锁定右键共用：无论由谁锁定的右键，再按一次该映射键即解锁。
     *
     * @param button 手柄按钮
     * @param mouseButton 鼠标按钮
     */
    private fun handleMouseToggle(button: ControllerButton, mouseButton: MouseButton) { // 语法：private fun 私有函数，处理鼠标长按切换映射
        markMouseActionLayer(button, mouseButton) // 记录当前激活层内长按过鼠标左右键（用于层停用时决定是否释放）
        val locked = toggledMouseButtons.contains(mouseButton) // 当前是否已锁存（语法：contains 集合包含判断）
        setMouseLockState(mouseButton, !locked) // 反转锁存状态——解除锁定必须在居中之前立即发出
        // 解除锁定时在解锁之后再居中：解锁报文立即发出（让游戏尽快退出 mouselook），
        // 居中延后 [unlockCenterDelayMs] 再发，避免被游戏侧的光标接管覆盖（鼠标长按交换不受此影响）
        if (locked) { // 语法：if 分支，仅从锁定变为解除时需要在解锁后居中
            mainHandler.postDelayed({ injector.sendMouseCenterExact() }, unlockCenterDelayMs) // 延时居中央（语法：postDelayed 延迟投递）
        } // 结束解锁居中分支
        Log.d(TAG, if (locked) "Toggle MouseUp: $mouseButton" else "Toggle MouseDown: $mouseButton") // 打印 toggle 日志（语法：if 表达式 + 字符串模板）
    } // 结束 handleMouseToggle 函数

    /**
     * 处理鼠标长按交换映射
     *
     * 检查左右键的长按锁存状态并交换：
     * - 右键长按中 → 释放右键，改为长按左键
     * - 左键长按中 → 释放左键，改为长按右键
     * - 左右键都未长按 → 不做任何处理
     *
     * 与「鼠标长按」共用全局锁存标志 [toggledMouseButtons]，交换后仍可由
     * 各自的长按映射键解锁（见 [handleMouseToggle]）。
     */
    private fun handleMouseSwapToggle() { // 语法：private fun 私有函数，交换左右键的长按锁存状态
        when { // 语法：when 无参数分支表达式，按条件匹配
            toggledMouseButtons.contains(MouseButton.RIGHT) -> { // 右键长按中 → 换成长按左键
                setMouseLockState(MouseButton.RIGHT, false) // 释放右键（内部发送 F12 解锁）
                setMouseLockState(MouseButton.LEFT, true) // 按下并锁存左键
                Log.d(TAG, "Swap MouseToggle: RIGHT -> LEFT") // 打印交换日志
            } // 结束右键分支
            toggledMouseButtons.contains(MouseButton.LEFT) -> { // 左键长按中 → 换成长按右键
                setMouseLockState(MouseButton.LEFT, false) // 释放左键
                setMouseLockState(MouseButton.RIGHT, true) // 锁存右键（内部发送 F11 锁定）
                Log.d(TAG, "Swap MouseToggle: LEFT -> RIGHT") // 打印交换日志
            } // 结束左键分支
            else -> { // 语法：else 兜底分支，左右键均未长按
                Log.d(TAG, "Swap MouseToggle: no active hold, ignored") // 打印忽略日志
            } // 结束 else 分支
        } // 结束 when 分支
    } // 结束 handleMouseSwapToggle 函数

    /**
     * 处理释放鼠标所有长按动作
     *
     * 遍历全局锁存集合 [toggledMouseButtons]，先逐个发送 MouseUp 并清除锁存记录，
     * 再把鼠标光标移到屏幕正中央（顺序：先解锁、后居中），最后通知 [onMouseToggleChanged]
     * 使悬浮窗边框恢复默认色。集合为空（无任何长按锁存）时不做任何处理。
     */
    private fun handleMouseReleaseAll() { // 语法：private fun 私有函数，释放所有鼠标长按锁存
        if (toggledMouseButtons.isEmpty() && remoteLockedButtons.isEmpty()) { // 本地与远端都没有长按锁存
            Log.d(TAG, "Release all MouseToggles: none active, ignored") // 打印忽略日志
            return // 直接返回
        } // 结束空集合判断
        val released = toggledMouseButtons.toList() // 复制当前本地锁存按钮列表（复制后逐个释放，避免遍历中修改）
        released.forEach { button -> // 逐个释放（语法：forEach 遍历 + lambda）
            setMouseLockState(button, false) // 释放该按钮（右键会改发 F12 解锁）——解锁立即发出
        } // 结束逐个释放遍历
        // 通知客户端：解除它那边的锁存，并结束「临时禁用 WASD 自动锁定」（客户端据此归位+重新锁定）
        // 无条件发送：即使远端没锁存，客户端也需要这个信号来恢复 WASD 自动锁定
        onRequestRemoteUnlock?.invoke() // 语法：?. 安全调用
        // 解锁之后再居中，且延后 [unlockCenterDelayMs]：让游戏先处理完退出 mouselook 再放指针
        mainHandler.postDelayed({ injector.sendMouseCenterExact() }, unlockCenterDelayMs) // 延时居中央（语法：postDelayed 延迟投递）
        Log.d(TAG, "Release all MouseToggles: $released, remote=$remoteLockedButtons") // 打印释放日志
    } // 结束 handleMouseReleaseAll 函数

    /**
     * 处理「右键锁定切换」动作（视角锁定 / mouselook 开关）
     *
     * 按当前状态执行以下动作之一：
     * | 当前状态 | 行为 |
     * | --- | --- |
     * | 右键未锁定 | 光标停到屏幕左下角之外，发送 MouseDown(RIGHT) 进入视角锁定 |
     * | 右键已锁定 | 松开右键，等待 [unlockCenterDelayMs] 让游戏退出 mouselook，再把光标移到屏幕正中央 |
     * | 左键锁存中 | 不执行任何动作（手柄按键本身不会透传给游戏） |
     *
     * 锁存状态取本地与远端（Rust 上报）的合并视图 [isMouseLocked]。解锁时两套锁存
     * 各自独立解除：本地有锁存就发真实 MouseUp，远端有锁存就发 0x26 让客户端松开，
     * 不能只解其中一份，否则合并视图会一直停留在「锁定」。
     */
    private fun handleRightLockToggle() { // 语法：private fun 私有函数，右键锁定切换
        if (isMouseLocked(MouseButton.LEFT)) { // 左键锁存中：什么也不做
            Log.d(TAG, "RightLockToggle: left button locked, ignored") // 打印忽略日志
            return // 直接返回
        } // 结束左键锁存判断
        if (isMouseLocked(MouseButton.RIGHT)) { // 右键已锁定 → 解锁 + 延时居中
            // 锁存分本地与远端两份，必须都解除：任一残留都会让合并视图继续显示「锁定」，
            // 边框不恢复、WASD 自动锁定被跳过，只能再按一次客户端快捷键才能自愈
            if (toggledMouseButtons.contains(MouseButton.RIGHT)) { // 本地锁存：发送真实 MouseUp
                setMouseLockState(MouseButton.RIGHT, false) // 释放右键——立即发出
            } // 结束本地锁存判断
            if (remoteLockedButtons.contains(MouseButton.RIGHT)) { // 远端（Rust）锁存：通知客户端松开
                onRequestRemoteUnlock?.invoke() // 0x26：客户端松开并自行延时居中
            } // 结束远端锁存判断
            mainHandler.postDelayed({ injector.sendMouseCenterExact() }, unlockCenterDelayMs) // 延时把光标移到屏幕正中央
            Log.d(TAG, "RightLockToggle: MouseUp RIGHT, center after ${unlockCenterDelayMs}ms") // 打印解锁日志
        } else { // 未锁定 → 停到屏幕外 + 按下右键
            injector.sendMouseCenter(true) // 固定停到屏幕左下角之外（锁定瞬间视角不会从偏移位置开始漂移）
            setMouseLockState(MouseButton.RIGHT, true) // 锁存右键（发送 MouseDown）
            Log.d(TAG, "RightLockToggle: MouseDown RIGHT (cursor parked offscreen)") // 打印锁定日志
        } // 结束状态分支
    } // 结束 handleRightLockToggle 函数

    /**
     * 应用 Windows 客户端上报的锁存状态（0x28 报文）
     *
     * 只更新远端记录与悬浮窗边框，不注入任何鼠标事件：客户端已经发出过真实的
     * 按下/松开事件，APK 再发一次会造成重复按键。
     *
     * @param leftLocked 客户端侧左键是否处于锁存
     * @param rightLocked 客户端侧右键是否处于锁存
     * @param wasdSuspended 客户端是否临时禁用 WASD 自动锁定（「解锁+居中+按键」指向型技能）
     */
    fun applyRemoteLockState(leftLocked: Boolean, rightLocked: Boolean, wasdSuspended: Boolean) { // 语法：fun 公开函数，供服务在收到上报时调用
        applyRemoteLock(MouseButton.LEFT, leftLocked) // 同步左键
        applyRemoteLock(MouseButton.RIGHT, rightLocked) // 同步右键
        if (remoteWasdLockSuspended != wasdSuspended) { // 挂起状态变化才打日志（便于排查）
            Log.d(TAG, "Remote WASD lock suspend: $wasdSuspended") // 打印客户端挂起状态
        }
        remoteWasdLockSuspended = wasdSuspended // 同步客户端的「临时禁用 WASD 自动锁定」
        // 客户端已明确解锁（例如「解锁+居中+按键」）：本地残留会被合并视图继续当作锁定，
        // 导致边框一直橙红、WASD 判断失效，这里以客户端上报为权威清掉本地残留
        if (!leftLocked) clearLocalLockIfStale(MouseButton.LEFT) // 左键对齐
        if (!rightLocked) clearLocalLockIfStale(MouseButton.RIGHT) // 右键对齐
        if (pendingLockResync) { // 刚（重）连接：客户端状态已就绪，可以对齐物理鼠标状态
            pendingLockResync = false // 只对齐一次
            resyncMouseLockState() // 重放本地锁存，修复断线期间丢失的按下/松开
        } // 结束待同步判断
    } // 结束 applyRemoteLockState 函数

    /**
     * 客户端上报「未锁存」时清掉本地残留锁存
     *
     * 只清标志、不注入 MouseUp：客户端已经松开过，再发会重复。刚锁定不到 [STALE_LOCK_MS]
     * 的本地锁存跳过，避免被尚未收到 0x03 的滞后上报误清（客户端 200ms 轮询上报）。
     */
    private fun clearLocalLockIfStale(button: MouseButton) { // 语法：private fun 私有函数
        if (!toggledMouseButtons.contains(button)) return // 本地没有锁存：无需清理
        val lockedAt = toggledMouseAt[button] ?: return // 没有时间戳（旧状态）：不清理
        if (SystemClock.uptimeMillis() - lockedAt < STALE_LOCK_MS) return // 刚锁定：忽略可能滞后的上报
        toggledMouseButtons.remove(button) // 清除本地锁存标志
        toggledMouseAt.remove(button) // 清除时间戳
        refreshToggleNotify(button) // 按合并视图刷新边框
        Log.d(TAG, "Remote cleared local lock: $button") // 打印对齐日志
    } // 结束 clearLocalLockIfStale 函数

    /**
     * 客户端已连接（含重连）
     *
     * 断线期间的鼠标报文会丢失，两端物理状态可能不一致。这里只做标记，
     * 等客户端回传一次 0x28 锁存状态后再对齐（见 [applyRemoteLockState]），
     * 避免在不知道客户端锁存时贸然补发 MouseUp 把客户端自己的锁定解开。
     */
    fun onBridgeConnected() { // 语法：fun 公开函数，由服务在客户端连接时调用
        pendingLockResync = true // 标记待同步（等首次 0x28）
    } // 结束 onBridgeConnected 函数

    /**
     * 客户端已断开
     *
     * 远端锁存记录只能靠上报更新，断开后不会再有上报：继续保留会让合并视图
     * 永远停在「锁定」，导致边框不恢复、WASD 自动锁定被跳过，所以这里清空。
     * 本地锁存（[toggledMouseButtons]）是 APK 自己的状态，不动。
     */
    fun onBridgeDisconnected() { // 语法：fun 公开函数，由服务在客户端断开时调用
        pendingLockResync = false // 断开后不能再等 0x28
        if (remoteLockedButtons.isEmpty()) return // 无远端锁存则无需清理
        remoteLockedButtons.clear() // 清空远端锁存记录
        refreshToggleNotify(MouseButton.LEFT) // 按合并视图刷新左键边框
        refreshToggleNotify(MouseButton.RIGHT) // 按合并视图刷新右键边框
        Log.d(TAG, "Bridge disconnected: remote lock state cleared") // 打印清理日志
    } // 结束 onBridgeDisconnected 函数

    /**
     * 按「本地锁存 + 远端锁存」对齐鼠标按钮的物理按下状态（重连后调用）
     *
     * - 本地锁存 → 补发 MouseDown，确保断线期间未送达的按下生效
     * - 两端都未锁 → 补发 MouseUp，确保断线期间残留的按下被释放
     * - 仅远端锁存 → 不干预（客户端自己维护它的锁存）
     *
     * 正被按住（如 MouseClick 按住）的按钮跳过，避免打断进行中的按键。
     */
    private fun resyncMouseLockState() { // 语法：private fun 私有函数
        listOf(MouseButton.LEFT, MouseButton.RIGHT).forEach { button -> // 逐个按钮对齐
            if (pressedMouseButtons.containsValue(button)) return@forEach // 正在按住：跳过（语法：return@forEach 结束本次循环）
            when { // 语法：when 无参数分支
                toggledMouseButtons.contains(button) -> injector.sendMouseDown(button, latch = true) // 本地锁存：恢复物理按下并同步客户端标志
                !remoteLockedButtons.contains(button) -> injector.sendMouseUp(button, latch = true) // 两端都未锁：确保释放并同步客户端标志
                else -> {} // 仅远端锁存：客户端维护，不干预
            } // 结束状态分支
        } // 结束逐按钮对齐
        Log.d(TAG, "Resync mouse lock: local=$toggledMouseButtons remote=$remoteLockedButtons") // 打印重同步日志
    } // 结束 resyncMouseLockState 函数

    /** 更新单个按钮的远端锁存记录，并在合并视图变化时通知悬浮窗 */
    private fun applyRemoteLock(button: MouseButton, locked: Boolean) { // 语法：private fun 私有函数
        val changed = if (locked) remoteLockedButtons.add(button) else remoteLockedButtons.remove(button) // 更新集合（语法：if 表达式）
        if (changed) { // 状态确有变化才通知
            refreshToggleNotify(button) // 按合并视图回调边框高亮
            Log.d(TAG, "Remote lock state: $button=$locked") // 打印远端状态日志
        } // 结束变化判断
    } // 结束 applyRemoteLock 函数

    /** 按钮是否处于锁存（本地锁存 或 客户端上报的远端锁存） */
    private fun isMouseLocked(button: MouseButton): Boolean = // 语法：表达式体函数 + || 逻辑或
        toggledMouseButtons.contains(button) || remoteLockedButtons.contains(button) // 合并两套锁存

    /**
     * 记录当前激活的操作层「触发过鼠标左右键动作」的手柄按钮
     *
     * 仅在鼠标按钮为左键/右键时记录，供 [releaseMouseStateOnLayerDeactivated] 判断与释放。
     *
     * @param button 手柄按钮
     * @param mouseButton 鼠标按钮
     */
    private fun markMouseActionLayer(button: ControllerButton, mouseButton: MouseButton) { // 语法：private fun 私有函数，标记层内触发过鼠标左右键
        if (mouseButton != MouseButton.LEFT && mouseButton != MouseButton.RIGHT) return // 仅左右键需要标记（语法：&& 逻辑与）
        steamInput.getActiveLayers().forEach { layer -> // 标记所有当前激活层
            layerMouseActionButtons.getOrPut(layer) { mutableSetOf() }.add(button) // 记录「层 → 按钮」
        } // 结束遍历
    } // 结束 markMouseActionLayer 函数

    /**
     * 操作层停用时按条件释放鼠标左右键状态
     *
     * 只有当该层在激活期间确实点击或长按过鼠标左键/右键时才释放：
     * - 该层内按下过鼠标点击的按钮 → 立即补发 MouseUp（不必等手柄键松开，
     *   因为该手柄键松开时可能已查不到该层映射，导致鼠标一直卡在按下态）；
     * - 该层内操作过鼠标长按 → 释放所有左右键长按锁存（先解锁、后居中）；
     * - 只是按下切层键、未操作过鼠标左右键 → 不释放，保留用户原有的鼠标状态。
     *
     * @param layer 被停用的操作层；null 表示一次性停用全部层（切换操作集/销毁）
     */
    private fun releaseMouseStateOnLayerDeactivated(layer: OperationLayer?) { // 语法：private fun 私有函数，按条件释放鼠标状态
        val buttons = if (layer == null) { // 语法：if 表达式，区分单层停用与全部停用
            if (layerMouseActionButtons.isEmpty()) emptySet() // 无任何层记录
            else { // 有层记录
                val all = layerMouseActionButtons.values.flatten().toSet() // 汇总所有按钮
                layerMouseActionButtons.clear() // 清空记录
                all // 返回汇总结果
            } // 结束记录判断
        } else { // 单层停用
            layerMouseActionButtons.remove(layer) ?: emptySet() // 取出该层按钮记录（无则空集合）
        } // 结束 if 表达式
        if (buttons.isEmpty()) { // 该层未操作过鼠标左右键
            Log.d(TAG, "Layer deactivated: no mouse click in layer, skip release") // 打印跳过日志
            return // 直接返回，不释放鼠标状态
        } // 结束未触发判断
        // 1. 立即补发该层内鼠标点击的松开事件（避免卡在按下态）
        buttons.forEach { button -> // 遍历该层内操作过鼠标的按钮
            pressedMouseButtons.remove(button)?.let { mouseButton -> // 取出并移除按下记录
                injector.sendMouseUp(mouseButton) // 补发鼠标松开
                Log.i(TAG, "Layer deactivated: force MouseUp $mouseButton ($button)") // 打印强制松开日志
            } // 结束按下记录判断
        } // 结束遍历
        // 2. 释放该层内长按锁存的左右键（先解锁、后居中）
        if (toggledMouseButtons.isNotEmpty()) handleMouseReleaseAll() // 有长按锁存才释放
    } // 结束 releaseMouseStateOnLayerDeactivated 函数

    /**
     * 进入精细移动模式（按住该映射键期间生效）
     *
     * 右摇杆位移按 [FINE_MOVE_FACTOR] 缩小，用于在 Winlator 中精确对准目标。
     *
     * @param button 手柄按钮
     */
    private fun enterFineMove(button: ControllerButton) { // 语法：private fun 私有函数，进入精细移动
        if (fineMoveButtons.add(button)) { // 本次按下才算新进入（语法：add 返回是否新增）
            fineMoveActive = true // 开启精细移动标志
            Log.d(TAG, "FineMove enter: $button") // 打印进入日志
        } // 结束新增判断
    } // 结束 enterFineMove 函数

    /**
     * 退出精细移动模式（该映射键松开时调用，全部松开后恢复普通灵敏度）
     *
     * @param button 手柄按钮
     */
    private fun clearFineMove(button: ControllerButton) { // 语法：private fun 私有函数，退出精细移动
        if (fineMoveButtons.remove(button)) { // 仅当该按钮确实处于精细移动中才处理
            fineMoveActive = fineMoveButtons.isNotEmpty() // 仍有其它按钮按住时保持精细移动
            Log.d(TAG, "FineMove exit: $button") // 打印退出日志
        } // 结束移除判断
    } // 结束 clearFineMove 函数

    /**
     * 一键定角转身：把一次水平鼠标位移拆成多小步注入
     *
     * 直接发送一个很大的位移，部分游戏/系统会按单帧上限裁剪或做指针加速，
     * 导致实际转角远小于预期；拆成 [TURN_STEPS] 步、每步间隔 [stepMs] 毫秒
     * 后与真实鼠标快速拖动的效果一致。总耗时 = 步数 × 间隔。
     *
     * @param pixels 水平位移像素（正=向右，负=向左）
     * @param stepMs 每步之间的间隔毫秒数（越小转得越快）
     */
    private fun turnByPixels(pixels: Int, stepMs: Int) { // 语法：private fun 私有函数，定角转身
        if (pixels == 0) return // 位移为 0 直接返回
        val gap = stepMs.coerceIn(1, 50) // 间隔限制在 1~50ms，避免填 0 空转或过大导致明显延迟
        // 在后台线程分步发送，避免阻塞手柄事件回调线程（回调必须尽快返回）
        Thread {
            var sent = 0 // 已发送的累计位移
            for (i in 1..TURN_STEPS) { // 按步数切分总位移
                val target = (pixels.toLong() * i / TURN_STEPS).toInt() // 第 i 步后应达到的累计位移
                val dx = target - sent // 本次需要发送的增量（余数不会丢失）
                if (dx != 0) injector.sendMouseMove(dx.toFloat(), 0f) // 发送本步水平位移
                sent += dx // 累计已发送
                try {
                    Thread.sleep(gap.toLong()) // 步间间隔，模拟真实拖动
                } catch (_: InterruptedException) {
                    return@Thread // 被中断则结束
                }
            }
            Log.d(TAG, "Turn180 sent ${pixels}px, step=${gap}ms") // 打印转身完成日志
        }.start()
    } // 结束 turnByPixels 函数

    /**
     * 进入快速移动模式（按住该映射键期间生效）
     *
     * 右摇杆位移按 [FAST_MOVE_FACTOR] 放大，用于低灵敏度下快速转身。
     * 与精细移动同时按住时仍以精细移动为准（由 [processLookTick] 保证）。
     *
     * @param button 手柄按钮
     */
    private fun enterFastMove(button: ControllerButton) { // 语法：private fun 私有函数，进入快速移动
        if (fastMoveButtons.add(button)) { // 本次按下才算新进入（语法：add 返回是否新增）
            fastMoveActive = true // 开启快速移动标志
            Log.d(TAG, "FastMove enter: $button") // 打印进入日志
        } // 结束新增判断
    } // 结束 enterFastMove 函数

    /**
     * 退出快速移动模式（该映射键松开时调用，全部松开后恢复普通灵敏度）
     *
     * @param button 手柄按钮
     */
    private fun clearFastMove(button: ControllerButton) { // 语法：private fun 私有函数，退出快速移动
        if (fastMoveButtons.remove(button)) { // 仅当该按钮确实处于快速移动中才处理
            fastMoveActive = fastMoveButtons.isNotEmpty() // 仍有其它按钮按住时保持快速移动
            Log.d(TAG, "FastMove exit: $button") // 打印退出日志
        } // 结束移除判断
    } // 结束 clearFastMove 函数

    /**
     * 设置某个鼠标按钮的长按锁存状态（幂等）
     *
     * 与当前状态一致时不发送任何事件，并同步全局锁存集合与悬浮窗边框回调。
     * - 左右键：锁定时发送 MouseDown，释放时发送 MouseUp
     *
     * @param button 鼠标按钮
     * @param locked 目标状态（true=锁定，false=释放）
     */
    private fun setMouseLockState(button: MouseButton, locked: Boolean) { // 语法：private fun 私有函数，设置鼠标长按锁存状态
        if (toggledMouseButtons.contains(button) == locked) return // 状态一致，无需变更
        if (locked) { // 需要锁定
            injector.sendMouseDown(button, latch = true) // 锁定：发送真实鼠标按下（标记为锁存，客户端同步其标志）
            toggledMouseButtons.add(button) // 记入锁存集合
            toggledMouseAt[button] = SystemClock.uptimeMillis() // 记录锁定时间（用于忽略滞后的远端上报）
            refreshToggleNotify(button) // 通知回调（按本地+远端合并视图）
        } else { // 需要释放
            injector.sendMouseUp(button, latch = true) // 解锁：发送真实鼠标松开（客户端同步清除其标志）
            toggledMouseButtons.remove(button) // 移出锁存集合
            toggledMouseAt.remove(button) // 清除锁定时间
            refreshToggleNotify(button) // 通知回调（远端仍锁存时保持高亮）
        } // 结束状态分支
    } // 结束 setMouseLockState 函数

    /**
     * 按「本地锁存 + 远端锁存」的合并状态刷新外部通知
     *
     * 只在合并状态真正变化时回调 [onMouseToggleChanged]，避免本地解锁但远端仍锁存时
     * 错误地把悬浮窗边框恢复成普通色。
     */
    private fun refreshToggleNotify(button: MouseButton) { // 语法：private fun 私有函数
        val locked = isMouseLocked(button) // 合并后的锁定状态
        val wasLocked = notifiedLockedButtons.contains(button) // 上次通知过的状态
        if (locked == wasLocked) return // 状态未变，无需通知
        if (locked) notifiedLockedButtons.add(button) else notifiedLockedButtons.remove(button) // 记录本次状态
        onMouseToggleChanged?.invoke(button, locked) // 通知外部更新悬浮窗边框（语法：?. 安全调用）
    } // 结束 refreshToggleNotify 函数

    /**
     * 处理鼠标交互键动作
     *
     * 执行序列（顺序不可调整）：
     * 1. 记录左右键的长按锁存状态，并让 Windows 端记录当前鼠标光标位置；
     * 2. 若左右键处于锁定则先解锁（**立即发出**），再把鼠标移到屏幕正中央，然后左键点击一下、右键点击一下；
     * 3. 最后把鼠标移回原位置，再恢复记录时的长按锁存状态。
     *
     * 居中固定用屏幕正中央，不受「居中落点」开关影响；解锁立即发出，第 2 步的居中
     * 与之后的点击/还原/恢复锁存整体延后 [unlockCenterDelayMs]（WoW 退出 mouselook 需要时间，
     * 否则居中会被游戏侧的光标接管覆盖），相对顺序不变。
     */
    private fun handleMouseClickSequence() { // 语法：private fun 私有函数，执行鼠标交互键序列
        val leftLocked = toggledMouseButtons.contains(MouseButton.LEFT) // 记录左键锁存状态
        val rightLocked = toggledMouseButtons.contains(MouseButton.RIGHT) // 记录右键锁存状态
        injector.sendCursorSave() // 1. 记录当前光标位置（Windows 客户端保存，末尾用于还原）
        if (leftLocked || rightLocked) { // 2. 仅在锁定时才需要先解锁
            setMouseLockState(MouseButton.LEFT, false) // 临时释放左键锁存（未锁定则无操作）——立即发出
            setMouseLockState(MouseButton.RIGHT, false) // 临时释放右键锁存（未锁定则无操作）——立即发出
        } // 结束解锁分支
        // 解锁之后延后 [unlockCenterDelayMs] 再走「居中 → 左右键点击 → 还原位置 → 恢复锁存」，
        // 让游戏先处理完退出 mouselook；序列内部相对顺序不变
        mainHandler.postDelayed({ // 语法：postDelayed 延迟投递，延后执行整段序列
            injector.sendMouseCenterExact() // 居中：把鼠标光标移到屏幕正中央
            injector.sendMouseDown(MouseButton.LEFT) // 左键点击：按下
            injector.sendMouseUp(MouseButton.LEFT) // 左键点击：松开
            injector.sendMouseDown(MouseButton.RIGHT) // 右键点击：按下
            injector.sendMouseUp(MouseButton.RIGHT) // 右键点击：松开
            injector.sendCursorRestore() // 3. 把鼠标光标移回原位置
            setMouseLockState(MouseButton.LEFT, leftLocked) // 恢复左键原有锁存状态
            setMouseLockState(MouseButton.RIGHT, rightLocked) // 恢复右键原有锁存状态
            Log.d(TAG, "Interact key done: L=$leftLocked R=$rightLocked") // 打印完成日志
        }, unlockCenterDelayMs) // 延时结束（语法：postDelayed 的第二参数）
    } // 结束 handleMouseClickSequence 函数


    /**
     * 左摇杆移动（WASD）时确保鼠标右键处于锁定状态
     *
     * 移动触发时检查全局锁存状态（[toggledMouseButtons]）：
     * - 右键已锁定 → 不做任何事，避免重复发送 MouseDown
     * - 右键未锁定 → 发送 MouseDown(RIGHT) 并记为锁存
     *
     * 锁定后不随停止移动自动松开，与「鼠标长按」一致，
     * 需再按一次映射到右键长按的手柄键解锁（见 [handleMouseToggle]）。
     */
    private fun ensureRightButtonLockedOnMove() { // 语法：private fun 私有函数，左摇杆移动时自动锁定鼠标右键
        if (isMouseLocked(MouseButton.LEFT)) return // 左键长按锁存时不做自动锁定右键（避免与左键锁存冲突）
        if (remoteWasdLockSuspended) return // 客户端「解锁+居中+按键」临时禁用中：APK 同样不自动锁定
        if (isMouseLocked(MouseButton.RIGHT)) return // 右键已锁定（含客户端上报的锁存）：不重复锁定（语法：if 单行返回）
        if (pressedMouseButtons.containsValue(MouseButton.RIGHT)) return // 右键正被临时按住（MouseClick 动作）：不重复按下，避免其松开时打断锁定态（语法：containsValue 映射值包含判断）
        // 开关开启时把鼠标光标归位（落点由「居中落点」开关决定：屏幕外下方 / 屏幕正中央）
        if (steamInput.profile.globalSettings.wasdAutoCenterMouse) injector.sendMouseCenter(steamInput.profile.globalSettings.cursorParkOffscreen) // 避免锁定瞬间视角从偏移位置开始漂移（语法：if 单行语句）
        setMouseLockState(MouseButton.RIGHT, true) // 锁存右键（内部发送 F11 锁定，不发真实鼠标右键按下）
        Log.d(TAG, "Auto MouseDown: RIGHT (left stick move)") // 打印自动锁定日志
    } // 结束 ensureRightButtonLockedOnMove 函数

    /**
     * 处理鼠标滚轮映射
     *
     * 每次按下发送一次滚轮事件（不需要松开）。
     *
     * @param delta 滚轮增量（正数=上滚，负数=下滚）
     */
    private fun handleMouseScroll(delta: Float) { // 语法：private fun 私有函数，处理鼠标滚轮映射
        injector.sendMouseScroll(delta) // 发送滚轮事件（正数=上滚，负数=下滚）
        Log.d(TAG, "MouseScroll: delta=$delta") // 打印滚轮日志（语法：字符串模板 "$delta"）
    } // 结束 handleMouseScroll 函数

    /**
     * 处理摇杆事件
     *
     * 根据摇杆类型和配置决定:
     * - 右摇杆 → 视角控制（LookAround），发送鼠标相对移动
     * - 左摇杆 → 鼠标移动（MouseMove），发送鼠标相对移动（如配置）
     *
     * @param stick 摇杆类型
     * @param x X轴值 (-1.0~1.0)
     * @param y Y轴值 (-1.0~1.0)
     */
    private fun handleStick(stick: ControllerStick, x: Float, y: Float) { // 语法：private fun 私有函数，处理摇杆事件
        when (stick) { // 语法：when 分支，按摇杆类型分发
            ControllerStick.RIGHT_STICK -> { // 右摇杆分支（视角控制）
                // 右摇杆 → 视角控制（LookAround）
                // 只记录最新位置，实际发送由固定频率循环 [processLookTick] 消费。
                // 事件驱动即时发送时，MotionEvent 到达时间抖动会使鼠标包时间分布不均，
                // 游戏内镜头一顿一顿；固定 tick 发送让包节奏稳定，还原真实鼠标的高频均匀位移。
                latestLookX = x // 更新右摇杆最新 X 位置
                latestLookY = y // 更新右摇杆最新 Y 位置
            } // 结束右摇杆分支
            ControllerStick.LEFT_STICK -> { // 左摇杆分支（WASD 8方向移动）
                // 左摇杆 → WASD 8方向映射（固定映射，不随操作层变化）
                // 8方向: 上(W)/下(S)/左(A)/右(D)/左上(A+W)/右上(D+W)/左下(A+S)/右下(D+S)
                val threshold = 0.5f // 摇杆触发阈值，超过才视为按下方向（语法：val 只读变量，0.5f=Float 字面量）
                val newKeys = mutableSetOf<Int>() // 本次计算出的新方向键集合（语法：val+mutableSetOf 可变集合）
                if (x > threshold) newKeys.add(KeyEvent.KEYCODE_D) // 摇杆右偏：加入 D 键（语法：if 单行语句）
                else if (x < -threshold) newKeys.add(KeyEvent.KEYCODE_A) // 摇杆左偏：加入 A 键（语法：else if）
                if (y > threshold) newKeys.add(KeyEvent.KEYCODE_S) // 摇杆下偏：加入 S 键（语法：if 单行语句）
                else if (y < -threshold) newKeys.add(KeyEvent.KEYCODE_W) // 摇杆上偏：加入 W 键（语法：else if）

                // WASD 触发（正在移动）→ 开启「WASD 自动锁定」时确保鼠标右键已锁定；摇杆回中（无方向键）不做处理，
                // 锁定态保持到用户按「鼠标长按」映射键解锁。
                if (newKeys.isNotEmpty() && steamInput.profile.globalSettings.wasdAutoRightLock) ensureRightButtonLockedOnMove() // 开关开启且有方向键触发时检查并自动锁定右键（语法：&& 逻辑与 + isNotEmpty() 集合非空判断）

                // 释放不再按下的方向键
                for (key in leftStickPressedKeys) { // 遍历旧集合中的方向键（语法：for(x in y) 遍历）
                    if (key !in newKeys) injector.sendKeyUp(key) // 旧键不在新集合中则松开（语法：!in 非成员判断 + if 单行语句）
                } // 结束旧键遍历 for
                // 按下新增的方向键
                for (key in newKeys) { // 遍历新集合中的方向键（语法：for(x in y) 遍历）
                    if (key !in leftStickPressedKeys) injector.sendKeyDown(key) // 新键不在旧集合中则按下（语法：!in 非成员判断）
                } // 结束新键遍历 for
                leftStickPressedKeys.clear() // 清空旧集合
                leftStickPressedKeys.addAll(newKeys) // 将新方向键集合整体写入
            } // 结束左摇杆分支
            ControllerStick.DPAD_AS_STICK -> { // D-Pad 当作摇杆的分支
                // D-Pad 作为摇杆 → 不处理
            } // 结束 D-Pad 分支
        } // 结束 when 摇杆类型分支
    } // 结束 handleStick 函数

    // ====================================================================
    // 右摇杆固定频率发送循环（业界标准做法: Steam Input / DS4Windows）
    // ====================================================================

    /**
     * 启动右摇杆固定频率发送循环
     *
     * 专用守护线程，自校正 tick：
     * - 每 [LOOK_TICK_MS] 毫秒执行一次 [processLookTick]
     * - 位移按**实际经过时间** dt 计算（tick 被系统调度延迟时总量不变，不丢量）
     * - 循环超时则跳过 sleep 立即进入下一轮（丢帧不堆积延迟）
     */
    private fun startLookLoop() { // 语法：private fun 私有函数，启动右摇杆发送循环线程
        if (lookThreadRunning) return // 若循环已在运行则直接返回，防止重复启动（语法：if 单行语句）
        lookThreadRunning = true // 置循环运行标志为 true
        lookThread = Thread({ // 创建线程，参数为线程体 lambda（语法：Thread 构造 + lambda 作为 Runnable）
            var lastTickNanos = SystemClock.elapsedRealtimeNanos() // 记录上一 tick 的纳秒时间戳（语法：var 可变变量）
            try { // 语法：try 异常处理块开始
                while (lookThreadRunning) { // 语法：while 循环，运行标志为 true 时持续 tick
                    val tickStart = SystemClock.elapsedRealtimeNanos() // 记录本次 tick 起始纳秒时间（语法：val 只读变量）
                    val dt = ((tickStart - lastTickNanos) / 1e9f).coerceIn(0.001f, 0.05f) // 计算实际间隔秒数并钳制到 [1ms,50ms]（语法：coerceIn 范围钳制 + 1e9f 科学计数法）
                    lastTickNanos = tickStart // 更新上一 tick 时间戳
                    processLookTick(dt) // 执行一次位移计算与发送
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - tickStart) / 1_000_000L // 计算本 tick 处理耗时（毫秒）（语法：1_000_000L Long 字面量 + 下划线数字）
                    val sleepMs = LOOK_TICK_MS - elapsedMs // 计算需 sleep 时长以保持固定节奏（语法：val 只读变量）
                    if (sleepMs > 0) { // 语法：if 分支，若还有剩余时间
                        Thread.sleep(sleepMs) // 睡眠以维持固定 tick 频率
                    } // 结束 if (sleepMs>0) 分支
                } // 结束 while 循环
            } catch (e: InterruptedException) { // 语法：catch 捕获中断异常
                // 停止请求
            } // 结束 catch 块
            lookThreadRunning = false // 循环退出后复位运行标志
        }, "SteamLike-LookLoop").apply { isDaemon = true } // 设置线程名并设为守护线程（语法：.apply 作用域函数 + isDaemon 属性）
        lookThread?.start() // 启动线程（语法：?. 安全调用）
    } // 结束 startLookLoop 函数

    /**
     * 停止右摇杆发送循环，等待线程退出
     */
    private fun stopLookLoop() { // 语法：private fun 私有函数，停止右摇杆发送循环
        lookThreadRunning = false // 复位运行标志，使循环退出
        lookThread?.interrupt() // 中断线程的 sleep（若有）（语法：?. 安全调用）
        lookThread?.join(500) // 等待线程最多 500ms 结束（语法：?. 安全调用 + join 等待线程）
        lookThread = null // 清空线程引用
    } // 结束 stopLookLoop 函数

    /**
     * 单个 tick：读取最新摇杆位置，计算并发送鼠标位移
     *
     * 处理链（与业界一致）：幅值钳制 → 加速曲线 → 时间常数 EMA → 位移积分
     * 死区已在 [SteamInput] 侧统一应用。
     *
     * @param dt 实际经过时间（秒），位移 = 速度 × dt
     */
    private fun processLookTick(dt: Float) { // 语法：private fun 私有函数，单个 tick 计算并发送鼠标位移
        var rx = latestLookX // 读取最新 X 到局部变量（语法：var 可变变量）
        var ry = latestLookY // 读取最新 Y 到局部变量（语法：var 可变变量）
        val settings = steamInput.profile.globalSettings // 获取全局设置（灵敏度/加速/平滑）（语法：val 只读变量 + 链式属性访问）

        // 幅值钳制（mag>1 缩回单位圆）
        val mag = Math.sqrt((rx * rx + ry * ry).toDouble()).toFloat() // 计算摇杆向量模长（语法：Math.sqrt 平方根 + toDouble/toFloat 类型转换）
        if (mag > 1f) { // 语法：if 分支，模长超过 1（越界）
            val scale = 1f / mag // 计算缩放系数将模长缩回单位圆（语法：val 只读变量）
            rx *= scale // 缩放 X（语法：*= 复合赋值）
            ry *= scale // 缩放 Y（语法：*= 复合赋值）
        } // 结束幅值钳制分支

        // 加速曲线：pow(mag, accel)，轻推更慢、重推更快（精确瞄准/快速转身兼顾）
        val accel = settings.lookAcceleration.coerceIn(0.5f, 3.0f) // 取加速度指数并钳制到 [0.5,3.0]（语法：coerceIn 范围钳制）
        if (rx != 0f || ry != 0f) { // 语法：if 分支 + || 逻辑或，仅在摇杆有位移时计算曲线
            val normMag = Math.sqrt((rx * rx + ry * ry).toDouble()).toFloat() // 计算缩放前的模长（语法：Math.sqrt 平方根）
            val curve = Math.pow(normMag.toDouble(), accel.toDouble()).toFloat() // 计算加速曲线值 pow(mag,accel)（语法：Math.pow 幂运算）
            val scale = if (normMag > 0f) curve / normMag else 0f // 计算缩放系数，模长为 0 时取 0（语法：if 作为表达式）
            rx *= scale // 应用加速缩放 X（语法：*= 复合赋值）
            ry *= scale // 应用加速缩放 Y（语法：*= 复合赋值）
        } // 结束加速曲线分支

        // 时间常数 EMA 平滑（与 tick 频率无关的稳定平滑）
        val tau = settings.lookSmoothing.coerceIn(0f, 0.95f) * LOOK_SMOOTH_TAU_MAX // 计算平滑时间常数 τ（语法：coerceIn 范围钳制）
        val alpha = if (tau <= 0f) 1f else 1f - kotlin.math.exp(-dt / tau) // 计算 EMA 系数 α=1-exp(-dt/τ)（语法：if 作为表达式 + kotlin.math.exp 指数函数）
        smoothedLookX = smoothedLookX * (1f - alpha) + rx * alpha // 对 X 做指数移动平均
        smoothedLookY = smoothedLookY * (1f - alpha) + ry * alpha // 对 Y 做指数移动平均

        // 位移积分：满推 LOOK_SPEED_PX_PER_SEC × 灵敏度 × dt
        // 亚像素部分由注入器余量累积补发（不丢量、不产生脉冲）
        // 精细移动优先：两者同时按住时按精细处理，避免一放大一缩小互相抵消
        val moveScale = when { // 视角位移缩放系数（语法：when 分支表达式）
            fineMoveActive -> FINE_MOVE_FACTOR // 精细移动：缩小
            fastMoveActive -> FAST_MOVE_FACTOR // 快速移动：放大
            else -> 1f // 普通速度
        }
        val dx = smoothedLookX * settings.lookSensitivity * LOOK_SPEED_PX_PER_SEC * dt * moveScale // 计算 X 方向位移（语法：val 只读变量）
        val dy = smoothedLookY * settings.lookSensitivity * LOOK_SPEED_PX_PER_SEC * dt * moveScale // 计算 Y 方向位移（语法：val 只读变量）
        if (dx != 0f || dy != 0f) { // 语法：if 分支 + || 逻辑或，若存在位移
            injector.sendMouseMove(dx, dy) // 发送鼠标相对移动
        } // 结束位移发送分支
    } // 结束 processLookTick 函数

    companion object { // 语法：companion object 伴生对象，存放类级常量
        /**
         * 右摇杆满推时的鼠标移动速度（像素/秒）
         *
         * 位移 = 速度 × 实际经过时间，无论 tick/事件频率如何，速度恒定。
         */
        private const val LOOK_SPEED_PX_PER_SEC = 480f // 语法：const val 编译期常量，右摇杆满推移动速度（像素/秒）

        /** 本地锁存被客户端「未锁存」上报清除前的保护时间（毫秒），避开 0x03 尚未送达的窗口 */
        private const val STALE_LOCK_MS = 400L // 语法：const val 编译期常量（毫秒）

        /**
         * 视角 EMA 平滑时间常数上限（秒）
         *
         * lookSmoothing=0.95 时 τ=45ms，lookSmoothing=0.5（默认）时 τ≈24ms。
         */
        private const val LOOK_SMOOTH_TAU_MAX = 0.048f // 语法：const val 编译期常量，EMA 平滑时间常数上限（秒）

        /**
         * 右摇杆发送循环 tick 间隔（毫秒）≈ 60Hz
         *
         * 与游戏帧率对齐：更高的发送频率只会增加注入与模拟器侧处理开销、
         * 与渲染争抢 CPU，对实际观感无益。每 tick 位移若干像素，配合
         * 余量累积仍能保持连续移动（位移按实际 dt 积分，速度与频率无关）。
         */
        private const val LOOK_TICK_MS = 16L // 语法：const val 编译期常量，tick 间隔毫秒（16L=Long 字面量，约 60Hz，与游戏帧率对齐以减少无效注入）

        /** 鼠标滚轮增量（每次按下发送的滚轮量） */
        private const val SCROLL_DELTA = 120f // 语法：const val 编译期常量，鼠标滚轮增量

        /** 精细移动模式下右摇杆位移的缩放系数（0.2 = 正常速度的 20%） */
        private const val FINE_MOVE_FACTOR = 0.2f // 语法：const val 编译期常量，精细移动缩放系数

        /** 快速移动模式下右摇杆位移的放大系数（2.5 = 正常速度的 2.5 倍） */
        private const val FAST_MOVE_FACTOR = 2.5f // 语法：const val 编译期常量，快速移动缩放系数

        /** 定角转身的注入步数（步数越多越接近真实拖动，总耗时也越长） */
        private const val TURN_STEPS = 10 // 语法：const val 编译期常量，转身位移分步数
    } // 结束伴生对象
} // 结束 KeyboardMouseMapper 类
