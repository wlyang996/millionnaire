# 方案 B 生成提示词

## 整页组合预览

输入角色：原设计图为布局/风格参考，新头像、大厅背景、UI 元件表和标题牌为新素材参考。Transparent background: false。

Use case: ui-mockup. Build a clean polished TWO-SCREEN presentation of candidate art B for the original Chinese friend multiplayer boardgame. This is a visual layout study, not a working game. Source 1 is established layout/style guide. Sources 2-5 are NEW artwork to incorporate faithfully: expressive eight avatars, painted riverside background, moderate bevel yellow/blue buttons and ivory panels, blank honey wooden sign. Recompose LEFT portrait lobby and RIGHT portrait friend room, equally sized, side by side with a thin neutral divider. Both screens fully visible, no phone frames. Keep 2.5D cartoon town art, lively portraits, clean gentle warm depth, sky-blue grass-green ivory palette. Avoid glossy generic dolls or thick candy-like UI. Lobby: upper small avatar and settings, wood title plaque with crisp simplified Chinese '好友桌游', new village scenic background and four new expressive figures as a small group, yellow '创建房间', blue '房间号加入', ivory '返回对局', unobtrusive bottom '我的战绩'. Friend room: blue header '好友房间', white room-number panel '826315' plus blue '分享邀请'; 4 columns by 2 rows EIGHT seats with the new avatar identities, names 小林 可可 阿杰 奶茶 阿凯 圆圆 豆豆 毛毛 and restrained green ready chips. Lower airy ivory settings panel: 地图 50格; 初始资金 3000; 结束模式 限时; 局时 30分钟; 投骰时间 15秒. Bottom narrow voice row with mic and avatars, gray wait button '等待全员准备'. Reuse candidate art B as much as possible, consistent visual size and margins, correct simplified Chinese text. Clean accurate silhouette edges composited into scenes, no colored fringe, no watermark. No additional screens. This single image is a conceptual comparison preview only.

日期：2026-10-05（Africa/Cairo）。工具：内置 image_gen。
参考：`design/ui/main-screens-v1.png`，仅作为风格参考。
目标：保持已认可的 2.5D 卡通小镇风格，增加角色表情和动作区别，降低过强的塑料光泽。
状态：候选，后期选择；不替换方案 A，不修改客户端资源。

## 一次边缘修正（头像表、UI 表、图标表各一次）

输入：对应首次生成原图。Transparent background: true。

Precise technical cleanup of this transparent game asset sheet. Preserve every character/element, identity, pose, color, material, lighting, count and arrangement exactly. Remove ALL disconnected colored flecks, stray pixels, colored fringe and white halos in transparent space or along silhouettes. Each silhouette must have a clean smooth anti-aliased alpha edge without a colored outline. Keep all objects completely inside the image with 24px transparent outer padding, keep rows separated and individual elements isolated. Do not add artwork, text, borders or a backdrop. Transparent background with fully clear alpha outside artwork. Preserve soft original warm 2.5D cartoon art. Only technical edge cleanup and padding.

结果：仍有少量残边和杂点，详见 MANIFEST.md；属于待选择候选，尚未正式验收。

## avatars_sheet

Transparent background: true

Use case: stylized-concept. Create an ORIGINAL game avatar sprite sheet on a genuinely transparent background, exactly FOUR equal columns by TWO equal rows, each figure isolated and centered with generous transparent padding in its own cell. Input reference is STYLE GUIDE: match the playful lively stylized 2.5D children from the FIRST (lobby) and SECOND (room) screen of the supplied image, not generic glossy doll portraits. Preserve their compact chibi proportions, expressive varied gestures, large but naturally proportioned eyes, asymmetrical lively hair silhouettes, soft warm light and sky-blue grass-green warm-ivory game palette. Subtly painted sculpted shapes, restrained highlights, no wet plastic sheen, no photoreal pores, no elaborate hair strands. Bust portraits with shoulders, each facing viewer, distinctive silhouette and expression. Row1: cheerful tousled brown-haired boy blue hoodie leaning forward; brown-haired girl yellow cardigan with side flower clip laughing with eyes smiling; green cap boy black round glasses with a playful confident grin; pink knit cap girl reddish jacket friendly shy smile. Row2: black tousled-haired boy navy jacket calm grin; violet bob-haired girl curious head tilt lavender jacket; sandy cap boy cream vest mischievous smile; long black-haired girl peach jacket warm attentive smile. EIGHT figures only, same scale and art direction, heads fully contained. No avatar border circles, no captions, no numbers, no props outside cells, no scene, no watermark. Transparent alpha not a painted checkerboard. Wide 2:1 sheet.
## ui_sheet

Transparent background: true

Use case: stylized-concept. Produce one game UI sprite sheet on GENUINELY TRANSPARENT background, exactly 2 columns x 4 rows equal isolated cells with ample empty padding. Reference image is a STYLE GUIDE: use the pleasing moderate depth and warmth of the first lobby and second room screen's buttons and white panels. Eight BLANK assets only: row1 yellow main button left / sky blue secondary button right; row2 muted gray-blue disabled button left / fresh leaf green small button right; row3 large square warm ivory rounded panel left / pale warm ivory wide inner section panel right; row4 small green ready capsule left / muted gray capsule right. Buttons 4:1 aspect and panels consistent 20px soft corners. Fresh blue and golden yellow, subtle painterly soft bevel and slender lower depth, ONE restrained upper-left highlight, no heavily stacked rings, no glass reflections, no candy plastic, no giant white glare. Panel centers uniform empty warm ivory, fine soft beige outer edge, narrow restrained shadow within the object alpha. Match source's uncluttered premium friendly tabletop UI, do not simplify into flat vector. Front-facing. No text, no symbols, no icons, no labels, no watermark, no borders around sheet. Clear separation in all cells. Portrait 1:2 canvas.
## icons_sheet

Transparent background: true

Use case: stylized-concept. Create one matching game controls sprite sheet, transparent alpha, exactly 3 columns x 3 rows equal cells, nine isolated icons centered with generous padding and equal optical size. Match the small understated circular controls in the top right of reference lobby and voice row in the room, cohesive soft painterly 2.5D cartoon tabletop-town UI. Muted medium sky-blue round discs, minimal thin edge, small ivory simple symbols, soft low depth and gentle warm upper-left light. Avoid shiny glass domes, metallic rings and excessive plastic glare. Row1: blue circle with ivory X / blue circle with ivory left arrow / warm gold coin with softly embossed plain center no letter. Row2: blue circle with ivory microphone / blue circle with ivory chat bubble and three blue dots / blue circle with ivory three connected share nodes. Row3: blue circle with ivory two overlapping pages / blue circle with ivory simple gear / blue circle with ivory speaker and two sound waves. Clean readable silhouettes at 48px. No decorative badges, no letters, no words, no scene, no watermark. Reference is style guide only. Square sheet with transparent space between items.
## logo_title

Transparent background: true

Use case: stylized-concept. One isolated BLANK wooden title sign for the cheerful miniature tabletop-town game in the reference. Reference lobby title sign is STYLE GUIDE: match its handmade rounded honey-wood shapes, warm subtle painted grain, asymmetric small leaf clusters and playful sculpted silhouette, softly shaded 2.5D, not polished plastic or photoreal wood. Empty broad light honey center reserved for later Chinese title overlay. Slim darker rounded wood perimeter, moderate depth, fresh green leaves at two corners, a couple of tiny meadow flowers allowed as part of edge, no bird. Front-facing, landscape 2:1, centered with generous transparent padding. True transparent alpha. NO TEXT or lettering, no branding, no characters, no scene, no dice, no watermark. Charming and understated, identical palette and material feel to supplied lobby.
## bg_lobby

Transparent background: false

Use case: stylized-concept. Generate a full-bleed opaque portrait 9:16 background for the same original multiplayer tabletop-town game. Use the FIRST lobby screen of the reference as strict art STYLE GUIDE: cheerful warm miniature riverside village, soft painted 2.5D sculpted scenery with gently irregular handcrafted roof shapes, blue sky and turquoise river, lush grass green trees, warm ivory pathways, ochre/terracotta cottages. Friendly illustrated mobile-game world, not photoreal, not toy plastic render, no glossy skin or sterile geometric symmetry. Upper 12% blue sky and modest fluffy clouds. Middle 15-60% layered colorful small cottages beside winding river, simple bridge, some trees and a miniature square. Lower 40% quieter warm meadow/path clearing, very sparse flowers at edges, low contrast in middle for UI buttons to be overlaid. Same intimacy and charm as reference; less generic huge panoramic town. Important: environment only. NO people, NO animals, NO dice, NO playing board, NO buttons, NO panels, NO lettering, NO logo, NO title sign, NO watermark. All canvas opaque scene. High quality portrait background, no frame.
