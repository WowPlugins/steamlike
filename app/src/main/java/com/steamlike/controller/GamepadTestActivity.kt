package com.steamlike.controller // 声明包名，与本文件所在目录对应

import android.os.Build // 导入 Build 类，用于判断系统版本
import android.os.Bundle // 导入 Bundle 类，用于传递 Activity 状态数据
import android.view.MenuItem // 导入 MenuItem 类，菜单项类型
import android.view.MotionEvent // 导入 MotionEvent 类，触摸事件类型
import android.view.WindowManager // 导入 WindowManager 类，窗口布局参数
import android.widget.Button // 导入 Button 按钮控件
import android.widget.GridLayout // 导入 GridLayout 网格布局
import android.widget.LinearLayout // 导入 LinearLayout 线性布局
import android.widget.TextView // 导入 TextView 文本控件
import android.widget.Toast // 导入 Toast 提示控件
import androidx.appcompat.app.AppCompatActivity // 导入 AppCompatActivity 兼容基类
import com.steamlike.controller.core.ControllerButton // 导入手柄按键枚举
import com.steamlike.controller.core.SteamInput // 导入 SteamInput 主控制器类
import com.steamlike.controller.core.SteamInputHolder // 导入运行中 SteamInput 的全局访问点
import com.steamlike.controller.ui.UiKit // 导入 UI 工具类

/**
 * 手柄按键测试 Activity（调试用）
 *
 * ## 用途
 * 模拟器没有真实手柄，此 Activity 通过点击按钮模拟手柄按键的按下/松开事件，
 * 直接触发 [SteamInput.handleButtonEvent]，方便测试操作层切换和按键映射。
 *
 * ## 交互
 * - 触摸按钮 = 按下事件 (isPressed=true)
 * - 松开按钮 = 松开事件 (isPressed=false)
 * - 顶部显示当前激活层名（实时更新）
 *
 * ## 搭配 Windows 测试程序
 * 启动此 Activity 后，可同时在 Windows 端运行 inputbridge_test.exe：
 * ```
 * adb forward tcp:27015 tcp:27015
 * inputbridge_test.exe
 * ```
 * 即可看到 Android 端模拟按键产生的映射输出消息。
 */
class GamepadTestActivity : AppCompatActivity() { // 声明手柄测试 Activity，继承 AppCompatActivity（语法：class 声明类并继承）

    companion object { // 伴生对象，存放类级成员（语法：companion object 伴生对象）
        private const val TAG = "GamepadTestActivity" // 日志标签常量（语法：const val 编译期常量）
    } // 结束伴生对象

    private var steamInput: SteamInput? = null // 主控制器引用，可为空（语法：var 可变变量 + ? 可空类型）
    private var statusText: TextView? = null // 状态显示文本控件，可为空

    override fun onCreate(savedInstanceState: Bundle?) { // 覆写 Activity 创建回调（语法：override 覆写 + fun 函数声明）
        super.onCreate(savedInstanceState) // 调用父类创建逻辑（语法：super 调用父类方法）

        // 挖孔屏横屏兜底设置（主方案是自定义标题栏，见 UiKit.titleBar）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // 系统版本 >= Android 9 时执行（语法：if 条件分支）
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES // 布局延伸到挖孔屏短边
        } // 结束挖孔屏设置 if

        steamInput = SteamInputHolder.current // 取运行中的控制器（服务未启动时为 null）
        if (steamInput == null) { // 服务未启动或未初始化时进入（语法：if 判断 + == 比较）
            Toast.makeText(this, "服务未启动或初始化未完成", Toast.LENGTH_LONG).show() // 弹出错误提示
            finish() // 关闭当前界面
            return // 提前返回（语法：return）
        } // 结束空值判断

        val root = LinearLayout(this).apply { // 创建根线性布局并进入 apply 作用域（语法：lambda 接收者 apply）
            orientation = LinearLayout.VERTICAL // 垂直方向排列
            setPadding(16, 16, 16, 16) // 设置四周 16px 内边距
            background = UiKit.rounded(this@GamepadTestActivity, 0xF21C1C1C.toInt(), 0) // 设置深色圆角背景（语法：this@GamepadTestActivity 限定外层 Activity）
            // Android 15+ 强制 edge-to-edge：让根布局自动按状态栏高度加 padding，避免标题重叠
            fitsSystemWindows = true // 布局自动避开系统栏区域
        } // 结束根布局 apply 块

        // 自定义标题栏（替代系统 ActionBar：系统 ActionBar 挖孔屏横屏时避让挖孔不贴边）
        root.addView(UiKit.titleBar(this, "手柄按键测试") { finish() }) // 添加标题栏，点击返回按钮时关闭界面（语法：lambda 回调）

        // 状态显示
        val statusLabel = TextView(this).apply { // 创建状态标签文本并进入 apply 作用域
            text = "当前激活层:" // 设置标签文字
            textSize = 14f // 字号 14sp
            setTextColor(0xFFDDDDDD.toInt()) // 浅灰文字颜色
        } // 结束状态标签 apply 块
        root.addView(statusLabel) // 把状态标签加入根布局

        statusText = TextView(this).apply { // 创建当前层名文本并进入 apply 作用域
            textSize = 16f // 字号 16sp
            setPadding(0, 8, 0, 16) // 设置上下内边距
            setTextColor(0xFF4CAF50.toInt()) // 绿色文字
            text = steamInput?.activeLayerName ?: "公共层" // 显示当前激活层，无则显示公共层（语法：?. 安全调用 + ?: 空值合并）
        } // 结束当前层文本 apply 块
        root.addView(statusText) // 把当前层文本加入根布局

        // 监听层变化（叠加在原有回调之上，不破坏 ControllerOverlayService 的层切换监听）
        savedLayerCallback = steamInput?.onLayerChanged // 先保存原有的层变化回调（语法：?. 安全调用）
        steamInput?.onLayerChanged = { layerName -> // 覆写层变化回调（语法：lambda 回调，参数 layerName）
            runOnUiThread { statusText?.text = layerName } // 在主线程更新当前层文本（语法：lambda + runOnUiThread）
            savedLayerCallback?.invoke(layerName) // 继续调用原回调（语法：?.invoke 调用保存的函数）
        } // 结束层变化回调 lambda

        // 说明
        val hint = TextView(this).apply { // 创建提示文本并进入 apply 作用域
            text = "提示: 按住按钮 = 持续按下，松开 = 释放\n映射的消息会发送到 Windows 测试程序" // 设置提示文字
            textSize = 12f // 字号 12sp
            setPadding(0, 0, 0, 16) // 设置底部内边距
            setTextColor(0xFFAAAAAA.toInt()) // 灰色文字
        } // 结束提示文本 apply 块
        root.addView(hint) // 把提示文本加入根布局

        // 按钮网格
        val grid = GridLayout(this).apply { // 创建网格布局并进入 apply 作用域
            columnCount = 4 // 固定 4 列
            rowCount = 5  // 18个按钮 / 4列 = 5行（向上取整） // 固定 5 行
            useDefaultMargins = true // 使用默认间距
            alignmentMode = GridLayout.ALIGN_MARGINS // 按外边距对齐
        } // 结束网格布局 apply 块

        // 所有可模拟的按钮
        val buttons = listOf( // 构建按钮与标签的对应列表（语法：val + listOf 列表）
            ControllerButton.A to "A", // A 键（语法：to 创建键值对）
            ControllerButton.B to "B", // B 键
            ControllerButton.X to "X", // X 键
            ControllerButton.Y to "Y", // Y 键
            ControllerButton.LEFT_SHOULDER to "LB", // 左肩键
            ControllerButton.RIGHT_SHOULDER to "RB", // 右肩键
            ControllerButton.LEFT_TRIGGER_CLICK to "L2", // 左扳机按下
            ControllerButton.RIGHT_TRIGGER_CLICK to "R2", // 右扳机按下
            ControllerButton.LEFT_STICK_CLICK to "L3", // 左摇杆按下
            ControllerButton.RIGHT_STICK_CLICK to "R3", // 右摇杆按下
            ControllerButton.DPAD_UP to "↑", // 方向键上
            ControllerButton.DPAD_DOWN to "↓", // 方向键下
            ControllerButton.DPAD_LEFT to "←", // 方向键左
            ControllerButton.DPAD_RIGHT to "→", // 方向键右
            ControllerButton.MENU to "Menu", // 菜单键
            ControllerButton.OPTIONS to "Options", // 选项键
            ControllerButton.GUIDE to "Guide", // 引导键
            ControllerButton.TOUCHPAD_CLICK to "Touchpad" // 触摸板点击
        ) // 结束按钮列表

        for ((button, label) in buttons) { // 遍历按钮列表并解构键值对（语法：for 循环 + 解构）
            val btn = Button(this).apply { // 创建按钮并进入 apply 作用域
                text = label // 设置按钮文字
                setTextColor(0xFFFFFFFF.toInt()) // 白色文字
                isAllCaps = false // 不强制大写
                // 圆角按钮样式
                background = UiKit.buttonBackground(this@GamepadTestActivity, UiKit.Style.NORMAL) // 应用常规按钮背景
                layoutParams = GridLayout.LayoutParams().apply { // 设置网格布局参数并进入 apply 作用域
                    width = 0 // 宽度为 0，由权重分配
                    height = UiKit.dp(this@GamepadTestActivity, 44) // 高度 44dp
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) // 占满整列（权重 1f）
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED) // 自动分配行
                } // 结束布局参数 apply 块

                // 触摸事件: DOWN=按下, UP=松开
                setOnTouchListener { v, event -> // 设置触摸监听（语法：lambda 回调，参数为视图与事件）
                    when (event.actionMasked) { // 按事件类型分支（语法：when 分支语句）
                        MotionEvent.ACTION_DOWN -> { // 按下事件
                            steamInput?.handleButtonEvent(button, true) // 触发按键按下（语法：?. 安全调用）
                            v.background = UiKit.rounded( // 按下时把按钮背景改为高亮色
                                this@GamepadTestActivity, 0xFF2196F3.toInt(), 10 // 蓝色圆角背景
                            ) // 结束 rounded 调用
                            v.performClick() // 触发无障碍点击
                        } // 结束按下分支
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { // 松开或取消事件
                            steamInput?.handleButtonEvent(button, false) // 触发按键松开
                            v.background = UiKit.buttonBackground( // 恢复常规按钮背景
                                this@GamepadTestActivity, UiKit.Style.NORMAL // 常规样式
                            ) // 结束 buttonBackground 调用
                        } // 结束松开分支
                    } // 结束 when 分支
                    true // 返回 true 表示消费该触摸事件
                } // 结束触摸监听 lambda
            } // 结束按钮 apply 块
            grid.addView(btn) // 把按钮加入网格
        } // 结束 for 循环

        root.addView(grid) // 把网格加入根布局

        // "释放所有" 按钮
        root.addView(UiKit.spacer(this@GamepadTestActivity, 12)) // 添加 12dp 间距
        root.addView(UiKit.button(this@GamepadTestActivity, "释放所有按键", { // 创建“释放所有按键”按钮（语法：lambda 点击回调）
            // 触发所有按钮的松开事件（避免状态残留）
            for ((button, _) in buttons) { // 遍历所有按钮，忽略标签（语法：for 循环 + 解构）
                steamInput?.handleButtonEvent(button, false) // 逐个触发松开事件
            } // 结束 for 循环
            Toast.makeText(this@GamepadTestActivity, "已释放所有按键", Toast.LENGTH_SHORT).show() // 弹出完成提示
        }, UiKit.Style.PRIMARY)) // 按钮使用主色调样式

        // 顶部提示
        val tip = TextView(this).apply { // 创建调试提示文本并进入 apply 作用域
            text = "\nWindows 端调试:\n  adb forward tcp:27015 tcp:27015\n  inputbridge_test.exe" // 设置调试命令提示
            textSize = 11f // 字号 11sp
            setTextColor(0xFF888888.toInt()) // 灰色文字
            setPadding(0, 16, 0, 0) // 设置顶部内边距
        } // 结束提示文本 apply 块
        root.addView(tip) // 把提示文本加入根布局

        setContentView(root) // 设置根布局为界面内容
    } // 结束 onCreate 函数

    override fun onOptionsItemSelected(item: MenuItem): Boolean { // 覆写菜单项选择回调（语法：override 覆写 + 返回类型）
        if (item.itemId == android.R.id.home) { // 点击了返回/主页按钮时进入（语法：if 判断）
            finish() // 关闭当前界面
            return true // 返回已处理
        } // 结束判断
        return super.onOptionsItemSelected(item) // 其他菜单项交给父类处理
    } // 结束 onOptionsItemSelected 函数

    override fun onDestroy() { // 覆写 Activity 销毁回调（语法：override + Activity 生命周期 onDestroy）
        super.onDestroy() // 调用父类销毁逻辑
        // 恢复原始的 onLayerChanged 回调（ControllerOverlayService 的层切换监听）
        if (savedLayerCallback != null) { // 原回调存在时进入（语法：if 判断 + != 非空比较）
            steamInput?.onLayerChanged = savedLayerCallback // 恢复原始回调（语法：?. 安全调用）
        } // 结束判断
    } // 结束 onDestroy 函数

    private var savedLayerCallback: ((String) -> Unit)? = null // 保存的原层变化回调（语法：函数类型可空变量）
} // 结束 GamepadTestActivity 类
