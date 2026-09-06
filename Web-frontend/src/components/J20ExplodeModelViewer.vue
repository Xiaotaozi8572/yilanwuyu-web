<template>
  <div ref="host" class="explode-model-viewer" :class="{ 'is-ready': isReady }">
    <div class="model-stage"></div>
    <div v-if="!isReady && !loadError" class="model-loading" role="status">
      <span class="loading-line"><i :style="{ width: `${loadProgress}%` }"></i></span>
      <span>正在装载 歼-20 拆解模型 {{ loadProgress }}%</span>
    </div>
    <div v-if="loadError" class="model-error" role="alert">3D 拆解模型暂时无法显示</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'

const props = defineProps({
  explodedModelUrl: {
    type: String,
    required: true
  },
  assembledModelUrl: {
    type: String,
    required: true
  },
  exploded: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['ready'])
const host = ref(null)
const isReady = ref(false)
const loadError = ref(false)
const loadProgress = ref(0)

let renderer
let controls
let resizeObserver
let animationFrame
let explodedRoot
let assembledRoot
let scene
let lastFrameTime = 0
let explosionProgress = 0
let initialCameraDistance = 0
const explodeParts = []
const materialSet = new Set()
const cleanupCallbacks = []

function disposeMaterial(material) {
  Object.values(material).forEach((value) => {
    if (value?.isTexture) value.dispose()
  })
  material.dispose()
}

function resizeRenderer(camera) {
  if (!host.value || !renderer) return
  const width = Math.max(host.value.clientWidth, 1)
  const height = Math.max(host.value.clientHeight, 1)
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

function prepareModel(root) {
  root.traverse((object) => {
    if (!object.isMesh) return
    object.castShadow = true
    object.receiveShadow = true
    const materials = Array.isArray(object.material) ? object.material : [object.material]
    materials.filter(Boolean).forEach((material) => {
      material.side = THREE.DoubleSide
      material.userData.baseOpacity = material.opacity
      material.userData.baseTransparent = material.transparent
      material.needsUpdate = true
      materialSet.add(material)
    })
  })
}

function sanitizeModel(root) {
  const meshes = []
  root.traverse((object) => {
    if (object.isMesh) meshes.push(object)
  })
  if (meshes.length < 1) return

  meshes.forEach((object) => {
    if (object.name.trim() === '平面' || object.name.trim().toLowerCase() === 'plane') {
      object.visible = false
    }
  })
}

function computeVisibleBounds(root) {
  root.updateWorldMatrix(true, true)
  const box = new THREE.Box3()
  root.traverse((object) => {
    if (!object.isMesh || object.visible === false) return
    let ancestor = object.parent
    let hidden = false
    while (ancestor) {
      if (ancestor.visible === false) {
        hidden = true
        break
      }
      ancestor = ancestor.parent
    }
    if (!hidden) box.expandByObject(object)
  })
  return box
}

function createExplosionTargets(root) {
  root.traverse((object) => {
    if (object === root || object.position.lengthSq() < 1e-12) return
    explodeParts.push({
      object,
      mergedPosition: new THREE.Vector3(),
      explodedPosition: object.position.clone()
    })
  })
}

function applyExplosionProgress(progress) {
  if (!explodedRoot) return
  explodeParts.forEach(({ object, mergedPosition, explodedPosition }) => {
    object.position.lerpVectors(mergedPosition, explodedPosition, progress)
  })
}

function setModelOpacity(root, opacity) {
  if (!root) return
  const isVisible = opacity > 0.015
  root.visible = isVisible
  if (!isVisible) return

  root.traverse((object) => {
    if (!object.isMesh) return
    const materials = Array.isArray(object.material) ? object.material : [object.material]
    materials.filter(Boolean).forEach((material) => {
      const baseOpacity = material.userData.baseOpacity ?? 1
      material.opacity = baseOpacity * opacity
      material.transparent = opacity < 0.985 || material.userData.baseTransparent
      material.depthWrite = opacity > 0.82
      material.needsUpdate = true
    })
  })
}

function updateModelBlend(progress) {
  const eased = progress * progress * (3 - 2 * progress)
  const explodedOpacity = THREE.MathUtils.smoothstep(eased, 0.12, 0.34)
  const assembledOpacity = 1 - THREE.MathUtils.smoothstep(eased, 0.04, 0.22)

  setModelOpacity(explodedRoot, explodedOpacity)
  setModelOpacity(assembledRoot, assembledOpacity)
}

function centerModel(root) {
  const bounds = computeVisibleBounds(root)
  const center = bounds.getCenter(new THREE.Vector3())
  root.position.sub(center)
  return Math.max(bounds.getSize(new THREE.Vector3()).length() * 0.5, 0.01)
}

function updateLoadProgress(progressState) {
  loadProgress.value = Math.min(
    99,
    Math.round(((progressState.exploded + progressState.assembled) / 2) * 100)
  )
}

function loadGltf(loader, url, progressKey, progressState) {
  return new Promise((resolve, reject) => {
    loader.load(
      url,
      (gltf) => {
        progressState[progressKey] = 1
        updateLoadProgress(progressState)
        resolve(gltf)
      },
      (event) => {
        if (event.total > 0) {
          progressState[progressKey] = Math.min(0.99, event.loaded / event.total)
          updateLoadProgress(progressState)
        }
      },
      reject
    )
  })
}

function setupZoomAwarePan() {
  const canvas = renderer.domElement
  const setLeftMouseMode = (event) => {
    if (event.button !== 0 || !initialCameraDistance) return
    const distance = controls.object.position.distanceTo(controls.target)
    controls.mouseButtons.LEFT =
      distance < initialCameraDistance * 0.82 ? THREE.MOUSE.PAN : THREE.MOUSE.ROTATE
  }
  const restoreLeftMouseMode = () => {
    if (controls) controls.mouseButtons.LEFT = THREE.MOUSE.ROTATE
  }

  canvas.addEventListener('pointerdown', setLeftMouseMode, true)
  window.addEventListener('pointerup', restoreLeftMouseMode)
  window.addEventListener('pointercancel', restoreLeftMouseMode)
  cleanupCallbacks.push(() => {
    canvas.removeEventListener('pointerdown', setLeftMouseMode, true)
    window.removeEventListener('pointerup', restoreLeftMouseMode)
    window.removeEventListener('pointercancel', restoreLeftMouseMode)
  })
}

onMounted(() => {
  const stage = host.value.querySelector('.model-stage')
  scene = new THREE.Scene()

  const camera = new THREE.PerspectiveCamera(33, 1, 0.01, 10000)
  renderer = new THREE.WebGLRenderer({
    antialias: true,
    alpha: true,
    powerPreference: 'high-performance'
  })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 3))
  renderer.setClearColor(0x000000, 0)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.26
  renderer.domElement.setAttribute('aria-label', '歼-20 爆炸拆解三维模型')
  stage.appendChild(renderer.domElement)

  scene.add(new THREE.HemisphereLight(0xe5f8ff, 0x15181d, 2.5))

  const keyLight = new THREE.DirectionalLight(0xffffff, 4.8)
  keyLight.position.set(6, 8, 9)
  scene.add(keyLight)

  const rimLight = new THREE.DirectionalLight(0x4ce8ff, 3.4)
  rimLight.position.set(-8, 2, -7)
  scene.add(rimLight)

  const fillLight = new THREE.DirectionalLight(0xffd18a, 1.7)
  fillLight.position.set(1, -5, 5)
  scene.add(fillLight)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.065
  controls.enablePan = true
  controls.screenSpacePanning = true
  controls.autoRotate = false

  resizeRenderer(camera)
  resizeObserver = new ResizeObserver(() => resizeRenderer(camera))
  resizeObserver.observe(host.value)

  const loader = new GLTFLoader()
  const progressState = { exploded: 0, assembled: 0 }

  Promise.all([
    loadGltf(loader, props.explodedModelUrl, 'exploded', progressState),
    loadGltf(loader, props.assembledModelUrl, 'assembled', progressState)
  ])
    .then(([explodedGltf, assembledGltf]) => {
      explodedRoot = explodedGltf.scene
      assembledRoot = assembledGltf.scene
      sanitizeModel(explodedRoot)
      sanitizeModel(assembledRoot)
      prepareModel(explodedRoot)
      prepareModel(assembledRoot)

      createExplosionTargets(explodedRoot)
      applyExplosionProgress(0)
      const mergedRadius = centerModel(explodedRoot)
      scene.add(explodedRoot)

      applyExplosionProgress(1)
      const explodedCenter = computeVisibleBounds(explodedRoot).getCenter(new THREE.Vector3())
      explodeParts.forEach(({ explodedPosition }) => explodedPosition.sub(explodedCenter))
      applyExplosionProgress(1)
      const explodedBounds = computeVisibleBounds(explodedRoot)
      const explodedSize = explodedBounds.getSize(new THREE.Vector3())
      const explodedRadius = Math.max(explodedSize.length() * 0.5, mergedRadius)

      const assembledRadius = centerModel(assembledRoot)
      scene.add(assembledRoot)

      explosionProgress = props.exploded ? 1 : 0
      applyExplosionProgress(explosionProgress)
      updateModelBlend(explosionProgress)

      const verticalFov = THREE.MathUtils.degToRad(camera.fov)
      const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * camera.aspect)
      const fitFov = Math.min(verticalFov, horizontalFov)
      const displayRadius = Math.max(explodedRadius, assembledRadius)
      const distance = (displayRadius / Math.sin(fitFov / 2)) * 0.36
      camera.near = Math.max(distance / 120, 0.01)
      camera.far = distance * 25
      camera.position.set(distance * 0.86, distance * 0.42, distance * 1.04)
      camera.updateProjectionMatrix()

      controls.target.set(0, 0, 0)
      controls.minDistance = distance * 0.46
      controls.maxDistance = distance * 2.8
      controls.update()
      initialCameraDistance = camera.position.distanceTo(controls.target)
      setupZoomAwarePan()

      isReady.value = true
      loadProgress.value = 100
      emit('ready')
    })
    .catch(() => {
      loadError.value = true
    })

  const animate = (time) => {
    const delta = Math.min((time - lastFrameTime) / 1000 || 0, 0.05)
    lastFrameTime = time
    const target = props.exploded ? 1 : 0
    explosionProgress = THREE.MathUtils.damp(explosionProgress, target, 4.8, delta)
    if (Math.abs(explosionProgress - target) < 0.001) explosionProgress = target
    const eased = explosionProgress * explosionProgress * (3 - 2 * explosionProgress)

    applyExplosionProgress(eased)
    updateModelBlend(eased)

    controls.update()
    renderer.render(scene, camera)
    animationFrame = requestAnimationFrame(animate)
  }
  animationFrame = requestAnimationFrame(animate)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame)
  cleanupCallbacks.forEach((cleanup) => cleanup())
  resizeObserver?.disconnect()
  controls?.dispose()
  ;[explodedRoot, assembledRoot].filter(Boolean).forEach((root) => {
    root.traverse((object) => {
      if (!object.isMesh) return
      object.geometry?.dispose()
    })
  })
  materialSet.forEach(disposeMaterial)
  renderer?.dispose()
  renderer?.domElement.remove()
  scene?.clear()
})
</script>

<style scoped>
.explode-model-viewer,
.model-stage {
  position: absolute;
  inset: 0;
}

.explode-model-viewer {
  overflow: hidden;
}

.model-stage {
  opacity: 0;
  transform: scale(0.985);
  transition: opacity 0.6s ease, transform 0.85s var(--ease-smooth);
}

.is-ready .model-stage {
  opacity: 1;
  transform: scale(1);
}

.model-stage :deep(canvas) {
  display: block;
  width: 100%;
  height: 100%;
  touch-action: none;
}

.model-loading,
.model-error {
  position: absolute;
  z-index: 3;
  left: 50%;
  top: 50%;
  display: grid;
  width: min(300px, 62%);
  gap: 12px;
  color: rgba(255, 255, 255, 0.76);
  font-size: var(--text-xs, 12px);
  font-weight: var(--weight-medium, 500);
  text-align: center;
  transform: translate(-50%, -50%);
}

.loading-line {
  height: 2px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.14);
}

.loading-line i {
  display: block;
  height: 100%;
  background: #52eaff;
  box-shadow: 0 0 14px rgba(82, 234, 255, 0.72);
  transition: width 0.22s ease;
}

@media (prefers-reduced-motion: reduce) {
  .model-stage {
    transition: opacity 0.2s ease;
  }
}
</style>

