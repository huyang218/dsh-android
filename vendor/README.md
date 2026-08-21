# vendor/

构建输入,不是构建产物。这里的东西**进仓库**,因为没有它就没人能重打出同一个 apk。

## `node.tar`

Android aarch64 的 Node 运行时,展开后是 `node/bin/node` 加 `node/lib/*.so`(35 个
条目,其中 18 个是符号链接)。[`scripts/prepare-runtime.sh`](../scripts/prepare-runtime.sh)
把它原样copy 进 `assets/runtime/node.tar`,首启时由应用解到私有目录。

**它是探针,不是发行方案。** 这是 Termux 的 aarch64 构建,从一台手工铺过运行时的
设备上 `tar` 下来的。能直接用是因为 bionic 的链接器先查 `LD_LIBRARY_PATH`、再查
`DT_RUNPATH`,所以二进制里写死的 termux 前缀被绕开了——证据见
[feasibility 第三节](../docs/feasibility.md)。**按 termux-packages 的配方用自己的前缀
重编仍然要做**([PLAN 线 A](../PLAN.md)),理由有两条:路径前缀在 npm / node-gyp
一类地方还会露头;以及把别人编的二进制放进公开分发的 apk,是许可与分发责任问题。

里面的库和它们的许可:Node 本体 MIT,OpenSSL(`libssl` / `libcrypto`)Apache-2.0,
ICU 是 ICU 许可,SQLite 公有领域,zlib 是 zlib 许可,`libc++_shared` 属 LLVM 例外条款。
**全部宽松,没有 GPL/LGPL**,可以随 apk 分发——但 Apache-2.0 和 ICU 要求随附许可
文本,而 apk 现在一份都没带。公开发行前要补。

## 为什么不用 Git LFS

一个 90 MB 的 tar 在 git 的包里约 33 MB,不触发 GitHub 的 100 MB 硬限制。LFS 的免费
额度是 1 GB 存储 / 1 GB 月流量,每次重打 Node 都会吃掉一大块,而这个文件半年也未必
换一次。**重编 Node 之后如果开始频繁更新它**,再谈 LFS 或者把它挂成 Release 资产由
脚本下载;在那之前,多这一次 33 MB 的历史比多一套取包流程便宜。
