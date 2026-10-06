# 单件位图清单

2026-10-06。共85个透明PNG，位于png/。原图未覆盖，已检查分类预览和透明留边，尚未在引擎验证。

- [头像](previews/avatars.jpg)：8件，png/avatars/。
- [棋子](previews/pawns.jpg)：8件，png/pawns/；糖糖、可可、阿杰、奶茶、阿凯、圆圆、豆豆、毛毛。
- [房屋](previews/houses.jpg)：0–3级，4件，png/houses/。
- [中央小镇](previews/town.jpg)：1件，png/town/，中央广场用于叠加骰子。
- [按钮底](previews/buttons.jpg)：黄、蓝、绿、米白、红、禁用灰，6件，png/buttons/，无固定文字。
- [图标](previews/icons.jpg)：金币、语音、聊天、蓝／红时钟、返回、设置、复制、分享、房屋、拍卖、定位、路障、钥匙、查询、盾牌、箭头、勾选、银行、监狱，20件，png/icons/。
- [道具卡](previews/cards.jpg)：14道具正面加卡背／空白底，16件，png/cards/。
- [骰子](previews/dice.jpg)：1–6点及3个翻滚姿态，9件，png/dice/。
- [鳄鱼](previews/croc.jpg)：开口、半闭、闭口，3件，png/croc/，牙齿独立叠加。
- [牙齿](previews/teeth.jpg)：正常与按下，2件，png/teeth/；危险牙不另设外观。
- [事件卡](previews/event_art.jpg)：五种正面、问号背面、展开牌堆、提示框，8件，png/event_art/。

[sprites.json](sprites.json)保存逐件文件名、尺寸、原图区域、裁切偏移、清理参数和SHA-256。[manifest.json](manifest.json)保存图集alpha统计，等分格是初始索引，实际跨格裁切以sprites.json为准。

每件有8像素透明留边。紧裁尺寸不等于动画锚点；正式导入时应校准SpriteFrame、统一缩放、人物脚底锚点、按钮九宫格及鳄鱼牙齿位置。骰子翻滚姿态和人物位图不代替程序动画。

本包为待决定的新候选，未接入client或替换已确认素材。文字、昵称、价格、倒计时应由游戏文字层绘制。
