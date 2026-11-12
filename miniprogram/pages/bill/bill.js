const api = require('../../utils/api');
const fmt = require('../../utils/fmt');
const outbox = require('../../utils/outbox');
const app = getApp();

/* ---------- 本地日期工具（避免时区漂移，统一按 UTC 算天数） ---------- */
function pad(n) { return String(n).padStart(2, '0'); }
function iso(d) { return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()); }
function parseIso(s) { const p = s.split('-').map(Number); return new Date(Date.UTC(p[0], p[1] - 1, p[2])); }
function addDays(s, n) { const d = parseIso(s); d.setUTCDate(d.getUTCDate() + n); return iso(d); }
function mondayOf(s) { const d = parseIso(s); const w = (d.getUTCDay() + 6) % 7; d.setUTCDate(d.getUTCDate() - w); return iso(d); }
function monthFirst(s) { return s.slice(0, 7) + '-01'; }
function monthLast(s) { const p = s.split('-').map(Number); return iso(new Date(Date.UTC(p[0], p[1], 0))); }

const PERIODS = [
  { key: 'day', label: '今天' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
  { key: 'year', label: '今年' }
];

Page({
  data: {
    periods: PERIODS,
    period: 'month',
    anchor: '',
    from: '',
    to: '',
    label: '',
    q: '',
    custom: false,
    view: 'list',          // list | stat
    cat: '',               // 分类筛选
    catName: '',
    groups: [],
    tops: [],
    statCats: [],
    trend: [],
    dayChart: [],
    showDays: false,
    maxDayYuan: '0.00',
    out: '0.00',
    in: '0.00',
    net: '0.00',
    count: 0,
    loading: false,
    pending: 0,
    catMap: {},
    picker: { show: false, id: 0, type: 'expense', cats: [] }
  },

  onShow() {
    app.onReady(async () => {
      await app.refreshCategories();
      if (!this.data.anchor) {
        const today = fmt.todayKey();
        this.setData({ anchor: today });
        this.applyRange('month', today);
      }
      this.setData({ catMap: app.catMap(), pending: app.syncPending() });
      if (app.globalData.pending > 0) {
        await app.flushOutbox(true);
        this.setData({ pending: app.globalData.pending });
      }
      this.load();
    });
  },

  /* ---------- 区间 ---------- */
  rangeOf(period, anchor) {
    if (period === 'day') return { from: anchor, to: anchor };
    if (period === 'week') { const from = mondayOf(anchor); return { from, to: addDays(from, 6) }; }
    if (period === 'year') return { from: anchor.slice(0, 4) + '-01-01', to: anchor.slice(0, 4) + '-12-31' };
    return { from: monthFirst(anchor), to: monthLast(anchor) };
  },

  applyRange(period, anchor) {
    const r = this.rangeOf(period, anchor);
    this.setData({
      period: period,
      from: r.from,
      to: r.to,
      custom: false,
      label: this.labelOf(period, r)
    });
  },

  labelOf(period, r) {
    if (period === 'day') return r.from.slice(5).replace('-', '月') + '日';
    if (period === 'week') return r.from.slice(5).replace('-', '/') + ' – ' + r.to.slice(5).replace('-', '/');
    if (period === 'year') return r.from.slice(0, 4) + ' 年';
    return r.from.slice(0, 4) + ' 年 ' + Number(r.from.slice(5, 7)) + ' 月';
  },

  setPeriod(e) {
    const p = e.currentTarget.dataset.p;
    if (p === this.data.period && !this.data.custom) return;
    this.applyRange(p, this.data.anchor || fmt.todayKey());
    this.load();
  },

  shift(e) {
    if (this.data.custom) return;   // 自定义区间用日期选择器，箭头不参与
    const delta = Number(e.currentTarget.dataset.d);
    let anchor = this.data.anchor || fmt.todayKey();
    if (this.data.period === 'day') anchor = addDays(anchor, delta);
    else if (this.data.period === 'week') anchor = addDays(anchor, delta * 7);
    else if (this.data.period === 'year') anchor = (Number(anchor.slice(0, 4)) + delta) + anchor.slice(4);
    else {
      const p = anchor.split('-').map(Number);
      const d = new Date(Date.UTC(p[0], p[1] - 1 + delta, 1));
      anchor = iso(d);
    }
    if (anchor > fmt.todayKey()) return;
    this.setData({ anchor: anchor });
    this.applyRange(this.data.period, anchor);
    this.load();
  },

  onCustom() {
    this.setData({ custom: true });
  },

  onFrom(e) {
    this.setData({ custom: true, from: e.detail.value });
    this.afterCustom();
  },

  onTo(e) {
    this.setData({ custom: true, to: e.detail.value });
    this.afterCustom();
  },

  afterCustom() {
    if (this.data.from && this.data.to && this.data.from <= this.data.to) {
      this.setData({ label: this.data.from.slice(5).replace('-', '/') + ' – ' + this.data.to.slice(5).replace('-', '/') });
      this.load();
    }
  },

  onSearch(e) {
    const q = e.detail.value;
    this.setData({ q: q });
    if (this._t) clearTimeout(this._t);
    this._t = setTimeout(() => this.load(), 320);
  },

  clearSearch() {
    if (this._t) clearTimeout(this._t);
    this.setData({ q: '' });
    this.load();
  },

  async load() {
    this.setData({ loading: true });
    try {
      const params = { from: this.data.from, to: this.data.to, limit: 1000 };
      if (this.data.q) params.q = this.data.q;
      if (this.data.cat) params.category = this.data.cat;
      const [rows, rep] = await Promise.all([
        api.get('/api/transactions', params),
        api.get('/api/report', { from: this.data.from, to: this.data.to }).catch(() => null)
      ]);
      const days = new Map();
      const cats = new Map();
      let out = 0, inc = 0;

      rows.forEach(r => {
        const day = fmt.dayKeyOf(r.occurredAt);
        if (!days.has(day)) {
          days.set(day, {
            day: day,
            label: fmt.dayLabel(day),
            weekday: fmt.weekday(day),
            items: [],
            out: 0
          });
        }
        const c = this.data.catMap[r.category] || {};
        const g = days.get(day);
        g.items.push({
          id: r.id,
          type: r.type,
          category: r.category,
          catName: c.name || r.category,
          icon: c.icon || '✨',
          title: r.note || r.merchant || c.name || r.category,
          time: fmt.hhmm(r.occurredAt),
          amountYuan: fmt.yuan(r.amount)
        });

        if (r.type === 'income') {
          inc += r.amount;
        } else {
          out += r.amount;
          g.out += r.amount;
        }

        const key = r.type + '|' + r.category;
        const cur = cats.get(key) || {
          type: r.type,
          category: r.category,
          name: c.name || r.category,
          icon: c.icon || '✨',
          total: 0
        };
        cur.total += r.amount;
        cats.set(key, cur);
      });

      const topList = Array.from(cats.values())
        .filter(c => c.type === 'expense')
        .sort((a, b) => b.total - a.total)
        .slice(0, 4);
      const maxTotal = topList.length ? topList[0].total : 1;
      const tops = topList.map(c => ({
        category: c.category,
        name: c.name,
        icon: c.icon,
        totalYuan: fmt.yuan(c.total),
        pct: Math.max(8, Math.round(c.total / maxTotal * 100))
      }));

      this.setData({
        groups: Array.from(days.values()).map(g => Object.assign(g, { outYuan: fmt.yuan(g.out) })),
        tops: tops,
        out: fmt.yuan(out),
        in: fmt.yuan(inc),
        net: fmt.yuan(inc - out),
        count: rows.length,
        ...this.buildStats(rep || {})
      });
    } catch (e) {
      if (!outbox.isNetwork(e)) wx.showToast({ title: '加载失败', icon: 'none' });
    } finally {
      this.setData({ loading: false });
    }
  },

  /* ---------- 统计视图 ---------- */
  buildStats(rep) {
    const exp = rep.expense || 0;
    const catMap = this.data.catMap;
    const statCats = (rep.byCategory || [])
      .filter(c => c.type === 'expense')
      .sort((a, b) => b.total - a.total)
      .slice(0, 12)
      .map(c => {
        const m = catMap[c.category] || {};
        return {
          category: c.category,
          name: m.name || c.category,
          icon: m.icon || '✨',
          totalYuan: fmt.yuan(c.total),
          n: c.n,
          pct: exp ? Math.round(c.total / exp * 1000) / 10 : 0
        };
      });
    const maxPct = statCats.length ? statCats[0].pct : 0;
    statCats.forEach(c => { c.w = maxPct ? Math.max(3, Math.round(c.pct / maxPct * 100)) : 0; });

    const trend = (rep.trend || []).map(t => ({
      label: Number(t.month.slice(5)) + '月',
      expense: t.expense,
      income: t.income
    }));
    const maxT = Math.max(1, ...trend.map(t => Math.max(t.expense, t.income)));
    trend.forEach(t => {
      t.eh = Math.max(2, Math.round(t.expense / maxT * 100));
      t.ih = Math.max(2, Math.round(t.income / maxT * 100));
    });

    const days = rep.byDay || [];
    const showDays = days.length > 0 && days.length <= 31;
    const maxD = Math.max(1, ...days.map(d => d.expense));
    const dayChart = showDays ? days.map(d => {
      const n = Number(d.day.slice(8));
      return {
        label: (n === 1 || n % 5 === 0) ? String(n) : '',
        h: Math.max(2, Math.round(d.expense / maxD * 100))
      };
    }) : [];

    return {
      statCats: statCats,
      trend: trend,
      dayChart: dayChart,
      showDays: showDays,
      maxDayYuan: fmt.yuan(days.length ? maxD : 0)
    };
  },

  setView(e) {
    this.setData({ view: e.currentTarget.dataset.v });
  },

  filterCat(e) {
    const cat = e.currentTarget.dataset.cat;
    const name = e.currentTarget.dataset.name;
    this.setData({ cat: cat, catName: name, view: 'list' });
    this.load();
  },

  clearCat() {
    this.setData({ cat: '', catName: '' });
    this.load();
  },

  /* ---------- 单笔操作：改金额 / 改分类 / 删除 ---------- */
  onTapItem(e) {
    const id = e.currentTarget.dataset.id;
    const row = this.findRow(id);
    if (!row) return;

    wx.showActionSheet({
      itemList: ['修改金额', '修改分类', '删除这笔'],
      success: (res) => {
        if (res.tapIndex === 0) this.editAmount(row);
        else if (res.tapIndex === 1) this.openCat(row);
        else this.confirmDelete(id);
      }
    });
  },

  findRow(id) {
    for (const g of this.data.groups) {
      const hit = g.items.find(x => x.id === id);
      if (hit) return hit;
    }
    return null;
  },

  editAmount(row) {
    const cur = (row.amountYuan || '').replace(/,/g, '');
    wx.showModal({
      title: '修改金额',
      editable: true,
      content: cur,
      placeholderText: '例如 25.50',
      confirmColor: '#D9552F',
      success: async (r) => {
        if (!r.confirm) return;
        const yuan = parseFloat(r.content);
        if (!Number.isFinite(yuan) || yuan <= 0) {
          return wx.showToast({ title: '金额不对', icon: 'none' });
        }
        await this.patch(row.id, { amount: Math.round(yuan * 100) });
      }
    });
  },

  openCat(row) {
    const cats = (app.globalData.categories || []).filter(c => c.type === row.type);
    this.setData({ picker: { show: true, id: row.id, type: row.type, cats: cats } });
  },

  closePicker() {
    this.setData({ 'picker.show': false });
  },

  async pickCat(e) {
    const cat = e.currentTarget.dataset.id;
    const id = this.data.picker.id;
    this.setData({ 'picker.show': false });
    await this.patch(id, { category: cat });
  },

  async patch(id, patch) {
    try {
      await api.patch('/api/transactions/' + id, patch);
      wx.showToast({ title: '已更新', icon: 'success' });
      this.load();
    } catch (e) {
      if (outbox.isNetwork(e)) {
        wx.showToast({ title: '连不上服务器，稍后改', icon: 'none' });
      } else {
        wx.showToast({ title: e.message || '修改失败', icon: 'none' });
      }
    }
  },

  confirmDelete(id) {
    wx.showModal({
      title: '删除这笔？',
      content: '删除后不计入统计',
      confirmColor: '#D9552F',
      success: async (r) => {
        if (!r.confirm) return;
        try {
          await api.del('/api/transactions/' + id);
          wx.showToast({ title: '已删除', icon: 'none' });
          this.load();
        } catch (e) {
          wx.showToast({ title: '删除失败', icon: 'none' });
        }
      }
    });
  },

  async onSyncNow() {
    if (!app.globalData.pending) return;
    wx.showLoading({ title: '同步中' });
    const n = await app.flushOutbox(true);
    wx.hideLoading();
    this.setData({ pending: app.globalData.pending });
    if (n) { wx.showToast({ title: '已同步 ' + n + ' 笔', icon: 'success' }); this.load(); }
    else wx.showToast({ title: '暂时还连不上', icon: 'none' });
  },

  async onLogout() {
    const res = await new Promise((resolve) => {
      wx.showModal({
        title: '退出登录',
        content: '退出后需要重新登录',
        success: resolve,
        fail: () => resolve({ confirm: false })
      });
    });
    if (!res.confirm) return;

    try {
      await api.post('/auth/logout', {});
    } catch (e) { /* 令牌已失效也继续本地清理 */ }

    wx.removeStorageSync('token');
    wx.removeStorageSync('skipWx');
    app.globalData.me = null;
    app.globalData.ready = false;
    wx.reLaunch({ url: '/pages/login/login' });
  }
});
