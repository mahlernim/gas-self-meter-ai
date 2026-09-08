import test from 'node:test';
import assert from 'node:assert/strict';
import { createManifest, PACKAGE, UPLOAD_CERTIFICATE } from './prepare-play-release.mjs';

function input() {
  const revision = 'a'.repeat(40);
  const digest = 'b'.repeat(64);
  return { metadata: { package: PACKAGE, revision, versionName: '0.6.0', versionCode: 15, sha256: digest, uploadCertificateSha256: UPLOAD_CERTIFICATE },
    artifact: './synthetic.aab', digest, revision, versionName: '0.6.0', versionCode: '15', configuration: 'c'.repeat(64), notes: '합성 릴리스 안내' };
}

test('manifest is scoped to the existing alpha track and carries exact version/hash', () => {
  const result = createManifest(input());
  assert.equal(result.packageName, PACKAGE);
  assert.equal(result.track, 'alpha');
  assert.equal(result.versionCode, '15');
  assert.equal(result.sha256, 'b'.repeat(64));
  assert.equal(result.releaseNotes[0].language, 'ko-KR');
});
test('another application is rejected', () => {
  const x = input(); x.metadata.package = 'other.application'; assert.throws(() => createManifest(x), /Package/);
});
test('different source revision is rejected', () => {
  const x = input(); x.metadata.revision = 'd'.repeat(40); assert.throws(() => createManifest(x), /revision/);
});
test('different version is rejected', () => {
  const x = input(); x.metadata.versionCode = 16; assert.throws(() => createManifest(x), /version/);
});
test('changed AAB is rejected', () => {
  const x = input(); x.digest = 'd'.repeat(64); assert.throws(() => createManifest(x), /bytes/);
});
test('a different signing certificate is rejected', () => {
  const x = input(); x.metadata.uploadCertificateSha256 = 'd'.repeat(64); assert.throws(() => createManifest(x), /certificate/);
});
test('original colon-delimited uppercase fingerprint is accepted', () => {
  const x = input(); x.metadata.uploadCertificateSha256 = UPLOAD_CERTIFICATE.toUpperCase().match(/../g).join(':');
  assert.equal(createManifest(x).uploadCertificateSha256, UPLOAD_CERTIFICATE);
});
test('missing and placeholder Play configuration hashes are rejected', () => {
  for (const configuration of [undefined, '', '0'.repeat(64), 'bad']) assert.throws(() => createManifest({ ...input(), configuration }), /configuration/);
});
test('empty or oversized release notes are rejected', () => {
  for (const notes of ['', '  ', 'x'.repeat(501)]) assert.throws(() => createManifest({ ...input(), notes }), /notes/);
});
test('invalid version syntax is rejected', () => {
  const x = input(); x.versionName = x.metadata.versionName = 'unknown'; assert.throws(() => createManifest(x), /version/);
});
