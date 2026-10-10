# 好友系列赛实际Cocos预览

2026-10-10，Cocos Creator3.8.8 web-mobile构建，本机浏览器1280×720；游戏内容720×1280等比显示。离线fixture验证实际节点布局，规则计分、WS和落库另由测试覆盖。

- [四人设置](setup4.png)、[八人设置](setup8.png)、[八人设置滚动到底](setup8-scrolled.png)。最后四名头像、昵称、准备状态可见。
- [四人局间积分](points4.png)、[八人局间积分](points8.png)、[八人积分滚动](points8-scrolled.png)。16/16/8/6和1/1/3/4并列，无金币图标；零分两人共同第7。
- [四人最终总榜](final4.png)、[八人并列冠军](final8.png)、[八人总榜滚动](final8-scrolled.png)。四人26/22/12/9；八人前两名26/26共同冠军。
- [本局结算入口](result4.png)：先显示本局净资产与称号，再点击查看系列积分。
- [点击后积分页](result-to-points4.png)：已实际点击验证跳转，浏览器错误日志为空。

复验地址：`/?demo=1&preview=series-setup4`，setup8、points4、points8、final4、final8、result4同理。演示关闭联网，不产生战绩或系列计分。运行页复用现有独立素材，整页候选稿未切图接入。
