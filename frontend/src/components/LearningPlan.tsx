import { pointStatusLabel } from '../learningStatus'
import type { KnowledgePoint } from '../learningTypes'

interface Props {
  activeKnowledgePointId: string | null
  points: KnowledgePoint[]
}

export function LearningPlan({ activeKnowledgePointId, points }: Props) {
  return (
    <section className="learning-plan" aria-label="学习计划">
      <div className="learning-section-heading">
        <div>
          <span className="eyebrow">学习计划</span>
          <h2>知识点路线</h2>
        </div>
        <span>{points.length} 个知识点</span>
      </div>
      <ol>
        {points.map((point) => (
          <li className={point.id === activeKnowledgePointId ? 'active' : ''} key={point.id}>
            <span className="plan-sequence">{point.sequenceNo}</span>
            <div>
              <strong>{point.topic}</strong>
              <small>{point.subtopics.join(' · ') || '无子主题'} · 约 {point.estimatedMinutes} 分钟</small>
              {point.errorMessage && <span className="document-error">{point.errorMessage}</span>}
            </div>
            <span className={`point-status point-${point.status.toLowerCase()}`}>
              {pointStatusLabel(point.status)}
            </span>
          </li>
        ))}
      </ol>
    </section>
  )
}
