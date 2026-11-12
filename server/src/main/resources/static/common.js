/* 记账本 · 网页端共用脚本 */

const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

const TOKEN_KEY = 'lg_token';

function token() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

function setToken(t) {
  if (t) localStorage.setItem(TOKEN_KEY, t);
  else localStorage.removeItem(TOKEN_KEY);
}

async function req(path, opts) {
  opts = opts || {};
  const headers = { 'Content-Type': 'application/json' };
  if (token()) headers.Authorization = 'Bearer ' + token();

  let res;
  try {
    res = await fetch(path, {
      method: opts.method || 'GET',
      headers: headers,
      body: opts.body ? JSON.stringify(opts.body) : undefined
    });
  } catch (e) {
    /* 断网 / 后端没起来：标记成 NETWORK，调用方可以转存本地 */
    const err = new Error('NETWORK');
    err.code = 'NETWORK';
    err.network = true;
    throw err;
  }

  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }

  if (!res.ok) {
    if (res.status === 401 && !location.pathname.endsWith('login.html')) {
      setToken('');
      location.href = '/login.html';
    }
    const code = (data && data.error) || ('HTTP_' + res.status);
    const err = new Error(data && data.message ? code + '：' + data.message : code);
    err.code = code;
    err.status = res.status;
    throw err;
  }
  return data;
}

/* ---------------- 本地待同步队列（断网先存本地，恢复后补推） ---------------- */

const OUTBOX_KEY = 'lg_outbox';

function outboxAll() {
  try {
    const list = JSON.parse(localStorage.getItem(OUTBOX_KEY) || '[]');
    return Array.isArray(list) ? list : [];
  } catch (e) {
    return [];
  }
}

function outboxSave(list) {
  localStorage.setItem(OUTBOX_KEY, JSON.stringify(list || []));
}

function outboxAdd(entry) {
  const list = outboxAll();
  list.push(entry);
  outboxSave(list);
  return list;
}

function outboxRemove(id) {
  outboxSave(outboxAll().filter(e => e.id !== id));
}

function isNetworkError(err) {
  return !!(err && (err.network || err.code === 'NETWORK' || err.status === 0 || err.status >= 500));
}

function guard() {
  if (!token()) location.href = '/login.html';
}

async function logout() {
  try { await req('/auth/logout', { method: 'POST' }); } catch (e) { /* 令牌失效也继续 */ }
  setToken('');
  location.href = '/login.html';
}

/* ---------------- 格式化 ---------------- */

function money(fen) {
  return (Math.round(Number(fen) || 0) / 100).toFixed(2);
}

function parseYuan(v) {
  const n = parseFloat(v);
  return Number.isFinite(n) ? Math.round(n * 100) : null;
}

function monthNow() {
  const d = new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

function shiftMonth(month, delta) {
  const p = month.split('-');
  const d = new Date(Number(p[0]), Number(p[1]) - 1 + delta, 1);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

function monthText(month) {
  const p = (month || '').split('-');
  return p.length === 2 ? p[0] + ' 年 ' + Number(p[1]) + ' 月' : month;
}

function dayText(iso) {
  const s = String(iso || '');
  return s.slice(5, 10).replace('-', '/') + ' ' + s.slice(11, 16);
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, m => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]
  ));
}

function catOf(cats, id) {
  return cats.find(c => c.id === id) || { id: id, name: id, icon: '✨' };
}

/* ---------------- Toast ---------------- */

function uiToast(msg, type) {
  let box = $('#toasts');
  if (!box) {
    box = document.createElement('div');
    box.id = 'toasts';
    document.body.appendChild(box);
  }
  const el = document.createElement('div');
  el.className = 'toast' + (type ? ' ' + type : '');
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 2800);
}

/* ---------------- 弹窗 ---------------- */

function uiConfirm(opt) {
  opt = opt || {};
  return new Promise(resolve => {
    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.innerHTML = `
      <div class="modal" role="dialog" aria-modal="true">
        <h3>${esc(opt.title || '确认')}</h3>
        <div class="modal-text">${esc(opt.text || '')}</div>
        <div class="modal-actions">
          <button class="btn ghost" data-cancel>${esc(opt.cancel || '取消')}</button>
          <button class="btn ${opt.danger ? 'danger' : ''}" data-ok>${esc(opt.ok || '确定')}</button>
        </div>
      </div>`;

    const close = (val) => { mask.remove(); resolve(val); };
    mask.addEventListener('click', e => { if (e.target === mask) close(false); });
    $('[data-cancel]', mask).onclick = () => close(false);
    $('[data-ok]', mask).onclick = () => close(true);
    document.addEventListener('keydown', function onKey(e) {
      if (e.key === 'Escape') { document.removeEventListener('keydown', onKey); close(false); }
    });
    document.body.appendChild(mask);
    $('[data-ok]', mask).focus();
  });
}

/* 把一段 HTML 塞进弹窗，返回操作 DOM 的句柄 */
function uiModal(html, opt) {
  opt = opt || {};
  return new Promise(resolve => {
    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.innerHTML = `<div class="modal" role="dialog" aria-modal="true">${html}</div>`;
    const close = (val) => { mask.remove(); resolve(val); };
    mask.addEventListener('click', e => { if (e.target === mask) close(null); });
    document.body.appendChild(mask);
    resolve({ root: mask, close: close });
  });
}

/* ---------------- 图表（纯 SVG，不依赖第三方库） ---------------- */

const PALETTE = ['#D9552F', '#E08A3C', '#C9A227', '#4C9A6E', '#3E7CB1',
  '#7A5EA8', '#C2557A', '#8A6A4F', '#5C8D89', '#A65E3A'];

/* 分类 id 稳定映射到一个颜色，同一分类在表格、图例、图表里颜色一致 */
function colorFor(id) {
  let h = 0;
  const s = String(id || '');
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

function pctOf(part, total) {
  if (!total) return 0;
  return Math.round((part / total) * 1000) / 10;
}

function deltaText(cur, prev) {
  if (!prev || prev <= 0) return cur > 0 ? { text: '新增', dir: 1 } : { text: '—', dir: 0 };
  const d = ((cur - prev) / prev) * 100;
  return { text: (d >= 0 ? '+' : '') + d.toFixed(1) + '%', dir: d >= 0 ? 1 : -1 };
}

/* 环形图：items = [{label, value, color}] */
function donutSvg(items, opt) {
  opt = opt || {};
  const size = opt.size || 180;
  const sw = opt.thickness || 18;
  const r = (size - sw) / 2;
  const c = 2 * Math.PI * r;
  const cx = size / 2;
  const list = (items || []).filter(it => it.value > 0);
  const total = list.reduce((s, it) => s + it.value, 0) || 1;

  let acc = 0;
  const segs = list.map(it => {
    const len = (it.value / total) * c;
    const seg = '<circle cx="' + cx + '" cy="' + cx + '" r="' + r + '" fill="none" stroke="' +
      (it.color || colorFor(it.label)) + '" stroke-width="' + sw + '" stroke-dasharray="' +
      Math.max(len - 2, 0.6) + ' ' + c + '" stroke-dashoffset="' + (-acc) + '" stroke-linecap="round">' +
      '<title>' + esc(it.label) + ' ¥' + money(it.value) + '</title></circle>';
    acc += len;
    return seg;
  }).join('');

  return '<svg class="donut-svg" viewBox="0 0 ' + size + ' ' + size + '" role="img">' +
    '<g transform="rotate(-90 ' + cx + ' ' + cx + ')">' +
    '<circle class="donut-track" cx="' + cx + '" cy="' + cx + '" r="' + r + '" fill="none" stroke="' +
    (opt.track || '#F2EADF') + '" stroke-width="' + sw + '"/>' +
    segs + '</g>' +
    '<text x="' + cx + '" y="' + (cx - 2) + '" text-anchor="middle" class="donut-num">' +
    esc(opt.center || '') + '</text>' +
    '<text x="' + cx + '" y="' + (cx + 20) + '" text-anchor="middle" class="donut-sub">' +
    esc(opt.sub || '') + '</text></svg>';
}

/* 分组柱状图：rows = [{label, expense, income}] */
function barsSvg(rows, opt) {
  opt = opt || {};
  const W = 560, H = 200, padL = 6, padR = 6, padT = 16, padB = 28;
  const list = rows || [];
  if (!list.length) return '<div class="empty">还没有数据</div>';
  const max = Math.max(1, ...list.map(r => Math.max(r.expense || 0, r.income || 0)));
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const slot = innerW / list.length;
  const bw = Math.min(16, slot * 0.26);

  let grid = '';
  [0, 0.5, 1].forEach(f => {
    const y = padT + innerH * (1 - f);
    grid += '<line x1="' + padL + '" y1="' + y + '" x2="' + (W - padR) + '" y2="' + y +
      '" stroke="#F0E8DC" stroke-width="1"/>';
  });

  let bars = '';
  list.forEach((r, i) => {
    const cx = padL + slot * i + slot / 2;
    const eh = ((r.expense || 0) / max) * innerH;
    const ih = ((r.income || 0) / max) * innerH;
    const label = /^\d{4}-\d{2}$/.test(r.label || '') ? Number(r.label.slice(5)) + '月' : (r.label || '');

    bars += '<rect x="' + (cx - bw - 2) + '" y="' + (padT + innerH - eh) + '" width="' + bw +
      '" height="' + Math.max(eh, r.expense ? 3 : 0) + '" rx="4" fill="#D9552F" opacity=".9">' +
      '<title>' + esc(label) + ' 支出 ¥' + money(r.expense) + '</title></rect>';
    bars += '<rect x="' + (cx + 2) + '" y="' + (padT + innerH - ih) + '" width="' + bw +
      '" height="' + Math.max(ih, r.income ? 3 : 0) + '" rx="4" fill="#4C9A6E" opacity=".85">' +
      '<title>' + esc(label) + ' 收入 ¥' + money(r.income) + '</title></rect>';
    bars += '<text x="' + cx + '" y="' + (H - 8) + '" text-anchor="middle" class="ax">' + esc(label) + '</text>';
  });

  return '<svg class="bars-svg" viewBox="0 0 ' + W + ' ' + H + '" role="img" preserveAspectRatio="xMidYMid meet">' +
    grid + bars + '</svg>';
}

/* 单列每日柱：days = [{day:'2026-11-01', expense, income}] */
function dayBarsSvg(days, opt) {
  opt = opt || {};
  const W = 760, H = 170, padT = 12, padB = 26, padL = 4, padR = 4;
  const list = days || [];
  if (!list.length) return '<div class="empty">这个月还没有支出</div>';
  const max = Math.max(1, ...list.map(d => d.expense || 0));
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const slot = innerW / list.length;
  const bw = Math.max(3, Math.min(18, slot * 0.55));

  let bars = '';
  list.forEach((d, i) => {
    const h = ((d.expense || 0) / max) * innerH;
    const x = padL + slot * i + (slot - bw) / 2;
    const y = padT + innerH - h;
    const dd = Number(String(d.day).slice(8));
    const showLabel = list.length <= 16 || dd % 5 === 0 || i === list.length - 1;
    bars += '<rect x="' + x + '" y="' + y + '" width="' + bw + '" height="' + Math.max(h, d.expense ? 2 : 0) +
      '" rx="' + Math.min(4, bw / 2) + '" fill="#D9552F" opacity="' + (d.expense ? '.88' : '.18') + '">' +
      '<title>' + esc(String(d.day).slice(5)) + ' 支出 ¥' + money(d.expense) + '</title></rect>';
    if (showLabel) {
      bars += '<text x="' + (x + bw / 2) + '" y="' + (H - 8) + '" text-anchor="middle" class="ax">' + dd + '</text>';
    }
  });

  return '<svg class="bars-svg" viewBox="0 0 ' + W + ' ' + H + '" role="img" preserveAspectRatio="xMidYMid meet">' +
    '<line x1="' + padL + '" y1="' + (padT + innerH) + '" x2="' + (W - padR) + '" y2="' + (padT + innerH) +
    '" stroke="#EFE6D9" stroke-width="1"/>' + bars + '</svg>';
}

/* 迷你走势线：values 数组，用于 KPI 卡右下角 */
function sparkSvg(values, color) {
  const list = (values || []).map(v => Number(v) || 0);
  if (list.length < 2) return '';
  const W = 96, H = 30;
  const max = Math.max.apply(null, list) || 1;
  const min = Math.min.apply(null, list);
  const span = Math.max(max - min, 1);
  const pts = list.map((v, i) => {
    const x = (i / (list.length - 1)) * W;
    const y = H - 3 - ((v - min) / span) * (H - 8);
    return x.toFixed(1) + ',' + y.toFixed(1);
  }).join(' ');
  return '<svg class="spark" viewBox="0 0 ' + W + ' ' + H + '" aria-hidden="true">' +
    '<polyline points="' + pts + '" fill="none" stroke="' + (color || '#D9552F') +
    '" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
}
