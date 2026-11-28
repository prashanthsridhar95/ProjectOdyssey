import { describe, expect, it } from 'vitest'
import { addDays, moneyShort, phone, recent, relative } from '../format'
import { statusMessage } from '../client'

describe('format helpers', () => {
  const now = Date.parse('2026-11-28T12:00:00Z')
  it('describes times relative to now', () => {
    expect(relative('2026-11-28T11:59:40Z', now)).toBe('just now')
    expect(relative('2026-11-28T11:40:00Z', now)).toBe('20 min ago')
    expect(relative('2026-11-28T09:00:00Z', now)).toBe('3 h ago')
    expect(relative('2026-11-27T06:00:00Z', now)).toBe('yesterday')
    expect(relative('2026-11-28T12:30:00Z', now)).toBe('in 30 min')
  })
  it('drops the relative time once a date is enough', () => {
    expect(recent('2026-11-10T12:00:00Z', now)).toBeNull()
    expect(recent('2026-11-26T12:00:00Z', now)).toBe('2 days ago')
  })
  it('formats Indian mobile numbers and leaves others alone', () => {
    expect(phone('+919845021734')).toBe('+91 98450 21734')
    expect(phone('+14155550100')).toBe('+14155550100')
    expect(phone(undefined)).toBe('-')
  })
  it('shortens money for chart axes', () => {
    expect(moneyShort(4_500_000)).toBe('₹45k')
    expect(moneyShort(12_000_000)).toBe('₹1.2L')
    expect(moneyShort(9_900)).toBe('₹99')
  })
  it('moves dates across month ends in India time', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01')
    expect(addDays('2026-12-01', -1)).toBe('2026-11-30')
  })
  it('explains HTTP failures in plain words', () => {
    expect(statusMessage(403)).toMatch(/do not have access/)
    expect(statusMessage(503)).toMatch(/server had a problem/)
    expect(statusMessage(429)).toMatch(/Too many attempts/)
  })
})

describe('dates that are missing', () => {
  it('print a dash, never "Invalid Date"', async () => {
    const { dateOnly, dateTime, timeOf } = await import('../format')
    expect(dateOnly('')).toBe('-')
    expect(dateTime('nonsense')).toBe('-')
    expect(timeOf('')).toBe('-')
  })
})
