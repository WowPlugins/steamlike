package com.steamlike.controller.config // 包声明：config 配置模块，负责配置的 JSON 序列化

import com.steamlike.controller.core.ActionSet // 导入操作集数据类
import com.steamlike.controller.core.ControllerButton // 导入手柄按键枚举
import com.steamlike.controller.core.ControllerProfile // 导入控制器配置数据类
import com.steamlike.controller.core.GlobalSettings // 导入全局设置数据类
import com.steamlike.controller.core.KeyMapping // 导入按键映射数据类
import com.steamlike.controller.core.MappedAction // 导入映射动作密封类
import com.steamlike.controller.core.MouseButton // 导入鼠标按键枚举
import com.steamlike.controller.core.OperationLayer // 导入操作层数据类
import org.json.JSONArray // 导入 Android 内置 JSON 数组类
import org.json.JSONObject // 导入 Android 内置 JSON 对象类

/**
 * 控制器配置文件 JSON 序列化/反序列化
 *
 * ## JSON 格式 (version=3)
 * ```json
 * {
 *   "version": 3,
 *   "globalSettings": {
 *     "deadzone": 0.15,
 *     "lookSensitivity": 0.5,
 *     "cursorSpeed": 1.0,
 *     "lookSmoothing": 0.5,
 *     "lookAcceleration": 1.5
 *   },
 *   "activeActionSet": "默认",
 *   "actionSets": [
 *     {
 *       "name": "默认",
 *       "commonLayer": {
 *         "name": "Common",
 *         "buttonMappings": {
 *           "A": { "action": { "type": "keyboard", "keyCode": 62 }, "subCommands": [] },
 *           "B": { "action": { "type": "mouse", "button": "RIGHT" }, "subCommands": [] }
 *         }
 *       },
 *       "layers": [
 *         {
 *           "name": "Layer1",
 *           "buttonMappings": {
 *             "A": { "action": { "type": "keyboard", "keyCode": 57 }, "subCommands": [7] }
 *           }
 *         }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * ## 版本兼容
 * - version=3: 当前格式，包含操作集列表（[ActionSet]）与当前操作集名
 * - version=2: 旧格式（顶层 commonLayer/layers，无操作集），加载时自动迁移为默认操作集
 *
 * ## 设计原则
 * 1. 所有枚举使用名称字符串（如 "A"、"DPAD_UP"），人类可读
 * 2. 动作使用 type 字段区分类型（"keyboard"/"mouse"/"switchLayer"/"mouseMove"/"lookAround"）
 * 3. 子命令是 keyCode 整数列表
 * 4. 使用 Android 内置 org.json，无额外依赖
 */
object ControllerConfig { // 单例对象：配置的 JSON 序列化/反序列化工具（语法：object=单例声明）

    const val CONFIG_VERSION = 3 // 当前配置文件版本号（语法：const val=编译期常量）

    /**
     * 将 [ControllerProfile] 序列化为 JSON 字符串
     *
     * @param profile 控制器配置
     * @param indent 缩进空格数（0=紧凑模式）
     * @param appConfig 运行时配置（非 null 时写入顶层 `settings` 字段，随配置一起导出）
     * @return JSON 字符串
     */
    fun toJson(profile: ControllerProfile, indent: Int = 2, appConfig: AppConfig? = null): String { // 序列化配置为 JSON（语法：fun 声明；默认参数；可空类型）
        val json = JSONObject() // 创建根 JSON 对象（语法：val=只读变量）
        json.put("version", CONFIG_VERSION) // 写入版本号字段
        json.put("globalSettings", globalSettingsToJson(profile.globalSettings)) // 写入全局设置
        json.put("activeActionSet", profile.activeActionSetName) // 写入当前操作集名称
        val actionSetsArray = JSONArray() // 创建操作集 JSON 数组
        profile.actionSets.forEach { set -> // 遍历每个操作集（语法：forEach=集合遍历；lambda 参数 set）
            actionSetsArray.put(actionSetToJson(set)) // 序列化单个操作集并加入数组
        } // 结束 forEach 遍历
        json.put("actionSets", actionSetsArray) // 写入操作集数组
        if (appConfig != null) { // 若传入运行时配置（语法：!= 判非空）
            json.put("settings", appConfigToJson(appConfig)) // 写入顶层 settings 字段
        } // 结束 if 块
        return json.toString(indent) // 按缩进格式化输出 JSON（语法：toString(indent)=格式化输出）
    } // 结束 toJson 函数

    // ===== AppConfig（运行时配置）序列化 =====

    /**
     * 将 [AppConfig] 序列化为 JSON 对象（写入配置文件顶层 `settings` 字段）
     */
    fun appConfigToJson(cfg: AppConfig): JSONObject { // 序列化运行时配置为 JSON 对象
        val json = JSONObject() // 创建 JSON 对象
        json.put("serverHost", cfg.serverHost) // 写入服务器监听地址
        json.put("serverPort", cfg.serverPort) // 写入服务器监听端口
        json.put("smartPauseEnabled", cfg.smartPauseEnabled) // 写入智能暂停开关
        val whitelist = JSONArray() // 创建白名单 JSON 数组
        cfg.captureWhitelist.forEach { whitelist.put(it) } // 遍历白名单包名加入数组（语法：it=lambda 隐式参数）
        json.put("captureWhitelist", whitelist) // 写入捕获白名单数组
        json.put("captureEnabled", cfg.captureEnabled) // 写入捕获总开关
        json.put("launcherPackage", cfg.launcherPackage) // 写入拉起应用包名
        json.put("gameExePath", cfg.gameExePath) // 写入游戏 EXE 路径
        val exeHistory = JSONArray() // 创建游戏 EXE 历史路径数组
        cfg.gameExeHistory.forEach { exeHistory.put(it) } // 遍历写入历史路径（语法：forEach + it lambda 参数）
        json.put("gameExeHistory", exeHistory) // 写入历史路径数组
        return json // 返回 JSON 对象
    } // 结束 appConfigToJson 函数

    /**
     * 从 JSON 对象解析 [AppConfig]（缺失字段使用默认值）
     */
    fun parseAppConfig(json: JSONObject?): AppConfig { // 从 JSON 解析运行时配置（语法：JSONObject?=可空参数类型）
        if (json == null) return AppConfig() // JSON 为空直接返回默认配置（语法：if 判空 + return 提前返回）
        return AppConfig( // 构造 AppConfig 数据类（语法：数据类命名参数构造）
            serverHost = json.optString("serverHost", AppConfig.DEFAULT_HOST), // 读取监听地址，缺失用默认值（语法：optString=带默认值读取）
            serverPort = json.optInt("serverPort", AppConfig.DEFAULT_PORT), // 读取端口，缺失用默认值（语法：optInt=带默认值读取）
            smartPauseEnabled = json.optBoolean("smartPauseEnabled", true), // 读取智能暂停开关，默认开启
            captureWhitelist = json.optJSONArray("captureWhitelist")?.let { arr -> // 读取白名单数组（语法：?.=安全调用；let=作用域函数）
                (0 until arr.length()).map { arr.getString(it) } // 遍历索引取出包名列表（语法：until=开区间；map=集合转换）
                    .filter { it.isNotBlank() } // 过滤空白项（语法：filter=按条件过滤；it=隐式参数）
                    .distinct() // 去重（语法：distinct=去除重复元素）
            } ?: AppConfig.DEFAULT_WHITELIST, // 数组为空时回退默认白名单（语法：?:=空值合并）
            captureEnabled = json.optBoolean("captureEnabled", true), // 读取捕获总开关，默认开启
            launcherPackage = json.optString("launcherPackage", AppConfig.DEFAULT_LAUNCHER), // 读取拉起应用包名，默认 Winlator
            gameExePath = json.optString("gameExePath", ""), // 读取游戏 EXE 路径，默认空
            gameExeHistory = json.optJSONArray("gameExeHistory")?.let { arr -> // 读取历史路径数组（语法：?.let 安全调用）
                (0 until arr.length()).map { arr.getString(it) } // 遍历索引取出路径列表
                    .filter { it.isNotBlank() } // 过滤空白项
                    .distinct() // 去重
            } ?: emptyList() // 缺失时用空列表（语法：?:=空值合并）
        ) // 结束 AppConfig 构造
    } // 结束 parseAppConfig 函数

    /**
     * 从完整配置文件 JSON 字符串解析 AppConfig（顶层 `settings` 字段）
     */
    fun appConfigFromJsonString(jsonString: String): AppConfig { // 从配置文件字符串解析运行时配置
        val json = JSONObject(jsonString) // 将字符串解析为 JSON 对象
        return parseAppConfig(json.optJSONObject("settings")) // 读取 settings 子对象并解析
    } // 结束 appConfigFromJsonString 函数

    /**
     * 从 JSON 字符串反序列化为 [ControllerProfile]
     *
     * @param jsonString JSON 字符串
     * @return 控制器配置
     * @throws org.json.JSONException JSON 格式错误时抛出
     */
    fun fromJson(jsonString: String): ControllerProfile { // 反序列化 JSON 为控制器配置
        val json = JSONObject(jsonString) // 将字符串解析为 JSON 对象
        val version = json.optInt("version", 1) // 读取版本号，缺失默认 1
        return when { // 按版本分发解析（语法：when=无参数分支表达式）
            version == CONFIG_VERSION -> parseV3(json) // 版本 3：解析操作集格式
            version == 2 -> parseV2(json)  // 旧格式自动迁移为默认操作集 // 版本 2：旧格式自动迁移
            else -> throw IllegalArgumentException( // 其他版本：拒绝加载（语法：throw=抛出异常）
                "Unsupported config version: $version, expected: $CONFIG_VERSION or 2" // 错误信息（语法：字符串模板 $var 插值）
            ) // 结束 IllegalArgumentException 构造
        } // 结束 when 表达式
    } // 结束 fromJson 函数

    /**
     * 解析 version=3 格式（操作集列表）
     */
    private fun parseV3(json: JSONObject): ControllerProfile { // 解析新版操作集格式（语法：private=私有函数）
        val globalSettings = json.optJSONObject("globalSettings")?.let { parseGlobalSettings(it) } // 读取全局设置（语法：?.=安全调用；let=作用域函数）
            ?: GlobalSettings() // 缺失时用默认全局设置（语法：?:=空值合并）

        val actionSetsArray = json.optJSONArray("actionSets") ?: JSONArray() // 读取操作集数组，缺失用空数组
        val actionSets = (0 until actionSetsArray.length()).map { i -> // 遍历索引逐个解析操作集（语法：until=开区间；map=集合转换）
            parseActionSet(actionSetsArray.getJSONObject(i)) // 解析第 i 个操作集
        } // 结束 map 转换
        // 容错：无操作集时回退到默认「默认」操作集
        if (actionSets.isEmpty()) { // 若操作集列表为空
            return ControllerProfile( // 返回只含默认操作集的配置
                actionSets = listOf(ControllerProfile.createDefault().activeActionSet), // 用默认配置的当前操作集构建列表（语法：listOf=创建列表）
                globalSettings = globalSettings // 沿用解析出的全局设置
            ) // 结束 ControllerProfile 构造
        } // 结束 if 判空块
        val activeName = json.optString("activeActionSet", ControllerProfile.DEFAULT_ACTION_SET_NAME) // 读取当前操作集名，缺失用默认名

        return ControllerProfile( // 构造完整配置
            actionSets = actionSets, // 设置操作集列表
            activeActionSetName = activeName, // 设置当前操作集名
            globalSettings = globalSettings // 设置全局设置
        ) // 结束 ControllerProfile 构造
    } // 结束 parseV3 函数

    /**
     * 解析 version=2 旧格式（顶层 commonLayer/layers），迁移为单个默认操作集
     */
    private fun parseV2(json: JSONObject): ControllerProfile { // 解析旧版配置并迁移为操作集格式
        val globalSettings = json.optJSONObject("globalSettings")?.let { parseGlobalSettings(it) } // 读取全局设置（语法：?.=安全调用）
            ?: GlobalSettings() // 缺失时用默认全局设置（语法：?:=空值合并）

        val commonLayerJson = json.getJSONObject("commonLayer") // 读取旧格式公共层对象（必需字段）
        val commonLayer = parseLayer(commonLayerJson) // 解析公共层

        val layersArray = json.optJSONArray("layers") ?: JSONArray() // 读取旧格式操作层数组，缺失用空数组
        val layers = (0 until layersArray.length()).map { i -> // 遍历索引逐个解析操作层（语法：until=开区间；map=转换）
            parseLayer(layersArray.getJSONObject(i)) // 解析第 i 个操作层
        } // 结束 map 转换

        return ControllerProfile( // 构造迁移后的配置
            actionSets = listOf( // 包成单个默认操作集列表
                // 创建默认操作集（语法：命名参数构造）
                ActionSet(name = ControllerProfile.DEFAULT_ACTION_SET_NAME, commonLayer = commonLayer, layers = layers)
            ), // 结束 listOf
            globalSettings = globalSettings // 设置全局设置
        ) // 结束 ControllerProfile 构造
    } // 结束 parseV2 函数

    // ===== 内部序列化方法 =====

    private fun actionSetToJson(set: ActionSet): JSONObject { // 序列化单个操作集为 JSON 对象
        val json = JSONObject() // 创建 JSON 对象
        json.put("name", set.name) // 写入操作集名称
        json.put("commonLayer", layerToJson(set.commonLayer)) // 写入公共层
        val layersArray = JSONArray() // 创建操作层 JSON 数组
        set.layers.forEach { layer -> layersArray.put(layerToJson(layer)) } // 遍历操作层并序列化加入数组
        json.put("layers", layersArray) // 写入操作层数组
        return json // 返回 JSON 对象
    } // 结束 actionSetToJson 函数

    private fun parseActionSet(json: JSONObject): ActionSet { // 解析操作集 JSON
        val name = json.getString("name") // 读取操作集名称（必需字段）
        val commonLayer = parseLayer(json.getJSONObject("commonLayer")) // 解析公共层
        val layersArray = json.optJSONArray("layers") ?: JSONArray() // 读取操作层数组，缺失用空数组
        val layers = (0 until layersArray.length()).map { i -> // 遍历索引逐个解析操作层
            parseLayer(layersArray.getJSONObject(i)) // 解析第 i 个操作层
        } // 结束 map 转换
        return ActionSet(name = name, commonLayer = commonLayer, layers = layers) // 构造操作集
    } // 结束 parseActionSet 函数

    private fun globalSettingsToJson(settings: GlobalSettings): JSONObject { // 序列化全局设置为 JSON 对象
        val json = JSONObject() // 创建 JSON 对象
        json.put("deadzone", settings.deadzone) // 写入死区
        json.put("lookSensitivity", settings.lookSensitivity) // 写入视角灵敏度
        json.put("cursorSpeed", settings.cursorSpeed) // 写入光标速度
        json.put("lookSmoothing", settings.lookSmoothing) // 写入视角平滑
        json.put("lookAcceleration", settings.lookAcceleration) // 写入视角加速度
        json.put("longPressThresholdMs", settings.longPressThresholdMs) // 写入长按判定阈值
        json.put("longPressFallbackToShort", settings.longPressFallbackToShort) // 写入长按回退开关
        json.put("rightButtonUseFunctionKeys", settings.rightButtonUseFunctionKeys) // 写入右键 F11/F12 模式开关
        json.put("wasdAutoRightLock", settings.wasdAutoRightLock) // 写入 WASD 自动锁定右键开关
        json.put("wasdAutoCenterMouse", settings.wasdAutoCenterMouse) // 写入 WASD 自动锁定前的鼠标居中开关
        json.put("cursorParkOffscreen", settings.cursorParkOffscreen) // 写入居中落点开关（是否停放到屏幕外下方）
        return json // 返回 JSON 对象
    } // 结束 globalSettingsToJson 函数

    private fun parseGlobalSettings(json: JSONObject): GlobalSettings { // 解析全局设置 JSON
        return GlobalSettings( // 构造全局设置（语法：数据类命名参数构造）
            deadzone = json.optDouble("deadzone", 0.15).toFloat(), // 读取死区并转 Float（语法：optDouble=带默认值读取；toFloat()=类型转换）
            lookSensitivity = json.optDouble("lookSensitivity", 0.5).toFloat(), // 读取灵敏度并转 Float
            cursorSpeed = json.optDouble("cursorSpeed", 1.0).toFloat(), // 读取光标速度并转 Float
            lookSmoothing = json.optDouble("lookSmoothing", 0.5).toFloat(), // 读取平滑并转 Float
            lookAcceleration = json.optDouble("lookAcceleration", 1.5).toFloat(), // 读取加速度并转 Float
            longPressThresholdMs = json.optInt("longPressThresholdMs", 500), // 读取长按阈值（语法：optInt=带默认值读取整数）
            longPressFallbackToShort = json.optBoolean("longPressFallbackToShort", false), // 读取长按回退开关
            rightButtonUseFunctionKeys = json.optBoolean("rightButtonUseFunctionKeys", true), // 读取右键 F11/F12 模式开关（默认开启）
            wasdAutoRightLock = json.optBoolean("wasdAutoRightLock", true), // 读取 WASD 自动锁定右键开关（默认开启）
            wasdAutoCenterMouse = json.optBoolean("wasdAutoCenterMouse", true), // 读取 WASD 自动锁定前的鼠标居中开关（默认开启）
            cursorParkOffscreen = json.optBoolean("cursorParkOffscreen", true) // 读取居中落点开关（默认停放到屏幕外下方）
        ) // 结束 GlobalSettings 构造
    } // 结束 parseGlobalSettings 函数

    private fun layerToJson(layer: OperationLayer): JSONObject { // 序列化操作层为 JSON 对象
        val json = JSONObject() // 创建 JSON 对象
        json.put("name", layer.name) // 写入操作层名称
        val mappings = JSONObject() // 创建映射表 JSON 对象
        layer.buttonMappings.forEach { (button, mapping) -> // 遍历按键映射（语法：解构声明 (button, mapping)）
            mappings.put(button.name, mappingToJson(mapping)) // 以按键名称为键写入映射
        } // 结束 forEach 遍历
        json.put("buttonMappings", mappings) // 写入按键映射对象
        return json // 返回 JSON 对象
    } // 结束 layerToJson 函数

    private fun parseLayer(json: JSONObject): OperationLayer { // 解析操作层 JSON
        val name = json.getString("name") // 读取操作层名称

        val mappings = mutableMapOf<ControllerButton, KeyMapping>() // 创建可变映射表（语法：mutableMapOf=可变映射）
        val mappingsJson = json.optJSONObject("buttonMappings") ?: JSONObject() // 读取映射表 JSON，缺失用空对象
        mappingsJson.keys().forEach { buttonName -> // 遍历每个按键名（语法：keys()=键集合）
            val button = runCatching { ControllerButton.valueOf(buttonName) }.getOrNull() // 按名称解析枚举，失败得 null（语法：runCatching=异常捕获）
            if (button != null) { // 若解析成功（语法：!= 判非空）
                val mappingJson = mappingsJson.getJSONObject(buttonName) // 读取该按键的映射 JSON
                val mapping = parseMapping(mappingJson) // 解析映射
                if (mapping != null) { // 若解析成功
                    mappings[button] = mapping // 写入映射表（语法：[]=下标赋值）
                } // 结束内层 if
            } // 结束外层 if
        } // 结束 forEach 遍历

        return OperationLayer( // 构造操作层
            name = name, // 设置名称
            buttonMappings = mappings // 设置映射表
        ) // 结束 OperationLayer 构造
    } // 结束 parseLayer 函数

    private fun mappingToJson(mapping: KeyMapping): JSONObject { // 序列化按键映射为 JSON 对象
        val json = JSONObject() // 创建 JSON 对象
        json.put("action", actionToJson(mapping.action)) // 写入动作对象
        val subArray = JSONArray() // 创建子命令 JSON 数组
        mapping.subCommands.forEach { subArray.put(it) } // 遍历子命令加入数组
        json.put("subCommands", subArray) // 写入子命令数组
        mapping.longPressAction?.let { json.put("longPressAction", actionToJson(it)) } // 写入长按动作（未配置则不写该字段）
        val longSubArray = JSONArray() // 创建长按子命令 JSON 数组
        mapping.longPressSubCommands.forEach { longSubArray.put(it) } // 遍历长按子命令加入数组
        json.put("longPressSubCommands", longSubArray) // 写入长按子命令数组（作用于长按动作）
        return json // 返回 JSON 对象
    } // 结束 mappingToJson 函数

    private fun parseMapping(json: JSONObject): KeyMapping? { // 解析按键映射（语法：KeyMapping?=可空返回类型）
        val actionJson = json.getJSONObject("action") // 读取动作 JSON（必需字段）
        val action = parseAction(actionJson) ?: return null // 解析动作，失败则整体返回 null（语法：?:=空值合并）
        val subArray = json.optJSONArray("subCommands") ?: JSONArray() // 读取子命令数组，缺失用空数组
        val subCommands = (0 until subArray.length()).map { subArray.getInt(it) } // 遍历索引读取子命令整数列表
        if (subCommands.size > KeyMapping.MAX_SUB_COMMANDS) { // 若子命令数量超上限
            return null  // 子命令超过限制，跳过 // 子命令超限，拒绝加载
        } // 结束 if 判断
        val longPressAction = json.optJSONObject("longPressAction")?.let { parseAction(it) } // 读取长按动作，缺失或解析失败则为 null（语法：optJSONObject 带默认值读取）
        val longSubArray = json.optJSONArray("longPressSubCommands") ?: JSONArray() // 读取长按子命令数组，缺失用空数组
        val longPressSubCommands = (0 until longSubArray.length()).map { longSubArray.getInt(it) } // 遍历索引读取长按子命令整数列表
        if (longPressSubCommands.size > KeyMapping.MAX_SUB_COMMANDS) { // 若长按子命令数量超上限
            return null // 长按子命令超限，拒绝加载
        } // 结束 if 判断
        return KeyMapping(action, subCommands, longPressAction, longPressSubCommands) // 构造按键映射（含长按动作与其子命令）
    } // 结束 parseMapping 函数

    private fun actionToJson(action: MappedAction): JSONObject { // 序列化映射动作为 JSON 对象
        val json = JSONObject() // 创建 JSON 对象
        when (action) { // 按动作类型分发（语法：when=分支表达式）
            is MappedAction.KeyboardKey -> { // 键盘按键类型（语法：is=类型判断）
                json.put("type", "keyboard") // 写入类型标识
                json.put("keyCode", action.keyCode) // 写入按键码
            } // 结束键盘按键分支
            is MappedAction.MouseClick -> { // 鼠标点击类型
                json.put("type", "mouse") // 写入类型标识
                json.put("button", action.button.name) // 写入鼠标按钮名
            } // 结束鼠标点击分支
            is MappedAction.MouseToggle -> { // 鼠标按住切换类型
                json.put("type", "mouseToggle") // 写入类型标识
                json.put("button", action.button.name) // 写入鼠标按钮名
            } // 结束鼠标切换分支
            is MappedAction.MouseSwapToggle -> { // 鼠标长按交换类型
                json.put("type", "mouseSwapToggle") // 写入类型标识
            } // 结束鼠标长按交换分支
            is MappedAction.MouseReleaseAll -> { // 释放鼠标长按类型
                json.put("type", "mouseReleaseAll") // 写入类型标识
            } // 结束释放鼠标长按分支
            is MappedAction.MouseClickSequence -> { // 交互键类型
                json.put("type", "mouseClickSequence") // 写入类型标识
            } // 结束交互键分支
            is MappedAction.SwitchLayer -> { // 切换操作层类型
                json.put("type", "switchLayer") // 写入类型标识
                json.put("layerName", action.layerName) // 写入目标层名
            } // 结束切换层分支
            is MappedAction.SwitchLayerAndKey -> { // 切层+按键同时生效类型
                json.put("type", "switchLayerAndKey") // 写入类型标识
                json.put("layerName", action.layerName) // 写入目标层名
                json.put("keyCode", action.keyCode) // 写入同时按下的键盘键码
            } // 结束切层+按键分支
            is MappedAction.NumpadKey -> { // 数字小键盘类型
                json.put("type", "numpadKey") // 写入类型标识
                json.put("keyCode", action.keyCode) // 写入小键盘键码
            } // 结束数字小键盘分支
            is MappedAction.MouseMove -> { // 鼠标移动类型
                json.put("type", "mouseMove") // 写入类型标识
            } // 结束鼠标移动分支
            is MappedAction.LookAround -> { // 视角环绕类型
                json.put("type", "lookAround") // 写入类型标识
            } // 结束视角环绕分支
            is MappedAction.MouseScrollUp -> { // 滚轮上滚类型
                json.put("type", "mouseScrollUp") // 写入类型标识
            } // 结束滚轮上滚分支
            is MappedAction.MouseScrollDown -> { // 滚轮下滚类型
                json.put("type", "mouseScrollDown") // 写入类型标识
            } // 结束滚轮下滚分支
            is MappedAction.ToggleOverlay -> { // 切换悬浮窗类型
                json.put("type", "toggleOverlay") // 写入类型标识
            } // 结束切换悬浮窗分支
            is MappedAction.ToggleKeyboard -> { // 切换键盘类型
                json.put("type", "toggleKeyboard") // 写入类型标识
            } // 结束切换键盘分支
            is MappedAction.ToggleCapture -> { // 切换捕获类型
                json.put("type", "toggleCapture") // 写入类型标识
            } // 结束切换捕获分支
        } // 结束 when 表达式
        return json // 返回 JSON 对象
    } // 结束 actionToJson 函数

    private fun parseAction(json: JSONObject): MappedAction? { // 解析映射动作（语法：MappedAction?=可空返回类型）
        val type = json.getString("type") // 读取动作类型标识
        return when (type) { // 按类型字符串分发解析（语法：when=按值分支）
            "keyboard" -> { // 键盘按键类型
                val keyCode = json.getInt("keyCode") // 读取按键码
                MappedAction.KeyboardKey(keyCode) // 构造键盘按键动作（块内最后一行即返回值）
            } // 结束键盘按键分支
            "mouse" -> { // 鼠标点击类型
                val buttonStr = json.getString("button") // 读取按钮名字符串
                val button = runCatching { MouseButton.valueOf(buttonStr) }.getOrNull() // 解析枚举，失败得 null（语法：runCatching=异常捕获）
                    ?: return null // 解析失败返回 null（语法：?:=空值合并）
                MappedAction.MouseClick(button) // 构造鼠标点击动作
            } // 结束鼠标点击分支
            "mouseToggle" -> { // 鼠标按住切换类型
                val buttonStr = json.getString("button") // 读取按钮名字符串
                val button = runCatching { MouseButton.valueOf(buttonStr) }.getOrNull() // 解析枚举，失败得 null
                    ?: return null // 解析失败返回 null
                MappedAction.MouseToggle(button) // 构造鼠标切换动作
            } // 结束鼠标切换分支
            "mouseSwapToggle" -> MappedAction.MouseSwapToggle // 鼠标长按交换：直接返回单例对象
            "mouseReleaseAll" -> MappedAction.MouseReleaseAll // 释放鼠标长按：直接返回单例对象
            "mouseClickSequence" -> MappedAction.MouseClickSequence // 交互键：直接返回单例对象
            "switchLayer" -> { // 切换操作层类型
                val layerName = json.getString("layerName") // 读取目标层名
                MappedAction.SwitchLayer(layerName) // 构造切换层动作
            } // 结束切换层分支
            "switchLayerAndKey" -> { // 切层+按键同时生效类型
                val layerName = json.getString("layerName") // 读取目标层名
                val keyCode = json.getInt("keyCode") // 读取同时按下的键盘键码
                MappedAction.SwitchLayerAndKey(layerName, keyCode) // 构造切层+按键动作
            } // 结束切层+按键分支
            "numpadKey" -> { // 数字小键盘类型
                val keyCode = json.getInt("keyCode") // 读取小键盘键码
                MappedAction.NumpadKey(keyCode) // 构造数字小键盘动作
            } // 结束数字小键盘分支
            "mouseMove" -> MappedAction.MouseMove // 鼠标移动：直接返回单例对象
            "lookAround" -> MappedAction.LookAround // 视角环绕：直接返回单例对象
            "mouseScrollUp" -> MappedAction.MouseScrollUp // 滚轮上滚：直接返回单例对象
            "mouseScrollDown" -> MappedAction.MouseScrollDown // 滚轮下滚：直接返回单例对象
            "toggleOverlay" -> MappedAction.ToggleOverlay // 切换悬浮窗：直接返回单例对象
            "toggleKeyboard" -> MappedAction.ToggleKeyboard // 切换键盘：直接返回单例对象
            "toggleCapture" -> MappedAction.ToggleCapture // 切换捕获：直接返回单例对象
            else -> null // 未知类型返回 null
        } // 结束 when 表达式
    } // 结束 parseAction 函数
} // 结束 ControllerConfig 单例对象
