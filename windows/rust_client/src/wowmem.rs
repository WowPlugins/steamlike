//! 读取游戏进程内的角色状态（由 `SLState` 插件维护）
//!
//! 插件在 Lua 5.1 的 table array part 里维护一块连续 TValue 数组：
//! 每槽 16 字节（8 字节 double + 4 字节类型标签 + 4 字节对齐填充），
//! 前三个槽是固定的 anchor 浮点。本模块先全内存扫描这三个浮点定位数组首地址，
//! 之后每 100ms 只读 128 字节，用「双 seq + 互补值 + 校验和」判断帧是否有效。
//!
//! anchor 是数值而不是字符串，所以表内容变化时地址不会移动，
//! 只有句柄失效或内存被重新分配时才需要重扫。

use std::mem::size_of;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use windows::Win32::Foundation::{CloseHandle, HANDLE};
use windows::Win32::System::Diagnostics::Debug::ReadProcessMemory;
use windows::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W, TH32CS_SNAPPROCESS,
};
use windows::Win32::System::Memory::{
    VirtualQueryEx, MEMORY_BASIC_INFORMATION, MEM_COMMIT, MEM_PRIVATE, PAGE_GUARD, PAGE_NOACCESS,
};
use windows::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_INFORMATION, PROCESS_VM_READ};

use crate::runtime::log;

/// Lua 5.1 顺序整数槽的字节数（double 8 + 类型标签 4 + 对齐 4）
const SLOT: usize = 16;
/// 插件预分配的槽数
const NSLOT: usize = 8;
/// 一帧的字节数
const FRAME: usize = SLOT * NSLOT;
/// anchor 浮点（对应插件里 1..3 号槽）
const ANCHORS: [f64; 3] = [3494.38, 1982.44, 9918.71];
/// 是否额外校验 TValue 类型标签（标准 Lua 5.1 的 number 为 3）
///
/// 默认关闭：若客户端换过 Lua 实现，标签值可能不同，开着会导致永远定位不到。
/// 三重 anchor 连续匹配 + 双 seq + 互补值 + 校验和，已经足够排除误命中。
const CHECK_TAG: bool = false;

/// 状态未知（未找到进程 / 插件未启用 / 扫描失败）
const UNKNOWN: u8 = 0;
/// 非潜行
const NO_STEALTH: u8 = 1;
/// 潜行中
const STEALTH: u8 = 2;

/// 当前角色潜行状态（原子量，钩子线程直接读取）
static STATE: AtomicU8 = AtomicU8::new(UNKNOWN);
/// 内存扫描总开关（界面可改，扫描线程每轮读取）
static ENABLED: AtomicBool = AtomicBool::new(false);
/// 扫描线程是否已创建（只创建一次，关闭开关时线程空转等待）
static STARTED: AtomicBool = AtomicBool::new(false);
/// 目标进程的可执行文件名（配置里的 exe 文件名，小写）
static TARGET: Mutex<Option<String>> = Mutex::new(None);
/// 「插件已是最新」这条日志只在首次调用时记一次，避免界面反复切换开关时刷屏
static ADDON_LOGGED: AtomicBool = AtomicBool::new(false);

/// 读取当前潜行状态：`None` 表示状态不可用（未知）
pub fn stealth() -> Option<bool> {
    match STATE.load(Ordering::Relaxed) {
        NO_STEALTH => Some(false),
        STEALTH => Some(true),
        _ => None,
    }
}

/// 状态文本（供界面显示）
pub fn state_text() -> &'static str {
    match STATE.load(Ordering::Relaxed) {
        NO_STEALTH => "非潜行",
        STEALTH => "潜行中",
        _ => "未知",
    }
}

/// 插件安装目录与是否已写入文件（供界面显示安装状态）
///
/// 返回 None 表示还没配置游戏路径。
pub fn addon_dir_text(wow_path: &str) -> Option<(String, bool)> {
    let path = wow_path.trim();
    if path.is_empty() {
        return None;
    }
    let dir = std::path::Path::new(path).parent()?;
    let addon = dir.join("Interface").join("AddOns").join("SLState");
    let installed = addon.join("SLState.toc").is_file() && addon.join("SLState.lua").is_file();
    Some((addon.display().to_string(), installed))
}

/// 手动导出插件：强制重写文件（界面上的「导出插件」按钮用）
///
/// 与 `ensure_addon` 的区别是不比较内容，用户点一次就必然覆盖。
pub fn export_addon(wow_path: &str) -> Result<String, String> {
    let path = wow_path.trim();
    if path.is_empty() {
        return Err("请先选择魔兽 exe 路径".to_string());
    }
    let dir = std::path::Path::new(path)
        .parent()
        .ok_or_else(|| format!("无法从路径解析游戏目录：{}", path))?;
    let addon_dir = dir.join("Interface").join("AddOns").join("SLState");
    std::fs::create_dir_all(&addon_dir)
        .map_err(|e| format!("创建目录失败（{}）：{}", addon_dir.display(), e))?;
    let toc = include_str!("../assets/SLState/SLState.toc");
    let lua = include_str!("../assets/SLState/SLState.lua");
    for (name, content) in [("SLState.toc", toc), ("SLState.lua", lua)] {
        let file = addon_dir.join(name);
        std::fs::write(&file, content)
            .map_err(|e| format!("写入 {} 失败：{}", file.display(), e))?;
    }
    log(format!(
        "[状态] 已手动导出 SLState 插件：{}",
        addon_dir.display()
    ));
    Ok(addon_dir.display().to_string())
}

/// 把插件文件写入 `{游戏目录}/Interface/AddOns/SLState`
///
/// 内容与目标文件相同则跳过，避免每次启动都重写；首次安装后需要玩家在
/// 插件列表里勾选启用一次。
pub fn ensure_addon(wow_path: &str) {
    let path = wow_path.trim();
    if path.is_empty() {
        log("[状态] 未配置游戏路径，跳过 SLState 插件安装");
        return;
    }
    let Some(dir) = std::path::Path::new(path).parent() else {
        log(format!("[状态] 无法从路径解析游戏目录：{}", path));
        return;
    };
    let addon_dir = dir.join("Interface").join("AddOns").join("SLState");
    if let Err(e) = std::fs::create_dir_all(&addon_dir) {
        log(format!(
            "[状态] 插件目录创建失败（{}）：{}",
            addon_dir.display(),
            e
        ));
        return;
    }
    let toc = include_str!("../assets/SLState/SLState.toc");
    let lua = include_str!("../assets/SLState/SLState.lua");
    let toc_path = addon_dir.join("SLState.toc");
    let lua_path = addon_dir.join("SLState.lua");
    let mut changed = false;
    for (file, content) in [(&toc_path, toc), (&lua_path, lua)] {
        let same = std::fs::read_to_string(file)
            .map(|old| old == content)
            .unwrap_or(false);
        if !same {
            match std::fs::write(file, content) {
                Ok(()) => changed = true,
                Err(e) => log(format!("[状态] 写入 {} 失败：{}", file.display(), e)),
            }
        }
    }
    if changed {
        log(format!(
            "[状态] 已安装 SLState 插件：{}（首次需要在游戏插件列表中勾选启用）",
            addon_dir.display()
        ));
    } else if !ADDON_LOGGED.swap(true, Ordering::Relaxed) {
        log(format!("[状态] SLState 插件已是最新：{}", addon_dir.display()));
    }
}

/// 应用内存扫描总开关（界面开关与启动时都走这里）
///
/// 插件安装与开关无关：即使扫描关闭也会把插件放进 AddOns 目录，
/// 这样游戏插件列表里始终能看到并启用它；开关只决定是否扫描内存。
pub fn apply(enabled: bool, wow_path: &str) {
    *TARGET.lock().unwrap() = exe_name(wow_path);
    ensure_addon(wow_path);
    ENABLED.store(enabled, Ordering::SeqCst);
    if enabled {
        start_thread();
        log("[状态] 内存扫描已开启");
    } else {
        STATE.store(UNKNOWN, Ordering::Relaxed);
        log("[状态] 内存扫描已关闭");
    }
}

/// 从游戏 exe 路径取出可执行文件名（小写）
fn exe_name(wow_path: &str) -> Option<String> {
    std::path::Path::new(wow_path.trim())
        .file_name()
        .map(|n| n.to_string_lossy().to_ascii_lowercase())
        .filter(|n| n.ends_with(".exe"))
}

/// 确保扫描线程已启动（只创建一次，关闭开关时线程空转等待）
fn start_thread() {
    if STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    let _ = std::thread::Builder::new()
        .name("wow_state".to_string())
        .spawn(run);
}

fn run() {
    let mut handle: Option<HANDLE> = None;
    let mut base: Option<usize> = None;
    let mut last_seq: u16 = u16::MAX;
    let mut bad_reads = 0u32;
    let mut scan_fails = 0u64;
    let mut reported_missing = false;

    loop {
        // 总开关关闭：释放句柄、状态置未知，空转等待
        if !ENABLED.load(Ordering::Relaxed) {
            if let Some(h) = handle.take() {
                let _ = unsafe { CloseHandle(h) };
            }
            base = None;
            last_seq = u16::MAX;
            reported_missing = false;
            STATE.store(UNKNOWN, Ordering::Relaxed);
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }

        if handle.is_none() {
            let target = TARGET.lock().unwrap().clone();
            match open_wow(target.as_deref()) {
                Some(h) => {
                    handle = Some(h);
                    base = None;
                    last_seq = u16::MAX;
                    bad_reads = 0;
                    reported_missing = false;
                    log("[状态] 已连接到游戏进程");
                }
                None => {
                    STATE.store(UNKNOWN, Ordering::Relaxed);
                    if !reported_missing {
                        reported_missing = true;
                        log("[状态] 未找到游戏进程（启动游戏后会自动重试）");
                    }
                    std::thread::sleep(Duration::from_secs(2));
                    continue;
                }
            }
        }

        let h = handle.unwrap();

        match base {
            // 已定位：每 100ms 只读一帧，seq 没变说明状态没变，直接跳过
            Some(addr) => match read_frame(h, addr) {
                Some((seq, data)) => {
                    bad_reads = 0;
                    if seq != last_seq {
                        if last_seq == u16::MAX {
                            log(format!("[状态] 收到首帧 seq={} data={}", seq, data));
                        }
                        last_seq = seq;
                        set_state(data);
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
                None => {
                    // 连续读失败视为地址漂移（表被回收/重分配），退回全扫
                    bad_reads += 1;
                    if bad_reads >= 3 {
                        base = None;
                        STATE.store(UNKNOWN, Ordering::Relaxed);
                        log("[状态] 状态数组连续读取失败，重新扫描内存");
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
            },
            None => {
                let started = Instant::now();
                let mut regions = 0usize;
                match find_frame(h, &mut regions) {
                    Some(addr) => {
                        base = Some(addr);
                        last_seq = u16::MAX;
                        scan_fails = 0;
                        log(format!(
                            "[状态] 扫描命中 0x{:X}（{} 个区域，耗时 {}ms）",
                            addr,
                            regions,
                            started.elapsed().as_millis()
                        ));
                    }
                    None => {
                        STATE.store(UNKNOWN, Ordering::Relaxed);
                        scan_fails += 1;
                        // 没装插件时每次都会扫不中，这里做节流：首次与每 10 次记一条
                        if scan_fails == 1 || scan_fails % 10 == 0 {
                            log(format!(
                                "[状态] 扫描未命中第 {} 次（{} 个区域，耗时 {}ms）",
                                scan_fails,
                                regions,
                                started.elapsed().as_millis()
                            ));
                        }
                        // 句柄可能已失效（游戏退出后 PID 复用）：读全失败则重开
                        if !probe_alive(h) {
                            let _ = unsafe { CloseHandle(h) };
                            handle = None;
                        }
                        std::thread::sleep(Duration::from_secs(1));
                    }
                }
            }
        }
    }
}

fn set_state(data: f64) {
    let new = if data != 0.0 { STEALTH } else { NO_STEALTH };
    let old = STATE.swap(new, Ordering::Relaxed);
    if old != new {
        log(format!(
            "[状态] 角色状态变化：{} → {}",
            match old {
                NO_STEALTH => "非潜行",
                STEALTH => "潜行中",
                _ => "未知",
            },
            if new == STEALTH { "潜行中" } else { "非潜行" }
        ));
    }
}

/// 读取帧里的 double
///
/// # Panics
/// 调用方必须保证 `offset + 8 <= buf.len()`（`validate_frame`/`read_frame` 已保证整帧长度）。
fn f64_at(buf: &[u8], offset: usize) -> f64 {
    f64::from_le_bytes(buf[offset..offset + 8].try_into().unwrap())
}

fn near(a: f64, b: f64) -> bool {
    (a - b).abs() < 1e-6
}

/// 探测进程是否仍可读（读取进程头 4 字节）
fn probe_alive(h: HANDLE) -> bool {
    let mut buf = [0u8; 4];
    let mut read = 0usize;
    unsafe {
        ReadProcessMemory(
            h,
            std::ptr::null(),
            buf.as_mut_ptr() as *mut _,
            buf.len(),
            Some(&mut read),
        )
        .is_ok()
    }
}

/// 打开游戏进程（默认按 `wow.exe` 匹配，同时接受配置里指定的文件名）
fn open_wow(expected: Option<&str>) -> Option<HANDLE> {
    unsafe {
        let snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0).ok()?;
        let mut entry = PROCESSENTRY32W::default();
        entry.dwSize = size_of::<PROCESSENTRY32W>() as u32;
        let mut ok = Process32FirstW(snapshot, &mut entry).is_ok();
        while ok {
            let name = String::from_utf16_lossy(&entry.szExeFile)
                .trim_end_matches('\0')
                .to_ascii_lowercase();
            if name == "wow.exe" || expected == Some(name.as_str()) {
                let _ = CloseHandle(snapshot);
                return OpenProcess(
                    PROCESS_QUERY_INFORMATION | PROCESS_VM_READ,
                    false,
                    entry.th32ProcessID,
                )
                .ok();
            }
            ok = Process32NextW(snapshot, &mut entry).is_ok();
        }
        let _ = CloseHandle(snapshot);
        None
    }
}

/// 校验一帧，返回（seq, data）
///
/// 校验项：三个 anchor、4/8 号槽的 seq 一致且在 0..=65535、
/// 5 号槽 data 与 6 号槽互补、7 号槽校验和匹配。
fn validate_frame(b: &[u8]) -> Option<(u16, f64)> {
    if !(near(f64_at(b, 0), ANCHORS[0])
        && near(f64_at(b, SLOT), ANCHORS[1])
        && near(f64_at(b, 2 * SLOT), ANCHORS[2]))
    {
        return None;
    }
    // 可选加强：number 槽的类型标签应为 3（LUA_TNUMBER）
    if CHECK_TAG {
        for i in 3..NSLOT {
            let tag = i32::from_le_bytes(b[i * SLOT + 8..i * SLOT + 12].try_into().ok()?);
            if tag != 3 {
                return None;
            }
        }
    }
    let seq_a = f64_at(b, 3 * SLOT);
    let seq_b = f64_at(b, 7 * SLOT);
    if seq_a != seq_b || !(0.0..=65535.0).contains(&seq_a) {
        return None;
    }
    let data = f64_at(b, 4 * SLOT);
    let neg = f64_at(b, 5 * SLOT);
    if neg != -data {
        return None;
    }
    let chk = f64_at(b, 6 * SLOT);
    if (seq_a * 31.0 + data * 17.0) % 65536.0 != chk {
        return None;
    }
    Some((seq_a as u16, data))
}

/// 从已定位的地址读取一帧并校验
fn read_frame(h: HANDLE, addr: usize) -> Option<(u16, f64)> {
    let mut buf = [0u8; FRAME];
    let mut read = 0usize;
    let ok = unsafe {
        ReadProcessMemory(
            h,
            addr as *const _,
            buf.as_mut_ptr() as *mut _,
            FRAME,
            Some(&mut read),
        )
    };
    if ok.is_err() || read != FRAME {
        return None;
    }
    validate_frame(&buf)
}

/// 全内存扫描 anchor，返回状态数组首地址
///
/// 只扫已提交、可读的私有区（Lua 的堆来自 malloc，属于 MEM_PRIVATE）。
fn find_frame(h: HANDLE, regions: &mut usize) -> Option<usize> {
    let mut addr: usize = 0x1_0000;
    while addr < 0x8000_0000 {
        let mut info = MEMORY_BASIC_INFORMATION::default();
        let len = unsafe {
            VirtualQueryEx(
                h,
                Some(addr as *const _),
                &mut info,
                size_of::<MEMORY_BASIC_INFORMATION>(),
            )
        };
        if len == 0 {
            break;
        }
        let base = info.BaseAddress as usize;
        let size = info.RegionSize;
        if base == 0 || size == 0 {
            break;
        }
        let readable = info.State.0 == MEM_COMMIT.0
            && info.Type.0 == MEM_PRIVATE.0
            && (info.Protect.0 & (PAGE_NOACCESS.0 | PAGE_GUARD.0)) == 0;
        if readable && size >= FRAME {
            *regions += 1;
            if let Some(found) = scan_region(h, base, size) {
                return Some(found);
            }
        }
        addr = base.saturating_add(size);
    }
    None
}

/// 按 64KB 分块扫描一个内存区域，块与块之间重叠一帧，避免 anchor 跨边界漏掉
fn scan_region(h: HANDLE, base: usize, size: usize) -> Option<usize> {
    const STEP: usize = 0x1_0000; // 64KB
    let mut chunk = vec![0u8; STEP + FRAME];
    let mut offset = 0usize;
    while offset < size {
        let len = (size - offset).min(STEP + FRAME);
        let mut read = 0usize;
        let ok = unsafe {
            ReadProcessMemory(
                h,
                (base + offset) as *const _,
                chunk.as_mut_ptr() as *mut _,
                len,
                Some(&mut read),
            )
        };
        let got = if ok.is_ok() { read } else { 0 };
        if got >= FRAME {
            let buf = &chunk[..got];
            // TValue 数组按 8 字节（实际 16 字节）对齐，8 字节步进即可覆盖
            let mut k = 0usize;
            while k + FRAME <= got {
                if validate_frame(&buf[k..k + FRAME]).is_some() {
                    return Some(base + offset + k);
                }
                k += 8;
            }
        }
        offset += STEP;
    }
    None
}
