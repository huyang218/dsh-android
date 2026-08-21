# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 这个仓库现在是什么

**只有文档,没有代码。** 三次提交全是方案收口,产物是 `README.md` / `PLAN.md` /
`docs/feasibility.md` / `docs/packaging.md`。没有 Gradle 工程、没有 `build.gradle`、
没有测试——所以**没有构建、lint、测试命令可写**,`.gitignore` 里的 `build/` `.gradle/`
`local.properties` 是为将来预留的。

目标:把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)(`dsh`)的
**host 和 client 一起装进 Android 手机**,不连服务器、不依赖另一台开着的电脑。非官方项目。

接下来是 PLAN.md 里**两条并行的线**:

- **线 A(证伪点)**:一台真机上,嵌进来的 Node 跑起 `dsh web`、`curl` 拿到 200。
  **跑不通,整个方案作废。**
- **线 B(工作量大头)**:移动端 client 插件,住在本仓库 `packages/mobile-layout/`。
  **它不依赖 Android**——Mac 上起 `dsh web`、浏览器开手机视口就能开发。

早期版本的 PLAN 写着"里程碑 1 之前不写任何 UI",已经推翻:压着线 B 只会让最耗时的
一段更晚开始。代价是线 A 若失败线 B 白做,这个取舍已经做过,不要重开。

## 已经定死的决策(不要重新论证,也不要"顺手优化"掉)

每一条背后都有证据,写在 `docs/feasibility.md`(结论来自 `dsh-desktop` 的 `seed.tar`
里解出的 `@deepseek-ai/dsh@0.1.0-rc.7` 依赖树,不是推测)。改动前先读那一节。

| 决策 | 结论 | 一句话理由 |
|---|---|---|
| 平台 | **仅 Android** | iOS 无 JIT、禁 `fork`/`exec`、审核禁下发可执行代码,三条各自成立即否决 |
| agent 能力面 | **没有 shell** | `node-pty` 是 `dsh-subprocess-local` 的顶层静态 import 且无 bionic 预编译;换一份不含该行的 composition 就永不加载 |
| Node 形态 | **独立进程,从数据目录 exec** | 不走 `libnode.so` + embedder;由 targetSdk 28 解锁 |
| 分发 | **不上 Google Play,GitHub 直发 apk** | 同时解开文件系统和 W^X 两道锁 |
| `targetSdk` | **28**(`compileSdk` 用最新) | 沿用 Termux 多年验证过的姿势:新 SDK 编译、旧规则运行 |
| UI | **换 client 名册,不 fork 上游布局** | 上游客户端本就是 host 推送的插件名册,"the shell makes zero composition decisions" |
| 移动 UI 插件放哪 | **本仓库 `packages/mobile-layout/`** | 跟着 apk 走版本,不是通用插件;目录仍一层平铺,留发 npm 的余地 |
| host↔client | **进程内 carrier,不走网络** | 同设备无远程,也就没有鉴权层要写 |

**明确不做**:iOS、连接远程 host、复用 `dsh-desktop` 的代码(借鉴思路,不共享实现)。

## 移动 UI 适配:三层,成本差一个数量级

"通过插件改样式兼容手机"准确的说法是三层杠杆(证据见 `docs/feasibility.md` 第四点一节):

- **名册层**——哪些 `ui-*` 压根不进名册。配置级。**不适配,是不出现**,先用这层砍。
- **主题层**——`ui-theme` 的 `--dsw-*` token。配置级,但上游 `docs/web-styling.zh.md`
  写死了规矩:功能包只能用 `--dsw-alias-*` 语义别名,不得自定义全局主题、不得在功能
  组件 CSS 里写主题选择器、不得引入组件库或 Tailwind。**移动端覆盖要收在 ui-theme,
  不能散进功能包。**
- **布局层**——`ui-layout` 的三栏 AppFrame。**唯一必须写代码的一层**,CSS 解决不了:
  拖拽手柄、56px 控制轨、让位链是结构不是皮肤。

机制照抄 `../dsh-plugins/packages/astock-chart`(已跑通的 `ui/` 类客户端插件)。

## 三个最容易被忽略的约束

1. **真正的拦路虎是文件系统,不是 shell。** 砍掉 shell 后剩"文件系统 + LLM + 会话 +
   附件",但 Android 分区存储下这句话里的文件系统默认是空的。**没有工作区的 agent
   不是减配,是没有对象**——这比没有 shell 严重得多。`docs/feasibility.md` 第四点五节
   列了三条路(`MANAGE_EXTERNAL_STORAGE` / SAF 后端 / 只用私有目录),工作量差一个
   数量级,当前选的是第一条。
2. **SELinux 不给应用做硬链接,而 dsh 用 `link(2)` 发布新文件。** 两处:会话日志
   (`dsh-session-persistence-jsonl`)和附件对象(`dsh-attachment-local`)。前者的表现是
   **每个新会话的第一条消息必失败**,UI 只写 "This turn failed",host 日志无痕。已由
   `packages/storage-no-hardlink/` 顶掉那两行(占名 + rename,不是简单换 `rename`)。
   证据、代价和装法的坑见 `docs/feasibility.md` 四点七。**这条和 targetSdk 无关,
   别试图靠改 targetSdk 或加权限解决。**
3. **targetSdk 28 意味着按老规则写。** 前台服务、权限请求都是旧语义,**照抄新版
   Android 文档会踩坑**。这个选择唯一的长期不确定性是"未来 Android 拒绝安装的
   targetSdk 下限"(`docs/packaging.md` 待验证清单第三条)。

## 参考实现在哪(读,不要 import)

- **`../dsh-desktop`**([github.com/huyang218/dsh-desktop](https://github.com/huyang218/dsh-desktop))—— 兄弟项目,Electron 壳。同一批问题它已经答过一遍:
  `src/server.js`(进程归属与整树收干净)、`src/runtime.js`(双槽位运行时与更新)、
  `scripts/prepare-seed.mjs`(把当前活跃 runtime 快照成 `seed.tar`)、
  `scripts/prepare-node.mjs`(把 Node 工具链打成 `node-runtime.tgz`)。
  Android 侧的宿主换成前台服务,实现要重写。
- **`../dsh-desktop/seed.tar`** —— 事实来源。判定原生模块、composition 出厂行
  (`dsh-base/cordis.patch.yml`)时解这个包看,不要靠记忆。
- **`~/Documents/code/open/deepseek-harness`** —— dsh 上游源码。
- **`../dsh-plugins`**([github.com/huyang218/dsh-plugins](https://github.com/huyang218/dsh-plugins))—— 插件 monorepo,含自己的 CLAUDE.md 与本机 dsh 运行环境说明。

## 写文档时的调子

现有四份文档的写法是**每条结论都带证据和代价**:引原文注释、给文件名行号、列
"怎么证伪",风险按"会不会推翻方案"排序而不是按难易。新增内容照这个来——尤其是
不要把"待验证"清单里的项目当成已知事实往正文里写。文档为中文。

- 里程碑与验收标准:`PLAN.md`
- 技术判定与证据:`docs/feasibility.md`
- 分发形态与待验证项:`docs/packaging.md`
