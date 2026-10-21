plugins {  // 语法：plugins{} 块，声明本模块需要应用的 Gradle 插件
    id("com.android.application")  // 语法：id(...) 应用 Android 应用插件（AGP），用于构建 APK
}  // 结束 plugins 块

android {  // 语法：android{} 块，Android 应用模块的配置入口
    namespace = "com.steamlike.controller"  // 命名空间：包名，用于生成 R 类与 BuildConfig 类的包路径
    compileSdk = 37  // 语法：compileSdk 编译所用的 Android SDK 版本（37）

    defaultConfig {  // 语法：defaultConfig{} 块，定义应用默认配置（所有构建类型共享）
        applicationId = "com.steamlike.controller"  // 应用唯一 ID（即安装包名）
        minSdk = 24  // 语法：minSdk 最低支持 Android 版本（Android 7.0）
        targetSdk = 37  // 语法：targetSdk 目标 Android 版本（37，决定系统兼容行为）
        versionCode = 1  // 内部版本号（整数，用于版本比较/升级判断）
        versionName = "0.1.41"  // 对外显示的版本名称
    }  // 结束 defaultConfig 块

    buildTypes {  // 语法：buildTypes{} 块，配置各构建类型（debug/release 等）
        release {  // release：发布版构建配置
            isMinifyEnabled = false  // 是否启用代码混淆与压缩（false = 不启用）
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")  // 语法：proguardFiles(...) 指定混淆规则文件（Android 默认优化规则 + 项目自定义规则）
        }  // 结束 release 块
    }  // 结束 buildTypes 块

    // 生成 BuildConfig（用于在界面显示 versionName）
    buildFeatures {  // 语法：buildFeatures{} 块，开关各类构建特性
        buildConfig = true  // 开启生成 BuildConfig 类（代码中可读取 VERSION_NAME 等常量）
    }  // 结束 buildFeatures 块

    compileOptions {  // 语法：compileOptions{} 块，配置 Java 编译选项
        sourceCompatibility = JavaVersion.VERSION_1_8  // Java 源码兼容版本设为 1.8
        targetCompatibility = JavaVersion.VERSION_1_8  // 生成字节码的目标版本设为 1.8
    }  // 结束 compileOptions 块

    kotlin {  // 语法：kotlin{} 块，配置 Kotlin 编译相关选项
        compilerOptions {  // 语法：compilerOptions{} 块，Kotlin 编译器选项
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)  // 语法：jvmTarget.set(...) 设置 Kotlin 编译目标为 JVM 1.8（与 Java 版本保持一致）
        }  // 结束 compilerOptions 块
    }  // 结束 kotlin 块
}  // 结束 android 块

dependencies {  // 语法：dependencies{} 块，声明本模块的依赖
    implementation("androidx.core:core-ktx:1.12.0")  // 语法：implementation(...) 声明编译期+运行期可见的依赖；androidx 核心 Kotlin 扩展库
    implementation("androidx.appcompat:appcompat:1.6.1")  // AppCompat 兼容库（提供兼容 ActionBar/主题）
    implementation("com.google.android.material:material:1.11.0")  // Material 设计组件库
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")  // ConstraintLayout 约束布局库

    // 单元测试依赖
    testImplementation("junit:junit:4.13.2")  // 语法：testImplementation(...) 仅测试源码可见的依赖；JUnit 4 单元测试框架
    testImplementation("org.json:json:20240303")  // JVM 版 JSON 库（测试用；Android 自带 org.json 但纯 JVM 测试需要此依赖）
}  // 结束 dependencies 块

// ====================================================================
// Windows 客户端预编译任务
// ====================================================================
// 在 APK 编译前自动执行：
//   1. cargo build --release 编译 windows/rust_client → 打包到 assets（cargo 可用时）
//   2. 同步 windows/control.bat / control.ps1 → 打包到 assets（脚本始终与源码一致）
// 这样 APK 内置的 exe 和脚本每次都与源码同步，无需手动复制。
//
// 依赖: Rust 工具链 (cargo，如 M:\msys64\ucrt64\bin\cargo.exe 或系统 PATH 中的 cargo)
// 如 cargo 不可用，exe 编译跳过（使用 assets 中已有的 exe），脚本仍会同步。
// ====================================================================

tasks.register("compileWindowsExe") {  // 语法：tasks.register("任务名"){...} 注册自定义 Gradle 任务（在 APK 编译前自动执行）
    group = "build"  // 任务分组：build（在 gradlew tasks 列表中归入 build 组）
    description = "Builds Windows inputbridge_client.exe (Rust) and syncs scripts to assets"  // 任务说明（显示在 gradlew tasks 列表）

    val rustProjectDir = rootProject.file("windows/rust_client")  // Rust 工程目录（Cargo.toml 所在处）
    val manifestFile = rustProjectDir.resolve("Cargo.toml")  // 语法：File.resolve(...) 拼接子路径；Cargo.toml 清单文件
    val releaseExe = rustProjectDir.resolve("target/release/inputbridge_client.exe")  // cargo 构建产物路径
    val outputExe = rootProject.file("windows/inputbridge_client.exe")  // 编译输出 exe 的归档路径（windows 目录下）
    val assetsExe = file("src/main/assets/inputbridge_client.exe")  // 定位 APK assets 中的 exe 目标路径（file() 相对当前模块）
    val controlSource = rootProject.file("windows/control.bat")  // 定位根项目下 control.bat 源脚本
        val assetsControl = file("src/main/assets/control.bat")  // 定位 APK assets 中 control.bat 的目标路径
        val controlPs1Source = rootProject.file("windows/control.ps1")  // 定位根项目下 control.ps1 源脚本
        val assetsPs1Control = file("src/main/assets/control.ps1")  // 定位 APK assets 中 control.ps1 的目标路径

    // 搜索 cargo 路径（Rust 工具链）
    val cargoCandidates = listOf(  // 语法：listOf(...) 创建不可变列表；列出常见的 cargo 安装位置候选
        "M:/msys64/ucrt64/bin/cargo.exe",  // MSYS2 UCRT64 候选路径 1
        "C:/msys64/ucrt64/bin/cargo.exe",  // MSYS2 UCRT64 候选路径 2
        "${System.getProperty("user.home")}/.cargo/bin/cargo.exe"  // rustup 默认安装位置
    )  // 结束 cargoCandidates 列表
    val systemPath = System.getenv("PATH") ?: ""  // 读取系统环境变量 PATH，为空时回退为空字符串（?: 为 Kotlin 空安全 Elvis 运算符）
    val pathCargo = systemPath.split(File.pathSeparator)  // 语法：split(...) 按路径分隔符（Windows 为 ;）拆分 PATH
        .map { File(it, "cargo.exe") }  // 语法：map{...} 将每个目录路径转换为 File(目录, "cargo.exe")
        .firstOrNull { it.exists() }  // 取第一个真实存在的 cargo.exe，全部不存在则返回 null
    val cargoPath = cargoCandidates.map { File(it) }.firstOrNull { it.exists() } ?: pathCargo  // 先在候选路径中找存在的 cargo，找不到再回退到 PATH 中的；整体仍可能为 null

    // ===== 按需编译声明（增量构建）=====
    // 声明输入（Rust 源码 + 清单 + 脚本源）与输出（exe + 脚本副本）后，
    // Gradle 会在输入与输出都未变化时把任务标记为 UP-TO-DATE 并直接跳过，
    // 从而避免每次编译 APK 都重新执行 cargo 编译。
    inputs.dir(rustProjectDir.resolve("src"))  // 输入：Rust 源码目录（.rs 文件内容变化才需重编）
    inputs.file(manifestFile)  // 输入：Cargo.toml（依赖/特性变化会影响产物）
    inputs.file(rustProjectDir.resolve("Cargo.lock"))  // 输入：Cargo.lock（依赖版本锁定变化会影响产物）
    inputs.file(rootProject.file("windows/control.bat"))  // 输入：control.bat 脚本源
    inputs.file(rootProject.file("windows/control.ps1"))  // 输入：control.ps1 脚本源
    inputs.property("versionName", android.defaultConfig.versionName ?: "0.0.0")  // 输入：APK 版本号（版本号变化时重新编译 exe 并注入）
    outputs.files(outputExe, assetsExe, assetsControl, assetsPs1Control)  // 输出：exe 归档与 assets 副本 + 两个脚本副本

    doLast {  // 语法：doLast{} 块，任务执行阶段的末尾执行其中代码（真正干活的动作）
        // 1. 编译 exe（cargo 可用时）
        if (cargoPath == null || !cargoPath.exists() || !manifestFile.exists()) {  // 语法：if 条件分支；未找到 cargo 或 Rust 工程缺失
            // 警告日志：跳过 exe 编译，沿用 assets 中已有的 inputbridge_client.exe
            logger.warn("cargo or windows/rust_client not found, skipping Windows exe build. Using existing assets/inputbridge_client.exe")
        } else {  // 否则（找到了可用的 cargo 且工程存在）
            logger.lifecycle("Building Windows exe with: ${cargoPath.absolutePath}")  // 输出生命周期日志：正在使用哪个 cargo 编译

            // 确保输出目录存在
            outputExe.parentFile.mkdirs()  // 创建 exe 输出目录（不存在时）
            assetsExe.parentFile.mkdirs()  // 创建 assets 目录（不存在时）

            // 执行 cargo release 构建
            val cmd = listOf(  // 语法：listOf(...) 组装构建命令的参数列表
                cargoPath.absolutePath,  // cargo 可执行文件路径
                "build", "--release",  // 构建 release 产物
                "--manifest-path", manifestFile.absolutePath  // 显式指定 Cargo.toml，避免依赖工作目录
            )  // 结束命令参数列表
            val process = ProcessBuilder(cmd)  // 语法：ProcessBuilder 启动子进程执行命令
                .directory(rustProjectDir)  // 子进程工作目录设为 Rust 工程目录（保证 .cargo/config.toml 生效）
                .redirectErrorStream(true)  // 合并标准输出与错误流，便于统一读取
                .apply {
                    // 把 APK 的 versionName 注入 exe：Rust 端用 option_env!("STEAMLIKE_VERSION") 读取，
                    // 使 exe 界面/日志里的版本号与 APK 始终一致（手动 cargo build 时回退到 Cargo.toml 版本）
                    environment()["STEAMLIKE_VERSION"] = android.defaultConfig.versionName ?: "0.0.0"
                }
                .start()  // 启动子进程
            val output = process.inputStream.bufferedReader().readText()  // 读取子进程的全部输出文本
            val exitCode = process.waitFor()  // 等待子进程结束并获取退出码（0 表示成功）
            if (exitCode != 0 || !releaseExe.exists()) {  // 若构建失败（退出码非 0）或产物缺失
                // 警告日志：构建失败，沿用已有 exe，并附上构建输出
                logger.warn("cargo build failed (exit=$exitCode), using existing exe. Output:\n$output")
            } else {  // 构建成功
                logger.lifecycle("Windows exe built: ${releaseExe.absolutePath} (${releaseExe.length()} bytes)")  // 日志：exe 构建完成及字节数

                // 归档到 windows/ 与复制到 assets
                releaseExe.copyTo(outputExe, overwrite = true)  // 语法：copyTo(目标, overwrite=true) 复制 exe 到 windows 目录归档
                releaseExe.copyTo(assetsExe, overwrite = true)  // 复制 exe 到 assets，存在则覆盖
                logger.lifecycle("Copied to assets: ${assetsExe.absolutePath}")  // 日志：已复制到 assets
            }  // 结束构建成功分支
        }  // 结束 cargo 可用分支

        // 2. 始终同步 control.bat 脚本到 assets（保证 APK 内脚本与源码一致）
        if (controlSource.exists()) {  // 语法：if 条件判断；若根项目存在 control.bat
            assetsControl.parentFile.mkdirs()  // 创建 assets 目录（不存在时）
            controlSource.copyTo(assetsControl, overwrite = true)  // 复制 control.bat 到 assets（覆盖）
            logger.lifecycle("Copied control.bat to assets: ${assetsControl.absolutePath}")  // 日志：已同步 control.bat
        } else {  // 源码不存在
            logger.warn("windows/control.bat not found, keeping existing assets/control.bat")  // 警告日志：保留 assets 中已有的 control.bat
        }  // 结束 control.bat 同步分支

        // 3. 同步 control.ps1 脚本到 assets（保证 APK 内脚本与源码一致）
        if (controlPs1Source.exists()) {  // 语法：if 条件判断；若根项目存在 control.ps1
            assetsPs1Control.parentFile.mkdirs()  // 创建 assets 目录（不存在时）
            controlPs1Source.copyTo(assetsPs1Control, overwrite = true)  // 复制 control.ps1 到 assets（覆盖）
            logger.lifecycle("Copied control.ps1 to assets: ${assetsPs1Control.absolutePath}")  // 日志：已同步 control.ps1
        } else {  // 源码不存在
            logger.warn("windows/control.ps1 not found, keeping existing assets/control.ps1")  // 警告日志：保留 assets 中已有的 control.ps1
        }  // 结束 control.ps1 同步分支
    }  // 结束 doLast 块
}  // 结束 compileWindowsExe 任务

// 在 preBuild 前执行编译
tasks.named("preBuild") {  // 语法：tasks.named("任务名") 获取已存在的任务并对其进行配置
    dependsOn("compileWindowsExe")  // 语法：dependsOn(...) 声明任务依赖：preBuild 依赖 compileWindowsExe，先编译 exe 再构建
}  // 结束 preBuild 配置块
