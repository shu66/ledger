const api = require('../../utils/api');
const fmt = require('../../utils/fmt');
const outbox = require('../../utils/outbox');
const app = getApp();

Page({
  data: {
    type: 'expense',
    amount: '',
    note: '',
    cats: [],
    catId: '',
    date: '',
    dateLabel: '',
    saving: false
  },

  onShow() {
    app.onReady(async () => {
      await app.refreshCategories();
      const today = fmt.todayKey();
      const date = this.data.date || today;
      this.setData({ date: date, dateLabel: fmt.dateLabel(date) });
      this.syncCats(this.data.catId);
    });
  },

  syncCats(keep) {
    const cats = (app.globalData.categories || []).filter(c => c.type === this.data.type);
    const ok = cats.some(c => c.id === keep);
    this.setData({ cats: cats, catId: ok ? keep : (cats[0] ? cats[0].id : '') });
  },

  onType(e) {
    const type = e.currentTarget.dataset.t;
    if (type === this.data.type) return;
    this.setData({ type: type }, () => this.syncCats(''));
  },

  onAmount(e) {
    this.setData({ amount: e.detail.value });
  },

  onNote(e) {
    this.setData({ note: e.detail.value });
  },

  onCat(e) {
    this.setData({ catId: e.currentTarget.dataset.id });
  },

  onDate(e) {
    this.setData({ date: e.detail.value, dateLabel: fmt.dateLabel(e.detail.value) });
  },

  onAddCategory() {
    wx.showModal({
      title: '新建分类',
      editable: true,
      placeholderText: '比如：宠物、健身',
      success: async (r) => {
        if (!r.confirm) return;
        const name = (r.content || '').trim();
        if (!name) return;
        try {
          const c = await api.post('/api/categories', { name: name, type: this.data.type });
          await app.refreshCategories();
          this.syncCats(c.id);
          wx.showToast({ title: '已添加', icon: 'success' });
        } catch (e) {
          const msg = e.code === 'BAD_CATEGORY_NAME' ? '名字太长或为空' : '添加失败';
          wx.showToast({ title: msg, icon: 'none' });
        }
      }
    });
  },

  async onSave() {
    const yuan = parseFloat(this.data.amount);
    if (!Number.isFinite(yuan) || yuan <= 0) {
      return wx.showToast({ title: '先填金额', icon: 'none' });
    }
    if (!this.data.catId) {
      return wx.showToast({ title: '选一个分类', icon: 'none' });
    }

    this.setData({ saving: true });
    const body = {
      items: [{
        type: this.data.type,
        amount: Math.round(yuan * 100),
        category: this.data.catId,
        merchant: null,
        note: this.data.note || null,
        occurredAt: fmt.atNow(this.data.date)
      }],
      text: '',
      source: 'manual'
    };

    try {
      await api.post('/api/transactions/batch', body);
      this.setData({ amount: '', note: '' });
      wx.showToast({ title: '已记一笔', icon: 'success' });
    } catch (e) {
      if (outbox.isNetwork(e)) {
        outbox.add(body);
        app.syncPending();
        this.setData({ amount: '', note: '' });
        wx.showToast({ title: '网络不好，已存本地', icon: 'none' });
        return;
      }
      wx.showToast({ title: e.message || '保存失败', icon: 'none' });
    } finally {
      this.setData({ saving: false });
    }
  }
});
