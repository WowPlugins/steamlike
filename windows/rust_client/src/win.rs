//! Windows 输入注入与常量
//!
//! 所有注入事件都会带上 [`MAGIC`] 标记写入 `dwExtraInfo`，
//! 钩子回调据此识别「本程序自己注入的事件」并直接放行，
//! 避免自己注入的事件被再次拦截（递归）。

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use windows::core::{w, PCWSTR};
use windows::Win32::Foundation::POINT;
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::WindowsAndMessaging::{
    FindWindowW, GetCursorPos, GetSystemMetrics, SetCursorPos, SetWindowTextW, SM_CXSCREEN,
    SM_CYSCREEN,
};

use crate::runtime::{log, pack_pos, unpack_pos, CURSOR_UNSET, RT};

/// 注入事件标记：钩子识别自己注入的事件用
pub const MAGIC: usize = 0x534C_4B52; // "SLKR"

/* ===== 虚拟键码 ===== */
pub const VK_W: u16 = 0x57;
pub const VK_A: u16 = 0x41;
pub const VK_S: u16 = 0x53;
pub const VK_D: u16 = 0x44;
pub const VK_F11: u16 = 0x7A;
pub const VK_F12: u16 = 0x7B;

/* ===== 鼠标按钮编号（与 Android 协议一致） ===== */
pub const BTN_LEFT: u8 = 0;
pub const BTN_RIGHT: u8 = 1;
pub const BTN_MIDDLE: u8 = 2;
pub const BTN_FORWARD: u8 = 3;
pub const BTN_BACK: u8 = 4;

/// 已注入且尚未释放的按键（用于「释放所有按键」）
static PRESSED_KEYS: [AtomicBool; 256] = [const { AtomicBool::new(false) }; 256];
/// 已注入且尚未释放的鼠标按钮
static PRESSED_BUTTONS: [AtomicBool; 5] = [const { AtomicBool::new(false) }; 5];

fn key_input(vk: u16, down: bool) -> INPUT {
    let mut input = INPUT::default();
    input.r#type = INPUT_KEYBOARD;
    input.Anonymous.ki = KEYBDINPUT {
        wVk: VIRTUAL_KEY(vk),
        wScan: 0,
        dwFlags: if down {
            KEYBD_EVENT_FLAGS(0)
        } else {
            KEYEVENTF_KEYUP
        },
        time: 0,
        dwExtraInfo: MAGIC,
    };
    input
}

fn apply_button_flag(button: u8, down: bool) -> Option<(MOUSE_EVENT_FLAGS, u32)> {
    let pair = match button {
        BTN_LEFT => (
            if down { MOUSEEVENTF_LEFTDOWN } else { MOUSEEVENTF_LEFTUP },
            0,
        ),
        BTN_RIGHT => (
            if down { MOUSEEVENTF_RIGHTDOWN } else { MOUSEEVENTF_RIGHTUP },
            0,
        ),
        BTN_MIDDLE => (
            if down { MOUSEEVENTF_MIDDLEDOWN } else { MOUSEEVENTF_MIDDLEUP },
            0,
        ),
        BTN_FORWARD => (if down { MOUSEEVENTF_XDOWN } else { MOUSEEVENTF_XUP }, 1),
        BTN_BACK => (if down { MOUSEEVENTF_XDOWN } else { MOUSEEVENTF_XUP }, 2),
        _ => return None,
    };
    Some(pair)
}

fn button_input(button: u8, down: bool) -> Option<INPUT> {
    let (dw_flags, mouse_data) = apply_button_flag(button, down)?;
    let mut input = INPUT::default();
    input.r#type = INPUT_MOUSE;
    input.Anonymous.mi = MOUSEINPUT {
        dx: 0,
        dy: 0,
        mouseData: mouse_data,
        dwFlags: dw_flags,
        time: 0,
        dwExtraInfo: MAGIC,
    };
    Some(input)
}

fn send(inputs: &[INPUT]) {
    if inputs.is_empty() {
        return;
    }
    unsafe {
        SendInput(inputs, std::mem::size_of::<INPUT>() as i32);
    }
}

/// 注入键盘事件
pub fn inject_key(vk: u16, down: bool) {
    send(&[key_input(vk, down)]);
    if (vk as usize) < 256 {
        PRESSED_KEYS[vk as usize].store(down, Ordering::Relaxed);
    }
}

/// 注入鼠标移动
///
/// 按「游戏是否在运行」分两条路：
/// - **游戏运行中**：纯 `MOUSEEVENTF_MOVE` 相对位移（与 C 版一致），mouselook 和
///   关闭硬件指针后的游戏界面都靠这条路径，不要在这条路径上加任何光标搬运/裁剪。
/// - **Winlator 桌面（游戏未运行）**：改用 `SetCursorPos` 绝对定位，也就是触摸滑屏
///   走的那条路径。桌面下 Winlator 的 X 服务器不处理 Wine 转发来的相对位移，
///   注入后指针纹丝不动（`GetCursorPos` 也读不到变化），只有绝对定位才能真正搬动指针。
///
/// 绝对定位每次都用 `GetCursorPos` 的实时位置做基准：桌面下触摸也在移动指针，
/// 自维护坐标会和真实位置脱节，把指针拽回旧位置。
pub fn inject_mouse_move(dx: i32, dy: i32) {
    if dx == 0 && dy == 0 {
        return;
    }
    if !in_game() {
        let (x, y) = current_cursor();
        let (w, h) = screen_size();
        move_cursor_to(
            (x + dx).clamp(0, w.saturating_sub(1)),
            (y + dy).clamp(0, h.saturating_sub(1)),
        );
        return;
    }
    let mut input = INPUT::default();
    input.r#type = INPUT_MOUSE;
    input.Anonymous.mi = MOUSEINPUT {
        dx,
        dy,
        mouseData: 0,
        dwFlags: MOUSEEVENTF_MOVE,
        time: 0,
        dwExtraInfo: MAGIC,
    };
    send(&[input]);
}

/// 一次指针移动调用中判断「游戏是否在运行」的最大缓存时长（毫秒）
const GAME_CHECK_MS: u64 = 500;

/// 鼠标移动调用时的时间戳（毫秒）
fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn screen_size() -> (i32, i32) {
    unsafe { (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)) }
}

/// 按当前标题查找本程序窗口并改名，用于连接后把标题栏版本号同步为 APK 版本
///
/// eframe 0.22 没有运行时修改标题的 API，这里直接调用 Win32 `FindWindowW` + `SetWindowTextW`。
///
/// @param old_title 当前窗口标题（必须与创建时一致才能找到窗口）
/// @param new_title 新窗口标题
/// @return 是否成功改名
pub fn rename_own_window(old_title: &str, new_title: &str) -> bool {
    let old_w: Vec<u16> = old_title.encode_utf16().chain(std::iter::once(0)).collect();
    let new_w: Vec<u16> = new_title.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        let hwnd = FindWindowW(PCWSTR::null(), PCWSTR(old_w.as_ptr()));
        if hwnd.0 == 0 {
            return false;
        }
        SetWindowTextW(hwnd, PCWSTR(new_w.as_ptr())).is_ok()
    }
}

/// WoW 主窗口是否还存在（带缓存，避免每帧枚举窗口）
///
/// 按窗口类名 `GxWindowClass` 判断，找不到再按标题兜底。
/// 游戏窗口可能嵌在 Wine 虚拟桌面里，但仍然是可被 `FindWindowW` 找到的顶层窗口。
fn in_game() -> bool {
    static LAST_CHECK: AtomicU64 = AtomicU64::new(0);
    static IN_GAME: AtomicBool = AtomicBool::new(true);

    let now = now_ms();
    if now.saturating_sub(LAST_CHECK.load(Ordering::Relaxed)) < GAME_CHECK_MS {
        return IN_GAME.load(Ordering::Relaxed);
    }
    let found = unsafe {
        FindWindowW(w!("GxWindowClass"), PCWSTR::null()).0 != 0
            || FindWindowW(PCWSTR::null(), w!("World of Warcraft")).0 != 0
    };
    LAST_CHECK.store(now, Ordering::Relaxed);
    if IN_GAME.swap(found, Ordering::Relaxed) != found {
        log(format!(
            "[指针] 切换为{}模式",
            if found {
                "游戏内相对位移"
            } else {
                "桌面绝对定位（SetCursorPos）"
            }
        ));
    }
    found
}

/// 注入鼠标按钮按下/释放
pub fn inject_mouse_button(button: u8, down: bool) {
    let Some(input) = button_input(button, down) else {
        return;
    };
    send(&[input]);
    if (button as usize) < 5 {
        PRESSED_BUTTONS[button as usize].store(down, Ordering::Relaxed);
    }
}

/// 注入鼠标滚轮（正=上滚，负=下滚）
pub fn inject_mouse_wheel(delta: i16) {
    let mut input = INPUT::default();
    input.r#type = INPUT_MOUSE;
    input.Anonymous.mi = MOUSEINPUT {
        dx: 0,
        dy: 0,
        mouseData: delta as i32 as u32,
        dwFlags: MOUSEEVENTF_WHEEL,
        time: 0,
        dwExtraInfo: MAGIC,
    };
    send(&[input]);
}

/// 读取当前光标位置
pub fn current_cursor() -> (i32, i32) {
    let mut pt = POINT::default();
    unsafe {
        let _ = GetCursorPos(&mut pt);
    }
    (pt.x, pt.y)
}

/// 移动光标并把结果位置同步给钩子的基准坐标
///
/// `SetCursorPos` 会触发一次系统级 `WM_MOUSEMOVE`，钩子会把它当成真实移动而
/// 再注入一份等量相对位移（造成光标多跳一次）。这里把落点写成钩子的基准坐标，
/// 使该次事件算出的位移为 0，从而让光标精确停在目标位置。
pub fn move_cursor_to(x: i32, y: i32) {
    unsafe {
        let _ = SetCursorPos(x, y);
    }
    let (ax, ay) = current_cursor();
    RT.last_mouse.store(pack_pos(ax, ay), Ordering::Relaxed);
}

/// 把鼠标光标移动到主屏正中央
pub fn center_cursor() {
    unsafe {
        let cx = GetSystemMetrics(SM_CXSCREEN) / 2;
        let cy = GetSystemMetrics(SM_CYSCREEN) / 2;
        move_cursor_to(cx, cy);
    }
}

/// 把鼠标光标停放到「屏幕左下角之外、屏幕下边缘下方 2/3 屏高处」
///
/// 视角锁定前把光标停到屏幕外，可避免它停在界面元素上弹出 tooltip；
/// 落点取左下角，让光标尽量远离游戏画面中央的交互区域。
pub fn park_cursor_offscreen() {
    unsafe {
        let cy = GetSystemMetrics(SM_CYSCREEN);
        move_cursor_to(0, cy + cy * 2 / 3);
    }
}

/// 按「居中落点」开关把光标归位：true = 屏幕左下角之外，false = 屏幕正中央
pub fn position_cursor(offscreen: bool) {
    if offscreen {
        park_cursor_offscreen();
    } else {
        center_cursor();
    }
}

/// 记录当前光标位置，供 [restore_cursor] 还原（交互键序列开始处调用）
pub fn save_cursor() {
    let (x, y) = current_cursor();
    RT.saved_cursor.store(pack_pos(x, y), Ordering::Relaxed);
}

/// 把光标还原到 [save_cursor] 记录的位置（尚未记录过时不做任何事）
pub fn restore_cursor() {
    let saved = RT.saved_cursor.load(Ordering::Relaxed);
    if saved == CURSOR_UNSET {
        return;
    }
    let (x, y) = unpack_pos(saved);
    move_cursor_to(x, y);
}

/// 一次性释放本程序注入过的全部按键与鼠标按钮
pub fn release_all() {
    let mut inputs: Vec<INPUT> = Vec::new();
    for i in 0..256usize {
        if PRESSED_KEYS[i].swap(false, Ordering::Relaxed) {
            inputs.push(key_input(i as u16, false));
        }
    }
    for b in 0..5u8 {
        if PRESSED_BUTTONS[b as usize].swap(false, Ordering::Relaxed) {
            if let Some(input) = button_input(b, false) {
                inputs.push(input);
            }
        }
    }
    send(&inputs);
}

/// WASD 在钩子里被「接管」的状态（按下后由本程序注入，抬起时也要自己注入）
pub static WASD_TAKEN: [AtomicBool; 4] = [const { AtomicBool::new(false) }; 4];

/// WASD 键 → 接管槽位下标
pub fn wasd_index(vk: u16) -> Option<usize> {
    match vk {
        VK_W => Some(0),
        VK_A => Some(1),
        VK_S => Some(2),
        VK_D => Some(3),
        _ => None,
    }
}

/* ===== 系统时间同步 ===== */

use windows::Win32::Foundation::{CloseHandle, FILETIME, HANDLE, LUID, SYSTEMTIME};
use windows::Win32::Security::{
    AdjustTokenPrivileges, LUID_AND_ATTRIBUTES, LookupPrivilegeValueW, SE_PRIVILEGE_ENABLED,
    TOKEN_ADJUST_PRIVILEGES, TOKEN_PRIVILEGES, TOKEN_QUERY,
};
use windows::Win32::System::SystemInformation::{
    GetLocalTime, GetSystemTimeAsFileTime, SetSystemTime,
};
use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};
use windows::Win32::System::Time::{FileTimeToSystemTime, SystemTimeToTzSpecificLocalTime};

/// 格式化 SYSTEMTIME 为可读文本
fn fmt_st(st: &SYSTEMTIME) -> String {
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
        st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, st.wMilliseconds
    )
}

/// 启用 SeSystemtimePrivilege 特权（SetSystemTime 需要；失败时仅记录，不阻断）
fn enable_systemtime_privilege() -> bool {
    unsafe {
        let mut token = HANDLE::default();
        if OpenProcessToken(
            GetCurrentProcess(),
            TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY,
            &mut token,
        )
        .is_err()
        {
            return false;
        }
        let mut tp = TOKEN_PRIVILEGES {
            PrivilegeCount: 1,
            Privileges: [LUID_AND_ATTRIBUTES {
                Luid: LUID::default(),
                Attributes: SE_PRIVILEGE_ENABLED,
            }],
        };
        if LookupPrivilegeValueW(None, w!("SeSystemtimePrivilege"), &mut tp.Privileges[0].Luid)
            .is_err()
        {
            let _ = CloseHandle(token);
            return false;
        }
        let ok = AdjustTokenPrivileges(
            token,
            false,
            Some(&tp),
            0,
            None,
            None,
        )
        .is_ok();
        let _ = CloseHandle(token);
        ok
    }
}

/// 把 Windows 系统时间校准为 APK 下发的 epoch 毫秒
///
/// Winlator 容器内的系统时间容易漂移，APK 在客户端每次连上时下发一次准确时间；
/// 与当前时间偏差小于 500ms 时跳过，避免频繁改写时钟。
pub fn sync_system_time(epoch_millis: u64) {
    // epoch 毫秒 → FILETIME（1601-01-01 起的 100ns 单位）
    let target_ft: i64 = ((epoch_millis as i64) + 11_644_473_600_000) * 10_000;
    unsafe {
        // windows 0.52 的 GetSystemTimeAsFileTime 按值返回 FILETIME
        let now = GetSystemTimeAsFileTime();
        let now_ft = ((now.dwHighDateTime as i64) << 32) | now.dwLowDateTime as i64;
        let diff_ms = (target_ft - now_ft) / 10_000;
        if diff_ms.abs() < 500 {
            log(format!("[时间同步] 系统时间已准确（偏差 {} ms），跳过", diff_ms));
            return;
        }

        if !enable_systemtime_privilege() {
            log("[时间同步] SeSystemtimePrivilege 启用失败，仍尝试直接校准");
        }

        let before = GetLocalTime();
        let ft = FILETIME {
            dwLowDateTime: (target_ft & 0xFFFF_FFFF) as u32,
            dwHighDateTime: ((target_ft >> 32) & 0xFFFF_FFFF) as u32,
        };
        let mut utc = SYSTEMTIME::default();
        if FileTimeToSystemTime(&ft, &mut utc).is_err() {
            log("[时间同步] 目标时间换算失败，跳过校准");
            return;
        }
        if SetSystemTime(&utc).is_err() {
            log("[时间同步] SetSystemTime 调用失败，无法校准系统时间");
            return;
        }
        // 校准后本地时间应等于目标 UTC 对应的本地时间
        let mut local = SYSTEMTIME::default();
        let _ = SystemTimeToTzSpecificLocalTime(None, &utc, &mut local);
        log(format!(
            "[时间同步] 已校准系统时间：{} → {}（原偏差 {} ms）",
            fmt_st(&before),
            fmt_st(&local),
            diff_ms
        ));
    }
}
