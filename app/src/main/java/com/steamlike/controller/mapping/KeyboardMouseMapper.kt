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
     * 锁存状态由用户主动解锁，不随操作层切换自动释放
     * （松开手柄键也不改变状态，见 [handleMouseToggle]）。
     */
    private val toggledMouseButtons = mutableSetOf<MouseButton>() // 语法：val+mutableSetOf 可变集合，记录鼠标按钮锁存状态（全局统一标志）

    // ====================================================================
    // 长按判定状态机（Long-Press）
    // ====================================================================
    // 仅对「已配置长按动作」或「全局回退开关开启」的可延迟短按动作生效：
    // 按下时不立即注入，改为记录按下时间并启动阈值定时器；松开时按按住时长
    // 决定执行短按动作还是长按动作（见 [beginDeferredPress]/[finishDeferredPress]）。
    // 层切换、滚轮、鼠标长按锁存、悬浮窗/键盘/捕获切换等动作不参与判定。
    // ====================================================================

    /** 主线程 Handler，用于长按阈值定时（与输入事件同线程，避免并发） */
    private val mainHandler = Handler(Looper.getMainLooper()) // 语法：Handler(Looper.getMainLooper())=绑定主线程的消息处理器

    /** 一次延迟按下（长按判定中）的上下文 */
    private data class DeferredPress( // 语法：private data class 私有数据类，封装延迟按下上下文
        val mapping: KeyMapping, // 按下时生效的映射（松开时按它判定，避免层切换后映射变化）
        val pressTimeMs: Long // 按下时刻（SystemClock.elapsedRealtime）
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
        steamInput.onButtonMapped = { button, isPressed, mapping -> // 注册按钮映射回调（语法：lambda 表达式赋给属性）
            handleMapping(button, isPressed, mapping) // 分发按钮映射到内部处理方法
        } // 结束按钮映射 lambda
        steamInput.onStickMapped = { stick, x, y -> // 注册摇杆映射回调（语法：lambda 表达式）
            handleStick(stick, x, y) // 分发摇杆事件到内部处理方法
        } // 结束摇杆映射 lambda
        steamInput.onLayerChanged = { layerName -> // 注册操作层变化回调（语法：lambda 表达式）
            Log.i(TAG, "Active layer: $layerName") // 打印激活层日志（语法：字符串模板 "$layerName"）
            // 通知外部监听器，传递当前所有激活层名称列表
            onLayerChanged?.invoke(getActiveLayers()) // 安全调用触发回调，传入激活层名称列表（语法：?. 安全调用 + invoke()）
        } // 结束操作层变化 lambda
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
        leftStickPressedKeys.clear() // 清空左摇杆方向键集合
        toggledMouseButtons.clear() // 清空鼠标按钮锁存记录（含左摇杆移动自动锁定的右键）
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
     */
    private fun handleMapping(button: ControllerButton, isPressed: Boolean, mapping: KeyMapping) { // 语法：private fun 私有函数，按动作类型分发按键映射
        if (!isPressed) { // 语法：if 分支，判断是否为松开事件
            if (finishDeferredPress(button)) return // 若该按钮处于长按判定中，按按住时长决定短按/长按（语法：if 提前返回）
            releaseButtonInjection(button) // 按已注入状态释放该按钮的全部键鼠状态
            return // 直接返回，松开不按当前层映射分发
        } // 结束 if 松开分支

        // 长按判定：仅对「已配置长按动作」或「全局回退开启」的可延迟短按动作生效
        if (isLongPressEligible(mapping)) { // 语法：if 判断是否启用长按判定
            beginDeferredPress(button, mapping) // 按下时不注入，记录时间并启动阈值定时器
            return // 返回，等待松开时判定
        } // 结束长按判定分支

        when (val action = mapping.action) { // 语法：when 分支表达式，同时用 val 声明局部变量 action
            // 切换动作：始终处理
            is MappedAction.ToggleOverlay -> { onToggleOverlay?.invoke(); return } // 悬浮窗切换动作：触发回调后返回（语法：is 类型匹配 + 分号并列多语句）
            is MappedAction.ToggleKeyboard -> { onToggleKeyboard?.invoke(); return } // 系统键盘切换动作：触发回调后返回（语法：is 类型匹配）
            is MappedAction.ToggleCapture -> { onToggleCapture?.invoke(); return } // 捕获切换动作：触发回调后返回（语法：is 类型匹配）
            else -> {} // 其他动作不在切换分支处理（语法：else 兜底分支）
        } // 结束切换动作 when

        // 非切换动作：始终处理（暂停时也转发到 bridge，让游戏接收手柄输入）
        when (val action = mapping.action) { // 语法：when 分支表达式，分发非切换动作
            is MappedAction.KeyboardKey -> handleKeyboardKey(button, true, action.keyCode, mapping.subCommands) // 键盘按键动作：处理主键+子命令（语法：is 类型匹配）
            is MappedAction.SwitchLayerAndKey -> handleKeyboardKey(button, true, action.keyCode, mapping.subCommands) // 切层+按键动作：按键盘键注入（层切换由 SteamInput 处理）
            is MappedAction.NumpadKey -> handleKeyboardKey(button, true, action.keyCode, mapping.subCommands) // 数字小键盘动作：按键盘键注入（VK 映射到小键盘键）
            is MappedAction.MouseClick -> handleMouseClick(button, true, action.button) // 鼠标点击动作：按下鼠标按钮
            is MappedAction.MouseToggle -> handleMouseToggle(action.button) // 鼠标长按切换动作（仅在按下时到达此处）
            is MappedAction.MouseSwapToggle -> handleMouseSwapToggle() // 鼠标长按交换动作：在左右键长按间交换
            is MappedAction.MouseReleaseAll -> handleMouseReleaseAll() // 释放鼠标所有长按动作：解除全部长按锁存
            is MappedAction.SwitchLayer -> {} // SteamInput 已处理 // 切换层动作：由 SteamInput 内部处理
            is MappedAction.MouseMove, is MappedAction.LookAround -> {} // handleStick 处理 // 鼠标移动/视角动作：由 handleStick 处理
            is MappedAction.MouseScrollUp -> handleMouseScroll(SCROLL_DELTA) // 鼠标上滚动作：传入正增量
            is MappedAction.MouseScrollDown -> handleMouseScroll(-SCROLL_DELTA) // 鼠标下滚动作：传入负增量
            is MappedAction.ToggleOverlay, // 悬浮窗切换动作（此处重复列出，上面已处理）
            is MappedAction.ToggleKeyboard, // 系统键盘切换动作（此处重复列出，上面已处理）
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
     * @param subCommands 子命令键码列表（短按动作沿用原映射的子命令；长按动作为空）
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
    private fun injectInstantAction(action: MappedAction) { // 语法：private fun 私有函数，触发瞬时动作
        when (action) { // 按动作类型分发（语法：when 分支）
            is MappedAction.MouseToggle -> handleMouseToggle(action.button) // 鼠标长按锁存：切换锁存状态
            is MappedAction.MouseSwapToggle -> handleMouseSwapToggle() // 鼠标长按交换：交换左右键锁存
            is MappedAction.MouseReleaseAll -> handleMouseReleaseAll() // 释放鼠标所有长按：解除全部锁存
            is MappedAction.MouseScrollUp -> handleMouseScroll(SCROLL_DELTA) // 滚轮上滚：发送一次上滚
            is MappedAction.MouseScrollDown -> handleMouseScroll(-SCROLL_DELTA) // 滚轮下滚：发送一次下滚
            is MappedAction.ToggleOverlay -> onToggleOverlay?.invoke() // 切换悬浮窗（语法：?. 安全调用）
            is MappedAction.ToggleKeyboard -> onToggleKeyboard?.invoke() // 切换系统键盘
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
            injectInstantAction(action) // 直接触发一次
        } // 结束类型判断
    } // 结束 tapAction 函数

    /**
     * 开始一次「延迟按下」（长按判定）
     *
     * 按下时不注入任何事件，仅记录按下时间；若配置了长按动作，则在阈值到达时
     * 由 [onLongPressThreshold] 注入长按按下。
     */
    private fun beginDeferredPress(button: ControllerButton, mapping: KeyMapping) { // 语法：private fun 私有函数，开始长按判定
        if (deferredPresses.containsKey(button)) return // 已在判定中则忽略，防重复（语法：containsKey 判断）
        val pressTime = SystemClock.elapsedRealtime() // 记录按下时刻（单调时钟，不受系统时间调整影响）
        deferredPresses[button] = DeferredPress(mapping, pressTime) // 存入延迟按下上下文
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
            injectActionDown(button, longAction) // 注入按下（记录状态）
            longHoldActive.add(button) // 记入保持型长按集合
        } else { // 瞬时型动作
            injectInstantAction(longAction) // 触发一次
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
            tapAction(button, pending.mapping.action, pending.mapping.subCommands) // 执行短按动作（保持型点按/触发型触发）
        } else if (longAction != null) { // 长按：已配置长按动作
            if (longHoldActive.remove(button)) { // 保持型长按已按下 → 释放
                releaseButtonInjection(button) // 释放长按注入的按键
            } else if (!longInstantFired.remove(button)) { // 瞬时型尚未触发（阈值回调与松开竞态）→ 补发一次
                tapAction(button, longAction) // 补发执行长按动作（保持型点按/触发型触发）
            } // 结束长按类型判断
        } else if (steamInput.profile.globalSettings.longPressFallbackToShort) { // 长按未配置动作：回退开关开启
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
        if (released) { // 语法：if 分支，若本按钮确有释放动作
            Log.d(TAG, "Released button $button injections") // 打印释放日志（语法：字符串模板 "$button"）
        } // 结束 if (released) 分支
    } // 结束 releaseButtonInjection 函数

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
     * 第二次按下 → 发送 MouseUp（不再按下）
     * 松开手柄键不触发任何操作（保持 toggle 状态）
     *
     * 锁存状态按鼠标按钮全局记录（[toggledMouseButtons]），与左摇杆移动
     * 自动锁定右键共用：无论由谁锁定的右键，再按一次该映射键即解锁。
     *
     * @param mouseButton 鼠标按钮
     */
    private fun handleMouseToggle(mouseButton: MouseButton) { // 语法：private fun 私有函数，处理鼠标长按切换映射
        if (toggledMouseButtons.contains(mouseButton)) { // 语法：if 分支，若该鼠标按钮已处于锁存状态（contains 集合包含判断）
            // 再次按下 → 释放（左摇杆自动锁定过的右键也在此一并解锁）
            injector.sendMouseUp(mouseButton) // 发送鼠标按钮松开事件
            toggledMouseButtons.remove(mouseButton) // 从锁存记录中移除该鼠标按钮
            onMouseToggleChanged?.invoke(mouseButton, false) // 通知回调当前为松开状态（语法：?. 安全调用）
            Log.d(TAG, "Toggle MouseUp: $mouseButton") // 打印 toggle 松开日志（语法：字符串模板）
        } else { // 语法：else 分支，第一次按下
            // 第一次按下 → 按下
            injector.sendMouseDown(mouseButton) // 发送鼠标按钮按下事件（保持不松开）
            toggledMouseButtons.add(mouseButton) // 记录该鼠标按钮处于锁存状态（语法：Set.add 集合添加元素）
            onMouseToggleChanged?.invoke(mouseButton, true) // 通知回调当前为按下状态（语法：?. 安全调用）
            Log.d(TAG, "Toggle MouseDown: $mouseButton") // 打印 toggle 按下日志（语法：字符串模板）
        } // 结束 if-else 分支
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
                injector.sendMouseUp(MouseButton.RIGHT) // 释放右键
                toggledMouseButtons.remove(MouseButton.RIGHT) // 移出右键锁存记录
                onMouseToggleChanged?.invoke(MouseButton.RIGHT, false) // 通知右键已释放（语法：?. 安全调用）
                injector.sendMouseDown(MouseButton.LEFT) // 按下并锁存左键
                toggledMouseButtons.add(MouseButton.LEFT) // 记入左键锁存记录
                onMouseToggleChanged?.invoke(MouseButton.LEFT, true) // 通知左键已锁存
                Log.d(TAG, "Swap MouseToggle: RIGHT -> LEFT") // 打印交换日志
            } // 结束右键分支
            toggledMouseButtons.contains(MouseButton.LEFT) -> { // 左键长按中 → 换成长按右键
                injector.sendMouseUp(MouseButton.LEFT) // 释放左键
                toggledMouseButtons.remove(MouseButton.LEFT) // 移出左键锁存记录
                onMouseToggleChanged?.invoke(MouseButton.LEFT, false) // 通知左键已释放
                injector.sendMouseDown(MouseButton.RIGHT) // 按下并锁存右键
                toggledMouseButtons.add(MouseButton.RIGHT) // 记入右键锁存记录
                onMouseToggleChanged?.invoke(MouseButton.RIGHT, true) // 通知右键已锁存
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
     * 遍历全局锁存集合 [toggledMouseButtons]，逐个发送 MouseUp 并清除锁存记录，
     * 同时通知 [onMouseToggleChanged] 使悬浮窗边框恢复默认色。
     * 集合为空（无任何长按锁存）时不做任何处理。
     */
    private fun handleMouseReleaseAll() { // 语法：private fun 私有函数，释放所有鼠标长按锁存
        if (toggledMouseButtons.isEmpty()) { // 无任何长按锁存
            Log.d(TAG, "Release all MouseToggles: none active, ignored") // 打印忽略日志
            return // 直接返回
        } // 结束空集合判断
        val released = toggledMouseButtons.toList() // 复制当前锁存按钮列表（复制后清空，避免遍历中修改）
        toggledMouseButtons.clear() // 清空全部长按锁存记录
        released.forEach { button -> // 逐个释放（语法：forEach 遍历 + lambda）
            injector.sendMouseUp(button) // 发送鼠标按钮松开事件
            onMouseToggleChanged?.invoke(button, false) // 通知回调已释放，悬浮窗边框恢复（语法：?. 安全调用）
        } // 结束逐个释放遍历
        Log.d(TAG, "Release all MouseToggles: $released") // 打印释放日志
    } // 结束 handleMouseReleaseAll 函数

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
        if (toggledMouseButtons.contains(MouseButton.LEFT)) return // 左键长按锁存时不做自动锁定右键（避免与左键锁存冲突）
        if (toggledMouseButtons.contains(MouseButton.RIGHT)) return // 右键已锁定：直接返回不重复锁定（语法：if 单行返回）
        if (pressedMouseButtons.containsValue(MouseButton.RIGHT)) return // 右键正被临时按住（MouseClick 动作）：不重复按下，避免其松开时打断锁定态（语法：containsValue 映射值包含判断）
        injector.sendMouseCenter() // 锁定前先把鼠标光标移到屏幕中央，避免锁定瞬间视角从偏移位置开始漂移
        injector.sendMouseDown(MouseButton.RIGHT) // 发送鼠标右键按下事件（保持不松开）
        toggledMouseButtons.add(MouseButton.RIGHT) // 记入全局锁存状态（与「鼠标长按」共用同一标志）
        onMouseToggleChanged?.invoke(MouseButton.RIGHT, true) // 通知回调，悬浮窗边框红色高亮（语法：?. 安全调用）
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

                // WASD 触发（正在移动）→ 确保鼠标右键已锁定；摇杆回中（无方向键）不做处理，
                // 锁定态保持到用户按「鼠标长按」映射键解锁。
                if (newKeys.isNotEmpty()) ensureRightButtonLockedOnMove() // 有方向键触发时检查并自动锁定右键（语法：isNotEmpty() 集合非空判断）

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
        val dx = smoothedLookX * settings.lookSensitivity * LOOK_SPEED_PX_PER_SEC * dt // 计算 X 方向位移（速度×灵敏度×时间）（语法：val 只读变量）
        val dy = smoothedLookY * settings.lookSensitivity * LOOK_SPEED_PX_PER_SEC * dt // 计算 Y 方向位移（语法：val 只读变量）
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

        /**
         * 视角 EMA 平滑时间常数上限（秒）
         *
         * lookSmoothing=0.95 时 τ=45ms，lookSmoothing=0.5（默认）时 τ≈24ms。
         */
        private const val LOOK_SMOOTH_TAU_MAX = 0.048f // 语法：const val 编译期常量，EMA 平滑时间常数上限（秒）

        /**
         * 右摇杆发送循环 tick 间隔（毫秒）≈ 125Hz
         *
         * 接近真实鼠标的回报频率（多数鼠标 125~1000Hz），
         * 每 tick 位移 1~4px，配合余量累积实现平滑连续移动。
         */
        private const val LOOK_TICK_MS = 8L // 语法：const val 编译期常量，tick 间隔毫秒（8L=Long 字面量，约 125Hz）

        /** 鼠标滚轮增量（每次按下发送的滚轮量） */
        private const val SCROLL_DELTA = 120f // 语法：const val 编译期常量，鼠标滚轮增量
    } // 结束伴生对象
} // 结束 KeyboardMouseMapper 类
