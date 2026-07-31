<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LandingNavbar from '@/components/landing/LandingNavbar.vue'
import LandingHero from '@/components/landing/LandingHero.vue'
import LandingAppBar from '@/components/landing/LandingAppBar.vue'
import LandingConsole from '@/components/landing/LandingConsole.vue'
import LandingOrchestration from '@/components/landing/LandingOrchestration.vue'
import LandingIntegrations from '@/components/landing/LandingIntegrations.vue'
import LandingTestimonials from '@/components/landing/LandingTestimonials.vue'
import LandingPricing from '@/components/landing/LandingPricing.vue'
import LandingCTA from '@/components/landing/LandingCTA.vue'

const router = useRouter()
const auth = useAuthStore()

function handleGetStarted() {
  if (auth.isLoggedIn) {
    router.push({ name: 'Dashboard' })
  } else {
    router.push({ name: 'Login' })
  }
}
</script>

<template>
  <div class="relative min-h-screen overflow-x-hidden bg-[#0c0c0c] text-white">
    <!-- Fixed full-screen background video -->
    <div class="fixed inset-0 z-0 pointer-events-none">
      <video
        autoplay
        loop
        muted
        playsinline
        class="w-full h-full object-cover pointer-events-none"
        src="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260508_064122_c4750c0e-7476-4b44-94a2-a85a65c63bf2.mp4"
      />
    </div>

    <!-- Hidden-on-mobile fixed vertical guide lines at the 36rem container edges -->
    <div class="hidden md:block pointer-events-none fixed inset-y-0 left-1/2 -translate-x-[calc(50%+36rem)] w-px bg-white/10 z-[5]" />
    <div class="hidden md:block pointer-events-none fixed inset-y-0 left-1/2 translate-x-[calc(-50%+36rem)] w-px bg-white/10 z-[5]" />

    <!-- Root-level SVG noise filter (multiply blend) for the shiny headline -->
    <svg width="0" height="0" class="absolute" aria-hidden="true">
      <filter id="lp-noise">
        <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch" />
        <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 0.35 0" />
        <feComposite in2="SourceGraphic" operator="in" result="noise" />
        <feBlend in="SourceGraphic" in2="noise" mode="multiply" />
      </filter>
    </svg>

    <!-- Page content layered above the background -->
    <div class="relative z-10">
      <LandingNavbar @cta="handleGetStarted" />
      <LandingHero @cta="handleGetStarted" />
      <LandingAppBar />
      <LandingConsole />
      <LandingOrchestration />
      <LandingIntegrations />
      <LandingTestimonials />
      <LandingPricing />
      <LandingCTA @cta="handleGetStarted" />
    </div>
  </div>
</template>
