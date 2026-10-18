//! Windows 输入注入与常量
//!
//! 所有注入事件都会带上 [`MAGIC`] 标记写入 `dwExtraInfo`，
//! 钩子回调据此识别「本程序自己注入的事件」并直接放行，
//! 避免自己注入的事件被再次拦截（递归）。

use std::sync::atomic::{AtomicBool, Ordering};
use windows::Win32::Foundation::POINT;
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::WindowsAndMessaging::{
    GetCursorPos, GetSystemMetrics, SetCursorPos, SM_CXSCREEN, SM_CYSCREEN,
};

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

/// 把鼠标光标移动到主屏正中央
pub fn center_cursor() {
    unsafe {
        let cx = GetSystemMetrics(SM_CXSCREEN) / 2;
        let cy = GetSystemMetrics(SM_CYSCREEN) / 2;
        let _ = SetCursorPos(cx, cy);
    }
}

/// 把鼠标光标停放到「屏幕中央正下方、屏幕下边缘之外 1/10 屏高处」
///
/// 视角锁定前把光标停到屏幕外，可避免它停在界面元素上弹出 tooltip。
pub fn park_cursor_offscreen() {
    unsafe {
        let cx = GetSystemMetrics(SM_CXSCREEN) / 2;
        let cy = GetSystemMetrics(SM_CYSCREEN);
        let _ = SetCursorPos(cx, cy + cy / 10);
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
