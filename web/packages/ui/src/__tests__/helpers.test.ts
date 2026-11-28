import { describe, expect, it } from 'vitest'
import { isValidIndianMobile, toE164 } from '../auth'
import { initials, statusTone, toCsv } from '../components'

describe('ui helpers', () => {
  it('normalises Indian mobile numbers however they are typed', () => {
    expect(toE164('98450 21734')).toBe('+919845021734')
    expect(toE164('+91 98450-21734')).toBe('+919845021734')
    expect(toE164('09845021734')).toBe('+919845021734')
    expect(isValidIndianMobile('98450 2173')).toBe(false)
    expect(isValidIndianMobile('9845021734')).toBe(true)
  })
  it('escapes CSV cells that hold commas, quotes or new lines', () => {
    expect(toCsv([['a', 'b,c', 'say "hi"', 'x\ny', null, 3]])).toBe('a,"b,c","say ""hi""","x\ny",,3')
  })
  it('makes initials and status tones', () => {
    expect(initials('Sparkle Auto Spa')).toBe('SA')
    expect(initials('')).toBe('?')
    expect(statusTone('COMPLETED')).toBe('good')
    expect(statusTone('NO_SHOW')).toBe('bad')
    expect(statusTone('SOMETHING_NEW')).toBe('neutral')
  })
})
