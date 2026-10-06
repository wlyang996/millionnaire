# assets-v3 单件素材交付清单

本次已输出 40 件：{'可用': 18, '待确认': 22}。阶段一 40 件，阶段二 0 件。

状态说明：可用指本地技术与视觉自检通过；待确认指造型、等级或新补图案需用户评审；均未接入游戏、未自动标记为用户确认。

透明 PNG 保留 8px 空边；raw 原图保留。尺寸含空边。九宫格边距顺序 L/T/R/B，单位为输出像素；仅建议，未在 Cocos 验证。

## 完成度与限制

- 阶段一登记目标 137 件；已产出 40 件。
- 阶段二登记目标 63 件；已产出 0 件。
- 事件卡正面五类待补设计；不生成内容。
- 自动检查包含 RGBA、8px 透明边、品红候选计数、2x 显示像素及哈希。几何保真和动画连贯性仍需人工评审。
- previews/ 含浅、深、彩色底；comparisons/ 每类含原设计区域和单件素材及差异说明。

## 单件登记

|文件|尺寸|来源与位置|720显示尺寸|九宫格 L/T/R/B|边缘品红/全图品红|状态与差异|
|---|---|---|---|---|---|---|
|[button_primary](./png/button_primary.png)|[709, 213]|还原；04-property-popups.png [190, 715, 398, 787]；raw/buttons_sheet.png|[280, 90]|[32, 32, 32, 32]|0/0|可用；无字底板还原；光泽较参考更强，保留独立文字区域。|
|[button_secondary](./png/button_secondary.png)|[707, 213]|还原；05-auction-debt.png [1070, 536, 1415, 601]；raw/buttons_sheet.png|[280, 90]|[32, 32, 32, 32]|0/0|可用；无字底板还原；光泽较参考更强，保留独立文字区域。|
|[button_disabled](./png/button_disabled.png)|[770, 214]|还原；06-room-connection.png [28, 950, 334, 1021]；raw/buttons_sheet.png|[280, 90]|[32, 32, 32, 32]|0/0|可用；无字底板还原；光泽较参考更强，保留独立文字区域。|
|[button_success](./png/button_success.png)|[476, 214]|还原；03-profile-result.png [714, 475, 770, 509]；raw/buttons_sheet.png|[96, 48]|[32, 32, 32, 32]|0/0|可用；无字底板还原；光泽较参考更强，保留独立文字区域。|
|[button_ghost](./png/button_ghost.png)|[673, 209]|还原；04-property-popups.png [47, 718, 183, 787]；raw/buttons_sheet.png|[200, 80]|[32, 32, 32, 32]|0/0|可用；无字底板还原；光泽较参考更强，保留独立文字区域。|
|[button_green_login](./png/button_green_login.png)|[768, 209]|还原；03-profile-result.png [29, 518, 397, 596]；raw/buttons_sheet.png|[592, 92]|[32, 32, 32, 32]|0/0|可用；无字底板还原；光泽较参考更强，保留独立文字区域。|
|[panel_ivory](./png/panel_ivory.png)|[344, 439]|还原；04-property-popups.png [35, 263, 410, 806]；raw/surfaces_sheet.png|[640, 800]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[panel_section](./png/panel_section.png)|[544, 127]|还原；04-property-popups.png [59, 522, 389, 595]；raw/surfaces_sheet.png|[560, 108]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[panel_warning](./png/panel_warning.png)|[523, 126]|还原；05-auction-debt.png [549, 335, 951, 421]；raw/surfaces_sheet.png|[580, 112]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[panel_input](./png/panel_input.png)|[446, 114]|还原；03-profile-result.png [32, 698, 386, 746]；raw/surfaces_sheet.png|[560, 76]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[capsule_ready](./png/capsule_ready.png)|[291, 131]|还原；06-room-connection.png [42, 347, 128, 375]；raw/surfaces_sheet.png|[120, 38]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[capsule_idle](./png/capsule_idle.png)|[286, 130]|还原；06-room-connection.png [373, 506, 456, 533]；raw/surfaces_sheet.png|[120, 38]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[title_wood](./png/title_wood.png)|[406, 130]|还原；09-ui-states.png [32, 26, 438, 99]；raw/surfaces_sheet.png|[340, 70]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[player_bar](./png/player_bar.png)|[401, 157]|还原；01-board.png [271, 97, 474, 187]；raw/surfaces_sheet.png|[155, 69]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[asset_bar](./png/asset_bar.png)|[636, 135]|还原；01-board.png [25, 1575, 911, 1672]；raw/surfaces_sheet.png|[696, 72]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[turn_pill](./png/turn_pill.png)|[535, 176]|还原；01-board.png [328, 647, 613, 760]；raw/surfaces_sheet.png|[218, 86]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[bubble_me](./png/bubble_me.png)|[258, 254]|还原；09-ui-states.png [272, 766, 341, 826]；raw/surfaces_sheet.png|[72, 50]|否|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[bubble_name](./png/bubble_name.png)|[635, 212]|还原；01-board.png [250, 1205, 362, 1264]；raw/surfaces_sheet.png|[106, 46]|[32, 32, 32, 32]|0/0|可用；无字无图标还原；边框与光泽略加强，面板大尺寸用九宫格延展中心。|
|[avatar_xiaolin](./png/avatar_xiaolin.png)|[423, 419]|还原；03-profile-result.png [40, 831, 117, 907]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[avatar_keke](./png/avatar_keke.png)|[425, 418]|还原；03-profile-result.png [136, 832, 205, 907]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[avatar_ajie](./png/avatar_ajie.png)|[425, 420]|还原；03-profile-result.png [224, 832, 295, 907]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[avatar_naicha](./png/avatar_naicha.png)|[424, 420]|还原；03-profile-result.png [319, 832, 387, 907]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[avatar_akai](./png/avatar_akai.png)|[422, 416]|还原；03-profile-result.png [40, 951, 117, 1031]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[avatar_yuanyuan](./png/avatar_yuanyuan.png)|[424, 417]|还原；03-profile-result.png [136, 951, 205, 1031]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[avatar_doudou](./png/avatar_doudou.png)|[425, 417]|还原；03-profile-result.png [224, 951, 295, 1031]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[avatar_maomao](./png/avatar_maomao.png)|[424, 417]|还原；03-profile-result.png [319, 951, 387, 1031]；raw/avatars_sheet.png|[72, 72]|否|0/0|待确认；沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。|
|[card_roadblock](./png/card_roadblock.png)|[271, 247]|还原；09-ui-states.png [529, 457, 610, 530]；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_free_rent](./png/card_free_rent.png)|[232, 257]|还原；09-ui-states.png [80, 443, 152, 535]；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_build](./png/card_build.png)|[265, 235]|还原；09-ui-states.png [186, 450, 269, 532]；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_downgrade](./png/card_downgrade.png)|[273, 241]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_fixed_move](./png/card_fixed_move.png)|[195, 245]|还原；09-ui-states.png [427, 449, 485, 536]；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_jail_release](./png/card_jail_release.png)|[245, 259]|还原；09-ui-states.png [1179, 455, 1254, 534]；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_auction](./png/card_auction.png)|[282, 258]|还原；09-ui-states.png [299, 450, 384, 537]；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_trade](./png/card_trade.png)|[230, 250]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_refuse_purchase](./png/card_refuse_purchase.png)|[281, 281]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_house_protection](./png/card_house_protection.png)|[254, 282]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_query](./png/card_query.png)|[245, 264]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_forced_purchase](./png/card_forced_purchase.png)|[298, 224]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_demolish](./png/card_demolish.png)|[259, 262]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|
|[card_clear_land](./png/card_clear_land.png)|[264, 268]|还原；设计未覆盖 ；raw/cards_sheet.png|[72, 84]|否|0/0|待确认；沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。|

## 尚未制作／待补设计

- card_base（阶段1）：统一道具卡底；待制作。
- card_base_green（阶段1）：绿色道具卡底；待制作。
- card_base_yellow（阶段1）：黄色道具卡底；待制作。
- card_base_pink（阶段1）：粉色道具卡底；待制作。
- card_base_purple（阶段1）：紫色道具卡底；待制作。
- badge_count（阶段1）：数量角标底；待制作。
- scroll_track（阶段1）：手牌滑动轨道；待制作。
- scroll_thumb（阶段1）：手牌滑动滑块；待制作。
- selection_frame（阶段1）：房间选项选中框；待制作。
- hand_tray（阶段1）：道具栏托盘底；待制作。
- icon_circle（阶段1）：圆形图标按钮底；待制作。
- asset_button（阶段1）：我的资产胶囊按钮底；待制作。
- icon_back（阶段1）：返回图标；待制作。
- icon_close（阶段1）：关闭图标；待制作。
- icon_coin（阶段1）：金币图标；待制作。
- icon_mic（阶段1）：麦克风图标；待制作。
- icon_chat（阶段1）：聊天图标；待制作。
- icon_share（阶段1）：分享图标；待制作。
- icon_copy（阶段1）：复制图标；待制作。
- icon_settings（阶段1）：设置图标；待制作。
- icon_sound（阶段1）：声音图标；待制作。
- icon_clock（阶段1）：本局时钟图标；待制作。
- icon_stopwatch（阶段1）：回合闹钟图标；待制作。
- icon_check（阶段1）：勾选图标；待制作。
- icon_chevron（阶段1）：右箭头图标；待制作。
- icon_plus（阶段1）：加号图标；待制作。
- icon_minus（阶段1）：减号图标；待制作。
- icon_signal（阶段1）：语音信号图标；待制作。
- icon_home（阶段1）：房屋首页图标；待制作。
- icon_wechat（阶段1）：微信双气泡图标；待制作。
- tile_horizontal（阶段1）：横向地块底；待制作。
- tile_vertical（阶段1）：竖向地块底；待制作。
- tile_corner（阶段1）：转角地块底；待制作。
- tile_start（阶段1）：起点绿旗地块图标；待制作。
- tile_event（阶段1）：事件问号地块图标；待制作。
- tile_bank（阶段1）：银行地块图标；待制作。
- tile_jail（阶段1）：监狱地块图标；待制作。
- tile_rest（阶段1）：休息地块图标；待制作。
- tile_game_zone（阶段1）：游乐广场摩天轮地块图标；待制作。
- tile_station（阶段1）：车站地块图标；待制作。
- tile_park（阶段1）：公园地块图标；待制作。
- tile_theater（阶段1）：剧场地块图标；待制作。
- tile_bridge（阶段1）：石桥地块图标；待制作。
- tile_boat（阶段1）：帆船地块图标；待制作。
- tile_bread（阶段1）：面包地块图标；待制作。
- tile_pottery（阶段1）：陶艺地块图标；待制作。
- tile_windmill（阶段1）：风车地块图标；待制作。
- tile_lumber（阶段1）：木匠地块图标；待制作。
- tile_flower（阶段1）：花卉地块图标；待制作。
- tile_sunflower（阶段1）：向日葵地块图标；待制作。
- property_strip_low（阶段1）：低档绿色地产色条；待制作。
- property_strip_mid（阶段1）：中档蓝色地产色条；待制作。
- property_strip_high（阶段1）：高档紫色地产色条；待制作。
- house_lv0（阶段1）：房屋 0 级；待制作。
- house_lv1（阶段1）：房屋 1 级；待制作。
- house_lv2（阶段1）：房屋 2 级；待制作。
- house_lv3（阶段1）：房屋 3 级；待制作。
- mark_mortgage（阶段1）：抵押标记；待制作。
- mark_owner（阶段1）：归属标记；待制作。
- board_roadblock（阶段1）：棋盘路障；待制作。
- pawn_xiaolin（阶段1）：小林/糖糖棋子与底座；待制作。
- pawn_keke（阶段1）：可可棋子与底座；待制作。
- pawn_ajie（阶段1）：阿杰棋子与底座；待制作。
- pawn_naicha（阶段1）：奶茶棋子与底座；待制作。
- pawn_akai（阶段1）：阿凯棋子与底座；待制作。
- pawn_yuanyuan（阶段1）：圆圆棋子与底座；待制作。
- pawn_doudou（阶段1）：豆豆棋子与底座；待制作。
- pawn_maomao（阶段1）：毛毛棋子与底座；待制作。
- dice_face_1（阶段1）：1点骰子；待制作。
- dice_face_2（阶段1）：2点骰子；待制作。
- dice_face_3（阶段1）：3点骰子；待制作。
- dice_face_4（阶段1）：4点骰子；待制作。
- dice_face_5（阶段1）：5点骰子；待制作。
- dice_face_6（阶段1）：6点骰子；待制作。
- dice_roll_1（阶段1）：骰子翻滚帧 1；待制作。
- dice_roll_2（阶段1）：骰子翻滚帧 2；待制作。
- dice_roll_3（阶段1）：骰子翻滚帧 3；待制作。
- pawn_hop_1（阶段1）：蓄力跳跃帧；待制作。
- pawn_hop_2（阶段1）：起跳跳跃帧；待制作。
- pawn_hop_3（阶段1）：腾空跳跃帧；待制作。
- pawn_hop_4（阶段1）：下落跳跃帧；待制作。
- pawn_hop_5（阶段1）：落地跳跃帧；待制作。
- pawn_hop_6（阶段1）：回弹跳跃帧；待制作。
- pawn_base_ring（阶段1）：棋子发光底座环；待制作。
- town_tree（阶段2）：树；待制作。
- town_bridge（阶段2）：石桥；待制作。
- town_duck（阶段2）：鸭子；待制作。
- town_house（阶段2）：小镇房屋；待制作。
- town_river（阶段2）：河湾河流；待制作。
- town_boat（阶段2）：木船；待制作。
- town_lamp（阶段2）：街灯；待制作。
- board_center（阶段2）：整块棋盘中央背景；待制作。
- bg_lobby（阶段2）：大厅背景；待制作。
- bg_room（阶段2）：好友房间背景；待制作。
- bg_profile（阶段2）：登录资料背景；待制作。
- bg_result（阶段2）：结算背景；待制作。
- bg_board（阶段2）：对局背景；待制作。
- bg_teeth（阶段2）：小游戏背景；待制作。
- croc_open（阶段2）：张嘴鳄鱼；待制作。
- croc_closed（阶段2）：闭嘴鳄鱼；待制作。
- tooth_upper_1_normal（阶段2）：上排第1颗牙正常；待制作。
- tooth_upper_1_pressed（阶段2）：上排第1颗牙按下；待制作。
- tooth_upper_2_normal（阶段2）：上排第2颗牙正常；待制作。
- tooth_upper_2_pressed（阶段2）：上排第2颗牙按下；待制作。
- tooth_upper_3_normal（阶段2）：上排第3颗牙正常；待制作。
- tooth_upper_3_pressed（阶段2）：上排第3颗牙按下；待制作。
- tooth_upper_4_normal（阶段2）：上排第4颗牙正常；待制作。
- tooth_upper_4_pressed（阶段2）：上排第4颗牙按下；待制作。
- tooth_upper_5_normal（阶段2）：上排第5颗牙正常；待制作。
- tooth_upper_5_pressed（阶段2）：上排第5颗牙按下；待制作。
- tooth_upper_6_normal（阶段2）：上排第6颗牙正常；待制作。
- tooth_upper_6_pressed（阶段2）：上排第6颗牙按下；待制作。
- tooth_upper_7_normal（阶段2）：上排第7颗牙正常；待制作。
- tooth_upper_7_pressed（阶段2）：上排第7颗牙按下；待制作。
- tooth_upper_8_normal（阶段2）：上排第8颗牙正常；待制作。
- tooth_upper_8_pressed（阶段2）：上排第8颗牙按下；待制作。
- tooth_lower_1_normal（阶段2）：下排第1颗牙正常；待制作。
- tooth_lower_1_pressed（阶段2）：下排第1颗牙按下；待制作。
- tooth_lower_2_normal（阶段2）：下排第2颗牙正常；待制作。
- tooth_lower_2_pressed（阶段2）：下排第2颗牙按下；待制作。
- tooth_lower_3_normal（阶段2）：下排第3颗牙正常；待制作。
- tooth_lower_3_pressed（阶段2）：下排第3颗牙按下；待制作。
- tooth_lower_4_normal（阶段2）：下排第4颗牙正常；待制作。
- tooth_lower_4_pressed（阶段2）：下排第4颗牙按下；待制作。
- tooth_lower_5_normal（阶段2）：下排第5颗牙正常；待制作。
- tooth_lower_5_pressed（阶段2）：下排第5颗牙按下；待制作。
- tooth_lower_6_normal（阶段2）：下排第6颗牙正常；待制作。
- tooth_lower_6_pressed（阶段2）：下排第6颗牙按下；待制作。
- tooth_lower_7_normal（阶段2）：下排第7颗牙正常；待制作。
- tooth_lower_7_pressed（阶段2）：下排第7颗牙按下；待制作。
- tooth_lower_8_normal（阶段2）：下排第8颗牙正常；待制作。
- tooth_lower_8_pressed（阶段2）：下排第8颗牙按下；待制作。
- state_turn_highlight（阶段2）：当前回合高亮框标记；待制作。
- state_suspect（阶段2）：疑似断线标记；待制作。
- state_offline（阶段2）：已掉线标记；待制作。
- state_hosted（阶段2）：托管标记；待制作。
- state_away（阶段2）：暂离标记；待制作。
- state_bankrupt（阶段2）：破产标记；待制作。
- state_countdown_ring（阶段2）：倒计时环标记；待制作。
- state_resync_spinner（阶段2）：重连同步环标记；待制作。
- decor_ribbon（阶段2）：结算缎带；待制作。
- decor_trophy（阶段2）：奖杯；待制作。
- decor_coin_pile（阶段2）：金币堆；待制作。
- decor_property_buy（阶段2）：买地房屋插画；待制作。
- decor_property_upgrade（阶段2）：升级房屋插画；待制作。
- decor_laurel（阶段2）：排名月桂；待制作。
- decor_logo（阶段2）：好友桌游标题；待制作。
- event_card_back（阶段1）：中央点击抽取大卡背；待制作。
- event_card_stack（阶段1）：默认左上角三张扇形牌堆；待制作。
- event_card_back_single（阶段1）：牌堆可拆单张卡背；待制作。
- event_card_stack_small（阶段1）：牌堆缩小态；待制作。
- event_click_frame（阶段1）：可点击金色边框；待制作。
- event_click_glow（阶段1）：可点击金色光效；待制作。
- event_card_flip_mid（阶段1）：卡背翻转中间帧（可选）；待制作。
- event_stack_label（阶段1）：牌堆标签木牌空底；待制作。
- event_face_reward（阶段1）：事件卡面：奖励；待用户补设计。
- event_face_fine（阶段1）：事件卡面：罚款；待用户补设计。
- event_face_item（阶段1）：事件卡面：道具；待用户补设计。
- event_face_move（阶段1）：事件卡面：位移；待用户补设计。
- event_face_jail（阶段1）：事件卡面：入狱；待用户补设计。
