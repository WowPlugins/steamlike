//! WoW 插件安装
//!
//! 安装 `ChatStateBeacon` 插件：它在屏幕右上角（调试模式为屏幕中央）绘制一个色块信标
//! （聊天输入框打开=绿色，关闭=红色），APK 通过截屏读取该点像素判断聊天框状态。
//! 调试开关在插件自己的设置界面（界面 → 插件 → SteamLike 信标）或 `/slbeacon` 命令中。

/// 插件清单（3.3.5a 客户端接口号）
const ADDON_TOC: &str = "## Interface: 30300\n\
## Title: SteamLike 信标\n\
## Notes: 为 SteamLike 提供 WoW 聊天框状态信标（右上角色块）\n\
## Version: 1.0\n\
## SavedVariables: SteamLikeChatBeaconDB\n\
ChatStateBeacon.lua\n";

/// 插件正文：色块信标 + 设置界面 + `/slbeacon` 命令
const ADDON_LUA: &str = r#"-- SteamLike 聊天框状态信标
-- 绿色 = 聊天输入框打开；红色 = 关闭。供 Android 端截屏读取。
local beacon = CreateFrame("Frame", "SteamLikeChatBeacon", UIParent)
beacon:SetSize(14, 14)
beacon:SetFrameStrata("TOOLTIP")
local tex = beacon:CreateTexture(nil, "OVERLAY")
tex:SetAllPoints(beacon)

-- 根据调试开关把信标放到右上角或屏幕中央
local function ApplyAnchor()
    beacon:ClearAllPoints()
    if SteamLikeChatBeaconDB and SteamLikeChatBeaconDB.debugCenter then
        beacon:SetPoint("CENTER", UIParent, "CENTER", 0, 0)
    else
        beacon:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", 0, 0)
    end
end

-- 初始化存档（首次登录时 SavedVariables 为 nil）
local function InitDB()
    if type(SteamLikeChatBeaconDB) ~= "table" then
        SteamLikeChatBeaconDB = {}
    end
    if SteamLikeChatBeaconDB.debugCenter == nil then
        SteamLikeChatBeaconDB.debugCenter = false
    end
end

local function anyChatOpen()
    local count = NUM_CHAT_WINDOWS or 10
    for i = 1, count do
        local eb = _G["ChatFrame" .. i .. "EditBox"]
        if eb and eb:IsShown() then
            return true
        end
    end
    return false
end

InitDB()
ApplyAnchor()

local acc = 0
beacon:SetScript("OnUpdate", function(self, elapsed)
    acc = acc + elapsed
    if acc < 0.1 then
        return
    end
    acc = 0
    if anyChatOpen() then
        tex:SetTexture(0, 1, 0, 1)
    else
        tex:SetTexture(1, 0, 0, 1)
    end
end)

-- 设置界面（界面 → 插件 → SteamLike 信标）
local panel = CreateFrame("Frame")
panel.name = "SteamLike 信标"
local title = panel:CreateFontString(nil, "ARTWORK", "GameFontNormal")
title:SetPoint("TOPLEFT", 10, -10)
title:SetText("SteamLike 聊天框信标")
local desc = panel:CreateFontString(nil, "ARTWORK", "GameFontHighlight")
desc:SetPoint("TOPLEFT", title, "BOTTOMLEFT", 0, -8)
desc:SetWidth(540)
desc:SetJustifyH("LEFT")
desc:SetText("该插件在屏幕右上角绘制状态色块：绿色=聊天输入框打开，红色=关闭，供 Android 端识别聊天框状态。调试模式下色块移到屏幕中央，便于确认插件是否生效。")

local cb = CreateFrame("CheckButton", nil, panel, "InterfaceOptionsCheckButtonTemplate")
cb:SetPoint("TOPLEFT", desc, "BOTTOMLEFT", 0, -14)
-- 3.3.5 的模板标签是 $parentText，匿名按钮拿不到 → 自己建一个标签
local cbLabel = panel:CreateFontString(nil, "ARTWORK", "GameFontHighlight")
cbLabel:SetPoint("LEFT", cb, "RIGHT", 4, 0)
cbLabel:SetText("调试模式：信标显示在屏幕中央")
cb.tooltipText = "开启后色块移到屏幕中央；关闭后固定在右上角。修改立即生效，无需 /reload。"
cb:SetScript("OnClick", function(self)
    SteamLikeChatBeaconDB.debugCenter = self:GetChecked() and true or false
    ApplyAnchor()
end)
panel:SetScript("OnShow", function()
    cb:SetChecked(SteamLikeChatBeaconDB.debugCenter and true or false)
end)
InterfaceOptions_AddCategory(panel)

-- 快捷命令：/slbeacon 切换调试模式
SLASH_STEAMLIKEBEACON1 = "/slbeacon"
SlashCmdList["STEAMLIKEBEACON"] = function()
    SteamLikeChatBeaconDB.debugCenter = not SteamLikeChatBeaconDB.debugCenter
    ApplyAnchor()
    DEFAULT_CHAT_FRAME:AddMessage(
        "|cff4b8bf5SteamLike 信标|r：调试模式" ..
        (SteamLikeChatBeaconDB.debugCenter and "已开启（屏幕中央）" or "已关闭（右上角）") ..
        "，已立即生效"
    )
end
"#;

/// 确保插件已写入游戏目录（`<游戏目录>/Interface/AddOns/ChatStateBeacon/`）
pub fn ensure_installed(wow_path: &str) {
    if wow_path.trim().is_empty() {
        return;
    }
    let Some(dir) = std::path::Path::new(wow_path.trim()).parent() else {
        return;
    };
    let addon_dir = dir.join("Interface").join("AddOns").join("ChatStateBeacon");

    if let Err(e) = std::fs::create_dir_all(&addon_dir) {
        crate::runtime::log(format!("安装聊天框信标插件失败（建目录）：{}", e));
        return;
    }

    let mut changed = false;
    for (name, content) in [
        ("ChatStateBeacon.toc", ADDON_TOC),
        ("ChatStateBeacon.lua", ADDON_LUA),
    ] {
        let path = addon_dir.join(name);
        let same = std::fs::read_to_string(&path)
            .map(|old| old == content)
            .unwrap_or(false);
        if !same {
            match std::fs::write(&path, content) {
                Ok(()) => changed = true,
                Err(e) => crate::runtime::log(format!("写入插件 {} 失败：{}", name, e)),
            }
        }
    }

    if changed {
        crate::runtime::log(format!("已安装聊天框信标插件：{}", addon_dir.display()));
    }
}
