export function assetPath(path) {
  return `/c919-assets/${path
    .replace(/^\/+/, '')
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/')}`
}
