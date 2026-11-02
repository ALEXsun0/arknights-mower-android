import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import Component from './MaaBasic.vue'

const state = vi.hoisted(() => ({ client: null, config: {}, mounted: [], unmounted: [] }))
vi.mock('vue', async (original) => ({
  ...(await original()),
  useSSRContext: () => ({ modules: new Set() }),
  inject: (key) => (key === 'axios' ? state.client : false),
  onMounted: (callback) => state.mounted.push(callback),
  onUnmounted: (callback) => state.unmounted.push(callback)
}))
vi.mock('naive-ui', async () => {
  const { h } = await import('vue')
  const wrapper = {
    inheritAttrs: false,
    props: ['label'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [slots.header?.(), slots.label?.() || props.label, slots.default?.()])
  }
  return Object.fromEntries(
    [
      'NA',
      'NAlert',
      'NButton',
      'NCard',
      'NCheckbox',
      'NDivider',
      'NForm',
      'NFormItem',
      'NInput',
      'NProgress',
      'NRadio',
      'NRadioGroup',
      'NSelect',
      'NSpace',
      'NSwitch'
    ].map((name) => [name, wrapper])
  )
})
vi.mock('./HelpText.vue', () => ({
  default: {
    setup:
      (_, { slots }) =>
      () =>
        slots.default?.()
  }
}))
vi.mock('@/stores/config', () => ({ useConfigStore: () => ({}) }))
vi.mock('@/stores/mower', () => ({ useMowerStore: () => ({}) }))
vi.mock('./SoftwareComponentUpdates.vue', () => ({ default: { setup: () => () => null } }))
vi.mock('@/utils/dialog', () => ({ folder_dialog: vi.fn() }))
vi.mock('pinia', () => ({ storeToRefs: () => state.config }))

describe('MAA core and resource updates', () => {
  let scope
  let component
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('window', { setTimeout, clearTimeout })
    state.mounted = []
    state.unmounted = []
    state.config = Object.fromEntries(
      Object.entries({
        manager_owned_device: false,
        runtime_platform: 'android',
        updates_enabled: true,
        maa_path: '/maa',
        maa_mirrorchyan_token: '',
        maa_update_channel: 'beta',
        maa_auto_check_update: false,
        maa_auto_update: false,
        maa_resource_auto_check_update: false,
        maa_resource_auto_update: false,
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
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })
  it('checks the companion after finding a core update and preserves the core check token', async () => {
    let complete
    state.client.post.mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve
        })
    )
    const check = vi.fn(async () => {})
    component.maa_component_controls.value = [{ check }]
    const pending = component.check_maa_update()
    expect(check).not.toHaveBeenCalled()
    expect(component.maa_combined_checking.value).toBe(true)
    complete({
      data: { ok: true, available: true, latest: { tag: 'v6.19.0' }, check_id: 'maa-only' }
    })
    await pending
    expect(check).toHaveBeenCalledOnce()
    expect(component.maa_update_check.value.id).toBe('maa-only')
    expect(component.maa_combined_checking.value).toBe(false)
  })

  it('a companion failure does not hide the core update', async () => {
    component.maa_component_controls.value = [
      {
        check: async () => {
          throw new Error('offline')
        }
      }
    ]
    await component.check_maa_update()
    expect(component.maa_update_check.value.available).toBe(true)
  })
  it('a core failure skips the companion', async () => {
    state.client.post.mockRejectedValue(new Error('core offline'))
    const check = vi.fn(async () => {})
    component.maa_component_controls.value = [{ check }]
    await component.check_maa_update()
    expect(check).not.toHaveBeenCalled()
    expect(component.maa_update_check.value.status).toBe('error')
  })
  it.each([
    { ok: true, available: false },
    { ok: false, message: 'core check failed' },
    { ok: true, available: true, latest: { tag: 'v6.18.0' }, installed_version: 'v6.18.0' }
  ])('skips the companion without a confirmed core update: %j', async (result) => {
    state.client.post.mockResolvedValueOnce({ data: result })
    const check = vi.fn()
    component.maa_component_controls.value = [{ check }]
    await component.check_maa_update()
    expect(check).not.toHaveBeenCalled()
  })

  it('checks the interface when automatic MAA checking finds an update', async () => {
    const check = vi.fn()
    component.maa_component_controls.value = [{ check }]
    state.config.maa_auto_check_update.value = true
    component.maa_update_supported.value = true
    await nextTick()
    await component.auto_check_updates()
    expect(check).toHaveBeenCalledOnce()
  })

  it.each(['linux', 'darwin', 'windows'])(
    'does not add requests or controls on %s',
    async (platform) => {
      state.config.runtime_platform.value = platform
      const check = vi.fn(async () => {})
      component.maa_component_controls.value = [{ check }]
      await component.get_maa_update_info()
      expect(component.maa_component_updates.value).toEqual([])
      await component.check_maa_update()
      expect(check).not.toHaveBeenCalled()
      expect(state.client.post).toHaveBeenCalledTimes(1)
      expect(state.client.post.mock.calls[0][0]).toMatch(/\/maa-update\/check$/)
    }
  )
})
