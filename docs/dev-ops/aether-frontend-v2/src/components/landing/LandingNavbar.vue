<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Menu, X, Github } from 'lucide-vue-next'
import AetherLogo from './AetherLogo.vue'
import LandingButton from './LandingButton.vue'

const NAV_ITEMS = [
  { label: '亮点',     href: '#highlights',   type: 'anchor' as const },
  { label: '架构',     href: '#architecture',  type: 'anchor' as const },
  { label: '产品预览', href: '#preview',       type: 'anchor' as const },
  { label: '路线图',   href: '#roadmap',       type: 'anchor' as const },
  { label: '文档',     href: '/#/docs',          type: 'external' as const },
]

const router = useRouter()
const route = useRoute()
const mobileOpen = ref(false)
const scrolled = ref(false)
const activeAnchor = ref('')

const emit = defineEmits<{
  cta: []
}>()

function handleNavClick(item: typeof NAV_ITEMS[number], e: Event) {
  mobileOpen.value = false

  if (item.type === 'anchor') {
    e.preventDefault()
    const el = document.querySelector(item.href)
    if (el) el.scrollIntoView({ behavior: 'smooth' })
    return
  }

  if (item.type === 'route') {
    e.preventDefault()
    router.push(item.href)
    return
  }

  if (item.type === 'external') {
    e.preventDefault()
    window.open(item.href, '_blank')
    return
  }
}

function isActive(item: typeof NAV_ITEMS[number]): boolean {
  if (item.type === 'route') return route.path === item.href
  if (item.type === 'anchor') return activeAnchor.value === item.href
  return false
}

function onScroll() {
  scrolled.value = window.scrollY > 0

  const anchors = NAV_ITEMS.filter(i => i.type === 'anchor').map(i => i.href)
  for (let i = anchors.length - 1; i >= 0; i--) {
    const el = document.querySelector(anchors[i])
    if (el) {
      const rect = el.getBoundingClientRect()
      if (rect.top <= 120) {
        activeAnchor.value = anchors[i]
        return
      }
    }
  }
  activeAnchor.value = ''
}

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => window.removeEventListener('scroll', onScroll))
</script>

<template>
  <header
    v-motion
    :initial="{ opacity: 0, y: -10 }"
    :enter="{ opacity: 1, y: 0, transition: { duration: 600, ease: 'easeOut' } }"
    class="fixed top-0 left-0 right-0 z-50 transition-all duration-300"
    :class="{ 'bg-black/70 backdrop-blur-xl border-b border-white/10': scrolled }"
  >
    <div class="nav-inner flex items-center justify-between px-6 md:px-10 h-16">
      <!-- Logo -->
      <a href="#" aria-label="Aether home" class="flex items-center gap-3 group">
        <AetherLogo className="w-8 h-8 text-white" />
        <span class="font-black text-lg tracking-tight text-white">Aether</span>
      </a>

      <!-- Desktop Nav -->
      <nav class="hidden md:flex items-center gap-1">
        <a
          v-for="item in NAV_ITEMS"
          :key="item.label"
          :href="item.href"
          @click="handleNavClick(item, $event)"
          class="nav-link text-sm font-medium px-3.5 py-2 rounded-lg transition-all duration-200"
          :class="isActive(item)
            ? 'text-white bg-white/10'
            : 'text-white/60 hover:text-white hover:bg-white/5'"
        >
          {{ item.label }}
        </a>
      </nav>

      <!-- Right actions -->
      <div class="flex items-center gap-2">
        <!-- GitHub -->
        <a
          href="https://github.com/your-org/aether"
          target="_blank"
          rel="noopener"
          class="hidden sm:flex w-9 h-9 rounded-lg items-center justify-center transition-all duration-200 text-white/60 hover:text-white hover:bg-white/5"
          aria-label="GitHub"
        >
          <Github class="w-4 h-4" />
        </a>

        <!-- CTA -->
        <div class="hidden md:block">
          <LandingButton label="开始使用" @click="emit('cta')" />
        </div>

        <!-- Mobile menu button -->
        <button
          type="button"
          class="md:hidden w-9 h-9 rounded-lg flex items-center justify-center transition-all duration-200 text-white/60 hover:text-white hover:bg-white/5"
          :aria-label="mobileOpen ? 'Close menu' : 'Open menu'"
          @click="mobileOpen = !mobileOpen"
        >
          <X v-if="mobileOpen" class="w-4 h-4" />
          <Menu v-else class="w-4 h-4" />
        </button>
      </div>
    </div>

    <!-- Mobile menu -->
    <Transition name="slide-down">
      <div
        v-if="mobileOpen"
        class="md:hidden border-t border-white/10 bg-black/90 backdrop-blur-xl"
      >
        <div class="px-6 py-4 flex flex-col gap-1">
          <a
            v-for="item in NAV_ITEMS"
            :key="item.label"
            :href="item.href"
            @click="handleNavClick(item, $event)"
            class="mobile-nav-link text-sm font-medium px-3 py-2.5 rounded-lg transition-all duration-200"
            :class="isActive(item)
              ? 'text-white bg-white/10'
              : 'text-white/60 hover:text-white hover:bg-white/5'"
          >
            {{ item.label }}
          </a>
          <div class="pt-3 mt-2 border-t border-white/10">
            <LandingButton label="开始使用" full @click="mobileOpen = false; emit('cta')" />
          </div>
        </div>
      </div>
    </Transition>
  </header>
</template>

<style scoped>
.slide-down-enter-active {
  transition: all 0.25s ease-out;
}
.slide-down-leave-active {
  transition: all 0.2s ease-in;
}
.slide-down-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.slide-down-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
