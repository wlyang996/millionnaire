import { compareVersions } from './versionComparison.js'

// The reviewed snapshot is also the published payload; later edits cannot change it.
export function createReleaseReview(before, settings, expectedActiveId) {
  const original=JSON.parse(JSON.stringify(before)), draft=JSON.parse(JSON.stringify(settings))
  return {settings:draft,expectedActiveId,groups:compareVersions(original,draft)}
}
