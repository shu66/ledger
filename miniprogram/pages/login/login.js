const api = require('../../utils/api');
const app = getApp();

Page({
  data: {
    mode: 'bind',        // bind | login | register
    screen: 'wx',        // wx：只显示微信登录；email：邮箱表单
    email: '',
    password: '',
    nickname: '',
    err: '',
    loading: false,
    wxLoading: false,
    bindToken: '',
    focusKey: '',      // 当前聚焦的输入框：email | password | nickname
    topPad: 34         // 自定义导航栏下，让出状态栏高度
  },

  onLoad() {
    const bt = wx.getStorageSync('bindToken') || '';
    let topPad = 34;
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      topPad = (info.statusBarHeight || 24) + 10;
    } catch (e) { /* 取不到就用默认值 */ }
    this.setData({ bindToken: bt, mode: bt ? 'bind' : 'login', topPad, screen: bt ? 'email' : 'wx' });
  },

  /** 切到邮箱表单 */
  toEmail() {
    this.setData({ screen: 'email', mode: 'login', err: '', focusKey: '' });
  },

  /** 从邮箱表单切回微信登录 */
  toWx() {
    const patch = { screen: 'wx', mode: 'login', err: '', focusKey: '', password: '' };
    if (this.data.bindToken) {
      wx.removeStorageSync('bindToken');
      patch.bindToken = '';
    }
    this.setData(patch);
  },

  /** 微信一键登录：已绑定直接进，未绑定转到绑定邮箱 */
  async onWxLogin() {
    if (this.data.wxLoading) return;
    this.setData({ wxLoading: true, err: '' });
    try {
      await app.wxLogin();               // 成功时内部已写入 token
      wx.removeStorageSync('bindToken');
      wx.removeStorageSync('skipWx');
      await app.boot();
      wx.reLaunch({ url: '/pages/index/index' });
    } catch (e) {
      if (e.code === 'NEED_BIND') {
        wx.setStorageSync('bindToken', e.bindToken);
        this.setData({ screen: 'email', mode: 'bind', bindToken: e.bindToken, password: '', err: '', focusKey: '' });
      } else {
        this.setData({ err: this.errText(e) });
      }
    } finally {
      this.setData({ wxLoading: false });
    }
  },

  errText(e) {
    const map = {
      BAD_CREDENTIALS: '邮箱或密码不正确',
      EMAIL_TAKEN: '这个邮箱已注册，直接登录即可',
      WEAK_PASSWORD: '密码至少 8 位',
      EMAIL_INVALID: '邮箱格式不对',
      BIND_TOKEN_INVALID: '绑定已超时，请重新点微信登录',
      WECHAT_ALREADY_BOUND: '这个微信已绑定其他账号',
      ACCOUNT_DISABLED: '账号已停用',
      TOO_MANY: '试得太频繁，稍后再试',
      WX_LOGIN_FAILED: '微信登录失败，检查后台 WX_APPID / WX_SECRET',
      WX_NOT_CONFIGURED: '后台还没配微信登录（WX_APPID / WX_SECRET）',
      WX_UPSTREAM: '连不上微信服务器，稍后再试',
      NETWORK: '连不上服务器，检查网络或后端地址'
    };
    if (e.code === 'WX_NOT_CONFIGURED' || e.code === 'WX_UPSTREAM' || e.code === 'WX_LOGIN_FAILED') {
      return e.message || map[e.code];
    }
    return map[e.code] || ('失败：' + (e.message || '未知错误'));
  },

  onInput(e) {
    this.setData({ [e.currentTarget.dataset.k]: e.detail.value });
  },

  /* 整块输入区都能点：第一次点就聚焦并弹出键盘，
     不再出现「要点两次才能输入」的情况 */
  onFieldTap(e) {
    const k = e.currentTarget.dataset.k;
    if (k && this.data.focusKey !== k) this.setData({ focusKey: k });
  },

  onFocus(e) {
    const k = e.currentTarget.dataset.k;
    if (k && this.data.focusKey !== k) this.setData({ focusKey: k });
  },

  onBlur(e) {
    const k = e.currentTarget.dataset.k;
    // 只有「当前聚焦的正好是它」时才清空，避免点另一个输入框时把新的焦点又清掉
    if (k && this.data.focusKey === k) this.setData({ focusKey: '' });
  },

  switchMode(e) {
    const mode = e.currentTarget.dataset.m;
    const patch = { mode, err: '', focusKey: '' };
    // 从「绑定微信」切到邮箱登录时放弃这次绑定，避免误绑到另一个账号
    if (mode === 'login' && this.data.bindToken) {
      wx.removeStorageSync('bindToken');
      patch.bindToken = '';
    }
    this.setData(patch);
  },

  async submit() {
    const { mode, email, password, nickname, bindToken } = this.data;
    if (!email || !password) {
      return this.setData({ err: '邮箱和密码都要填' });
    }
    wx.hideKeyboard();
    this.setData({ loading: true, err: '', focusKey: '' });

    try {
      let token;

      if (mode === 'register') {
        const r = await api.post('/auth/register', { email, password, nickname });
        token = r.token;
        // 刚注册的账号顺手绑上当前微信，失败不阻断登录
        if (bindToken) {
          try {
            await api.post('/auth/wx/bind', { bindToken, email, password });
          } catch (e) { /* ignore */ }
        }
      } else if (bindToken) {
        // bind 模式，以及「已有账号」从 bind 切到登录的情况：一次完成登录 + 绑微信
        const r = await api.post('/auth/wx/bind', { bindToken, email, password });
        token = r.token;
      } else {
        const r = await api.post('/auth/login', { email, password });
        token = r.token;
      }

      wx.setStorageSync('token', token);
      wx.removeStorageSync('bindToken');
      wx.removeStorageSync('skipWx');

      await app.boot();
      wx.reLaunch({ url: '/pages/index/index' });
    } catch (e) {
      this.setData({ err: this.errText(e) });
    } finally {
      this.setData({ loading: false });
    }
  },

  skipBind() {
    wx.removeStorageSync('bindToken');
    // 不跳首页：此时还没有 token，跳过去会再次触发 NEED_BIND 被弹回登录页。
    // 记一个标记让 app.boot() 别再自动 wx.login，并留在本页切到邮箱登录。
    wx.setStorageSync('skipWx', true);
    this.setData({ mode: 'login', password: '', err: '' });
  }
});
