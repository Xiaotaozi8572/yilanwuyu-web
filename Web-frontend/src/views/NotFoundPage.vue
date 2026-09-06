<template>
  <main class="not-found-page">
    <section class="not-found-panel glass-panel">
      <p class="eyebrow">导航中断</p>
      <h1>404</h1>
      <p class="message">您访问的页面不存在或已移动，请返回首页重新选择。</p>
      <router-link class="home-btn" to="/" aria-label="返回首页">返回首页</router-link>
    </section>
  </main>
</template>

<script setup>
</script>

<style scoped>
.not-found-page {
  position: relative;
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
  color: #fff;
  background:
    radial-gradient(circle at 35% 42%, rgba(56, 232, 255, 0.06), transparent 34%),
    radial-gradient(circle at 90% 10%, rgba(255, 255, 255, 0.04), transparent 30%),
    var(--color-bg, #0a0c10);
}

.not-found-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.025), transparent 42%),
    repeating-linear-gradient(90deg, transparent 0 79px, rgba(255, 255, 255, 0.018) 80px),
    repeating-linear-gradient(0deg, transparent 0 79px, rgba(255, 255, 255, 0.014) 80px);
  mask-image: linear-gradient(180deg, transparent 2%, #000 22%, #000 82%, transparent 100%);
  pointer-events: none;
}

.not-found-panel {
  position: relative;
  z-index: 2;
  width: min(calc(100% - 80px), 520px);
  padding: 44px 40px 46px;
  text-align: center;
  border-radius: 10px;
  background: rgba(17, 23, 31, 0.78);
  border: 1px solid var(--glass-border, rgba(56, 232, 255, 0.28));
  box-shadow:
    0 24px 58px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(16px) saturate(140%);
  -webkit-backdrop-filter: blur(16px) saturate(140%);
}

.eyebrow {
  margin-bottom: 8px;
  color: var(--accent, #38e8ff);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-semibold, 600);
  letter-spacing: 0;
}

h1 {
  font-size: 96px;
  font-weight: var(--weight-heavy, 800);
  line-height: 1;
  margin-bottom: 6px;
  text-shadow: 0 2px 16px rgba(0, 0, 0, 0.35);
}

.message {
  max-width: 380px;
  margin: 0 auto 26px;
  color: rgba(255, 255, 255, 0.76);
  font-size: 15px;
  line-height: 1.7;
}

.home-btn {
  display: inline-block;
  padding: 11px 34px;
  border-radius: 6px;
  color: #041016;
  background: var(--accent, #38e8ff);
  font-size: 14px;
  font-weight: var(--weight-bold, 700);
  text-decoration: none;
  box-shadow: 0 8px 22px rgba(56, 232, 255, 0.28);
  transition:
    background 0.25s ease,
    transform 0.25s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.home-btn:hover {
  background: #7df0ff;
  transform: translateY(-2px);
}
</style>
