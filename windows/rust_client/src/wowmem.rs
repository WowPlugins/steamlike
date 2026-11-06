//! 读取游戏进程内的角色状态（由 `SLState` 插件维护）
//!
//! 插件在 Lua 5.1 的 table array part 里维护一块连续 TValue 数组：
//! 每槽 16 字节（8 字节 double + 4 字节类型标签 + 4 字节对齐填充），
//! 前三个槽是固定的 anchor 浮点。本模块先全内存扫描这三个浮点定位数组首地址，
//! 之后每 100ms 只读一帧，用「双 seq + 校验和」判断帧是否有效。
//!
//! 槽位布局必须与 `SLState.lua` 完全一致：
//! ```text
//! 1..3   anchor 浮点
//! 4      seq（payload 写完才写它）
//! 5..14  状态位 S1..S10（1 = 真，0 = 假）
//! 15     checksum = (seq*31 + Σ S(i)*(i+1)) % 65536
//! 16     seq 再写一次（读端要求 4 与 16 相同才接受）
//! ```
//!
//! anchor 是数值而不是字符串，所以表内容变化时地址不会移动，
//! 只有句柄失效或内存被重新分配时才需要重扫。

use std::mem::size_of;
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
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
use windows::Win32::System::Threading::{
    GetExitCodeProcess, OpenProcess, PROCESS_QUERY_INFORMATION, PROCESS_VM_READ,
};

use crate::runtime::log;

/// Lua 5.1 顺序整数槽的字节数（double 8 + 类型标签 4 + 对齐 4）
const SLOT: usize = 16;
/// 插件预分配的槽数（必须与 Lua 的 BUF_SLOTS 一致）
const NSLOT: usize = 16;
/// 一帧的字节数
const FRAME: usize = SLOT * NSLOT;
/// anchor 浮点（对应插件里 1..3 号槽）
const ANCHORS: [f64; 3] = [3494.38, 1982.44, 9918.71];
/// 是否额外校验 TValue 类型标签（标准 Lua 5.1 的 number 为 3）
///
/// 默认关闭：若客户端换过 Lua 实现，标签值可能不同，开着会导致永远定位不到。
/// 三重 anchor 连续匹配 + 双 seq + 校验和，已经足够排除误命中。
const CHECK_TAG: bool = false;

/// 4 号槽：帧序号
const SLOT_SEQ: usize = 3;
/// 5..14 号槽：状态位，顺序与 [`STATE_DEFS`] 一致
const SLOT_STATE0: usize = 4;
/// 15 号槽：校验和
const SLOT_CHECKSUM: usize = 14;
/// 16 号槽：帧序号再写一次
const SLOT_SEQ2: usize = 15;

/// 可暴露的状态定义（顺序即插件里的数组槽位，必须与 `SLState.lua` 的 STATE_DEFS 一致）
pub const STATE_DEFS: [(&str, &str); 10] = [
    ("stealthed", "潜行中"),
    ("chatOpen", "聊天窗打开"),
    ("inCombat", "战斗中"),
    ("mounted", "骑乘中"),
    ("hasTarget", "有目标"),
    ("hasPet", "有宠物"),
    ("isDead", "死亡"),
    ("isGhost", "灵魂状态"),
    ("isFalling", "下落中"),
    ("isSwimming", "游泳中"),
];
/// 状态数量
pub const STATE_COUNT: usize = STATE_DEFS.len();

/// 最近一帧的状态位（bit i 对应 STATE_DEFS[i]）
static BITS: AtomicU16 = AtomicU16::new(0);
/// 状态是否有效（未找到进程 / 插件未启用 / 扫描失败时为 false）
static VALID: AtomicBool = AtomicBool::new(false);
/// 内存扫描总开关（界面可改，扫描线程每轮读取）
static ENABLED: AtomicBool = AtomicBool::new(false);
/// 扫描线程是否已创建（只创建一次，关闭开关时线程空转等待）
static STARTED: AtomicBool = AtomicBool::new(false);
/// 目标进程的可执行文件名（配置里的 exe 文件名，小写）
static TARGET: Mutex<Option<String>> = Mutex::new(None);

/// 读取状态位：`None` 表示状态不可用（未知）
pub fn states() -> Option<u16> {
    if VALID.load(Ordering::Relaxed) {
        Some(BITS.load(Ordering::Relaxed))
    } else {
        None
    }
}

/// 读取某个状态的当前值：`None` 表示状态不可用（未知）
///
/// `id` 取 [`STATE_DEFS`] 里的第一个字段（如 `stealthed`、`inCombat`）。
pub fn state(id: &str) -> Option<bool> {
    if !VALID.load(Ordering::Relaxed) {
        return None;
    }
    let index = STATE_DEFS.iter().position(|(key, _)| *key == id)?;
    Some(BITS.load(Ordering::Relaxed) & (1u16 << index) != 0)
}

/// 状态名（用于界面与日志）；未知 id 原样返回
pub fn state_name(id: &str) -> &str {
    STATE_DEFS
        .iter()
        .find(|(key, _)| *key == id)
        .map(|(_, name)| *name)
        .unwrap_or(id)
}

/// 当前为真的状态名列表（供界面显示），未知时返回「未知」
pub fn states_text() -> String {
    match states() {
        None => "未知".to_string(),
        Some(bits) => describe(bits),
    }
}

/// 把状态位转成「潜行中、战斗中」这样的文本
fn describe(bits: u16) -> String {
    let names: Vec<&str> = STATE_DEFS
        .iter()
        .enumerate()
        .filter(|(i, _)| bits & (1u16 << i) != 0)
        .map(|(_, (_, name))| *name)
        .collect();
    if names.is_empty() {
        "（全部为假）".to_string()
    } else {
        names.join("、")
    }
}

/// 应用内存扫描总开关（界面开关与启动时都走这里）
///
/// 插件文件由用户手动放在 `Interface/AddOns/SLState`，本程序不再负责安装。
pub fn apply(enabled: bool, wow_path: &str) {
    *TARGET.lock().unwrap() = exe_name(wow_path);
    ENABLED.store(enabled, Ordering::SeqCst);
    if enabled {
        start_thread();
        log("[状态] 内存扫描已开启");
    } else {
        VALID.store(false, Ordering::Relaxed);
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
            VALID.store(false, Ordering::Relaxed);
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
                    VALID.store(false, Ordering::Relaxed);
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
                Some((seq, bits)) => {
                    bad_reads = 0;
                    if seq != last_seq {
                        if last_seq == u16::MAX {
                            log(format!("[状态] 收到首帧 seq={} 状态={}", seq, describe(bits)));
                        }
                        last_seq = seq;
                        set_states(bits);
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
                None => {
                    // 连续读失败视为地址漂移（表被回收/重分配），退回全扫
                    bad_reads += 1;
                    if bad_reads >= 3 {
                        base = None;
                        VALID.store(false, Ordering::Relaxed);
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
                        VALID.store(false, Ordering::Relaxed);
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

/// 更新状态位并记录变化（首次读取与位变化时各记一条日志）
fn set_states(bits: u16) {
    let first = !VALID.swap(true, Ordering::Relaxed);
    let old = BITS.swap(bits, Ordering::Relaxed);
    if first {
        log(format!("[状态] 角色状态：{}", describe(bits)));
        return;
    }
    if old == bits {
        return;
    }
    let changed: Vec<String> = STATE_DEFS
        .iter()
        .enumerate()
        .filter(|(i, _)| (old ^ bits) & (1u16 << i) != 0)
        .map(|(i, (_, name))| {
            let on = bits & (1u16 << i) != 0;
            format!("{}={}", name, if on { "是" } else { "否" })
        })
        .collect();
    log(format!("[状态] 状态变化：{}", changed.join("、")));
}

/// 读取帧里的 double
///
/// # Panics
/// 调用方必须保证 `offset + 8 <= buf.len()`（`parse_frame`/`read_frame` 已保证整帧长度）。
fn f64_at(buf: &[u8], offset: usize) -> f64 {
    f64::from_le_bytes(buf[offset..offset + 8].try_into().unwrap())
}

fn near(a: f64, b: f64) -> bool {
    (a - b).abs() < 1e-6
}

/// 探测进程是否仍在运行（退出码 STILL_ACTIVE = 259）
///
/// 扫描失败时用这个判断句柄是否已失效（游戏退出后 PID 会被复用）。
fn probe_alive(h: HANDLE) -> bool {
    let mut code = 0u32;
    match unsafe { GetExitCodeProcess(h, &mut code) } {
        Ok(()) => code == 259,
        Err(_) => false,
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

/// 校验一帧，返回（seq, 状态位）
///
/// 校验项：三个 anchor、4/16 号槽的 seq 一致且在 0..=65535、
/// 状态槽只取 0/1、15 号槽校验和匹配。
fn parse_frame(b: &[u8]) -> Option<(u16, u16)> {
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
    let seq_a = f64_at(b, SLOT_SEQ * SLOT);
    let seq_b = f64_at(b, SLOT_SEQ2 * SLOT);
    if seq_a != seq_b || !(0.0..=65535.0).contains(&seq_a) {
        return None;
    }
    let mut bits = 0u16;
    let mut sum = 0.0f64;
    for i in 0..STATE_COUNT {
        let v = f64_at(b, (SLOT_STATE0 + i) * SLOT);
        if v != 0.0 && v != 1.0 {
            return None;
        }
        if v != 0.0 {
            bits |= 1u16 << i;
        }
        sum += v * (i as f64 + 2.0);
    }
    let chk = f64_at(b, SLOT_CHECKSUM * SLOT);
    if (seq_a * 31.0 + sum) % 65536.0 != chk {
        return None;
    }
    Some((seq_a as u16, bits))
}

/// 从已定位的地址读取一帧并校验
fn read_frame(h: HANDLE, addr: usize) -> Option<(u16, u16)> {
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
    parse_frame(&buf)
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
                if parse_frame(&buf[k..k + FRAME]).is_some() {
                    return Some(base + offset + k);
                }
                k += 8;
            }
        }
        offset += STEP;
    }
    None
}
