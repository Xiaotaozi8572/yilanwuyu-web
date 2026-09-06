export function z20AssetPath(path) {
  return `/z20-assets/${path
    .replace(/^\/+/, '')
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/')}`
}
