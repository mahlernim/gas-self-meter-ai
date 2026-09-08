import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';

export const PACKAGE = 'dev.mahlernim.gasselfmeter';
export const UPLOAD_CERTIFICATE = 'dc388fe57d8374881701f849008a9b1b342d5ffc2ddeab1bffc03547833902e9';
const sha256 = /^[a-f0-9]{64}$/;
function requireThat(condition, message) { if (!condition) throw new Error(message); }

/** Creates a manifest only. The publisher's plan still verifies the actual JAR signature. */
export function createManifest({ metadata, artifact, digest, revision, versionName, versionCode, configuration, notes }) {
  requireThat(metadata?.package === PACKAGE, 'Package does not match this application.');
  requireThat(metadata.revision === revision && /^[a-f0-9]{40}$/.test(revision), 'Build source revision differs from this checkout.');
  requireThat(metadata.versionName === versionName && String(metadata.versionCode) === String(versionCode), 'Build version differs from app/build.gradle.kts.');
  requireThat(/^\d+\.\d+\.\d+$/.test(versionName) && /^[1-9]\d*$/.test(String(versionCode)), 'Invalid release version.');
  requireThat(sha256.test(digest) && digest === metadata.sha256, 'AAB bytes differ from release-verification.json.');
  const certificate = String(metadata.uploadCertificateSha256 ?? '').replaceAll(':', '').toLowerCase();
  requireThat(certificate === UPLOAD_CERTIFICATE, 'Original upload certificate is required. Never create a replacement key.');
  requireThat(typeof configuration === 'string' && sha256.test(configuration) && !/^0+$/.test(configuration), 'A reviewed Play configuration SHA-256 is required.');
  requireThat(typeof notes === 'string' && notes.trim().length > 0 && [...notes.trim()].length <= 500, 'Release notes must contain 1 to 500 characters.');
  return {
    packageName: PACKAGE,
    track: 'alpha',
    versionName,
    versionCode: String(versionCode),
    artifact: resolve(artifact),
    sha256: digest,
    uploadCertificateSha256: UPLOAD_CERTIFICATE,
    expectedConfigurationSha256: configuration,
    releaseNotes: [{ language: 'ko-KR', text: notes.trim() }],
  };
}

export function main(argv = process.argv.slice(2)) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const options = {};
  const accepted = new Set(['--configuration-sha256', '--artifact', '--metadata', '--notes', '--output']);
  for (let i = 0; i < argv.length; i += 2) {
    requireThat(accepted.has(argv[i]) && argv[i + 1] && !argv[i + 1].startsWith('--') && !(argv[i] in options), 'Invalid or duplicate option. See docs/PLAY_RELEASE.md.');
    options[argv[i]] = argv[i + 1];
  }
  const metadata = JSON.parse(readFileSync(resolve(options['--metadata'] ?? resolve(root, 'artifacts/release-verification.json')), 'utf8'));
  const gradle = readFileSync(resolve(root, 'app/build.gradle.kts'), 'utf8');
  const versionName = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1];
  const versionCode = /versionCode\s*=\s*(\d+)/.exec(gradle)?.[1];
  const artifact = resolve(options['--artifact'] ?? resolve(root, `artifacts/gas-self-meter-ai-${versionName}.aab`));
  const notesPath = resolve(options['--notes'] ?? resolve(root, `release-notes/${versionName}/ko-KR.txt`));
  const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  requireThat(!execFileSync('git', ['status', '--porcelain', '--untracked-files=no'], { cwd: root, encoding: 'utf8' }).trim(), 'Commit tracked changes before preparing a release manifest.');
  const manifest = createManifest({ metadata, artifact, digest: createHash('sha256').update(readFileSync(artifact)).digest('hex'),
    revision, versionName, versionCode, configuration: options['--configuration-sha256'], notes: readFileSync(notesPath, 'utf8') });
  const output = resolve(options['--output'] ?? resolve(root, 'artifacts/play-release.json'));
  mkdirSync(dirname(output), { recursive: true });
  // Never silently replace a manifest associated with an existing publisher journal.
  writeFileSync(output, JSON.stringify(manifest, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' });
  console.log(`Manifest prepared: ${output}`);
  console.log('No Play request was sent. Run publisher play plan, then the journaled upload/validate/submit stages.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
