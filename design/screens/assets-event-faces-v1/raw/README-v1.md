# 事件卡面补充位图 v1

日期：2026-10-08。状态：已生成并完成静态自检的候选素材，尚未由用户确认或接入游戏。

## 成品

所有成品均为 **369×473、RGBA、PNG**；卡外为真实透明，题头带和说明带留空。

- `png/event_build.png`：蓝题头，小房子、绿色向上箭头、锤子，免费加盖一级。
- `png/event_downgrade.png`：蓝题头，裂缝和歪斜屋顶的小房子、红色向下箭头，房屋降一级。
- `png/event_station.png`：蓝题头，小镇站台和驶入的可爱小火车，前往车站。
- `png/event_start.png`：蓝题头，起点拱门、方格线、金币，回到起点领奖励。
- `png/lucky_face.png`：红题头，四叶草、金色星星、彩带，幸运。
- `png/unlucky_face.png`：深紫题头，诙谐的小乌云、雨滴和掉落的香蕉皮，不幸。

`sprites.json` 记录六张素材的名称及相对路径。

## 生成与处理方式

使用内置 `image_gen` 图片生成工具，以现有事件卡正面和幸运／不幸卡背为参考，生成二列三行图集，保存为 `raw/atlas.png`。完整英文生成提示词保存在 `raw/generation-prompt.txt`。

用户明确允许 Python 裁切、去背和缩放；`split.py` 使用 Pillow 按指定格位提取中央插画，缩放到参考卡的插画窗口。为精确保留原有边框、题头带和说明带位置，复用奖励卡的卡壳；红／紫题头通过 HSV 改色保留菱形暗纹。没有手绘代替 AI 插画。

`raw/frame-reference.png` 是奖励卡原图的只读来源副本。脚本以原卡透明轮廓的最大连通区域清理外部杂点，保留边缘抗锯齿，将透明像素统一为 RGBA=(0,0,0,0)，并使内部近不透明像素为 alpha=255。

复现命令（需要 Pillow）：

```powershell
cd F:/work/millionnaire/design/screens/assets-event-faces-v1
python split.py
```

图集裁切坐标针对本次原图，不应直接套用于重新生成的图集。脚本会重建本目录的六张成品、清单及预览，不触碰来源目录或游戏代码。

## 来源

- 风格与卡壳：`../assets-supplement-v1/png/event_art/01-event_reward.png`。
- 已查看的其他正面参考：同目录 `02-event_fine.png`、`03-event_tool.png`、`04-event_move.png`、`05-event_jail.png`。
- 已查看的设计稿：`../12-事件卡-五类卡面与问号卡背.png`；其中示例文字不用于此次成品。
- 颜色参考：`../assets-lucky-v1/png/lucky_card_back.png`、`../assets-lucky-v1/png/unlucky_card_back.png`。

## 自检记录

六张成品已逐张放大查看：主体正确、金框完整、卡片正面无倾斜；题头和说明带无文字，插画无文字／字母／数字／标签／水印；没有卡外闪光碎片。

脚本重新读取六个 PNG 验证尺寸、RGBA 格式、透明轮廓一致，说明带与参考完全一致，四张蓝题头与参考完全一致。每张有 18,245 个 alpha=0 像素，透明区的 RGB 也清零。边缘保留抗锯齿的部分透明像素。

机器校验细节：`raw/validation.json`。六图对照预览：`raw/final-preview.png`；该预览有灰色查看底，仅用于检查，六张成品均为真实透明。

此次全部新增文件均保存于本方案目录；未更新父目录索引、运行父目录同步脚本、改动已确认素材或修改游戏代码。
