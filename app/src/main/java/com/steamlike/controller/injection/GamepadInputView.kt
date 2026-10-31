package com.steamlike.controller.injection  // 声明包名：注入相关类的包

import android.content.Context  // 导入 Android 上下文类（View 构造参数）
import android.text.InputType  // 导入输入类型常量（设置 IME 输入类型）
import android.util.Log  // 导入日志工具类
import android.view.KeyEvent  // 导入按键事件类
import android.view.MotionEvent  // 导入触摸/手柄摇杆事件类
import android.view.View  // 导入 View 基类（本类继承它）
import android.view.WindowManager  // 导入窗口管理器（添加悬浮窗）
import android.view.WindowManager.LayoutParams  // 导入窗口布局参数类
import android.view.inputmethod.BaseInputConnection  // 导入 IME 输入连接基类
import android.view.inputmethod.EditorInfo  // 导入编辑器信息类（配置输入法）
import android.view.inputmethod.InputConnection  // 导入输入连接接口（键盘通信通道）
import android.graphics.PixelFormat  // 导入像素格式类（窗口透明背景）
import android.os.Build  // 导入系统版本类（版本判断）
import android.view.WindowInsets  // 导入窗口 Insets 类（获取键盘可见性）

/**
 * 透明全屏手柄输入捕获View
 *
 * ## 原理
 * 替代 Shizuku + getevent 方案。通过 WindowManager 添加一个全屏透明的、
 * **不可触摸但可获焦点** 的窗口来接收手柄的 KeyEvent 和 MotionEvent。
 *
 * - `FLAG_NOT_TOUCHABLE`: 触摸事件穿透到下层（Winlator），用户仍可触摸操作 Winlator
 * - 不设 `FLAG_NOT_FOCUSABLE`: 窗口可获焦点，能接收手柄按键/摇杆事件
 * - 全屏透明: 不遮挡 Winlator 画面
 *
 * ## 事件流
 * ```
 * 手柄硬件 → Android InputManager → GamepadInputView（焦点窗口）
 *      ├─ dispatchKeyEvent()        → 按钮按下/释放（A/B/X/Y/LB/RB/D-Pad...）
 *      └─ dispatchGenericMotionEvent() → 摇杆移动/扳机按压（左/右摇杆, LT/RT）
 * ```
 *
 * ## 悬浮窗切换快捷键
 * 手柄 Home 键（KEYCODE_BUTTON_MODE）切换悬浮窗展开/收起/映射列表。
 * 同时支持物理键盘 Ctrl+Shift+X。
 *
 * @param context Context
 */
class GamepadInputView(context: Context) : View(context) {  // 语法：class 声明类；透明全屏手柄输入捕获 View，继承 View

    companion object {  // 语法：companion object 伴生对象（类级静态成员容器）
        private const val TAG = "GamepadInputView"  // 语法：const val 编译期常量；日志标签

        /**
         * 需要经按键事件通道（onImeKey）转发的控制键集合。
         *
         * 字母/数字/符号等可打印键由 commitText/setComposingText 文本通道注入，
         * 若按键事件通道也转发会重复注入（"按一个字母出现两个"）。
         * 只有这些真正的控制键（没有对应文本）才走按键事件通道转发。
         */
        private val CONTROL_KEY_CODES = setOf(  // 语法：val + setOf 集合构造；需要经按键事件通道转发的控制键集合
            KeyEvent.KEYCODE_ENTER,  // 回车键
            KeyEvent.KEYCODE_TAB,  // Tab 键
            KeyEvent.KEYCODE_DPAD_UP,  // 方向上键
            KeyEvent.KEYCODE_DPAD_DOWN,  // 方向下键
            KeyEvent.KEYCODE_DPAD_LEFT,  // 方向左键
            KeyEvent.KEYCODE_DPAD_RIGHT,  // 方向右键
            KeyEvent.KEYCODE_ESCAPE,  // ESC 键
            KeyEvent.KEYCODE_PAGE_UP,  // PageUp 键
            KeyEvent.KEYCODE_PAGE_DOWN,  // PageDown 键
            KeyEvent.KEYCODE_MOVE_HOME,  // Home 键
            KeyEvent.KEYCODE_MOVE_END,  // End 键
        )  // 结束 setOf 集合

        /**
         * 创建用于添加到 WindowManager 的 LayoutParams
         *
         * - **1x1 像素尺寸**: 不覆盖屏幕，避免影响系统返回手势（Android 14+ 预测式返回
         *   基于窗口层次判定，全屏窗口即使 FLAG_NOT_TOUCHABLE 也会被系统认为遮挡边缘）
         * - 透明背景（PixelFormat.TRANSLUCENT）
         * - FLAG_NOT_TOUCHABLE: 触摸穿透到下层应用
         * - 不含 FLAG_NOT_FOCUSABLE: 可获焦点接收手柄事件（手柄事件通过 InputManager
         *   路由到焦点窗口，与 View 尺寸无关）
         * - FLAG_LAYOUT_IN_SCREEN: 覆盖整个屏幕含状态栏区域
         * - FLAG_LAYOUT_NO_LIMITS: 不受屏幕边界限制
         */
        fun createLayoutParams(): WindowManager.LayoutParams {  // 语法：fun 函数；创建用于添加到 WindowManager 的布局参数
            return LayoutParams(  // 语法：return 返回 + 构造函数调用；创建窗口布局参数对象
                1,  // 宽度 1 像素（1x1 小窗口避免遮挡屏幕）
                1,  // 高度 1 像素
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)  // 语法：if 表达式；判断系统版本是否 >= Android 8.0
                    LayoutParams.TYPE_APPLICATION_OVERLAY  // Android 8.0+ 使用应用悬浮窗类型
                else  // 语法：else 分支；低版本走另一类型
                    @Suppress("DEPRECATION")  // 抑制过时 API 的编译警告
                    LayoutParams.TYPE_PHONE,  // Android 8.0 以下使用 TYPE_PHONE 悬浮窗类型
                LayoutParams.FLAG_NOT_TOUCHABLE or  // 标志：触摸事件穿透（不拦截触摸）
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN or  // 标志：覆盖整个屏幕含状态栏区域
                    LayoutParams.FLAG_LAYOUT_NO_LIMITS,  // 标志：不受屏幕边界限制
                PixelFormat.TRANSLUCENT  // 窗口背景为透明像素格式
            ).apply {  // 语法：apply 作用域函数（在对象上下文中执行并返回该对象）
                // 软输入模式：不自动弹出也不调整布局。
                // 注意不能设 SOFT_INPUT_STATE_ALWAYS_HIDDEN（会阻止 showSoftInput 弹出键盘，
                // 键盘需绑定到本窗口才能把输入经 IME 转发到 Windows 注入）
                softInputMode = LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED or  // 软键盘模式：状态不强制指定
                    LayoutParams.SOFT_INPUT_ADJUST_NOTHING  // 软键盘模式：不调整窗口布局
            }  // 结束 apply 块
        }  // 结束 createLayoutParams 函数
    }  // 结束 companion object

    /** KeyEvent 回调（按钮按下/释放），返回 true=已处理 */
    var onKeyEvent: ((KeyEvent) -> Boolean)? = null  // 语法：var + lambda 类型 + ?可空；按键事件回调变量

    /** MotionEvent 回调（摇杆/扳机），返回 true=已处理 */
    var onGenericMotion: ((MotionEvent) -> Boolean)? = null  // 语法：var + lambda 类型 + ?可空；摇杆/扳机事件回调

    /** 悬浮窗切换回调（组合键触发），在主线程调用 */
    var onToggleOverlay: (() -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；悬浮窗切换回调

    /**
     * 是否正在捕获（历史字段，当前无实际作用）
     *
     * 早期实现里映射器依据本字段决定是否处理非切换动作。现在捕获/暂停由
     * [ControllerOverlayService.isCapturing] 统一管理：**暂停捕获时整个
     * GamepadInputView 会被 WindowManager 移除**（恢复侧滑返回手势），事件根本
     * 到不了本 View；恢复捕获时才重新创建窗口。本字段已不再被读取，保留仅为
     * 兼容旧逻辑。
     */
    var isCapturing: Boolean = true  // 语法：var 可变变量；是否正在捕获（历史字段，保留兼容旧逻辑）

    /**
     * IME 提交/更新的单个字符回调（用于转发到 Windows 注入 WoW）
     *
     * - 普通字符: 直接转发该字符
     * - '\b': 退格（删除光标前字符）
     * - '\u007F': 向前删除（Delete 键）
     *
     * 由 [onCreateInputConnection] 返回的 InputConnection 在 IME 回调时触发，
     * 均在主线程调用。
     */
    var onImeChar: ((Char) -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；IME 单字符回调

    /**
     * IME 整段文本回调（中文等非 ASCII 连续字符合并成一个文本包）
     *
     * 非 ASCII 字符在 Windows 侧是通过「写剪贴板 + Ctrl+V」粘贴注入的，
     * 逐字粘贴时剪贴板会被下一个字符过早覆盖，表现为丢字/重字；
     * 因此把同一段连续非 ASCII 字符合并成一个包，一次粘贴注入。
     */
    var onImeText: ((String) -> Unit)? = null

    /**
     * IME 特殊按键事件回调（回车/方向键等控制键）
     *
     * 退格/删除按键已被 InputConnection 显式处理（转为 onImeChar 的 '\b'），
     * 字母/数字/符号走文本通道（commitText/setComposingText），均不回调到这里。
     */
    var onImeKey: ((KeyEvent) -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；IME 特殊按键回调

    /**
     * IME 关闭回调：输入连接被关闭（用户点击键盘自身隐藏按钮/返回键/输入法失活）。
     * 应用无法直接收到键盘自身隐藏的系统回调，此回调让服务端能恢复捕获。
     */
    var onImeClosed: (() -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；IME 关闭回调

    /** 返回键在 IME 处理前到达本 View（键盘打开时用户按返回）→ 通知服务端恢复捕获 */
    var onImeBackPressed: (() -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；返回键回调

    /** 窗口 insets 显示键盘已收起（键盘自身隐藏按钮/下滑收起等）→ 通知服务端恢复捕获 */
    var onImeHidden: (() -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；键盘收起回调

    /**
     * 已经实际注入到游戏的文本（用于 diff 计算，避免重复注入）。
     *
     * 注意：中文候选等非 ASCII 组合文本不会在组合阶段注入，
     * 因此这里记录的是真正发到 Windows 的内容，而不是输入法的组合文本。
     */
    private var injectedText = ""  // 语法：private var 私有可变变量；已注入到游戏的文本

    /**
     * 抑制标志：deleteSurroundingText 已处理退格后，输入法可能以
     * sendKeyEvent(KEYCODE_DEL) 回退重试，抑制该次回退避免重复删除
     */
    private var suppressDelFromDeleteSurrounding = false  // 语法：private var 私有可变变量；抑制删除键回退重试标志
    /** 软键盘「发送」后的回车抑制截止时刻（毫秒） */
    // performEditorAction 里已手动注入一次回车，super 还会把回车派发回本 View，
    // 若再转给游戏就会变成两次回车（登录界面会提交两次导致认证失败），这里在时间窗内吞掉
    private var suppressEnterUntilMs = 0L  // 语法：private var + Long；置为将来时刻表示正在抑制回车

    init {  // 语法：init 初始化块（构造时自动执行）
        // 必须设置可获焦点，才能接收 KeyEvent
        isFocusable = true  // 设置 View 可获焦点（才能接收按键事件）
        isFocusableInTouchMode = true  // 设置触摸模式下也可获焦点
        // 监听 IME 可见性变化：点击键盘自身隐藏按钮时系统没有任何回调到服务端，
        // 只能通过本窗口的 insets 变化（键盘收起 → ime 不可见）来驱动恢复捕获
        setOnApplyWindowInsetsListener { _, insets ->  // 语法：lambda 参数（_ 忽略第一个参数）；设置窗口 Insets 变化监听
            val imeVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&  // 语法：val + && 逻辑与；判断系统版本是否 >= Android 11
                insets.isVisible(WindowInsets.Type.ime())  // 判断 IME 键盘当前是否可见
            Log.i(TAG, "onApplyWindowInsets imeVisible=$imeVisible")  // 语法：字符串模板 $变量；打印键盘可见性日志
            if (!imeVisible) {  // 语法：if + ! 取反；键盘不可见时执行
                onImeHidden?.invoke()  // 语法：?. 安全调用 + invoke 调用；通知键盘已收起回调
            }  // 结束 if 块
            insets  // 返回 insets 让系统继续处理
        }  // 结束 setOnApplyWindowInsetsListener lambda
    }  // 结束 init 块

    override fun onCheckIsTextEditor(): Boolean = true  // 语法：override 覆写 + 单表达式函数体；声明本 View 是文本编辑器（支持绑定 IME）

    /** 计算两段文本的公共前缀长度 */
    private fun commonPrefixLength(a: String, b: String): Int {  // 语法：private fun 私有函数
        var n = 0  // 公共前缀长度
        while (n < a.length && n < b.length && a[n] == b[n]) n++  // 逐字符比较
        return n  // 返回公共前缀长度
    }  // 结束 commonPrefixLength 函数

    /**
     * 把注入内容从 [from] 回退成 [to]：公共前缀之后先退格，再注入新增部分。
     *
     * 连续非 ASCII 字符合并成一个整段文本包（[onImeText]），一次剪贴板粘贴完成，
     * 避免逐字粘贴时剪贴板被下一段文本过早覆盖导致丢字/重字。
     */
    private fun emitDiff(from: String, to: String) {  // 语法：private fun；按差异注入文本
        val common = commonPrefixLength(from, to)  // 相同前缀长度
        repeat(from.length - common) { onImeChar?.invoke('\b') }  // 回退被替换的部分
        var i = common  // 从差异起点开始注入
        while (i < to.length) {  // 遍历新增内容
            if (to[i].code > 0x7F) {  // 非 ASCII：合并连续字符合成一个文本包
                var j = i  // 连续非 ASCII 结束位置
                while (j < to.length && to[j].code > 0x7F) j++  // 向后扫描连续非 ASCII
                onImeText?.invoke(to.substring(i, j))  // 整段发送一次粘贴
                i = j  // 跳过已注入部分
            } else {  // ASCII：逐字符走 VK 通道
                onImeChar?.invoke(to[i])  // 发送单个字符
                i++  // 前进一个字符
            }  // 结束 if-else
        }  // 结束 while 循环
    }  // 结束 emitDiff 函数

    /**
     * 为软键盘提供 InputConnection，使键盘能绑定到本窗口并捕获输入
     *
     * IME 只能绑定本进程有焦点的窗口。Winlator 是独立进程，软键盘无法直接
     * 绑定到它；因此让键盘绑定到本 1x1 焦点窗口，将输入转发到 Windows 注入。
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {  // 语法：override 覆写；创建 IME 输入连接供软键盘绑定
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or  // 语法：or 位或运算；设置 IME 操作为"发送"
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or  // 禁用提取式 UI（不占用全屏输入栏）
            EditorInfo.IME_FLAG_NO_FULLSCREEN  // 禁用全屏输入模式
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or  // 设置输入类型为普通文本类
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or  // 文本变体：短消息（输入法显示「发送」动作键）
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS  // 关闭输入法联想/拼写建议
        return object : BaseInputConnection(this, false) {  // 语法：object 匿名对象 + 冒号继承；返回匿名输入连接对象
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {  // 语法：override 覆写；提交文本回调
                val s = text?.toString() ?: ""  // 语法：val + ?. 安全调用 + ?: 空合并；转字符串，空则用空串
                Log.d(TAG, "commitText text='$s' injected='$injectedText'")  // 语法：字符串模板；打印提交文本日志
                // 提交会替换当前组合区：只对差异部分做回退/注入。
                // 不要「整体退格后重打」——组合文本通常已由 setComposingText 注入过，
                // 整段重打会让无法映射的字符在回退后补不回来（表现为密码少字符、登录失败）
                emitDiff(injectedText, s)  // 回退差异部分，注入新增部分（非 ASCII 连续字符合并成一个粘贴包）
                injectedText = ""  // 清空已注入文本记录
                return super.commitText(text, newCursorPosition)  // 调用父类完成默认提交行为
            }  // 结束 commitText 函数

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {  // 语法：override 覆写；设置组合文本回调
                val newText = text?.toString() ?: ""  // 语法：val + ?. 安全调用 + ?: 空合并；转字符串，空则用空串
                Log.d(TAG, "setComposingText text='$newText' injected='$injectedText'")  // 语法：字符串模板；打印组合文本日志
                // 中文候选等非 ASCII 组合文本不在组合阶段注入：候选会随拼音不断变化，
                // 逐次「退格 + 剪贴板粘贴」会让剪贴板在被游戏读取前就被覆盖（丢字/重字）。
                // 等 commitText 时再按差异一次性粘贴整段最终文本。
                if (newText.any { it.code > 0x7F }) {  // 语法：any + lambda；组合文本含非 ASCII 字符
                    return super.setComposingText(text, newCursorPosition)  // 仅记录组合文本，不注入游戏
                }  // 结束非 ASCII 分支
                // 与已注入文本求差异：缩短的部分回退，新增的部分注入，
                // 使英文逐字输入时字符能即时到达游戏
                emitDiff(injectedText, newText)  // 回退被缩短的部分并注入新增字符
                injectedText = newText  // 更新已注入文本记录
                return super.setComposingText(text, newCursorPosition)  // 调用父类完成默认组合行为
            }  // 结束 setComposingText 函数

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {  // 语法：override 覆写；删除光标前后文本回调
                Log.d(TAG, "deleteSurroundingText before=$beforeLength after=$afterLength")  // 语法：字符串模板；打印删除日志
                // 显式转发退格/删除
                repeat(beforeLength) { onImeChar?.invoke('\b') }  // 语法：repeat + lambda；光标前字符逐个发退格
                repeat(afterLength) { onImeChar?.invoke('\u007F') }  // 语法：repeat + lambda；光标后字符逐个发 Delete（\u007F）
                // 同步已注入文本记录，避免后续 commitText 再次回退这些字符
                if (beforeLength > 0) injectedText = injectedText.dropLast(minOf(beforeLength, injectedText.length))  // 语法：dropLast + minOf；截掉被删掉的前缀
                if (afterLength > 0) injectedText = ""  // 光标后删除：后续内容无法精确跟踪，清空记录
                // 非编辑框连接 deleteSurroundingText 会返回 false，输入法常随后以
                // sendKeyEvent(KEYCODE_DEL) 回退重试；标记抑制该次回退避免重复删除
                if (beforeLength > 0 || afterLength > 0) {  // 语法：if + || 逻辑或；存在删除操作时置抑制标志
                    suppressDelFromDeleteSurrounding = true  // 置位抑制标志
                }  // 结束 if 块
                return super.deleteSurroundingText(beforeLength, afterLength)  // 调用父类完成默认删除行为
            }  // 结束 deleteSurroundingText 函数

            override fun sendKeyEvent(event: KeyEvent): Boolean {  // 语法：override 覆写；按键事件回调（IME 转发按键到本 View）
                // 退格/删除在按键事件通道统一处理：Gboard 等输入法对非编辑框连接
                // 以按键事件（而非 deleteSurroundingText）发送退格，此前 DEL 被丢弃
                // 导致退格永远到不了游戏。
                // - KEYCODE_DEL（退格键）→ 回退 '\b'（VK_BACK）
                // - KEYCODE_FORWARD_DEL（Delete 键）→ 向前删除 '\u007F'（VK_DELETE）
                // - 控制键（回车/方向/翻页等）→ onImeKey 转发
                // - 可打印键（字母/数字/符号）→ 不转发！它们已由 commitText/
                //   setComposingText 文本通道注入，若这里再经 onImeKey 转发会重复注入
                //   （表现为"按一个字母出现两个"）
                if (event.action == KeyEvent.ACTION_DOWN) {  // 语法：if 条件判断；仅处理按下动作
                    // 「发送」动作已在 performEditorAction 里注入过一次回车，
                    // 输入法若再走按键通道发回车，同样吞掉，避免重复提交登录
                    if (event.keyCode == KeyEvent.KEYCODE_ENTER &&  // 语法：if 条件判断；回车键
                        android.os.SystemClock.uptimeMillis() < suppressEnterUntilMs  // 语法：全限定调用；仍在抑制时间窗内
                    ) {  // 条件结束
                        Log.d(TAG, "sendKeyEvent drop duplicated enter (editor action)")  // 打印吞掉重复回车的日志
                        return super.sendKeyEvent(event)  // 仅交给父类，不再转发到游戏
                    }  // 结束 if 块
                    when (event.keyCode) {  // 语法：when 分支表达式；按按键码分发处理
                        KeyEvent.KEYCODE_DEL -> {  // 退格键分支
                            if (suppressDelFromDeleteSurrounding) {  // 语法：if 条件判断；处于抑制标志时
                                suppressDelFromDeleteSurrounding = false  // 消费该次回退并清除标志
                            } else {  // 语法：else 分支；未抑制时
                                onImeChar?.invoke('\b')  // 语法：?. 安全调用；发送退格字符
                                if (injectedText.isNotEmpty()) {  // 同步已注入文本记录
                                    injectedText = injectedText.dropLast(1)  // 去掉被退格删掉的最后一个字符
                                }  // 结束 if 块
                            }  // 结束 if-else 内层块
                        }  // 结束 KEYCODE_DEL 分支
                        KeyEvent.KEYCODE_FORWARD_DEL -> {  // Delete 键分支：发送向前删除字符
                            onImeChar?.invoke('\u007F')  // 发送向前删除字符
                            injectedText = ""  // 光标后删除：后续内容无法精确跟踪，清空记录
                        }  // 结束 KEYCODE_FORWARD_DEL 分支
                        else -> {  // 语法：else 兜底分支
                            if (event.keyCode in CONTROL_KEY_CODES) onImeKey?.invoke(event)  // 语法：in 成员判断；控制键经按键事件通道转发
                            else Log.d(TAG, "sendKeyEvent drop printable key=${event.keyCode} " +  // 语法：else + 字符串模板拼接；可打印键丢弃并打印日志
                                "char=${event.unicodeChar}")  // 日志续行：附带字符编码
                        }  // 结束 else 分支
                    }  // 结束 when 分支
                }  // 结束 if（仅按下）块
                return super.sendKeyEvent(event)  // 调用父类完成默认按键处理
            }  // 结束 sendKeyEvent 函数

            override fun performEditorAction(actionCode: Int): Boolean {  // 语法：override 覆写；编辑器动作（软键盘发送/回车）回调
                // 软键盘"发送/回车"动作 → 注入回车
                val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)  // 语法：val + 构造函数；构造回车键按下事件
                val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)  // 语法：val + 构造函数；构造回车键释放事件
                // 先开启抑制窗口，再手动注入：super 随后派发回来的回车不会二次进游戏
                suppressEnterUntilMs = android.os.SystemClock.uptimeMillis() + 400  // 语法：全限定调用；400ms 内吞掉派发回来的回车
                onImeKey?.invoke(down)  // 语法：?. 安全调用；转发回车按下事件
                onImeKey?.invoke(up)  // 语法：?. 安全调用；转发回车释放事件
                return super.performEditorAction(actionCode)  // 调用父类完成默认动作
            }  // 结束 performEditorAction 函数

            override fun closeConnection() {  // 语法：override 覆写；输入连接关闭回调
                Log.i(TAG, "InputConnection closed")  // 打印输入连接关闭日志
                super.closeConnection()  // 调用父类完成默认关闭
                // 键盘自身隐藏按钮/输入法失活 → 输入连接被关闭，通知服务端恢复捕获
                onImeClosed?.invoke()  // 语法：?. 安全调用；通知 IME 关闭回调
            }  // 结束 closeConnection 函数
        }  // 结束匿名 InputConnection 对象
    }  // 结束 onCreateInputConnection 函数

    /**
     * 键盘打开时按返回键（BACK）会先经此回调到达本 View。
     *
     * 若 IME 未消费该返回键，则回到默认返回行为；这里统一通知服务端
     * 键盘已收起并恢复捕获（insets 监听可能因窗口特性不触发，作为兜底）。
     */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {  // 语法：override 覆写；IME 处理前的按键回调
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {  // 语法：if + && 逻辑与；按下返回键时
            Log.i(TAG, "onKeyPreIme BACK pressed, notify service")  // 打印返回键按下日志
            onImeBackPressed?.invoke()  // 语法：?. 安全调用；通知返回键回调（恢复捕获）
        }  // 结束 if 块
        return super.onKeyPreIme(keyCode, event)  // 调用父类完成默认处理
    }  // 结束 onKeyPreIme 函数

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {  // 语法：override 覆写；分发按键事件
        // 软键盘「发送」后的时间窗内，吞掉输入连接派发回来的回车（已手动注入过一次）
        if (event.keyCode == KeyEvent.KEYCODE_ENTER &&  // 语法：if 条件判断；回车键
            android.os.SystemClock.uptimeMillis() < suppressEnterUntilMs  // 语法：全限定调用；仍在抑制时间窗内
        ) {  // 条件结束
            Log.d(TAG, "dispatchKeyEvent drop duplicated enter (editor action)")  // 打印吞掉重复回车的日志
            return true  // 消费该事件，不再转发给手柄映射器
        }  // 结束 if 块
        Log.d(TAG, "dispatchKeyEvent act=${event.action} key=${event.keyCode} " +  // 语法：字符串模板 + 字符串拼接；打印按键分发日志
            "src=${event.source} rep=${event.repeatCount} unicode=${event.unicodeChar}")  // 日志续行：来源/重复次数/字符编码
        // 始终转发到映射器，由映射器根据 isCapturing 决定是否处理
        val handled = onKeyEvent?.invoke(event) ?: false  // 语法：val + ?. 安全调用 + ?: 空合并；回调为空时视为未处理
        return handled || super.dispatchKeyEvent(event)  // 语法：|| 逻辑或；已处理则不再传给父类
    }  // 结束 dispatchKeyEvent 函数

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {  // 语法：override 覆写；分发手柄摇杆/扳机事件
        val handled = onGenericMotion?.invoke(event) ?: false  // 语法：val + ?. 安全调用 + ?: 空合并；回调为空时视为未处理
        return handled || super.dispatchGenericMotionEvent(event)  // 语法：|| 逻辑或；已处理则不再传给父类
    }  // 结束 dispatchGenericMotionEvent 函数

    /**
     * 窗口焦点变化时自动重新请求焦点
     *
     * 当用户切到其他应用再切回时，窗口可能失去焦点。
     * 此回调在窗口重新获得焦点时自动 requestFocus()。
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {  // 语法：override 覆写；窗口焦点变化回调
        super.onWindowFocusChanged(hasWindowFocus)  // 调用父类完成默认处理
        Log.i(TAG, "onWindowFocusChanged hasFocus=$hasWindowFocus")  // 语法：字符串模板；打印焦点变化日志
        if (hasWindowFocus) {  // 语法：if 条件判断；获得焦点时
            requestFocus()  // 主动请求焦点（接收手柄事件）
            // 延迟再次请求焦点，确保在窗口动画完成后仍获得焦点
            postDelayed({ requestFocus() }, 200)  // 语法：lambda + postDelayed 延时任务；200ms 后再请求一次焦点
        }  // 结束 if 块
    }  // 结束 onWindowFocusChanged 函数
}  // 结束 GamepadInputView 类
