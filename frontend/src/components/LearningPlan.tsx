import { pointStatusLabel } from '../learningStatus'
import type { KnowledgePoint } from '../learningTypes'
import { priorityLabel } from './PlanningStart'
import { SourceLink } from './SourceDrawer'

interface Props {
  activeKnowledgePointId: string | null
  points: KnowledgePoint[]
}

export function LearningPlan({ activeKnowledgePointId, points }: Props) {
  return (
    <details open className="learning-plan" aria-label="学习计划">
      <summary className="learning-section-heading">
        <div>
          <span className="eyebrow">学习计划</span>
          <h2>知识点路线</h2>
        </div>
        <span>{points.length} 个知识点</span>
      </summary>
      <ol>
        {points.map((point) => (
          <li className={point.id === activeKnowledgePointId ? 'active' : ''} key={point.id}>
            <span className="plan-sequence">{point.sequenceNo}</span>
            <div>
              {point.chapterTitle && <small>{point.chapterTitle}</small>}
              <strong>{point.topic}</strong>
              {point.priority && <span className={`priority priority-${point.priority.toLowerCase()}`}>{priorityLabel(point.priority)}</span>}
              <small>{point.subtopics.join(' · ') || '无子主题'} · 约 {point.estimatedMinutes} 分钟</small>
              <div className="source-links">{point.sourceChunkIds?.map((id, i) => <SourceLink key={id} chunkId={id} label={`资料 ${i + 1}`} />)}</div>
              {point.errorMessage && <span className="document-error">{point.errorMessage}</span>}
            </div>
            <span className={`point-status point-${point.status.toLowerCase()}`}>
              {pointStatusLabel(point.status)}
            </span>
          </li>
        ))}
      </ol>
    </details>
  )
}
