import { Navigate, Route, Routes } from 'react-router-dom'
import { useAsync, useSession } from '@odyssey/api'
import { AppShell, Badge, Card, Spinner } from '@odyssey/ui'
import { Login } from './Login'
import { OverviewPage } from './pages/Overview'
import { BookingsPage } from './pages/Bookings'
import { CalendarPage } from './pages/Calendar'
import { MenuPage } from './pages/Menu'
import { ReviewsPage } from './pages/Reviews'
import { ClosuresPage } from './pages/Closures'
import { BulkMenuPage } from './pages/BulkMenu'
import { EarningsPage } from './pages/Earnings'
import { StaffPage } from './pages/Staff'
import { SettingsPage } from './pages/Settings'

export function App() {
  const { user, logout, api } = useSession()
  const me = useAsync(() => (user ? api.provider.me() : Promise.resolve(undefined)), [user?.id])
  if (!user) return <Login />
  if (me.loading || !me.data) return <Spinner />
  const owner = user.role === 'PROVIDER_OWNER'
  const manager = owner || user.role === 'PROVIDER_MANAGER'
  const live = me.data.status === 'LIVE'
  const nav = [
    { to: '/', label: 'Overview', end: true },
    { to: '/bookings', label: 'Bookings' },
    { to: '/calendar', label: 'Slots' },
    { to: '/closures', label: 'Closures' },
    ...(manager ? [{ to: '/menu', label: 'Menu' }] : []),
    ...(owner ? [{ to: '/bulk', label: 'Bulk edit' }] : []),
    { to: '/reviews', label: 'Reviews' },
    ...(owner ? [{ to: '/earnings', label: 'Earnings' }, { to: '/staff', label: 'Staff' }] : []),
    ...(manager ? [{ to: '/settings', label: 'Settings' }] : []),
  ]
  return (
    <AppShell brand={me.data.name} subtitle="Vendor" user={`${user.name} · ${user.role.replace('PROVIDER_', '').toLowerCase()}`} onLogout={logout} nav={nav}>
      {!live && (
        <Card className="stack tight" >
          <div className="row"><Badge tone="warn">{me.data.status.replace(/_/g, ' ').toLowerCase()}</Badge><strong>You are not live yet</strong></div>
          <p className="muted">{me.data.reviewNote ?? 'The platform team is reviewing your details. You will be listed for customers once approved.'}</p>
        </Card>
      )}
      <div style={{ height: live ? 0 : 16 }} />
      <Routes>
        <Route path="/" element={<OverviewPage />} />
        <Route path="/bookings" element={<BookingsPage provider={me.data} />} />
        <Route path="/calendar" element={<CalendarPage provider={me.data} />} />
        <Route path="/closures" element={<ClosuresPage provider={me.data} />} />
        <Route path="/reviews" element={<ReviewsPage />} />
        {owner && <Route path="/bulk" element={<BulkMenuPage provider={me.data} />} />}
        {manager && <Route path="/menu" element={<MenuPage provider={me.data} />} />}
        {owner && <Route path="/earnings" element={<EarningsPage />} />}
        {owner && <Route path="/staff" element={<StaffPage />} />}
        {manager && <Route path="/settings" element={<SettingsPage provider={me.data} onChanged={me.reload} owner={owner} />} />}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppShell>
  )
}
