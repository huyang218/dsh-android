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
| 智谱 GLM | `glm-5.3` | ✗ | `ZHIPUAI_API_KEY` |
| 智谱 GLM | `glm-5v-turbo` | ✓ | 同上 |
| 腾讯云 TokenHub | `kimi-k3` | ✓ | `TOKENHUB_API_KEY` |

**GLM-5.3 收不了图。** 智谱文档写的是"仅支持文本模态",模型总览里它也归在「文本模型」
段而不是「视觉模型」段。它在这里是当主力文本模型用的;附了图再选它,一样会被客户端挡
在门二上——那不是配置错了。看图请选 `GLM-5V-Turbo` 或 `Kimi K3`。

K3 走的是腾讯云 TokenHub,不是 Moonshot 直连——同一个腾讯云账号,和联网搜索是一家。
**但 key 是两回事**:TokenHub 用它控制台里建的 API Key 走 Bearer,不是联网搜索那对 TC3
签名的 SecretId/SecretKey。换成直连只要改 `baseURL` 和 `apiKeyEnv` 两行。

key 填在应用的「密钥」页里。DeepSeek 那条路由不受影响,仍然在。

## 待验证

型号 ID 与长度抄自各家公开文档(2026-08-22 读取),**没有拿真实 key 打过**。验的办法:
填好 key,在模型选择器里选一个带「看图」的,附一张图发出去。

- ID 错 → provider 报 `UNKNOWN_MODEL`,会把 ID 念出来。
- 长度报大了 → 一次超长请求被服务端拒掉,而消息已经落库,会话会反复重试同一个必失败的
  请求。所以文件里写的是文档数字**往下取**,不往上凑。

## 加一条路由

在 `cordis.patch.yml` 的 `providers:` 下再加一块就行,不用碰别的地方。要收图就写
`input: [text, image]`——**这一个数组是图片能不能发出去的唯一开关**。
