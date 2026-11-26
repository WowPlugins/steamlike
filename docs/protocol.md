# InputBridge TCP 协议

本文档是 Android 端（`InputBridgeServer.kt`）与 Windows 端（`windows/rust_client/src/bridge.rs`）之间协议的**单一事实来源**。两端的常量与分支必须与本文档一致。

- 传输：TCP，Android 端监听 `27015`（`AppConfig.DEFAULT_PORT`），Windows 端为客户端
- 字节序：小端
- 定长包：固定 8 字节，`Byte0` 为消息类型，其余为载荷
- 变长包：仅 `0x21` / `0x22`，格式为「8 字节头 + UTF-8 正文」，正文长度 uint32 小端，但两条包的位置不同：
  - `0x21`（exe → APK）：长度在 `Byte1..4`
  - `0x22`（APK → exe）：长度在 `Byte4..7`
- 未识别的消息类型必须忽略（保证新旧版本可互通）

## 消息表

| 值 | 方向 | 名称 | 载荷 | 说明 |
|---|---|---|---|---|
| `0x01` | APK → exe | 键盘事件 | `Byte1..2` VK(uint16)，`Byte3` 1=按下 | 带 `MAGIC` 标记，本地钩子放行且不改状态 |
| `0x02` | APK → exe | 鼠标移动 | `Byte1..2` dx(int16)，`Byte3..4` dy(int16) | 右摇杆视角 |
| `0x03` | APK → exe | 鼠标按钮 | `Byte1` 按钮(0/1/2/3/4)，`Byte2` 1=按下，`Byte3` 1=锁存同步 | `Byte3=1` 时 exe 同步本地锁存标志 |
| `0x04` | APK → exe | 鼠标滚轮 | `Byte1..2` delta(int16) | |
| `0x05` | APK → exe | 释放所有按键 | 无 | 同时结束「临时禁用 WASD 自动锁定」 |
| `0x06` | APK → exe | 心跳 | 无 | 接收方忽略 |
| `0x07` | APK → exe | 鼠标归位 | `Byte1` 落点(0=屏幕正中，1=屏幕中央下方屏外 2/3) | 只作用于本次归位 |
| `0x08` | APK → exe | 强制居中 | 无 | 交互键 / 解除锁定专用 |
| `0x09` | APK → exe | 记录光标位置 | 无 | 交互键序列开始，同时抑制 1s 的 WASD 自动锁定 |
| `0x0A` | APK → exe | 还原光标位置 | 无 | 还原 `0x09` 记录的位置 |
| `0x0B` | APK → exe | 时间同步 | `Byte1..7` 小端 56 位 epoch 毫秒 | 校准 Windows 系统时间 |
| `0x0C` | APK → exe | APK 版本 | `Byte1..3` major/minor/patch | 客户端界面显示该版本 |
| `0x0D` | APK → exe | 期望客户端版本 | `Byte1..3` `Cargo.toml` 版本 | 与 exe 编译版本比对，不一致则提示重新导出 |
| `0x20` | exe → APK | 客户端版本 | `Byte1..3` exe 编译版本 | APK 据此判断客户端是否过期 |
| `0x21` | exe → APK | 剪贴板文本 | 变长，UTF-8 正文 | 游戏内复制的文本同步到手机 |
| `0x22` | APK → exe | Unicode 文本 | 变长，UTF-8 正文 | 软键盘输入：写入剪贴板后注入 Ctrl+V |
| `0x23` | exe → APK | 切换安卓键盘 | 无 | 映射动作反向通知，显示/隐藏切换 |
| `0x25` | exe → APK | 显示安卓键盘 | 无 | 只弹出不收起 |
| `0x26` | APK → exe | 解锁鼠标锁定 | 无 | 解除左右键长按锁存并延时居中 |
| `0x27` | APK → exe | 映射按键 | `Byte1..2` VK，`Byte3` 1=按下 | 不带 `MAGIC`，可被客户端本地映射再次命中；连发的重复 down 会去重 |
| `0x28` | exe → APK | 鼠标锁存状态 | `Byte1` 右键锁存，`Byte2` 左键锁存，`Byte3` 临时禁用 WASD | 状态变化时上报 |

## 版本号约定

三个版本号各自独立递增（见 `.trae/rules/工程约束.md`）：

| 位置 | 含义 | 何时递增 |
|---|---|---|
| `app/build.gradle.kts` 的 `versionName` | APK 版本 | 仅 APK 改动 |
| `windows/rust_client/Cargo.toml` 的 `version` | exe 编译版本 | Rust 代码/依赖改动 |
| 导出目录的 `version.txt` | 第一行 = APK 版本（界面显示），第二行 = Rust 编译版本（自检用） | APK「导出」时生成 |

exe 启动时读取 `version.txt`：第一行作为显示版本，第二行与自己编译版本不一致时写入日志告警；未连接 APK 时也能发现 exe 过期。连接后 `0x0D` / `0x20` 做同样的比对。

## 修改协议时

1. 先改本文档。
2. 同步 `InputBridgeServer.kt` 的 `MSG_*` 与 `windows/rust_client/src/bridge.rs` 的 `MSG_*`。
3. 更新 `ProtocolConstantsTest.kt`（Kotlin）与 `bridge.rs` 的 `tests` 模块，保证两端取值仍与本文档一致。
4. 运行 `./gradlew test` 与 `cargo test`。
