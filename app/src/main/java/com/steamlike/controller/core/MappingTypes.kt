package com.steamlike.controller.core // 包声明（语法：package 声明当前文件所属包）

import com.steamlike.controller.injection.MouseButton // 导入鼠标按钮枚举（语法：import 导入声明）

/**
 * 鼠标按钮显示名称扩展函数
 */
fun MouseButton.toDisplayName(): String = when (this) { // 扩展函数：鼠标按钮转中文名（语法：fun MouseButton.xxx() 扩展函数 + when 表达式 + this 接收者）
    MouseButton.LEFT -> "鼠标左键" // 左键显示名（语法：when 分支 -> 返回值）
    MouseButton.RIGHT -> "鼠标右键" // 右键显示名
    MouseButton.MIDDLE -> "鼠标中键" // 中键显示名
    MouseButton.FORWARD -> "鼠标前进键" // 前进键显示名
    MouseButton.BACK -> "鼠标后退键" // 后退键显示名
} // 结束 toDisplayName 扩展函数

/**
 * 映射动作类型（sealed class，密封类）
 *
 * 每个手柄按键可以映射到以下动作之一。使用密封类确保 when 表达式穷举所有可能，
 * 编译器会在添加新动作类型时提示更新所有处理逻辑。
 *
 * ## 支持的动作类型
 * - [KeyboardKey]: 键盘按键（包括字母/数字/功能键/Ctrl/Shift/Alt 等）
 * - [MouseClick]: 鼠标点击（左/右/中键）
 * - [SwitchLayer]: 切换操作层（按住激活，松开回公共层）
 * - [MouseMove]: 鼠标移动（摇杆专用，控制光标）
 * - [LookAround]: 视角控制（摇杆专用，控制游戏视角）
 *
 * ## Android 知识点: sealed class
 * Kotlin 密封类限制子类必须在同一文件中定义，编译器能保证 when 表达式覆盖所有分支。
 * 适用于有限状态的场景（如动作类型枚举）。与 enum class 不同，密封类每个子类可以
 * 有不同的字段，更灵活。
 */
sealed class MappedAction { // 映射动作密封类（语法：sealed class 密封类，子类须在同一文件定义）

    /**
     * 键盘按键动作
     *
     * 将手柄按键映射为键盘按键。keyCode 使用 Android [KeyEvent] 常量，
     * 由 [com.steamlike.controller.injection.BridgeInputInjector] 转换为 Windows VK Code 注入。
     *
     * ## 支持的按键
     * - 字母: A-Z ([KeyEvent.KEYCODE_A] ~ [KeyEvent.KEYCODE_Z])
     * - 数字: 0-9 ([KeyEvent.KEYCODE_0] ~ [KeyEvent.KEYCODE_9])
     * - 功能键: F1-F12 ([KeyEvent.KEYCODE_F1] ~ [KeyEvent.KEYCODE_F12])
     * - 修饰键: Ctrl/Shift/Alt ([KeyEvent.KEYCODE_CTRL_LEFT] 等)
     * - 符号: 空格/回车/Tab/Esc/方向键等
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量
     */
    data class KeyboardKey(val keyCode: Int) : MappedAction() // 键盘按键动作（语法：data class 数据类 + 冒号继承密封类）

    /**
     * 鼠标点击动作
     *
     * 将手柄按键映射为鼠标按钮点击。
     *
     * @param button 鼠标按钮（[MouseButton.LEFT]/[MouseButton.RIGHT]/[MouseButton.MIDDLE]）
     */
    data class MouseClick(val button: MouseButton) : MappedAction() // 鼠标点击动作

    /**
     * 切换操作层动作
     *
     * 将手柄按键映射为操作层切换。按下时激活目标层，松开时回到公共层。
     * 用于实现"按住D-Pad上切换到Layer1，松开回Common"等功能。
     *
     * @param layerName 目标层名称（如 "Layer1"、"Combat"）
     */
    data class SwitchLayer(val layerName: String) : MappedAction() // 切换操作层动作

    /**
     * 切换操作层 + 按键映射同时生效动作
     *
     * 按下时同时做两件事:
     * 1. 激活目标操作层（与 [SwitchLayer] 相同）
     * 2. 注入一个键盘按键按下（与 [KeyboardKey] 相同，由 BridgeInputInjector 转换为 VK 码注入）
     *
     * 松开时: 停用目标层 + 注入该按键松开，两者同时结束。
     * 用于"按住切层键的同时还按住某个键"的场景，例如按住 D-Pad 上切换到战斗层
     * 的同时按住 Ctrl（切层 + 组合键同时生效）。
     *
     * @param layerName 目标层名称（如 "Layer1"、"Combat"）
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量（按下切层时同时按下的键盘键）
     */
    data class SwitchLayerAndKey(val layerName: String, val keyCode: Int) : MappedAction() // 切换层+按键同时生效动作

    /**
     * 数字小键盘按键动作
     *
     * 按下时注入一个数字小键盘按键（Num1~Num9、Num0、NumLock），用于配合游戏内
     * 动作条分组/翻页快捷键（例如 WoW 中用 Num1~Num9 快速切换动作条分组）。
     * 与 [KeyboardKey] 注入路径相同，由 BridgeInputInjector 转换为 Windows VK 码注入。
     *
     * @param keyCode Android [KeyEvent.KEYCODE_NUMPAD_*] 常量
     */
    data class NumpadKey(val keyCode: Int) : MappedAction() // 数字小键盘按键动作

    /**
     * 鼠标移动动作（摇杆专用）
     *
     * 将摇杆映射为鼠标相对移动，用于控制光标位置。
     * 摇杆X/Y值会乘以 [GlobalSettings.cursorSpeed] 后发送给 Windows。
     */
    data object MouseMove : MappedAction() // 鼠标移动动作（语法：data object 数据单例对象）

    /**
     * 视角控制动作（摇杆专用）
     *
     * 将摇杆映射为鼠标相对移动，用于控制游戏视角（如 WoW 中的右键拖拽视角）。
     * 摇杆X/Y值会乘以 [GlobalSettings.lookSensitivity] 后发送给 Windows。
     */
    data object LookAround : MappedAction() // 视角控制动作

    /**
     * 鼠标长按切换动作
     *
     * 第一次按下发送 MouseDown（不松开），第二次按下发送 MouseUp。
     * 适用于"按一下开始长按，再按一下释放"的场景。
     *
     * @param button 鼠标按钮（[MouseButton.LEFT]/[MouseButton.RIGHT]等）
     */
    data class MouseToggle(val button: MouseButton) : MappedAction() // 鼠标长按切换动作

    /**
     * 鼠标长按交换动作
     *
     * 处理时检查左右键的长按锁存状态并交换：
     * - 右键长按中 → 释放右键，改为长按左键
     * - 左键长按中 → 释放左键，改为长按右键
     * 若左右键都没有处于长按锁存状态，则不做任何处理。
     */
    data object MouseSwapToggle : MappedAction() // 鼠标长按交换动作

    /**
     * 释放鼠标所有长按锁存动作
     *
     * 一次性释放所有处于长按锁存状态的鼠标按钮（左键/右键/中键，含左摇杆移动
     * 自动锁定的右键）：逐个发送 MouseUp 并清除锁存状态，同时通知悬浮窗边框恢复。
     * 适用于"一键解除所有鼠标长按"的场景。无长按锁存时不做任何处理。
     */
    data object MouseReleaseAll : MappedAction() // 释放鼠标所有长按动作

    /**
     * 鼠标交互键动作
     *
     * 记录左右键当前的长按锁存状态后，临时释放已锁定的键，立即依次发送
     * 「左键点击 → 右键点击」，最后把左右键恢复到记录时的锁存状态。
     * 用于在保持原有锁定语义的前提下插入一组点击。
     */
    data object MouseClickSequence : MappedAction() // 鼠标交互键动作

    /**
     * 鼠标滚轮上滚动作
     *
     * 将手柄按键映射为鼠标滚轮向上滚动（发送一次固定增量的滚轮事件）。
     * 每次按下发送一次滚轮上滚，无需松开。
     */
    data object MouseScrollUp : MappedAction() // 鼠标滚轮上滚动作

    /**
     * 鼠标滚轮下滚动作
     *
     * 将手柄按键映射为鼠标滚轮向下滚动（发送一次固定增量的滚轮事件）。
     * 每次按下发送一次滚轮下滚，无需松开。
     */
    data object MouseScrollDown : MappedAction() // 鼠标滚轮下滚动作

    /**
     * 切换悬浮窗显示/隐藏
     *
     * 按下时切换悬浮窗在收起和按键映射列表之间切换。
     * 与手柄 Home 键的内置功能相同，但可映射到任意按钮。
     */
    data object ToggleOverlay : MappedAction() // 切换悬浮窗动作

    /**
     * 切换安卓系统键盘显示/隐藏
     *
     * 按下时切换软键盘的显示状态。
     * 在需要触摸输入的游戏场景中很有用（如输入聊天文字）。
     */
    data object ToggleKeyboard : MappedAction() // 切换安卓系统键盘动作

    /**
     * 切换手柄捕获状态
     *
     * 按下时切换 GamepadInputView 的显示/隐藏（暂停/恢复手柄事件捕获）。
     * 暂停时系统返回手势恢复，恢复时手柄事件重新被捕获。
     */
    data object ToggleCapture : MappedAction() // 切换手柄捕获状态动作
} // 结束 MappedAction 密封类

/**
 * 按键映射定义
 *
 * 每个手柄按键的完整映射，包含主动作和最多 3 个子命令。
 *
 * ## 子命令（Sub-Command）机制
 * 子命令是 Steam Input 风格的组合键实现。每个按键映射可以添加最多 3 个子命令，
 * 最终输出为组合键。
 *
 * ### 工作原理
 * ```
 * 手柄X → KeyMapping(action=KeyboardKey(Alt), subCommands=[KEYCODE_3])
 * 输出: 按下Alt → 按下3 → 松开3 → 松开Alt（即 Alt+3 组合键）
 * ```
 *
 * ### 多子命令示例
 * ```
 * 手柄X → KeyMapping(action=KeyboardKey(Ctrl), subCommands=[KEYCODE_SHIFT, KEYCODE_3])
 * 输出: 按下Ctrl → 按下Shift → 按下3 → 松开3 → 松开Shift → 松开Ctrl（即 Ctrl+Shift+3）
 * ```
 *
 * ### 约束
 * - 子命令只支持键盘按键（[MappedAction.KeyboardKey]），不支持鼠标/切换层
 * - 子命令最多 3 个
 * - 主动作为 [MappedAction.SwitchLayer] 或摇杆动作时，子命令无效
 *
 * ## 长按映射（Long-Press）
 * 每个按键可选配置一个「长按动作」（[longPressAction]）。判定在**松开时**进行：
 * - 仅当配置了长按动作时该按键才进入判定；未配置时保持原有「按下即触发」行为
 * - 按住时长 < [GlobalSettings.longPressThresholdMs]：执行短按动作（按下+立即松开）
 * - 按住时长 ≥ 阈值：执行长按动作（保持型动作为按住，松开时释放；瞬时动作触发一次）
 * - 全局开关 [GlobalSettings.longPressFallbackToShort] 开启时，未配置长按动作的按键
 *   也会进入判定，长按松开时回退执行短按动作
 *
 * @param action 主动作（短按动作）
 * @param subCommands 子命令列表（最多 3 个 Android [KeyEvent.KEYCODE_*] 常量）
 * @param longPressAction 长按动作（null=不启用长按判定）
 */
data class KeyMapping( // 按键映射定义（语法：data class 数据类）
    val action: MappedAction, // 主动作（短按动作）
    val subCommands: List<Int> = emptyList(), // 子命令列表（语法：默认参数 = emptyList()）
    val longPressAction: MappedAction? = null // 长按动作（语法：可空类型 + 默认参数 = null）
) { // 数据类主体开始
    init { // 初始化块校验（语法：init 初始化块）
        require(subCommands.size <= MAX_SUB_COMMANDS) { // 校验子命令数量不超上限（语法：require 前置条件检查）
            "子命令最多 $MAX_SUB_COMMANDS 个，当前 ${subCommands.size} 个" // 错误消息（语法：字符串模板 $var 与 ${expr}）
        } // 结束 require
    } // 结束 init 块

    /**
     * 转换为人类可读的描述字符串（用于 UI 显示）
     *
     * 示例: "Alt+3"、"Ctrl+Shift+3"、"鼠标左键"、"切换到 Layer1"
     */
    fun describe(): String { // 转为可读描述（语法：fun 成员函数声明）
        val parts = mutableListOf<String>() // 描述片段列表（语法：mutableListOf + <String> 泛型）
        parts.add(actionLabel(action)) // 加入短按动作描述
        subCommands.forEach { parts.add(keyCodeToName(it)) } // 追加各子命令名（语法：forEach lambda + it 默认参数名）
        var text = parts.joinToString("+") // 用 + 拼接为最终描述
        val lp = longPressAction // 取长按动作（局部变量便于智能转换）
        if (lp != null) { // 若配置了长按动作则追加显示
            text += " / 长按:${actionLabel(lp)}" // 追加长按动作描述（语法：+= 字符串拼接）
        } // 结束长按描述判断
        return text // 返回完整描述
    } // 结束 describe 函数

    /**
     * 将单个动作转换为可读名称片段（供 [describe] 复用）
     */
    private fun actionLabel(action: MappedAction): String = when (action) { // 按动作类型分支（语法：when 表达式）
        is MappedAction.KeyboardKey -> keyCodeToName(action.keyCode) // 键盘键：按键名（语法：is 类型判断分支）
        is MappedAction.MouseClick -> action.button.toDisplayName() // 鼠标点击：按钮名
        is MappedAction.SwitchLayer -> "切换→${action.layerName}" // 层切换：目标层名
        is MappedAction.SwitchLayerAndKey -> "切换→${action.layerName}+${keyCodeToName(action.keyCode)}" // 层切换+按键
        is MappedAction.NumpadKey -> "小键盘${keyCodeToName(action.keyCode)}" // 数字小键盘
        is MappedAction.MouseMove -> "鼠标移动" // 鼠标移动描述
        is MappedAction.LookAround -> "视角控制" // 视角控制描述
        is MappedAction.MouseToggle -> "长按${action.button.toDisplayName()}" // 鼠标长按描述
        is MappedAction.MouseSwapToggle -> "长按交换" // 鼠标长按交换描述
        is MappedAction.MouseReleaseAll -> "释放鼠标长按" // 释放鼠标所有长按描述
        is MappedAction.MouseClickSequence -> "交互键" // 鼠标交互键描述
        is MappedAction.MouseScrollUp -> "滚轮上滚" // 滚轮上滚描述
        is MappedAction.MouseScrollDown -> "滚轮下滚" // 滚轮下滚描述
        is MappedAction.ToggleOverlay -> "切换悬浮窗" // 切换悬浮窗描述
        is MappedAction.ToggleKeyboard -> "切换键盘" // 切换键盘描述
        is MappedAction.ToggleCapture -> "切换捕获" // 切换捕获描述
    } // 结束 when

    companion object { // 伴生对象（语法：companion object 静态成员容器）
        /** 子命令最大数量 */
        const val MAX_SUB_COMMANDS = 3 // 子命令数量上限（语法：const val 编译期常量）

        /** Shift 符号键码偏移：数字键码 + 该偏移 = 需要按住 Shift 的数字键符号（如 1008=Shift+1 → "!"） */
        const val SHIFT_SYMBOL_OFFSET = 1000 // Shift 符号键码偏移常量

        /** Shift 符号字符表：数字键码(7~16) → 按住 Shift 时输出的符号字符（用整数键码，避免测试依赖 Android 框架） */
        private val shiftSymbolChars = mapOf( // Shift 符号字符映射表（语法：mapOf 不可变映射）
            7 to ')',  // KEYCODE_0 // Shift+0 → 右括号
            8 to '!',  // KEYCODE_1 // Shift+1 → 叹号
            9 to '@',  // KEYCODE_2 // Shift+2 → at 符号
            10 to '#', // KEYCODE_3 // Shift+3 → 井号
            11 to '$', // KEYCODE_4 // Shift+4 → 美元符号
            12 to '%', // KEYCODE_5 // Shift+5 → 百分号
            13 to '^', // KEYCODE_6 // Shift+6 → 脱字符
            14 to '&', // KEYCODE_7 // Shift+7 → 与号
            15 to '*', // KEYCODE_8 // Shift+8 → 星号
            16 to '(', // KEYCODE_9 // Shift+9 → 左括号
            68 to '~', // KEYCODE_GRAVE // Shift+` → 波浪号
            69 to '_', // KEYCODE_MINUS // Shift+- → 下划线
            70 to '+', // KEYCODE_EQUALS // Shift+= → 加号
        ) // 结束符号字符映射

        /** 判断是否为 Shift 符号键码（键码 = 偏移 + 数字键码） */
        fun isShiftSymbolKey(keyCode: Int): Boolean = keyCode >= SHIFT_SYMBOL_OFFSET // 符号键码判断函数（语法：单表达式函数）

        /** 取 Shift 符号键码对应的实际数字键码 */
        fun shiftSymbolBaseKey(keyCode: Int): Int = keyCode - SHIFT_SYMBOL_OFFSET // 还原为实际数字键码函数

        /**
         * Android KeyCode 转换为人类可读名称
         *
         * 覆盖字母/数字/功能键/修饰键/符号键等常用按键。
         * 使用 Android [KeyEvent] 常量值，但直接使用整数避免测试时依赖 Android 框架。
         *
         * ## Android KeyCode 常用值
         * - A=29, B=30, ..., Z=54
         * - 0=7, 1=8, ..., 9=16
         * - F1=131, F2=132, ..., F12=142
         * - Shift=59/60, Ctrl=113/114, Alt=57/58
         * - Space=62, Enter=66, Tab=61, Esc=111
         */
        fun keyCodeToName(keyCode: Int): String { // 键码转名称（语法：伴生对象内静态函数）
            // Shift 符号键（编码 = SHIFT_SYMBOL_OFFSET + 数字键码）优先识别
            if (isShiftSymbolKey(keyCode)) { // 判断是否 Shift 符号编码（语法：if 分支 + 单表达式函数调用）
                return shiftSymbolChars[shiftSymbolBaseKey(keyCode)]?.toString() ?: keyCode.toString() // 返回对应符号字符，未知编码则返回原始数字（语法：Map 下标 + ?: 空值合并）
            } // 结束 Shift 符号识别
            // 字母 A-Z (KEYCODE_A=29 ~ KEYCODE_Z=54)
            if (keyCode in 29..54) { // 判断是否字母区（语法：in 范围判断 + 区间 29..54）
                return ('A' + (keyCode - 29)).toString() // 计算对应字母字符并转字符串（语法：Char 算术 + toString）
            } // 结束 if
            // 数字 0-9 (KEYCODE_0=7 ~ KEYCODE_9=16)
            if (keyCode in 7..16) { // 判断是否数字区
                return ('0' + (keyCode - 7)).toString() // 计算对应数字字符并转字符串
            } // 结束 if
            // 功能键 F1-F12 (KEYCODE_F1=131 ~ KEYCODE_F12=142)
            if (keyCode in 131..142) { // 判断是否功能键区
                return "F${keyCode - 131 + 1}" // 计算功能键编号（语法：字符串模板 ${expr}）
            } // 结束 if
            // 其他常用键
            return when (keyCode) { // 常用键查表（语法：when 分支表达式）
                62 -> "Space"           // KEYCODE_SPACE // 空格键
                66 -> "Enter"           // KEYCODE_ENTER // 回车键
                61 -> "Tab"             // KEYCODE_TAB // Tab 键
                111 -> "Esc"            // KEYCODE_ESCAPE // Esc 键
                4 -> "Back"             // KEYCODE_BACK // 返回键
                67 -> "Backspace"       // KEYCODE_DEL // 退格键
                59, 60 -> "Shift"        // KEYCODE_SHIFT_LEFT/RIGHT // 左右 Shift 键
                113, 114 -> "Ctrl"       // KEYCODE_CTRL_LEFT/RIGHT // 左右 Ctrl 键
                57, 58 -> "Alt"          // KEYCODE_ALT_LEFT/RIGHT // 左右 Alt 键
                19 -> "↑"               // KEYCODE_DPAD_UP // 上方向键
                20 -> "↓"               // KEYCODE_DPAD_DOWN // 下方向键
                21 -> "←"               // KEYCODE_DPAD_LEFT // 左方向键
                22 -> "→"               // KEYCODE_DPAD_RIGHT // 右方向键
                69 -> "-"               // KEYCODE_MINUS // 减号
                70 -> "="               // KEYCODE_EQUALS // 等号
                71 -> "["               // KEYCODE_LEFT_BRACKET // 左方括号
                72 -> "]"               // KEYCODE_RIGHT_BRACKET // 右方括号
                74 -> ";"               // KEYCODE_SEMICOLON // 分号
                75 -> "'"               // KEYCODE_APOSTROPHE // 撇号
                73 -> "\\"              // KEYCODE_BACKSLASH // 反斜杠
                55 -> ","               // KEYCODE_COMMA // 逗号
                56 -> "."               // KEYCODE_PERIOD // 句号
                76 -> "/"               // KEYCODE_SLASH // 斜杠
                68 -> "`"               // KEYCODE_GRAVE // 反引号
                115 -> "CapsLock"       // KEYCODE_CAPS_LOCK // 大写锁定键
                143 -> "NumLock"        // KEYCODE_NUM_LOCK // 数字锁定键
                116 -> "ScrollLock"     // KEYCODE_SCROLL_LOCK // 滚动锁定键
                124 -> "Insert"         // KEYCODE_INSERT // 插入键
                123 -> "Home"           // KEYCODE_HOME // Home 键
                92 -> "PageUp"          // KEYCODE_PAGE_UP // 上翻页键
                93 -> "PageDown"        // KEYCODE_PAGE_DOWN // 下翻页键
                122 -> "End"            // KEYCODE_MOVE_END // End 键
                144 -> "Num0"           // KEYCODE_NUMPAD_0 // 小键盘数字 0
                145 -> "Num1"           // KEYCODE_NUMPAD_1 // 小键盘数字 1
                146 -> "Num2"           // KEYCODE_NUMPAD_2 // 小键盘数字 2
                147 -> "Num3"           // KEYCODE_NUMPAD_3 // 小键盘数字 3
                148 -> "Num4"           // KEYCODE_NUMPAD_4 // 小键盘数字 4
                149 -> "Num5"           // KEYCODE_NUMPAD_5 // 小键盘数字 5
                150 -> "Num6"           // KEYCODE_NUMPAD_6 // 小键盘数字 6
                151 -> "Num7"           // KEYCODE_NUMPAD_7 // 小键盘数字 7
                152 -> "Num8"           // KEYCODE_NUMPAD_8 // 小键盘数字 8
                153 -> "Num9"           // KEYCODE_NUMPAD_9 // 小键盘数字 9
                154 -> "Num/"           // KEYCODE_NUMPAD_DIVIDE // 小键盘除号
                155 -> "Num*"           // KEYCODE_NUMPAD_MULTIPLY // 小键盘乘号
                156 -> "Num-"           // KEYCODE_NUMPAD_SUBTRACT // 小键盘减号
                157 -> "Num+"           // KEYCODE_NUMPAD_ADD // 小键盘加号
                158 -> "Num."           // KEYCODE_NUMPAD_DOT // 小键盘小数点
                159 -> "Num,"           // KEYCODE_NUMPAD_COMMA // 小键盘逗号
                160 -> "NumEnter"       // KEYCODE_NUMPAD_ENTER // 小键盘回车
                else -> "Key($keyCode)" // 未知键兜底（语法：else 兜底分支 + 字符串模板）
            } // 结束 when
        } // 结束 keyCodeToName 函数
    } // 结束 companion object
} // 结束 KeyMapping 数据类

/**
 * 操作层
 *
 * 一个操作层包含自己的按键映射表。当层激活时，按键查询优先使用本层映射，
 * 本层没有的按键回退到公共层（[ControllerProfile.commonLayer]）。
 *
 * ## 层类型
 * - **公共层** (commonLayer): 名称为 "Common"，始终激活
 * - **操作层 1-10**
 *
 * ## 触发按键机制
 * 层切换由 **公共层的 [MappedAction.SwitchLayer] 映射** 驱动：
 * 在公共层把某个手柄按键绑定为 `SwitchLayer("Layer1")`，按下该键激活 Layer1、松开回到公共层。
 * 例如: 公共层 `DPAD_UP → SwitchLayer("Layer1")`，按住 D-Pad 上 → 激活 Layer1，松开 → 回 Common。
 * 层编辑页的「切入按键」按钮即用于读写公共层中的这条 SwitchLayer 映射。
 *
 * @param name 层名称（如 "Common"、"Layer1"、"战斗"）
 * @param buttonMappings 按键映射表（ControllerButton → KeyMapping）
 */
data class OperationLayer( // 操作层定义（语法：data class 数据类）
    val name: String, // 层名称（语法：val 只读属性）
    val buttonMappings: MutableMap<ControllerButton, KeyMapping> = mutableMapOf() // 按键映射表（语法：MutableMap 可变 Map + 默认参数）
) { // 数据类主体开始
    /**
     * 查询本层是否有指定按钮的映射
     */
    fun getMapping(button: ControllerButton): KeyMapping? = buttonMappings[button] // 查询按钮映射（语法：表达式体函数 + 可空返回 KeyMapping?）
} // 结束 OperationLayer 数据类

/**
 * 全局设置（所有层统一）
 *
 * 摇杆相关参数统一配置，不区分操作层。
 *
 * @param deadzone 摇杆死区（0.0~1.0），小于此值的输入归零。默认 0.15
 * @param lookSensitivity 右摇杆视角灵敏度倍率（0.1~5.0），越大移动越快。默认 0.5
 * @param cursorSpeed 光标移动速度倍率（>1.0 更快，<1.0 更慢）。默认 1.0
 * @param lookSmoothing 视角平滑系数（0.0~0.95），0=关闭平滑，越大越顺滑但延迟略增。默认 0.5
 * @param lookAcceleration 视角加速曲线指数（0.5~3.0），1.0=线性，>1轻推更慢重推更快，<1相反。默认 1.5
 * @param longPressThresholdMs 长按判定阈值（毫秒），按住时长达到该值视为长按。默认 500
 * @param longPressFallbackToShort 长按未配置动作时，是否在松开时回退执行短按动作。默认 false
 * @param wasdAutoRightLock 左摇杆移动（WASD）时是否自动锁定鼠标右键。默认 true
 * @param wasdAutoCenterMouse 自动锁定右键前是否先把鼠标移到屏幕正中央。默认 true
 * @param cursorParkOffscreen 「居中」时的落点：true=屏幕中央正下方、屏幕下边缘之外 1/10 屏高处，false=屏幕正中央。默认 true
 */
data class GlobalSettings( // 全局设置（语法：data class 数据类）
    val deadzone: Float = 0.15f, // 摇杆死区（语法：默认参数 = 0.15f）
    val lookSensitivity: Float = 0.5f, // 视角灵敏度倍率
    val cursorSpeed: Float = 1.0f, // 光标移动速度倍率
    val lookSmoothing: Float = 0.5f, // 视角平滑系数
    val lookAcceleration: Float = 1.5f, // 视角加速曲线指数
    val longPressThresholdMs: Int = 500, // 长按判定阈值（毫秒）
    val longPressFallbackToShort: Boolean = false, // 长按未配置动作时是否回退执行短按动作
    val rightButtonUseFunctionKeys: Boolean = true, // 右键锁定/解锁是否发送 F11/F12（false=发送真实鼠标右键按下/松开）
    val wasdAutoRightLock: Boolean = true, // 左摇杆移动时是否自动锁定鼠标右键（默认开启）
    val wasdAutoCenterMouse: Boolean = true, // 自动锁定右键前是否先把鼠标移到屏幕正中央（默认开启）
    val cursorParkOffscreen: Boolean = true // 居中落点：true=屏幕中央正下方屏外 1/10 屏高，false=屏幕正中央（默认屏幕外下方）
) // 结束 GlobalSettings 数据类

/**
 * 操作集（Action Set）
 *
 * 一组完整的操作层配置，位于「操作层」之上。切换操作集时，其下的公共层与所有
 * 操作层**整体切换**（每个操作集拥有独立的公共层 + 10 个操作层）。
 *
 * ## 与 Steam Input 的对应关系
 * Steam Input 中一个「动作集（Action Set）」针对一种游戏场景（如战斗/菜单/载具），
 * 动作集内部再通过「动作层（Action Layer）」叠加微调。本项目按此模型：
 * - **操作集**: 独立命名，可添加/删除/拷贝/切换（如「默认」「坦克」「治疗」）
 * - **操作集内**的公共层 + 操作层: 切层机制与原来一致（公共层 SwitchLayer 映射驱动）
 *
 * @param name 操作集名称（可自定义，默认「默认」）
 * @param commonLayer 本操作集的公共层（始终激活）
 * @param layers 本操作集的操作层列表（最多 [ControllerProfile.MAX_LAYERS] 个）
 */
data class ActionSet( // 操作集定义（语法：data class 数据类）
    val name: String, // 操作集名称
    val commonLayer: OperationLayer, // 本操作集的公共层
    val layers: List<OperationLayer> // 本操作集的操作层列表
) { // 数据类主体开始
    /**
     * 本操作集所有层（公共层 + 操作层），用于 UI 显示
     */
    val allLayers: List<OperationLayer> get() = listOf(commonLayer) + layers // 全部层列表（语法：自定义 getter + listOf + List 加法拼接）

    /**
     * 按名称查找本操作集内的层
     */
    fun findLayer(name: String): OperationLayer? = // 按名称查找层（语法：表达式体函数跨行）
        allLayers.firstOrNull { it.name == name } // 取第一个名称匹配的层（语法：firstOrNull lambda + it）
} // 结束 ActionSet 数据类

/**
 * 控制器完整配置
 *
 * 包含多个操作集（[ActionSet]）和全局设置，对应一个完整的配置文件。
 * 当前生效的操作集由 [activeActionSetName] 指定；切换操作集时，其下所有操作层整体切换。
 *
 * ## 访问语义（向后兼容）
 * [commonLayer] / [layers] / [allLayers] / [findLayer] 均作用于**当前生效的操作集**，
 * 原有"读当前配置"的调用无需改动；操作集管理（添加/删除/拷贝/切换）操作 [actionSets] 本身。
 *
 * @param actionSets 操作集列表（至少 1 个，默认包含「默认」操作集）
 * @param activeActionSetName 当前生效的操作集名称（不存在时回退到第一个操作集）
 * @param globalSettings 全局摇杆设置（所有操作集统一）
 */
data class ControllerProfile( // 控制器完整配置（语法：data class 数据类）
    val actionSets: List<ActionSet>, // 操作集列表
    val activeActionSetName: String = DEFAULT_ACTION_SET_NAME, // 当前生效操作集名称（语法：默认参数引用伴生常量）
    val globalSettings: GlobalSettings = GlobalSettings() // 全局摇杆设置（语法：默认参数构造对象）
) { // 数据类主体开始
    /**
     * 当前生效的操作集（名称匹配失败时回退到第一个）
     */
    val activeActionSet: ActionSet // 当前生效操作集（语法：属性无初始值，靠 getter 提供）
        get() = actionSets.firstOrNull { it.name == activeActionSetName } ?: actionSets.first() // 按名查找，失败回退第一个（语法：firstOrNull + ?: 空值合并）

    /**
     * 当前操作集的公共层（始终激活）
     */
    val commonLayer: OperationLayer get() = activeActionSet.commonLayer // 当前操作集的公共层（语法：getter 表达式体）

    /**
     * 当前操作集的操作层列表
     */
    val layers: List<OperationLayer> get() = activeActionSet.layers // 当前操作集的操作层列表

    /**
     * 当前操作集所有层（公共层 + 操作层），用于 UI 显示
     */
    val allLayers: List<OperationLayer> get() = activeActionSet.allLayers // 当前操作集全部层

    /**
     * 按名称查找当前操作集内的层
     */
    fun findLayer(name: String): OperationLayer? = activeActionSet.findLayer(name) // 在当前操作集内按名查找层

    /**
     * 按名称查找操作集
     */
    fun findActionSet(name: String): ActionSet? = actionSets.firstOrNull { it.name == name } // 按名称查找操作集

    companion object { // 伴生对象（语法：companion object 静态成员容器）
        /** 操作层最大数量 */
        const val MAX_LAYERS = 10 // 操作层数量上限（语法：const val 编译期常量）

        /** 默认操作集名称 */
        const val DEFAULT_ACTION_SET_NAME = "默认" // 默认操作集名称

        /**
         * 创建默认配置（单个「默认」操作集，内含 Common 层 + 10 个操作层）
         *
         * 层切换通过 Common 层的 KeyMapping(SwitchLayer) 实现：
         * - 按住触发键 → 激活对应操作层（按键映射优先用激活层，回退到 Common）
         * - 松开触发键 → 停用对应操作层，回到公共层
         *
         * 默认 Common 层层切换映射:
         * - D-Pad ↑ → Layer1
         * - D-Pad ↓ → Layer2
         * - D-Pad ← → Layer3
         * - D-Pad → → Layer4
         * - LB → Layer5
         * - RB → Layer6
         * - L3 → Layer7
         * - R3 → Layer8
         * - L2 → Layer9
         * - R2 → Layer10
         *
         * 操作层无默认按键映射；切换由 Common 层的 SwitchLayer 映射完成。
         */
        fun createDefault(): ControllerProfile { // 创建默认配置（语法：伴生对象内静态函数）
            // Common 层层切换按键映射
            // 注意：RIGHT_STICK_CLICK (R3) 保留为 LookAround 视角控制，不作为层切换键
            // Layer8 的触发键改为 TOUCHPAD_CLICK，避免覆盖 R3 的 LookAround 映射
            val switchKeys = listOf( // 层切换键映射表（语法：listOf 创建 Pair 列表）
                ControllerButton.DPAD_UP to "Layer1", // 上方向键→Layer1（语法：to 中缀函数构造 Pair）
                ControllerButton.DPAD_DOWN to "Layer2", // 下方向键→Layer2
                ControllerButton.DPAD_LEFT to "Layer3", // 左方向键→Layer3
                ControllerButton.DPAD_RIGHT to "Layer4", // 右方向键→Layer4
                ControllerButton.LEFT_SHOULDER to "Layer5", // 左肩键→Layer5
                ControllerButton.RIGHT_SHOULDER to "Layer6", // 右肩键→Layer6
                ControllerButton.LEFT_STICK_CLICK to "Layer7", // 左摇杆按下→Layer7
                ControllerButton.TOUCHPAD_CLICK to "Layer8", // 触控板点击→Layer8
                ControllerButton.LEFT_TRIGGER_CLICK to "Layer9", // 左扳机点击→Layer9
                ControllerButton.RIGHT_TRIGGER_CLICK to "Layer10" // 右扳机点击→Layer10
            ) // 结束 listOf

            val common = OperationLayer(name = "Common") // 创建公共层（语法：具名参数 name =）
            // 公共层默认按键映射（除层切换外的按键）
            common.buttonMappings[ControllerButton.A] = KeyMapping(MappedAction.KeyboardKey(62))  // KEYCODE_SPACE // A键→空格（跳跃）
            common.buttonMappings[ControllerButton.B] = KeyMapping(MappedAction.MouseClick(MouseButton.RIGHT)) // B键→鼠标右键
            common.buttonMappings[ControllerButton.X] = KeyMapping(MappedAction.MouseClick(MouseButton.LEFT)) // X键→鼠标左键
            common.buttonMappings[ControllerButton.Y] = KeyMapping(MappedAction.KeyboardKey(37))  // KEYCODE_I // Y键→I 键
            common.buttonMappings[ControllerButton.MENU] = KeyMapping(MappedAction.KeyboardKey(111))  // KEYCODE_ESCAPE // MENU→Esc 键
            common.buttonMappings[ControllerButton.OPTIONS] = KeyMapping(MappedAction.KeyboardKey(41))  // KEYCODE_M // OPTIONS→M 键
            common.buttonMappings[ControllerButton.RIGHT_STICK_CLICK] = KeyMapping(MappedAction.LookAround) // R3→视角控制
            // 层切换按键映射（按住激活对应层，松开回公共层）
            switchKeys.forEach { (button, layerName) -> // 遍历层切换键生成映射（语法：forEach lambda + 解构声明）
                common.buttonMappings[button] = KeyMapping(MappedAction.SwitchLayer(layerName)) // 绑定 SwitchLayer 动作到按钮
            } // 结束 forEach
            // 右摇杆默认视角控制
            // 摇杆映射不在 buttonMappings 中，而是通过 ControllerStick 单独处理

            // 操作层：无默认按键映射，层切换由 Common 层的 SwitchLayer 映射完成
            val layers = (1..MAX_LAYERS).map { i -> // 创建 1 到 10 的操作层（语法：(1..n) 区间 + map lambda）
                OperationLayer(name = "Layer$i") // 每层命名为 Layer1..Layer10（语法：字符串模板 $i）
            } // 结束 map

            return ControllerProfile( // 构造并返回默认配置（语法：具名参数构造）
                actionSets = listOf( // 操作集列表参数
                    ActionSet(name = DEFAULT_ACTION_SET_NAME, commonLayer = common, layers = layers) // 单个「默认」操作集
                ), // 结束操作集列表
                globalSettings = GlobalSettings() // 使用默认全局设置
            ) // 结束 ControllerProfile 构造
        } // 结束 createDefault 函数
    } // 结束 companion object
} // 结束 ControllerProfile 数据类
