import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import Component from './MaaBasic.vue'

const state = vi.hoisted(() => ({ client: null, config: {}, unmounted: [] }))
vi.mock('vue', async (original) => ({
  ...(await original()),
  useSSRContext: () => ({ modules: new Set() }),
  inject: (key) => (key === 'axios' ? state.client : false),
  onMounted: () => {},
  onUnmounted: (callback) => state.unmounted.push(callback)
}))
vi.mock('@/stores/config', () => ({ useConfigStore: () => ({}) }))
vi.mock('pinia', () => ({ storeToRefs: () => state.config }))

describe('Android MAA Mirror resource updates', () => {
  let scope
  let component
  beforeEach(() => {
    state.unmounted = []
    state.config = Object.fromEntries(
      Object.entries({
        runtime_platform: 'android',
        maa_path: '/maa',
        maa_mirrorchyan_token: '',
        maa_update_channel: 'beta',
        maa_auto_check_update: false,
        maa_restore_theme_enable: false,
        maa_restore_theme: '',
        maa_conn_preset: '',
        maa_touch_option: ''
      }).map(([key, value]) => [key, ref(value)])
    )
    state.client = {
      get: vi.fn(async () => ({
        data: {
          ok: true,
          supported: true,
          installed: true,
          platform: 'android',
          component_updates: [{ endpoint: '/android/python-update' }]
        }
      })),
      post: vi.fn(async () => ({
        data: {
          ok: true,
          available: true,
          latest: { tag: 'v6.19.0' },
          installed_version: 'v6.18.0',
          check_id: 'maa-only'
        }
      }))
    }
    scope = effectScope()
    component = scope.run(() => Component.setup({}, { expose: () => {} }))
    component.maa_installed.value = true
  })
  afterEach(() => {
    state.unmounted.forEach((callback) => callback())
    scope.stop()
  })
  it('uses Mirror for Android resources while keeping the core on GitHub', async () => {
    state.config.maa_mirrorchyan_token.value = 'fixture-cdk'
    component.android_resource_source.value = 'mirrorchyan'
    await component.check_maa_resource_update()
    expect(state.client.post).toHaveBeenCalledWith(
      expect.stringMatching(/\/maa-resource-update\/check$/),
      expect.objectContaining({ source: 'mirrorchyan', mirror_token: 'fixture-cdk' })
    )
    await component.check_maa_update()
    expect(state.client.post).toHaveBeenCalledWith(
      expect.stringMatching(/\/maa-update\/check$/),
      expect.objectContaining({ source: 'github', mirror_token: '' })
    )
    expect(state.client.post.mock.calls.some(([url]) => url.endsWith('/mirrorchyan-status'))).toBe(
      false
    )
  })
  it('invalidates the resource receipt when its Android source changes', async () => {
    component.maa_resource_update_check.value = {
      status: 'success',
      available: true,
      id: 'old-receipt'
    }
    component.android_resource_source.value = 'mirrorchyan'
    await Promise.resolve()
    expect(component.maa_resource_update_check.value.id).toBe('')
  })
})
