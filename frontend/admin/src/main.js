import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './styles/global.css'
import { useAdminWs } from './composables/useAdminWs'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(ElementPlus)
app.use(router)

window.addEventListener('admin:unauthorized', () => {
  useAdminWs().disconnect()
  if (router.currentRoute.value.path !== '/login') router.replace('/login')
})

app.mount('#app')
