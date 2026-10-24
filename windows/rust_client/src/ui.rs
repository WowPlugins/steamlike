//! eframe 界面

use std::sync::atomic::Ordering;
use std::time::Duration;

use eframe::egui;

use crate::config::{self, ClientConfig};
use crate::hooks;
use crate::runtime::{self, BRIDGE_TARGET, CONNECTED, RT, WANT_CONNECT};

/// 可选按键（已排除被 WASD 接管逻辑与 F11/F12 使用的按键）
const KEY_CHOICES: &[(u32, &str)] = &[
    (0x42, "B"),
    (0x43, "C"),
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
    (0x54, "T"),
    (0x55, "U"),
    (0x56, "V"),
    (0x58, "X"),
    (0x59, "Y"),
    (0x5A, "Z"),
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
    (0x20, "空格"),
    (0x09, "Tab"),
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
];

fn key_name(vk: u32) -> String {
    KEY_CHOICES
        .iter()
        .find(|(code, _)| *code == vk)
        .map(|(_, name)| name.to_string())
        .unwrap_or_else(|| format!("VK 0x{:02X}", vk))
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
}

impl App {
    pub fn new(cfg: ClientConfig) -> Self {
        Self {
            last_intercept: cfg.intercept_enabled,
            last_connect: cfg.auto_connect,
            title_version: crate::runtime::display_version(),
            mismatch_dismissed: false,
            mismatch_logged: false,
            cfg,
        }
    }

    fn section(ui: &mut egui::Ui, title: &str) {
        ui.add_space(4.0);
        ui.label(egui::RichText::new(title).strong());
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
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

        // 版本不一致：APK 下发的版本 ≠ 本 exe 编译版本 → 停用全部逻辑并弹模态提示
        let mismatch = runtime::apk_version_mismatch();
        if mismatch && !self.mismatch_logged {
            self.mismatch_logged = true;
            runtime::log(format!(
                "[版本] APK v{} 与本客户端 v{} 不一致，所有功能已停用",
                runtime::apk_version().unwrap_or_default(),
                runtime::build_version()
            ));
        }

        if mismatch && self.mismatch_dismissed && ctx.input(|i| i.pointer.any_click()) {
            // 用户确认后仍去操作界面：不执行任何功能，重新弹出模态提示
            self.mismatch_dismissed = false;
        }

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading(format!("SteamLike InputBridge v{}", version));
            ui.label("运行在 Winlator 内：接收手机手柄事件注入魔兽，可选拦截本地键鼠事件。");
            ui.separator();

            /* ===== 魔兽启动 ===== */
            Self::section(ui, "魔兽启动");
            ui.horizontal(|ui| {
                ui.label("EXE 路径：");
                if ui.button("浏览…").clicked() {
                    if let Some(p) = rfd::FileDialog::new()
                        .add_filter("可执行文件", &["exe"])
                        .pick_file()
                    {
                        self.cfg.wow_path = p.display().to_string();
                        dirty = true;
                    }
                }
            });
            let resp = ui.add(
                egui::TextEdit::singleline(&mut self.cfg.wow_path)
                    .desired_width(f32::INFINITY)
                    .hint_text("例如 D:\\Games\\WoW\\Wow.exe"),
            );
            if resp.changed() {
                dirty = true;
            }
            if ui
                .checkbox(
                    &mut self.cfg.wow_path_from_config_json,
                    "从 config.json 读取游戏路径（关闭则使用上方界面选择的路径）",
                )
                .changed()
            {
                dirty = true;
            }
            if ui
                .checkbox(&mut self.cfg.auto_launch, "启动本程序时自动启动魔兽")
                .changed()
            {
                dirty = true;
            }
            if ui.button("立即启动魔兽").clicked() {
                crate::launch_game(&self.cfg);
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
                    dirty = true;
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
                    dirty = true;
                }
            });
            if ui
                .checkbox(&mut self.cfg.auto_connect, "连接 APK 服务器")
                .changed()
            {
                dirty = true;
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
                dirty = true;
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
                    dirty = true;
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
                    dirty = true;
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
                    dirty = true;
                }
            });
            if ui
                .checkbox(
                    &mut self.cfg.right_button_fkeys,
                    "右键锁定使用 F11/F12（关闭则发送真实鼠标右键长按）",
                )
                .changed()
            {
                dirty = true;
            }
            if ui
                .checkbox(
                    &mut self.cfg.cursor_park_offscreen,
                    "WASD 归中时停放到屏幕左下角之外 2/3 处（关闭则为屏幕正中央）",
                )
                .changed()
            {
                dirty = true;
            }
            ui.label(
                egui::RichText::new(
                    "拦截开启后：WASD 自动锁定右键；交互键执行左右键点击且不透传；\
                     长按交换键切换左右键长按；已锁定一侧的点击用于解锁；鼠标移动改发相对移动。",
                )
                .weak(),
            );

            /* ===== 日志 ===== */
            ui.separator();
            Self::section(ui, "运行日志");
            let logs = runtime::logs_snapshot();
            egui::ScrollArea::vertical()
                .max_height(160.0)
                .stick_to_bottom(true)
                .show(ui, |ui| {
                    for line in &logs {
                        ui.label(line);
                    }
                });
        });

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
                        "Windows 客户端版本：v{}\n手机 APK 版本：v{}\n",
                        runtime::build_version(),
                        runtime::apk_version().unwrap_or_default()
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

        if dirty {
            config::save(&self.cfg);
        }

        // 状态与日志需要持续刷新
        ctx.request_repaint_after(Duration::from_millis(300));
    }
}
