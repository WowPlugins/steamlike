//! eframe 界面

use std::sync::atomic::Ordering;
use std::time::Duration;

use eframe::egui;

use crate::config::{self, ClientConfig};
use crate::hooks;
use crate::runtime::{self, BRIDGE_TARGET, CONNECTED, RT, WANT_CONNECT};

/// 可选按键（完整键位表：字母、数字、符号、控制键、编辑键、修饰键、功能键）
const KEY_CHOICES: &[(u32, &str)] = &[
    /* 字母 */
    (0x41, "A"),
    (0x42, "B"),
    (0x43, "C"),
    (0x44, "D"),
    (0x45, "E"),
    (0x46, "F"),
    (0x47, "G"),
    (0x48, "H"),
    (0x49, "I"),
    (0x4A, "J"),
    (0x4B, "K"),
    (0x4C, "L"),
    (0x4D, "M"),
    (0x4E, "N"),
    (0x4F, "O"),
    (0x50, "P"),
    (0x51, "Q"),
    (0x52, "R"),
    (0x53, "S"),
    (0x54, "T"),
    (0x55, "U"),
    (0x56, "V"),
    (0x57, "W"),
    (0x58, "X"),
    (0x59, "Y"),
    (0x5A, "Z"),
    /* 数字与符号（不按 Shift 的字符） */
    (0x30, "0"),
    (0x31, "1"),
    (0x32, "2"),
    (0x33, "3"),
    (0x34, "4"),
    (0x35, "5"),
    (0x36, "6"),
    (0x37, "7"),
    (0x38, "8"),
    (0x39, "9"),
    (0xBA, ";"),
    (0xBB, "="),
    (0xBC, ","),
    (0xBD, "-"),
    (0xBE, "."),
    (0xBF, "/"),
    (0xC0, "`"),
    (0xDB, "["),
    (0xDC, "\\"),
    (0xDD, "]"),
    (0xDE, "'"),
    /* 控制键 */
    (0x08, "Backspace"),
    (0x09, "Tab"),
    (0x0D, "Enter"),
    (0x1B, "Esc"),
    (0x20, "空格"),
    /* 编辑与导航 */
    (0x21, "PageUp"),
    (0x22, "PageDown"),
    (0x23, "End"),
    (0x24, "Home"),
    (0x25, "左方向"),
    (0x26, "上方向"),
    (0x27, "右方向"),
    (0x28, "下方向"),
    (0x2D, "Insert"),
    (0x2E, "Delete"),
    /* 修饰与锁定 */
    (0x10, "Shift"),
    (0x11, "Ctrl"),
    (0x12, "Alt"),
    (0x5B, "Win"),
    (0x14, "CapsLock"),
    (0x90, "NumLock"),
    (0x91, "ScrollLock"),
    /* 功能键 */
    (0x70, "F1"),
    (0x71, "F2"),
    (0x72, "F3"),
    (0x73, "F4"),
    (0x74, "F5"),
    (0x75, "F6"),
    (0x76, "F7"),
    (0x77, "F8"),
    (0x78, "F9"),
    (0x79, "F10"),
    (0x7A, "F11"),
    (0x7B, "F12"),
];

/// Shift 组合下的符号（同一物理键按下 Shift 后产生的字符）
///
/// 选中这些符号时界面会自动把 Shift 加进子命令，注入时即 shift+VK。
const SHIFT_SYMBOLS: &[(u32, &str)] = &[
    (0x30, ")"),
    (0x31, "!"),
    (0x32, "@"),
    (0x33, "#"),
    (0x34, "$"),
    (0x35, "%"),
    (0x36, "^"),
    (0x37, "&"),
    (0x38, "*"),
    (0x39, "("),
    (0xBA, ":"),
    (0xBB, "+"),
    (0xBC, "<"),
    (0xBD, "_"),
    (0xBE, ">"),
    (0xBF, "?"),
    (0xC0, "~"),
    (0xDB, "{"),
    (0xDC, "|"),
    (0xDD, "}"),
    (0xDE, "\""),
];

/// 数字小键盘按键
const NUMPAD_CHOICES: &[(u32, &str)] = &[
    (0x60, "Num 0"),
    (0x61, "Num 1"),
    (0x62, "Num 2"),
    (0x63, "Num 3"),
    (0x64, "Num 4"),
    (0x65, "Num 5"),
    (0x66, "Num 6"),
    (0x67, "Num 7"),
    (0x68, "Num 8"),
    (0x69, "Num 9"),
    (0x6A, "Num *"),
    (0x6B, "Num +"),
    (0x6D, "Num -"),
    (0x6E, "Num ."),
    (0x6F, "Num /"),
    (0x0D, "Num Enter"),
];

/// Shift 符号名（用于映射描述：Shift+1 直接显示为 !）
pub fn shift_symbol_name(vk: u32) -> Option<&'static str> {
    SHIFT_SYMBOLS
        .iter()
        .find(|(code, _)| *code == vk)
        .map(|(_, name)| *name)
}

pub fn key_name(vk: u32) -> String {
    KEY_CHOICES
        .iter()
        .find(|(code, _)| *code == vk)
        .map(|(_, name)| name.to_string())
        .unwrap_or_else(|| format!("VK 0x{:02X}", vk))
}

/// 数字小键盘按键名（供映射描述使用）
pub fn numpad_key_name(vk: u32) -> String {
    match vk {
        0x60 => "Num 0".to_string(),
        0x61 => "Num 1".to_string(),
        0x62 => "Num 2".to_string(),
        0x63 => "Num 3".to_string(),
        0x64 => "Num 4".to_string(),
        0x65 => "Num 5".to_string(),
        0x66 => "Num 6".to_string(),
        0x67 => "Num 7".to_string(),
        0x68 => "Num 8".to_string(),
        0x69 => "Num 9".to_string(),
        0x6A => "Num *".to_string(),
        0x6B => "Num +".to_string(),
        0x6D => "Num -".to_string(),
        0x6E => "Num .".to_string(),
        0x6F => "Num /".to_string(),
        _ => format!("Num VK 0x{:02X}", vk),
    }
}

/// 鼠标按键名（供映射描述使用）
pub fn mouse_button_name(button: u8) -> &'static str {
    match button {
        0 => "左键",
        1 => "右键",
        2 => "中键",
        3 => "前进键",
        4 => "后退键",
        _ => "未知",
    }
}

/// 加载中文字体（微软雅黑），避免中文显示为方块
pub fn setup_fonts(ctx: &egui::Context) {
    const CANDIDATES: [&str; 4] = [
        "C:/Windows/Fonts/msyh.ttc", // 微软雅黑
        "C:/Windows/Fonts/msyh.ttf",
        "C:/Windows/Fonts/simhei.ttf", // 黑体
        "C:/Windows/Fonts/simsun.ttc", // 宋体
    ];

    let Some(path) = CANDIDATES.iter().find(|p| std::path::Path::new(*p).exists()) else {
        return;
    };
    let Ok(bytes) = std::fs::read(path) else {
        return;
    };

    let mut fonts = egui::FontDefinitions::default();
    fonts
        .font_data
        .insert("cjk".to_owned(), egui::FontData::from_owned(bytes));

    // 追加到字体族末尾：西文仍走默认字体，仅缺失字形回退到中文字体
    for family in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
        fonts
            .families
            .get_mut(&family)
            .unwrap()
            .push("cjk".to_owned());
    }

    ctx.set_fonts(fonts);
}

pub struct App {
    cfg: ClientConfig,
    last_intercept: bool,
    last_connect: bool,
    title_version: String,
    /// 版本不一致弹窗是否已被用户确认关闭（关闭后一旦操作界面会再次弹出）
    mismatch_dismissed: bool,
    /// 版本不一致是否已写入日志（避免每帧重复记录）
    mismatch_logged: bool,
    /// 游戏 exe 路径错误提示（路径不存在/未设置时显示红字）
    launch_error: Option<String>,
    /// 当前选中的 Tab 页索引
    selected_tab: usize,
    /// 映射编辑对话框：当前正在编辑的映射索引（None 表示未打开）
    editing_mapping: Option<usize>,
    /// 映射编辑对话框：短按动作类型选择
    edit_tap_type: usize,
    /// 映射编辑对话框：长按动作类型选择
    edit_long_press_type: usize,
    /// 映射编辑对话框：短按动作值（键盘按键/鼠标按键等）
    edit_tap_value: u32,
    /// 映射编辑对话框：长按动作值
    edit_long_press_value: u32,
    /// 映射编辑对话框：短按子命令列表
    edit_tap_subs: Vec<u32>,
    /// 映射编辑对话框：长按子命令列表
    edit_long_press_subs: Vec<u32>,
    /// 映射编辑对话框：源按键
    edit_source_vk: u32,
    /// 映射编辑对话框：是否启用
    edit_enabled: bool,
    /// 是否处于完整编辑面板状态（false = 小面板/圆点）
    full_editor: bool,
    /// 上次应用的窗口尺寸（用于检测面板状态切换时调整窗口大小）
    last_window_size: (f32, f32),
    /// 上次裁剪圆角时的窗口实际尺寸（尺寸变化后需要重新设置窗口区域）
    last_region_size: (i32, i32),
    /// 上次强制置顶的时间（魔兽窗口化全屏会盖住悬浮窗，需周期性重新置顶）
    last_topmost_at: std::time::Instant,
}

impl App {
    pub fn new(cfg: ClientConfig) -> Self {
        // 启动时就检查一次路径，路径无效则直接显示提示
        let launch_error = if cfg.auto_launch || !cfg.wow_path.trim().is_empty() {
            config::check_wow_path(&cfg).err()
        } else {
            None
        };

        // 把配置中的映射同步到运行时
        *RT.key_mappings.lock().unwrap() = cfg.key_mappings.clone();
        RT.key_mapping_enabled.store(cfg.key_mapping_enabled, Ordering::SeqCst);
        RT.overlay_expanded.store(cfg.overlay_expanded, Ordering::SeqCst);

        // 启动时总是展开（不沿用上次收起为圆点的状态）
        let mut cfg = cfg;
        cfg.overlay_expanded = true;

        Self {
            last_intercept: cfg.intercept_enabled,
            last_connect: cfg.auto_connect,
            title_version: crate::runtime::display_version(),
            mismatch_dismissed: false,
            mismatch_logged: false,
            launch_error,
            cfg,
            selected_tab: 0,
            editing_mapping: None,
            edit_tap_type: 0,
            edit_long_press_type: 0,
            edit_tap_value: 0,
            edit_long_press_value: 0,
            edit_tap_subs: Vec::new(),
            edit_long_press_subs: Vec::new(),
            edit_source_vk: 0,
            edit_enabled: true,
            // 启动即显示完整主界面
            full_editor: true,
            last_window_size: (0.0, 0.0),
            last_region_size: (0, 0),
            last_topmost_at: std::time::Instant::now(),
        }
    }

    fn section(ui: &mut egui::Ui, title: &str) {
        ui.add_space(4.0);
        ui.label(egui::RichText::new(title).strong());
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        let mut dirty = false;

        // 连接后 APK 会下发版本号：与标题栏不一致时同步更新窗口标题
        let version = crate::runtime::display_version();
        if version != self.title_version {
            let old_title = format!("SteamLike InputBridge v{}", self.title_version);
            let new_title = format!("SteamLike InputBridge v{}", version);
            if crate::win::rename_own_window(&old_title, &new_title) {
                self.title_version = version.clone();
            }
        }

        // 版本不一致：APK 期望的客户端版本 ≠ 本 exe 编译版本 → 停用全部逻辑并弹模态提示
        let mismatch = runtime::apk_version_mismatch();
        if mismatch && !self.mismatch_logged {
            self.mismatch_logged = true;
            runtime::log(format!(
                "[版本] APK 期望 v{} 与本客户端 v{} 不一致，所有功能已停用",
                runtime::expected_rust_version().unwrap_or_default(),
                runtime::build_version()
            ));
        }

        if mismatch && self.mismatch_dismissed && ctx.input(|i| i.pointer.any_click()) {
            // 用户确认后仍去操作界面：不执行任何功能，重新弹出模态提示
            self.mismatch_dismissed = false;
        }

        // 映射配置变更时同步到运行时
        if self.cfg.key_mappings != *RT.key_mappings.lock().unwrap() {
            *RT.key_mappings.lock().unwrap() = self.cfg.key_mappings.clone();
        }
        RT.key_mapping_enabled.store(self.cfg.key_mapping_enabled, Ordering::SeqCst);

        // 悬浮窗展开状态以运行期为准：映射动作在动作线程里翻转它，
        // 这里回写到配置（若反过来每帧用配置覆盖运行期，映射触发就永远看不到效果）
        let rt_expanded = RT.overlay_expanded.load(Ordering::Relaxed);
        if rt_expanded != self.cfg.overlay_expanded {
            self.cfg.overlay_expanded = rt_expanded;
            dirty = true;
        }

        // 面板状态 → 目标窗口尺寸（状态切换时调整窗口大小）
        let target = if !self.cfg.overlay_expanded {
            egui::vec2(64.0, 64.0) // 圆点
        } else if self.full_editor || !self.cfg.overlay_compact_enabled {
            egui::vec2(620.0, 660.0) // 完整编辑面板（小面板不参与形态时直接展开为主界面）
        } else {
            egui::vec2(280.0, 250.0) // 小面板
        };
        if self.last_window_size != (target.x, target.y) {
            self.last_window_size = (target.x, target.y);
            // 用 Win32 直接调整窗口尺寸：eframe 的请求在 Winlator/Wine 下不一定生效
            let scale = ctx.pixels_per_point();
            crate::win::resize_own_window(
                &format!("SteamLike InputBridge v{}", self.title_version),
                (target.x * scale) as i32,
                (target.y * scale) as i32,
            );
        }

        // 圆角裁剪：把窗口区域设成圆角矩形，圆角外完全不绘制（Wine 下不依赖桌面透明合成）
        let title = format!("SteamLike InputBridge v{}", self.title_version);
        let size = crate::win::own_window_size(&title);
        if size != self.last_region_size {
            self.last_region_size = size;
            crate::win::round_own_window(&title, 12);
        }

        // 魔兽以窗口化全屏启动后会盖住悬浮窗：每秒用 Win32 强制置顶一次，
        // 保证小圆点始终显示在游戏画面之上（eframe 的 always_on_top 在 Wine 下不可靠）
        if self.last_topmost_at.elapsed() >= Duration::from_secs(1) {
            self.last_topmost_at = std::time::Instant::now();
            crate::win::set_topmost(&title);
        }

        if !self.cfg.overlay_expanded {
            self.render_collapsed(ctx, frame, &mut dirty);
        } else if self.full_editor || !self.cfg.overlay_compact_enabled {
            self.render_full(ctx, frame, &mut dirty, &version);
        } else {
            self.render_compact(ctx, frame, &mut dirty);
        }

        // 版本不一致：整窗透明遮罩拦住所有点击，主界面上的功能都点不动
        if mismatch {
            egui::Area::new(egui::Id::new("mismatch_blocker"))
                .order(egui::Order::Middle)
                .fixed_pos(egui::Pos2::ZERO)
                .show(ctx, |ui| {
                    ui.allocate_response(ctx.screen_rect().size(), egui::Sense::click());
                });
        }

        // 模态提示：版本不一致时展示；用户点「我知道了」暂时关闭，再次操作界面会重新弹出
        if mismatch && !self.mismatch_dismissed {
            egui::Window::new("⚠ 版本不一致")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
                .show(ctx, |ui| {
                    ui.label(format!(
                        "Windows 客户端版本：v{}\nAPK 期望的客户端版本：v{}\n",
                        runtime::build_version(),
                        runtime::expected_rust_version().unwrap_or_default()
                    ));
                    ui.label(
                        "版本不一致，客户端全部功能已停用（键鼠拦截、注入、连接均不工作）。\n\
                         请从 APK「导出」重新获取 inputbridge_client.exe，替换到 Winlator 后重启本程序。",
                    );
                    ui.add_space(6.0);
                    if ui.button("我知道了").clicked() {
                        self.mismatch_dismissed = true;
                    }
                });
        }

        // 界面参数即时同步到运行期状态
        RT.intercept_enabled
            .store(self.cfg.intercept_enabled && !mismatch, Ordering::SeqCst);
        RT.right_button_fkeys
            .store(self.cfg.right_button_fkeys, Ordering::SeqCst);
        RT.interaction_vk
            .store(self.cfg.interaction_vk, Ordering::SeqCst);
        RT.swap_vk.store(self.cfg.swap_vk, Ordering::SeqCst);
        RT.long_press_ms
            .store(self.cfg.long_press_ms.max(50), Ordering::SeqCst);
        RT.cursor_park_offscreen
            .store(self.cfg.cursor_park_offscreen, Ordering::SeqCst);
        RT.clipboard_sync
            .store(self.cfg.clipboard_sync, Ordering::SeqCst);
        *BRIDGE_TARGET.lock().unwrap() = (self.cfg.server_ip.clone(), self.cfg.server_port);
        WANT_CONNECT.store(self.cfg.auto_connect && !mismatch, Ordering::SeqCst);

        // 版本不一致时强制关闭键鼠拦截（配置本身不改，版本恢复一致后自动生效）
        let intercept_now = self.cfg.intercept_enabled && !mismatch;
        if intercept_now != self.last_intercept {
            self.last_intercept = intercept_now;
            hooks::set_enabled(intercept_now);
            runtime::log(if intercept_now {
                "[拦截] 已启用键鼠拦截"
            } else {
                "[拦截] 已关闭键鼠拦截"
            });
        }

        if self.cfg.auto_connect != self.last_connect {
            self.last_connect = self.cfg.auto_connect;
            runtime::log(if self.last_connect {
                "[网络] 连接开关已打开"
            } else {
                "[网络] 连接开关已关闭"
            });
        }

        // 映射编辑对话框（模态）
        self.render_mapping_editor(ctx, &mut dirty);

        // 所有界面交互（含映射编辑对话框的保存）都完成后再落盘
        if dirty {
            config::save(&self.cfg);
        }

        // 状态与日志需要持续刷新
        ctx.request_repaint_after(Duration::from_millis(300));
    }

    /// 窗口背景用全透明，配合窗口圆角区域裁剪，圆角外不出现黑色边角
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] {
        egui::Color32::TRANSPARENT.to_normalized_gamma_f32()
    }
}

impl App {
    /// 基本设置 Tab 页（魔兽启动、APK 服务器、键鼠拦截、剪贴板同步）
    fn tab_basic_settings(&mut self, ui: &mut egui::Ui, dirty: &mut bool) {

            /* ===== 魔兽启动 ===== */
            Self::section(ui, "魔兽启动");
            ui.horizontal(|ui| {
                ui.label("EXE 路径：");
                if ui.button("浏览…").clicked() {
                    if let Some(p) = rfd::FileDialog::new()
                        .add_filter("可执行文件", &["exe"])
                        .pick_file()
                    {
                        let picked = p.display().to_string();
                        self.cfg.wow_path = picked.clone();
                        config::remember_path(&mut self.cfg, &picked);
                        self.launch_error = None;
                        *dirty = true;
                    }
                }
                // 历史路径下拉：快速切回以前用过的 exe，选中即校验路径是否有效
                ui.label("历史：");
                let selected_text = if self.cfg.wow_path.trim().is_empty() {
                    "（无）".to_string()
                } else {
                    std::path::Path::new(&self.cfg.wow_path)
                        .file_name()
                        .map(|n| n.to_string_lossy().to_string())
                        .unwrap_or_else(|| self.cfg.wow_path.clone())
                };
                let history = self.cfg.wow_path_history.clone();
                egui::ComboBox::from_id_source("wow_path_history")
                    .selected_text(selected_text)
                    .width(200.0)
                    .show_ui(ui, |ui| {
                        if history.is_empty() {
                            ui.label("暂无历史路径");
                        }
                        for path in history {
                            if ui.selectable_label(self.cfg.wow_path == path, &path).clicked() {
                                self.cfg.wow_path = path.clone();
                                if std::path::Path::new(&path).is_file() {
                                    self.launch_error = None;
                                } else {
                                    self.launch_error =
                                        Some(format!("exe 路径不存在：{}", path));
                                    runtime::log(format!("[启动] 历史路径不可用：{}", path));
                                }
                                *dirty = true;
                            }
                        }
                    });
            });
            let resp = ui.add(
                egui::TextEdit::singleline(&mut self.cfg.wow_path)
                    .desired_width(f32::INFINITY)
                    .hint_text("例如 D:\\Games\\WoW\\Wow.exe"),
            );
            if resp.changed() {
                *dirty = true;
                // 手动输入成有效路径后清掉旧提示
                if std::path::Path::new(self.cfg.wow_path.trim()).is_file() {
                    self.launch_error = None;
                }
            }
            if ui
                .checkbox(
                    &mut self.cfg.wow_path_from_config_json,
                    "从 config.json 读取游戏路径（关闭则使用上方界面选择的路径）",
                )
                .changed()
            {
                *dirty = true;
            }
            if ui
                .checkbox(&mut self.cfg.auto_launch, "启动本程序时自动启动魔兽")
                .changed()
            {
                *dirty = true;
            }
            ui.horizontal(|ui| {
                ui.label("自动启动延时：");
                if ui
                    .add(
                        egui::DragValue::new(&mut self.cfg.auto_launch_delay_ms)
                            .clamp_range(0..=30000)
                            .speed(50.0)
                            .suffix(" ms"),
                    )
                    .changed()
                {
                    *dirty = true;
                }
                ui.add_space(12.0);
                if ui
                    .checkbox(
                        &mut self.cfg.skip_launch_on_input,
                        "延时内有键鼠输入则本次不自动启动",
                    )
                    .changed()
                {
                    *dirty = true;
                }
            });
            if ui.button("立即启动魔兽").clicked() {
                match config::check_wow_path(&self.cfg) {
                    Ok(path) => {
                        self.launch_error = None;
                        config::remember_path(&mut self.cfg, &path);
                        // 启动后自动收起到小圆点，避免大面板挡住游戏画面
                        self.cfg.overlay_expanded = false;
                        self.full_editor = false;
                        RT.overlay_expanded.store(false, Ordering::Relaxed);
                        *dirty = true;
                        crate::launch_game(&self.cfg);
                    }
                    Err(e) => {
                        self.launch_error = Some(e.clone());
                        runtime::log(format!("[启动] {}", e));
                    }
                }
            }
            if let Some(err) = &self.launch_error {
                // 路径无效时红色提示，避免用户点了没反应
                ui.label(
                    egui::RichText::new(err)
                        .color(egui::Color32::from_rgb(0xD3, 0x2F, 0x2F)),
                );
            }

            /* ===== APK 服务器 ===== */
            ui.separator();
            Self::section(ui, "APK 服务器");
            ui.horizontal(|ui| {
                ui.label("IP：");
                if ui
                    .add(
                        egui::TextEdit::singleline(&mut self.cfg.server_ip).desired_width(140.0),
                    )
                    .changed()
                {
                    *dirty = true;
                }
                ui.label("端口：");
                if ui
                    .add(
                        egui::DragValue::new(&mut self.cfg.server_port)
                            .clamp_range(1..=65535)
                            .speed(1.0),
                    )
                    .changed()
                {
                    *dirty = true;
                }
            });
            if ui
                .checkbox(&mut self.cfg.auto_connect, "连接 APK 服务器")
                .changed()
            {
                *dirty = true;
            }
            let status = runtime::status_snapshot();
            let color = if CONNECTED.load(Ordering::Relaxed) {
                egui::Color32::from_rgb(0x1B, 0x8A, 0x2E)
            } else {
                egui::Color32::from_rgb(0x9E, 0x5B, 0x00)
            };
            ui.label(egui::RichText::new(format!("状态：{}", status)).color(color));

            /* ===== 键鼠拦截 ===== */
            ui.separator();
            Self::section(ui, "键鼠拦截（全局钩子）");
            if ui
                .checkbox(&mut self.cfg.intercept_enabled, "启用键鼠拦截")
                .changed()
            {
                *dirty = true;
            }
            ui.horizontal(|ui| {
                ui.label("交互键：");
                let before = self.cfg.interaction_vk;
                egui::ComboBox::from_id_source("interaction_key")
                    .selected_text(key_name(self.cfg.interaction_vk))
                    .show_ui(ui, |ui| {
                        for (vk, name) in KEY_CHOICES {
                            ui.selectable_value(&mut self.cfg.interaction_vk, *vk, *name);
                        }
                    });
                if self.cfg.interaction_vk != before {
                    *dirty = true;
                }

                ui.add_space(12.0);
                ui.label("长按交换键：");
                let before = self.cfg.swap_vk;
                egui::ComboBox::from_id_source("swap_key")
                    .selected_text(key_name(self.cfg.swap_vk))
                    .show_ui(ui, |ui| {
                        for (vk, name) in KEY_CHOICES {
                            ui.selectable_value(&mut self.cfg.swap_vk, *vk, *name);
                        }
                    });
                if self.cfg.swap_vk != before {
                    *dirty = true;
                }

                ui.add_space(12.0);
                ui.label("长按阈值：");
                if ui
                    .add(
                        egui::DragValue::new(&mut self.cfg.long_press_ms)
                            .clamp_range(50..=5000)
                            .speed(10.0)
                            .suffix(" ms"),
                    )
                    .changed()
                {
                    *dirty = true;
                }
            });
            if ui
                .checkbox(
                    &mut self.cfg.right_button_fkeys,
                    "右键锁定使用 F11/F12（关闭则发送真实鼠标右键长按）",
                )
                .changed()
            {
                *dirty = true;
            }
            if ui
                .checkbox(
                    &mut self.cfg.cursor_park_offscreen,
                    "WASD 归中时停放到屏幕左下角之外 2/3 处（关闭则为屏幕正中央）",
                )
                .changed()
            {
                *dirty = true;
            }
            ui.label(
                egui::RichText::new(
                    "拦截开启后：WASD 自动锁定右键；交互键执行左右键点击且不透传；\
                     长按交换键切换左右键长按；已锁定一侧的点击用于解锁；鼠标移动改发相对移动。",
                )
                .weak(),
            );

            /* ===== 剪贴板同步 ===== */
            ui.separator();
            Self::section(ui, "剪贴板同步");
            if ui
                .checkbox(
                    &mut self.cfg.clipboard_sync,
                    "启用剪贴板同步（始终记录到本地文件，连接 APK 时同时发送到手机）",
                )
                .changed()
            {
                *dirty = true;
            }
            ui.label(
                egui::RichText::new(format!(
                    "剪贴板内容变化时追加写入 exe 目录的 clipboard_log.txt（每条带时间戳）；\
                     已连接 APK 时另把文本发送到手机剪贴板。记录文件：{}",
                    config::exe_dir().join("clipboard_log.txt").display()
                ))
                .weak(),
            );

            /* ===== 悬浮窗形态 ===== */
            ui.separator();
            Self::section(ui, "悬浮窗形态");
            if ui
                .checkbox(
                    &mut self.cfg.overlay_compact_enabled,
                    "小面板参与形态（关闭时只在「完整主界面」和「小圆点」之间切换）",
                )
                .changed()
            {
                // 关闭小面板后当前若停留在小面板，直接落回完整主界面，避免出现不存在的形态
                if !self.cfg.overlay_compact_enabled {
                    self.full_editor = true;
                }
                *dirty = true;
            }
            ui.label(
                egui::RichText::new(
                    "关闭（默认）：小圆点 ⇄ 完整主界面；打开：小圆点 ⇄ 小面板 ⇄ 完整主界面。",
                )
                .weak(),
            );
    }

    /// 按键映射 Tab 页
    fn tab_key_mappings(&mut self, ui: &mut egui::Ui, dirty: &mut bool) {
        Self::section(ui, "按键映射");
        ui.label("把某个按键映射为另一个按键/组合键或功能。拦截开启时生效。");
        ui.add_space(4.0);

        // 总开关
        if ui
            .checkbox(&mut self.cfg.key_mapping_enabled, "启用按键映射（总开关）")
            .changed()
        {
            *dirty = true;
        }
        ui.add_space(8.0);

        // 映射列表
        let mappings = &mut self.cfg.key_mappings;
        let mut to_remove: Option<usize> = None;
        let mut to_edit: Option<usize> = None;
        for (i, mapping) in mappings.iter_mut().enumerate() {
            egui::Frame::group(ui.style()).show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    // 启用开关
                    if ui.checkbox(&mut mapping.enabled, "").changed() {
                        *dirty = true;
                    }
                    ui.label(
                        egui::RichText::new(format!("按键 {}", key_name(mapping.source_vk)))
                            .strong(),
                    );
                    // 编辑/删除放在右侧
                    ui.with_layout(
                        egui::Layout::right_to_left(egui::Align::Center),
                        |ui| {
                            if ui.button("删除").clicked() {
                                to_remove = Some(i);
                            }
                            if ui.button("编辑").clicked() {
                                to_edit = Some(i);
                            }
                        },
                    );
                });
                // 点击与长按分别一行，避免分不清哪个动作属于点击、哪个属于长按
                ui.horizontal(|ui| {
                    ui.label("点击 →");
                    Self::render_action_summary(ui, &mapping.tap_action);
                });
                ui.horizontal(|ui| {
                    ui.label("长按 →");
                    Self::render_action_summary(ui, &mapping.long_press_action);
                });
            });
            ui.add_space(4.0);
        }
        if let Some(i) = to_remove {
            mappings.remove(i);
            *dirty = true;
        }
        if let Some(i) = to_edit {
            self.open_mapping_editor(i);
        }

        // 添加按钮
        if ui.button("+ 添加映射").clicked() {
            self.cfg.key_mappings.push(crate::config::KeyMapping::default());
            *dirty = true;
            self.open_mapping_editor(self.cfg.key_mappings.len() - 1);
        }
    }

    /// 映射列表里动作的简短展示（未设置时弱化显示）
    fn render_action_summary(ui: &mut egui::Ui, action: &crate::config::MappedAction) {
        if matches!(action, crate::config::MappedAction::None) {
            ui.weak("未设置");
        } else {
            ui.label(action.describe());
        }
    }

    /// 日志 Tab 页
    fn tab_logs(&mut self, ui: &mut egui::Ui) {
        Self::section(ui, "运行日志");
        let logs = runtime::logs_snapshot();
        egui::ScrollArea::vertical()
            .max_height(300.0)
            .stick_to_bottom(true)
            .show(ui, |ui| {
                for line in &logs {
                    ui.label(line);
                }
            });
    }

    /// 打开映射编辑对话框
    fn open_mapping_editor(&mut self, index: usize) {
        let mapping = &self.cfg.key_mappings[index];
        self.editing_mapping = Some(index);
        self.edit_source_vk = mapping.source_vk;
        self.edit_enabled = mapping.enabled;

        // 解析短按动作
        match &mapping.tap_action {
            crate::config::MappedAction::KeyboardKey { key_code, sub_commands } => {
                self.edit_tap_type = 1;
                self.edit_tap_value = *key_code;
                self.edit_tap_subs = sub_commands.clone();
            }
            crate::config::MappedAction::NumpadKey { key_code, sub_commands } => {
                self.edit_tap_type = 2;
                self.edit_tap_value = *key_code;
                self.edit_tap_subs = sub_commands.clone();
            }
            crate::config::MappedAction::MouseClick { button, sub_commands } => {
                self.edit_tap_type = 3;
                self.edit_tap_value = *button as u32;
                self.edit_tap_subs = sub_commands.clone();
            }
            crate::config::MappedAction::MouseToggle { button, sub_commands } => {
                self.edit_tap_type = 4;
                self.edit_tap_value = *button as u32;
                self.edit_tap_subs = sub_commands.clone();
            }
            crate::config::MappedAction::ScrollUp => {
                self.edit_tap_type = 5;
                self.edit_tap_value = 0;
                self.edit_tap_subs.clear();
            }
            crate::config::MappedAction::ScrollDown => {
                self.edit_tap_type = 6;
                self.edit_tap_value = 0;
                self.edit_tap_subs.clear();
            }
            crate::config::MappedAction::MouseSwapToggle => {
                self.edit_tap_type = 7;
                self.edit_tap_value = 0;
                self.edit_tap_subs.clear();
            }
            crate::config::MappedAction::MouseReleaseAll => {
                self.edit_tap_type = 8;
                self.edit_tap_value = 0;
                self.edit_tap_subs.clear();
            }
            crate::config::MappedAction::Interact => {
                self.edit_tap_type = 9;
                self.edit_tap_value = 0;
                self.edit_tap_subs.clear();
            }
            crate::config::MappedAction::OverlayToggle => {
                self.edit_tap_type = 10;
                self.edit_tap_value = 0;
                self.edit_tap_subs.clear();
            }
            _ => {
                self.edit_tap_type = 0;
                self.edit_tap_value = 0;
                self.edit_tap_subs.clear();
            }
        }

        // 解析长按动作
        match &mapping.long_press_action {
            crate::config::MappedAction::KeyboardKey { key_code, sub_commands } => {
                self.edit_long_press_type = 1;
                self.edit_long_press_value = *key_code;
                self.edit_long_press_subs = sub_commands.clone();
            }
            crate::config::MappedAction::NumpadKey { key_code, sub_commands } => {
                self.edit_long_press_type = 2;
                self.edit_long_press_value = *key_code;
                self.edit_long_press_subs = sub_commands.clone();
            }
            crate::config::MappedAction::MouseClick { button, sub_commands } => {
                self.edit_long_press_type = 3;
                self.edit_long_press_value = *button as u32;
                self.edit_long_press_subs = sub_commands.clone();
            }
            crate::config::MappedAction::MouseToggle { button, sub_commands } => {
                self.edit_long_press_type = 4;
                self.edit_long_press_value = *button as u32;
                self.edit_long_press_subs = sub_commands.clone();
            }
            crate::config::MappedAction::ScrollUp => {
                self.edit_long_press_type = 5;
                self.edit_long_press_value = 0;
                self.edit_long_press_subs.clear();
            }
            crate::config::MappedAction::ScrollDown => {
                self.edit_long_press_type = 6;
                self.edit_long_press_value = 0;
                self.edit_long_press_subs.clear();
            }
            crate::config::MappedAction::MouseSwapToggle => {
                self.edit_long_press_type = 7;
                self.edit_long_press_value = 0;
                self.edit_long_press_subs.clear();
            }
            crate::config::MappedAction::MouseReleaseAll => {
                self.edit_long_press_type = 8;
                self.edit_long_press_value = 0;
                self.edit_long_press_subs.clear();
            }
            crate::config::MappedAction::Interact => {
                self.edit_long_press_type = 9;
                self.edit_long_press_value = 0;
                self.edit_long_press_subs.clear();
            }
            crate::config::MappedAction::OverlayToggle => {
                self.edit_long_press_type = 10;
                self.edit_long_press_value = 0;
                self.edit_long_press_subs.clear();
            }
            _ => {
                self.edit_long_press_type = 0;
                self.edit_long_press_value = 0;
                self.edit_long_press_subs.clear();
            }
        }
    }

    /// 保存映射编辑对话框
    fn save_mapping_editor(&mut self) {
        if let Some(index) = self.editing_mapping {
            // 先构建动作（只读借用编辑字段），再拿映射的可变借用写入，避免与 &self 冲突
            let tap = self.build_action(self.edit_tap_type, self.edit_tap_value, &self.edit_tap_subs);
            let lp = self.build_action(self.edit_long_press_type, self.edit_long_press_value, &self.edit_long_press_subs);
            let mapping = &mut self.cfg.key_mappings[index];
            mapping.source_vk = self.edit_source_vk;
            mapping.enabled = self.edit_enabled;
            mapping.tap_action = tap;
            mapping.long_press_action = lp;

            self.editing_mapping = None;
        }
    }

    /// 根据类型和值构建 MappedAction
    fn build_action(&self, action_type: usize, value: u32, subs: &[u32]) -> crate::config::MappedAction {
        match action_type {
            1 => crate::config::MappedAction::KeyboardKey {
                key_code: value,
                sub_commands: subs.to_vec(),
            },
            2 => crate::config::MappedAction::NumpadKey {
                key_code: value,
                sub_commands: subs.to_vec(),
            },
            3 => crate::config::MappedAction::MouseClick {
                button: value as u8,
                sub_commands: subs.to_vec(),
            },
            4 => crate::config::MappedAction::MouseToggle {
                button: value as u8,
                sub_commands: subs.to_vec(),
            },
            5 => crate::config::MappedAction::ScrollUp,
            6 => crate::config::MappedAction::ScrollDown,
            7 => crate::config::MappedAction::MouseSwapToggle,
            8 => crate::config::MappedAction::MouseReleaseAll,
            9 => crate::config::MappedAction::Interact,
            10 => crate::config::MappedAction::OverlayToggle,
            _ => crate::config::MappedAction::None,
        }
    }

    /// 渲染映射编辑对话框（egui Window 实现模态）
    fn render_mapping_editor(&mut self, ctx: &egui::Context, dirty: &mut bool) {
        let Some(_index) = self.editing_mapping else { return };

        let action_types = [
            "未设置", "键盘按键", "数字小键盘", "鼠标点击", "鼠标长按",
            "滚轮上滚", "滚轮下滚", "鼠标长按交换", "释放鼠标长按",
            "交互键", "悬浮窗展开/收起",
        ];
        // 长按与短按支持完全相同的动作类型
        let long_press_types = action_types;

        let mut open = true;
        egui::Window::new(format!("编辑映射: {}", key_name(self.edit_source_vk)))
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
            .open(&mut open)
            .show(ctx, |ui| {
                // 源按键
                ui.horizontal(|ui| {
                    ui.label("源按键:");
                    egui::ComboBox::from_id_source("map_source_vk")
                        .selected_text(key_name(self.edit_source_vk))
                        .show_ui(ui, |ui| {
                            for (vk, name) in KEY_CHOICES {
                                ui.selectable_value(&mut self.edit_source_vk, *vk, *name);
                            }
                        });
                });

                ui.checkbox(&mut self.edit_enabled, "启用此映射");

                ui.separator();

                /* ===== 短按动作 ===== */
                ui.label(egui::RichText::new("点击（短按）动作").strong());
                ui.horizontal(|ui| {
                    ui.label("动作类型:");
                    egui::ComboBox::from_id_source("map_tap_type")
                        .selected_text(action_types[self.edit_tap_type])
                        .show_ui(ui, |ui| {
                            for (i, name) in action_types.iter().enumerate() {
                                ui.selectable_value(&mut self.edit_tap_type, i, *name);
                            }
                        });
                });

                // 短按动作值
                Self::render_action_value_editor(ui, self.edit_tap_type, &mut self.edit_tap_value, &mut self.edit_tap_subs, "tap");

                ui.separator();

                /* ===== 长按动作 ===== */
                ui.label(egui::RichText::new("长按动作（可选）").strong());
                ui.horizontal(|ui| {
                    ui.label("动作类型:");
                    egui::ComboBox::from_id_source("map_lp_type")
                        .selected_text(long_press_types[self.edit_long_press_type])
                        .show_ui(ui, |ui| {
                            for (i, name) in long_press_types.iter().enumerate() {
                                ui.selectable_value(&mut self.edit_long_press_type, i, *name);
                            }
                        });
                });

                // 长按动作值
                Self::render_action_value_editor(ui, self.edit_long_press_type, &mut self.edit_long_press_value, &mut self.edit_long_press_subs, "lp");

                ui.separator();

                // 按钮
                ui.horizontal(|ui| {
                    if ui.button("保存").clicked() {
                        self.save_mapping_editor();
                        *dirty = true;
                    }
                    if ui.button("取消").clicked() {
                        self.editing_mapping = None;
                    }
                });
            });

        if !open {
            self.editing_mapping = None;
        }
    }

    /// 键盘按键选择器：普通键列表 + Shift 符号列表
    ///
    /// 符号以「主键 Shift + 子命令 该物理键」的形式保存，这样注入顺序恰好是
    /// Shift 先按、符号键后按（与「主键先按」的注入顺序一致）。
    fn render_key_picker(ui: &mut egui::Ui, value: &mut u32, subs: &mut Vec<u32>, id_prefix: &str) {
        // 主键 Shift + 唯一子命令 → 视为 Shift 符号组合
        let symbol_vk = if *value == 0x10 && subs.len() == 1 {
            Some(subs[0])
        } else {
            None
        };
        let selected_text = match symbol_vk.and_then(shift_symbol_name) {
            Some(sym) => format!("Shift+{}", sym),
            None => key_name(*value),
        };
        egui::ComboBox::from_id_source(format!("{}_value", id_prefix))
            .selected_text(selected_text)
            .show_ui(ui, |ui| {
                for (vk, name) in KEY_CHOICES {
                    let selected = symbol_vk.is_none() && *value == *vk;
                    if ui.selectable_label(selected, *name).clicked() {
                        *value = *vk;
                        subs.clear();
                    }
                }
                ui.separator();
                ui.label(egui::RichText::new("Shift 符号").weak());
                for (vk, name) in SHIFT_SYMBOLS {
                    let selected = symbol_vk == Some(*vk);
                    if ui
                        .selectable_label(selected, format!("Shift+{}", name))
                        .clicked()
                    {
                        *value = 0x10;
                        subs.clear();
                        subs.push(*vk);
                    }
                }
            });
    }

    /// 渲染动作值编辑器（根据动作类型显示不同的选择器）
    fn render_action_value_editor(
        ui: &mut egui::Ui,
        action_type: usize,
        value: &mut u32,
        subs: &mut Vec<u32>,
        id_prefix: &str,
    ) {
        match action_type {
            1 => {
                // 键盘按键：普通键 + Shift 符号（选符号自动附加 Shift 子命令）
                ui.horizontal(|ui| {
                    ui.label("按键:");
                    Self::render_key_picker(ui, value, subs, id_prefix);
                });
                Self::render_sub_commands_editor(ui, subs, id_prefix);
            }
            2 => {
                // 数字小键盘
                ui.horizontal(|ui| {
                    ui.label("按键:");
                    egui::ComboBox::from_id_source(format!("{}_value", id_prefix))
                        .selected_text(numpad_key_name(*value))
                        .show_ui(ui, |ui| {
                            for (vk, name) in NUMPAD_CHOICES {
                                ui.selectable_value(value, *vk, *name);
                            }
                        });
                });
                Self::render_sub_commands_editor(ui, subs, id_prefix);
            }
            3 | 4 => {
                // 鼠标点击 / 鼠标长按
                ui.horizontal(|ui| {
                    ui.label("鼠标按键:");
                    egui::ComboBox::from_id_source(format!("{}_value", id_prefix))
                        .selected_text(mouse_button_name(*value as u8))
                        .show_ui(ui, |ui| {
                            ui.selectable_value(value, 0, "左键");
                            ui.selectable_value(value, 1, "右键");
                            ui.selectable_value(value, 2, "中键");
                            ui.selectable_value(value, 3, "前进键");
                            ui.selectable_value(value, 4, "后退键");
                        });
                });
                Self::render_sub_commands_editor(ui, subs, id_prefix);
            }
            _ => {
                // 无参数动作
                ui.label(egui::RichText::new("（此动作无参数）").weak());
            }
        }
    }

    /// 渲染子命令编辑器
    fn render_sub_commands_editor(ui: &mut egui::Ui, subs: &mut Vec<u32>, id_prefix: &str) {
        ui.label("子命令（组合键）:");
        let mut to_remove: Option<usize> = None;
        for (i, sub) in subs.iter_mut().enumerate() {
            ui.horizontal(|ui| {
                ui.label(format!("子 {}:", i + 1));
                egui::ComboBox::from_id_source(format!("{}_sub_{}", id_prefix, i))
                    .selected_text(key_name(*sub))
                    .show_ui(ui, |ui| {
                        for (vk, name) in KEY_CHOICES {
                            ui.selectable_value(sub, *vk, *name);
                        }
                    });
                if ui.button("删除").on_hover_text("删除这条子命令").clicked() {
                    to_remove = Some(i);
                }
            });
        }
        if let Some(i) = to_remove {
            subs.remove(i);
        }
        if subs.len() < 3 {
            if ui.button("+ 添加子命令").clicked() {
                subs.push(0x43); // 默认 C
            }
        }
    }

    /// 圆点状态：连接状态颜色 + 映射数量，点击展开为小面板，可拖动
    fn render_collapsed(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame, dirty: &mut bool) {
        egui::CentralPanel::default()
            .frame(egui::Frame::none())
            .show(ctx, |ui| {
                let size = ui.available_size();
                let (rect, response) =
                    ui.allocate_exact_size(size, egui::Sense::click_and_drag());
                let center = rect.center();
                let radius = size.min_elem() / 2.0 - 4.0;
                let color = if CONNECTED.load(Ordering::Relaxed) {
                    egui::Color32::from_rgb(0x1B, 0x8A, 0x2E) // 绿色：已连接
                } else {
                    egui::Color32::from_rgb(0x9E, 0x5B, 0x00) // 橙色：未连接
                };
                ui.painter().circle_filled(center, radius, color);
                ui.painter().circle_stroke(
                    center,
                    radius,
                    egui::Stroke::new(2.0_f32, egui::Color32::WHITE),
                );
                let count = RT.key_mappings.lock().unwrap().len();
                ui.painter().text(
                    center,
                    egui::Align2::CENTER_CENTER,
                    format!("{}", count),
                    egui::FontId::proportional(16.0),
                    egui::Color32::WHITE,
                );
                if response.clicked() {
                    self.cfg.overlay_expanded = true;
                    RT.overlay_expanded.store(true, Ordering::Relaxed);
                    *dirty = true;
                }
                if response.drag_started() {
                    frame.drag_window();
                }
                if response.hovered() {
                    ui.output_mut(|o| o.cursor_icon = egui::CursorIcon::PointingHand);
                }
            });
    }

    /// 小面板：常用开关 + 操作按钮
    fn render_compact(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame, dirty: &mut bool) {
        let mut panel = egui::Frame::window(&ctx.style());
        panel.rounding = egui::Rounding::same(12.0_f32);
        // 透明窗口下阴影会画成圆角外的黑色晕圈，必须去掉
        panel.shadow = egui::epaint::Shadow::NONE;
        egui::CentralPanel::default().frame(panel).show(ctx, |ui| {
            // 标题栏：可拖动，右侧「收起为圆点」「退出」
            ui.horizontal(|ui| {
                let title = ui.add(
                    egui::Label::new(egui::RichText::new("InputBridge").strong())
                        .sense(egui::Sense::drag()),
                );
                if title.drag_started() {
                    frame.drag_window();
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("×").on_hover_text("退出").clicked() {
                        frame.close();
                    }
                    if ui.button("─").on_hover_text("收起为圆点").clicked() {
                        self.cfg.overlay_expanded = false;
                        RT.overlay_expanded.store(false, Ordering::Relaxed);
                        *dirty = true;
                    }
                });
            });
            ui.separator();

            // 常用开关
            if ui
                .checkbox(&mut self.cfg.intercept_enabled, "键鼠拦截")
                .changed()
            {
                *dirty = true;
            }
            if ui
                .checkbox(&mut self.cfg.key_mapping_enabled, "按键映射")
                .changed()
            {
                *dirty = true;
            }

            ui.separator();

            // 操作按钮
            ui.horizontal(|ui| {
                if ui.button("编辑映射").clicked() {
                    self.selected_tab = 1;
                    self.full_editor = true;
                }
                if ui.button("设置").clicked() {
                    self.selected_tab = 0;
                    self.full_editor = true;
                }
            });
            let count = RT.key_mappings.lock().unwrap().len();
            ui.label(
                egui::RichText::new(format!("映射: {} 条", count)).weak(),
            );
        });
    }

    /// 完整编辑面板：Tab 页（基本设置 / 按键映射 / 日志）
    fn render_full(
        &mut self,
        ctx: &egui::Context,
        frame: &mut eframe::Frame,
        dirty: &mut bool,
        version: &str,
    ) {
        let mut panel = egui::Frame::window(&ctx.style());
        panel.rounding = egui::Rounding::same(12.0_f32);
        panel.shadow = egui::epaint::Shadow::NONE;
        egui::CentralPanel::default()
            .frame(panel)
            .show(ctx, |ui| {
                // 标题栏：返回小面板 + 可拖动标题 + 收起为圆点
                ui.horizontal(|ui| {
                    // 小面板不参与形态时没有「上一级」，直接由右侧「─」收起到圆点
                    if self.cfg.overlay_compact_enabled && ui.button("◀ 小面板").clicked() {
                        self.full_editor = false;
                    }
                    let title = ui.add(
                        egui::Label::new(
                            egui::RichText::new(format!("InputBridge v{}", version)).strong(),
                        )
                        .sense(egui::Sense::drag()),
                    );
                    if title.drag_started() {
                        frame.drag_window();
                    }
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.button("─").on_hover_text("收起为圆点").clicked() {
                            self.cfg.overlay_expanded = false;
                            RT.overlay_expanded.store(false, Ordering::Relaxed);
                            *dirty = true;
                        }
                    });
                });
                ui.separator();

                // Tab 栏
                ui.horizontal(|ui| {
                    ui.selectable_value(&mut self.selected_tab, 0, "基本设置");
                    ui.selectable_value(&mut self.selected_tab, 1, "按键映射");
                    ui.selectable_value(&mut self.selected_tab, 2, "日志");
                });
                ui.separator();

                match self.selected_tab {
                    0 => self.tab_basic_settings(ui, dirty),
                    1 => self.tab_key_mappings(ui, dirty),
                    2 => self.tab_logs(ui),
                    _ => {}
                }
            });
    }
}
