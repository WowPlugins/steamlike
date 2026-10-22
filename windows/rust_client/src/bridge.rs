//! 与 Android 端 InputBridgeServer 的 TCP 连接
//!
//! 协议：8 字节定长包
//! - 0x01 键盘事件：Byte1-2 VK(uint16 LE)，Byte3 是否按下
//! - 0x02 鼠标移动：Byte1-2 dx(int16 LE)，Byte3-4 dy(int16 LE)
//! - 0x03 鼠标按钮：Byte1 按钮(0左/1右/2中/3前进/4后退)，Byte2 是否按下
//! - 0x04 鼠标滚轮：Byte1-2 delta(int16 LE)
//! - 0x05 释放所有按键
//! - 0x06 心跳（忽略）
//! - 0x07 鼠标归位：Byte1 落点(0=屏幕正中央，1=屏幕中央正下方、屏幕下边缘之外 1/10 屏高处)
//! - 0x08 鼠标强制移动到屏幕正中央（交互键 / 解除锁定专用，无载荷）
//! - 0x09 记录当前光标位置（交互键序列）
//! - 0x0A 还原到 0x09 记录的光标位置（交互键序列）
//! - 0x0B 时间同步：Byte1-7 为小端 56 位 epoch 毫秒（APK 下发），校准 Windows 系统时间

use std::io::{ErrorKind, Read};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::atomic::Ordering;
use std::time::Duration;

use crate::runtime::{log, set_status, BRIDGE_TARGET, CONNECTED, WANT_CONNECT};
use crate::win;

const PACKET_SIZE: usize = 8;

/// 启动网络线程（程序启动时调用一次，线程自身按开关决定连接/断开）
pub fn start() {
    std::thread::Builder::new()
        .name("bridge".to_string())
        .spawn(bridge_thread)
        .expect("网络线程创建失败");
}

fn resolve(ip: &str, port: u16) -> Option<SocketAddr> {
    let host = if ip.trim().is_empty() { "127.0.0.1" } else { ip.trim() };
    (host, port).to_socket_addrs().ok().and_then(|mut it| it.next())
}

fn bridge_thread() {
    let mut stream: Option<TcpStream> = None;
    let mut connected_to: Option<(String, u16)> = None;
    let mut buf: Vec<u8> = Vec::with_capacity(4096);

    loop {
        if !WANT_CONNECT.load(Ordering::Relaxed) {
            if stream.is_some() {
                stream = None;
                connected_to = None;
                buf.clear();
                CONNECTED.store(false, Ordering::Relaxed);
                log("[网络] 已断开与 APK 服务器的连接");
            }
            set_status("未连接（连接开关已关闭）");
            std::thread::sleep(Duration::from_millis(200));
            continue;
        }

        let target = BRIDGE_TARGET.lock().unwrap().clone();

        // 服务器地址/端口被界面改动 → 断开后按新地址重连
        if stream.is_some() && connected_to.as_ref() != Some(&target) {
            stream = None;
            connected_to = None;
            buf.clear();
            CONNECTED.store(false, Ordering::Relaxed);
        }

        if stream.is_none() {
            set_status(format!("正在连接 {}:{} …", target.0, target.1));
            let Some(addr) = resolve(&target.0, target.1) else {
                set_status("服务器地址无效");
                std::thread::sleep(Duration::from_secs(1));
                continue;
            };
            match TcpStream::connect_timeout(&addr, Duration::from_secs(2)) {
                Ok(s) => {
                    // 手柄事件都是 8 字节小包，禁用 Nagle 降低延迟
                    let _ = s.set_nodelay(true);
                    let _ = s.set_read_timeout(Some(Duration::from_millis(200)));
                    stream = Some(s);
                    connected_to = Some(target.clone());
                    CONNECTED.store(true, Ordering::Relaxed);
                    set_status(format!("已连接 {}:{}", target.0, target.1));
                    log(format!("[网络] 已连接 APK 服务器 {}:{}", target.0, target.1));
                }
                Err(e) => {
                    set_status(format!("连接失败：{}（2 秒后重试）", e));
                    std::thread::sleep(Duration::from_secs(2));
                    continue;
                }
            }
        }

        if let Some(s) = stream.as_mut() {
            if !pump(s, &mut buf) {
                stream = None;
                connected_to = None;
                buf.clear();
                CONNECTED.store(false, Ordering::Relaxed);
                set_status("连接已断开，重连中…");
                log("[网络] 与 APK 服务器的连接已断开");
                std::thread::sleep(Duration::from_secs(1));
            }
        }
    }
}

/// 读一次套接字并按 8 字节分包处理；返回 false 表示连接已断开
fn pump(stream: &mut TcpStream, buf: &mut Vec<u8>) -> bool {
    let mut tmp = [0u8; 1024];
    match stream.read(&mut tmp) {
        Ok(0) => false,
        Ok(n) => {
            buf.extend_from_slice(&tmp[..n]);
            while buf.len() >= PACKET_SIZE {
                let mut packet = [0u8; PACKET_SIZE];
                packet.copy_from_slice(&buf[..PACKET_SIZE]);
                buf.drain(..PACKET_SIZE);
                process_packet(&packet);
            }
            true
        }
        // 读取超时用于周期性回到主循环检查开关状态
        Err(e) => matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut),
    }
}

fn process_packet(data: &[u8; PACKET_SIZE]) {
    match data[0] {
        0x01 => {
            let vk = u16::from_le_bytes([data[1], data[2]]);
            win::inject_key(vk, data[3] != 0);
        }
        0x02 => {
            let dx = i16::from_le_bytes([data[1], data[2]]) as i32;
            let dy = i16::from_le_bytes([data[3], data[4]]) as i32;
            win::inject_mouse_move(dx, dy);
        }
        0x03 => win::inject_mouse_button(data[1], data[2] != 0),
        0x04 => {
            let delta = i16::from_le_bytes([data[1], data[2]]);
            win::inject_mouse_wheel(delta);
        }
        0x05 => win::release_all(),
        0x06 => {} // 心跳
        0x07 => {
            // 落点由报文 Byte1 决定，仅作用于本次归位；
            // exe 自身的拦截路径落点由界面开关 + 配置文件决定，不在此处覆盖
            win::position_cursor(data[1] != 0);
        }
        // 交互键与各「解除锁定」路径的归中固定落在屏幕正中央，不受落点开关影响
        0x08 => win::center_cursor(),
        // 交互键序列：记录当前光标位置，供序列末尾还原
        0x09 => win::save_cursor(),
        // 交互键序列：把光标还原到 0x09 记录的位置
        0x0A => win::restore_cursor(),
        // 时间同步：Byte1-7 为小端 56 位 epoch 毫秒，校准 Windows 系统时间
        0x0B => {
            let mut millis: u64 = 0;
            for i in 0..7 {
                millis |= (data[1 + i] as u64) << (8 * i);
            }
            win::sync_system_time(millis);
        }
        _ => {}
    }
}
