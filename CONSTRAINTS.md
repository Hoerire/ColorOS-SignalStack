# 约束与已知坑

## 一、绝对禁止（每条都导致过 SystemUI 崩溃循环）

1. **往 `StatusIconContainer` 插入任何 View**
   `onMeasure` 会把每个子 View 强转成 `StatusIconDisplayable`，不是就抛
   `ClassCastException`。已踩 2 次。

2. **重排 `StatusIconContainer` 的现有子视图**（`removeViewAt` + `addView`）
   触发 `onViewAdded` / `onViewRemoved` → `mLayoutStates` / `mMeasureViews`
   状态机错位。已踩 1 次，导致整机重启。

3. **用自定义 Drawable 代理顶替系统图标的 drawable**
   `OplusModernStatusBarWifiView.onIconTintChanged` 等路径会对 drawable 做类型
   强转 / 按原类型处理，代理对象直接崩。已踩 1 次（SwapDraw 方案）。

4. **反射构造 `ModernStatusBarWifiView` / `OplusModernStatusBarWifiView`**
   `binding == null`，`onIconTintChanged` 里 NPE。

5. **杀 `lspd` 进程** —— 会破坏整个 LSPosed，管理器显示「未安装」，只能重启设备恢复。

6. **kill SystemUI 之后 30 秒内做下一次操作** —— 它还在重建，容易误判。

## 二、正确做法

### 改图标顺序（信号 ↔ WiFi 位置）

**不要碰视图树。** 顺序由 `config_statusBarIcons` 这个数组决定：

| 版本 | 顺序 |
|---|---|
| ColorOS 16 | `... wifi, nosim_all, nosim_one, nosim_two, mobile, ...` |
| ColorOS 17 | `... stacked_mobile, mobile, nosim_all, nosim_one, nosim_two, wifi, ...` |

`StatusBarIconList(String[])` 构造函数的入参顺序 == 屏幕显示顺序。
Hook 这个构造函数，在 `proceed()` 之前重排数组即可，纯数据层，零崩溃风险。

实现见 `Engine.hookIconOrder()` / `Engine.reorder()`。

### 改信号图标外观

只替换 `mobile_signal` 这个 ImageView 的 drawable（`VStack`），
制式标 / 主卡 / 副卡全在同一张 drawable 里画。**不要往容器塞 view。**

### 信号 drawable 的绘制边界

`mobile_signal` 的 View 只有 67px 宽，右侧紧邻电池。
**画到 View 边界外会被裁剪**，加宽 `intrinsicWidth` 会被电池顶掉。
所以大号制式标放不进信号右侧，这个方向已彻底否决。

### 网速视图

工厂布局 `network_speed_view.xml` 里两个 TextView 都是 `gravity=CENTER`。
**不要改成 END** —— 字形会贴死视图边缘被 `clipChildren` 裁掉（表现为 "S" 消失）。

## 三、构建

```sh
cd /storage/emulated/0/DeepSeek/stacksig
export ANDROID_JAR=/data/user/0/app.dsh.mobile/files/dsh-home/android.jar
export LIBXP_JAR=/storage/emulated/0/DeepSeek/build/stub/libxposed-api-102.jar
export KEYSTORE=/storage/emulated/0/DeepSeek/debug.keystore
export KS_ALIAS=androiddebugkey KS_PASS=android KS_KEYPASS=android
export ANDROID_BUILD_TOOLS=/data/user/0/app.dsh.mobile/files/engine/extensions/android-buildtools
export D8=$ANDROID_BUILD_TOOLS/share/java/d8.jar
export APKSIGNER=$ANDROID_BUILD_TOOLS/share/java/apksigner.jar
export JAVA=$ANDROID_BUILD_TOOLS/lib/jvm/java-21-openjdk/bin/java
export JAVAC=$ANDROID_BUILD_TOOLS/lib/jvm/java-21-openjdk/bin/javac
sh build.sh /storage/emulated/0/DeepSeek/outNN
```

**注意**：环境变量必须逐行 export，用反斜杠续行会出问题。

## 四、反编译工具链

`dexsql` 是 glibc 二进制，本机没有 glibc，必须借 Termux 的 glibc 装载器：

```sh
G=/data/local/tmp/glibc/root/data/data/com.termux/files/usr/glibc
su -c "$G/lib/ld-linux-aarch64.so.1 --library-path $G/lib /data/local/tmp/dexsql \
  query --input <apk> --sql \"SELECT ...\""
```

常用表：`methods`（`class_descriptor` / `name` / `descriptor`）、`types`、
`fields`、`strings`。

反汇编单个类：`dexsql decode --input <apk> --class '<descriptor;>'`

**资源表**用 `aapt2 dump resources`（`aapt dump --values` 在大 APK 上会空输出）。

## 五、部署

```sh
su -c 'cp <apk> /data/local/tmp/x.apk'
su -c 'chmod 644 /data/local/tmp/x.apk'
su -c 'pm install -r /data/local/tmp/x.apk'
su -c 'killall com.android.systemui'
sleep 32
su -c 'pidof com.android.systemui'   # 必须非空
```

`pm install -r` 会改变 APK 路径，必要时刷新 LSPosed 的 `modules.apk_path`。

读模块日志（是 LSPosed 日志，不是 logcat）：

```sh
L=$(su -c 'ls -t /data/adb/lspd/log/modules_*.log | head -1')
su -c "grep 'StackSig' $L" | tail -20
```

## 六、模块元数据

- `staticScope=true` —— LSPosed 直接从 APK 读 `scope.list`。设成 false 时靠守护
  进程推送 scope，曾失败过，模块根本不加载。
- libxposed API 102 只有两个 `log` 重载：`log(int,String,String)` 和
  `log(int,String,String,Throwable)`。别的签名调用会抛 `NoSuchMethodError`。

## 七、当前状态

**已安装**：带 `hookIconOrder` 的版本（构建于 `out-order/`，08:04:05 安装）。
效果：状态栏顺序已是「信号 → WiFi → 电池」，与 ColorOS 17 一致，SystemUI 稳定。

**源码**：`main` 分支待提交本次改动（`Engine.hookIconOrder` + `reorder`，共 52 行）。

**回滚备份**：`/storage/emulated/0/DeepSeek/out-stable/signed.apk`（无排序改动）。

**未发布**：新版本尚未推送仓库 release，versionCode 仍为 39 / v1.5。

**下一步**：无待办。大号制式标方向已否决（见第二节「信号 drawable 的绘制边界」）。
