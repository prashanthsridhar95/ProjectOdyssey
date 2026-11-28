import { useMemo, useState } from 'react'
import { dateOnly, messageOf, relative, useAsync, useSession } from '@odyssey/api'
import { Badge, Button, Card, Chips, EmptyState, ErrorNote, KpiStrip, PageHeader, Skeleton, useToast } from '@odyssey/ui'

/** What customers said, with a public reply under each review. */
export function ReviewsPage({ onChange }: { onChange?: () => void }) {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.provider.reviews(), [])
  const [open, setOpen] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [show, setShow] = useState<'all' | 'unreplied' | 'low'>('all')
  const avg = data?.length ? data.reduce((s, r) => s + r.rating, 0) / data.length : 0
  const rows = useMemo(() => (data ?? []).filter((r) => show === 'all' || (show === 'unreplied' ? !r.reply : r.rating <= 3)), [data, show])

  async function send(id: string) {
    setBusy(true)
    try { await api.provider.reply(id, text.trim()); toast('Reply posted'); setOpen(null); setText(''); reload(); onChange?.() } catch (e) { toast(messageOf(e), true) } finally { setBusy(false) }
  }
  return (
    <>
      <PageHeader title="Reviews" subtitle="Your replies are public and show under the customer's review. A kind, specific reply wins customers back." />
      <ErrorNote message={error} onRetry={reload} />
      {data && data.length > 0 && <><KpiStrip items={[
        { label: 'Average rating', icon: 'star', value: avg.toFixed(1), foot: `${data.length} reviews` },
        { label: 'Waiting for a reply', icon: 'messageSquare', value: data.filter((r) => !r.reply).length },
        { label: '3 stars or fewer', icon: 'alert', value: data.filter((r) => r.rating <= 3).length },
      ]} /><div className="spacer" /></>}
      <Chips label="Show" value={show} onChange={setShow} options={[{ value: 'all', label: 'All', count: data?.length }, { value: 'unreplied', label: 'Not replied', count: data?.filter((r) => !r.reply).length }, { value: 'low', label: '3 stars or fewer', count: data?.filter((r) => r.rating <= 3).length }]} />
      <div className="spacer" style={{ height: 14 }} />
      {loading && !data ? <Card><div className="stack">{[1, 2, 3].map((i) => <Skeleton key={i} height={40} />)}</div></Card> : !rows.length ? <Card><EmptyState icon="star" title={show === 'all' ? 'No reviews yet' : 'Nothing here'}>{show === 'all' ? 'Customers can rate you after each completed booking.' : 'Every review in this group has a reply.'}</EmptyState></Card> : (
        <div className="stack">
          {rows.map((r) => (
            <Card key={r.id}>
              <div className="row spread top">
                <div><strong>{r.customerName}</strong> <span className="muted small" title={dateOnly(r.createdAt)}>· {relative(r.createdAt)}</span></div>
                <span className="stars" aria-label={`${r.rating} out of 5`}>{'★'.repeat(r.rating)}<span style={{ color: 'var(--c-border-strong)' }}>{'★'.repeat(5 - r.rating)}</span></span>
              </div>
              {r.comment ? <p style={{ marginTop: 8 }}>{r.comment}</p> : <p className="faint small" style={{ marginTop: 8 }}>No comment, rating only.</p>}
              {r.tags.length > 0 && <div className="row wrap tight" style={{ marginTop: 10 }}>{r.tags.map((t) => <Badge key={t}>{t}</Badge>)}</div>}
              {r.reply ? <div style={{ marginTop: 14, padding: '10px 12px', background: 'var(--c-surface-2)', borderLeft: '2px solid var(--c-primary)' }}><div className="muted small">Your reply · {r.replyAt ? relative(r.replyAt) : ''}</div><p style={{ marginTop: 4 }}>{r.reply}</p></div>
                : open === r.id ? (
                  <div className="stack tight" style={{ marginTop: 14 }}>
                    <textarea rows={3} value={text} onChange={(e) => setText(e.target.value)} maxLength={500} autoFocus aria-label="Your reply" placeholder={r.rating <= 3 ? 'Say sorry, and what you will do about it' : 'Thank them by name'} />
                    <div className="row spread"><span className="faint small num">{text.length}/500</span><div className="row tight"><Button small variant="ghost" onClick={() => setOpen(null)}>Cancel</Button><Button variant="primary" small busy={busy} disabled={!text.trim()} onClick={() => send(r.id)}>Post reply</Button></div></div>
                  </div>
                ) : <div style={{ marginTop: 12 }}><Button small icon="messageSquare" onClick={() => { setOpen(r.id); setText('') }}>Reply</Button></div>}
            </Card>
          ))}
        </div>
      )}
    </>
  )
}
