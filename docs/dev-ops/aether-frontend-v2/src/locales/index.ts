import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import en from './en'

export type SupportedLocale = 'zh-CN' | 'en'
export const SUPPORTED_LOCALES: SupportedLocale[] = ['zh-CN', 'en']
export const DEFAULT_LOCALE: SupportedLocale = 'zh-CN'

export const i18n = createI18n({
  legacy: false,
  locale: (localStorage.getItem('locale') as SupportedLocale) || DEFAULT_LOCALE,
  fallbackLocale: 'en',
  messages: { 'zh-CN': zhCN, en },
})

export default i18n
