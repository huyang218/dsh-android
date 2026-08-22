# dsh-plugin-llm-vision-hunyuan

一条能接图的模型路由。**这个包没有代码**——只有一份 `cordis.patch.yml`。

## 为什么不是一个适配器

手机端"发图"这条链上有两道门,它们在不同的地方:

| 门 | 在哪 | 表现 |
|---|---|---|
| 客户端能不能选到图 | `ui-conversation` 只认 `paste` 和 document 上的 `drop` | 没有入口就没法选 |
| 模型收不收图 | `inputModalities` | "The current model does not support images" |

第一道门由 `packages/mobile-layout` 的附件按钮 + 应用侧的 `onShowFileChooser` 解决。
第二道门解决不了的原因很具体:**`dsh-llm-deepseek` 把 `inputModalities` 写死成
`["text"]`**(`lib/index.js:447`、`:519`),所以任何 DeepSeek 路由都不可能收图。

而 `dsh-llm-pi-ai` 的模态是从配置里读的(`inputModalities: [...model.input]`),并且
**dsh-base 已经把它挂上了**,只是空转——用上游自己的话说,"mounted dormant: zero
routes ... until a `llm-pi-ai:` settings section supplies provider profiles"。

所以缺的不是适配器,是一条路由。这个包就是那条路由,跟着 apk 走,而不是让人在手机上
手写 `settings.yaml`。

## 需要什么

一个**混元 API Key**(控制台 → 混元 → API KEY),填在应用的「密钥」页里,变量名
`HUNYUAN_API_KEY`。

**不是** `packages/web-search-tencent` 用的那对 `TENCENTCLOUD_SECRET_ID` /
`TENCENTCLOUD_SECRET_KEY`。同一家两个产品两套凭证:联网搜索是云 API,每次请求用
TC3-HMAC-SHA256 签名;这里是 OpenAI 兼容端点,`Authorization: Bearer <key>`。拿
SecretId 填这里会得到一个看不出所以然的 401。

## 待验证

`cordis.patch.yml` 里的模型 ID 与长度来自腾讯公开的模型列表
(<https://cloud.tencent.com/document/product/1729/104753>,2026-08-22 读取),
**没有拿真实 key 验过**。验的办法:填好 key,在模型选择器里选「混元视觉 1.5」,附一张图
发出去。

- ID 错 → provider 报 `UNKNOWN_MODEL`,会把 ID 念出来。
- 长度报大了 → 一次超长请求被服务端拒掉,消息已经落库,会话反复重试同一个必失败的请求。
  所以文件里写的是文档数字**往下取整**,不是往上凑 2 的幂。
