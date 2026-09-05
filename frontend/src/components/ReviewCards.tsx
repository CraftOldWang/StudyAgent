import type { ReviewCard } from '../learningTypes'

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
            <small>{card.sourceChunkId ? `来源 chunk #${card.sourceChunkId}` : '无可验证的来源 chunk'}</small>
          </article>
        ))}
      </div>
    </section>
  )
}
