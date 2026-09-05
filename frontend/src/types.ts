export interface Account { id: number; username: string; role: string }
export interface Department { id: number; name: string; joined: boolean }
export interface Sharing { visibility: 'PRIVATE' | 'ALL' | 'DEPARTMENTS'; departmentIds: number[] }
export interface KnowledgeBase { id: number; name: string; ownerId: number; role: 'ADMIN' | 'OWNER' | 'EDITOR' | 'VIEWER' }
export interface DocumentItem { id: number; name: string; status: string; latestTaskStatus: string; latestTaskStage: string | null; errorCode: string | null; indexVersion: number; activeIndexVersion: number | null; sizeBytes: number; updatedAt: string }
export interface Member { userId: number; username: string; role: 'OWNER' | 'EDITOR' | 'VIEWER'; status: string }
export interface Source { id: string; documentId: number; chunkId: number; documentName: string; pageNumber: number | null; paragraphNumber: number; quote: string }
export interface Hit { chunkId: number; documentId: number; documentName: string; pageNumber: number | null; paragraphNumber: number; content: string; score: number }
export interface Conversation { id: number; knowledgeBaseId: number; createdAt: string }
export interface Message { id: number; role: string; status: string; content: string | null; redacted: boolean; citations: Source[]; errorCode?: string; createdAt?: string }
export const roleName = (role: string) => ({ ADMIN: '系统管理员', OWNER: '所有者', EDITOR: '可编辑', VIEWER: '只读' }[role] ?? role)
// 后端 LocalDateTime 使用 UTC，必须补齐时区再转换为浏览器本地时间。
export const dateLabel = (value: string) => new Date(/Z$|[+-]\d{2}:\d{2}$/.test(value) ? value : `${value}Z`).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
