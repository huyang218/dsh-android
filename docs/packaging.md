# 分发与打包形态

## 决定:不上 Google Play,GitHub 直发 apk

和 [`dsh-desktop`](../../dsh-desktop) 一致。这不是妥协,是**同时解开两道锁**的那个选择。

### 它解开了什么

| 锁 | 上架的话 | 不上架 |
|---|---|---|
| **agent 有没有工作区** | 分区存储强制生效,只能自己写 SAF 后端(为 `@deepseek-ai/dsh-fs` 实现十二个原语),而且 SAF 没有 POSIX 路径语义 | targetSdk 停在 28 保留 legacy external storage,拿得到**真实 POSIX 路径**,`dsh-fs-local` 有希望原样可用 |
| **可执行文件能放哪** | W^X 生效,不能从应用数据目录 exec,Node 得做成 `libnode.so` 用 embedder API 嵌进去 | targetSdk 28 下可以从数据目录 exec,**Node 就是个普通的独立进程** |

第二把锁的解开顺带把里程碑 0 剩下的那个技术选择也消掉了:不必走 nodejs-mobile
那条"把 Node 编成共享库、改 embedder"的路,直接跑二进制。

### 先例

Termux 就是这么做的。实测其 `master` 分支的 `gradle.properties`:

```
minSdkVersion=21
targetSdkVersion=28
compileSdkVersion=36
ndkVersion=29.0.14206865
```

`compileSdk 36` 配 `targetSdk 28`——用新 SDK 编译,按旧规则运行。这正是我们要的姿势,
而且 Termux 在这个姿势上跑了很多年,还顺带证明了
[Node 22+ 在 bionic 上能跑](feasibility.md#三node-本身有现成配方)。

### 代价

- **更新要自己做。** 没有 Play 的分发和更新通道。`dsh-desktop` 那套(对照 GitHub
  Release 版本号、热更新 + 整包更新两条路)的**思路**可以搬,实现得重写。
- **用户要开"未知来源安装"。** 非官方项目本来就是这个受众,可接受。
- **低 targetSdk 的长期风险。** Android 近几个大版本在给可安装的 targetSdk 设下限
  (Android 14 起拒绝过低的 targetSdk 应用安装)。28 目前安全,但趋势在收紧,
  **具体门槛和未来走向需要核实**——这是这个决定唯一的长期不确定性。
- **要按老规则写。** targetSdk 28 下前台服务、权限请求等的行为是旧语义,
  照抄新文档会踩坑。

## 由此确定的打包形态

```
apk
├── Node 二进制(aarch64,按 termux-packages 的配方用自己的前缀重编)
├── dsh 运行时快照(seed,思路同 dsh-desktop 的 seed.tar)
├── 手持 composition(不含 subprocess / sandbox / bash / pwsh / terminal 行)
├── 前端 dist + 移动端 client 名册(布局插件源码在本仓库 packages/mobile-layout/)
└── Android 应用本体(WebView + 前台服务持有 Node 进程)
```

Node 以**独立进程**运行,由前台服务持有;应用退出时整树收干净——和 `dsh-desktop`
里 `src/server.js` 解决的是同一个问题,只是换了宿主。

## 出包与签名:本机打包,GitHub 只放结果

**apk 不在 CI 里构建**,由维护者在自己机器上打完上传 Release
([`scripts/release.sh`](../scripts/release.sh))。GitHub Actions 只跑两个插件包的
单测([`.github/workflows/test.yml`](../.github/workflows/test.yml))。

理由是签名:GitHub 直发没有 Play 站在中间,**签名是用户判断"这次更新和上次同源"的
唯一依据**,而 Android 不允许换签名覆盖安装——密钥丢了,所有已装用户都得卸载重装。
在 CI 里签名意味着密钥必须以 secret 的形式存在于 GitHub 上;**在本机签名,密钥根本
不用离开这台机器**,也就没有那份泄露面。代价是出包这件事不可复现于他人之手,而且
忘了跑就没有包——对一个非官方项目,这个代价比密钥外置划算。

```sh
scripts/release.sh v0.1.0             # 单测 → assembleRelease → 校验签名 → 建 Release
scripts/release.sh v0.1.0 --dry-run   # 打包和校验都做,不上传
```

脚本里三件事是刻意的:

- **先跑单测再打包**。apk 里现在一行 JS 都没有,布局和存储插件坏了它照样能编出来。
- **找不到 `app-release.apk` 就停**,并说明多半是没配签名。构建产物叫
  `app-release-unsigned.apk` 时脚本拒绝上传——不签名的包发出去,等于把后续所有更新
  的路堵死。
- **用 `apksigner verify` 校验而不是相信**:signingConfig 悄悄回退的话,应该由我们
  发现,不是由用户发现。

### 签名

密钥只存在于本机:仓库根目录的 `keystore.properties`(已 gitignore)指向同样
gitignore 掉的 `.jks`。`app/build.gradle` 的判断只有一条——`keystore.properties`
在不在。**不在就照样构建,出未签名的 apk**:新克隆的人要能编得动,而"未签名"会写在
文件名里,不会悄悄用 debug 密钥糊过去。

```sh
keytool -genkeypair -v -keystore release.jks -alias dsh-android \
  -keyalg RSA -keysize 4096 -validity 10000
printf 'storeFile=release.jks\nstorePassword=…\nkeyAlias=dsh-android\nkeyPassword=…\n' > keystore.properties
```

`-validity 10000`(约 27 年)不是随手写的:密钥过期意味着无法再发布能覆盖安装的更新。
**备份 `release.jks`**——它没有副本,丢了就换不回来了。

### lint 那条必须关掉的规则

`lintVitalRelease` 会用 `ExpiredTargetSdkVersion` 卡住 release 构建:
"Google Play requires that apps target API level 33 or higher"。这是 **Play 的政策
检查**,而这个应用不上 Play——`targetSdk 28` 正是整个方案的地基。所以
`app/build.gradle` 里显式 `disable 'ExpiredTargetSdkVersion'`:把这件事说出口,
而不是让 release apk 永远编不出来。

### 上传的包现在还不是成品

`scripts/release.sh` 写进 Release 说明里的那句要一直留着:**apk 里没有 Node 二进制,
也没有 dsh 运行时快照**,装上去会一直等一个不存在的 host。运行时仍要 adb 铺,
见 [PLAN 线 A](../PLAN.md) 里"运行时打进 apk"那一条。

## 待验证

这些都要在[线 A](../PLAN.md#线-anode-在手机上把-dsh-起起来证伪点)里用真机回答,不要靠文档推断:

- [ ] targetSdk 28 + `WRITE_EXTERNAL_STORAGE` 在 Android 13/14/15 上是否确实还给真实路径
- [x] **从应用数据目录 exec 二进制:Android 15 上成立。** 这是这份文档最要紧的赌注,
      现在有实测:`targetSdk 28` 的应用在 Android 15 (API 35, arm64) 上,由**应用进程
      自己** `ProcessBuilder` 拉起 `files/node/bin/node`,进程树是
      `io.github.huyang218.dshandroid(4465) → node(4533)`,同一个 uid `u0_a207`。
      不是 `run-as` 代跑,是应用自己 fork 的。Android 9 上也先验过一次作为基线。
      **W^X 那把锁,targetSdk 28 确实还开着。**
- [ ] 当前 Android 版本拒绝安装的 targetSdk 下限到底是多少
- [ ] `dsh-fs-local` 在拿到真实路径后能否原样工作(还是仍要一个 Android 后端)
- [ ] 前台服务能不能在长会话里稳住 Node 进程
