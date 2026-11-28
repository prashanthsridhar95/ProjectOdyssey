import { messageOf, useSession } from '@odyssey/api'
import { SignIn } from '@odyssey/ui'

/** Owners and managers sign in with phone + OTP. Counter staff sign in on the shared device with phone + 4-digit PIN. */
export function Login() {
  const { api, login, config } = useSession()
  return (
    <SignIn
      brand={config?.tenant.name ?? 'Odyssey'}
      audience={`your ${(config?.config.labels.provider ?? 'vendor').toLowerCase()} dashboard`}
      headline="Fill every bay, every day."
      points={[
        ['bell', 'New bookings arrive with a sound, so the counter never misses one'],
        ['calendar', 'Block breaks, add walk-ins and keep the calendar true'],
        ['wallet', 'See what you earned and when it reaches your bank'],
      ]}
      requestOtp={(phone) => api.auth.requestOtp(phone)}
      verifyOtp={async (phone, code) => { const r = await api.auth.verifyOtp(phone, code); login(r.user, r.accessToken) }}
      staffPin={async (phone, pin) => { const r = await api.auth.staffPin(phone, pin); login(r.user, r.accessToken) }}
      devHint={import.meta.env.DEV ? 'Development build: the dev identity provider accepts the code set in DEV_OTP.' : undefined}
      describeError={messageOf}
    />
  )
}
