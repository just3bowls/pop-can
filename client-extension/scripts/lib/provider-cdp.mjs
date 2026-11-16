export const cdpBase = process.env.PC_CDP || process.env.TH_CDP || 'http://127.0.0.1:9222'

export const listJson = async path => {
  const res = await fetch(`${cdpBase}${path}`)
  if (!res.ok) throw new Error(`CDP ${path} -> HTTP ${res.status}`)
  return res.json()
}

export const withTimeout = (promise, ms, label) =>
  Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms))
  ])

export const connect = async wsUrl => {
  const ws = new WebSocket(wsUrl)
  let id = 0
  const pending = new Map()
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const msgId = ++id
      pending.set(msgId, { resolve, reject })
      ws.send(JSON.stringify({ id: msgId, method, params }))
    })
  ws.addEventListener('message', event => {
    const msg = JSON.parse(event.data)
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id).resolve(msg)
      pending.delete(msg.id)
    }
  })
  await withTimeout(
    new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve)
      ws.addEventListener('error', reject)
    }),
    10000,
    'websocket connect'
  )
  return { ws, send }
}

export const evalInTarget = async (target, expression) => {
  const client = await connect(target.webSocketDebuggerUrl)
  const res = await withTimeout(
    client.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }),
    20000,
    'Runtime.evaluate'
  )
  client.ws.close()
  if (res.result?.exceptionDetails) throw new Error(JSON.stringify(res.result.exceptionDetails).slice(0, 400))
  return res.result?.result?.value
}

export const findPluginPage = async () => {
  const pages = await listJson('/json/list')
  return pages.filter(t => t.type === 'page' && t.url.includes('entrypoints/main'))[0] || null
}

export const pageIsUsable = async page => {
  try {
    const probe = await evalInTarget(
      page,
      "location.href.startsWith('chrome-extension://') && typeof chrome.runtime.getURL === 'function'"
    )
    return probe === true
  } catch (error) {
    return false
  }
}

export const reloadPage = async page => {
  const client = await connect(page.webSocketDebuggerUrl)
  await client.send('Page.enable')
  await client.send('Page.reload', { ignoreCache: true })
  client.ws.close()
  for (let i = 0; i < 60; i++) {
    await new Promise(r => setTimeout(r, 250))
    if (await pageIsUsable(page)) return true
  }
  return false
}

export const openPluginPage = async () => {
  const targets = await listJson('/json/list')
  const workers = targets.filter(t => t.type === 'service_worker')
  if (workers.length === 0) throw new Error('extension service worker not found — is the extension loaded?')
  let opened = false
  for (const worker of workers) {
    try {
      const who = await withTimeout(
        evalInTarget(
          worker,
          "chrome.windows.create({url: chrome.runtime.getURL('entrypoints/main/index.html'), type:'popup', width:1100, height:900}).then(() => chrome.runtime.id)"
        ),
        10000,
        'create plugin window'
      )
      if (who) {
        opened = true
        break
      }
    } catch (error) {
      continue
    }
  }
  if (!opened) throw new Error('no live service worker could open the plugin page')
  for (let i = 0; i < 60; i++) {
    await new Promise(r => setTimeout(r, 250))
    const page = await findPluginPage()
    if (page) return page
  }
  throw new Error('plugin page did not appear')
}

export const usablePluginPage = async () => {
  const found = await findPluginPage()
  if (found && (await reloadPage(found)) && (await pageIsUsable(found))) return found
  return openPluginPage()
}
