function installMowerFileExport(marker) {
  if (window.__mowerFileExport) {
    window.__mowerFileExport.connect(marker)
    return
  }
  let port, expectedMarker, reply, busy = false, requestId = 0
  const retained = new Set(), revoked = new Set()
  const originalRevoke = URL.revokeObjectURL.bind(URL)
  URL.revokeObjectURL = function (url) {
    if (retained.has(url)) revoked.add(url)
    else originalRevoke(url)
  }
  function connect(value) {
    expectedMarker = value
    if (reply) reply.reject(new Error('页面已重载，请重新导出'))
    reply = null
    if (port) port.close()
    port = null
  }
  window.addEventListener('message', event => {
    if (event.data !== expectedMarker || !event.ports[0]) return
    if (port) port.close()
    port = event.ports[0]
    port.onmessage = event => {
      const result = JSON.parse(event.data)
      if (!reply || reply.id !== result.id) return
      const callback = reply
      reply = null
      callback.resolve(result)
    }
  })
  function send(exportPort, message) {
    return new Promise((resolve, reject) => {
      const id = ++requestId
      const timeout = message.action === 'finish' ? null : setTimeout(() => {
        if (reply && reply.id === id) reply = null
        reject(new Error('导出传输超时，请重试'))
      }, 30000)
      reply = {
        id,
        reject(error) { clearTimeout(timeout); reject(error) },
        resolve(result) {
          clearTimeout(timeout)
          result.error ? reject(new Error(result.error)) : resolve(result)
        }
      }
      try { exportPort.postMessage(JSON.stringify({ ...message, id })) }
      catch (error) { reply = null; clearTimeout(timeout); reject(error) }
    })
  }
  async function save(url, name, mime) {
    if (busy) { alert('请先完成或取消当前文件导出'); return }
    if (!port) { alert('导出连接尚未就绪，请重载 WebUI 后重试'); return }
    const parsed = new URL(url, location.href)
    if (!['blob:', 'data:'].includes(parsed.protocol) && parsed.origin !== location.origin) {
      alert('仅支持导出当前 Mower 页面生成的文件'); return
    }
    busy = true
    const exportPort = port
    retained.add(url)
    const controller = new AbortController()
    const fetchTimeout = setTimeout(() => controller.abort(), 60000)
    try {
      const response = await fetch(url, { credentials: 'same-origin', signal: controller.signal })
      if (!response.ok) throw new Error(`读取导出文件失败：HTTP ${response.status}`)
      const blob = await response.blob()
      if (port !== exportPort) throw new Error('页面已重载，请重新导出')
      clearTimeout(fetchTimeout)
      retained.delete(url)
      if (revoked.delete(url)) originalRevoke(url)
      await send(exportPort, { action: 'begin', name: name || 'mower-export', mime: (mime || blob.type || 'application/octet-stream').split(';')[0], size: blob.size })
      for (let start = 0; start < blob.size; start += 48 * 1024) {
        const bytes = new Uint8Array(await blob.slice(start, start + 48 * 1024).arrayBuffer())
        await send(exportPort, { action: 'append', data: btoa(String.fromCharCode(...bytes)) })
      }
      await send(exportPort, { action: 'finish' })
    } catch (error) {
      if (port === exportPort) {
        try { exportPort.postMessage(JSON.stringify({ action: 'abort' })) } catch (_) {}
      }
      alert(`导出失败：${error.message || error}`)
    } finally {
      clearTimeout(fetchTimeout)
      retained.delete(url)
      if (revoked.delete(url)) originalRevoke(url)
      busy = false
    }
  }
  function download(anchor) {
    if (!anchor.hasAttribute('download')) return false
    void save(anchor.href, anchor.download, '')
    return true
  }
  const originalClick = HTMLAnchorElement.prototype.click
  HTMLAnchorElement.prototype.click = function () {
    if (!download(this)) originalClick.call(this)
  }
  const originalDispatch = HTMLAnchorElement.prototype.dispatchEvent
  HTMLAnchorElement.prototype.dispatchEvent = function (event) {
    if (event.type === 'click' && download(this)) return false
    return originalDispatch.call(this, event)
  }
  document.addEventListener('click', event => {
    const anchor = event.target.closest && event.target.closest('a[download]')
    if (anchor && download(anchor)) { event.preventDefault(); event.stopImmediatePropagation() }
  }, true)
  window.__mowerFileExport = { connect, save }
  connect(marker)
}
