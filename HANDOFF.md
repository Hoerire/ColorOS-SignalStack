# HANDOFF —— ColorOS 堆叠信号（接手文档）

> 目标读者：下一个会话里的我。读完这一份就能改代码、能出包、能发版，不用去搜文件。
> 项目根：`/storage/emulated/0/DeepSeek/stacksig`

---

## 1. 这是什么

LSPosed 模块（libxposed API 102），作用域只有 `com.android.systemui`。
把 ColorOS 17 的**双排（堆叠）信号**搬到 ColorOS 15/16：主卡大信号 + 副卡小信号叠在同一槽位，左上角画 2G/3G/4G/5G 制式标。

设计红线（改代码前先记住）：

- **只换 Drawable，不动布局、不新增 View**。所有几何调整都在已有 View 的 padding / margin / LayoutParams 上做。
- **颜色不自己算**，转发 `setImageTintList` / `setColorFilter` 给三张子图，保证和电池、网速同色。
- 每个 hook 都必须 `try/catch (Throwable)` 吞掉异常，SystemUI 里抛异常 = 状态栏崩。

---

## 2. 目录结构

```
stacksig/
├── README.md                     对外说明 + 更新日志（改版必更）
├── build.sh                      构建脚本（5 步，见 §6）
├── HANDOFF.md                    本文件
├── src/io/github/hoerire/stacksig/
│   ├── StackSigModule.java        48 行  libxposed 入口：只认 com.android.systemui
│   ├── Engine.java              1233 行  全部 hook、状态栏几何、颜色、网速逻辑
│   ├── Res17.java                 90 行  反射 addAssetPath 建自身资源表，按名取 ic_s17_*
│   ├── VStack.java               128 行  主卡 + 副卡 + 制式标 → 一个 Drawable
│   └── StackDraw.java             59 行  降级用：C17 素材缺失时主卡 + 62% 副卡
├── meta/
│   ├── AndroidManifest.xml        包名 io.github.hoerire.stacksig + 版本号
│   ├── META-INF/xposed/
│   │   ├── java_init.list         入口类全名
│   │   ├── scope.list             com.android.systemui
│   │   └── module.prop            LSPosed 管理器显示的版本（易漏！）
│   └── res/drawable/              21 个 ic_s17_*.xml，C17 矢量图，打包进 APK
└── res_stacked/                   40 个原始 XML，素材来源，**不参与构建**
```

`res_stacked/` 只是当初从 C17 抽出来的素材参考，`build.sh` 只用 `meta/res`。要加新素材 → 往 `meta/res/drawable` 放，并确认 `Res17.get()` 的名字对得上。

打包进 APK 的资源命名规则：

| 前缀 | 含义 |
|---|---|
| `ic_s17_single_N` | 单卡满高，N=0..4 |
| `ic_s17_lte_signal_stacked_primary_N` | 双卡主卡，N=0..4 |
| `ic_s17_lte_signal_stacked_secondary_N` | 双卡副卡，N=0..4 |
| `ic_s17_2g/3g/4g/5g/5ga` | 制式标 |

---

## 3. 运行时结构（一条 hook 链）

```
SystemUI 进程启动
  └─ StackSigModule.onPackageLoaded("com.android.systemui")
       └─ Engine.install(module, classLoader)  → 注册 8 处 hook，返回成功数
            ├─ ImageView.setImageDrawable / setImageResource / setImageBitmap   → H
            ├─ TextView.setTextColor                     → 时钟换色 → refreshAll()
            ├─ StatusBarIconView.onDarkChanged            → 换色后重建 drawable
            ├─ View.setVisibility                         → 防止系统把藏起来的节点改回 VISIBLE
            ├─ Handler.sendMessageAtTime                  → 网速排期 4s/1s → 1s
            └─ NetworkSpeedView.applyNetworkState         → 网速右对齐
```

`H.intercept()` 是主循环：拿到被设图标的对象 → 判断 `entryName` 含 `mobile_signal` → `iv.post(stack(iv))`。

### stack() 一次合成（Engine.java:405）

```
1. 向上 8 层找「含 ≥2 个信号图标的容器」→ 双卡；找不到再找含 1 个的 → 单卡
2. primaryRoot / secondRoot 定主副
3. levelOfView()：优先用 setImageResource 抓到的资源名尾部数字，其次 Drawable.getLevel()
4. 双卡时 secondaryLevelBySub() 按 subId 直接问基带（副卡被 GONE 后系统不再更新它）
5. sig = "主等级/副等级/wifi|mobile"，与 LAST 相同则只收尾不重建
6. 250ms 节流（防 NR/LTE 抖动闪屏），首次合成不节流
7. build() → Res17 取图 → VStack(primary, secondary, tint, badge)
8. collapse(secondRoot) / collapseSiblings / collapseInnerSignals 收掉重复节点
9. tighten() / tightenRightPadding() / alignEdges() / lockSpeedWidth() 微调几何
```

`attachLayoutWatcher()` 挂了 `OnGlobalLayoutListener`，每次布局（≥90ms 间隔）重跑 `applyWifiRule / stack / lockSpeedWidth / tightenRightPadding / alignEdges`。
**系统每次重排都会冲掉 translation/margin，靠这个 listener 反复压回去。**

---

## 4. 关键方法索引（Engine.java）

| 行 | 方法 | 作用 |
|---|---|---|
| 44 | `install()` | 注册全部 hook |
| 64 | `hookDarkChanged()` | StatusBarIconView.onDarkChanged |
| 122 | `hookSpeedAlign()` | NetworkSpeedView.applyNetworkState |
| 145 | `alignSpeedRight()` | 网速盒子 WRAP_CONTENT + END 重力，右侧 2dp |
| 182 | `hookClockColor()` | TextView.setTextColor → refreshAll |
| 230 | `hookVisibility()` | View.setVisibility 兜底 |
| 256 | `hook()` / `H` | ImageView 三件套 hook |
| 334 | `retry()` | RETRY_DELAYS = {0,32,64,120,200,320,500,800,1200,1800,2500} |
| 357 | `hookNetSpeedInterval()` | Handler 排期改写，NETSPEED_INTERVAL=1000ms |
| 405 | `stack()` | 主合成入口 |
| 527 | `build()` | 取素材 + 组 VStack |
| 556 | `badgeNameFor()` | 按 getDataNetworkType() 选制式标 |
| 579 | `applyWifiRule()` | 隐藏系统自带制式小图与箭头 |
| 786 | `attachLayoutWatcher()` | 布局后重压 |
| 833 | `alignEdges()` | 两端边距拉平（EDGE_FILL 控制比例） |
| 891 | `tightenRightPadding()` | 右侧容器 padding 回收（EXTEND_DP 控制） |
| 958 | `lockSpeedWidth()` | 网速最小宽度锁死，位数变化不推动邻居 |
| 990–1079 | `collapse*()` / `forceHidden()` / `hiddenAncestor()` | 树清理与隐藏 |
| 1119 | `secondaryLevelBySub()` | 副卡等级兜底 |
| 1136 | `levelOfView()` | 从资源名 / getLevel() 取 0..4 |
| 1158 | `isWifiConnected()` | 三级判断：NetworkCapabilities → WifiManager → ConnectivityManager |
| 1195 | `ensureNetworkCallback()` | 网络变化 → refreshAll |
| 1212 | `refreshAll()` | invalidateSelf + 重跑 stack；**不要清 LAST** |

---

## 5. 可调参数（改视觉基本只动这几个）

| 位置 | 常量 | 现值 | 含义 |
|---|---|---|---|
| Engine.java | `EXTEND_DP` | `4f` | 右侧容器内边距回收量（dp）。越大整排越贴边 |
| Engine.java | `EDGE_FILL` | `0.5f` | 两端差值补齐比例。1.0=完全拉平，越小越靠中间 |
| Engine.java | `NETSPEED_INTERVAL` | `1000L` | 网速刷新周期 ms |
| Engine.java | `RETRY_DELAYS` | 数组 | 首帧失败重试时间表 |
| VStack.java | `BADGE_H` | `0.25f` | 制式标高度 / 图标高度 |
| VStack.java | `BADGE_RATIO` | `1.38f` | 制式标宽高比 |
| VStack.java | `BADGE_X` / `BADGE_Y` | `0.08f` | 制式标左上偏移 |
| StackDraw.java | `SCALE` | `0.62f` | 降级模式下副卡缩放 |

**1.5 的改动就是把 `EXTEND_DP` 从 8 降到 4、`EDGE_FILL` 从 1.0 降到 0.5**，因为外扩过头把时钟挤掉了。

---

## 6. 构建（在本机就能出包）

工具链都在扩展里，`javac/java/d8/aapt/apksigner/zipalign` 已装。**注意 `D8` 和 `APKSIGNER` 在 `share/java/`，不在 `lib/`**（`build.sh` 的默认探测会走错）。

```sh
BT=/data/user/0/app.dsh.mobile/files/engine/extensions/android-buildtools
cd /storage/emulated/0/DeepSeek/stacksig

ANDROID_JAR=/data/user/0/app.dsh.mobile/files/dsh-home/android.jar \
LIBXP_JAR=/storage/emulated/0/DeepSeek/build/stub/libxposed-api-102.jar \
KEYSTORE=/storage/emulated/0/源码/zafiro-15-1.8.1-Fork/app/src/main/assets/buildkit/debug.keystore \
KS_PASS=android KS_KEYPASS=android KS_ALIAS=androiddebugkey \
ANDROID_BUILD_TOOLS=$BT \
D8=$BT/share/java/d8.jar APKSIGNER=$BT/share/java/apksigner.jar \
AAPT=$BT/bin/aapt ZIPALIGN=$BT/bin/zipalign \
JAVA=$BT/lib/jvm/java-21-openjdk/bin/java JAVAC=$BT/lib/jvm/java-21-openjdk/bin/javac \
sh build.sh /tmp/ssbuild
```

产出 `/tmp/ssbuild/signed.apk`。

**若 `/data/user/0/app.dsh.mobile/files/dsh-home/android.jar` 不存在**，用 `ls $HOME/android.jar` 重新确认实际路径。

---

## 7. 装机与生效

```sh
# pm 读不了 /storage（SELinux fuse 上下文），必须先拷到 /data/local/tmp
su -c 'cp /data/local/tmp/ss.apk /data/local/tmp/ss.apk'  # 实际：先 cp 目标 APK 到 /data/local/tmp
pm install -r /data/local/tmp/ss.apk

# 让模块重新加载：重启 SystemUI
su -c 'killall com.android.systemui'

# 校验
su -c 'dumpsys package io.github.hoerire.stacksig | grep -E "versionName|versionCode"'
```

LSPosed 里作用域必须勾选「系统界面」，否则 hook 不生效、也看不到任何报错。

---

## 8. 发布：三个仓库 + 三个 release

| 角色 | 仓库 | 本地目录 | remote 名 | tag 规则 |
|---|---|---|---|---|
| GitHub 主仓 | `Hoerire/ColorOS-SignalStack` | `release/io.github.hoerire.stacksig` | `origin` | `1-<ver>` |
| LSP 官方 | `Xposed-Modules-Repo/io.github.hoerire.stacksig` | 同上 | `org` | `<versionCode>-<ver>` |
| LSP 私人 | `Hoerire/com.hg.stacksig` | `release/com.hg.stacksig` | `origin` | `1-<ver>` |

发版顺序（缺一步用户就看不到新版本）：

```sh
# 1) 本地 bump 三处：meta/AndroidManifest.xml、meta/META-INF/xposed/module.prop、README 更新日志
# 2) 拷贝源码与 APK 进两个 release 目录，commit
cp README.md build.sh 到 release/io.github.hoerire.stacksig/
cp -r src/. release/io.github.hoerire.stacksig/src/
cp -r meta/. release/io.github.hoerire.stacksig/meta/
git -C release/io.github.hoerire.stacksig -c safe.directory='*' commit -am "1.5：..."

# 3) 三个 remote 推送
git -C release/io.github.hoerire.stacksig -c safe.directory='*' push origin main
git -C release/io.github.hoerire.stacksig -c safe.directory='*' push org main
git -C release/com.hg.stacksig -c safe.directory='*' push origin main

# 4) 建 release（API）——body 用上版本的格式：### 版本 + 变更 + ### 安装
# 5) 传 APK：**必须用 node fetch，不要用 curl -d @file**（见坑 §10）
```

三个 release 都要传 `ColorOSStackSig-v<ver>.apk`。旧版本的 APK 从私人仓删除，只留最新的。


---

## 8.1 发布用凭据（本机文件，勿外传）

GitHub Personal Access Token（repo 权限，三个仓库共用）：

```
<GITHUB_TOKEN>
```

它已经内嵌在两个 release 目录的 remote URL 里，正常 push 不需要再输入：

```sh
git -C /storage/emulated/0/DeepSeek/release/io.github.hoerire.stacksig remote -v
# origin https://x-access-token:ghp_xxx@github.com/Hoerire/ColorOS-SignalStack.git
# org    https://x-access-token:ghp_xxx@github.com/Xposed-Modules-Repo/io.github.hoerire.stacksig.git
```

走 API（建 release、传 asset、查版本）时：

```sh
TOKEN=<GITHUB_TOKEN>
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/Hoerire/ColorOS-SignalStack/releases?per_page=1"
```

顺带记一下其它发布环境里的值：

| 用途 | 值 |
|---|---|
| 签名 keystore | `/storage/emulated/0/源码/zafiro-15-1.8.1-Fork/app/src/main/assets/buildkit/debug.keystore` |
| storepass / keypass | `android` / `android` |
| key alias | `androiddebugkey` |
| libxposed 接口 jar | `/storage/emulated/0/DeepSeek/build/stub/libxposed-api-102.jar` |

> 这份文件只留在本机，推仓库时会把 token 换成 `<GITHUB_TOKEN>` 占位符。
---

## 9. 排查手册

| 现象 | 原因 | 处理 |
|---|---|---|
| 图标完全没变 | 作用域没勾 / 模块没启用 / SystemUI 没重启 | 勾作用域 → `killall com.android.systemui` |
| 出现两个信号图标并排 | 副卡那份被系统改回 VISIBLE | `hookVisibility` 应已覆盖；看 `hiddenAncestor` 是否漏层 |
| 颜色不跟电池一致 | 走了 fallbackDark 兜底 | 检查宿主 ImageView 是否真的收到 tint |
| 信号比邻居大一圈 | 改过显示大小/分辨率，资源表还是旧 dpi | `Res17.ensure()` 会按 densityDpi 重建，确认没被缓存挡住 |
| 重启后好久才变堆叠 | 首帧容器未测量 | `retry()` 时间表；可加大次数 |
| 下拉通知栏那份不生效 | 面板里的实例不走 setImageResource | `levelOfView` 用 `lastKnownLevel` 兜底 |
| 卡二永远空信号 | 副卡被 GONE 后系统不再更新 | `secondaryLevelBySub()` 直接问基带 |
| 网速还在 4 秒跳 | 系统改用了非 Handler 排期 | `hookNetSpeedInterval` 失效，需要换 hook 点 |
| 时钟被挤/两端太贴边 | `EXTEND_DP` / `EDGE_FILL` 太大 | 调小这两个（1.5 就是这么修的） |
| LSPosed 里显示旧版本号 | 只改了 AndroidManifest，漏了 module.prop | 两处一起改 |
| GitHub 页面看不到新版本 | 网页/管理器缓存 | 硬刷新；release 要单独建，push 不会自动建 |

---

## 10. 两个必须记住的坑

**① 上传二进制不能用本机 curl 的 `-d @file`**
本机 `curl` 是 node 封装的 wrapper，`--data-binary @xx.apk` 会把路径字符串本身当请求体（实测 asset 只有 78 字节）。上传 APK 一律用 node：

```js
const buf = require('fs').readFileSync(apkPath);
await fetch('https://uploads.github.com/repos/OWNER/REPO/releases/ID/assets?name=NAME.apk', {
  method: 'POST',
  headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/octet-stream' },
  body: buf,
});
```

**② `pm install` 读不了 `/storage/emulated/0`**
报 `System server has no access to read file context u:object_r:fuse:s0` → 先 `cp` 到 `/data/local/tmp/` 再装。

---

## 11. 改版 checklist（照做不会漏）

1. 改 `src/` 里的代码
2. bump `meta/AndroidManifest.xml` 的 `versionCode` / `versionName`
3. bump `meta/META-INF/xposed/module.prop` 的 `version` / `versionCode`
4. `README.md` 加一节「### x.y」到更新日志
5. §6 构建
6. §7 装机 + 重启 SystemUI，肉眼确认状态栏正常
7. 更新 `/storage/emulated/0/DeepSeek/ColorOS16堆叠信号-v<ver>.apk`，删掉旧的
8. §8 同步源码/APK 到两个 release 目录、推三个 remote、建三个 release、传 APK
9. 旧版本 APK 从私人仓删掉

---

## 12. 当前状态（2026-10-30）

- 版本 **1.5**，versionCode **39**
- 最近改动：`EXTEND_DP 8→4`、`EDGE_FILL 1.0→0.5`，修「两端外扩过度致时钟被挤」
- 已发布：三个仓库 commit + 三个 release（`1-1.5` / `39-1.5` / `1-1.5`）
- 本地装机版本 1.5，作用域已启用
- 素材来源 ColorOS 17 状态栏矢量图，`res_stacked/` 留档
