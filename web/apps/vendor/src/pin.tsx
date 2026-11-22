import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '@odyssey/api'
import { PinPrompt } from '@odyssey/ui'

type Ask = <T>(run: (pin: string) => Promise<T>, title?: string) => Promise<T | undefined>
const Ctx = createContext<Ask>(async () => undefined)

/**
 * Owner-PIN gate. `withPin(run)` asks for the PIN, runs the call with it, and re-asks if the server says the PIN is wrong.
 * The PIN is kept in memory for 60 seconds so a short run of protected actions is not tedious; it is never stored.
 */
export function PinProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState<{ title?: string; resolve: (pin: string | null) => void } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const cache = useRef<{ pin: string; at: number } | null>(null)

  const askUser = (title?: string) => new Promise<string | null>((resolve) => setOpen({ title, resolve }))

  const withPin: Ask = useCallback(async (run, title) => {
    let pin = cache.current && Date.now() - cache.current.at < 60_000 ? cache.current.pin : null
    for (;;) {
      if (!pin) {
        pin = await askUser(title)
        if (!pin) return undefined
      }
      try {
        const result = await run(pin)
        cache.current = { pin, at: Date.now() }
        setOpen(null); setError(null)
        return result
      } catch (e) {
        if (e instanceof ApiError && e.isPinProblem && e.code !== 'pin_not_set') {
          cache.current = null; pin = null
          setError(e.code === 'pin_locked' ? e.message : 'That PIN is not right.')
          if (e.code === 'pin_locked') { setOpen(null); throw e }
          continue
        }
        setOpen(null); setError(null)
        throw e
      }
    }
  }, [])

  return (
    <Ctx.Provider value={withPin}>
      {children}
      {open && <PinPrompt title={open.title} error={error} onClose={() => { open.resolve(null); setOpen(null); setError(null) }} onSubmit={(pin) => open.resolve(pin)} />}
    </Ctx.Provider>
  )
}

export const useWithPin = () => useContext(Ctx)
