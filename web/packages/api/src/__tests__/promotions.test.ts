import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApi } from '../client'
import { daysText, promotionProblem, promotionSentence, timeText, type PromotionInput } from '../promotions'

const base: PromotionInput = {
  type: 'HAPPY_HOUR', name: 'Happy hour', kind: 'PERCENT', value: 2000, maxDiscountMinor: null, locationIds: [], services: [], variantKeys: [],
  days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY'], startTime: '14:00', endTime: '16:00', startDate: null, endDate: null, active: true,
}

describe('promotion sentences (same wording as the server)', () => {
  it('reads a happy hour the way a vendor says it', () => {
    expect(promotionSentence(base)).toBe('20% off every Mon–Thu 2–4 pm on all services')
    expect(promotionSentence({ ...base, days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] })).toBe('20% off every weekday 2–4 pm on all services')
    expect(promotionSentence({ ...base, kind: 'FLAT', value: 5000, days: ['SATURDAY', 'SUNDAY'], startTime: '11:00', endTime: '13:00', services: ['Foam wash', 'Interior clean'] }))
      .toBe('₹50 off every weekend 11 am–1 pm on Foam wash and Interior clean')
    expect(promotionSentence({ ...base, value: 1250, days: [], startTime: '14:30' })).toBe('12.5% off every day 2:30–4 pm on all services')
  })
  it('reads date discounts, caps and vehicle types', () => {
    const date = { ...base, type: 'DATE_DISCOUNT' as const, days: [], startTime: null, endTime: null }
    expect(promotionSentence({ ...date, kind: 'FLAT', value: 15000, startDate: '2026-12-25', endDate: '2026-12-25' })).toBe('₹150 off on 25 Dec on all services')
    expect(promotionSentence({ ...date, value: 1500, startDate: '2026-10-10', endDate: '2026-10-15', maxDiscountMinor: 20000 })).toBe('15% off from 10 Oct to 15 Oct on all services (up to ₹200)')
    expect(promotionSentence({ ...base, variantKeys: ['SUV', 'SEDAN'] }, (k) => (k === 'SUV' ? 'SUV' : 'Sedan'))).toMatch(/for SUV and Sedan$/)
    expect(promotionSentence({ ...date, kind: 'PRICE', value: 29900, startDate: '2026-12-01', endDate: '2026-12-01' })).toBe('₹299 special price on 1 Dec on all services')
  })
  it('names days and times plainly', () => {
    expect(daysText(['MONDAY', 'WEDNESDAY', 'FRIDAY'])).toBe('every Mon, Wed and Fri')
    expect(daysText([])).toBe('every day')
    expect(timeText('09:00', '11:30')).toBe('9–11:30 am')
    expect(timeText('11:00', '13:00')).toBe('11 am–1 pm')
  })
  it('explains what is missing before saving', () => {
    expect(promotionProblem(base)).toBeNull()
    expect(promotionProblem({ ...base, name: ' ' })).toBe('Give the offer a name')
    expect(promotionProblem({ ...base, endTime: '13:00' })).toBe('The end time must be after the start time')
    expect(promotionProblem({ ...base, days: [] })).toBe('Pick at least one day')
    expect(promotionProblem({ ...base, value: 12000 })).toMatch(/between 1% and 100%/)
  })
})

describe('promotions client', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('sends the owner PIN when a vendor saves an offer, and not when pausing', async () => {
    const fn = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ id: 'p1' }), { status: 200 }))
    vi.stubGlobal('fetch', fn)
    const api = createApi({ baseUrl: 'http://api.test', getToken: () => 't' })
    await api.promotions.vendor.save(base, '123456')
    const [url, init] = fn.mock.calls[0]!
    expect(String(url)).toBe('http://api.test/v1/provider/promotions')
    expect((init as RequestInit).method).toBe('POST')
    expect((init as RequestInit).headers).toMatchObject({ 'X-Owner-Pin': '123456' })
    await api.promotions.vendor.setActive('p1', false)
    expect((fn.mock.calls[1]![1] as RequestInit).headers).not.toHaveProperty('X-Owner-Pin')
  })
})
