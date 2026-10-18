//! 低级键鼠钩子（WH_KEYBOARD_LL / WH_MOUSE_LL）与游戏逻辑
//!
//! 结构：
//! - **钩子线程**：安装/卸载钩子并持续泵消息。低级钩子的回调只会在这个线程上被调用。
//! - **动作线程**：处理需要计时或较长的注入序列（长按交换键、交互键）。
//!   钩子回调只负责「吞掉事件 + 投递一条指令」，保持回调足够快（低级钩子有超时限制）。
//!
//! 事件是否被吞掉（return LRESULT(1)）的规则：
//! | 事件 | 处理 |
//! | --- | --- |
//! | 交互键 | 吞掉，交给动作线程执行「左键点击 + 右键点击」序列，不透传 |
//! | 长按交换键 | 吞掉，交给动作线程做长按判定；短按则补发一次普通点按 |
//! | WASD | 吞掉并由本程序注入，保证「光标归位 → F11 → WASD」的顺序 |
//! | 已锁定侧的鼠标按键按下 | 吞掉，作为解锁触发（连带吞掉对应的抬起） |
//! | 鼠标移动 | 吞掉原始事件，改注入等量的相对移动 |
//! | 其它 | 直接透传给魔兽 |

use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU32, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use crossbeam_channel::{unbounded, Receiver, RecvTimeoutError, Sender};
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::GetCurrentThreadId;
use windows::Win32::UI::WindowsAndMessaging::*;

use crate::runtime::{log, pack_pos, unpack_pos, RT};
use crate::win::{self, BTN_LEFT, BTN_RIGHT, MAGIC, VK_F11, VK_F12};

/* ===== 钩子线程消息 ===== */
const WM_APP_INSTALL: u32 = 0x8001; // WM_APP + 1
const WM_APP_UNINSTALL: u32 = 0x8002;
const WM_APP_QUIT: u32 = 0x8003;

/* ===== 动作线程指令 ===== */
#[derive(Debug, Clone, Copy)]
enum Cmd {
    /// 交互键按下
    Interact,
    /// 长按交换键按下
    SwapDown,
    /// 长按交换键抬起
    SwapUp,
    /// 长按阈值到达（内部定时产生）
    SwapTimeout,
    /// 仅用于记录日志：钩子回调内不能加锁，日志改由动作线程打印
    Note(&'static str),
}

static ACTION_TX: OnceLock<Sender<Cmd>> = OnceLock::new();
static KEY_HOOK: AtomicIsize = AtomicIsize::new(0);
static MOUSE_HOOK: AtomicIsize = AtomicIsize::new(0);
static HOOK_THREAD_ID: AtomicU32 = AtomicU32::new(0);
/// 是否正在等待「用于解锁的那次鼠标按下」对应的抬起，需要一并吞掉
static SWALLOW_LEFT_UP: AtomicBool = AtomicBool::new(false);
static SWALLOW_RIGHT_UP: AtomicBool = AtomicBool::new(false);

/* ===== 鼠标锁存（左右键长按） ===== */

/// 设置某个鼠标按钮的长按锁存状态（幂等），返回是否真的发生了变化
///
/// 右键默认发送 F11（锁定）/ F12（解锁），关闭「右键 F11/F12 模式」后
/// 改为发送真实鼠标右键按下/松开；左键始终发送真实鼠标左键按下/松开。
///
/// 注意：本函数会在钩子回调里被调用，因此**不能打印日志**（钩子回调必须保持无锁）。
pub fn set_lock(button: u8, locked: bool) -> bool {
    let flag = if button == BTN_RIGHT {
        &RT.right_locked
    } else if button == BTN_LEFT {
        &RT.left_locked
    } else {
        return false;
    };
    if flag.load(Ordering::Relaxed) == locked {
        return false;
    }
    if button == BTN_RIGHT && RT.right_button_fkeys.load(Ordering::Relaxed) {
        let vk = if locked { VK_F11 } else { VK_F12 };
        win::inject_key(vk, true);
        win::inject_key(vk, false);
    } else {
        win::inject_mouse_button(button, locked);
    }
    flag.store(locked, Ordering::Relaxed);
    true
}

/// WASD 按下时确保右键处于长按锁定（左键已锁定或右键已锁定则跳过）
fn ensure_right_locked_on_move() {
    if RT.left_locked.load(Ordering::Relaxed) {
        return;
    }
    if RT.right_locked.load(Ordering::Relaxed) {
        return;
    }
    // 先把光标归位（落点由「居中落点」开关决定：屏幕外下方 / 屏幕正中央），
    // 避免锁定瞬间视角从偏移位置开始漂移；顺序仍保持「先归位、后锁定」不变。
    win::position_cursor(RT.cursor_park_offscreen.load(Ordering::Relaxed));
    if set_lock(BTN_RIGHT, true) {
        queue_cmd(Cmd::Note("[锁存] WASD 触发：右键锁定"));
    }
}

/// 长按交换键触发：交换左右键的长按锁定状态
fn do_swap() {
    if RT.right_locked.load(Ordering::Relaxed) {
        set_lock(BTN_RIGHT, false);
        set_lock(BTN_LEFT, true);
        log("[交换] 右键长按 → 左键长按");
    } else if RT.left_locked.load(Ordering::Relaxed) {
        set_lock(BTN_LEFT, false);
        set_lock(BTN_RIGHT, true);
        log("[交换] 左键长按 → 右键长按");
    } else {
        log("[交换] 左右键均未长按，忽略");
    }
}

/// 交互键触发：临时释放锁存 → 光标居中 → 左键点击 → 光标居中 → 右键点击 →
/// 光标居中 → 恢复原有锁存状态
fn do_interact() {
    let left_locked = RT.left_locked.load(Ordering::Relaxed);
    let right_locked = RT.right_locked.load(Ordering::Relaxed);

    set_lock(BTN_LEFT, false);
    set_lock(BTN_RIGHT, false);

    win::center_cursor();
    win::inject_mouse_button(BTN_LEFT, true);
    win::inject_mouse_button(BTN_LEFT, false);

    win::center_cursor();
    win::inject_mouse_button(BTN_RIGHT, true);
    win::inject_mouse_button(BTN_RIGHT, false);

    win::center_cursor();
    set_lock(BTN_LEFT, left_locked);
    set_lock(BTN_RIGHT, right_locked);

    log(format!(
        "[交互] 完成（原锁存 左={} 右={}）",
        left_locked, right_locked
    ));
}

/// 释放本程序接管/锁存的所有输入（关闭拦截或退出时调用）
fn release_all_owned() {
    for i in 0..4usize {
        if win::WASD_TAKEN[i].swap(false, Ordering::Relaxed) {
            let vk = [win::VK_W, win::VK_A, win::VK_S, win::VK_D][i];
            win::inject_key(vk, false);
        }
    }
    set_lock(BTN_LEFT, false);
    set_lock(BTN_RIGHT, false);
    SWALLOW_LEFT_UP.store(false, Ordering::Relaxed);
    SWALLOW_RIGHT_UP.store(false, Ordering::Relaxed);
}

/* ===== 钩子回调 ===== */

unsafe extern "system" fn keyboard_proc(ncode: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if ncode < 0 {
        return CallNextHookEx(HHOOK(0), ncode, wparam, lparam);
    }

    let kb = &*(lparam.0 as *const KBDLLHOOKSTRUCT);
    // 自己注入的事件直接放行，避免递归拦截
    if kb.dwExtraInfo == MAGIC {
        return CallNextHookEx(HHOOK(0), ncode, wparam, lparam);
    }
    if !RT.intercept_enabled.load(Ordering::Relaxed) {
        return CallNextHookEx(HHOOK(0), ncode, wparam, lparam);
    }

    let down = match wparam.0 as u32 {
        WM_KEYDOWN | WM_SYSKEYDOWN => true,
        WM_KEYUP | WM_SYSKEYUP => false,
        _ => return CallNextHookEx(HHOOK(0), ncode, wparam, lparam),
    };
    let vk = kb.vkCode as u16;

    // 交互键：完全吞掉，不透传给魔兽
    if vk as u32 == RT.interaction_vk.load(Ordering::Relaxed) {
        if down {
            queue_cmd(Cmd::Interact);
        }
        return LRESULT(1);
    }

    // 长按交换键：吞掉，由动作线程判定短按/长按
    if vk as u32 == RT.swap_vk.load(Ordering::Relaxed) {
        queue_cmd(if down { Cmd::SwapDown } else { Cmd::SwapUp });
        return LRESULT(1);
    }

    // WASD：由本程序接管注入，保证「光标居中 → F11 → WASD」的顺序
    if let Some(slot) = win::wasd_index(vk) {
        if down {
            if win::WASD_TAKEN[slot].swap(true, Ordering::Relaxed) {
                // 系统自动重复，按键已处于按下状态，直接吞掉
                return LRESULT(1);
            }
            ensure_right_locked_on_move();
            win::inject_key(vk, true);
            return LRESULT(1);
        }
        if win::WASD_TAKEN[slot].swap(false, Ordering::Relaxed) {
            win::inject_key(vk, false);
            return LRESULT(1);
        }
    }

    CallNextHookEx(HHOOK(0), ncode, wparam, lparam)
}

unsafe extern "system" fn mouse_proc(ncode: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if ncode < 0 {
        return CallNextHookEx(HHOOK(0), ncode, wparam, lparam);
    }

    let ms = &*(lparam.0 as *const MSLLHOOKSTRUCT);
    let msg = wparam.0 as u32;

    if ms.dwExtraInfo == MAGIC {
        // 自己注入的事件：放行，同时同步光标基准位置
        if msg == WM_MOUSEMOVE {
            RT.last_mouse
                .store(pack_pos(ms.pt.x, ms.pt.y), Ordering::Relaxed);
        }
        return CallNextHookEx(HHOOK(0), ncode, wparam, lparam);
    }
    if !RT.intercept_enabled.load(Ordering::Relaxed) {
        return CallNextHookEx(HHOOK(0), ncode, wparam, lparam);
    }

    match msg {
        // 鼠标移动：吞掉原始事件，改注入等量的相对移动
        WM_MOUSEMOVE => {
            let prev = RT
                .last_mouse
                .swap(pack_pos(ms.pt.x, ms.pt.y), Ordering::Relaxed);
            let (px, py) = unpack_pos(prev);
            win::inject_mouse_move(ms.pt.x - px, ms.pt.y - py);
            LRESULT(1)
        }

        // 左键按下：若左键处于长按锁存，则本次点击作为解锁触发（不透传）
        WM_LBUTTONDOWN => {
            if RT.left_locked.load(Ordering::Relaxed) {
                set_lock(BTN_LEFT, false);
                SWALLOW_LEFT_UP.store(true, Ordering::Relaxed);
                queue_cmd(Cmd::Note("[锁存] 左键点击：解除左键锁定（本次点击不透传）"));
                LRESULT(1)
            } else {
                CallNextHookEx(HHOOK(0), ncode, wparam, lparam)
            }
        }
        WM_LBUTTONUP => {
            if SWALLOW_LEFT_UP.swap(false, Ordering::Relaxed) {
                LRESULT(1)
            } else {
                CallNextHookEx(HHOOK(0), ncode, wparam, lparam)
            }
        }

        // 右键按下：若右键处于长按锁存，则本次点击作为解锁触发（不透传）
        WM_RBUTTONDOWN => {
            if RT.right_locked.load(Ordering::Relaxed) {
                set_lock(BTN_RIGHT, false);
                SWALLOW_RIGHT_UP.store(true, Ordering::Relaxed);
                queue_cmd(Cmd::Note("[锁存] 右键点击：解除右键锁定（本次点击不透传）"));
                LRESULT(1)
            } else {
                CallNextHookEx(HHOOK(0), ncode, wparam, lparam)
            }
        }
        WM_RBUTTONUP => {
            if SWALLOW_RIGHT_UP.swap(false, Ordering::Relaxed) {
                LRESULT(1)
            } else {
                CallNextHookEx(HHOOK(0), ncode, wparam, lparam)
            }
        }

        // 其余鼠标事件（中键/侧键/滚轮）直接透传
        _ => CallNextHookEx(HHOOK(0), ncode, wparam, lparam),
    }
}

fn queue_cmd(cmd: Cmd) {
    if let Some(tx) = ACTION_TX.get() {
        let _ = tx.send(cmd);
    }
}

/* ===== 动作线程 ===== */

fn action_thread(rx: Receiver<Cmd>) {
    // 长按判定状态机
    let mut held_at: Option<Instant> = None;
    let mut fired = false;

    loop {
        let cmd = match held_at {
            None => match rx.recv() {
                Ok(c) => c,
                Err(_) => break,
            },
            Some(t0) if !fired => {
                let threshold = Duration::from_millis(RT.long_press_ms.load(Ordering::Relaxed) as u64);
                let deadline = t0 + threshold;
                match rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
                    Ok(c) => c,
                    Err(RecvTimeoutError::Timeout) => Cmd::SwapTimeout,
                    Err(RecvTimeoutError::Disconnected) => break,
                }
            }
            Some(_) => match rx.recv() {
                Ok(c) => c,
                Err(_) => break,
            },
        };

        match cmd {
            Cmd::SwapDown => {
                if held_at.is_none() {
                    held_at = Some(Instant::now());
                    fired = false;
                }
            }
            Cmd::SwapUp => {
                let was_held = held_at.take().is_some();
                if was_held && !fired {
                    // 未到阈值就抬起 → 当作普通点按补发给魔兽
                    let vk = RT.swap_vk.load(Ordering::Relaxed) as u16;
                    win::inject_key(vk, true);
                    win::inject_key(vk, false);
                }
                fired = false;
            }
            Cmd::SwapTimeout => {
                if held_at.is_some() && !fired {
                    fired = true;
                    do_swap();
                }
            }
            Cmd::Interact => do_interact(),
            Cmd::Note(msg) => log(msg),
        }
    }
}

/* ===== 钩子线程 ===== */

fn install_hooks() {
    unsafe {
        let hmod = match GetModuleHandleW(None) {
            Ok(m) => m,
            Err(e) => {
                log(format!("获取模块句柄失败: {}", e));
                return;
            }
        };
        match SetWindowsHookExW(WH_KEYBOARD_LL, Some(keyboard_proc), hmod, 0) {
            Ok(h) => KEY_HOOK.store(h.0, Ordering::SeqCst),
            Err(e) => log(format!("键盘钩子安装失败: {}", e)),
        }
        match SetWindowsHookExW(WH_MOUSE_LL, Some(mouse_proc), hmod, 0) {
            Ok(h) => MOUSE_HOOK.store(h.0, Ordering::SeqCst),
            Err(e) => log(format!("鼠标钩子安装失败: {}", e)),
        }
    }
    // 记录当前光标位置作为相对移动的基准
    let (x, y) = win::current_cursor();
    RT.last_mouse.store(pack_pos(x, y), Ordering::SeqCst);
    log("键鼠钩子已安装");
}

fn uninstall_hooks() {
    release_all_owned();
    unsafe {
        let k = KEY_HOOK.swap(0, Ordering::SeqCst);
        if k != 0 {
            let _ = UnhookWindowsHookEx(HHOOK(k));
        }
        let m = MOUSE_HOOK.swap(0, Ordering::SeqCst);
        if m != 0 {
            let _ = UnhookWindowsHookEx(HHOOK(m));
        }
    }
    log("键鼠钩子已卸载");
}

fn hook_thread_main() {
    // 先创建消息队列，保证 PostThreadMessageW 能投递
    unsafe {
        let mut msg = MSG::default();
        let _ = PeekMessageW(&mut msg, HWND(0), 0, 0, PM_NOREMOVE);
        HOOK_THREAD_ID.store(GetCurrentThreadId(), Ordering::SeqCst);
    }

    loop {
        let mut msg = MSG::default();
        let ret = unsafe { GetMessageW(&mut msg, HWND(0), 0, 0) }.0;
        if ret == -1 || ret == 0 {
            break;
        }
        match msg.message {
            WM_APP_INSTALL => install_hooks(),
            WM_APP_UNINSTALL => uninstall_hooks(),
            WM_APP_QUIT => {
                uninstall_hooks();
                break;
            }
            _ => {}
        }
    }

    HOOK_THREAD_ID.store(0, Ordering::SeqCst);
}

/// 启动动作线程与钩子线程（程序启动时调用一次）
pub fn start() {
    let (tx, rx) = unbounded::<Cmd>();
    let _ = ACTION_TX.set(tx);
    std::thread::Builder::new()
        .name("action".to_string())
        .spawn(move || action_thread(rx))
        .expect("动作线程创建失败");

    std::thread::Builder::new()
        .name("hook".to_string())
        .spawn(hook_thread_main)
        .expect("钩子线程创建失败");

    // 等待钩子线程把消息队列准备好
    for _ in 0..200 {
        if HOOK_THREAD_ID.load(Ordering::SeqCst) != 0 {
            return;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    log("钩子线程启动超时");
}

fn post(msg: u32) {
    let id = HOOK_THREAD_ID.load(Ordering::SeqCst);
    if id == 0 {
        return;
    }
    unsafe {
        let _ = PostThreadMessageW(id, msg, WPARAM(0), LPARAM(0));
    }
}

/// 开关键鼠拦截（由界面开关调用）
pub fn set_enabled(on: bool) {
    if on {
        post(WM_APP_INSTALL);
    } else {
        post(WM_APP_UNINSTALL);
    }
}

/// 程序退出时清理
pub fn shutdown() {
    post(WM_APP_QUIT);
}
