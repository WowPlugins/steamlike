//! WoW 剪贴板同步
//!
//! 轮询 Windows 剪贴板序列号，内容一旦变化：
//! 1. 开关开启时，把文本追加写入 exe 目录下的 `clipboard_log.txt`（每条带时间戳）；
//! 2. 已连接 APK 时，通过 TCP 把文本发送到手机剪贴板。
//!
//! 用轮询而不是监听 Ctrl+C 按键：钩子会放行带 MAGIC 标记的自注入事件，
//! 且游戏处理复制是异步的（钩子看到按键时剪贴板还没更新）。
//! 序列号变化与复制来源无关，手柄映射的 Ctrl+C、物理键盘、触摸复制都能捕获，
//! 并且同一内容只会处理一次，天然去重。

use std::io::Write;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

use windows::Win32::System::SystemInformation::GetLocalTime;
use winapi::um::winbase::{GlobalAlloc, GlobalFree, GlobalLock, GlobalUnlock, GMEM_MOVEABLE};
use winapi::um::winuser::{
    CloseClipboard, EmptyClipboard, GetClipboardData, GetClipboardSequenceNumber, OpenClipboard,
    SetClipboardData, CF_UNICODETEXT,
};

use crate::bridge;
use crate::config;
use crate::runtime::{self, RT};

/// 本程序自己写入剪贴板后的序列号（轮询线程跳过，避免把注入的中文当成游戏复制内容回传手机）
static OWN_SEQ: AtomicU32 = AtomicU32::new(0);

/// 把文本写入 Windows 剪贴板（用于 APK 软键盘输入中文后粘贴进游戏）
pub fn write_text(text: &str) -> bool {
    unsafe {
        if OpenClipboard(std::ptr::null_mut()) == 0 {
            return false;
        }
        EmptyClipboard();
        let mut wide: Vec<u16> = text.encode_utf16().collect();
        wide.push(0);
        let handle = GlobalAlloc(GMEM_MOVEABLE, wide.len() * 2);
        if handle.is_null() {
            CloseClipboard();
            return false;
        }
        let ptr = GlobalLock(handle) as *mut u16;
        if ptr.is_null() {
            GlobalFree(handle);
            CloseClipboard();
            return false;
        }
        std::ptr::copy_nonoverlapping(wide.as_ptr(), ptr, wide.len());
        GlobalUnlock(handle);
        // 成功交给系统后内存归剪贴板所有，不能再 GlobalFree
        if SetClipboardData(CF_UNICODETEXT as u32, handle).is_null() {
            GlobalFree(handle);
            CloseClipboard();
            return false;
        }
        CloseClipboard();
        // 记下写入后的序列号，让轮询线程跳过这次变化
        OWN_SEQ.store(GetClipboardSequenceNumber(), Ordering::SeqCst);
        true
    }
}

/// 轮询间隔（毫秒）
const POLL_INTERVAL_MS: u64 = 300;

/// 本地记录文件名（与 exe 同目录）
const LOG_FILE_NAME: &str = "clipboard_log.txt";

/// 启动剪贴板轮询线程（程序启动时调用一次）
pub fn start() {
    std::thread::Builder::new()
        .name("clipboard".to_string())
        .spawn(thread_main)
        .expect("剪贴板线程创建失败");
}

fn thread_main() {
    // 启动时先记下当前序列号，避免把已在剪贴板里的旧内容当成新变化发出去
    let mut last_seq = unsafe { GetClipboardSequenceNumber() };
    // 上次处理过的文本与时间：Winlator 会周期性刷新剪贴板（序列号不停变化但内容相同），
    // 用内容去重避免每 300ms 都写一次文件和发一次手机
    let mut last_text: Option<(String, std::time::Instant)> = None;

    loop {
        std::thread::sleep(Duration::from_millis(POLL_INTERVAL_MS));

        let seq = unsafe { GetClipboardSequenceNumber() };
        if seq == last_seq {
            continue;
        }
        // 自己刚写入的剪贴板（软键盘粘贴用），不计入同步
        if seq == OWN_SEQ.load(Ordering::SeqCst) {
            last_seq = seq;
            continue;
        }
        // 开关关闭时记录序列号但不处理，保证重新开启后不会补发关闭期间的内容
        if !RT.clipboard_sync.load(Ordering::Relaxed) {
            last_seq = seq;
            continue;
        }

        // 读取失败（别的程序占用剪贴板）或内容不是文本时保留旧序列号，下一轮重试
        let Some(text) = read_text() else { continue };
        last_seq = seq;
        let trimmed = text.trim();
        if trimmed.is_empty() {
            continue;
        }
        // 内容与上次相同且间隔很短 → 视为序列号抖动（Winlator 会把手机剪贴板反复刷进 Wine）
        if let Some((prev, at)) = &last_text {
            if prev == trimmed && at.elapsed() < std::time::Duration::from_secs(2) {
                continue;
            }
        }
        last_text = Some((trimmed.to_string(), std::time::Instant::now()));

        // 始终写本地文件（无论是否已连接 APK）
        append_to_file(&text);
        // 已连接时同时发送到手机；未连接时 send_text 内部直接丢弃
        bridge::send_text(&text);
        runtime::log(format!(
            "[剪贴板] 已同步 {} 字节文本（本地记录 + {}）",
            text.len(),
            if runtime::CONNECTED.load(Ordering::Relaxed) {
                "发送手机"
            } else {
                "未连接 APK"
            }
        ));
    }
}

/// 读取剪贴板的 Unicode 文本；剪贴板中没有文本时返回 None
fn read_text() -> Option<String> {
    unsafe {
        // 打开剪贴板失败时直接跳过本轮（别的程序可能正占用）
        if OpenClipboard(std::ptr::null_mut()) == 0 {
            return None;
        }
        let handle = GetClipboardData(CF_UNICODETEXT as u32);
        let result = if handle.is_null() {
            None
        } else {
            let ptr = GlobalLock(handle) as *const u16;
            if ptr.is_null() {
                None
            } else {
                // CF_UNICODETEXT 是以 NUL 结尾的 UTF-16 字符串
                let mut len = 0usize;
                while *ptr.add(len) != 0 {
                    len += 1;
                }
                let units = std::slice::from_raw_parts(ptr, len);
                let text = String::from_utf16_lossy(units);
                let _ = GlobalUnlock(handle);
                Some(text)
            }
        };
        CloseClipboard();
        result
    }
}

/// 把文本追加到 exe 同目录的本地记录文件，每条记录带日期时间戳
fn append_to_file(text: &str) {
    let path = config::exe_dir().join(LOG_FILE_NAME);
    match std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
    {
        Ok(mut file) => {
            let _ = writeln!(file, "[{}] {}", timestamp_text(), text);
        }
        Err(e) => runtime::log(format!("写入剪贴板记录失败 {}：{}", path.display(), e)),
    }
}

/// 当前本地时间文本，格式 `YYYY-MM-DD HH:MM:SS`
fn timestamp_text() -> String {
    let st = unsafe { GetLocalTime() };
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}",
        st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond
    )
}
