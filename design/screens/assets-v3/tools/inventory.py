"""Build the asset inventory before production. PIL + Python standard library only."""
from pathlib import Path
import json, hashlib
from collections import Counter
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SCREENS = ROOT.parent / 'screens'
ITEMS = []

def add(key, name, cat, file, box, display, grade='不可用，需要补生成', note='', stage=1, sliced=False, margin=None, code=''):
    ITEMS.append(dict(key=key, name=name, category=cat, source=file, bbox=box,
                      native=[box[2]-box[0], box[3]-box[1]] if box else None,
                      display=list(display), target=[display[0]*2+16, display[1]*2+16],
                      extraction=grade, note=note, stage=stage, nine_slice=sliced,
                      slice_margin=margin, code=code, status='待制作'))

B='01-board.png'; M='02-motion-storyboard.png'; P='03-profile-result.png'
U='04-property-popups.png'; D='05-auction-debt.png'; R='06-room-connection.png'
S='07-style-reference-a.png'; T='09-ui-states.png'
chars=[('xiaolin','小林/糖糖'),('keke','可可'),('ajie','阿杰'),('naicha','奶茶'),('akai','阿凯'),('yuanyuan','圆圆'),('doudou','豆豆'),('maomao','毛毛')]
avatar_boxes=[(40,831,117,907),(136,832,205,907),(224,832,295,907),(319,832,387,907),(40,951,117,1031),(136,951,205,1031),(224,951,295,1031),(319,951,387,1031)]
for (key,name),box in zip(chars,avatar_boxes):
    add('avatar_'+key,name+'头像','avatars',P,list(box),(72,72),'勉强，放大会糊','资料页网格中的头像；名字单独渲染。小林与糖糖使用同一蓝衣男孩造型。',code='ui/Widgets.ts; screens/ProfileScreen.ts')

ui=[
('button_primary','黄色主按钮',U,(190,715,398,787),(280,90),True),
('button_secondary','蓝色次按钮',D,(1070,536,1415,601),(280,90),True),
('button_disabled','灰蓝禁用按钮',R,(28,950,334,1021),(280,90),True),
('button_success','绿色小按钮',P,(714,475,770,509),(96,48),True),
('button_ghost','灰蓝幽灵按钮',U,(47,718,183,787),(200,80),True),
('button_green_login','绿色登录按钮',P,(29,518,397,596),(592,92),True),
('panel_ivory','象牙色弹窗面板',U,(35,263,410,806),(640,800),True),
('panel_section','内部分区块',U,(59,522,389,595),(560,108),True),
('panel_warning','红色欠款分区',D,(549,335,951,421),(580,112),True),
('panel_input','昵称输入框',P,(32,698,386,746),(560,76),True),
('capsule_ready','绿色准备胶囊',R,(42,347,128,375),(120,38),True),
('capsule_idle','灰色未准备胶囊',R,(373,506,456,533),(120,38),True),
('title_wood','木质标题牌',T,(32,26,438,99),(340,70),True),
('player_bar','玩家状态条底',B,(271,97,474,187),(155,69),True),
('asset_bar','底部资产条底',B,(25,1575,911,1672),(696,72),True),
('turn_pill','中央回合药丸',B,(328,647,613,760),(218,86),True),
('bubble_me','我气泡',T,(272,766,341,826),(72,50),False),
('bubble_name','我与昵称气泡',B,(250,1205,362,1264),(106,46),True),
('card_base','统一道具卡底',T,(56,431,171,585),(120,144),True),
('card_base_green','绿色道具卡底',T,(173,431,281,585),(120,144),True),
('card_base_yellow','黄色道具卡底',T,(284,431,397,585),(120,144),True),
('card_base_pink','粉色道具卡底',T,(399,431,510,585),(120,144),True),
('card_base_purple','紫色道具卡底',T,(512,431,625,585),(120,144),True),
('badge_count','数量角标底',T,(1438,410,1483,456),(30,30),False),
('scroll_track','手牌滑动轨道',T,(153,600,491,617),(400,12),True),
('scroll_thumb','手牌滑动滑块',T,(182,600,221,617),(48,12),True),
('selection_frame','房间选项选中框',R,(253,569,355,612),(130,54),True),
('hand_tray','道具栏托盘底',T,(33,409,639,638),(640,160),True),
('icon_circle','圆形图标按钮底',B,(690,1579,778,1664),(68,68),False),
('asset_button','我的资产胶囊按钮底',B,(430,1593,638,1654),(168,60),True),
]
for key,name,file,box,display,sl in ui:
    add(key,name,'ui',file,list(box),display,note='去除原图文字与叠加图标，仅生成可复用底板。' if key!='selection_frame' else '选项文字、勾选图标独立叠加。',sliced=sl,margin=[32,32,32,32] if sl else None,code='ui/Buttons.ts; ui/Widgets.ts; ui/Popup.ts; screens/board/*')
icons=[
('back','返回',B,(144,30,165,64)),('close','关闭',None,None),
('coin','金币',U,(201,541,235,577)),('mic','麦克风',B,(716,1592,752,1655)),
('chat','聊天',B,(834,1593,885,1645)),('share','分享',R,(345,149,372,174)),
('copy','复制',R,(260,155,283,180)),('settings','设置',S,(269,58,293,81)),
('sound','声音',S,(227,58,247,80)),('clock','本局时钟',B,(470,31,500,62)),
('stopwatch','回合闹钟',B,(370,658,420,708)),('check','勾选',R,(45,350,66,371)),
('chevron','右箭头',B,(587,1609,602,1635)),('plus','加号',D,(400,580,424,605)),
('minus','减号',D,(89,588,112,597)),('signal','语音信号',R,(57,913,88,936)),
('home','房屋首页',S,(99,651,136,693)),('wechat','微信双气泡',P,(114,538,170,581))]
for key,name,file,box in icons:
    add('icon_'+key,name+'图标','icons',file,list(box) if box else None,(36,36),'勉强，放大会糊' if box else '不可用，需要补生成','微信图标仅作本地视觉候选，微信胶囊本身由平台提供。' if key=='wechat' else '主体独立、无文字；未出现的关闭图标参照返回图标的线宽。',code='ui/Icons.ts; ui/Keypad.ts; ui/Buttons.ts')

cards=[('roadblock','路障',T,(529,457,610,530)),('free_rent','免租',T,(80,443,152,535)),
('build','建造',T,(186,450,269,532)),('downgrade','降级',None,None),
('fixed_move','定点移动',T,(427,449,485,536)),('jail_release','出狱',T,(1179,455,1254,534)),
('auction','拍卖',T,(299,450,384,537)),('trade','交易',None,None),
('refuse_purchase','拒绝购买',None,None),('house_protection','房屋保护',None,None),
('query','查询',None,None),('forced_purchase','强制购房',None,None),('demolish','拆楼',None,None),('clear_land','清地',None,None)]
for key,name,file,box in cards:
    add('card_'+key,name+'道具图标','cards',file,list(box) if box else None,(72,84),'勉强，放大会糊' if box else '不可用，需要补生成','不包含卡底与文字。'+('设计未覆盖；符号语义取自当前 Icons.ts，造型配色取自 screens。' if not box else '忠实参考 09 道具卡。'),code='ui/Icons.ts; core/Models.ts; screens/board/HandBar.ts')

for key,name,box,display in [('tile_horizontal','横向地块底',(399,1331,468,1436),(54,72)),('tile_vertical','竖向地块底',(32,554,111,625),(64,56)),('tile_corner','转角地块底',(9,1334,87,1444),(66,80))]:
    add(key,name,'tiles',B,list(box),display,note='无地名无图标。各方向保持正面阅读，不旋转文字。',sliced=True,margin=[18,18,18,24],code='screens/board/BoardView.ts')
tileicons=[('start','起点绿旗',(42,1350,81,1402)),('event','事件问号',(272,346,314,389)),('bank','银行',(411,348,459,389)),('jail','监狱',(858,1350,901,1394)),('rest','休息',None),('game_zone','游乐广场摩天轮',(835,345,894,391)),('station','车站',(564,347,596,386)),('park','公园',(54,345,106,388)),('theater','剧场',(29,909,83,950)),('bridge','石桥',(858,1277,914,1310)),('boat','帆船',(854,699,909,738)),('bread','面包',(28,1054,80,1092)),('pottery','陶艺',(99,1352,137,1393)),('windmill','风车',(550,1346,594,1393)),('lumber','木匠',(619,1350,667,1396)),('flower','花卉',(20,1272,76,1310)),('sunflower','向日葵',(857,909,910,949))]
for key,name,box in tileicons:
    file=B if box else S
    box=box or (1136,473,1187,519)
    add('tile_'+key,name+'地块图标','tile_icons',file,list(box),(38,38),'勉强，放大会糊','仅图标，保留地名文字由客户端叠加；休息参考 07 咖啡杯。',code='screens/board/BoardView.ts')
for key,name,box in [('low','低档绿色',(473,414,540,434)),('mid','中档蓝色',(543,415,608,435)),('high','高档紫色',(259,416,326,435))]:
    add('property_strip_'+key,name+'地产色条','tiles',B,list(box),(54,12),'可用','色档与当前 Theme.ts 一致；顶、底、侧方向由客户端旋转色条。',sliced=True,margin=[10,6,10,6],code='core/Theme.ts; screens/board/BoardView.ts')
for level,box,file in [(0,(496,828,621,946),T),(1,(523,639,578,687),U),(2,(623,636,678,686),U),(3,(726,639,781,686),U)]:
    add('house_lv'+str(level),'房屋 '+str(level)+' 级','houses',file,list(box),(80,84),'勉强，放大会糊','0 级采用未升级小屋；等级轮廓参考 04 升级序列，但小图信息不足，需要补全；不凭屋顶颜色定义等级。',code='screens/board/BoardView.ts; popups/Common.ts')
add('mark_mortgage','抵押标记','tiles',D,[618,488,664,512],(36,28),note='设计图只有文字说明，没有独立标记；生成锁扣图标，押字由文本叠加。',code='screens/board/BoardView.ts')
add('mark_owner','归属标记','tiles',None,None,(18,18),note='设计未覆盖；白边圆形底，客户端以玩家色染色。',code='screens/board/BoardView.ts')
add('board_roadblock','棋盘路障','tiles',T,[529,457,610,530],(48,44),'勉强，放大会糊','参考道具路障，缩小摆在格子上。',code='screens/board/BoardView.ts')
pawn_boxes=[(1054,774,1196,975),avatar_boxes[1],(838,460,891,529),(858,257,897,311),(1126,295,1176,355),(1138,548,1184,610),avatar_boxes[6],avatar_boxes[7]]
for i,(key,name) in enumerate(chars):
    file=M if i==0 else P if i in (1,6,7) else S
    box=pawn_boxes[i]
    add('pawn_'+key,name+'棋子与底座','pawns',file,list(box),(64,110),'勉强，放大会糊' if i not in (1,6,7) else '不可用，需要补生成','参考位置'+('仅为头像，原图没有该角色完整棋子。' if i in (1,6,7) else '背景复杂，先裁参考再忠实还原。')+'统一发光蓝底座；我方通过显示尺寸 1.3 倍与独立气泡强调。',code='screens/board/BoardView.ts')
for n in range(1,7):
    box=[1329,186,1464,321] if n==4 else [95,215,203,321] if n==1 else [390,778,548,944]
    add('dice_face_'+str(n),str(n)+'点骰子','dice',M if n in (1,4) else B,box,(128,128),'勉强，放大会糊' if n in (1,4) else '不可用，需要补生成','点数指朝上的结果面；三可见面必须符合标准骰子对面和为7；2/3/5/6没有独立结果设计。',code='ui/DiceView.ts')
for n,box in enumerate([(336,147,456,275),(577,168,704,282),(1082,156,1205,279)],1):
    add('dice_roll_'+str(n),'骰子翻滚帧 '+str(n),'dice',M,list(box),(128,128),'勉强，放大会糊','仅用于翻滚表现，不决定对局结果。',code='ui/DiceView.ts')
for n,(name,box) in enumerate([('蓄力',(67,828,155,934)),('起跳',(206,801,307,918)),('腾空',(365,784,466,901)),('下落',(519,808,603,920)),('落地',(672,832,759,936)),('回弹',(822,803,939,932))],1):
    add('pawn_hop_'+str(n),name+'跳跃帧','motion',M,list(box),(92,128),'勉强，放大会糊','同一蓝衣男孩，底座另行叠加；每步约250ms，不能直接当沿路径滑行。',code='screens/board/BoardView.ts')
add('pawn_base_ring','棋子发光底座环','pawns',M,[1064,904,1201,977],(74,36),note='抽离人物后还原蓝色底盘和青色柔光。',code='screens/board/BoardView.ts')

# Phase 2 only begins after the core set has been checked and recorded.
for key,name,box,disp in [('town_tree','树',(161,486,293,623),(112,144)),('town_bridge','石桥',(512,526,714,630),(240,144)),('town_duck','鸭子',(563,608,600,643),(42,42)),('town_house','小镇房屋',(663,923,816,1104),(164,204)),('town_river','河湾河流',(439,551,833,1210),(350,580)),('town_boat','木船',(598,1134,704,1196),(112,64)),('town_lamp','街灯',(578,874,617,1008),(40,110)),('board_center','整块棋盘中央背景',(119,438,818,1328),(536,682))]:
    add(key,name,'town',B,list(box),disp,note='原图含背景融合或骰子/按钮遮挡，需补生成；中央整图不含棋盘环、角色、骰子及UI。',stage=2,code='ui/Backdrop.ts; screens/board/BoardView.ts')
for key,name,file,box in [('bg_lobby','大厅',S,(7,34,401,963)),('bg_room','好友房间',R,(13,40,492,1043)),('bg_profile','登录资料',P,(10,69,410,1254)),('bg_result','结算',P,(416,69,803,1254)),('bg_board','对局',B,(0,0,941,1672)),('bg_teeth','小游戏',S,(1223,34,1620,963))]:
    add(key,name+'背景','backgrounds',file,list(box),(720,1280),note='整页被 UI 和人物遮挡；仅作构图参考，重新生成完整背景。背景本身不透明，PNG画布外保留8px透明边。',stage=2,code='ui/Backdrop.ts; screens/*')
for key,name,box in [('croc_open','张嘴鳄鱼',(1246,329,1603,779)),('croc_closed','闭嘴鳄鱼',(1246,329,1603,779))]:
    add(key,name,'teeth',S,list(box),(600,580),note='张嘴造型参考07，闭嘴未覆盖。张嘴本体不得烘焙牙齿；牙齿须独立叠加。',stage=2,code='screens/TeethScreen.ts')
for row in ('upper','lower'):
    for i in range(1,9):
        for state in ('normal','pressed'):
            add(f'tooth_{row}_{i}_{state}',('上' if row=='upper' else '下')+f'排第{i}颗牙'+('正常' if state=='normal' else '按下'),'teeth',S,[1305,455,1559,516] if row=='upper' else [1276,628,1593,714],(46,62),note='该位置为整排参考，不是单牙精确轮廓。8颗需逐颗登记；按下态未设计，先提供候选。危险牙外观不得有区别。',stage=2,code='screens/TeethScreen.ts')
states=[('turn_highlight','当前回合高亮框',B,(60,97,265,187),(155,69)),('suspect','疑似断线',R,(800,134,853,163),(86,34)),('offline','已掉线',R,(919,134,974,172),(92,46)),('hosted','托管',R,(571,204,624,227),(86,34)),('away','暂离',None,None,(86,34)),('bankrupt','破产',P,(827,277,900,334),(86,34)),('countdown_ring','倒计时环',U,(327,275,350,302),(52,52)),('resync_spinner','重连同步环',R,(1203,458,1276,532),(96,96))]
for key,name,file,box,disp in states:
    add('state_'+key,name+'标记','states',file,list(box) if box else None,disp,note='无文字底；文字与倒计时数字由客户端渲染。框与圆环中心透明。暂离没有页面设计。',stage=2,sliced=key=='turn_highlight',margin=[24,24,24,24] if key=='turn_highlight' else None,code='screens/board/PlayerBar.ts; ui/Widgets.ts; popups/ResyncPopup.ts')
decor=[('ribbon','结算缎带',P,(468,224,751,313),(420,132)),('trophy','奖杯',P,(526,150,693,236),(220,172)),('coin_pile','金币堆',None,None,(160,112)),('property_buy','买地房屋插画',U,(96,331,352,473),(440,264)),('property_upgrade','升级房屋插画',U,(539,327,778,476),(440,264)),('laurel','排名月桂',P,(513,370,553,419),(64,96)),('logo','好友桌游标题',S,(43,128,368,242),(500,168))]
for key,name,file,box,disp in decor:
    add('decor_'+key,name,'decor',file,list(box) if box else None,disp,note='标题牌和缎带无文字版；logo保留好友桌游四字。金币堆未出现。',stage=2,code='screens/LobbyScreen.ts; screens/ResultScreen.ts; popups/Common.ts')
add('event_card_back','待定事件卡背','events','10-event-card-preview.png',[361,619,580,938],(168,244),'勉强，放大会糊','待决定候选，不替代01棋盘。',stage=2)
add('event_card_stack','待定事件牌堆','events','11-event-idle-preview.png',[145,444,290,588],(112,112),'勉强，放大会糊','待决定候选，不替代01棋盘。',stage=2)

def main():
    for directory in ('raw','png','tools','previews','references','comparisons','reports'):
        (ROOT/directory).mkdir(parents=True,exist_ok=True)
    dims={p.name:Image.open(p).size for p in SCREENS.glob('*.png')}
    for item in ITEMS:
        if item['bbox']:
            x0,y0,x1,y1=item['bbox']; w,h=dims[item['source']]
            assert 0<=x0<x1<=w and 0<=y0<y1<=h, item
    (ROOT/'inventory.json').write_text(json.dumps(ITEMS,ensure_ascii=False,indent=2),encoding='utf-8')
    stats=Counter(i['extraction'] for i in ITEMS)
    lines=['# 单件美术素材盘点（制作前）','', '唯一视觉依据：design/screens/；未读取或沿用 design/ui、assets、assets-v2。',
           'README 已读取；01 为棋盘布局依据，07 为风格参考，08 仅记录候选存在，不采用其造型；10/11 为待决定事件候选。',
           '720×1280 为客户端设计坐标。包围盒采用原图像素 (left, top, right, bottom)，右/下不含。原生尺寸为参考区域尺寸；被遮挡的参考区域不能当作可直接使用的完整素材。',
           '输出目标为主体至少 2×建议显示尺寸，并额外留 8px 透明边。放大裁图不会增加原始细节，必须诚实区分。',
           '缺失设计项写明“未出现”；其语义来自代码，仅风格来自 screens。文字、金额、昵称、数量均由 Cocos 文本渲染，避免烘焙。',
           '8角色：小林/糖糖、可可、阿杰、奶茶、阿凯、圆圆、豆豆、毛毛；小林/糖糖为同一蓝衣男孩的页面命名差异。',
           '代码核对覆盖 screens、screens/board、popups、ui 所有 .ts。代码仍是 Graphics 占位，本交付仅素材，不修改或集成客户端。',
           '新增页面复用 inventory.json 坐标、tools 脚本及 prompts.jsonl；保留原始图和 SHA256。', '',
           f'共 {len(ITEMS)} 个逐件条目。分类：'+ '；'.join(f'{k} {v}' for k,v in stats.items())+'。',
           f'第一阶段核心集 {sum(i["stage"]==1 for i in ITEMS)} 件；第二阶段 {sum(i["stage"]==2 for i in ITEMS)} 件（32个独立牙齿状态计入）。','',
           '## 页面与代码覆盖', '',
           '- screens：资料/大厅/房间/棋盘/小游戏/结算/观战。',
           '- popups：买地/升级/租金/交易/拍卖/欠款两段/重连；通用资产/地块详情/聊天/确认/弃牌/历史/加入房间复用面板与UI套件。后者没有专属整页设计。',
           '- ui：Buttons/Widgets/Icons/DiceView/Backdrop/EditField/Keypad/ScrollList/HScroll/Toast 等复用无文字底板、图标与动态文本。',
           '- 02 动作：骰子翻滚3帧、结果面6张、蓝衣男孩跳跃6帧；其余7人完整跳跃分镜缺失，静态棋子不等于动作动画。',
           '- 09：倒计时状态用运行时颜色/透明度，5项手牌横滑用统一卡底与滑块；土地名使用文字。',
           '', '## 逐件清单','',
           '|阶段|文件名/名称|用途/代码|设计图与像素包围盒|原生像素|能否直接抠取|720显示尺寸|建议输出尺寸|备注|',
           '|---|---|---|---|---|---|---|---|---|']
    for i in ITEMS:
        ref=i['source']+' '+str(i['bbox']) if i['bbox'] else '未出现，需要补设计（同风格候选）'
        lines.append('|'+ '|'.join(str(x).replace('|','/') for x in [i['stage'],i['key']+'.png / '+i['name'],i['code'] or i['category'],ref,i['native'] or '无',i['extraction'],i['display'],i['target'],i['note']])+'|')
    lines+=['','## 来源固定快照','', '|图片|尺寸|SHA256|','|---|---|---|']
    for p in sorted(SCREENS.glob('*.png')):
        lines.append(f'|{p.name}|{dims[p.name]}|{hashlib.sha256(p.read_bytes()).hexdigest()}|')
    (ROOT/'INVENTORY.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps({'total':len(ITEMS),'grades':stats,'stages':Counter(i['stage'] for i in ITEMS)},ensure_ascii=False))

if __name__=='__main__': main()
