package com.steamlike.controller.core // 包声明（语法：package 声明当前文件所属包）

import android.hardware.BatteryState // 导入电量状态类（Android 14+ 公开 API，手柄电量）
import android.os.Build // 导入 Build 类，用于判断系统版本（电量 API 需 Android 14+）
import android.view.InputDevice // 导入输入设备类（语法：import 导入声明）
import android.view.KeyEvent // 导入按键事件类
import android.view.MotionEvent // 导入运动事件类
import kotlin.math.roundToInt // 导入 Float 四舍五入取整扩展函数（语法：import 顶层扩展函数）

/**
 * 已连接的手柄设备信息
 *
 * 封装 Android [InputDevice] 的相关信息，包括设备类型识别、能力检测等。
 * 由 [SteamInput] 在设备连接时通过 [fromInputDevice] 创建，存储在 `connectedControllers` 映射中。
 *
 * ## 设备类型识别
 * 通过 USB Vendor ID 和 Product ID 识别手柄类型（Xbox/PS/Switch/Steam Controller），
 * 用于按键映射修正（如 PS 手柄的 ×/○ 与 Xbox 的 A/B 位置互换）。
 *
 * ## 能力检测
 * 检测设备支持的输入类型:
 * - 是否有左/右摇杆（通过 AXIS_X/Y/Z/RX 等检测）
 * - 是否有模拟扳机（通过 AXIS_LTRIGGER/AXIS_BRAKE 检测）
 * - 是否有十字键（通过 SOURCE_DPAD 检测）
 * - 是否支持震动（通过 Vibrator.hasVibrator() 检测）
 *
 * @param deviceId Android 设备 ID（与 InputEvent.deviceId 一致）
 * @param name 设备名称（来自 InputDevice.name）
 * @param controllerType 手柄类型（用于按键修正）
 * @param inputDevice 原始 Android InputDevice 对象
 * @param supportsVibration 是否支持震动反馈
 * @param hasLeftStick 是否有左摇杆
 * @param hasRightStick 是否有右摇杆
 * @param hasAnalogTriggers 是否有模拟扳机（LT/RT 可读取 0.0~1.0 值）
 * @param hasDpad 是否有十字键
 */
data class ControllerDevice( // 手柄设备信息数据类（语法：data class 自动生成 equals/hashCode/toString/copy）
    val deviceId: Int, // Android 设备 ID（语法：val 只读属性）
    val name: String, // 设备名称
    val controllerType: ControllerType, // 手柄类型（用于按键修正）
    val inputDevice: InputDevice, // 原始 Android InputDevice 对象
    val supportsVibration: Boolean, // 是否支持震动反馈
    val hasLeftStick: Boolean, // 是否有左摇杆
    val hasRightStick: Boolean, // 是否有右摇杆
    val hasAnalogTriggers: Boolean, // 是否有模拟扳机
    val hasDpad: Boolean // 是否有十字键
) { // 数据类主体开始
    companion object { // 伴生对象（语法：companion object 静态成员容器）
        /**
         * 从 Android InputDevice 创建 ControllerDevice
         *
         * 流程:
         * 1. 检查设备来源（SOURCE_GAMEPAD 或 SOURCE_JOYSTICK），都不是则返回 null
         * 2. 检测摇杆、扳机、D-Pad 等能力
         * 3. 检测震动支持
         * 4. 通过 Vendor/Product ID 识别手柄类型
         *
         * @param device Android InputDevice
         * @return ControllerDevice 实例；非手柄设备返回 null
         */
        fun fromInputDevice(device: InputDevice): ControllerDevice? { // 从 InputDevice 创建设备信息（语法：可空返回 ControllerDevice?）
            val sources = device.sources // 获取设备输入来源位掩码
            // 检查是否为手柄类设备（游戏手柄或摇杆）
            val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD // 判断是否含游戏手柄源（语法：and 位与运算）
            val isJoystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK // 判断是否含摇杆源
            if (!isGamepad && !isJoystick) return null // 两者都不是则非手柄设备，返回 null（语法：! 逻辑非 + && 与运算）

            // 检测左摇杆: AXIS_X 和 AXIS_Y 同时存在
            val hasLeftStick = device.getMotionRange(MotionEvent.AXIS_X) != null && // 检测是否存在 AXIS_X 轴（语法：&& 跨行连接条件）
                    device.getMotionRange(MotionEvent.AXIS_Y) != null // 且同时存在 AXIS_Y 轴
            // 检测右摇杆: AXIS_Z 或 AXIS_RX 任一存在
            val hasRightStick = device.getMotionRange(MotionEvent.AXIS_Z) != null || // 检测是否存在 AXIS_Z 轴（语法：|| 或运算）
                    device.getMotionRange(MotionEvent.AXIS_RX) != null // 或存在 AXIS_RX 轴
            // 检测模拟扳机: AXIS_LTRIGGER 或 AXIS_BRAKE 任一存在
            val hasAnalogTriggers = device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || // 检测是否存在 AXIS_LTRIGGER 轴
                    device.getMotionRange(MotionEvent.AXIS_BRAKE) != null // 或存在 AXIS_BRAKE 轴
            // 检测十字键
            val hasDpad = sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD // 判断是否含 D-Pad 来源

            // 检测震动支持
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) { // 获取震动器（语法：if 表达式）
                device.vibrator // API 31+ 直接读取震动器
            } else { // 旧版本分支
                @Suppress("DEPRECATION") // 抑制弃用警告（语法：@Suppress 注解）
                device.vibrator // 旧版本同样读取震动器
            } // 结束 if 表达式
            val supportsVibration = vibrator?.hasVibrator() == true // 判断震动器是否有震动能力（语法：?. 安全调用）

            return ControllerDevice( // 构造设备信息对象（语法：具名参数构造）
                deviceId = device.id, // 设备 ID
                name = device.name, // 设备名称
                controllerType = ControllerType.fromVendorProduct(device.vendorId, device.productId), // 按厂商/产品 ID 识别手柄类型
                inputDevice = device, // 保存原始 InputDevice
                supportsVibration = supportsVibration, // 震动支持标记
                hasLeftStick = hasLeftStick, // 左摇杆标记
                hasRightStick = hasRightStick, // 右摇杆标记
                hasAnalogTriggers = hasAnalogTriggers, // 模拟扳机标记
                hasDpad = hasDpad // 十字键标记
            ) // 结束 ControllerDevice 构造
        } // 结束 fromInputDevice 函数

        /**
         * 读取当前手柄电量
         *
         * 使用 Android 14 (API 34) 的公开 API [InputDevice.getBatteryState]，
         * 遍历已连接输入设备，返回第一个上报电量信息的手柄。
         * 手柄的 HID 电量由内核 power_supply 节点上报给系统，无需特殊权限。
         * 手柄已连接但报告"电量未知"（capacity 为负数，如部分 Xbox 手柄）时，
         * 返回 [GamepadBattery.percent] 为 -1 的对象（界面显示"未知"）。
         *
         * **注意**：部分设备上该调用会阻塞数秒（实测 Xbox 手柄约 10 秒），
         * 必须在后台线程调用，禁止在主线程直接调用。
         *
         * @return 电量信息；系统低于 Android 14、无手柄或手柄不报告电量存在时返回 null
         */
        fun readBattery(): GamepadBattery? { // 读取手柄电量（语法：可空返回类型 GamepadBattery?）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null // Android 14 以下无此 API，直接返回 null（语法：if 单行返回）
            for (deviceId in InputDevice.getDeviceIds()) { // 遍历所有输入设备 ID（语法：for(x in 数组) 遍历）
                val device = InputDevice.getDevice(deviceId) ?: continue // 取设备对象，为空则跳过本次循环（语法：?: 空值合并 + continue）
                val sources = device.sources // 读取设备输入来源位掩码
                val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD // 是否含游戏手柄源（语法：and 位与运算）
                val isJoystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK // 是否含摇杆源
                if (!isGamepad && !isJoystick) continue // 非手柄设备跳过（语法：! 逻辑非 + && 与运算）
                val batteryState = device.batteryState // 取该设备的电量状态对象（该 API 不会返回 null）
                if (!batteryState.isPresent) continue // 设备未上报电量则跳过
                val capacity = batteryState.capacity // 电量原始值（浮点数，0.0~1.0；未知时为负数）
                val charging = batteryState.status == BatteryState.STATUS_CHARGING || // 是否正在充电
                        batteryState.status == BatteryState.STATUS_FULL // 或已充满（语法：|| 或运算，跨行连接）
                return GamepadBattery( // 返回电量信息对象
                    deviceName = device.name ?: "", // 手柄名称（语法：?: 空值合并，避免 null）
                    percent = if (capacity < 0f) -1 // 手柄报告"电量未知"（如部分 Xbox 手柄）时标记为 -1
                    else (capacity * 100f).roundToInt().coerceIn(0, 100), // 否则换算为 0~100 整数并钳制范围（语法：coerceIn 范围钳制）
                    charging = charging // 充电状态标记
                ) // 结束 GamepadBattery 构造
            } // 结束设备遍历 for
            return null // 未找到带电量信息的手柄
        } // 结束 readBattery 函数
    } // 结束 companion object
} // 结束 ControllerDevice 数据类

/**
 * 手柄电量信息
 *
 * 由 [ControllerDevice.readBattery] 读取，用于悬浮窗与主界面显示。
 *
 * @param deviceName 手柄名称（来自 InputDevice.name）
 * @param percent 电量百分比（0~100 整数）；-1 表示手柄报告"电量未知"
 * @param charging 是否充电中或已充满
 */
data class GamepadBattery( // 手柄电量信息数据类（语法：data class 自动生成 equals/hashCode/toString/copy）
    val deviceName: String, // 手柄名称（语法：val 只读属性）
    val percent: Int, // 电量百分比 0~100；-1 表示未知
    val charging: Boolean // 是否充电中/已充满
) { // 数据类主体开始
    /**
     * 界面显示文本
     *
     * 电量未知（[percent] 为 -1）时显示"手柄电量: 未知"，
     * 否则显示"手柄电量: 85%"，充电中/已充满时追加"（充电中）"。
     */
    val displayText: String // val=只读属性；get()=自定义取值逻辑
        get() = if (percent < 0) "手柄电量: 未知" // 电量未知分支
        else "手柄电量: $percent%" + if (charging) "（充电中）" else "" // 有数值分支（语法：字符串模板 + if 表达式）
} // 结束 GamepadBattery 数据类

/**
 * 手柄输入映射器
 *
 * 将 Android 原生 [KeyEvent] / [MotionEvent] 映射到统一的 [ControllerButton] / [Vector2] / Float。
 * 同时处理不同手柄类型的按键修正（如 PS 手柄的 ×/○ 与 Xbox 的 A/B 位置互换）。
 *
 * ## 主要功能
 * 1. **按键映射**: Android KEYCODE_BUTTON_* → ControllerButton 枚举
 * 2. **PS 手柄修正**: PS 手柄的 ×/○ 位置与 Xbox 的 A/B 互换，需要特殊处理
 * 3. **摇杆值读取**: 从 MotionEvent 的 AXIS_X/Y/Z/RX 等轴读取摇杆位置，归一化到 -1.0~1.0
 * 4. **扳机值读取**: 从 AXIS_LTRIGGER/AXIS_BRAKE 等轴读取扳机值，归一化到 0.0~1.0
 *
 * ## 使用场景
 * 在 [SteamInput.dispatchKeyEvent] 和 [SteamInput.dispatchGenericMotionEvent] 中调用，
 * 将系统事件转换为统一的内部表示后再进行动作分发。
 */
object ControllerInputMapper { // 输入映射器单例（语法：object 单例对象）

    /**
     * Android KeyCode → ControllerButton 默认映射表
     *
     * 适用于 Xbox 风格手柄（A/B/X/Y 按标准布局）。
     * PS 手柄需要额外使用 [psKeyCodeOverrides] 修正。
     */
    private val keyCodeMap: Map<Int, ControllerButton> = mapOf( // Android 键码→统一按钮映射表（语法：mapOf 不可变 Map + 显式类型）
        KeyEvent.KEYCODE_BUTTON_A to ControllerButton.A, // A 键映射（语法：to 中缀函数构造 Pair）
        KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B, // B 键映射
        KeyEvent.KEYCODE_BUTTON_X to ControllerButton.X, // X 键映射
        KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y, // Y 键映射
        KeyEvent.KEYCODE_BUTTON_L1 to ControllerButton.LEFT_SHOULDER,    // LB // 左肩键映射
        KeyEvent.KEYCODE_BUTTON_R1 to ControllerButton.RIGHT_SHOULDER,   // RB // 右肩键映射
        KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerButton.LEFT_STICK_CLICK,   // L3 // 左摇杆按下映射
        KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerButton.RIGHT_STICK_CLICK,  // R3 // 右摇杆按下映射
        KeyEvent.KEYCODE_BUTTON_L2 to ControllerButton.LEFT_TRIGGER_CLICK,     // L2 按到底 // 左扳机点击映射
        KeyEvent.KEYCODE_BUTTON_R2 to ControllerButton.RIGHT_TRIGGER_CLICK,    // R2 按到底 // 右扳机点击映射
        KeyEvent.KEYCODE_BUTTON_START to ControllerButton.MENU, // Start 键→菜单键映射
        KeyEvent.KEYCODE_BUTTON_SELECT to ControllerButton.OPTIONS, // Select 键→选项键映射
        KeyEvent.KEYCODE_BUTTON_MODE to ControllerButton.GUIDE,           // Home/PS键 // 中央 Home 键映射
        KeyEvent.KEYCODE_DPAD_UP to ControllerButton.DPAD_UP, // 十字键上映射
        KeyEvent.KEYCODE_DPAD_DOWN to ControllerButton.DPAD_DOWN, // 十字键下映射
        KeyEvent.KEYCODE_DPAD_LEFT to ControllerButton.DPAD_LEFT, // 十字键左映射
        KeyEvent.KEYCODE_DPAD_RIGHT to ControllerButton.DPAD_RIGHT, // 十字键右映射
        KeyEvent.KEYCODE_BUTTON_C to ControllerButton.TOUCHPAD_CLICK      // PS 触控板点击 // PS 触控板键映射
    ) // 结束 mapOf

    /**
     * PS 手柄按键覆盖表
     *
     * PS 手柄的 ×/○ 与 Xbox 的 A/B 物理位置互换:
     * - PS 的 × (Android KEYCODE_BUTTON_A) 在 Xbox 的 A 位置，但 Android 报为 A
     * - PS 的 ○ (Android KEYCODE_BUTTON_B) 在 Xbox 的 B 位置，但 Android 报为 B
     *
     * 为保持统一的游戏体验（A=确认/跳跃，B=取消），需要交换映射:
     * - Android A → ControllerButton.X（PS 的 × 映射到 Xbox 的 X，因为 × 在 PS 上是确认键，对应 Xbox 的 A）
     * - Android X → ControllerButton.A
     *
     * 注意: 这里只覆盖 A/B/X，Y 不变（Y/△ 在两种手柄上位置一致）
     */
    private val psKeyCodeOverrides: Map<Int, ControllerButton> = mapOf( // PS 手柄按键修正表（语法：mapOf 不可变 Map）
        KeyEvent.KEYCODE_BUTTON_A to ControllerButton.X,  // PS × → Xbox X // PS × 修正为 X
        KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B,  // PS ○ → Xbox B（不变）// PS ○ 保持 B
        KeyEvent.KEYCODE_BUTTON_X to ControllerButton.A,  // PS □ → Xbox A // PS □ 修正为 A
        KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y   // PS △ → Xbox Y（不变）// PS △ 保持 Y
    ) // 结束 mapOf

    /**
     * 将 Android KeyCode 映射为统一的 ControllerButton
     *
     * 流程:
     * 1. 如果是 PS 手柄（PS3/PS4/PS5），先查 [psKeyCodeOverrides]
     * 2. 否则查 [keyCodeMap] 默认映射
     *
     * @param keyCode Android KeyEvent.keyCode
     * @param controllerType 手柄类型（用于决定是否应用 PS 修正）
     * @return 对应的 ControllerButton；未映射的按键返回 null
     */
    fun mapKeyCode(keyCode: Int, controllerType: ControllerType): ControllerButton? { // 键码→统一按钮（语法：可空返回 ControllerButton?）
        // PS 手柄特殊处理: ×/○ 与 A/B 位置互换
        if (controllerType == ControllerType.PS3 || controllerType == ControllerType.PS4 || // 判断是否 PS3/PS4 手柄（语法：|| 或运算跨行）
            controllerType == ControllerType.PS5_DUALSENSE // 或 PS5 手柄
        ) { // 结束类型判断
            psKeyCodeOverrides[keyCode]?.let { return it } // 查 PS 修正表，命中即返回（语法：?. 安全调用 + let + 局部返回）
        } // 结束 if
        return keyCodeMap[keyCode] // 否则查默认映射表
    } // 结束 mapKeyCode 函数

    /**
     * 从 MotionEvent 读取摇杆的 2D 位置
     *
     * 不同摇杆使用不同的 MotionEvent 轴:
     * - **左摇杆**: AXIS_X (X轴), AXIS_Y (Y轴)
     * - **右摇杆**: AXIS_Z/AXIS_RX (X轴), AXIS_RZ/AXIS_RY (Y轴)（不同设备用不同轴）
     * - **D-Pad 作为摇杆**: AXIS_HAT_X, AXIS_HAT_Y（值仅 -1/0/1）
     *
     * 返回的 Vector2 范围为 -1.0~1.0，已应用死区（flat）。
     *
     * @param event MotionEvent
     * @param stick 摇杆类型
     * @param device 输入设备（用于查询轴范围）
     * @return 摇杆位置向量
     */
    fun getStickValue(event: MotionEvent, stick: ControllerStick, device: InputDevice): Vector2 { // 读取摇杆 2D 位置（语法：返回 Vector2）
        return when (stick) { // 按摇杆类型分支（语法：when 表达式）
            ControllerStick.LEFT_STICK -> { // 左摇杆分支
                val x = getCenteredAxis(event, device, MotionEvent.AXIS_X) // 读取 X 轴归一化值
                val y = getCenteredAxis(event, device, MotionEvent.AXIS_Y) // 读取 Y 轴归一化值
                Vector2(x, y) // 构造位置向量
            } // 结束左摇杆分支
            ControllerStick.RIGHT_STICK -> { // 右摇杆分支
                // 右摇杆 X 轴: 优先 AXIS_Z，回退到 AXIS_RX
                var x = getCenteredAxis(event, device, MotionEvent.AXIS_Z) // 读取 Z 轴值（语法：var 可变变量）
                if (x == 0f) x = getCenteredAxis(event, device, MotionEvent.AXIS_RX) // Z 轴为零则回退读 RX 轴
                // 右摇杆 Y 轴: 优先 AXIS_RZ，回退到 AXIS_RY
                var y = getCenteredAxis(event, device, MotionEvent.AXIS_RZ) // 读取 RZ 轴值
                if (y == 0f) y = getCenteredAxis(event, device, MotionEvent.AXIS_RY) // RZ 轴为零则回退读 RY 轴
                Vector2(x, y) // 构造位置向量
            } // 结束右摇杆分支
            ControllerStick.DPAD_AS_STICK -> { // 十字键当摇杆分支
                // D-Pad 作为摇杆: HAT 轴值仅 -1/0/1
                val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).coerceIn(-1f, 1f) // 读取 HAT_X 并限幅（语法：coerceIn 限幅函数）
                val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).coerceIn(-1f, 1f) // 读取 HAT_Y 并限幅
                Vector2(x, y) // 构造位置向量
            } // 结束十字键分支
        } // 结束 when
    } // 结束 getStickValue 函数

    /**
     * 从 MotionEvent 读取扳机的模拟值
     *
     * 不同设备使用不同的轴表示扳机:
     * - **左扳机 (LT)**: 优先 AXIS_LTRIGGER，回退到 AXIS_BRAKE
     * - **右扳机 (RT)**: 优先 AXIS_RTRIGGER，回退到 AXIS_GAS
     *
     * 返回值范围为 0.0（完全释放）~ 1.0（完全按下），已归一化。
     *
     * @param event MotionEvent
     * @param trigger 扳机类型
     * @param device 输入设备
     * @return 扳机值（0.0~1.0）
     */
    fun getTriggerValue(event: MotionEvent, trigger: ControllerTrigger, device: InputDevice): Float { // 读取扳机模拟值（语法：返回 Float）
        val rawValue = when (trigger) { // 按扳机类型读取原始值（语法：when 表达式）
            ControllerTrigger.LEFT_TRIGGER -> { // 左扳机分支
                var v = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) // 读取 LTRIGGER 轴值
                if (v <= 0f) v = event.getAxisValue(MotionEvent.AXIS_BRAKE) // 值无效则回退读 BRAKE 轴
                v // 返回读取值
            } // 结束左扳机分支
            ControllerTrigger.RIGHT_TRIGGER -> { // 右扳机分支
                var v = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) // 读取 RTRIGGER 轴值
                if (v <= 0f) v = event.getAxisValue(MotionEvent.AXIS_GAS) // 值无效则回退读 GAS 轴
                v // 返回读取值
            } // 结束右扳机分支
        } // 结束 when
        return normalizeTrigger(rawValue, device, trigger) // 归一化到 0~1 并返回
    } // 结束 getTriggerValue 函数

    /**
     * 读取摇杆轴值并归一化到 -1.0~1.0
     *
     * 处理步骤:
     * 1. 查询轴的运动范围（min/max/flat）
     * 2. 将原始值减去中点，得到相对位移
     * 3. 除以最大距离，归一化到 -1.0~1.0
     * 4. 应用 flat 死区（小于 flat 的值归零，防止漂移）
     *
     * @param event MotionEvent
     * @param device 输入设备
     * @param axis 轴常量（如 AXIS_X）
     * @return 归一化后的轴值（-1.0~1.0）
     */
    private fun getCenteredAxis(event: MotionEvent, device: InputDevice, axis: Int): Float { // 读轴值并归一化（语法：private 私有函数 + 返回 Float）
        val range = device.getMotionRange(axis, event.source) ?: return 0f // 获取轴范围，无则返回 0（语法：?: 空值合并）
        val value = event.getAxisValue(axis) // 读取原始轴值
        val flat = range.flat  // 设备的死区值 // 设备死区阈值
        val mid = (range.min + range.max) / 2.0f  // 轴的中点 // 轴范围中点
        val adjValue = value - mid  // 相对中点的位移 // 相对中点的位移
        val maxDistance = if (adjValue > 0) range.max - mid else mid - range.min // 按位移方向取最大距离（语法：if 表达式）
        val normalized = if (maxDistance == 0f) 0f else (adjValue / maxDistance).coerceIn(-1f, 1f) // 归一化到 -1~1（语法：coerceIn 限幅）
        // 应用设备死区: 小于 flat 的输入归零
        return if (kotlin.math.abs(normalized) < flat) 0f else normalized // 死区内归零否则返回原值（语法：kotlin.math.abs 绝对值）
    } // 结束 getCenteredAxis 函数

    /**
     * 归一化扳机值到 0.0~1.0
     *
     * 不同设备的扳机轴范围不同:
     * - 部分设备: 0.0~1.0（直接使用）
     * - 部分设备: -1.0~1.0（需要 (value+1)/2 转换）
     *
     * 通过检测 range.min/max 判断设备类型并选择合适的转换方式。
     *
     * @param rawValue 原始轴值
     * @param device 输入设备
     * @param trigger 扳机类型（用于查询对应轴）
     * @return 归一化后的扳机值（0.0~1.0）
     */
    private fun normalizeTrigger(rawValue: Float, device: InputDevice, trigger: ControllerTrigger): Float { // 归一化扳机值（语法：private 私有函数）
        val axis = when (trigger) { // 按扳机类型取对应轴（语法：when 表达式）
            ControllerTrigger.LEFT_TRIGGER -> MotionEvent.AXIS_LTRIGGER // 左扳机对应轴
            ControllerTrigger.RIGHT_TRIGGER -> MotionEvent.AXIS_RTRIGGER // 右扳机对应轴
        } // 结束 when
        val range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) // 查询轴范围
            ?: return rawValue.coerceIn(0f, 1f) // 无范围则直接限幅返回（语法：?: 空值合并跨行）
        val min = range.min // 轴最小值
        val max = range.max // 轴最大值
        // 如果范围是 -1.0~1.0，需要转换为 0.0~1.0
        return if (min >= -1.01f && min <= -0.99f && max >= 0.99f && max <= 1.01f) { // 判断轴范围是否为 -1~1（语法：&& 与运算）
            ((rawValue + 1f) / 2f).coerceIn(0f, 1f) // 用 (value+1)/2 公式转换并限幅
        } else { // 其他范围分支
            rawValue.coerceIn(0f, 1f) // 直接限幅到 0~1
        } // 结束 if
    } // 结束 normalizeTrigger 函数
} // 结束 ControllerInputMapper 单例对象
