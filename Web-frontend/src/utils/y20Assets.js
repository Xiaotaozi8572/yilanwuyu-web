export function y20AssetPath(path) {
  return `/y20-assets/${path
    .replace(/^\/+/, '')
    .split('/')
    .map((part) => encodeURIComponent(part))
    .join('/')}`
}
