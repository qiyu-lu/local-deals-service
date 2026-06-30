import { ref } from 'vue'

const connected = ref(false)
const connecting = ref(false)
let ws = null
const _listeners = []

export function useAdminWs() {
  function connect() {
    if (connected.value || connecting.value) return
    const token = localStorage.getItem('token')
    if (!token) return
    connecting.value = true
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const host = window.location.host
    const url = `${protocol}://${host}/api/ws/connect?token=${encodeURIComponent(token)}`
    try {
      ws = new WebSocket(url)
    } catch {
      connecting.value = false
      return
    }
    ws.onopen = () => { connecting.value = false; connected.value = true }
    ws.onmessage = (e) => { _listeners.forEach(fn => fn(e)) }
    ws.onerror = () => { connecting.value = false }
    ws.onclose = () => { connecting.value = false; connected.value = false; ws = null }
  }

  function disconnect() {
    if (ws) { ws.close(); ws = null }
    connected.value = false
    connecting.value = false
  }

  function onMessage(fn) {
    _listeners.push(fn)
    return () => {
      const i = _listeners.indexOf(fn)
      if (i !== -1) _listeners.splice(i, 1)
    }
  }

  return { connected, connecting, connect, disconnect, onMessage }
}
