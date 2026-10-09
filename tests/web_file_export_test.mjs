import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const script = readFileSync(new URL('../android/app/src/main/assets/web-file-export.js', import.meta.url), 'utf8')

function setup(bytes, nativeError, options = {}) {
  const messages = [], alerts = [], revoked = [], listeners = {}, clicks = []
  let releaseSave
  let releasePicker
  const saved = new Promise(resolve => { releaseSave = resolve })
  const picker = new Promise(resolve => { releasePicker = resolve })
  const port = {
    close() {},
    postMessage(raw) {
      const message = JSON.parse(raw)
      messages.push(message)
      if (message.action === 'abort') return
      if (options.holdPicker && message.action === 'finish') { releasePicker(); return }
      queueMicrotask(() => {
        if (options.staleReplies) port.onmessage({ data: JSON.stringify({ id: message.id - 1, error: '旧请求失败' }) })
        port.onmessage({ data: JSON.stringify(nativeError ? { id: message.id, error: nativeError } : { id: message.id }) })
        if (message.action === 'finish' || nativeError) releaseSave()
      })
    }
  }
  class Anchor {
    constructor(href, download) { this.href = href; this.download = download }
    hasAttribute(name) { return name === 'download' && this.download != null }
    click() { clicks.push(this.href) }
    dispatchEvent() { clicks.push(this.href); return true }
  }
  class BrowserURL extends URL {}
  BrowserURL.revokeObjectURL = url => revoked.push(url)
  const window = { addEventListener(name, callback) { listeners[name] = callback } }
  const context = vm.createContext({
    window, document: { addEventListener() {} }, URL: BrowserURL, HTMLAnchorElement: Anchor,
    location: { href: 'http://127.0.0.1:58000/plan', origin: 'http://127.0.0.1:58000' },
    alert(message) { alerts.push(message) }, AbortController, setTimeout, clearTimeout, Uint8Array,
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
    async fetch(url) {
      await Promise.resolve()
      assert.ok(!revoked.includes(url), 'Blob must remain readable until captured')
      return { ok: true, blob: async () => new Blob([bytes], { type: 'application/json' }) }
    }
  })
  vm.runInContext(script + '\ninstallMowerFileExport("test-marker")', context)
  listeners.message({ data: 'test-marker', ports: [port] })
  return { context, messages, alerts, revoked, clicks, saved, picker, window, Anchor, listeners }
}

test('programmatic Blob downloads survive immediate revocation and transfer exact bytes', async () => {
  const bytes = Buffer.alloc(100001)
  for (let index = 0; index < bytes.length; index++) bytes[index] = index % 256
  const env = setup(bytes)
  new env.Anchor('blob:http://127.0.0.1:58000/plan', 'plan.json').click()
  vm.runInContext('URL.revokeObjectURL("blob:http://127.0.0.1:58000/plan")', env.context)
  await env.saved
  assert.equal(env.messages[0].name, 'plan.json')
  assert.equal(env.messages[0].size, bytes.length)
  const chunks = env.messages.filter(message => message.action === 'append')
  assert.ok(chunks.every(message => message.data.length <= 65536))
  assert.deepEqual(Buffer.concat(chunks.map(message => Buffer.from(message.data, 'base64'))), bytes)
  assert.deepEqual(env.alerts, [])
  assert.equal(env.revoked.length, 1)
  assert.deepEqual(env.clicks, [])
})

test('data URL images and same-origin HTTP downloads use the same native save flow', async () => {
  for (const [url, name, mime] of [['data:image/png;base64,AQID', '仓库.png', 'image/png'], ['http://127.0.0.1:58000/config-backup/export', 'config.zip', 'application/zip']]) {
    const env = setup(Buffer.from([1, 2, 3]))
    await env.window.__mowerFileExport.save(url, name, mime)
    assert.equal(env.messages[0].name, name)
    assert.equal(env.messages[0].mime, mime)
    assert.equal(env.messages.at(-1).action, 'finish')
  }
})

test('detached chart download anchors also open native export', async () => {
  const env = setup(Buffer.from([1, 2, 3]))
  const result = new env.Anchor('data:image/png;base64,AQID', 'report.png').dispatchEvent({ type: 'click' })
  await env.saved
  assert.equal(result, false)
  assert.equal(env.messages[0].name, 'report.png')
  assert.deepEqual(env.clicks, [])
})

test('native failure releases export state so the user can retry', async () => {
  const env = setup(Buffer.from([1]), '无法打开所选保存位置')
  await env.window.__mowerFileExport.save('blob:http://127.0.0.1:58000/one', 'plan.json', '')
  await env.window.__mowerFileExport.save('blob:http://127.0.0.1:58000/two', 'config.zip', '')
  assert.equal(env.messages.filter(message => message.action === 'begin').length, 2)
  assert.equal(env.alerts.length, 2)
  assert.ok(env.alerts[0].includes('无法打开所选保存位置'))
})

test('external downloads are rejected while ordinary links keep their browser behavior', async () => {
  const env = setup(Buffer.from([1]))
  await env.window.__mowerFileExport.save('https://example.invalid/file', 'config.zip', '')
  assert.deepEqual(env.messages, [])
  assert.equal(env.alerts.length, 1)
  new env.Anchor('http://127.0.0.1:58000/plan', null).click()
  assert.equal(env.clicks.length, 1)
})

test('delayed replies for earlier chunks do not interrupt the current export', async () => {
  const env = setup(Buffer.alloc(100001), null, { staleReplies: true })
  await env.window.__mowerFileExport.save('blob:http://127.0.0.1:58000/plan', 'plan.json', '')
  assert.equal(env.messages.at(-1).action, 'finish')
  assert.deepEqual(env.alerts, [])
})

test('page reconnection cancels an outstanding picker wait and permits another export', async () => {
  const env = setup(Buffer.from([1]), null, { holdPicker: true })
  const first = env.window.__mowerFileExport.save('blob:http://127.0.0.1:58000/plan', 'plan.json', '')
  await env.picker
  env.window.__mowerFileExport.connect('reloaded')
  const newMessages = []
  const nextPort = {
    close() {},
    postMessage(raw) {
      const message = JSON.parse(raw)
      newMessages.push(message)
      queueMicrotask(() => nextPort.onmessage({ data: JSON.stringify({ id: message.id }) }))
    }
  }
  env.listeners.message({ data: 'reloaded', ports: [nextPort] })
  await first
  await env.window.__mowerFileExport.save('data:application/json,[]', 'next.json', '')
  assert.equal(newMessages[0].action, 'begin')
  assert.equal(newMessages.at(-1).action, 'finish')
  assert.equal(env.alerts.length, 1)
  assert.ok(env.alerts[0].includes('页面已重载'))
})
