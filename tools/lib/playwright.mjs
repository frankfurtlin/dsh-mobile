/**
 * Resolve a playwright install without pinning one machine's pnpm store path.
 *
 * The screenshot tools need playwright, but this repo deliberately has no
 * node_modules of its own — on a development box it is borrowed from whichever
 * checkout already has it. Rather than hard-coding that path, probe a few
 * candidates and let $PLAYWRIGHT_PATH override everything.
 */

import { createRequire } from 'node:module'
import { existsSync, readdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')

/** Every `playwright` a pnpm store under `base` could expose, newest last. */
function pnpmCandidates(base) {
  const store = join(base, 'node_modules/.pnpm')
  if (!existsSync(store)) return []
  return readdirSync(store)
    .filter((entry) => entry === 'playwright' || entry.startsWith('playwright@'))
    .sort()
    .map((entry) => join(store, entry, 'node_modules/playwright'))
}

/**
 * @returns {import('playwright').typeof import('playwright')} the playwright module
 * @throws when nothing usable is found, with the locations that were tried
 */
export function resolvePlaywright() {
  const requireFromRoot = createRequire(join(ROOT, 'package.json'))
  const bases = [
    process.env.DSH_HARNESS_DIR,
    ROOT,
    join(ROOT, '..'),
    homedir(),
    join(homedir(), 'deepseek-harness'),
    process.cwd(),
  ].filter(Boolean)

  const candidates = []
  if (process.env.PLAYWRIGHT_PATH) candidates.push(process.env.PLAYWRIGHT_PATH)
  for (const base of bases) {
    candidates.push(join(base, 'node_modules/playwright'), ...pnpmCandidates(base))
  }

  for (const candidate of candidates) {
    if (!existsSync(candidate)) continue
    try {
      return requireFromRoot(candidate)
    } catch {
      /* not loadable — keep looking */
    }
  }
  try {
    return requireFromRoot('playwright')
  } catch {
    throw new Error(
      `playwright not found; tried:\n  ${candidates.join('\n  ')}\n`
      + 'Install it in the repo or point PLAYWRIGHT_PATH at an existing install.',
    )
  }
}
