# 事件卡第3版主体放大来源与校验

2026-10-08。用户已确认第2版风格，本轮只调整主体组合的大小；第3版待审阅，未修改游戏代码。

素材：`F:/work/millionnaire/design/screens/assets-event-faces-v1/png/` 六个原名称PNG。
设计稿：`F:/work/millionnaire/design/screens/31-事件卡-新增卡面与幸运不幸.png`。
版本原稿：`F:/work/millionnaire/design/screens/ui/event-faces-v3/事件卡-新增卡面与幸运不幸-v3.png`。

第2版六张原件保存于 `assets-event-faces-v1/raw/png-v2/`；`raw/design-final-v2.png` 已存在且未覆盖。原卡参考来自 `assets-supplement-v1/png/event_art/01-event_reward.png`～`05-event_jail.png`，原文件未改。

内置image_gen对六张插画分别重新渲染放大的相同物件，保留奶油放射光、草丛白花、物件组合和暖色3D风格。每张生成原图1380×1140，保存于 `assets-event-faces-v1/raw/art-v3-*.png`；提示词在 `raw/generation-prompt-v3.json`。第3版合成脚本 `build-v3.py` 只将新插画下采样到321×265，并通过沿用的圆角窗口掩码替换原卡壳内部。

`raw/validation-v3.json` 自动校验六张369×473 RGBA、原alpha、透明区域RGB归零、18,245个全透明像素、原说明带、插画窗口外所有像素、奖励模板可见卡壳，以及设计稿六窗口之外的所有像素。幸运／不幸题头沿用第2版红／紫改色，框和纹理位置不变。

`raw/compare-v3.png` 上排五张原卡、下排六张第3版；只在对比图归一化原卡尺寸。目视检查主体明显扩大并接近原卡占比、图像清晰无文字、所有主体及草丛未裁断且在说明带以上。颜色前景包围盒只是辅助估算，不能代替精确的物件分割；组合高度约84%～90%，包含辅助物件与草丛。草丛底边在说明带上沿前约6～11像素。

当前31号来源已加入 `records/update.ps1`，版本3；运行同步时校验SHA-256。第2版历史脚本和对比图保留。没有修改客户端、数据库、构建或部署。
