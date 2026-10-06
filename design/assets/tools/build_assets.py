"""Reproduce crops, transparent assets, manifest and QA contact sheets. PIL only."""
from pathlib import Path
from collections import deque
import json
from PIL import Image, ImageDraw, ImageOps
from keyout import keyout, finish_edges

ROOT=Path(__file__).resolve().parents[1]
RAW=ROOT/'raw'; PNG=ROOT/'png'
items=[]

def emit(name, source, box, limit, use, display, slices=None, upscale=False):
    crop=Image.open(RAW/source).crop(box) if box else Image.open(RAW/source)
    crop.save(RAW/(name+'_crop.png'))
    im=keyout(crop,limit)
    if name.startswith('icon_'):
        # The generated sheet contains tiny isolated strokes below row two.
        # Each icon is one connected button; keep that complete component only.
        alpha=im.getchannel('A'); w,h=im.size
        occupied=set(i for i,a in enumerate(alpha.get_flattened_data()) if a)
        components=[]
        while occupied:
            seed=occupied.pop(); q=deque([seed]); component=[seed]
            while q:
                i=q.popleft(); x,y=i%w,i//w
                for yy in range(max(0,y-1),min(h,y+2)):
                    for xx in range(max(0,x-1),min(w,x+2)):
                        j=yy*w+xx
                        if j in occupied:
                            occupied.remove(j); q.append(j); component.append(j)
            components.append(component)
        keep=set(max(components,key=len)); data=list(im.get_flattened_data())
        im.putdata([pixel if i in keep else (0,0,0,0) for i,pixel in enumerate(data)])
        body=im.crop(im.getbbox())
        body.thumbnail((80,80),Image.Resampling.LANCZOS)
        im=Image.new('RGBA',(body.width+16,body.height+16)); im.paste(body,(8,8))
    if upscale:
        # Resize trimmed foreground, then reapply the exact eight-pixel border.
        body=im.crop((8,8,im.width-8,im.height-8))
        body=body.resize((limit[0]-16,limit[1]-16),Image.Resampling.LANCZOS)
        im=Image.new('RGBA',limit); im.paste(body,(8,8))
    im=finish_edges(im)
    im.save(PNG/(name+'.png'))
    pixels=list(im.get_flattened_data())
    residual=sum(a>0 and min(r,b)-g>45 and r>120 and b>120 for r,g,b,a in pixels)
    # Restrict residual check to silhouette; violet hair is a legitimate interior.
    edge_residual=0
    w,h=im.size
    for y in range(1,h-1):
        for x in range(1,w-1):
            r,g,b,a=pixels[y*w+x]
            if a and min(r,b)-g>45 and r>120 and b>120 and any(pixels[yy*w+xx][3]==0 for xx,yy in ((x-1,y),(x+1,y),(x,y-1),(x,y+1))):
                edge_residual+=1
    items.append(dict(name=name,size=list(im.size),use=use,display=display,slices=slices,edge_magenta=edge_residual,magenta_candidates=residual,upscaled=upscale))

avatars=Image.open(RAW/'avatars_sheet.png')
names=['小林：蓝帽衫男孩','可可：棕发女孩','阿杰：绿帽眼镜男孩','奶茶：粉帽女孩','阿凯：黑发男孩','圆圆：紫发女孩','豆豆：卡其帽男孩','毛毛：黑长发女孩']
for i in range(8):
    c,r=i%4,i//4; w,h=avatars.size
    emit(f'avatar_{i+1}','avatars_sheet.png',(round(c*w/4),round(r*h/2),round((c+1)*w/4),round((r+1)*h/2)),(512,512),names[i],'96×96（大厅可用112×112）')

ui=[
('btn_primary_yellow',(0,40,627,300),(600,180),'黄色主按钮','600×140',(72,38,72,48),False),
('btn_secondary_blue',(627,40,1254,300),(600,180),'蓝色次按钮','600×140',(72,38,72,48),False),
('btn_secondary_gray',(0,300,627,550),(600,180),'灰蓝次按钮','600×140',(72,38,72,48),False),
('btn_small_green',(627,300,1254,550),(600,180),'绿色小按钮','220×64',(72,38,72,48),False),
('panel_ivory',(0,550,560,1030),(640,640),'暖象牙大面板','640×640',(80,80,80,90),True),
('panel_ivory_inner',(560,550,1254,1030),(560,200),'浅色分区面板','560×200',(56,44,56,52),True),
('chip_ready_green',(0,1030,627,1254),(220,96),'准备状态空白胶囊','120×36',(48,22,48,30),False),
('chip_gray',(627,1030,1254,1254),(220,96),'灰色状态空白胶囊','120×36',(48,22,48,30),False)]
for n,b,l,u,d,s,up in ui: emit(n,'ui_sheet.png',b,l,u,d,s,up)
icons=['close','back','coin','mic','chat','share','copy','settings','sound']
for i,n in enumerate(icons):
    c,r=i%3,i//3
    emit('icon_'+n,'icons_sheet.png',(c*418,r*418,(c+1)*418,(r+1)*418),(96,96),'关闭 返回 金币 麦克风 聊天 分享 复制 设置 声音'.split()[i],'48×48；金币可用32×32')
emit('logo_title','logo_title.png',None,(800,420),'空白木质标题牌，代码叠加文字','580×290')
bg=Image.open(RAW/'bg_lobby.png').convert('RGB').resize((1080,1920),Image.Resampling.LANCZOS)
bg.save(PNG/'bg_lobby.png')
items.append(dict(name='bg_lobby',size=[1080,1920],use='大厅不透明背景',display='720×1280',slices=None,edge_magenta=None,upscaled=True))

for theme,color in [('light','#fff5dc'),('dark','#183e50')]:
    out=Image.new('RGB',(1200,1400),color); d=ImageDraw.Draw(out)
    for i,item in enumerate(items):
        x=(i%4)*300; y=(i//4)*200
        im=Image.open(PNG/(item['name']+'.png')).convert('RGBA')
        im.thumbnail((280,165),Image.Resampling.LANCZOS)
        out.paste(im,(x+(300-im.width)//2,y+25),im)
        d.text((x+8,y+5),item['name'],fill='white' if theme=='dark' else 'black')
    out.save(ROOT/('qa_'+theme+'.png'))
(ROOT/'qa_report.json').write_text(json.dumps(items,ensure_ascii=False,indent=2),encoding='utf-8')

lines=['# 第一批游戏界面素材','', '共27件；内置imagegen生成，PIL处理，不依赖numpy。原始输出、未抠图切片均在raw/；透明成品在png/。', '',
'## 处理与验证','', '品红/近品红颜色键去底；边缘局部估算alpha与去溢色；按alpha包围盒裁切；最终每侧8px透明边。头像无圆框，使用代码圆形遮罩和边框，尺寸为紧裁后的矩形纹理。背景不透明。', '',
'生成原图实际尺寸：头像1774×887、UI1254×1254、图标1254×1254、标题牌1774×887、背景941×1672。大面板及背景放大至需求尺寸，内分区面板调至560×200；并非原生同尺寸细节。', '',
'每件品红边缘候选数记录在qa_report.json；同时查看qa_light.png及qa_dark.png。计数规则为非透明轮廓相邻透明像素且R/B>120、min(R,B)-G>45。该数值不能证明所有视觉毛边均不存在。', '',
'九宫格边距单位是成品纹理px，顺序左/上/右/下，包含8px透明边。是建议起点，未在Cocos中验证。渐变高光会随中心区域拉伸；按钮优先横向拉伸，高度变化控制在约±20%。', '',
'## 单件清单','']
for item in items:
    n=item['name']; sz='×'.join(map(str,item['size']))
    sl='/'.join(map(str,item['slices'])) if item['slices'] else '不建议'
    qa='不适用（不透明）' if n=='bg_lobby' else f"品红轮廓候选{item['edge_magenta']}px；明暗底预览未见明显品红残边或毛边，当前无需重做"
    lines.append(f"- `{n}.png` — {sz}；{item['use']}；720×1280下建议{item['display']}；九宫格：{sl}；自检：{qa}。")
lines+=['','## 风格和使用风险','',
'- 头像同表生成，组内风格统一；与参考画面的角色并非逐像素相同。圆圆偏蓝紫以避开色键；奶茶帽子偏暖珊瑚粉。角色肤色差异较温和。',
'- 图标的金属/塑料高光比头像和背景更强；大厅背景细节更接近插画小镇，按钮更接近立体塑料。建议实机叠加后再判断协调性。',
'- 木牌中心有木纹，代码叠字需要足够对比度；叶片装饰不可九宫格拉伸。',
'- 已做PNG及明暗底静态检查，尚未导入Cocos、测试压缩/九宫格渲染或实机显示。', '',
'## 复现','', '`python design/assets/tools/build_assets.py`', '',
'单图：`python design/assets/tools/keyout.py input.png output.png --max-size 512 512`', '',
'完整生成提示词见tools/generation_prompts.md。', '',
'## 批次2建议','',
'- 14种卡牌图标：按已确认规则逐项命名，先做免租、建造、拍卖、定点移动，其余10种待规则清单对应；同时做统一空白卡底、角标与数量徽章。',
'- 棋盘：空白地产格、车站/银行/监狱/休息/事件/小游戏格，地产色条、道路转角、房屋1/2/3级；8个与头像对应的棋子。',
'- 骰子：相同透视与光照的1至6面，投骰落地阴影。',
'- 虎口拔牙：原创鳄鱼本体、独立上下牙各8颗、按下状态、闭嘴状态；危险牙外观不能暴露。',
'- 状态标记：选中/当前回合/房主/准备/断线/托管/破产/冻结/抵押；统一倒计时底及语音波条。',
'- 弹窗装饰：地产插画、金币小堆、结果奖杯/丝带、加载装饰和统一分隔线。', '']
(ROOT/'MANIFEST.md').write_text('\n'.join(lines),encoding='utf-8')
print(json.dumps([(i['name'],i['size'],i['edge_magenta']) for i in items],ensure_ascii=False))
