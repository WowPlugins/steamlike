package com.steamlike.controller.core // 包声明（语法：package 声明当前文件所属包）

import android.content.Context // 导入 Android 上下文类（语法：import 导入声明）
import android.hardware.input.InputManager // 导入输入管理器系统服务类
import android.os.Handler // 导入 Handler（消息处理器）
import android.os.Looper // 导入 Looper（消息循环）
import android.util.Log // 导入日志工具类
import android.view.InputDevice // 导入输入设备类
import android.view.KeyEvent // 导入按键事件类
import android.view.MotionEvent // 导入运动事件类
import java.util.concurrent.ConcurrentHashMap // 导入线程安全的 HashMap
import java.util.concurrent.CopyOnWriteArrayList // 导入写时复制列表
import java.util.concurrent.CopyOnWriteArraySet // 导入写时复制集合

/**
 * Steam风格输入系统主控制器（重构版）
 *
 * ## 架构概述
 *
 * 采用 **公共层 + 操作层** 架构，使用 [ControllerProfile] 管理按键映射。
 *
 * ### 公共层 (commonLayer)
 * - 类型: [OperationLayer]，名称为 "Common"
 * - 始终激活，提供默认按键映射
 * - 所有操作层查询不到的按键回退到此层
 *
 * ### 操作层 (Layer1-Layer10)
 * - 最多 10 个，每个有独立的按键映射表
 * - 实际层切换由 **公共层的 [MappedAction.SwitchLayer] 映射** 驱动：
 *   按下触发键（如 D-Pad ↑）→ 激活对应层；松开 → 回到公共层
 * - 激活时按键查询优先使用本层映射
 *
 * ### 按键查询顺序
 * ```
 * 用户按下按钮 A
 *      ↓
 * 遍历激活的操作层，查找 buttonMappings[A]
 *      ↓ 找到?
 *      ├─ 是 → 使用该层的映射
 *      └─ 否 → 回退到公共层 commonLayer.buttonMappings[A]
 * ```
 *
 * ## Android 知识点: InputManager
 * [InputManager] 是 Android 系统服务，管理所有输入设备。
 * 通过 [InputManager.getInputDeviceIds] 获取所有设备ID，
 * 通过 [InputManager.registerInputDeviceListener] 监听设备插拔。
 * 手柄设备通过 [InputDevice.SOURCE_GAMEPAD] 或 [InputDevice.SOURCE_JOYSTICK] 识别。
 *
 * ## Android 知识点: Handler & Looper
 * [Handler] 绑定到 [Looper.getMainLooper]（主线程 Looper），用于在主线程执行代码。
 * [mainHandler.postDelayed] 用于定时循环（60fps 更新循环）。
 * 主线程 Looper 由 Android 系统在 [android.app.ActivityThread.main] 中创建。
 *
 * ## 线程安全
 * - [ConcurrentHashMap]: 线程安全的 HashMap，读写无需同步
 * - [CopyOnWriteArrayList]: 写时复制 List，适合读多写少场景
 * - [CopyOnWriteArraySet]: 写时复制 Set，用于 heldButtons
 * - 输入事件在主线程分发（View 事件分发），更新循环也在主线程
 *
 * @param context Android Context，用于获取 InputManager 系统服务
 */
class SteamInput(context: Context) { // 主控制器类（语法：class 声明类，主构造函数参数 context）

    private val appContext = context.applicationContext // 获取应用级上下文（语法：val 只读变量 + 类型推断）
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as InputManager // 获取输入管理器系统服务（语法：as 类型转换）
    private val mainHandler = Handler(Looper.getMainLooper()) // 主线程 Handler，用于在主线程执行代码

    // D-Pad HAT 轴状态跟踪，避免 MotionEvent 重复触发
    // 记录当前 HAT 轴按下的方向（无 = emptySet）
    private val hatState = mutableSetOf<ControllerButton>() // HAT 轴按下方向集合（语法：mutableSetOf 可变集合 + <T> 泛型指定元素类型）
    // 扳机轴（L2/R2）按下状态，避免重复触发
    private var l2Pressed = false // L2 扳机按下状态标记（语法：var 可变变量）
    private var r2Pressed = false // R2 扳机按下状态标记
    /**
     * 按键触发的层切换记录
     *
     * key: 触发层切换的按键（如 DPAD_UP）
     * value: 被激活的操作层
     *
     * 按下时如果是 SwitchLayer 映射，记录 button->layer；
     * 松开时检查此映射，停用对应层。这样即使激活层覆盖了该按键的映射，
     * 也能正确停用层（不会执行激活层中该按键的 keyup）。
     */
    private val buttonTriggeredLayers = mutableMapOf<ControllerButton, OperationLayer>() // 按键触发层切换记录表（语法：mutableMapOf 可变 Map + 键/值泛型）

    /**
     * 「切层+按键」类型按下时缓存的原始映射
     *
     * [MappedAction.SwitchLayerAndKey] 按下时记录原映射，松开时（经过 [buttonTriggeredLayers]
     * 提前停层返回路径）仍需用它注入按键松开，否则同时按下的键盘键会卡住。
     */
    private val switchLayerAndKeyMappings = mutableMapOf<ControllerButton, KeyMapping>() // 切层+按键类型缓存映射表

    /**
     * 控制器配置（公共层 + 操作层 + 全局设置）
     *
     * 由外部通过 [loadProfile] 加载，包含所有按键映射定义。
     */
    @Volatile // 多线程可见性注解（语法：@Volatile 注解保证变量跨线程可见）
    var profile: ControllerProfile = ControllerProfile.createDefault() // 控制器配置对象（语法：var 可变变量 + 显式类型注解 + 伴生方法调用）
        private set // 私有 setter，外部只读（语法：private set 限定可写范围）

    /**
     * 当前激活的操作层列表（按激活顺序排列）
     *
     * 层激活由公共层（或任意层）中的 [MappedAction.SwitchLayer] 映射驱动：
     * - 按下切层键 → 激活目标层；多个层可同时激活，按激活顺序叠加（后激活的优先级更高）
     * - 松开切层键 → 停用对应层（回到公共层）
     *
     * 使用 [CopyOnWriteArrayList] 保证遍历安全。
     */
    private val activeLayers = CopyOnWriteArrayList<OperationLayer>() // 当前激活的操作层列表（语法：泛型 <OperationLayer> 指定元素类型）

    /**
     * 当前激活层名称（用于悬浮窗显示）
     */
    @Volatile // 多线程可见性注解
    var activeLayerName: String = "Common" // 当前激活层名称（语法：var + 显式类型 String）
        private set // 私有 setter，外部只读

    /**
     * 是否正在捕获（暂停时停止处理所有事件）
     *
     * false 时 dispatchKeyEvent 和 dispatchGenericMotionEvent 直接返回 false，
     * 不处理任何手柄事件（层切换、按键映射等全部停止）。
     */
    @Volatile // 多线程可见性注解
    var isCapturing: Boolean = true // 是否正在捕获手柄事件（语法：Boolean 布尔类型）

    /**
     * 已连接的手柄设备列表（按 deviceId 索引）
     */
    private val connectedControllers = ConcurrentHashMap<Int, ControllerDevice>() // 已连接手柄设备表（语法：ConcurrentHashMap 线程安全 Map）
    val controllers: Map<Int, ControllerDevice> get() = connectedControllers // 对外暴露的只读设备表（语法：自定义 getter 属性）

    /** 手柄连接回调 */
    var onControllerConnected: ((ControllerDevice) -> Unit)? = null // 手柄连接回调（语法：lambda 函数类型 (T) -> Unit，? 可空类型）

    /** 手柄断开回调 */
    var onControllerDisconnected: ((ControllerDevice) -> Unit)? = null // 手柄断开回调

    /**
     * 当前按住的所有按钮集合
     *
     * 用于判断操作层触发键是否按住。
     */
    private val heldButtons = CopyOnWriteArraySet<ControllerButton>() // 当前按住的按钮集合（语法：CopyOnWriteArraySet 线程安全集合）

    /**
     * 按钮映射回调
     *
     * 当按钮按下/释放且找到有效映射时调用。
     * 外部（KeyboardMouseMapper）通过此回调执行键盘/鼠标注入。
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mapping 找到的按键映射
     */
    // 按钮映射回调（语法：lambda 带命名参数 + 可空类型 + Unit 无返回值；该行超长故注释置上方）
    var onButtonMapped: ((button: ControllerButton, isPressed: Boolean, mapping: KeyMapping) -> Unit)? = null

    /**
     * 无映射按钮的松开回调
     *
     * 当手柄按键松开、但在当前激活层与公共层里都查不到映射时调用。
     * 典型场景：在操作层里把某键绑定为鼠标左键并按住，松开层触发键使该层停用后
     * 再松开该键——此时查不到映射，若不通知外部释放，按下时注入的鼠标按钮会一直卡住。
     */
    var onButtonReleased: ((button: ControllerButton) -> Unit)? = null

    /**
     * 操作层停用回调
     *
     * 操作层被停用（松开切层键、切换操作集、销毁）时调用；参数为被停用的层，
     * 一次性停用全部层时参数为 null。外部据此释放该层按键注入的鼠标长按锁存。
     */
    var onLayerDeactivated: ((layer: OperationLayer?) -> Unit)? = null

    /**
     * 手柄按钮按下/释放状态回调（覆盖所有按钮，与映射动作类型无关）
     *
     * 在 [handleButtonEvent] 中更新 [heldButtons] 后调用，
     * 用于 UI 实时反馈（悬浮窗按键映射页 / 层编辑页按钮高亮）。
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     */
    var onButtonStateChanged: ((button: ControllerButton, isPressed: Boolean) -> Unit)? = null // 按钮状态变化回调

    /**
     * 摇杆映射回调
     *
     * 当摇杆移动时调用。外部根据映射类型（MouseMove/LookAround）执行不同操作。
     *
     * @param stick 摇杆类型
     * @param x X轴值 (-1.0~1.0)
     * @param y Y轴值 (-1.0~1.0)
     */
    var onStickMapped: ((stick: ControllerStick, x: Float, y: Float) -> Unit)? = null // 摇杆映射回调

    /**
     * 操作层切换回调
     *
     * 当激活层发生变化时调用，用于更新悬浮窗 UI。
     */
    var onLayerChanged: ((activeLayerName: String) -> Unit)? = null // 操作层切换回调

    /**
     * 操作集切换回调
     *
     * 当通过 [switchActionSet] 切换操作集时调用，传递新操作集名称。
     * 由 ControllerOverlayService 设置，用于更新悬浮窗显示当前操作集。
     */
    var onActionSetChanged: ((actionSetName: String) -> Unit)? = null // 操作集切换回调

    /**
     * 系统输入设备监听器
     *
     * 监听手柄的插入/拔出/变化事件。
     *
     * ## Android 知识点: InputDeviceListener
     * [InputManager.InputDeviceListener] 是回调接口，需在主线程注册（第二个参数）。
     * 回调方法:
     * - [onInputDeviceAdded]: 设备插入时调用（如连接蓝牙手柄）
     * - [onInputDeviceRemoved]: 设备拔出时调用
     * - [onInputDeviceChanged]: 设备配置变化时调用
     */
    private val inputDeviceListener = object : InputManager.InputDeviceListener { // 设备监听器实例（语法：object : 接口 匿名实现对象）
        override fun onInputDeviceAdded(deviceId: Int) { // 设备插入回调（语法：override 重写父类方法 + fun 函数声明）
            val device = InputDevice.getDevice(deviceId) ?: return // 获取设备对象，不存在则返回（语法：?: 空值合并运算符）
            ControllerDevice.fromInputDevice(device)?.let { controller -> // 转换设备并处理（语法：?. 安全调用 + let 作用域函数 + lambda 参数）
                connectedControllers[deviceId] = controller // 存入已连接设备表
                Log.i(TAG, "Controller connected: ${controller.name} (${controller.controllerType})") // 打印连接日志（语法：字符串模板 ${}）
                onControllerConnected?.invoke(controller) // 触发连接回调（语法：?. 安全调用 + invoke 调用）
            } // 结束 let 块
        } // 结束 onInputDeviceAdded 函数

        override fun onInputDeviceRemoved(deviceId: Int) { // 设备拔出回调
            connectedControllers.remove(deviceId)?.let { controller -> // 从设备表移除并处理（语法：?. 安全调用 + let）
                Log.i(TAG, "Controller disconnected: ${controller.name}") // 打印断开日志
                onControllerDisconnected?.invoke(controller) // 触发断开回调
            } // 结束 let 块
        } // 结束 onInputDeviceRemoved 函数

        override fun onInputDeviceChanged(deviceId: Int) { // 设备配置变化回调
            val device = InputDevice.getDevice(deviceId) ?: return // 获取设备对象，不存在则返回
            ControllerDevice.fromInputDevice(device)?.let { controller -> // 重新转换并更新
                connectedControllers[deviceId] = controller // 更新设备表
            } // 结束 let 块
        } // 结束 onInputDeviceChanged 函数
    } // 结束 inputDeviceListener 匿名对象

    /**
     * 初始化: 注册设备监听器、扫描已连接设备
     */
    init { // 初始化块（语法：init 在构造时执行）
        inputManager.registerInputDeviceListener(inputDeviceListener, mainHandler) // 注册设备监听器
        InputDevice.getDeviceIds().forEach { deviceId -> // 遍历所有已连接设备 ID（语法：forEach lambda）
            val device = InputDevice.getDevice(deviceId) ?: return@forEach // 获取设备，空则跳过本次（语法：return@标签 局部返回）
            ControllerDevice.fromInputDevice(device)?.let { controller -> // 转换设备
                connectedControllers[deviceId] = controller // 存入设备表
            } // 结束 let 块
        } // 结束 forEach
    } // 结束 init 块

    // ====================================================================
    // 配置管理
    // ====================================================================

    /**
     * 加载控制器配置
     *
     * 替换当前的 [profile]，所有按键映射使用新配置。
     * 调用后会停用所有操作层。
     */
    fun loadProfile(newProfile: ControllerProfile) { // 加载控制器配置（语法：fun 成员函数声明）
        profile = newProfile // 替换当前配置
        deactivateAllLayers() // 停用所有操作层
        // 通知悬浮窗操作集信息已变化（编辑页保存/切换操作集后保持显示一致）
        onActionSetChanged?.invoke(profile.activeActionSetName) // 通知操作集信息变化
        Log.i(TAG, "Profile loaded: ${newProfile.layers.size} layers, action set: ${profile.activeActionSetName}") // 打印加载日志
    } // 结束 loadProfile 函数

    // ====================================================================
    // 操作层管理
    // ====================================================================

    /**
     * 激活操作层
     *
     * 将层加入激活列表，后续按键查询优先使用此层。
     * 同时更新 [activeLayerName] 并触发 [onLayerChanged] 回调。
     */
    fun activateLayer(layer: OperationLayer) { // 激活操作层
        if (!activeLayers.contains(layer)) { // 未激活时才加入（语法：! 逻辑非）
            activeLayers.add(layer) // 加入激活列表
        } // 结束 if
        activeLayerName = layer.name // 更新激活层名称
        Log.i(TAG, "Layer activated: ${layer.name}") // 打印激活日志
        onLayerChanged?.invoke(activeLayerName) // 触发层变化回调
    } // 结束 activateLayer 函数

    /**
     * 按名称激活操作层
     */
    fun activateLayer(name: String) { // 按名称激活操作层
        profile.findLayer(name)?.let { activateLayer(it) } // 查找层并激活（语法：?. 安全调用 + let + it 默认参数名）
    } // 结束 activateLayer(name) 函数

    /**
     * 停用操作层
     *
     * 从激活列表移除，回到公共层。
     */
    fun deactivateLayer(layer: OperationLayer) { // 停用操作层
        if (activeLayers.remove(layer)) { // 移除成功才继续处理
            activeLayerName = if (activeLayers.isEmpty()) "Common" else activeLayers.last().name // 更新激活层名（语法：if 表达式 + else）
            Log.i(TAG, "Layer deactivated: ${layer.name}, active=${activeLayerName}") // 打印停用日志
            onLayerChanged?.invoke(activeLayerName) // 触发层变化回调
            onLayerDeactivated?.invoke(layer) // 通知外部释放该层按住时注入的鼠标长按锁存
        } // 结束 if
    } // 结束 deactivateLayer 函数

    /**
     * 按名称停用操作层
     */
    fun deactivateLayer(name: String) { // 按名称停用操作层
        profile.findLayer(name)?.let { deactivateLayer(it) } // 查找层并停用
    } // 结束 deactivateLayer(name) 函数

    /**
     * 停用所有操作层
     */
    fun deactivateAllLayers() { // 停用所有操作层
        val hadActive = activeLayers.isNotEmpty() // 记录停用前是否有激活层
        activeLayers.clear() // 清空激活列表
        buttonTriggeredLayers.clear() // 清空层切换触发记录
        switchLayerAndKeyMappings.clear() // 清空切层+按键缓存映射
        activeLayerName = "Common" // 激活层名回到公共层
        onLayerChanged?.invoke(activeLayerName) // 触发层变化回调
        if (hadActive) onLayerDeactivated?.invoke(null) // 通知外部释放各层按住时注入的鼠标长按锁存
    } // 结束 deactivateAllLayers 函数

    /**
     * 检查指定操作层是否激活
     */
    fun isLayerActive(name: String): Boolean = activeLayers.any { it.name == name } // 判断层是否激活（语法：表达式体函数 = + any lambda）

    /**
     * 获取当前激活的操作层列表
     */
    fun getActiveLayers(): List<OperationLayer> = activeLayers.toList() // 获取激活层列表快照（语法：返回类型 List<OperationLayer>）

    // ====================================================================
    // 操作集管理
    // ====================================================================

    /**
     * 切换操作集（整体切换其下所有操作层）
     *
     * 停用所有已激活的操作层，将当前操作集切换为目标操作集。
     * 会触发 [onActionSetChanged] 与 [onLayerChanged]（回到公共层状态）回调，
     * 悬浮窗据此刷新操作集信息与层按钮。
     *
     * @param name 目标操作集名称（不存在时忽略）
     */
    fun switchActionSet(name: String) { // 切换操作集
        if (profile.findActionSet(name) == null) { // 目标操作集不存在则忽略
            Log.w(TAG, "Action set not found: $name") // 打印警告日志（语法：字符串模板 $name）
            return // 提前返回
        } // 结束 if
        deactivateAllLayers() // 停用所有操作层
        profile = profile.copy(activeActionSetName = name) // 更新当前操作集名（语法：copy() 数据类复制 + 具名参数）
        Log.i(TAG, "Action set switched: $name") // 打印切换日志
        onActionSetChanged?.invoke(name) // 触发操作集变化回调
    } // 结束 switchActionSet 函数

    /**
     * 当前操作集名称（用于 UI 显示）
     */
    fun getActiveActionSetName(): String = profile.activeActionSetName // 获取当前操作集名称

    // ====================================================================
    // 按键查询
    // ====================================================================

    /**
     * 查找按钮的有效映射（核心方法）
     *
     * 查找顺序:
     * 1. 遍历激活的操作层，查找 buttonMappings[button]
     * 2. 如果没有，回退到公共层 commonLayer.buttonMappings[button]
     *
     * @param button 要查找的按钮
     * @return 找到的映射；未绑定返回 null
     */
    fun getEffectiveMapping(button: ControllerButton): KeyMapping? { // 查找按钮有效映射（语法：返回可空类型 KeyMapping?）
        // 1. 从激活层查找
        for (layer in activeLayers) { // 遍历激活层（语法：for(x in y) 遍历）
            layer.getMapping(button)?.let { return it } // 找到映射立即返回（语法：?. 安全调用 + let + return 局部返回）
        } // 结束 for
        // 2. 回退到公共层
        return profile.commonLayer.getMapping(button) // 回退到公共层查询
    } // 结束 getEffectiveMapping 函数

    // ====================================================================
    // 输入分发
    // ====================================================================

    /**
     * 处理系统 KeyEvent（按钮按下/释放）
     *
     * ## Android 知识点: KeyEvent
     * [KeyEvent] 表示键盘/按钮事件，包含:
     * - [KeyEvent.getAction]: ACTION_DOWN 或 ACTION_UP
     * - [KeyEvent.getKeyCode]: 按键代码（如 KEYCODE_BUTTON_A）
     * - [KeyEvent.getDeviceId]: 来源设备ID
     * - [KeyEvent.getRepeatCount]: 长按重复次数（0=首次按下）
     * - [KeyEvent.isFromSource]: 检查事件来源（GAMEPAD/DPAD/JOYSTICK）
     *
     * @param event 系统按键事件
     * @return true=已处理
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean { // 处理系统按键事件（语法：返回类型 Boolean）
        // 暂停捕获时走 dispatchKeyEventWhilePaused 处理切换类动作。
        // 注意：现在暂停捕获会真正移除焦点窗口，手柄事件到不了应用，此分支
        // 实际只会由无障碍按键过滤转发路径触发（该能力在本机未授予，已弃用）。
        if (!isCapturing) return dispatchKeyEventWhilePaused(event) // 暂停捕获时走暂停处理路径
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && // 来源非游戏手柄则跳过（语法：&& 与运算跨行）
            !event.isFromSource(InputDevice.SOURCE_DPAD) && // 且非 D-Pad 来源
            !event.isFromSource(InputDevice.SOURCE_JOYSTICK) // 且非摇杆来源
        ) return false // 三者都不是则返回 false

        val deviceId = event.deviceId // 获取事件来源设备 ID
        val controller = connectedControllers[deviceId] ?: return false // 设备未连接则返回（语法：?: 空值合并运算符）
        val button = ControllerInputMapper.mapKeyCode(event.keyCode, controller.controllerType) ?: return false // 键码转统一按钮，失败则返回
        if (event.repeatCount != 0) return true // 长按重复事件直接拦截（语法：!= 不等比较）

        val isPressed = event.action == KeyEvent.ACTION_DOWN // 判断是按下还是释放
        handleButtonEvent(button, isPressed) // 分发按钮事件
        return true // 返回已处理
    } // 结束 dispatchKeyEvent 函数

    /**
     * 暂停捕获状态下处理按键（无障碍转发路径，当前已弃用）
     *
     * 暂停捕获时 [ControllerOverlayService.pauseCapturing] 会真正移除 GamepadInputView
     * 焦点窗口，手柄事件无法再到达应用。本方法原本设计为：由无障碍服务全局接收
     * 按键后转发到此处，处理"切换捕获"等 Toggle 动作以便暂停后恢复。
     *
     * 但实测这台 MIUI 设备未授予无障碍按键过滤能力（capabilities=0），[GamepadAccessibilityService]
     * 的 onKeyEvent 不会被调用，因此**本方法当前实际上不会被触发**。暂停后恢复捕获
     * 只能通过悬浮窗"恢复捕获"按钮或主界面捕获开关。保留本方法以兼容旧调用点。
     *
     * 仅处理切换类动作（[MappedAction.ToggleCapture]/[MappedAction.ToggleOverlay]/
     * [MappedAction.ToggleKeyboard]），不处理普通按键映射，避免暂停时仍向游戏注入键鼠事件。
     *
     * @param event 系统按键事件
     * @return true=已处理（拦截该按键）
     */
    fun dispatchKeyEventWhilePaused(event: KeyEvent): Boolean { // 暂停状态下处理按键（语法：fun 函数声明 + Boolean 返回）
        // 打印暂停按键日志（语法：字符串模板 ${}；该行超长故注释置上方）
        Log.d(TAG, "PausedKeyEvent act=${event.action} key=${event.keyCode} src=${event.source} dev=${event.deviceId} repeat=${event.repeatCount} capturing=$isCapturing")
        if (isCapturing) return false // 已在捕获中则不处理
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && // 来源非摇杆则跳过
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD) && // 且非游戏手柄来源
            !event.isFromSource(InputDevice.SOURCE_DPAD) // 且非 D-Pad 来源
        ) return false // 三者都不是则返回 false

        val deviceId = event.deviceId // 获取设备 ID
        val controller = connectedControllers[deviceId] // 获取控制器
        Log.d(TAG, "  controller=$controller") // 打印控制器日志
        if (controller == null) return false // 无控制器则返回
        val button = ControllerInputMapper.mapKeyCode(event.keyCode, controller.controllerType) // 键码转统一按钮
        Log.d(TAG, "  button=$button") // 打印按钮日志
        if (button == null) return false // 无按钮则返回
        // 仅响应首次按下；松开/重复事件直接拦截
        if (event.repeatCount != 0 || event.action != KeyEvent.ACTION_DOWN) return true // 非首次按下直接拦截（语法：|| 或运算）

        val mapping = getEffectiveMapping(button) // 查找有效映射
        Log.d(TAG, "  mapping=$mapping") // 打印映射日志
        if (mapping == null) return false // 无映射则返回
        val action = mapping.action // 取出映射动作
        val isToggle = action is MappedAction.ToggleCapture || // 判断是否为切换捕获动作（语法：is 类型检查）
            action is MappedAction.ToggleOverlay || // 或切换悬浮窗动作
            action is MappedAction.ToggleKeyboard // 或切换键盘动作
        Log.d(TAG, "  action=${action.javaClass.simpleName} isToggle=$isToggle") // 打印动作类型日志
        if (!isToggle) { // 非切换类动作不处理
            return false // 返回未处理
        } // 结束 if

        Log.i(TAG, "Paused key: $button -> ${action.javaClass.simpleName}") // 打印暂停按键日志
        // 转发到 KeyboardMouseMapper.handleMapping，触发对应 Toggle 回调（恢复捕获等）
        onButtonMapped?.invoke(button, true, mapping) // 触发映射回调
        return true // 返回已处理
    } // 结束 dispatchKeyEventWhilePaused 函数

    /**
     * 处理系统 MotionEvent（摇杆移动/扳机按压）
     *
     * ## Android 知识点: MotionEvent & AXIS
     * [MotionEvent] 包含模拟量轴值:
     * - [MotionEvent.AXIS_X]/[MotionEvent.AXIS_Y]: 左摇杆
     * - [MotionEvent.AXIS_Z]/[MotionEvent.AXIS_RZ]: 右摇杆
     * - [MotionEvent.AXIS_LTRIGGER]/[MotionEvent.AXIS_RTRIGGER]: 扳机
     * - [MotionEvent.getAxisValue]: 获取轴值，范围 -1.0~1.0
     *
     * @param event 系统运动事件
     * @return true=已处理
     */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { // 处理系统运动事件（语法：返回类型 Boolean）
        if (!isCapturing) return false // 暂停捕获则忽略
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && // 来源非摇杆则跳过
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD) && // 且非游戏手柄来源
            !event.isFromSource(InputDevice.SOURCE_DPAD) // 且非 D-Pad 来源
        ) return false // 三者都不是则返回 false

        val deviceId = event.deviceId // 获取设备 ID
        val device = event.device ?: return false // 无设备对象则返回
        val controller = connectedControllers[deviceId] ?: return false // 设备未连接则返回

        val leftStick = ControllerInputMapper.getStickValue(event, ControllerStick.LEFT_STICK, device) // 读取左摇杆位置
        val rightStick = ControllerInputMapper.getStickValue(event, ControllerStick.RIGHT_STICK, device) // 读取右摇杆位置

        // 应用死区
        val deadzone = profile.globalSettings.deadzone // 读取全局死区配置
        val leftStickDz = leftStick.withDeadzone(deadzone) // 左摇杆应用死区
        val rightStickDz = rightStick.withDeadzone(deadzone) // 右摇杆应用死区

        // 通知摇杆事件（始终回调）
        // 原实现仅在"死区外或有居中事件"时回调：摇杆从死区外回中经过死区时
        // 事件被跳过，映射器的 EMA 平滑值会冻结在最后位置，产生拖尾抖动。
        // 始终回调 (0,0) 让平滑值立即收敛到零。
        onStickMapped?.invoke(ControllerStick.LEFT_STICK, leftStickDz.x, leftStickDz.y) // 回调左摇杆位置
        onStickMapped?.invoke(ControllerStick.RIGHT_STICK, rightStickDz.x, rightStickDz.y) // 回调右摇杆位置

        // 处理 D-Pad 的 HAT 轴事件（很多手柄的 D-Pad 通过 MotionEvent 而非 KeyEvent 发送）
        handleDpadHatAxis(event) // 处理 D-Pad HAT 轴事件

        // 处理扳机轴 L2/R2（按到底触发 click 事件）
        handleTriggerAxis(event, device) // 处理扳机轴事件

        return true // 返回已处理
    } // 结束 dispatchGenericMotionEvent 函数

    /**
     * 处理 D-Pad 的 HAT 轴（AXIS_HAT_X / AXIS_HAT_Y）
     *
     * ## Android 知识点: HAT 轴
     * 很多手柄的 D-Pad 不发送 KeyEvent.KEYCODE_DPAD_*，而是通过 MotionEvent 的
     * AXIS_HAT_X（-1=左, 0=中, 1=右）和 AXIS_HAT_Y（-1=上, 0=中, 1=下）发送。
     *
     * 本方法将 HAT 轴值转换为按下/释放事件，通过 [handleButtonEvent] 处理层切换和按键映射。
     * 使用 [hatState] 跟踪当前激活方向，避免 MotionEvent 高频触发重复事件。
     *
     * @param event MotionEvent
     */
    private fun handleDpadHatAxis(event: MotionEvent) { // 处理 D-Pad HAT 轴（语法：private 私有函数）
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X) // 读取 HAT_X 轴值
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y) // 读取 HAT_Y 轴值

        // 计算当前激活的方向（|hatX| 或 |hatY| > 0.5 视为按下）
        val upPressed = hatY < -0.5f // 判断上方向是否按下
        val downPressed = hatY > 0.5f // 判断下方向是否按下
        val leftPressed = hatX < -0.5f // 判断左方向是否按下
        val rightPressed = hatX > 0.5f // 判断右方向是否按下

        // 检测状态变化，避免重复触发
        listOf( // 构造方向状态列表（语法：listOf 创建列表）
            ControllerButton.DPAD_UP to upPressed, // 上方向与按下状态配对（语法：to 中缀函数构造 Pair）
            ControllerButton.DPAD_DOWN to downPressed, // 下方向与按下状态配对
            ControllerButton.DPAD_LEFT to leftPressed, // 左方向与按下状态配对
            ControllerButton.DPAD_RIGHT to rightPressed // 右方向与按下状态配对
        ).forEach { (button, pressed) -> // 遍历处理每个方向（语法：forEach lambda + 解构声明）
            if (pressed && !hatState.contains(button)) { // 新按下的方向（语法：&& 与运算 + ! 逻辑非）
                hatState.add(button) // 记录已按方向
                handleButtonEvent(button, isPressed = true) // 触发按下事件（语法：具名参数 isPressed =）
            } else if (!pressed && hatState.contains(button)) { // 新松开的已记录方向
                hatState.remove(button) // 移除已按方向
                handleButtonEvent(button, isPressed = false) // 触发松开事件
            } // 结束 if/else
        } // 结束 forEach
    } // 结束 handleDpadHatAxis 函数

    /**
     * 处理扳机轴 L2/R2（AXIS_LTRIGGER / AXIS_RTRIGGER）
     *
     * ## Android 知识点: 模拟扳机
     * L2/R2 是模拟扳机，行程中发送 MotionEvent.AXIS_LTRIGGER/AXIS_RTRIGGER（值 0.0~1.0）。
     * 按到底（>= [TRIGGER_CLICK_THRESHOLD]）会触发 KEYCODE_BUTTON_L2/R2 KeyEvent，
     * 但部分手柄（尤其是模拟器或非标准驱动）只发 MotionEvent 不发 KeyEvent。
     *
     * 本方法检测扳机轴值，超过阈值时触发 [ControllerButton.LEFT_TRIGGER_CLICK]/
     * [ControllerButton.RIGHT_TRIGGER_CLICK] 按下事件，低于阈值触发释放。
     * 使用 [l2Pressed]/[r2Pressed] 状态避免重复触发。
     *
     * ## 快速扳机（霍尔扳机 0↔255 直跳）适配
     * 手柄 App 设为"快速扳机"后，扳机轴只在 0 和满值间跳变，没有中间值；
     * Android 会把高频的多次轴采样合并进同一个 [MotionEvent]（含历史采样）。
     * 若只读最新采样值，合并窗口内的"松开"或"按下"翻转会被最新值覆盖而丢失，
     * 状态机卡在按下态，表现为鼠标/按键按下后不回弹。
     * 因此这里按时间顺序遍历该事件的全部采样（历史→最新），逐采样执行状态翻转，
     * 保证窗口内每次 0↔1 跳变都被正确处理（快速连点不漏、不卡）。
     *
     * @param event MotionEvent
     * @param device InputDevice（用于检测其他可能轴）
     */
    private fun handleTriggerAxis(event: MotionEvent, device: InputDevice) { // 处理扳机轴事件
        // L2: 优先 AXIS_LTRIGGER，回退 AXIS_BRAKE
        val l2Values = collectTriggerSamples( // 收集 L2 全部采样（历史→最新）
            event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, device
        )
        // R2: 优先 AXIS_RTRIGGER，回退 AXIS_GAS
        val r2Values = collectTriggerSamples( // 收集 R2 全部采样（历史→最新）
            event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, device
        )

        // 按时间顺序逐采样执行状态翻转（历史→最新），快速连点/直跳不漏翻转
        l2Values.forEach { value -> // 遍历 L2 采样序列
            if (value >= TRIGGER_CLICK_THRESHOLD && !l2Pressed) { // L2 超过阈值且未按下则触发按下
                l2Pressed = true // 标记 L2 已按下
                handleButtonEvent(ControllerButton.LEFT_TRIGGER_CLICK, isPressed = true) // 触发左扳机点击按下
            } else if (value < TRIGGER_CLICK_THRESHOLD && l2Pressed) { // L2 低于阈值且已按下则触发释放
                l2Pressed = false // 标记 L2 已释放
                handleButtonEvent(ControllerButton.LEFT_TRIGGER_CLICK, isPressed = false) // 触发左扳机点击释放
            } // 结束 L2 采样判断
        } // 结束 L2 采样遍历

        // R2 按下/释放检测（逻辑同 L2）
        r2Values.forEach { value -> // 遍历 R2 采样序列
            if (value >= TRIGGER_CLICK_THRESHOLD && !r2Pressed) { // R2 超过阈值且未按下则触发按下
                r2Pressed = true // 标记 R2 已按下
                handleButtonEvent(ControllerButton.RIGHT_TRIGGER_CLICK, isPressed = true) // 触发右扳机点击按下
            } else if (value < TRIGGER_CLICK_THRESHOLD && r2Pressed) { // R2 低于阈值且已按下则触发释放
                r2Pressed = false // 标记 R2 已释放
                handleButtonEvent(ControllerButton.RIGHT_TRIGGER_CLICK, isPressed = false) // 触发右扳机点击释放
            } // 结束 R2 采样判断
        } // 结束 R2 采样遍历
    } // 结束 handleTriggerAxis 函数

    /**
     * 收集某个扳机在本次 [MotionEvent] 内的全部采样值（历史→最新）
     *
     * 快速扳机模式下 Android 会把多次轴采样合并进同一个 MotionEvent，
     * 必须按时间顺序遍历所有历史采样 + 当前采样，避免漏掉中间的 0↔1 翻转。
     *
     * 轴兼容：优先轴每个采样点取 [primaryAxis]；若该采样点为 0 且设备存在
     * [fallbackAxis]，则改用 [fallbackAxis] 的同位置采样值（兼容只报
     * BRAKE/GAS 而不报 LTRIGGER/RTRIGGER 的手柄）。
     *
     * @param event MotionEvent
     * @param primaryAxis 优先轴（如 AXIS_LTRIGGER）
     * @param fallbackAxis 回退轴（如 AXIS_BRAKE）
     * @param device InputDevice（用于判断回退轴是否存在）
     * @return 采样值序列（按时间先后）
     */
    private fun collectTriggerSamples( // 收集扳机采样序列（语法：private fun 私有函数）
        event: MotionEvent, // 运动事件
        primaryAxis: Int, // 优先轴
        fallbackAxis: Int, // 回退轴
        device: InputDevice // 输入设备
    ): List<Float> { // 返回 Float 列表
        val useFallback = device.getMotionRange(fallbackAxis) != null // 回退轴是否存在
        val total = event.historySize + 1 // 采样总数 = 历史采样数 + 当前采样（语法：+1 当前采样）
        val samples = ArrayList<Float>(total) // 创建采样值列表
        for (i in 0 until total) { // 遍历每个采样位置
            val atHistory = i < event.historySize // 是否为历史采样
            var v = if (atHistory) event.getHistoricalAxisValue(primaryAxis, i) // 读取历史采样
                    else event.getAxisValue(primaryAxis) // 读取当前采样
            if (v == 0f && useFallback) { // 优先轴采样为 0 且存在回退轴则回退
                v = if (atHistory) event.getHistoricalAxisValue(fallbackAxis, i) // 读取回退轴历史采样
                    else event.getAxisValue(fallbackAxis) // 读取回退轴当前采样
            } // 结束回退判断
            samples.add(v) // 加入采样列表
        } // 结束采样遍历
        return samples // 返回采样序列
    } // 结束 collectTriggerSamples 函数

    /**
     * 处理按钮事件
     *
     * 核心逻辑:
     * 1. 更新 heldButtons 集合
     * 2. 松开时：若该按键此前触发过 SwitchLayer 层切换，则停用对应层并返回
     * 3. 查找按钮的有效映射（激活层 → 公共层回退）
     * 4. 如果映射是 SwitchLayer，按下时激活目标层并记录（松开时停用）
     * 5. 否则触发 onButtonMapped 回调
     *
     * @param button 统一按钮编码
     * @param isPressed true=按下, false=释放
     */
    fun handleButtonEvent(button: ControllerButton, isPressed: Boolean) { // 处理按钮事件
        // 更新 heldButtons
        if (isPressed) heldButtons.add(button) else heldButtons.remove(button) // 按下则添加、释放则移除（语法：单行 if/else）
        // 通知 UI 实时反馈（悬浮窗映射页 / 层编辑页按钮高亮）
        onButtonStateChanged?.invoke(button, isPressed) // 触发按钮状态回调

        // 松开时：优先检查该按键是否曾触发过层切换，是则停用对应层
        // 这样即使激活层覆盖了该按键的映射，仍能正确停用层
        // 例如：Common 的 Up->SwitchLayer(L1)，layer1 的 Up->KeyboardKey(C)
        //   按下 Up：激活 layer1（layer1 的 Up 不会执行，因为按下时层刚激活）
        //   松开 Up：buttonTriggeredLayers[Up]=layer1，停用 layer1（不执行 layer1 的 Up->C 的 keyup）
        if (!isPressed) { // 松开时分支（语法：! 逻辑非）
            val triggeredLayer = buttonTriggeredLayers.remove(button) // 取出并移除该按钮的触发层记录
            if (triggeredLayer != null) { // 曾触发过层切换则停用
                deactivateLayer(triggeredLayer) // 停用对应操作层
                // 「切层+按键」类型：停用层后还需注入按键松开，避免同时按下的键盘键卡住
                val savedMapping = switchLayerAndKeyMappings.remove(button) // 取出缓存的切层+按键原映射
                if (savedMapping != null) { // 存在缓存映射则注入按键松开
                    onButtonMapped?.invoke(button, false, savedMapping) // 触发映射回调，注入按键松开
                } // 结束缓存映射判断
                return // 提前返回
            } // 结束 if
        } // 结束松开分支

        // 查找有效映射（先查激活层，回退到 Common 层）
        val mapping = getEffectiveMapping(button) // 查找按钮有效映射
        if (mapping == null) { // 无有效映射
            // 松开时也必须通知外部释放：按下时可能是在某个操作层里绑定了动作，
            // 松开前该层已被停用（查不到映射），不通知会让注入的键/鼠标按钮永久卡住
            if (!isPressed) onButtonReleased?.invoke(button) // 仅松开时通知外部释放已注入状态
            return // 提前返回
        } // 结束无映射判断

        // 处理 SwitchLayer 动作（通过按键映射切换层）
        when (val action = mapping.action) { // 按动作类型分发（语法：when 分支表达式 + val 局部捕获）
            is MappedAction.SwitchLayer -> { // 层切换动作分支（语法：is 类型判断分支）
                // 暂停时不处理层切换
                if (isCapturing) { // 捕获中才处理层切换
                    val targetLayer = profile.findLayer(action.layerName) // 查找目标操作层
                    if (targetLayer != null) { // 目标层存在
                        if (isPressed) { // 按下时激活目标层
                            activateLayer(targetLayer) // 激活目标层
                            buttonTriggeredLayers[button] = targetLayer // 记录按钮与层的触发关系
                        } // 结束按下分支
                    } // 结束目标层存在判断
                } // 结束捕获判断
                // 松开时：该按钮不是切层触发键（按下时在层内执行的是其它映射），
                // 必须通知外部释放按下时注入的键鼠状态，避免鼠标/按键卡在按下态
                if (!isPressed) onButtonReleased?.invoke(button) // 释放残留注入状态
            } // 结束 SwitchLayer 分支
            is MappedAction.SwitchLayerAndKey -> { // 切层+按键同时生效分支（语法：is 类型判断分支）
                // 暂停时不处理层切换
                if (isCapturing) { // 捕获中才处理层切换
                    val targetLayer = profile.findLayer(action.layerName) // 查找目标操作层
                    if (targetLayer != null) { // 目标层存在
                        if (isPressed) { // 按下时激活目标层并缓存原映射
                            activateLayer(targetLayer) // 激活目标层
                            buttonTriggeredLayers[button] = targetLayer // 记录按钮与层的触发关系
                            switchLayerAndKeyMappings[button] = mapping // 缓存原映射供松开时注入按键
                        } // 结束按下分支
                    } // 结束目标层存在判断
                } // 结束捕获判断
                // 同时注入键盘按键（与 KeyboardKey 相同的注入路径）
                onButtonMapped?.invoke(button, isPressed, mapping) // 触发映射回调，交由外部注入按键
            } // 结束 SwitchLayerAndKey 分支
            else -> { // 其他动作分支
                // 键盘/鼠标动作 → 通知外部注入
                onButtonMapped?.invoke(button, isPressed, mapping) // 触发映射回调，交由外部注入
            } // 结束 else 分支
        } // 结束 when
    } // 结束 handleButtonEvent 函数

    /**
     * 获取当前按住的按钮集合
     */
    fun getHeldButtons(): Set<ControllerButton> = heldButtons.toSet() // 获取当前按住按钮集合（语法：表达式体函数 + toSet）

    /**
     * 获取第一个已连接手柄的设备ID
     */
    fun getFirstControllerId(): Int? = connectedControllers.keys.firstOrNull() // 获取首个已连接设备 ID（语法：返回可空类型 Int?）

    /**
     * 触发手柄震动
     *
     * ## Android 知识点: VibrationEffect
     * API 26+ 使用 [android.os.VibrationEffect.createOneShot] 创建震动效果。
     * 需要手柄设备支持震动（[ControllerDevice.supportsVibration]）。
     *
     * @param deviceId 设备ID
     * @param durationMs 震动时长（毫秒）
     * @param amplitude 震动强度 (0-255)
     */
    fun vibrate(deviceId: Int, durationMs: Long = 200, amplitude: Int = 255) { // 触发手柄震动（语法：默认参数 = 200 / = 255）
        val controller = connectedControllers[deviceId] ?: return // 设备未连接则返回
        if (!controller.supportsVibration) return // 设备不支持震动则返回
        val vibrator = controller.inputDevice.vibrator ?: return // 无震动器则返回
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { // API 26+ 使用新震动 API
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, amplitude)) // 创建单次震动效果并执行
        } else { // 旧版本分支
            @Suppress("DEPRECATION") // 抑制弃用警告（语法：@Suppress 注解）
            vibrator.vibrate(durationMs) // 使用旧 API 震动
        } // 结束版本判断
    } // 结束 vibrate 函数

    /**
     * 销毁控制器，释放资源
     */
    fun destroy() { // 销毁控制器
        inputManager.unregisterInputDeviceListener(inputDeviceListener) // 注销设备监听器
        deactivateAllLayers() // 停用所有操作层
    } // 结束 destroy 函数

    companion object { // 伴生对象（语法：companion object 静态成员容器）
        private const val TAG = "SteamLikeInput" // 日志标签常量（语法：const val 编译期常量）

        /**
         * 扳机键"按到底"判定阈值
         *
         * 模拟扳机轴值 0.0~1.0，达到此阈值视为按下（触发 click 事件）
         */
        private const val TRIGGER_CLICK_THRESHOLD = 0.5f // 扳机按到底判定阈值
    } // 结束 companion object
} // 结束 SteamInput 类
