# ColorOS 堆叠信号

把 ColorOS 17 的双排（堆叠）信号样式带到 ColorOS 15 / 16：主卡的大信号与副卡的小信号叠在同一个槽位里，左上角标出当前数据网络的制式。

适用于官方系统没有提供「堆叠信号」开关、但状态栏其余部分仍保持原厂风格的场景。模块只替换信号图标的绘制内容，不动布局，不新增 view。

![状态对照](states.png)

<sub>从上到下：5G 单卡 / 4G 单卡 / 5G 双卡（下方四点为副卡）/ WiFi 连接时 / WiFi 断开时的制式标</sub>



## 效果

- **双卡双排**：主卡大信号 + 副卡小信号（下方四颗小点），占一个图标位
- **单卡满高**：单卡时使用专用素材，高度与状态栏其他图标齐平，下方不叠副卡
- **制式标跟随网络**：按当前数据网络自动显示 2G / 3G / 4G / 5G
- **颜色跟随状态栏**：与时钟同色，深浅色壁纸、锁屏与解锁自动适配
- **不改布局**：图标间距保持系统原生，不撑宽容器、不打乱右侧其他图标

## 兼容性

| 项目 | 说明 |
|---|---|
| 系统 | ColorOS 15 / ColorOS 16 |
| 框架 | LSPosed（libxposed API 102） |
| 作用域 | 仅 `com.android.systemui`，不注入系统框架 |
| 素材 | 取自 ColorOS 17 状态栏矢量图 |

## 安装

1. 在 LSPosed 管理器里安装并启用本模块
2. 作用域勾选「系统界面」（`com.android.systemui`）
3. 重启系统界面（或重启手机）

> 作用域只勾系统界面即可，勾选系统框架没有作用。

## 说明

- 状态栏信号旁原有的小制式图会被隐藏，制式标改由模块绘制在信号图标左上角
- 数据活动箭头（▲▼）不显示
- WiFi 图标保持系统原生样式，交回系统绘制，避免主题切换时颜色不同步

## 已知问题

- 单卡 + 下拉通知栏时，那一份实例仍显示原厂样式（该路径下的绘制时机不同，暂未定位）

## 构建

仓库包含完整源码：`src/`（Java）、`meta/`（清单与素材）、`build.sh`（构建脚本）。

依赖：JDK 8+、Android build-tools、`android.jar`（compileSdk 36）、libxposed API 102 的接口 jar。

```sh
ANDROID_JAR=$HOME/android.jar \
LIBXP_JAR=libs/libxposed-api-102.jar \
KEYSTORE=debug.keystore KS_ALIAS=androiddebugkey KS_PASS=android \
sh build.sh /tmp/out
```

产物为 `/tmp/out/signed.apk`。脚本会自动探测 build-tools 的目录布局并定位 `d8.jar` / `apksigner.jar`；`aapt` 需要 framework 资源表，默认读 `/system/framework/framework-res.apk`（可用 `FRAMEWORK_RES` 覆盖）。其他可覆盖项：`ANDROID_BUILD_TOOLS`、`JAVA`、`JAVAC`、`D8`、`APKSIGNER`、`AAPT`、`ZIPALIGN`。

目录结构：

```
src/io/github/hoerire/stacksig/   模块源码
meta/AndroidManifest.xml          清单
meta/res/drawable/                ColorOS 17 矢量素材
meta/res/values/strings.xml       模块名与描述
meta/META-INF/xposed/             java_init.list / scope.list / module.prop
build.sh                          构建脚本
```

构建时 `resources.arsc` 会保持 STORED 并做 4 字节对齐（Android 11+ 的安装要求），脚本里已经处理，不要用普通 zip 工具重新打包。

## 许可证

MIT
