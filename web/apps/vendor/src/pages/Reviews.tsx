import { useState } from 'react'
import { dateOnly, messageOf, useAsync, useSession } from '@odyssey/api'
import { Badge, Button, Card, Empty, ErrorNote, PageHeader, Spinner, useToast } from '@odyssey/ui'

/** What customers said, with a public reply under each review. */
export function ReviewsPage() {
  const { api } = useSession()
  const toast = useToast()
  const { data, error, loading, reload } = useAsync(() => api.provider.reviews(), [])
  const [open, setOpen] = useState<string | null>(null)
  const [text, setText] = useState('')
  async function send(id: string) {
    try { await api.provider.reply(id, text.trim()); toast('Reply posted'); setOpen(null); setText(''); reload() } catch (e) { toast(messageOf(e), true) }
  }
  return (
    <>
      <PageHeader title="Reviews" subtitle="Replies are public, under the customer's review" />
      <ErrorNote message={error} />
      {loading ? <Spinner /> : !data?.length ? <Card><Empty>No reviews yet.</Empty></Card> : (
        <div className="stack">
          {data.map((r) => (
            <Card key={r.id}>
              <div className="row spread"><div><strong>{r.customerName}</strong> <span className="muted small">· {dateOnly(r.createdAt)}</span></div><Badge tone={r.rating >= 4 ? 'good' : r.rating === 3 ? 'warn' : 'bad'}>{'★'.repeat(r.rating)}{'☆'.repeat(5 - r.rating)}</Badge></div>
              {r.comment && <p style={{ marginTop: 8 }}>{r.comment}</p>}
              {r.tags.length > 0 && <div className="row wrap" style={{ marginTop: 8 }}>{r.tags.map((t) => <Badge key={t}>{t}</Badge>)}</div>}
              {r.reply ? <div style={{ marginTop: 12, paddingLeft: 12, borderLeft: '2px solid var(--c-primary)' }}><div className="muted small">Your reply · {r.replyAt ? dateOnly(r.replyAt) : ''}</div><p>{r.reply}</p></div>
                : open === r.id ? (
                  <div className="stack tight" style={{ marginTop: 12 }}>
                    <textarea rows={3} value={text} onChange={(e) => setText(e.target.value)} placeholder="Thank them, or say what you will fix" />
                    <div className="row"><Button variant="primary" small disabled={!text.trim()} onClick={() => send(r.id)}>Post reply</Button><Button small onClick={() => setOpen(null)}>Cancel</Button></div>
                  </div>
                ) : <div style={{ marginTop: 12 }}><Button small onClick={() => { setOpen(r.id); setText('') }}>Reply</Button></div>}
            </Card>
          ))}
        </div>
      )}
    </>
  )
}
