import { messageOf, useSession } from '@odyssey/api'
import { SignIn } from '@odyssey/ui'

/** Phone + OTP sign-in. The identity provider behind it is swappable (dev code today, the IAM product later). */
export function Login() {
  const { api, login, config } = useSession()
  return (
    <SignIn
      brand={config?.tenant.name ?? 'Odyssey'}
      audience="the admin console"
      headline="Run the marketplace from one place."
      points={[
        ['shield', 'Verify and onboard vendors with a clear review trail'],
        ['wallet', 'Settle payouts from a balanced double-entry ledger'],
        ['trend', 'Watch bookings, cancellations and take rate as they happen'],
      ]}
      requestOtp={(phone) => api.auth.requestOtp(phone)}
      verifyOtp={async (phone, code) => { const r = await api.auth.verifyOtp(phone, code); login(r.user, r.accessToken) }}
      devHint={import.meta.env.DEV ? 'Development build: the dev identity provider accepts the code set in DEV_OTP.' : undefined}
      describeError={messageOf}
    />
  )
}
