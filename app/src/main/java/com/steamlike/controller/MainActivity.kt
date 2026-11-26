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
import android.widget.TextView // 导入 TextView 文本控件
import android.widget.Toast // 导入 Toast 提示
import androidx.activity.result.contract.ActivityResultContracts // 导入 Activity Result 契约（SAF 文件选择器）
import androidx.core.content.ContextCompat // 导入 ContextCompat 兼容工具（启动前台服务）
import androidx.appcompat.app.AppCompatActivity // 导入 AppCompatActivity 兼容基类
import com.steamlike.controller.config.AppConfig // 导入运行时配置数据类
import com.steamlike.controller.config.AppConfigStore // 导入运行时配置读写类
import com.steamlike.controller.config.ConfigManager // 导入配置管理器
import com.steamlike.controller.core.ControllerDevice // 导入手柄设备类（读取手柄电量）
import com.steamlike.controller.core.SteamInputHolder // 导入运行中 SteamInput 的全局访问点
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
            // 保存运行时配置（只覆盖本页的地址/端口，其它字段沿用配置文件）
            saveRuntimeConfig() // 保存运行时配置
            val cfg = AppConfigStore.load(this@MainActivity) // 读取最新配置
            val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply { // 构建启动服务 Intent 并进入 apply 作用域
                putExtra(ControllerOverlayService.EXTRA_HOST, host) // 携带监听地址
                putExtra(ControllerOverlayService.EXTRA_PORT, port) // 携带监听端口
                putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, cfg.smartPauseEnabled) // 携带智能暂停开关状态（来自高级设置页）
                putExtra(ControllerOverlayService.EXTRA_WHITELIST, cfg.captureWhitelist.joinToString(",")) // 携带捕获白名单
                putExtra(ControllerOverlayService.EXTRA_LAUNCHER_PACKAGE, cfg.launcherPackage) // 携带拉起应用包名
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

        // ===== Windows 客户端卡片 =====
        val winCard = UiKit.card(this) // 创建“Windows 客户端”卡片
        winCard.addView(UiKit.sectionTitle(this, "Windows 客户端")) // 添加卡片标题
        winCard.addView(UiKit.spacer(this, 6)) // 添加 6dp 间距
        winCard.addView(UiKit.button(this, "导出 Windows 客户端到 Download/AControler", { // 创建导出客户端按钮（语法：lambda 点击回调）
            exportFilesToDownload() // 执行导出操作
        })) // 结束按钮回调
        container.addView(winCard) // 把 Windows 客户端卡片加入内容容器

        // ===== 配置管理卡片 =====
        val configCard = UiKit.card(this) // 创建“配置管理”卡片
        configCard.addView(UiKit.sectionTitle(this, "配置管理")) // 添加卡片标题
        configCard.addView(UiKit.spacer(this, 4)) // 添加 4dp 间距

        // 高级设置入口：智能暂停、摇杆手感、长按阈值、WASD 自动锁定、调试等不常用项集中到二级页面
        configCard.addView(UiKit.button(this, "⚙️ 高级设置", {
            startActivity(Intent(this@MainActivity, AdvancedSettingsActivity::class.java))
        }, UiKit.Style.PRIMARY))
        configCard.addView(UiKit.caption(this, "智能暂停、摇杆手感、长按阈值、WASD 自动锁定、调试", 0xFF999999.toInt(), 10f))
        configCard.addView(UiKit.spacer(this, 10))

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
            putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, cfg.smartPauseEnabled) // 携带智能暂停开关状态（来自高级设置页）
            putExtra(ControllerOverlayService.EXTRA_WHITELIST, cfg.captureWhitelist.joinToString(",")) // 携带捕获白名单
            putExtra(ControllerOverlayService.EXTRA_LAUNCHER_PACKAGE, cfg.launcherPackage) // 携带拉起应用包名
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
     * 保存首页负责的运行时配置（服务器地址/端口）
     *
     * 其它字段（智能暂停、白名单、捕获开关、拉起包名等）在「高级设置」页维护，
     * 这里先读取现有配置再只覆盖地址/端口，避免把高级设置页的改动覆盖回默认值。
     */
    private fun saveRuntimeConfig() { // 保存首页负责的运行时配置
        val cfg = AppConfigStore.load(this).copy( // 先读现有配置，再仅替换地址与端口（语法：copy 复制并修改部分字段）
            serverHost = hostEditText.text.toString().trim().ifBlank { AppConfig.DEFAULT_HOST }, // 监听地址，为空时用默认值
            serverPort = portEditText.text.toString().trim().toIntOrNull() ?: AppConfig.DEFAULT_PORT // 端口，非法时用默认值
        ) // 结束 AppConfig 构造
        AppConfigStore.save(this, cfg) // 持久化配置到文件
    } // 结束 saveRuntimeConfig 函数

    // ====================================================================
    // Windows 客户端文件导出
    // ====================================================================
    // 将打包在 APK assets 中的 inputbridge_client.exe
    // 释放到 Download/AControler 目录，方便用户通过文件管理器或 ADB 取出，
    // 复制到 Winlator 的 C 盘使用。
    //
    // 实现策略:
    // - Android 10+ (API 29+): 使用 MediaStore.Downloads API + RELATIVE_PATH 子目录
    // - Android 9 及以下 (API < 29): 直接写入 Environment.DIRECTORY_DOWNLOADS/AControler
    // ====================================================================

    /** 导出目标子目录名 */
    private val exportDirName = "AControler" // 导出目标子目录名（语法：val 只读变量）

    /** 需要导出的文件列表 (assetName → displayName) */
    private val exportFiles = listOf( // 需要导出的文件列表（语法：val + listOf 列表）
        "inputbridge_client.exe" to "inputbridge_client.exe" // 导出 exe（语法：to 创建键值对）
    ) // 结束列表

    /**
     * 导出 Windows 客户端文件到 Download/AControler 目录
     *
     * 从 APK 的 assets 中读取 exe，写入到公共 Download/AControler 目录。
     */
    private fun exportFilesToDownload() { // 导出 Windows 客户端文件到 Download/AControler
        val results = mutableListOf<String>() // 收集各文件导出结果（语法：val + mutableListOf 可变列表）

        for ((assetName, displayName) in exportFiles) { // 遍历待导出文件（语法：for 循环 + 解构键值对）
            // 从 assets 读取文件字节
            val bytes = try { // 尝试读取（语法：try 异常处理）
                assets.open(assetName).use { it.readBytes() } // 读取原始字节（语法：use 自动关闭资源）
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

        // 额外导出 version.txt（Windows 客户端启动时读取该文件显示版本号，
        // 因此仅提升版本号无需重新编译/导出 exe）
        // 第一行 = APK 版本（界面显示），第二行 = Rust 编译版本（客户端自检 exe 是否过期）
        val versionBytes = "${BuildConfig.VERSION_NAME}\n${BuildConfig.RUST_CLIENT_VERSION}\n".toByteArray(Charsets.UTF_8) // 版本号文本（语法：字符串模板）
        val versionSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 系统版本分支（语法：if 条件分支）
            exportViaMediaStore(versionBytes, "version.txt") // 用 MediaStore 方式写入
        } else { // 旧系统分支
            exportViaLegacyFile(versionBytes, "version.txt") // 用传统文件方式写入
        } // 结束版本分支
        results.add("version.txt: ${if (versionSuccess) "OK" else "FAIL"} (${versionBytes.size} bytes)") // 记录 version.txt 导出结果

        // 汇总提示
        val allSuccess = results.all { it.contains("OK") } // 判断是否全部成功（语法：lambda + all 判断）
        val msg = if (allSuccess) { // 全部成功时（语法：if 条件分支）
            "已导出 ${exportFiles.size + 1} 个文件到 Download/$exportDirName 目录:\n" + // 成功提示第一行（exe+version.txt）
            results.joinToString("\n") { "  $it" } // 拼接各文件结果（语法：joinToString + lambda）
        } else { // 有失败时进入
            "导出部分失败:\n" + results.joinToString("\n") { "  $it" } // 拼接失败明细
        } // 结束全部成功判断
        toastLog(msg, long = true) // 弹出汇总提示
    } // 结束 exportFilesToDownload 函数

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
        ContextCompat.registerReceiver( // 注册广播接收器
            this, clientStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED // 非导出（仅应用内部）
        ) // 结束注册
        logD("onResume: registered client status receiver") // 输出注册日志
        updateUI() // 刷新界面状态
        updateConfigStatus() // 刷新配置状态

        // Debug: 自动跳转测试页面
        // 通过 `adb shell am start -n com.steamlike.controller/.MainActivity --ez auto_open_test true` 触发
        if (intent?.getBooleanExtra(EXTRA_AUTO_OPEN_TEST, false) == true) { // 收到自动打开测试页标记时进入（语法：if + ?. 安全调用）
            // 清除 extra 避免重复跳转
            intent.removeExtra(EXTRA_AUTO_OPEN_TEST) // 移除 extra 防止重复跳转
            ensureServiceRunning() // 确保映射服务已启动
            if (SteamInputHolder.current != null) { // 服务已就绪时进入（语法：if 判断）
                startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java)) // 直接跳转到手柄测试页
            } else { // 服务未就绪分支
                // 等待服务初始化，最多 5 秒
                var waited = 0 // 已等待毫秒数（语法：var 可变变量）
                val tick = 100 // 轮询间隔（语法：val 只读变量）
                val maxWait = 5000 // 最大等待时间
                configStatusText.postDelayed(object : Runnable { // 延迟执行轮询（语法：object : 匿名接口实现）
                    override fun run() { // 覆写 run 方法（语法：override）
                        waited += tick // 累加等待时间
                        if (SteamInputHolder.current != null) { // 服务就绪时进入
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
