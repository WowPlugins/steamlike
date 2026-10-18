//! 运行期共享状态
//!
//! 全局钩子回调运行在钩子线程、网络线程、界面线程等多线程环境中，
//! 为了在钩子回调里保持「无锁、不阻塞」（钩子有 300ms 超时限制），
//! 所有钩子需要读取的运行期参数都放在原子量里；状态文本/日志这类
//! 只有非钩子线程会访问的数据才使用互斥锁。

use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32};
use std::sync::Mutex;

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
    /// 「居中落点」开关：true = 屏幕中央正下方、屏幕下边缘之外 1/10 屏高处；false = 屏幕正中央
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

/// 追加一条日志（同时输出到控制台，方便在 Winlator 终端排查）
///
/// 注意：绝对不要在钩子回调里调用（钩子回调必须保持无锁）。
pub fn log(msg: impl AsRef<str>) {
    let m = msg.as_ref();
    println!("{}", m);
    let mut guard = LOGS.lock().unwrap();
    guard.push(m.to_string());
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
