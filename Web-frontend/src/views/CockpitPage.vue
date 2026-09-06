<template>
  <main class="sim-shell">
    <Teleport to="body">
      <div class="sim-viewport" :class="{ leaving: viewportLeaving }">
        <iframe
          class="sim-frame"
          :src="simUrl"
          title="C919 沉浸式模拟驾驶"
          allow="fullscreen; webgl"
          allowfullscreen
          referrerpolicy="no-referrer"
          loading="eager"
        ></iframe>
      </div>
    </Teleport>
  </main>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'

const router = useRouter()
const simUrl = '/c919-sim/index.html'
const viewportLeaving = ref(false)

// #app-stage 的 contain 会困住 fixed 元素，iframe 必须 Teleport 到 body 才能铺满整个视口
onBeforeRouteLeave(() => {
  viewportLeaving.value = true
})

function goBack() {
  router.push('/interaction')
}

function onMessage(event) {
  if (event.data === 'c919-sim:exit') goBack()
}

onMounted(() => window.addEventListener('message', onMessage))
onUnmounted(() => window.removeEventListener('message', onMessage))
</script>

<style scoped>
.sim-shell {
  position: relative;
  width: 100%;
  height: 100%;
  background: var(--color-bg);
  overflow: hidden;
}

.sim-viewport {
  position: fixed;
  inset: 0;
  z-index: 2;
  overflow: hidden;
  background: #060a12;
  animation: simViewportIn 0.45s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.sim-viewport.leaving {
  opacity: 0;
  transition: opacity 0.45s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.sim-frame {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
}

@keyframes simViewportIn {
  from {
    opacity: 0;
  }
}
</style>
