//! 配置文件（JSON）读写
//!
//! 程序自己的配置保存在 exe 同目录的 `rust_client_config.json`。

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// 程序自身配置文件（exe 同目录）
pub const CONFIG_FILE_NAME: &str = "rust_client_config.json";

pub const DEFAULT_IP: &str = "127.0.0.1";
pub const DEFAULT_PORT: u16 = 27015;

#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct ClientConfig {
    /// 界面上选择的魔兽 exe 路径
    pub wow_path: String,
    /// 历史使用过的魔兽 exe 路径（下拉快速切换，最新的在前）
    pub wow_path_history: Vec<String>,
    /// 启动程序时是否自动启动魔兽（默认启动）
    pub auto_launch: bool,
    /// 自动启动魔兽前的等待时间（毫秒），期间检测到键鼠输入可取消本次自动启动
    pub auto_launch_delay_ms: u32,
    /// 自动启动等待期间有键鼠输入则取消本次自动启动（默认开启）
    pub skip_launch_on_input: bool,
    /// 点击「立即启动魔兽」后是否自动收起为小圆点（默认关闭，保持当前界面）
    pub minimize_on_launch: bool,
    /// APK 服务器 IP
    pub server_ip: String,
    /// APK 服务器端口
    pub server_port: u16,
    /// 是否连接 APK 服务器（默认连接）
    pub auto_connect: bool,
    /// 键鼠拦截总开关（默认关闭）
    pub intercept_enabled: bool,
    /// 交互键虚拟键码（默认 X）
    pub interaction_vk: u32,
    /// 长按交换键虚拟键码（默认 1）
    pub swap_vk: u32,
    /// 长按判定阈值（毫秒）
    pub long_press_ms: u32,
    /// 拦截路径中 WASD 触发时的归位落点：true = 屏幕左下角之外、下边缘下方 2/3 屏高处，
    /// false = 屏幕正中央（默认屏幕左下角之外）
    pub cursor_park_offscreen: bool,
    /// 剪贴板同步开关（默认开启）：剪贴板文本始终追加到 exe 目录的 clipboard_log.txt，
    /// 已连接 APK 时同时发送到手机剪贴板
    pub clipboard_sync: bool,
    /// 按键映射总开关（默认开启）：关闭时所有映射不生效，按键原样透传
    pub key_mapping_enabled: bool,
    /// 按键映射列表
    pub key_mappings: Vec<KeyMapping>,
    /// 角色状态读取总开关（默认关闭）
    ///
    /// 开启后会把 SLState 插件装进游戏目录并扫描游戏进程内存，
    /// 供「仅潜行 / 仅非潜行」的映射条件使用。
    pub enable_state_scan: bool,
    /// 悬浮窗是否展开（默认展开为小面板，false = 收起为状态点）
    pub overlay_expanded: bool,
    /// 小面板（紧凑形态）是否参与悬浮窗形态（默认不参与）
    ///
    /// 关闭时形态只有两种：小圆点 ⇄ 完整主界面；
    /// 打开时才有三种：小圆点 ⇄ 小面板 ⇄ 完整主界面。
    #[serde(default)]
    pub overlay_compact_enabled: bool,
    /// 上次退出时窗口左上角的 X 坐标（物理像素，None = 未记录）
    pub window_x: Option<i32>,
    /// 上次退出时窗口左上角的 Y 坐标（物理像素，None = 未记录）
    pub window_y: Option<i32>,
    /// 启动时恢复上次退出时的窗口位置（默认关闭：每次启动使用系统默认位置）
    pub restore_window_pos: bool,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            wow_path: String::new(),
            wow_path_history: Vec::new(),
            auto_launch: true,
            auto_launch_delay_ms: 1000,
            skip_launch_on_input: true,
            minimize_on_launch: false,
            server_ip: DEFAULT_IP.to_string(),
            server_port: DEFAULT_PORT,
            auto_connect: true,
            intercept_enabled: false,
            interaction_vk: 0x58,
            swap_vk: 0x31,
            long_press_ms: 500,
            cursor_park_offscreen: true,
            clipboard_sync: true,
            key_mapping_enabled: true,
            key_mappings: Vec::new(),
            enable_state_scan: false,
            overlay_expanded: true,
            overlay_compact_enabled: false,
            window_x: None,
            window_y: None,
            restore_window_pos: false,
        }
    }
}

/* ===== 按键映射数据结构 ===== */

/// 映射动作（短按或长按触发后要执行的动作）
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum MappedAction {
    /// 未设置（占位，保存时不写）
    None,
    /// 键盘按键（含子命令组合键）
    KeyboardKey { key_code: u32, sub_commands: Vec<u32> },
    /// 数字小键盘按键（含子命令组合键）
    NumpadKey { key_code: u32, sub_commands: Vec<u32> },
    /// 鼠标点击（含子命令组合键）
    MouseClick { button: u8, sub_commands: Vec<u32> },
    /// 鼠标长按/锁存（含子命令组合键）
    MouseToggle { button: u8, sub_commands: Vec<u32> },
    /// 滚轮上滚
    ScrollUp,
    /// 滚轮下滚
    ScrollDown,
    /// 鼠标长按交换（左右键锁存互换）
    MouseSwapToggle,
    /// 释放所有鼠标长按锁存
    MouseReleaseAll,
    /// 交互键（解锁存 → 居中 → 右键点击 → 恢复锁存）
    Interact,
    /// 悬浮窗展开/收起
    OverlayToggle,
    /// 拉起安卓软键盘（仅弹出，已在显示时不收起）
    ShowKeyboard,
    /// 先发送回车，再切换安卓软键盘显示/隐藏
    ToggleKeyboardEnter,
    /// 解锁鼠标锁定 → 光标居中 → 发送原始按键（按键与子命令均可任意配置）
    UnlockKey {
        key_code: u32,
        sub_commands: Vec<u32>,
    },
}

impl Default for MappedAction {
    fn default() -> Self {
        Self::None
    }
}

impl MappedAction {
    /// 动作的具体描述（用于界面列表展示）
    pub fn describe(&self) -> String {
        match self {
            Self::None => "未设置".to_string(),
            Self::KeyboardKey { key_code, sub_commands } => {
                // 主键 Shift + 唯一子命令 = Shift 符号组合，能对应符号时直接显示（Shift+1 → !）
                if *key_code == 0x10 && sub_commands.len() == 1 {
                    if let Some(sym) = crate::ui::shift_symbol_name(sub_commands[0]) {
                        return format!("键盘 Shift+{}", sym);
                    }
                }
                let key_name = crate::ui::key_name(*key_code);
                if sub_commands.is_empty() {
                    format!("键盘 {}", key_name)
                } else {
                    let subs: Vec<String> = sub_commands.iter().map(|&c| crate::ui::key_name(c)).collect();
                    format!("键盘 {} + {}", key_name, subs.join(" + "))
                }
            }
            Self::NumpadKey { key_code, sub_commands } => {
                let key_name = crate::ui::numpad_key_name(*key_code);
                if sub_commands.is_empty() {
                    format!("小键盘 {}", key_name)
                } else {
                    let subs: Vec<String> = sub_commands.iter().map(|&c| crate::ui::key_name(c)).collect();
                    format!("小键盘 {} + {}", key_name, subs.join(" + "))
                }
            }
            Self::MouseClick { button, sub_commands } => {
                let btn_name = crate::ui::mouse_button_name(*button);
                if sub_commands.is_empty() {
                    format!("鼠标点击 {}", btn_name)
                } else {
                    let subs: Vec<String> = sub_commands.iter().map(|&c| crate::ui::key_name(c)).collect();
                    format!("鼠标点击 {} + {}", btn_name, subs.join(" + "))
                }
            }
            Self::MouseToggle { button, sub_commands } => {
                let btn_name = crate::ui::mouse_button_name(*button);
                if sub_commands.is_empty() {
                    format!("鼠标长按 {}", btn_name)
                } else {
                    let subs: Vec<String> = sub_commands.iter().map(|&c| crate::ui::key_name(c)).collect();
                    format!("鼠标长按 {} + {}", btn_name, subs.join(" + "))
                }
            }
            Self::ScrollUp => "滚轮上滚".to_string(),
            Self::ScrollDown => "滚轮下滚".to_string(),
            Self::MouseSwapToggle => "鼠标长按交换".to_string(),
            Self::MouseReleaseAll => "释放鼠标长按".to_string(),
            Self::Interact => "交互键".to_string(),
            Self::OverlayToggle => "悬浮窗展开/收起".to_string(),
            Self::ShowKeyboard => "显示安卓键盘".to_string(),
            Self::ToggleKeyboardEnter => "回车+切换键盘".to_string(),
            Self::UnlockKey {
                key_code,
                sub_commands,
            } => {
                format!(
                    "解锁+居中+{}",
                    crate::ui::combo_name(*key_code, sub_commands)
                )
            }
        }
    }
}

/// 是否为修饰键（Ctrl/Shift/Alt/Win）
pub fn is_modifier_vk(vk: u32) -> bool {
    matches!(vk, 0x10 | 0x11 | 0x12 | 0x5B | 0x5C)
}

/// 一条按键映射：源按键 → 短按动作 + 长按动作
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(default)]
pub struct KeyMapping {
    /// 源按键虚拟键码
    pub source_vk: u32,
    /// 源按键的修饰键（如 Ctrl+7 里的 Ctrl），需全部按住才触发
    #[serde(default)]
    pub source_mods: Vec<u32>,
    /// 短按动作
    pub tap_action: MappedAction,
    /// 长按动作（None 表示无长按映射）
    pub long_press_action: MappedAction,
    /// 该映射是否生效（默认生效）
    pub enabled: bool,
    /// 生效条件列表（可多条，按 `require_mode` 组合）
    #[serde(default)]
    pub require_all: Vec<StateCondition>,
    /// 多条条件的组合方式（与 / 或）
    #[serde(default)]
    pub require_mode: ConditionMode,
    /// 单条条件的旧格式（保留用于读取旧配置；新写入统一用 `require_all`）
    #[serde(default)]
    pub require_state: Option<StateCondition>,
    /// 旧版单状态条件：None=始终生效，Some(true)=仅潜行，Some(false)=仅非潜行
    ///
    /// 保留用于读取旧配置。
    #[serde(default)]
    pub require_stealth: Option<bool>,
}

/// 多条生效条件的组合方式
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum ConditionMode {
    /// 全部满足才生效
    #[default]
    And,
    /// 任一满足即生效
    Or,
}

impl ConditionMode {
    /// 条件之间的连接词（用于界面与日志）
    pub fn joiner(self) -> &'static str {
        match self {
            ConditionMode::And => " 且 ",
            ConditionMode::Or => " 或 ",
        }
    }
}

/// 键位映射的生效条件
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct StateCondition {
    /// 状态 id，与 SLState 插件 `STATE_DEFS` 的顺序/名称一致（如 `stealthed`）
    pub state: String,
    /// 期望值：true = 该状态为真，false = 该状态为假
    pub value: bool,
}

impl KeyMapping {
    /// 组合键的全部按键（源按键 + 修饰键）
    pub fn combo_keys(&self) -> Vec<u32> {
        let mut keys = Vec::with_capacity(self.source_mods.len() + 1);
        keys.push(self.source_vk);
        keys.extend(self.source_mods.iter().copied());
        keys
    }

    /// 触发键：组合里真正被拦截的那个主键（跳过修饰键）
    ///
    /// 无论 Ctrl 被填在「源按键」还是「子命令」里，触发键都是 7，
    /// 这样 Ctrl+7 能上钩子拦截 7，同时 Ctrl 本体仍透传给游戏。
    pub fn trigger_vk(&self) -> u32 {
        if !is_modifier_vk(self.source_vk) {
            return self.source_vk;
        }
        self.source_mods
            .iter()
            .copied()
            .find(|&v| !is_modifier_vk(v))
            .unwrap_or(0)
    }

    /// 全部生效条件（状态 id, 期望值）
    ///
    /// 新字段 `require_all` 优先；单条旧字段与更旧的「仅潜行」字段依次兜底，
    /// 保证老配置继续可用。
    pub fn conditions(&self) -> Vec<(&str, bool)> {
        if !self.require_all.is_empty() {
            return self
                .require_all
                .iter()
                .map(|c| (c.state.as_str(), c.value))
                .collect();
        }
        if let Some(c) = &self.require_state {
            return vec![(c.state.as_str(), c.value)];
        }
        self.require_stealth
            .map(|v| vec![("stealthed", v)])
            .unwrap_or_default()
    }

    /// 是否设置了生效条件
    pub fn has_condition(&self) -> bool {
        !self.conditions().is_empty()
    }

    /// 当前角色状态是否满足本映射的生效条件
    ///
    /// 多条条件按 `require_mode` 组合（与 / 或）。状态未知（未装插件 /
    /// 未找到游戏进程）时该条件视为不满足，此时映射不生效，按键原样透传。
    pub fn matches_now(&self) -> bool {
        let conditions = self.conditions();
        if conditions.is_empty() {
            return true;
        }
        match self.require_mode {
            ConditionMode::And => conditions
                .iter()
                .all(|(id, want)| crate::wowmem::state(id) == Some(*want)),
            ConditionMode::Or => conditions
                .iter()
                .any(|(id, want)| crate::wowmem::state(id) == Some(*want)),
        }
    }

    /// 生效条件的展示文本（如「潜行中=是 且 战斗中=否」）
    pub fn condition_text(&self) -> String {
        let conditions = self.conditions();
        if conditions.is_empty() {
            return String::new();
        }
        let joiner = self.require_mode.joiner();
        conditions
            .iter()
            .map(|(id, want)| {
                format!(
                    "{}={}",
                    crate::wowmem::state_name(id),
                    if *want { "是" } else { "否" }
                )
            })
            .collect::<Vec<_>>()
            .join(joiner)
    }
}

impl Default for KeyMapping {
    fn default() -> Self {
        Self {
            source_vk: 0,
            source_mods: Vec::new(),
            tap_action: MappedAction::None,
            long_press_action: MappedAction::None,
            enabled: true,
            require_all: Vec::new(),
            require_mode: ConditionMode::And,
            require_state: None,
            require_stealth: None,
        }
    }
}

/// exe 所在目录（取不到时回退当前工作目录）
pub fn exe_dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."))
}

/// 读取程序自身配置，文件不存在或解析失败时返回默认值
pub fn load() -> ClientConfig {
    let candidates = [
        exe_dir().join(CONFIG_FILE_NAME),
        PathBuf::from(CONFIG_FILE_NAME),
    ];
    for path in candidates {
        if let Ok(text) = std::fs::read_to_string(&path) {
            match serde_json::from_str::<ClientConfig>(&text) {
                Ok(cfg) => return cfg,
                Err(e) => crate::runtime::log(format!("配置文件解析失败 {}: {}", path.display(), e)),
            }
        }
    }
    ClientConfig::default()
}

/// 保存程序自身配置
pub fn save(cfg: &ClientConfig) {
    let Ok(text) = serde_json::to_string_pretty(cfg) else {
        return;
    };
    let path = exe_dir().join(CONFIG_FILE_NAME);
    if let Err(e) = std::fs::write(&path, text) {
        crate::runtime::log(format!("配置保存失败 {}: {}", path.display(), e));
    }
}

/// 校验当前设置的游戏路径是否可用：Ok(路径) / Err(给用户看的错误说明)
pub fn check_wow_path(cfg: &ClientConfig) -> Result<String, String> {
    let path = cfg.wow_path.trim();
    if path.is_empty() {
        return Err("请先选择魔兽 exe 路径".to_string());
    }
    let file = Path::new(path);
    if !file.exists() {
        return Err(format!("exe 路径不存在：{}", path));
    }
    if !file.is_file() {
        return Err(format!("路径不是可执行文件：{}", path));
    }
    Ok(path.to_string())
}

/// 记录一条历史路径（去重、最新在前、最多保留 10 条）
pub fn remember_path(cfg: &mut ClientConfig, path: &str) {
    let path = path.trim();
    if path.is_empty() {
        return;
    }
    cfg.wow_path_history.retain(|p| p != path);
    cfg.wow_path_history.insert(0, path.to_string());
    cfg.wow_path_history.truncate(10);
}
