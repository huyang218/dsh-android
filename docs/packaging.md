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
├── 前端 dist + 移动端 client 名册
└── Android 应用本体(WebView + 前台服务持有 Node 进程)
```

Node 以**独立进程**运行,由前台服务持有;应用退出时整树收干净——和 `dsh-desktop`
里 `src/server.js` 解决的是同一个问题,只是换了宿主。

## 待验证

这些都要在里程碑 1 里用真机回答,不要靠文档推断:

- [ ] targetSdk 28 + `WRITE_EXTERNAL_STORAGE` 在 Android 13/14/15 上是否确实还给真实路径
- [ ] 从应用数据目录 exec 二进制,在 Android 13/14/15 上是否都还成立
- [ ] 当前 Android 版本拒绝安装的 targetSdk 下限到底是多少
- [ ] `dsh-fs-local` 在拿到真实路径后能否原样工作(还是仍要一个 Android 后端)
- [ ] 前台服务能不能在长会话里稳住 Node 进程
