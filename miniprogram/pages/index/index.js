const api = require('../../utils/api');
const fmt = require('../../utils/fmt');
const outbox = require('../../utils/outbox');
const app = getApp();

const EXAMPLES = ['午饭面馆 25', '打车去公司 18.5', '卖闲置收入 120'];

Page({
  data: {
    me: {},
    text: '',
    pendingText: '',
    loading: false,
    cards: [],
    recent: [],
    catMap: {},
    monthOut: '0.00',
    monthIn: '0.00',
    monthCount: 0,
    todayLabel: '',
    examples: EXAMPLES,
    raw: '',
    model: '',
    edited: false,
    pending: 0,
    picker: { show: false, i: 0, cats: [], cur: '' }
  },

  onShow() {
    app.onReady(async () => {
      this.setData({
        me: app.globalData.me || {},
        todayLabel: fmt.todayKey().slice(5).replace('-', '月') + '日',
        pending: app.syncPending()
      });
      await app.refreshCategories();
      this.setData({ catMap: app.catMap() });
      if (app.globalData.pending > 0) {
        await app.flushOutbox(true);
        this.setData({ pending: app.globalData.pending });
      }
      this.loadHome();
    });
  },

  async onSyncNow() {
    if (!app.globalData.pending) return;
    wx.showLoading({ title: '同步中' });
    const n = await app.flushOutbox(true);
    wx.hideLoading();
    this.setData({ pending: app.globalData.pending });
    if (n) {
      wx.showToast({ title: '已同步 ' + n + ' 笔', icon: 'success' });
      this.loadHome();
    } else {
      wx.showToast({ title: '暂时还连不上', icon: 'none' });
    }
  },

  onInput(e) {
    this.setData({ text: e.detail.value });
  },

  onExample(e) {
    this.setData({ text: e.currentTarget.dataset.t });
  },

  async onSubmit() {
    const text = (this.data.text || '').trim();
    if (!text || this.data.loading) return;

    this.setData({ loading: true, cards: [] });
    try {
      const res = await api.post('/api/parse', { text });
      const items = res.items || [];
      if (!items.length) {
        wx.showToast({ title: '没看出是记账', icon: 'none' });
        return;
      }

      // AI 可能顺手建了新分类，刷新后才能显示分类名
      if (res.createdCategories && res.createdCategories.length) {
        await app.refreshCategories();
        this.setData({ catMap: app.catMap() });
        wx.showToast({ title: 'AI 新建分类：' + res.createdCategories.join('、'), icon: 'none' });
      }

      this.setData({
        cards: this.decorate(items),
        pendingText: text,
        raw: res.raw || '',
        model: res.model || '',
        edited: false,
        text: ''
      });
    } catch (e) {
      const msg = e.code === 'NO_LLM_CONFIG' ? '管理员还没配置 AI 模型' : '解析失败，请重试';
      wx.showToast({ title: msg, icon: 'none' });
    } finally {
      this.setData({ loading: false });
    }
  },

  decorate(items) {
    const map = this.data.catMap;
    return items.map(it => {
      const c = map[it.category] || {};
      return Object.assign({}, it, {
        catName: c.name || it.category,
        icon: c.icon || '✨',
        amountInput: it.amount ? String(it.amount / 100) : '',
        warn: (it.flags && it.flags.inText === false) || (typeof it.confidence === 'number' && it.confidence < 0.45)
      });
    });
  },

  onAmount(e) {
    const i = e.currentTarget.dataset.i;
    const v = parseFloat(e.detail.value);
    const cards = this.data.cards.slice();
    cards[i] = Object.assign({}, cards[i], {
      amount: Number.isFinite(v) ? Math.round(v * 100) : null,
      amountInput: e.detail.value
    });
    this.setData({ cards, edited: true });
  },

  onPickCategory(e) {
    const i = e.currentTarget.dataset.i;
    const type = this.data.cards[i].type;
    const cats = (app.globalData.categories || []).filter(c => c.type === type);
    if (!cats.length) return;

    this.setData({
      picker: {
        show: true,
        i,
        cats,
        cur: this.data.cards[i].category
      }
    });
  },

  closePicker() {
    this.setData({ 'picker.show': false });
  },

  pickCat(e) {
    const id = e.currentTarget.dataset.id;
    const { i, cats } = this.data.picker;
    const c = cats.find(x => x.id === id);
    if (!c) return;

    const cards = this.data.cards.slice();
    cards[i] = Object.assign({}, cards[i], {
      category: c.id,
      catName: c.name,
      icon: c.icon || '✨'
    });
    this.setData({ cards, edited: true, 'picker.show': false });
  },

  // 解析结果里找不到合适分类时，当场新建一个（建完自动选中）
  async newCatInPicker() {
    const { i } = this.data.picker;
    const card = this.data.cards[i];
    if (!card) return;

    const res = await new Promise(resolve => {
      wx.showModal({
        title: '新建分类',
        editable: true,
        placeholderText: '例如：宠物、装修',
        success: resolve,
        fail: () => resolve({ confirm: false })
      });
    });
    if (!res.confirm) return;

    const name = (res.content || '').trim();
    if (!name) return wx.showToast({ title: '请输入分类名称', icon: 'none' });

    try {
      const c = await api.post('/api/categories', {
        name,
        type: card.type,
        icon: '🏷️'
      });
      await app.refreshCategories();
      const found = (app.globalData.categories || []).find(x => x.id === c.id) || c;

      const cards = this.data.cards.slice();
      cards[i] = Object.assign({}, cards[i], {
        category: found.id,
        catName: found.name,
        icon: found.icon || '🏷️'
      });
      this.setData({
        cards,
        edited: true,
        picker: { show: false, i: 0, cats: [], cur: '' }
      });
      wx.showToast({ title: '已新建并选用', icon: 'none' });
    } catch (err) {
      wx.showToast({ title: err.message || '新建失败', icon: 'none' });
    }
  },

  onDrop(e) {
    const i = e.currentTarget.dataset.i;
    const cards = this.data.cards.slice();
    cards.splice(i, 1);
    this.setData({ cards, edited: true });
  },

  async onConfirm() {
    const cards = this.data.cards.filter(c => Number.isInteger(c.amount) && c.amount > 0);
    if (!cards.length) {
      return wx.showToast({ title: '金额还没填', icon: 'none' });
    }

    const items = cards.map(c => ({
      type: c.type,
      amount: c.amount,
      category: c.category,
      merchant: c.merchant || null,
      note: c.note || null,
      occurredAt: c.occurredAt
    }));

    const body = {
      items: items,
      text: this.data.pendingText,
      raw: this.data.raw,
      model: this.data.model,
      edited: this.data.edited,
      source: 'text'
    };

    try {
      await api.post('/api/transactions/batch', body);
      this.setData({ cards: [], pendingText: '', raw: '', model: '', edited: false });
      wx.showToast({ title: '已入账', icon: 'success' });
      this.loadHome();
    } catch (e) {
      if (outbox.isNetwork(e)) {
        outbox.add(body);
        app.syncPending();
        this.setData({ cards: [], pendingText: '', raw: '', model: '', edited: false, pending: app.globalData.pending });
        wx.showToast({ title: '网络不好，已存本地', icon: 'none' });
        return;
      }
      wx.showToast({ title: e.message || '保存失败', icon: 'none' });
    }
  },

  async loadHome() {
    try {
      const rows = await api.get('/api/transactions');
      const sum = await api.get('/api/summary', { month: fmt.thisMonth() });
      let out = 0, inc = 0, n = 0;
      sum.forEach(s => {
        if (s.type === 'income') inc += s.total; else out += s.total;
        n += s.n;
      });
      this.setData({
        recent: rows.slice(0, 5).map(r => this.row(r)),
        monthOut: fmt.yuan(out),
        monthIn: fmt.yuan(inc),
        monthCount: n
      });
    } catch (e) { /* 静默，页面其余部分仍可用 */ }
  },

  row(r) {
    const c = this.data.catMap[r.category] || {};
    return Object.assign({}, r, {
      catName: c.name || r.category,
      icon: c.icon || '✨',
      amountYuan: fmt.yuan(r.amount),
      time: fmt.hhmm(r.occurredAt),
      day: fmt.dayLabel(fmt.dayKeyOf(r.occurredAt))
    });
  },

  goBill() {
    wx.switchTab({ url: '/pages/bill/bill' });
  }
});
