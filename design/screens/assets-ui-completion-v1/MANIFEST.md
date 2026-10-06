# UI缺少素材补充包 v1

2026-10-06。按用户要求补充独立位图并标注代码/交互缺项。原图保留在atlases，单件在png，分类预览在previews，提示词在jobs.json；用户已明确授权PIL清理与拆分。

## 素材范围

- 14张独立金框道具大卡：路障、免租、建造、降级、定点移动、出狱、拍卖、交易、拒绝购买、房屋保护、查询、强制购房、拆楼、清地。底部无字，卡名与规则由客户端文字层绘制。
- 1张详情背景：card_detail_background，原生生成图保留，另输出720×1280设计画布版。
- 3种规则面板：card_detail_rules_5/2/3，分别包含4/1/2条分隔线。
- 6色扁平按钮：button_flat_yellow/blue/green/ivory/red/gray。关闭与操作按钮使用无字底，标签保持动态。
- 6种详情辅助素材：card_detail_title/back、icon_start_flag、icon_active_card、icon_response_lightning、rule_separator。
- 6种聊天皮肤：面板、白/蓝/粉消息气泡、快捷短语底、输入底。头像复用既有素材，文字不烘焙进皮肤。
- 8种资产角色插画：information_host_角色名，按糖糖、可可、阿杰、奶茶、阿凯、圆圆、豆豆、毛毛顺序。
- 32个跳跃姿态：每个角色ready/crouch/airborne/land四帧；保留同一格画布尺寸与alpha，不按每帧身体包围盒紧裁。
- 6种特殊土地近景：scene_bank/jail/game_center/rest/start/station；事件卡背与牌堆复用既有素材。特殊土地完整页面20～23及25仍为候选，素材制作不代表其布局已经确认。
- 品牌Logo与四人桌游插画各1件：brand_logo/lobby_friends。
- 6种结算装饰：result_trophy/result_laurel/result_plaque/minigame_title/result_reward_panel/result_zero_panel；均为无字底图，除Logo明确保留“好友桌游”文字。

合计计划90件；实际数量及逐件SHA-256以sprites.json为准，不计原图和预览为独立素材。

## 状态边界

本包仅新增素材及文档，不覆盖旧素材、不自动替换ArtCatalog、不改业务流程。单件生成不等于已接入或已定稿。Cocos九宫格、场景缩放、角色脚底锚点、动画序列及实机显示需在后续接入时验证。

真实3D骰子需要模型/渲染/动画实现，本批位图不能替代。道具目标选择、合法性、扣卡与结算、银行/监狱流程、虎口拔牙结果页以及其他页面的排版还原均属于代码工作，详见[UI修正清单](../records/ui-corrections-2026-10-06.md)。

未完整定义的交易输入、房主移除确认、登录失败/断网/战绩空态仍需设计决策，不在本包凭空定义新流程。

## 验证文件

sprites.json保存来源区域、裁切、alpha清理、固定帧尺寸和SHA-256；crop-overrides.json记录生成图实际排布与数学网格不同的情况。split_assets.py可复现拆分。分类预览用于检查完整边框、角色身份、透明轮廓与图案；预览通过不代表实际动画连贯性或微信真机已通过。
