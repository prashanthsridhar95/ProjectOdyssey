import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { Avatar, ErrorBoundary, IconButton, TitleSuffix, useOnline } from './components'
import { Icon, type IconName } from './icons'

// ---- theme ----

export type ThemePref = 'light' | 'dark' | 'system'
const ThemeCtx = createContext<{ pref: ThemePref; set: (p: ThemePref) => void }>({ pref: 'system', set: () => undefined })
export const useTheme = () => useContext(ThemeCtx)
const THEME_KEY = 'odyssey.theme'

function applyTheme(pref: ThemePref) {
  const dark = pref === 'dark' || (pref === 'system' && window.matchMedia?.('(prefers-color-scheme: dark)').matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
}

/** Light, dark or follow the system; remembered on this browser. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [pref, setPref] = useState<ThemePref>(() => {
    try { return (localStorage.getItem(THEME_KEY) as ThemePref) || 'system' } catch { return 'system' }
  })
  useEffect(() => {
    applyTheme(pref)
    if (pref !== 'system') return
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)')
    const h = () => applyTheme('system')
    mq?.addEventListener('change', h)
    return () => mq?.removeEventListener('change', h)
  }, [pref])
  const set = useCallback((p: ThemePref) => { setPref(p); try { localStorage.setItem(THEME_KEY, p) } catch { /* private mode */ } }, [])
  return <ThemeCtx.Provider value={{ pref, set }}>{children}</ThemeCtx.Provider>
}

// ---- navigation ----

export interface NavItem { to: string; label: string; end?: boolean; icon?: IconName; count?: number; countTone?: 'bad' | 'neutral'; keywords?: string }
export interface NavGroup { label?: string; items: NavItem[] }
export interface Command { id: string; label: string; section: string; icon?: IconName; keywords?: string; run: () => void }

interface ShellUser { name: string; role: string; detail?: string }

function flatten(nav: (NavItem | NavGroup)[]): NavGroup[] {
  const groups: NavGroup[] = []
  for (const n of nav) {
    if ('items' in n) groups.push(n)
    else { const last = groups[groups.length - 1]; if (last && !last.label) last.items.push(n); else groups.push({ items: [n] }) }
  }
  return groups
}

export function AppShell({ brand, subtitle, nav, user, onLogout, children, env, commands = [], banner }: {
  brand: string
  subtitle: string
  nav: (NavItem | NavGroup)[]
  user: ShellUser | string
  onLogout: () => void
  children: ReactNode
  /** Shown as a pill in the top bar, e.g. "Demo" when the identity provider is the dev stand-in. */
  env?: { label: string; demo?: boolean }
  commands?: Command[]
  banner?: ReactNode
}) {
  const groups = useMemo(() => flatten(nav), [nav])
  const items = groups.flatMap((g) => g.items.map((i) => ({ ...i, group: g.label })))
  const loc = useLocation()
  const [navOpen, setNavOpen] = useState(false)
  const [palette, setPalette] = useState(false)
  const online = useOnline()
  const who: ShellUser = typeof user === 'string' ? { name: user.split(' · ')[0] ?? user, role: user.split(' · ')[1] ?? '' } : user

  useEffect(() => { setNavOpen(false) }, [loc.pathname])
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalette((p) => !p) }
      else if (e.key === '/' && !palette && !/INPUT|TEXTAREA|SELECT/.test((e.target as HTMLElement).tagName) && !(e.target as HTMLElement).isContentEditable) { e.preventDefault(); setPalette(true) }
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [palette])

  const current = [...items].sort((a, b) => b.to.length - a.to.length).find((i) => (i.end || i.to === '/' ? loc.pathname === i.to : loc.pathname === i.to || loc.pathname.startsWith(i.to + '/')))
  const deeper = current && loc.pathname !== current.to

  return (
    <TitleSuffix.Provider value={`${brand} ${subtitle}`}>
      <div className={`shell ${navOpen ? 'nav-open' : ''}`}>
        <a href="#main" className="sr-only">Skip to content</a>
        <aside className="sidebar" aria-label="Main">
          <Link to="/" className="brand" style={{ textDecoration: 'none', color: 'inherit' }}>
            <span className="mark"><Icon name="droplet" size={16} /></span>
            <span className="word">{brand}<small>{subtitle}</small></span>
          </Link>
          <nav className="nav">
            {groups.map((g, gi) => (
              <div className="group" key={gi}>
                {g.label && <div className="group-label">{g.label}</div>}
                {g.items.map((n) => (
                  <NavLink key={n.to} to={n.to} end={n.end}>
                    {n.icon && <Icon name={n.icon} size={17} />}<span>{n.label}</span>
                    {!!n.count && <span className={`count ${n.countTone === 'neutral' ? 'neutral' : ''}`} aria-label={`${n.count} waiting`}>{n.count > 99 ? '99+' : n.count}</span>}
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
          <div className="foot">
            <button type="button" className="btn ghost small block" style={{ justifyContent: 'flex-start' }} onClick={() => setPalette(true)}>
              <Icon name="command" size={14} />Shortcuts<span className="grow" /><kbd>⌘K</kbd>
            </button>
          </div>
        </aside>
        <div className="scrim" onClick={() => setNavOpen(false)} />
        <div className="workspace">
          <header className="topbar">
            <span className="mobile-only"><IconButton icon="menu" label="Open menu" onClick={() => setNavOpen(true)} /></span>
            <div className="crumbs grow">
              {current?.group && <span className="desktop-only">{current.group}</span>}
              {current?.group && <Icon className="icon desktop-only" name="chevronRight" size={12} />}
              {deeper ? <Link to={current!.to}>{current!.label}</Link> : <span className="here">{current?.label ?? brand}</span>}
              {deeper && <><Icon name="chevronRight" size={12} /><span className="here">Details</span></>}
            </div>
            <button type="button" className="searchbtn" onClick={() => setPalette(true)} aria-label="Search and go to">
              <Icon name="search" size={15} /><span className="label">Jump to…</span><kbd>⌘K</kbd>
            </button>
            {env && <span className={`env desktop-only ${env.demo ? 'demo' : ''}`} title={env.demo ? 'Sign-in uses the development identity provider' : undefined}><span className="dot" />{env.label}</span>}
            <UserMenu user={who} onLogout={onLogout} />
          </header>
          {!online && <div className="banner warn" role="status"><Icon name="wifiOff" />You are offline. Changes will fail until the connection is back.</div>}
          {banner}
          <main className="main" id="main" tabIndex={-1}>
            <ErrorBoundary resetKey={loc.pathname}>{children}</ErrorBoundary>
          </main>
        </div>
        {palette && <CommandPalette onClose={() => setPalette(false)} items={items} commands={commands} onLogout={onLogout} />}
      </div>
    </TitleSuffix.Provider>
  )
}

function UserMenu({ user, onLogout }: { user: ShellUser; onLogout: () => void }) {
  const [open, setOpen] = useState(false)
  const { pref, set } = useTheme()
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const h = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', h); document.addEventListener('keydown', k)
    return () => { document.removeEventListener('mousedown', h); document.removeEventListener('keydown', k) }
  }, [open])
  const themes: [ThemePref, string, IconName][] = [['light', 'Light', 'sun'], ['dark', 'Dark', 'moon'], ['system', 'Match system', 'monitor']]
  return (
    <div className="usermenu" ref={ref}>
      <button type="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Avatar name={user.name} />
        <span className="who stack xtight" style={{ gap: 0 }}><span className="small strong">{user.name}</span><span className="tiny muted" style={{ textTransform: 'capitalize' }}>{user.role}</span></span>
        <Icon name="chevronDown" size={14} />
      </button>
      {open && (
        <div className="menu" role="menu">
          <div className="head"><div className="strong">{user.name}</div><div className="muted small" style={{ textTransform: 'capitalize' }}>{user.role}{user.detail ? ` · ${user.detail}` : ''}</div></div>
          <div className="label">Appearance</div>
          {themes.map(([k, l, ic]) => (
            <button key={k} type="button" role="menuitemradio" aria-checked={pref === k} className={`item ${pref === k ? 'on' : ''}`} onClick={() => set(k)}>
              <Icon name={ic} />{l}{pref === k && <Icon className="icon check" name="check" />}
            </button>
          ))}
          <div className="divider" style={{ margin: '6px 0' }} />
          <button type="button" role="menuitem" className="item" onClick={onLogout}><Icon name="logout" />Sign out</button>
        </div>
      )}
    </div>
  )
}

function CommandPalette({ onClose, items, commands, onLogout }: { onClose: () => void; items: (NavItem & { group?: string })[]; commands: Command[]; onLogout: () => void }) {
  const navigate = useNavigate()
  const { set } = useTheme()
  const [q, setQ] = useState('')
  const [at, setAt] = useState(0)
  const all: Command[] = useMemo(() => [
    ...items.map((i) => ({ id: `go:${i.to}`, label: i.label, section: i.group ?? 'Go to', icon: i.icon, keywords: i.keywords, run: () => navigate(i.to) })),
    ...commands,
    { id: 'theme:light', label: 'Use light theme', section: 'Appearance', icon: 'sun' as IconName, run: () => set('light') },
    { id: 'theme:dark', label: 'Use dark theme', section: 'Appearance', icon: 'moon' as IconName, run: () => set('dark') },
    { id: 'theme:system', label: 'Match system theme', section: 'Appearance', icon: 'monitor' as IconName, run: () => set('system') },
    { id: 'signout', label: 'Sign out', section: 'Account', icon: 'logout' as IconName, run: onLogout },
  ], [items, commands, navigate, set, onLogout])
  const needle = q.trim().toLowerCase()
  const list = needle ? all.filter((c) => `${c.label} ${c.section} ${c.keywords ?? ''}`.toLowerCase().includes(needle)) : all
  const run = (c: Command | undefined) => { if (!c) return; onClose(); c.run() }
  return (
    <div className="palette" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="box" role="dialog" aria-modal="true" aria-label="Jump to">
        <div className="q">
          <Icon name="search" size={17} />
          <input autoFocus placeholder="Jump to a page or run a command…" value={q} aria-label="Search commands"
            onChange={(e) => { setQ(e.target.value); setAt(0) }}
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown') { e.preventDefault(); setAt((a) => Math.min(list.length - 1, a + 1)) }
              else if (e.key === 'ArrowUp') { e.preventDefault(); setAt((a) => Math.max(0, a - 1)) }
              else if (e.key === 'Enter') { e.preventDefault(); run(list[at]) }
              else if (e.key === 'Escape') onClose()
            }} />
          <kbd>Esc</kbd>
        </div>
        <ul role="listbox">
          {list.length === 0 && <li className="muted" style={{ cursor: 'default' }}>Nothing matches "{q}"</li>}
          {list.map((c, i) => (
            <li key={c.id} role="option" aria-selected={i === at} className={i === at ? 'on' : ''} onMouseEnter={() => setAt(i)} onClick={() => run(c)}>
              <Icon name={c.icon ?? 'chevronRight'} />{c.label}<span className="sec">{c.section}</span>
            </li>
          ))}
        </ul>
        <div className="foot"><span><kbd>↑</kbd> <kbd>↓</kbd> to move</span><span><kbd>Enter</kbd> to open</span><span><kbd>/</kbd> or <kbd>⌘K</kbd> to open this anywhere</span></div>
      </div>
    </div>
  )
}
