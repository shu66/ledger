const api = require('./utils/api');
const outbox = require('./utils/outbox');

App({
  globalData: {
    me: null,
    categories: [],
    ready: false,
    pending: 0
  },

  onLaunch() {
    this.boot();
  },

  async boot() {
    this.globalData.ready = false;

    if (!wx.getStorageSync('token') && !wx.getStorageSync('skipWx')) {
      try {
        await this.wxLogin();
      } catch (e) {
        if (e.code === 'NEED_BIND') {
          wx.setStorageSync('bindToken', e.bindToken);
        }
        wx.reLaunch({ url: '/pages/login/login' });
        return;
      }
    }

    try {
      const me = await api.get('/auth/me');
      this.globalData.me = me;
      this.globalData.offline = false;
      wx.setStorageSync('me_cache', me);
    } catch (e) {
      const cached = wx.getStorageSync('me_cache');
      /* 断网时不要把人踢下线：用上次缓存的账号先进去，账可以先记在本地 */
      if (outbox.isNetwork(e) && cached) {
        this.globalData.me = cached;
        this.globalData.offline = true;
      } else {
        wx.removeStorageSync('token');
        wx.reLaunch({ url: '/pages/login/login' });
        return;
      }
    }

    await this.refreshCategories();
    this.syncPending();
    if (!this.globalData.offline) {
      await this.flushOutbox(true);
    }

    this.globalData.ready = true;
    if (this.readyCallback) {
      const cb = this.readyCallback;
      this.readyCallback = null;
      cb();
    }
  },

  /** 分类包含系统分类与用户自建分类（AI 也会自动建），每次进页面前刷新一次 */
  async refreshCategories() {
    try {
      this.globalData.categories = await api.get('/api/categories');
      wx.setStorageSync('cat_cache', this.globalData.categories);
    } catch (e) {
      // 拿不到就沿用缓存或旧列表，页面仍可用
      const cached = wx.getStorageSync('cat_cache');
      if (cached && cached.length) this.globalData.categories = cached;
    }
    return this.globalData.categories;
  },

  syncPending() {
    this.globalData.pending = outbox.count();
    return this.globalData.pending;
  },

  /** 把本地排队的账目一起推上去；返回成功条数 */
  async flushOutbox(silent) {
    const list = outbox.all();
    if (!list.length) {
      this.syncPending();
      return 0;
    }

    let done = 0;
    for (const entry of list) {
      try {
        await api.post('/api/transactions/batch', entry.body);
        outbox.remove(entry.id);
        done++;
      } catch (e) {
        if (outbox.isNetwork(e)) break;   // 还是连不上，留着下次
        outbox.remove(entry.id);          // 数据本身被拒，丢弃，避免永远卡住
        if (!silent) wx.showToast({ title: '一条本地账目被拒绝', icon: 'none' });
      }
    }

    this.globalData.pending = outbox.count();
    this.globalData.offline = false;

    if (done && !silent) {
      wx.showToast({ title: '已同步 ' + done + ' 笔', icon: 'success' });
    }
    if (done) {
      this.globalData.syncedAt = Date.now();
    }
    return done;
  },

  catMap() {
    const map = {};
    (this.globalData.categories || []).forEach(c => { map[c.id] = c; });
    return map;
  },

  wxLogin() {
    return new Promise((resolve, reject) => {
      wx.login({
        success: async ({ code }) => {
          try {
            const res = await api.post('/auth/wx/login', { code });
            if (res.needBind) {
              const err = new Error('NEED_BIND');
              err.code = 'NEED_BIND';
              err.bindToken = res.bindToken;
              return reject(err);
            }
            wx.setStorageSync('token', res.token);
            wx.removeStorageSync('skipWx');   // 之后启动可以继续自动登录
            resolve(res);
          } catch (e) {
            reject(e);
          }
        },
        fail: reject
      });
    });
  },

  onReady(cb) {
    if (this.globalData.ready) return cb();
    this.readyCallback = cb;
  }
});
