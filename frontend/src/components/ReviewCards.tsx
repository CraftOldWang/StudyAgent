import type { ReviewCard } from '../learningTypes'
import { SourceLink } from './SourceDrawer'

export function ReviewCards({ cards }: { cards: ReviewCard[] }) {
  if (cards.length === 0) return null
  return (
    <section className="cards-block">
      <div className="learning-section-heading">
        <div><span className="eyebrow">复习卡片</span><h2>三张可复习卡片</h2></div>
      </div>
      <div className="review-cards">
        {cards.map((card) => (
          <article key={card.id}>
            <strong>{card.front}</strong>
            <p>{card.back}</p>
            <SourceLink chunkId={card.sourceChunkId} />
          </article>
        ))}
      </div>
    </section>
  )
}
