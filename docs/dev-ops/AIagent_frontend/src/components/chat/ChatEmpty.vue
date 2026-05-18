<template>
  <div class="chat-empty">
    <div class="chat-empty-icon">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    </div>
    <h3>开始对话</h3>
    <p>选择一个智能体，在下方输入你的问题，按下回车发送。</p>
  </div>
</template>

<style scoped>
.chat-empty {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 10px;
  padding: 40px;
  animation: fadeIn 0.5s ease;
}

.chat-empty-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: var(--surface);
  box-shadow: var(--shadow-md);
  display: grid;
  place-items: center;
  margin-bottom: 6px;
  animation: float 4s var(--ease-in-out-smooth) infinite;
}

.chat-empty-icon svg {
  width: 22px;
  height: 22px;
  color: var(--text-secondary);
}

.chat-empty h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
  animation: stagger-fade-up 0.6s var(--ease-out-expo) both;
  animation-delay: 0.1s;
}

.chat-empty p {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
  max-width: 36ch;
  animation: stagger-fade-up 0.6s var(--ease-out-expo) both;
  animation-delay: 0.2s;
}
</style>
