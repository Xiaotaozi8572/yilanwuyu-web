<template>
  <main class="scene-shell">
    <div class="scene-frame">
      <div class="scene-inner-background" aria-hidden="true">
        <slot name="background" />
      </div>
      <Teleport to="body">
        <div class="scene-background-plane" aria-hidden="true">
          <slot name="background" />
        </div>
      </Teleport>
      <div
        class="image-layer"
        :class="{ loaded: imageLoaded }"
        :style="{ mixBlendMode: imageBlendMode || undefined }"
      >
        <img
          class="scene-image"
          :src="resolvedImage"
          :alt="alt"
          draggable="false"
          @load="onImageLoad"
        />
      </div>
      <div class="scene-vignette" aria-hidden="true" />
      <div class="scene-design-plane">
        <slot />
      </div>
    </div>
  </main>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { z20AssetPath } from '../utils/z20Assets'

const props = defineProps({
  image: {
    type: String,
    required: true
  },
  alt: {
    type: String,
    default: ''
  },
  imageBlendMode: {
    type: String,
    default: ''
  },
  assetBase: {
    type: String,
    default: '/z20-assets/'
  }
})

const resolvedImage = computed(() => {
  if (props.assetBase === '/z20-assets/') return z20AssetPath(props.image)
  const clean = props.image.replace(/^\/+/, '')
  return `${props.assetBase}/${clean
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/')}`
})

const imageLoaded = ref(false)

function onImageLoad() {
  imageLoaded.value = true
}

watch(
  () => props.image,
  () => {
    imageLoaded.value = false
  }
)
</script>

<style scoped>
.scene-shell {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: transparent;
}

.scene-frame {
  position: relative;
  width: 100%;
  height: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: transparent;
  isolation: isolate;
  border-radius: 0;
  box-shadow: none;
}

.image-layer {
  position: absolute;
  left: 50%;
  top: 0;
  z-index: 1;
  width: 86.53846154%;
  height: 100%;
  opacity: 0;
  transform: translateX(-50%);
  transition: opacity 0.55s var(--ease-smooth, cubic-bezier(0.22, 0.75, 0.18, 1));
}

.scene-inner-background {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.scene-inner-background > :deep(*) {
  pointer-events: none;
}

.scene-background-plane {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.scene-background-plane > :deep(*) {
  pointer-events: none;
}

.image-layer.loaded {
  opacity: 1;
}

.scene-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  user-select: none;
  pointer-events: none;
}

.scene-design-plane {
  position: absolute;
  left: 50%;
  top: 0;
  z-index: 3;
  width: 86.53846154%;
  height: 100%;
  transform: translateX(-50%);
  transform-origin: center;
}

.scene-vignette {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background:
    radial-gradient(circle at 50% 0%, rgba(56, 232, 255, 0.04), transparent 48%),
    radial-gradient(circle at 80% 80%, rgba(10, 12, 16, 0.35), transparent 50%);
  opacity: 0.8;
  mix-blend-mode: screen;
}

@media (max-width: 0px) { /* 舞台固定1280×720+整体等比缩放，视口断点停用 */
  .scene-frame {
    border-radius: 0;
  }
}
</style>
