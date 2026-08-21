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

有它走 Node 内部 ESM loader,没有就退回普通 `import()`。

> **订正(2026-08-20,真机实测)**:上面这句"缺了它丢的只是热重载路径,不是应用"
> **是错的**。在 Android 上它是**硬性启动失败**:
>
> ```
> Error: failed to apply loader entry 2cdba830 (@deepseek-ai/cordis-plugin-hmr):
>        --expose-internals is required for HMR service
> ```
>
> 原因不在 composition 里,而在 CLI:`dsh/lib/profile-boot-*.js` 为了**监听用户
> patch 文件的实时改动**,在 `ctx.get("hmr") === undefined` 时会自己 `loader.create`
> 一个**匿名** `cordis-plugin-hmr` 条目。而 `dsh-web-app` 恰好把 `hmr` 那行禁掉了,
> 所以这条路一定会走到。没有 `node-addon-require-builtin` 时,HMR 的构造函数就要求
> `--expose-internals`,拿不到就抛。
>
> 探针里用 `node --expose-internals` 绕过去了,启动成功。但**应用不该长期依赖这个
> 标志**:正确的解法是应用自己的启动路径不要走 CLI 的 patch 监听器(手机上也没有人
> 会去手改 `cordis.patch.yml`)。

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

> **真机实测补充(2026-08-20)**:代价比"没有 bash"更宽一点。除了 `tool-bash` /
> `tool-pwsh` 等 `shell`,**`tool-fs-search` 等 `subprocess`**——它的搜索是 shell 出去
> 做的。所以无壳形态里 agent **能读写你指给它的任何文件,但不能自己去找**;剩下的
> 探索手段只有 `tool-fs` 的目录列举。这是这个形态最尖锐的一条实际损失。
>
> 反过来,`fs-sandbox` **必须留着**:名字有误导性,它才是提供 `ctx.fs` 的那一行,
> 只注入 `sandboxPolicy`,从不碰 `subprocess`。把它当"沙箱"顺手禁掉,等于把文件系统
> 从一个以文件系统为全部意义的形态里拿走。

### 编排要改两层,不是一层

主机编排(profile)决定哪些**服务**存在;**agent 预设**决定模型能看见哪些**工具**。
出厂的两个预设都假设有 shell(`standard` 挂 `tool-bash`,`minimal` 本身就是个 bash
agent),所以主机编排改对了、预设没改,建会话仍然失败:

```
agent-presets: preset "standard" failed to mount: 3 row(s) did not activate:
tool-bash (@deepseek-ai/dsh-tool-bash): waiting for shell
tool-fs (@deepseek-ai/dsh-tool-fs): waiting for fs
tool-fs-search (@deepseek-ai/dsh-tool-fs-search): waiting for subprocess
```

**这个失败在界面上和日志里都看不见。** UI 只表现为"选了工作区却没反应",host 的
stdout 一个字都没有——那条消息只在 `session.create` 的 RPC 响应体里。是接上 WebView
远程调试、挂钩 `fetch` 才看到的。手持形态自己的预设见
[`composition/`](../composition/README.md)。

### 砍 shell 不止砍一行(真机实测)

`subprocess` 拿掉之后,`shell` 服务就没了,而 `permission`
(`@deepseek-ai/dsh-permission-presets`)**硬依赖**它:

```
Error: dsh: 1 entry did not activate
@deepseek-ai/dsh-permission-presets: pending (waiting for service: shell)
```

它 `static inject = ["shell", "approval", "sessions"]`,而且构造函数里直接断言
`ctx.shell.sandboxMode !== undefined`——"挂在一个不做约束的执行器上是配置错误"。
所以无壳组合里它必须一起关掉,没有软降级。

实测能启动的手持 composition,禁用行是:
`subprocess`、`sandbox`、`sandbox-policy`、`bash-sandbox`、`pwsh-sandbox`、
`fs-sandbox`、`permission`。

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

### 四点一、"改样式适配手机"要动的是三层,不是一层

上游源码 `packages/client/` 下有 **31 个 `ui-*` 包**。移动端适配的成本按层分,差一个
数量级:

| 层 | 是什么 | 成本 |
|---|---|---|
| **名册层** | 哪些 `ui-*` 压根不出现(`ui-directory-picker-native`、`ui-settings-plugin-inventory`、settings 的桌面部分) | 配置级。**不适配,是不出现**——比适配它们省事得多 |
| **主题层** | `ui-theme` 独占 `--dsw-*` 静态色阶、语义别名、排版、动效、全局样式表 | 配置级,但有硬规矩,见下 |
| **布局层** | `ui-layout` 的三栏 AppFrame | **唯一必须写代码的一层**:拖拽手柄、56px 控制轨、让位链是结构不是皮肤,换变量换不掉 |

主题层的硬规矩来自上游 `docs/web-styling.zh.md`,不是风格建议:功能包**只能**用
`--dsw-alias-*` 语义别名,**不得另行定义全局主题**,功能组件 CSS **不得包含主题选择器**
(明暗主题覆盖属于主题所有方),且**不得引入组件库或 Tailwind**。所以移动端的尺度覆盖
要收在 `ui-theme` 的 token 上,不能散进各个功能包——散了就是在跟上游的样式系统对着干。

机制上这条路是通的,不需要新口子:`dsh-plugins/packages/astock-chart` 已经是跑通的
`ui/` 类客户端插件——`dsh.client.platform: "web"`、`inject: ["...ui-slots", "...ui-tool"]`、
`exports["./client"]` 指向 `client/client.js`。移动布局插件是同一套机制,换个注入点。

顺带:上游把一批方法钉死在 loopback(`host.pickDirectory`、`host.openPath`、整个
`settings.*` / `credentials.*`、部分 `agentPreset.*`),并明说 `/api` 那道 trust fence
"is a reachability policy, not authentication"。全本地形态下这些都不构成约束——
但它也意味着**这套东西一旦要远程,鉴权得自己写**,这正是本项目不走远程的原因之一。

### 四点二、布局层的两个教训,都是实测撞出来的

**一、别用 `transform` 做抽屉的滑动。** 这不是风格问题:transform——哪怕是抽屉打开时
的单位矩阵——会让该元素成为其内部所有 `position: fixed` 后代的**包含块**。上游的对话框
渲染在"谁打开它"的那棵子树里,而 Settings 的入口按钮住在侧边栏里,于是:

```
412px 视口,Settings 的 overlay:position:fixed, z-index:1000, 实测宽度 300px
                              ← 正是抽屉的宽度,不是视口宽度
```

表现是对话框被压进抽屉那一条里、一行一个词、右半边看不见。**凡是能从侧边栏打开的模态
都是同一个下场**,不只 Settings。改成用 `left` / `bottom` 做位移就解开了(代价:这两个
过渡不再走合成器,300px 的面板上不算代价)。`packages/mobile-layout/` 里有一条单测钉住
这件事,防止有人"顺手优化"回 transform。

**二、功能包自带的桌面对话框,是第四件要处理的事**(名册/主题/布局三层之外)。
`ui-settings-general` 的 `SettingsRoot.module.css` 写死
`.panel{width:800px;max-width:calc(100vw - 48px);display:flex}` 加一列
`flex:none;width:188px` 的导航;412px 视口下内容列只剩 **176px**。这不在主题 token 的
覆盖范围内,也不是 `ui-layout` 的结构——只能从外面覆盖。

选择器怎么写才不脆:上游是 CSS Modules,线上类名是 `<构建哈希>_panel`,**哈希每次构建
都变,局部名不变**,所以用 `[class*="_panel"]` 挂上游的**源码标识符**,而不是构建产物。
仍然是耦合:上游改局部名,这些规则会静默失效(对话框退回桌面形态,看得见、不会崩)。
真正的解法是上游加媒体查询,这是不 fork 的前提下能做到的最好一档。

**所有对别人包的覆盖收在一张表里**(`mobile-layout` 的 `overrides.css`),并且整体锁在
`@media (max-width:640px)` 里——同一个插件在笔记本上打开 handheld profile 时不能改样子。
这两条都有单测。

**三、左边缘不是你的。** 抽屉的标准手势是从左边缘右划,但在手势导航下**那正是系统的
返回手势**——实测 `input swipe` 从 x=8 划进来,应用被退到桌面,而 frame 连一个
`touchstart` 都没收到。解法是 `setSystemGestureExclusionRects`(API 29+),这也正是
Material 的 DrawerLayout 在做的事。平台每条边最多让出 200dp,所以只认领屏幕中段的一
条带:拇指够得着,四个角还留给系统,返回手势不会被整条吃掉。

**四、覆盖上游控件要用 `[class]` 提权。** 上游自己的规则是类选择器,`button{...}` 这样
的类型选择器永远比不过它——实测把 `min-height:44px` 写成 `button{}`,那个
`min-height:28px` 的工作区按钮纹丝不动。写成 `button[class]{}`(0,1,1)就压得住,而且
**一个上游类名都不用写死**:它说的是"带 CSS Modules 类名的按钮",不是某个具体的类。

**五、点击目标要量,不要凭感觉。** 这个 frame 上一共 13 个可交互元素小于 44px,最小的
28px——上游是按鼠标做的,那是它的合理选择,但手机上 44px 是地板。量完再改,改完再量:
现在是 0 个。

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

## 四点六、WebView 的版本不等于 Android 的版本

模拟器上第一次跑就撞到:应用起来了,页面全白,logcat 两行说明一切——

```
I chromium: Uncaught SyntaxError: Unexpected token ?   ← 前端 bundle 里的 ?. / ??
I cr_LibraryLoader: native library version "66.0.3359.158"
```

Android 9 (API 28) 的默认系统镜像自带 **Chromium 66**,而 dsh 的前端产物用了 `?.` /
`??`(ES2020),要 **Chromium 80+**。

关键在于**这不是 Android 版本的问题**:WebView 在 Android 上是可独立更新的组件
(Play 商店里的 "Android System WebView"),所以一台 Android 9 的真机只要更新过
WebView 就能跑,而一台没有 Play、或者 ROM 自带 vendor WebView 的设备,哪怕系统版本很新
也可能跑不了。

**这条和"不上架"的决定是耦合的**:GitHub 直发意味着用户设备上的 WebView 由谁更新、
更新到哪一版,我们完全不控制。所以:

- `minSdk` 不是真实下限。真实下限是**设备上的 WebView 版本**,和 API level 正交。
- 应用必须在启动时认出这件事,给一句人能看懂的话,而不是白屏。已实现:
  `MainActivity` 解析 WebView 的 UA 取 Chromium 主版本号,低于阈值就不加载页面。
- 阈值目前定在 **80**,依据是上面那条语法错误,**不是**对客户端全部特性用法的审计。
  真实下限只能等真机跑起来再收紧。

## 四点七、SELinux 不给应用做硬链接

第三条结构性约束,和前两条一样不是排期问题:**Android 的 SELinux 策略不允许应用对
自己数据目录里的文件做 `link(2)`**。内核审计记录是直证(真机日志,`adb logcat`):

```
avc: denied { link } for comm="libuv-worker" name="session.jsonl.zstd.061ec4043e4b.tmp"
  scontext=u:r:untrusted_app_27:s0 tcontext=u:object_r:app_data_file:s0
  tclass=file permissive=0 app=io.github.huyang218.dshandroid
```

用 `ln` 在同一目录复现,同样 denied(`scontext=u:r:runas_app`)。要点:

- **不是没申请的权限**。manifest 里没有对应条目可加,`MANAGE_EXTERNAL_STORAGE` 无关。
- **不是 targetSdk 能换的**。denied 的主体是 app 域对 `app_data_file` 这一类,
  和四点五那条"停在 28 就有真实 POSIX 路径"是两回事:路径是真的,`link` 依然没有。
- **共享存储更没有**。FUSE 的 `/sdcard` 本来就不支持硬链接。

dsh 里踩到它的一共两处,全量 grep 过 rc.7 的运行时,只有这两处用 `link`:

| 位置 | 症状 | 什么时候炸 |
|---|---|---|
| `dsh-session-persistence-jsonl` `lib/index.js:1128` | `EACCES: permission denied, link '...session.jsonl.zstd.<hex>.tmp'`,UI 只显示 "This turn failed" | **每个新会话的第一条消息**,host 日志里一个字都没有 |
| `dsh-attachment-local` `lib/index.js:192` | 包装成 `ATTACHMENT_WRITE_FAILED`,EACCES 在 `cause` 里 | 只有发图时 |

**上游为什么用硬链接**:`link(tmp, final)` 一次买到两个性质——排他创建(名字被占就
EEXIST,不会静默覆盖别人已发布的文件)和内容原子(读者要么看不到,要么看到完整文件)。
**`rename` 只保住后者**,所以它不是能直接替换上去的。

我们的替代是"先占名再改名",两个性质都留着:

```
open(final, O_CREAT|O_EXCL)   → 名字归我们,否则 EEXIST,和 link 同一个信号
写 tmp,fsync                 → 完整内容,写在旁边
rename(tmp, final)            → 原子地盖掉我们自己的占位文件
```

**代价说清楚**:占名到 rename 之间,final 路径上是一个 0 字节文件。这个窗口里来的读者
会看到一个空文件,而上游那条路径上他什么也看不到。单进程的手持 host 没有这样的读者;
放到多进程共享的机器上,这个取舍就是错的。

实现落在 [`packages/storage-no-hardlink/`](../packages/storage-no-hardlink/),两个子类各覆盖
一个方法,其余全部继承上游。**不是 fork**:编码器、协调器、追加路径、torn-tail 恢复、
产物格式仍然是上游的,升级 runtime 不用动。会腐坏的只有被覆盖的那两个方法的形状——
上游若改了发布序列,这里要跟。

一个装法上的坑:loader 的 patch 把 `name` 当**校验**而不是覆盖
(`patch: name mismatch for %C ... skipping`),所以不能把已有行指到别的包,只能
`disabled: true` 加 `insert`——也因此被禁那行原本带的 config 要在新行里重写一遍。

**实测(2026-08-21,Android 15 / API 35 模拟器)**:换上之后同一条消息不再报 EACCES,
`sessions/<项目>/session-<id>/session.jsonl.zstd` 落地 5175 字节,没有残留 `.tmp`,
`logcat` 里不再出现新的 `denied { link }`。这一轮的失败停在
`llm-deepseek: MISSING_CREDENTIAL`——没配 API key,是另一件事。

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
| **设备上的 WebView 太老** | 前端要 Chromium 80+,而 WebView 由 Play 独立更新,不随系统版本走(见第四点六节)。不上架 = 我们不控制这条更新链 | 已在模拟器上证实会白屏;应用现在会检测并提示。真实下限待真机收紧 |
| **无 shell 的 agent 还好不好用** | 这是产品问题不是技术问题,但它决定这个应用值不值得做 | 线 A 跑通之后自己用一周 |
