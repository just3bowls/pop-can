// @ts-ignore
import locationScript from './content/index.ts?script'

// @ts-ignore
globalThis.__popCanLocationScript = locationScript

// 网页原生会话的胶囊 UI：按已授权的 provider origin 运行时注册，只画界面、不碰页面行为。
// @ts-ignore
import webChatScript from './content/webchat.ts?script'

// @ts-ignore
globalThis.__popCanWebChatScript = webChatScript

// 正文抽取用的第三方库源码：从 npm 包取"浏览器可执行的那份文件"（?raw 是文件原文，
// 因此必须指向 UMD/浏览器版，不能用包名——包名会解析到 CJS 入口，注入页面会报 module is not defined），
// 以字符串形式打进 bundle，再由 background 通过 CDP Runtime.evaluate 注入目标页面（运行时不联网、不加 host 权限）。
// @ts-ignore
import readabilitySource from '@mozilla/readability/Readability.js?raw'
// @ts-ignore
import turndownSource from 'turndown/dist/turndown.js?raw'

// @ts-ignore
globalThis.__thExtractSources = { readability: readabilitySource, turndown: turndownSource }

// Provider Pack 验签公钥。规则本身由扩展在运行时从签名 pack 或离线导入安装。
// @ts-ignore
import providerPackPublicKey from '../keys/provider-pack-public-key.spki.base64?raw'

// @ts-ignore
globalThis.__popCanProviderPackPublicKey = providerPackPublicKey.trim()

// @ts-ignore
import { backgroundMain } from 'scalajs:background.js'

backgroundMain()
