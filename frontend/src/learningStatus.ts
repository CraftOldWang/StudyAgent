import type { KnowledgePointStatus, LearningSessionStatus } from './learningTypes'

export function pointStatusLabel(status: KnowledgePointStatus): string {
  return {
    NEW: '待学习',
    EXPLAINING: '讲解中',
    QUIZZING: '测验中',
    CARD_GENERATING: '生成卡片',
    COMPLETED: '已完成',
  }[status]
}

export function sessionStatusLabel(status: LearningSessionStatus): string {
  return status === 'COMPLETED' ? '已完成' : '学习中'
}
