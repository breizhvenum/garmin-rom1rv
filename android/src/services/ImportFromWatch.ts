// Copyright (C) 2026 breizhvenum. Licensed under the GNU GPL v3 (see LICENSE).
import { mtpList, mtpCopy, mtpClose } from '../native/GarminMtp';
import { importFitFromPath } from './ImportFit';
import * as Garmin from '../native/GarminModule';
import { isFrench as __isFr } from '../i18n';
function L(fr: string, en: string): string { return __isFr ? fr : en; } // hoisted: safe at module level

// Reads the newest activities of the watch over the USB cable (MTP, read-only) and imports the ones not
// seen yet. The names already handled are remembered so a file is never downloaded twice.
const SEEN_KEY = 'garmin_mtp_seen_v1';
function store(): any { try { return require('@react-native-async-storage/async-storage').default; } catch (e) { return null; } }
async function loadSeen(): Promise<string[]> {
  try { const st = store(); const v = st ? await st.getItem(SEEN_KEY) : null; return v ? JSON.parse(v) : []; } catch (e) { return []; }
}
async function saveSeen(names: string[]): Promise<void> {
  try { const st = store(); if (st) await st.setItem(SEEN_KEY, JSON.stringify(names.slice(-500))); } catch (e) { /* not kept: files are re-checked next time */ }
}

export interface WatchImportResult { onWatch: number; imported: number; exists: number; deleted: number; failed: number; skipped: number }

async function importFiles(
  names: string[], copy: (i: number) => Promise<string>, res: WatchImportResult,
): Promise<void> {
  res.onWatch = names.length;
  const seen = await loadSeen();
  for (let i = 0; i < names.length; i++) {
    const name = names[i];
    if (seen.includes(name)) { res.skipped++; continue; }
    try {
      const path = await copy(i);
      const r = await importFitFromPath(path);
      if (r.status === 'imported') res.imported++; else if (r.status === 'exists') res.exists++; else res.deleted++;
      seen.push(name);   // imported, already there or deleted by the user: no need to download it again
    } catch (e) { res.failed++; }   // e.g. a non-GPS activity (no track): shown as failed, retried next time
  }
  await saveSeen(seen);
}

/** Watch in "Stockage de masse" / USB Mass Storage mode (e.g. Forerunner 245): the watch is a disk, the
 *  .fit files are read from GARMIN/Activity. Not yet run against a real watch. */
async function importFromMassStorage(limit: number): Promise<WatchImportResult> {
  const res: WatchImportResult = { onWatch: 0, imported: 0, exists: 0, deleted: 0, failed: 0, skipped: 0 };
  try {
    await Garmin.connect();
    const files = await Garmin.listFitFiles(limit);
    await importFiles(files.map(f => f.name), i => Garmin.copyFitToCache(files[i].volumeIndex, files[i].name), res);
    if (files.length === 0) {
      throw new Error(L('Aucun fichier .fit trouvé dans GARMIN/Activity de la montre.', 'No .fit file found in the watch\'s GARMIN/Activity folder.'));
    }
  } finally {
    await Garmin.disconnect().catch(() => {});
  }
  return res;
}

export async function importFromWatch(limit = 10): Promise<WatchImportResult> {
  let mode: string = 'other';
  try { mode = await Garmin.usbMode(); } catch (e) { /* unknown: try MTP like before */ }
  if (mode === 'mass') return importFromMassStorage(limit);

  const res: WatchImportResult = { onWatch: 0, imported: 0, exists: 0, deleted: 0, failed: 0, skipped: 0 };
  try {
    const files = await mtpList(limit);
    await importFiles(files.map(f => f.name), i => mtpCopy(files[i].handle, files[i].name), res);
  } finally {
    await mtpClose().catch(() => {});
  }
  return res;
}
