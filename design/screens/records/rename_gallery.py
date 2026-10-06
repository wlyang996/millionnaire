"""Rename UI/gallery files with concrete Chinese descriptions; preserve snapshots."""
from pathlib import Path
import json, hashlib, shutil

ROOT=Path(__file__).resolve().parent.parent
HISTORY=ROOT/'ui/history/gallery-before-refresh-2026-10-06'
HISTORY.mkdir(parents=True,exist_ok=True)
NAMES={
 '01-board.png':'01-对局棋盘-普通回合.png',
 '02-motion-storyboard.png':'02-骰子翻滚与人物逐格跳跃-动作分镜.png',
 '03-profile-result.png':'03-登录资料-本局结算-破产观战.png',
 '04-property-popups.png':'04-购买地产-升级-租金响应-交易确认.png',
 '05-auction-debt.png':'05-拍卖竞价-应急抵押-欠款第二阶段.png',
 '06-room-connection.png':'06-好友房间-连接状态-重连同步.png',
 '07-style-reference-a.png':'07-风格参考A-历史四界面总览.png',
 '08-style-reference-b.png':'08-风格参考B-大厅与好友房间.png',
 '09-ui-states.png':'09-道具横滑-回合提示-时间警示规范.png',
 '10-event-card-preview.png':'10-事件抽卡-本人中央点击卡背.png',
 '11-event-idle-preview.png':'11-对局棋盘-他人回合等待.png',
 '12-event-faces.png':'12-事件卡-五类卡面与问号卡背.png',
 '13-target-move-query-roadblock.png':'13-路障放置-定点移动-查询目标与结果.png',
 '14-current-property-cards.png':'14-强制购房-降级-拆楼-清地.png',
 '15-bank-jail.png':'15-银行抵押与赎回-监狱操作.png',
 '16-teeth-card-detail.png':'16-虎口拔牙结果-定点移动与房屋保护详情.png',
 '17-discard-surrender-history.png':'17-弃牌-认输二次确认-本人战绩.png',
 '18-information-input-candidates.png':'18-资产总览-格子详情-房间号键盘-候选.png'}

# Check all explicitly named single-file destinations before moving anything.
operations=[]
for old,new in NAMES.items():
    source=ROOT/old
    target=HISTORY/old
    if source.exists():
        if target.exists() and source.read_bytes()!=target.read_bytes():
            raise RuntimeError(f'History collision: {target}')
        operations.append((source,target,'historical gallery snapshot'))
source_names={'ui/board-v7/board-design-v7.png':'ui/board-v7/对局棋盘-普通回合-v7.png',
              'ui/board-v7/ui-states-v7.png':'ui/board-v7/道具栏-回合提示-时间警示规范-v7.png'}
for old,new in NAMES.items():
    if int(old[:2])>=12:
        source_names['ui/supplement-v1/'+old]='ui/supplement-v1/'+new[3:-4]+'-v1.png'
for old,new in source_names.items():
    source,target=ROOT/old,ROOT/new
    if source.exists():
        if target.exists() and source.read_bytes()!=target.read_bytes():
            raise RuntimeError(f'Source collision: {target}')
        operations.append((source,target,'descriptive source filename'))

records=[]
for source,target,role in operations:
    # No recursive deletion; every target is checked inside the archive.
    if not target.resolve().is_relative_to(ROOT.resolve()):
        raise RuntimeError(f'Outside archive: {target}')
    digest=hashlib.sha256(source.read_bytes()).hexdigest()
    if not target.exists():
        shutil.move(str(source),str(target))
    else:
        # Collision preflight proved the duplicate is byte-identical.
        source.unlink()
    records.append({'old':source.relative_to(ROOT).as_posix(),
                    'new':target.relative_to(ROOT).as_posix(),'role':role,'sha256':digest})

# Local source links and generation output targets follow their renamed source.
# Historical generation reference inputs point at the exact preserved snapshot.
for folder in [ROOT/'ui/board-v7',ROOT/'ui/supplement-v1',ROOT/'ui/refresh-v3']:
    for path in folder.rglob('*'):
        if path.suffix not in ('.md','.json','.txt'):
            continue
        text=path.read_text(encoding='utf-8-sig')
        before=text
        for old,new in source_names.items():
            text=text.replace(old,new)
            if old.startswith('ui/'+folder.name+'/'):
                text=text.replace(old.rsplit('/',1)[-1],new.rsplit('/',1)[-1])
        for old in NAMES:
            text=text.replace('F:/work/millionnaire/design/screens/'+old,
                              'F:/work/millionnaire/design/screens/ui/history/gallery-before-refresh-2026-10-06/'+old)
        if text!=before:
            path.write_text(text,encoding='utf-8')

manifest={'date':'2026-10-06','root_names':NAMES,'source_names':source_names,
          'operations':records,'historical_gallery':'ui/history/gallery-before-refresh-2026-10-06',
          'note':'Historical references retain exact old images; refreshed gallery published by update.ps1'}
report=ROOT/'records/renamed-files-2026-10-06.json'
if records or not report.exists():
    report.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'moved_files':len(records),'gallery_names':len(NAMES)},ensure_ascii=False))
