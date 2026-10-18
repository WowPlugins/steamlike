package com.steamlike.controller.injection  // 声明包名：注入相关类的包

import android.view.KeyEvent  // 导入 Android 按键事件类（提供 KEYCODE_* 常量）

/**
 * 桥接输入注入器
 *
 * 实现 [InputInjector] 接口，将键盘/鼠标事件通过 [InputBridgeServer] 的TCP连接
 * 发送给运行在Winlator内的Windows配套程序。
 *
 * ## 工作原理
 * ```
 * Android KeyCode (如 KEYCODE_SPACE=62)
 *      ↓ androidKeyCodeToWindowsVK()
 * Windows VK Code (如 VK_SPACE=0x20)
 *      ↓ InputBridgeServer.sendKeyEvent()
 * TCP数据包 → Windows客户端
 *      ↓
 * Windows SendInput() → WoW游戏接收
 * ```
 *
 * ## 架构优势
 * | 特性 | 说明 |
 * |------|------|
 * | 注入位置 | Windows应用层(Winlator内) |
 * | 依赖 | 仅需悬浮窗权限 + Windows配套程序 |
 * | 延迟 | 低(直接到Windows) |
 * | 可靠性 | 高(SendInput稳定) |
 * | 手柄读取 | GamepadInputView焦点窗口捕获(无需Shizuku) |
 *
 * @param server TCP服务器实例
 */
class BridgeInputInjector(  // 语法：class 声明类；桥接输入注入器实现类
    private val server: InputBridgeServer  // 语法：val 只读属性；持有 TCP 服务器实例（构造参数注入依赖）
) : InputInjector {  // 语法：冒号+接口名表示实现该接口

    /** 当前按下的键集合（用于releaseAll） */
    private val pressedKeys = mutableSetOf<Int>()  // 语法：val 只读引用 + 泛型；当前按下的按键 VK 码集合

    /** 当前按下的鼠标按钮集合 */
    private val pressedButtons = mutableSetOf<MouseButton>()  // 语法：val 只读引用 + 泛型；当前按下的鼠标按钮集合

    /**
     * 鼠标相对移动的小数余量（X/Y）
     *
     * 协议层将鼠标位移编码为 int16，小数部分会被截断。
     * 低灵敏度/轻推摇杆时单帧位移常小于 1px，直接截断会导致
     * 微小的视角移动被丢弃（慢速转向一顿一顿、轻推几乎不动）。
     *
     * 这里把每帧的小数余量累积起来，累计超过 1px 时补发一次，
     * 从而在不改协议的前提下保留亚像素精度。
     */
    private var mouseRemainderX = 0f  // 语法：var 可变变量；X 轴位移小数余量累积
    private var mouseRemainderY = 0f  // 语法：var 可变变量；Y 轴位移小数余量累积

    override fun isAvailable(): Boolean {  // 语法：override 覆写接口方法 + fun 函数；判断注入器是否可用
        // 服务器已启动即可认为注入器可用（客户端是否连接由连接状态另行展示）
        return server.isRunning()  // 返回 TCP 服务器运行状态
    }  // 结束 isAvailable 函数

    /**
     * 按下键盘按键
     * @param keyCode Android KeyEvent.KEYCODE_* 常量
     */
    override fun sendKeyDown(keyCode: Int) {  // 语法：override 覆写；发送键盘按键按下事件
        val vkCode = androidKeyCodeToWindowsVK(keyCode)  // 语法：val 只读变量；把 Android 按键码转换为 Windows VK 码
        if (vkCode != 0) {  // 语法：if 条件判断；仅当映射有效（非0）时才发送
            pressedKeys.add(vkCode)  // 记录该按键处于按下状态
            server.sendKeyEvent(vkCode, isDown = true)  // 语法：命名参数 isDown=true；经 TCP 发送按键按下事件
        }  // 结束 if 块
    }  // 结束 sendKeyDown 函数

    /**
     * 释放键盘按键
     * @param keyCode Android KeyEvent.KEYCODE_* 常量
     */
    override fun sendKeyUp(keyCode: Int) {  // 语法：override 覆写；发送键盘按键释放事件
        val vkCode = androidKeyCodeToWindowsVK(keyCode)  // 语法：val 只读变量；把 Android 按键码转换为 Windows VK 码
        if (vkCode != 0) {  // 语法：if 条件判断；仅当映射有效（非0）时才发送
            pressedKeys.remove(vkCode)  // 从按下集合中移除该按键
            server.sendKeyEvent(vkCode, isDown = false)  // 语法：命名参数 isDown=false；经 TCP 发送按键释放事件
        }  // 结束 if 块
    }  // 结束 sendKeyUp 函数

    /**
     * 用原始 Windows VK 码发送一次按键点击（按下 + 立即释放）
     *
     * 绕过 Android KeyCode → VK 映射，直接发送 VK 码，用于 Android 未定义的按键
     * （如 VK_F11=0x7A、VK_F12=0x7B）。
     *
     * @param vkCode Windows 虚拟键码
     */
    override fun sendVkKeyPress(vkCode: Int) {  // 语法：override 覆写；用原始 VK 码发送按键点击
        if (vkCode == 0) return  // VK 码无效时不发送（语法：if 单行返回）
        pressedKeys.add(vkCode)  // 记录该 VK 处于按下状态
        server.sendKeyEvent(vkCode, isDown = true)  // 发送按下事件
        pressedKeys.remove(vkCode)  // 立即移除按下状态
        server.sendKeyEvent(vkCode, isDown = false)  // 发送释放事件
    }  // 结束 sendVkKeyPress 函数

    override fun sendMouseDown(button: MouseButton) {  // 语法：override 覆写；发送鼠标按钮按下事件
        val btnId = when (button) {  // 语法：val + when 分支表达式；把鼠标按钮枚举映射为协议按钮 ID
            MouseButton.LEFT -> 0  // 左键映射为 ID 0
            MouseButton.RIGHT -> 1  // 右键映射为 ID 1
            MouseButton.MIDDLE -> 2  // 中键映射为 ID 2
            MouseButton.FORWARD -> 3  // 前进键映射为 ID 3
            MouseButton.BACK -> 4  // 后退键映射为 ID 4
        }  // 结束 when 分支
        pressedButtons.add(button)  // 记录该按钮处于按下状态
        server.sendMouseButton(btnId, isDown = true)  // 语法：命名参数 isDown=true；经 TCP 发送鼠标按下事件
    }  // 结束 sendMouseDown 函数

    override fun sendMouseUp(button: MouseButton) {  // 语法：override 覆写；发送鼠标按钮释放事件
        val btnId = when (button) {  // 语法：val + when 分支；把鼠标按钮枚举映射为协议按钮 ID
            MouseButton.LEFT -> 0  // 左键映射为 ID 0
            MouseButton.RIGHT -> 1  // 右键映射为 ID 1
            MouseButton.MIDDLE -> 2  // 中键映射为 ID 2
            MouseButton.FORWARD -> 3  // 前进键映射为 ID 3
            MouseButton.BACK -> 4  // 后退键映射为 ID 4
        }  // 结束 when 分支
        pressedButtons.remove(button)  // 从按下集合中移除该按钮
        server.sendMouseButton(btnId, isDown = false)  // 语法：命名参数 isDown=false；经 TCP 发送鼠标释放事件
    }  // 结束 sendMouseUp 函数

    /**
     * 发送鼠标相对移动（带小数余量累积）
     *
     * 将 dx/dy 与未发送的余量合并后取整发送，余量保留到下一次调用。
     * 例如连续收到 0.4/0.4/0.4：实际发送 0/0/1，累计位移 1px 而不是全部丢弃。
     *
     * @param dx X轴相对位移（像素，右为正）
     * @param dy Y轴相对位移（像素，下为正）
     */
    override fun sendMouseMove(dx: Float, dy: Float) {  // 语法：override 覆写；发送鼠标相对位移
        mouseRemainderX += dx  // 累加本次 X 轴位移到余量
        mouseRemainderY += dy  // 累加本次 Y 轴位移到余量
        val ix = mouseRemainderX.toInt()  // 语法：val 只读变量；取 X 余量的整数部分
        val iy = mouseRemainderY.toInt()  // 语法：val 只读变量；取 Y 余量的整数部分
        if (ix == 0 && iy == 0) return  // 提前返回，不发网络包 // 累计不足 1px，本帧不发送
        mouseRemainderX -= ix  // 扣除已发送的 X 整数部分，保留新余量
        mouseRemainderY -= iy  // 扣除已发送的 Y 整数部分，保留新余量
        server.sendMouseMove(ix.toFloat(), iy.toFloat())  // 经 TCP 发送整数位移（转回 Float 类型）
    }  // 结束 sendMouseMove 函数

    /**
     * 将鼠标光标归位到开关决定的落点（屏幕外下方 / 屏幕正中央）
     *
     * 归位前先清空位移小数余量，避免残留的亚像素位移在归位后被补发。
     *
     * @param parkOffscreen true=停放到屏幕外下方，false=屏幕正中央
     */
    override fun sendMouseCenter(parkOffscreen: Boolean) {  // 语法：override 覆写；发送鼠标归位命令
        mouseRemainderX = 0f  // 清零 X 轴位移余量
        mouseRemainderY = 0f  // 清零 Y 轴位移余量
        server.sendMouseCenter(parkOffscreen)  // 转发给 TCP 服务器，携带落点开关值
    }  // 结束 sendMouseCenter 函数

    /**
     * 将鼠标光标强制移动到屏幕正中央（交互键内部序列专用）
     */
    override fun sendMouseCenterExact() {  // 语法：override 覆写；发送强制居中央命令
        mouseRemainderX = 0f  // 清零 X 轴位移余量
        mouseRemainderY = 0f  // 清零 Y 轴位移余量
        server.sendMouseCenterExact()  // 转发给 TCP 服务器发送强制居中央事件
    }  // 结束 sendMouseCenterExact 函数

    /**
     * 记录当前鼠标光标位置（交互键序列开头使用）
     *
     * 同样先清空位移小数余量，避免残留的亚像素位移在归位/还原之后被补发。
     */
    override fun sendCursorSave() {  // 语法：override 覆写；发送记录光标位置命令
        mouseRemainderX = 0f  // 清零 X 轴位移余量
        mouseRemainderY = 0f  // 清零 Y 轴位移余量
        server.sendCursorSave()  // 转发给 TCP 服务器发送记录光标位置事件
    }  // 结束 sendCursorSave 函数

    /**
     * 把鼠标光标还原到记录的位置（交互键序列末尾使用）
     */
    override fun sendCursorRestore() {  // 语法：override 覆写；发送还原光标位置命令
        mouseRemainderX = 0f  // 清零 X 轴位移余量
        mouseRemainderY = 0f  // 清零 Y 轴位移余量
        server.sendCursorRestore()  // 转发给 TCP 服务器发送还原光标位置事件
    }  // 结束 sendCursorRestore 函数

    /**
     * 发送鼠标滚轮事件
     * @param delta 滚轮增量（正数=上滚，负数=下滚）
     */
    override fun sendMouseScroll(delta: Float) {  // 语法：override 覆写；发送鼠标滚轮事件
        server.sendMouseWheel(delta)  // 转发给 TCP 服务器发送滚轮事件
    }  // 结束 sendMouseScroll 函数

    /**
     * 释放所有按下的键和按钮
     * 通知Windows客户端执行释放操作
     */
    override fun releaseAll() {  // 语法：override 覆写；释放所有按键和按钮
        // 本地状态清理
        pressedKeys.clear()  // 清空本地按下的按键集合
        pressedButtons.clear()  // 清空本地按下的按钮集合
        mouseRemainderX = 0f  // 清零 X 轴位移余量
        mouseRemainderY = 0f  // 清零 Y 轴位移余量
        // 通知Windows客户端释放所有
        server.sendReleaseAll()  // 经 TCP 通知 Windows 客户端释放所有按键
    }  // 结束 releaseAll 函数

    override fun destroy() {  // 语法：override 覆写；销毁注入器
        releaseAll()  // 销毁前先释放所有按键，防止按键卡住
    }  // 结束 destroy 函数

    // ====================================================================
    // Android KeyCode → Windows VK Code 映射表
    // ====================================================================

    companion object {  // 语法：companion object 伴生对象（类级静态成员容器）
        /**
         * 将Android KeyCode转换为Windows虚拟键码
         *
         * Windows VK Code参考: https://docs.microsoft.com/en-us/windows/win32/inputdev/virtual-key-codes
         *
         * @param androidKeyCode Android KeyEvent.KEYCODE_* 常量
         * @return Windows VK Code，0=无对应映射
         */
        fun androidKeyCodeToWindowsVK(androidKeyCode: Int): Int {  // 语法：fun 函数；Android 按键码转 Windows VK 码
        return when (androidKeyCode) {  // 语法：when 分支表达式；按 Android 按键码分发返回对应的 VK 码
            // ===== 字母 A-Z =====
            KeyEvent.KEYCODE_A -> 0x41  // VK_A // 映射字母A键
            KeyEvent.KEYCODE_B -> 0x42  // 映射字母B键
            KeyEvent.KEYCODE_C -> 0x43  // 映射字母C键
            KeyEvent.KEYCODE_D -> 0x44  // 映射字母D键
            KeyEvent.KEYCODE_E -> 0x45  // 映射字母E键
            KeyEvent.KEYCODE_F -> 0x46  // 映射字母F键
            KeyEvent.KEYCODE_G -> 0x47  // 映射字母G键
            KeyEvent.KEYCODE_H -> 0x48  // 映射字母H键
            KeyEvent.KEYCODE_I -> 0x49  // 映射字母I键
            KeyEvent.KEYCODE_J -> 0x4A  // 映射字母J键
            KeyEvent.KEYCODE_K -> 0x4B  // 映射字母K键
            KeyEvent.KEYCODE_L -> 0x4C  // 映射字母L键
            KeyEvent.KEYCODE_M -> 0x4D  // 映射字母M键
            KeyEvent.KEYCODE_N -> 0x4E  // 映射字母N键
            KeyEvent.KEYCODE_O -> 0x4F  // 映射字母O键
            KeyEvent.KEYCODE_P -> 0x50  // 映射字母P键
            KeyEvent.KEYCODE_Q -> 0x51  // 映射字母Q键
            KeyEvent.KEYCODE_R -> 0x52  // 映射字母R键
            KeyEvent.KEYCODE_S -> 0x53  // 映射字母S键
            KeyEvent.KEYCODE_T -> 0x54  // 映射字母T键
            KeyEvent.KEYCODE_U -> 0x55  // 映射字母U键
            KeyEvent.KEYCODE_V -> 0x56  // 映射字母V键
            KeyEvent.KEYCODE_W -> 0x57  // 映射字母W键
            KeyEvent.KEYCODE_X -> 0x58  // 映射字母X键
            KeyEvent.KEYCODE_Y -> 0x59  // 映射字母Y键
            KeyEvent.KEYCODE_Z -> 0x5A  // 映射字母Z键

            // ===== 数字 0-9 =====
            KeyEvent.KEYCODE_0 -> 0x30  // VK_0 // 映射数字0键
            KeyEvent.KEYCODE_1 -> 0x31  // 映射数字1键
            KeyEvent.KEYCODE_2 -> 0x32  // 映射数字2键
            KeyEvent.KEYCODE_3 -> 0x33  // 映射数字3键
            KeyEvent.KEYCODE_4 -> 0x34  // 映射数字4键
            KeyEvent.KEYCODE_5 -> 0x35  // 映射数字5键
            KeyEvent.KEYCODE_6 -> 0x36  // 映射数字6键
            KeyEvent.KEYCODE_7 -> 0x37  // 映射数字7键
            KeyEvent.KEYCODE_8 -> 0x38  // 映射数字8键
            KeyEvent.KEYCODE_9 -> 0x39  // 映射数字9键

            // ===== 功能键 =====
            KeyEvent.KEYCODE_F1 -> 0x70   // VK_F1 // 映射功能键F1
            KeyEvent.KEYCODE_F2 -> 0x71  // 映射功能键F2
            KeyEvent.KEYCODE_F3 -> 0x72  // 映射功能键F3
            KeyEvent.KEYCODE_F4 -> 0x73  // 映射功能键F4
            KeyEvent.KEYCODE_F5 -> 0x74  // 映射功能键F5
            KeyEvent.KEYCODE_F6 -> 0x75  // 映射功能键F6
            KeyEvent.KEYCODE_F7 -> 0x76  // 映射功能键F7
            KeyEvent.KEYCODE_F8 -> 0x77  // 映射功能键F8
            KeyEvent.KEYCODE_F9 -> 0x78  // 映射功能键F9
            KeyEvent.KEYCODE_F10 -> 0x79  // 映射功能键F10
            KeyEvent.KEYCODE_F11 -> 0x7A  // 映射功能键F11
            KeyEvent.KEYCODE_F12 -> 0x7B  // 映射功能键F12

            // ===== 修饰键 =====
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xA0   // VK_LSHIFT // 映射左Shift键
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1  // VK_RSHIFT // 映射右Shift键
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xA2    // VK_LCONTROL // 映射左Ctrl键
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3   // VK_RCONTROL // 映射右Ctrl键
            KeyEvent.KEYCODE_ALT_LEFT -> 0xA4     // VK_LMENU // 映射左Alt键
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5    // VK_RMENU // 映射右Alt键

            // ===== 特殊键 =====
            KeyEvent.KEYCODE_SPACE -> 0x20        // VK_SPACE // 映射空格键
            KeyEvent.KEYCODE_ENTER -> 0x0D        // VK_RETURN // 映射回车键
            KeyEvent.KEYCODE_ESCAPE -> 0x1B       // VK_ESCAPE // 映射ESC键
            KeyEvent.KEYCODE_TAB -> 0x09          // VK_TAB // 映射Tab键
            KeyEvent.KEYCODE_BACK -> 0x08         // VK_BACK (Backspace) // 映射退格键
            KeyEvent.KEYCODE_DEL -> 0x2E          // VK_DELETE // 映射删除键
            KeyEvent.KEYCODE_INSERT -> 0x2D       // VK_INSERT // 映射插入键
            KeyEvent.KEYCODE_HOME -> 0x24         // VK_HOME // 映射Home键
            KeyEvent.KEYCODE_PAGE_UP -> 0x21      // VK_PRIOR // 映射PageUp键
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x22    // VK_NEXT // 映射PageDown键
            KeyEvent.KEYCODE_MOVE_END -> 0x23     // VK_END // 映射End键

            // ===== 方向键 =====
            KeyEvent.KEYCODE_DPAD_UP -> 0x26      // VK_UP // 映射方向上键
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x28    // VK_DOWN // 映射方向下键
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x25    // VK_LEFT // 映射方向左键
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27   // VK_RIGHT // 映射方向右键

            // ===== 符号键 =====
            KeyEvent.KEYCODE_MINUS -> 0xBD        // VK_OEM_MINUS "-" // 映射减号键
            KeyEvent.KEYCODE_EQUALS -> 0xBB       // VK_OEM_PLUS "=" // 映射等号键
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB // VK_OEM_4 "[" // 映射左方括号键
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD // VK_OEM_6 "]" // 映射右方括号键
            KeyEvent.KEYCODE_BACKSLASH -> 0xDC     // VK_OEM_5 "\" // 映射反斜杠键
            KeyEvent.KEYCODE_SEMICOLON -> 0xBA     // VK_OEM_1 ";" // 映射分号键
            KeyEvent.KEYCODE_APOSTROPHE -> 0xDE    // VK_OEM_7 "'" // 映射单引号键
            KeyEvent.KEYCODE_COMMA -> 0xBC         // VK_OEM_COMMA "," // 映射逗号键
            KeyEvent.KEYCODE_PERIOD -> 0xBE        // VK_OEM_PERIOD "." // 映射句号键
            KeyEvent.KEYCODE_SLASH -> 0xBF         // VK_OEM_2 "/" // 映射斜杠键
            KeyEvent.KEYCODE_GRAVE -> 0xC0         // VK_OEM_3 "`" // 映射反引号键

            // ===== 小键盘 =====
            KeyEvent.KEYCODE_NUM_LOCK -> 0x90     // VK_NUMLOCK // 映射NumLock键
            KeyEvent.KEYCODE_NUMPAD_0 -> 0x60     // VK_NUMPAD0 // 映射小键盘0键
            KeyEvent.KEYCODE_NUMPAD_1 -> 0x61  // 映射小键盘1键
            KeyEvent.KEYCODE_NUMPAD_2 -> 0x62  // 映射小键盘2键
            KeyEvent.KEYCODE_NUMPAD_3 -> 0x63  // 映射小键盘3键
            KeyEvent.KEYCODE_NUMPAD_4 -> 0x64  // 映射小键盘4键
            KeyEvent.KEYCODE_NUMPAD_5 -> 0x65  // 映射小键盘5键
            KeyEvent.KEYCODE_NUMPAD_6 -> 0x66  // 映射小键盘6键
            KeyEvent.KEYCODE_NUMPAD_7 -> 0x67  // 映射小键盘7键
            KeyEvent.KEYCODE_NUMPAD_8 -> 0x68  // 映射小键盘8键
            KeyEvent.KEYCODE_NUMPAD_9 -> 0x69  // 映射小键盘9键
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6F     // VK_DIVIDE // 映射小键盘除号键
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6A   // VK_MULTIPLY // 映射小键盘乘号键
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6D   // VK_SUBTRACT // 映射小键盘减号键
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6B        // VK_ADD // 映射小键盘加号键
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0x6E        // VK_DECIMAL // 映射小键盘小数点键
            KeyEvent.KEYCODE_NUMPAD_COMMA -> 0x6C      // VK_SEPARATOR // 映射小键盘逗号键（键盘分隔符）
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x0D      // VK_RETURN // 映射小键盘回车键（与主回车同为 VK_RETURN）

            // ===== 其他 =====
            KeyEvent.KEYCODE_CAPS_LOCK -> 0x14     // VK_CAPITAL // 映射大写锁定键
            KeyEvent.KEYCODE_SCROLL_LOCK -> 0x91   // VK_SCROLL // 映射滚动锁定键

            else -> 0  // 无对应映射 // 无法映射的按键返回 0
        }
        }

        /**
         * 将单个字符映射为 Windows 虚拟键码及是否需要 Shift
         *
         * 用于把 IME 软键盘输入的文本转为 Windows VK 键码注入（配合 Shift 上档）。
         * 仅覆盖 ASCII 可打印字符；无法映射的字符返回 null（忽略）。
         *
         * @param c 输入字符
         * @return (VK Code, 是否需要Shift) 或 null=无对应映射
         */
        fun charToWindowsVK(c: Char): Pair<Int, Boolean>? {  // 语法：fun 函数 + Pair 泛型 + ?可空返回值；单字符转 VK 码及是否需 Shift
            return when {  // 语法：when 无参数分支表达式；按字符条件匹配返回 VK 码与 Shift 标记的 Pair
                c in 'a'..'z' -> (0x41 + (c - 'a')) to false  // VK_A..VK_Z // 小写字母：VK码=0x41+字母偏移，不需Shift
                c in 'A'..'Z' -> (0x41 + (c - 'A')) to true  // 大写字母：VK码同理，但需配合Shift注入
                c in '0'..'9' -> (0x30 + (c - '0')) to false  // VK_0..VK_9 // 数字：VK码=0x30+数字偏移，不需Shift
                c == ' ' -> 0x20 to false                    // VK_SPACE // 空格键
                c == '\t' -> 0x09 to false                   // VK_TAB // Tab键
                c == '\n' || c == '\r' -> 0x0D to false      // VK_RETURN // 换行/回车均映射为回车键
                c == '-' -> 0xBD to false                    // VK_OEM_MINUS // 减号键
                c == '_' -> 0xBD to true  // 下划线 = Shift+减号键
                c == '=' -> 0xBB to false                    // VK_OEM_PLUS // 等号键
                c == '+' -> 0xBB to true  // 加号 = Shift+等号键
                c == '[' -> 0xDB to false                    // VK_OEM_4 // 左方括号键
                c == '{' -> 0xDB to true  // 左花括号 = Shift+左方括号键
                c == ']' -> 0xDD to false                    // VK_OEM_6 // 右方括号键
                c == '}' -> 0xDD to true  // 右花括号 = Shift+右方括号键
                c == '\\' -> 0xDC to false                   // VK_OEM_5 // 反斜杠键
                c == '|' -> 0xDC to true  // 竖线 = Shift+反斜杠键
                c == ';' -> 0xBA to false                    // VK_OEM_1 // 分号键
                c == ':' -> 0xBA to true  // 冒号 = Shift+分号键
                c == '\'' -> 0xDE to false                   // VK_OEM_7 // 单引号键
                c == '"' -> 0xDE to true  // 双引号 = Shift+单引号键
                c == ',' -> 0xBC to false                    // VK_OEM_COMMA // 逗号键
                c == '<' -> 0xBC to true  // 小于号 = Shift+逗号键
                c == '.' -> 0xBE to false                    // VK_OEM_PERIOD // 句号键
                c == '>' -> 0xBE to true  // 大于号 = Shift+句号键
                c == '/' -> 0xBF to false                    // VK_OEM_2 // 斜杠键
                c == '?' -> 0xBF to true  // 问号 = Shift+斜杠键
                c == '`' -> 0xC0 to false                    // VK_OEM_3 // 反引号键
                c == '~' -> 0xC0 to true  // 波浪号 = Shift+反引号键
                c == '!' -> 0x31 to true                     // Shift + VK_1 // 感叹号 = Shift+数字1
                c == '@' -> 0x32 to true  // @ = Shift+数字2
                c == '#' -> 0x33 to true  // # = Shift+数字3
                c == '$' -> 0x34 to true  // $ = Shift+数字4
                c == '%' -> 0x35 to true  // % = Shift+数字5
                c == '^' -> 0x36 to true  // ^ = Shift+数字6
                c == '&' -> 0x37 to true  // & = Shift+数字7
                c == '*' -> 0x38 to true  // * = Shift+数字8
                c == '(' -> 0x39 to true  // ( = Shift+数字9
                c == ')' -> 0x30 to true  // ) = Shift+数字0
                else -> null  // 无法映射的字符返回 null（忽略）
            }  // 结束 when 分支
        }  // 结束 charToWindowsVK 函数
    }  // 结束 companion object
}  // 结束 BridgeInputInjector 类
