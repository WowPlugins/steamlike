//! 运行期共享状态
//!
//! 全局钩子回调运行在钩子线程、网络线程、界面线程等多线程环境中，
//! 为了在钩子回调里保持「无锁、不阻塞」（钩子有 300ms 超时限制），
//! 所有钩子需要读取的运行期参数都放在原子量里；状态文本/日志这类
//! 只有非钩子线程会访问的数据才使用互斥锁。

use std::fs::File;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32};
use std::sync::Mutex;
use std::sync::OnceLock;

use windows::Win32::System::SystemInformation::GetLocalTime;

/// 键鼠拦截运行期参数与锁存状态
pub struct Runtime {
    /// 拦截总开关（默认关闭）
    pub intercept_enabled: AtomicBool,
    /// 右键锁定方式：true = 发送 F11/F12，false = 真实鼠标右键长按
    pub right_button_fkeys: AtomicBool,
    /// 交互键虚拟键码
    pub interaction_vk: AtomicU32,
    /// 长按交换键虚拟键码
    pub swap_vk: AtomicU32,
    /// 长按判定阈值（毫秒）
    pub long_press_ms: AtomicU32,
    /// 鼠标左键长按锁存中
    pub left_locked: AtomicBool,
    /// 鼠标右键长按锁存中
    pub right_locked: AtomicBool,
    /// 上一次鼠标光标位置（高 32 位 X，低 32 位 Y），用于把绝对移动换算成相对移动
    pub last_mouse: AtomicI64,
    /// 「居中落点」开关：true = 屏幕中央正下方、屏幕下边缘之外 2/3 屏高处；false = 屏幕正中央
    pub cursor_park_offscreen: AtomicBool,
    /// 交互键序列开始时记录的光标位置（高 32 位 X，低 32 位 Y），供序列末尾还原
    pub saved_cursor: AtomicI64,
}

/// `saved_cursor` 的「尚未记录」哨兵值
pub const CURSOR_UNSET: i64 = i64::MIN;

pub static RT: Runtime = Runtime {
    intercept_enabled: AtomicBool::new(false),
    right_button_fkeys: AtomicBool::new(true),
    interaction_vk: AtomicU32::new(0x58), // X
    swap_vk: AtomicU32::new(0x31),        // 1
    long_press_ms: AtomicU32::new(500),
    left_locked: AtomicBool::new(false),
    right_locked: AtomicBool::new(false),
    last_mouse: AtomicI64::new(0),
    cursor_park_offscreen: AtomicBool::new(true),
    saved_cursor: AtomicI64::new(CURSOR_UNSET),
};

/// APK 服务器地址与端口（界面可改，网络线程每次重连时读取）
pub static BRIDGE_TARGET: Mutex<(String, u16)> = Mutex::new((String::new(), 0));
/// 是否连接 APK 服务器
pub static WANT_CONNECT: AtomicBool = AtomicBool::new(true);
/// 当前是否已连上 APK 服务器
pub static CONNECTED: AtomicBool = AtomicBool::new(false);
/// 网络状态文本（界面展示）
pub static STATUS: Mutex<String> = Mutex::new(String::new());

static LOGS: Mutex<Vec<String>> = Mutex::new(Vec::new());
const MAX_LOGS: usize = 200;

/// 文件日志句柄（exe 目录下的 inputbridge_client.log，启动时清空重写）
static LOG_FILE: Mutex<Option<File>> = Mutex::new(None);

/// 初始化文件日志：清空 exe 目录下旧的 inputbridge_client.log
///
/// 必须在单实例检查通过之后调用，避免截断已在运行实例的日志文件。
pub fn init_file_log() {
    let Ok(exe) = std::env::current_exe() else {
        return;
    };
    let Some(dir) = exe.parent() else {
        return;
    };
    let path = dir.join("inputbridge_client.log");
    match File::create(&path) {
        Ok(f) => {
            *LOG_FILE.lock().unwrap() = Some(f);
        }
        Err(e) => println!("无法打开日志文件 {:?}：{}", path, e),
    }
}

/// 当前本地时间戳文本，格式 [HH:MM:SS.mmm]
fn timestamp() -> String {
    let st = unsafe { GetLocalTime() };
    format!(
        "[{:02}:{:02}:{:02}.{:03}]",
        st.wHour, st.wMinute, st.wSecond, st.wMilliseconds
    )
}

/// 客户端版本号（进程内缓存一次）
///
/// 优先读取 exe 目录下的 version.txt（APK 导出时写入，仅改版本号时无需重编 Rust），
/// 读取失败时回退到编译期注入的 STEAMLIKE_VERSION，再回退到 Cargo.toml 的版本。
pub fn version() -> &'static str {
    static VERSION: OnceLock<String> = OnceLock::new();
    VERSION.get_or_init(|| {
        let from_file = std::env::current_exe()
            .ok()
            .and_then(|exe| exe.parent().map(|dir| dir.join("version.txt")))
            .and_then(|path| std::fs::read_to_string(path).ok())
            .map(|text| text.trim().to_string())
            .filter(|v| !v.is_empty());
        from_file.unwrap_or_else(|| {
            option_env!("STEAMLIKE_VERSION")
                .unwrap_or(env!("CARGO_PKG_VERSION"))
                .to_string()
        })
    })
}

/// 远端（APK）下发的版本号：连接成功后自动同步，界面优先显示它
static REMOTE_VERSION: Mutex<Option<String>> = Mutex::new(None);

/// 记录 APK 下发的版本号
pub fn set_remote_version(v: String) {
    if !v.is_empty() {
        *REMOTE_VERSION.lock().unwrap() = Some(v);
    }
}

/// 界面显示的版本号：优先用 APK 下发的（连接后），否则用 exe 自带版本
pub fn display_version() -> String {
    if let Some(v) = REMOTE_VERSION.lock().unwrap().clone() {
        return v;
    }
    version().to_string()
}

/// 追加一条日志：同时输出到控制台、日志文件与界面日志区
///
/// 注意：绝对不要在钩子回调里调用（钩子回调必须保持无锁）。
pub fn log(msg: impl AsRef<str>) {
    let line = format!("{} {}", timestamp(), msg.as_ref());
    println!("{}", line);
    // 写入文件日志（未初始化或写失败时静默忽略，不影响运行）
    if let Ok(mut guard) = LOG_FILE.lock() {
        if let Some(f) = guard.as_mut() {
            use std::io::Write;
            let _ = writeln!(f, "{}", line);
            let _ = f.flush();
        }
    }
    let mut guard = LOGS.lock().unwrap();
    guard.push(line);
    if guard.len() > MAX_LOGS {
        let excess = guard.len() - MAX_LOGS;
        guard.drain(0..excess);
    }
}

/// 取日志快照（界面每帧读取）
pub fn logs_snapshot() -> Vec<String> {
    LOGS.lock().unwrap().clone()
}

pub fn set_status(s: impl Into<String>) {
    *STATUS.lock().unwrap() = s.into();
}

pub fn status_snapshot() -> String {
    STATUS.lock().unwrap().clone()
}

/// 把光标坐标打包进一个 i64，保证读取时 X/Y 是同一时刻的值
pub fn pack_pos(x: i32, y: i32) -> i64 {
    ((x as i64) << 32) | ((y as i64) & 0xFFFF_FFFF)
}

pub fn unpack_pos(v: i64) -> (i32, i32) {
    ((v >> 32) as i32, v as i32)
}
