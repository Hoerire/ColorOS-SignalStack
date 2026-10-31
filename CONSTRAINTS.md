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

### 改图标顺序

**不要碰视图树。** 顺序由 `config_statusBarIcons` 这个数组决定，实测原厂 46 项：

```
... 28=mute 29=ethernet 30=network_speed 31=vowifi 32=volte
    33=zen 34=wifi 35..37=nosim 38=mobile 39=dynamic_info ... 45=battery
```

`StatusBarIconList(String[])` 构造函数的入参顺序 == 屏幕显示顺序。
Hook 这个构造函数，在 `proceed()` 之前重排数组即可，纯数据层，零崩溃风险。

实现见 `Order.hookIconOrder()` / `Order.reorder()` / `Order.moveBefore()`。

**当前顺序**：网速 → NFC → 闹钟 → 免打扰 → 月亮 → 信号 → WiFi → 电池
（`moveBefore(network_speed, logging)` 把网速顶到最前，
再 `moveBefore(zen, wifi)`、`moveBefore(mobile, wifi)`）

### 改信号图标外观

只替换 `mobile_signal` 这个 ImageView 的 drawable（`VStack`），
制式标 / 主卡 / 副卡全在同一张 drawable 里画。**不要往容器塞 view。**

### 信号 drawable 的绘制边界

`mobile_signal` 的 View 只有 67px 宽，右侧紧邻电池。
**画到 View 边界外会被裁剪**，加宽 `intrinsicWidth` 会被电池顶掉。
所以大号制式标放不进信号右侧，这个方向已彻底否决。

### 网速：数值与刷新都由我们接管

**不要改原厂的 Handler 排期。** 曾经把它从 4 秒压到 1 秒，但没改差分窗口 ——
分母忽大忽小，瞬时抖动被放大，实测 1.2 KB/s 会显示成 7.00（虚高 6 倍）。

正确做法（见 `Speed.java`）：

1. 每秒读累计收发字节，按**真实时间差**算速率（B/s）；
2. hook `NetworkSpeedIconState.getSpeedText()` 返回我们的值 ——
   它返回的就是字节/秒，单位换算、KB/s ↔ MB/s、小数位全归原厂；
3. 每秒反射调一次 `NetworkSpeedView.applyNetworkState(mState)` 触发重绘，
   否则原厂 4 秒才刷一次，算得再快也看不出来。

**统计范围只能算物理网卡**：`wlan0` / `rmnet_data*` / `eth*`。
不能用 `TrafficStats.getTotalRxBytes()` —— 它把所有接口相加，
走 VPN/代理时同一份数据经过 `tun0` 和 `wlan0` 被算两遍，实测翻倍。

**SystemUI 读不到 `/sys/class/net`**（SELinux 域限制），但 `/proc/net/dev` 能读。
所以判断接口只能靠名字前缀，不能遍历 sysfs。

### 网速视图的排版

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

**`dexsql` 已装成本机命令（2026-10-31），直接 `dexsql ...` 即可，不用再写 loader 前缀。**

| 位置 | 内容 |
|---|---|
| `$HOME/tools/dexsql/dexsql` | 主程序（取自 `/storage/emulated/0/DeepSeek/dexsql`，18MB） |
| `$HOME/tools/glibc/ld-linux-aarch64.so.1` + `lib/` | Termux glibc loader 与库（37MB，从 `/data/local/tmp/glibc` 抽出） |
| `$HOME/tools/bin/dexsql` | 包装脚本（canonical 副本） |
| `.../files/engine/bin/dexsql` | 同名副本，`PATH` 首位，所以命令直接命中 |
| `$HOME/tools/README.md` | 用法 / 迁移 / 修复说明 |

`$HOME` = `/data/user/0/app.dsh.mobile/files/dsh-home`。包装脚本就是：

```sh
exec $HOME/tools/glibc/lib/ld-linux-aarch64.so.1 --library-path $HOME/tools/glibc/lib \
     $HOME/tools/dexsql/dexsql "$@"
```

根目录可用 `DSH_DEXSQL_HOME` 覆盖；二进制或 loader 缺失会直接报错退出。

**两个坑**：

- `/storage/emulated/0` 是 **noexec** 挂载，脚本/二进制放那里执行报 `Permission denied`，
  必须放 `$HOME` 或 `/data/local/tmp`；
- `engine/bin` 属于 app 自身目录，DSH 升级可能覆盖，丢了重建一行：
  `cp $HOME/tools/bin/dexsql /data/user/0/app.dsh.mobile/files/engine/bin/dexsql && chmod 755 $_`

手工调用（老写法，仍可用，root 下跑）：

```sh
G=/data/local/tmp/glibc/root/data/data/com.termux/files/usr/glibc
su -c "$G/lib/ld-linux-aarch64.so.1 --library-path $G/lib /data/local/tmp/dexsql \
  query --input <apk> --sql \"SELECT ...\""
```

常用表：`methods`（`class_descriptor` / `name` / `descriptor`）、`types`、
`fields`、`strings`、`code_items`（`insns_off` / `insns_size` / `tries_size`）。

改过的 dex（如 `classes.patched.dex`）直接用 `dexsql decode` 校验很顺手，
verifier 报错（如 `invoke argument count exceeds outs_size`）说明字节码改坏了。

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

**已提交（未推送）**：v1.10 / versionCode 47。
内容：制式标独立 View + 网络指示器下移 + 单双卡自适应。
构建产物：`/storage/emulated/0/DeepSeek/out-restored/signed.apk`。
- 源码仓库：[Hoerire/ColorOS-SignalStack](https://github.com/Hoerire/ColorOS-SignalStack)（tag `1-1.9`）
- LSPosed 仓库：[io.github.hoerire.stacksig](https://github.com/Xposed-Modules-Repo/io.github.hoerire.stacksig)（tag `46-1.9`）

**注意**：versionName 是 1.9，versionCode 是 46 —— code 必须递增，
否则装过 45 的设备会认为没更新，`pm install -r` 直接跳过。

### v1.10（未发布）制式标独立视图 + 网络指示器下移

**制式标不再是 drawable 里的一张小图，改成挂在 `mobile_combo` 上的独立 TextView。**

原因：drawable 出不了 View 边界。`mobile_signal` 只有 67px 宽，箭头在它左边 24px 处，
画在 drawable 左上的制式标永远追不上箭头，左缘对不齐。

**摆位（560dpi 实测像素）**

| 参数 | 值 | 说明 |
|---|---|---|
| `leftMargin` | 1.15dp | 5G 左缘与箭头上三角左缘齐平（均为 1039） |
| `topMargin` | 2dp | 顶部与闹钟齐平 |
| `textSize` | 6.5dp | 再加宽会顶到信号柱 |

**箭头下移（`dropDataInout` / `pushDown`）**

```java
v.setTranslationY(7dp * density * columnRatio());
```

- **只能用 `translationY`，绝不能改 `LayoutParams`**。用 `topMargin` 会触发重新
  measure + scaleType，箭头被按新高度缩放（实测 h 从 49 掉到 33），看起来就是「箭头变小了」。
- `columnRatio()` = 信号视图高 / 56px（双卡基准）。单卡用 `ic_s17_single_*` 满高素材，
  柱子更高，按比例放大才不会偏高。

**制式刷新只有一条通道**

`startBadgeTicker` 每 2.5 秒比对 `badgeLabel(ctx)`，变了就 `pushBadgeLabel` 直接改文本。
`stack()` 里**只登记 combo，不写文本**；`sig` 里**不含制式** ——
两处都写会出现两套刷新互相打架，制式还会连带重建 drawable 白闪一下。

**5GA 只看数据卡**

```java
int subId = SubscriptionManager.getDefaultDataSubscriptionId();
overrideNrAdvanced(ctx, subId);   // 只问这一张
```

不要扫所有 subId。实测双卡时 5GA 报在 subId=1、默认数据卡是 subId=2，
扫所有卡会显示与实际不符的制式。系统状态用
`dumpsys telephony.registry | grep mTelephonyDisplayInfo` 核对：
`overrideNetwork=NONE` → 5G，`NR_ADVANCED` → 5GA。

**颜色必须在时钟换色的同一调用栈里刷**

状态栏换色时系统只回调它自己的图标。挂在 `TextView.setTextColor` 上，
遇到 `clock` 就调 `syncAllBadgeColors`。取色优先级：
**时钟文字色 → 同排 ImageView tint → 信号图标 tint**。1 秒 ticker 只作兜底。

**新建 TextView 必须等容器量完**

SystemUI 刚重启时 `stack()` 在 `mobile_combo` measure 完之前就被调起来，
此时 combo 尺寸为 0，TextView 会堆在信号柱正上方，等下次布局才归位。
用 `attachBadgeWhenMeasured`：`getWidth()>0 && getHeight()>0` 才建，否则挂 `OnLayoutChangeListener`。

---

**踩过的坑（本轮）**

1. `su -c screencap` 在 root 域链接不到 `libbinder_ndk.so`，**静默失败并保留旧图** ——
   前面几次「移多了/移少了」的误判全是看了陈旧截图。正确写法：
   `su -c 'LD_LIBRARY_PATH=/system/lib64 screencap -p out.png'`
2. `badgeLabel` 里必须先判 `5ga` 再判 `5g`，否则 `ic_s17_5ga` 含 `5g`，5GA 被吞成 5G。
3. **整理箭头时不要按名字遍历整棵子树**。WiFi 与移动数据两个 `inout` 在同一个祖先下，
   按名字匹配会把 WiFi 那一支也清掉，实测 WiFi 图标整块消失。
   只碰 `DROPPED` 里登记过的（= `pushDown` 亲自改过的）view。
4. **WiFi 上下线时不要调 `refreshAll()`**。系统正在切换自己的图标，
   我们重建信号 drawable 会和它抢同一帧，WiFi 图标要好几秒才出来。
   那一刻只 permit `pushBadgeLabel`（改文本）+ `invalidateSelf`（纯重绘）。
5. **改完必须核对截图的新鲜度**。`su -c screencap` 失败时静默保留旧图，
   前后两张 md5 一样就说明看的是旧画面，据此调参数会越调越偏。

---

### 代码结构（v1.9 重构后）

| 文件 | 职责 |
|---|---|
| `Engine.java` | 信号合成、颜色、可见性、边距 |
| `Speed.java` | 网速采样与刷新 |
| `Spacing.java` | 图标间距（padding） |
| `Order.java` | 图标顺序（数组重排） |
| `VStack.java` | 双排信号绘制、制式标 |
| `Res17.java` | 资源加载 |

### v1.9 定型内容

1. 顺序：网速 → NFC → 闹钟 → 免打扰 → 月亮 → 信号 → WiFi → 电池
2. 间距：`GAP_INSET_PX = 0` 统一 padding，只给蓝牙额外 +1
3. 制式标：`BADGE_H=0.30`、`BADGE_X=0.02`；宽度按素材自身宽高比算
4. 2G 素材 viewport 收到墨迹实际范围，四种制式 intrinsic 比例一致
5. 5GA 判断：NR + `TelephonyDisplayInfo.overrideNetworkType==5`
6. 网速：真实字节数、1 秒刷新、只算物理网卡
7. 上传/下载箭头只在 WiFi 下显示

### 仓库约定

**LSPosed 仓库只放文档和图片**（README / LICENSE / PNG），源码不放。
源码只在主仓库维护，避免两边不同步。

### 版本号同步（老坑）

两处必须同时改，只改一处会导致 `pm install -r` 认为是同版本而不更新：
- `meta/META-INF/xposed/module.prop` → `version` / `versionCode`
- `meta/AndroidManifest.xml` → `android:versionName` / `android:versionCode`

---

### FpsLock → 「ColorOS全局高刷」v1.1（另一支模块，非 StackSig）

- 原始包：`/storage/emulated/0/DeepSeek/FpsLock 刷新率锁_1.0.apk`（com.dsh.fpslock，versionCode 1000，签名 CN=FpsLock Module，私钥已丢失，只能卸装重装）
- 新包：`/storage/emulated/0/DeepSeek/ColorOS全局高刷_1.1.apk`（versionCode 1001，签名 CN=ColorOS Global 165；keystore 在 fpslock_work/coloros.key，口令 android）
- 没有源码（/data/local/tmp/fpslock/FpsLockModule.v04*.java 是旧版，不含 OTI/applyMode），直接在 dex 上做等长字节码替换：
  1. `safeLog(String)` 首指令改 `return-void` + nop 填充，tries/handler 原样保留
  2. `onPackageLoaded` 从 insns+140 字节（unit 70）起 23 个 code unit（setId/setPriority/intercept/safeLog）整体换成 setId/setPriority/intercept + 直接 `invoke-virtual log(4,"FpsLock","hooked callApplicationOnCreate")`
  3. 该 code_item 的 outs_size 必须 3→4，否则 invoke 4 参会过不了 verifier（dexsql 报 invoke argument count exceeds outs_size）
  4. 位置：onPackageLoaded code_item 5612 / insns 5628；safeLog code_item 6236 / insns 6252。改动等长，offset 与 try 条目全不变；改完必须重算 SHA1(signature) + Adler32(checksum)
- 改名：resources.arsc 字符串池 @0x7f020000 的 "FpsLock 刷新率锁"(20B) 原地换 "ColorOS全局高刷"(19B)，uleb128 长度 0x0c/0x14 → 0x0b/0x13 尾部补 NUL；module.prop 的 name/version/versionCode；二进制清单 versionCode 1000→1001、字符串池 "1.0"→"1.1"
- 打包坑：resources.arsc 必须 ZIP_STORED + 4 字节对齐，否则 pm install 报 Targeting R+ ... requires the resources.arsc ... stored uncompressed
- 状态 2026-10-31：已 pm install v1.1；LSPosed modules_state.enabled 已用 sqlite 置 1（/data/adb/lspd/config/modules_config.db，备份 /data/local/tmp/modules_config.db.bak），scope 走 APK 内静态 scope.list（systemui + system）
- 待用户自行重启生效：lspd 内存里仍是旧的 enabled=0，重启 SystemUI 不够，必须整机/框架重启后新 dex 才注入
- 重启后预期：SystemUI 每次加载只出 1 行 hooked callApplicationOnCreate；system 进程的 OTI 循环日志归零

### 「ColorOS全局高刷」v1.4（2026-10-31 后半段）

- **改成源码重建**：`/storage/emulated/0/DeepSeek/fpslock_src/src/com/dsh/fpslock/FpsLockModule.java`
  构建：`cd fpslock_src && bash build.sh 1004 1.4 <模板APK> <输出APK>`（javac → d8 → 换 classes.dex/manifest/module.prop → zipalign -f 4 → apksigner，keystore 用 `fpslock_work/coloros.key`，口令 android，与已装包同签名，可直接 `pm install -r`）
- 行为：亮屏 → 立即 applyMode(SF transact 1035 mode=4) + setSystemFrameRateControl(165) 并每 800ms 复投；**息屏 → removeCallbacks，零轮询**；亮屏/解锁 → 广播触发恢复
- system 侧 OTI 循环也改成 system context 广播驱动（息屏零定时器），但 system_server 要下次重启才换新代码；当前 system 进程仍是旧逻辑（日志已静音）
- 实测证据（debug 版计数器）：息屏 62 秒 `tickRuns` 停在 43 不变、`ticksWhileOff=0`；解锁后 43→45 恢复
- 静默版（v1.4 正式）：锁屏 15 秒 + 解锁，LSPosed 日志新增 **0 行**；只有注入时 1 行 `hooked callApplicationOnCreate`
- 成品：`/storage/emulated/0/DeepSeek/ColorOS全局高刷_1.4.apk`；带计数器的调试版留档 `fpslock_work/dbg.apk`
- 注意：`resources.arsc` 必须 ZIP_STORED + 4 字节对齐，否则 pm install 报 Targetting R+ 错误
- 功耗实测（2026-10-31）：800ms 复投线程 60 秒 / 约 75 次，CPU 时间 <10ms（<0.02%，单次 <0.13ms）→ 复投可忽略；真正耗电是常驻 165Hz 出帧（surfaceflinger 12% + composer 7% + crtc_commit 6.7%）。用户决定保持 800ms 不变
- 源码包：`/storage/emulated/0/DeepSeek/ColorOS全局高刷_src_v1.4.zip`（含 build.sh / tools / 模板 / 签名密钥 / stub），重建 dex 与线上版本 md5 一致

### 「安装器净化」v1.6（2026-10-31 尾段，com.dsh.instclean）

- 源码：`/storage/emulated/0/DeepSeek/instclean/`，构建 `bash build.sh 100X 1.X`（keystore: keystore/debug.keystore，alias androiddebugkey，口令 android）
- 作用域：`com.android.packageinstaller` + `com.oplus.appdetail`（scope.list）；LSPosed 配置直接写 `/data/adb/lspd/config/modules_config.db` 的 modules / modules_state / scope 三张表，改完 `PRAGMA wal_checkpoint(FULL)` 再用管理器开关一次（会通知 lspd 重载）
- ColorOS 安装链路（实测）：文件管理 → `com.oplus.appdetail`（HostCompatibleActivity → PackageParsingV2Activity「正在准备」+「安装信息收集提醒」→ InstallGuideActivity 风险扫描页 → InstallFinishActivity 广告页）→ `com.android.packageinstaller/.InstallStart` → `oplus.OPlusPackageInstallerActivity`（详情+风险块+权限）→ 安装成功页（含「自动清除安装包和残留」开关）
- **硬坑**：不要在 `OPlusPackageInstallerActivity` 里 `setContentView` 换掉厂商界面——它的异步回调会去操作自己的 TextView，直接 NPE 崩进程（`k2()`）。只能**加 View**（在「版本」那一行后面插一栏），或者只做 GONE 隐藏
- 也不能直接反射调 `Activity.onCreate` 跳过厂商逻辑：libxposed 报 `InvocationTargetException`，页面起不来
- 现策略（v1.6）：appdetail 解析页/扫描页 onResume 自动点「继续安装」；InstallFinishActivity 直接 finish；安装器页只做两件事——补一行「包名 · 版本(vc)」，把「正在扫描 / 发现以下风险」块 setVisibility(GONE)（40×250ms 反复扫），并把「自动清除安装包」开关置 off
- **厂商装完会删源 APK**（安装成功页的「自动清除安装包和残留」默认开）→ 测试包别只放 Download，留底在 `/storage/emulated/0/DeepSeek/testpool/`
- 干净 AOSP 安装器（`软件包安装程序_16.000.003.apk`）：同包名+平台签名，正常装被 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 顶掉；改名成 `com.dshmodx.packageinstaller` 能装上，但一启动就 `NoSuchMethodError: ApplicationInfo.isSystemApp()`（本 ROM framework 没这方法）→ 此路需 dex 补丁，暂时放弃
- v1.7~v1.9 补：appdetail 的 HostCompatible / PackageParsingV2 / InstallGuide 改成「透明窗口 + 无动画 finish」，白屏从数秒降到 <0.9s，直接落到安装页
- **隐藏扫描块只能 GONE 命中的那个 TextView**，往上吃父容器会把「应用权限」和按钮一起吃掉（v1.6 的 bug，v1.8 修）
- v1.9 现状：安装页显示 图标/名称/版本/包名/来自/应用权限 + 退出安装/继续安装，扫描与风险文字全部隐藏
- 未处理：退出时的「是否删除安装包」弹窗不走 android.app.Dialog，hook 不到；成功页「自动清除安装包和残留」开关已被置 off

- v2.0~v2.4（当前装机版 /storage/emulated/0/DeepSeek/instclean/out/安装器净化_2.4.apk）：
  - 做法改成「自绘卡片盖在厂商安装页上」：content.addView(..., MATCH_PARENT)，绝不 setContentView 替换（厂商异步回调 NPE，k2() 崩进程）
  - 卡片字段：名称 / 包名 / 版本名 / 版本号 / 大小 / 安装来源 / minSdk·targetSdk / 签名 SHA-256 前 8 字节 / 权限数 / 当前已装版本 / 取消·安装
  - 点安装 → 自己的 PackageInstaller 会话（安装器有 INSTALL_PACKAGES，静默）→ 卡片上显示「正在安装…」→「安装完成」→ 1.2s 后自动关
  - 关键 extras（InstallStart 抓到 intent 后塞进去，厂商会自己跳过验证与删包）：oplus_extra_need_verification=false、oplus_extra_delete_apk_switch=false
  - appdetail 三页（HostCompatible / PackageParsingV2 / InstallGuide）置透明 + 无动画 finish，白屏 <0.9s
  - 未处理：厂商「手机已有相同的版本」弹窗仍在卡片下面出现（不影响卡片按钮）

- v2.5~v2.8（当前装机版 安装器净化_2.8.apk）：
  - 签名显示完整 SHA-256（32 字节大写，自动换行，可长按选中复制）
  - 「手机已有相同的版本」弹窗已干掉：厂商用 Activity.showDialog → 托管对话框，hook 了 OPlusPackageInstallerActivity#onCreateDialog(int,Bundle)、PackageInstallerActivity$ 两个 DialogFragment、s0/t0 的 onCreateDialog，以及 PackageInstallerActivity#k()/l()，接管期间一律返回 null → 弹窗不创建（hooks=12）
  - 实测：点 APK → 卡片（包名/版本/大小/来源/SDK/签名/权限/当前版本）→ 点安装 → 正在安装… → 安装完成 → 1.2s 自动关
  - 注意：find() 找不到类时只记 miss，不报错；改完记得看 logcat 里 hooks=N 是否变大，确认 hook 真装上了

- v2.9~v3.2（当前装机版 安装器净化_3.2.apk）：
  - 卡片改挂到 decorView（不是 android.R.id.content）并 bringToFront，盖掉厂商底部那层按钮栏
  - 权限改成可展开分组：默认收起，标题「权限 共 N 项 · 可申请 M 项 ▸」，点开列出全部，dangerous 权限（可主动申请）排前面标蓝并加「可申请」
  - 判断「可主动申请」= pm.getPermissionInfo(name).protectionLevel & PROTECTION_MASK_BASE == PROTECTION_DANGEROUS
  - 每个 APK 进来先清静态字段，解析完成后再移除旧卡片重建一次，避免残留上一个包的数据
- v3.3~v3.5：权限行改成和普通行同款（灰标签「权限」+ 深色「共 N 项 ▸」），不再用蓝色、不再写「可申请 N 项」；展开后 dangerous 权限排最前并带「可申请」标记，列表放进固定高度 340dp 的 ScrollView，不再撑爆卡片
- v3.7~v4.0：
  - 权限中文化：内置 60+ 条词典（Detail.DICT）+ getPermissionInfo().loadLabel 兜底，最后退回短名（应用私有权限如 com.android.systemui.FACE 没有官方中文）
  - 展开列表：可申请的在最上面（深色），中间空一行，下面是默认权限（灰色）；不再写「可申请」字样
  - 卡片顶部加 APK 图标（pi.applicationInfo.loadIcon(pm)，64dp，与标题同行）
  - 每个 APK 进入时把 permAll/permRuntime/permLabels/icon 一起清空，否则会串上一个包的数据（排序错乱就是这么来的）
- v4.1~v4.2：图标缩到 40dp（标题 20sp）；卡片补充信息：架构（lib/ 下 ABI 列表，无原生库时显示「无原生库」）、DEX（classes*.dex 数量与总大小）、调试（debug/release、testOnly）、签名方案（v1/v2/v3，靠尾部 APK Sig Block 42 + block id 判定）、签名者 SHA-256、权限分组、当前已装版本
- v4.3~v4.4：标题 28sp（与 40dp 图标齐高）；「SDK」行拆成「目标系统 / 最低系统」并显示 Android 版本名（API 21~37 映射表 osName()）；新增「加固状态」——按 lib/*.so 与 assets 名匹配 25 条常见壳特征（360加固 libjiagu、腾讯乐固 libshella/shellx/StubShell、梆梆 libsecexe/secmain、爱加密 libexec/ijiami、百度 libbaiduprotect、网易易盾 libnesec、几维 libkwscmm、顶象 libx3g、娜迦 libchaosvmp、通付盾 libegis、盛大 libapssec、阿里聚安全 libmoji/libmobisec 等），命中显示「检测到 XX」，否则「未检测到加固」
- v4.5：安装/取消改成圆角按钮（GradientDrawable，cornerRadius 12dp，取消 #EFEFEF/#333，安装 #1A73E8/#FFF，16sp，setStateListAnimator(null) 去阴影，minHeight/minWidth 归零避免被系统 Button 样式撑开）
- v4.6~v4.8：新增「签名者」（证书主体 O·CN·OU，原神实测显示 miHoYo · Genshin Impact · TianMing）、「组件」（活动/服务/接收器/提供者数量，需 getPackageArchiveInfo 加 GET_ACTIVITIES|GET_SERVICES|GET_RECEIVERS|GET_PROVIDERS）、「入口」（launcher activity，ActivityInfo.intentFilters 用反射取，直接引用编译不过）、「文件」（zip 条目数 + .so 数）、「安装位置」、「安装记录」（首装/更新时间，仅已装包）、「SHA-1」
- v4.9：按用户要求去掉「文件」「安装位置」「组件」三行（代码保留，只是不渲染）；当前行序：包名/版本名/版本号/大小/安装来源/加固状态/目标系统/最低系统/架构/DEX/调试/签名者/签名方案/签名/SHA-1/安装记录/权限/当前版本
- v5.1~v5.7 降级安装：
  - 先在 SessionParams 上反射设 installFlags |= 0x80 (INSTALL_ALLOW_DOWNGRADE)（字段确实存在且能过 Parcel，实测 installFlags=4194432 传到服务端），但 ColorOS 的 PMS 仍回 INSTALL_FAILED_VERSION_DOWNGRADE —— 只对 shell/root 放行（`pm install -r -d` 从 root 执行可以过）
  - 兜底：结果里命中 VERSION_DOWNGRADE 时，卡片底部出现红色「卸载并安装」+提示「系统拒绝降级安装 · 需先卸载（会清除该应用数据）」；点它先 PackageInstaller.uninstall，再轮询等包消失（不用广播，进程被回收会丢），然后自动重新 commit
  - 实测 v2.0 → v1.0 降级成功；临时 APK 不再立即删除（降级要重装一次）
- v6.0~v7.0 安装通道：点「安装」先走 root（`su -c "pm install -r -d <apk>"`，20s 看门狗；输出含 Success 即成功），拿不到 root / exec 失败就静默回退 `PackageInstaller` 会话，不再弹任何提示；只有真失败（签名不符、INSTALL_FAILED_*）才在卡片底部红字显示原因，降级被拒时额外给「卸载后安装（清除数据）」按钮
- ⚠️ 实测本机安装器进程 (u0_a229) exec `su`（/system/bin/su 存在）全部失败 ENOENT/EACCES → KernelSU 没放行这个 uid，所以 root 通道会立即回退普通通道；要真正生效需在 KernelSU 的 App Profile 里给「软件包安装程序」授权超级用户
- v7.1~v7.2 布局修复：卡片内容全部塞进 ScrollView（fillViewport），底部按钮栏放在 ScrollView 外面固定住，行数多也不会把按钮顶出屏幕；降级兜底里三个按钮改成「取消 / Root 降级」一行 + 下方「卸载后安装（清除数据）」，并修正了之前把 2 个 weight 按钮直接加进竖向 bar 导致宽度为 0（看不见）的问题
- v8.0~v8.3 root 通道（实测通过）：
  - 安装器进程 exec su 永远失败（该 uid 不在 KernelSU allowlist，且 App 私有缓存 /data/user/0/<pkg> 在 su 的命名空间/权限下也读不到）
  - 改法：安装器把 APK 复制到 /storage/emulated/0/DeepSeek/instclean_tmp_<ts>.apk（安装器有 MANAGE_EXTERNAL_STORAGE），再广播 com.dsh.instclean.ROOT_INSTALL 给模块 App（com.dsh.instclean，KernelSU 已授权 root）
  - 模块 App 的 RootInstaller 执行 su -c "cp <shared> /data/local/tmp/x.apk && chmod 644 … && pm install -r -d …；rm -f …"（必须先复制到 /data/local/tmp，否则 pm 由 system_server 读文件会报 Can't open file），回广播 com.dsh.instclean.ROOT_RESULT
  - 实测：dummy v2.0 → v1.0 降级成功，日志 `InstCleanRoot: code=0 out=Success` / `reply ok=true msg=已安装（root 通道）`，不卸载、不清数据
  - 安装器端 20 秒收不到回执会自动退回普通 PackageInstaller 会话，卡片不会卡在「正在安装…」
- v8.4~v8.5 收尾：去掉「本进程试 su」和 20 秒超时回退；点安装 = 复制 APK 到 /storage/emulated/0/Download/instclean_tmp_*.apk → 广播给模块 App（root 已授权）→ su 执行 cp /data/local/tmp + `pm install -r -d` → 安装器每 300ms 用 PackageManager 查版本号，版本对上就立刻「安装完成（root 通道）」
- 实测：dummy v2.0 → 点测试E(v1.0) 安装 → versionCode 变 1，未卸载、未清数据、无长时间等待
- 注意：root 写 /storage/emulated/0 下的结果文件会失败（FUSE），所以用版本号轮询判断成功，不再依赖回执广播/结果文件
- v9.0 速度修复：本地 `su` 尝试限时 1.2 秒（之前 20 秒看门狗 + 跨应用广播，导致点安装要等很久）→ 超时立刻改走普通 PackageInstaller 会话；只有当普通安装回报 VERSION_DOWNGRADE 时才自动转入 root 委派（复制到 Download + 广播 + 版本号轮询），普通安装恢复成一键即装
- v9.2：所有安装改由 root 守护执行，普通安装路径已移除。链路：安装器写 `<cache>/instclean_req.txt`（两行：apk 路径、结果路径）→ 守护进程读文件、cp 到 /data/local/tmp、`pm install -r -d`、把 OK/ERR 写回结果文件 → 安装器轮询结果 → 卡片显示完成
- 守护脚本 `/data/adb/service.d/instclean_daemon.sh`（KernelSU 开机以 root 常驻，0.2s 轮询）；**必须在重启后才常驻**，命令行里起后台进程会随会话结束被回收
- 实测（前台跑守护）：request → `OK` → dummy 2.0 降到 1.0，无卸载、无 Download 复制
- 保留：安装器自身若被 KernelSU 授权 root，会先走 1.2 秒本地 su 快路径，更快
- v9.3（按用户建议，全部用 shell）：守护脚本不再复制 APK，直接 `pm install -r -d <原路径>`；pm 由 shell 进程读文件、进不去应用私有目录，所以只做「临时 chmod 父目录 711 / cache 755 / apk 644 → 装完立刻按 stat 记录复原」，微秒级，实测 dummy 1.0→2.0 返回 OK，无任何复制
- 仅当原地安装仍失败（极少）才退回 /data/local/tmp 单次中转
- v9.4（按用户要求，砍掉守护脚本）：点安装 = 安装器进程直接 exec 一条 su 命令：
  `su -c "M1..M3=$(stat -c %a 目录); chmod 711 父目录; chmod 755 cache; chmod 644 apk; pm install -r -d apk; chmod 复原; true"`
  - 无守护进程、无请求文件、无复制；实测该命令在 root 下返回 Success（dummy 2.0→1.0）
  - **唯一前提**：KernelSU 必须授权给「软件包安装程序」com.android.packageinstaller（当前 allowlist 里只有 com.dsh.instclean / app.dsh.mobile / com.omarea.vtools 等，没有安装器）；未授权时卡片提示「没有拿到 root：请在 KernelSU 里给软件包安装程序授权」
  - /data/adb/service.d/instclean_daemon.sh 已删除，安装器缓存里的中间文件也清空
- v9.5~v9.6：**不用给安装器授权**，改回由已授权的「安装器净化」(com.dsh.instclean) 代跑 root：
  - 安装器把 APK 复制到 `/storage/emulated/0/Download/instclean_tmp_<ts>.apk` → 广播 `com.dsh.instclean.ROOT_INSTALL`（带 FLAG_INCLUDE_STOPPED_PACKAGES）→ App 的 RootInstaller 执行 `su -c "cp →/data/local/tmp→chmod→pm install -r -d→rm"` → 回广播 ROOT_RESULT
  - 模块 App 新增桌面入口 `MainActivity`（「安装器净化」图标），点一下即可让进程常驻，避免被冻结后广播收不到
  - RootInstaller 的路径白名单改成 `com.android.packageinstaller` 或 `/storage/emulated/0/Download/instclean_tmp_`
  - 实测：广播进来 → `InstCleanRoot: code=0 out=Success` → dummy 1.0→2.0，临时包已删
- v10.1（当前装机版）最终取舍：默认走**系统会话安装**（APK 只从 content:// 复制到缓存一次，实测点安装 1 秒内 `session result status=0 / INSTALL_SUCCEEDED`，无额外复制）；只有会话回报 `VERSION_DOWNGRADE`（降级）时才转 root 通道（复制到 Download → 广播给「安装器净化」→ su 原地装 → 轮询版本号确认）
- 结论：想「降级也不复制」，唯一办法是 KernelSU 给「软件包安装程序」com.android.packageinstaller 授权 root（安装器自己 1 秒内 su 原地装）；否则降级那一次必然有一次复制（模块 App 的挂载命名空间看不到安装器私有目录，实测 chmod /data/user/0 与 /data/data 均报 No such file）
- v10.2~v10.3（最终形态，按用户要求回退）：**只走系统会话安装**；降级被系统拒绝时**不做任何 root 操作**，只在卡片底部红字提示「版本降级被系统拒绝。要降级安装，请先在 KernelSU 里给『软件包安装程序』(com.android.packageinstaller) 授予超级用户权限，再回来点一次安装」
- 已删除：守护脚本、请求/结果文件、Download 中转复制、桌面启动页、root 委派调用；模块现在只剩三块：appdetail 页面净化（去扫描/隐私弹窗/推广页）、安装详情卡片、普通会话安装 + 降级提示
- 若日后给安装器授权 root，可把降级改回「安装器自己 su 原地装（chmod 目录→pm install -r -d→复原）」，一条命令、零复制
- v10.4（最终）：普通安装/升级走会话；会话回报 VERSION_DOWNGRADE 时**自动**用安装器自己的 su 原地降级：
  `su -c "chmod 711 父目录; chmod 755 cache; chmod 644 apk; pm install -r -d apk; chmod 复原; true"` —— 零复制
  实测：dummy v2.0 → 装 v1.0，日志 `InstClean: root install: Success`，`versionCode=1 / versionName=1.0 / lastUpdateTime 13:45:47`
  - 前提：KernelSU 已给「软件包安装程序」(com.android.packageinstaller) 授权 root（用户 2026-10-31 13:44 已授权，截图可见 ROOT 标记）
  - 模块不再有任何跨应用委派、守护脚本、Download 中转
- v10.5~v10.6：降级失败时的提示改成简洁版（不再带异常堆栈）：`降级失败：需要 root 权限 / KernelSU → 超级用户 → 给「软件包安装程序」（com.android.packageinstaller）授权后重试。`
- ⚠️ 实测：即使用户在 KernelSU 里给「软件包安装程序」授了 ROOT，安装器进程里 exec `/system/bin/su` 仍报 `error=2 No such file or directory`（KSU 的 su 挂载没有进到这个 uid 的命名空间）；所以这台机器上降级仍走不通，需要 root 直装时用 DSH/MT 管理器手动 `pm install -r -d`
