#!/usr/bin/env node
/**
 * End-to-end acceptance harness for dsh-mobile.
 *
 * Runs every check that can be done without a phone: toolchain, APK artifacts and
 * their manifest, the bridge server's own end-to-end suite, the DSH-side wiring,
 * and the delivery documents. Device-side items live in
 * docs/verification/device-checklist.md.
 *
 * Usage:
 *   node tools/acceptance.mjs
 *   node tools/acceptance.mjs --skip-gradle          # no Android SDK needed
 *   node tools/acceptance.mjs --json out.json --quiet
 *
 * Exit code 1 when any check fails.
 */

import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync, mkdirSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const ANDROID_DIR = join(ROOT, 'packages/mobile-shell/android')
const BRIDGE_DIR = join(ROOT, 'packages/bridge-server')
// Toolchain and DSH state default under $HOME so the harness works for any user
// (see docs/DEPLOY.md). Override with ANDROID_HOME / JAVA_HOME / DSH_PROFILE_DIR.
const ANDROID_HOME = process.env.ANDROID_HOME ?? join(homedir(), '.local/android')
const BUILD_TOOLS = process.env.DSH_BUILD_TOOLS ?? join(ANDROID_HOME, 'build-tools/35.0.0')
const JAVA_HOME = process.env.JAVA_HOME ?? findJdk(ANDROID_HOME)
const PROFILE_DIR = process.env.DSH_PROFILE_DIR ?? join(homedir(), '.dsh/profiles/web')
const DSH_BIN = process.env.DSH_BIN ?? 'dsh'

/** Newest `jdk-*` under the toolchain dir, or the path itself if it is a JDK. */
function findJdk(base) {
  try {
    const entries = readdirSync(base).filter((name) => name.startsWith('jdk'))
    if (entries.length > 0) return join(base, entries.sort().pop())
  } catch {
    /* fall through: report the unresolved path and let the check fail loudly */
  }
  return join(base, 'jdk')
}

const argv = process.argv.slice(2)
const flags = new Set(argv.filter((a) => a.startsWith('--')))
const jsonFlag = argv.find((a) => a.startsWith('--json'))
const jsonPath = jsonFlag ? (jsonFlag.includes('=') ? jsonFlag.split('=')[1] : argv[argv.indexOf(jsonFlag) + 1]) : null
const quiet = flags.has('--quiet')

const checks = []
/**
 * Record one check.
 *
 * @param {string} section grouping label
 * @param {string} name assertion label
 * @param {boolean} ok whether it passed
 * @param {string} [detail] extra context
 * @returns {void}
 */
function check(section, name, ok, detail = '') {
  checks.push({ section, name, ok: Boolean(ok), detail })
  if (!quiet || !ok) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}${detail && !ok ? ` — ${detail}` : ''}`)
}

/**
 * Run a command, never throwing.
 *
 * @param {string} command binary
 * @param {string[]} args arguments
 * @param {Record<string, unknown>} [options] spawn options
 * @returns {{ status: number, stdout: string, stderr: string }} result
 */
function run(command, args, options = {}) {
  const res = spawnSync(command, args, {
    encoding: 'utf8',
    env: { ...process.env, PATH: `${JAVA_HOME}/bin:${process.env.PATH ?? ''}` },
    ...options,
  })
  return { status: res.status ?? -1, stdout: res.stdout ?? '', stderr: res.stderr ?? '' }
}

/**
 * sha256 of a file.
 *
 * @param {string} file path
 * @returns {string} hex digest
 */
function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

// ----- 1. toolchain ---------------------------------------------------------

if (!flags.has('--skip-gradle')) {
  console.log('Toolchain')
  check('toolchain', 'JAVA_HOME resolves', existsSync(join(JAVA_HOME, 'bin/java')), JAVA_HOME)
  const java = run(join(JAVA_HOME, 'bin/java'), ['-version'])
  check('toolchain', 'java runs', java.status === 0, (java.stderr || java.stdout).split('\n')[0])
  const aapt2 = join(BUILD_TOOLS, 'aapt2')
  check('toolchain', 'aapt2 present', existsSync(aapt2), aapt2)
  check('toolchain', 'apksigner present', existsSync(join(BUILD_TOOLS, 'apksigner')), BUILD_TOOLS)
}

// ----- 2. APK artifacts -----------------------------------------------------

console.log('APK artifacts')
const debugApk = join(ANDROID_DIR, 'app/build/outputs/apk/debug/app-debug.apk')
const releaseApk = join(ANDROID_DIR, 'app/build/outputs/apk/release/app-release.apk')
for (const [label, apk] of [['debug', debugApk], ['release', releaseApk]]) {
  const ok = existsSync(apk) && statSync(apk).size > 1024 * 1024
  check('apk', `${label} APK exists and is plausible`, ok, existsSync(apk) ? `${statSync(apk).size} B` : 'missing')
  if (ok) console.log(`       ${label}: ${(statSync(apk).size / 1024 / 1024).toFixed(2)} MiB sha256=${sha256(apk).slice(0, 16)}…`)
}

if (!flags.has('--skip-gradle') && existsSync(join(BUILD_TOOLS, 'apksigner'))) {
  const verify = run(join(BUILD_TOOLS, 'apksigner'), ['verify', '--print-certs', releaseApk])
  check('apk', 'release APK signature verifies', verify.status === 0 && verify.stdout.includes('Signer #1'), verify.stdout.split('\n')[0] ?? verify.stderr.trim())
  const dn = verify.stdout.match(/certificate DN: (.+)/)?.[1]
  if (dn) console.log(`       signer: ${dn.trim()}`)
}

// The bridge hands the release build to a logged-in phone browser; the copy in
// dist/ must be exactly the signed artifact, not a stale one.
const distDir = join(ROOT, 'dist')
const servedApks = existsSync(distDir) ? readdirSync(distDir).filter((name) => name.endsWith('.apk')) : []
check('apk', 'a servable APK exists in dist/', servedApks.length > 0, distDir)
for (const name of servedApks) {
  const served = join(distDir, name)
  const same = existsSync(releaseApk) && sha256(served) === sha256(releaseApk)
  check('apk', `dist/${name} matches the release build`, same, same ? `${statSync(served).size} B` : 'differs from app-release.apk')
}

// ----- 3. APK manifest ------------------------------------------------------

if (!flags.has('--skip-gradle') && existsSync(join(BUILD_TOOLS, 'aapt2'))) {
  console.log('APK manifest')
  const dump = run(join(BUILD_TOOLS, 'aapt2'), ['dump', 'xmltree', '--file', 'AndroidManifest.xml', releaseApk])
  const manifest = dump.stdout
  check('manifest', 'manifest dump succeeds', dump.status === 0 && manifest.length > 500, dump.stderr.trim().slice(0, 160))
  for (const component of ['MainActivity', 'SetupActivity', 'ShareTargetActivity', 'A11yService', 'BridgeService']) {
    check('manifest', `declares ${component}`, manifest.includes(`top.frankfurtlin.dshmobile.${component}`))
  }
  check('manifest', 'A11yService is bound by the system permission', manifest.includes('BIND_ACCESSIBILITY_SERVICE'))
  check('manifest', 'BridgeService declares a foreground service type', /foregroundServiceType\(0x01010599\)=0x0*10/.test(manifest))
  check('manifest', 'package visibility <queries> block present', manifest.includes('E: queries'))
  const sendFilters = (manifest.match(/android\.intent\.action\.SEND/g) ?? []).length
  check('manifest', 'share targets accept ACTION_SEND', sendFilters >= 3, `occurrences=${sendFilters}`)

  const badging = run(join(BUILD_TOOLS, 'aapt2'), ['dump', 'badging', releaseApk])
  check('manifest', 'applicationId is top.frankfurtlin.dshmobile', /package: name='top\.frankfurtlin\.dshmobile'/.test(badging.stdout), badging.stdout.split('\n')[0])
  check('manifest', 'app label is DSH Mobile', /application-label:'DSH Mobile'/.test(badging.stdout))
  check('manifest', 'requests FOREGROUND_SERVICE', badging.stdout.includes('android.permission.FOREGROUND_SERVICE'))
  check('manifest', 'requests POST_NOTIFICATIONS', badging.stdout.includes('android.permission.POST_NOTIFICATIONS'))
}

// ----- 4. bridge server -----------------------------------------------------

if (!flags.has('--skip-bridge')) {
  console.log('Bridge server')
  const smoke = run(process.execPath, ['tools/smoke.mjs'], { cwd: BRIDGE_DIR })
  const summary = smoke.stdout.match(/(\d+)\/(\d+) checks passed/)
  check('bridge', 'smoke suite passes completely', smoke.status === 0 && summary && summary[1] === summary[2], summary ? summary[0] : smoke.stderr.trim().slice(0, 200))
  if (summary) console.log(`       smoke: ${summary[0]}`)

  const sdk = run(process.execPath, ['tools/sdk-check.mjs'], { cwd: BRIDGE_DIR })
  const tools = sdk.stdout.match(/^tools: (.+)$/m)?.[1]?.split(', ') ?? []
  check('bridge', 'DSH MCP SDK client connects', sdk.status === 0 && sdk.stdout.includes('sdk-check: ok'), sdk.stderr.trim().slice(0, 200))
  check('bridge', 'registers 24 phone tools', tools.length === 24, `count=${tools.length}`)
  check('bridge', 'exposes the automation tools', ['phone_ui_tree', 'phone_screenshot', 'phone_tap', 'phone_swipe', 'phone_global', 'phone_apps', 'phone_launch', 'phone_a11y_status'].every((t) => tools.includes(t)), tools.join(','))
  check('bridge', 'exposes the phone file tools', ['phone_files_save', 'phone_files_open'].every((t) => tools.includes(t)), tools.join(','))
  check('bridge', 'exposes the browsable-folder tools', ['phone_fs_roots', 'phone_fs_list', 'phone_fs_read', 'phone_fs_write', 'phone_fs_mkdir'].every((t) => tools.includes(t)), tools.join(','))

  const parity = run(process.execPath, ['tools/check-protocol-parity.mjs'], { cwd: ROOT })
  const paritySummary = parity.stdout.match(/(\d+)\/(\d+) parity checks passed/)
  check('bridge', 'app, server and fake phone agree on every op', parity.status === 0 && paritySummary && paritySummary[1] === paritySummary[2], paritySummary ? paritySummary[0] : parity.stderr.trim().slice(0, 200))
  if (paritySummary) console.log(`       parity: ${paritySummary[0]}`)
}

// ----- 4b. app unit tests ---------------------------------------------------

if (!flags.has('--skip-gradle') && existsSync(join(JAVA_HOME, 'bin/java'))) {
  console.log('App unit tests')
  const testSource = join(ANDROID_DIR, 'app/src/test/java/top/frankfurtlin/dshmobile/EndpointTest.java')
  check('unit', 'EndpointTest present', existsSync(testSource))
  const gradle = run('./gradlew', ['testDebugUnitTest', '--console=plain'], {
    cwd: ANDROID_DIR,
    env: {
      ...process.env,
      JAVA_HOME,
      ANDROID_HOME,
      PATH: `${JAVA_HOME}/bin:${join(ANDROID_HOME, 'platform-tools')}:${process.env.PATH ?? ''}`,
    },
  })
  const resultsDir = join(ANDROID_DIR, 'app/build/test-results/testDebugUnitTest')
  let tests = 0
  let failures = 0
  let errors = 0
  if (existsSync(resultsDir)) {
    for (const name of readdirSync(resultsDir)) {
      if (!name.startsWith('TEST-') || !name.endsWith('.xml')) continue
      const xml = readFileSync(join(resultsDir, name), 'utf8')
      const attrs = xml.match(/<testsuite[^>]*>/)?.[0] ?? ''
      tests += Number(attrs.match(/tests="(\d+)"/)?.[1] ?? 0)
      failures += Number(attrs.match(/failures="(\d+)"/)?.[1] ?? 0)
      errors += Number(attrs.match(/errors="(\d+)"/)?.[1] ?? 0)
    }
  }
  check('unit', 'gradle unit test task succeeds', gradle.status === 0, gradle.stdout.split('\n').filter(Boolean).slice(-2).join(' | ').slice(0, 200))
  check('unit', 'unit tests all pass', tests >= 16 && failures === 0 && errors === 0, `tests=${tests} failures=${failures} errors=${errors}`)
  console.log(`       unit: ${tests} tests, ${failures} failures, ${errors} errors`)
}

// ----- 5. DSH-side wiring ---------------------------------------------------

console.log('DSH wiring')
// The profile only exists on a machine where dsh-mobile is actually deployed (or
// being developed against). A fresh clone legitimately has none, so this section
// reports "skipped" rather than failing.
const profilePkg = join(PROFILE_DIR, 'package.json')
if (!existsSync(profilePkg)) {
  console.log(`       skipped: no DSH profile at ${PROFILE_DIR} — set DSH_PROFILE_DIR to assert this host's wiring`)
} else {
  const pkg = JSON.parse(readFileSync(profilePkg, 'utf8'))
  const bundles = pkg.dsh?.profile?.bundles ?? []
  check('dsh', 'web profile depends on dsh-mobile-xc', Object.keys(pkg.dependencies ?? {}).includes('dsh-mobile-xc'), JSON.stringify(pkg.dependencies ?? {}))
  check('dsh', 'web profile bundles enable dsh-mobile-xc', bundles.includes('dsh-mobile-xc'), bundles.join(','))
  check('dsh', 'web profile depends on dsh-mobile-presentation', Object.keys(pkg.dependencies ?? {}).includes('dsh-mobile-presentation'), JSON.stringify(pkg.dependencies ?? {}))
  check('dsh', 'web profile bundles enable dsh-mobile-presentation', bundles.includes('dsh-mobile-presentation'), bundles.join(','))
}

// The presentation plugin's client half is inlined from styles/mobile.css, so a
// stale artifact would ship the previous stylesheet without any other symptom.
const presentationBuild = run(process.execPath, [join(ROOT, 'packages/mobile-presentation/scripts/build.mjs'), '--check'])
check('dsh', 'presentation client.js is in sync with styles/mobile.css', presentationBuild.status === 0, (presentationBuild.stderr || presentationBuild.stdout).trim().split('\n')[0])

for (const artifact of ['dsh-mobile-bridge.service', 'caddy-dsh-mobile.snippet', 'dsh-profile-mcp-phone.yml']) {
  check('deploy', `deploy/${artifact} present`, existsSync(join(ROOT, 'deploy', artifact)))
}

// The shipped deploy files are templates, so assert them for portability rather
// than comparing against whatever happens to be installed on this host.
const unitTemplate = readFileSync(join(ROOT, 'deploy/dsh-mobile-bridge.service'), 'utf8')
const caddyTemplate = readFileSync(join(ROOT, 'deploy/caddy-dsh-mobile.snippet'), 'utf8')
check('deploy', 'unit template declares the download dir', unitTemplate.includes('DSH_BRIDGE_DOWNLOAD_DIR=@INSTALL_DIR@/dist'))
// The placeholders are what make the templates portable; assert they are still
// used for every value that differs between hosts.
check('deploy', 'unit template uses @…@ placeholders', unitTemplate.includes('WorkingDirectory=@INSTALL_DIR@') && unitTemplate.includes('@NODE_BIN@'))
check('deploy', 'Caddy template declares @DOMAIN@', caddyTemplate.includes('@DOMAIN@'))

if (!flags.has('--skip-dsh') && existsSync(PROFILE_DIR)) {
  // Host-specific: assert the deployment described in docs/DEPLOY.md is actually
  // in place, not just that the artifacts exist in the repo.
  const installedUnit = '/etc/systemd/system/dsh-mobile-bridge.service'
  const liveCaddy = '/etc/caddy/Caddyfile'
  const livePatch = join(PROFILE_DIR, 'cordis.patch.yml')
  check('deploy', 'bridge unit is installed system-wide', existsSync(installedUnit), installedUnit)
  if (existsSync(installedUnit)) {
    // Placeholders are substituted at install time, so compare directives.
    const installed = readFileSync(installedUnit, 'utf8')
    check('deploy', 'installed unit runs the bridge entrypoint', /WorkingDirectory=\S+/.test(installed) && installed.includes('src/index.js'))
  }
  check('deploy', 'Caddy routes /bridge/* to the bridge', existsSync(liveCaddy) && /handle\s+\/bridge\//.test(readFileSync(liveCaddy, 'utf8')), liveCaddy)
  check('deploy', 'live profile carries the mcp-phone entry', existsSync(livePatch) && readFileSync(livePatch, 'utf8').includes('mcp-phone'), livePatch)
  if (existsSync(livePatch)) {
    check('deploy', 'live profile keeps an mcp-phone rollback copy', existsSync(`${livePatch}.rollback-no-mcp-phone`))
  }
}

if (!flags.has('--skip-dsh')) {
  const patchFile = join(ROOT, 'deploy/dsh-profile-mcp-phone.yml')
  if (existsSync(patchFile)) {
    const dump = run(DSH_BIN, ['--profile', process.env.DSH_SPIKE_PROFILE ?? 'mobspike', '--patch', patchFile, '--dump-config'])
    const ok = dump.status === 0 && /mcp-phone|phone/.test(dump.stdout)
    check('dsh', 'MCP patch parses under a shadow profile', ok, (dump.stderr || dump.stdout).trim().split('\n').slice(0, 2).join(' | ').slice(0, 200))
  }
}

// ----- 6. docs --------------------------------------------------------------

console.log('Docs')
for (const doc of [
  'README.md',
  'docs/PLAN.md',
  'docs/DEPLOY.md',
  'docs/ROLLBACK.md',
  'docs/DELIVERY.md',
  'docs/verification/device-checklist.md',
  'docs/verification/protocol-parity.json',
  'docs/adr/0001-mobile-ui-plugin-selection.md',
  'docs/adr/0002-app-shell-native-webview.md',
  'docs/adr/0003-agent-phone-bridge.md',
  'docs/adr/0004-no-root-automation.md',
  'docs/adr/0005-browsable-folders.md',
  'docs/adr/0006-artifact-download-route.md',
  'docs/adr/0007-mobile-presentation-layer.md',
  'packages/mobile-presentation/README.md',
  'docs/verification/presentation.json',
]) {
  check('docs', `${doc} present`, existsSync(join(ROOT, doc)))
}

// ----- summary --------------------------------------------------------------

const failed = checks.filter((c) => !c.ok)
const passed = checks.length - failed.length
const report = {
  generatedAt: new Date().toISOString(),
  host: process.env.HOSTNAME ?? '',
  root: ROOT,
  skipped: [...flags].filter((f) => f.startsWith('--skip')),
  summary: { passed, total: checks.length, failed: failed.length },
  checks,
}
const out = jsonPath ?? join(ROOT, `docs/verification/acceptance-${new Date().toISOString().replace(/[:.]/g, '-')}.json`)
mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, `${JSON.stringify(report, null, 2)}\n`)

console.log(`\n${passed}/${checks.length} acceptance checks passed`)
console.log(`report: ${out}`)
if (failed.length > 0) {
  console.log('\nfailures:')
  for (const item of failed) console.log(`  - [${item.section}] ${item.name}${item.detail ? ` — ${item.detail}` : ''}`)
}
process.exit(failed.length === 0 ? 0 : 1)
