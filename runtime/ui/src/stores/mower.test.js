import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'
import { useMowerStore } from './mower'

vi.mock('axios', () => ({ default: { get: vi.fn() } }))

describe('Mower startup ownership', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('keeps process ownership when the task is stopped', async () => {
    axios.get.mockResolvedValue({
      data: {
        status: 'stopped',
        auto_start_handled: true,
        desktop_startup_handled: true,
        plan_condition: []
      }
    })
    const store = useMowerStore()
    await store.get_running()
    expect(store.running).toBe(false)
    expect(store.auto_start_handled).toBe(true)
    expect(store.desktop_startup_handled).toBe(true)
  })

  it('clears ownership when connecting to a legacy host', async () => {
    const store = useMowerStore()
    store.auto_start_handled = true
    store.desktop_startup_handled = true
    axios.get.mockResolvedValue({ data: { status: 'stopped', plan_condition: [] } })
    await store.get_running()
    expect(store.auto_start_handled).toBe(false)
    expect(store.desktop_startup_handled).toBe(false)
  })
})
