# 手持 composition

apk 要带进设备的编排层。运行时把它铺成 `$DSH_HOME` 的两部分:

```
profiles/handheld/   → $DSH_HOME/profiles/handheld/
agent-presets/handheld/ → $DSH_HOME/.agent-presets/handheld/
```

还有第三件东西不在本目录,但和这层绑死:`profiles/handheld/package.json` 的
`dsh.profile.bundles` 里点名了本仓库的两个插件,**它们必须能从 profile 目录解析出来**。
都不是安装自带的行,所以要落到

```
packages/mobile-layout/       → $DSH_HOME/profiles/handheld/node_modules/dsh-plugin-mobile-layout/
packages/storage-no-hardlink/ → $DSH_HOME/profiles/handheld/node_modules/dsh-plugin-storage-no-hardlink/
```

(`storage-no-hardlink` 顶掉的是会话日志和附件两个存储后端;为什么非顶不可,见
[feasibility 四点七](../docs/feasibility.md#四点七selinux-不给应用做硬链接)。)

`dsh-app-boot` 的 `resolveBundleDir` 只认两个锚点:先安装目录,再 profile 目录——
后者顺着 Node 的父目录查找命中上面这条路径。解析不到就是启动期 fail loud
(`cannot resolve profile bundle ...`),不是静默降级。**别改成用 `--patch` 塞绝对
路径**:host 半边会加载,但客户端名册只收按包名装进 profile 的行,bundle 不会被服务,
`/plugins/<id>/client.js` 404,界面照样是桌面三栏。

这两个包由 [`scripts/prepare-runtime.sh`](../scripts/prepare-runtime.sh) 打进
`assets/runtime/composition.tar`,首启时由 `RuntimeInstaller` 解到上面那条路径——
是**实目录**,不是 Mac 那边 pnpm 的 `link:`(符号链接指向一个手机上不存在的 checkout)。
`bundles` 那行两边一样,不用动。

**两层都要改,漏一层就坏,而且坏得不明显。** 主机编排(`profiles/`)决定哪些服务
存在;agent 预设(`agent-presets/`)决定模型能看见哪些工具。出厂的两个预设
(`standard` / `minimal`)都假设有 shell,所以即使主机编排正确,建会话仍然会以
`agent-preset-invalid` 失败——而 UI 上只表现为"选了工作区却没反应",host 日志里
一个字都没有。那条错误只在 `session.create` 的 RPC 响应里。

无壳形态实际付出的代价,按痛感排序:

| 丢掉的 | 为什么 |
|---|---|
| `tool-bash` / `tool-pwsh` | 等 `shell`,而 `shell` 来自 `subprocess` |
| **`tool-fs-search`** | 等 `subprocess`——它的搜索是 shell 出去做的 |
| `permission`(权限预设) | 注入 `shell` 且断言 `sandboxMode` 存在 |

`tool-fs-search` 是这里最尖锐的一条:**agent 能读写你指给它的任何文件,但不能自己去
找**。剩下的探索手段只有 `tool-fs` 的目录列举。

`fs-sandbox` **必须留着**——名字有误导性,它才是提供 `ctx.fs` 的那一行,只注入
`sandboxPolicy`,从不碰 `subprocess`。把它当"沙箱"顺手禁掉,就等于把文件系统从
一个以文件系统为全部意义的形态里拿走。
