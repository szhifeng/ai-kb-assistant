<script setup lang="ts">
import { ref } from 'vue'

type Citation = {
  source: string
  snippet: string
  score: number | null
}

const question = ref('')
const answer = ref('')
const citations = ref<Citation[]>([])
const conversationId = ref(`conv-${Date.now()}`)
const model = ref('deepseek')
const uploading = ref(false)
const streaming = ref(false)
const ingestMessage = ref('')
const errorMessage = ref('')

async function uploadDocument(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  const form = new FormData()
  form.append('file', file)
  uploading.value = true
  ingestMessage.value = ''
  errorMessage.value = ''
  try {
    const response = await fetch('/api/documents', { method: 'POST', body: form })
    if (!response.ok) throw new Error(`上传失败: ${response.status}`)
    const result = await response.json()
    ingestMessage.value = `${result.source} 已入库，分片数 ${result.chunkCount}`
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '上传失败'
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function ask() {
  if (!question.value.trim() || streaming.value) return

  answer.value = ''
  citations.value = []
  errorMessage.value = ''
  streaming.value = true

  const params = new URLSearchParams({
    question: question.value,
    conversationId: conversationId.value,
    model: model.value,
  })

  const eventSource = new EventSource(`/api/chat/stream?${params.toString()}`)
  eventSource.addEventListener('token', (event) => {
    answer.value += (event as MessageEvent).data
  })
  eventSource.addEventListener('citations', (event) => {
    citations.value = JSON.parse((event as MessageEvent).data)
    streaming.value = false
    eventSource.close()
  })
  eventSource.onerror = () => {
    errorMessage.value = '流式连接异常，请检查后端服务或模型 Key'
    streaming.value = false
    eventSource.close()
  }
}
</script>

<template>
  <main class="page">
    <section class="hero-panel">
      <p class="eyebrow">Spring AI RAG Assistant</p>
      <h1>个人知识库问答助手</h1>
      <p class="subtitle">上传文档后，基于 PGVector 检索与 ChatClient Advisors 进行流式问答，并返回引用来源。</p>
    </section>

    <section class="workspace">
      <aside class="sidebar">
        <label class="field">
          <span>上传文档</span>
          <input type="file" accept=".md,.pdf,.doc,.docx,.txt" :disabled="uploading" @change="uploadDocument" />
        </label>
        <p v-if="ingestMessage" class="success">{{ ingestMessage }}</p>

        <label class="field">
          <span>会话 ID</span>
          <input v-model="conversationId" />
        </label>

        <label class="field">
          <span>模型路由</span>
          <select v-model="model">
            <option value="deepseek">DeepSeek</option>
            <option value="openai">OpenAI</option>
          </select>
        </label>
      </aside>

      <section class="chat-panel">
        <textarea v-model="question" rows="4" placeholder="请输入你的问题，例如：这篇文档的核心结论是什么？" />
        <button :disabled="streaming || !question.trim()" @click="ask">
          {{ streaming ? '生成中...' : '流式提问' }}
        </button>

        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>

        <section class="answer-card">
          <h2>回答</h2>
          <p v-if="answer" class="answer">{{ answer }}</p>
          <p v-else class="placeholder">等待模型回答...</p>
        </section>

        <section class="answer-card">
          <h2>引用</h2>
          <ul v-if="citations.length" class="citations">
            <li v-for="(citation, index) in citations" :key="`${citation.source}-${index}`">
              <strong>{{ citation.source }}</strong>
              <p>{{ citation.snippet }}</p>
            </li>
          </ul>
          <p v-else class="placeholder">回答结束后显示引用来源。</p>
        </section>
      </section>
    </section>
  </main>
</template>
