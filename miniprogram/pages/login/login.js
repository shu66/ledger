const api = require('../../utils/api');
const app = getApp();

Page({
  data: {
    mode: 'bind',        // bind | login | register
    email: '',
    password: '',
    nickname: '',
    err: '',
    loading: false,
    bindToken: ''
  },

  onLoad() {
    const bt = wx.getStorageSync('bindToken') || '';
    this.setData({ bindToken: bt, mode: bt ? 'bind' : 'login' });
  },

  onInput(e) {
    this.setData({ [e.currentTarget.dataset.k]: e.detail.value });
  },

  switchMode(e) {
    this.setData({ mode: e.currentTarget.dataset.m, err: '' });
  },

  async submit() {
    const { mode, email, password, nickname, bindToken } = this.data;
    if (!email || !password) {
      return this.setData({ err: '邮箱和密码都要填' });
    }
    this.setData({ loading: true, err: '' });

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
      const map = {
        BAD_CREDENTIALS: '邮箱或密码不正确',
        EMAIL_TAKEN: '这个邮箱已注册，直接登录即可',
        WEAK_PASSWORD: '密码至少 8 位',
        EMAIL_INVALID: '邮箱格式不对',
        BIND_TOKEN_INVALID: '绑定已超时，请重新进入小程序',
        WECHAT_ALREADY_BOUND: '这个微信已绑定其他账号',
        ACCOUNT_DISABLED: '账号已停用',
        TOO_MANY: '试得太频繁，稍后再试',
        NETWORK: '连不上服务器'
      };
      this.setData({ err: map[e.code] || ('失败：' + e.message) });
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
