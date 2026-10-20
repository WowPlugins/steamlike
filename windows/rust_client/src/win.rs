//! Windows 输入注入与常量
//!
//! 所有注入事件都会带上 [`MAGIC`] 标记写入 `dwExtraInfo`，
//! 钩子回调据此识别「本程序自己注入的事件」并直接放行，
//! 避免自己注入的事件被再次拦截（递归）。

use std::sync::atomic::{AtomicBool, Ordering};
use windows::Win32::Foundation::POINT;
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::WindowsAndMessaging::{
    ClipCursor, GetCursorPos, GetForegroundWindow, GetSystemMetrics, GetWindowTextW, SetCursorPos,
    SM_CXSCREEN, SM_CYSCREEN,
};

use crate::runtime::{pack_pos, unpack_pos, CURSOR_UNSET, RT};

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

/// 注入鼠标相对移动
///
/// 相对移动只会更新 Wine 内部的「Windows 光标坐标」，Winlator 那层真正的
/// 硬件指针（屏幕上画出来的 WoW 指针）不会跟着刷新。表现就是：松开右键退出
/// mouselook 后，WoW 的 hover/tooltip 会随摇杆变化，但可见指针停在原地不动
/// （只有直接滑屏幕——走 X 绝对坐标——才能移动它）。
///
/// 因此在「游戏没有接管光标」时，注入相对位移后再向当前位置做一次 1px 的
/// `SetCursorPos` 往返：位置先变 1px 再变回来，强制驱动把硬件指针同步到
/// 内部坐标，而起点/终点相同，游戏侧收到的总位移不变。
///
/// 游戏接管光标（按住左/右键的 mouselook，或 F11/F12 模式已切到 mouselook）时不做同步：
/// 此时指针是隐藏的，WoW 自己每帧把光标钉在视口中心，插值反而会干扰视角。
pub fn inject_mouse_move(dx: i32, dy: i32) {
    if dx == 0 && dy == 0 {
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

    refresh_foreground_state();
    if !cursor_captured() {
        sync_hardware_cursor();
    }
}

/// 前台窗口是否为魔兽世界
///
/// 只有游戏在前台时，左右键才意味着 mouselook；切到桌面（Wine 桌面或其他程序）后
/// 必须重新同步硬件指针，否则会出现「右摇杆让提示在变、但可见指针不动」。
pub fn game_in_foreground() -> bool {
    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.0 == 0 {
            return false;
        }
        let mut buf = [0u16; 128];
        let len = GetWindowTextW(hwnd, &mut buf);
        if len <= 0 {
            return false;
        }
        let title = String::from_utf16_lossy(&buf[..len as usize]);
        title.to_ascii_lowercase().contains("world of warcraft")
    }
}

/// 前台是否处于游戏的缓存值（避免每次移动都取窗口标题）
static GAME_FOREGROUND: AtomicBool = AtomicBool::new(false);

/// 前台从游戏切走时清掉所有陈旧状态
///
/// 大退/切出游戏时若右键仍处于锁存状态（游戏被杀时按着右键），
/// `cursor_captured()` 会一直返回 true 而永久跳过硬件指针同步；
/// 这里清除按键锁存、mouselook 标记与裁剪矩形，让桌面上的指针恢复跟随。
fn refresh_foreground_state() {
    let now = game_in_foreground();
    if GAME_FOREGROUND.swap(now, Ordering::Relaxed) && !now {
        RT.fkeys_mouselook.store(false, Ordering::Relaxed);
        unsafe {
            let _ = ClipCursor(None);
        }
        release_all();
    }
}

/// 光标是否正被游戏接管（mouselook）：按住左/右键，或 F11/F12 模式已切到 mouselook
///
/// 不看左右键锁存标志：F11/F12 模式下锁存标志只在 WASD 路径里维护，
/// 手柄侧解锁不会清除它，会长期停在 true 而漏掉同步。
fn cursor_captured() -> bool {
    if !GAME_FOREGROUND.load(Ordering::Relaxed) {
        return false;
    }
    if RT.fkeys_mouselook.load(Ordering::Relaxed) {
        return true;
    }
    if PRESSED_BUTTONS[BTN_LEFT as usize].load(Ordering::Relaxed)
        || PRESSED_BUTTONS[BTN_RIGHT as usize].load(Ordering::Relaxed)
    {
        return true;
    }
    // 触摸直接按住左/右键（Winlator 物理输入）同样会进入 mouselook
    unsafe {
        GetAsyncKeyState(VK_LBUTTON.0 as i32) < 0 || GetAsyncKeyState(VK_RBUTTON.0 as i32) < 0
    }
}

/// 把硬件指针同步到 Wine 内部的光标坐标（1px 往返，净位移为 0）
///
/// 先解除裁剪矩形：游戏退出后残留的裁剪会把 `SetCursorPos` 钳在原地，
/// 导致桌面上指针卡死。
fn sync_hardware_cursor() {
    let (x, y) = current_cursor();
    unsafe {
        let _ = ClipCursor(None);
        let _ = SetCursorPos(x + 1, y);
        let _ = SetCursorPos(x, y);
    }
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
///
/// 调用点都是「退出 mouselook 之后」的归位（解除锁定 / 交互键），因此先把
/// WoW 在 mouselook 里设置的裁剪矩形解除：裁剪还留着时 `SetCursorPos` 会被
/// 钳制在矩形内，硬件指针也停在原处，出现「指针卡住、hover 却在动」。
///
/// 裁剪矩形属于设置它的线程，只有游戏进程退出才会自动解除，这解释了为什么
/// 卡住后 /reload 无效、只能大退游戏。
pub fn center_cursor() {
    unsafe {
        let _ = ClipCursor(None);
        let cx = GetSystemMetrics(SM_CXSCREEN) / 2;
        let cy = GetSystemMetrics(SM_CYSCREEN) / 2;
        move_cursor_to(cx, cy);
    }
}

/// 把鼠标光标停放到「屏幕中央正下方、屏幕下边缘之外 1/10 屏高处」
///
/// 视角锁定前把光标停到屏幕外，可避免它停在界面元素上弹出 tooltip。
pub fn park_cursor_offscreen() {
    unsafe {
        let cx = GetSystemMetrics(SM_CXSCREEN) / 2;
        let cy = GetSystemMetrics(SM_CYSCREEN);
        move_cursor_to(cx, cy + cy / 10);
    }
}

/// 按「居中落点」开关把光标归位：true = 屏幕外下方，false = 屏幕正中央
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
