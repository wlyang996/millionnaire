/** 登录页仅填写昵称；头像与昵称编辑从大厅的个人资料入口进入。 */
import { Graphics, Label } from 'cc';
import { checkNickname, NICK_MAX } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { EditField } from '../ui/EditField';
import { drawCheck } from '../ui/Icons';
import { fillCircle, fillPoly, fillRR, gfx, line, mk, onTap, setText, strokeRR, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { art, characterChoices, characterArtRevision, informationCharacterKey } from '../ui/Art';
import { isWechat } from '../net/Wx';
import { diagnosticError, networkLog } from '../net/Diagnostics';

export class ProfileScreen extends Screen {
    get id(): 'profile' | 'profile-edit' { return this.editing ? 'profile-edit' : 'profile'; }
    get title(): string { return this.editing ? '个人资料' : '登录'; }
    constructor(private readonly editing = false) { super(); }
    private nick = '';
    private avatarIdx = 0;
    private initialized = false;
    private hint!: Label;
    private hintIcon!: Graphics;
    private enter!: Button;
    private field!: EditField;
    private busy = false;
    private identityReady = false;
    private identityError = '';
    private characterPage = 0;
    private catalogVersion = -1;

    onShow(): void {
        if (!this.editing && ctx.store.online && isWechat() && !this.identityReady) void this.identify();
    }

    private async identify(): Promise<void> {
        if (this.busy) return;
        this.busy = true;
        this.identityError = '';
        this.rebuild();
        try {
            const existing = await ctx.store.online!.wechatQuickLogin();
            if (!this.root.isValid || ctx.screens.current !== this) return;
            if (existing) { ctx.screens.go('lobby'); return; }
            this.identityReady = true;
        } catch (e) {
            this.identityError = wechatError(e as Parameters<typeof wechatError>[0]);
        } finally {
            this.busy = false;
            if (this.root.isValid && ctx.screens.current === this) this.rebuild();
        }
    }

    protected build(): void {
        this.catalogVersion = characterArtRevision();
        const st = ctx.store;
        this.backdrop('sky');
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        if (!this.initialized) {
            this.nick = st.profile.nickname;
            this.avatarIdx = st.profile.avatar;
            this.initialized = true;
        }
        if (!this.editing) { this.buildLogin(); return; }
        this.header('个人资料', () => { if (!this.busy) ctx.screens.back('lobby'); });
        avatar(this.root, 302, 120, 116, this.avatarIdx, this.nick || '我');

        const panelHeight = characterChoices().length > 8 ? 704 : 646;
        const card = mk(this.root, 'ProfilePanel', 36, 280, 648, panelHeight);
        art(card, 'info_asset_panel', 0, 0, 648, panelHeight, 'panel');
        text(card, '个人资料', 28, 12, 592, 56, 38, '#613C24', { bold: true });
        // 标题两侧的绿叶
        const leaves = gfx(mk(card, 'Leaves', 0, 0, 648, 70));
        for (const [cx, dir] of [[218, -1], [430, 1]] as [number, number][]) {
            fillPoly(leaves, [[cx, 54], [cx + dir * 14, 24], [cx + dir * 30, 14], [cx + dir * 24, 40]], '#5DAE45');
            fillPoly(leaves, [[cx, 54], [cx + dir * 30, 40], [cx + dir * 44, 46], [cx + dir * 24, 56]], '#7CC75E');
        }

        // 头像
        text(card, '内置头像', 28, 222, 400, 36, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        const choices = characterChoices();
        const pages = Math.max(1, Math.ceil(choices.length / 8));
        this.characterPage = Math.min(this.characterPage, pages - 1);
        choices.slice(this.characterPage * 8, this.characterPage * 8 + 8).forEach((choice, cellIndex) => {
            const i = choice.id;
            const cx = 28 + (cellIndex % 4) * 150;
            const cy = 260 + Math.floor(cellIndex / 4) * 128;
            const cell = mk(card, 'AvatarCell', cx, cy, 138, 120);
            const sel = i === this.avatarIdx;
            // 设计稿 03：圆角方形人物卡（头像底色的浅色 + 半身人物图），下方名字
            fillRR(gfx(cell), 0, 0, 138, 120, 18, sel ? Theme.c.white : '#F2E9D6');
            const tile = mk(cell, 'Tile', 22, 4, 94, 88);
            fillRR(gfx(tile), 0, 0, 94, 88, 16, Theme.avatarColors[i % 8] + '55');
            if (!art(tile, informationCharacterKey(i), 4, 2, 86, 86)) avatar(cell, 24, 4, 90, i, choice.name);
            text(cell, choice.name, 0, 94, 138, 26, Theme.font.xs, sel ? Theme.c.blueDark : Theme.c.ink, { bold: sel });
            if (sel) {
                const gg = gfx(mk(cell, 'Sel', 0, 0, 138, 120));
                strokeRR(gg, 0, 0, 138, 120, 18, Theme.c.blue, 4);
                fillCircle(gg, 115, 88, 15, Theme.c.blue);
                drawCheck(gg, 115, 88, 30, Theme.c.white, 4);
            }
            onTap(cell, () => {
                if (this.busy) return;
                this.avatarIdx = i;
                this.rebuild();
            }, false);
        });
        if (pages > 1) {
            new Button(card, '上一页', 28, 516, 150, 40, 'ghost', () => { this.characterPage = (this.characterPage + pages - 1) % pages; this.rebuild(); }, 22);
            text(card, (this.characterPage + 1) + ' / ' + pages, 220, 516, 180, 40, 22, Theme.c.ink);
            new Button(card, '下一页', 470, 516, 150, 40, 'ghost', () => { this.characterPage = (this.characterPage + 1) % pages; this.rebuild(); }, 22);
        }

        // 昵称
        text(card, '昵称', 28, 76, 200, 36, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        this.field = new EditField(card, 28, 116, 592, 60, '输入昵称（最多 ' + NICK_MAX + ' 字）', 20, (s) => {
            this.nick = s;
            this.checkNow();
        });
        if (this.nick) this.field.value = this.nick;
        this.hintIcon = gfx(mk(card, 'HintIcon', 28, 184, 28, 28));
        this.hint = text(card, '', 64, 180, 556, 36, 21, Theme.c.inkSoft, { align: 'l' });


        this.enter = primaryButton(card, '保存资料', 28, pages > 1 ? 570 : 520, 592, pages > 1 ? 88 : 96, () => {
            void this.saveProfile();
        });
        this.checkNow();
    }

    private buildLogin(): void {
        art(this.root, 'brand_logo', 100, 112, 520, 331);
        const card = mk(this.root, 'LoginPanel', 36, 590, 648, 280);
        art(card, 'info_asset_panel', 0, 0, 648, 280, 'panel');
        if (ctx.store.online && isWechat() && !this.identityReady) {
            text(card, this.identityError || '正在识别微信身份…', 28, 64, 592, 160, Theme.font.md,
                this.identityError ? Theme.c.red : Theme.c.ink, { wrap: true });
            this.enter = new Button(this.root, this.identityError ? '重试登录' : '正在登录…', 36, 900, 648, 96,
                'success', () => void this.identify(), Theme.font.lg);
            this.enter.setEnabled(!!this.identityError && !this.busy);
            return;
        }
        text(card, '昵称', 28, 28, 592, 44, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
        this.field = new EditField(card, 28, 88, 592, 64, '输入昵称（最多 ' + NICK_MAX + ' 字）', 20, s => {
            this.nick = s; this.checkNow();
        });
        if (this.nick) this.field.value = this.nick;
        this.hintIcon = gfx(mk(card, 'HintIcon', 28, 184, 28, 28));
        this.hint = text(card, '', 64, 180, 556, 64, 21, Theme.c.inkSoft, { align: 'l', wrap: true });
        this.enter = new Button(this.root, '微信登录', 36, 900, 648, 96, 'success', () => void this.submitLogin(), Theme.font.lg);
        this.checkNow();
    }

    private async submitLogin(): Promise<void> {
        if (this.busy) return;
        const st = ctx.store;
        this.busy = true; this.checkNow();
        try {
            if (st.online && isWechat() && !this.identityReady) {
                const existing = await st.online.wechatQuickLogin();
                if (existing) { ctx.screens.go('lobby'); return; }
            }
            const result = checkNickname(this.nick);
            if (!result.ok) { Toast.show(result.reason); return; }
            const choices = characterChoices();
            const defaultAvatar = choices.find(c => c.id === st.profile.avatar)?.id ?? choices[0]?.id;
            if (defaultAvatar === undefined) { Toast.show('暂无可用人物，请稍后重试'); return; }
            if (st.online) await st.online.login(this.nick.trim(), defaultAvatar);
            else st.setProfile({ nickname: this.nick.trim(), avatar: defaultAvatar, loggedIn: true });
            ctx.screens.go('lobby');
        } catch (e) { Toast.show(wechatError(e as Parameters<typeof wechatError>[0])); }
        finally { this.busy = false; if (this.root.isValid) this.checkNow(); }
    }

    private async saveProfile(): Promise<void> {
        if (this.busy) return;
        const st = ctx.store, result = checkNickname(this.nick);
        if (!result.ok) { Toast.show(result.reason); return; }
        if (!characterChoices().some(c => c.id === this.avatarIdx)) { Toast.show('该人物已停用，请重新选择'); return; }
        this.busy = true; this.checkNow();
        try {
            let nextRoom = false;
            if (st.online) nextRoom = await st.online.updateProfile(this.nick.trim(), this.avatarIdx);
            else st.setProfile({ nickname: this.nick.trim(), avatar: this.avatarIdx });
            ctx.screens.back('lobby');
            Toast.show(nextRoom ? '资料已保存，下次加入房间时生效' : '资料已保存');
        } catch (e) { Toast.show(wechatError(e as Parameters<typeof wechatError>[0])); }
        finally { this.busy = false; if (this.root.isValid) this.checkNow(); }
    }

    private checkNow(): void {
        if (!this.editing && ctx.store.online && isWechat() && !this.identityReady) return;
        const r = checkNickname(this.nick);
        const ok = !this.busy && r.ok;
        const good = !this.nick.trim() || r.ok;
        setText(this.hint, this.nick.trim() ? r.reason : this.editing ? '请输入昵称' : '首次登录请填写昵称，进入后可在左上角修改资料', good ? Theme.c.greenDark : Theme.c.red);
        // 设计稿 03：提示前的圆形对勾（不合法时红色叉）
        const g = this.hintIcon;
        g.clear();
        fillCircle(g, 14, 14, 13, good ? Theme.c.green : Theme.c.red);
        if (good) drawCheck(g, 14, 14, 18, Theme.c.white, 3);
        else {
            line(g, 9, 9, 19, 19, Theme.c.white, 3);
            line(g, 19, 9, 9, 19, Theme.c.white, 3);
        }
        this.enter.setEnabled(ok, this.busy ? '正在保存或登录…' : r.reason);
    }

    /** store 变化时不整页重建，避免打断输入。 */
    refresh(): void {
        if (this.catalogVersion !== characterArtRevision() && EditField.editing === 0) { this.rebuild(); return; }
        this.checkNow();
    }
}

/** 登录失败提示：写明失败在哪一步、具体原因；微信换身份失败时解释错误码。 */
function wechatError(e: { code?: string; wxErrcode?: number; stage?: string; reason?: string } | undefined): string {
    networkLog('login.failed', diagnosticError(e), true);
    const c = e ? e.wxErrcode : undefined;
    if (e && e.code === 'WECHAT_LOGIN_FAILED') {
        if (c === 40029) return '微信登录失败（40029：code 无效，请检查小游戏 AppID 与后台 WECHAT_APPID 是否一致）';
        if (c === 40125) return '微信登录失败（40125：后台 WECHAT_APPSECRET 不正确）';
        if (c === 40013) return '微信登录失败（40013：后台 WECHAT_APPID 不正确）';
        if (c === -1) return '后台连不上微信服务器：' + (e && e.reason ? e.reason : '');
    }
    const where = e && e.stage ? e.stage + '失败' : '登录失败';
    return where + '：' + (e && e.reason ? e.reason : e && e.code ? e.code : '未知错误');
}
