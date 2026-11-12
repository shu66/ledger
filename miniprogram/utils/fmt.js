function pad(n) {
  return n < 10 ? '0' + n : '' + n;
}

/** 分 -> 两位小数的元 */
function yuan(fen) {
  const n = Math.round(Number(fen) || 0);
  return (n / 100).toFixed(2);
}

function todayKey() {
  const d = new Date();
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function thisMonth() {
  const d = new Date();
  return d.getFullYear() + '-' + pad(d.getMonth() + 1);
}

function dayKeyOf(iso) {
  return (iso || '').slice(0, 10);
}

function hhmm(iso) {
  return (iso || '').slice(11, 16);
}

function dayLabel(key) {
  if (!key) return '';
  const t = todayKey();
  if (key === t) return '今天';
  const y = new Date();
  y.setDate(y.getDate() - 1);
  const yk = y.getFullYear() + '-' + pad(y.getMonth() + 1) + '-' + pad(y.getDate());
  if (key === yk) return '昨天';
  const p = key.split('-');
  return Number(p[1]) + '月' + Number(p[2]) + '日';
}

function weekday(key) {
  const d = new Date(key + 'T00:00:00+08:00');
  return '日一二三四五六'[d.getDay()];
}

function monthLabel(month) {
  const p = (month || '').split('-');
  return p.length === 2 ? p[0] + '年' + Number(p[1]) + '月' : month;
}

function shiftMonth(month, delta) {
  const p = month.split('-');
  const d = new Date(Number(p[0]), Number(p[1]) - 1 + delta, 1);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1);
}

/** 选中的日期 + 此刻时分秒，带 +08:00 偏移，后端按上海时区入库 */
function atNow(dateStr) {
  const d = new Date();
  return dateStr + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) + '+08:00';
}

/** 给 picker 用的中文日期 */
function dateLabel(dateStr) {
  const p = (dateStr || '').split('-');
  if (p.length !== 3) return dateStr;
  return Number(p[1]) + '月' + Number(p[2]) + '日 周' + weekday(dateStr);
}

module.exports = {
  yuan: yuan,
  todayKey: todayKey,
  thisMonth: thisMonth,
  dayKeyOf: dayKeyOf,
  hhmm: hhmm,
  dayLabel: dayLabel,
  weekday: weekday,
  monthLabel: monthLabel,
  shiftMonth: shiftMonth,
  atNow: atNow,
  dateLabel: dateLabel
};
