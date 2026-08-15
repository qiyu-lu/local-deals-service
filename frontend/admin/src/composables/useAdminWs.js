import { ref } from 'vue'

const connected = ref(false)
const connecting = ref(false)
let ws = null
let wsToken = null
const _listeners = []

export function useAdminWs() {
  function connect() {
    const token = localStorage.getItem('token')
    if (!token) return
    if ((connected.value || connecting.value) && wsToken === token) return
    if (ws) disconnect()
    connecting.value = true
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const host = window.location.host
    const url = `${protocol}://${host}/api/ws/admin/connect?token=${encodeURIComponent(token)}`
    let socket
    try {
      socket = new WebSocket(url)
      ws = socket
      wsToken = token
    } catch {
      connecting.value = false
      return
    }
    socket.onopen = () => {
      if (ws !== socket) {
        socket.close()
        return
      }
      connecting.value = false
      connected.value = true
    }
    socket.onmessage = (e) => {
      if (ws === socket) _listeners.forEach(fn => fn(e))
    }
    socket.onerror = () => {
      if (ws === socket) connecting.value = false
    }
    socket.onclose = () => {
      if (ws !== socket) return
      connecting.value = false
      connected.value = false
      ws = null
      wsToken = null
    }
  }

  function disconnect() {
    const socket = ws
    ws = null
    wsToken = null
    connected.value = false
    connecting.value = false
    if (socket) socket.close()
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
