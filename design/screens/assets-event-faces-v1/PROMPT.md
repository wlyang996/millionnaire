# 任务：补 6 张事件卡面位图（大富翁类微信小游戏）

工作目录：F:/work/millionnaire/design/screens/assets-event-faces-v1（只在这个目录里写文件）。

## 参考（必须先看）
- 现有卡面（风格、边框、尺寸以此为准）：
  - F:/work/millionnaire/design/screens/assets-supplement-v1/png/event_art/01-event_reward.png（奖励）
  - F:/work/millionnaire/design/screens/assets-supplement-v1/png/event_art/02-event_fine.png（罚款）
  - F:/work/millionnaire/design/screens/assets-supplement-v1/png/event_art/03-event_tool.png（道具）
  - F:/work/millionnaire/design/screens/assets-supplement-v1/png/event_art/04-event_move.png（位移）
  - F:/work/millionnaire/design/screens/assets-supplement-v1/png/event_art/05-event_jail.png（入狱）
- 设计稿：F:/work/millionnaire/design/screens/12-事件卡-五类卡面与问号卡背.png
- 卡背改色版（幸运红 / 不幸紫的颜色参考）：F:/work/millionnaire/design/screens/assets-lucky-v1/png/lucky_card_back.png、unlucky_card_back.png

现有卡面结构：竖版圆角金色边框；顶部一条蓝色题头带（菱形暗纹，空白，运行时写类别文字）；中间奶油色区域里是暖色 3D Q 版河边小镇风格插画；底部一条空白奶油色说明带（运行时写结果文字）。原图 369×473，RGBA，边框外透明。

## 要生成的 6 张（文件名固定）
| 文件 | 题头颜色 | 插画内容 |
|---|---|---|
| event_build.png | 蓝（同现有） | 小房子 + 绿色向上箭头 + 锤子，表示“免费加盖一级” |
| event_downgrade.png | 蓝 | 有裂缝、屋顶歪斜的小房子 + 红色向下箭头，表示“房屋降一级” |
| event_station.png | 蓝 | 小镇火车站站台 + 一列可爱的小火车驶入，表示“前往车站” |
| event_start.png | 蓝 | 小镇入口的起点拱门 + 方格起点线 + 金币，表示“回到起点领奖励” |
| lucky_face.png | 红（与幸运卡背同色系） | 四叶草 + 金色星星 + 彩带，喜庆，表示“幸运” |
| unlucky_face.png | 深紫（与不幸卡背同色系） | 头顶小乌云下雨 + 掉落的香蕉皮，诙谐不吓人，表示“不幸” |

## 硬性要求
- 与参考卡面同一风格、同一边框、同一题头带和说明带位置与比例；正面、无透视倾斜。
- 不要任何文字、字母、数字、标签（题头带和说明带都留空，运行时写字）。
- 真透明背景（边框外 alpha=0），不要棋盘格、白底、水印、边框外的闪光碎片。
- 每张最终输出 369×473 RGBA PNG，存到 png/ 子目录，文件名如上表。
- 推荐做法：用内置图片生成工具先出一张 2 列 × 3 行的整图（每格一张卡，格间大透明间隔），存为 raw/atlas.png，再写 split.py 按格裁切、去背、缩放到 369×473 输出到 png/。若透明不干净，在 split.py 里做抠图修正。
- 生成后自己逐张检查：边框完整、透明干净、无文字；不合格就重生成对应的卡。
- 最后写 sprites.json：{"sprites":[{"name":"event_build","file":"png/event_build.png"}, ...]}（name 为文件名去掉 .png），以及 README.md（简述内容与生成方式、日期 2026-10-08）。

完成后在最终回复里列出 6 个文件路径和自检结论。
