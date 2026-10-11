/*
 * InputBridge Client - Windows配套程序
 *
 * 在Winlator内运行，连接Android端的InputBridgeServer，
 * 接收手柄映射事件，通过SendInput()注入到Windows环境。
 *
 * 编译: gcc inputbridge_client.c -o inputbridge_client.exe -lws2_32 -luser32
 * 或使用: build.bat
 *
 * 用法: inputbridge_client.exe [android_ip] [port]
 *   默认: android_ip=127.0.0.1  port=27015
 *
 * 在Winlator中运行:
 *   1. 将inputbridge_client.exe复制到Winlator的C盘
 *   2. 在Winlator的命令行中运行: inputbridge_client.exe
 *   3. 保持窗口打开，切到WoW游戏即可
 */

#include <stdio.h> // 语法：#include 预处理指令，引入标准输入输出库（printf 等）
#include <stdlib.h> // 语法：#include 预处理指令，引入标准库（malloc/atoi 等）
#include <string.h> // 语法：#include 预处理指令，引入字符串处理库（strrchr/memcpy 等）
#include <winsock2.h> // 语法：#include 预处理指令，引入 Winsock2 套接字网络库
#include <windows.h> // 语法：#include 预处理指令，引入 Windows API（SendInput 等）
#include <ws2tcpip.h> // 语法：#include 预处理指令，引入 Winsock TCP/IP 扩展头文件

#pragma comment(lib, "ws2_32.lib") // 语法：#pragma comment 编译器指令，链接 ws2_32 网络库
#pragma comment(lib, "user32.lib") // 语法：#pragma comment 编译器指令，链接 user32 用户界面库

/* ===== 协议定义 ===== */

#define DEFAULT_PORT 27015 // 语法：#define 宏定义，默认连接端口号 27015
#define DEFAULT_IP   "127.0.0.1" // 语法：#define 宏定义，默认服务器 IP（本机回环地址）
#define PACKET_SIZE  8 // 语法：#define 宏定义，每个数据包固定为 8 字节
#define RECV_BUF_SIZE 4096 // 语法：#define 宏定义，接收缓冲区大小（字节）

/* 消息类型 */
#define MSG_KEY_EVENT    0x01 // 语法：#define 宏定义，消息类型：键盘事件
#define MSG_MOUSE_MOVE   0x02 // 语法：#define 宏定义，消息类型：鼠标移动
#define MSG_MOUSE_BUTTON 0x03 // 语法：#define 宏定义，消息类型：鼠标按钮
#define MSG_MOUSE_WHEEL  0x04 // 语法：#define 宏定义，消息类型：鼠标滚轮
#define MSG_RELEASE_ALL  0x05 // 语法：#define 宏定义，消息类型：释放所有已按下的按键
#define MSG_PING         0x06 // 语法：#define 宏定义，消息类型：心跳包（连接保活）
#define MSG_MOUSE_CENTER 0x07 // 语法：#define 宏定义，消息类型：把鼠标光标移动到屏幕中央

/* 鼠标按钮ID */
#define MOUSE_LEFT    0 // 语法：#define 宏定义，鼠标左键按钮 ID
#define MOUSE_RIGHT   1 // 语法：#define 宏定义，鼠标右键按钮 ID
#define MOUSE_MIDDLE  2 // 语法：#define 宏定义，鼠标中键按钮 ID
#define MOUSE_FORWARD 3 // 语法：#define 宏定义，鼠标前进侧键按钮 ID
#define MOUSE_BACK    4 // 语法：#define 宏定义，鼠标后退侧键按钮 ID

/* ===== 全局状态 ===== */

static SOCKET sock = INVALID_SOCKET; // 语法：static 静态全局变量，TCP 套接字句柄，初始化为无效值
static int running = 1; // 语法：static 静态全局变量，程序运行标志，1=运行中 0=退出

/* 当前按下的键集合（用于ReleaseAll） */
static unsigned char pressedKeys[256] = {0}; // 语法：static 静态数组，记录 256 个虚拟键码是否处于按下状态
static int pressedMouseButtons[5] = {0, 0, 0, 0, 0}; // 语法：static 静态数组，记录 5 个鼠标按钮的按下状态

/* ===== 输入注入函数 ===== */

/*
 * 注入键盘事件
 * vkCode: Windows虚拟键码
 * isDown: 1=按下, 0=释放
 */
static void InjectKey(WORD vkCode, int isDown) { // 语法：static 静态函数定义，注入单个键盘事件；WORD=16 位无符号整数类型
    INPUT input; // 语法：声明 INPUT 联合体变量，用于描述一次输入事件（键盘/鼠标）
    memset(&input, 0, sizeof(input)); // 语法：memset 将结构体清零；& 取地址；sizeof 计算结构体字节数
    input.type = INPUT_KEYBOARD; // 设置输入事件类型为键盘事件
    input.ki.wVk = vkCode; // 设置键盘事件的虚拟键码字段
    input.ki.wScan = 0; // 硬件扫描码置 0（使用虚拟键码模式）
    input.ki.dwFlags = isDown ? 0 : KEYEVENTF_KEYUP; // 按下时传 0，释放时传 KEYEVENTF_KEYUP 标志
    input.ki.time = 0; // 事件时间戳置 0（由系统自动填充）
    input.ki.dwExtraInfo = 0; // 附加信息置 0

    SendInput(1, &input, sizeof(INPUT)); // 语法：SendInput 注入输入事件；1=事件个数；& 取地址；sizeof 传结构体大小

    /* 更新状态 */
    if (vkCode < 256) { // 语法：if 条件判断，虚拟键码在合法范围（0~255）内
        pressedKeys[vkCode] = isDown ? 1 : 0; // 更新该键的按下状态记录
    }
}

/*
 * 注入鼠标移动
 * dx, dy: 相对位移
 */
static void InjectMouseMove(int dx, int dy) { // 语法：static 静态函数定义，注入鼠标相对移动事件
    INPUT input; // 语法：声明 INPUT 联合体变量，用于描述一次输入事件
    memset(&input, 0, sizeof(input)); // 语法：memset 清零；sizeof 计算结构体大小
    input.type = INPUT_MOUSE; // 设置输入事件类型为鼠标事件
    input.mi.dx = dx; // 设置鼠标相对移动的 X 方向位移
    input.mi.dy = dy; // 设置鼠标相对移动的 Y 方向位移
    input.mi.dwFlags = MOUSEEVENTF_MOVE; // 设置事件标志为鼠标相对移动
    input.mi.time = 0; // 事件时间戳置 0
    input.mi.dwExtraInfo = 0; // 附加信息置 0

    SendInput(1, &input, sizeof(INPUT)); // 语法：SendInput 注入鼠标移动事件
}

/*
 * 注入鼠标按钮事件
 * button: 0=左, 1=右, 2=中, 3=前进, 4=后退
 * isDown: 1=按下, 0=释放
 */
static void InjectMouseButton(int button, int isDown) { // 语法：static 静态函数定义，注入鼠标按钮按下/释放事件
    INPUT input; // 语法：声明 INPUT 联合体变量
    memset(&input, 0, sizeof(input)); // 语法：memset 清零；sizeof 计算结构体大小
    input.type = INPUT_MOUSE; // 设置输入事件类型为鼠标事件

    DWORD downFlag, upFlag; // 语法：声明两个 DWORD（32 位无符号整数）变量，存放按下/释放的事件标志
    DWORD mouseData = 0; // 存放扩展鼠标键数据（前进/后退键的 XBUTTON 值），默认 0
    switch (button) { // 语法：switch 分支语句，根据按钮 ID 选择对应的事件标志
        case MOUSE_LEFT: // 语法：case 分支：鼠标左键
            downFlag = MOUSEEVENTF_LEFTDOWN; // 左键按下事件标志
            upFlag = MOUSEEVENTF_LEFTUP; // 左键释放事件标志
            break; // 语法：break 跳出 switch 语句
        case MOUSE_RIGHT: // 语法：case 分支：鼠标右键
            downFlag = MOUSEEVENTF_RIGHTDOWN; // 右键按下事件标志
            upFlag = MOUSEEVENTF_RIGHTUP; // 右键释放事件标志
            break; // 语法：break 跳出 switch 语句
        case MOUSE_MIDDLE: // 语法：case 分支：鼠标中键
            downFlag = MOUSEEVENTF_MIDDLEDOWN; // 中键按下事件标志
            upFlag = MOUSEEVENTF_MIDDLEUP; // 中键释放事件标志
            break; // 语法：break 跳出 switch 语句
        case MOUSE_FORWARD: // 语法：case 分支：鼠标前进侧键
            downFlag = MOUSEEVENTF_XDOWN; // 扩展鼠标键按下事件标志
            upFlag = MOUSEEVENTF_XUP; // 扩展鼠标键释放事件标志
            mouseData = XBUTTON1; // 数据指定为 XBUTTON1（前进键标识）
            break; // 语法：break 跳出 switch 语句
        case MOUSE_BACK: // 语法：case 分支：鼠标后退侧键
            downFlag = MOUSEEVENTF_XDOWN; // 扩展鼠标键按下事件标志
            upFlag = MOUSEEVENTF_XUP; // 扩展鼠标键释放事件标志
            mouseData = XBUTTON2; // 数据指定为 XBUTTON2（后退键标识）
            break; // 语法：break 跳出 switch 语句
        default: // 语法：default 默认分支：未知按钮 ID
            return; // 语法：return 直接退出函数，不注入任何事件
    } // 结束 switch 语句

    input.mi.dwFlags = isDown ? downFlag : upFlag; // 根据按下/释放选择对应的事件标志
    input.mi.mouseData = mouseData; // 设置扩展鼠标键数据
    input.mi.time = 0; // 事件时间戳置 0
    input.mi.dwExtraInfo = 0; // 附加信息置 0

    SendInput(1, &input, sizeof(INPUT)); // 语法：SendInput 注入鼠标按钮事件

    /* 更新状态 */
    if (button >= 0 && button < 5) { // 语法：if 条件判断，按钮 ID 在合法范围（0~4）内
        pressedMouseButtons[button] = isDown ? 1 : 0; // 更新该按钮的按下状态记录
    }
}

/*
 * 注入鼠标滚轮
 * delta: 滚轮增量
 */
static void InjectMouseWheel(int delta) { // 语法：static 静态函数定义，注入鼠标滚轮事件
    INPUT input; // 语法：声明 INPUT 联合体变量
    memset(&input, 0, sizeof(input)); // 语法：memset 清零；sizeof 计算结构体大小
    input.type = INPUT_MOUSE; // 设置输入事件类型为鼠标事件
    input.mi.dwFlags = MOUSEEVENTF_WHEEL; // 设置事件标志为鼠标滚轮滚动
    input.mi.mouseData = (DWORD)delta; // 语法：类型转换，将滚轮增量放入 mouseData（正=向上滚动 负=向下）
    input.mi.time = 0; // 事件时间戳置 0
    input.mi.dwExtraInfo = 0; // 附加信息置 0

    SendInput(1, &input, sizeof(INPUT)); // 语法：SendInput 注入鼠标滚轮事件
}

/*
 * 将鼠标光标移动到屏幕中央
 */
static void InjectMouseCenter(void) { // 语法：static 静态函数定义，把光标移到主屏中心
    int cx = GetSystemMetrics(SM_CXSCREEN) / 2; // 语法：GetSystemMetrics 获取主屏宽度；除以 2 得到中心 X 坐标
    int cy = GetSystemMetrics(SM_CYSCREEN) / 2; // 语法：GetSystemMetrics 获取主屏高度；除以 2 得到中心 Y 坐标
    SetCursorPos(cx, cy); // 语法：SetCursorPos 将光标移动到指定屏幕坐标
}

/*
 * 释放所有按下的键和按钮
 */
static void ReleaseAllInputs(void) { // 语法：static 静态函数定义，批量释放所有按下的键盘键和鼠标按钮
    INPUT inputs[256]; // 语法：声明 INPUT 结构体数组，存放多个待注入的释放事件
    int count = 0; // 计数器，记录已放入数组的事件个数

    /* 释放所有按下的键盘按键 */
    for (int i = 0; i < 256; i++) { // 语法：for 循环遍历 0~255 所有虚拟键码
        if (pressedKeys[i]) { // 语法：if 判断该键是否处于按下状态
            memset(&inputs[count], 0, sizeof(INPUT)); // 语法：memset 清零；& 取数组元素地址；sizeof 计算大小
            inputs[count].type = INPUT_KEYBOARD; // 设置事件类型为键盘事件
            inputs[count].ki.wVk = (WORD)i; // 语法：类型转换，设置虚拟键码
            inputs[count].ki.dwFlags = KEYEVENTF_KEYUP; // 设置事件标志为按键释放
            count++; // 事件计数加 1
            pressedKeys[i] = 0; // 清除该键的按下状态记录
        }
    }

    /* 释放所有按下的鼠标按钮 */
    if (pressedMouseButtons[MOUSE_LEFT]) { // 语法：if 判断左键是否处于按下状态
        memset(&inputs[count], 0, sizeof(INPUT)); // 语法：memset 清零；sizeof 计算大小
        inputs[count].type = INPUT_MOUSE; // 设置事件类型为鼠标事件
        inputs[count].mi.dwFlags = MOUSEEVENTF_LEFTUP; // 设置事件标志为左键释放
        count++; // 事件计数加 1
        pressedMouseButtons[MOUSE_LEFT] = 0; // 清除左键按下状态
    }
    if (pressedMouseButtons[MOUSE_RIGHT]) { // 语法：if 判断右键是否处于按下状态
        memset(&inputs[count], 0, sizeof(INPUT)); // 语法：memset 清零；sizeof 计算大小
        inputs[count].type = INPUT_MOUSE; // 设置事件类型为鼠标事件
        inputs[count].mi.dwFlags = MOUSEEVENTF_RIGHTUP; // 设置事件标志为右键释放
        count++; // 事件计数加 1
        pressedMouseButtons[MOUSE_RIGHT] = 0; // 清除右键按下状态
    }
    if (pressedMouseButtons[MOUSE_MIDDLE]) { // 语法：if 判断中键是否处于按下状态
        memset(&inputs[count], 0, sizeof(INPUT)); // 语法：memset 清零；sizeof 计算大小
        inputs[count].type = INPUT_MOUSE; // 设置事件类型为鼠标事件
        inputs[count].mi.dwFlags = MOUSEEVENTF_MIDDLEUP; // 设置事件标志为中键释放
        count++; // 事件计数加 1
        pressedMouseButtons[MOUSE_MIDDLE] = 0; // 清除中键按下状态
    }
    if (pressedMouseButtons[MOUSE_FORWARD]) { // 语法：if 判断前进键是否处于按下状态
        memset(&inputs[count], 0, sizeof(INPUT)); // 语法：memset 清零；sizeof 计算大小
        inputs[count].type = INPUT_MOUSE; // 设置事件类型为鼠标事件
        inputs[count].mi.dwFlags = MOUSEEVENTF_XUP; // 设置事件标志为扩展键释放
        inputs[count].mi.mouseData = XBUTTON1; // 数据指定为 XBUTTON1（前进键标识）
        count++; // 事件计数加 1
        pressedMouseButtons[MOUSE_FORWARD] = 0; // 清除前进键按下状态
    }
    if (pressedMouseButtons[MOUSE_BACK]) { // 语法：if 判断后退键是否处于按下状态
        memset(&inputs[count], 0, sizeof(INPUT)); // 语法：memset 清零；sizeof 计算大小
        inputs[count].type = INPUT_MOUSE; // 设置事件类型为鼠标事件
        inputs[count].mi.dwFlags = MOUSEEVENTF_XUP; // 设置事件标志为扩展键释放
        inputs[count].mi.mouseData = XBUTTON2; // 数据指定为 XBUTTON2（后退键标识）
        count++; // 事件计数加 1
        pressedMouseButtons[MOUSE_BACK] = 0; // 清除后退键按下状态
    }

    if (count > 0) { // 语法：if 判断是否有待释放的事件
        SendInput(count, inputs, sizeof(INPUT)); // 语法：SendInput 一次性批量注入全部释放事件
    }
}

/* ===== 数据包处理 ===== */

/*
 * 处理一个8字节数据包
 */
static void ProcessPacket(const unsigned char* data) { // 语法：static 静态函数定义；const 限定只读；unsigned char* 为无符号字节指针
    unsigned char msgType = data[0]; // 语法：数组下标访问，取出第 0 字节作为消息类型

    switch (msgType) { // 语法：switch 分支语句，按消息类型分发处理
        case MSG_KEY_EVENT: { // 语法：case 分支：键盘事件
            /* Byte 1-2: VK Code (uint16 LE) */
            WORD vkCode = (WORD)(data[1] | (data[2] << 8)); // 语法：位运算拼接小端 16 位值；| 按位或；<< 左移 8 位；类型转换
            /* Byte 3: isDown */
            int isDown = data[3] ? 1 : 0; // 语法：三目运算符，第 3 字节非 0 视为按下
            InjectKey(vkCode, isDown); // 调用键盘事件注入函数
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_KEY_EVENT 分支

        case MSG_MOUSE_MOVE: { // 语法：case 分支：鼠标移动
            /* Byte 1-2: dx (int16 LE) */
            short dx = (short)(data[1] | (data[2] << 8)); // 语法：位运算解析小端 16 位位移；类型转换
            /* Byte 3-4: dy (int16 LE) */
            short dy = (short)(data[3] | (data[4] << 8)); // 语法：位运算解析小端 16 位位移；类型转换
            InjectMouseMove(dx, dy); // 调用鼠标移动注入函数
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_MOUSE_MOVE 分支

        case MSG_MOUSE_BUTTON: { // 语法：case 分支：鼠标按钮
            /* Byte 1: button */
            int button = data[1]; // 取出第 1 字节作为按钮 ID
            /* Byte 2: isDown */
            int isDown = data[2] ? 1 : 0; // 语法：三目运算符，第 2 字节非 0 视为按下
            InjectMouseButton(button, isDown); // 调用鼠标按钮注入函数
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_MOUSE_BUTTON 分支

        case MSG_MOUSE_WHEEL: { // 语法：case 分支：鼠标滚轮
            /* Byte 1-2: delta (int16 LE) */
            short delta = (short)(data[1] | (data[2] << 8)); // 语法：位运算解析小端 16 位滚轮增量；类型转换
            InjectMouseWheel(delta); // 调用鼠标滚轮注入函数
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_MOUSE_WHEEL 分支

        case MSG_RELEASE_ALL: { // 语法：case 分支：释放所有按键
            ReleaseAllInputs(); // 调用释放函数
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_RELEASE_ALL 分支

        case MSG_PING: { // 语法：case 分支：心跳包
            /* 心跳包，无需处理 */
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_PING 分支

        case MSG_MOUSE_CENTER: { // 语法：case 分支：鼠标移动到屏幕中央
            /* 无载荷，直接归中光标 */
            InjectMouseCenter(); // 调用鼠标居中函数
            break; // 语法：break 跳出 switch 语句
        } // 结束 MSG_MOUSE_CENTER 分支

        default: // 语法：default 默认分支：未知消息类型
            /* 未知消息类型，忽略 */
            break; // 语法：break 跳出 switch 语句
    } // 结束 switch 语句
} // 结束 ProcessPacket 函数

/* ===== 网络连接 ===== */

/*
 * 连接到Android服务器
 * 返回: 0=成功, -1=失败
 */
static int ConnectToServer(const char* ip, int port) { // 语法：static 静态函数定义；const char* 为只读字符串指针（服务器 IP）
    struct sockaddr_in serverAddr; // 语法：struct 声明套接字地址结构体，存放 IPv4 地址信息

    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP); // 语法：socket 创建套接字；AF_INET=IPv4；SOCK_STREAM=流式(TCP)；返回套接字句柄
    if (sock == INVALID_SOCKET) { // 语法：if 判断套接字创建是否失败
        printf("[ERROR] Socket creation failed: %d\n", WSAGetLastError()); // 打印错误信息，WSAGetLastError 获取错误码
        return -1; // 语法：return 返回 -1 表示失败
    }

    serverAddr.sin_family = AF_INET; // 设置地址族为 IPv4
    serverAddr.sin_port = htons((u_short)port); // 语法：htons 将端口号转为网络字节序；类型转换
    serverAddr.sin_addr.s_addr = inet_addr(ip); // 语法：inet_addr 将点分十进制 IP 字符串转为网络序数值

    printf("[CONNECT] Connecting to %s:%d ...\n", ip, port); // 打印正在连接服务器的信息

    if (connect(sock, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) == SOCKET_ERROR) { // 语法：connect 发起 TCP 连接；& 取地址；类型转换
        printf("[ERROR] Connection failed: %d\n", WSAGetLastError()); // 打印连接失败的错误码
        closesocket(sock); // 语法：closesocket 关闭套接字
        sock = INVALID_SOCKET; // 将套接字句柄重置为无效值
        return -1; // 语法：return 返回 -1 表示失败
    }

    printf("[CONNECT] Connected to Android server!\n"); // 打印连接成功信息
    return 0; // 语法：return 返回 0 表示成功
} // 结束 ConnectToServer 函数

/*
 * 接收并处理数据
 * 返回: 0=正常, -1=连接断开
 */
static int ReceiveAndProcess(void) { // 语法：static 静态函数定义，接收网络数据并按包处理
    unsigned char recvBuf[RECV_BUF_SIZE]; // 语法：声明接收缓冲区数组，大小 4096 字节
    static unsigned char packetBuf[PACKET_SIZE]; // 语法：static 静态数组，存放未凑满的 8 字节分包数据（跨调用保留）
    static int packetOffset = 0; // 语法：static 静态变量，当前分包已填充的字节偏移（跨调用保留）

    int bytesReceived = recv(sock, (char*)recvBuf, RECV_BUF_SIZE, 0); // 语法：recv 从套接字接收数据；类型转换；返回实际接收字节数
    if (bytesReceived <= 0) { // 语法：if 判断连接已关闭或出错
        return -1;  /* 连接断开 */ // 语法：return 返回 -1 表示连接断开
    }

    /* 处理接收到的数据，按8字节分包 */
    for (int i = 0; i < bytesReceived; i++) { // 语法：for 循环逐个字节处理接收到的数据
        packetBuf[packetOffset++] = recvBuf[i]; // 将当前字节填入分包缓冲，偏移自增
        if (packetOffset >= PACKET_SIZE) { // 语法：if 判断已凑满 8 字节
            /* 完整包，处理 */
            ProcessPacket(packetBuf); // 调用数据包处理函数
            packetOffset = 0; // 重置分包偏移，准备下一个包
        }
    }

    return 0; // 语法：return 返回 0 表示正常
} // 结束 ReceiveAndProcess 函数

/*
 * 控制台控制处理（Ctrl+C退出时清理）
 */
static BOOL WINAPI ConsoleHandler(DWORD signal) { // 语法：static 静态函数；WINAPI 为 Windows 调用约定；BOOL 为布尔类型；控制台事件回调函数
    if (signal == CTRL_C_EVENT || signal == CTRL_CLOSE_EVENT) { // 语法：if 逻辑或 || 判断是否为 Ctrl+C 或窗口关闭事件
        printf("\n[EXIT] Cleaning up...\n"); // 打印退出清理提示
        running = 0; // 将运行标志置 0，通知主循环退出
        ReleaseAllInputs(); // 释放所有按下的按键
        if (sock != INVALID_SOCKET) { // 语法：if 判断套接字句柄有效
            closesocket(sock); // 语法：closesocket 关闭套接字
        }
        ExitProcess(0); // 语法：ExitProcess 立即终止进程，退出码为 0
    }
    return TRUE; // 语法：return 返回 TRUE 表示该事件已处理
} // 结束 ConsoleHandler 函数

/* ===== config.json 读取与游戏启动 ===== */

/*
 * 获取当前可执行文件所在目录（不含尾部反斜杠）。
 * 用于定位与 exe 同目录的 config.json。
 */
static void GetExeDir(char* dir, size_t dirSize) { // 语法：static 静态函数定义；char* 为字符指针（输出参数）；size_t 无符号大小类型
    char exePath[MAX_PATH]; // 语法：声明字符数组，存放 exe 完整路径；MAX_PATH=260
    DWORD len = GetModuleFileNameA(NULL, exePath, MAX_PATH); // 语法：GetModuleFileNameA 获取当前 exe 完整路径；返回字符串长度
    if (len == 0 || len >= MAX_PATH) { dir[0] = '\0'; return; } // 语法：if 逻辑或 || 判断获取失败或路径过长；置空并返回
    char* slash = strrchr(exePath, '\\'); // 语法：strrchr 从右向左查找最后一个反斜杠；返回位置指针
    if (!slash) slash = strrchr(exePath, '/'); // 语法：if 判断未找到反斜杠；再用 strrchr 查找正斜杠
    size_t n = slash ? (size_t)(slash - exePath) : 0; // 语法：三目运算符；指针相减得到目录长度；类型转换
    if (n >= dirSize) n = dirSize - 1; // 语法：if 防止越界，限制目录长度不超过缓冲区
    memcpy(dir, exePath, n); // 语法：memcpy 拷贝目录部分到输出缓冲区
    dir[n] = '\0'; // 在目录末尾追加字符串结束符
} // 结束 GetExeDir 函数

/*
 * 从固定结构的 JSON 中提取 "wowPath" 字段的字符串值（含反转义）。
 *
 * 兼容格式: {"wowPath":"C:\\WoW\\Wow.exe"}（键值间可有空白）。
 * 读取成功返回 1，文件缺失/解析失败返回 0。
 */
static int LoadWowPathFromConfig(const char* filePath, char* out, size_t outSize) { // 语法：static 静态函数定义，从 JSON 配置文件读取 wowPath 字段
    FILE* f = fopen(filePath, "rb"); // 语法：fopen 以二进制只读方式打开文件；FILE* 为文件指针
    if (!f) return 0; // 语法：if 判断打开失败；return 返回 0 表示失败
    fseek(f, 0, SEEK_END); // 语法：fseek 将文件指针移动到文件末尾
    long len = ftell(f); // 语法：ftell 获取当前文件指针位置（即文件长度）
    fseek(f, 0, SEEK_SET); // 语法：fseek 将文件指针移回文件开头
    if (len <= 0 || len > 65536) { fclose(f); return 0; } // 语法：if 逻辑或 || 判断长度非法；fclose 关闭文件；return 返回 0
    char* buf = (char*)malloc((size_t)len + 1); // 语法：malloc 动态分配内存；类型转换；分配 len+1 字节存放文件内容
    if (!buf) { fclose(f); return 0; } // 语法：if 判断分配失败；fclose 关闭文件并返回 0
    size_t n = fread(buf, 1, (size_t)len, f); // 语法：fread 读取文件内容到缓冲区；返回实际读取字节数
    buf[n] = '\0'; // 在内容末尾追加字符串结束符
    fclose(f); // 语法：fclose 关闭文件

    const char* key = strstr(buf, "\"wowPath\""); // 语法：strstr 在缓冲区中查找 "wowPath" 字符串；返回位置指针
    if (!key) { free(buf); return 0; } // 语法：if 判断未找到；free 释放内存；return 返回 0
    const char* colon = strchr(key + 9, ':'); // 语法：strchr 在键名后查找冒号；指针加 9 跳过键名字符串
    if (!colon) { free(buf); return 0; } // 语法：if 判断未找到冒号；free 释放内存并返回 0
    const char* q1 = strchr(colon + 1, '"'); // 语法：strchr 查找值开头的双引号；指针加 1 跳过冒号
    if (!q1) { free(buf); return 0; } // 语法：if 判断未找到双引号；free 释放内存并返回 0

    const char* p = q1 + 1; // 指针指向值字符串的首个字符
    char* dst = out; // 语法：char* 字符指针，指向输出缓冲区的当前写入位置
    size_t used = 0; // 已写入输出缓冲区的字符数
    while (*p && used + 1 < outSize) { // 语法：while 循环遍历值字符；*p 解引用取值；&& 逻辑与；留出结束符空间
        if (*p == '"') break; /* 字符串结束 */ // 语法：if 判断遇到双引号则值结束；break 跳出循环
        if (*p == '\\' && (p[1] == '\\' || p[1] == '"' || p[1] == '/' || // 语法：if 判断转义字符开头；&& 与 || 逻辑运算
                           p[1] == 'n' || p[1] == 't' || p[1] == 'r' || p[1] == 'b' || p[1] == 'f')) { // 判断是否为常见转义字符
            switch (p[1]) { // 语法：switch 按转义字符类型进行转换
                case 'n': *dst++ = '\n'; break; // 换行转义；*dst++ 解引用赋值并后移指针
                case 't': *dst++ = '\t'; break; // 制表符转义
                case 'r': *dst++ = '\r'; break; // 回车转义
                case 'b': *dst++ = '\b'; break; // 退格转义
                case 'f': *dst++ = '\f'; break; // 换页转义
                default:  *dst++ = p[1]; break; /* \\ \" \/ 取原字符 */ // 语法：default 默认分支；其余转义取原字符
            } // 结束 switch 语句
            p += 2; used++; // 指针跳过反斜杠和转义字符；已用计数加 1
        } else { // 语法：else 非转义字符分支
            *dst++ = *p++; used++; // 普通字符直接拷贝；两个指针各自后移；计数加 1
        }
    }
    *dst = '\0'; // 在输出缓冲区末尾追加字符串结束符
    free(buf); // 语法：free 释放动态分配的内存
    return 1; // 语法：return 返回 1 表示读取成功
} // 结束 LoadWowPathFromConfig 函数

/*
 * 启动游戏进程（不等待），工作目录设为游戏 exe 所在目录。
 */
static void LaunchGameProcess(const char* exePath) { // 语法：static 静态函数定义；const char* 为只读字符串指针（游戏可执行文件路径）
    char cmd[MAX_PATH * 2]; // 语法：声明字符数组，存放命令行字符串；MAX_PATH*2 预留足够空间
    char workDir[MAX_PATH] = ""; // 声明工作目录缓冲区，初始化为空字符串
    const char* slash = strrchr(exePath, '\\'); // 语法：strrchr 查找最后一个反斜杠
    if (!slash) slash = strrchr(exePath, '/'); // 语法：if 判断未找到反斜杠；再用 strrchr 查找正斜杠
    if (slash) { // 语法：if 判断找到了路径分隔符
        size_t d = (size_t)(slash - exePath); // 语法：指针相减得到目录长度；类型转换
        if (d >= MAX_PATH) d = MAX_PATH - 1; // 语法：if 防止越界，限制目录长度
        memcpy(workDir, exePath, d); // 语法：memcpy 拷贝目录部分到工作目录缓冲区
        workDir[d] = '\0'; // 在工作目录末尾追加字符串结束符
    }

    /* 命令行带引号包裹，兼容路径含空格 */
    snprintf(cmd, sizeof(cmd), "\"%s\"", exePath); // 语法：snprintf 格式化字符串到缓冲区；sizeof 限制最大写入长度

    STARTUPINFOA si; // 语法：声明 STARTUPINFOA 结构体，描述进程启动信息
    PROCESS_INFORMATION pi; // 语法：声明 PROCESS_INFORMATION 结构体，接收新进程信息
    ZeroMemory(&si, sizeof(si)); // 语法：ZeroMemory 将结构体清零；& 取地址；sizeof 计算大小
    si.cb = sizeof(si); // 设置结构体大小字段（Windows API 要求）
    ZeroMemory(&pi, sizeof(pi)); // 语法：ZeroMemory 清零；sizeof 计算大小

    if (CreateProcessA(NULL, cmd, NULL, NULL, FALSE, // 语法：CreateProcessA 创建进程；FALSE=不继承句柄
                       NORMAL_PRIORITY_CLASS, NULL, // 指定普通优先级；环境块为空
                       workDir[0] ? workDir : NULL, &si, &pi)) { // 语法：三目运算符选择工作目录；& 取地址传结构体
        printf("[INFO] 游戏已启动: %s\n", exePath); // 打印游戏启动成功信息
        CloseHandle(pi.hThread); // 语法：CloseHandle 关闭线程句柄
        CloseHandle(pi.hProcess); // 语法：CloseHandle 关闭进程句柄
    } else { // 语法：else 创建进程失败分支
        printf("[ERROR] 启动游戏失败: %s (错误码=%lu)\n", exePath, GetLastError()); // 打印失败信息；GetLastError 获取错误码
    }
} // 结束 LaunchGameProcess 函数

/* ===== 主函数 ===== */

int main(int argc, char* argv[]) { // 语法：main 程序入口函数；argc=命令行参数个数；argv=参数数组（char* 指针数组）
    const char* ip = DEFAULT_IP; // 语法：const 只读指针，默认服务器 IP 地址
    int port = DEFAULT_PORT; // 默认端口号
    int reconnectDelay = 1000;  /* 重连延迟(ms) */ // 重连前的等待时间（毫秒）

    /* 解析命令行参数 */
    if (argc >= 2) ip = argv[1]; // 语法：if 判断参数个数；argv[1] 为第一个命令行参数（服务器 IP）
    if (argc >= 3) port = atoi(argv[2]); // 语法：if 判断参数个数；atoi 将字符串转为整数（端口号）

    /* 禁用stdout缓冲，确保管道/重定向环境下实时输出 */
    setvbuf(stdout, NULL, _IONBF, 0); // 语法：setvbuf 设置标准输出为无缓冲模式
    setvbuf(stderr, NULL, _IONBF, 0); // 语法：setvbuf 设置标准错误为无缓冲模式

    /* 单进程限制: 使用命名互斥锁确保同时只有一个实例运行 */
    HANDLE hMutex = CreateMutex(NULL, TRUE, "Global\\SteamLikeInputBridgeClient"); // 语法：CreateMutex 创建命名互斥锁；TRUE=初始拥有；返回句柄
    if (hMutex == NULL || GetLastError() == ERROR_ALREADY_EXISTS) { // 语法：if 逻辑或 || 判断创建失败或锁已存在
        printf("[ERROR] Another instance is already running. Exiting.\n"); // 打印已有实例在运行的提示
        if (hMutex) CloseHandle(hMutex); // 语法：if 判断句柄有效；CloseHandle 关闭互斥锁句柄
        return 1; // 语法：return 返回 1 退出程序
    }
    printf("[INFO] Single instance lock acquired.\n"); // 打印成功获取单实例锁的信息

    /* 读取 config.json 中的 wowPath 并启动游戏进程（缺失/为空则报错退出） */
    { // 开始一个局部代码块
        char configPath[MAX_PATH]; // 语法：声明字符数组，存放 config.json 的路径
        char wowPath[MAX_PATH] = ""; // 声明游戏路径缓冲区，初始化为空字符串
        char exeDir[MAX_PATH]; // 声明 exe 所在目录缓冲区

        /* 优先读取 exe 所在目录的 config.json，其次当前工作目录 */
        GetExeDir(exeDir, sizeof(exeDir)); // 调用函数获取 exe 所在目录
        if (exeDir[0]) { // 语法：if 判断目录非空
            snprintf(configPath, sizeof(configPath), "%s\\config.json", exeDir); // 语法：snprintf 拼接出 config.json 的完整路径
            LoadWowPathFromConfig(configPath, wowPath, sizeof(wowPath)); // 从 exe 所在目录的配置文件读取 wowPath
        }
        if (wowPath[0] == '\0') { // 语法：if 判断未读取到游戏路径
            LoadWowPathFromConfig("config.json", wowPath, sizeof(wowPath)); // 从当前工作目录的配置文件读取 wowPath
        }

        if (wowPath[0] == '\0') { // 语法：if 判断仍然没有有效的游戏路径
            printf("[ERROR] 未在 config.json 中找到有效的 wowPath 配置。\n"); // 打印错误信息
            printf("[ERROR] 请先在手机 App 主界面选择游戏 EXE 路径并重新导出 Windows 客户端。\n"); // 打印解决方法提示
            if (hMutex) CloseHandle(hMutex); // 语法：if 判断句柄有效；CloseHandle 释放互斥锁句柄
            return 1; // 语法：return 返回 1 退出程序
        }
        printf("[INFO] 读取 wowPath = %s\n", wowPath); // 打印读取到的游戏路径
        LaunchGameProcess(wowPath); // 启动游戏进程
    } // 结束局部代码块

    /* 初始化Winsock */
    WSADATA wsaData; // 语法：声明 WSADATA 结构体，存放 Winsock 初始化信息
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) { // 语法：WSAStartup 初始化 Winsock；MAKEWORD(2,2)=版本 2.2；& 取地址
        printf("[错误] WSAStartup失败: %d\n", WSAGetLastError()); // 打印初始化失败的错误码
        return 1; // 语法：return 返回 1 退出程序
    }

    /* 注册控制台退出处理 */
    SetConsoleCtrlHandler(ConsoleHandler, TRUE); // 语法：SetConsoleCtrlHandler 注册控制台事件处理函数；TRUE=添加

    printf("========================================\n"); // 打印分隔线
    printf("  InputBridge Client for Winlator\n"); // 打印程序标题
    printf("  SteamLike Controller - Windows Side\n"); // 打印副标题
    printf("========================================\n"); // 打印分隔线
    printf("  Server: %s:%d\n", ip, port); // 打印服务器地址和端口
    printf("  Protocol: TCP 8-byte fixed-length packets\n"); // 打印协议说明
    printf("  Injection: SendInput()\n"); // 打印输入注入方式说明
    printf("========================================\n"); // 打印分隔线
    printf("  Press Ctrl+C to quit\n\n"); // 打印退出提示

    /* 主循环: 连接 → 接收 → 断开 → 重连 */
    while (running) { // 语法：while 循环，条件为运行标志 running
        /* 连接到服务器 */
        if (ConnectToServer(ip, port) != 0) { // 语法：if 判断连接服务器是否失败
            printf("[RETRY] Reconnecting in %d seconds...\n", reconnectDelay / 1000); // 打印重连提示
            Sleep(reconnectDelay); // 语法：Sleep 暂停指定毫秒数
            continue; // 语法：continue 跳过本次循环，重新尝试连接
        }

        /* 接收并处理数据，直到连接断开 */
        while (running) { // 语法：while 内层循环，条件为运行标志 running
            if (ReceiveAndProcess() != 0) { // 语法：if 判断连接已断开
                printf("[DISCONNECT] Connection closed\n"); // 打印连接断开信息
                ReleaseAllInputs(); // 释放所有按下的按键
                break; // 语法：break 跳出内层循环
            }
        }

        closesocket(sock); // 语法：closesocket 关闭套接字
        sock = INVALID_SOCKET; // 将套接字句柄重置为无效值

        if (running) { // 语法：if 判断程序仍在运行
            printf("[RETRY] Reconnecting in %d seconds...\n", reconnectDelay / 1000); // 打印重连提示
            Sleep(reconnectDelay); // 语法：Sleep 暂停指定毫秒数后重连
        }
    }

    /* 清理 */
    ReleaseAllInputs(); // 退出前释放所有按下的按键
    WSACleanup(); // 语法：WSACleanup 清理 Winsock 资源

    /* 释放单进程互斥锁 */
    if (hMutex) { // 语法：if 判断互斥锁句柄有效
        ReleaseMutex(hMutex); // 语法：ReleaseMutex 释放互斥锁
        CloseHandle(hMutex); // 语法：CloseHandle 关闭互斥锁句柄
    }

    printf("[EXIT] Program exited\n"); // 打印程序退出信息
    return 0; // 语法：return 返回 0 表示正常退出
} // 结束 main 函数
