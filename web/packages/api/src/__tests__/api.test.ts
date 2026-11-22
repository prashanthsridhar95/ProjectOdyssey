import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createApi } from '../client'
import { bpsToPercent, istInstant, money } from '../format'

function mockFetch(status: number, body?: unknown) {
  const fn = vi.fn().mockResolvedValue(new Response(body === undefined ? null : JSON.stringify(body), { status }))
  vi.stubGlobal('fetch', fn)
  return fn
}
afterEach(() => vi.unstubAllGlobals())

describe('format', () => {
  it('formats paise as rupees', () => {
    expect(money(29900)).toBe('₹299')
    expect(money(29950)).toBe('₹299.50')
    expect(money(undefined)).toBe('-')
  })
  it('formats basis points', () => {
    expect(bpsToPercent(1500)).toBe('15%')
    expect(bpsToPercent(1250)).toBe('12.50%')
  })
  it('turns an IST wall-clock time into a UTC instant', () => {
    expect(istInstant('2030-01-07', '10:00')).toBe('2030-01-07T04:30:00.000Z')
  })
})

describe('api client', () => {
  const api = (token: string | null = 't0k') => createApi({ baseUrl: 'http://api.test', tenant: 'carwash', getToken: () => token })

  it('sends tenant, bearer token and owner PIN headers', async () => {
    const fetchFn = mockFetch(200, [])
    await api().provider.earnings('123456').catch(() => undefined)
    const [url, init] = fetchFn.mock.calls[0]
    expect(String(url)).toBe('http://api.test/v1/provider/earnings')
    expect(init.headers).toMatchObject({ 'X-Tenant': 'carwash', Authorization: 'Bearer t0k', 'X-Owner-Pin': '123456' })
  })

  it('turns an error body into an ApiError with the server code', async () => {
    mockFetch(403, { code: 'pin_invalid', message: 'Wrong PIN' })
    const err = await api().provider.earnings('000000').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.code).toBe('pin_invalid')
    expect(err.isPinProblem).toBe(true)
  })

  it('signs the user out when the token is rejected', async () => {
    mockFetch(401, { code: 'unauthorized', message: 'Please sign in' })
    const onUnauthorized = vi.fn()
    const client = createApi({ baseUrl: 'http://api.test', getToken: () => 'old', onUnauthorized })
    await client.auth.me().catch(() => undefined)
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('reports a network failure plainly', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fail')))
    const err = await api().config().catch((e) => e)
    expect(err.code).toBe('network')
  })

  it('handles 204 responses', async () => {
    mockFetch(204)
    await expect(api().auth.requestOtp('+911234567890')).resolves.toBeUndefined()
  })
})
