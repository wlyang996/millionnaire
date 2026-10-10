$ErrorActionPreference = 'Stop'
# Current gallery only. Historical sources are preserved separately.
$galleryRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$designRoot = Split-Path $galleryRoot -Parent
$sourceUi = Join-Path $galleryRoot 'ui'
function Find-LatestBoardImage([string] $legacyStem, [string] $descriptiveStem) {
    $candidates = @(Get-ChildItem -LiteralPath $sourceUi -Directory | ForEach-Object {
        if ($_.Name -match '^board-v(\d+)$') {
            $version = [int]$Matches[1]
            foreach ($stem in @($descriptiveStem, $legacyStem)) {
                $candidatePath = Join-Path $_.FullName ($stem + '-v' + $version + '.png')
                if ((Test-Path -LiteralPath $candidatePath -PathType Leaf) -and (Get-Item -LiteralPath $candidatePath).Length -gt 0) {
                    [pscustomobject]@{ Version = $version; Path = $candidatePath }
                    break
                }
            }
        }
    })
    $selected = $candidates | Sort-Object Version -Descending | Select-Object -First 1
    if (-not $selected) { throw "No completed image for $descriptiveStem under $sourceUi" }
    return $selected
}
$board = Find-LatestBoardImage 'board-design' '对局棋盘-普通回合'
$motion = Find-LatestBoardImage 'board-motion' '骰子翻滚与人物逐格跳跃-分镜'
$uiStates = Find-LatestBoardImage 'ui-states' '道具栏-回合提示-时间警示规范'
$entries = @(
    [pscustomobject]@{ File = '01-对局棋盘-普通回合.png'; Source = $board.Path; Role = '对局棋盘-普通回合'; Version = $board.Version; Status = 'design candidate' },
    [pscustomobject]@{ File = '02-骰子翻滚与人物逐格跳跃-动作分镜.png'; Source = $motion.Path; Role = '骰子翻滚与人物逐格跳跃-动作分镜'; Version = $motion.Version; Status = 'design candidate' },
    [pscustomobject]@{ File = '03-登录资料-本局结算-破产观战.png'; Source = (Join-Path $sourceUi 'refresh-v3/登录资料-本局结算-破产观战-v3.png'); Role = '登录资料-本局结算-破产观战'; Version = 3; Status = 'design candidate' },
    [pscustomobject]@{ File = '04-购买地产-升级-租金响应-交易确认.png'; Source = (Join-Path $sourceUi 'refresh-v3/购买地产-升级-租金响应-交易确认-v3.png'); Role = '购买地产-升级-租金响应-交易确认'; Version = 3; Status = 'design candidate' },
    [pscustomobject]@{ File = '05-拍卖竞价-应急抵押-欠款第二阶段.png'; Source = (Join-Path $sourceUi 'refresh-v3/拍卖竞价-应急抵押-欠款第二阶段-v3.png'); Role = '拍卖竞价-应急抵押-欠款第二阶段'; Version = 3; Status = 'design candidate' },
    [pscustomobject]@{ File = '06-好友房间-连接状态-重连同步.png'; Source = (Join-Path $sourceUi 'refresh-v3/好友房间-连接状态-重连同步-v3.png'); Role = '好友房间-连接状态-重连同步'; Version = 3; Status = 'design candidate' },
    [pscustomobject]@{ File = '07-风格参考A-历史四界面总览.png'; Source = (Join-Path $sourceUi 'main-screens-v1.png'); Role = '风格参考A-历史四界面总览'; Version = 1; Status = 'historical style reference' },
    [pscustomobject]@{ File = '08-风格参考B-大厅与好友房间.png'; Source = (Join-Path $galleryRoot 'assets-v2/layout_preview.png'); Role = '风格参考B-大厅与好友房间'; Version = 2; Status = 'design candidate' },
    [pscustomobject]@{ File = '09-道具横滑-回合提示-时间警示规范.png'; Source = $uiStates.Path; Role = '道具横滑-回合提示-时间警示规范'; Version = $uiStates.Version; Status = 'design candidate' },
    [pscustomobject]@{ File = '10-事件抽卡-本人中央点击卡背.png'; Source = (Join-Path $sourceUi 'event-card-preview/事件抽卡-本人中央点击卡背-v4.png'); Role = '事件抽卡-本人中央点击卡背'; Version = 4; Status = 'design candidate' },
    [pscustomobject]@{ File = '11-对局棋盘-他人回合等待.png'; Source = (Join-Path $sourceUi 'board-v7/对局棋盘-他人回合等待-v7.png'); Role = '对局棋盘-他人回合等待'; Version = 7; Status = 'design candidate' },
    [pscustomobject]@{ File = '12-事件卡-五类卡面与问号卡背.png'; Source = (Join-Path $sourceUi 'supplement-v1/事件卡-五类卡面与问号卡背-v1.png'); Role = '事件卡-五类卡面与问号卡背'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '13-路障放置-定点移动-查询目标与结果.png'; Source = (Join-Path $sourceUi 'supplement-v1/路障放置-定点移动-查询目标与结果-v1.png'); Role = '路障放置-定点移动-查询目标与结果'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '14-强制购房-降级-拆楼-清地.png'; Source = (Join-Path $sourceUi 'supplement-v1/强制购房-降级-拆楼-清地-v1.png'); Role = '强制购房-降级-拆楼-清地'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '15-银行抵押与赎回-监狱操作.png'; Source = (Join-Path $sourceUi 'supplement-v1/银行抵押与赎回-监狱操作-v1.png'); Role = '银行抵押与赎回-监狱操作'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '16-虎口拔牙结果-定点移动与房屋保护详情.png'; Source = (Join-Path $sourceUi 'supplement-v1/虎口拔牙结果-定点移动与房屋保护详情-v1.png'); Role = '虎口拔牙结果-定点移动与房屋保护详情'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '17-弃牌-认输二次确认-本人战绩.png'; Source = (Join-Path $sourceUi 'supplement-v1/弃牌-认输二次确认-本人战绩-v1.png'); Role = '弃牌-认输二次确认-本人战绩'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '18-资产总览-格子详情-房间号键盘-候选.png'; Source = (Join-Path $sourceUi 'supplement-v1/资产总览-格子详情-房间号键盘-候选-v1.png'); Role = '资产总览-格子详情-房间号键盘-候选'; Version = 1; Status = 'assets and tile layout adopted and browser checked; keypad candidate; exact artwork fidelity pending' },
    [pscustomobject]@{ File = '19-道具详情与聊天-待审阅.png'; Source = (Join-Path $sourceUi 'details-chat-v1/道具详情与聊天-待审阅-v1.png'); Role = '道具详情与聊天-待审阅'; Version = 1; Status = 'chat layout approved; card dialog superseded by screen 16; artwork integration pending' },
    [pscustomobject]@{ File = '20-银行-抵押与赎回-候选.png'; Source = (Join-Path $sourceUi 'special-lands-v1/银行-抵押与赎回-v1.png'); Role = '银行-抵押与赎回'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '21-监狱-出狱判定与游戏中心-虎口拔牙-候选.png'; Source = (Join-Path $sourceUi 'special-lands-v1/监狱-出狱判定与游戏中心-虎口拔牙-v1.png'); Role = '监狱-出狱判定与游戏中心-虎口拔牙'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '22-事件土地-中央抽卡与休息区-停留提示-候选.png'; Source = (Join-Path $sourceUi 'special-lands-v1/事件土地-中央抽卡与休息区-停留提示-v1.png'); Role = '事件土地-中央抽卡与休息区-停留提示'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '23-起点-经过奖励与车站-产权租金详情-候选.png'; Source = (Join-Path $sourceUi 'special-lands-v1/起点-经过奖励与车站-产权租金详情-v1.png'); Role = '起点-经过奖励与车站-产权租金详情'; Version = 1; Status = 'design candidate' },
    [pscustomobject]@{ File = '24-小镇入口-游戏大厅-候选.png'; Source = (Join-Path $sourceUi 'town-entrance-v1/小镇入口-游戏大厅-v1.png'); Role = '小镇入口-游戏大厅'; Version = 1; Status = 'misinterpreted request; retained for history; not adopted' },
    [pscustomobject]@{ File = '25-小镇入口-起点土地详情-候选.png'; Source = (Join-Path $sourceUi 'start-tile-v1/小镇入口-起点土地详情-v1.png'); Role = '小镇入口-起点土地详情'; Version = 1; Status = 'design candidate; not implemented' },
    [pscustomobject]@{ File = '26-道具响应-拒绝购买-房屋保护-建造确认.png'; Source = (Join-Path $sourceUi 'interaction-supplement-2026-10-07/道具响应-拒绝购买-房屋保护-建造确认-v3.png'); Role = '道具响应-拒绝购买-房屋保护-建造确认'; Version = 3; Status = 'design candidate; not implemented in this request' },
    [pscustomobject]@{ File = '27-发起拍卖-选择拍卖资产-卖家观看.png'; Source = (Join-Path $sourceUi 'interaction-supplement-2026-10-07/发起拍卖-选择拍卖资产-卖家观看-v3.png'); Role = '发起拍卖-选择拍卖资产-卖家观看'; Version = 3; Status = 'design candidate; not implemented in this request' },
    [pscustomobject]@{ File = '28-发起交易-等待答复.png'; Source = (Join-Path $sourceUi 'interaction-supplement-2026-10-07/发起交易-等待答复-v3.png'); Role = '发起交易-等待答复'; Version = 3; Status = 'design candidate; not implemented in this request' },
    [pscustomobject]@{ File = '29-拍卖交易-排队横幅与结果提示.png'; Source = (Join-Path $sourceUi 'interaction-supplement-2026-10-07/拍卖交易-排队横幅与结果提示-v3.png'); Role = '拍卖交易-排队横幅与结果提示'; Version = 3; Status = 'design candidate; not implemented in this request' },
    [pscustomobject]@{ File = '30-房屋保护-拆楼响应.png'; Source = (Join-Path $sourceUi 'interaction-supplement-2026-10-07/房屋保护-拆楼响应-v3.png'); Role = '房屋保护-拆楼响应'; Version = 3; Status = 'design candidate; not implemented in this request' },
    [pscustomobject]@{ File = '32-半程奖励-城市事件-趣味称号-候选.png'; Source = (Join-Path $sourceUi 'global-gameplay-v1/半程奖励-城市事件-趣味称号-v1.png'); Role = '轮次收入奖励全局提醒-城市事件预告与常驻标签-结算趣味称号'; Version = 1; Status = 'layout approved by user; implemented; historical reward title retained' }
)

# Check every source before copying any file.
foreach ($entry in $entries) {
    if (-not (Test-Path -LiteralPath $entry.Source -PathType Leaf)) {
        throw "Missing source: $($entry.Source)"
    }
    if ((Get-Item -LiteralPath $entry.Source).Length -eq 0) {
        throw "Empty source: $($entry.Source)"
    }
}

$published = @()
foreach ($entry in $entries) {
    $destination = [IO.Path]::GetFullPath((Join-Path $galleryRoot $entry.File))
    if (-not $destination.StartsWith($galleryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Destination outside gallery: $destination"
    }
    $sourceHash = (Get-FileHash -LiteralPath $entry.Source -Algorithm SHA256).Hash
    $destinationHash = if (Test-Path -LiteralPath $destination) {
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    } else { '' }
    $changed = $sourceHash -ne $destinationHash
    if ($changed) { Copy-Item -LiteralPath $entry.Source -Destination $destination -Force }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) {
        throw "Copy verification failed or source changed while copying: $($entry.File). Run again."
    }
    $published += [pscustomobject]@{
        file = $entry.File
        source = $entry.Source.Substring($designRoot.Length + 1).Replace('\', '/')
        version = $entry.Version
        role = $entry.Role
        status = $entry.Status
        sha256 = $sourceHash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $destination).Length
    }
    $status = if ($changed) { 'Updated' } else { 'Unchanged' }
    Write-Output "$status $($entry.File)"
}

$report = [ordered]@{
    board_version = $board.Version
    motion_version = $motion.Version
    ui_states_version = $uiStates.Version
    files = $published
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'sources.json') -Encoding UTF8
Write-Output "Gallery ready: $galleryRoot"
