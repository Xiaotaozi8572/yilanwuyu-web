export function j20AssetPath(path) {
  return `/j20-assets/${path
    .replace(/^\/+/, '')
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/')}`
}
