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
| 腾讯云 TokenHub | `deepseek/deepseek-v4-flash-vision-exp` | ⚠ 存疑 | `TOKENHUB_API_KEY` |
| 腾讯云 TokenHub | `kimi-k3` | ✗(已实测) | 同上 |

一个 key,一条路由。上游的 DeepSeek 官方路由不受影响,仍然在。

**这条路由上没有一个型号确定能收图,而这是实测出来的。** 2026-08-23 在模拟器上拿真实
key 逐个试过:

| 型号 | 结果 |
|---|---|
| `kimi-k3` | 200、流式正常,模型答"我没有收到任何图片附件",Input 2.1K token |
| `hy-vision-2.0-instruct` | 同上,Input 772 token |
| `deepseek/deepseek-v4-flash-vision-exp` | 同上,Input 2.5K token(**仍保留 image 声明**,见下) |
| `glm-5v-turbo` | `402 endpoint is inactive`(账号未开通) |
| `hunyuan-turbos-vision-video-20250728`(HY-Vision-Video) | `402`,且腾讯能力表写着它「图片理解:不支持」 |

而我们发出去的东西是合规的——把这条路由指到设备本地的桩服务器截下真实请求体,
`image_url.url` 是 `data:image/png;base64,…`(20902 字符),`model` 和
`Authorization: Bearer` 全对。

差别在形式:TokenHub 的《多模态理解》接口把 `image_url.url` 定义成**图片 URL 链接**
(还规定封装格式和 10MB 上限),而 pi-ai 只会内联 base64、从不发外链
(`pi-ai/dist/api/openai-completions.js:821`)。那份文档还把 `role` 限定为 `user` 一种,
也就是说它压根不是通用对话接口,装不下 dsh 的 system + tools + 历史。

**要接一个原生吃 data URI 的直连厂商接口,各是一块配置的事:**

| 想用 | baseURL | apiKeyEnv | 型号 |
|---|---|---|---|
| 混元直连 | `https://api.hunyuan.cloud.tencent.com/v1` | `HUNYUAN_API_KEY` | `hunyuan-vision-1.5-instruct` |
| Moonshot 直连 | `https://api.moonshot.cn/v1` | `MOONSHOT_API_KEY` | `kimi-k3` |
| 智谱直连 | `https://open.bigmodel.cn/api/paas/v4` | `ZHIPUAI_API_KEY` | `glm-5v-turbo` |

## 待验证

型号 ID 与长度抄自各家公开文档(2026-08-22 读取),**没有拿真实 key 打过**。验的办法:
填好 key,在模型选择器里选一个带「看图」的,附一张图发出去。

- ID 错 → provider 报 `UNKNOWN_MODEL`,会把 ID 念出来。
- 长度报大了 → 一次超长请求被服务端拒掉,而消息已经落库,会话会反复重试同一个必失败的
  请求。所以文件里写的是文档数字**往下取**,不往上凑。

## 加一条路由

在 `cordis.patch.yml` 的 `providers:` 下再加一块就行,不用碰别的地方。要收图就写
`input: [text, image]`——**这一个数组是图片能不能发出去的唯一开关**。

## 那条存疑的路由

`deepseek/deepseek-v4-flash-vision-exp` 是 TokenHub 上唯一一个既在「语言模型」表里
(带 Function Calling,装得下 dsh 的 system + tools + 历史)、又标着 Vision 的型号,
所以它是这个网关上最有希望的一条,声明先留着。

但实测它没看见图。要分清是网关不转内联图、还是 dsh 的请求形状(system + tools + 历史)
让网关走了别的路,得绕开 agent 单独打一次:

```bash
scratchpad/probe-tokenhub.sh deepseek/deepseek-v4-flash-vision-exp
```

一张 64×64 纯红图问它什么颜色。**说红色就是能收,说看不见就是不能,没有中间答案。**
若证实不能收,把 `input` 改成 `[text]` —— 声明了却收不到,客户端会放行,人会以为是应用
坏了。
