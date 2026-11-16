import { defineManifest } from '@crxjs/vite-plugin'
import {readFileSync} from 'node:fs'

const version = readFileSync(new URL('../VERSION', import.meta.url), 'utf8').trim()
const key = readFileSync(new URL('./keys/extension-public-key.base64', import.meta.url), 'utf8').trim()

export default defineManifest({
    manifest_version: 3,
    name: 'Pop Can',
    version,
    key,

    permissions: [
        'storage',
        'unlimitedStorage',
        'tabs',
        'tabGroups',
        'favicon',
        'activeTab',
        'debugger',
        'alarms',
        'scripting',
        // 本地服务改用 native messaging（chrome.runtime.connectNative）：Chrome 只认 manifest 里的 allowed_origins，
        // 装 host 的那次动作写的就是这条 ID。
        'nativeMessaging',
        // browser_search 用浏览器默认搜索引擎搜索（不拼 URL、不关心引擎是谁）
        'search'
    ],

    optional_host_permissions: ['http://*/*', 'https://*/*'],

    action: {
        default_title: 'Pop Can',
        default_icon: {
            16: 'assets/icons/pop-can-minimal-16.png',
            48: 'assets/icons/pop-can-minimal-48.png',
            128: 'assets/icons/pop-can-minimal-128.png'
        }
    },

    background: {
        service_worker: 'entrypoints/background.ts',
        type: 'module',
    },
})
