import { definePlugin } from '@halo-dev/console-shared'
import LogView from './views/LogView.vue'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [],
  extensionPoints: {
    'plugin:self:tabs:create': () => {
      return [
        {
          id: 'logs',
          label: '刷新日志',
          component: markRaw(LogView),
          priority: 10,
        },
      ]
    },
  },
})
