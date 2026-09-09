import type { KnowledgePointStatus, LearningSessionStatus } from './learningTypes'

export function pointStatusLabel(status: KnowledgePointStatus): string {
  return {
    NEW: '待学习',
    EXPLAINING: '讲解中',
    QUIZZING: '测验中',
    FEEDBACK: '练习后答疑',
    CARD_GENERATING: '卡片待确认',
    CARD_CONFIRMING: '正在写入 Anki',
    COMPLETED: '已完成',
  }[status]
}

export function sessionStatusLabel(status: LearningSessionStatus): string {
  return status === 'COMPLETED' ? '已完成' : '学习中'
}
