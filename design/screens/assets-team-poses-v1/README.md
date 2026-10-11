# 组队合作人物动作独立素材 v1

2026-10-11用户确认37号稿后授权集成。使用内置image_gen生成，参考37号稿与现有8位角色头像。完整提示词见`prompts.json`和`props-prompts.json`，像素来源/大小/SHA-256见`manifest.json`。

- `team_pose_give.png`：递出金币袋。
- `team_pose_receive.png`：伸出双手接币。
- `team_pose_worry.png`：忧虑、双手合拢。
- `team_pose_celebrate.png`：伸出双手托杯，可镜像组合。
- `team_trophy.png`：完整独立奖杯，无文字。
- `team_debt_thought.png`：房屋金币思考气泡。

四张动作表各9格（3×3）：糖糖、可可、阿杰、奶茶、阿凯、圆圆、豆豆、毛毛、机器人。总计36个姿态与2件道具。角色索引与现有头像一致，机器人索引8不取模成真人。钱袋只在出资人姿态中，接币者不重复持钱袋；奖杯只绘制一次，由两位实际冠军成员的托杯姿态组合。

全部PNG是真正RGBA透明素材，不裁整页UI、不改变生成像素。`regions.json`记录每格主要人物的裁框，排除相邻人物边缘碎片；Cocos在运行时引用对应SpriteFrame。按原格坐标保持人物比例与手部位置，右方庆祝人物镜像。禁用素材自动图集打包，避免运行裁框使用错误纹理坐标。

运行副本位于`client/assets/resources/art/team_poses/`，执行`node client/tools/sync-team-poses.cjs`校验复制、元数据、资源索引与裁框；通用导入脚本也保留这些资源。旧独立人物素材和35–37号稿不覆盖。静态姿态，不宣称骨骼动作或逐帧动画。
