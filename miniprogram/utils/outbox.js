const KEY = 'lg_outbox';

/** 本地待同步队列：后端不可用时先存这里，恢复后一起补推 */
function all() {
  const list = wx.getStorageSync(KEY);
  return Array.isArray(list) ? list : [];
}

function save(list) {
  wx.setStorageSync(KEY, list);
}

function add(body) {
  const list = all();
  const entry = {
    id: 'tx_' + Date.now() + '_' + Math.floor(Math.random() * 1e6),
    at: Date.now(),
    body: body
  };
  list.push(entry);
  save(list);
  return entry;
}

function remove(id) {
  save(all().filter(x => x.id !== id));
}

function count() {
  return all().length;
}

/** 网络层失败（连不上、超时、5xx）才值得排队重试；4xx 是请求本身的问题 */
function isNetwork(e) {
  return !!e && (e.network === true || e.code === 'NETWORK' || e.status === 0 || e.status >= 500);
}

module.exports = { KEY, all, add, remove, count, isNetwork };
