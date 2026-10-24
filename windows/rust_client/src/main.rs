//! SteamLike InputBridge（Rust 版 Windows 客户端）
//!
//! 运行在 Winlator 的 Windows 容器内，功能：
//! 1. 连接 Android 端 InputBridgeServer，接收手柄映射事件并注入魔兽；
//! 2. 可选拦截本地键鼠事件（总开关默认关闭），实现 WASD 自动锁定右键、
//!    交互键点击序列、长按交换左右键锁定、鼠标相对移动转发等。

mod bridge;
mod config;
mod hooks;
mod runtime;
mod ui;
mod win;

use std::sync::atomic::Ordering;

use eframe::egui;
use windows::core::{w, HRESULT};
use windows::Win32::Foundation::{BOOL, GetLastError, ERROR_ALREADY_EXISTS, HWND};
use windows::Win32::System::Threading::CreateMutexW;
use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONINFORMATION, MB_OK};

use runtime::{BRIDGE_TARGET, RT, WANT_CONNECT};

const MUTEX_NAME_W: windows::core::PCWSTR = w!("Global\\SteamLikeInputBridgeClient");

fn main() -> eframe::Result<()> {
    if !acquire_single_instance() {
        runtime::log("已有实例在运行，本次启动退出");
        unsafe {
            let _ = MessageBoxW(
                HWND(0),
                w!("InputBridge 已在运行，本次启动退出。"),
                w!("SteamLike InputBridge"),
                MB_OK | MB_ICONINFORMATION,
            );
        }
        return Ok(());
    }

    // 单实例确认后清空旧日志并初始化文件日志
    runtime::init_file_log();

    runtime::log(format!(
        "==== SteamLike InputBridge v{} 启动 ====",
        runtime::version()
    ));

    let cfg = config::load();
    runtime::log(format!(
        "游戏路径来源：{}",
        if cfg.wow_path_from_config_json {
            "config.json"
        } else {
            "界面选择"
        }
    ));
    apply_runtime(&cfg);

    hooks::start();
    if cfg.intercept_enabled {
        hooks::set_enabled(true);
    }
    bridge::start();

    if cfg.auto_launch {
        launch_game(&cfg);
    }

    let options = eframe::NativeOptions {
        initial_window_size: Some(egui::vec2(620.0, 640.0)),
        ..Default::default()
    };

    let result = eframe::run_native(
        &format!("SteamLike InputBridge v{}", runtime::version()),
        options,
        Box::new(|cc| {
            ui::setup_fonts(&cc.egui_ctx);
            Box::new(ui::App::new(cfg))
        }),
    );

    hooks::shutdown();
    win::release_all();
    result
}

/// 把配置写入运行期原子量
fn apply_runtime(cfg: &config::ClientConfig) {
    RT.intercept_enabled
        .store(cfg.intercept_enabled, Ordering::SeqCst);
    RT.right_button_fkeys
        .store(cfg.right_button_fkeys, Ordering::SeqCst);
    RT.interaction_vk
        .store(cfg.interaction_vk, Ordering::SeqCst);
    RT.swap_vk.store(cfg.swap_vk, Ordering::SeqCst);
    RT.long_press_ms
        .store(cfg.long_press_ms.max(50), Ordering::SeqCst);
    RT.cursor_park_offscreen
        .store(cfg.cursor_park_offscreen, Ordering::SeqCst);
    *BRIDGE_TARGET.lock().unwrap() = (cfg.server_ip.clone(), cfg.server_port);
    WANT_CONNECT.store(cfg.auto_connect, Ordering::SeqCst);
}

/// 按界面开关决定游戏路径并启动（不等待退出）
///
/// 供启动流程与界面「立即启动魔兽」按钮共用。
pub(crate) fn launch_game(cfg: &config::ClientConfig) {
    let Some(path) = config::resolve_wow_path(cfg) else {
        return;
    };
    let exe = std::path::PathBuf::from(&path);
    if !exe.exists() {
        runtime::log(format!("游戏 exe 不存在：{}", path));
        return;
    }
    let mut cmd = std::process::Command::new(&exe);
    if let Some(dir) = exe.parent() {
        cmd.current_dir(dir);
    }
    match cmd.spawn() {
        Ok(_) => runtime::log(format!("已启动游戏：{}", path)),
        Err(e) => runtime::log(format!("启动游戏失败 {}：{}", path, e)),
    }
}

/// 单实例限制：命名互斥锁，已有实例时返回 false
fn acquire_single_instance() -> bool {
    unsafe {
        let handle = CreateMutexW(None, BOOL(1), MUTEX_NAME_W);
        if handle.is_err() {
            return false;
        }
        // 句柄交由进程持有，直到进程结束（HANDLE 无 Drop，无需显式关闭）
        let already_exists = match GetLastError() {
            Ok(()) => false,
            Err(e) => e.code() == HRESULT::from_win32(ERROR_ALREADY_EXISTS.0),
        };
        !already_exists
    }
}
