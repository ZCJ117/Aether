import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/styles/base.css'
import './assets/styles/animations.css'
import './assets/styles/transitions.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
