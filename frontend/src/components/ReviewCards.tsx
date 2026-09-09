import type { ReviewCard } from '../learningTypes'
import { SourceLink } from './SourceDrawer'
import { AnkiCardExport } from './AnkiCardExport'

export function ReviewCards({ cards }: { cards: ReviewCard[] }) {
  if (cards.length === 0) return null
  return (
    <section className="cards-block">
      <div className="learning-section-heading">
        <div><span className="eyebrow">复习卡片</span><h2>三张可复习卡片</h2></div>
      </div>
      <p className="muted">打开本机 Anki 与 AnkiConnect 后，可逐张导出；重复导出会复用已有笔记。</p>
      <div className="review-cards">
        {cards.map((card) => (
          <article key={card.id}>
            <strong>{card.front}</strong>
            <p>{card.back}</p>
            <SourceLink chunkId={card.sourceChunkId} />
            <AnkiCardExport key={card.id} cardId={card.id} />
          </article>
        ))}
      </div>
    </section>
  )
}
