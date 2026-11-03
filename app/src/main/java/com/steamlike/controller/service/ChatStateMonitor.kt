package com.steamlike.controller.service  // 包声明：服务层（与悬浮窗服务同包，便于内部使用）

import android.content.Context  // 导入 Context（获取系统服务/屏幕尺寸）
import android.content.Intent  // 导入 Intent（承载截屏授权结果）
import android.graphics.PixelFormat  // 导入 PixelFormat（ImageReader 像素格式）
import android.hardware.display.DisplayManager  // 导入 DisplayManager（创建虚拟显示）
import android.hardware.display.VirtualDisplay  // 导入 VirtualDisplay（屏幕镜像虚拟显示）
import android.media.ImageReader  // 导入 ImageReader（读取镜像帧）
import android.media.projection.MediaProjection  // 导入 MediaProjection（截屏会话）
import android.media.projection.MediaProjectionManager  // 导入 MediaProjectionManager（创建截屏会话）
import android.os.Handler  // 导入 Handler（采样定时器）
import android.os.HandlerThread  // 导入 HandlerThread（后台采样线程）
import android.os.SystemClock  // 导入 SystemClock（单调时钟，用于日志限频）
import android.util.Log  // 导入 Log（日志）
import java.nio.ByteBuffer  // 导入 ByteBuffer（像素缓冲）

/**
 * 聊天框状态监视器
 *
 * 通过 MediaProjection 把屏幕缩小镜像到 [ImageReader]，每 150ms 扫描两处区域，
 * 识别 WoW 插件 `ChatStateBeacon` 画的色块信标：
 * - 右上角（正常模式，插件锚定 UIParent 右上角）
 * - 屏幕中央（调试模式，插件锚定 UIParent 中央）
 * - 绿色 = 聊天输入框打开；红色 = 聊天输入框关闭
 *
 * ## 性能设计（低配/高分辨率设备的关键）
 * - 镜像按 1/[SCALE] 缩放，像素量直接降为 1/4
 * - **整行批量拷贝**：每行一次性 `get(byte[])`，只有每行 1 次 JNI 调用，
 *   避免逐像素 `get(int)`（那是最主要的开销来源）
 * - **隔点采样**：区域内按 [STEP] 像素间隔取样，色块 10px 级别仍能命中
 * - **短路扫描**：先扫右上角，命中即返回；未命中才扫中央（调试模式才需要）
 */
class ChatStateMonitor(  // 语法：class 声明类；聊天框信标监视器
    private val context: Context,  // 语法：val 构造参数；用于获取系统服务
    private val resultCode: Int,  // 语法：val 构造参数；截屏授权结果码
    private val resultData: Intent  // 语法：val 构造参数；截屏授权数据（含令牌）
) {  // 结束构造参数列表

    companion object {  // 语法：companion object 伴生对象（类级常量）
        private const val TAG = "ChatStateMonitor"  // 日志标签

        const val STATE_UNKNOWN = 0  // 状态：未识别到信标
        const val STATE_OPEN = 1  // 状态：聊天框打开
        const val STATE_CLOSED = 2  // 状态：聊天框关闭

        private const val SCALE = 2  // 镜像缩放比（1/2 分辨率，兼顾像素精度与开销）
        private const val REGION = 100  // 单个扫描区域边长（镜像图坐标）
        private const val STEP = 2  // 采样步长（每隔 2 像素取一次，横纵都跳）
        private const val MIN_HITS = 3  // 判定所需的最少同色采样点数
        private const val INTERVAL_MS = 150L  // 采样间隔（毫秒）
        private const val CHANNEL_TOL = 90  // 颜色通道容差（低于该值视为该通道关闭）
        private const val HIGH_CHANNEL = 255 - CHANNEL_TOL  // 判定「亮」阈值（165 以上）
        private const val DEBUG_LOG_INTERVAL_MS = 1000L  // 调试日志打印间隔（毫秒）
    }  // 结束 companion object

    /** 当前聊天框状态（STATE_UNKNOWN/STATE_OPEN/STATE_CLOSED） */
    @Volatile  // 语法：@Volatile 注解；跨线程可见
    var state: Int = STATE_UNKNOWN  // 语法：var + 类型；当前状态
        private set  // 语法：private set；只允许内部修改

    private val thread = HandlerThread("ChatBeacon").also { it.start() }  // 语法：also{} 作用域函数；启动后台线程
    private val handler = Handler(thread.looper)  // 绑定后台线程的 Handler

    private var projection: MediaProjection? = null  // 截屏会话
    private var virtualDisplay: VirtualDisplay? = null  // 虚拟显示（屏幕镜像）
    private var reader: ImageReader? = null  // 帧读取器
    private var released = false  // 是否已释放

    private var lastDebugLogAt = 0L  // 上次调试日志时间（SystemClock 毫秒）

    /** 行缓冲（复用，避免每帧分配；大小够一行区域即可） */
    private val rowBytes = ByteArray(REGION * 4)  // 语法：val + ByteArray 构造；一行区域的最大字节数
    private val cornerCounts = Counts()  // 右上角计数（复用对象）
    private val centerCounts = Counts()  // 中央计数（复用对象）

    private val tick = object : Runnable {  // 语法：object 表达式；实现 Runnable 定时采样
        override fun run() {  // 每次采样回调
            if (released) return  // 已释放则不再采样
            sample()  // 读取一帧并识别信标
            handler.postDelayed(this, INTERVAL_MS)  // 继续下一次采样
        }  // 结束 run 函数
    }  // 结束匿名 Runnable

    /**
     * 启动截屏与采样
     * @return true=启动成功
     */
    fun start(): Boolean {  // 语法：fun 返回 Boolean；启动监视
        if (reader != null) return true  // 已在运行
        return try {  // try 表达式：捕获创建失败
            val metrics = context.resources.displayMetrics  // 屏幕参数
            val width = (metrics.widthPixels / SCALE).coerceAtLeast(1)  // 镜像宽度
            val height = (metrics.heightPixels / SCALE).coerceAtLeast(1)  // 镜像高度
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)  // 创建帧读取器（双缓冲）

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager  // 截屏管理器
            projection = manager.getMediaProjection(resultCode, resultData)  // 创建截屏会话
            if (projection == null) {  // 授权数据无效
                Log.w(TAG, "getMediaProjection returned null")  // 打印失败日志
                reader?.close()  // 关闭读取器
                reader = null  // 清空引用
                return false  // 返回失败
            }

            virtualDisplay = projection?.createVirtualDisplay(  // 创建虚拟显示并开始镜像
                "ChatBeacon",  // 显示名称
                width,  // 宽度
                height,  // 高度
                metrics.densityDpi,  // 密度
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,  // 自动镜像屏幕
                reader?.surface,  // 输出到读取器
                null,  // 无状态回调
                handler  // 回调线程
            )  // 结束 createVirtualDisplay
            handler.post(tick)  // 开始定时采样
            Log.i(TAG, "started, image=${width}x$height")  // 打印启动日志
            true  // 返回成功
        } catch (t: Throwable) {  // 捕获异常（权限/系统限制等）
            Log.e(TAG, "start failed", t)  // 打印失败日志
            release()  // 清理已创建资源
            false  // 返回失败
        }  // 结束 try-catch
    }  // 结束 start 函数

    /** 释放截屏与线程资源 */
    fun release() {  // 语法：fun 函数；停止监视并释放
        released = true  // 标记已释放
        handler.removeCallbacksAndMessages(null)  // 取消所有定时任务
        try { virtualDisplay?.release() } catch (_: Throwable) { }  // 释放虚拟显示
        try { reader?.close() } catch (_: Throwable) { }  // 关闭帧读取器
        try { projection?.stop() } catch (_: Throwable) { }  // 结束截屏会话
        virtualDisplay = null  // 清空引用
        reader = null  // 清空引用
        projection = null  // 清空引用
        thread.quitSafely()  // 退出后台线程
        Log.i(TAG, "released")  // 打印释放日志
    }  // 结束 release 函数

    /** 区域像素计数（绿/红） */
    private class Counts {  // 语法：嵌套类；统计结果
        var green = 0  // 绿色采样点数
        var red = 0  // 红色采样点数
        fun reset() { green = 0; red = 0 }  // 语法：多语句分号；重置
        override fun toString(): String = "green=$green red=$red"  // 语法：表达式函数体；日志拼接
    }  // 结束 Counts 类

    /**
     * 统计指定矩形区域内接近纯绿/纯红的采样点数
     *
     * 逐行整块拷贝到 [rowBytes] 后按 [STEP] 采样，JNI 调用次数为「行数」而不是「像素数」。
     * 先用绿通道做廉价早退（绝大多数游戏像素绿通道不亮），命中才继续读红/蓝通道。
     */
    private fun countRegion(  // 语法：private fun；区域计数
        buffer: ByteBuffer,  // 像素缓冲
        rowStride: Int,  // 行跨度
        pixelStride: Int,  // 像素跨度
        x0: Int,  // 起点 X（含）
        x1: Int,  // 终点 X（不含）
        y0: Int,  // 起点 Y（含）
        y1: Int,  // 终点 Y（不含）
        out: Counts  // 输出计数
    ) {  // 结束参数列表
        out.reset()  // 清空计数
        if (pixelStride <= 0 || x1 <= x0 || y1 <= y0) return  // 参数异常直接返回

        val cols = x1 - x0  // 区域列数
        val rowLen = cols * pixelStride  // 一行区域内字节数
        if (rowLen > rowBytes.size) return  // 缓冲不够（理论上不会发生）

        var y = y0  // 行号
        while (y < y1) {  // 逐行（行内隔点采样，行方向不跳行以保持对齐简单）
            val rowStart = y * rowStride + x0 * pixelStride  // 行数据起点偏移
            if (rowStart + rowLen <= buffer.limit()) {  // 边界保护
                buffer.position(rowStart)  // 定位到行起点
                buffer.get(rowBytes, 0, rowLen)  // 整行批量拷贝（1 次 JNI 调用）

                var i = 0  // 行内字节索引
                val stepBytes = pixelStride * STEP  // 每步跨过的字节数
                while (i + pixelStride <= rowLen) {  // 遍历采样点
                    val g = rowBytes[i + 1].toInt() and 0xFF  // 绿通道
                    if (g >= HIGH_CHANNEL) {  // 绿亮：只可能是绿色信标
                        val r = rowBytes[i].toInt() and 0xFF  // 红通道
                        if (r < CHANNEL_TOL) {  // 红低 → 接近纯绿
                            val b = rowBytes[i + 2].toInt() and 0xFF  // 蓝通道
                            if (b < CHANNEL_TOL) out.green++  // 计入绿色
                        }  // 结束绿色判断
                    } else {  // 绿不亮：检查红色分支（廉价早退）
                        val r = rowBytes[i].toInt() and 0xFF  // 红通道
                        if (r >= HIGH_CHANNEL) {  // 红亮
                            if (g < CHANNEL_TOL) {  // 绿低
                                val b = rowBytes[i + 2].toInt() and 0xFF  // 蓝通道
                                if (b < CHANNEL_TOL) out.red++  // 计入红色
                            }  // 结束红色判断
                        }  // 结束红亮判断
                    }  // 结束绿通道分支
                    i += stepBytes  // 跳到下一个采样点
                }  // 结束行内循环
            }  // 结束边界判断
            y += STEP  // 隔行采样
        }  // 结束行循环
    }  // 结束 countRegion 函数

    /** 按绿/红采样点数判定状态（无法判定时返回 UNKNOWN） */
    private fun decide(green: Int, red: Int): Int {  // 语法：private fun；状态判定
        return when {  // 语法：when 表达式；按条件分支
            green >= MIN_HITS && green > red -> STATE_OPEN  // 绿色占优 → 打开
            red >= MIN_HITS && red > green -> STATE_CLOSED  // 红色占优 → 关闭
            else -> STATE_UNKNOWN  // 无法判定
        }  // 结束 when
    }  // 结束 decide 函数

    /** 读取一帧，先扫右上角（命中即短路），必要时再扫中央，更新状态并打印调试日志 */
    private fun sample() {  // 语法：private fun；采样与识别
        val image = reader?.acquireLatestImage() ?: return  // 无新帧则跳过（?: 空值提前返回）
        try {  // 保证帧被关闭
            val plane = image.planes[0]  // 第一个平面（RGBA）
            val buffer = plane.buffer  // 像素缓冲
            val rowStride = plane.rowStride  // 行跨度
            val pixelStride = plane.pixelStride  // 像素跨度
            val width = image.width  // 镜像宽度
            val height = image.height  // 镜像高度

            // 1) 右上角区域（正常模式色块就在这里，命中就不用扫中央）
            val xCorner = (width - REGION).coerceAtLeast(0)  // 右上角起点 X
            countRegion(buffer, rowStride, pixelStride, xCorner, width, 0, REGION.coerceAtMost(height), cornerCounts)  // 统计右上角
            var next = decide(cornerCounts.green, cornerCounts.red)  // 先判定
            var centerScanned = false  // 中央区域是否扫描过
            var centerNote = "skipped"  // 日志里的中央区域说明

            // 2) 未命中（可能是调试模式把色块挪到了中央）才扫中央
            if (next == STATE_UNKNOWN) {  // 右上角没识别到
                val xc0 = ((width - REGION) / 2).coerceAtLeast(0)  // 中央起点 X
                val xc1 = (xc0 + REGION).coerceAtMost(width)  // 中央终点 X
                val yc0 = ((height - REGION) / 2).coerceAtLeast(0)  // 中央起点 Y
                val yc1 = (yc0 + REGION).coerceAtMost(height)  // 中央终点 Y
                countRegion(buffer, rowStride, pixelStride, xc0, xc1, yc0, yc1, centerCounts)  // 统计中央
                centerScanned = true  // 标记已扫描
                centerNote = centerCounts.toString()  // 日志中输出中央计数
                val green = cornerCounts.green + centerCounts.green  // 绿色合计
                val red = cornerCounts.red + centerCounts.red  // 红色合计
                next = decide(green, red)  // 再次判定
            }  // 结束中央扫描
            next = if (next == STATE_UNKNOWN) state else next  // 仍未识别 → 保持上次状态

            val now = SystemClock.elapsedRealtime()  // 当前单调时钟
            if (next != state) {  // 状态变化时记录
                state = next  // 更新状态
                Log.i(TAG, "chat state -> ${if (next == STATE_OPEN) "打开" else "关闭"} (corner[$cornerCounts] center[$centerNote])")  // 打印状态变化
            } else if (now - lastDebugLogAt >= DEBUG_LOG_INTERVAL_MS) {  // 每秒打印一次调试信息
                lastDebugLogAt = now  // 记录打印时间
                Log.d(TAG, "debug: state=$state corner[$cornerCounts] center[$centerNote] scanned=$centerScanned image=${width}x$height")  // 打印调试明细
            }  // 结束日志判断
        } catch (t: Throwable) {  // 捕获解码异常
            Log.w(TAG, "sample failed", t)  // 打印采样失败
        } finally {  // 无论成功与否
            image.close()  // 关闭帧，避免阻塞生产者
        }  // 结束 try-catch-finally
    }  // 结束 sample 函数
}  // 结束 ChatStateMonitor 类
