import { useMemo } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { useAsync, useLabels, useSession } from '@odyssey/api'
import { AppShell, NotFound, type Command, type NavGroup } from '@odyssey/ui'
import { Login } from './Login'
import { Dashboard } from './pages/Dashboard'
import { Vendors, VendorDetail, NewVendor } from './pages/Vendors'
import { OrdersPage } from './pages/Orders'
import { Payments } from './pages/Payments'
import { Rules } from './pages/Rules'
import { Support } from './pages/Support'
import { Customers } from './pages/Customers'
import { Moderation } from './pages/Moderation'
import { BillingPage } from './pages/Billing'
import { SettingsPage } from './pages/Settings'
import { Audit } from './pages/Audit'

const ROLE_LABEL: Record<string, string> = { ADMIN: 'Administrator', SUPPORT: 'Support', FINANCE: 'Finance' }

export function App() {
  const { user, logout, config, api } = useSession()
  const labels = useLabels()
  const navigate = useNavigate()
  // Work waiting on someone: shown as counts in the menu so nothing sits unseen.
  const queue = useAsync(() => (user ? Promise.all([api.admin.providers('SUBMITTED'), api.admin.tickets('OPEN'), api.admin.outliers()]) : Promise.resolve(null)), [user?.id])
  const [review, tickets, outliers] = queue.data ?? [[], [], []]

  const nav: NavGroup[] = useMemo(() => [
    { items: [{ to: '/', label: 'Dashboard', end: true, icon: 'dashboard' }] },
    { label: 'Marketplace', items: [
      { to: '/vendors', label: `${labels.provider}s`, icon: 'store', count: review.length, keywords: 'onboarding verification kyc' },
      { to: '/orders', label: `${labels.order}s`, icon: 'calendar', keywords: 'orders bookings refunds cancel' },
      { to: '/customers', label: `${labels.customer}s`, icon: 'users' },
      { to: '/moderation', label: 'Menu moderation', icon: 'tag', count: outliers.length, countTone: 'neutral', keywords: 'prices outliers' },
      { to: '/support', label: 'Support', icon: 'lifebuoy', count: tickets.length, keywords: 'tickets reports complaints' },
    ] },
    { label: 'Money', items: [
      { to: '/payments', label: 'Payments', icon: 'wallet', keywords: 'payouts ledger collections refunds settlement' },
      { to: '/rules', label: 'Commission rules', icon: 'percent' },
      { to: '/billing', label: 'Platform billing', icon: 'receipt' },
    ] },
    { label: 'Configuration', items: [
      { to: '/settings', label: 'Settings', icon: 'settings', keywords: 'tax fees cancellation policy areas cities' },
      { to: '/audit', label: 'Audit log', icon: 'history', keywords: 'history changes who' },
    ] },
  ], [labels, review.length, tickets.length, outliers.length])

  const commands: Command[] = useMemo(() => [
    { id: 'new-vendor', label: `Add a ${labels.provider.toLowerCase()}`, section: 'Actions', icon: 'plus', run: () => navigate('/vendors/new') },
    { id: 'review', label: `Review submitted ${labels.provider.toLowerCase()}s`, section: 'Actions', icon: 'shield', run: () => navigate('/vendors?status=SUBMITTED') },
    { id: 'payouts', label: 'Run the payout batch', section: 'Actions', icon: 'wallet', run: () => navigate('/payments') },
    { id: 'tickets', label: 'Open support tickets', section: 'Actions', icon: 'lifebuoy', run: () => navigate('/support') },
  ], [labels, navigate])

  if (!user) return <Login />
  return (
    <AppShell
      brand={config?.tenant.name ?? 'Odyssey'}
      subtitle="Admin"
      user={{ name: user.name, role: ROLE_LABEL[user.role] ?? user.role.toLowerCase(), detail: user.phone }}
      onLogout={logout}
      nav={nav}
      commands={commands}
      env={import.meta.env.DEV ? { label: 'Development', demo: true } : undefined}
    >
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/vendors" element={<Vendors />} />
        <Route path="/vendors/new" element={<NewVendor />} />
        <Route path="/vendors/:id" element={<VendorDetail />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/payments" element={<Payments />} />
        <Route path="/payouts" element={<Navigate to="/payments" replace />} />
        <Route path="/rules" element={<Rules />} />
        <Route path="/customers" element={<Customers />} />
        <Route path="/moderation" element={<Moderation onChange={queue.reload} />} />
        <Route path="/support" element={<Support onChange={queue.reload} />} />
        <Route path="/billing" element={<BillingPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/audit" element={<Audit />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </AppShell>
  )
}
