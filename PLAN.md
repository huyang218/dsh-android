# 计划

原则:**每个里程碑都要能被证伪。** 顺序是按"哪一步失败最贵"排的——先撞最可能
推翻方案的那面墙,不要等 UI 做完了才发现 Node 起不来。

## 里程碑 0:分叉点 —— 已全部定完

1. **手机上的 agent 要不要 shell** —— **不要。** composition 里不放
   `subprocess` / `sandbox` / bash / pwsh / terminal,`node-pty` 不进依赖图。
2. **上不上 Google Play** —— **不上,GitHub 直发 apk**,和 `dsh-desktop` 一致。
   理由与代价见 [packaging.md](docs/packaging.md):它同时解开文件系统和 W^X 两道锁。
3. **Node 怎么进 apk** —— 由第 2 条决定:**独立进程**,从应用数据目录直接 exec,
   由前台服务持有。不必走 `libnode.so` + embedder 那条路。
4. **移动 UI 插件放哪** —— **本仓库 `packages/mobile-layout/`**,不放
   [`dsh-plugins`](https://github.com/huyang218/dsh-plugins)。它跟着 apk 走版本,是这个应用的一部分,不是通用
   插件。但目录沿用那边的**一层平铺**姿势(`packages/<名字>/`),因为生态的扫描器
   只认这一层——留着将来单独发 npm 的余地,成本为零。

`targetSdk = 28`(`compileSdk` 用最新),沿用 Termux 验证过多年的姿势。

## 两条线,并行

**线 A 是证伪线,线 B 是工作量线,B 不依赖 A。** 之前把 UI 排在 Node 之后,理由是
"别在死地基上盖楼";但移动 client 插件在 Mac 上就能开发——起 `dsh web`,浏览器开手机
视口,不需要任何 Android。压着不做只是让最耗时的一段更晚开始。

**并行的代价**:线 A 若失败,线 B 的产出对本项目无用——它只能服务"手机浏览器连桌面
host",而那是[明确不做](#明确不做)的形态。这是拿可能白做的 UI 工,换更早的界面反馈,
以及"名册该留哪些行"的答案。接受。

### 线 A:Node 在手机上把 dsh 起起来(证伪点)

不做 UI,不做应用,只要一件事:**一台真机上,嵌进来的 Node 跑起 `dsh web`,
`curl` 拿到 200。**

**结论:跑通了(2026-08-20,API 28 / arm64-v8a 模拟器)。**

```
$ curl http://127.0.0.1:3199/          # adb forward 到设备的 3080
HTTP 200  11948 bytes  0.013751s
```

- [x] **Node 在 Android 上跑**:`v24.18.0`。**没有重编**——直接用 Termux 的 aarch64
      二进制,把它依赖的 `.so` 一起搬到自己的目录,启动时设 `LD_LIBRARY_PATH`。
      能成是因为它的解释器是系统的 `/system/bin/linker64`,而 **bionic 的链接器先查
      `LD_LIBRARY_PATH`,再查 `DT_RUNPATH`**(RUNPATH 里写死的 termux 前缀因此被绕过)。
- [x] **`node:sqlite` 可用**:`:memory:` 建表插查都正常。
- [x] **`sharp` 落到 wasm**:`sharp.versions` 里带 `emscripten`,确认走的是
      `@img/sharp-wasm32`,没有去找原生 `.node`。
- [x] **手持 composition 能启动**:禁用行见
      [feasibility](docs/feasibility.md#砍-shell-不止砍一行真机实测)——比原计划多两行。
- [x] **`dsh web` 起来了,应答 200**,`__DSH_BOOT__` 名册正常下发。
- [x] **从应用数据目录 exec 二进制**:成立(API 28,见 packaging.md)。
- [x] **应用自己持有 host(Android 15 实测)**:`NodeService`(前台服务)用
      `ProcessBuilder` 拉起 Node,`MainActivity` 轮询 loopback 端口后才加载 WebView。
      进程树 `dshandroid(4465) → node(4533)`,同 uid;**dsh 的客户端在应用的 WebView
      里渲染出来了**,开发机上没有任何进程参与。这一步之后,`10.0.2.2` 那个开发夹具
      已经从代码里删掉——应用只认 `127.0.0.1`。
- [x] **选工作区 → 建会话 → 进到模型配置**:走通了。中间挡了一道
      `agent-preset-invalid`——出厂预设都假设有 shell,所以手持形态要自带 agent 预设,
      不只是改主机编排。整套编排已收进 [`composition/`](composition/README.md)。
- [ ] 按 termux-packages 的配方用自己的前缀重编(见下,**仍然要做,但不再是前置**)
- [x] **运行时打进 apk(2026-08-21 完成)**:三个 tar 进 assets(node 90 MB /
      seed 265 MB / composition 110 KB),`RuntimeInstaller` 首启解包,之后靠 stamp
      跳过。**84 MB 的 apk,装完不需要电脑**。解包实测 node 0.9s、seed 4.0s、
      composition 0.03s。`Runtime.java` 那句"换成 apk 内解包时不用改别处"成立了——
      布局没动。两个踩到的坑(AAPT 解 `.gz`、`/data/user/0` 本身是符号链接)见
      [packaging.md](docs/packaging.md)。
- [ ] 进程守护与退避重启;应用退出时整树收干净

**这一步跑不通,整个方案作废——现在它跑通了,剩下的都是工程量。**

#### 探针与发行方案的区别

上面用的是 **Termux 的二进制**,这是**探针,不是发行方案**。它证明的是"这条路通",
不是"就这么发"。重编仍然要做,理由是许可与分发责任、以及 Termux 前缀在 npm/node-gyp
等路径上还会露头。但**它不再是前置条件**了:证伪点已过,重编从"必须先做"降级成
"工程量里的一项"。

#### 三个踩到的坑(都不在原计划里)

1. **`--expose-internals`**:CLI 会为监听用户 patch 文件而匿名挂一个 HMR,在没有
   `node-addon-require-builtin` 的 Android 上直接抛错。这订正了 feasibility 原来
   "缺了它只丢热重载"的判断——详见那份文档的订正块。应用自己的启动路径应绕开 CLI
   的 patch 监听器,而不是长期带这个标志。
2. **砍 shell 要连 `permission` 一起砍**,否则 `1 entry did not activate`。
3. **`adb push` 不保留符号链接**,而 Termux 的 lib 目录大量用软链(31 项里 18 条是
   软链)。打包进 apk 时同样要面对这件事——assets 里也没有软链。

### 线 B:移动 client 插件(在 Mac 上做,不碰 Android)

上游 `packages/client/` 下约三十个 `ui-*` 包,要动的东西分三层,**成本差一个数量级**,
所以按这个顺序做:先用名册层砍掉不该出现的,再看还剩多少真需要重写。

- [x] **机制打通(已验证)**:`packages/mobile-layout/` 顶掉 `ui-layout`,rc.7 的 web
      客户端在 390×844 视口渲染出单列 + 抽屉 + 底部单,零控制台报错。
      **顶掉 `ui-layout` 连带要接管三件事**,少一件就废:
      ① 唯一的 `root` 槽注册(它同时声明四个子槽);
      ② `layout` 服务(`toggleSidebar`/`openDetails`/`closeDetails`,rc.7 里九个包 inject 它);
      ③ **ThemePresenter**——把 `ctx.theme` 快照投到 document 上,砍掉就整个界面无样式。
      子槽声明必须和上游逐字一致,这才是"其余名册原样加载"的前提。
      **rc.7 的四个子槽是 `sidebar` / `conversation` / `details` / `shell.overlay`**;
      rc.5 README 写的 `conversation.empty` 已过时,以装机版本为准。
- [x] **顶栏(已做)**:48px + `env(safe-area-inset-top)`,一个抽屉开关。第一次跑就
      撞到的缺口——"打开侧边栏"按钮原本住在侧边栏里,抽屉一关就没入口了(桌面上它
      靠 56px 控制轨常驻,手机没有那条轨)。文案走上游 `ctx.locale.register` +
      `locale: NS` 的 `t` 座位,不自己发明 i18n。
- [x] **选中会话自动收抽屉(已做)**:手机一次只能显示一样东西,导航和目的地不能
      同时占屏。桌面上侧边栏是常驻列,上游 AppFrame 没有这个动作可做。
- [x] **`ui-conversation` 把宽内容切掉——表格和代码块已解决**(2026-08-21)。那三个
      选项里选了"从外面覆盖",但不挂构建哈希:`[class*="_scrollBody"] table` 改成
      `display:block;overflow-x:auto`,宽表自己横滚而不是被容器切掉。实测注入一张
      983px 宽的表:容器 404px,`scrollWidth` 983,**能滚到**。代码块同理。
      **剩下的**:非 `table`/`pre` 的宽内容(比如宽图、长 URL)仍然只能靠容器,
      要不要让 `_scrollBody` 整体可横滚,没定——那会让整段对话都能左右晃,不一定更好。
- [ ] **`ui-trajectory` 自带桌面双栏**:表格 + 一个 `<aside>` 事件详情,390px 下互相
      遮挡,连点都点不动(Playwright 报 pointer events 被 aside 拦截)。
      **名册层的头号候选:手机上直接不出现。**
- [ ] **`details` 根槽的"打开"路径未验证**:单已挂载、内容在渲染、"关闭详情"按钮在,
      但 rc.7 里没找到从会话流触发 `openDetails()` 的入口(工具行是就地展开,
      `Inspect` 也不开单)。**别当成已通。**
- [x] **Settings 能在手机上用了**(2026-08-21 实测)。原本两件事叠在一起:
      ① 抽屉用 `transform` 滑动,把 Settings 的 `position:fixed` overlay 压进了抽屉
      那 300px 里(**从侧边栏打开的任何模态都一样**),改用 `left`/`bottom` 位移解开;
      ② 对话框自己是桌面形状(panel 800px + 188px 导航列,412px 下内容只剩 176px),
      用一张收在 `mobile-layout` 的覆盖表把它改成整屏 + 顶部标签条。
      General / Models / Plugins(含 140 条插件列表)四个页都验过,页面级无横向滚动。
      细节与选择器为什么这么写:[feasibility 四点二](docs/feasibility.md#四点二布局层的两个教训都是实测撞出来的)。
- [ ] **名册层(最便宜)**:哪些 `ui-*` 压根不进名册——`ui-directory-picker-native`
      仍是候选。~~`ui-settings-plugin-inventory`、settings 的桌面部分~~ 已不必砍:
      覆盖之后它们在手机上是能用的。不适配,是不出现——但能适配就不用砍。
- [x] **抽屉手势(2026-08-21)**:左边缘右划开、抽屉上左划关,竖向意图优先所以不会
      抢滚动。**是阈值不是橡皮筋**——跟手要用 `transform`,而带 transform 的祖先会俘获
      `position:fixed` 后代(就是把 Settings 压进抽屉那个 bug),离散开合才保得住那个修复。
      配套要在 Android 侧认领左边缘,否则手势导航先把它当返回吃掉,见
      [feasibility 四点二](docs/feasibility.md)。
- [x] **点击目标 ≥44px(2026-08-21)**:量出 13 个小于 44 的控件(最小 28),覆盖后 0 个。
- [ ] **布局层剩下的**:会话列表、详情单的手势(下拉关闭)、输入区的键盘让位。
- [ ] **主题层**:移动端尺度落到 `ui-theme` 的 `--dsw-*` token 上。上游
      [`docs/web-styling.zh.md`](https://github.com/deepseek-ai/deepseek-harness)
      写死了规矩:功能包只能用 `--dsw-alias-*` 语义别名,**不得自定义全局主题、
      不得在功能组件 CSS 里写主题选择器**。别把移动端的覆盖散进各个包。
- [ ] 保留 conversation / tool / trajectory / attachment
- [ ] 验收:Mac 上 `dsh web` + 手机视口,能建会话、发消息、拿到回复,且没有横向滚动

机制参照 `dsh-plugins/packages/astock-chart`(已跑通的 `ui/` 类插件):
`dsh.client.platform: "web"`、`inject: [...]`、`exports["./client"]`。

**装法有个坑**:客户端名册只收**按包名装进 profile** 的行。用 `--patch` 以绝对路径
插入,host 半边会加载、`--dump-config` 也看得见,但扫描器认不出它的 `dsh.client`,
bundle 不会被服务(`/plugins/<id>/client.js` 404)。所以要 `link:` 进 profile。

本机跑法(profile `mobile` 已建好,和日常在用的 `web` 互不影响):

```sh
export DSH_HOME="$HOME/Library/Application Support/dsh-desktop/dsh-home"
BIN="$HOME/Library/Application Support/dsh-desktop/runtime/slot-b/node_modules/@deepseek-ai/dsh/lib/bin.js"
node "$BIN" plugin --profile mobile install     # 插件是 link:,改代码不用重装
node "$BIN" --profile mobile --port 3099        # 浏览器开手机视口访问
cd packages/mobile-layout && npm test           # 纯函数与服务面的单测
```

### 线 B 的运行载体:一个空壳 apk(已建)

`app/` 已经能装能跑:`targetSdk 28` / `compileSdk 35` / `minSdk 21`,**零依赖**
(framework 的 `Activity` + `WebView`,没有 androidx),debug apk 11KB。

**它指向开发机(`10.0.2.2:3099`),这是开发夹具,不是产品形态。** 产品形态里 host
跑在本机 127.0.0.1 上(线 A);连另一台机器是[明确不做](#明确不做)的。它存在只是为了
让线 B 对着**真正的 Android WebView** 调样式,而不是对着一个假装成手机的桌面浏览器。
线 A 一旦跑通,`dsh.devUrl` 换成 loopback,这层夹具就没了。

```sh
export JAVA_HOME=/usr/local/opt/openjdk@17     # AGP 8.7.3 要 JDK 17
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.huyang218.dshandroid/.MainActivity
```

已验证:装得上、起得来、能连到开发机。**没验证的是 Node**——apk 里现在一个二进制都
没有,线 A 还是全开的。

- [x] 空壳 apk,targetSdk 28,WebView 全屏
- [x] WebView 版本检测(见下)
- [ ] 前台服务持有 Node 进程(等线 A 有二进制可持有)
- [ ] `dsh.devUrl` 换成设备本机 loopback

**模拟器上撞到的第一件事:WebView 版本不等于 Android 版本。** API 28 的默认镜像自带
Chromium 66,dsh 前端要 80+,直接 `Unexpected token ?` 白屏。详见
[feasibility 第四点六节](docs/feasibility.md#四点六webview-的版本不等于-android-的版本)——
它和"不上架"耦合:更新链不在我们手里。应用现在会检测并给一句人话,不再白屏。
UI 开发要用现代系统镜像(`system-images;android-35;google_apis;arm64-v8a`)。

## 汇合点:WebView 里跑起来

两条线都活了才做这一步。

- [x] **WebView 加载 dsh 前端 dist + 线 B 的名册**(2026-08-21,Android 15 / API 35
      模拟器)。`mobile-layout` 进了 `__DSH_BOOT__`,`ui-layout` 只剩两条 inject 边、
      不再有自己的行;界面从 56px 控制轨换成 48px 顶栏 + 抽屉,抽屉开关、遮罩关闭
      都通,`手持模式` 预设在会话头上。装法见
      [`composition/README.md`](composition/README.md) 的第三件东西。
      **没验的:** 建会话发消息拿回复(没配 API key,见下一条)、详情单的打开路径。
- [ ] 决定走 in-process carrier 还是本地 HTTP(同设备下前者更干净,见 feasibility 第四节)
- [x] **存储层能在 Android 上落盘**(2026-08-21,同一台模拟器)。撞到第三条结构性
      约束:**SELinux 不给应用做硬链接**,而 dsh 有两处用 `link(2)` 发布新文件——
      会话日志和附件对象。会话那处的表现是**每个新会话的第一条消息必失败**,UI 只写
      "This turn failed",host 日志无痕。换法与代价见
      [feasibility 四点七](docs/feasibility.md#四点七selinux-不给应用做硬链接),
      实现在 [`packages/storage-no-hardlink/`](packages/storage-no-hardlink/)。
      实测:`session.jsonl.zstd` 落地 5175 字节,无残留 `.tmp`,不再有 `denied { link }`。
- [x] **建会话、发一条消息、拿到模型回复**(2026-08-21,Android 15 / API 35 模拟器,
      **运行时来自 apk 解包**):新会话 `packaged-runtime` 建起来、消息发出去、模型
      回了话(LLM 5 秒,87 tok/s),会话日志正常落盘、无残留 `.tmp`。模型自己报的
      能力面也对上了:"I have no shell, so I can only read/write files"。
      **仍未在真机上跑过**,只在模拟器上。凭据存储长期要落到 Android Keystore(里程碑 4)。
- [ ] **附件那处只做了实现,没实测**——要发一张图才走得到,发图路径本身也没验过

## 里程碑 4:变成一个真的应用

到这里才轮到"应用"该有的东西,大部分问题 `dsh-desktop` 已经答过一遍,可以照抄思路:

**外壳的展示与操作(2026-08-21 做了一轮)**——这些是全新安装那次自己看出来的,不是
列表推出来的:

- [x] **有图标了**。之前桌面上是安卓默认小绿人:自适应图标 + 五档 PNG 兜底,
      一台设备加一个还在跑的进程,**不用上游的鲸鱼**——非官方项目借人家的标识等于
      替它背书。
- [x] **首启不再是一分半的白屏**:图标 + 真实百分比进度条 + 当前载荷名。百分比要能
      算出来,靠的是打包时把三个 tar 的字节数写进 `stamp`——**应用自己量不出来**,
      载荷在 apk 里是压缩的,`openFd` 直接拒绝,流上报的长度是压缩后的。
- [x] **返回键先关抽屉/详情单,再走历史,最后才退出**。手机上抽屉就是导航,越过它
      直接退出像是应用随机崩了。`mobile-layout` 挂 `window.__dshmBack()`,外壳问它
      "这一下算不算你的";实测抽屉 `data-drawer-open` 从 `true` 变没,应用留在前台。
      退出用 `moveTaskToBack` 而不是 `finish`:host 是前台服务,下次回来该是活的
      WebView,不是重新加载一遍客户端。
- [x] **失败页给证据**:原来只印一个私有目录里的日志路径——看的人根本打不开。现在
      直接把日志尾巴贴在屏幕上,加一个重试按钮。
- [x] **深色模式**:`values-night` 一套色,`windowBackground` 跟着走,否则点开图标
      到第一帧之间是一道白闪。
- [x] **中英文案**:外壳自己的几句话进 `strings.xml` / `values-zh`。
- [x] **超时预算按实测改**:90 秒是拍脑袋的,而模拟器满载时组插件树要 **104 秒**,
      于是屏幕在 host 还在起的时候就宣布失败。改成 5 分钟,并且**真失败由服务在进程
      退出的那一刻上报**,计时器只是兜底。

- [ ] 前台服务持有 Node 进程,应用退出时整树收干净
- [ ] 进程守护与退避重启
- [ ] 运行时更新(双槽位)与回退
- [ ] 数据快照与恢复
- [ ] 凭据存储(Android Keystore,不能照搬桌面端)

## 明确不做

- **iOS** —— 见 [feasibility.md 第五节](docs/feasibility.md#五ios排除),不是排期问题
- **连接远程 host** —— 那需要自己写鉴权层,而且违背这个项目的前提
- **fork 上游布局** —— 换名册、加一个布局包,不改 `ui-layout` 本身
- **复用 dsh-desktop 的代码** —— 借鉴思路,不共享实现
