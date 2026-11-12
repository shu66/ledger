// 开发：微信开发者工具勾选「详情 → 本地设置 → 不校验合法域名」；
//       真机调试把 DEV 换成开发机的内网 IP（手机和电脑要同一 Wi-Fi）。
// 上线：把 PROD 换成小程序后台「开发管理 → 服务器域名」里配置过的 https 域名。
const DEV = 'http://192.168.1.10:3000';
const PROD = 'https://ledger.example.com';

function base() {
  // 体验版/开发版走 DEV，正式版自动走 PROD，避免发版时忘了改地址
  try {
    const env = wx.getAccountInfoSync().miniProgram.envVersion;
    if (env === 'release') return PROD;
  } catch (e) { /* 低版本基础库没有这个 API，按开发环境处理 */ }
  return DEV;
}

module.exports = { BASE: base() };
