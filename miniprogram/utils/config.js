// 开发（微信开发者工具模拟器）：用 127.0.0.1 即可，先在「详情 → 本地设置」勾选
//   「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」。
// 真机调试：把 DEV 换成开发机的内网 IP（手机和电脑连同一个 Wi-Fi），
//   本机当前是 192.168.10.103，并且要放行防火墙 3000 端口。
// 上线：把 PROD 换成小程序后台「开发管理 → 服务器域名」里配置过的 https 域名。
// const DEV = 'http://127.0.0.1:3000';
const DEV = 'https://jz.shu66.top';
const PROD = 'https://jz.shu66.top';

function base() {
  // 体验版/开发版走 DEV，正式版自动走 PROD，避免发版时忘了改地址
  try {
    const env = wx.getAccountInfoSync().miniProgram.envVersion;
    if (env === 'release') return PROD;
  } catch (e) { /* 低版本基础库没有这个 API，按开发环境处理 */ }
  return DEV;
}

module.exports = { BASE: base() };
