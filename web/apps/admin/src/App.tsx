import { Navigate, Route, Routes } from 'react-router-dom'
import { useLabels, useSession } from '@odyssey/api'
import { AppShell } from '@odyssey/ui'
import { Login } from './Login'
import { Dashboard } from './pages/Dashboard'
import { Vendors, VendorDetail, NewVendor } from './pages/Vendors'
import { OrdersPage } from './pages/Orders'
import { Payouts } from './pages/Payouts'
import { BillingPage } from './pages/Billing'
import { SettingsPage } from './pages/Settings'
import { Audit } from './pages/Audit'

export function App() {
  const { user, logout, config } = useSession()
  const labels = useLabels()
  if (!user) return <Login />
  return (
    <AppShell
      brand={config?.tenant.name ?? 'Odyssey'}
      subtitle="Admin"
      user={`${user.name} · ${user.role.toLowerCase()}`}
      onLogout={logout}
      nav={[
        { to: '/', label: 'Dashboard', end: true },
        { to: '/vendors', label: `${labels.provider}s` },
        { to: '/orders', label: `${labels.order}s` },
        { to: '/payouts', label: 'Payouts' },
        { to: '/billing', label: 'Platform billing' },
        { to: '/settings', label: 'Settings' },
        { to: '/audit', label: 'Audit log' },
      ]}
    >
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/vendors" element={<Vendors />} />
        <Route path="/vendors/new" element={<NewVendor />} />
        <Route path="/vendors/:id" element={<VendorDetail />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/payouts" element={<Payouts />} />
        <Route path="/billing" element={<BillingPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/audit" element={<Audit />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppShell>
  )
}
