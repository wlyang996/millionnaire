// Read the server's canonical metadata after save (ID allocation and trimmed names included).
export function characterMetadata(character) {
  return { avatar: character.id, name: character.name, sort: character.sort, enabled: character.enabled, fallback: character.fallback }
}
