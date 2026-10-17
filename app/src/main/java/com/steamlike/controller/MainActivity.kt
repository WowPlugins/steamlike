package com.steamlike.controller // 声明包名，与本文件所在目录对应

import android.app.ActivityManager // 导入 ActivityManager，用于查询正在运行的服务
import android.app.AppOpsManager // 导入 AppOpsManager，用于检查“使用情况访问”权限
import android.content.BroadcastReceiver // 导入广播接收器基类
import android.content.ContentResolver // 导入内容解析器，用于 MediaStore 查询/删除
import android.content.ContentUris // 导入 ContentUris，用于拼接带 ID 的 content URI
import android.content.Context // 导入 Context 上下文
import android.content.Intent // 导入 Intent，组件跳转/通信
import android.content.IntentFilter // 导入 IntentFilter，广播过滤器
import android.content.pm.PackageManager // 导入 PackageManager，权限检查常量
import android.net.Uri // 导入 Uri 统一资源标识符
import android.os.Build // 导入 Build，判断系统版本
import android.os.Bundle // 导入 Bundle，Activity 状态传递
import android.os.Environment // 导入 Environment，获取公共目录
import android.os.Process // 导入 Process，获取当前进程 UID
import android.provider.MediaStore // 导入 MediaStore，媒体文件数据库
import android.provider.Settings // 导入 Settings，系统设置（悬浮窗权限等）
import android.util.Log // 导入 Log，日志输出
import android.view.View // 导入 View 基类（控制控件可见性）
import android.view.WindowManager // 导入 WindowManager，窗口布局参数
import android.widget.AdapterView // 导入 AdapterView 适配器视图基类（下拉选择监听）
import android.widget.ArrayAdapter // 导入 ArrayAdapter 数组适配器（下拉框选项）
import android.widget.Button // 导入 Button 按钮控件
import android.widget.EditText // 导入 EditText 输入框控件
import android.widget.LinearLayout // 导入 LinearLayout 线性布局
import android.widget.ScrollView // 导入 ScrollView 滚动视图
import android.widget.Spinner // 导入 Spinner 下拉选择器控件
import android.widget.Switch // 导入 Switch 开关控件
import android.widget.TextView // 导入 TextView 文本控件
import android.widget.Toast // 导入 Toast 提示
import androidx.activity.result.contract.ActivityResultContracts // 导入 Activity Result 契约（SAF 文件选择器）
import androidx.core.content.ContextCompat // 导入 ContextCompat 兼容工具（启动前台服务）
import androidx.appcompat.app.AppCompatActivity // 导入 AppCompatActivity 兼容基类
import com.steamlike.controller.config.AppConfig // 导入运行时配置数据类
import com.steamlike.controller.config.AppConfigStore // 导入运行时配置读写类
import com.steamlike.controller.config.ConfigManager // 导入配置管理器
import com.steamlike.controller.config.ControllerConfig // 导入控制器配置类（JSON 序列化）
import com.steamlike.controller.core.ControllerDevice // 导入手柄设备类（读取手柄电量）
import com.steamlike.controller.core.ControllerProfile // 导入控制器配置文件（操作集/图层）
import com.steamlike.controller.core.GlobalSettings // 导入全局设置（右摇杆参数）
import com.steamlike.controller.service.ControllerOverlayService // 导入悬浮窗服务
import com.steamlike.controller.ui.UiKit // 导入 UI 工具类
import java.io.File // 导入 File 文件类
import java.io.FileOutputStream // 导入 FileOutputStream 文件输出流

class MainActivity : AppCompatActivity() { // 声明主界面 Activity，继承 AppCompatActivity（语法：class 声明类并继承）

    private lateinit var statusText: TextView // 运行状态文本控件（语法：lateinit 延迟初始化变量）
    private lateinit var startButton: Button // 启动手柄映射按钮
    private lateinit var overlayButton: Button // 悬浮窗权限按钮
    private lateinit var configStatusText: TextView // 配置状态文本控件
    private lateinit var connectionStatusText: TextView // 客户端连接状态文本控件
    /** 手柄电量显示文本（Android 14+ 可读取，无法读取时隐藏） */
    private lateinit var batteryStatusText: TextView // 手柄电量显示文本（语法：lateinit 延迟初始化变量）
    /** 智能暂停开关 */
    private lateinit var smartPauseSwitch: Switch // 智能暂停开关控件（语法：lateinit 延迟初始化变量）
    /** 捕获白名单输入框 */
    private lateinit var whitelistEditText: EditText // 捕获白名单输入框
    /** 使用情况访问授权入口按钮 */
    private lateinit var usageStatsButton: Button // “使用情况访问”授权入口按钮
    /** 使用情况访问授权状态文本 */
    private lateinit var usageStatsStatusText: TextView // “使用情况访问”授权状态文本
    /** 手柄捕获开关（与悬浮窗暂停/恢复双向同步） */
    private lateinit var captureSwitch: Switch // 手柄捕获开关控件
    /** 捕获状态显示文本 */
    private lateinit var captureStatusText: TextView // 捕获状态显示文本
    /** 悬浮窗"游戏"按钮拉起应用包名输入框 */
    private lateinit var launcherEditText: EditText // 悬浮窗“游戏”按钮拉起应用包名输入框
    /** 游戏 EXE 路径显示文本 */
    private lateinit var gameExeText: TextView // 游戏 EXE 路径显示文本
    /** 游戏 EXE 历史路径下拉框（选择历史路径直接切换） */
    private var gameExeSpinner: Spinner? = null // var=可变变量；?=可空类型；历史路径下拉控件引用
    /** 抑制捕获开关监听标志（程序化同步状态时避免循环触发） */
    private var suppressCaptureListener = false // 抑制捕获开关监听标志（语法：var 可变变量）
    /** TCP监听地址输入框 */
    private lateinit var hostEditText: EditText // TCP 监听地址输入框
    /** TCP监听端口输入框 */
    private lateinit var portEditText: EditText // TCP 监听端口输入框
    /** 主界面滚动容器（用于保存/恢复滚动位置） */
    private lateinit var mainScroll: ScrollView // 主界面滚动容器（保存/恢复滚动位置）

    // ====================================================================
    // SAF（Storage Access Framework）文件选择器
    // ====================================================================
    // 使用 AndroidX Activity Result API 注册文件选择器，
    // 替代已废弃的 startActivityForResult。
    //
    // 导出: CreateDocument → 用户选择保存位置 → 获取 content:// URI
    // 导入: OpenDocument   → 用户选择配置文件 → 获取 content:// URI
    //
    // 获取 URI 后，通过 Intent 将操作转发给 ControllerOverlayService 执行。
    // ====================================================================

    /**
     * SAF 创建文档启动器（用于导出配置）
     *
     * 点击"导出配置"按钮时调用 [launch]，弹出系统文件选择器让用户选择保存位置。
     * 用户选择后，回调中获取 content:// URI，发送导出 Intent 给服务。
     *
     * MIME 类型设为 "application/json"，文件名建议为 "steamlike_config.json"。
     */
    private val createDocumentLauncher = registerForActivityResult( // 注册“创建文档”启动器（语法：val 只读变量 + 回调注册）
        ActivityResultContracts.CreateDocument("application/json") // 创建 JSON 文档的契约
    ) { uri: Uri? -> // 回调 lambda，接收用户选择的 URI（语法：lambda 回调，参数 uri 可空）
        if (uri != null) { // URI 非空说明用户已选择保存位置（语法：if 条件判断 + != 非空比较）
            // 用户已选择保存位置，发送导出请求给服务
            sendConfigIntent(ControllerOverlayService.ACTION_EXPORT_CONFIG, uri) // 发送导出配置 Intent 给服务
        } // 结束 uri 非空判断
    } // 结束创建文档回调 lambda

    /**
     * SAF 打开文档启动器（用于导入配置）
     *
     * 点击"导入配置"按钮时调用 [launch]，弹出系统文件选择器让用户选择配置文件。
     * 用户选择后，回调中获取 content:// URI，发送导入 Intent 给服务。
     *
     * 支持的 MIME 类型: application/json, text/plain, 以及通配符（兼容性）
     */
    private val openDocumentLauncher = registerForActivityResult( // 注册“打开文档”启动器（语法：val 只读变量 + 回调注册）
        ActivityResultContracts.OpenDocument() // 打开文档的契约（不指定 MIME）
    ) { uri: Uri? -> // 回调 lambda，接收用户选择的配置 URI（语法：lambda 回调）
        if (uri != null) { // 用户选择了配置文件（语法：if 判断）
            // 用户已选择配置文件，发送导入请求给服务
            sendConfigIntent(ControllerOverlayService.ACTION_IMPORT_CONFIG, uri) // 发送导入配置 Intent 给服务
        } // 结束 uri 非空判断
    } // 结束打开文档回调 lambda

    /**
     * SAF 打开文档启动器（选择游戏 EXE 路径）
     *
     * 用户从 Download 目录选择游戏 exe，解析为 Android 路径后
     * 转换为 Winlator 内部路径（Download 映射为 D 盘）保存到配置。
     */
    private val openExeLauncher = registerForActivityResult( // 注册“选择游戏 EXE”启动器（语法：val 只读变量 + 回调注册）
        ActivityResultContracts.StartActivityForResult() // 以结果形式启动 Activity 的契约
    ) { result -> // 回调 lambda，接收 Activity 结果（语法：lambda 回调）
        val uri = result.data?.data // 从结果 Intent 中取 URI（语法：val + ?. 安全调用）
        if (result.resultCode == RESULT_OK && uri != null) { // 结果正常且 URI 非空（语法：&& 逻辑与）
            handleExeSelection(uri) // 处理选中的游戏 EXE 文件
        } // 结束结果判断
    } // 结束 EXE 选择回调 lambda

    override fun onCreate(savedInstanceState: Bundle?) { // 覆写 Activity 创建回调（语法：override 覆写 + fun 函数声明）
        super.onCreate(savedInstanceState) // 调用父类创建逻辑（语法：super 调用父类）

        // 挖孔屏横屏兜底设置（本机对系统装饰避让无效，主方案是用自定义标题栏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // 系统版本 >= Android 9 时执行（语法：if 条件分支）
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES // 布局延伸到挖孔屏短边
        } // 结束挖孔屏设置 if

        // 状态栏深色（与深色界面一致，图标为浅色）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { // 系统版本 >= Android 5.0 时执行（语法：if 条件分支）
            window.statusBarColor = 0xFF1C1C1C.toInt() // 设置状态栏背景为深色
        } // 结束状态栏颜色设置 if

        // 加载当前运行时配置（随配置文件持久化）
        val appCfg = AppConfigStore.load(this) // 读取运行时配置（语法：val 只读变量）

        // 根布局：垂直方向，深色背景，上方为固定标题，下方为可滚动内容区
        val root = LinearLayout(this).apply { // 创建根线性布局并进入 apply 作用域（语法：lambda 接收者 apply）
            orientation = LinearLayout.VERTICAL // 布局方向为垂直
            UiKit.applyDarkBackground(this, this@MainActivity) // 应用深色背景（语法：this@MainActivity 限定外层 Activity）
            // Android 15+ 强制 edge-to-edge：内容会绘制到状态栏下方，
            // 开启 fitsSystemWindows 让根布局自动按状态栏高度加 padding，避免标题重叠
            fitsSystemWindows = true // 让布局自动避开系统栏区域
        } // 结束根布局 apply 块

        // ===== 固定头部（不随页面滚动，样式与使用说明页一致）=====
        val header = LinearLayout(this).apply { // 创建头部线性布局并进入 apply 作用域
            orientation = LinearLayout.VERTICAL // 头部垂直排列
            setPadding( // 设置头部内边距
                UiKit.dp(this@MainActivity, 20), // 左内边距 20dp
                UiKit.dp(this@MainActivity, 20), // 上内边距 20dp
                UiKit.dp(this@MainActivity, 20), // 右内边距 20dp
                UiKit.dp(this@MainActivity, 8) // 下内边距 8dp
            ) // 结束 setPadding 调用
        } // 结束头部布局 apply 块
        header.addView(UiKit.bigTitle(this, "SteamLike 手柄控制器")) // 添加大标题
        header.addView(UiKit.caption( // 添加副标题
            this, // 上下文参数
            "WoW 乌龟服 1.18.1 · 手柄 → 键鼠桥接（Winlator）", // 副标题文字
            0xFF888888.toInt(), 13f // 灰色文字、13sp 字号
        )) // 结束 caption 调用
        // 版本号：与 build.gradle.kts 的 versionName 保持同步（修改版本时递增最后一位小版本号）
        header.addView(UiKit.caption( // 添加版本号显示
            this, // 上下文参数
            "版本 v${BuildConfig.VERSION_NAME}", // 版本号文字（语法：字符串模板 ${}）
            0xFF7A8CFF.toInt(), 12f // 蓝色文字、12sp 字号
        )) // 结束 caption 调用
        root.addView(header) // 把头部加入根布局

        val scroll = ScrollView(this).apply { // 创建滚动视图并进入 apply 作用域
            // 背景色由 root 统一设置，ScrollView 自身无需重复设置
        } // 结束滚动视图 apply 块
        mainScroll = scroll // 保存滚动视图引用（用于恢复滚动位置）
        val container = LinearLayout(this).apply { // 创建内容容器并进入 apply 作用域
            orientation = LinearLayout.VERTICAL // 内容垂直排列
            setPadding( // 设置内容容器内边距
                UiKit.dp(this@MainActivity, 20), // 左内边距 20dp
                UiKit.dp(this@MainActivity, 8), // 上内边距 8dp
                UiKit.dp(this@MainActivity, 20), // 右内边距 20dp
                UiKit.dp(this@MainActivity, 32) // 下内边距 32dp
            ) // 结束 setPadding 调用
        } // 结束内容容器 apply 块
        scroll.addView(container) // 把内容容器加入滚动视图
        // 内容区占满剩余高度，可独立滚动
        root.addView(scroll, LinearLayout.LayoutParams( // 把滚动视图加入根布局并指定布局参数
            LinearLayout.LayoutParams.MATCH_PARENT, // 宽度充满父容器
            0, // 高度为 0（由权重分配）
            1f // 权重为 1，占满剩余高度
        )) // 结束 LayoutParams 调用
        setContentView(root) // 设置根布局为界面内容

        // ===== 运行状态卡片 =====
        val statusCard = UiKit.card(this) // 创建“运行状态”卡片
        statusCard.addView(UiKit.sectionTitle(this, "运行状态")) // 添加卡片标题
        statusCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距
        statusText = UiKit.caption(this, "", 0xFFDDDDDD.toInt(), 13f) // 初始化运行状态文本（淡色、13sp）
        statusCard.addView(statusText) // 把状态文本加入卡片
        statusCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        // 连接状态：固定两行高度，服务广播更新文本时不改变卡片高度，避免页面自动滚动
        connectionStatusText = UiKit.caption(this, "未启动", 0xFFAAAAAA.toInt(), 13f).apply { // 创建连接状态文本并进入 apply 作用域
            minLines = 2 // 最小两行（占位固定高度）
            maxLines = 2 // 最大两行
            ellipsize = android.text.TextUtils.TruncateAt.END // 超长时末尾省略号
        } // 结束连接状态文本 apply 块
        statusCard.addView(connectionStatusText) // 把连接状态文本加入卡片
        // 手柄电量（Android 14+ 才能读取；读取在后台线程完成后再显示，未连接手柄时该行隐藏）
        batteryStatusText = UiKit.caption(this, "", 0xFF8BC34A.toInt(), 13f).apply { // 初始化手柄电量文本（浅绿色、13sp）
            visibility = View.GONE // 读取完成前先隐藏，避免出现空行
        } // 结束 apply 块
        statusCard.addView(batteryStatusText) // 把手柄电量文本加入卡片
        statusCard.addView(UiKit.spacer(this, 10)) // 添加 10dp 间距
        // 悬浮窗权限按钮
        overlayButton = UiKit.button(this, "授予悬浮窗权限", { // 创建悬浮窗权限按钮（语法：lambda 点击回调）
            if (!Settings.canDrawOverlays(this@MainActivity)) { // 尚未授予悬浮窗权限时（语法：if + ! 取反）
                val intent = Intent( // 构建跳转系统悬浮窗权限页的 Intent
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, // 悬浮窗权限管理页面动作
                    Uri.parse("package:$packageName") // 携带本应用包名（语法：字符串模板）
                ) // 结束 Intent 构造
                startActivity(intent) // 打开系统悬浮窗权限设置页
            } // 结束权限判断
        }, UiKit.Style.PRIMARY) // 按钮使用主色调样式
        statusCard.addView(overlayButton) // 把按钮加入卡片
        container.addView(statusCard) // 把状态卡片加入内容容器

        // ===== 智能暂停卡片 =====
        val smartCard = UiKit.card(this) // 创建“智能暂停”卡片
        smartCard.addView(UiKit.sectionTitle(this, "智能暂停（修复右滑返回失效）")) // 添加卡片标题
        smartCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        smartCard.addView(UiKit.caption( // 添加说明文字
            this, // 上下文参数
            ("焦点窗口会拦截 Android 13+ 的右滑返回手势。白名单应用（如 Winlator）在前台时保持手柄捕获，\n" // 说明第一段
                + "切到其他应用自动暂停捕获，右滑返回恢复正常。需要授权\"使用情况访问\"，\n" // 说明第二段（字符串拼接）
                + "以上设置随配置文件一并保存/导出。"), // 说明第三段
            0xFF999999.toInt(), 11f // 灰色文字、11sp 字号
        )) // 结束 caption 调用
        smartCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 智能暂停开关
        smartPauseSwitch = UiKit.switchRow(this, "启用智能暂停", appCfg.smartPauseEnabled) { checked -> // 创建智能暂停开关行（语法：lambda 回调，参数为开关状态）
            updateUsageStatsStatus() // 刷新授权状态显示
            toastLog(if (checked) "智能暂停已开启（切出游戏自动暂停捕获）" else "智能暂停已关闭（手动模式）") // 弹出开关结果提示（语法：if/else 表达式）
        } // 结束智能暂停开关回调
        smartCard.addView(smartPauseSwitch) // 把开关加入卡片

        // 捕获开关（与悬浮窗暂停/恢复按钮双向同步）
        captureSwitch = UiKit.switchRow(this, "手柄捕获", appCfg.captureEnabled) { checked -> // 创建手柄捕获开关行（语法：lambda 回调）
            if (!suppressCaptureListener) setCaptureSwitch(checked) // 非抑制状态时同步捕获开关（语法：if 判断）
        } // 结束捕获开关回调
        smartCard.addView(captureSwitch) // 把捕获开关加入卡片

        // 捕获状态显示（实时接收服务广播同步）
        captureStatusText = UiKit.caption(this, "", 0xFF999999.toInt(), 11f) // 初始化捕获状态文本
        smartCard.addView(captureStatusText) // 把捕获状态文本加入卡片
        smartCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 白名单输入框（支持多个包名，逗号分隔）
        smartCard.addView(UiKit.label(this, "捕获白名单（支持多个包名，逗号分隔）")) // 添加白名单标签
        whitelistEditText = UiKit.input( // 创建白名单输入框
            this, // 上下文参数
            "如: com.winlator, com.winlator.hub", // 输入框提示文字
            appCfg.captureWhitelist.joinToString(",") // 用逗号拼接白名单包名作为初始值
        ) // 结束 input 调用
        smartCard.addView(whitelistEditText) // 把输入框加入卡片
        smartCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 拉起应用包名（悬浮窗"游戏"按钮使用）
        smartCard.addView(UiKit.label(this, "悬浮窗\"游戏\"按钮拉起的应用包名")) // 添加包名标签
        launcherEditText = UiKit.input(this, "如: com.winlator", appCfg.launcherPackage) // 创建拉起应用包名输入框
        smartCard.addView(launcherEditText) // 把输入框加入卡片
        smartCard.addView(UiKit.spacer(this, 8)) // 添加 8dp 间距

        // 使用情况访问授权入口
        usageStatsButton = UiKit.button(this, "授权使用情况访问", { // 创建授权入口按钮（语法：lambda 点击回调）
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) // 跳转到使用情况访问设置页
        }) // 结束按钮回调
        smartCard.addView(usageStatsButton) // 把按钮加入卡片
        usageStatsStatusText = UiKit.caption(this, "", 0xFF999999.toInt(), 11f) // 初始化授权状态文本
        smartCard.addView(usageStatsStatusText) // 把授权状态文本加入卡片
        container.addView(smartCard) // 把智能暂停卡片加入内容容器

        // ===== TCP 服务器配置卡片 =====
        val serverCard = UiKit.card(this) // 创建“TCP 服务器配置”卡片
        serverCard.addView(UiKit.sectionTitle(this, "TCP 服务器配置")) // 添加卡片标题
        serverCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 地址和端口输入框（水平布局）
        val hostPortLayout = LinearLayout(this).apply { // 创建地址/端口水平布局并进入 apply 作用域
            orientation = LinearLayout.HORIZONTAL // 水平方向排列
        } // 结束水平布局 apply 块
        hostEditText = UiKit.input(this, "监听地址 (如 0.0.0.0)", appCfg.serverHost).apply { // 创建地址输入框并进入 apply 作用域
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f) // 宽度 0，权重 2（占 2/3）
        } // 结束地址输入框 apply 块
        portEditText = UiKit.input(this, "端口", appCfg.serverPort.toString()).apply { // 创建端口输入框并进入 apply 作用域
            inputType = android.text.InputType.TYPE_CLASS_NUMBER // 输入类型为数字
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) // 宽度 0，权重 1（占 1/3）
        } // 结束端口输入框 apply 块
        hostPortLayout.addView(hostEditText) // 地址输入框加入水平布局
        hostPortLayout.addView(portEditText) // 端口输入框加入水平布局
        serverCard.addView(hostPortLayout) // 水平布局加入卡片
        serverCard.addView(UiKit.spacer(this, 8)) // 添加 8dp 间距

        // 启动服务按钮
        startButton = UiKit.button(this, "启动手柄映射", { // 创建启动按钮（语法：lambda 点击回调）
            val host = hostEditText.text.toString().trim() // 读取并去除首尾空白的监听地址（语法：val + 链式调用）
            val portStr = portEditText.text.toString().trim() // 读取并去除首尾空白的端口字符串
            val port = portStr.toIntOrNull() ?: 27015 // 端口转整数，失败时默认 27015（语法：?: 空值合并）
            // 保存全部运行时配置到配置文件（随配置一并导入/导出）
            saveRuntimeConfig() // 保存运行时配置
            val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply { // 构建启动服务 Intent 并进入 apply 作用域
                putExtra(ControllerOverlayService.EXTRA_HOST, host) // 携带监听地址
                putExtra(ControllerOverlayService.EXTRA_PORT, port) // 携带监听端口
                putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, smartPauseSwitch.isChecked) // 携带智能暂停开关状态
                putExtra(ControllerOverlayService.EXTRA_WHITELIST, whitelistEditText.text.toString().trim()) // 携带捕获白名单
                putExtra(ControllerOverlayService.EXTRA_LAUNCHER_PACKAGE, launcherEditText.text.toString().trim()) // 携带拉起应用包名
            } // 结束 Intent apply 块
            ContextCompat.startForegroundService(this@MainActivity, intent) // 启动前台服务
            // 连接状态由服务广播(ACTION_CLIENT_STATUS)实时更新，不在此手动改文本（避免布局高度变化导致滚动）
            toastLog("✅ 服务已启动！监听 ${host.ifBlank { "0.0.0.0" }}:$port") // 弹出启动成功提示（语法：字符串模板 + ifBlank）
            // 收起软键盘并清除输入框焦点，避免布局因 IME 弹出/收起而自动滚动
            hostEditText.clearFocus() // 清除地址框焦点
            portEditText.clearFocus() // 清除端口框焦点
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager) // 获取输入法管理器（语法：as? 安全类型转换）
                ?.hideSoftInputFromWindow(this@MainActivity.currentFocus?.windowToken, 0) // 隐藏软键盘（语法：?. 安全调用）
            logD("Start button clicked, host=$host port=$port") // 输出启动日志
        }, UiKit.Style.PRIMARY) // 按钮使用主色调样式
        serverCard.addView(startButton) // 把启动按钮加入卡片
        serverCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 停止按钮
        serverCard.addView(UiKit.button(this, "停止服务", { // 创建停止服务按钮（语法：lambda 点击回调）
            val intent = Intent(this@MainActivity, ControllerOverlayService::class.java) // 构建服务 Intent
            intent.action = ControllerOverlayService.ACTION_STOP // 设置动作：停止服务
            startService(intent) // 通过 startService 触发停止
            stopService(Intent(this@MainActivity, ControllerOverlayService::class.java)) // 停止服务
            updateUI() // 刷新界面状态
        }, UiKit.Style.DANGER)) // 按钮使用危险色调样式
        container.addView(serverCard) // 把 TCP 卡片加入内容容器

        // ===== 配置管理卡片 =====
        val configCard = UiKit.card(this) // 创建“配置管理”卡片
        configCard.addView(UiKit.sectionTitle(this, "配置管理")) // 添加卡片标题
        configCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距

        // 配置状态
        configStatusText = UiKit.caption(this, "", 0xFFDDDDDD.toInt(), 12f) // 初始化配置状态文本
        configCard.addView(configStatusText) // 把配置状态文本加入卡片
        configCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 导出配置按钮
        configCard.addView(UiKit.button(this, "导出配置", { // 创建导出配置按钮（语法：lambda 点击回调）
            // 先确保服务已启动
            ensureServiceRunning() // 确保映射服务已启动
            // 启动 SAF 创建文档
            createDocumentLauncher.launch("steamlike_config.json") // 弹出系统保存位置选择器
        })) // 结束导出按钮回调
        configCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 导入配置按钮
        configCard.addView(UiKit.button(this, "导入配置", { // 创建导入配置按钮（语法：lambda 点击回调）
            // 先确保服务已启动
            ensureServiceRunning() // 确保映射服务已启动
            // 启动 SAF 打开文档
            openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) // 弹出系统文件选择器（多种 MIME 兼容）
        })) // 结束导入按钮回调
        configCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 重置为默认按钮
        configCard.addView(UiKit.button(this, "重置为默认配置", { // 创建重置按钮（语法：lambda 点击回调）
            ensureServiceRunning() // 确保映射服务已启动
            val intent = Intent(this@MainActivity, ControllerOverlayService::class.java) // 构建服务 Intent
            intent.action = ControllerOverlayService.ACTION_RESET_CONFIG // 设置动作：重置配置
            ContextCompat.startForegroundService(this@MainActivity, intent) // 启动前台服务执行重置
            toastLog("正在重置...") // 弹出提示
            // 延迟刷新UI
            configStatusText.postDelayed({ updateConfigStatus() }, 1000) // 延迟 1 秒刷新配置状态（语法：lambda 回调 + postDelayed）
        }, UiKit.Style.DANGER)) // 按钮使用危险色调样式
        configCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 操作层设置按钮（不启动手柄映射服务；LayerEditActivity 直接读写配置文件）
        configCard.addView(UiKit.button( // 添加“层与操作集设置”按钮
            this, // 上下文参数
            "层与操作集设置", // 按钮文字
            onClick = { // 点击回调（语法：lambda + 命名参数）
                startActivity(Intent(this@MainActivity, LayerEditActivity::class.java)) // 跳转到层编辑界面
            }, // 结束点击回调
            style = UiKit.Style.PRIMARY // 按钮使用主色调样式
        )) // 结束按钮创建
        configCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 使用说明按钮（跳转到独立的 HelpActivity 帮助文档页面）
        configCard.addView(UiKit.button( // 添加“使用说明”按钮
            this, // 上下文参数
            "📖 使用说明", // 按钮文字
            onClick = { // 点击回调（语法：lambda）
                startActivity(Intent(this@MainActivity, HelpActivity::class.java)) // 跳转到帮助文档页面
            } // 结束点击回调
        )) // 结束按钮创建
        container.addView(configCard) // 把配置管理卡片加入内容容器

        // ===== 右摇杆优化设置卡片 =====
        val lookCard = UiKit.card(this) // 创建“右摇杆优化设置”卡片
        lookCard.addView(UiKit.sectionTitle(this, "右摇杆优化设置")) // 添加卡片标题
        lookCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        lookCard.addView(UiKit.caption( // 添加设置说明文字
            this, // 上下文参数
            ("右摇杆控制鼠标视角时，可通过以下参数调节手感。\n" // 说明第一段
                + "• 觉得滑动过快 → 降低「灵敏度」或提高「加速曲线指数」\n" // 说明第二段
                + "• 觉得不够流畅/抖动 → 提高「平滑系数」(0.5~0.8 推荐)\n" // 说明第三段
                + "• 摇杆居中时仍在漂移 → 提高「死区」(0.1~0.25)\n" // 说明第四段
                + "• 轻推难以精确瞄准 → 降低「加速曲线指数」靠近 1.0"), // 说明第五段
            0xFF999999.toInt(), 11f // 灰色文字、11sp 字号
        )) // 结束 caption 调用
        lookCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距

        // 读取当前设置值
        val currentSettings = getCurrentProfile().globalSettings // 获取当前生效的全局设置（语法：val 只读变量）

        // 死区
        val deadzoneEdit = makeSettingsEdit( // 创建设置项：死区
            title = "死区 (Deadzone)", // 设置项标题（语法：命名参数）
            hint = "0.0 ~ 1.0，默认 0.15", // 输入框提示
            desc = ("小于此值的摇杆输入会被视为零输入，消除摇杆中心漂移。\n" // 设置说明第一段
                + "推荐: 0.10~0.25。摇杆容易漂移可调高；摇杆精准可调低。"), // 设置说明第二段
            value = currentSettings.deadzone // 当前死区值
        ) // 结束 makeSettingsEdit 调用
        lookCard.addView(deadzoneEdit.first) // 把死区设置区块加入卡片（Pair 的第一个元素）

        // 视角灵敏度
        val sensitivityEdit = makeSettingsEdit( // 创建设置项：视角灵敏度
            title = "右摇杆视角灵敏度 (Look Sensitivity)", // 设置项标题
            hint = "0.1 ~ 5.0，默认 0.5", // 输入框提示
            desc = ("右摇杆控制视角/鼠标移动的整体速度倍率。\n" // 设置说明第一段
                + "数值越大移动越快。觉得滑动过快请降低此值(如 0.3)；过慢可提高(如 0.8)。"), // 设置说明第二段
            value = currentSettings.lookSensitivity // 当前灵敏度值
        ) // 结束 makeSettingsEdit 调用
        lookCard.addView(sensitivityEdit.first) // 把灵敏度设置区块加入卡片

        // 平滑系数
        val smoothingEdit = makeSettingsEdit( // 创建设置项：平滑系数
            title = "视角平滑系数 (Look Smoothing)", // 设置项标题
            hint = "0.0 ~ 0.95，默认 0.5", // 输入框提示
            desc = ("指数移动平均(EMA)滤波系数，降低摇杆抖动让移动更顺滑。\n" // 设置说明第一段
                + "0=关闭平滑(最跟手但有抖动)，越大越顺滑但延迟增加。\n" // 设置说明第二段
                + "推荐: 0.3~0.7。觉卡顿降一点，觉延迟降一点。"), // 设置说明第三段
            value = currentSettings.lookSmoothing // 当前平滑系数值
        ) // 结束 makeSettingsEdit 调用
        lookCard.addView(smoothingEdit.first) // 把平滑设置区块加入卡片

        // 加速曲线指数
        val accelerationEdit = makeSettingsEdit( // 创建设置项：加速曲线指数
            title = "视角加速曲线指数 (Look Acceleration)", // 设置项标题
            hint = "0.5 ~ 3.0，默认 1.5", // 输入框提示
            desc = ("非线性响应曲线。1.0=线性(轻推重推一致)；\n" // 设置说明第一段
                + ">1.0 轻推更慢、重推更快(利于精确瞄准又保证转向速度)；\n" // 设置说明第二段
                + "<1.0 轻推更快、重推相对变慢。\n" // 设置说明第三段
                + "觉得轻推过快难以瞄准 → 提高此值(如 1.8~2.2)。"), // 设置说明第四段
            value = currentSettings.lookAcceleration // 当前加速指数值
        ) // 结束 makeSettingsEdit 调用
        lookCard.addView(accelerationEdit.first) // 把加速设置区块加入卡片

        // 光标速度倍率
        val cursorSpeedEdit = makeSettingsEdit( // 创建设置项：光标速度倍率
            title = "光标移动速度倍率 (Cursor Speed)", // 设置项标题
            hint = "默认 1.0", // 输入框提示
            desc = ("左摇杆/其他鼠标移动场景的速度倍率(右摇杆视角由灵敏度控制)。\n" // 设置说明第一段
                + ">1.0 更快，<1.0 更慢。一般保持 1.0 即可。"), // 设置说明第二段
            value = currentSettings.cursorSpeed // 当前光标速度值
        ) // 结束 makeSettingsEdit 调用
        lookCard.addView(cursorSpeedEdit.first) // 把光标速度设置区块加入卡片
        lookCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 保存按钮
        lookCard.addView(UiKit.button( // 添加“保存右摇杆设置”按钮
            this, // 上下文参数
            "保存右摇杆设置", // 按钮文字
            onClick = { // 点击回调（语法：lambda）
                val dz = deadzoneEdit.second.text.toString().trim().toFloatOrNull() // 读取死区输入并转 Float（语法：toFloatOrNull 安全转换）
                val sens = sensitivityEdit.second.text.toString().trim().toFloatOrNull() // 读取灵敏度输入并转 Float
                val sm = smoothingEdit.second.text.toString().trim().toFloatOrNull() // 读取平滑输入并转 Float
                val ac = accelerationEdit.second.text.toString().trim().toFloatOrNull() // 读取加速输入并转 Float
                val cs = cursorSpeedEdit.second.text.toString().trim().toFloatOrNull() // 读取光标速度输入并转 Float
                if (dz == null || sens == null || sm == null || ac == null || cs == null) { // 任一输入无效时进入（语法：if + || 逻辑或）
                    toastLog("请填写全部 5 个数值", long = true) // 提示填写全部数值（语法：命名参数）
                } else if (!Settings.canDrawOverlays(this@MainActivity)) { // 未授予悬浮窗权限时进入（语法：else if 分支 + ! 取反）
                    toastLog("请先授予悬浮窗权限并启动服务", long = true) // 提示先授权
                } else { // 以上条件都不满足时进入
                    ensureServiceRunning() // 确保映射服务已启动
                    val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply { // 构建更新设置 Intent 并进入 apply 作用域
                        action = ControllerOverlayService.ACTION_UPDATE_SETTINGS // 设置动作：更新全局设置
                        putExtra(ControllerOverlayService.EXTRA_DEADZONE, dz) // 携带死区值
                        putExtra(ControllerOverlayService.EXTRA_LOOK_SENSITIVITY, sens) // 携带灵敏度值
                        putExtra(ControllerOverlayService.EXTRA_LOOK_SMOOTHING, sm) // 携带平滑值
                        putExtra(ControllerOverlayService.EXTRA_LOOK_ACCELERATION, ac) // 携带加速值
                        putExtra(ControllerOverlayService.EXTRA_CURSOR_SPEED, cs) // 携带光标速度值
                    } // 结束 Intent apply 块
                    ContextCompat.startForegroundService(this@MainActivity, intent) // 启动前台服务执行设置更新
                    toastLog("正在保存右摇杆设置...") // 弹出保存提示
                } // 结束 else 分支
            }, // 结束点击回调
            style = UiKit.Style.PRIMARY // 按钮使用主色调样式
        )) // 结束按钮创建
        container.addView(lookCard) // 把右摇杆优化卡片加入内容容器

        // ===== 长按映射设置卡片 =====
        val longPressCard = UiKit.card(this) // 创建“长按映射设置”卡片
        longPressCard.addView(UiKit.sectionTitle(this, "长按映射设置")) // 添加卡片标题
        longPressCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        longPressCard.addView(UiKit.caption( // 添加设置说明文字
            this, // 上下文参数
            ("在「层与操作集设置」中为某按键配置「长按动作」后，本设置即生效。\n" // 说明第一段
                + "• 按住时长达到阈值 → 触发长按动作；未达到 → 触发短按动作\n" // 说明第二段
                + "• 阈值越小，越容易触发长按\n" // 说明第三段
                + "• 开启下方开关后，未单独配置长按动作的按键，长按松开时会执行其短按动作"), // 说明第四段
            0xFF999999.toInt(), 11f // 灰色文字、11sp 字号
        )) // 结束 caption 调用
        longPressCard.addView(UiKit.spacer(this, 8)) // 添加 8dp 间距

        // 长按判定阈值下拉（档位与 全局设置 longPressThresholdMs 对应）
        longPressCard.addView(UiKit.label(this, "长按判定阈值")) // 添加阈值标签
        longPressCard.addView(UiKit.caption(this, "按住时长达到该值即视为长按（默认 500 ms）", 0xFF999999.toInt(), 10f)) // 添加阈值说明
        longPressCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        val longPressThresholdOptions = listOf(300, 500, 800, 1000) // 阈值档位列表（毫秒）
        val longPressThresholdSpinner = Spinner(this).apply { // 创建阈值下拉框并配置（语法：apply{}=作用域函数）
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, // 设置适配器
                longPressThresholdOptions.map { "${it} ms" }).also { // 显示为「500 ms」形式（语法：map lambda）
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项布局
            } // 结束 also 块
            setSelection(longPressThresholdOptions.indexOf(currentSettings.longPressThresholdMs).coerceAtLeast(0)) // 初始选中当前阈值档位
        } // 结束 Spinner apply 块
        longPressCard.addView(longPressThresholdSpinner) // 把阈值下拉框加入卡片
        longPressCard.addView(UiKit.spacer(this, 8)) // 添加 8dp 间距

        // 长按未映射时回退执行短按动作开关
        val longPressFallbackSwitch = UiKit.switchRow( // 创建回退开关行（语法：return 返回 Switch 控件）
            this, "长按未映射时执行短按动作", currentSettings.longPressFallbackToShort // 标题与当前状态
        ) {} // 空回调，保存时统一读取状态
        longPressCard.addView(longPressFallbackSwitch) // 把回退开关加入卡片
        longPressCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 保存按钮
        longPressCard.addView(UiKit.button( // 添加“保存长按设置”按钮
            this, // 上下文参数
            "保存长按设置", // 按钮文字
            onClick = { // 点击回调（语法：lambda）
                if (!Settings.canDrawOverlays(this@MainActivity)) { // 未授予悬浮窗权限时进入（语法：! 取反）
                    toastLog("请先授予悬浮窗权限并启动服务", long = true) // 提示先授权
                } else { // 已授权时进入
                    ensureServiceRunning() // 确保映射服务已启动
                    val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply { // 构建更新设置 Intent 并进入 apply 作用域
                        action = ControllerOverlayService.ACTION_UPDATE_SETTINGS // 设置动作：更新全局设置
                        putExtra(ControllerOverlayService.EXTRA_LONG_PRESS_THRESHOLD_MS, // 携带长按阈值（语法：putExtra 写入 extra）
                            longPressThresholdOptions[longPressThresholdSpinner.selectedItemPosition]) // 读取选中档位毫秒值
                        putExtra(ControllerOverlayService.EXTRA_LONG_PRESS_FALLBACK, longPressFallbackSwitch.isChecked) // 携带回退开关状态
                    } // 结束 Intent apply 块
                    ContextCompat.startForegroundService(this@MainActivity, intent) // 启动前台服务执行设置更新
                    toastLog("正在保存长按设置...") // 弹出保存提示
                } // 结束 else 分支
            }, // 结束点击回调
            style = UiKit.Style.PRIMARY // 按钮使用主色调样式
        )) // 结束按钮创建
        container.addView(longPressCard) // 把长按映射卡片加入内容容器

        // ===== 鼠标右键模式卡片 =====
        val rightModeCard = UiKit.card(this) // 创建“鼠标右键模式”卡片
        rightModeCard.addView(UiKit.sectionTitle(this, "鼠标右键模式")) // 添加卡片标题
        rightModeCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        rightModeCard.addView(UiKit.caption( // 添加设置说明文字
            this, // 上下文参数
            ("鼠标右键长按（锁定视角）与解锁动作的发送方式：\n" // 说明第一段
                + "• 开启：发送 F11（锁定）/ F12（解锁）按键，由游戏内绑定接收\n" // 说明第二段
                + "• 关闭：直接发送真实鼠标右键按下 / 松开"), // 说明第三段
            0xFF999999.toInt(), 11f // 灰色文字、11sp 字号
        )) // 结束 caption 调用
        rightModeCard.addView(UiKit.spacer(this, 8)) // 添加 8dp 间距

        // 右键发送方式开关（默认发送 F11/F12）
        val rightFunctionKeysSwitch = UiKit.switchRow( // 创建开关行（语法：return 返回 Switch 控件）
            this, "右键锁定 / 解锁发送 F11 / F12", currentSettings.rightButtonUseFunctionKeys // 标题与当前状态
        ) {} // 空回调，保存时统一读取状态
        rightModeCard.addView(rightFunctionKeysSwitch) // 把开关加入卡片
        rightModeCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 保存按钮
        rightModeCard.addView(UiKit.button( // 添加“保存右键模式”按钮
            this, // 上下文参数
            "保存右键模式", // 按钮文字
            onClick = { // 点击回调（语法：lambda）
                if (!Settings.canDrawOverlays(this@MainActivity)) { // 未授予悬浮窗权限时进入（语法：! 取反）
                    toastLog("请先授予悬浮窗权限并启动服务", long = true) // 提示先授权
                } else { // 已授权时进入
                    ensureServiceRunning() // 确保映射服务已启动
                    val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply { // 构建更新设置 Intent 并进入 apply 作用域
                        action = ControllerOverlayService.ACTION_UPDATE_SETTINGS // 设置动作：更新全局设置
                        putExtra(ControllerOverlayService.EXTRA_RIGHT_BUTTON_FUNCTION_KEYS, rightFunctionKeysSwitch.isChecked) // 携带右键 F11/F12 模式开关状态
                    } // 结束 Intent apply 块
                    ContextCompat.startForegroundService(this@MainActivity, intent) // 启动前台服务执行设置更新
                    toastLog("正在保存右键模式...") // 弹出保存提示
                } // 结束 else 分支
            }, // 结束点击回调
            style = UiKit.Style.PRIMARY // 按钮使用主色调样式
        )) // 结束按钮创建
        container.addView(rightModeCard) // 把鼠标右键模式卡片加入内容容器

        // ===== Windows 客户端卡片 =====
        val winCard = UiKit.card(this) // 创建“Windows 客户端”卡片
        winCard.addView(UiKit.sectionTitle(this, "Windows 客户端")) // 添加卡片标题
        winCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距

        // 游戏 EXE 路径（导出后 control.bat 先启动客户端、成功后再启动游戏）
        winCard.addView(UiKit.label(this, "游戏 EXE 路径（Winlator 内完整路径）")) // 添加 EXE 路径标签
        winCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        gameExeText = UiKit.caption( // 创建游戏 EXE 路径显示文本
            this, // 上下文参数
            appCfg.gameExePath.ifBlank { "未设置（control.bat 将只启动输入桥接客户端）" }, // 路径为空时显示提示文字（语法：ifBlank 空值兜底）
            0xFFCCCCCC.toInt(), 12f // 浅灰文字、12sp 字号
        ) // 结束 caption 调用
        winCard.addView(gameExeText) // 把路径文本加入卡片
        winCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距
        // 历史路径下拉：直接选择之前用过的路径（无需再走文件选择器）
        val exeSpinner = Spinner(this).apply { // 创建历史路径下拉框并配置（语法：apply{}=作用域函数）
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener { // 设置选择监听（语法：object : 接口匿名实现）
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) { // 选中某项时
                    val target = parent?.getItemAtPosition(position) as? String ?: return // 取选中路径（语法：as?=安全类型转换）
                    val cur = AppConfigStore.load(this@MainActivity).gameExePath // 读取当前游戏路径
                    if (target == cur) return // 与当前路径一致则无需处理
                    AppConfigStore.save(this@MainActivity, // 保存选中的历史路径
                        AppConfigStore.load(this@MainActivity).copy(gameExePath = target)) // 复制配置并更新游戏路径
                    if (::gameExeText.isInitialized) gameExeText.text = target // 同步界面显示文本
                    toastLog("已选择历史路径: $target") // 弹出切换提示（语法：字符串模板）
                } // 结束 onItemSelected 方法
                override fun onNothingSelected(parent: AdapterView<*>?) {} // 无选中项时不处理（语法：空函数体）
            } // 结束匿名监听对象
        } // 结束 Spinner apply 块
        gameExeSpinner = exeSpinner // 记录控件引用供后续刷新
        winCard.addView(exeSpinner) // 把历史路径下拉框加入卡片
        refreshGameExeSpinner() // 初始填充历史路径（无历史时自动隐藏）
        winCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距
        winCard.addView(UiKit.button(this, "选择游戏 EXE 路径", { // 创建“选择游戏 EXE”按钮（语法：lambda 点击回调）
            // 弹出系统文件选择器，直接定位到 Download 目录
            openExeLauncher.launch(buildOpenExeIntent()) // 启动文件选择器
        })) // 结束按钮回调
        winCard.addView(UiKit.spacer(this, 8)) // 添加 8dp 间距
        winCard.addView(UiKit.button(this, "导出 Windows 客户端到 Download/AControler", { // 创建导出客户端按钮（语法：lambda 点击回调）
            exportFilesToDownload() // 执行导出操作
        })) // 结束按钮回调
        container.addView(winCard) // 把 Windows 客户端卡片加入内容容器

        // ===== 调试卡片 =====
        val debugCard = UiKit.card(this) // 创建“调试”卡片
        debugCard.addView(UiKit.sectionTitle(this, "调试")) // 添加卡片标题
        debugCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距
        debugCard.addView(UiKit.button( // 添加“测试手柄按键”按钮
            this, // 上下文参数
            "测试手柄按键（模拟器调试用）", // 按钮文字
            onClick = { // 点击回调（语法：lambda）
                ensureServiceRunning() // 确保映射服务已启动
                if (LayerEditActivity.steamInputRef == null) { // 服务尚未初始化完成时进入（语法：if 判断）
                    toastLog("服务正在初始化，请稍候...") // 弹出等待提示
                    var waited = 0 // 已等待毫秒数（语法：var 可变变量）
                    val tick = 100 // 每次轮询间隔毫秒数（语法：val 只读变量）
                    val maxWait = 3000 // 最大等待毫秒数
                    configStatusText.postDelayed(object : Runnable { // 延迟执行并传入匿名对象（语法：object : 接口匿名实现）
                        override fun run() { // 覆写 Runnable 的 run 方法（语法：override 覆写）
                            waited += tick // 累加等待时间
                            if (LayerEditActivity.steamInputRef != null) { // 服务初始化完成时进入
                                startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java)) // 跳转到手柄测试界面
                            } else if (waited < maxWait) { // 未完成且未超时时进入（语法：else if 分支）
                                configStatusText.postDelayed(this, tick.toLong()) // 继续延迟轮询
                            } else { // 超时则进入
                                toastLog("服务初始化超时，请重试", long = true) // 提示超时
                            } // 结束超时判断
                        } // 结束 run 方法
                    }, tick.toLong()) // 以 100ms 为周期开始轮询
                } else { // 服务已就绪时进入
                    startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java)) // 直接跳转到手柄测试界面
                } // 结束就绪判断
            } // 结束点击回调
        )) // 结束按钮创建
        container.addView(debugCard) // 把调试卡片加入内容容器

        // 恢复上次退出的滚动位置（上划退出/进程重建后回到原位置）
        mainScroll.post { // 在主线程队列中执行恢复滚动（语法：lambda + post）
            mainScroll.scrollTo( // 滚动到指定位置
                0, getSharedPreferences(SCROLL_PREFS, MODE_PRIVATE).getInt(KEY_SCROLL_Y, 0) // 读取保存的 Y 轴位置
            ) // 结束 scrollTo 调用
        } // 结束 post lambda

        // 请求蓝牙连接权限（Android 12+）：准确检测手柄真实连接状态
        requestBluetoothPermission() // 请求蓝牙连接权限
    } // 结束 onCreate 函数

    // ====================================================================
    // 配置管理辅助方法
    // ====================================================================
    // 这些方法在 Activity 中处理 UI 交互，实际的配置操作由 Service 执行。
    // Activity → Intent → Service 的通信模式确保配置操作在服务生命周期内执行。
    // ====================================================================

    /**
     * 确保映射服务已启动
     *
     * 导出/导入/重置操作需要服务运行中才能执行（服务持有 SteamInput 实例）。
     * 如果服务未启动，此方法会自动启动服务。
     *
     * ## 前置条件
     * - 必须已授予悬浮窗权限（SYSTEM_ALERT_WINDOW）
     * - 未授予权限时显示提示，不启动服务
     *
     * ## 行为
     * - 服务已运行: 无操作（startForegroundService 不会重复创建）
     * - 服务未运行: 启动前台服务
     */
    private fun ensureServiceRunning() { // 确保映射服务已启动的函数（语法：fun 私有函数声明）
        if (!Settings.canDrawOverlays(this)) { // 未授予悬浮窗权限时进入（语法：if + ! 取反）
            toastLog("请先授予悬浮窗权限", long = true) // 弹出提示
            return // 提前返回（语法：return）
        } // 结束权限判断
        // 启动前台服务（如果已启动则不会重复启动，onStartCommand 会再次调用）
        // 保存全部运行时配置到配置文件，并通过 EXTRA 传给服务
        saveRuntimeConfig() // 保存运行时配置
        val cfg = AppConfigStore.load(this) // 重新加载配置（语法：val 只读变量）
        val intent = Intent(this, ControllerOverlayService::class.java).apply { // 构建服务 Intent 并进入 apply 作用域
            putExtra(ControllerOverlayService.EXTRA_HOST, cfg.serverHost) // 携带监听地址
            putExtra(ControllerOverlayService.EXTRA_PORT, cfg.serverPort) // 携带监听端口
            putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, smartPauseSwitch.isChecked) // 携带智能暂停开关状态
            putExtra(ControllerOverlayService.EXTRA_WHITELIST, whitelistEditText.text.toString().trim()) // 携带捕获白名单
            putExtra(ControllerOverlayService.EXTRA_LAUNCHER_PACKAGE, launcherEditText.text.toString().trim()) // 携带拉起应用包名
        } // 结束 Intent apply 块
        ContextCompat.startForegroundService(this, intent) // 启动前台服务
    } // 结束 ensureServiceRunning 函数

    /**
     * 发送配置操作 Intent 到服务
     *
     * 将用户通过 SAF 选择的文件 URI 和操作类型通过 Intent 发送给服务。
     * 服务在 [ControllerOverlayService.onStartCommand] 中接收并执行实际操作。
     *
     * ## 通信流程
     * ```
     * Activity (SAF 回调)
     *      ↓ 构建 Intent (action=EXPORT/IMPORT, extra=URI)
     *      ↓ startForegroundService(intent)
     * Service.onStartCommand()
     *      ↓ 根据 action 分发到 handleExportConfig/handleImportConfig
     *      ↓ 执行配置操作
     *      ↓ Toast 提示结果
     * ```
     *
     * @param action 操作类型:
     *        - [ControllerOverlayService.ACTION_EXPORT_CONFIG] 导出
     *        - [ControllerOverlayService.ACTION_IMPORT_CONFIG] 导入
     * @param uri 用户通过 SAF 选择的文件 URI
     */
    private fun sendConfigIntent(action: String, uri: Uri) { // 发送配置操作 Intent 给服务的函数（语法：fun 私有函数 + 参数）
        if (!Settings.canDrawOverlays(this)) { // 未授予悬浮窗权限时进入
            toastLog("请先授予悬浮窗权限并启动服务", long = true) // 弹出提示
            return // 提前返回
        } // 结束权限判断
        val intent = Intent(this, ControllerOverlayService::class.java).apply { // 构建服务 Intent 并进入 apply 作用域
            this.action = action // 设置操作类型（导出或导入）
            putExtra(ControllerOverlayService.EXTRA_CONFIG_URI, uri) // 携带配置文件 URI
        } // 结束 Intent apply 块
        ContextCompat.startForegroundService(this, intent) // 启动前台服务执行操作
        // 显示操作进行中提示
        toastLog( // 弹出操作中提示
            if (action == ControllerOverlayService.ACTION_EXPORT_CONFIG) "正在导出..." else "正在导入..." // 根据操作类型显示不同文字（语法：if/else 表达式）
        ) // 结束 toastLog 调用
        // 延迟刷新配置状态显示（等待服务完成操作）
        configStatusText.postDelayed({ updateConfigStatus() }, 1000) // 延迟 1 秒刷新配置状态
    } // 结束 sendConfigIntent 函数

    /**
     * 更新配置状态显示
     *
     * 检查内部配置文件是否存在，在 UI 上显示配置状态:
     * - 已加载: 显示文件名和大小
     * - 未加载: 提示使用默认 WoW 预设
     *
     * 在 [onResume] 和配置操作完成后调用。
     */
    private fun updateConfigStatus() { // 更新配置状态显示的函数
        // 配置文件路径: {filesDir}/steamlike_config.json
        val configFile = File(filesDir, "steamlike_config.json") // 定位内部配置文件（语法：val 只读变量）
        val hasConfig = configFile.exists() // 判断配置文件是否存在
        configStatusText.text = if (hasConfig) { // 设置配置状态文本（语法：if/else 表达式）
            "配置文件: 已加载\n路径: ${configFile.name}\n大小: ${configFile.length()} 字节" // 已加载时显示文件名和大小（语法：字符串模板）
        } else { // 未加载分支
            "配置文件: 未加载（使用默认 WoW 预设）" // 未加载时显示提示
        } // 结束 if/else 表达式
    } // 结束 updateConfigStatus 函数

    /**
     * 保存全部运行时配置到配置文件（随配置一并导入/导出）
     *
     * 包含：服务器地址/端口、智能暂停开关、捕获白名单（多个）、
     * 捕获开关、悬浮窗"游戏"按钮拉起应用包名。
     */
    private fun saveRuntimeConfig() { // 保存全部运行时配置到配置文件
        val cfg = AppConfig( // 构建运行时配置对象
            serverHost = hostEditText.text.toString().trim().ifBlank { AppConfig.DEFAULT_HOST }, // 监听地址，为空时用默认值（语法：ifBlank 兜底）
            serverPort = portEditText.text.toString().trim().toIntOrNull() ?: AppConfig.DEFAULT_PORT, // 端口，非法时用默认值（语法：?: 空值合并）
            smartPauseEnabled = smartPauseSwitch.isChecked, // 智能暂停开关状态
            captureWhitelist = AppConfig.parseWhitelist(whitelistEditText.text.toString()), // 解析白名单包名列表
            captureEnabled = captureSwitch.isChecked, // 捕获开关状态
            launcherPackage = launcherEditText.text.toString().trim().ifBlank { AppConfig.DEFAULT_LAUNCHER }, // 拉起应用包名，为空时用默认值
            // 游戏 EXE 路径由"选择游戏 EXE 路径"对话框维护，保存其它设置时保留
            gameExePath = AppConfigStore.load(this).gameExePath // 读取已有游戏路径并保留
        ) // 结束 AppConfig 构造
        AppConfigStore.save(this, cfg) // 持久化配置到文件
    } // 结束 saveRuntimeConfig 函数

    /**
     * 构建打开文件选择器的 Intent
     *
     * 注：不传 EXTRA_INITIAL_URI 跳转 Download——该设备上的系统文件选择器
     * （com.google.android.documentsui）在带初始目录跳转时存在面包屑动画
     * 崩溃 bug（RecyclerView: Tmp detached view），去掉跳转可正常打开选择器。
     */
    private fun buildOpenExeIntent(): Intent { // 构建打开文件选择器的 Intent
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply { // 创建打开文档 Intent 并进入 apply 作用域
            addCategory(Intent.CATEGORY_OPENABLE) // 限定可打开的文件
            type = "*/*" // 允许所有文件类型
        } // 结束 Intent apply 块
    } // 结束 buildOpenExeIntent 函数

    /**
     * 处理用户选择的游戏 EXE 文件
     *
     * 1. 解析 SAF URI 得到 Android 真实路径（须在 Download 目录下）
     * 2. 转换为 Winlator 内部路径：Download 目录映射为 D 盘
     * 3. 保存到配置（导出 control.bat 时嵌入，脚本先启动客户端、成功后再启动游戏）
     *
     * @param uri SAF 返回的文件 URI
     */
    private fun handleExeSelection(uri: Uri) { // 处理用户选择的游戏 EXE 文件
        val androidPath = resolveFileAbsolutePath(uri) // 解析 URI 为 Android 绝对路径（语法：val 只读变量）
        if (androidPath == null) { // 解析失败时进入（语法：if 判断 + == 比较）
            toastLog("无法获取文件路径，请从 Download 目录选择 EXE", long = true) // 提示路径获取失败
            return // 提前返回
        } // 结束解析失败判断
        val downloadDir = Environment.getExternalStoragePublicDirectory( // 获取公共下载目录
            Environment.DIRECTORY_DOWNLOADS // 下载目录类型
        ).absolutePath // 取其绝对路径字符串
        if (!androidPath.startsWith(downloadDir)) { // 所选文件不在 Download 目录时进入（语法：if + ! 取反）
            toastLog("请选择 Download 目录下的 EXE 文件（该目录在 Winlator 中映射为 D 盘）", long = true) // 提示选择位置错误
            return // 提前返回
        } // 结束目录判断
        // 相对 Download 的路径 → D 盘路径
        val rel = androidPath.removePrefix(downloadDir).trimStart('/', '\\') // 去掉 Download 前缀和开头分隔符（语法：链式调用）
        val winPath = "D:\\" + rel.replace('/', '\\') // 转换为 Winlator D 盘路径（语法：字符串拼接）

        val old = AppConfigStore.load(this) // 读取现有运行时配置
        // 更新历史路径：新路径置顶、去重、最多保留 10 条
        val newHistory = (listOf(winPath) + old.gameExeHistory.filter { it != winPath }).take(10) // 语法：listOf 拼接 + filter 过滤 + take 截取前 10
        val cfg = old.copy(gameExePath = winPath, gameExeHistory = newHistory) // 复制配置并更新游戏路径与历史（语法：data class 的 copy 方法）
        AppConfigStore.save(this, cfg) // 持久化配置
        if (::gameExeText.isInitialized) { // 若 gameExeText 已初始化（语法：:: 属性引用 + isInitialized 判断）
            gameExeText.text = winPath // 更新界面显示的路径
        } // 结束初始化判断
        refreshGameExeSpinner() // 刷新历史路径下拉框内容
        toastLog("游戏路径已保存（Winlator D 盘）: $winPath") // 弹出保存成功提示（语法：字符串模板）
    } // 结束 handleExeSelection 函数

    /**
     * 刷新游戏 EXE 历史路径下拉框
     *
     * 读取配置中的历史路径重建下拉内容，并选中当前路径；
     * 历史为空时隐藏该下拉框。
     */
    private fun refreshGameExeSpinner() { // private=私有；fun=函数声明；刷新游戏 EXE 历史下拉
        val spinner = gameExeSpinner ?: return // 控件未创建则直接返回（语法：?:=空值合并）
        val cfg = AppConfigStore.load(this) // 读取运行时配置
        val history = cfg.gameExeHistory // 取历史路径列表
        if (history.isEmpty()) { // 无历史路径时进入（语法：if 判断）
            spinner.visibility = View.GONE // 隐藏下拉框
            return // 提前返回
        } // 结束空判断
        spinner.visibility = View.VISIBLE // 显示下拉框
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, history).also { // 设置适配器（语法：also=作用域函数）
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 设置下拉展开项布局
        } // 结束 also 块
        spinner.setSelection(history.indexOf(cfg.gameExePath).coerceAtLeast(0)) // 选中当前路径（语法：indexOf=查找索引；coerceAtLeast=下限保护）
    } // 结束 refreshGameExeSpinner 函数

    /**
     * 解析 SAF content:// URI 为 Android 文件系统绝对路径
     *
     * 优先用 MediaStore 的 DATA 列；Android 10+ 部分文件无 DATA 时
     * 用 RELATIVE_PATH + DISPLAY_NAME 拼出绝对路径。
     *
     * @param uri 文件 URI
     * @return 绝对路径；解析失败返回 null
     */
    private fun resolveFileAbsolutePath(uri: Uri): String? { // 解析 content URI 为绝对路径，失败返回 null（语法：可空返回类型）
        // 1. 尝试 DATA 列（多数 MediaStore 文件可用）
        contentResolver.query( // 查询 MediaStore
            uri, // 查询的 URI
            arrayOf(MediaStore.MediaColumns.DATA), // 只查询 DATA 列
            null, null, null // 无选择条件、无排序等
        )?.use { cursor -> // 非空时自动关闭游标（语法：?.use 安全调用 + 资源自动关闭）
            if (cursor.moveToFirst()) { // 有数据时进入（语法：if 判断）
                val data = cursor.getString(0) // 读取 DATA 列值（语法：val 只读变量）
                if (!data.isNullOrEmpty() && File(data).exists()) return data // 路径有效且文件存在则直接返回（语法：&& 逻辑与 + 提前 return）
            } // 结束有数据判断
        } // 结束 use 块
        // 2. RELATIVE_PATH + DISPLAY_NAME（Android 10+ Downloads 集合）
        contentResolver.query( // 再次查询 MediaStore
            uri, // 查询的 URI
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), // 查询文件名与相对路径两列
            null, null, null // 无过滤条件
        )?.use { cursor -> // 非空时自动关闭游标
            if (cursor.moveToFirst()) { // 有数据时进入
                val name = cursor.getString(0) ?: return null // 读文件名，为空则返回 null（语法：?: 空值合并 + return）
                var rel = cursor.getString(1) ?: "" // 读相对路径，为空用空串（语法：var 可变变量 + ?: 兜底）
                // RELATIVE_PATH 形如 "Download/AControler/"，去掉开头的 Download/
                rel = rel.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/") // 去掉开头的 Download/ 前缀（语法：字符串模板）
                val downloadDir = Environment.getExternalStoragePublicDirectory( // 获取公共下载目录
                    Environment.DIRECTORY_DOWNLOADS // 下载目录类型
                ).absolutePath // 取其绝对路径
                val file = File(downloadDir, rel + name) // 拼出完整文件路径
                if (file.exists()) return file.absolutePath // 文件存在则返回绝对路径
            } // 结束有数据判断
        } // 结束 use 块
        // 3. file:// 直接路径
        if (uri.scheme == "file") return uri.path // 若是 file:// 协议直接返回路径（语法：if 判断 + return）
        return null // 全部失败返回 null
    } // 结束 resolveFileAbsolutePath 函数

    /**
     * 捕获开关变化 → 持久化 + 通知服务（悬浮窗同步）
     *
     * @param enabled true=恢复捕获, false=暂停捕获
     */
    private fun setCaptureSwitch(enabled: Boolean) { // 捕获开关变化时同步服务与配置（语法：fun 函数 + Boolean 参数）
        saveRuntimeConfig() // 先保存运行时配置
        if (isServiceRunning()) { // 服务正在运行时进入（语法：if 判断）
            val intent = Intent(this, ControllerOverlayService::class.java).apply { // 构建服务 Intent 并进入 apply 作用域
                action = ControllerOverlayService.ACTION_SET_CAPTURE // 设置动作：切换捕获状态
                putExtra(ControllerOverlayService.EXTRA_CAPTURE_ENABLED, enabled) // 携带捕获开关状态
            } // 结束 Intent apply 块
            ContextCompat.startForegroundService(this, intent) // 启动前台服务通知悬浮窗
        } else { // 服务未运行时进入
            toastLog("服务未运行，开关状态已保存（启动手柄映射后生效）") // 提示状态已保存稍后生效
        } // 结束服务运行判断
        logD("Capture switch set to $enabled") // 输出日志（语法：字符串模板）
    } // 结束 setCaptureSwitch 函数

    /**
     * 检查 ControllerOverlayService 是否在运行
     */
    private fun isServiceRunning(): Boolean { // 检查悬浮窗服务是否在运行
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager // 获取 ActivityManager（语法：as 类型转换）
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(100) // 查询最多 100 个运行中的服务
        return services.any { // 判断是否存在匹配服务（语法：lambda + any 集合判断）
            it.service.className == ControllerOverlayService::class.java.name && // 服务类名匹配（语法：&& 逻辑与）
                it.service.packageName == packageName // 服务包名匹配
        } // 结束 any lambda
    } // 结束 isServiceRunning 函数

    /**
     * 从配置文件同步捕获开关状态（onResume 时调用）
     */
    private fun syncCaptureSwitchFromPrefs() { // 从配置文件同步捕获开关状态
        if (!::captureSwitch.isInitialized) return // 控件未初始化则直接返回（语法：:: 属性引用 + 提前 return）
        val enabled = AppConfigStore.load(this).captureEnabled // 读取配置中的捕获开关状态
        suppressCaptureListener = true // 置抑制标志，避免同步触发监听
        captureSwitch.isChecked = enabled // 设置开关状态
        suppressCaptureListener = false // 取消抑制标志
        captureStatusText.text = if (enabled) "捕获状态: ✅ 运行中" else "捕获状态: ⏸ 已暂停（可点悬浮窗恢复）" // 更新捕获状态文本（语法：if/else 表达式）
    } // 结束 syncCaptureSwitchFromPrefs 函数

    /**
     * 检查"使用情况访问"权限是否已授权
     */
    private fun hasUsageStatsPermission(): Boolean { // 检查“使用情况访问”权限是否已授权
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager // 获取 AppOpsManager（语法：as 类型转换）
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 系统版本 >= Android 10 时进入（语法：if 条件分支）
            appOps.unsafeCheckOpNoThrow( // 使用新 API 检查（Android 10+）
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName // 检查使用情况权限项
            ) // 结束检查调用
        } else { // 旧系统版本分支
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow( // 使用旧 API 检查
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName // 检查使用情况权限项
            ) // 结束检查调用
        } // 结束版本分支
        return mode == AppOpsManager.MODE_ALLOWED // 返回是否已允许（语法：== 比较返回布尔）
    } // 结束 hasUsageStatsPermission 函数

    /**
     * 刷新"使用情况访问"授权状态显示
     */
    private fun updateUsageStatsStatus() { // 刷新“使用情况访问”授权状态显示
        if (!::usageStatsStatusText.isInitialized) return // 控件未初始化则直接返回
        val granted = hasUsageStatsPermission() // 判断权限是否已授权
        usageStatsStatusText.text = if (granted) { // 已授权时（语法：if/else 表达式）
            "使用情况访问: ✅ 已授权（智能暂停可用）" // 显示已授权文字
        } else { // 未授权分支
            if (smartPauseSwitch.isChecked) { // 智能暂停开启时进入（语法：if 判断）
                "使用情况访问: ❌ 未授权 — 点击上方按钮前往系统设置开启，否则智能暂停不生效（手动模式）" // 提示需授权才能生效
            } else { // 智能暂停关闭分支
                "使用情况访问: ❌ 未授权（智能暂停已关闭，不影响手动模式）" // 提示不影响手动模式
            } // 结束内层判断
        } // 结束外层 if/else 表达式
    } // 结束 updateUsageStatsStatus 函数

    /**
     * 获取当前生效的 ControllerProfile
     *
     * 数据源优先级:
     * 1. 服务已启动: 读取运行时 [LayerEditActivity.steamInputRef] 的 profile
     * 2. 服务未启动但配置文件存在: 从内部配置文件加载
     * 3. 都没有: 使用代码内置默认 profile
     */
    private fun getCurrentProfile(): ControllerProfile { // 获取当前生效的控制器配置
        LayerEditActivity.steamInputRef?.profile?.let { return it } // 服务已启动则返回运行时配置（语法：?. 安全调用 + let + return）
        val configFile = File(filesDir, "steamlike_config.json") // 定位内部配置文件
        if (configFile.exists()) { // 配置文件存在时进入（语法：if 判断）
            return try { // 尝试读取（语法：try 异常处理 + return）
                ControllerConfig.fromJson(configFile.readText()) // 从 JSON 文本解析配置
            } catch (e: Exception) { // 解析异常时进入（语法：catch 捕获异常）
                ControllerProfile.createDefault() // 返回默认配置
            } // 结束 try-catch
        } // 结束配置文件存在判断
        return ControllerProfile.createDefault() // 无配置文件时返回默认配置
    } // 结束 getCurrentProfile 函数

    /**
     * 创建一个设置项 UI 区块（标题 + 说明 + 输入框）
     *
     * @param title 设置项名称
     * @param hint 输入框提示
     * @param desc 设置项说明文本（多行）
     * @param value 当前值（Float）
     * @return Pair(LinearLayout 视图, EditText 输入框)
     */
    private fun makeSettingsEdit( // 创建设置项 UI 区块的函数
        title: String, // 设置项标题（语法：函数参数）
        hint: String, // 输入框提示
        desc: String, // 设置说明文本
        value: Float // 当前值
    ): Pair<LinearLayout, EditText> { // 返回布局与输入框的组合（语法：Pair 返回类型）
        val layout = LinearLayout(this).apply { // 创建设置项垂直布局并进入 apply 作用域
            orientation = LinearLayout.VERTICAL // 垂直方向
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0) // 顶部留 10dp 内边距
        } // 结束布局 apply 块
        layout.addView(UiKit.label(this, title)) // 添加标题标签
        layout.addView(UiKit.caption(this, desc, 0xFF999999.toInt(), 10f)) // 添加说明文字
        layout.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距
        val edit = UiKit.input(this, hint, value.toString()).apply { // 创建输入框并进入 apply 作用域
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or // 数字输入类型（语法：or 位或运算）
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL // 允许小数
        } // 结束输入框 apply 块
        layout.addView(edit) // 输入框加入布局
        return Pair(layout, edit) // 返回布局与输入框对
    } // 结束 makeSettingsEdit 函数

    // ====================================================================
    // Windows 客户端文件导出
    // ====================================================================
    // 将打包在 APK assets 中的 inputbridge_client.exe 和 control.bat
    // 释放到 Download/AControler 目录，方便用户通过文件管理器或 ADB 取出，
    // 复制到 Winlator 的 C 盘使用。
    //
    // 实现策略:
    // - Android 10+ (API 29+): 使用 MediaStore.Downloads API + RELATIVE_PATH 子目录
    // - Android 9 及以下 (API < 29): 直接写入 Environment.DIRECTORY_DOWNLOADS/AControler
    // ====================================================================

    /** 导出目标子目录名 */
    private val exportDirName = "AControler" // 导出目标子目录名（语法：val 只读变量）

    /** control.bat 模板中游戏 EXE 路径占位符（导出时替换为实际路径） */
    private val GAME_EXE_PLACEHOLDER = "__GAME_EXE__" // 游戏 EXE 路径占位符（语法：val 常量）

    /** 需要导出的文件列表 (assetName → displayName) */
    private val exportFiles = listOf( // 需要导出的文件列表（语法：val + listOf 列表）
        "inputbridge_client.exe" to "inputbridge_client.exe", // 导出 exe（语法：to 创建键值对）
        "control.bat" to "control.bat", // 导出 bat 启动脚本
        "control.ps1" to "control.ps1" // 导出 PowerShell 脚本
    ) // 结束列表

    /**
     * 导出 Windows 客户端文件到 Download/AControler 目录
     *
     * 从 APK 的 assets 中读取 exe、control.bat 和 control.ps1，写入到公共 Download/AControler 目录。
     * control.bat 和 control.ps1 导出时嵌入用户配置的游戏 EXE 路径（脚本先启动客户端，成功后再启动游戏）。
     */
    private fun exportFilesToDownload() { // 导出 Windows 客户端文件到 Download/AControler
        val results = mutableListOf<String>() // 收集各文件导出结果（语法：val + mutableListOf 可变列表）
        val gameExe = AppConfigStore.load(this).gameExePath // 读取游戏 EXE 路径

        for ((assetName, displayName) in exportFiles) { // 遍历待导出文件（语法：for 循环 + 解构键值对）
            // 从 assets 读取文件字节（control.bat 和 control.ps1 模板嵌入游戏路径）
            val bytes = try { // 尝试读取（语法：try 异常处理）
                if (assetName == "control.bat" || assetName == "control.ps1") { // 脚本类文件时进入（语法：if + || 逻辑或）
                    val template = assets.open(assetName).use { it.readBytes().toString(Charsets.UTF_8) } // 读取模板文本（语法：use 自动关闭资源）
                    template.replace(GAME_EXE_PLACEHOLDER, gameExe).toByteArray(Charsets.UTF_8) // 替换占位符后转字节
                } else { // 非脚本文件分支
                    assets.open(assetName).use { it.readBytes() } // 直接读取原始字节
                } // 结束文件类型分支
            } catch (e: Exception) { // 读取异常时进入（语法：catch 捕获异常）
                toastLog("读取 $assetName 失败: ${e.message}", long = true) // 弹出失败提示（语法：字符串模板）
                return // 提前返回
            } // 结束 try-catch

            // 根据 Android 版本选择写入方式（均强制覆盖已存在文件）
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 系统版本 >= Android 10 时（语法：if 条件分支）
                exportViaMediaStore(bytes, displayName) // 用 MediaStore 方式写入
            } else { // 旧系统分支
                exportViaLegacyFile(bytes, displayName) // 用传统文件方式写入
            } // 结束版本分支

            results.add("$displayName: ${if (success) "OK" else "FAIL"} (${bytes.size} bytes)") // 记录本次导出结果（语法：字符串模板 + if 表达式）
        } // 结束 for 循环

        // 额外导出 config.json（含 wowPath），Windows 客户端启动时读取并据此拉起游戏进程
        val configJson = "{\n\t\"wowPath\":${jsonStringValue(gameExe)}\n}" // 生成 config.json 内容（语法：字符串拼接 + 转义符）
        val configBytes = configJson.toByteArray(Charsets.UTF_8) // 转成 UTF-8 字节
        val configSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 系统版本分支（语法：if 条件分支）
            exportViaMediaStore(configBytes, "config.json") // 用 MediaStore 方式写入
        } else { // 旧系统分支
            exportViaLegacyFile(configBytes, "config.json") // 用传统文件方式写入
        } // 结束版本分支
        results.add("config.json: ${if (configSuccess) "OK" else "FAIL"} (${configBytes.size} bytes)") // 记录 config.json 导出结果

        // 汇总提示
        val allSuccess = results.all { it.contains("OK") } // 判断是否全部成功（语法：lambda + all 判断）
        val msg = if (allSuccess) { // 全部成功时（语法：if 条件分支）
            "已导出 ${exportFiles.size + 1} 个文件到 Download/$exportDirName 目录:\n" + // 成功提示第一行
            results.joinToString("\n") { "  $it" } + "\n" + // 拼接各文件结果（语法：joinToString + lambda）
            if (gameExe.isBlank()) { // 游戏路径为空时（语法：if 嵌套表达式）
                "config.json 的 wowPath 为空，Windows 客户端启动时会报错退出。\n请先在“选择游戏 EXE 路径”设置游戏路径后重新导出。" // 提醒需先设置游戏路径
            } else { // 路径非空分支
                "游戏路径已写入 config.json 与 control.bat/control.ps1：$gameExe" // 提示路径已写入
            } // 结束路径判断
        } else { // 有失败时进入
            "导出部分失败:\n" + results.joinToString("\n") { "  $it" } // 拼接失败明细
        } // 结束全部成功判断
        toastLog(msg, long = true) // 弹出汇总提示
    } // 结束 exportFilesToDownload 函数

    /**
     * JSON 字符串值（带引号与转义），用于生成 config.json 中的路径字段
     *
     * Windows 路径中的反斜杠 `\` 会转义为 `\\`，双引号转义为 `\"`，
     * 保证生成的 config.json 是合法 JSON。
     */
    private fun jsonStringValue(s: String): String = buildString { // 生成 JSON 字符串值（语法：表达式体函数 + buildString）
        append('"') // 追加开引号
        for (c in s) { // 遍历每个字符（语法：for 循环）
            when (c) { // 按字符分支处理（语法：when 分支语句）
                '\\' -> append("\\\\") // 反斜杠转义为两个反斜杠
                '"' -> append("\\\"") // 双引号转义
                '\n' -> append("\\n") // 换行转义
                '\r' -> append("\\r") // 回车转义
                '\t' -> append("\\t") // 制表符转义
                '\b' -> append("\\b") // 退格转义
                '\u000C' -> append("\\f") // 换页符转义
                else -> append(c) // 其他字符原样追加
            } // 结束 when 分支
        } // 结束 for 循环
        append('"') // 追加收尾引号
    } // 结束 jsonStringValue 函数

    /**
     * 通过 MediaStore.Downloads 写入文件到子目录 (Android 10+)，强制覆盖同名文件
     *
     * 使用 MediaStore API 写入公共 Download/AControler 目录，无需申请存储权限。
     *
     * @param bytes 文件字节数组
     * @param displayName 显示文件名
     * @return true=写入成功
     */
    private fun exportViaMediaStore(bytes: ByteArray, displayName: String): Boolean { // 通过 MediaStore 写入文件（Android 10+）
        return try { // 尝试执行（语法：try 异常处理）
            val resolver = contentResolver // 获取内容解析器
            // 带尾斜杠的目录路径（MediaStore RELATIVE_PATH 规范格式为 "Download/AControler/"）
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$exportDirName/" // 拼接相对路径（语法：字符串模板）

            // 强制覆盖：先删除 AControler 目录下所有同名旧记录，再插入新文件
            deleteMediaStoreDownload(resolver, displayName) // 删除同名旧记录

            val values = android.content.ContentValues().apply { // 构建插入值并进入 apply 作用域
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) // 设置文件名
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream") // 设置 MIME 类型
                // 指定写入 Downloads/AControler 子目录
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) // 设置相对路径
            } // 结束 ContentValues apply 块
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI // 获取下载集合的 URI
            val uri = resolver.insert(collection, values) ?: run { // 插入记录，失败时执行 run 块（语法：?: 空值合并 + run）
                Log.e(TAG, "MediaStore insert failed for $displayName") // 输出插入失败日志（语法：字符串模板）
                return false // 返回失败
            } // 结束 run 块
            resolver.openOutputStream(uri)?.use { output -> // 打开输出流并自动关闭（语法：?.use 安全调用 + 资源关闭）
                output.write(bytes) // 写入文件字节
                output.flush() // 刷新缓冲区
            } ?: run { // 打开输出流失败时进入（语法：?: 空值合并）
                Log.e(TAG, "openOutputStream failed for $displayName") // 输出打开失败日志
                return false // 返回失败
            } // 结束 run 块
            Log.i(TAG, "Exported $displayName to Download/$exportDirName (${bytes.size} bytes)") // 输出导出成功日志
            true // 返回成功
        } catch (e: Exception) { // 捕获异常（语法：catch 捕获异常）
            Log.e(TAG, "Export $displayName failed", e) // 输出异常日志
            false // 返回失败
        } // 结束 try-catch
    } // 结束 exportViaMediaStore 函数

    /**
     * 删除 Download/AControler 目录下指定文件名的所有 MediaStore 记录
     *
     * 用 `LIKE 'Download/AControler%'` 匹配路径，避免因 RELATIVE_PATH 带/不带尾斜杠、
     * 大小写等差异导致漏删，从而出现同名重复文件（旧文件没被覆盖）。
     * 循环删除直到查询无残留，防止结果集快照与删除交错遗漏。
     */
    private fun deleteMediaStoreDownload(resolver: ContentResolver, displayName: String) { // 删除下载目录下指定文件名的所有记录
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI // 获取下载集合 URI
        while (true) { // 无限循环直到删干净（语法：while 循环）
            var found = false // 本轮是否找到记录（语法：var 可变变量）
            resolver.query( // 查询匹配记录
                collection, // 下载集合
                arrayOf(MediaStore.MediaColumns._ID), // 只查询 ID 列
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " + // 按文件名匹配（语法：字符串拼接）
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", // 且相对路径匹配
                arrayOf(displayName, "Download/$exportDirName%"), // 文件名与路径通配参数
                null // 无排序
            )?.use { cursor -> // 非空时自动关闭游标（语法：?.use 安全调用）
                while (cursor.moveToNext()) { // 遍历查询结果（语法：while 循环）
                    val id = cursor.getLong(0) // 读取记录 ID
                    resolver.delete( // 删除该记录
                        ContentUris.withAppendedId(collection, id), // 拼接带 ID 的 URI
                        null, null // 无条件
                    ) // 结束 delete 调用
                    found = true // 标记本轮回找到并删除
                } // 结束遍历
            } // 结束 use 块
            if (!found) break // 未找到任何记录则跳出循环（语法：if + break 跳出）
        } // 结束 while 循环
    } // 结束 deleteMediaStoreDownload 函数

    /**
     * 通过直接文件写入到子目录 (Android 9 及以下)
     *
     * 直接写入 Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/AControler，
     * 需要 WRITE_EXTERNAL_STORAGE 运行时权限。
     *
     * @param bytes 文件字节数组
     * @param displayName 显示文件名
     * @return true=写入成功
     */
    private fun exportViaLegacyFile(bytes: ByteArray, displayName: String): Boolean { // 通过传统文件方式写入（Android 9 及以下）
        // 检查写入权限
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) // 检查存储写入权限（语法：if 判断）
            != PackageManager.PERMISSION_GRANTED) { // 未授权时进入
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), // 请求写入权限
                REQUEST_WRITE_STORAGE) // 权限请求码
            return false // 返回失败等待授权后重试
        } // 结束权限判断
        return try { // 尝试执行（语法：try 异常处理）
            val downloadDir = Environment.getExternalStoragePublicDirectory( // 获取公共下载目录
                Environment.DIRECTORY_DOWNLOADS // 下载目录类型
            ) // 结束目录获取
            val targetDir = File(downloadDir, exportDirName) // 构造目标子目录
            if (!targetDir.exists()) targetDir.mkdirs() // 目录不存在则创建（语法：if 判断）
            val targetFile = File(targetDir, displayName) // 构造目标文件
            FileOutputStream(targetFile).use { output -> // 打开输出流并自动关闭（语法：use 资源自动关闭）
                output.write(bytes) // 写入文件字节
                output.flush() // 刷新缓冲区
            } // 结束 use 块
            Log.i(TAG, "Exported $displayName to ${targetFile.absolutePath} (${bytes.size} bytes)") // 输出成功日志
            true // 返回成功
        } catch (e: Exception) { // 捕获异常（语法：catch）
            Log.e(TAG, "Export $displayName failed", e) // 输出异常日志
            false // 返回失败
        } // 结束 try-catch
    } // 结束 exportViaLegacyFile 函数

    /**
     * 请求蓝牙连接权限（Android 12+，API 31+）
     *
     * 用于查询手柄蓝牙 HID Host 连接状态，准确检测手柄真实连接状态：手柄关电源/关蓝牙时
     * 及时把悬浮窗图标更新为"未连接"（MIUI 上输入设备条目可能残留，无法仅靠条目判断）。
     *
     * 用户拒绝时功能降级：悬浮窗仅依赖系统输入设备条目判断连接状态，
     * 不影响手柄映射等核心功能。
     */
    private fun requestBluetoothPermission() { // 请求蓝牙连接权限（Android 12+）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // 系统版本低于 Android 12 直接返回（语法：if + return）
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) // 检查蓝牙连接权限
            == PackageManager.PERMISSION_GRANTED) return // 已授权则直接返回
        requestPermissions( // 请求蓝牙连接权限
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), // 权限数组
            REQUEST_BLUETOOTH_CONNECT // 权限请求码
        ) // 结束请求
    } // 结束 requestBluetoothPermission 函数

    companion object { // 伴生对象，存放类级常量（语法：companion object 伴生对象）
        /** 请求 WRITE_EXTERNAL_STORAGE 权限的请求码 (仅 Android 9 及以下使用) */
        private const val REQUEST_WRITE_STORAGE = 1001 // 存储写入权限请求码（语法：const val 编译期常量）
        /** 请求 BLUETOOTH_CONNECT 权限的请求码 (Android 12+ 检测手柄蓝牙连接状态) */
        private const val REQUEST_BLUETOOTH_CONNECT = 1002 // 蓝牙连接权限请求码
        private const val TAG = "SteamLikeUI" // 日志标签（语法：const val 编译期常量）
        /** 主界面滚动位置持久化 */
        private const val SCROLL_PREFS = "main_scroll" // 滚动位置偏好文件名
        private const val KEY_SCROLL_Y = "scroll_y" // 滚动位置键名
        /** Debug用: 启动 MainActivity 时传入此 extra=true 会自动启动服务并跳转到测试页面 */
        const val EXTRA_AUTO_OPEN_TEST = "auto_open_test" // 自动打开测试页的 Intent extra 键（语法：const val 编译期常量）
    } // 结束伴生对象

    // ====================================================================
    // 客户端连接状态广播接收器
    // ====================================================================
    // 接收来自 ControllerOverlayService 的连接状态广播，
    // 在 UI 上实时显示 Windows 客户端的连接/断开状态。
    // ====================================================================

    /**
     * 客户端连接状态广播接收器
     *
     * 接收 [ControllerOverlayService.ACTION_CLIENT_STATUS] 广播，
     * 更新 connectionStatusText 显示连接状态。
     */
    private val clientStatusReceiver = object : BroadcastReceiver() { // 客户端连接状态广播接收器（语法：object : 匿名对象实现接口）
        override fun onReceive(context: Context?, intent: Intent?) { // 覆写广播接收回调（语法：override 覆写 + fun 函数）
            when (intent?.action) { // 按广播动作分发处理（语法：when 分支 + ?. 安全调用）
                ControllerOverlayService.ACTION_CLIENT_STATUS -> { // 客户端连接状态广播
                    val statusText = intent.getStringExtra(ControllerOverlayService.EXTRA_STATUS_TEXT) // 读取状态文本
                        ?: "unknown" // 为空时用 "unknown"（语法：?: 空值合并）
                    val connected = intent.getBooleanExtra(ControllerOverlayService.EXTRA_CONNECTED, false) // 读取是否已连接
                    val displayText = if (connected) { // 已连接时（语法：if 条件分支）
                        "✅ 已连接 Windows 客户端\n$statusText" // 显示已连接（语法：字符串模板）
                    } else { // 未连接分支
                        "⏳ 等待 Windows 客户端连接\n$statusText" // 显示等待连接
                    } // 结束连接判断
                    connectionStatusText.text = displayText // 更新连接状态文本
                    connectionStatusText.setTextColor( // 设置状态文字颜色
                        if (connected) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt() // 已连接为绿色，否则灰色（语法：if/else 表达式）
                    ) // 结束 setTextColor 调用
                    Log.i(TAG, "Connection status: connected=$connected, msg=$statusText") // 输出连接状态日志
                } // 结束连接状态分支
                ControllerOverlayService.ACTION_CAPTURE_STATUS -> { // 捕获状态广播
                    // 悬浮窗暂停/恢复 → 同步 app 内捕获开关
                    val capturing = intent.getBooleanExtra( // 读取是否正在捕获
                        ControllerOverlayService.EXTRA_CAPTURING, true // 默认 true
                    ) // 结束读取
                    if (::captureSwitch.isInitialized) { // 捕获开关已初始化时（语法：:: 属性引用 + isInitialized）
                        suppressCaptureListener = true // 置抑制标志
                        captureSwitch.isChecked = capturing // 同步开关状态
                        suppressCaptureListener = false // 取消抑制标志
                    } // 结束初始化判断
                    if (::captureStatusText.isInitialized) { // 状态文本已初始化时
                        captureStatusText.text = if (capturing) { // 正在捕获时（语法：if/else 表达式）
                            "捕获状态: ✅ 运行中" // 显示运行中
                        } else { // 暂停分支
                            "捕获状态: ⏸ 已暂停（点悬浮窗'恢复捕获'或此开关恢复）" // 显示已暂停
                        } // 结束捕获判断
                    } // 结束初始化判断
                    Log.i(TAG, "Capture status: capturing=$capturing") // 输出捕获状态日志
                } // 结束捕获状态分支
            } // 结束 when 分支
        } // 结束 onReceive 函数
    } // 结束广播接收器对象

    /** 日志辅助方法，所有 UI 操作日志统一输出到 Logcat (tag: SteamLikeUI) */
    private fun logD(msg: String) = Log.d(TAG, msg) // 输出调试日志（语法：表达式体函数）

    /** 日志辅助方法，Toast 同时输出到 Logcat */
    private fun toastLog(msg: String, long: Boolean = false) { // 弹出 Toast 并输出日志（语法：默认参数值）
        Log.i(TAG, "Toast: $msg") // 输出日志（语法：字符串模板）
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() // 弹出 Toast 提示
    } // 结束 toastLog 函数

    override fun onResume() { // 覆写 Activity 恢复回调（语法：override + Activity 生命周期 onResume）
        super.onResume() // 调用父类恢复逻辑（语法：super 调用父类）
        // 注册客户端连接状态广播接收器
        // Android 14+ (API 34+) 要求指定 RECEIVER_EXPORTED 或 RECEIVER_NOT_EXPORTED
        // 此广播仅用于应用内部通信，使用 NOT_EXPORTED
        val filter = IntentFilter(ControllerOverlayService.ACTION_CLIENT_STATUS) // 创建连接状态广播过滤器
        filter.addAction(ControllerOverlayService.ACTION_CAPTURE_STATUS) // 增加捕获状态动作
        ContextCompat.registerReceiver( // 注册广播接收器
            this, clientStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED // 非导出（仅应用内部）
        ) // 结束注册
        logD("onResume: registered client status receiver") // 输出注册日志
        syncCaptureSwitchFromPrefs() // 同步捕获开关状态
        updateUI() // 刷新界面状态
        updateConfigStatus() // 刷新配置状态
        updateUsageStatsStatus() // 刷新授权状态

        // Debug: 自动跳转测试页面
        // 通过 `adb shell am start -n com.steamlike.controller/.MainActivity --ez auto_open_test true` 触发
        if (intent?.getBooleanExtra(EXTRA_AUTO_OPEN_TEST, false) == true) { // 收到自动打开测试页标记时进入（语法：if + ?. 安全调用）
            // 清除 extra 避免重复跳转
            intent.removeExtra(EXTRA_AUTO_OPEN_TEST) // 移除 extra 防止重复跳转
            ensureServiceRunning() // 确保映射服务已启动
            if (LayerEditActivity.steamInputRef != null) { // 服务已就绪时进入（语法：if 判断）
                startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java)) // 直接跳转到手柄测试页
            } else { // 服务未就绪分支
                // 等待服务初始化，最多 5 秒
                var waited = 0 // 已等待毫秒数（语法：var 可变变量）
                val tick = 100 // 轮询间隔（语法：val 只读变量）
                val maxWait = 5000 // 最大等待时间
                configStatusText.postDelayed(object : Runnable { // 延迟执行轮询（语法：object : 匿名接口实现）
                    override fun run() { // 覆写 run 方法（语法：override）
                        waited += tick // 累加等待时间
                        if (LayerEditActivity.steamInputRef != null) { // 服务就绪时进入
                            startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java)) // 跳转手柄测试页
                        } else if (waited < maxWait) { // 未超时继续等待（语法：else if 分支）
                            configStatusText.postDelayed(this, tick.toLong()) // 继续轮询
                        } else { // 超时分支
                            toastLog("服务初始化超时，请检查", long = true) // 提示超时
                        } // 结束超时判断
                    } // 结束 run 方法
                }, tick.toLong()) // 以 100ms 周期开始轮询
            } // 结束服务就绪判断
        } // 结束自动打开标记判断
    } // 结束 onResume 函数

    override fun onPause() { // 覆写 Activity 暂停回调（语法：override + Activity 生命周期 onPause）
        super.onPause() // 调用父类暂停逻辑（语法：super）
        // 注销广播接收器，避免内存泄漏
        unregisterReceiver(clientStatusReceiver) // 注销广播接收器
        // 保存滚动位置（下次进入/进程重建后恢复）
        if (::mainScroll.isInitialized) { // 滚动容器已初始化时（语法：:: 属性引用 + isInitialized）
            getSharedPreferences(SCROLL_PREFS, MODE_PRIVATE).edit() // 打开偏好编辑器（语法：链式调用）
                .putInt(KEY_SCROLL_Y, mainScroll.scrollY).apply() // 保存滚动 Y 坐标
        } // 结束初始化判断
        logD("onPause: unregistered client status receiver") // 输出注销日志
    } // 结束 onPause 函数

    private fun updateUI() { // 刷新主界面状态显示
        val hasOverlay = Settings.canDrawOverlays(this) // 检查是否已授予悬浮窗权限
        val canStart = hasOverlay // 可启动条件即拥有悬浮窗权限

        val status = buildString { // 构建状态文本（语法：buildString 字符串构建器）
            append("悬浮窗权限: ${if (hasOverlay) "✅ 已授予" else "❌ 未授予"}\n") // 追加权限状态（语法：字符串模板 + if 表达式）
            append("就绪状态: ${if (canStart) "✅ 可以启动" else "⚠️ 请先完成上述步骤"}") // 追加就绪状态
        } // 结束 buildString 块
        statusText.text = status // 更新运行状态文本

        overlayButton.isEnabled = !hasOverlay // 已授权时禁用悬浮窗按钮（语法：! 取反）
        startButton.isEnabled = canStart // 可启动时启用启动按钮

        overlayButton.text = if (hasOverlay) "悬浮窗已就绪 ✅" else "授予悬浮窗权限" // 更新按钮文字（语法：if/else 表达式）

        updateBatteryStatus() // 刷新卡片中的手柄电量显示
    } // 结束 updateUI 函数

    /**
     * 刷新主界面的手柄电量显示
     *
     * 使用 [ControllerDevice.readBattery]（Android 14 公开 API）读取，读取在后台线程执行；
     * 系统低于 Android 14 或未连接手柄时隐藏该行，
     * 手柄已连接但报告电量未知时显示"手柄电量: 未知"。
     */
    private fun updateBatteryStatus() { // private=私有；fun=函数声明；刷新手柄电量
        // 读取会阻塞（部分设备约 10 秒），必须在后台线程执行
        Thread { // 启动后台线程（语法：Thread { }.start() 匿名线程）
            val battery = ControllerDevice.readBattery() // 读取当前手柄电量
            if (isFinishing || isDestroyed) return@Thread // 页面已销毁则不再更新（语法：return@Thread 从 lambda 返回）
            runOnUiThread { // 回到主线程更新 UI（语法：runOnUiThread 主线程调度）
                if (battery == null) { // 无法读取电量（无手柄/系统不支持）时（语法：if 分支）
                    batteryStatusText.visibility = View.GONE // 隐藏电量文本行
                } else { // 读到电量信息（可能为"未知"）时
                    batteryStatusText.text = battery.displayText // 显示电量文本（未知时显示"手柄电量: 未知"）
                    batteryStatusText.visibility = View.VISIBLE // 显示电量文本行
                } // 结束分支
            } // 结束 runOnUiThread lambda
        }.start() // 启动线程
    } // 结束 updateBatteryStatus 函数
} // 结束 MainActivity 类
