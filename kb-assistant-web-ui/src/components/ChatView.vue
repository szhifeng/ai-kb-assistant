<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

type Citation = { source: string; snippet: string; score: number | null }
type DocumentDetail = {
  source: string
  chunkCount: number
  size: number | null
  uploaderEmail: string | null
  status: string | null
  message: string | null
  uploadedAt: string | null
}
type IngestResult = { source: string; chunkCount: number }
type ChatAnswer = { answer: string; reasoning?: string; citations: Citation[]; conversationId: string }
type AnswerMode = 'stream' | 'sync'
type UploadAudit = {
  id: string
  uploaderEmail: string | null
  source: string
  size: number
  status: 'success' | 'failed'
  message: string
  chunkCount: number | null
  createdAt: string
}
type ChatMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  citations: Citation[]
  createdAt: string
  pending?: boolean
  mode?: AnswerMode
  webSearchEnabled?: boolean
  error?: string | null
}
type UserProfile = { email: string; displayName: string; signedInAt: string }
type ConversationSession = {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

const SETTINGS_KEY = 'kb-assistant-ui-settings'
const LAST_EMAIL_KEY = 'kb-assistant-last-email'
const QUICK_QUESTIONS = [
  '这批文档的核心结论是什么？',
  '请按条目总结关键事实和风险点',
  '有哪些内容可以作为决策依据？',
]

const loginEmail = ref(localStorage.getItem(LAST_EMAIL_KEY) ?? '')
const loginError = ref('')
const user = ref<UserProfile | null>(null)
const question = ref('')
const conversationId = ref('')
const activeSessionId = ref('')
const sessions = ref<ConversationSession[]>([])
const model = ref('deepseek')
const answerMode = ref<AnswerMode>('stream')
const webSearchEnabled = ref(false)
const documents = ref<DocumentDetail[]>([])
const uploadHistory = ref<UploadAudit[]>([])
const messages = ref<ChatMessage[]>([])
const chatOpen = ref(false)
const profileOpen = ref(false)
const listLoading = ref(false)
const uploading = ref(false)
const streaming = ref(false)
const deletingSource = ref('')
const errorMessage = ref('')
const threadRef = ref<HTMLElement | null>(null)
let activeStream: EventSource | null = null
let scrollFrame = 0

const currentEmail = computed(() => user.value?.email ?? '')
const isSignedIn = computed(() => Boolean(user.value))
const identityLabel = computed(() => user.value?.displayName || '未登录')
const totalChunks = computed(() => documents.value.reduce((sum, item) => sum + item.chunkCount, 0))
const successUploads = computed(() => uploadHistory.value.filter((item) => item.status === 'success').length)
const failedUploads = computed(() => uploadHistory.value.filter((item) => item.status === 'failed').length)
const latestAssistantMessage = computed(() => [...messages.value].reverse().find((item) => item.role === 'assistant'))
const healthText = computed(() => streaming.value ? '生成中' : uploading.value ? '解析中' : isSignedIn.value ? '就绪' : '待登录')

watch([model, answerMode, webSearchEnabled], saveUiSettings)
watch(() => messages.value.length, queueThreadScroll)

onMounted(async () => {
  loadUiSettings()
  await fetchDocuments()
  if (loginEmail.value) await restoreUser(loginEmail.value)
})

onBeforeUnmount(() => {
  closeActiveStream()
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
})

function loadUiSettings() {
  try {
    const settings = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}')
    model.value = settings.model || 'deepseek'
    answerMode.value = settings.answerMode || 'stream'
    webSearchEnabled.value = Boolean(settings.webSearchEnabled)
  } catch {
    localStorage.removeItem(SETTINGS_KEY)
  }
}

function saveUiSettings() {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify({
    model: model.value,
    answerMode: answerMode.value,
    webSearchEnabled: webSearchEnabled.value,
  }))
}

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) throw new Error(await readError(response))
  if (response.status === 204) return undefined as T
  const text = await response.text()
  return text ? JSON.parse(text) as T : undefined as T
}

async function readError(response: Response) {
  const text = await response.text()
  if (!text) return `${response.status} ${response.statusText}`
  try {
    const payload = JSON.parse(text)
    return `${payload.status || response.status} ${payload.message || payload.error || response.statusText}`
  } catch {
    return text
  }
}

async function restoreUser(email: string) {
  try {
    user.value = await api<UserProfile>(`/api/auth/me?email=${encodeURIComponent(email)}`)
    await hydrateUserWorkspace()
  } catch {
    localStorage.removeItem(LAST_EMAIL_KEY)
  }
}

async function signIn() {
  const email = loginEmail.value.trim().toLowerCase()
  loginError.value = ''
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    loginError.value = '请输入有效邮箱'
    return
  }
  try {
    user.value = await api<UserProfile>('/api/auth/email/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email }),
    })
    localStorage.setItem(LAST_EMAIL_KEY, email)
    await hydrateUserWorkspace()
    profileOpen.value = false
  } catch (error) {
    loginError.value = error instanceof Error ? error.message : '登录失败'
  }
}

function signOut() {
  closeActiveStream()
  user.value = null
  sessions.value = []
  messages.value = []
  uploadHistory.value = []
  activeSessionId.value = ''
  conversationId.value = ''
  chatOpen.value = false
  profileOpen.value = false
  localStorage.removeItem(LAST_EMAIL_KEY)
}

async function hydrateUserWorkspace() {
  await Promise.all([fetchSessions(), fetchUploads()])
  if (!sessions.value.length) await createSession()
  else await switchSession(sessions.value[0].id)
}

async function fetchDocuments() {
  listLoading.value = true
  try {
    documents.value = await api<DocumentDetail[]>('/api/documents/details')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '读取文档失败'
  } finally {
    listLoading.value = false
  }
}

async function fetchUploads() {
  if (!user.value) return
  uploadHistory.value = await api<UploadAudit[]>(`/api/users/${encodeURIComponent(user.value.email)}/uploads`)
}

async function fetchSessions() {
  if (!user.value) return
  sessions.value = await api<ConversationSession[]>(`/api/users/${encodeURIComponent(user.value.email)}/sessions`)
}

async function createSession() {
  if (!user.value) return
  const session = await api<ConversationSession>(`/api/users/${encodeURIComponent(user.value.email)}/sessions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title: '新会话' }),
  })
  await fetchSessions()
  await switchSession(session.id)
  chatOpen.value = true
}

async function switchSession(sessionId: string) {
  if (!user.value) return
  closeActiveStream()
  activeSessionId.value = sessionId
  conversationId.value = sessionId
  messages.value = await api<ChatMessage[]>(
    `/api/users/${encodeURIComponent(user.value.email)}/sessions/${encodeURIComponent(sessionId)}/messages`,
  )
  chatOpen.value = true
  await scrollThreadToEnd()
}

async function deleteSession(sessionId: string) {
  if (!user.value || !window.confirm('确认删除该会话及其消息？')) return
  await api<void>(`/api/users/${encodeURIComponent(user.value.email)}/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
  await fetchSessions()
  messages.value = []
  if (sessions.value.length) await switchSession(sessions.value[0].id)
}

async function persistMessage(message: ChatMessage) {
  if (!user.value || !activeSessionId.value) return
  await api<void>(
    `/api/users/${encodeURIComponent(user.value.email)}/sessions/${encodeURIComponent(activeSessionId.value)}/messages`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(message) },
  )
}

async function uploadDocuments(event: Event) {
  if (!user.value) return
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  uploading.value = true
  try {
    for (const file of files) {
      const form = new FormData()
      form.append('file', file)
      form.append('uploaderEmail', user.value.email)
      await api<IngestResult>('/api/documents', { method: 'POST', body: form })
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '上传失败'
  } finally {
    uploading.value = false
    input.value = ''
    await Promise.all([fetchDocuments(), fetchUploads()])
  }
}

async function deleteDocument(source: string) {
  if (!window.confirm(`确认删除 ${source} 的全部向量分片？`)) return
  deletingSource.value = source
  try {
    await api<void>(`/api/documents/${encodeURIComponent(source)}`, { method: 'DELETE' })
    await fetchDocuments()
  } finally {
    deletingSource.value = ''
  }
}

function makeId(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`
}

async function appendUserAndAssistant(text: string, mode: AnswerMode) {
  question.value = ''
  const userMessage: ChatMessage = {
    id: makeId('message'), role: 'user', content: text, citations: [], createdAt: new Date().toISOString(), mode,
    webSearchEnabled: webSearchEnabled.value,
  }
  const assistantMessage: ChatMessage = {
    id: makeId('message'), role: 'assistant', content: '', reasoning: '', citations: [], createdAt: new Date().toISOString(), mode,
    webSearchEnabled: webSearchEnabled.value, pending: true,
  }
  messages.value.push(userMessage, assistantMessage)
  await persistMessage(userMessage)
  return messages.value[messages.value.length - 1]
}

async function ask() {
  const text = question.value.trim()
  if (!text || streaming.value || !user.value || !activeSessionId.value) return
  if (answerMode.value === 'sync') await askSync(text)
  else await askStream(text)
}

async function askStream(text: string) {
  streaming.value = true
  const assistant = await appendUserAndAssistant(text, 'stream')
  const params = new URLSearchParams({ question: text, conversationId: conversationId.value, model: model.value, webSearchEnabled: String(webSearchEnabled.value) })
  activeStream = new EventSource(`/api/chat/stream?${params}`)
  activeStream.addEventListener('reasoning', event => { assistant.reasoning = (assistant.reasoning || '') + (event as MessageEvent).data; queueThreadScroll() })
  activeStream.addEventListener('token', event => { assistant.content += (event as MessageEvent).data; queueThreadScroll() })
  activeStream.addEventListener('citations', async event => {
    try { assistant.citations = JSON.parse((event as MessageEvent).data) }
    catch { assistant.error = '引用数据解析失败' }
    assistant.pending = false
    streaming.value = false
    closeActiveStream()
    await persistMessage(assistant)
    await fetchSessions()
  })
  activeStream.onerror = async () => {
    assistant.pending = false
    assistant.error = '流式连接异常'
    streaming.value = false
    closeActiveStream()
    await persistMessage(assistant)
  }
}

async function askSync(text: string) {
  streaming.value = true
  const assistant = await appendUserAndAssistant(text, 'sync')
  try {
    const result = await api<ChatAnswer>('/api/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: text, conversationId: conversationId.value, model: model.value, webSearchEnabled: webSearchEnabled.value }),
    })
    assistant.reasoning = result.reasoning
    assistant.content = result.answer
    assistant.citations = result.citations
  } catch (error) {
    assistant.error = error instanceof Error ? error.message : '问答失败'
  } finally {
    assistant.pending = false
    streaming.value = false
    await persistMessage(assistant)
    await fetchSessions()
  }
}

function stopGeneration() {
  closeActiveStream()
  streaming.value = false
  const message = latestAssistantMessage.value
  if (message?.pending) { message.pending = false; message.error = '用户已停止生成' }
}

function closeActiveStream() { activeStream?.close(); activeStream = null }
function openChat() { chatOpen.value = true }
function closeChat() { chatOpen.value = false }
function toggleProfilePanel() { profileOpen.value = !profileOpen.value }
function useQuickQuestion(text: string) { question.value = text; chatOpen.value = true }
function formatTime(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '未知时间' }
function formatBytes(bytes: number | null) { if (bytes == null) return '未知大小'; if (bytes < 1024) return `${bytes} B`; if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`; return `${(bytes / 1048576).toFixed(1)} MB` }
function formatScore(score: number | null) { return score == null ? '无分数' : score.toFixed(3) }
function sourceChunkCount(source: string) { return documents.value.find(item => item.source === source)?.chunkCount ?? null }
function sourceExists(source: string) { return documents.value.some(item => item.source === source) }
function assistantPlaceholder(message: ChatMessage) {
  if (message.content) return message.content
  if (!message.pending) return ''
  return message.reasoning ? '正在整理回答...' : '生成中...'
}

function renderMarkdown(value: string | undefined | null) {
  const markdown = (value ?? '').replace(/\r\n/g, '\n').trim()
  if (!markdown) return ''

  const lines = markdown.split('\n')
  const html: string[] = []
  const paragraph: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let listItems: string[] = []
  let inFence = false
  let fenceLanguage = ''
  let fenceLines: string[] = []

  const flushParagraph = () => {
    if (!paragraph.length) return
    html.push(`<p>${renderInlineMarkdown(paragraph.join('\n'))}</p>`)
    paragraph.length = 0
  }

  const flushList = () => {
    if (!listType) return
    html.push(`<${listType}>${listItems.map(item => `<li>${renderInlineMarkdown(item)}</li>`).join('')}</${listType}>`)
    listType = null
    listItems = []
  }

  const flushFence = () => {
    const languageClass = fenceLanguage ? ` class="language-${escapeAttribute(fenceLanguage)}"` : ''
    html.push(`<pre><code${languageClass}>${escapeHtml(fenceLines.join('\n'))}</code></pre>`)
    inFence = false
    fenceLanguage = ''
    fenceLines = []
  }

  const parseTable = (startIndex: number) => {
    const rows: string[][] = []
    let index = startIndex
    while (index < lines.length && isTableLine(lines[index])) {
      const cells = splitTableCells(lines[index])
      if (!isTableDivider(cells)) rows.push(cells)
      index += 1
    }

    if (rows.length < 2) return null

    const [head, ...body] = rows
    html.push(
      `<div class="table-scroll"><table><thead><tr>${head.map(cell => `<th>${renderInlineMarkdown(cell)}</th>`).join('')}</tr></thead>` +
      `<tbody>${body.map(row => `<tr>${head.map((_, cellIndex) => `<td>${renderInlineMarkdown(row[cellIndex] ?? '')}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`,
    )
    return index
  }

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    const fenceMatch = line.match(/^```([\w-]+)?\s*$/)
    if (fenceMatch) {
      if (inFence) {
        flushFence()
      } else {
        flushParagraph()
        flushList()
        inFence = true
        fenceLanguage = fenceMatch[1] ?? ''
        fenceLines = []
      }
      continue
    }

    if (inFence) {
      fenceLines.push(line)
      continue
    }

    if (!line.trim()) {
      flushParagraph()
      flushList()
      continue
    }

    const tableEndIndex = isTableLine(line) ? parseTable(index) : null
    if (tableEndIndex != null) {
      flushParagraph()
      flushList()
      index = tableEndIndex - 1
      continue
    }

    const headingMatch = line.match(/^(#{1,3})\s*(.+)$/)
    if (headingMatch) {
      flushParagraph()
      flushList()
      const level = headingMatch[1].length + 2
      html.push(`<h${level}>${renderInlineMarkdown(headingMatch[2])}</h${level}>`)
      continue
    }

    if (/^[-*_]\s*[-*_]\s*[-*_][\s-*_]*$/.test(line.trim())) {
      flushParagraph()
      flushList()
      html.push('<hr>')
      continue
    }

    const quoteMatch = line.match(/^>\s?(.*)$/)
    if (quoteMatch) {
      flushParagraph()
      flushList()
      html.push(`<blockquote>${renderInlineMarkdown(quoteMatch[1])}</blockquote>`)
      continue
    }

    const unorderedMatch = line.match(/^\s*[-*+]\s+(.+)$/)
    const orderedMatch = line.match(/^\s*\d+\.\s+(.+)$/)
    if (unorderedMatch || orderedMatch) {
      flushParagraph()
      const nextType = unorderedMatch ? 'ul' : 'ol'
      if (listType && listType !== nextType) flushList()
      listType = nextType
      listItems.push((unorderedMatch ?? orderedMatch)?.[1] ?? '')
      continue
    }

    flushList()
    paragraph.push(line)
  }

  if (inFence) flushFence()
  flushParagraph()
  flushList()
  return html.join('')
}

function isTableLine(value: string) {
  return /^\s*\|.+\|\s*$/.test(value) && value.split('|').length >= 3
}

function splitTableCells(value: string) {
  return value.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())
}

function isTableDivider(cells: string[]) {
  return cells.length > 0 && cells.every(cell => /^:?-{3,}:?$/.test(cell.replace(/\s+/g, '')))
}

function renderInlineMarkdown(value: string) {
  const codeSpans: string[] = []
  let html = escapeHtml(value).replace(/`([^`]+)`/g, (_, code: string) => {
    const index = codeSpans.push(`<code>${code}</code>`) - 1
    return `@@CODE${index}@@`
  })

  html = html.replace(/\[([^\]]+)]\(([^)\s]+)\)/g, (_, label: string, url: string) => {
    const safeUrl = safeMarkdownUrl(url)
    return `<a href="${safeUrl}" target="_blank" rel="noreferrer noopener">${label}</a>`
  })
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>')
  html = html.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  html = html.replace(/(^|[^_])_([^_\n]+)_/g, '$1<em>$2</em>')
  html = html.replace(/\n/g, '<br>')

  return html.replace(/@@CODE(\d+)@@/g, (_, index: string) => codeSpans[Number(index)] ?? '')
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function escapeAttribute(value: string) {
  return escapeHtml(value).replace(/\s+/g, '-')
}

function safeMarkdownUrl(value: string) {
  const decoded = value.replace(/&amp;/g, '&').trim()
  if (/^(https?:|mailto:|\/|#)/i.test(decoded)) return escapeAttribute(decoded)
  return '#'
}
async function scrollThreadToEnd() { await nextTick(); if (threadRef.value) threadRef.value.scrollTop = threadRef.value.scrollHeight }
function queueThreadScroll() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(async () => {
    scrollFrame = 0
    await scrollThreadToEnd()
  })
}
</script>

<template>
  <main class="app-shell">
    <div class="ambient ambient-one"></div>
    <div class="ambient ambient-two"></div>

    <header class="topbar">
      <div class="brand-block">
        <p class="eyebrow">Enterprise Knowledge Hub</p>
        <h1>共享知识库</h1>
        <p class="topbar-subtitle">统一接入团队文档，保留上传记录、会话上下文与可追溯引用。</p>
      </div>

      <section class="identity-panel">
        <form v-if="!user" class="login-form" @submit.prevent="signIn">
          <label>
            <span>邮箱登录</span>
            <input v-model="loginEmail" type="email" placeholder="name@company.com" />
          </label>
          <button>进入工作台</button>
          <p v-if="loginError">{{ loginError }}</p>
        </form>

        <button v-else class="profile-trigger" @click="toggleProfilePanel">
          <span class="avatar">{{ identityLabel.slice(0, 1).toUpperCase() }}</span>
          <span>
            <strong>{{ identityLabel }}</strong>
            <small>{{ currentEmail }}</small>
          </span>
        </button>
      </section>
    </header>

    <section v-if="errorMessage" class="error-banner">
      <strong>请求失败</strong>
      <span>{{ errorMessage }}</span>
    </section>

    <section class="dashboard">
      <section class="hero-dashboard">
        <div class="hero-copy">
          <p class="section-kicker">Shared Library</p>
          <h2>把散落文档变成可检索、可引用的知识资产</h2>
        </div>
        <div class="hero-actions">
          <button class="ghost-button" :disabled="listLoading" @click="fetchDocuments">
            {{ listLoading ? '同步中' : '刷新知识库' }}
          </button>
          <button class="primary-button" :disabled="!isSignedIn" @click="openChat">打开问答</button>
        </div>
      </section>

      <section class="metrics">
        <article>
          <span>当前状态</span>
          <strong>{{ healthText }}</strong>
        </article>
        <article>
          <span>已入库文档</span>
          <strong>{{ documents.length }}</strong>
        </article>
        <article>
          <span>向量分片</span>
          <strong>{{ totalChunks }}</strong>
        </article>
        <article>
          <span>解析成功 / 失败</span>
          <strong>{{ successUploads }} / {{ failedUploads }}</strong>
        </article>
      </section>

      <section class="dashboard-grid">
        <section class="upload-board">
          <div class="section-heading">
            <div>
              <p class="section-kicker">Upload</p>
              <h2>上传共享文档</h2>
            </div>
            <span class="status-pill" :class="{ active: uploading }">{{ uploading ? '解析中' : '待上传' }}</span>
          </div>

          <label class="dropzone" :class="{ disabled: uploading || !isSignedIn }">
            <input
              type="file"
              multiple
              accept=".md,.pdf,.doc,.docx,.txt"
              :disabled="uploading || !isSignedIn"
              @change="uploadDocuments"
            />
            <span class="dropzone-icon">+</span>
            <strong>{{ uploading ? '正在解析文件' : '选择或拖入文档' }}</strong>
            <small>{{ isSignedIn ? '支持 Markdown、PDF、Word、TXT，解析结果会写入上传记录。' : '登录后启用上传和个人审计。' }}</small>
          </label>

          <div class="upload-tips">
            <span>向量分片自动生成</span>
            <span>引用来源可追踪</span>
            <span>上传历史持久化</span>
          </div>
        </section>

        <section class="library-board">
          <div class="section-heading compact">
            <div>
              <p class="section-kicker">Library</p>
              <h2>共享文档</h2>
            </div>
            <span class="count-badge">{{ documents.length }} 份</span>
          </div>

          <ul v-if="documents.length" class="document-list">
            <li v-for="document in documents" :key="document.source">
              <div class="document-icon">DOC</div>
              <div class="document-main">
                <strong>{{ document.source }}</strong>
                <span>{{ document.chunkCount }} 个分片 · {{ formatBytes(document.size) }}</span>
                <span>{{ document.uploaderEmail || '未知上传人' }} · {{ formatTime(document.uploadedAt) }}</span>
              </div>
              <button class="danger-button" :disabled="deletingSource === document.source" @click="deleteDocument(document.source)">
                删除
              </button>
            </li>
          </ul>
          <p v-else class="empty-state">知识库暂无文档，上传后即可开始问答。</p>
        </section>

        <section class="audit-board">
          <div class="section-heading compact">
            <div>
              <p class="section-kicker">Audit</p>
              <h2>我的上传记录</h2>
            </div>
          </div>

          <ol v-if="uploadHistory.length" class="upload-list">
            <li v-for="record in uploadHistory" :key="record.id" :class="record.status">
              <div class="record-main">
                <strong>{{ record.source }}</strong>
                <span>{{ formatTime(record.createdAt) }} · {{ formatBytes(record.size) }}</span>
              </div>
              <p>{{ record.message }}</p>
              <small v-if="record.chunkCount != null">分片 {{ record.chunkCount }}</small>
            </li>
          </ol>
          <p v-else class="empty-state">暂无后端上传记录。</p>
        </section>
      </section>
    </section>

    <div v-if="profileOpen && user" class="overlay profile-overlay" @click.self="profileOpen = false">
      <aside class="profile-drawer">
        <div class="drawer-head">
          <div class="avatar">{{ identityLabel.slice(0, 1).toUpperCase() }}</div>
          <div>
            <strong>{{ identityLabel }}</strong>
            <span>{{ currentEmail }}</span>
          </div>
          <button class="icon-button" aria-label="关闭个人面板" @click="profileOpen = false">x</button>
        </div>

        <div class="button-row">
          <button class="ghost-button" @click="createSession">新建会话</button>
          <button class="ghost-button" @click="signOut">退出登录</button>
        </div>

        <ul class="session-list">
          <li v-for="session in sessions" :key="session.id" :class="{ active: session.id === activeSessionId }">
            <button @click="switchSession(session.id)">
              <strong>{{ session.title }}</strong>
              <span>{{ session.messageCount }} 条消息</span>
              <small>{{ formatTime(session.updatedAt) }}</small>
            </button>
            <button class="icon-button" aria-label="删除会话" @click="deleteSession(session.id)">x</button>
          </li>
        </ul>
      </aside>
    </div>

    <button v-if="!chatOpen" class="chat-fab" :disabled="!isSignedIn" @click="openChat">
      <span>AI</span>
      <strong>知识库问答</strong>
    </button>

    <div v-if="chatOpen" class="overlay chat-overlay" @click.self="closeChat">
      <aside class="chat-drawer">
        <div class="chat-toolbar">
          <div class="chat-title">
            <p class="section-kicker">RAG Chat</p>
            <h2>{{ sessions.find(s => s.id === activeSessionId)?.title || '知识库问答' }}</h2>
          </div>

          <div class="chat-controls">
            <label>
              <span>模型</span>
              <select v-model="model">
                <option value="deepseek">DeepSeek</option>
                <option value="openai">OpenAI Compatible</option>
              </select>
            </label>
            <label>
              <span>模式</span>
              <select v-model="answerMode">
                <option value="stream">流式</option>
                <option value="sync">非流式</option>
              </select>
            </label>
            <label class="network-toggle">
              <span>联网</span>
              <button class="switch-button" :class="{ active: webSearchEnabled }" type="button" @click="webSearchEnabled = !webSearchEnabled">
                <span>{{ webSearchEnabled ? '开' : '关' }}</span>
              </button>
            </label>
          </div>

          <button class="icon-button" aria-label="关闭问答面板" @click="closeChat">x</button>
        </div>

        <section ref="threadRef" class="chat-thread">
          <div v-if="!messages.length" class="chat-empty">
            <span class="welcome-mark">RAG</span>
            <h3>从共享知识库开始提问</h3>
            <p>你可以先用下面的问题快速验证知识库召回、引用来源和回答稳定性。</p>
            <div class="quick-questions">
              <button v-for="item in QUICK_QUESTIONS" :key="item" @click="useQuickQuestion(item)">{{ item }}</button>
            </div>
          </div>

          <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
            <div class="message-meta">
              <strong>{{ message.role === 'user' ? '你' : '知识库助手' }}</strong>
              <span>{{ formatTime(message.createdAt) }}</span>
            </div>
            <details v-if="message.role === 'assistant' && message.reasoning" class="reasoning-block" open>
              <summary>
                <strong>思考过程</strong>
                <span>{{ message.pending ? '推理中' : '已完成' }}</span>
              </summary>
              <div class="markdown-content reasoning-markdown" v-html="renderMarkdown(message.reasoning)"></div>
            </details>
            <div
              v-if="message.content"
              class="markdown-content message-content"
              v-html="renderMarkdown(message.content)"
            ></div>
            <p v-else-if="message.pending" class="message-content muted">{{ assistantPlaceholder(message) }}</p>

            <div v-if="message.citations.length" class="citation-block">
              <div class="citation-heading">
                <strong>引用来源</strong>
                <span>{{ message.citations.length }} 条</span>
              </div>
              <ul class="citation-list">
                <li v-for="(citation, index) in message.citations" :key="`${message.id}-${index}`" class="citation-card">
                  <div class="citation-card-head">
                    <span class="citation-index">{{ index + 1 }}</span>
                    <div class="citation-title">
                      <strong>{{ citation.source }}</strong>
                      <small>{{ sourceExists(citation.source) ? `当前库中 ${sourceChunkCount(citation.source)} 个分片` : '来源已不在当前文档列表' }}</small>
                    </div>
                    <span class="score-badge">{{ formatScore(citation.score) }}</span>
                  </div>
                  <div class="markdown-content citation-snippet" v-html="renderMarkdown(citation.snippet)"></div>
                </li>
              </ul>
            </div>

            <small v-if="message.error" class="inline-error">{{ message.error }}</small>
          </article>
        </section>

        <footer class="composer">
          <textarea v-model="question" placeholder="向知识库提问，Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="ask" />
          <div class="composer-footer">
            <span>Ctrl + Enter 发送 · {{ webSearchEnabled ? '联网检索已开启' : '仅使用知识库' }}</span>
            <button v-if="streaming" class="danger-action" @click="stopGeneration">停止生成</button>
            <button v-else :disabled="!question.trim() || !isSignedIn" @click="ask">发送</button>
          </div>
        </footer>
      </aside>
    </div>
  </main>
</template>
