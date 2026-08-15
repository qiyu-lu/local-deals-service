import { ref } from 'vue'
import { createAdminWsTicket, resultData } from '../api'
import { getAdminToken } from '../auth/adminSession'

const connected = ref(false)
const connecting = ref(false)
let ws = null
let sessionToken = null
let connectionAttempt = 0
const listeners = []

export function useAdminWs() {
  async function connect() {
    const token = getAdminToken()
    if (!token) return false
    if ((connected.value || connecting.value) && sessionToken === token) return true
    if (ws) disconnect()

    const attempt = ++connectionAttempt
    connecting.value = true
    sessionToken = token

    try {
      const ticketResult = await createAdminWsTicket()
      const ticketPayload = resultData(ticketResult)
      const ticket = typeof ticketPayload === 'string' ? ticketPayload : ticketPayload?.ticket
      if (!ticket) throw new Error('未获取到 WebSocket 一次性票据')
      if (attempt !== connectionAttempt || token !== getAdminToken()) return false

      const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
      const host = window.location.host
      const url = `${protocol}://${host}/api/ws/admin/connect?ticket=${encodeURIComponent(ticket)}`
      const socket = new WebSocket(url)
      ws = socket

      socket.onopen = () => {
        if (ws !== socket || attempt !== connectionAttempt) {
          socket.close()
          return
        }
        connecting.value = false
        connected.value = true
      }
      socket.onmessage = event => {
        if (ws === socket) listeners.forEach(listener => listener(event))
      }
      socket.onerror = () => {
        if (ws === socket) connecting.value = false
      }
      socket.onclose = () => {
        if (ws !== socket) return
        connecting.value = false
        connected.value = false
        ws = null
        sessionToken = null
      }
      return true
    } catch {
      if (attempt === connectionAttempt) {
        connecting.value = false
        sessionToken = null
      }
      return false
    }
  }

  function disconnect() {
    connectionAttempt += 1
    const socket = ws
    ws = null
    sessionToken = null
    connected.value = false
    connecting.value = false
    if (socket) socket.close()
  }

  function onMessage(listener) {
    listeners.push(listener)
    return () => {
      const index = listeners.indexOf(listener)
      if (index !== -1) listeners.splice(index, 1)
    }
  }

  return { connected, connecting, connect, disconnect, onMessage }
}
