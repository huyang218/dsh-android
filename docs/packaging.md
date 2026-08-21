# 分发与打包形态

## 决定:不上 Google Play,GitHub 直发 apk

和 [`dsh-desktop`](https://github.com/huyang218/dsh-desktop) 一致。这不是妥协,是**同时解开两道锁**的那个选择。

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
apk(84 MB,实测)
├── assets/runtime/node.tar         90 MB  Node 二进制 + 它链接的 .so(aarch64)
├── assets/runtime/seed.tar        265 MB  dsh 运行时快照(node_modules)
├── assets/runtime/composition.tar 110 KB  手持 profile、agent 预设、本仓库两个插件
├── assets/runtime/stamp                   上面三个的身份,装机时用来判断要不要重解
└── Android 应用本体(WebView + 前台服务持有 Node 进程),零依赖,约 30 KB
```

**载荷不压缩,由 apk 自己压**:372 MB 的 tar 打进 84 MB 的 apk。这一条踩过坑,
见下面「两个只有真机才会告诉你的坑」。

首次启动由 [`RuntimeInstaller`](../app/src/main/java/io/github/huyang218/dshandroid/RuntimeInstaller.java)
把三个 tar 解到应用私有目录;之后每次启动比对 stamp,一致就直接起 host。实测
(Android 15 / API 35 模拟器):node 0.9 秒、seed 4.0 秒、composition 0.03 秒。

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

### 载荷怎么生成

[`scripts/prepare-runtime.sh`](../scripts/prepare-runtime.sh) 产出 `app/src/main/assets/runtime/`
(已 gitignore——75 MB 二进制不进仓库):

```sh
scripts/prepare-runtime.sh --from-device   # 从一台已铺好运行时的设备取 Node 树
scripts/prepare-runtime.sh                 # 之后用本地缓存的 node.tar 重打
```

seed 默认取本机 dsh-desktop 的活跃 runtime(`DSH_SEED_SRC` 可覆盖),打包时剪掉
android-arm64 上永远不会加载的东西:`node-pty`(手持形态本来就不挂它)、几个
`*-darwin-arm64` 可选依赖、以及 17 MB 的 `sharp-libvips-darwin-arm64`(sharp 在这里
走 wasm32)。**307 MB → 258 MB**,剪的都是"这台机器上不可能被 require 的文件"。

Node 树是 [`vendor/node.tar`](../vendor/),**进仓库**——它是构建输入,没有它别人
重打不出同一个 apk(90 MB 的 tar 在 git 包里约 33 MB,不碰 GitHub 的 100 MB 硬限制)。
它目前是 Termux 的 aarch64 构建,从一台手工铺过的设备上取下来的——**是探针,不是发行
方案**。按 termux-packages 的配方用自己的前缀重编仍然要做(见 [PLAN 线 A](../PLAN.md)),
而"打进公开分发的 apk"正是让这件事从工程洁癖变成许可与分发责任的那一步:里面的库
(OpenSSL、ICU、SQLite、zlib、libc++)全是宽松许可,可以分发,但 Apache-2.0 和 ICU
要求随附许可文本,apk 现在一份都没带。细节见 [`vendor/README.md`](../vendor/README.md)。

### 两个只有真机才会告诉你的坑

**一、AAPT 会把 `.gz` 资产解开,并且改名。** 最初三个载荷叫 `node.tar.gz`,打进 apk
以后变成 `assets/runtime/node.tar`——应用按自己写的名字去 open,得到
`FileNotFoundException`;而 Java 侧的 `GZIPInputStream` 拿到的是已经解压的 tar,
在 tar 还阻塞等输入时就悄悄结束了,表现为"卡住",不是报错。**结论:载荷用 `.tar`,
压缩交给 apk。**

**二、`/data/user/0/<包名>` 本身是一条指向 `/data/data/<包名>` 的符号链接。** 于是
"absolutePath 和 canonicalPath 不同就是符号链接"这个常见写法,会把这个应用数据目录
下的**每一个文件**都判成符号链接。递归删除因此退化成对一个非空目录调用 `delete()`,
整个安装在第一步就失败。判定要用 `Os.lstat` + `S_ISLNK`,不是路径比较。

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
