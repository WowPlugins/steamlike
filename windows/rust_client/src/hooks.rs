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
//! | 交互键 | 吞掉，交给动作线程执行「解锁 → 居中 → 右键点击 → 恢复锁存」序列，不透传 |
//! | 长按交换键 | 吞掉，交给动作线程做长按判定；短按则补发一次普通点按 |
//! | 映射按键 | 吞掉，交给动作线程按短按/长按映射执行目标动作，不透传 |
//! | WASD | 吞掉并由本程序注入，保证「光标归位 → F11 → WASD」的顺序 |
//! | 有锁存时的鼠标按键按下 | 吞掉，作为解锁触发（连带吞掉对应的抬起），左右键均可解除 |
//! | 鼠标移动 | 直接透传给魔兽（不改写，避免光标偏移导致界面点击/hover 失效） |
//! | 其它 | 直接透传给魔兽 |

use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU32, Ordering};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use crossbeam_channel::{unbounded, Receiver, RecvTimeoutError, Sender};
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::GetCurrentThreadId;
use windows::Win32::UI::WindowsAndMessaging::*;

use crate::runtime::{log, pack_pos, RT};
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
    /// 解除鼠标长按锁定后的延时居中（钩子回调里不能阻塞，故交给动作线程延时执行）
    UnlockCenter,
    /// 仅用于记录日志：钩子回调内不能加锁，日志改由动作线程打印
    Note(&'static str),
    /// 映射按键按下（含短按和长按判定）
    MappingDown(u32),
    /// 映射按键抬起
    MappingUp(u32),
    /// 长按阈值到达（映射按键用）
    MappingLongPress(u32),
}

/// 「解锁 → 居中」之间插入的等待（毫秒）
///
/// 锁定时游戏处于 mouselook：WoW 会自己接管光标（隐藏指针、把光标钉在视口中心），
/// 收到解锁后需等它的帧循环处理完才真正放开光标。若解锁与居中间隔太近，
/// 居中会被游戏随后的一次光标接管覆盖（表现为「锁定状态下在游戏里不居中」）。
/// 因此解锁立即发出，居中延后本时长执行。
const UNLOCK_CENTER_DELAY_MS: u64 = 150;

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
    // 先把光标归位（落点由「居中落点」开关决定：屏幕左下角之外 2/3 处 / 屏幕正中央），
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

/// 交互键触发：
/// 1. 记录左右键锁存状态与光标位置
/// 2. 解除左右锁存 → 等游戏退出 mouselook → 光标居中 → 右键点击一次
/// 3. 光标移回原位置 → 恢复原有锁存状态
fn do_interact() {
    let left_locked = RT.left_locked.load(Ordering::Relaxed);
    let right_locked = RT.right_locked.load(Ordering::Relaxed);
    let (saved_x, saved_y) = win::current_cursor(); // 1. 记录光标原位置

    // 2. 先解锁（未锁定时 set_lock 自身为空操作）——立即发出，其余动作等游戏处理完 mouselook 退出
    set_lock(BTN_LEFT, false);
    set_lock(BTN_RIGHT, false);
    std::thread::sleep(Duration::from_millis(UNLOCK_CENTER_DELAY_MS)); // 等游戏退出 mouselook 再居中和点击

    win::center_cursor();

    // 右键点击一次（WoW 中与 NPC/物品交互的标准方式）
    win::inject_mouse_button(BTN_RIGHT, true);
    win::inject_mouse_button(BTN_RIGHT, false);

    // 3. 先把光标移回原位置，再恢复原有锁存状态
    win::move_cursor_to(saved_x, saved_y);
    set_lock(BTN_LEFT, left_locked);
    set_lock(BTN_RIGHT, right_locked);

    log(format!(
        "[交互] 完成（原锁存 左={} 右={}，光标还原到 {},{}）",
        left_locked, right_locked, saved_x, saved_y
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

    // 映射按键：吞掉，交给动作线程做短按/长按判定
    if RT.key_mapping_enabled.load(Ordering::Relaxed) {
        let is_mapped = {
            let mappings = RT.key_mappings.lock().unwrap();
            mappings.iter().any(|m| m.source_vk == vk as u32 && m.enabled)
        };
        if is_mapped {
            queue_cmd(if down {
                Cmd::MappingDown(vk as u32)
            } else {
                Cmd::MappingUp(vk as u32)
            });
            return LRESULT(1);
        }
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
        // 鼠标移动：直接透传给魔兽
        //
        // 这里**不要**再「吞掉原始移动 + 回注等量相对移动」。
        // Winlator 的触摸/鼠标事件进入 Wine 时，光标已经被移动到 ms.pt 指示的位置；
        // 若在钩子里吞掉它再注入一份相同位移的相对移动，等于让光标多走一遍，
        // 界面上的光标位置与实际手指位置不一致，表现为按钮不高亮、点击落空；
        // 而原始移动被吞掉后，魔兽也收不到它需要的原始输入（Raw Input）事件。
        WM_MOUSEMOVE => CallNextHookEx(HHOOK(0), ncode, wparam, lparam),

        // 鼠标左/右键按下：只要任一侧处于长按锁存，本次点击就作为解锁触发（不透传）
        //
        // 锁存是互斥的（WASD 自动锁右键，长按交换键只会把锁换到另一侧），
        // 因此点任意一侧都能解除当前锁定，避免锁在左键时点右键却把点击透传给魔兽。
        WM_LBUTTONDOWN | WM_RBUTTONDOWN => {
            let left_locked = RT.left_locked.load(Ordering::Relaxed);
            let right_locked = RT.right_locked.load(Ordering::Relaxed);
            if left_locked || right_locked {
                // 先解锁（立即发出，让游戏尽快退出 mouselook），居中交给动作线程延时执行
                set_lock(BTN_LEFT, false);
                set_lock(BTN_RIGHT, false);
                if msg == WM_LBUTTONDOWN {
                    SWALLOW_LEFT_UP.store(true, Ordering::Relaxed);
                    queue_cmd(Cmd::Note("[锁存] 左键点击：解除鼠标锁定（本次点击不透传）"));
                } else {
                    SWALLOW_RIGHT_UP.store(true, Ordering::Relaxed);
                    queue_cmd(Cmd::Note("[锁存] 右键点击：解除鼠标锁定（本次点击不透传）"));
                }
                queue_cmd(Cmd::UnlockCenter);
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
    // 长按判定状态机（长按交换键用）
    let mut held_at: Option<Instant> = None;
    let mut fired = false;

    // 映射按键长按判定状态机：vk → (按下时间, 是否已触发长按)
    let mut mapping_held: std::collections::HashMap<u32, (Instant, bool)> = std::collections::HashMap::new();

    loop {
        // 计算下一个超时事件：长按交换键 或 任意映射按键的长按阈值
        let mut next_timeout: Option<(Instant, Cmd)> = None;

        // 长按交换键超时
        if let Some(t0) = held_at {
            if !fired {
                let threshold = Duration::from_millis(RT.long_press_ms.load(Ordering::Relaxed) as u64);
                let deadline = t0 + threshold;
                if deadline > Instant::now() {
                    next_timeout = Some((deadline, Cmd::SwapTimeout));
                }
            }
        }

        // 映射按键长按超时
        for (&vk, &(t0, fired_flag)) in &mapping_held {
            if !fired_flag {
                let threshold = Duration::from_millis(RT.long_press_ms.load(Ordering::Relaxed) as u64);
                let deadline = t0 + threshold;
                if deadline > Instant::now() {
                    if next_timeout.map_or(true, |(t, _)| deadline < t) {
                        next_timeout = Some((deadline, Cmd::MappingLongPress(vk)));
                    }
                }
            }
        }

        let cmd = match next_timeout {
            None => match rx.recv() {
                Ok(c) => c,
                Err(_) => break,
            },
            Some((deadline, timeout_cmd)) => {
                match rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
                    Ok(c) => c,
                    Err(RecvTimeoutError::Timeout) => timeout_cmd,
                    Err(RecvTimeoutError::Disconnected) => break,
                }
            }
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
            Cmd::UnlockCenter => {
                // 解锁已由钩子回调立即发出，这里等游戏处理完退出 mouselook 再把光标放到屏幕正中央
                std::thread::sleep(Duration::from_millis(UNLOCK_CENTER_DELAY_MS));
                win::center_cursor();
            }
            Cmd::Note(msg) => log(msg),
            Cmd::MappingDown(vk) => {
                mapping_held.insert(vk, (Instant::now(), false));
            }
            Cmd::MappingUp(vk) => {
                if let Some((_, fired_flag)) = mapping_held.remove(&vk) {
                    if !fired_flag {
                        // 未到阈值就抬起 → 执行短按映射
                        execute_mapping(vk, false);
                    }
                }
            }
            Cmd::MappingLongPress(vk) => {
                if let Some((_, fired_flag)) = mapping_held.get_mut(&vk) {
                    if !*fired_flag {
                        *fired_flag = true;
                        execute_mapping(vk, true);
                    }
                }
            }
        }
    }
}

/// 执行映射动作
///
/// vk 是源按键虚拟键码，is_long_press 表示是否为长按触发。
/// 从运行时映射列表中查找该按键的映射，若启用则执行对应动作。
fn execute_mapping(source_vk: u32, is_long_press: bool) {
    // 总开关关闭时不执行任何映射
    if !RT.key_mapping_enabled.load(Ordering::Relaxed) {
        return;
    }

    let mappings = RT.key_mappings.lock().unwrap();
    let mapping = match mappings.iter().find(|m| m.source_vk == source_vk && m.enabled) {
        Some(m) => m.clone(),
        None => return,
    };
    drop(mappings);

    let action = if is_long_press {
        &mapping.long_press_action
    } else {
        &mapping.tap_action
    };

    match action {
        crate::config::MappedAction::None => {}
        crate::config::MappedAction::KeyboardKey { key_code, sub_commands } => {
            execute_keyboard_key(*key_code as u16, sub_commands);
        }
        crate::config::MappedAction::NumpadKey { key_code, sub_commands } => {
            execute_keyboard_key(*key_code as u16, sub_commands);
        }
        crate::config::MappedAction::MouseClick { button, sub_commands } => {
            execute_mouse_click(*button, sub_commands);
        }
        crate::config::MappedAction::MouseToggle { button, sub_commands } => {
            execute_mouse_toggle(*button, sub_commands);
        }
        crate::config::MappedAction::ScrollUp => {
            win::inject_mouse_wheel(120);
            log(format!("[映射] {} → 滚轮上滚", crate::ui::key_name(source_vk)));
        }
        crate::config::MappedAction::ScrollDown => {
            win::inject_mouse_wheel(-120);
            log(format!("[映射] {} → 滚轮下滚", crate::ui::key_name(source_vk)));
        }
        crate::config::MappedAction::MouseSwapToggle => {
            do_swap();
        }
        crate::config::MappedAction::MouseReleaseAll => {
            set_lock(BTN_LEFT, false);
            set_lock(BTN_RIGHT, false);
            log("[映射] 释放所有鼠标长按锁存");
        }
        crate::config::MappedAction::Interact => {
            do_interact();
        }
        crate::config::MappedAction::OverlayToggle => {
            let expanded = RT.overlay_expanded.load(Ordering::Relaxed);
            RT.overlay_expanded.store(!expanded, Ordering::Relaxed);
            log(format!(
                "[映射] 悬浮窗{}",
                if expanded { "收起" } else { "展开" }
            ));
        }
    }
}

/// 执行键盘按键动作（含子命令组合键）
///
/// 顺序：先按下主键 → 依次按下子命令 → 逆序抬起子命令 → 抬起主键。
/// 与 APK 的 KeyboardMouseMapper 保持一致：需要组合键时把修饰键设为主键、
/// 字符键设为子命令（如主键 Ctrl + 子命令 C）。
fn execute_keyboard_key(key_code: u16, sub_commands: &[u32]) {
    win::inject_key(key_code, true);
    for &sub in sub_commands {
        win::inject_key(sub as u16, true);
    }
    for &sub in sub_commands.iter().rev() {
        win::inject_key(sub as u16, false);
    }
    win::inject_key(key_code, false);
    let key_name = crate::ui::key_name(key_code as u32);
    if sub_commands.is_empty() {
        log(format!("[映射] 键盘 {}", key_name));
    } else {
        let subs: Vec<String> = sub_commands.iter().map(|&c| crate::ui::key_name(c)).collect();
        log(format!("[映射] 键盘 {} + {}", key_name, subs.join(" + ")));
    }
}

/// 执行鼠标点击动作（含子命令组合键）
fn execute_mouse_click(button: u8, sub_commands: &[u32]) {
    win::inject_mouse_button(button, true);
    for &sub in sub_commands {
        win::inject_key(sub as u16, true);
    }
    for &sub in sub_commands.iter().rev() {
        win::inject_key(sub as u16, false);
    }
    win::inject_mouse_button(button, false);
    let btn_name = crate::ui::mouse_button_name(button);
    log(format!("[映射] 鼠标点击 {}", btn_name));
}

/// 执行鼠标长按/锁存动作（含子命令组合键）
fn execute_mouse_toggle(button: u8, sub_commands: &[u32]) {
    // 先执行子命令（如果有），再锁存鼠标按钮
    for &sub in sub_commands {
        win::inject_key(sub as u16, true);
        win::inject_key(sub as u16, false);
    }
    set_lock(button, true);
    let btn_name = crate::ui::mouse_button_name(button);
    log(format!("[映射] 鼠标长按 {}", btn_name));
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
