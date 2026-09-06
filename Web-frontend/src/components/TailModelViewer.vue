<template>
  <div ref="host" class="tail-model-viewer" :class="{ 'is-ready': isReady }">
    <div class="model-stage" aria-hidden="true"></div>
    <div v-if="!isReady && !loadError" class="model-loading" role="status">
      <span class="loading-line"><i :style="{ width: `${loadProgress}%` }"></i></span>
      <span>正在装载{{ modelLabel }} {{ loadProgress }}%</span>
    </div>
    <div v-if="loadError" class="model-error" role="alert">3D 模型暂时无法显示</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'

const props = defineProps({
  modelUrl: {
    type: String,
    required: true
  },
  modelLabel: {
    type: String,
    default: 'C919 三维模型'
  }
})

const host = ref(null)
const isReady = ref(false)
const loadError = ref(false)
const loadProgress = ref(0)

let renderer
let controls
let resizeObserver
let animationFrame
let modelRoot
let scene
let initialCameraDistance = 0
const cleanupCallbacks = []

function disposeMaterial(material) {
  Object.values(material).forEach((value) => {
    if (value?.isTexture) value.dispose()
  })
  material.dispose()
}

function createWindshieldGlassMaterial(sourceMaterial) {
  const material = new THREE.MeshPhysicalMaterial({
    name: sourceMaterial.name,
    color: 0xa9d6df,
    roughness: 0.1,
    metalness: 0,
    transparent: true,
    opacity: 0.3,
    transmission: 0.18,
    thickness: 0.08,
    ior: 1.46,
    clearcoat: 0.8,
    clearcoatRoughness: 0.08,
    side: THREE.DoubleSide,
    depthWrite: false
  })
  return material
}

function resizeRenderer(camera) {
  if (!host.value || !renderer) return
  const width = Math.max(host.value.clientWidth, 1)
  const height = Math.max(host.value.clientHeight, 1)
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

function sanitizeModel(root) {
  const meshes = []
  root.traverse((object) => {
    if (object.isMesh) meshes.push(object)
  })
  if (meshes.length < 2) return

  const measured = meshes.map((object) => ({
    object,
    size: new THREE.Box3().setFromObject(object).getSize(new THREE.Vector3())
  }))
  const lengths = measured.map((item) => item.size.length()).sort((a, b) => a - b)
  const median = lengths[Math.floor(lengths.length / 2)]
  const sizeLimit = Math.max(median * 4, 60)

  measured.forEach(({ object, size }) => {
    if (object.name.trim() === '平面' || size.length() > sizeLimit) {
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

  const camera = new THREE.PerspectiveCamera(34, 1, 0.01, 10000)
  renderer = new THREE.WebGLRenderer({
    antialias: true,
    alpha: true,
    powerPreference: 'high-performance'
  })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 3))
  renderer.setClearColor(0x000000, 0)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.15
  renderer.domElement.setAttribute('aria-label', props.modelLabel)
  stage.appendChild(renderer.domElement)

  scene.add(new THREE.HemisphereLight(0xd7f7ff, 0x161a20, 2.2))

  const keyLight = new THREE.DirectionalLight(0xffffff, 4.4)
  keyLight.position.set(5, 7, 8)
  scene.add(keyLight)

  const rimLight = new THREE.DirectionalLight(0x45e8ff, 3.2)
  rimLight.position.set(-7, 3, -6)
  scene.add(rimLight)

  const warmLight = new THREE.DirectionalLight(0xffd18a, 1.5)
  warmLight.position.set(2, -4, 4)
  scene.add(warmLight)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.06
  controls.enablePan = true
  controls.screenSpacePanning = true
  controls.autoRotate =
    !props.modelUrl.includes('c919-front-window') &&
    !window.matchMedia('(prefers-reduced-motion: reduce)').matches
  controls.autoRotateSpeed = 0.65

  resizeRenderer(camera)
  resizeObserver = new ResizeObserver(() => resizeRenderer(camera))
  resizeObserver.observe(host.value)

  const loader = new GLTFLoader()
  loader.load(
    props.modelUrl,
    (gltf) => {
      modelRoot = gltf.scene
      const isFrontWindow = props.modelUrl.includes('c919-front-window')
      sanitizeModel(modelRoot)
      const bounds = computeVisibleBounds(modelRoot)
      const center = bounds.getCenter(new THREE.Vector3())
      const size = bounds.getSize(new THREE.Vector3())
      const radius = Math.max(size.length() * 0.5, 0.01)

      modelRoot.position.sub(center)
      modelRoot.traverse((object) => {
        if (!object.isMesh) return
        object.castShadow = true
        object.receiveShadow = true
        if (object.material) {
          const materials = Array.isArray(object.material) ? object.material : [object.material]
          let hasGlassMaterial = false
          const preparedMaterials = materials.map((material) => {
            const isGlass =
              isFrontWindow &&
              (`${object.name} ${material.name}`).toLowerCase().includes('glass')

            if (isGlass) {
              hasGlassMaterial = true
              return createWindshieldGlassMaterial(material)
            }

            material.side = THREE.DoubleSide
            material.needsUpdate = true
            return material
          })
          object.material = Array.isArray(object.material) ? preparedMaterials : preparedMaterials[0]
          if (hasGlassMaterial) object.renderOrder = 2
        }
      })
      scene.add(modelRoot)

      const verticalFov = THREE.MathUtils.degToRad(camera.fov)
      const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * camera.aspect)
      const fitFov = Math.min(verticalFov, horizontalFov)
      const distance = (radius / Math.sin(fitFov / 2)) * (isFrontWindow ? 0.76 : 1)
      camera.near = Math.max(distance / 100, 0.01)
      camera.far = distance * 20
      if (isFrontWindow) {
        camera.position.set(0, distance * 0.2, distance * 1.04)
      } else {
        camera.position.set(distance * 0.78, distance * 0.34, distance * 0.96)
      }
      camera.updateProjectionMatrix()

      controls.target.set(0, 0, 0)
      controls.minDistance = distance * 0.45
      controls.maxDistance = distance * 2.4
      controls.update()
      initialCameraDistance = camera.position.distanceTo(controls.target)
      setupZoomAwarePan()

      isReady.value = true
      loadProgress.value = 100
    },
    (event) => {
      if (event.total > 0) {
        loadProgress.value = Math.min(99, Math.round((event.loaded / event.total) * 100))
      }
    },
    () => {
      loadError.value = true
    }
  )

  const animate = () => {
    controls.update()
    renderer.render(scene, camera)
    animationFrame = requestAnimationFrame(animate)
  }
  animate()
})

onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame)
  cleanupCallbacks.forEach((cleanup) => cleanup())
  resizeObserver?.disconnect()
  controls?.dispose()
  if (modelRoot) {
    modelRoot.traverse((object) => {
      if (!object.isMesh) return
      object.geometry?.dispose()
      if (Array.isArray(object.material)) object.material.forEach(disposeMaterial)
      else if (object.material) disposeMaterial(object.material)
    })
  }
  renderer?.dispose()
  renderer?.domElement.remove()
  scene?.clear()
})
</script>

<style scoped>
.tail-model-viewer,
.model-stage {
  position: absolute;
  inset: 0;
}

.tail-model-viewer {
  overflow: hidden;
  background:
    linear-gradient(rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    #090b0e;
  background-size: 64px 64px;
}

.tail-model-viewer::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(circle at 50% 48%, transparent 25%, rgba(3, 5, 7, 0.74) 100%);
}

.model-stage {
  z-index: 1;
  opacity: 0;
  transform: scale(0.985);
  transition: opacity 0.65s ease, transform 0.9s var(--ease-smooth);
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
  top: 52%;
  display: grid;
  width: min(280px, 64%);
  gap: 12px;
  color: rgba(255, 255, 255, 0.72);
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
  background: #38e8ff;
  box-shadow: 0 0 12px rgba(56, 232, 255, 0.75);
  transition: width 0.25s ease;
}

@media (prefers-reduced-motion: reduce) {
  .model-stage {
    transition: opacity 0.2s ease;
  }
}
</style>
