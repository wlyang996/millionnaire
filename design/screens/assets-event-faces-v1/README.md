# 新增事件卡面：第 3 版

日期：**2026-10-08**。状态：第 2 版风格已由用户确认；当前 `png/` 为第 3 版主体放大候选，已完成本地图片与结构自检，待审阅。本轮未接入游戏。

## 第 3 版：仅放大主体物件与草丛

六张插画使用内置 **image_gen** 分别以第2版插画为编辑参考，在高分辨率 **1380×1140** 下重新生成放大的相同物件组合，再一次性缩小至 **321×265** 插画窗口，与第2版原卡壳合成。未将第2版低分辨率位图直接放大。单个居中物件、奶油放射光、草丛小白花、柔和配色、幸运红题头及不幸紫题头沿用原方案；没有新增场景、拱门或站台。四叶草重做后保留四片心形叶与两颗金星。

- 当前六张切图：`png/event_build.png`、`event_downgrade.png`、`event_station.png`、`event_start.png`、`lucky_face.png`、`unlucky_face.png`。
- 当前设计稿：`../31-事件卡-新增卡面与幸运不幸.png`；版本原稿：`../ui/event-faces-v3/事件卡-新增卡面与幸运不幸-v3.png`。
- 原卡与第3版同尺寸对比：`raw/compare-v3.png`。
- 第2版六张原件：`raw/png-v2/`；第2版设计稿 `raw/design-final-v2.png` 原已存在，本次未覆盖。
- 第3版生成原图：`raw/art-v3-*.png`；完整提示词：`raw/generation-prompt-v3.json`；合成与校验：`build-v3.py`、`raw/validation-v3.json`。

自检结论：六张均为 **369×473 RGBA**，卡外真透明，每张18,245个全透明像素。所有插画窗口之外的像素、完整alpha、说明带和题头均与第2版一致；规范金框及空带与奖励卡模板可见像素一致，红／紫题头仅沿用原改色。主体和草丛明显扩大，占比目视接近上排原卡；彩色前景测得组合高度约84%～90%，这是包含箭头／旗杆／草丛的颜色边界估算，不等同于精确主体分割。草丛底边距说明带上沿仍有约6～11像素。逐张目视检查插画清晰、无文字、物件未裁断；设计稿六个插画窗口以外逐像素不变，文字、数值、头像、背景和版式均保留。

复现第3版合成与校验：

```powershell
cd F:/work/millionnaire/design/screens/assets-event-faces-v1
python build-v3.py
powershell -NoProfile -File ../records/update.ps1
```

第2版 `split.py`／`review.py` 保留作为历史复现脚本；运行它们会恢复第2版内容，**当前第3版请用 `build-v3.py`**。原五张参考图和游戏代码未改。

以下保留第2版来源及处理记录，其中第2版路径不代表当前 `png/` 的生成来源。

## 交付

设计稿：`F:/work/millionnaire/design/screens/31-事件卡-新增卡面与幸运不幸.png`，1024×1536。木牌、头像条、河边小镇及三列两行卡片沿用 12 号稿版式，题头、结果、说明文字按任务要求保留。六块插画从最终切图同步，确保设计稿与素材共用相同物件。

六张切图全部为 **369×473 RGBA PNG**，边框外真透明；题头和说明带留空；插画无文字数字：

- `png/event_build.png`：小房子、小绿上箭头、小锤子。
- `png/event_downgrade.png`：屋顶缺少几片瓦的小房子、小珊瑚红下箭头。
- `png/event_station.png`：单个小火车头和一小截铁轨。
- `png/event_start.png`：方格小旗、三枚金币。
- `png/lucky_face.png`：四叶草、两颗小金星，红色题头。
- `png/unlucky_face.png`：小乌云、雨滴、香蕉皮，深紫题头。

`sprites.json` 保持原六个名称和相对路径。

## 第 2 版修订

移除车站整片站台、起点拱门等满幅场景，改为单个居中的主体物件组合。统一淡奶油放射光、小片草丛及白花托底，缩小物件并增加四周留白。第二轮进一步缩小锤子和上下箭头，降低箭头及四叶草的鲜亮程度，移除前四张多余金星。暖色 3D Q 版和柔和高光以原钱袋、牢门为依据。

## 来源及逐像素对齐范围

唯一风格来源：`../12-事件卡-五类卡面与问号卡背.png` 与 `../assets-supplement-v1/png/event_art/01-event_reward.png`～`05-event_jail.png`，五张均已逐张查看。题头色系参考 `../assets-lucky-v1/png/lucky_card_back.png` 和 `unlucky_card_back.png`。

原五张实际尺寸分别为 369×473、369×474、368×474、368×474、369×468，无法同时与不同轮廓逐像素一致。因此以 **01-event_reward.png** 为规范卡壳，直接复用金框、四角圆钉、蓝题头及空说明带，仅替换插画。四张蓝题头原像素不变；红紫题头仅改色，保留原菱形纹理和几何位置。全部未替换可见卡壳像素经自动校验，与对应规范卡壳一致。

透明处理保留主体轮廓与抗锯齿，清理模板外部零散 alpha 杂点；每张 18,245 个 alpha=0 像素，透明区 RGB 归零，六张 alpha 轮廓一致。不宣称与模板外围杂点或隐形 RGB 完全一致。

## 生成、处理与复现

使用内置 **image_gen** 生成设计稿和二列三行无字图集；按用户指定的 Python/Pillow 裁切、去背、缩放及原卡壳合成流程处理。完整三阶段提示词：`raw/generation-prompt-v2.txt`。

```powershell
cd F:/work/millionnaire/design/screens/assets-event-faces-v1
python split.py
python review.py
```

`raw/atlas.png` 为第二轮选定图集；`raw/atlas-candidate-v2.png` 保留第一轮候选。生成图集仍含卡外底色，最终切图透明由原卡壳 alpha 实现。`split.py` 显式裁切坐标仅适用于本次图集；`review.py` 同步设计稿插画并生成对比图。

第 1 版原有 raw 文件均改名为 `*-v1.*` 保留；旧六张切图另备份在 `raw/png-v1/`，旧脚本和 README 保存为 `raw/split-v1.py`、`raw/README-v1.md`。原始设计生成稿保存在 `raw/design-generated-v2.png`，最终同步稿保存在 `raw/design-final-v2.png`。

## 自检

- `raw/compare-v2.png`：上排原五张，下排新六张，均以 369×473 展示；仅对比图归一化原参考，来源文件未改。
- 逐张目视检查通过：单个居中物件组合、奶油放射光、草丛白花托底、主体未贴边、无文字，箭头与锤子为辅助物件；色彩柔和程度已对照原钱袋与牢门。
- `raw/validation.json`：六张尺寸、RGBA、透明角点、alpha 轮廓、未替换可见卡壳像素、空说明带与四张蓝题头均通过自动检查；红紫仅改色，框和暗纹几何位置不变。
- 设计稿的文字、顺序与最终物件已目视复核，设计稿和切图共用同一插画来源。

第2版历史交付仅写本方案目录与指定31号设计稿，未运行 `screens/records/update.ps1`。第3版本次已更新screens根README、31号版本原稿及来源记录，并运行同步脚本；其余01～30号查看图均未变化。游戏代码未改。
