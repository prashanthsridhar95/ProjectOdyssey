import { useMemo } from 'react'
import { Link, Route, Routes, useNavigate } from 'react-router-dom'
import { isoDate, useAsync, useSession } from '@odyssey/api'
import { AppShell, Badge, Button, Card, EmptyState, Icon, NotFound, Splash, type Command, type NavGroup } from '@odyssey/ui'
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

const ROLE: Record<string, string> = { PROVIDER_OWNER: 'Owner', PROVIDER_MANAGER: 'Manager', PROVIDER_STAFF: 'Counter staff' }
const STATUS_TEXT: Record<string, string> = {
  SUBMITTED: 'Your application is with the platform team. Most are reviewed within two working days.',
  UNDER_REVIEW: 'The platform team is checking your documents and bank details.',
  CHANGES_REQUESTED: 'The platform team needs a few changes before you can go live.',
  APPROVED: 'You are approved. The platform team will take you live shortly.',
  SUSPENDED: 'Your listing is paused, so customers cannot book. Contact the platform team.',
  REJECTED: 'Your application was not approved.',
}

export function App() {
  const { user, logout, api, config } = useSession()
  const navigate = useNavigate()
  const me = useAsync(() => (user ? api.provider.me() : Promise.resolve(undefined)), [user?.id])
  const today = useAsync(() => (me.data?.locations[0] ? api.provider.orders({ date: isoDate(), locationId: me.data.locations[0].id }) : Promise.resolve([])), [me.data?.id])
  const waiting = (today.data ?? []).filter((o) => o.actions.includes('ACCEPT')).length
  const reviews = useAsync(() => (user ? api.provider.reviews() : Promise.resolve([])), [user?.id])
  const unreplied = (reviews.data ?? []).filter((r) => !r.reply).length

  const owner = user?.role === 'PROVIDER_OWNER'
  const manager = owner || user?.role === 'PROVIDER_MANAGER'
  const nav: NavGroup[] = useMemo(() => [
    { items: [{ to: '/', label: 'Overview', end: true, icon: 'dashboard' }] },
    { label: 'Operations', items: [
      { to: '/bookings', label: 'Bookings', icon: 'inbox', count: waiting, keywords: 'queue accept start complete' },
      { to: '/calendar', label: 'Schedule', icon: 'calendar', keywords: 'slots bays block walk in calendar' },
      { to: '/closures', label: 'Closures', icon: 'clock', keywords: 'holiday lunch break maintenance' },
    ] },
    { label: 'Business', items: [
      ...(manager ? [{ to: '/menu', label: 'Menu and prices', icon: 'tag' as const }] : []),
      ...(owner ? [{ to: '/bulk', label: 'Bulk price edit', icon: 'layers' as const }] : []),
      { to: '/reviews', label: 'Reviews', icon: 'star', count: unreplied, countTone: 'neutral' as const },
      ...(owner ? [{ to: '/earnings', label: 'Earnings', icon: 'wallet' as const, keywords: 'payout statement money' }] : []),
    ] },
    ...(manager ? [{ label: 'Setup', items: [
      ...(owner ? [{ to: '/staff', label: 'Staff', icon: 'users' as const }] : []),
      { to: '/settings', label: 'Settings', icon: 'settings' as const, keywords: 'hours bays bank pin auto accept' },
    ] }] : []),
  ], [owner, manager, waiting, unreplied])
  const commands: Command[] = useMemo(() => [
    { id: 'walkin', label: 'Add a walk-in', section: 'Actions', icon: 'plus', run: () => navigate('/calendar') },
    { id: 'block', label: 'Block a time slot', section: 'Actions', icon: 'ban', run: () => navigate('/calendar') },
    ...(owner ? [{ id: 'statement', label: 'Download this month\'s statement', section: 'Actions', icon: 'download' as const, run: () => navigate('/earnings') }] : []),
  ], [navigate, owner])

  if (!user) return <Login />
  if (me.loading && !me.data) return <Splash name={config?.tenant.name} />
  if (!me.data) return <div className="login"><Card><EmptyState icon="alert" title="We could not load your business" action={<div className="row"><Button icon="refresh" onClick={me.reload}>Try again</Button><Button variant="ghost" onClick={logout}>Sign out</Button></div>}>{me.error ?? 'Please try again in a moment.'}</EmptyState></Card></div>
  const live = me.data.status === 'LIVE'

  return (
    <AppShell account brand={me.data.name} subtitle={config?.tenant.name ? `on ${config.tenant.name}` : 'Vendor'} user={{ name: user.name, role: ROLE[user.role] ?? user.role, detail: me.data.name }}
      onLogout={logout} nav={nav} commands={commands} env={import.meta.env.DEV ? { label: 'Development', demo: true } : undefined}
      banner={!live && (
        <div className={`banner ${['SUSPENDED', 'REJECTED'].includes(me.data.status) ? 'bad' : 'warn'}`} role="status">
          <Icon name={me.data.status === 'SUSPENDED' ? 'pause' : 'clock'} />
          <span className="grow"><strong>Not live yet</strong> · {me.data.reviewNote ? `Note from the platform team: ${me.data.reviewNote}` : STATUS_TEXT[me.data.status] ?? 'Customers cannot book you yet.'}</span>
          <Badge tone="warn">{me.data.status.replace(/_/g, ' ').toLowerCase()}</Badge>
          {manager && <Link to="/settings" className="small">Check your details</Link>}
        </div>
      )}>
      <Routes>
        <Route path="/" element={<OverviewPage provider={me.data} />} />
        <Route path="/bookings" element={<BookingsPage provider={me.data} onChange={today.reload} />} />
        <Route path="/calendar" element={<CalendarPage provider={me.data} />} />
        <Route path="/closures" element={<ClosuresPage provider={me.data} />} />
        <Route path="/reviews" element={<ReviewsPage onChange={reviews.reload} />} />
        {owner && <Route path="/bulk" element={<BulkMenuPage provider={me.data} />} />}
        {manager && <Route path="/menu" element={<MenuPage provider={me.data} />} />}
        {owner && <Route path="/earnings" element={<EarningsPage />} />}
        {owner && <Route path="/staff" element={<StaffPage />} />}
        {manager && <Route path="/settings" element={<SettingsPage provider={me.data} onChanged={me.reload} owner={owner} />} />}
        <Route path="*" element={<NotFound />} />
      </Routes>
    </AppShell>
  )
}
