-- SteamLike State
--
-- 把角色状态暴露给 SteamLike Windows 客户端。
--
-- 原理：Lua 5.1 的 table 用顺序整数索引填充时，会分配一块连续的 TValue 数组
-- （array part），每槽 16 字节：前 8 字节 double 数值，后 4 字节类型标签
-- （number 为 3），再 4 字节对齐填充。外部进程扫描前三个 anchor 浮点定位这块
-- 内存，之后按 (i-1)*16 的固定步幅复读。
--
-- 三条硬约束（写错就失效）：
--   1. 初始化时一次顺序填满 1..8，之后永不增删索引
--      （中途跳着写或插入非整数键会触发 rehash，把槽挪到 hash part）；
--   2. 槽里只放 number（放字符串会变成 GC 指针，内容在另一块内存）；
--   3. 表必须保持强引用，不能被 GC 回收。
--
-- 槽位布局（索引 i 对应字节偏移 (i-1)*16）：
--   1..3  anchor 浮点
--   4     seq（帧序号，payload 写完才写它）
--   5     data（1 = 潜行中，0 = 非潜行）
--   6     -data（互补值，防撕裂/误码）
--   7     checksum = (seq*31 + data*17) % 65536
--   8     seq 再写一次（读端要求 4 与 8 相同才接受）
--
-- 命令：/slstate          打开/关闭设置面板
--       /slstate debug    切换调试日志（默认关闭）

SLStateDB = SLStateDB or {}
if SLStateDB.debug == nil then
    SLStateDB.debug = false
end

local PREFIX = "|cff33ff99[SLState]|r "

--- 调试日志：只在设置面板里打开开关后才输出到聊天框
local function Debug(msg)
    if SLStateDB.debug and DEFAULT_CHAT_FRAME then
        DEFAULT_CHAT_FRAME:AddMessage(PREFIX .. tostring(msg))
    end
end

local BUF = {}
for i = 1, 8 do BUF[i] = 0 end -- 顺序填满 → array part 稳定为 8 槽，无 hash part

-- anchor 在运行期拼出来，避免完整三个常量直接出现在字节码常量区
local a1 = 3000 + 494.38 -- 3494.38
local a2 = 1500 + 482.44 -- 1982.44
local a3 = 9000 + 918.71 -- 9918.71
BUF[3] = a3
BUF[2] = a2
BUF[1] = a1

-- 强引用，防止被 GC 回收
SL_STATE_MEM = BUF

local seq = 0
local last = -1
local acc = 0
local usedFallback = false
local panel
local optionsPanel
local statusTexts = {}
local debugChecks = {}

--- 设置界面里的状态文字（浮动面板与原生选项页共用）
local function UpdateStatus()
    local name = "未检测"
    if last == 1 then
        name = "潜行中"
    elseif last == 0 then
        name = "非潜行"
    end
    local text = string.format("当前状态：%s\nseq = %d    调试日志：%s", name, seq,
        SLStateDB.debug and "开" or "关")
    for i = 1, #statusTexts do
        statusTexts[i]:SetText(text)
    end
end

--- 切换调试日志（两个界面的勾选框保持同步）
local function SetDebug(on)
    SLStateDB.debug = on
    for i = 1, #debugChecks do
        debugChecks[i]:SetChecked(on)
    end
    if DEFAULT_CHAT_FRAME then
        DEFAULT_CHAT_FRAME:AddMessage(PREFIX .. "调试日志已" .. (on and "开启" or "关闭"))
    end
    UpdateStatus()
end

--- 写入一帧（payload 全部写完才写 seq，读端据此判断整帧有效）
local function Pulse(data)
    seq = (seq + 1) % 65536
    local chk = (seq * 31 + data * 17) % 65536
    BUF[5] = data
    BUF[6] = -data
    BUF[7] = chk
    BUF[4] = seq
    BUF[8] = seq
    Debug(string.format("写入帧 seq=%d data=%d chk=%d", seq, data, chk))
    UpdateStatus()
end

--- 检测玩家是否处于潜行状态
--- 优先用 IsStealthed()；个别客户端没有该 API 时退回扫描玩家增益图标
local function DetectStealth()
    if IsStealthed then
        return IsStealthed() and 1 or 0
    end
    if not usedFallback then
        usedFallback = true
        Debug("IsStealthed 不可用，改为扫描玩家增益图标")
    end
    for i = 1, 16 do
        -- 1.12 的第 1 个返回值是图标路径，3.3.5 的第 3 个才是，两者都取
        local first, _, third = UnitBuff("player", i)
        local icon = third or first
        if icon then
            local lower = string.lower(icon)
            if string.find(lower, "ability_stealth") or string.find(lower, "ability_ambush") then
                return 1
            end
        end
    end
    return 0
end

--- 只在状态变化时写帧，平时不写，客户端靠 seq 不变直接跳过
local function Refresh()
    local value = DetectStealth()
    if value ~= last then
        local before = last
        last = value
        Pulse(value)
        Debug(string.format("状态变化 %d → %d", before, value))
    end
end

--- 懒创建设置面板（1.12 的 API：SetBackdrop / UICheckButtonTemplate 都可用）
local function CreatePanel()
    if panel then
        return panel
    end
    panel = CreateFrame("Frame", "SLStatePanel", UIParent)
    panel:SetWidth(280)
    panel:SetHeight(132)
    panel:SetPoint("CENTER", UIParent, "CENTER", 0, 0)
    panel:SetFrameStrata("DIALOG")
    panel:EnableMouse(true)
    panel:SetMovable(true)
    panel:RegisterForDrag("LeftButton")
    panel:SetScript("OnDragStart", function(self)
        self:StartMoving()
    end)
    panel:SetScript("OnDragStop", function(self)
        self:StopMovingOrSizing()
    end)
    panel:SetBackdrop({
        bgFile = "Interface\\DialogFrame\\UI-DialogBox-Background",
        edgeFile = "Interface\\DialogFrame\\UI-DialogBox-Border",
        tile = true, tileSize = 32, edgeSize = 32,
        insets = { left = 8, right = 8, top = 8, bottom = 8 },
    })

    local title = panel:CreateFontString(nil, "OVERLAY", "GameFontNormal")
    title:SetPoint("TOP", panel, "TOP", 0, -14)
    title:SetText("SteamLike State")

    local close = CreateFrame("Button", nil, panel, "UIPanelCloseButton")
    close:SetPoint("TOPRIGHT", panel, "TOPRIGHT", -4, -4)

    local check = CreateFrame("CheckButton", "SLStateDebugCheck", panel, "UICheckButtonTemplate")
    check:SetWidth(24)
    check:SetHeight(24)
    check:SetPoint("TOPLEFT", panel, "TOPLEFT", 16, -38)
    getglobal("SLStateDebugCheckText"):SetText("启用调试日志")
    check:SetChecked(SLStateDB.debug and 1 or nil)
    check:SetScript("OnClick", function(self)
        SetDebug(self:GetChecked() and true or false)
    end)
    table.insert(debugChecks, check)

    local text = panel:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
    text:SetPoint("TOPLEFT", panel, "TOPLEFT", 20, -74)
    text:SetJustifyH("LEFT")
    table.insert(statusTexts, text)

    UpdateStatus()
    return panel
end

--- 原生插件管理页里的选项面板（3.3.5 的「界面 → 插件」页可点开）
local function CreateOptionsPanel()
    if optionsPanel then
        return optionsPanel
    end
    local p = CreateFrame("Frame", "SLStateOptionsPanel", InterfaceOptionsFramePanelContainer or UIParent)
    p.name = "SLState"
    p:Hide()

    local title = p:CreateFontString(nil, "ARTWORK", "GameFontNormalLarge")
    title:SetPoint("TOPLEFT", p, "TOPLEFT", 16, -16)
    title:SetText("SLState（SteamLike 角色状态）")

    local desc = p:CreateFontString(nil, "ARTWORK", "GameFontHighlightSmall")
    desc:SetPoint("TOPLEFT", title, "BOTTOMLEFT", 0, -8)
    -- 右侧锚点让宽度跟随设置面板，文字自动换行，不会跑出窗口
    desc:SetPoint("RIGHT", p, "RIGHT", -20, 0)
    desc:SetJustifyH("LEFT")
    desc:SetText("把角色是否潜行写入固定内存，供 SteamLike 客户端读取，"
        .. "用作按键映射的生效条件。\n需要先在客户端里打开「启用角色状态读取」。")

    local check = CreateFrame("CheckButton", "SLStateOptionsDebugCheck", p, "UICheckButtonTemplate")
    check:SetWidth(24)
    check:SetHeight(24)
    check:SetPoint("TOPLEFT", desc, "BOTTOMLEFT", 0, -16)
    getglobal("SLStateOptionsDebugCheckText"):SetText("启用调试日志（输出到聊天框）")
    check:SetChecked(SLStateDB.debug and 1 or nil)
    check:SetScript("OnClick", function(self)
        SetDebug(self:GetChecked() and true or false)
    end)
    table.insert(debugChecks, check)

    local text = p:CreateFontString(nil, "ARTWORK", "GameFontHighlight")
    text:SetPoint("TOPLEFT", check, "BOTTOMLEFT", 0, -16)
    text:SetJustifyH("LEFT")
    table.insert(statusTexts, text)

    if InterfaceOptions_AddCategory then
        InterfaceOptions_AddCategory(p)
    end
    -- 让面板铺满右侧设置区域，内容按左上角排布
    if InterfaceOptionsFramePanelContainer then
        p:SetAllPoints(InterfaceOptionsFramePanelContainer)
    end
    UpdateStatus()
    optionsPanel = p
    return p
end

SLASH_SLSTATE1 = "/slstate"
SlashCmdList["SLSTATE"] = function(msg)
    msg = string.lower(msg or "")
    if msg == "debug" then
        SetDebug(not SLStateDB.debug)
        return
    end
    local p = CreatePanel()
    if p:IsShown() then
        p:Hide()
    else
        p:Show()
        UpdateStatus()
    end
end

-- 注册到原生插件管理页（3.3.5 的「界面 → 插件」列表里出现 SLState 选项）
CreateOptionsPanel()

local SL_FRAME = CreateFrame("Frame")
SL_FRAME:RegisterEvent("PLAYER_ENTERING_WORLD")
SL_FRAME:SetScript("OnEvent", function()
    Debug("PLAYER_ENTERING_WORLD 触发，立即刷新")
    Refresh()
end)
SL_FRAME:SetScript("OnUpdate", function(_, elapsed)
    acc = acc + elapsed
    if acc >= 0.3 then
        acc = 0
        Refresh()
    end
end)

Debug(string.format("插件已加载，anchor = %.2f / %.2f / %.2f", BUF[1], BUF[2], BUF[3]))
