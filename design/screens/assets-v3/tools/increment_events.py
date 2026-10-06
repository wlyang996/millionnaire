from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
path=ROOT/'inventory.json'
items=json.loads(path.read_text(encoding='utf-8'))
if not any(i['key']=='event_card_back_single' for i in items):
    edits={
        'event_card_back':('中央点击抽取大卡背',[361,619,580,938],[168,244]),
        'event_card_stack':('默认左上角三张扇形牌堆',[126,442,452,643],[250,154])}
    for i in items:
        if i['key'] in edits:
            name,bbox,display=edits[i['key']]
            i['previous_decision']={k:i[k] for k in ('name','bbox','display','stage','note')}
            i.update(name=name,bbox=bbox,native=[bbox[2]-bbox[0],bbox[3]-bbox[1]],display=display,target=[2*display[0]+16,2*display[1]+16],stage=1,note='2026-10-06 用户已决定采用：11为默认，10仅事件触发玩家私有中央直接点击；本次增量取代旧待定说明。')
    specs=[
        ('event_card_back_single','牌堆可拆单张卡背','11-event-idle-preview.png',[224,441,363,630],[106,144]),
        ('event_card_stack_small','牌堆缩小态','10-event-card-preview.png',[136,442,215,553],[60,84]),
        ('event_click_frame','可点击金色边框','10-event-card-preview.png',[355,613,588,943],[182,260]),
        ('event_click_glow','可点击金色光效','10-event-card-preview.png',[343,600,598,951],[198,268]),
        ('event_card_flip_mid','卡背翻转中间帧（可选）','10-event-card-preview.png',[361,619,580,938],[36,244]),
        ('event_stack_label','牌堆标签木牌空底','11-event-idle-preview.png',[225,629,364,670],[106,32])]
    for key,name,source,bbox,display in specs:
        items.append(dict(key=key,name=name,category='events',source=source,bbox=bbox,native=[bbox[2]-bbox[0],bbox[3]-bbox[1]],display=display,target=[2*display[0]+16,2*display[1]+16],extraction='不可用，需要补生成',note='用户事件交互决定增量；不烘焙点击提示文字。',stage=1,nine_slice=False,slice_margin=None,code='',status='待制作'))
    for kind,name in [('reward','奖励'),('fine','罚款'),('item','道具'),('move','位移'),('jail','入狱')]:
        items.append(dict(key='event_face_'+kind,name='事件卡面：'+name,category='events',source=None,bbox=None,native=None,display=[168,244],target=[352,504],extraction='待用户补设计',note='设计图未画出；禁止自行编造文案或图案。',stage=1,nine_slice=False,slice_margin=None,code='',status='待用户补设计'))
    path.write_text(json.dumps(items,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    text='''

## 2026-10-06 续做增量：已确定事件交互

本节取代上文“10/11 待决定”与旧事件条目的候选说明，其余 189 条原盘点保留。
默认采用 11-event-idle-preview.png：中央骰子保留，棋盘内部左上角三张蓝金问号卡背扇形展开。
事件触发采用 10-event-card-preview.png：仅触发玩家中央显示大卡背，直接点击抽取；无独立抽取按钮，临时卡片遮挡中央骰子。
event_card_back / event_card_stack 移入阶段一并校正牌堆范围；inventory.json 的 previous_decision 保留旧字段。
新增单卡、缩小牌堆、点击边框、点击光效、可选翻转帧、木牌空底六项；五类卡面仅占位登记，待用户补设计，不生成内容。

|条目|来源范围（原图像素）|720 显示|状态|
|---|---|---|---|
|event_card_back|10 [361,619,580,938]|168×244|阶段一制作|
|event_card_stack|11 [126,442,452,643]|250×154|阶段一制作；三张扇形，不含木牌文字|
|event_card_back_single|11 [224,441,363,630]|106×144|阶段一制作；可用于客户端分层组合|
|event_card_stack_small|10 [136,442,215,553]|60×84|阶段一制作；缩小扇形态与10单卡小标识有差异|
|event_click_frame / event_click_glow|10 [343,600,598,951]|182×260 / 198×268|阶段一制作；单独透明特效|
|event_card_flip_mid|10 [361,619,580,938]|36×244|可选；卡背水平压缩示意，不代表完整3D翻转|
|event_stack_label|11 [225,629,364,670]|106×32|木牌空底；“事件卡”由客户端文字叠加|
|event_face_reward / fine / item / move / jail|设计未提供|168×244|待用户补设计；不编造文案与图案|

输出目录统一为 design/screens/assets-v3/；原文件路径与脚本旧路径说明仅为历史记录。续做脚本以自身位置解析目录，不运行会覆盖原盤点的 inventory.py。
'''
    with (ROOT/'INVENTORY.md').open('a',encoding='utf-8') as f:f.write(text)
    print('Added event decision and 11 inventory records; original inventory preserved.')
else:print('Event increment already present; no changes.')
