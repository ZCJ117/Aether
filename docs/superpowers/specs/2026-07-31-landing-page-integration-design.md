# Landing Page Integration — Design Spec

**Date:** 2026-07-31  
**Status:** Draft  
**Topic:** Port `fronted` (React) landing page into `aether-frontend-v2` (Vue 3), replacing the pre-login public pages

---

## 1. Goal

Replace the current minimal `LandingView.vue` in `aether-frontend-v2` with a polished, multi-section landing page ported from the `fronted` React project. All "Aura" email-client branding and text replaced with "Aether" AI Agent terminology. All post-login pages (LoginView, /app/*) remain untouched.

## 2. Scope & Boundaries

### In Scope
- Port all 9 sections from the React landing page to Vue 3 SFCs
- Replace all email-client text with AI Agent content (Chinese + English bilingual as appropriate)
- Preserve animations via `@vueuse/motion`
- Preserve all visual effects (video background, noise SVG filter, liquid-glass borders, shiny gradient text, vertical guide lines)
- Navbar links anchor-scroll to landing sections; CTAs route to `/login`

### Out of Scope
- LoginView.vue — zero changes
- All /app/* routes, views, components — zero changes
- Router configuration — zero changes (existing `/` and `/login` routes stay)
- Backend API alignment
- Auth store changes
- i18n integration (hardcoded Chinese text is acceptable for a landing page)

## 3. Component Tree

```
LandingView.vue                    ← REPLACE (page shell: bg video, noise filter, vertical guides)
├── LandingNavbar.vue              ← ADD (logo + nav links + CTA)
├── LandingHero.vue                ← ADD (animated headline + subtitle + CTA)
├── LandingAppBar.vue              ← ADD (Mac menu bar → Aether branding)
├── LandingConsole.vue             ← ADD (InboxMockup → Agent Console mockup)
├── LandingOrchestration.vue       ← ADD (FeatureTriage → Agent orchestration)
├── LandingIntegrations.vue        ← ADD (LogoCloud → integration partners)
├── LandingTestimonials.vue        ← ADD (user quotes, AI context)
├── LandingPricing.vue             ← ADD (pricing tiers)
├── LandingCTA.vue                 ← ADD (final CTA)
├── LandingButton.vue              ← ADD (shared CTA button)
├── SectionEyebrow.vue             ← ADD (shared section label)
└── AetherLogo.vue                 ← ADD (brand SVG logo)
```

## 4. Text Content Mapping

| Original (Aura email) | Section | → Aether AI Agent |
|---|---|---|
| "Your email. Revitalized" | Hero headline | "你的 AI 智能体。即刻编排" |
| "Aura is the premier inbox platform..." | Hero subtitle | "Aether 是企业级多智能体 AI 运行时..." |
| "Download Aura" | All CTAs | "开始使用" |
| "Solutions, Pricing, Blog, Documentation, Careers" | Nav | "特性, 定价, 文档, 关于, 博客" |
| "Aura — Inbox" | AppBar title | "Aether — Agent Console" |
| "Compose with Aura" | Sidebar CTA | "部署智能体" |
| Email sidebar (Inbox/Starred/Sent/...) | Console sidebar | Agents, Tasks, Tools, Logs, Memory |
| Email messages list | Console list | Agent execution tasks / results |
| "Summary by Aura" | Console reader | "摘要 · Aether Agent" |
| "Clear your inbox in a single pass" | Orchestration heading | "一个工作流，编排所有智能体" |
| "Triage" + "AI-native" | Eyebrow | "编排" + "AI 原生" |
| "Auto-categorize, Snooze for later..." | Chips | "自动路由, 并行执行, 循环迭代, 工具编排" |
| Priority/Follow-up/Updates/Archived | Triage groups | 执行中/等待/完成/归档 |
| "Trusted by the world's most thoughtful teams" | Integrations | "深受全球顶尖 AI 团队信赖" |
| Company logos (Linear, Vercel, ...) | Integrations | AI/tech partner logos (same visuals, decorative) |
| Email-related testimonials | Testimonials | AI Agent developer quotes |
| "Your email. Revitalized" watermark | Pricing backdrop | "智能体协作。生产级" |
| Pricing plans (Free/Standard/Pro) | Pricing | Pro/Team/Enterprise AI Agent plans |
| "Close the tabs. Open your day." | CTA heading | "告别重复劳动。开启智能协作。" |
| "Talk to sales" | CTA secondary | "联系我们" |

## 5. Animation Strategy

Use `@vueuse/motion` (`v-motion` directive) with the same easing curves as the original Framer Motion:

- **Hero:** `initial: { opacity: 0, y: 20 }` → staggered entrance (0.3s / 0.5s / 0.7s delays)
- **Sections below fold:** `v-motion-visible` with `{ opacity: 0, y: 20/24/40 }` → `{ opacity: 1, y: 0 }`, `duration: 0.6–0.9s`, `ease: [0.22, 1, 0.36, 1]`
- **Shiny gradient:** CSS `@keyframes shiny` (already in source CSS, just port)
- **Staggered lists:** CSS animation-delay or `v-motion` delay increment

No scroll-trigger complexity beyond `v-motion-visible` (built-in IntersectionObserver wrapper).

## 6. Styling

### Ported CSS Utilities (add to `src/style.css`)

- `.liquid-glass` — glassmorphism card with gradient border pseudo-element
- `.animate-shiny` — background-position keyframe animation for gradient text
- `.c3-*` classes — pricing section cards, toggle, watermark (rename to `.lp-*` for landing-page namespace)

### Tailwind Config

No changes needed — source and target both use Tailwind. All utility classes (`bg-[#0c0c0c]`, `text-white/60`, etc.) work as-is.

### Video Background

Keep the same CloudFront video URL from the source. It's a decorative abstract background video — not email-specific.

## 7. Files to Create/Modify

| Action | File |
|---|---|
| ADD | `src/components/landing/LandingNavbar.vue` |
| ADD | `src/components/landing/LandingHero.vue` |
| ADD | `src/components/landing/LandingAppBar.vue` |
| ADD | `src/components/landing/LandingConsole.vue` |
| ADD | `src/components/landing/LandingOrchestration.vue` |
| ADD | `src/components/landing/LandingIntegrations.vue` |
| ADD | `src/components/landing/LandingTestimonials.vue` |
| ADD | `src/components/landing/LandingPricing.vue` |
| ADD | `src/components/landing/LandingCTA.vue` |
| ADD | `src/components/landing/LandingButton.vue` |
| ADD | `src/components/landing/SectionEyebrow.vue` |
| ADD | `src/components/landing/AetherLogo.vue` |
| REPLACE | `src/views/LandingView.vue` |
| MODIFY | `src/style.css` (append liquid-glass + shiny + pricing CSS) |
| MODIFY | `package.json` (add `@vueuse/motion`) |

## 8. Data Flow

This is a purely presentational landing page. No API calls, no async state, no store dependencies beyond the existing `useAuthStore.isLoggedIn` check (already in `LandingView.vue`). The CTA button checks auth state and routes accordingly — this logic is preserved from the existing view.

## 9. Error & Edge Cases

- **Video load failure:** CSS `background-color: #0c0c0c` fallback already present
- **JS disabled / motion failure:** All content is in static HTML; animations degrade gracefully
- **Mobile responsive:** Source project has responsive breakpoints; port them faithfully
- **Scroll position:** No scroll restoration needed (landing page, no virtual lists)

## 10. Testing Criteria

- [ ] Landing page loads at `/` with all 9 sections visible
- [ ] "开始使用" button routes to `/login` when not authenticated
- [ ] "开始使用" button routes to `/app/dashboard` when authenticated
- [ ] `/login` page renders unchanged
- [ ] All `/app/*` routes render unchanged
- [ ] Video background plays (or gracefully absent)
- [ ] Animations trigger on scroll
- [ ] Mobile viewport: sections stack, no horizontal overflow
- [ ] No React/JSX artifacts remain in the codebase
- [ ] No "Aura" text remains in any user-visible content
- [ ] No email-client terminology remains in user-visible content
