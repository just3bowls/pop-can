import {defineConfig} from 'vite'
import {crx} from '@crxjs/vite-plugin'
import manifest from './manifest.config.js'
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import {presetBuildWatchPlugin} from './scripts/vite-plugin-preset-build.mjs'
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname_resolved = path.dirname(fileURLToPath(import.meta.url));

// 检查是否为构建模式
const isBuild = process.argv.includes('build');
const extensionOutDir = path.resolve(
    __dirname_resolved,
    isBuild ? 'dist' : 'dev-extension',
);

// 自定义 Scala.js 解析器（用于 build 模式，解决 scalaJSPlugin 与 Vite 7 不兼容问题）
// 在 build 之前需要手动运行: sbt "extension / fullLinkJS"
const scalaJSBuildResolver = () => ({
    name: 'scalajs-build-resolver',
    resolveId(source) {
        if (source.startsWith('scalajs:')) {
            const moduleName = source.slice('scalajs:'.length);
            // 指向 fullLinkJS 的输出目录
            return path.resolve(__dirname_resolved, 'target/scala-3.7.1/client-extension-opt', moduleName);
        }
        return null;
    }
});

export default defineConfig({
    plugins: [
        ...(isBuild ? [] : [presetBuildWatchPlugin()]),
        // 修复：Source Map 路径重写中间件
        {
            name: 'sourcemap-path-fix',
            configureServer(server) {
                server.middlewares.use((req, res, next) => {
                    if (!req.url) return next();

                    // 如果请求 URL: http://localhost:3000/client-extension/src/main/scala/...
                    // 但是vite运行在client-extension子模块上，所以请求时会在当前目录中找client-extension
                    // 也就是文件夹变成了 client-extension/client-extension/src，所以这里需要替换一下
                    if (req.url.startsWith('/client-extension/')) {
                        // 去掉 /client-extension 前缀，Vite 会在当前项目根目录下寻找剩余路径
                        req.url = req.url.replace(/^\/client-extension\//, '/');
                    }
                    next();
                });
            },
        },

        crx({manifest}),


        // Scala.js 插件配置：
        // - 开发模式 (npm run dev): 使用 scalaJSPlugin 自动调用 sbt fastLinkJS
        // - 构建模式 (npm run build): 使用自定义解析器指向预编译的 fullLinkJS 输出
        //   （需要先运行: sbt "extension / fullLinkJS"）
        ...(isBuild ? [scalaJSBuildResolver()] : [scalaJSPlugin({
            cwd: '..', // sbt在根目录
            projectID: 'extension',
            uriPrefix: 'scalajs'
        })]),


    ],


    // 这里的 server 配置主要影响 vite 作为一个 web server 时的行为
    server: {
        // CRX 开发模式下，资源是通过 script 标签注入的，inline 更安全
        sourcemap: 'inline',
        port: 3000,
        strictPort: true,
        hmr: {
            clientPort: 3000,
            overlay: false,
        },
        cors: {
            origin: [
                /chrome-extension:\/\//,
            ],
        },
    },


    resolve: {
        extensions: ['.mjs', '.js', '.mts', '.ts', '.jsx', '.tsx', '.json'],
    },

    build: {
        outDir: extensionOutDir,
        emptyOutDir: true,
        // 生产环境可以保持 true (生成独立文件) 否者 'inline'
        sourcemap: process.env.NODE_ENV === 'production' ? true : 'inline',
        minify: false, // 调试时建议关闭压缩，减少干扰
        modulePreload: false,
        rollupOptions: {
            input: {
                main: 'entrypoints/main/index.html',
            },
        },
    },
})
