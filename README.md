# 画师工作台 / ArtFlow Workbench

这是从现有 `画师工作台` APK 接管整理出的可持续维护工程。

## 当前基线

- 包名：`cn.artflow.workbench`
- 原 APK：v1.7（业务前端位于 `app/src/main/assets/index.html`）
- 当前自维护版本：`1.7.1-custom` / `versionCode 1701`
- Android：minSdk 24，targetSdk 34
- 架构：原生 Android WebView 壳 + 单页 HTML/JS 业务层

> 业务 assets 从现有 APK 原样保留；Android 壳根据 APK 中的类名、字符串和行为重新整理成可维护源码。

## 签名锁定

Release 构建**禁止使用临时/新签名**。构建必须提供以下四个参数：

- `SIGNING_KEYSTORE_PATH`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

缺少任何一个，`assembleRelease` 会直接失败，避免误发不同签名的 APK。

### GitHub Actions secrets

仓库创建后添加：

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

`.github/workflows/build-release.yml` 会恢复固定 keystore，并生成签名 APK。

## 关于旧 APK 的签名

现有 APK 的私钥无法从 APK 反推出。因此第一次安装此自维护版本时，需要先卸载原作者签名的 APK；从这次固定签名开始，后续版本可一直覆盖升级。

## 数据兼容性

应用 ID 保持 `cn.artflow.workbench`，但 Android 因签名变化不会允许直接覆盖旧包。卸载旧包前，建议先在应用内导出完整数据备份。
