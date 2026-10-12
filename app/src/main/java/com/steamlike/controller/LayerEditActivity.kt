package com.steamlike.controller // 包声明：指明当前文件所属的包（应用包名）

import android.content.Intent // 导入 Intent 类，用于启动组件/传递数据
import android.content.Context // 导入 Context 类（应用/组件运行环境上下文）
import android.os.Build // 导入 Build 类，用于判断系统版本号
import android.os.Bundle // 导入 Bundle 类（Activity 状态传递的键值容器）
import android.util.AttributeSet // 导入 AttributeSet（XML 布局属性集合）
import android.util.Log // 导入 Log 类，用于打印调试日志
import android.view.Gravity // 导入 Gravity（布局重力对齐常量）
import android.view.InputDevice // 导入 InputDevice（输入设备信息类）
import android.view.KeyEvent // 导入 KeyEvent（按键事件类）
import android.view.LayoutInflater // 导入 LayoutInflater（将 XML 布局实例化为 View 对象）
import android.view.MenuItem // 导入 MenuItem（菜单项接口）
import android.view.MotionEvent // 导入 MotionEvent（触摸/模拟量事件类）
import android.view.View // 导入 View（视图基类）
import android.view.WindowManager // 导入 WindowManager（窗口管理，含布局参数常量）
import android.view.inputmethod.InputMethodManager // 导入 InputMethodManager（输入法管理器，控制软键盘）
import android.widget.AdapterView // 导入 AdapterView（适配器视图基类，如 ListView/Spinner）
import android.widget.ArrayAdapter // 导入 ArrayAdapter（数组数据适配器）
import android.widget.Button // 导入 Button（按钮控件）
import android.widget.EditText // 导入 EditText（文本输入框控件）
import android.widget.LinearLayout // 导入 LinearLayout（线性布局容器）
import android.widget.ListView // 导入 ListView（列表视图控件）
import android.widget.Spinner // 导入 Spinner（下拉选择控件）
import android.widget.TextView // 导入 TextView（文本显示控件）
import android.widget.Toast // 导入 Toast（轻量提示弹窗）
import androidx.core.content.ContextCompat // 导入 ContextCompat（兼容性工具，如启动前台服务）
import androidx.appcompat.app.AlertDialog // 导入 AlertDialog（对话框类）
import androidx.appcompat.app.AppCompatActivity // 导入 AppCompatActivity（兼容性 Activity 基类）
import com.steamlike.controller.config.AppConfigStore // 导入本应用配置存储类（读写运行时配置）
import com.steamlike.controller.config.ConfigManager // 导入配置管理器（含配置文件名常量）
import com.steamlike.controller.config.ControllerConfig // 导入控制器配置序列化类（JSON 读写）
import com.steamlike.controller.core.ActionSet // 导入操作集数据类
import com.steamlike.controller.core.ControllerButton // 导入手柄按键枚举
import com.steamlike.controller.core.ControllerInputMapper // 导入按键码映射器（KeyCode→手柄按键）
import com.steamlike.controller.core.ControllerType // 导入手柄类型枚举
import com.steamlike.controller.service.ControllerOverlayService // 导入悬浮窗服务类（主编排器）
import com.steamlike.controller.core.ControllerProfile // 导入控制器配置档案数据类
import com.steamlike.controller.core.KeyMapping // 导入按键映射数据类
import com.steamlike.controller.core.MappedAction // 导入映射动作密封类
import com.steamlike.controller.core.MouseButton // 导入鼠标按键枚举
import com.steamlike.controller.core.OperationLayer // 导入操作层数据类
import com.steamlike.controller.core.SteamInput // 导入 SteamInput（主控制器）
import java.io.File // 导入 java.io.File（文件操作类）

/**
 * 操作层设置 Activity
 *
 * 用于编辑手柄控制器的操作层和按键映射配置。
 *
 * ## 功能
 * 1. 选择操作层（Common + Layer1-Layer10），通过顶部下拉框切换
 * 2. 显示当前操作层所有手柄按键的映射情况（列表形式）
 * 3. 点击某个按键进入编辑对话框，可设置映射功能:
 *    - 键盘按键（字母A-Z、数字0-9、功能键F1-F12、修饰键、符号键等）
 *    - 鼠标点击（左键/中键/右键）
 *    - 切换操作层（选择目标层）
 * 4. 每个映射可添加最多3个子命令（子命令也是键盘按键，用于组合键）
 * 5. 可设置操作层名称和触发按键
 *
 * ## 数据流
 * ```
 * ControllerOverlayService 创建 SteamInput 实例
 *      ↓ 设置 LayerEditActivity.steamInputRef
 * LayerEditActivity 通过 steamInputRef?.profile 获取配置
 *      ↓ 用户编辑映射
 * 修改 OperationLayer.buttonMappings（MutableMap，可直接修改）
 *      ↓ 保存
 * steamInputRef?.loadProfile(newProfile) → 更新运行时
 * ConfigManager.saveToInternal(profile) → 持久化到内部存储
 * ```
 *
 * ## Android 知识点
 * - **Spinner**: 下拉选择器，类似 HTML 的 `<select>`。通过 `ArrayAdapter` 提供选项数据。
 * - **ListView**: 列表视图，通过 `ArrayAdapter` 提供数据，`setOnItemClickListener` 处理点击。
 * - **AlertDialog**: 模态对话框，通过 `AlertDialog.Builder` 构建，支持自定义视图。
 * - **LayoutInflater**: 将 XML 布局文件实例化为 View 对象。
 * - **ArrayAdapter**: 通用适配器，将数组/列表数据绑定到 AdapterView（如 ListView/Spinner）。
 */
class LayerEditActivity : AppCompatActivity() { // 定义操作层编辑 Activity 类，继承自 AppCompatActivity（语法：class X:Y()=声明类并继承基类）

    companion object { // 伴生对象块开始：存放静态成员/类级常量与方法（语法：companion object=伴生对象，相当于 Java 静态成员）
        private const val TAG = "LayerEditActivity" // 私有常量：日志标签（语法：const val=编译期常量）

        /**
         * SteamInput 实例引用
         *
         * 由 [ControllerOverlayService] 在创建 SteamInput 后赋值，
         * LayerEditActivity 通过此引用获取和修改配置。
         *
         * ## 使用方式
         * ```kotlin
         * val profile = steamInputRef?.profile  // 获取当前配置
         * steamInputRef?.loadProfile(newProfile)  // 更新配置
         * ```
         *
         * ## 线程安全
         * 此变量为 `var`，在服务主线程写入，Activity 主线程读取。
         * 引用赋值是原子的，但建议在主线程操作。
         */
        var steamInputRef: SteamInput? = null // 静态引用：SteamInput 实例，由服务赋值后供本页读写配置（语法：var=可变变量，SteamInput?=可空类型）

        /** Intent extra 键: 初始选中的操作层名称 */
        const val EXTRA_LAYER_NAME = "layer_name" // 常量：Intent 传参键名，用于指定初始选中的层名（语法：const val=编译期常量）

        /**
         * 键盘按键选项列表（显示名称 → Android KeyCode）
         *
         * 用于按键映射编辑对话框中的按键选择 Spinner。
         * 包含字母、数字、功能键、修饰键、符号键、导航键等常用按键。
         *
         * ## Android KeyCode
         * Android 使用 [KeyEvent.KEYCODE_*] 常量表示按键，例如:
         * - KEYCODE_A = 29, KEYCODE_Z = 54
         * - KEYCODE_0 = 7, KEYCODE_9 = 16
         * - KEYCODE_F1 = 131, KEYCODE_F12 = 142
         */
        val keyboardKeyOptions: List<Pair<String, Int>> = buildKeyboardKeyOptions() // 只读属性：键盘按键选项列表（显示名→KeyCode），由构建函数初始化（语法：val=只读变量，List<Pair<...>>=泛型列表）

        val numpadKeyOptions: List<Pair<String, Int>> = listOf( // 只读属性：数字小键盘按键选项列表（显示名→KeyCode），供「数字小键盘」动作类型使用
            "Num1" to KeyEvent.KEYCODE_NUMPAD_1, // 小键盘数字 1（语法：to=创建 Pair 键值对）
            "Num2" to KeyEvent.KEYCODE_NUMPAD_2, // 小键盘数字 2
            "Num3" to KeyEvent.KEYCODE_NUMPAD_3, // 小键盘数字 3
            "Num4" to KeyEvent.KEYCODE_NUMPAD_4, // 小键盘数字 4
            "Num5" to KeyEvent.KEYCODE_NUMPAD_5, // 小键盘数字 5
            "Num6" to KeyEvent.KEYCODE_NUMPAD_6, // 小键盘数字 6
            "Num7" to KeyEvent.KEYCODE_NUMPAD_7, // 小键盘数字 7
            "Num8" to KeyEvent.KEYCODE_NUMPAD_8, // 小键盘数字 8
            "Num9" to KeyEvent.KEYCODE_NUMPAD_9, // 小键盘数字 9
            "Num0" to KeyEvent.KEYCODE_NUMPAD_0, // 小键盘数字 0
            "NumLock" to KeyEvent.KEYCODE_NUM_LOCK, // 数字锁定键
            "Num/" to KeyEvent.KEYCODE_NUMPAD_DIVIDE, // 小键盘除号
            "Num*" to KeyEvent.KEYCODE_NUMPAD_MULTIPLY, // 小键盘乘号
            "Num-" to KeyEvent.KEYCODE_NUMPAD_SUBTRACT, // 小键盘减号
            "Num+" to KeyEvent.KEYCODE_NUMPAD_ADD, // 小键盘加号
            "Num." to KeyEvent.KEYCODE_NUMPAD_DOT, // 小键盘小数点
            "NumEnter" to KeyEvent.KEYCODE_NUMPAD_ENTER, // 小键盘回车
        ) // 结束数字小键盘选项列表

        /**
         * 构建键盘按键选项列表
         *
         * 按类别组织: 字母 → 数字 → 功能键 → 修饰键 → 特殊键 → 方向键 → 符号键 → 锁定键 → 导航键
         */
        private fun buildKeyboardKeyOptions(): List<Pair<String, Int>> { // 私有函数：构建键盘按键选项列表并返回（语法：fun=函数声明，返回类型 List<Pair<String,Int>>）
            val list = mutableListOf<Pair<String, Int>>() // 创建可变列表存放按键选项（语法：val=只读变量，mutableListOf=可变列表工厂函数，<T>=泛型）

            // ===== 字母 A-Z =====
            // KEYCODE_A 到 KEYCODE_Z 是连续的 (29~54)
            for (c in 'A'..'Z') { // 循环遍历字符 A 到 Z（语法：for...in=迭代循环，'A'..'Z'=字符区间）
                val keyCode = KeyEvent.KEYCODE_A + (c - 'A') // 计算当前字母对应的 KeyCode（A 的 KeyCode 加字母偏移量）
                list.add(c.toString() to keyCode) // 将「字母显示名 to KeyCode」对添加到列表（语法：to=创建 Pair 键值对）
            } // 结束字母遍历 for 循环

            // ===== 数字 0-9 =====
            // KEYCODE_0 到 KEYCODE_9 是连续的 (7~16)
            for (d in '0'..'9') { // 循环遍历数字字符 0 到 9
                val keyCode = KeyEvent.KEYCODE_0 + (d - '0') // 计算数字对应的 KeyCode（0 的 KeyCode 加数字偏移量）
                list.add(d.toString() to keyCode) // 将「数字显示名 to KeyCode」对添加到列表
            } // 结束数字遍历 for 循环

            // ===== 数字键的 Shift 符号 =====
            // 按住 Shift 时数字键输出的符号（键码 = SHIFT_SYMBOL_OFFSET + 对应数字键码，注入时自动按住 Shift）
            list.add("!（Shift+1）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_1) // Shift+1 → 叹号
            list.add("@（Shift+2）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_2) // Shift+2 → at 符号
            list.add("#（Shift+3）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_3) // Shift+3 → 井号
            list.add("$（Shift+4）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_4) // Shift+4 → 美元符号
            list.add("%（Shift+5）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_5) // Shift+5 → 百分号
            list.add("^（Shift+6）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_6) // Shift+6 → 脱字符
            list.add("&（Shift+7）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_7) // Shift+7 → 与号
            list.add("*（Shift+8）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_8) // Shift+8 → 星号
            list.add("(（Shift+9）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_9) // Shift+9 → 左括号
            list.add(")（Shift+0）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_0) // Shift+0 → 右括号
            list.add("~（Shift+`）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_GRAVE) // Shift+` → 波浪号
            list.add("_（Shift+-）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_MINUS) // Shift+- → 下划线
            list.add("+（Shift+=）" to KeyMapping.SHIFT_SYMBOL_OFFSET + KeyEvent.KEYCODE_EQUALS) // Shift+= → 加号

            // ===== 功能键 F1-F12 =====
            // KEYCODE_F1 到 KEYCODE_F12 是连续的 (131~142)
            for (i in 1..12) { // 循环遍历 1 到 12（对应 F1-F12）
                val keyCode = KeyEvent.KEYCODE_F1 + (i - 1) // 计算功能键 KeyCode（F1 的 KeyCode 加序号偏移量）
                list.add("F$i" to keyCode) // 将「F+数字」显示名与 KeyCode 配对加入列表（语法：字符串模板 "$var"）
            } // 结束功能键遍历 for 循环

            // ===== 修饰键 =====
            list.add("Shift" to KeyEvent.KEYCODE_SHIFT_LEFT) // 添加左 Shift 修饰键选项
            list.add("Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT) // 添加左 Ctrl 修饰键选项
            list.add("Alt" to KeyEvent.KEYCODE_ALT_LEFT) // 添加左 Alt 修饰键选项

            // ===== 特殊键 =====
            list.add("Space" to KeyEvent.KEYCODE_SPACE) // 添加空格键选项
            list.add("Enter" to KeyEvent.KEYCODE_ENTER) // 添加回车键选项
            list.add("Tab" to KeyEvent.KEYCODE_TAB) // 添加 Tab 键选项
            list.add("Esc" to KeyEvent.KEYCODE_ESCAPE) // 添加 Esc 键选项
            list.add("Backspace" to KeyEvent.KEYCODE_DEL) // 添加退格键选项（KEYCODE_DEL 即 Backspace）
            list.add("Back" to KeyEvent.KEYCODE_BACK) // 添加返回键选项

            // ===== 方向键 =====
            list.add("↑" to KeyEvent.KEYCODE_DPAD_UP) // 添加上方向键选项
            list.add("↓" to KeyEvent.KEYCODE_DPAD_DOWN) // 添加下方向键选项
            list.add("←" to KeyEvent.KEYCODE_DPAD_LEFT) // 添加左方向键选项
            list.add("→" to KeyEvent.KEYCODE_DPAD_RIGHT) // 添加右方向键选项

            // ===== 符号键 =====
            list.add("-" to KeyEvent.KEYCODE_MINUS) // 添加减号键选项
            list.add("=" to KeyEvent.KEYCODE_EQUALS) // 添加等号键选项
            list.add("[" to KeyEvent.KEYCODE_LEFT_BRACKET) // 添加左方括号键选项
            list.add("]" to KeyEvent.KEYCODE_RIGHT_BRACKET) // 添加右方括号键选项
            list.add(";" to KeyEvent.KEYCODE_SEMICOLON) // 添加分号键选项
            list.add("'" to KeyEvent.KEYCODE_APOSTROPHE) // 添加单引号键选项
            list.add("\\" to KeyEvent.KEYCODE_BACKSLASH) // 添加反斜杠键选项（字符串中需转义）
            list.add("," to KeyEvent.KEYCODE_COMMA) // 添加逗号键选项
            list.add("." to KeyEvent.KEYCODE_PERIOD) // 添加句点键选项
            list.add("/" to KeyEvent.KEYCODE_SLASH) // 添加斜杠键选项
            list.add("`" to KeyEvent.KEYCODE_GRAVE) // 添加反引号键选项

            // ===== 锁定键 =====
            list.add("CapsLock" to KeyEvent.KEYCODE_CAPS_LOCK) // 添加大小写锁定键选项
            list.add("NumLock" to KeyEvent.KEYCODE_NUM_LOCK) // 添加数字锁定键选项
            list.add("ScrollLock" to KeyEvent.KEYCODE_SCROLL_LOCK) // 添加滚动锁定键选项

            // ===== 导航键 =====
            list.add("Insert" to KeyEvent.KEYCODE_INSERT) // 添加 Insert 键选项
            list.add("Home" to KeyEvent.KEYCODE_HOME) // 添加 Home 键选项
            list.add("PageUp" to KeyEvent.KEYCODE_PAGE_UP) // 添加上翻页键选项
            list.add("PageDown" to KeyEvent.KEYCODE_PAGE_DOWN) // 添加下翻页键选项
            list.add("End" to KeyEvent.KEYCODE_MOVE_END) // 添加 End 键选项

            return list // 返回构建好的按键选项列表
        } // 结束 buildKeyboardKeyOptions 函数

        /**
         * 鼠标按键选项列表（显示名称 → MouseButton 枚举）
         */
        val mouseButtonOptions: List<Pair<String, MouseButton>> = listOf( // 只读属性：鼠标按键选项列表（显示名→MouseButton 枚举），用 listOf 创建（语法：val=只读变量）
            "鼠标左键" to MouseButton.LEFT, // 鼠标左键选项
            "鼠标中键" to MouseButton.MIDDLE, // 鼠标中键选项
            "鼠标右键" to MouseButton.RIGHT, // 鼠标右键选项
            "鼠标前进键" to MouseButton.FORWARD, // 鼠标前进键选项
            "鼠标后退键" to MouseButton.BACK // 鼠标后退键选项（最后一个元素不带逗号）
        ) // 结束 listOf 调用

        /**
         * 将手柄按键枚举转换为人类可读的显示名称
         *
         * 使用 Steam/Xbox 风格命名（LB/RB/L2/R2/L3/R3 等）
         *
         * @param button 手柄按键枚举值
         * @return 可读名称（如 "A"、"LB"、"D-Pad ↑"）
         */
        fun buttonDisplayName(button: ControllerButton): String = when (button) { // 公开函数：将手柄按键枚举转成可读显示名，用 when 表达式直接返回（语法：fun=函数声明，when=分支表达式，表达式体用 = 号）
            ControllerButton.A -> "A" // A 键显示为 "A"（语法：->=when 分支）
            ControllerButton.B -> "B" // B 键显示为 "B"
            ControllerButton.X -> "X" // X 键显示为 "X"
            ControllerButton.Y -> "Y" // Y 键显示为 "Y"
            ControllerButton.LEFT_SHOULDER -> "LB" // 左肩键显示为 "LB"
            ControllerButton.RIGHT_SHOULDER -> "RB" // 右肩键显示为 "RB"
            ControllerButton.LEFT_TRIGGER_CLICK -> "L2" // 左扳机按下显示为 "L2"
            ControllerButton.RIGHT_TRIGGER_CLICK -> "R2" // 右扳机按下显示为 "R2"
            ControllerButton.LEFT_STICK_CLICK -> "L3" // 左摇杆按下显示为 "L3"
            ControllerButton.RIGHT_STICK_CLICK -> "R3" // 右摇杆按下显示为 "R3"
            ControllerButton.MENU -> "Menu" // 菜单键显示为 "Menu"
            ControllerButton.OPTIONS -> "Options" // 选项键显示为 "Options"
            ControllerButton.GUIDE -> "Guide" // 主页/Guide 键显示为 "Guide"
            ControllerButton.DPAD_UP -> "D-Pad ↑" // 十字键上显示为 "D-Pad ↑"
            ControllerButton.DPAD_DOWN -> "D-Pad ↓" // 十字键下显示为 "D-Pad ↓"
            ControllerButton.DPAD_LEFT -> "D-Pad ←" // 十字键左显示为 "D-Pad ←"
            ControllerButton.DPAD_RIGHT -> "D-Pad →" // 十字键右显示为 "D-Pad →"
            ControllerButton.TOUCHPAD_CLICK -> "Touchpad" // 触摸板按下显示为 "Touchpad"
        } // 结束 when 表达式/buttonDisplayName 函数
    } // 结束 companion object 伴生对象

    // ====================================================================
    // UI 元素
    // ====================================================================

    /** 操作集选择下拉框（切换操作集） */
    private lateinit var actionSetSpinner: Spinner // 延迟初始化属性：操作集选择下拉框（语法：lateinit=延迟初始化，var=可变变量）

    /** 操作集管理按钮：添加/拷贝/改名/删除 */
    private lateinit var actionSetAddButton: Button // 延迟初始化属性：「添加操作集」按钮
    private lateinit var actionSetCopyButton: Button // 延迟初始化属性：「拷贝操作集」按钮
    private lateinit var actionSetRenameButton: Button // 延迟初始化属性：「重命名操作集」按钮
    private lateinit var actionSetDeleteButton: Button // 延迟初始化属性：「删除操作集」按钮

    /** 操作层选择下拉框 */
    private lateinit var layerSpinner: Spinner // 延迟初始化属性：操作层选择下拉框

    /** 按键映射列表 */
    private lateinit var mappingsListView: ListView // 延迟初始化属性：按键映射列表视图

    /** 当前层映射摘要（显示层名 + 已映射按键数量） */
    private lateinit var mappingSummaryText: TextView // 延迟初始化属性：当前层映射摘要文本控件

    /** 编辑层名称按钮 */
    private lateinit var editLayerNameButton: Button // 延迟初始化属性：「编辑层名称」按钮

    /** 编辑触发按键按钮 */
    private lateinit var editTriggerButton: Button // 延迟初始化属性：「编辑切入按键」按钮

    /** 保存配置按钮 */
    private lateinit var saveButton: Button // 延迟初始化属性：「保存配置」按钮

    // ====================================================================
    // 状态
    // ====================================================================

    /** 当前选中的操作层 */
    private var currentLayer: OperationLayer? = null // 可空属性：当前选中的操作层，未选中时为 null（语法：var=可变变量，OperationLayer?=可空类型）

    /**
     * 当前选中的操作集
     *
     * 所有层的编辑（层 Spinner、映射、切入按键）都基于此操作集；
     * 切换操作集时其下所有操作层整体切换。
     */
    private var currentActionSet: ActionSet? = null // 可空属性：当前选中的操作集（所有层编辑都基于它）

    /** 所有操作集名称列表（用于操作集 Spinner 选项） */
    private var actionSetNames: List<String> = emptyList() // 可变属性：所有操作集名称列表，初始为空列表

    /**
     * 抑制操作集 Spinner 监听器标志
     *
     * 程序化更新操作集 Spinner 的 adapter/selection 时阻止 onItemSelected 回调，
     * 避免重建列表时误触发操作集切换。
     */
    private var suppressActionSetSpinnerListener = false // 标志位：抑制操作集 Spinner 监听器（程序化更新时置 true）

    // ===== 手柄按键视觉反馈状态 =====
    /** 扳机按到底阈值（与 SteamInput 一致，轴值 >= 此值视为按下） */
    private val triggerClickThreshold = 0.5f // 只读常量：扳机判定按下的轴值阈值（语法：val=只读变量，0.5f=Float 字面量）
    /** D-Pad HAT 轴当前激活的方向集合（避免 MotionEvent 高频触发重复事件） */
    private val hatState = mutableSetOf<ControllerButton>() // 只读引用：当前被按下的十字键方向集合（集合本身可变）（语法：mutableSetOf=可变集合工厂）
    private var l2Pressed = false // 标志位：左扳机当前是否处于按下状态
    private var r2Pressed = false // 标志位：右扳机当前是否处于按下状态

    /**
     * 当前编辑的控制器配置（本地副本）
     *
     * 不依赖手柄映射服务运行：优先取服务运行时 [SteamInput.profile]，
     * 否则从配置文件加载，编辑后写回文件。服务运行时同步到 [SteamInput]。
     */
    private var profile: ControllerProfile = ControllerProfile.createDefault() // 当前编辑的控制器配置（本地副本），默认配置兜底（语法：var=可变变量）

    /** 所有操作层名称列表（用于 Spinner 选项） */
    private var layerNames: List<String> = emptyList() // 可变属性：所有操作层名称列表，初始为空

    /**
     * 抑制 Spinner 监听器标志
     *
     * 当程序化更新 Spinner 的 adapter 和 selection 时，阻止 onItemSelected 回调执行。
     * 避免在重建列表时触发不必要的 loadLayer 调用。
     */
    private var suppressLayerSpinnerListener = false // 标志位：抑制操作层 Spinner 监听器（程序化更新时置 true）

    // ====================================================================
    // 生命周期
    // ====================================================================

    /**
     * Activity 创建时调用
     *
     * 初始化 UI、加载配置、设置事件监听。
     */
    override fun onCreate(savedInstanceState: Bundle?) { // 覆写生命周期方法：Activity 创建时调用，负责初始化界面与数据（语法：override=覆写父类方法，fun=函数声明，Bundle?=可空参数）
        super.onCreate(savedInstanceState) // 调用父类 onCreate 完成基础初始化（语法：super=调用父类成员）

        // 挖孔屏横屏兜底设置：尝试让窗口内容延伸到挖孔区。
        // 本机 MIUI/Android 16 下对系统装饰避让无效，主方案是使用自定义标题栏
        // （见 R.id.btn_back），此处作为其它挖孔设备的兜底保留。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // 判断系统版本是否 >= Android 9（P），决定是否处理挖孔屏（语法：if=条件判断）
            window.attributes.layoutInDisplayCutoutMode = // 设置窗口布局在挖孔区域的显示模式（跨行赋值，上半部分）
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES // 模式为 SHORT_EDGES：内容延伸到短边挖孔区
        } // 结束挖孔屏兜底设置 if 块

        // 状态栏深色（与深色界面一致，图标为浅色）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { // 判断系统版本是否 >= Android 5.0（LOLLIPOP）
            window.statusBarColor = 0xFF1C1C1C.toInt() // 设置状态栏颜色为深灰（0xFF1C1C1C 转 Int）（语法：toInt()=类型转换）
        } // 结束状态栏颜色设置 if 块

        setContentView(R.layout.activity_layer_edit) // 加载本页面的布局文件作为内容视图

        // 自定义标题栏返回按钮（替代系统 ActionBar 的返回箭头。
        // 系统 ActionBar 在挖孔屏横屏时会避让挖孔区导致不贴边，故用自定义标题栏）
        findViewById<android.widget.TextView>(R.id.btn_back).setOnClickListener { finish() } // 绑定自定义标题栏返回按钮，点击时结束当前页面（语法：findViewById<T>=按 ID 查找控件，setOnClickListener{}=点击监听 lambda）

        // 加载配置（优先服务运行时 profile，否则从配置文件加载，不依赖服务运行）
        profile = loadProfile() // 调用加载函数获取配置并赋给本地 profile

        // 注意：不再暂停悬浮窗，用户要求进入设置页面时悬浮窗保持可见

        // 初始化 UI 元素
        actionSetSpinner = findViewById(R.id.spinner_action_set) // 初始化操作集选择下拉框（按 ID 查找布局控件）
        actionSetAddButton = findViewById(R.id.btn_action_set_add) // 初始化「添加操作集」按钮
        actionSetCopyButton = findViewById(R.id.btn_action_set_copy) // 初始化「拷贝操作集」按钮
        actionSetRenameButton = findViewById(R.id.btn_action_set_rename) // 初始化「重命名操作集」按钮
        actionSetDeleteButton = findViewById(R.id.btn_action_set_delete) // 初始化「删除操作集」按钮
        layerSpinner = findViewById(R.id.spinner_layer) // 初始化操作层选择下拉框
        mappingsListView = findViewById(R.id.list_mappings) // 初始化按键映射列表视图
        mappingSummaryText = findViewById(R.id.text_mapping_summary) // 初始化映射摘要文本控件
        editLayerNameButton = findViewById(R.id.btn_edit_layer_name) // 初始化「编辑层名称」按钮
        editTriggerButton = findViewById(R.id.btn_edit_trigger) // 初始化「编辑切入按键」按钮
        saveButton = findViewById(R.id.btn_save) // 初始化「保存配置」按钮

        // 设置操作集选择 Spinner 与操作集管理按钮
        setupActionSetSpinner() // 初始化操作集下拉框（填充选项与选择监听器）
        actionSetAddButton.setOnClickListener { showAddActionSetDialog() } // 点击「添加」弹出添加操作集对话框（语法：setOnClickListener{}=点击监听 lambda）
        actionSetCopyButton.setOnClickListener { showCopyActionSetDialog() } // 点击「拷贝」弹出拷贝操作集对话框
        actionSetRenameButton.setOnClickListener { showRenameActionSetDialog() } // 点击「重命名」弹出重命名对话框
        actionSetDeleteButton.setOnClickListener { confirmDeleteActionSet() } // 点击「删除」弹出删除确认对话框

        // 设置操作层选择 Spinner
        setupLayerSpinner() // 初始化操作层下拉框（填充层选项与选择监听器）

        // 设置按钮点击事件
        editLayerNameButton.setOnClickListener { showLayerNameEditDialog() } // 点击「编辑层名称」弹出改名对话框
        editTriggerButton.setOnClickListener { showTriggerButtonEditDialog() } // 点击「切入按键」弹出切入键编辑对话框
        saveButton.setOnClickListener { saveProfile(showToast = true) } // 点击「保存」保存配置并显示提示（命名参数 showToast=true）

        // 设置按键映射列表点击事件（点击某个按键进入编辑对话框）
        mappingsListView.setOnItemClickListener { _, _, position, _ -> // 设置列表项点击监听：点击某行进入编辑（语法：lambda 参数 {_,_,position,_->}，_=忽略参数）
            // 根据 position 获取对应的 ControllerButton 枚举值
            val button = ControllerButton.values()[position] // 用行号 position 从枚举数组取对应手柄按键（语法：values()=枚举静态方法返回数组）
            showMappingEditDialog(button) // 弹出该按键的映射编辑对话框
        } // 结束列表项点击监听 lambda

        Log.i(TAG, "LayerEditActivity created, profile layers: ${profile.allLayers.size}") // 打印日志：页面创建成功，并输出当前层数量（语法：字符串模板 "${表达式}"）
    } // 结束 onCreate 函数

    /**
     * 加载控制器配置（不依赖服务运行）
     *
     * 优先级：服务运行时 [SteamInput.profile] → 内部配置文件 → 默认配置。
     */
    private fun loadProfile(): ControllerProfile { // 私有函数：加载控制器配置并返回（语法：fun=函数声明，返回类型 ControllerProfile）
        steamInputRef?.profile?.let { return it } // 若服务运行中则直接返回其运行时 profile（语法：?.=安全调用，let{}=lambda 接收非空值，it=隐式参数）
        val file = File(filesDir, ConfigManager.CONFIG_FILE_NAME) // 构造配置文件对象：内部存储目录 + 配置文件名
        if (file.exists()) { // 若配置文件存在则进入解析分支
            return try { // 尝试解析 JSON（语法：try=异常捕获块）
                ControllerConfig.fromJson(file.readText()) // 读取文件文本并反序列化为 ControllerProfile
            } catch (e: Exception) { // 捕获解析异常（语法：catch (e: Exception)=捕获异常并绑定到变量 e）
                Log.e(TAG, "Failed to parse config, using default", e) // 打印错误日志，并传入异常对象
                ControllerProfile.createDefault() // 返回默认配置作为兜底
            } // 结束 try-catch 块
        } // 结束配置文件存在 if 分支
        return ControllerProfile.createDefault() // 无配置文件时返回默认配置
    } // 结束 loadProfile 函数

    /**
     * 处理菜单项点击
     *
     * 兼容处理 android.R.id.home 返回事件（等价于按返回键）。
     * 当前页面已改用自定义标题栏返回按钮（[R.id.btn_back]），
     * 不再依赖系统 ActionBar 返回箭头，此处理仅作兼容保留。
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean { // 覆写：处理菜单项点击事件（语法：override=覆写，fun=函数声明，返回类型 Boolean）
        if (item.itemId == android.R.id.home) { // 若点击的是系统 Home（返回箭头）菜单项
            finish() // 结束当前页面（等价按返回键）
            return true // 返回 true 表示事件已消费
        } // 结束 Home 菜单项判断 if 块
        return super.onOptionsItemSelected(item) // 其它菜单项交给父类处理（语法：super=调用父类实现）
    } // 结束 onOptionsItemSelected 函数

    /**
     * 手柄按键视觉反馈
     *
     * 手柄按键按下/释放时高亮映射列表中对应的按钮行，松开恢复。
     * 事件来源过滤为 GAMEPAD/DPAD/JOYSTICK，不影响键盘/软键盘输入。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean { // 覆写：分发按键事件，用于手柄按键视觉反馈（语法：override=覆写，fun=函数声明，返回类型 Boolean）
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || // 判断事件是否来自游戏手柄设备（跨行条件，上半部分）
            event.isFromSource(InputDevice.SOURCE_DPAD) || // 或来自十字键设备
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) // 或来自摇杆设备
        ) { // 结束来源判断，进入手柄事件处理块
            // 使用已连接手柄的类型做按键映射（PS 手柄 A/X 位置互换），未连接时用默认
            val controllerType = steamInputRef?.controllers?.get(event.deviceId)?.controllerType // 获取该设备对应的手柄类型（安全调用链，可能为 null）（语法：?.=安全调用）
                ?: ControllerType.XBOX_360 // 若为空则默认 Xbox 360 类型（语法：?:=空值合并运算符）
            val button = ControllerInputMapper.mapKeyCode(event.keyCode, controllerType) // 将 KeyCode 按手柄类型映射为 ControllerButton
            if (button != null && event.repeatCount == 0) { // 仅处理映射成功且是首次按下（非长按重复）的情况（语法：&&=逻辑与）
                updateMappingRowHighlight(button, event.action == KeyEvent.ACTION_DOWN) // 根据按下/释放动作更新映射列表行高亮
            } // 结束映射非空判断 if 块
        } // 结束手柄事件来源处理 if 块
        return super.dispatchKeyEvent(event) // 交回父类继续正常分发事件
    } // 结束 dispatchKeyEvent 函数

    /**
     * 手柄模拟量事件（扳机 / 十字键 HAT 轴）视觉反馈
     *
     * L2/R2 与很多手柄的 D-Pad 通过 MotionEvent 轴值上报而非 KeyEvent，
     * 需在此检测并同步到映射列表高亮。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean { // 覆写：处理手柄模拟量事件（扳机/十字键 HAT 轴）的视觉反馈
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || // 判断事件是否来自游戏手柄（跨行条件）
            event.isFromSource(InputDevice.SOURCE_DPAD) || // 或来自十字键
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) // 或来自摇杆
        ) { // 结束来源判断，进入模拟量处理块
            handleDpadHatHighlight(event) // 处理十字键 HAT 轴的按下/释放高亮
            handleTriggerHighlight(event) // 处理 L2/R2 扳机轴的按下/释放高亮
        } // 结束手柄模拟量来源判断 if 块
        return super.onGenericMotionEvent(event) // 交回父类继续处理
    } // 结束 onGenericMotionEvent 函数

    /**
     * 处理 D-Pad HAT 轴（AXIS_HAT_X / AXIS_HAT_Y）按下/释放高亮
     */
    private fun handleDpadHatHighlight(event: MotionEvent) { // 私有函数：处理十字键 HAT 轴按下/释放的高亮
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X) // 读取 HAT 轴 X 方向值（范围 -1~1）
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y) // 读取 HAT 轴 Y 方向值（范围 -1~1）
        val upPressed = hatY < -0.5f // Y 值 < -0.5 视为上方向按下
        val downPressed = hatY > 0.5f // Y 值 > 0.5 视为下方向按下
        val leftPressed = hatX < -0.5f // X 值 < -0.5 视为左方向按下
        val rightPressed = hatX > 0.5f // X 值 > 0.5 视为右方向按下
        listOf( // 构建「按键→是否按下」配对列表（语法：listOf=不可变列表工厂）
            ControllerButton.DPAD_UP to upPressed, // 上方向按键与按下状态配对
            ControllerButton.DPAD_DOWN to downPressed, // 下方向按键与按下状态配对
            ControllerButton.DPAD_LEFT to leftPressed, // 左方向按键与按下状态配对
            ControllerButton.DPAD_RIGHT to rightPressed // 右方向按键与按下状态配对
        ).forEach { (button, pressed) -> // 遍历每个配对并解构为 button 和 pressed（语法：forEach=遍历 lambda，解构声明）
            if (pressed && !hatState.contains(button)) { // 按下且之前未记录，则新增按下状态
                hatState.add(button) // 记录该方向已按下
                updateMappingRowHighlight(button, true) // 高亮对应映射行
            } else if (!pressed && hatState.contains(button)) { // 释放且之前记录为按下，则清除状态
                hatState.remove(button) // 移除按下状态记录
                updateMappingRowHighlight(button, false) // 取消对应映射行高亮
            } // 结束按下/释放处理 if-else 块
        } // 结束 forEach 遍历
    } // 结束 handleDpadHatHighlight 函数

    /**
     * 处理 L2/R2 扳机轴（AXIS_LTRIGGER / AXIS_RTRIGGER）按下/释放高亮
     */
    private fun handleTriggerHighlight(event: MotionEvent) { // 私有函数：处理 L2/R2 扳机轴的按下/释放高亮
        var l2Value = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) // 读取左扳机轴值（语法：var=可变变量）
        if (l2Value == 0f && event.device?.getMotionRange(MotionEvent.AXIS_BRAKE) != null) { // 若 LTRIGGER 为 0 且设备支持 BRAKE 轴，则改用 BRAKE 轴（兼容部分手柄）
            l2Value = event.getAxisValue(MotionEvent.AXIS_BRAKE) // 从 BRAKE 轴重新读取左扳机值
        } // 结束左扳机轴兼容判断
        if (l2Value >= triggerClickThreshold && !l2Pressed) { // 轴值达阈值且当前未按下，则判定为按下
            l2Pressed = true // 记录左扳机为按下状态
            updateMappingRowHighlight(ControllerButton.LEFT_TRIGGER_CLICK, true) // 高亮左扳机映射行
        } else if (l2Value < triggerClickThreshold && l2Pressed) { // 轴值低于阈值且当前按下，则判定为释放
            l2Pressed = false // 记录左扳机为释放状态
            updateMappingRowHighlight(ControllerButton.LEFT_TRIGGER_CLICK, false) // 取消左扳机映射行高亮
        } // 结束左扳机按下/释放判断

        var r2Value = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) // 读取右扳机轴值
        if (r2Value == 0f && event.device?.getMotionRange(MotionEvent.AXIS_GAS) != null) { // 若 RTRIGGER 为 0 且设备支持 GAS 轴，则改用 GAS 轴（兼容部分手柄）
            r2Value = event.getAxisValue(MotionEvent.AXIS_GAS) // 从 GAS 轴重新读取右扳机值
        } // 结束右扳机轴兼容判断
        if (r2Value >= triggerClickThreshold && !r2Pressed) { // 轴值达阈值且当前未按下，则判定为按下
            r2Pressed = true // 记录右扳机为按下状态
            updateMappingRowHighlight(ControllerButton.RIGHT_TRIGGER_CLICK, true) // 高亮右扳机映射行
        } else if (r2Value < triggerClickThreshold && r2Pressed) { // 轴值低于阈值且当前按下，则判定为释放
            r2Pressed = false // 记录右扳机为释放状态
            updateMappingRowHighlight(ControllerButton.RIGHT_TRIGGER_CLICK, false) // 取消右扳机映射行高亮
        } // 结束右扳机按下/释放判断
    } // 结束 handleTriggerHighlight 函数

    /**
     * 更新按键映射列表行的按下高亮
     *
     * @param button 手柄按钮
     * @param pressed true=按下高亮, false=释放恢复
     */
    private fun updateMappingRowHighlight(button: ControllerButton, pressed: Boolean) { // 私有函数：更新映射列表行的按下高亮
        if (!::mappingsListView.isInitialized) return // 若列表尚未初始化则直接返回（语法：::属性引用 + isInitialized 检查 lateinit）
        val position = button.ordinal // 用枚举声明顺序序号作为列表行号（语法：ordinal=枚举顺序索引）
        val visibleIndex = position - mappingsListView.firstVisiblePosition // 计算行号相对首个可见行的偏移
        val row = mappingsListView.getChildAt(visibleIndex) ?: return // 获取该偏移对应的可见行 View，不可见则返回（语法：?:=空值合并）
        row.setBackgroundColor(if (pressed) 0xFF2196F3.toInt() else 0x00000000) // 按下设为蓝色高亮，释放设为透明（语法：if...else 表达式）
    } // 结束 updateMappingRowHighlight 函数

    /**
     * Activity 销毁时调用
     *
     * 注意：不再需要恢复悬浮窗，因为进入设置页面时没有暂停悬浮窗。
     */
    override fun onDestroy() { // 覆写：Activity 销毁时调用（语法：override=覆写）
        super.onDestroy() // 调用父类清理逻辑
    } // 结束 onDestroy 函数

    /**
     * 发送 overlay 控制意图到 ControllerOverlayService
     *
     * 用于通知服务暂停/恢复悬浮窗。
     *
     * @param action ControllerOverlayService.ACTION_PAUSE_OVERLAY
     *               或 ControllerOverlayService.ACTION_RESUME_OVERLAY
     */
    private fun sendOverlayAction(action: String) { // 私有函数：发送 overlay 控制意图到悬浮窗服务
        val intent = Intent(this, ControllerOverlayService::class.java).apply { // 构建启动服务的意图，apply 块内修改属性（语法：apply{}=作用域函数返回自身，::class.java=类引用）
            this.action = action // 设置意图的 action 为传入的控制指令
        } // 结束 apply 块
        ContextCompat.startForegroundService(this, intent) // 以前台服务方式启动悬浮窗服务
    } // 结束 sendOverlayAction 函数

    // ====================================================================
    // 初始化方法
    // ====================================================================

    /**
     * 设置操作层选择 Spinner
     *
     * 从当前操作集获取所有层名称，填充到 Spinner。
     * 优先使用 [preferredLayerName]（操作集切换时默认选中第一层），
     * 否则取 Intent 携带的 EXTRA_LAYER_NAME，再否则选中第一层。
     *
     * @param preferredLayerName 优先选中的层名（null 时按默认规则选择）
     */
    private fun setupLayerSpinner(preferredLayerName: String? = null) { // 私有函数：初始化操作层下拉框（语法：fun=函数声明，String?=可空参数并带默认值 null）
        val actionSet = currentActionSet ?: return // 取当前操作集，为空则退出（语法：?:=空值合并 + return 提前返回）

        // 获取当前操作集所有层名称（Common + Layer1-Layer10）
        layerNames = actionSet.allLayers.map { it.name } // 提取所有层名称到列表（语法：map{}=转换 lambda，it=隐式参数）

        // 创建适配器并设置到 Spinner
        // 创建数组适配器绑定层名列表（语法：ArrayAdapter=数组适配器，also{}=作用域函数返回原对象）
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, layerNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块
        layerSpinner.adapter = adapter // 将适配器设置到层选择下拉框

        // 获取初始层名称：优先参数，其次 Intent 传递的层名，最后第一层
        val initialLayerName = preferredLayerName // 初始层名先取参数值（跨行赋值上半部分）
            ?: intent.getStringExtra(EXTRA_LAYER_NAME) // 参数为空则取 Intent 传递的层名（语法：?:=空值合并）
            ?: layerNames.first() // 仍为空则取层列表第一项
        val initialPos = layerNames.indexOf(initialLayerName).coerceAtLeast(0) // 计算初始层在列表中的位置，最小为 0（语法：coerceAtLeast=下限约束）
        layerSpinner.setSelection(initialPos) // 设置下拉框初始选中项

        // 设置 Spinner 选择监听器（用户切换操作层时刷新列表）
        layerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { // 设置选择监听器：匿名对象实现接口（语法：object:接口=匿名对象实现监听接口）
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { // 覆写：选项被选中时回调（语法：override=覆写，<*>=星投影通配泛型）
                // 程序化更新时跳过（避免重建列表时的重复调用）
                if (suppressLayerSpinnerListener) return // 抑制标志为 true 时跳过处理
                val name = layerNames.getOrNull(position) ?: return // 按位置取层名，越界则返回（语法：getOrNull=安全取值，?:=空值合并）
                // 选中项已是当前层则跳过：重建 adapter 后 onItemSelected 由异步布局触发，
                // suppress 标志可能已复位，不判断会把选择重置回第一层（表现为"选了不切换"）
                if (name == currentLayer?.name) return // 若与当前层同名则跳过（避免重复加载）
                loadLayer(name) // 加载并显示该层的映射
            } // 结束 onItemSelected 回调

            override fun onNothingSelected(parent: AdapterView<*>?) {} // 覆写：无选项选中时回调，此处为空实现
        } // 结束匿名监听对象

        // 加载初始层
        loadLayer(layerNames[initialPos]) // 加载初始选中层
    } // 结束 setupLayerSpinner 函数

    /**
     * 加载指定名称的操作层（在当前操作集内查找）
     *
     * @param name 操作层名称（如 "Common"、"Layer1"）
     */
    private fun loadLayer(name: String) { // 私有函数：加载指定名称的操作层
        val actionSet = currentActionSet ?: return // 取当前操作集，为空则退出（语法：?:=空值合并）
        currentLayer = actionSet.findLayer(name) // 在当前操作集中查找指定层并设为当前层
        refreshMappingsList() // 刷新按键映射列表
        updateLayerInfoButtons() // 更新层信息按钮显示
        Log.d(TAG, "Loaded layer: $name, mappings: ${currentLayer?.buttonMappings?.size}") // 打印调试日志：层名与映射数量（语法：字符串模板 "${...}"）
    } // 结束 loadLayer 函数

    /**
     * 刷新按键映射列表与当前层摘要
     *
     * 遍历所有 [ControllerButton] 枚举值，显示每个按键的映射情况。
     * 未设置映射的按键显示 "[未设置]"。
     *
     * 同时更新映射摘要行（当前层名称 + 已映射按键数量）。
     */
    private fun refreshMappingsList() { // 私有函数：刷新按键映射列表与当前层摘要
        val layer = currentLayer ?: return // 取当前层，为空则退出（语法：?:=空值合并）

        // 构建显示列表: 每个手柄按键一行
        val items = ControllerButton.values().map { button -> // 遍历所有手柄按键构建显示行（语法：map{}=转换 lambda，显式参数 button）
            val mapping = layer.buttonMappings[button] // 取该按键的映射，可能为 null
            val desc = mapping?.describe() ?: "[未设置]" // 取映射描述，无映射显示「未设置」（语法：?.=安全调用，?:=空值合并）
            "${buttonDisplayName(button)} → $desc" // 拼接「按键名 → 映射描述」字符串（语法：字符串模板 "$var"）
        } // 结束 map 转换

        // 更新当前层映射摘要（层名 + 已映射数量）
        if (::mappingSummaryText.isInitialized) { // 若摘要文本控件已初始化则更新（语法：:: + isInitialized 检查 lateinit）
            val mappedCount = layer.buttonMappings.values.count() // 统计已映射的按键数量
            mappingSummaryText.text = // 设置摘要文本（跨行赋值）
                "当前层「${layer.name}」已映射 $mappedCount 个按键" // 摘要文本内容：层名 + 映射数量（语法：字符串模板）
        } // 结束摘要更新 if 块

        // 使用 ArrayAdapter 绑定数据到 ListView
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items) // 创建数组适配器，绑定映射描述行到系统简单列表布局
        mappingsListView.adapter = adapter // 将适配器设置到映射列表视图
    } // 结束 refreshMappingsList 函数

    /**
     * 计算切入当前层的按键
     *
     * 即当前操作集的公共层（Common）中配置了「切换到当前层」（[MappedAction.SwitchLayer]）
     * 的手柄按键。这是实际驱动层切换的按键，也是「切入按键」按钮的数据来源。
     *
     * @return 公共层中切到当前层的按键列表（可为空）
     */
    private fun switchInButtons(): List<ControllerButton> { // 私有函数：计算切入当前层的按键列表
        val layer = currentLayer ?: return emptyList() // 取当前层，为空返回空列表（语法：?:=空值合并）
        val common = currentActionSet?.commonLayer ?: return emptyList() // 取公共层，为空返回空列表（语法：?.=安全调用，?:=空值合并）
        return common.buttonMappings // 从公共层映射表开始筛选（跨行链式调用）
            .filterValues { mapping -> // 过滤映射：只保留切到当前层的条目（语法：filterValues=按值过滤 lambda）
                (mapping.action as? MappedAction.SwitchLayer)?.layerName == layer.name // 判断动作是否为 SwitchLayer 且目标层等于当前层（语法：as?=安全类型转换）
            } // 结束 filterValues 过滤
            .keys // 取出符合条件的按键集合
            .toList() // 转为列表返回
    } // 结束 switchInButtons 函数

    /**
     * 更新层信息按钮的显示文字和状态
     *
     * - 编辑名称按钮: 显示当前层名称
     * - 切入按键按钮: 显示公共层中切到当前层的按键（Common 层禁用）
     */
    private fun updateLayerInfoButtons() { // 私有函数：更新层信息按钮的显示文字和状态
        val layer = currentLayer // 读取当前层引用
        val actionSet = currentActionSet // 读取当前操作集引用

        if (layer == null || actionSet == null) { // 若当前层或操作集为空（语法：||=逻辑或）
            editLayerNameButton.text = "层名称" // 名称按钮显示占位文字「层名称」
            editTriggerButton.text = "切入按键" // 切入键按钮显示占位文字「切入按键」
            editTriggerButton.isEnabled = false // 禁用切入键按钮
        } else { // 否则（当前层与操作集均有效）
            editLayerNameButton.text = "名称: ${layer.name}" // 名称按钮显示当前层名（语法：字符串模板）
            // 切入按键 = 当前操作集公共层中切到本层的 SwitchLayer 按键
            val triggerText = switchInButtons() // 计算切入本层的按键列表（跨行赋值）
                .joinToString("/") { buttonDisplayName(it) } // 用斜杠连接各按键显示名（语法：joinToString=拼接 lambda）
            editTriggerButton.text = if (triggerText.isEmpty()) "切入: 无" else "切入: $triggerText" // 无切入键显示「切入: 无」，否则显示按键名（语法：if...else 表达式，字符串模板）
            // 公共层（Common）始终激活，不能设置切入按键
            val isCommon = (layer === actionSet.commonLayer) // 判断当前层是否就是公共层（语法：===引用相等比较）
            editTriggerButton.isEnabled = !isCommon // 公共层时禁用切入键按钮，其它层启用
        } // 结束层信息按钮更新 if-else 块
    } // 结束 updateLayerInfoButtons 函数

    // ====================================================================
    // 编辑对话框
    // ====================================================================

    /**
     * 显示按键映射编辑对话框
     *
     * 对话框内容:
     * 1. 动作类型 Spinner（键盘按键 / 鼠标点击 / 切换操作层）
     * 2. 动作值 Spinner（根据动作类型动态切换选项）
     * 3. 子命令区域（最多3个键盘按键，切换操作层时隐藏）
     *
     * 保存时构建 [KeyMapping] 并写入当前操作层的 buttonMappings。
     *
     * @param button 正在编辑的手柄按键
     */
    private fun showMappingEditDialog(button: ControllerButton) { // 私有函数：显示按键映射编辑对话框
        val layer = currentLayer ?: return // 取当前层，为空则退出（语法：?:=空值合并）
        val existingMapping = layer.buttonMappings[button] // 取该按键已有的映射，可能为 null

        // 从布局文件加载对话框视图
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mapping_edit, null) // 用布局填充器把对话框布局实例化为 View（语法：LayoutInflater=布局实例化器）

        // 获取对话框中的 UI 元素
        val tvButtonName = dialogView.findViewById<TextView>(R.id.tv_button_name) // 获取按钮名称文本控件（语法：findViewById<T>=按 ID 查找并指定类型）
        val spinnerActionType = dialogView.findViewById<Spinner>(R.id.spinner_action_type) // 获取动作类型下拉框
        val tvActionLabel = dialogView.findViewById<TextView>(R.id.tv_action_label) // 获取动作值标签文本控件
        val spinnerActionValue = dialogView.findViewById<Spinner>(R.id.spinner_action_value) // 获取动作值下拉框
        val spinnerLongPressType = dialogView.findViewById<Spinner>(R.id.spinner_long_press_type) // 获取长按动作类型下拉框
        val tvLongPressLabel = dialogView.findViewById<TextView>(R.id.tv_long_press_label) // 获取长按动作值标签文本控件
        val spinnerLongPressValue = dialogView.findViewById<Spinner>(R.id.spinner_long_press_value) // 获取长按动作值下拉框
        val layoutExtraKey = dialogView.findViewById<LinearLayout>(R.id.layout_extra_key) // 获取「同时按下的按键」区域容器
        val spinnerExtraKey = dialogView.findViewById<Spinner>(R.id.spinner_extra_key) // 获取「同时按下的按键」下拉框
        val layoutSubCommands = dialogView.findViewById<LinearLayout>(R.id.layout_sub_commands) // 获取子命令区域父容器
        val layoutSubCommandList = dialogView.findViewById<LinearLayout>(R.id.layout_sub_command_list) // 获取子命令列表容器
        val btnAddSubCommand = dialogView.findViewById<Button>(R.id.btn_add_sub_command) // 获取「添加子命令」按钮

        // 显示正在编辑的按键名称
        tvButtonName.text = "编辑按键: ${buttonDisplayName(button)}" // 设置标题显示正在编辑的按键名（语法：字符串模板）

        // ===== 设置动作类型 Spinner =====
        // 0=未设置(取消映射), 1=键盘按键, 2=鼠标点击, 3=鼠标长按, 4=切换操作层,
        // 5=滚轮上滚, 6=滚轮下滚, 7=切换悬浮窗, 8=切换键盘, 9=切换捕获, 10=切换层+按键, 11=数字小键盘, 12=鼠标长按交换,
        // 13=释放鼠标长按, 14=交互键
        val actionTypes = listOf("未设置", "键盘按键", "鼠标点击", "鼠标长按", "切换操作层", // 定义动作类型选项列表（语法：listOf=列表工厂）
            "滚轮上滚", "滚轮下滚", "切换悬浮窗", "切换键盘", "切换捕获", "切换层+按键", "数字小键盘", "鼠标长按交换", "释放鼠标长按", "交互键") // 动作类型列表续行（共 15 种）
        // 设置动作类型下拉框的适配器（语法：ArrayAdapter=数组适配器，also{}=作用域函数）
        spinnerActionType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actionTypes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块

        // 从已有映射确定初始动作类型（0=未设置, 1=键盘按键, ... 12=鼠标长按交换）
        val initialActionType = actionTypeIndexOf(existingMapping?.action) // 复用动作类型索引转换（语法：调用私有辅助函数）

        // 先设置动作值 Spinner 的适配器（基于初始类型）
        setupActionValueSpinner(spinnerActionValue, tvActionLabel, initialActionType) // 按初始类型初始化动作值下拉框的选项

        // ===== 长按动作类型 Spinner =====
        // 长按可选类型：排除「切换操作层」「切层+按键」（层切换按键保持原有「按住即切层」语义）
        val longPressTypeIndices = listOf(0, 1, 2, 3, 5, 6, 7, 8, 9, 11, 12, 13, 14) // 长按可选动作类型索引列表（语法：listOf 列表）
        spinnerLongPressType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, // 设置长按类型下拉框适配器
            longPressTypeIndices.map { actionTypes[it] }).also { // 用可选项索引映射出显示名（语法：map lambda）
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块
        val initialLongPressType = actionTypeIndexOf(existingMapping?.longPressAction) // 长按动作类型索引
        val initialLongPressPos = longPressTypeIndices.indexOf(initialLongPressType).coerceAtLeast(0) // 在长按选项列表中的位置
        setupActionValueSpinner(spinnerLongPressValue, tvLongPressLabel, initialLongPressType) // 初始化长按动作值下拉框选项
        spinnerLongPressType.setSelection(initialLongPressPos) // 设置长按动作类型下拉框的初始选中项

        // 设置「同时按下的按键」下拉框的适配器（固定为键盘按键列表，仅切层+按键类型使用）
        spinnerExtraKey.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, keyboardKeyOptions.map { it.first }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块

        // 设置初始动作类型选择
        spinnerActionType.setSelection(initialActionType) // 设置动作类型下拉框的初始选中项

        // ===== 定义「恢复动作值定位」局部函数 =====
        // 仅用于打开对话框时的初始定位（让下拉框显示当前映射的按键，如映射的是"4"却显示"A"）。
        // 注意：不能用于动作类型切换后的 onItemSelected 回调，否则会按旧映射类型把选中位置
        // 设置到新列表的非法索引（如键盘长列表的 74 → 数字小键盘仅 11 项）导致越界崩溃。
        fun restoreValueSelectionFor(action: MappedAction?, valueSpinner: Spinner) { // 局部函数：根据已有动作恢复动作值下拉框选中项（语法：fun 局部函数）
            when (action) { // 根据已有映射动作设置动作值选中（语法：when(值)=分支）
                is MappedAction.KeyboardKey -> { // 若为键盘按键动作
                    val pos = keyboardKeyOptions.indexOfFirst { it.second == action.keyCode } // 查找与已存 KeyCode 匹配的选项下标（语法：indexOfFirst=按条件查找 lambda，it=隐式参数）
                    if (pos >= 0) valueSpinner.setSelection(pos) // 找到则设置动作值下拉框选中项
                } // 结束键盘按键分支
                is MappedAction.MouseClick -> { // 若为鼠标点击动作
                    val pos = mouseButtonOptions.indexOfFirst { it.second == action.button } // 查找与已存鼠标按键匹配的选项下标
                    if (pos >= 0) valueSpinner.setSelection(pos) // 找到则设置选中项
                } // 结束鼠标点击分支
                is MappedAction.MouseToggle -> { // 若为鼠标长按动作
                    val pos = mouseButtonOptions.indexOfFirst { it.second == action.button } // 查找与已存鼠标按键匹配的选项下标
                    if (pos >= 0) valueSpinner.setSelection(pos) // 找到则设置选中项
                } // 结束鼠标长按分支
                is MappedAction.SwitchLayer -> { // 若为切换层动作
                    val pos = layerNames.indexOfFirst { it == action.layerName } // 查找与目标层名匹配的选项下标
                    if (pos >= 0) valueSpinner.setSelection(pos) // 找到则设置选中项
                } // 结束切换层分支
                is MappedAction.SwitchLayerAndKey -> { // 若为切层+按键动作
                    val pos = layerNames.indexOfFirst { it == action.layerName } // 查找与目标层名匹配的选项下标
                    if (pos >= 0) valueSpinner.setSelection(pos) // 找到则设置目标层选中项
                    val keyPos = keyboardKeyOptions.indexOfFirst { it.second == action.keyCode } // 查找与同时按键 KeyCode 匹配的选项下标
                    if (keyPos >= 0) spinnerExtraKey.setSelection(keyPos) // 找到则设置额外按键选中项
                } // 结束切层+按键分支
                is MappedAction.NumpadKey -> { // 若为数字小键盘动作
                    val pos = numpadKeyOptions.indexOfFirst { it.second == action.keyCode } // 查找与小键盘键码匹配的选项下标
                    if (pos >= 0) valueSpinner.setSelection(pos) // 找到则设置动作值下拉框选中项
                } // 结束数字小键盘分支
                else -> {} // 其它类型无需设置动作值
            } // 结束恢复定位 when 块
        } // 结束 restoreValueSelectionFor 局部函数

        // 包装：恢复短按动作值选中项（语法：局部函数调用参数化版本）
        fun restoreActionValueSelection() = restoreValueSelectionFor(existingMapping?.action, spinnerActionValue) // 恢复短按动作值选中项
        // 包装：恢复长按动作值选中项
        fun restoreLongPressValueSelection() = restoreValueSelectionFor(existingMapping?.longPressAction, spinnerLongPressValue) // 恢复长按动作值选中项

        // ===== 定义「动作类型是否匹配已有映射」局部函数 =====
        // 仅在"当前动作类型与已有映射类型一致"时恢复动作值选中项：
        // - 打开对话框时 setSelection 触发的首次回调 / attach 布局重复触发的回调，
        //   重建动作值 Spinner（重置到首项）后能同步恢复为当前映射（如键盘 X）；
        // - 用户手动切换动作类型到其它类型时不匹配，不恢复（显示首项，重新选择）。
        fun actionTypeMatchesMapping(position: Int): Boolean = when (existingMapping?.action) { // 判断动作类型是否匹配已有映射（语法：局部函数 + when 表达式体）
            is MappedAction.KeyboardKey -> position == 1 // 键盘按键 → 类型 1
            is MappedAction.MouseClick -> position == 2 // 鼠标点击 → 类型 2
            is MappedAction.MouseToggle -> position == 3 // 鼠标长按 → 类型 3
            is MappedAction.MouseSwapToggle -> position == 12 // 鼠标长按交换 → 类型 12
            is MappedAction.MouseReleaseAll -> position == 13 // 释放鼠标长按 → 类型 13
            is MappedAction.MouseClickSequence -> position == 14 // 交互键 → 类型 14
            is MappedAction.SwitchLayer -> position == 4 // 切换层 → 类型 4
            is MappedAction.SwitchLayerAndKey -> position == 10 // 切层+按键 → 类型 10
            is MappedAction.NumpadKey -> position == 11 // 数字小键盘 → 类型 11
            is MappedAction.MouseScrollUp -> position == 5 // 滚轮上滚 → 类型 5
            is MappedAction.MouseScrollDown -> position == 6 // 滚轮下滚 → 类型 6
            is MappedAction.ToggleOverlay -> position == 7 // 切换悬浮窗 → 类型 7
            is MappedAction.ToggleKeyboard -> position == 8 // 切换键盘 → 类型 8
            is MappedAction.ToggleCapture -> position == 9 // 切换捕获 → 类型 9
            is MappedAction.MouseMove, is MappedAction.LookAround -> position == 0 // 摇杆专用 → 未设置(0)
            null -> position == 0 // 未映射 → 未设置(0)
        } // 结束 actionTypeMatchesMapping 局部函数

        // ===== 子命令管理 =====
        // 子命令 Spinner 列表（动态添加/删除）
        val subCommandSpinners = mutableListOf<Spinner>() // 创建子命令下拉框列表，用于动态跟踪所有子命令行（语法：mutableListOf=可变列表工厂）

        // 预填充已有子命令
        existingMapping?.subCommands?.forEach { keyCode -> // 遍历已有映射的子命令列表（语法：?.=安全调用，forEach=遍历 lambda）
            val spinner = addSubCommandSpinner(layoutSubCommandList, btnAddSubCommand, subCommandSpinners) // 动态添加一个子命令下拉框
            // 找到对应的键盘按键位置 (+1 因为第一个选项是"无")
            val pos = keyboardKeyOptions.indexOfFirst { it.second == keyCode } // 查找子命令 KeyCode 对应的选项下标
            if (pos >= 0) spinner.setSelection(pos + 1) // 找到则选中（加 1 跳过「无」选项）
        } // 结束已有子命令遍历
        updateAddSubCommandButton(btnAddSubCommand, subCommandSpinners.size) // 更新「添加子命令」按钮的文字与可用状态

        // 子命令区域可见性（未设置/切换操作层/滚轮/悬浮窗/键盘/捕获时隐藏；切层+按键类型支持子命令故显示）
        layoutSubCommands.visibility = // 设置子命令区域可见性（跨行赋值）
            if (initialActionType == 0 || initialActionType == 4 || // 若初始类型为未设置(0)或切换层(4)（跨行条件）
                initialActionType == 5 || initialActionType == 6 || // 或为滚轮上滚(5)、滚轮下滚(6)
                initialActionType == 7 || initialActionType == 8 || // 或为切换悬浮窗(7)、切换键盘(8)
                initialActionType == 9 || initialActionType == 12 || // 或为切换捕获(9)、鼠标长按交换(12)
                initialActionType == 13 || initialActionType == 14) // 或为释放鼠标长按(13)、交互键(14)
                View.GONE else View.VISIBLE // 上述类型隐藏子命令区，否则显示（语法：if...else 表达式，View.GONE=隐藏且不占位）

        // 额外按键区域仅「切换层+按键」类型显示
        layoutExtraKey.visibility = if (initialActionType == 10) View.VISIBLE else View.GONE // 仅切层+按键类型显示附加按键区

        // ===== 动作类型切换监听器 =====
        // 用户切换动作类型时，更新动作值 Spinner 的选项
        spinnerActionType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { // 设置动作类型下拉框选择监听：匿名对象实现接口（语法：object:接口=匿名对象）
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { // 覆写：动作类型切换时回调（语法：override=覆写）
                // 重新设置动作值 Spinner 的选项（内部会把选中位置重置为首项，避免旧索引越界崩溃）
                setupActionValueSpinner(spinnerActionValue, tvActionLabel, position) // 按新类型重建动作值下拉框的选项
                // 未设置/切换操作层/滚轮/悬浮窗/键盘/捕获时隐藏子命令区域（切层+按键类型支持子命令故显示）
                layoutSubCommands.visibility = // 设置子命令区域可见性（跨行赋值）
                    if (position == 0 || position == 4 || position == 5 || position == 6 || // 若类型为未设置/切换层/滚轮上下滚（跨行条件）
                        position == 7 || position == 8 || position == 9 || position == 12 || // 或为切换悬浮窗/键盘/捕获/鼠标长按交换
                        position == 13 || position == 14) // 或为释放鼠标长按(13)、交互键(14)
                        View.GONE else View.VISIBLE // 隐藏子命令区，否则显示
                // 额外按键区域仅「切换层+按键」类型显示
                layoutExtraKey.visibility = if (position == 10) View.VISIBLE else View.GONE // 仅切层+按键类型显示附加按键区
                // 动作类型与已有映射一致时，同步恢复动作值选中项：
                // 打开对话框/attach 布局会触发本回调重建动作值 Spinner（重置到首项），
                // 这里在重建后立即恢复为当前映射，避免显示错误（如映射键盘 X 却显示键盘 A）。
                if (actionTypeMatchesMapping(position)) { // 仅匹配时恢复，用户手动切到其它类型不误恢复
                    restoreActionValueSelection() // 恢复动作值选中项
                } // 结束匹配判断
            } // 结束 onItemSelected 回调

            override fun onNothingSelected(parent: AdapterView<*>?) {} // 覆写：无选项选中时回调，空实现
        } // 结束动作类型监听匿名对象

        // ===== 长按动作类型切换监听器 =====
        // 用户切换长按动作类型时，重建长按动作值下拉框的选项
        spinnerLongPressType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { // 设置长按类型下拉框选择监听（语法：object:接口=匿名对象）
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { // 覆写：长按类型切换时回调
                val actionType = longPressTypeIndices[position] // 位置还原为动作类型索引（语法：列表下标访问）
                setupActionValueSpinner(spinnerLongPressValue, tvLongPressLabel, actionType) // 按新类型重建长按动作值选项
                // 与已有长按动作类型一致时恢复选中值（打开对话框/attach 触发时显示当前长按动作）
                if (actionType == actionTypeIndexOf(existingMapping?.longPressAction)) { // 类型匹配判断（语法：== 相等比较）
                    restoreLongPressValueSelection() // 恢复长按动作值选中项
                } // 结束匹配判断
            } // 结束 onItemSelected 回调

            override fun onNothingSelected(parent: AdapterView<*>?) {} // 覆写：无选项选中时回调，空实现
        } // 结束长按动作类型监听匿名对象

        // 添加子命令按钮点击事件
        btnAddSubCommand.setOnClickListener { // 设置「添加子命令」按钮点击监听（语法：setOnClickListener{}=点击监听 lambda）
            if (subCommandSpinners.size < KeyMapping.MAX_SUB_COMMANDS) { // 未达到子命令数量上限时才允许添加
                addSubCommandSpinner(layoutSubCommandList, btnAddSubCommand, subCommandSpinners) // 动态添加一个子命令下拉框
                updateAddSubCommandButton(btnAddSubCommand, subCommandSpinners.size) // 更新添加按钮状态
            } // 结束数量上限判断
        } // 结束「添加子命令」按钮监听 lambda

        // ===== 显示对话框 =====
        AlertDialog.Builder(this) // 构建对话框（语法：AlertDialog.Builder=对话框构建器，链式调用）
            .setView(dialogView) // 设置对话框显示的自定义视图
            .setPositiveButton("保存") { _, _ -> // 设置「保存」按钮及其点击回调（语法：setPositiveButton=设置确认按钮，lambda 参数 _,_=忽略）
                val actionType = spinnerActionType.selectedItemPosition // 读取动作类型下拉框当前选中位置
                if (actionType == 0) { // 若选择「未设置」
                    // 未设置：取消该按键的映射（回退到公共层/无动作）
                    layer.buttonMappings.remove(button) // 从映射表移除该按键的映射
                    Log.i(TAG, "Mapping removed: ${buttonDisplayName(button)}") // 打印日志：映射已移除（语法：字符串模板）
                } else { // 否则（选择了具体动作类型）
                    // 构建动作（actionType-1 映射回 0=键盘/1=鼠标点击/2=鼠标长按/3=切换层/9=切层+按键/10=数字小键盘）
                    val action = buildAction( // 调用构建动作函数（跨行参数）
                        actionType - 1, // 动作类型减去 1 还原为构建函数使用的索引
                        spinnerActionValue.selectedItemPosition, // 动作值下拉框当前选中位置
                        spinnerExtraKey.selectedItemPosition // 附加按键下拉框当前选中位置（仅切层+按键类型生效）
                    ) // 结束 buildAction 调用
                    // 收集子命令（跳过"无"选项）
                    val subCommands = collectSubCommands(subCommandSpinners) // 收集所有已选子命令 KeyCode
                    // 长按动作：位置还原为动作类型索引，0=未设置（不启用长按判定）
                    val longPressType = longPressTypeIndices[spinnerLongPressType.selectedItemPosition] // 长按动作类型索引
                    val longPressAction = if (longPressType == 0) null // 未设置 → 不启用长按判定（语法：if 表达式）
                        else buildAction(longPressType - 1, spinnerLongPressValue.selectedItemPosition) // 否则构建长按动作

                    // 创建新的按键映射（含可选长按动作）
                    val newMapping = KeyMapping(action, subCommands, longPressAction) // 构建新的按键映射对象（语法：三参构造）

                    // 写入当前操作层的 buttonMappings（MutableMap，可直接修改）
                    layer.buttonMappings[button] = newMapping // 将新映射写入当前层的映射表

                    Log.i(TAG, "Mapping saved: ${buttonDisplayName(button)} → ${newMapping.describe()}") // 打印日志：映射已保存（语法：字符串模板）
                } // 结束动作构建 if-else 块

                // 刷新列表显示
                refreshMappingsList() // 刷新映射列表显示

                // 保存到运行时和内部存储
                saveProfile(showToast = true) // 保存配置并显示成功提示
            } // 结束「保存」按钮回调 lambda
            .setNegativeButton("取消", null) // 设置「取消」按钮，无回调直接关闭
            .show() // 显示对话框
    } // 结束 showMappingEditDialog 函数

    /**
     * 显示层名称编辑对话框
     *
     * 使用 EditText 输入新名称，保存后重建 profile。
     *
     * ## 主动弹出软键盘
     * AlertDialog 中的 EditText 默认不会自动弹出软键盘，需要：
     * 1. 对话框显示后请求 EditText 焦点
     * 2. 通过 InputMethodManager.showSoftInput 主动弹出键盘
     * 3. 设置 windowSoftInputMode 让对话框窗口自动调整布局
     */
    private fun showLayerNameEditDialog() { // 私有函数：显示层名称编辑对话框
        val layer = currentLayer ?: return // 取当前层，为空则退出（语法：?:=空值合并）

        val editText = EditText(this).apply { // 创建文本输入框，apply 块内配置属性（语法：apply{}=作用域函数）
            setText(layer.name) // 预填当前层名
            setSelection(layer.name.length) // 光标定位到文本末尾
            hint = "输入操作层名称" // 设置输入框提示文字
            // 单行输入，避免多行模式导致回车键无法确定
            setSingleLine(true) // 设为单行输入模式
            // 请求焦点以便接收输入
            requestFocus() // 请求输入焦点
        } // 结束 apply 块

        val dialog = AlertDialog.Builder(this) // 构建对话框并赋给 dialog 变量（语法：AlertDialog.Builder=对话框构建器）
            .setTitle("编辑层名称") // 设置对话框标题
            .setView(editText) // 设置对话框内容为输入框
            .setPositiveButton("确定") { _, _ -> // 设置「确定」按钮回调（语法：lambda 参数 _,_=忽略）
                val newName = editText.text.toString().trim() // 读取输入文本并去除首尾空格
                if (newName.isNotEmpty()) { // 若新名称非空
                    // 重建操作层（name 是 val，需要 copy）
                    applyLayerNameChange(name = newName) // 应用层名称变更（命名参数）
                } else { // 否则（名称为空）
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show() // 弹出短提示：名称不能为空
                } // 结束名称非空判断
            } // 结束「确定」按钮回调
            .setNegativeButton("取消", null) // 设置「取消」按钮
            .create() // 创建对话框对象

        // 对话框窗口显示时主动弹出软键盘
        dialog.window?.setSoftInputMode( // 设置对话框窗口软键盘模式（语法：?.=安全调用，窗口可能为空）
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE // 模式为始终显示软键盘
        ) // 结束 setSoftInputMode 调用
        dialog.show() // 显示对话框

        // 对话框显示后再请求一次焦点并弹出软键盘（双保险）
        editText.requestFocus() // 再次请求输入框焦点
        val imm = getSystemService(InputMethodManager::class.java) // 获取输入法管理器服务（语法：::class.java=类引用）
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) // 主动弹出软键盘（语法：?.=安全调用，可能为 null）
    } // 结束 showLayerNameEditDialog 函数

    /**
     * 显示切入按键编辑对话框
     *
     * 选择公共层（Common）中用于切换到当前层的按键。选择后写入公共层的
     * [MappedAction.SwitchLayer] 映射，与设置页/悬浮窗中实际的层切换行为一致。
     * 支持选择"无"（清除当前层的切入映射）。
     * 公共层（Common）不允许设置（始终激活，无需切入）。
     */
    private fun showTriggerButtonEditDialog() { // 私有函数：显示切入按键编辑对话框
        val layer = currentLayer ?: return // 取当前层，为空则退出（语法：?:=空值合并）
        val common = currentActionSet?.commonLayer ?: return // 取公共层，为空则退出（语法：?.=安全调用，?:=空值合并）

        // 公共层不能设置切入按键（始终激活，无需切入）
        if (layer === common) { // 若当前层就是公共层（语法：===引用相等比较）
            Toast.makeText(this, "公共层不能设置切入按键", Toast.LENGTH_SHORT).show() // 弹出提示：公共层不能设置切入按键
            return // 提前结束函数
        } // 结束公共层判断

        // 当前切入本层的按键（公共层 SwitchLayer 映射）
        val currentKeys = switchInButtons() // 计算当前切入本层的按键列表

        // 构建按键选项: "无" + 所有 ControllerButton 的显示名称
        // 选项 = 「无」+ 所有手柄按键显示名（语法：listOf=列表工厂，+=列表拼接，map=转换 lambda）
        val buttonOptions = listOf("无") + ControllerButton.values().map { buttonDisplayName(it) }
        val spinner = Spinner(this).apply { // 创建下拉框，apply 块内配置（语法：apply{}=作用域函数）
            adapter = ArrayAdapter( // 设置下拉框适配器（跨行参数）
                this@LayerEditActivity, // 使用外层 Activity 作为上下文（语法：this@类名=指定标签的 this）
                android.R.layout.simple_spinner_item, // 列表项布局：系统简单列表项
                buttonOptions // 数据源：按键选项列表
            ).also { // 创建适配器后继续配置（语法：also{}=作用域函数）
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
            } // 结束 also 块
            // 当前选中第一个切入键（无 = 0）
            val currentPos = if (currentKeys.isEmpty()) 0 // 无切入键则选「无」（位置 0）（语法：if...else 表达式）
            else 1 + ControllerButton.values().indexOf(currentKeys.first()) // 否则选第一个切入键（位置=枚举序号+1，因首项是「无」）
            setSelection(currentPos) // 设置下拉框初始选中项
        } // 结束 apply 块

        AlertDialog.Builder(this) // 构建切入按键选择对话框（语法：AlertDialog.Builder=对话框构建器）
            .setTitle("选择切入按键") // 设置对话框标题
            .setMessage("在公共层中，按住此按键激活「${layer.name}」，松开回到公共层") // 设置对话框说明文字（语法：字符串模板）
            .setView(spinner) // 设置对话框内容为按键下拉框
            .setPositiveButton("确定") { _, _ -> // 设置「确定」按钮回调（语法：lambda 参数 _,_=忽略）
                val pos = spinner.selectedItemPosition // 读取下拉框选中位置
                val chosen = if (pos == 0) null else ControllerButton.values()[pos - 1] // 位置 0 表示「无」（null），否则取对应手柄按键
                setLayerSwitchInKey(layer, chosen) // 写入切入按键映射
            } // 结束「确定」按钮回调
            .setNegativeButton("取消", null) // 设置「取消」按钮
            .show() // 显示对话框
    } // 结束 showTriggerButtonEditDialog 函数

    /**
     * 设置切入当前层的按键（写入公共层的 SwitchLayer 映射）
     *
     * 若选中的按键在公共层中已映射为其他内容（非切换到本层），
     * 会弹出确认框避免误覆盖已有映射。
     *
     * @param layer 当前操作层
     * @param chosen 新的切入按键（null = 清除切入映射）
     */
    private fun setLayerSwitchInKey(layer: OperationLayer, chosen: ControllerButton?) { // 私有函数：设置切入当前层的按键（含覆盖确认）
        val common = currentActionSet?.commonLayer ?: return // 取公共层，为空则退出（语法：?.=安全调用，?:=空值合并）
        val oldName = layer.name // 记录当前层名

        // 若选中的按键已映射为其他内容（非切到本层），先确认再覆盖
        if (chosen != null) { // 若选择了具体按键
            val existing = common.buttonMappings[chosen] // 取公共层中该按键已有的映射
            // 判断已有映射是否为切到本层（语法：as?=安全类型转换，?.=安全调用）
            val isSwitchToThis = (existing?.action as? MappedAction.SwitchLayer)?.layerName == oldName
            if (existing != null && !isSwitchToThis) { // 已有其它映射且非切到本层（语法：&&=逻辑与，!=非空判断）
                AlertDialog.Builder(this) // 构建覆盖确认对话框
                    .setTitle("覆盖映射") // 设置标题：覆盖映射
                    .setMessage( // 设置确认信息（跨行字符串拼接）
                        "公共层按键「${buttonDisplayName(chosen)}」当前映射为" + // 提示信息前半段（语法：字符串模板）
                            "「${existing.describe()}」，确定改为切换到「$oldName」吗？" // 提示信息后半段：原映射与新用途
                    ) // 结束 setMessage 调用
                    .setPositiveButton("确定") { _, _ -> doSetLayerSwitchInKey(layer, chosen) } // 确认后直接写入（语法：lambda 参数 _,_=忽略）
                    .setNegativeButton("取消", null) // 设置取消按钮
                    .show() // 显示确认对话框
                return // 提前返回，等待用户确认
            } // 结束需确认条件判断
        } // 结束 chosen 非空判断

        doSetLayerSwitchInKey(layer, chosen) // 无需确认，直接写入切入映射
    } // 结束 setLayerSwitchInKey 函数

    /**
     * 实际写入切入按键（跳过确认）
     *
     * 先清除公共层中所有指向当前层的旧 SwitchLayer 映射，再写入新按键的映射；
     * 选择"无"时仅清除，不新增。
     *
     * @param layer 当前操作层
     * @param chosen 新的切入按键（null = 清除切入映射）
     */
    private fun doSetLayerSwitchInKey(layer: OperationLayer, chosen: ControllerButton?) { // 私有函数：实际写入切入按键映射（跳过确认）
        val common = currentActionSet?.commonLayer ?: return // 取公共层，为空则退出（语法：?.=安全调用，?:=空值合并）
        val layerName = layer.name // 记录当前层名

        // 清除公共层中所有切到本层的旧映射（保留被选中的按键，稍后统一写入）
        common.buttonMappings.entries.removeAll { (button, mapping) -> // 批量移除旧映射（语法：removeAll{}=按条件移除 lambda，解构 (button, mapping)）
            button != chosen && (mapping.action as? MappedAction.SwitchLayer)?.layerName == layerName // 条件：非新选中键且动作是切到本层（语法：as?=安全类型转换）
        } // 结束 removeAll 条件 lambda

        // 写入新的切入映射
        if (chosen != null) { // 若选择了新切入键
            common.buttonMappings[chosen] = KeyMapping(MappedAction.SwitchLayer(layerName)) // 写入公共层该按键的切层映射
        } // 结束新切入键判断

        saveProfile(showToast = true) // 保存配置并提示（命名参数）
        refreshMappingsList() // 刷新映射列表
        updateLayerInfoButtons() // 更新层信息按钮显示
        Log.i(TAG, "Layer switch-in key set: layer=$layerName key=$chosen") // 打印日志：切入键已设置（语法：字符串模板）
    } // 结束 doSetLayerSwitchInKey 函数

    // ====================================================================
    // 保存方法
    // ====================================================================

    /**
     * 应用操作层名称变更
     *
     * 由于 [OperationLayer.name] 是 `val`（不可变），需要使用 `copy()` 创建新的
     * OperationLayer，并在**当前操作集**内重建层列表、再重建 [ControllerProfile]。
     *
     * ## 重建逻辑
     * - 公共层: 替换当前操作集的 commonLayer
     * - 操作层: 在当前操作集 layers 列表中替换对应项
     *
     * ## 层名同步
     * 层名变化时，同步更新当前操作集所有层（含公共层）中引用旧层名的 SwitchLayer 映射，
     * 否则重命名后切层映射会找不到目标层。
     *
     * @param name 新的层名称
     */
    private fun applyLayerNameChange(name: String) { // 私有函数：应用操作层名称变更
        val oldLayer = currentLayer ?: return // 取当前层，为空则退出（语法：?:=空值合并）
        val actionSet = currentActionSet ?: return // 取当前操作集，为空则退出
        val oldName = oldLayer.name // 记录旧层名

        // 创建新的操作层（copy 保持 buttonMappings 引用不变）
        val newLayer = oldLayer.copy(name = name) // 用 copy 创建新层对象，仅修改名称（语法：copy=数据类拷贝方法）

        // 在当前操作集内重建层列表
        val newCommon: OperationLayer // 声明新公共层变量（语法：显式声明变量类型）
        val newLayers: List<OperationLayer> // 声明新操作层列表变量（语法：List<OperationLayer>=泛型列表类型）
        if (oldLayer === actionSet.commonLayer) { // 若重命名的是公共层（语法：===引用相等比较）
            newCommon = newLayer // 新公共层即为重命名后的层
            newLayers = actionSet.layers // 操作层列表不变
        } else { // 否则（重命名的是普通操作层）
            newCommon = actionSet.commonLayer // 公共层不变
            newLayers = actionSet.layers.map { if (it === oldLayer) newLayer else it } // 替换列表中的旧层为新层（语法：map=转换 lambda，===引用相等）
        } // 结束层重建 if-else 块
        val newActionSet = ActionSet(name = actionSet.name, commonLayer = newCommon, layers = newLayers) // 用新层列表重建操作集对象

        // 层名变化时: 同步更新当前操作集所有层中引用旧层名的 SwitchLayer 映射
        // 否则重命名后 SwitchLayer(oldName) 会找不到目标层，导致层切换失效
        if (oldName != name) { // 若层名确实发生变化
            newActionSet.allLayers.forEach { layer -> // 遍历新操作集所有层（语法：forEach=遍历 lambda）
                layer.buttonMappings.toMutableMap().let { newMap -> // 复制映射表副本以便修改（语法：let{}=作用域函数，newMap 为接收参数）
                    var changed = false // 记录是否有映射被修改（语法：var=可变变量）
                    layer.buttonMappings.forEach { (button, mapping) -> // 遍历每一条映射（语法：forEach lambda，解构 (button, mapping)）
                        val action = mapping.action // 取出映射动作
                        if (action is MappedAction.SwitchLayer && action.layerName == oldName) { // 若是切层动作且目标为旧层名（语法：is=类型判断，&&=逻辑与）
                            newMap[button] = mapping.copy(action = MappedAction.SwitchLayer(name)) // 更新副本中的映射目标为新层名（语法：copy=数据类拷贝）
                            changed = true // 标记发生修改
                        } else if (action is MappedAction.SwitchLayerAndKey && action.layerName == oldName) { // 若是切层+按键动作且目标为旧层名
                            newMap[button] = mapping.copy(action = MappedAction.SwitchLayerAndKey(name, action.keyCode)) // 更新副本中的目标层名（按键不变）（语法：copy=数据类拷贝）
                            changed = true // 标记发生修改
                        } // 结束切层目标判断
                    } // 结束映射遍历
                    if (changed) { // 若确有修改
                        layer.buttonMappings.clear() // 清空原映射表
                        layer.buttonMappings.putAll(newMap) // 写入修改后的映射副本
                    } // 结束修改写入判断
                } // 结束 let 块
            } // 结束所有层遍历
            Log.i(TAG, "Updated SwitchLayer references: $oldName -> $name") // 打印日志：切层引用已更新（语法：字符串模板）
        } // 结束层名变化判断

        // 重建 ControllerProfile（替换当前操作集）
        val newProfile = profile.copy( // 用 copy 重建配置档案（跨行参数）
            actionSets = profile.actionSets.map { if (it === actionSet) newActionSet else it } // 替换配置中当前操作集为新操作集（语法：map=转换 lambda）
        ) // 结束 copy 调用
        // 更新本地配置
        this.profile = newProfile // 用新配置覆盖本地配置引用

        // 更新当前引用（指向新对象）
        currentActionSet = newActionSet // 当前操作集指向新对象
        currentLayer = if (oldLayer === actionSet.commonLayer) { // 若重命名的是公共层则指向新公共层（语法：if...else 表达式）
            newCommon // 当前层为新公共层
        } else { // 否则（普通层）
            newActionSet.findLayer(name) // 在新操作集中按新名查找当前层
        } // 结束当前层引用更新

        // 保存（写文件 + 服务运行时同步）
        saveProfile(showToast = false) // 静默保存配置（不弹提示）

        // 刷新操作层 Spinner（层名可能已改变）
        refreshLayerSpinner(newName = name) // 刷新层下拉框并选中新层名（命名参数）

        // 刷新列表和按钮显示
        refreshMappingsList() // 刷新映射列表
        updateLayerInfoButtons() // 更新层信息按钮显示

        Log.i(TAG, "Layer name updated: $oldName -> $name") // 打印日志：层名已更新（语法：字符串模板）
        Toast.makeText(this, "层信息已保存", Toast.LENGTH_SHORT).show() // 弹出提示：层信息已保存
    } // 结束 applyLayerNameChange 函数

    /**
     * 保存当前配置到文件（服务运行时同步到 SteamInput）
     *
     * - 写回 `steamlike_config.json`（含运行时配置 settings）
     * - 若手柄映射服务在运行，同步 [SteamInput.loadProfile]
     *
     * @param showToast 是否显示保存成功提示
     */
    private fun saveProfile(showToast: Boolean) { // 私有函数：保存当前配置到文件并同步服务运行时
        // 持久化到配置文件（含运行时设置 settings）
        val appConfig = AppConfigStore.load(this) // 读取当前运行时配置（如 WoW 路径等）
        File(filesDir, ConfigManager.CONFIG_FILE_NAME) // 构造配置文件对象（跨行链式调用）
            .writeText(ControllerConfig.toJson(profile, 2, appConfig)) // 把配置序列化为 JSON 写入文件
        // 服务运行时同步（不依赖服务也可正常保存）
        steamInputRef?.let { si -> si.loadProfile(profile) } // 若服务运行则同步加载新配置（语法：?.=安全调用，let{}=lambda，si=显式参数）

        if (showToast) { // 若要求显示提示
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show() // 弹出短提示：配置已保存
        } // 结束提示显示判断
        Log.i(TAG, "Profile saved to internal storage") // 打印日志：配置已保存到内部存储
    } // 结束 saveProfile 函数

    // ====================================================================
    // 操作集管理
    // ====================================================================

    /**
     * 初始化操作集选择 Spinner
     *
     * 从 [profile] 的操作集列表填充选项，选中当前生效的操作集，
     * 并设置选择监听器（用户切换操作集时整体切换其下所有操作层）。
     */
    private fun setupActionSetSpinner() { // 私有函数：初始化操作集选择下拉框
        actionSetNames = profile.actionSets.map { it.name } // 提取所有操作集名称到列表（语法：map=转换 lambda）
        currentActionSet = profile.activeActionSet // 将当前生效的操作集设为当前操作集

        // 创建数组适配器绑定操作集名称（语法：ArrayAdapter=数组适配器，also{}=作用域函数）
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actionSetNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块
        actionSetSpinner.adapter = adapter // 将适配器设置到操作集下拉框

        // 选中当前生效的操作集
        val pos = actionSetNames.indexOf(profile.activeActionSetName).coerceAtLeast(0) // 计算当前操作集在下拉框中的位置，最小为 0（语法：coerceAtLeast=下限约束）
        actionSetSpinner.setSelection(pos) // 设置下拉框选中当前操作集

        actionSetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { // 设置操作集下拉框选择监听：匿名对象实现接口（语法：object:接口=匿名对象）
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { // 覆写：选项被选中时回调（语法：override=覆写，<*>=星投影泛型）
                // 程序化更新时跳过（避免重建列表时误触发切换）
                if (suppressActionSetSpinnerListener) return // 抑制标志为 true 时跳过
                val selected = profile.actionSets.getOrNull(position) ?: return // 按位置取操作集，越界则返回（语法：getOrNull=安全取值，?:=空值合并）
                // 选中项已是当前操作集则跳过：
                // Spinner 重建 adapter 后 onItemSelected 由异步布局触发，此时 suppress 标志
                // 可能已复位，若不判断会产生 switchToActionSet 无限循环（页面不停刷新/掉帧）
                if (selected.name == currentActionSet?.name) return // 选中项与当前操作集同名则跳过（避免无限循环）
                switchToActionSet(selected) // 切换到选中的操作集
            } // 结束 onItemSelected 回调

            override fun onNothingSelected(parent: AdapterView<*>?) {} // 覆写：无选项选中时回调，空实现
        } // 结束操作集监听匿名对象
    } // 结束 setupActionSetSpinner 函数

    /**
     * 切换到指定操作集
     *
     * 更新 [profile] 的 [ControllerProfile.activeActionSetName]、当前操作集引用，
     * 刷新操作集/操作层两个 Spinner，并保存配置。切换后其下所有操作层整体切换。
     *
     * @param actionSet 目标操作集
     */
    private fun switchToActionSet(actionSet: ActionSet) { // 私有函数：切换到指定操作集
        this.profile = profile.copy(activeActionSetName = actionSet.name) // 更新配置中当前生效操作集名（语法：copy=数据类拷贝）
        currentActionSet = actionSet // 更新当前操作集引用

        // 刷新操作集 Spinner 与操作层 Spinner
        refreshActionSetUi() // 刷新两个下拉框的选项与选中项

        saveProfile(showToast = false) // 静默保存配置
        Log.i(TAG, "Action set switched: ${actionSet.name}") // 打印日志：操作集已切换（语法：字符串模板）
    } // 结束 switchToActionSet 函数

    /**
     * 刷新操作集与操作层两个 Spinner
     *
     * 在添加/拷贝/改名/删除/切换操作集后调用：
     * 1. 重建操作集 Spinner 选项并选中当前操作集（抑制监听避免重复切换）
     * 2. 重建操作层 Spinner（默认选中第一层）
     */
    private fun refreshActionSetUi() { // 私有函数：刷新操作集与操作层两个下拉框
        suppressActionSetSpinnerListener = true // 置抑制标志，避免程序化更新触发切换
        actionSetNames = profile.actionSets.map { it.name } // 重新提取操作集名称列表（语法：map=转换 lambda）
        // 重建操作集下拉框适配器（语法：ArrayAdapter=数组适配器，also{}=作用域函数）
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actionSetNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块
        actionSetSpinner.adapter = adapter // 重新绑定适配器到操作集下拉框
        val pos = actionSetNames.indexOf(currentActionSet?.name).coerceAtLeast(0) // 计算当前操作集位置，最小为 0（语法：?.=安全调用，coerceAtLeast=下限约束）
        actionSetSpinner.setSelection(pos) // 选中当前操作集
        suppressActionSetSpinnerListener = false // 复位抑制标志

        // 重建操作层 Spinner（切换后默认选中第一层）
        setupLayerSpinner() // 重建操作层下拉框（默认选中第一层）
    } // 结束 refreshActionSetUi 函数

    /**
     * 显示「添加操作集」对话框
     *
     * 输入新操作集名称，创建全新的默认操作集（Common + Layer1-Layer10，空映射）
     * 并切换到它。名称不能为空、不能与已有操作集重名。
     */
    private fun showAddActionSetDialog() { // 私有函数：显示「添加操作集」对话框
        val editText = EditText(this).apply { // 创建文本输入框，apply 块内配置（语法：apply{}=作用域函数）
            hint = "输入操作集名称" // 设置输入框提示文字
            setSingleLine(true) // 设为单行输入
            requestFocus() // 请求输入焦点
        } // 结束 apply 块

        val dialog = AlertDialog.Builder(this) // 构建对话框（语法：AlertDialog.Builder=对话框构建器）
            .setTitle("添加操作集") // 设置标题
            .setMessage("创建全新的操作集（含公共层 + 10 个操作层，映射为空）") // 设置说明文字
            .setView(editText) // 设置内容为输入框
            .setPositiveButton("确定") { _, _ -> // 设置「确定」按钮回调（语法：lambda 参数 _,_=忽略）
                val name = editText.text.toString().trim() // 读取输入并去除首尾空格
                when { // 条件分支判断（语法：when 无参数多条件分支）
                    name.isEmpty() -> // 名称为空时（语法：->=分支）
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show() // 弹出提示：名称不能为空
                    profile.actionSets.any { it.name == name } -> // 名称已存在时（语法：any=判断是否存在满足条件的元素）
                        Toast.makeText(this, "操作集「$name」已存在", Toast.LENGTH_SHORT).show() // 弹出提示：操作集已存在（语法：字符串模板）
                    else -> { // 其余情况（合法新名称）
                        val newSet = createEmptyActionSet(name) // 创建全新的默认操作集
                        this.profile = profile.copy( // 用 copy 重建配置（跨行参数）
                            actionSets = profile.actionSets + newSet, // 在操作集列表末尾追加新操作集（语法：+=列表拼接）
                            activeActionSetName = name // 将当前生效操作集设为新操作集
                        ) // 结束 copy 调用
                        currentActionSet = newSet // 更新当前操作集引用
                        refreshActionSetUi() // 刷新两个下拉框
                        saveProfile(showToast = false) // 静默保存配置
                        Toast.makeText(this, "已添加并切换到操作集「$name」", Toast.LENGTH_SHORT).show() // 弹出提示：已添加并切换（语法：字符串模板）
                        Log.i(TAG, "Action set added: $name") // 打印日志：操作集已添加
                    } // 结束新增操作集分支
                } // 结束 when 条件分支
            } // 结束「确定」按钮回调
            .setNegativeButton("取消", null) // 设置取消按钮
            .create() // 创建对话框对象

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) // 设置对话框窗口始终显示软键盘（语法：?.=安全调用）
        dialog.show() // 显示对话框
        editText.requestFocus() // 请求输入框焦点
        val imm = getSystemService(InputMethodManager::class.java) // 获取输入法管理器（语法：::class.java=类引用）
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) // 主动弹出软键盘（语法：?.=安全调用）
    } // 结束 showAddActionSetDialog 函数

    /**
     * 显示「拷贝操作集」对话框
     *
     * 输入新名称，深拷贝当前操作集的所有层与按键映射到新操作集并切换。
     * 名称不能为空、不能与已有操作集重名。
     */
    private fun showCopyActionSetDialog() { // 私有函数：显示「拷贝操作集」对话框
        val actionSet = currentActionSet ?: return // 取当前操作集，为空则退出（语法：?:=空值合并）
        val editText = EditText(this).apply { // 创建文本输入框，apply 块内配置（语法：apply{}=作用域函数）
            hint = "输入新操作集名称" // 设置提示文字
            setSingleLine(true) // 设为单行输入
            requestFocus() // 请求输入焦点
        } // 结束 apply 块

        val dialog = AlertDialog.Builder(this) // 构建对话框（语法：AlertDialog.Builder=对话框构建器）
            .setTitle("拷贝操作集") // 设置标题
            .setMessage("将完整复制「${actionSet.name}」的所有层与按键映射，并切换到新操作集") // 设置说明文字（语法：字符串模板）
            .setView(editText) // 设置内容为输入框
            .setPositiveButton("确定") { _, _ -> // 设置「确定」按钮回调（语法：lambda 参数 _,_=忽略）
                val name = editText.text.toString().trim() // 读取输入并去除首尾空格
                when { // 条件分支判断（语法：when 无参数分支）
                    name.isEmpty() -> // 名称为空时
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show() // 弹出提示：名称不能为空
                    profile.actionSets.any { it.name == name } -> // 名称已存在时（语法：any=存在性判断 lambda）
                        Toast.makeText(this, "操作集「$name」已存在", Toast.LENGTH_SHORT).show() // 弹出提示：操作集已存在
                    else -> { // 其余情况（合法名称）
                        val copy = copyActionSet(actionSet, name) // 深拷贝当前操作集到新名称
                        this.profile = profile.copy( // 用 copy 重建配置（跨行参数）
                            actionSets = profile.actionSets + copy, // 追加拷贝后的操作集
                            activeActionSetName = name // 设为当前生效操作集
                        ) // 结束 copy 调用
                        currentActionSet = copy // 更新当前操作集引用
                        refreshActionSetUi() // 刷新两个下拉框
                        saveProfile(showToast = false) // 静默保存配置
                        Toast.makeText(this, "已拷贝并切换到操作集「$name」", Toast.LENGTH_SHORT).show() // 弹出提示：已拷贝并切换
                        Log.i(TAG, "Action set copied: ${actionSet.name} -> $name") // 打印日志：操作集已拷贝
                    } // 结束拷贝分支
                } // 结束 when 条件分支
            } // 结束「确定」按钮回调
            .setNegativeButton("取消", null) // 设置取消按钮
            .create() // 创建对话框对象

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) // 设置对话框窗口始终显示软键盘（语法：?.=安全调用）
        dialog.show() // 显示对话框
        editText.requestFocus() // 请求输入框焦点
        val imm = getSystemService(InputMethodManager::class.java) // 获取输入法管理器（语法：::class.java=类引用）
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) // 主动弹出软键盘（语法：?.=安全调用）
    } // 结束 showCopyActionSetDialog 函数

    /**
     * 显示「重命名操作集」对话框
     *
     * 修改当前操作集名称，保持其下所有层与映射不变。
     */
    private fun showRenameActionSetDialog() { // 私有函数：显示「重命名操作集」对话框
        val actionSet = currentActionSet ?: return // 取当前操作集，为空则退出（语法：?:=空值合并）
        val editText = EditText(this).apply { // 创建文本输入框，apply 块内配置（语法：apply{}=作用域函数）
            setText(actionSet.name) // 预填当前操作集名称
            setSelection(actionSet.name.length) // 光标定位到文本末尾
            hint = "输入新名称" // 设置提示文字
            setSingleLine(true) // 设为单行输入
            requestFocus() // 请求输入焦点
        } // 结束 apply 块

        val dialog = AlertDialog.Builder(this) // 构建对话框（语法：AlertDialog.Builder=对话框构建器）
            .setTitle("重命名操作集") // 设置标题
            .setView(editText) // 设置内容为输入框
            .setPositiveButton("确定") { _, _ -> // 设置「确定」按钮回调（语法：lambda 参数 _,_=忽略）
                val name = editText.text.toString().trim() // 读取输入并去除首尾空格
                when { // 条件分支判断（语法：when 无参数分支）
                    name.isEmpty() -> // 名称为空时
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show() // 弹出提示：名称不能为空
                    name != actionSet.name && profile.actionSets.any { it.name == name } -> // 名称变化且已被其它操作集占用时（语法：&&=逻辑与，any=存在性判断）
                        Toast.makeText(this, "操作集「$name」已存在", Toast.LENGTH_SHORT).show() // 弹出提示：操作集已存在
                    name == actionSet.name -> { // 名称未变化时
                        // 名称未变化，无需处理
                    } // 结束名称未变化分支
                    else -> { // 其余情况（合法新名称）
                        val renamed = actionSet.copy(name = name) // 用 copy 创建改名后的操作集（语法：copy=数据类拷贝）
                        this.profile = profile.copy( // 用 copy 重建配置（跨行参数）
                            // 替换原操作集为新操作集（语法：map=转换 lambda，===引用相等）
                            actionSets = profile.actionSets.map { if (it === actionSet) renamed else it },
                            activeActionSetName = name // 更新当前生效操作集名
                        ) // 结束 copy 调用
                        currentActionSet = renamed // 更新当前操作集引用
                        refreshActionSetUi() // 刷新两个下拉框
                        saveProfile(showToast = false) // 静默保存配置
                        Toast.makeText(this, "操作集已重命名为「$name」", Toast.LENGTH_SHORT).show() // 弹出提示：已重命名
                        Log.i(TAG, "Action set renamed: ${actionSet.name} -> $name") // 打印日志：操作集已重命名
                    } // 结束重命名分支
                } // 结束 when 条件分支
            } // 结束「确定」按钮回调
            .setNegativeButton("取消", null) // 设置取消按钮
            .create() // 创建对话框对象

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) // 设置对话框窗口始终显示软键盘（语法：?.=安全调用）
        dialog.show() // 显示对话框
        editText.requestFocus() // 请求输入框焦点
        val imm = getSystemService(InputMethodManager::class.java) // 获取输入法管理器（语法：::class.java=类引用）
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) // 主动弹出软键盘（语法：?.=安全调用）
    } // 结束 showRenameActionSetDialog 函数

    /**
     * 显示「删除操作集」确认对话框
     *
     * 至少保留 1 个操作集。删除后切换到剩余的第一个操作集。
     */
    private fun confirmDeleteActionSet() { // 私有函数：显示「删除操作集」确认对话框
        val actionSet = currentActionSet ?: return // 取当前操作集，为空则退出（语法：?:=空值合并）
        if (profile.actionSets.size <= 1) { // 若只剩一个操作集则不能删除
            Toast.makeText(this, "至少需要保留一个操作集", Toast.LENGTH_SHORT).show() // 弹出提示：至少保留一个操作集
            return // 提前返回
        } // 结束最少保留数量判断

        AlertDialog.Builder(this) // 构建删除确认对话框（语法：AlertDialog.Builder=对话框构建器）
            .setTitle("删除操作集") // 设置标题
            .setMessage("确定删除操作集「${actionSet.name}」吗？其下所有操作层与按键映射将一并删除。") // 设置确认信息（语法：字符串模板）
            .setPositiveButton("删除") { _, _ -> // 设置「删除」按钮回调（语法：lambda 参数 _,_=忽略）
                val remaining = profile.actionSets.filterNot { it === actionSet } // 过滤掉被删除的操作集（语法：filterNot=取不满足条件的元素）
                val next = remaining.first() // 取剩余的第一个操作集作为切换目标
                this.profile = profile.copy(actionSets = remaining, activeActionSetName = next.name) // 更新配置：移除被删操作集并切换生效集
                currentActionSet = next // 更新当前操作集引用
                refreshActionSetUi() // 刷新两个下拉框
                saveProfile(showToast = false) // 静默保存配置
                Toast.makeText(this, "已删除操作集「${actionSet.name}」", Toast.LENGTH_SHORT).show() // 弹出提示：已删除
                Log.i(TAG, "Action set deleted: ${actionSet.name}") // 打印日志：操作集已删除
            } // 结束「删除」按钮回调
            .setNegativeButton("取消", null) // 设置取消按钮
            .show() // 显示对话框
    } // 结束 confirmDeleteActionSet 函数

    /**
     * 创建全新的默认操作集（Common + 10 个空操作层）
     *
     * 新操作集的层与按键映射均为空，由用户自行配置。
     *
     * @param name 操作集名称
     * @return 新的操作集
     */
    private fun createEmptyActionSet(name: String): ActionSet { // 私有函数：创建全新的默认操作集并返回
        val common = OperationLayer(name = "Common") // 创建公共层，名称为 "Common"
        // 创建 1..MAX_LAYERS 个空操作层，名称为 Layer1..LayerN（语法：map=转换 lambda，字符串模板）
        val layers = (1..ControllerProfile.MAX_LAYERS).map { OperationLayer(name = "Layer$it") }
        return ActionSet(name = name, commonLayer = common, layers = layers) // 组装并返回新操作集（含公共层 + 操作层）
    } // 结束 createEmptyActionSet 函数

    /**
     * 深拷贝操作集到新名称
     *
     * 复制公共层与所有操作层，每层的按键映射表都创建新的 Map 实例，
     * 避免两个操作集共享底层映射导致互相影响。
     *
     * @param actionSet 源操作集
     * @param newName 新操作集名称
     * @return 拷贝后的操作集
     */
    private fun copyActionSet(actionSet: ActionSet, newName: String): ActionSet { // 私有函数：深拷贝操作集到新名称
        fun copyLayer(layer: OperationLayer): OperationLayer { // 局部函数：深拷贝单个操作层（语法：函数内嵌套函数声明）
            val newMap = LinkedHashMap<ControllerButton, KeyMapping>() // 创建新的有序映射表（语法：LinkedHashMap=保持插入顺序的哈希表）
            newMap.putAll(layer.buttonMappings) // 把原层映射内容复制到新映射表
            return OperationLayer(name = layer.name, buttonMappings = newMap) // 用新映射表创建独立的新操作层
        } // 结束 copyLayer 局部函数
        return ActionSet( // 组装拷贝后的操作集（跨行参数）
            name = newName, // 新操作集名称
            commonLayer = copyLayer(actionSet.commonLayer), // 深拷贝公共层
            layers = actionSet.layers.map { copyLayer(it) } // 深拷贝所有操作层（语法：map=转换 lambda）
        ) // 结束 ActionSet 构造调用
    } // 结束 copyActionSet 函数

    // ====================================================================
    // 辅助方法
    // ====================================================================

    /**
     * 刷新操作层 Spinner
     *
     * 当层名发生变化时，重建 Spinner 的选项列表并选中新的层名。
     * 使用 [suppressLayerSpinnerListener] 阻止程序化更新触发监听器。
     *
     * @param newName 新选中的层名
     */
    private fun refreshLayerSpinner(newName: String) { // 私有函数：刷新操作层下拉框
        val profile = this.profile // 读取本地配置引用
        suppressLayerSpinnerListener = true // 置抑制标志，避免程序化更新触发监听

        // 重建层名列表
        layerNames = profile.allLayers.map { it.name } // 重新提取层名列表（语法：map=转换 lambda）

        // 创建新适配器
        // 重建层下拉框适配器（语法：ArrayAdapter=数组适配器，also{}=作用域函数）
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, layerNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块
        layerSpinner.adapter = adapter // 重新绑定适配器到层下拉框

        // 选中新的层名
        val pos = layerNames.indexOf(newName).coerceAtLeast(0) // 计算新层名位置，最小为 0（语法：coerceAtLeast=下限约束）
        layerSpinner.setSelection(pos) // 选中新层名

        suppressLayerSpinnerListener = false // 复位抑制标志
    } // 结束 refreshLayerSpinner 函数

    /**
     * 将动作对象转换为对话框动作类型索引（短按与长按类型下拉框共用）
     *
     * 索引: 0=未设置, 1=键盘按键, 2=鼠标点击, 3=鼠标长按, 4=切换操作层, 5=滚轮上滚,
     *       6=滚轮下滚, 7=切换悬浮窗, 8=切换键盘, 9=切换捕获, 10=切换层+按键,
     *       11=数字小键盘, 12=鼠标长按交换
     */
    private fun actionTypeIndexOf(action: MappedAction?): Int = when (action) { // 动作 → 类型索引（语法：表达式体函数 + when）
        is MappedAction.KeyboardKey -> 1 // 键盘按键 → 类型 1（语法：is=类型判断分支）
        is MappedAction.MouseClick -> 2 // 鼠标点击 → 类型 2
        is MappedAction.MouseToggle -> 3 // 鼠标长按 → 类型 3
        is MappedAction.SwitchLayer -> 4 // 切换层 → 类型 4
        is MappedAction.MouseScrollUp -> 5 // 滚轮上滚 → 类型 5
        is MappedAction.MouseScrollDown -> 6 // 滚轮下滚 → 类型 6
        is MappedAction.ToggleOverlay -> 7 // 切换悬浮窗 → 类型 7
        is MappedAction.ToggleKeyboard -> 8 // 切换键盘 → 类型 8
        is MappedAction.ToggleCapture -> 9 // 切换捕获 → 类型 9
        is MappedAction.SwitchLayerAndKey -> 10 // 切层+按键 → 类型 10
        is MappedAction.NumpadKey -> 11 // 数字小键盘 → 类型 11
        is MappedAction.MouseSwapToggle -> 12 // 鼠标长按交换 → 类型 12
        is MappedAction.MouseReleaseAll -> 13 // 释放鼠标长按 → 类型 13
        is MappedAction.MouseClickSequence -> 14 // 交互键 → 类型 14
        is MappedAction.MouseMove, is MappedAction.LookAround -> 0 // 摇杆专用 → 未设置（语法：多类型分支）
        null -> 0 // 未映射 → 未设置
    } // 结束 when 表达式

    /**
     * 根据动作类型设置动作值 Spinner 的选项
     *
     * - 未设置 (type=0): 显示占位选项（保存时取消映射）
     * - 键盘按键 (type=1): 显示键盘按键列表（字母/数字/功能键/修饰键/符号键等）
     * - 鼠标点击 (type=2): 显示 左键/中键/右键
     * - 鼠标长按 (type=3): 显示 左键/中键/右键
     * - 切换操作层 (type=4): 显示所有操作层名称
     * - 数字小键盘 (type=11): 显示小键盘数字键组
     *
     * @param spinner 动作值 Spinner
     * @param label 动作值标签（根据类型更新文字）
     * @param actionType 动作类型 (0=未设置, 1=键盘, 2=鼠标点击, 3=鼠标长按, 4=切换层, 10=切层+按键, 11=数字小键盘)
     */
    private fun setupActionValueSpinner(spinner: Spinner, label: TextView, actionType: Int) { // 私有函数：根据动作类型设置动作值下拉框选项
        val options: List<String> = when (actionType) { // 按动作类型计算选项列表（语法：when=分支表达式，显式声明 List<String> 类型）
            0 -> {  // 未设置（取消映射） // 未设置类型分支：保存时取消映射
                label.text = "未设置（保存后取消该按键映射）" // 更新动作标签文字
                listOf("（无）") // 返回占位选项「（无）」（语法：listOf=列表工厂）
            } // 结束未设置分支
            1 -> {  // 键盘按键 // 键盘按键类型分支
                label.text = "选择按键:" // 更新动作标签文字
                keyboardKeyOptions.map { it.first } // 返回所有键盘按键显示名（语法：map=转换 lambda）
            } // 结束键盘按键分支
            2 -> {  // 鼠标点击 // 鼠标点击类型分支
                label.text = "选择鼠标按键:" // 更新动作标签文字
                mouseButtonOptions.map { it.first } // 返回所有鼠标按键显示名（语法：map=转换 lambda）
            } // 结束鼠标点击分支
            3 -> {  // 鼠标长按 // 鼠标长按类型分支
                label.text = "选择鼠标按键:" // 更新动作标签文字
                mouseButtonOptions.map { it.first } // 返回所有鼠标按键显示名
            } // 结束鼠标长按分支
            4 -> {  // 切换操作层 // 切换操作层类型分支
                label.text = "选择目标层:" // 更新动作标签文字
                layerNames // 返回所有层名称作为选项
            } // 结束切换层分支
            10 -> {  // 切换层+按键 // 切层+按键类型分支
                label.text = "选择目标层:" // 更新动作标签文字
                layerNames // 返回所有层名称作为选项
            } // 结束切层+按键分支
            11 -> {  // 数字小键盘 // 数字小键盘类型分支
                label.text = "选择小键盘按键:" // 更新动作标签文字
                numpadKeyOptions.map { it.first } // 返回所有小键盘按键显示名
            } // 结束数字小键盘分支
            5 -> {  // 滚轮上滚 // 滚轮上滚类型分支
                label.text = "按下时发送滚轮上滚事件" // 更新动作标签文字
                listOf("（滚轮上滚）") // 返回占位选项
            } // 结束滚轮上滚分支
            6 -> {  // 滚轮下滚 // 滚轮下滚类型分支
                label.text = "按下时发送滚轮下滚事件" // 更新动作标签文字
                listOf("（滚轮下滚）") // 返回占位选项
            } // 结束滚轮下滚分支
            7 -> {  // 切换悬浮窗 // 切换悬浮窗类型分支
                label.text = "按下时切换悬浮窗显示/隐藏" // 更新动作标签文字
                listOf("（切换悬浮窗）") // 返回占位选项
            } // 结束切换悬浮窗分支
            8 -> {  // 切换键盘 // 切换键盘类型分支
                label.text = "按下时显示/隐藏安卓系统键盘" // 更新动作标签文字
                listOf("（切换键盘）") // 返回占位选项
            } // 结束切换键盘分支
            9 -> {  // 切换捕获 // 切换捕获类型分支
                label.text = "按下时暂停/恢复手柄事件捕获" // 更新动作标签文字
                listOf("（切换捕获）") // 返回占位选项
            } // 结束切换捕获分支
            12 -> {  // 鼠标长按交换 // 鼠标长按交换类型分支
                label.text = "按下时交换左右键的长按锁存状态" // 更新动作标签文字
                listOf("（鼠标长按交换）") // 返回占位选项
            } // 结束鼠标长按交换分支
            13 -> {  // 释放鼠标长按 // 释放鼠标长按类型分支
                label.text = "按下时释放所有鼠标长按锁存" // 更新动作标签文字
                listOf("（释放鼠标长按）") // 返回占位选项
            } // 结束释放鼠标长按分支
            14 -> {  // 交互键 // 交互键类型分支
                label.text = "按下时释放锁存→左键点击→右键点击→恢复锁存" // 更新动作标签文字
                listOf("（交互键）") // 返回占位选项
            } // 结束交互键分支
            else -> emptyList() // 其它类型返回空列表（语法：else=默认分支）
        } // 结束选项列表 when 表达式

        // 设置动作值下拉框适配器（语法：ArrayAdapter=数组适配器，also{}=作用域函数）
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
        } // 结束 also 块
        // 重置选中位置为首项：Spinner 更换 adapter 后会保留旧列表的选中索引，
        // 若旧索引超出新列表长度（如键盘按键长列表索引 74 → 数字小键盘仅 11 项）布局时会越界崩溃
        spinner.setSelection(0) // 重置下拉框选中位置为首项（语法：setSelection=设置下拉框选中项）
    } // 结束 setupActionValueSpinner 函数

    /**
     * 根据动作类型和选中位置构建 [MappedAction]
     *
     * @param actionType 动作类型 (0=键盘, 1=鼠标点击, 2=鼠标长按, 3=切换层, 9=切层+按键, 10=数字小键盘)
     * @param valuePosition 动作值 Spinner 的选中位置
     * @param extraValuePosition 附加按键 Spinner 的选中位置（仅切层+按键类型使用，默认 0）
     * @return 对应的 MappedAction 实例
     */
    private fun buildAction(actionType: Int, valuePosition: Int, extraValuePosition: Int = 0): MappedAction { // 私有函数：根据动作类型和选中位置构建动作对象
        return when (actionType) { // 按动作类型返回对应动作（语法：when=分支表达式）
            0 -> {  // 键盘按键 // 键盘按键动作
                MappedAction.KeyboardKey(keyboardKeyOptions[valuePosition].second) // 用所选 KeyCode 构建键盘按键动作
            } // 结束键盘动作分支
            1 -> {  // 鼠标点击 // 鼠标点击动作
                MappedAction.MouseClick(mouseButtonOptions[valuePosition].second) // 用所选鼠标按键构建鼠标点击动作
            } // 结束鼠标点击分支
            2 -> {  // 鼠标长按 // 鼠标长按动作
                MappedAction.MouseToggle(mouseButtonOptions[valuePosition].second) // 用所选鼠标按键构建鼠标长按动作
            } // 结束鼠标长按分支
            3 -> {  // 切换操作层 // 切换操作层动作
                MappedAction.SwitchLayer(layerNames[valuePosition]) // 用所选目标层名构建切层动作
            } // 结束切层动作分支
            9 -> {  // 切换层+按键 // 切层+按键动作
                MappedAction.SwitchLayerAndKey( // 构建切层+按键动作（跨行参数）
                    layerNames[valuePosition], // 目标层名（动作值下拉框选中项）
                    keyboardKeyOptions[extraValuePosition].second // 同时按下的键盘键码（附加按键下拉框选中项）
                ) // 结束 SwitchLayerAndKey 构造
            } // 结束切层+按键分支
            10 -> MappedAction.NumpadKey(numpadKeyOptions[valuePosition].second) // 数字小键盘动作：用所选小键盘键码构建（语法：下标访问列表元素）
            11 -> MappedAction.MouseSwapToggle // 鼠标长按交换动作：交换左右键长按锁存状态
            12 -> MappedAction.MouseReleaseAll // 释放鼠标长按动作：解除全部鼠标长按锁存
            13 -> MappedAction.MouseClickSequence // 交互键动作：临时释放锁存后左右键依次点击
            4 -> MappedAction.MouseScrollUp   // 滚轮上滚 // 滚轮上滚动作
            5 -> MappedAction.MouseScrollDown  // 滚轮下滚 // 滚轮下滚动作
            6 -> MappedAction.ToggleOverlay   // 切换悬浮窗 // 切换悬浮窗动作
            7 -> MappedAction.ToggleKeyboard  // 切换键盘 // 切换键盘动作
            8 -> MappedAction.ToggleCapture   // 切换捕获 // 切换捕获动作
            else -> MappedAction.KeyboardKey(keyboardKeyOptions[0].second) // 兜底：默认返回键盘按键动作（用第一个键）
        } // 结束 buildAction 的 when 表达式
    } // 结束 buildAction 函数

    /**
     * 添加一个子命令选择行
     *
     * 每行包含:
     * - 一个 Spinner（选项: "无" + 键盘按键列表）
     * - 一个删除按钮（✕，移除该子命令行）
     *
     * @param container 子命令行的父容器
     * @param addButton 添加子命令按钮（用于更新其状态）
     * @param spinners 子命令 Spinner 列表（用于跟踪所有行）
     * @return 新创建的 Spinner
     */
    private fun addSubCommandSpinner( // 私有函数：添加一个子命令选择行（跨行参数列表）
        container: LinearLayout, // 参数：子命令行父容器
        addButton: Button, // 参数：「添加子命令」按钮（用于更新其状态）
        spinners: MutableList<Spinner> // 参数：子命令下拉框列表（用于跟踪所有行）
    ): Spinner { // 函数返回新创建的 Spinner
        // 创建水平行容器
        val row = LinearLayout(this).apply { // 创建水平布局行，apply 块内配置（语法：apply{}=作用域函数）
            orientation = LinearLayout.HORIZONTAL // 设为水平方向排列
            gravity = Gravity.CENTER_VERTICAL // 子控件垂直居中对齐
            setPadding(0, 4, 0, 4) // 设置上下内边距 4 像素
        } // 结束 apply 块

        // 创建子命令 Spinner
        val spinner = Spinner(this).apply { // 创建子命令下拉框，apply 块内配置（语法：apply{}=作用域函数）
            // 选项: "无" + 键盘按键列表
            val options = listOf("无") + keyboardKeyOptions.map { it.first } // 选项 = 「无」+ 所有键盘按键名（语法：列表拼接 +，map=转换 lambda）
            adapter = ArrayAdapter( // 设置下拉框适配器（跨行参数）
                this@LayerEditActivity, // 使用外层 Activity 作为上下文（语法：this@类名=指定标签的 this）
                android.R.layout.simple_spinner_item, // 列表项布局：系统简单列表项
                options // 数据源：按键选项列表
            ).also { // 创建适配器后继续配置（语法：also{}=作用域函数）
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项的布局资源
            } // 结束 also 块
            // 占据剩余空间
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) // 布局参数：宽度 0 加权重 1 占满剩余空间，高度自适应
        } // 结束 apply 块
        row.addView(spinner) // 把子命令下拉框加入行容器

        // 创建删除按钮
        val removeButton = Button(this).apply { // 创建删除按钮，apply 块内配置（语法：apply{}=作用域函数）
            text = "✕" // 按钮文字为 ✕
            setOnClickListener { // 设置点击监听（语法：setOnClickListener{}=点击监听 lambda）
                spinners.remove(spinner) // 从跟踪列表移除该子命令
                container.removeView(row) // 从容器移除整行
                updateAddSubCommandButton(addButton, spinners.size) // 更新「添加子命令」按钮状态
            } // 结束删除按钮点击监听
        } // 结束 apply 块
        row.addView(removeButton) // 把删除按钮加入行容器

        // 添加到容器
        container.addView(row) // 把整行加入子命令列表容器
        spinners.add(spinner) // 把下拉框加入跟踪列表

        return spinner // 返回新创建的子命令下拉框
    } // 结束 addSubCommandSpinner 函数

    /**
     * 更新"添加子命令"按钮的状态和文字
     *
     * @param button 添加子命令按钮
     * @param count 当前子命令数量
     */
    private fun updateAddSubCommandButton(button: Button, count: Int) { // 私有函数：更新「添加子命令」按钮状态和文字
        if (count >= KeyMapping.MAX_SUB_COMMANDS) { // 若子命令数已达上限
            // 已满，禁用按钮
            button.isEnabled = false // 禁用添加按钮
            button.text = "已满 ${KeyMapping.MAX_SUB_COMMANDS} 个" // 显示「已满 N 个」（语法：字符串模板）
        } else { // 否则（未满）
            // 未满，启用按钮并显示计数
            button.isEnabled = true // 启用添加按钮
            button.text = "+ 添加子命令 ($count/${KeyMapping.MAX_SUB_COMMANDS})" // 显示「+ 添加子命令 (当前/上限)」（语法：字符串模板）
        } // 结束数量判断 if-else 块
    } // 结束 updateAddSubCommandButton 函数

    /**
     * 从子命令 Spinner 列表收集已选择的 KeyCode
     *
     * 跳过选中"无"（位置 0）的 Spinner，只收集有效的键盘按键 KeyCode。
     *
     * @param spinners 子命令 Spinner 列表
     * @return 已选择的 KeyCode 列表（最多3个）
     */
    private fun collectSubCommands(spinners: List<Spinner>): List<Int> { // 私有函数：从子命令下拉框列表收集已选 KeyCode
        return spinners.mapNotNull { spinner -> // 遍历并过滤空值（语法：mapNotNull=映射并剔除 null 的 lambda）
            val pos = spinner.selectedItemPosition // 读取下拉框选中位置
            if (pos > 0) { // 位置大于 0 表示选了具体按键
                // pos=0 是"无"，pos>=1 对应 keyboardKeyOptions[pos-1]
                keyboardKeyOptions[pos - 1].second // 返回对应按键的 KeyCode
            } else { // 否则（选了「无」）
                null  // 跳过"无" // 返回 null 以便跳过该下拉框
            } // 结束位置判断
        } // 结束 mapNotNull 遍历
    } // 结束 collectSubCommands 函数
} // 结束 LayerEditActivity 类

/**
 * 全展开 ListView（用于 ScrollView 内）
 *
 * 操作层设置页面内容（操作集区域 + 操作层区域 + 按键映射列表）可能超出屏幕高度，
 * 若用普通 ListView 以 layout_weight 占剩余空间，会被压缩到很小甚至无法滚动。
 * 本类重写 onMeasure，将高度测量改为 AT_MOST + 极大上限，使 ListView 一次性展开
 * 全部行，由外层 ScrollView 统一接管滚动，避免嵌套滚动冲突。
 */
class NonScrollListView @JvmOverloads constructor( // 定义全展开 ListView 类（语法：class=类声明，@JvmOverloads=生成多个重载构造函数注解，constructor=主构造函数）
    context: Context, // 构造函数参数：上下文
    attrs: AttributeSet? = null, // 构造函数参数：XML 属性集，可空且默认 null
    defStyleAttr: Int = 0 // 构造函数参数：默认样式属性，默认 0
) : ListView(context, attrs, defStyleAttr) { // 继承 ListView 并调用父类构造（语法：class X:Y()=继承基类）

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { // 覆写测量方法：自定义高度测量（语法：override=覆写）
        val expandSpec = View.MeasureSpec.makeMeasureSpec( // 构建扩展高度测量规格（跨行参数）
            Int.MAX_VALUE shr 2, // 高度上限取极大值（右移 2 位防溢出）（语法：shr=按位右移运算）
            View.MeasureSpec.AT_MOST // 测量模式为 AT_MOST（不超过上限）
        ) // 结束 makeMeasureSpec 调用
        super.onMeasure(widthMeasureSpec, expandSpec) // 用扩展高度规格调用父类测量，使列表一次性展开
    } // 结束 onMeasure 函数
} // 结束 NonScrollListView 类
