# 接管说明

原 APK 的业务逻辑几乎全部位于 `assets/index.html`；原生层非常薄，主要职责为：

1. 创建 WebView 并加载 `file:///android_asset/index.html`
2. 启用 JavaScript / DOM Storage / Database / 文件与内容访问
3. 处理多图选择文件输入
4. 注入下载拦截脚本，把 blob/data URL 通过 `AndroidBridge.saveFile(...)` 保存到系统下载目录
5. WebView 返回导航与渲染进程异常恢复

`app/src/main/assets/` 是从 APK 直接提取的前端资源，未在接管阶段主动修业务 bug，以便建立可验证的原始基线。
