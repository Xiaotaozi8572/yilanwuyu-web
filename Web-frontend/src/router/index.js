import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Intro',
    component: () => import('../views/IntroPage.vue')
  },
  {
    path: '/select',
    name: 'AircraftSelect',
    component: () => import('../views/AircraftSelectPage.vue')
  },
  {
    path: '/aircraft/:id',
    name: 'AircraftDetail',
    component: () => import('../views/AircraftDetailPage.vue')
  },
  {
    path: '/interaction',
    name: 'CoreInteraction',
    component: () => import('../views/CoreInteractionPage.vue')
  },
  {
    path: '/parts',
    name: 'PartsIntro',
    component: () => import('../views/PartsIntroPage.vue')
  },
  {
    path: '/parts-3d',
    name: 'TailModel',
    component: () => import('../views/TailModelPage.vue')
  },
  {
    path: '/explode',
    name: 'ExplodeView',
    component: () => import('../views/ExplodeViewPage.vue')
  },
  {
    path: '/cockpit',
    name: 'Cockpit',
    component: () => import('../views/CockpitPage.vue')
  },
  {
    path: '/classroom',
    name: 'Classroom',
    component: () => import('../views/ClassroomPage.vue')
  },
  {
    path: '/quiz',
    name: 'Quiz',
    component: () => import('../views/QuizPage.vue')
  },
  {
    path: '/result',
    name: 'Result',
    component: () => import('../views/ResultPage.vue')
  },
  {
    path: '/z20/interaction',
    name: 'Z20CoreInteraction',
    component: () => import('../views/Z20CoreInteractionPage.vue')
  },
  {
    path: '/z20/parts',
    name: 'Z20PartsIntro',
    component: () => import('../views/Z20PartsIntroPage.vue')
  },
  {
    path: '/z20/parts-3d',
    name: 'Z20Model',
    component: () => import('../views/Z20ModelPage.vue')
  },
  {
    path: '/z20/explode',
    name: 'Z20ExplodeView',
    component: () => import('../views/Z20ExplodeViewPage.vue')
  },
  {
    path: '/z20/cockpit',
    name: 'Z20Cockpit',
    component: () => import('../views/Z20CockpitPage.vue')
  },
  {
    path: '/z20/classroom',
    name: 'Z20Classroom',
    component: () => import('../views/Z20ClassroomPage.vue')
  },
  {
    path: '/z20/quiz',
    name: 'Z20Quiz',
    component: () => import('../views/Z20QuizPage.vue')
  },
  {
    path: '/z20/result',
    name: 'Z20Result',
    component: () => import('../views/Z20ResultPage.vue')
  },
  {
    path: '/y20/interaction',
    name: 'Y20CoreInteraction',
    component: () => import('../views/Y20CoreInteractionPage.vue')
  },
  {
    path: '/y20/parts',
    name: 'Y20PartsIntro',
    component: () => import('../views/Y20PartsIntroPage.vue')
  },
  {
    path: '/y20/parts-3d',
    name: 'Y20Model',
    component: () => import('../views/Y20ModelPage.vue')
  },
  {
    path: '/y20/explode',
    name: 'Y20ExplodeView',
    component: () => import('../views/Y20ExplodeViewPage.vue')
  },
  {
    path: '/y20/cockpit',
    name: 'Y20Cockpit',
    component: () => import('../views/Y20CockpitPage.vue')
  },
  {
    path: '/y20/classroom',
    name: 'Y20Classroom',
    component: () => import('../views/Y20ClassroomPage.vue')
  },
  {
    path: '/y20/quiz',
    name: 'Y20Quiz',
    component: () => import('../views/Y20QuizPage.vue')
  },
  {
    path: '/y20/result',
    name: 'Y20Result',
    component: () => import('../views/Y20ResultPage.vue')
  },
  {
    path: '/j20/interaction',
    name: 'J20CoreInteraction',
    component: () => import('../views/J20CoreInteractionPage.vue')
  },
  {
    path: '/j20/parts',
    name: 'J20PartsIntro',
    component: () => import('../views/J20PartsIntroPage.vue')
  },
  {
    path: '/j20/parts-3d',
    name: 'J20Model',
    component: () => import('../views/J20ModelPage.vue')
  },
  {
    path: '/j20/explode',
    name: 'J20ExplodeView',
    component: () => import('../views/J20ExplodeViewPage.vue')
  },
  {
    path: '/j20/cockpit',
    name: 'J20Cockpit',
    component: () => import('../views/J20CockpitPage.vue')
  },
  {
    path: '/j20/classroom',
    name: 'J20Classroom',
    component: () => import('../views/J20ClassroomPage.vue')
  },
  {
    path: '/j20/quiz',
    name: 'J20Quiz',
    component: () => import('../views/J20QuizPage.vue')
  },
  {
    path: '/j20/result',
    name: 'J20Result',
    component: () => import('../views/J20ResultPage.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFoundPage.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
