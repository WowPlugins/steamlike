//! 配置文件（JSON）读写
//!
//! 程序自己的配置保存在 exe 同目录的 `rust_client_config.json`；
//! 同时兼容读取 SteamLike App 导出的 `config.json`（其中只有 `wowPath`）。

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// 程序自身配置文件（exe 同目录）
pub const CONFIG_FILE_NAME: &str = "rust_client_config.json";
/// SteamLike App 导出的游戏路径配置
pub const EXPORTED_CONFIG_FILE_NAME: &str = "config.json";

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
    /// true = 从 config.json 读取游戏路径；false = 使用界面选择的路径（默认读取 config.json）
    pub wow_path_from_config_json: bool,
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
    /// 右键锁定方式：true = F11/F12，false = 真实鼠标右键长按（默认 F11/F12）
    pub right_button_fkeys: bool,
    /// 拦截路径中 WASD 触发时的归位落点：true = 屏幕左下角之外、下边缘下方 2/3 屏高处，
    /// false = 屏幕正中央（默认屏幕左下角之外）
    pub cursor_park_offscreen: bool,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            wow_path: String::new(),
            wow_path_history: Vec::new(),
            wow_path_from_config_json: true,
            auto_launch: true,
            auto_launch_delay_ms: 1000,
            skip_launch_on_input: true,
            server_ip: DEFAULT_IP.to_string(),
            server_port: DEFAULT_PORT,
            auto_connect: true,
            intercept_enabled: false,
            interaction_vk: 0x58,
            swap_vk: 0x31,
            long_press_ms: 500,
            right_button_fkeys: true,
            cursor_park_offscreen: true,
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

/// 从 SteamLike App 导出的 config.json 中读取 wowPath
pub fn read_exported_wow_path() -> Option<String> {
    let candidates = [
        exe_dir().join(EXPORTED_CONFIG_FILE_NAME),
        PathBuf::from(EXPORTED_CONFIG_FILE_NAME),
    ];
    for path in candidates {
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) else {
            continue;
        };
        if let Some(p) = value.get("wowPath").and_then(|v| v.as_str()) {
            if !p.trim().is_empty() {
                return Some(p.to_string());
            }
        }
    }
    None
}

/// 按界面开关决定最终要启动的游戏路径
pub fn resolve_wow_path(cfg: &ClientConfig) -> Option<String> {
    if cfg.wow_path_from_config_json {
        match read_exported_wow_path() {
            Some(p) => Some(p),
            None => {
                crate::runtime::log("未在 config.json 中读到 wowPath，跳过启动游戏");
                None
            }
        }
    } else if cfg.wow_path.trim().is_empty() {
        crate::runtime::log("界面选择的游戏路径为空，跳过启动游戏");
        None
    } else {
        Some(cfg.wow_path.clone())
    }
}

/// 校验当前设置的游戏路径是否可用：Ok(路径) / Err(给用户看的错误说明)
pub fn check_wow_path(cfg: &ClientConfig) -> Result<String, String> {
    if cfg.wow_path_from_config_json {
        return match read_exported_wow_path() {
            Some(p) if Path::new(&p).is_file() => Ok(p),
            Some(p) => Err(format!("config.json 中的 exe 路径不存在：{}", p)),
            None => Err(format!(
                "{} 中未找到 wowPath，请先让 APK 导出配置",
                EXPORTED_CONFIG_FILE_NAME
            )),
        };
    }
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
