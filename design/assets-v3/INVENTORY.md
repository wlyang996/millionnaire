# 单件美术素材盘点（制作前）

唯一视觉依据：design/screens/；未读取或沿用 design/ui、assets、assets-v2。
README 已读取；01 为棋盘布局依据，07 为风格参考，08 仅记录候选存在，不采用其造型；10/11 为待决定事件候选。
720×1280 为客户端设计坐标。包围盒采用原图像素 (left, top, right, bottom)，右/下不含。原生尺寸为参考区域尺寸；被遮挡的参考区域不能当作可直接使用的完整素材。
输出目标为主体至少 2×建议显示尺寸，并额外留 8px 透明边。放大裁图不会增加原始细节，必须诚实区分。
缺失设计项写明“未出现”；其语义来自代码，仅风格来自 screens。文字、金额、昵称、数量均由 Cocos 文本渲染，避免烘焙。
8角色：小林/糖糖、可可、阿杰、奶茶、阿凯、圆圆、豆豆、毛毛；小林/糖糖为同一蓝衣男孩的页面命名差异。
代码核对覆盖 screens、screens/board、popups、ui 所有 .ts。代码仍是 Graphics 占位，本交付仅素材，不修改或集成客户端。
新增页面复用 inventory.json 坐标、tools 脚本及 prompts.jsonl；保留原始图和 SHA256。

共 189 个逐件条目。分类：勉强，放大会糊 71；不可用，需要补生成 115；可用 3。
第一阶段核心集 124 件；第二阶段 65 件（32个独立牙齿状态计入）。

## 页面与代码覆盖

- screens：资料/大厅/房间/棋盘/小游戏/结算/观战。
- popups：买地/升级/租金/交易/拍卖/欠款两段/重连；通用资产/地块详情/聊天/确认/弃牌/历史/加入房间复用面板与UI套件。后者没有专属整页设计。
- ui：Buttons/Widgets/Icons/DiceView/Backdrop/EditField/Keypad/ScrollList/HScroll/Toast 等复用无文字底板、图标与动态文本。
- 02 动作：骰子翻滚3帧、结果面6张、蓝衣男孩跳跃6帧；其余7人完整跳跃分镜缺失，静态棋子不等于动作动画。
- 09：倒计时状态用运行时颜色/透明度，5项手牌横滑用统一卡底与滑块；土地名使用文字。

## 逐件清单

|阶段|文件名/名称|用途/代码|设计图与像素包围盒|原生像素|能否直接抠取|720显示尺寸|建议输出尺寸|备注|
|---|---|---|---|---|---|---|---|---|
|1|avatar_xiaolin.png / 小林/糖糖头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [40, 831, 117, 907]|[77, 76]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|avatar_keke.png / 可可头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [136, 832, 205, 907]|[69, 75]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|avatar_ajie.png / 阿杰头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [224, 832, 295, 907]|[71, 75]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|avatar_naicha.png / 奶茶头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [319, 832, 387, 907]|[68, 75]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|avatar_akai.png / 阿凯头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [40, 951, 117, 1031]|[77, 80]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|avatar_yuanyuan.png / 圆圆头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [136, 951, 205, 1031]|[69, 80]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|avatar_doudou.png / 豆豆头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [224, 951, 295, 1031]|[71, 80]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|avatar_maomao.png / 毛毛头像|ui/Widgets.ts; screens/ProfileScreen.ts|03-profile-result.png [319, 951, 387, 1031]|[68, 80]|勉强，放大会糊|[72, 72]|[160, 160]|资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。|
|1|button_primary.png / 黄色主按钮|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|04-property-popups.png [190, 715, 398, 787]|[208, 72]|不可用，需要补生成|[280, 90]|[576, 196]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|button_secondary.png / 蓝色次按钮|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|05-auction-debt.png [1070, 536, 1415, 601]|[345, 65]|不可用，需要补生成|[280, 90]|[576, 196]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|button_disabled.png / 灰蓝禁用按钮|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|06-room-connection.png [28, 950, 334, 1021]|[306, 71]|不可用，需要补生成|[280, 90]|[576, 196]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|button_success.png / 绿色小按钮|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|03-profile-result.png [714, 475, 770, 509]|[56, 34]|不可用，需要补生成|[96, 48]|[208, 112]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|button_ghost.png / 灰蓝幽灵按钮|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|04-property-popups.png [47, 718, 183, 787]|[136, 69]|不可用，需要补生成|[200, 80]|[416, 176]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|button_green_login.png / 绿色登录按钮|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|03-profile-result.png [29, 518, 397, 596]|[368, 78]|不可用，需要补生成|[592, 92]|[1200, 200]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|panel_ivory.png / 象牙色弹窗面板|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|04-property-popups.png [35, 263, 410, 806]|[375, 543]|不可用，需要补生成|[640, 800]|[1296, 1616]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|panel_section.png / 内部分区块|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|04-property-popups.png [59, 522, 389, 595]|[330, 73]|不可用，需要补生成|[560, 108]|[1136, 232]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|panel_warning.png / 红色欠款分区|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|05-auction-debt.png [549, 335, 951, 421]|[402, 86]|不可用，需要补生成|[580, 112]|[1176, 240]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|panel_input.png / 昵称输入框|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|03-profile-result.png [32, 698, 386, 746]|[354, 48]|不可用，需要补生成|[560, 76]|[1136, 168]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|capsule_ready.png / 绿色准备胶囊|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|06-room-connection.png [42, 347, 128, 375]|[86, 28]|不可用，需要补生成|[120, 38]|[256, 92]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|capsule_idle.png / 灰色未准备胶囊|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|06-room-connection.png [373, 506, 456, 533]|[83, 27]|不可用，需要补生成|[120, 38]|[256, 92]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|title_wood.png / 木质标题牌|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [32, 26, 438, 99]|[406, 73]|不可用，需要补生成|[340, 70]|[696, 156]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|player_bar.png / 玩家状态条底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|01-board.png [271, 97, 474, 187]|[203, 90]|不可用，需要补生成|[155, 69]|[326, 154]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|asset_bar.png / 底部资产条底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|01-board.png [25, 1575, 911, 1672]|[886, 97]|不可用，需要补生成|[696, 72]|[1408, 160]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|turn_pill.png / 中央回合药丸|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|01-board.png [328, 647, 613, 760]|[285, 113]|不可用，需要补生成|[218, 86]|[452, 188]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|bubble_me.png / 我气泡|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [272, 766, 341, 826]|[69, 60]|不可用，需要补生成|[72, 50]|[160, 116]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|bubble_name.png / 我与昵称气泡|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|01-board.png [250, 1205, 362, 1264]|[112, 59]|不可用，需要补生成|[106, 46]|[228, 108]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|card_base.png / 统一道具卡底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [56, 431, 171, 585]|[115, 154]|不可用，需要补生成|[120, 144]|[256, 304]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|card_base_green.png / 绿色道具卡底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [173, 431, 281, 585]|[108, 154]|不可用，需要补生成|[120, 144]|[256, 304]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|card_base_yellow.png / 黄色道具卡底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [284, 431, 397, 585]|[113, 154]|不可用，需要补生成|[120, 144]|[256, 304]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|card_base_pink.png / 粉色道具卡底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [399, 431, 510, 585]|[111, 154]|不可用，需要补生成|[120, 144]|[256, 304]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|card_base_purple.png / 紫色道具卡底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [512, 431, 625, 585]|[113, 154]|不可用，需要补生成|[120, 144]|[256, 304]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|badge_count.png / 数量角标底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [1438, 410, 1483, 456]|[45, 46]|不可用，需要补生成|[30, 30]|[76, 76]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|scroll_track.png / 手牌滑动轨道|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [153, 600, 491, 617]|[338, 17]|不可用，需要补生成|[400, 12]|[816, 40]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|scroll_thumb.png / 手牌滑动滑块|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [182, 600, 221, 617]|[39, 17]|不可用，需要补生成|[48, 12]|[112, 40]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|selection_frame.png / 房间选项选中框|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|06-room-connection.png [253, 569, 355, 612]|[102, 43]|不可用，需要补生成|[130, 54]|[276, 124]|选项文字、勾选图标独立叠加。|
|1|hand_tray.png / 道具栏托盘底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|09-ui-states.png [33, 409, 639, 638]|[606, 229]|不可用，需要补生成|[640, 160]|[1296, 336]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|icon_circle.png / 圆形图标按钮底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|01-board.png [690, 1579, 778, 1664]|[88, 85]|不可用，需要补生成|[68, 68]|[152, 152]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|asset_button.png / 我的资产胶囊按钮底|ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*|01-board.png [430, 1593, 638, 1654]|[208, 61]|不可用，需要补生成|[168, 60]|[352, 136]|去除原图文字与叠加图标，仅生成可复用底板。|
|1|icon_back.png / 返回图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|01-board.png [144, 30, 165, 64]|[21, 34]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_close.png / 关闭图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_coin.png / 金币图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|04-property-popups.png [201, 541, 235, 577]|[34, 36]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_mic.png / 麦克风图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|01-board.png [716, 1592, 752, 1655]|[36, 63]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_chat.png / 聊天图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|01-board.png [834, 1593, 885, 1645]|[51, 52]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_share.png / 分享图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|06-room-connection.png [345, 149, 372, 174]|[27, 25]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_copy.png / 复制图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|06-room-connection.png [260, 155, 283, 180]|[23, 25]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_settings.png / 设置图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|07-style-reference-a.png [269, 58, 293, 81]|[24, 23]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_sound.png / 声音图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|07-style-reference-a.png [227, 58, 247, 80]|[20, 22]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_clock.png / 本局时钟图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|01-board.png [470, 31, 500, 62]|[30, 31]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_stopwatch.png / 回合闹钟图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|01-board.png [370, 658, 420, 708]|[50, 50]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_check.png / 勾选图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|06-room-connection.png [45, 350, 66, 371]|[21, 21]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_chevron.png / 右箭头图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|01-board.png [587, 1609, 602, 1635]|[15, 26]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_plus.png / 加号图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|05-auction-debt.png [400, 580, 424, 605]|[24, 25]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_minus.png / 减号图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|05-auction-debt.png [89, 588, 112, 597]|[23, 9]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_signal.png / 语音信号图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|06-room-connection.png [57, 913, 88, 936]|[31, 23]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_home.png / 房屋首页图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|07-style-reference-a.png [99, 651, 136, 693]|[37, 42]|勉强，放大会糊|[36, 36]|[88, 88]|主体独立、无文字；未出现的关闭图标参照返回图标的线宽。|
|1|icon_wechat.png / 微信双气泡图标|ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts|03-profile-result.png [114, 538, 170, 581]|[56, 43]|勉强，放大会糊|[36, 36]|[88, 88]|微信图标仅作本地视觉候选，微信胶囊本身由平台提供。|
|1|card_roadblock.png / 路障道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|09-ui-states.png [529, 457, 610, 530]|[81, 73]|勉强，放大会糊|[72, 84]|[160, 184]|不包含卡底与文字。忠实参考 09 道具卡。|
|1|card_free_rent.png / 免租道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|09-ui-states.png [80, 443, 152, 535]|[72, 92]|勉强，放大会糊|[72, 84]|[160, 184]|不包含卡底与文字。忠实参考 09 道具卡。|
|1|card_build.png / 建造道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|09-ui-states.png [186, 450, 269, 532]|[83, 82]|勉强，放大会糊|[72, 84]|[160, 184]|不包含卡底与文字。忠实参考 09 道具卡。|
|1|card_downgrade.png / 降级道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|card_fixed_move.png / 定点移动道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|09-ui-states.png [427, 449, 485, 536]|[58, 87]|勉强，放大会糊|[72, 84]|[160, 184]|不包含卡底与文字。忠实参考 09 道具卡。|
|1|card_jail_release.png / 出狱道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|09-ui-states.png [1179, 455, 1254, 534]|[75, 79]|勉强，放大会糊|[72, 84]|[160, 184]|不包含卡底与文字。忠实参考 09 道具卡。|
|1|card_auction.png / 拍卖道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|09-ui-states.png [299, 450, 384, 537]|[85, 87]|勉强，放大会糊|[72, 84]|[160, 184]|不包含卡底与文字。忠实参考 09 道具卡。|
|1|card_trade.png / 交易道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|card_refuse_purchase.png / 拒绝购买道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|card_house_protection.png / 房屋保护道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|card_query.png / 查询道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|card_forced_purchase.png / 强制购房道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|card_demolish.png / 拆楼道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|card_clear_land.png / 清地道具图标|ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[72, 84]|[160, 184]|不包含卡底与文字。设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。|
|1|tile_horizontal.png / 横向地块底|screens/board/BoardView.ts|01-board.png [399, 1331, 468, 1436]|[69, 105]|不可用，需要补生成|[54, 72]|[124, 160]|无地名无图标。各方向保持正面阅读，不旋转文字。|
|1|tile_vertical.png / 竖向地块底|screens/board/BoardView.ts|01-board.png [32, 554, 111, 625]|[79, 71]|不可用，需要补生成|[64, 56]|[144, 128]|无地名无图标。各方向保持正面阅读，不旋转文字。|
|1|tile_corner.png / 转角地块底|screens/board/BoardView.ts|01-board.png [9, 1334, 87, 1444]|[78, 110]|不可用，需要补生成|[66, 80]|[148, 176]|无地名无图标。各方向保持正面阅读，不旋转文字。|
|1|tile_start.png / 起点绿旗地块图标|screens/board/BoardView.ts|01-board.png [42, 1350, 81, 1402]|[39, 52]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_event.png / 事件问号地块图标|screens/board/BoardView.ts|01-board.png [272, 346, 314, 389]|[42, 43]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_bank.png / 银行地块图标|screens/board/BoardView.ts|01-board.png [411, 348, 459, 389]|[48, 41]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_jail.png / 监狱地块图标|screens/board/BoardView.ts|01-board.png [858, 1350, 901, 1394]|[43, 44]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_rest.png / 休息地块图标|screens/board/BoardView.ts|07-style-reference-a.png [1136, 473, 1187, 519]|[51, 46]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_game_zone.png / 游乐广场摩天轮地块图标|screens/board/BoardView.ts|01-board.png [835, 345, 894, 391]|[59, 46]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_station.png / 车站地块图标|screens/board/BoardView.ts|01-board.png [564, 347, 596, 386]|[32, 39]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_park.png / 公园地块图标|screens/board/BoardView.ts|01-board.png [54, 345, 106, 388]|[52, 43]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_theater.png / 剧场地块图标|screens/board/BoardView.ts|01-board.png [29, 909, 83, 950]|[54, 41]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_bridge.png / 石桥地块图标|screens/board/BoardView.ts|01-board.png [858, 1277, 914, 1310]|[56, 33]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_boat.png / 帆船地块图标|screens/board/BoardView.ts|01-board.png [854, 699, 909, 738]|[55, 39]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_bread.png / 面包地块图标|screens/board/BoardView.ts|01-board.png [28, 1054, 80, 1092]|[52, 38]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_pottery.png / 陶艺地块图标|screens/board/BoardView.ts|01-board.png [99, 1352, 137, 1393]|[38, 41]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_windmill.png / 风车地块图标|screens/board/BoardView.ts|01-board.png [550, 1346, 594, 1393]|[44, 47]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_lumber.png / 木匠地块图标|screens/board/BoardView.ts|01-board.png [619, 1350, 667, 1396]|[48, 46]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_flower.png / 花卉地块图标|screens/board/BoardView.ts|01-board.png [20, 1272, 76, 1310]|[56, 38]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|tile_sunflower.png / 向日葵地块图标|screens/board/BoardView.ts|01-board.png [857, 909, 910, 949]|[53, 40]|勉强，放大会糊|[38, 38]|[92, 92]|仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。|
|1|property_strip_low.png / 低档绿色地产色条|core/Theme.ts; screens/board/BoardView.ts|01-board.png [473, 414, 540, 434]|[67, 20]|可用|[54, 12]|[124, 40]|色档与当前 Theme.ts 一致；顶、底、侧方向由客户端旋转色条。|
|1|property_strip_mid.png / 中档蓝色地产色条|core/Theme.ts; screens/board/BoardView.ts|01-board.png [543, 415, 608, 435]|[65, 20]|可用|[54, 12]|[124, 40]|色档与当前 Theme.ts 一致；顶、底、侧方向由客户端旋转色条。|
|1|property_strip_high.png / 高档紫色地产色条|core/Theme.ts; screens/board/BoardView.ts|01-board.png [259, 416, 326, 435]|[67, 19]|可用|[54, 12]|[124, 40]|色档与当前 Theme.ts 一致；顶、底、侧方向由客户端旋转色条。|
|1|house_lv0.png / 房屋 0 级|screens/board/BoardView.ts; popups/Common.ts|09-ui-states.png [496, 828, 621, 946]|[125, 118]|勉强，放大会糊|[80, 84]|[176, 184]|0 级采用未升级小屋；等级轮廓参考 04 升级序列，但小图信息不足，需要补全；不凭屋顶颜色定义等级。|
|1|house_lv1.png / 房屋 1 级|screens/board/BoardView.ts; popups/Common.ts|04-property-popups.png [523, 639, 578, 687]|[55, 48]|勉强，放大会糊|[80, 84]|[176, 184]|0 级采用未升级小屋；等级轮廓参考 04 升级序列，但小图信息不足，需要补全；不凭屋顶颜色定义等级。|
|1|house_lv2.png / 房屋 2 级|screens/board/BoardView.ts; popups/Common.ts|04-property-popups.png [623, 636, 678, 686]|[55, 50]|勉强，放大会糊|[80, 84]|[176, 184]|0 级采用未升级小屋；等级轮廓参考 04 升级序列，但小图信息不足，需要补全；不凭屋顶颜色定义等级。|
|1|house_lv3.png / 房屋 3 级|screens/board/BoardView.ts; popups/Common.ts|04-property-popups.png [726, 639, 781, 686]|[55, 47]|勉强，放大会糊|[80, 84]|[176, 184]|0 级采用未升级小屋；等级轮廓参考 04 升级序列，但小图信息不足，需要补全；不凭屋顶颜色定义等级。|
|1|mark_mortgage.png / 抵押标记|screens/board/BoardView.ts|05-auction-debt.png [618, 488, 664, 512]|[46, 24]|不可用，需要补生成|[36, 28]|[88, 72]|设计图只有文字说明，没有独立标记；生成锁扣图标，押字由文本叠加。|
|1|mark_owner.png / 归属标记|screens/board/BoardView.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[18, 18]|[52, 52]|设计未覆盖；白边圆形底，客户端以玩家色染色。|
|1|board_roadblock.png / 棋盘路障|screens/board/BoardView.ts|09-ui-states.png [529, 457, 610, 530]|[81, 73]|勉强，放大会糊|[48, 44]|[112, 104]|参考道具路障，缩小摆在格子上。|
|1|pawn_xiaolin.png / 小林/糖糖棋子与底座|screens/board/BoardView.ts|02-motion-storyboard.png [1054, 774, 1196, 975]|[142, 201]|勉强，放大会糊|[64, 110]|[144, 236]|参考位置背景复杂，先裁参考再忠实还原。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|pawn_keke.png / 可可棋子与底座|screens/board/BoardView.ts|03-profile-result.png [136, 832, 205, 907]|[69, 75]|不可用，需要补生成|[64, 110]|[144, 236]|参考位置仅为头像，原图没有该角色完整棋子。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|pawn_ajie.png / 阿杰棋子与底座|screens/board/BoardView.ts|07-style-reference-a.png [838, 460, 891, 529]|[53, 69]|勉强，放大会糊|[64, 110]|[144, 236]|参考位置背景复杂，先裁参考再忠实还原。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|pawn_naicha.png / 奶茶棋子与底座|screens/board/BoardView.ts|07-style-reference-a.png [858, 257, 897, 311]|[39, 54]|勉强，放大会糊|[64, 110]|[144, 236]|参考位置背景复杂，先裁参考再忠实还原。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|pawn_akai.png / 阿凯棋子与底座|screens/board/BoardView.ts|07-style-reference-a.png [1126, 295, 1176, 355]|[50, 60]|勉强，放大会糊|[64, 110]|[144, 236]|参考位置背景复杂，先裁参考再忠实还原。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|pawn_yuanyuan.png / 圆圆棋子与底座|screens/board/BoardView.ts|07-style-reference-a.png [1138, 548, 1184, 610]|[46, 62]|勉强，放大会糊|[64, 110]|[144, 236]|参考位置背景复杂，先裁参考再忠实还原。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|pawn_doudou.png / 豆豆棋子与底座|screens/board/BoardView.ts|03-profile-result.png [224, 951, 295, 1031]|[71, 80]|不可用，需要补生成|[64, 110]|[144, 236]|参考位置仅为头像，原图没有该角色完整棋子。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|pawn_maomao.png / 毛毛棋子与底座|screens/board/BoardView.ts|03-profile-result.png [319, 951, 387, 1031]|[68, 80]|不可用，需要补生成|[64, 110]|[144, 236]|参考位置仅为头像，原图没有该角色完整棋子。统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。|
|1|dice_face_1.png / 1点骰子|ui/DiceView.ts|02-motion-storyboard.png [95, 215, 203, 321]|[108, 106]|勉强，放大会糊|[128, 128]|[272, 272]|点数指朝上的结果面；三可见面必须符合标准骰子对面和为7；2/3/5/6没有独立结果设计。|
|1|dice_face_2.png / 2点骰子|ui/DiceView.ts|01-board.png [390, 778, 548, 944]|[158, 166]|不可用，需要补生成|[128, 128]|[272, 272]|点数指朝上的结果面；三可见面必须符合标准骰子对面和为7；2/3/5/6没有独立结果设计。|
|1|dice_face_3.png / 3点骰子|ui/DiceView.ts|01-board.png [390, 778, 548, 944]|[158, 166]|不可用，需要补生成|[128, 128]|[272, 272]|点数指朝上的结果面；三可见面必须符合标准骰子对面和为7；2/3/5/6没有独立结果设计。|
|1|dice_face_4.png / 4点骰子|ui/DiceView.ts|02-motion-storyboard.png [1329, 186, 1464, 321]|[135, 135]|勉强，放大会糊|[128, 128]|[272, 272]|点数指朝上的结果面；三可见面必须符合标准骰子对面和为7；2/3/5/6没有独立结果设计。|
|1|dice_face_5.png / 5点骰子|ui/DiceView.ts|01-board.png [390, 778, 548, 944]|[158, 166]|不可用，需要补生成|[128, 128]|[272, 272]|点数指朝上的结果面；三可见面必须符合标准骰子对面和为7；2/3/5/6没有独立结果设计。|
|1|dice_face_6.png / 6点骰子|ui/DiceView.ts|01-board.png [390, 778, 548, 944]|[158, 166]|不可用，需要补生成|[128, 128]|[272, 272]|点数指朝上的结果面；三可见面必须符合标准骰子对面和为7；2/3/5/6没有独立结果设计。|
|1|dice_roll_1.png / 骰子翻滚帧 1|ui/DiceView.ts|02-motion-storyboard.png [336, 147, 456, 275]|[120, 128]|勉强，放大会糊|[128, 128]|[272, 272]|仅用于翻滚表现，不决定对局结果。|
|1|dice_roll_2.png / 骰子翻滚帧 2|ui/DiceView.ts|02-motion-storyboard.png [577, 168, 704, 282]|[127, 114]|勉强，放大会糊|[128, 128]|[272, 272]|仅用于翻滚表现，不决定对局结果。|
|1|dice_roll_3.png / 骰子翻滚帧 3|ui/DiceView.ts|02-motion-storyboard.png [1082, 156, 1205, 279]|[123, 123]|勉强，放大会糊|[128, 128]|[272, 272]|仅用于翻滚表现，不决定对局结果。|
|1|pawn_hop_1.png / 蓄力跳跃帧|screens/board/BoardView.ts|02-motion-storyboard.png [67, 828, 155, 934]|[88, 106]|勉强，放大会糊|[92, 128]|[200, 272]|同一蓝衣男孩，底座另行叠加；每步约250ms，不能直接当沿路径滑行。|
|1|pawn_hop_2.png / 起跳跳跃帧|screens/board/BoardView.ts|02-motion-storyboard.png [206, 801, 307, 918]|[101, 117]|勉强，放大会糊|[92, 128]|[200, 272]|同一蓝衣男孩，底座另行叠加；每步约250ms，不能直接当沿路径滑行。|
|1|pawn_hop_3.png / 腾空跳跃帧|screens/board/BoardView.ts|02-motion-storyboard.png [365, 784, 466, 901]|[101, 117]|勉强，放大会糊|[92, 128]|[200, 272]|同一蓝衣男孩，底座另行叠加；每步约250ms，不能直接当沿路径滑行。|
|1|pawn_hop_4.png / 下落跳跃帧|screens/board/BoardView.ts|02-motion-storyboard.png [519, 808, 603, 920]|[84, 112]|勉强，放大会糊|[92, 128]|[200, 272]|同一蓝衣男孩，底座另行叠加；每步约250ms，不能直接当沿路径滑行。|
|1|pawn_hop_5.png / 落地跳跃帧|screens/board/BoardView.ts|02-motion-storyboard.png [672, 832, 759, 936]|[87, 104]|勉强，放大会糊|[92, 128]|[200, 272]|同一蓝衣男孩，底座另行叠加；每步约250ms，不能直接当沿路径滑行。|
|1|pawn_hop_6.png / 回弹跳跃帧|screens/board/BoardView.ts|02-motion-storyboard.png [822, 803, 939, 932]|[117, 129]|勉强，放大会糊|[92, 128]|[200, 272]|同一蓝衣男孩，底座另行叠加；每步约250ms，不能直接当沿路径滑行。|
|1|pawn_base_ring.png / 棋子发光底座环|screens/board/BoardView.ts|02-motion-storyboard.png [1064, 904, 1201, 977]|[137, 73]|不可用，需要补生成|[74, 36]|[164, 88]|抽离人物后还原蓝色底盘和青色柔光。|
|2|town_tree.png / 树|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [161, 486, 293, 623]|[132, 137]|不可用，需要补生成|[112, 144]|[240, 304]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|town_bridge.png / 石桥|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [512, 526, 714, 630]|[202, 104]|不可用，需要补生成|[240, 144]|[496, 304]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|town_duck.png / 鸭子|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [563, 608, 600, 643]|[37, 35]|不可用，需要补生成|[42, 42]|[100, 100]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|town_house.png / 小镇房屋|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [663, 923, 816, 1104]|[153, 181]|不可用，需要补生成|[164, 204]|[344, 424]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|town_river.png / 河湾河流|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [439, 551, 833, 1210]|[394, 659]|不可用，需要补生成|[350, 580]|[716, 1176]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|town_boat.png / 木船|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [598, 1134, 704, 1196]|[106, 62]|不可用，需要补生成|[112, 64]|[240, 144]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|town_lamp.png / 街灯|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [578, 874, 617, 1008]|[39, 134]|不可用，需要补生成|[40, 110]|[96, 236]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|board_center.png / 整块棋盘中央背景|ui/Backdrop.ts; screens/board/BoardView.ts|01-board.png [119, 438, 818, 1328]|[699, 890]|不可用，需要补生成|[536, 682]|[1088, 1380]|原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。|
|2|bg_lobby.png / 大厅背景|ui/Backdrop.ts; screens/*|07-style-reference-a.png [7, 34, 401, 963]|[394, 929]|不可用，需要补生成|[720, 1280]|[1456, 2576]|整页被 UI 和人物遮挡；仅作构图参考，重新生成完整背景。背景本身不透明，PNG画布外保留8px透明边。|
|2|bg_room.png / 好友房间背景|ui/Backdrop.ts; screens/*|06-room-connection.png [13, 40, 492, 1043]|[479, 1003]|不可用，需要补生成|[720, 1280]|[1456, 2576]|整页被 UI 和人物遮挡；仅作构图参考，重新生成完整背景。背景本身不透明，PNG画布外保留8px透明边。|
|2|bg_profile.png / 登录资料背景|ui/Backdrop.ts; screens/*|03-profile-result.png [10, 69, 410, 1254]|[400, 1185]|不可用，需要补生成|[720, 1280]|[1456, 2576]|整页被 UI 和人物遮挡；仅作构图参考，重新生成完整背景。背景本身不透明，PNG画布外保留8px透明边。|
|2|bg_result.png / 结算背景|ui/Backdrop.ts; screens/*|03-profile-result.png [416, 69, 803, 1254]|[387, 1185]|不可用，需要补生成|[720, 1280]|[1456, 2576]|整页被 UI 和人物遮挡；仅作构图参考，重新生成完整背景。背景本身不透明，PNG画布外保留8px透明边。|
|2|bg_board.png / 对局背景|ui/Backdrop.ts; screens/*|01-board.png [0, 0, 941, 1672]|[941, 1672]|不可用，需要补生成|[720, 1280]|[1456, 2576]|整页被 UI 和人物遮挡；仅作构图参考，重新生成完整背景。背景本身不透明，PNG画布外保留8px透明边。|
|2|bg_teeth.png / 小游戏背景|ui/Backdrop.ts; screens/*|07-style-reference-a.png [1223, 34, 1620, 963]|[397, 929]|不可用，需要补生成|[720, 1280]|[1456, 2576]|整页被 UI 和人物遮挡；仅作构图参考，重新生成完整背景。背景本身不透明，PNG画布外保留8px透明边。|
|2|croc_open.png / 张嘴鳄鱼|screens/TeethScreen.ts|07-style-reference-a.png [1246, 329, 1603, 779]|[357, 450]|不可用，需要补生成|[600, 580]|[1216, 1176]|张嘴造型参考07，闭嘴未覆盖。张嘴本体不得烘焙牙齿；牙齿须独立叠加。|
|2|croc_closed.png / 闭嘴鳄鱼|screens/TeethScreen.ts|07-style-reference-a.png [1246, 329, 1603, 779]|[357, 450]|不可用，需要补生成|[600, 580]|[1216, 1176]|张嘴造型参考07，闭嘴未覆盖。张嘴本体不得烘焙牙齿；牙齿须独立叠加。|
|2|tooth_upper_1_normal.png / 上排第1颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_1_pressed.png / 上排第1颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_2_normal.png / 上排第2颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_2_pressed.png / 上排第2颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_3_normal.png / 上排第3颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_3_pressed.png / 上排第3颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_4_normal.png / 上排第4颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_4_pressed.png / 上排第4颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_5_normal.png / 上排第5颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_5_pressed.png / 上排第5颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_6_normal.png / 上排第6颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_6_pressed.png / 上排第6颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_7_normal.png / 上排第7颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_7_pressed.png / 上排第7颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_8_normal.png / 上排第8颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_upper_8_pressed.png / 上排第8颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1305, 455, 1559, 516]|[254, 61]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_1_normal.png / 下排第1颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_1_pressed.png / 下排第1颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_2_normal.png / 下排第2颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_2_pressed.png / 下排第2颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_3_normal.png / 下排第3颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_3_pressed.png / 下排第3颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_4_normal.png / 下排第4颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_4_pressed.png / 下排第4颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_5_normal.png / 下排第5颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_5_pressed.png / 下排第5颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_6_normal.png / 下排第6颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_6_pressed.png / 下排第6颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_7_normal.png / 下排第7颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_7_pressed.png / 下排第7颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_8_normal.png / 下排第8颗牙正常|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|tooth_lower_8_pressed.png / 下排第8颗牙按下|screens/TeethScreen.ts|07-style-reference-a.png [1276, 628, 1593, 714]|[317, 86]|不可用，需要补生成|[46, 62]|[108, 140]|该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。|
|2|state_turn_highlight.png / 当前回合高亮框标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|01-board.png [60, 97, 265, 187]|[205, 90]|不可用，需要补生成|[155, 69]|[326, 154]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|state_suspect.png / 疑似断线标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|06-room-connection.png [800, 134, 853, 163]|[53, 29]|不可用，需要补生成|[86, 34]|[188, 84]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|state_offline.png / 已掉线标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|06-room-connection.png [919, 134, 974, 172]|[55, 38]|不可用，需要补生成|[92, 46]|[200, 108]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|state_hosted.png / 托管标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|06-room-connection.png [571, 204, 624, 227]|[53, 23]|不可用，需要补生成|[86, 34]|[188, 84]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|state_away.png / 暂离标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[86, 34]|[188, 84]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|state_bankrupt.png / 破产标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|03-profile-result.png [827, 277, 900, 334]|[73, 57]|不可用，需要补生成|[86, 34]|[188, 84]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|state_countdown_ring.png / 倒计时环标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|04-property-popups.png [327, 275, 350, 302]|[23, 27]|不可用，需要补生成|[52, 52]|[120, 120]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|state_resync_spinner.png / 重连同步环标记|screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts|06-room-connection.png [1203, 458, 1276, 532]|[73, 74]|不可用，需要补生成|[96, 96]|[208, 208]|无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。|
|2|decor_ribbon.png / 结算缎带|screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts|03-profile-result.png [468, 224, 751, 313]|[283, 89]|不可用，需要补生成|[420, 132]|[856, 280]|标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。|
|2|decor_trophy.png / 奖杯|screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts|03-profile-result.png [526, 150, 693, 236]|[167, 86]|不可用，需要补生成|[220, 172]|[456, 360]|标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。|
|2|decor_coin_pile.png / 金币堆|screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts|未出现，需要补设计（同风格候选）|无|不可用，需要补生成|[160, 112]|[336, 240]|标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。|
|2|decor_property_buy.png / 买地房屋插画|screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts|04-property-popups.png [96, 331, 352, 473]|[256, 142]|不可用，需要补生成|[440, 264]|[896, 544]|标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。|
|2|decor_property_upgrade.png / 升级房屋插画|screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts|04-property-popups.png [539, 327, 778, 476]|[239, 149]|不可用，需要补生成|[440, 264]|[896, 544]|标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。|
|2|decor_laurel.png / 排名月桂|screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts|03-profile-result.png [513, 370, 553, 419]|[40, 49]|不可用，需要补生成|[64, 96]|[144, 208]|标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。|
|2|decor_logo.png / 好友桌游标题|screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts|07-style-reference-a.png [43, 128, 368, 242]|[325, 114]|不可用，需要补生成|[500, 168]|[1016, 352]|标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。|
|2|event_card_back.png / 待定事件卡背|events|10-event-card-preview.png [361, 619, 580, 938]|[219, 319]|勉强，放大会糊|[168, 244]|[352, 504]|待决定候选，不替代01棋盘。|
|2|event_card_stack.png / 待定事件牌堆|events|11-event-idle-preview.png [145, 444, 290, 588]|[145, 144]|勉强，放大会糊|[112, 112]|[240, 240]|待决定候选，不替代01棋盘。|

## 来源固定快照

|图片|尺寸|SHA256|
|---|---|---|
|01-board.png|(941, 1672)|2143548676959e60f4fbbd98ee8ca5ffd449779ec71b143e3824016fb1c95eea|
|02-motion-storyboard.png|(1536, 1024)|12313828f2a5b24d0eb74d93aa3c0d7fa84f5d08beaf4ce2860e9c149c09b6ba|
|03-profile-result.png|(1220, 1289)|5ca1f315584f96d334114a91d29ef7682cc4b4048b7395ae97972f69cd9311d0|
|04-property-popups.png|(1721, 914)|fb3f8ffc04643799448334578fff97b329c334cc8dec5575d14a835f9b18ad38|
|05-auction-debt.png|(1495, 1052)|99a180015ea8aaaa8a26ffbfc9cd441f61e3540856353a485713d94356ae44f6|
|06-room-connection.png|(1492, 1054)|131b6db9b6e789d3036d212a335866f8069d9b2ae559594bf5e14f9bd502010f|
|07-style-reference-a.png|(1625, 968)|5b3af725b161149e1459843fe1e455a310ace2cb3932300a37e4e6b5a7d61133|
|08-style-reference-b.png|(1374, 1145)|75ed82d85de7d4090c65584b9abd6aebfad4951a17c10467f8216ba1b3c34dfd|
|09-ui-states.png|(1536, 1024)|e3670265f42518e4b3a476da23d24f3300d72e3c82377bd78f13b6af079b80e4|
|10-event-card-preview.png|(941, 1672)|747a2c76ffbc6ff7b9c3c8560429f3c89fef11b77332df04fdf4674d634f3294|
|11-event-idle-preview.png|(941, 1672)|2bbcaa5ba9bdbbd4dc4be77dec640acbf8b68e6d4d20786b22c82195ad11f493|
