const state = { consent: null, list: [], detail: null, view: 'overview' };

export function snapshot() { return state; }
export function setConsent(consent) { state.consent = consent; }
export function setList(list) { state.list = list; }
export function showDetail(detail) { state.detail = detail; state.view = 'detail'; }
export function leaveDetail() { state.detail = null; state.view = 'overview'; }
