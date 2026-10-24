package com.steamlike.controller.injection  // 声明包名：注入相关类的包

import android.util.Log  // 导入日志工具类
import com.steamlike.controller.BuildConfig  // 导入 BuildConfig（读取 versionName 用于版本同步包）
import java.io.IOException  // 导入 IO 异常类
import java.io.OutputStream  // 导入输出流类（向客户端写数据）
import java.net.InetSocketAddress  // 导入网络地址类（绑定监听地址）
import java.net.ServerSocket  // 导入 TCP 服务器套接字类
import java.net.Socket  // 导入 TCP 套接字类（客户端连接）
import java.util.concurrent.CopyOnWriteArrayList  // 导入写时复制列表类（并发安全的客户端列表）
import java.util.concurrent.LinkedBlockingQueue  // 导入阻塞队列类（消息队列，poll 阻塞等待以避免空转轮询）
import java.util.concurrent.TimeUnit  // 导入时间单位类（poll 超时参数）
import java.util.concurrent.atomic.AtomicBoolean  // 导入原子布尔类（线程安全标志）
import java.util.concurrent.atomic.AtomicInteger  // 导入原子整数类（自增 ID）

/**
 * InputBridge TCP服务器
 *
 * 在Android端运行TCP服务器，接收手柄映射事件，转发给连接的Windows客户端。
 * Windows客户端运行在Winlator内部，通过localhost连接到本服务器。
 *
 * ## 架构
 * ```
 * Android APK                          Winlator (Windows)
 * ┌──────────────────┐                ┌──────────────────────┐
 * │ GamepadInputView  │                │ InputBridgeClient.exe │
 * │      ↓            │                │  (C程序)              │
 * │ SteamInput        │                │      ↓                │
 * │      ↓            │                │ recv() 接收事件       │
 * │ BridgeInputInjector│               │      ↓                │
 * │      ↓            │   TCP连接      │ SendInput() 注入      │
 * │ InputBridgeServer │←──────────────→│ (localhost:27015)     │
 * └──────────────────┘                └──────────────────────┘
 * ```
 *
 * ## 通信协议
 * 每个消息为8字节定长包:
 * ```
 * Byte 0: 消息类型
 *   0x01 = 键盘事件
 *   0x02 = 鼠标移动
 *   0x03 = 鼠标按钮
 *   0x04 = 鼠标滚轮
 *   0x05 = 释放所有按键
 *   0x06 = 心跳Ping
 *   0x07 = 鼠标归位（落点由开关决定：屏幕左下角之外 2/3 处 / 屏幕正中央）
 *   0x08 = 鼠标强制移动到屏幕正中央（交互键 / 解除锁定专用）
 *   0x09 = 记录当前鼠标光标位置（交互键序列）
 *   0x0A = 还原到 0x09 记录的鼠标光标位置（交互键序列）
 *   0x0B = 时间同步（客户端连接时下发 APK 当前时间）
 *
 * Byte 1-7: 载荷 (按消息类型不同)
 *
 * 键盘事件 (0x01):
 *   Byte 1-2: Windows虚拟键码 (uint16 LE)
 *   Byte 3:   是否按下 (0=释放, 1=按下)
 *   Byte 4-7: 保留
 *
 * 鼠标移动 (0x02):
 *   Byte 1-2: dx (int16 LE)
 *   Byte 3-4: dy (int16 LE)
 *   Byte 5-7: 保留
 *
 * 鼠标按钮 (0x03):
 *   Byte 1: 按钮 (0=左, 1=右, 2=中)
 *   Byte 2: 是否按下 (0=释放, 1=按下)
 *   Byte 3-7: 保留
 *
 * 鼠标滚轮 (0x04):
 *   Byte 1-2: delta (int16 LE)
 *   Byte 3-7: 保留
 *
 * 鼠标归位 (0x07):
 *   Byte 1: 落点 (0=屏幕正中央, 1=屏幕左下角之外、屏幕下边缘下方 2/3 屏高处)
 *   Byte 2-7: 保留
 *
 * 鼠标强制居中央 (0x08):
 *   无载荷
 *
 * 鼠标光标位置记录 (0x09):
 *   无载荷。Windows 客户端把当前光标位置存下来
 *
 * 鼠标光标位置还原 (0x0A):
 *   无载荷。Windows 客户端把光标移回 0x09 记录的位置
 *
 * 时间同步 (0x0B):
 *   Byte 1-7: epoch 毫秒 (56 位小端)。Windows 客户端收到后校准 Winlator 系统时间
 * ```
 *
 * @param host TCP监听地址，null或空表示监听所有接口(0.0.0.0)
 * @param port TCP监听端口，默认27015
 */
class InputBridgeServer(  // 语法：class 声明类；InputBridge TCP 服务器
    private val host: String? = null,  // 语法：val 构造参数 + ?可空；监听地址，null 表示监听所有接口
    private val port: Int = DEFAULT_PORT  // 语法：val 构造参数 + 默认值；监听端口，默认 27015
) {  // 结束构造参数列表，进入类体

    companion object {  // 语法：companion object 伴生对象（类级静态成员容器）
        private const val TAG = "InputBridgeServer"  // 语法：const val 编译期常量；日志标签
        const val DEFAULT_PORT = 27015  // 语法：const val 编译期常量；默认端口号
        const val DEFAULT_HOST = "0.0.0.0"  // 语法：const val 编译期常量；默认监听地址（所有接口）

        // 消息类型
        const val MSG_KEY_EVENT: Byte = 0x01  // 语法：const val + Byte 类型；消息类型：键盘事件
        const val MSG_MOUSE_MOVE: Byte = 0x02  // 消息类型：鼠标移动
        const val MSG_MOUSE_BUTTON: Byte = 0x03  // 消息类型：鼠标按钮
        const val MSG_MOUSE_WHEEL: Byte = 0x04  // 消息类型：鼠标滚轮
        const val MSG_RELEASE_ALL: Byte = 0x05  // 消息类型：释放所有按键
        const val MSG_PING: Byte = 0x06  // 消息类型：心跳 Ping
        const val MSG_MOUSE_CENTER: Byte = 0x07  // 消息类型：鼠标归位（落点由开关决定）
        const val MSG_MOUSE_CENTER_EXACT: Byte = 0x08  // 消息类型：鼠标强制移到屏幕正中央（交互键 / 解除锁定专用）
        const val MSG_CURSOR_SAVE: Byte = 0x09  // 消息类型：记录当前鼠标光标位置（交互键序列）
        const val MSG_CURSOR_RESTORE: Byte = 0x0A  // 消息类型：还原到记录的光标位置（交互键序列）
        const val MSG_TIME_SYNC: Byte = 0x0B  // 消息类型：时间同步（下发 APK 当前时间给 Windows 客户端）
        const val MSG_VERSION: Byte = 0x0C  // 消息类型：版本同步（下发 APK 版本号，Windows 客户端界面/标题栏显示）
        const val MSG_CLIENT_VERSION: Byte = 0x20  // 消息类型：客户端信息（Windows 客户端上报 exe 编译版本）

        const val PACKET_SIZE = 8  // 语法：const val 编译期常量；协议数据包固定长度 8 字节
    }  // 结束 companion object

    private var serverSocket: ServerSocket? = null  // 语法：private var + ?可空；TCP 服务器套接字
    private val isRunning = AtomicBoolean(false)  // 语法：val + AtomicBoolean 原子类型；服务器运行标志（线程安全）
    private val clients = CopyOnWriteArrayList<ClientConnection>()  // 语法：val + 泛型；已连接客户端列表（并发安全）
    private val messageQueue = LinkedBlockingQueue<ByteArray>()  // 语法：val + 泛型；待发送消息队列（阻塞队列，poll 阻塞等待）

    /** 服务器状态回调 */
    var onClientConnected: ((String) -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；客户端连接回调
    var onClientDisconnected: ((String) -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；客户端断开回调
    var onServerError: ((String) -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；服务器错误回调
    var onClientVersionMismatch: ((String) -> Unit)? = null  // 语法：var + lambda 类型 + ?可空；客户端 exe 版本与 APK 期望不一致回调

    /**
     * 启动TCP服务器
     * @return true=启动成功
     */
    fun start(): Boolean {  // 语法：fun 函数；启动 TCP 服务器
        if (isRunning.get()) return true  // 语法：if + return 提前返回；已在运行则直接返回成功
        return try {  // 语法：return + try 表达式；尝试启动并返回结果
            serverSocket = ServerSocket()  // 创建未绑定的服务器套接字
            // 绑定到指定接口，null/空表示监听所有接口(0.0.0.0)
            val addr = if (host.isNullOrBlank()) InetSocketAddress(port)  // 语法：val + if 表达式；地址为空时监听所有接口
                        else InetSocketAddress(host, port)  // 语法：else 分支；否则监听指定地址和端口
            serverSocket!!.bind(addr)  // 语法：!! 非空断言；绑定监听地址
            isRunning.set(true)  // 置位运行标志

            // 启动接受连接的线程
            Thread(::acceptLoop, "BridgeServer-Accept").start()  // 语法：方法引用 :: + Thread；启动接受连接线程
            // 启动消息分发线程
            Thread(::dispatchLoop, "BridgeServer-Dispatch").start()  // 语法：方法引用 :: + Thread；启动消息分发线程

            Log.i(TAG, "服务器已启动, 地址=${addr.hostString}:${port}")  // 语法：字符串模板；打印启动成功日志
            true  // 返回 true 表示启动成功
        } catch (e: IOException) {  // 语法：catch 捕获 IO 异常
            Log.e(TAG, "启动服务器失败", e)  // 打印启动失败日志
            onServerError?.invoke(e.message ?: "未知错误")  // 语法：?. 安全调用 + ?: 空合并；通知错误回调
            false  // 返回 false 表示启动失败
        }  // 结束 try-catch
    }  // 结束 start 函数

    /**
     * 停止服务器
     */
    fun stop() {  // 语法：fun 函数；停止服务器
        isRunning.set(false)  // 清除运行标志（循环线程将退出）
        clients.forEach { it.close() }  // 语法：forEach + it 隐式参数；关闭所有客户端连接
        clients.clear()  // 清空客户端列表
        messageQueue.clear()  // 清空消息队列
        try {  // 尝试关闭服务器套接字
            serverSocket?.close()  // 语法：?. 安全调用；关闭服务器套接字
        } catch (e: IOException) {  // 语法：catch 捕获 IO 异常
            Log.e(TAG, "关闭服务器失败", e)  // 打印关闭失败日志
        }  // 结束 try-catch
        serverSocket = null  // 置空服务器套接字引用
        Log.i(TAG, "服务器已停止")  // 打印停止日志
    }  // 结束 stop 函数

    /**
     * 发送键盘事件
     * @param vkCode Windows虚拟键码 (如 VK_SPACE=0x20)
     * @param isDown true=按下, false=释放
     */
    fun sendKeyEvent(vkCode: Int, isDown: Boolean) {  // 语法：fun 函数；发送键盘事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray 构造；创建 8 字节定长数据包
        packet[0] = MSG_KEY_EVENT  // 写入消息类型：键盘事件
        packet[1] = (vkCode and 0xFF).toByte()  // 语法：位与 and 0xFF；写入 VK 码低 8 位
        packet[2] = ((vkCode shr 8) and 0xFF).toByte()  // 语法：shr 右移；写入 VK 码高 8 位（小端序）
        packet[3] = if (isDown) 1 else 0  // 语法：if 表达式；写入按下标志（1=按下）
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendKeyEvent 函数

    /**
     * 发送鼠标移动事件
     * @param dx X轴相对位移
     * @param dy Y轴相对位移
     */
    fun sendMouseMove(dx: Float, dy: Float) {  // 语法：fun 函数；发送鼠标移动事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_MOUSE_MOVE  // 写入消息类型：鼠标移动
        val dxInt = dx.toInt().toShort().toInt()  // 语法：链式转换；Float 取整并转 int16 范围
        val dyInt = dy.toInt().toShort().toInt()  // 语法：链式转换；Y 轴同样取整并转 int16
        packet[1] = (dxInt and 0xFF).toByte()  // 写入 dx 低 8 位
        packet[2] = ((dxInt shr 8) and 0xFF).toByte()  // 写入 dx 高 8 位（小端序）
        packet[3] = (dyInt and 0xFF).toByte()  // 写入 dy 低 8 位
        packet[4] = ((dyInt shr 8) and 0xFF).toByte()  // 写入 dy 高 8 位
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendMouseMove 函数

    /**
     * 发送鼠标按钮事件
     * @param button 0=左键, 1=右键, 2=中键
     * @param isDown true=按下, false=释放
     */
    fun sendMouseButton(button: Int, isDown: Boolean) {  // 语法：fun 函数；发送鼠标按钮事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_MOUSE_BUTTON  // 写入消息类型：鼠标按钮
        packet[1] = button.toByte()  // 写入按钮 ID（0=左,1=右,2=中）
        packet[2] = if (isDown) 1 else 0  // 语法：if 表达式；写入按下标志
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendMouseButton 函数

    /**
     * 发送鼠标滚轮事件
     * @param delta 滚轮增量
     */
    fun sendMouseWheel(delta: Float) {  // 语法：fun 函数；发送鼠标滚轮事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_MOUSE_WHEEL  // 写入消息类型：鼠标滚轮
        val deltaInt = delta.toInt().toShort().toInt()  // 语法：链式转换；滚轮增量取整并转 int16
        packet[1] = (deltaInt and 0xFF).toByte()  // 写入 delta 低 8 位
        packet[2] = ((deltaInt shr 8) and 0xFF).toByte()  // 写入 delta 高 8 位
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendMouseWheel 函数

    /**
     * 发送鼠标归位事件
     *
     * Byte 1 携带落点：0=屏幕正中央，1=屏幕中央正下方、屏幕下边缘之外 1/10 屏高处。
     * Windows 客户端收到后按落点调用 SetCursorPos。
     * 用于左摇杆 WASD 移动自动锁定右键前，先把光标归位，
     * 避免锁定瞬间视角从偏移位置开始漂移。
     *
     * @param parkOffscreen true=停放到屏幕外下方，false=屏幕正中央
     */
    fun sendMouseCenter(parkOffscreen: Boolean) {  // 语法：fun 函数；发送鼠标归位事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_MOUSE_CENTER  // 写入消息类型：鼠标归位
        packet[1] = if (parkOffscreen) 1 else 0  // 写入落点：1=屏幕外下方，0=屏幕正中央
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendMouseCenter 函数

    /**
     * 发送鼠标强制移到屏幕正中央事件
     *
     * 无载荷：交互键内部归中与各「解除锁定」路径固定用屏幕正中央，
     * 不受「居中落点」开关影响。
     */
    fun sendMouseCenterExact() {  // 语法：fun 函数；发送强制居中央事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_MOUSE_CENTER_EXACT  // 写入消息类型：强制居中央
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendMouseCenterExact 函数

    /**
     * 发送「记录当前鼠标光标位置」事件
     *
     * 用于交互键序列开头：APK 侧无法得知 Windows 光标坐标，由 Windows 客户端
     * 把当前光标位置存下来，序列末尾用 [sendCursorRestore] 还原。
     */
    fun sendCursorSave() {  // 语法：fun 函数；发送记录光标位置事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_CURSOR_SAVE  // 写入消息类型：记录光标位置
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendCursorSave 函数

    /**
     * 发送「还原鼠标光标位置」事件
     *
     * 把光标移回 [sendCursorSave] 记录的位置（未记录过时 Windows 客户端不做任何事）。
     */
    fun sendCursorRestore() {  // 语法：fun 函数；发送还原光标位置事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_CURSOR_RESTORE  // 写入消息类型：还原光标位置
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendCursorRestore 函数

    /**
     * 发送释放所有按键事件
     */
    fun sendReleaseAll() {  // 语法：fun 函数；发送释放所有按键事件
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节数据包
        packet[0] = MSG_RELEASE_ALL  // 写入消息类型：释放所有
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendReleaseAll 函数

    /**
     * 发送心跳包
     */
    fun sendPing() {  // 语法：fun 函数；发送心跳包
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节定长数据包
        packet[0] = MSG_PING  // 写入消息类型：心跳 Ping
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendPing 函数

    /**
     * 发送时间同步包
     *
     * 把 APK 当前时间（epoch 毫秒）以 56 位小端写入 Byte 1-7 下发，
     * Windows 客户端收到后校准 Winlator 容器的系统时间（容器时间容易漂移）。
     * 客户端每次连接建立时发送一次。
     */
    fun sendTimeSync() {  // 语法：fun 函数；发送时间同步包
        val packet = ByteArray(PACKET_SIZE)  // 语法：val + ByteArray；创建 8 字节定长数据包
        packet[0] = MSG_TIME_SYNC  // 写入消息类型：时间同步
        val millis = System.currentTimeMillis()  // 取当前 epoch 毫秒
        for (i in 0..6) {  // 语法：for 循环；56 位小端拆成 7 字节写入 Byte 1-7
            packet[1 + i] = ((millis ushr (8 * i)) and 0xFF).toByte()  // 写入第 i 个字节
        }
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendTimeSync 函数

    /**
     * 发送版本同步包
     *
     * 把 APK 的 versionName 拆成 主版本/次版本/修订号 写入 Byte 1-3，
     * Windows 客户端收到后用它刷新界面与标题栏的版本号，因此仅改版本号
     * 无需重新编译或重新导出 exe。客户端每次连接建立时发送一次。
     */
    fun sendVersion() {  // 语法：fun 函数；发送版本同步包
        val packet = ByteArray(PACKET_SIZE)  // 创建 8 字节定长数据包
        packet[0] = MSG_VERSION  // 写入消息类型：版本同步
        val parts = BuildConfig.VERSION_NAME.split(".")  // 拆分版本号（major.minor.patch）
        for (i in 0..2) {  // 语法：for 循环；依次写入三个数字
            packet[1 + i] = ((parts.getOrNull(i)?.toIntOrNull() ?: 0) and 0xFF).toByte()  // 越界或非数字按 0 处理
        }  // 结束循环
        enqueue(packet)  // 加入发送队列
    }  // 结束 sendVersion 函数

    /** 是否有客户端连接 */
    fun hasClients(): Boolean = clients.isNotEmpty()  // 语法：单表达式函数；返回客户端列表是否非空

    /** 获取已连接客户端数量 */
    fun clientCount(): Int = clients.size  // 语法：单表达式函数；返回客户端数量

    /** 服务器是否正在运行（accept/dispatch 循环是否存活） */
    fun isRunning(): Boolean = isRunning.get()  // 语法：单表达式函数；返回原子运行标志

    // ===== 内部实现 =====

    private fun enqueue(packet: ByteArray) {  // 语法：private fun 私有函数；把数据包加入发送队列
        messageQueue.add(packet)  // 队列添加（线程安全）
    }  // 结束 enqueue 函数

    /**
     * 接受客户端连接的循环
     */
    private fun acceptLoop() {  // 语法：private fun 私有函数；接受客户端连接的循环
        while (isRunning.get()) {  // 语法：while 循环；运行期间持续接受连接
            try {  // 尝试接受新连接
                val socket = serverSocket?.accept() ?: break  // 语法：?. 安全调用 + ?: 空合并 + break；套接字为空时退出循环
                // 禁用 Nagle 算法：手柄事件是大量 8 字节小包，
                // 默认 Nagle 会让小包等待 ACK 造成 ~40ms 周期延迟，表现为游戏内一卡一卡
                try {  // 尝试设置 TCP_NODELAY
                    socket.setTcpNoDelay(true)  // 禁用 Nagle 算法（降低小包延迟）
                } catch (e: IOException) {  // 语法：catch 捕获 IO 异常
                    // TCP_NODELAY 设置失败不致命，仅影响延迟表现
                    Log.w(TAG, "Failed to set TCP_NODELAY", e)  // 打印设置失败警告日志
                }  // 结束 try-catch
                val client = ClientConnection(socket)  // 语法：val + 构造；封装客户端连接对象
                clients.add(client)  // 加入客户端列表
                val addr = socket.remoteSocketAddress.toString()  // 获取客户端远程地址字符串
                Log.i(TAG, "客户端已连接: $addr")  // 语法：字符串模板；打印连接日志
                onClientConnected?.invoke(addr)  // 语法：?. 安全调用；通知连接回调
                sendTimeSync()  // 客户端连上后立即下发当前时间，校准 Winlator 系统时间
                sendVersion()  // 同时下发 APK 版本号，客户端界面/标题栏显示与 APK 一致的版本
                Thread({ handleClient(client) }, "BridgeServer-Client-${client.id}").start()  // 语法：lambda + Thread + 字符串模板；启动客户端处理线程
            } catch (e: IOException) {  // 语法：catch 捕获 IO 异常
                if (isRunning.get()) {  // 语法：if 条件判断；仅在运行中才记录错误
                    Log.e(TAG, "接受连接失败", e)  // 打印接受连接失败日志
                }  // 结束 if 块
            }  // 结束 try-catch
        }  // 结束 while 循环
    }  // 结束 acceptLoop 函数

    /**
     * 处理单个客户端连接（读取客户端发来的定长数据包）
     *
     * 目前客户端只在连接建立时上报一次 exe 版本（0x20），用于判断是否需要重新导出客户端；
     * 其余字节仅用于检测断开。读取按 8 字节定长分包，避免 TCP 分片导致解析错位。
     */
    private fun handleClient(client: ClientConnection) {  // 语法：private fun 私有函数；处理单个客户端连接
        try {  // 尝试读取客户端数据
            val input = client.socket.getInputStream()  // 获取客户端输入流（读取断开信号）
            val buffer = ByteArray(64)  // 语法：val + ByteArray；创建读取缓冲区
            val frame = ByteArray(PACKET_SIZE)  // 定长帧缓冲（按 8 字节组包）
            var filled = 0  // 当前帧已填充字节数
            while (isRunning.get() && !client.closed) {  // 语法：while + && 逻辑与 + ! 取反；运行且未关闭时持续读
                val read = input.read(buffer)  // 读取客户端发来的数据
                if (read == -1) break  // 客户端断开 // 读到 -1 说明对端已关闭连接
                var offset = 0  // 本次读到的数据偏移
                while (offset < read) {  // 语法：while 循环；把读到的字节逐个填进定长帧
                    val copy = minOf(PACKET_SIZE - filled, read - offset)  // 本次可拷贝字节数
                    System.arraycopy(buffer, offset, frame, filled, copy)  // 拷贝到帧缓冲
                    filled += copy  // 更新已填充字节数
                    offset += copy  // 更新偏移
                    if (filled == PACKET_SIZE) {  // 凑满一个完整数据包
                        filled = 0  // 重置帧缓冲计数
                        handleClientPacket(frame)  // 处理该数据包
                    }  // 结束整包判断
                }  // 结束内层 while 循环
            }  // 结束 while 循环
        } catch (e: IOException) {  // 语法：catch 捕获 IO 异常
            // 客户端断开
        } finally {  // 语法：finally 块（无论是否异常都会执行）
            client.close()  // 关闭该客户端连接
            clients.remove(client)  // 从客户端列表移除
            val addr = client.socket.remoteSocketAddress?.toString() ?: "unknown"  // 语法：?. 安全调用 + ?: 空合并；获取地址，取不到则用 unknown
            Log.i(TAG, "客户端已断开: $addr")  // 语法：字符串模板；打印断开日志
            onClientDisconnected?.invoke(addr)  // 语法：?. 安全调用；通知断开回调
        }  // 结束 finally 块
    }  // 结束 handleClient 函数

    /**
     * 处理客户端上报的数据包
     *
     * 目前只处理 0x20 客户端信息包：比对 exe 编译版本与 APK 期望的 Rust 版本，
     * 不一致说明 Winlator 里的 exe 没有更新，需要重新导出并复制。
     */
    private fun handleClientPacket(packet: ByteArray) {  // 语法：private fun 私有函数；处理客户端上报的数据包
        if (packet[0] != MSG_CLIENT_VERSION) return  // 只处理客户端信息包
        val clientVersion = "${packet[1]}.${packet[2]}.${packet[3]}"  // 拼成 主.次.修订 版本号
        if (clientVersion != BuildConfig.RUST_CLIENT_VERSION) {  // 与 APK 期望版本比对
            Log.w(TAG, "客户端 exe 版本不一致: 实际 $clientVersion, 期望 ${BuildConfig.RUST_CLIENT_VERSION}")  // 打印版本不一致警告
            onClientVersionMismatch?.invoke(clientVersion)  // 通知外部提示用户重新导出客户端
        } else {  // 版本一致
            Log.i(TAG, "客户端 exe 版本已是最新: $clientVersion")  // 打印版本一致日志
        }  // 结束版本比对
    }  // 结束 handleClientPacket 函数

    /**
     * 消息分发循环：将队列中的消息发送给所有已连接的客户端
     *
     * 用 [LinkedBlockingQueue] 阻塞等待替代空转轮询（原来的 `Thread.sleep(1)` 会持续
     * 唤醒 CPU，与模拟器争抢核心）。取到首包后一次性抽干队列，把积压的定长包合并成
     * 单次 write + flush，减少 TCP 系统调用次数。
     */
    private fun dispatchLoop() {  // 语法：private fun 私有函数；消息分发循环
        while (isRunning.get()) {  // 语法：while 循环；运行期间持续分发消息
            try {  // 尝试处理队列
                // 阻塞等待首个数据包（最长 200ms），队列为空时不占用 CPU
                val first = messageQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue  // 语法：?: 空值合并 + continue；超时无包则回到循环条件判断
                // 抽干当前队列中已积压的包（通常 1~2 个），合并为一次发送
                val batch = ArrayList<ByteArray>()  // 语法：val + ArrayList；本批待发送的数据包
                batch.add(first)  // 先加入首包
                messageQueue.drainTo(batch)  // 一次性取走队列中剩余的全部包
                val merged = ByteArray(batch.size * PACKET_SIZE)  // 合并缓冲区（所有包均为定长 PACKET_SIZE）
                var offset = 0  // 写入偏移量
                for (p in batch) {  // 语法：for 循环；逐个拷贝到合并缓冲区
                    System.arraycopy(p, 0, merged, offset, p.size)  // 语法：System.arraycopy；数组拷贝
                    offset += p.size  // 累加偏移
                }  // 结束 for 循环
                // 发送给所有客户端（合并后单次 write + flush）
                val deadClients = mutableListOf<ClientConnection>()  // 语法：val + 泛型；记录发送失败的客户端
                for (client in clients) {  // 语法：for 循环；遍历所有客户端
                    try {  // 尝试发送
                        client.send(merged)  // 向该客户端发送合并后的数据
                    } catch (e: IOException) {  // 语法：catch 捕获 IO 异常
                        deadClients.add(client)  // 发送失败则记入待清理列表
                    }  // 结束 try-catch
                }  // 结束 for 循环
                // 清理断开的客户端
                if (deadClients.isNotEmpty()) {  // 语法：if 条件判断；存在失败客户端时
                    deadClients.forEach { c ->  // 语法：forEach + 显式参数 c；遍历失败客户端
                        c.close()  // 关闭该客户端
                        clients.remove(c)  // 从列表移除
                    }  // 结束 forEach lambda
                }  // 结束 if 块
            } catch (e: InterruptedException) {  // 语法：catch 捕获中断异常；线程被中断时
                break  // 退出分发循环
            } catch (e: Exception) {  // 语法：catch 捕获其他异常
                Log.e(TAG, "分发消息异常", e)  // 打印分发异常日志
            }  // 结束 catch 块
        }  // 结束 while 循环
    }  // 结束 dispatchLoop 函数

    /**
     * 客户端连接封装
     */
    private class ClientConnection(val socket: Socket) {  // 语法：private class 私有类 + 构造属性；客户端连接封装
        /** 自增连接ID（仅用于线程命名/日志区分，替代不可靠的 hashCode） */
        val id: Int = NEXT_ID.getAndIncrement()  // 语法：val + AtomicInteger 自增；取全局自增 ID
        // 语法：val + try 表达式 + ?可空；获取输出流，失败则为 null
        private val outputStream: OutputStream? = try { socket.getOutputStream() } catch (e: IOException) { null }
        @Volatile var closed: Boolean = false  // 语法：@Volatile 注解 + var 变量；连接关闭标志（多线程可见）

        fun send(data: ByteArray) {  // 语法：fun 函数；向客户端发送数据
            if (closed) throw IOException("连接已关闭")  // 语法：if + throw 抛出异常；已关闭则抛异常
            outputStream?.write(data)  // 语法：?. 安全调用；写入数据
            outputStream?.flush()  // 语法：?. 安全调用；刷新输出流
        }  // 结束 send 函数

        fun close() {  // 语法：fun 函数；关闭连接
            closed = true  // 置位关闭标志
            try { socket.close() } catch (e: IOException) {}  // 语法：try-catch + 空 catch 块；关闭套接字，忽略异常
        }  // 结束 close 函数

        companion object {  // 语法：companion object 伴生对象（类级静态成员容器）
            private val NEXT_ID = AtomicInteger(0)  // 语法：val + AtomicInteger；全局自增 ID 生成器
        }  // 结束 companion object
    }  // 结束 ClientConnection 类
}  // 结束 InputBridgeServer 类
