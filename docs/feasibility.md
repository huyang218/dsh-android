# 可行性:dsh 能不能整个跑在 Android 上

结论:**能,但要砍掉 shell,并且只有 Android。**

下面每一条都是从 `dsh-desktop` 打包用的 `seed.tar` 里解出的 `@deepseek-ai/dsh@0.1.0-rc.7`
及其依赖树读出来的,不是推测。行号指的是 seed 里那份构建产物。

## 一、原生模块:三个里只有一个是真的拦路虎

`dsh` 的依赖树里有四个原生扩展。逐个判定:

### `koffi` — 不是问题

`@deepseek-ai/dsh-fs-local`、`@deepseek-ai/dsh-session-persistence-jsonl`、
`@deepseek-ai/dsh-sandbox-windows-acl`、`@deepseek-ai/dsh-host-directory-picker-native` 依赖它。

但 `dsh-fs-local/lib/index.js` 里那段的原文注释是:

> Windows security-descriptor helpers for atomic local-file replacement.
> **Koffi loads lazily so non-Windows processes never open Win32 libraries.**

加载点是 `await import("koffi")`,紧接着 `koffi.load("advapi32.dll")` /
`koffi.load("kernel32.dll")`。`dsh-session-persistence-jsonl` 同构。**非 Windows 永不触碰。**

### `node-addon-require-builtin` — 不是问题

它是 `@deepseek-ai/dsh` 的直接依赖,预编译目标里没有 bionic
(只有 `darwin-arm64/x64`、`linux-arm64-gnu`、`linux-x64-gnu`、`win32-×3`),
看起来像死路。但看它唯一的消费者 `@deepseek-ai/cordis-plugin-loader/lib/index.js:15`:

```js
try {
  return require("node-addon-require-builtin").requireBuiltin(id);
} catch {}
```

拿不到就返回 `undefined`。真正的消费点在同文件 264 行:

```js
if (this.ctx.loader.internal) return await this.ctx.loader.internal.import(name, this.ctx.baseUrl, {});
else if (name.startsWith(".")) return await import(/* ... */);
```

有它走 Node 内部 ESM loader,没有就退回普通 `import()`。**缺了它丢的是 HMR 那条
热重载路径,不是应用。**

### `node-pty` — 这个是真的

`@deepseek-ai/dsh-subprocess-local/lib/index.js:4` 是顶层静态 import:

```js
import * as nodePty from "node-pty";
```

预编译只有 `win32-{arm64,x64}` / `linux-{arm64,x64}` / `darwin-{x64,arm64}`,全是 glibc,
没有 bionic 目标。而 `subprocess` 是 `@deepseek-ai/dsh-base/cordis.patch.yml:163` 的一行,
所以**所有出厂 composition 都会加载它**。

关键在于:它是**编排里的一行,不是内核依赖**。手持端换一份不含
`subprocess` / `sandbox` / `bash-sandbox` / `pwsh-sandbox` / `terminal*` 的 composition,
node-pty 就不在图里。

代价是 agent 没有 shell。这不是"降级版",是另一种形态:文件系统 + LLM + 会话 + 附件。
手机上要不要 shell,本来也是个真问题。

### `sharp` — 不是问题

`@deepseek-ai/dsh-attachment-local` 依赖它,而 `attachment-local` 在
`dsh-base/cordis.patch.yml:106` 是出厂行,不好摘。但 seed 里同时躺着
`@img/sharp-wasm32`,**有 wasm 兜底**。需要在真机上验证兜底路径确实被选中(见风险表)。

## 二、SQLite:干净

`@deepseek-ai/dsh-session-query-sqlite` 除了 `schemastery` 没有任何依赖——用的是 Node
内置的 `node:sqlite`。这一层零移植成本,前提是 Node 版本够。

## 三、Node 本身:有现成配方

`dsh` 要 Node ≥ 22(`node:sqlite` 就是这么来的)。Termux 的 aarch64 仓库现状:

| 包 | 版本 | 依赖 |
|---|---|---|
| `nodejs` | 26.4.0-1 | libc++, openssl, c-ares, libicu, libsqlite, zlib, libffi |
| `nodejs-lts` | 24.18.0-1 | libc++, openssl, c-ares, libicu, libsqlite, zlib |

**Node 22+ 在 Android bionic 上能跑,这是既成事实,不需要论证。** 但 Termux 的二进制
不能直接搬:它把前缀写死在 `/data/data/com.termux/files/usr`,要按 termux-packages 的
配方用自己的前缀重编。

## 四、host 与 client:上游架构本来就允许

- `@deepseek-ai/dsh-client-web`:客户端是**由 host 推送的插件名册**
  (`window.__DSH_BOOT__`),README 原文 "the shell makes zero composition decisions"。
  所以移动端 UI 是换一份名册,不是 fork 上游布局。
- `@deepseek-ai/dsh-client-ui-layout`:现有布局是三栏 AppFrame,带拖拽手柄、56px
  控制轨、让位链,几何状态连 localStorage 都不写。手机上直接用是灾难,必须换。
- `@deepseek-ai/dsh-client-connection`:浏览器 carrier 走 `/api` HTTP POST + 两条只下行
  的 WebSocket;**in-process carrier 满足同一抽象**。同设备上可以完全不起网络。

顺带:上游把一批方法钉死在 loopback(`host.pickDirectory`、`host.openPath`、整个
`settings.*` / `credentials.*`、部分 `agentPreset.*`),并明说 `/api` 那道 trust fence
"is a reachability policy, not authentication"。全本地形态下这些都不构成约束——
但它也意味着**这套东西一旦要远程,鉴权得自己写**,这正是本项目不走远程的原因之一。

## 四点五、真正的约束是文件系统,不是 shell

砍掉 shell 之后剩下的是"文件系统 + LLM + 会话 + 附件"。但在 Android 上,
**这句话里的"文件系统"默认是空的**:分区存储下应用只能自由读写自己的数据目录,
别的应用的数据看不见,共享存储要走 SAF 或 MediaStore。

也就是说,一个没有工作区的 agent 不是"减配",是**没有对象**。这比没有 shell 严重得多,
而且它决定这个应用值不值得做。

三条路,工作量差一个数量级:

| 路 | 前提 | dsh 侧的工作 |
|---|---|---|
| **`MANAGE_EXTERNAL_STORAGE`** | 不上 Google Play(Play 只把这个权限开给文件管理器类应用),从 GitHub 直发 apk——和 `dsh-desktop` 的分发方式一致 | **可能为零**:拿得到真实 POSIX 路径,`dsh-fs-local` 也许原样可用 |
| **SAF(Storage Access Framework)** | 能上架 | 要为 SAF 写一个 `FileSystem` 后端。`@deepseek-ai/dsh-fs` 是显式的 provider 契约层——README 原文 "A backend subclasses `FileSystem` and implements twelve primitives",且已有 `fs-local` / `fs-sandbox` / `fs-e2b` 三个实现,所以这是架构预留的口子,不是 hack。但 SAF 没有 POSIX 路径语义,路径映射是真工作量 |
| **只用应用私有目录** | 无 | 零。但 agent 只能对着用户手动导进来的文件干活 |

**不上架同时解开两道锁**:文件系统这道,以及 W^X 那道——Play 强制较新的 targetSdk,
自行分发则可以像 Termux 那样停在 targetSdk 28,可执行文件的位置限制随之消失。

考虑到 `dsh-desktop` 本来就是 GitHub 直发的非官方项目,**走同样的分发方式是省力的默认选择**。

## 五、iOS:排除

不是难,是不可能:

- 没有 JIT,V8 跑不了(苹果只给自家 WebKit 开例外)
- 不允许 `fork`/`exec`
- 审核条款不允许下发可执行代码

三条里任意一条单独成立就否决,而这三条都不是工程能绕开的。

## 六、未解决的风险

按"会不会推翻方案"排序:

| 风险 | 说明 | 怎么证伪 |
|---|---|---|
| **W^X / 可执行文件位置** | Android 10 起,targetSdk ≥ 29 的应用不能执行自己数据目录里的文件。Termux 长期靠停在 targetSdk 28 绕开。通行做法是把可执行文件塞进 APK 的 native lib 目录(`lib/arm64-v8a/`),或者干脆把 Node 作为 `libnode.so` 嵌入、用 embedder API 在线程里起——后者根本不 exec。**这是打包形态的分叉点,必须先定。** | 真机上跑一个最小 demo,两条路各试一次 |
| **Termux 配方能否换前缀** | 换前缀重编 Node 及其 7 个依赖库,工作量未知 | 先按 termux-packages 的 `TERMUX_PREFIX` 走一遍 |
| **`sharp` 的 wasm 兜底** | 需要确认 Android 上确实落到 wasm32 而不是硬找原生 `.node` | 装好后 require 一次看走哪条 |
| **前台服务存活** | Android 会杀后台进程;Node 得挂在前台服务上,还要面对 Android 14+ 对前台服务类型的收紧 | 长会话真机放置测试 |
| **无 shell 的 agent 还好不好用** | 这是产品问题不是技术问题,但它决定这个应用值不值得做 | 里程碑 1 之后自己用一周 |
