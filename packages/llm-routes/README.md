# dsh-plugin-llm-routes

这个 apk 提供哪些模型路由。**没有代码**——只有一份 `cordis.patch.yml`。

## 为什么不是一个适配器

手机端"发图"这条链上有两道门,它们在不同的地方:

| 门 | 在哪 | 表现 |
|---|---|---|
| 客户端能不能选到图 | `ui-conversation` 只认 `paste` 和 document 上的 `drop` | 没有入口就没法选 |
| 模型收不收图 | `inputModalities` | "The current model does not support images" |

第一道门由 [`packages/mobile-layout`](../mobile-layout/) 的附件按钮 + 应用侧的
`onShowFileChooser` 解决。第二道门解决不了的原因很具体:**`dsh-llm-deepseek` 把
`inputModalities` 写死成 `["text"]`**(`lib/index.js:447`、`:519`),所以任何 DeepSeek
路由都不可能收图。

而 `dsh-llm-pi-ai` 的模态是从配置里读的(`inputModalities: [...model.input]`),并且
**dsh-base 已经把它挂上了**,只是零路由空转——用上游自己的话说,"mounted dormant: zero
routes ... until a `llm-pi-ai:` settings section supplies provider profiles"。

所以缺的不是适配器,是路由。这个包就是那些路由,跟着 apk 走,而不是让人在手机上手写
`settings.yaml`。

## 现在有哪几条

| 路由 | 型号 | 收图 | key |
|---|---|---|---|
| 腾讯云 TokenHub | `deepseek/deepseek-v4-flash-vision-exp` | ✓ 实测 | `TOKENHUB_API_KEY` |
| 腾讯云 TokenHub | `kimi-k3` | ✓ 实测 | 同上 |

一个 key,一条路由。上游的 DeepSeek 官方路由不受影响,仍然在。

两个都验过:同一张 64×64 的应用图标,问"几个字母、什么底色",都答对了 D、H 和黑色底,
连 H 横梁上那个蓝点都说了出来。首选 DS-V4-Flash-Vision——TTFT 1.2s,而 K3 是 38s。

## 收图有一个前提,不在这个包里

**必须关掉运行时上下文快照**,开关在
[`composition/profiles/handheld/cordis.patch.yml`](../../composition/profiles/handheld/cordis.patch.yml)。

dsh 每轮会在用户那条消息**后面**再追加一条 `user` 角色的运行时上下文,于是消息序列是
`system → user(图+文) → user(上下文) → assistant`——**两条连续的 user**。而 TokenHub
的《OpenAI Chat Completions 协议字段说明》把顺序写死成
`[system(可选) → user → assistant → user → ...]`,它对不合规的序列做归一化合并,
合并时把 `image_url` 那个 content part 丢掉了。

症状是 HTTP 200、流式正常、token 计数看着也像那么回事,只有模型在正文里说"我没有收到
任何图片附件"。**我一度据此判定"TokenHub 不转内联 base64",那是错的**——同一份文档
明写着 `image_url.url` 接受 `data:image/...;base64,...`。关掉那条追加消息后,同一张图
同一个问题,模型立刻答对。

代价和补偿写在那份 patch 的注释里。

## 没验的部分

上下文与输出长度抄自 TokenHub 的模型列表,没有单独压测过边界。长度报大了的表现是一次
超长请求被服务端拒掉,而消息那时已经落库,会话会反复重试同一个必失败的请求——所以文件
里写的是文档数字**往下取**,不往上凑。

另外这几个型号当初是在**错误前提**下判"不收图"的,后来没有重测,现在的状态是未知而不是
否定:`hy-vision-2.0-instruct`、`glm-5v-turbo`(那次是 402,控制台未开通)、
`hunyuan-turbos-vision-video-20250728`(402;另外腾讯的能力表写着它「图片理解:不支持」,
这条与前提无关,仍然成立)。

## 加一条路由

在 `cordis.patch.yml` 的 `providers:` 下再加一块就行,不用碰别的地方。要收图就写
`input: [text, image]`——**这一个数组是客户端那道门的唯一开关**,但它只管客户端放不放行;
那头收不收得到,得实测。
