# 全局玩法独立素材 v1

2026-10-10。根据用户确认的32号布局补齐独立插画，生成工具为内置 image_gen。此目录只保存不含UI文字的素材，所有姓名、金额、轮数与活动名称由代码绑定服务端数据。

- `reward_gift.png`：金币礼盒，1536×1024，透明背景，已接入奖励页。
- `city_construction.png`：建设节人物与小屋，1536×1024，透明背景，已接入城市预告。

生成参考：`../ui/global-gameplay-v1/半程奖励-城市事件-趣味称号-v1.png`，仅沿用视觉风格。历史整页稿标题不作为实际奖励名。

来源：原始礼盒 `C:/Users/Administrator/.codex/generated_images/01a10ec9-bf84-7480-b53f-50c189c668dc/exec-74c37590-7753-453d-b2d4-b4dd76b96fc8.png`；原始建设节 `C:/Users/Administrator/.codex/generated_images/01a10ec9-bf84-7480-b53f-50c189c668dc/exec-270db4d6-161b-4a1f-bb7d-f49dc0ac94eb.png`。原输出保留，工作区与客户端各存独立副本。

提示词要点：按已确认32号礼盒与工匠风格，生成精致明亮的3D卡通插画。礼盒为打开的金色金币礼盒和蓝金丝带；工匠戴黄色安全帽、穿蓝色背带裤，旁边为奶油色红屋顶小屋。单件透明背景，无标题、文字、按钮、屏幕或UI边框。工具使用 `transparent_background=true`，保留PNG透明通道，未用整页截图切出固定文字。

导入与SHA256记录：`../records/client-art-import-2026-10-10-global.json`。命令：`node client/tools/sync-design-art.cjs --global-only`；限定本批新增素材，不覆盖其他已优化运行资源。
