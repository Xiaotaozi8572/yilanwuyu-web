<template>
  <SceneShell :image="sceneImage" alt="知识巩固测试结果">
    <button class="scene-hotspot back-hotspot" @click="$router.push('/z20/interaction')">
      <span class="sr-only">返回主交互页</span>
    </button>
    <button class="scene-hotspot retry-hotspot" @click="$router.push('/z20/quiz')">
      <span class="sr-only">重新挑战</span>
    </button>
    <button class="scene-hotspot home-hotspot" @click="$router.push('/z20/interaction')">
      <span class="sr-only">返回主菜单</span>
    </button>
  </SceneShell>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SceneShell from '../components/Z20SceneShell.vue'
import { z20AssetPath } from '../utils/z20Assets'

const route = useRoute()

const score = computed(() => {
  const parsed = Number.parseInt(route.query.score, 10)
  if (Number.isNaN(parsed)) return 0
  return Math.max(0, Math.min(8, parsed))
})

const resultImages = [
  '结果页/C919.jpg',
  '结果页/C919-1.jpg',
  '结果页/C919-2.jpg',
  '结果页/C919-3.jpg',
  '结果页/C919-4.jpg',
  '结果页/C919-5.jpg',
  '结果页/C919-6.jpg',
  '结果页/C919-7.jpg',
  '结果页/C919-8.jpg'
]

const sceneImage = computed(() => resultImages[score.value])
</script>

<style scoped>
.back-hotspot {
  --x: 6.181;
  --y: 9.829;
  --w: 5.556;
  --h: 8.547;
  --hotspot-radius: 999px;
}

.retry-hotspot,
.home-hotspot {
  --y: 66.35;
  --w: 15.69;
  --h: 6.3;
  --hotspot-radius: 999px;
}

.retry-hotspot:hover::before,
.home-hotspot:hover::before,
.retry-hotspot:focus-visible::before,
.home-hotspot:focus-visible::before {
  background: rgba(56, 232, 255, 0.1);
  box-shadow: inset 0 0 0 1px rgba(56, 232, 255, 0.42);
}

.retry-hotspot:active,
.home-hotspot:active {
  transform: none;
}

.retry-hotspot {
  --x: 33.4;
}

.home-hotspot {
  --x: 51.18;
}
</style>
