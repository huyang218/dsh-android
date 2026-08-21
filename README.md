# dsh Android

[![test](https://github.com/huyang218/dsh-android/actions/workflows/test.yml/badge.svg)](https://github.com/huyang218/dsh-android/actions/workflows/test.yml)

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)(`dsh`)的 Android 应用:把 dsh 的 host 和 client **一起装进手机**,不连任何服务器,也不依赖任何一台开着的电脑。

> **非官方项目**,与 DeepSeek 无隶属关系,也未获其背书。与
> [`dsh-desktop`](../dsh-desktop) 是兄弟项目,共享同一套立意——运行时、存储、进程
> 全部由应用自己持有——但**不共享代码**:桌面端是 Electron 外壳,这里是 Android
> 应用加一个嵌入式 Node 运行时。

## 为什么不是"手机连电脑"

因为那要求另一台机器一直开着。这个项目的前提是手机上独立可用:飞行模式下打不开
模型,但应用本身、会话历史、文件都还在手上。

代价写在下面的[形态决策](#形态决策)里,不小,先看清楚再决定要不要做。

## 形态决策

| 决策 | 结论 | 为什么 |
|---|---|---|
| **平台** | **仅 Android** | iOS 上没有 JIT、不允许 `fork`/`exec`、审核不允许下发可执行代码。三条里任意一条都足以否决,而这三条都不是靠工程能绕过去的。 |
| **agent 能力面** | **没有 shell** | `node-pty` 是 `@deepseek-ai/dsh-subprocess-local` 的顶层静态 import,而 `subprocess` 是 `dsh-base` 出厂编排里的一行。手持端换一份不含该行的 composition,就永远不会加载它。换来的是:文件系统 + LLM + 会话 + 附件,能读能写能对话,不能跑命令。 |
| **Node 从哪来** | **嵌进 apk,作为独立进程运行** | dsh 要 Node ≥ 22。Termux 的 aarch64 仓库里 `nodejs 26.4.0` 和 `nodejs-lts 24.18.0` 都是现成的,证明 Node 22+ 在 bionic 上能跑;但 Termux 的二进制把前缀写死在 `/data/data/com.termux/files/usr`,不能直接搬,要按它的配方用自己的前缀重编。 |
| **分发** | **不上 Google Play,GitHub 直发 apk** | 同时解开两道锁:`targetSdk 28` 保留真实 POSIX 路径(否则 agent 没有工作区,这比没有 shell 严重得多),且可执行文件能从数据目录 exec(否则 Node 得编成共享库嵌入)。详见 [docs/packaging.md](docs/packaging.md)。 |
| **UI** | **WebView + 自己的 client 名册** | dsh 的浏览器客户端是一份由 host 推送的插件名册,shell 本身不做任何编排决策。所以手机 UI 不是 fork 上游布局,而是换一份名册。上游给的是三层杠杆:名册层(哪些 `ui-*` 包不出现)、主题层(`--dsw-*` token)、布局层(顶掉三栏 AppFrame)。前两层是配置级的,布局层要自己写一个包——详见 [feasibility 第四节](docs/feasibility.md#四host-与-client上游架构本来就允许)。 |
| **移动 UI 插件放哪** | **本仓库 `packages/mobile-layout/`** | 它是这个应用的一部分,跟着 apk 走版本,不是通用插件,所以不放 [`dsh-plugins`](../dsh-plugins)。目录仍按那边的一层平铺姿势(`packages/<名字>/`),保留将来单独发到 npm 的余地。 |
| **host↔client 传输** | **进程内,不走网络** | `@deepseek-ai/dsh-client-connection` 的 in-process carrier 与浏览器 carrier 满足同一抽象。同一台设备上没有远程,也就没有鉴权层要写——上游那道 `/api` trust fence 天然是 loopback。 |

## 状态

**证伪点已经过了。** 2026-08-20,在 API 28 / arm64-v8a 模拟器上,Node 24.18.0 起了
`dsh web`,`curl` 拿到 **HTTP 200**;`node:sqlite` 可用,`sharp` 落到 wasm。所以这个
项目不再是"看上去可行",而是"跑过一次"。细节和踩到的三个坑见
[PLAN.md 线 A](PLAN.md#线-anode-在手机上把-dsh-起起来证伪点)。

剩下的是[两条并行的线](PLAN.md#两条线并行):

- **线 A(已过证伪点)**:把这套东西装进 apk,由前台服务持有;用自己的前缀重编 Node。
- **线 B(工作量大头)**:移动端的 client 插件。它**不依赖线 A**——在 Mac 上起
  `dsh web`、浏览器开手机视口就能开发,反馈快得多。

代价是线 A 若失败,线 B 的产出对本项目无用(它只能服务"手机浏览器连桌面 host",
而那是明确不做的形态)。接受这个代价,因为布局层最耗时间,且它会反过来暴露名册
该留哪些行。

- 可行性结论与证据:[docs/feasibility.md](docs/feasibility.md)
- 分发与打包形态:[docs/packaging.md](docs/packaging.md)

## 与 dsh-desktop 的关系

能借鉴的是**思路**,不是代码:双槽位运行时更新、进程归属与守护、数据快照与恢复、
插件管理——这些问题在手机上同样存在,而桌面端已经把答案写过一遍。但实现要重写,
因为承载它们的东西从 Electron 主进程换成了 Android 的前台服务。
