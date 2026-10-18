package com.steamlike.controller.injection  // 声明包名：注入相关类的包

import android.view.InputEvent  // 导入输入事件基类
import android.view.KeyEvent  // 导入按键事件类（提供 KEYCODE_* 常量）
import android.view.MotionEvent  // 导入触摸事件类（鼠标按钮/摇杆相关）

/**
 * 输入注入器接口
 *
 * ## 设计目的
 * 将手柄映射后的键盘/鼠标事件注入到目标应用（Winlator 内的 WoW 游戏）。
 * 使用接口抽象是为了支持多种注入实现，便于未来扩展。
 *
 * ## 当前实现
 * - [BridgeInputInjector]: 通过 TCP 桥接到 Windows，使用 SendInput() 注入
 *
 * ## 未来可能的实现
 * - LocalInputInjector: 直接注入 Android 系统（需要 root 或 Shizuku）
 * - UsbInjector: 通过 USB HID 注入
 *
 * ## 使用流程
 * ```kotlin
 * val injector: InputInjector = BridgeInputInjector(server)
 * if (injector.isAvailable()) {
 *     injector.sendKeyPress(KeyEvent.KEYCODE_SPACE)  // 模拟按下空格键
 *     injector.sendMouseClick(MouseButton.LEFT)       // 模拟鼠标左键点击
 * }
 * ```
 *
 * ## 线程安全
 * 实现类需保证线程安全，因为注入操作可能在多个线程调用:
 * - 主线程的按钮回调（onPressed/onReleased）
 * - 主线程的摇杆回调（onValueChanged）
 * - 服务销毁时调用 [destroy]
 */
interface InputInjector {  // 语法：interface 声明接口；输入注入器接口

    /**
     * 检查注入器是否可用
     *
     * 在调用其他方法前应先检查此方法，避免注入失败。
     *
     * @return true=可用，false=不可用（如 TCP 服务器未启动）
     */
    fun isAvailable(): Boolean  // 语法：fun 抽象方法；检查注入器是否可用

    /**
     * 按下键盘按键
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量（如 [KeyEvent.KEYCODE_SPACE]）
     */
    fun sendKeyDown(keyCode: Int)  // 语法：fun 抽象方法；按下键盘按键

    /**
     * 释放键盘按键
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量
     */
    fun sendKeyUp(keyCode: Int)  // 语法：fun 抽象方法；释放键盘按键

    /**
     * 发送 Windows 虚拟键（VK）点击（按下 + 立即释放）
     *
     * 直接使用 Windows VK 码，绕过 Android KeyCode → VK 的映射表，
     * 用于需要直接指定 Windows VK 码的场景（如 VK_F11=0x7A、VK_F12=0x7B）。
     *
     * @param vkCode Windows 虚拟键码
     */
    fun sendVkKeyPress(vkCode: Int)  // 语法：fun 抽象方法；用原始 VK 码发送一次按键点击

    /**
     * 按键点击（按下 + 立即释放）
     *
     * 默认实现调用 [sendKeyDown] 后立即调用 [sendKeyUp]。
     * 适用于不需要长按的场景（如快捷栏按键）。
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量
     */
    fun sendKeyPress(keyCode: Int) {  // 语法：fun 接口默认实现方法（带方法体）；按键点击 = 按下 + 立即释放
        sendKeyDown(keyCode)  // 先发送按下事件
        sendKeyUp(keyCode)  // 再立即发送释放事件
    }  // 结束 sendKeyPress 方法

    /**
     * 鼠标按下
     *
     * @param button 鼠标按钮（[MouseButton.LEFT] / [MouseButton.RIGHT] / [MouseButton.MIDDLE]）
     */
    fun sendMouseDown(button: MouseButton)  // 语法：fun 抽象方法；鼠标按下

    /**
     * 鼠标释放
     *
     * @param button 鼠标按钮
     */
    fun sendMouseUp(button: MouseButton)  // 语法：fun 抽象方法；鼠标释放

    /**
     * 鼠标点击（按下 + 立即释放）
     *
     * 默认实现调用 [sendMouseDown] 后立即调用 [sendMouseUp]。
     *
     * @param button 鼠标按钮
     */
    fun sendMouseClick(button: MouseButton) {  // 语法：fun 接口默认实现方法（带方法体）；鼠标点击 = 按下 + 立即释放
        sendMouseDown(button)  // 先发送按下事件
        sendMouseUp(button)  // 再立即发送释放事件
    }  // 结束 sendMouseClick 方法

    /**
     * 鼠标相对移动
     *
     * 用于摇杆控制视角或光标。dx/dy 为相对位移（非绝对坐标）。
     *
     * @param dx X 轴相对位移（像素，右为正）
     * @param dy Y 轴相对位移（像素，下为正）
     */
    fun sendMouseMove(dx: Float, dy: Float)  // 语法：fun 抽象方法；鼠标相对移动

    /**
     * 将鼠标光标归位到「全局设置 cursorParkOffscreen 开关决定的落点」
     *
     * - 开启（默认）：屏幕中央正下方、屏幕下边缘之外 1/10 屏高处
     * - 关闭：屏幕正中央
     *
     * 用于左摇杆 WASD 移动自动锁定右键前，先把光标归位，
     * 避免锁定瞬间视角从偏移位置开始漂移。
     *
     * @param parkOffscreen true=停放到屏幕外下方，false=屏幕正中央
     */
    fun sendMouseCenter(parkOffscreen: Boolean)  // 语法：fun 抽象方法；鼠标归位（按开关决定落点）

    /**
     * 将鼠标光标强制移动到屏幕正中央
     *
     * 供「交互键」内部序列与各「解除锁定」路径使用：这些场景的归中必须始终落在
     * 屏幕正中央，不受 cursorParkOffscreen 开关影响。
     */
    fun sendMouseCenterExact()  // 语法：fun 抽象方法；鼠标强制移到屏幕正中央（交互键 / 解除锁定专用）

    /**
     * 记录当前鼠标光标位置
     *
     * 供「交互键」序列使用：APK 侧无法得知 Windows 光标坐标，需由 Windows 客户端
     * 存下当前位置，序列末尾再用 [sendCursorRestore] 还原。
     */
    fun sendCursorSave()  // 语法：fun 抽象方法；记录当前鼠标光标位置（交互键序列专用）

    /**
     * 把鼠标光标还原到 [sendCursorSave] 记录的位置
     */
    fun sendCursorRestore()  // 语法：fun 抽象方法；还原到记录的光标位置（交互键序列专用）

    /**
     * 鼠标滚轮滚动
     *
     * @param delta 滚轮增量（正数=上滚，负数=下滚）
     */
    fun sendMouseScroll(delta: Float)  // 语法：fun 抽象方法；鼠标滚轮滚动

    /**
     * 释放所有按下的键和按钮
     *
     * 在以下场景调用:
     * - 切换操作层时（防止按键卡住）
     * - 服务停止时
     * - 客户端断开连接时
     */
    fun releaseAll()  // 语法：fun 抽象方法；释放所有按下的键和按钮

    /**
     * 销毁注入器，释放资源
     *
     * 在服务 onDestroy 时调用。
     */
    fun destroy()  // 语法：fun 抽象方法；销毁注入器，释放资源
}  // 结束 InputInjector 接口

/**
 * 鼠标按钮枚举
 *
 * 用于 [InputInjector.sendMouseDown] / [InputInjector.sendMouseUp] 等方法。
 */
enum class MouseButton {  // 语法：enum class 枚举类；鼠标按钮枚举
    /** 左键（主键，用于点击/选择） */
    LEFT,  // 语法：枚举常量；左键
    /** 右键（次键，用于上下文菜单/视角控制） */
    RIGHT,  // 语法：枚举常量；右键
    /** 中键（滚轮按下，用于自动滚动等） */
    MIDDLE,  // 语法：枚举常量；中键
    /** 前进键（X1，用于浏览器/游戏前进） */
    FORWARD,  // 语法：枚举常量；前进键
    /** 后退键（X2，用于浏览器/游戏后退） */
    BACK  // 语法：枚举常量；后退键（最后一个元素无逗号）
}  // 结束 MouseButton 枚举
