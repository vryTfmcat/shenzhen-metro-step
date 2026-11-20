# 地铁单步导航

深圳地铁的个人出行辅助工具。输入站名或在线路图上选择起终点，按当前一步查看上车方向、换乘和下车站。内置线网支持离线规划，选填高德 Web 服务 Key 后可以查看高德方案。

## Android

在 [Releases](../../releases) 下载已发布的签名 APK，支持 Android 8.0 及以上，建议使用更新的系统 WebView。0.1.1 已在实机通过冷启动和内置示例验证。

对照深圳地铁及港铁（深圳）的首末班时刻，内置 14 条线路的 466 个有方向区间。乘车段的站序、方向和全部区间都匹配时，点「我已上车」自动计时。各乘车段均可另查高德参考车程。车程资料不全时可用参考值或手动填写后开始计时。实际用时会随运行图和停站变化，以车内报站为准。

高德路线规划与参考车程查询共用同一把 Web 服务 Key。Android 版在验证通过后用系统 Keystore 加密存入本机。可在高德设置中清除。

乘车页可在蛇形线路图上记录实际到站。线路图保留本段已过站，并在能核实站序及方向时显示下车站之后最多两站。点选下车站之后的站需确认坐过站，可从记录的站重新规划。

换乘卡优先展示下车及目标站台、方向、楼层和对向关系。岗厦北、黄木岗、大运、八卦岭、会展中心已录入部分官方布局；未核实的楼层或对向关系会标明。

Android 行程页右上角「乘车小窗」打开画中画，也可从「行程选项」进入。0.2.3 的下车闹铃由应用直接按系统闹钟音量播放。允许通知和精确闹钟后，切换 App 仍可响铃。可从通知或乘车页停止，也可在乘车页试听。实时通知的状态栏样式由系统决定。

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

站内设施来自 [深圳地铁无障碍乘车指南](https://www.szmc.net/szmc_m/shentieyunying/yunyingzixun/wuzhangaichengchezhinan/line1/) 的 11 张线路表，匹配 241 个站。站卡同时指向可用的运营方原始资料。
