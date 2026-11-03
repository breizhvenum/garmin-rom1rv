// Copyright (C) 2026 breizhvenum. Licensed under the GNU GPL v3 (see LICENSE).
// Part of rom1rv, derived from the Sommet project by skinnie (https://github.com/skinnie/sommet), also GPL v3.
// Garmin rom1rv — reads a watch-native .fit (Forerunner 255 etc.) with no dependency.
// Gives the same point shape as readSmlPoints (lat, lon, time ms, dopplerSpeed m/s, sat, hdop) so the
// Speed analysis runs unchanged, and builds a GPX (with the Doppler speed kept) from those points.

const FIT_EPOCH_MS = 631065600000;            // 1989-12-31T00:00:00Z
const SEMI = 180 / 2147483648;                 // semicircles -> degrees
const BASE: Record<number, { n: number; inv: number }> = {
  1: { n: 1, inv: 0x7f }, 2: { n: 1, inv: 0xff }, 3: { n: 2, inv: 0x7fff }, 4: { n: 2, inv: 0xffff },
  5: { n: 4, inv: 0x7fffffff }, 6: { n: 4, inv: 0xffffffff }, 10: { n: 1, inv: 0 }, 11: { n: 2, inv: 0 }, 12: { n: 4, inv: 0 },
};
interface Def { little: boolean; global: number; fields: { num: number; size: number; bt: number }[]; devSize: number }
export interface FitRecord { time: number; lat: number; lon: number; speed: number | null; alt: number | null; hr: number | null; dist: number | null }

function readUInt(v: DataView, o: number, n: number, little: boolean): number {
  if (n === 1) return v.getUint8(o);
  if (n === 2) return v.getUint16(o, little);
  return v.getUint32(o, little);
}

/** Parses the FIT file and returns the `record` messages (GPS samples) plus the sport name when present. */
export function parseFit(bytes: Uint8Array): { records: FitRecord[]; sport: string | null } {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes.length < 14) throw new Error('not a FIT file');
  const hl = v.getUint8(0);
  if (String.fromCharCode(bytes[8], bytes[9], bytes[10], bytes[11]) !== '.FIT') throw new Error('not a FIT file');
  const end = Math.min(bytes.length, hl + v.getUint32(4, true));
  const defs: Record<number, Def> = {};
  const records: FitRecord[] = [];
  let sport: string | null = null;
  let pos = hl, lastTs = 0;
  while (pos < end) {
    const h = v.getUint8(pos++);
    let d: Def, off = -1;
    if (h & 0x80) { d = defs[(h >> 5) & 3]; off = h & 0x1f; }
    else if (h & 0x40) {
      pos++;                                   // reserved
      const little = v.getUint8(pos++) === 0;
      const global = v.getUint16(pos, little); pos += 2;
      const nf = v.getUint8(pos++);
      const fields: Def['fields'] = [];
      for (let i = 0; i < nf; i++) { fields.push({ num: bytes[pos], size: bytes[pos + 1], bt: bytes[pos + 2] & 0x1f }); pos += 3; }
      let devSize = 0;
      if (h & 0x20) { const nd = v.getUint8(pos++); for (let i = 0; i < nd; i++) { devSize += bytes[pos + 1]; pos += 3; } }
      defs[h & 0x0f] = { little, global, fields, devSize };
      continue;
    } else d = defs[h & 0x0f];
    if (!d) throw new Error('corrupt FIT (undefined message)');
    const f: Record<number, number> = {};
    for (const fd of d.fields) {
      const b = BASE[fd.bt];
      if (b && fd.size === b.n) {
        let val: number;
        if (fd.bt === 1) val = v.getInt8(pos);
        else if (fd.bt === 3) val = v.getInt16(pos, d.little);
        else if (fd.bt === 5) val = v.getInt32(pos, d.little);
        else val = readUInt(v, pos, b.n, d.little);
        if (val !== b.inv) f[fd.num] = val;
      } else if (fd.bt === 7 && d.global === 12 && fd.num === 3) {
        let s = ''; for (let i = 0; i < fd.size && bytes[pos + i]; i++) s += String.fromCharCode(bytes[pos + i]);
        if (s) sport = s;
      }
      pos += fd.size;
    }
    pos += d.devSize;
    if (off >= 0) { let ts = (lastTs & ~0x1f) + off; if (ts < lastTs) ts += 0x20; f[253] = ts; }
    if (f[253] !== undefined) lastTs = f[253];
    if (d.global === 20 && f[0] !== undefined && f[1] !== undefined && f[253] !== undefined) {
      const sp = f[73] !== undefined ? f[73] / 1000 : f[6] !== undefined ? f[6] / 1000 : null;   // enhanced_speed, else speed (m/s)
      const alt = f[78] !== undefined ? f[78] / 5 - 500 : f[2] !== undefined ? f[2] / 5 - 500 : null;
      records.push({ time: FIT_EPOCH_MS + f[253] * 1000, lat: f[0] * SEMI, lon: f[1] * SEMI, speed: sp, alt, hr: f[3] ?? null, dist: f[5] !== undefined ? f[5] / 100 : null });
    }
  }
  if (records.length < 2) throw new Error('not enough GPS points');
  return { records, sport };
}

/** Points for analyseSession(): the watch's Doppler speed is kept as dopplerSpeed (m/s). */
export function fitToPoints(bytes: Uint8Array): any[] {
  return parseFit(bytes).records
    .filter(r => Math.abs(r.lat) <= 90 && Math.abs(r.lon) <= 180)
    .map(r => ({ lat: r.lat, lon: r.lon, time: r.time, dopplerSpeed: r.speed, sat: null, hdop: null }));
}

/** GPX 1.1 built from the FIT; the Doppler speed (m/s) stays in <gpxtpx:speed> next to the heart rate. */
export function fitToGpx(bytes: Uint8Array, name = 'Garmin rom1rv'): string {
  const { records } = parseFit(bytes);
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  const out: string[] = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<gpx version="1.1" creator="Garmin rom1rv" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">',
    `<trk><name>${esc(name)}</name><trkseg>`,
  ];
  for (const r of records) {
    let ext = '';
    if (r.speed !== null) ext += `<gpxtpx:speed>${r.speed.toFixed(3)}</gpxtpx:speed>`;
    if (r.hr !== null) ext += `<gpxtpx:hr>${r.hr}</gpxtpx:hr>`;
    out.push(`<trkpt lat="${r.lat.toFixed(7)}" lon="${r.lon.toFixed(7)}">` +
      (r.alt !== null ? `<ele>${r.alt.toFixed(1)}</ele>` : '') + `<time>${new Date(r.time).toISOString()}</time>` +
      (ext ? `<extensions><gpxtpx:TrackPointExtension>${ext}</gpxtpx:TrackPointExtension></extensions>` : '') + '</trkpt>');
  }
  out.push('</trkseg></trk></gpx>');
  return out.join('\n');
}

/** base64 (as RNFS.readFile(path, 'base64') returns it) -> bytes, without relying on atob. */
export function base64ToBytes(b64: string): Uint8Array {
  const T = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const clean = b64.replace(/[^A-Za-z0-9+/]/g, '');
  const out = new Uint8Array(Math.floor(clean.length * 3 / 4));
  let o = 0, acc = 0, bits = 0;
  for (let i = 0; i < clean.length; i++) {
    acc = (acc << 6) | T.indexOf(clean[i]); bits += 6;
    if (bits >= 8) { bits -= 8; out[o++] = (acc >> bits) & 0xff; acc &= (1 << bits) - 1; }
  }
  return out;
}
