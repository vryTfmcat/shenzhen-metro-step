# 地铁单步导航

深圳地铁的个人出行辅助工具。输入站名或在线路图上选择起终点，按当前一步查看上车方向、换乘和下车站。内置线网支持离线规划，选填高德 Web 服务 Key 后可以查看高德方案。

## Android

在 [Releases](../../releases) 下载签名 APK，支持 Android 8.0 及以上，建议使用更新的系统 WebView。0.1.1 已在安卓实机通过冷启动及内置示例的上车、下车提示验证。到站提醒由用户手动选择实际到达的站来触发。

## Windows 网页

安装 Python 3 后运行 `启动.cmd`，在本机打开 <http://127.0.0.1:8765/>。高德 Web 服务 Key 在应用中输入，保存在运行进程的内存中。网页与 Android 使用同一套界面和线网数据。

## 构建 APK

在 Windows PowerShell 7 中从仓库根目录运行 `pwsh -File android/build.ps1`。设置 `JAVA_HOME` 指向 JDK 11 或更新版本。构建脚本在仓库的上一级查找以下目录。

- `tools/android-sdk/platform/android-35/android.jar`
- `tools/android-sdk/build-tools/` 下解压的 Windows Build Tools 35.0.0
- `private-data/android-signing/` 中的签名材料。首次构建会生成本机签名材料，覆盖安装已发布版本需要原有签名密钥。

输出位于上一级的 `releases/`。

## 数据来源

深圳站点、站序及线网颜色来自 [高德深圳地铁图](https://www.amap.com/ssr/subway/shenzhen-4403) 的 2026-11-20 快照。品牌标志参考 [深圳地铁品牌标志](https://www.szmc.net/szmc_m/jituangaikuang/qiyewenhua/szLOGO/)。
