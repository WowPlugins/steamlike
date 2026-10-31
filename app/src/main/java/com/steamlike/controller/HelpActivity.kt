package com.steamlike.controller // 语法：package=包声明，声明本文件所属包

import android.os.Build // 语法：import=导入类，Build 用于判断系统版本
import android.os.Bundle // 语法：import=导入类，Bundle=保存 Activity 状态的数据容器
import android.view.WindowManager // 语法：import=导入类，WindowManager 用于配置窗口属性
import android.widget.LinearLayout // 语法：import=导入类，LinearLayout=线性布局容器
import android.widget.ScrollView // 语法：import=导入类，ScrollView=可滚动容器
import android.widget.TextView // 语法：import=导入类，TextView=文本控件
import androidx.appcompat.app.AppCompatActivity // 语法：import=导入类，AppCompatActivity=兼容性 Activity 基类
import com.steamlike.controller.config.ControllerConfig // 语法：import=导入类，ControllerConfig=配置 JSON 读写
import com.steamlike.controller.core.ControllerButton // 语法：import=导入类，ControllerButton=手柄按键枚举
import com.steamlike.controller.core.ControllerProfile // 语法：import=导入类，ControllerProfile=配置文件数据模型
import com.steamlike.controller.core.MappedAction // 语法：import=导入类，MappedAction=映射动作
import com.steamlike.controller.ui.UiKit // 语法：import=导入类，UiKit=统一 UI 样式工具
import java.io.File // 语法：import=导入类，File=文件操作类

/**
 * 使用说明页面
 *
 * 独立的帮助文档页面，与 MainActivity 使用同一套深色卡片 UI 风格（[UiKit]）。
 * 内容根据最新实现维护：
 * - 架构：Android 焦点窗口 + IME 键盘 → TCP(27015) → Windows SendInput 注入
 * - 配置分层：操作集（Action Set）→ 公共层 + 10 个操作层，切换操作集时整体切换
 * - 操作层切换由公共层中的 SwitchLayer 映射驱动（层编辑页的「切入按键」即读写该映射）
 * - 悬浮窗三态图标（🎮未连接 / ▶映射中 / ⏸暂停）
 * - 层按钮短按跳转设置页、右键锁存边框高亮、按键按下高亮反馈等新特性
 *
 * 操作层切换说明根据当前 profile 动态生成（[buildLayerSwitchLines]），
 * 避免文档与用户实际配置脱节。
 */
class HelpActivity : AppCompatActivity() { // 语法：class=类声明，继承 AppCompatActivity（Activity 基类）

    override fun onCreate(savedInstanceState: Bundle?) { // 语法：override=覆写父类方法；fun onCreate=Activity 创建时生命周期回调；Bundle?=可空参数
        super.onCreate(savedInstanceState) // 语法：super=调用父类实现，必须先执行
        // 挖孔屏横屏兜底设置（主方案是自定义标题栏，见 UiKit.titleBar）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // 语法：if 条件判断，Android 9（API 28）及以上才执行
            window.attributes.layoutInDisplayCutoutMode = // 语法：window.attributes=窗口属性，设置挖孔区显示模式
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES // 语法：SHORT_EDGES=仅短边避让挖孔区
        } // 结束 if 版本判断
        buildUi() // 调用构建页面 UI 的方法
    } // 结束 onCreate 函数

    /**
     * 构建使用说明页面 UI（深色卡片风格，可滚动）
     */
    private fun buildUi() { // 语法：private fun=私有函数声明，构建页面 UI
        // 根布局：垂直方向，上方为固定标题，下方为可滚动内容区
        val root = LinearLayout(this).apply { // 语法：val=只读变量 + apply 作用域函数，创建垂直根布局
            orientation = LinearLayout.VERTICAL // 设置布局方向为垂直
            // Android 15+ 强制 edge-to-edge：内容会绘制到状态栏下方，
            // 开启 fitsSystemWindows 让根布局自动按状态栏高度加 padding，避免标题重叠
            fitsSystemWindows = true // 语法：fitsSystemWindows=自动避开系统栏（状态栏），按系统栏高度加内边距
        } // 结束 apply 作用域块
        UiKit.applyDarkBackground(root, this) // 调用 UiKit 为根布局设置深色背景

        // ===== 固定头部（不随页面滚动）=====
        val header = LinearLayout(this).apply { // 语法：val + apply 作用域函数，创建固定头部容器
            orientation = LinearLayout.VERTICAL // 设置头部方向为垂直
            // 语法：setPadding=设置内边距；this@HelpActivity=显式引用外部 Activity；dp 转像素
            setPadding(UiKit.dp(this@HelpActivity, 16), UiKit.dp(this@HelpActivity, 16),
                UiKit.dp(this@HelpActivity, 16), UiKit.dp(this@HelpActivity, 6)) // 四边内边距：左右 16、上 16、下 6
        } // 结束 apply 作用域块
        // 自定义标题栏（替代系统 ActionBar：系统 ActionBar 挖孔屏横屏时避让挖孔不贴边）
        header.addView(UiKit.titleBar(this, "📖 使用说明") { finish() }) // 语法：addView=添加子控件；尾随 Lambda 作 onBack 回调，finish()=关闭当前 Activity
        header.addView(UiKit.spacer(this, 4)) // 标题栏下方添加 4dp 垂直间距
        header.addView(UiKit.caption(this, "SteamLike Controller 使用指南（随版本持续更新）", 0xFF888888.toInt(), 11f)) // 添加副标题说明文字（深灰、11sp）
        root.addView(header) // 把固定头部添加到根布局

        // 可滚动内容区
        val scroll = ScrollView(this).apply { // 语法：val + apply 作用域函数，创建可滚动容器
            isFillViewport = true // 语法：isFillViewport=内容不足时也让 ScrollView 占满视口高度
        } // 结束 apply 作用域块
        val container = LinearLayout(this).apply { // 语法：val + apply 作用域函数，创建内容区容器
            orientation = LinearLayout.VERTICAL // 设置内容区方向为垂直
            setPadding(UiKit.dp(this@HelpActivity, 16), UiKit.dp(this@HelpActivity, 8), // 语法：setPadding=设置内容区内边距（左/上）
                UiKit.dp(this@HelpActivity, 16), UiKit.dp(this@HelpActivity, 24)) // 内边距右 16、下 24
        } // 结束 apply 作用域块
        scroll.addView(container) // 把内容区容器放入滚动容器
        // 内容区占满剩余高度，可独立滚动
        root.addView(scroll, LinearLayout.LayoutParams( // 语法：addView 带布局参数，把滚动区加入根布局
            LinearLayout.LayoutParams.MATCH_PARENT, // 语法：MATCH_PARENT=宽度占满父容器
            0, // 语法：高度先设为 0，配合权重决定实际高度
            1f // 语法：weight=权重 1，占满剩余高度
        )) // 结束 addView 与 LayoutParams 构造

        // ===== 一、架构概览 =====
        container.addView(makeCard("架构概览", // 语法：addView=添加子控件；调用 makeCard 创建卡片，标题为“架构概览”
            "Android 端通过 1x1 透明焦点窗口捕获手柄输入，经「操作集 + 公共层 + 10 个操作层」\n" // 正文第1行：说明捕获与映射链路（\n 为换行符）
                + "映射系统转换后，通过 TCP(27015) 发送给 Windows 客户端；\n" // 正文：映射后经 TCP 传输到 Windows 客户端
                + "Windows 端用 SendInput() 把事件注入 Winlator 中的 WoW。\n\n" // 正文：Windows 端把事件注入游戏
                + "软键盘输入（IME）也走同一链路：焦点窗口持有软键盘 → 捕获文本 → TCP 转发 →\n" // 正文：IME 软键盘输入走同一链路
                + "Windows 端按键注入，无需额外驱动。")) // 正文收尾：按键注入，无需额外驱动

        // ===== 二、快速开始 =====
        container.addView(makeCard("快速开始（Android 端）", // 创建“快速开始（Android 端）”说明卡片
            "1. 授予悬浮窗权限\n" // 正文：第 1 步
                + "2. 连接手柄（蓝牙/OTG），确认悬浮窗显示「🎮 未连接」变为层名\n" // 正文：第 2 步
                + "3. 点击「启动手柄映射」，悬浮窗显示「▶」（映射中）\n" // 正文：第 3 步
                + "4. 切到 Winlator 运行 WoW")) // 正文：第 4 步

        container.addView(makeCard("快速开始（Windows 端）", // 创建“快速开始（Windows 端）”说明卡片
            "1. 点「导出 Windows 客户端」，从 Download/AControler\n" // 正文：导出 Windows 客户端
                + "   取出 inputbridge_client.exe\n" // 正文：取出客户端程序
                + "2. 复制到 Winlator 的 C 盘\n" // 正文：复制到 C 盘
                + "3. 直接运行 inputbridge_client.exe\n" // 正文：启动方式
                + "4. 在客户端界面选择魔兽 exe；保持窗口打开，切回游戏")) // 正文：保持客户端运行

        // ===== 三、悬浮窗状态图标 =====
        container.addView(makeCard("悬浮窗状态图标", // 创建“悬浮窗状态图标”说明卡片
            "收起胶囊显示当前状态：\n" // 正文：引导语
                + "· 🎮 未连接 —— 手柄未连接，映射未生效\n" // 正文：未连接图标含义
                + "· 层名 ▶ —— 映射中（手柄捕获生效）\n" // 正文：映射中图标含义
                + "· 层名 ⏸ —— 捕获暂停（映射不工作）\n\n" // 正文：暂停图标含义
                + "右键锁存（长按右键）时悬浮窗边框变红色，松开恢复，方便战斗中确认右键状态。")) // 正文：右键锁存边框提示

        // ===== 四、操作集 =====
        container.addView(makeCard("操作集（Action Set）", // 创建“操作集”说明卡片
            "操作集位于操作层之上，是「公共层 + 10 个操作层」的完整配置集合。\n" // 正文：操作集定义
                + "切换操作集时，其下所有操作层整体切换（仿 Steam Input 的动作集/动作层模型）。\n\n" // 正文：整体切换机制
                + "· 初始自带一个「默认」操作集\n" // 正文：默认操作集
                + "· 可添加 / 删除 / 切换操作集，每个操作集可自定义名称\n" // 正文：操作集管理
                + "· 可拷贝操作集（含全部层与按键映射），拷贝时可直接改名\n" // 正文：拷贝操作集
                + "· 悬浮窗展开面板顶部显示当前操作集名称\n" // 正文：面板显示操作集名
                + "· 典型用法：为不同职业/场景准备独立操作集，如「默认」「治疗」「PVP」")) // 正文：典型用法

        // ===== 五、操作层系统 =====
        container.addView(makeCard("操作层系统（公共层 + 10 操作层）", // 创建“操作层系统”说明卡片
            "每个操作集内部映射分为「公共层 + 10 个操作层」（仿 Steam Input）。\n" // 正文：分层结构
                + "· 公共层：全局生效的按键，也可配置「切层」映射（SwitchLayer）\n" // 正文：公共层说明
                + "· 操作层：战斗/飞行/界面等场景专用配置\n\n" // 正文：操作层说明
                + "【切层方式】\n" // 正文：切层方式小节标题
                + "· 在公共层给某手柄键配置「切层」动作，按下即激活对应操作层\n" // 正文：切入操作层方式
                + "· 再次按下/配置切换回公共层的映射，可回到公共层\n" // 正文：切回公共层方式
                + "· 层编辑页的「切入按键」按钮可设置/修改切到该层的按键（写入公共层 SwitchLayer 映射）")) // 正文：「切入按键」按钮说明

        // ===== 六、层与操作集设置 =====
        container.addView(makeCard("层与操作集设置", // 创建“层与操作集设置”说明卡片
            "· 应用内：配置管理 → 层与操作集设置\n" // 正文：应用内入口
                + "· 悬浮窗：展开面板短按操作层按钮 → 直接跳转到该层的设置页\n\n" // 正文：悬浮窗快捷入口
                + "设置页顶部为操作集选择器与「添加/拷贝/改名/删除」按钮；\n" // 正文：页面顶部功能区
                + "下方为当前操作集的层选择器，显示当前层名称与已映射按键数量；\n" // 正文：层选择器说明
                + "列表逐行显示每个手柄按键的映射，点击可编辑；\n" // 正文：映射列表说明
                + "按住手柄按键，对应行会高亮，方便对照确认。")) // 正文：按键高亮对照

        // ===== 七、操作层切换说明（动态） =====
        // 语法：字符串模板 ${profileName()} 动态显示操作集名；正文由 buildLayerSwitchLines 动态生成
        container.addView(makeCard("当前配置的切层说明（操作集: ${profileName()}）", buildLayerSwitchLines()))

        // ===== 八、键盘输入 =====
        container.addView(makeCard("键盘输入（切换键盘）", // 创建“键盘输入”说明卡片
            "· 手柄键「切换键盘」可弹出/收起软键盘\n" // 正文：切换键盘手柄键
                + "· 弹出软键盘时保持捕获（不暂停），输入内容经 IME 转发到 Windows 注入游戏\n" // 正文：弹出键盘保持捕获
                + "· 点击键盘收起按钮、返回键或再次按切换键，键盘隐藏后自动恢复捕获\n" // 正文：键盘收起后恢复捕获
                + "· 可打印字符（字母/数字/符号）走文本通道，方向键/回车/退格等走按键通道")) // 正文：文本/按键双通道

        // ===== 九、暂停捕获 =====
        container.addView(makeCard("暂停捕获", // 创建“暂停捕获”说明卡片
            "· 暂停会移除手柄焦点窗口，侧滑返回手势恢复正常\n" // 正文：暂停的效果
                + "· 代价：暂停后手柄事件无法到达手机，「切换捕获」手柄键无法恢复，\n" // 正文：暂停的代价
                + "  需用悬浮窗「恢复捕获」按钮或 App 内「手柄捕获」开关恢复")) // 正文：恢复捕获的方式

        // ===== 十、智能暂停 =====
        container.addView(makeCard("智能暂停", // 创建“智能暂停”说明卡片
            "· 开启后自动检测前台应用：白名单（如 Winlator）在前台时保持手柄捕获，\n" // 正文：智能检测前台应用
                + "  切到其他应用自动暂停捕获，右滑返回恢复正常\n" // 正文：自动暂停行为
                + "· 需授权「使用情况访问」")) // 正文：权限要求

        // ===== 十一、悬浮窗操作 =====
        container.addView(makeCard("悬浮窗操作", // 创建“悬浮窗操作”说明卡片
            "· 收起胶囊显示当前层与状态图标，点击展开面板，可拖动\n" // 正文：胶囊操作
                + "· 展开面板顶部显示当前操作集名称\n" // 正文：面板顶部信息
                + "· 「游戏」拉起 Winlator；「暂停/恢复」切换捕获（与 App 内开关同步）\n" // 正文：游戏/暂停/恢复按钮
                + "· 层按钮：短按跳转该层设置页；长按激活层、松开回公共层\n" // 正文：层按钮操作
                + "· 「清除层」清空激活层；「映射」查看按键映射页（按键按下高亮）\n" // 正文：清除层/映射按钮
                + "· 「关闭」停止服务")) // 正文：关闭服务

        // ===== 十二、配置管理 =====
        container.addView(makeCard("配置管理", // 创建“配置管理”说明卡片
            "· 导出 / 导入：JSON 完整备份（含运行时设置）\n" // 正文：导出/导入说明
                + "· 重置配置：恢复默认 WoW 预设\n" // 正文：重置配置
                + "· 游戏 EXE 路径在 Windows 客户端界面中选择")) // 正文：游戏路径在客户端设置

        setContentView(root) // 语法：setContentView=把构建好的根布局设为页面内容
    } // 结束 buildUi 函数

    /**
     * 创建一个说明区块卡片（标题 + 正文）
     *
     * @param title 区块标题
     * @param body 正文（支持 \n 换行）
     * @return 卡片容器视图
     */
    private fun makeCard(title: String, body: String): LinearLayout { // 语法：private fun=私有函数声明，返回 LinearLayout 卡片容器
        val card = UiKit.card(this) // 语法：val=只读变量；调用 UiKit 创建深色卡片容器
        card.addView(UiKit.sectionTitle(this, title)) // 卡片内添加区块标题
        card.addView(UiKit.spacer(this, 4)) // 标题与正文之间添加 4dp 间距
        card.addView(UiKit.caption(this, body, 0xFFCCCCCC.toInt(), 12f)) // 卡片内添加正文说明文字（浅灰、12sp）
        return card // 返回组装好的卡片
    } // 结束 makeCard 函数

    /**
     * 获取当前生效的 ControllerProfile
     *
     * 数据源优先级:
     * 1. 服务已启动: 读取运行时 [LayerEditActivity.steamInputRef] 的 profile
     * 2. 服务未启动但配置文件存在: 从内部配置文件加载
     * 3. 都没有: 使用代码内置默认 profile
     */
    private fun getCurrentProfile(): ControllerProfile { // 语法：private fun=私有函数声明，返回当前生效的配置
        LayerEditActivity.steamInputRef?.profile?.let { return it } // 语法：?.=安全调用（为空则跳过）；let=作用域函数；服务已启动时直接返回其 profile
        val configFile = File(filesDir, "steamlike_config.json") // 语法：val=只读变量；File=构造文件对象，指向内部存储的配置文件
        if (configFile.exists()) { // 语法：if 条件判断，配置文件存在时读取
            return try { // 语法：return try 表达式，解析出错时走 catch 回退
                ControllerConfig.fromJson(configFile.readText()) // 从 JSON 文本解析出配置对象
            } catch (e: Exception) { // 语法：catch=捕获异常，解析失败时执行
                ControllerProfile.createDefault() // 回退为内置默认配置
            } // 结束 catch 块
        } // 结束 if 文件存在判断
        return ControllerProfile.createDefault() // 无配置文件时返回内置默认配置
    } // 结束 getCurrentProfile 函数

    /**
     * 获取当前生效的操作集名称
     *
     * @return 操作集名称（如「默认」），获取失败时回退到「默认」
     */
    private fun profileName(): String { // 语法：private fun=私有函数声明，返回当前操作集名称
        return try { // 语法：return try 表达式，读取失败时回退
            getCurrentProfile().activeActionSetName // 取当前生效操作集的名称
        } catch (e: Exception) { // 语法：catch=捕获异常
            ControllerProfile.DEFAULT_ACTION_SET_NAME // 回退为“默认”操作集名常量
        } // 结束 catch 块
    } // 结束 profileName 函数

    /**
     * 根据当前 profile 动态构建操作层切换说明
     *
     * 显示当前生效操作集（[ControllerProfile.activeActionSet]）内每个操作层的「切层键」配置
     * （[MappedAction.SwitchLayer] 映射），便于用户直接查看当前生效的切换按键。
     * 若未配置切层映射则提示未配置。
     *
     * @return 多行字符串，每行一个操作层
     */
    private fun buildLayerSwitchLines(): String { // 语法：private fun=私有函数声明，动态构建切层说明文本
        val profile = getCurrentProfile() // 获取当前生效配置
        val lines = profile.layers.map { layer -> // 语法：map=高阶函数遍历每个操作层并转换；layer -> 为 Lambda 参数
            // 公共层中切到该操作层的映射键
            val switchKeys = profile.commonLayer.buttonMappings // 语法：val=只读变量；读取公共层的按键映射表
                .filterValues { mapping -> // 语法：filterValues=按值过滤映射；mapping -> 为 Lambda 参数
                    (mapping.action as? MappedAction.SwitchLayer)?.layerName == layer.name // 语法：as?=安全类型转换；?.=安全调用；筛出切到该层的映射
                } // 结束 filterValues 过滤
                .map { buttonDisplayName(it.key) } // 语法：map=转换；it=单参数 Lambda 的隐式名称；把按键转为显示名
            // 该层中切回其他层（通常为公共层）的映射键
            val backKeys = layer.buttonMappings // 语法：val=只读变量；读取当前操作层的按键映射表
                .filterValues { mapping -> mapping.action is MappedAction.SwitchLayer } // 语法：filterValues=按值过滤；is=类型判断，筛出切层类映射
                .map { buttonDisplayName(it.key) } // 语法：map=转换；把切回用的按键转为显示名
            // 语法：if 表达式 + joinToString；空则显示“未配置”，否则用 / 连接
            val switchText = if (switchKeys.isEmpty()) "未配置" else switchKeys.joinToString("/")
            val backText = if (backKeys.isEmpty()) "未配置" else backKeys.joinToString("/") // 语法：if 表达式 + joinToString；切回键为空则“未配置”，否则 / 连接
            "· ${layer.name}  切入:$switchText  切回:$backText" // 语法：字符串模板 ${layer.name} 与 $变量；组装该操作层的一行说明
        } // 结束 map 遍历
        return lines.joinToString("\n") // 语法：joinToString=用换行符拼接所有行，返回多行文本
    } // 结束 buildLayerSwitchLines 函数

    /**
     * 将 ControllerButton 转换为可读名称
     *
     * 与 LayerEditActivity.buttonDisplayName 保持一致。
     */
    private fun buttonDisplayName(button: ControllerButton): String = when (button) { // 语法：表达式函数 + when 表达式，将按键枚举映射为显示名
        ControllerButton.A -> "A" // 语法：when 分支，A 键显示为 A
        ControllerButton.B -> "B" // B 键显示为 B
        ControllerButton.X -> "X" // X 键显示为 X
        ControllerButton.Y -> "Y" // Y 键显示为 Y
        ControllerButton.LEFT_SHOULDER -> "LB" // 左肩键显示为 LB
        ControllerButton.RIGHT_SHOULDER -> "RB" // 右肩键显示为 RB
        ControllerButton.LEFT_TRIGGER_CLICK -> "L2" // 左扳机键按下显示为 L2
        ControllerButton.RIGHT_TRIGGER_CLICK -> "R2" // 右扳机键按下显示为 R2
        ControllerButton.LEFT_STICK_CLICK -> "L3" // 左摇杆按下显示为 L3
        ControllerButton.RIGHT_STICK_CLICK -> "R3" // 右摇杆按下显示为 R3
        ControllerButton.MENU -> "Menu" // Menu 键显示为 Menu
        ControllerButton.OPTIONS -> "Options" // Options 键显示为 Options
        ControllerButton.GUIDE -> "Guide" // Guide 键显示为 Guide
        ControllerButton.DPAD_UP -> "D-Pad ↑" // 方向键上显示为 D-Pad ↑
        ControllerButton.DPAD_DOWN -> "D-Pad ↓" // 方向键下显示为 D-Pad ↓
        ControllerButton.DPAD_LEFT -> "D-Pad ←" // 方向键左显示为 D-Pad ←
        ControllerButton.DPAD_RIGHT -> "D-Pad →" // 方向键右显示为 D-Pad →
        ControllerButton.TOUCHPAD_CLICK -> "Touchpad" // 触摸板按下显示为 Touchpad
    } // 结束 when 表达式
} // 结束 HelpActivity 类
