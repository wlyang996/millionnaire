/** 页面 1：登录资料（微信登录 / 昵称输入与检查 / 内置头像选择）。 */
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
import { art, AVATAR_NAMES, informationCharacterKey } from '../ui/Art';
import { isWechat } from '../net/Wx';

export class ProfileScreen extends Screen {
    readonly id = 'profile' as const;
    readonly title = '登录资料';
    private nick = '';
    private avatarIdx = 0;
    private initialized = false;
    private hint!: Label;
    private hintIcon!: Graphics;
    private enter!: Button;
    private field!: EditField;
    private busy = false;

    protected build(): void {
        const st = ctx.store;
        this.backdrop('sky');
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        if (!this.initialized) {
            this.nick = st.profile.nickname;
            this.avatarIdx = st.profile.avatar;
            this.initialized = true;
        }
        // 设计稿 03：品牌 Logo（好友桌游 + 骰子）
        art(this.root, 'brand_logo', 100, 112, 520, 331);

        const card = mk(this.root, 'ProfilePanel', 36, 612, 648, 646);
        art(card, 'info_asset_panel', 0, 0, 648, 646, 'panel');
        text(card, '登录资料', 28, 12, 592, 56, 38, '#613C24', { bold: true });
        // 标题两侧的绿叶
        const leaves = gfx(mk(card, 'Leaves', 0, 0, 648, 70));
        for (const [cx, dir] of [[218, -1], [430, 1]] as [number, number][]) {
            fillPoly(leaves, [[cx, 54], [cx + dir * 14, 24], [cx + dir * 30, 14], [cx + dir * 24, 40]], '#5DAE45');
            fillPoly(leaves, [[cx, 54], [cx + dir * 30, 40], [cx + dir * 44, 46], [cx + dir * 24, 56]], '#7CC75E');
        }

        // 微信登录
        const logged = st.profile.loggedIn;
        new Button(this.root, logged ? '已登录 · 微信用户 ✓' : '微信登录', 36, 500, 648, 92, logged ? 'ghost' : 'success', () => {
            if (st.profile.loggedIn || this.busy) return;
            if (st.online && isWechat()) return this.wechatLogin();
            st.setProfile({ loggedIn: true });
            if (!this.nick && !st.online) this.nick = '微信用户';
            Toast.show(st.online ? '测试登录：填写昵称后保存即可联机' : '微信登录成功（演示，未连接微信）');
            this.rebuild();
        }, Theme.font.lg);

        // 头像
        text(card, '内置头像', 28, 222, 400, 36, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        for (let i = 0; i < 8; i++) {
            const cx = 28 + (i % 4) * 150;
            const cy = 260 + Math.floor(i / 4) * 128;
            const cell = mk(card, 'AvatarCell', cx, cy, 138, 120);
            const sel = i === this.avatarIdx;
            // 设计稿 03：圆角方形人物卡（头像底色的浅色 + 半身人物图），下方名字
            fillRR(gfx(cell), 0, 0, 138, 120, 18, sel ? Theme.c.white : '#F2E9D6');
            const tile = mk(cell, 'Tile', 22, 4, 94, 88);
            fillRR(gfx(tile), 0, 0, 94, 88, 16, Theme.avatarColors[i % 8] + '55');
            if (!art(tile, informationCharacterKey(i), 4, 2, 86, 86)) avatar(cell, 24, 4, 90, i, AVATAR_NAMES[i]);
            text(cell, AVATAR_NAMES[i], 0, 94, 138, 26, Theme.font.xs, sel ? Theme.c.blueDark : Theme.c.ink, { bold: sel });
            if (sel) {
                const gg = gfx(mk(cell, 'Sel', 0, 0, 138, 120));
                strokeRR(gg, 0, 0, 138, 120, 18, Theme.c.blue, 4);
                fillCircle(gg, 115, 88, 15, Theme.c.blue);
                drawCheck(gg, 115, 88, 30, Theme.c.white, 4);
            }
            onTap(cell, () => {
                this.avatarIdx = i;
                this.rebuild();
            }, false);
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


        this.enter = primaryButton(card, '保存资料', 28, 520, 592, 96, () => {
            const r = checkNickname(this.nick);
            if (!st.profile.loggedIn) return Toast.show('请先微信登录');
            if (!r.ok) return Toast.show(r.reason);
            st.setProfile({ nickname: this.nick.trim(), avatar: this.avatarIdx });
            if (!st.online) return ctx.screens.go('lobby');
            // 联机：微信里用 wx.login（带上昵称与头像），其他环境用测试身份，然后连接服务器
            this.enter.setEnabled(false, '正在连接服务器…');
            st.online.login(this.nick.trim(), this.avatarIdx).then(() => ctx.screens.go('lobby'), (e: { code?: string; wxErrcode?: number }) => {
                Toast.show(e && e.code === 'INVALID_NICKNAME' ? '昵称不合法，请换一个' : wechatError(e));
                this.checkNow();
            });
        });
        this.checkNow();
    }

    /** 微信里：wx.login 换身份；老用户直接进大厅（沿用服务端存的昵称、头像），新用户留在本页填资料。 */
    private wechatLogin(): void {
        const st = ctx.store;
        this.busy = true;
        st.online!.wechatQuickLogin().then((p) => {
            this.busy = false;
            st.setProfile({ loggedIn: true });
            if (p) {
                st.setProfile({ nickname: p.nickname, avatar: p.avatar >= 0 ? p.avatar : st.profile.avatar });
                Toast.show('欢迎回来，' + p.nickname);
                ctx.screens.go('lobby');
                return;
            }
            Toast.show('微信登录成功，请填写昵称并选择头像');
            this.rebuild();
        }, (e: { code?: string; wxErrcode?: number }) => {
            this.busy = false;
            Toast.show(wechatError(e));
        });
    }

    private checkNow(): void {
        const r = checkNickname(this.nick);
        const ok = r.ok && ctx.store.profile.loggedIn;
        const good = !this.nick.trim() || r.ok;
        setText(this.hint, this.nick.trim() ? r.reason : '昵称需检查，检查通过后可继续', good ? Theme.c.greenDark : Theme.c.red);
        // 设计稿 03：提示前的圆形对勾（不合法时红色叉）
        const g = this.hintIcon;
        g.clear();
        fillCircle(g, 14, 14, 13, good ? Theme.c.green : Theme.c.red);
        if (good) drawCheck(g, 14, 14, 18, Theme.c.white, 3);
        else {
            line(g, 9, 9, 19, 19, Theme.c.white, 3);
            line(g, 19, 9, 9, 19, Theme.c.white, 3);
        }
        this.enter.setEnabled(ok, ctx.store.profile.loggedIn ? r.reason : '请先微信登录');
    }

    /** store 变化时不整页重建，避免打断输入。 */
    refresh(): void {
        this.checkNow();
    }
}

/** 登录失败提示：写明失败在哪一步、具体原因；微信换身份失败时解释错误码。 */
function wechatError(e: { code?: string; wxErrcode?: number; stage?: string; reason?: string } | undefined): string {
    console.error('[login] failed', e);
    const c = e ? e.wxErrcode : undefined;
    if (e && e.code === 'WECHAT_LOGIN_FAILED') {
        if (c === 40029) return '微信登录失败（40029：code 无效，请检查小游戏 AppID 与后台 WECHAT_APPID 是否一致）';
        if (c === 40125) return '微信登录失败（40125：后台 WECHAT_APPSECRET 不正确）';
        if (c === 40013) return '微信登录失败（40013：后台 WECHAT_APPID 不正确）';
        if (c === -1) return '微信登录失败（后台连不上微信服务器）';
    }
    const where = e && e.stage ? e.stage + '失败' : '登录失败';
    return where + '：' + (e && e.reason ? e.reason : e && e.code ? e.code : '未知错误');
}
