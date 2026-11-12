const { BASE } = require('./config');

function request(path, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE + path,
      method: opts.method || 'GET',
      data: opts.data,
      timeout: opts.timeout || 45000,
      header: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + (wx.getStorageSync('token') || '')
      },
      success(res) {
        const body = res.data || {};
        if (res.statusCode >= 400) {
          const err = new Error(body.error || ('HTTP_' + res.statusCode));
          err.code = body.error;
          err.status = res.statusCode;
          err.message = body.message || err.message;
          err.network = res.statusCode >= 500;
          if (res.statusCode === 401) wx.removeStorageSync('token');
          return reject(err);
        }
        resolve(body);
      },
      fail(e) {
        const err = new Error('NETWORK');
        err.code = 'NETWORK';
        err.network = true;
        err.detail = e;
        reject(err);
      }
    });
  });
}

module.exports = {
  get: function (p, d) { return request(p, { data: d }); },
  post: function (p, d) { return request(p, { method: 'POST', data: d }); },
  patch: function (p, d) { return request(p, { method: 'PATCH', data: d }); },
  del: function (p) { return request(p, { method: 'DELETE' }); }
};
