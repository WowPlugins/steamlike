package com.steamlike.controller.injection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TCP 协议契约测试
 *
 * 锁定 [InputBridgeServer] 的消息类型取值，防止有人只改一端就提交。
 * 取值必须与 `docs/protocol.md` 和 `windows/rust_client/src/bridge.rs` 的 `MSG_*` 一致，
 * 对端的对应断言在 `bridge.rs` 的 `tests::message_codes_match_contract`。
 */
class ProtocolConstantsTest { // 语法：class=类声明，JUnit 4 测试类

    @Test // JUnit 4 测试方法标注
    fun 消息类型取值与协议文档一致() { // 中文函数名在 Kotlin 中合法，便于失败时快速定位
        val expected = mapOf( // 消息名 → 取值（与 docs/protocol.md 消息表逐行对应）
            "MSG_KEY_EVENT" to 0x01,
            "MSG_MOUSE_MOVE" to 0x02,
            "MSG_MOUSE_BUTTON" to 0x03,
            "MSG_MOUSE_WHEEL" to 0x04,
            "MSG_RELEASE_ALL" to 0x05,
            "MSG_PING" to 0x06,
            "MSG_MOUSE_CENTER" to 0x07,
            "MSG_MOUSE_CENTER_EXACT" to 0x08,
            "MSG_CURSOR_SAVE" to 0x09,
            "MSG_CURSOR_RESTORE" to 0x0A,
            "MSG_TIME_SYNC" to 0x0B,
            "MSG_VERSION" to 0x0C,
            "MSG_RUST_VERSION" to 0x0D,
            "MSG_CLIENT_VERSION" to 0x20,
            "MSG_CLIPBOARD_TEXT" to 0x21,
            "MSG_UNICODE_TEXT" to 0x22,
            "MSG_TOGGLE_KEYBOARD" to 0x23,
            "MSG_SHOW_KEYBOARD" to 0x25,
            "MSG_UNLOCK_MOUSE" to 0x26,
            "MSG_MAPPED_KEY" to 0x27,
            "MSG_LOCK_STATE" to 0x28
        )

        val actual = mapOf(
            "MSG_KEY_EVENT" to InputBridgeServer.MSG_KEY_EVENT.toInt(),
            "MSG_MOUSE_MOVE" to InputBridgeServer.MSG_MOUSE_MOVE.toInt(),
            "MSG_MOUSE_BUTTON" to InputBridgeServer.MSG_MOUSE_BUTTON.toInt(),
            "MSG_MOUSE_WHEEL" to InputBridgeServer.MSG_MOUSE_WHEEL.toInt(),
            "MSG_RELEASE_ALL" to InputBridgeServer.MSG_RELEASE_ALL.toInt(),
            "MSG_PING" to InputBridgeServer.MSG_PING.toInt(),
            "MSG_MOUSE_CENTER" to InputBridgeServer.MSG_MOUSE_CENTER.toInt(),
            "MSG_MOUSE_CENTER_EXACT" to InputBridgeServer.MSG_MOUSE_CENTER_EXACT.toInt(),
            "MSG_CURSOR_SAVE" to InputBridgeServer.MSG_CURSOR_SAVE.toInt(),
            "MSG_CURSOR_RESTORE" to InputBridgeServer.MSG_CURSOR_RESTORE.toInt(),
            "MSG_TIME_SYNC" to InputBridgeServer.MSG_TIME_SYNC.toInt(),
            "MSG_VERSION" to InputBridgeServer.MSG_VERSION.toInt(),
            "MSG_RUST_VERSION" to InputBridgeServer.MSG_RUST_VERSION.toInt(),
            "MSG_CLIENT_VERSION" to InputBridgeServer.MSG_CLIENT_VERSION.toInt(),
            "MSG_CLIPBOARD_TEXT" to InputBridgeServer.MSG_CLIPBOARD_TEXT.toInt(),
            "MSG_UNICODE_TEXT" to InputBridgeServer.MSG_UNICODE_TEXT.toInt(),
            "MSG_TOGGLE_KEYBOARD" to InputBridgeServer.MSG_TOGGLE_KEYBOARD.toInt(),
            "MSG_SHOW_KEYBOARD" to InputBridgeServer.MSG_SHOW_KEYBOARD.toInt(),
            "MSG_UNLOCK_MOUSE" to InputBridgeServer.MSG_UNLOCK_MOUSE.toInt(),
            "MSG_MAPPED_KEY" to InputBridgeServer.MSG_MAPPED_KEY.toInt(),
            "MSG_LOCK_STATE" to InputBridgeServer.MSG_LOCK_STATE.toInt()
        )

        assertEquals(expected, actual) // 一次性比对全部取值，失败信息直接给出差异项
    }

    @Test
    fun 消息类型不重复() {
        val codes = actualCodes() // 取全部消息值
        assertEquals(codes.size, codes.toSet().size) // 有重复说明两处分支会互相覆盖
    }

    @Test
    fun 包格式与默认端口一致() {
        assertEquals(8, InputBridgeServer.PACKET_SIZE) // 定长包 8 字节
        assertEquals(27015, InputBridgeServer.DEFAULT_PORT) // 默认监听端口
    }

    /** 收集全部消息类型取值（顺序与协议文档一致） */
    private fun actualCodes(): List<Int> = listOf(
        InputBridgeServer.MSG_KEY_EVENT.toInt(),
        InputBridgeServer.MSG_MOUSE_MOVE.toInt(),
        InputBridgeServer.MSG_MOUSE_BUTTON.toInt(),
        InputBridgeServer.MSG_MOUSE_WHEEL.toInt(),
        InputBridgeServer.MSG_RELEASE_ALL.toInt(),
        InputBridgeServer.MSG_PING.toInt(),
        InputBridgeServer.MSG_MOUSE_CENTER.toInt(),
        InputBridgeServer.MSG_MOUSE_CENTER_EXACT.toInt(),
        InputBridgeServer.MSG_CURSOR_SAVE.toInt(),
        InputBridgeServer.MSG_CURSOR_RESTORE.toInt(),
        InputBridgeServer.MSG_TIME_SYNC.toInt(),
        InputBridgeServer.MSG_VERSION.toInt(),
        InputBridgeServer.MSG_RUST_VERSION.toInt(),
        InputBridgeServer.MSG_CLIENT_VERSION.toInt(),
        InputBridgeServer.MSG_CLIPBOARD_TEXT.toInt(),
        InputBridgeServer.MSG_UNICODE_TEXT.toInt(),
        InputBridgeServer.MSG_TOGGLE_KEYBOARD.toInt(),
        InputBridgeServer.MSG_SHOW_KEYBOARD.toInt(),
        InputBridgeServer.MSG_UNLOCK_MOUSE.toInt(),
        InputBridgeServer.MSG_MAPPED_KEY.toInt(),
        InputBridgeServer.MSG_LOCK_STATE.toInt()
    )
}
