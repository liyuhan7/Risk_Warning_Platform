import { beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { AxiosError, CanceledError, type AxiosAdapter, type AxiosResponse } from 'axios'

const { toast, push, route } = vi.hoisted(() => ({
  toast: vi.fn(), push: vi.fn(), route: { value: { path: '/project' } }
}))
vi.mock('element-plus', () => ({ ElMessage: { error: toast } }))
vi.mock('@/router', () => ({ default: { push, currentRoute: route } }))

import request, { notifyRequestError } from './request'

const storage = new Map<string, string>()
vi.stubGlobal('sessionStorage', {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => storage.set(key, value),
  removeItem: (key: string) => storage.delete(key)
})

/** 使用 Axios adapter 替身，真实执行生产实例的请求与响应拦截器。 */
function respond(status: number, data: unknown): void {
  const adapter: AxiosAdapter = async (config) => {
    const response: AxiosResponse = { status, statusText: '', data, headers: {}, config }
    if (status >= 400) throw new AxiosError('private-transport', 'ERR_BAD_RESPONSE', config, undefined, response)
    return response
  }
  request.defaults.adapter = adapter
}

describe('统一请求层', () => {
  beforeEach(() => {
    toast.mockReset(); push.mockReset(); route.value.path = '/project'
    storage.clear(); storage.set('token', 'Bearer controlled'); storage.set('user', 'controlled-user')
    respond(200, { code: 200, message: 'success', data: { id: 1 } })
  })

  it('保留成功 envelope 与已有 Bearer 令牌', async () => {
    request.defaults.adapter = async (config) => {
      expect(config.baseURL).toBe('/api')
      expect(config.headers.Authorization).toBe('Bearer controlled')
      return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, message: 'success', data: { id: 1 } } }
    }
    expect(await request.get('/controlled')).toEqual({ code: 200, message: 'success', data: { id: 1 } })
    expect(toast).not.toHaveBeenCalled(); expect(push).not.toHaveBeenCalled()
  })

  it('无令牌不写认证头，空成功 data 仍兼容', async () => {
    storage.delete('token')
    request.defaults.adapter = async (config) => {
      expect(config.headers.Authorization).toBeUndefined()
      return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, message: 'success', data: null } }
    }
    expect(await request.get('/controlled')).toEqual({ code: 200, message: 'success', data: null })
    expect(toast).not.toHaveBeenCalled()
  })

  it('HTTP 401 清理存储会话并跳转，错误仍可由调用方捕获', async () => {
    respond(401, { code: 401, message: '凭据无效', data: null })
    const error = await request.get('/controlled').catch((failure: unknown) => failure)
    expect(axios.isAxiosError(error)).toBe(true)
    expect(storage.has('token')).toBe(false); expect(storage.has('user')).toBe(false)
    expect(push.mock.calls).toEqual([['/login']])
    expect(toast.mock.calls).toEqual([['凭据无效']])
    notifyRequestError(error, '页面失败')
    expect(toast).toHaveBeenCalledTimes(1)
  })

  it('登录页上的 401 不重复导航且使用缺省提示', async () => {
    route.value.path = '/login'; respond(401, { code: 401, data: null })
    await expect(request.get('/controlled')).rejects.toThrow('登录已过期，请重新登录')
    expect(push).not.toHaveBeenCalled()
    expect(toast.mock.calls).toEqual([['登录已过期，请重新登录']])
  })

  it.each([400, 403, 404, 409, 422, 500, 502, 503])('HTTP %i 保留会话，读取错误 envelope 并仅提示一次', async (status) => {
    respond(status, { code: status, message: `controlled-${status}`, data: null })
    const error = await request.get('/controlled').catch((failure: unknown) => failure)
    expect(error).toBeInstanceOf(AxiosError)
    expect(toast.mock.calls).toEqual([[`controlled-${status}`]])
    expect(storage.get('token')).toBe('Bearer controlled'); expect(storage.get('user')).toBe('controlled-user')
    expect(push).not.toHaveBeenCalled()
    notifyRequestError(error, '页面失败'); expect(toast).toHaveBeenCalledTimes(1)
  })

  it('非 401 HTTP 不因错误 body 的 code=401 清理会话', async () => {
    respond(403, { code: 401, message: 'controlled', data: null })
    await expect(request.get('/controlled')).rejects.toThrow('controlled')
    expect(storage.has('token')).toBe(true); expect(push).not.toHaveBeenCalled()
  })

  it.each([null, '<html>private-body</html>', { message: 123 }, { message: ' ' }])('非 JSON 或缺少有效 message 的 HTTP 错误使用通用提示', async (body) => {
    respond(500, body)
    await expect(request.get('/controlled')).rejects.toThrow('请求失败')
    expect(toast.mock.calls).toEqual([['请求失败']])
  })

  it('2xx 业务失败只拒绝和提示，不进入网络错误分支', async () => {
    respond(200, { code: 409, message: 'controlled-conflict', data: null })
    const error = await request.get('/controlled').catch((failure: unknown) => failure)
    expect(toast.mock.calls).toEqual([['controlled-conflict']])
    notifyRequestError(error, '页面失败'); expect(toast).toHaveBeenCalledTimes(1)
    expect(storage.has('token')).toBe(true); expect(push).not.toHaveBeenCalled()
  })

  it.each([null, [], 'private-html', { code: 200 }, { code: '200', data: null }])('非法成功响应不能作为成功数据返回', async (body) => {
    respond(200, body)
    await expect(request.get('/controlled')).rejects.toThrow('服务响应格式错误或请求失败')
    expect(toast).toHaveBeenCalledTimes(1)
  })

  it('无响应网络故障提示一次并保留原 AxiosError', async () => {
    request.defaults.adapter = async (config) => { throw new AxiosError('private-network', 'ERR_NETWORK', config) }
    const error = await request.get('/controlled').catch((failure: unknown) => failure)
    expect(axios.isAxiosError(error)).toBe(true)
    expect(toast.mock.calls).toEqual([['网络错误，请检查网络连接']])
    notifyRequestError(error, '页面失败'); expect(toast).toHaveBeenCalledTimes(1)
    expect(storage.has('token')).toBe(true); expect(push).not.toHaveBeenCalled()
  })

  it('调用方本地错误仍提示，不同请求错误分别提示', () => {
    const first = new Error('本地校验失败')
    notifyRequestError(first, 'fallback'); notifyRequestError(first, 'fallback')
    notifyRequestError(new Error('另一个失败'), 'fallback')
    expect(toast.mock.calls).toEqual([['本地校验失败'], ['另一个失败']])
  })

  it('主动取消请求不提示也不清理会话', async () => {
    const cancelled = new CanceledError('controlled-cancel')
    request.defaults.adapter = async () => { throw cancelled }
    await expect(request.get('/controlled')).rejects.toBe(cancelled)
    notifyRequestError(cancelled)
    expect(toast).not.toHaveBeenCalled(); expect(push).not.toHaveBeenCalled(); expect(storage.has('token')).toBe(true)
  })
})
