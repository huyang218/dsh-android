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

`targetSdk = 28`(`compileSdk` 用最新),沿用 Termux 验证过多年的姿势。

## 里程碑 1:Node 在手机上把 dsh 起起来(证伪点)

不做 UI,不做应用,只要一件事:**一台真机上,嵌进来的 Node 跑起 `dsh web`,
`curl` 拿到 200。**

- [ ] 按 termux-packages 的配方,用自己的前缀编出 Node 24 或 26 的 aarch64 构建
- [ ] 按里程碑 0 的选择把它塞进一个空壳 apk,能启动并打印 `process.version`
- [ ] 把 dsh 的运行时装进应用数据目录(桌面端的 seed 打包思路可以照搬)
- [ ] 写一份**不含 `subprocess` / `sandbox` / bash / pwsh / terminal 行**的 composition
- [ ] 起 `dsh web`,本机 `127.0.0.1` 应答 200
- [ ] 确认 `sharp` 落到 wasm32、`node:sqlite` 可用

**这一步跑不通,整个方案作废。** 跑通了,剩下的都是工程量,没有未知。

## 里程碑 2:WebView 里出现现有的 UI

先不管好不好看——把上游那份三栏 UI 原样装进 WebView,证明 host↔client 通路是活的。

- [ ] WebView 加载 dsh 的前端 dist
- [ ] 决定走 in-process carrier 还是本地 HTTP(同设备下前者更干净,见 feasibility 第四节)
- [ ] 能建会话、发一条消息、拿到模型回复

## 里程碑 3:换成移动端的 client 名册

这一步才动 UI,而且是**换名册不是改上游**:

- [ ] 一个移动布局插件顶掉 `dsh-client-ui-layout` 的三栏 AppFrame
- [ ] 裁掉手机上无意义的行(directory-picker、plugin-inventory、settings 的桌面部分)
- [ ] 保留 conversation / tool / trajectory / attachment

## 里程碑 4:变成一个真的应用

到这里才轮到"应用"该有的东西,大部分问题 `dsh-desktop` 已经答过一遍,可以照抄思路:

- [ ] 前台服务持有 Node 进程,应用退出时整树收干净
- [ ] 进程守护与退避重启
- [ ] 运行时更新(双槽位)与回退
- [ ] 数据快照与恢复
- [ ] 凭据存储(Android Keystore,不能照搬桌面端)

## 明确不做

- **iOS** —— 见 [feasibility.md 第五节](docs/feasibility.md#五ios排除),不是排期问题
- **连接远程 host** —— 那需要自己写鉴权层,而且违背这个项目的前提
- **复用 dsh-desktop 的代码** —— 借鉴思路,不共享实现
