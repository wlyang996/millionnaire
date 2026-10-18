const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture(quickLogin) {
    const routes = [], views = [];
    const ctx = {store: {online: {wechatQuickLogin: quickLogin}}, screens: {go: id => routes.push(id)}};
    const exports = {};
    vm.runInNewContext(fs.readFileSync(require.resolve('../temp/profile-startup/screens/ProfileScreen.js'), 'utf8'), {
        exports, require: name => {
            if (name === '../ui/Screen') return {Screen: class {}};
            if (name === '../ui/Ctx') return {ctx};
            if (name === '../net/Wx') return {isWechat: () => true};
            if (name === '../net/Diagnostics') return {networkLog() {}, diagnosticError: e => e};
            return {};
        }
    });
    const screen = new exports.ProfileScreen();
    screen.root = {isValid: true};
    screen.rebuild = () => views.push({ready: screen.identityReady, error: screen.identityError, busy: screen.busy});
    ctx.screens.current = screen;
    return {screen, routes, views, ctx};
}

test('returning user enters the lobby without entering a nickname', async () => {
    const {screen, routes, views} = fixture(async () => ({nickname: '已保存昵称', avatar: 1}));
    await screen.identify();
    assert.deepEqual(routes, ['lobby']);
    assert(views.every(v => !v.ready));
});

test('only a confirmed new identity opens nickname entry', async () => {
    let resolve;
    const {screen, routes} = fixture(() => new Promise(r => {resolve = r;}));
    const pending = screen.identify();
    assert.equal(screen.identityReady, false);
    resolve(null);
    await pending;
    assert.equal(screen.identityReady, true);
    assert.deepEqual(routes, []);
});

test('network failure keeps registration hidden and retry can recover the existing user', async () => {
    let attempts = 0;
    const {screen, routes} = fixture(async () => {
        if (++attempts === 1) throw {stage: '微信登录', code: 'NETWORK_ERROR'};
        return {nickname: '已保存昵称', avatar: 1};
    });
    await screen.identify();
    assert.equal(screen.identityReady, false);
    assert.match(screen.identityError, /NETWORK_ERROR/);
    assert.equal(screen.busy, false);
    await screen.identify();
    assert.deepEqual(routes, ['lobby']);
});

test('duplicate checks are ignored and an inactive screen does not redirect', async () => {
    let resolve, calls = 0;
    const {screen, routes, ctx} = fixture(() => {calls++; return new Promise(r => {resolve = r;});});
    const pending = screen.identify();
    await screen.identify();
    assert.equal(calls, 1);
    ctx.screens.current = null;
    resolve({nickname: '原昵称', avatar: 1});
    await pending;
    assert.deepEqual(routes, []);
});
