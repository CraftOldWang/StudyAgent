import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export function MessageContent({ text }: { text: string }) {
  return <div className="markdown-content"><Markdown remarkPlugins={[remarkGfm]} skipHtml components={{
    img: ({ alt }) => <span className="muted">[图片：{alt || '资料插图'}]</span>,
    a: ({ href, children }) => <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>,
    table: ({ children }) => <div className="markdown-table"><table>{children}</table></div>,
  }}>{text}</Markdown></div>
}
