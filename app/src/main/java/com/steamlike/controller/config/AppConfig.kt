package com.steamlike.controller.config // 包声明：config 配置模块

/**
 * 运行时配置（随配置文件持久化）
 *
 * 集中存储除按键映射外的所有运行参数，随 `steamlike_config.json`
 * 的 `settings` 字段一并导入/导出，避免散落在多处 SharedPreferences。
 *
 * ## 包含的配置
 * - [serverHost]/[serverPort]: TCP 监听地址/端口
 * - [smartPauseEnabled]: 智能暂停开关（前台应用检测自动移除/恢复焦点窗口）
 * - [captureWhitelist]: 捕获白名单（前台在此列表内时保持手柄捕获，支持多个包名）
 * - [captureEnabled]: 捕获总开关（悬浮窗按钮与 App 内开关双向同步）
 * - [launcherPackage]: 悬浮窗"游戏"按钮拉起的应用包名
 * - [gameExePath]: 游戏 EXE 完整路径（Winlator 内，如 C:\\WoW\\Wow.exe），
 *   随 control.bat 导出：脚本先启动 inputbridge_client.exe，成功后再启动游戏
 *
 * @param serverHost TCP 监听地址
 * @param serverPort TCP 监听端口
 * @param smartPauseEnabled 智能暂停开关
 * @param captureWhitelist 捕获白名单包名列表（多个）
 * @param captureEnabled 捕获总开关
 * @param launcherPackage 悬浮窗拉起的目标应用包名
 * @param gameExePath 游戏 EXE 路径（Winlator 内），空表示不自动启动游戏
 * @param gameExeHistory 游戏 EXE 历史路径列表（最近的在前，供主界面下拉直接选择）
 */
data class AppConfig( // 运行时配置数据类（语法：data class=数据类，自动生成 equals/hashCode/copy）
    val serverHost: String = DEFAULT_HOST, // 服务器监听地址（语法：val=只读属性；默认参数引用伴生常量）
    val serverPort: Int = DEFAULT_PORT, // 服务器监听端口（默认 27015）
    val smartPauseEnabled: Boolean = true, // 智能暂停开关（默认开启）
    val captureWhitelist: List<String> = DEFAULT_WHITELIST, // 捕获白名单包名列表（默认 Winlator 系列）
    val captureEnabled: Boolean = true, // 捕获总开关（默认开启）
    val launcherPackage: String = DEFAULT_LAUNCHER, // 悬浮窗拉起的目标应用包名
    val gameExePath: String = "", // 游戏 EXE 完整路径（Winlator 内），空表示不自动启动游戏
    val gameExeHistory: List<String> = emptyList() // 游戏 EXE 历史路径（最近的在前，供主界面下拉快速选择）
) { // 结束主构造参数，开始类体
    companion object { // 伴生对象：存放默认常量与工具方法（语法：companion object=类级成员容器）
        const val DEFAULT_HOST = "0.0.0.0" // 默认监听地址（语法：const val=编译期常量）
        const val DEFAULT_PORT = 27015 // 默认 TCP 端口（与 Windows 客户端约定的 27015）
        const val DEFAULT_LAUNCHER = "com.winlator" // 默认拉起的应用包名（Winlator 官方）

        /**
         * 默认捕获白名单（前台应用在此集合内时保持焦点窗口捕获手柄）
         *
         * 包含 Winlator 官方包名及其常见分支（如 com.winlator.hub）。
         * 用户可在 App 内增删，支持多个包名。
         */
        val DEFAULT_WHITELIST = listOf("com.winlator", "com.winlator.hub") // 默认白名单列表（语法：listOf=创建不可变列表）

        /**
         * 解析白名单字符串为包名列表
         *
         * 支持分隔符：逗号（中英文）、分号、空格、换行。
         * 空输入返回默认白名单。
         *
         * @param raw 原始字符串（如 "com.winlator, com.winlator.hub"）
         * @return 去重后的包名列表
         */
        fun parseWhitelist(raw: String): List<String> { // 解析白名单字符串（语法：fun=函数声明）
            val trimmed = raw.trim() // 去除首尾空白（语法：trim()=去除空白）
            if (trimmed.isEmpty()) return DEFAULT_WHITELIST // 空字符串直接返回默认白名单
            return trimmed.split(',', '，', ';', '；', ' ', '\n', '\t') // 按多种分隔符拆分（语法：split=按分隔符拆分）
                .map { it.trim() } // 每项去首尾空白（语法：map=集合转换；it=隐式参数）
                .filter { it.isNotEmpty() } // 过滤掉空项（语法：filter=按条件过滤）
                .distinct() // 去重（语法：distinct=去除重复）
        } // 结束 parseWhitelist 函数
    } // 结束伴生对象
} // 结束 AppConfig 数据类
